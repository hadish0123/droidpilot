package com.mobilemcp.pro.context

internal data class ContextMessage(
    val role: String,
    val content: String
)

internal class ConversationContextManager(
    private val maxMessages: Int = 24,
    private val maxEstimatedTokens: Int = 6_000
) {
    private val messages = ArrayDeque<ContextMessage>()

    init {
        require(maxMessages >= 2)
        require(maxEstimatedTokens >= 256)
    }

    fun clear() {
        messages.clear()
    }

    fun restore(lines: List<Pair<String, String>>) {
        messages.clear()
        lines.forEach { (role, content) ->
            if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                messages.addLast(ContextMessage(role, content))
            }
        }
        prune()
    }

    fun appendTurn(user: String, assistant: String) {
        if (user.isNotBlank()) {
            messages.addLast(ContextMessage("user", user))
        }
        if (assistant.isNotBlank()) {
            messages.addLast(ContextMessage("assistant", assistant))
        }
        prune()
    }

    fun recent(limit: Int): List<ContextMessage> {
        if (limit <= 0 || messages.isEmpty()) return emptyList()
        return messages.toList().takeLast(limit)
    }

    internal fun estimatedTokens(): Int =
        messages.sumOf { estimateTokens(it.content) }

    internal fun size(): Int = messages.size

    private fun prune() {
        while (
            messages.size > maxMessages ||
            estimatedTokens() > maxEstimatedTokens
        ) {
            if (messages.isEmpty()) break
            messages.removeFirst()
        }
    }

    private fun estimateTokens(text: String): Int =
        ((text.length + 3) / 4).coerceAtLeast(1)
}
