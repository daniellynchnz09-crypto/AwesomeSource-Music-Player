package com.mslynch.awesomesource.organize.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mslynch.awesomesource.organize.persistence.entity.LibraryEntity
import com.mslynch.awesomesource.organize.persistence.entity.SidecarMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(library: LibraryEntity): Long

    @Query("SELECT * FROM libraries ORDER BY name")
    fun observeAll(): Flow<List<LibraryEntity>>

    @Query("DELETE FROM libraries WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SidecarMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SidecarMetadataEntity)

    @Query("SELECT * FROM sidecar_metadata WHERE path = :path")
    suspend fun getByPath(path: String): SidecarMetadataEntity?
}
