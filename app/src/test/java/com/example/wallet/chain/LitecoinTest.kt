package com.example.wallet.chain

import com.example.wallet.abandonSeed
import com.example.wallet.crypto.Hd
import com.example.wallet.data.CoinIds
import com.example.wallet.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Litecoin shares the Bitcoin SegWit implementation (BIP-143 signing is identical), so BitcoinTxTest covers signing.
 * Expected addresses come from tools/verify/tron_golden.py (independent pure-Python derivation); the mainnet one also
 * matches the widely published BIP-84 vector for this mnemonic. Dust limit and API endpoints are not verified live.
 */
class LitecoinTest {
    private val main = BitcoinProvider.litecoin()
    private val test = BitcoinProvider.litecoin(testnet = true)

    @Test fun derivesBip84Addresses() {
        assertEquals("ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh", main.deriveAddress(abandonSeed()))
        assertEquals("tltc1q6rz28mcfaxtmd6v789l9rrlrusdprr9pesrjxk", test.deriveAddress(abandonSeed()))
    }

    @Test fun usesSlip44CoinType2OnMainnet_notBitcoins0() {
        val ltcKey = Hd.secp256k1(abandonSeed(), 84 or Hd.H, 2 or Hd.H, 0 or Hd.H, 0, 0)
        val btcKey = Hd.secp256k1(abandonSeed(), 84 or Hd.H, 0 or Hd.H, 0 or Hd.H, 0, 0)
        assertFalse(ltcKey.publicKey == btcKey.publicKey)
    }

    @Test fun metadata() {
        assertEquals(Chain.LTC, main.chain)
        assertEquals("LTC", main.chain.symbol)
        assertFalse(main.isTestnet)
        assertEquals("Mainnet", main.networkName)
        assertTrue(test.isTestnet)
        assertEquals("Testnet", test.networkName)
        assertEquals(8, main.nativeDecimals)
        assertEquals("https://litecoinspace.org/tx/abc", main.explorerUrl("abc"))
        assertEquals("https://litecoinspace.org/testnet/tx/abc", test.explorerUrl("abc"))
        // Bitcoin itself is unchanged
        assertEquals(Chain.BTC, BitcoinProvider().chain)
        assertTrue(BitcoinProvider().isTestnet)
    }

    @Test fun mainnetAddresses_acceptedAndRejected() {
        assertTrue(main.isValidAddress("ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh"))
        assertTrue(main.isValidAddress("LYyKS4hm5QcmAWntuZSA6Kp5TKg9fCeLQn"))   // P2PKH (L...)
        assertTrue(main.isValidAddress("MMeXQHJLVmYWoP54xQ6o4aMeiLC338jxXE"))   // P2SH, current version (M...)
        assertTrue(main.isValidAddress("3FSP6PtNYeh5zsoArX7TEw7FPdbb28nNHb"))   // P2SH, legacy version (3...)
        assertFalse("bitcoin bech32", main.isValidAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
        assertFalse("bitcoin legacy", main.isValidAddress("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"))
        assertFalse("testnet on mainnet", main.isValidAddress("tltc1q6rz28mcfaxtmd6v789l9rrlrusdprr9pesrjxk"))
        assertFalse("bad checksum", main.isValidAddress("ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wx"))
        assertFalse("mweb not supported", main.isValidAddress("ltcmweb1qq0lq0lq0lq0lq0lq0lq0lq0lq0lq0lq0lq0lq0lq0lq0lq0lq0lq0lq0lq"))
        assertFalse("empty", main.isValidAddress(""))
    }

    @Test fun testnetAddresses_acceptedAndRejected() {
        assertTrue(test.isValidAddress("tltc1q6rz28mcfaxtmd6v789l9rrlrusdprr9pesrjxk"))
        assertTrue(test.isValidAddress("QaMMH9geBDFXLrBm9kmLwaXwkNFakGWZUE"))   // P2SH Q...
        assertTrue(test.isValidAddress("2N6zbA8pQA7CSCfRiXejKrt6WbyokoNMeoj"))  // legacy P2SH 2...
        assertFalse(test.isValidAddress("ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh"))
        assertFalse(test.isValidAddress("MMeXQHJLVmYWoP54xQ6o4aMeiLC338jxXE"))
    }

    @Test fun scriptsForEachAddressType() {
        assertEquals("001496cd3178f3de0f307bcee1aeaa4d5987862dd30a", main.scriptForAddress("ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh")!!.hex())
        val p2pkh = main.scriptForAddress("LYyKS4hm5QcmAWntuZSA6Kp5TKg9fCeLQn")!!
        assertEquals(25, p2pkh.size)
        assertEquals(0x76, p2pkh[0].toInt())
        val m = main.scriptForAddress("MMeXQHJLVmYWoP54xQ6o4aMeiLC338jxXE")!!
        val three = main.scriptForAddress("3FSP6PtNYeh5zsoArX7TEw7FPdbb28nNHb")!!
        assertEquals("both P2SH spellings are the same script", m.toList(), three.toList())
        assertEquals(23, m.size)
    }

    @Test fun priced() {
        assertEquals("litecoin", CoinIds.forAsset(Chain.LTC, null))
        assertNull(CoinIds.forAsset(Chain.LTC, "anything"))
    }

    @Test fun coinSelection_usesTheDustLimitPassedIn() {
        val utxos = listOf(Utxo("11".repeat(32), 0, 1_000_000))
        val fee = CoinSelection.select(utxos, 1_000, 22, 1.0).fee
        // leave about 3000 sats of change: above Bitcoin's 546 limit, below Litecoin's 5460 (that change goes to the miner)
        val amount = 1_000_000 - fee - 3_000
        assertTrue(CoinSelection.select(utxos, amount, 22, 1.0, 546).change >= 546)
        assertEquals(0L, CoinSelection.select(utxos, amount, 22, 1.0, 5460).change)
    }
}
