package com.example.fileopener.domain.usecase

import com.example.fileopener.domain.model.DocumentFormat
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.fakes.FakeDocumentRecordsRepository
import com.example.fileopener.fakes.FakeDocumentStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentActionsUseCasesTest {
    private val storage = FakeDocumentStorage()
    private val records = FakeDocumentRecordsRepository()
    private val rename = RenameDocumentUseCase(storage, records)
    private val delete = DeleteDocumentUseCase(storage, records)
    private val report = DocumentItem("content://x/report.pdf", "report.pdf", DocumentFormat.PDF, 10, 0)

    @Test
    fun normalizeName_keepsExtensionWhenUserDropsOrChangesIt() {
        assertEquals("Итоги.pdf", RenameDocumentUseCase.normalizeName(report, "  Итоги "))
        assertEquals("Итоги.pdf", RenameDocumentUseCase.normalizeName(report, "Итоги.pdf"))
        assertEquals("Итоги.PDF", RenameDocumentUseCase.normalizeName(report, "Итоги.PDF"))
        assertEquals("Итоги.docx.pdf", RenameDocumentUseCase.normalizeName(report, "Итоги.docx"))
        assertEquals("v1.2.pdf", RenameDocumentUseCase.normalizeName(report, "v1.2"))
    }

    @Test
    fun normalizeName_rejectsEmptyAndForbiddenCharacters() {
        assertNull(RenameDocumentUseCase.normalizeName(report, "   "))
        assertNull(RenameDocumentUseCase.normalizeName(report, "a/b"))
        assertNull(RenameDocumentUseCase.normalizeName(report, "a:b"))
        assertNull(RenameDocumentUseCase.normalizeName(report, ".."))
    }

    @Test
    fun rename_movesRecordsToNewUri() = runTest {
        storage.writable += report.uri

        val result = rename(report, "Итоги")

        val renamed = (result as RenameResult.Success).document
        assertEquals("Итоги.pdf", renamed.name)
        assertEquals(listOf(report.uri to renamed), records.replaced)
    }

    @Test
    fun rename_failsWithoutTouchingRecordsWhenProviderRefuses() = runTest {
        assertEquals(RenameResult.Failed, rename(report, "Итоги"))
        assertTrue(records.replaced.isEmpty())
    }

    @Test
    fun rename_toSameNameIsNoOp() = runTest {
        assertEquals(RenameResult.Success(report), rename(report, "report.pdf"))
        assertTrue(records.replaced.isEmpty())
    }

    @Test
    fun delete_forgetsRecordsOnlyAfterFileIsDeleted() = runTest {
        assertFalse(delete(report))
        assertTrue(records.forgotten.isEmpty())

        storage.writable += report.uri
        assertTrue(delete(report))
        assertEquals(listOf(report.uri), storage.deleted)
        assertEquals(listOf(report.uri), records.forgotten)
    }
}
