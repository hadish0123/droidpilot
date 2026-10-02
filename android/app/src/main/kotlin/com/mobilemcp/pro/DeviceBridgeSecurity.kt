package com.mobilemcp.pro

import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the credential used by the optional Device Bridge.
 *
 * The token is generated with 256 bits of entropy and persisted through
 * [SecureStore], so it is encrypted with Android Keystore before it reaches
 * SharedPreferences.
 */
class DeviceBridgeSecurity(
    private val secureStore: SecureStore
) {
    companion object {
        private const val STORE_KEY = "device_bridge_auth_token_v1"
        private const val TOKEN_BYTES = 32

        internal fun generateToken(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    fun getOrCreateToken(): String {
        val existing = secureStore.getString(STORE_KEY)?.trim()
        if (!existing.isNullOrBlank()) return existing

        return generateToken().also { token ->
            secureStore.putString(STORE_KEY, token)
        }
    }

    fun rotateToken(): String =
        generateToken().also { token ->
            secureStore.putString(STORE_KEY, token)
        }
}
