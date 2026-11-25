package com.example.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.web3j.crypto.Keys

/**
 * PancakeSwap V2 on BNB Chain testnet (a Uniswap V2 fork): the swap code is the same Uniswap v2 code, so only the configuration is new.
 * Calldata encoding is covered by UniswapV2AbiTest. NOT covered: the live router and whether its pools have liquidity.
 */
class PancakeTestnetTest {
    private val cfg = UniswapV2Deployments.PANCAKE_BSC_TESTNET

    @Test fun addresses_areChecksummed() {
        for (a in listOf(cfg.router, cfg.wrapped!!, UniswapV2Deployments.BSC_TESTNET_BUSD)) {
            assertEquals("not EIP-55: $a", a, Keys.toChecksumAddress(a))
        }
    }

    @Test fun config_isDistinctFromUniswapOnSepolia() {
        assertEquals("PancakeSwap v2", cfg.name)
        assertEquals("Uniswap v2", UniswapV2Deployments.SEPOLIA.name)
        assertNull(UniswapV2Deployments.SEPOLIA.wrapped)
        assertNotEquals(UniswapV2Deployments.SEPOLIA.router, cfg.router)
    }

    @Test fun knownValues() {
        assertEquals("0xD99D1c33F9fC3444f8101754aBC46c52416550D1", cfg.router)
        assertEquals("0xae13d989daC2f0dEbFf460aC112a837C89BAa7cd", cfg.wrapped)
    }
}
