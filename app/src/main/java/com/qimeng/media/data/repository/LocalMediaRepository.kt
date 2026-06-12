package com.qimeng.media.data.repository

import com.qimeng.media.data.db.dao.MediaFileDao
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorFileCount
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.MediaTagCrossRef
import com.qimeng.media.data.db.entity.ScanSourceEntity
import com.qimeng.media.data.db.entity.SettingEntity
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.data.db.entity.TimelineTagEntity
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.data.db.model.MediaTagName
import kotlinx.coroutines.flow.Flow

interface LocalMediaRepository {
    fun observeAllMedia(): Flow<List<MediaFileEntity>>
    fun observeNonCosMedia(): Flow<List<MediaFileEntity>>
    fun observeMediaByType(mediaType: String): Flow<List<MediaFileEntity>>
    fun observeMediaCount(mediaType: String): Flow<Int>
    fun observeLatestHistory(limit: Int = HISTORY_LIMIT): Flow<List<ViewHistoryEntity>>
    fun observeAuthors(): Flow<List<AuthorEntity>>
    fun observeAuthorCount(): Flow<Int>
    fun observeMediaForAuthor(authorId: String): Flow<List<AuthorMediaCrossRef>>
    fun observeAuthorsForMedia(recordKey: String): Flow<List<AuthorMediaCrossRef>>
    fun observeAllAuthorMedia(): Flow<List<AuthorMediaCrossRef>>
    fun observeAuthorFileCounts(): Flow<List<AuthorFileCount>>
    fun observeScanSources(): Flow<List<ScanSourceEntity>>
    fun observeAllStats(): Flow<List<ViewStatsEntity>>
    fun observeAllTags(): Flow<List<TagEntity>>
    fun observeAllMediaTagNames(): Flow<List<MediaTagName>>
    fun observeSetting(key: String): Flow<SettingEntity?>

    suspend fun getSetting(key: String): SettingEntity?

    suspend fun upsertMedia(mediaFiles: List<MediaFileEntity>)
    suspend fun getMediaByKey(recordKey: String): MediaFileEntity?
    suspend fun getAllMedia(): List<MediaFileEntity>
    suspend fun getMediaByType(mediaType: String): List<MediaFileEntity>
    suspend fun getAllUriStrings(): List<String>
    suspend fun getRecordKeysByUriPrefix(prefix: String): List<String>
    suspend fun getCosRecordKeysByUriPrefix(prefix: String): List<String>
    suspend fun getNonCosKeysAndUris(): List<MediaFileDao.NonCosKeyUri>
    suspend fun getCosKeysAndUris(): List<MediaFileDao.NonCosKeyUri>
    suspend fun getNonCosKeysAndFileNames(): List<MediaFileDao.NonCosKeyFileName>
    suspend fun recordView(recordKey: String, fileName: String)
    suspend fun recordHistory(history: ViewHistoryEntity)
    suspend fun upsertStats(stats: ViewStatsEntity)
    suspend fun upsertAuthor(author: AuthorEntity)
    suspend fun upsertAllAuthors(authors: List<AuthorEntity>)
    suspend fun upsertAuthorMedia(crossRef: AuthorMediaCrossRef)
    suspend fun upsertAllAuthorMedia(crossRefs: List<AuthorMediaCrossRef>)
    suspend fun getScanSources(): List<ScanSourceEntity>
    suspend fun upsertScanSource(source: ScanSourceEntity)
    suspend fun deleteScanSource(uriString: String)
    suspend fun deleteMediaAndRefs(recordKeys: List<String>)
    suspend fun deleteOrphanAuthors()
    suspend fun deleteOrphanCosAuthors()
    suspend fun deleteCrossRefsByAuthorIds(authorIds: List<String>)
    suspend fun upsertSetting(setting: SettingEntity)
    suspend fun deleteSetting(key: String)
    suspend fun clearHistory()
    suspend fun clearAllAuthors()
    suspend fun deleteAuthor(authorId: String)
    suspend fun deleteAuthorsByIds(authorIds: List<String>)
    suspend fun getAllAuthors(): List<AuthorEntity>
    suspend fun rebuildMediaIndex(mediaFiles: List<MediaFileEntity>)

    fun observeCosWorks(): Flow<List<CosWorkEntity>>
    fun observeCosAuthors(): Flow<List<String>>
    fun observeCosWorksByAuthor(authorName: String): Flow<List<CosWorkEntity>>
    fun observeCosMedia(): Flow<List<MediaFileEntity>>
    fun observeCosScanSources(): Flow<List<ScanSourceEntity>>
    suspend fun getCosScanSources(): List<ScanSourceEntity>
    suspend fun upsertCosWorks(works: List<CosWorkEntity>)
    suspend fun rebuildCosIndex(mediaFiles: List<MediaFileEntity>, cosWorks: List<CosWorkEntity>)
    suspend fun deleteCosScanSource(uriString: String)

    suspend fun getTagByName(name: String): TagEntity?
    suspend fun insertTag(tag: TagEntity): TagEntity?
    suspend fun upsertTagCrossRef(crossRef: MediaTagCrossRef)
    suspend fun deleteTagById(tagId: Long)
    suspend fun removeTagFromMedia(recordKey: String, tagId: Long)
    suspend fun updateBrowseSeconds(recordKey: String, seconds: Long)
    fun observeTagsForMedia(recordKey: String): Flow<List<MediaTagCrossRef>>
    fun observeTagEntitiesForMedia(recordKey: String): Flow<List<TagEntity>>
    suspend fun updateMediaMetadata(recordKey: String, width: Int?, height: Int?, durationMillis: Long?)

    // 时间轴标签
    fun observeTimelineTags(recordKey: String): Flow<List<TimelineTagEntity>>
    suspend fun upsertTimelineTag(tag: TimelineTagEntity)
    suspend fun deleteTimelineTag(timelineTagId: Long)
    suspend fun getAllTimelineTags(): List<TimelineTagEntity>

    companion object {
        const val HISTORY_LIMIT = 500
    }
}
