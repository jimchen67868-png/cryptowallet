package com.example.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The Across proxy client: no network, a fake transport records what would be sent. */
class AcrossProxyTest {
    private class Sent(val method: String, val url: String, val body: String?, val headers: Map<String, String>)

    private class Fake(var status: Int = 200, var body: String = "{}", var throwIo: Boolean = false) {
        val sent = ArrayList<Sent>()
        val transport = AcrossTransport { method, url, b, headers ->
            sent.add(Sent(method, url, b, headers))
            if (throwIo) throw IOException("offline")
            HttpResult(status, body)
        }
    }

    private val order = "0x" + "ab".repeat(32)
    private fun client(fake: Fake, url: String = "https://proxy.example", token: String = "tok") =
        AcrossProxyClient(url, token, fake.transport)

    private fun expectError(block: () -> Unit): AcrossApiException {
        try { block() } catch (e: AcrossApiException) { return e }
        fail("expected AcrossApiException")
        throw AssertionError()
    }

    // ---- configuration ----

    @Test fun configured_onlyForHttpsWithAToken() {
        val f = Fake()
        assertTrue(client(f).isConfigured)
        assertTrue(client(f, "https://proxy.example/").isConfigured)
        assertFalse("plain http would expose the app token", client(f, "http://proxy.example").isConfigured)
        assertFalse(client(f, "").isConfigured)
        assertFalse(client(f, "https://").isConfigured)
        assertFalse(client(f, "proxy.example").isConfigured)
        assertFalse(client(f, token = "").isConfigured)
        assertFalse(client(f, token = "   ").isConfigured)
    }

    @Test fun unconfigured_failsBeforeTouchingTheNetwork() {
        val f = Fake()
        val e = expectError { client(f, "").quote("{}") }
        assertEquals(AcrossErrors.NOT_CONFIGURED, e.code)
        assertTrue(AcrossErrors.userMessage(e).contains("proxy", ignoreCase = true))
        expectError { client(f, "http://insecure.example").capabilities(emptyMap()) }
        assertTrue(f.sent.isEmpty())
    }

    // ---- requests ----

    @Test fun capabilities_buildsAnEncodedGetWithOnlyTheAppToken() {
        val f = Fake(body = """{"ok":1}""")
        val out = client(f, "https://proxy.example/").capabilities(
            linkedMapOf("originChainId" to "42161", "destinationChainId" to "8453", "capabilities.operations" to "approve_token:evm,send_transaction:evm")
        )
        assertEquals("""{"ok":1}""", out)
        val s = f.sent.single()
        assertEquals("GET", s.method)
        assertEquals(
            "https://proxy.example/v1/capabilities?originChainId=42161&destinationChainId=8453&capabilities.operations=approve_token%3Aevm%2Csend_transaction%3Aevm",
            s.url
        )
        assertNull(s.body)
        assertEquals(setOf("X-App-Token", "Accept"), s.headers.keys)
        assertEquals("tok", s.headers["X-App-Token"])
    }

    @Test fun capabilities_withoutQueryHasNoQuestionMark() {
        val f = Fake()
        client(f).capabilities(emptyMap())
        assertEquals("https://proxy.example/v1/capabilities", f.sent.single().url)
    }

    @Test fun quote_postsTheBodyUnchanged() {
        val f = Fake(body = """{"orderId":"x"}""")
        val body = """{"origin":{"amount":"2000000"},"amountType":"exact_input"}"""
        assertEquals("""{"orderId":"x"}""", client(f).quote(body))
        val s = f.sent.single()
        assertEquals("POST", s.method)
        assertEquals("https://proxy.example/v1/quotes", s.url)
        assertEquals(body, s.body)
    }

    @Test fun noAcrossKeyOrAuthorizationHeaderIsEverSent() {
        val f = Fake()
        val c = client(f)
        c.capabilities(mapOf("a" to "b")); c.quote("{}"); c.transfer(order)
        assertEquals(3, f.sent.size)
        for (s in f.sent) {
            assertFalse(s.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
            assertTrue(s.headers.keys.all { it == "X-App-Token" || it == "Accept" })
        }
    }

    @Test fun transfer_requiresAFullOrderIdAndEncodesNothingElse() {
        val f = Fake()
        client(f).transfer(order)
        assertEquals("https://proxy.example/v1/transfers/$order", f.sent.single().url)
        for (bad in listOf("", "0x1234", order + "/x", order.drop(1), "../quotes", "0x" + "zz".repeat(32), order + "?a=b")) {
            try { client(f).transfer(bad); fail("accepted $bad") } catch (e: IllegalArgumentException) { /* expected */ }
        }
        assertEquals("no request for a bad id", 1, f.sent.size)
    }

    // ---- errors ----

    @Test fun nestedErrorEnvelope() {
        val f = Fake(400, """{"error":{"type":"request","code":"request.unsupported_param","message":"unsupported field: amountTyp","param":"amountTyp","requestId":"abc123"}}""")
        val e = expectError { client(f).quote("{}") }
        assertEquals("request.unsupported_param", e.code)
        assertEquals(400, e.httpStatus)
        assertEquals("abc123", e.requestId)
        assertEquals("unsupported field: amountTyp", e.message)
    }

    @Test fun flatGatewayEnvelope() {
        val f = Fake(401, """{"code":"unauthenticated","message":"AUTH_INVALID","details":null}""")
        val e = expectError { client(f).capabilities(emptyMap()) }
        assertEquals("unauthenticated", e.code)
        assertEquals(401, e.httpStatus)
        assertNull(e.requestId)
        assertTrue(AcrossErrors.userMessage(e).contains("credentials"))
    }

    @Test fun garbageBodiesStillProduceAUsableError() {
        for ((status, body) in listOf(500 to "<html>oops</html>", 502 to "", 429 to "not json", 404 to "{}")) {
            val e = expectError { client(Fake(status, body)).quote("{}") }
            assertEquals(status, e.httpStatus)
            assertEquals("http_$status", e.code)
            assertTrue(AcrossErrors.userMessage(e).isNotBlank())
        }
    }

    @Test fun networkFailureBecomesAProxyUnreachableError() {
        val e = expectError { client(Fake(throwIo = true)).quote("{}") }
        assertEquals("proxy_upstream_unreachable", e.code)
        assertTrue(AcrossErrors.userMessage(e).contains("unavailable"))
    }

    @Test fun overLongServerMessagesAreTruncated() {
        val e = expectError { client(Fake(400, """{"code":"x.y","message":"${"m".repeat(500)}"}""")).quote("{}") }
        assertEquals(200, e.message!!.length)
    }

    // ---- friendly messages ----

    private fun msg(code: String, status: Int = 400) = AcrossErrors.userMessage(AcrossApiException(code, "server text", status))

    @Test fun friendlyMessages() {
        assertTrue(msg("policy.amount_out_of_range").contains("amount"))
        assertTrue(msg("conflict.quote_expired").contains("expired"))
        assertTrue(msg("rate_limit.exceeded", 429).contains("Too many"))
        assertTrue(msg("whatever", 429).contains("Too many"))
        assertTrue(msg("proxy_unauthorized", 401).contains("credentials"))
        assertTrue(msg("auth.invalid_credentials", 401).contains("credentials"))
        assertTrue(msg("system.unavailable", 503).contains("unavailable"))
        assertTrue(msg("proxy_upstream_unreachable", 502).contains("unavailable"))
        assertTrue(msg("not_found.route").contains("route"))
        assertTrue(msg("permission.not_entitled", 403).contains("isn't enabled"))
    }

    @Test fun unknownCodesShowTheCodeAndTheServerText() {
        val m = msg("brand.new_code")
        assertTrue(m.contains("brand.new_code") && m.contains("server text"))
    }
}
