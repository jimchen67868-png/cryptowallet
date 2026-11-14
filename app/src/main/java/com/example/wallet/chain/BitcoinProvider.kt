package com.example.wallet.chain

import com.example.wallet.crypto.Bech32
import com.example.wallet.crypto.Hd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Bitcoin testnet, native SegWit (P2WPKH, BIP-84). Receive + balance only.
 * Sending needs UTXO selection, fee estimation and tx signing: see README.
 */
class BitcoinProvider(
    private val apiBase: String = "https://mempool.space/testnet/api",
    private val hrp: String = "tb"
) : ChainProvider {

    override val chain = Chain.BTC
    override val supportsSend = false

    // m/84'/1'/0'/0/0 (coin type 1 = testnet; use 0 for mainnet and hrp "bc")
    override fun deriveAddress(seed: ByteArray): String {
        val kp = Hd.secp256k1(seed, 84 or Hd.H, 1 or Hd.H, 0 or Hd.H, 0, 0)
        val pub = kp.publicKeyPoint.getEncoded(true)
        return Bech32.encodeSegwitV0(hrp, Hd.hash160(pub))
    }

    override suspend fun getBalance(address: String): BigDecimal = withContext(Dispatchers.IO) {
        val json = JSONObject(Http.get("$apiBase/address/$address"))
        val chainStats = json.getJSONObject("chain_stats")
        val sats = chainStats.getLong("funded_txo_sum") - chainStats.getLong("spent_txo_sum")
        BigDecimal.valueOf(sats).movePointLeft(8)
    }

    override fun isValidAddress(address: String): Boolean =
        address.lowercase().startsWith("${hrp}1") && address.length in 42..62

    override suspend fun send(seed: ByteArray, to: String, amount: BigDecimal): String =
        throw UnsupportedOperationException("Bitcoin sending is not implemented yet")
}
