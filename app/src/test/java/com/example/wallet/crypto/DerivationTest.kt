package com.example.wallet.crypto

import com.example.wallet.abandonSeed
import com.example.wallet.chain.BitcoinProvider
import com.example.wallet.chain.EvmKeys
import com.example.wallet.chain.SolanaProvider
import com.example.wallet.hex
import com.example.wallet.unhex
import org.junit.Assert.assertEquals
import org.junit.Test
import org.web3j.crypto.Bip32ECKeyPair

/**
 * Key derivation against published vectors:
 * BIP-32 test vector 1, SLIP-0010 ed25519 test vector 1, BIP-84 (bc1qcr8te...), and the well-known
 * addresses of the "abandon ... about" mnemonic for Ethereum and Solana.
 */
class DerivationTest {
    private val vector1 = "000102030405060708090a0b0c0d0e0f".unhex()

    private fun priv(k: Bip32ECKeyPair) = k.privateKey.toString(16).padStart(64, '0')
    private fun pub(k: Bip32ECKeyPair) = k.publicKeyPoint.getEncoded(true).hex()

    // ---- BIP-32 ----
    @Test fun bip32_vector1_master() {
        val k = Hd.secp256k1(vector1)
        assertEquals("e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35", priv(k))
        assertEquals("0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2", pub(k))
    }

    @Test fun bip32_vector1_hardenedChild() {
        val k = Hd.secp256k1(vector1, 0 or Hd.H)
        assertEquals("edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea", priv(k))
        assertEquals("035a784662a4a20a65bf6aab9ae98a6c068a81c52e4b032c0fb5400c706cfccc56", pub(k))
    }

    @Test fun bip32_vector1_normalChildOfHardened() {
        val k = Hd.secp256k1(vector1, 0 or Hd.H, 1)
        assertEquals("3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368", priv(k))
        assertEquals("03501e454bf00751f24b1b489aa925215d66af2234e3891c3b21a52bedb3cd711c", pub(k))
    }

    // ---- SLIP-0010 (ed25519) ----
    @Test fun slip10_vector1_master() {
        assertEquals("2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7",
            Hd.ed25519PrivateKey(vector1).hex())
    }

    @Test fun slip10_vector1_chain() {
        assertEquals("68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3",
            Hd.ed25519PrivateKey(vector1, 0).hex())
        assertEquals("b1d0bad404bf35da785a64ca1ac54b2617211d2777696fbffaf208f746ae84f2",
            Hd.ed25519PrivateKey(vector1, 0, 1).hex())
    }

    // ---- addresses from the standard mnemonic ----
    @Test fun bitcoin_mainnet_bip84_firstAddress() {
        val mainnet = BitcoinProvider(
            apiBase = "https://example.invalid", hrp = "bc", coinType = 0,
            p2pkhVersion = 0x00, p2shVersion = 0x05, explorerTxUrl = ""
        )
        // BIP-84 specification vector: m/84'/0'/0'/0/0
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", mainnet.deriveAddress(abandonSeed()))
    }

    @Test fun bitcoin_testnet_firstAddress() {
        // Same key, coin type 1, "tb" prefix (value computed with an independent reference implementation)
        assertEquals("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", BitcoinProvider().deriveAddress(abandonSeed()))
    }

    @Test fun bitcoin_keyAndHash160() {
        val k = Hd.secp256k1(abandonSeed(), 84 or Hd.H, 0 or Hd.H, 0 or Hd.H, 0, 0)
        val pub = k.publicKeyPoint.getEncoded(true)
        assertEquals("0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c", pub.hex())
        assertEquals("c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2", Hd.hash160(pub).hex())
    }

    @Test fun ethereum_firstAddress() {
        // Widely published address for this mnemonic at m/44'/60'/0'/0/0
        assertEquals(
            "0x9858effd232b4033e47d90003d41ec34ecaeda94",
            EvmKeys.credentials(abandonSeed()).address.lowercase()
        )
    }

    @Test fun solana_firstAddress() {
        // m/44'/501'/0'/0' (Phantom / Solana CLI default)
        assertEquals("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", SolanaProvider().deriveAddress(abandonSeed()))
    }
}
