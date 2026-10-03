package com.mobilemcp.pro

import java.util.Locale

internal object PrimeWebSearchIntent {
    private val explicitPhrases = listOf(
        "search the web",
        "search web",
        "web search",
        "look up online",
        "search online",
        "جستجو در وب",
        "جستجوی وب",
        "تو وب بگرد",
        "در وب بگرد",
        "در اینترنت بگرد",
        "اینترنت رو بگرد",
        "از اینترنت پیدا کن"
    )

    private val freshnessPhrases = listOf(
        "latest",
        "most recent",
        "recent news",
        "today",
        "current version",
        "current release",
        "what's new",
        "whats new",
        "آخرین",
        "جدیدترین",
        "تازه ترین",
        "تازه‌ترین",
        "امروز",
        "خبرهای جدید",
        "اخبار جدید",
        "نسخه فعلی",
        "نسخهٔ فعلی",
        "نسخه جدید"
    )

    fun shouldSearch(input: String): Boolean {
        val normalized = normalize(input)
        if (normalized.isBlank()) return false

        return explicitPhrases.any {
            normalized.contains(
                normalize(it)
            )
        } || freshnessPhrases.any {
            normalized.contains(
                normalize(it)
            )
        }
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
