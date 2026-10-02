package com.mobilemcp.pro.ai

internal enum class ModelPurpose {
    FAST_CHAT,
    ACTIONS
}

/** Deterministic model selection policy kept outside the agent loop. */
internal object ModelSelector {
    fun select(models: List<AiModel>, purpose: ModelPurpose): AiModel {
        require(models.isNotEmpty()) { "At least one model is required" }

        val markers = when (purpose) {
            ModelPurpose.FAST_CHAT -> listOf("luna", "mini", "instant", "sol")
            ModelPurpose.ACTIONS -> listOf("sol", "pro")
        }

        for (marker in markers) {
            val match = models.firstOrNull { model ->
                model.id.contains(marker, ignoreCase = true) ||
                    model.displayName.contains(marker, ignoreCase = true)
            }
            if (match != null) return match
        }

        return models.first()
    }
}
