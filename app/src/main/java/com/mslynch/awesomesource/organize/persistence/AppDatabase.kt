package com.mslynch.awesomesource.organize.persistence

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    // Bumped from 1 -> 2: TrackEntity gained matchedReleaseId/proposed* columns for
    // the review-status feature (no migration written then - this was pre-release,
    // schema-unstable dev data with no real scanned library yet, so
    // fallbackToDestructiveMigration's wipe-and-recreate was an acceptable trade).
    // Bumped 2 -> 3: TrackEntity gained coverArtPath for the Library row thumbnail
    // feature - this time a real Migration is written instead of relying on the
    // destructive fallback, since by this point the user has a real, hard-won,
    // fully-organized ~1368-track library (review statuses, accepted matches, bulk
    // edits) that a silent wipe would have thrown away for a purely additive column.
    version = 3,
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN coverArtPath TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "awesomesource.db",
                )
                    .addMigrations(MIGRATION_2_3)
                    // Still kept as a safety net for any version jump the explicit
                    // migrations above don't cover (e.g. a much older version 1 db).
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
    }
}
