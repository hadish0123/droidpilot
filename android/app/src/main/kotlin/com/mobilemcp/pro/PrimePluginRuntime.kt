package com.mobilemcp.pro

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal enum class PrimePluginCapability {
    TOOLS,
    RESOURCES,
    PROMPTS
}

internal data class PrimePluginManifest(
    val schema: String,
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val mcpUrl: String,
    val capabilities:
        Set<PrimePluginCapability>
)

internal data class PrimePluginRecord(
    val manifest: PrimePluginManifest,
    val mcpServerId: String,
    val enabled: Boolean,
    val installedAt: Long,
    val updatedAt: Long
)

internal object PrimePluginManifestParser {
    private const val SCHEMA =
        "prime.plugin.v1"
    private val ID_PATTERN =
        Regex(
            "[A-Za-z0-9][A-Za-z0-9._-]{2,79}"
        )

    fun parse(raw: String):
        PrimePluginManifest {
        require(
            raw.length <= 256_000
        ) {
            "Plugin manifest is too large"
        }

        val json = JSONObject(raw)
        val schema = json
            .optString("schema")
            .trim()
        require(schema == SCHEMA) {
            "Unsupported plugin manifest schema"
        }

        val id = json
            .optString("id")
            .trim()
        require(
            ID_PATTERN.matches(id)
        ) {
            "Plugin id is invalid"
        }

        val name = json
            .optString("name")
            .trim()
            .take(80)
        require(name.isNotBlank()) {
            "Plugin name is required"
        }

        val version = json
            .optString("version")
            .trim()
            .take(40)
        require(version.isNotBlank()) {
            "Plugin version is required"
        }

        val description = json
            .optString(
                "description"
            )
            .trim()
            .take(500)
            .takeIf {
                it.isNotBlank()
            }

        val runtime = json
            .optJSONObject(
                "runtime"
            )
            ?: throw IllegalArgumentException(
                "Plugin runtime is required"
            )
        require(
            runtime
                .optString("type")
                .trim()
                .lowercase(
                    Locale.ROOT
                ) == "mcp"
        ) {
            "Only MCP plugin runtime is supported"
        }

        val mcpUrl =
            PrimeMcpEndpointValidator
                .normalize(
                    runtime
                        .optString("url")
                )

        val capabilities =
            parseCapabilities(
                json.optJSONArray(
                    "capabilities"
                )
            )

        require(
            capabilities.isNotEmpty()
        ) {
            "Plugin must declare at least one capability"
        }

        return PrimePluginManifest(
            schema = schema,
            id = id,
            name = name,
            version = version,
            description =
                description,
            mcpUrl = mcpUrl,
            capabilities =
                capabilities
        )
    }

    private fun parseCapabilities(
        array: JSONArray?
    ): Set<PrimePluginCapability> {
        if (array == null) {
            return setOf(
                PrimePluginCapability
                    .TOOLS
            )
        }

        val result =
            linkedSetOf<
                PrimePluginCapability
            >()

        for (
            index in 0
                until array.length()
        ) {
            val value = array
                .optString(index)
                .trim()
                .uppercase(
                    Locale.ROOT
                )
            if (value.isBlank()) {
                continue
            }

            val capability =
                runCatching {
                    PrimePluginCapability
                        .valueOf(value)
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Unsupported plugin capability: " +
                            value
                    )
                }

            result += capability
        }

        return result
    }
}

internal object PrimePluginCodec {
    fun encode(
        records:
            List<PrimePluginRecord>
    ): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put(
                        "manifest",
                        encodeManifest(
                            record.manifest
                        )
                    )
                    .put(
                        "mcpServerId",
                        record.mcpServerId
                    )
                    .put(
                        "enabled",
                        record.enabled
                    )
                    .put(
                        "installedAt",
                        record.installedAt
                    )
                    .put(
                        "updatedAt",
                        record.updatedAt
                    )
            )
        }
        return array.toString()
    }

    fun decode(
        raw: String?
    ): List<PrimePluginRecord> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        return runCatching {
            val array =
                JSONArray(raw)
            buildList {
                for (
                    index in 0
                        until array.length()
                ) {
                    val item =
                        array.optJSONObject(
                            index
                        ) ?: continue
                    val manifestJson =
                        item.optJSONObject(
                            "manifest"
                        ) ?: continue

                    val manifest =
                        decodeManifest(
                            manifestJson
                        )
                    val serverId =
                        item.optString(
                            "mcpServerId"
                        ).trim()
                    if (
                        serverId.isBlank()
                    ) {
                        continue
                    }

                    add(
                        PrimePluginRecord(
                            manifest =
                                manifest,
                            mcpServerId =
                                serverId,
                            enabled =
                                item.optBoolean(
                                    "enabled",
                                    true
                                ),
                            installedAt =
                                item.optLong(
                                    "installedAt"
                                ),
                            updatedAt =
                                item.optLong(
                                    "updatedAt"
                                )
                        )
                    )
                }
            }
        }.getOrDefault(
            emptyList()
        )
    }

    private fun encodeManifest(
        manifest:
            PrimePluginManifest
    ): JSONObject =
        JSONObject()
            .put(
                "schema",
                manifest.schema
            )
            .put(
                "id",
                manifest.id
            )
            .put(
                "name",
                manifest.name
            )
            .put(
                "version",
                manifest.version
            )
            .put(
                "description",
                manifest.description
                    ?: JSONObject.NULL
            )
            .put(
                "mcpUrl",
                manifest.mcpUrl
            )
            .put(
                "capabilities",
                JSONArray().apply {
                    manifest
                        .capabilities
                        .forEach {
                            put(it.name)
                        }
                }
            )

    private fun decodeManifest(
        json: JSONObject
    ): PrimePluginManifest {
        val capabilities =
            linkedSetOf<
                PrimePluginCapability
            >()
        val array = json
            .optJSONArray(
                "capabilities"
            ) ?: JSONArray()

        for (
            index in 0
                until array.length()
        ) {
            runCatching {
                PrimePluginCapability
                    .valueOf(
                        array.getString(
                            index
                        )
                    )
            }.getOrNull()?.let {
                capabilities += it
            }
        }

        return PrimePluginManifest(
            schema = json
                .optString(
                    "schema",
                    "prime.plugin.v1"
                ),
            id = json
                .getString("id"),
            name = json
                .getString("name"),
            version = json
                .getString(
                    "version"
                ),
            description =
                if (
                    json.isNull(
                        "description"
                    )
                ) {
                    null
                } else {
                    json.optString(
                        "description"
                    )
                        .takeIf {
                            it.isNotBlank()
                        }
                },
            mcpUrl =
                PrimeMcpEndpointValidator
                    .normalize(
                        json.getString(
                            "mcpUrl"
                        )
                    ),
            capabilities =
                capabilities
        )
    }
}

internal class PrimePluginStore(
    context: Context,
    private val secureStore:
        SecureStore =
        SecureStore(
            context
                .applicationContext
        )
) {
    companion object {
        private const val STORE_KEY =
            "prime_plugins_v1"
        private const val MAX_PLUGINS =
            30
    }

    @Synchronized
    fun list():
        List<PrimePluginRecord> =
        readAll()
            .sortedByDescending {
                it.updatedAt
            }

    @Synchronized
    fun get(
        pluginId: String
    ): PrimePluginRecord? =
        readAll().firstOrNull {
            it.manifest.id ==
                pluginId
        }

    @Synchronized
    fun save(
        record:
            PrimePluginRecord
    ) {
        val records =
            readAll()
                .toMutableList()
        val index =
            records.indexOfFirst {
                it.manifest.id ==
                    record.manifest.id
            }

        if (index >= 0) {
            records[index] =
                record
        } else {
            require(
                records.size <
                    MAX_PLUGINS
            ) {
                "Maximum plugin count reached"
            }
            records += record
        }

        writeAll(records)
    }

    @Synchronized
    fun remove(
        pluginId: String
    ): PrimePluginRecord? {
        val records =
            readAll()
                .toMutableList()
        val index =
            records.indexOfFirst {
                it.manifest.id ==
                    pluginId
            }
        if (index < 0) {
            return null
        }

        val removed =
            records.removeAt(
                index
            )
        writeAll(records)
        return removed
    }

    private fun readAll():
        List<PrimePluginRecord> =
        PrimePluginCodec.decode(
            secureStore.getString(
                STORE_KEY
            )
        )

    private fun writeAll(
        records:
            List<PrimePluginRecord>
    ) {
        secureStore.putString(
            STORE_KEY,
            PrimePluginCodec
                .encode(records)
        )
    }
}

internal class PrimePluginManifestLoader(
    context: Context
) {
    companion object {
        private const val MAX_BYTES =
            256 * 1024
    }

    private val appContext =
        context.applicationContext

    fun load(uri: Uri): String {
        val input =
            appContext
                .contentResolver
                .openInputStream(uri)
                ?: throw IllegalStateException(
                    "Plugin manifest is not readable"
                )

        return input.use { stream ->
            val output =
                ByteArrayOutputStream(
                    32 * 1024
                )
            val buffer =
                ByteArray(
                    8 * 1024
                )
            var total = 0

            while (true) {
                val count =
                    stream.read(buffer)
                if (count < 0) break
                total += count
                if (
                    total > MAX_BYTES
                ) {
                    throw IllegalArgumentException(
                        "Plugin manifest exceeds 256 KiB"
                    )
                }
                output.write(
                    buffer,
                    0,
                    count
                )
            }

            output.toString(
                Charsets.UTF_8.name()
            )
        }
    }
}

internal class PrimePluginManager(
    context: Context,
    private val pluginStore:
        PrimePluginStore =
        PrimePluginStore(context),
    private val mcpStore:
        PrimeMcpServerStore =
        PrimeMcpServerStore(
            context
        )
) {
    fun list():
        List<PrimePluginRecord> =
        pluginStore.list()

    fun install(
        manifest:
            PrimePluginManifest,
        bearerToken:
            String? = null
    ): PrimePluginRecord {
        val existing =
            pluginStore.get(
                manifest.id
            )
        val now =
            System.currentTimeMillis()

        val existingServer =
            existing?.let {
                mcpStore.get(
                    it.mcpServerId
                )
            }
        val effectiveToken =
            bearerToken
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: existingServer
                    ?.bearerToken

        val server =
            mcpStore.save(
                name =
                    "Plugin · " +
                        manifest.name,
                url =
                    manifest.mcpUrl,
                bearerToken =
                    effectiveToken,
                enabled =
                    existing
                        ?.enabled
                        ?: true,
                id =
                    existing
                        ?.mcpServerId
            )

        val record =
            PrimePluginRecord(
                manifest =
                    manifest,
                mcpServerId =
                    server.id,
                enabled =
                    existing
                        ?.enabled
                        ?: true,
                installedAt =
                    existing
                        ?.installedAt
                        ?: now,
                updatedAt =
                    now
            )

        pluginStore.save(
            record
        )
        return record
    }

    fun setEnabled(
        pluginId: String,
        enabled: Boolean
    ): PrimePluginRecord? {
        val current =
            pluginStore.get(
                pluginId
            ) ?: return null

        mcpStore.setEnabled(
            current.mcpServerId,
            enabled
        )

        val updated =
            current.copy(
                enabled = enabled,
                updatedAt =
                    System
                        .currentTimeMillis()
            )
        pluginStore.save(
            updated
        )
        return updated
    }

    fun uninstall(
        pluginId: String
    ): PrimePluginRecord? {
        val removed =
            pluginStore.remove(
                pluginId
            ) ?: return null

        mcpStore.remove(
            removed.mcpServerId
        )
        return removed
    }

    fun serverFor(
        record:
            PrimePluginRecord
    ): PrimeMcpServerConfig? =
        mcpStore.get(
            record.mcpServerId
        )
}
