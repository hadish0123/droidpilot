package com.mobilemcp.pro.chat

internal object PrimeChatSchema {
    const val VERSION = 2

    val migration1To2: List<String> = listOf(
        "ALTER TABLE chats ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE chats ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'complete'",
        "ALTER TABLE messages ADD COLUMN parent_message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL",
        "ALTER TABLE messages ADD COLUMN metadata_json TEXT",
        """
        CREATE TABLE attachments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            chat_id INTEGER NOT NULL,
            message_id INTEGER,
            kind TEXT NOT NULL,
            display_name TEXT NOT NULL,
            mime_type TEXT,
            storage_uri TEXT NOT NULL,
            size_bytes INTEGER,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE,
            FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE tool_calls (
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
            FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE,
            FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE SET NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_prime_chats_pinned_updated ON chats(pinned DESC, archived ASC, updated_at DESC)",
        "CREATE INDEX idx_prime_messages_parent ON messages(parent_message_id)",
        "CREATE INDEX idx_prime_attachments_chat ON attachments(chat_id, id)",
        "CREATE INDEX idx_prime_attachments_message ON attachments(message_id, id)",
        "CREATE INDEX idx_prime_tool_calls_chat ON tool_calls(chat_id, id)",
        "CREATE INDEX idx_prime_tool_calls_message ON tool_calls(message_id, id)"
    )
}
