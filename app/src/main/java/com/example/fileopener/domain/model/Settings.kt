package com.example.fileopener.domain.model

/** Тема экрана чтения; не зависит от темы самого приложения. */
enum class ReaderTheme { LIGHT, DARK, SEPIA }

enum class ReaderFont { SANS, SERIF, MONO }

/** Допустимый диапазон размера шрифта при pinch-to-zoom и в настройках. */
const val MIN_READER_FONT_SP = 10f
const val MAX_READER_FONT_SP = 48f

/**
 * Настройки просмотрщиков (DOCX/DOC/TXT — шрифт и интервал, таблицы — фиксация шапки).
 * [theme] = `null` — тема ещё не выбрана, используется светлая или тёмная по системной теме.
 */
data class ReaderSettings(
    val theme: ReaderTheme? = null,
    val font: ReaderFont = ReaderFont.SERIF,
    val fontSizeSp: Float = 18f,
    val lineSpacing: Float = 1.4f,
    val freezeHeaderRow: Boolean = true,
)

enum class LibraryViewMode { LIST, GRID }

enum class SortField { DATE, NAME, SIZE }

/** Настройки главного экрана. Пустой [categoryFilter] означает «все типы». */
data class LibraryPreferences(
    val viewMode: LibraryViewMode = LibraryViewMode.LIST,
    val sortField: SortField = SortField.DATE,
    val sortDescending: Boolean = true,
    val categoryFilter: Set<DocumentCategory> = emptySet(),
)
