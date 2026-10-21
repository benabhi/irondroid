package com.irondroid.data.session

import com.irondroid.data.CampaignStore
import com.irondroid.engine.character.Character
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The campaign being played: its character sheet in memory, where the action handlers read and
 * change it. [Autosave] writes it back to the database after every action that changes the game.
 */
class GameSession(val campaignId: Long, val characterName: String, sheet: Character) {
    private val state = MutableStateFlow(sheet)

    /** The current sheet; screens observe it. */
    val sheet: StateFlow<Character> = state.asStateFlow()

    /** Replaces the sheet with what [change] returns. Safe to call from any thread. */
    fun update(change: (Character) -> Character) = state.update(change)

    companion object {
        /** Opens a saved campaign, or returns null if it has no character (or no longer exists). */
        suspend fun open(store: CampaignStore, campaignId: Long): GameSession? =
            store.loadCharacter(campaignId)?.let { GameSession(campaignId, it.name, it.sheet) }
    }
}
