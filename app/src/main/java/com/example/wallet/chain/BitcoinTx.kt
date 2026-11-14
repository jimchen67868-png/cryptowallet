package com.example.wallet.chain

import org.web3j.crypto.ECKeyPair
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

class Utxo(val txid: String, val vout: Int, val value: Long)
class TxOut(val value: Long, val script: ByteArray)

/** Builds and signs native SegWit (P2WPKH) transactions per BIP-141 / BIP-143. */
object BitcoinTx {
    private const val VERSION = 2L
    private const val SEQUENCE = 0xfffffffdL // opt-in RBF
    private const val SIGHASH_ALL = 1L

    /**
     * All inputs must be P2WPKH outputs belonging to [key].
     * Returns the fully signed raw transaction (witness serialization).
     */
    fun signP2wpkh(
        inputs: List<Utxo>,
        outputs: List<TxOut>,
        key: ECKeyPair,
        pubKey: ByteArray,
        pubKeyHash: ByteArray
    ): ByteArray {
        require(pubKey.size == 33 && pubKeyHash.size == 20)
        require(inputs.isNotEmpty() && outputs.isNotEmpty())

        val outpoints = inputs.map { hexToBytes(it.txid).reversedArray() + le32(it.vout.toLong()) }
        val outputsSer = outputs.map { le64(it.value) + varInt(it.script.size) + it.script }

        val hashPrevouts = dsha(concat(outpoints))
        val hashSequence = dsha(concat(inputs.map { le32(SEQUENCE) }))
        val hashOutputs = dsha(concat(outputsSer))
        // scriptCode for P2WPKH: OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG, length-prefixed
        val scriptCode = byteArrayOf(0x19, 0x76, 0xa9.toByte(), 0x14) + pubKeyHash +
            byteArrayOf(0x88.toByte(), 0xac.toByte())

        val witnesses = inputs.mapIndexed { i, u ->
            val preimage = concat(
                listOf(
                    le32(VERSION), hashPrevouts, hashSequence, outpoints[i], scriptCode,
                    le64(u.value), le32(SEQUENCE), hashOutputs, le32(0), le32(SIGHASH_ALL)
                )
            )
            val sig = key.sign(dsha(preimage)) // deterministic (RFC6979), low-S
            val der = derEncode(sig.r, sig.s) + byteArrayOf(SIGHASH_ALL.toByte())
            concat(listOf(byteArrayOf(2), varInt(der.size), der, varInt(pubKey.size), pubKey))
        }

        val w = ByteArrayOutputStream()
        w.write(le32(VERSION))
        w.write(byteArrayOf(0x00, 0x01)) // segwit marker + flag
        w.write(varInt(inputs.size))
        for (op in outpoints) {
            w.write(op)
            w.write(0) // empty scriptSig
            w.write(le32(SEQUENCE))
        }
        w.write(varInt(outputs.size))
        outputsSer.forEach { w.write(it) }
        witnesses.forEach { w.write(it) }
        w.write(le32(0)) // locktime
        return w.toByteArray()
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

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
