package com.example.wallet.chain

import com.example.wallet.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * An error from the Across API (or from our proxy in front of it). Across uses two envelopes and this reads both:
 * nested `{"error":{"code","message","requestId"}}` and the flat gateway shape `{"code","message"}`.
 */
class AcrossApiException(
    val code: String,
    message: String,
    val httpStatus: Int,
    val requestId: String? = null
) : IOException(message)

internal object AcrossErrors {
    fun parse(status: Int, body: String): AcrossApiException {
        val obj = try { JSONObject(body) } catch (e: Exception) { null }
        val nested = obj?.optJSONObject("error")
        val code = nested?.optString("code")?.takeIf { it.isNotBlank() }
            ?: obj?.optString("code")?.takeIf { it.isNotBlank() }
            ?: "http_$status"
        val message = nested?.optString("message")?.takeIf { it.isNotBlank() }
            ?: obj?.optString("message")?.takeIf { it.isNotBlank() }
            ?: "HTTP $status"
        val requestId = nested?.optString("requestId")?.takeIf { it.isNotBlank() }
        return AcrossApiException(code, message.take(200), status, requestId)
    }

    /** What to show the user. Server messages are written for developers, so they are only appended for unknown codes. */
    fun userMessage(e: AcrossApiException): String = when {
        e.code == NOT_CONFIGURED -> e.message ?: "The bridge is not set up in this build."
        e.code == "proxy_unauthorized" || e.code == "unauthenticated" || e.code.startsWith("auth.") ->
            "The bridge service rejected this app's credentials. The app token or Across key needs attention."
        e.code == "policy.amount_out_of_range" -> "That amount is outside what this route can carry. Try a different amount."
        e.code == "conflict.quote_expired" -> "The quote expired. Request a new one."
        e.code == "rate_limit.exceeded" || e.httpStatus == 429 -> "Too many requests. Wait a minute and try again."
        e.code == "not_found.route" -> "This route isn't available right now."
        e.code == "permission.not_entitled" -> "This bridge feature isn't enabled for the app's Across key."
        e.code.startsWith("system.") || e.code == "proxy_upstream_unreachable" || e.httpStatus in 500..599 ->
            "The bridge service is unavailable right now. Try again shortly."
        else -> "Bridge service error (${e.code}): ${e.message}"
    }

    const val NOT_CONFIGURED = "not_configured"
}

/** How the client talks to the network. Tests replace it with a fake. */
internal fun interface AcrossTransport {
    fun send(method: String, url: String, body: String?, headers: Map<String, String>): HttpResult
}

/**
 * Talks to the Across API THROUGH our proxy (see proxy/). The Across API key lives only in the proxy; this app holds a
 * proxy URL and a revocable app token. Only three calls exist, matching what the proxy allows.
 * Returns raw JSON text: parsing and validating it is the job of the caller, which must not trust it blindly.
 */
internal class AcrossProxyClient(
    baseUrl: String = BuildConfig.ACROSS_PROXY_URL,
    private val token: String = BuildConfig.ACROSS_PROXY_TOKEN,
    private val transport: AcrossTransport = AcrossTransport { method, url, body, headers ->
        Http.exchange(url, method, body, if (body != null) "application/json" else null, headers)
    }
) {
    private val base: String = baseUrl.trim().trimEnd('/')

    /** Only an https proxy with a token counts. Plain http would put the app token on the wire in clear. */
    val isConfigured: Boolean get() = base.startsWith("https://") && base.length > "https://".length && token.isNotBlank()

    /** GET /v1/capabilities, e.g. originChainId, destinationChainId, originToken, destinationToken, include. */
    fun capabilities(query: Map<String, String>): String {
        val qs = query.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        return call("GET", "/v1/capabilities" + if (qs.isNotEmpty()) "?$qs" else "", null)
    }

    /** POST /v1/quotes with a JSON object body. Prices one transfer; nothing is signed or sent. */
    fun quote(bodyJson: String): String = call("POST", "/v1/quotes", bodyJson)

    /** GET /v1/transfers/{orderId}. */
    fun transfer(orderId: String): String {
        require(ORDER_ID.matches(orderId)) { "Invalid order id" }
        return call("GET", "/v1/transfers/$orderId", null)
    }

    private fun call(method: String, pathAndQuery: String, body: String?): String {
        if (!isConfigured) {
            throw AcrossApiException(
                AcrossErrors.NOT_CONFIGURED,
                "Bridging needs the Across proxy. Deploy proxy/ and set ACROSS_PROXY_URL (https) and ACROSS_PROXY_TOKEN when building.",
                0
            )
        }
        val headers = HashMap<String, String>()
        headers["X-App-Token"] = token
        headers["Accept"] = "application/json"
        val result = try {
            transport.send(method, base + pathAndQuery, body, headers)
        } catch (e: AcrossApiException) {
            throw e
        } catch (e: IOException) {
            throw AcrossApiException("proxy_upstream_unreachable", "Could not reach the bridge service.", 0)
        }
        if (result.status !in 200..299) throw AcrossErrors.parse(result.status, result.body)
        return result.body
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        val ORDER_ID = Regex("0x[0-9a-fA-F]{64}")
    }
}
