package com.example.wallet.chain

import com.example.wallet.crypto.Base58
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest

/** Tron addresses: Base58Check of 0x41 + the last 20 bytes of keccak256(uncompressed public key). */
object TronAddress {
    const val PREFIX: Byte = 0x41
    const val SIZE = 21

    /** 21-byte address (0x41 + 20 bytes) of a secp256k1 key. */
    fun fromKeyPair(key: ECKeyPair): ByteArray {
        val pub64 = Numeric.toBytesPadded(key.publicKey, 64)
        return byteArrayOf(PREFIX) + Hash.sha3(pub64).copyOfRange(12, 32)
    }

    fun toBase58(addr21: ByteArray): String {
        require(addr21.size == SIZE && addr21[0] == PREFIX) { "Not a Tron address" }
        return Base58.encode(addr21 + checksum(addr21))
    }

    /** The 21-byte form of a Base58Check address, or null if it is malformed, has a bad checksum or the wrong prefix. */
    fun decode(address: String): ByteArray? {
        val a = address.trim()
        if (a.length != 34 || a[0] != 'T') return null
        val raw = Base58.decode(a) ?: return null
        if (raw.size != SIZE + 4) return null
        val body = raw.copyOfRange(0, SIZE)
        if (body[0] != PREFIX) return null
        if (!checksum(body).contentEquals(raw.copyOfRange(SIZE, SIZE + 4))) return null
        return body
    }

    /** Hex form as returned by the node ("41" + 40 hex digits) -> Base58, or null if malformed. */
    fun hexToBase58(hex: String): String? = runCatching {
        val b = TronTx.fromHex(hex)
        if (b.size == SIZE && b[0] == PREFIX) toBase58(b) else null
    }.getOrNull()

    private fun checksum(payload: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(payload)).copyOfRange(0, 4)
    }
}

/**
 * Builds and signs Tron transactions locally (protobuf, no third-party library). The node is only asked for a recent
 * block reference and to broadcast, so it can never choose the recipient or the amount.
 *
 * Wire layout (Tron `Transaction.raw`): 1 ref_block_bytes, 4 ref_block_hash, 8 expiration, 11 contract,
 * 14 timestamp, 18 fee_limit. Contract types: TransferContract = 1, TriggerSmartContract = 31.
 */
object TronTx {
    private const val TYPE_TRANSFER = 1L
    private const val TYPE_TRIGGER = 31L
    private const val URL_TRANSFER = "type.googleapis.com/protocol.TransferContract"
    private const val URL_TRIGGER = "type.googleapis.com/protocol.TriggerSmartContract"

    /**
     * TRX transfer, `raw_data` bytes. All times are milliseconds. [memo] goes into `raw_data.data` (field 10); Tron burns a
     * small network-set memo fee for it. THORChain swaps read their instructions from there.
     */
    fun transferRaw(
        refBlockBytes: ByteArray, refBlockHash: ByteArray, expirationMs: Long, timestampMs: Long,
        owner: ByteArray, to: ByteArray, amountSun: Long, memo: ByteArray? = null
    ): ByteArray {
        require(owner.size == TronAddress.SIZE && to.size == TronAddress.SIZE) { "Bad address length" }
        require(amountSun > 0) { "Amount must be positive" }
        val transfer = ld(1, owner) + ld(2, to) + vi(3, amountSun)
        return raw(refBlockBytes, refBlockHash, expirationMs, timestampMs, TYPE_TRANSFER, URL_TRANSFER, transfer, null, memo)
    }

    /** Smart-contract call (TRC-20 `transfer`), `raw_data` bytes. [feeLimitSun] caps the TRX burned for energy. */
    fun triggerRaw(
        refBlockBytes: ByteArray, refBlockHash: ByteArray, expirationMs: Long, timestampMs: Long,
        owner: ByteArray, contract: ByteArray, data: ByteArray, feeLimitSun: Long, callValueSun: Long = 0L
    ): ByteArray {
        require(owner.size == TronAddress.SIZE && contract.size == TronAddress.SIZE) { "Bad address length" }
        require(feeLimitSun > 0) { "Fee limit must be positive" }
        require(callValueSun >= 0) { "Negative call value" }
        // call_value (3) is the TRX sent with the call (payable swaps); it is left out when 0
        val trigger = ld(1, owner) + ld(2, contract) + (if (callValueSun > 0) vi(3, callValueSun) else ByteArray(0)) + ld(4, data)
        return raw(refBlockBytes, refBlockHash, expirationMs, timestampMs, TYPE_TRIGGER, URL_TRIGGER, trigger, feeLimitSun, null)
    }

    private fun raw(
        refBytes: ByteArray, refHash: ByteArray, expirationMs: Long, timestampMs: Long,
        type: Long, typeUrl: String, value: ByteArray, feeLimit: Long?, memo: ByteArray?
    ): ByteArray {
        require(refBytes.size == 2 && refHash.size == 8) { "Bad block reference" }
        val any = ld(1, typeUrl.toByteArray(Charsets.US_ASCII)) + ld(2, value)
        val contract = vi(1, type) + ld(2, any)
        return ld(1, refBytes) + ld(4, refHash) + vi(8, expirationMs) +
            (if (memo != null && memo.isNotEmpty()) ld(10, memo) else ByteArray(0)) + ld(11, contract) +
            vi(14, timestampMs) + (if (feeLimit != null) vi(18, feeLimit) else ByteArray(0))
    }

    /** The transaction id: SHA-256 of the raw_data bytes. This is also exactly what gets signed. */
    fun txId(raw: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(raw)

    /** r || s || recoveryId (0 or 1), low-S, over [txId]. */
    fun sign(key: ECKeyPair, raw: ByteArray): ByteArray {
        val hash = txId(raw)
        val sig = key.sign(hash)
        var recId = -1
        for (i in 0..1) {
            val recovered = Sign.recoverFromSignature(i, sig, hash)
            if (recovered != null && recovered == key.publicKey) { recId = i; break }
        }
        check(recId >= 0) { "Could not compute the signature recovery id" }
        return Numeric.toBytesPadded(sig.r, 32) + Numeric.toBytesPadded(sig.s, 32) + byteArrayOf(recId.toByte())
    }

    /** Serialized `Transaction { raw_data = 1; signature = 2 }`, the payload of `/wallet/broadcasthex`. */
    fun signedTransaction(raw: ByteArray, signature: ByteArray): ByteArray {
        require(signature.size == 65) { "Signature must be 65 bytes" }
        return ld(1, raw) + ld(2, signature)
    }

    /** ABI data of `transfer(address,uint256)`: selector + recipient (20 bytes, left-padded) + amount. */
    fun trc20TransferData(to: ByteArray, amount: BigInteger): ByteArray {
        require(to.size == TronAddress.SIZE && to[0] == TronAddress.PREFIX) { "Bad recipient" }
        require(amount.signum() > 0 && amount.bitLength() <= 256) { "Bad amount" }
        return fromHex("a9059cbb") + ByteArray(12) + to.copyOfRange(1, 21) + Numeric.toBytesPadded(amount, 32)
    }

    /** The two ABI words used as `parameter` for `triggerconstantcontract` (no selector). */
    fun trc20TransferParameter(to: ByteArray, amount: BigInteger): String =
        toHex(trc20TransferData(to, amount).copyOfRange(4, 68))

    /** Block reference from a `getnowblock` response: last 2 bytes of the number, bytes 8..16 of the block id. */
    fun blockReference(blockId: String, number: Long): Pair<ByteArray, ByteArray> {
        require(blockId.length == 64) { "Unexpected block id" }
        // A Tron block id starts with the block number (8 bytes, big-endian): a cheap consistency check on the response.
        require(blockId.substring(0, 16).toLongOrNull(16) == number) { "Block id does not match the block number" }
        val bytes = ByteArray(2) { ((number shr (8 * (1 - it))) and 0xff).toByte() }
        return bytes to fromHex(blockId.substring(16, 32))
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun fromHex(s: String): ByteArray {
        require(s.length % 2 == 0) { "Odd-length hex" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // ---- protobuf primitives ----
    private fun varint(value: Long): ByteArray {
        require(value >= 0) { "Negative varint" }
        val o = ByteArrayOutputStream()
        var n = value
        while (true) {
            val b = (n and 0x7f).toInt()
            n = n ushr 7
            if (n != 0L) o.write(b or 0x80) else { o.write(b); break }
        }
        return o.toByteArray()
    }

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())
    private fun ld(field: Int, data: ByteArray) = tag(field, 2) + varint(data.size.toLong()) + data
    private fun vi(field: Int, value: Long) = tag(field, 0) + varint(value)
}

/** Fee arithmetic (pure). Tron burns TRX only for the part of bandwidth/energy that the account does not already have. */
object TronFee {
    const val DEFAULT_BANDWIDTH_PRICE_SUN = 1000L   // sun per byte
    const val DEFAULT_ENERGY_PRICE_SUN = 420L       // sun per energy unit
    const val DEFAULT_CREATE_ACCOUNT_SUN = 1_000_000L
    const val DEFAULT_MEMO_FEE_SUN = 1_000_000L     // a transaction carrying a memo burns this much (network parameter)
    const val SUN_PER_TRX = 1_000_000L
    /** Hard ceiling for the fee_limit of a contract call (TRX burned for energy at most). */
    const val MAX_FEE_LIMIT_SUN = 200 * SUN_PER_TRX

    /** Size charged for bandwidth: the signed transaction plus Tron's fixed 64-byte result allowance. */
    fun bandwidthBytes(rawSize: Int): Int = rawSize + 3 + 2 + 65 + 64 // raw_data header + signature field + result allowance

    fun bandwidthSun(bytes: Int, availableBandwidth: Long, pricePerByteSun: Long): Long =
        if (availableBandwidth >= bytes) 0L else bytes * pricePerByteSun

    fun energySun(energyUsed: Long, availableEnergy: Long, pricePerEnergySun: Long): Long =
        maxOf(0L, energyUsed - maxOf(0L, availableEnergy)) * pricePerEnergySun

    /** fee_limit for a contract call: the estimate plus 25% headroom, at least 1 TRX, never above [MAX_FEE_LIMIT_SUN]. */
    fun feeLimit(estimatedEnergySun: Long): Long {
        val padded = BigInteger.valueOf(estimatedEnergySun).multiply(BigInteger.valueOf(5)).add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(4))
        return minOf(MAX_FEE_LIMIT_SUN, maxOf(SUN_PER_TRX, padded.toLong()))
    }

    fun sunToTrx(sun: Long): BigDecimal = BigDecimal.valueOf(sun).movePointLeft(6)
}

/** JSON shapes of the TronGrid v1 API (pure functions so they can be unit-tested). */
object TronParse {
    /** `data[0]` of `/v1/accounts/{address}`, or null if the account does not exist on chain yet. */
    fun account(json: JSONObject): JSONObject? = json.optJSONArray("data")?.optJSONObject(0)

    fun trxBalanceSun(account: JSONObject?): Long = account?.optLong("balance", 0L) ?: 0L

    /** TRC-20 balances keyed by Base58 contract address. Raw units, never scaled. */
    fun trc20Balances(account: JSONObject?): Map<String, BigInteger> {
        val arr: JSONArray = account?.optJSONArray("trc20") ?: return emptyMap()
        val out = LinkedHashMap<String, BigInteger>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            for (k in o.keys()) {
                val v = o.optString(k, "")
                val n = v.toBigIntegerOrNull() ?: continue
                out[k] = n
            }
        }
        return out
    }

    /** Native TRX transfers from `/v1/accounts/{address}/transactions`. Other contract types are skipped. */
    fun nativeHistory(json: JSONObject, me: String, symbol: String = "TRX"): List<TxRecord> {
        val arr = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<TxRecord>()
        for (i in 0 until arr.length()) {
            val tx = arr.optJSONObject(i) ?: continue
            val c = tx.optJSONObject("raw_data")?.optJSONArray("contract")?.optJSONObject(0) ?: continue
            if (c.optString("type") != "TransferContract") continue
            val v = c.optJSONObject("parameter")?.optJSONObject("value") ?: continue
            val from = TronAddress.hexToBase58(v.optString("owner_address")) ?: continue
            val to = TronAddress.hexToBase58(v.optString("to_address")) ?: continue
            val sun = v.optLong("amount", 0L)
            val ok = (tx.optJSONArray("ret")?.optJSONObject(0)?.optString("contractRet", "SUCCESS") ?: "SUCCESS") == "SUCCESS"
            val ts = tx.optLong("block_timestamp", 0L).takeIf { it > 0 }
            out.add(
                TxRecord(
                    id = tx.optString("txID"),
                    timeSec = ts?.div(1000),
                    direction = direction(from, to, me),
                    amount = TronFee.sunToTrx(sun),
                    symbol = symbol,
                    counterparty = if (from == me) to else from,
                    status = if (!ok) TxStatus.FAILED else if (ts == null) TxStatus.PENDING else TxStatus.CONFIRMED
                )
            )
        }
        return out
    }

    /** TRC-20 transfers from `/v1/accounts/{address}/transactions/trc20`, limited to [known] contracts. */
    fun tokenHistory(json: JSONObject, me: String, known: Map<String, Int>): List<TxRecord> {
        val arr = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<TxRecord>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val info = t.optJSONObject("token_info") ?: continue
            val contract = info.optString("address")
            val decimals = known[contract] ?: continue // unknown/airdropped tokens are not shown
            val raw = t.optString("value").toBigIntegerOrNull() ?: continue
            val from = t.optString("from")
            val to = t.optString("to")
            val ts = t.optLong("block_timestamp", 0L).takeIf { it > 0 }
            out.add(
                TxRecord(
                    id = t.optString("transaction_id"),
                    timeSec = ts?.div(1000),
                    direction = direction(from, to, me),
                    amount = BigDecimal(raw).movePointLeft(decimals),
                    symbol = info.optString("symbol", "?"),
                    counterparty = if (from == me) to else from,
                    status = if (ts == null) TxStatus.PENDING else TxStatus.CONFIRMED
                )
            )
        }
        return out
    }

    private fun direction(from: String, to: String, me: String) = when {
        from == me && to == me -> TxDirection.SELF
        from == me -> TxDirection.OUT
        else -> TxDirection.IN
    }

    /** Human-readable broadcast/simulation error: Tron returns the message as hex-encoded UTF-8. */
    fun errorText(json: JSONObject): String {
        val code = json.optString("code", "")
        val msg = json.optString("message", "")
        val decoded = runCatching { String(TronTx.fromHex(msg), Charsets.UTF_8) }.getOrDefault(msg)
        return listOf(code, decoded).filter { it.isNotBlank() }.joinToString(": ").ifBlank { "unknown error" }
    }
}
