package com.mobilemcp.pro

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal enum class PrimeProviderKind {
    OPENROUTER,
    GEMINI,
    ANTHROPIC,
    OPENAI_COMPATIBLE
}

internal data class PrimeProviderProfile(
    val id: String,
    val name: String,
    val kind: PrimeProviderKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val createdAt: Long,
    val updatedAt: Long
)

internal object PrimeProviderEndpointValidator {
    fun normalize(raw: String): String {
        val value = raw.trim()
        require(value.isNotBlank()) {
            "Provider base URL cannot be empty"
        }

        val uri = runCatching {
            URI(value)
        }.getOrElse {
            throw IllegalArgumentException(
                "Provider URL is invalid"
            )
        }

        val scheme = uri.scheme
            ?.lowercase(Locale.ROOT)
        require(
            scheme == "https" ||
                scheme == "http"
        ) {
            "Provider endpoint must use HTTPS, or HTTP only on localhost"
        }
        require(uri.userInfo == null) {
            "Credentials must not be embedded in provider URL"
        }
        require(uri.fragment == null) {
            "Provider URL must not contain a fragment"
        }

        val host = uri.host
            ?.trim()
            .orEmpty()
        require(host.isNotBlank()) {
            "Provider URL must include a host"
        }

        if (scheme == "http") {
            require(
                PrimeMcpEndpointValidator
                    .isLoopback(host)
            ) {
                "Remote custom providers must use HTTPS"
            }
        }

        val path = uri.rawPath
            ?.takeIf {
                it.isNotBlank()
            }
            ?: ""

        return URI(
            scheme,
            null,
            host,
            uri.port,
            path.trimEnd('/'),
            uri.rawQuery,
            null
        ).toASCIIString()
            .trimEnd('/')
    }
}

internal object PrimeProviderCodec {
    fun encode(
        profiles:
            List<PrimeProviderProfile>
    ): String {
        val array = JSONArray()
        profiles.forEach {
            profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put(
                        "kind",
                        profile.kind.name
                    )
                    .put(
                        "baseUrl",
                        profile.baseUrl
                    )
                    .put(
                        "model",
                        profile.model
                    )
                    .put(
                        "apiKey",
                        profile.apiKey
                            ?: JSONObject.NULL
                    )
                    .put(
                        "createdAt",
                        profile.createdAt
                    )
                    .put(
                        "updatedAt",
                        profile.updatedAt
                    )
            )
        }
        return array.toString()
    }

    fun decode(
        raw: String?
    ): List<PrimeProviderProfile> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (
                    index in 0
                        until array.length()
                ) {
                    val json =
                        array.optJSONObject(
                            index
                        ) ?: continue
                    val id =
                        json.optString("id")
                            .trim()
                    val name =
                        json.optString("name")
                            .trim()
                    val model =
                        json.optString("model")
                            .trim()
                    val kind =
                        runCatching {
                            PrimeProviderKind
                                .valueOf(
                                    json.optString(
                                        "kind"
                                    )
                                )
                        }.getOrNull()
                            ?: continue

                    if (
                        id.isBlank() ||
                        name.isBlank() ||
                        model.isBlank()
                    ) {
                        continue
                    }

                    val base =
                        runCatching {
                            PrimeProviderEndpointValidator
                                .normalize(
                                    json.optString(
                                        "baseUrl"
                                    )
                                )
                        }.getOrNull()
                            ?: continue

                    add(
                        PrimeProviderProfile(
                            id = id,
                            name =
                                name.take(80),
                            kind = kind,
                            baseUrl = base,
                            model =
                                model.take(160),
                            apiKey =
                                if (
                                    json.isNull(
                                        "apiKey"
                                    )
                                ) {
                                    null
                                } else {
                                    json.optString(
                                        "apiKey"
                                    )
                                        .trim()
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                },
                            createdAt =
                                json.optLong(
                                    "createdAt"
                                ),
                            updatedAt =
                                json.optLong(
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
}

internal class PrimeProviderStore(
    context: Context,
    private val secureStore:
        SecureStore =
        SecureStore(
            context.applicationContext
        )
) {
    companion object {
        private const val STORE_KEY =
            "prime_provider_profiles_v1"
        private const val ACTIVE_KEY =
            "prime_active_provider_v1"
        private const val MAX_PROFILES = 12
        const val CHATGPT_ID =
            "chatgpt-plan"
    }

    @Synchronized
    fun list():
        List<PrimeProviderProfile> =
        readAll()
            .sortedByDescending {
                it.updatedAt
            }

    @Synchronized
    fun get(id: String):
        PrimeProviderProfile? =
        readAll().firstOrNull {
            it.id == id
        }

    fun activeId(): String =
        secureStore
            .getString(ACTIVE_KEY)
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: CHATGPT_ID

    fun setActive(id: String) {
        if (id == CHATGPT_ID) {
            secureStore.putString(
                ACTIVE_KEY,
                CHATGPT_ID
            )
            return
        }
        require(get(id) != null) {
            "Provider profile does not exist"
        }
        secureStore.putString(
            ACTIVE_KEY,
            id
        )
    }

    @Synchronized
    fun save(
        name: String,
        kind: PrimeProviderKind,
        baseUrl: String,
        model: String,
        apiKey: String?,
        id: String? = null
    ): PrimeProviderProfile {
        val cleanName =
            name.trim().take(80)
        val cleanModel =
            model.trim().take(160)
        require(cleanName.isNotBlank()) {
            "Provider name is required"
        }
        require(cleanModel.isNotBlank()) {
            "Model is required"
        }

        val base =
            PrimeProviderEndpointValidator
                .normalize(baseUrl)
        val cleanKey =
            apiKey
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        if (
            kind !=
                PrimeProviderKind
                    .OPENAI_COMPATIBLE
        ) {
            require(cleanKey != null) {
                "API key is required for this provider"
            }
        }

        val records =
            readAll().toMutableList()
        val existingIndex =
            id?.let { wanted ->
                records.indexOfFirst {
                    it.id == wanted
                }.takeIf {
                    it >= 0
                }
            }

        val now =
            System.currentTimeMillis()
        val record =
            if (
                existingIndex != null
            ) {
                val current =
                    records[
                        existingIndex
                    ]
                current.copy(
                    name = cleanName,
                    kind = kind,
                    baseUrl = base,
                    model = cleanModel,
                    apiKey =
                        cleanKey
                            ?: current.apiKey,
                    updatedAt = now
                ).also {
                    records[
                        existingIndex
                    ] = it
                }
            } else {
                require(
                    records.size <
                        MAX_PROFILES
                ) {
                    "Maximum provider profile count reached"
                }
                PrimeProviderProfile(
                    id = UUID
                        .randomUUID()
                        .toString(),
                    name = cleanName,
                    kind = kind,
                    baseUrl = base,
                    model = cleanModel,
                    apiKey = cleanKey,
                    createdAt = now,
                    updatedAt = now
                ).also {
                    records += it
                }
            }

        writeAll(records)
        return record
    }

    @Synchronized
    fun remove(
        id: String
    ): PrimeProviderProfile? {
        val records =
            readAll().toMutableList()
        val index =
            records.indexOfFirst {
                it.id == id
            }
        if (index < 0) {
            return null
        }

        val removed =
            records.removeAt(index)
        writeAll(records)
        if (activeId() == id) {
            setActive(CHATGPT_ID)
        }
        return removed
    }

    private fun readAll():
        List<PrimeProviderProfile> =
        PrimeProviderCodec.decode(
            secureStore.getString(
                STORE_KEY
            )
        )

    private fun writeAll(
        profiles:
            List<PrimeProviderProfile>
    ) {
        secureStore.putString(
            STORE_KEY,
            PrimeProviderCodec
                .encode(profiles)
        )
    }
}

internal object PrimeProviderDefaults {
    fun baseUrl(
        kind: PrimeProviderKind
    ): String =
        when (kind) {
            PrimeProviderKind
                .OPENROUTER ->
                "https://openrouter.ai/api/v1"
            PrimeProviderKind
                .GEMINI ->
                "https://generativelanguage.googleapis.com/v1beta"
            PrimeProviderKind
                .ANTHROPIC ->
                "https://api.anthropic.com"
            PrimeProviderKind
                .OPENAI_COMPATIBLE ->
                "http://127.0.0.1:11434/v1"
        }

    fun modelHint(
        kind: PrimeProviderKind
    ): String =
        when (kind) {
            PrimeProviderKind
                .OPENROUTER ->
                "openrouter/free"
            PrimeProviderKind
                .GEMINI ->
                "gemini-3.8-flash"
            PrimeProviderKind
                .ANTHROPIC ->
                "claude-sonnet-4-6"
            PrimeProviderKind
                .OPENAI_COMPATIBLE ->
                "local-model"
        }
}

internal object PrimeProviderFactory {
    fun create(
        profile:
            PrimeProviderProfile
    ): AiProvider =
        when (profile.kind) {
            PrimeProviderKind
                .OPENROUTER ->
                PrimeOpenAiCompatibleProvider(
                    profile =
                        profile,
                    providerId =
                        "openrouter"
                )

            PrimeProviderKind
                .OPENAI_COMPATIBLE ->
                PrimeOpenAiCompatibleProvider(
                    profile =
                        profile,
                    providerId =
                        "openai-compatible"
                )

            PrimeProviderKind
                .GEMINI ->
                PrimeGeminiProvider(
                    profile
                )

            PrimeProviderKind
                .ANTHROPIC ->
                PrimeAnthropicProvider(
                    profile
                )
        }
}

private object PrimeProviderHttp {
    suspend fun post(
        url: String,
        headers:
            Map<String, String>,
        body: JSONObject,
        operation: String
    ): JSONObject =
        withContext(
            Dispatchers.IO
        ) {
            coroutineContext
                .ensureActive()

            val conn =
                URL(url)
                    .openConnection()
                    as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 12_000
            conn.readTimeout = 90_000
            conn.instanceFollowRedirects =
                false
            conn.setRequestProperty(
                "Content-Type",
                "application/json"
            )
            conn.setRequestProperty(
                "Accept",
                "application/json"
            )
            headers.forEach {
                (key, value) ->
                conn.setRequestProperty(
                    key,
                    value
                )
            }

            val bytes =
                body.toString()
                    .toByteArray(
                        Charsets.UTF_8
                    )
            conn.setFixedLengthStreamingMode(
                bytes.size
            )

            try {
                conn.outputStream.use {
                    it.write(bytes)
                }
                coroutineContext
                    .ensureActive()
                val status =
                    conn.responseCode
                val text =
                    (
                        if (
                            status in
                            200..299
                        ) {
                            conn.inputStream
                        } else {
                            conn.errorStream
                        }
                    )
                        ?.bufferedReader(
                            Charsets.UTF_8
                        )
                        ?.use {
                            it.readText()
                        }
                        .orEmpty()
                if (
                    status !in 200..299
                ) {
                    throw IllegalStateException(
                        operation +
                            " failed with HTTP " +
                            status +
                            ": " +
                            extractError(text)
                    )
                }
                coroutineContext
                    .ensureActive()
                JSONObject(text)
            } catch (
                e: CancellationException
            ) {
                throw e
            } catch (e: IOException) {
                throw IllegalStateException(
                    operation +
                        " network error: " +
                        (
                            e.message
                                ?: "I/O failure"
                            ),
                    e
                )
            } finally {
                conn.disconnect()
            }
        }

    private fun extractError(
        text: String
    ): String {
        if (text.isBlank()) {
            return "empty error response"
        }

        return runCatching {
            val json =
                JSONObject(text)
            json.optJSONObject(
                "error"
            )
                ?.optString(
                    "message"
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: json.optString(
                    "message"
                ).takeIf {
                    it.isNotBlank()
                }
        }.getOrNull()
            ?.take(500)
            ?: text.take(500)
    }
}

private abstract class PrimeConfiguredProvider(
    protected val profile:
        PrimeProviderProfile,
    final override val providerId:
        String
) : AiProvider {
    override fun isAvailable():
        Boolean =
        profile.kind ==
            PrimeProviderKind
                .OPENAI_COMPATIBLE ||
            !profile.apiKey
                .isNullOrBlank()

    override suspend fun listModels(
        forceRefresh: Boolean
    ): List<AiModel> =
        listOf(
            AiModel(
                id = profile.model,
                displayName =
                    profile.name +
                        " · " +
                        profile.model
            )
        )

    protected fun emit(
        value: String,
        onTextDelta:
            ((String) -> Unit)?
    ): String {
        val clean =
            value.trim()
        if (clean.isNotBlank()) {
            onTextDelta?.invoke(
                clean
            )
        }
        return clean
    }
}

private class PrimeOpenAiCompatibleProvider(
    profile: PrimeProviderProfile,
    providerId: String
) : PrimeConfiguredProvider(
    profile,
    providerId
) {
    override suspend fun streamText(
        request: AiTextRequest,
        onTextDelta:
            ((String) -> Unit)?
    ): String {
        val messages =
            JSONArray().apply {
                if (
                    request.instructions
                        .isNotBlank()
                ) {
                    put(
                        JSONObject()
                            .put(
                                "role",
                                "system"
                            )
                            .put(
                                "content",
                                request.instructions
                            )
                    )
                }
                request.messages
                    .forEach {
                        message ->
                        put(
                            JSONObject()
                                .put(
                                    "role",
                                    message.role
                                )
                                .put(
                                    "content",
                                    message.content
                                )
                        )
                    }
            }

        val body =
            JSONObject()
                .put(
                    "model",
                    request.model
                )
                .put(
                    "messages",
                    messages
                )
                .put(
                    "stream",
                    false
                )

        val headers =
            linkedMapOf<String, String>()
        profile.apiKey
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                headers[
                    "Authorization"
                ] = "Bearer " + it
            }
        if (
            profile.kind ==
            PrimeProviderKind.OPENROUTER
        ) {
            headers[
                "X-Title"
            ] = "PRIME P6"
        }

        val response =
            PrimeProviderHttp.post(
                url =
                    profile.baseUrl +
                        "/chat/completions",
                headers = headers,
                body = body,
                operation =
                    profile.name
            )
        val text =
            response
                .optJSONArray(
                    "choices"
                )
                ?.optJSONObject(0)
                ?.optJSONObject(
                    "message"
                )
                ?.optString(
                    "content"
                )
                .orEmpty()

        return emit(
            text,
            onTextDelta
        ).ifBlank {
            throw IllegalStateException(
                profile.name +
                    " returned no text"
            )
        }
    }
}

private class PrimeGeminiProvider(
    profile: PrimeProviderProfile
) : PrimeConfiguredProvider(
    profile,
    "gemini"
) {
    override suspend fun streamText(
        request: AiTextRequest,
        onTextDelta:
            ((String) -> Unit)?
    ): String {
        val contents =
            JSONArray()
        request.messages.forEach {
            message ->
            contents.put(
                JSONObject()
                    .put(
                        "role",
                        if (
                            message.role ==
                            "assistant"
                        ) {
                            "model"
                        } else {
                            "user"
                        }
                    )
                    .put(
                        "parts",
                        JSONArray().put(
                            JSONObject()
                                .put(
                                    "text",
                                    message.content
                                )
                        )
                    )
            )
        }

        val body =
            JSONObject()
                .put(
                    "contents",
                    contents
                )
        if (
            request.instructions
                .isNotBlank()
        ) {
            body.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            request.instructions
                        )
                    )
                )
            )
        }

        val model =
            URLEncoder.encode(
                request.model,
                Charsets.UTF_8.name()
            )
                .replace("+", "%20")
        val response =
            PrimeProviderHttp.post(
                url =
                    profile.baseUrl +
                        "/models/" +
                        model +
                        ":generateContent",
                headers =
                    mapOf(
                        "x-goog-api-key" to
                            (
                                profile.apiKey
                                    ?: ""
                                )
                    ),
                body = body,
                operation =
                    profile.name
            )

        val parts =
            response
                .optJSONArray(
                    "candidates"
                )
                ?.optJSONObject(0)
                ?.optJSONObject(
                    "content"
                )
                ?.optJSONArray(
                    "parts"
                )
                ?: JSONArray()

        val text =
            buildString {
                for (
                    index in 0
                        until parts.length()
                ) {
                    val value =
                        parts
                            .optJSONObject(
                                index
                            )
                            ?.optString(
                                "text"
                            )
                            .orEmpty()
                    if (
                        value.isNotBlank()
                    ) {
                        if (
                            isNotEmpty()
                        ) {
                            appendLine()
                        }
                        append(value)
                    }
                }
            }

        return emit(
            text,
            onTextDelta
        ).ifBlank {
            throw IllegalStateException(
                profile.name +
                    " returned no text"
            )
        }
    }
}

private class PrimeAnthropicProvider(
    profile: PrimeProviderProfile
) : PrimeConfiguredProvider(
    profile,
    "anthropic"
) {
    override suspend fun streamText(
        request: AiTextRequest,
        onTextDelta:
            ((String) -> Unit)?
    ): String {
        val messages =
            JSONArray().apply {
                request.messages
                    .forEach {
                        message ->
                        put(
                            JSONObject()
                                .put(
                                    "role",
                                    if (
                                        message.role ==
                                        "assistant"
                                    ) {
                                        "assistant"
                                    } else {
                                        "user"
                                    }
                                )
                                .put(
                                    "content",
                                    message.content
                                )
                        )
                    }
            }

        val body =
            JSONObject()
                .put(
                    "model",
                    request.model
                )
                .put(
                    "max_tokens",
                    4096
                )
                .put(
                    "messages",
                    messages
                )
        if (
            request.instructions
                .isNotBlank()
        ) {
            body.put(
                "system",
                request.instructions
            )
        }

        val response =
            PrimeProviderHttp.post(
                url =
                    profile.baseUrl +
                        "/v1/messages",
                headers =
                    mapOf(
                        "Authorization" to
                            (
                                "Bearer " +
                                    (
                                        profile.apiKey
                                            ?: ""
                                        )
                                ),
                        "anthropic-version" to
                            "2023-06-01"
                    ),
                body = body,
                operation =
                    profile.name
            )

        val content =
            response.optJSONArray(
                "content"
            ) ?: JSONArray()
        val text =
            buildString {
                for (
                    index in 0
                        until content.length()
                ) {
                    val block =
                        content
                            .optJSONObject(
                                index
                            )
                            ?: continue
                    if (
                        block.optString(
                            "type"
                        ) != "text"
                    ) {
                        continue
                    }
                    val value =
                        block.optString(
                            "text"
                        )
                    if (
                        value.isNotBlank()
                    ) {
                        if (
                            isNotEmpty()
                        ) {
                            appendLine()
                        }
                        append(value)
                    }
                }
            }

        return emit(
            text,
            onTextDelta
        ).ifBlank {
            throw IllegalStateException(
                profile.name +
                    " returned no text"
            )
        }
    }
}
