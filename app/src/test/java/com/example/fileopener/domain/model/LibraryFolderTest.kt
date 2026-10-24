package com.example.fileopener.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFolderTest {
    @Test
    fun nameFromTreeUri_takesLastPathSegmentOfDocumentId() {
        assertEquals(
            "Work",
            LibraryFolder.nameFromTreeUri("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWork"),
        )
        assertEquals(
            "Мои документы",
            LibraryFolder.nameFromTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3A%D0%9C%D0%BE%D0%B8%20%D0%B4%D0%BE%D0%BA%D1%83%D0%BC%D0%B5%D0%BD%D1%82%D1%8B",
            ),
        )
        assertEquals(
            "Reports",
            LibraryFolder.nameFromTreeUri(
                "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FReports",
            ),
        )
    }

    @Test
    fun nameFromTreeUri_rootsAndOddUris() {
        assertEquals("Download", LibraryFolder.nameFromTreeUri("content://com.android.providers.downloads.documents/tree/downloads"))
        assertEquals("primary", LibraryFolder.nameFromTreeUri("content://com.android.externalstorage.documents/tree/primary%3A"))
        assertEquals("content://x/no-tree", LibraryFolder.nameFromTreeUri("content://x/no-tree"))
    }

    @Test
    fun hasUriAsName_detectsOldFallback() {
        val tree = "content://com.android.providers.downloads.documents/tree/downloads"
        assertTrue(LibraryFolder(tree, tree, addedAt = 0).hasUriAsName)
        assertFalse(LibraryFolder(tree, "Download", addedAt = 0).hasUriAsName)
        assertFalse(LibraryFolder(LibraryFolder.SINGLE_FILES_URI, "", addedAt = 0).hasUriAsName)
    }
}
