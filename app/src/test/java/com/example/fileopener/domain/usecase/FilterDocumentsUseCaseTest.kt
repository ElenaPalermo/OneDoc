package com.example.fileopener.domain.usecase

import com.example.fileopener.domain.model.DocumentCategory
import com.example.fileopener.domain.model.DocumentFormat
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.model.LibraryPreferences
import com.example.fileopener.domain.model.SortField
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterDocumentsUseCaseTest {
    private val filter = FilterDocumentsUseCase()

    private val pdf = doc("Отчёт.pdf", DocumentFormat.PDF, size = 300, modified = 1)
    private val xlsx = doc("budget.xlsx", DocumentFormat.XLSX, size = 100, modified = 3)
    private val csv = doc("Ведомость.csv", DocumentFormat.CSV, size = 200, modified = 2)
    private val all = listOf(pdf, xlsx, csv)

    @Test
    fun defaultPreferences_sortByDateDescending() {
        assertEquals(listOf(xlsx, csv, pdf), filter(all, LibraryPreferences()))
    }

    @Test
    fun sortByNameAscending_isCaseInsensitive() {
        val prefs = LibraryPreferences(sortField = SortField.NAME, sortDescending = false)
        assertEquals(listOf(xlsx, csv, pdf), filter(all, prefs))
    }

    @Test
    fun sortBySizeDescending() {
        val prefs = LibraryPreferences(sortField = SortField.SIZE)
        assertEquals(listOf(pdf, csv, xlsx), filter(all, prefs))
    }

    @Test
    fun categoryFilter_keepsOnlySelectedCategories() {
        val prefs = LibraryPreferences(categoryFilter = setOf(DocumentCategory.SPREADSHEET))
        assertEquals(listOf(xlsx, csv), filter(all, prefs))
    }

    @Test
    fun query_matchesNameIgnoringCaseAndWhitespace() {
        assertEquals(listOf(pdf), filter(all, LibraryPreferences(), query = "  отчёт "))
    }

    private fun doc(name: String, format: DocumentFormat, size: Long, modified: Long) =
        DocumentItem(uri = "content://test/$name", name = name, format = format, sizeBytes = size, lastModified = modified)
}
