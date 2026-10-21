package com.example.fileopener.reader.pdf

import com.example.fileopener.reader.TocEntry
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import io.legere.pdfiumandroid.api.Bookmark
import io.legere.pdfiumandroid.api.PdfPasswordException
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import io.legere.pdfiumandroid.suspend.PdfPageKt
import io.legere.pdfiumandroid.suspend.PdfiumCoreKt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/** Открывает PDF через Pdfium. Один экземпляр ядра на приложение. */
class PdfiumOpener(context: Context) : PdfOpener {
    private val resolver = context.applicationContext.contentResolver
    private val core by lazy { PdfiumCoreKt(Dispatchers.IO) }

    override suspend fun open(uri: String, password: String?): PdfDocumentHandle = withContext(Dispatchers.IO) {
        // Pdfium читает файл произвольным доступом, поэтому нужен дескриптор, а не поток.
        val descriptor = resolver.openFileDescriptor(uri.toUri(), "r")
            ?: throw FileNotFoundException("Провайдер не вернул дескриптор для $uri")
        try {
            val document = if (password == null) core.newDocument(descriptor) else core.newDocument(descriptor, password)
            PdfiumDocumentHandle(document, descriptor, document.getPageCount())
        } catch (e: PdfPasswordException) {
            descriptor.close()
            throw PdfPasswordRequiredException(wrongPassword = password != null)
        } catch (e: Throwable) {
            descriptor.close()
            throw e
        }
    }
}

class PdfiumDocumentHandle(
    private val document: PdfDocumentKt,
    private val descriptor: ParcelFileDescriptor,
    override val pageCount: Int,
) : PdfDocumentHandle {

    override suspend fun pageSize(pageIndex: Int): PageSize = openPage(pageIndex).use { page ->
        PageSize(page.getPageWidthPoint().toFloat(), page.getPageHeightPoint().toFloat())
    }

    override suspend fun render(
        pageIndex: Int,
        pageWidthPx: Int,
        pageHeightPx: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        offsetX: Int,
        offsetY: Int,
    ): Bitmap {
        val bitmap = createBitmap(bitmapWidth, bitmapHeight)
        openPage(pageIndex).use { page ->
            page.renderPageBitmap(
                bitmap = bitmap,
                startX = -offsetX,
                startY = -offsetY,
                drawSizeX = pageWidthPx,
                drawSizeY = pageHeightPx,
                renderAnnot = true,
            )
        }
        return bitmap
    }

    override suspend fun tableOfContents(): List<TocEntry> =
        buildList { flatten(document.getTableOfContents(), level = 0) }

    private fun MutableList<TocEntry>.flatten(bookmarks: List<Bookmark>, level: Int) {
        for (bookmark in bookmarks) {
            val title = bookmark.title?.trim().orEmpty()
            if (title.isNotEmpty() && bookmark.pageIdx >= 0) add(TocEntry(title, bookmark.pageIdx.toInt(), level))
            flatten(bookmark.children, level + 1)
        }
    }

    override suspend fun findOnPage(pageIndex: Int, query: String): List<List<NormalizedRect>> =
        openPage(pageIndex).use { page ->
            page.openTextPage().use { text ->
                val result = text.findStart(query, emptySet(), 0) ?: return@use emptyList()
                result.use { find ->
                    buildList {
                        while (find.findNext()) {
                            val start = find.getSchResultIndex()
                            val count = find.getSchCount()
                            if (start < 0 || count <= 0) continue
                            val rects = (0 until text.textPageCountRects(start, count)).mapNotNull { index ->
                                val pageRect = text.textPageGetRect(index) ?: return@mapNotNull null
                                // Перевод из пунктов PDF (ось Y вверх, поворот страницы) в доли страницы
                                // той же функцией Pdfium, что используется при рендере, — подсветка совпадает с текстом.
                                val device = page.mapRectToDevice(0, 0, NORMALIZED_SIZE, NORMALIZED_SIZE, 0, pageRect)
                                NormalizedRect(
                                    left = minOf(device.left, device.right) / NORMALIZED_SIZE.toFloat(),
                                    top = minOf(device.top, device.bottom) / NORMALIZED_SIZE.toFloat(),
                                    right = maxOf(device.left, device.right) / NORMALIZED_SIZE.toFloat(),
                                    bottom = maxOf(device.top, device.bottom) / NORMALIZED_SIZE.toFloat(),
                                )
                            }
                            if (rects.isNotEmpty()) add(rects)
                        }
                    }
                }
            }
        }

    private suspend fun openPage(pageIndex: Int): PdfPageKt =
        document.openPage(pageIndex) ?: throw IOException("Не удалось открыть страницу ${pageIndex + 1}")

    override fun close() {
        document.close()
        descriptor.close()
    }

    private companion object {
        /** «Виртуальный экран» для перевода координат; большое число — меньше ошибка округления до int. */
        const val NORMALIZED_SIZE = 100_000
    }
}
