package com.example.fileopener.reader.flow

import com.example.fileopener.domain.model.DocumentFormat
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.reader.doc.DocReader
import com.example.fileopener.reader.docx.DocxReader
import com.example.fileopener.reader.txt.TextDocument
import com.example.fileopener.reader.txt.TextReader
import com.example.fileopener.reader.xml.XmlParserFactory
import com.example.fileopener.reader.xmldoc.XmlDocumentReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Загружает TXT/XML/DOCX/DOC в общую модель [FlowDocument]. */
fun interface FlowDocumentLoader {
    suspend fun load(document: DocumentItem): FlowDocument
}

class DefaultFlowDocumentLoader(
    private val openStream: (String) -> InputStream,
    /** Локальный файл с содержимым документа: DOCX читается как ZIP с произвольным доступом. */
    private val localFile: suspend (String) -> File,
    private val parsers: XmlParserFactory,
) : FlowDocumentLoader {
    override suspend fun load(document: DocumentItem): FlowDocument = withContext(Dispatchers.IO) {
        when (document.format) {
            DocumentFormat.TXT -> openStream(document.uri).use(TextReader::read).toFlowDocument()
            DocumentFormat.DOCX -> DocxReader(parsers).read(localFile(document.uri))
            DocumentFormat.DOC -> openStream(document.uri).use(DocReader::read)
            DocumentFormat.XML -> XmlDocumentReader.read({ openStream(document.uri) }, parsers)
            else -> throw IllegalArgumentException("${document.format} не является документом с переносом текста")
        }
    }
}

fun TextDocument.toFlowDocument(): FlowDocument =
    FlowDocument.of(paragraphs.map(::TextLineBlock), paragraphSpacing = false)
