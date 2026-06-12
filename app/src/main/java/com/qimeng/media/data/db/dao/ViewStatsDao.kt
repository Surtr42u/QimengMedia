package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.ViewStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ViewStatsDao {
    @Query("SELECT * FROM view_stats WHERE recordKey = :recordKey")
    fun observeByRecordKey(recordKey: String): Flow<ViewStatsEntity?>

    @Query("SELECT * FROM view_stats WHERE recordKey = :recordKey")
    suspend fun getByRecordKey(recordKey: String): ViewStatsEntity?

    @Query("SELECT * FROM view_stats ORDER BY fileName ASC")
    fun observeAllByFileName(): Flow<List<ViewStatsEntity>>

    @Query("SELECT * FROM view_stats ORDER BY fileName ASC")
    suspend fun getAllByFileName(): List<ViewStatsEntity>

    @Query("SELECT * FROM view_stats WHERE recordKey IN (:recordKeys)")
    suspend fun getByRecordKeys(recordKeys: List<String>): List<ViewStatsEntity>

    @Query("SELECT * FROM view_stats ORDER BY (viewCount + playCount) DESC, lastOpenedAtMillis DESC LIMIT :limit")
    fun observeTopByHeat(limit: Int): Flow<List<ViewStatsEntity>>

    @Upsert
    suspend fun upsert(stats: ViewStatsEntity)

    @Query("DELETE FROM view_stats")
    suspend fun clearAll()
}
