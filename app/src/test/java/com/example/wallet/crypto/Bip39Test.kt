package com.example.wallet.crypto

import com.example.wallet.ABANDON
import com.example.wallet.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors from the BIP-39 reference (Trezor) test set. */
class Bip39Test {
    private val words12 = ABANDON.split(" ")
    private val words24 = List(23) { "abandon" } + "art"

    @Test fun seed_12Words_trezorPassphrase() {
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            SeedGenerator.toSeed(words12, "TREZOR").hex()
        )
    }

    @Test fun seed_12Words_emptyPassphrase() {
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            SeedGenerator.toSeed(words12).hex()
        )
    }

    @Test fun seed_24Words_trezorPassphrase() {
        assertEquals(
            "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd3097170af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8",
            SeedGenerator.toSeed(words24, "TREZOR").hex()
        )
    }

    @Test fun validMnemonics_areAccepted() {
        assertTrue(SeedGenerator.isValid(words12))
        assertTrue(SeedGenerator.isValid(words24))
    }

    @Test fun badChecksum_isRejected() {
        assertFalse(SeedGenerator.isValid(List(12) { "abandon" }))
    }

    @Test fun unknownWord_isRejected() {
        assertFalse(SeedGenerator.isValid(words12.dropLast(1) + "notaword"))
    }

    @Test fun wrongWordCount_isRejected() {
        assertFalse(SeedGenerator.isValid(words12.dropLast(1)))   // 11 words
        assertFalse(SeedGenerator.isValid(words12 + "abandon"))   // 13 words
        assertFalse(SeedGenerator.isValid(emptyList()))
    }

    @Test fun generatedMnemonics_areValidAndRandom() {
        val a = SeedGenerator.generateMnemonic(12)
        val b = SeedGenerator.generateMnemonic(12)
        val c = SeedGenerator.generateMnemonic(24)
        assertEquals(12, a.size)
        assertEquals(24, c.size)
        assertTrue(SeedGenerator.isValid(a))
        assertTrue(SeedGenerator.isValid(c))
        assertNotEquals(a, b) // 2^-128 chance of a false failure
    }
}
