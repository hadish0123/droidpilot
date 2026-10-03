package com.mobilemcp.pro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrimeChatBranchingTest {
    @Test
    fun branching_is_non_destructive_by_design() {
        val source = File(
            "src/main/kotlin/com/mobilemcp/pro/PrimeChatStore.kt"
        ).readText()

        assertTrue(source.contains("fun forkBeforeMessage("))
        assertTrue(source.contains("fun forkThroughMessage("))
        assertTrue(source.contains(".filter { it.id < messageId }"))
        assertTrue(source.contains(".filter { it.id <= messageId }"))
        assertFalse(
            "Branching must not delete source messages",
            source.substringAfter("fun forkBeforeMessage(")
                .substringBefore("fun updateMessageStatus(")
                .contains("delete(")
        )
    }

    @Test
    fun edit_and_regenerate_use_forks_not_history_deletion() {
        val source = File(
            "src/main/kotlin/com/mobilemcp/pro/MainActivity.kt"
        ).readText()

        assertTrue(source.contains("titleSuffix = \" · edited\""))
        assertTrue(source.contains("titleSuffix = \" · regenerated\""))
        assertTrue(source.contains("persistUserMessage = false"))
        assertFalse(source.contains("deleteMessagesAfter("))
    }
}
