package com.example.wallet.chain

import com.example.wallet.crypto.Hd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.crypto.Credentials
import org.web3j.crypto.WalletUtils
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger

/** Ethereum / EVM. Defaults to the Sepolia testnet. */
class EthereumProvider(
    rpcUrl: String = "https://ethereum-sepolia-rpc.publicnode.com",
    private val chainId: Long = 11155111L
) : ChainProvider {

    override val chain = Chain.ETH
    override val supportsSend = true

    private val web3: Web3j by lazy { Web3j.build(HttpService(rpcUrl)) }

    // m/44'/60'/0'/0/0
    private fun credentials(seed: ByteArray): Credentials =
        Credentials.create(Hd.secp256k1(seed, 44 or Hd.H, 60 or Hd.H, 0 or Hd.H, 0, 0))

    override fun deriveAddress(seed: ByteArray): String = credentials(seed).address

    override suspend fun getBalance(address: String): BigDecimal = withContext(Dispatchers.IO) {
        val res = web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send()
        if (res.hasError()) throw IllegalStateException(res.error.message)
        Convert.fromWei(BigDecimal(res.balance), Convert.Unit.ETHER)
    }

    override fun isValidAddress(address: String): Boolean = WalletUtils.isValidAddress(address)

    override suspend fun send(seed: ByteArray, to: String, amount: BigDecimal): String =
        withContext(Dispatchers.IO) {
            val creds = credentials(seed)
            val wei = Convert.toWei(amount, Convert.Unit.ETHER).toBigIntegerExact()
            val gasPrice = web3.ethGasPrice().send().gasPrice
            val txManager = RawTransactionManager(web3, creds, chainId)
            val res = txManager.sendTransaction(gasPrice, BigInteger.valueOf(21_000), to, "", wei)
            if (res.hasError()) throw IllegalStateException(res.error.message)
            res.transactionHash
        }
}
