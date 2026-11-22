package com.example.wallet.chain

import com.example.wallet.data.EvmTokens
import com.example.wallet.data.TokenInfo
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.abs

/** An EVM network that Across can bridge to and from, with its SpokePool and the tokens we know on it. */
class BridgeRoute(
    val chain: Chain,
    val chainId: Long,
    val spokePool: String,
    /** True if the native coin is ETH (Ethereum, Base, Arbitrum); false for Polygon (POL). */
    val nativeIsEth: Boolean,
    val tokens: List<TokenInfo>
) {
    val weth: String get() = tokens.first { it.symbol == "WETH" }.address
}

/** Across V3 SpokePool deployments (EIP-55 checked in the tests). BNB Chain is not offered. */
object AcrossRoutes {
    val MAINNET: List<BridgeRoute> by lazy {
        listOf(
            BridgeRoute(Chain.ETH, 1L, "0x5c7BCd6E7De5423a257D81B442095A1a6ced35C5", true, EthereumProvider.MAINNET_TOKENS),
            BridgeRoute(Chain.BASE, 8453L, "0x09aea4b2242abC8bb4BB78D537A67a245A7bEC64", true, EvmTokens.BASE),
            BridgeRoute(Chain.ARB, 42161L, "0xe35e9842fceaCA96570B734083f4a58e8F7C5f2A", true, EvmTokens.ARBITRUM),
            BridgeRoute(Chain.POL, 137L, "0x9295ee1d8C5b022Be115A2AD3c30C72E34e7F096", false, EvmTokens.POLYGON)
        )
    }
}

class BridgeQuote(
    val chain: Chain,
    val destChain: Chain,
    val from: SwapAsset,
    val amountInRaw: BigInteger,
    val amountOutRaw: BigInteger,
    val feeRaw: BigInteger,
    val decimals: Int,
    val outSymbol: String,
    val etaSeconds: Long,
    val needsApproval: Boolean,
    /** A separate fee paid in the source chain's native coin on top of the amount (CCIP). Zero when fees come out of the amount. */
    val networkFeeRaw: BigInteger = BigInteger.ZERO,
    val networkFeeSymbol: String = "",
    /** Decimals of [networkFeeRaw]: 18 for EVM coins, 8 for Litecoin, 6 for Tron. */
    val networkFeeDecimals: Int = 18,
    /** Whatever the provider needs to execute this quote (THORChain: the checked plan). Never shown. */
    val payload: Any? = null,
    /** The user's own address on [destChain] when the provider needs one (THORChain), shown before confirming. */
    val destAddress: String? = null,
    /** Provider-specific explanation shown in the confirmation instead of the Across refund text. */
    val note: String? = null
) {
    val amountIn: BigDecimal get() = BigDecimal(amountInRaw).movePointLeft(decimals)
    val amountOut: BigDecimal get() = BigDecimal(amountOutRaw).movePointLeft(decimals)
    val fee: BigDecimal get() = BigDecimal(feeRaw).movePointLeft(decimals)
    /** The separate native-coin fee, or zero. */
    val networkFee: BigDecimal get() = BigDecimal(networkFeeRaw).movePointLeft(networkFeeDecimals)
    val feePercent: Double
        get() = if (amountInRaw.signum() == 0) 0.0
        else BigDecimal(feeRaw).multiply(BigDecimal(100)).divide(BigDecimal(amountInRaw), 2, RoundingMode.HALF_UP).toDouble()
}

/**
 * depositV3 on the Across SpokePool. The wallet builds this call itself: recipient, depositor, tokens, amounts and
 * destination chain are all ours; only fee, timestamps and the relayer come from the API and are validated first.
 */
object AcrossAbi {
    /** depositV3(address,address,address,address,uint256,uint256,uint256,address,uint32,uint32,uint32,bytes) */
    const val SELECTOR_DEPOSIT_V3 = "7b939232"

    private const val UINT32_MAX = 0xFFFFFFFFL

    private fun word(hex: String): String = hex.removePrefix("0x").lowercase().padStart(64, '0')
    private fun word(v: BigInteger): String {
        require(v.signum() >= 0 && v.bitLength() <= 256) { "value out of range" }
        return v.toString(16).padStart(64, '0')
    }
    private fun word(v: Long): String = word(BigInteger.valueOf(v))
    private fun uint32(v: Long, name: String): String {
        require(v in 0..UINT32_MAX) { "$name does not fit in 32 bits" }
        return word(v)
    }

    fun depositV3(
        depositor: String, recipient: String, inputToken: String, outputToken: String,
        inputAmount: BigInteger, outputAmount: BigInteger, destinationChainId: Long,
        exclusiveRelayer: String, quoteTimestamp: Long, fillDeadline: Long, exclusivityDeadline: Long
    ): String = "0x" + SELECTOR_DEPOSIT_V3 +
        word(depositor) + word(recipient) + word(inputToken) + word(outputToken) +
        word(inputAmount) + word(outputAmount) + word(destinationChainId) + word(exclusiveRelayer) +
        uint32(quoteTimestamp, "quoteTimestamp") + uint32(fillDeadline, "fillDeadline") +
        uint32(exclusivityDeadline, "exclusivityDeadline") +
        word(12 * 32L) +  // offset of `bytes message`: after the 12 head words
        word(0L)          // empty message: length 0
}

class SuggestedFees(
    val totalFee: BigInteger,
    val timestamp: Long,
    val fillDeadline: Long,
    val exclusiveRelayer: String,
    val exclusivityDeadline: Long,
    val spokePool: String,
    val etaSeconds: Long,
    val isAmountTooLow: Boolean
)

/** Sanity checks on the numbers the Across API supplies. Fails closed. */
object AcrossGuard {
    /** Refuse bridges whose fee exceeds this share of the amount (small amounts carry a large fixed gas fee). */
    const val MAX_FEE_PERCENT = 5L
    const val MAX_CLOCK_SKEW_SECONDS = 900L
    const val MAX_DEADLINE_SECONDS = 86_400L

    private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")

    fun validate(fees: SuggestedFees, route: BridgeRoute, amountIn: BigInteger, nowSec: Long): String? {
        if (!fees.spokePool.equals(route.spokePool, ignoreCase = true)) return "Unexpected bridge contract in the quote"
        if (fees.isAmountTooLow) return "The amount is below the bridge minimum for this route"
        if (fees.totalFee.signum() < 0) return "Invalid bridge fee in the quote"
        if (fees.totalFee >= amountIn) return "The bridge fee is larger than the amount"
        if (fees.totalFee.multiply(BigInteger.valueOf(100)) > amountIn.multiply(BigInteger.valueOf(MAX_FEE_PERCENT))) {
            return "The bridge fee is over $MAX_FEE_PERCENT% of the amount. Try a larger amount."
        }
        if (abs(fees.timestamp - nowSec) > MAX_CLOCK_SKEW_SECONDS) return "The quote timestamp is out of range"
        if (fees.fillDeadline <= nowSec || fees.fillDeadline > nowSec + MAX_DEADLINE_SECONDS) {
            return "The quote deadline is out of range"
        }
        if (!ADDRESS.matches(fees.exclusiveRelayer)) return "Invalid relayer address in the quote"
        if (fees.exclusivityDeadline < 0 || fees.exclusivityDeadline > nowSec + MAX_DEADLINE_SECONDS) {
            return "Invalid exclusivity window in the quote"
        }
        return null
    }
}

/** Across REST API (keyless). Mainnet only. */
internal class AcrossApi(private val baseUrl: String = "https://app.across.network/api") {
    fun suggestedFees(
        originChainId: Long, destinationChainId: Long, inputToken: String, outputToken: String,
        amount: BigInteger, recipient: String
    ): SuggestedFees {
        val url = "$baseUrl/suggested-fees?inputToken=$inputToken&outputToken=$outputToken" +
            "&originChainId=$originChainId&destinationChainId=$destinationChainId&amount=$amount&recipient=$recipient"
        val j = JSONObject(Http.get(url))
        val message = j.optString("message")
        if (j.optString("type") == "AcrossApiError" && message.isNotBlank()) throw IllegalStateException(message)
        return SuggestedFees(
            totalFee = BigInteger(j.getJSONObject("totalRelayFee").getString("total")),
            timestamp = j.getString("timestamp").toLong(),
            fillDeadline = j.getString("fillDeadline").toLong(),
            exclusiveRelayer = j.optString("exclusiveRelayer", ZERO_ADDRESS).ifBlank { ZERO_ADDRESS },
            exclusivityDeadline = j.optString("exclusivityDeadline", "0").ifBlank { "0" }.toLong(),
            spokePool = j.getString("spokePoolAddress"),
            etaSeconds = j.optString("estimatedFillTimeSec", "0").toLongOrNull() ?: 0L,
            isAmountTooLow = j.optBoolean("isAmountTooLow", false)
        )
    }

    /** Fill status of a deposit, looked up by its origin-chain transaction hash. Any failure is UNKNOWN, never a guess. */
    fun depositStatus(depositTxId: String): FillStatus {
        if (!BridgeSwapLogic.TX_ID.matches(depositTxId)) return FillStatus.UNKNOWN
        return AcrossStatus.parse(Http.get("$baseUrl/deposit/status?depositTxnRef=$depositTxId"))
    }

    private companion object {
        const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
    }
}

/** Parses the `status` field of Across's /deposit/status response. Anything unrecognised is UNKNOWN. */
internal object AcrossStatus {
    fun parse(body: String): FillStatus = try {
        when (JSONObject(body).optString("status").lowercase()) {
            "filled" -> FillStatus.FILLED
            "pending" -> FillStatus.PENDING
            "expired" -> FillStatus.EXPIRED
            "refunded" -> FillStatus.REFUNDED
            else -> FillStatus.UNKNOWN
        }
    } catch (e: Exception) {
        FillStatus.UNKNOWN
    }
}
