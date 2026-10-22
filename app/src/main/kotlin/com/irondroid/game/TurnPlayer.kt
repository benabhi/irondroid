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
import com.irondroid.ai.narrator.Narrator
import com.irondroid.ai.narrator.NarratorStep
import com.irondroid.ai.narrator.PastTurn
import com.irondroid.ai.narrator.TurnEvent
import com.irondroid.ai.narrator.TurnOutcome
import com.irondroid.data.CampaignStore
import com.irondroid.data.NewHistoryEntry
import com.irondroid.data.entity.CharacterGender
import com.irondroid.data.entity.HistoryEntryKind
import com.irondroid.engine.action.ActionDispatcher
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.DispatchResult
import com.irondroid.engine.action.PendingChoice
import com.irondroid.engine.action.UndoRole
import com.irondroid.engine.character.Character
import com.irondroid.engine.game.GameActions
import com.irondroid.engine.move.MoveCatalog
import com.irondroid.engine.move.MoveRoll
import com.irondroid.engine.move.SourcePick
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One turn on the play screen: what the player said, the actions run and the narration. */
/**
 * A roll or oracle answer of a turn, as its history card keeps it: the engine's [summary] (for the
 * AI) and the result's [data], from which the screen writes its own line in the player's language.
 */
data class StepCard(val action: String, val summary: String, val data: JsonObject?)

data class PlayedTurn(
    val playerInput: String?,
    val steps: List<StepCard>,
    val narration: String,
    /** Why the narrator did not answer, when it failed. */
    val error: AiException? = null,
    /** Tokens, time and cost of the turn (IRON-111), when known. */
    val metrics: TurnMetrics? = null,
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
        run(chosen) { onEvent -> narrator.afterChoice(chosen, result.result.toJson().toString(), past(), ownConsequence, onEvent) }
    }

    /** Whether there is a turn to undo or correct now. */
    val canTakeBack: Boolean get() = !busy && turns.isNotEmpty() && pendingMove == null

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

    private suspend fun run(input: String, play: suspend ((TurnEvent) -> Unit) -> TurnOutcome) {
        live = PlayedTurn(input, emptyList(), "", activity = NarratorActivity.Thinking)
        val outcome = play { event ->
            val current = live ?: return@play
            live = when (event) {
                is TurnEvent.Text -> current.copy(narration = current.narration + event.text, activity = null)
                is TurnEvent.Step -> current.copy(steps = current.steps + listOfNotNull(cardSummary(event.step)))
                TurnEvent.Thinking -> current.copy(activity = NarratorActivity.Thinking)
                is TurnEvent.Acting -> current.copy(activity = NarratorActivity.Acting(event.action))
            }
        }
        Log.i(TAG, "turn effort=${outcome.effort} steps=${outcome.steps.size} firstText=${outcome.firstTextMs}ms total=${outcome.totalMs}ms usage=${outcome.usage} error=${outcome.error?.kind}")
        // Where the time went (IRON-62): per request, the first output, the first text and the end, from the turn start.
        outcome.rounds.forEachIndexed { i, round ->
            Log.i(TAG, "round ${i + 1}: start=${round.startMs}ms output=${round.firstOutputMs}ms text=${round.firstTextMs}ms end=${round.endMs}ms calls=${round.toolCalls}")
        }
        outcome.steps.forEach { Log.i(TAG, "${it.action} ${it.arguments} → ${it.summary}") }
        flush()
        val metrics = TurnMetrics.of(outcome, pricing(), Instant.now())
        store.appendTurn(campaignId, input, outcome.narration.ifBlank { null }, outcome.steps.mapNotNull(::card), metrics?.toJson())
        turns += PlayedTurn(input, outcome.steps.mapNotNull(::cardSummary), outcome.narration, outcome.error, metrics)
        live = null
        choice = dispatcher.choices.current
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

    /** The name of [move] in the player's language. */
    fun moveName(move: String): String = moves.text(move, language)?.name ?: move

    /** The stats or meters the player can pick for [move]; empty when the move picks it itself. */
    fun sources(move: String): List<String> {
        val roll = moves[move]?.roll as? MoveRoll.Action ?: return emptyList()
        return if (roll.pick == SourcePick.ANY) roll.sources.map { it.name.lowercase() } else emptyList()
    }

    private fun past() = turns.map { PastTurn(it.playerInput, it.narration.ifBlank { null }) }

    companion object {
        private const val TAG = "Turn"
        private const val CHOOSE_OPTION = "choose_option"

        /** What a corrected turn keeps as the player's words: the action and, below, the correction. */
        fun correctionInput(input: String, correction: String) = "$input\n\n✎ $correction"
        private val json = Json

        /** The kind of card a step leaves in the history, or null for reads and suggestions. */
        private fun kind(step: NarratorStep): HistoryEntryKind? = when {
            !step.succeeded -> null
            step.action == "roll_move" || step.action == "roll_progress" -> HistoryEntryKind.MOVE
            step.action == "ask_oracle" || step.action == "roll_oracle_table" -> HistoryEntryKind.ORACLE
            step.action == "mark_progress" || step.action == "create_progress_track" -> HistoryEntryKind.SHEET_CHANGE
            else -> null
        }

        private fun cardSummary(step: NarratorStep): StepCard? = StepCard(step.action, step.summary, step.data).takeIf { kind(step) != null }

        private fun card(step: NarratorStep): NewHistoryEntry? = kind(step)?.let { kind ->
            NewHistoryEntry(
                kind,
                buildJsonObject {
                    put("action", step.action)
                    put("arguments", step.arguments)
                    put("summary", step.summary)
                    step.data?.let { put("data", it) }
                }.toString(),
            )
        }

        private fun fromPayload(payload: String): StepCard? = try {
            (json.parseToJsonElement(payload) as? JsonObject)?.let { card ->
                StepCard(
                    card["action"]?.jsonPrimitive?.content.orEmpty(),
                    card["summary"]?.jsonPrimitive?.content ?: return@let null,
                    card["data"] as? JsonObject,
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
