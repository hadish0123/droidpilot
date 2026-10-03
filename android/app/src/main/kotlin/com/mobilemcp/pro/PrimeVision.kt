package com.mobilemcp.pro

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

internal data class PrimeSessionImage(
    val id: Long,
    val input: AiImageInput,
    val byteSize: Int,
    val addedAt: Long,
    val persistedAttachmentId: Long? = null,
    val storageUri: String? = null
)

internal interface PrimeImageContextSource {
    fun images(
        limit: Int = 4
    ): List<AiImageInput>

    fun hasImages(): Boolean =
        images(1).isNotEmpty()
}

internal object EmptyPrimeImageContextSource :
    PrimeImageContextSource {
    override fun images(
        limit: Int
    ): List<AiImageInput> =
        emptyList()
}

internal object PrimeVisionIntent {
    private val imageTerms = listOf(
        "image",
        "photo",
        "picture",
        "screenshot",
        "تصویر",
        "عکس",
        "اسکرین شات",
        "اسکرین‌شات"
    )

    private val visualVerbs = listOf(
        "describe",
        "analyze",
        "analyse",
        "what is in",
        "what's in",
        "read this",
        "explain this",
        "توضیح",
        "توصیف",
        "تحلیل",
        "بگو چیه",
        "چی میبینی",
        "چی می‌بینی",
        "متنش رو بخون",
        "متنش را بخوان"
    )

    private val pronounPhrases = listOf(
        "این رو توضیح بده",
        "این را توضیح بده",
        "این چیه",
        "این چیست",
        "این رو تحلیل کن",
        "این را تحلیل کن"
    )

    fun shouldAnalyze(
        input: String,
        hasImages: Boolean
    ): Boolean {
        if (!hasImages) return false
        val normalized =
            normalize(input)

        if (
            pronounPhrases.any {
                normalized.contains(
                    normalize(it)
                )
            }
        ) {
            return true
        }

        val mentionsImage =
            imageTerms.any {
                normalized.contains(
                    normalize(it)
                )
            }
        val asksVisual =
            visualVerbs.any {
                normalized.contains(
                    normalize(it)
                )
            }

        return mentionsImage ||
            asksVisual
    }

    private fun normalize(
        value: String
    ): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('\u200c', ' ')
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
}

internal class PrimeImageSession(
    private val maxImages: Int = 4,
    private val maxTotalBytes: Int =
        20 * 1024 * 1024
) : PrimeImageContextSource {
    private val images =
        ArrayDeque<PrimeSessionImage>()
    private var nextId = 1L

    @Synchronized
    fun add(
        input: AiImageInput,
        byteSize: Int,
        persistedAttachmentId: Long? = null,
        storageUri: String? = null
    ): PrimeSessionImage {
        require(byteSize > 0) {
            "Image is empty"
        }
        require(
            byteSize <=
                8 * 1024 * 1024
        ) {
            "Image exceeds the 8 MiB PRIME limit"
        }

        val item =
            PrimeSessionImage(
                id = nextId++,
                input = input,
                byteSize = byteSize,
                addedAt =
                    System.currentTimeMillis(),
                persistedAttachmentId =
                    persistedAttachmentId,
                storageUri = storageUri
            )
        images.addLast(item)

        while (
            images.size >
            maxImages
        ) {
            images.removeFirst()
        }

        while (
            totalBytes() >
                maxTotalBytes &&
            images.size > 1
        ) {
            images.removeFirst()
        }

        return item
    }

    @Synchronized
    override fun images(
        limit: Int
    ): List<AiImageInput> =
        images
            .asReversed()
            .take(
                limit.coerceIn(
                    0,
                    maxImages
                )
            )
            .reversed()
            .map { it.input }

    @Synchronized
    fun list():
        List<PrimeSessionImage> =
        images.asReversed()

    @Synchronized
    fun remove(id: Long): Boolean {
        val item = images
            .firstOrNull {
                it.id == id
            }
            ?: return false
        images.remove(item)
        return true
    }

    @Synchronized
    fun clear() {
        images.clear()
    }

    @Synchronized
    private fun totalBytes(): Int =
        images.sumOf {
            it.byteSize
        }
}

internal class PrimeImageLoader(
    context: Context
) {
    companion object {
        private const val MAX_BYTES =
            8 * 1024 * 1024

        private val SUPPORTED =
            setOf(
                "image/png",
                "image/jpeg",
                "image/webp"
            )
    }

    private val appContext =
        context.applicationContext

    fun load(
        uri: Uri
    ): Pair<AiImageInput, Int> {
        val resolver =
            appContext.contentResolver
        val mime = resolver
            .getType(uri)
            ?.lowercase(
                Locale.ROOT
            )
            ?: throw IllegalArgumentException(
                "نوع تصویر مشخص نیست."
            )

        require(mime in SUPPORTED) {
            "فرمت تصویر پشتیبانی نمی‌شود. PNG، JPEG یا WEBP انتخاب کن."
        }

        val name = displayName(uri)
            ?: uri.lastPathSegment
            ?: "image"

        val bytes = resolver
            .openInputStream(uri)
            ?.use {
                readBounded(it)
            }
            ?: throw IllegalStateException(
                "تصویر قابل خواندن نیست."
            )

        val encoded =
            Base64.getEncoder()
                .encodeToString(
                    bytes
                )
        val input =
            AiImageInput(
                name = name.take(160),
                mimeType = mime,
                dataUrl =
                    "data:" +
                        mime +
                        ";base64," +
                        encoded
            )

        return input to
            bytes.size
    }

    private fun displayName(
        uri: Uri
    ): String? {
        appContext.contentResolver
            .query(
                uri,
                arrayOf(
                    OpenableColumns
                        .DISPLAY_NAME
                ),
                null,
                null,
                null
            )
            ?.use { cursor ->
                if (
                    cursor.moveToFirst() &&
                    !cursor.isNull(0)
                ) {
                    return cursor
                        .getString(0)
                }
            }
        return null
    }

    private fun readBounded(
        input:
            java.io.InputStream
    ): ByteArray {
        val output =
            ByteArrayOutputStream(
                64 * 1024
            )
        val buffer =
            ByteArray(
                16 * 1024
            )
        var total = 0

        while (true) {
            val count =
                input.read(buffer)
            if (count < 0) break
            total += count
            if (
                total > MAX_BYTES
            ) {
                throw IllegalArgumentException(
                    "حجم تصویر از سقف 8 MiB PRIME بیشتر است."
                )
            }
            output.write(
                buffer,
                0,
                count
            )
        }

        return output
            .toByteArray()
    }
}
