package com.mobilemcp.pro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal data class PrimeCostRate(
    val id: String,
    val providerId: String,
    val modelPattern: String,
    val inputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
    val updatedAt: Long
) {
    init {
        require(providerId.isNotBlank()) {
            "Provider ID is required"
        }
        require(modelPattern.isNotBlank()) {
            "Model pattern is required"
        }
        require(
            inputUsdPerMillion.isFinite() &&
                inputUsdPerMillion >= 0.0
        ) {
            "Input price must be non-negative"
        }
        require(
            outputUsdPerMillion.isFinite() &&
                outputUsdPerMillion >= 0.0
        ) {
            "Output price must be non-negative"
        }
    }

    fun matches(
        provider: String,
        model: String?
    ): Boolean {
        if (
            !providerId.equals(
                provider,
                ignoreCase = true
            )
        ) {
            return false
        }

        return modelPattern == "*" ||
            (
                model != null &&
                    modelPattern.equals(
                        model,
                        ignoreCase = true
                    )
                )
    }
}

internal data class PrimeCostEstimate(
    val usd: Double,
    val matchedEvents: Int,
    val unmatchedEvents: Int
)

internal object PrimeCostCodec {
    fun encode(
        rates: List<PrimeCostRate>
    ): String {
        val array = JSONArray()
        rates.forEach { rate ->
            array.put(
                JSONObject()
                    .put("id", rate.id)
                    .put(
                        "providerId",
                        rate.providerId
                    )
                    .put(
                        "modelPattern",
                        rate.modelPattern
                    )
                    .put(
                        "inputUsdPerMillion",
                        rate.inputUsdPerMillion
                    )
                    .put(
                        "outputUsdPerMillion",
                        rate.outputUsdPerMillion
                    )
                    .put(
                        "updatedAt",
                        rate.updatedAt
                    )
            )
        }
        return array.toString()
    }

    fun decode(
        raw: String?
    ): List<PrimeCostRate> {
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
                        array.optJSONObject(index)
                            ?: continue
                    val provider =
                        json.optString(
                            "providerId"
                        ).trim()
                    val pattern =
                        json.optString(
                            "modelPattern"
                        ).trim()
                    val input =
                        json.optDouble(
                            "inputUsdPerMillion",
                            Double.NaN
                        )
                    val output =
                        json.optDouble(
                            "outputUsdPerMillion",
                            Double.NaN
                        )
                    if (
                        provider.isBlank() ||
                        pattern.isBlank() ||
                        !input.isFinite() ||
                        !output.isFinite() ||
                        input < 0.0 ||
                        output < 0.0
                    ) {
                        continue
                    }

                    add(
                        PrimeCostRate(
                            id =
                                json.optString(
                                    "id"
                                )
                                    .trim()
                                    .ifBlank {
                                        UUID
                                            .randomUUID()
                                            .toString()
                                    },
                            providerId =
                                provider.take(80),
                            modelPattern =
                                pattern.take(160),
                            inputUsdPerMillion =
                                input,
                            outputUsdPerMillion =
                                output,
                            updatedAt =
                                json.optLong(
                                    "updatedAt"
                                )
                        )
                    )
                }
            }
        }.getOrDefault(
            emptyList()
        )
    }
}

internal object PrimeCostCalculator {
    fun rateFor(
        event: PrimeAiUsageEvent,
        rates: List<PrimeCostRate>
    ): PrimeCostRate? {
        val matches = rates
            .filter {
                it.matches(
                    event.providerId,
                    event.model
                )
            }

        return matches
            .sortedWith(
                compareByDescending<PrimeCostRate> {
                    it.modelPattern != "*"
                }.thenByDescending {
                    it.updatedAt
                }
            )
            .firstOrNull()
    }

    fun estimate(
        events: List<PrimeAiUsageEvent>,
        rates: List<PrimeCostRate>
    ): PrimeCostEstimate {
        var usd = 0.0
        var matched = 0
        var unmatched = 0

        events.forEach { event ->
            val rate = rateFor(
                event,
                rates
            )
            if (rate == null) {
                unmatched += 1
                return@forEach
            }

            usd +=
                event.estimatedInputTokens
                    .coerceAtLeast(0)
                    .toDouble() /
                    1_000_000.0 *
                    rate.inputUsdPerMillion
            usd +=
                event.estimatedOutputTokens
                    .coerceAtLeast(0)
                    .toDouble() /
                    1_000_000.0 *
                    rate.outputUsdPerMillion
            matched += 1
        }

        return PrimeCostEstimate(
            usd = usd,
            matchedEvents = matched,
            unmatchedEvents = unmatched
        )
    }
}

internal class PrimeCostStore(
    context: Context,
    private val secureStore:
        SecureStore =
        SecureStore(
            context.applicationContext
        )
) {
    companion object {
        private const val STORE_KEY =
            "prime_ai_cost_rates_v1"
        private const val MAX_RATES = 80
    }

    @Synchronized
    fun list(): List<PrimeCostRate> =
        readAll()
            .sortedByDescending {
                it.updatedAt
            }

    @Synchronized
    fun save(
        providerId: String,
        modelPattern: String,
        inputUsdPerMillion: Double,
        outputUsdPerMillion: Double
    ): PrimeCostRate {
        val provider = providerId
            .trim()
            .lowercase(Locale.ROOT)
            .take(80)
        val pattern = modelPattern
            .trim()
            .ifBlank { "*" }
            .take(160)

        require(provider.isNotBlank()) {
            "Provider ID is required"
        }
        require(
            inputUsdPerMillion.isFinite() &&
                inputUsdPerMillion >= 0.0
        ) {
            "Input price must be non-negative"
        }
        require(
            outputUsdPerMillion.isFinite() &&
                outputUsdPerMillion >= 0.0
        ) {
            "Output price must be non-negative"
        }

        val rates =
            readAll().toMutableList()
        val index = rates.indexOfFirst {
            it.providerId.equals(
                provider,
                ignoreCase = true
            ) &&
                it.modelPattern.equals(
                    pattern,
                    ignoreCase = true
                )
        }
        val now =
            System.currentTimeMillis()
        val rate =
            PrimeCostRate(
                id =
                    if (index >= 0) {
                        rates[index].id
                    } else {
                        UUID.randomUUID()
                            .toString()
                    },
                providerId = provider,
                modelPattern = pattern,
                inputUsdPerMillion =
                    inputUsdPerMillion,
                outputUsdPerMillion =
                    outputUsdPerMillion,
                updatedAt = now
            )

        if (index >= 0) {
            rates[index] = rate
        } else {
            require(
                rates.size < MAX_RATES
            ) {
                "Maximum cost rate count reached"
            }
            rates += rate
        }

        writeAll(rates)
        return rate
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val rates =
            readAll().toMutableList()
        val changed =
            rates.removeAll {
                it.id == id
            }
        if (changed) {
            writeAll(rates)
        }
        return changed
    }

    @Synchronized
    fun clear() {
        secureStore.remove(
            STORE_KEY
        )
    }

    @Synchronized
    fun estimate(
        events: List<PrimeAiUsageEvent>
    ): PrimeCostEstimate =
        PrimeCostCalculator.estimate(
            events,
            readAll()
        )

    private fun readAll():
        List<PrimeCostRate> =
        PrimeCostCodec.decode(
            secureStore.getString(
                STORE_KEY
            )
        )

    private fun writeAll(
        rates: List<PrimeCostRate>
    ) {
        secureStore.putString(
            STORE_KEY,
            PrimeCostCodec.encode(
                rates.takeLast(
                    MAX_RATES
                )
            )
        )
    }
}
