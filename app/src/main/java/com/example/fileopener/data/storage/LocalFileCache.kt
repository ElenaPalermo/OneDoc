package com.example.fileopener.data.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Даёт локальный файл для URI: file:// — как есть, content:// — копия в кэше.
 * Нужна форматам, которые читаются с произвольным доступом (DOCX/XLSX как ZIP).
 * Хранятся только несколько последних копий.
 */
class LocalFileCache(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.cacheDir, "documents")

    suspend fun localFile(uri: String): File = withContext(Dispatchers.IO) {
        val parsed = uri.toUri()
        if (parsed.scheme == ContentResolver.SCHEME_FILE) return@withContext File(checkNotNull(parsed.path))

        directory.mkdirs()
        // Имя копии зависит от размера и даты изменения: неизменённый документ (50–100 МБ) не копируется повторно.
        val version = documentVersion(parsed)
        val target = File(directory, cacheName(uri + version.orEmpty()))
        if (version != null && target.exists()) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        val temp = File(directory, "${target.name}.part")
        // Без размера и даты изменения от провайдера копируем каждый раз: документ мог измениться.
        resolver.openInputStream(parsed)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            ?: throw FileNotFoundException("Провайдер не вернул поток для $uri")
        target.delete()
        if (!temp.renameTo(target)) throw FileNotFoundException("Не удалось сохранить копию $uri")
        prune(keep = target)
        target
    }

    private fun prune(keep: File) {
        directory.listFiles()
            ?.filter { it != keep && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_FILES - 1)
            ?.forEach { it.delete() }
    }

    /** «размер:дата изменения» от провайдера или `null`, если он их не сообщает. */
    private fun documentVersion(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                val modified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                if (size != null && modified != null && modified > 0) "$size:$modified" else null
            }
    }.getOrNull()

    private fun cacheName(uri: String): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_CACHED_FILES = 4
    }
}
