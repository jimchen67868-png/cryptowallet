package com.example.wallet.chain

import com.example.wallet.abandonSeed
import com.example.wallet.crypto.Hd
import com.example.wallet.data.CoinIds
import com.example.wallet.hex
import com.example.wallet.unhex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.web3j.crypto.ECDSASignature
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Tron. Expected values come from published documentation (TronGrid/dRPC/Chainstack captures, the well-known address of
 * private key 1) or from tools/verify/tron_golden.py (independent pure-Python implementation).
 * Not covered: live TronGrid responses (the JSON tests use shapes from the public docs, not captured data).
 */
class TronTest {
    private val provider = TronProvider()
    private val owner = "41c8599111f29c1e1e061265b4af93ea1f274ad78a".unhex()
    private val dest = "4192ad11c1bf16b3b14b0bd6b5c7e2db73a0b5e83a".unhex()
    private val key get() = Hd.secp256k1(abandonSeed(), 44 or Hd.H, 195 or Hd.H, 0 or Hd.H, 0, 0)
    private val usdt = TronProvider.USDT_MAINNET

    // ---- addresses ----

    @Test fun addressOfPrivateKeyOne_isTheWellKnownOne() {
        val addr = TronAddress.fromKeyPair(ECKeyPair.create(BigInteger.ONE))
        assertEquals("417e5f4552091a69125d5dfcb7b8c2659029395bdf", addr.hex())
        assertEquals("TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC", TronAddress.toBase58(addr))
    }

    @Test fun standardMnemonic_derivesKnownTronAddress() {
        assertEquals("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH", provider.deriveAddress(abandonSeed()))
        assertEquals(owner.hex(), TronAddress.decode("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH")!!.hex())
    }

    @Test fun validation_acceptsRealAddresses_rejectsEverythingElse() {
        assertTrue(provider.isValidAddress("TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC"))
        assertTrue(provider.isValidAddress(" TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH "))
        assertTrue(provider.isValidAddress(usdt))
        assertFalse("bad checksum", provider.isValidAddress("TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HD"))
        assertFalse("one char short", provider.isValidAddress("TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2H"))
        assertFalse("bitcoin", provider.isValidAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
        assertFalse("ethereum", provider.isValidAddress("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"))
        assertFalse("hex form", provider.isValidAddress("417e5f4552091a69125d5dfcb7b8c2659029395bdf"))
        assertFalse("empty", provider.isValidAddress(""))
    }

    @Test fun hexToBase58_roundTrips_andRejectsGarbage() {
        assertEquals("TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC", TronAddress.hexToBase58("417e5f4552091a69125d5dfcb7b8c2659029395bdf"))
        assertNull(TronAddress.hexToBase58("007e5f4552091a69125d5dfcb7b8c2659029395bdf")) // wrong prefix
        assertNull(TronAddress.hexToBase58("41zz"))
        assertNull(TronAddress.hexToBase58(""))
    }

    // ---- transaction encoding ----

    /** Published capture (Chainstack docs, mainnet). The docs truncate raw_data_hex, so the prefix is compared. */
    @Test fun transferRaw_matchesPublishedCapture_chainstack() {
        val raw = TronTx.transferRaw(
            "5e4b".unhex(), "47c9dc89341b300d".unhex(), 1591089627000L, 1591089567635L,
            "41608f8da72479edc7dd921e4c30bb7e7cddbe722e".unhex(), "41e9d79cc47518930bc322d9bf7cddd260a0260a8d".unhex(), 1000
        )
        val published = "0a025e4b220847c9dc89341b300d40f8fed3a2a72e5a66080112620a2d747970652e676f6f676c65617069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e747261637412310a1541608f8da72479edc7dd921e4c30bb7e7cddbe722e121541e9d79cc47518930bc322d9bf7"
        assertTrue(raw.hex().startsWith(published))
        assertEquals("0a025e4b220847c9dc89341b300d40f8fed3a2a72e5a66080112620a2d747970652e676f6f676c65617069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e747261637412310a1541608f8da72479edc7dd921e4c30bb7e7cddbe722e121541e9d79cc47518930bc322d9bf7cddd260a0260a8d18e8077093afd0a2a72e", raw.hex())
        assertEquals("77ddfa7093cc5f745c0d3a54abb89ef070f983343c05e0f89e5a52f3e5401299", TronTx.txId(raw).hex())
    }

    /** Published Nile capture (tronprotocol docs) with its published txID (dRPC docs): a complete end-to-end match. */
    @Test fun transferRaw_matchesPublishedCapture_nile_includingTxId() {
        val raw = TronTx.transferRaw(
            "2927".unhex(), "939ece39d08a7abe".unhex(), 1777447527000L, 1777447470038L,
            "41dd791d6b49e190062d650e6a23c575510d35f2f9".unhex(), dest, 1_000_000
        )
        assertEquals("0a0229272208939ece39d08a7abe40d8c483c1dd335a67080112630a2d747970652e676f6f676c65617069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e747261637412320a1541dd791d6b49e190062d650e6a23c575510d35f2f912154192ad11c1bf16b3b14b0bd6b5c7e2db73a0b5e83a18c0843d70d68780c1dd33", raw.hex())
        assertEquals("6303c7639f591407ef9f34a7ac8c9c9a21151f5a6af21924502515734fc267ab", TronTx.txId(raw).hex())
    }

    @Test fun signedTransfer_matchesReferenceImplementation() {
        val raw = TronTx.transferRaw("2927".unhex(), "939ece39d08a7abe".unhex(), 1777447527000L, 1777447470038L, owner, dest, 1_000_000)
        val sig = TronTx.sign(key, raw)
        assertEquals("ffcbb631119762bf0aea8e723eeeba79ca295a39d905641090a98b811e62ab3642cc0c5fc081d759a9e417d81c659459efecb67c0e252f77049032e78db3ecdc00", sig.hex())
        assertEquals("0a85010a0229272208939ece39d08a7abe40d8c483c1dd335a67080112630a2d747970652e676f6f676c65617069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e747261637412320a1541c8599111f29c1e1e061265b4af93ea1f274ad78a12154192ad11c1bf16b3b14b0bd6b5c7e2db73a0b5e83a18c0843d70d68780c1dd331241ffcbb631119762bf0aea8e723eeeba79ca295a39d905641090a98b811e62ab3642cc0c5fc081d759a9e417d81c659459efecb67c0e252f77049032e78db3ecdc00", TronTx.signedTransaction(raw, sig).hex())
    }

    @Test fun signature_isRecoverableToTheSigningKey_lowS() {
        val raw = TronTx.transferRaw("2927".unhex(), "939ece39d08a7abe".unhex(), 1L shl 40, 1L shl 40, owner, dest, 7)
        val sig = TronTx.sign(key, raw)
        val ec = ECDSASignature(BigInteger(1, sig.copyOfRange(0, 32)), BigInteger(1, sig.copyOfRange(32, 64)))
        assertEquals(key.publicKey, Sign.recoverFromSignature(sig[64].toInt(), ec, TronTx.txId(raw)))
        assertTrue("recovery id is 0 or 1", sig[64].toInt() in 0..1)
        val halfN = BigInteger("7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0", 16)
        assertTrue("low-S", ec.s <= halfN)
    }

    @Test fun trc20Transfer_dataAndRawMatchReferenceImplementation() {
        val data = TronTx.trc20TransferData(dest, BigInteger.valueOf(5_000_000))
        assertEquals("a9059cbb00000000000000000000000092ad11c1bf16b3b14b0bd6b5c7e2db73a0b5e83a00000000000000000000000000000000000000000000000000000000004c4b40", data.hex())
        val contract = TronAddress.decode(usdt)!!
        assertEquals("41a614f803b6fd780986a42c78ec9c7f77e6ded13c", contract.hex())
        val raw = TronTx.triggerRaw("2927".unhex(), "939ece39d08a7abe".unhex(), 1777447527000L, 1777447470038L, owner, contract, data, 100_000_000L)
        assertEquals("0a0229272208939ece39d08a7abe40d8c483c1dd335aae01081f12a9010a31747970652e676f6f676c65617069732e636f6d2f70726f746f636f6c2e54726967676572536d617274436f6e747261637412740a1541c8599111f29c1e1e061265b4af93ea1f274ad78a121541a614f803b6fd780986a42c78ec9c7f77e6ded13c2244a9059cbb00000000000000000000000092ad11c1bf16b3b14b0bd6b5c7e2db73a0b5e83a00000000000000000000000000000000000000000000000000000000004c4b4070d68780c1dd33900180c2d72f", raw.hex())
        assertEquals("e6d071781458435084f1c6e857aa68fe01ef5a387c78a056a4b118f2044d2439", TronTx.txId(raw).hex())
    }

    @Test fun trc20Parameter_isTheTwoWordsWithoutSelector() {
        val p = TronTx.trc20TransferParameter(dest, BigInteger.valueOf(5_000_000))
        assertEquals(128, p.length)
        assertTrue(p.startsWith("0".repeat(24) + "92ad11c1bf16b3b14b0bd6b5c7e2db73a0b5e83a"))
        assertTrue(p.endsWith("4c4b40"))
    }

    @Test fun builders_rejectBadInput() {
        val r = "2927".unhex(); val h = "939ece39d08a7abe".unhex()
        fun bad(block: () -> Unit) {
            try { block(); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) { }
        }
        bad { TronTx.transferRaw(r, h, 1, 1, owner, dest, 0) }
        bad { TronTx.transferRaw(r, h, 1, 1, owner.copyOf(20), dest, 1) }
        bad { TronTx.transferRaw(ByteArray(3), h, 1, 1, owner, dest, 1) }
        bad { TronTx.triggerRaw(r, h, 1, 1, owner, dest, ByteArray(4), 0) }
        bad { TronTx.trc20TransferData(dest, BigInteger.ZERO) }
        bad { TronTx.signedTransaction(ByteArray(10), ByteArray(64)) }
    }

    @Test fun blockReference_usesLastTwoBytesOfNumberAndMiddleOfId() {
        val id = "0000000001234567" + "89abcdef01234567" + "0011223344556677" + "8899aabbccddeeff"
        val (bytes, hash) = TronTx.blockReference(id, 0x01234567L)
        assertEquals("4567", bytes.hex())
        assertEquals("89abcdef01234567", hash.hex())
        try { TronTx.blockReference(id, 0x01234568L); fail("number/id mismatch must be rejected") } catch (e: IllegalArgumentException) { }
        try { TronTx.blockReference("00", 0); fail("short id must be rejected") } catch (e: IllegalArgumentException) { }
    }

    @Test fun transferWithMemo_putsMemoInField10_beforeTheContract() {
        val memo = "=:BTC.BTC:bc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh:1234567".toByteArray()
        val withMemo = TronTx.transferRaw("2927".unhex(), "939ece39d08a7abe".unhex(), 1777447527000L, 1777447470038L, owner, dest, 1_000_000, memo)
        assertEquals("0a0229272208939ece39d08a7abe40d8c483c1dd33523c3d3a4254432e4254433a626331716a6d786e7a37386e6d63386e7137377775786832356e32657337727a6d356332726b6b3477683a313233343536375a67080112630a2d747970652e676f6f676c65617069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e747261637412320a1541c8599111f29c1e1e061265b4af93ea1f274ad78a12154192ad11c1bf16b3b14b0bd6b5c7e2db73a0b5e83a18c0843d70d68780c1dd33", withMemo.hex())
        assertEquals("1f92ed35e7d405208c3f47145c400dea1e57d4702b3116deab6747732eee7917", TronTx.txId(withMemo).hex())
        val without = TronTx.transferRaw("2927".unhex(), "939ece39d08a7abe".unhex(), 1777447527000L, 1777447470038L, owner, dest, 1_000_000, null)
        assertEquals(without.hex(), TronTx.transferRaw("2927".unhex(), "939ece39d08a7abe".unhex(), 1777447527000L, 1777447470038L, owner, dest, 1_000_000, ByteArray(0)).hex())
        assertTrue("memo adds its bytes plus a 2-byte header", withMemo.size == without.size + memo.size + 2)
    }

    // ---- fees ----

    @Test fun fees_burnOnlyWhatTheAccountLacks() {
        assertEquals(0L, TronFee.bandwidthSun(267, 600, 1000))
        assertEquals(267_000L, TronFee.bandwidthSun(267, 266, 1000))
        assertEquals(0L, TronFee.energySun(65_000, 70_000, 420))
        assertEquals(5_000L * 420, TronFee.energySun(65_000, 60_000, 420))
        assertEquals(65_000L * 420, TronFee.energySun(65_000, -5, 420))
    }

    @Test fun feeLimit_hasHeadroom_floor_andCeiling() {
        assertEquals(12_500_000L, TronFee.feeLimit(10_000_000))
        assertEquals(1_000_000L, TronFee.feeLimit(0))
        assertEquals(TronFee.MAX_FEE_LIMIT_SUN, TronFee.feeLimit(10_000_000_000_000L))
    }

    @Test fun bandwidthBytes_ofATransfer_isAbout267() {
        val raw = TronTx.transferRaw("2927".unhex(), "939ece39d08a7abe".unhex(), 1777447527000L, 1777447470038L, owner, dest, 1_000_000)
        assertEquals(267, TronFee.bandwidthBytes(raw.size)) // the figure usually quoted for a TRX transfer
    }

    // ---- JSON parsing (shapes follow the TronGrid v1 docs; not captured from the live API) ----

    private val me = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"
    private val other = "TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC"

    @Test fun parse_accountBalances_andInactiveAccount() {
        val j = JSONObject("""{"data":[{"balance":2500000,"trc20":[{"$usdt":"12340000"},{"TScam":"1"}]}],"success":true}""")
        val acc = TronParse.account(j)
        assertEquals(2_500_000L, TronParse.trxBalanceSun(acc))
        assertEquals(BigInteger.valueOf(12_340_000), TronParse.trc20Balances(acc)[usdt])
        val none = TronParse.account(JSONObject("""{"data":[],"success":true}"""))
        assertNull(none)
        assertEquals(0L, TronParse.trxBalanceSun(none))
        assertTrue(TronParse.trc20Balances(none).isEmpty())
    }

    @Test fun parse_nativeHistory_directionsAndStatus() {
        val meHex = "41c8599111f29c1e1e061265b4af93ea1f274ad78a"
        val otherHex = "417e5f4552091a69125d5dfcb7b8c2659029395bdf"
        fun tx(id: String, from: String, to: String, sun: Long, ret: String = "SUCCESS", type: String = "TransferContract") =
            """{"txID":"$id","block_timestamp":1700000000000,"ret":[{"contractRet":"$ret"}],"raw_data":{"contract":[{"type":"$type","parameter":{"value":{"owner_address":"$from","to_address":"$to","amount":$sun}}}]}}"""
        val j = JSONObject(
            """{"data":[${tx("a", otherHex, meHex, 1_500_000)},${tx("b", meHex, otherHex, 2_000_000)},""" +
                """${tx("c", meHex, otherHex, 1, "OUT_OF_ENERGY")},${tx("d", meHex, otherHex, 1, type = "TriggerSmartContract")}]}"""
        )
        val h = TronParse.nativeHistory(j, me)
        assertEquals(listOf("a", "b", "c"), h.map { it.id }) // the contract call is skipped
        assertEquals(TxDirection.IN, h[0].direction)
        assertEquals(other, h[0].counterparty)
        assertEquals(0, BigDecimal("1.5").compareTo(h[0].amount))
        assertEquals(TxDirection.OUT, h[1].direction)
        assertEquals(other, h[1].counterparty)
        assertEquals(TxStatus.FAILED, h[2].status)
        assertEquals(1_700_000_000L, h[0].timeSec)
        assertEquals("TRX", h[0].symbol)
    }

    @Test fun parse_tokenHistory_onlyKnownTokens() {
        val known = mapOf(usdt to 6)
        val j = JSONObject(
            """{"data":[
            {"transaction_id":"t1","block_timestamp":1700000000000,"from":"$other","to":"$me","value":"2500000","type":"Transfer",
              "token_info":{"symbol":"USDT","address":"$usdt","decimals":6}},
            {"transaction_id":"t2","block_timestamp":1700000001000,"from":"$other","to":"$me","value":"1","type":"Transfer",
              "token_info":{"symbol":"USDT","address":"TFakeUsdt00000000000000000000000000","decimals":6}}]}"""
        )
        val h = TronParse.tokenHistory(j, me, known)
        assertEquals(1, h.size)
        assertEquals(0, BigDecimal("2.5").compareTo(h[0].amount))
        assertEquals(TxDirection.IN, h[0].direction)
        assertEquals(other, h[0].counterparty)
    }

    @Test fun errorText_decodesHexMessages() {
        val msg = "Contract validate error".toByteArray().hex()
        assertEquals("SIGERROR: Contract validate error", TronParse.errorText(JSONObject("""{"code":"SIGERROR","message":"$msg"}""")))
        assertEquals("unknown error", TronParse.errorText(JSONObject("{}")))
    }

    // ---- configuration ----

    @Test fun mainnetProvider_listsUsdtOnly_andPricesIt() {
        val m = TronProvider.mainnet()
        assertFalse(m.isTestnet)
        assertEquals("Mainnet", m.networkName)
        assertTrue(provider.isTestnet)
        assertEquals("Nile testnet", provider.networkName)
        assertEquals(listOf("USDT"), TronProvider.MAINNET_TOKENS.map { it.symbol })
        assertEquals("tether", CoinIds.forAsset(Chain.TRX, usdt))
        assertEquals("tron", CoinIds.forAsset(Chain.TRX, null))
        assertNull("a case-changed contract must not get a price", CoinIds.forAsset(Chain.TRX, usdt.lowercase()))
        assertTrue(m.explorerUrl("abc").endsWith("/#/transaction/abc"))
        assertNotNull(TronAddress.decode(usdt))
    }
}
