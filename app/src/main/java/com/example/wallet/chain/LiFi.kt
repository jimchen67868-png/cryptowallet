package com.example.wallet.chain

import org.json.JSONObject
import java.math.BigInteger

/**
 * Bridging through LI.FI's keyless REST API (https://li.quest/v1), routed over Across.
 *
 * LI.FI returns a ready-made transaction for its "Diamond" contract. Unlike our own depositV3 builder this is calldata we
 * did not write, so nothing is signed until [LiFiGuard] has decoded it and checked the receiver, token, amounts, fee step
 * and deadlines against what the user asked for. Anything it does not recognise is refused, never guessed at.
 */

/** A quote from GET /v1/quote, reduced to the fields the wallet reads. */
class LiFiQuote(
    val tool: String,
    val fromChainId: Long,
    val toChainId: Long,
    val fromToken: String,
    val toToken: String,
    val fromAmount: BigInteger,
    val toAmount: BigInteger,
    val toAmountMin: BigInteger,
    val approvalAddress: String,
    val executionSeconds: Long,
    val txTo: String,
    val txData: String,
    val txValue: BigInteger,
    val txFrom: String,
    val txChainId: Long
)

/** What the user asked for. The guard compares the quote against this, never against itself. */
class LiFiRequest(
    val user: String,
    val fromChainId: Long,
    val toChainId: Long,
    /** Source token address, or null for the native coin (then [toToken] must be the zero address too). */
    val fromToken: String?,
    val toToken: String,
    val amount: BigInteger,
    /** Decimals of the source and destination token (they differ for USDC/USDT on BNB Chain: 18, versus 6 elsewhere). */
    val fromDecimals: Int = 18,
    val toDecimals: Int = 18
)

object LiFiQuoteParser {
    private fun hex(s: String?, name: String): BigInteger {
        val t = (s ?: throw IllegalArgumentException("LI.FI quote is missing $name")).removePrefix("0x")
        return if (t.isEmpty()) BigInteger.ZERO else BigInteger(t, 16)
    }

    private fun dec(s: String?, name: String): BigInteger {
        if (s.isNullOrBlank()) throw IllegalArgumentException("LI.FI quote is missing $name")
        return BigInteger(s)
    }

    /** Throws IllegalStateException with LI.FI's own message for an error response, IllegalArgumentException if malformed. */
    fun parse(body: String): LiFiQuote {
        val j = try { JSONObject(body) } catch (e: Exception) { throw IllegalArgumentException("LI.FI returned something that is not JSON") }
        val tx = j.optJSONObject("transactionRequest")
        if (tx == null) {
            val message = j.optString("message")
            throw IllegalStateException(if (message.isNotBlank()) "LI.FI: ${message.take(200)}" else "LI.FI returned no transaction for this transfer")
        }
        val action = j.optJSONObject("action") ?: throw IllegalArgumentException("LI.FI quote is missing action")
        val estimate = j.optJSONObject("estimate") ?: throw IllegalArgumentException("LI.FI quote is missing estimate")
        val from = action.optJSONObject("fromToken") ?: throw IllegalArgumentException("LI.FI quote is missing fromToken")
        val to = action.optJSONObject("toToken") ?: throw IllegalArgumentException("LI.FI quote is missing toToken")
        return LiFiQuote(
            tool = j.optString("tool"),
            fromChainId = action.getLong("fromChainId"),
            toChainId = action.getLong("toChainId"),
            fromToken = from.getString("address"),
            toToken = to.getString("address"),
            fromAmount = dec(action.optString("fromAmount"), "fromAmount"),
            toAmount = dec(estimate.optString("toAmount"), "toAmount"),
            toAmountMin = dec(estimate.optString("toAmountMin"), "toAmountMin"),
            approvalAddress = estimate.optString("approvalAddress"),
            executionSeconds = estimate.optLong("executionDuration", 0L),
            txTo = tx.optString("to"),
            txData = tx.optString("data"),
            txValue = hex(tx.optString("value", "0x0"), "value"),
            txFrom = tx.optString("from"),
            txChainId = tx.getLong("chainId")
        )
    }
}

/**
 * LI.FI contracts we have verified against a real quote (tools/capture_lifi.sh). A chain not listed here is refused:
 * adding one means capturing a quote from it, decoding it (tools/verify/lifi_decode.py) and checking the addresses.
 *
 * - diamond: where the transaction is sent and what gets the token approval. LI.FI's docs give 0x1231DEB6… for
 *   Ethereum, Base and Arbitrum ("some chains use a different deployment").
 * - feeContract / feeRecipient: the fee-collection step LI.FI adds (its 0.25% service fee): the FeeForwarder contract and
 *   the wallet it forwards to. Chains without FeeForwarder use a legacy contract, so these differ per chain.
 */
object LiFiPins {
    class Pin(val chainId: Long, val diamond: String, val feeContract: String, val feeRecipient: String)

    const val DIAMOND = "0x1231DEB6f5749EF6cE6943a275A1D3E7486F4EaE"

    private const val FEE_CONTRACT = "0xDe2b920506EE931a6A2ABDF566526220214FBbAE"
    private const val FEE_RECIPIENT = "0xC06ebbefD94032B85424D51906e2A335EFAe264B"
    const val ZERO = "0x0000000000000000000000000000000000000000"

    val PINNED: Map<Long, Pin> = mapOf(
        // Arbitrum: both fee addresses observed in a real quote (USDC, Arbitrum -> Base, 2026-11-18).
        42161L to Pin(42161L, DIAMOND, FEE_CONTRACT, FEE_RECIPIENT),
        // Observed in real quotes on 2026-11-23 (tools/capture_lifi_all.sh): the same Diamond, fee contract and fee recipient
        // appear on every one of these networks (USDC -> Arbitrum from each; BNB Chain also USDC <- Arbitrum).
        1L to Pin(1L, DIAMOND, FEE_CONTRACT, FEE_RECIPIENT),
        8453L to Pin(8453L, DIAMOND, FEE_CONTRACT, FEE_RECIPIENT),
        137L to Pin(137L, DIAMOND, FEE_CONTRACT, FEE_RECIPIENT),
        56L to Pin(56L, DIAMOND, FEE_CONTRACT, FEE_RECIPIENT)
    )

    /**
     * Native ETH is bridged as WETH inside the Across deposit. Only networks whose native coin is ETH and for which a real
     * native quote was captured: Ethereum, Base, Arbitrum. (Polygon native gets no quote; BNB native is routed over another bridge.)
     */
    val WRAPPED_NATIVE: Map<Long, String> = mapOf(
        1L to "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2",
        8453L to "0x4200000000000000000000000000000000000006",
        42161L to "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1"
    )

    fun forChain(chainId: Long): Pin? = PINNED[chainId]
}

/** Decodes the one LI.FI facet call we accept: swapAndStartBridgeTokensViaAcrossV4(bridgeData, swapData[], acrossData). */
internal object LiFiCalldata {
    const val SELECTOR = "1794958f"
    private const val MAX_BYTES = 16 * 1024
    private const val MAX_SWAPS = 4
    private const val MAX_STRING = 64
    private const val MAX_CALLDATA = 1024
    /** BridgeData has 10 head words; AcrossV4Data has 11 head words and then the (empty) message. */
    private const val ACROSS_HEAD_WORDS = 11

    class BridgeData(
        val referrer: String, val sendingAssetId: String, val receiver: String, val minAmount: BigInteger,
        val destinationChainId: BigInteger, val hasSourceSwaps: Boolean, val hasDestinationCall: Boolean,
        val bridge: String, val integrator: String
    )

    class Swap(
        val callTo: String, val approveTo: String, val sendingAssetId: String, val receivingAssetId: String,
        val fromAmount: BigInteger, val requiresDeposit: Boolean, val callData: String
    )

    class FeeCall(val token: String, val recipients: List<Pair<String, BigInteger>>)

    class Decoded(val selector: String, val bridge: BridgeData, val swaps: List<Swap>, val across: List<BigInteger>, val messageLength: BigInteger)

    /** Null if the data is not exactly the expected shape. */
    fun decode(data: String): Decoded? = try { decodeOrThrow(data) } catch (e: Exception) { null }

    private fun decodeOrThrow(data: String): Decoded {
        require(data.startsWith("0x"))
        val hex = data.substring(2).lowercase()
        require(hex.length % 2 == 0 && hex.length / 2 <= MAX_BYTES && hex.all { it in '0'..'9' || it in 'a'..'f' })
        val selector = hex.substring(0, 8)
        require(selector == SELECTOR) { "unknown selector" }
        val body = hex.substring(8)
        require(body.length % 64 == 0)
        val n = body.length / 64

        fun w(i: Int): BigInteger { require(i in 0 until n); return BigInteger(body.substring(i * 64, (i + 1) * 64), 16) }
        fun idx(byteOffset: BigInteger): Int {
            require(byteOffset.signum() >= 0 && byteOffset.bitLength() < 20 && byteOffset.mod(BigInteger.valueOf(32)).signum() == 0)
            return byteOffset.toInt() / 32
        }
        fun addr(v: BigInteger): String { require(v.bitLength() <= 160); return "0x" + v.toString(16).padStart(40, '0') }
        fun bool(v: BigInteger): Boolean { require(v == BigInteger.ZERO || v == BigInteger.ONE); return v == BigInteger.ONE }
        fun bytesAt(lengthWord: Int, max: Int): String {
            val len = w(lengthWord)
            require(len.bitLength() < 16 && len.toInt() <= max)
            val start = (lengthWord + 1) * 64
            val end = start + len.toInt() * 2
            require(end <= body.length)
            return body.substring(start, end)
        }
        fun ascii(h: String): String {
            val bytes = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            require(bytes.all { it in 0x20..0x7e })
            return String(bytes, Charsets.US_ASCII)
        }

        val bOff = idx(w(0))
        val sOff = idx(w(1))
        val aOff = idx(w(2))
        require(bOff == 3 && sOff > bOff && aOff > sOff)

        val bridge = BridgeData(
            referrer = addr(w(bOff + 3)), sendingAssetId = addr(w(bOff + 4)), receiver = addr(w(bOff + 5)),
            minAmount = w(bOff + 6), destinationChainId = w(bOff + 7),
            hasSourceSwaps = bool(w(bOff + 8)), hasDestinationCall = bool(w(bOff + 9)),
            bridge = ascii(bytesAt(bOff + idx(w(bOff + 1)), MAX_STRING)),
            integrator = ascii(bytesAt(bOff + idx(w(bOff + 2)), MAX_STRING))
        )

        val count = w(sOff)
        require(count.bitLength() < 8 && count.toInt() <= MAX_SWAPS)
        val swaps = (0 until count.toInt()).map { i ->
            // Array element offsets are relative to the start of the array content, just after the length word.
            val e = sOff + 1 + idx(w(sOff + 1 + i))
            Swap(
                callTo = addr(w(e)), approveTo = addr(w(e + 1)), sendingAssetId = addr(w(e + 2)), receivingAssetId = addr(w(e + 3)),
                fromAmount = w(e + 4), requiresDeposit = bool(w(e + 6)),
                callData = bytesAt(e + idx(w(e + 5)), MAX_CALLDATA)
            )
        }

        val across = (0 until ACROSS_HEAD_WORDS).map { w(aOff + it) }
        val messageWord = aOff + idx(across[ACROSS_HEAD_WORDS - 1])
        require(messageWord == aOff + ACROSS_HEAD_WORDS) { "unexpected message offset" }
        val messageLength = w(messageWord)
        // Nothing may follow the empty message: the calldata ends exactly here.
        require(messageLength == BigInteger.ZERO && n == messageWord + 1) { "trailing or non-empty data" }
        return Decoded(selector, bridge, swaps, across, messageLength)
    }

    /** The fee step's call: 0x332d746b(address token, (address recipient, uint256 amount)[]) with exactly one recipient. */
    fun decodeFeeCall(callData: String): FeeCall? = try {
        require(callData.length == 8 + 5 * 64 && callData.startsWith(FEE_SELECTOR))
        val ww = (0 until 5).map { BigInteger(callData.substring(8 + it * 64, 8 + (it + 1) * 64), 16) }
        require(ww[1] == BigInteger.valueOf(0x40) && ww[2] == BigInteger.ONE)
        require(ww[0].bitLength() <= 160 && ww[3].bitLength() <= 160)
        FeeCall("0x" + ww[0].toString(16).padStart(40, '0'), listOf("0x" + ww[3].toString(16).padStart(40, '0') to ww[4]))
    } catch (e: Exception) {
        null
    }

    /** The native-coin fee step: 0x0e8ae67f((address recipient, uint256 amount)[]) with exactly one recipient. */
    fun decodeNativeFeeCall(callData: String): FeeCall? = try {
        require(callData.length == 8 + 4 * 64 && callData.startsWith(NATIVE_FEE_SELECTOR))
        val ww = (0 until 4).map { BigInteger(callData.substring(8 + it * 64, 8 + (it + 1) * 64), 16) }
        require(ww[0] == BigInteger.valueOf(0x20) && ww[1] == BigInteger.ONE && ww[2].bitLength() <= 160)
        FeeCall(LiFiPins.ZERO, listOf("0x" + ww[2].toString(16).padStart(40, '0') to ww[3]))
    } catch (e: Exception) {
        null
    }

    const val FEE_SELECTOR = "332d746b"
    const val NATIVE_FEE_SELECTOR = "0e8ae67f"
}

/** Fails closed: returns a user-facing reason to refuse, or null if the quote is exactly what the user asked for. */
object LiFiGuard {
    const val NATIVE_UNSUPPORTED =
        "Bridging the native coin through LI.FI isn't enabled yet. Bridge a token such as USDC or WETH instead."

    /** LI.FI's own service fee is 0.25%; refuse a fee step that takes more than 1%. */
    const val MAX_SERVICE_FEE_BPS = 100L
    /** Refuse if the whole trip (LI.FI fee + bridge fee) costs more than this share. Small amounts carry a fixed bridge cost. */
    const val MAX_TOTAL_FEE_PERCENT = 5L
    const val MAX_CLOCK_SKEW_SECONDS = 900L
    const val MAX_DEADLINE_SECONDS = 86_400L
    /** Across's outputAmountPercent is a fraction of 1e18: it must stay between 90% and 100%. */
    private val ONE_E18 = BigInteger.TEN.pow(18)
    private val MIN_OUTPUT_PERCENT = ONE_E18.multiply(BigInteger.valueOf(90)).divide(BigInteger.valueOf(100))

    private fun same(a: String?, b: String?) = a != null && b != null && a.equals(b, ignoreCase = true)
    private fun addrOf(v: BigInteger) = "0x" + v.toString(16).padStart(40, '0')

    fun validate(q: LiFiQuote, r: LiFiRequest, nowSec: Long): String? {
        val native = r.fromToken == null
        val fromToken = r.fromToken ?: LiFiPins.ZERO
        val wrappedIn = if (native) LiFiPins.WRAPPED_NATIVE[r.fromChainId] else null
        val wrappedOut = if (native) LiFiPins.WRAPPED_NATIVE[r.toChainId] else null
        if (native && (wrappedIn == null || wrappedOut == null)) return NATIVE_UNSUPPORTED
        if (native && !same(r.toToken, LiFiPins.ZERO)) return "A native-coin bridge must ask for the native coin on the destination too"
        val pin = LiFiPins.forChain(r.fromChainId) ?: return "Bridging from this network through LI.FI isn't enabled in this build yet."

        // What was asked for.
        if (q.tool != "across") return "LI.FI chose a bridge this wallet doesn't support (${q.tool.take(20)})"
        if (q.fromChainId != r.fromChainId || q.toChainId != r.toChainId) return "The quote is for a different route than requested"
        if (!same(q.fromToken, fromToken) || !same(q.toToken, r.toToken)) return "The quote uses different tokens than requested"
        if (q.fromAmount != r.amount) return "The quote is for a different amount than requested"

        // Where the transaction goes and who sends it.
        if (!same(q.txTo, pin.diamond)) return "Unexpected LI.FI contract in the quote"
        if (!same(q.approvalAddress, pin.diamond)) return "Unexpected approval address in the quote"
        if (q.txChainId != r.fromChainId) return "The quote's transaction is for a different network"
        if (!same(q.txFrom, r.user)) return "The quote's transaction is for a different sender"
        if (native) {
            if (q.txValue != r.amount) return "The quote's transaction value doesn't match the amount"
        } else if (q.txValue.signum() != 0) {
            return "The quote asks to send native coin with a token transfer"
        }

        // The calldata itself.
        val d = LiFiCalldata.decode(q.txData) ?: return "The bridge transaction has an unrecognised format"
        val b = d.bridge
        if (b.bridge != "across") return "The bridge transaction does not use Across"
        if (!same(b.receiver, r.user)) return "The bridge transaction would send the funds to a different address"
        if (!same(b.sendingAssetId, fromToken)) return "The bridge transaction uses a different token"
        if (b.destinationChainId != BigInteger.valueOf(r.toChainId)) return "The bridge transaction goes to a different network"
        if (b.hasDestinationCall) return "The bridge transaction includes a destination call"
        if (b.referrer != "0x0000000000000000000000000000000000000000") return "The bridge transaction has an unexpected referrer"

        // The fee step: LI.FI's service fee, taken from the amount before bridging.
        var fee = BigInteger.ZERO
        if (b.hasSourceSwaps) {
            if (d.swaps.size != 1) return "The bridge transaction has unexpected extra steps"
            val s = d.swaps.single()
            if (!same(s.callTo, pin.feeContract) || !same(s.approveTo, pin.feeContract)) return "The bridge transaction calls an unknown fee contract"
            if (!same(s.sendingAssetId, fromToken) || !same(s.receivingAssetId, fromToken)) return "The fee step changes the token"
            if (s.fromAmount != r.amount) return "The fee step uses a different amount"
            val call = (if (native) LiFiCalldata.decodeNativeFeeCall(s.callData) else LiFiCalldata.decodeFeeCall(s.callData))
                ?: return "The fee step has an unrecognised format"
            if (!same(call.token, fromToken)) return "The fee step uses a different token"
            val (recipient, amount) = call.recipients.single()
            if (!same(recipient, pin.feeRecipient)) return "The fee would go to an unknown address"
            fee = amount
            if (fee.multiply(BigInteger.valueOf(10_000)) > r.amount.multiply(BigInteger.valueOf(MAX_SERVICE_FEE_BPS))) {
                return "LI.FI's fee in this quote is unusually high"
            }
        } else if (d.swaps.isNotEmpty()) {
            return "The bridge transaction has unexpected extra steps"
        }
        // Exactly what remains after the fee is what gets bridged.
        if (b.minAmount != r.amount.subtract(fee)) return "The bridged amount doesn't match the amount minus fees"

        // The Across deposit parameters (11 head words of AcrossV4Data).
        val a = d.across
        if (!same(addrOf(a[0]), r.user) || !same(addrOf(a[1]), r.user)) return "The deposit would use a different recipient or refund address"
        // A native-coin trip is deposited as WETH on the origin and paid out as WETH on the destination (the receiver gets ETH).
        if (!same(addrOf(a[2]), wrappedIn ?: fromToken)) return "The deposit uses a different input token"
        if (!same(addrOf(a[3]), wrappedOut ?: r.toToken)) return "The deposit pays out a different token"
        if (a[4] != q.toAmount) return "The deposit output doesn't match the quote"
        if (a[5].signum() <= 0 || a[5] > ONE_E18 || a[5] < MIN_OUTPUT_PERCENT) return "The deposit output percentage is out of range"
        if (a[6].bitLength() > 160) return "Invalid relayer address in the deposit"
        val timestamp = a[7]
        val fillDeadline = a[8]
        val exclusivity = a[9]
        if (timestamp.bitLength() > 32 || fillDeadline.bitLength() > 32 || exclusivity.bitLength() > 32) return "Invalid timestamps in the deposit"
        if (Math.abs(timestamp.toLong() - nowSec) > MAX_CLOCK_SKEW_SECONDS) return "The quote timestamp is out of range"
        if (fillDeadline.toLong() <= nowSec || fillDeadline.toLong() > nowSec + MAX_DEADLINE_SECONDS) return "The quote deadline is out of range"
        if (exclusivity.toLong() > nowSec + MAX_DEADLINE_SECONDS) return "Invalid exclusivity window in the deposit"

        // Money: the user must get at least 95% of what they put in, and never more than they put in.
        // Amounts are compared in a common scale because the two tokens can have different decimals (BNB Chain USDC: 18, others: 6).
        val sIn = BigInteger.TEN.pow(r.fromDecimals)
        val sOut = BigInteger.TEN.pow(r.toDecimals)
        if (q.toAmount.signum() <= 0 || q.toAmount.multiply(sIn) > b.minAmount.multiply(sOut)) return "The quoted output is inconsistent"
        if (q.toAmountMin.signum() <= 0 || q.toAmountMin > q.toAmount) return "The quoted minimum output is inconsistent"
        if (q.toAmountMin.multiply(sIn).multiply(BigInteger.valueOf(100)) <
            r.amount.multiply(BigInteger.valueOf(100 - MAX_TOTAL_FEE_PERCENT)).multiply(sOut)
        ) return "The fees are over $MAX_TOTAL_FEE_PERCENT% of the amount. Try a larger amount."
        return null
    }
}

/** Maps LI.FI's /v1/status to our FillStatus. Anything unrecognised is UNKNOWN, never FILLED. */
internal object LiFiStatus {
    fun parse(body: String): FillStatus = try {
        val j = JSONObject(body)
        val sub = j.optString("substatus").uppercase()
        when (j.optString("status").uppercase()) {
            "DONE" -> when (sub) {
                "COMPLETED" -> FillStatus.FILLED
                "REFUNDED" -> FillStatus.REFUNDED
                else -> FillStatus.UNKNOWN // e.g. PARTIAL: the balance check decides
            }
            "PENDING" -> FillStatus.PENDING
            "FAILED" -> FillStatus.EXPIRED
            else -> FillStatus.UNKNOWN // NOT_FOUND (not indexed yet), INVALID, or something new
        }
    } catch (e: Exception) {
        FillStatus.UNKNOWN
    }
}

/** Keyless client for LI.FI's REST API. Rate limit without a key: 75 quotes per 2 hours per IP. */
internal class LiFiApi(
    private val baseUrl: String = "https://li.quest/v1",
    private val transport: (url: String) -> HttpResult = { url -> Http.exchange(url, "GET", null, null, mapOf("Accept" to "application/json")) }
) {
    private val address = Regex("0x[0-9a-fA-F]{40}")

    fun quote(r: LiFiRequest): LiFiQuote {
        val fromToken = r.fromToken ?: LiFiPins.ZERO
        require(address.matches(r.user) && address.matches(fromToken) && address.matches(r.toToken)) { "Invalid address in the bridge request" }
        require(r.amount.signum() > 0) { "Invalid amount" }
        // allowBridges asks LI.FI for Across routes only: the guard accepts nothing else.
        val url = "$baseUrl/quote?fromChain=${r.fromChainId}&toChain=${r.toChainId}&fromToken=$fromToken&toToken=${r.toToken}" +
            "&fromAmount=${r.amount}&fromAddress=${r.user}&toAddress=${r.user}&allowBridges=across"
        val res = transport(url)
        if (res.status !in 200..299) {
            val message = try { JSONObject(res.body).optString("message") } catch (e: Exception) { "" }
            throw IllegalStateException("LI.FI: " + message.ifBlank { "HTTP ${res.status}" }.take(200))
        }
        return LiFiQuoteParser.parse(res.body)
    }

    fun status(txHash: String, fromChainId: Long): FillStatus {
        if (!BridgeSwapLogic.TX_ID.matches(txHash)) return FillStatus.UNKNOWN
        val res = transport("$baseUrl/status?txHash=$txHash&fromChain=$fromChainId")
        return if (res.status in 200..299) LiFiStatus.parse(res.body) else FillStatus.UNKNOWN
    }
}
