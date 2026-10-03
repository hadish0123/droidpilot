package com.mobilemcp.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimePluginRuntimeTest {

    @Test
    fun manifestParsesMcpRuntimeAndCapabilities() {
        val manifest =
            PrimePluginManifestParser
                .parse(
                    """
                    {
                      "schema":"prime.plugin.v1",
                      "id":"com.example.docs",
                      "name":"Docs Plugin",
                      "version":"1.2.0",
                      "description":"Search docs",
                      "runtime":{
                        "type":"mcp",
                        "url":"https://example.com/mcp"
                      },
                      "capabilities":[
                        "tools",
                        "resources"
                      ]
                    }
                    """.trimIndent()
                )

        assertEquals(
            "com.example.docs",
            manifest.id
        )
        assertEquals(
            "https://example.com/mcp",
            manifest.mcpUrl
        )
        assertTrue(
            PrimePluginCapability
                .TOOLS in
                manifest.capabilities
        )
        assertTrue(
            PrimePluginCapability
                .RESOURCES in
                manifest.capabilities
        )
    }

    @Test
    fun remoteCleartextPluginIsRejected() {
        try {
            PrimePluginManifestParser
                .parse(
                    """
                    {
                      "schema":"prime.plugin.v1",
                      "id":"com.example.bad",
                      "name":"Bad",
                      "version":"1",
                      "runtime":{
                        "type":"mcp",
                        "url":"http://example.com/mcp"
                      },
                      "capabilities":["tools"]
                    }
                    """.trimIndent()
                )
            throw AssertionError(
                "Expected cleartext rejection"
            )
        } catch (
            _: IllegalArgumentException
        ) {
        }
    }

    @Test
    fun codecRoundTripsPluginMetadata() {
        val original =
            listOf(
                PrimePluginRecord(
                    manifest =
                        PrimePluginManifest(
                            schema =
                                "prime.plugin.v1",
                            id =
                                "com.example.docs",
                            name =
                                "Docs",
                            version =
                                "1.0",
                            description =
                                null,
                            mcpUrl =
                                "https://example.com/mcp",
                            capabilities =
                                setOf(
                                    PrimePluginCapability
                                        .TOOLS,
                                    PrimePluginCapability
                                        .PROMPTS
                                )
                        ),
                    mcpServerId =
                        "server-1",
                    enabled = false,
                    installedAt = 10,
                    updatedAt = 20
                )
            )

        val decoded =
            PrimePluginCodec.decode(
                PrimePluginCodec
                    .encode(original)
            )

        assertEquals(
            original,
            decoded
        )
        assertFalse(
            decoded.single()
                .enabled
        )
    }
}
