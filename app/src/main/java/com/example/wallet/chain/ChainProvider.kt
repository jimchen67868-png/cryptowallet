package com.example.wallet.chain

import java.math.BigDecimal

enum class Chain(val symbol: String, val label: String) {
    ETH("ETH", "Ethereum (Sepolia testnet)"),
    BTC("BTC", "Bitcoin (testnet)"),
    SOL("SOL", "Solana (devnet)")
}

/** A fungible token balance (ERC-20 contract or SPL mint). */
data class TokenBalance(
    val id: String,          // contract address or mint
    val symbol: String,
    val balance: BigDecimal,
    val decimals: Int,
    val removable: Boolean = false
)

/** One implementation per blockchain. Add new coins by implementing this. */
interface ChainProvider {
    val chain: Chain
    val supportsSend: Boolean

    /** True if [sendToken] is implemented (ERC-20 / SPL transfers). */
    val supportsTokenSend: Boolean get() = false

    /** True if the user can add token contracts manually (chains without free token discovery). */
    val supportsCustomTokens: Boolean get() = false

    /** Derives the receive address from the 64-byte BIP-39 seed. */
    fun deriveAddress(seed: ByteArray): String

    suspend fun getBalance(address: String): BigDecimal

    /** Token balances for [address]. Chains without token support return an empty list. */
    suspend fun getTokenBalances(address: String): List<TokenBalance> = emptyList()

    /** Registers a custom token by contract address. Throws if it isn't a valid token. */
    suspend fun addToken(contract: String) {
        throw UnsupportedOperationException("Custom tokens are not supported on ${chain.symbol}")
    }

    fun removeToken(id: String) {}

    fun isValidAddress(address: String): Boolean

    /**
     * Estimated network fee in the chain's main unit, or null if unknown.
     * Throws if funds are insufficient so the UI can warn before confirming.
     */
    suspend fun estimateFee(address: String, to: String, amount: BigDecimal): BigDecimal? = null

    /** Signs and broadcasts. Returns the transaction id/hash. */
    suspend fun send(seed: ByteArray, to: String, amount: BigDecimal): String

    /** Fee (in the chain's native coin) for sending [amount] of [token]. Throws if it would fail. */
    suspend fun estimateTokenFee(address: String, token: TokenBalance, to: String, amount: BigDecimal): BigDecimal? = null

    /** Sends a token. Returns the transaction id/hash. */
    suspend fun sendToken(seed: ByteArray, token: TokenBalance, to: String, amount: BigDecimal): String {
        throw UnsupportedOperationException("Token transfers are not supported on ${chain.symbol}")
    }
}
