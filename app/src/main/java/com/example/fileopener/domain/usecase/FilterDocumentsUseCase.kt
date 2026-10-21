package com.example.fileopener.domain.usecase

import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.model.LibraryPreferences
import com.example.fileopener.domain.model.SortField

/** Фильтр по типу, поиск по имени и сортировка списка документов библиотеки. */
class FilterDocumentsUseCase {
    operator fun invoke(
        documents: List<DocumentItem>,
        preferences: LibraryPreferences,
        query: String = "",
    ): List<DocumentItem> {
        val trimmedQuery = query.trim()
        val filtered = documents.filter { document ->
            (preferences.categoryFilter.isEmpty() || document.format.category in preferences.categoryFilter) &&
                (trimmedQuery.isEmpty() || document.name.contains(trimmedQuery, ignoreCase = true))
        }
        val comparator: Comparator<DocumentItem> = when (preferences.sortField) {
            SortField.DATE -> compareBy { it.lastModified }
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.SIZE -> compareBy { it.sizeBytes }
        }
        return filtered.sortedWith(if (preferences.sortDescending) comparator.reversed() else comparator)
    }
}
