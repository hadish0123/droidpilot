package com.mobilemcp.pro

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class PrimeMcpClientTest {

    @Test
    fun endpointValidatorRequiresHttpsExceptLoopback() {
        assertEquals(
            "https://example.com/mcp",
            PrimeMcpEndpointValidator.normalize(
                "https://example.com/mcp"
            )
        )
        assertTrue(
            PrimeMcpEndpointValidator.normalize(
                "http://127.0.0.1:8080/mcp"
            ).startsWith(
                "http://127.0.0.1:8080"
            )
        )

        try {
            PrimeMcpEndpointValidator.normalize(
                "http://example.com/mcp"
            )
            throw AssertionError(
                "Expected remote cleartext rejection"
            )
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun serverCodecRoundTripsBearerToken() {
        val original = listOf(
            PrimeMcpServerConfig(
                id = "one",
                name = "Example",
                url = "https://example.com/mcp",
                bearerToken = "secret",
                enabled = true,
                createdAt = 1,
                updatedAt = 2
            )
        )

        assertEquals(
            original,
            PrimeMcpServerCodec.decode(
                PrimeMcpServerCodec.encode(
                    original
                )
            )
        )
    }

    @Test
    fun modernDiscoveryUsesStatelessEnvelopeAndHeaders() =
        runBlocking {
            MockWebServer().use { server ->
                val requests =
                    CopyOnWriteArrayList<RecordedRequest>()

                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(
                            request: RecordedRequest
                        ): MockResponse {
                            requests += request
                            val json = JSONObject(
                                request.body.clone().readUtf8()
                            )
                            val id = json.optLong("id")
                            val method = json
                                .optString("method")

                            val result = when (method) {
                                "server/discover" ->
                                    JSONObject()
                                        .put(
                                            "supportedVersions",
                                            org.json.JSONArray()
                                                .put(
                                                    PrimeMcpClient.MODERN_VERSION
                                                )
                                        )
                                        .put(
                                            "capabilities",
                                            JSONObject()
                                        )
                                        .put(
                                            "_meta",
                                            JSONObject().put(
                                                "io.modelcontextprotocol/serverInfo",
                                                JSONObject()
                                                    .put(
                                                        "name",
                                                        "modern-test"
                                                    )
                                                    .put(
                                                        "version",
                                                        "1.2.3"
                                                    )
                                            )
                                        )

                                "tools/list" ->
                                    JSONObject().put(
                                        "tools",
                                        org.json.JSONArray()
                                            .put(
                                                JSONObject()
                                                    .put(
                                                        "name",
                                                        "calculator"
                                                    )
                                                    .put(
                                                        "description",
                                                        "Calculate"
                                                    )
                                            )
                                    )

                                "resources/list" ->
                                    JSONObject().put(
                                        "resources",
                                        org.json.JSONArray()
                                            .put(
                                                JSONObject()
                                                    .put(
                                                        "uri",
                                                        "demo://resource"
                                                    )
                                                    .put(
                                                        "name",
                                                        "Demo"
                                                    )
                                            )
                                    )

                                "prompts/list" ->
                                    JSONObject().put(
                                        "prompts",
                                        org.json.JSONArray()
                                            .put(
                                                JSONObject()
                                                    .put(
                                                        "name",
                                                        "draft"
                                                    )
                                            )
                                    )

                                else -> JSONObject()
                            }

                            return MockResponse()
                                .setResponseCode(200)
                                .addHeader(
                                    "Content-Type",
                                    "application/json"
                                )
                                .setBody(
                                    JSONObject()
                                        .put(
                                            "jsonrpc",
                                            "2.0"
                                        )
                                        .put("id", id)
                                        .put(
                                            "result",
                                            result
                                        )
                                        .toString()
                                )
                        }
                    }

                val client = PrimeMcpClient()
                val discovery = client.discover(
                    configFor(server)
                )

                assertEquals(
                    PrimeMcpProtocolEra.MODERN_2026,
                    discovery.era
                )
                assertEquals(
                    PrimeMcpClient.MODERN_VERSION,
                    discovery.protocolVersion
                )
                assertEquals(
                    "modern-test",
                    discovery.remoteServerName
                )
                assertEquals(
                    listOf("calculator"),
                    discovery.tools.map {
                        it.name
                    }
                )
                assertEquals(1, discovery.resources.size)
                assertEquals(1, discovery.prompts.size)

                assertTrue(requests.size >= 4)
                requests.forEach { request ->
                    assertEquals(
                        PrimeMcpClient.MODERN_VERSION,
                        request.getHeader(
                            "MCP-Protocol-Version"
                        )
                    )
                    val body = JSONObject(
                        request.body.clone()
                            .readUtf8()
                    )
                    val meta = body
                        .getJSONObject("params")
                        .getJSONObject("_meta")
                    assertEquals(
                        PrimeMcpClient.MODERN_VERSION,
                        meta.getString(
                            "io.modelcontextprotocol/protocolVersion"
                        )
                    )
                }
            }
        }

    @Test
    fun fallsBackToLegacyInitializeAndUsesSession() =
        runBlocking {
            MockWebServer().use { server ->
                val requests =
                    CopyOnWriteArrayList<RecordedRequest>()

                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(
                            request: RecordedRequest
                        ): MockResponse {
                            requests += request
                            val raw = request.body.clone().readUtf8()
                            val json = JSONObject(raw)
                            val method = json
                                .optString("method")

                            if (
                                method ==
                                "notifications/initialized"
                            ) {
                                return MockResponse()
                                    .setResponseCode(202)
                            }

                            val id = json.optLong("id")
                            val response = when (method) {
                                "server/discover" ->
                                    JSONObject()
                                        .put(
                                            "jsonrpc",
                                            "2.0"
                                        )
                                        .put("id", id)
                                        .put(
                                            "error",
                                            JSONObject()
                                                .put(
                                                    "code",
                                                    -32601
                                                )
                                                .put(
                                                    "message",
                                                    "Method not found"
                                                )
                                        )

                                "initialize" ->
                                    JSONObject()
                                        .put(
                                            "jsonrpc",
                                            "2.0"
                                        )
                                        .put("id", id)
                                        .put(
                                            "result",
                                            JSONObject()
                                                .put(
                                                    "protocolVersion",
                                                    PrimeMcpClient.LEGACY_VERSION
                                                )
                                                .put(
                                                    "capabilities",
                                                    JSONObject()
                                                )
                                                .put(
                                                    "serverInfo",
                                                    JSONObject()
                                                        .put(
                                                            "name",
                                                            "legacy-test"
                                                        )
                                                        .put(
                                                            "version",
                                                            "0.9"
                                                        )
                                                )
                                        )

                                "tools/list" ->
                                    rpcResult(
                                        id,
                                        JSONObject().put(
                                            "tools",
                                            org.json.JSONArray()
                                        )
                                    )

                                "resources/list" ->
                                    rpcResult(
                                        id,
                                        JSONObject().put(
                                            "resources",
                                            org.json.JSONArray()
                                        )
                                    )

                                "prompts/list" ->
                                    rpcResult(
                                        id,
                                        JSONObject().put(
                                            "prompts",
                                            org.json.JSONArray()
                                        )
                                    )

                                else ->
                                    rpcResult(
                                        id,
                                        JSONObject()
                                    )
                            }

                            val mock = MockResponse()
                                .setResponseCode(200)
                                .addHeader(
                                    "Content-Type",
                                    "application/json"
                                )
                                .setBody(
                                    response.toString()
                                )

                            if (method == "initialize") {
                                mock.addHeader(
                                    "Mcp-Session-Id",
                                    "legacy-session"
                                )
                            }
                            return mock
                        }
                    }

                val discovery = PrimeMcpClient()
                    .discover(
                        configFor(server)
                    )

                assertEquals(
                    PrimeMcpProtocolEra.LEGACY_2025,
                    discovery.era
                )
                assertEquals(
                    "legacy-test",
                    discovery.remoteServerName
                )

                val initialized = requests
                    .first {
                        JSONObject(
                            it.body.clone()
                                .readUtf8()
                        ).optString("method") ==
                            "notifications/initialized"
                    }
                assertEquals(
                    "legacy-session",
                    initialized.getHeader(
                        "Mcp-Session-Id"
                    )
                )

                val toolList = requests
                    .first {
                        JSONObject(
                            it.body.clone()
                                .readUtf8()
                        ).optString("method") ==
                            "tools/list"
                    }
                assertEquals(
                    "legacy-session",
                    toolList.getHeader(
                        "Mcp-Session-Id"
                    )
                )
                assertEquals(
                    PrimeMcpClient.LEGACY_VERSION,
                    toolList.getHeader(
                        "MCP-Protocol-Version"
                    )
                )
            }
        }

    @Test
    fun modernToolCallSendsStandardMethodAndNameHeaders() =
        runBlocking {
            MockWebServer().use { server ->
                val requests =
                    CopyOnWriteArrayList<RecordedRequest>()

                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(
                            request: RecordedRequest
                        ): MockResponse {
                            requests += request
                            val json = JSONObject(
                                request.body.clone().readUtf8()
                            )
                            val method = json
                                .optString("method")
                            val id = json.optLong("id")

                            val result = when (method) {
                                "server/discover" ->
                                    JSONObject()
                                        .put(
                                            "supportedVersions",
                                            org.json.JSONArray()
                                                .put(
                                                    PrimeMcpClient.MODERN_VERSION
                                                )
                                        )
                                        .put(
                                            "capabilities",
                                            JSONObject()
                                        )

                                "tools/call" ->
                                    JSONObject()
                                        .put(
                                            "isError",
                                            false
                                        )
                                        .put(
                                            "content",
                                            org.json.JSONArray()
                                                .put(
                                                    JSONObject()
                                                        .put(
                                                            "type",
                                                            "text"
                                                        )
                                                        .put(
                                                            "text",
                                                            "42"
                                                        )
                                                )
                                        )

                                else -> JSONObject()
                            }

                            return MockResponse()
                                .setResponseCode(200)
                                .addHeader(
                                    "Content-Type",
                                    "application/json"
                                )
                                .setBody(
                                    rpcResult(
                                        id,
                                        result
                                    ).toString()
                                )
                        }
                    }

                val result = PrimeMcpClient()
                    .callTool(
                        configFor(server),
                        "calculator",
                        JSONObject().put(
                            "expression",
                            "6*7"
                        )
                    )

                assertFalse(result.isError)
                assertEquals("42", result.text)

                val call = requests.first {
                    JSONObject(
                        it.body.clone().readUtf8()
                    ).optString("method") ==
                        "tools/call"
                }
                assertEquals(
                    "tools/call",
                    call.getHeader("Mcp-Method")
                )
                assertEquals(
                    "calculator",
                    call.getHeader("Mcp-Name")
                )
            }
        }

    private fun configFor(
        server: MockWebServer
    ): PrimeMcpServerConfig =
        PrimeMcpServerConfig(
            id = "test",
            name = "Test MCP",
            url = server.url("/mcp").toString(),
            bearerToken = "test-token",
            enabled = true,
            createdAt = 1,
            updatedAt = 1
        )

    private fun rpcResult(
        id: Long,
        result: JSONObject
    ): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
}
