package com.irondroid.data.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets. [associatedData] is authenticated but not encrypted: decrypting with
 * different associated data fails, which ties a secret to what it belongs to.
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray

    /** Throws if [encrypted] was tampered with, made with another key or other associated data. */
    fun decrypt(encrypted: ByteArray, associatedData: ByteArray): ByteArray
}

/**
 * AES-GCM with a key that lives in the Android Keystore and never leaves it (RNF-SEG-01). Output is
 * the IV length, the IV and the ciphertext with its tag. The key is created on first use.
 */
class KeystoreCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {
    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray {
        // The Keystore picks a random IV for every encryption; it cannot be chosen from outside.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(associatedData)
        }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(encrypted: ByteArray, associatedData: ByteArray): ByteArray {
        require(encrypted.isNotEmpty()) { "Nothing to decrypt" }
        val ivSize = encrypted[0].toInt()
        require(ivSize > 0 && encrypted.size > 1 + ivSize) { "Encrypted data is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, encrypted, 1, ivSize))
            updateAAD(associatedData)
        }
        return cipher.doFinal(encrypted, 1 + ivSize, encrypted.size - 1 - ivSize)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as SecretKey?)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "irondroid_api_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}
