package com.mobilemcp.pro

import java.util.Locale

object PersianInput {
    fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک').replace('\u200c', ' ')
        .replace(Regex("[\\u064b-\\u065f\\u0670\\u0640]"), "")
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
        "گوگل" to "Google", "google" to "Google", "گوگل کروم" to "Chrome", "google chrome" to "Chrome",
        "روبیکا" to "Rubika", "rubika" to "Rubika", "ایتا" to "Eitaa", "eitaa" to "Eitaa",
        "بله" to "Bale", "bale" to "Bale", "سروش" to "Soroush", "soroush" to "Soroush",
        "بازار" to "Bazaar", "bazaar" to "Bazaar", "شاد" to "Shad", "shad" to "Shad"
    )

    fun appAliases(name: String): Set<String> {
        val key = normalize(name)
        val canonical = appNames[key] ?: name.trim()
        return (appNames.filterValues { it.equals(canonical, ignoreCase = true) }.keys + canonical + name)
            .map(::normalize).toSet()
    }

    private fun commandText(text: String): String = normalize(text)
        .replace(Regex("^(?:(?:لطفا|حالا|الان|میشه|می شه|میتونی|می تونی|میخوام|می خوام|please|now)\\s+)+"), "")

    fun simpleKeyTarget(text: String): String? = when (commandText(text)) {
        "برگرد", "برو عقب", "برگرد عقب", "عقب", "back", "go back" -> "back"
        "برو خانه", "برو خونه", "برو صفحه اصلی", "صفحه اصلی رو باز کن", "home", "go home" -> "home"
        "برنامه های اخیر", "برنامه های اخیر رو باز کن", "recents", "recent apps" -> "recents"
        "اعلان ها رو باز کن", "اعلان ها را باز کن", "notifications" -> "notifications"
        else -> null
    }

    fun simpleAppTarget(text: String): String? {
        if (Regex("(?:https?://|www\\.|[a-z0-9-]+\\.(?:com|org|net|ir|io|dev)(?:/|\\s|$))", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)) return null
        val value = commandText(text)
        if (simpleKeyTarget(value) != null) return null
        // Extract a whole launch request, including arbitrary installed app
        // names. Never throw away an extra task after the app's name.
        val patterns = listOf(
            Regex("^(?:برو|بری|بریم)(?:\\s+(?:تو|توی|داخل|به))?\\s+(.+)$"),
            Regex("^(?:go(?:\\s+(?:to|into))?|open|launch|start)\\s+(.+)$"),
            Regex("^وارد\\s+(.+?)\\s+(?:شو|بشو)$"),
            Regex("^باز کن\\s+(.+)$"),
            Regex("^(.+?)\\s+(?:(?:رو|را)\\s+)?(?:باز کن|باز کنی|بازش کن|بیار بالا|اجرا کن)$")
        )
        var name = patterns.firstNotNullOfOrNull { it.matchEntire(value)?.groupValues?.get(1) } ?: return null
        name = name.replace(Regex("^(?:برنامه|اپلیکیشن|اپ|application|app)\\s+"), "")
            .trim().trim('«', '»', '"', '\'').trim()
        if (name.isBlank() || name.split(' ').size > 6) return null
        if (Regex("\\b(?:and|or|then|search|type|send)\\b|(?:^|\\s)(?:و|یا|بعد|سپس|سرچ|جستجو|بنویس|تایپ|بفرست|ارسال|چت|کانال|گروه)(?:\\s|$)").containsMatchIn(name)) return null
        if (Regex("^(?:صفحه|بخش|پروفایل|پوشه|مخاطب|لینک|سایت|وب سایت)\\s").containsMatchIn(name)) return null
        if (Regex("^(?:تنظیمات|settings)\\s").containsMatchIn(name)) return null
        if (name.contains("http") || name.contains('/') || name.contains("www ")) return null
        return appNames[normalize(name)] ?: name
    }

    fun isPhoneTask(text: String, phoneContext: Boolean = false): Boolean {
        val value = commandText(text)
        if (simpleAppTarget(text) != null || simpleKeyTarget(text) != null) return true
        val device = appNames.keys + listOf("گوشی", "صفحه", "دکمه", "چت", "مخاطب", "برنامه", "اپلیکیشن", "اپ ", "وای فای", "بلوتوث", "wifi", "bluetooth")
        val action = listOf("باز کن", "برو ", "بری ", "وارد ", "بزن", "کلیک", "اسکرول", "تایپ", "ارسال", "بفرست", "پیام بده", "حذف", "پاک کن",
            "تماس بگیر", "زنگ بزن", "فعال کن", "خاموش کن", "روشن کن", "سرچ", "جستجو", "search", "open ", "launch ", "click ", "tap ")
        if (device.any { value.contains(it) } && action.any { value.contains(it) }) return true
        if (Regex("^(?:برو|بری|وارد|باز کن|open|launch|go)\\s+").containsMatchIn(value)) return true
        if (value.contains("برنامه") && action.any { value.contains(it) }) return true
        if (value in setOf("برگرد", "برو خانه", "برو عقب", "back", "go home", "home", "recents")) return true
        if (listOf("تایپ کن ", "کلیک کن ", "اسکرول ", "tap ", "click ", "scroll ", "type ")
                .any { value.startsWith(it) }) return true
        if (phoneContext && Regex("^(?:(?:حالا|الان)\\s+)?(?:روی|داخل|توی|تو|به|این|اولی|دومی)\\s+.*(?:بزن|کلیک|انتخاب|باز کن|برو|بنویس|بفرست|پیام بده)").containsMatchIn(value)) return true
        return phoneContext && listOf("بنویس", "پاک کن", "بفرست", "حذف", "ارسال", "کلیک", "اسکرول", "type ", "send ", "scroll ", "tap ")
            .any { value.startsWith(it) || value.startsWith("حالا $it") }
    }
}
