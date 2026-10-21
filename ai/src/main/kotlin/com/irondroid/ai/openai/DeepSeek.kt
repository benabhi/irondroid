package com.irondroid.ai.openai

import com.irondroid.ai.ModelInfo
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

    val INFO = ProviderInfo(
        id = ID,
        name = "DeepSeek",
        models = listOf(ModelInfo("deepseek-flash"), ModelInfo("deepseek-v4-pro")),
    )

    /** The registration for the app's [com.irondroid.ai.ProviderRegistry]; a settings base URL replaces [BASE_URL]. */
    fun registration(client: OkHttpClient? = null) = ProviderRegistration(INFO) { settings ->
        OpenAiCompatibleProvider(INFO, settings.baseUrl ?: BASE_URL, settings.apiKey, client, returnsReasoning = true)
    }
}
