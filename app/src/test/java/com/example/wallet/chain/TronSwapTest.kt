package com.example.wallet.chain

import com.example.wallet.hex
import com.example.wallet.unhex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.web3j.crypto.Hash
import java.math.BigInteger

/**
 * SunSwap V2 on Tron: calldata, selectors, path, parsing and price-impact maths. Expected calldata comes from the
 * independent Python encoder in tools/verify/tron_golden.py. NOT tested here: the live router, pools and TronGrid; the
 * provider's quote/approve/swap sequence needs a live run (a few TRX first).
 */
class TronSwapTest {
    private val owner = "41c8599111f29c1e1e061265b4af93ea1f274ad78a".unhex()
    private val wtrx = TronAddress.decode(SunSwapV2.WTRX)!!
    private val usdt = TronAddress.decode(TronProvider.USDT_MAINNET)!!
    private val router = TronAddress.decode(SunSwapV2.ROUTER)!!
    private val deadline = 1777447800L
    private fun big(v: Long) = BigInteger.valueOf(v)

    private fun selector(sig: String) = Hash.sha3(sig.toByteArray()).copyOfRange(0, 4).hex()

    @Test fun contractAddresses_areValid_andTheExpectedOnes() {
        assertEquals("416e0617948fe030a7e4970f8389d4ad295f249b7e", router.hex())
        assertEquals("41891cdb91d149f23b1a45d9c5ca78a88d0cb44c18", wtrx.hex())
        assertEquals("41689abaeeed3f0bb3585773192e23224cac25dd41", TronAddress.decode(SunSwapV2.FACTORY)!!.hex())
    }

    @Test fun selectors_matchKeccakOfTheSignatures() {
        val path = listOf(wtrx, usdt)
        val trxIn = SunSwapV2.swapCall(null, TronProvider.USDT_MAINNET, big(5), big(1), owner, deadline, path)
        val trxOut = SunSwapV2.swapCall(TronProvider.USDT_MAINNET, null, big(5), big(1), owner, deadline, path)
        val tokTok = SunSwapV2.swapCall(TronProvider.USDT_MAINNET, "TXDk8mbtRbXeYuMNS83CfKPaYYT8XWv9Hz", big(5), big(1), owner, deadline, path)
        assertEquals(selector(trxIn.signature), trxIn.selector)
        assertEquals(selector(trxOut.signature), trxOut.selector)
        assertEquals(selector(tokTok.signature), tokTok.selector)
        assertEquals(selector(SunSwapV2.SIG_GET_AMOUNTS_OUT), SunSwapV2.getAmountsOutData(big(1), path).copyOfRange(0, 4).hex())
        assertEquals(selector(SunSwapV2.SIG_APPROVE), SunSwapV2.approveData(router, big(1)).copyOfRange(0, 4).hex())
        assertEquals("7ff36ab5", trxIn.selector)   // the well-known Uniswap V2 selectors
        assertEquals("18cbafe5", trxOut.selector)
        assertEquals("38ed1739", tokTok.selector)
    }

    @Test fun calldata_matchesReferenceEncoder() {
        val path = listOf(wtrx, usdt)
        assertEquals("d06ca61f00000000000000000000000000000000000000000000000000000000000f424000000000000000000000000000000000000000000000000000000000000000400000000000000000000000000000000000000000000000000000000000000002000000000000000000000000891cdb91d149f23b1a45d9c5ca78a88d0cb44c18000000000000000000000000a614f803b6fd780986a42c78ec9c7f77e6ded13c", SunSwapV2.getAmountsOutData(big(1_000_000), path).hex())
        val trxIn = SunSwapV2.swapCall(null, TronProvider.USDT_MAINNET, big(5_000_000), big(4_950_000), owner, deadline, path)
        assertEquals("7ff36ab500000000000000000000000000000000000000000000000000000000004b87f00000000000000000000000000000000000000000000000000000000000000080000000000000000000000000c8599111f29c1e1e061265b4af93ea1f274ad78a0000000000000000000000000000000000000000000000000000000069f1b3780000000000000000000000000000000000000000000000000000000000000002000000000000000000000000891cdb91d149f23b1a45d9c5ca78a88d0cb44c18000000000000000000000000a614f803b6fd780986a42c78ec9c7f77e6ded13c", (trxIn.selector + trxIn.param))
        assertEquals("TRX goes as call_value, not as an argument", 5_000_000L, trxIn.callValueSun)
        val trxOut = SunSwapV2.swapCall(TronProvider.USDT_MAINNET, null, big(5_000_000), big(4_950_000), owner, deadline, listOf(usdt, wtrx))
        assertEquals("18cbafe500000000000000000000000000000000000000000000000000000000004c4b4000000000000000000000000000000000000000000000000000000000004b87f000000000000000000000000000000000000000000000000000000000000000a0000000000000000000000000c8599111f29c1e1e061265b4af93ea1f274ad78a0000000000000000000000000000000000000000000000000000000069f1b3780000000000000000000000000000000000000000000000000000000000000002000000000000000000000000a614f803b6fd780986a42c78ec9c7f77e6ded13c000000000000000000000000891cdb91d149f23b1a45d9c5ca78a88d0cb44c18", (trxOut.selector + trxOut.param))
        assertEquals(0L, trxOut.callValueSun)
        assertEquals("095ea7b30000000000000000000000006e0617948fe030a7e4970f8389d4ad295f249b7e00000000000000000000000000000000000000000000000000000000004c4b40", SunSwapV2.approveData(router, big(5_000_000)).hex())
    }

    @Test fun triggerRaw_withCallValue_matchesReference() {
        val path = listOf(wtrx, usdt)
        val c = SunSwapV2.swapCall(null, TronProvider.USDT_MAINNET, big(5_000_000), big(4_950_000), owner, deadline, path)
        val raw = TronTx.triggerRaw(
            "2927".unhex(), "939ece39d08a7abe".unhex(), 1777447527000L, 1777447470038L,
            owner, router, (c.selector + c.param).unhex(), 50_000_000L, c.callValueSun
        )
        assertEquals("0a0229272208939ece39d08a7abe40d8c483c1dd335ad502081f12d0020a31747970652e676f6f676c65617069732e636f6d2f70726f746f636f6c2e54726967676572536d617274436f6e7472616374129a020a1541c8599111f29c1e1e061265b4af93ea1f274ad78a1215416e0617948fe030a7e4970f8389d4ad295f249b7e18c096b10222e4017ff36ab500000000000000000000000000000000000000000000000000000000004b87f00000000000000000000000000000000000000000000000000000000000000080000000000000000000000000c8599111f29c1e1e061265b4af93ea1f274ad78a0000000000000000000000000000000000000000000000000000000069f1b3780000000000000000000000000000000000000000000000000000000000000002000000000000000000000000891cdb91d149f23b1a45d9c5ca78a88d0cb44c18000000000000000000000000a614f803b6fd780986a42c78ec9c7f77e6ded13c70d68780c1dd33900180e1eb17", raw.hex())
        assertEquals("433afb88c50a1fcb057a682fe23c15122c12e665ad41ecd05b8324a0dd9069f1", TronTx.txId(raw).hex())
        // without call_value the field is simply absent (existing TRC-20 encodings are unchanged)
        val plain = TronTx.triggerRaw("2927".unhex(), "939ece39d08a7abe".unhex(), 1L shl 40, 1L shl 40, owner, router, ByteArray(4), 1_000_000L)
        val zero = TronTx.triggerRaw("2927".unhex(), "939ece39d08a7abe".unhex(), 1L shl 40, 1L shl 40, owner, router, ByteArray(4), 1_000_000L, 0L)
        assertEquals(plain.hex(), zero.hex())
    }

    @Test fun path_nativeIsWtrx_andTokenToTokenGoesThroughWtrx() {
        val u = TronProvider.USDT_MAINNET
        assertEquals(listOf(SunSwapV2.WTRX, u), SunSwapV2.path(null, u))
        assertEquals(listOf(u, SunSwapV2.WTRX), SunSwapV2.path(u, null))
        assertEquals(listOf(u, SunSwapV2.WTRX, "TXDk8mbtRbXeYuMNS83CfKPaYYT8XWv9Hz"), SunSwapV2.path(u, "TXDk8mbtRbXeYuMNS83CfKPaYYT8XWv9Hz"))
        try { SunSwapV2.path(u, u); fail() } catch (e: IllegalArgumentException) { }
        try { SunSwapV2.path(null, null); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun parseAmounts_readsTheLastOutput_andIsStrict() {
        val ok = SunSwapV2.parseAmounts("0000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000004c4b40000000000000000000000000000000000000000000000000000000000012d687")
        assertEquals(listOf(big(5_000_000), big(1_234_567)), ok)
        fun bad(hex: String) { try { SunSwapV2.parseAmounts(hex); fail("should reject") } catch (e: IllegalArgumentException) { } }
        bad("")
        bad("0000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000004c4b40000000000000000000000000000000000000000000000000000000000012d687".replaceFirst("0000000000000000000000000000000000000000000000000000000000000020", "0000000000000000000000000000000000000000000000000000000000000040"))
        bad("0000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000004c4b40000000000000000000000000000000000000000000000000000000000012d687" + "00")
        bad("0000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000004c4b40000000000000000000000000000000000000000000000000000000000012d687".dropLast(64))
        bad("0000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000004c4b40000000000000000000000000000000000000000000000000000000000012d687".replaceFirst("0000000000000000000000000000000000000000000000000000000000000002", "0000000000000000000000000000000000000000000000000000000000000009"))
    }

    @Test fun parseUint_andAddressWord() {
        assertEquals(big(5_000_000), SunSwapV2.parseUint("00000000000000000000000000000000000000000000000000000000004c4b40"))
        assertEquals("891cdb91d149f23b1a45d9c5ca78a88d0cb44c18", SunSwapV2.parseAddressWord("000000000000000000000000891cdb91d149f23b1a45d9c5ca78a88d0cb44c18"))
        try { SunSwapV2.parseUint("00"); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun impact_comparesTradeRateWithTheTinyTradeRate() {
        // pool price 0.25 USDT per TRX for the probe; the trade also gets 0.25 -> no impact
        assertEquals(0L, SunSwapV2.impactBps(big(100_000_000), big(25_000_000), big(1_000_000), big(250_000)))
        // the trade gets 0.2375 per TRX -> 5% worse
        assertEquals(500L, SunSwapV2.impactBps(big(100_000_000), big(23_750_000), big(1_000_000), big(250_000)))
        // better than the probe (rounding) is never negative
        assertEquals(0L, SunSwapV2.impactBps(big(100_000_000), big(26_000_000), big(1_000_000), big(250_000)))
        assertEquals(10_000L, SunSwapV2.impactBps(big(100), big(1), big(10), big(0)))
        assertTrue(SunSwapV2.impactBps(big(100_000_000), big(23_750_000), big(1_000_000), big(250_000)) > SunSwapV2.MAX_IMPACT_BPS)
    }

    @Test fun providerOffersSwaps_onMainnetAndNile() {
        val m = TronProvider.mainnet()
        assertTrue(m.supportsSwap)
        assertEquals(listOf("TRX", "USDT"), m.swapAssets().map { it.symbol })
        assertEquals(listOf<String?>(null, TronProvider.USDT_MAINNET), m.swapAssets().map { it.id })
        assertEquals(listOf(6, 6), m.swapAssets().map { it.decimals })
        assertTrue(m.swapAssets().all { it.curated })
        val nile = TronProvider()
        assertTrue(nile.isTestnet)
        assertTrue(nile.supportsSwap)
        assertEquals(listOf<String?>(null, SunSwapV2.NILE_USDT), nile.swapAssets().map { it.id })
        assertNotNull(m.swapProviderName)
        assertEquals(1, SunSwapV2.SLIPPAGE_BPS / 100)
    }

    @Test fun mainnetPinsTheRouterIdentity_nileDiscoversIt() {
        assertEquals(listOf(SunSwapV2.ROUTER), SunSwapV2.MAINNET.routers)
        assertEquals(SunSwapV2.WTRX, SunSwapV2.MAINNET.wtrx)
        assertEquals(SunSwapV2.FACTORY, SunSwapV2.MAINNET.factory)
        assertEquals(2, SunSwapV2.NILE.routers.size)
        assertEquals(null, SunSwapV2.NILE.wtrx)   // testnet: the router's own WETH()/factory() are used
        assertEquals(null, SunSwapV2.NILE.factory)
    }

    @Test fun nileAddresses_areValidBase58Check() {
        for (a in SunSwapV2.NILE.routers + SunSwapV2.NILE_USDT) assertNotNull(a, TronAddress.decode(a))
        assertEquals(
            "listed as the USDT of Nile in several independent references",
            "TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf", SunSwapV2.NILE_USDT
        )
        assertTrue(TronProvider.NILE_TOKENS.single().address == SunSwapV2.NILE_USDT)
    }

    @Test fun path_usesTheRoutersOwnWtrx() {
        val discovered = "TYsbWxNnyTgsZaTFaue9hqpxkU3Fkco94a"
        assertEquals(listOf(discovered, SunSwapV2.NILE_USDT), SunSwapV2.path(null, SunSwapV2.NILE_USDT, discovered))
        assertEquals(listOf(SunSwapV2.NILE_USDT, discovered), SunSwapV2.path(SunSwapV2.NILE_USDT, null, discovered))
    }
}
