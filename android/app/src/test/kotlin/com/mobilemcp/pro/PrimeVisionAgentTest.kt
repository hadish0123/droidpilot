package com.mobilemcp.pro

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeVisionAgentTest {

    @Test
    fun attachedImageQuestionUsesVisionOnly() =
        runBlocking {
            var visionCalls = 0
            var textCalls = 0
            var searchCalls = 0

            val provider =
                object : AiProvider {
                    override val providerId =
                        "vision-provider"
                    override val supportsVision =
                        true
                    override val supportsWebSearch =
                        true

                    override fun isAvailable() =
                        true

                    override suspend fun listModels(
                        forceRefresh: Boolean
                    ) = listOf(
                        AiModel(
                            "vision_sol",
                            "Vision Sol"
                        )
                    )

                    override suspend fun streamText(
                        request: AiTextRequest,
                        onTextDelta:
                            ((String) -> Unit)?
                    ): String {
                        textCalls += 1
                        return "wrong"
                    }

                    override suspend fun searchWeb(
                        request: AiTextRequest
                    ): AiWebResult {
                        searchCalls += 1
                        return AiWebResult(
                            "wrong",
                            emptyList()
                        )
                    }

                    override suspend fun analyzeImages(
                        request: AiTextRequest,
                        images:
                            List<AiImageInput>
                    ): String {
                        visionCalls += 1
                        assertEquals(
                            1,
                            images.size
                        )
                        assertTrue(
                            request.instructions
                                .contains(
                                    "untrusted"
                                )
                        )
                        return "یک نمودار است."
                    }
                }

            val imageSource =
                object :
                    PrimeImageContextSource {
                    override fun images(
                        limit: Int
                    ) = listOf(
                        AiImageInput(
                            name = "chart.png",
                            mimeType =
                                "image/png",
                            dataUrl =
                                "data:image/png;base64,AA=="
                        )
                    )
                }

            val outcome =
                PrimeAgent(
                    provider = provider,
                    imageContextSource =
                        imageSource
                ).run(
                    userText =
                        "این عکس را توضیح بده",
                    confirmedForTask =
                        false,
                    uiProvider = {
                        error(
                            "Vision must not read phone UI"
                        )
                    },
                    actionRunner = { _, _ ->
                        error(
                            "Vision must not run phone tools"
                        )
                    },
                    onProgress = {}
                )

            assertEquals(
                "یک نمودار است.",
                outcome.text
            )
            assertEquals(1, visionCalls)
            assertEquals(0, textCalls)
            assertEquals(0, searchCalls)
        }
}
