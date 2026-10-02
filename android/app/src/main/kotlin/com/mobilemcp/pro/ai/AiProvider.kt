package com.mobilemcp.pro.ai

internal enum class AiCapability {
    TEXT,
    STREAMING,
    STRUCTURED_OUTPUT,
    TOOLS,
    VISION,
    EMBEDDINGS
}

internal data class AiProviderInfo(
    val id: String,
    val label: String,
    val capabilities: Set<AiCapability>
)

internal data class AiModel(
    val id: String,
    val displayName: String
)

internal data class AiMessage(
    val role: String,
    val content: String
)

internal data class AiTextRequest(
    val model: String,
    val instructions: String,
    val messages: List<AiMessage>,
    val onTextDelta: ((String) -> Unit)? = null
)

internal interface AiProvider {
    val info: AiProviderInfo

    suspend fun listModels(): List<AiModel>

    suspend fun generateText(request: AiTextRequest): String
}
