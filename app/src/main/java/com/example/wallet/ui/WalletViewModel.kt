package com.example.wallet.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.wallet.AppContainer
import com.example.wallet.chain.Chain
import com.example.wallet.chain.ChainProvider
import com.example.wallet.chain.BridgeQuote
import com.example.wallet.chain.SwapAsset
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
import kotlinx.coroutines.launch
import java.math.BigDecimal

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
    private val priceService: PriceService
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
    var pendingBridge by mutableStateOf<BridgeQuote?>(null)
        private set
    var history by mutableStateOf<HistoryUi>(HistoryUi.Loading)
        private set
    /** CoinGecko coin id -> USD price. */
    var prices by mutableStateOf<Map<String, Double>>(emptyMap())
        private set

    var network by mutableStateOf(settings.network)
        private set

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
        viewModelScope.launch {
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
        viewModelScope.launch {
            val words = runCatching { repo.importWallet(activity, phrase) }.getOrNull()
            if (words == null) message = "Invalid phrase or authentication cancelled."
            else startSession(words)
        }
    }

    fun unlock(activity: FragmentActivity) {
        viewModelScope.launch {
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
        tokens = emptyMap()
        history = HistoryUi.Loading
        if (repo.hasWallet()) screen = Screen.Locked
    }

    fun refreshBalances() {
        refreshPrices()
        accounts.forEach { acc ->
            val provider = providers.first { it.chain == acc.chain }
            viewModelScope.launch {
                val result = runCatching { provider.getBalance(acc.address) }
                accounts = accounts.map {
                    if (it.chain == acc.chain) it.copy(balance = result.getOrNull(), error = result.isFailure) else it
                }
            }
            viewModelScope.launch {
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
        viewModelScope.launch {
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
        val amount = amountText.trim().toBigDecimalOrNull()
        if (amount == null || amount.signum() <= 0) { message = "Enter a valid amount"; return }
        val fromAsset = SwapAsset(from?.id, from?.symbol ?: chain.symbol, from?.decimals ?: provider.nativeDecimals)
        val balance = from?.balance ?: acc.balance
        if (balance != null && amount > balance) { message = "Amount exceeds your ${fromAsset.symbol} balance"; return }
        limitError(chain, from?.id, amount)?.let { message = it; return }
        sending = true
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    // ---- moving funds to another network (Across, EVM mainnets) ----
    fun requestBridgeQuote(chain: Chain, from: TokenBalance?, dest: Chain, amountText: String) {
        val provider = providers.first { it.chain == chain }
        val acc = accounts.firstOrNull { it.chain == chain } ?: return
        if (!provider.supportsBridge) { message = "Moving funds to another network is not available here."; return }
        val amount = amountText.trim().toBigDecimalOrNull()
        if (amount == null || amount.signum() <= 0) { message = "Enter a valid amount"; return }
        val fromAsset = SwapAsset(from?.id, from?.symbol ?: chain.symbol, from?.decimals ?: provider.nativeDecimals)
        val balance = from?.balance ?: acc.balance
        if (balance != null && amount > balance) { message = "Amount exceeds your ${fromAsset.symbol} balance"; return }
        limitError(chain, from?.id, amount)?.let { message = it; return }
        sending = true
        viewModelScope.launch {
            runCatching { provider.quoteBridge(acc.address, fromAsset, dest, amount) }
                .onSuccess { pendingBridge = it }
                .onFailure { message = it.message ?: "Could not get a bridge quote" }
            sending = false
        }
    }

    fun cancelBridge() { pendingBridge = null }

    fun confirmBridge(activity: FragmentActivity) {
        val q = pendingBridge ?: return
        pendingBridge = null
        val provider = providers.first { it.chain == q.chain }
        val s = seed?.copyOf() ?: return
        viewModelScope.launch {
            if (!repo.confirmIdentity(activity, "Confirm bridge")) {
                message = "Authentication cancelled. Nothing was sent."
                s.fill(0)
                return@launch
            }
            limitError(q.chain, q.from.id, q.amountIn)?.let { message = it; s.fill(0); return@launch }
            sending = true
            val r = runCatching { provider.executeBridge(s, q) }
            s.fill(0)
            sending = false
            r.onSuccess {
                message = "Bridge submitted. Tx: ${it.swapTxId}. Funds arrive on ${q.destChain.label} in a few minutes: open that card and tap Refresh."
                screen = Screen.Home
                refreshBalances()
            }.onFailure { message = "Bridge failed: ${it.message}" }
        }
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        val amount = amountText.trim().toBigDecimalOrNull()
        val target = to.trim()
        if (amount == null || amount.signum() <= 0) { message = "Enter a valid amount"; return }
        if (!provider.isValidAddress(target)) { message = "Invalid ${chain.symbol} address"; return }
        if (token != null && amount > token.balance) {
            message = "Amount exceeds your ${token.symbol} balance"; return
        }
        limitError(chain, token?.id, amount)?.let { message = it; return }
        sending = true
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        val amount = amountText.trim().toBigDecimalOrNull()
        val target = to.trim()
        if (amount == null || amount.signum() <= 0) { message = "Enter a valid amount"; s.fill(0); return }
        if (!provider.isValidAddress(target)) { message = "Invalid ${chain.symbol} address"; s.fill(0); return }
        limitError(chain, token?.id, amount)?.let { message = it; s.fill(0); return }
        sending = true
        viewModelScope.launch {
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
        WalletViewModel(c.repository, c::providersFor, c.settings, c.priceService) as T
}
