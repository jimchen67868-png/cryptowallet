package com.example.wallet.chain

import com.example.wallet.crypto.Hd
import com.example.wallet.data.TokenInfo
import com.example.wallet.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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

/** Ethereum / EVM. Defaults to the Sepolia testnet. */
class EthereumProvider(
    private val tokenStore: TokenStore,
    rpcUrl: String = "https://ethereum-sepolia-rpc.publicnode.com",
    private val chainId: Long = 11155111L,
    /** Well-known tokens shown automatically when the balance is above zero. Verify addresses per network. */
    private val knownTokens: List<TokenInfo> = SEPOLIA_TOKENS
) : ChainProvider {

    override val chain = Chain.ETH
    override val supportsSend = true
    override val supportsCustomTokens = true
    override val supportsTokenSend = true

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
        return FunctionEncoder.encode(
            Function(
                "transfer",
                listOf<Type<*>>(Address(to), Uint256(raw)),
                listOf<TypeReference<*>>(TypeReference.create(Bool::class.java))
            )
        )
    }

    /** Gas estimate + 20% buffer. Fails early (with the node's reason) if the transfer would revert. */
    private fun gasForTransfer(from: String, token: String, data: String): BigInteger {
        val tx = Transaction.createFunctionCallTransaction(from, null, null, null, token, data)
        val res = web3.ethEstimateGas(tx).send()
        if (res.hasError()) throw IllegalStateException("Transfer would fail: ${res.error.message}")
        return res.amountUsed.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN)
    }

    // ---- sending ETH ----

    override suspend fun estimateFee(address: String, to: String, amount: BigDecimal): BigDecimal? =
        withContext(Dispatchers.IO) {
            val gasPrice = web3.ethGasPrice().send().gasPrice
            Convert.fromWei(BigDecimal(gasPrice.multiply(BigInteger.valueOf(21_000))), Convert.Unit.ETHER)
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

    private companion object {
        const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

        val DECIMALS_FN = Function(
            "decimals", emptyList<Type<*>>(),
            listOf<TypeReference<*>>(TypeReference.create(Uint8::class.java))
        )
        val SYMBOL_FN = Function(
            "symbol", emptyList<Type<*>>(),
            listOf<TypeReference<*>>(TypeReference.create(Utf8String::class.java))
        )

        // Sepolia test tokens (double-check on a block explorer before relying on them)
        val SEPOLIA_TOKENS = listOf(
            TokenInfo("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", "USDC", 6),
            TokenInfo("0x779877A7B0D9E8603169DdbD7836e478b4624789", "LINK", 18),
            TokenInfo("0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14", "WETH", 18)
        )
    }
}
