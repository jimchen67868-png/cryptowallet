package com.example.wallet.chain

import com.example.wallet.crypto.Hd
import com.example.wallet.data.TokenInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Tron (TRX) and TRC-20 tokens (USDT). Key: BIP-44 m/44'/195'/0'/0/0 (secp256k1, same as TronLink and Ledger).
 *
 * Transactions are built and signed locally ([TronTx]); the node (TronGrid, keyless) only supplies a block reference,
 * balances, fee parameters and the broadcast. After broadcasting, the transaction id the node reports must equal the
 * id computed locally, otherwise the send is reported as an error.
 *
 * Defaults to the Nile testnet. See [mainnet].
 *
 * Tron-specific costs, included in [estimateFee] / [estimateTokenFee] and shown before confirming: a TRX transfer to an
 * address that has never been used costs an extra account-creation fee; a USDT transfer needs "energy", which without
 * staking is paid by burning TRX (several TRX, more when the recipient holds no USDT yet). Prices are read from the
 * network at runtime; the constants in [TronFee] are only fallbacks and deliberately on the high side.
 */
class TronProvider(
    private val apiBase: String = "https://nile.trongrid.io",
    override val isTestnet: Boolean = true,
    private val explorerTxUrl: String = "https://nile.tronscan.org/#/transaction/",
    private val knownTokens: List<TokenInfo> = emptyList(),
    /** THORChain swaps (mainnet only). */
    private val thor: ThorSwaps? = null
) : ChainProvider {

    override val chain = Chain.TRX
    override val supportsSend = true
    override val supportsTokenSend = true
    override val nativeDecimals = 6
    override val networkName = if (isTestnet) "Nile testnet" else "Mainnet"

    private val decimalsByContract: Map<String, Int> = knownTokens.associate { it.address to it.decimals }

    // m/44'/195'/0'/0/0
    private fun keyPair(seed: ByteArray) =
        Hd.secp256k1(seed, 44 or Hd.H, 195 or Hd.H, 0 or Hd.H, 0, 0)

    override fun deriveAddress(seed: ByteArray): String =
        TronAddress.toBase58(TronAddress.fromKeyPair(keyPair(seed)))

    override fun isValidAddress(address: String): Boolean = TronAddress.decode(address) != null

    override fun explorerUrl(txId: String) = explorerTxUrl + txId

    // ---- balances ----

    private var accountCache: Pair<String, Pair<Long, JSONObject?>>? = null // address -> (time, account)

    /** One `/v1/accounts` call serves both the TRX balance and the token balances (short cache: TronGrid rate-limits). */
    private fun account(address: String): JSONObject? {
        val c = accountCache
        val now = System.currentTimeMillis()
        if (c != null && c.first == address && now - c.second.first < 3_000) return c.second.second
        val acc = TronParse.account(JSONObject(Http.get("$apiBase/v1/accounts/$address")))
        accountCache = address to (now to acc)
        return acc
    }

    override suspend fun getBalance(address: String): BigDecimal = withContext(Dispatchers.IO) {
        TronFee.sunToTrx(TronParse.trxBalanceSun(account(address)))
    }

    override suspend fun getTokenBalances(address: String): List<TokenBalance> = withContext(Dispatchers.IO) {
        val raw = TronParse.trc20Balances(account(address))
        // Only tokens from our own list: anyone can airdrop look-alike TRC-20 tokens to an address.
        knownTokens.mapNotNull { t ->
            val units = raw[t.address] ?: return@mapNotNull null
            if (units.signum() <= 0) null
            else TokenBalance(t.address, t.symbol, BigDecimal(units).movePointLeft(t.decimals), t.decimals)
        }
    }

    // ---- history ----

    override suspend fun getHistory(address: String): List<TxRecord> = withContext(Dispatchers.IO) {
        val native = TronParse.nativeHistory(
            JSONObject(Http.get("$apiBase/v1/accounts/$address/transactions?limit=30&order_by=block_timestamp,desc")), address
        )
        val tokens = if (decimalsByContract.isEmpty()) emptyList() else runCatching {
            TronParse.tokenHistory(
                JSONObject(Http.get("$apiBase/v1/accounts/$address/transactions/trc20?limit=30&order_by=block_timestamp,desc")),
                address, decimalsByContract
            )
        }.getOrDefault(emptyList()) // token history is a bonus: don't hide TRX history if it fails
        (native + tokens).sortedByDescending { it.timeSec ?: Long.MAX_VALUE }.take(40)
    }

    // ---- fees ----

    private class Params(val bandwidthSun: Long, val energySun: Long, val createAccountSun: Long, val memoSun: Long)
    private class Resources(val bandwidth: Long, val energy: Long)

    private fun chainParams(): Params {
        val arr = runCatching { JSONObject(Http.get("$apiBase/wallet/getchainparameters")).optJSONArray("chainParameter") }.getOrNull()
        fun find(key: String, default: Long): Long {
            if (arr == null) return default
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("key") == key && o.has("value")) return o.optLong("value", default)
            }
            return default
        }
        return Params(
            find("getTransactionFee", TronFee.DEFAULT_BANDWIDTH_PRICE_SUN),
            find("getEnergyFee", TronFee.DEFAULT_ENERGY_PRICE_SUN),
            find("getCreateNewAccountFeeInSystemContract", TronFee.DEFAULT_CREATE_ACCOUNT_SUN),
            find("getMemoFee", TronFee.DEFAULT_MEMO_FEE_SUN)
        )
    }

    private fun resources(address: String): Resources {
        val j = JSONObject(post("/wallet/getaccountresource", JSONObject().put("address", address).put("visible", true)))
        val bw = (j.optLong("freeNetLimit") - j.optLong("freeNetUsed")) + (j.optLong("NetLimit") - j.optLong("NetUsed"))
        val en = j.optLong("EnergyLimit") - j.optLong("EnergyUsed")
        return Resources(maxOf(0L, bw), maxOf(0L, en))
    }

    private fun post(path: String, body: JSONObject): String = Http.postJson("$apiBase$path", body.toString())

    override suspend fun estimateFee(address: String, to: String, amount: BigDecimal): BigDecimal =
        withContext(Dispatchers.IO) { TronFee.sunToTrx(transferFeeSun(address, to, toSun(amount), null)) }

    /** Fee in sun for a TRX transfer (bandwidth, account creation, memo). Throws if the balance can't cover amount + fee. */
    private fun transferFeeSun(address: String, to: String, sun: Long, memo: ByteArray?): Long {
        val dest = TronAddress.decode(to) ?: throw IllegalArgumentException("Invalid address")
        val p = chainParams()
        val res = resources(address)
        // Size of a transfer is constant for our encoding; build a dummy one to measure it.
        val dummy = TronTx.transferRaw(ByteArray(2), ByteArray(8), 1L shl 40, 1L shl 40,
            TronAddress.decode(address) ?: throw IllegalArgumentException("Invalid own address"), dest, sun, memo)
        var fee = TronFee.bandwidthSun(TronFee.bandwidthBytes(dummy.size), res.bandwidth, p.bandwidthSun)
        if (isInactive(to)) fee += p.createAccountSun
        if (memo != null && memo.isNotEmpty()) fee += p.memoSun
        val balance = TronParse.trxBalanceSun(account(address))
        if (balance < sun + fee) throw IllegalStateException("Insufficient TRX (amount + network fee of about ${TronFee.sunToTrx(fee).toPlainString()} TRX)")
        return fee
    }

    /** True if [address] has never been activated on chain (a first transfer then burns an extra account-creation fee). */
    private fun isInactive(address: String): Boolean = TronParse.account(JSONObject(Http.get("$apiBase/v1/accounts/$address"))) == null

    private class TokenEstimate(val feeSun: Long, val feeLimitSun: Long)

    private fun tokenEstimate(address: String, token: TokenBalance, to: String, amount: BigDecimal): TokenEstimate {
        requireKnown(token)
        val owner = TronAddress.decode(address) ?: throw IllegalArgumentException("Invalid own address")
        val dest = TronAddress.decode(to) ?: throw IllegalArgumentException("Invalid address")
        val contract = TronAddress.decode(token.id) ?: throw IllegalArgumentException("Invalid token contract")
        val raw = toRaw(amount, token.decimals)

        val sim = JSONObject(
            post(
                "/wallet/triggerconstantcontract",
                JSONObject()
                    .put("owner_address", address)
                    .put("contract_address", token.id)
                    .put("function_selector", "transfer(address,uint256)")
                    .put("parameter", TronTx.trc20TransferParameter(dest, raw))
                    .put("visible", true)
            )
        )
        val okSim = sim.optJSONObject("result")?.optBoolean("result", false) ?: false
        if (!okSim) throw IllegalStateException("The token transfer would fail: ${TronParse.errorText(sim.optJSONObject("result") ?: sim)}")
        val energyUsed = sim.optLong("energy_used", 0L)
        if (energyUsed <= 0L) throw IllegalStateException("Could not estimate the energy for this transfer")

        val p = chainParams()
        val res = resources(address)
        val energySun = TronFee.energySun(energyUsed, res.energy, p.energySun)
        val dummy = TronTx.triggerRaw(ByteArray(2), ByteArray(8), 1L shl 40, 1L shl 40, owner, contract,
            TronTx.trc20TransferData(dest, raw), TronFee.MAX_FEE_LIMIT_SUN)
        val bwSun = TronFee.bandwidthSun(TronFee.bandwidthBytes(dummy.size), res.bandwidth, p.bandwidthSun)
        val total = energySun + bwSun
        val balance = TronParse.trxBalanceSun(account(address))
        if (balance < total) throw IllegalStateException(
            "Not enough TRX for the network fee: a USDT transfer needs about ${TronFee.sunToTrx(total).toPlainString()} TRX " +
                "(you have ${TronFee.sunToTrx(balance).toPlainString()} TRX)"
        )
        return TokenEstimate(total, TronFee.feeLimit(energySun))
    }

    override suspend fun estimateTokenFee(address: String, token: TokenBalance, to: String, amount: BigDecimal): BigDecimal =
        withContext(Dispatchers.IO) { TronFee.sunToTrx(tokenEstimate(address, token, to, amount).feeSun) }

    // ---- sending ----

    override suspend fun send(seed: ByteArray, to: String, amount: BigDecimal): String = sendInternal(seed, to, amount, null)

    private suspend fun sendInternal(seed: ByteArray, to: String, amount: BigDecimal, memo: ByteArray?): String = withContext(Dispatchers.IO) {
        val kp = keyPair(seed)
        val owner = TronAddress.fromKeyPair(kp)
        val dest = TronAddress.decode(to) ?: throw IllegalArgumentException("Invalid address")
        require(!dest.contentEquals(owner)) { "You can't send TRX to your own address" }
        val sun = toSun(amount)
        val me = TronAddress.toBase58(owner)
        // Fail early (and with a readable message) if the balance can't cover amount + fee.
        transferFeeSun(me, to, sun, memo)
        val ref = latestBlock()
        val raw = TronTx.transferRaw(ref.bytes, ref.hash, ref.expirationMs, ref.timestampMs, owner, dest, sun, memo)
        broadcast(kp, raw)
    }

    // ---- THORChain swaps (mainnet) ----

    override val supportsBridge: Boolean get() = thor != null

    override fun bridgeDestinations(): List<Chain> = thor?.destinationsFrom(chain).orEmpty()

    override val bridgeProviderNote: String? get() = if (thor != null) ThorSwaps.PROVIDER_NOTE else null

    override suspend fun quoteBridgeTo(
        userAddress: String, from: SwapAsset, dest: Chain, destAddress: String, amount: BigDecimal
    ): BridgeQuote = withContext(Dispatchers.IO) {
        val t = thor ?: throw UnsupportedOperationException("Swaps are not supported on ${chain.label}")
        require(from.id == null) { "Only TRX itself can be swapped to another network here (not USDT)" }
        val sun = toSun(amount)
        val plan = t.plan(chain, dest, destAddress, BigInteger.valueOf(sun).multiply(BigInteger.valueOf(100))) // 1e8 units
        TronAddress.decode(plan.vault) ?: throw IllegalStateException("THORChain gave an address this wallet can't pay")
        val fee = transferFeeSun(userAddress, plan.vault, sun, plan.memo.toByteArray(Charsets.US_ASCII))
        t.toBridgeQuote(plan, from, BigInteger.valueOf(fee), nativeDecimals)
    }

    override suspend fun executeBridge(seed: ByteArray, quote: BridgeQuote): SwapResult = withContext(Dispatchers.IO) {
        val t = thor ?: throw UnsupportedOperationException("Swaps are not supported on ${chain.label}")
        val plan = quote.payload as? ThorPlan ?: throw IllegalStateException("Not a THORChain quote")
        check(plan.from == chain && quote.chain == chain) { "Quote is for another network" }
        t.recheck(plan)
        val txid = sendInternal(seed, plan.vault, BigDecimal(plan.amountIn).movePointLeft(8), plan.memo.toByteArray(Charsets.US_ASCII))
        SwapResult(emptyList(), txid)
    }

    override suspend fun sendToken(seed: ByteArray, token: TokenBalance, to: String, amount: BigDecimal): String =
        withContext(Dispatchers.IO) {
            requireKnown(token)
            val kp = keyPair(seed)
            val owner = TronAddress.fromKeyPair(kp)
            val me = TronAddress.toBase58(owner)
            val dest = TronAddress.decode(to) ?: throw IllegalArgumentException("Invalid address")
            require(!dest.contentEquals(owner)) { "You can't send to your own address" }
            val contract = TronAddress.decode(token.id) ?: throw IllegalArgumentException("Invalid token contract")
            val raw = toRaw(amount, token.decimals)
            val have = TronParse.trc20Balances(TronParse.account(JSONObject(Http.get("$apiBase/v1/accounts/$me"))))[token.id]
                ?: BigInteger.ZERO
            if (raw > have) throw IllegalStateException("Amount exceeds your ${token.symbol} balance")
            val est = tokenEstimate(me, token, to, amount)
            val ref = latestBlock()
            val rawData = TronTx.triggerRaw(
                ref.bytes, ref.hash, ref.expirationMs, ref.timestampMs, owner, contract,
                TronTx.trc20TransferData(dest, raw), est.feeLimitSun
            )
            broadcast(kp, rawData)
        }

    private class Ref(val bytes: ByteArray, val hash: ByteArray, val expirationMs: Long, val timestampMs: Long)

    private fun latestBlock(): Ref {
        val j = JSONObject(post("/wallet/getnowblock", JSONObject()))
        val header = j.optJSONObject("block_header")?.optJSONObject("raw_data") ?: throw IllegalStateException("Unexpected block response")
        val number = header.optLong("number", -1L)
        check(number >= 0) { "Unexpected block response" }
        val (bytes, hash) = TronTx.blockReference(j.optString("blockID"), number)
        val now = System.currentTimeMillis()
        // The transaction must not look expired to the node, so anchor the expiry to the later of the block and device time.
        val expiration = maxOf(now, header.optLong("timestamp", now)) + 60_000L
        return Ref(bytes, hash, expiration, now)
    }

    private fun broadcast(kp: org.web3j.crypto.ECKeyPair, raw: ByteArray): String {
        val localId = TronTx.toHex(TronTx.txId(raw))
        val signed = TronTx.signedTransaction(raw, TronTx.sign(kp, raw))
        val j = JSONObject(post("/wallet/broadcasthex", JSONObject().put("transaction", TronTx.toHex(signed))))
        if (!j.optBoolean("result", false)) throw IllegalStateException("Broadcast rejected: ${TronParse.errorText(j)}")
        val reported = j.optString("txid", localId)
        check(reported.equals(localId, ignoreCase = true)) { "Node reported a different transaction id, aborting" }
        return localId
    }

    // ---- helpers ----

    private fun requireKnown(token: TokenBalance) {
        // Fail closed: only tokens from the built-in list can be sent, so a bad contract address can never be signed.
        check(decimalsByContract[token.id] == token.decimals) { "Unsupported token" }
    }

    private fun toSun(amount: BigDecimal): Long {
        val sun = try {
            amount.movePointRight(6).toBigIntegerExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("TRX amounts allow at most 6 decimal places")
        }
        require(sun.signum() > 0 && sun.bitLength() < 63) { "Invalid amount" }
        return sun.toLong()
    }

    private fun toRaw(amount: BigDecimal, decimals: Int): BigInteger {
        val raw = try {
            amount.movePointRight(decimals).toBigIntegerExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("This token allows at most $decimals decimal places")
        }
        require(raw.signum() > 0) { "Amount must be positive" }
        return raw
    }

    companion object {
        /** USDT (TRC-20) on Tron mainnet. USDC is not listed: Circle stopped issuing USDC on Tron. */
        const val USDT_MAINNET = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
        val MAINNET_TOKENS = listOf(TokenInfo(USDT_MAINNET, "USDT", 6))

        fun mainnet() = TronProvider(
            apiBase = "https://api.trongrid.io", isTestnet = false,
            explorerTxUrl = "https://tronscan.org/#/transaction/", knownTokens = MAINNET_TOKENS, thor = ThorSwaps()
        )
    }
}
