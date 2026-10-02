package com.mobilemcp.pro.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextTest {
    @Test fun rememberKeepsOnlyTheConfiguredTail() {
        val context = ConversationContext(maxMessages = 4)

        context.remember("u1", "a1")
        context.remember("u2", "a2")
        context.remember("u3", "a3")

        assertEquals(
            listOf("u2", "a2", "u3", "a3"),
            context.recent(10).map { it.content }
        )
    }

    @Test fun restoreDropsInvalidRolesAndBlankMessages() {
        val context = ConversationContext(maxMessages = 10)
        context.restore(
            listOf(
                "system" to "do not restore",
                "user" to "",
                "user" to "hello",
                "assistant" to "hi"
            )
        )

        assertEquals(listOf("user", "assistant"), context.recent(10).map { it.role })
        assertEquals(listOf("hello", "hi"), context.recent(10).map { it.content })
    }

    @Test fun clearRemovesAllMessages() {
        val context = ConversationContext()
        context.remember("hello", "hi")
        context.clear()

        assertTrue(context.recent(10).isEmpty())
        assertEquals(0, context.size())
    }
}
