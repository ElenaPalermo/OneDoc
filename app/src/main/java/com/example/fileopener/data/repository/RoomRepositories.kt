package com.example.fileopener.data.repository

import com.example.fileopener.data.db.BookmarkEntity
import com.example.fileopener.data.db.DocumentRecordsDao
import com.example.fileopener.data.db.LibraryDao
import com.example.fileopener.data.db.PositionEntity
import com.example.fileopener.data.db.ReadingStateDao
import com.example.fileopener.data.db.RecentDao
import com.example.fileopener.data.db.toColumns
import com.example.fileopener.data.db.toDomain
import com.example.fileopener.data.db.toEntity
import com.example.fileopener.data.db.toLibraryEntity
import com.example.fileopener.data.db.toRecentEntity
import com.example.fileopener.domain.model.Bookmark
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.model.LibraryFolder
import com.example.fileopener.domain.model.ReadingPosition
import com.example.fileopener.domain.model.RecentDocument
import com.example.fileopener.domain.repository.DocumentRecordsRepository
import com.example.fileopener.domain.repository.LibraryRepository
import com.example.fileopener.domain.repository.ReadingStateRepository
import com.example.fileopener.domain.repository.RecentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLibraryRepository(private val dao: LibraryDao) : LibraryRepository {
    override fun observeFolders(): Flow<List<LibraryFolder>> =
        dao.observeFolders().map { folders -> folders.map { it.toDomain() } }

    override fun observeDocuments(): Flow<List<DocumentItem>> =
        dao.observeDocuments().map { documents -> documents.map { it.toDomain() } }

    override suspend fun getFolders(): List<LibraryFolder> = dao.getFolders().map { it.toDomain() }

    override suspend fun getFolderDocuments(treeUri: String): List<DocumentItem> =
        dao.getFolderDocuments(treeUri).map { it.toDomain() }

    override suspend fun addFolder(folder: LibraryFolder) = dao.upsertFolder(folder.toEntity())

    override suspend fun removeFolder(treeUri: String) = dao.deleteFolder(treeUri)

    override suspend fun addDocuments(treeUri: String, documents: List<DocumentItem>) =
        dao.upsertDocuments(documents.map { it.toLibraryEntity(treeUri) })

    override suspend fun replaceFolderDocuments(treeUri: String, documents: List<DocumentItem>) =
        dao.replaceFolderDocuments(treeUri, documents.map { it.toLibraryEntity(treeUri) })
}

class RoomRecentRepository(
    private val dao: RecentDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecentRepository {
    override fun observeRecent(limit: Int): Flow<List<RecentDocument>> =
        dao.observeRecent(limit).map { recents -> recents.map { it.toDomain() } }

    override suspend fun markOpened(document: DocumentItem) =
        dao.upsert(document.toRecentEntity(openedAt = clock()))

    override suspend fun remove(uri: String) = dao.delete(uri)

    override suspend fun clear() = dao.clear()
}

class RoomDocumentRecordsRepository(private val dao: DocumentRecordsDao) : DocumentRecordsRepository {
    override suspend fun replace(oldUri: String, document: DocumentItem) =
        dao.replace(oldUri, document.uri, document.name, document.sizeBytes, document.lastModified)

    override suspend fun forget(uri: String) = dao.forget(uri)
}

class RoomReadingStateRepository(
    private val dao: ReadingStateDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadingStateRepository {
    override suspend fun getPosition(documentUri: String): ReadingPosition? =
        dao.getPosition(documentUri)?.position?.toDomain()

    override suspend fun savePosition(documentUri: String, position: ReadingPosition) =
        dao.upsertPosition(PositionEntity(documentUri, position.toColumns(), updatedAt = clock()))

    override fun observeBookmarks(documentUri: String): Flow<List<Bookmark>> =
        dao.observeBookmarks(documentUri).map { bookmarks -> bookmarks.map { it.toDomain() } }

    override suspend fun addBookmark(documentUri: String, title: String, position: ReadingPosition): Long =
        dao.insertBookmark(
            BookmarkEntity(
                documentUri = documentUri,
                title = title,
                position = position.toColumns(),
                createdAt = clock(),
            ),
        )

    override suspend fun removeBookmark(id: Long) = dao.deleteBookmark(id)
}
