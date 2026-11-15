package com.example.wallet.chain

import com.example.wallet.crypto.Base58
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class SolMeta(val key: ByteArray, val signer: Boolean, val writable: Boolean)
class SolIx(val programId: ByteArray, val accounts: List<SolMeta>, val data: ByteArray)

/** Minimal legacy-format Solana transaction builder/signer. */
object SolanaTx {
    val SYSTEM_PROGRAM: ByteArray = Base58.decode("11111111111111111111111111111111")!!
    val ATA_PROGRAM: ByteArray = Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")!!

    /** SystemProgram.Transfer: [u32 LE instruction index = 2, u64 LE lamports]. */
    fun systemTransfer(from: ByteArray, to: ByteArray, lamports: Long): SolIx = SolIx(
        SYSTEM_PROGRAM,
        listOf(SolMeta(from, signer = true, writable = true), SolMeta(to, signer = false, writable = true)),
        le32(2) + le64(lamports)
    )

    /** Associated Token Account program, CreateIdempotent (instruction 1). */
    fun createAssociatedTokenAccountIdempotent(
        payer: ByteArray, ata: ByteArray, owner: ByteArray, mint: ByteArray, tokenProgram: ByteArray
    ): SolIx = SolIx(
        ATA_PROGRAM,
        listOf(
            SolMeta(payer, signer = true, writable = true),
            SolMeta(ata, signer = false, writable = true),
            SolMeta(owner, signer = false, writable = false),
            SolMeta(mint, signer = false, writable = false),
            SolMeta(SYSTEM_PROGRAM, signer = false, writable = false),
            SolMeta(tokenProgram, signer = false, writable = false)
        ),
        byteArrayOf(1)
    )

    /** Token / Token-2022 TransferChecked (instruction 12): [12, amount u64 LE, decimals]. */
    fun tokenTransferChecked(
        source: ByteArray, mint: ByteArray, dest: ByteArray, owner: ByteArray,
        tokenProgram: ByteArray, amount: Long, decimals: Int
    ): SolIx = SolIx(
        tokenProgram,
        listOf(
            SolMeta(source, signer = false, writable = true),
            SolMeta(mint, signer = false, writable = false),
            SolMeta(dest, signer = false, writable = true),
            SolMeta(owner, signer = true, writable = false)
        ),
        byteArrayOf(12) + le64(amount) + byteArrayOf(decimals.toByte())
    )

    /** The associated token account address for [owner] and [mint]. */
    fun associatedTokenAddress(owner: ByteArray, mint: ByteArray, tokenProgram: ByteArray): ByteArray =
        findProgramAddress(listOf(owner, tokenProgram, mint), ATA_PROGRAM)

    private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xff).toByte() }
    private fun le64(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xff).toByte() }


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
