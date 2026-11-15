package com.example.wallet.data

import android.content.Context

enum class Network { TESTNET, MAINNET }

object AppConfig {
    /**
     * Per-transaction cap on mainnet, in USD. Sends above it (or of assets with no known price)
     * are blocked. Raise it only after the code has been reviewed and proven with small amounts.
     */
    const val MAINNET_TX_LIMIT_USD = 100.0
}

/** Non-secret app settings. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var network: Network
        get() = runCatching { Network.valueOf(prefs.getString(KEY_NETWORK, null) ?: "TESTNET") }
            .getOrDefault(Network.TESTNET)
        set(value) { prefs.edit().putString(KEY_NETWORK, value.name).apply() }

    private companion object {
        const val KEY_NETWORK = "network"
    }
}
