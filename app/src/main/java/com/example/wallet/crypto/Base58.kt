package com.example.wallet.crypto

import java.math.BigInteger

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        var n = BigInteger(1, input)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val qr = n.divideAndRemainder(BASE)
            sb.append(ALPHABET[qr[1].toInt()])
            n = qr[0]
        }
        repeat(zeros) { sb.append('1') }
        return sb.reverse().toString()
    }

    /** Returns null if the string contains invalid characters. */
    fun decode(s: String): ByteArray? {
        var n = BigInteger.ZERO
        for (c in s) {
            val d = ALPHABET.indexOf(c)
            if (d < 0) return null
            n = n.multiply(BASE).add(BigInteger.valueOf(d.toLong()))
        }
        val raw = n.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()
        val zeros = s.takeWhile { it == '1' }.length
        return ByteArray(zeros) + raw
    }
}
