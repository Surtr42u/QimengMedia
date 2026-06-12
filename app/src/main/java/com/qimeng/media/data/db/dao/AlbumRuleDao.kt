package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.AlbumRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumRuleDao {
    @Query("SELECT * FROM album_rules ORDER BY LENGTH(sourceName) DESC, sourceName ASC")
    fun observeAllRules(): Flow<List<AlbumRuleEntity>>

    @Query("SELECT * FROM album_rules WHERE enabled = 1 ORDER BY LENGTH(sourceName) DESC, sourceName ASC")
    suspend fun getEnabledRulesByPriority(): List<AlbumRuleEntity>

    @Upsert
    suspend fun upsert(rule: AlbumRuleEntity)

    @Query("DELETE FROM album_rules WHERE albumRuleId = :albumRuleId")
    suspend fun deleteById(albumRuleId: Long)

    @Query("DELETE FROM album_rules")
    suspend fun clearAll()
}
