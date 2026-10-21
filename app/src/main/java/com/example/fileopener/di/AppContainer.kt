package com.example.fileopener.di

import android.content.Context
import android.util.Xml
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.fileopener.data.db.AppDatabase
import com.example.fileopener.data.repository.RoomDocumentRecordsRepository
import com.example.fileopener.data.repository.RoomLibraryRepository
import com.example.fileopener.data.repository.RoomReadingStateRepository
import com.example.fileopener.data.repository.RoomRecentRepository
import com.example.fileopener.data.settings.DataStoreSettingsRepository
import com.example.fileopener.data.storage.LocalFileCache
import com.example.fileopener.data.storage.SafDocumentStorage
import com.example.fileopener.data.thumbnail.PdfThumbnailProvider
import com.example.fileopener.domain.repository.DocumentRecordsRepository
import com.example.fileopener.domain.repository.DocumentStorage
import com.example.fileopener.domain.repository.LibraryRepository
import com.example.fileopener.domain.repository.ReadingStateRepository
import com.example.fileopener.domain.repository.RecentRepository
import com.example.fileopener.domain.repository.SettingsRepository
import com.example.fileopener.domain.usecase.AddLibraryFilesUseCase
import com.example.fileopener.domain.usecase.AddLibraryFolderUseCase
import com.example.fileopener.domain.usecase.DeleteDocumentUseCase
import com.example.fileopener.domain.usecase.RemoveLibraryFolderUseCase
import com.example.fileopener.domain.usecase.RenameDocumentUseCase
import com.example.fileopener.domain.usecase.ScanLibraryUseCase
import com.example.fileopener.reader.flow.DefaultFlowDocumentLoader
import com.example.fileopener.reader.flow.FlowDocumentLoader
import com.example.fileopener.reader.pdf.PdfOpener
import com.example.fileopener.reader.pdf.PdfiumOpener
import com.example.fileopener.reader.sheet.DefaultSpreadsheetLoader
import com.example.fileopener.reader.sheet.SpreadsheetLoader
import com.example.fileopener.reader.xml.XmlParserFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Ручной DI: единственный экземпляр живёт в [com.example.fileopener.DocFlowApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database by lazy { AppDatabase.build(appContext) }

    /** Для записей, которые должны завершиться после закрытия экрана (сохранение позиции чтения). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val libraryRepository: LibraryRepository by lazy { RoomLibraryRepository(database.libraryDao()) }
    val recentRepository: RecentRepository by lazy { RoomRecentRepository(database.recentDao()) }
    val readingStateRepository: ReadingStateRepository by lazy {
        RoomReadingStateRepository(database.readingStateDao())
    }
    val documentRecordsRepository: DocumentRecordsRepository by lazy {
        RoomDocumentRecordsRepository(database.documentRecordsDao())
    }
    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(appContext.settingsDataStore)
    }
    val documentStorage: DocumentStorage by lazy { SafDocumentStorage(appContext) }
    val pdfOpener: PdfOpener by lazy { PdfiumOpener(appContext) }
    val pdfThumbnails by lazy { PdfThumbnailProvider(File(appContext.cacheDir, "thumbnails"), pdfOpener) }
    private val localFileCache by lazy { LocalFileCache(appContext) }
    private val xmlParsers = XmlParserFactory {
        Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
    }
    val flowDocumentLoader: FlowDocumentLoader by lazy {
        DefaultFlowDocumentLoader(
            openStream = ::openDocumentStream,
            localFile = localFileCache::localFile,
            parsers = xmlParsers,
        )
    }
    val spreadsheetLoader: SpreadsheetLoader by lazy {
        DefaultSpreadsheetLoader(
            openStream = ::openDocumentStream,
            localFile = localFileCache::localFile,
            parsers = xmlParsers,
            cacheDirectory = File(appContext.cacheDir, "sheets"),
        )
    }

    val scanLibrary by lazy { ScanLibraryUseCase(libraryRepository, documentStorage) }
    val addLibraryFolder by lazy { AddLibraryFolderUseCase(libraryRepository, documentStorage, scanLibrary) }
    val addLibraryFiles by lazy { AddLibraryFilesUseCase(libraryRepository, documentStorage) }
    val removeLibraryFolder by lazy { RemoveLibraryFolderUseCase(libraryRepository, documentStorage) }
    val renameDocument by lazy { RenameDocumentUseCase(documentStorage, documentRecordsRepository) }
    val deleteDocument by lazy { DeleteDocumentUseCase(documentStorage, documentRecordsRepository) }

    /** Открытие потока документа для движков чтения (`reader/`). */
    fun openDocumentStream(uri: String): InputStream =
        appContext.contentResolver.openInputStream(uri.toUri())
            ?: throw FileNotFoundException("Провайдер не вернул поток для $uri")
}
