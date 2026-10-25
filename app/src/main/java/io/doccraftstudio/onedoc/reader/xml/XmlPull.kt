package io.doccraftstudio.onedoc.reader.xml

import org.xmlpull.v1.XmlPullParser

/** Создаёт парсер с обработкой пространств имён: на устройстве — встроенный в Android, в тестах — kxml2. */
fun interface XmlParserFactory {
    fun create(): XmlPullParser
}

/**
 * Обходит прямых потомков текущего элемента (парсер стоит на его START_TAG) и возвращается на его END_TAG.
 * [block] получает локальное имя потомка; он может прочитать потомка целиком (вложенным [forEachChild]
 * или `nextText()`), а может проигнорировать — вложенные события тогда просто пропускаются.
 */
inline fun XmlPullParser.forEachChild(block: (name: String) -> Unit) {
    val parentDepth = depth
    while (true) {
        when (next()) {
            XmlPullParser.END_DOCUMENT -> return
            XmlPullParser.END_TAG -> if (depth == parentDepth) return
            XmlPullParser.START_TAG -> if (depth == parentDepth + 1) block(name)
        }
    }
}

/** Пропускает текущий элемент целиком. */
fun XmlPullParser.skipElement() = forEachChild { }

/** Значение атрибута по локальному имени, без учёта префикса (w:val, r:id). */
fun XmlPullParser.attr(localName: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i) == localName) return getAttributeValue(i)
    }
    return null
}

/** Атрибут из пространства имён связей OOXML (`r:id`, `r:embed`) — не путать с `w:id` у закладок. */
fun XmlPullParser.relationshipAttr(localName: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i) == localName && getAttributeNamespace(i).endsWith("relationships")) {
            return getAttributeValue(i)
        }
    }
    return null
}

/** Переключатель OOXML: `<w:b/>` и `<w:b w:val="true"/>` — включено, `w:val="0|false|off"` — выключено. */
fun XmlPullParser.toggleValue(): Boolean = when (attr("val")?.lowercase()) {
    null, "1", "true", "on" -> true
    else -> false
}

/** Перемещает парсер на корневой элемент документа. */
fun XmlPullParser.moveToRoot(): Boolean {
    while (true) {
        when (next()) {
            XmlPullParser.START_TAG -> return true
            XmlPullParser.END_DOCUMENT -> return false
        }
    }
}
