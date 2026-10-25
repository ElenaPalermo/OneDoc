package io.doccraftstudio.onedoc.fakes

import android.graphics.Bitmap
import io.doccraftstudio.onedoc.reader.pdf.NormalizedRect
import io.doccraftstudio.onedoc.reader.pdf.PageSize
import io.doccraftstudio.onedoc.reader.pdf.PdfDocumentHandle
import io.doccraftstudio.onedoc.reader.pdf.PdfOpener
import io.doccraftstudio.onedoc.reader.pdf.PdfPasswordRequiredException
import io.doccraftstudio.onedoc.reader.TocEntry

/** PDF без нативного Pdfium: [matches] — совпадения поиска по номерам страниц (для любого запроса). */
class FakePdfHandle(
    override val pageCount: Int,
    private val toc: List<TocEntry> = emptyList(),
    private val matches: Map<Int, List<List<NormalizedRect>>> = emptyMap(),
) : PdfDocumentHandle {
    var closed = false
    val searchedPages = mutableListOf<Int>()

    override suspend fun pageSize(pageIndex: Int) = PageSize(600f, 800f)

    override suspend fun render(
        pageIndex: Int,
        pageWidthPx: Int,
        pageHeightPx: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        offsetX: Int,
        offsetY: Int,
    ): Bitmap = throw UnsupportedOperationException("Рендер в JVM-тестах недоступен")

    override suspend fun tableOfContents() = toc

    override suspend fun findOnPage(pageIndex: Int, query: String): List<List<NormalizedRect>> {
        searchedPages += pageIndex
        return matches[pageIndex].orEmpty()
    }

    override fun close() {
        closed = true
    }
}

/** Открывает [handle]; если задан [password], без него (или с другим) требует пароль. */
class FakePdfOpener(private val handle: FakePdfHandle, private val password: String? = null) : PdfOpener {
    override suspend fun open(uri: String, password: String?): PdfDocumentHandle {
        if (this.password != null && password != this.password) {
            throw PdfPasswordRequiredException(wrongPassword = password != null)
        }
        return handle
    }
}

fun rect(top: Float) = NormalizedRect(left = 0.1f, top = top, right = 0.3f, bottom = top + 0.02f)
