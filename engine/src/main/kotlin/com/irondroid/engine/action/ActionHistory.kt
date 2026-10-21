package com.irondroid.engine.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a logged call ended. */
enum class CallOutcome { SUCCESS, FAILURE, NEEDS_CONFIRMATION }

/** The part a call plays in undoing. */
enum class UndoRole {
    /** Nothing to undo: it read, failed, only rolled dice, or was the undo itself. */
    NONE,

    /** A play action that changed the game or opened a choice: undoing goes back to before it. */
    START,

    /** Done while the choice of a [START] was open, such as picking an option: undone with it. */
    PART,

    /** A sensitive action that changed the game: nothing before it can be undone. */
    BARRIER,
}

/**
 * One call in the turn history (RF-API-09): who made it, what it asked and what came of it.
 * [details] is the result (see [ActionResult.toJson]) or the error. [before] is the game state
 * just before a [UndoRole.START]; [choiceOpenAfter] says whether the player was left with a choice.
 */
data class LoggedCall<S>(
    val sequence: Int,
    val name: String,
    val caller: Caller,
    val arguments: JsonObject,
    val outcome: CallOutcome,
    val details: JsonObject,
    val role: UndoRole,
    val before: S? = null,
    val choiceOpenAfter: Boolean = false,
    val undone: Boolean = false,
)

/** Hears what the history records, so it can be saved. */
interface ActionHistoryListener<S> {
    fun onLogged(call: LoggedCall<S>)

    fun onUndone(sequences: List<Int>)
}

/**
 * The calls of a game and how to undo them (RF-API-09, RF-API-10). It listens to the dispatcher,
 * logs every call, and keeps the state before each play action so [undo] can return to it.
 *
 * A play action that changes the game or opens a choice starts an undo unit; what follows while
 * its choice is open joins that unit, so undoing a move also drops the options picked for it.
 * Sensitive actions are a barrier: undoing stops there. [snapshot] reads the current state and
 * [restore] puts one back; states must be immutable. Calls to [undoAction] are logged but are
 * never undone themselves.
 */
class ActionHistory<S>(
    private val choices: PendingChoices,
    private val snapshot: () -> S,
    private val restore: (S) -> Unit,
    private val undoAction: String = "undo",
) : ActionListener {
    private val lock = Any()
    private val log = mutableListOf<LoggedCall<S>>()
    private val listeners = mutableListOf<ActionHistoryListener<S>>()
    private var nextSequence = 1
    private var before: S? = null

    /** Every call so far, oldest first. */
    val calls: List<LoggedCall<S>> get() = synchronized(lock) { log.toList() }

    fun addListener(listener: ActionHistoryListener<S>) = synchronized(lock) { listeners += listener }

    fun removeListener(listener: ActionHistoryListener<S>) = synchronized(lock) { listeners -= listener }

    /** Puts back a saved history, as when a game is opened again. */
    fun load(calls: List<LoggedCall<S>>) = synchronized(lock) {
        log.clear()
        log += calls.sortedBy { it.sequence }
        nextSequence = (log.maxOfOrNull { it.sequence } ?: 0) + 1
    }

    /** Whether the last unit left a choice open; such a game cannot go on from where it is. */
    private fun openUnitStart(): Int? {
        val last = log.lastOrNull { !it.undone && it.role != UndoRole.NONE } ?: return null
        if (!last.choiceOpenAfter) return null
        return log.indexOfLast { !it.undone && it.role == UndoRole.START && it.sequence <= last.sequence }.takeIf { it >= 0 }
    }

    /**
     * Undoes the last unit if it left a choice open, as after reopening the app: an open choice
     * lives in memory only, so the game goes back to before the action that opened it.
     * Returns whether something was undone.
     */
    fun recoverOpenChoice(): Boolean = synchronized(lock) {
        val start = openUnitStart() ?: return false
        undoFrom(start)
        true
    }

    /** Whether [undo] has something to undo now. */
    val canUndo: Boolean get() = synchronized(lock) { lastUnitStart() != null }

    /**
     * Goes back to before the last unit and returns the calls undone, oldest first. Drops the open
     * choice, if any. Throws [ActionRejectedException] when there is nothing to undo.
     */
    fun undo(): List<LoggedCall<S>> = synchronized(lock) {
        val start = lastUnitStart() ?: run {
            val barrier = log.lastOrNull { !it.undone && it.role != UndoRole.NONE }
            throw if (barrier?.role == UndoRole.BARRIER) {
                ActionRejectedException.state("${barrier.name} cannot be undone, nor anything before it")
            } else {
                ActionRejectedException.state("There is nothing to undo")
            }
        }
        undoFrom(start)
    }

    private fun lastUnitStart(): Int? {
        for (index in log.indices.reversed()) {
            val call = log[index]
            if (call.undone) continue
            when (call.role) {
                UndoRole.NONE, UndoRole.PART -> continue
                UndoRole.BARRIER -> return null
                UndoRole.START -> return index
            }
        }
        return null
    }

    private fun undoFrom(start: Int): List<LoggedCall<S>> {
        restore(checkNotNull(log[start].before) { "Call ${log[start].sequence} has no state to go back to" })
        choices.cancel()
        val undone = mutableListOf<LoggedCall<S>>()
        for (index in start until log.size) {
            val call = log[index]
            if (call.undone || call.role == UndoRole.NONE) continue
            log[index] = call.copy(undone = true)
            undone += log[index]
        }
        val sequences = undone.map { it.sequence }
        listeners.toList().forEach { runCatching { it.onUndone(sequences) } }
        return undone
    }

    override fun beforeAction(call: ActionCall, action: ActionDefinition) {
        synchronized(lock) { before = if (choices.current == null) snapshot() else null }
    }

    override fun afterAction(result: DispatchResult.Success) = Unit

    override fun onResult(result: DispatchResult) {
        synchronized(lock) {
            val call = result.call
            val (outcome, details, role) = when (result) {
                is DispatchResult.Success -> Triple(CallOutcome.SUCCESS, result.result.toJson(), roleOf(result))
                is DispatchResult.Failure -> Triple(CallOutcome.FAILURE, result.error.toJson(), UndoRole.NONE)
                is DispatchResult.NeedsConfirmation -> Triple(CallOutcome.NEEDS_CONFIRMATION, JsonObject(emptyMap()), UndoRole.NONE)
            }
            val logged = LoggedCall(
                sequence = nextSequence++,
                name = call.name,
                caller = call.caller,
                arguments = call.arguments,
                outcome = outcome,
                details = details,
                role = role,
                before = before.takeIf { role == UndoRole.START },
                choiceOpenAfter = choices.current != null,
            )
            before = null
            log += logged
            listeners.toList().forEach { runCatching { it.onLogged(logged) } }
        }
    }

    private fun roleOf(success: DispatchResult.Success): UndoRole {
        val action = success.action
        val opensChoice = success.result.pendingChoice != null
        return when {
            action.name == undoAction -> UndoRole.NONE
            before == null && (action.answersChoice || opensChoice || action.changesState) -> UndoRole.PART
            action.permission == ActionPermission.SENSITIVE && action.changesState -> UndoRole.BARRIER
            action.permission.undoable && (action.changesState || opensChoice) -> UndoRole.START
            else -> UndoRole.NONE
        }
    }
}

/** The error as JSON, to log it or send it to the AI. */
fun ActionError.toJson(): JsonObject = buildJsonObject {
    put("code", code.name.lowercase())
    put("message", message)
    put("details", JsonArray(details.map(::JsonPrimitive)))
    suggestion?.let { put("suggestion", it) }
}
