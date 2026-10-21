package com.irondroid.data.secret

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same format as [KeystoreCipher], with a software key: Robolectric has no Android Keystore. */
private class SoftwareCipher(private val key: SecretKey = newKey()) : SecretCipher {
    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(associatedData)
        }
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(encrypted: ByteArray, associatedData: ByteArray): ByteArray {
        val ivSize = encrypted[0].toInt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted, 1, ivSize))
            updateAAD(associatedData)
        }
        return cipher.doFinal(encrypted, 1 + ivSize, encrypted.size - 1 - ivSize)
    }

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

@RunWith(RobolectricTestRunner::class)
class ApiKeyStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var preferences: SharedPreferences
    private lateinit var store: ApiKeyStore

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences(ApiKeyStore.FILE_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = ApiKeyStore(preferences, SoftwareCipher())
    }

    @Test
    fun `a saved key reads back the same`() {
        store.save("deepseek", "sk-test-1234")

        assertEquals("sk-test-1234", store.load("deepseek"))
        assertTrue(store.contains("deepseek"))
    }

    @Test
    fun `the key is not stored in plain text`() {
        store.save("deepseek", "sk-test-1234")

        val stored = preferences.getString("deepseek", null)!!
        assertFalse("sk-test-1234" in stored)
        assertFalse("1234" in stored)
    }

    @Test
    fun `saving the same key twice stores different ciphertext`() {
        store.save("deepseek", "sk-test-1234")
        val first = preferences.getString("deepseek", null)

        store.save("deepseek", "sk-test-1234")

        assertNotEquals(first, preferences.getString("deepseek", null))
    }

    @Test
    fun `spaces around a pasted key are dropped`() {
        store.save("openai", "  sk-pasted\n")

        assertEquals("sk-pasted", store.load("openai"))
    }

    @Test
    fun `empty keys and providers are rejected`() {
        assertFailsWith<IllegalArgumentException> { store.save("deepseek", "   ") }
        assertFailsWith<IllegalArgumentException> { store.save(" ", "sk-test") }
        assertTrue(store.providers().isEmpty())
    }

    @Test
    fun `each provider keeps its own key`() {
        store.save("deepseek", "sk-deep")
        store.save("anthropic", "sk-ant")
        store.save("deepseek", "sk-deep-2")

        assertEquals("sk-deep-2", store.load("deepseek"))
        assertEquals("sk-ant", store.load("anthropic"))
        assertEquals(setOf("deepseek", "anthropic"), store.providers())
    }

    @Test
    fun `a missing key is null`() {
        assertNull(store.load("gemini"))
        assertFalse(store.contains("gemini"))
    }

    @Test
    fun `deleting a key forgets it`() {
        store.save("deepseek", "sk-test")

        store.delete("deepseek")

        assertNull(store.load("deepseek"))
        assertTrue(store.providers().isEmpty())
    }

    @Test
    fun `a key moved to another provider cannot be read and is dropped`() {
        store.save("deepseek", "sk-test")
        preferences.edit().putString("openai", preferences.getString("deepseek", null)).commit()

        assertNull(store.load("openai"))
        assertFalse(store.contains("openai"))
        assertEquals("sk-test", store.load("deepseek"))
    }

    @Test
    fun `a key that the current cipher cannot decrypt is dropped`() {
        store.save("deepseek", "sk-test")
        val afterKeyLoss = ApiKeyStore(preferences, SoftwareCipher())

        assertNull(afterKeyLoss.load("deepseek"))
        assertFalse(afterKeyLoss.contains("deepseek"))
    }

    @Test
    fun `corrupted stored text is dropped`() {
        preferences.edit().putString("deepseek", "not base64 !!").commit()

        assertNull(store.load("deepseek"))
        assertFalse(store.contains("deepseek"))
    }

    @Test
    fun `unicode keys survive`() {
        store.save("custom", "clé-ñandú-🔑")

        assertEquals("clé-ñandú-🔑", store.load("custom"))
    }
}
