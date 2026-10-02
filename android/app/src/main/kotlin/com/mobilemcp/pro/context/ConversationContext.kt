package com.mobilemcp.pro.context

internal data class ContextMessage(
    val role: String,
    val content: String
)

/**
 * Bounded conversation buffer used by orchestration.
 *
 * This deliberately starts small and deterministic. Token budgeting,
 * summarization and memory retrieval can be layered behind this boundary
 * without changing provider or tool code.
 */
internal class ConversationContext(
    private val maxMessages: Int = 10
) {
    init {
        require(maxMessages > 0) { "maxMessages must be positive" }
    }

    private val messages = ArrayDeque<ContextMessage>()

    fun clear() {
        messages.clear()
    }

    fun restore(lines: List<Pair<String, String>>, maxRestoreMessages: Int = 12) {
        require(maxRestoreMessages > 0) { "maxRestoreMessages must be positive" }
        messages.clear()

        lines.takeLast(maxRestoreMessages).forEach { (role, content) ->
            if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                append(ContextMessage(role, content))
            }
        }
    }

    fun remember(user: String, assistant: String) {
        append(ContextMessage("user", user))
        append(ContextMessage("assistant", assistant))
    }

    fun recent(limit: Int): List<ContextMessage> {
        require(limit >= 0) { "limit must not be negative" }
        if (limit == 0) return emptyList()
        return messages.toList().takeLast(limit)
    }

    fun size(): Int = messages.size

    private fun append(message: ContextMessage) {
        messages.addLast(message)
        while (messages.size > maxMessages) {
            messages.removeFirst()
        }
    }
}
