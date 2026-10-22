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

    /** Rolls the Ironsworn action roll so the dice land on the given values. */
    fun roll(action: Int, challenge1: Int, challenge2: Int) {
        evaluateJavascript("irondroidDice.roll($action, $challenge1, $challenge2)", null)
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
