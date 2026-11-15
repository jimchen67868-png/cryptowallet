package com.example.wallet.chain

import com.example.wallet.crypto.Base58
import com.example.wallet.crypto.Bech32
import com.example.wallet.crypto.Hd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Bitcoin, native SegWit (P2WPKH, BIP-84), single receive/change address at index 0.
 * Defaults to testnet; for mainnet use hrp "bc", coinType 0, versions 0x00/0x05 and a mainnet API.
 * Uses the mempool.space REST API for UTXOs, fee rates and broadcasting.
 */
class BitcoinProvider(
    private val apiBase: String = "https://mempool.space/testnet/api",
    private val hrp: String = "tb",
    private val coinType: Int = 1,
    private val p2pkhVersion: Int = 0x6f,
    private val p2shVersion: Int = 0xc4,
    private val explorerTxUrl: String = "https://mempool.space/testnet/tx/"
) : ChainProvider {

    override val chain = Chain.BTC
    override val supportsSend = true
    override val isTestnet = hrp != "bc"
    override val networkName = if (isTestnet) "Testnet" else "Mainnet"

    // m/84'/coinType'/0'/0/0
    private fun keyPair(seed: ByteArray) =
        Hd.secp256k1(seed, 84 or Hd.H, coinType or Hd.H, 0 or Hd.H, 0, 0)

    override fun deriveAddress(seed: ByteArray): String {
        val pub = keyPair(seed).publicKeyPoint.getEncoded(true)
        return Bech32.encodeSegwitV0(hrp, Hd.hash160(pub))
    }

    override suspend fun getBalance(address: String): BigDecimal = withContext(Dispatchers.IO) {
        val json = JSONObject(Http.get("$apiBase/address/$address"))
        val s = json.getJSONObject("chain_stats")
        BigDecimal.valueOf(s.getLong("funded_txo_sum") - s.getLong("spent_txo_sum")).movePointLeft(8)
    }

    override fun isValidAddress(address: String): Boolean =
        runCatching { scriptForAddress(address.trim()) != null }.getOrDefault(false)

    override suspend fun estimateFee(address: String, to: String, amount: BigDecimal): BigDecimal =
        withContext(Dispatchers.IO) {
            val script = scriptForAddress(to.trim()) ?: throw IllegalArgumentException("Invalid address")
            BigDecimal.valueOf(plan(address, script, toSats(amount)).fee).movePointLeft(8)
        }

    override suspend fun send(seed: ByteArray, to: String, amount: BigDecimal): String =
        withContext(Dispatchers.IO) {
            val kp = keyPair(seed)
            val pub = kp.publicKeyPoint.getEncoded(true)
            val pubHash = Hd.hash160(pub)
            val myAddress = Bech32.encodeSegwitV0(hrp, pubHash)
            val myScript = byteArrayOf(0x00, 0x14) + pubHash

            val destScript = scriptForAddress(to.trim()) ?: throw IllegalArgumentException("Invalid address")
            val sats = toSats(amount)
            val plan = plan(myAddress, destScript, sats)

            val outputs = buildList {
                add(TxOut(sats, destScript))
                if (plan.change > 0) add(TxOut(plan.change, myScript)) // change returns to our address
            }
            // Safety: inputs - outputs must equal exactly the planned fee
            val inTotal = plan.inputs.sumOf { it.value }
            val outTotal = outputs.sumOf { it.value }
            check(inTotal - outTotal == plan.fee) { "Fee mismatch, aborting" }

            val raw = BitcoinTx.signP2wpkh(plan.inputs, outputs, kp, pub, pubHash)
            val txid = Http.postText("$apiBase/tx", BitcoinTx.toHex(raw)).trim()
            check(txid.length == 64) { "Unexpected broadcast response: ${txid.take(100)}" }
            txid
        }

    // ---- history ----

    override fun explorerUrl(txId: String) = explorerTxUrl + txId

    /** Uses mempool.space: up to 25 unconfirmed + 25 confirmed transactions. */
    override suspend fun getHistory(address: String): List<TxRecord> = withContext(Dispatchers.IO) {
        val arr = JSONArray(Http.get("$apiBase/address/$address/txs"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { tx ->
            var received = 0L   // outputs paying us
            var spent = 0L      // our coins used as inputs
            var firstOther: String? = null  // first recipient that isn't us
            var firstFrom: String? = null   // first sender that isn't us

            val vout = tx.getJSONArray("vout")
            for (i in 0 until vout.length()) {
                val o = vout.getJSONObject(i)
                val a = o.optString("scriptpubkey_address", "")
                if (a == address) received += o.optLong("value")
                else if (firstOther == null && a.isNotEmpty()) firstOther = a
            }
            val vin = tx.getJSONArray("vin")
            for (i in 0 until vin.length()) {
                val prev = vin.getJSONObject(i).optJSONObject("prevout") ?: continue
                val a = prev.optString("scriptpubkey_address", "")
                if (a == address) spent += prev.optLong("value")
                else if (firstFrom == null && a.isNotEmpty()) firstFrom = a
            }

            val fee = tx.optLong("fee")
            val status = tx.optJSONObject("status")
            val confirmed = status?.optBoolean("confirmed") ?: false
            val time = if (confirmed) status?.optLong("block_time") else null

            val sent = spent - received - fee // value that left to other people
            val direction: TxDirection
            val sats: Long
            val counterparty: String?
            when {
                spent == 0L -> { direction = TxDirection.IN; sats = received; counterparty = firstFrom }
                sent > 0 -> { direction = TxDirection.OUT; sats = sent; counterparty = firstOther }
                else -> { direction = TxDirection.SELF; sats = 0; counterparty = null }
            }
            TxRecord(
                id = tx.getString("txid"),
                timeSec = time,
                direction = direction,
                amount = BigDecimal.valueOf(sats).movePointLeft(8),
                symbol = "BTC",
                counterparty = counterparty,
                status = if (confirmed) TxStatus.CONFIRMED else TxStatus.PENDING
            )
        }
    }

    // ---- internals ----

    private fun toSats(amount: BigDecimal): Long {
        val sats = try {
            amount.movePointRight(8).toBigIntegerExact().toLong()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Bitcoin amounts allow at most 8 decimal places")
        }
        require(sats >= DUST) { "Amount is below the dust limit ($DUST sats)" }
        return sats
    }

    private fun fetchUtxos(address: String): List<Utxo> {
        val arr = JSONArray(Http.get("$apiBase/address/$address/utxo"))
        val list = ArrayList<Utxo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getJSONObject("status").getBoolean("confirmed")) { // skip unconfirmed
                list.add(Utxo(o.getString("txid"), o.getInt("vout"), o.getLong("value")))
            }
        }
        return list
    }

    /** sat/vB for roughly "next half hour" confirmation. */
    private fun feeRate(): Double {
        val j = JSONObject(Http.get("$apiBase/v1/fees/recommended"))
        return maxOf(1.0, j.getDouble("halfHourFee"))
    }

    private fun plan(address: String, destScript: ByteArray, sats: Long): CoinSelection.Plan =
        CoinSelection.select(fetchUtxos(address), sats, destScript.size, feeRate())

    /** Output script for any supported destination address, or null if invalid. */
    internal fun scriptForAddress(address: String): ByteArray? {
        if (address.lowercase().startsWith("${hrp}1")) {
            val seg = Bech32.decodeSegwit(hrp, address) ?: return null
            val len = seg.program.size
            val ok = (seg.version == 0 && (len == 20 || len == 32)) || (seg.version == 1 && len == 32)
            if (!ok) return null
            val op = if (seg.version == 0) 0x00 else 0x50 + seg.version
            return byteArrayOf(op.toByte(), len.toByte()) + seg.program
        }
        // Legacy Base58Check (P2PKH / P2SH)
        val raw = Base58.decode(address) ?: return null
        if (raw.size != 25) return null
        val payload = raw.copyOfRange(0, 21)
        val md = MessageDigest.getInstance("SHA-256")
        val check = md.digest(md.digest(payload)).copyOfRange(0, 4)
        if (!check.contentEquals(raw.copyOfRange(21, 25))) return null
        val hash = payload.copyOfRange(1, 21)
        return when (payload[0].toInt() and 0xff) {
            p2pkhVersion -> byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())
            p2shVersion -> byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
            else -> null
        }
    }

    private companion object {
        const val DUST = CoinSelection.DUST
    }
}
