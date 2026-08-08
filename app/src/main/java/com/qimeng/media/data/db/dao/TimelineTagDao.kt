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

    /** v1.12：按 (recordKey, timeMillis, name) 查重，避免备份重复导入时间轴标签翻倍。
     * TimelineTagEntity 主键为 autoGenerate，构造恒为 0，@Upsert 每次都会 INSERT 新行。 */
    @Query("SELECT timelineTagId FROM timeline_tags WHERE recordKey = :recordKey AND timeMillis = :timeMillis AND name = :name LIMIT 1")
    suspend fun findExisting(recordKey: String, timeMillis: Long, name: String): Long?

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
