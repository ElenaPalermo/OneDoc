package io.doccraftstudio.onedoc.data.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.createBitmap
import io.doccraftstudio.onedoc.domain.model.DocumentFormat
import io.doccraftstudio.onedoc.domain.model.DocumentItem
import io.doccraftstudio.onedoc.reader.pdf.PdfOpener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Миниатюры первой страницы PDF для библиотеки: память (LRU) → JPEG в [directory] → рендер Pdfium.
 * Ключ — URI, дата изменения и размер, поэтому изменённый файл получает новую миниатюру.
 */
class PdfThumbnailProvider(
    private val directory: File,
    private val opener: PdfOpener,
) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Файлы, которые не удалось отрисовать (пароль, повреждение): не пытаемся снова до перезапуска. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** Рендер одновременно не больше двух документов: быстрая прокрутка не должна забивать Pdfium и память. */
    private val renderPermits = Semaphore(MAX_PARALLEL_RENDERS)

    /** Миниатюра из памяти без ожидания — чтобы при прокрутке списка не мигала иконка. */
    fun cached(document: DocumentItem): Bitmap? =
        if (document.format == DocumentFormat.PDF) memory.get(key(document)) else null

    suspend fun thumbnail(document: DocumentItem): Bitmap? {
        if (document.format != DocumentFormat.PDF) return null
        val key = key(document)
        memory.get(key)?.let { return it }
        if (key in failed) return null
        return withContext(Dispatchers.IO) {
            val file = File(directory, "$key.jpg")
            val bitmap = BitmapFactory.decodeFile(file.path, DECODE_OPTIONS) ?: renderPermits.withPermit { render(document, key) }
                ?.also { save(it, file) }
            if (bitmap == null) failed += key else memory.put(key, bitmap)
            bitmap
        }
    }

    private suspend fun render(document: DocumentItem, key: String): Bitmap? = try {
        opener.open(document.uri, password = null).use { handle ->
            if (handle.pageCount == 0) return null
            val aspect = handle.pageSize(0).aspectRatio.coerceIn(MIN_ASPECT, MAX_ASPECT)
            val width = THUMBNAIL_WIDTH_PX
            val height = (width * aspect).toInt().coerceAtLeast(1)
            val page = handle.render(0, width, height)
            // Pdfium оставляет фон прозрачным, а JPEG прозрачность не хранит — подкладываем белый лист.
            createBitmap(width, height, Bitmap.Config.RGB_565).also { opaque ->
                Canvas(opaque).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(page, 0f, 0f, null)
                }
                page.recycle()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "Нет миниатюры для ${document.name}: ${e.message}")
        null
    }

    private fun save(bitmap: Bitmap, file: File) {
        runCatching {
            directory.mkdirs()
            val temp = File(directory, "${file.name}.part")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            temp.renameTo(file)
            prune()
        }
    }

    /** Старые миниатюры (удалённые и изменённые файлы) вытесняются по дате записи. */
    private fun prune() {
        val files = directory.listFiles() ?: return
        if (files.size <= MAX_DISK_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_DISK_FILES).forEach { it.delete() }
    }

    private fun key(document: DocumentItem): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${document.uri}|${document.lastModified}|${document.sizeBytes}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "DocFlowThumbnails"
        const val THUMBNAIL_WIDTH_PX = 360
        const val MIN_ASPECT = 0.5f
        const val MAX_ASPECT = 2f
        const val JPEG_QUALITY = 85
        const val MAX_PARALLEL_RENDERS = 2
        const val MAX_DISK_FILES = 500
        /** ~30 миниатюр в RGB_565: больше, чем помещается на экране сетки. */
        const val MEMORY_BYTES = 12 * 1024 * 1024
        val DECODE_OPTIONS = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    }
}
