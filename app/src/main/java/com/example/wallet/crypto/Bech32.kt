package com.example.wallet.crypto

/** Minimal Bech32 encoder for native SegWit v0 (P2WPKH) addresses. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    fun encodeSegwitV0(hrp: String, program: ByteArray): String {
        val data = listOf(0) + convertBits(program, 8, 5)
        val checksum = createChecksum(hrp, data)
        return hrp + "1" + (data + checksum).map { CHARSET[it] }.joinToString("")
    }

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
        val expanded = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }
        val mod = polymod(expanded + data + List(6) { 0 }) xor 1
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
}
