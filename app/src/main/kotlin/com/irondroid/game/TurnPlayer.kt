package com.irondroid.game

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irondroid.ai.AiException
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
import com.irondroid.data.entity.HistoryEntryKind
import com.irondroid.engine.action.ActionDispatcher
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.DispatchResult
import com.irondroid.engine.action.PendingChoice
import com.irondroid.engine.move.MoveCatalog
import com.irondroid.engine.move.MoveRoll
import com.irondroid.engine.move.SourcePick
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One turn on the play screen: what the player said, the actions run and the narration. */
data class PlayedTurn(
    val playerInput: String?,
    val steps: List<String>,
    val narration: String,
    /** Why the narrator did not answer, when it failed. */
    val error: AiException? = null,
)

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
    /** Makes the narrator that asks this player before rolling; null when the AI has no API key yet. */
    narrator: ((MoveConfirmer) -> Narrator)?,
    /** Waits for the pending writes of the open game, so the turn's calls get its number. */
    private val flush: suspend () -> Unit,
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

    val busy: Boolean get() = live != null

    val hasNarrator: Boolean get() = narrator != null

    suspend fun load() {
        val history = store.history(campaignId)
        val cards = history.entries.groupBy { it.turnId }
        turns.clear()
        history.turns.forEach { turn ->
            turns += PlayedTurn(turn.playerInput, cards[turn.id].orEmpty().mapNotNull { summary(it.payload) }, turn.narration.orEmpty())
        }
        choice = dispatcher.choices.current
        loaded = true
    }

    /** Plays the player's [input]. */
    suspend fun play(input: String) {
        val narrator = narrator ?: return
        if (busy || input.isBlank()) return
        run(input.trim()) { onEvent -> narrator.play(input.trim(), past(), onEvent) }
    }

    /** The player picks [optionIds] of the open choice; the narrator tells what follows. */
    suspend fun choose(optionIds: List<String>) {
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
        val chosen = open.options.filter { it.id in optionIds }.joinToString(" · ") { it.text }
        run(chosen) { onEvent -> narrator.afterChoice(chosen, result.result.toJson().toString(), past(), onEvent) }
    }

    private suspend fun run(input: String, play: suspend ((TurnEvent) -> Unit) -> TurnOutcome) {
        live = PlayedTurn(input, emptyList(), "")
        val outcome = play { event ->
            val current = live ?: return@play
            live = when (event) {
                is TurnEvent.Text -> current.copy(narration = current.narration + event.text)
                is TurnEvent.Step -> current.copy(steps = current.steps + listOfNotNull(cardSummary(event.step)))
            }
        }
        Log.i(TAG, "turn steps=${outcome.steps.size} firstText=${outcome.firstTextMs}ms total=${outcome.totalMs}ms usage=${outcome.usage} error=${outcome.error?.kind}")
        outcome.steps.forEach { Log.i(TAG, "${it.action} ${it.arguments} → ${it.summary}") }
        flush()
        store.appendTurn(campaignId, input, outcome.narration.ifBlank { null }, outcome.steps.mapNotNull(::card))
        turns += PlayedTurn(input, outcome.steps.mapNotNull(::cardSummary), outcome.narration, outcome.error)
        live = null
        choice = dispatcher.choices.current
    }

    override fun needsConfirmation(move: String): Boolean = confirmMoves() && moves[move]?.let { it.roll !is MoveRoll.None } == true

    override suspend fun confirm(proposal: MoveProposal): MoveDecision {
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
        private val json = Json

        /** The kind of card a step leaves in the history, or null for reads and suggestions. */
        private fun kind(step: NarratorStep): HistoryEntryKind? = when {
            !step.succeeded -> null
            step.action == "roll_move" || step.action == "roll_progress" -> HistoryEntryKind.MOVE
            step.action == "ask_oracle" || step.action == "roll_oracle_table" -> HistoryEntryKind.ORACLE
            step.action == "mark_progress" || step.action == "create_progress_track" -> HistoryEntryKind.SHEET_CHANGE
            else -> null
        }

        private fun cardSummary(step: NarratorStep): String? = step.summary.takeIf { kind(step) != null }

        private fun card(step: NarratorStep): NewHistoryEntry? = kind(step)?.let { kind ->
            NewHistoryEntry(
                kind,
                buildJsonObject {
                    put("action", step.action)
                    put("arguments", step.arguments)
                    put("summary", step.summary)
                }.toString(),
            )
        }

        private fun summary(payload: String): String? = try {
            (json.parseToJsonElement(payload) as? JsonObject)?.get("summary")?.jsonPrimitive?.content
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
