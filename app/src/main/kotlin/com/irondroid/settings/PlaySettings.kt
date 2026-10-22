package com.irondroid.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.irondroid.ai.ReasoningEffort
import com.irondroid.theme.ThemeMode

/** How the player wants to play, apart from the AI choices. */
class PlaySettings(private val preferences: SharedPreferences) {
    /** Whether the narrator's moves wait for the player before the dice (RF-ENV-04). On unless the player turns it off. */
    var confirmMoves: Boolean
        get() = preferences.getBoolean(KEY_CONFIRM_MOVES, true)
        set(value) = preferences.edit { putBoolean(KEY_CONFIRM_MOVES, value) }

    /** How hard the narrator thinks (IRON-109); low unless the player picks another. */
    var narratorEffort: ReasoningEffort
        get() = preferences.getString(KEY_NARRATOR_EFFORT, null)?.let { saved -> ReasoningEffort.entries.firstOrNull { it.name == saved } } ?: ReasoningEffort.LOW
        set(value) = preferences.edit { putString(KEY_NARRATOR_EFFORT, value.name) }

    /** Whether each turn shows its tokens, time and cost under the narration (IRON-111); off unless turned on. */
    var showMetrics: Boolean
        get() = preferences.getBoolean(KEY_SHOW_METRICS, false)
        set(value) = preferences.edit { putBoolean(KEY_SHOW_METRICS, value) }

    /** Light, dark or as the phone (RF-UI-04); light unless the player picks another. */
    var themeMode: ThemeMode
        get() = preferences.getString(KEY_THEME_MODE, null)?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } } ?: ThemeMode.LIGHT
        set(value) = preferences.edit { putString(KEY_THEME_MODE, value.name) }

    /** Whether changes show without movement (IRON-68, RF-UI-06); off unless the player turns it on. */
    var reduceMotion: Boolean
        get() = preferences.getBoolean(KEY_REDUCE_MOTION, false)
        set(value) = preferences.edit { putBoolean(KEY_REDUCE_MOTION, value) }

    companion object {
        const val FILE_NAME = "play_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_SHOW_METRICS = "show_metrics"
        private const val KEY_NARRATOR_EFFORT = "narrator_effort"
        private const val KEY_CONFIRM_MOVES = "confirm_moves"
    }
}
