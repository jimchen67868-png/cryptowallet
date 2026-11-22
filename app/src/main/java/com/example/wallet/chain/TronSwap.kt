package com.example.wallet.chain

import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * SunSwap V2 on Tron mainnet (a Uniswap V2 fork: same ABI). The wallet builds every call itself and reads quotes
 * straight from the router contract with a read-only simulation (`getAmountsOut`), so no third-party API supplies
 * transaction data. Router, factory and WTRX were taken from SunSwap's V2 interface documentation and an independent
 * on-chain verification (router `WETH()` = WTRX, `factory()` = factory); the provider re-checks both on chain
 * before it signs anything that sends TRX to the router.
 *
 * Native TRX is traded through WTRX, so a TRX leg uses the `...ETH...` router functions with `call_value`.
 */
object SunSwapV2 {
    const val ROUTER = "TKzxdSv2FZKQrEqkKVgp5DcwEXBEKMg2Ax"
    const val FACTORY = "TKWJdrQkqHisa1X8HUdHEfREvTzw4pMAaY"
    const val WTRX = "TNUC9Qb1rRpS5CbWLmNMxXBjyFoydXjWFR"

    // Nile testnet. The sources disagree about which V2 router is current there, so both are probed on chain and the first
    // one that answers WETH()/factory() and has a pool for the pair is used (WTRX and factory are discovered, not pinned).
    const val NILE_ROUTER_A = "TMn1qrmYUMSTXo9babrJLzepKZoPC7M6Sy"   // sun-protocol/sunswap-v2-contracts deployment list
    const val NILE_ROUTER_B = "TYMjxCXfqLpMWW1QToP6hbcjpion7EE25p"   // docs.sun.io list as used by TronLink's tooling
    const val NILE_USDT = "TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf"       // the faucet's test USDT

    /** Routers to try, and what a router must report about itself (null = accept what it reports: testnet only). */
    class Config(val routers: List<String>, val wtrx: String?, val factory: String?)

    val MAINNET = Config(listOf(ROUTER), WTRX, FACTORY)
    val NILE = Config(listOf(NILE_ROUTER_A, NILE_ROUTER_B), null, null)

    const val SLIPPAGE_BPS = 100            // 1%: minimum output below the quote
    const val MAX_IMPACT_BPS = 300L         // refuse trades that move the pool price more than 3%
    const val DEADLINE_SECONDS = 300L
    /** Energy assumed for the swap when it can't be simulated yet (token not approved). Real value is simulated before signing. */
    const val FALLBACK_SWAP_ENERGY = 250_000L
    const val FALLBACK_APPROVE_ENERGY = 40_000L

    const val SIG_GET_AMOUNTS_OUT = "getAmountsOut(uint256,address[])"
    const val SIG_SWAP_TRX_FOR_TOKENS = "swapExactETHForTokens(uint256,address[],address,uint256)"
    const val SIG_SWAP_TOKENS_FOR_TRX = "swapExactTokensForETH(uint256,uint256,address[],address,uint256)"
    const val SIG_SWAP_TOKENS_FOR_TOKENS = "swapExactTokensForTokens(uint256,uint256,address[],address,uint256)"
    const val SIG_APPROVE = "approve(address,uint256)"
    const val SIG_ALLOWANCE = "allowance(address,address)"

    // 4-byte selectors (the well-known Uniswap V2 values; checked against keccak in tools/verify/tron_golden.py)
    private const val SEL_GET_AMOUNTS_OUT = "d06ca61f"
    private const val SEL_SWAP_TRX_FOR_TOKENS = "7ff36ab5"
    private const val SEL_SWAP_TOKENS_FOR_TRX = "18cbafe5"
    private const val SEL_SWAP_TOKENS_FOR_TOKENS = "38ed1739"
    private const val SEL_APPROVE = "095ea7b3"

    /** Router path in Base58 contract addresses: native TRX is WTRX; token to token goes through WTRX. */
    fun path(fromId: String?, toId: String?, wtrx: String = WTRX): List<String> {
        require(fromId != toId) { "Pick two different assets" }
        return when {
            fromId == null -> listOf(wtrx, toId!!)
            toId == null -> listOf(fromId, wtrx)
            else -> listOf(fromId, wtrx, toId)
        }
    }

    private fun word(v: BigInteger): ByteArray {
        require(v.signum() >= 0 && v.bitLength() <= 256) { "value out of range" }
        return Numeric.toBytesPadded(v, 32)
    }
    private fun word(v: Long) = word(BigInteger.valueOf(v))
    private fun addr(a21: ByteArray): ByteArray {
        require(a21.size == TronAddress.SIZE && a21[0] == TronAddress.PREFIX) { "Bad address" }
        return ByteArray(12) + a21.copyOfRange(1, 21)
    }
    private fun tail(path: List<ByteArray>): ByteArray {
        require(path.size in 2..3) { "Bad path" }
        return word(path.size.toLong()) + path.fold(ByteArray(0)) { acc, p -> acc + addr(p) }
    }

    /** Arguments without selector, hex: what `triggerconstantcontract`'s `parameter` field takes. */
    fun getAmountsOutParam(amountIn: BigInteger, path: List<ByteArray>): String =
        TronTx.toHex(word(amountIn) + word(64) + tail(path))

    fun getAmountsOutData(amountIn: BigInteger, path: List<ByteArray>): ByteArray =
        TronTx.fromHex(SEL_GET_AMOUNTS_OUT) + TronTx.fromHex(getAmountsOutParam(amountIn, path))

    /** swapExactETHForTokens: the TRX amount travels as call_value, not as an argument. */
    fun swapTrxForTokensParam(minOut: BigInteger, path: List<ByteArray>, to: ByteArray, deadline: Long): String =
        TronTx.toHex(word(minOut) + word(0x80) + addr(to) + word(deadline) + tail(path))

    /** swapExactTokensForETH / swapExactTokensForTokens. */
    fun swapTokensParam(amountIn: BigInteger, minOut: BigInteger, path: List<ByteArray>, to: ByteArray, deadline: Long): String =
        TronTx.toHex(word(amountIn) + word(minOut) + word(0xa0) + addr(to) + word(deadline) + tail(path))

    fun approveParam(spender: ByteArray, amount: BigInteger): String = TronTx.toHex(addr(spender) + word(amount))
    fun approveData(spender: ByteArray, amount: BigInteger): ByteArray = TronTx.fromHex(SEL_APPROVE) + TronTx.fromHex(approveParam(spender, amount))

    fun allowanceParam(owner: ByteArray, spender: ByteArray): String = TronTx.toHex(addr(owner) + addr(spender))

    /** Which router function, its signature and selector, for a given direction. */
    class Call(val signature: String, val selector: String, val param: String, val callValueSun: Long)

    fun swapCall(fromId: String?, toId: String?, amountIn: BigInteger, minOut: BigInteger, to: ByteArray, deadline: Long, path: List<ByteArray>): Call = when {
        fromId == null -> Call(SIG_SWAP_TRX_FOR_TOKENS, SEL_SWAP_TRX_FOR_TOKENS, swapTrxForTokensParam(minOut, path, to, deadline), amountIn.longValueExact())
        toId == null -> Call(SIG_SWAP_TOKENS_FOR_TRX, SEL_SWAP_TOKENS_FOR_TRX, swapTokensParam(amountIn, minOut, path, to, deadline), 0L)
        else -> Call(SIG_SWAP_TOKENS_FOR_TOKENS, SEL_SWAP_TOKENS_FOR_TOKENS, swapTokensParam(amountIn, minOut, path, to, deadline), 0L)
    }

    /** `uint256[] amounts` returned by getAmountsOut (the last entry is the output). Strict about the layout. */
    fun parseAmounts(hex: String): List<BigInteger> {
        val b = TronTx.fromHex(hex.removePrefix("0x"))
        require(b.size >= 64 && (b.size - 64) % 32 == 0) { "Unexpected quote response" }
        require(BigInteger(1, b.copyOfRange(0, 32)) == BigInteger.valueOf(32)) { "Unexpected quote response" }
        val n = BigInteger(1, b.copyOfRange(32, 64)).toInt()
        require(n in 2..3 && b.size == 64 + 32 * n) { "Unexpected quote response" }
        return List(n) { BigInteger(1, b.copyOfRange(64 + 32 * it, 96 + 32 * it)) }
    }

    fun parseUint(hex: String): BigInteger {
        val b = TronTx.fromHex(hex.removePrefix("0x"))
        require(b.size == 32) { "Unexpected response" }
        return BigInteger(1, b)
    }

    /** A 32-byte word holding an address -> its 20 bytes as hex. */
    fun parseAddressWord(hex: String): String {
        val b = TronTx.fromHex(hex.removePrefix("0x"))
        require(b.size == 32) { "Unexpected response" }
        return TronTx.toHex(b.copyOfRange(12, 32))
    }

    /**
     * How much worse the trade's average price is than the price of a tiny trade through the same pool, in bps
     * (0 = no impact). A shallow pool shows up here before anything is signed.
     */
    fun impactBps(amountIn: BigInteger, amountOut: BigInteger, probeIn: BigInteger, probeOut: BigInteger): Long {
        require(amountIn.signum() > 0 && probeIn.signum() > 0) { "Bad amounts" }
        if (probeOut.signum() == 0) return 10_000L
        // trade rate / probe rate = (out * probeIn) / (in * probeOut)
        val ratioBps = amountOut.multiply(probeIn).multiply(BigInteger.valueOf(10_000)).divide(amountIn.multiply(probeOut))
        return maxOf(0L, 10_000L - ratioBps.toLong())
    }
}
