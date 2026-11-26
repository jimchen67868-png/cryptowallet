package com.example.wallet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class AmountParserTest {
    private fun ok(text: String, expected: String) =
        assertEquals("'$text'", 0, BigDecimal(expected).compareTo(AmountParser.parse(text)!!))

    @Test fun dotDecimals() {
        ok("0.01", "0.01"); ok("1", "1"); ok(".5", "0.5"); ok("5.", "5"); ok("  0.25  ", "0.25"); ok("0.00000001", "0.00000001")
    }

    @Test fun decimalComma() {
        ok("0,01", "0.01"); ok(",5", "0.5"); ok("12,5", "12.5"); ok("0,000001", "0.000001")
    }

    @Test fun thousandsSeparators() {
        ok("1,234.56", "1234.56"); ok("12,345,678.9", "12345678.9"); ok("1 234", "1234"); ok("1\u00a0234,5", "1234.5")
    }

    @Test fun arabicSeparators() {
        ok("0\u066b01", "0.01")
    }

    @Test fun refusesAmbiguousOrInvalid() {
        for (bad in listOf("", "  ", ".", ",", "abc", "1e5", "-1", "+1", "1.2.3", "1,234,567", "1,5.2", "12,34.5", "1,2,3", "0x10", "٠٫٥", "1..2", "1,,2")) {
            assertNull("'$bad'", AmountParser.parse(bad))
        }
        assertNull(AmountParser.parse(null))
    }
}
