package com.mobilemcp.pro.tool

import com.mobilemcp.pro.PrimeActionResult
import org.json.JSONObject

/**
 * Provider-neutral execution boundary for agent-visible tools.
 *
 * Android Accessibility, MCP, plugins and future connectors can implement this
 * contract without coupling PrimeAgent to their transport or lifecycle.
 */
internal interface ToolRuntime {
    suspend fun readContext(task: String): String

    suspend fun execute(
        command: String,
        params: JSONObject
    ): PrimeActionResult
}

internal class LambdaToolRuntime(
    private val contextReader: suspend (String) -> String,
    private val executor: suspend (String, JSONObject) -> PrimeActionResult
) : ToolRuntime {
    override suspend fun readContext(task: String): String = contextReader(task)

    override suspend fun execute(
        command: String,
        params: JSONObject
    ): PrimeActionResult = executor(command, params)
}
