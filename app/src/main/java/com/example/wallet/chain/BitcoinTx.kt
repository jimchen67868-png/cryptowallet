package com.example.wallet.chain

import org.web3j.crypto.ECKeyPair
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.math.ceil

class Utxo(val txid: String, val vout: Int, val value: Long)
class TxOut(val value: Long, val script: ByteArray)

/** Builds and signs native SegWit (P2WPKH) transactions per BIP-141 / BIP-143. */
object BitcoinTx {
    const val DEFAULT_SEQUENCE = 0xfffffffdL // opt-in RBF
    private const val SIGHASH_ALL = 1L

    /**
     * BIP-143 signature hash for input [index], which must be a P2WPKH output paying [pubKeyHash].
     * [Utxo.txid] is in the usual display order (reversed relative to the wire format).
     */
    fun sighashP2wpkh(
        version: Long,
        inputs: List<Utxo>,
        sequences: List<Long>,
        outputs: List<TxOut>,
        index: Int,
        pubKeyHash: ByteArray,
        locktime: Long
    ): ByteArray {
        require(sequences.size == inputs.size && index in inputs.indices && pubKeyHash.size == 20)
        val outpoints = inputs.map { outpoint(it) }
        val hashPrevouts = dsha(concat(outpoints))
        val hashSequence = dsha(concat(sequences.map { le32(it) }))
        val hashOutputs = dsha(concat(outputs.map { serializeOutput(it) }))
        // scriptCode for P2WPKH: OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG, length-prefixed
        val scriptCode = byteArrayOf(0x19, 0x76, 0xa9.toByte(), 0x14) + pubKeyHash +
            byteArrayOf(0x88.toByte(), 0xac.toByte())
        val preimage = concat(
            listOf(
                le32(version), hashPrevouts, hashSequence, outpoint(inputs[index]), scriptCode,
                le64(inputs[index].value), le32(sequences[index]), hashOutputs, le32(locktime), le32(SIGHASH_ALL)
            )
        )
        return dsha(preimage)
    }

    /** Deterministic (RFC 6979), low-S ECDSA signature, DER-encoded, with the SIGHASH_ALL byte appended. */
    fun signHash(key: ECKeyPair, hash: ByteArray): ByteArray {
        val sig = key.sign(hash)
        return derEncode(sig.r, sig.s) + byteArrayOf(SIGHASH_ALL.toByte())
    }

    /**
     * All inputs must be P2WPKH outputs belonging to [key].
     * Returns the fully signed raw transaction (witness serialization).
     */
    fun signP2wpkh(
        inputs: List<Utxo>,
        outputs: List<TxOut>,
        key: ECKeyPair,
        pubKey: ByteArray,
        pubKeyHash: ByteArray,
        version: Long = 2L,
        locktime: Long = 0L,
        sequences: List<Long> = inputs.map { DEFAULT_SEQUENCE }
    ): ByteArray {
        require(pubKey.size == 33 && pubKeyHash.size == 20)
        require(inputs.isNotEmpty() && outputs.isNotEmpty())
        require(sequences.size == inputs.size)

        val witnesses = inputs.indices.map { i ->
            val sig = signHash(key, sighashP2wpkh(version, inputs, sequences, outputs, i, pubKeyHash, locktime))
            concat(listOf(byteArrayOf(2), varInt(sig.size), sig, varInt(pubKey.size), pubKey))
        }

        val w = ByteArrayOutputStream()
        w.write(le32(version))
        w.write(byteArrayOf(0x00, 0x01)) // segwit marker + flag
        w.write(varInt(inputs.size))
        inputs.forEachIndexed { i, u ->
            w.write(outpoint(u))
            w.write(0) // empty scriptSig
            w.write(le32(sequences[i]))
        }
        w.write(varInt(outputs.size))
        outputs.forEach { w.write(serializeOutput(it)) }
        witnesses.forEach { w.write(it) }
        w.write(le32(locktime))
        return w.toByteArray()
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun outpoint(u: Utxo): ByteArray = hexToBytes(u.txid).reversedArray() + le32(u.vout.toLong())
    private fun serializeOutput(o: TxOut): ByteArray = le64(o.value) + varInt(o.script.size) + o.script

    private fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun dsha(b: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(b))
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        parts.forEach { o.write(it) }
        return o.toByteArray()
    }

    private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xff).toByte() }
    private fun le64(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xff).toByte() }

    private fun varInt(n: Int): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        else -> byteArrayOf(0xfd.toByte(), (n and 0xff).toByte(), ((n shr 8) and 0xff).toByte())
    }

    private fun derEncode(r: BigInteger, s: BigInteger): ByteArray {
        fun int(v: BigInteger): ByteArray {
            val b = v.toByteArray() // minimal two's complement, as DER requires
            return byteArrayOf(0x02, b.size.toByte()) + b
        }
        val body = int(r) + int(s)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

/** Largest-first coin selection with change handling. Pure function: no network. */
object CoinSelection {
    const val DUST = 546L

    class Plan(val inputs: List<Utxo>, val fee: Long, val change: Long)

    /** Virtual size: 11 overhead + 68 per P2WPKH input + outputs (8 value + 1 length + script). */
    fun vsize(inputs: Int, destScriptSize: Int, withChange: Boolean): Int =
        11 + 68 * inputs + (9 + destScriptSize) + (if (withChange) 31 else 0)

    fun select(utxos: List<Utxo>, sats: Long, destScriptSize: Int, feeRate: Double, dust: Long = DUST): Plan {
        require(sats > 0) { "Amount must be positive" }
        require(feeRate > 0) { "Fee rate must be positive" }
        val sorted = utxos.sortedByDescending { it.value }
        if (sorted.isEmpty()) throw IllegalStateException("No confirmed funds available")

        fun fee(inputs: Int, withChange: Boolean) =
            ceil(feeRate * vsize(inputs, destScriptSize, withChange)).toLong()

        val selected = mutableListOf<Utxo>()
        var total = 0L
        for (u in sorted) {
            selected.add(u)
            total += u.value
            val feeWithChange = fee(selected.size, true)
            if (total >= sats + feeWithChange) {
                val change = total - sats - feeWithChange
                return if (change >= dust) Plan(selected.toList(), feeWithChange, change)
                else Plan(selected.toList(), total - sats, 0) // dust change goes to the miner
            }
            if (total >= sats + fee(selected.size, false)) {
                return Plan(selected.toList(), total - sats, 0)
            }
        }
        throw IllegalStateException("Insufficient confirmed funds (amount + network fee)")
    }
}
