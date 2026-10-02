package com.mobilemcp.pro

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

class ResponsesStreamTest {
    private fun read(text: String, delta: ((String) -> Unit)? = null) = ResponsesStream.read(StringReader(text).buffered(), delta)
    private val done = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
    @Test fun keepsStandaloneWhitespace() {
        val chunks = mutableListOf<String>()
        val input = listOf("سلام", " ", "دنیا").joinToString("") {
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"$it\"}\n\n"
        } + done
        assertEquals("سلام دنیا", read(input) { chunks += it })
        assertEquals(listOf("سلام", " ", "دنیا"), chunks)
    }
    @Test fun completedEventStopsReadingImmediately() {
        val lines = arrayOf("data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}", "", done.trim(), "")
        var index = 0
        val reader = object : BufferedReader(StringReader("")) {
            override fun readLine(): String {
                if (index == lines.size) throw AssertionError("Read beyond the terminal event")
                return lines[index++]
            }
        }
        assertEquals("ok", ResponsesStream.read(reader))
    }
    @Test(expected = IOException::class) fun truncatedReplyFails() {
        read("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n")
    }
    @Test(expected = IOException::class) fun doneWithoutCompletionFails() { read("data: [DONE]\n\n") }
    @Test(expected = IllegalStateException::class) fun blankCompletedReplyFails() { read(done) }
    @Test fun extractsFinalTextWhenThereAreNoDeltas() {
        assertEquals("پاسخ واقعی", read("data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"پاسخ واقعی\"}]}]}}\n\n"))
    }
    @Test fun readsMultilineSseDataAndComments() {
        assertEquals("ok", read(": heartbeat\n\ndata: {\"type\":\"response.output_text.delta\",\ndata: \"delta\":\"ok\"}\n\n" + done))
    }
    @Test(expected = IllegalStateException::class) fun apiErrorIsNotAnAnswer() {
        read("data: {\"type\":\"error\",\"message\":\"quota exhausted\"}\n\n")
    }
    @Test fun refusalIsSpokenAsText() {
        assertEquals("نمی‌توانم", read("data: {\"type\":\"response.refusal.delta\",\"delta\":\"نمی‌توانم\"}\n\n" + done))
    }
}
