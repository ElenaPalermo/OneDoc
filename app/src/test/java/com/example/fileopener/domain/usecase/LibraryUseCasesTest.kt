package com.example.fileopener.domain.usecase

import com.example.fileopener.domain.model.DocumentFormat
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.model.LibraryFolder
import com.example.fileopener.fakes.FakeDocumentStorage
import com.example.fileopener.fakes.FakeLibraryRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUseCasesTest {
    private val repository = FakeLibraryRepository()
    private val storage = FakeDocumentStorage()
    private val scan = ScanLibraryUseCase(repository, storage)

    @Test
    fun scan_replacesDocumentsOfAccessibleFolders() = runTest {
        repository.addFolder(folder("tree://a"))
        repository.replaceFolderDocuments("tree://a", listOf(doc("old.pdf")))
        storage.trees["tree://a"] = listOf(doc("new.pdf"))

        val unavailable = scan()

        assertTrue(unavailable.isEmpty())
        assertEquals(listOf("new.pdf"), repository.getFolderDocuments("tree://a").map { it.name })
    }

    @Test
    fun scan_keepsIndexOfUnavailableFolderAndReportsIt() = runTest {
        repository.addFolder(folder("tree://gone"))
        repository.replaceFolderDocuments("tree://gone", listOf(doc("kept.pdf")))

        val unavailable = scan()

        assertEquals(listOf("tree://gone"), unavailable.map { it.treeUri })
        assertEquals(listOf("kept.pdf"), repository.getFolderDocuments("tree://gone").map { it.name })
    }

    @Test
    fun scan_dropsSingleFilesThatNoLongerResolve() = runTest {
        val alive = doc("alive.txt")
        repository.addFolder(folder(LibraryFolder.SINGLE_FILES_URI))
        repository.addDocuments(LibraryFolder.SINGLE_FILES_URI, listOf(alive, doc("deleted.txt")))
        storage.resolvable[alive.uri] = alive

        scan()

        assertEquals(listOf(alive), repository.getFolderDocuments(LibraryFolder.SINGLE_FILES_URI))
    }

    @Test
    fun addFolder_failsWithoutPersistableAccess() = runTest {
        storage.persistAllowed = false
        val added = AddLibraryFolderUseCase(repository, storage, scan).invoke("tree://a")

        assertFalse(added)
        assertTrue(repository.getFolders().isEmpty())
    }

    @Test
    fun addFolder_withoutProviderName_takesNameFromUri() = runTest {
        storage.displayNames[DOWNLOADS_TREE] = null
        storage.trees[DOWNLOADS_TREE] = emptyList()

        AddLibraryFolderUseCase(repository, storage, scan).invoke(DOWNLOADS_TREE)

        assertEquals(listOf("Download"), repository.getFolders().map { it.displayName })
    }

    @Test
    fun scan_repairsFolderNameStoredAsUri() = runTest {
        repository.addFolder(LibraryFolder(DOWNLOADS_TREE, DOWNLOADS_TREE, addedAt = 7))
        repository.replaceFolderDocuments(DOWNLOADS_TREE, listOf(doc("old.pdf")))
        storage.displayNames[DOWNLOADS_TREE] = null
        storage.trees[DOWNLOADS_TREE] = listOf(doc("new.pdf"))

        scan()

        assertEquals(listOf(LibraryFolder(DOWNLOADS_TREE, "Download", addedAt = 7)), repository.getFolders())
        assertEquals(listOf("new.pdf"), repository.getFolderDocuments(DOWNLOADS_TREE).map { it.name })
    }

    @Test
    fun addFiles_skipsUnsupportedAndStoresUnderSingleFilesFolder() = runTest {
        val pdf = doc("a.pdf")
        storage.resolvable[pdf.uri] = pdf

        val count = AddLibraryFilesUseCase(repository, storage).invoke(listOf(pdf.uri, "content://x/archive.zip"))

        assertEquals(1, count)
        assertEquals(listOf(pdf), repository.getFolderDocuments(LibraryFolder.SINGLE_FILES_URI))
    }

    private fun folder(uri: String) = LibraryFolder(uri, uri.substringAfterLast('/'), addedAt = 0)

    private fun doc(name: String) = DocumentItem(
        uri = "content://x/$name",
        name = name,
        format = DocumentFormat.fromFileName(name)!!,
        sizeBytes = 1,
        lastModified = 0,
    )

    private companion object {
        const val DOWNLOADS_TREE = "content://com.android.providers.downloads.documents/tree/downloads"
    }
}

