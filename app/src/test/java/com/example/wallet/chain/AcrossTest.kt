package com.example.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.web3j.crypto.Keys
import java.math.BigInteger

/**
 * The bridge transaction is built by the wallet, so the golden calldata (from an independent ABI encoder,
 * tools/verify/across_golden.py) guards against a wrong selector or a mis-ordered field. AcrossGuard tests cover
 * what must be REJECTED when the API returns something unexpected.
 */
class AcrossTest {
    private val user = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private val zero = "0x0000000000000000000000000000000000000000"
    private val wethMain = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"
    private val wethBase = "0x4200000000000000000000000000000000000006"
    private val usdcMain = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    private val usdcArb = "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
    private val relayer = "0x07aE8551Be970cB1cCa11Dd7a11F47Ae82e70E67"
    private val one = BigInteger.TEN.pow(18)

    // ---- calldata ----
    @Test fun depositV3_ethEthereumToBase() = assertEquals(
        ETH_TO_BASE,
        AcrossAbi.depositV3(user, user, wethMain, wethBase, one, BigInteger("999500000000000000"), 8453, zero, 1700000000, 1700021600, 0)
    )

    @Test fun depositV3_usdcWithExclusiveRelayer() = assertEquals(
        USDC_TO_ARB,
        AcrossAbi.depositV3(user, user, usdcMain, usdcArb, BigInteger.valueOf(250_000_000), BigInteger.valueOf(249_100_000),
            42161, relayer, 1700000123, 1700021723, 1700000200)
    )

    @Test fun depositV3_layout() {
        val data = AcrossAbi.depositV3(user, user, wethMain, wethBase, one, one, 8453, zero, 1, 2, 0)
        assertEquals("0x" + AcrossAbi.SELECTOR_DEPOSIT_V3, data.substring(0, 10))
        assertEquals(2 + 8 + 64 * 13, data.length) // selector + 12 head words + empty bytes length
    }

    @Test fun depositV3_rejectsValuesThatDoNotFit() {
        for (bad in listOf(-1L, 0x1_0000_0000L)) {
            try {
                AcrossAbi.depositV3(user, user, wethMain, wethBase, one, one, 8453, zero, bad, 2, 0)
                fail("expected IllegalArgumentException for $bad")
            } catch (e: IllegalArgumentException) { /* expected */ }
        }
        try {
            AcrossAbi.depositV3(user, user, wethMain, wethBase, BigInteger.ONE.shiftLeft(256), one, 8453, zero, 1, 2, 0)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    // ---- quote validation ----
    private val now = 1_700_000_000L
    private val ethRoute = AcrossRoutes.MAINNET.first { it.chain == Chain.ETH }

    private fun fees(
        fee: BigInteger = BigInteger.valueOf(500_000_000_000_000L),
        timestamp: Long = now - 10,
        fillDeadline: Long = now + 21_600,
        relayer: String = zero,
        exclusivity: Long = 0,
        spokePool: String = ethRoute.spokePool,
        tooLow: Boolean = false
    ) = SuggestedFees(fee, timestamp, fillDeadline, relayer, exclusivity, spokePool, 5, tooLow)

    private fun check(f: SuggestedFees, amount: BigInteger = one) = AcrossGuard.validate(f, ethRoute, amount, now)

    @Test fun guard_acceptsAnOrdinaryQuote() = assertNull(check(fees()))

    @Test fun guard_spokePoolMustBeTheAllowListedOne() {
        assertEquals("Unexpected bridge contract in the quote",
            check(fees(spokePool = "0x00000000000000000000000000000000deadbeef")))
        assertNull(check(fees(spokePool = ethRoute.spokePool.lowercase())))
        // another chain's SpokePool is not valid for this origin
        assertNotNull(check(fees(spokePool = AcrossRoutes.MAINNET.first { it.chain == Chain.BASE }.spokePool)))
    }

    @Test fun guard_rejectsTooLowAndNegativeAndHugeFees() {
        assertNotNull(check(fees(tooLow = true)))
        assertNotNull(check(fees(fee = BigInteger.valueOf(-1))))
        assertNotNull(check(fees(fee = one)))              // fee equals the amount
        assertNotNull(check(fees(fee = one.multiply(BigInteger.TEN))))
    }

    @Test fun guard_feeCapIsFivePercent() {
        val amount = BigInteger.valueOf(100_000)
        assertNull(check(fees(fee = BigInteger.valueOf(5_000)), amount))     // exactly 5%
        assertNotNull(check(fees(fee = BigInteger.valueOf(5_001)), amount))  // just over
        assertNull(check(fees(fee = BigInteger.ZERO), amount))
    }

    @Test fun guard_timestampMustBeRecent() {
        assertNull(check(fees(timestamp = now - 900)))
        assertNull(check(fees(timestamp = now + 900)))
        assertNotNull(check(fees(timestamp = now - 901)))
        assertNotNull(check(fees(timestamp = now + 901)))
    }

    @Test fun guard_fillDeadlineMustBeInTheNearFuture() {
        assertNull(check(fees(fillDeadline = now + 86_400)))
        assertNotNull(check(fees(fillDeadline = now + 86_401)))
        assertNotNull(check(fees(fillDeadline = now)))
        assertNotNull(check(fees(fillDeadline = now - 5)))
    }

    @Test fun guard_relayerAndExclusivityMustBeSane() {
        assertNull(check(fees(relayer = relayer, exclusivity = now + 200)))
        assertNull(check(fees(relayer = relayer, exclusivity = 30)))     // relative offsets are fine too
        assertNotNull(check(fees(relayer = "0x123")))
        assertNotNull(check(fees(relayer = "not an address")))
        assertNotNull(check(fees(exclusivity = now + 86_401)))
        assertNotNull(check(fees(exclusivity = -1)))
    }

    // ---- routes ----
    @Test fun routes_spokePoolsAreChecksummedAndDistinct() {
        val pools = AcrossRoutes.MAINNET.map { it.spokePool }.filter { it.isNotEmpty() } // BNB Chain: LI.FI only, no direct deposit
        for (p in pools) assertEquals(p, Keys.toChecksumAddress(p))
        assertEquals(pools.size, pools.map { it.lowercase() }.toSet().size)
    }

    @Test fun routes_chainsAndNativeCoins() {
        val byChain = AcrossRoutes.MAINNET.associateBy { it.chain }
        assertEquals(setOf(Chain.ETH, Chain.BASE, Chain.ARB, Chain.POL, Chain.BNB), byChain.keys)
        assertEquals(listOf(1L, 8453L, 42161L, 137L, 56L), listOf(Chain.ETH, Chain.BASE, Chain.ARB, Chain.POL, Chain.BNB).map { byChain.getValue(it).chainId })
        assertTrue(byChain.getValue(Chain.ETH).nativeIsEth && byChain.getValue(Chain.BASE).nativeIsEth && byChain.getValue(Chain.ARB).nativeIsEth)
        assertFalse(byChain.getValue(Chain.POL).nativeIsEth) // POL is not ETH: native bridging is refused there
    }

    @Test fun routes_everyChainHasWethAndUsdc() {
        for (r in AcrossRoutes.MAINNET) {
            if (r.chain != Chain.BNB) assertNotNull("${r.chain} WETH", r.tokens.firstOrNull { it.symbol == "WETH" })
            assertNotNull("${r.chain} USDC", r.tokens.firstOrNull { it.symbol == "USDC" })
        }
    }

    @Test fun routes_sameSymbolHasSameDecimalsOnEveryChain() {
        // BNB Chain's USDC/USDT are 18 decimals (documented in EvmTokens): that route converts amounts explicitly.
        val bySymbol = AcrossRoutes.MAINNET.filter { it.chain != Chain.BNB }.flatMap { r -> r.tokens.map { it.symbol to it.decimals } }.groupBy({ it.first }, { it.second })
        for ((symbol, decimals) in bySymbol) assertEquals("$symbol decimals differ between chains", 1, decimals.toSet().size)
    }

    // ---- quote maths ----
    @Test fun quote_displayAmountsAndFeePercent() {
        val q = BridgeQuote(Chain.ETH, Chain.BASE, SwapAsset(null, "ETH", 18), one, BigInteger("999500000000000000"),
            BigInteger("500000000000000"), 18, "ETH (or WETH)", 12, false)
        assertEquals("1", q.amountIn.stripTrailingZeros().toPlainString())
        assertEquals("0.9995", q.amountOut.stripTrailingZeros().toPlainString())
        assertEquals("0.0005", q.fee.stripTrailingZeros().toPlainString())
        assertEquals(0.05, q.feePercent, 0.0001)
    }

    private companion object {
        const val ETH_TO_BASE =
            "0x7b939232" +
            "0000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda94" +
            "0000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda94" +
            "000000000000000000000000c02aaa39b223fe8d0a0e5c4f27ead9083c756cc2" +
            "0000000000000000000000004200000000000000000000000000000000000006" +
            "0000000000000000000000000000000000000000000000000de0b6b3a7640000" +
            "0000000000000000000000000000000000000000000000000ddeeff45500c000" +
            "0000000000000000000000000000000000000000000000000000000000002105" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000006553f100" +
            "0000000000000000000000000000000000000000000000000000000065544560" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000180" +
            "0000000000000000000000000000000000000000000000000000000000000000"
        const val USDC_TO_ARB =
            "0x7b939232" +
            "0000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda94" +
            "0000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda94" +
            "000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" +
            "000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e5831" +
            "000000000000000000000000000000000000000000000000000000000ee6b280" +
            "000000000000000000000000000000000000000000000000000000000ed8f6e0" +
            "000000000000000000000000000000000000000000000000000000000000a4b1" +
            "00000000000000000000000007ae8551be970cb1cca11dd7a11f47ae82e70e67" +
            "000000000000000000000000000000000000000000000000000000006553f17b" +
            "00000000000000000000000000000000000000000000000000000000655445db" +
            "000000000000000000000000000000000000000000000000000000006553f1c8" +
            "0000000000000000000000000000000000000000000000000000000000000180" +
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
