package com.mobilemcp.pro

/**
 * Provider-neutral model metadata used by PRIME orchestration.
 */
internal data class AiModel(
    val id: String,
    val displayName: String
)

/**
 * Provider-neutral chat message.
 */
internal data class AiMessage(
    val role: String,
    val content: String
)

/**
 * Provider-neutral text generation request.
 */
internal data class AiTextRequest(
    val model: String,
    val instructions: String,
    val messages: List<AiMessage>
)

internal data class AiWebCitation(
    val url: String,
    val title: String?
)

internal data class AiWebResult(
    val text: String,
    val citations: List<AiWebCitation>
)

/**
 * Boundary between PRIME orchestration and any model backend.
 *
 * Implementations own authentication, HTTP transport, retries and provider
 * response parsing. PRIME's orchestration must not depend on provider-specific
 * JSON or network APIs.
 */
internal interface AiProvider {
    val providerId: String

    fun isAvailable(): Boolean

    suspend fun listModels(forceRefresh: Boolean = false): List<AiModel>

    suspend fun streamText(
        request: AiTextRequest,
        onTextDelta: ((String) -> Unit)? = null
    ): String

    val supportsWebSearch: Boolean
        get() = false

    suspend fun searchWeb(
        request: AiTextRequest
    ): AiWebResult {
        throw UnsupportedOperationException(
            "Web search is not supported by this provider"
        )
    }

    fun reset() = Unit
}
