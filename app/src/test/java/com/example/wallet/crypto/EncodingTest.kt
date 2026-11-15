package com.example.wallet.crypto

import com.example.wallet.chain.BitcoinProvider
import com.example.wallet.hex
import com.example.wallet.unhex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EncodingTest {

    // ---- Base58: vectors from Bitcoin Core's base58_encode_decode.json ----
    private val base58Vectors = listOf(
        "" to "", "61" to "2g", "626262" to "a3gV", "636363" to "aPEr",
        "73696d706c792061206c6f6e6720737472696e67" to "2cFupjhnEsSn59qHXstmK2ffpLv2",
        "00eb15231dfceb60925886b67d065299925915aeb172c06647" to "1NS17iag9jJgTHD1VXjvLCEnZuQ3rJDE9L",
        "516b6fcd0f" to "ABnLTmg", "bf4f89001e670274dd" to "3SEo3LWLoPntC", "572e4794" to "3EFU7m",
        "ecac89cad93923c02321" to "EJDM8drfXA6uyA", "10c8511e" to "Rt5zm",
        "00000000000000000000" to "1111111111"
    )

    @Test fun base58_encode() {
        for ((hex, b58) in base58Vectors) assertEquals(hex, b58, Base58.encode(hex.unhex()))
    }

    @Test fun base58_decode() {
        for ((hex, b58) in base58Vectors) assertEquals(b58, hex, Base58.decode(b58)!!.hex())
    }

    @Test fun base58_invalidCharactersRejected() {
        assertNull(Base58.decode("0OIl"))
        assertNull(Base58.decode("abc!"))
    }

    @Test fun base58_solanaSystemProgramIs32ZeroBytes() {
        assertArrayEquals(ByteArray(32), Base58.decode("1".repeat(32)))
    }

    // ---- Bech32 / Bech32m (BIP-173, BIP-350) ----
    private val program20 = "751e76e8199196d454941c45d1b3a323f1433bd6".unhex()
    private val taprootKey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798".unhex()

    @Test fun bech32_encodeP2wpkh() {
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", Bech32.encodeSegwitV0("bc", program20))
        assertEquals("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", Bech32.encodeSegwitV0("tb", program20))
    }

    @Test fun bech32_decodeV0_lowerAndUpperCase() {
        for (addr in listOf(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"
        )) {
            val seg = Bech32.decodeSegwit("bc", addr)!!
            assertEquals(0, seg.version)
            assertArrayEquals(program20, seg.program)
        }
    }

    @Test fun bech32m_decodeTaproot() {
        val seg = Bech32.decodeSegwit("bc", "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0")!!
        assertEquals(1, seg.version)
        assertArrayEquals(taprootKey, seg.program)
    }

    @Test fun bech32_invalidAddressesRejected() {
        val bad = listOf(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5",                         // bad checksum
            "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx".replace("qw5", "qW5"),    // mixed case
            "BC13W508D6QEJXTDG4Y5R3ZARVARY0C5XW7KN40WF2",                          // witness version 19 (> 16)
            "bc1rw508d6qejxtdg4y5r3zarvary0c5xw7k3vevug",                          // v3 but classic bech32 checksum
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd",      // v1 with bech32 (not m) checksum
            "bc1", "", "1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        )
        for (a in bad) assertNull(a, Bech32.decodeSegwit("bc", a))
    }

    @Test fun bech32_wrongPrefixRejected() {
        assertNull(Bech32.decodeSegwit("tb", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
        assertNull(Bech32.decodeSegwit("bc", "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
    }

    // ---- Bitcoin output scripts for each address type ----
    private val mainnet = BitcoinProvider(
        apiBase = "https://example.invalid", hrp = "bc", coinType = 0,
        p2pkhVersion = 0x00, p2shVersion = 0x05, explorerTxUrl = ""
    )
    private val testnet = BitcoinProvider()

    @Test fun script_p2wpkh() {
        assertEquals("0014751e76e8199196d454941c45d1b3a323f1433bd6",
            mainnet.scriptForAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")!!.hex())
    }

    @Test fun script_p2tr() {
        assertEquals("512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            mainnet.scriptForAddress("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0")!!.hex())
    }

    @Test fun script_p2pkh_genesisAddress() {
        assertEquals("76a91462e907b15cbf27d5425399ebf6f0fb50ebb88f1888ac",
            mainnet.scriptForAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")!!.hex())
    }

    @Test fun script_p2sh() {
        assertEquals("a914b472a266d0bd89c13706a4132ccfb16f7c3b9fcb87",
            mainnet.scriptForAddress("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")!!.hex())
    }

    @Test fun script_testnetP2pkh() {
        assertEquals("76a914243f1394f44554f4ce3fd68649c19adc483ce92488ac",
            testnet.scriptForAddress("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn")!!.hex())
    }

    @Test fun script_wrongNetworkRejected() {
        // A mainnet address must never be accepted by the testnet provider, and vice versa.
        assertNull(testnet.scriptForAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
        assertNull(testnet.scriptForAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
        assertNull(testnet.scriptForAddress("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"))
        assertNull(mainnet.scriptForAddress("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        assertNull(mainnet.scriptForAddress("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"))
    }

    @Test fun script_corruptedAddressesRejected() {
        assertNull(mainnet.scriptForAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb"))                       // bad base58 checksum
        assertNull(mainnet.scriptForAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5"))             // bad bech32 checksum
        assertNull(mainnet.scriptForAddress("bc1rw5uspcuh"))                                           // witness v3, 1-byte program
        assertNull(mainnet.scriptForAddress(""))
        assertNull(mainnet.scriptForAddress("not an address"))
        assertNotNull(mainnet.scriptForAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")) // control
    }
}
