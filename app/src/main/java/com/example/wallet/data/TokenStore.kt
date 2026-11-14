package com.example.wallet.data

import android.content.Context
import com.example.wallet.chain.Chain
import org.json.JSONArray
import org.json.JSONObject

data class TokenInfo(val address: String, val symbol: String, val decimals: Int)

/** Persists user-added token contracts (public data only, never keys). */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("custom_tokens", Context.MODE_PRIVATE)

    fun list(chain: Chain): List<TokenInfo> {
        val arr = JSONArray(prefs.getString(chain.name, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TokenInfo(o.getString("a"), o.getString("s"), o.getInt("d"))
        }
    }

    fun add(chain: Chain, token: TokenInfo) {
        val current = list(chain).filterNot { it.address.equals(token.address, ignoreCase = true) }
        save(chain, current + token)
    }

    fun remove(chain: Chain, address: String) =
        save(chain, list(chain).filterNot { it.address.equals(address, ignoreCase = true) })

    private fun save(chain: Chain, tokens: List<TokenInfo>) {
        val arr = JSONArray()
        tokens.forEach { arr.put(JSONObject().put("a", it.address).put("s", it.symbol).put("d", it.decimals)) }
        prefs.edit().putString(chain.name, arr.toString()).apply()
    }
}
