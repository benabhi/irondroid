package com.irondroid.game

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irondroid.ai.AiException
import com.irondroid.ai.ModelPricing
import com.irondroid.ai.narrator.MoveConfirmer
import com.irondroid.ai.narrator.MoveDecision
import com.irondroid.ai.narrator.MoveProposal
import com.irondroid.ai.narrator.MoveSuggester
import com.irondroid.ai.narrator.Narrator
import com.irondroid.ai.narrator.NarratorStep
import com.irondroid.ai.narrator.PastTurn
import com.irondroid.ai.narrator.TurnEvent
import com.irondroid.ai.narrator.TurnOutcome
import com.irondroid.data.CampaignStore
import com.irondroid.data.NewHistoryEntry
import com.irondroid.data.entity.CharacterGender
import com.irondroid.data.entity.HistoryEntryKind
import com.irondroid.dice.ThrownDie
import com.irondroid.engine.action.ActionDispatcher
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.DispatchResult
import com.irondroid.engine.action.PendingChoice
import com.irondroid.engine.action.StateChange
import com.irondroid.engine.action.UndoRole
import com.irondroid.engine.character.Character
import com.irondroid.engine.character.Stat
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.game.GameActions
import com.irondroid.engine.move.MoveCatalog
import com.irondroid.engine.move.MoveCategory
import com.irondroid.engine.move.MoveRoll
import com.irondroid.engine.move.SourcePick
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One turn on the play screen: what the player said, the actions run and the narration. */
/**
 * A roll or oracle answer of a turn, as its history card keeps it: the engine's [summary] (for the
 * AI), the result's [data] and the [changes] to the game, from which the screen draws its card in the
 * player's language (IRON-63).
 */
data class StepCard(val action: String, val summary: String, val data: JsonObject?, val changes: List<StateChange> = emptyList())

/** A move of the picker (IRON-64): [trigger] is the first sentence of when it is made, in the player's language. */
data class PickerMove(val id: String, val name: String, val category: MoveCategory, val trigger: String)

/**
 * The hand edits of the sheet panel (IRON-66): debilities to [mark] and [clear], the new value of each
 * meter by name (`health`, `spirit`, `supply`, `momentum`), and the new [stats] when they changed.
 */
data class SheetEdit(
    val meters: Map<String, Int> = emptyMap(),
    val mark: Set<Debility> = emptySet(),
    val clear: Set<Debility> = emptySet(),
    val stats: Map<Stat, Int>? = null,
)

data class PlayedTurn(
    val playerInput: String?,
    val steps: List<StepCard>,
    val narration: String,
    /** Why the narrator did not answer, when it failed. */
    val error: AiException? = null,
    /** Tokens, time and cost of the turn (IRON-111), when known. */
    val metrics: TurnMetrics? = null,
    /** Whether the player wrote this turn's Pay the Price, so the screen offers to apply it to the sheet (IRON-66). */
    val ownPrice: Boolean = false,
    /** While the turn plays, what the narrator is doing when it is not writing (IRON-62). */
    val activity: NarratorActivity? = null,
)

/** What the narrator is doing between pieces of text (IRON-62). */
sealed interface NarratorActivity {
    /** Waiting for the model, which thinks before it writes or calls an action. */
    data object Thinking : NarratorActivity

    /** Running [action] through the engine, such as `roll_move`. */
    data class Acting(val action: String) : NarratorActivity
}

/**
 * Plays the turns of the open campaign (IRON-52) and keeps what the play screen shows. Every turn is
 * saved with its narration and the cards of the moves and oracles it rolled, so the story is there
 * when the campaign is opened again.
 */
class TurnPlayer(
    private val campaignId: Long,
    private val store: CampaignStore,
    private val dispatcher: ActionDispatcher,
    private val moves: MoveCatalog,
    /** Language of the move names. */
    private val language: String,
    /** Whether moves wait for the player before the dice (RF-ENV-04), read at each roll. */
    private val confirmMoves: () -> Boolean,
    /** Makes the narrator that plays with this player (it asks before rolling); null when the AI has no API key yet. */
    narrator: ((TurnPlayer) -> Narrator)?,
    /** Waits for the pending writes of the open game, so the turn's calls get its number. */
    private val flush: suspend () -> Unit,
    /** The calls of the open game, to know what undoing a turn takes back (IRON-57). */
    private val history: () -> ActionHistory<Character>?,
    /** Prices of the model in use, to estimate what each turn costs (IRON-111). */
    private val pricing: () -> ModelPricing? = { null },
    /** Asks the AI which moves fit the scene when the move picker opens (IRON-64); null without an API key. */
    private val suggester: MoveSuggester? = null,
    /** The sheet of the open game, to apply hand edits as targets (IRON-66). */
    private val sheet: () -> Character? = { null },
    /** Shows the dice of a roll and returns once they stop (IRON-72); the card of the roll waits for it. */
    private val showDice: suspend (List<ThrownDie>) -> Unit = {},
) : MoveConfirmer {
    /** A move waiting for the player, with the answer the narrator waits on. */
    class PendingMove(val proposal: MoveProposal, internal val answer: CompletableDeferred<MoveDecision>)

    private val narrator: Narrator? = narrator?.invoke(this)

    /** The move the narrator wants to roll, waiting for the player (IRON-53). */
    var pendingMove by mutableStateOf<PendingMove?>(null)
        private set
    /** The saved turns, oldest first, then the one being played. */
    val turns = mutableStateListOf<PlayedTurn>()

    /** The turn being played, or null. */
    var live by mutableStateOf<PlayedTurn?>(null)
        private set

    /**
     * The sheet as of what the screen has shown of the turn being played, or null to show the game's
     * own: the engine changes the sheet the moment it rolls, and the status bar must not tell the
     * result, such as a +1 momentum, while the dice still roll (IRON-72).
     */
    var shownSheet by mutableStateOf<Character?>(null)
        private set

    /** The choice the player has to make, if any (RF-ENV-11). */
    var choice by mutableStateOf<PendingChoice?>(null)
        private set

    var loaded by mutableStateOf(false)
        private set

    /** How the narrator refers to the character (IRON-56); null until the player picks. */
    var gender by mutableStateOf<CharacterGender?>(null)
        private set

    suspend fun changeGender(value: CharacterGender) {
        store.setCharacterGender(campaignId, value)
        gender = value
    }

    val busy: Boolean get() = live != null

    val hasNarrator: Boolean get() = narrator != null

    suspend fun load() {
        val history = store.history(campaignId)
        val cards = history.entries.groupBy { it.turnId }
        turns.clear()
        history.turns.forEach { turn ->
            turns += PlayedTurn(
                turn.playerInput,
                cards[turn.id].orEmpty().mapNotNull { fromPayload(it.payload) },
                turn.narration.orEmpty(),
                metrics = TurnMetrics.fromJson(turn.metrics),
            )
        }
        choice = dispatcher.choices.current
        gender = store.characterGender(campaignId)
        loaded = true
    }

    /** A move the player asked for with the buttons: it rolls without asking again (RF-ENV-05). */
    private var requested: MoveProposal? = null

    /** Plays [move] with [source], asked for with the buttons and shown as [said] (RF-ENV-05). */
    suspend fun requestMove(move: String, source: String?, said: String) {
        val narrator = narrator ?: return
        if (busy) return
        requested = MoveProposal(move, source)
        try {
            run(said) { onEvent -> narrator.requestMove(said, move, source, past(), onEvent) }
        } finally {
            requested = null
        }
    }

    /** Asks the oracle [question] with [odds], from the buttons, shown as [said] (RF-ENV-05). */
    suspend fun requestOracle(question: String, odds: String, said: String) {
        val narrator = narrator ?: return
        if (busy) return
        run(said) { onEvent -> narrator.requestOracle(said, question, odds, past(), onEvent) }
    }

    /**
     * Asks the yes/no oracle without the narrator (IRON-65): the engine answers at once and the
     * answer stays as a card of its own turn, which the narrator reads in the next turn's context.
     */
    suspend fun askOracleOnly(question: String, odds: String, said: String) {
        if (busy) return
        val arguments = buildJsonObject {
            put("question", question)
            put("odds", odds)
        }.toString()
        val result = dispatcher.dispatch(ASK_ORACLE, arguments, Caller.PLAYER)
        if (result !is DispatchResult.Success) {
            Log.w(TAG, "ask_oracle failed: $result")
            return
        }
        flush()
        val step = NarratorStep(ASK_ORACLE, arguments, result.result.summary, true, result.result.data, result.result.changes)
        diceOf(step)?.let { showDice(it) }
        store.appendTurn(campaignId, said, null, listOfNotNull(card(step)), null)
        turns += PlayedTurn(said, listOfNotNull(cardSummary(step)), "")
    }

    /**
     * Applies the player's hand edits to the sheet (IRON-66, RF-PJ-04) through the engine, as the
     * player: debilities first, since they move momentum's maximum, then each meter's difference,
     * then the stats. They stay as one turn with a card of every change. Returns whether any applied.
     */
    suspend fun applySheetEdit(edit: SheetEdit): Boolean {
        if (busy) return false
        val steps = mutableListOf<NarratorStep>()
        fun call(name: String, arguments: JsonObject, confirmed: Boolean = false) {
            val result = dispatcher.dispatch(name, arguments.toString(), Caller.PLAYER, confirmed)
            if (result is DispatchResult.Success) {
                steps += NarratorStep(name, arguments.toString(), result.result.summary, true, result.result.data, result.result.changes)
            } else {
                Log.w(TAG, "$name failed: $result")
            }
        }
        edit.clear.forEach { call("clear_debility", buildJsonObject { put("debility", it.name.lowercase()) }) }
        edit.mark.forEach { call("mark_debility", buildJsonObject { put("debility", it.name.lowercase()) }) }
        // Targets, not differences: marking a debility may already have lowered momentum.
        val now = sheet()
        edit.meters.forEach { (meter, target) ->
            val current = when (meter) {
                "momentum" -> now?.momentum?.value
                else -> now?.meters?.get(MeterType.valueOf(meter.uppercase()))?.value
            } ?: return@forEach
            if (target != current) {
                call("adjust_meter", buildJsonObject {
                    put("meter", meter)
                    put("amount", target - current)
                })
            }
        }
        // Stats by hand are a sensitive edit: the player's own tap is the confirmation (RF-API-08).
        edit.stats?.let { stats -> call("set_stats", buildJsonObject { stats.forEach { (stat, value) -> put(stat.name.lowercase(), value) } }, confirmed = true) }
        if (steps.isEmpty()) return false
        flush()
        val edited = NarratorStep(MANUAL_EDIT, "", "Edited by hand", true, JsonObject(emptyMap()), steps.flatMap { it.changes })
        store.appendTurn(campaignId, null, null, listOfNotNull(card(edited)), null)
        turns += PlayedTurn(null, listOfNotNull(cardSummary(edited)), "")
        return true
    }

    /** Plays the player's [input]. */
    suspend fun play(input: String) {
        val narrator = narrator ?: return
        if (busy || input.isBlank()) return
        run(input.trim()) { onEvent -> narrator.play(input.trim(), past(), onEvent) }
    }

    /**
     * The player picks [optionIds] of the open choice, shown to them as [chosen] (or the consequence
     * they wrote themselves); the narrator tells what follows.
     */
    suspend fun choose(optionIds: List<String>, chosen: String, ownConsequence: Boolean = false) {
        val narrator = narrator ?: return
        val open = choice ?: return
        if (busy) return
        val arguments = buildJsonObject {
            put("choice", open.id)
            put("options", JsonArray(optionIds.map { JsonPrimitive(it) }))
        }
        val result = dispatcher.dispatch("choose_option", arguments.toString(), Caller.PLAYER)
        if (result !is DispatchResult.Success) {
            Log.w(TAG, "choose_option failed: $result")
            return
        }
        choice = dispatcher.choices.current
        // What the picked options changed goes on a card of the turn, before the narrator's own (IRON-63).
        val picked = NarratorStep(CHOOSE_OPTION, arguments.toString(), result.result.summary, true, result.result.data, result.result.changes)
        run(chosen, listOf(picked)) { onEvent -> narrator.afterChoice(chosen, result.result.toJson().toString(), past(), ownConsequence, onEvent) }
        // A price the player wrote does not change the sheet by itself: the turn offers to apply it by hand (IRON-66).
        if (ownConsequence) turns.lastOrNull()?.let { turns[turns.lastIndex] = it.copy(ownPrice = true) }
    }

    /** Whether there is a turn to undo or correct now. */
    val canTakeBack: Boolean get() = !busy && turns.isNotEmpty() && pendingMove == null && !endsWithHandStats

    /** Stats changed by hand cannot be undone by the engine (RF-API-08), so neither can their turn (IRON-66). */
    private val endsWithHandStats: Boolean
        get() = turns.lastOrNull()?.steps.orEmpty().any { step -> step.action == MANUAL_EDIT && step.changes.any { it.field.startsWith("stats.") } }

    /**
     * Undoes the last turn (RF-ENV-09): its rolls and sheet changes through the engine's undo, and
     * its narration. A turn that answered a choice belongs to the move that opened it, so undoing it
     * also takes back the turn of that move. Returns the turns taken back, oldest first.
     */
    suspend fun undoLastTurn(): List<PlayedTurn> {
        if (!canTakeBack) return emptyList()
        flush()
        val last = store.lastTurnNumber(campaignId)
        // Reads and undo calls change nothing (and an undo is logged under the turn after the one it took back).
        val calls = store.actionCalls(campaignId).filter { !it.undone && it.undoRole != UndoRole.NONE }
        fun firstCall(number: Int) = calls.filter { it.turnNumber == number }.minByOrNull { it.sequence }
        // A turn that only answered a choice goes back with the turn of the move that opened it.
        // A sensitive edit in these turns cannot be undone: leave the story as it is rather than drop its card.
        if (calls.any { it.turnNumber == last && it.undoRole == UndoRole.BARRIER }) return emptyList()
        var from = last
        while (from > 1 && firstCall(from)?.actionName == CHOOSE_OPTION) from--
        val first = (from..last).firstNotNullOfOrNull { firstCall(it) }?.sequence
        if (first != null) {
            while ((history()?.lastUndoableSequence ?: 0) >= first) {
                val undo = dispatcher.dispatch(GameActions.UNDO, "", Caller.PLAYER)
                if (undo !is DispatchResult.Success) {
                    Log.w(TAG, "undo failed: $undo")
                    break
                }
            }
        }
        flush()
        val taken = turns.takeLast(last - from + 1)
        repeat(last - from + 1) { store.deleteLastTurn(campaignId) }
        load()
        return taken
    }

    /**
     * Undoes the last turn and plays it again with the player's [correction] of the narration
     * (RF-ENV-09), such as "no, my character cannot swim".
     */
    suspend fun correctLastTurn(correction: String) {
        val narrator = narrator ?: return
        if (correction.isBlank()) return
        val taken = undoLastTurn().takeIf { it.isNotEmpty() } ?: return
        val input = taken.first().playerInput.orEmpty()
        val previous = taken.joinToString("\n\n") { it.narration }
        val shown = correctionInput(input, correction.trim())
        run(shown) { onEvent -> narrator.correct(input, previous, correction.trim(), past(), onEvent) }
    }

    /**
     * Plays a turn; [before] are steps the player already ran for it, such as picking options.
     *
     * What the narrator does reaches the screen in order through a queue: a roll's dice are shown
     * first, and its card, and whatever the narrator does next, wait until they stop (IRON-72), so
     * neither the card nor the narration gives the result away while the dice still roll.
     */
    private suspend fun run(input: String, before: List<NarratorStep> = emptyList(), play: suspend ((TurnEvent) -> Unit) -> TurnOutcome) {
        live = PlayedTurn(input, before.mapNotNull(::cardSummary), "", activity = NarratorActivity.Thinking)
        shownSheet = sheet()
        val outcome = coroutineScope {
            // Each event with the sheet as it was right after it, which the screen shows once the event shows.
            val events = Channel<Pair<TurnEvent, Character?>>(Channel.UNLIMITED)
            val shown = launch { for ((event, after) in events) show(event, after) }
            val outcome = play { events.trySend(it to sheet()) }
            events.close()
            shown.join()
            outcome
        }
        shownSheet = null
        Log.i(TAG, "turn effort=${outcome.effort} steps=${outcome.steps.size} firstText=${outcome.firstTextMs}ms total=${outcome.totalMs}ms usage=${outcome.usage} error=${outcome.error?.kind}")
        // Where the time went (IRON-62): per request, the first output, the first text and the end, from the turn start.
        outcome.rounds.forEachIndexed { i, round ->
            Log.i(TAG, "round ${i + 1}: start=${round.startMs}ms output=${round.firstOutputMs}ms text=${round.firstTextMs}ms end=${round.endMs}ms calls=${round.toolCalls}")
        }
        outcome.steps.forEach { Log.i(TAG, "${it.action} ${it.arguments} → ${it.summary}") }
        flush()
        val metrics = TurnMetrics.of(outcome, pricing(), Instant.now())
        val steps = before + outcome.steps
        store.appendTurn(campaignId, input, outcome.narration.ifBlank { null }, steps.mapNotNull(::card), metrics?.toJson())
        turns += PlayedTurn(input, steps.mapNotNull(::cardSummary), outcome.narration, outcome.error, metrics)
        live = null
        choice = dispatcher.choices.current
    }

    private suspend fun show(event: TurnEvent, sheetAfter: Character?) {
        if (event is TurnEvent.Step) diceOf(event.step)?.let { showDice(it) }
        shownSheet = sheetAfter
        val current = live ?: return
        live = when (event) {
            is TurnEvent.Text -> current.copy(narration = current.narration + event.text, activity = null)
            is TurnEvent.Step -> current.copy(steps = current.steps + listOfNotNull(cardSummary(event.step)))
            TurnEvent.Thinking -> current.copy(activity = NarratorActivity.Thinking)
            is TurnEvent.Acting -> current.copy(activity = NarratorActivity.Acting(event.action))
        }
    }

    override fun needsConfirmation(move: String): Boolean = confirmMoves() && moves[move]?.let { it.roll !is MoveRoll.None } == true

    override suspend fun confirm(proposal: MoveProposal): MoveDecision {
        // The player already chose this move with the buttons: no need to ask again.
        requested?.takeIf { it.move == proposal.move }?.let { asked ->
            requested = null
            return MoveDecision.Roll(asked.move, asked.source ?: proposal.source)
        }
        val pending = PendingMove(proposal, CompletableDeferred())
        pendingMove = pending
        return try {
            pending.answer.await()
        } finally {
            pendingMove = null
        }
    }

    /** The player's answer to the move waiting. */
    fun answer(decision: MoveDecision) {
        pendingMove?.answer?.complete(decision)
    }

    /** The moves the player can switch to: those with dice, by name. */
    val rollableMoves: List<Pair<String, String>> by lazy {
        moves.moves.filter { it.roll !is MoveRoll.None }
            .map { it.id to (moves.text(it.id, language)?.name ?: it.id) }
            .sortedBy { it.second }
    }

    /** The moves of the picker (IRON-64): those with dice, with their category and when they are made. */
    val pickerMoves: List<PickerMove> by lazy {
        moves.moves.filter { it.roll !is MoveRoll.None }.map { move ->
            val text = moves.text(move.id, language)
            PickerMove(move.id, text?.name ?: move.id, move.category, firstSentence(text?.trigger.orEmpty()))
        }.sortedBy { it.name }
    }

    /** The moves the AI suggests for the scene, best first; null while it is asked (IRON-64). */
    var suggestedMoves by mutableStateOf<List<String>?>(emptyList())
        private set
    private var suggestedAfter = -1

    /** Asks the AI for the moves that fit the last turn, once per turn. */
    suspend fun loadSuggestions() {
        val suggester = suggester ?: return
        val last = turns.lastOrNull() ?: return
        if (suggestedAfter == turns.size) return
        suggestedAfter = turns.size
        suggestedMoves = null
        val rollable = pickerMoves.map { it.id }.toSet()
        val started = System.currentTimeMillis()
        suggestedMoves = suggester.suggest(last.narration, last.playerInput).filter { it in rollable }
        Log.i(TAG, "suggested moves $suggestedMoves in ${System.currentTimeMillis() - started}ms")
    }

    /** The name of [move] in the player's language. */
    fun moveName(move: String): String = moves.text(move, language)?.name ?: move

    /** The stats or meters the player can pick for [move]; empty when the move picks it itself. */
    fun sources(move: String): List<String> {
        val roll = moves[move]?.roll as? MoveRoll.Action ?: return emptyList()
        return if (roll.pick == SourcePick.ANY) roll.sources.map { it.name.lowercase() } else emptyList()
    }

    private fun past() = turns.map { turn ->
        // A question answered without narration (IRON-65) carries its answer, so the narrator does not ask it again.
        val answered = turn.steps.takeIf { turn.narration.isBlank() }?.firstOrNull { it.action == ASK_ORACLE }
        val input = if (answered != null) "${turn.playerInput}\n\n" + ANSWERED_NOTE.format(answered.summary) else turn.playerInput
        PastTurn(input, turn.narration.ifBlank { null })
    }

    companion object {
        private const val TAG = "Turn"
        private const val CHOOSE_OPTION = "choose_option"
        private const val ASK_ORACLE = "ask_oracle"
        private const val MANUAL_EDIT = "manual_edit"

        /** Model-facing note on a question the player asked the oracle without narration. */
        private const val ANSWERED_NOTE = "(The player asked the yes/no oracle without narration and the engine answered: %s. This answer is final: use it and do not ask again.)"

        /** The first sentence of a move's trigger, such as "When you attempt something risky…, roll." */
        fun firstSentence(trigger: String): String {
            val line = trigger.lineSequence().first()
            val end = line.indexOf(". ")
            return if (end < 0) line else line.substring(0, end + 1)
        }

        /** What a corrected turn keeps as the player's words: the action and, below, the correction. */
        fun correctionInput(input: String, correction: String) = "$input\n\n✎ $correction"
        private val json = Json

        /** The kind of card a step leaves in the history, or null for reads and suggestions. */
        private fun kind(step: NarratorStep): HistoryEntryKind? = when {
            !step.succeeded -> null
            step.action == "roll_move" || step.action == "roll_progress" -> HistoryEntryKind.MOVE
            step.action == "ask_oracle" || step.action == "roll_oracle_table" -> HistoryEntryKind.ORACLE
            step.action == "mark_progress" || step.action == "create_progress_track" -> HistoryEntryKind.SHEET_CHANGE
            (step.action == CHOOSE_OPTION || step.action == MANUAL_EDIT) && step.changes.isNotEmpty() -> HistoryEntryKind.SHEET_CHANGE
            else -> null
        }

        private fun cardSummary(step: NarratorStep): StepCard? =
            StepCard(step.action, step.summary, step.data, step.changes).takeIf { kind(step) != null }

        private fun card(step: NarratorStep): NewHistoryEntry? = kind(step)?.let { kind ->
            NewHistoryEntry(
                kind,
                buildJsonObject {
                    put("action", step.action)
                    put("arguments", step.arguments)
                    put("summary", step.summary)
                    step.data?.let { put("data", it) }
                    if (step.changes.isNotEmpty()) put("changes", JsonArray(step.changes.map { it.toJson() }))
                }.toString(),
            )
        }

        private fun fromPayload(payload: String): StepCard? = try {
            (json.parseToJsonElement(payload) as? JsonObject)?.let { card ->
                StepCard(
                    card["action"]?.jsonPrimitive?.content.orEmpty(),
                    card["summary"]?.jsonPrimitive?.content ?: return@let null,
                    card["data"] as? JsonObject,
                    (card["changes"] as? JsonArray).orEmpty().mapNotNull { change ->
                        val fields = change as? JsonObject ?: return@mapNotNull null
                        StateChange(
                            fields["field"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            fields["before"] ?: JsonNull,
                            fields["after"] ?: JsonNull,
                        )
                    },
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
