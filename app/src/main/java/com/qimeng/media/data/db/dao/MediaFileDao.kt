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

    // LIKE 前缀查询必须带 ESCAPE '\'：tree URI 含 %3A 等百分号编码时，未转义的 % 会退化成
    // 通配符导致跨前缀误匹配（调用方透传前须先 escapeLikePattern 转义 % _ \）

    @Query("SELECT recordKey FROM media_files WHERE uriString LIKE :prefix || '%' ESCAPE '\\' AND isCosFile = 0")
    suspend fun getRecordKeysByUriPrefix(prefix: String): List<String>

    @Query("SELECT recordKey FROM media_files WHERE uriString LIKE :prefix || '%' ESCAPE '\\' AND isCosFile = 1")
    suspend fun getCosRecordKeysByUriPrefix(prefix: String): List<String>

    @Query("SELECT recordKey, uriString, sourceUri FROM media_files WHERE isCosFile = 0")
    suspend fun getNonCosKeysAndUris(): List<NonCosKeyUri>

    @Query("SELECT recordKey, uriString, sourceUri FROM media_files WHERE isCosFile = 1")
    suspend fun getCosKeysAndUris(): List<NonCosKeyUri>

    data class NonCosKeyUri(val recordKey: String, val uriString: String, val sourceUri: String?)

    data class NonCosKeyFileName(val recordKey: String, val fileName: String)

    @Query("SELECT recordKey, fileName FROM media_files WHERE isCosFile = 0")
    suspend fun getNonCosKeysAndFileNames(): List<NonCosKeyFileName>

    /** 备份导入用全表轻量投影（recordKey+fileName，不分 COS）：仅用键存在性与文件名回填，不物化 18 列全实体 */
    @Query("SELECT recordKey, fileName FROM media_files")
    suspend fun getAllKeysAndFileNames(): List<NonCosKeyFileName>

    @Query("SELECT recordKey FROM media_files WHERE isCosFile = 1 AND uriString LIKE :prefix || '%' ESCAPE '\\'")
    suspend fun getCosRecordKeysByUriPrefixLight(prefix: String): List<String>

    @Query("SELECT recordKey FROM media_files WHERE isCosFile = 1")
    suspend fun getAllCosRecordKeys(): List<String>

    @Query("SELECT recordKey FROM media_files WHERE isCosFile = 0")
    suspend fun getAllNonCosRecordKeys(): List<String>

    /**
     * 按扫描源 tree URI 精确查询归属文件（v1.12 新增）。
     * 用于删除扫描源/启动清理：比旧的 URI 前缀匹配（content://media 等文件 URI 与
     * tree URI 命名空间不同恒匹配失败）可靠。
     * [sourceUri] 为 null 的旧版数据行不匹配（保守保留）。
     */
    @Query("SELECT recordKey FROM media_files WHERE sourceUri = :sourceUri")
    suspend fun getRecordKeysBySourceUri(sourceUri: String): List<String>

    /** v1.12：按扫描源 tree URI 精确查询归属 COS 文件。 */
    @Query("SELECT recordKey FROM media_files WHERE sourceUri = :sourceUri AND isCosFile = 1")
    suspend fun getCosRecordKeysBySourceUri(sourceUri: String): List<String>

    /** v1.12：按扫描源 tree URI 删除归属文件（供清理孤儿文件使用）。 */
    @Query("DELETE FROM media_files WHERE sourceUri = :sourceUri")
    suspend fun deleteBySourceUri(sourceUri: String)

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
