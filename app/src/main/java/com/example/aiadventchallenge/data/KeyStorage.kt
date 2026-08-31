package com.example.aiadventchallenge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the LLM API key encrypted with a key that lives in the Android Keystore,
 * so the raw key is never baked into the APK or written to disk in plaintext.
 */
object KeyStorage {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "llm_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREF_NAME = "secure_prefs"
    private const val PREF_KEY = "llm_api_key"

    fun save(context: Context, value: String) {
        val encrypted = encrypt(value)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, encrypted)
            .apply()
    }

    fun load(context: Context): String? {
        val encrypted = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY, null)
            ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val (ivB64, dataB64) = value.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}