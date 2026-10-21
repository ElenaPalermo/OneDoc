package com.example.fileopener.domain.repository

import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.model.ImportResult

/** Доступ к файлам через Storage Access Framework. Все методы безопасно вызывать с главного потока. */
interface DocumentStorage {
    /** Рекурсивно ищет поддерживаемые документы в папке; `null`, если доступ к папке потерян. */
    suspend fun scanFolder(treeUri: String): List<DocumentItem>?

    suspend fun folderDisplayName(treeUri: String): String?

    /** Метаданные документа или `null`, если файл недоступен либо формат не поддерживается. */
    suspend fun resolve(uri: String, mimeTypeHint: String? = null): DocumentItem?

    /**
     * Готовит файл из чужого Intent к повторному открытию: берёт persistable-доступ,
     * а если провайдер его не даёт (Telegram, WhatsApp, почта), копирует файл во внутреннее хранилище.
     */
    suspend fun importIncoming(uri: String, mimeTypeHint: String?): ImportResult

    /** Сохраняет доступ к URI после перезапуска (для результатов `OpenDocumentTree`/`OpenDocument`). */
    fun persistAccess(uri: String): Boolean

    fun releaseAccess(uri: String)

    /**
     * Переименовывает файл на устройстве. Возвращает документ с новыми метаданными (URI может
     * измениться) или `null`, если провайдер не умеет переименовывать или нет прав на запись.
     */
    suspend fun rename(document: DocumentItem, newName: String): DocumentItem?

    /** Удаляет файл с устройства; `false`, если удалить не удалось. */
    suspend fun delete(document: DocumentItem): Boolean
}
