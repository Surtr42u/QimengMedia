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
