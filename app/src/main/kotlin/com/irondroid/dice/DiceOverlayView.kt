package com.irondroid.dice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transparent WebView drawn above the whole app where the 3D dice roll (RF-DAD-01).
 *
 * It never takes touches, so everything underneath keeps working while dice are on screen.
 * Results are decided by the caller; the page only animates the dice to land on them.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class DiceOverlayView(context: Context, private val listener: Listener, dark: Boolean) : WebView(context) {

    interface Listener {
        fun onReady(info: DiceReadyInfo)
        fun onRollComplete(report: DiceRollReport)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = false
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }
        // Keep the dice table clear of the status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                setMargins(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        addJavascriptInterface(Bridge(), "DiceBridge")
        // The page starts with the theme colors so its warm-up throw prepares the right ones.
        loadUrl(if (dark) "$PAGE_URL?theme=dark" else PAGE_URL)
    }

    /** Throws [dice] so each lands on its value. */
    fun roll(dice: List<ThrownDie>) {
        val json = JSONArray(dice.map { JSONObject().put("sides", it.sides).put("value", it.value).put("ink", it.ink) })
        evaluateJavascript("irondroidDice.roll($json)", null)
    }

    /** Dice colors for the dark theme (light dice on dark paper) or the light one. */
    fun setDark(dark: Boolean) {
        evaluateJavascript("irondroidDice.setTheme($dark)", null)
    }

    fun clearDice() {
        evaluateJavascript("irondroidDice.clear()", null)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    /** Called by the page on a WebView background thread; results are handed to the main thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun onReady(json: String) {
            val info = DiceReadyInfo.fromJson(JSONObject(json))
            Log.i(TAG, "ready: $info")
            mainHandler.post { listener.onReady(info) }
        }

        @JavascriptInterface
        fun onRollComplete(json: String) {
            Log.d(TAG, "raw: $json")
            val report = DiceRollReport.fromJson(JSONObject(json))
            Log.i(TAG, "roll: $report")
            mainHandler.post { listener.onRollComplete(report) }
        }

        @JavascriptInterface
        fun onError(json: String) {
            val message = JSONObject(json).optString("message")
            Log.e(TAG, "error: $message")
            mainHandler.post { listener.onError(message) }
        }
    }

    companion object {
        const val TAG = "Dice"
        private const val PAGE_URL = "https://appassets.androidplatform.net/assets/dice/index.html"
    }
}

/**
 * One die of a throw: [sides] 6 or 10, the [value] it must land on (a d10 shows its 10 as "0") and
 * whether it is [ink] (dark on the light theme) or paper.
 */
data class ThrownDie(val sides: Int, val value: Int, val ink: Boolean) {
    init {
        require(sides == 6 || sides == 10) { "Only d6 and d10 are thrown, not d$sides" }
        require(value in 1..sides) { "A d$sides cannot show $value" }
    }

    companion object {
        /** An action roll (RF-DAD-02): the ink action die and the two paper challenge dice. */
        fun actionRoll(action: Int, challenge1: Int, challenge2: Int) =
            listOf(ThrownDie(6, action, ink = true), ThrownDie(10, challenge1, ink = false), ThrownDie(10, challenge2, ink = false))

        /** A progress roll: only the two challenge dice. */
        fun progressRoll(challenge1: Int, challenge2: Int) =
            listOf(ThrownDie(10, challenge1, ink = false), ThrownDie(10, challenge2, ink = false))

        /**
         * A 1 to 100 oracle roll as two d10 (RF-DAD-03): tens in ink and units in paper, each "0"
         * read as zero, so 00 and 0 make 100.
         */
        fun percentile(roll: Int): List<ThrownDie> {
            require(roll in 1..100) { "An oracle roll goes from 1 to 100, not $roll" }
            fun die(digit: Int) = if (digit == 0) 10 else digit
            return listOf(ThrownDie(10, die(roll / 10 % 10), ink = true), ThrownDie(10, die(roll % 10), ink = false))
        }
    }
}

/** What the dice page reports once it is loaded. */
data class DiceReadyInfo(
    val initMs: Double,
    /** The invisible first throw that compiles shaders, so the player's first roll does not stutter. */
    val warmupMs: Double,
    val width: Int,
    val height: Int,
    val pixelRatio: Double,
    val gpu: String,
) {
    companion object {
        fun fromJson(json: JSONObject) = DiceReadyInfo(
            initMs = json.getDouble("initMs"),
            warmupMs = json.optDouble("warmupMs", 0.0),
            width = json.getInt("width"),
            height = json.getInt("height"),
            pixelRatio = json.getDouble("pixelRatio"),
            gpu = json.optString("gpu"),
        )
    }
}

/** Values the dice ended up showing and how smoothly they were drawn. */
data class DiceRollReport(
    val shown: List<Int>,
    val durationMs: Double,
    val firstFrameMs: Double,
    val frames: Int,
    val avgFps: Double,
    val worstFrameMs: Double,
    val slowFrames: Int,
    val simulationMs: Double,
    /** Outcome of every hidden throw: "ok", or why it was redone ("stacked", "tilted", "slow"). */
    val throws: List<String>,
) {
    companion object {
        fun fromJson(json: JSONObject): DiceRollReport {
            val shown = json.getJSONArray("shown")
            val throws = json.getJSONArray("throws")
            return DiceRollReport(
                shown = List(shown.length()) { shown.getInt(it) },
                durationMs = json.getDouble("durationMs"),
                firstFrameMs = json.getDouble("firstFrameMs"),
                frames = json.getInt("frames"),
                avgFps = json.getDouble("avgFps"),
                worstFrameMs = json.getDouble("worstFrameMs"),
                slowFrames = json.getInt("slowFrames"),
                simulationMs = json.getDouble("simulationMs"),
                throws = List(throws.length()) { throws.getString(it) },
            )
        }
    }
}
