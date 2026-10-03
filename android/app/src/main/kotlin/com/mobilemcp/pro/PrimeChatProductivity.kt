package com.mobilemcp.pro

internal object PrimeChatProductivity {
    fun exportText(
        chat: PrimeChatSummary,
        messages: List<PrimeStoredMessage>
    ): String = buildString {
        appendLine("PRIME P6")
        append("Conversation: ")
        appendLine(chat.title)
        appendLine()

        messages.forEach { message ->
            val role = when (message.role) {
                "user" -> "You"
                "assistant" -> "PRIME"
                else -> message.role
            }
            append(role)
            append(": ")
            appendLine(message.content.trim())
            appendLine()
        }
    }.trimEnd()

    fun snippet(
        content: String,
        maxChars: Int = 120
    ): String {
        val clean = content
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return if (clean.length <= maxChars) {
            clean
        } else {
            clean.take(
                maxChars.coerceAtLeast(12)
            ).trimEnd() + "…"
        }
    }
}
