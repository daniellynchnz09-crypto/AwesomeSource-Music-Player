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
