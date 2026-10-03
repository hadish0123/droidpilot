package com.mobilemcp.pro

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeProviderRuntimeTest {

    @Test
    fun remoteCustomProviderRequiresHttps() {
        try {
            PrimeProviderEndpointValidator
                .normalize(
                    "http://example.com/v1"
                )
            throw AssertionError(
                "Expected remote cleartext rejection"
            )
        } catch (
            _: IllegalArgumentException
        ) {
        }

        assertTrue(
            PrimeProviderEndpointValidator
                .normalize(
                    "http://127.0.0.1:11434/v1"
                )
                .startsWith(
                    "http://127.0.0.1"
                )
        )
    }

    @Test
    fun providerCodecKeepsEncryptedProfileFields() {
        val original =
            listOf(
                PrimeProviderProfile(
                    id = "p1",
                    name = "OpenRouter",
                    kind =
                        PrimeProviderKind
                            .OPENROUTER,
                    baseUrl =
                        "https://openrouter.ai/api/v1",
                    model =
                        "openrouter/free",
                    apiKey = "secret",
                    createdAt = 1,
                    updatedAt = 2
                )
            )

        assertEquals(
            original,
            PrimeProviderCodec
                .decode(
                    PrimeProviderCodec
                        .encode(original)
                )
        )
    }

    @Test
    fun openAiCompatibleProviderUsesChatCompletions() =
        runBlocking {
            MockWebServer().use {
                server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader(
                            "Content-Type",
                            "application/json"
                        )
                        .setBody(
                            JSONObject()
                                .put(
                                    "choices",
                                    JSONArray().put(
                                        JSONObject()
                                            .put(
                                                "message",
                                                JSONObject()
                                                    .put(
                                                        "role",
                                                        "assistant"
                                                    )
                                                    .put(
                                                        "content",
                                                        "hello"
                                                    )
                                            )
                                    )
                                )
                                .toString()
                        )
                )

                val profile =
                    PrimeProviderProfile(
                        id = "local",
                        name = "Local",
                        kind =
                            PrimeProviderKind
                                .OPENAI_COMPATIBLE,
                        baseUrl =
                            server.url(
                                "/v1"
                            )
                                .toString()
                                .trimEnd('/'),
                        model =
                            "local-model",
                        apiKey = null,
                        createdAt = 1,
                        updatedAt = 1
                    )
                val provider =
                    PrimeProviderFactory
                        .create(profile)
                val result =
                    provider.streamText(
                        AiTextRequest(
                            model =
                                profile.model,
                            instructions =
                                "system",
                            messages =
                                listOf(
                                    AiMessage(
                                        "user",
                                        "hi"
                                    )
                                )
                        )
                    )

                assertEquals(
                    "hello",
                    result
                )
                val request =
                    server.takeRequest()
                assertEquals(
                    "/v1/chat/completions",
                    request.path
                )
                val json =
                    JSONObject(
                        request.body
                            .readUtf8()
                    )
                assertFalse(
                    json.getBoolean(
                        "stream"
                    )
                )
                assertEquals(
                    "system",
                    json.getJSONArray(
                        "messages"
                    )
                        .getJSONObject(0)
                        .getString(
                            "role"
                        )
                )
            }
        }

    @Test
    fun geminiProviderUsesGenerateContentAndApiKeyHeader() =
        runBlocking {
            MockWebServer().use {
                server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader(
                            "Content-Type",
                            "application/json"
                        )
                        .setBody(
                            JSONObject()
                                .put(
                                    "candidates",
                                    JSONArray().put(
                                        JSONObject()
                                            .put(
                                                "content",
                                                JSONObject()
                                                    .put(
                                                        "parts",
                                                        JSONArray().put(
                                                            JSONObject()
                                                                .put(
                                                                    "text",
                                                                    "gemini"
                                                                )
                                                        )
                                                    )
                                            )
                                    )
                                )
                                .toString()
                        )
                )

                val profile =
                    PrimeProviderProfile(
                        id = "g",
                        name = "Gemini",
                        kind =
                            PrimeProviderKind
                                .GEMINI,
                        baseUrl =
                            server.url(
                                "/v1beta"
                            )
                                .toString()
                                .trimEnd('/'),
                        model =
                            "gemini-test",
                        apiKey =
                            "gem-key",
                        createdAt = 1,
                        updatedAt = 1
                    )
                val provider =
                    PrimeProviderFactory
                        .create(profile)
                assertEquals(
                    "gemini",
                    provider.streamText(
                        AiTextRequest(
                            model =
                                profile.model,
                            instructions =
                                "system",
                            messages =
                                listOf(
                                    AiMessage(
                                        "user",
                                        "hi"
                                    )
                                )
                        )
                    )
                )

                val request =
                    server.takeRequest()
                assertEquals(
                    "gem-key",
                    request.getHeader(
                        "x-goog-api-key"
                    )
                )
                assertTrue(
                    request.path
                        .orEmpty()
                        .contains(
                            ":generateContent"
                        )
                )
            }
        }

    @Test
    fun anthropicProviderUsesMessagesHeaders() =
        runBlocking {
            MockWebServer().use {
                server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader(
                            "Content-Type",
                            "application/json"
                        )
                        .setBody(
                            JSONObject()
                                .put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject()
                                            .put(
                                                "type",
                                                "text"
                                            )
                                            .put(
                                                "text",
                                                "claude"
                                            )
                                    )
                                )
                                .toString()
                        )
                )

                val profile =
                    PrimeProviderProfile(
                        id = "a",
                        name =
                            "Anthropic",
                        kind =
                            PrimeProviderKind
                                .ANTHROPIC,
                        baseUrl =
                            server.url("")
                                .toString()
                                .trimEnd('/'),
                        model =
                            "claude-test",
                        apiKey =
                            "anth-key",
                        createdAt = 1,
                        updatedAt = 1
                    )
                val provider =
                    PrimeProviderFactory
                        .create(profile)
                assertEquals(
                    "claude",
                    provider.streamText(
                        AiTextRequest(
                            model =
                                profile.model,
                            instructions =
                                "system",
                            messages =
                                listOf(
                                    AiMessage(
                                        "user",
                                        "hi"
                                    )
                                )
                        )
                    )
                )

                val request =
                    server.takeRequest()
                assertEquals(
                    "/v1/messages",
                    request.path
                )
                assertEquals(
                    "Bearer anth-key",
                    request.getHeader(
                        "Authorization"
                    )
                )
                assertEquals(
                    "2023-06-01",
                    request.getHeader(
                        "anthropic-version"
                    )
                )
            }
        }
}
