package com.block154.courierpilot

/**
 * OCR reads the zero in Lithuanian postcodes as a letter: `LTO1117` for `LT01117`. The same stop
 * then appeared twice (once from Accessibility, once from OCR). Only `LT` + five characters with
 * at least three real digits are touched, so ordinary words never change.
 */
internal object OcrPostcodeRepair {
    private val postcode = Regex("""(?i)\b(LT)(\s?-?\s?)([0-9OQD]{5})\b""")

    fun repair(text: String): String = postcode.replace(text) { match ->
        val digits = match.groupValues[3]
        if (digits.count(Char::isDigit) < 3) return@replace match.value
        val fixed = digits.map { if (it.isDigit()) it else '0' }.joinToString("")
        match.groupValues[1] + match.groupValues[2] + fixed
    }
}
