package com.mobilemcp.pro.voice

object SpeechText {
    fun chunks(text: String, limit: Int = 180): List<String> {
        require(limit >= 20)
        val cleaned = text.replace(Regex("https?://\\S+"), "لینک")
            .replace(Regex("[\\*#`_]"), "")
            .replace("PRIME", "پرایم", ignoreCase = true)
            .replace("P6", "پی شش", ignoreCase = true)
            .replace(Regex("\\s+"), " ").trim()
        val result = mutableListOf<String>()
        var rest = cleaned
        while (rest.isNotEmpty()) {
            var end = rest.length.coerceAtMost(limit)
            if (rest.length > limit) {
                val sentence = rest.take(limit).indexOfLast { it in ".!؟?؛" }
                val word = rest.lastIndexOf(' ', limit - 1)
                end = when {
                    sentence >= 30 -> sentence + 1
                    word > 0 -> word
                    else -> limit
                }
            }
            result += rest.take(end).trim()
            rest = rest.drop(end).trimStart()
        }
        return result.filter { it.isNotBlank() }
    }
}
