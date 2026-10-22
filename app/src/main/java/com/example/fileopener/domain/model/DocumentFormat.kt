package com.example.fileopener.domain.model

/** Группа форматов: определяет просмотрщик, иконку и фильтр в библиотеке. */
enum class DocumentCategory { PDF, WORD, SPREADSHEET, TEXT }

/** Поддерживаемые форматы файлов с расширениями и MIME-типами. */
enum class DocumentFormat(
    val category: DocumentCategory,
    val extensions: Set<String>,
    val mimeTypes: Set<String>,
) {
    PDF(DocumentCategory.PDF, setOf("pdf"), setOf("application/pdf")),
    DOCX(
        DocumentCategory.WORD,
        setOf("docx"),
        setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ),
    DOC(DocumentCategory.WORD, setOf("doc"), setOf("application/msword")),
    XLSX(
        DocumentCategory.SPREADSHEET,
        setOf("xlsx"),
        setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ),
    XLS(DocumentCategory.SPREADSHEET, setOf("xls"), setOf("application/vnd.ms-excel")),
    CSV(
        DocumentCategory.SPREADSHEET,
        setOf("csv"),
        setOf("text/csv", "text/comma-separated-values", "text/x-csv", "application/csv"),
    ),
    TXT(DocumentCategory.TEXT, setOf("txt"), setOf("text/plain"));

    /**
     * Имя файла с расширением этого формата. Нужно, когда формат определён по MIME, а имени нет
     * или в нём нет расширения: копия вложения во внутреннем хранилище опознаётся только по имени.
     */
    fun fileName(name: String?): String {
        val base = name?.takeIf { it.isNotBlank() } ?: this.name.lowercase()
        return if (fromFileName(base) == this) base else "$base.${extensions.first()}"
    }

    companion object {
        fun fromFileName(fileName: String): DocumentFormat? {
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (extension.isEmpty()) return null
            return entries.firstOrNull { extension in it.extensions }
        }

        fun fromMimeType(mimeType: String): DocumentFormat? {
            val normalized = mimeType.substringBefore(';').trim().lowercase()
            return entries.firstOrNull { normalized in it.mimeTypes }
        }

        /**
         * Расширение проверяется первым: мессенджеры и почта часто отдают
         * `application/octet-stream` или `text/plain` для CSV.
         */
        fun detect(fileName: String?, mimeType: String?): DocumentFormat? =
            fileName?.let(::fromFileName) ?: mimeType?.let(::fromMimeType)
    }
}
