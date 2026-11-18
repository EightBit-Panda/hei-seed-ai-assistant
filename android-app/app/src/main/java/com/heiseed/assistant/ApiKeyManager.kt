
package com.heiseed.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyManager(context: Context) {

    private val preferences = context.getSharedPreferences(
        "hei_seed_secure",
        Context.MODE_PRIVATE
    )

    private val keyAlias = "hei_seed_api_key"

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val existingKey = keyStore.getKey(keyAlias, null)

        if (existingKey is SecretKey) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun saveApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) {
            "API key cannot be empty"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())

        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

        preferences.edit()
            .putString("encrypted_key", Base64.encodeToString(
                encrypted,
                Base64.NO_WRAP
            ))
            .putString("iv", Base64.encodeToString(
                cipher.iv,
                Base64.NO_WRAP
            ))
            .apply()
    }

    fun getApiKey(): String? {
        val encryptedString = preferences.getString("encrypted_key", null)
            ?: return null

        val ivString = preferences.getString("iv", null)
            ?: return null

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val spec = GCMParameterSpec(
            128,
            Base64.decode(ivString, Base64.NO_WRAP)
        )

        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)

        val decrypted = cipher.doFinal(
            Base64.decode(encryptedString, Base64.NO_WRAP)
        )

        return String(decrypted, Charsets.UTF_8)
    }

    fun clearApiKey() {
        preferences.edit()
            .remove("encrypted_key")
            .remove("iv")
            .apply()
    }
}
