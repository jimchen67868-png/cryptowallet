package com.example.wallet.chain

import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/** An asset that can be swapped. [id] is the token contract, or null for the chain's native coin. */
data class SwapAsset(
    val id: String?,
    val symbol: String,
    val decimals: Int,
    /** False for tokens the user added by hand: they are not on the built-in list and get a warning. */
    val curated: Boolean = true
)

class SwapQuote(
    val chain: Chain,
    val from: SwapAsset,
    val to: SwapAsset,
    val amountInRaw: BigInteger,
    val expectedOutRaw: BigInteger,
    val slippageBps: Int,
    val needsApproval: Boolean,
    val gasUsd: String?
) {
    val amountIn: BigDecimal get() = BigDecimal(amountInRaw).movePointLeft(from.decimals)
    val expectedOut: BigDecimal get() = BigDecimal(expectedOutRaw).movePointLeft(to.decimals)
    val minOut: BigDecimal
        get() = BigDecimal(SwapGuard.minOut(expectedOutRaw, slippageBps)).movePointLeft(to.decimals)
}

class SwapResult(val approvalTxIds: List<String>, val swapTxId: String)

/**
 * Safety checks on aggregator-supplied calldata. The wallet signs whatever transaction the API returns,
 * so a compromised or buggy API must not be able to redirect funds. Everything here fails closed:
 * anything unrecognised is rejected.
 */
object SwapGuard {
    /** ParaSwap Augustus V6.2 router. Same address on every supported EVM chain. */
    const val AUGUSTUS_V6 = "0x6A000F20005980200259B80c5102003040001068"
    const val TOKEN_TRANSFER_PROXY = "0x93aAAe79a53759cD164340E4C8766E4Db5331cD7"
    const val NATIVE_PLACEHOLDER = "0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE"

    /** swapExactAmountIn(address,(address,address,uint256,uint256,uint256,bytes32,address),uint256,bytes,bytes) */
    const val SELECTOR_SWAP_EXACT_AMOUNT_IN = "e3ead59e"

    /** The calldata's minimum output may be at most this far below the quoted output. */
    const val MAX_SLIPPAGE_BPS = 100

    private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
    private val ALLOWED_ROUTERS = setOf(AUGUSTUS_V6.lowercase())
    private val ALLOWED_SPENDERS = setOf(AUGUSTUS_V6.lowercase(), TOKEN_TRANSFER_PROXY.lowercase())

    fun isAllowedSpender(spender: String) = spender.lowercase() in ALLOWED_SPENDERS

    fun minOut(expected: BigInteger, slippageBps: Int): BigInteger =
        expected.multiply(BigInteger.valueOf(10_000L - slippageBps)).divide(BigInteger.valueOf(10_000))

    fun tokenKey(a: SwapAsset): String = (a.id ?: NATIVE_PLACEHOLDER).lowercase()

    class Decoded(
        val srcToken: String,
        val destToken: String,
        val fromAmount: BigInteger,
        val minToAmount: BigInteger,
        val beneficiary: String
    )

    /**
     * Layout after the 4-byte selector (32-byte words): executor, then the GenericData struct
     * (srcToken, destToken, fromAmount, toAmount, quotedAmount, metadata, beneficiary), partnerAndFee, ...
     * Returns null for any other function, malformed or truncated data, or non-address words.
     */
    fun decodeSwapExactAmountIn(data: String): Decoded? {
        val hex = data.removePrefix("0x").lowercase()
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        if (hex.length < 8 + 64 * 8) return null
        if (hex.substring(0, 8) != SELECTOR_SWAP_EXACT_AMOUNT_IN) return null

        fun word(i: Int) = hex.substring(8 + 64 * i, 8 + 64 * (i + 1))
        fun address(i: Int): String? {
            val w = word(i)
            return if (w.substring(0, 24).all { it == '0' }) "0x" + w.substring(24) else null
        }
        return Decoded(
            srcToken = address(1) ?: return null,
            destToken = address(2) ?: return null,
            fromAmount = BigInteger(word(3), 16),
            minToAmount = BigInteger(word(4), 16),
            beneficiary = address(7) ?: return null
        )
    }

    /** Returns an error message, or null if the transaction does exactly what the user asked for. */
    fun validateSwapTx(
        to: String,
        value: BigInteger,
        data: String,
        src: SwapAsset,
        dest: SwapAsset,
        amountIn: BigInteger,
        expectedOut: BigInteger,
        user: String
    ): String? {
        if (to.lowercase() !in ALLOWED_ROUTERS) return "Unexpected swap router address"
        val expectedValue = if (src.id == null) amountIn else BigInteger.ZERO
        if (value != expectedValue) return "Unexpected transaction value"
        val d = decodeSwapExactAmountIn(data) ?: return "Unrecognised swap call data"
        if (d.srcToken != tokenKey(src)) return "Swap source token mismatch"
        if (d.destToken != tokenKey(dest)) return "Swap destination token mismatch"
        if (d.fromAmount != amountIn) return "Swap amount mismatch"
        if (d.beneficiary != user.lowercase() && d.beneficiary != ZERO_ADDRESS) {
            return "Swap would pay out to a different address"
        }
        if (d.minToAmount < minOut(expectedOut, MAX_SLIPPAGE_BPS)) return "Swap minimum output is too low"
        return null
    }
}

/** ParaSwap REST API (keyless). Mainnet networks only: there is no aggregator liquidity on testnets. */
internal class ParaSwapApi(
    private val networkId: Int,
    private val baseUrl: String = "https://api.paraswap.io"
) {
    class Route(val json: JSONObject, val destAmount: BigInteger, val spender: String?, val gasUsd: String?) {
        val contractAddress: String? get() = json.optString("contractAddress").ifBlank { null }
    }

    class BuiltTx(val to: String, val value: BigInteger, val data: String, val chainId: Long)

    private fun key(a: SwapAsset) = a.id ?: SwapGuard.NATIVE_PLACEHOLDER

    fun route(user: String, from: SwapAsset, to: SwapAsset, amountRaw: BigInteger): Route {
        val url = "$baseUrl/prices?srcToken=${key(from)}&srcDecimals=${from.decimals}" +
            "&destToken=${key(to)}&destDecimals=${to.decimals}&amount=$amountRaw&side=SELL" +
            "&network=$networkId&version=6.2&excludeDirectContractMethods=true&userAddress=$user"
        val json = JSONObject(Http.get(url))
        throwIfError(json)
        val pr = json.getJSONObject("priceRoute")
        return Route(
            json = pr,
            destAmount = BigInteger(pr.getString("destAmount")),
            spender = pr.optString("tokenTransferProxy").ifBlank { null },
            gasUsd = pr.optString("gasCostUSD").ifBlank { null }
        )
    }

    fun build(
        user: String, from: SwapAsset, to: SwapAsset, route: Route, amountRaw: BigInteger, slippageBps: Int
    ): BuiltTx {
        val body = JSONObject()
            .put("srcToken", key(from)).put("srcDecimals", from.decimals)
            .put("destToken", key(to)).put("destDecimals", to.decimals)
            .put("srcAmount", amountRaw.toString())
            .put("slippage", slippageBps)
            .put("priceRoute", route.json)
            .put("userAddress", user)
        val json = JSONObject(Http.postJson("$baseUrl/transactions/$networkId?ignoreChecks=true", body.toString()))
        throwIfError(json)
        return BuiltTx(
            to = json.getString("to"),
            value = parseBig(json.optString("value", "0")),
            data = json.getString("data"),
            chainId = json.optLong("chainId", 0L)
        )
    }

    private fun throwIfError(json: JSONObject) {
        val err = json.optString("error")
        if (err.isNotBlank()) throw IllegalStateException(err)
    }

    private fun parseBig(s: String): BigInteger = when {
        s.isBlank() -> BigInteger.ZERO
        s.startsWith("0x") -> BigInteger(s.substring(2).ifEmpty { "0" }, 16)
        else -> BigInteger(s)
    }
}
