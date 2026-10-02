package com.mobilemcp.pro.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextManagerTest {
    @Test
    fun restoreKeepsOnlySupportedRolesAndPrunesOldestMessages() {
        val manager = ConversationContextManager(
            maxMessages = 4,
            maxEstimatedTokens = 1_000
        )

        manager.restore(
            listOf(
                "system" to "ignore",
                "user" to "one",
                "assistant" to "two",
                "user" to "three",
                "assistant" to "four",
                "user" to "five"
            )
        )

        assertEquals(4, manager.size())
        assertEquals(
            listOf("two", "three", "four", "five"),
            manager.recent(10).map { it.content }
        )
    }

    @Test
    fun tokenBudgetBoundsLargeConversations() {
        val manager = ConversationContextManager(
            maxMessages = 100,
            maxEstimatedTokens = 100
        )

        repeat(20) { index ->
            manager.appendTurn(
                "u$index " + "x".repeat(80),
                "a$index " + "y".repeat(80)
            )
        }

        assertTrue(manager.estimatedTokens() <= 100)
        assertTrue(manager.size() < 40)
    }

    @Test
    fun recentReturnsRequestedTailOnly() {
        val manager = ConversationContextManager()
        repeat(5) { index ->
            manager.appendTurn("u$index", "a$index")
        }

        assertEquals(
            listOf("u4", "a4"),
            manager.recent(2).map { it.content }
        )
    }
}
