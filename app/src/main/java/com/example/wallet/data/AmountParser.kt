package com.example.wallet.data

import java.math.BigDecimal

/**
 * Reads an amount typed on a phone keyboard. Many locales produce a comma as the decimal separator ("0,01"), which
 * `String.toBigDecimalOrNull()` rejects, so the app kept saying "Enter a valid amount".
 *
 * Accepted: "0.01", ".5", "5.", "0,01" (one comma and no dot: a decimal comma), "1,234.56" (commas only as thousands
 * separators in groups of 3 before a dot), and spaces / non-breaking spaces anywhere (thousands separators).
 * Refused (null): "1,234" style ambiguity with several commas and no dot, letters, exponents, signs, empty text.
 * Only ASCII digits are accepted.
 */
object AmountParser {
    private val THOUSANDS = Regex("""\d{1,3}(,\d{3})+(\.\d*)?""")
    private val PLAIN = Regex("""\d*\.?\d*""")

    fun parse(text: String?): BigDecimal? {
        if (text == null) return null
        var t = text.filterNot { it == ' ' || it == '\u00a0' || it == '\u202f' || it == '\u2009' }
        t = t.replace('\u066b', '.').replace('\u060c', ',') // Arabic decimal separator / comma
        if (t.isEmpty()) return null
        val commas = t.count { it == ',' }
        val dots = t.count { it == '.' }
        t = when {
            commas == 0 -> t
            dots == 0 && commas == 1 -> t.replace(',', '.')            // decimal comma
            dots >= 1 && THOUSANDS.matches(t) -> t.replace(",", "")    // 1,234.56
            else -> return null                                        // "1,234,567", "1,5.2": ambiguous
        }
        if (!PLAIN.matches(t) || t.none { it in '0'..'9' }) return null
        return t.toBigDecimalOrNull()
    }
}
