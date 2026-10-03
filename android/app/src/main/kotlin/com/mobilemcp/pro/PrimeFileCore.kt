package com.mobilemcp.pro

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

internal enum class PrimeDocumentKind {
    TEXT,
    CSV,
    JSON,
    PDF,
    DOCX,
    XLSX,
    PPTX
}

internal data class PrimeParsedDocument(
    val name: String,
    val mimeType: String?,
    val kind: PrimeDocumentKind,
    val text: String,
    val chunks: List<String>
)

internal data class PrimeSessionDocument(
    val id: Long,
    val name: String,
    val mimeType: String?,
    val kind: PrimeDocumentKind,
    val textChars: Int,
    val chunkCount: Int,
    val addedAt: Long
)

internal data class PrimeFileChunk(
    val documentId: Long,
    val documentName: String,
    val index: Int,
    val text: String,
    val normalized: String,
    val addedAt: Long
)

internal interface PrimeFileContextSource {
    fun relevant(query: String, limit: Int = 6): List<PrimeFileChunk>
}

internal object EmptyPrimeFileContextSource : PrimeFileContextSource {
    override fun relevant(
        query: String,
        limit: Int
    ): List<PrimeFileChunk> = emptyList()
}

internal object PrimeFileText {
    private val punctuation = Regex("[^\\p{L}\\p{N}_]+")
    private val stopWords = setOf(
        "که", "را", "رو", "من", "تو", "این", "اون", "آن",
        "و", "در", "به", "از", "برای", "با", "یک", "فایل",
        "سند", "لطفا", "لطفاً",
        "the", "a", "an", "and", "or", "to", "of", "in", "file",
        "document", "please"
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

    fun isDocumentReference(query: String): Boolean {
        val normalized = normalize(query)
        return listOf(
            "فایل",
            "سند",
            "pdf",
            "پی دی اف",
            "خلاصه",
            "خلاصه کن",
            "این فایل",
            "فایل پیوست",
            "document",
            "file",
            "attachment",
            "summarize",
            "summary"
        ).any { normalized.contains(normalize(it)) }
    }
}

internal object PrimeFileChunker {
    fun chunk(
        text: String,
        maxChars: Int = 1_600,
        overlapChars: Int = 180,
        maxChunks: Int = 120
    ): List<String> {
        require(maxChars >= 400)
        require(overlapChars in 0 until maxChars)
        require(maxChunks > 0)

        val clean = text
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\n{4,}"), "\n\n\n")
            .trim()

        if (clean.isBlank()) return emptyList()
        if (clean.length <= maxChars) return listOf(clean)

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < clean.length && chunks.size < maxChunks) {
            var end = (start + maxChars).coerceAtMost(clean.length)

            if (end < clean.length) {
                val paragraphBreak = clean.lastIndexOf("\n\n", end)
                val lineBreak = clean.lastIndexOf('\n', end)
                val sentenceBreak = maxOf(
                    clean.lastIndexOf(". ", end),
                    clean.lastIndexOf("؟ ", end),
                    clean.lastIndexOf("! ", end)
                )

                val candidate = listOf(
                    paragraphBreak,
                    lineBreak,
                    sentenceBreak
                ).maxOrNull() ?: -1

                if (candidate > start + maxChars / 2) {
                    end = candidate + 1
                }
            }

            val piece = clean.substring(start, end).trim()
            if (piece.isNotBlank()) chunks += piece

            if (end >= clean.length) break
            start = (end - overlapChars).coerceAtLeast(start + 1)
        }

        return chunks
    }
}

internal class PrimeAttachmentSession(
    private val maxDocuments: Int = 8,
    private val maxChunks: Int = 240
) : PrimeFileContextSource {
    private val documents = ArrayDeque<PrimeSessionDocument>()
    private val chunks = mutableListOf<PrimeFileChunk>()
    private var nextId = 1L

    @Synchronized
    fun add(document: PrimeParsedDocument): PrimeSessionDocument {
        val now = System.currentTimeMillis()
        val id = nextId++
        val sessionDocument = PrimeSessionDocument(
            id = id,
            name = document.name,
            mimeType = document.mimeType,
            kind = document.kind,
            textChars = document.text.length,
            chunkCount = document.chunks.size,
            addedAt = now
        )

        documents.addLast(sessionDocument)
        document.chunks.forEachIndexed { index, chunk ->
            chunks += PrimeFileChunk(
                documentId = id,
                documentName = document.name,
                index = index,
                text = chunk,
                normalized = PrimeFileText.normalize(chunk),
                addedAt = now
            )
        }

        while (documents.size > maxDocuments) {
            val removed = documents.removeFirst()
            chunks.removeAll { it.documentId == removed.id }
        }

        if (chunks.size > maxChunks) {
            val keepIds = documents
                .asReversed()
                .flatMap { doc ->
                    chunks.filter { it.documentId == doc.id }
                }
                .take(maxChunks)
                .map { it.documentId to it.index }
                .toSet()

            chunks.removeAll {
                (it.documentId to it.index) !in keepIds
            }
        }

        return sessionDocument
    }

    @Synchronized
    fun listDocuments(): List<PrimeSessionDocument> =
        documents.asReversed()

    @Synchronized
    fun clear() {
        documents.clear()
        chunks.clear()
    }

    @Synchronized
    fun remove(documentId: Long): Boolean {
        val removedDocument = documents.firstOrNull {
            it.id == documentId
        } ?: return false

        documents.remove(removedDocument)
        chunks.removeAll { it.documentId == documentId }
        return true
    }

    override fun relevant(
        query: String,
        limit: Int
    ): List<PrimeFileChunk> {
        val cappedLimit = limit.coerceIn(0, 12)
        if (cappedLimit == 0 || chunks.isEmpty()) return emptyList()

        val queryNormalized = PrimeFileText.normalize(query)
        val queryTokens = PrimeFileText.tokens(query)

        val ranked = chunks
            .map { chunk ->
                val chunkTokens = PrimeFileText.tokens(chunk.normalized)
                val overlap = queryTokens.intersect(chunkTokens).size
                var score = overlap * 4.0

                if (
                    queryNormalized.isNotBlank() &&
                    chunk.normalized.contains(queryNormalized)
                ) {
                    score += 18.0
                }

                if (queryTokens.isNotEmpty()) {
                    score += 6.0 *
                        overlap.toDouble() /
                        queryTokens.size.toDouble()
                }

                chunk to score
            }
            .filter { (_, score) -> score > 0.0 }
            .sortedWith(
                compareByDescending<Pair<PrimeFileChunk, Double>> {
                    it.second
                }.thenByDescending { it.first.addedAt }
                    .thenBy { it.first.index }
            )
            .take(cappedLimit)
            .map { it.first }

        if (ranked.isNotEmpty()) return ranked

        if (PrimeFileText.isDocumentReference(query)) {
            val latest = documents.lastOrNull() ?: return emptyList()
            return chunks
                .filter { it.documentId == latest.id }
                .sortedBy { it.index }
                .take(cappedLimit)
        }

        return emptyList()
    }
}

internal object PrimeOoxmlTextExtractor {
    fun docx(entries: Map<String, ByteArray>): String {
        val names = entries.keys
            .filter {
                it == "word/document.xml" ||
                    it.matches(
                        Regex("word/(header|footer)\\d+\\.xml")
                    )
            }
            .sortedWith(
                compareBy<String> {
                    if (it == "word/document.xml") 0 else 1
                }.thenBy { it }
            )

        return names
            .mapNotNull { name ->
                entries[name]?.let {
                    extractTextNodes(
                        it,
                        textElements = setOf("t"),
                        breakElements = setOf("p", "br", "tab")
                    )
                }
            }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .trim()
    }

    fun pptx(entries: Map<String, ByteArray>): String {
        val slideRegex = Regex("ppt/slides/slide(\\d+)\\.xml")
        val slides = entries.keys
            .mapNotNull { name ->
                slideRegex.matchEntire(name)?.let {
                    name to it.groupValues[1].toIntOrNull().orEmptyInt()
                }
            }
            .sortedBy { it.second }

        return slides.joinToString("\n\n") { (name, number) ->
            val text = entries[name]
                ?.let {
                    extractTextNodes(
                        it,
                        textElements = setOf("t"),
                        breakElements = setOf("p", "br")
                    )
                }
                .orEmpty()
                .trim()

            if (text.isBlank()) {
                ""
            } else {
                "Slide $number\n$text"
            }
        }.trim()
    }

    fun xlsx(entries: Map<String, ByteArray>): String {
        val sharedStrings = entries["xl/sharedStrings.xml"]
            ?.let(::extractSharedStrings)
            .orEmpty()

        val sheetRegex = Regex(
            "xl/worksheets/sheet(\\d+)\\.xml"
        )
        val sheets = entries.keys
            .mapNotNull { name ->
                sheetRegex.matchEntire(name)?.let {
                    name to it.groupValues[1].toIntOrNull().orEmptyInt()
                }
            }
            .sortedBy { it.second }

        return sheets.joinToString("\n\n") { (name, number) ->
            val text = entries[name]
                ?.let {
                    extractWorksheet(
                        xml = it,
                        sharedStrings = sharedStrings
                    )
                }
                .orEmpty()
                .trim()

            if (text.isBlank()) {
                ""
            } else {
                "Sheet $number\n$text"
            }
        }.trim()
    }

    private fun Int?.orEmptyInt(): Int = this ?: Int.MAX_VALUE

    private fun extractTextNodes(
        xml: ByteArray,
        textElements: Set<String>,
        breakElements: Set<String>
    ): String {
        val output = StringBuilder()
        val current = StringBuilder()
        var capturing = false

        parseXml(
            xml,
            object : DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes?
                ) {
                    val name = simpleName(localName, qName)
                    if (name in textElements) {
                        capturing = true
                        current.setLength(0)
                    }
                }

                override fun characters(
                    ch: CharArray,
                    start: Int,
                    length: Int
                ) {
                    if (capturing) {
                        current.append(ch, start, length)
                    }
                }

                override fun endElement(
                    uri: String?,
                    localName: String?,
                    qName: String?
                ) {
                    val name = simpleName(localName, qName)
                    if (name in textElements) {
                        appendToken(output, current.toString())
                        current.setLength(0)
                        capturing = false
                    } else if (name in breakElements) {
                        appendBreak(output)
                    }
                }
            }
        )

        return normalizeOutput(output.toString())
    }

    private fun extractSharedStrings(
        xml: ByteArray
    ): List<String> {
        val result = mutableListOf<String>()
        val item = StringBuilder()
        val text = StringBuilder()
        var inItem = false
        var inText = false

        parseXml(
            xml,
            object : DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes?
                ) {
                    when (simpleName(localName, qName)) {
                        "si" -> {
                            inItem = true
                            item.setLength(0)
                        }
                        "t" -> if (inItem) {
                            inText = true
                            text.setLength(0)
                        }
                    }
                }

                override fun characters(
                    ch: CharArray,
                    start: Int,
                    length: Int
                ) {
                    if (inText) text.append(ch, start, length)
                }

                override fun endElement(
                    uri: String?,
                    localName: String?,
                    qName: String?
                ) {
                    when (simpleName(localName, qName)) {
                        "t" -> if (inText) {
                            item.append(text)
                            inText = false
                        }
                        "si" -> if (inItem) {
                            result += item.toString()
                            inItem = false
                        }
                    }
                }
            }
        )

        return result
    }

    private fun extractWorksheet(
        xml: ByteArray,
        sharedStrings: List<String>
    ): String {
        val output = StringBuilder()
        val value = StringBuilder()
        var cellType: String? = null
        var capturingValue = false
        var rawValue: String? = null

        parseXml(
            xml,
            object : DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes?
                ) {
                    when (simpleName(localName, qName)) {
                        "c" -> {
                            cellType = attributes?.getValue("t")
                            rawValue = null
                        }
                        "v", "t" -> {
                            capturingValue = true
                            value.setLength(0)
                        }
                    }
                }

                override fun characters(
                    ch: CharArray,
                    start: Int,
                    length: Int
                ) {
                    if (capturingValue) {
                        value.append(ch, start, length)
                    }
                }

                override fun endElement(
                    uri: String?,
                    localName: String?,
                    qName: String?
                ) {
                    when (simpleName(localName, qName)) {
                        "v", "t" -> {
                            rawValue = value.toString()
                            capturingValue = false
                        }
                        "c" -> {
                            val raw = rawValue.orEmpty()
                            val resolved = if (cellType == "s") {
                                raw.toIntOrNull()
                                    ?.let { sharedStrings.getOrNull(it) }
                                    .orEmpty()
                            } else {
                                raw
                            }
                            if (resolved.isNotBlank()) {
                                if (
                                    output.isNotEmpty() &&
                                    output.last() != '\n'
                                ) {
                                    output.append('\t')
                                }
                                output.append(resolved.trim())
                            }
                            cellType = null
                            rawValue = null
                        }
                        "row" -> appendBreak(output)
                    }
                }
            }
        )

        return normalizeOutput(output.toString())
    }

    private fun parseXml(
        xml: ByteArray,
        handler: DefaultHandler
    ) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching {
                setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true
                )
            }
            runCatching {
                setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false
                )
            }
            runCatching {
                setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false
                )
            }
        }

        factory.newSAXParser().parse(
            ByteArrayInputStream(xml),
            handler
        )
    }

    private fun simpleName(
        localName: String?,
        qName: String?
    ): String {
        val name = localName
            ?.takeIf { it.isNotBlank() }
            ?: qName.orEmpty()

        return name.substringAfter(':')
    }

    private fun appendToken(
        output: StringBuilder,
        token: String
    ) {
        val clean = token.trim()
        if (clean.isBlank()) return
        if (
            output.isNotEmpty() &&
            !output.last().isWhitespace()
        ) {
            output.append(' ')
        }
        output.append(clean)
    }

    private fun appendBreak(output: StringBuilder) {
        while (
            output.isNotEmpty() &&
            (output.last() == ' ' || output.last() == '\t')
        ) {
            output.deleteCharAt(output.lastIndex)
        }
        if (output.isNotEmpty() && output.last() != '\n') {
            output.append('\n')
        }
    }

    private fun normalizeOutput(value: String): String =
        value
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
