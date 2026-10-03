package com.mobilemcp.pro

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class PrimeAiOperation {
    MODELS,
    TEXT,
    WEB_SEARCH,
    VISION
}

internal data class PrimeAiUsageEvent(
    val id: String,
    val timestamp: Long,
    val providerId: String,
    val operation: PrimeAiOperation,
    val model: String?,
    val durationMs: Long,
    val estimatedInputTokens: Int,
    val estimatedOutputTokens: Int,
    val mediaItems: Int,
    val success: Boolean,
    val errorType: String?
)

internal data class PrimeAiUsageSummary(
    val calls: Int,
    val failures: Int,
    val estimatedInputTokens: Long,
    val estimatedOutputTokens: Long,
    val averageLatencyMs: Long,
    val byProvider: Map<String, Int>
)

internal interface PrimeObservabilitySink {
    fun record(
        event: PrimeAiUsageEvent
    )
}

internal object EmptyPrimeObservabilitySink :
    PrimeObservabilitySink {
    override fun record(
        event: PrimeAiUsageEvent
    ) = Unit
}

internal object PrimeAiUsageCodec {
    fun encode(
        events: List<PrimeAiUsageEvent>
    ): String {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("id", event.id)
                    .put(
                        "timestamp",
                        event.timestamp
                    )
                    .put(
                        "providerId",
                        event.providerId
                    )
                    .put(
                        "operation",
                        event.operation.name
                    )
                    .put(
                        "model",
                        event.model
                            ?: JSONObject.NULL
                    )
                    .put(
                        "durationMs",
                        event.durationMs
                    )
                    .put(
                        "estimatedInputTokens",
                        event.estimatedInputTokens
                    )
                    .put(
                        "estimatedOutputTokens",
                        event.estimatedOutputTokens
                    )
                    .put(
                        "mediaItems",
                        event.mediaItems
                    )
                    .put(
                        "success",
                        event.success
                    )
                    .put(
                        "errorType",
                        event.errorType
                            ?: JSONObject.NULL
                    )
            )
        }
        return array.toString()
    }

    fun decode(
        raw: String?
    ): List<PrimeAiUsageEvent> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (
                    index in 0
                        until array.length()
                ) {
                    val json =
                        array.optJSONObject(
                            index
                        ) ?: continue
                    val id =
                        json.optString("id")
                            .trim()
                    val provider =
                        json.optString(
                            "providerId"
                        ).trim()
                    val operation =
                        runCatching {
                            PrimeAiOperation
                                .valueOf(
                                    json.optString(
                                        "operation"
                                    )
                                )
                        }.getOrNull()
                            ?: continue

                    if (
                        id.isBlank() ||
                        provider.isBlank()
                    ) {
                        continue
                    }

                    add(
                        PrimeAiUsageEvent(
                            id = id,
                            timestamp =
                                json.optLong(
                                    "timestamp"
                                ),
                            providerId =
                                provider,
                            operation =
                                operation,
                            model =
                                if (
                                    json.isNull(
                                        "model"
                                    )
                                ) {
                                    null
                                } else {
                                    json.optString(
                                        "model"
                                    )
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                },
                            durationMs =
                                json.optLong(
                                    "durationMs"
                                )
                                    .coerceAtLeast(
                                        0
                                    ),
                            estimatedInputTokens =
                                json.optInt(
                                    "estimatedInputTokens"
                                )
                                    .coerceAtLeast(
                                        0
                                    ),
                            estimatedOutputTokens =
                                json.optInt(
                                    "estimatedOutputTokens"
                                )
                                    .coerceAtLeast(
                                        0
                                    ),
                            mediaItems =
                                json.optInt(
                                    "mediaItems"
                                )
                                    .coerceAtLeast(
                                        0
                                    ),
                            success =
                                json.optBoolean(
                                    "success"
                                ),
                            errorType =
                                if (
                                    json.isNull(
                                        "errorType"
                                    )
                                ) {
                                    null
                                } else {
                                    json.optString(
                                        "errorType"
                                    )
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                }
                        )
                    )
                }
            }
        }.getOrDefault(
            emptyList()
        )
    }
}

internal class PrimeObservabilityStore(
    context: Context,
    private val secureStore:
        SecureStore =
        SecureStore(
            context.applicationContext
        )
) : PrimeObservabilitySink {
    companion object {
        private const val STORE_KEY =
            "prime_ai_usage_v1"
        private const val MAX_EVENTS = 400
    }

    @Synchronized
    override fun record(
        event: PrimeAiUsageEvent
    ) {
        val events =
            readAll().toMutableList()
        events += event
        val retained =
            events.takeLast(
                MAX_EVENTS
            )
        secureStore.putString(
            STORE_KEY,
            PrimeAiUsageCodec
                .encode(retained)
        )
    }

    @Synchronized
    fun recent(
        limit: Int = 30
    ): List<PrimeAiUsageEvent> =
        readAll()
            .asReversed()
            .take(
                limit.coerceIn(
                    0,
                    MAX_EVENTS
                )
            )

    @Synchronized
    fun summary():
        PrimeAiUsageSummary {
        val events = readAll()
        if (events.isEmpty()) {
            return PrimeAiUsageSummary(
                calls = 0,
                failures = 0,
                estimatedInputTokens = 0,
                estimatedOutputTokens = 0,
                averageLatencyMs = 0,
                byProvider =
                    emptyMap()
            )
        }

        return PrimeAiUsageSummary(
            calls = events.size,
            failures =
                events.count {
                    !it.success
                },
            estimatedInputTokens =
                events.sumOf {
                    it.estimatedInputTokens
                        .toLong()
                },
            estimatedOutputTokens =
                events.sumOf {
                    it.estimatedOutputTokens
                        .toLong()
                },
            averageLatencyMs =
                events.sumOf {
                    it.durationMs
                } / events.size,
            byProvider =
                events.groupingBy {
                    it.providerId
                }.eachCount()
        )
    }

    @Synchronized
    fun clear() {
        secureStore.remove(
            STORE_KEY
        )
    }

    private fun readAll():
        List<PrimeAiUsageEvent> =
        PrimeAiUsageCodec.decode(
            secureStore.getString(
                STORE_KEY
            )
        )
}

internal class PrimeObservedProvider(
    private val delegate: AiProvider,
    private val sink:
        PrimeObservabilitySink
) : AiProvider {
    override val providerId:
        String =
        delegate.providerId

    override val supportsWebSearch:
        Boolean
        get() =
            delegate.supportsWebSearch

    override val supportsVision:
        Boolean
        get() =
            delegate.supportsVision

    override fun isAvailable():
        Boolean =
        delegate.isAvailable()

    override fun reset() {
        delegate.reset()
    }

    override suspend fun listModels(
        forceRefresh: Boolean
    ): List<AiModel> =
        observe(
            operation =
                PrimeAiOperation.MODELS,
            model = null,
            inputTokens = 0,
            mediaItems = 0,
            outputTokens = {
                0
            }
        ) {
            delegate.listModels(
                forceRefresh
            )
        }

    override suspend fun streamText(
        request: AiTextRequest,
        onTextDelta:
            ((String) -> Unit)?
    ): String =
        observe(
            operation =
                PrimeAiOperation.TEXT,
            model = request.model,
            inputTokens =
                estimateInput(request),
            mediaItems = 0,
            outputTokens = {
                text ->
                ApproximateTokenEstimator
                    .estimate(text)
            }
        ) {
            delegate.streamText(
                request,
                onTextDelta
            )
        }

    override suspend fun searchWeb(
        request: AiTextRequest
    ): AiWebResult =
        observe(
            operation =
                PrimeAiOperation
                    .WEB_SEARCH,
            model = request.model,
            inputTokens =
                estimateInput(request),
            mediaItems = 0,
            outputTokens = {
                result ->
                ApproximateTokenEstimator
                    .estimate(
                        result.text
                    )
            }
        ) {
            delegate.searchWeb(
                request
            )
        }

    override suspend fun analyzeImages(
        request: AiTextRequest,
        images: List<AiImageInput>
    ): String =
        observe(
            operation =
                PrimeAiOperation.VISION,
            model = request.model,
            inputTokens =
                estimateInput(request),
            mediaItems =
                images.size,
            outputTokens = {
                text ->
                ApproximateTokenEstimator
                    .estimate(text)
            }
        ) {
            delegate.analyzeImages(
                request,
                images
            )
        }

    private fun estimateInput(
        request: AiTextRequest
    ): Int =
        ApproximateTokenEstimator
            .estimate(
                request.instructions
            ) +
            request.messages
                .sumOf {
                    ApproximateTokenEstimator
                        .estimate(
                            it.content
                        ) + 4
                }

    private suspend fun <T> observe(
        operation: PrimeAiOperation,
        model: String?,
        inputTokens: Int,
        mediaItems: Int,
        outputTokens: (T) -> Int,
        block: suspend () -> T
    ): T {
        val start =
            System.nanoTime()
        return try {
            val result = block()
            record(
                operation =
                    operation,
                model = model,
                startNanos = start,
                inputTokens =
                    inputTokens,
                outputTokens =
                    outputTokens(result),
                mediaItems =
                    mediaItems,
                success = true,
                errorType = null
            )
            result
        } catch (
            e: CancellationException
        ) {
            record(
                operation =
                    operation,
                model = model,
                startNanos = start,
                inputTokens =
                    inputTokens,
                outputTokens = 0,
                mediaItems =
                    mediaItems,
                success = false,
                errorType =
                    "cancelled"
            )
            throw e
        } catch (e: Throwable) {
            record(
                operation =
                    operation,
                model = model,
                startNanos = start,
                inputTokens =
                    inputTokens,
                outputTokens = 0,
                mediaItems =
                    mediaItems,
                success = false,
                errorType =
                    e.javaClass
                        .simpleName
                        .take(80)
            )
            throw e
        }
    }

    private fun record(
        operation: PrimeAiOperation,
        model: String?,
        startNanos: Long,
        inputTokens: Int,
        outputTokens: Int,
        mediaItems: Int,
        success: Boolean,
        errorType: String?
    ) {
        val duration =
            (
                System.nanoTime() -
                    startNanos
                ) / 1_000_000L

        runCatching {
            sink.record(
                PrimeAiUsageEvent(
                    id = UUID
                        .randomUUID()
                        .toString(),
                    timestamp =
                        System
                            .currentTimeMillis(),
                    providerId =
                        providerId,
                    operation =
                        operation,
                    model = model,
                    durationMs =
                        duration
                            .coerceAtLeast(
                                0
                            ),
                    estimatedInputTokens =
                        inputTokens
                            .coerceAtLeast(
                                0
                            ),
                    estimatedOutputTokens =
                        outputTokens
                            .coerceAtLeast(
                                0
                            ),
                    mediaItems =
                        mediaItems
                            .coerceAtLeast(
                                0
                            ),
                    success =
                        success,
                    errorType =
                        errorType
                )
            )
        }
    }
}
