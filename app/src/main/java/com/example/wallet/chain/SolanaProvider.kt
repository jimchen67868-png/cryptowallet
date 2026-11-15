package com.example.wallet.chain

import com.example.wallet.crypto.Base58
import com.example.wallet.crypto.Hd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64

/** Solana devnet. Receive, SOL + SPL balances, SOL and SPL token transfers. */
class SolanaProvider(
    private val rpcUrl: String = "https://api.devnet.solana.com",
    /** Friendly names for common mints; unknown mints show a shortened mint address. */
    private val knownMints: Map<String, String> = mapOf(
        "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU" to "USDC", // devnet
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "USDC", // mainnet
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "USDT"  // mainnet
    ),
    private val explorerSuffix: String = "?cluster=devnet"
) : ChainProvider {

    override val chain = Chain.SOL
    override val supportsSend = true
    override val supportsTokenSend = true
    override val isTestnet = explorerSuffix.isNotEmpty() // "?cluster=devnet" is only set off mainnet
    override val networkName = if (isTestnet) "Devnet" else "Mainnet"

    private class Row(val mint: String, val amount: BigDecimal, val decimals: Int)

    private class TokBal(val owner: String, val mint: String, val amount: BigInteger, val decimals: Int)

    private class Prepared(
        val source: ByteArray,
        val tokenProgram: ByteArray,
        val mint: ByteArray,
        val destOwner: ByteArray,
        val destAta: ByteArray,
        val needsCreate: Boolean
    )

    // m/44'/501'/0'/0' (SLIP-10 ed25519, same path Phantom uses for account 0)
    private fun privateKey(seed: ByteArray) = Hd.ed25519PrivateKey(seed, 44, 501, 0, 0)

    private fun publicKey(sk: ByteArray): ByteArray {
        val pk = ByteArray(32)
        Ed25519.generatePublicKey(sk, 0, pk, 0)
        return pk
    }

    override fun deriveAddress(seed: ByteArray): String {
        val sk = privateKey(seed)
        try {
            return Base58.encode(publicKey(sk))
        } finally {
            sk.fill(0)
        }
    }

    private fun rpc(method: String, params: String): JSONObject {
        val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
        val json = JSONObject(Http.postJson(rpcUrl, body))
        if (json.has("error")) throw IllegalStateException(json.getJSONObject("error").optString("message"))
        return json
    }

    override suspend fun getBalance(address: String): BigDecimal = withContext(Dispatchers.IO) {
        val lamports = rpc("getBalance", """["$address"]""").getJSONObject("result").getLong("value")
        BigDecimal.valueOf(lamports).movePointLeft(9)
    }

    // ---- token balances (auto-discovered) ----

    override suspend fun getTokenBalances(address: String): List<TokenBalance> = withContext(Dispatchers.IO) {
        val rows = listOf(TOKEN_PROGRAM_ID, TOKEN_2022_PROGRAM_ID).mapIndexedNotNull { i, program ->
            if (i == 0) fetchTokenRows(address, program)
            else runCatching { fetchTokenRows(address, program) }.getOrNull()
        }.flatten()

        rows.groupBy { it.mint }
            .map { (mint, list) ->
                TokenBalance(
                    id = mint,
                    symbol = knownMints[mint] ?: (mint.take(4) + "…" + mint.takeLast(4)),
                    balance = list.fold(BigDecimal.ZERO) { a, r -> a + r.amount },
                    decimals = list.first().decimals
                )
            }
            .filter { it.balance.signum() > 0 }
            .sortedBy { it.symbol }
    }

    private fun fetchTokenRows(owner: String, program: String): List<Row> {
        val arr = rpc(
            "getTokenAccountsByOwner",
            """["$owner",{"programId":"$program"},{"encoding":"jsonParsed"}]"""
        ).getJSONObject("result").getJSONArray("value")
        return (0 until arr.length()).map {
            val info = parsedInfo(arr.getJSONObject(it))
            val ta = info.getJSONObject("tokenAmount")
            Row(info.getString("mint"), BigDecimal(ta.getString("uiAmountString")), ta.getInt("decimals"))
        }
    }

    private fun parsedInfo(account: JSONObject): JSONObject =
        account.getJSONObject("account").getJSONObject("data").getJSONObject("parsed").getJSONObject("info")

    // ---- history ----

    override fun explorerUrl(txId: String) = "https://explorer.solana.com/tx/$txId$explorerSuffix"

    /** Last 20 signatures, each parsed for our SOL and token balance changes. */
    override suspend fun getHistory(address: String): List<TxRecord> = withContext(Dispatchers.IO) {
        val sigs = rpc("getSignaturesForAddress", """["$address",{"limit":20}]""").getJSONArray("result")
        val limiter = Semaphore(4) // be gentle with the public RPC
        coroutineScope {
            (0 until sigs.length()).map { sigs.getJSONObject(it).getString("signature") }
                .map { sig ->
                    async {
                        limiter.withPermit {
                            runCatching { parseTransaction(address, sig) }.getOrDefault(emptyList())
                        }
                    }
                }
                .awaitAll()
                .flatten()
        }
    }

    private fun parseTransaction(me: String, signature: String): List<TxRecord> {
        val res = rpc(
            "getTransaction",
            """["$signature",{"encoding":"jsonParsed","maxSupportedTransactionVersion":0,"commitment":"confirmed"}]"""
        ).optJSONObject("result") ?: return emptyList()

        val meta = res.getJSONObject("meta")
        val time = if (res.isNull("blockTime")) null else res.getLong("blockTime")
        val keys = res.getJSONObject("transaction").getJSONObject("message").getJSONArray("accountKeys")
        val pubkeys = (0 until keys.length()).map { keys.getJSONObject(it).getString("pubkey") }
        val myIdx = pubkeys.indexOf(me)
        if (myIdx < 0) return emptyList()

        if (!meta.isNull("err")) {
            // Failed: nothing moved. Only worth listing if we paid for it.
            return if (myIdx == 0) {
                listOf(TxRecord(signature, time, TxDirection.OUT, BigDecimal.ZERO, "SOL", null, TxStatus.FAILED))
            } else emptyList()
        }

        val fee = meta.getLong("fee")
        val pre = meta.getJSONArray("preBalances")
        val post = meta.getJSONArray("postBalances")
        val deltas = pubkeys.indices.map { post.getLong(it) - pre.getLong(it) }
        val solDelta = deltas[myIdx] + if (myIdx == 0) fee else 0L // exclude the fee we paid

        // ---- token balance changes, per (owner, mint) ----
        val net = HashMap<Pair<String, String>, BigInteger>()
        val decimals = HashMap<String, Int>()
        for (t in tokenBalances(meta.optJSONArray("postTokenBalances"))) {
            net.merge(t.owner to t.mint, t.amount) { a, b -> a + b }
            decimals[t.mint] = t.decimals
        }
        for (t in tokenBalances(meta.optJSONArray("preTokenBalances"))) {
            net.merge(t.owner to t.mint, t.amount.negate()) { a, b -> a + b }
            decimals[t.mint] = t.decimals
        }

        val records = ArrayList<TxRecord>()
        for (mint in net.keys.filter { it.first == me }.map { it.second }) {
            val d = net.getValue(me to mint)
            if (d.signum() == 0) continue
            val counterparty = net.entries
                .filter { it.key.second == mint && it.key.first != me && it.value.signum() == -d.signum() }
                .maxByOrNull { it.value.abs() }?.key?.first
            records.add(
                TxRecord(
                    id = signature,
                    timeSec = time,
                    direction = if (d.signum() > 0) TxDirection.IN else TxDirection.OUT,
                    amount = BigDecimal(d.abs()).movePointLeft(decimals.getValue(mint)),
                    symbol = knownMints[mint] ?: (mint.take(4) + "…" + mint.takeLast(4)),
                    counterparty = counterparty,
                    status = TxStatus.CONFIRMED
                )
            )
        }

        // SOL line. When a token moved, small SOL changes are just account rent: hide them.
        val isRentOnly = records.isNotEmpty() && kotlin.math.abs(solDelta) < RENT_NOISE_LAMPORTS
        if (solDelta != 0L && !isRentOnly) {
            val incoming = solDelta > 0
            val counterparty = pubkeys.indices
                .filter { it != myIdx && deltas[it] != 0L && (deltas[it] > 0) != incoming }
                .maxByOrNull { kotlin.math.abs(deltas[it]) }?.let { pubkeys[it] }
            records.add(
                TxRecord(
                    id = signature,
                    timeSec = time,
                    direction = if (incoming) TxDirection.IN else TxDirection.OUT,
                    amount = BigDecimal.valueOf(kotlin.math.abs(solDelta)).movePointLeft(9),
                    symbol = "SOL",
                    counterparty = counterparty,
                    status = TxStatus.CONFIRMED
                )
            )
        }
        return records
    }

    private fun tokenBalances(arr: JSONArray?): List<TokBal> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val ui = o.getJSONObject("uiTokenAmount")
            TokBal(o.optString("owner", ""), o.getString("mint"), BigInteger(ui.getString("amount")), ui.getInt("decimals"))
        }
    }

    // ---- SPL transfers ----

    override suspend fun estimateTokenFee(
        address: String, token: TokenBalance, to: String, amount: BigDecimal
    ): BigDecimal? = withContext(Dispatchers.IO) {
        val p = prepareTransfer(address, token, to, toRaw(amount, token.decimals))
        var lamports = SIGNATURE_FEE_LAMPORTS
        if (p.needsCreate) {
            // one-time rent for the recipient's new token account
            lamports += rpc("getMinimumBalanceForRentExemption", "[165]").getLong("result")
        }
        val fee = BigDecimal.valueOf(lamports).movePointLeft(9)
        if (getBalance(address) < fee) {
            throw IllegalStateException("Not enough SOL to pay the network fee (about ${fee.toPlainString()} SOL)")
        }
        fee
    }

    override suspend fun sendToken(
        seed: ByteArray, token: TokenBalance, to: String, amount: BigDecimal
    ): String = withContext(Dispatchers.IO) {
        val sk = privateKey(seed)
        try {
            val pk = publicKey(sk)
            val raw = toRaw(amount, token.decimals)
            val p = prepareTransfer(Base58.encode(pk), token, to, raw)

            val ixs = buildList {
                if (p.needsCreate) {
                    // CreateIdempotent associated token account for the recipient
                    add(
                        SolIx(
                            ATA_PROGRAM_ID,
                            listOf(
                                SolMeta(pk, signer = true, writable = true),
                                SolMeta(p.destAta, signer = false, writable = true),
                                SolMeta(p.destOwner, signer = false, writable = false),
                                SolMeta(p.mint, signer = false, writable = false),
                                SolMeta(SYSTEM_PROGRAM_ID, signer = false, writable = false),
                                SolMeta(p.tokenProgram, signer = false, writable = false)
                            ),
                            byteArrayOf(1)
                        )
                    )
                }
                // TransferChecked: [12, amount u64 LE, decimals]
                add(
                    SolIx(
                        p.tokenProgram,
                        listOf(
                            SolMeta(p.source, signer = false, writable = true),
                            SolMeta(p.mint, signer = false, writable = false),
                            SolMeta(p.destAta, signer = false, writable = true),
                            SolMeta(pk, signer = true, writable = false)
                        ),
                        byteArrayOf(12) + le64(raw.toLong()) + byteArrayOf(token.decimals.toByte())
                    )
                )
            }

            signAndSend(sk, pk, ixs)
        } finally {
            sk.fill(0)
        }
    }

    /** Finds the source token account and the recipient's associated token account. */
    private fun prepareTransfer(owner: String, token: TokenBalance, to: String, raw: BigInteger): Prepared {
        val destOwner = Base58.decode(to)?.takeIf { it.size == 32 }
            ?: throw IllegalArgumentException("Invalid Solana address")
        val mint = Base58.decode(token.id) ?: throw IllegalArgumentException("Invalid mint")

        val accounts = rpc(
            "getTokenAccountsByOwner",
            """["$owner",{"mint":"${token.id}"},{"encoding":"jsonParsed"}]"""
        ).getJSONObject("result").getJSONArray("value")

        var best: JSONObject? = null
        var bestAmount = BigInteger.ZERO
        for (i in 0 until accounts.length()) {
            val o = accounts.getJSONObject(i)
            val info = parsedInfo(o)
            if (info.optString("state") == "frozen") continue
            val amt = BigInteger(info.getJSONObject("tokenAmount").getString("amount"))
            if (best == null || amt > bestAmount) { best = o; bestAmount = amt }
        }
        if (best == null || bestAmount < raw) {
            throw IllegalStateException("Insufficient ${token.symbol} balance in a single token account")
        }

        val tokenProgram = Base58.decode(best.getJSONObject("account").getString("owner"))!!
        val source = Base58.decode(best.getString("pubkey"))!!
        val destAta = SolanaTx.findProgramAddress(listOf(destOwner, tokenProgram, mint), ATA_PROGRAM_ID)
        val exists = !rpc(
            "getAccountInfo", """["${Base58.encode(destAta)}",{"encoding":"base64"}]"""
        ).getJSONObject("result").isNull("value")

        return Prepared(source, tokenProgram, mint, destOwner, destAta, needsCreate = !exists)
    }

    private fun toRaw(amount: BigDecimal, decimals: Int): BigInteger {
        val raw = try {
            amount.movePointRight(decimals).toBigIntegerExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("This token allows at most $decimals decimal places")
        }
        require(raw.signum() > 0 && raw.bitLength() <= 63) { "Amount out of range" }
        return raw
    }

    private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xff).toByte() }
    private fun le64(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xff).toByte() }

    override fun isValidAddress(address: String): Boolean = Base58.decode(address)?.size == 32

    // ---- native SOL ----

    override suspend fun estimateFee(address: String, to: String, amount: BigDecimal): BigDecimal? =
        withContext(Dispatchers.IO) {
            checkNativeTransfer(address, to.trim(), toLamports(amount))
            BigDecimal.valueOf(SIGNATURE_FEE_LAMPORTS).movePointLeft(9)
        }

    override suspend fun send(seed: ByteArray, to: String, amount: BigDecimal): String =
        withContext(Dispatchers.IO) {
            val sk = privateKey(seed)
            try {
                val pk = publicKey(sk)
                val destination = Base58.decode(to.trim())?.takeIf { it.size == 32 }
                    ?: throw IllegalArgumentException("Invalid Solana address")
                val lamports = toLamports(amount)
                checkNativeTransfer(Base58.encode(pk), to.trim(), lamports)

                // SystemProgram.Transfer: [u32 LE instruction index = 2, u64 LE lamports]
                val ix = SolIx(
                    SYSTEM_PROGRAM_ID,
                    listOf(
                        SolMeta(pk, signer = true, writable = true),
                        SolMeta(destination, signer = false, writable = true)
                    ),
                    le32(2) + le64(lamports)
                )
                signAndSend(sk, pk, listOf(ix))
            } finally {
                sk.fill(0)
            }
        }

    /** Pre-flight checks that mirror the cluster's rules, so failures are explained clearly. */
    private fun checkNativeTransfer(from: String, to: String, lamports: Long) {
        if (!isValidAddress(to)) throw IllegalArgumentException("Invalid Solana address")
        val balance = rpc("getBalance", """["$from"]""").getJSONObject("result").getLong("value")
        if (balance < lamports + SIGNATURE_FEE_LAMPORTS) {
            throw IllegalStateException("Insufficient SOL (amount + network fee)")
        }
        val rentMin = rpc("getMinimumBalanceForRentExemption", "[0]").getLong("result")
        val left = balance - lamports - SIGNATURE_FEE_LAMPORTS
        if (left != 0L && left < rentMin) {
            throw IllegalStateException(
                "Your account must keep at least ${lamportsToSol(rentMin)} SOL (or be emptied completely)"
            )
        }
        val recipientMissing = rpc("getAccountInfo", """["$to",{"encoding":"base64"}]""")
            .getJSONObject("result").isNull("value")
        if (recipientMissing && lamports < rentMin) {
            throw IllegalStateException(
                "Recipient has no account yet: send at least ${lamportsToSol(rentMin)} SOL to open it"
            )
        }
    }

    private fun toLamports(amount: BigDecimal): Long {
        val v = try {
            amount.movePointRight(9).toBigIntegerExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("SOL allows at most 9 decimal places")
        }
        require(v.signum() > 0 && v.bitLength() <= 63) { "Amount out of range" }
        return v.toLong()
    }

    private fun lamportsToSol(l: Long) = BigDecimal.valueOf(l).movePointLeft(9).stripTrailingZeros().toPlainString()

    /** Shared by SOL and SPL sends: blockhash, sign (single signer = payer), broadcast. */
    private fun signAndSend(sk: ByteArray, pk: ByteArray, ixs: List<SolIx>): String {
        val blockhash = Base58.decode(
            rpc("getLatestBlockhash", """[{"commitment":"confirmed"}]""")
                .getJSONObject("result").getJSONObject("value").getString("blockhash")
        )?.takeIf { it.size == 32 } ?: throw IllegalStateException("Bad blockhash from RPC")

        val message = SolanaTx.buildMessage(pk, ixs, blockhash)
        val tx = SolanaTx.serialize(SolanaTx.sign(sk, pk, message), message)
        val b64 = Base64.getEncoder().encodeToString(tx)
        return rpc("sendTransaction", """["$b64",{"encoding":"base64"}]""").getString("result")
    }

    private companion object {
        const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        const val SIGNATURE_FEE_LAMPORTS = 5_000L
        const val RENT_NOISE_LAMPORTS = 3_000_000L // ~ rent for a couple of token accounts

        val TOKEN_PROGRAM_ID = TOKEN_PROGRAM
        val TOKEN_2022_PROGRAM_ID = TOKEN_2022_PROGRAM
        val ATA_PROGRAM_ID: ByteArray = Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")!!
        val SYSTEM_PROGRAM_ID: ByteArray = Base58.decode("11111111111111111111111111111111")!!
    }
}
