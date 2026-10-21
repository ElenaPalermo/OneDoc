package com.example.fileopener.domain.repository

import com.example.fileopener.domain.model.Bookmark
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.model.LibraryFolder
import com.example.fileopener.domain.model.LibraryPreferences
import com.example.fileopener.domain.model.ReaderSettings
import com.example.fileopener.domain.model.ReadingPosition
import com.example.fileopener.domain.model.RecentDocument
import kotlinx.coroutines.flow.Flow

/** Индекс библиотеки: выбранные папки и найденные в них документы. */
interface LibraryRepository {
    fun observeFolders(): Flow<List<LibraryFolder>>
    fun observeDocuments(): Flow<List<DocumentItem>>
    suspend fun getFolders(): List<LibraryFolder>
    suspend fun getFolderDocuments(treeUri: String): List<DocumentItem>
    suspend fun addFolder(folder: LibraryFolder)
    suspend fun removeFolder(treeUri: String)
    suspend fun addDocuments(treeUri: String, documents: List<DocumentItem>)

    /** Атомарно заменяет содержимое папки результатом нового сканирования. */
    suspend fun replaceFolderDocuments(treeUri: String, documents: List<DocumentItem>)
}

/** История открытых документов (вкладка «Недавние»). */
interface RecentRepository {
    fun observeRecent(limit: Int = DEFAULT_LIMIT): Flow<List<RecentDocument>>
    suspend fun markOpened(document: DocumentItem)
    suspend fun remove(uri: String)
    suspend fun clear()

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}

/** Позиции чтения и закладки, привязанные к URI документа. */
interface ReadingStateRepository {
    suspend fun getPosition(documentUri: String): ReadingPosition?
    suspend fun savePosition(documentUri: String, position: ReadingPosition)
    fun observeBookmarks(documentUri: String): Flow<List<Bookmark>>
    suspend fun addBookmark(documentUri: String, title: String, position: ReadingPosition): Long
    suspend fun removeBookmark(id: Long)
}

/** Все записи о документе: индекс библиотеки, «Недавние», позиция чтения и закладки. */
interface DocumentRecordsRepository {
    /** Переносит записи с [oldUri] на [document] (после переименования). */
    suspend fun replace(oldUri: String, document: DocumentItem)

    /** Удаляет все записи о документе (после удаления файла). */
    suspend fun forget(uri: String)
}

interface SettingsRepository {
    val readerSettings: Flow<ReaderSettings>
    val libraryPreferences: Flow<LibraryPreferences>
    suspend fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings)
    suspend fun updateLibraryPreferences(transform: (LibraryPreferences) -> LibraryPreferences)
}
