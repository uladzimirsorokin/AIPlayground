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
 * Stores API keys encrypted with keys that live in the Android Keystore,
 * so raw keys are never baked into the APK or written to disk in plaintext.
 *
 * - [save]/[load] — облачный ключ провайдера (OpenRouter и т.п.)
 * - [saveLocal]/[loadLocal] — ключ локального шлюза (gateway: auth + rate limit), напр. за Tailscale Funnel
 */
object KeyStorage {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREF_NAME = "secure_prefs"
    private const val CLOUD_ALIAS = "llm_api_key"
    private const val LOCAL_ALIAS = "llm_local_key"

    fun save(context: Context, value: String) = saveKey(context, CLOUD_ALIAS, value)

    fun load(context: Context): String? = loadKey(context, CLOUD_ALIAS)

    fun saveLocal(context: Context, value: String) = saveKey(context, LOCAL_ALIAS, value)

    fun loadLocal(context: Context): String? = loadKey(context, LOCAL_ALIAS)

    private fun saveKey(context: Context, alias: String, value: String) {
        val encrypted = encrypt(alias, value)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(alias, encrypted)
            .apply()
    }

    private fun loadKey(context: Context, alias: String): String? {
        val encrypted = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(alias, null)
            ?: return null
        return try {
            decrypt(alias, encrypted)
        } catch (e: Exception) {
            null
        }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(alias: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(alias: String, value: String): String {
        val (ivB64, dataB64) = value.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(alias),
            GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
