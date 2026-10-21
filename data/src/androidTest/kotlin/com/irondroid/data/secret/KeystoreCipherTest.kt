package com.irondroid.data.secret

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Uses the phone's real Android Keystore, with a test alias so the app's key is never touched. */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {
    private val alias = "irondroid_test_key"
    private val cipher = KeystoreCipher(alias)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        context.deleteSharedPreferences("api_keys_test")
    }

    @Test
    fun encryptsAndDecryptsWithTheKeystore() {
        val plain = "sk-device-test".toByteArray()

        val encrypted = cipher.encrypt(plain, "deepseek".toByteArray())

        assertFalse(encrypted.contentEquals(plain))
        assertContentEquals(plain, cipher.decrypt(encrypted, "deepseek".toByteArray()))
        assertContentEquals(plain, KeystoreCipher(alias).decrypt(encrypted, "deepseek".toByteArray()))
    }

    @Test
    fun everyEncryptionUsesANewIv() {
        val one = cipher.encrypt("same".toByteArray(), "p".toByteArray())
        val two = cipher.encrypt("same".toByteArray(), "p".toByteArray())

        assertFalse(one.contentEquals(two))
    }

    @Test
    fun otherAssociatedDataFails() {
        val encrypted = cipher.encrypt("sk".toByteArray(), "deepseek".toByteArray())

        assertFailsWith<Exception> { cipher.decrypt(encrypted, "openai".toByteArray()) }
    }

    @Test
    fun shortDataIsRejected() {
        assertFailsWith<IllegalArgumentException> { cipher.decrypt(ByteArray(0), "p".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { cipher.decrypt(byteArrayOf(12, 1, 2), "p".toByteArray()) }
    }

    @Test
    fun apiKeyStoreWorksOnTheDevice() {
        val preferences = context.getSharedPreferences("api_keys_test", android.content.Context.MODE_PRIVATE)
        val store = ApiKeyStore(preferences, cipher)

        store.save("deepseek", "sk-device-test")

        assertEquals("sk-device-test", store.load("deepseek"))
        assertFalse("sk-device-test" in preferences.getString("deepseek", null)!!)
        store.delete("deepseek")
        assertNull(store.load("deepseek"))
    }
}
