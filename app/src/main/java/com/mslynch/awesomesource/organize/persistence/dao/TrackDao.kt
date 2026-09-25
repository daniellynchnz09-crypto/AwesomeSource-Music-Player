package com.mslynch.awesomesource.organize.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mslynch.awesomesource.organize.persistence.entity.TrackArtistCreditEntity
import com.mslynch.awesomesource.organize.persistence.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE path = :path")
    suspend fun getByPath(path: String): TrackEntity?

    /** Tracks the pipeline already confidently matched (so a release ID is known)
     * but which never got a cached artwork file - either because they were matched
     * before cover-art fetching existed, or the fetch failed/found nothing archived
     * at the time. `isCurated()` blocks these from ever going through a normal
     * rescan again, so this is the only path left to backfill `coverArtPath` for
     * them - see `OrganizeLibrary.backfillMissingCoverArt`. */
    @Query("SELECT * FROM tracks WHERE matchedReleaseId IS NOT NULL AND coverArtPath IS NULL")
    suspend fun getCuratedMissingArt(): List<TrackEntity>

    /** Narrow, single-column update so a cover-art backfill can't touch any of a
     * curated track's protected fields - see `isCurated()`. */
    @Query("UPDATE tracks SET coverArtPath = :coverArtPath WHERE path = :path")
    suspend fun updateCoverArtPath(path: String, coverArtPath: String)

    @Query("SELECT * FROM tracks WHERE path IN (:paths)")
    suspend fun getByPaths(paths: List<String>): List<TrackEntity>

    /** Every track whose path starts with `folderPrefix/` - a superset of the
     * immediate folder siblings (nested subfolders match too), narrowed down to
     * direct children in Kotlin by the caller since SQLite has no portable way to
     * express "no further '/' after the prefix" - see
     * `OrganizeLibrary.discoverAlbumSiblings`. */
    @Query("SELECT * FROM tracks WHERE path LIKE :folderPrefix || '/%' AND path NOT IN (:excludePaths)")
    suspend fun getPathsUnderFolder(folderPrefix: String, excludePaths: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE path = :path")
    fun observeByPath(path: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks ORDER BY path")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY path")
    suspend fun getAll(): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT * FROM tracks WHERE status = :status")
    fun observeByStatus(status: String): Flow<List<TrackEntity>>

    @Query("DELETE FROM tracks WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCredits(credits: List<TrackArtistCreditEntity>)

    @Query("DELETE FROM track_artist_credits WHERE trackPath = :trackPath")
    suspend fun clearCredits(trackPath: String)

    @Query("SELECT * FROM track_artist_credits WHERE trackPath = :trackPath")
    suspend fun getCredits(trackPath: String): List<TrackArtistCreditEntity>
}
