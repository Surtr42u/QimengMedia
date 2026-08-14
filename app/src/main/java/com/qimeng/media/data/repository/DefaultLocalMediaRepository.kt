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
import com.qimeng.media.data.db.entity.DailyBrowseEntity
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
import java.util.Calendar

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

    override fun observeDailyBrowse(): Flow<List<DailyBrowseEntity>> =
        database.dailyBrowseDao().observeSince(0L, Int.MAX_VALUE)

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

    override suspend fun getRecordKeysBySourceUri(sourceUri: String): List<String> =
        mediaFileDao.getRecordKeysBySourceUri(sourceUri)

    override suspend fun getCosRecordKeysBySourceUri(sourceUri: String): List<String> =
        mediaFileDao.getCosRecordKeysBySourceUri(sourceUri)

    override suspend fun getNonCosKeysAndUris(): List<MediaFileDao.NonCosKeyUri> =
        mediaFileDao.getNonCosKeysAndUris()

    override suspend fun getCosKeysAndUris(): List<MediaFileDao.NonCosKeyUri> =
        mediaFileDao.getCosKeysAndUris()

    override suspend fun getNonCosKeysAndFileNames(): List<MediaFileDao.NonCosKeyFileName> =
        mediaFileDao.getNonCosKeysAndFileNames()

    override suspend fun getAllRecordKeys(): List<String> =
        mediaFileDao.getAllRecordKeys()

    override suspend fun getViewStatsRecordKeys(): List<String> =
        viewStatsDao.getAllRecordKeys()

    override suspend fun getHistoryRecordKeys(): List<String> =
        viewHistoryDao.getAllRecordKeys()

    override suspend fun getTimelineTagRecordKeys(): List<String> =
        database.timelineTagDao().getAllRecordKeys()

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

            recordDailyBrowse(recordKey, fileName, mediaType, now) { dayKey, dayStart ->
                val updated = database.dailyBrowseDao().incrementViewCount(dayKey, now)
                if (updated == 0) {
                    database.dailyBrowseDao().upsert(
                        DailyBrowseEntity(
                            recordKeyDay = dayKey,
                            recordKey = recordKey,
                            fileName = fileName,
                            mediaType = mediaType,
                            dayStartMillis = dayStart,
                            viewCount = 1,
                            updatedAtMillis = now
                        )
                    )
                }
            }
        }
    }

    override suspend fun recordPlay(recordKey: String, fileName: String) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            val mediaFile = mediaFileDao.getByRecordKey(recordKey)
            val mediaType = mediaFile?.mediaType ?: "video"

            val existing = viewStatsDao.getByRecordKey(recordKey)
            val stats = if (existing != null) {
                existing.copy(
                    playCount = existing.playCount + 1,
                    updatedAtMillis = now
                )
            } else {
                ViewStatsEntity(
                    recordKey = recordKey,
                    fileName = fileName,
                    viewCount = 0,
                    playCount = 1,
                    updatedAtMillis = now
                )
            }
            viewStatsDao.upsert(stats)

            recordDailyBrowse(recordKey, fileName, mediaType, now) { dayKey, dayStart ->
                val updated = database.dailyBrowseDao().incrementPlayCount(dayKey, now)
                if (updated == 0) {
                    database.dailyBrowseDao().upsert(
                        DailyBrowseEntity(
                            recordKeyDay = dayKey,
                            recordKey = recordKey,
                            fileName = fileName,
                            mediaType = mediaType,
                            dayStartMillis = dayStart,
                            viewCount = 0,
                            playCount = 1,
                            updatedAtMillis = now
                        )
                    )
                }
            }
        }
    }

    /**
     * 按天浏览聚合的通用写入：优先原子 +1/追加，命中 0 行时（当天首条）upsert 新行。
     * 事务内调用；[block] 接收 (recordKeyDay, dayStartMillis) 并执行实际的增量/建行逻辑。
     */
    private suspend fun recordDailyBrowse(
        recordKey: String,
        fileName: String,
        mediaType: String,
        now: Long,
        block: suspend (dayKey: String, dayStart: Long) -> Unit
    ) {
        val dayStart = dayStartOf(now)
        val dayKey = "$recordKey@$dayStart"
        block(dayKey, dayStart)
    }

    /** 将时间戳对齐到当天零点（聚合表天粒度分桶键） */
    private fun dayStartOf(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
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

    override suspend fun rebuildAuthorAssociations(
        authorEntities: List<AuthorEntity>,
        crossRefs: List<AuthorMediaCrossRef>
    ) {
        database.withTransaction {
            if (authorEntities.isNotEmpty()) {
                // SQLite 绑定参数上限旧设备为 999：authors 表 3 列 × 300 = 900 < 999，分块写入
                authorEntities.chunked(300).forEach { authorDao.upsertAllAuthors(it) }
                // 先删除这些作者的旧 crossRef，再插入新的（避免匹配规则变更后旧数据残留）
                // 单列 IN 列表同样受 999 上限约束，按 deleteAllRefsChunked 的 500 分块模式对齐
                authorEntities.map { it.authorId }.chunked(500).forEach { authorDao.deleteCrossRefsByAuthorIds(it) }
            }
            if (crossRefs.isNotEmpty()) {
                // author_media_cross_ref 表 4 列 × 200 = 800 < 999，分块写入
                crossRefs.chunked(200).forEach { authorDao.upsertAllAuthorMedia(it) }
            }
        }
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
        // v1.12：SQLite 绑定参数上限旧设备为 999，删除数千文件的扫描源时单条
        // DELETE IN(:list) 会抛 SQLiteException 导致整体回滚；按 500 分块（对齐
        // upsert 的 chunked 模式）避免变量数超限，且保持在单个事务内保证原子性。
        database.withTransaction {
            deleteAllRefsChunked(recordKeys)
        }
        // 2. 清理 SharedPreferences（点赞、收藏）
        MediaDetailPrefsCleaner.cleanByRecordKeys(application, recordKeys)
        // 3. 清理 Coil 内存缓存 + 本地缩略图文件缓存
        MediaCacheCleaner.cleanByRecordKeys(application, recordKeys)
    }

    /**
     * v1.12：分块级联删除（须在数据库事务内调用）。
     * 规避旧设备 SQLite 绑定参数上限（999），DELETE IN(:大列表) 分块执行。
     */
    private suspend fun deleteAllRefsChunked(recordKeys: List<String>) {
        recordKeys.chunked(500).forEach { chunk ->
            authorDao.deleteCrossRefsByRecordKeys(chunk)
            database.tagDao().deleteCrossRefsByRecordKeys(chunk)
            viewStatsDao.deleteByRecordKeys(chunk)
            viewHistoryDao.deleteByRecordKeys(chunk)
            database.dailyBrowseDao().deleteByRecordKeys(chunk)
            database.timelineTagDao().deleteByRecordKeys(chunk)
            mediaFileDao.deleteByRecordKeys(chunk)
        }
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
        if (authorIds.isEmpty()) return
        database.withTransaction {
            // v1.12：分块规避旧设备 SQLite 绑定参数上限（999），大批量删除作者时单条
            // DELETE IN(:大列表) 会抛 SQLiteException 导致整体回滚
            authorIds.chunked(500).forEach { chunk ->
                authorDao.deleteCrossRefsByAuthorIds(chunk)
                authorDao.deleteAuthorsByIds(chunk)
            }
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

    override suspend fun getCosWorksBySourceUri(sourceUri: String): List<CosWorkEntity> =
        database.cosWorkDao().getBySourceUri(sourceUri)

    override suspend fun deleteCosWorksByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { chunk -> database.cosWorkDao().deleteByIds(chunk) }
    }

    override suspend fun rebuildCosIndex(mediaFiles: List<MediaFileEntity>, cosWorks: List<CosWorkEntity>) {
        // 轻量查询：只获取 recordKey，避免加载全量 MediaFileEntity
        val existingCosKeys = mediaFileDao.getAllCosRecordKeys()
        database.withTransaction {
            if (existingCosKeys.isNotEmpty()) {
                // v1.12：分块删除避免 SQLite 绑定参数超限
                existingCosKeys.chunked(500).forEach { chunk ->
                    authorDao.deleteCrossRefsByRecordKeys(chunk)
                }
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
            // v1.12：按 sourceUri 精确匹配归属作品与文件。
            // 旧版用 folderUri（文件系统路径）与扫描源 content URI 前缀匹配恒失败，
            // 删除任一 COS 源会清空所有源作品；这里只删除该源自己的作品。
            database.cosWorkDao().deleteBySourceUri(uriString)

            // 删除该源归属的媒体文件（含级联统计/历史/标签/关联；v1.12 按 500 分块避免
            // SQLite 绑定参数超限，旧设备 DELETE IN(:大列表) 会抛 SQLiteException）
            val orphanKeys = mediaFileDao.getCosRecordKeysBySourceUri(uriString)
            if (orphanKeys.isNotEmpty()) {
                allOrphanKeys.addAll(orphanKeys)
                deleteAllRefsChunked(orphanKeys)
            }

            // v1.x 修复：移除旧版数据（sourceUri 为 null/空）的 folderUri 前缀兜底清理。
            // 旧实现中 folderUri 是文件系统路径（/storage/emulated/0/...），剩余源的 uriString
            // 是 tree URI（content://.../tree/...），两者命名空间不同 startsWith 恒不命中，
            // 导致 activeCosUris.none{...} 恒为 true，取到全部旧版作品行并整体删除
            // （清空所有源 cos_works）。旧版数据无法可靠反推归属，按「宁可残留不可误删」
            // 原则保守保留、跳过清理（与 GUIDE_SCAN.md「删除扫描源」第 3 条一致）。
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
        // 读-改-写包事务，避免快速连续退出详情页时并发累计丢失更新
        database.withTransaction {
            val now = System.currentTimeMillis()
            val existing = viewStatsDao.getByRecordKey(recordKey) ?: return@withTransaction
            viewStatsDao.upsert(
                existing.copy(
                    totalBrowseSeconds = existing.totalBrowseSeconds + seconds,
                    updatedAtMillis = now
                )
            )

            // 按天聚合同步累计（mediaType 从 media_files 查，聚合行缺失时补建）
            val mediaFile = mediaFileDao.getByRecordKey(recordKey)
            val mediaType = mediaFile?.mediaType ?: existing.fileName.substringAfterLast('.', "").let { ext ->
                if (ext.equals("gif", ignoreCase = true)) "animated_image" else if (ext.equals("mp4", ignoreCase = true) || ext.equals("mov", ignoreCase = true)) "video" else "image"
            }
            recordDailyBrowse(recordKey, existing.fileName, mediaType, now) { dayKey, dayStart ->
                val updated = database.dailyBrowseDao().addBrowseSeconds(dayKey, seconds, now)
                if (updated == 0) {
                    database.dailyBrowseDao().upsert(
                        DailyBrowseEntity(
                            recordKeyDay = dayKey,
                            recordKey = recordKey,
                            fileName = existing.fileName,
                            mediaType = mediaType,
                            dayStartMillis = dayStart,
                            viewCount = 0,
                            updatedAtMillis = now
                        )
                    )
                }
            }
        }
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
