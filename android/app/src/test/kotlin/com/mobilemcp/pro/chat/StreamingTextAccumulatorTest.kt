package com.mobilemcp.pro.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTextAccumulatorTest {
    @Test
    fun preservesOrderedStreamingDeltas() {
        val accumulator = StreamingTextAccumulator()

        assertEquals("سلام", accumulator.append("سلام"))
        assertEquals("سلام دنیا", accumulator.append(" دنیا"))
        assertEquals("سلام دنیا", accumulator.snapshot())
    }

    @Test
    fun emptyDeltaDoesNotChangeSnapshot() {
        val accumulator = StreamingTextAccumulator()
        accumulator.append("PRIME")

        assertEquals("PRIME", accumulator.append(""))
    }
}
