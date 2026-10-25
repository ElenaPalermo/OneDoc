package io.doccraftstudio.onedoc.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.doccraftstudio.onedoc.domain.model.Bookmark
import io.doccraftstudio.onedoc.domain.model.DocumentFormat
import io.doccraftstudio.onedoc.domain.model.DocumentItem
import io.doccraftstudio.onedoc.domain.model.LibraryFolder
import io.doccraftstudio.onedoc.domain.model.ReadingPosition
import io.doccraftstudio.onedoc.domain.model.RecentDocument

@Entity(tableName = "library_folders")
data class LibraryFolderEntity(
    @PrimaryKey @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(
    tableName = "library_documents",
    foreignKeys = [
        ForeignKey(
            entity = LibraryFolderEntity::class,
            parentColumns = ["tree_uri"],
            childColumns = ["folder_uri"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("folder_uri")],
)
data class LibraryDocumentEntity(
    @PrimaryKey val uri: String,
    @ColumnInfo(name = "folder_uri") val folderUri: String,
    val name: String,
    val format: DocumentFormat,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
)

@Entity(tableName = "recent_documents", indices = [Index("last_opened_at")])
data class RecentEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val format: DocumentFormat,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long,
)

/** Колонки позиции чтения; встраиваются в [PositionEntity] и [BookmarkEntity]. */
data class PositionColumns(
    @ColumnInfo(name = "item_index") val index: Int,
    @ColumnInfo(name = "item_offset") val offset: Int,
    @ColumnInfo(name = "sheet_index") val sheet: Int,
    @ColumnInfo(name = "first_column") val column: Int,
    val zoom: Float,
)

@Entity(tableName = "reading_positions")
data class PositionEntity(
    @PrimaryKey @ColumnInfo(name = "document_uri") val documentUri: String,
    @Embedded val position: PositionColumns,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "bookmarks", indices = [Index("document_uri")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "document_uri") val documentUri: String,
    val title: String,
    @Embedded val position: PositionColumns,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

fun LibraryFolderEntity.toDomain() = LibraryFolder(treeUri, displayName, addedAt)

fun LibraryFolder.toEntity() = LibraryFolderEntity(treeUri, displayName, addedAt)

fun LibraryDocumentEntity.toDomain() = DocumentItem(uri, name, format, sizeBytes, lastModified)

fun DocumentItem.toLibraryEntity(folderUri: String) =
    LibraryDocumentEntity(uri, folderUri, name, format, sizeBytes, lastModified)

fun RecentEntity.toDomain() = RecentDocument(
    document = DocumentItem(uri, name, format, sizeBytes, lastModified),
    lastOpenedAt = lastOpenedAt,
)

fun DocumentItem.toRecentEntity(openedAt: Long) =
    RecentEntity(uri, name, format, sizeBytes, lastModified, openedAt)

fun PositionColumns.toDomain() = ReadingPosition(index, offset, sheet, column, zoom)

fun ReadingPosition.toColumns() = PositionColumns(index, offset, sheet, column, zoom)

fun BookmarkEntity.toDomain() = Bookmark(id, documentUri, title, position.toDomain(), createdAt)
