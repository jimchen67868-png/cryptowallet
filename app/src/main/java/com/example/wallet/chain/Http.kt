package com.example.wallet.chain

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object Http {
    fun get(url: String): String = request(url, "GET", null, null)
    fun postJson(url: String, body: String): String = request(url, "POST", body, "application/json")
    fun postText(url: String, body: String): String = request(url, "POST", body, "text/plain")

    /** Like [get]/[postJson] but never throws on a non-2xx status: the caller reads the status and body itself. */
    fun exchange(
        url: String, method: String, body: String?, contentType: String?, headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType ?: "text/plain")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return HttpResult(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun request(url: String, method: String, body: String?, contentType: String?): String {
        val r = exchange(url, method, body, contentType)
        if (r.status !in 200..299) throw IOException("HTTP ${r.status}: ${r.body.take(200)}")
        return r.body
    }
}

internal class HttpResult(val status: Int, val body: String)
