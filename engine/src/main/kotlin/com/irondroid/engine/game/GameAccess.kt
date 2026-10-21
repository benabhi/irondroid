package com.irondroid.engine.game

import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.action.ActionRejectedException
import com.irondroid.engine.character.Character

/**
 * The open game, as the actions see it. The app implements it over the campaign being played, so
 * the engine does not know where games are stored.
 */
interface GameAccess {
    /** The character sheet of the open game; throws [ActionRejectedException] when no game is open. */
    val sheet: Character

    /** The character's name; throws [ActionRejectedException] when no game is open. */
    val characterName: String

    /** The turn history of the open game, or null when none is open. */
    val history: ActionHistory<Character>?

    /** Replaces the sheet with what [change] returns. */
    fun update(change: (Character) -> Character)

    /** Writes the game to storage now, without waiting for the autosave. */
    fun save()

    companion object {
        /** The error every action gives when there is no game to act on. */
        fun noGame() = ActionRejectedException.state("No game is open", "The player has to open a campaign first")
    }
}
