package com.mobilemcp.pro

import java.util.UUID

class RemoteBridgeSecurity(
    private val secureStore: SecureStore
) {
    companion object {
        const val DEFAULT_RELAY_URL =
            "https://prime-p6-remote-mcp-production.up.railway.app"

        private const val DEVICE_ID_KEY = "remote_bridge_device_id_v1"
        private const val CREDENTIAL_KEY = "remote_bridge_credential_v1"
        private const val ENABLED_KEY = "remote_bridge_enabled_v1"
    }

    fun getOrCreateDeviceId(): String {
        val current = secureStore.getString(DEVICE_ID_KEY)?.trim()
        if (!current.isNullOrBlank()) return current
        return UUID.randomUUID().toString().also {
            secureStore.putString(DEVICE_ID_KEY, it)
        }
    }

    fun credential(): String? =
        secureStore.getString(CREDENTIAL_KEY)?.trim()?.takeIf { it.isNotBlank() }

    fun saveCredential(value: String) {
        require(value.length >= 32) { "Remote bridge credential is invalid" }
        secureStore.putString(CREDENTIAL_KEY, value)
    }

    fun isEnabled(): Boolean =
        secureStore.getString(ENABLED_KEY) == "1"

    fun setEnabled(enabled: Boolean) {
        secureStore.putString(ENABLED_KEY, if (enabled) "1" else "0")
    }

    fun clearCredential() {
        secureStore.remove(CREDENTIAL_KEY)
        setEnabled(false)
    }
}
