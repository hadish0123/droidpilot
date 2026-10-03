package com.mobilemcp.pro

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrimeDeviceRuntimeInstrumentedTest {

    private val context: Context
        get() =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext

    @Before
    fun resetStorage() {
        context.deleteDatabase(
            "prime_chats.db"
        )
        context.getSharedPreferences(
            "prime_p6_secure",
            Context.MODE_PRIVATE
        ).edit()
            .clear()
            .commit()
    }

    @After
    fun cleanupStorage() {
        context.deleteDatabase(
            "prime_chats.db"
        )
        context.getSharedPreferences(
            "prime_p6_secure",
            Context.MODE_PRIVATE
        ).edit()
            .clear()
            .commit()
    }

    @Test
    fun chatAttachmentsPersistAndCascadeOnRealSqlite() {
        val store =
            PrimeChatStore(context)
        val chatId =
            store.createChat(
                "device-test"
            )
        val messageId =
            store.appendMessage(
                chatId = chatId,
                role = "user",
                content = "hello"
            )
        val attachmentId =
            store.addAttachment(
                chatId = chatId,
                kind = "document",
                displayName =
                    "report.pdf",
                mimeType =
                    "application/pdf",
                storageUri =
                    "content://prime-test/report",
                sizeBytes = 123L,
                messageId = messageId
            )

        assertTrue(
            attachmentId > 0L
        )
        assertEquals(
            1,
            store.attachments(
                chatId
            ).size
        )
        store.close()

        val reopened =
            PrimeChatStore(context)
        val attachment =
            reopened
                .attachments(chatId)
                .single()

        assertEquals(
            "report.pdf",
            attachment.displayName
        )
        assertEquals(
            messageId,
            attachment.messageId
        )

        reopened.deleteChat(chatId)
        assertFalse(
            reopened.chatExists(
                chatId
            )
        )
        assertTrue(
            reopened
                .attachments(chatId)
                .isEmpty()
        )
        reopened.close()
    }

    @Test
    fun secureStoreEncryptsRoundTripWithAndroidKeystore() {
        val store =
            SecureStore(context)
        val key =
            "instrumented_secret"
        val secret =
            "PRIME-device-secret"

        store.putString(
            key,
            secret
        )

        assertEquals(
            secret,
            store.getString(key)
        )

        val raw =
            context.getSharedPreferences(
                "prime_p6_secure",
                Context.MODE_PRIVATE
            ).getString(
                key,
                null
            ).orEmpty()

        assertTrue(raw.isNotBlank())
        assertFalse(
            raw.contains(secret)
        )

        store.remove(key)
        assertEquals(
            null,
            store.getString(key)
        )
    }
}
