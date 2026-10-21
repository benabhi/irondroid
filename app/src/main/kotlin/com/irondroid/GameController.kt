package com.irondroid

import android.util.Log
import com.irondroid.data.CampaignStore
import com.irondroid.data.session.Autosave
import com.irondroid.data.session.GameSession
import com.irondroid.data.session.TurnRecorder
import com.irondroid.engine.action.ActionDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the campaign being played: its [GameSession], the [Autosave] listening to the action
 * dispatcher, so every action that changes the game is saved (IRON-34), and the [TurnRecorder]
 * that logs every call and makes undo possible (IRON-41). One campaign at a time.
 */
class GameController(
    private val store: CampaignStore,
    private val dispatcher: ActionDispatcher,
    private val scope: CoroutineScope,
) {
    private class OpenGame(val session: GameSession, val autosave: Autosave, val turns: TurnRecorder)

    private var current: OpenGame? = null

    /** Opens [campaignId], closing any other one first. Null if the campaign or its character is gone. */
    suspend fun open(campaignId: Long): GameSession? {
        current?.takeIf { it.session.campaignId == campaignId }?.let { return it.session }
        close()
        val session = GameSession.open(store, campaignId) ?: return null
        val turns = TurnRecorder.open(store, session, dispatcher.choices, scope) { Log.e(TAG, "Turn history failed for campaign $campaignId", it) }
        val autosave = Autosave(store, session, scope) { Log.e(TAG, "Autosave failed for campaign $campaignId", it) }
        dispatcher.addListener(turns.history)
        dispatcher.addListener(autosave)
        current = OpenGame(session, autosave, turns)
        return session
    }

    /** Stops playing the open campaign; pending saves are still written. */
    fun close() {
        current?.let {
            dispatcher.removeListener(it.autosave)
            dispatcher.removeListener(it.turns.history)
            it.autosave.close()
            it.turns.close()
            // An open choice belongs to this game; the turn history undoes it when the game is opened again.
            dispatcher.choices.cancel()
        }
        current = null
    }

    /** Writes pending saves now, as when the app goes to the background. */
    fun flush() {
        val open = current ?: return
        scope.launch {
            open.turns.flush()
            open.autosave.flush()
        }
    }

    private companion object {
        const val TAG = "irondroid"
    }
}
