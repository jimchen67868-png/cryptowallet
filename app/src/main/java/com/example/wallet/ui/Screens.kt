package com.example.wallet.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.wallet.chain.Chain
import com.example.wallet.data.AppConfig
import com.example.wallet.data.Network
import com.example.wallet.chain.SwapAsset
import com.example.wallet.chain.TokenBalance
import com.example.wallet.chain.TxDirection
import com.example.wallet.chain.TxRecord
import com.example.wallet.chain.TxStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun WalletRoot(vm: WalletViewModel, activity: FragmentActivity) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    BackHandler(enabled = vm.canGoBack) { vm.back() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            when (val s = vm.screen) {
                Screen.Welcome -> WelcomeScreen(vm)
                Screen.ShowBackup -> BackupScreen(vm)
                Screen.ConfirmBackup -> ConfirmBackupScreen(vm, activity)
                Screen.Import -> ImportScreen(vm, activity)
                Screen.Locked -> LockedScreen(vm, activity)
                Screen.Home -> HomeScreen(vm)
                is Screen.Receive -> ReceiveScreen(vm, s.chain)
                is Screen.Send -> SendScreen(vm, activity, s.chain, s.token)
                is Screen.History -> HistoryScreen(vm, s.chain)
                is Screen.Swap -> SwapScreen(vm, activity, s.chain, s.from)
                Screen.NetworkSettings -> NetworkScreen(vm)
                Screen.MainnetWarning -> MainnetWarningScreen(vm)
            }
        }
    }
}

@Composable
private fun WelcomeScreen(vm: WalletViewModel) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Crypto Wallet", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Non-custodial: your keys never leave this device.\nStarts on test networks. Mainnet is opt-in from the home screen.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = vm::startCreate, modifier = Modifier.fillMaxWidth()) { Text("Create new wallet") }
        OutlinedButton(onClick = { vm.goTo(Screen.Import) }, modifier = Modifier.fillMaxWidth()) {
            Text("Import with recovery phrase")
        }
    }
}

@Composable
private fun BackupScreen(vm: WalletViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Write down your recovery phrase", style = MaterialTheme.typography.headlineSmall)
        Text("Anyone with these words can take your funds. Store them offline and never share them. Nobody, including us, can recover them for you.")
        vm.pendingWords.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEachIndexed { col, w ->
                    Text("${row * 2 + col + 1}. $w", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Button(onClick = vm::goToConfirm, modifier = Modifier.fillMaxWidth()) { Text("I've written it down") }
    }
}

@Composable
private fun ConfirmBackupScreen(vm: WalletViewModel, activity: FragmentActivity) {
    val answers = remember { mutableStateListOf("", "", "") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Confirm your backup", style = MaterialTheme.typography.headlineSmall)
        vm.confirmIndices.forEachIndexed { i, wordIndex ->
            OutlinedTextField(
                value = answers[i],
                onValueChange = { answers[i] = it },
                label = { Text("Word #${wordIndex + 1}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(onClick = { vm.confirmBackup(activity, answers.toList()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Confirm and secure wallet")
        }
    }
}

@Composable
private fun ImportScreen(vm: WalletViewModel, activity: FragmentActivity) {
    var phrase by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Import wallet", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            label = { Text("12 or 24 word recovery phrase") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            modifier = Modifier.fillMaxWidth().height(140.dp)
        )
        Button(onClick = { vm.importWallet(activity, phrase) }, modifier = Modifier.fillMaxWidth()) { Text("Import") }
        TextButton(onClick = vm::back) { Text("Back") }
    }
}

@Composable
private fun LockedScreen(vm: WalletViewModel, activity: FragmentActivity) {
    var confirmReset by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Wallet locked", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { vm.unlock(activity) }) { Text("Unlock") }
        TextButton(onClick = { confirmReset = true }) { Text("Can't unlock? Reset wallet") }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset wallet?") },
            text = { Text("This removes the wallet from this device. You will need your recovery phrase to restore it.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetWallet() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HomeScreen(vm: WalletViewModel) {
    var addTokenFor by remember { mutableStateOf<Chain?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Portfolio", style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(onClick = vm::refreshBalances) { Text("Refresh") }
                TextButton(onClick = { vm.lock() }) { Text("Lock") }
            }
        }
        if (vm.network == Network.MAINNET) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("MAINNET · real funds", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { vm.goTo(Screen.NetworkSettings) }) { Text("Network") }
                }
            }
        } else {
            TextButton(onClick = { vm.goTo(Screen.NetworkSettings) }) { Text("Network: testnet (change)") }
        }
        vm.portfolioUsd()?.let {
            Text("≈ ${formatUsd(it)}", style = MaterialTheme.typography.headlineMedium)
        }
        if (vm.isTestnet) {
            Text(
                "Testnet coins have no real value. USD figures are mainnet reference prices from CoinGecko.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.accounts) { acc ->
                val bal = acc.balance
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${acc.chain.label} · ${vm.providers.first { it.chain == acc.chain }.networkName}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Row {
                                if (vm.providers.first { it.chain == acc.chain }.supportsSwap) {
                                    TextButton(onClick = { vm.goTo(Screen.Swap(acc.chain)) }) { Text("Swap") }
                                }
                                TextButton(onClick = { vm.openHistory(acc.chain) }) { Text("History") }
                            }
                        }
                        Text(
                            when {
                                acc.error -> "Balance unavailable"
                                bal == null -> "Loading…"
                                else -> "${bal.stripTrailingZeros().toPlainString()} ${acc.chain.symbol}"
                            },
                            style = MaterialTheme.typography.headlineSmall
                        )
                        val price = vm.priceOf(acc.chain)
                        if (bal != null && price != null) {
                            Text("≈ ${formatUsd(bal.toDouble() * price)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(shorten(acc.address), style = MaterialTheme.typography.bodySmall)
                        vm.tokens[acc.chain].orEmpty().forEach { t ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val tokenUsd = vm.priceOf(acc.chain, t.id)
                                    ?.let { "  ≈ ${formatUsd(t.balance.toDouble() * it)}" }.orEmpty()
                                Text("${t.symbol}  ${t.balance.stripTrailingZeros().toPlainString()}$tokenUsd")
                                Row {
                                    if (vm.providers.first { it.chain == acc.chain }.supportsSwap) {
                                        TextButton(onClick = { vm.goTo(Screen.Swap(acc.chain, t)) }) { Text("Swap") }
                                    }
                                    if (vm.providers.first { it.chain == acc.chain }.supportsTokenSend) {
                                        TextButton(onClick = { vm.goTo(Screen.Send(acc.chain, t)) }) { Text("Send") }
                                    }
                                    if (t.removable) {
                                        TextButton(onClick = { vm.removeToken(acc.chain, t.id) }) { Text("Remove") }
                                    }
                                }
                            }
                        }
                        if (vm.providers.first { it.chain == acc.chain }.supportsCustomTokens) {
                            TextButton(onClick = { addTokenFor = acc.chain }) { Text("Add token") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.goTo(Screen.Receive(acc.chain)) }) { Text("Receive") }
                            Button(onClick = { vm.goTo(Screen.Send(acc.chain)) }) { Text("Send") }
                        }
                    }
                }
            }
        }
    }

    addTokenFor?.let { c ->
        AddTokenDialog(
            onAdd = { vm.addToken(c, it); addTokenFor = null },
            onDismiss = { addTokenFor = null }
        )
    }
}

@Composable
private fun AddTokenDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var contract by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add token") },
        text = {
            OutlinedTextField(
                value = contract,
                onValueChange = { contract = it },
                label = { Text("Token contract address (0x…)") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(contract) }, enabled = contract.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReceiveScreen(vm: WalletViewModel, chain: Chain) {
    val address = vm.accounts.firstOrNull { it.chain == chain }?.address ?: return
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Receive ${chain.symbol} on ${chain.label}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Only send ${chain.label} (${vm.providers.first { it.chain == chain }.networkName}) assets to this address.",
            textAlign = TextAlign.Center
        )
        QrCode(address, 240.dp)
        Text(address, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = { clipboard.setText(AnnotatedString(address)) }) { Text("Copy address") }
        TextButton(onClick = vm::back) { Text("Back") }
    }
}

@Composable
private fun SendScreen(vm: WalletViewModel, activity: FragmentActivity, chain: Chain, token: TokenBalance?) {
    val provider = vm.providers.first { it.chain == chain }
    val asset = token?.symbol ?: chain.symbol
    val canSend = if (token != null) provider.supportsTokenSend else provider.supportsSend
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Send $asset on ${chain.label}", style = MaterialTheme.typography.headlineSmall)
        if (token != null) {
            Text("Available: ${token.balance.stripTrailingZeros().toPlainString()} $asset. The network fee is paid in ${chain.symbol}.")
        }
        if (!canSend) {
            Text("Sending $asset isn't implemented in this starter yet. See the README for what's needed.")
        } else {
            OutlinedTextField(
                value = to, onValueChange = { to = it },
                label = { Text("Recipient address") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = amount, onValueChange = { amount = it },
                label = { Text("Amount ($asset)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            val unitPrice = vm.priceOf(chain, token?.id)
            val typed = amount.trim().toBigDecimalOrNull()
            if (unitPrice != null && typed != null && typed.signum() > 0) {
                Text("≈ ${formatUsd(typed.toDouble() * unitPrice)}", style = MaterialTheme.typography.bodySmall)
            }
            if (vm.sending) CircularProgressIndicator()
            Button(
                onClick = { vm.reviewSend(chain, token, to, amount) },
                enabled = !vm.sending && to.isNotBlank() && amount.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Review") }
        }
        TextButton(onClick = vm::back) { Text("Back") }
    }

    vm.pendingSend?.let { p ->
        val fee = p.fee?.stripTrailingZeros()?.toPlainString() ?: "unknown"
        val sym = p.token?.symbol ?: p.chain.symbol
        val usdText = p.amount.toBigDecimalOrNull()
            ?.let { a -> vm.priceOf(p.chain, p.token?.id)?.let { " (≈ ${formatUsd(a.toDouble() * it)})" } }
            .orEmpty()
        AlertDialog(
            onDismissRequest = vm::cancelSend,
            title = { Text(if (vm.network == Network.MAINNET) "Confirm MAINNET transaction" else "Confirm transaction") },
            text = {
                Text("Send ${p.amount} $sym$usdText on ${p.chain.label} to:\n\n${p.to}\n\nNetwork fee: $fee ${p.chain.symbol}\n\nThis cannot be undone.")
            },
            confirmButton = { TextButton(onClick = { vm.confirmSend(activity) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = vm::cancelSend) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NetworkScreen(vm: WalletViewModel) {
    val mainnet = vm.network == Network.MAINNET
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Network", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (mainnet) "You are on MAINNET. Balances and sends use real funds."
            else "You are on test networks (Sepolia, Bitcoin testnet, Solana devnet). Coins here have no real value."
        )
        if (mainnet) {
            Button(onClick = { vm.switchNetwork(Network.TESTNET) }, modifier = Modifier.fillMaxWidth()) {
                Text("Switch to testnet")
            }
        } else {
            Button(onClick = { vm.goTo(Screen.MainnetWarning) }, modifier = Modifier.fillMaxWidth()) {
                Text("Switch to mainnet…")
            }
        }
        TextButton(onClick = vm::back) { Text("Back") }
    }
}

@Composable
private fun MainnetWarningScreen(vm: WalletViewModel) {
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Switch to mainnet?", style = MaterialTheme.typography.headlineSmall)
        Text("You are about to use real money. Please read this carefully:")
        Text("• This is a starter project. Its code has not been audited or tested with real funds.")
        Text("• Transactions cannot be reversed. A wrong address or a bug can lose funds permanently.")
        Text("• Each send is capped at about $${AppConfig.MAINNET_TX_LIMIT_USD.toInt()} and needs your fingerprint or PIN.")
        Text("• Start with an amount you can afford to lose, and make sure your recovery phrase backup is correct.")
        Text("• Anyone with your recovery phrase can take everything. Never share or photograph it.")
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Type MAINNET to continue") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { vm.switchNetwork(Network.MAINNET) },
            enabled = typed.trim() == "MAINNET",
            modifier = Modifier.fillMaxWidth()
        ) { Text("I understand, switch to mainnet") }
        TextButton(onClick = vm::back) { Text("Cancel") }
    }
}

@Composable
private fun SwapScreen(vm: WalletViewModel, activity: FragmentActivity, chain: Chain, from: TokenBalance?) {
    val provider = vm.providers.first { it.chain == chain }
    val fromSymbol = from?.symbol ?: chain.symbol
    val options = provider.swapAssets().filter { it.id?.lowercase() != from?.id?.lowercase() }
    var toIndex by remember { mutableStateOf(0) }
    var amount by remember { mutableStateOf("") }
    val available = from?.balance ?: vm.accounts.firstOrNull { it.chain == chain }?.balance

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Swap on ${chain.label}", style = MaterialTheme.typography.headlineSmall)
        Text("From: $fromSymbol" + (available?.let { "  (available ${it.plain()})" } ?: ""))
        Text("To:")
        options.forEachIndexed { i, o ->
            if (i == toIndex) {
                Button(onClick = { toIndex = i }, modifier = Modifier.fillMaxWidth()) { Text(o.symbol) }
            } else {
                OutlinedButton(onClick = { toIndex = i }, modifier = Modifier.fillMaxWidth()) { Text(o.symbol) }
            }
        }
        OutlinedTextField(
            value = amount, onValueChange = { amount = it },
            label = { Text("Amount ($fromSymbol)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        val unitPrice = vm.priceOf(chain, from?.id)
        val typed = amount.trim().toBigDecimalOrNull()
        if (unitPrice != null && typed != null && typed.signum() > 0) {
            Text("≈ ${formatUsd(typed.toDouble() * unitPrice)}", style = MaterialTheme.typography.bodySmall)
        }
        if (vm.sending) CircularProgressIndicator()
        Button(
            onClick = { options.getOrNull(toIndex)?.let { vm.requestSwapQuote(chain, from, it, amount) } },
            enabled = !vm.sending && amount.isNotBlank() && options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Get quote") }
        Text(
            "Routed through ParaSwap. Before anything is signed, the app checks the quote's router, tokens, amount, " +
                "payout address and minimum output itself, and rejects the swap if anything is off.",
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = vm::back) { Text("Back") }
    }

    vm.pendingSwap?.let { q ->
        AlertDialog(
            onDismissRequest = vm::cancelSwap,
            title = { Text("Confirm MAINNET swap") },
            text = {
                Text(
                    "Swap ${q.amountIn.plain()} ${q.from.symbol} for about ${q.expectedOut.plain()} ${q.to.symbol}\n\n" +
                        "Minimum you receive: ${q.minOut.plain()} ${q.to.symbol} (${q.slippageBps / 100.0}% slippage)\n" +
                        (if (q.needsApproval) "\nFirst needs a token approval for exactly this amount, so 2 transactions.\n" else "") +
                        (q.gasUsd?.let { "\nEstimated network fee: about $" + it + "\n" } ?: "") +
                        "\nThis cannot be undone."
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmSwap(activity) }) { Text("Swap") } },
            dismissButton = { TextButton(onClick = vm::cancelSwap) { Text("Cancel") } }
        )
    }
}

private fun java.math.BigDecimal.plain(): String = stripTrailingZeros().toPlainString()

@Composable
private fun HistoryScreen(vm: WalletViewModel, chain: Chain) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${chain.label} history", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { vm.loadHistory(chain) }) { Text("Refresh") }
        }
        when (val h = vm.history) {
            HistoryUi.Loading -> CircularProgressIndicator()
            is HistoryUi.Failed -> Text("Could not load history: ${h.message}")
            is HistoryUi.Loaded -> {
                if (h.items.isEmpty()) {
                    Text("No transactions yet.")
                } else {
                    Text("Tap a transaction to open it in the block explorer.", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(h.items) { tx ->
                            TxRow(tx) { uriHandler.openUri(vm.explorerUrl(chain, tx.id)) }
                        }
                    }
                }
            }
        }
        TextButton(onClick = vm::back) { Text("Back") }
    }
}

@Composable
private fun TxRow(tx: TxRecord, onClick: () -> Unit) {
    val label = when (tx.direction) {
        TxDirection.IN -> "Received"
        TxDirection.OUT -> "Sent"
        TxDirection.SELF -> "To yourself"
    }
    val suffix = when (tx.status) {
        TxStatus.PENDING -> " (pending)"
        TxStatus.FAILED -> " (failed)"
        TxStatus.CONFIRMED -> ""
    }
    val sign = when (tx.direction) {
        TxDirection.IN -> "+"
        TxDirection.OUT -> "-"
        TxDirection.SELF -> ""
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label + suffix, style = MaterialTheme.typography.titleSmall)
                tx.counterparty?.let { Text(shorten(it), style = MaterialTheme.typography.bodySmall) }
                Text(formatTime(tx.timeSec), style = MaterialTheme.typography.bodySmall)
            }
            Text("$sign${tx.amount.stripTrailingZeros().toPlainString()} ${tx.symbol}")
        }
    }
}

private fun formatUsd(v: Double): String =
    if (v > 0 && v < 0.01) "<$0.01"
    else java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US).format(v)

private fun formatTime(sec: Long?): String =
    if (sec == null) "Pending"
    else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(sec * 1000))

@Composable
private fun QrCode(text: String, size: Dp) {
    val bitmap = remember(text) {
        val w = 512
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, w, w)
        val pixels = IntArray(w * w) { i ->
            if (matrix.get(i % w, i / w)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, w, w, Bitmap.Config.ARGB_8888)
    }
    Image(bitmap.asImageBitmap(), contentDescription = "Address QR code", modifier = Modifier.size(size))
}

private fun shorten(a: String) = if (a.length > 18) a.take(9) + "…" + a.takeLast(7) else a
