package com.mobilemcp.pro.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRegistryTest {
    private val registry = ModelRegistry()

    @Test
    fun fastChatPrefersFastModelMarkers() {
        val models = listOf(
            AiModel("prime_sol", "Sol"),
            AiModel("prime_luna", "Luna")
        )

        assertEquals(
            "prime_luna",
            registry.select(models, ModelPurpose.FAST_CHAT).id
        )
    }

    @Test
    fun actionPurposePrefersReasoningModelMarkers() {
        val models = listOf(
            AiModel("prime_luna", "Luna"),
            AiModel("prime_sol", "Sol")
        )

        assertEquals(
            "prime_sol",
            registry.select(models, ModelPurpose.AGENT_ACTION).id
        )
    }

    @Test
    fun fallsBackToFirstAvailableModel() {
        val models = listOf(
            AiModel("custom-a", "Custom A"),
            AiModel("custom-b", "Custom B")
        )

        assertEquals(
            "custom-a",
            registry.select(models, ModelPurpose.AGENT_ACTION).id
        )
    }
}
