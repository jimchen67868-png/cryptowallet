package com.example.wallet.crypto

/** Bech32 / Bech32m for SegWit addresses (BIP-173, BIP-350). */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    private const val BECH32M_CONST = 0x2bc830a3

    class Segwit(val version: Int, val program: ByteArray)

    /** Encodes a witness v0 program (P2WPKH = 20 bytes, P2WSH = 32 bytes). */
    fun encodeSegwitV0(hrp: String, program: ByteArray): String {
        val data = listOf(0) + convertBits(program, 8, 5)
        val checksum = createChecksum(hrp, data)
        return hrp + "1" + (data + checksum).map { CHARSET[it] }.joinToString("")
    }

    /** Returns null if the address is malformed, has a bad checksum or the wrong hrp. */
    fun decodeSegwit(hrp: String, address: String): Segwit? {
        if (address != address.lowercase() && address != address.uppercase()) return null
        val a = address.lowercase()
        val pos = a.lastIndexOf('1')
        if (pos < 1 || pos + 7 > a.length || a.length > 90) return null
        if (a.substring(0, pos) != hrp) return null
        val data = ArrayList<Int>()
        for (c in a.substring(pos + 1)) {
            val i = CHARSET.indexOf(c)
            if (i < 0) return null
            data.add(i)
        }
        val version = data[0]
        if (version > 16) return null
        val expected = if (version == 0) 1 else BECH32M_CONST
        if (polymod(hrpExpand(hrp) + data) != expected) return null
        val program = from5Bits(data.subList(1, data.size - 6)) ?: return null
        return Segwit(version, program)
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if (((b ushr i) and 1) == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun createChecksum(hrp: String, data: List<Int>): List<Int> {
        val mod = polymod(hrpExpand(hrp) + data + List(6) { 0 }) xor 1
        return (0 until 6).map { (mod ushr (5 * (5 - it))) and 31 }
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int): List<Int> {
        var acc = 0
        var bits = 0
        val out = mutableListOf<Int>()
        val maxv = (1 shl to) - 1
        val maxAcc = (1 shl (from + to - 1)) - 1
        for (b in data) {
            acc = ((acc shl from) or (b.toInt() and 0xff)) and maxAcc
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        return out
    }

    /** 5-bit groups -> bytes, no padding allowed (strict). */
    private fun from5Bits(data: List<Int>): ByteArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (v in data) {
            acc = ((acc shl 5) or v) and 0xfff
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((acc ushr bits) and 0xff).toByte())
            }
        }
        if (bits >= 5 || ((acc shl (8 - bits)) and 0xff) != 0) return null
        return out.toByteArray()
    }
}
