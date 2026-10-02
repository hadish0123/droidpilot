package com.mobilemcp.pro

data class InstalledApp(val label: String, val packageName: String)

sealed class AppMatch {
    data class Found(val app: InstalledApp) : AppMatch()
    data class Ambiguous(val apps: List<InstalledApp>) : AppMatch()
    object Missing : AppMatch()
}

/** Resolve the live launcher inventory, independent of the phone's language. */
object AppCatalog {
    private val preferredPackages = mapOf(
        "telegram" to "org.telegram.messenger", "chrome" to "com.android.chrome",
        "settings" to "com.android.settings", "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android", "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm"
    )

    fun match(name: String, installed: List<InstalledApp>): AppMatch {
        val apps = installed.distinctBy { it.packageName }
        val key = PersianInput.normalize(name)
        if (key.isBlank()) return AppMatch.Missing
        apps.firstOrNull { it.packageName.equals(name.trim(), ignoreCase = true) }
            ?.let { return AppMatch.Found(it) }
        val aliases = PersianInput.appAliases(name)
        preferredPackages.entries.firstOrNull { it.key in aliases }?.value?.let { preferred ->
            apps.firstOrNull { it.packageName == preferred }?.let { return AppMatch.Found(it) }
        }
        val exact = apps.filter { PersianInput.normalize(it.label) in aliases }
        if (exact.isNotEmpty()) return choose(exact)
        if ("google" in aliases) return AppMatch.Missing
        // Word boundaries prevent "Google" from silently becoming an
        // unrelated substring match. Multiple candidates need clarification.
        val partial = apps.filter { app ->
            val label = " ${PersianInput.normalize(app.label)} "
            aliases.any { alias -> label.contains(" $alias ") }
        }
        return choose(partial)
    }

    private fun choose(matches: List<InstalledApp>): AppMatch = when (matches.size) {
        0 -> AppMatch.Missing
        1 -> AppMatch.Found(matches.single())
        else -> AppMatch.Ambiguous(matches.sortedBy { it.label })
    }
}
