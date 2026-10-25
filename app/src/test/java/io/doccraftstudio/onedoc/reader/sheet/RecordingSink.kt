package io.doccraftstudio.onedoc.reader.sheet

/** Запоминает всё, что парсер передал в [SheetSink]. */
class RecordingSink : SheetSink {
    var defaultWidth: Float? = null
    val widths = mutableListOf<Triple<Int, Int, Float>>()
    val rows = linkedMapOf<Int, List<SheetCell>>()
    val merges = mutableListOf<CellRange>()

    override fun defaultColumnWidth(widthChars: Float) {
        defaultWidth = widthChars
    }

    override fun columnWidth(firstColumn: Int, lastColumn: Int, widthChars: Float) {
        widths += Triple(firstColumn, lastColumn, widthChars)
    }

    override fun row(index: Int, cells: List<SheetCell>) {
        rows[index] = cells
    }

    override fun merge(range: CellRange) {
        merges += range
    }

    fun cell(row: Int, column: Int): SheetCell? = rows[row]?.firstOrNull { it.column == column }

    fun text(row: Int, column: Int): String? = cell(row, column)?.text
}
