package com.example.fileopener.reader.sheet

import com.example.fileopener.domain.model.DocumentFormat
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.reader.csv.CsvSource
import com.example.fileopener.reader.xls.XlsReader
import com.example.fileopener.reader.xlsx.XlsxReader
import com.example.fileopener.reader.xml.XmlParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Открывает XLSX/XLS/CSV и создаёт файлы кэша строк для разобранных листов. */
interface SpreadsheetLoader {
    suspend fun load(document: DocumentItem): SpreadsheetSource

    /** Новое хранилище строк листа во временном файле; удаляется при закрытии. */
    fun createRowStore(): SheetRowStore
}

class DefaultSpreadsheetLoader(
    private val openStream: (String) -> InputStream,
    /** Локальный файл с содержимым документа: XLSX читается как ZIP с произвольным доступом. */
    private val localFile: suspend (String) -> File,
    private val parsers: XmlParserFactory,
    /** Папка для кэша листов; очищается при первом обращении — там могли остаться файлы прошлого запуска. */
    private val cacheDirectory: File,
) : SpreadsheetLoader {
    private val directory by lazy {
        cacheDirectory.deleteRecursively()
        cacheDirectory.apply { mkdirs() }
    }

    override suspend fun load(document: DocumentItem): SpreadsheetSource = withContext(Dispatchers.IO) {
        when (document.format) {
            DocumentFormat.XLSX -> XlsxReader(parsers).open(localFile(document.uri))
            DocumentFormat.XLS -> openStream(document.uri).use { XlsReader.open(it) }
            DocumentFormat.CSV -> CsvSource(document.name.substringBeforeLast('.'), document.sizeBytes) {
                openStream(document.uri)
            }
            else -> throw IllegalArgumentException("${document.format} не является таблицей")
        }
    }

    override fun createRowStore(): SheetRowStore =
        SheetRowStore(File.createTempFile("sheet", ".bin", directory))
}
