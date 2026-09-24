package com.mslynch.awesomesource.organize.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mslynch.awesomesource.organize.persistence.entity.ScanSessionEntity
import com.mslynch.awesomesource.organize.persistence.entity.TagChangeEntity

/**
 * Mirrors `legacy-desktop-tagger/musictagger/persistence/db.py`'s scan-session and
 * undo-log functions. The actual undo *action* (replaying old values back onto
 * disk) belongs in a repository that also has the tag-writer available - see
 * db.py's `undo_batch`, which combines this table's rows with
 * `tags/writer.apply_raw_fields`; this DAO only owns the SQL.
 */
@Dao
interface UndoLogDao {
    @Insert
    suspend fun insertSession(session: ScanSessionEntity): Long

    @Query("UPDATE scan_sessions SET completedAt = :completedAt, status = 'completed' WHERE id = :id")
    suspend fun completeSession(id: Long, completedAt: String)

    @Insert
    suspend fun insertTagChanges(changes: List<TagChangeEntity>)

    @Query("SELECT batchId FROM tag_changes WHERE undone = 0 ORDER BY id DESC LIMIT 1")
    suspend fun getLastBatchId(): String?

    @Query("SELECT DISTINCT batchId FROM tag_changes WHERE undone = 0 ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentBatchIds(limit: Int = 20): List<String>

    @Query(
        "SELECT * FROM tag_changes WHERE batchId = :batchId AND undone = 0 AND fieldName != 'cover_art'"
    )
    suspend fun getUndoableChanges(batchId: String): List<TagChangeEntity>

    @Query("UPDATE tag_changes SET undone = 1 WHERE batchId = :batchId")
    suspend fun markBatchUndone(batchId: String)
}
