package com.mobilemcp.pro

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mobilemcp.pro.chat.PrimeChatSchema

data class PrimeChatSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false
)

data class PrimeStoredMessage(
    val id: Long,
    val chatId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val status: String = "complete",
    val parentMessageId: Long? = null,
    val metadataJson: String? = null
)

class PrimeChatStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    PrimeChatSchema.VERSION
) {
    companion object {
        private const val DATABASE_NAME = "prime_chats.db"
        private const val TABLE_CHATS = "chats"
        private const val TABLE_MESSAGES = "messages"
        private const val TABLE_ATTACHMENTS = "attachments"
        private const val TABLE_TOOL_CALLS = "tool_calls"

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
                updated_at INTEGER NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0
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
                status TEXT NOT NULL DEFAULT 'complete',
                parent_message_id INTEGER,
                metadata_json TEXT,
                FOREIGN KEY(chat_id) REFERENCES $TABLE_CHATS(id) ON DELETE CASCADE,
                FOREIGN KEY(parent_message_id) REFERENCES $TABLE_MESSAGES(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_ATTACHMENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL,
                message_id INTEGER,
                kind TEXT NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT,
                storage_uri TEXT NOT NULL,
                size_bytes INTEGER,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(chat_id) REFERENCES $TABLE_CHATS(id) ON DELETE CASCADE,
                FOREIGN KEY(message_id) REFERENCES $TABLE_MESSAGES(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_TOOL_CALLS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL,
                message_id INTEGER,
                call_id TEXT,
                tool_name TEXT NOT NULL,
                risk TEXT NOT NULL,
                status TEXT NOT NULL,
                arguments_json TEXT NOT NULL,
                result_json TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(chat_id) REFERENCES $TABLE_CHATS(id) ON DELETE CASCADE,
                FOREIGN KEY(message_id) REFERENCES $TABLE_MESSAGES(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        createIndexes(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2 && newVersion >= 2) {
            PrimeChatSchema.migration1To2.forEach(db::execSQL)
        }
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX idx_prime_messages_chat ON $TABLE_MESSAGES(chat_id, id)"
        )
        db.execSQL(
            "CREATE INDEX idx_prime_chats_updated ON $TABLE_CHATS(updated_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX idx_prime_chats_pinned_updated ON $TABLE_CHATS(pinned DESC, archived ASC, updated_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX idx_prime_messages_parent ON $TABLE_MESSAGES(parent_message_id)"
        )
        db.execSQL(
            "CREATE INDEX idx_prime_attachments_chat ON $TABLE_ATTACHMENTS(chat_id, id)"
        )
        db.execSQL(
            "CREATE INDEX idx_prime_attachments_message ON $TABLE_ATTACHMENTS(message_id, id)"
        )
        db.execSQL(
            "CREATE INDEX idx_prime_tool_calls_chat ON $TABLE_TOOL_CALLS(chat_id, id)"
        )
        db.execSQL(
            "CREATE INDEX idx_prime_tool_calls_message ON $TABLE_TOOL_CALLS(message_id, id)"
        )
    }

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
    fun chat(chatId: Long): PrimeChatSummary? {
        readableDatabase.query(
            TABLE_CHATS,
            arrayOf("id", "title", "updated_at", "pinned", "archived"),
            "id=?",
            arrayOf(chatId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                chatSummary(cursor)
            } else {
                null
            }
        }
    }

    @Synchronized
    fun listChats(includeArchived: Boolean = false): List<PrimeChatSummary> {
        val result = mutableListOf<PrimeChatSummary>()
        readableDatabase.query(
            TABLE_CHATS,
            arrayOf("id", "title", "updated_at", "pinned", "archived"),
            if (includeArchived) null else "archived=0",
            null,
            null,
            null,
            "pinned DESC, updated_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += chatSummary(cursor)
            }
        }
        return result
    }

    @Synchronized
    fun archivedChats(): List<PrimeChatSummary> =
        listChats(includeArchived = true)
            .filter { it.isArchived }

    private fun chatSummary(cursor: Cursor): PrimeChatSummary =
        PrimeChatSummary(
            id = cursor.getLong(0),
            title = cursor.getString(1),
            updatedAt = cursor.getLong(2),
            isPinned = cursor.getInt(3) != 0,
            isArchived = cursor.getInt(4) != 0
        )

    @Synchronized
    fun messages(chatId: Long): List<PrimeStoredMessage> {
        val result = mutableListOf<PrimeStoredMessage>()
        readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(
                "id",
                "chat_id",
                "role",
                "content",
                "created_at",
                "status",
                "parent_message_id",
                "metadata_json"
            ),
            "chat_id=?",
            arrayOf(chatId.toString()),
            null,
            null,
            "id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += storedMessage(cursor)
            }
        }
        return result
    }

    @Synchronized
    fun searchMessages(
        query: String,
        limit: Int = 50
    ): List<PrimeStoredMessage> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()

        val boundedLimit = limit.coerceIn(1, 200)

        val result = mutableListOf<PrimeStoredMessage>()
        readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(
                "id",
                "chat_id",
                "role",
                "content",
                "created_at",
                "status",
                "parent_message_id",
                "metadata_json"
            ),
            "instr(lower(content), lower(?)) > 0",
            arrayOf(clean),
            null,
            null,
            "created_at DESC, id DESC",
            boundedLimit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += storedMessage(cursor)
            }
        }
        return result
    }

    private fun storedMessage(cursor: Cursor): PrimeStoredMessage =
        PrimeStoredMessage(
            id = cursor.getLong(0),
            chatId = cursor.getLong(1),
            role = cursor.getString(2),
            content = cursor.getString(3),
            createdAt = cursor.getLong(4),
            status = cursor.getString(5),
            parentMessageId =
                if (cursor.isNull(6)) null else cursor.getLong(6),
            metadataJson =
                if (cursor.isNull(7)) null else cursor.getString(7)
        )

    @Synchronized
    fun appendMessage(
        chatId: Long,
        role: String,
        content: String,
        status: String = "complete",
        parentMessageId: Long? = null,
        metadataJson: String? = null
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
                put("status", status)
                if (parentMessageId == null) {
                    putNull("parent_message_id")
                } else {
                    put("parent_message_id", parentMessageId)
                }
                if (metadataJson == null) {
                    putNull("metadata_json")
                } else {
                    put("metadata_json", metadataJson)
                }
            }

            val messageId = db.insertOrThrow(
                TABLE_MESSAGES,
                null,
                values
            )

            db.update(
                TABLE_CHATS,
                ContentValues().apply {
                    put("updated_at", now)
                },
                "id=?",
                arrayOf(chatId.toString())
            )

            if (role == "user") {
                updateAutomaticTitle(
                    db,
                    chatId,
                    content.trim(),
                    now
                )
            }

            db.setTransactionSuccessful()
            return messageId
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun message(messageId: Long): PrimeStoredMessage? {
        readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(
                "id", "chat_id", "role", "content",
                "created_at", "status", "parent_message_id", "metadata_json"
            ),
            "id=?",
            arrayOf(messageId.toString()),
            null, null, null, "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) storedMessage(cursor) else null
        }
    }

    /**
     * Creates a new conversation containing every visible turn before [messageId].
     * The source conversation is never mutated, so edit/regenerate operations are
     * durable branches instead of destructive rewrites.
     */
    @Synchronized
    fun forkBeforeMessage(
        messageId: Long,
        titleSuffix: String = " · branch"
    ): Long {
        val source = message(messageId)
            ?: throw IllegalArgumentException("Unknown message: $messageId")
        val sourceChat = chat(source.chatId)
            ?: throw IllegalArgumentException("Unknown chat: ${source.chatId}")
        val sourceMessages = messages(source.chatId)
            .filter { it.id < messageId }

        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val title = (sourceChat.title + titleSuffix).take(80)
            val newChatId = db.insertOrThrow(
                TABLE_CHATS,
                null,
                ContentValues().apply {
                    put("title", title)
                    put("created_at", now)
                    put("updated_at", now)
                    put("pinned", 0)
                    put("archived", 0)
                }
            )

            var previousNewId: Long? = null
            sourceMessages.forEach { old ->
                val newId = db.insertOrThrow(
                    TABLE_MESSAGES,
                    null,
                    ContentValues().apply {
                        put("chat_id", newChatId)
                        put("role", old.role)
                        put("content", old.content)
                        put("created_at", old.createdAt)
                        put("status", old.status)
                        if (previousNewId == null) putNull("parent_message_id")
                        else put("parent_message_id", previousNewId)
                        if (old.metadataJson == null) putNull("metadata_json")
                        else put("metadata_json", old.metadataJson)
                    }
                )
                previousNewId = newId
            }

            db.setTransactionSuccessful()
            return newChatId
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateMessageStatus(
        messageId: Long,
        status: String,
        metadataJson: String? = null
    ) {
        val values = ContentValues().apply {
            put("status", status)
            if (metadataJson != null) {
                put("metadata_json", metadataJson)
            }
        }
        writableDatabase.update(
            TABLE_MESSAGES,
            values,
            "id=?",
            arrayOf(messageId.toString())
        )
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
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        } ?: return

        if (current != "New chat") return

        val clean = firstUserText
            .replace('\n', ' ')
            .replace(
                Regex("""\s+"""),
                " "
            )
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
        val clean = title
            .trim()
            .ifBlank { "New chat" }
            .take(80)
        writableDatabase.update(
            TABLE_CHATS,
            ContentValues().apply {
                put("title", clean)
                put(
                    "updated_at",
                    System.currentTimeMillis()
                )
            },
            "id=?",
            arrayOf(chatId.toString())
        )
    }

    @Synchronized
    fun setPinned(
        chatId: Long,
        pinned: Boolean
    ) {
        writableDatabase.update(
            TABLE_CHATS,
            ContentValues().apply {
                put(
                    "pinned",
                    if (pinned) 1 else 0
                )
            },
            "id=?",
            arrayOf(chatId.toString())
        )
    }

    @Synchronized
    fun setArchived(
        chatId: Long,
        archived: Boolean
    ) {
        writableDatabase.update(
            TABLE_CHATS,
            ContentValues().apply {
                put(
                    "archived",
                    if (archived) 1 else 0
                )
                put(
                    "updated_at",
                    System.currentTimeMillis()
                )
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
        writableDatabase.delete(
            TABLE_TOOL_CALLS,
            null,
            null
        )
        writableDatabase.delete(
            TABLE_ATTACHMENTS,
            null,
            null
        )
        writableDatabase.delete(
            TABLE_MESSAGES,
            null,
            null
        )
        writableDatabase.delete(
            TABLE_CHATS,
            null,
            null
        )
    }
}
