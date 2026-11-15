package com.example.wallet.data

import com.example.wallet.chain.Chain
import com.example.wallet.chain.EthereumProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Keys
import org.web3j.crypto.WalletUtils

/** Guards the hand-maintained token tables against typos (a wrong digit would break the EIP-55 checksum). */
class TokenListsTest {
    private val lists = mapOf(
        Chain.ETH to EthereumProvider.MAINNET_TOKENS + EthereumProvider.SEPOLIA_TOKENS,
        Chain.POL to EvmTokens.POLYGON,
        Chain.BNB to EvmTokens.BNB,
        Chain.BASE to EvmTokens.BASE,
        Chain.ARB to EvmTokens.ARBITRUM
    )

    @Test fun addresses_areValidAndEip55Checksummed() {
        for ((_, tokens) in lists) for (t in tokens) {
            assertTrue(t.address, WalletUtils.isValidAddress(t.address))
            assertEquals("bad checksum casing: ${t.address}", t.address, Keys.toChecksumAddress(t.address))
        }
    }

    @Test fun noDuplicateAddressesWithinAChain() {
        for ((chain, tokens) in lists) {
            val lower = tokens.map { it.address.lowercase() }
            assertEquals("$chain", lower.size, lower.toSet().size)
        }
    }

    @Test fun decimals_areSane() {
        for ((_, tokens) in lists) for (t in tokens) assertTrue("${t.symbol} ${t.decimals}", t.decimals in 0..18)
        // BNB Chain's bridged stablecoins use 18 decimals; everywhere else USDC/USDT use 6.
        EvmTokens.BNB.forEach { assertEquals(18, it.decimals) }
        (EvmTokens.POLYGON + EvmTokens.BASE + EvmTokens.ARBITRUM + EthereumProvider.MAINNET_TOKENS)
            .filter { it.symbol == "USDC" || it.symbol == "USDT" }
            .forEach { assertEquals(it.address, 6, it.decimals) }
    }

    @Test fun everyCuratedTokenHasAPrice() {
        for ((chain, tokens) in lists) for (t in tokens) {
            assertNotNull("${chain.name} ${t.symbol}", CoinIds.forAsset(chain, t.address))
        }
    }

    // ---- price lookup rules ----
    @Test fun nativeCoins() {
        assertEquals("ethereum", CoinIds.forAsset(Chain.ETH, null))
        assertEquals("ethereum", CoinIds.forAsset(Chain.BASE, null))
        assertEquals("ethereum", CoinIds.forAsset(Chain.ARB, null))
        assertEquals("bitcoin", CoinIds.forAsset(Chain.BTC, null))
        assertEquals("solana", CoinIds.forAsset(Chain.SOL, null))
        assertEquals("binancecoin", CoinIds.forAsset(Chain.BNB, null))
        assertEquals("polygon-ecosystem-token", CoinIds.forAsset(Chain.POL, null))
    }

    @Test fun evmTokenLookup_isCaseInsensitive() {
        assertEquals("usd-coin", CoinIds.forAsset(Chain.ETH, "0xA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48"))
    }

    @Test fun solanaMintLookup_isCaseSensitive() {
        assertEquals("usd-coin", CoinIds.forAsset(Chain.SOL, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"))
        assertNull(CoinIds.forAsset(Chain.SOL, "epjfwdd5aufqssqem2qn1xzybapc8g4weggkzwytdt1v"))
    }

    @Test fun unknownOrWrongChainTokens_getNoPrice() {
        assertNull(CoinIds.forAsset(Chain.ETH, "0x0000000000000000000000000000000000000001"))
        // Ethereum's USDC address means nothing on Base: never price by address alone.
        assertNull(CoinIds.forAsset(Chain.BASE, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"))
        assertNull(CoinIds.forAsset(Chain.BTC, "anything"))
    }
}
