package io.doccraftstudio.onedoc.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.doccraftstudio.onedoc.domain.model.DocumentFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun deletingFolder_cascadesToItsDocuments() = runTest {
        val dao = db.libraryDao()
        dao.upsertFolder(LibraryFolderEntity("tree://docs", "Docs", addedAt = 0))
        dao.replaceFolderDocuments("tree://docs", listOf(document("a.pdf"), document("b.pdf")))
        assertEquals(2, dao.observeDocuments().first().size)

        dao.deleteFolder("tree://docs")

        assertTrue(dao.observeDocuments().first().isEmpty())
    }

    @Test
    fun replaceFolderDocuments_dropsFilesMissingFromNewScan() = runTest {
        val dao = db.libraryDao()
        dao.upsertFolder(LibraryFolderEntity("tree://docs", "Docs", addedAt = 0))
        dao.replaceFolderDocuments("tree://docs", listOf(document("a.pdf"), document("b.pdf")))

        dao.replaceFolderDocuments("tree://docs", listOf(document("b.pdf")))

        assertEquals(listOf("b.pdf"), dao.observeDocuments().first().map { it.name })
    }

    @Test
    fun recent_isOrderedByLastOpenedDescending() = runTest {
        val dao = db.recentDao()
        dao.upsert(RecentEntity("u1", "old.txt", DocumentFormat.TXT, 1, 1, lastOpenedAt = 10))
        dao.upsert(RecentEntity("u2", "new.txt", DocumentFormat.TXT, 1, 1, lastOpenedAt = 20))
        dao.upsert(RecentEntity("u1", "old.txt", DocumentFormat.TXT, 1, 1, lastOpenedAt = 30))

        assertEquals(listOf("u1", "u2"), dao.observeRecent(limit = 10).first().map { it.uri })
    }

    @Test
    fun position_roundTrips() = runTest {
        val dao = db.readingStateDao()
        val columns = PositionColumns(index = 5, offset = 120, sheet = 1, column = 3, zoom = 1.5f)
        dao.upsertPosition(PositionEntity("u1", columns, updatedAt = 0))

        assertEquals(columns, dao.getPosition("u1")?.position)
    }

    @Test
    fun replaceRecords_movesDocumentInAllTables() = runTest {
        val library = db.libraryDao()
        val reading = db.readingStateDao()
        library.upsertFolder(LibraryFolderEntity("tree://docs", "Docs", addedAt = 0))
        library.upsertDocuments(listOf(document("a.pdf")))
        db.recentDao().upsert(RecentEntity("tree://docs/a.pdf", "a.pdf", DocumentFormat.PDF, 1, 0, lastOpenedAt = 5))
        val columns = PositionColumns(index = 3, offset = 0, sheet = 0, column = 0, zoom = 1f)
        reading.upsertPosition(PositionEntity("tree://docs/a.pdf", columns, updatedAt = 0))
        reading.insertBookmark(BookmarkEntity(documentUri = "tree://docs/a.pdf", title = "b", position = columns, createdAt = 0))

        db.documentRecordsDao().replace("tree://docs/a.pdf", "tree://docs/b.pdf", "b.pdf", sizeBytes = 2, lastModified = 7)

        assertEquals(listOf("tree://docs/b.pdf" to "b.pdf"), library.observeDocuments().first().map { it.uri to it.name })
        assertEquals(listOf("b.pdf"), db.recentDao().observeRecent(10).first().map { it.name })
        assertEquals(columns, reading.getPosition("tree://docs/b.pdf")?.position)
        assertEquals(1, reading.observeBookmarks("tree://docs/b.pdf").first().size)
        assertNull(reading.getPosition("tree://docs/a.pdf"))
    }

    @Test
    fun forgetRecords_removesDocumentEverywhere() = runTest {
        val library = db.libraryDao()
        val reading = db.readingStateDao()
        library.upsertFolder(LibraryFolderEntity("tree://docs", "Docs", addedAt = 0))
        library.upsertDocuments(listOf(document("a.pdf"), document("keep.pdf")))
        val columns = PositionColumns(index = 1, offset = 0, sheet = 0, column = 0, zoom = 1f)
        reading.upsertPosition(PositionEntity("tree://docs/a.pdf", columns, updatedAt = 0))
        reading.insertBookmark(BookmarkEntity(documentUri = "tree://docs/a.pdf", title = "b", position = columns, createdAt = 0))

        db.documentRecordsDao().forget("tree://docs/a.pdf")

        assertEquals(listOf("keep.pdf"), library.observeDocuments().first().map { it.name })
        assertNull(reading.getPosition("tree://docs/a.pdf"))
        assertTrue(reading.observeBookmarks("tree://docs/a.pdf").first().isEmpty())
    }

    private fun document(name: String) = LibraryDocumentEntity(
        uri = "tree://docs/$name",
        folderUri = "tree://docs",
        name = name,
        format = DocumentFormat.PDF,
        sizeBytes = 1,
        lastModified = 0,
    )
}
