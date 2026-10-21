package com.example.fileopener.reader

/**
 * Пункт оглавления.
 * @property targetIndex страница PDF или индекс блока документа с переносом.
 * @property level глубина вложенности (0 — верхний уровень).
 */
data class TocEntry(val title: String, val targetIndex: Int, val level: Int)

/** Раздел оглавления, к которому относится позиция: последний пункт, начинающийся не позже неё. */
fun List<TocEntry>.chapterFor(index: Int): TocEntry? = lastOrNull { it.targetIndex <= index }
