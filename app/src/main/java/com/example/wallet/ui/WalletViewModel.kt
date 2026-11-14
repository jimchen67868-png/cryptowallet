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
import com.example.wallet.crypto.SeedGenerator
import com.example.wallet.data.WalletRepository
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class Account(
    val chain: Chain,
    val address: String,
    val balance: BigDecimal? = null,
    val error: Boolean = false
)

sealed interface Screen {
    object Welcome : Screen
    object ShowBackup : Screen
    object ConfirmBackup : Screen
    object Import : Screen
    object Locked : Screen
    object Home : Screen
    data class Receive(val chain: Chain) : Screen
    data class Send(val chain: Chain) : Screen
}

class WalletViewModel(
    private val repo: WalletRepository,
    val providers: List<ChainProvider>
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

    /** The seed lives only in memory while unlocked and is wiped on lock(). */
    private var seed: ByteArray? = null

    val canGoBack: Boolean
        get() = screen is Screen.Receive || screen is Screen.Send ||
            screen == Screen.ConfirmBackup || screen == Screen.ShowBackup || screen == Screen.Import

    fun clearMessage() { message = null }
    fun goTo(s: Screen) { screen = s }

    fun back() {
        screen = when (screen) {
            is Screen.Receive, is Screen.Send -> Screen.Home
            Screen.ConfirmBackup -> Screen.ShowBackup
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
        if (repo.hasWallet()) screen = Screen.Locked
    }

    fun refreshBalances() {
        accounts.forEach { acc ->
            val provider = providers.first { it.chain == acc.chain }
            viewModelScope.launch {
                val result = runCatching { provider.getBalance(acc.address) }
                accounts = accounts.map {
                    if (it.chain == acc.chain) it.copy(balance = result.getOrNull(), error = result.isFailure) else it
                }
            }
        }
    }

    // ---- send ----
    fun send(chain: Chain, to: String, amountText: String) {
        val provider = providers.first { it.chain == chain }
        val s = seed?.copyOf() ?: return
        val amount = amountText.trim().toBigDecimalOrNull()
        val target = to.trim()
        if (amount == null || amount.signum() <= 0) { message = "Enter a valid amount"; s.fill(0); return }
        if (!provider.isValidAddress(target)) { message = "Invalid ${chain.symbol} address"; s.fill(0); return }
        sending = true
        viewModelScope.launch {
            val r = runCatching { provider.send(s, target, amount) }
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
        WalletViewModel(c.repository, c.providers) as T
}
