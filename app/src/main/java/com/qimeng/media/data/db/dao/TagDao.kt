package com.qimeng.media.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.qimeng.media.data.db.entity.MediaTagCrossRef
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.data.db.model.MediaTagName
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeTags(): Flow<List<TagEntity>>

    /** 详情页标签弹窗使用：按创建时间倒序（新标签置顶），创建时间相同按名字 */
    @Query("SELECT * FROM tags ORDER BY createdAtMillis DESC, name ASC")
    fun observeTagsNewestFirst(): Flow<List<TagEntity>>

    @Query("""
        SELECT media_tag_cross_refs.recordKey AS recordKey, tags.tagId AS tagId, tags.name AS name
        FROM media_tag_cross_refs
        INNER JOIN tags ON tags.tagId = media_tag_cross_refs.tagId
        ORDER BY tags.name ASC
    """)
    fun observeAllMediaTagNames(): Flow<List<MediaTagName>>

    @Query("""
        SELECT tags.* FROM tags
        INNER JOIN media_tag_cross_refs ON tags.tagId = media_tag_cross_refs.tagId
        WHERE media_tag_cross_refs.recordKey = :recordKey
        ORDER BY tags.name ASC
    """)
    fun observeTagEntitiesForMedia(recordKey: String): Flow<List<TagEntity>>

    /** 详情页标签弹窗使用：按文件-标签关联时间倒序（最近添加的标签置顶，删除后重新添加同样置顶） */
    @Query("""
        SELECT tags.* FROM tags
        INNER JOIN media_tag_cross_refs ON tags.tagId = media_tag_cross_refs.tagId
        WHERE media_tag_cross_refs.recordKey = :recordKey
        ORDER BY media_tag_cross_refs.createdAtMillis DESC, tags.name ASC
    """)
    fun observeTagEntitiesForMediaNewestFirst(recordKey: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAllTags(): List<TagEntity>

    @Query("SELECT * FROM media_tag_cross_refs ORDER BY recordKey ASC")
    suspend fun getAllCrossRefs(): List<MediaTagCrossRef>

    @Query("SELECT * FROM media_tag_cross_refs WHERE recordKey IN (:recordKeys)")
    suspend fun getCrossRefsByRecordKeys(recordKeys: List<String>): List<MediaTagCrossRef>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Upsert
    suspend fun upsertCrossRef(crossRef: MediaTagCrossRef)

    @Query("SELECT * FROM media_tag_cross_refs WHERE recordKey = :recordKey")
    fun observeTagsForMedia(recordKey: String): Flow<List<MediaTagCrossRef>>

    @Query("DELETE FROM media_tag_cross_refs WHERE recordKey = :recordKey AND tagId = :tagId")
    suspend fun removeTagFromMedia(recordKey: String, tagId: Long)

    @Query("DELETE FROM media_tag_cross_refs")
    suspend fun clearCrossRefs()

    @Query("DELETE FROM tags WHERE tagId = :tagId")
    suspend fun deleteTag(tagId: Long)

    @Query("DELETE FROM tags")
    suspend fun clearAll()

    @Query("DELETE FROM media_tag_cross_refs WHERE recordKey IN (:recordKeys)")
    suspend fun deleteCrossRefsByRecordKeys(recordKeys: List<String>)
}
