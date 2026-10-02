package com.mobilemcp.pro.voice

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class SafeVoiceArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun archive(name: String, link: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { tar ->
            val entry = if (link) TarArchiveEntry(name, '2'.code.toByte()).apply { linkName = "../../outside" }
            else TarArchiveEntry(name, true).apply { size = 2 }
            tar.putArchiveEntry(entry)
            if (!link) tar.write(byteArrayOf(65, 66))
            tar.closeArchiveEntry()
        }
        return bytes.toByteArray()
    }
    @Test fun extractsRegularNestedFiles() {
        val root = temp.newFolder()
        SafeVoiceArchive.extract(archive("voice/tokens.txt").inputStream(), root)
        assertEquals("AB", File(root, "voice/tokens.txt").readText())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTraversal() {
        SafeVoiceArchive.extract(archive("../outside").inputStream(), temp.newFolder())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsLinks() {
        SafeVoiceArchive.extract(archive("voice/link", true).inputStream(), temp.newFolder())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsAbsolutePaths() {
        SafeVoiceArchive.extract(archive("/outside").inputStream(), temp.newFolder())
    }
}
