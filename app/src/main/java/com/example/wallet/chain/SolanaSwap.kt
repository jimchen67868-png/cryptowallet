package com.example.wallet.chain

import com.example.wallet.crypto.Base58
import org.json.JSONObject
import java.math.BigInteger
import java.util.Base64

/** Minimal parser for legacy and v0 Solana transactions: just enough to inspect what would be signed. */
object SolanaTxParser {
    class Ix(val programIdIndex: Int, val accountIndexes: List<Int>, val data: ByteArray)

    class Parsed(
        val signatureCount: Int,
        val requiredSignatures: Int,
        val isVersioned: Boolean,
        val staticKeys: List<ByteArray>,
        val instructions: List<Ix>,
        val lookupTableCount: Int
    )

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun u8(): Int {
            require(pos < b.size) { "unexpected end of data" }
            return b[pos++].toInt() and 0xff
        }
        fun bytes(n: Int): ByteArray {
            require(n >= 0 && pos + n <= b.size) { "unexpected end of data" }
            val out = b.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
        fun shortVec(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val x = u8()
                value = value or ((x and 0x7f) shl shift)
                if (x and 0x80 == 0) return value
                shift += 7
                require(shift <= 14) { "bad length prefix" }
            }
        }
    }

    /** Returns null for anything malformed, truncated or with trailing bytes. */
    fun parse(tx: ByteArray): Parsed? = try {
        parseOrThrow(tx)
    } catch (e: Exception) {
        null
    }

    private fun parseOrThrow(tx: ByteArray): Parsed {
        val r = Reader(tx)
        val signatureCount = r.shortVec()
        r.bytes(64 * signatureCount)

        val first = r.u8()
        val versioned = first and 0x80 != 0
        val required: Int
        if (versioned) {
            require(first and 0x7f == 0) { "unsupported message version" }
            required = r.u8()
        } else {
            required = first
        }
        r.u8() // readonly signed
        r.u8() // readonly unsigned

        val keys = List(r.shortVec()) { r.bytes(32) }
        r.bytes(32) // recent blockhash

        val instructions = List(r.shortVec()) {
            val program = r.u8()
            val accounts = List(r.shortVec()) { r.u8() }
            val data = r.bytes(r.shortVec())
            Ix(program, accounts, data)
        }

        var lookups = 0
        if (versioned) {
            lookups = r.shortVec()
            repeat(lookups) {
                r.bytes(32)
                r.bytes(r.shortVec()) // writable indexes
                r.bytes(r.shortVec()) // readonly indexes
            }
        }
        require(r.pos == tx.size) { "trailing bytes" }
        return Parsed(signatureCount, required, versioned, keys, instructions, lookups)
    }
}

/**
 * Pre-signing checks on a transaction supplied by the Jupiter API. Fails closed: only a short allow-list of
 * programs and instruction types may appear at the top level, so a malicious or buggy response cannot, for
 * example, reassign the wallet account, approve a delegate, or close an account into someone else's wallet.
 * (Jupiter's route instruction CPIs into the DEX programs; those never appear at the top level.)
 */
object SolanaSwapGuard {
    const val WSOL_MINT = "So11111111111111111111111111111111111111112"
    const val JUPITER_V6 = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"
    const val COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
    const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

    /** Upper bound on the priority fee a transaction may carry (0.001 SOL). */
    const val MAX_PRIORITY_FEE_LAMPORTS = 1_000_000L

    /** SOL the wallet may lose beyond the swap amount: base + priority fee and rent for new token accounts. */
    const val SOL_OVERHEAD_LAMPORTS = 5_000_000L

    private const val DEFAULT_MAX_COMPUTE_UNITS = 1_400_000L

    fun validateTransaction(tx: ByteArray, user: ByteArray): String? {
        val p = SolanaTxParser.parse(tx) ?: return "Unrecognised swap transaction"
        if (p.signatureCount != 1 || p.requiredSignatures != 1) return "Swap transaction needs unexpected signers"
        if (p.staticKeys.isEmpty() || !p.staticKeys[0].contentEquals(user)) return "Swap transaction has a different fee payer"

        val keys = p.staticKeys.map { Base58.encode(it) }
        val me = Base58.encode(user)
        var computeLimit: Long? = null
        var computePrice: Long? = null

        for (ix in p.instructions) {
            val program = keys.getOrNull(ix.programIdIndex) ?: return "Swap transaction loads a program from a lookup table"
            // Accounts we check must be static keys: an index into a lookup table cannot be verified, so it fails.
            fun account(i: Int): String? = ix.accountIndexes.getOrNull(i)?.let { keys.getOrNull(it) }
            val d = ix.data

            when (program) {
                COMPUTE_BUDGET -> {
                    if (d.isEmpty()) return "Malformed compute budget instruction"
                    when (d[0].toInt()) {
                        1, 4 -> {}
                        2 -> { if (d.size != 5) return "Malformed compute budget instruction"; computeLimit = leUnsigned(d, 1, 4) }
                        3 -> { if (d.size != 9) return "Malformed compute budget instruction"; computePrice = leUnsigned(d, 1, 8) }
                        else -> return "Unexpected compute budget instruction"
                    }
                }
                SYSTEM_PROGRAM -> {
                    // Only Transfer (index 2), used to fund a temporary wrapped-SOL account. Assign, Allocate, ... are refused.
                    if (d.size < 4 || leUnsigned(d, 0, 4) != 2L) return "Unexpected system program instruction"
                    if (account(0) != me) return "System transfer from an unexpected account"
                }
                TOKEN_PROGRAM, TOKEN_2022_PROGRAM -> {
                    if (d.isEmpty()) return "Malformed token instruction"
                    when (d[0].toInt()) {
                        17 -> {} // SyncNative
                        9 -> { // CloseAccount: lamports must go back to us
                            if (account(1) != me || account(2) != me) return "Token account would be closed to another address"
                        }
                        else -> return "Unexpected token instruction"
                    }
                }
                ATA_PROGRAM -> {
                    val type = if (d.isEmpty()) 0 else d[0].toInt()
                    if (type != 0 && type != 1) return "Unexpected associated token account instruction"
                    if (account(0) != me || account(2) != me) return "Token account would be created for another owner"
                }
                JUPITER_V6 -> {}
                else -> return "Swap transaction calls an unexpected program"
            }
        }

        val price = computePrice
        if (price != null) {
            val limit = computeLimit ?: DEFAULT_MAX_COMPUTE_UNITS
            val fee = BigInteger.valueOf(limit).multiply(BigInteger.valueOf(price))
                .add(BigInteger.valueOf(999_999)).divide(BigInteger.valueOf(1_000_000)) // micro-lamports, rounded up
            if (fee > BigInteger.valueOf(MAX_PRIORITY_FEE_LAMPORTS)) return "Swap priority fee is too high"
        }
        return null
    }

    private fun leUnsigned(d: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in len - 1 downTo 0) v = (v shl 8) or (d[off + i].toLong() and 0xff)
        return v
    }
}

class TokenState(
    val mint: String,
    val owner: String,
    val amount: BigInteger,
    val delegate: String?,
    val closeAuthority: String?
)

/** [programOwner] is the account's owning program (the System program for a normal wallet). */
class AccountState(val lamports: Long, val programOwner: String?, val token: TokenState?)

/**
 * Compares account state before and after a simulation of the swap. Whatever the instructions say, the
 * outcome must be: the wallet gives up at most the swap amount (plus small fees), receives at least the minimum,
 * and nothing else of ours (other token balances, owners, delegates, open accounts) is touched.
 */
object SolSimulationGuard {
    fun check(
        pre: Map<String, AccountState?>,
        post: Map<String, AccountState?>,
        user: String,
        srcMint: String,
        srcNative: Boolean,
        dstMint: String,
        dstNative: Boolean,
        amountIn: BigInteger,
        minOut: BigInteger
    ): String? {
        val preWallet = pre[user] ?: return "Wallet account not found"
        val postWallet = post[user] ?: return "Wallet account would be closed"
        if (postWallet.programOwner != preWallet.programOwner) return "Wallet account ownership would change"

        val overhead = BigInteger.valueOf(SolanaSwapGuard.SOL_OVERHEAD_LAMPORTS)
        val drop = BigInteger.valueOf(preWallet.lamports).subtract(BigInteger.valueOf(postWallet.lamports))
        val maxDrop = if (srcNative) overhead.add(amountIn) else overhead
        if (drop > maxDrop) return "The swap would spend more SOL than expected"
        if (dstNative) {
            val gain = BigInteger.valueOf(postWallet.lamports).subtract(BigInteger.valueOf(preWallet.lamports)).add(overhead)
            if (gain < minOut) return "The swap would return less SOL than the minimum"
        }

        // Existing token accounts: still there, same owner / delegate / close authority.
        for ((address, before) in pre) {
            val t = before?.token ?: continue
            val after = post[address]
            if (after == null) {
                if (t.mint != SolanaSwapGuard.WSOL_MINT) return "A token account of yours would be closed"
                continue // a temporary wrapped-SOL account may be closed
            }
            val a = after.token ?: return "A token account would change type"
            if (a.owner != t.owner || a.delegate != t.delegate || a.closeAuthority != t.closeAuthority) {
                return "A token account's authority would change"
            }
        }
        for (after in post.values) {
            val t = after?.token ?: continue
            if (t.owner != user) return "A token account would be owned by someone else"
        }

        // Balances per mint, summed over all our accounts for that mint.
        fun total(m: Map<String, AccountState?>, mint: String): BigInteger =
            m.values.mapNotNull { it?.token }
                .filter { it.mint == mint && it.owner == user }
                .fold(BigInteger.ZERO) { acc, t -> acc.add(t.amount) }

        val mints = (pre.values + post.values).mapNotNull { it?.token?.mint }.toSet() - SolanaSwapGuard.WSOL_MINT
        for (mint in mints) {
            val delta = total(post, mint).subtract(total(pre, mint))
            when {
                mint == srcMint && !srcNative ->
                    if (delta < amountIn.negate()) return "The swap would spend more of the source token than expected"
                mint == dstMint && !dstNative ->
                    if (delta < minOut) return "The swap would return less than the minimum"
                else -> if (delta.signum() < 0) return "Another token balance of yours would decrease"
            }
        }
        // The expected output token must actually have appeared.
        if (!dstNative && total(post, dstMint).subtract(total(pre, dstMint)) < minOut) {
            return "The swap would return less than the minimum"
        }
        return null
    }
}

/** Jupiter swap API. Keyless "lite" endpoint by default; mainnet only. */
internal class JupiterApi(private val baseUrl: String = "https://lite-api.jup.ag/swap/v1") {
    class Quote(val json: JSONObject, val inAmount: BigInteger, val outAmount: BigInteger)

    fun quote(inputMint: String, outputMint: String, amount: BigInteger, slippageBps: Int): Quote {
        val url = "$baseUrl/quote?inputMint=$inputMint&outputMint=$outputMint&amount=$amount" +
            "&slippageBps=$slippageBps&restrictIntermediateTokens=true"
        val json = JSONObject(Http.get(url))
        throwIfError(json)
        check(json.optString("inputMint") == inputMint && json.optString("outputMint") == outputMint) {
            "Quote is for different tokens than requested"
        }
        val inAmount = BigInteger(json.getString("inAmount"))
        check(inAmount == amount) { "Quote amount differs from the request" }
        return Quote(json, inAmount, BigInteger(json.getString("outAmount")))
    }

    /** Returns the unsigned transaction bytes (fee payer = [user]). */
    fun swapTransaction(quote: Quote, user: String): ByteArray {
        val body = JSONObject()
            .put("quoteResponse", quote.json)
            .put("userPublicKey", user)
            .put("wrapAndUnwrapSol", true)
            .put("dynamicComputeUnitLimit", true)
            .put(
                "prioritizationFeeLamports",
                JSONObject().put(
                    "priorityLevelWithMaxLamports",
                    JSONObject().put("maxLamports", 100_000).put("priorityLevel", "medium")
                )
            )
        val json = JSONObject(Http.postJson("$baseUrl/swap", body.toString()))
        throwIfError(json)
        return Base64.getDecoder().decode(json.getString("swapTransaction"))
    }

    private fun throwIfError(json: JSONObject) {
        val err = json.optString("error")
        if (err.isNotBlank()) throw IllegalStateException(err)
    }
}
