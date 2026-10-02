package com.mobilemcp.pro.server

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mobilemcp.pro.model.CommandRequest
import com.mobilemcp.pro.model.CommandResponse
import com.mobilemcp.pro.service.MobileAccessibilityService
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional outbound PRIME bridge for remote MCP relays.
 *
 * This is additive: the existing LAN WebSocketCommandServer remains unchanged.
 * The phone initiates the TLS connection, so no inbound port or public phone IP
 * is required. Relay authentication is carried in the Authorization header.
 */
class RemoteBridgeClient(
    private val relayUrl: String,
    private val authToken: String,
    private val onLog: (String) -> Unit = {}
) {
    companion object { private const val TAG = "RemoteBridgeClient" }

    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    @Volatile private var socket: WebSocketClient? = null

    fun start() {
        require(relayUrl.startsWith("wss://")) { "Remote bridge requires wss://" }
        require(authToken.trim().length >= 32) { "Remote bridge token is missing or too short" }
        stopped.set(false)
        connect()
    }

    private fun connect() {
        if (stopped.get()) return
        val headers = mapOf("Authorization" to "Bearer ${authToken.trim()}")
        val client = object : WebSocketClient(URI(relayUrl), headers) {
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
                    CommandResponse.error(request.id, "Accessibility service is not running. Enable it in Settings.")
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
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
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
