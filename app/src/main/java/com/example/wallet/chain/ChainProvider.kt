package com.example.wallet.chain

import java.math.BigDecimal

enum class Chain(val symbol: String, val label: String) {
    ETH("ETH", "Ethereum (Sepolia testnet)"),
    BTC("BTC", "Bitcoin (testnet)"),
    SOL("SOL", "Solana (devnet)")
}

/** One implementation per blockchain. Add new coins by implementing this. */
interface ChainProvider {
    val chain: Chain
    val supportsSend: Boolean

    /** Derives the receive address from the 64-byte BIP-39 seed. */
    fun deriveAddress(seed: ByteArray): String

    suspend fun getBalance(address: String): BigDecimal

    fun isValidAddress(address: String): Boolean

    /** Signs and broadcasts. Returns the transaction id/hash. */
    suspend fun send(seed: ByteArray, to: String, amount: BigDecimal): String
}
