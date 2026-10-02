package com.mobilemcp.pro.ai

internal enum class ModelPurpose {
    FAST_CHAT,
    AGENT_ACTION
}

internal class ModelRegistry {
    fun select(models: List<AiModel>, purpose: ModelPurpose): AiModel {
        require(models.isNotEmpty()) { "No AI models are available" }

        val markers = when (purpose) {
            ModelPurpose.FAST_CHAT -> listOf("luna", "mini", "instant", "sol")
            ModelPurpose.AGENT_ACTION -> listOf("sol", "pro")
        }

        for (marker in markers) {
            models.firstOrNull { model ->
                model.id.contains(marker, ignoreCase = true) ||
                    model.displayName.contains(marker, ignoreCase = true)
            }?.let { return it }
        }

        return models.first()
    }
}
