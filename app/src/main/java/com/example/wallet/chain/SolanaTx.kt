package com.example.wallet.chain

import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class SolMeta(val key: ByteArray, val signer: Boolean, val writable: Boolean)
class SolIx(val programId: ByteArray, val accounts: List<SolMeta>, val data: ByteArray)

/** Minimal legacy-format Solana transaction builder/signer. */
object SolanaTx {

    /** Serializes a legacy message. [payer] is the fee payer and first signer. */
    fun buildMessage(payer: ByteArray, ixs: List<SolIx>, blockhash: ByteArray): ByteArray {
        require(payer.size == 32 && blockhash.size == 32)

        class Entry(val key: ByteArray, var signer: Boolean, var writable: Boolean)
        val map = LinkedHashMap<String, Entry>()
        fun add(key: ByteArray, signer: Boolean, writable: Boolean) {
            val id = hex(key)
            val e = map[id]
            if (e == null) map[id] = Entry(key, signer, writable)
            else { e.signer = e.signer || signer; e.writable = e.writable || writable }
        }

        add(payer, signer = true, writable = true)
        for (ix in ixs) {
            ix.accounts.forEach { add(it.key, it.signer, it.writable) }
            add(ix.programId, signer = false, writable = false)
        }

        val all = map.values.toList()
        val ordered = all.filter { it.signer && it.writable } +
            all.filter { it.signer && !it.writable } +
            all.filter { !it.signer && it.writable } +
            all.filter { !it.signer && !it.writable }
        val index = HashMap<String, Int>()
        ordered.forEachIndexed { i, e -> index[hex(e.key)] = i }

        val out = ByteArrayOutputStream()
        out.write(ordered.count { it.signer })                    // required signatures
        out.write(ordered.count { it.signer && !it.writable })    // readonly signed
        out.write(ordered.count { !it.signer && !it.writable })   // readonly unsigned
        out.write(shortVec(ordered.size))
        ordered.forEach { out.write(it.key) }
        out.write(blockhash)
        out.write(shortVec(ixs.size))
        for (ix in ixs) {
            out.write(index.getValue(hex(ix.programId)))
            out.write(shortVec(ix.accounts.size))
            ix.accounts.forEach { out.write(index.getValue(hex(it.key))) }
            out.write(shortVec(ix.data.size))
            out.write(ix.data)
        }
        return out.toByteArray()
    }

    /** Ed25519 signature over the serialized message. */
    fun sign(sk: ByteArray, pk: ByteArray, message: ByteArray): ByteArray {
        val sig = ByteArray(64)
        Ed25519.sign(sk, 0, pk, 0, message, 0, message.size, sig, 0)
        return sig
    }

    /** Wire format for a single-signature transaction. */
    fun serialize(signature: ByteArray, message: ByteArray): ByteArray =
        shortVec(1) + signature + message

    /** Program Derived Address: first bump (255 down) whose hash is NOT a valid curve point. */
    fun findProgramAddress(seeds: List<ByteArray>, programId: ByteArray): ByteArray {
        for (bump in 255 downTo 0) {
            val md = MessageDigest.getInstance("SHA-256")
            seeds.forEach { md.update(it) }
            md.update(byteArrayOf(bump.toByte()))
            md.update(programId)
            md.update("ProgramDerivedAddress".toByteArray())
            val h = md.digest()
            if (!Ed25519.validatePublicKeyPartial(h, 0)) return h
        }
        throw IllegalStateException("Could not derive program address")
    }

    private fun shortVec(n: Int): ByteArray =
        if (n < 0x80) byteArrayOf(n.toByte())
        else byteArrayOf(((n and 0x7f) or 0x80).toByte(), (n shr 7).toByte())

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
