package com.example.wallet.chain

import com.example.wallet.crypto.Base58
import com.example.wallet.crypto.Hd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONObject
import java.math.BigDecimal

/** Solana devnet. Receive + balance only. */
class SolanaProvider(
    private val rpcUrl: String = "https://api.devnet.solana.com"
) : ChainProvider {

    override val chain = Chain.SOL
    override val supportsSend = false

    // m/44'/501'/0'/0' (SLIP-10 ed25519, same path Phantom uses for account 0)
    override fun deriveAddress(seed: ByteArray): String {
        val sk = Hd.ed25519PrivateKey(seed, 44, 501, 0, 0)
        try {
            val pk = ByteArray(32)
            Ed25519.generatePublicKey(sk, 0, pk, 0)
            return Base58.encode(pk)
        } finally {
            sk.fill(0)
        }
    }

    override suspend fun getBalance(address: String): BigDecimal = withContext(Dispatchers.IO) {
        val body = """{"jsonrpc":"2.0","id":1,"method":"getBalance","params":["$address"]}"""
        val json = JSONObject(Http.postJson(rpcUrl, body))
        if (json.has("error")) throw IllegalStateException(json.getJSONObject("error").optString("message"))
        val lamports = json.getJSONObject("result").getLong("value")
        BigDecimal.valueOf(lamports).movePointLeft(9)
    }

    override fun isValidAddress(address: String): Boolean = Base58.decode(address)?.size == 32

    override suspend fun send(seed: ByteArray, to: String, amount: BigDecimal): String =
        throw UnsupportedOperationException("Solana sending is not implemented yet")
}
