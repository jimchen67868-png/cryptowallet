package com.example.wallet

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.example.wallet.ui.WalletRoot
import com.example.wallet.ui.WalletViewModel
import com.example.wallet.ui.WalletViewModelFactory

// FragmentActivity (not plain ComponentActivity) is required by BiometricPrompt.
class MainActivity : FragmentActivity() {

    private val vm: WalletViewModel by viewModels {
        WalletViewModelFactory((application as WalletApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots, screen recording and the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { WalletRoot(vm, this) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.lock() // wipe the seed from memory whenever the app leaves the foreground
    }
}
