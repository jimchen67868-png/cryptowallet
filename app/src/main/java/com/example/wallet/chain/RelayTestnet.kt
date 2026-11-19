package com.example.wallet.chain

import org.json.JSONObject
import java.math.BigInteger

/**
 * Bridging TEST ETH between Sepolia and Base Sepolia through Relay's testnet API (https://api.testnets.relay.link).
 *
 * This route exists so the bridge and bridge-then-swap flows can be tried with worthless funds. It is deliberately NOT
 * available on mainnet: in Relay's flow the on-chain deposit only carries the depositor and an opaque order id
 * (`depositNative(address,bytes32)`), and Relay's server decides where the funds go. Unlike the LI.FI route the wallet
 * cannot prove the recipient from the calldata, it can only check what Relay's response claims. That is fine for
 * test funds and is not something to trust with real money.
 *
 * What [RelayGuard] does verify: the contract (pinned), the function, the depositor, the exact value, the chain, that the
 * status link belongs to the quote, and that Relay's own response names you as sender and recipient with sane amounts.
 */

class RelayQuote(
    val stepCount: Int,
    val stepId: String,
    val stepKind: String,
    val requestId: String,
    val itemCount: Int,
    val itemStatus: String,
    val txFrom: String,
    val txTo: String,
    val txData: String,
    val txValue: BigInteger,
    val txChainId: Long,
    val checkEndpoint: String,
    val sender: String,
    val recipient: String,
    val amountIn: BigInteger,
    val amountOut: BigInteger,
    val minAmountOut: BigInteger,
    val etaSeconds: Long
)

class RelayRequest(val user: String, val originChainId: Long, val destinationChainId: Long, val amount: BigInteger)

object RelayTestnet {
    const val API_BASE = "https://api.testnets.relay.link"

    const val SEPOLIA = 11_155_111L
    const val BASE_SEPOLIA = 84_532L

    /**
     * Relay's v2 depository contract on both testnets. Taken from Relay's own /chains response (protocol.v2.depository)
     * and confirmed as the `to` of a real deposit transaction (tools/verify/relay_fixture_sepolia_basesepolia_eth.json).
     */
    const val DEPOSITORY = "0x5Feab8dB4534F9F7E2669Bb260c57a01aD1c12e3"

    const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

    /** Chains Relay's testnet API supports for bridging that this wallet has a provider for. */
    private val CHAIN_IDS: Map<Chain, Long> = mapOf(Chain.ETH to SEPOLIA, Chain.BASE to BASE_SEPOLIA)

    fun chainIdOf(chain: Chain): Long? = CHAIN_IDS[chain]

    fun destinations(origin: Chain): List<Chain> = if (origin in CHAIN_IDS) CHAIN_IDS.keys.filter { it != origin } else emptyList()

    /** Native ETH arrives as native ETH on the other testnet. Tokens cannot be bridged on Relay's testnets. */
    fun candidateAssets(from: SwapAsset, dest: Chain): List<SwapAsset> =
        if (from.id == null && dest in CHAIN_IDS) listOf(SwapAsset(null, dest.symbol, 18)) else emptyList()
}

object RelayQuoteParser {
    private fun dec(s: String?, name: String): BigInteger {
        if (s.isNullOrBlank()) throw IllegalArgumentException("Relay quote is missing $name")
        return BigInteger(s)
    }

    /** Throws IllegalStateException with Relay's own message for an error response, IllegalArgumentException if malformed. */
    fun parse(body: String): RelayQuote {
        val j = try { JSONObject(body) } catch (e: Exception) { throw IllegalArgumentException("Relay returned something that is not JSON") }
        val steps = j.optJSONArray("steps")
        if (steps == null || steps.length() == 0) {
            val message = j.optString("message")
            throw IllegalStateException(if (message.isNotBlank()) "Relay: ${message.take(200)}" else "Relay returned no transaction for this transfer")
        }
        val step = steps.getJSONObject(0)
        val items = step.optJSONArray("items") ?: throw IllegalArgumentException("Relay quote step has no items")
        if (items.length() == 0) throw IllegalArgumentException("Relay quote step has no items")
        val item = items.getJSONObject(0)
        val data = item.optJSONObject("data") ?: throw IllegalArgumentException("Relay quote is missing transaction data")
        val details = j.optJSONObject("details") ?: throw IllegalArgumentException("Relay quote is missing details")
        val cin = details.optJSONObject("currencyIn") ?: throw IllegalArgumentException("Relay quote is missing currencyIn")
        val cout = details.optJSONObject("currencyOut") ?: throw IllegalArgumentException("Relay quote is missing currencyOut")
        return RelayQuote(
            stepCount = steps.length(),
            stepId = step.optString("id"),
            stepKind = step.optString("kind"),
            requestId = step.optString("requestId"),
            itemCount = items.length(),
            itemStatus = item.optString("status"),
            txFrom = data.optString("from"),
            txTo = data.optString("to"),
            txData = data.optString("data"),
            txValue = dec(data.optString("value"), "value"),
            txChainId = data.getLong("chainId"),
            checkEndpoint = item.optJSONObject("check")?.optString("endpoint") ?: "",
            sender = details.optString("sender"),
            recipient = details.optString("recipient"),
            amountIn = dec(cin.optString("amount"), "currencyIn.amount"),
            amountOut = dec(cout.optString("amount"), "currencyOut.amount"),
            minAmountOut = dec(cout.optString("minimumAmount"), "currencyOut.minimumAmount"),
            etaSeconds = details.optLong("timeEstimate", 0L)
        )
    }
}

/** depositNative(address depositor, bytes32 id): selector 0x49290c1c, two words, nothing else. */
internal object RelayCalldata {
    const val SELECTOR = "49290c1c"

    class Deposit(val depositor: String, val id: String)

    fun decode(data: String): Deposit? = try {
        require(data.startsWith("0x"))
        val hex = data.substring(2).lowercase()
        require(hex.length == 8 + 2 * 64 && hex.all { it in '0'..'9' || it in 'a'..'f' })
        require(hex.substring(0, 8) == SELECTOR)
        val word0 = hex.substring(8, 72)
        require(word0.substring(0, 24).all { it == '0' }) // a clean address
        Deposit("0x" + word0.substring(24), hex.substring(72))
    } catch (e: Exception) {
        null
    }
}

/** Fails closed: returns a user-facing reason to refuse, or null if the quote is what the user asked for. */
object RelayGuard {
    /** Testnet only: fixed costs dominate tiny amounts (about 11% at 0.0001 ETH), so this is generous. */
    const val MAX_FEE_PERCENT = 50L
    /** Relay's minimum (after slippage) must stay within 20% of its expected output. */
    const val MIN_OUT_PERCENT = 80L

    const val TOKENS_UNSUPPORTED = "Only ETH can be bridged between testnets (Relay's testnet supports no tokens)."

    val REQUEST_ID = Regex("0x[0-9a-fA-F]{64}")

    private fun same(a: String?, b: String?) = a != null && b != null && a.equals(b, ignoreCase = true)

    fun validate(q: RelayQuote, r: RelayRequest): String? {
        val allowed = setOf(RelayTestnet.SEPOLIA, RelayTestnet.BASE_SEPOLIA)
        if (r.originChainId !in allowed || r.destinationChainId !in allowed || r.originChainId == r.destinationChainId) {
            return "Relay's testnet bridge only connects Sepolia and Base Sepolia"
        }
        if (r.amount.signum() <= 0) return "Invalid amount"

        // Shape: exactly one on-chain deposit step, nothing else to sign.
        if (q.stepCount != 1 || q.itemCount != 1) return "The Relay quote has unexpected extra steps"
        if (q.stepId != "deposit" || q.stepKind != "transaction") return "The Relay quote step is not a plain deposit"
        if (q.itemStatus != "incomplete") return "The Relay quote step is not ready to send"
        if (!REQUEST_ID.matches(q.requestId)) return "The Relay quote has an invalid request id"
        // The status link must be for THIS quote, or tracking could follow somebody else's transfer.
        if (q.checkEndpoint != "/intents/status/v3?requestId=${q.requestId}") return "The Relay status link doesn't match the quote"

        // Where the transaction goes and what it does.
        if (q.txChainId != r.originChainId) return "The quote's transaction is for a different network"
        if (!same(q.txFrom, r.user)) return "The quote's transaction is for a different sender"
        if (!same(q.txTo, RelayTestnet.DEPOSITORY)) return "Unexpected Relay contract in the quote"
        if (q.txValue != r.amount) return "The deposit value doesn't match the amount you asked to bridge"
        val deposit = RelayCalldata.decode(q.txData) ?: return "The Relay deposit has an unrecognised format"
        if (!same(deposit.depositor, r.user)) return "The Relay deposit names a different depositor"
        if (deposit.id.all { it == '0' }) return "The Relay deposit has an empty order id"

        // What Relay says it will do. This is Relay's claim, not something the transaction proves.
        if (!same(q.sender, r.user) || !same(q.recipient, r.user)) return "Relay's quote names a different sender or recipient"
        if (q.amountIn != r.amount) return "Relay's quote is for a different amount"
        if (q.amountOut.signum() <= 0 || q.amountOut > q.amountIn) return "Relay's quoted output is inconsistent"
        if (q.minAmountOut.signum() <= 0 || q.minAmountOut > q.amountOut) return "Relay's quoted minimum output is inconsistent"
        if (q.minAmountOut.multiply(BigInteger.valueOf(100)) < q.amountOut.multiply(BigInteger.valueOf(MIN_OUT_PERCENT))) {
            return "Relay's quoted minimum output is too low"
        }
        if (q.amountOut.multiply(BigInteger.valueOf(100)) < q.amountIn.multiply(BigInteger.valueOf(100 - MAX_FEE_PERCENT))) {
            return "Relay's fees are over $MAX_FEE_PERCENT% of the amount"
        }
        return null
    }
}

/** Maps Relay's /intents/status/v3 to our FillStatus. Only `success` and `refund` are final; anything unknown is UNKNOWN. */
internal object RelayStatus {
    fun parse(body: String): FillStatus = try {
        val j = JSONObject(body)
        when (j.optString("status").lowercase()) {
            "success" -> FillStatus.FILLED
            "refund" -> FillStatus.REFUNDED
            // A failed fill can still be retried or refunded; only a failure that says no refund will follow is final.
            "failure" -> if (j.optString("refundFailReason").let { it.isNotBlank() && it != "null" }) FillStatus.EXPIRED else FillStatus.PENDING
            "waiting", "depositing", "pending", "submitted" -> FillStatus.PENDING
            else -> FillStatus.UNKNOWN
        }
    } catch (e: Exception) {
        FillStatus.UNKNOWN
    }
}

/** Keyless client for Relay's TESTNET API. If Relay starts requiring a key there, deploy proxy/relay-worker.js. */
internal class RelayApi(
    private val baseUrl: String = RelayTestnet.API_BASE,
    private val transport: (method: String, url: String, body: String?) -> HttpResult = { method, url, body ->
        Http.exchange(url, method, body, if (body != null) "application/json" else null, mapOf("Accept" to "application/json"))
    }
) {
    private val address = Regex("0x[0-9a-fA-F]{40}")

    fun quote(r: RelayRequest): RelayQuote {
        require(address.matches(r.user)) { "Invalid address in the bridge request" }
        require(r.amount.signum() > 0) { "Invalid amount" }
        val body = JSONObject()
            .put("user", r.user)
            .put("originChainId", r.originChainId)
            .put("destinationChainId", r.destinationChainId)
            .put("originCurrency", RelayTestnet.ZERO_ADDRESS)
            .put("destinationCurrency", RelayTestnet.ZERO_ADDRESS)
            .put("amount", r.amount.toString())
            .put("tradeType", "EXACT_INPUT")
            .toString()
        val res = transport("POST", "$baseUrl/quote/v2", body)
        if (res.status == 401 || res.status == 403) {
            throw IllegalStateException(
                "Relay now needs an API key for testnet quotes. Deploy proxy/relay-worker.js and point the app at it (see proxy/README.md)."
            )
        }
        if (res.status !in 200..299) {
            val message = try { JSONObject(res.body).optString("message") } catch (e: Exception) { "" }
            throw IllegalStateException("Relay: " + message.ifBlank { "HTTP ${res.status}" }.take(200))
        }
        return RelayQuoteParser.parse(res.body)
    }

    fun status(requestId: String): FillStatus {
        if (!RelayGuard.REQUEST_ID.matches(requestId)) return FillStatus.UNKNOWN
        val res = transport("GET", "$baseUrl/intents/status/v3?requestId=$requestId", null)
        return if (res.status in 200..299) RelayStatus.parse(res.body) else FillStatus.UNKNOWN
    }
}
