package com.mobilemcp.pro.ai

import com.mobilemcp.pro.PrimeApiEndpoints
import com.mobilemcp.pro.PrimeCredentials
import com.mobilemcp.pro.ResponsesStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

internal class OpenAiResponsesProvider(
    private val auth: PrimeCredentials,
    private val endpoints: PrimeApiEndpoints = PrimeApiEndpoints()
) : AiProvider {

    companion object {
        private const val USER_AGENT = "PRIME-P6/6.0.9"
    }

    override val info = AiProviderInfo(
        id = "openai-chatgpt",
        label = "OpenAI / ChatGPT",
        capabilities = setOf(
            AiCapability.TEXT,
            AiCapability.STREAMING,
            AiCapability.STRUCTURED_OUTPUT
        )
    )

    override suspend fun listModels(): List<AiModel> =
        withNetworkRetry("models") {
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
                val stream =
                    if (status in 200..299) conn.inputStream else conn.errorStream

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

                val visible = mutableListOf<AiModel>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (item.optString("visibility") != "list") continue

                    val slug = item.optString("slug")
                    if (slug.isBlank()) continue

                    visible += AiModel(
                        id = slug,
                        displayName = item.optString("display_name")
                            .ifBlank { slug }
                    )
                }

                if (visible.isEmpty()) {
                    throw IllegalStateException(
                        "No visible ChatGPT model is available"
                    )
                }

                visible
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun generateText(request: AiTextRequest): String {
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
                    val errorBody =
                        conn.errorStream?.bufferedReader()?.use { it.readText() }
                            .orEmpty()
                    throw apiError(status, errorBody)
                }

                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    ResponsesStream.read(
                        reader,
                        onDelta = { delta ->
                            if (request.onTextDelta != null) emitted = true
                            request.onTextDelta?.invoke(delta)
                        },
                        checkCancelled = { requestContext.ensureActive() }
                    )
                }
            } catch (e: IOException) {
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
