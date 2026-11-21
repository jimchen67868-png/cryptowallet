package com.example.wallet.data

import com.example.wallet.chain.Chain
import com.example.wallet.chain.EthereumProvider
import com.example.wallet.chain.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Maps wallet assets to CoinGecko coin ids. Tokens are matched by contract/mint address,
 * never by symbol, so look-alike scam tokens named "USDC" don't get a price.
 * Testnet tokens map to the mainnet coin and are only a reference price.
 */
object CoinIds {
    private val NATIVE = mapOf(
        Chain.BTC to "bitcoin", Chain.ETH to "ethereum", Chain.SOL to "solana",
        Chain.BASE to "ethereum", Chain.ARB to "ethereum",
        Chain.POL to "polygon-ecosystem-token", Chain.BNB to "binancecoin",
        Chain.LTC to "litecoin", Chain.TRX to "tron"
    )

    // Our own curated token lists only: the symbol is matched here, never for user-supplied tokens.
    private val SYMBOL_IDS = mapOf(
        "USDC" to "usd-coin", "USDT" to "tether", "LINK" to "chainlink", "WETH" to "weth", "ARB" to "arbitrum",
        "DAI" to "dai", "WBTC" to "wrapped-bitcoin", "UNI" to "uniswap", "AAVE" to "aave",
        "BTCB" to "binance-bitcoin", "WBNB" to "wbnb", "ETH" to "ethereum" // "ETH" = the bridged ETH token on BNB Chain
    )

    private fun curated(chain: Chain, list: List<TokenInfo>): Map<String, String> =
        list.mapNotNull { t -> SYMBOL_IDS[t.symbol]?.let { "${chain.name}:${t.address.lowercase()}" to it } }.toMap()

    private val TOKENS: Map<String, String> = mapOf(
        // Ethereum mainnet (lowercase)
        "ETH:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to "usd-coin",
        "ETH:0xdac17f958d2ee523a2206206994597c13d831ec7" to "tether",
        "ETH:0x514910771af9ca656af840dff83e8264ecf986ca" to "chainlink",
        "ETH:0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2" to "weth",
        // Sepolia test tokens
        "ETH:0x1c7d4b196cb0c7b01d743fbc6116a902379c7238" to "usd-coin",
        "ETH:0x779877a7b0d9e8603169ddbd7836e478b4624789" to "chainlink",
        "ETH:0xfff9976782d46cc05630d1f6ebab18b2324d6b14" to "weth",
        // Solana (case-sensitive mints): mainnet USDC/USDT, devnet USDC
        "SOL:EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "usd-coin",
        "SOL:Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "tether",
        "SOL:4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU" to "usd-coin",
        "SOL:JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN" to "jupiter-exchange-solana",
        "SOL:DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to "bonk",
        "SOL:EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm" to "dogwifcoin",
        "SOL:mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So" to "msol",
        "SOL:J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn" to "jito-staked-sol",
        // Tron mainnet (case-sensitive Base58 contract): USDT only
        "TRX:TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t" to "tether"
    ) + curated(Chain.ETH, EthereumProvider.MAINNET_TOKENS) +
        curated(Chain.POL, EvmTokens.POLYGON) +
        curated(Chain.BNB, EvmTokens.BNB) +
        curated(Chain.BASE, EvmTokens.BASE) +
        curated(Chain.ARB, EvmTokens.ARBITRUM)

    val all: List<String> = (NATIVE.values + TOKENS.values).distinct().sorted()

    /** [tokenId] = null means the chain's native coin. */
    fun forAsset(chain: Chain, tokenId: String?): String? {
        if (tokenId == null) return NATIVE[chain]
        val key = chain.name + ":" + (if (chain.isEvm) tokenId.lowercase() else tokenId)
        return TOKENS[key]
    }
}

/** USD prices from CoinGecko's free public API, cached for a minute to stay under rate limits. */
class PriceService {
    private var cache: Map<String, Double> = emptyMap()
    private var cachedAt = 0L

    suspend fun fetchUsd(): Map<String, Double> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < CACHE_MS) return@withContext cache

        val ids = CoinIds.all.joinToString(",")
        val json = JSONObject(Http.get("$BASE/simple/price?ids=$ids&vs_currencies=usd"))
        val out = HashMap<String, Double>()
        for (id in CoinIds.all) {
            val o = json.optJSONObject(id) ?: continue
            if (o.has("usd")) out[id] = o.getDouble("usd")
        }
        if (out.isNotEmpty()) {
            cache = out
            cachedAt = now
        }
        cache
    }

    private companion object {
        const val BASE = "https://api.coingecko.com/api/v3"
        const val CACHE_MS = 60_000L
    }
}
