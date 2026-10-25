package io.doccraftstudio.onedoc.reader.pdf

import io.doccraftstudio.onedoc.fakes.FakePdfHandle
import io.doccraftstudio.onedoc.fakes.rect
import io.doccraftstudio.onedoc.reader.TocEntry
import io.doccraftstudio.onedoc.reader.chapterFor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfTextSearchTest {
    @Test
    fun searchPages_emitsOnlyPagesWithMatchesInOrder() = runTest {
        val handle = FakePdfHandle(
            pageCount = 4,
            matches = mapOf(1 to listOf(listOf(rect(0.1f)), listOf(rect(0.5f))), 3 to listOf(listOf(rect(0.2f)))),
        )

        val pages = handle.searchPages("x").toList()

        assertEquals(listOf(listOf(1, 1), listOf(3)), pages.map { page -> page.map { it.pageIndex } })
        assertEquals(listOf(0, 1, 2, 3), handle.searchedPages)
    }

    @Test
    fun searchPages_wrapsEveryPageInGuard() = runTest {
        val handle = FakePdfHandle(pageCount = 3)
        var guarded = 0

        handle.searchPages("x") { block -> guarded++; block() }.toList()

        assertEquals(3, guarded)
    }

    @Test
    fun chapterFor_returnsLastEntryStartingNotAfterPage() {
        val toc = listOf(TocEntry("Введение", 0, 0), TocEntry("Глава 1", 4, 0), TocEntry("1.1", 6, 1))

        assertEquals("Введение", toc.chapterFor(3)?.title)
        assertEquals("Глава 1", toc.chapterFor(4)?.title)
        assertEquals("1.1", toc.chapterFor(100)?.title)
        assertNull(listOf(TocEntry("Позже", 5, 0)).chapterFor(2))
    }
}
