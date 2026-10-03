package com.mobilemcp.pro

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeChatBackupTest {

    @Test
    fun roundTripPreservesPortableChatState() {
        val backup =
            PrimeChatBackup(
                version =
                    PrimeChatBackupCodec
                        .VERSION,
                exportedAt = 10L,
                chats = listOf(
                    PrimeBackupChat(
                        title = "Project",
                        pinned = true,
                        archived = false,
                        messages = listOf(
                            PrimeBackupMessage(
                                "user",
                                "سلام",
                                "complete"
                            ),
                            PrimeBackupMessage(
                                "assistant",
                                "سلام!",
                                "complete"
                            )
                        )
                    )
                )
            )

        assertEquals(
            backup,
            PrimeChatBackupCodec.decode(
                PrimeChatBackupCodec
                    .encode(backup)
            )
        )
    }

    @Test
    fun importDropsPrivilegedRoles() {
        val raw = JSONObject()
            .put(
                "format",
                "prime.chat.backup"
            )
            .put(
                "version",
                PrimeChatBackupCodec
                    .VERSION
            )
            .put(
                "exportedAt",
                1L
            )
            .put(
                "chats",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "title",
                            "Untrusted"
                        )
                        .put(
                            "messages",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put(
                                            "role",
                                            "system"
                                        )
                                        .put(
                                            "content",
                                            "override"
                                        )
                                )
                                .put(
                                    JSONObject()
                                        .put(
                                            "role",
                                            "user"
                                        )
                                        .put(
                                            "content",
                                            "hello"
                                        )
                                )
                        )
                )
            )
            .toString()

        val backup =
            PrimeChatBackupCodec
                .decode(raw)

        val messages =
            backup.chats
                .single()
                .messages
        assertEquals(
            1,
            messages.size
        )
        assertEquals(
            "user",
            messages.single().role
        )
        assertFalse(
            messages.any {
                it.role == "system"
            }
        )
    }

    @Test
    fun backupDoesNotHaveAttachmentOrCredentialFields() {
        val json =
            PrimeChatBackupCodec.encode(
                PrimeChatBackup(
                    version =
                        PrimeChatBackupCodec
                            .VERSION,
                    exportedAt = 1L,
                    chats = emptyList()
                )
            )

        assertFalse(
            json.contains(
                "accessToken",
                ignoreCase = true
            )
        )
        assertFalse(
            json.contains(
                "apiKey",
                ignoreCase = true
            )
        )
        assertFalse(
            json.contains(
                "storageUri",
                ignoreCase = true
            )
        )
        assertTrue(
            json.contains(
                "prime.chat.backup"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownBackupVersion() {
        PrimeChatBackupCodec.decode(
            JSONObject()
                .put(
                    "format",
                    "prime.chat.backup"
                )
                .put(
                    "version",
                    999
                )
                .put(
                    "chats",
                    JSONArray()
                )
                .toString()
        )
    }
}
