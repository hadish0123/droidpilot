package com.mobilemcp.pro.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeChatSchemaTest {
    @Test
    fun migrationToVersionTwoIsAdditive() {
        assertEquals(2, PrimeChatSchema.VERSION)

        val sql = PrimeChatSchema.migration1To2.joinToString("\n")
        assertTrue(sql.contains("CREATE TABLE attachments"))
        assertTrue(sql.contains("CREATE TABLE tool_calls"))
        assertTrue(sql.contains("parent_message_id"))
        assertFalse(sql.contains("DROP TABLE", ignoreCase = true))
    }
}
