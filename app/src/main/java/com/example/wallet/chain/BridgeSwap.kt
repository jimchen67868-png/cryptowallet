package com.example.wallet.chain

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * "Bridge, then swap on arrival" as two separate, individually authorised steps.
 *
 * Across fills on the destination chain after the deposit, so the swap cannot be part of the same transaction.
 * The wallet therefore deposits first, watches for the funds to land, and only then asks the user to confirm a swap
 * built from a FRESH quote on the destination chain. Nothing here signs anything: this file is pure logic plus a
 * small amount of non-secret persistence, so it can be unit-tested without Android.
 */
enum class FillStatus { PENDING, FILLED, EXPIRED, REFUNDED, UNKNOWN }

enum class JobStage {
    /** Deposit sent; waiting for a relayer to fill on the destination chain. */
    WAITING_FILL,
    /** Funds are on the destination chain; the user has not confirmed the swap yet. */
    ARRIVED,
    /** The swap is being signed/sent. Resets to ARRIVED if the app dies in this stage. */
    SWAPPING,
    DONE,
    /** Across reports the deposit expired or was refunded: nothing arrived, the funds go back to the origin chain. */
    BRIDGE_FAILED,
    /** No arrival seen for a long time. Polling stops; the user can check again by hand. */
    TIMED_OUT
}

/** An asset that may show up on the destination chain, with its balance before the bridge was sent. */
data class ArrivalCandidate(val asset: SwapAsset, val preBalanceRaw: BigInteger)

data class BridgeSwapJob(
    val id: String,
    val originChain: Chain,
    val destChain: Chain,
    val depositTxId: String,
    val startedAtSec: Long,
    /** What the bridge quote said would arrive (input minus fee). */
    val expectedOutRaw: BigInteger,
    val candidates: List<ArrivalCandidate>,
    val target: SwapAsset,
    /** Indicative swap quote taken when the user started, used only to warn about price drift on arrival. */
    val previewInRaw: BigInteger?,
    val previewOutRaw: BigInteger?,
    val stage: JobStage = JobStage.WAITING_FILL,
    val arrivedAsset: SwapAsset? = null,
    val swapTxId: String? = null
)

object BridgeSwapLogic {
    /** Funds count as arrived once the balance rose by at least this share of the quoted amount (99.9%). */
    const val ARRIVAL_TOLERANCE_BPS = 10L
    const val SLOW_AFTER_SECONDS = 2 * 3_600L
    const val GIVE_UP_AFTER_SECONDS = 24 * 3_600L

    /** Warn when the arrival-time swap price is this much worse than the preview (3%). */
    const val MAX_PRICE_DRIFT_BPS = 300L

    val TX_ID = Regex("0x[0-9a-fA-F]{64}")

    fun minArrival(expectedOutRaw: BigInteger): BigInteger =
        expectedOutRaw.multiply(BigInteger.valueOf(10_000L - ARRIVAL_TOLERANCE_BPS)).divide(BigInteger.valueOf(10_000L))

    /**
     * Native coin kept back when the swap input is the chain's native coin, so the swap itself can be paid for.
     * A heuristic, not an estimate: the swap fails closed (node or simulation rejects it) if it is too low.
     */
    fun gasReserveRaw(chain: Chain): BigInteger = when (chain) {
        Chain.ETH -> BigInteger("6000000000000000")       // 0.006 ETH
        Chain.BASE, Chain.ARB -> BigInteger("200000000000000") // 0.0002 ETH
        Chain.POL -> BigInteger("50000000000000000")      // 0.05 POL
        else -> BigInteger("6000000000000000")
    }

    /**
     * Assets the bridged funds may arrive as. Native ETH is filled as ETH on ETH-gas chains (and as WETH on Polygon);
     * we watch both ETH and WETH where either is possible. A token arrives as the same-symbol token on the destination.
     * Empty if this asset cannot be bridged between the two routes.
     */
    fun candidateAssets(from: SwapAsset, origin: BridgeRoute, dest: BridgeRoute): List<SwapAsset> {
        if (from.id == null) {
            if (!origin.nativeIsEth) return emptyList()
            val weth = SwapAsset(dest.weth, "WETH", 18)
            return if (dest.nativeIsEth) listOf(SwapAsset(null, dest.chain.symbol, 18), weth) else listOf(weth)
        }
        val src = origin.tokens.firstOrNull { it.address.equals(from.id, ignoreCase = true) } ?: return emptyList()
        val dst = dest.tokens.firstOrNull { it.symbol == src.symbol } ?: return emptyList()
        if (dst.decimals != src.decimals) return emptyList()
        return listOf(SwapAsset(dst.address, dst.symbol, dst.decimals))
    }

    private fun sameAsset(a: SwapAsset, b: SwapAsset) = SwapGuard.tokenKey(a) == SwapGuard.tokenKey(b)

    /** [currentRaw] is parallel to [BridgeSwapJob.candidates]; null entries mean "balance unknown". */
    fun arrivedAsset(job: BridgeSwapJob, currentRaw: List<BigInteger?>): SwapAsset? {
        val need = minArrival(job.expectedOutRaw)
        for ((i, c) in job.candidates.withIndex()) {
            val cur = currentRaw.getOrNull(i) ?: continue
            if (cur.subtract(c.preBalanceRaw) >= need) return c.asset
        }
        return null
    }

    /**
     * Next stage after one poll. Arrival needs a balance increase AND either Across saying "filled" or Across
     * being unreachable/unrecognised (UNKNOWN). A balance increase while Across still says "pending" is not enough:
     * it could be an unrelated deposit.
     */
    fun nextStage(job: BridgeSwapJob, status: FillStatus, arrived: SwapAsset?, nowSec: Long): JobStage {
        if (job.stage != JobStage.WAITING_FILL && job.stage != JobStage.TIMED_OUT) return job.stage
        return when {
            status == FillStatus.EXPIRED || status == FillStatus.REFUNDED -> JobStage.BRIDGE_FAILED
            arrived != null && (status == FillStatus.FILLED || status == FillStatus.UNKNOWN) -> JobStage.ARRIVED
            job.stage == JobStage.WAITING_FILL && nowSec - job.startedAtSec > GIVE_UP_AFTER_SECONDS -> JobStage.TIMED_OUT
            else -> job.stage
        }
    }

    /**
     * How much to swap: what actually arrived (never more than quoted), minus [reserveRaw] for network fees when the
     * input is the native coin. Null if the bridged funds are not (or no longer) on the chain, or nothing is left
     * after the reserve.
     */
    fun swapAmountRaw(job: BridgeSwapJob, asset: SwapAsset, currentRaw: BigInteger, reserveRaw: BigInteger): BigInteger? {
        val c = job.candidates.firstOrNull { sameAsset(it.asset, asset) } ?: return null
        val delta = currentRaw.subtract(c.preBalanceRaw)
        if (delta < minArrival(job.expectedOutRaw)) return null
        val amount = delta.min(job.expectedOutRaw).subtract(reserveRaw)
        return if (amount.signum() > 0) amount else null
    }

    /** How much worse (positive) or better (negative) the new swap rate is than the preview, in basis points. */
    fun priceDriftBps(previewIn: BigInteger?, previewOut: BigInteger?, newIn: BigInteger, newOut: BigInteger): Long? {
        if (previewIn == null || previewOut == null || previewIn.signum() <= 0 || previewOut.signum() <= 0) return null
        if (newIn.signum() <= 0) return null
        // rate = out / in. worse = (prevRate - newRate) / prevRate, scaled by 10_000, all in integers.
        val prev = previewOut.multiply(newIn)
        val now = newOut.multiply(previewIn)
        return prev.subtract(now).multiply(BigInteger.valueOf(10_000L)).divide(prev).toLong()
    }

    fun driftWarning(driftBps: Long?): String? =
        if (driftBps != null && driftBps > MAX_PRICE_DRIFT_BPS)
            "The price is about ${driftBps / 100.0}% worse than when you started. Cancel if you don't want that."
        else null

    fun statusText(job: BridgeSwapJob, nowSec: Long): String {
        val dest = job.destChain.label
        val mins = maxOf(0L, (nowSec - job.startedAtSec) / 60)
        return when (job.stage) {
            JobStage.WAITING_FILL ->
                if (nowSec - job.startedAtSec > SLOW_AFTER_SECONDS) "Still waiting for your funds on $dest: taking longer than usual ($mins min)."
                else "Waiting for your funds to arrive on $dest ($mins min so far)."
            JobStage.ARRIVED -> "Funds arrived on $dest. Review the swap to ${job.target.symbol}."
            JobStage.SWAPPING -> "Swapping on $dest…"
            JobStage.DONE -> "Done: swapped to ${job.target.symbol} on $dest."
            JobStage.BRIDGE_FAILED ->
                "The bridge did not complete. Across refunds the funds to your ${job.originChain.label} address; this can take hours."
            JobStage.TIMED_OUT -> "No arrival seen after 24 hours. Check the deposit on the explorer, or check again."
        }
    }
}

/** Non-secret persistence of jobs, so a bridge that is still in flight survives the app being killed. */
interface BridgeSwapStore {
    fun list(): List<BridgeSwapJob>
    fun save(job: BridgeSwapJob)
    fun remove(id: String)
}

class InMemoryBridgeSwapStore : BridgeSwapStore {
    private val map = LinkedHashMap<String, BridgeSwapJob>()
    override fun list() = map.values.toList()
    override fun save(job: BridgeSwapJob) { map[job.id] = job }
    override fun remove(id: String) { map.remove(id) }
}

object BridgeSwapJobCodec {
    private fun assetJson(a: SwapAsset) = JSONObject()
        .put("id", a.id ?: JSONObject.NULL).put("symbol", a.symbol).put("decimals", a.decimals).put("curated", a.curated)

    private fun asset(o: JSONObject) = SwapAsset(
        id = if (o.isNull("id")) null else o.getString("id"),
        symbol = o.getString("symbol"), decimals = o.getInt("decimals"), curated = o.optBoolean("curated", true)
    )

    fun toJson(j: BridgeSwapJob): JSONObject = JSONObject()
        .put("id", j.id).put("origin", j.originChain.name).put("dest", j.destChain.name)
        .put("deposit", j.depositTxId).put("started", j.startedAtSec).put("expectedOut", j.expectedOutRaw.toString())
        .put("candidates", JSONArray().also { arr ->
            j.candidates.forEach { arr.put(JSONObject().put("asset", assetJson(it.asset)).put("pre", it.preBalanceRaw.toString())) }
        })
        .put("target", assetJson(j.target))
        .put("previewIn", j.previewInRaw?.toString() ?: JSONObject.NULL)
        .put("previewOut", j.previewOutRaw?.toString() ?: JSONObject.NULL)
        .put("stage", j.stage.name)
        .put("arrived", j.arrivedAsset?.let { assetJson(it) } ?: JSONObject.NULL)
        .put("swapTx", j.swapTxId ?: JSONObject.NULL)

    /** Null for anything malformed: a corrupt record is dropped rather than half-trusted. */
    fun fromJson(o: JSONObject): BridgeSwapJob? = try {
        val cands = o.getJSONArray("candidates")
        BridgeSwapJob(
            id = o.getString("id"),
            originChain = Chain.valueOf(o.getString("origin")),
            destChain = Chain.valueOf(o.getString("dest")),
            depositTxId = o.getString("deposit"),
            startedAtSec = o.getLong("started"),
            expectedOutRaw = BigInteger(o.getString("expectedOut")),
            candidates = (0 until cands.length()).map {
                val c = cands.getJSONObject(it)
                ArrivalCandidate(asset(c.getJSONObject("asset")), BigInteger(c.getString("pre")))
            },
            target = asset(o.getJSONObject("target")),
            previewInRaw = if (o.isNull("previewIn")) null else BigInteger(o.getString("previewIn")),
            previewOutRaw = if (o.isNull("previewOut")) null else BigInteger(o.getString("previewOut")),
            stage = JobStage.valueOf(o.getString("stage")),
            arrivedAsset = if (o.isNull("arrived")) null else asset(o.getJSONObject("arrived")),
            swapTxId = if (o.isNull("swapTx")) null else o.getString("swapTx")
        )
    } catch (e: Exception) {
        null
    }
}

/**
 * Orchestrates polling and stage changes. The network lookups are injected, so tests drive it with fakes.
 * [fillStatus] and [balances] may throw: a failed lookup never advances a job.
 */
class BridgeSwapCoordinator(
    private val store: BridgeSwapStore,
    private val fillStatus: suspend (BridgeSwapJob) -> FillStatus,
    private val balances: suspend (BridgeSwapJob) -> List<BigInteger?>,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    /** Saved jobs, oldest first. */
    fun jobs(): List<BridgeSwapJob> = store.list().sortedBy { it.startedAtSec }

    /**
     * Call once when a session starts. A job that was mid-swap when the app died goes back to ARRIVED: the swap leg
     * re-reads the balance before quoting, so it cannot swap funds that are already gone.
     */
    fun recoverInterrupted(): List<BridgeSwapJob> {
        store.list().filter { it.stage == JobStage.SWAPPING }.forEach { store.save(it.copy(stage = JobStage.ARRIVED)) }
        return jobs()
    }

    fun start(job: BridgeSwapJob) = store.save(job)

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** One poll. Returns the (possibly advanced) job, already saved. Jobs past the waiting stages are returned as-is. */
    suspend fun poll(job: BridgeSwapJob): BridgeSwapJob {
        if (job.stage != JobStage.WAITING_FILL && job.stage != JobStage.TIMED_OUT) return job
        val status = attempt { fillStatus(job) } ?: FillStatus.UNKNOWN
        val arrived = if (status == FillStatus.FILLED || status == FillStatus.UNKNOWN) {
            attempt { balances(job) }?.let { BridgeSwapLogic.arrivedAsset(job, it) }
        } else null
        val stage = BridgeSwapLogic.nextStage(job, status, arrived, nowSec())
        if (stage == job.stage) return job
        return job.copy(stage = stage, arrivedAsset = if (stage == JobStage.ARRIVED) arrived else job.arrivedAsset).also(store::save)
    }

    fun markSwapping(job: BridgeSwapJob): BridgeSwapJob = job.copy(stage = JobStage.SWAPPING).also(store::save)
    fun markDone(job: BridgeSwapJob, swapTxId: String): BridgeSwapJob =
        job.copy(stage = JobStage.DONE, swapTxId = swapTxId).also(store::save)
    fun backToArrived(job: BridgeSwapJob): BridgeSwapJob = job.copy(stage = JobStage.ARRIVED).also(store::save)
    fun dismiss(job: BridgeSwapJob) = store.remove(job.id)
}
