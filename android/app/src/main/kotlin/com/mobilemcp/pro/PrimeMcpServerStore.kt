package com.mobilemcp.pro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.util.UUID

internal data class PrimeMcpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val bearerToken: String?,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

internal object PrimeMcpEndpointValidator {
    fun normalize(raw: String): String {
        val value = raw.trim()
        require(value.isNotBlank()) {
            "MCP URL cannot be empty"
        }

        val uri = runCatching { URI(value) }
            .getOrElse {
                throw IllegalArgumentException(
                    "MCP URL is invalid"
                )
            }

        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") {
            "MCP server must use HTTPS, or HTTP only on localhost"
        }
        require(uri.userInfo == null) {
            "Credentials must not be embedded in the MCP URL"
        }
        require(uri.fragment == null) {
            "MCP URL must not contain a fragment"
        }

        val host = uri.host?.trim().orEmpty()
        require(host.isNotBlank()) {
            "MCP URL must include a host"
        }

        if (scheme == "http") {
            require(isLoopback(host)) {
                "Cleartext MCP is allowed only on localhost; use HTTPS for remote servers"
            }
        }

        val path = uri.rawPath
            ?.takeIf { it.isNotBlank() }
            ?: "/mcp"

        return URI(
            scheme,
            null,
            host,
            uri.port,
            path,
            uri.rawQuery,
            null
        ).toASCIIString()
    }

    fun isLoopback(host: String): Boolean {
        val clean = host
            .removePrefix("[")
            .removeSuffix("]")

        if (
            clean.equals("localhost", ignoreCase = true) ||
            clean.equals("ip6-localhost", ignoreCase = true)
        ) {
            return true
        }

        return runCatching {
            InetAddress.getByName(clean).isLoopbackAddress
        }.getOrDefault(false)
    }
}

internal object PrimeMcpServerCodec {
    fun encode(
        servers: List<PrimeMcpServerConfig>
    ): String {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("url", server.url)
                    .put(
                        "bearerToken",
                        server.bearerToken ?: JSONObject.NULL
                    )
                    .put("enabled", server.enabled)
                    .put("createdAt", server.createdAt)
                    .put("updatedAt", server.updatedAt)
            )
        }
        return array.toString()
    }

    fun decode(
        raw: String?
    ): List<PrimeMcpServerConfig> {
        if (raw.isNullOrBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index)
                        ?: continue

                    val id = json.optString("id").trim()
                    val name = json.optString("name").trim()
                    val rawUrl = json.optString("url").trim()
                    if (
                        id.isBlank() ||
                        name.isBlank() ||
                        rawUrl.isBlank()
                    ) {
                        continue
                    }

                    val url = runCatching {
                        PrimeMcpEndpointValidator.normalize(rawUrl)
                    }.getOrNull() ?: continue

                    add(
                        PrimeMcpServerConfig(
                            id = id,
                            name = name.take(80),
                            url = url,
                            bearerToken = if (
                                json.isNull("bearerToken")
                            ) {
                                null
                            } else {
                                json.optString("bearerToken")
                                    .trim()
                                    .takeIf { it.isNotBlank() }
                            },
                            enabled = json.optBoolean(
                                "enabled",
                                true
                            ),
                            createdAt = json.optLong(
                                "createdAt"
                            ),
                            updatedAt = json.optLong(
                                "updatedAt"
                            )
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

internal class PrimeMcpServerStore(
    context: Context,
    private val secureStore: SecureStore = SecureStore(
        context.applicationContext
    )
) {
    companion object {
        private const val STORE_KEY =
            "prime_mcp_servers_v1"
        private const val MAX_SERVERS = 20
    }

    @Synchronized
    fun list(): List<PrimeMcpServerConfig> =
        readAll()
            .sortedByDescending {
                it.updatedAt
            }

    @Synchronized
    fun get(id: String): PrimeMcpServerConfig? =
        readAll().firstOrNull { it.id == id }

    @Synchronized
    fun save(
        name: String,
        url: String,
        bearerToken: String? = null,
        enabled: Boolean = true,
        id: String? = null
    ): PrimeMcpServerConfig {
        val cleanName = name.trim().take(80)
        require(cleanName.isNotBlank()) {
            "MCP server name cannot be empty"
        }

        val normalizedUrl =
            PrimeMcpEndpointValidator.normalize(url)
        val token = bearerToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val now = System.currentTimeMillis()
        val servers = readAll().toMutableList()
        val existingIndex = id?.let { wanted ->
            servers.indexOfFirst {
                it.id == wanted
            }.takeIf { it >= 0 }
        }

        val item = if (existingIndex != null) {
            val current = servers[existingIndex]
            current.copy(
                name = cleanName,
                url = normalizedUrl,
                bearerToken = token,
                enabled = enabled,
                updatedAt = now
            ).also {
                servers[existingIndex] = it
            }
        } else {
            require(servers.size < MAX_SERVERS) {
                "Maximum MCP server count reached"
            }
            PrimeMcpServerConfig(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                url = normalizedUrl,
                bearerToken = token,
                enabled = enabled,
                createdAt = now,
                updatedAt = now
            ).also {
                servers += it
            }
        }

        writeAll(servers)
        return item
    }

    @Synchronized
    fun setEnabled(
        id: String,
        enabled: Boolean
    ): PrimeMcpServerConfig? {
        val servers = readAll().toMutableList()
        val index = servers.indexOfFirst {
            it.id == id
        }
        if (index < 0) return null

        val updated = servers[index].copy(
            enabled = enabled,
            updatedAt = System.currentTimeMillis()
        )
        servers[index] = updated
        writeAll(servers)
        return updated
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val servers = readAll()
        val retained = servers.filterNot {
            it.id == id
        }
        if (retained.size == servers.size) {
            return false
        }
        writeAll(retained)
        return true
    }

    @Synchronized
    fun clear() {
        secureStore.remove(STORE_KEY)
    }

    private fun readAll(): List<PrimeMcpServerConfig> =
        PrimeMcpServerCodec.decode(
            secureStore.getString(STORE_KEY)
        )

    private fun writeAll(
        servers: List<PrimeMcpServerConfig>
    ) {
        secureStore.putString(
            STORE_KEY,
            PrimeMcpServerCodec.encode(servers)
        )
    }
}
