package com.mobilemcp.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeMemoryTest {

    @Test
    fun parsesExplicitPersianRememberCommand() {
        val command = PrimeMemoryCommandParser.parse(
            "یادت باشه من پاسخ‌های کوتاه رو ترجیح میدم"
        )

        assertTrue(command is PrimeMemoryCommand.Remember)
        command as PrimeMemoryCommand.Remember
        assertEquals(
            PrimeMemoryKind.PREFERENCE,
            command.kind
        )
        assertTrue(command.content.contains("پاسخ‌های کوتاه"))
    }

    @Test
    fun parsesEnglishForgetAndPersianListCommands() {
        val forget = PrimeMemoryCommandParser.parse(
            "forget that my old project uses Java"
        )
        assertTrue(forget is PrimeMemoryCommand.Forget)
        assertTrue(
            (forget as PrimeMemoryCommand.Forget)
                .query
                .contains("old project")
        )

        assertTrue(
            PrimeMemoryCommandParser.parse(
                "چه چیزهایی از من یادت هست"
            ) is PrimeMemoryCommand.ListMemories
        )
    }

    @Test
    fun normalConversationDoesNotBecomeMemoryCommand() {
        assertEquals(
            null,
            PrimeMemoryCommandParser.parse(
                "امروز درباره پروژه صحبت کنیم"
            )
        )
    }

    @Test
    fun rankerPrefersRelevantPreferenceOverUnrelatedRecentFact() {
        val now = 1_000_000_000L
        val items = listOf(
            PrimeMemoryItem(
                id = 1,
                kind = PrimeMemoryKind.PREFERENCE,
                content = "من پاسخ های کوتاه را ترجیح می دهم",
                normalized = PrimeMemoryText.normalize(
                    "من پاسخ های کوتاه را ترجیح می دهم"
                ),
                createdAt = now - 1000,
                updatedAt = now - 1000
            ),
            PrimeMemoryItem(
                id = 2,
                kind = PrimeMemoryKind.FACT,
                content = "ماشین من سفید است",
                normalized = PrimeMemoryText.normalize(
                    "ماشین من سفید است"
                ),
                createdAt = now,
                updatedAt = now
            )
        )

        val ranked = PrimeMemoryRanker.rank(
            items = items,
            query = "جوابت کوتاه باشه",
            limit = 2,
            now = now
        )

        assertEquals(1L, ranked.first().id)
    }

    @Test
    fun jsonCodecRoundTripsMemoryMetadata() {
        val original = listOf(
            PrimeMemoryItem(
                id = 9,
                kind = PrimeMemoryKind.PROJECT,
                content = "پروژه من PRIME است",
                normalized = PrimeMemoryText.normalize(
                    "پروژه من PRIME است"
                ),
                createdAt = 10,
                updatedAt = 20,
                sourceChatId = 7
            )
        )

        val decoded = PrimeMemoryJsonCodec.decode(
            PrimeMemoryJsonCodec.encode(original)
        )

        assertEquals(original, decoded)
    }

    @Test
    fun normalizerUnifiesArabicAndPersianCharacters() {
        assertEquals(
            PrimeMemoryText.normalize("كاربر يكي"),
            PrimeMemoryText.normalize("کاربر یکی")
        )
        assertFalse(
            PrimeMemoryText.tokens("من و تو").contains("من")
        )
    }
}
