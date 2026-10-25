package io.doccraftstudio.onedoc.domain.usecase

import io.doccraftstudio.onedoc.domain.model.DocumentFormat
import io.doccraftstudio.onedoc.domain.model.DocumentItem
import io.doccraftstudio.onedoc.domain.repository.DocumentRecordsRepository
import io.doccraftstudio.onedoc.domain.repository.DocumentStorage

sealed interface RenameResult {
    data class Success(val document: DocumentItem) : RenameResult
    /** Имя пустое или содержит недопустимые символы. */
    data object InvalidName : RenameResult
    /** Провайдер отказал: нет прав на запись, файл с таким именем уже есть или файл недоступен. */
    data object Failed : RenameResult
}

/** Переименовывает файл и переносит на новое имя историю, позицию чтения и закладки. */
class RenameDocumentUseCase(
    private val storage: DocumentStorage,
    private val records: DocumentRecordsRepository,
) {
    suspend operator fun invoke(document: DocumentItem, input: String): RenameResult {
        val newName = normalizeName(document, input) ?: return RenameResult.InvalidName
        if (newName == document.name) return RenameResult.Success(document)
        val renamed = storage.rename(document, newName) ?: return RenameResult.Failed
        records.replace(document.uri, renamed)
        return RenameResult.Success(renamed)
    }

    companion object {
        private val FORBIDDEN_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

        /**
         * Имя файла из ввода пользователя или `null`, если оно недопустимо. Если пользователь стёр
         * или поменял расширение, возвращается исходное — иначе файл перестанет открываться.
         */
        fun normalizeName(document: DocumentItem, input: String): String? {
            val name = input.trim()
            if (name.isEmpty() || name == "." || name == ".." || FORBIDDEN_CHARS.containsMatchIn(name)) return null
            if (DocumentFormat.fromFileName(name) == document.format) return name
            val extension = document.name.substringAfterLast('.', missingDelimiterValue = "")
                .ifEmpty { document.format.extensions.first() }
            return "$name.$extension"
        }
    }
}

/** Удаляет файл с устройства и все записи о нём. */
class DeleteDocumentUseCase(
    private val storage: DocumentStorage,
    private val records: DocumentRecordsRepository,
) {
    suspend operator fun invoke(document: DocumentItem): Boolean {
        if (!storage.delete(document)) return false
        records.forget(document.uri)
        return true
    }
}
