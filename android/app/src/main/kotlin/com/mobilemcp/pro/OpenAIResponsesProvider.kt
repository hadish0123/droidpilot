package com.mobilemcp.pro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

internal interface PrimeCredentials {
    fun isSignedIn(): Boolean
    suspend fun accessToken(): String
}

internal data class PrimeApiEndpoints(
    val models: String = "https://api.openai.com/v1/models",
    val responses: String = "https://api.openai.com/v1/responses"
)

/**
 * OpenAI Responses API implementation of [AiProvider].
 *
 * PRIME orchestration remains provider-neutral; all OpenAI-specific HTTP,
 * authentication, SSE parsing, retry and error mapping lives here.
 */
internal class OpenAIResponsesProvider(
    private val auth: PrimeCredentials,
    private val endpoints: PrimeApiEndpoints = PrimeApiEndpoints()
) : AiProvider {

    companion object {
        private const val USER_AGENT = "PRIME-P6/6.0.9"
    }

    override val providerId: String = "openai-responses"

    override val supportsWebSearch: Boolean = true

    private val modelMutex = Mutex()

    @Volatile
    private var modelCache: List<AiModel>? = null

    override fun isAvailable(): Boolean = auth.isSignedIn()

    override fun reset() {
        modelCache = null
    }

    override suspend fun listModels(forceRefresh: Boolean): List<AiModel> {
        if (!forceRefresh) modelCache?.let { return it }

        return modelMutex.withLock {
            if (!forceRefresh) modelCache?.let { return@withLock it }

            val models = withNetworkRetry("models") {
                val token = auth.accessToken()
                coroutineContext.ensureActive()

                val conn = URL(endpoints.models).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", USER_AGENT)

                try {
                    val status = conn.responseCode
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    val body = stream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()

                    if (status !in 200..299) throw apiError(status, body)

                    val array = JSONObject(body)
                        .optJSONArray("models")
                        ?: throw IllegalStateException(
                            "No ChatGPT models are available for this account"
                        )

                    buildList {
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            if (item.optString("visibility") != "list") continue

                            val slug = item.optString("slug")
                            if (slug.isBlank()) continue

                            add(
                                AiModel(
                                    id = slug,
                                    displayName = item.optString("display_name")
                                        .ifBlank { slug }
                                )
                            )
                        }
                    }.ifEmpty {
                        throw IllegalStateException(
                            "No visible ChatGPT model is available"
                        )
                    }
                } finally {
                    conn.disconnect()
                }
            }

            modelCache = models
            models
        }
    }

    override suspend fun searchWeb(
        request: AiTextRequest
    ): AiWebResult {
        return withNetworkRetry(
            "web search"
        ) {
            val token = auth.accessToken()
            coroutineContext.ensureActive()

            val input = JSONArray()
            request.messages.forEach { message ->
                input.put(
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

            val body = JSONObject()
                .put(
                    "model",
                    request.model
                )
                .put(
                    "instructions",
                    request.instructions
                )
                .put("input", input)
                .put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put(
                            "type",
                            "web_search"
                        )
                    )
                )
                .put(
                    "tool_choice",
                    "required"
                )
                .put(
                    "include",
                    JSONArray().put(
                        "web_search_call.action.sources"
                    )
                )
                .put("store", false)
                .put("stream", false)

            val conn = URL(
                endpoints.responses
            ).openConnection()
                as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 12_000
            conn.readTimeout = 90_000
            conn.setRequestProperty(
                "Authorization",
                "Bearer " + token
            )
            conn.setRequestProperty(
                "Content-Type",
                "application/json"
            )
            conn.setRequestProperty(
                "Accept",
                "application/json"
            )
            conn.setRequestProperty(
                "User-Agent",
                USER_AGENT
            )

            val bytes = body.toString()
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

                val status =
                    conn.responseCode
                val responseBody =
                    (
                        if (
                            status in 200..299
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
                    throw apiError(
                        status,
                        responseBody
                    )
                }

                coroutineContext
                    .ensureActive()
                parseWebResult(
                    responseBody
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    override suspend fun streamText(
        request: AiTextRequest,
        onTextDelta: ((String) -> Unit)?
    ): String {
        var emitted = false

        return withNetworkRetry("responses") {
            val token = auth.accessToken()
            val requestContext = coroutineContext
            requestContext.ensureActive()

            val input = JSONArray()
            request.messages.forEach { message ->
                input.put(
                    JSONObject()
                        .put("role", message.role)
                        .put("content", message.content)
                )
            }

            val body = JSONObject()
                .put("model", request.model)
                .put("instructions", request.instructions)
                .put("input", input)
                .put("store", false)
                .put("stream", true)

            val conn = URL(endpoints.responses).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 12_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("User-Agent", USER_AGENT)

            val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bodyBytes.size)

            try {
                conn.outputStream.use { it.write(bodyBytes) }

                val status = conn.responseCode
                if (status !in 200..299) {
                    val errorBody = conn.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                    throw apiError(status, errorBody)
                }

                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    ResponsesStream.read(
                        reader,
                        onDelta = { delta ->
                            if (onTextDelta != null) emitted = true
                            onTextDelta?.invoke(delta)
                        },
                        checkCancelled = { requestContext.ensureActive() }
                    )
                }
            } catch (e: IOException) {
                // Never replay an already displayed/heard prefix on retry.
                if (emitted) {
                    throw IllegalStateException(
                        "پاسخ هنگام دریافت قطع شد؛ دوباره تلاش کن.",
                        e
                    )
                }
                throw e
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parseWebResult(
        raw: String
    ): AiWebResult {
        val root = JSONObject(raw)
        val output = root
            .optJSONArray("output")
            ?: JSONArray()

        val text = StringBuilder()
        val citations =
            linkedMapOf<String, AiWebCitation>()

        for (
            outputIndex in 0
                until output.length()
        ) {
            val item = output
                .optJSONObject(outputIndex)
                ?: continue

            when (
                item.optString("type")
            ) {
                "message" -> {
                    val content = item
                        .optJSONArray(
                            "content"
                        )
                        ?: JSONArray()

                    for (
                        contentIndex in 0
                            until content.length()
                    ) {
                        val part = content
                            .optJSONObject(
                                contentIndex
                            )
                            ?: continue
                        if (
                            part.optString(
                                "type"
                            ) != "output_text"
                        ) {
                            continue
                        }

                        val value =
                            part.optString(
                                "text"
                            )
                        if (
                            value.isNotBlank()
                        ) {
                            if (
                                text.isNotEmpty()
                            ) {
                                text.appendLine()
                            }
                            text.append(value)
                        }

                        collectAnnotations(
                            part.optJSONArray(
                                "annotations"
                            ),
                            citations
                        )
                    }
                }

                "web_search_call" -> {
                    collectSources(
                        item.optJSONObject(
                            "action"
                        )?.optJSONArray(
                            "sources"
                        ),
                        citations
                    )
                }
            }
        }

        val answer = text
            .toString()
            .trim()
        if (answer.isBlank()) {
            throw IllegalStateException(
                "Web search returned no text result"
            )
        }

        return AiWebResult(
            text = answer,
            citations =
                citations.values
                    .take(12)
        )
    }

    private fun collectAnnotations(
        array: JSONArray?,
        output:
            MutableMap<String, AiWebCitation>
    ) {
        if (array == null) return

        for (
            index in 0
                until array.length()
        ) {
            val item = array
                .optJSONObject(index)
                ?: continue
            if (
                item.optString("type") !=
                "url_citation"
            ) {
                continue
            }

            addCitation(
                url = item.optString(
                    "url"
                ),
                title = item.optString(
                    "title"
                ),
                output = output
            )
        }
    }

    private fun collectSources(
        array: JSONArray?,
        output:
            MutableMap<String, AiWebCitation>
    ) {
        if (array == null) return

        for (
            index in 0
                until array.length()
        ) {
            val item = array
                .optJSONObject(index)
                ?: continue
            addCitation(
                url = item.optString(
                    "url"
                ),
                title = item.optString(
                    "title"
                ),
                output = output
            )
        }
    }

    private fun addCitation(
        url: String,
        title: String?,
        output:
            MutableMap<String, AiWebCitation>
    ) {
        val clean = url.trim()
        if (
            clean.isBlank() ||
            output.containsKey(clean)
        ) {
            return
        }

        val valid = runCatching {
            val parsed = URL(clean)
            parsed.protocol == "https" ||
                parsed.protocol == "http"
        }.getOrDefault(false)
        if (!valid) return

        output[clean] =
            AiWebCitation(
                url = clean,
                title = title
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.take(180)
            )
    }

    private suspend fun <T> withNetworkRetry(
        operation: String,
        block: suspend () -> T
    ): T {
        var last: Throwable? = null

        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is IllegalStateException || !isRetryableNetworkError(e)) {
                    throw e
                }
                last = e

                if (attempt < 2) {
                    delay(if (attempt == 0) 450L else 1_100L)
                }
            }
        }

        throw friendlyNetworkException(operation, last)
    }

    private fun isRetryableNetworkError(error: Throwable?): Boolean {
        var current = error

        while (current != null) {
            if (
                current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException ||
                current is IOException
            ) {
                return true
            }

            val message = current.message.orEmpty()
            if (
                message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("unexpected end of stream", ignoreCase = true) ||
                message.contains("stream ended", ignoreCase = true) ||
                message.contains("connection reset", ignoreCase = true) ||
                message.contains("broken pipe", ignoreCase = true)
            ) {
                return true
            }

            current = current.cause
        }

        return false
    }

    private fun friendlyNetworkException(
        operation: String,
        error: Throwable?
    ): IllegalStateException {
        var current = error
        var dns = false

        while (current != null) {
            if (
                current is UnknownHostException ||
                current.message.orEmpty()
                    .contains("Unable to resolve host", ignoreCase = true)
            ) {
                dns = true
                break
            }
            current = current.cause
        }

        return if (dns) {
            IllegalStateException(
                "PRIME_API_DNS: PRIME cannot resolve api.openai.com after retries. " +
                    "Check whether your VPN/proxy includes PRIME.",
                error
            )
        } else {
            IllegalStateException(
                "PRIME_API_NETWORK: OpenAI $operation connection was interrupted after retries.",
                error
            )
        }
    }

    private fun apiError(status: Int, body: String): Exception {
        val message = try {
            val json = JSONObject(body)
            json.optString("detail").ifBlank {
                json.optJSONObject("error")?.optString("message").orEmpty()
            }
        } catch (_: Exception) {
            ""
        }

        val friendly = when {
            message.isNotBlank() -> message
            status == 401 ->
                "ChatGPT connection expired. Connect the account again."
            status == 403 ->
                "ChatGPT plan access is not available for this request."
            status == 429 ->
                "ChatGPT usage limit reached. Try again later."
            else ->
                "ChatGPT request failed with HTTP $status"
        }

        return IllegalStateException(friendly)
    }
}
