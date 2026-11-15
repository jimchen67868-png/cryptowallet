package com.example.wallet.chain

import com.example.wallet.abandonSeed
import com.example.wallet.crypto.Base58
import com.example.wallet.crypto.Hd
import com.example.wallet.hex
import com.example.wallet.unhex
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Expected transactions come from an independent reference implementation (tools/verify/golden.py)
 * whose Ed25519 signer is checked against RFC 8032 test vector 1 (see the first test).
 */
class SolanaTxTest {
    private val sk = Hd.ed25519PrivateKey(abandonSeed(), 44, 501, 0, 0)
    private val pk = pubOf(sk)
    private val blockhash = ByteArray(32) { 7 }

    private fun pubOf(secret: ByteArray): ByteArray {
        val out = ByteArray(32)
        Ed25519.generatePublicKey(secret, 0, out, 0)
        return out
    }

    @Test fun ed25519_rfc8032_vector1() {
        val secret = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".unhex()
        val pub = pubOf(secret)
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", pub.hex())
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            SolanaTx.sign(secret, pub, ByteArray(0)).hex()
        )
    }

    @Test fun ownerAddress_matchesKnownValue() {
        assertEquals("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", Base58.encode(pk))
    }

    // ---- SystemProgram.Transfer ----
    private val destination = pubOf(ByteArray(32) { 2 })

    @Test fun systemTransfer_instructionData() {
        val ix = SolanaTx.systemTransfer(pk, destination, 1_000_000_000)
        assertEquals("0200000000ca9a3b00000000", ix.data.hex()) // u32 LE 2, u64 LE 1e9
    }

    @Test fun systemTransfer_signedTransaction() {
        val message = SolanaTx.buildMessage(pk, listOf(SolanaTx.systemTransfer(pk, destination, 1_000_000_000)), blockhash)
        assertEquals(
            "01000103f036276246a75b9de3349ed42b15e232f6518fc20f5fcd4f1d64e81f9bd258f7" +
                "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0707070707070707070707070707070707070707070707070707070707070707" +
                "01020200010c0200000000ca9a3b00000000",
            message.hex()
        )
        val signature = SolanaTx.sign(sk, pk, message)
        assertEquals(EXPECTED_TRANSFER_SIG, signature.hex())
        // wire format: 1 signature, then the 64-byte signature, then the message
        assertEquals("01" + EXPECTED_TRANSFER_SIG + message.hex(), SolanaTx.serialize(signature, message).hex())
    }

    // ---- SPL token transfer ----
    private val tokenProgram = Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")!!
    private val mint = Base58.decode("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")!!
    private val source = pubOf(ByteArray(32) { 3 })

    @Test fun associatedTokenAddress_matchesReference() {
        val ata = SolanaTx.associatedTokenAddress(destination, mint, tokenProgram)
        assertEquals("GzpVTWkyGGfBXRaprnrhV3JtGj3TT52z5w2CrEJsTfjm", Base58.encode(ata))
        assertFalse("a PDA must not be a valid curve point", Ed25519.validatePublicKeyPartial(ata, 0))
    }

    @Test fun tokenTransferChecked_instructionData() {
        val ix = SolanaTx.tokenTransferChecked(source, mint, destination, pk, tokenProgram, 1_000_000, 6)
        assertEquals("0c40420f000000000006", ix.data.hex()) // 12, u64 LE 1_000_000, decimals 6
    }

    @Test fun tokenTransfer_withCreateAta_signedTransaction() {
        val ata = SolanaTx.associatedTokenAddress(destination, mint, tokenProgram)
        val ixs = listOf(
            SolanaTx.createAssociatedTokenAccountIdempotent(pk, ata, destination, mint, tokenProgram),
            SolanaTx.tokenTransferChecked(source, mint, ata, pk, tokenProgram, 1_000_000, 6)
        )
        val message = SolanaTx.buildMessage(pk, ixs, blockhash)
        assertEquals(EXPECTED_SPL_MESSAGE, message.hex())
        val signature = SolanaTx.sign(sk, pk, message)
        assertEquals(EXPECTED_SPL_SIG, signature.hex())
        assertEquals("01" + EXPECTED_SPL_SIG + message.hex(), SolanaTx.serialize(signature, message).hex())
    }

    @Test fun tokenTransfer_existingAta_message() {
        val ata = SolanaTx.associatedTokenAddress(destination, mint, tokenProgram)
        val message = SolanaTx.buildMessage(
            pk, listOf(SolanaTx.tokenTransferChecked(source, mint, ata, pk, tokenProgram, 1_000_000, 6)), blockhash
        )
        assertEquals(EXPECTED_SPL_NO_CREATE_MESSAGE, message.hex())
    }

    // ---- constants ----
    @Test fun knownMintsAndPrograms_areValid32ByteKeys() {
        val all = SolanaProvider.DEFAULT_KNOWN_MINTS.keys + listOf(
            "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL",
            "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
            "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",
            "11111111111111111111111111111111"
        )
        for (s in all) assertEquals(s, 32, Base58.decode(s)?.size)
        assertNotNull(SolanaTx.ATA_PROGRAM)
    }

    private companion object {
        const val EXPECTED_TRANSFER_SIG =
            "0208010b17454466fad04b9f9323e87ac7e86e328de18b6e097fb1c150c3dfe03a286f340bfac2a8e74af1f89106f2d7088d31f554941bdf6745eb7af78d9301"

        const val EXPECTED_SPL_SIG =
            "092872ee5807426bb8dcfffa8da7e9d50b088bd3dad1b52dc16ccbc989a5c85c54c3624e7b37d8d84f271c15bde05c8153d9433ddd2cd17cf362b1c8a2580803"

        const val EXPECTED_SPL_MESSAGE =
            "01000508f036276246a75b9de3349ed42b15e232f6518fc20f5fcd4f1d64e81f9bd258f7" +
            "edaf1b9c161f6d698a46b9b07809a4a18dbf53c432b6a24bf1b10883fccb2298" +
            "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1" +
            "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394" +
            "3b442cb3912157f13a933d0134282d032b5ffecd01a2dbf1b7790608df002ea7" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "06ddf6e1d765a193d9cbe146ceeb79ac1cb485ed5f5b37913a8cf5857eff00a9" +
            "8c97258f4e2489f1bb3d1029148e0d830b5a1399daff1084048e7bd8dbe9f859" +
            "0707070707070707070707070707070707070707070707070707070707070707" +
            "02070600010304050601010604020401000a0c40420f000000000006"

        const val EXPECTED_SPL_NO_CREATE_MESSAGE =
            "01000205f036276246a75b9de3349ed42b15e232f6518fc20f5fcd4f1d64e81f9bd258f7" +
            "edaf1b9c161f6d698a46b9b07809a4a18dbf53c432b6a24bf1b10883fccb2298" +
            "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1" +
            "3b442cb3912157f13a933d0134282d032b5ffecd01a2dbf1b7790608df002ea7" +
            "06ddf6e1d765a193d9cbe146ceeb79ac1cb485ed5f5b37913a8cf5857eff00a9" +
            "0707070707070707070707070707070707070707070707070707070707070707" +
            "010404020301000a0c40420f000000000006"
    }
}
