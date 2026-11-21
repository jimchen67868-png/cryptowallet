package com.example.wallet.chain

import java.math.BigInteger

/**
 * Bridging the CCIP-BnM TEST token between Sepolia and BNB Chain testnet with Chainlink CCIP, called directly on the
 * on-chain Router: no API, no key. The wallet builds the transaction itself, so (unlike a third-party quote) the receiver,
 * token and amount are in calldata we wrote, and a wrong token or unsupported lane is refused by the Router on-chain.
 *
 * Only CCIP-BnM moves: CCIP cannot bridge native coins. The fee is paid in the source chain's native coin (msg.value),
 * read from the Router just before sending. Arrival is detected by the destination balance, so no tracking API is needed.
 *
 * Addresses, chain selectors and the message layout are from Chainlink's CCIP directory and Client.sol docs, and are
 * cross-checked by tools/verify/ccip_golden.py (EIP-55 checksums, function selectors, independent ABI encoder).
 */
object CcipTestnet {
    class Lane(val chain: Chain, val chainId: Long, val router: String, val selector: BigInteger, val bnm: String)

    val SEPOLIA = Lane(
        Chain.ETH, 11_155_111L, "0x0BF3dE8c5D3e8A2B34D2BEeB17ABfCeBaf363A59",
        BigInteger("16015286601757825753"), "0xFd57b4ddBf88a4e07fF4e34C487b99af2Fe82a05"
    )
    val BNB_TESTNET = Lane(
        Chain.BNB, 97L, "0xE1053aE1857476f36A3C62580FF9b016E8EE8F6f",
        BigInteger("13264668187771770619"), "0xbFA2ACd33ED6EEc0ed3Cc06bF1ac38d22b36B9e9"
    )

    private val LANES = listOf(SEPOLIA, BNB_TESTNET)

    const val BNM_SYMBOL = "CCIP-BnM"
    const val BNM_DECIMALS = 18

    fun laneOf(chain: Chain): Lane? = LANES.firstOrNull { it.chain == chain }

    fun destinations(origin: Chain): List<Chain> = if (laneOf(origin) != null) LANES.map { it.chain }.filter { it != origin } else emptyList()

    /** CCIP-BnM arrives as CCIP-BnM on the other chain. Nothing else is bridged here. */
    fun candidateAssets(from: SwapAsset, origin: Chain, dest: Chain): List<SwapAsset> {
        val o = laneOf(origin) ?: return emptyList()
        val d = laneOf(dest) ?: return emptyList()
        if (o.chain == d.chain || from.id == null || !from.id.equals(o.bnm, ignoreCase = true)) return emptyList()
        return listOf(SwapAsset(d.bnm, BNM_SYMBOL, BNM_DECIMALS))
    }
}

/** Calldata builders for the CCIP Router: ccipSend / getFee with a token-only message, native fee. */
object CcipAbi {
    const val SELECTOR_GET_FEE = "20487ded"   // getFee(uint64,(bytes,bytes,(address,uint256)[],address,bytes))
    const val SELECTOR_CCIP_SEND = "96f4e9f9" // ccipSend(uint64,(bytes,bytes,(address,uint256)[],address,bytes))
    const val SELECTOR_DRIP = "67a5cd06"      // drip(address): the CCIP-BnM test-token faucet
    const val EXTRA_ARGS_V2_TAG = "181dcf10"  // GenericExtraArgsV2: still accepted on CCIP 2.0 lanes (only gasLimit is honoured)

    private val ZERO_ADDRESS = "0x" + "0".repeat(40)

    private fun word(v: BigInteger): String {
        require(v.signum() >= 0 && v.bitLength() <= 256) { "value out of range" }
        return v.toString(16).padStart(64, '0')
    }

    private fun word(v: Long) = word(BigInteger.valueOf(v))

    private fun addr(a: String): String {
        val h = a.removePrefix("0x").lowercase()
        require(h.length == 40 && h.all { it in '0'..'9' || it in 'a'..'f' }) { "invalid address" }
        return h.padStart(64, '0')
    }

    private fun padRight(hex: String) = hex + "0".repeat((64 - hex.length % 64) % 64)

    /** A dynamic `bytes` tail: length word, then the content padded to a word boundary. */
    private fun bytesTail(hex: String) = word((hex.length / 2).toLong()) + padRight(hex)

    /** tag + abi.encode(uint256 gasLimit, bool allowOutOfOrderExecution). Gas limit 0 is right for a plain transfer to a wallet. */
    fun extraArgsV2(gasLimit: BigInteger = BigInteger.ZERO, allowOutOfOrderExecution: Boolean = true): String =
        EXTRA_ARGS_V2_TAG + word(gasLimit) + word(if (allowOutOfOrderExecution) 1L else 0L)

    /** EVM2AnyMessage { receiver = abi.encode(address), data = "", tokenAmounts = [(token, amount)], feeToken = 0 (native), extraArgs }. */
    internal fun message(receiver: String, token: String, amount: BigInteger): String {
        val tails = listOf(
            bytesTail(addr(receiver)),
            bytesTail(""),
            word(1L) + addr(token) + word(amount),
            bytesTail(extraArgsV2())
        )
        var pos = 5 * 32L // five head words
        val offsets = tails.map { val o = pos; pos += (it.length / 2).toLong(); o }
        return word(offsets[0]) + word(offsets[1]) + word(offsets[2]) + addr(ZERO_ADDRESS) + word(offsets[3]) + tails.joinToString("")
    }

    private fun call(selector: String, destSelector: BigInteger, receiver: String, token: String, amount: BigInteger): String {
        require(destSelector.signum() > 0 && destSelector.bitLength() <= 64) { "invalid chain selector" }
        return "0x" + selector + word(destSelector) + word(0x40L) + message(receiver, token, amount)
    }

    fun getFee(destSelector: BigInteger, receiver: String, token: String, amount: BigInteger) =
        call(SELECTOR_GET_FEE, destSelector, receiver, token, amount)

    fun ccipSend(destSelector: BigInteger, receiver: String, token: String, amount: BigInteger) =
        call(SELECTOR_CCIP_SEND, destSelector, receiver, token, amount)

    fun drip(to: String) = "0x" + SELECTOR_DRIP + addr(to)

    /** The fee from a getFee eth_call result (exactly one 32-byte word), or null if malformed. */
    fun feeFromResult(returnData: String?): BigInteger? {
        val hex = returnData?.removePrefix("0x") ?: return null
        if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return BigInteger(hex, 16)
    }
}

/** Fails closed: returns a user-facing reason to refuse, or null. */
object CcipGuard {
    /** Refuse a fee above this (0.05 of the native coin): a CCIP testnet fee is a small fraction of it. */
    val MAX_FEE_WEI: BigInteger = BigInteger("50000000000000000")

    const val ONLY_BNM = "Only the CCIP-BnM test token can be bridged between Sepolia and BNB testnet. Use \"Get test CCIP-BnM\" on the home screen."

    fun validateRequest(origin: CcipTestnet.Lane?, dest: CcipTestnet.Lane?, token: String?, amount: BigInteger): String? {
        if (origin == null || dest == null || origin.chain == dest.chain) return "CCIP connects Sepolia and BNB testnet only"
        if (token == null || !token.equals(origin.bnm, ignoreCase = true)) return ONLY_BNM
        if (amount.signum() <= 0) return "Invalid amount"
        return null
    }

    fun validateFee(fee: BigInteger?): String? = when {
        fee == null -> "Could not read the CCIP fee"
        fee.signum() <= 0 -> "The CCIP fee came back as zero, which is not expected"
        fee > MAX_FEE_WEI -> "The CCIP fee is unusually high"
        else -> null
    }

    /** At send time the fee may have moved a little; accept up to 50% above what the user saw, never more. */
    fun freshFeeAcceptable(quoted: BigInteger, fresh: BigInteger): Boolean =
        fresh.signum() > 0 && fresh.multiply(BigInteger.valueOf(100)) <= quoted.multiply(BigInteger.valueOf(150))
}

/** Which test-network bridge serves an asset: Relay for native ETH (Sepolia <-> Base Sepolia), CCIP for CCIP-BnM (Sepolia <-> BNB testnet). */
object TestnetRoutes {
    const val UNSUPPORTED =
        "On the test networks, ETH moves between Sepolia and Base Sepolia (Relay), and the CCIP-BnM test token moves between " +
            "Sepolia and BNB testnet (Chainlink CCIP)."

    fun candidateAssets(from: SwapAsset, origin: Chain, dest: Chain): List<SwapAsset> {
        val relay = if (from.id == null && RelayTestnet.chainIdOf(origin) != null) RelayTestnet.candidateAssets(from, dest) else emptyList()
        return relay.ifEmpty { CcipTestnet.candidateAssets(from, origin, dest) }
    }
}
