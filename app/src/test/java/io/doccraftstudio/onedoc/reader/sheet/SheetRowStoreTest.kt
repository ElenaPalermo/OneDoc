package io.doccraftstudio.onedoc.reader.sheet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class SheetRowStoreTest {
    private val file = File.createTempFile("store", ".bin")
    private val store = SheetRowStore(file)

    @After
    fun tearDown() {
        store.close()
        assertFalse("файл кэша удаляется при закрытии", file.exists())
    }

    @Test
    fun rowsBecomeReadableAfterPublish_gapsAreNull() {
        store.row(0, listOf(SheetCell(0, "Шапка", style = 2), SheetCell(300, "далеко", CellKind.NUMBER)))
        store.row(2, listOf(SheetCell(1, "третья")))
        // До первой публикации строки ещё не видны интерфейсу.
        assertEquals(0, store.state.value.rowCount)
        assertTrue(store.readRows(0, 10).isEmpty())

        store.finish()
        val rows = store.readRows(0, 10)
        assertEquals(3, rows.size)
        assertEquals(listOf(SheetCell(0, "Шапка", style = 2), SheetCell(300, "далеко", CellKind.NUMBER)), rows[0]!!.cells)
        assertNull(rows[1])
        assertEquals("третья", rows[2]!!.cellAt(1)?.text)
        assertEquals(301, store.state.value.columnCount)
        assertTrue(store.state.value.complete)
    }

    @Test
    fun firstRowsArePublishedBeforeParsingEnds() {
        repeat(150) { store.row(it, listOf(SheetCell(0, "r$it"))) }
        val state = store.state.value
        assertEquals(100, state.rowCount)
        assertFalse(state.complete)
        assertEquals("r99", store.readRows(95, 10).last()!!.cells.single().text)
    }

    @Test
    fun readRows_windowInTheMiddle() {
        repeat(1_000) { store.row(it * 2, listOf(SheetCell(it % 5, "v$it"))) }
        store.finish()
        val window = store.readRows(500, 4)
        assertEquals(listOf("v250", null, "v251", null), window.map { it?.cells?.single()?.text })
    }

    @Test
    fun columnWidths_explicitDefaultAndAutoSized() {
        store.columnWidth(0, 0, 20f)
        store.columnWidth(2, 2, 0f)
        store.row(0, listOf(SheetCell(0, "a"), SheetCell(1, "длинный текст колонки"), SheetCell(2, "скрыто"), SheetCell(3, "x")))
        store.finish()
        val widths = store.state.value.columnWidths
        assertEquals(20f, widths[0])
        assertEquals("длинный текст колонки".length + 1f, widths[1])
        assertEquals(0f, widths[2])
        assertEquals("короткий текст расширяется до минимума", 4f, widths[3])
    }

    @Test
    fun columnWidths_sameArrayWhenUnchanged() {
        repeat(100) { store.row(it, listOf(SheetCell(0, "abc"))) }
        val first = store.state.value.columnWidths
        store.finish()
        assertTrue("сетка не пересобирает геометрию колонок", first === store.state.value.columnWidths)
    }

    @Test
    fun columnWidths_defaultWidthReplacesAutoSize() {
        store.defaultColumnWidth(8.43f)
        store.row(0, listOf(SheetCell(0, "очень длинный текст, который Excel показал бы поверх соседей")))
        store.finish()
        assertEquals(8.43f, store.state.value.columnWidths[0])
    }

    @Test
    fun merges_areIndexedByRowAfterFinish() {
        store.row(0, listOf(SheetCell(0, "Заголовок")))
        store.row(1, listOf(SheetCell(0, "x")))
        store.merge(CellRange(0, 1, 0, 3))
        store.finish()
        val merges = store.state.value.merges
        assertEquals(listOf(CellRange(0, 1, 0, 3)), merges[0])
        assertEquals(listOf(CellRange(0, 1, 0, 3)), merges[1])
        assertNull(merges[2])
    }

    @Test
    fun search_ignoresCaseAndReturnsCellsInOrder() {
        store.row(0, listOf(SheetCell(0, "Москва"), SheetCell(3, "Казань")))
        store.row(5, listOf(SheetCell(1, "Нижний Новгород"), SheetCell(2, "МОСКВА-Сити")))
        store.finish()
        assertEquals(listOf(CellPosition(0, 0), CellPosition(5, 2)), store.search("москва", limit = 10))
        assertEquals(listOf(CellPosition(0, 0)), store.search("москва", limit = 1))
        assertTrue(store.search("Самара", limit = 10).isEmpty())
    }

    @Test
    fun searchRange_continuesFromPublishedBoundary() {
        store.row(0, listOf(SheetCell(0, "Москва")))
        for (row in 1 until 100) store.row(row, listOf(SheetCell(0, "строка $row")))
        // Первые 100 строк опубликованы, разбор продолжается.
        val first = store.state.value
        assertFalse(first.complete)
        val partial = store.searchRange("москва", 10, fromOffset = 0, untilOffset = first.publishedBytes)
        assertEquals(listOf(CellPosition(0, 0)), partial.matches)
        assertEquals(first.publishedBytes, partial.nextOffset)

        store.row(150, listOf(SheetCell(2, "снова Москва")))
        store.finish()
        val rest = store.searchRange("москва", 10, partial.nextOffset, store.state.value.publishedBytes)
        assertEquals(listOf(CellPosition(150, 2)), rest.matches)
    }

    @Test
    fun search_matchesStringContainsIgnoreCase() {
        val random = Random(7)
        val alphabet = "aAbBzZ09.,- яЯёЁжЖKİiIkKßẞ\u0000😀"
        fun text(length: Int) = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
        val rows = List(3_000) { row ->
            List(1 + random.nextInt(4)) { column ->
                val length = if (row % 500 == 0) 1_500 + random.nextInt(500) else random.nextInt(12)
                // Половина ячеек — только ASCII, как числа и коды на больших листах.
                SheetCell(column * 2, if (random.nextBoolean()) text(length).filter { it.code < 0x80 } else text(length))
            }
        }
        rows.forEachIndexed { index, cells -> store.row(index, cells) }
        store.finish()
        val queries = listOf("a", "Ab", "z0", "9.", "ЯЖ", "ё", "k", "K", "K", "i", "İ", "ß", "ẞ", "😀", "\u0000", "a\u0000") +
            List(40) { text(1 + random.nextInt(3)) }
        for (query in queries) {
            val expected = rows.flatMapIndexed { index, cells ->
                cells.filter { it.text.contains(query, ignoreCase = true) }.map { CellPosition(index, it.column) }
            }
            assertEquals("запрос «$query»", expected, store.search(query, limit = Int.MAX_VALUE))
        }
    }

    @Test
    fun searchRange_inSmallChunksFindsEverything() {
        repeat(20_000) { store.row(it, listOf(SheetCell(0, "row $it"), SheetCell(1, if (it % 997 == 0) "Нашлось" else "-"))) }
        store.finish()
        val end = store.state.value.publishedBytes
        val found = ArrayList<CellPosition>()
        var offset = 0L
        while (offset < end) {
            val chunk = store.searchRange("НАШЛОСЬ", 100, offset, minOf(end, offset + 1_000))
            found += chunk.matches
            offset = chunk.nextOffset
        }
        assertEquals((0 until 20_000 step 997).map { CellPosition(it, 1) }, found)
    }

    @Test
    fun expectedRowCount_isPublishedWithState() {
        store.expectedRowCount(250_000)
        store.row(0, listOf(SheetCell(0, "a")))
        store.finish()
        assertEquals(250_000, store.state.value.expectedRowCount)
        assertEquals(1, store.state.value.rowCount)
    }

    @Test
    fun veryLongText_isTruncatedInsteadOfFailing() {
        store.row(0, listOf(SheetCell(0, "я".repeat(50_000))))
        store.finish()
        assertEquals(2_000, store.readRows(0, 1).single()!!.cells.single().text.length)
    }
}
