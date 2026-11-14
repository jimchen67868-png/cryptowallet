package com.example.wallet.crypto

import org.web3j.crypto.MnemonicUtils
import java.security.SecureRandom

/** BIP-39 mnemonic generation, validation and seed derivation. */
object SeedGenerator {

    /** 12 words = 128 bits of entropy, 24 words = 256 bits. */
    fun generateMnemonic(wordCount: Int = 12): List<String> {
        require(wordCount == 12 || wordCount == 24) { "Use 12 or 24 words" }
        val entropy = ByteArray(if (wordCount == 12) 16 else 32)
        SecureRandom().nextBytes(entropy)
        try {
            return MnemonicUtils.generateMnemonic(entropy).split(" ")
        } finally {
            entropy.fill(0)
        }
    }

    fun isValid(words: List<String>): Boolean = try {
        (words.size == 12 || words.size == 24) &&
            MnemonicUtils.validateMnemonic(words.joinToString(" "))
    } catch (e: Exception) {
        false
    }

    /** 64-byte BIP-39 seed. Wipe it (fill(0)) as soon as you are done. */
    fun toSeed(words: List<String>, passphrase: String = ""): ByteArray =
        MnemonicUtils.generateSeed(words.joinToString(" "), passphrase)
}
