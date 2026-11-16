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
        TokenInfo("0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619", "WETH", 18),
        TokenInfo("0x8f3Cf7ad23Cd3CaDbD9735AFf958023239c6A063", "DAI", 18),
        TokenInfo("0x1BFD67037B42Cf73acF2047067bd4F2C47D9BfD6", "WBTC", 8),
        TokenInfo("0x53E0bca35eC356BD5ddDFebbD1Fc0fD03FaBad39", "LINK", 18)
    )
    val BNB = listOf(
        TokenInfo("0x55d398326f99059fF775485246999027B3197955", "USDT", 18),
        TokenInfo("0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", "USDC", 18),
        TokenInfo("0x7130d2A12B9BCbFAe4f2634d864A1Ee1Ce3Ead9c", "BTCB", 18),
        TokenInfo("0x2170Ed0880ac9A755fd29B2688956BD959F933F8", "ETH", 18),
        TokenInfo("0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c", "WBNB", 18)
    )
    val BASE = listOf(
        TokenInfo("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", 6),
        TokenInfo("0x4200000000000000000000000000000000000006", "WETH", 18),
        TokenInfo("0x50c5725949A6F0c72E6C4a641F24049A917DB0Cb", "DAI", 18)
    )
    val ARBITRUM = listOf(
        TokenInfo("0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "USDC", 6),
        TokenInfo("0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", "USDT", 6),
        TokenInfo("0x82aF49447D8a07e3bd95BD0d56f35241523fBab1", "WETH", 18),
        TokenInfo("0x912CE59144191C1204E64559FE8253a0e49E6548", "ARB", 18),
        TokenInfo("0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", "DAI", 18),
        TokenInfo("0x2f2a2543B76A4166549F7aaB2e75Bef0aefC5B0f", "WBTC", 8),
        TokenInfo("0xf97f4df75117a78c1A5a0DBb814Af92458539FB4", "LINK", 18)
    )
}
