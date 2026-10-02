package com.mobilemcp.pro

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeToolRuntimeTest {

    @Test
    fun unknownToolsNeverReachTheRunner() = runBlocking {
        var calls = 0
        val runtime = PrimeToolRuntime { _, _ ->
            calls += 1
            PrimeActionResult(true, "unexpected")
        }

        val execution = runtime.execute(
            command = "shell_exec",
            params = JSONObject(),
            declaredRisk = "safe",
            confirmedForTask = true
        )

        assertTrue(execution is PrimeToolExecution.Rejected)
        assertEquals(0, calls)
    }

    @Test
    fun sensitiveSemanticClickRequiresConfirmation() = runBlocking {
        var calls = 0
        val runtime = PrimeToolRuntime { _, _ ->
            calls += 1
            PrimeActionResult(true, "sent")
        }

        val execution = runtime.execute(
            command = "click_element",
            params = JSONObject().put("text", "ارسال"),
            declaredRisk = "write",
            confirmedForTask = false
        )

        assertTrue(execution is PrimeToolExecution.NeedsConfirmation)
        assertEquals(0, calls)
        val assessment =
            (execution as PrimeToolExecution.NeedsConfirmation).assessment
        assertEquals(ToolRisk.SENSITIVE, assessment.risk)
    }

    @Test
    fun confirmedSensitiveActionExecutesExactlyOnce() = runBlocking {
        var calls = 0
        val runtime = PrimeToolRuntime { command, params ->
            calls += 1
            assertEquals("click_element", command)
            assertEquals("send_button", params.getString("id"))
            PrimeActionResult(true, "sent")
        }

        val execution = runtime.execute(
            command = "click_element",
            params = JSONObject().put("id", "send_button"),
            declaredRisk = null,
            confirmedForTask = true
        )

        assertTrue(execution is PrimeToolExecution.Completed)
        assertEquals(1, calls)
        assertTrue(
            (execution as PrimeToolExecution.Completed).result.success
        )
    }

    @Test
    fun declaredRiskCanElevateButNeverLowerRuntimeRisk() {
        val write = PrimeToolPolicy.assess(
            "set_text",
            JSONObject().put("text", "draft"),
            "safe"
        )!!
        assertEquals(ToolRisk.WRITE, write.risk)
        assertFalse(write.requiresConfirmation)

        val elevated = PrimeToolPolicy.assess(
            "open_app",
            JSONObject().put("name", "Settings"),
            "destructive"
        )!!
        assertEquals(ToolRisk.DESTRUCTIVE, elevated.risk)
        assertTrue(elevated.requiresConfirmation)
    }

    @Test
    fun registryPromptAndRuntimeShareTheSameAllowlist() {
        val contract = PrimeToolRegistry.promptContract()
        assertTrue(contract.contains("open_app"))
        assertTrue(contract.contains("click_element"))
        assertTrue(contract.contains("get_ui_tree"))
        assertFalse(contract.contains("shell_exec"))
    }
}
