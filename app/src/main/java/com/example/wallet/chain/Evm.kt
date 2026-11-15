package com.example.wallet.chain

import com.example.wallet.crypto.Hd
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import java.math.BigInteger

/** Key derivation shared by every EVM network: m/44'/60'/0'/0/0. */
object EvmKeys {
    fun credentials(seed: ByteArray): Credentials =
        Credentials.create(Hd.secp256k1(seed, 44 or Hd.H, 60 or Hd.H, 0 or Hd.H, 0, 0))
}

object Erc20 {
    /** ABI-encoded `transfer(address,uint256)` call data, "0x"-prefixed. */
    fun transferData(to: String, raw: BigInteger): String = FunctionEncoder.encode(
        Function(
            "transfer",
            listOf<Type<*>>(Address(to), Uint256(raw)),
            listOf<TypeReference<*>>(TypeReference.create(Bool::class.java))
        )
    )
}
