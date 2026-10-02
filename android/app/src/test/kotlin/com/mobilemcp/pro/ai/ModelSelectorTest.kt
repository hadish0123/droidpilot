package com.mobilemcp.pro.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectorTest {
    private val models = listOf(
        AiModel("fallback", "Fallback"),
        AiModel("gpt-sol", "Sol"),
        AiModel("gpt-luna", "Luna"),
        AiModel("gpt-pro", "Pro")
    )

    @Test fun fastChatPrefersFastModel() {
        assertEquals("gpt-luna", ModelSelector.select(models, ModelPurpose.FAST_CHAT).id)
    }

    @Test fun actionsPreferSolBeforePro() {
        assertEquals("gpt-sol", ModelSelector.select(models, ModelPurpose.ACTIONS).id)
    }

    @Test fun fallsBackToFirstVisibleModel() {
        val fallback = listOf(AiModel("custom", "Custom"))
        assertEquals("custom", ModelSelector.select(fallback, ModelPurpose.ACTIONS).id)
    }
}
