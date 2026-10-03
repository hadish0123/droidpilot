package com.mobilemcp.pro

import android.content.Context

/**
 * User-controlled long-term memory encrypted through Android Keystore.
 *
 * Memory is intentionally explicit: PRIME stores items only when the user asks
 * it to remember something. Retrieval is local and no memory database is sent
 * anywhere wholesale; only a small relevant subset is added to model context.
 */
internal class PrimeMemoryStore(
    context: Context,
    private val secureStore: SecureStore = SecureStore(
        context.applicationContext
    )
) : PrimeMemorySource {

    companion object {
        private const val STORE_KEY = "prime_long_term_memory_v1"
        private const val MAX_ITEMS = 300
        private const val MAX_ITEM_CHARS = 2_000
    }

    @Synchronized
    fun remember(
        kind: PrimeMemoryKind,
        content: String,
        sourceChatId: Long? = null
    ): PrimeMemoryItem {
        val clean = content
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_ITEM_CHARS)

        require(clean.isNotBlank()) { "Memory content cannot be blank" }

        val now = System.currentTimeMillis()
        val normalized = PrimeMemoryText.normalize(clean)
        val items = readAll().toMutableList()

        val existingIndex = items.indexOfFirst {
            it.normalized == normalized ||
                (
                    it.kind == kind &&
                        PrimeMemoryRanker
                            .isNearDuplicate(
                                it.normalized,
                                normalized
                            )
                )
        }

        val item = if (existingIndex >= 0) {
            items[existingIndex].copy(
                kind = kind,
                content = clean,
                normalized = normalized,
                updatedAt = now,
                sourceChatId = sourceChatId
                    ?: items[existingIndex].sourceChatId
            ).also {
                items[existingIndex] = it
            }
        } else {
            PrimeMemoryItem(
                id = (items.maxOfOrNull { it.id } ?: 0L) + 1L,
                kind = kind,
                content = clean,
                normalized = normalized,
                createdAt = now,
                updatedAt = now,
                sourceChatId = sourceChatId
            ).also {
                items += it
            }
        }

        val retained = items
            .sortedByDescending { it.updatedAt }
            .take(MAX_ITEMS)
            .sortedBy { it.id }

        writeAll(retained)
        return item
    }

    override fun relevant(
        query: String,
        limit: Int
    ): List<PrimeMemoryItem> =
        PrimeMemoryRanker.rank(
            items = readAll(),
            query = query,
            limit = limit.coerceIn(0, 8)
        )

    @Synchronized
    fun list(limit: Int = 50): List<PrimeMemoryItem> =
        readAll()
            .sortedByDescending { it.updatedAt }
            .take(limit.coerceIn(0, MAX_ITEMS))

    @Synchronized
    fun forgetMatching(query: String): Int {
        val clean = PrimeMemoryText.normalize(query)
        if (clean.isBlank()) return 0

        val items = readAll()
        val rankedIds = PrimeMemoryRanker
            .rankScored(
                items = items,
                query = clean,
                limit = items.size
            )
            .filter {
                it.score >= 7.0
            }
            .map {
                it.item.id
            }
            .toSet()

        val retained = items.filterNot { item ->
            item.normalized.contains(clean) ||
                clean.contains(item.normalized) ||
                item.id in rankedIds
        }

        val removed = items.size - retained.size
        if (removed > 0) writeAll(retained)
        return removed
    }

    @Synchronized
    fun clear() {
        secureStore.remove(STORE_KEY)
    }

    private fun readAll(): List<PrimeMemoryItem> =
        PrimeMemoryJsonCodec.decode(
            secureStore.getString(STORE_KEY)
        )

    private fun writeAll(items: List<PrimeMemoryItem>) {
        secureStore.putString(
            STORE_KEY,
            PrimeMemoryJsonCodec.encode(items)
        )
    }
}
