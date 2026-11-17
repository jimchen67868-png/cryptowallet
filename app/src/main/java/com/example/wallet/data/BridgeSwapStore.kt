package com.example.wallet.data

import android.content.Context
import com.example.wallet.chain.BridgeSwapJob
import com.example.wallet.chain.BridgeSwapJobCodec
import com.example.wallet.chain.BridgeSwapStore
import org.json.JSONObject

/**
 * Keeps in-flight "bridge then swap" jobs across app restarts. Holds no keys or secrets: only chains, amounts, the
 * public deposit transaction id and asset addresses. Excluded from cloud backup and device transfer like the other prefs.
 */
class PrefsBridgeSwapStore(context: Context) : BridgeSwapStore {
    private val prefs = context.getSharedPreferences("bridge_swap_jobs", Context.MODE_PRIVATE)

    override fun list(): List<BridgeSwapJob> =
        prefs.all.entries
            .filter { it.key.startsWith(PREFIX) }
            .mapNotNull { (it.value as? String)?.let { text -> runCatching { JSONObject(text) }.getOrNull() } }
            .mapNotNull(BridgeSwapJobCodec::fromJson)

    override fun save(job: BridgeSwapJob) {
        prefs.edit().putString(PREFIX + job.id, BridgeSwapJobCodec.toJson(job).toString()).apply()
    }

    override fun remove(id: String) {
        prefs.edit().remove(PREFIX + id).apply()
    }

    private companion object {
        const val PREFIX = "job:"
    }
}
