package com.mobilemcp.pro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeWebSearchIntentTest {
    @Test
    fun detectsFreshAndExplicitWebRequests() {
        assertTrue(
            PrimeWebSearchIntent.shouldSearch(
                "آخرین نسخه Kotlin چیه؟"
            )
        )
        assertTrue(
            PrimeWebSearchIntent.shouldSearch(
                "Search the web for current Android news"
            )
        )
    }

    @Test
    fun ordinaryStableQuestionStaysOfflineFromSearchTool() {
        assertFalse(
            PrimeWebSearchIntent.shouldSearch(
                "فرق interface و abstract class چیست؟"
            )
        )
    }
}
