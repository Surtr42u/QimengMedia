package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ViewHistoryDao {
    @Query("SELECT * FROM view_history ORDER BY openedAtMillis DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<ViewHistoryEntity>>

    @Query("SELECT * FROM view_history ORDER BY openedAtMillis DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<ViewHistoryEntity>

    @Upsert
    suspend fun upsert(history: ViewHistoryEntity)

    @Query("DELETE FROM view_history WHERE recordKey NOT IN (SELECT recordKey FROM view_history ORDER BY openedAtMillis DESC LIMIT :limit)")
    suspend fun pruneToLimit(limit: Int)

    @Query("DELETE FROM view_history")
    suspend fun clearAll()
}
