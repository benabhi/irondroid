package com.irondroid.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/** How the player wants to play, apart from the AI choices. */
class PlaySettings(private val preferences: SharedPreferences) {
    /** Whether the narrator's moves wait for the player before the dice (RF-ENV-04). On unless the player turns it off. */
    var confirmMoves: Boolean
        get() = preferences.getBoolean(KEY_CONFIRM_MOVES, true)
        set(value) = preferences.edit { putBoolean(KEY_CONFIRM_MOVES, value) }

    companion object {
        const val FILE_NAME = "play_settings"
        private const val KEY_CONFIRM_MOVES = "confirm_moves"
    }
}
