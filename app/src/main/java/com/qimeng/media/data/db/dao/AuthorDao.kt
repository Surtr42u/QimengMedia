package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorFileCount
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface AuthorDao {
    @Query("SELECT * FROM authors ORDER BY displayName ASC")
    fun observeAuthors(): Flow<List<AuthorEntity>>

    @Query("SELECT * FROM authors ORDER BY displayName ASC")
    suspend fun getAllAuthors(): List<AuthorEntity>

    @Query("SELECT * FROM authors WHERE authorId = :authorId")
    suspend fun getAuthor(authorId: String): AuthorEntity?

    @Query("SELECT COUNT(*) FROM authors")
    fun observeAuthorCount(): Flow<Int>

    @Query("SELECT * FROM author_media_cross_refs WHERE authorId = :authorId ORDER BY fileName ASC")
    fun observeMediaForAuthor(authorId: String): Flow<List<AuthorMediaCrossRef>>

    @Query("SELECT * FROM author_media_cross_refs WHERE recordKey = :recordKey ORDER BY authorId ASC")
    fun observeAuthorsForMedia(recordKey: String): Flow<List<AuthorMediaCrossRef>>

    @Query("SELECT * FROM author_media_cross_refs ORDER BY fileName ASC")
    fun observeAllAuthorMedia(): Flow<List<AuthorMediaCrossRef>>

    @Query("SELECT authorId, COUNT(*) as fileCount FROM author_media_cross_refs GROUP BY authorId")
    fun observeAuthorFileCounts(): Flow<List<AuthorFileCount>>

    @Upsert
    suspend fun upsertAuthor(author: AuthorEntity)

    @Upsert
    suspend fun upsertAllAuthors(authors: List<AuthorEntity>)

    @Upsert
    suspend fun upsertAuthorMedia(crossRef: AuthorMediaCrossRef)

    @Upsert
    suspend fun upsertAllAuthorMedia(crossRefs: List<AuthorMediaCrossRef>)

    @Query("DELETE FROM author_media_cross_refs WHERE authorId = :authorId AND recordKey = :recordKey")
    suspend fun removeAuthorMedia(authorId: String, recordKey: String)

    @Query("DELETE FROM author_media_cross_refs")
    suspend fun clearAuthorMedia()

    @Query("DELETE FROM authors WHERE authorId = :authorId")
    suspend fun deleteAuthor(authorId: String)

    @Query("DELETE FROM authors WHERE authorId IN (:authorIds)")
    suspend fun deleteAuthorsByIds(authorIds: List<String>)

    @Query("DELETE FROM authors")
    suspend fun clearAll()

    @Query("DELETE FROM author_media_cross_refs WHERE recordKey IN (:recordKeys)")
    suspend fun deleteCrossRefsByRecordKeys(recordKeys: List<String>)

    @Query("DELETE FROM author_media_cross_refs WHERE authorId IN (:authorIds)")
    suspend fun deleteCrossRefsByAuthorIds(authorIds: List<String>)

    @Query("DELETE FROM authors WHERE authorId NOT IN (SELECT DISTINCT authorId FROM author_media_cross_refs)")
    suspend fun deleteOrphanAuthors()

    @Query("DELETE FROM authors WHERE authorId LIKE 'cos\\_%' ESCAPE '\\' AND authorId NOT IN (SELECT DISTINCT authorId FROM author_media_cross_refs)")
    suspend fun deleteOrphanCosAuthors()
}
