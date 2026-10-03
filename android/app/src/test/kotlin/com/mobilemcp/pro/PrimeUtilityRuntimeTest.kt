package com.mobilemcp.pro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeUtilityRuntimeTest {
    @Test
    fun calculatorUsesNormalPrecedence() {
        assertEquals(
            "14",
            PrimeUtilityRuntime.calculate(
                "2 + 3 * 4"
            )
        )
        assertEquals(
            "20",
            PrimeUtilityRuntime.calculate(
                "(2 + 3) * 4"
            )
        )
        assertEquals(
            "512",
            PrimeUtilityRuntime.calculate(
                "2^3^2"
            )
        )
    }

    @Test
    fun calculatorAcceptsPersianDigits() {
        val result =
            PrimeUtilityRuntime.tryHandle(
                "حساب کن ۲۵ * ۴"
            )
        assertEquals(
            "calculator",
            result?.tool
        )
        assertEquals(
            "100",
            result?.text
        )
    }

    @Test
    fun unitConversionSupportsPersianAndTemperature() {
        assertEquals(
            "5000 m",
            PrimeUtilityRuntime
                .tryHandle(
                    "5 کیلومتر به متر"
                )
                ?.text
        )
        assertEquals(
            "32 °F",
            PrimeUtilityRuntime.convertUnit(
                0.0,
                "celsius",
                "fahrenheit"
            )
        )
    }

    @Test
    fun jsonHashAndRegexAreDeterministic() {
        assertTrue(
            PrimeUtilityRuntime.formatJson(
                """{"x":1,"y":2}"""
            ).contains("\n")
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            PrimeUtilityRuntime.sha256(
                "hello"
            )
        )

        val regex =
            PrimeUtilityRuntime.execute(
                "regex_find",
                JSONObject()
                    .put("pattern", "\\d+")
                    .put("text", "a12 b34")
            )

        assertTrue(regex.text.contains("12"))
        assertTrue(regex.text.contains("34"))
    }

    @Test
    fun parserRejectsCodeLikeInput() {
        try {
            PrimeUtilityRuntime.calculate(
                "1 + Runtime.exec(1)"
            )
            throw AssertionError(
                "Expected parser rejection"
            )
        } catch (_: IllegalArgumentException) {
        }
    }
}
