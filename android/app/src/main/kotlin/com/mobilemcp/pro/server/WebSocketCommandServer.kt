package com.mobilemcp.pro.server

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.mobilemcp.pro.model.CommandRequest
import com.mobilemcp.pro.model.CommandResponse
import com.mobilemcp.pro.service.MobileAccessibilityService
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

class WebSocketCommandServer(
    port: Int,
    authToken: String,
    private val onLog: (String) -> Unit,
    private val onConnectionChange: (Int) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "WSCommandServer"
        private const val MIN_TOKEN_LENGTH = 32
        private const val AUTH_PREFIX = "Bearer "
    }

    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()
    private val connectedClients = mutableSetOf<WebSocket>()
    private val authToken = authToken.trim().also {
        require(it.length >= MIN_TOKEN_LENGTH) {
            "Device Bridge authentication token is missing or too short"
        }
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val remoteAddr = conn.remoteSocketAddress?.toString() ?: "unknown"

        if (!isAuthorized(handshake.getFieldValue("Authorization"))) {
            Log.w(TAG, "Rejected unauthenticated client: $remoteAddr")
            onLog("Client rejected: authentication required")
            conn.close(4001, "Unauthorized")
            return
        }

        Log.i(TAG, "Authenticated client connected: $remoteAddr")
        onLog("Authenticated client connected: $remoteAddr")
        synchronized(connectedClients) {
            connectedClients.add(conn)
        }
        onConnectionChange(connectedClients.size)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val remoteAddr = conn.remoteSocketAddress?.toString() ?: "unknown"
        Log.i(TAG, "Client disconnected: $remoteAddr")
        onLog("Client disconnected: $remoteAddr")
        synchronized(connectedClients) {
            connectedClients.remove(conn)
        }
        onConnectionChange(connectedClients.size)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (!synchronized(connectedClients) { conn in connectedClients }) return
        if (message.length > 1_000_000) {
            conn.close(1009, "Message too large")
            return
        }

        executor.submit {
            try {
                val request = gson.fromJson(message, CommandRequest::class.java)
                if (request.command == null) {
                    sendError(conn, null, "Missing 'command' field")
                    return@submit
                }

                onLog(">> ${request.command}")

                val service = MobileAccessibilityService.instance
                if (service == null) {
                    sendError(
                        conn,
                        request.id,
                        "Accessibility service is not running. Enable it in Settings."
                    )
                    return@submit
                }

                val response = service.handleCommand(request)
                val finalResponse = response.copy(id = request.id)
                conn.send(gson.toJson(finalResponse))

                onLog(
                    "<< ${request.command}: " +
                        if (finalResponse.success) "OK" else "ERR"
                )
            } catch (e: JsonSyntaxException) {
                sendError(conn, null, "Invalid JSON: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
                sendError(conn, null, "Internal error: ${e.message}")
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
        onLog("Error: ${ex.message}")
    }

    override fun onStart() {
        Log.i(TAG, "Authenticated WebSocket server started on port ${this.port}")
        onLog("Authenticated bridge started on port ${this.port}")
        connectionLostTimeout = 60
    }

    fun getConnectionCount(): Int =
        synchronized(connectedClients) { connectedClients.size }

    private fun isAuthorized(header: String?): Boolean {
        if (header.isNullOrBlank() || !header.startsWith(AUTH_PREFIX)) return false

        val provided = header.removePrefix(AUTH_PREFIX).toByteArray(Charsets.UTF_8)
        val expected = authToken.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(provided, expected)
    }

    private fun sendError(conn: WebSocket, id: String?, message: String) {
        val response = CommandResponse.error(id, message)
        conn.send(gson.toJson(response))
        onLog("<< ERROR: $message")
    }

    fun shutdown() {
        try {
            executor.shutdownNow()
            stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down", e)
        }
    }
}
