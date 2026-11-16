package com.example.wallet.chain

import com.example.wallet.abandonSeed
import com.example.wallet.crypto.Base58
import com.example.wallet.crypto.Hd
import com.example.wallet.hex
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger

class SolanaSwapTest {
    private val sk = Hd.ed25519PrivateKey(abandonSeed(), 44, 501, 0, 0)
    private val user = pubOf(sk)
    private val other = pubOf(ByteArray(32) { 9 })
    private val pool = pubOf(ByteArray(32) { 5 })
    private val blockhash = ByteArray(32) { 7 }

    private fun pubOf(secret: ByteArray): ByteArray = ByteArray(32).also { Ed25519.generatePublicKey(secret, 0, it, 0) }
    private fun key(b58: String) = Base58.decode(b58)!!

    private val computeBudget = key(SolanaSwapGuard.COMPUTE_BUDGET)
    private val jupiter = key(SolanaSwapGuard.JUPITER_V6)
    private val system = key(SolanaSwapGuard.SYSTEM_PROGRAM)
    private val tokenProgram = key(SolanaSwapGuard.TOKEN_PROGRAM)
    private val usdcMint = key("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
    private val wsolMint = key(SolanaSwapGuard.WSOL_MINT)
    private val usdcAta = SolanaTx.associatedTokenAddress(user, usdcMint, tokenProgram)
    private val wsolAta = SolanaTx.associatedTokenAddress(user, wsolMint, tokenProgram)

    private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xff).toByte() }
    private fun le64(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xff).toByte() }

    // ---- instruction builders ----
    private fun limit(units: Long) = SolIx(computeBudget, emptyList(), byteArrayOf(2) + le32(units))
    private fun price(microLamports: Long) = SolIx(computeBudget, emptyList(), byteArrayOf(3) + le64(microLamports))
    private val route = SolIx(jupiter, listOf(SolMeta(user, true, true), SolMeta(pool, false, true), SolMeta(usdcAta, false, true)), byteArrayOf(1, 2, 3))
    private fun ataFor(owner: ByteArray) = SolanaTx.createAssociatedTokenAccountIdempotent(user, usdcAta, owner, usdcMint, tokenProgram)
    private val fundWsol = SolanaTx.systemTransfer(user, wsolAta, 1_000_000)
    private val syncNative = SolIx(tokenProgram, listOf(SolMeta(wsolAta, false, true)), byteArrayOf(17))
    private fun close(dest: ByteArray, owner: ByteArray = user) = SolIx(
        tokenProgram, listOf(SolMeta(wsolAta, false, true), SolMeta(dest, false, true), SolMeta(owner, false, false)), byteArrayOf(9)
    )

    private fun tx(vararg ixs: SolIx, payer: ByteArray = user): ByteArray =
        SolanaTx.serialize(ByteArray(64), SolanaTx.buildMessage(payer, ixs.toList(), blockhash))

    private fun verdict(t: ByteArray) = SolanaSwapGuard.validateTransaction(t, user)

    // ---- v0 builder (what Jupiter actually sends) ----
    private fun shortVec(n: Int): ByteArray =
        if (n < 0x80) byteArrayOf(n.toByte()) else byteArrayOf(((n and 0x7f) or 0x80).toByte(), (n shr 7).toByte())

    private class RawIx(val program: Int, val accounts: List<Int>, val data: ByteArray)

    private fun v0Tx(keys: List<ByteArray>, ixs: List<RawIx>, lookups: Int = 1): ByteArray {
        val m = ByteArrayOutputStream()
        m.write(0x80); m.write(1); m.write(0); m.write(1)            // v0 marker, header (1 signer, 0 ro-signed, 1 ro-unsigned)
        m.write(shortVec(keys.size)); keys.forEach { m.write(it) }
        m.write(blockhash)
        m.write(shortVec(ixs.size))
        for (ix in ixs) {
            m.write(ix.program); m.write(shortVec(ix.accounts.size))
            ix.accounts.forEach { m.write(it) }
            m.write(shortVec(ix.data.size)); m.write(ix.data)
        }
        m.write(shortVec(lookups))
        repeat(lookups) {
            m.write(ByteArray(32) { 0x33 })                           // lookup table address
            m.write(shortVec(2)); m.write(1); m.write(2)              // writable indexes
            m.write(shortVec(1)); m.write(3)                          // readonly indexes
        }
        return shortVec(1) + ByteArray(64) + m.toByteArray()
    }

    // ---------------- parser ----------------
    @Test fun parser_legacyTransaction() {
        val p = SolanaTxParser.parse(tx(SolanaTx.systemTransfer(user, other, 5)))!!
        assertFalse(p.isVersioned)
        assertEquals(1, p.signatureCount)
        assertEquals(1, p.requiredSignatures)
        assertEquals(3, p.staticKeys.size)
        assertArrayEquals(user, p.staticKeys[0])
        assertEquals(1, p.instructions.size)
        assertEquals("0200000005000000" + "00000000", p.instructions[0].data.hex())
    }

    @Test fun parser_v0TransactionWithLookupTable() {
        val bytes = v0Tx(listOf(user, jupiter), listOf(RawIx(1, listOf(0, 2, 3, 4), byteArrayOf(1))))
        val p = SolanaTxParser.parse(bytes)!!
        assertTrue(p.isVersioned)
        assertEquals(1, p.lookupTableCount)
        assertEquals(2, p.staticKeys.size)
        assertEquals(listOf(0, 2, 3, 4), p.instructions[0].accountIndexes) // 2.. refer to lookup-table accounts
    }

    @Test fun parser_rejectsMalformedInput() {
        val good = tx(SolanaTx.systemTransfer(user, other, 5))
        assertNull(SolanaTxParser.parse(ByteArray(0)))
        assertNull(SolanaTxParser.parse(good.copyOf(good.size - 1)))   // truncated
        assertNull(SolanaTxParser.parse(good + 0))                      // trailing byte
        assertNull(SolanaTxParser.parse(ByteArray(300) { 0xff.toByte() }))
        val v1 = v0Tx(listOf(user, jupiter), listOf(RawIx(1, listOf(0), byteArrayOf(1))))
        v1[1 + 64] = 0x81.toByte()                                      // unsupported message version 1
        assertNull(SolanaTxParser.parse(v1))
    }

    // ---------------- signing ----------------
    @Test fun signSwapTransaction_legacy_matchesGolden() {
        val message = SolanaTx.buildMessage(user, listOf(SolanaTx.systemTransfer(user, pubOf(ByteArray(32) { 2 }), 1_000_000_000)), blockhash)
        val signed = SolanaTx.signSwapTransaction(SolanaTx.serialize(ByteArray(64), message), sk, user)
        assertEquals(
            "01" + "0208010b17454466fad04b9f9323e87ac7e86e328de18b6e097fb1c150c3dfe03a286f340bfac2a8e74af1f89106f2d7088d31f554941bdf6745eb7af78d9301" + message.hex(),
            signed.hex()
        )
    }

    @Test fun signSwapTransaction_v0_signatureVerifies() {
        val unsigned = v0Tx(listOf(user, jupiter), listOf(RawIx(1, listOf(0, 2), byteArrayOf(1))))
        val signed = SolanaTx.signSwapTransaction(unsigned, sk, user)
        assertEquals(unsigned.size, signed.size)
        val message = unsigned.copyOfRange(1 + 64, unsigned.size)
        assertArrayEquals(message, signed.copyOfRange(1 + 64, signed.size))        // message untouched
        assertTrue(Ed25519.verify(signed, 1, user, 0, message, 0, message.size))   // signature valid
    }

    @Test fun signSwapTransaction_requiresExactlyOneSignatureSlot() {
        val twoSlots = byteArrayOf(2) + ByteArray(128) + ByteArray(10)
        try { SolanaTx.signSwapTransaction(twoSlots, sk, user); fail("expected IllegalArgumentException") }
        catch (e: IllegalArgumentException) { /* expected */ }
    }

    // ---------------- instruction allow-list ----------------
    @Test fun typicalSolToUsdcSwap_isAccepted() {
        assertNull(verdict(tx(limit(200_000), price(1_000), ataFor(user), fundWsol, syncNative, route, close(user))))
    }

    @Test fun v0SwapWithLookupTableAccounts_isAccepted() {
        assertNull(verdict(v0Tx(listOf(user, jupiter), listOf(RawIx(1, listOf(0, 2, 3, 4), byteArrayOf(1))))))
    }

    @Test fun unknownProgram_isRejected() =
        assertNotNull(verdict(tx(route, SolIx(other, listOf(SolMeta(user, true, true)), byteArrayOf(1)))))

    @Test fun systemAssign_isRejected() {
        // Assign would hand control of the wallet account to another program
        val assign = SolIx(system, listOf(SolMeta(user, true, true)), le32(1) + other)
        assertEquals("Unexpected system program instruction", verdict(tx(route, assign)))
    }

    @Test fun systemTransferFromAnotherAccount_isRejected() {
        val t = SolIx(system, listOf(SolMeta(other, false, true), SolMeta(user, false, true)), le32(2) + le64(1))
        assertEquals("System transfer from an unexpected account", verdict(tx(route, t)))
    }

    @Test fun dangerousTokenInstructions_areRejected() {
        for (type in listOf(3, 4, 6, 7, 8, 12, 13, 14)) { // Transfer, Approve, SetAuthority, MintTo, Burn, TransferChecked, ApproveChecked...
            val ix = SolIx(tokenProgram, listOf(SolMeta(usdcAta, false, true), SolMeta(other, false, false), SolMeta(user, true, false)), byteArrayOf(type.toByte()) + le64(1))
            assertEquals("type $type", "Unexpected token instruction", verdict(tx(route, ix)))
        }
    }

    @Test fun closeAccount_mustReturnLamportsToUs() {
        assertEquals("Token account would be closed to another address", verdict(tx(route, close(other))))
        assertNotNull(verdict(tx(route, close(user, owner = other))))
        assertNull(verdict(tx(route, close(user))))
    }

    @Test fun ataCreation_mustBeForOurOwnWallet() {
        assertEquals("Token account would be created for another owner", verdict(tx(route, ataFor(other))))
        assertNull(verdict(tx(route, ataFor(user))))
        val recoverNested = SolIx(key(SolanaSwapGuard.ATA_PROGRAM), listOf(SolMeta(user, true, true)), byteArrayOf(2))
        assertNotNull(verdict(tx(route, recoverNested)))
    }

    @Test fun differentFeePayer_isRejected() {
        // no instruction needs our signature, so the only signer is the other fee payer
        val harmless = SolIx(jupiter, listOf(SolMeta(pool, false, true)), byteArrayOf(1))
        assertEquals("Swap transaction has a different fee payer", verdict(tx(harmless, payer = other)))
    }

    @Test fun extraSigner_isRejected() {
        val withCosigner = SolIx(jupiter, listOf(SolMeta(user, true, true), SolMeta(other, true, false)), byteArrayOf(1))
        val msg = SolanaTx.buildMessage(user, listOf(withCosigner), blockhash)
        val twoSlots = byteArrayOf(2) + ByteArray(128) + msg
        assertEquals("Swap transaction needs unexpected signers", verdict(twoSlots))
    }

    @Test fun programFromLookupTable_isRejected() {
        // program id index 2 is beyond the static keys, so it would be resolved through a lookup table
        val bytes = v0Tx(listOf(user, jupiter), listOf(RawIx(2, listOf(0), byteArrayOf(1))))
        assertEquals("Swap transaction loads a program from a lookup table", verdict(bytes))
    }

    @Test fun accountsWeCannotResolve_failClosed() {
        // CloseAccount whose destination is an index into a lookup table: cannot be shown to be us
        val ata = key(SolanaSwapGuard.ATA_PROGRAM)
        val tokenIdx = listOf(user, jupiter, tokenProgram, ata)
        val closeViaLookup = RawIx(2, listOf(1, 4, 0), byteArrayOf(9)) // account[1] = index 4 > static keys
        assertNotNull(verdict(v0Tx(tokenIdx, listOf(closeViaLookup))))
    }

    @Test fun priorityFee_isCapped() {
        assertNull(verdict(tx(limit(1_000_000), price(1_000_000), route)))                  // exactly 1,000,000 lamports
        assertEquals("Swap priority fee is too high", verdict(tx(limit(1_000_000), price(1_000_001), route)))
        assertEquals("Swap priority fee is too high", verdict(tx(price(1_000_000), route))) // no limit set: assume 1.4M units
        assertNull(verdict(tx(limit(200_000), price(1_000), route)))
    }

    @Test fun deprecatedAndUnknownComputeBudgetInstructions_areRejected() {
        val requestUnits = SolIx(computeBudget, emptyList(), byteArrayOf(0) + le32(1) + le32(1_000_000)) // carries an extra fee field
        assertNotNull(verdict(tx(requestUnits, route)))
        assertNotNull(verdict(tx(SolIx(computeBudget, emptyList(), byteArrayOf(9)), route)))
        assertNotNull(verdict(tx(SolIx(computeBudget, emptyList(), byteArrayOf(3, 1)), route)))   // malformed
    }

    @Test fun programConstants_areValid32ByteKeys() {
        listOf(
            SolanaSwapGuard.COMPUTE_BUDGET, SolanaSwapGuard.JUPITER_V6, SolanaSwapGuard.WSOL_MINT, SolanaSwapGuard.TOKEN_PROGRAM,
            SolanaSwapGuard.TOKEN_2022_PROGRAM, SolanaSwapGuard.ATA_PROGRAM, SolanaSwapGuard.SYSTEM_PROGRAM
        ).forEach { assertEquals(it, 32, Base58.decode(it)?.size) }
    }

    // ---------------- simulation guard ----------------
    private val me = "me"
    private val sys = SolanaSwapGuard.SYSTEM_PROGRAM
    private val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val usdt = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
    private val wsol = SolanaSwapGuard.WSOL_MINT

    private fun wallet(lamports: Long, owner: String? = sys) = AccountState(lamports, owner, null)
    private fun tok(mint: String, amount: Long, owner: String = me, delegate: String? = null, close: String? = null) =
        AccountState(2_039_280, "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
            TokenState(mint, owner, BigInteger.valueOf(amount), delegate, close))

    private fun sim(
        pre: Map<String, AccountState?>, post: Map<String, AccountState?>,
        src: String, srcNative: Boolean, dst: String, dstNative: Boolean, amountIn: Long, minOut: Long
    ) = SolSimulationGuard.check(pre, post, me, src, srcNative, dst, dstNative, BigInteger.valueOf(amountIn), BigInteger.valueOf(minOut))

    private val ONE_SOL = 1_000_000_000L
    private val FEES_AND_RENT = 2_044_280L

    @Test fun sim_solToUsdc_ok() {
        val pre = mapOf(me to wallet(10 * ONE_SOL), "usdcAta" to null)
        val post = mapOf(me to wallet(9 * ONE_SOL - FEES_AND_RENT), "usdcAta" to tok(usdc, 150_000_000))
        assertNull(sim(pre, post, wsol, true, usdc, false, ONE_SOL, 149_000_000))
    }

    @Test fun sim_solToUsdc_outputBelowMinimum_isRejected() {
        val pre = mapOf(me to wallet(10 * ONE_SOL), "usdcAta" to null)
        val post = mapOf(me to wallet(9 * ONE_SOL - FEES_AND_RENT), "usdcAta" to tok(usdc, 100_000_000))
        assertEquals("The swap would return less than the minimum", sim(pre, post, wsol, true, usdc, false, ONE_SOL, 149_000_000))
    }

    @Test fun sim_solToUsdc_noOutputAccountAtAll_isRejected() {
        val pre = mapOf(me to wallet(10 * ONE_SOL), "usdcAta" to null)
        val post = mapOf(me to wallet(9 * ONE_SOL), "usdcAta" to null)
        assertNotNull(sim(pre, post, wsol, true, usdc, false, ONE_SOL, 149_000_000))
    }

    @Test fun sim_spendingMoreSolThanAsked_isRejected() {
        val pre = mapOf(me to wallet(10 * ONE_SOL), "usdcAta" to null)
        val post = mapOf(me to wallet(8 * ONE_SOL), "usdcAta" to tok(usdc, 150_000_000))
        assertEquals("The swap would spend more SOL than expected", sim(pre, post, wsol, true, usdc, false, ONE_SOL, 149_000_000))
    }

    @Test fun sim_usdcToSol_ok_andTemporaryWsolAccountMayClose() {
        val pre = mapOf(me to wallet(ONE_SOL), "usdcAta" to tok(usdc, 100_000_000), "wsolAta" to null)
        val post = mapOf(me to wallet(ONE_SOL + 650_000_000 - 5_000), "usdcAta" to tok(usdc, 0), "wsolAta" to null)
        assertNull(sim(pre, post, usdc, false, wsol, true, 100_000_000, 640_000_000))
        val preWithWsol = pre + ("wsolAta" to tok(wsol, 0))
        assertNull(sim(preWithWsol, post, usdc, false, wsol, true, 100_000_000, 640_000_000))
    }

    @Test fun sim_usdcToSol_returnsTooLittle_isRejected() {
        val pre = mapOf(me to wallet(ONE_SOL), "usdcAta" to tok(usdc, 100_000_000))
        val post = mapOf(me to wallet(ONE_SOL + 100_000_000), "usdcAta" to tok(usdc, 0))
        assertEquals("The swap would return less SOL than the minimum", sim(pre, post, usdc, false, wsol, true, 100_000_000, 640_000_000))
    }

    @Test fun sim_spendingMoreSourceTokenThanAsked_isRejected() {
        val pre = mapOf(me to wallet(ONE_SOL), "usdcAta" to tok(usdc, 500_000_000))
        val post = mapOf(me to wallet(ONE_SOL + 650_000_000), "usdcAta" to tok(usdc, 300_000_000))
        assertEquals("The swap would spend more of the source token than expected", sim(pre, post, usdc, false, wsol, true, 100_000_000, 640_000_000))
    }

    private fun baseline() = mapOf(me to wallet(10 * ONE_SOL), "usdcAta" to tok(usdc, 100_000_000), "usdtAta" to tok(usdt, 50_000_000))
    private fun healthyPost() = mapOf(me to wallet(10 * ONE_SOL - 5_000), "usdcAta" to tok(usdc, 100_000_000), "usdtAta" to tok(usdt, 50_000_000))

    @Test fun sim_untouchedAccounts_areFine() {
        val pre = baseline()
        val post = healthyPost().toMutableMap<String, AccountState?>().apply {
            put("usdcAta", tok(usdc, 0)); put("usdtAta", tok(usdt, 50_000_000 + 99_000_000))
        }
        assertNull(sim(pre, post, usdc, false, usdt, false, 100_000_000, 98_000_000))
    }

    @Test fun sim_unrelatedTokenBalanceDecreasing_isRejected() {
        val pre = baseline().toMutableMap<String, AccountState?>().apply { put("bonkAta", tok("BonkMint", 1_000)) }
        val post = healthyPost().toMutableMap<String, AccountState?>().apply {
            put("usdcAta", tok(usdc, 0)); put("usdtAta", tok(usdt, 149_000_000)); put("bonkAta", tok("BonkMint", 0))
        }
        assertEquals("Another token balance of yours would decrease", sim(pre, post, usdc, false, usdt, false, 100_000_000, 98_000_000))
    }

    @Test fun sim_newDelegate_isRejected() {
        val post = healthyPost().toMutableMap<String, AccountState?>().apply {
            put("usdcAta", tok(usdc, 0, delegate = "attacker")); put("usdtAta", tok(usdt, 149_000_000))
        }
        assertEquals("A token account's authority would change", sim(baseline(), post, usdc, false, usdt, false, 100_000_000, 98_000_000))
    }

    @Test fun sim_ownerOrCloseAuthorityChange_isRejected() {
        for (changed in listOf(tok(usdt, 149_000_000, owner = "attacker"), tok(usdt, 149_000_000, close = "attacker"))) {
            val post = healthyPost().toMutableMap<String, AccountState?>().apply { put("usdcAta", tok(usdc, 0)); put("usdtAta", changed) }
            assertNotNull(sim(baseline(), post, usdc, false, usdt, false, 100_000_000, 98_000_000))
        }
    }

    @Test fun sim_closingAnotherTokenAccount_isRejected() {
        val post = healthyPost().toMutableMap<String, AccountState?>().apply {
            put("usdcAta", tok(usdc, 0)); put("usdtAta", null)
        }
        assertEquals("A token account of yours would be closed", sim(baseline(), post, usdc, false, usdt, false, 100_000_000, 98_000_000))
    }

    @Test fun sim_walletOwnershipChange_isRejected() {
        val post = healthyPost().toMutableMap<String, AccountState?>().apply {
            put(me, wallet(10 * ONE_SOL - 5_000, owner = "SomeOtherProgram")); put("usdcAta", tok(usdc, 0)); put("usdtAta", tok(usdt, 149_000_000))
        }
        assertEquals("Wallet account ownership would change", sim(baseline(), post, usdc, false, usdt, false, 100_000_000, 98_000_000))
    }

    @Test fun sim_walletMissing_isRejected() {
        assertNotNull(sim(mapOf(me to null), mapOf(me to null), wsol, true, usdc, false, 1, 1))
    }
}
