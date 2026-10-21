package com.example.fileopener.ui.viewer

import com.example.fileopener.R
import com.example.fileopener.domain.model.DocumentFormat
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.model.MAX_READER_FONT_SP
import com.example.fileopener.domain.model.ReadingPosition
import com.example.fileopener.fakes.FakeDocumentStorage
import com.example.fileopener.fakes.FakePdfHandle
import com.example.fileopener.fakes.FakePdfOpener
import com.example.fileopener.fakes.rect
import com.example.fileopener.reader.flow.toFlowDocument
import com.example.fileopener.reader.pdf.PdfOpener
import com.example.fileopener.reader.txt.TextReader
import com.example.fileopener.reader.csv.CsvSource
import com.example.fileopener.reader.sheet.SheetRowStore
import com.example.fileopener.reader.sheet.SpreadsheetLoader
import java.io.File
import com.example.fileopener.reader.TocEntry
import kotlinx.coroutines.flow.first
import com.example.fileopener.fakes.FakeReadingStateRepository
import com.example.fileopener.fakes.FakeRecentRepository
import com.example.fileopener.fakes.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val document = DocumentItem("content://x/notes.txt", "notes.txt", DocumentFormat.TXT, 10, 0)
    private val pdf = DocumentItem("content://x/report.pdf", "report.pdf", DocumentFormat.PDF, 10, 0)
    private val csv = DocumentItem("content://x/prices.csv", "prices.csv", DocumentFormat.CSV, 10, 0)
    private val storage = FakeDocumentStorage().apply {
        resolvable[document.uri] = document
        resolvable[pdf.uri] = pdf
        resolvable[csv.uri] = csv
    }
    private val recent = FakeRecentRepository()
    private val readingState = FakeReadingStateRepository()
    private val settings = FakeSettingsRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun load_readsTextRestoresPositionAndMarksRecent() = runTest(dispatcher) {
        readingState.positions[document.uri] = ReadingPosition(index = 2, offset = 15)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value as ViewerUiState.Flow
        assertEquals(ReadingPosition(index = 2, offset = 15), state.initialPosition)
        assertEquals(listOf("Первый", "", "Третий абзац"), state.content.blocks.map { it.plainText })
        assertEquals(listOf(document), recent.opened)
    }

    @Test
    fun load_unavailableDocument_showsError() = runTest(dispatcher) {
        val viewModel = createViewModel(uri = "content://x/deleted.txt")
        advanceUntilIdle()

        assertEquals(ViewerUiState.Error(R.string.viewer_unavailable), viewModel.uiState.value)
        assertTrue(recent.opened.isEmpty())
    }

    @Test
    fun positionChange_isSavedOnlyAfterDelay() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPositionChanged(ReadingPosition(index = 1))
        advanceTimeBy(ViewerViewModel.POSITION_SAVE_DELAY_MS - 1)
        runCurrent()
        assertNull(readingState.positions[document.uri])

        advanceTimeBy(2)
        runCurrent()
        assertEquals(ReadingPosition(index = 1), readingState.positions[document.uri])
    }

    @Test
    fun flushPosition_savesImmediately() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPositionChanged(ReadingPosition(index = 2, offset = 7))
        viewModel.flushPosition()
        runCurrent()

        assertEquals(ReadingPosition(index = 2, offset = 7), readingState.positions[document.uri])
    }

    @Test
    fun toggleBookmark_addsSnippetTitleThenRemoves() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        // Абзац 1 пустой: заголовок берётся из ближайшего непустого.
        viewModel.onPositionChanged(ReadingPosition(index = 1))

        viewModel.toggleBookmark()
        advanceUntilIdle()
        assertEquals(listOf("Третий абзац"), readingState.allBookmarks.map { it.title })
        assertTrue(viewModel.isCurrentPositionBookmarked.value)

        viewModel.toggleBookmark()
        advanceUntilIdle()
        assertTrue(readingState.allBookmarks.isEmpty())
        assertFalse(viewModel.isCurrentPositionBookmarked.value)
    }

    @Test
    fun setFontSize_isClamped() = runTest(dispatcher) {
        val viewModel = createViewModel()
        viewModel.setFontSize(500f)
        advanceUntilIdle()

        assertEquals(MAX_READER_FONT_SP, settings.currentReaderSettings.fontSizeSp)
    }

    @Test
    fun pdf_opensSessionWithAllPages() = runTest(dispatcher) {
        val viewModel = createViewModel(uri = pdf.uri, pdfOpener = FakePdfOpener(FakePdfHandle(pageCount = 5)))
        advanceUntilIdle()

        val state = viewModel.uiState.value as ViewerUiState.Pdf
        assertEquals(5, state.session.pageCount)
        assertEquals(5, state.session.pageSizes.value.size)
    }

    @Test
    fun pdf_withPassword_asksAgainOnWrongAndOpensOnRight() = runTest(dispatcher) {
        val viewModel = createViewModel(
            uri = pdf.uri,
            pdfOpener = FakePdfOpener(FakePdfHandle(pageCount = 2), password = "секрет"),
        )
        advanceUntilIdle()
        assertEquals(ViewerUiState.PasswordRequired(pdf, wrongPassword = false), viewModel.uiState.value)

        viewModel.submitPassword("неверно")
        advanceUntilIdle()
        assertEquals(ViewerUiState.PasswordRequired(pdf, wrongPassword = true), viewModel.uiState.value)

        viewModel.submitPassword("секрет")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is ViewerUiState.Pdf)
    }

    @Test
    fun pdf_bookmarkTitleIsChapterFromTableOfContents() = runTest(dispatcher) {
        val handle = FakePdfHandle(pageCount = 10, toc = listOf(TocEntry("Введение", 0, 0), TocEntry("Глава 2", 5, 0)))
        val viewModel = createViewModel(uri = pdf.uri, pdfOpener = FakePdfOpener(handle))
        advanceUntilIdle()

        viewModel.onPositionChanged(ReadingPosition(index = 7))
        viewModel.toggleBookmark()
        advanceUntilIdle()

        assertEquals(listOf("Глава 2"), readingState.allBookmarks.map { it.title })
    }

    @Test
    fun pdfSearch_selectsFirstMatchFromCurrentPageAndNavigatesToIt() = runTest(dispatcher) {
        val handle = FakePdfHandle(
            pageCount = 5,
            matches = mapOf(1 to listOf(listOf(rect(0.5f))), 3 to listOf(listOf(rect(0.2f)))),
        )
        val viewModel = createViewModel(uri = pdf.uri, pdfOpener = FakePdfOpener(handle))
        advanceUntilIdle()
        viewModel.onPositionChanged(ReadingPosition(index = 2))

        viewModel.search("отчёт")
        advanceUntilIdle()

        val search = (viewModel.uiState.value as ViewerUiState.Pdf).session.search.value
        assertEquals(2, search.matches.size)
        assertEquals(3, search.current?.pageIndex)
        // Отступ 3% над совпадением: 0.2 − 0.03 = 0.17 высоты страницы.
        assertEquals(ReadingPosition(index = 3, offset = 1_700), viewModel.navigationRequests.first())
    }

    @Test
    fun pdfSearch_wrapsToStartWhenNothingBelowCurrentPage() = runTest(dispatcher) {
        val handle = FakePdfHandle(pageCount = 5, matches = mapOf(1 to listOf(listOf(rect(0.5f)))))
        val viewModel = createViewModel(uri = pdf.uri, pdfOpener = FakePdfOpener(handle))
        advanceUntilIdle()
        viewModel.onPositionChanged(ReadingPosition(index = 4))

        viewModel.search("отчёт")
        advanceUntilIdle()

        val session = (viewModel.uiState.value as ViewerUiState.Pdf).session
        assertEquals(1, session.search.value.current?.pageIndex)
        session.nextMatch()
        assertEquals(0, session.search.value.currentIndex)
    }

    @Test
    fun load_csvOpensSheetAtSavedSheetPosition() = runTest(dispatcher) {
        readingState.positions[csv.uri] = ReadingPosition(index = 1, sheet = 0, zoom = 1.5f)

        val viewModel = createViewModel(uri = csv.uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value as ViewerUiState.Sheet
        assertEquals(listOf("prices"), state.session.sheetNames)
        assertEquals(1.5f, state.session.positionFor(0).zoom)
        val parsed = state.session.sheet(0).state.first { it.complete }
        assertEquals(3, parsed.rowCount)
        assertEquals(2, parsed.columnCount)
    }

    @Test
    fun sheetSearch_navigatesToMatchAndBookmarkTakesRowText() = runTest(dispatcher) {
        val viewModel = createViewModel(uri = csv.uri)
        advanceUntilIdle()
        val session = (viewModel.uiState.value as ViewerUiState.Sheet).session

        viewModel.search("ГРУША")
        val search = session.search.first { it.isActive && !it.inProgress }
        assertEquals(1, search.matches.size)
        assertEquals(2, search.current?.row)
        // Совпадение показывается с парой строк над ним.
        assertEquals(ReadingPosition(index = 0, sheet = 0, column = 0), viewModel.navigationRequests.first())

        // Строка видна на экране — значит, она в кэше окна строк.
        session.sheet(0).loadRows(0..2)
        viewModel.onPositionChanged(ReadingPosition(index = 2, sheet = 0))
        viewModel.toggleBookmark()
        advanceUntilIdle()
        assertEquals(listOf("груша 30"), readingState.allBookmarks.map { it.title })
    }

    private val sheetCache = File(System.getProperty("java.io.tmpdir"), "viewer-test-sheets").apply { mkdirs() }

    private val spreadsheetLoader = object : SpreadsheetLoader {
        override suspend fun load(document: DocumentItem) =
            CsvSource("prices") { "фрукт;цена\nяблоко;10\nгруша;30\n".byteInputStream() }

        override fun createRowStore() = SheetRowStore(File.createTempFile("sheet", ".bin", sheetCache))
    }

    private fun TestScope.createViewModel(
        uri: String = document.uri,
        pdfOpener: PdfOpener = FakePdfOpener(FakePdfHandle(pageCount = 1)),
    ) = ViewerViewModel(
        uri = uri,
        storage = storage,
        recentRepository = recent,
        readingStateRepository = readingState,
        settingsRepository = settings,
        flowLoader = { TextReader.read("Первый\n\nТретий абзац".byteInputStream()).toFlowDocument() },
        pdfOpener = pdfOpener,
        spreadsheetLoader = spreadsheetLoader,
        applicationScope = this,
    )
}
