
package com.heiseed.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceAuthManager(context: Context) {

    private val preferences = context.getSharedPreferences(
        "hei_seed_device_auth",
        Context.MODE_PRIVATE
    )

    private val keyAlias = "hei_seed_device_auth_key"

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun getOrCreateEncryptionKey(): SecretKey {
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

    /**
     * Generates a new random 256-bit device secret,
     * stores it encrypted, and returns it as 64 hex characters.
     */
    fun generateDeviceKey(): String {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)

        val deviceKey = randomBytes.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

        saveDeviceKey(deviceKey)

        randomBytes.fill(0)

        return deviceKey
    }

    private fun saveDeviceKey(deviceKey: String) {
        require(deviceKey.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            "Device key must contain exactly 64 hexadecimal characters"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey())

        val encrypted = cipher.doFinal(
            deviceKey.toByteArray(Charsets.UTF_8)
        )

        preferences.edit()
            .putString(
                "encrypted_device_key",
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            )
            .putString(
                "iv",
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            )
            .apply()
    }

    /**
     * Returns the stored device key, or null if none exists.
     */
    fun getDeviceKey(): String? {
        val encryptedString = preferences.getString(
            "encrypted_device_key",
            null
        ) ?: return null

        val ivString = preferences.getString("iv", null)
            ?: return null

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val spec = GCMParameterSpec(
            128,
            Base64.decode(ivString, Base64.NO_WRAP)
        )

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateEncryptionKey(),
            spec
        )

        val decrypted = cipher.doFinal(
            Base64.decode(encryptedString, Base64.NO_WRAP)
        )

        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * Removes the stored device key.
     */
    fun clearDeviceKey() {
        preferences.edit()
            .remove("encrypted_device_key")
            .remove("iv")
            .apply()
    }

    fun hasDeviceKey(): Boolean {
        return !getDeviceKey().isNullOrBlank()
    }
}
