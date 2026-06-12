package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.ScanSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSourceDao {
    @Query("SELECT * FROM scan_sources ORDER BY addedAtMillis DESC")
    fun observeSources(): Flow<List<ScanSourceEntity>>

    @Query("SELECT * FROM scan_sources ORDER BY addedAtMillis DESC")
    suspend fun getAllSources(): List<ScanSourceEntity>

    @Upsert
    suspend fun upsert(source: ScanSourceEntity)

    @Query("DELETE FROM scan_sources WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)

    @Query("DELETE FROM scan_sources")
    suspend fun clearAll()

    @Query("SELECT * FROM scan_sources WHERE isCosDirectory = 1 ORDER BY addedAtMillis DESC")
    fun observeCosSources(): Flow<List<ScanSourceEntity>>

    @Query("SELECT * FROM scan_sources WHERE isCosDirectory = 1 ORDER BY addedAtMillis DESC")
    suspend fun getCosSources(): List<ScanSourceEntity>
}
