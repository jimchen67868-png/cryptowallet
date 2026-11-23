package com.example.wallet.chain

import com.example.wallet.crypto.Hd
import com.example.wallet.data.TokenInfo
import com.example.wallet.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.crypto.Credentials
import org.web3j.crypto.WalletUtils
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/** Ethereum / EVM. Defaults to the Sepolia testnet. */
class EthereumProvider(
    private val tokenStore: TokenStore,
    rpcUrl: String = "https://ethereum-sepolia-rpc.publicnode.com",
    private val chainId: Long = 11155111L,
    /** Well-known tokens shown automatically when the balance is above zero. Verify addresses per network. */
    private val knownTokens: List<TokenInfo> = SEPOLIA_TOKENS,
    /** Blockscout REST API (no key needed) used for history. */
    private val explorerApi: String? = "https://eth-sepolia.blockscout.com/api/v2",
    private val explorerTxUrl: String = "https://sepolia.etherscan.io/tx/",
    /** Which EVM network this instance talks to (Ethereum, Base, Arbitrum, Polygon, BNB Chain). */
    private val evmChain: Chain = Chain.ETH,
    override val isTestnet: Boolean = true,
    testnetName: String = "Sepolia testnet",
    /** ParaSwap network id (1, 8453, 42161, 137, 56). Null disables swaps, e.g. on testnets. */
    private val swapNetworkId: Int? = null,
    /** Uniswap v3 deployment, used on testnets where no aggregator has liquidity. Null disables it. */
    private val uniswap: UniswapV3Config? = null,
    /** Across bridge routes (mainnets only). The route for this chain's own id must be among them. */
    private val bridgeRoutes: List<BridgeRoute> = emptyList(),
    /** Uniswap v2 router, tried alongside [uniswap] on testnets. Only used when [uniswap] is set too. */
    private val uniswapV2: UniswapV2Config? = null,
    /** Bridge TEST ETH to the other Relay testnet (Sepolia <-> Base Sepolia). Never set on mainnet. */
    private val relayTestnet: Boolean = false,
    /** Bridge the CCIP-BnM test token between the CCIP test networks (Sepolia, BNB, Base, Arbitrum) through Chainlink CCIP. Never set on mainnet. */
    private val ccipTestnet: Boolean = false,
    /** THORChain swaps from the native coin to Bitcoin, Litecoin and Tron (mainnet only). */
    private val thor: ThorSwaps? = null
) : ChainProvider {

    override val chain = evmChain
    override val supportsSend = true
    override val networkName = if (isTestnet) testnetName else "Mainnet"
    override val supportsCustomTokens = true
    override val supportsTokenSend = true

    private val web3: Web3j by lazy { Web3j.build(HttpService(rpcUrl)) }

    // m/44'/60'/0'/0/0
    private fun credentials(seed: ByteArray): Credentials = EvmKeys.credentials(seed)

    override fun deriveAddress(seed: ByteArray): String = credentials(seed).address

    override suspend fun getBalance(address: String): BigDecimal = withContext(Dispatchers.IO) {
        val res = web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send()
        if (res.hasError()) throw IllegalStateException(res.error.message)
        Convert.fromWei(BigDecimal(res.balance), Convert.Unit.ETHER)
    }

    // ---- ERC-20 ----

    override suspend fun getTokenBalances(address: String): List<TokenBalance> = withContext(Dispatchers.IO) {
        val custom = tokenStore.list(chain)
        val customKeys = custom.map { it.address.lowercase() }.toSet()
        val all = (custom + knownTokens.filter { it.address.lowercase() !in customKeys })
        coroutineScope {
            all.map { t ->
                async {
                    runCatching {
                        val isCustom = t.address.lowercase() in customKeys
                        val bal = BigDecimal(balanceOf(t.address, address)).movePointLeft(t.decimals)
                        // Known tokens are hidden at zero; tokens the user added always show.
                        if (bal.signum() == 0 && !isCustom) null
                        else TokenBalance(t.address, t.symbol, bal, t.decimals, removable = isCustom)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    override suspend fun addToken(contract: String) {
        withContext(Dispatchers.IO) {
            require(WalletUtils.isValidAddress(contract)) { "Invalid contract address" }
            val decimals = (callContract(contract, DECIMALS_FN).firstOrNull()?.value as? BigInteger)
                ?: throw IllegalArgumentException("Not an ERC-20 token contract on this network")
            val symbol = runCatching { callContract(contract, SYMBOL_FN)[0].value as String }
                .getOrDefault("ERC20")
            tokenStore.add(chain, TokenInfo(contract, symbol, decimals.toInt()))
        }
    }

    override fun removeToken(id: String) = tokenStore.remove(chain, id)

    private fun balanceOf(token: String, owner: String): BigInteger {
        val fn = Function(
            "balanceOf",
            listOf<Type<*>>(Address(owner)),
            listOf<TypeReference<*>>(TypeReference.create(Uint256::class.java))
        )
        val out = callContract(token, fn)
        return out.firstOrNull()?.value as? BigInteger
            ?: throw IllegalStateException("balanceOf returned nothing")
    }

    /** Read-only eth_call. Returns an empty list if the address isn't a contract. */
    private fun callContract(contract: String, fn: Function): List<Type<*>> {
        val tx = Transaction.createEthCallTransaction(ZERO_ADDRESS, contract, FunctionEncoder.encode(fn))
        val res = web3.ethCall(tx, DefaultBlockParameterName.LATEST).send()
        if (res.hasError()) throw IllegalStateException(res.error.message)
        return FunctionReturnDecoder.decode(res.value, fn.outputParameters)
    }

    // ---- history ----

    override fun explorerUrl(txId: String) = explorerTxUrl + txId

    /** ETH transfers (value > 0) plus ERC-20 transfers, from Blockscout. */
    override suspend fun getHistory(address: String): List<TxRecord> = withContext(Dispatchers.IO) {
        val api = explorerApi ?: throw UnsupportedOperationException(
            "No free history API is configured for ${chain.label}. Add a Blockscout endpoint in AppContainer."
        )
        val me = address.lowercase()
        val out = ArrayList<TxRecord>()

        for (o in fetchItems("$api/addresses/$address/transactions")) {
            val wei = runCatching { BigInteger(o.optString("value", "0").ifBlank { "0" }) }.getOrNull() ?: continue
            if (wei.signum() == 0) continue // contract calls: token moves are listed below
            out.add(
                historyRecord(
                    id = o.optString("hash", ""),
                    ts = o.optString("timestamp", ""),
                    me = me,
                    from = o.optJSONObject("from")?.optString("hash", "").orEmpty().lowercase(),
                    to = o.optJSONObject("to")?.optString("hash", "").orEmpty().lowercase(),
                    amount = BigDecimal(wei).movePointLeft(18),
                    symbol = chain.symbol,
                    failed = o.optString("status") == "error"
                )
            )
        }

        val transfers = runCatching {
            fetchItems("$api/addresses/$address/token-transfers?type=ERC-20")
        }.getOrDefault(emptyList())
        for (o in transfers) {
            val id = o.optString("transaction_hash", "").ifBlank { o.optString("tx_hash", "") }
            val total = o.optJSONObject("total")
            val token = o.optJSONObject("token")
            val raw = runCatching { BigInteger(total?.optString("value", "").orEmpty()) }.getOrNull() ?: continue
            val decimals = total?.optString("decimals", "")?.toIntOrNull()
                ?: token?.optString("decimals", "")?.toIntOrNull()
                ?: 18
            if (id.isBlank()) continue
            out.add(
                historyRecord(
                    id = id,
                    ts = o.optString("timestamp", ""),
                    me = me,
                    from = o.optJSONObject("from")?.optString("hash", "").orEmpty().lowercase(),
                    to = o.optJSONObject("to")?.optString("hash", "").orEmpty().lowercase(),
                    amount = BigDecimal(raw).movePointLeft(decimals),
                    symbol = token?.optString("symbol", "")?.ifBlank { null } ?: "ERC20",
                    failed = false
                )
            )
        }
        out
    }

    private fun fetchItems(url: String): List<JSONObject> {
        val arr = JSONObject(Http.get(url)).optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun historyRecord(
        id: String, ts: String, me: String, from: String, to: String,
        amount: BigDecimal, symbol: String, failed: Boolean
    ): TxRecord {
        val direction = when {
            from == me && to == me -> TxDirection.SELF
            from == me -> TxDirection.OUT
            else -> TxDirection.IN
        }
        val counterparty = if (direction == TxDirection.IN) from else to
        return TxRecord(
            id = id,
            timeSec = runCatching { Instant.parse(ts).epochSecond }.getOrNull(),
            direction = direction,
            amount = amount,
            symbol = symbol,
            counterparty = counterparty.ifBlank { null },
            status = if (failed) TxStatus.FAILED else TxStatus.CONFIRMED
        )
    }

    // ---- swaps (ParaSwap) ----

    override val supportsSwap: Boolean get() = swapNetworkId != null || uniswap != null
    override val swapProviderName: String
        get() = when {
            swapNetworkId != null -> "ParaSwap"
            uniswapV2 != null -> "Uniswap v3 and v2"
            else -> "Uniswap v3"
        }

    private val paraSwap: ParaSwapApi? by lazy { swapNetworkId?.let { ParaSwapApi(it) } }

    /** Native coin, the curated tokens, WETH (for wrap/unwrap on Uniswap networks) and tokens the user added. */
    override fun swapAssets(): List<SwapAsset> {
        if (!supportsSwap) return emptyList()
        val curated = listOf(SwapAsset(null, chain.symbol, 18)) +
            knownTokens.map { SwapAsset(it.address, it.symbol, it.decimals) }
        val have = curated.mapNotNull { it.id?.lowercase() }.toSet()
        val custom = tokenStore.list(chain)
            .filter { it.address.lowercase() !in have }
            .map { SwapAsset(it.address, it.symbol, it.decimals, curated = false) }
        val weth = uniswap?.weth
            ?.takeIf { w -> (curated + custom).none { it.id.equals(w, ignoreCase = true) } }
            ?.let { SwapAsset(it, "WETH", 18) }
        return curated + listOfNotNull(weth) + custom
    }

    override suspend fun quoteSwap(
        userAddress: String, from: SwapAsset, to: SwapAsset, amount: BigDecimal
    ): SwapQuote =
        if (swapNetworkId != null) quoteParaSwap(userAddress, from, to, amount)
        else quoteUniswap(userAddress, from, to, amount)

    override suspend fun executeSwap(seed: ByteArray, quote: SwapQuote): SwapResult =
        if (swapNetworkId != null) executeParaSwap(seed, quote) else executeUniswap(seed, quote)

    private suspend fun quoteParaSwap(
        userAddress: String, from: SwapAsset, to: SwapAsset, amount: BigDecimal
    ): SwapQuote = withContext(Dispatchers.IO) {
        val api = paraSwap ?: throw UnsupportedOperationException("Swaps are not available on ${chain.label}")
        require(SwapGuard.tokenKey(from) != SwapGuard.tokenKey(to)) { "Choose two different assets" }
        val raw = rawAmount(amount, from.decimals)
        val route = api.route(userAddress, from, to, raw)
        var needsApproval = false
        if (from.id != null) {
            val spender = route.spender ?: SwapGuard.AUGUSTUS_V6
            check(SwapGuard.isAllowedSpender(spender)) { "Unexpected token spender in the quote" }
            needsApproval = allowance(from.id, userAddress, spender) < raw
        }
        SwapQuote(chain, from, to, raw, route.destAmount, SWAP_SLIPPAGE_BPS, needsApproval, route.gasUsd)
    }

    private suspend fun executeParaSwap(seed: ByteArray, quote: SwapQuote): SwapResult = withContext(Dispatchers.IO) {
        val api = paraSwap ?: throw UnsupportedOperationException("Swaps are not available on ${chain.label}")
        val creds = credentials(seed)
        val user = creds.address
        val txManager = RawTransactionManager(web3, creds, chainId)
        val approvals = ArrayList<String>()

        // 1. Fresh route; refuse if the price moved against the user since the quote they saw.
        var route = api.route(user, quote.from, quote.to, quote.amountInRaw)
        ensurePriceHeld(quote, route)

        // 2. ERC-20 sources: approve exactly this amount (never unlimited) to an allow-listed spender.
        val srcToken = quote.from.id
        if (srcToken != null) {
            val spender = route.spender ?: SwapGuard.AUGUSTUS_V6
            check(SwapGuard.isAllowedSpender(spender)) { "Unexpected token spender in the quote" }
            val current = allowance(srcToken, user, spender)
            if (current < quote.amountInRaw) {
                // Some tokens (e.g. USDT) refuse a new non-zero approval while an old one is outstanding.
                if (current.signum() > 0) approvals.add(sendApproval(txManager, user, srcToken, spender, BigInteger.ZERO))
                approvals.add(sendApproval(txManager, user, srcToken, spender, quote.amountInRaw))
                route = api.route(user, quote.from, quote.to, quote.amountInRaw) // the wait may have moved the price
                ensurePriceHeld(quote, route)
            }
        }

        // 3. Build the swap transaction, then verify it ourselves before signing.
        val tx = api.build(user, quote.from, quote.to, route, quote.amountInRaw, quote.slippageBps)
        SwapGuard.validateSwapTx(
            tx.to, tx.value, tx.data, quote.from, quote.to, quote.amountInRaw, route.destAmount, user
        )?.let { throw IllegalStateException(it) }
        route.contractAddress?.let {
            check(it.equals(tx.to, ignoreCase = true)) { "Router differs from the one in the quote" }
        }
        check(tx.chainId == 0L || tx.chainId == chainId) { "Transaction is for a different network" }

        // 4. Simulate (eth_estimateGas fails if it would revert), then send.
        val gas = gasForCall(user, tx.to, tx.value, tx.data)
        val gasPrice = web3.ethGasPrice().send().gasPrice
        val res = txManager.sendTransaction(gasPrice, gas, tx.to, tx.data, tx.value)
        if (res.hasError()) throw IllegalStateException(res.error.message)
        SwapResult(approvals, res.transactionHash)
    }

    // ---- bridging to another network (Across): call data is built here, the API only supplies fee/timing numbers ----

    private val lifi: LiFiApi by lazy { LiFiApi() }
    private val originRoute: BridgeRoute? get() = bridgeRoutes.firstOrNull { it.chain == chain }

    private val ccipLane: CcipTestnet.Lane? get() = if (ccipTestnet) CcipTestnet.laneOf(chain) else null

    override val supportsBridge: Boolean
        get() = if (relayTestnet || ccipTestnet) {
            (relayTestnet && RelayTestnet.chainIdOf(chain) != null) || ccipLane != null
        } else (originRoute != null && bridgeRoutes.size > 1) || thorDestinations().isNotEmpty()

    private fun thorDestinations(): List<Chain> = if (isTestnet) emptyList() else thor?.destinationsFrom(chain).orEmpty()

    override fun bridgeDestinations(): List<Chain> = when {
        relayTestnet || ccipTestnet ->
            ((if (relayTestnet) RelayTestnet.destinations(chain) else emptyList()) +
                (if (ccipTestnet) CcipTestnet.destinations(chain) else emptyList())).distinct()
        else -> ((if (originRoute != null && bridgeRoutes.size > 1) bridgeRoutes.map { it.chain }.filter { it != chain } else emptyList()) +
            thorDestinations()).distinct()
    }

    // ---- THORChain: native coin -> BTC / LTC / TRX, paid into THORChain's router contract ----

    override suspend fun quoteBridgeTo(
        userAddress: String, from: SwapAsset, dest: Chain, destAddress: String, amount: BigDecimal
    ): BridgeQuote =
        if (dest in thorDestinations()) quoteThor(userAddress, from, dest, destAddress, amount)
        else quoteBridge(userAddress, from, dest, amount)

    private suspend fun quoteThor(user: String, from: SwapAsset, dest: Chain, destAddress: String, amount: BigDecimal): BridgeQuote =
        withContext(Dispatchers.IO) {
            val t = thor ?: throw UnsupportedOperationException("Swaps are not supported here")
            require(from.id == null) { "Only ${chain.symbol} itself can be swapped to ${dest.label} (not tokens yet)" }
            val units = try {
                amount.movePointRight(8).toBigIntegerExact()
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("At most 8 decimal places are allowed for this swap")
            }
            require(units.signum() > 0) { "Invalid amount" }
            val plan = t.plan(chain, dest, destAddress, units)
            val (data, wei) = thorCall(plan)
            val gas = gasForCall(user, plan.router, wei, data) // fails closed if the router would reject this call
            val gasPrice = web3.ethGasPrice().send().gasPrice
            t.toBridgeQuote(plan, from, gas.multiply(gasPrice), 18)
        }

    /** Calldata and value for the router deposit of [plan]. Also checks that both addresses are well-formed. */
    private fun thorCall(plan: ThorPlan): Pair<String, BigInteger> {
        check(WalletUtils.isValidAddress(plan.vault) && WalletUtils.isValidAddress(plan.router)) { "THORChain gave an address this wallet can't pay" }
        val wei = ThorRouter.unitsToWei(plan.amountIn)
        val data = ThorRouter.depositWithExpiry(plan.vault, wei, plan.memo, nowSeconds() + ThorRouter.EXPIRY_SECONDS)
        return data to wei
    }

    private fun checkRouterHasCode(router: String) {
        val code = web3.ethGetCode(router, org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send().code
        check(code != null && code.length > 2) { "THORChain's router has no contract code on this network. Nothing was sent." }
    }

    private fun executeThor(seed: ByteArray, quote: BridgeQuote, plan: ThorPlan): SwapResult {
        val t = thor ?: throw UnsupportedOperationException("Swaps are not supported here")
        check(plan.from == chain && quote.chain == chain) { "Quote is for another network" }
        t.recheck(plan)
        checkRouterHasCode(plan.router)
        val creds = credentials(seed)
        val (data, wei) = thorCall(plan)
        val hash = sendCall(RawTransactionManager(web3, creds, chainId), creds.address, plan.router, wei, data)
        return SwapResult(emptyList(), hash)
    }

    override suspend fun dryRunBridge(userAddress: String, quote: BridgeQuote): String = withContext(Dispatchers.IO) {
        val plan = quote.payload as? ThorPlan ?: return@withContext "A dry run is not available for this route."
        val t = thor ?: throw UnsupportedOperationException("Swaps are not supported here")
        check(plan.from == chain && quote.chain == chain) { "Quote is for another network" }
        val report = t.dryRunReport(plan)
        checkRouterHasCode(plan.router)
        val (data, wei) = thorCall(plan)
        val gas = gasForCall(userAddress, plan.router, wei, data)
        val fee = Convert.fromWei(BigDecimal(gas.multiply(web3.ethGasPrice().send().gasPrice)), Convert.Unit.ETHER)
        report + "\nTransaction that would be sent:\n" +
            "- call depositWithExpiry on the router with ${Convert.fromWei(BigDecimal(wei), Convert.Unit.ETHER).toPlainString()} ${chain.symbol}\n" +
            "- the router has contract code on ${chain.label}\n" +
            "- the node simulated the call: it would succeed\n" +
            "- gas about ${gas} units, network fee about ${fee.toPlainString()} ${chain.symbol}"
    }

    // ---- Chainlink CCIP (testnet): CCIP-BnM between Sepolia and BNB testnet, called on the Router directly ----

    /** The Router's fee for this exact transfer, in the native coin. A Router refuses an unsupported token or lane here. */
    private fun ccipFee(lane: CcipTestnet.Lane, dest: CcipTestnet.Lane, user: String, raw: BigInteger): BigInteger {
        // Not every pair of CCIP testnets is connected: ask the source Router before asking for a fee.
        val supported = web3.ethCall(
            Transaction.createEthCallTransaction(user, lane.router, CcipAbi.isChainSupported(dest.selector)), DefaultBlockParameterName.LATEST
        ).send()
        if (supported.hasError() || !CcipAbi.isTrue(supported.value)) {
            throw IllegalStateException("CCIP has no lane from ${chain.label} to ${dest.chain.label} on testnet. Try another destination.")
        }
        val data = CcipAbi.getFee(dest.selector, user, lane.bnm, raw)
        val res = web3.ethCall(Transaction.createEthCallTransaction(user, lane.router, data), DefaultBlockParameterName.LATEST).send()
        if (res.hasError()) throw IllegalStateException("The CCIP router refused this transfer: ${res.error.message.orEmpty().take(160)}")
        val fee = CcipAbi.feeFromResult(res.value)
        CcipGuard.validateFee(fee)?.let { throw IllegalStateException(it) }
        return fee!!
    }

    private fun quoteCcip(user: String, from: SwapAsset, dest: Chain, raw: BigInteger): BridgeQuote {
        val lane = ccipLane
        val destLane = CcipTestnet.laneOf(dest)
        CcipGuard.validateRequest(lane, destLane, from.id, raw)?.let { throw IllegalStateException(it) }
        lane!!; destLane!!
        val fee = ccipFee(lane, destLane, user, raw)
        val needsApproval = allowance(lane.bnm, user, lane.router) < raw
        // CCIP-BnM is burned here and minted there one for one; the fee is paid separately in the native coin.
        return BridgeQuote(
            chain, dest, from, raw, raw, BigInteger.ZERO, CcipTestnet.BNM_DECIMALS, CcipTestnet.BNM_SYMBOL, 0L, needsApproval,
            networkFeeRaw = fee, networkFeeSymbol = chain.symbol
        )
    }

    private fun executeCcip(seed: ByteArray, quote: BridgeQuote): SwapResult {
        val lane = ccipLane
        val destLane = CcipTestnet.laneOf(quote.destChain)
        val raw = quote.amountInRaw
        CcipGuard.validateRequest(lane, destLane, quote.from.id, raw)?.let { throw IllegalStateException(it) }
        lane!!; destLane!!
        val creds = credentials(seed)
        val user = creds.address
        val txManager = RawTransactionManager(web3, creds, chainId)

        // Exact-amount approval to the Router (never unlimited).
        val approvals = ArrayList<String>()
        val current = allowance(lane.bnm, user, lane.router)
        if (current < raw) {
            if (current.signum() > 0) approvals.add(sendApproval(txManager, user, lane.bnm, lane.router, BigInteger.ZERO))
            approvals.add(sendApproval(txManager, user, lane.bnm, lane.router, raw))
        }

        // Read the fee after the approval confirmed, so it is current; refuse if it moved a lot since the user saw it.
        val fee = ccipFee(lane, destLane, user, raw)
        check(CcipGuard.freshFeeAcceptable(quote.networkFeeRaw, fee)) { "The CCIP fee changed. Please request a new quote." }

        // eth_estimateGas inside sendCall fails closed if the Router would reject this call.
        val hash = sendCall(txManager, user, lane.router, fee, CcipAbi.ccipSend(destLane.selector, user, lane.bnm, raw))
        return SwapResult(approvals, hash, "ccip:$hash")
    }

    override val faucetTokenSymbol: String? get() = ccipLane?.let { CcipTestnet.BNM_SYMBOL }

    /** CCIP-BnM's public faucet: drip(address) mints a small amount to the caller. */
    override suspend fun requestFaucetToken(seed: ByteArray): String = withContext(Dispatchers.IO) {
        val lane = ccipLane ?: throw UnsupportedOperationException("No test-token faucet on this network")
        val creds = credentials(seed)
        val txManager = RawTransactionManager(web3, creds, chainId)
        sendCall(txManager, creds.address, lane.bnm, BigInteger.ZERO, CcipAbi.drip(creds.address))
    }

    private val relay: RelayApi by lazy { RelayApi() }

    /** A fresh Relay testnet quote for exactly what the user asked for, checked by [RelayGuard]. */
    private fun freshRelayQuote(user: String, dest: Chain, raw: BigInteger): RelayQuote {
        val destId = RelayTestnet.chainIdOf(dest) ?: throw IllegalArgumentException("${dest.label} is not a supported destination")
        val req = RelayRequest(user, chainId, destId, raw)
        val quote = relay.quote(req)
        RelayGuard.validate(quote, req)?.let { throw IllegalStateException(it) }
        return quote
    }

    private class BridgePlan(
        val route: BridgeRoute, val dest: BridgeRoute,
        val inputToken: String, val outputToken: String, val decimals: Int, val outSymbol: String
    )

    private fun bridgePlan(from: SwapAsset, destChain: Chain): BridgePlan {
        val origin = originRoute ?: throw UnsupportedOperationException("Bridging is not available on ${chain.label}")
        val dest = bridgeRoutes.firstOrNull { it.chain == destChain && it.chain != chain }
            ?: throw IllegalArgumentException("${destChain.label} is not a supported destination")
        if (from.id == null) {
            check(origin.nativeIsEth) { "${chain.symbol} can't be bridged here. Bridge a token such as USDC instead." }
            // Native ETH travels as WETH on the SpokePool; the destination receives ETH or WETH.
            return BridgePlan(origin, dest, origin.weth, dest.weth, 18, if (dest.nativeIsEth) "ETH (or WETH)" else "WETH")
        }
        val src = origin.tokens.firstOrNull { it.address.equals(from.id, ignoreCase = true) }
            ?: throw IllegalArgumentException("${from.symbol} can't be bridged from ${chain.label}")
        val dst = dest.tokens.firstOrNull { it.symbol == src.symbol }
            ?: throw IllegalArgumentException("${src.symbol} isn't available on ${dest.chain.label}")
        check(src.decimals == dst.decimals) { "${src.symbol} has different decimals on ${dest.chain.label}" }
        return BridgePlan(origin, dest, src.address, dst.address, src.decimals, dst.symbol)
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    /** A fresh LI.FI quote for exactly what the user asked for, checked by [LiFiGuard]. Throws with a user-facing reason. */
    private fun freshQuote(plan: BridgePlan, from: SwapAsset, raw: BigInteger, user: String): LiFiQuote {
        if (from.id == null) throw IllegalStateException(LiFiGuard.NATIVE_UNSUPPORTED)
        val req = LiFiRequest(user, plan.route.chainId, plan.dest.chainId, from.id, plan.outputToken, raw)
        val quote = lifi.quote(req)
        LiFiGuard.validate(quote, req, nowSeconds())?.let { throw IllegalStateException(it) }
        return quote
    }

    override suspend fun quoteBridge(
        userAddress: String, from: SwapAsset, dest: Chain, amount: BigDecimal
    ): BridgeQuote = withContext(Dispatchers.IO) {
        if (relayTestnet || ccipTestnet) {
            if (ccipLane != null && from.id != null && CcipTestnet.laneOf(dest) != null) {
                return@withContext quoteCcip(userAddress, from, dest, rawAmount(amount, from.decimals))
            }
            if (!relayTestnet || from.id != null || RelayTestnet.chainIdOf(dest) == null) throw IllegalStateException(TestnetRoutes.UNSUPPORTED)
            val rawEth = rawAmount(amount, from.decimals)
            val rq = freshRelayQuote(userAddress, dest, rawEth)
            return@withContext BridgeQuote(
                chain, dest, from, rawEth, rq.amountOut, rawEth.subtract(rq.amountOut),
                18, dest.symbol, rq.etaSeconds, false
            )
        }
        val plan = bridgePlan(from, dest)
        val raw = rawAmount(amount, from.decimals)
        val q = freshQuote(plan, from, raw, userAddress)
        val needsApproval = from.id != null && allowance(from.id, userAddress, q.approvalAddress) < raw
        // Total cost = LI.FI's 0.25% service fee plus the Across fee.
        BridgeQuote(
            chain, dest, from, raw, q.toAmount, raw.subtract(q.toAmount),
            plan.decimals, plan.outSymbol, q.executionSeconds, needsApproval
        )
    }

    override suspend fun executeBridge(seed: ByteArray, quote: BridgeQuote): SwapResult = withContext(Dispatchers.IO) {
        (quote.payload as? ThorPlan)?.let { return@withContext executeThor(seed, quote, it) }
        if (relayTestnet || ccipTestnet) {
            if (ccipLane != null && quote.from.id != null && CcipTestnet.laneOf(quote.destChain) != null) {
                return@withContext executeCcip(seed, quote)
            }
            if (!relayTestnet || quote.from.id != null || RelayTestnet.chainIdOf(quote.destChain) == null) throw IllegalStateException(TestnetRoutes.UNSUPPORTED)
            val rcreds = credentials(seed)
            val ruser = rcreds.address
            val rtx = RawTransactionManager(web3, rcreds, chainId)
            val rq = freshRelayQuote(ruser, quote.destChain, quote.amountInRaw)
            val rfloor = quote.amountOutRaw.multiply(BigInteger.valueOf(95)).divide(BigInteger.valueOf(100))
            check(rq.amountOut >= rfloor) { "Relay's fee changed. Please request a new quote." }
            // eth_estimateGas inside sendCall fails closed if the depository would reject this call.
            val hash = sendCall(rtx, ruser, rq.txTo, rq.txValue, rq.txData)
            return@withContext SwapResult(emptyList(), hash, rq.requestId)
        }
        val plan = bridgePlan(quote.from, quote.destChain)
        val creds = credentials(seed)
        val user = creds.address
        val txManager = RawTransactionManager(web3, creds, chainId)
        val raw = quote.amountInRaw
        val srcToken = quote.from.id ?: throw IllegalStateException(LiFiGuard.NATIVE_UNSUPPORTED)
        val floor = quote.amountOutRaw.multiply(BigInteger.valueOf(98)).divide(BigInteger.valueOf(100))

        var q = freshQuote(plan, quote.from, raw, user)
        check(q.toAmount >= floor) { "The bridge fee changed. Please request a new quote." }

        // Approve exactly this amount to LI.FI's (pinned) Diamond, never unlimited.
        val approvals = ArrayList<String>()
        val current = allowance(srcToken, user, q.approvalAddress)
        if (current < raw) {
            if (current.signum() > 0) approvals.add(sendApproval(txManager, user, srcToken, q.approvalAddress, BigInteger.ZERO))
            approvals.add(sendApproval(txManager, user, srcToken, q.approvalAddress, raw))
            // LI.FI says to re-fetch after an approval: quote timestamps and gas estimates go stale while it confirms.
            q = freshQuote(plan, quote.from, raw, user)
            check(q.toAmount >= floor) { "The bridge fee changed. Please request a new quote." }
        }

        // eth_estimateGas inside sendCall fails closed if the Diamond would reject this call.
        SwapResult(approvals, sendCall(txManager, user, q.txTo, BigInteger.ZERO, q.txData))
    }

    override suspend fun bridgeFillStatus(depositTxId: String): FillStatus = withContext(Dispatchers.IO) {
        try {
            when {
                depositTxId.startsWith("ccip:") -> FillStatus.UNKNOWN // CCIP is followed by the destination balance
                relayTestnet -> relay.status(depositTxId)
                ccipTestnet -> FillStatus.UNKNOWN
                else -> lifi.status(depositTxId, chainId.toLong())
            }
        } catch (e: Exception) { FillStatus.UNKNOWN }
    }

    // ---- swaps on testnets (Uniswap v3): calldata is built here, nothing third-party is signed ----

    private fun isWrap(cfg: UniswapV3Config, from: SwapAsset, to: SwapAsset) =
        from.id == null && to.id.equals(cfg.weth, ignoreCase = true)

    private fun isUnwrap(cfg: UniswapV3Config, from: SwapAsset, to: SwapAsset) =
        to.id == null && from.id.equals(cfg.weth, ignoreCase = true)

    private suspend fun quoteUniswap(
        userAddress: String, from: SwapAsset, to: SwapAsset, amount: BigDecimal
    ): SwapQuote = withContext(Dispatchers.IO) {
        val cfg = uniswap ?: throw UnsupportedOperationException("Swaps are not available on ${chain.label} $networkName")
        require(SwapGuard.tokenKey(from) != SwapGuard.tokenKey(to)) { "Choose two different assets" }
        val raw = rawAmount(amount, from.decimals)
        // ETH <-> WETH is a 1:1 wrap/unwrap on the WETH contract: no pool, no slippage.
        if (isWrap(cfg, from, to) || isUnwrap(cfg, from, to)) {
            return@withContext SwapQuote(chain, from, to, raw, raw, 0, false, null)
        }
        val v3 = runCatching { bestUniswapQuote(cfg, from, to, raw) }
        val v2cfg = uniswapV2
        if (v2cfg == null) {
            // No v2 configured: behave exactly as v3-only (a missing pool surfaces v3's own error message).
            val best = v3.getOrThrow()
            val needsApproval = from.id != null && allowance(from.id, userAddress, cfg.router) < raw
            return@withContext SwapQuote(chain, from, to, raw, best.second, UNISWAP_SLIPPAGE_BPS, needsApproval, null, VENUE_V3)
        }
        val v2 = runCatching { bestV2Quote(v2cfg, from, to, raw) }
        val v3out = v3.getOrNull()?.second
        val v2out = v2.getOrNull()?.second
        val useV2 = UniswapVenue.preferV2(v3out, v2out) ?: throw IllegalStateException(
            "No Uniswap v3 or v2 pool with liquidity for this pair on $networkName. " +
                "Pick another pair, or create a pool at app.uniswap.org."
        )
        val router = if (useV2) v2cfg.router else cfg.router
        val needsApproval = from.id != null && allowance(from.id, userAddress, router) < raw
        SwapQuote(
            chain, from, to, raw, (if (useV2) v2out else v3out)!!, UNISWAP_SLIPPAGE_BPS, needsApproval, null,
            if (useV2) VENUE_V2 else VENUE_V3
        )
    }

    private suspend fun executeUniswap(seed: ByteArray, quote: SwapQuote): SwapResult = withContext(Dispatchers.IO) {
        val cfg = uniswap ?: throw UnsupportedOperationException("Swaps are not available on ${chain.label} $networkName")
        val creds = credentials(seed)
        val user = creds.address
        val txManager = RawTransactionManager(web3, creds, chainId)
        val from = quote.from
        val to = quote.to
        val amount = quote.amountInRaw

        if (isWrap(cfg, from, to)) {
            return@withContext SwapResult(emptyList(), sendCall(txManager, user, cfg.weth, amount, UniswapV3Abi.wethDeposit()))
        }
        if (isUnwrap(cfg, from, to)) {
            return@withContext SwapResult(
                emptyList(), sendCall(txManager, user, cfg.weth, BigInteger.ZERO, UniswapV3Abi.wethWithdraw(amount))
            )
        }

        if (quote.venue == VENUE_V2) {
            return@withContext executeUniswapV2(txManager, user, quote)
        }

        // Fresh on-chain quote; refuse if the price moved against the user since they confirmed.
        val (fee, out) = bestUniswapQuote(cfg, from, to, amount)
        val floor = quote.expectedOutRaw.multiply(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100))
        check(out >= floor) { "The price moved by more than 1%. Please request a new quote." }

        // ERC-20 source: approve exactly this amount to the router (never unlimited).
        val approvals = ArrayList<String>()
        val srcToken = from.id
        if (srcToken != null) {
            val current = allowance(srcToken, user, cfg.router)
            if (current < amount) {
                if (current.signum() > 0) approvals.add(sendApproval(txManager, user, srcToken, cfg.router, BigInteger.ZERO))
                approvals.add(sendApproval(txManager, user, srcToken, cfg.router, amount))
            }
        }

        val minOut = SwapGuard.minOut(out, quote.slippageBps)
        val tokenIn = srcToken ?: cfg.weth   // native in: the router wraps the attached ETH
        val tokenOut = to.id ?: cfg.weth
        val data = if (to.id == null) {
            // Native out: receive WETH inside the router, then unwrap it straight to the user.
            UniswapV3Abi.multicall(
                listOf(
                    UniswapV3Abi.exactInputSingle(tokenIn, tokenOut, fee, UniswapV3Abi.ADDRESS_THIS, amount, minOut),
                    UniswapV3Abi.unwrapWeth9(minOut, user)
                )
            )
        } else {
            UniswapV3Abi.exactInputSingle(tokenIn, tokenOut, fee, user, amount, minOut)
        }
        val value = if (srcToken == null) amount else BigInteger.ZERO
        SwapResult(approvals, sendCall(txManager, user, cfg.router, value, data))
    }

    /** The router's own WETH (v2 and v3 deployments on a testnet do not share one). Re-read if a lookup failed. */
    private val v2Weth: String by lazy {
        val router = uniswapV2?.router ?: throw UnsupportedOperationException("Uniswap v2 is not available on ${chain.label} $networkName")
        val res = web3.ethCall(
            Transaction.createEthCallTransaction(ZERO_ADDRESS, router, UniswapV2Abi.wethCall()), DefaultBlockParameterName.LATEST
        ).send()
        check(!res.hasError()) { "Could not read WETH from the Uniswap v2 router" }
        UniswapV2Abi.addressFromWord(res.value) ?: throw IllegalStateException("The Uniswap v2 router returned an invalid WETH address")
    }

    /** Best output over the direct pair and a hop through WETH. Returns (path, amountOut). */
    private fun bestV2Quote(v2: UniswapV2Config, from: SwapAsset, to: SwapAsset, raw: BigInteger): Pair<List<String>, BigInteger> {
        val weth = v2Weth
        val tokenIn = from.id ?: weth
        val tokenOut = to.id ?: weth
        check(!tokenIn.equals(tokenOut, ignoreCase = true)) { "Choose two different assets" }
        var best: Pair<List<String>, BigInteger>? = null
        for (path in UniswapV2Abi.candidatePaths(tokenIn, tokenOut, weth)) {
            val out = runCatching {
                val tx = Transaction.createEthCallTransaction(ZERO_ADDRESS, v2.router, UniswapV2Abi.getAmountsOut(raw, path))
                val res = web3.ethCall(tx, DefaultBlockParameterName.LATEST).send()
                if (res.hasError()) null else UniswapV2Abi.lastAmount(res.value, path.size)
            }.getOrNull() ?: continue // a missing pair reverts
            if (out.signum() > 0 && (best == null || out > best.second)) best = path to out
        }
        return best ?: throw IllegalStateException("No Uniswap v2 pool with liquidity for this pair on $networkName")
    }

    private fun executeUniswapV2(txManager: RawTransactionManager, user: String, quote: SwapQuote): SwapResult {
        val v2 = uniswapV2 ?: throw UnsupportedOperationException("Uniswap v2 is not available on ${chain.label} $networkName")
        val from = quote.from
        val to = quote.to
        val amount = quote.amountInRaw

        // Fresh on-chain quote; refuse if the price moved against the user since they confirmed.
        val (path, out) = bestV2Quote(v2, from, to, amount)
        val floor = quote.expectedOutRaw.multiply(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100))
        check(out >= floor) { "The price moved by more than 1%. Please request a new quote." }

        // ERC-20 source: approve exactly this amount to the v2 router (never unlimited).
        val approvals = ArrayList<String>()
        val srcToken = from.id
        if (srcToken != null) {
            val current = allowance(srcToken, user, v2.router)
            if (current < amount) {
                if (current.signum() > 0) approvals.add(sendApproval(txManager, user, srcToken, v2.router, BigInteger.ZERO))
                approvals.add(sendApproval(txManager, user, srcToken, v2.router, amount))
            }
        }

        val minOut = SwapGuard.minOut(out, quote.slippageBps)
        val deadline = nowSeconds() + UniswapV2Abi.DEADLINE_SECONDS
        val data = when {
            srcToken == null -> UniswapV2Abi.swapExactEthForTokens(minOut, path, user, deadline)
            to.id == null -> UniswapV2Abi.swapExactTokensForEth(amount, minOut, path, user, deadline)
            else -> UniswapV2Abi.swapExactTokensForTokens(amount, minOut, path, user, deadline)
        }
        val value = if (srcToken == null) amount else BigInteger.ZERO
        return SwapResult(approvals, sendCall(txManager, user, v2.router, value, data))
    }

    /** Best output over the fee tiers that have a pool with liquidity. Returns (fee tier, amountOut). */
    private fun bestUniswapQuote(cfg: UniswapV3Config, from: SwapAsset, to: SwapAsset, raw: BigInteger): Pair<Int, BigInteger> {
        val tokenIn = from.id ?: cfg.weth
        val tokenOut = to.id ?: cfg.weth
        var best: Pair<Int, BigInteger>? = null
        for (fee in UniswapV3Abi.FEE_TIERS) {
            val out = runCatching {
                val tx = Transaction.createEthCallTransaction(
                    ZERO_ADDRESS, cfg.quoter, UniswapV3Abi.quoteExactInputSingle(tokenIn, tokenOut, raw, fee)
                )
                val res = web3.ethCall(tx, DefaultBlockParameterName.LATEST).send()
                if (res.hasError()) null else UniswapV3Abi.firstWord(res.value)
            }.getOrNull() ?: continue // no pool at this tier reverts
            if (out.signum() > 0 && (best == null || out > best.second)) best = fee to out
        }
        return best ?: throw IllegalStateException(
            "No Uniswap v3 pool with liquidity for this pair on $networkName. " +
                "Pick another pair, or create a pool at app.uniswap.org."
        )
    }

    private fun sendCall(
        txManager: RawTransactionManager, from: String, to: String, value: BigInteger, data: String
    ): String {
        val gas = gasForCall(from, to, value, data)
        val gasPrice = web3.ethGasPrice().send().gasPrice
        val res = txManager.sendTransaction(gasPrice, gas, to, data, value)
        if (res.hasError()) throw IllegalStateException(res.error.message)
        return res.transactionHash
    }

    private fun ensurePriceHeld(quote: SwapQuote, route: ParaSwapApi.Route) {
        val floor = quote.expectedOutRaw.multiply(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100))
        check(route.destAmount >= floor) { "The price moved by more than 1%. Please request a new quote." }
    }

    private fun allowance(token: String, owner: String, spender: String): BigInteger {
        val fn = Function(
            "allowance",
            listOf<Type<*>>(Address(owner), Address(spender)),
            listOf<TypeReference<*>>(TypeReference.create(Uint256::class.java))
        )
        return callContract(token, fn).firstOrNull()?.value as? BigInteger
            ?: throw IllegalStateException("allowance returned nothing")
    }

    private fun sendApproval(
        txManager: RawTransactionManager, from: String, token: String, spender: String, amount: BigInteger
    ): String {
        val data = Erc20.approveData(spender, amount)
        val gas = gasForCall(from, token, BigInteger.ZERO, data)
        val gasPrice = web3.ethGasPrice().send().gasPrice
        val res = txManager.sendTransaction(gasPrice, gas, token, data, BigInteger.ZERO)
        if (res.hasError()) throw IllegalStateException(res.error.message)
        waitForReceipt(res.transactionHash)
        return res.transactionHash
    }

    private fun waitForReceipt(hash: String) {
        repeat(50) { // about 2.5 minutes
            val receipt = web3.ethGetTransactionReceipt(hash).send().transactionReceipt
            if (receipt.isPresent) {
                check(receipt.get().isStatusOK) { "The approval transaction failed" }
                return
            }
            Thread.sleep(3_000)
        }
        throw IllegalStateException("The approval is still pending. Check the explorer, then try the swap again.")
    }

    /** Gas for an arbitrary call, with a 30% buffer (swap gas varies with the route). Fails early on revert. */
    private fun gasForCall(from: String, to: String, value: BigInteger, data: String): BigInteger {
        val tx = Transaction.createFunctionCallTransaction(from, null, null, null, to, value, data)
        val res = web3.ethEstimateGas(tx).send()
        if (res.hasError()) throw IllegalStateException("Transaction would fail: ${res.error.message}")
        return res.amountUsed.multiply(BigInteger.valueOf(13)).divide(BigInteger.TEN)
    }

    private fun rawAmount(amount: BigDecimal, decimals: Int): BigInteger = try {
        amount.movePointRight(decimals).toBigIntegerExact()
    } catch (e: ArithmeticException) {
        throw IllegalArgumentException("At most $decimals decimal places are allowed")
    }

    // ---- sending ERC-20 ----

    override suspend fun estimateTokenFee(
        address: String, token: TokenBalance, to: String, amount: BigDecimal
    ): BigDecimal? = withContext(Dispatchers.IO) {
        val data = transferData(to, token, amount)
        val gas = gasForTransfer(address, token.id, data)
        val gasPrice = web3.ethGasPrice().send().gasPrice
        Convert.fromWei(BigDecimal(gas.multiply(gasPrice)), Convert.Unit.ETHER)
    }

    override suspend fun sendToken(
        seed: ByteArray, token: TokenBalance, to: String, amount: BigDecimal
    ): String = withContext(Dispatchers.IO) {
        val creds = credentials(seed)
        val data = transferData(to, token, amount)
        val gas = gasForTransfer(creds.address, token.id, data)
        val gasPrice = web3.ethGasPrice().send().gasPrice
        val txManager = RawTransactionManager(web3, creds, chainId)
        val res = txManager.sendTransaction(gasPrice, gas, token.id, data, BigInteger.ZERO)
        if (res.hasError()) throw IllegalStateException(res.error.message)
        res.transactionHash
    }

    private fun transferData(to: String, token: TokenBalance, amount: BigDecimal): String {
        require(WalletUtils.isValidAddress(to)) { "Invalid address" }
        require(!to.equals(token.id, ignoreCase = true)) {
            "Don't send tokens to the token's own contract address (they would be lost)"
        }
        val raw = try {
            amount.movePointRight(token.decimals).toBigIntegerExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("${token.symbol} allows at most ${token.decimals} decimal places")
        }
        return Erc20.transferData(to, raw)
    }

    /** Gas estimate + 20% buffer. Fails early (with the node's reason) if the transfer would revert. */
    private fun gasForTransfer(from: String, token: String, data: String): BigInteger {
        val tx = Transaction.createFunctionCallTransaction(from, null, null, null, token, data)
        val res = web3.ethEstimateGas(tx).send()
        if (res.hasError()) throw IllegalStateException("Transfer would fail: ${res.error.message}")
        return res.amountUsed.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN)
    }

    /**
     * Gas for a native transfer. Plain transfers return 21000 and are used as-is; anything higher
     * (Arbitrum includes the L1 data cost in gas, contract recipients need more) gets a 20% buffer.
     */
    private fun gasForNative(from: String, to: String, wei: BigInteger): BigInteger {
        val tx = Transaction.createEtherTransaction(from, null, null, null, to, wei)
        val res = web3.ethEstimateGas(tx).send()
        if (res.hasError()) throw IllegalStateException("Transfer would fail: ${res.error.message}")
        val g = res.amountUsed
        return if (g <= BigInteger.valueOf(21_000)) g else g.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN)
    }

    // ---- sending native coin ----

    override suspend fun estimateFee(address: String, to: String, amount: BigDecimal): BigDecimal? =
        withContext(Dispatchers.IO) {
            val wei = Convert.toWei(amount, Convert.Unit.ETHER).toBigIntegerExact()
            val gas = gasForNative(address, to, wei)
            val gasPrice = web3.ethGasPrice().send().gasPrice
            Convert.fromWei(BigDecimal(gas.multiply(gasPrice)), Convert.Unit.ETHER)
        }

    override fun isValidAddress(address: String): Boolean = WalletUtils.isValidAddress(address)

    override suspend fun send(seed: ByteArray, to: String, amount: BigDecimal): String =
        withContext(Dispatchers.IO) {
            val creds = credentials(seed)
            val wei = Convert.toWei(amount, Convert.Unit.ETHER).toBigIntegerExact()
            val gasPrice = web3.ethGasPrice().send().gasPrice
            val txManager = RawTransactionManager(web3, creds, chainId)
            val gas = gasForNative(creds.address, to, wei)
            val res = txManager.sendTransaction(gasPrice, gas, to, "", wei)
            if (res.hasError()) throw IllegalStateException(res.error.message)
            res.transactionHash
        }

    companion object {
        private const val SWAP_SLIPPAGE_BPS = 50 // 0.5%
        private const val UNISWAP_SLIPPAGE_BPS = 100 // 1%: testnet pools are thin
        private const val VENUE_V3 = "Uniswap v3"
        private const val VENUE_V2 = "Uniswap v2"
        private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

        private val DECIMALS_FN = Function(
            "decimals", emptyList<Type<*>>(),
            listOf<TypeReference<*>>(TypeReference.create(Uint8::class.java))
        )
        private val SYMBOL_FN = Function(
            "symbol", emptyList<Type<*>>(),
            listOf<TypeReference<*>>(TypeReference.create(Utf8String::class.java))
        )

        // Sepolia test tokens (double-check on a block explorer before relying on them)
        val SEPOLIA_TOKENS = listOf(
            TokenInfo("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", "USDC", 6),
            TokenInfo("0x779877A7B0D9E8603169DdbD7836e478b4624789", "LINK", 18),
            TokenInfo("0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14", "WETH", 18)
        )

        // Ethereum mainnet. Verify each address on etherscan.io before trusting a balance.
        val MAINNET_TOKENS = listOf(
            TokenInfo("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", 6),
            TokenInfo("0xdAC17F958D2ee523a2206206994597C13D831ec7", "USDT", 6),
            TokenInfo("0x514910771AF9Ca656af840dff83E8264EcF986CA", "LINK", 18),
            TokenInfo("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "WETH", 18),
            TokenInfo("0x6B175474E89094C44Da98b954EedeAC495271d0F", "DAI", 18),
            TokenInfo("0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599", "WBTC", 8),
            TokenInfo("0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984", "UNI", 18),
            TokenInfo("0x7Fc66500c84A76Ad7e9c93437bFc5Ac33E2DDaE9", "AAVE", 18)
        )
    }
}
