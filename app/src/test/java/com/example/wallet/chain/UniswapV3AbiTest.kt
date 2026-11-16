package com.example.wallet.chain

import com.example.wallet.data.CoinIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Keys
import java.math.BigInteger

/**
 * Uniswap v3 calldata is built by the wallet itself, so these golden values (produced by an independent ABI
 * encoder, tools/verify/uniswap_golden.py) are what protects against a wrong selector or a mis-ordered field.
 */
class UniswapV3AbiTest {
    private val weth = "0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14"
    private val usdc = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
    private val user = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private val one = BigInteger.TEN.pow(18)
    private val minOut = BigInteger.valueOf(123456789)

    @Test fun quote_exactInputSingle() = assertEquals(
        QUOTE,
        UniswapV3Abi.quoteExactInputSingle(weth, usdc, one, 3000)
    )

    @Test fun exactInputSingle_toUser() = assertEquals(
        EXACT,
        UniswapV3Abi.exactInputSingle(weth, usdc, 500, user, one, minOut)
    )

    @Test fun exactInputSingle_keepOutputInRouter() = assertEquals(
        EXACT_THIS,
        UniswapV3Abi.exactInputSingle(usdc, weth, 3000, UniswapV3Abi.ADDRESS_THIS, BigInteger.valueOf(5_000_000), minOut)
    )

    @Test fun unwrapWeth9() = assertEquals(UNWRAP, UniswapV3Abi.unwrapWeth9(minOut, user))

    @Test fun multicall_swapThenUnwrap() {
        val swap = UniswapV3Abi.exactInputSingle(usdc, weth, 3000, UniswapV3Abi.ADDRESS_THIS, BigInteger.valueOf(5_000_000), minOut)
        assertEquals(MULTI, UniswapV3Abi.multicall(listOf(swap, UniswapV3Abi.unwrapWeth9(minOut, user))))
    }

    @Test fun multicall_singleElement() =
        assertEquals(MULTI1, UniswapV3Abi.multicall(listOf(UniswapV3Abi.unwrapWeth9(BigInteger.valueOf(7), user))))

    @Test fun wethWrapAndUnwrap() {
        assertEquals("0xd0e30db0", UniswapV3Abi.wethDeposit())
        assertEquals(WITHDRAW, UniswapV3Abi.wethWithdraw(one))
    }

    @Test fun firstWord_decodesAmountOut() {
        assertEquals(BigInteger.valueOf(0x1234), UniswapV3Abi.firstWord("0x" + "0".repeat(60) + "1234" + "ff".repeat(32)))
        assertNull(UniswapV3Abi.firstWord("0x"))
        assertNull(UniswapV3Abi.firstWord(null))
        assertNull(UniswapV3Abi.firstWord("0x1234"))
    }

    @Test fun amountsOutOfRange_areRejected() {
        try {
            UniswapV3Abi.exactInputSingle(weth, usdc, 500, user, BigInteger.ONE.shiftLeft(256), minOut)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun deployments_areChecksummedAndDistinct() {
        for (c in listOf(UniswapDeployments.SEPOLIA, UniswapDeployments.BASE_SEPOLIA, UniswapDeployments.ARBITRUM_SEPOLIA)) {
            for (a in listOf(c.router, c.quoter, c.weth)) assertEquals(a, Keys.toChecksumAddress(a))
            assertTrue(c.router != c.quoter)
        }
    }

    @Test fun sepoliaWeth_matchesTheCuratedTokenList() {
        val listed = EthereumProvider.SEPOLIA_TOKENS.first { it.symbol == "WETH" }.address
        assertEquals(UniswapDeployments.SEPOLIA.weth.lowercase(), listed.lowercase())
    }

    // ---- Solana swap targets ----
    @Test fun solanaSwapAssets_areVerifiableAndPriced() {
        val mainnet = SolanaProvider(explorerSuffix = "")
        val assets = mainnet.swapAssets()
        assertTrue(assets.size >= 8)
        assertNull("native SOL first", assets.first().id)
        for (a in assets.drop(1)) {
            assertEquals(a.symbol, 32, com.example.wallet.crypto.Base58.decode(a.id!!)?.size)
            assertNotNull("${a.symbol} needs a price for the mainnet cap", CoinIds.forAsset(Chain.SOL, a.id))
            assertEquals("display name matches", a.symbol, SolanaProvider.DEFAULT_KNOWN_MINTS[a.id])
            assertTrue(a.decimals in 0..9)
        }
        assertEquals(assets.map { it.id }.size, assets.map { it.id }.toSet().size)
    }

    @Test fun solanaDevnet_hasNoSwaps() = assertTrue(SolanaProvider().swapAssets().isEmpty())

    private companion object {
        const val QUOTE =
            "0xc6a5026a" +
            "000000000000000000000000fff9976782d46cc05630d1f6ebab18b2324d6b14" +
            "0000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c7238" +
            "0000000000000000000000000000000000000000000000000de0b6b3a7640000" +
            "0000000000000000000000000000000000000000000000000000000000000bb8" +
            "0000000000000000000000000000000000000000000000000000000000000000"
        const val EXACT =
            "0x04e45aaf" +
            "000000000000000000000000fff9976782d46cc05630d1f6ebab18b2324d6b14" +
            "0000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c7238" +
            "00000000000000000000000000000000000000000000000000000000000001f4" +
            "0000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda94" +
            "0000000000000000000000000000000000000000000000000de0b6b3a7640000" +
            "00000000000000000000000000000000000000000000000000000000075bcd15" +
            "0000000000000000000000000000000000000000000000000000000000000000"
        const val EXACT_THIS =
            "0x04e45aaf" +
            "0000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c7238" +
            "000000000000000000000000fff9976782d46cc05630d1f6ebab18b2324d6b14" +
            "0000000000000000000000000000000000000000000000000000000000000bb8" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "00000000000000000000000000000000000000000000000000000000004c4b40" +
            "00000000000000000000000000000000000000000000000000000000075bcd15" +
            "0000000000000000000000000000000000000000000000000000000000000000"
        const val UNWRAP =
            "0x49404b7c" +
            "00000000000000000000000000000000000000000000000000000000075bcd15" +
            "0000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda94"
        const val MULTI =
            "0xac9650d8" +
            "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "0000000000000000000000000000000000000000000000000000000000000040" +
            "0000000000000000000000000000000000000000000000000000000000000160" +
            "00000000000000000000000000000000000000000000000000000000000000e4" +
            "04e45aaf0000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902" +
            "379c7238000000000000000000000000fff9976782d46cc05630d1f6ebab18b2" +
            "324d6b1400000000000000000000000000000000000000000000000000000000" +
            "00000bb800000000000000000000000000000000000000000000000000000000" +
            "0000000200000000000000000000000000000000000000000000000000000000" +
            "004c4b4000000000000000000000000000000000000000000000000000000000" +
            "075bcd1500000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000044" +
            "49404b7c00000000000000000000000000000000000000000000000000000000" +
            "075bcd150000000000000000000000009858effd232b4033e47d90003d41ec34" +
            "ecaeda9400000000000000000000000000000000000000000000000000000000"
        const val MULTI1 =
            "0xac9650d8" +
            "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000044" +
            "49404b7c00000000000000000000000000000000000000000000000000000000" +
            "000000070000000000000000000000009858effd232b4033e47d90003d41ec34" +
            "ecaeda9400000000000000000000000000000000000000000000000000000000"
        const val WITHDRAW =
            "0x2e1a7d4d" +
            "0000000000000000000000000000000000000000000000000de0b6b3a7640000"
    }
}
