package com.example.wallet.chain

import java.math.BigInteger

/**
 * A Uniswap v2 deployment: just the UniswapV2Router02. Its WETH is read from the router on-chain (`WETH()`) rather
 * than hardcoded, because v2 and v3 deployments on the same testnet do not share a WETH.
 */
class UniswapV2Config(
    val router: String,
    /** Shown to the user as the swap venue. */
    val name: String = "Uniswap v2",
    /** The wrapped native coin, listed as a swappable asset on a network that has no Uniswap v3 config. The router's own WETH() is what is used. */
    val wrapped: String? = null
)

/** Official Uniswap v2 deployments (docs.uniswap.org). Uniswap publishes none for Base Sepolia or Arbitrum Sepolia. */
object UniswapV2Deployments {
    val SEPOLIA = UniswapV2Config(router = "0xeE567Fe1712Faf6149d80dA1E6934E354124CfE3")

    /**
     * PancakeSwap V2 on BNB Chain testnet: a Uniswap V2 fork (same router ABI), deployed and announced by PancakeSwap. Router, factory
     * (0x6725F303b657a9451d8BA641348b6761A6CC7a17) and WBNB were found in several independent references; all pass their EIP-55 checksums.
     * Pool liquidity on a testnet is not guaranteed: the quote says so if a pair has none.
     */
    val PANCAKE_BSC_TESTNET = UniswapV2Config(
        router = "0xD99D1c33F9fC3444f8101754aBC46c52416550D1",
        name = "PancakeSwap v2",
        wrapped = "0xae13d989daC2f0dEbFf460aC112a837C89BAa7cd"
    )
    /** BUSD test token used with that router's WBNB pool. */
    const val BSC_TESTNET_BUSD = "0x78867BbEeF44f2326bF8DDd1941a4439382EF2A7"
}

/**
 * Calldata builders for UniswapV2Router02. Like the v3 builders, the wallet writes these itself, so the recipient,
 * amounts and path are ours and there is no third-party calldata to trust. Outputs are checked against an independent
 * encoder in the unit tests (tools/verify/uniswap_v2_golden.py).
 */
object UniswapV2Abi {
    const val SELECTOR_GET_AMOUNTS_OUT = "d06ca61f"
    const val SELECTOR_SWAP_EXACT_TOKENS_FOR_TOKENS = "38ed1739"
    const val SELECTOR_SWAP_EXACT_ETH_FOR_TOKENS = "7ff36ab5"
    const val SELECTOR_SWAP_EXACT_TOKENS_FOR_ETH = "18cbafe5"
    const val SELECTOR_WETH = "ad5c4648"

    /** Orders the router will accept at most this long after they are built (seconds). */
    const val DEADLINE_SECONDS = 20 * 60L

    private fun word(hex: String): String = hex.removePrefix("0x").lowercase().padStart(64, '0')

    private fun word(v: BigInteger): String {
        require(v.signum() >= 0 && v.bitLength() <= 256) { "value out of range" }
        return v.toString(16).padStart(64, '0')
    }

    private fun word(v: Long): String = word(BigInteger.valueOf(v))

    /** address[]: length word, then one word per element. */
    private fun addressArray(path: List<String>): String {
        require(path.size >= 2) { "a swap path needs at least two tokens" }
        return word(path.size.toLong()) + path.joinToString("") { word(it) }
    }

    /** Direct pair first, then a hop through WETH when neither side already is WETH. */
    fun candidatePaths(tokenIn: String, tokenOut: String, weth: String): List<List<String>> {
        require(!tokenIn.equals(tokenOut, ignoreCase = true)) { "tokenIn and tokenOut must differ" }
        val paths = arrayListOf(listOf(tokenIn, tokenOut))
        if (!tokenIn.equals(weth, ignoreCase = true) && !tokenOut.equals(weth, ignoreCase = true)) {
            paths.add(listOf(tokenIn, weth, tokenOut))
        }
        return paths
    }

    fun wethCall(): String = "0x$SELECTOR_WETH"

    /** getAmountsOut(uint256,address[]): head = amountIn, offset to the array (2 head words = 0x40). */
    fun getAmountsOut(amountIn: BigInteger, path: List<String>): String =
        "0x" + SELECTOR_GET_AMOUNTS_OUT + word(amountIn) + word(0x40L) + addressArray(path)

    /** swapExactTokensForTokens(amountIn, amountOutMin, path, to, deadline): array offset = 5 head words = 0xa0. */
    fun swapExactTokensForTokens(
        amountIn: BigInteger, minOut: BigInteger, path: List<String>, to: String, deadline: Long
    ): String = "0x" + SELECTOR_SWAP_EXACT_TOKENS_FOR_TOKENS + word(amountIn) + word(minOut) + word(0xa0L) +
        word(to) + word(deadline) + addressArray(path)

    /** swapExactETHForTokens(amountOutMin, path, to, deadline), payable: array offset = 4 head words = 0x80. */
    fun swapExactEthForTokens(minOut: BigInteger, path: List<String>, to: String, deadline: Long): String =
        "0x" + SELECTOR_SWAP_EXACT_ETH_FOR_TOKENS + word(minOut) + word(0x80L) + word(to) + word(deadline) + addressArray(path)

    fun swapExactTokensForEth(
        amountIn: BigInteger, minOut: BigInteger, path: List<String>, to: String, deadline: Long
    ): String = "0x" + SELECTOR_SWAP_EXACT_TOKENS_FOR_ETH + word(amountIn) + word(minOut) + word(0xa0L) +
        word(to) + word(deadline) + addressArray(path)

    /**
     * The last element of the `uint256[] amounts` returned by getAmountsOut (the final output amount), or null if the
     * data is malformed or the array does not have [expectedLength] elements.
     */
    fun lastAmount(returnData: String?, expectedLength: Int): BigInteger? {
        val hex = returnData?.removePrefix("0x") ?: return null
        fun wordAt(byteOffset: Long): BigInteger? {
            val start = byteOffset * 2
            if (start < 0 || start + 64 > hex.length) return null
            return BigInteger(hex.substring(start.toInt(), (start + 64).toInt()), 16)
        }
        val offset = wordAt(0)?.takeIf { it.bitLength() < 31 }?.toLong() ?: return null
        val length = wordAt(offset)?.takeIf { it.bitLength() < 31 }?.toLong() ?: return null
        if (length != expectedLength.toLong() || length < 2) return null
        return wordAt(offset + 32 + (length - 1) * 32)
    }

    /** An address from a 32-byte eth_call result (e.g. `WETH()`), or null if malformed, dirty or the zero address. */
    fun addressFromWord(returnData: String?): String? {
        val hex = returnData?.removePrefix("0x") ?: return null
        if (hex.length != 64) return null
        if (!hex.substring(0, 24).all { it == '0' }) return null
        val addr = hex.substring(24)
        if (addr.all { it == '0' }) return null
        return "0x$addr"
    }
}

/** Which Uniswap version serves a swap when both are configured. */
object UniswapVenue {
    /** True = use v2, false = use v3, null = neither has a pool. The higher output wins; a tie goes to v3. */
    fun preferV2(v3Out: BigInteger?, v2Out: BigInteger?): Boolean? = when {
        v3Out == null && v2Out == null -> null
        v3Out == null -> true
        v2Out == null -> false
        else -> v2Out > v3Out
    }
}
