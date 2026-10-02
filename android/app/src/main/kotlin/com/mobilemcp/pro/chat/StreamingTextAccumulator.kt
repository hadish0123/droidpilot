package com.mobilemcp.pro.chat

internal class StreamingTextAccumulator {
    private val buffer = StringBuilder()

    @Synchronized
    fun append(delta: String): String {
        if (delta.isNotEmpty()) buffer.append(delta)
        return buffer.toString()
    }

    @Synchronized
    fun snapshot(): String = buffer.toString()
}
