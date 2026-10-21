package com.example.fileopener.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentFormatTest {
    @Test
    fun fromFileName_isCaseInsensitive() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.fromFileName("Report.PDF"))
        assertEquals(DocumentFormat.XLSX, DocumentFormat.fromFileName("budget.2026.xlsx"))
    }

    @Test
    fun fromFileName_unknownOrMissingExtension_returnsNull() {
        assertNull(DocumentFormat.fromFileName("archive.zip"))
        assertNull(DocumentFormat.fromFileName("README"))
    }

    @Test
    fun fromMimeType_ignoresParameters() {
        assertEquals(DocumentFormat.TXT, DocumentFormat.fromMimeType("text/plain; charset=utf-8"))
        assertEquals(DocumentFormat.DOC, DocumentFormat.fromMimeType("application/msword"))
    }

    @Test
    fun detect_prefersExtensionOverMime() {
        assertEquals(DocumentFormat.CSV, DocumentFormat.detect("data.csv", "text/plain"))
        assertEquals(DocumentFormat.DOCX, DocumentFormat.detect("letter.docx", "application/octet-stream"))
    }

    @Test
    fun detect_fallsBackToMime() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.detect("attachment", "application/pdf"))
        assertNull(DocumentFormat.detect(null, "application/octet-stream"))
    }
}
