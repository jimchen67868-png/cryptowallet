package com.example.wallet.chain

import java.math.BigDecimal

/** [symbol] is the native coin. Base and Arbitrum use ETH as gas, so their symbol is "ETH". */
enum class Chain(val symbol: String, val label: String, val isEvm: Boolean = false) {
    ETH("ETH", "Ethereum", true),
    BTC("BTC", "Bitcoin"),
    SOL("SOL", "Solana"),
    BASE("ETH", "Base", true),
    ARB("ETH", "Arbitrum", true),
    POL("POL", "Polygon", true),
    BNB("BNB", "BNB Chain", true)
}

/** A fungible token balance (ERC-20 contract or SPL mint). */
data class TokenBalance(
    val id: String,          // contract address or mint
    val symbol: String,
    val balance: BigDecimal,
    val decimals: Int,
    val removable: Boolean = false
)

enum class TxDirection { IN, OUT, SELF }
enum class TxStatus { CONFIRMED, PENDING, FAILED }

/** One line of transaction history. [amount] is always positive; [direction] gives the sign. */
data class TxRecord(
    val id: String,
    val timeSec: Long?,          // null while pending / unknown
    val direction: TxDirection,
    val amount: BigDecimal,
    val symbol: String,
    val counterparty: String?,
    val status: TxStatus
)

/** One implementation per blockchain. Add new coins by implementing this. */
interface ChainProvider {
    val chain: Chain
    val supportsSend: Boolean

    /** True when this provider points at a test network (coins have no real value). */
    val isTestnet: Boolean

    /** Human-readable network, e.g. "Mainnet" or "Sepolia testnet". */
    val networkName: String

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

    /** Recent transactions for [address] (newest data the public APIs return). */
    suspend fun getHistory(address: String): List<TxRecord> = emptyList()

    /** True if [quoteSwap] / [executeSwap] work on this network (EVM mainnets only). */
    val supportsSwap: Boolean get() = false

    /** Assets the user can swap between on this network (native coin + curated tokens). */
    fun swapAssets(): List<SwapAsset> = emptyList()

    suspend fun quoteSwap(userAddress: String, from: SwapAsset, to: SwapAsset, amount: BigDecimal): SwapQuote {
        throw UnsupportedOperationException("Swaps are not supported on ${chain.label}")
    }

    /** Approves (if needed), validates the aggregator's transaction, then signs and broadcasts the swap. */
    suspend fun executeSwap(seed: ByteArray, quote: SwapQuote): SwapResult {
        throw UnsupportedOperationException("Swaps are not supported on ${chain.label}")
    }

    /** Block explorer page for a transaction id. */
    fun explorerUrl(txId: String): String

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
