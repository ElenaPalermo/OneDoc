package com.example.fileopener.data.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import com.example.fileopener.domain.model.DocumentFormat
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.model.ImportResult
import com.example.fileopener.domain.repository.DocumentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

class SafDocumentStorage(context: Context) : DocumentStorage {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    /** Копии вложений из мессенджеров и почты; в filesDir, чтобы система не чистила их как кэш. */
    private val incomingDir = File(appContext.filesDir, "incoming")

    override suspend fun scanFolder(treeUri: String): List<DocumentItem>? = withContext(Dispatchers.IO) {
        val tree = treeUri.toUri()
        val result = mutableListOf<DocumentItem>()
        // Обход в ширину: глубокие деревья не грозят переполнением стека.
        val pending = ArrayDeque<Pair<String, Int>>()
        try {
            pending.add(DocumentsContract.getTreeDocumentId(tree) to 0)
            while (pending.isNotEmpty()) {
                ensureActive()
                val (parentId, depth) = pending.removeFirst()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val mimeType = cursor.getString(2)
                        if (name.startsWith('.')) continue
                        if (mimeType == Document.MIME_TYPE_DIR) {
                            if (depth < MAX_SCAN_DEPTH) pending.add(documentId to depth + 1)
                            continue
                        }
                        val format = DocumentFormat.detect(name, mimeType) ?: continue
                        result += DocumentItem(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString(),
                            name = name,
                            format = format,
                            sizeBytes = cursor.getLongOrZero(3),
                            lastModified = cursor.getLongOrZero(4),
                        )
                    }
                }
            }
            result
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: FileNotFoundException) {
            null
        }
    }

    override suspend fun folderDisplayName(treeUri: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val tree = treeUri.toUri()
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(rootUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    override suspend fun resolve(uri: String, mimeTypeHint: String?): DocumentItem? = withContext(Dispatchers.IO) {
        when (val result = query(uri, mimeTypeHint)) {
            is QueryResult.Found -> result.document
            else -> null
        }
    }

    private sealed interface QueryResult {
        data class Found(val document: DocumentItem) : QueryResult
        /** Файл доступен, но формат не поддерживается. */
        data class UnsupportedFormat(val name: String?) : QueryResult
        /** Нет доступа, файла нет или провайдер не вернул данных. */
        data object Inaccessible : QueryResult
    }

    private fun query(uri: String, mimeTypeHint: String?): QueryResult {
        val parsed = uri.toUri()
        if (parsed.scheme == ContentResolver.SCHEME_FILE) {
            return resolveFile(parsed)?.let(QueryResult::Found) ?: QueryResult.UnsupportedFormat(parsed.lastPathSegment)
        }
        return try {
            // Проекция null: не все провайдеры (почта, мессенджеры) поддерживают COLUMN_LAST_MODIFIED.
            resolver.query(parsed, null, null, null, null)?.use { cursor ->
                // MediaProvider без прав не бросает исключение, а возвращает пустой результат.
                if (!cursor.moveToFirst()) return@use QueryResult.Inaccessible
                // MediaStore на Android 8 отдаёт пустой DISPLAY_NAME (`content://media/external/file/N`),
                // но заполняет TITLE — имя без расширения.
                val name = cursor.stringColumn(OpenableColumns.DISPLAY_NAME)
                    ?: cursor.stringColumn(MediaStore.MediaColumns.TITLE)
                    ?: parsed.lastPathSegment
                val mimeType = runCatching { resolver.getType(parsed) }.getOrNull() ?: mimeTypeHint
                val format = DocumentFormat.detect(name, mimeType) ?: return@use QueryResult.UnsupportedFormat(name)
                QueryResult.Found(
                    DocumentItem(
                        uri = uri,
                        name = format.fileName(name),
                        format = format,
                        sizeBytes = cursor.longColumn(OpenableColumns.SIZE) ?: 0L,
                        lastModified = cursor.longColumn(Document.COLUMN_LAST_MODIFIED) ?: 0L,
                    ),
                )
            } ?: QueryResult.Inaccessible
        } catch (e: SecurityException) {
            Log.w(TAG, "Нет доступа к $uri", e)
            QueryResult.Inaccessible
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Провайдер не понимает $uri", e)
            QueryResult.Inaccessible
        }
    }

    override suspend fun importIncoming(uri: String, mimeTypeHint: String?): ImportResult =
        withContext(Dispatchers.IO) {
            val parsed = uri.toUri()
            val document = when (val result = query(uri, mimeTypeHint)) {
                is QueryResult.Found -> result.document
                is QueryResult.UnsupportedFormat -> return@withContext ImportResult.Unsupported(result.name)
                QueryResult.Inaccessible -> return@withContext ImportResult.Failed
            }
            if (parsed.scheme == ContentResolver.SCHEME_FILE) return@withContext ImportResult.Success(document)
            if (DocumentsContract.isDocumentUri(appContext, parsed) && persistAccess(uri)) {
                return@withContext ImportResult.Success(document)
            }
            copyToIncoming(parsed, document)?.let { ImportResult.Success(it) } ?: ImportResult.Failed
        }

    override fun persistAccess(uri: String): Boolean {
        val parsed = uri.toUri()
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Запись нужна для переименования и удаления; если провайдер её не дал — хватит чтения.
        return runCatching { resolver.takePersistableUriPermission(parsed, readWrite) }
            .recoverCatching { resolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .isSuccess
    }

    override fun releaseAccess(uri: String) {
        val parsed = uri.toUri()
        resolver.persistedUriPermissions
            .filter { it.uri == parsed }
            .forEach { permission ->
                val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                runCatching { resolver.releasePersistableUriPermission(parsed, flags) }
            }
    }

    override suspend fun rename(document: DocumentItem, newName: String): DocumentItem? =
        withContext(Dispatchers.IO) {
            val parsed = document.uri.toUri()
            val newUri = try {
                when {
                    parsed.scheme == ContentResolver.SCHEME_FILE -> {
                        val file = File(parsed.path ?: return@withContext null)
                        val target = File(file.parentFile, newName.replace(UNSAFE_FILE_NAME_CHARS, "_"))
                        if (target.exists() || !file.renameTo(target)) return@withContext null
                        Uri.fromFile(target)
                    }
                    DocumentsContract.isDocumentUri(appContext, parsed) ->
                        DocumentsContract.renameDocument(resolver, parsed, newName) ?: return@withContext null
                    else -> return@withContext null
                }
            } catch (e: Exception) {
                // Провайдеры сообщают об отказе по-разному: FileNotFound, UnsupportedOperation, IllegalState…
                Log.w(TAG, "Не удалось переименовать ${document.uri}", e)
                return@withContext null
            }
            // Файл, добавленный по одному, при смене URI получает от провайдера только временный доступ;
            // в папке библиотеки доступ к новому URI даёт разрешение на всё дерево.
            if (newUri != parsed) persistAccess(newUri.toString())
            (query(newUri.toString(), null) as? QueryResult.Found)?.document
                ?: document.copy(uri = newUri.toString(), name = newName)
        }

    override suspend fun delete(document: DocumentItem): Boolean = withContext(Dispatchers.IO) {
        val parsed = document.uri.toUri()
        val deleted = try {
            when {
                parsed.scheme == ContentResolver.SCHEME_FILE -> {
                    val file = File(parsed.path ?: return@withContext false)
                    file.delete().also {
                        // Копия вложения лежит в своей папке incoming/<хэш>/ — убираем и её.
                        val parent = file.parentFile
                        if (it && parent != null && parent.parentFile == incomingDir) parent.delete()
                    }
                }
                DocumentsContract.isDocumentUri(appContext, parsed) -> DocumentsContract.deleteDocument(resolver, parsed)
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось удалить ${document.uri}", e)
            false
        }
        if (deleted) releaseAccess(document.uri)
        deleted
    }

    /**
     * Копирует файл в `filesDir/incoming/<хэш имени и размера>/<имя>`.
     * Повторное открытие того же вложения переиспользует копию вместо дублирования.
     */
    private fun copyToIncoming(source: Uri, document: DocumentItem): DocumentItem? {
        val safeName = document.name.replace(UNSAFE_FILE_NAME_CHARS, "_")
        val target = File(File(incomingDir, copyKey(document)), safeName)
        val reused = target.exists() && document.sizeBytes > 0 && target.length() == document.sizeBytes
        if (!reused) {
            try {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "$safeName.part")
                resolver.openInputStream(source)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return null
                }
            } catch (e: IOException) {
                return null
            } catch (e: SecurityException) {
                return null
            }
        }
        return document.copy(
            uri = Uri.fromFile(target).toString(),
            sizeBytes = target.length(),
            lastModified = document.lastModified.takeIf { it > 0 } ?: target.lastModified(),
        )
    }

    private fun resolveFile(uri: Uri): DocumentItem? {
        val file = File(uri.path ?: return null)
        if (!file.isFile) return null
        val format = DocumentFormat.fromFileName(file.name) ?: return null
        return DocumentItem(uri.toString(), file.name, format, file.length(), file.lastModified())
    }


    private fun copyKey(document: DocumentItem): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${document.name}:${document.sizeBytes}".toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun Cursor.getLongOrZero(index: Int): Long = if (isNull(index)) 0L else getLong(index)

    private fun Cursor.stringColumn(name: String): String? =
        getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.longColumn(name: String): Long? =
        getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private companion object {
        const val TAG = "DocFlowStorage"
        const val MAX_SCAN_DEPTH = 12
        val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000]")
        val CHILD_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
