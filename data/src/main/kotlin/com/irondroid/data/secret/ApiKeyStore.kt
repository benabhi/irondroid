package com.irondroid.data.secret

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64

/**
 * Keeps one API key per AI provider, encrypted (RNF-SEG-01). Keys are stored in a private
 * preferences file that is left out of Android backups: the Keystore key is not backed up, so a
 * restored copy could not be read anyway.
 *
 * The provider id is the associated data of each encryption, so a key saved for one provider cannot
 * be read as another's.
 */
class ApiKeyStore(private val preferences: SharedPreferences, private val cipher: SecretCipher) {
    /** Saves [apiKey] for [provider], replacing any previous one. Surrounding spaces from pasting are dropped. */
    fun save(provider: String, apiKey: String) {
        require(provider.isNotBlank()) { "A provider id is needed" }
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty()) { "The API key is empty" }
        val encrypted = cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8), provider.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(provider, Base64.getEncoder().encodeToString(encrypted)).apply()
    }

    /**
     * The key saved for [provider], or null if there is none. A key that can no longer be decrypted
     * (for example after the Keystore key was lost) is deleted and reported as missing.
     */
    fun load(provider: String): String? {
        val stored = preferences.getString(provider, null) ?: return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(stored), provider.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        } catch (_: Exception) {
            delete(provider)
            null
        }
    }

    fun contains(provider: String): Boolean = preferences.contains(provider)

    /** Providers that have a key saved. */
    fun providers(): Set<String> = preferences.all.keys.toSet()

    fun delete(provider: String) {
        preferences.edit().remove(provider).apply()
    }

    companion object {
        /** Name of the preferences file; the app's backup rules exclude it. */
        const val FILE_NAME = "api_keys"

        fun open(context: Context): ApiKeyStore = ApiKeyStore(
            context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
            KeystoreCipher(),
        )
    }
}
