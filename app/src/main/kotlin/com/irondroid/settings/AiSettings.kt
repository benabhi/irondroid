package com.irondroid.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.irondroid.ai.ProviderInfo
import com.irondroid.ai.ProviderRegistry

/**
 * The player's AI choices that are not secret (RF-IA-03): which provider, and which model of each.
 * The API keys live apart, encrypted, in [com.irondroid.data.secret.ApiKeyStore]. A saved provider
 * or model that no longer exists falls back to the first one.
 */
class AiSettings(private val preferences: SharedPreferences, private val registry: ProviderRegistry) {
    var provider: ProviderInfo
        get() = preferences.getString(KEY_PROVIDER, null)?.let(registry::find) ?: registry.providers.first()
        set(value) = preferences.edit { putString(KEY_PROVIDER, value.id) }

    /** The model chosen for [provider], or its default. */
    fun model(provider: ProviderInfo = this.provider): String =
        preferences.getString(KEY_MODEL + provider.id, null)?.takeIf { provider.model(it) != null } ?: provider.defaultModel.id

    fun setModel(provider: ProviderInfo, model: String) {
        require(provider.model(model) != null) { "${provider.id} has no model $model" }
        preferences.edit { putString(KEY_MODEL + provider.id, model) }
    }

    companion object {
        const val FILE_NAME = "ai_settings"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model."
    }
}
