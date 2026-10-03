package com.mobilemcp.pro

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeExternalToolRuntimeTest {

    @Test
    fun mcpRiskDefaultsToSensitiveWithoutAnnotations() {
        assertEquals(
            ToolRisk.SENSITIVE,
            PrimeMcpToolRisk.minimumRisk(
                PrimeMcpTool(
                    name = "write_anything",
                    description = null,
                    inputSchema = null,
                    annotations = null
                )
            )
        )
    }

    @Test
    fun readOnlyAndDestructiveHintsElevatePolicy() {
        assertEquals(
            ToolRisk.READ,
            PrimeMcpToolRisk.minimumRisk(
                PrimeMcpTool(
                    name = "read",
                    description = null,
                    inputSchema = null,
                    annotations = JSONObject()
                        .put(
                            "readOnlyHint",
                            true
                        )
                )
            )
        )

        assertEquals(
            ToolRisk.DESTRUCTIVE,
            PrimeMcpToolRisk.minimumRisk(
                PrimeMcpTool(
                    name = "delete",
                    description = null,
                    inputSchema = null,
                    annotations = JSONObject()
                        .put(
                            "readOnlyHint",
                            true
                        )
                        .put(
                            "destructiveHint",
                            true
                        )
                )
            )
        )
    }

    @Test
    fun namespacedCommandsDoNotCollideAcrossServers() {
        val first =
            PrimeMcpToolSource.commandFor(
                "server-a",
                "search"
            )
        val second =
            PrimeMcpToolSource.commandFor(
                "server-b",
                "search"
            )
        val differentTool =
            PrimeMcpToolSource.commandFor(
                "server-a",
                "search!"
            )

        assertNotEquals(first, second)
        assertNotEquals(
            first,
            differentTool
        )
        assertTrue(
            first.startsWith("mcp__")
        )
    }

    @Test
    fun sensitiveExternalToolIsBlockedBeforeSource() =
        runBlocking {
            var calls = 0
            val definition =
                PrimeExternalToolDefinition(
                    command =
                        "mcp__abc__send__123",
                    displayName = "send",
                    description = null,
                    inputSchema = JSONObject(),
                    minimumRisk =
                        ToolRisk.SENSITIVE,
                    sourceLabel = "CRM"
                )

            val source =
                object :
                    PrimeExternalToolSource {
                    override fun definitions() =
                        listOf(definition)

                    override suspend fun execute(
                        command: String,
                        params: JSONObject
                    ): PrimeActionResult {
                        calls += 1
                        return PrimeActionResult(
                            true,
                            "sent"
                        )
                    }
                }

            val execution =
                PrimeExternalToolRuntime(
                    listOf(definition),
                    source
                ).execute(
                    command =
                        definition.command,
                    params = JSONObject(),
                    declaredRisk = "safe",
                    confirmedForTask = false
                )

            assertTrue(
                execution is
                    PrimeExternalToolExecution
                        .NeedsConfirmation
            )
            assertEquals(0, calls)
        }

    @Test
    fun readOnlyExternalToolExecutesWithoutConfirmation() =
        runBlocking {
            var calls = 0
            val definition =
                PrimeExternalToolDefinition(
                    command =
                        "mcp__abc__lookup__123",
                    displayName = "lookup",
                    description = null,
                    inputSchema = JSONObject(),
                    minimumRisk =
                        ToolRisk.READ,
                    sourceLabel = "Docs"
                )

            val source =
                object :
                    PrimeExternalToolSource {
                    override fun definitions() =
                        listOf(definition)

                    override suspend fun execute(
                        command: String,
                        params: JSONObject
                    ): PrimeActionResult {
                        calls += 1
                        return PrimeActionResult(
                            true,
                            "found"
                        )
                    }
                }

            val execution =
                PrimeExternalToolRuntime(
                    listOf(definition),
                    source
                ).execute(
                    command =
                        definition.command,
                    params = JSONObject(),
                    declaredRisk = "read",
                    confirmedForTask = false
                )

            assertTrue(
                execution is
                    PrimeExternalToolExecution
                        .Completed
            )
            assertEquals(1, calls)
            assertFalse(
                (execution as
                    PrimeExternalToolExecution
                        .Completed)
                    .assessment
                    .requiresConfirmation
            )
        }
}
