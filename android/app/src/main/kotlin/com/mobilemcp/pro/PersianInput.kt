package com.mobilemcp.pro

import java.util.Locale

object PersianInput {
    fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک').replace('\u200c', ' ')
        .replace(Regex("[.!؟?،,؛:]+"), " ").replace(Regex("\\s+"), " ").trim()
    fun isPositiveConfirmation(text: String): Boolean = normalize(text) in setOf(
        "بله", "آره", "اره", "اوکی", "باشه", "تایید", "تأیید", "انجام بده", "ارسال کن",
        "yes", "ok", "okay", "confirm", "do it"
    )
    fun isNegativeConfirmation(text: String): Boolean = normalize(text) in setOf(
        "نه", "خیر", "لغو", "بیخیال", "بی خیال", "cancel", "no", "stop"
    )
    private val appNames = mapOf(
        "تلگرام" to "Telegram", "telegram" to "Telegram", "کروم" to "Chrome", "chrome" to "Chrome",
        "واتساپ" to "WhatsApp", "واتس اپ" to "WhatsApp", "whatsapp" to "WhatsApp",
        "اینستاگرام" to "Instagram", "instagram" to "Instagram", "یوتیوب" to "YouTube", "youtube" to "YouTube",
        "جیمیل" to "Gmail", "gmail" to "Gmail", "تنظیمات" to "Settings", "settings" to "Settings",
        "گوگل" to "Google", "google" to "Google"
    )
    fun simpleAppTarget(text: String): String? {
        val value = normalize(text)
        // Match the entire utterance. Substrings used to discard extra tasks
        // such as opening a particular chat or visiting a website.
        for ((name, app) in appNames) {
            if (value in setOf("$name رو باز کن", "$name را باز کن", "$name باز کن",
                    "باز کن $name", "برو تو $name", "برو داخل $name", "برو به $name", "open $name", "launch $name")) return app
        }
        return null
    }

    fun isPhoneTask(text: String, phoneContext: Boolean = false): Boolean {
        val value = normalize(text)
        val device = appNames.keys + listOf("گوشی", "صفحه", "دکمه", "چت", "مخاطب", "وای فای", "بلوتوث", "wifi", "bluetooth")
        val action = listOf("باز کن", "برو ", "بزن", "کلیک", "اسکرول", "تایپ", "ارسال", "بفرست", "پیام بده", "حذف", "پاک کن",
            "تماس بگیر", "زنگ بزن", "فعال کن", "خاموش کن", "روشن کن", "سرچ", "جستجو", "search", "open ", "launch ", "click ", "tap ")
        if (device.any { value.contains(it) } && action.any { value.contains(it) }) return true
        if (value in setOf("برگرد", "برو خانه", "برو عقب", "back", "go home", "home", "recents")) return true
        return phoneContext && listOf("بنویس", "پاک کن", "بفرست", "حذف", "ارسال", "کلیک", "اسکرول", "type ", "send ", "scroll ", "tap ")
            .any { value.startsWith(it) || value.startsWith("حالا $it") }
    }
}
