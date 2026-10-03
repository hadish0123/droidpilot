package com.mobilemcp.pro

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

internal data class PrimeExternalToolDefinition(
    val command: String,
    val displayName: String,
    val description: String?,
    val inputSchema: JSONObject?,
    val minimumRisk: ToolRisk,
    val sourceLabel: String
) {
    fun promptLine(): String {
        val schema = inputSchema
            ?.toString()
            ?.take(800)
            ?: "{}"

        val descriptionText = description
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(220)
            ?.takeIf { it.isNotBlank() }

        return buildString {
            append("- ")
            append(command)
            append(" [minimum_risk=")
            append(
                minimumRisk.name.lowercase(
                    Locale.ROOT
                )
            )
            append(", source=")
            append(sourceLabel.take(80))
            append("] params schema: ")
            append(schema)
            descriptionText?.let {
                append(" description: ")
                append(it)
            }
        }
    }
}

internal interface PrimeExternalToolSource {
    fun definitions(): List<PrimeExternalToolDefinition>

    suspend fun execute(
        command: String,
        params: JSONObject
    ): PrimeActionResult
}

internal object EmptyPrimeExternalToolSource :
    PrimeExternalToolSource {
    override fun definitions():
        List<PrimeExternalToolDefinition> =
        emptyList()

    override suspend fun execute(
        command: String,
        params: JSONObject
    ): PrimeActionResult =
        PrimeActionResult(
            false,
            "External tool is unavailable: " + command
        )
}

internal object PrimeMcpToolRisk {
    fun minimumRisk(tool: PrimeMcpTool): ToolRisk {
        val annotations = tool.annotations
        if (
            annotations?.optBoolean(
                "destructiveHint",
                false
            ) == true
        ) {
            return ToolRisk.DESTRUCTIVE
        }

        if (
            annotations?.optBoolean(
                "readOnlyHint",
                false
            ) == true
        ) {
            return ToolRisk.READ
        }

        return ToolRisk.SENSITIVE
    }
}

internal class PrimeMcpToolSource(
    private val serverStore: PrimeMcpServerStore,
    private val client: PrimeMcpClient
) : PrimeExternalToolSource {

    private data class Target(
        val serverId: String,
        val toolName: String
    )

    private val lock = Any()
    private val discoveries =
        linkedMapOf<String, PrimeMcpDiscovery>()

    fun updateDiscovery(
        discovery: PrimeMcpDiscovery
    ) {
        synchronized(lock) {
            discoveries[discovery.serverId] =
                discovery
        }
    }

    fun removeServer(serverId: String) {
        synchronized(lock) {
            discoveries.remove(serverId)
        }
    }

    fun clear() {
        synchronized(lock) {
            discoveries.clear()
        }
    }

    override fun definitions():
        List<PrimeExternalToolDefinition> {
        val enabledIds = serverStore
            .list()
            .asSequence()
            .filter { it.enabled }
            .map { it.id }
            .toSet()

        val snapshot = synchronized(lock) {
            discoveries.values
                .filter {
                    it.serverId in enabledIds
                }
                .toList()
        }

        return snapshot
            .flatMap { discovery ->
                discovery.tools
                    .take(40)
                    .map { tool ->
                        PrimeExternalToolDefinition(
                            command = commandFor(
                                discovery.serverId,
                                tool.name
                            ),
                            displayName = tool.name,
                            description =
                                tool.description,
                            inputSchema =
                                tool.inputSchema,
                            minimumRisk =
                                PrimeMcpToolRisk
                                    .minimumRisk(
                                        tool
                                    ),
                            sourceLabel =
                                discovery.serverName
                        )
                    }
            }
            .take(80)
    }

    override suspend fun execute(
        command: String,
        params: JSONObject
    ): PrimeActionResult {
        val target = resolveTarget(command)
            ?: return PrimeActionResult(
                false,
                "MCP tool is no longer available: " +
                    command
            )

        val server = serverStore
            .get(target.serverId)
            ?.takeIf { it.enabled }
            ?: return PrimeActionResult(
                false,
                "MCP server is disabled or missing"
            )

        return try {
            val result = client.callTool(
                config = server,
                toolName = target.toolName,
                arguments = params
            )

            val summary = result.text
                .trim()
                .take(6_000)
                .ifBlank {
                    if (result.isError) {
                        "MCP tool returned an error"
                    } else {
                        "MCP tool completed"
                    }
                }

            PrimeActionResult(
                success = !result.isError,
                summary = summary
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PrimeActionResult(
                false,
                (
                    e.message
                        ?: "MCP tool execution failed"
                    ).take(1_000)
            )
        }
    }

    private fun resolveTarget(
        command: String
    ): Target? {
        val enabledIds = serverStore
            .list()
            .filter { it.enabled }
            .map { it.id }
            .toSet()

        val snapshot = synchronized(lock) {
            discoveries.values.toList()
        }

        snapshot.forEach { discovery ->
            if (
                discovery.serverId !in
                enabledIds
            ) {
                return@forEach
            }

            discovery.tools
                .take(40)
                .forEach { tool ->
                    if (
                        commandFor(
                            discovery.serverId,
                            tool.name
                        ) == command
                    ) {
                        return Target(
                            discovery.serverId,
                            tool.name
                        )
                    }
                }
        }

        return null
    }

    companion object {
        internal fun commandFor(
            serverId: String,
            toolName: String
        ): String {
            val serverHash =
                sha256(serverId).take(10)
            val toolHash =
                sha256(toolName).take(8)
            val safeName = toolName
                .lowercase(Locale.ROOT)
                .replace(
                    Regex("[^a-z0-9_\\-]+"),
                    "_"
                )
                .trim('_')
                .ifBlank { "tool" }
                .take(48)

            return "mcp__" +
                serverHash +
                "__" +
                safeName +
                "__" +
                toolHash
        }

        private fun sha256(
            value: String
        ): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    value.toByteArray(
                        Charsets.UTF_8
                    )
                )
                .joinToString("") {
                    "%02x".format(
                        it.toInt() and 0xff
                    )
                }
    }
}

internal sealed class PrimeExternalToolExecution {
    data class Completed(
        val result: PrimeActionResult,
        val assessment: ToolRiskAssessment
    ) : PrimeExternalToolExecution()

    data class NeedsConfirmation(
        val message: String,
        val assessment: ToolRiskAssessment
    ) : PrimeExternalToolExecution()

    data class Rejected(
        val message: String
    ) : PrimeExternalToolExecution()
}

internal class PrimeExternalToolRuntime(
    definitions: List<PrimeExternalToolDefinition>,
    private val source: PrimeExternalToolSource
) {
    private val byCommand =
        definitions.associateBy {
            it.command
        }

    suspend fun execute(
        command: String,
        params: JSONObject,
        declaredRisk: String?,
        confirmedForTask: Boolean
    ): PrimeExternalToolExecution {
        val definition = byCommand[command]
            ?: return PrimeExternalToolExecution.Rejected(
                "External tool is not registered: " +
                    command
            )

        val risk = ToolRisk.max(
            definition.minimumRisk,
            ToolRisk.parse(declaredRisk)
        )
        val assessment =
            ToolRiskAssessment(
                risk = risk,
                requiresConfirmation =
                    risk == ToolRisk.SENSITIVE ||
                        risk == ToolRisk.DESTRUCTIVE,
                reason =
                    "external " +
                        definition.sourceLabel +
                        " tool"
            )

        if (
            assessment.requiresConfirmation &&
            !confirmedForTask
        ) {
            val verb = if (
                risk == ToolRisk.DESTRUCTIVE
            ) {
                "یک ابزار خارجی با اثر مخرب"
            } else {
                "یک ابزار خارجی حساس"
            }

            return PrimeExternalToolExecution
                .NeedsConfirmation(
                    message =
                        verb +
                            " از «" +
                            definition.sourceLabel +
                            "» برای «" +
                            definition.displayName +
                            "» اجرا شود؟",
                    assessment = assessment
                )
        }

        val result = try {
            source.execute(
                command,
                params
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PrimeActionResult(
                false,
                e.message
                    ?: "External tool execution failed"
            )
        }

        return PrimeExternalToolExecution
            .Completed(
                result = result,
                assessment = assessment
            )
    }
}
