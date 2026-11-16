package com.example.wallet.chain

import java.math.BigInteger

/** A Uniswap v3 deployment: SwapRouter02, QuoterV2 and the chain's WETH9. */
class UniswapV3Config(val router: String, val quoter: String, val weth: String)

/** Official Uniswap v3 testnet deployments (checked against EIP-55 checksums). Pools exist only where someone created them. */
object UniswapDeployments {
    val SEPOLIA = UniswapV3Config(
        router = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E",
        quoter = "0xEd1f6473345F45b75F8179591dd5bA1888cf2FB3",
        weth = "0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14"
    )
    val BASE_SEPOLIA = UniswapV3Config(
        router = "0x94cC0AaC535CCDB3C01d6787D6413C739ae12bc4",
        quoter = "0xC5290058841028F1614F3A6F0F5816cAd0df5E27",
        weth = "0x4200000000000000000000000000000000000006"
    )
    val ARBITRUM_SEPOLIA = UniswapV3Config(
        router = "0x101F443B4d1b059569D643917553c771E1b9663E",
        quoter = "0x2779a0CC1c3e0E44D2542EC3e79e3864Ae93Ef0B",
        weth = "0x980B62Da83eFf3D4576C647993b0c1D7faf17c73"
    )
}

/**
 * Calldata builders for Uniswap v3 (SwapRouter02 / QuoterV2) and WETH9. The wallet builds these itself, so
 * unlike the aggregator path there is no third-party calldata to trust: the recipient, amounts and tokens are ours.
 * Every function's expected output is checked against an independent ABI encoder in the unit tests.
 */
object UniswapV3Abi {
    /** quoteExactInputSingle((address,address,uint256,uint24,uint160)) */
    const val SELECTOR_QUOTE_EXACT_INPUT_SINGLE = "c6a5026a"
    /** exactInputSingle((address,address,uint24,address,uint256,uint256,uint160)) */
    const val SELECTOR_EXACT_INPUT_SINGLE = "04e45aaf"
    const val SELECTOR_MULTICALL = "ac9650d8"
    const val SELECTOR_UNWRAP_WETH9 = "49404b7c"
    const val SELECTOR_WETH_DEPOSIT = "d0e30db0"
    const val SELECTOR_WETH_WITHDRAW = "2e1a7d4d"

    /** SwapRouter02's marker for "keep the output in the router" (so it can be unwrapped to ETH afterwards). */
    const val ADDRESS_THIS = "0x0000000000000000000000000000000000000002"

    /** Pool fee tiers in hundredths of a bip: 0.01%, 0.05%, 0.3%, 1%. */
    val FEE_TIERS = listOf(100, 500, 3000, 10000)

    private fun word(hex: String): String = hex.removePrefix("0x").lowercase().padStart(64, '0')

    private fun word(v: BigInteger): String {
        require(v.signum() >= 0 && v.bitLength() <= 256) { "value out of range" }
        return v.toString(16).padStart(64, '0')
    }

    private fun word(v: Int): String = word(BigInteger.valueOf(v.toLong()))

    fun quoteExactInputSingle(tokenIn: String, tokenOut: String, amountIn: BigInteger, fee: Int): String =
        "0x" + SELECTOR_QUOTE_EXACT_INPUT_SINGLE + word(tokenIn) + word(tokenOut) + word(amountIn) + word(fee) + word(0)

    fun exactInputSingle(
        tokenIn: String, tokenOut: String, fee: Int, recipient: String, amountIn: BigInteger, minOut: BigInteger
    ): String = "0x" + SELECTOR_EXACT_INPUT_SINGLE + word(tokenIn) + word(tokenOut) + word(fee) +
        word(recipient) + word(amountIn) + word(minOut) + word(0) // sqrtPriceLimitX96 = 0 (no limit)

    fun unwrapWeth9(minOut: BigInteger, recipient: String): String =
        "0x" + SELECTOR_UNWRAP_WETH9 + word(minOut) + word(recipient)

    fun wethDeposit(): String = "0x$SELECTOR_WETH_DEPOSIT"

    fun wethWithdraw(amount: BigInteger): String = "0x" + SELECTOR_WETH_WITHDRAW + word(amount)

    /** multicall(bytes[]): head offset, array length, element offsets, then each element (length + padded data). */
    fun multicall(calls: List<String>): String {
        val elements = calls.map { c ->
            val hex = c.removePrefix("0x")
            require(hex.length % 2 == 0) { "odd-length hex" }
            val byteLength = hex.length / 2
            word(byteLength) + hex.padEnd(((byteLength + 31) / 32) * 64, '0')
        }
        val sb = StringBuilder("0x").append(SELECTOR_MULTICALL).append(word(32)).append(word(calls.size))
        var offset = 32 * calls.size
        for (e in elements) {
            sb.append(word(offset))
            offset += e.length / 2
        }
        elements.forEach { sb.append(it) }
        return sb.toString()
    }

    /** First 32-byte word of an eth_call result (QuoterV2 returns amountOut first), or null if too short. */
    fun firstWord(returnData: String?): BigInteger? {
        val hex = returnData?.removePrefix("0x") ?: return null
        if (hex.length < 64) return null
        return BigInteger(hex.substring(0, 64), 16)
    }
}
