package com.mobilemcp.pro

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenAIVisionProviderTest {

    @Test
    fun visionUsesResponsesInputImageDataUrl() =
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
                            JSONObject()
                                .put(
                                    "output",
                                    JSONArray().put(
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
                                                            "یک گربه"
                                                        )
                                                )
                                            )
                                    )
                                )
                                .toString()
                        )
                )

                val provider =
                    OpenAIResponsesProvider(
                        auth =
                            object :
                                PrimeCredentials {
                                override fun isSignedIn() =
                                    true

                                override suspend fun accessToken() =
                                    "vision-token"
                            },
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
                    provider.analyzeImages(
                        request =
                            AiTextRequest(
                                model =
                                    "vision-sol",
                                instructions =
                                    "Analyze safely.",
                                messages =
                                    listOf(
                                        AiMessage(
                                            "user",
                                            "این عکس چیست؟"
                                        )
                                    )
                            ),
                        images =
                            listOf(
                                AiImageInput(
                                    name =
                                        "cat.png",
                                    mimeType =
                                        "image/png",
                                    dataUrl =
                                        "data:image/png;base64,AA=="
                                )
                            )
                    )

                assertEquals(
                    "یک گربه",
                    result
                )

                val request =
                    server.takeRequest()
                val body = JSONObject(
                    request.body.readUtf8()
                )
                assertFalse(
                    body.getBoolean(
                        "stream"
                    )
                )

                val content =
                    body.getJSONArray(
                        "input"
                    )
                        .getJSONObject(0)
                        .getJSONArray(
                            "content"
                        )

                assertEquals(
                    "input_text",
                    content
                        .getJSONObject(0)
                        .getString("type")
                )
                assertEquals(
                    "input_image",
                    content
                        .getJSONObject(1)
                        .getString("type")
                )
                assertEquals(
                    "data:image/png;base64,AA==",
                    content
                        .getJSONObject(1)
                        .getString(
                            "image_url"
                        )
                )
                assertEquals(
                    "auto",
                    content
                        .getJSONObject(1)
                        .getString("detail")
                )
            }
        }
}
