package com.irondroid.dice

import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The table the 3D dice roll on: one transparent [DiceOverlayView] above the whole app (RF-DAD-01, IRON-71).
 *
 * Any screen asks it to [roll] values the engine already decided (RF-DAD-04); the dice land on them,
 * stay a moment and fade away. The page loads on first use, or earlier with [preload], and throws once
 * unseen so the first real roll does not stutter. A roll that fails or never finishes gives null, so the
 * game always goes on, with or without dice.
 */
class DiceTable(private val root: FrameLayout) {

    private var overlay: DiceOverlayView? = null
    private var ready = CompletableDeferred<DiceReadyInfo?>()
    private var pending: CompletableDeferred<DiceRollReport?>? = null
    private val rolls = Mutex()
    private var dark = false

    /** Loads the page now, so the first roll does not wait for it. */
    fun preload() {
        overlay()
    }

    /**
     * Throws [dice], each landing on its value, and returns what they show once they stop, or null if
     * the dice could not be shown. [fast] plays the throw faster and keeps the dice a shorter while
     * (RF-DAD-05). Rolls wait for each other.
     */
    suspend fun roll(dice: List<ThrownDie>, fast: Boolean = false): DiceRollReport? = rolls.withLock {
        val view = overlay()
        // Null if the page failed to load or is still loading after the timeout.
        val loaded = withTimeoutOrNull(LOAD_TIMEOUT_MS) { ready.await() }
        if (loaded == null) {
            Log.w(DiceOverlayView.TAG, "dice table not ready, roll shown without dice")
            return null
        }
        val result = CompletableDeferred<DiceRollReport?>()
        pending = result
        view.roll(dice, fast)
        val report = withTimeoutOrNull(ROLL_TIMEOUT_MS) { result.await() }
        pending = null
        if (report == null) {
            Log.w(DiceOverlayView.TAG, "roll did not finish, clearing the dice")
            view.clearDice()
        } else if (report.shown != dice.map { it.value }) {
            Log.e(DiceOverlayView.TAG, "dice show ${report.shown}, the engine rolled ${dice.map { it.value }}")
        }
        report
    }

    /** Dice colors that follow the app theme (ink on paper, or the inverse). */
    fun setDark(dark: Boolean) {
        if (this.dark == dark) return
        this.dark = dark
        overlay?.setDark(dark)
    }

    /** Stops drawing while the app is not on screen. */
    fun pause() {
        overlay?.onPause()
    }

    fun resume() {
        overlay?.onResume()
    }

    /** Frees the WebView when the activity ends. */
    fun destroy() {
        overlay?.let {
            root.removeView(it)
            it.destroy()
        }
        overlay = null
        pending?.complete(null)
    }

    private fun overlay(): DiceOverlayView = overlay ?: DiceOverlayView(root.context, listener, dark).also {
        root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlay = it
        ready = CompletableDeferred()
    }

    private val listener = object : DiceOverlayView.Listener {
        override fun onReady(info: DiceReadyInfo) {
            ready.complete(info)
        }

        override fun onRollComplete(report: DiceRollReport) {
            pending?.complete(report)
        }

        override fun onError(message: String) {
            // Before the page is ready the error is the page failing to load: rolls go on without dice.
            if (!ready.isCompleted) ready.complete(null)
            pending?.complete(null)
        }
    }

    private companion object {
        /** Page load plus the unseen warm-up throw; it took under a second on the Motorola (IRON-12). */
        const val LOAD_TIMEOUT_MS = 8_000L

        /** Rolls lasted 2.2 to 3.4 s on the Motorola (IRON-12); past this something went wrong. */
        const val ROLL_TIMEOUT_MS = 10_000L
    }
}

/** The dice table of the activity, for any screen that rolls dice; null where there is none (previews). */
val LocalDiceTable = staticCompositionLocalOf<DiceTable?> { null }
