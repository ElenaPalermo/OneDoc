package io.doccraftstudio.onedoc.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_folders ORDER BY display_name COLLATE NOCASE")
    fun observeFolders(): Flow<List<LibraryFolderEntity>>

    @Query("SELECT * FROM library_documents")
    fun observeDocuments(): Flow<List<LibraryDocumentEntity>>

    @Query("SELECT * FROM library_folders")
    suspend fun getFolders(): List<LibraryFolderEntity>

    @Query("SELECT * FROM library_documents WHERE folder_uri = :folderUri")
    suspend fun getFolderDocuments(folderUri: String): List<LibraryDocumentEntity>

    @Upsert
    suspend fun upsertFolder(folder: LibraryFolderEntity)

    /** Документы папки удаляются каскадно (внешний ключ). */
    @Query("DELETE FROM library_folders WHERE tree_uri = :treeUri")
    suspend fun deleteFolder(treeUri: String)

    @Query("DELETE FROM library_documents WHERE folder_uri = :folderUri")
    suspend fun deleteFolderDocuments(folderUri: String)

    @Upsert
    suspend fun upsertDocuments(documents: List<LibraryDocumentEntity>)

    @Transaction
    suspend fun replaceFolderDocuments(folderUri: String, documents: List<LibraryDocumentEntity>) {
        deleteFolderDocuments(folderUri)
        upsertDocuments(documents)
    }
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recent_documents ORDER BY last_opened_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentEntity>>

    @Upsert
    suspend fun upsert(recent: RecentEntity)

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recent_documents")
    suspend fun clear()
}

/** Записи одного документа во всех таблицах; ключ — URI документа. */
@Dao
interface DocumentRecordsDao {
    @Query(
        "UPDATE OR REPLACE library_documents SET uri = :newUri, name = :name, size_bytes = :sizeBytes, " +
            "last_modified = :lastModified WHERE uri = :oldUri",
    )
    suspend fun renameLibraryDocument(oldUri: String, newUri: String, name: String, sizeBytes: Long, lastModified: Long)

    @Query(
        "UPDATE OR REPLACE recent_documents SET uri = :newUri, name = :name, size_bytes = :sizeBytes, " +
            "last_modified = :lastModified WHERE uri = :oldUri",
    )
    suspend fun renameRecent(oldUri: String, newUri: String, name: String, sizeBytes: Long, lastModified: Long)

    @Query("UPDATE OR REPLACE reading_positions SET document_uri = :newUri WHERE document_uri = :oldUri")
    suspend fun movePosition(oldUri: String, newUri: String)

    @Query("UPDATE bookmarks SET document_uri = :newUri WHERE document_uri = :oldUri")
    suspend fun moveBookmarks(oldUri: String, newUri: String)

    @Query("DELETE FROM library_documents WHERE uri = :uri")
    suspend fun deleteLibraryDocument(uri: String)

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun deleteRecent(uri: String)

    @Query("DELETE FROM reading_positions WHERE document_uri = :uri")
    suspend fun deletePosition(uri: String)

    @Query("DELETE FROM bookmarks WHERE document_uri = :uri")
    suspend fun deleteBookmarks(uri: String)

    @Transaction
    suspend fun replace(oldUri: String, newUri: String, name: String, sizeBytes: Long, lastModified: Long) {
        renameLibraryDocument(oldUri, newUri, name, sizeBytes, lastModified)
        renameRecent(oldUri, newUri, name, sizeBytes, lastModified)
        if (newUri != oldUri) {
            movePosition(oldUri, newUri)
            moveBookmarks(oldUri, newUri)
        }
    }

    @Transaction
    suspend fun forget(uri: String) {
        deleteLibraryDocument(uri)
        deleteRecent(uri)
        deletePosition(uri)
        deleteBookmarks(uri)
    }
}

@Dao
interface ReadingStateDao {
    @Query("SELECT * FROM reading_positions WHERE document_uri = :documentUri")
    suspend fun getPosition(documentUri: String): PositionEntity?

    @Upsert
    suspend fun upsertPosition(position: PositionEntity)

    @Query("SELECT * FROM bookmarks WHERE document_uri = :documentUri ORDER BY sheet_index, item_index, item_offset")
    fun observeBookmarks(documentUri: String): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)
}
