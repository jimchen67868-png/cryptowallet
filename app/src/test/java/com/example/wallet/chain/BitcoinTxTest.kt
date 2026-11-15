package com.example.wallet.chain

import com.example.wallet.abandonSeed
import com.example.wallet.crypto.Hd
import com.example.wallet.hex
import com.example.wallet.unhex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import java.math.BigInteger

class BitcoinTxTest {

    private fun rev(hex: String) = hex.chunked(2).reversed().joinToString("")

    /**
     * BIP-143 "Native P2WPKH" example: a transaction with one non-segwit input and one P2WPKH input.
     * Checks the sighash and the exact deterministic (RFC 6979) signature Bitcoin Core produces.
     */
    @Test fun bip143_nativeP2wpkh_sighashAndSignature() {
        val inputs = listOf(
            Utxo(rev("fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f"), 0, 625_000_000),
            Utxo(rev("ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a"), 1, 600_000_000)
        )
        val outputs = listOf(
            TxOut(112_340_000, "76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac".unhex()),
            TxOut(223_450_000, "76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac".unhex())
        )
        val key = ECKeyPair.create(BigInteger("619c335025c7f4012e556c2a58b2506e30b8511b53ade95ea316fd8c3286feb9", 16))
        val pubKey = Sign.publicPointFromPrivate(key.privateKey).getEncoded(true)
        assertEquals("025476c2e83188368da1ff3e292e7acafcdb3566bb0ad253f62fc70f07aeee6357", pubKey.hex())
        val pkh = Hd.hash160(pubKey)
        assertEquals("1d0f172a0ecb48aee1be1f2687d2963ae33f71a1", pkh.hex())

        val sighash = BitcoinTx.sighashP2wpkh(
            version = 1, inputs = inputs, sequences = listOf(0xffffffeeL, 0xffffffffL),
            outputs = outputs, index = 1, pubKeyHash = pkh, locktime = 0x11
        )
        assertEquals("c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670", sighash.hex())

        assertEquals(
            "304402203609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a" +
                "0220573a954c4518331561406f90300e8f3358f51928d43c212a8caed02de67eebee01",
            BitcoinTx.signHash(key, sighash).hex()
        )
    }

    /**
     * Full signed 2-input / 2-output P2WPKH transaction for the BIP-84 key of the standard mnemonic.
     * Expected bytes come from an independent reference implementation that reproduces the BIP-143
     * vector above (see tools/verify/golden.py). The second signature has the high bit set in r,
     * which exercises DER padding.
     */
    @Test fun signedTransaction_matchesReferenceImplementation() {
        val key = Hd.secp256k1(abandonSeed(), 84 or Hd.H, 0 or Hd.H, 0 or Hd.H, 0, 0)
        val pub = key.publicKeyPoint.getEncoded(true)
        val pkh = Hd.hash160(pub)

        val raw = BitcoinTx.signP2wpkh(
            inputs = listOf(Utxo("11".repeat(32), 0, 50_000), Utxo("22".repeat(32), 1, 70_000)),
            outputs = listOf(
                TxOut(100_000, "0014751e76e8199196d454941c45d1b3a323f1433bd6".unhex()),
                TxOut(15_000, ("0014" + pkh.hex()).unhex())
            ),
            key = key, pubKey = pub, pubKeyHash = pkh
        )
        assertEquals(EXPECTED_RAW_TX, raw.hex())
    }

    @Test fun signing_isDeterministic() {
        val key = Hd.secp256k1(abandonSeed(), 84 or Hd.H, 0 or Hd.H, 0 or Hd.H, 0, 0)
        val pub = key.publicKeyPoint.getEncoded(true)
        val args = Triple(listOf(Utxo("33".repeat(32), 0, 10_000)), listOf(TxOut(9_000, "0014".unhex() + ByteArray(20))), key)
        val a = BitcoinTx.signP2wpkh(args.first, args.second, args.third, pub, Hd.hash160(pub))
        val b = BitcoinTx.signP2wpkh(args.first, args.second, args.third, pub, Hd.hash160(pub))
        assertEquals(a.hex(), b.hex())
    }

    // ---- coin selection ----

    private fun u(value: Long, n: Int = 0) = Utxo("%064x".format(n + 1), 0, value)
    private val p2wpkhSize = 22

    @Test fun vsize_formula() {
        assertEquals(141, CoinSelection.vsize(1, p2wpkhSize, true))   // 11 + 68 + 31 + 31
        assertEquals(110, CoinSelection.vsize(1, p2wpkhSize, false))  // 11 + 68 + 31
        assertEquals(209, CoinSelection.vsize(2, p2wpkhSize, true))
    }

    @Test fun select_singleInput_withChange() {
        val p = CoinSelection.select(listOf(u(100_000)), 50_000, p2wpkhSize, 2.0)
        assertEquals(1, p.inputs.size)
        assertEquals(282, p.fee)             // 141 vB * 2 sat/vB
        assertEquals(49_718, p.change)
    }

    @Test fun select_dustChange_goesToMiner() {
        val p = CoinSelection.select(listOf(u(50_500)), 50_000, p2wpkhSize, 2.0)
        assertEquals(0, p.change)
        assertEquals(500, p.fee)             // everything not sent is the fee
    }

    @Test fun select_exactFit_noChangeOutput() {
        val p = CoinSelection.select(listOf(u(50_110)), 50_000, p2wpkhSize, 1.0)
        assertEquals(0, p.change)
        assertEquals(110, p.fee)
    }

    @Test fun select_prefersLargestFirst_andAddsInputsAsNeeded() {
        val p = CoinSelection.select(listOf(u(20_000, 1), u(40_000, 2), u(30_000, 3)), 60_000, p2wpkhSize, 1.0)
        assertEquals(listOf(40_000L, 30_000L), p.inputs.map { it.value })
        assertEquals(209, p.fee)
        assertEquals(9_791, p.change)
    }

    @Test fun select_feeIsRoundedUp() {
        val p = CoinSelection.select(listOf(u(100_000)), 50_000, p2wpkhSize, 1.5)
        assertEquals(212, p.fee)             // ceil(141 * 1.5) = ceil(211.5)
    }

    @Test fun select_insufficientFunds_throws() {
        for ((utxos, sats) in listOf(
            listOf(u(10_000)) to 50_000L,
            listOf(u(50_000)) to 50_000L,      // covers the amount but not the fee
            emptyList<Utxo>() to 1_000L
        )) {
            try {
                CoinSelection.select(utxos, sats, p2wpkhSize, 1.0)
                fail("expected IllegalStateException for $sats")
            } catch (e: IllegalStateException) { /* expected */ }
        }
    }

    @Test fun select_invariant_inputsEqualOutputsPlusFee() {
        val utxos = listOf(u(12_345, 1), u(67_890, 2), u(1_111, 3), u(250_000, 4))
        for (sats in listOf(546L, 5_000L, 70_000L, 300_000L)) {
            for (rate in listOf(1.0, 2.5, 10.0, 55.5)) {
                val p = CoinSelection.select(utxos, sats, p2wpkhSize, rate)
                val inTotal = p.inputs.sumOf { it.value }
                assertEquals("sats=$sats rate=$rate", inTotal, sats + p.change + p.fee)
                assertTrue(p.change == 0L || p.change >= CoinSelection.DUST)
                assertTrue(p.fee >= Math.ceil(rate * CoinSelection.vsize(p.inputs.size, p2wpkhSize, p.change > 0)).toLong())
            }
        }
    }

    private companion object {
        const val EXPECTED_RAW_TX =
            "0200000000010211111111111111111111111111111111111111111111111111111111111111110000000000fdffffff" +
            "22222222222222222222222222222222222222222222222222222222222222220100000000fdffffff02" +
            "a086010000000000160014751e76e8199196d454941c45d1b3a323f1433bd6" +
            "983a000000000000160014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2" +
            "02473044022073eb8f4bbcd85890bb3e7783217472f77d35e4b8411f2aa3d191cd1a55845b27022058eddc4eba718a5a00f4370ef8c5139f543312424c8107b363027ff8a8eeb9bb01" +
            "210330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c" +
            "02483045022100c84c656eb06a350991333ac0923f65bb882fee383e72eba7141eee11f02eb2cc02201ecb862494caca3df77b3995cd6f9578956dd7ba3d0ac1720c839117988b682701" +
            "210330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c" +
            "00000000"
    }
}
