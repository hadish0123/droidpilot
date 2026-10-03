package com.mobilemcp.pro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

internal enum class PrimeMcpProtocolEra {
    MODERN_2026,
    LEGACY_2025
}

internal data class PrimeMcpTool(
    val name: String,
    val description: String?,
    val inputSchema: JSONObject?
)

internal data class PrimeMcpResource(
    val uri: String,
    val name: String?,
    val description: String?,
    val mimeType: String?
)

internal data class PrimeMcpPrompt(
    val name: String,
    val description: String?
)

internal data class PrimeMcpDiscovery(
    val serverId: String,
    val serverName: String,
    val era: PrimeMcpProtocolEra,
    val protocolVersion: String,
    val remoteServerName: String?,
    val remoteServerVersion: String?,
    val tools: List<PrimeMcpTool>,
    val resources: List<PrimeMcpResource>,
    val prompts: List<PrimeMcpPrompt>,
    val connectedAt: Long
)

internal data class PrimeMcpCallResult(
    val isError: Boolean,
    val text: String,
    val raw: JSONObject
)

internal class PrimeMcpException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

private data class PrimeMcpHttpResponse(
    val status: Int,
    val contentType: String?,
    val body: String,
    val headers: Map<String, List<String>>
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull {
            it.key.equals(
                name,
                ignoreCase = true
            )
        }?.value?.firstOrNull()
}

private data class PrimeMcpConnection(
    val config: PrimeMcpServerConfig,
    val era: PrimeMcpProtocolEra,
    val protocolVersion: String,
    val sessionId: String?,
    val remoteServerName: String?,
    val remoteServerVersion: String?
)

internal class PrimeMcpClient(
    private val clientName: String = "PRIME P6",
    private val clientVersion: String = "6.0.10"
) {
    companion object {
        const val MODERN_VERSION = "2026-07-28"
        const val LEGACY_VERSION = "2025-11-25"

        private val SUPPORTED_LEGACY =
            setOf(
                "2025-11-25",
                "2025-06-18",
                "2025-03-26"
            )

        private const val CONNECT_TIMEOUT_MS =
            10_000
        private const val READ_TIMEOUT_MS =
            35_000
        private const val MAX_RESPONSE_CHARS =
            2_000_000
        private const val MAX_PAGES = 10
    }

    private val ids = AtomicLong(1)

    suspend fun discover(
        config: PrimeMcpServerConfig
    ): PrimeMcpDiscovery =
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val connection = connect(config)

            val tools = listTools(connection)
            val resources = listResources(
                connection
            )
            val prompts = listPrompts(connection)

            PrimeMcpDiscovery(
                serverId = config.id,
                serverName = config.name,
                era = connection.era,
                protocolVersion =
                    connection.protocolVersion,
                remoteServerName =
                    connection.remoteServerName,
                remoteServerVersion =
                    connection.remoteServerVersion,
                tools = tools,
                resources = resources,
                prompts = prompts,
                connectedAt =
                    System.currentTimeMillis()
            )
        }

    suspend fun callTool(
        config: PrimeMcpServerConfig,
        toolName: String,
        arguments: JSONObject = JSONObject()
    ): PrimeMcpCallResult =
        withContext(Dispatchers.IO) {
            val connection = connect(config)
            val result = requestResult(
                connection = connection,
                method = "tools/call",
                params = JSONObject()
                    .put("name", toolName)
                    .put("arguments", arguments),
                mcpName = toolName
            )

            PrimeMcpCallResult(
                isError = result.optBoolean(
                    "isError",
                    false
                ),
                text = contentToText(
                    result.optJSONArray("content")
                ),
                raw = result
            )
        }

    suspend fun readResource(
        config: PrimeMcpServerConfig,
        uri: String
    ): String = withContext(Dispatchers.IO) {
        val connection = connect(config)
        val result = requestResult(
            connection,
            "resources/read",
            JSONObject().put("uri", uri),
            mcpName = uri
        )

        val contents = result.optJSONArray("contents")
            ?: return@withContext ""
        buildString {
            for (index in 0 until contents.length()) {
                val item = contents.optJSONObject(index)
                    ?: continue
                val text = item.optString("text")
                if (text.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    append(text)
                }
            }
        }
    }

    suspend fun getPrompt(
        config: PrimeMcpServerConfig,
        promptName: String,
        arguments: JSONObject? = null
    ): String = withContext(Dispatchers.IO) {
        val params = JSONObject()
            .put("name", promptName)
        if (arguments != null) {
            params.put("arguments", arguments)
        }

        val connection = connect(config)
        val result = requestResult(
            connection,
            "prompts/get",
            params,
            mcpName = promptName
        )

        val messages = result.optJSONArray("messages")
            ?: return@withContext ""

        buildString {
            for (index in 0 until messages.length()) {
                val message = messages
                    .optJSONObject(index)
                    ?: continue
                val role = message.optString("role")
                val content = message
                    .optJSONObject("content")
                val text = content
                    ?.optString("text")
                    .orEmpty()
                if (text.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    append(role.ifBlank { "message" })
                    append(": ")
                    append(text)
                }
            }
        }
    }

    private suspend fun connect(
        config: PrimeMcpServerConfig
    ): PrimeMcpConnection {
        coroutineContext.ensureActive()

        val modern = runCatching {
            discoverModern(config)
        }

        modern.getOrNull()?.let {
            return it
        }

        val modernError = modern.exceptionOrNull()
        if (
            modernError is CancellationException
        ) {
            throw modernError
        }

        return runCatching {
            initializeLegacy(config)
        }.getOrElse { legacyError ->
            if (
                legacyError is CancellationException
            ) {
                throw legacyError
            }

            throw PrimeMcpException(
                "MCP negotiation failed for " +
                    config.name +
                    ": modern probe: " +
                    safeMessage(modernError) +
                    "; legacy handshake: " +
                    safeMessage(legacyError),
                legacyError
            )
        }
    }

    private suspend fun discoverModern(
        config: PrimeMcpServerConfig
    ): PrimeMcpConnection {
        val params = JSONObject()
            .put(
                "_meta",
                modernMeta()
            )

        val response = postJsonRpc(
            config = config,
            method = "server/discover",
            params = params,
            protocolVersion = MODERN_VERSION,
            sessionId = null,
            modern = true
        )

        val result = resultOrThrow(
            response.json,
            "server/discover"
        )

        val supported = result
            .optJSONArray("supportedVersions")
        if (
            supported != null &&
            !containsString(
                supported,
                MODERN_VERSION
            )
        ) {
            throw PrimeMcpException(
                "Server does not advertise " +
                    MODERN_VERSION
            )
        }

        val serverInfo = result
            .optJSONObject("_meta")
            ?.optJSONObject(
                "io.modelcontextprotocol/serverInfo"
            )
            ?: response.json
                .optJSONObject("result")
                ?.optJSONObject("_meta")
                ?.optJSONObject(
                    "io.modelcontextprotocol/serverInfo"
                )

        return PrimeMcpConnection(
            config = config,
            era = PrimeMcpProtocolEra.MODERN_2026,
            protocolVersion = MODERN_VERSION,
            sessionId = null,
            remoteServerName = serverInfo
                ?.optString("name")
                ?.takeIf { it.isNotBlank() },
            remoteServerVersion = serverInfo
                ?.optString("version")
                ?.takeIf { it.isNotBlank() }
        )
    }

    private suspend fun initializeLegacy(
        config: PrimeMcpServerConfig
    ): PrimeMcpConnection {
        val params = JSONObject()
            .put(
                "protocolVersion",
                LEGACY_VERSION
            )
            .put(
                "capabilities",
                JSONObject()
            )
            .put(
                "clientInfo",
                JSONObject()
                    .put("name", clientName)
                    .put(
                        "version",
                        clientVersion
                    )
            )

        val response = postJsonRpc(
            config = config,
            method = "initialize",
            params = params,
            protocolVersion = null,
            sessionId = null,
            modern = false
        )

        val result = resultOrThrow(
            response.json,
            "initialize"
        )
        val selected = result
            .optString("protocolVersion")
            .ifBlank { LEGACY_VERSION }

        if (selected !in SUPPORTED_LEGACY) {
            throw PrimeMcpException(
                "Unsupported legacy MCP version: " +
                    selected
            )
        }

        val sessionId = response.http
            .header("Mcp-Session-Id")
            ?.takeIf { it.isNotBlank() }

        postNotification(
            config = config,
            method = "notifications/initialized",
            params = JSONObject(),
            protocolVersion = selected,
            sessionId = sessionId
        )

        val serverInfo = result
            .optJSONObject("serverInfo")

        return PrimeMcpConnection(
            config = config,
            era = PrimeMcpProtocolEra.LEGACY_2025,
            protocolVersion = selected,
            sessionId = sessionId,
            remoteServerName = serverInfo
                ?.optString("name")
                ?.takeIf { it.isNotBlank() },
            remoteServerVersion = serverInfo
                ?.optString("version")
                ?.takeIf { it.isNotBlank() }
        )
    }

    private suspend fun listTools(
        connection: PrimeMcpConnection
    ): List<PrimeMcpTool> =
        pageList(
            connection = connection,
            method = "tools/list",
            arrayKey = "tools"
        ) { item ->
            val name = item.optString("name")
                .trim()
            if (name.isBlank()) {
                null
            } else {
                PrimeMcpTool(
                    name = name,
                    description = item
                        .optString("description")
                        .takeIf { it.isNotBlank() },
                    inputSchema = item
                        .optJSONObject(
                            "inputSchema"
                        )
                )
            }
        }

    private suspend fun listResources(
        connection: PrimeMcpConnection
    ): List<PrimeMcpResource> =
        pageList(
            connection = connection,
            method = "resources/list",
            arrayKey = "resources"
        ) { item ->
            val uri = item.optString("uri")
                .trim()
            if (uri.isBlank()) {
                null
            } else {
                PrimeMcpResource(
                    uri = uri,
                    name = item
                        .optString("name")
                        .takeIf { it.isNotBlank() },
                    description = item
                        .optString("description")
                        .takeIf { it.isNotBlank() },
                    mimeType = item
                        .optString("mimeType")
                        .takeIf { it.isNotBlank() }
                )
            }
        }

    private suspend fun listPrompts(
        connection: PrimeMcpConnection
    ): List<PrimeMcpPrompt> =
        pageList(
            connection = connection,
            method = "prompts/list",
            arrayKey = "prompts"
        ) { item ->
            val name = item.optString("name")
                .trim()
            if (name.isBlank()) {
                null
            } else {
                PrimeMcpPrompt(
                    name = name,
                    description = item
                        .optString("description")
                        .takeIf { it.isNotBlank() }
                )
            }
        }

    private suspend fun <T> pageList(
        connection: PrimeMcpConnection,
        method: String,
        arrayKey: String,
        parser: (JSONObject) -> T?
    ): List<T> {
        val result = mutableListOf<T>()
        var cursor: String? = null
        var page = 0

        do {
            coroutineContext.ensureActive()
            val params = JSONObject()
            if (!cursor.isNullOrBlank()) {
                params.put("cursor", cursor)
            }

            val pageResult = try {
                requestResult(
                    connection,
                    method,
                    params
                )
            } catch (e: PrimeMcpException) {
                if (
                    e.message.orEmpty()
                        .contains("-32601")
                ) {
                    return result
                }
                throw e
            }

            val array = pageResult
                .optJSONArray(arrayKey)
                ?: JSONArray()

            for (index in 0 until array.length()) {
                val item = array
                    .optJSONObject(index)
                    ?: continue
                parser(item)?.let(result::add)
            }

            cursor = pageResult
                .optString("nextCursor")
                .takeIf { it.isNotBlank() }
            page += 1
        } while (
            cursor != null &&
            page < MAX_PAGES
        )

        return result
    }

    private suspend fun requestResult(
        connection: PrimeMcpConnection,
        method: String,
        params: JSONObject,
        mcpName: String? = null
    ): JSONObject {
        if (
            connection.era ==
            PrimeMcpProtocolEra.MODERN_2026
        ) {
            val existingMeta = params
                .optJSONObject("_meta")
                ?: JSONObject()
            params.put(
                "_meta",
                mergeJson(
                    existingMeta,
                    modernMeta()
                )
            )
        }

        val response = postJsonRpc(
            config = connection.config,
            method = method,
            params = params,
            protocolVersion =
                connection.protocolVersion,
            sessionId =
                connection.sessionId,
            modern =
                connection.era ==
                    PrimeMcpProtocolEra.MODERN_2026,
            mcpName = mcpName
        )

        return resultOrThrow(
            response.json,
            method
        )
    }

    private data class JsonRpcResponse(
        val json: JSONObject,
        val http: PrimeMcpHttpResponse
    )

    private suspend fun postJsonRpc(
        config: PrimeMcpServerConfig,
        method: String,
        params: JSONObject,
        protocolVersion: String?,
        sessionId: String?,
        modern: Boolean,
        mcpName: String? = null
    ): JsonRpcResponse {
        val id = ids.getAndIncrement()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)

        val response = executeHttp(
            config = config,
            body = body,
            protocolVersion =
                protocolVersion,
            sessionId = sessionId,
            method = if (modern) method else null,
            mcpName = if (modern) {
                mcpName
                    ?: params
                        .optString("name")
                        .takeIf {
                            it.isNotBlank()
                        }
            } else {
                null
            }
        )

        val json = parseJsonRpcResponse(
            response.body,
            id
        )

        return JsonRpcResponse(
            json = json,
            http = response
        )
    }

    private suspend fun postNotification(
        config: PrimeMcpServerConfig,
        method: String,
        params: JSONObject,
        protocolVersion: String?,
        sessionId: String?
    ) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)

        executeHttp(
            config = config,
            body = body,
            protocolVersion =
                protocolVersion,
            sessionId = sessionId,
            method = null,
            mcpName = null
        )
    }

    private suspend fun executeHttp(
        config: PrimeMcpServerConfig,
        body: JSONObject,
        protocolVersion: String?,
        sessionId: String?,
        method: String?,
        mcpName: String?
    ): PrimeMcpHttpResponse {
        coroutineContext.ensureActive()

        val connection = URL(config.url)
            .openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout =
            CONNECT_TIMEOUT_MS
        connection.readTimeout =
            READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty(
            "Content-Type",
            "application/json"
        )
        connection.setRequestProperty(
            "Accept",
            "application/json, text/event-stream"
        )
        connection.setRequestProperty(
            "User-Agent",
            "PRIME-P6/$clientVersion"
        )

        config.bearerToken?.let {
            connection.setRequestProperty(
                "Authorization",
                "Bearer $it"
            )
        }

        if (!protocolVersion.isNullOrBlank()) {
            connection.setRequestProperty(
                "MCP-Protocol-Version",
                protocolVersion
            )
        }
        if (!sessionId.isNullOrBlank()) {
            connection.setRequestProperty(
                "Mcp-Session-Id",
                sessionId
            )
        }
        if (!method.isNullOrBlank()) {
            connection.setRequestProperty(
                "Mcp-Method",
                method
            )
        }
        if (!mcpName.isNullOrBlank()) {
            connection.setRequestProperty(
                "Mcp-Name",
                mcpName.take(512)
            )
        }

        val bytes = body.toString()
            .toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(
            bytes.size
        )

        return try {
            connection.outputStream.use {
                it.write(bytes)
            }

            coroutineContext.ensureActive()
            val status = connection.responseCode
            val input = if (
                status in 200..299
            ) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = input
                ?.bufferedReader(Charsets.UTF_8)
                ?.use(::readBounded)
                .orEmpty()

            if (status !in 200..299) {
                throw PrimeMcpException(
                    "MCP HTTP $status: " +
                        responseBody.take(500)
                )
            }

            val responseHeaders =
                linkedMapOf<String, List<String>>()
            connection.headerFields
                .forEach { (key, values) ->
                    if (key != null && values != null) {
                        responseHeaders[key] = values
                    }
                }

            PrimeMcpHttpResponse(
                status = status,
                contentType = connection
                    .contentType,
                body = responseBody,
                headers = responseHeaders
            )
        } catch (e: CancellationException) {
            connection.disconnect()
            throw e
        } catch (e: IOException) {
            throw PrimeMcpException(
                "MCP network error: " +
                    safeMessage(e),
                e
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(
        reader: BufferedReader
    ): String {
        val output = StringBuilder()
        val buffer = CharArray(8 * 1024)

        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            output.append(
                buffer,
                0,
                count
            )
            if (
                output.length >
                MAX_RESPONSE_CHARS
            ) {
                throw PrimeMcpException(
                    "MCP response exceeded size limit"
                )
            }
        }

        return output.toString()
    }

    private fun parseJsonRpcResponse(
        raw: String,
        wantedId: Long
    ): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            throw PrimeMcpException(
                "MCP returned an empty response"
            )
        }

        if (
            trimmed.startsWith("{")
        ) {
            return JSONObject(trimmed)
        }

        val candidates = mutableListOf<JSONObject>()
        var eventData = StringBuilder()

        fun flush() {
            val text = eventData
                .toString()
                .trim()
            eventData = StringBuilder()
            if (text.isBlank()) return
            runCatching {
                JSONObject(text)
            }.getOrNull()?.let {
                candidates += it
            }
        }

        trimmed.lineSequence().forEach { line ->
            when {
                line.startsWith("data:") -> {
                    if (eventData.isNotEmpty()) {
                        eventData.append('\n')
                    }
                    eventData.append(
                        line.removePrefix("data:")
                            .trimStart()
                    )
                }
                line.isBlank() -> flush()
            }
        }
        flush()

        return candidates.firstOrNull {
            it.optLong("id", Long.MIN_VALUE) ==
                wantedId
        } ?: candidates.firstOrNull()
        ?: throw PrimeMcpException(
            "MCP returned no JSON-RPC response event"
        )
    }

    private fun resultOrThrow(
        response: JSONObject,
        method: String
    ): JSONObject {
        response.optJSONObject("error")
            ?.let { error ->
                val code = error.optInt(
                    "code",
                    Int.MIN_VALUE
                )
                val message = error
                    .optString("message")
                    .ifBlank {
                        "Unknown MCP error"
                    }
                throw PrimeMcpException(
                    "$method failed ($code): $message"
                )
            }

        return response.optJSONObject("result")
            ?: throw PrimeMcpException(
                "$method returned no result"
            )
    }

    private fun modernMeta(): JSONObject =
        JSONObject()
            .put(
                "io.modelcontextprotocol/protocolVersion",
                MODERN_VERSION
            )
            .put(
                "io.modelcontextprotocol/clientCapabilities",
                JSONObject()
            )
            .put(
                "io.modelcontextprotocol/clientInfo",
                JSONObject()
                    .put("name", clientName)
                    .put(
                        "version",
                        clientVersion
                    )
            )

    private fun contentToText(
        content: JSONArray?
    ): String {
        if (content == null) return ""
        return buildString {
            for (index in 0 until content.length()) {
                val item = content
                    .optJSONObject(index)
                    ?: continue
                val type = item.optString("type")
                val text = when (type) {
                    "text" -> item
                        .optString("text")
                    "resource" -> item
                        .optJSONObject("resource")
                        ?.optString("text")
                        .orEmpty()
                    else -> ""
                }

                if (text.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    append(text)
                }
            }
        }
    }

    private fun containsString(
        array: JSONArray,
        wanted: String
    ): Boolean {
        for (index in 0 until array.length()) {
            if (
                array.optString(index) ==
                wanted
            ) {
                return true
            }
        }
        return false
    }

    private fun mergeJson(
        first: JSONObject,
        second: JSONObject
    ): JSONObject {
        val result = JSONObject(
            first.toString()
        )
        second.keys().forEach { key ->
            result.put(
                key,
                second.get(key)
            )
        }
        return result
    }

    private fun safeMessage(
        error: Throwable?
    ): String =
        error?.message
            ?.take(300)
            ?.ifBlank { null }
            ?: error
                ?.javaClass
                ?.simpleName
            ?: "unknown error"
}
