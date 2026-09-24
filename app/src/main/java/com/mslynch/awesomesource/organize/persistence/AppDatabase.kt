package com.mslynch.awesomesource.organize.persistence

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import com.mslynch.awesomesource.organize.persistence.dao.LibraryDao
import com.mslynch.awesomesource.organize.persistence.dao.QueryCacheDao
import com.mslynch.awesomesource.organize.persistence.dao.SidecarMetadataDao
import com.mslynch.awesomesource.organize.persistence.dao.TrackDao
import com.mslynch.awesomesource.organize.persistence.dao.UndoLogDao
import com.mslynch.awesomesource.organize.persistence.entity.GeminiGroundingCacheEntity
import com.mslynch.awesomesource.organize.persistence.entity.LibraryEntity
import com.mslynch.awesomesource.organize.persistence.entity.MbQueryCacheEntity
import com.mslynch.awesomesource.organize.persistence.entity.ScanSessionEntity
import com.mslynch.awesomesource.organize.persistence.entity.SidecarMetadataEntity
import com.mslynch.awesomesource.organize.persistence.entity.TagChangeEntity
import com.mslynch.awesomesource.organize.persistence.entity.TrackArtistCreditEntity
import com.mslynch.awesomesource.organize.persistence.entity.TrackEntity

/**
 * Room descendant of `legacy-desktop-tagger/musictagger/persistence/db.py`'s SQLite
 * schema, extended with the libraries/sidecar-metadata/Gemini-cache tables the
 * Android app needs that the desktop tool didn't. Version starts at 1 since this is
 * a from-scratch schema, not a migration of the old `data/app.db` file (which stays
 * archived alongside the retired desktop tool).
 */
@Database(
    entities = [
        TrackEntity::class,
        TrackArtistCreditEntity::class,
        LibraryEntity::class,
        SidecarMetadataEntity::class,
        ScanSessionEntity::class,
        TagChangeEntity::class,
        MbQueryCacheEntity::class,
        GeminiGroundingCacheEntity::class,
    ],
    // Bumped from 1: TrackEntity gained matchedReleaseId/proposed* columns for the
    // review-status feature. No migration is written since this is pre-release,
    // schema-unstable dev data with no real users yet - fallbackToDestructiveMigration
    // below just wipes and recreates on a version mismatch, consistent with how
    // every schema change has been handled so far in this project.
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun libraryDao(): LibraryDao
    abstract fun sidecarMetadataDao(): SidecarMetadataDao
    abstract fun undoLogDao(): UndoLogDao
    abstract fun queryCacheDao(): QueryCacheDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "awesomesource.db",
                ).fallbackToDestructiveMigration(true).build().also { instance = it }
            }
    }
}
