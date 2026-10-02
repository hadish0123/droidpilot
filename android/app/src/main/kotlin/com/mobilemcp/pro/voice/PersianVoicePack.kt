package com.mobilemcp.pro.voice

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

object PersianVoicePack {
    const val DIRECTORY = "vits-piper-fa_IR-ganji-medium"
    const val MODEL = "fa_IR-ganji-medium.onnx"
    const val SHA256 = "6eae2acccd1b4460159fa5acdad4bb5d2df6d64d8da3e4029dbdff4db14e7a7a"
    private const val DOWNLOAD_BYTES = 67_186_157L
    private const val URL_PATH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$DIRECTORY.tar.bz2"
    private val installMutex = Mutex()

    fun directory(context: Context) = File(context.noBackupFilesDir, "prime-voices/$DIRECTORY")
    fun isInstalled(context: Context): Boolean = valid(directory(context))
    private fun valid(dir: File): Boolean =
        File(dir, ".verified").takeIf { it.isFile }?.readText() == SHA256 &&
            File(dir, MODEL).length() > 60_000_000 &&
            File(dir, "tokens.txt").length() > 0 &&
            File(dir, "espeak-ng-data/phontab").isFile &&
            File(dir, "espeak-ng-data/lang/ira/fa").isFile

    suspend fun ensureInstalled(context: Context, progress: (String) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            installMutex.withLock {
                val destination = directory(context)
                if (valid(destination)) return@withLock destination
                val parent = destination.parentFile!!
                parent.mkdirs()
                require(StatFs(parent.path).availableBytes >= 260_000_000L) {
                    "برای نصب صدای فارسی حداقل ۲۶۰ مگابایت فضای خالی لازم است."
                }
                val archive = File(parent, "persian.part")
                val staging = File(parent, "installing")
                staging.deleteRecursively()
                val workContext = coroutineContext
                try {
                    progress("دانلود صدای فارسی · ۰٪")
                    val conn = URL(URL_PATH).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty("Accept", "application/octet-stream")
                    val digest = MessageDigest.getInstance("SHA-256")
                    try {
                        require(conn.responseCode in 200..299) { "دانلود صدا ناموفق بود: HTTP ${conn.responseCode}" }
                        var received = 0L
                        var lastPercent = -1
                        conn.inputStream.use { input ->
                            archive.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    workContext.ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    received += n
                                    require(received <= DOWNLOAD_BYTES) { "Voice download exceeds expected size" }
                                    digest.update(buffer, 0, n)
                                    output.write(buffer, 0, n)
                                    val percent = (received * 100 / DOWNLOAD_BYTES).toInt()
                                    if (percent != lastPercent) {
                                        progress("دانلود صدای فارسی · $percent٪ (۶۴ مگابایت)")
                                        lastPercent = percent
                                    }
                                }
                            }
                        }
                        require(received == DOWNLOAD_BYTES) { "دانلود صدا ناقص است؛ دوباره تلاش کن." }
                    } finally {
                        conn.disconnect()
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    require(hash == SHA256) { "فایل صدا معتبر نیست؛ دوباره دانلود کن." }
                    progress("نصب صدای فارسی…")
                    archive.inputStream().use { input ->
                        BZip2CompressorInputStream(input).use { compressed ->
                            SafeVoiceArchive.extract(compressed, staging) { workContext.ensureActive() }
                        }
                    }
                    workContext.ensureActive()
                    val extracted = File(staging, DIRECTORY)
                    File(extracted, ".verified").writeText(SHA256)
                    require(valid(extracted)) { "بستهٔ صدا کامل نیست." }
                    destination.deleteRecursively()
                    require(extracted.renameTo(destination)) { "نصب صدای فارسی کامل نشد." }
                    progress("صدای فارسی آماده است · آفلاین")
                    destination
                } finally {
                    archive.delete()
                    staging.deleteRecursively()
                }
            }
        }
}
