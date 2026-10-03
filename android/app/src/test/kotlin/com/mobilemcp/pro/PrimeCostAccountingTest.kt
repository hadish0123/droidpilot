package com.mobilemcp.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeCostAccountingTest {

    @Test
    fun codecRoundTripsRates() {
        val rates = listOf(
            PrimeCostRate(
                id = "r1",
                providerId = "gemini",
                modelPattern = "model-a",
                inputUsdPerMillion = 0.25,
                outputUsdPerMillion = 1.0,
                updatedAt = 9L
            )
        )

        assertEquals(
            rates,
            PrimeCostCodec.decode(
                PrimeCostCodec.encode(
                    rates
                )
            )
        )
    }

    @Test
    fun exactModelRateBeatsProviderWildcard() {
        val event = event(
            provider = "gemini",
            model = "model-a",
            input = 1_000_000,
            output = 1_000_000
        )
        val rates = listOf(
            PrimeCostRate(
                "wild",
                "gemini",
                "*",
                1.0,
                1.0,
                20L
            ),
            PrimeCostRate(
                "exact",
                "gemini",
                "model-a",
                2.0,
                3.0,
                10L
            )
        )

        val estimate =
            PrimeCostCalculator.estimate(
                listOf(event),
                rates
            )

        assertEquals(
            5.0,
            estimate.usd,
            0.000001
        )
        assertEquals(
            1,
            estimate.matchedEvents
        )
        assertEquals(
            0,
            estimate.unmatchedEvents
        )
    }

    @Test
    fun unmatchedUsageIsNotInventedAsZeroCost() {
        val estimate =
            PrimeCostCalculator.estimate(
                listOf(
                    event(
                        provider = "anthropic",
                        model = "unknown",
                        input = 100,
                        output = 50
                    )
                ),
                emptyList()
            )

        assertEquals(
            0.0,
            estimate.usd,
            0.0
        )
        assertEquals(
            0,
            estimate.matchedEvents
        )
        assertEquals(
            1,
            estimate.unmatchedEvents
        )
    }

    @Test
    fun tokenCostUsesPerMillionRates() {
        val estimate =
            PrimeCostCalculator.estimate(
                listOf(
                    event(
                        provider =
                            "openrouter",
                        model = "m",
                        input = 250_000,
                        output = 500_000
                    )
                ),
                listOf(
                    PrimeCostRate(
                        "r",
                        "openrouter",
                        "m",
                        4.0,
                        8.0,
                        1L
                    )
                )
            )

        assertTrue(
            estimate.usd >
                4.999 &&
                estimate.usd <
                5.001
        )
    }

    private fun event(
        provider: String,
        model: String?,
        input: Int,
        output: Int
    ) = PrimeAiUsageEvent(
        id = "e",
        timestamp = 1L,
        providerId = provider,
        operation = PrimeAiOperation.TEXT,
        model = model,
        durationMs = 1L,
        estimatedInputTokens = input,
        estimatedOutputTokens = output,
        mediaItems = 0,
        success = true,
        errorType = null
    )
}
