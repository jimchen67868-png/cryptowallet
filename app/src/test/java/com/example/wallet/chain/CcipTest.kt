package com.example.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.StaticStruct
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.crypto.Keys
import org.web3j.crypto.Hash
import java.math.BigInteger

/**
 * Chainlink CCIP testnet route. The wallet builds these calls itself, so the golden values come from an independent ABI
 * encoder (tools/verify/ccip_golden.py) and a second opinion from web3j's own encoder.
 */
class CcipTest {
    private val user = "0xa3a0fccdf214159ce35d4a0066bb32fe10733d67"
    private val sepolia = CcipTestnet.SEPOLIA
    private val bnb = CcipTestnet.BNB_TESTNET
    private val amount = BigInteger.TEN.pow(15)

    // ---- selectors, addresses, lanes ----

    @Test fun selectors_matchTheSolidityFunctionSignatures() {
        val sig = "(bytes,bytes,(address,uint256)[],address,bytes)"
        fun selector(s: String) = Hash.sha3String(s).substring(2, 10)
        assertEquals(selector("getFee(uint64,$sig)"), CcipAbi.SELECTOR_GET_FEE)
        assertEquals(selector("ccipSend(uint64,$sig)"), CcipAbi.SELECTOR_CCIP_SEND)
        assertEquals(selector("drip(address)"), CcipAbi.SELECTOR_DRIP)
        assertEquals("20487ded", CcipAbi.SELECTOR_GET_FEE); assertEquals("96f4e9f9", CcipAbi.SELECTOR_CCIP_SEND)
    }

    @Test fun lanes_addressesAreValidChecksummedAddresses() {
        for (a in listOf(sepolia.router, sepolia.bnm, bnb.router, bnb.bnm)) assertEquals(Keys.toChecksumAddress(a), a)
        assertEquals(BigInteger("16015286601757825753"), sepolia.selector)
        assertEquals(BigInteger("13264668187771770619"), bnb.selector)
        assertTrue(sepolia.selector.bitLength() <= 64 && bnb.selector.bitLength() <= 64)
        assertEquals(11_155_111L, sepolia.chainId); assertEquals(97L, bnb.chainId)
    }

    @Test fun lanes_destinationsAndLookup() {
        assertEquals(sepolia, CcipTestnet.laneOf(Chain.ETH)); assertEquals(bnb, CcipTestnet.laneOf(Chain.BNB))
        assertNull(CcipTestnet.laneOf(Chain.BASE)); assertNull(CcipTestnet.laneOf(Chain.ARB)); assertNull(CcipTestnet.laneOf(Chain.POL))
        assertEquals(listOf(Chain.BNB), CcipTestnet.destinations(Chain.ETH))
        assertEquals(listOf(Chain.ETH), CcipTestnet.destinations(Chain.BNB))
        assertTrue(CcipTestnet.destinations(Chain.BASE).isEmpty())
    }

    @Test fun candidateAssets_onlyBnmArrivesAsBnm() {
        val bnmOnSepolia = SwapAsset(sepolia.bnm.lowercase(), "CCIP-BnM", 18)
        assertEquals(listOf(SwapAsset(bnb.bnm, "CCIP-BnM", 18)), CcipTestnet.candidateAssets(bnmOnSepolia, Chain.ETH, Chain.BNB))
        assertEquals(listOf(SwapAsset(sepolia.bnm, "CCIP-BnM", 18)), CcipTestnet.candidateAssets(SwapAsset(bnb.bnm, "CCIP-BnM", 18), Chain.BNB, Chain.ETH))
        assertTrue("native coins cannot go through CCIP", CcipTestnet.candidateAssets(SwapAsset(null, "ETH", 18), Chain.ETH, Chain.BNB).isEmpty())
        assertTrue(CcipTestnet.candidateAssets(SwapAsset("0x0000000000000000000000000000000000000abc", "X", 18), Chain.ETH, Chain.BNB).isEmpty())
        assertTrue("wrong chain's BnM", CcipTestnet.candidateAssets(SwapAsset(bnb.bnm, "CCIP-BnM", 18), Chain.ETH, Chain.BNB).isEmpty())
        assertTrue(CcipTestnet.candidateAssets(bnmOnSepolia, Chain.ETH, Chain.BASE).isEmpty())
        assertTrue(CcipTestnet.candidateAssets(bnmOnSepolia, Chain.ETH, Chain.ETH).isEmpty())
    }

    @Test fun testnetRoutes_chooseRelayForEthAndCcipForBnm() {
        assertEquals(listOf(SwapAsset(null, "ETH", 18)), TestnetRoutes.candidateAssets(SwapAsset(null, "ETH", 18), Chain.ETH, Chain.BASE))
        assertTrue("no Relay lane to BNB testnet", TestnetRoutes.candidateAssets(SwapAsset(null, "ETH", 18), Chain.ETH, Chain.BNB).isEmpty())
        assertEquals(listOf(SwapAsset(bnb.bnm, "CCIP-BnM", 18)), TestnetRoutes.candidateAssets(SwapAsset(sepolia.bnm, "CCIP-BnM", 18), Chain.ETH, Chain.BNB))
        assertTrue(TestnetRoutes.candidateAssets(SwapAsset(sepolia.bnm, "CCIP-BnM", 18), Chain.ETH, Chain.BASE).isEmpty())
    }

    // ---- calldata: golden values from the independent Python encoder ----

    @Test fun extraArgs_v2_noGasLimitOutOfOrderAllowed() = assertEquals("0x181dcf1000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001".removePrefix("0x"), CcipAbi.extraArgsV2())

    @Test fun getFee_sepoliaToBnb() = assertEquals(GETFEE_SEPOLIA_TO_BNB, CcipAbi.getFee(bnb.selector, user, sepolia.bnm, amount))

    @Test fun ccipSend_sepoliaToBnb() = assertEquals(SEND_SEPOLIA_TO_BNB, CcipAbi.ccipSend(bnb.selector, user, sepolia.bnm, amount))

    @Test fun ccipSend_bnbToSepolia() =
        assertEquals(SEND_BNB_TO_SEPOLIA, CcipAbi.ccipSend(sepolia.selector, user, bnb.bnm, BigInteger("123456789012345678")))

    @Test fun drip() = assertEquals(DRIP, CcipAbi.drip(user))

    @Test fun getFeeAndSendDifferOnlyInTheSelector() =
        assertEquals(CcipAbi.getFee(bnb.selector, user, sepolia.bnm, amount).substring(10), CcipAbi.ccipSend(bnb.selector, user, sepolia.bnm, amount).substring(10))

    @Test fun mixedCaseAddressesEncodeIdentically() = assertEquals(
        CcipAbi.ccipSend(bnb.selector, user, sepolia.bnm, amount),
        CcipAbi.ccipSend(bnb.selector, Keys.toChecksumAddress(user), sepolia.bnm.lowercase(), amount)
    )

    @Test fun badInputsAreRefused() {
        val bad = listOf<() -> Unit>(
            { CcipAbi.ccipSend(bnb.selector, "0xnope", sepolia.bnm, amount) },
            { CcipAbi.ccipSend(bnb.selector, user, "0x1234", amount) },
            { CcipAbi.ccipSend(BigInteger.ZERO, user, sepolia.bnm, amount) },
            { CcipAbi.ccipSend(BigInteger.ONE.shiftLeft(64), user, sepolia.bnm, amount) },
            { CcipAbi.ccipSend(bnb.selector, user, sepolia.bnm, BigInteger.valueOf(-1)) },
            { CcipAbi.ccipSend(bnb.selector, user, sepolia.bnm, BigInteger.ONE.shiftLeft(256)) },
            { CcipAbi.drip("0x12") }
        )
        for (call in bad) { try { call(); fail("accepted") } catch (e: IllegalArgumentException) { /* expected */ } }
    }

    // ---- a second opinion: web3j's ABI encoder ----

    private class TokenAmount(token: Address, amount: Uint256) : StaticStruct(token, amount)

    private class Message(receiver: DynamicBytes, data: DynamicBytes, tokenAmounts: DynamicArray<TokenAmount>, feeToken: Address, extraArgs: DynamicBytes) :
        DynamicStruct(receiver, data, tokenAmounts, feeToken, extraArgs)

    private fun hexBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun matchesWeb3jEncoder() {
        val message = Message(
            DynamicBytes(hexBytes("000000000000000000000000" + user.removePrefix("0x"))),
            DynamicBytes(ByteArray(0)),
            DynamicArray(TokenAmount::class.java, listOf(TokenAmount(Address(sepolia.bnm), Uint256(amount)))),
            Address("0x0000000000000000000000000000000000000000"),
            DynamicBytes(hexBytes(CcipAbi.extraArgsV2()))
        )
        val params = FunctionEncoder.encodeConstructor(listOf(Uint64(bnb.selector), message))
        assertEquals(SEND_SEPOLIA_TO_BNB.substring(10), params)
    }

    // ---- fee decoding and guard ----

    private fun word(v: BigInteger) = v.toString(16).padStart(64, '0')

    @Test fun feeFromResult() {
        assertEquals(BigInteger("130463107598943"), CcipAbi.feeFromResult("0x" + word(BigInteger("130463107598943"))))
        for (bad in listOf(null, "", "0x", "0x12", "0x" + "0".repeat(65), "0x" + "zz".repeat(32))) assertNull(bad, CcipAbi.feeFromResult(bad))
    }

    @Test fun guard_request() {
        assertNull(CcipGuard.validateRequest(sepolia, bnb, sepolia.bnm, amount))
        assertNull(CcipGuard.validateRequest(sepolia, bnb, sepolia.bnm.lowercase(), amount))
        assertEquals(CcipGuard.ONLY_BNM, CcipGuard.validateRequest(sepolia, bnb, null, amount))
        assertEquals(CcipGuard.ONLY_BNM, CcipGuard.validateRequest(sepolia, bnb, bnb.bnm, amount))
        assertEquals(CcipGuard.ONLY_BNM, CcipGuard.validateRequest(sepolia, bnb, "0x0000000000000000000000000000000000000abc", amount))
        assertEquals("Invalid amount", CcipGuard.validateRequest(sepolia, bnb, sepolia.bnm, BigInteger.ZERO))
        assertTrue(CcipGuard.validateRequest(sepolia, sepolia, sepolia.bnm, amount)!!.contains("only"))
        assertTrue(CcipGuard.validateRequest(null, bnb, sepolia.bnm, amount)!!.contains("only"))
        assertTrue(CcipGuard.validateRequest(sepolia, null, sepolia.bnm, amount)!!.contains("only"))
    }

    @Test fun guard_fee() {
        assertNull(CcipGuard.validateFee(BigInteger("130463107598943")))
        assertNull(CcipGuard.validateFee(CcipGuard.MAX_FEE_WEI))
        assertTrue(CcipGuard.validateFee(CcipGuard.MAX_FEE_WEI.add(BigInteger.ONE))!!.contains("unusually high"))
        assertTrue(CcipGuard.validateFee(BigInteger.ZERO)!!.contains("zero"))
        assertTrue(CcipGuard.validateFee(BigInteger.valueOf(-1))!!.contains("zero"))
        assertTrue(CcipGuard.validateFee(null)!!.contains("Could not read"))
    }

    @Test fun guard_freshFeeMayRiseByHalfButNoMore() {
        val quoted = BigInteger.valueOf(1_000_000)
        assertTrue(CcipGuard.freshFeeAcceptable(quoted, BigInteger.valueOf(1_000_000)))
        assertTrue(CcipGuard.freshFeeAcceptable(quoted, BigInteger.valueOf(1_500_000)))
        assertFalse(CcipGuard.freshFeeAcceptable(quoted, BigInteger.valueOf(1_500_001)))
        assertTrue("a cheaper fee is fine", CcipGuard.freshFeeAcceptable(quoted, BigInteger.valueOf(10)))
        assertFalse(CcipGuard.freshFeeAcceptable(quoted, BigInteger.ZERO))
    }

    // ---- quote object ----

    @Test fun bridgeQuote_networkFeeIsInNativeUnits() {
        val q = BridgeQuote(Chain.ETH, Chain.BNB, SwapAsset(sepolia.bnm, "CCIP-BnM", 18), amount, amount, BigInteger.ZERO, 18, "CCIP-BnM", 0L, false,
            networkFeeRaw = BigInteger("130463107598943"), networkFeeSymbol = "ETH")
        assertEquals(java.math.BigDecimal("0.000130463107598943"), q.networkFee)
        assertEquals(java.math.BigDecimal("0.001"), q.amountOut.stripTrailingZeros())
        val plain = BridgeQuote(Chain.ETH, Chain.BASE, SwapAsset(null, "ETH", 18), amount, amount, BigInteger.ZERO, 18, "ETH", 0L, false)
        assertEquals(0, plain.networkFee.signum())
    }

    private companion object {
        const val GETFEE_SEPOLIA_TO_BNB = "0x20487ded000000000000000000000000000000000000000000000000b8159170038f96fb000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000e00000000000000000000000000000000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001600000000000000000000000000000000000000000000000000000000000000020000000000000000000000000a3a0fccdf214159ce35d4a0066bb32fe10733d6700000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000000000000fd57b4ddbf88a4e07ff4e34c487b99af2fe82a0500000000000000000000000000000000000000000000000000038d7ea4c680000000000000000000000000000000000000000000000000000000000000000044181dcf100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000"
        const val SEND_SEPOLIA_TO_BNB = "0x96f4e9f9000000000000000000000000000000000000000000000000b8159170038f96fb000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000e00000000000000000000000000000000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001600000000000000000000000000000000000000000000000000000000000000020000000000000000000000000a3a0fccdf214159ce35d4a0066bb32fe10733d6700000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000000000000fd57b4ddbf88a4e07ff4e34c487b99af2fe82a0500000000000000000000000000000000000000000000000000038d7ea4c680000000000000000000000000000000000000000000000000000000000000000044181dcf100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000"
        const val SEND_BNB_TO_SEPOLIA = "0x96f4e9f9000000000000000000000000000000000000000000000000de41ba4fc9d91ad9000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000e00000000000000000000000000000000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001600000000000000000000000000000000000000000000000000000000000000020000000000000000000000000a3a0fccdf214159ce35d4a0066bb32fe10733d6700000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000000000000bfa2acd33ed6eec0ed3cc06bf1ac38d22b36b9e900000000000000000000000000000000000000000000000001b69b4ba630f34e0000000000000000000000000000000000000000000000000000000000000044181dcf100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000"
        const val DRIP = "0x67a5cd06000000000000000000000000a3a0fccdf214159ce35d4a0066bb32fe10733d67"
    }
}
