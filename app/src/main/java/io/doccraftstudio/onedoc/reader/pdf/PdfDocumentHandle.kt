package io.doccraftstudio.onedoc.reader.pdf

import io.doccraftstudio.onedoc.reader.TocEntry
import android.graphics.Bitmap
import java.io.Closeable
import java.io.IOException

/** Размер страницы в пунктах PDF (1/72 дюйма) с учётом поворота страницы. */
data class PageSize(val widthPt: Float, val heightPt: Float) {
    /** Высота относительно ширины; по ней раскладываются страницы до рендера. */
    val aspectRatio: Float get() = if (widthPt > 0f) heightPt / widthPt else DEFAULT_ASPECT

    companion object {
        /** A4 — пока размер страницы неизвестен. */
        private const val DEFAULT_ASPECT = 297f / 210f
        val A4 = PageSize(595f, 842f)
    }
}

/** Прямоугольник в долях страницы (0..1), начало координат — левый верхний угол. */
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Одно совпадение поиска: страница и прямоугольники (по одному на строку, если совпадение переносится). */
data class PdfSearchMatch(val pageIndex: Int, val rects: List<NormalizedRect>)

/** Документ защищён паролем; [wrongPassword] — пароль был введён, но не подошёл. */
class PdfPasswordRequiredException(val wrongPassword: Boolean) : IOException("PDF защищён паролем")

/**
 * Открытый PDF-документ. Абстракция над Pdfium нужна, чтобы логику поиска и просмотрщика
 * можно было тестировать на JVM без нативной библиотеки.
 */
interface PdfDocumentHandle : Closeable {
    val pageCount: Int

    suspend fun pageSize(pageIndex: Int): PageSize

    /**
     * Рендерит страницу, отмасштабированную до [pageWidthPx]×[pageHeightPx], в битмап [bitmapWidth]×[bitmapHeight].
     * [offsetX]/[offsetY] — левый верхний угол фрагмента внутри страницы: так рисуется только видимая
     * часть сильно увеличенной страницы в полном разрешении.
     */
    suspend fun render(
        pageIndex: Int,
        pageWidthPx: Int,
        pageHeightPx: Int,
        bitmapWidth: Int = pageWidthPx,
        bitmapHeight: Int = pageHeightPx,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ): Bitmap

    suspend fun tableOfContents(): List<TocEntry>

    /** Все совпадения [query] на странице без учёта регистра; по списку прямоугольников на совпадение. */
    suspend fun findOnPage(pageIndex: Int, query: String): List<List<NormalizedRect>>
}

fun interface PdfOpener {
    /** @throws PdfPasswordRequiredException если нужен (или не подошёл) пароль. */
    suspend fun open(uri: String, password: String?): PdfDocumentHandle
}
