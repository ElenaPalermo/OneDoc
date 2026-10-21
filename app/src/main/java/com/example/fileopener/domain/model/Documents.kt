package com.example.fileopener.domain.model

/**
 * Документ, известный приложению. [uri] — content:// URI с persistable-доступом через SAF
 * либо file:// URI копии во внутреннем хранилище (для вложений из мессенджеров и почты).
 */
data class DocumentItem(
    val uri: String,
    val name: String,
    val format: DocumentFormat,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** Папка, выбранная пользователем через SAF (`OpenDocumentTree`) для автосканирования. */
data class LibraryFolder(
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
) {
    val isSingleFiles: Boolean get() = treeUri == SINGLE_FILES_URI

    companion object {
        /** Псевдопапка для файлов, добавленных по одному через `OpenMultipleDocuments`. */
        const val SINGLE_FILES_URI = "docflow://single-files"
    }
}

/** Результат открытия файла, пришедшего из другого приложения. */
sealed interface ImportResult {
    data class Success(val document: DocumentItem) : ImportResult
    data class Unsupported(val fileName: String?) : ImportResult
    data object Failed : ImportResult
}

data class RecentDocument(
    val document: DocumentItem,
    val lastOpenedAt: Long,
)

/**
 * Позиция чтения, общая для всех форматов.
 *
 * @property index страница PDF, индекс блока текста или строка таблицы.
 * @property offset смещение внутри элемента [index]; единица зависит от формата: пиксели для текста,
 *   1/10000 высоты страницы для PDF (не зависит от масштаба и поворота экрана).
 * @property sheet индекс листа (только таблицы).
 * @property column первая видимая колонка (только таблицы).
 * @property zoom масштаб просмотра.
 */
data class ReadingPosition(
    val index: Int = 0,
    val offset: Int = 0,
    val sheet: Int = 0,
    val column: Int = 0,
    val zoom: Float = 1f,
)

data class Bookmark(
    val id: Long,
    val documentUri: String,
    val title: String,
    val position: ReadingPosition,
    val createdAt: Long,
)
