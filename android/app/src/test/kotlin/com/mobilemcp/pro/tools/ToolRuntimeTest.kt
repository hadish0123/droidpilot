package com.mobilemcp.pro.tools

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRuntimeTest {
    private val runtime = ToolRuntime()

    @Test
    fun unknownToolIsRejectedBeforeExecutorRuns() = runBlocking {
        var executed = false

        val result = runtime.execute(
            ToolInvocation("shell_exec", JSONObject())
        ) {
            executed = true
            ToolExecutionResult(true, "unexpected")
        }

        assertFalse(executed)
        assertFalse(result.success)
        assertTrue(result.summary.contains("Unsupported tool"))
    }

    @Test
    fun registeredToolCarriesRiskMetadata() = runBlocking {
        val result = runtime.execute(
            ToolInvocation("get_ui_tree", JSONObject())
        ) {
            ToolExecutionResult(true, "ok")
        }

        assertTrue(result.success)
        assertEquals(ToolRisk.READ, result.risk)
    }
}
