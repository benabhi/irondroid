package com.irondroid.dice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Transparent WebView drawn above the whole app where the 3D dice roll (RF-DAD-01).
 *
 * It never takes touches, so everything underneath keeps working while dice are on screen.
 * Results are decided by the caller; the page only animates the dice to land on them.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class DiceOverlayView(context: Context, private val listener: Listener) : WebView(context) {

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
        addJavascriptInterface(Bridge(), "DiceBridge")
        loadUrl(PAGE_URL)
    }

    /** Rolls the Ironsworn action roll so the dice land on the given values. */
    fun roll(action: Int, challenge1: Int, challenge2: Int) {
        evaluateJavascript("irondroidDice.roll($action, $challenge1, $challenge2)", null)
    }

    /** Renders at the screen's real density instead of one pixel per CSS pixel. */
    fun setSharp(sharp: Boolean) {
        evaluateJavascript("irondroidDice.setSharp($sharp)", null)
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
        const val TAG = "DiceProto"
        private const val PAGE_URL = "https://appassets.androidplatform.net/assets/dice/index.html"
    }
}

/** What the dice page reports once it is loaded. */
data class DiceReadyInfo(
    val initMs: Double,
    val width: Int,
    val height: Int,
    val pixelRatio: Double,
    val gpu: String,
) {
    companion object {
        fun fromJson(json: JSONObject) = DiceReadyInfo(
            initMs = json.getDouble("initMs"),
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
) {
    companion object {
        fun fromJson(json: JSONObject): DiceRollReport {
            val shown = json.getJSONArray("shown")
            return DiceRollReport(
                shown = List(shown.length()) { shown.getInt(it) },
                durationMs = json.getDouble("durationMs"),
                firstFrameMs = json.getDouble("firstFrameMs"),
                frames = json.getInt("frames"),
                avgFps = json.getDouble("avgFps"),
                worstFrameMs = json.getDouble("worstFrameMs"),
                slowFrames = json.getInt("slowFrames"),
            )
        }
    }
}
