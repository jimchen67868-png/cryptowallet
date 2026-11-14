package com.example.wallet.security

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
 * Stores the mnemonic encrypted with an AES-256-GCM key that lives in the
 * Android Keystore (hardware-backed where available). The key requires user
 * authentication: call BiometricAuthenticator first, then encrypt/decrypt
 * within AUTH_VALIDITY_SECONDS. Requires minSdk 30.
 */
class SecureSeedStore(context: Context) {

    private val prefs = context.getSharedPreferences("wallet_secure", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasWallet(): Boolean = prefs.contains(KEY_CIPHERTEXT)

    fun saveMnemonic(words: List<String>) {
        val plain = words.joinToString(" ").toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plain)
            prefs.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .apply()
        } finally {
            plain.fill(0)
        }
    }

    /** Throws UserNotAuthenticatedException if auth is older than the validity window. */
    fun loadMnemonic(): List<String>? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val ct = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val plain = cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP))
        try {
            return String(plain, Charsets.UTF_8).split(" ")
        } finally {
            plain.fill(0)
        }
    }

    fun wipe() {
        prefs.edit().clear().apply()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "wallet_master_key"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ct"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AUTH_VALIDITY_SECONDS = 30
    }
}
