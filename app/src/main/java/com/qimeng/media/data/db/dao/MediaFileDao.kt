package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.MediaFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaFileDao {
    @Query("SELECT * FROM media_files ORDER BY modifiedAtMillis DESC")
    fun observeAll(): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_files WHERE isCosFile = 0 ORDER BY modifiedAtMillis DESC")
    fun observeNonCosMedia(): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_files WHERE mediaType = :mediaType ORDER BY modifiedAtMillis DESC")
    fun observeByType(mediaType: String): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_files WHERE recordKey = :recordKey")
    suspend fun getByRecordKey(recordKey: String): MediaFileEntity?

    @Query("SELECT * FROM media_files")
    suspend fun getAll(): List<MediaFileEntity>

    @Query("SELECT * FROM media_files WHERE mediaType = :mediaType")
    suspend fun getByType(mediaType: String): List<MediaFileEntity>

    @Query("SELECT uriString FROM media_files")
    suspend fun getAllUriStrings(): List<String>

    @Query("SELECT COUNT(*) FROM media_files WHERE mediaType = :mediaType")
    fun observeCountByType(mediaType: String): Flow<Int>

    @Upsert
    suspend fun upsert(mediaFile: MediaFileEntity)

    @Upsert
    suspend fun upsertAll(mediaFiles: List<MediaFileEntity>)

    @Query("DELETE FROM media_files")
    suspend fun clearIndex()

    @Query("DELETE FROM media_files WHERE recordKey IN (:recordKeys)")
    suspend fun deleteByRecordKeys(recordKeys: List<String>)

    @Query("SELECT * FROM media_files WHERE isCosFile = 1 ORDER BY modifiedAtMillis DESC")
    fun observeCosMedia(): Flow<List<MediaFileEntity>>

    @Query("DELETE FROM media_files WHERE isCosFile = 1")
    suspend fun deleteCosMedia()

    @Query("DELETE FROM media_files WHERE isCosFile = 0")
    suspend fun clearNonCosIndex()

    @Query("SELECT recordKey FROM media_files WHERE uriString LIKE :prefix || '%' AND isCosFile = 0")
    suspend fun getRecordKeysByUriPrefix(prefix: String): List<String>

    @Query("SELECT recordKey FROM media_files WHERE uriString LIKE :prefix || '%' AND isCosFile = 1")
    suspend fun getCosRecordKeysByUriPrefix(prefix: String): List<String>

    @Query("SELECT recordKey, uriString FROM media_files WHERE isCosFile = 0")
    suspend fun getNonCosKeysAndUris(): List<NonCosKeyUri>

    @Query("SELECT recordKey, uriString FROM media_files WHERE isCosFile = 1")
    suspend fun getCosKeysAndUris(): List<NonCosKeyUri>

    data class NonCosKeyUri(val recordKey: String, val uriString: String)

    data class NonCosKeyFileName(val recordKey: String, val fileName: String)

    @Query("SELECT recordKey, fileName FROM media_files WHERE isCosFile = 0")
    suspend fun getNonCosKeysAndFileNames(): List<NonCosKeyFileName>

    @Query("SELECT recordKey FROM media_files WHERE isCosFile = 1 AND uriString LIKE :prefix || '%'")
    suspend fun getCosRecordKeysByUriPrefixLight(prefix: String): List<String>

    @Query("SELECT recordKey FROM media_files WHERE isCosFile = 1")
    suspend fun getAllCosRecordKeys(): List<String>

    @Query("SELECT recordKey FROM media_files")
    suspend fun getAllRecordKeys(): List<String>

    /**
     * 轻量空库检测：EXISTS + LIMIT 1 命中首行即短路返回，不物化任何行/列。
     * 用于 AutoSyncUseCase 空库覆盖防护，比 getAllRecordKeys().isEmpty() 更省内存。
     */
    @Query("SELECT EXISTS(SELECT 1 FROM media_files LIMIT 1)")
    suspend fun hasAny(): Boolean

    @Query("UPDATE media_files SET width = :width, height = :height, durationMillis = :durationMillis WHERE recordKey = :recordKey")
    suspend fun updateMetadata(recordKey: String, width: Int?, height: Int?, durationMillis: Long?)
}
