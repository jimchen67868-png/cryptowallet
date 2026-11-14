package com.example.wallet.chain

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object Http {
    fun get(url: String): String = request(url, "GET", null)
    fun postJson(url: String, body: String): String = request(url, "POST", body)

    private fun request(url: String, method: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }
}
