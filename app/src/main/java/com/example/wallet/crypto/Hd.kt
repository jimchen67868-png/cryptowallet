package com.example.wallet.crypto

import org.web3j.crypto.Bip32ECKeyPair
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.RIPEMD160Digest

/** Key derivation helpers. */
object Hd {
    const val H: Int = Bip32ECKeyPair.HARDENED_BIT

    /** BIP-32 secp256k1 derivation (BTC, ETH). Use `44 or Hd.H` for hardened indices. */
    fun secp256k1(seed: ByteArray, vararg path: Int): Bip32ECKeyPair {
        val master = Bip32ECKeyPair.generateKeyPair(seed)
        return Bip32ECKeyPair.deriveKeyPair(master, path)
    }

    /** SLIP-0010 ed25519 derivation (Solana). All indices are treated as hardened. */
    fun ed25519PrivateKey(seed: ByteArray, vararg path: Int): ByteArray {
        var i = hmacSha512("ed25519 seed".toByteArray(), seed)
        var key = i.copyOfRange(0, 32)
        var chain = i.copyOfRange(32, 64)
        for (index in path) {
            val idx = index or Int.MIN_VALUE
            val data = ByteArray(37)
            System.arraycopy(key, 0, data, 1, 32)
            data[33] = (idx ushr 24).toByte()
            data[34] = (idx ushr 16).toByte()
            data[35] = (idx ushr 8).toByte()
            data[36] = idx.toByte()
            i = hmacSha512(chain, data)
            key = i.copyOfRange(0, 32)
            chain = i.copyOfRange(32, 64)
        }
        return key
    }

    fun hash160(data: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256").digest(data)
        val d = RIPEMD160Digest()
        d.update(sha, 0, sha.size)
        val out = ByteArray(20)
        d.doFinal(out, 0)
        return out
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }
}
