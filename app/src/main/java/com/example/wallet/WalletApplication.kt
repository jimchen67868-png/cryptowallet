package com.example.wallet

import android.app.Application
import android.content.Context
import com.example.wallet.chain.BitcoinProvider
import com.example.wallet.chain.ChainProvider
import com.example.wallet.chain.EthereumProvider
import com.example.wallet.chain.SolanaProvider
import com.example.wallet.data.WalletRepository
import com.example.wallet.security.BiometricAuthenticator
import com.example.wallet.security.SecureSeedStore

class WalletApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Simple manual dependency container (swap for Hilt later if you like). */
class AppContainer(context: Context) {
    val repository = WalletRepository(SecureSeedStore(context), BiometricAuthenticator())
    val providers: List<ChainProvider> = listOf(EthereumProvider(), BitcoinProvider(), SolanaProvider())
}
