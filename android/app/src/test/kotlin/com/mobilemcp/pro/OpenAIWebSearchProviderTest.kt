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

class OpenAIWebSearchProviderTest {

    @Test
    fun webSearchUsesCurrentResponsesToolAndParsesCitations() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader(
                            "Content-Type",
                            "application/json"
                        )
                        .setBody(
                            responseBody()
                                .toString()
                        )
                )

                val credentials =
                    object :
                        PrimeCredentials {
                        override fun isSignedIn() =
                            true

                        override suspend fun accessToken() =
                            "test-token"
                    }

                val provider =
                    OpenAIResponsesProvider(
                        auth = credentials,
                        endpoints =
                            PrimeApiEndpoints(
                                models =
                                    server.url(
                                        "/v1/models"
                                    ).toString(),
                                responses =
                                    server.url(
                                        "/v1/responses"
                                    ).toString()
                            )
                    )

                val result =
                    provider.searchWeb(
                        AiTextRequest(
                            model = "test-sol",
                            instructions =
                                "Use current sources.",
                            messages =
                                listOf(
                                    AiMessage(
                                        "user",
                                        "latest Kotlin release"
                                    )
                                )
                        )
                    )

                assertEquals(
                    "Kotlin is current.",
                    result.text
                )
                assertEquals(
                    2,
                    result.citations.size
                )
                assertEquals(
                    "https://kotlinlang.org/docs/releases.html",
                    result.citations.first().url
                )

                val request =
                    server.takeRequest()
                assertEquals(
                    "Bearer test-token",
                    request.getHeader(
                        "Authorization"
                    )
                )

                val json = JSONObject(
                    request.body.readUtf8()
                )
                assertFalse(
                    json.getBoolean(
                        "stream"
                    )
                )
                assertEquals(
                    "required",
                    json.getString(
                        "tool_choice"
                    )
                )
                assertEquals(
                    "web_search",
                    json.getJSONArray(
                        "tools"
                    )
                        .getJSONObject(0)
                        .getString("type")
                )
                assertEquals(
                    "web_search_call.action.sources",
                    json.getJSONArray(
                        "include"
                    ).getString(0)
                )
            }
        }

    private fun responseBody(): JSONObject =
        JSONObject()
            .put(
                "output",
                JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "type",
                                "web_search_call"
                            )
                            .put(
                                "action",
                                JSONObject().put(
                                    "sources",
                                    JSONArray()
                                        .put(
                                            JSONObject()
                                                .put(
                                                    "url",
                                                    "https://kotlinlang.org/docs/releases.html"
                                                )
                                                .put(
                                                    "title",
                                                    "Kotlin releases"
                                                )
                                        )
                                        .put(
                                            JSONObject()
                                                .put(
                                                    "url",
                                                    "https://example.com/secondary"
                                                )
                                                .put(
                                                    "title",
                                                    "Secondary"
                                                )
                                        )
                                )
                            )
                    )
                    .put(
                        JSONObject()
                            .put(
                                "type",
                                "message"
                            )
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put(
                                            "type",
                                            "output_text"
                                        )
                                        .put(
                                            "text",
                                            "Kotlin is current."
                                        )
                                        .put(
                                            "annotations",
                                            JSONArray().put(
                                                JSONObject()
                                                    .put(
                                                        "type",
                                                        "url_citation"
                                                    )
                                                    .put(
                                                        "url",
                                                        "https://kotlinlang.org/docs/releases.html"
                                                    )
                                                    .put(
                                                        "title",
                                                        "Kotlin releases"
                                                    )
                                            )
                                        )
                                )
                            )
                    )
            )
}
