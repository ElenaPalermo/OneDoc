package io.doccraftstudio.onedoc.reader.pdf

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Ищет [query] по всем страницам по порядку; выдаёт совпадения постранично, как только страница обработана.
 * [guard] оборачивает каждое обращение к документу (блокировка от закрытия во время поиска).
 */
fun PdfDocumentHandle.searchPages(
    query: String,
    guard: suspend (suspend () -> List<List<NormalizedRect>>) -> List<List<NormalizedRect>> = { it() },
): Flow<List<PdfSearchMatch>> = flow {
    for (pageIndex in 0 until pageCount) {
        val matches = guard { findOnPage(pageIndex, query) }
        if (matches.isNotEmpty()) emit(matches.map { PdfSearchMatch(pageIndex, it) })
    }
}

