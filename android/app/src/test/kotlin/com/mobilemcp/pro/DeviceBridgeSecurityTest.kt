package com.mobilemcp.pro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBridgeSecurityTest {
    @Test
    fun generatedTokenHasStrongUrlSafeShape() {
        val token = DeviceBridgeSecurity.generateToken()

        assertTrue(token.length >= 43)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(token.contains('='))
    }
}
