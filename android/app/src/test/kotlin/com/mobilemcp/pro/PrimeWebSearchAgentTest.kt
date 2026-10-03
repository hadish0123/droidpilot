package com.mobilemcp.pro

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeWebSearchAgentTest {

    @Test
    fun freshQuestionUsesWebSearchAndSurfacesSourceUrl() =
        runBlocking {
            var textCalls = 0
            var searchCalls = 0

            val provider =
                object : AiProvider {
                    override val providerId =
                        "search-provider"
                    override val supportsWebSearch =
                        true

                    override fun isAvailable() =
                        true

                    override suspend fun listModels(
                        forceRefresh: Boolean
                    ) = listOf(
                        AiModel(
                            "search_sol",
                            "Search Sol"
                        )
                    )

                    override suspend fun streamText(
                        request: AiTextRequest,
                        onTextDelta:
                            ((String) -> Unit)?
                    ): String {
                        textCalls += 1
                        return "wrong path"
                    }

                    override suspend fun searchWeb(
                        request: AiTextRequest
                    ): AiWebResult {
                        searchCalls += 1
                        assertTrue(
                            request.messages
                                .last()
                                .content
                                .contains(
                                    "آخرین نسخه Kotlin"
                                )
                        )
                        return AiWebResult(
                            text =
                                "آخرین نسخه را پیدا کردم.",
                            citations =
                                listOf(
                                    AiWebCitation(
                                        url =
                                            "https://kotlinlang.org/docs/releases.html",
                                        title =
                                            "Kotlin releases"
                                    )
                                )
                        )
                    }
                }

            val agent =
                PrimeAgent(
                    provider = provider
                )
            val result = agent.run(
                userText =
                    "آخرین نسخه Kotlin را با منبع بگو",
                confirmedForTask = false,
                uiProvider = {
                    error(
                        "Web search must not read Android UI"
                    )
                },
                actionRunner = { _, _ ->
                    error(
                        "Web search must not run phone actions"
                    )
                },
                onProgress = {}
            )

            assertEquals(1, searchCalls)
            assertEquals(0, textCalls)
            assertTrue(
                result.text.contains(
                    "https://kotlinlang.org/docs/releases.html"
                )
            )
            assertTrue(
                result.text.contains("منابع")
            )
        }
}
