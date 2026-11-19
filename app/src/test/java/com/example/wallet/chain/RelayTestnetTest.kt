package com.example.wallet.chain

import org.json.JSONArray
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
 * Relay testnet bridge. FIXTURE is a REAL Relay response (0.0001 ETH, Sepolia -> Base Sepolia) reduced to the fields the
 * wallet reads (tools/verify/relay_fixture_sepolia_basesepolia_eth.json, captured with tools/capture_relay.sh).
 */
class RelayTestnetTest {
    private val user = "0xa3a0fccdf214159ce35d4a0066bb32fe10733d67"
    private val attacker = "0x00000000000000000000000000000000000bad01"
    private val amount = BigInteger.valueOf(100_000_000_000_000L)
    private val requestId = "0x1795062582fec3cdda47925bcc753afef7610b030d407949df8e2d97b231432f"
    private val request = RelayRequest(user, RelayTestnet.SEPOLIA, RelayTestnet.BASE_SEPOLIA, amount)

    private fun parse(mutate: JSONObject.() -> Unit = {}): RelayQuote = RelayQuoteParser.parse(JSONObject(FIXTURE).also(mutate).toString())
    private fun item(root: JSONObject) = root.getJSONArray("steps").getJSONObject(0).getJSONArray("items").getJSONObject(0)
    private fun tx(root: JSONObject) = item(root).getJSONObject("data")
    private fun details(root: JSONObject) = root.getJSONObject("details")
    private fun refused(contains: String, q: RelayQuote, r: RelayRequest = request) {
        val reason = RelayGuard.validate(q, r)
        assertNotNull("expected a refusal containing '$contains' but the guard accepted", reason)
        assertTrue("'$reason' should contain '$contains'", reason!!.contains(contains))
    }

    // ---- parser ----

    @Test fun parser_readsTheRealQuote() {
        val q = parse()
        assertEquals(1, q.stepCount); assertEquals(1, q.itemCount)
        assertEquals("deposit", q.stepId); assertEquals("transaction", q.stepKind); assertEquals("incomplete", q.itemStatus)
        assertEquals(requestId, q.requestId)
        assertEquals(user, q.txFrom); assertEquals("0x5feab8db4534f9f7e2669bb260c57a01ad1c12e3", q.txTo)
        assertEquals(amount, q.txValue); assertEquals(11_155_111L, q.txChainId)
        assertEquals("/intents/status/v3?requestId=$requestId", q.checkEndpoint)
        assertEquals(user, q.sender); assertEquals(user, q.recipient)
        assertEquals(amount, q.amountIn)
        assertEquals(BigInteger.valueOf(88_697_179_453_965L), q.amountOut)
        assertEquals(BigInteger.valueOf(84_421_975_404_284L), q.minAmountOut)
    }

    @Test fun parser_surfacesRelaysError() {
        try {
            RelayQuoteParser.parse("""{"message":"Amount is higher than the available liquidity. Max amount is 1 USD","errorCode":"INSUFFICIENT_LIQUIDITY","steps":[]}""")
            fail()
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("available liquidity")) }
    }

    @Test fun parser_rejectsMalformedQuotes() {
        for (bad in listOf("", "not json", "[]")) { try { RelayQuoteParser.parse(bad); fail("accepted: $bad") } catch (e: RuntimeException) { /* expected */ } }
        try { RelayQuoteParser.parse("{}"); fail() } catch (e: IllegalStateException) { /* no steps */ }
        try { parse { remove("details") }; fail() } catch (e: IllegalArgumentException) { /* expected */ }
        try { parse { details(this).remove("currencyOut") }; fail() } catch (e: IllegalArgumentException) { /* expected */ }
        try { parse { details(this).getJSONObject("currencyIn").remove("amount") }; fail() } catch (e: IllegalArgumentException) { /* expected */ }
        try { parse { item(this).remove("data") }; fail() } catch (e: IllegalArgumentException) { /* expected */ }
        try { parse { getJSONArray("steps").getJSONObject(0).put("items", JSONArray()) }; fail() } catch (e: IllegalArgumentException) { /* expected */ }
    }

    // ---- calldata ----

    @Test fun calldata_readsTheRealDeposit() {
        val d = RelayCalldata.decode(parse().txData)!!
        assertEquals(user, d.depositor)
        assertEquals("5a71a9666f1446b673615ab56babb72acf25398a136b976cfce7e26fb0775a12", d.id)
    }

    @Test fun calldata_rejectsAnythingElse() {
        val good = parse().txData
        val cases = mapOf(
            "empty" to "0x", "no prefix" to good.removePrefix("0x"), "other selector" to "0xdeadbeef" + good.substring(10),
            "short" to good.dropLast(2), "trailing byte" to good + "00", "not hex" to good.dropLast(2) + "zz",
            "dirty depositor" to good.substring(0, 10) + "1" + good.substring(11)
        )
        for ((name, data) in cases) assertNull(name, RelayCalldata.decode(data))
        assertNotNull(RelayCalldata.decode(good))
    }

    // ---- guard ----

    @Test fun guard_acceptsTheRealQuote() = assertNull(RelayGuard.validate(parse(), request))

    @Test fun guard_acceptsMixedCaseAddresses() {
        val q = parse { tx(this).put("to", RelayTestnet.DEPOSITORY); tx(this).put("from", Keys.toChecksumAddress(user)) }
        assertNull(RelayGuard.validate(q, RelayRequest(Keys.toChecksumAddress(user), RelayTestnet.SEPOLIA, RelayTestnet.BASE_SEPOLIA, amount)))
    }

    @Test fun guard_onlySepoliaAndBaseSepolia() {
        refused("only connects Sepolia and Base Sepolia", parse(), RelayRequest(user, 1L, RelayTestnet.BASE_SEPOLIA, amount))
        refused("only connects Sepolia and Base Sepolia", parse(), RelayRequest(user, RelayTestnet.SEPOLIA, 421_614L, amount))
        refused("only connects Sepolia and Base Sepolia", parse(), RelayRequest(user, RelayTestnet.SEPOLIA, RelayTestnet.SEPOLIA, amount))
        refused("Invalid amount", parse(), RelayRequest(user, RelayTestnet.SEPOLIA, RelayTestnet.BASE_SEPOLIA, BigInteger.ZERO))
    }

    @Test fun guard_shapeOfTheQuote() {
        refused("extra steps", parse { getJSONArray("steps").put(getJSONArray("steps").getJSONObject(0)) })
        refused("extra steps", parse { val items = getJSONArray("steps").getJSONObject(0).getJSONArray("items"); items.put(items.getJSONObject(0)) })
        refused("not a plain deposit", parse { getJSONArray("steps").getJSONObject(0).put("id", "approve") })
        refused("not a plain deposit", parse { getJSONArray("steps").getJSONObject(0).put("kind", "signature") })
        refused("not ready", parse { item(this).put("status", "complete") })
        refused("invalid request id", parse { getJSONArray("steps").getJSONObject(0).put("requestId", "0x1234") })
        refused("status link", parse { item(this).getJSONObject("check").put("endpoint", "/intents/status/v3?requestId=0x" + "ab".repeat(32)) })
        refused("status link", parse { item(this).getJSONObject("check").put("endpoint", "/intents/status/v3?requestId=$requestId&x=1") })
        refused("status link", parse { item(this).remove("check") })
    }

    @Test fun guard_transactionTargetAndValue() {
        refused("different network", parse { tx(this).put("chainId", 1) })
        refused("different sender", parse { tx(this).put("from", attacker) })
        refused("Unexpected Relay contract", parse { tx(this).put("to", attacker) })
        refused("deposit value", parse { tx(this).put("value", "100000000000001") })
        refused("deposit value", parse { tx(this).put("value", "0") })
    }

    @Test fun guard_depositCalldata() {
        val good = parse().txData
        refused("unrecognised format", parse { tx(this).put("data", "0xdeadbeef") })
        refused("unrecognised format", parse { tx(this).put("data", good + "00") })
        refused("different depositor", parse { tx(this).put("data", good.substring(0, 34) + attacker.removePrefix("0x") + good.substring(74)) })
        refused("empty order id", parse { tx(this).put("data", good.substring(0, 74) + "0".repeat(64)) })
    }

    @Test fun guard_relaysClaims() {
        refused("different sender or recipient", parse { details(this).put("sender", attacker) })
        refused("different sender or recipient", parse { details(this).put("recipient", attacker) })
        refused("different amount", parse { details(this).getJSONObject("currencyIn").put("amount", "100000000000001") })
    }

    private fun withOut(out: Long, min: Long) = parse { details(this).getJSONObject("currencyOut").put("amount", out.toString()).put("minimumAmount", min.toString()) }

    @Test fun guard_outputsMustBeConsistent() {
        refused("inconsistent", withOut(0, 0))
        refused("inconsistent", withOut(100_000_000_000_001L, 80_000_000_000_000L))
        refused("minimum output is inconsistent", withOut(88_000_000_000_000L, 0))
        refused("minimum output is inconsistent", withOut(88_000_000_000_000L, 88_000_000_000_001L))
        refused("too low", withOut(88_000_000_000_000L, 70_399_999_999_999L))
        assertNull(RelayGuard.validate(withOut(88_000_000_000_000L, 70_400_000_000_000L), request))
    }

    @Test fun guard_feeCapIsGenerousOnTestnetButNotUnlimited() {
        assertNull("the real quote costs 11.3%", RelayGuard.validate(parse(), request))
        assertNull(RelayGuard.validate(withOut(50_000_000_000_000L, 45_000_000_000_000L), request))
        refused("over 50%", withOut(49_999_999_999_999L, 45_000_000_000_000L))
    }

    // ---- status ----

    @Test fun status_mapping() {
        assertEquals(FillStatus.FILLED, RelayStatus.parse("""{"status":"success"}"""))
        assertEquals(FillStatus.FILLED, RelayStatus.parse("""{"status":"SUCCESS"}"""))
        assertEquals(FillStatus.REFUNDED, RelayStatus.parse("""{"status":"refund"}"""))
        for (s in listOf("waiting", "depositing", "pending", "submitted")) assertEquals(s, FillStatus.PENDING, RelayStatus.parse("""{"status":"$s"}"""))
        assertEquals("a failed fill may still be retried or refunded", FillStatus.PENDING, RelayStatus.parse("""{"status":"failure"}"""))
        assertEquals(FillStatus.PENDING, RelayStatus.parse("""{"status":"failure","refundFailReason":null}"""))
        assertEquals(FillStatus.EXPIRED, RelayStatus.parse("""{"status":"failure","refundFailReason":"MANUAL_REFUND_REQUIRED"}"""))
        for (body in listOf("", "x", "{}", """{"status":"mystery"}""", """{"status":null}""")) assertEquals(body, FillStatus.UNKNOWN, RelayStatus.parse(body))
    }

    // ---- client ----

    private class Net(var status: Int = 200, var body: String = "{}") {
        val calls = ArrayList<Triple<String, String, String?>>()
        val transport: (String, String, String?) -> HttpResult = { m, u, b -> calls.add(Triple(m, u, b)); HttpResult(status, body) }
    }

    @Test fun api_quoteRequest() {
        val net = Net(body = FIXTURE)
        val q = RelayApi(RelayTestnet.API_BASE, net.transport).quote(request)
        assertEquals(requestId, q.requestId)
        val (method, url, body) = net.calls.single()
        assertEquals("POST", method); assertEquals("https://api.testnets.relay.link/quote/v2", url)
        val j = JSONObject(body!!)
        assertEquals(user, j.getString("user")); assertEquals(11_155_111L, j.getLong("originChainId")); assertEquals(84_532L, j.getLong("destinationChainId"))
        assertEquals(RelayTestnet.ZERO_ADDRESS, j.getString("originCurrency")); assertEquals(RelayTestnet.ZERO_ADDRESS, j.getString("destinationCurrency"))
        assertEquals("100000000000000", j.getString("amount")); assertEquals("EXACT_INPUT", j.getString("tradeType"))
    }

    @Test fun api_errors() {
        try { RelayApi(RelayTestnet.API_BASE, Net(400, """{"message":"Amount is higher than the available liquidity","errorCode":"INSUFFICIENT_LIQUIDITY"}""").transport).quote(request); fail() }
        catch (e: IllegalStateException) { assertEquals("Relay: Amount is higher than the available liquidity", e.message) }
        for (code in listOf(401, 403)) {
            try { RelayApi(RelayTestnet.API_BASE, Net(code, "{}").transport).quote(request); fail() }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("API key")) }
        }
        try { RelayApi(RelayTestnet.API_BASE, Net(500, "<html>").transport).quote(request); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("500")) }
        val net = Net()
        for (bad in listOf(RelayRequest("0xnope", 11_155_111L, 84_532L, amount), RelayRequest(user, 11_155_111L, 84_532L, BigInteger.ZERO))) {
            try { RelayApi(RelayTestnet.API_BASE, net.transport).quote(bad); fail() } catch (e: IllegalArgumentException) { /* expected */ }
        }
        assertTrue("nothing was sent for bad input", net.calls.isEmpty())
    }

    @Test fun api_status() {
        val net = Net(body = """{"status":"success"}""")
        assertEquals(FillStatus.FILLED, RelayApi(RelayTestnet.API_BASE, net.transport).status(requestId))
        assertEquals(Triple("GET", "https://api.testnets.relay.link/intents/status/v3?requestId=$requestId", null), net.calls.single())
        assertEquals(FillStatus.UNKNOWN, RelayApi(RelayTestnet.API_BASE, Net(404, "{}").transport).status(requestId))
        val untouched = Net()
        assertEquals(FillStatus.UNKNOWN, RelayApi(RelayTestnet.API_BASE, untouched.transport).status("0x1234&x=1"))
        assertTrue(untouched.calls.isEmpty())
    }

    // ---- chains, assets, pins ----

    @Test fun chains_andAssets() {
        assertEquals(listOf(Chain.BASE), RelayTestnet.destinations(Chain.ETH))
        assertEquals(listOf(Chain.ETH), RelayTestnet.destinations(Chain.BASE))
        assertTrue(RelayTestnet.destinations(Chain.ARB).isEmpty()); assertTrue(RelayTestnet.destinations(Chain.POL).isEmpty())
        assertEquals(RelayTestnet.SEPOLIA, RelayTestnet.chainIdOf(Chain.ETH)); assertEquals(RelayTestnet.BASE_SEPOLIA, RelayTestnet.chainIdOf(Chain.BASE))
        assertNull(RelayTestnet.chainIdOf(Chain.ARB))
        val eth = SwapAsset(null, "ETH", 18)
        assertEquals(listOf(SwapAsset(null, "ETH", 18)), RelayTestnet.candidateAssets(eth, Chain.BASE))
        assertTrue("no tokens on Relay's testnets", RelayTestnet.candidateAssets(SwapAsset("0x0000000000000000000000000000000000000abc", "USDC", 6), Chain.BASE).isEmpty())
        assertTrue(RelayTestnet.candidateAssets(eth, Chain.ARB).isEmpty())
    }

    @Test fun depository_isChecksummedAndMatchesTheRealDeposit() {
        assertEquals(Keys.toChecksumAddress(RelayTestnet.DEPOSITORY), RelayTestnet.DEPOSITORY)
        assertTrue(parse().txTo.equals(RelayTestnet.DEPOSITORY, ignoreCase = true))
    }

    // ---- jobs on the test networks ----

    private fun job() = BridgeSwapJob(
        id = "j", originChain = Chain.ETH, destChain = Chain.BASE, depositTxId = "0x" + "ab".repeat(32), startedAtSec = 1_000L,
        expectedOutRaw = BigInteger.valueOf(88_697_179_453_965L), candidates = listOf(ArrivalCandidate(SwapAsset(null, "ETH", 18), BigInteger.ZERO)),
        target = SwapAsset("0x4200000000000000000000000000000000000006", "WETH", 18), previewInRaw = null, previewOutRaw = null,
        testnet = true, trackingId = requestId
    )

    @Test fun jobs_keepTestnetFlagAndTrackingIdAcrossRestarts() {
        val j = job()
        assertEquals(j, BridgeSwapJobCodec.fromJson(JSONObject(BridgeSwapJobCodec.toJson(j).toString())))
    }

    @Test fun jobs_savedBeforeTheseFieldsExistedAreMainnetWithoutTrackingId() {
        val old = BridgeSwapJobCodec.toJson(job()).also { it.remove("testnet"); it.remove("trackingId") }
        val j = BridgeSwapJobCodec.fromJson(JSONObject(old.toString()))!!
        assertEquals(false, j.testnet); assertNull(j.trackingId)
    }

    @Test fun jobs_gasReserveOnTestnetsIsSeparateFromMainnet() {
        assertEquals(BigInteger("6000000000000000"), BridgeSwapLogic.gasReserveRaw(Chain.ETH))
        assertEquals(BigInteger("200000000000000"), BridgeSwapLogic.gasReserveRaw(Chain.ETH, testnet = true))
        assertEquals(BigInteger("5000000000000"), BridgeSwapLogic.gasReserveRaw(Chain.BASE, testnet = true))
        assertTrue(BridgeSwapLogic.gasReserveRaw(Chain.BASE, testnet = true) < BridgeSwapLogic.gasReserveRaw(Chain.BASE))
    }

    private companion object {
        const val FIXTURE = """{"_note":"Reduced from a REAL Relay testnet POST /quote/v2 response (0.0001 ETH, Sepolia -> Base Sepolia) captured with tools/capture_relay.sh. Only the fields the wallet reads are kept.","steps":[{"id":"deposit","kind":"transaction","requestId":"0x1795062582fec3cdda47925bcc753afef7610b030d407949df8e2d97b231432f","items":[{"status":"incomplete","data":{"from":"0xa3a0fccdf214159ce35d4a0066bb32fe10733d67","to":"0x5feab8db4534f9f7e2669bb260c57a01ad1c12e3","data":"0x49290c1c000000000000000000000000a3a0fccdf214159ce35d4a0066bb32fe10733d675a71a9666f1446b673615ab56babb72acf25398a136b976cfce7e26fb0775a12","value":"100000000000000","chainId":11155111,"gas":"33400","maxFeePerGas":"1000000001","maxPriorityFeePerGas":"999999984"},"check":{"endpoint":"/intents/status/v3?requestId=0x1795062582fec3cdda47925bcc753afef7610b030d407949df8e2d97b231432f","method":"GET"}}]}],"details":{"operation":"swap","sender":"0xa3a0fccdf214159ce35d4a0066bb32fe10733d67","recipient":"0xa3a0fccdf214159ce35d4a0066bb32fe10733d67","currencyIn":{"amount":"100000000000000"},"currencyOut":{"amount":"88697179453965","minimumAmount":"84421975404284"}}}"""
    }
}
