package com.mobilemcp.pro.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun longRepliesAreNotTruncated() {
        val text = (1..900).joinToString(" ") { "واژه$it" }
        val chunks = SpeechText.chunks(text)
        assertEquals(text, chunks.joinToString(" "))
        assertTrue(chunks.all { it.length <= 180 })
    }
    @Test fun removesMarkupAndPronouncesProductName() {
        assertEquals(listOf("پرایم پی شش: سلام لینک"), SpeechText.chunks("**PRIME P6:** سلام https://example.com"))
    }
    @Test fun blankInputHasNoAudioChunks() { assertTrue(SpeechText.chunks(" \n ").isEmpty()) }
}
