package com.qimeng.media.data.repository

import android.app.Application
import androidx.room.withTransaction
import com.qimeng.media.core.MediaCacheCleaner
import com.qimeng.media.core.MediaDetailPrefsCleaner
import com.qimeng.media.data.db.AppDatabase
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

class DefaultLocalMediaRepository(
    private val database: AppDatabase,
    private val application: Application
) : LocalMediaRepository {
    private val mediaFileDao = database.mediaFileDao()
    private val viewHistoryDao = database.viewHistoryDao()
    private val viewStatsDao = database.viewStatsDao()
    private val authorDao = database.authorDao()
    private val scanSourceDao = database.scanSourceDao()

    override fun observeAllMedia(): Flow<List<MediaFileEntity>> = mediaFileDao.observeAll()

    override fun observeNonCosMedia(): Flow<List<MediaFileEntity>> = mediaFileDao.observeNonCosMedia()

    override fun observeMediaByType(mediaType: String): Flow<List<MediaFileEntity>> =
        mediaFileDao.observeByType(mediaType)

    override fun observeMediaCount(mediaType: String): Flow<Int> =
        mediaFileDao.observeCountByType(mediaType)

    override fun observeLatestHistory(limit: Int): Flow<List<ViewHistoryEntity>> =
        viewHistoryDao.observeLatest(limit)

    override fun observeAuthors(): Flow<List<AuthorEntity>> = authorDao.observeAuthors()

    override fun observeAuthorCount(): Flow<Int> = authorDao.observeAuthorCount()

    override fun observeMediaForAuthor(authorId: String): Flow<List<AuthorMediaCrossRef>> =
        authorDao.observeMediaForAuthor(authorId)

    override fun observeAuthorsForMedia(recordKey: String): Flow<List<AuthorMediaCrossRef>> =
        authorDao.observeAuthorsForMedia(recordKey)

    override fun observeAllAuthorMedia(): Flow<List<AuthorMediaCrossRef>> =
        authorDao.observeAllAuthorMedia()

    override fun observeAuthorFileCounts(): Flow<List<AuthorFileCount>> =
        authorDao.observeAuthorFileCounts()

    override fun observeScanSources(): Flow<List<ScanSourceEntity>> = scanSourceDao.observeSources()

    override fun observeAllStats(): Flow<List<ViewStatsEntity>> = viewStatsDao.observeAllByFileName()

    override fun observeAllTags(): Flow<List<TagEntity>> = database.tagDao().observeTags()

    override fun observeAllMediaTagNames(): Flow<List<MediaTagName>> =
        database.tagDao().observeAllMediaTagNames()

    override fun observeSetting(key: String): Flow<SettingEntity?> = database.settingDao().observeSetting(key)

    override suspend fun getSetting(key: String): SettingEntity? = database.settingDao().getSetting(key)

    override suspend fun upsertMedia(mediaFiles: List<MediaFileEntity>) {
        mediaFileDao.upsertAll(mediaFiles)
    }

    override suspend fun getMediaByKey(recordKey: String): MediaFileEntity? =
        mediaFileDao.getByRecordKey(recordKey)

    override suspend fun getAllMedia(): List<MediaFileEntity> =
        mediaFileDao.getAll()

    override suspend fun getMediaByType(mediaType: String): List<MediaFileEntity> =
        mediaFileDao.getByType(mediaType)

    override suspend fun getAllUriStrings(): List<String> =
        mediaFileDao.getAllUriStrings()

    override suspend fun getRecordKeysByUriPrefix(prefix: String): List<String> =
        mediaFileDao.getRecordKeysByUriPrefix(prefix)

    override suspend fun getCosRecordKeysByUriPrefix(prefix: String): List<String> =
        mediaFileDao.getCosRecordKeysByUriPrefix(prefix)

    override suspend fun getNonCosKeysAndUris(): List<MediaFileDao.NonCosKeyUri> =
        mediaFileDao.getNonCosKeysAndUris()

    override suspend fun getCosKeysAndUris(): List<MediaFileDao.NonCosKeyUri> =
        mediaFileDao.getCosKeysAndUris()

    override suspend fun getNonCosKeysAndFileNames(): List<MediaFileDao.NonCosKeyFileName> =
        mediaFileDao.getNonCosKeysAndFileNames()

    override suspend fun getAllRecordKeys(): List<String> =
        mediaFileDao.getAllRecordKeys()

    override suspend fun recordView(recordKey: String, fileName: String) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            val mediaFile = mediaFileDao.getByRecordKey(recordKey)
            val mediaType = mediaFile?.mediaType ?: "image"

            val existing = viewStatsDao.getByRecordKey(recordKey)
            val stats = if (existing != null) {
                existing.copy(
                    viewCount = existing.viewCount + 1,
                    lastOpenedAtMillis = now,
                    updatedAtMillis = now
                )
            } else {
                ViewStatsEntity(
                    recordKey = recordKey,
                    fileName = fileName,
                    viewCount = 1,
                    lastOpenedAtMillis = now,
                    updatedAtMillis = now
                )
            }
            viewStatsDao.upsert(stats)

            viewHistoryDao.upsert(
                ViewHistoryEntity(
                    recordKey = recordKey,
                    fileName = fileName,
                    mediaType = mediaType,
                    openedAtMillis = now
                )
            )
            viewHistoryDao.pruneToLimit(LocalMediaRepository.HISTORY_LIMIT)
        }
    }

    override suspend fun recordHistory(history: ViewHistoryEntity) {
        database.withTransaction {
            viewHistoryDao.upsert(history)
            viewHistoryDao.pruneToLimit(LocalMediaRepository.HISTORY_LIMIT)
        }
    }

    override suspend fun upsertStats(stats: ViewStatsEntity) {
        viewStatsDao.upsert(stats)
    }

    override suspend fun upsertAuthor(author: AuthorEntity) {
        authorDao.upsertAuthor(author)
    }

    override suspend fun upsertAllAuthors(authors: List<AuthorEntity>) {
        authorDao.upsertAllAuthors(authors)
    }

    override suspend fun upsertAuthorMedia(crossRef: AuthorMediaCrossRef) {
        authorDao.upsertAuthorMedia(crossRef)
    }

    override suspend fun upsertAllAuthorMedia(crossRefs: List<AuthorMediaCrossRef>) {
        authorDao.upsertAllAuthorMedia(crossRefs)
    }

    override suspend fun getScanSources(): List<ScanSourceEntity> = scanSourceDao.getAllSources()

    override suspend fun upsertScanSource(source: ScanSourceEntity) {
        scanSourceDao.upsert(source)
    }

    override suspend fun deleteScanSource(uriString: String) {
        scanSourceDao.deleteByUri(uriString)
    }

    override suspend fun deleteMediaAndRefs(recordKeys: List<String>) {
        if (recordKeys.isEmpty()) return
        // 1. 事务中删除数据库关联数据
        database.withTransaction {
            authorDao.deleteCrossRefsByRecordKeys(recordKeys)
            database.tagDao().deleteCrossRefsByRecordKeys(recordKeys)
            viewStatsDao.deleteByRecordKeys(recordKeys)
            viewHistoryDao.deleteByRecordKeys(recordKeys)
            database.timelineTagDao().deleteByRecordKeys(recordKeys)
            mediaFileDao.deleteByRecordKeys(recordKeys)
        }
        // 2. 清理 SharedPreferences（点赞、收藏）
        MediaDetailPrefsCleaner.cleanByRecordKeys(application, recordKeys)
        // 3. 清理 Coil 内存缓存 + 本地缩略图文件缓存
        MediaCacheCleaner.cleanByRecordKeys(application, recordKeys)
    }

    override suspend fun deleteOrphanAuthors() {
        authorDao.deleteOrphanAuthors()
    }

    override suspend fun deleteOrphanCosAuthors() {
        authorDao.deleteOrphanCosAuthors()
    }

    override suspend fun deleteCrossRefsByAuthorIds(authorIds: List<String>) {
        authorDao.deleteCrossRefsByAuthorIds(authorIds)
    }

    override suspend fun upsertSetting(setting: SettingEntity) {
        database.settingDao().upsert(setting)
    }

    override suspend fun deleteSetting(key: String) {
        database.settingDao().deleteByKey(key)
    }

    override suspend fun clearHistory() {
        viewHistoryDao.clearAll()
    }

    override suspend fun clearAllAuthors() {
        database.withTransaction {
            authorDao.clearAuthorMedia()
            authorDao.clearAll()
        }
    }

    override suspend fun deleteAuthor(authorId: String) {
        database.withTransaction {
            authorDao.deleteAuthor(authorId)
        }
    }

    override suspend fun deleteAuthorsByIds(authorIds: List<String>) {
        database.withTransaction {
            authorDao.deleteCrossRefsByAuthorIds(authorIds)
            authorDao.deleteAuthorsByIds(authorIds)
        }
    }

    override suspend fun getAllAuthors(): List<AuthorEntity> = authorDao.getAllAuthors()

    override suspend fun rebuildMediaIndex(mediaFiles: List<MediaFileEntity>) {
        database.withTransaction {
            mediaFileDao.clearNonCosIndex()
            mediaFileDao.upsertAll(mediaFiles)
        }
    }

    override fun observeCosWorks(): Flow<List<CosWorkEntity>> = database.cosWorkDao().observeAll()

    override fun observeCosAuthors(): Flow<List<String>> = database.cosWorkDao().observeAuthors()

    override fun observeCosWorksByAuthor(authorName: String): Flow<List<CosWorkEntity>> =
        database.cosWorkDao().observeByAuthor(authorName)

    override fun observeCosMedia(): Flow<List<MediaFileEntity>> = mediaFileDao.observeCosMedia()

    override fun observeCosScanSources(): Flow<List<ScanSourceEntity>> = scanSourceDao.observeCosSources()

    override suspend fun getCosScanSources(): List<ScanSourceEntity> = scanSourceDao.getCosSources()

    override suspend fun upsertCosWorks(works: List<CosWorkEntity>) {
        database.cosWorkDao().upsertAll(works)
    }

    override suspend fun rebuildCosIndex(mediaFiles: List<MediaFileEntity>, cosWorks: List<CosWorkEntity>) {
        // 轻量查询：只获取 recordKey，避免加载全量 MediaFileEntity
        val existingCosKeys = mediaFileDao.getAllCosRecordKeys()
        database.withTransaction {
            if (existingCosKeys.isNotEmpty()) {
                authorDao.deleteCrossRefsByRecordKeys(existingCosKeys)
                authorDao.deleteOrphanCosAuthors()
            }
            mediaFileDao.deleteCosMedia()
            database.cosWorkDao().clearAll()
            mediaFileDao.upsertAll(mediaFiles)
            database.cosWorkDao().upsertAll(cosWorks)
        }
    }

    override suspend fun deleteCosScanSource(uriString: String) {
        val allOrphanKeys = mutableListOf<String>()
        database.withTransaction {
            scanSourceDao.deleteByUri(uriString)
            val remainingCosSources = scanSourceDao.getCosSources()
            val activeCosUris = remainingCosSources.map { src ->
                if (src.uriString.endsWith("/")) src.uriString else "${src.uriString}/"
            }
            val allCosWorks = database.cosWorkDao().getAll()
            val inactiveWorks = if (activeCosUris.isEmpty()) {
                allCosWorks
            } else {
                allCosWorks.filter { work ->
                    activeCosUris.none { prefix -> work.folderUri.startsWith(prefix) }
                }
            }
            // 轻量查询：只获取 recordKey，避免加载全量 MediaFileEntity
            for (work in inactiveWorks) {
                val prefix = if (work.folderUri.endsWith("/")) work.folderUri else "${work.folderUri}/"
                val orphanKeys = mediaFileDao.getCosRecordKeysByUriPrefixLight(prefix)
                if (orphanKeys.isNotEmpty()) {
                    allOrphanKeys.addAll(orphanKeys)
                    authorDao.deleteCrossRefsByRecordKeys(orphanKeys)
                    database.tagDao().deleteCrossRefsByRecordKeys(orphanKeys)
                    viewStatsDao.deleteByRecordKeys(orphanKeys)
                    viewHistoryDao.deleteByRecordKeys(orphanKeys)
                    database.timelineTagDao().deleteByRecordKeys(orphanKeys)
                    mediaFileDao.deleteByRecordKeys(orphanKeys)
                }
            }
            val activeUris = activeCosUris.map { it.trimEnd('/') }
            database.cosWorkDao().deleteByInactiveUris(activeUris)
            authorDao.deleteOrphanCosAuthors()
        }
        // 事务外清理 SharedPreferences（点赞、收藏）
        if (allOrphanKeys.isNotEmpty()) {
            MediaDetailPrefsCleaner.cleanByRecordKeys(application, allOrphanKeys)
            MediaCacheCleaner.cleanByRecordKeys(application, allOrphanKeys)
        }
    }

    override suspend fun getTagByName(name: String): TagEntity? = database.tagDao().getByName(name)

    override suspend fun insertTag(tag: TagEntity): TagEntity? {
        val id = database.tagDao().insert(tag)
        return if (id >= 0) tag.copy(tagId = id) else null
    }

    override suspend fun upsertTagCrossRef(crossRef: MediaTagCrossRef) {
        database.tagDao().upsertCrossRef(crossRef)
    }

    override suspend fun deleteTagById(tagId: Long) {
        database.tagDao().deleteTag(tagId)
    }

    override suspend fun removeTagFromMedia(recordKey: String, tagId: Long) {
        database.tagDao().removeTagFromMedia(recordKey, tagId)
    }

    override suspend fun updateBrowseSeconds(recordKey: String, seconds: Long) {
        val existing = viewStatsDao.getByRecordKey(recordKey) ?: return
        viewStatsDao.upsert(
            existing.copy(
                totalBrowseSeconds = existing.totalBrowseSeconds + seconds,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    override suspend fun updateMediaMetadata(recordKey: String, width: Int?, height: Int?, durationMillis: Long?) {
        mediaFileDao.updateMetadata(recordKey, width, height, durationMillis)
    }

    override fun observeTagsForMedia(recordKey: String): Flow<List<MediaTagCrossRef>> =
        database.tagDao().observeTagsForMedia(recordKey)

    override fun observeTagEntitiesForMedia(recordKey: String): Flow<List<TagEntity>> =
        database.tagDao().observeTagEntitiesForMedia(recordKey)

    // 时间轴标签
    override fun observeTimelineTags(recordKey: String): Flow<List<TimelineTagEntity>> =
        database.timelineTagDao().observeForVideo(recordKey)

    override suspend fun upsertTimelineTag(tag: TimelineTagEntity) =
        database.timelineTagDao().upsert(tag)

    override suspend fun deleteTimelineTag(timelineTagId: Long) =
        database.timelineTagDao().deleteById(timelineTagId)

    override suspend fun getAllTimelineTags(): List<TimelineTagEntity> =
        database.timelineTagDao().getAll()
}
