package com.mobilemcp.pro

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

internal data class PrimeUtilityResponse(
    val tool: String,
    val text: String
)

internal object PrimeUtilityRuntime {
    private data class UnitDef(
        val dimension: String,
        val factor: Double,
        val canonical: String
    )

    private val units = buildMap<String, UnitDef> {
        fun add(
            dimension: String,
            factor: Double,
            canonical: String,
            vararg aliases: String
        ) {
            val unit = UnitDef(
                dimension,
                factor,
                canonical
            )
            aliases.forEach {
                put(normalizeWords(it), unit)
            }
        }

        add("length", 1.0, "m",
            "m", "meter", "meters", "متر")
        add("length", 1_000.0, "km",
            "km", "kilometer", "kilometers", "کیلومتر")
        add("length", 0.01, "cm",
            "cm", "centimeter", "centimeters", "سانتیمتر", "سانتی متر")
        add("length", 0.001, "mm",
            "mm", "millimeter", "millimeters", "میلیمتر", "میلی متر")
        add("length", 0.0254, "in",
            "in", "inch", "inches", "اینچ")
        add("length", 0.3048, "ft",
            "ft", "foot", "feet", "فوت")
        add("length", 1_609.344, "mi",
            "mi", "mile", "miles", "مایل")

        add("mass", 1.0, "kg",
            "kg", "kilogram", "kilograms", "کیلوگرم")
        add("mass", 0.001, "g",
            "g", "gram", "grams", "گرم")
        add("mass", 0.45359237, "lb",
            "lb", "lbs", "pound", "pounds", "پوند")

        add("time", 1.0, "s",
            "s", "sec", "second", "seconds", "ثانیه")
        add("time", 60.0, "min",
            "min", "minute", "minutes", "دقیقه")
        add("time", 3_600.0, "h",
            "h", "hr", "hour", "hours", "ساعت")

        add("data", 1.0, "B",
            "b", "byte", "bytes", "بایت")
        add("data", 1_024.0, "KB",
            "kb", "kib", "kilobyte", "کیلوبایت")
        add("data", 1_048_576.0, "MB",
            "mb", "mib", "megabyte", "مگابایت")
        add("data", 1_073_741_824.0, "GB",
            "gb", "gib", "gigabyte", "گیگابایت")
    }

    private val temperatures = setOf(
        "c", "°c", "celsius", "سلسیوس", "سانتیگراد",
        "f", "°f", "fahrenheit", "فارنهایت",
        "k", "kelvin", "کلوین"
    )

    fun tryHandle(
        input: String
    ): PrimeUtilityResponse? {
        val clean = input.trim()
        if (clean.isBlank()) return null

        calculatorExpression(clean)?.let {
            return PrimeUtilityResponse(
                "calculator",
                calculate(it)
            )
        }

        unitRequest(clean)?.let {
            (value, from, to) ->
            return PrimeUtilityResponse(
                "unit_convert",
                convertUnit(
                    value,
                    from,
                    to
                )
            )
        }

        Regex(
            """(?is)^sha-?256\s*[:：]?\s+(.+)$"""
        ).matchEntire(clean)?.let {
            return PrimeUtilityResponse(
                "sha256",
                sha256(
                    it.groupValues[1]
                )
            )
        }

        Regex(
            """(?is)^(?:format\s+json|pretty\s+json|json\s+format|json\s*مرتب)\s*[:：]?\s*(.+)$"""
        ).matchEntire(clean)?.let {
            return PrimeUtilityResponse(
                "json_format",
                formatJson(
                    it.groupValues[1]
                )
            )
        }

        return null
    }

    fun execute(
        name: String,
        params: JSONObject
    ): PrimeUtilityResponse =
        when (name) {
            "calculator" ->
                PrimeUtilityResponse(
                    name,
                    calculate(
                        params.getString(
                            "expression"
                        )
                    )
                )

            "unit_convert" ->
                PrimeUtilityResponse(
                    name,
                    convertUnit(
                        params.getDouble("value"),
                        params.getString("from"),
                        params.getString("to")
                    )
                )

            "json_format" ->
                PrimeUtilityResponse(
                    name,
                    formatJson(
                        params.getString("json")
                    )
                )

            "sha256" ->
                PrimeUtilityResponse(
                    name,
                    sha256(
                        params.getString("text")
                    )
                )

            "regex_find" ->
                PrimeUtilityResponse(
                    name,
                    regexFind(
                        params.getString("pattern"),
                        params.getString("text")
                    )
                )

            else ->
                throw IllegalArgumentException(
                    "Unknown utility tool: $name"
                )
        }

    fun calculate(
        expression: String
    ): String {
        val clean = normalizeDigits(
            expression
        )
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .replace(',', '.')
            .trim()

        require(clean.length <= 300) {
            "Expression is too long"
        }

        val value = ArithmeticParser(
            clean
        ).parse()

        require(value.isFinite()) {
            "Result is not finite"
        }
        return formatNumber(value)
    }

    fun convertUnit(
        value: Double,
        fromRaw: String,
        toRaw: String
    ): String {
        require(value.isFinite()) {
            "Value must be finite"
        }

        val from = normalizeWords(fromRaw)
        val to = normalizeWords(toRaw)

        if (
            from in temperatures &&
            to in temperatures
        ) {
            val c = toCelsius(
                value,
                from
            )
            return formatNumber(
                fromCelsius(c, to)
            ) + " " + temperatureName(to)
        }

        val source = units[from]
            ?: throw IllegalArgumentException(
                "Unsupported unit: $fromRaw"
            )
        val target = units[to]
            ?: throw IllegalArgumentException(
                "Unsupported unit: $toRaw"
            )

        require(
            source.dimension ==
                target.dimension
        ) {
            "Units are not compatible"
        }

        return formatNumber(
            value *
                source.factor /
                target.factor
        ) + " " + target.canonical
    }

    fun formatJson(
        raw: String
    ): String {
        val clean = raw.trim()
        require(clean.length <= 200_000) {
            "JSON input is too large"
        }

        return when {
            clean.startsWith("{") ->
                JSONObject(clean)
                    .toString(2)
            clean.startsWith("[") ->
                JSONArray(clean)
                    .toString(2)
            else ->
                throw IllegalArgumentException(
                    "Input is not a JSON object or array"
                )
        }
    }

    fun sha256(
        text: String
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                text.toByteArray(
                    Charsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 255
                )
            }

    fun regexFind(
        pattern: String,
        text: String
    ): String {
        require(pattern.length <= 500) {
            "Regex is too long"
        }
        require(text.length <= 100_000) {
            "Regex input is too long"
        }

        val matches = Regex(pattern)
            .findAll(text)
            .take(50)
            .map { it.value }
            .toList()

        if (matches.isEmpty()) {
            return "0 matches"
        }

        return buildString {
            append(matches.size)
            appendLine(
                if (matches.size == 1) {
                    " match"
                } else {
                    " matches"
                }
            )
            matches.forEachIndexed {
                index,
                value ->
                append(index + 1)
                append(". ")
                appendLine(
                    value.take(500)
                )
            }
        }.trimEnd()
    }

    private fun calculatorExpression(
        input: String
    ): String? {
        val normalized =
            normalizeDigits(input)

        Regex(
            """(?i)^(?:calc(?:ulate)?|calculator|حساب\s*کن|محاسبه\s*کن|حاصل)\s*[:：]?\s*(.+)$"""
        ).matchEntire(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val compact =
            normalized.replace(" ", "")
        if (
            compact.length in 3..300 &&
            compact.any {
                it in "+-*/%^×÷−"
            } &&
            compact.all {
                it.isDigit() ||
                    it in ".,+-*/%^()×÷−"
            }
        ) {
            return compact
        }

        return null
    }

    private fun unitRequest(
        input: String
    ): Triple<Double, String, String>? {
        val normalized =
            normalizeDigits(input)
                .trim()

        val match = Regex(
            """(?i)^(?:convert\s+|تبدیل\s+)?(-?\d+(?:\.\d+)?)\s+(.+?)\s+(?:to|به)\s+(.+?)\s*$"""
        ).matchEntire(normalized)
            ?: return null

        val value = match
            .groupValues[1]
            .toDoubleOrNull()
            ?: return null
        val from = match
            .groupValues[2]
            .trim()
        val to = match
            .groupValues[3]
            .trim()

        val nf = normalizeWords(from)
        val nt = normalizeWords(to)

        if (
            nf !in units &&
            nf !in temperatures
        ) return null
        if (
            nt !in units &&
            nt !in temperatures
        ) return null

        return Triple(
            value,
            from,
            to
        )
    }

    private fun normalizeDigits(
        value: String
    ): String = buildString {
        value.forEach {
            append(
                when (it) {
                    '۰', '٠' -> '0'
                    '۱', '١' -> '1'
                    '۲', '٢' -> '2'
                    '۳', '٣' -> '3'
                    '۴', '٤' -> '4'
                    '۵', '٥' -> '5'
                    '۶', '٦' -> '6'
                    '۷', '٧' -> '7'
                    '۸', '٨' -> '8'
                    '۹', '٩' -> '9'
                    else -> it
                }
            )
        }
    }

    private fun normalizeWords(
        value: String
    ): String =
        value
            .trim()
            .lowercase()
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('\u200c', ' ')
            .replace(Regex("\\s+"), " ")

    private fun toCelsius(
        value: Double,
        unit: String
    ): Double = when (unit) {
        "f", "°f", "fahrenheit",
        "فارنهایت" ->
            (value - 32.0) *
                5.0 / 9.0
        "k", "kelvin", "کلوین" ->
            value - 273.15
        else -> value
    }

    private fun fromCelsius(
        value: Double,
        unit: String
    ): Double = when (unit) {
        "f", "°f", "fahrenheit",
        "فارنهایت" ->
            value * 9.0 /
                5.0 + 32.0
        "k", "kelvin", "کلوین" ->
            value + 273.15
        else -> value
    }

    private fun temperatureName(
        unit: String
    ): String = when (unit) {
        "f", "°f", "fahrenheit",
        "فارنهایت" -> "°F"
        "k", "kelvin", "کلوین" -> "K"
        else -> "°C"
    }

    private fun formatNumber(
        value: Double
    ): String {
        val rounded = round(value)
        if (
            abs(value - rounded) <
            1e-10 &&
            rounded >= Long.MIN_VALUE &&
            rounded <= Long.MAX_VALUE
        ) {
            return rounded.toLong()
                .toString()
        }

        return BigDecimal
            .valueOf(value)
            .stripTrailingZeros()
            .toPlainString()
    }

    private class ArithmeticParser(
        private val text: String
    ) {
        private var index = 0

        fun parse(): Double {
            val value = expression()
            spaces()
            require(index == text.length) {
                "Unexpected input at position $index"
            }
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                spaces()
                value = when {
                    take('+') -> value + term()
                    take('-') -> value - term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = power()
            while (true) {
                spaces()
                when {
                    take('*') ->
                        value *= power()
                    take('/') -> {
                        val divisor = power()
                        require(divisor != 0.0) {
                            "Division by zero"
                        }
                        value /= divisor
                    }
                    take('%') -> {
                        val divisor = power()
                        require(divisor != 0.0) {
                            "Modulo by zero"
                        }
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun power(): Double {
            var value = unary()
            spaces()
            if (take('^')) {
                value = value.pow(power())
            }
            return value
        }

        private fun unary(): Double {
            spaces()
            return when {
                take('+') -> unary()
                take('-') -> -unary()
                else -> primary()
            }
        }

        private fun primary(): Double {
            spaces()
            if (take('(')) {
                val value = expression()
                spaces()
                require(take(')')) {
                    "Missing closing parenthesis"
                }
                return value
            }

            val start = index
            var dot = false
            while (index < text.length) {
                val char = text[index]
                when {
                    char.isDigit() ->
                        index += 1
                    char == '.' &&
                        !dot -> {
                        dot = true
                        index += 1
                    }
                    else -> break
                }
            }

            require(index > start) {
                "Expected number at position $index"
            }
            return text.substring(
                start,
                index
            ).toDouble()
        }

        private fun take(
            char: Char
        ): Boolean {
            if (
                index < text.length &&
                text[index] == char
            ) {
                index += 1
                return true
            }
            return false
        }

        private fun spaces() {
            while (
                index < text.length &&
                text[index].isWhitespace()
            ) {
                index += 1
            }
        }
    }
}
