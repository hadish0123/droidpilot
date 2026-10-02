package com.mobilemcp.pro.tools

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal data class ToolInvocation(
    val name: String,
    val params: JSONObject
)

internal data class ToolExecutionResult(
    val success: Boolean,
    val summary: String,
    val risk: ToolRisk? = null
)

internal class ToolRuntime(
    private val registry: ToolRegistry = ToolRegistry.default()
) {
    suspend fun execute(
        invocation: ToolInvocation,
        executor: suspend (ToolInvocation) -> ToolExecutionResult
    ): ToolExecutionResult {
        val definition = registry.find(invocation.name)
            ?: return ToolExecutionResult(
                success = false,
                summary = "Unsupported tool: ${invocation.name}",
                risk = null
            )

        return try {
            executor(invocation).copy(risk = definition.risk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                summary = e.message ?: "Tool execution failed",
                risk = definition.risk
            )
        }
    }
}
