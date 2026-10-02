package com.mobilemcp.pro.ai

/**
 * Provider-neutral model metadata used by the PRIME orchestration layer.
 */
internal data class AiModel(
    val id: String,
    val displayName: String
)

internal data class AiMessage(
    val role: String,
    val content: String
)

internal data class AiTextRequest(
    val modelId: String,
    val instructions: String,
    val messages: List<AiMessage>
)

/**
 * Small provider boundary: orchestration must not know HTTP, auth headers,
 * SSE framing, retries, or provider-specific response envelopes.
 */
internal interface AiProvider {
    suspend fun listModels(): List<AiModel>

    suspend fun generateText(
        request: AiTextRequest,
        onTextDelta: ((String) -> Unit)? = null
    ): String
}
