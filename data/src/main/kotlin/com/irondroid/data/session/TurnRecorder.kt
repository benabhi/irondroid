package com.irondroid.data.session

import com.irondroid.data.CampaignStore
import com.irondroid.data.NewActionCall
import com.irondroid.data.entity.ActionCallEntity
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.action.ActionHistoryListener
import com.irondroid.engine.action.LoggedCall
import com.irondroid.engine.action.PendingChoices
import com.irondroid.engine.character.Character
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Keeps the turn history of the open game in the database (RF-API-09, RF-API-10): every call the
 * [history] logs and every undo are written in order, one at a time, in [scope].
 *
 * Add [history] to the action dispatcher as a listener. The undo action (IRON-43) calls
 * [ActionHistory.undo]; the restored sheet is saved by the autosave like any other change.
 */
class TurnRecorder private constructor(
    private val store: CampaignStore,
    private val campaignId: Long,
    val history: ActionHistory<Character>,
    scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
) : ActionHistoryListener<Character> {
    private sealed interface Request {
        class Write(val block: suspend () -> Unit) : Request

        class Flush(val done: CompletableDeferred<Unit>) : Request
    }

    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val worker: Job = scope.launch {
        for (request in requests) {
            when (request) {
                is Request.Write -> try {
                    request.block()
                } catch (e: Exception) {
                    onError(e)
                }
                is Request.Flush -> request.done.complete(Unit)
            }
        }
    }

    override fun onLogged(call: LoggedCall<Character>) {
        val row = NewActionCall(
            sequence = call.sequence,
            actionName = call.name,
            caller = call.caller,
            arguments = call.arguments.toString(),
            outcome = call.outcome,
            details = call.details.toString(),
            undoRole = call.role,
            before = call.before?.let { json.encodeToString(Character.serializer(), it) },
            choiceOpenAfter = call.choiceOpenAfter,
            undone = call.undone,
        )
        requests.trySend(Request.Write { store.appendActionCall(campaignId, row) })
    }

    override fun onUndone(sequences: List<Int>) {
        requests.trySend(Request.Write { store.markActionCallsUndone(campaignId, sequences) })
    }

    /** Waits until everything logged so far is written. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        if (requests.trySend(Request.Flush(done)).isSuccess) done.await()
    }

    /** Stops taking new writes; pending ones are still written. */
    fun close() {
        history.removeListener(this)
        requests.close()
    }

    /** Waits for the last pending write after [close]. */
    suspend fun join() = worker.join()

    companion object {
        private val json = Json

        /**
         * Loads the saved history of [session]'s campaign and starts recording. A choice left open
         * when the game was last closed cannot go on, so the game goes back to before the action
         * that opened it, and that sheet is saved.
         */
        suspend fun open(
            store: CampaignStore,
            session: GameSession,
            choices: PendingChoices,
            scope: CoroutineScope,
            onError: (Throwable) -> Unit = {},
        ): TurnRecorder {
            val history = ActionHistory(choices, snapshot = { session.sheet.value }, restore = { sheet -> session.update { sheet } })
            history.load(store.actionCalls(session.campaignId).map(::toLogged))
            val recorder = TurnRecorder(store, session.campaignId, history, scope, onError)
            history.addListener(recorder)
            if (history.recoverOpenChoice()) {
                recorder.flush()
                store.saveCharacter(session.campaignId, session.sheet.value)
            }
            return recorder
        }

        private fun toLogged(row: ActionCallEntity) = LoggedCall(
            sequence = row.sequence,
            name = row.actionName,
            caller = row.caller,
            arguments = json.parseToJsonElement(row.arguments) as JsonObject,
            outcome = row.outcome,
            details = json.parseToJsonElement(row.details) as JsonObject,
            role = row.undoRole,
            before = row.before?.let { json.decodeFromString(Character.serializer(), it) },
            choiceOpenAfter = row.choiceOpenAfter,
            undone = row.undone,
        )
    }
}
