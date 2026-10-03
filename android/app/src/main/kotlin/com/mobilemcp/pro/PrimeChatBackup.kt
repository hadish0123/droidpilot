package com.mobilemcp.pro

import org.json.JSONArray
import org.json.JSONObject

internal data class PrimeBackupMessage(
    val role: String,
    val content: String,
    val status: String
)

internal data class PrimeBackupChat(
    val title: String,
    val pinned: Boolean,
    val archived: Boolean,
    val messages: List<PrimeBackupMessage>
)

internal data class PrimeChatBackup(
    val version: Int,
    val exportedAt: Long,
    val chats: List<PrimeBackupChat>
)

internal object PrimeChatBackupCodec {
    const val VERSION = 1
    const val MAX_JSON_CHARS =
        8 * 1024 * 1024
    private const val MAX_CHATS = 200
    private const val MAX_MESSAGES = 10_000
    private const val MAX_MESSAGE_CHARS = 100_000

    fun capture(
        store: PrimeChatStore
    ): PrimeChatBackup {
        val chats = store
            .listChats(
                includeArchived = true
            )
            .take(MAX_CHATS)
            .map { chat ->
                PrimeBackupChat(
                    title =
                        chat.title.take(80),
                    pinned =
                        chat.isPinned,
                    archived =
                        chat.isArchived,
                    messages =
                        store.messages(
                            chat.id
                        )
                            .take(MAX_MESSAGES)
                            .mapNotNull {
                                message ->
                                val role =
                                    message.role
                                        .takeIf {
                                            it == "user" ||
                                                it == "assistant"
                                        }
                                        ?: return@mapNotNull null
                                PrimeBackupMessage(
                                    role = role,
                                    content =
                                        message.content
                                            .take(
                                                MAX_MESSAGE_CHARS
                                            ),
                                    status =
                                        message.status
                                            .take(40)
                                            .ifBlank {
                                                "complete"
                                            }
                                )
                            }
                )
            }

        return PrimeChatBackup(
            version = VERSION,
            exportedAt =
                System.currentTimeMillis(),
            chats = chats
        )
    }

    fun encode(
        backup: PrimeChatBackup
    ): String {
        require(
            backup.version == VERSION
        ) {
            "Unsupported backup version"
        }

        val root = JSONObject()
            .put(
                "format",
                "prime.chat.backup"
            )
            .put(
                "version",
                backup.version
            )
            .put(
                "exportedAt",
                backup.exportedAt
            )
        val chats = JSONArray()

        backup.chats
            .take(MAX_CHATS)
            .forEach { chat ->
                val messages =
                    JSONArray()
                chat.messages
                    .take(MAX_MESSAGES)
                    .forEach {
                        message ->
                        if (
                            message.role != "user" &&
                            message.role != "assistant"
                        ) {
                            return@forEach
                        }
                        messages.put(
                            JSONObject()
                                .put(
                                    "role",
                                    message.role
                                )
                                .put(
                                    "content",
                                    message.content
                                        .take(
                                            MAX_MESSAGE_CHARS
                                        )
                                )
                                .put(
                                    "status",
                                    message.status
                                        .take(40)
                                )
                        )
                    }

                chats.put(
                    JSONObject()
                        .put(
                            "title",
                            chat.title.take(80)
                        )
                        .put(
                            "pinned",
                            chat.pinned
                        )
                        .put(
                            "archived",
                            chat.archived
                        )
                        .put(
                            "messages",
                            messages
                        )
                )
            }

        root.put(
            "chats",
            chats
        )
        return root.toString()
    }

    fun decode(
        raw: String
    ): PrimeChatBackup {
        require(
            raw.length <=
                MAX_JSON_CHARS
        ) {
            "Backup exceeds PRIME size limit"
        }

        val root = JSONObject(raw)
        require(
            root.optString(
                "format"
            ) == "prime.chat.backup"
        ) {
            "Not a PRIME chat backup"
        }

        val version =
            root.optInt(
                "version",
                -1
            )
        require(version == VERSION) {
            "Unsupported backup version: $version"
        }

        val sourceChats =
            root.optJSONArray(
                "chats"
            ) ?: JSONArray()
        require(
            sourceChats.length() <=
                MAX_CHATS
        ) {
            "Backup contains too many chats"
        }

        var messageCount = 0
        val chats = buildList {
            for (
                chatIndex in 0
                    until sourceChats.length()
            ) {
                val chatJson =
                    sourceChats
                        .optJSONObject(
                            chatIndex
                        )
                        ?: continue
                val sourceMessages =
                    chatJson
                        .optJSONArray(
                            "messages"
                        )
                        ?: JSONArray()

                require(
                    sourceMessages.length() <=
                        MAX_MESSAGES
                ) {
                    "A backup chat contains too many messages"
                }

                val messages = buildList {
                    for (
                        messageIndex in 0
                            until sourceMessages
                                .length()
                    ) {
                        val json =
                            sourceMessages
                                .optJSONObject(
                                    messageIndex
                                )
                                ?: continue
                        val role =
                            json.optString(
                                "role"
                            )
                                .trim()
                        if (
                            role != "user" &&
                            role != "assistant"
                        ) {
                            continue
                        }

                        val content =
                            json.optString(
                                "content"
                            )
                        require(
                            content.length <=
                                MAX_MESSAGE_CHARS
                        ) {
                            "Backup message exceeds PRIME size limit"
                        }

                        messageCount += 1
                        require(
                            messageCount <=
                                MAX_MESSAGES
                        ) {
                            "Backup contains too many messages"
                        }

                        add(
                            PrimeBackupMessage(
                                role = role,
                                content =
                                    content,
                                status =
                                    json.optString(
                                        "status",
                                        "complete"
                                    )
                                        .take(40)
                                        .ifBlank {
                                            "complete"
                                        }
                            )
                        )
                    }
                }

                add(
                    PrimeBackupChat(
                        title =
                            chatJson
                                .optString(
                                    "title",
                                    "Imported chat"
                                )
                                .trim()
                                .ifBlank {
                                    "Imported chat"
                                }
                                .take(80),
                        pinned =
                            chatJson
                                .optBoolean(
                                    "pinned",
                                    false
                                ),
                        archived =
                            chatJson
                                .optBoolean(
                                    "archived",
                                    false
                                ),
                        messages =
                            messages
                    )
                )
            }
        }

        return PrimeChatBackup(
            version = version,
            exportedAt =
                root.optLong(
                    "exportedAt",
                    0L
                ),
            chats = chats
        )
    }

    fun restore(
        store: PrimeChatStore,
        backup: PrimeChatBackup
    ): List<Long> {
        require(
            backup.version == VERSION
        ) {
            "Unsupported backup version"
        }

        return backup.chats.map {
            chat ->
            val chatId =
                store.createChat(
                    chat.title
                )
            chat.messages.forEach {
                message ->
                store.appendMessage(
                    chatId = chatId,
                    role = message.role,
                    content =
                        message.content,
                    status =
                        message.status
                )
            }
            if (chat.pinned) {
                store.setPinned(
                    chatId,
                    true
                )
            }
            if (chat.archived) {
                store.setArchived(
                    chatId,
                    true
                )
            }
            chatId
        }
    }
}
