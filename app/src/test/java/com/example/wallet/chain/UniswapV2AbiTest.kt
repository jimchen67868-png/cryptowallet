package com.example.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.web3j.crypto.Keys
import java.math.BigInteger

/**
 * Uniswap v2 calldata is built by the wallet itself, so these golden values (produced by an independent ABI
 * encoder, tools/verify/uniswap_v2_golden.py) protect against a wrong selector, a wrong dynamic-array offset
 * or a mis-ordered field.
 */
class UniswapV2AbiTest {
    private val weth = "0x7b79995e5f793a07bc00c21412e50ecae098e7f9"
    private val usdc = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
    private val dai = "0x68194a729c2450ad26072b3d33adacbcef39d574"
    private val user = "0x9858effd232b4033e47d90003d41ec34ecaeda94"
    private val one = BigInteger.TEN.pow(18)
    private val five = BigInteger.valueOf(5_000_000)
    private val minOut = BigInteger.valueOf(123456789)
    private val deadline = 1_700_001_200L

    @Test fun selectors_matchTheSolidityFunctionSignatures() {
        assertEquals("d06ca61f", UniswapV2Abi.SELECTOR_GET_AMOUNTS_OUT)
        assertEquals("38ed1739", UniswapV2Abi.SELECTOR_SWAP_EXACT_TOKENS_FOR_TOKENS)
        assertEquals("7ff36ab5", UniswapV2Abi.SELECTOR_SWAP_EXACT_ETH_FOR_TOKENS)
        assertEquals("18cbafe5", UniswapV2Abi.SELECTOR_SWAP_EXACT_TOKENS_FOR_ETH)
        assertEquals("0xad5c4648", UniswapV2Abi.wethCall())
    }

    @Test fun getAmountsOut_direct() = assertEquals(AMOUNTS_DIRECT, UniswapV2Abi.getAmountsOut(one, listOf(weth, usdc)))

    @Test fun getAmountsOut_throughWeth() = assertEquals(AMOUNTS_HOP, UniswapV2Abi.getAmountsOut(five, listOf(usdc, weth, dai)))

    @Test fun swapExactTokensForTokens_direct() =
        assertEquals(TOK_TOK, UniswapV2Abi.swapExactTokensForTokens(five, minOut, listOf(usdc, dai), user, deadline))

    @Test fun swapExactTokensForTokens_throughWeth() =
        assertEquals(TOK_TOK_HOP, UniswapV2Abi.swapExactTokensForTokens(five, minOut, listOf(usdc, weth, dai), user, deadline))

    @Test fun swapExactEthForTokens() =
        assertEquals(ETH_TOK, UniswapV2Abi.swapExactEthForTokens(minOut, listOf(weth, usdc), user, deadline))

    @Test fun swapExactTokensForEth() =
        assertEquals(TOK_ETH, UniswapV2Abi.swapExactTokensForEth(five, minOut, listOf(usdc, weth), user, deadline))

    @Test fun mixedCaseAddressesEncodeIdentically() {
        val checksummed = listOf(Keys.toChecksumAddress(weth), Keys.toChecksumAddress(usdc))
        assertEquals(AMOUNTS_DIRECT, UniswapV2Abi.getAmountsOut(one, checksummed))
    }

    @Test fun outOfRangeAndShortPathsAreRejected() {
        for (call in listOf<() -> Unit>(
            { UniswapV2Abi.getAmountsOut(BigInteger.ONE.shiftLeft(256), listOf(weth, usdc)) },
            { UniswapV2Abi.getAmountsOut(BigInteger.valueOf(-1), listOf(weth, usdc)) },
            { UniswapV2Abi.getAmountsOut(one, listOf(weth)) },
            { UniswapV2Abi.swapExactEthForTokens(minOut, emptyList(), user, deadline) }
        )) {
            try { call(); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) { /* expected */ }
        }
    }

    // ---- paths ----

    @Test fun paths_directThenWethHop() =
        assertEquals(listOf(listOf(usdc, dai), listOf(usdc, weth, dai)), UniswapV2Abi.candidatePaths(usdc, dai, weth))

    @Test fun paths_noHopWhenAnEndIsWeth() {
        assertEquals(listOf(listOf(weth, usdc)), UniswapV2Abi.candidatePaths(weth, usdc, weth))
        assertEquals(listOf(listOf(usdc, weth)), UniswapV2Abi.candidatePaths(usdc, weth, weth))
    }

    @Test fun paths_sameTokenIsRejected() {
        try { UniswapV2Abi.candidatePaths(usdc, usdc.uppercase().replace("0X", "0x"), weth); fail("expected IllegalArgumentException") }
        catch (e: IllegalArgumentException) { /* expected */ }
    }

    // ---- decoding eth_call results ----

    private fun w(v: Long) = v.toString(16).padStart(64, '0')

    @Test fun lastAmount_readsTheFinalOutput() {
        // uint256[]: offset 0x20, length 3, [5000000, 777, 42]
        val data = "0x" + w(0x20) + w(3) + w(5_000_000) + w(777) + w(42)
        assertEquals(BigInteger.valueOf(42), UniswapV2Abi.lastAmount(data, 3))
        // two-element path
        assertEquals(BigInteger.valueOf(9), UniswapV2Abi.lastAmount("0x" + w(0x20) + w(2) + w(1) + w(9), 2))
    }

    @Test fun lastAmount_rejectsMalformedData() {
        assertNull(UniswapV2Abi.lastAmount(null, 2))
        assertNull(UniswapV2Abi.lastAmount("0x", 2))
        assertNull("length does not match the path", UniswapV2Abi.lastAmount("0x" + w(0x20) + w(3) + w(1) + w(2) + w(3), 2))
        assertNull("truncated", UniswapV2Abi.lastAmount("0x" + w(0x20) + w(3) + w(1) + w(2), 3))
        assertNull("absurd offset", UniswapV2Abi.lastAmount("0x" + "f".repeat(64) + w(2) + w(1) + w(2), 2))
        assertNull("length < 2", UniswapV2Abi.lastAmount("0x" + w(0x20) + w(1) + w(5), 1))
    }

    @Test fun addressFromWord_acceptsOnlyAProperAddress() {
        assertEquals(weth, UniswapV2Abi.addressFromWord("0x" + "0".repeat(24) + weth.removePrefix("0x")))
        assertNull(UniswapV2Abi.addressFromWord(null))
        assertNull(UniswapV2Abi.addressFromWord("0x"))
        assertNull("zero address", UniswapV2Abi.addressFromWord("0x" + "0".repeat(64)))
        assertNull("dirty upper bytes", UniswapV2Abi.addressFromWord("0x" + "0".repeat(23) + "1" + weth.removePrefix("0x")))
        assertNull("too long", UniswapV2Abi.addressFromWord("0x" + "0".repeat(26) + weth.removePrefix("0x")))
    }

    // ---- choosing between v3 and v2 ----

    private fun bi(v: Long) = BigInteger.valueOf(v)

    @Test fun venue_higherOutputWins() {
        assertEquals(true, UniswapVenue.preferV2(bi(100), bi(101)))
        assertEquals(false, UniswapVenue.preferV2(bi(101), bi(100)))
    }

    @Test fun venue_tieGoesToV3() = assertEquals(false, UniswapVenue.preferV2(bi(100), bi(100)))

    @Test fun venue_onlyOneHavingAPoolIsUsed() {
        assertEquals(true, UniswapVenue.preferV2(null, bi(1)))
        assertEquals(false, UniswapVenue.preferV2(bi(1), null))
    }

    @Test fun venue_noPoolAnywhereIsNull() = assertNull(UniswapVenue.preferV2(null, null))

    // ---- deployments ----

    @Test fun sepoliaRouter_isAValidChecksummedAddress() {
        val r = UniswapV2Deployments.SEPOLIA.router
        assertTrue(r.matches(Regex("0x[0-9a-fA-F]{40}")))
        assertEquals(Keys.toChecksumAddress(r), r)
    }

    private companion object {
        const val AMOUNTS_DIRECT = "0xd06ca61f0000000000000000000000000000000000000000000000000de0b6b3a7640000000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000020000000000000000000000007b79995e5f793a07bc00c21412e50ecae098e7f90000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c7238"
        const val AMOUNTS_HOP = "0xd06ca61f00000000000000000000000000000000000000000000000000000000004c4b40000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000030000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c72380000000000000000000000007b79995e5f793a07bc00c21412e50ecae098e7f900000000000000000000000068194a729c2450ad26072b3d33adacbcef39d574"
        const val TOK_TOK = "0x38ed173900000000000000000000000000000000000000000000000000000000004c4b4000000000000000000000000000000000000000000000000000000000075bcd1500000000000000000000000000000000000000000000000000000000000000a00000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda94000000000000000000000000000000000000000000000000000000006553f5b000000000000000000000000000000000000000000000000000000000000000020000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c723800000000000000000000000068194a729c2450ad26072b3d33adacbcef39d574"
        const val TOK_TOK_HOP = "0x38ed173900000000000000000000000000000000000000000000000000000000004c4b4000000000000000000000000000000000000000000000000000000000075bcd1500000000000000000000000000000000000000000000000000000000000000a00000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda94000000000000000000000000000000000000000000000000000000006553f5b000000000000000000000000000000000000000000000000000000000000000030000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c72380000000000000000000000007b79995e5f793a07bc00c21412e50ecae098e7f900000000000000000000000068194a729c2450ad26072b3d33adacbcef39d574"
        const val ETH_TOK = "0x7ff36ab500000000000000000000000000000000000000000000000000000000075bcd1500000000000000000000000000000000000000000000000000000000000000800000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda94000000000000000000000000000000000000000000000000000000006553f5b000000000000000000000000000000000000000000000000000000000000000020000000000000000000000007b79995e5f793a07bc00c21412e50ecae098e7f90000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c7238"
        const val TOK_ETH = "0x18cbafe500000000000000000000000000000000000000000000000000000000004c4b4000000000000000000000000000000000000000000000000000000000075bcd1500000000000000000000000000000000000000000000000000000000000000a00000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda94000000000000000000000000000000000000000000000000000000006553f5b000000000000000000000000000000000000000000000000000000000000000020000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c72380000000000000000000000007b79995e5f793a07bc00c21412e50ecae098e7f9"
    }
}
