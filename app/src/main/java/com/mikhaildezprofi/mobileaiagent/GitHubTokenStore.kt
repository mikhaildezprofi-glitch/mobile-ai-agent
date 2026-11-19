package com.mikhaildezprofi.mobileaiagent

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
 * Stores a fine-grained GitHub token encrypted with an AES-256 key held by Android Keystore.
 * The token itself is never written to disk in plaintext.
 */
class GitHubTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasToken(): Boolean =
        !prefs.getString(CIPHERTEXT, null).isNullOrBlank() &&
            !prefs.getString(IV, null).isNullOrBlank()

    @Synchronized
    fun save(token: String) {
        val clean = token.trim()
        require(isValidTokenFormat(clean)) { "Некорректный формат GitHub-токена." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        check(
            prefs.edit()
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        ) { "Не удалось сохранить GitHub-токен." }
    }

    @Synchronized
    fun read(): String? {
        val ivText = prefs.getString(IV, null) ?: return null
        val encryptedText = prefs.getString(CIPHERTEXT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(
                cipher.doFinal(Base64.decode(encryptedText, Base64.NO_WRAP)),
                Charsets.UTF_8
            ).takeIf(::isValidTokenFormat)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().remove(IV).remove(CIPHERTEXT).commit()) {
            "Не удалось удалить GitHub-токен."
        }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "github_secure_config"
        private const val IV = "token_iv"
        private const val CIPHERTEXT = "token_ciphertext"
        private const val KEY_ALIAS = "mobile_ai_agent_github_token_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Accepts classic and fine-grained PAT prefixes without assuming a fixed token length. */
        fun isValidTokenFormat(token: String): Boolean {
            if (token.length !in 20..512) return false
            if (token.any { it.isWhitespace() || it.isISOControl() }) return false
            return token.matches(Regex("[A-Za-z0-9_]+"))
        }
    }
}
