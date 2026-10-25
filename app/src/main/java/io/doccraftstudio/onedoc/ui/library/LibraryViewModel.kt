package io.doccraftstudio.onedoc.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.doccraftstudio.onedoc.appContainer
import io.doccraftstudio.onedoc.domain.model.DocumentCategory
import io.doccraftstudio.onedoc.domain.model.DocumentItem
import io.doccraftstudio.onedoc.domain.model.LibraryFolder
import io.doccraftstudio.onedoc.domain.model.LibraryPreferences
import io.doccraftstudio.onedoc.domain.model.LibraryViewMode
import io.doccraftstudio.onedoc.domain.model.SortField
import io.doccraftstudio.onedoc.domain.repository.LibraryRepository
import io.doccraftstudio.onedoc.domain.repository.SettingsRepository
import io.doccraftstudio.onedoc.domain.usecase.AddLibraryFilesUseCase
import io.doccraftstudio.onedoc.domain.usecase.AddLibraryFolderUseCase
import io.doccraftstudio.onedoc.domain.usecase.FilterDocumentsUseCase
import io.doccraftstudio.onedoc.domain.usecase.RemoveLibraryFolderUseCase
import io.doccraftstudio.onedoc.domain.usecase.ScanLibraryUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoading: Boolean = true,
    val isScanning: Boolean = false,
    val folders: List<LibraryFolder> = emptyList(),
    /** Документы после поиска, фильтра и сортировки. */
    val documents: List<DocumentItem> = emptyList(),
    /** Сколько документов в библиотеке без поиска и фильтра: отличает «пусто» от «ничего не найдено». */
    val totalCount: Int = 0,
    val query: String = "",
    val preferences: LibraryPreferences = LibraryPreferences(),
)

/** Одноразовые сообщения для Snackbar. */
sealed interface LibraryMessage {
    data object FolderAccessFailed : LibraryMessage
    data object NoSupportedFiles : LibraryMessage
    data class FilesAdded(val count: Int) : LibraryMessage
    data class FoldersUnavailable(val names: List<String>) : LibraryMessage
}

class LibraryViewModel(
    libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val scanLibrary: ScanLibraryUseCase,
    private val addLibraryFolder: AddLibraryFolderUseCase,
    private val addLibraryFiles: AddLibraryFilesUseCase,
    private val removeLibraryFolder: RemoveLibraryFolderUseCase,
    filterDocuments: FilterDocumentsUseCase = FilterDocumentsUseCase(),
) : ViewModel() {

    private val activeOperations = MutableStateFlow(0)
    private val query = MutableStateFlow("")
    private val _messages = Channel<LibraryMessage>(Channel.BUFFERED)
    val messages: Flow<LibraryMessage> = _messages.receiveAsFlow()

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.observeFolders(),
        libraryRepository.observeDocuments(),
        settingsRepository.libraryPreferences,
        activeOperations,
        query,
    ) { folders, documents, preferences, operations, query ->
        LibraryUiState(
            isLoading = false,
            isScanning = operations > 0,
            folders = folders,
            documents = filterDocuments(documents, preferences, query),
            totalCount = documents.size,
            query = query,
            preferences = preferences,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // Автосканирование при запуске: подхватываем файлы, появившиеся в папках с прошлого раза.
        refresh()
    }

    fun refresh() = launchOperation {
        val unavailable = scanLibrary()
        if (unavailable.isNotEmpty()) {
            _messages.send(LibraryMessage.FoldersUnavailable(unavailable.map { it.displayName }))
        }
    }

    fun onFolderPicked(treeUri: String?) {
        if (treeUri == null) return
        launchOperation {
            if (!addLibraryFolder(treeUri)) _messages.send(LibraryMessage.FolderAccessFailed)
        }
    }

    fun onFilesPicked(uris: List<String>) {
        if (uris.isEmpty()) return
        launchOperation {
            val added = addLibraryFiles(uris)
            _messages.send(if (added > 0) LibraryMessage.FilesAdded(added) else LibraryMessage.NoSupportedFiles)
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /** Повторный выбор того же поля меняет направление; новое поле — сначала новые, большие или «А–Я». */
    fun sortBy(field: SortField) = updatePreferences { current ->
        if (current.sortField == field) {
            current.copy(sortDescending = !current.sortDescending)
        } else {
            current.copy(sortField = field, sortDescending = field != SortField.NAME)
        }
    }

    fun toggleCategory(category: DocumentCategory) = updatePreferences { current ->
        val filter = current.categoryFilter
        current.copy(categoryFilter = if (category in filter) filter - category else filter + category)
    }

    fun clearCategoryFilter() = updatePreferences { it.copy(categoryFilter = emptySet()) }

    fun setViewMode(mode: LibraryViewMode) = updatePreferences { it.copy(viewMode = mode) }

    private fun updatePreferences(transform: (LibraryPreferences) -> LibraryPreferences) {
        viewModelScope.launch { settingsRepository.updateLibraryPreferences(transform) }
    }

    fun removeFolder(folder: LibraryFolder) {
        viewModelScope.launch { removeLibraryFolder(folder) }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            activeOperations.update { it + 1 }
            try {
                block()
            } finally {
                activeOperations.update { it - 1 }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer
                LibraryViewModel(
                    libraryRepository = container.libraryRepository,
                    settingsRepository = container.settingsRepository,
                    scanLibrary = container.scanLibrary,
                    addLibraryFolder = container.addLibraryFolder,
                    addLibraryFiles = container.addLibraryFiles,
                    removeLibraryFolder = container.removeLibraryFolder,
                )
            }
        }
    }
}
