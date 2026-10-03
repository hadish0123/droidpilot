package com.mobilemcp.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeChatProductivityTest {
    @Test
    fun exportKeepsRolesAndConversationContent() {
        val chat = PrimeChatSummary(
            id = 1,
            title = "Project",
            updatedAt = 1,
            isPinned = true
        )
        val text = PrimeChatProductivity
            .exportText(
                chat,
                listOf(
                    PrimeStoredMessage(
                        id = 1,
                        chatId = 1,
                        role = "user",
                        content = "hello",
                        createdAt = 1
                    ),
                    PrimeStoredMessage(
                        id = 2,
                        chatId = 1,
                        role = "assistant",
                        content = "hi",
                        createdAt = 2
                    )
                )
            )

        assertTrue(
            text.contains(
                "Conversation: Project"
            )
        )
        assertTrue(
            text.contains("You: hello")
        )
        assertTrue(
            text.contains("PRIME: hi")
        )
        assertFalse(
            text.contains("metadata_json")
        )
    }

    @Test
    fun snippetCompactsWhitespaceAndBoundsLength() {
        assertEquals(
            "one two three",
            PrimeChatProductivity.snippet(
                "one\n  two   three",
                40
            )
        )

        val value =
            PrimeChatProductivity.snippet(
                "abcdefghijklmnopqrstuvwxyz",
                12
            )
        assertTrue(
            value.endsWith("…")
        )
        assertTrue(
            value.length <= 13
        )
    }
}
