package com.mobilemcp.pro.server

import java.security.SecureRandom

internal object BridgeSecurity {
    private const val TOKEN_BYTES = 32
    private val secureRandom = SecureRandom()

    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isAuthorized(authorizationHeader: String?, expectedToken: String): Boolean {
        val expected = "Bearer $expectedToken"
        if (authorizationHeader == null || authorizationHeader.length != expected.length) return false

        var difference = 0
        for (index in expected.indices) {
            difference = difference or (authorizationHeader[index].code xor expected[index].code)
        }
        return difference == 0
    }
}
