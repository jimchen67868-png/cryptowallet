package com.example.wallet.chain

import com.example.wallet.data.TokenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.math.BigInteger

/**
 * "Bridge then swap on arrival". The network is faked, so these tests drive the real state machine: what counts as
 * arrival, what must NOT count, how much is swapped, and that a job survives an app restart.
 */
class BridgeSwapTest {
    private val usdcEth = TokenInfo("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", 6)
    private val usdcArb = TokenInfo("0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "USDC", 6)
    private val wethEth = TokenInfo("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "WETH", 18)
    private val wethArb = TokenInfo("0x82aF49447D8a07e3bd95BD0d56f35241523fBab1", "WETH", 18)
    private val wethBase = TokenInfo("0x4200000000000000000000000000000000000006", "WETH", 18)
    private val wethPol = TokenInfo("0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619", "WETH", 18)
    private val pool = "0x0000000000000000000000000000000000000001"

    private val ethRoute = BridgeRoute(Chain.ETH, 1L, pool, true, listOf(usdcEth, wethEth))
    private val arbRoute = BridgeRoute(Chain.ARB, 42161L, pool, true, listOf(usdcArb, wethArb))
    private val baseRoute = BridgeRoute(Chain.BASE, 8453L, pool, true, listOf(wethBase))
    private val polRoute = BridgeRoute(Chain.POL, 137L, pool, false, listOf(wethPol))

    private val nativeEth = SwapAsset(null, "ETH", 18)
    private val usdcArbAsset = SwapAsset(usdcArb.address, "USDC", 6)
    private val wethArbAsset = SwapAsset(wethArb.address, "WETH", 18)
    private val target = SwapAsset("0x912CE59144191C1204E64559FE8253a0e49E6548", "ARB", 18)
    private val depositTx = "0x" + "ab".repeat(32)

    private fun job(
        stage: JobStage = JobStage.WAITING_FILL,
        started: Long = 1_000L,
        expected: BigInteger = BigInteger.valueOf(1_000_000),
        candidates: List<ArrivalCandidate> = listOf(ArrivalCandidate(usdcArbAsset, BigInteger.ZERO)),
        arrived: SwapAsset? = null
    ) = BridgeSwapJob(
        id = "job1", originChain = Chain.ETH, destChain = Chain.ARB, depositTxId = depositTx,
        startedAtSec = started, expectedOutRaw = expected, candidates = candidates, target = target,
        previewInRaw = BigInteger.valueOf(1_000_000), previewOutRaw = BigInteger.TEN.pow(18),
        stage = stage, arrivedAsset = arrived
    )

    // ---- which assets can arrive ----

    @Test fun candidates_nativeEthToEthGasChainWatchesEthAndWeth() {
        val c = BridgeSwapLogic.candidateAssets(nativeEth, ethRoute, baseRoute)
        assertEquals(listOf(SwapAsset(null, "ETH", 18), SwapAsset(wethBase.address, "WETH", 18)), c)
    }

    @Test fun candidates_nativeEthToPolygonArrivesAsWethOnly() =
        assertEquals(listOf(SwapAsset(wethPol.address, "WETH", 18)), BridgeSwapLogic.candidateAssets(nativeEth, ethRoute, polRoute))

    @Test fun candidates_nativePolygonCannotBeBridged() =
        assertTrue(BridgeSwapLogic.candidateAssets(SwapAsset(null, "POL", 18), polRoute, ethRoute).isEmpty())

    @Test fun candidates_tokenArrivesAsSameSymbolOnDestination() =
        assertEquals(listOf(usdcArbAsset), BridgeSwapLogic.candidateAssets(SwapAsset(usdcEth.address.lowercase(), "USDC", 6), ethRoute, arbRoute))

    @Test fun candidates_tokenMissingOnEitherSideGivesNothing() {
        assertTrue(BridgeSwapLogic.candidateAssets(SwapAsset("0x0000000000000000000000000000000000000abc", "XYZ", 18), ethRoute, arbRoute).isEmpty())
        assertTrue(BridgeSwapLogic.candidateAssets(SwapAsset(usdcEth.address, "USDC", 6), ethRoute, baseRoute).isEmpty())
    }

    @Test fun candidates_decimalMismatchGivesNothing() {
        val odd = BridgeRoute(Chain.ARB, 42161L, pool, true, listOf(TokenInfo(usdcArb.address, "USDC", 18), wethArb))
        assertTrue(BridgeSwapLogic.candidateAssets(SwapAsset(usdcEth.address, "USDC", 6), ethRoute, odd).isEmpty())
    }

    // ---- arrival detection ----

    @Test fun arrival_needsTheFullAmountWithinTolerance() {
        val j = job()
        assertNull(BridgeSwapLogic.arrivedAsset(j, listOf(BigInteger.valueOf(998_999))))
        assertEquals(usdcArbAsset, BridgeSwapLogic.arrivedAsset(j, listOf(BigInteger.valueOf(999_000))))
        assertEquals(usdcArbAsset, BridgeSwapLogic.arrivedAsset(j, listOf(BigInteger.valueOf(1_000_000))))
    }

    @Test fun arrival_isMeasuredAgainstTheBalanceBeforeTheBridge() {
        val j = job(candidates = listOf(ArrivalCandidate(usdcArbAsset, BigInteger.valueOf(5_000_000))))
        assertNull("an old balance is not an arrival", BridgeSwapLogic.arrivedAsset(j, listOf(BigInteger.valueOf(5_000_000))))
        assertEquals(usdcArbAsset, BridgeSwapLogic.arrivedAsset(j, listOf(BigInteger.valueOf(6_000_000))))
    }

    @Test fun arrival_findsWhichOfEthAndWethArrived() {
        val one = BigInteger.TEN.pow(18)
        val j = job(expected = one, candidates = listOf(
            ArrivalCandidate(SwapAsset(null, "ETH", 18), BigInteger.ZERO),
            ArrivalCandidate(wethArbAsset, BigInteger.ZERO)
        ))
        assertEquals(SwapAsset(null, "ETH", 18), BridgeSwapLogic.arrivedAsset(j, listOf(one, BigInteger.ZERO)))
        assertEquals(wethArbAsset, BridgeSwapLogic.arrivedAsset(j, listOf(BigInteger.ZERO, one)))
    }

    @Test fun arrival_unknownBalancesAreSkippedNotTreatedAsArrived() {
        val j = job()
        assertNull(BridgeSwapLogic.arrivedAsset(j, listOf(null)))
        assertNull(BridgeSwapLogic.arrivedAsset(j, emptyList()))
    }

    // ---- stage transitions ----

    private val big = BigInteger.valueOf(1_000_000)

    @Test fun stage_filledAndArrivedMovesOn() =
        assertEquals(JobStage.ARRIVED, BridgeSwapLogic.nextStage(job(), FillStatus.FILLED, usdcArbAsset, 1_100))

    @Test fun stage_unreachableStatusApiStillAllowsBalanceBasedArrival() =
        assertEquals(JobStage.ARRIVED, BridgeSwapLogic.nextStage(job(), FillStatus.UNKNOWN, usdcArbAsset, 1_100))

    @Test fun stage_pendingStatusBlocksArrivalEvenIfBalanceRose() =
        assertEquals(JobStage.WAITING_FILL, BridgeSwapLogic.nextStage(job(), FillStatus.PENDING, usdcArbAsset, 1_100))

    @Test fun stage_filledButBalanceNotVisibleYetKeepsWaiting() =
        assertEquals(JobStage.WAITING_FILL, BridgeSwapLogic.nextStage(job(), FillStatus.FILLED, null, 1_100))

    @Test fun stage_expiredOrRefundedIsAFailedBridge() {
        assertEquals(JobStage.BRIDGE_FAILED, BridgeSwapLogic.nextStage(job(), FillStatus.EXPIRED, null, 1_100))
        assertEquals(JobStage.BRIDGE_FAILED, BridgeSwapLogic.nextStage(job(), FillStatus.REFUNDED, null, 1_100))
    }

    @Test fun stage_timesOutAfterTwentyFourHours() {
        val day = BridgeSwapLogic.GIVE_UP_AFTER_SECONDS
        assertEquals(JobStage.WAITING_FILL, BridgeSwapLogic.nextStage(job(), FillStatus.PENDING, null, 1_000 + day))
        assertEquals(JobStage.TIMED_OUT, BridgeSwapLogic.nextStage(job(), FillStatus.PENDING, null, 1_000 + day + 1))
    }

    @Test fun stage_aTimedOutJobCanStillArriveOrFail() {
        val t = job(stage = JobStage.TIMED_OUT)
        assertEquals(JobStage.ARRIVED, BridgeSwapLogic.nextStage(t, FillStatus.FILLED, usdcArbAsset, 999_999))
        assertEquals(JobStage.BRIDGE_FAILED, BridgeSwapLogic.nextStage(t, FillStatus.EXPIRED, null, 999_999))
        assertEquals(JobStage.TIMED_OUT, BridgeSwapLogic.nextStage(t, FillStatus.PENDING, null, 999_999))
    }

    @Test fun stage_laterStagesNeverGoBackwards() {
        for (s in listOf(JobStage.ARRIVED, JobStage.SWAPPING, JobStage.DONE, JobStage.BRIDGE_FAILED)) {
            assertEquals(s, BridgeSwapLogic.nextStage(job(stage = s), FillStatus.EXPIRED, null, 9_999_999))
            assertEquals(s, BridgeSwapLogic.nextStage(job(stage = s), FillStatus.FILLED, usdcArbAsset, 9_999_999))
        }
    }

    // ---- how much to swap ----

    @Test fun amount_isWhatArrivedCappedAtTheQuote() {
        val j = job(candidates = listOf(ArrivalCandidate(usdcArbAsset, BigInteger.valueOf(2_000_000))))
        // 999_500 arrived (within tolerance): swap exactly that, not the quoted 1_000_000
        assertEquals(BigInteger.valueOf(999_500), BridgeSwapLogic.swapAmountRaw(j, usdcArbAsset, BigInteger.valueOf(2_999_500), BigInteger.ZERO))
        // extra deposits by someone else are never swapped
        assertEquals(BigInteger.valueOf(1_000_000), BridgeSwapLogic.swapAmountRaw(j, usdcArbAsset, BigInteger.valueOf(9_000_000), BigInteger.ZERO))
    }

    @Test fun amount_keepsAReserveForNetworkFees() {
        val one = BigInteger.TEN.pow(18)
        val reserve = BridgeSwapLogic.gasReserveRaw(Chain.ARB)
        val j = job(expected = one, candidates = listOf(ArrivalCandidate(SwapAsset(null, "ETH", 18), BigInteger.ZERO)))
        assertEquals(one.subtract(reserve), BridgeSwapLogic.swapAmountRaw(j, SwapAsset(null, "ETH", 18), one, reserve))
    }

    @Test fun amount_isNullWhenTheFundsAreGoneOrTheReserveEatsEverything() {
        val j = job()
        assertNull("not arrived / already swapped", BridgeSwapLogic.swapAmountRaw(j, usdcArbAsset, BigInteger.valueOf(10), BigInteger.ZERO))
        assertNull("reserve larger than the amount", BridgeSwapLogic.swapAmountRaw(j, usdcArbAsset, big, big))
        assertNull("not a candidate asset", BridgeSwapLogic.swapAmountRaw(j, wethArbAsset, big, BigInteger.ZERO))
    }

    @Test fun reserve_isLargestOnEthereumMainnet() {
        assertTrue(BridgeSwapLogic.gasReserveRaw(Chain.ETH) > BridgeSwapLogic.gasReserveRaw(Chain.BASE))
        assertTrue(BridgeSwapLogic.gasReserveRaw(Chain.ARB).signum() > 0)
        assertTrue(BridgeSwapLogic.gasReserveRaw(Chain.POL).signum() > 0)
    }

    // ---- price drift between the preview and the arrival-time quote ----

    private fun bi(v: Long) = BigInteger.valueOf(v)

    @Test fun drift_sameRateIsZeroAndWorseIsPositive() {
        assertEquals(0L, BridgeSwapLogic.priceDriftBps(bi(1000), bi(2000), bi(500), bi(1000)))
        assertEquals(500L, BridgeSwapLogic.priceDriftBps(bi(1000), bi(2000), bi(500), bi(950)))
        assertEquals(-1000L, BridgeSwapLogic.priceDriftBps(bi(1000), bi(2000), bi(500), bi(1100)))
    }

    @Test fun drift_missingOrZeroPreviewGivesNoVerdict() {
        assertNull(BridgeSwapLogic.priceDriftBps(null, bi(1), bi(1), bi(1)))
        assertNull(BridgeSwapLogic.priceDriftBps(bi(1), null, bi(1), bi(1)))
        assertNull(BridgeSwapLogic.priceDriftBps(bi(0), bi(1), bi(1), bi(1)))
        assertNull(BridgeSwapLogic.priceDriftBps(bi(1), bi(1), bi(0), bi(1)))
    }

    @Test fun drift_warnsOnlyAboveTheThreshold() {
        assertNull(BridgeSwapLogic.driftWarning(null))
        assertNull(BridgeSwapLogic.driftWarning(BridgeSwapLogic.MAX_PRICE_DRIFT_BPS))
        assertNull(BridgeSwapLogic.driftWarning(-500))
        assertTrue(BridgeSwapLogic.driftWarning(301)!!.contains("3.01%"))
    }

    // ---- status text ----

    @Test fun statusText_coversEveryStage() {
        for (s in JobStage.values()) assertTrue(BridgeSwapLogic.statusText(job(stage = s), 1_600).isNotBlank())
        assertTrue(BridgeSwapLogic.statusText(job(), 1_000 + 600).contains("10 min"))
        assertTrue(BridgeSwapLogic.statusText(job(), 1_000 + BridgeSwapLogic.SLOW_AFTER_SECONDS + 60).contains("longer than usual"))
        assertTrue(BridgeSwapLogic.statusText(job(stage = JobStage.ARRIVED), 2_000).contains("ARB"))
    }

    // ---- persistence format ----

    @Test fun codec_roundTripKeepsEverything() {
        val full = job(
            stage = JobStage.DONE, arrived = SwapAsset(null, "ETH", 18),
            candidates = listOf(
                ArrivalCandidate(SwapAsset(null, "ETH", 18), BigInteger("123456789012345678901234567890")),
                ArrivalCandidate(wethArbAsset, BigInteger.ZERO)
            )
        ).copy(swapTxId = "0x" + "cd".repeat(32), target = target.copy(curated = false))
        assertEquals(full, BridgeSwapJobCodec.fromJson(JSONObject(BridgeSwapJobCodec.toJson(full).toString())))
    }

    @Test fun codec_roundTripWithoutOptionalFields() {
        val j = job().copy(previewInRaw = null, previewOutRaw = null)
        assertEquals(j, BridgeSwapJobCodec.fromJson(JSONObject(BridgeSwapJobCodec.toJson(j).toString())))
    }

    @Test fun codec_rejectsCorruptRecords() {
        val good = BridgeSwapJobCodec.toJson(job())
        fun mutated(key: String, value: Any?) = JSONObject(good.toString()).also { it.put(key, value) }
        assertNotNull(BridgeSwapJobCodec.fromJson(good))
        assertNull(BridgeSwapJobCodec.fromJson(mutated("stage", "NOT_A_STAGE")))
        assertNull(BridgeSwapJobCodec.fromJson(mutated("origin", "DOGE")))
        assertNull(BridgeSwapJobCodec.fromJson(mutated("expectedOut", "twelve")))
        assertNull(BridgeSwapJobCodec.fromJson(JSONObject(good.toString()).also { it.remove("deposit") }))
        assertNull(BridgeSwapJobCodec.fromJson(JSONObject("{}")))
    }

    // ---- Across status parsing ----

    @Test fun acrossStatus_mapsKnownValues() {
        assertEquals(FillStatus.FILLED, AcrossStatus.parse("""{"status":"filled","fillTx":"0x1"}"""))
        assertEquals(FillStatus.PENDING, AcrossStatus.parse("""{"status":"pending"}"""))
        assertEquals(FillStatus.EXPIRED, AcrossStatus.parse("""{"status":"expired"}"""))
        assertEquals(FillStatus.REFUNDED, AcrossStatus.parse("""{"status":"refunded"}"""))
        assertEquals(FillStatus.FILLED, AcrossStatus.parse("""{"status":"Filled"}"""))
    }

    @Test fun acrossStatus_anythingElseIsUnknownNeverFilled() {
        for (body in listOf("", "not json", "{}", """{"status":"deposit-pending"}""", """{"status":null}""",
            """{"message":"Deposit not found"}""", """{"status":"filled-ish"}""")) {
            assertEquals(body, FillStatus.UNKNOWN, AcrossStatus.parse(body))
        }
    }

    @Test fun txIdPattern_acceptsOnlyAFullHash() {
        assertTrue(BridgeSwapLogic.TX_ID.matches(depositTx))
        for (bad in listOf("", "0x", "0x1234", depositTx + "&x=1", depositTx.drop(1), "0x" + "zz".repeat(32))) {
            assertFalse(bad, BridgeSwapLogic.TX_ID.matches(bad))
        }
    }

    // ---- coordinator, with the network faked ----

    private class Net(
        var status: FillStatus = FillStatus.PENDING,
        var balances: List<BigInteger?> = listOf(BigInteger.ZERO),
        var statusThrows: Boolean = false,
        var balancesThrow: Boolean = false,
        var now: Long = 1_100L
    ) {
        var statusCalls = 0
        var balanceCalls = 0
    }

    private fun coordinator(store: BridgeSwapStore, net: Net) = BridgeSwapCoordinator(
        store = store,
        fillStatus = { net.statusCalls++; if (net.statusThrows) throw IOException("offline"); net.status },
        balances = { net.balanceCalls++; if (net.balancesThrow) throw IOException("rpc down"); net.balances },
        nowSec = { net.now }
    )

    @Test fun coordinator_stillPendingChangesNothing() = runBlocking {
        val store = InMemoryBridgeSwapStore().also { it.save(job()) }
        val net = Net(status = FillStatus.PENDING)
        val after = coordinator(store, net).poll(job())
        assertEquals(JobStage.WAITING_FILL, after.stage)
        assertEquals(JobStage.WAITING_FILL, store.list().single().stage)
        assertEquals("no balance lookups while Across says pending", 0, net.balanceCalls)
    }

    @Test fun coordinator_arrivalIsSavedAndSurvivesARestart() = runBlocking {
        val store = InMemoryBridgeSwapStore().also { it.save(job()) }
        val net = Net(status = FillStatus.FILLED, balances = listOf(big))
        val after = coordinator(store, net).poll(job())
        assertEquals(JobStage.ARRIVED, after.stage)
        assertEquals(usdcArbAsset, after.arrivedAsset)

        // "App restart": a new coordinator over the same store sees the arrived job and does not poll it again.
        val restarted = coordinator(store, Net())
        val loaded = restarted.recoverInterrupted().single()
        assertEquals(JobStage.ARRIVED, loaded.stage)
        assertEquals(usdcArbAsset, loaded.arrivedAsset)
    }

    @Test fun coordinator_filledButBalanceLagsThenCatchesUp() = runBlocking {
        val store = InMemoryBridgeSwapStore().also { it.save(job()) }
        val net = Net(status = FillStatus.FILLED, balances = listOf(BigInteger.ZERO))
        val c = coordinator(store, net)
        var j = c.poll(job())
        assertEquals(JobStage.WAITING_FILL, j.stage)
        net.balances = listOf(big)
        j = c.poll(j)
        assertEquals(JobStage.ARRIVED, j.stage)
    }

    @Test fun coordinator_statusApiDownFallsBackToTheBalance() = runBlocking {
        val store = InMemoryBridgeSwapStore().also { it.save(job()) }
        val net = Net(statusThrows = true, balances = listOf(BigInteger.ZERO))
        val c = coordinator(store, net)
        var j = c.poll(job())
        assertEquals("no balance rise, no arrival", JobStage.WAITING_FILL, j.stage)
        net.balances = listOf(big)
        j = c.poll(j)
        assertEquals(JobStage.ARRIVED, j.stage)
    }

    @Test fun coordinator_rpcFailureNeverAdvancesAJob() = runBlocking {
        val store = InMemoryBridgeSwapStore().also { it.save(job()) }
        val net = Net(status = FillStatus.FILLED, balancesThrow = true)
        assertEquals(JobStage.WAITING_FILL, coordinator(store, net).poll(job()).stage)
        assertEquals(JobStage.WAITING_FILL, store.list().single().stage)
    }

    @Test fun coordinator_expiredBecomesAFailedBridge() = runBlocking {
        val store = InMemoryBridgeSwapStore().also { it.save(job()) }
        val after = coordinator(store, Net(status = FillStatus.EXPIRED)).poll(job())
        assertEquals(JobStage.BRIDGE_FAILED, after.stage)
        assertNull(after.arrivedAsset)
        assertEquals(JobStage.BRIDGE_FAILED, store.list().single().stage)
    }

    @Test fun coordinator_timesOutThenCanBeCheckedAgainByHand() = runBlocking {
        val store = InMemoryBridgeSwapStore().also { it.save(job()) }
        val net = Net(status = FillStatus.PENDING, now = 1_000 + BridgeSwapLogic.GIVE_UP_AFTER_SECONDS + 5)
        val c = coordinator(store, net)
        var j = c.poll(job())
        assertEquals(JobStage.TIMED_OUT, j.stage)
        net.status = FillStatus.FILLED
        net.balances = listOf(big)
        j = c.poll(j)
        assertEquals(JobStage.ARRIVED, j.stage)
    }

    @Test fun coordinator_doesNotPollJobsPastTheWaitingStages() = runBlocking {
        val net = Net(status = FillStatus.EXPIRED)
        val c = coordinator(InMemoryBridgeSwapStore(), net)
        for (s in listOf(JobStage.ARRIVED, JobStage.SWAPPING, JobStage.DONE, JobStage.BRIDGE_FAILED)) {
            assertEquals(s, c.poll(job(stage = s)).stage)
        }
        assertEquals(0, net.statusCalls)
    }

    @Test fun coordinator_cancellationIsNotSwallowed() {
        val c = BridgeSwapCoordinator(InMemoryBridgeSwapStore(), { throw CancellationException("stop") }, { emptyList() })
        try {
            runBlocking { c.poll(job()) }
            fail("expected CancellationException")
        } catch (e: CancellationException) { /* expected */ }
    }

    @Test fun coordinator_aJobInterruptedMidSwapIsRecoveredOnlyAtSessionStart() {
        val store = InMemoryBridgeSwapStore().also { it.save(job(stage = JobStage.SWAPPING, arrived = usdcArbAsset)) }
        val c = coordinator(store, Net())
        // Listing must not undo an in-progress swap: otherwise the UI could offer a second swap while one is running.
        assertEquals(JobStage.SWAPPING, c.jobs().single().stage)
        assertEquals(JobStage.ARRIVED, c.recoverInterrupted().single().stage)
        assertEquals(JobStage.ARRIVED, store.list().single().stage)
    }

    @Test fun coordinator_fullHappyPathBridgeThenSwap() = runBlocking {
        val store = InMemoryBridgeSwapStore()
        val net = Net()
        val c = coordinator(store, net)
        c.start(job())                                   // deposit sent
        var j = c.poll(c.jobs().single())                // relayer has not filled yet
        assertEquals(JobStage.WAITING_FILL, j.stage)

        net.status = FillStatus.FILLED                   // fill lands
        net.balances = listOf(BigInteger.valueOf(999_800))
        j = c.poll(j)
        assertEquals(JobStage.ARRIVED, j.stage)

        val amount = BridgeSwapLogic.swapAmountRaw(j, j.arrivedAsset!!, BigInteger.valueOf(999_800), BigInteger.ZERO)
        assertEquals(BigInteger.valueOf(999_800), amount)

        j = c.markSwapping(j)
        assertEquals(JobStage.SWAPPING, c.jobs().single().stage)
        j = c.markDone(j, "0xswap")
        assertEquals(JobStage.DONE, c.jobs().single().stage)
        assertEquals("0xswap", c.jobs().single().swapTxId)

        c.dismiss(j)
        assertTrue(c.jobs().isEmpty())
    }

    @Test fun coordinator_aFailedSwapLeavesTheFundsReadyToRetry() {
        val store = InMemoryBridgeSwapStore()
        val c = coordinator(store, Net())
        val j = c.markSwapping(job(stage = JobStage.ARRIVED, arrived = usdcArbAsset))
        assertEquals(JobStage.ARRIVED, c.backToArrived(j).stage)
        assertEquals(usdcArbAsset, store.list().single().arrivedAsset)
    }

    @Test fun coordinator_jobsAreListedOldestFirst() {
        val store = InMemoryBridgeSwapStore()
        store.save(job().copy(id = "b", startedAtSec = 3_000))
        store.save(job().copy(id = "a", startedAtSec = 2_000))
        assertEquals(listOf("a", "b"), coordinator(store, Net()).jobs().map { it.id })
    }
}
