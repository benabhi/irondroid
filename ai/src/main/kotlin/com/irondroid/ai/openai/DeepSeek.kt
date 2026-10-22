package com.irondroid.ai.openai

import com.irondroid.ai.ModelInfo
import com.irondroid.ai.ModelPricing
import com.irondroid.ai.PeakHours
import com.irondroid.ai.ProviderInfo
import com.irondroid.ai.ProviderRegistration
import okhttp3.OkHttpClient

/**
 * DeepSeek (RF-IA-02): an OpenAI-compatible API. The models are the ones `/models` listed on
 * 21/10/2026; both reason before answering and call tools (prototype IRON-13).
 */
object DeepSeek {
    const val ID = "deepseek"
    const val BASE_URL = "https://api.deepseek.com"

    /**
     * DeepSeek charges double at busy hours: 01-04 and 06-10 UTC, Monday to Friday (it also leaves
     * out Chinese public holidays, which this ignores). Prices from its pricing page on 21/10/2026.
     */
    private val PEAK = PeakHours(listOf(1 until 4, 6 until 10))

    val INFO = ProviderInfo(
        id = ID,
        name = "DeepSeek",
        models = listOf(
            ModelInfo("deepseek-flash", pricing = ModelPricing(cachedInput = 0.003, input = 0.15, output = 0.6, PEAK, peakFactor = 2.0)),
            ModelInfo("deepseek-v4-pro", pricing = ModelPricing(cachedInput = 0.022, input = 0.66, output = 1.98, PEAK, peakFactor = 2.0)),
        ),
    )

    /** The registration for the app's [com.irondroid.ai.ProviderRegistry]; a settings base URL replaces [BASE_URL]. */
    fun registration(client: OkHttpClient? = null) = ProviderRegistration(INFO) { settings ->
        OpenAiCompatibleProvider(INFO, settings.baseUrl ?: BASE_URL, settings.apiKey, client, returnsReasoning = true)
    }
}
