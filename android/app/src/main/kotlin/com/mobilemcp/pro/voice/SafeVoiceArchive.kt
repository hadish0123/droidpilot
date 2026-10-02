package com.mobilemcp.pro.voice

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.InputStream

/** Extract only regular files and directories, with strict path and size bounds. */
object SafeVoiceArchive {
    fun extract(input: InputStream, destination: File, checkCancelled: () -> Unit = {}) {
        destination.mkdirs()
        val root = destination.canonicalFile
        var expanded = 0L
        var entries = 0
        TarArchiveInputStream(input).use { tar ->
            while (true) {
                checkCancelled()
                val entry = tar.nextTarEntry ?: break
                require(++entries <= 2000) { "Voice archive has too many files" }
                require(!entry.isSymbolicLink && !entry.isLink) { "Links are not allowed in voice archives" }
                require(entry.isFile || entry.isDirectory) { "Unsupported voice archive entry" }
                require(!File(entry.name).isAbsolute && '\\' !in entry.name) { "Invalid voice archive path" }
                val target = File(root, entry.name).canonicalFile
                require(target.path.startsWith(root.path + File.separator)) { "Unsafe voice archive path" }
                require(entry.size in 0..120_000_000L) { "Voice archive file is too large" }
                expanded += entry.size
                require(expanded <= 160_000_000L) { "Voice archive is too large" }
                if (entry.isDirectory) {
                    require(target.isDirectory || target.mkdirs()) { "Cannot create voice directory" }
                } else {
                    val parent = requireNotNull(target.parentFile) { "Invalid voice directory" }
                    require(parent.isDirectory || parent.mkdirs()) { "Cannot create voice directory" }
                    target.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var written = 0L
                        while (true) {
                            checkCancelled()
                            val n = tar.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            written += n
                        }
                        require(written == entry.size) { "Truncated voice archive" }
                    }
                }
            }
        }
    }
}
