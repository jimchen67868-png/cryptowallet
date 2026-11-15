package com.example.wallet.data

import androidx.fragment.app.FragmentActivity
import com.example.wallet.crypto.SeedGenerator
import com.example.wallet.security.BiometricAuthenticator
import com.example.wallet.security.SecureSeedStore

class WalletRepository(
    private val store: SecureSeedStore,
    private val auth: BiometricAuthenticator
) {
    fun hasWallet() = store.hasWallet()

    /** Step 1: generate and show to the user. Nothing is saved yet. */
    fun generateNewMnemonic(): List<String> = SeedGenerator.generateMnemonic(12)

    /** Step 2: call only after the user confirmed their backup. */
    suspend fun saveWallet(activity: FragmentActivity, words: List<String>): Boolean {
        if (!SeedGenerator.isValid(words)) return false
        if (!auth.authenticate(activity, "Secure your wallet")) return false
        store.saveMnemonic(words)
        return true
    }

    suspend fun importWallet(activity: FragmentActivity, phrase: String): List<String>? {
        val words = phrase.trim().lowercase().split(Regex("\\s+"))
        return if (saveWallet(activity, words)) words else null
    }

    suspend fun unlockMnemonic(activity: FragmentActivity): List<String>? {
        if (!auth.authenticate(activity, "Unlock wallet")) return null
        return store.loadMnemonic()
    }

    /** Extra biometric/PIN check, e.g. before signing a mainnet transaction. */
    suspend fun confirmIdentity(activity: FragmentActivity, title: String): Boolean =
        auth.authenticate(activity, title)

    fun wipe() = store.wipe()
}
