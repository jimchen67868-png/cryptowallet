package com.example.wallet.chain

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Native cross-chain swaps through THORChain, used for Litecoin and Tron (neither is covered by LI.FI or ParaSwap).
 *
 * How it works: the wallet sends the source coin to THORChain's current inbound vault, with a memo in the same
 * transaction (OP_RETURN on Litecoin, the `data` field on Tron) that names the output asset and OUR OWN address on the
 * destination chain. THORChain then pays out natively; there are no wrapped coins.
 *
 * Trust model, and why it is built this way: the vault address and the memo decide where the funds go, and both come
 * from a web API. So:
 *  - the memo is built here, never taken from the API (the API's memo is only compared against ours);
 *  - the vault address must be identical on at least two independently operated THORChain nodes ([ThorQuorum]);
 *  - paused/halted chains, expired quotes, amounts below the quote's minimum and unusually high fees are refused;
 *  - the output has a minimum ([ThorGuard.SLIPPAGE_BPS]); if the price moves further THORChain refunds the input.
 * Mainnet only: THORChain has no public testnet that matches Litecoin/Tron testnets.
 */
object ThorAssets {
    private val assets = mapOf(
        Chain.BTC to "BTC.BTC", Chain.LTC to "LTC.LTC", Chain.ETH to "ETH.ETH", Chain.BASE to "BASE.ETH", Chain.TRX to "TRON.TRX"
    )
    private val vaultChains = mapOf(
        Chain.BTC to "BTC", Chain.LTC to "LTC", Chain.ETH to "ETH", Chain.BASE to "BASE", Chain.TRX to "TRON"
    )

    /** What each source can deliver to. Native coins only; EVM outputs go to the wallet's own EVM address. */
    val DESTINATIONS: Map<Chain, List<Chain>> = mapOf(
        Chain.LTC to listOf(Chain.BTC, Chain.ETH, Chain.BASE, Chain.TRX),
        Chain.TRX to listOf(Chain.LTC, Chain.BTC, Chain.ETH, Chain.BASE)
    )

    fun asset(chain: Chain): String? = assets[chain]
    fun vaultChain(chain: Chain): String? = vaultChains[chain]
}

/** THORChain swap memo: `=:ASSET:DESTINATION:MIN_OUT` (all amounts in THORChain's 1e8 units). */
object ThorMemo {
    /** Bitcoin-family OP_RETURN limit. Longer memos are not relayed or are read wrongly: always refused. */
    const val MAX_BYTES = 80
    private val ASSET = Regex("[A-Z]{2,10}\\.[A-Z]{2,10}")

    fun swap(asset: String, destination: String, minOut: BigInteger): String {
        require(ASSET.matches(asset)) { "Bad asset name" }
        require(destination.isNotEmpty() && destination.none { it == ':' || it.isWhitespace() }) { "Bad destination address" }
        require(minOut.signum() > 0) { "Minimum output must be positive" }
        val memo = "=:$asset:$destination:$minOut"
        require(memo.toByteArray(Charsets.US_ASCII).size <= MAX_BYTES && memo.all { it.code in 0x20..0x7e }) { "Swap memo is too long" }
        return memo
    }

    /** The destination field of a swap memo, or null. */
    fun destination(memo: String): String? = memo.split(":").getOrNull(2)?.takeIf { it.isNotEmpty() }
}

class ThorInbound(
    val chain: String,
    val address: String,
    val halted: Boolean,
    val tradingPaused: Boolean
)

/** The parts of `/thorchain/quote/swap` that this wallet uses. Amounts are THORChain 1e8 units. */
class ThorQuoteData(
    val inboundAddress: String,
    val memo: String?,
    val expiry: Long,
    val expectedOut: BigInteger,
    val feesTotal: BigInteger,
    val minIn: BigInteger,
    val etaSeconds: Long
)

object ThorParse {
    /** Throws with the node's own message if the response is an error. */
    fun quote(j: JSONObject): ThorQuoteData {
        if (j.has("error") || (j.has("message") && !j.has("expected_amount_out"))) {
            throw IllegalStateException(j.optString("message").ifBlank { j.optString("error", "THORChain declined the quote") })
        }
        fun big(name: String): BigInteger = j.optString(name, "").toBigIntegerOrNull() ?: BigInteger.ZERO
        val fees = j.optJSONObject("fees")
        return ThorQuoteData(
            inboundAddress = j.optString("inbound_address", ""),
            memo = j.optString("memo", "").ifEmpty { null },
            expiry = j.optLong("expiry", 0L),
            expectedOut = big("expected_amount_out"),
            feesTotal = fees?.optString("total", "")?.toBigIntegerOrNull() ?: BigInteger.ZERO,
            minIn = big("recommended_min_amount_in"),
            etaSeconds = j.optLong("total_swap_seconds", 0L)
        )
    }

    fun inbound(arr: JSONArray, chainCode: String): ThorInbound? {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("chain") != chainCode) continue
            return ThorInbound(
                chain = chainCode,
                address = o.optString("address", ""),
                halted = o.optBoolean("halted", false),
                tradingPaused = o.optBoolean("chain_trading_paused", false) || o.optBoolean("global_trading_paused", false)
            )
        }
        return null
    }
}

/** The inbound vault must be the same on every node that answers, and at least two must answer. */
object ThorQuorum {
    const val MIN_NODES = 2

    /** The agreed inbound, or an error message. `null` entries are nodes that did not answer. */
    fun agree(responses: List<ThorInbound?>): Pair<ThorInbound?, String?> {
        val got = responses.filterNotNull()
        if (got.size < MIN_NODES) return null to "Could not confirm the THORChain vault with $MIN_NODES independent nodes. Nothing was sent."
        if (got.any { it.address.isEmpty() }) return null to "THORChain has no inbound address for this network right now."
        if (got.map { it.address }.toSet().size != 1) return null to "THORChain nodes disagree about the vault address. Nothing was sent."
        if (got.any { it.halted || it.tradingPaused }) return null to "THORChain has paused this network right now."
        return got.first() to null
    }
}

object ThorGuard {
    const val SLIPPAGE_BPS = 100L          // refund instead of accepting an output more than 1% below the quote
    const val MAX_FEE_PERCENT = 5L
    const val MIN_SECONDS_LEFT = 90L

    /** Null if the quote may be used, otherwise the reason it may not. */
    fun validate(q: ThorQuoteData, vault: String, destAddress: String, amountIn: BigInteger, nowSec: Long): String? {
        if (q.inboundAddress != vault) return "THORChain's quote names a different vault than the nodes agreed on"
        val memoDest = q.memo?.let { ThorMemo.destination(it) }
        if (q.memo != null && memoDest != destAddress) return "THORChain's quote has a different destination address than yours"
        if (q.expiry - nowSec < MIN_SECONDS_LEFT) return "The quote is about to expire. Get a new one."
        if (q.expectedOut.signum() <= 0) return "This amount is too small to swap after THORChain's fees"
        if (amountIn < q.minIn) return "Below THORChain's minimum for this route (${BigDecimal(q.minIn).movePointLeft(8).stripTrailingZeros().toPlainString()}). Try a larger amount."
        val gross = q.expectedOut + q.feesTotal
        if (q.feesTotal.multiply(BigInteger.valueOf(100)) > gross.multiply(BigInteger.valueOf(MAX_FEE_PERCENT))) {
            return "THORChain's fees are over $MAX_FEE_PERCENT% of this swap. Try a larger amount."
        }
        return null
    }

    fun minOut(expectedOut: BigInteger): BigInteger =
        expectedOut.multiply(BigInteger.valueOf(10_000 - SLIPPAGE_BPS)).divide(BigInteger.valueOf(10_000))
}

class ThorPlan(
    val from: Chain,
    val dest: Chain,
    val vault: String,
    val memo: String,
    val amountIn: BigInteger,      // 1e8 units
    val expectedOut: BigInteger,   // 1e8 units
    val minOut: BigInteger,        // 1e8 units
    val feesTotal: BigInteger,     // 1e8 units, in the output asset
    val expirySec: Long,
    val etaSeconds: Long,
    val destAddress: String
)

/** THORNode REST API (keyless). Several independent endpoints so that no single one can choose the vault. */
open class ThorApi(
    private val nodes: List<String> = DEFAULT_NODES
) {
    open fun inbound(vaultChain: String): Pair<ThorInbound?, String?> {
        val answers = nodes.map { node ->
            runCatching { ThorParse.inbound(JSONArray(Http.get("$node/thorchain/inbound_addresses")), vaultChain) }.getOrNull()
        }
        return ThorQuorum.agree(answers)
    }

    open fun quote(from: String, to: String, amount: BigInteger, destination: String): ThorQuoteData {
        var last: Exception? = null
        for (node in nodes) {
            try {
                val url = "$node/thorchain/quote/swap?from_asset=$from&to_asset=$to&amount=$amount&destination=$destination"
                return ThorParse.quote(JSONObject(Http.get(url)))
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException(last?.message ?: "THORChain did not answer")
    }

    companion object {
        /** Public THORNode endpoints seen in THORChain documentation; the quorum rule works with any two that answer. */
        val DEFAULT_NODES = listOf(
            "https://thornode.ninerealms.com",
            "https://thornode.thorchain.info",
            "https://public-thornode.nativeswap.io"
        )
    }
}

class ThorSwaps(
    private val api: ThorApi = ThorApi(),
    private val clockSec: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    fun destinationsFrom(chain: Chain): List<Chain> = ThorAssets.DESTINATIONS[chain].orEmpty()

    /** Blocking (network). Throws with a user-facing message when the swap can't be made safely. */
    fun plan(from: Chain, dest: Chain, destAddress: String, amountIn: BigInteger): ThorPlan {
        require(dest in destinationsFrom(from)) { "${from.label} can't be swapped to ${dest.label} here" }
        val fromAsset = ThorAssets.asset(from) ?: throw IllegalArgumentException("Unsupported network")
        val toAsset = ThorAssets.asset(dest) ?: throw IllegalArgumentException("Unsupported network")
        val vaultChain = ThorAssets.vaultChain(from) ?: throw IllegalArgumentException("Unsupported network")
        val (inbound, err) = api.inbound(vaultChain)
        if (inbound == null) throw IllegalStateException(err ?: "THORChain is not available")
        val q = api.quote(fromAsset, toAsset, amountIn, destAddress)
        ThorGuard.validate(q, inbound.address, destAddress, amountIn, clockSec())?.let { throw IllegalStateException(it) }
        val minOut = ThorGuard.minOut(q.expectedOut)
        val memo = ThorMemo.swap(toAsset, destAddress, minOut) // built here; the API's memo was only compared
        return ThorPlan(from, dest, inbound.address, memo, amountIn, q.expectedOut, minOut, q.feesTotal, q.expiry, q.etaSeconds, destAddress)
    }

    /** Re-checks, right before signing, that the vault is still the agreed one and the plan has not expired. */
    fun recheck(plan: ThorPlan) {
        if (plan.expirySec - clockSec() < 30) throw IllegalStateException("The quote expired. Get a new one.")
        val vaultChain = ThorAssets.vaultChain(plan.from) ?: throw IllegalStateException("Unsupported network")
        val (inbound, err) = api.inbound(vaultChain)
        if (inbound == null) throw IllegalStateException(err ?: "THORChain is not available")
        if (inbound.address != plan.vault) throw IllegalStateException("THORChain's vault changed since the quote. Get a new quote.")
    }

    fun toBridgeQuote(plan: ThorPlan, from: SwapAsset, minerFeeRaw: BigInteger, nativeDecimals: Int): BridgeQuote {
        // THORChain reports fees in the OUTPUT asset: express them as a share of the input for display.
        val gross = plan.expectedOut + plan.feesTotal
        val feeIn = if (gross.signum() == 0) BigInteger.ZERO else plan.amountIn.multiply(plan.feesTotal).divide(gross)
        return BridgeQuote(
            chain = plan.from, destChain = plan.dest, from = from,
            amountInRaw = plan.amountIn, amountOutRaw = plan.expectedOut, feeRaw = feeIn, decimals = 8,
            outSymbol = plan.dest.symbol, etaSeconds = plan.etaSeconds, needsApproval = false,
            networkFeeRaw = minerFeeRaw, networkFeeSymbol = plan.from.symbol, networkFeeDecimals = nativeDecimals,
            payload = plan, destAddress = plan.destAddress,
            note = "Swapped by THORChain, a decentralised exchange network. The wallet sends your ${plan.from.symbol} to THORChain's " +
                "current vault with a memo naming YOUR ${plan.dest.label} address; the vault address was confirmed by " +
                "${ThorQuorum.MIN_NODES} independent nodes. Usually takes 5 to 60 minutes. If the price moves more than " +
                "${ThorGuard.SLIPPAGE_BPS / 100}% before it is processed, or the swap fails, THORChain refunds the ${plan.from.symbol} " +
                "to you minus fees. This cannot be undone."
        )
    }

    companion object {
        const val PROVIDER_NOTE = "Swaps between Litecoin, Tron, Bitcoin, Ethereum and Base go through THORChain (a decentralised " +
            "exchange network, third party). Output arrives as the native coin on the destination network, at your own address. " +
            "The wallet builds the memo itself and checks the vault address against several THORChain nodes. Fees (including " +
            "THORChain's outbound fee, which can be large for small amounts) come out of the output. Start with a small amount."
    }
}
