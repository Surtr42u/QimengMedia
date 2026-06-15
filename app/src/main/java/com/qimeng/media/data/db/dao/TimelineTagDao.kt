package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.TimelineTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineTagDao {
    @Query("SELECT * FROM timeline_tags WHERE recordKey = :recordKey ORDER BY timeMillis ASC")
    fun observeForVideo(recordKey: String): Flow<List<TimelineTagEntity>>

    @Upsert
    suspend fun upsert(tag: TimelineTagEntity)

    @Query("DELETE FROM timeline_tags WHERE timelineTagId = :timelineTagId")
    suspend fun deleteById(timelineTagId: Long)

    @Query("DELETE FROM timeline_tags")
    suspend fun clearAll()

    @Query("DELETE FROM timeline_tags WHERE recordKey IN (:recordKeys)")
    suspend fun deleteByRecordKeys(recordKeys: List<String>)

    @Query("SELECT * FROM timeline_tags ORDER BY recordKey, timeMillis ASC")
    suspend fun getAll(): List<TimelineTagEntity>
}
