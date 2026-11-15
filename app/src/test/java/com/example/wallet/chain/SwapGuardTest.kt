package com.example.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Keys
import java.math.BigInteger

/**
 * The guard decides whether the wallet signs calldata supplied by a third-party API, so most tests
 * here are about what must be REJECTED. Calldata is built by hand in the layout of
 * swapExactAmountIn(address,(address,address,uint256,uint256,uint256,bytes32,address),uint256,bytes,bytes);
 * the selector e3ead59e was checked against keccak256 of that signature with an independent implementation.
 */
class SwapGuardTest {
    private val user = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private val attacker = "0x00000000000000000000000000000000deadbeef"
    private val usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    private val weth = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"
    private val router = SwapGuard.AUGUSTUS_V6

    private val eth = SwapAsset(null, "ETH", 18)
    private val usdcAsset = SwapAsset(usdc, "USDC", 6)
    private val wethAsset = SwapAsset(weth, "WETH", 18)

    private val oneEth = BigInteger.TEN.pow(18)
    private val expectedUsdc = BigInteger.valueOf(2_500_000_000L) // 2,500 USDC
    private val goodMin = SwapGuard.minOut(expectedUsdc, 50)       // 0.5% slippage, as the wallet requests

    private fun word(hex: String) = hex.removePrefix("0x").lowercase().padStart(64, '0')
    private fun word(v: BigInteger) = word(v.toString(16))

    private fun calldata(
        selector: String = SwapGuard.SELECTOR_SWAP_EXACT_AMOUNT_IN,
        src: String = SwapGuard.NATIVE_PLACEHOLDER,
        dest: String = usdc,
        from: BigInteger = oneEth,
        min: BigInteger = goodMin,
        beneficiary: String = user
    ) = "0x" + selector +
        word("0x00000000000000000000000000000000000000e1") + // executor
        word(src) + word(dest) + word(from) + word(min) +
        word(expectedUsdc) +                                  // quotedAmount
        word("0x00") +                                        // metadata
        word(beneficiary) +
        word("0x00") +                                        // partnerAndFee
        word("0xa0") + word("0xc0") + word("0x00") + word("0x00")

    private fun check(
        to: String = router,
        value: BigInteger = oneEth,
        data: String = calldata(),
        src: SwapAsset = eth,
        dest: SwapAsset = usdcAsset,
        amountIn: BigInteger = oneEth,
        expectedOut: BigInteger = expectedUsdc
    ) = SwapGuard.validateSwapTx(to, value, data, src, dest, amountIn, expectedOut, user)

    // ---- accepted ----
    @Test fun nativeToToken_isAccepted() = assertNull(check())

    @Test fun tokenToNative_isAccepted() {
        val amountIn = BigInteger.valueOf(1_000_000_000L) // 1,000 USDC
        val out = BigInteger.TEN.pow(17) * BigInteger.valueOf(4) // 0.4 ETH
        val data = calldata(src = usdc, dest = SwapGuard.NATIVE_PLACEHOLDER, from = amountIn, min = SwapGuard.minOut(out, 50))
        assertNull(check(value = BigInteger.ZERO, data = data, src = usdcAsset, dest = eth, amountIn = amountIn, expectedOut = out))
    }

    @Test fun tokenToToken_isAccepted() {
        val data = calldata(src = usdc, dest = weth, from = BigInteger.valueOf(5_000_000), min = SwapGuard.minOut(oneEth, 50))
        assertNull(check(value = BigInteger.ZERO, data = data, src = usdcAsset, dest = wethAsset,
            amountIn = BigInteger.valueOf(5_000_000), expectedOut = oneEth))
    }

    @Test fun zeroBeneficiary_meansMsgSender_isAccepted() =
        assertNull(check(data = calldata(beneficiary = "0x0000000000000000000000000000000000000000")))

    @Test fun beneficiaryAndRouterAreCaseInsensitive() {
        assertNull(check(to = router.lowercase(), data = calldata(beneficiary = user.lowercase())))
    }

    @Test fun minimumOutputExactlyAtTheFloor_isAccepted() =
        assertNull(check(data = calldata(min = SwapGuard.minOut(expectedUsdc, SwapGuard.MAX_SLIPPAGE_BPS))))

    // ---- rejected: the cases that protect funds ----
    @Test fun wrongRouter_isRejected() =
        assertNotNull(check(to = attacker))

    @Test fun payoutToAnotherAddress_isRejected() =
        assertEquals("Swap would pay out to a different address", check(data = calldata(beneficiary = attacker)))

    @Test fun wrongSourceToken_isRejected() =
        assertEquals("Swap source token mismatch", check(data = calldata(src = usdc)))

    @Test fun wrongDestinationToken_isRejected() =
        assertEquals("Swap destination token mismatch", check(data = calldata(dest = weth)))

    @Test fun differentInputAmount_isRejected() =
        assertEquals("Swap amount mismatch", check(data = calldata(from = oneEth * BigInteger.TEN)))

    @Test fun minimumOutputBelowTheFloor_isRejected() {
        val tooLow = SwapGuard.minOut(expectedUsdc, SwapGuard.MAX_SLIPPAGE_BPS) - BigInteger.ONE
        assertEquals("Swap minimum output is too low", check(data = calldata(min = tooLow)))
        assertNotNull(check(data = calldata(min = BigInteger.ONE))) // "accept anything" must never pass
    }

    @Test fun nativeSource_wrongValue_isRejected() {
        assertEquals("Unexpected transaction value", check(value = oneEth * BigInteger.TEN))
        assertNotNull(check(value = BigInteger.ZERO))
    }

    @Test fun tokenSource_withEthAttached_isRejected() {
        val data = calldata(src = usdc, from = BigInteger.valueOf(1_000_000))
        assertNotNull(check(value = BigInteger.ONE, data = data, src = usdcAsset, amountIn = BigInteger.valueOf(1_000_000)))
    }

    @Test fun otherFunctionSelector_isRejected() {
        // swapExactAmountOut and anything else we have not modelled must fail closed
        assertEquals("Unrecognised swap call data", check(data = calldata(selector = "7f457675")))
        assertEquals("Unrecognised swap call data", check(data = calldata(selector = "a9059cbb")))
    }

    @Test fun malformedCalldata_isRejected() {
        assertNotNull(check(data = "0x"))
        assertNotNull(check(data = ""))
        assertNotNull(check(data = calldata().take(2 + 8 + 64 * 5)))            // truncated
        assertNotNull(check(data = calldata().replace("e3ead59e", "zzzzzzzz"))) // not hex
    }

    @Test fun dirtyAddressWord_isRejected() {
        // A "token" word with non-zero upper bytes is not an address: reject instead of truncating it.
        val dirty = calldata().replaceFirst(word(usdc), "ff" + word(usdc).substring(2))
        assertNull(SwapGuard.decodeSwapExactAmountIn(dirty))
        assertNotNull(check(data = dirty))
    }

    // ---- decoder ----
    @Test fun decoder_readsEveryField() {
        val d = SwapGuard.decodeSwapExactAmountIn(calldata())!!
        assertEquals(SwapGuard.NATIVE_PLACEHOLDER.lowercase(), d.srcToken)
        assertEquals(usdc.lowercase(), d.destToken)
        assertEquals(oneEth, d.fromAmount)
        assertEquals(goodMin, d.minToAmount)
        assertEquals(user.lowercase(), d.beneficiary)
    }

    // ---- approvals ----
    @Test fun approvalSpender_mustBeAllowListed() {
        assertTrue(SwapGuard.isAllowedSpender(SwapGuard.AUGUSTUS_V6))
        assertTrue(SwapGuard.isAllowedSpender(SwapGuard.TOKEN_TRANSFER_PROXY.lowercase()))
        assertFalse(SwapGuard.isAllowedSpender(attacker))
        assertFalse(SwapGuard.isAllowedSpender(""))
    }

    @Test fun approveCalldata_matchesReference() {
        assertEquals(
            "0x095ea7b3000000000000000000000000" + SwapGuard.AUGUSTUS_V6.removePrefix("0x").lowercase() +
                "00000000000000000000000000000000000000000000000000000000000f4240",
            Erc20.approveData(SwapGuard.AUGUSTUS_V6, BigInteger.valueOf(1_000_000))
        )
    }

    // ---- constants and math ----
    @Test fun hardCodedAddresses_areValidEip55() {
        for (a in listOf(SwapGuard.AUGUSTUS_V6, SwapGuard.TOKEN_TRANSFER_PROXY, SwapGuard.NATIVE_PLACEHOLDER)) {
            assertEquals(a, Keys.toChecksumAddress(a))
        }
    }

    @Test fun minOut_math() {
        assertEquals(BigInteger.valueOf(995), SwapGuard.minOut(BigInteger.valueOf(1000), 50))
        assertEquals(BigInteger.valueOf(990), SwapGuard.minOut(BigInteger.valueOf(1000), 100))
        assertEquals(BigInteger.ZERO, SwapGuard.minOut(BigInteger.ZERO, 50))
    }

    @Test fun quote_displayAmountsUseTokenDecimals() {
        val q = SwapQuote(Chain.ETH, eth, usdcAsset, oneEth, expectedUsdc, 50, false, null)
        assertEquals("1", q.amountIn.stripTrailingZeros().toPlainString())
        assertEquals("2500", q.expectedOut.stripTrailingZeros().toPlainString())
        assertEquals("2487.5", q.minOut.stripTrailingZeros().toPlainString())
    }
}
