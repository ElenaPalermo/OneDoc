package io.doccraftstudio.onedoc.reader.txt

import java.nio.charset.Charset

/** Кодировка текста и длина BOM, который нужно пропустить. */
data class DetectedCharset(val charset: Charset, val bomLength: Int)

/**
 * Определяет кодировку по началу файла: BOM → корректный UTF-8 → windows-1251
 * (самый частый случай для русскоязычных TXT/CSV без BOM).
 */
object CharsetDetector {
    const val SAMPLE_SIZE = 64 * 1024

    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")

    fun detect(sample: ByteArray, length: Int = sample.size): DetectedCharset {
        fun startsWith(vararg bom: Int) = length >= bom.size && bom.indices.all { sample[it] == bom[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> DetectedCharset(Charsets.UTF_8, 3)
            startsWith(0xFF, 0xFE) -> DetectedCharset(Charsets.UTF_16LE, 2)
            startsWith(0xFE, 0xFF) -> DetectedCharset(Charsets.UTF_16BE, 2)
            isValidUtf8(sample, length) -> DetectedCharset(Charsets.UTF_8, 0)
            else -> DetectedCharset(WINDOWS_1251, 0)
        }
    }

    /** Обрезанная на границе выборки последовательность в конце считается допустимой. */
    internal fun isValidUtf8(bytes: ByteArray, length: Int): Boolean {
        var i = 0
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            val continuation = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + continuation >= length) return true
            for (k in 1..continuation) {
                if (bytes[i + k].toInt() and 0xC0 != 0x80) return false
            }
            i += continuation + 1
        }
        return true
    }
}
