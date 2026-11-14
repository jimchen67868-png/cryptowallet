package com.example.wallet.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.wallet.chain.Chain
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
                is Screen.Send -> SendScreen(vm, s.chain)
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
            "Non-custodial: your keys never leave this device.\nTestnets only in this build.",
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Portfolio", style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(onClick = vm::refreshBalances) { Text("Refresh") }
                TextButton(onClick = { vm.lock() }) { Text("Lock") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.accounts) { acc ->
                val bal = acc.balance
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(acc.chain.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                acc.error -> "Balance unavailable"
                                bal == null -> "Loading…"
                                else -> "${bal.stripTrailingZeros().toPlainString()} ${acc.chain.symbol}"
                            },
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(shorten(acc.address), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.goTo(Screen.Receive(acc.chain)) }) { Text("Receive") }
                            Button(onClick = { vm.goTo(Screen.Send(acc.chain)) }) { Text("Send") }
                        }
                    }
                }
            }
        }
    }
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
        Text("Receive ${chain.symbol}", style = MaterialTheme.typography.headlineSmall)
        Text("Only send ${chain.label} assets to this address.", textAlign = TextAlign.Center)
        QrCode(address, 240.dp)
        Text(address, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = { clipboard.setText(AnnotatedString(address)) }) { Text("Copy address") }
        TextButton(onClick = vm::back) { Text("Back") }
    }
}

@Composable
private fun SendScreen(vm: WalletViewModel, chain: Chain) {
    val provider = vm.providers.first { it.chain == chain }
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Send ${chain.symbol}", style = MaterialTheme.typography.headlineSmall)
        if (!provider.supportsSend) {
            Text("Sending ${chain.symbol} isn't implemented in this starter yet. See the README for what's needed.")
        } else {
            OutlinedTextField(
                value = to, onValueChange = { to = it },
                label = { Text("Recipient address") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = amount, onValueChange = { amount = it },
                label = { Text("Amount (${chain.symbol})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            if (vm.sending) CircularProgressIndicator()
            Button(
                onClick = { confirm = true },
                enabled = !vm.sending && to.isNotBlank() && amount.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Review") }
        }
        TextButton(onClick = vm::back) { Text("Back") }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Confirm transaction") },
            text = {
                Text("Send ${amount.trim()} ${chain.symbol} to:\n\n${to.trim()}\n\nA network fee applies. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.send(chain, to, amount) }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

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
