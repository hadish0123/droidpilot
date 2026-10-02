package com.mobilemcp.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeContextManagerTest {

    @Test
    fun longHistoryKeepsRecentMessagesInsideBudget() {
        val context = PrimeContextManager(
            maxStoredMessages = 80,
            maxRequestMessages = 12,
            maxEstimatedTokens = 700,
            maxMessageChars = 4_000
        )

        val history = mutableListOf<Pair<String, String>>()
        repeat(50) { index ->
            history += "user" to "سوال شماره $index " + "متن ".repeat(20)
            history += "assistant" to "پاسخ شماره $index " + "جواب ".repeat(20)
        }
        context.restore(history)

        val window = context.buildWindow("سوال جدید")

        assertTrue(window.messages.size <= 12)
        assertTrue(window.estimatedTokens <= 700)
        assertTrue(window.droppedMessages > 0)
        assertTrue(window.messages.last().content.contains("49"))
        assertTrue(context.storedMessageCount() <= 80)
    }

    @Test
    fun providerWindowPreservesChronologicalRoleOrder() {
        val context = PrimeContextManager(
            maxEstimatedTokens = 2_000
        )
        context.restore(
            listOf(
                "user" to "one",
                "assistant" to "two",
                "user" to "three",
                "assistant" to "four"
            )
        )

        val roles = context.buildWindow("five")
            .messages
            .map { it.role }

        assertEquals(
            listOf("user", "assistant", "user", "assistant"),
            roles
        )
    }

    @Test
    fun exclusionPolicyRemovesOldCapabilityDenials() {
        val context = PrimeContextManager()
        context.restore(
            listOf(
                "user" to "برو تلگرام",
                "assistant" to "I cannot control your phone.",
                "user" to "روی علی بزن",
                "assistant" to "باشه"
            )
        )

        val window = context.buildWindow("ادامه بده") { message ->
            message.role == "assistant" &&
                PhoneReplyPolicy.isCapabilityDenial(message.content)
        }

        assertFalse(
            window.messages.any {
                it.content.contains("cannot control", ignoreCase = true)
            }
        )
        assertTrue(window.droppedMessages >= 1)
    }

    @Test
    fun hugeMessageIsClippedWithoutLosingBothEnds() {
        val context = PrimeContextManager(
            maxStoredMessages = 10,
            maxRequestMessages = 4,
            maxEstimatedTokens = 600,
            maxMessageChars = 2_000
        )

        val huge = "START-" + "الف".repeat(10_000) + "-END"
        context.restore(listOf("assistant" to huge))

        val message = context.buildWindow("next").messages.single().content

        assertTrue(message.startsWith("START-"))
        assertTrue(message.endsWith("-END"))
        assertTrue(message.contains("[context clipped]"))
        assertTrue(message.length <= 2_000)
    }

    @Test
    fun tokenEstimatorIsMoreConservativeForPersian() {
        val ascii = ApproximateTokenEstimator.estimate("a".repeat(100))
        val persian = ApproximateTokenEstimator.estimate("ا".repeat(100))

        assertEquals(25, ascii)
        assertEquals(50, persian)
    }
}
