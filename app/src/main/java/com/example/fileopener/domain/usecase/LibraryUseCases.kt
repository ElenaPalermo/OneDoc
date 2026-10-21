package com.example.fileopener.domain.usecase

import com.example.fileopener.domain.model.LibraryFolder
import com.example.fileopener.domain.repository.DocumentStorage
import com.example.fileopener.domain.repository.LibraryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Пересканирует все папки библиотеки. Возвращает папки, к которым пропал доступ. */
class ScanLibraryUseCase(
    private val libraryRepository: LibraryRepository,
    private val storage: DocumentStorage,
) {
    suspend operator fun invoke(): List<LibraryFolder> = coroutineScope {
        libraryRepository.getFolders()
            .map { folder -> async { folder.takeUnless { scanFolder(it) } } }
            .awaitAll()
            .filterNotNull()
    }

    /** `false`, если папка недоступна: прежний индекс тогда не трогаем, чтобы список не опустел. */
    suspend fun scanFolder(folder: LibraryFolder): Boolean {
        val documents = if (folder.isSingleFiles) {
            // Отдельные файлы перепроверяем по одному: удалённые и недоступные выпадают из индекса.
            libraryRepository.getFolderDocuments(folder.treeUri).mapNotNull { storage.resolve(it.uri) }
        } else {
            storage.scanFolder(folder.treeUri) ?: return false
        }
        libraryRepository.replaceFolderDocuments(folder.treeUri, documents)
        return true
    }
}

class AddLibraryFolderUseCase(
    private val libraryRepository: LibraryRepository,
    private val storage: DocumentStorage,
    private val scanLibrary: ScanLibraryUseCase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** `false`, если не удалось сохранить доступ к папке. */
    suspend operator fun invoke(treeUri: String): Boolean {
        if (!storage.persistAccess(treeUri)) return false
        val name = storage.folderDisplayName(treeUri) ?: treeUri.substringAfterLast("%3A")
        val folder = LibraryFolder(treeUri, name, addedAt = clock())
        libraryRepository.addFolder(folder)
        scanLibrary.scanFolder(folder)
        return true
    }
}

class AddLibraryFilesUseCase(
    private val libraryRepository: LibraryRepository,
    private val storage: DocumentStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Возвращает число добавленных файлов (неподдерживаемые форматы пропускаются). */
    suspend operator fun invoke(uris: List<String>): Int {
        val documents = uris.mapNotNull { uri ->
            storage.persistAccess(uri)
            storage.resolve(uri)
        }
        if (documents.isEmpty()) return 0
        libraryRepository.addFolder(LibraryFolder(LibraryFolder.SINGLE_FILES_URI, "", addedAt = clock()))
        libraryRepository.addDocuments(LibraryFolder.SINGLE_FILES_URI, documents)
        return documents.size
    }
}

class RemoveLibraryFolderUseCase(
    private val libraryRepository: LibraryRepository,
    private val storage: DocumentStorage,
) {
    suspend operator fun invoke(folder: LibraryFolder) {
        // Доступ к отдельным файлам не отзываем: они могут оставаться во вкладке «Недавние».
        if (!folder.isSingleFiles) storage.releaseAccess(folder.treeUri)
        libraryRepository.removeFolder(folder.treeUri)
    }
}
