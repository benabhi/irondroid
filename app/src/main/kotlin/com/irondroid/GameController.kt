package com.irondroid

import android.util.Log
import com.irondroid.data.CampaignStore
import com.irondroid.data.session.Autosave
import com.irondroid.data.session.GameSession
import com.irondroid.engine.action.ActionDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the campaign being played: its [GameSession] and the [Autosave] listening to the action
 * dispatcher, so every action that changes the game is saved (IRON-34). One campaign at a time.
 */
class GameController(
    private val store: CampaignStore,
    private val dispatcher: ActionDispatcher,
    private val scope: CoroutineScope,
) {
    private class OpenGame(val session: GameSession, val autosave: Autosave)

    private var current: OpenGame? = null

    /** Opens [campaignId], closing any other one first. Null if the campaign or its character is gone. */
    suspend fun open(campaignId: Long): GameSession? {
        current?.takeIf { it.session.campaignId == campaignId }?.let { return it.session }
        close()
        val session = GameSession.open(store, campaignId) ?: return null
        val autosave = Autosave(store, session, scope) { Log.e(TAG, "Autosave failed for campaign $campaignId", it) }
        dispatcher.addListener(autosave)
        current = OpenGame(session, autosave)
        return session
    }

    /** Stops playing the open campaign; pending saves are still written. */
    fun close() {
        current?.let {
            dispatcher.removeListener(it.autosave)
            it.autosave.close()
        }
        current = null
    }

    /** Writes pending saves now, as when the app goes to the background. */
    fun flush() {
        val autosave = current?.autosave ?: return
        scope.launch { autosave.flush() }
    }

    private companion object {
        const val TAG = "irondroid"
    }
}
