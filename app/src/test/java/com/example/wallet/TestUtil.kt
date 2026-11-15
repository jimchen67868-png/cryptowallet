package com.example.wallet

import com.example.wallet.crypto.SeedGenerator

/** The standard BIP-39 test mnemonic (all-zero entropy). */
const val ABANDON =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** 64-byte BIP-39 seed of [ABANDON] with an empty passphrase. */
fun abandonSeed(): ByteArray = SeedGenerator.toSeed(ABANDON.split(" "))
