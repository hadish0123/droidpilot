package com.mobilemcp.pro

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PrimeChatSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long
)

data class PrimeStoredMessage(
    val id: Long,
    val chatId: Long,
    val role: String,
    val content: String,
    val createdAt: Long
)

/**
 * Local PRIME conversation memory.
 *
 * Chats stay on the device and survive app restarts/updates. OAuth tokens are
 * still handled separately by SecureStore; this database stores only chat
 * titles and message text.
 */
class PrimeChatStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "prime_chats.db",
    null,
    1
) {

    companion object {
        private const val TABLE_CHATS = "chats"
        private const val TABLE_MESSAGES = "messages"

        const val PREFS = "prime_chat_state"
        const val ACTIVE_CHAT_ID = "active_chat_id"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CHATS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(chat_id) REFERENCES $TABLE_CHATS(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX idx_prime_messages_chat ON $TABLE_MESSAGES(chat_id, id)"
        )
        db.execSQL(
            "CREATE INDEX idx_prime_chats_updated ON $TABLE_CHATS(updated_at DESC)"
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) = Unit

    @Synchronized
    fun createChat(title: String = "New chat"): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", title)
            put("created_at", now)
            put("updated_at", now)
        }
        return writableDatabase.insertOrThrow(TABLE_CHATS, null, values)
    }

    @Synchronized
    fun chatExists(chatId: Long): Boolean {
        readableDatabase.query(
            TABLE_CHATS,
            arrayOf("id"),
            "id=?",
            arrayOf(chatId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    @Synchronized
    fun listChats(): List<PrimeChatSummary> {
        val result = mutableListOf<PrimeChatSummary>()
        readableDatabase.query(
            TABLE_CHATS,
            arrayOf("id", "title", "updated_at"),
            null,
            null,
            null,
            null,
            "updated_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PrimeChatSummary(
                    id = cursor.getLong(0),
                    title = cursor.getString(1),
                    updatedAt = cursor.getLong(2)
                )
            }
        }
        return result
    }

    @Synchronized
    fun messages(chatId: Long): List<PrimeStoredMessage> {
        val result = mutableListOf<PrimeStoredMessage>()
        readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf("id", "chat_id", "role", "content", "created_at"),
            "chat_id=?",
            arrayOf(chatId.toString()),
            null,
            null,
            "id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PrimeStoredMessage(
                    id = cursor.getLong(0),
                    chatId = cursor.getLong(1),
                    role = cursor.getString(2),
                    content = cursor.getString(3),
                    createdAt = cursor.getLong(4)
                )
            }
        }
        return result
    }

    @Synchronized
    fun appendMessage(
        chatId: Long,
        role: String,
        content: String
    ): Long {
        if (content.isBlank()) return -1L

        val now = System.currentTimeMillis()
        val db = writableDatabase

        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("chat_id", chatId)
                put("role", role)
                put("content", content.trim())
                put("created_at", now)
            }

            val messageId = db.insertOrThrow(
                TABLE_MESSAGES,
                null,
                values
            )

            db.update(
                TABLE_CHATS,
                ContentValues().apply { put("updated_at", now) },
                "id=?",
                arrayOf(chatId.toString())
            )

            if (role == "user") {
                updateAutomaticTitle(db, chatId, content.trim(), now)
            }

            db.setTransactionSuccessful()
            return messageId
        } finally {
            db.endTransaction()
        }
    }

    private fun updateAutomaticTitle(
        db: SQLiteDatabase,
        chatId: Long,
        firstUserText: String,
        now: Long
    ) {
        val current = db.query(
            TABLE_CHATS,
            arrayOf("title"),
            "id=?",
            arrayOf(chatId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return

        if (current != "New chat") return

        val clean = firstUserText
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val title = when {
            clean.isBlank() -> "New chat"
            clean.length <= 42 -> clean
            else -> clean.take(42).trimEnd() + "…"
        }

        db.update(
            TABLE_CHATS,
            ContentValues().apply {
                put("title", title)
                put("updated_at", now)
            },
            "id=?",
            arrayOf(chatId.toString())
        )
    }

    @Synchronized
    fun renameChat(chatId: Long, title: String) {
        val clean = title.trim().ifBlank { "New chat" }.take(80)
        writableDatabase.update(
            TABLE_CHATS,
            ContentValues().apply {
                put("title", clean)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(chatId.toString())
        )
    }

    @Synchronized
    fun deleteChat(chatId: Long) {
        writableDatabase.delete(
            TABLE_CHATS,
            "id=?",
            arrayOf(chatId.toString())
        )
    }

    @Synchronized
    fun deleteAllChats() {
        writableDatabase.delete(TABLE_MESSAGES, null, null)
        writableDatabase.delete(TABLE_CHATS, null, null)
    }
}
