package com.mobilemcp.pro

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

internal enum class PrimeMemoryKind {
    FACT,
    PREFERENCE,
    PROJECT
}

internal data class PrimeMemoryItem(
    val id: Long,
    val kind: PrimeMemoryKind,
    val content: String,
    val normalized: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceChatId: Long? = null
)

internal interface PrimeMemorySource {
    fun relevant(query: String, limit: Int = 6): List<PrimeMemoryItem>
}

internal object EmptyPrimeMemorySource : PrimeMemorySource {
    override fun relevant(
        query: String,
        limit: Int
    ): List<PrimeMemoryItem> = emptyList()
}

internal sealed class PrimeMemoryCommand {
    data class Remember(
        val content: String,
        val kind: PrimeMemoryKind
    ) : PrimeMemoryCommand()

    data class Forget(
        val query: String
    ) : PrimeMemoryCommand()

    data object ListMemories : PrimeMemoryCommand()
}

internal object PrimeMemoryCommandParser {
    private val rememberPrefixes = listOf(
        "یادت باشه",
        "یادت بمونه",
        "به خاطر بسپار",
        "به خاطر داشته باش",
        "این رو یادت نگه دار",
        "این را یادت نگه دار",
        "remember that",
        "remember:"
    )

    private val forgetPrefixes = listOf(
        "فراموش کن که",
        "فراموش کن",
        "یادت نباشه که",
        "یادت نباشه",
        "forget that",
        "forget:"
    )

    private val listPhrases = setOf(
        "چه چیزهایی از من یادت هست",
        "چی از من یادت هست",
        "حافظه ات رو نشون بده",
        "حافظه‌ات رو نشون بده",
        "حافظه ات را نشان بده",
        "show my memories",
        "what do you remember about me"
    )

    fun parse(input: String): PrimeMemoryCommand? {
        val clean = input.trim()
        if (clean.isBlank()) return null

        val normalized = PrimeMemoryText.normalize(clean)

        if (listPhrases.any { PrimeMemoryText.normalize(it) == normalized }) {
            return PrimeMemoryCommand.ListMemories
        }

        rememberPrefixes.firstOrNull { prefix ->
            normalized.startsWith(PrimeMemoryText.normalize(prefix))
        }?.let { prefix ->
            val content = removePrefixFlexible(clean, prefix)
            if (content.isNotBlank()) {
                return PrimeMemoryCommand.Remember(
                    content = content,
                    kind = inferKind(content)
                )
            }
        }

        forgetPrefixes.firstOrNull { prefix ->
            normalized.startsWith(PrimeMemoryText.normalize(prefix))
        }?.let { prefix ->
            val query = removePrefixFlexible(clean, prefix)
            if (query.isNotBlank()) {
                return PrimeMemoryCommand.Forget(query)
            }
        }

        return null
    }

    private fun removePrefixFlexible(
        original: String,
        prefix: String
    ): String {
        val lower = original.lowercase(Locale.ROOT)
        val prefixLower = prefix.lowercase(Locale.ROOT)
        val direct = lower.indexOf(prefixLower)
        if (direct == 0) {
            return original
                .drop(prefix.length)
                .trim()
                .trimStart(':', '：', '،', ',', '-', '–')
                .trim()
        }

        // Persian normalization can alter Arabic/Persian ی/ک and ZWNJ.
        // Fall back to token count instead of losing the user's memory text.
        val prefixTokens = PrimeMemoryText.tokens(prefix).size
        val words = original.trim().split(Regex("\\s+"))
        return words.drop(prefixTokens)
            .joinToString(" ")
            .trim()
            .trimStart(':', '：', '،', ',', '-', '–')
            .trim()
    }

    private fun inferKind(content: String): PrimeMemoryKind {
        val normalized = PrimeMemoryText.normalize(content)
        return when {
            listOf(
                "ترجیح",
                "دوست دارم",
                "دوست ندارم",
                "prefer",
                "preference",
                "favorite",
                "favourite"
            ).any { normalized.contains(PrimeMemoryText.normalize(it)) } ->
                PrimeMemoryKind.PREFERENCE

            listOf(
                "پروژه",
                "ریپو",
                "repository",
                "project",
                "برنامه ای که می سازم",
                "اپی که می سازم"
            ).any { normalized.contains(PrimeMemoryText.normalize(it)) } ->
                PrimeMemoryKind.PROJECT

            else -> PrimeMemoryKind.FACT
        }
    }
}

internal object PrimeMemoryText {
    private val punctuation = Regex("[^\\p{L}\\p{N}_]+")
    private val stopWords = setOf(
        "که", "را", "رو", "من", "تو", "این", "اون", "آن",
        "و", "در", "به", "از", "برای", "با", "یک",
        "the", "a", "an", "and", "or", "to", "of", "in", "my", "i"
    )

    fun normalize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ك', 'ک')
            .replace('\u200c', ' ')
            .replace(punctuation, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun tokens(value: String): Set<String> =
        normalize(value)
            .split(' ')
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stopWords }
            .toSet()
}

internal object PrimeMemoryRanker {
    fun rank(
        items: List<PrimeMemoryItem>,
        query: String,
        limit: Int,
        now: Long = System.currentTimeMillis()
    ): List<PrimeMemoryItem> {
        if (limit <= 0 || items.isEmpty()) return emptyList()

        val queryNormalized = PrimeMemoryText.normalize(query)
        val queryTokens = PrimeMemoryText.tokens(query)
        if (queryNormalized.isBlank()) {
            return items
                .sortedByDescending { it.updatedAt }
                .take(limit)
        }

        return items
            .map { item ->
                item to score(
                    item = item,
                    queryNormalized = queryNormalized,
                    queryTokens = queryTokens,
                    now = now
                )
            }
            .filter { (_, score) -> score > 0.0 }
            .sortedWith(
                compareByDescending<Pair<PrimeMemoryItem, Double>> { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .take(limit)
            .map { it.first }
    }

    private fun score(
        item: PrimeMemoryItem,
        queryNormalized: String,
        queryTokens: Set<String>,
        now: Long
    ): Double {
        val memoryNormalized = item.normalized
        val memoryTokens = PrimeMemoryText.tokens(memoryNormalized)

        var score = 0.0

        if (
            memoryNormalized.contains(queryNormalized) ||
            queryNormalized.contains(memoryNormalized)
        ) {
            score += 18.0
        }

        val overlap = queryTokens.intersect(memoryTokens).size
        score += overlap * 4.0

        if (queryTokens.isNotEmpty()) {
            score += 6.0 * overlap.toDouble() / queryTokens.size.toDouble()
        }

        score += when (item.kind) {
            PrimeMemoryKind.PREFERENCE -> 1.5
            PrimeMemoryKind.PROJECT -> 1.0
            PrimeMemoryKind.FACT -> 0.5
        }

        val ageDays = max(
            0L,
            (now - item.updatedAt) / (24L * 60L * 60L * 1000L)
        )
        score += when {
            ageDays <= 7 -> 1.5
            ageDays <= 30 -> 0.8
            ageDays <= 180 -> 0.3
            else -> 0.0
        }

        return score
    }
}

internal object PrimeMemoryJsonCodec {
    fun encode(items: List<PrimeMemoryItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("kind", item.kind.name)
                    .put("content", item.content)
                    .put("normalized", item.normalized)
                    .put("createdAt", item.createdAt)
                    .put("updatedAt", item.updatedAt)
                    .put(
                        "sourceChatId",
                        item.sourceChatId ?: JSONObject.NULL
                    )
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<PrimeMemoryItem> {
        if (raw.isNullOrBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val content = json.optString("content").trim()
                    if (content.isBlank()) continue

                    val kind = runCatching {
                        PrimeMemoryKind.valueOf(
                            json.optString("kind", PrimeMemoryKind.FACT.name)
                        )
                    }.getOrDefault(PrimeMemoryKind.FACT)

                    val source = if (json.isNull("sourceChatId")) {
                        null
                    } else {
                        json.optLong("sourceChatId")
                            .takeIf { it > 0L }
                    }

                    add(
                        PrimeMemoryItem(
                            id = json.optLong("id"),
                            kind = kind,
                            content = content,
                            normalized = json
                                .optString("normalized")
                                .ifBlank {
                                    PrimeMemoryText.normalize(content)
                                },
                            createdAt = json.optLong("createdAt"),
                            updatedAt = json.optLong("updatedAt"),
                            sourceChatId = source
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
