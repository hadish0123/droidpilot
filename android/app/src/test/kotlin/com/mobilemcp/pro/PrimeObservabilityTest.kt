package com.mobilemcp.pro

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeObservabilityTest {

    @Test
    fun codecRoundTripsUsageWithoutPromptContent() {
        val original =
            listOf(
                PrimeAiUsageEvent(
                    id = "e1",
                    timestamp = 10,
                    providerId =
                        "gemini",
                    operation =
                        PrimeAiOperation
                            .TEXT,
                    model =
                        "gemini-test",
                    durationMs = 123,
                    estimatedInputTokens =
                        50,
                    estimatedOutputTokens =
                        20,
                    mediaItems = 0,
                    success = true,
                    errorType = null
                )
            )

        val encoded =
            PrimeAiUsageCodec.encode(
                original
            )
        assertFalse(
            encoded.contains(
                "secret prompt"
            )
        )
        assertEquals(
            original,
            PrimeAiUsageCodec
                .decode(encoded)
        )
    }

    @Test
    fun observedProviderRecordsSuccessTokensAndLatency() =
        runBlocking {
            val events =
                mutableListOf<
                    PrimeAiUsageEvent
                >()
            val sink =
                object :
                    PrimeObservabilitySink {
                    override fun record(
                        event:
                            PrimeAiUsageEvent
                    ) {
                        events += event
                    }
                }

            val delegate =
                object : AiProvider {
                    override val providerId =
                        "fake"
                    override fun isAvailable() =
                        true

                    override suspend fun listModels(
                        forceRefresh:
                            Boolean
                    ) = listOf(
                        AiModel(
                            "m",
                            "M"
                        )
                    )

                    override suspend fun streamText(
                        request:
                            AiTextRequest,
                        onTextDelta:
                            ((String) -> Unit)?
                    ): String =
                        "hello world"
                }

            val provider =
                PrimeObservedProvider(
                    delegate,
                    sink
                )

            val result =
                provider.streamText(
                    AiTextRequest(
                        model = "m",
                        instructions =
                            "system",
                        messages =
                            listOf(
                                AiMessage(
                                    "user",
                                    "hello"
                                )
                            )
                    )
                )

            assertEquals(
                "hello world",
                result
            )
            val event =
                events.single()
            assertEquals(
                PrimeAiOperation.TEXT,
                event.operation
            )
            assertTrue(
                event.success
            )
            assertTrue(
                event.estimatedInputTokens >
                    0
            )
            assertTrue(
                event.estimatedOutputTokens >
                    0
            )
            assertTrue(
                event.durationMs >= 0
            )
        }

    @Test
    fun observedProviderRecordsFailureWithoutSwallowingIt() =
        runBlocking {
            val events =
                mutableListOf<
                    PrimeAiUsageEvent
                >()
            val provider =
                PrimeObservedProvider(
                    delegate =
                        object :
                            AiProvider {
                            override val providerId =
                                "broken"
                            override fun isAvailable() =
                                true
                            override suspend fun listModels(
                                forceRefresh:
                                    Boolean
                            ): List<AiModel> =
                                throw IllegalStateException(
                                    "boom"
                                )

                            override suspend fun streamText(
                                request:
                                    AiTextRequest,
                                onTextDelta:
                                    ((String) -> Unit)?
                            ): String =
                                error("unused")
                        },
                    sink =
                        object :
                            PrimeObservabilitySink {
                            override fun record(
                                event:
                                    PrimeAiUsageEvent
                            ) {
                                events +=
                                    event
                            }
                        }
                )

            try {
                provider.listModels()
                throw AssertionError(
                    "Expected failure"
                )
            } catch (
                _: IllegalStateException
            ) {
            }

            assertEquals(
                1,
                events.size
            )
            assertFalse(
                events.single()
                    .success
            )
            assertEquals(
                "IllegalStateException",
                events.single()
                    .errorType
            )
        }
}
