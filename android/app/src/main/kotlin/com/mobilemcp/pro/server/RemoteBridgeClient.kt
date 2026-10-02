package com.mobilemcp.pro.server

import android.util.Log
import com.google.gson.Gson
import com.mobilemcp.pro.model.CommandRequest
import com.mobilemcp.pro.model.CommandResponse
import com.mobilemcp.pro.service.MobileAccessibilityService
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RemoteBridgeClient(
    private val relayBaseUrl: String,
    private val credential: String,
    private val onLog: (String) -> Unit = {}
) {
    data class PairingResult(
        val credential: String,
        val mcpUrl: String
    )

    companion object {
        private const val TAG = "RemoteBridgeClient"

        fun pair(relayBaseUrl: String, pairingCode: String, deviceId: String): PairingResult {
            require(pairingCode.matches(Regex("\\d{6}"))) {
                "Pairing code must be 6 digits"
            }
            require(deviceId.matches(Regex("[a-zA-Z0-9_-]{8,128}"))) {
                "Invalid device identifier"
            }

            val base = relayBaseUrl.trim().trimEnd('/')
            require(base.startsWith("https://")) {
                "Remote bridge pairing requires HTTPS"
            }

            val connection = (URL("$base/phone/pair").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject()
                .put("code", pairingCode)
                .put("deviceId", deviceId)
                .toString()

            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (responseCode !in 200..299) {
                val message = runCatching {
                    JSONObject(responseBody).optString("error")
                }.getOrNull().orEmpty()
                throw IllegalStateException(
                    if (message.isNotBlank()) message else "Pairing failed (HTTP $responseCode)"
                )
            }

            val json = JSONObject(responseBody)
            val token = json.optString("credential").trim()
            val mcpUrl = json.optString("mcpUrl").trim()
            require(token.length >= 32) { "Bridge returned an invalid credential" }
            require(mcpUrl.startsWith("https://")) { "Bridge returned an invalid MCP URL" }
            return PairingResult(token, mcpUrl)
        }

        fun websocketUrl(relayBaseUrl: String): String {
            val base = relayBaseUrl.trim().trimEnd('/')
            return when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + "/phone"
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://") + "/phone"
                else -> throw IllegalArgumentException("Invalid remote bridge URL")
            }
        }
    }

    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    @Volatile private var socket: WebSocketClient? = null

    fun start() {
        require(credential.trim().length >= 32) {
            "Remote bridge credential is missing or too short"
        }
        stopped.set(false)
        connect()
    }

    private fun connect() {
        if (stopped.get()) return
        val headers = mapOf("Authorization" to "Bearer ${credential.trim()}")
        val client = object : WebSocketClient(URI(websocketUrl(relayBaseUrl)), headers) {
            override fun onOpen(handshake: ServerHandshake?) {
                onLog("Remote bridge connected")
            }

            override fun onMessage(message: String?) {
                if (message.isNullOrBlank() || message.length > 1_000_000) return
                executor.submit { handleMessage(this, message) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                onLog("Remote bridge disconnected")
                if (!stopped.get()) scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                Log.w(TAG, "Remote bridge error", ex)
                onLog("Remote bridge error: ${ex?.message ?: "unknown"}")
            }
        }
        socket = client
        client.connect()
    }

    private fun handleMessage(client: WebSocketClient, message: String) {
        val response = try {
            val request = gson.fromJson(message, CommandRequest::class.java)
            if (request.command == null) {
                CommandResponse.error(request.id, "Missing 'command' field")
            } else {
                val service = MobileAccessibilityService.instance
                if (service == null) {
                    CommandResponse.error(
                        request.id,
                        "Accessibility service is not running. Enable it in Settings."
                    )
                } else {
                    service.handleCommand(request).copy(id = request.id)
                }
            }
        } catch (e: Exception) {
            CommandResponse.error(null, "Remote command failed: ${e.message}")
        }
        if (client.isOpen) client.send(gson.toJson(response))
    }

    private fun scheduleReconnect() {
        Thread {
            try {
                Thread.sleep(3_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            connect()
        }.start()
    }

    fun stop() {
        stopped.set(true)
        socket?.close()
        socket = null
        executor.shutdownNow()
    }
}
