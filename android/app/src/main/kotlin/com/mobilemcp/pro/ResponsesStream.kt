package com.mobilemcp.pro

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException

/** SSE event framing and terminal validation for the public Responses API. */
object ResponsesStream {
    fun read(reader: BufferedReader, onDelta: ((String) -> Unit)? = null, checkCancelled: () -> Unit = {}): String {
        val output = StringBuilder()
        val data = mutableListOf<String>()
        fun dispatch(): String? {
            if (data.isEmpty()) return null
            val payload = data.joinToString("\n")
            data.clear()
            if (payload == "[DONE]") throw IOException("PRIME_STREAM_INCOMPLETE: no completion event")
            val event = try { JSONObject(payload) } catch (e: Exception) {
                throw IOException("Invalid Responses stream event", e)
            }
            when (event.optString("type")) {
                "response.output_text.delta", "response.refusal.delta" -> {
                    val delta = event.optString("delta")
                    output.append(delta)
                    // A standalone space/newline is meaningful between words.
                    if (delta.isNotEmpty()) onDelta?.invoke(delta)
                }
                "response.completed" -> {
                    val response = event.optJSONObject("response")
                    val status = response?.optString("status").orEmpty()
                    if (status.isNotEmpty() && status != "completed") {
                        throw IOException("PRIME_STREAM_INCOMPLETE: $status")
                    }
                    if (output.isEmpty()) {
                        val items = response?.optJSONArray("output")
                        if (items != null) for (i in 0 until items.length()) {
                            val content = items.optJSONObject(i)?.optJSONArray("content") ?: continue
                            for (j in 0 until content.length()) {
                                val part = content.optJSONObject(j) ?: continue
                                when (part.optString("type")) {
                                    "output_text" -> output.append(part.optString("text"))
                                    "refusal" -> output.append(part.optString("refusal"))
                                }
                            }
                        }
                    }
                    return output.toString().trim().ifBlank { throw IllegalStateException("پاسخ متنی از ChatGPT دریافت نشد.") }
                }
                "error", "response.failed" -> {
                    val error = event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error") ?: event
                    throw IllegalStateException(error.optString("message").ifBlank { "ChatGPT request failed: ${error.optString("code")}" })
                }
                "response.incomplete" -> throw IOException("PRIME_STREAM_INCOMPLETE: response incomplete")
            }
            return null
        }
        while (true) {
            checkCancelled()
            val line = reader.readLine() ?: break
            if (line.isEmpty()) dispatch()?.let { return it }
            else if (line.startsWith("data:")) data += line.substring(5).removePrefix(" ")
        }
        dispatch()?.let { return it }
        throw IOException("PRIME_STREAM_INCOMPLETE: stream ended before completion")
    }
}
