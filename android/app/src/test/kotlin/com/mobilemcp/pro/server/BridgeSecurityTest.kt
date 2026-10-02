package com.mobilemcp.pro.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSecurityTest {
    @Test fun generatedTokensAreStrongAndUnique() {
        val first = BridgeSecurity.generateToken()
        val second = BridgeSecurity.generateToken()

        assertTrue(first.length >= 64)
        assertNotEquals(first, second)
    }

    @Test fun authorizationIsFailClosed() {
        val token = BridgeSecurity.generateToken()

        assertFalse(BridgeSecurity.isAuthorized(null, token))
        assertFalse(BridgeSecurity.isAuthorized("", token))
        assertFalse(BridgeSecurity.isAuthorized("Bearer wrong", token))
        assertTrue(BridgeSecurity.isAuthorized("Bearer $token", token))
    }
}
