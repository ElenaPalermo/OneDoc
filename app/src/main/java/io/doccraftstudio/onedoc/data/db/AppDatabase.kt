package io.doccraftstudio.onedoc.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LibraryFolderEntity::class,
        LibraryDocumentEntity::class,
        RecentEntity::class,
        PositionEntity::class,
        BookmarkEntity::class,
    ],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun recentDao(): RecentDao
    abstract fun readingStateDao(): ReadingStateDao
    abstract fun documentRecordsDao(): DocumentRecordsDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "docflow.db").build()
    }
}
