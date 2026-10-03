package com.mobilemcp.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeVisionTest {
    @Test
    fun intentRequiresAnAttachedImage() {
        assertFalse(
            PrimeVisionIntent.shouldAnalyze(
                "این عکس رو توضیح بده",
                hasImages = false
            )
        )
        assertTrue(
            PrimeVisionIntent.shouldAnalyze(
                "این عکس رو توضیح بده",
                hasImages = true
            )
        )
        assertTrue(
            PrimeVisionIntent.shouldAnalyze(
                "این رو تحلیل کن",
                hasImages = true
            )
        )
    }

    @Test
    fun sessionKeepsOnlyBoundedRecentImages() {
        val session =
            PrimeImageSession(
                maxImages = 2,
                maxTotalBytes = 20
            )

        repeat(3) { index ->
            session.add(
                AiImageInput(
                    name =
                        "image-$index.png",
                    mimeType =
                        "image/png",
                    dataUrl =
                        "data:image/png;base64,AA=="
                ),
                byteSize = 4
            )
        }

        val images =
            session.images()
        assertEquals(2, images.size)
        assertEquals(
            "image-1.png",
            images.first().name
        )
        assertEquals(
            "image-2.png",
            images.last().name
        )
    }
}
