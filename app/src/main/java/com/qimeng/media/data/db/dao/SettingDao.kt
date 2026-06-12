package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.SettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings WHERE key = :key")
    fun observeSetting(key: String): Flow<SettingEntity?>

    @Query("SELECT * FROM settings WHERE key = :key")
    suspend fun getSetting(key: String): SettingEntity?

    @Query("SELECT * FROM settings ORDER BY key ASC")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings ORDER BY key ASC")
    suspend fun getAll(): List<SettingEntity>

    @Upsert
    suspend fun upsert(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM settings")
    suspend fun clearAll()
}
