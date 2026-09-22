package com.mslynch.awesomesource.organize.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mslynch.awesomesource.organize.persistence.entity.GeminiGroundingCacheEntity
import com.mslynch.awesomesource.organize.persistence.entity.MbQueryCacheEntity

@Dao
interface QueryCacheDao {
    @Query("SELECT responseJson FROM mb_query_cache WHERE queryKey = :key")
    suspend fun getCachedMbQuery(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCachedMbQuery(entity: MbQueryCacheEntity)

    // Cached MusicBrainz results are a verbatim replay of whatever the matching
    // logic decided at cache time - if the scorer's tuning later changes, stale
    // entries keep serving the pre-fix answer until cleared. No automatic
    // invalidation tied to code changes; exposed as a manual action, same as
    // db.py's `clear_query_cache`.
    @Query("DELETE FROM mb_query_cache")
    suspend fun clearMbQueryCache(): Int

    @Query("SELECT responseJson FROM gemini_grounding_cache WHERE queryKey = :key")
    suspend fun getCachedGeminiResponse(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCachedGeminiResponse(entity: GeminiGroundingCacheEntity)

    @Query("DELETE FROM gemini_grounding_cache")
    suspend fun clearGeminiCache(): Int
}
