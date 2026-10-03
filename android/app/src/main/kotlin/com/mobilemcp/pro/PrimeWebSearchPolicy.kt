package com.mobilemcp.pro

import java.util.Locale

internal object PrimeWebSearchPolicy {
    private val requiredHints = listOf(
        "جستجو کن",
        "جست‌وجو کن",
        "سرچ کن",
        "در وب",
        "در اینترنت",
        "وب سرچ",
        "آخرین",
        "جدیدترین",
        "به روز",
        "به‌روز",
        "امروز",
        "الان",
        "خبر",
        "اخبار",
        "قیمت فعلی",
        "نرخ فعلی",
        "current",
        "latest",
        "today",
        "right now",
        "recent",
        "news",
        "search the web",
        "web search",
        "look up online",
        "up to date",
        "up-to-date"
    )

    private val autoHints = listOf(
        "تحقیق کن",
        "بررسی منابع",
        "منبع بده",
        "با منبع",
        "research",
        "sources",
        "find sources"
    )

    fun modeFor(
        input: String
    ): AiWebSearchMode {
        val normalized = input
            .lowercase(Locale.ROOT)
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('\u200c', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        if (
            requiredHints.any {
                normalized.contains(
                    normalize(it)
                )
            }
        ) {
            return AiWebSearchMode.REQUIRED
        }

        if (
            autoHints.any {
                normalized.contains(
                    normalize(it)
                )
            }
        ) {
            return AiWebSearchMode.AUTO
        }

        return AiWebSearchMode.DISABLED
    }

    private fun normalize(
        value: String
    ): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('\u200c', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
