package com.mobilemcp.pro

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal class PrimeDocumentPipeline(
    context: Context
) {
    companion object {
        private const val MAX_FILE_BYTES = 24 * 1024 * 1024
        private const val MAX_ZIP_ENTRY_BYTES = 8 * 1024 * 1024
        private const val MAX_ZIP_TOTAL_BYTES = 40 * 1024 * 1024
        private const val MAX_EXTRACTED_CHARS = 600_000

        @Volatile
        private var pdfBoxInitialized = false

        private fun ensurePdfBox(context: Context) {
            if (pdfBoxInitialized) return
            synchronized(this) {
                if (!pdfBoxInitialized) {
                    PDFBoxResourceLoader.init(
                        context.applicationContext
                    )
                    pdfBoxInitialized = true
                }
            }
        }
    }

    private val appContext = context.applicationContext

    fun parse(uri: Uri): PrimeParsedDocument {
        val resolver = appContext.contentResolver
        val name = displayName(uri)
            ?: uri.lastPathSegment
            ?: "attachment"
        val mimeType = resolver.getType(uri)
        val bytes = resolver.openInputStream(uri)?.use {
            readBounded(it, MAX_FILE_BYTES)
        } ?: throw IllegalStateException(
            "فایل قابل خواندن نیست."
        )

        val kind = detectKind(name, mimeType)
        val extracted = when (kind) {
            PrimeDocumentKind.PDF -> extractPdf(bytes)
            PrimeDocumentKind.DOCX ->
                PrimeOoxmlTextExtractor.docx(readOoxmlEntries(bytes))
            PrimeDocumentKind.XLSX ->
                PrimeOoxmlTextExtractor.xlsx(readOoxmlEntries(bytes))
            PrimeDocumentKind.PPTX ->
                PrimeOoxmlTextExtractor.pptx(readOoxmlEntries(bytes))
            PrimeDocumentKind.TEXT,
            PrimeDocumentKind.CSV,
            PrimeDocumentKind.JSON -> decodeText(bytes)
        }
            .replace("\u0000", "")
            .trim()
            .take(MAX_EXTRACTED_CHARS)

        if (extracted.isBlank()) {
            throw IllegalStateException(
                "متن قابل استخراجی در فایل پیدا نشد."
            )
        }

        val chunks = PrimeFileChunker.chunk(extracted)
        if (chunks.isEmpty()) {
            throw IllegalStateException(
                "فایل متن قابل استفاده‌ای ندارد."
            )
        }

        return PrimeParsedDocument(
            name = name.take(180),
            mimeType = mimeType,
            kind = kind,
            text = extracted,
            chunks = chunks
        )
    }

    private fun detectKind(
        name: String,
        mimeType: String?
    ): PrimeDocumentKind {
        val lower = name.lowercase(Locale.ROOT)
        val mime = mimeType.orEmpty()
            .lowercase(Locale.ROOT)

        return when {
            lower.endsWith(".pdf") ||
                mime == "application/pdf" ->
                PrimeDocumentKind.PDF

            lower.endsWith(".docx") ||
                mime.contains(
                    "wordprocessingml.document"
                ) -> PrimeDocumentKind.DOCX

            lower.endsWith(".xlsx") ||
                mime.contains(
                    "spreadsheetml.sheet"
                ) -> PrimeDocumentKind.XLSX

            lower.endsWith(".pptx") ||
                mime.contains(
                    "presentationml.presentation"
                ) -> PrimeDocumentKind.PPTX

            lower.endsWith(".csv") ||
                mime == "text/csv" ->
                PrimeDocumentKind.CSV

            lower.endsWith(".json") ||
                mime == "application/json" ->
                PrimeDocumentKind.JSON

            lower.endsWith(".txt") ||
                lower.endsWith(".md") ||
                lower.endsWith(".log") ||
                mime.startsWith("text/") ->
                PrimeDocumentKind.TEXT

            else -> throw IllegalArgumentException(
                "فرمت فایل پشتیبانی نمی‌شود. " +
                    "PDF, TXT, MD, CSV, JSON, DOCX, XLSX یا PPTX انتخاب کن."
            )
        }
    }

    private fun extractPdf(bytes: ByteArray): String {
        ensurePdfBox(appContext)
        return PDDocument.load(bytes).use { document ->
            PDFTextStripper().getText(document)
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        val offset = if (
            bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            3
        } else {
            0
        }

        return bytes.copyOfRange(offset, bytes.size)
            .toString(Charsets.UTF_8)
    }

    private fun readOoxmlEntries(
        bytes: ByteArray
    ): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var total = 0

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }

                val name = entry.name
                    .replace('\\', '/')
                    .removePrefix("/")

                val wanted =
                    name == "word/document.xml" ||
                        name.matches(
                            Regex("word/(header|footer)\\d+\\.xml")
                        ) ||
                        name == "xl/sharedStrings.xml" ||
                        name.matches(
                            Regex("xl/worksheets/sheet\\d+\\.xml")
                        ) ||
                        name.matches(
                            Regex("ppt/slides/slide\\d+\\.xml")
                        )

                if (!wanted) {
                    zip.closeEntry()
                    continue
                }

                val data = readBounded(
                    zip,
                    MAX_ZIP_ENTRY_BYTES,
                    closeInput = false
                )
                total += data.size
                if (total > MAX_ZIP_TOTAL_BYTES) {
                    throw IllegalArgumentException(
                        "فایل فشرده بعد از بازشدن بیش از حد بزرگ است."
                    )
                }

                result[name] = data
                zip.closeEntry()
            }
        }

        return result
    }

    private fun displayName(uri: Uri): String? {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (
                cursor.moveToFirst() &&
                !cursor.isNull(0)
            ) {
                return cursor.getString(0)
            }
        }
        return null
    }

    private fun readBounded(
        input: java.io.InputStream,
        maxBytes: Int,
        closeInput: Boolean = false
    ): ByteArray {
        val output = ByteArrayOutputStream(
            maxBytes.coerceAtMost(64 * 1024)
        )
        val buffer = ByteArray(16 * 1024)
        var total = 0

        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw IllegalArgumentException(
                        "حجم فایل از سقف مجاز PRIME بیشتر است."
                    )
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        } finally {
            if (closeInput) input.close()
        }
    }
}
