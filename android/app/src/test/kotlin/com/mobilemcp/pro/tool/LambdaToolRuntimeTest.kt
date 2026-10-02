package com.mobilemcp.pro.tool

import com.mobilemcp.pro.PrimeActionResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LambdaToolRuntimeTest {
    @Test fun delegatesContextAndExecution() = runBlocking {
        val runtime = LambdaToolRuntime(
            contextReader = { task -> "context:$task" },
            executor = { command, params ->
                PrimeActionResult(true, "$command:${params.optString(\"value\")}")
            }
        )

        assertEquals("context:test", runtime.readContext("test"))
        assertEquals(
            "tap:42",
            runtime.execute("tap", JSONObject().put("value", "42")).summary
        )
    }
}
