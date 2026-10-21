package com.example.fileopener.ui.viewer

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.example.fileopener.R
import com.example.fileopener.appContainer
import com.example.fileopener.domain.model.Bookmark
import com.example.fileopener.domain.model.DocumentFormat
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.model.MAX_READER_FONT_SP
import com.example.fileopener.domain.model.MIN_READER_FONT_SP
import com.example.fileopener.domain.model.ReaderFont
import com.example.fileopener.domain.model.ReaderSettings
import com.example.fileopener.domain.model.ReaderTheme
import com.example.fileopener.domain.model.ReadingPosition
import com.example.fileopener.domain.repository.DocumentStorage
import com.example.fileopener.domain.repository.ReadingStateRepository
import com.example.fileopener.domain.repository.RecentRepository
import com.example.fileopener.domain.repository.SettingsRepository
import com.example.fileopener.reader.pdf.PdfOpener
import com.example.fileopener.reader.pdf.PdfPasswordRequiredException
import com.example.fileopener.reader.chapterFor
import com.example.fileopener.reader.flow.FlowDocument
import com.example.fileopener.reader.flow.FlowDocumentLoader
import com.example.fileopener.reader.sheet.SpreadsheetLoader
import com.example.fileopener.ui.navigation.ViewerRoute
import com.example.fileopener.ui.viewer.pdf.PdfSession
import com.example.fileopener.ui.viewer.sheet.SheetSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException

sealed interface ViewerUiState {
    data object Loading : ViewerUiState
    data class Error(@StringRes val messageRes: Int, val document: DocumentItem? = null) : ViewerUiState

    /** Документ загружен. [initialPosition] — сохранённая позиция, с которой нужно открыть документ. */
    sealed interface Loaded : ViewerUiState {
        val document: DocumentItem
        val initialPosition: ReadingPosition
    }

    /** TXT, DOCX, DOC — текст с переносом по ширине экрана. */
    data class Flow(
        override val document: DocumentItem,
        override val initialPosition: ReadingPosition,
        val content: FlowDocument,
    ) : Loaded

    data class Pdf(
        override val document: DocumentItem,
        override val initialPosition: ReadingPosition,
        val session: PdfSession,
    ) : Loaded

    /** XLSX, XLS, CSV — сетка листов. */
    data class Sheet(
        override val document: DocumentItem,
        override val initialPosition: ReadingPosition,
        val session: SheetSession,
    ) : Loaded

    /** PDF зашифрован; [wrongPassword] — предыдущая попытка не подошла. */
    data class PasswordRequired(val document: DocumentItem, val wrongPassword: Boolean) : ViewerUiState
}

/**
 * Общая логика всех просмотрщиков: загрузка документа, запись в «Недавние»,
 * восстановление и автосохранение позиции, закладки и настройки чтения.
 */
class ViewerViewModel(
    private val uri: String,
    private val storage: DocumentStorage,
    private val recentRepository: RecentRepository,
    private val readingStateRepository: ReadingStateRepository,
    private val settingsRepository: SettingsRepository,
    private val flowLoader: FlowDocumentLoader,
    private val pdfOpener: PdfOpener,
    private val spreadsheetLoader: SpreadsheetLoader,
    /** Переживает ViewModel: последняя позиция дописывается после закрытия экрана. */
    private val applicationScope: CoroutineScope,
    /** Сообщение о времени открытия (замеры на больших файлах). */
    private val reportOpenTime: (String) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ViewModel() {

    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    /** `null`, пока настройки не прочитаны: так документ не мелькает в теме по умолчанию. */
    val readerSettings: StateFlow<ReaderSettings?> = settingsRepository.readerSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val bookmarks: StateFlow<List<Bookmark>> = readingStateRepository.observeBookmarks(uri)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val position = MutableStateFlow(ReadingPosition())
    private var positionDirty = false

    /** Текущая страница/абзац; меняется только при переходе на другой элемент, а не на каждый кадр прокрутки. */
    val currentIndex: StateFlow<Int> = position.map { it.index }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    private var saveJob: Job? = null

    /** Есть ли закладка на текущем элементе (странице, абзаце, строке листа). */
    val isCurrentPositionBookmarked: StateFlow<Boolean> = combine(
        position.map { it.sheet to it.index }.distinctUntilChanged(),
        bookmarks,
    ) { (sheet, index), list -> list.any { it.position.sheet == sheet && it.position.index == index } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _navigationRequests = Channel<ReadingPosition>(Channel.CONFLATED)

    /** Запросы перехода (закладка, оглавление); просмотрщик прокручивает к позиции. */
    val navigationRequests: Flow<ReadingPosition> = _navigationRequests.receiveAsFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val startedAt = clock()
        val document = storage.resolve(uri)
        if (document == null) {
            _uiState.value = ViewerUiState.Error(R.string.viewer_unavailable)
            return
        }
        recentRepository.markOpened(document)
        val savedPosition = readingStateRepository.getPosition(uri) ?: ReadingPosition()
        position.value = savedPosition
        openContent(document, savedPosition, password = null)
        logOpenTime(document, startedAt)
    }

    /** Время открытия для замеров на больших файлах: до первых строк листа или до готовности PDF. */
    private suspend fun logOpenTime(document: DocumentItem, startedAt: Long) {
        val state = _uiState.value
        if (state !is ViewerUiState.Loaded) return
        val sheet = (state as? ViewerUiState.Sheet)?.session?.let { it.sheet(it.currentSheet.value).state }
        sheet?.first { it.rowCount > 0 || it.complete }
        reportOpenTime("${document.format} ${document.sizeBytes / 1024} КБ открыт за ${clock() - startedAt} мс")
        if (sheet != null) {
            val parsed = sheet.first { it.complete }
            reportOpenTime("лист из ${parsed.rowCount} строк разобран за ${clock() - startedAt} мс")
        }
    }

    private suspend fun openContent(document: DocumentItem, savedPosition: ReadingPosition, password: String?) {
        _uiState.value = try {
            when (document.format) {
                DocumentFormat.TXT, DocumentFormat.DOCX, DocumentFormat.DOC ->
                    ViewerUiState.Flow(document, savedPosition, flowLoader.load(document))
                DocumentFormat.PDF -> {
                    val session = PdfSession(
                        handle = pdfOpener.open(uri, password),
                        parentScope = viewModelScope,
                        closeScope = applicationScope,
                        onNavigate = ::goTo,
                    )
                    session.initialize()
                    ViewerUiState.Pdf(document, savedPosition, session)
                }
                DocumentFormat.XLSX, DocumentFormat.XLS, DocumentFormat.CSV -> ViewerUiState.Sheet(
                    document = document,
                    initialPosition = savedPosition,
                    session = SheetSession(
                        source = spreadsheetLoader.load(document),
                        createStore = spreadsheetLoader::createRowStore,
                        parentScope = viewModelScope,
                        closeScope = applicationScope,
                        initialPosition = savedPosition,
                        onNavigate = ::goTo,
                    ),
                )
            }
        } catch (e: PdfPasswordRequiredException) {
            ViewerUiState.PasswordRequired(document, e.wrongPassword)
        } catch (e: IOException) {
            ViewerUiState.Error(R.string.viewer_read_error, document)
        } catch (e: XmlPullParserException) {
            ViewerUiState.Error(R.string.viewer_read_error, document)
        } catch (e: OutOfMemoryError) {
            // Огромный документ не поместился в память — сообщаем, а не падаем.
            ViewerUiState.Error(R.string.viewer_too_large, document)
        } catch (e: SecurityException) {
            ViewerUiState.Error(R.string.viewer_unavailable, document)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // Нативные движки на повреждённых файлах бросают что угодно — показываем ошибку, а не падаем.
            ViewerUiState.Error(R.string.viewer_read_error, document)
        }
    }

    fun submitPassword(password: String) {
        val state = _uiState.value as? ViewerUiState.PasswordRequired ?: return
        _uiState.value = ViewerUiState.Loading
        viewModelScope.launch { openContent(state.document, position.value, password) }
    }

    /** Поиск в PDF начинается с текущей страницы, в таблице — с текущей строки листа. */
    fun search(query: String) {
        when (val state = _uiState.value) {
            is ViewerUiState.Pdf -> state.session.search(query, fromPage = position.value.index)
            is ViewerUiState.Sheet -> {
                state.session.search(query)
                logSearchTime(state.session, query.trim())
            }
            else -> Unit
        }
    }

    /** Время поиска по листу для замеров: до первого совпадения и до конца прохода. */
    private fun logSearchTime(session: SheetSession, query: String) {
        if (query.isEmpty()) return
        val startedAt = clock()
        viewModelScope.launch {
            val first = session.search.first { it.query != query || it.matches.isNotEmpty() || !it.inProgress }
            if (first.query != query) return@launch
            if (first.matches.isNotEmpty()) reportOpenTime("поиск «$query»: первое совпадение за ${clock() - startedAt} мс")
            val done = session.search.first { it.query != query || !it.inProgress }
            if (done.query == query) {
                reportOpenTime("поиск «$query»: ${done.matches.size} совпадений за ${clock() - startedAt} мс")
            }
        }
    }

    /** Вызывается просмотрщиком при прокрутке; запись в базу — с задержкой, чтобы не писать на каждый кадр. */
    fun onPositionChanged(newPosition: ReadingPosition) {
        if (newPosition == position.value) return
        position.value = newPosition
        positionDirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(POSITION_SAVE_DELAY_MS)
            persistPosition()
        }
    }

    /** Немедленно сохраняет позицию (уход приложения в фон, закрытие экрана). */
    fun flushPosition() {
        if (!positionDirty) return
        saveJob?.cancel()
        applicationScope.launch { persistPosition() }
    }

    private suspend fun persistPosition() {
        positionDirty = false
        readingStateRepository.savePosition(uri, position.value)
    }

    fun toggleBookmark() {
        val current = position.value
        val existing = bookmarks.value.firstOrNull { it.position.sheet == current.sheet && it.position.index == current.index }
        viewModelScope.launch {
            if (existing != null) {
                readingStateRepository.removeBookmark(existing.id)
            } else {
                readingStateRepository.addBookmark(uri, bookmarkTitle(current), current)
            }
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch { readingStateRepository.removeBookmark(bookmark.id) }
    }

    fun goTo(target: ReadingPosition) {
        _navigationRequests.trySend(target)
    }

    fun setTheme(theme: ReaderTheme) {
        viewModelScope.launch { settingsRepository.updateReaderSettings { it.copy(theme = theme) } }
    }

    fun setFontSize(fontSizeSp: Float) {
        val clamped = fontSizeSp.coerceIn(MIN_READER_FONT_SP, MAX_READER_FONT_SP)
        viewModelScope.launch { settingsRepository.updateReaderSettings { it.copy(fontSizeSp = clamped) } }
    }

    fun setFont(font: ReaderFont) {
        viewModelScope.launch { settingsRepository.updateReaderSettings { it.copy(font = font) } }
    }

    fun setFreezeHeaderRow(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateReaderSettings { it.copy(freezeHeaderRow = enabled) } }
    }

    fun setLineSpacing(lineSpacing: Float) {
        val clamped = lineSpacing.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)
        viewModelScope.launch { settingsRepository.updateReaderSettings { it.copy(lineSpacing = clamped) } }
    }

    /** Заголовок закладки — начало текста в этом месте; пустой, если текста нет (подпись даст UI). */
    private fun bookmarkTitle(target: ReadingPosition): String = when (val state = _uiState.value) {
        is ViewerUiState.Flow -> state.content.blocks
            .drop(target.index)
            .take(BOOKMARK_SNIPPET_SEARCH_DEPTH)
            .map { it.plainText }
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.take(BOOKMARK_TITLE_LENGTH)
            .orEmpty()
        // Для PDF — название раздела оглавления; подпись «Страница N» добавит UI.
        is ViewerUiState.Pdf -> state.session.tableOfContents.value
            ?.chapterFor(target.index)
            ?.title
            ?.take(BOOKMARK_TITLE_LENGTH)
            .orEmpty()
        // Для таблицы — содержимое строки; название листа и номер строки добавит UI.
        is ViewerUiState.Sheet -> state.session.rowText(target.sheet, target.index)
            ?.replace(WHITESPACE, " ")
            ?.take(BOOKMARK_TITLE_LENGTH)
            .orEmpty()
        else -> ""
    }

    override fun onCleared() {
        flushPosition()
        when (val state = _uiState.value) {
            is ViewerUiState.Pdf -> state.session.close()
            is ViewerUiState.Flow -> state.content.close()
            is ViewerUiState.Sheet -> state.session.close()
            else -> Unit
        }
    }

    companion object {
        const val POSITION_SAVE_DELAY_MS = 1_000L
        const val MIN_LINE_SPACING = 1f
        const val MAX_LINE_SPACING = 2.2f
        private const val BOOKMARK_TITLE_LENGTH = 80
        private const val BOOKMARK_SNIPPET_SEARCH_DEPTH = 5
        private val WHITESPACE = Regex("\\s+")

        val Factory = viewModelFactory {
            initializer {
                val container = appContainer
                ViewerViewModel(
                    uri = createSavedStateHandle().toRoute<ViewerRoute>().uri,
                    storage = container.documentStorage,
                    recentRepository = container.recentRepository,
                    readingStateRepository = container.readingStateRepository,
                    settingsRepository = container.settingsRepository,
                    flowLoader = container.flowDocumentLoader,
                    pdfOpener = container.pdfOpener,
                    spreadsheetLoader = container.spreadsheetLoader,
                    applicationScope = container.applicationScope,
                    reportOpenTime = { Log.i("DocFlowPerf", it) },
                )
            }
        }
    }
}
