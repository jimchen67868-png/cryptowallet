package com.example.wallet.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.wallet.AppContainer
import com.example.wallet.chain.AcrossRoutes
import com.example.wallet.chain.ArrivalCandidate
import com.example.wallet.chain.BridgeSwapCoordinator
import com.example.wallet.chain.BridgeSwapJob
import com.example.wallet.chain.BridgeSwapLogic
import com.example.wallet.chain.BridgeSwapStore
import com.example.wallet.chain.Chain
import com.example.wallet.chain.JobStage
import com.example.wallet.chain.RelayTestnet
import com.example.wallet.chain.TestnetRoutes
import com.example.wallet.chain.ChainProvider
import com.example.wallet.chain.BridgeQuote
import com.example.wallet.chain.SwapAsset
import com.example.wallet.chain.SwapGuard
import com.example.wallet.chain.SwapQuote
import com.example.wallet.chain.TokenBalance
import com.example.wallet.chain.TxRecord
import com.example.wallet.crypto.SeedGenerator
import com.example.wallet.data.AppConfig
import com.example.wallet.data.AppSettings
import com.example.wallet.data.CoinIds
import com.example.wallet.data.Network
import com.example.wallet.data.PriceService
import com.example.wallet.data.WalletRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

data class Account(
    val chain: Chain,
    val address: String,
    val balance: BigDecimal? = null,
    val error: Boolean = false
)

data class PendingSend(
    val chain: Chain,
    val token: TokenBalance?,
    val to: String,
    val amount: String,
    val fee: BigDecimal?
)

/** "Then swap on arrival" chosen together with a bridge: the swap target and an indicative quote taken up front. */
class ComboPlan(val to: SwapAsset, val preview: SwapQuote, val previewAsset: SwapAsset)

/** The swap leg of a bridge job, quoted fresh on the destination chain and waiting for the user's confirmation. */
class ArrivalSwap(val job: BridgeSwapJob, val quote: SwapQuote, val driftWarning: String?, val reservedRaw: BigInteger)

sealed interface HistoryUi {
    object Loading : HistoryUi
    class Loaded(val items: List<TxRecord>) : HistoryUi
    class Failed(val message: String) : HistoryUi
}

sealed interface Screen {
    object Welcome : Screen
    object ShowBackup : Screen
    object ConfirmBackup : Screen
    object Import : Screen
    object Locked : Screen
    object Home : Screen
    data class Receive(val chain: Chain) : Screen
    data class Send(val chain: Chain, val token: TokenBalance? = null) : Screen
    data class History(val chain: Chain) : Screen
    data class Swap(val chain: Chain, val from: TokenBalance? = null) : Screen
    data class Bridge(val chain: Chain, val from: TokenBalance? = null) : Screen
    object NetworkSettings : Screen
    object MainnetWarning : Screen
}

class WalletViewModel(
    private val repo: WalletRepository,
    private val providersFor: (Network) -> List<ChainProvider>,
    private val settings: AppSettings,
    private val priceService: PriceService,
    bridgeStore: BridgeSwapStore
) : ViewModel() {

    var screen by mutableStateOf<Screen>(if (repo.hasWallet()) Screen.Locked else Screen.Welcome)
        private set
    var pendingWords by mutableStateOf<List<String>>(emptyList())
        private set
    var confirmIndices by mutableStateOf<List<Int>>(emptyList())
        private set
    var accounts by mutableStateOf<List<Account>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var pendingSend by mutableStateOf<PendingSend?>(null)
        private set
    var tokens by mutableStateOf<Map<Chain, List<TokenBalance>>>(emptyMap())
        private set
    var pendingSwap by mutableStateOf<SwapQuote?>(null)
        private set
    var dryRunReport by mutableStateOf<String?>(null)
        private set
    var pendingBridge by mutableStateOf<BridgeQuote?>(null)
        private set
    var pendingCombo by mutableStateOf<ComboPlan?>(null)
        private set
    var bridgeJobs by mutableStateOf<List<BridgeSwapJob>>(emptyList())
        private set
    var pendingArrival by mutableStateOf<ArrivalSwap?>(null)
        private set
    var history by mutableStateOf<HistoryUi>(HistoryUi.Loading)
        private set
    /** CoinGecko coin id -> USD price. */
    var prices by mutableStateOf<Map<String, Double>>(emptyMap())
        private set

    var network by mutableStateOf(settings.network)
        private set

    /**
     * Safety net for every background job: an unexpected exception becomes a message (and a saved trace) instead of
     * closing the app. Cancellation is not an error and never reaches this.
     */
    private val guard = CoroutineExceptionHandler { _, e ->
        sending = false
        com.example.wallet.data.CrashReporter.recordHandled(e)
        message = "Something went wrong (${e::class.java.simpleName}): ${e.message.orEmpty().take(160)}"
    }

    val providers: List<ChainProvider> get() = providersFor(network)

    val isTestnet: Boolean get() = providers.any { it.isTestnet }

    /** The seed lives only in memory while unlocked and is wiped on lock(). */
    private var seed: ByteArray? = null

    val canGoBack: Boolean
        get() = screen is Screen.Receive || screen is Screen.Send || screen is Screen.History || screen is Screen.Swap || screen is Screen.Bridge ||
            screen == Screen.NetworkSettings || screen == Screen.MainnetWarning ||
            screen == Screen.ConfirmBackup || screen == Screen.ShowBackup || screen == Screen.Import

    fun clearMessage() { message = null }
    fun goTo(s: Screen) { screen = s }

    fun back() {
        screen = when (screen) {
            is Screen.Receive, is Screen.Send, is Screen.History, is Screen.Swap, is Screen.Bridge -> Screen.Home
            Screen.ConfirmBackup -> Screen.ShowBackup
            Screen.NetworkSettings -> Screen.Home
            Screen.MainnetWarning -> Screen.NetworkSettings
            else -> { pendingWords = emptyList(); Screen.Welcome }
        }
    }

    // ---- create / backup ----
    fun startCreate() {
        pendingWords = repo.generateNewMnemonic()
        screen = Screen.ShowBackup
    }

    fun goToConfirm() {
        confirmIndices = (0 until pendingWords.size).shuffled().take(3).sorted()
        screen = Screen.ConfirmBackup
    }

    fun confirmBackup(activity: FragmentActivity, answers: List<String>) {
        val ok = confirmIndices.zip(answers).all { (i, a) -> pendingWords[i] == a.trim().lowercase() }
        if (!ok) { message = "Those words don't match. Check your backup."; return }
        viewModelScope.launch(guard) {
            val words = pendingWords
            if (runCatching { repo.saveWallet(activity, words) }.getOrDefault(false)) {
                pendingWords = emptyList()
                startSession(words)
            } else {
                message = "Could not save the wallet securely."
            }
        }
    }

    // ---- import / unlock / reset ----
    fun importWallet(activity: FragmentActivity, phrase: String) {
        viewModelScope.launch(guard) {
            val words = runCatching { repo.importWallet(activity, phrase) }.getOrNull()
            if (words == null) message = "Invalid phrase or authentication cancelled."
            else startSession(words)
        }
    }

    fun unlock(activity: FragmentActivity) {
        viewModelScope.launch(guard) {
            val words = runCatching { repo.unlockMnemonic(activity) }.getOrElse {
                message = "Could not unlock. If your device security changed, reset and restore with your phrase."
                return@launch
            }
            if (words == null) message = "Authentication cancelled." else startSession(words)
        }
    }

    fun resetWallet() {
        lock(force = true)
        repo.wipe()
        screen = Screen.Welcome
    }

    // ---- session ----
    private fun startSession(words: List<String>) {
        val s = SeedGenerator.toSeed(words)
        seed = s
        runCatching {
            accounts = providers.map { Account(it.chain, it.deriveAddress(s)) }
        }.onFailure {
            message = "Key derivation failed: ${it.message}"
            lock(force = true)
            return
        }
        screen = Screen.Home
        refreshBalances()
        resumeBridgeJobs()
    }

    /** Wipes the seed from memory. Called when the app goes to the background. */
    fun lock(force: Boolean = false) {
        if (seed == null && !force) return
        seed?.fill(0)
        seed = null
        accounts = emptyList()
        pendingSend = null
        pendingSwap = null
        pendingBridge = null
        pendingCombo = null
        pendingArrival = null
        stopWatching()
        bridgeJobs = emptyList()
        tokens = emptyMap()
        history = HistoryUi.Loading
        if (repo.hasWallet()) screen = Screen.Locked
    }

    fun refreshBalances() {
        refreshPrices()
        accounts.forEach { acc ->
            val provider = providers.first { it.chain == acc.chain }
            viewModelScope.launch(guard) {
                val result = runCatching { provider.getBalance(acc.address) }
                accounts = accounts.map {
                    if (it.chain == acc.chain) it.copy(balance = result.getOrNull(), error = result.isFailure) else it
                }
            }
            viewModelScope.launch(guard) {
                runCatching { provider.getTokenBalances(acc.address) }.onSuccess { list ->
                    if (accounts.any { it.chain == acc.chain }) tokens = tokens + (acc.chain to list)
                }
            }
        }
    }

    // ---- network ----
    /** Re-derives all accounts for [target] from the in-memory seed. Requires the wallet to be unlocked. */
    fun switchNetwork(target: Network) {
        val s = seed ?: return
        if (target == network) { screen = Screen.Home; return }
        network = target
        settings.network = target
        pendingSend = null
        pendingCombo = null
        pendingArrival = null
        tokens = emptyMap()
        history = HistoryUi.Loading
        runCatching {
            accounts = providers.map { Account(it.chain, it.deriveAddress(s)) }
        }.onFailure {
            message = "Key derivation failed: ${it.message}"
            lock(force = true)
            return
        }
        screen = Screen.Home
        message = if (target == Network.MAINNET) "Mainnet enabled. These are real funds." else "Switched to testnet."
        refreshBalances()
        resumeBridgeJobs()
    }

    /** Mainnet safety limit. Returns an error message, or null if the amount is allowed. */
    private fun limitError(chain: Chain, tokenId: String?, amount: BigDecimal): String? {
        if (network != Network.MAINNET) return null
        val unit = priceOf(chain, tokenId)
            ?: return "The USD value of this asset can't be verified right now, so the mainnet safety " +
                "limit can't be applied. Try again shortly. Unpriced or custom tokens can't be sent on mainnet in this build."
        val usd = amount.toDouble() * unit
        if (usd > AppConfig.MAINNET_TX_LIMIT_USD) {
            return String.format(
                java.util.Locale.US,
                "Over the mainnet safety limit of $%.0f per transaction (this one is about $%.2f).",
                AppConfig.MAINNET_TX_LIMIT_USD, usd
            )
        }
        return null
    }

    // ---- prices ----
    private fun refreshPrices() {
        viewModelScope.launch(guard) {
            runCatching { priceService.fetchUsd() }.onSuccess { if (it.isNotEmpty()) prices = it }
        }
    }

    /** USD price of one unit of the native coin ([tokenId] = null) or of a token; null if unknown. */
    fun priceOf(chain: Chain, tokenId: String? = null): Double? =
        CoinIds.forAsset(chain, tokenId)?.let { prices[it] }

    /** Sum of all holdings that have a known price, or null if none do. */
    fun portfolioUsd(): Double? {
        var sum = 0.0
        var any = false
        for (acc in accounts) {
            val p = priceOf(acc.chain)
            val b = acc.balance
            if (p != null && b != null) { sum += b.toDouble() * p; any = true }
            for (t in tokens[acc.chain].orEmpty()) {
                val tp = priceOf(acc.chain, t.id) ?: continue
                sum += t.balance.toDouble() * tp
                any = true
            }
        }
        return if (any) sum else null
    }

    // ---- swaps (EVM mainnet, via ParaSwap) ----
    fun requestSwapQuote(chain: Chain, from: TokenBalance?, to: SwapAsset, amountText: String) {
        val provider = providers.first { it.chain == chain }
        val acc = accounts.firstOrNull { it.chain == chain } ?: return
        if (!provider.supportsSwap) {
            message = "Swaps are not available on this network."; return
        }
        val amount = com.example.wallet.data.AmountParser.parse(amountText)
        if (amount == null || amount.signum() <= 0) { message = "Enter a valid amount"; return }
        val fromAsset = SwapAsset(from?.id, from?.symbol ?: chain.symbol, from?.decimals ?: provider.nativeDecimals)
        val balance = from?.balance ?: acc.balance
        if (balance != null && amount > balance) { message = "Amount exceeds your ${fromAsset.symbol} balance"; return }
        limitError(chain, from?.id, amount)?.let { message = it; return }
        sending = true
        viewModelScope.launch(guard) {
            runCatching { provider.quoteSwap(acc.address, fromAsset, to, amount) }
                .onSuccess { pendingSwap = it }
                .onFailure { message = it.message ?: "Could not get a quote" }
            sending = false
        }
    }

    fun cancelSwap() { pendingSwap = null }

    fun confirmSwap(activity: FragmentActivity) {
        val q = pendingSwap ?: return
        pendingSwap = null
        val provider = providers.first { it.chain == q.chain }
        val s = seed?.copyOf() ?: return
        viewModelScope.launch(guard) {
            if (network == Network.MAINNET && !repo.confirmIdentity(activity, "Confirm mainnet swap")) {
                message = "Authentication cancelled. Nothing was sent."
                s.fill(0)
                return@launch
            }
            // Re-apply the USD cap right before signing.
            limitError(q.chain, q.from.id, q.amountIn)?.let { message = it; s.fill(0); return@launch }
            sending = true
            val r = runCatching { provider.executeSwap(s, q) }
            s.fill(0)
            sending = false
            r.onSuccess {
                message = "Swap submitted. Tx: ${it.swapTxId}"
                screen = Screen.Home
                refreshBalances()
            }.onFailure { message = "Swap failed: ${it.message}" }
        }
    }

    // ---- moving funds to another network (Across, EVM mainnets), optionally swapping on arrival ----

    /** The provider for [chain] on the network the app is on now (bridge jobs are only handled on their own network). */
    private fun netProvider(chain: Chain): ChainProvider = providers.first { it.chain == chain }

    /** Assets the bridged funds can arrive as: ETH on the Relay test networks, LI.FI/Across routes on mainnet. */
    private fun bridgeCandidates(from: SwapAsset, chain: Chain, dest: Chain): List<SwapAsset> =
        if (isTestnet) TestnetRoutes.candidateAssets(from, chain, dest)
        else BridgeSwapLogic.candidateAssets(from, routeOf(chain), routeOf(dest))

    private fun visibleJobs(): List<BridgeSwapJob> = coordinator.jobs().filter { it.testnet == isTestnet }

    private fun routeOf(chain: Chain) = AcrossRoutes.MAINNET.first { it.chain == chain }

    private fun toRaw(amount: BigDecimal, decimals: Int): BigInteger = amount.movePointRight(decimals).toBigInteger()

    /** Balances of [assets] on [chain], in raw units. An entry is null if it could not be read. */
    private suspend fun destBalances(chain: Chain, assets: List<SwapAsset>): List<BigInteger?> {
        val address = accounts.firstOrNull { it.chain == chain }?.address ?: return assets.map { null }
        val p = netProvider(chain)
        val native = runCatching { p.getBalance(address) }.getOrNull()
        val tokenList = runCatching { p.getTokenBalances(address) }.getOrNull()
        return assets.map { a ->
            if (a.id == null) native?.let { toRaw(it, a.decimals) }
            else tokenList?.let { list ->
                toRaw(list.firstOrNull { it.id.equals(a.id, ignoreCase = true) }?.balance ?: BigDecimal.ZERO, a.decimals)
            }
        }
    }

    private val coordinator = BridgeSwapCoordinator(
        store = bridgeStore,
        fillStatus = { job -> netProvider(job.originChain).bridgeFillStatus(job.trackingId ?: job.depositTxId) },
        balances = { job -> destBalances(job.destChain, job.candidates.map { it.asset }) }
    )

    private val watchers = HashMap<String, Job>()

    private fun stopWatching() {
        watchers.values.forEach { it.cancel() }
        watchers.clear()
    }

    /** Reloads saved jobs and resumes watching the ones still waiting. Mainnet only, and only while unlocked. */
    private fun resumeBridgeJobs() {
        stopWatching()
        if (seed == null) { bridgeJobs = emptyList(); return }
        coordinator.recoverInterrupted()
        bridgeJobs = visibleJobs()
        bridgeJobs.filter { it.stage == JobStage.WAITING_FILL }.forEach { startWatching(it.id) }
    }

    private fun startWatching(jobId: String) {
        if (watchers[jobId]?.isActive == true) return
        watchers[jobId] = viewModelScope.launch(guard) {
            while (true) {
                if (seed == null) return@launch
                val job = bridgeJobs.firstOrNull { it.id == jobId } ?: return@launch
                if (job.testnet != isTestnet || job.stage != JobStage.WAITING_FILL) return@launch
                val updated = coordinator.poll(job)
                bridgeJobs = visibleJobs()
                when (updated.stage) {
                    JobStage.ARRIVED -> { message = "Funds arrived on ${updated.destChain.label}. Review the swap on the home screen."; return@launch }
                    JobStage.BRIDGE_FAILED -> { message = "A bridge did not complete. See the home screen."; return@launch }
                    JobStage.TIMED_OUT -> return@launch
                    else -> {}
                }
                val elapsed = System.currentTimeMillis() / 1000 - job.startedAtSec
                delay(if (elapsed > 600) 30_000L else 15_000L)
            }
        }
    }

    /** Curated assets on [dest] that the bridged funds can be swapped into (never the bridged asset itself). */
    fun swapTargetsAfterBridge(chain: Chain, from: TokenBalance?, dest: Chain): List<SwapAsset> = try {
        val fromAsset = SwapAsset(from?.id, from?.symbol ?: chain.symbol, from?.decimals ?: 18)
        // THORChain routes (Litecoin, Tron) deliver the native coin straight to your address: no swap-on-arrival step.
        val cands = if (netProvider(chain).bridgeProviderNote != null || !dest.isEvm || from == null || chain == Chain.BNB || dest == Chain.BNB) emptyList()
        else bridgeCandidates(fromAsset, chain, dest)
        if (cands.isEmpty()) emptyList()
        else {
            val keys = cands.map { SwapGuard.tokenKey(it) }.toSet()
            netProvider(dest).swapAssets().filter { it.curated && SwapGuard.tokenKey(it) !in keys }
        }
    } catch (e: Exception) {
        // Called while the screen is drawn: an unknown route must give "no options", never a crash.
        com.example.wallet.data.CrashReporter.recordHandled(e)
        emptyList()
    }

    fun requestBridgeQuote(
        chain: Chain, from: TokenBalance?, dest: Chain, amountText: String,
        thenSwapTo: SwapAsset? = null
    ) {
        val provider = providers.first { it.chain == chain }
        val acc = accounts.firstOrNull { it.chain == chain } ?: return
        if (!provider.supportsBridge) { message = "Moving funds to another network is not available here."; return }
        val amount = com.example.wallet.data.AmountParser.parse(amountText)
        if (amount == null || amount.signum() <= 0) { message = "Enter a valid amount"; return }
        val fromAsset = SwapAsset(from?.id, from?.symbol ?: chain.symbol, from?.decimals ?: provider.nativeDecimals)
        val balance = from?.balance ?: acc.balance
        if (balance != null && amount > balance) { message = "Amount exceeds your ${fromAsset.symbol} balance"; return }
        limitError(chain, from?.id, amount)?.let { message = it; return }
        val destAddress = accounts.firstOrNull { it.chain == dest }?.address
        if (destAddress == null) { message = "No ${dest.label} address in this wallet"; return }
        sending = true
        viewModelScope.launch(guard) {
            runCatching {
                val q = provider.quoteBridgeTo(acc.address, fromAsset, dest, destAddress, amount)
                q to thenSwapTo?.let { buildCombo(q, it, acc.address) }
            }
                .onSuccess { (q, combo) -> pendingBridge = q; pendingCombo = combo }
                .onFailure { message = it.message ?: "Could not get a bridge quote" }
            sending = false
        }
    }

    /**
     * Checks that "swap on arrival" can actually work before anything is sent, and takes an indicative quote.
     * Throws with a user-facing message if not.
     */
    private suspend fun buildCombo(q: BridgeQuote, to: SwapAsset, user: String): ComboPlan {
        val cands = bridgeCandidates(q.from, q.chain, q.destChain)
        require(cands.isNotEmpty()) { "Can't plan a swap for ${q.from.symbol} on ${q.destChain.label}. Bridge it first, then swap." }
        val toKey = SwapGuard.tokenKey(to)
        require(cands.none { SwapGuard.tokenKey(it) == toKey }) { "Choose a different asset to swap into." }
        require(to.curated) { "Only built-in tokens can be used for swap on arrival." }

        val dest = netProvider(q.destChain)
        // The swap on arrival needs native coin for fees. Native ETH bridged to an ETH-gas chain brings its own.
        val bringsOwnGas = q.from.id == null && (isTestnet || routeOf(q.destChain).nativeIsEth)
        if (!bringsOwnGas) {
            val reserve = BigDecimal(BridgeSwapLogic.gasReserveRaw(q.destChain, isTestnet)).movePointLeft(dest.nativeDecimals)
            val have = dest.getBalance(user)
            require(have >= reserve) {
                "You have no ${q.destChain.symbol} on ${q.destChain.label} to pay for the swap there. " +
                    "Bridge without the swap, or add a little ${q.destChain.symbol} on ${q.destChain.label} first."
            }
        }
        val previewAsset = cands.first()
        val reserveRaw = if (previewAsset.id == null) BridgeSwapLogic.gasReserveRaw(q.destChain, isTestnet) else BigInteger.ZERO
        val previewRaw = q.amountOutRaw.subtract(reserveRaw)
        require(previewRaw.signum() > 0) { "This amount is too small to cover the swap on ${q.destChain.label}." }
        val preview = dest.quoteSwap(user, previewAsset, to, BigDecimal(previewRaw).movePointLeft(previewAsset.decimals))
        return ComboPlan(to, preview, previewAsset)
    }

    fun cancelBridge() { pendingBridge = null; pendingCombo = null }

    /** Re-checks the pending quote and shows what would be sent, without signing or sending anything. */
    fun dryRunBridge() {
        val q = pendingBridge ?: return
        val addr = accounts.firstOrNull { it.chain == q.chain }?.address ?: return
        sending = true
        viewModelScope.launch(guard) {
            dryRunReport = runCatching { netProvider(q.chain).dryRunBridge(addr, q) }
                .getOrElse { "DRY RUN FAILED. Nothing was sent.\n\n${it.message ?: it.javaClass.simpleName}" }
            sending = false
        }
    }

    fun closeDryRun() { dryRunReport = null }

    /** Mints the network's free test token (testnets), e.g. CCIP-BnM, so the CCIP bridge can be tried. */
    fun getFaucetToken(activity: FragmentActivity, chain: Chain) {
        val provider = providers.first { it.chain == chain }
        val symbol = provider.faucetTokenSymbol ?: return
        val s = seed?.copyOf() ?: return
        viewModelScope.launch(guard) {
            if (!repo.confirmIdentity(activity, "Get free test $symbol")) {
                message = "Authentication cancelled. Nothing was sent."
                s.fill(0)
                return@launch
            }
            sending = true
            val r = runCatching { provider.requestFaucetToken(s) }
            s.fill(0)
            sending = false
            r.onSuccess {
                message = "Requested free test $symbol on ${chain.label}. Tx: $it. It shows up after the transaction confirms: tap refresh in a minute."
                refreshBalances()
            }.onFailure { message = "Could not get test $symbol: ${it.message}" }
        }
    }

    fun confirmBridge(activity: FragmentActivity) {
        val q = pendingBridge ?: return
        val combo = pendingCombo
        pendingBridge = null
        pendingCombo = null
        val provider = providers.first { it.chain == q.chain }
        val s = seed?.copyOf() ?: return
        viewModelScope.launch(guard) {
            if (!repo.confirmIdentity(activity, "Confirm bridge")) {
                message = "Authentication cancelled. Nothing was sent."
                s.fill(0)
                return@launch
            }
            limitError(q.chain, q.from.id, q.amountIn)?.let { message = it; s.fill(0); return@launch }

            // Snapshot the destination balances BEFORE sending: arrival is detected as a rise above this baseline.
            val baseline: List<ArrivalCandidate>? = if (combo == null) null else {
                val cands = bridgeCandidates(q.from, q.chain, q.destChain)
                val raw = destBalances(q.destChain, cands)
                if (cands.isEmpty() || raw.any { it == null }) {
                    message = "Could not read your balance on ${q.destChain.label}. Nothing was sent."
                    s.fill(0)
                    return@launch
                }
                cands.zip(raw.map { it!! }) { a, b -> ArrivalCandidate(a, b) }
            }

            sending = true
            val r = runCatching { provider.executeBridge(s, q) }
            s.fill(0)
            sending = false
            r.onSuccess {
                if (combo != null && baseline != null) {
                    val job = BridgeSwapJob(
                        id = UUID.randomUUID().toString(),
                        originChain = q.chain, destChain = q.destChain,
                        depositTxId = it.swapTxId,
                        startedAtSec = System.currentTimeMillis() / 1000,
                        expectedOutRaw = q.amountOutRaw,
                        candidates = baseline,
                        target = combo.to,
                        previewInRaw = combo.preview.amountInRaw,
                        previewOutRaw = combo.preview.expectedOutRaw,
                        testnet = isTestnet,
                        trackingId = it.trackingId
                    )
                    coordinator.start(job)
                    bridgeJobs = visibleJobs()
                    startWatching(job.id)
                    message = "Bridge submitted. Tx: ${it.swapTxId}. I'll watch for your funds on ${q.destChain.label} and ask you to confirm the swap."
                } else {
                    message = "Bridge submitted. Tx: ${it.swapTxId}. Funds arrive on ${q.destChain.label} in a few minutes: open that card and tap Refresh."
                }
                screen = Screen.Home
                refreshBalances()
            }.onFailure { message = "Bridge failed: ${it.message}" }
        }
    }

    // ---- swap leg of a bridge job ----

    /** Quotes the swap on the destination chain from the real arrived amount, then asks the user to confirm. */
    fun reviewArrivalSwap(jobId: String) {
        val job = bridgeJobs.firstOrNull { it.id == jobId } ?: return
        val asset = job.arrivedAsset
        if (job.stage != JobStage.ARRIVED || asset == null || job.testnet != isTestnet) return
        val addr = accounts.firstOrNull { it.chain == job.destChain }?.address ?: return
        sending = true
        viewModelScope.launch(guard) {
            runCatching {
                val current = destBalances(job.destChain, listOf(asset)).firstOrNull()
                    ?: error("Could not read your balance on ${job.destChain.label}. Try again.")
                val reserve = if (asset.id == null) BridgeSwapLogic.gasReserveRaw(job.destChain, job.testnet) else BigInteger.ZERO
                val amountRaw = BridgeSwapLogic.swapAmountRaw(job, asset, current, reserve)
                    ?: error("The bridged funds are not on ${job.destChain.label} any more (they may already have been swapped). Check History.")
                val amount = BigDecimal(amountRaw).movePointLeft(asset.decimals)
                limitError(job.destChain, asset.id, amount)?.let { error(it) }
                val q = netProvider(job.destChain).quoteSwap(addr, asset, job.target, amount)
                val drift = BridgeSwapLogic.priceDriftBps(job.previewInRaw, job.previewOutRaw, q.amountInRaw, q.expectedOutRaw)
                ArrivalSwap(job, q, BridgeSwapLogic.driftWarning(drift), reserve)
            }
                .onSuccess { pendingArrival = it }
                .onFailure { message = it.message ?: "Could not quote the swap" }
            sending = false
        }
    }

    fun cancelArrivalSwap() { pendingArrival = null }

    fun confirmArrivalSwap(activity: FragmentActivity) {
        val p = pendingArrival ?: return
        pendingArrival = null
        val s = seed?.copyOf() ?: return
        viewModelScope.launch(guard) {
            // A fresh fingerprint/PIN for this leg, separate from the one that approved the bridge.
            if (!repo.confirmIdentity(activity, "Confirm swap of bridged funds")) {
                message = "Authentication cancelled. Nothing was swapped. Your funds are still on ${p.job.destChain.label}."
                s.fill(0)
                return@launch
            }
            limitError(p.job.destChain, p.quote.from.id, p.quote.amountIn)?.let { message = it; s.fill(0); return@launch }
            coordinator.markSwapping(p.job)
            bridgeJobs = visibleJobs()
            sending = true
            val r = runCatching { netProvider(p.job.destChain).executeSwap(s, p.quote) }
            s.fill(0)
            sending = false
            r.onSuccess {
                coordinator.markDone(p.job, it.swapTxId)
                bridgeJobs = visibleJobs()
                message = "Swap submitted. Tx: ${it.swapTxId}"
                refreshBalances()
            }.onFailure {
                coordinator.backToArrived(p.job)
                bridgeJobs = visibleJobs()
                message = "Swap failed: ${it.message}. Your funds are still on ${p.job.destChain.label}; tap Review swap to try again."
            }
        }
    }

    fun recheckBridgeJob(jobId: String) {
        val job = bridgeJobs.firstOrNull { it.id == jobId } ?: return
        viewModelScope.launch(guard) {
            coordinator.poll(job)
            bridgeJobs = visibleJobs()
        }
    }

    /** Stops tracking and forgets the job. Funds already on the destination chain stay where they are. */
    fun dismissBridgeJob(jobId: String) {
        val job = bridgeJobs.firstOrNull { it.id == jobId } ?: return
        if (job.stage == JobStage.SWAPPING) return
        watchers.remove(jobId)?.cancel()
        coordinator.dismiss(job)
        bridgeJobs = visibleJobs()
    }

    // ---- history ----
    fun openHistory(chain: Chain) {
        screen = Screen.History(chain)
        loadHistory(chain)
    }

    fun loadHistory(chain: Chain) {
        val acc = accounts.firstOrNull { it.chain == chain } ?: return
        val provider = providers.first { it.chain == chain }
        history = HistoryUi.Loading
        viewModelScope.launch(guard) {
            val r = runCatching { provider.getHistory(acc.address) }
            if ((screen as? Screen.History)?.chain != chain) return@launch // user navigated away
            history = r.fold(
                onSuccess = { list -> HistoryUi.Loaded(list.sortedByDescending { it.timeSec ?: Long.MAX_VALUE }) },
                onFailure = { HistoryUi.Failed(it.message ?: "Could not load history") }
            )
        }
    }

    fun explorerUrl(chain: Chain, txId: String): String =
        providers.first { it.chain == chain }.explorerUrl(txId)

    // ---- tokens ----
    fun addToken(chain: Chain, contract: String) {
        val provider = providers.first { it.chain == chain }
        viewModelScope.launch(guard) {
            runCatching { provider.addToken(contract.trim()) }
                .onSuccess { message = "Token added"; refreshBalances() }
                .onFailure { message = it.message ?: "Could not add token" }
        }
    }

    fun removeToken(chain: Chain, id: String) {
        providers.first { it.chain == chain }.removeToken(id)
        tokens = tokens + (chain to tokens[chain].orEmpty().filterNot { it.id == id })
    }

    // ---- send ----
    /** Validates, estimates the fee, then shows the confirmation dialog. */
    fun reviewSend(chain: Chain, token: TokenBalance?, to: String, amountText: String) {
        val provider = providers.first { it.chain == chain }
        val acc = accounts.firstOrNull { it.chain == chain } ?: return
        val amount = com.example.wallet.data.AmountParser.parse(amountText)
        val target = to.trim()
        if (amount == null || amount.signum() <= 0) { message = "Enter a valid amount"; return }
        if (!provider.isValidAddress(target)) { message = "Invalid ${chain.symbol} address"; return }
        if (token != null && amount > token.balance) {
            message = "Amount exceeds your ${token.symbol} balance"; return
        }
        limitError(chain, token?.id, amount)?.let { message = it; return }
        sending = true
        viewModelScope.launch(guard) {
            runCatching {
                if (token != null) provider.estimateTokenFee(acc.address, token, target, amount)
                else provider.estimateFee(acc.address, target, amount)
            }
                .onSuccess { pendingSend = PendingSend(chain, token, target, amountText.trim(), it) }
                .onFailure { message = it.message ?: "Could not estimate fee" }
            sending = false
        }
    }

    fun cancelSend() { pendingSend = null }

    fun confirmSend(activity: FragmentActivity) {
        val p = pendingSend ?: return
        pendingSend = null
        if (network != Network.MAINNET) {
            send(p.chain, p.token, p.to, p.amount)
            return
        }
        // Mainnet: require fingerprint/PIN again right before signing.
        viewModelScope.launch(guard) {
            if (!repo.confirmIdentity(activity, "Confirm mainnet transaction")) {
                message = "Authentication cancelled. Nothing was sent."
                return@launch
            }
            send(p.chain, p.token, p.to, p.amount)
        }
    }

    private fun send(chain: Chain, token: TokenBalance?, to: String, amountText: String) {
        val provider = providers.first { it.chain == chain }
        val s = seed?.copyOf() ?: return
        val amount = com.example.wallet.data.AmountParser.parse(amountText)
        val target = to.trim()
        if (amount == null || amount.signum() <= 0) { message = "Enter a valid amount"; s.fill(0); return }
        if (!provider.isValidAddress(target)) { message = "Invalid ${chain.symbol} address"; s.fill(0); return }
        limitError(chain, token?.id, amount)?.let { message = it; s.fill(0); return }
        sending = true
        viewModelScope.launch(guard) {
            val r = runCatching {
                if (token != null) provider.sendToken(s, token, target, amount)
                else provider.send(s, target, amount)
            }
            s.fill(0)
            sending = false
            r.onSuccess {
                message = "Sent. Tx: $it"
                screen = Screen.Home
                refreshBalances()
            }.onFailure { message = "Send failed: ${it.message}" }
        }
    }
}

class WalletViewModelFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WalletViewModel(c.repository, c::providersFor, c.settings, c.priceService, c.bridgeJobs) as T
}
