package com.example.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Erc20Test {
    private val recipient = "0x742d35Cc6634C0532925a3b844Bc454e4438f44e"

    /** transfer(address,uint256): selector a9059cbb, then two 32-byte words. */
    @Test fun transfer_encodesSelectorAddressAndAmount() {
        assertEquals(
            "0xa9059cbb" +
                "000000000000000000000000742d35cc6634c0532925a3b844bc454e4438f44e" +
                "00000000000000000000000000000000000000000000000000000000000f4240",
            Erc20.transferData(recipient, BigInteger.valueOf(1_000_000))
        )
    }

    @Test fun transfer_addressCaseDoesNotMatter() {
        assertEquals(
            Erc20.transferData(recipient, BigInteger.ONE),
            Erc20.transferData(recipient.lowercase(), BigInteger.ONE)
        )
    }

    @Test fun transfer_zeroAndMaxAmounts() {
        val zero = Erc20.transferData(recipient, BigInteger.ZERO)
        assertTrue(zero.endsWith("0".repeat(64)))
        val max = Erc20.transferData(recipient, BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE))
        assertTrue(max.endsWith("f".repeat(64)))
        assertEquals(2 + 8 + 64 + 64, max.length) // "0x" + selector + 2 words
    }
}
