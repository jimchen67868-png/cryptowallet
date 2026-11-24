package com.example.wallet.chain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Keys
import java.math.BigInteger

/**
 * The guard against REAL LI.FI quotes captured on 2026-11-23 with tools/capture_lifi_all.sh (quote requests only, nothing was sent):
 * USDC from Ethereum, Base, Polygon, Arbitrum and BNB Chain, and native ETH between Ethereum, Base and Arbitrum.
 * tools/verify/lifi_guard_check.py is an independent Python port of the same rules and passes on the same files.
 * Not covered: the transactions have never been broadcast by this wallet.
 */
class LiFiCaptureTest {
    private val user = "0xa3a0fccdf214159ce35d4a0066bb32fe10733d67"
    private val zero = LiFiPins.ZERO
    private val usdcEth = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    private val usdcBase = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
    private val usdcArb = "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
    private val usdcPol = "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359"
    private val usdcBnb = "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d"
    private val usdc2 = "2000000"
    private val ethSmall = "1000000000000000"

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/lifi/$name.json")!!.bufferedReader().readText()

    private fun quote(name: String, mutate: JSONObject.() -> Unit = {}): LiFiQuote =
        LiFiQuoteParser.parse(JSONObject(fixture(name)).also(mutate).toString())

    /** Quotes are valid around the moment they were made: the Across deposit timestamp word, plus 20 s. */
    private fun nowFor(q: LiFiQuote) = LiFiCalldata.decode(q.txData)!!.across[7].toLong() + 20

    private fun req(from: Long, to: Long, fromToken: String?, toToken: String, amount: String, fromDec: Int = 6, toDec: Int = 6) =
        LiFiRequest(user, from, to, fromToken, toToken, BigInteger(amount), fromDec, toDec)

    private fun verdict(name: String, r: LiFiRequest, mutate: JSONObject.() -> Unit = {}): String? {
        val q = quote(name, mutate)
        return LiFiGuard.validate(q, r, nowFor(q))
    }

    private fun refused(contains: String, name: String, r: LiFiRequest, mutate: JSONObject.() -> Unit = {}) {
        val reason = verdict(name, r, mutate)
        assertNotNull("expected a refusal containing '$contains' but the guard accepted $name", reason)
        assertTrue("'$reason' should contain '$contains'", reason!!.contains(contains))
    }

    // ---- real token quotes are accepted ----

    @Test fun tokens_fromEveryPinnedNetwork() {
        assertNull(verdict("eth_to_arb_usdc", req(1, 42161, usdcEth, usdcArb, usdc2)))
        assertNull(verdict("base_to_arb_usdc", req(8453, 42161, usdcBase, usdcArb, usdc2)))
        assertNull(verdict("pol_to_arb_usdc", req(137, 42161, usdcPol, usdcArb, usdc2)))
        assertNull(verdict("arb_to_eth_usdc", req(42161, 1, usdcArb, usdcEth, usdc2)))
        assertNull(verdict("arb_to_pol_usdc", req(42161, 137, usdcArb, usdcPol, usdc2)))
    }

    @Test fun bnbChain_usdcHas18Decimals_andNeedsTheDecimalsToBeKnown() {
        assertNull(verdict("bnb_to_arb_usdc", req(56, 42161, usdcBnb, usdcArb, "2000000000000000000", 18, 6)))
        assertNull(verdict("arb_to_bnb_usdc", req(42161, 56, usdcArb, usdcBnb, usdc2, 6, 18)))
        // without the decimals the amounts look 1e12 apart: the guard must not accept that by accident
        refused("fees", "bnb_to_arb_usdc", req(56, 42161, usdcBnb, usdcArb, "2000000000000000000"))
    }

    // ---- real native-coin quotes are accepted ----

    @Test fun nativeEth_betweenEthereumBaseAndArbitrum() {
        assertNull(verdict("eth_to_base_native", req(1, 8453, null, zero, ethSmall, 18, 18)))
        assertNull(verdict("base_to_eth_native", req(8453, 1, null, zero, ethSmall, 18, 18)))
        assertNull(verdict("arb_to_eth_native", req(42161, 1, null, zero, ethSmall, 18, 18)))
        assertNull(verdict("eth_to_arb_native", req(1, 42161, null, zero, ethSmall, 18, 18)))
    }

    @Test fun nativeFeeStep_isDecodedAndMatchesTheServiceFee() {
        val q = quote("eth_to_base_native")
        val d = LiFiCalldata.decode(q.txData)!!
        val fee = LiFiCalldata.decodeNativeFeeCall(d.swaps.single().callData)!!
        assertEquals(LiFiPins.ZERO, fee.token)
        val (recipient, amount) = fee.recipients.single()
        assertEquals("0xc06ebbefd94032b85424d51906e2a335efae264b", recipient)
        assertEquals(BigInteger("2500000000000"), amount)                       // 0.25% of 0.001 ETH
        assertEquals(BigInteger(ethSmall).subtract(amount), d.bridge.minAmount) // what remains is what gets bridged
        assertEquals(BigInteger(ethSmall), q.txValue)                           // the native amount travels as the value
    }

    @Test fun nativeFeeDecoder_isStrict() {
        val good = LiFiCalldata.decode(quote("eth_to_base_native").txData)!!.swaps.single().callData
        assertNotNull(LiFiCalldata.decodeNativeFeeCall(good))
        assertNull(LiFiCalldata.decodeNativeFeeCall(good + "00"))
        assertNull(LiFiCalldata.decodeNativeFeeCall(good.dropLast(2)))
        assertNull(LiFiCalldata.decodeNativeFeeCall("332d746b" + good.substring(8)))
        assertNull(LiFiCalldata.decodeNativeFeeCall(""))
        // a one-recipient array is required
        assertNull(LiFiCalldata.decodeNativeFeeCall(good.substring(0, 8 + 64) + "0".repeat(63) + "2" + good.substring(8 + 128)))
    }

    // ---- native requests that must be refused ----

    @Test fun native_onlyWhereTheWrappedCoinIsPinned() {
        refused("native coin", "eth_to_base_native", req(137, 8453, null, zero, ethSmall, 18, 18))   // Polygon: POL is not ETH
        refused("native coin", "eth_to_base_native", req(1, 137, null, zero, ethSmall, 18, 18))
        refused("native coin", "eth_to_base_native", req(56, 1, null, zero, ethSmall, 18, 18))
    }

    @Test fun native_mustAskForTheNativeCoinOnBothEnds() {
        refused("native coin", "eth_to_base_native", req(1, 8453, null, usdcBase, ethSmall, 18, 6))
    }

    @Test fun native_aTokenQuoteCannotAnswerANativeRequest_andViceVersa() {
        refused("different tokens", "eth_to_arb_usdc", req(1, 42161, null, zero, usdc2, 18, 18))
        refused("different tokens", "eth_to_base_native", req(1, 8453, usdcEth, usdcBase, ethSmall))
    }

    @Test fun native_transactionValueMustEqualTheAmount() {
        refused("value", "eth_to_base_native", req(1, 8453, null, zero, ethSmall, 18, 18)) {
            getJSONObject("transactionRequest").put("value", "0x38d7ea4c68001")
        }
        refused("value", "eth_to_base_native", req(1, 8453, null, zero, ethSmall, 18, 18)) {
            getJSONObject("transactionRequest").put("value", "0x0")
        }
        // and a token quote must carry no value
        refused("native coin", "eth_to_arb_usdc", req(1, 42161, usdcEth, usdcArb, usdc2)) {
            getJSONObject("transactionRequest").put("value", "0x1")
        }
    }

    @Test fun native_tamperedCalldataIsRefused() {
        val r = req(42161, 1, null, zero, ethSmall, 18, 18)
        val data = quote("arb_to_eth_native").txData
        fun with(old: String, new: String) = { j: JSONObject ->
            require(data.indexOf(old) >= 0 && data.indexOf(old) == data.lastIndexOf(old)) { "pattern must occur exactly once: $old" }
            j.getJSONObject("transactionRequest").put("data", data.replace(old, new))
            Unit
        }
        // the fee recipient
        refused("fee", "arb_to_eth_native", r, with("c06ebbefd94032b85424d51906e2a335efae264b", "00000000000000000000000000000000000bad01"))
        // the receiver of the bridged funds (your own address appears more than once: replace only the bridgeData receiver)
        val deposit = data.replace(user.removePrefix("0x"), "00000000000000000000000000000000000bad01")
        refused("different address", "arb_to_eth_native", r) { getJSONObject("transactionRequest").put("data", deposit) }
        // the deposited token (the origin's wrapped ETH) swapped for another
        refused("input token", "arb_to_eth_native", r, with("82af49447d8a07e3bd95bd0d56f35241523fbab1", "00000000000000000000000000000000000bad02"))
    }

    // ---- pins ----

    @Test fun pins_areTheSameContractsOnEveryNetwork_andChecksummed() {
        for (id in listOf(1L, 8453L, 137L, 42161L, 56L)) {
            val p = LiFiPins.forChain(id)!!
            assertEquals(LiFiPins.DIAMOND, p.diamond)
            assertEquals("0xDe2b920506EE931a6A2ABDF566526220214FBbAE", p.feeContract)
            assertEquals("0xC06ebbefD94032B85424D51906e2A335EFAe264B", p.feeRecipient)
            for (a in listOf(p.diamond, p.feeContract, p.feeRecipient)) assertEquals(a, Keys.toChecksumAddress(a))
        }
        assertNull(LiFiPins.forChain(10L)) // Optimism: nothing captured, not enabled
        for (a in LiFiPins.WRAPPED_NATIVE.values) assertEquals(a, Keys.toChecksumAddress(a))
        assertEquals(setOf(1L, 8453L, 42161L), LiFiPins.WRAPPED_NATIVE.keys)
    }

    @Test fun unpinnedOrigins_areStillRefused() {
        refused("isn't enabled", "eth_to_arb_usdc", req(10, 42161, usdcEth, usdcArb, usdc2))
        refused("isn't enabled", "eth_to_arb_usdc", req(43114, 42161, usdcEth, usdcArb, usdc2))
    }

    // ---- request building ----

    @Test fun api_nativeRequest_asksForTheNativeCoinOnBothEnds_acrossOnly() {
        val urls = ArrayList<String>()
        val api = LiFiApi("https://li.quest/v1") { url -> urls.add(url); HttpResult(200, fixture("eth_to_base_native")) }
        api.quote(LiFiRequest(user, 1L, 8453L, null, zero, BigInteger(ethSmall), 18, 18))
        val u = urls.single()
        assertTrue(u, u.contains("fromToken=$zero") && u.contains("toToken=$zero"))
        assertTrue(u, u.contains("allowBridges=across") && u.contains("fromChain=1&toChain=8453"))
    }

    @Test fun quoteModel_showsOutputInTheDestinationsDecimals() {
        // 2 USDC on BNB Chain (18 decimals) -> about 1.99 USDC on Arbitrum (6 decimals)
        val q = BridgeQuote(
            Chain.BNB, Chain.ARB, SwapAsset(usdcBnb, "USDC", 18), BigInteger("2000000000000000000"), BigInteger("1988613"),
            BigInteger("11387000000000000"), 18, "USDC", 60, true, outDecimals = 6
        )
        assertEquals("2", q.amountIn.stripTrailingZeros().toPlainString())
        assertEquals("1.988613", q.amountOut.toPlainString())
        assertEquals(0.57, q.feePercent, 0.01)
    }

    @Test fun routes_bnbIsALifiOnlyRoute() {
        val bnb = AcrossRoutes.MAINNET.first { it.chain == Chain.BNB }
        assertEquals(56L, bnb.chainId)
        assertEquals("", bnb.spokePool)
        assertTrue(bnb.tokens.any { it.symbol == "USDC" && it.decimals == 18 })
    }

}
