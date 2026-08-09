package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.DailyBrowseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyBrowseDao {
    /** 观察最近 limit 天的浏览聚合（按天倒序，limit 大时返回全量） */
    @Query("SELECT * FROM daily_browse WHERE dayStartMillis >= :cutoff ORDER BY dayStartMillis DESC LIMIT :limit")
    fun observeSince(cutoff: Long, limit: Int): Flow<List<DailyBrowseEntity>>

    /** 全量查询（备份导出用） */
    @Query("SELECT * FROM daily_browse ORDER BY dayStartMillis DESC")
    suspend fun getAll(): List<DailyBrowseEntity>

    /** 某天某文件浏览次数 +1（原子更新，避免读改写并发丢更新） */
    @Query("UPDATE daily_browse SET viewCount = viewCount + 1, updatedAtMillis = :now WHERE recordKeyDay = :recordKeyDay")
    suspend fun incrementViewCount(recordKeyDay: String, now: Long): Int

    /** 某天某文件播放次数 +1（原子更新） */
    @Query("UPDATE daily_browse SET playCount = playCount + 1, updatedAtMillis = :now WHERE recordKeyDay = :recordKeyDay")
    suspend fun incrementPlayCount(recordKeyDay: String, now: Long): Int

    /** 某天某文件浏览时长追加（原子更新） */
    @Query("UPDATE daily_browse SET totalBrowseSeconds = totalBrowseSeconds + :seconds, updatedAtMillis = :now WHERE recordKeyDay = :recordKeyDay")
    suspend fun addBrowseSeconds(recordKeyDay: String, seconds: Long, now: Long): Int

    @Upsert
    suspend fun upsert(entity: DailyBrowseEntity)

    @Query("DELETE FROM daily_browse WHERE recordKey IN (:recordKeys)")
    suspend fun deleteByRecordKeys(recordKeys: List<String>)

    @Query("DELETE FROM daily_browse")
    suspend fun clearAll()
}
