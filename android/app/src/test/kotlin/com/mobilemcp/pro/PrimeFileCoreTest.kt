package com.mobilemcp.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeFileCoreTest {

    @Test
    fun chunkerPreservesRecentTextWithBoundedChunks() {
        val text = buildString {
            repeat(40) { index ->
                append("Paragraph $index ")
                append("متن آزمایشی ".repeat(20))
                append("\n\n")
            }
        }

        val chunks = PrimeFileChunker.chunk(
            text = text,
            maxChars = 700,
            overlapChars = 80,
            maxChunks = 20
        )

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.size <= 20)
        assertTrue(chunks.all { it.length <= 700 })
        assertTrue(chunks.first().contains("Paragraph 0"))
    }

    @Test
    fun sessionRetrievesRelevantInvoiceChunk() {
        val session = PrimeAttachmentSession()
        session.add(
            PrimeParsedDocument(
                name = "invoice.csv",
                mimeType = "text/csv",
                kind = PrimeDocumentKind.CSV,
                text = "Invoice 100 total 50\nInvoice 200 total 900",
                chunks = listOf(
                    "Invoice 100 total 50",
                    "Invoice 200 total 900"
                )
            )
        )

        val relevant = session.relevant(
            "Invoice 200 چقدر است؟",
            limit = 2
        )

        assertTrue(relevant.isNotEmpty())
        assertTrue(relevant.first().text.contains("900"))
    }

    @Test
    fun genericSummaryFallsBackToLatestDocument() {
        val session = PrimeAttachmentSession()
        session.add(
            PrimeParsedDocument(
                name = "notes.txt",
                mimeType = "text/plain",
                kind = PrimeDocumentKind.TEXT,
                text = "alpha beta gamma",
                chunks = listOf("alpha", "beta", "gamma")
            )
        )

        val relevant = session.relevant(
            "این فایل رو خلاصه کن",
            limit = 2
        )

        assertEquals(2, relevant.size)
        assertEquals("alpha", relevant[0].text)
    }

    @Test
    fun docxExtractorReadsTextNodes() {
        val xml = """
            <w:document xmlns:w="urn:w">
              <w:body>
                <w:p><w:r><w:t>سلام</w:t></w:r></w:p>
                <w:p><w:r><w:t>PRIME</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent().toByteArray()

        val text = PrimeOoxmlTextExtractor.docx(
            mapOf("word/document.xml" to xml)
        )

        assertTrue(text.contains("سلام"))
        assertTrue(text.contains("PRIME"))
    }

    @Test
    fun pptxExtractorKeepsSlideOrder() {
        fun slide(text: String) = """
            <p:sld xmlns:p="urn:p" xmlns:a="urn:a">
              <a:t>$text</a:t>
            </p:sld>
        """.trimIndent().toByteArray()

        val text = PrimeOoxmlTextExtractor.pptx(
            mapOf(
                "ppt/slides/slide2.xml" to slide("second"),
                "ppt/slides/slide1.xml" to slide("first")
            )
        )

        assertTrue(text.indexOf("first") < text.indexOf("second"))
    }

    @Test
    fun xlsxExtractorResolvesSharedStringsAndNumbers() {
        val shared = """
            <sst xmlns="urn:s">
              <si><t>Invoice</t></si>
              <si><t>Total</t></si>
            </sst>
        """.trimIndent().toByteArray()

        val sheet = """
            <worksheet xmlns="urn:s">
              <sheetData>
                <row>
                  <c t="s"><v>0</v></c>
                  <c><v>200</v></c>
                  <c t="s"><v>1</v></c>
                  <c><v>900</v></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent().toByteArray()

        val text = PrimeOoxmlTextExtractor.xlsx(
            mapOf(
                "xl/sharedStrings.xml" to shared,
                "xl/worksheets/sheet1.xml" to sheet
            )
        )

        assertTrue(text.contains("Invoice"))
        assertTrue(text.contains("200"))
        assertTrue(text.contains("Total"))
        assertTrue(text.contains("900"))
        assertFalse(text.contains("\t\t"))
    }

    @Test
    fun documentSessionKeepsPersistentAttachmentProvenance() {
        val session = PrimeAttachmentSession()
        val stored = session.add(
            PrimeParsedDocument(
                name = "report.pdf",
                mimeType = "application/pdf",
                kind = PrimeDocumentKind.PDF,
                text = "hello",
                chunks = listOf("hello")
            ),
            persistedAttachmentId = 42L,
            storageUri = "content://docs/report"
        )

        assertEquals(42L, stored.persistedAttachmentId)
        assertEquals(
            "content://docs/report",
            stored.storageUri
        )
        assertTrue(session.remove(stored.id))
        assertTrue(session.listDocuments().isEmpty())
    }

}
