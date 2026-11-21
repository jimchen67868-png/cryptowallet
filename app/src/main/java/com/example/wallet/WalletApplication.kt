package com.example.wallet

import android.app.Application
import android.content.Context
import com.example.wallet.chain.AcrossRoutes
import com.example.wallet.chain.BitcoinProvider
import com.example.wallet.chain.CcipTestnet
import com.example.wallet.chain.BridgeRoute
import com.example.wallet.chain.Chain
import com.example.wallet.chain.ChainProvider
import com.example.wallet.chain.EthereumProvider
import com.example.wallet.chain.SolanaProvider
import com.example.wallet.chain.UniswapDeployments
import com.example.wallet.chain.UniswapV2Deployments
import com.example.wallet.chain.UniswapV3Config
import com.example.wallet.data.AppSettings
import com.example.wallet.data.PrefsBridgeSwapStore
import com.example.wallet.data.EvmTokens
import com.example.wallet.data.Network
import com.example.wallet.data.PriceService
import com.example.wallet.data.TokenInfo
import com.example.wallet.data.TokenStore
import com.example.wallet.data.WalletRepository
import com.example.wallet.security.BiometricAuthenticator
import com.example.wallet.security.SecureSeedStore

class WalletApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.example.wallet.data.CrashReporter.install(this)
        com.example.wallet.data.UiCrashGuard.install()
        container = AppContainer(this)
    }
}

/** Simple manual dependency container (swap for Hilt later if you like). */
class AppContainer(context: Context) {
    val repository = WalletRepository(SecureSeedStore(context), BiometricAuthenticator())
    val priceService = PriceService()
    val settings = AppSettings(context)
    val bridgeJobs = PrefsBridgeSwapStore(context)

    // Custom tokens are stored per network so testnet contracts never leak into mainnet.
    private val testnetTokens = TokenStore(context)
    private val mainnetTokens = TokenStore(context, "main:")

    /** One generic EVM provider per network: add another chain by adding one more call. */
    private fun evm(
        chain: Chain, rpc: String, chainId: Long, tokens: TokenStore, testnet: Boolean,
        testnetName: String, known: List<TokenInfo>, historyApi: String?, explorerTx: String,
        swapId: Int? = null, uniswap: UniswapV3Config? = null,
        bridgeRoutes: List<BridgeRoute> = emptyList(), relayTestnet: Boolean = false, ccipTestnet: Boolean = false
    ) = EthereumProvider(
        tokenStore = tokens,
        rpcUrl = rpc,
        chainId = chainId,
        knownTokens = known,
        explorerApi = historyApi,
        explorerTxUrl = explorerTx,
        evmChain = chain,
        isTestnet = testnet,
        testnetName = testnetName,
        swapNetworkId = swapId,
        uniswap = uniswap,
        bridgeRoutes = bridgeRoutes,
        relayTestnet = relayTestnet,
        ccipTestnet = ccipTestnet
    )

    private val testnetProviders: List<ChainProvider> by lazy {
        listOf(
            EthereumProvider(testnetTokens, uniswap = UniswapDeployments.SEPOLIA, uniswapV2 = UniswapV2Deployments.SEPOLIA, relayTestnet = true, ccipTestnet = true,
            // CCIP-BnM is a free test token with no market price, so it is added here and not to the priced SEPOLIA_TOKENS list.
            knownTokens = EthereumProvider.SEPOLIA_TOKENS +
                com.example.wallet.data.TokenInfo(CcipTestnet.SEPOLIA.bnm, CcipTestnet.BNM_SYMBOL, CcipTestnet.BNM_DECIMALS)), BitcoinProvider(), SolanaProvider(),
            evm(Chain.BASE, "https://base-sepolia-rpc.publicnode.com", 84532L, testnetTokens, true,
                "Sepolia testnet", emptyList(), "https://base-sepolia.blockscout.com/api/v2", "https://sepolia.basescan.org/tx/", uniswap = UniswapDeployments.BASE_SEPOLIA, relayTestnet = true),
            evm(Chain.ARB, "https://arbitrum-sepolia-rpc.publicnode.com", 421614L, testnetTokens, true,
                "Sepolia testnet", emptyList(), "https://arbitrum-sepolia.blockscout.com/api/v2", "https://sepolia.arbiscan.io/tx/", uniswap = UniswapDeployments.ARBITRUM_SEPOLIA),
            evm(Chain.POL, "https://polygon-amoy-bor-rpc.publicnode.com", 80002L, testnetTokens, true,
                "Amoy testnet", emptyList(), null, "https://amoy.polygonscan.com/tx/"),
            evm(Chain.BNB, "https://bsc-testnet-rpc.publicnode.com", 97L, testnetTokens, true,
                "Testnet", listOf(com.example.wallet.data.TokenInfo(CcipTestnet.BNB_TESTNET.bnm, CcipTestnet.BNM_SYMBOL, CcipTestnet.BNM_DECIMALS)),
                null, "https://testnet.bscscan.com/tx/", ccipTestnet = true)
        )
    }

    private val mainnetProviders: List<ChainProvider> by lazy {
        listOf(
            EthereumProvider(
                tokenStore = mainnetTokens,
                rpcUrl = "https://ethereum-rpc.publicnode.com",
                chainId = 1L,
                knownTokens = EthereumProvider.MAINNET_TOKENS,
                explorerApi = "https://eth.blockscout.com/api/v2",
                explorerTxUrl = "https://etherscan.io/tx/",
                isTestnet = false,
                swapNetworkId = 1,
                bridgeRoutes = AcrossRoutes.MAINNET
            ),
            BitcoinProvider(
                apiBase = "https://mempool.space/api",
                hrp = "bc",
                coinType = 0,
                p2pkhVersion = 0x00,
                p2shVersion = 0x05,
                explorerTxUrl = "https://mempool.space/tx/"
            ),
            SolanaProvider(
                rpcUrl = "https://api.mainnet-beta.solana.com",
                explorerSuffix = ""
            ),
            evm(Chain.BASE, "https://base-rpc.publicnode.com", 8453L, mainnetTokens, false,
                "Mainnet", EvmTokens.BASE, "https://base.blockscout.com/api/v2", "https://basescan.org/tx/", 8453, bridgeRoutes = AcrossRoutes.MAINNET),
            evm(Chain.ARB, "https://arbitrum-one-rpc.publicnode.com", 42161L, mainnetTokens, false,
                "Mainnet", EvmTokens.ARBITRUM, "https://arbitrum.blockscout.com/api/v2", "https://arbiscan.io/tx/", 42161, bridgeRoutes = AcrossRoutes.MAINNET),
            evm(Chain.POL, "https://polygon-bor-rpc.publicnode.com", 137L, mainnetTokens, false,
                "Mainnet", EvmTokens.POLYGON, "https://polygon.blockscout.com/api/v2", "https://polygonscan.com/tx/", 137, bridgeRoutes = AcrossRoutes.MAINNET),
            evm(Chain.BNB, "https://bsc-rpc.publicnode.com", 56L, mainnetTokens, false,
                "Mainnet", EvmTokens.BNB, null, "https://bscscan.com/tx/", 56)
        )
    }

    fun providersFor(network: Network): List<ChainProvider> =
        if (network == Network.MAINNET) mainnetProviders else testnetProviders
}
