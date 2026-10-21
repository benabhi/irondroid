package com.irondroid.data.session

import com.irondroid.data.CampaignStore
import com.irondroid.engine.action.ActionListener
import com.irondroid.engine.action.DispatchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Saves the game after every action that changes it (RF-PAR-02). Listens to the action dispatcher;
 * actions that only read are ignored.
 *
 * Saving runs in [scope], one save at a time, and always writes the latest sheet of [session]:
 * several actions in a row while a save is running end in one more save, never in a lost change.
 */
class Autosave(
    private val store: CampaignStore,
    private val session: GameSession,
    scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
) : ActionListener {
    private sealed interface Request {
        data object Save : Request

        class Flush(val done: CompletableDeferred<Unit>) : Request
    }

    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val worker: Job = scope.launch {
        var dirty = false
        for (request in requests) {
            // Coalesce whatever queued up meanwhile: one save covers every change so far.
            var flushes = listOfNotNull((request as? Request.Flush)?.done)
            dirty = dirty || request is Request.Save
            while (true) {
                val next = requests.tryReceive().getOrNull() ?: break
                if (next is Request.Flush) flushes = flushes + next.done else dirty = true
            }
            if (dirty) {
                dirty = false
                try {
                    store.saveCharacter(session.campaignId, session.sheet.value)
                } catch (e: Exception) {
                    onError(e)
                }
            }
            flushes.forEach { it.complete(Unit) }
        }
    }

    override fun afterAction(result: DispatchResult.Success) {
        if (result.action.changesState) requestSave()
    }

    /** Asks for a save of the current sheet, for changes made outside an action. */
    fun requestSave() {
        requests.trySend(Request.Save)
    }

    /** Waits until every save asked for so far is written, as before the app goes to the background. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        if (requests.trySend(Request.Flush(done)).isSuccess) done.await()
    }

    /** Stops listening for new saves; pending ones are still written. */
    fun close() {
        requests.close()
    }

    /** Waits for the last pending save after [close]. */
    suspend fun join() = worker.join()
}
