package com.mobilemcp.pro

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimeWebSearchPolicyTest {
    @Test
    fun latestAndExplicitSearchRequireWeb() {
        assertEquals(
            AiWebSearchMode.REQUIRED,
            PrimeWebSearchPolicy.modeFor(
                "آخرین نسخه Kotlin چیه؟"
            )
        )
        assertEquals(
            AiWebSearchMode.REQUIRED,
            PrimeWebSearchPolicy.modeFor(
                "search the web for Android news"
            )
        )
    }

    @Test
    fun researchCanUseAutoSearch() {
        assertEquals(
            AiWebSearchMode.AUTO,
            PrimeWebSearchPolicy.modeFor(
                "این موضوع را تحقیق کن و منبع بده"
            )
        )
    }

    @Test
    fun ordinaryConversationDoesNotSearch() {
        assertEquals(
            AiWebSearchMode.DISABLED,
            PrimeWebSearchPolicy.modeFor(
                "یک متن دوستانه بنویس"
            )
        )
    }
}
