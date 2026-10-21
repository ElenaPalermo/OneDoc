package com.example.fileopener.fakes

import com.example.fileopener.domain.model.Bookmark
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.model.ImportResult
import com.example.fileopener.domain.model.LibraryFolder
import com.example.fileopener.domain.model.LibraryPreferences
import com.example.fileopener.domain.model.ReaderSettings
import com.example.fileopener.domain.model.ReadingPosition
import com.example.fileopener.domain.model.RecentDocument
import com.example.fileopener.domain.repository.DocumentRecordsRepository
import com.example.fileopener.domain.repository.DocumentStorage
import com.example.fileopener.domain.repository.LibraryRepository
import com.example.fileopener.domain.repository.ReadingStateRepository
import com.example.fileopener.domain.repository.RecentRepository
import com.example.fileopener.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeLibraryRepository : LibraryRepository {
    private val folders = MutableStateFlow<Map<String, LibraryFolder>>(emptyMap())
    private val documents = MutableStateFlow<Map<String, List<DocumentItem>>>(emptyMap())

    override fun observeFolders(): Flow<List<LibraryFolder>> = folders.map { it.values.toList() }
    override fun observeDocuments(): Flow<List<DocumentItem>> = documents.map { it.values.flatten() }
    override suspend fun getFolders() = folders.value.values.toList()
    override suspend fun getFolderDocuments(treeUri: String) = documents.value[treeUri].orEmpty()
    override suspend fun addFolder(folder: LibraryFolder) {
        folders.update { it + (folder.treeUri to folder) }
    }
    override suspend fun removeFolder(treeUri: String) {
        folders.update { it - treeUri }
        documents.update { it - treeUri }
    }
    override suspend fun addDocuments(treeUri: String, documents: List<DocumentItem>) {
        val merged = (getFolderDocuments(treeUri) + documents).distinctBy { it.uri }
        this.documents.update { it + (treeUri to merged) }
    }
    override suspend fun replaceFolderDocuments(treeUri: String, documents: List<DocumentItem>) {
        this.documents.update { it + (treeUri to documents) }
    }
}

class FakeDocumentStorage : DocumentStorage {
    val trees = mutableMapOf<String, List<DocumentItem>>()
    val resolvable = mutableMapOf<String, DocumentItem>()
    var persistAllowed = true

    override suspend fun scanFolder(treeUri: String) = trees[treeUri]
    override suspend fun folderDisplayName(treeUri: String) = treeUri.substringAfterLast('/')
    override suspend fun resolve(uri: String, mimeTypeHint: String?) = resolvable[uri]
    override suspend fun importIncoming(uri: String, mimeTypeHint: String?): ImportResult =
        resolvable[uri]?.let { ImportResult.Success(it) } ?: ImportResult.Failed
    override fun persistAccess(uri: String) = persistAllowed
    override fun releaseAccess(uri: String) = Unit

    /** Файлы, которые провайдер разрешает менять (по URI). */
    val writable = mutableSetOf<String>()
    val deleted = mutableListOf<String>()

    override suspend fun rename(document: DocumentItem, newName: String): DocumentItem? {
        if (document.uri !in writable) return null
        return document.copy(uri = document.uri.substringBeforeLast('/') + "/" + newName, name = newName)
    }

    override suspend fun delete(document: DocumentItem): Boolean {
        if (document.uri !in writable) return false
        deleted += document.uri
        return true
    }
}

class FakeDocumentRecordsRepository : DocumentRecordsRepository {
    val replaced = mutableListOf<Pair<String, DocumentItem>>()
    val forgotten = mutableListOf<String>()

    override suspend fun replace(oldUri: String, document: DocumentItem) {
        replaced += oldUri to document
    }

    override suspend fun forget(uri: String) {
        forgotten += uri
    }
}

class FakeRecentRepository : RecentRepository {
    val opened = mutableListOf<DocumentItem>()

    override fun observeRecent(limit: Int): Flow<List<RecentDocument>> = MutableStateFlow(emptyList())
    override suspend fun markOpened(document: DocumentItem) {
        opened += document
    }
    override suspend fun remove(uri: String) = Unit
    override suspend fun clear() = Unit
}

class FakeReadingStateRepository : ReadingStateRepository {
    val positions = mutableMapOf<String, ReadingPosition>()
    private val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    private var nextId = 1L

    val allBookmarks: List<Bookmark> get() = bookmarks.value

    override suspend fun getPosition(documentUri: String) = positions[documentUri]
    override suspend fun savePosition(documentUri: String, position: ReadingPosition) {
        positions[documentUri] = position
    }
    override fun observeBookmarks(documentUri: String): Flow<List<Bookmark>> =
        bookmarks.map { list -> list.filter { it.documentUri == documentUri } }
    override suspend fun addBookmark(documentUri: String, title: String, position: ReadingPosition): Long {
        val id = nextId++
        bookmarks.update { it + Bookmark(id, documentUri, title, position, createdAt = 0) }
        return id
    }
    override suspend fun removeBookmark(id: Long) {
        bookmarks.update { list -> list.filterNot { it.id == id } }
    }
}

class FakeSettingsRepository : SettingsRepository {
    private val reader = MutableStateFlow(ReaderSettings())
    private val library = MutableStateFlow(LibraryPreferences())

    val currentReaderSettings: ReaderSettings get() = reader.value

    override val readerSettings: Flow<ReaderSettings> = reader
    override val libraryPreferences: Flow<LibraryPreferences> = library
    override suspend fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) = reader.update(transform)
    override suspend fun updateLibraryPreferences(transform: (LibraryPreferences) -> LibraryPreferences) =
        library.update(transform)
}
