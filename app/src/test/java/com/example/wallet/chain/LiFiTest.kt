package com.example.wallet.chain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.web3j.crypto.Keys
import java.math.BigInteger

/**
 * LI.FI bridge: parser, calldata decoder, guard and client. FIXTURE is a REAL LI.FI response (USDC Arbitrum -> Base, 2 USDC)
 * reduced to the fields the wallet reads (see tools/verify/lifi_fixture_arb_base_usdc.json and lifi_decode.py).
 * Every guard test mutates one thing in it and checks the wallet refuses, because the guard is all that stands between
 * a tampered quote and a signature.
 */
class LiFiTest {
    private val user = "0xa3a0fccdf214159ce35d4a0066bb32fe10733d67"
    private val usdcArb = "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
    private val usdcBase = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
    private val attacker = "0x00000000000000000000000000000000000bad01"
    private val now = 1_794_999_400L // 77 s after the fixture's quoteTimestamp
    private val request = LiFiRequest(user, 42161L, 8453L, usdcArb, usdcBase, BigInteger.valueOf(2_000_000))

    private fun fixtureJson() = JSONObject(FIXTURE)
    private fun parse(mutate: JSONObject.() -> Unit = {}): LiFiQuote = LiFiQuoteParser.parse(fixtureJson().also(mutate).toString())
    private fun dataOf(q: LiFiQuote = parse()) = q.txData

    // payload word i starts after "0x" + 8 selector chars
    private fun setWord(data: String, i: Int, value: BigInteger): String {
        val start = 10 + i * 64
        return data.substring(0, start) + value.toString(16).padStart(64, '0') + data.substring(start + 64)
    }
    private fun setAddr(data: String, i: Int, address: String) = setWord(data, i, BigInteger(address.removePrefix("0x"), 16))
    private fun replaceOnce(data: String, old: String, new: String): String {
        require(data.indexOf(old) >= 0 && data.indexOf(old) == data.lastIndexOf(old)) { "pattern must occur exactly once" }
        return data.replace(old, new)
    }
    private fun withData(data: String) = parse { getJSONObject("transactionRequest").put("data", data) }
    /** Calldata and quote edited together so they agree with each other (to test the economic checks in isolation). */
    private fun withDataAndOutput(data: String, output: Long) = parse {
        getJSONObject("transactionRequest").put("data", data)
        getJSONObject("estimate").put("toAmount", output.toString()).put("toAmountMin", output.toString())
    }
    private fun check(q: LiFiQuote = parse(), r: LiFiRequest = request, at: Long = now) = LiFiGuard.validate(q, r, at)

    private fun assertRefused(contains: String, q: LiFiQuote, r: LiFiRequest = request, at: Long = now) {
        val reason = LiFiGuard.validate(q, r, at)
        assertNotNull("expected a refusal containing '$contains' but the guard accepted", reason)
        assertTrue("'$reason' should contain '$contains'", reason!!.contains(contains))
    }

    // ---- parser ----

    @Test fun parser_readsTheRealQuote() {
        val q = parse()
        assertEquals("across", q.tool)
        assertEquals(42161L, q.fromChainId); assertEquals(8453L, q.toChainId)
        assertEquals(BigInteger.valueOf(2_000_000), q.fromAmount)
        assertEquals(BigInteger.valueOf(1_991_702), q.toAmount); assertEquals(BigInteger.valueOf(1_991_702), q.toAmountMin)
        assertEquals("0x1231DEB6f5749EF6cE6943a275A1D3E7486F4EaE", q.approvalAddress)
        assertEquals("0x1231DEB6f5749EF6cE6943a275A1D3E7486F4EaE", q.txTo)
        assertEquals(BigInteger.ZERO, q.txValue); assertEquals(42161L, q.txChainId); assertEquals(user, q.txFrom)
        assertTrue(q.txData.startsWith("0x1794958f"))
    }

    @Test fun parser_readsHexValues() = assertEquals(BigInteger.TEN.pow(18), parse { getJSONObject("transactionRequest").put("value", "0xde0b6b3a7640000") }.txValue)

    @Test fun parser_surfacesLiFiErrors() {
        try { LiFiQuoteParser.parse("""{"message":"No available quotes for the requested transfer","code":1002}"""); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("No available quotes")) }
    }

    @Test fun parser_rejectsMalformedQuotes() {
        for (bad in listOf("", "not json", "{}", "[]")) {
            try { LiFiQuoteParser.parse(bad); fail("accepted: $bad") } catch (e: RuntimeException) { /* expected */ }
        }
        try { parse { remove("estimate") }; fail() } catch (e: RuntimeException) { /* expected */ }
        try { parse { getJSONObject("action").remove("fromAmount") }; fail() } catch (e: RuntimeException) { /* expected */ }
    }

    // ---- calldata decoder ----

    @Test fun decoder_readsTheRealCalldata() {
        val d = LiFiCalldata.decode(dataOf())!!
        assertEquals("1794958f", d.selector)
        assertEquals(user, d.bridge.receiver); assertEquals(usdcArb.lowercase(), d.bridge.sendingAssetId)
        assertEquals(BigInteger.valueOf(1_995_000), d.bridge.minAmount); assertEquals(BigInteger.valueOf(8453), d.bridge.destinationChainId)
        assertTrue(d.bridge.hasSourceSwaps); assertEquals(false, d.bridge.hasDestinationCall)
        assertEquals("across", d.bridge.bridge); assertEquals("lifi-api", d.bridge.integrator)
        assertEquals("0x0000000000000000000000000000000000000000", d.bridge.referrer)
        val s = d.swaps.single()
        assertEquals("0xde2b920506ee931a6a2abdf566526220214fbbae", s.callTo); assertEquals(s.callTo, s.approveTo)
        assertEquals(usdcArb.lowercase(), s.sendingAssetId); assertEquals(usdcArb.lowercase(), s.receivingAssetId)
        assertEquals(BigInteger.valueOf(2_000_000), s.fromAmount); assertTrue(s.requiresDeposit)
        val fee = LiFiCalldata.decodeFeeCall(s.callData)!!
        assertEquals(usdcArb.lowercase(), fee.token)
        assertEquals(listOf("0xc06ebbefd94032b85424d51906e2a335efae264b" to BigInteger.valueOf(5000)), fee.recipients)
        assertEquals(11, d.across.size)
        assertEquals(BigInteger.valueOf(1_991_702), d.across[4]); assertEquals(BigInteger.valueOf(1_794_999_323), d.across[7])
        assertEquals(BigInteger.valueOf(1_795_008_607), d.across[8]); assertEquals(BigInteger.ZERO, d.messageLength)
    }

    @Test fun decoder_rejectsAnythingNotExactlyThatShape() {
        val good = dataOf()
        val cases = mapOf(
            "empty" to "0x", "no prefix" to good.removePrefix("0x"), "odd length" to good + "0", "not hex" to good.dropLast(2) + "zz",
            "other selector" to "0xdeadbeef" + good.substring(10),
            "truncated" to good.dropLast(64), "trailing word" to good + "0".repeat(64),
            "misaligned offset" to setWord(good, 0, BigInteger.valueOf(0x61)),
            "bridge offset moved" to setWord(good, 0, BigInteger.valueOf(0x80)),
            "message not empty" to setWord(good, 44, BigInteger.ONE),
            "message offset moved" to setWord(good, 43, BigInteger.valueOf(0x180)),
            "too many swaps" to setWord(good, 17, BigInteger.valueOf(9)),
            "bool out of range" to setWord(good, 11, BigInteger.TWO),
            "dirty address" to setWord(good, 8, BigInteger.ONE.shiftLeft(200)),
            "huge" to "0x1794958f" + "0".repeat(40_000)
        )
        for ((name, data) in cases) assertNull(name, LiFiCalldata.decode(data))
        assertNotNull(LiFiCalldata.decode(good))
    }

    @Test fun feeCall_rejectsOtherShapes() {
        val fee = LiFiCalldata.decode(dataOf())!!.swaps.single().callData
        assertNotNull(LiFiCalldata.decodeFeeCall(fee))
        assertNull(LiFiCalldata.decodeFeeCall(fee.dropLast(2)))
        assertNull(LiFiCalldata.decodeFeeCall("deadbeef" + fee.substring(8)))
        assertNull(LiFiCalldata.decodeFeeCall(fee + "00"))
        assertNull(LiFiCalldata.decodeFeeCall(""))
    }

    // ---- guard: the real quote is accepted ----

    @Test fun guard_acceptsTheRealQuote() = assertNull(check())
    @Test fun guard_acceptsMixedCaseAddresses() =
        assertNull(check(r = LiFiRequest(Keys.toChecksumAddress(user), 42161L, 8453L, usdcArb.lowercase(), usdcBase.uppercase().replace("0X", "0x"), BigInteger.valueOf(2_000_000))))

    // ---- guard: the request ----

    @Test fun guard_nativeCoinIsNotSupportedYet() =
        assertEquals(LiFiGuard.NATIVE_UNSUPPORTED, LiFiGuard.validate(parse(), LiFiRequest(user, 42161L, 8453L, null, usdcBase, BigInteger.valueOf(2_000_000)), now))

    @Test fun guard_unverifiedOriginChainsAreRefused() {
        assertRefused("isn't enabled in this build", parse(), LiFiRequest(user, 1L, 8453L, usdcArb, usdcBase, BigInteger.valueOf(2_000_000)))
        assertRefused("isn't enabled in this build", parse(), LiFiRequest(user, 137L, 8453L, usdcArb, usdcBase, BigInteger.valueOf(2_000_000)))
    }

    @Test fun guard_wrongBridgeToolOrRoute() {
        assertRefused("doesn't support", parse { put("tool", "stargate") })
        assertRefused("different route", parse { getJSONObject("action").put("toChainId", 10) })
        assertRefused("different route", parse { getJSONObject("action").put("fromChainId", 1) })
        assertRefused("different tokens", parse { getJSONObject("action").getJSONObject("toToken").put("address", attacker) })
        assertRefused("different tokens", parse { getJSONObject("action").getJSONObject("fromToken").put("address", attacker) })
        assertRefused("different amount", parse(), LiFiRequest(user, 42161L, 8453L, usdcArb, usdcBase, BigInteger.valueOf(3_000_000)))
        assertRefused("different tokens", parse(), LiFiRequest(user, 42161L, 8453L, usdcArb, attacker, BigInteger.valueOf(2_000_000)))
    }

    // ---- guard: where the transaction goes ----

    @Test fun guard_transactionTarget() {
        assertRefused("Unexpected LI.FI contract", parse { getJSONObject("transactionRequest").put("to", attacker) })
        assertRefused("approval address", parse { getJSONObject("estimate").put("approvalAddress", attacker) })
        assertRefused("different network", parse { getJSONObject("transactionRequest").put("chainId", 1) })
        assertRefused("different sender", parse { getJSONObject("transactionRequest").put("from", attacker) })
        assertRefused("native coin", parse { getJSONObject("transactionRequest").put("value", "0x1") })
    }

    // ---- guard: the bridge data ----

    @Test fun guard_receiverTokenAndChainInTheCalldata() {
        val d = dataOf()
        assertRefused("different address", withData(setAddr(d, 8, attacker)))
        assertRefused("different token", withData(setAddr(d, 7, attacker)))
        assertRefused("different network", withData(setWord(d, 10, BigInteger.valueOf(10))))
        assertRefused("destination call", withData(setWord(d, 12, BigInteger.ONE)))
        assertRefused("referrer", withData(setAddr(d, 6, attacker)))
        assertRefused("extra steps", withData(setWord(d, 11, BigInteger.ZERO)))
        assertRefused("does not use Across", withData(replaceOnce(d, "6163726f7373", "737461726761")))
    }

    // ---- guard: the fee step ----

    @Test fun guard_feeStepIsPinned() {
        val d = dataOf()
        assertRefused("unknown fee contract", withData(setAddr(d, 19, attacker)))
        assertRefused("unknown fee contract", withData(setAddr(d, 20, attacker)))
        assertRefused("changes the token", withData(setAddr(d, 21, attacker)))
        assertRefused("changes the token", withData(setAddr(d, 22, attacker)))
        assertRefused("different amount", withData(setWord(d, 23, BigInteger.valueOf(1_000_000))))
    }

    @Test fun guard_feeRecipientAndSize() {
        val d = dataOf()
        val recipientWord = "000000000000000000000000c06ebbefd94032b85424d51906e2a335efae264b"
        val amountWord = "0000000000000000000000000000000000000000000000000000000000001388"
        assertRefused("unknown address", withData(replaceOnce(d, recipientWord, "0".repeat(24) + attacker.removePrefix("0x"))))
        // 1% of 2,000,000 is 20,000: a 30,000 fee is refused even though everything else is consistent with it
        assertRefused("unusually high", withData(replaceOnce(d, amountWord, BigInteger.valueOf(30_000).toString(16).padStart(64, '0'))))
        // a fee of exactly 1% is the most we accept (bridged amount and outputs all move with it)
        val atCap = replaceOnce(setWord(setWord(d, 9, BigInteger.valueOf(1_980_000)), 37, BigInteger.valueOf(1_976_000)), amountWord, BigInteger.valueOf(20_000).toString(16).padStart(64, '0'))
        assertNull(check(withDataAndOutput(atCap, 1_976_000)))
        // fee changed but the bridged amount was not: the amounts no longer add up
        assertRefused("doesn't match", withData(replaceOnce(d, amountWord, BigInteger.valueOf(6_000).toString(16).padStart(64, '0'))))
        assertRefused("doesn't match", withData(setWord(d, 9, BigInteger.valueOf(1_996_000))))
    }

    // ---- guard: the Across deposit ----

    @Test fun guard_depositRecipientsAndTokens() {
        val d = dataOf()
        assertRefused("recipient or refund", withData(setAddr(d, 33, attacker)))
        assertRefused("recipient or refund", withData(setAddr(d, 34, attacker)))
        assertRefused("input token", withData(setAddr(d, 35, attacker)))
        assertRefused("different token", withData(setAddr(d, 36, attacker)))
        assertRefused("doesn't match the quote", withData(setWord(d, 37, BigInteger.valueOf(1_000_000))))
    }

    @Test fun guard_depositPercentAndRelayer() {
        val d = dataOf()
        assertRefused("percentage", withData(setWord(d, 38, BigInteger.ZERO)))
        assertRefused("percentage", withData(setWord(d, 38, BigInteger.TEN.pow(18).multiply(BigInteger.valueOf(5)).divide(BigInteger.TEN))))
        assertRefused("percentage", withData(setWord(d, 38, BigInteger.TEN.pow(18).add(BigInteger.ONE))))
        assertRefused("relayer", withData(setWord(d, 39, BigInteger.ONE.shiftLeft(200))))
    }

    @Test fun guard_timestamps() {
        val d = dataOf()
        assertRefused("timestamp", parse(), at = now + 1_000)
        assertRefused("timestamp", parse(), at = now - 2_000)
        assertRefused("deadline", withData(setWord(d, 41, BigInteger.valueOf(now - 1))))
        assertRefused("deadline", withData(setWord(d, 41, BigInteger.valueOf(now + 90_000))))
        assertRefused("exclusivity", withData(setWord(d, 42, BigInteger.valueOf(now + 90_000))))
        assertRefused("timestamps", withData(setWord(d, 40, BigInteger.ONE.shiftLeft(40))))
        assertNull("a 15 minute skew is tolerated", check(at = 1_794_999_323L + 900))
    }

    // ---- guard: the money ----

    @Test fun guard_outputsMustBeConsistentAndFeesBounded() {
        val d = dataOf()
        // quote and calldata agree on an output larger than what is bridged (1,995,000): impossible, refuse
        assertRefused("inconsistent", withDataAndOutput(setWord(d, 37, BigInteger.valueOf(1_999_999)), 1_999_999))
        assertRefused("inconsistent", parse { getJSONObject("estimate").put("toAmountMin", "2000000") })
        assertRefused("inconsistent", parse { getJSONObject("estimate").put("toAmountMin", "0") })
        // total fees over 5%: toAmountMin below 1,900,000
        assertRefused("over 5%", parse { getJSONObject("estimate").put("toAmountMin", "1899999") })
        assertNull(check(parse { getJSONObject("estimate").put("toAmountMin", "1900000") }))
    }

    @Test fun guard_garbageCalldata() {
        assertRefused("unrecognised format", parse { getJSONObject("transactionRequest").put("data", "0x") })
        assertRefused("unrecognised format", parse { getJSONObject("transactionRequest").put("data", "0xdeadbeef") })
    }

    // ---- pins ----

    @Test fun pins_areChecksummedAndOnlyVerifiedChainsAreListed() {
        val pin = LiFiPins.forChain(42161L)!!
        for (a in listOf(pin.diamond, pin.feeContract, pin.feeRecipient, LiFiPins.DIAMOND)) assertEquals(Keys.toChecksumAddress(a), a)
        assertEquals(setOf(42161L), LiFiPins.PINNED.keys)
        assertNull(LiFiPins.forChain(1L)); assertNull(LiFiPins.forChain(8453L)); assertNull(LiFiPins.forChain(137L))
    }

    // ---- status ----

    @Test fun status_mapping() {
        assertEquals(FillStatus.FILLED, LiFiStatus.parse("""{"status":"DONE","substatus":"COMPLETED"}"""))
        assertEquals(FillStatus.REFUNDED, LiFiStatus.parse("""{"status":"DONE","substatus":"REFUNDED"}"""))
        assertEquals(FillStatus.UNKNOWN, LiFiStatus.parse("""{"status":"DONE","substatus":"PARTIAL"}"""))
        assertEquals(FillStatus.UNKNOWN, LiFiStatus.parse("""{"status":"DONE"}"""))
        assertEquals(FillStatus.PENDING, LiFiStatus.parse("""{"status":"PENDING","substatus":"WAIT_DESTINATION_TRANSACTION"}"""))
        assertEquals(FillStatus.EXPIRED, LiFiStatus.parse("""{"status":"FAILED"}"""))
        for (body in listOf("", "x", "{}", """{"status":"NOT_FOUND"}""", """{"status":"INVALID"}""", """{"status":"WHATEVER"}""", """{"status":null}""")) {
            assertEquals(body, FillStatus.UNKNOWN, LiFiStatus.parse(body))
        }
    }

    // ---- client ----

    private class Net(var status: Int = 200, var body: String = "{}") {
        val urls = ArrayList<String>()
        val transport: (String) -> HttpResult = { url -> urls.add(url); HttpResult(status, body) }
    }

    @Test fun api_quoteRequestAndParse() {
        val net = Net(body = FIXTURE)
        val q = LiFiApi("https://li.quest/v1", net.transport).quote(request)
        assertEquals(1_991_702L, q.toAmount.toLong())
        assertEquals(
            "https://li.quest/v1/quote?fromChain=42161&toChain=8453&fromToken=$usdcArb&toToken=$usdcBase&fromAmount=2000000" +
                "&fromAddress=$user&toAddress=$user&allowBridges=across",
            net.urls.single()
        )
    }

    @Test fun api_errorsAndBadInput() {
        try { LiFiApi("https://li.quest/v1", Net(400, """{"message":"Invalid fromAmount"}""").transport).quote(request); fail() }
        catch (e: IllegalStateException) { assertEquals("LI.FI: Invalid fromAmount", e.message) }
        try { LiFiApi("https://li.quest/v1", Net(502, "<html>").transport).quote(request); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("502")) }
        val net = Net()
        for (bad in listOf(
            LiFiRequest("0xnope", 42161L, 8453L, usdcArb, usdcBase, BigInteger.ONE),
            LiFiRequest(user, 42161L, 8453L, "$usdcArb&evil=1", usdcBase, BigInteger.ONE),
            LiFiRequest(user, 42161L, 8453L, usdcArb, usdcBase, BigInteger.ZERO)
        )) { try { LiFiApi("https://li.quest/v1", net.transport).quote(bad); fail() } catch (e: IllegalArgumentException) { /* expected */ } }
        try { LiFiApi("https://li.quest/v1", net.transport).quote(LiFiRequest(user, 42161L, 8453L, null, usdcBase, BigInteger.ONE)); fail() }
        catch (e: IllegalStateException) { assertEquals(LiFiGuard.NATIVE_UNSUPPORTED, e.message) }
        assertTrue("nothing was sent for bad input", net.urls.isEmpty())
    }

    @Test fun api_status() {
        val tx = "0x" + "ab".repeat(32)
        val net = Net(body = """{"status":"DONE","substatus":"COMPLETED"}""")
        assertEquals(FillStatus.FILLED, LiFiApi("https://li.quest/v1", net.transport).status(tx, 42161L))
        assertEquals("https://li.quest/v1/status?txHash=$tx&fromChain=42161", net.urls.single())
        assertEquals(FillStatus.UNKNOWN, LiFiApi("https://li.quest/v1", Net(404, """{"message":"not found"}""").transport).status(tx, 42161L))
        val untouched = Net()
        assertEquals(FillStatus.UNKNOWN, LiFiApi("https://li.quest/v1", untouched.transport).status("0x1234&x=1", 42161L))
        assertTrue(untouched.urls.isEmpty())
    }

    private companion object {
        const val FIXTURE = """{"_note":"Reduced from a REAL LI.FI GET /v1/quote response (USDC Arbitrum -> Base, 2 USDC) captured with tools/capture_lifi.sh. Only the fields the wallet reads are kept; fromChainId/toChainId/toToken come from the request that produced it.","tool":"across","action":{"fromToken":{"address":"0xaf88d065e77c8cC2239327C5EDb3A432268e5831","symbol":"USDC","decimals":6,"chainId":42161},"toToken":{"address":"0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913","symbol":"USDC","decimals":6,"chainId":8453},"fromAmount":"2000000","fromChainId":42161,"toChainId":8453},"estimate":{"toAmount":"1991702","toAmountMin":"1991702","approvalAddress":"0x1231DEB6f5749EF6cE6943a275A1D3E7486F4EaE","executionDuration":0},"transactionRequest":{"value":"0x0","to":"0x1231DEB6f5749EF6cE6943a275A1D3E7486F4EaE","data":"0x1794958f000000000000000000000000000000000000000000000000000000000000006000000000000000000000000000000000000000000000000000000000000002200000000000000000000000000000000000000000000000000000000000000420114c6266b8869d3cb7b83da9ff68dd6f4cf65e70bd40847cead34c6b97d31976000000000000000000000000000000000000000000000000000000000000014000000000000000000000000000000000000000000000000000000000000001800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e5831000000000000000000000000a3a0fccdf214159ce35d4a0066bb32fe10733d6700000000000000000000000000000000000000000000000000000000001e70f800000000000000000000000000000000000000000000000000000000000021050000000000000000000000000000000000000000000000000000000000000001000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000066163726f7373000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000086c6966692d61706900000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000020000000000000000000000000de2b920506ee931a6a2abdf566526220214fbbae000000000000000000000000de2b920506ee931a6a2abdf566526220214fbbae000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e5831000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e583100000000000000000000000000000000000000000000000000000000001e848000000000000000000000000000000000000000000000000000000000000000e0000000000000000000000000000000000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000000000a4332d746b000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e583100000000000000000000000000000000000000000000000000000000000000400000000000000000000000000000000000000000000000000000000000000001000000000000000000000000c06ebbefd94032b85424d51906e2a335efae264b000000000000000000000000000000000000000000000000000000000000138800000000000000000000000000000000000000000000000000000000000000000000000000000000a3a0fccdf214159ce35d4a0066bb32fe10733d67000000000000000000000000a3a0fccdf214159ce35d4a0066bb32fe10733d67000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e5831000000000000000000000000833589fcd6edb6e08f4c7c32d4f71b54bda0291300000000000000000000000000000000000000000000000000000000001e64160000000000000000000000000000000000000000000000000ddad72fac2afeb80000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000006afd841b000000000000000000000000000000000000000000000000000000006afda85f000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001600000000000000000000000000000000000000000000000000000000000000000","from":"0xa3a0fccdf214159ce35d4a0066bb32fe10733d67","chainId":42161,"gasPrice":"0x131aa00","gasLimit":"0x2ae8a7"}}"""
    }
}
