package com.example.wallet.data

/**
 * Well-known tokens shown automatically on each EVM mainnet (hidden at zero balance).
 * Verify every address on the chain's block explorer before relying on a balance.
 * Note: USDT/USDC on BNB Chain use 18 decimals, unlike 6 elsewhere.
 */
object EvmTokens {
    val POLYGON = listOf(
        TokenInfo("0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", "USDC", 6),
        TokenInfo("0xc2132D05D31c914a87C6611C10748AEb04B58e8F", "USDT", 6),
        TokenInfo("0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619", "WETH", 18)
    )
    val BNB = listOf(
        TokenInfo("0x55d398326f99059fF775485246999027B3197955", "USDT", 18),
        TokenInfo("0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", "USDC", 18)
    )
    val BASE = listOf(
        TokenInfo("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", 6),
        TokenInfo("0x4200000000000000000000000000000000000006", "WETH", 18)
    )
    val ARBITRUM = listOf(
        TokenInfo("0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "USDC", 6),
        TokenInfo("0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", "USDT", 6),
        TokenInfo("0x82aF49447D8a07e3bd95BD0d56f35241523fBab1", "WETH", 18),
        TokenInfo("0x912CE59144191C1204E64559FE8253a0e49E6548", "ARB", 18)
    )
}
