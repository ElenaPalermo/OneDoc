package io.doccraftstudio.onedoc.di

import android.content.Context
import android.util.Xml
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import io.doccraftstudio.onedoc.data.db.AppDatabase
import io.doccraftstudio.onedoc.data.repository.RoomDocumentRecordsRepository
import io.doccraftstudio.onedoc.data.repository.RoomLibraryRepository
import io.doccraftstudio.onedoc.data.repository.RoomReadingStateRepository
import io.doccraftstudio.onedoc.data.repository.RoomRecentRepository
import io.doccraftstudio.onedoc.data.settings.DataStoreSettingsRepository
import io.doccraftstudio.onedoc.data.storage.LocalFileCache
import io.doccraftstudio.onedoc.data.storage.SafDocumentStorage
import io.doccraftstudio.onedoc.data.thumbnail.PdfThumbnailProvider
import io.doccraftstudio.onedoc.domain.repository.DocumentRecordsRepository
import io.doccraftstudio.onedoc.domain.repository.DocumentStorage
import io.doccraftstudio.onedoc.domain.repository.LibraryRepository
import io.doccraftstudio.onedoc.domain.repository.ReadingStateRepository
import io.doccraftstudio.onedoc.domain.repository.RecentRepository
import io.doccraftstudio.onedoc.domain.repository.SettingsRepository
import io.doccraftstudio.onedoc.domain.usecase.AddLibraryFilesUseCase
import io.doccraftstudio.onedoc.domain.usecase.AddLibraryFolderUseCase
import io.doccraftstudio.onedoc.domain.usecase.DeleteDocumentUseCase
import io.doccraftstudio.onedoc.domain.usecase.RemoveLibraryFolderUseCase
import io.doccraftstudio.onedoc.domain.usecase.RenameDocumentUseCase
import io.doccraftstudio.onedoc.domain.usecase.ScanLibraryUseCase
import io.doccraftstudio.onedoc.reader.flow.DefaultFlowDocumentLoader
import io.doccraftstudio.onedoc.reader.flow.FlowDocumentLoader
import io.doccraftstudio.onedoc.reader.pdf.PdfOpener
import io.doccraftstudio.onedoc.reader.pdf.PdfiumOpener
import io.doccraftstudio.onedoc.reader.sheet.DefaultSpreadsheetLoader
import io.doccraftstudio.onedoc.reader.sheet.SpreadsheetLoader
import io.doccraftstudio.onedoc.reader.xml.XmlParserFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Ручной DI: единственный экземпляр живёт в [io.doccraftstudio.onedoc.DocFlowApp]. */
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
