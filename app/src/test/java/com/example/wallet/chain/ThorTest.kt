package com.example.wallet.chain

import com.example.wallet.abandonSeed
import com.example.wallet.crypto.Hd
import com.example.wallet.hex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/**
 * THORChain swaps for Litecoin and Tron. Everything here is offline: JSON shapes follow THORNode's published quote and
 * inbound_addresses documentation, not captured live responses. NOT tested: the live THORNode endpoints, and that
 * THORChain accepts these transactions (first real use must be a small amount).
 */
class ThorTest {
    private val btcDest = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    private val vault = "ltc1qvaultvaultvaultvaultvaultvaultvaultabcd"
    private val now = 1_800_000_000L
    private fun big(s: String) = BigInteger(s)

    private fun quote(
        vault: String = this.vault, memo: String? = "=:BTC.BTC:$btcDest:1", expiry: Long = now + 600,
        out: String = "5000000", fees: String = "20000", minIn: String = "100000"
    ) = ThorQuoteData(vault, memo, expiry, big(out), big(fees), big(minIn), 900)

    // ---- memo ----

    @Test fun memo_isBuiltLocally_andReadable() {
        val m = ThorMemo.swap("BTC.BTC", btcDest, big("1234567"))
        assertEquals("=:BTC.BTC:$btcDest:1234567", m)
        assertEquals(btcDest, ThorMemo.destination(m))
        assertNull(ThorMemo.destination("=:BTC.BTC"))
    }

    @Test fun memo_worstCasesForOurOwnAddressesFitIn80Bytes() {
        val limit = big("999999999999") // 12 digits: far more than the $100 cap allows
        val ltc = "ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh"
        val evm = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"
        val tron = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"
        for ((asset, dest) in listOf("LTC.LTC" to ltc, "BTC.BTC" to btcDest, "ETH.ETH" to evm, "BASE.ETH" to evm, "TRON.TRX" to tron)) {
            val m = ThorMemo.swap(asset, dest, limit)
            assertTrue("$asset memo is ${m.length} bytes", m.toByteArray().size <= ThorMemo.MAX_BYTES)
        }
    }

    @Test fun memo_rejectsAnythingMalformed() {
        fun bad(block: () -> Unit) { try { block(); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) { } }
        bad { ThorMemo.swap("btc.btc", btcDest, BigInteger.ONE) }
        bad { ThorMemo.swap("BTC.BTC", "bc1q:evil", BigInteger.ONE) }
        bad { ThorMemo.swap("BTC.BTC", "bc1q evil", BigInteger.ONE) }
        bad { ThorMemo.swap("BTC.BTC", "", BigInteger.ONE) }
        bad { ThorMemo.swap("BTC.BTC", btcDest, BigInteger.ZERO) }
        bad { ThorMemo.swap("BTC.BTC", "b".repeat(80), BigInteger.ONE) } // too long
    }

    // ---- quorum ----

    private fun inbound(addr: String = vault, halted: Boolean = false, paused: Boolean = false, router: String = "") = ThorInbound("LTC", addr, halted, paused, router)

    @Test fun quorum_needsTwoAgreeingNodes() {
        assertEquals(vault, ThorQuorum.agree(listOf(inbound(), inbound(), null)).first!!.address)
        assertEquals(vault, ThorQuorum.agree(listOf(null, inbound(), inbound())).first!!.address)
        assertNotNull(ThorQuorum.agree(listOf(inbound(), null, null)).second)
        assertNotNull(ThorQuorum.agree(listOf(null, null, null)).second)
        assertNotNull(ThorQuorum.agree(emptyList()).second)
    }

    @Test fun quorum_refusesDisagreementPausesAndEmptyAddresses() {
        assertNotNull("disagreement", ThorQuorum.agree(listOf(inbound(), inbound("ltc1qother"))).second)
        assertNotNull("one dissenter among three", ThorQuorum.agree(listOf(inbound(), inbound(), inbound("ltc1qother"))).second)
        assertNotNull("halted", ThorQuorum.agree(listOf(inbound(), inbound(halted = true))).second)
        assertNotNull("paused", ThorQuorum.agree(listOf(inbound(paused = true), inbound())).second)
        assertNotNull("empty address", ThorQuorum.agree(listOf(inbound(""), inbound(""))).second)
    }

    // ---- guard ----

    @Test fun guard_acceptsAGoodQuote() {
        assertNull(ThorGuard.validate(quote(), vault, btcDest, big("1000000"), now))
    }

    @Test fun guard_refusesBadQuotes() {
        fun msg(q: ThorQuoteData, amountIn: String = "1000000", dest: String = btcDest) = ThorGuard.validate(q, vault, dest, big(amountIn), now)
        assertNotNull("different vault", msg(quote(vault = "ltc1qattacker")))
        assertNotNull("different destination in the api memo", msg(quote(memo = "=:BTC.BTC:bc1qattacker:1")))
        assertNull("no memo from the api is fine: ours is used", msg(quote(memo = null)))
        assertNotNull("expired", msg(quote(expiry = now - 1)))
        assertNotNull("about to expire", msg(quote(expiry = now + 30)))
        assertNotNull("zero output", msg(quote(out = "0")))
        assertNotNull("below minimum", msg(quote(minIn = "2000000")))
        assertNotNull("fees over 5%", msg(quote(out = "1000000", fees = "100000")))
        assertNull("fees at 4%", msg(quote(out = "960000", fees = "40000")))
    }

    @Test fun minOut_isOnePercentBelowTheQuote() {
        assertEquals(big("4950000"), ThorGuard.minOut(big("5000000")))
        assertEquals(BigInteger.ZERO, ThorGuard.minOut(BigInteger.ZERO))
    }

    // ---- parsing (shapes from the THORNode docs) ----

    @Test fun parse_quote() {
        val q = ThorParse.quote(JSONObject("""{"inbound_address":"$vault","memo":"=:BTC.BTC:$btcDest:5","expiry":1800000600,
            "expected_amount_out":"5000000","fees":{"asset":"BTC.BTC","total":"20000","outbound":"15000"},
            "recommended_min_amount_in":"100000","total_swap_seconds":900,"warning":"Do not cache"}"""))
        assertEquals(vault, q.inboundAddress)
        assertEquals(big("5000000"), q.expectedOut)
        assertEquals(big("20000"), q.feesTotal)
        assertEquals(big("100000"), q.minIn)
        assertEquals(1800000600L, q.expiry)
        assertEquals(900L, q.etaSeconds)
    }

    @Test fun parse_quoteErrorsBecomeMessages() {
        try { ThorParse.quote(JSONObject("""{"code":3,"message":"trading is halted","details":[]}""")); fail() }
        catch (e: IllegalStateException) { assertEquals("trading is halted", e.message) }
        try { ThorParse.quote(JSONObject("""{"error":"boom"}""")); fail() }
        catch (e: IllegalStateException) { assertEquals("boom", e.message) }
    }

    @Test fun parse_inbound() {
        val arr = JSONArray("""[{"chain":"BTC","address":"bc1qbtc","halted":false},
            {"chain":"LTC","address":"$vault","halted":false,"chain_trading_paused":false,"global_trading_paused":false},
            {"chain":"TRON","address":"TVault","halted":true}]""")
        val ltc = ThorParse.inbound(arr, "LTC")!!
        assertEquals(vault, ltc.address); assertFalse(ltc.halted); assertFalse(ltc.tradingPaused)
        assertTrue(ThorParse.inbound(arr, "TRON")!!.halted)
        assertNull(ThorParse.inbound(arr, "DOGE"))
        val paused = ThorParse.inbound(JSONArray("""[{"chain":"LTC","address":"a","global_trading_paused":true}]"""), "LTC")!!
        assertTrue(paused.tradingPaused)
    }

    // ---- planning with a fake node ----

    private class FakeApi(val inbound: Pair<ThorInbound?, String?>, val q: ThorQuoteData) : ThorApi(emptyList()) {
        var asked: List<String>? = null
        override fun inbound(vaultChain: String) = inbound
        override fun quote(from: String, to: String, amount: BigInteger, destination: String): ThorQuoteData {
            asked = listOf(from, to, amount.toString(), destination); return q
        }
    }

    private fun swaps(api: ThorApi) = ThorSwaps(api) { now }

    @Test fun plan_buildsItsOwnMemo_notTheNodes() {
        val apiQuote = quote(memo = "=:BTC.BTC:$btcDest:999999999") // node's limit differs from ours
        val api = FakeApi(inbound() to null, apiQuote)
        val plan = swaps(api).plan(Chain.LTC, Chain.BTC, btcDest, big("1000000"))
        assertEquals(listOf("LTC.LTC", "BTC.BTC", "1000000", btcDest), api.asked)
        assertEquals(vault, plan.vault)
        assertEquals("=:BTC.BTC:$btcDest:4950000", plan.memo)
        assertEquals(big("4950000"), plan.minOut)
        assertEquals(now + 600, plan.expirySec)
    }

    @Test fun plan_failsClosedWithoutQuorum_orOnAGuardFailure() {
        try { swaps(FakeApi(null to "nodes disagree", quote())).plan(Chain.LTC, Chain.BTC, btcDest, big("1000000")); fail() }
        catch (e: IllegalStateException) { assertEquals("nodes disagree", e.message) }
        try { swaps(FakeApi(inbound() to null, quote(memo = "=:BTC.BTC:bc1qattacker:1"))).plan(Chain.LTC, Chain.BTC, btcDest, big("1000000")); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("destination")) }
        try { swaps(FakeApi(inbound() to null, quote())).plan(Chain.LTC, Chain.LTC, btcDest, big("1000000")); fail() }
        catch (e: IllegalArgumentException) { }
    }

    @Test fun recheck_refusesAChangedVaultOrExpiredPlan() {
        val good = swaps(FakeApi(inbound() to null, quote())).plan(Chain.LTC, Chain.BTC, btcDest, big("1000000"))
        swaps(FakeApi(inbound() to null, quote())).recheck(good) // unchanged: fine
        try { swaps(FakeApi(inbound("ltc1qrotated") to null, quote())).recheck(good); fail() } catch (e: IllegalStateException) { }
        try { ThorSwaps(FakeApi(inbound() to null, quote())) { now + 10_000 }.recheck(good); fail() } catch (e: IllegalStateException) { }
    }

    @Test fun bridgeQuote_carriesThePlan_andConvertsTheFee() {
        val plan = swaps(FakeApi(inbound() to null, quote(out = "4800000", fees = "200000")))
            .plan(Chain.LTC, Chain.BTC, btcDest, big("1000000"))
        val bq = swaps(FakeApi(inbound() to null, quote())).toBridgeQuote(plan, SwapAsset(null, "LTC", 8), big("141"), 8)
        assertSame(plan, bq.payload)
        assertEquals(btcDest, bq.destAddress)
        assertEquals("BTC", bq.outSymbol)
        assertEquals(8, bq.decimals)
        assertEquals(big("1000000"), bq.amountInRaw)
        assertEquals(big("4800000"), bq.amountOutRaw)
        assertEquals(big("40000"), bq.feeRaw)                       // 1_000_000 * 200_000 / 5_000_000
        assertEquals(0, java.math.BigDecimal("0.00000141").compareTo(bq.networkFee))
        assertTrue(bq.note!!.contains("refunds"))
    }

    @Test fun dryRunReport_showsWhatWouldBeSent_andFailsIfTheVaultChanged() {
        val plan = swaps(FakeApi(inbound() to null, quote())).plan(Chain.LTC, Chain.BTC, btcDest, big("1000000"))
        val text = swaps(FakeApi(inbound() to null, quote())).dryRunReport(plan)
        assertTrue(text.contains("nothing was signed or sent"))
        assertTrue(text.contains(vault))
        assertTrue(text.contains(plan.memo))
        assertTrue(text.contains(btcDest))
        assertTrue(text.contains("0.01 LTC"))          // 1_000_000 units of 1e-8
        assertTrue(text.contains("0.04950000".trimEnd('0')) || text.contains("0.0495 BTC"))
        try { swaps(FakeApi(inbound("ltc1qrotated") to null, quote())).dryRunReport(plan); fail() } catch (e: IllegalStateException) { }
    }

    private fun assertSame(a: Any?, b: Any?) = org.junit.Assert.assertSame(a, b)

    // ---- configuration ----

    @Test fun destinations_areOnlyOurSources_andNeverTheSameChain() {
        assertEquals(setOf(Chain.BTC, Chain.LTC, Chain.TRX, Chain.ETH, Chain.BASE, Chain.BNB), ThorAssets.DESTINATIONS.keys)
        for ((src, dests) in ThorAssets.DESTINATIONS) {
            assertFalse(src in dests)
            assertNotNull(ThorAssets.asset(src)); assertNotNull(ThorAssets.vaultChain(src))
            dests.forEach { assertNotNull(ThorAssets.asset(it)) }
        }
        assertEquals("TRON.TRX", ThorAssets.asset(Chain.TRX))
        assertEquals("BASE.ETH", ThorAssets.asset(Chain.BASE))
        assertNull(ThorAssets.asset(Chain.SOL))
    }

    @Test fun onlyMainnetLitecoinAndTronOfferSwaps() {
        assertTrue(BitcoinProvider.litecoin().supportsBridge)
        assertEquals(listOf(Chain.BTC, Chain.ETH, Chain.BASE, Chain.BNB, Chain.TRX), BitcoinProvider.litecoin().bridgeDestinations())
        assertFalse(BitcoinProvider.litecoin(testnet = true).supportsBridge)
        assertFalse(BitcoinProvider().supportsBridge)       // Bitcoin testnet: no THORChain testnet
        val btcMain = BitcoinProvider(apiBase = "https://mempool.space/api", hrp = "bc", coinType = 0, p2pkhVersion = 0x00,
            p2shVersion = 0x05, explorerTxUrl = "https://mempool.space/tx/", thor = ThorSwaps())
        assertTrue(btcMain.supportsBridge)
        assertEquals(listOf(Chain.LTC, Chain.ETH, Chain.BASE, Chain.BNB, Chain.TRX), btcMain.bridgeDestinations())
        assertTrue(TronProvider.mainnet().supportsBridge)
        assertEquals(listOf(Chain.LTC, Chain.BTC, Chain.ETH, Chain.BASE, Chain.BNB), TronProvider.mainnet().bridgeDestinations())
        assertFalse(TronProvider().supportsBridge)
        assertNotNull(BitcoinProvider.litecoin().bridgeProviderNote)
        assertNull(BitcoinProvider().bridgeProviderNote)
    }

    // ---- EVM sources: the router contract ----

    private val router = "0xD37BbE5744D730a1d98d8DC97c42F0Ca46aD7146"
    private val evmVault = "0x1111111111111111111111111111111111111111"
    private val evmDest = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    private fun evmInbound(v: String = evmVault, r: String = router) = ThorInbound("ETH", v, false, false, r)
    private fun evmQuote(v: String = evmVault, r: String = router) =
        ThorQuoteData(v, "=:BTC.BTC:$evmDest:1", now + 600, big("5000000"), big("20000"), big("100000"), 900, r)

    @Test fun routerCalldata_matchesReferenceEncoder() {
        val memo = "=:BTC.BTC:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu:4950000"
        val data = ThorRouter.depositWithExpiry("0x1111111111111111111111111111111111111111", BigInteger.TEN.pow(18), memo, 1800000900L)
        assertEquals("0x44bc937b000000000000000000000000111111111111111111111111111111111111111100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000de0b6b3a764000000000000000000000000000000000000000000000000000000000000000000a0000000000000000000000000000000000000000000000000000000006b49d584000000000000000000000000000000000000000000000000000000000000003c3d3a4254432e4254433a626331716372387465346b723630396763617775746d727a61306a34787638306a79387a3330366679753a3439353030303000000000", data)
        assertEquals("44bc937b", org.web3j.crypto.Hash.sha3("depositWithExpiry(address,address,uint256,string,uint256)".toByteArray()).copyOfRange(0, 4).hex())
    }

    @Test fun routerCalldata_rejectsBadInput() {
        fun bad(block: () -> Unit) { try { block(); fail() } catch (e: IllegalArgumentException) { } }
        bad { ThorRouter.depositWithExpiry("not-an-address", BigInteger.ONE, "=:BTC.BTC:x:1", 1) }
        bad { ThorRouter.depositWithExpiry(evmVault, BigInteger.ZERO, "=:BTC.BTC:x:1", 1) }
        bad { ThorRouter.depositWithExpiry(evmVault, BigInteger.ONE, "", 1) }
        bad { ThorRouter.depositWithExpiry(evmVault, BigInteger.ONE, "caf\u00e9", 1) }
    }

    @Test fun unitsToWei_scalesFrom1e8To1e18() {
        assertEquals(big("10000000000000000"), ThorRouter.unitsToWei(big("1000000")))   // 0.01 coin
        assertEquals(BigInteger.TEN.pow(18), ThorRouter.unitsToWei(big("100000000")))   // 1 coin
    }

    @Test fun evmSources_andAssets() {
        assertEquals(listOf(Chain.BTC, Chain.LTC, Chain.TRX), ThorAssets.DESTINATIONS[Chain.ETH])
        assertEquals(listOf(Chain.BTC, Chain.LTC, Chain.TRX), ThorAssets.DESTINATIONS[Chain.BNB])
        assertEquals("BSC.BNB", ThorAssets.asset(Chain.BNB))
        assertEquals("BSC", ThorAssets.vaultChain(Chain.BNB))
        assertEquals("ETH", ThorAssets.vaultChain(Chain.ETH))
    }

    @Test fun quorum_comparesRoutersToo_andEvmAddressesIgnoreCase() {
        assertNotNull(ThorQuorum.agree(listOf(evmInbound(), evmInbound(r = "0x2222222222222222222222222222222222222222"))).second)
        assertNull(ThorQuorum.agree(listOf(evmInbound(), evmInbound(v = evmVault.uppercase().replace("0X", "0x"), r = router.lowercase()))).second)
    }

    @Test fun guard_requiresTheQuotesRouterToMatchTheAgreedOne() {
        assertNull(ThorGuard.validate(evmQuote(), evmVault, evmDest, big("1000000"), now, router))
        assertNotNull(ThorGuard.validate(evmQuote(r = "0x2222222222222222222222222222222222222222"), evmVault, evmDest, big("1000000"), now, router))
        assertNull("case differences are fine", ThorGuard.validate(evmQuote(r = router.lowercase()), evmVault, evmDest, big("1000000"), now, router))
    }

    @Test fun parse_routerFields() {
        val q = ThorParse.quote(JSONObject("""{"inbound_address":"$evmVault","router":"$router","expiry":1800000600,"expected_amount_out":"5","fees":{"total":"1"}}"""))
        assertEquals(router, q.router)
        val i = ThorParse.inbound(JSONArray("""[{"chain":"ETH","address":"$evmVault","router":"$router","halted":false}]"""), "ETH")!!
        assertEquals(router, i.router)
    }

    @Test fun plan_carriesTheRouter_andRecheckCatchesAChange() {
        val api = FakeApi(evmInbound() to null, evmQuote())
        val plan = swaps(api).plan(Chain.ETH, Chain.BTC, evmDest, big("1000000"))
        assertEquals(router, plan.router)
        assertEquals(evmVault, plan.vault)
        assertEquals("ETH.ETH", api.asked!![0])
        swaps(FakeApi(evmInbound() to null, evmQuote())).recheck(plan)
        try { swaps(FakeApi(evmInbound(r = "0x2222222222222222222222222222222222222222") to null, evmQuote())).recheck(plan); fail() } catch (e: IllegalStateException) { }
        assertTrue(swaps(FakeApi(evmInbound() to null, evmQuote())).dryRunReport(plan).contains("Router contract: $router"))
    }

    // ---- EVM tokens as the thing being sold ----

    @Test fun tokenAssetNames() {
        assertEquals("ETH.USDC-0XA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48", ThorAssets.evmTokenAsset(Chain.ETH, "USDC", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"))
        assertEquals("BASE.USDC-0X833589FCD6EDB6E08F4C7C32D4F71B54BDA02913", ThorAssets.evmTokenAsset(Chain.BASE, "usdc", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"))
        assertEquals("BSC.USDT-0X55D398326F99059FF775485246999027B3197955", ThorAssets.evmTokenAsset(Chain.BNB, "USDT", "0x55d398326f99059fF775485246999027B3197955"))
        assertNull("not an EVM chain", ThorAssets.evmTokenAsset(Chain.BTC, "USDC", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"))
        assertNull("chain THORChain doesn't serve", ThorAssets.evmTokenAsset(Chain.ARB, "USDC", "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"))
        assertNull("bad contract", ThorAssets.evmTokenAsset(Chain.ETH, "USDC", "0x1234"))
        assertNull("bad symbol", ThorAssets.evmTokenAsset(Chain.ETH, "US DC", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"))
    }

    @Test fun tokenUnits_exactBothWays() {
        assertEquals(big("500000000"), ThorTokens.toUnits(big("5000000"), 6))              // 5 USDC -> 5.0 in 1e8 units
        assertEquals(big("5000000"), ThorTokens.fromUnits(big("500000000"), 6))
        assertEquals(big("100000000"), ThorTokens.toUnits(BigInteger.TEN.pow(18), 18))     // 1 coin with 18 decimals
        assertEquals(BigInteger.TEN.pow(18), ThorTokens.fromUnits(big("100000000"), 18))
        assertEquals(big("1"), ThorTokens.toUnits(big("10000000000"), 18))                 // the smallest representable amount
        try { ThorTokens.toUnits(big("1"), 18); fail() } catch (e: IllegalArgumentException) { }     // finer than 1e-8: refused, never rounded
        try { ThorTokens.fromUnits(big("1"), 6); fail() } catch (e: IllegalArgumentException) { }    // 1e-8 of a 6-decimal token
        try { ThorTokens.toUnits(BigInteger.ZERO, 6); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun plan_usesTheTokenAssetForTheQuote_andCarriesTokenDetails() {
        val api = FakeApi(evmInbound() to null, evmQuote())
        val asset = "ETH.USDC-0XA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48"
        val plan = swaps(api).plan(Chain.ETH, Chain.BTC, evmDest, big("500000000"), asset, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", 6)
        assertEquals(asset, api.asked!![0])
        assertEquals("500000000", api.asked!![2])
        assertEquals("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", plan.tokenAddress)
        assertEquals(6, plan.tokenDecimals)
        assertTrue(swaps(FakeApi(evmInbound() to null, evmQuote())).dryRunReport(plan).contains("token 0xA0b86991"))
    }

    @Test fun routerCalldata_forAToken_putsTheTokenInTheAssetSlot() {
        val usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
        val data = ThorRouter.depositWithExpiry(evmVault, big("5000000"), "=:BTC.BTC:x:1", 1800000900L, usdc)
        assertTrue(data.contains(usdc.removePrefix("0x").lowercase()))
        assertTrue(data.startsWith("0x44bc937b"))
        assertFalse("the native asset slot is not used", ThorRouter.depositWithExpiry(evmVault, big("5000000"), "=:BTC.BTC:x:1", 1800000900L) == data)
        try { ThorRouter.depositWithExpiry(evmVault, big("5"), "=:BTC.BTC:x:1", 1L, "usdc"); fail() } catch (e: IllegalArgumentException) { }
    }

    // ---- data sources ----

    @Test fun defaultSources_areTwoOperators_notTheRetiredNineRealmsHosts() {
        val urls = ThorApi.DEFAULT_SOURCES.map { it.inboundUrl }
        assertEquals(
            listOf(
                "https://gateway.liquify.com/chain/thorchain_api/thorchain/inbound_addresses",
                "https://rest.cosmos.directory/thorchain/thorchain/inbound_addresses"
            ), urls
        )
        assertTrue("Nine Realms retired these hosts in April 2026", urls.none { it.contains("ninerealms") })
        assertTrue("at least as many sources as the quorum needs", ThorApi.DEFAULT_SOURCES.size >= ThorQuorum.MIN_NODES)
        assertEquals("different hosts", 2, ThorApi.DEFAULT_SOURCES.map { java.net.URI(it.baseUrl).host }.toSet().size)
        assertTrue("quotes need a THORNode source", ThorApi.DEFAULT_SOURCES.any { !it.midgard })
    }

    @Test fun midgardInboundJson_parsesLikeThorNodes() {
        // Midgard's /v2/thorchain/inbound_addresses mirrors THORNode's array of per-chain entries
        val arr = JSONArray("""[{"address":"$vault","chain":"LTC","halted":false,"chain_trading_paused":false,"global_trading_paused":false,"router":null}]""")
        val i = ThorParse.inbound(arr, "LTC")!!
        assertEquals(vault, i.address)
        assertEquals("", i.router)   // a JSON null router must read as "no router", not the string "null"
        assertFalse(i.halted)
    }

    // ---- minimums ----

    @Test fun parse_dustThreshold_andQuorumKeepsTheStrictest() {
        val arr = JSONArray("""[{"chain":"LTC","address":"$vault","dust_threshold":"10000","halted":false}]""")
        assertEquals(big("10000"), ThorParse.inbound(arr, "LTC")!!.dustThreshold)
        assertEquals(BigInteger.ZERO, ThorParse.inbound(JSONArray("""[{"chain":"LTC","address":"$vault"}]"""), "LTC")!!.dustThreshold)
        val a = ThorInbound("LTC", vault, false, false, "", big("10000"))
        val b = ThorInbound("LTC", vault, false, false, "", big("25000"))
        assertEquals(big("25000"), ThorQuorum.agree(listOf(a, b)).first!!.dustThreshold)
        assertEquals(big("25000"), ThorQuorum.agree(listOf(b, a)).first!!.dustThreshold)
    }

    @Test fun plan_refusesBelowTheDustThreshold_beforeAskingForAQuote() {
        val api = FakeApi(ThorInbound("LTC", vault, false, false, "", big("10000")) to null, quote(minIn = "1000"))
        try { swaps(api).plan(Chain.LTC, Chain.BTC, btcDest, big("9999")); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!, e.message!!.contains("0.0001 LTC")) }
        assertNull("no quote request was made", api.asked)
        swaps(api).plan(Chain.LTC, Chain.BTC, btcDest, big("10000")) // exactly at the threshold is accepted by our check
        assertNotNull(api.asked)
    }

    @Test fun friendlyErrors() {
        val dust = ThorErrors.friendly("HTTP 400: {\"code\":3,\"message\":\"amount less than dust threshold: invalid request\"}")
        assertTrue(dust, dust.startsWith("That amount is below THORChain's minimum for this network"))
        assertTrue(dust.contains("dust threshold"))               // the original text stays visible
        assertTrue(ThorErrors.friendly("not enough asset to pay for fees").contains("too small to cover"))
        assertTrue(ThorErrors.friendly("trading is halted").contains("paused"))
        assertEquals("THORChain did not answer", ThorErrors.friendly(null))
        assertEquals("something unrelated", ThorErrors.friendly("something unrelated"))
    }

    // ---- OP_RETURN and coin selection ----

    @Test fun opReturn_scriptAndSize() {
        val short = ByteArray(10) { 7 }
        assertEquals("6a0a" + "07".repeat(10), BitcoinTx.opReturnScript(short).hex())
        assertEquals(9 + 12, BitcoinTx.opReturnOutputBytes(short))
        val long = ByteArray(80) { 1 }
        assertEquals("6a4c50", BitcoinTx.opReturnScript(long).hex().substring(0, 6)) // OP_PUSHDATA1 for 76..80 bytes
        assertEquals(83, BitcoinTx.opReturnScript(long).size)
        try { BitcoinTx.opReturnScript(ByteArray(81)); fail() } catch (e: IllegalArgumentException) { }
        try { BitcoinTx.opReturnScript(ByteArray(0)); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun coinSelection_countsTheMemoOutput() {
        val utxos = listOf(Utxo("11".repeat(32), 0, 10_000_000))
        val plain = CoinSelection.select(utxos, 1_000_000, 22, 2.0)
        val memo = CoinSelection.select(utxos, 1_000_000, 22, 2.0, extraBytes = 9 + 2 + 60)
        assertEquals(2L * 71, memo.fee - plain.fee)
    }

    @Test fun signedTransaction_containsTheMemoOutput_afterVaultAndChange() {
        val kp = Hd.secp256k1(abandonSeed(), 84 or Hd.H, 2 or Hd.H, 0 or Hd.H, 0, 0)
        val pub = kp.publicKeyPoint.getEncoded(true)
        val pubHash = Hd.hash160(pub)
        val memo = "=:BTC.BTC:$btcDest:4950000".toByteArray()
        val vaultScript = byteArrayOf(0x00, 0x14) + ByteArray(20) { 9 }
        val myScript = byteArrayOf(0x00, 0x14) + pubHash
        val outs = listOf(TxOut(1_000_000, vaultScript), TxOut(500_000, myScript), TxOut(0, BitcoinTx.opReturnScript(memo)))
        val raw = BitcoinTx.signP2wpkh(listOf(Utxo("11".repeat(32), 0, 1_600_000)), outs, kp, pub, pubHash)
        val hex = raw.hex()
        val script = vaultScript.hex(); val change = myScript.hex(); val data = BitcoinTx.opReturnScript(memo).hex()
        assertTrue(hex.indexOf(script) in 0 until hex.indexOf(change))
        assertTrue(hex.indexOf(change) < hex.indexOf(data))
        assertTrue("zero-value output: 8 zero bytes then the script length", hex.contains("0000000000000000" + "%02x".format(data.length / 2) + data))
    }
}
