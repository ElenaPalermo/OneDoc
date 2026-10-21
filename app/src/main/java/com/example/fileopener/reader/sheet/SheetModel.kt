package com.example.fileopener.reader.sheet

import java.io.Closeable

/** Горизонтальное выравнивание ячейки; [GENERAL] — как в Excel: числа вправо, текст влево. */
enum class HorizontalAlignment { GENERAL, LEFT, CENTER, RIGHT }

/**
 * Оформление ячейки для отображения. Формат числа сюда не входит: значение форматируется
 * при разборе файла, и в кэш строк попадает уже готовый текст.
 * @property textColor ARGB; `null` — цвет текста темы чтения.
 * @property fillColor ARGB заливки; `null` — без заливки.
 */
data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val textColor: Int? = null,
    val fillColor: Int? = null,
    val alignment: HorizontalAlignment = HorizontalAlignment.GENERAL,
) {
    companion object {
        val DEFAULT = CellStyle()
    }
}

/** Тип значения: от него зависит выравнивание по умолчанию. */
enum class CellKind { TEXT, NUMBER, BOOLEAN, ERROR }

/**
 * Непустая ячейка строки.
 * @property text значение, уже отформатированное для показа (число, дата, текст).
 * @property style индекс в [SpreadsheetSource.styles].
 */
data class SheetCell(
    val column: Int,
    val text: String,
    val kind: CellKind = CellKind.TEXT,
    val style: Int = 0,
)

/** Строка листа: только непустые ячейки, по возрастанию колонки. */
class SheetRow(val cells: List<SheetCell>) {
    /** Ячейка в колонке [column] или `null`. */
    fun cellAt(column: Int): SheetCell? {
        var low = 0
        var high = cells.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = cells[mid].column
            when {
                value < column -> low = mid + 1
                value > column -> high = mid - 1
                else -> return cells[mid]
            }
        }
        return null
    }

    /** Текст строки для заголовка закладки. */
    val plainText: String get() = cells.joinToString(" ") { it.text }.trim()
}

/** Прямоугольник ячеек (индексы с нуля, границы включительно) — для объединённых ячеек. */
data class CellRange(val firstRow: Int, val lastRow: Int, val firstColumn: Int, val lastColumn: Int)

/** Приёмник содержимого листа: парсеры передают сюда строки по мере чтения файла. */
interface SheetSink {
    /** Ширина колонок по умолчанию в символах (как в Excel); без неё ширина подбирается по содержимому. */
    fun defaultColumnWidth(widthChars: Float)

    /** Явная ширина колонок [firstColumn]..[lastColumn] в символах; 0 — колонка скрыта. */
    fun columnWidth(firstColumn: Int, lastColumn: Int, widthChars: Float)

    /** Строка [index] (с нуля); строки приходят по возрастанию, пропущенные считаются пустыми. */
    fun row(index: Int, cells: List<SheetCell>)

    fun merge(range: CellRange)

    /**
     * Ожидаемое число строк листа, если формат сообщает его заранее (`<dimension>` XLSX) или его можно
     * оценить (CSV по размеру файла). Подсказка: по ней ширина колонки номеров не скачет во время разбора.
     */
    fun expectedRowCount(count: Int) = Unit
}

/** Открытая книга (XLSX, XLS) или CSV как книга из одного листа. */
interface SpreadsheetSource : Closeable {
    val sheetNames: List<String>

    /** Стили ячеек книги; индекс 0 — стиль по умолчанию, список не пустой. */
    val styles: List<CellStyle>

    /** Потоково читает лист [index] в [sink]. Блокирующий вызов — только в фоновом потоке. */
    fun readSheet(index: Int, sink: SheetSink)
}

/** Ограничения размера листа: как у Excel по строкам, по колонкам — с запасом для реальных файлов. */
const val MAX_SHEET_ROWS = 1_048_576
const val MAX_SHEET_COLUMNS = 2_048

/** Индекс колонки (с нуля) из ссылки на ячейку вида `AB12`; `-1`, если букв нет. */
fun columnIndexOf(reference: String): Int {
    var column = 0
    var letters = 0
    for (char in reference) {
        val upper = char.uppercaseChar()
        if (upper !in 'A'..'Z') break
        column = column * 26 + (upper - 'A' + 1)
        letters++
    }
    return if (letters == 0) -1 else column - 1
}

/** Номер строки (с нуля) из ссылки на ячейку вида `AB12`; `-1`, если цифр нет. */
fun rowIndexOf(reference: String): Int {
    val digits = reference.dropWhile { it.isLetter() || it == '$' }.filter { it.isDigit() }
    return (digits.toIntOrNull() ?: 0) - 1
}

/** Буквенное имя колонки: 0 → A, 26 → AA. */
fun columnName(index: Int): String {
    var value = index + 1
    val builder = StringBuilder()
    while (value > 0) {
        val rest = (value - 1) % 26
        builder.append('A' + rest)
        value = (value - 1) / 26
    }
    return builder.reverse().toString()
}

/** Диапазон вида `A1:C3` (или одна ячейка `B2`). */
fun parseCellRange(reference: String): CellRange? {
    val parts = reference.replace("$", "").split(':')
    val first = parts.first()
    val last = parts.getOrElse(1) { first }
    val range = CellRange(rowIndexOf(first), rowIndexOf(last), columnIndexOf(first), columnIndexOf(last))
    return range.takeIf { it.firstRow >= 0 && it.firstColumn >= 0 && it.lastRow >= it.firstRow && it.lastColumn >= it.firstColumn }
}
