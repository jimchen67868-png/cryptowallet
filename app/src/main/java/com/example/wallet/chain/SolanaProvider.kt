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
    private val knownMints: Map<String, String> = DEFAULT_KNOWN_MINTS,
    private val explorerSuffix: String = "?cluster=devnet",
    /** Jupiter swap API base URL (keyless "lite" tier). Change it if Jupiter moves or requires a key. */
    private val jupiterBaseUrl: String = "https://lite-api.jup.ag/swap/v1"
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

    // ---- swaps (Jupiter, mainnet only) ----

    override val supportsSwap: Boolean get() = !isTestnet
    override val swapProviderName = "Jupiter"
    override val nativeDecimals = 9

    private val jupiter: JupiterApi by lazy { JupiterApi(jupiterBaseUrl) }

    override fun swapAssets(): List<SwapAsset> =
        if (!supportsSwap) emptyList()
        else listOf(
            SwapAsset(null, "SOL", 9),
            SwapAsset("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC", 6),
            SwapAsset("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", "USDT", 6),
            SwapAsset("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", "JUP", 6),
            SwapAsset("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", "BONK", 5),
            SwapAsset("EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm", "WIF", 6),
            SwapAsset("mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So", "mSOL", 9),
            SwapAsset("J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn", "JitoSOL", 9)
        )

    private fun mintOf(a: SwapAsset) = a.id ?: SolanaSwapGuard.WSOL_MINT

    override suspend fun quoteSwap(
        userAddress: String, from: SwapAsset, to: SwapAsset, amount: BigDecimal
    ): SwapQuote = withContext(Dispatchers.IO) {
        check(supportsSwap) { "Swaps are only available on Solana mainnet" }
        require(mintOf(from) != mintOf(to)) { "Choose two different assets" }
        require(from.id != SolanaSwapGuard.WSOL_MINT) { "Unwrap wrapped SOL first, then swap native SOL" }
        verifyMint(from)
        verifyMint(to)
        val raw = toRaw(amount, from.decimals)
        val q = jupiter.quote(mintOf(from), mintOf(to), raw, SWAP_SLIPPAGE_BPS)
        SwapQuote(chain, from, to, raw, q.outAmount, SWAP_SLIPPAGE_BPS, needsApproval = false, gasUsd = null)
    }

    /**
     * Every token mint in our lists is hand-maintained, and a mistyped base58 string still looks like a mint.
     * So confirm on-chain that it exists, is a mint, and has the decimals we expect before quoting.
     */
    private fun verifyMint(asset: SwapAsset) {
        val mint = asset.id ?: return
        val value = rpc("getAccountInfo", """["$mint",{"encoding":"jsonParsed"}]""")
            .getJSONObject("result").optJSONObject("value")
            ?: throw IllegalStateException("${asset.symbol} mint was not found on this network")
        val parsed = value.optJSONObject("data")?.optJSONObject("parsed")
            ?: throw IllegalStateException("${asset.symbol} is not a token mint")
        check(parsed.optString("type") == "mint") { "${asset.symbol} is not a token mint" }
        check(parsed.getJSONObject("info").getInt("decimals") == asset.decimals) {
            "${asset.symbol} has unexpected decimals, refusing to swap"
        }
    }

    override suspend fun executeSwap(seed: ByteArray, quote: SwapQuote): SwapResult = withContext(Dispatchers.IO) {
        check(supportsSwap) { "Swaps are only available on Solana mainnet" }
        val sk = privateKey(seed)
        try {
            val pk = publicKey(sk)
            val user = Base58.encode(pk)

            // 1. Fresh quote; refuse if the price moved against the user since they confirmed.
            val q = jupiter.quote(mintOf(quote.from), mintOf(quote.to), quote.amountInRaw, SWAP_SLIPPAGE_BPS)
            val floor = quote.expectedOutRaw.multiply(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100))
            check(q.outAmount >= floor) { "The price moved by more than 1%. Please request a new quote." }

            // 2. Layer one: allow-listed programs and instruction types only.
            val raw = jupiter.swapTransaction(q, user)
            SolanaSwapGuard.validateTransaction(raw, pk)?.let { throw IllegalStateException(it) }

            // 3. Layer two: simulate and verify the balance changes are exactly what was asked for.
            val minOut = SwapGuard.minOut(q.outAmount, SWAP_SLIPPAGE_BPS)
            simulateAndVerify(raw, user, quote.from, quote.to, quote.amountInRaw, minOut)

            // 4. Sign and send.
            val signed = SolanaTx.signSwapTransaction(raw, sk, pk)
            val b64 = Base64.getEncoder().encodeToString(signed)
            val sig = rpc("sendTransaction", """["$b64",{"encoding":"base64"}]""").getString("result")
            SwapResult(emptyList(), sig)
        } finally {
            sk.fill(0)
        }
    }

    private fun simulateAndVerify(
        txBytes: ByteArray, user: String, from: SwapAsset, to: SwapAsset, amountIn: BigInteger, minOut: BigInteger
    ) {
        // Every account the swap could plausibly touch: the wallet, all our token accounts, and the
        // associated accounts for the two mints (which may not exist yet).
        val addresses = LinkedHashSet<String>()
        addresses.add(user)
        for (program in listOf(TOKEN_PROGRAM_ID, TOKEN_2022_PROGRAM_ID)) {
            val arr = rpc(
                "getTokenAccountsByOwner",
                """["$user",{"programId":"$program"},{"encoding":"jsonParsed"}]"""
            ).getJSONObject("result").getJSONArray("value")
            for (i in 0 until arr.length()) addresses.add(arr.getJSONObject(i).getString("pubkey"))
        }
        val owner = Base58.decode(user)!!
        val tokenProgram = Base58.decode(TOKEN_PROGRAM_ID)!!
        for (mint in listOf(from.id, to.id, SolanaSwapGuard.WSOL_MINT)) {
            if (mint != null) addresses.add(Base58.encode(SolanaTx.associatedTokenAddress(owner, Base58.decode(mint)!!, tokenProgram)))
        }
        check(addresses.size <= 100) { "Too many token accounts to verify this swap safely" }
        val list = addresses.toList()

        val quoted = JSONArray(list).toString()
        val pre = parseAccounts(
            list,
            rpc("getMultipleAccounts", """[$quoted,{"encoding":"jsonParsed","commitment":"confirmed"}]""")
                .getJSONObject("result").getJSONArray("value")
        )

        val config = JSONObject()
            .put("encoding", "base64")
            .put("sigVerify", false)
            .put("replaceRecentBlockhash", true)
            .put("commitment", "confirmed")
            .put("accounts", JSONObject().put("encoding", "jsonParsed").put("addresses", JSONArray(list)))
        val params = JSONArray().put(Base64.getEncoder().encodeToString(txBytes)).put(config)
        val value = rpc("simulateTransaction", params.toString()).getJSONObject("result").getJSONObject("value")
        if (!value.isNull("err")) throw IllegalStateException("Swap simulation failed: ${value.get("err")}")
        val postArr = value.getJSONArray("accounts")
        check(postArr.length() == list.size) { "Unexpected simulation result" }
        val post = parseAccounts(list, postArr)

        SolSimulationGuard.check(
            pre, post, user,
            srcMint = mintOf(from), srcNative = from.id == null,
            dstMint = mintOf(to), dstNative = to.id == null,
            amountIn = amountIn, minOut = minOut
        )?.let { throw IllegalStateException(it) }
    }

    private fun parseAccounts(addresses: List<String>, arr: JSONArray): Map<String, AccountState?> {
        val out = LinkedHashMap<String, AccountState?>()
        for ((i, address) in addresses.withIndex()) {
            out[address] = if (arr.isNull(i)) null else parseAccountState(arr.getJSONObject(i))
        }
        return out
    }

    private fun parseAccountState(o: JSONObject): AccountState {
        var token: TokenState? = null
        val data = o.opt("data")
        if (data is JSONObject && data.optString("program").startsWith("spl-token")) {
            val parsed = data.getJSONObject("parsed")
            if (parsed.optString("type") == "account") {
                val info = parsed.getJSONObject("info")
                token = TokenState(
                    mint = info.getString("mint"),
                    owner = info.getString("owner"),
                    amount = BigInteger(info.getJSONObject("tokenAmount").getString("amount")),
                    delegate = info.optString("delegate").ifBlank { null },
                    closeAuthority = info.optString("closeAuthority").ifBlank { null }
                )
            }
        }
        return AccountState(o.getLong("lamports"), o.optString("owner").ifBlank { null }, token)
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
                    add(SolanaTx.createAssociatedTokenAccountIdempotent(pk, p.destAta, p.destOwner, p.mint, p.tokenProgram))
                }
                add(SolanaTx.tokenTransferChecked(p.source, p.mint, p.destAta, pk, p.tokenProgram, raw.toLong(), token.decimals))
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
        val destAta = SolanaTx.associatedTokenAddress(destOwner, mint, tokenProgram)
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

                val ix = SolanaTx.systemTransfer(pk, destination, lamports)
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

    companion object {
        private const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        private const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        private const val SWAP_SLIPPAGE_BPS = 50 // 0.5%
        private const val SIGNATURE_FEE_LAMPORTS = 5_000L
        private const val RENT_NOISE_LAMPORTS = 3_000_000L // ~ rent for a couple of token accounts

        private val TOKEN_PROGRAM_ID = TOKEN_PROGRAM
        private val TOKEN_2022_PROGRAM_ID = TOKEN_2022_PROGRAM

        val DEFAULT_KNOWN_MINTS: Map<String, String> = mapOf(
            "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU" to "USDC", // devnet
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "USDC", // mainnet
            "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "USDT", // mainnet
            "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN" to "JUP",
            "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to "BONK",
            "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm" to "WIF",
            "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So" to "mSOL",
            "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn" to "JitoSOL"
        )
    }
}
