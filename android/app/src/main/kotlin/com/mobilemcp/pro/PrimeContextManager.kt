package com.mobilemcp.pro

import kotlin.math.ceil

internal data class PrimeContextWindow(
    val messages: List<AiMessage>,
    val estimatedTokens: Int,
    val droppedMessages: Int
)

/**
 * Provider-neutral conversation context manager.
 *
 * The database remains the source of truth for full chat history. This class
 * only owns the bounded in-memory window sent to model providers.
 */
internal class PrimeContextManager(
    private val maxStoredMessages: Int = 80,
    private val maxRequestMessages: Int = 20,
    private val maxEstimatedTokens: Int = 8_000,
    private val maxMessageChars: Int = 16_000
) {
    init {
        require(maxStoredMessages > 0)
        require(maxRequestMessages > 0)
        require(maxEstimatedTokens >= 512)
        require(maxMessageChars >= 256)
    }

    private val history = ArrayDeque<AiMessage>()

    fun clear() {
        history.clear()
    }

    fun restore(lines: List<Pair<String, String>>) {
        clear()

        lines.asSequence()
            .filter { (role, content) ->
                (role == "user" || role == "assistant") &&
                    content.isNotBlank()
            }
            .takeLastCompat(maxStoredMessages)
            .forEach { (role, content) ->
                history.addLast(
                    AiMessage(
                        role = role,
                        content = clipStoredMessage(content)
                    )
                )
            }
    }

    fun remember(user: String, assistant: String) {
        add("user", user)
        add("assistant", assistant)
    }

    fun buildWindow(
        currentPrompt: String,
        exclude: (AiMessage) -> Boolean = { false }
    ): PrimeContextWindow {
        val reservedForPrompt = ApproximateTokenEstimator.estimate(currentPrompt)
        val historyBudget = (maxEstimatedTokens - reservedForPrompt)
            .coerceAtLeast(512)

        val selectedReversed = mutableListOf<AiMessage>()
        var tokens = 0

        for (message in history.asReversed()) {
            if (exclude(message)) continue
            if (selectedReversed.size >= maxRequestMessages) break

            val messageTokens =
                ApproximateTokenEstimator.estimate(message.content) + 4

            if (
                selectedReversed.isNotEmpty() &&
                tokens + messageTokens > historyBudget
            ) {
                break
            }

            if (
                selectedReversed.isEmpty() &&
                messageTokens > historyBudget
            ) {
                val clipped = clipForTokenBudget(
                    message.content,
                    historyBudget.coerceAtLeast(64)
                )
                selectedReversed += message.copy(content = clipped)
                tokens += ApproximateTokenEstimator.estimate(clipped) + 4
                break
            }

            selectedReversed += message
            tokens += messageTokens
        }

        val selected = selectedReversed.asReversed()

        return PrimeContextWindow(
            messages = selected,
            estimatedTokens = tokens + reservedForPrompt,
            droppedMessages = (history.size - selected.size).coerceAtLeast(0)
        )
    }

    internal fun storedMessageCount(): Int = history.size

    private fun add(role: String, content: String) {
        if (content.isBlank()) return

        history.addLast(
            AiMessage(
                role = role,
                content = clipStoredMessage(content)
            )
        )

        while (history.size > maxStoredMessages) {
            history.removeFirst()
        }
    }

    private fun clipStoredMessage(content: String): String {
        val clean = content.trim()
        if (clean.length <= maxMessageChars) return clean

        val marker = "\n…[context clipped]…\n"
        val remaining = (maxMessageChars - marker.length).coerceAtLeast(2)
        val head = remaining / 2
        val tail = remaining - head
        return clean.take(head) + marker + clean.takeLast(tail)
    }

    private fun clipForTokenBudget(
        content: String,
        tokenBudget: Int
    ): String {
        if (ApproximateTokenEstimator.estimate(content) <= tokenBudget) {
            return content
        }

        var low = 1
        var high = content.length
        var best = ""

        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = clippedCandidate(content, mid)

            if (ApproximateTokenEstimator.estimate(candidate) <= tokenBudget) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return if (best.isNotEmpty()) best else content.take(1)
    }

    private fun clippedCandidate(content: String, maxChars: Int): String {
        if (maxChars >= content.length) return content

        val marker = "\n…[context clipped]…\n"
        if (maxChars <= marker.length + 2) {
            return content.take(maxChars.coerceAtLeast(1))
        }

        val payload = maxChars - marker.length
        val head = payload / 2
        val tail = payload - head
        return content.take(head) + marker + content.takeLast(tail)
    }
}

internal object ApproximateTokenEstimator {
    /**
     * Conservative approximation: ASCII-heavy text ~4 chars/token and
     * non-ASCII text (including Persian) ~2 chars/token.
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0

        var ascii = 0
        var nonAscii = 0
        for (ch in text) {
            if (ch.code < 128) ascii += 1 else nonAscii += 1
        }

        return ceil(ascii / 4.0).toInt() +
            ceil(nonAscii / 2.0).toInt()
    }
}

private fun <T> Sequence<T>.takeLastCompat(count: Int): List<T> {
    val buffer = ArrayDeque<T>(count)
    for (item in this) {
        if (buffer.size == count) buffer.removeFirst()
        buffer.addLast(item)
    }
    return buffer.toList()
}
