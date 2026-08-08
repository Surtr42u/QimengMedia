package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.CosWorkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CosWorkDao {
    @Query("SELECT * FROM cos_works ORDER BY authorName, workName")
    fun observeAll(): Flow<List<CosWorkEntity>>

    @Query("SELECT * FROM cos_works ORDER BY authorName, workName")
    suspend fun getAll(): List<CosWorkEntity>

    @Query("SELECT DISTINCT authorName FROM cos_works ORDER BY authorName")
    fun observeAuthors(): Flow<List<String>>

    @Query("SELECT * FROM cos_works WHERE authorName = :authorName ORDER BY workName")
    fun observeByAuthor(authorName: String): Flow<List<CosWorkEntity>>

    @Query("SELECT * FROM cos_works WHERE authorName = :authorName ORDER BY workName")
    suspend fun getByAuthor(authorName: String): List<CosWorkEntity>

    /** v1.12：按扫描源 tree URI 查询归属作品（删除扫描源时先取作品再删归属文件）。 */
    @Query("SELECT * FROM cos_works WHERE sourceUri = :sourceUri")
    suspend fun getBySourceUri(sourceUri: String): List<CosWorkEntity>

    /** v1.12：按扫描源 tree URI 删除归属作品。旧版 deleteByInactiveUris 用 folderUri 与
     * 扫描源 content URI 前缀匹配恒失败，删除任一 COS 源会误删全部源作品。 */
    @Query("DELETE FROM cos_works WHERE sourceUri = :sourceUri")
    suspend fun deleteBySourceUri(sourceUri: String)

    @Query("SELECT * FROM cos_works WHERE id = :id")
    suspend fun getById(id: Long): CosWorkEntity?

    @Upsert
    suspend fun upsert(work: CosWorkEntity)

    @Upsert
    suspend fun upsertAll(works: List<CosWorkEntity>)

    @Query("DELETE FROM cos_works")
    suspend fun clearAll()

    @Query("DELETE FROM cos_works WHERE folderUri NOT IN (:activeUris)")
    suspend fun deleteByInactiveUris(activeUris: List<String>)
}
