package com.qimeng.media.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.AppDatabase
import com.qimeng.media.data.db.entity.AlbumRuleEntity
import com.qimeng.media.data.db.entity.AuthorEntity
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
import com.qimeng.media.data.prefs.AppPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份目录数据量检测结果（只读，不写数据库）。
 *
 * 由 [BackupManager.peekBackupSummary] 在用户选择备份目录后调用，
 * 用于"检测到备份数据，是否导入恢复"的提示文案。
 * - [existingFileCount]：是否存在备份主文件（0 或 1）
 * - [mediaFileCount]/[authorCount]/[tagCount]/[statsCount]：主文件中对应段落的条目数
 * - [exportedAtMillis]：备份文件的导出时间
 */
data class BackupSummary(
    val existingFileCount: Int,
    val mediaFileCount: Int,
    val authorCount: Int,
    val tagCount: Int,
    val statsCount: Int,
    val exportedAtMillis: Long?
)

/**
 * 备份 v2.0 核心类：单文件全量 JSON 备份。
 *
 * 所有 App 数据（含 media_files 元数据、引用表、SharedPreferences、AppPrefs）序列化到
 * 单个 [BackupFileNames.QIMENG_BACKUP]（qimeng_backup.json），写入用户 SAF 授权的备份目录根目录。
 *
 * 设计要点：
 * - **media_files 全量进备份**：恢复后浏览历史/统计/标签立即可用（不再依赖重新扫描重建
 *   recordKey），根治旧版重名文件 pathHash key 漂移被 cleanupStaleRefs 误删的问题。
 * - **导入合并策略**：本地 media_files 为空（全新重装未扫描）→ 导入备份 media_files 全量；
 *   本地非空（已扫描）→ 跳过 media_files（保留新扫描的有效 URI），引用数据按稳定 key 合并。
 * - 字段名自解释（fileName/viewCount/openedAtMillis），AI 可直接读取 JSON 生成报告，无需 App 内置报告器。
 * - 原子写入（tmp → round-trip 校验 → 删旧 → rename）与 64MB 单文件读取上限沿用旧版安全设计。
 */
class BackupManager(private val context: Context) {

    /** 写入备份目录根目录下的单文件备份 JSON，返回 1（成功）或 0（失败）。 */
    suspend fun exportToDirectory(dirUri: Uri, database: AppDatabase, appPrefsManager: AppPrefsManager): Int = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return@withContext 0
        try {
            val json = buildBackupJson(database, appPrefsManager)
            if (writeJsonAtomic(dir, json)) 1 else 0
        } catch (e: Exception) {
            AppLog.e("BackupManager", "exportToDirectory failed: ${e.message}", e)
            0
        }
    }

    /** 从备份目录读取单文件备份 JSON 并合并恢复，返回成功导入的数据段数（0 表示失败或无数据）。 */
    suspend fun importFromDirectory(dirUri: Uri, database: AppDatabase, appPrefsManager: AppPrefsManager): Int = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return@withContext 0
        val json = readJson(dir) ?: return@withContext 0
        val data = json.optJSONObject("data") ?: return@withContext 0
        val hadLocalMedia = database.mediaFileDao().hasAny()
        var count = 0

        // 数据库部分在单一事务内导入，避免中途异常留下半恢复状态
        database.withTransaction {
            // 1. mediaFiles：仅本地空库时导入（避免覆盖已扫描的有效 URI）
            val backupMedia = importArray(data.optJSONArray("mediaFiles")) { it.toMediaFileEntity() }
            if (!hadLocalMedia && backupMedia.isNotEmpty()) {
                backupMedia.chunked(500).forEach { database.mediaFileDao().upsertAll(it) }
                count++
            }

            // 2. settings：跳过设备本地键（备份目录 URI/名称在恢复后无意义）
            val settings = importArray(data.optJSONArray("settings")) { it.toSettingEntity() }
                .filter { it.key != KEY_BACKUP_DIRECTORY_URI && it.key != KEY_BACKUP_DIRECTORY_NAME }
            settings.forEach { database.settingDao().upsert(it) }
            if (settings.isNotEmpty()) count++

            // 3. tags：按 name INSERT OR IGNORE 去重（忽略备份 tagId，避免向已有库合并时 id 撞号）
            val tagNames = mutableListOf<String>()
            importArray(data.optJSONArray("tags")) { it.toTagEntity() }.forEach { tag ->
                if (tag.name.isBlank()) return@forEach
                database.tagDao().insert(
                    TagEntity(tagId = 0, name = tag.name, createdAtMillis = tag.createdAtMillis)
                )
                tagNames.add(tag.name)
            }
            if (tagNames.isNotEmpty()) count++

            // 4. mediaTagRefs：按 tagName 查回本地真实 tagId 建关联（跳过本地不存在的媒体）
            val mediaMap = database.mediaFileDao().getAll().associateBy { it.recordKey }
            val mediaTagArr = data.optJSONArray("mediaTagRefs")
            val tagRefs = mutableListOf<MediaTagCrossRef>()
            if (mediaTagArr != null) {
                for (i in 0 until mediaTagArr.length()) {
                    val obj = mediaTagArr.optJSONObject(i) ?: continue
                    val recordKey = obj.optString("recordKey", "")
                    val tagName = obj.optString("tagName", "")
                    if (recordKey.isBlank() || tagName.isBlank() || recordKey !in mediaMap) continue
                    val localTag = database.tagDao().getByName(tagName) ?: continue
                    tagRefs.add(
                        MediaTagCrossRef(
                            recordKey = recordKey,
                            tagId = localTag.tagId,
                            fileName = mediaMap[recordKey]?.fileName ?: recordKey
                        )
                    )
                }
            }
            if (tagRefs.isNotEmpty()) {
                tagRefs.forEach { database.tagDao().upsertCrossRef(it) }
                count++
            }

            // 5. authors + authorMediaRefs
            val authors = importArray(data.optJSONArray("authors")) { it.toAuthorEntity() }
            if (authors.isNotEmpty()) {
                database.authorDao().upsertAllAuthors(authors)
                count++
            }
            val authorRefs = importArray(data.optJSONArray("authorMediaRefs")) { it.toAuthorMediaCrossRef() }
                .filter { it.recordKey in mediaMap }
            if (authorRefs.isNotEmpty()) {
                database.authorDao().upsertAllAuthorMedia(authorRefs)
                count++
            }

            // 6. albumRules
            val rules = importArray(data.optJSONArray("albumRules")) { it.toAlbumRuleEntity() }
            rules.forEach { database.albumRuleDao().upsert(it) }
            if (rules.isNotEmpty()) count++

            // 7. mediaStats：负数钳制
            val stats = importArray(data.optJSONArray("mediaStats")) { it.toViewStatsEntity() }
                .filter { it.recordKey in mediaMap }
            stats.forEach { database.viewStatsDao().upsert(it) }
            if (stats.isNotEmpty()) count++

            // 7.1 按天浏览聚合（v1.14）：无限历史统计的真相来源，恢复后趋势/窗口排行/沉浸时长全回来
            val dailyBrowse = importArray(data.optJSONArray("dailyBrowse")) { it.toDailyBrowseEntity() }
                .filter { it.recordKey in mediaMap }
            if (dailyBrowse.isNotEmpty()) {
                dailyBrowse.forEach { database.dailyBrowseDao().upsert(it) }
                count++
            }

            // 8. history
            val history = importArray(data.optJSONArray("history")) { it.toViewHistoryEntity() }
                .filter { it.recordKey in mediaMap }
            history.forEach { database.viewHistoryDao().upsert(it) }
            if (history.isNotEmpty()) count++

            // 9. scanSources
            val sources = importArray(data.optJSONArray("scanSources")) { it.toScanSourceEntity() }
            sources.forEach { database.scanSourceDao().upsert(it) }
            if (sources.isNotEmpty()) count++

            // 10. timelineTags：按 (recordKey, timeMillis, name) 查重防重复导入
            val timelineTags = importArray(data.optJSONArray("timelineTags")) { it.toTimelineTagEntity() }
                .filter { it.recordKey in mediaMap }
            var timelineCount = 0
            timelineTags.forEach { tag ->
                if (database.timelineTagDao().findExisting(tag.recordKey, tag.timeMillis, tag.name) == null) {
                    database.timelineTagDao().upsert(tag)
                    timelineCount++
                }
            }
            if (timelineCount > 0) count++

            // 11. cosWorks
            val cosWorks = importArray(data.optJSONArray("cosWorks")) { it.toCosWorkEntity() }
            if (cosWorks.isNotEmpty()) {
                cosWorks.chunked(500).forEach { database.cosWorkDao().upsertAll(it) }
                count++
            }
        }

        // SharedPreferences 与 AppPrefs 写入不在数据库事务内
        if (importLikes(data)) count++
        val followedIds = data.optJSONArray("followedAuthorIds")
        if (followedIds != null && followedIds.length() > 0) {
            importFollowedAuthors(followedIds)
            count++
        }
        val appPrefsJson = data.optJSONObject("appPrefs")
        if (appPrefsJson != null) {
            appPrefsManager.importFromJson(appPrefsJson.toString())
            count++
        }
        count
    }

    /**
     * 轻量检测备份目录是否已有可恢复数据（只读，不写数据库）。
     * 检测主文件 [BackupFileNames.QIMENG_BACKUP] 是否存在，存在则 parse 出关键段落条目数。
     */
    suspend fun peekBackupSummary(dirUri: Uri): BackupSummary? = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return@withContext null
        val file = dir.findFile(BackupFileNames.QIMENG_BACKUP)?.takeIf { it.isFile } ?: return@withContext null
        val json = readJson(dir) ?: return@withContext null
        val data = json.optJSONObject("data")
        BackupSummary(
            existingFileCount = 1,
            mediaFileCount = data?.optJSONArray("mediaFiles")?.length() ?: 0,
            authorCount = data?.optJSONArray("authors")?.length() ?: 0,
            tagCount = data?.optJSONArray("tags")?.length() ?: 0,
            statsCount = data?.optJSONArray("mediaStats")?.length() ?: 0,
            exportedAtMillis = json.optLong("exportedAtMillis", 0L).takeIf { it > 0 }
        )
    }

    // ===== 导出 =====

    private suspend fun buildBackupJson(database: AppDatabase, appPrefsManager: AppPrefsManager): JSONObject {
        val db = database
        val now = System.currentTimeMillis()
        val detailPrefs = context.getSharedPreferences(PREFS_MEDIA_DETAIL, Context.MODE_PRIVATE)
        val favSet = detailPrefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
        val followPrefs = context.getSharedPreferences(PREFS_AUTHOR_FOLLOW, Context.MODE_PRIVATE)
        val followedIds = followPrefs.getStringSet("followed_authors", emptySet()).orEmpty()

        return JSONObject().apply {
            put("format", "qimeng_backup")
            put("schemaVersion", 1)
            put("appIdentifier", "com.qimeng.media")
            put("exportedAtMillis", now)
            put("data", JSONObject().apply {
                put("mediaFiles", exportArray(db.mediaFileDao().getAll()) { it.toBackupJson() })
                put("settings", exportArray(db.settingDao().getAll()) { it.toBackupJson() })
                put("authors", exportArray(db.authorDao().getAllAuthors()) { it.toBackupJson() })
                put("authorMediaRefs", exportArray(db.authorDao().observeAllAuthorMedia().firstOrNull().orEmpty()) { it.toBackupJson() })
                put("tags", exportArray(db.tagDao().getAllTags()) { it.toBackupJson() })
                // mediaTagRefs 带 tagName 冗余：AI 读备份 JSON 无需再查 tags 表即可理解文件-标签关联
                val tagIdToName = db.tagDao().getAllTags().associateBy { it.tagId }
                put("mediaTagRefs", exportArray(db.tagDao().getAllCrossRefs()) { xref ->
                    JSONObject().apply {
                        put("recordKey", xref.recordKey)
                        put("tagName", tagIdToName[xref.tagId]?.name ?: "")
                    }
                })
                put("albumRules", exportArray(db.albumRuleDao().getEnabledRulesByPriority()) { it.toBackupJson() })
                put("mediaStats", exportArray(db.viewStatsDao().getAllByFileName()) { it.toBackupJson() })
                put("dailyBrowse", exportArray(db.dailyBrowseDao().getAll()) { it.toBackupJson() })
                put("history", exportArray(db.viewHistoryDao().getLatest(HISTORY_EXPORT_LIMIT)) { it.toBackupJson() })
                put("scanSources", exportArray(db.scanSourceDao().getAllSources()) { it.toBackupJson() })
                put("timelineTags", exportArray(db.timelineTagDao().getAll()) { it.toBackupJson() })
                put("cosWorks", exportArray(db.cosWorkDao().getAll()) { it.toBackupJson() })
                put("likes", exportLikesJson(detailPrefs))
                put("favorites", JSONArray().apply { favSet.sorted().forEach { put(it) } })
                put("followedAuthorIds", JSONArray().apply { followedIds.sorted().forEach { put(it) } })
                put("appPrefs", JSONObject(appPrefsManager.exportToJson()))
            })
        }
    }

    /** 从 media_detail_prefs 读取点赞数据（like_count_ 前缀 / like_date_ 前缀），返回 likes 数组。 */
    private fun exportLikesJson(prefs: android.content.SharedPreferences): JSONArray {
        val items = JSONArray()
        val likeCountPrefix = "like_count_"
        val likeDatePrefix = "like_date_"
        for (entry in prefs.all.entries) {
            val key = entry.key as String
            if (key.startsWith(likeCountPrefix) && entry.value is Int) {
                val recordKey = key.removePrefix(likeCountPrefix)
                val likeCount = entry.value as Int
                val lastLikeDate = prefs.getString(likeDatePrefix + recordKey, null)
                items.put(JSONObject().apply {
                    put("recordKey", recordKey)
                    put("likeCount", likeCount)
                    put("lastLikeDate", lastLikeDate ?: JSONObject.NULL)
                })
            }
        }
        return items
    }

    // ===== 实体 ↔ JSON 映射（自解释字段名，AI 可直接读取） =====

    private fun MediaFileEntity.toBackupJson() = JSONObject().apply {
        put("recordKey", recordKey); put("fileName", fileName); put("displayName", displayName)
        put("extension", extension); put("mediaType", mediaType); put("uriString", uriString)
        put("folderName", folderName); put("pathHash", pathHash); put("sizeBytes", sizeBytes)
        put("modifiedAtMillis", modifiedAtMillis)
        put("width", width ?: JSONObject.NULL); put("height", height ?: JSONObject.NULL)
        put("durationMillis", durationMillis ?: JSONObject.NULL)
        put("isDuplicateName", isDuplicateName); put("isCosFile", isCosFile)
        put("indexedAtMillis", indexedAtMillis); put("sourceUri", sourceUri ?: JSONObject.NULL)
    }

    private fun SettingEntity.toBackupJson() = JSONObject().apply {
        put("key", key); put("value", value); put("updatedAtMillis", updatedAtMillis)
    }

    private fun AuthorEntity.toBackupJson() = JSONObject().apply {
        put("authorId", authorId); put("displayName", displayName)
        put("createdAtMillis", createdAtMillis); put("updatedAtMillis", updatedAtMillis)
    }

    private fun AuthorMediaCrossRef.toBackupJson() = JSONObject().apply {
        put("authorId", authorId); put("recordKey", recordKey)
        put("fileName", fileName); put("isMatched", isMatched)
    }

    private fun TagEntity.toBackupJson() = JSONObject().apply {
        put("tagId", tagId); put("name", name); put("createdAtMillis", createdAtMillis)
    }

    private fun AlbumRuleEntity.toBackupJson() = JSONObject().apply {
        put("sourceName", sourceName); put("enabled", enabled)
        put("createdAtMillis", createdAtMillis); put("updatedAtMillis", updatedAtMillis)
    }

    private fun ViewStatsEntity.toBackupJson() = JSONObject().apply {
        put("recordKey", recordKey); put("fileName", fileName)
        put("viewCount", viewCount); put("playCount", playCount)
        put("totalBrowseSeconds", totalBrowseSeconds)
        put("lastOpenedAtMillis", lastOpenedAtMillis ?: JSONObject.NULL)
        put("updatedAtMillis", updatedAtMillis)
    }

    private fun DailyBrowseEntity.toBackupJson() = JSONObject().apply {
        put("recordKey", recordKey); put("fileName", fileName); put("mediaType", mediaType)
        put("dayStartMillis", dayStartMillis)
        put("viewCount", viewCount); put("playCount", playCount)
        put("totalBrowseSeconds", totalBrowseSeconds)
        put("updatedAtMillis", updatedAtMillis)
    }

    private fun ViewHistoryEntity.toBackupJson() = JSONObject().apply {
        put("recordKey", recordKey); put("fileName", fileName)
        put("mediaType", mediaType); put("openedAtMillis", openedAtMillis)
    }

    private fun ScanSourceEntity.toBackupJson() = JSONObject().apply {
        put("uriString", uriString); put("displayName", displayName)
        put("isBackupDirectory", isBackupDirectory); put("isCosDirectory", isCosDirectory)
        put("addedAtMillis", addedAtMillis); put("lastScannedAtMillis", lastScannedAtMillis ?: JSONObject.NULL)
    }

    private fun TimelineTagEntity.toBackupJson() = JSONObject().apply {
        put("recordKey", recordKey); put("fileName", fileName)
        put("timeMillis", timeMillis); put("name", name); put("createdAtMillis", createdAtMillis)
    }

    private fun CosWorkEntity.toBackupJson() = JSONObject().apply {
        put("authorName", authorName); put("workName", workName); put("folderUri", folderUri)
        put("fileCount", fileCount); put("indexedAtMillis", indexedAtMillis); put("sourceUri", sourceUri)
    }

    private fun JSONObject.toMediaFileEntity() = MediaFileEntity(
        recordKey = optString("recordKey", ""),
        fileName = optString("fileName", ""),
        displayName = optString("displayName", ""),
        extension = optString("extension", ""),
        mediaType = optString("mediaType", "image"),
        uriString = optString("uriString", ""),
        folderName = optString("folderName", ""),
        pathHash = optString("pathHash", ""),
        sizeBytes = optLong("sizeBytes", 0),
        modifiedAtMillis = optLong("modifiedAtMillis", 0),
        width = optNullableLong("width")?.toInt(),
        height = optNullableLong("height")?.toInt(),
        durationMillis = optNullableLong("durationMillis"),
        isDuplicateName = optBoolean("isDuplicateName", false),
        isCosFile = optBoolean("isCosFile", false),
        indexedAtMillis = optLong("indexedAtMillis", 0),
        sourceUri = optNullableString("sourceUri")
    )

    private fun JSONObject.toSettingEntity() = SettingEntity(
        key = optString("key", ""),
        value = optString("value", ""),
        updatedAtMillis = optLong("updatedAtMillis", System.currentTimeMillis())
    )

    private fun JSONObject.toAuthorEntity() = AuthorEntity(
        authorId = optString("authorId", "").take(64),
        displayName = optString("displayName", ""),
        createdAtMillis = optLong("createdAtMillis", System.currentTimeMillis()),
        updatedAtMillis = optLong("updatedAtMillis", System.currentTimeMillis())
    )

    private fun JSONObject.toAuthorMediaCrossRef() = AuthorMediaCrossRef(
        authorId = optString("authorId", ""),
        recordKey = optString("recordKey", ""),
        fileName = optString("fileName", ""),
        isMatched = optBoolean("isMatched", true)
    )

    private fun JSONObject.toTagEntity() = TagEntity(
        tagId = 0,
        name = optString("name", ""),
        createdAtMillis = optLong("createdAtMillis", System.currentTimeMillis())
    )

    private fun JSONObject.toAlbumRuleEntity() = AlbumRuleEntity(
        albumRuleId = 0,
        sourceName = optString("sourceName", ""),
        enabled = optBoolean("enabled", true),
        createdAtMillis = optLong("createdAtMillis", System.currentTimeMillis()),
        updatedAtMillis = optLong("updatedAtMillis", System.currentTimeMillis())
    )

    private fun JSONObject.toViewStatsEntity() = ViewStatsEntity(
        recordKey = optString("recordKey", ""),
        fileName = optString("fileName", ""),
        viewCount = optInt("viewCount", 0).coerceAtLeast(0),
        playCount = optInt("playCount", 0).coerceAtLeast(0),
        totalBrowseSeconds = optLong("totalBrowseSeconds", 0).coerceAtLeast(0L),
        lastOpenedAtMillis = optNullableLong("lastOpenedAtMillis"),
        updatedAtMillis = optLong("updatedAtMillis", System.currentTimeMillis()).coerceAtLeast(0L)
    )

    private fun JSONObject.toViewHistoryEntity() = ViewHistoryEntity(
        recordKey = optString("recordKey", ""),
        fileName = optString("fileName", ""),
        mediaType = optString("mediaType", "image"),
        openedAtMillis = optLong("openedAtMillis", System.currentTimeMillis()).coerceAtLeast(0L)
    )

    private fun JSONObject.toDailyBrowseEntity() = DailyBrowseEntity(
        recordKeyDay = optString("recordKey", "") + "@" + optLong("dayStartMillis", 0L),
        recordKey = optString("recordKey", ""),
        fileName = optString("fileName", ""),
        mediaType = optString("mediaType", "image"),
        dayStartMillis = optLong("dayStartMillis", 0L).coerceAtLeast(0L),
        viewCount = optInt("viewCount", 0).coerceAtLeast(0),
        playCount = optInt("playCount", 0).coerceAtLeast(0),
        totalBrowseSeconds = optLong("totalBrowseSeconds", 0).coerceAtLeast(0L),
        updatedAtMillis = optLong("updatedAtMillis", System.currentTimeMillis()).coerceAtLeast(0L)
    )

    private fun JSONObject.toScanSourceEntity() = ScanSourceEntity(
        uriString = optString("uriString", ""),
        displayName = optString("displayName", ""),
        isBackupDirectory = optBoolean("isBackupDirectory", false),
        isCosDirectory = optBoolean("isCosDirectory", false),
        addedAtMillis = optLong("addedAtMillis", System.currentTimeMillis()),
        lastScannedAtMillis = optNullableLong("lastScannedAtMillis")
    )

    private fun JSONObject.toTimelineTagEntity() = TimelineTagEntity(
        timelineTagId = 0,
        recordKey = optString("recordKey", ""),
        fileName = optString("fileName", ""),
        timeMillis = optLong("timeMillis", 0),
        name = optString("name", ""),
        createdAtMillis = optLong("createdAtMillis", System.currentTimeMillis())
    )

    private fun JSONObject.toCosWorkEntity() = CosWorkEntity(
        id = 0,
        authorName = optString("authorName", ""),
        workName = optString("workName", ""),
        folderUri = optString("folderUri", ""),
        fileCount = optInt("fileCount", 0).coerceAtLeast(0),
        indexedAtMillis = optLong("indexedAtMillis", System.currentTimeMillis()),
        sourceUri = optString("sourceUri", "")
    )

    // ===== SharedPreferences 导入 =====

    /** 导入 likes 数组并清理陈旧点赞键、合并收藏。返回是否有数据被写入。 */
    private fun importLikes(data: JSONObject): Boolean {
        val prefs = context.getSharedPreferences(PREFS_MEDIA_DETAIL, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val likes = data.optJSONArray("likes")
        var importedAny = false
        val backupLikeKeys = mutableSetOf<String>()
        val likeCountPrefix = "like_count_"
        val likeDatePrefix = "like_date_"

        if (likes != null) {
            var imported = 0
            for (i in 0 until likes.length()) {
                if (imported >= MAX_LIKE_ENTRIES) {
                    AppLog.w("Backup", "importLikes 达到上限 $MAX_LIKE_ENTRIES，跳过剩余条目")
                    break
                }
                val obj = likes.optJSONObject(i) ?: continue
                val recordKey = obj.optString("recordKey", "")
                if (recordKey.isBlank()) continue
                val likeCount = obj.optInt("likeCount", 0).coerceAtLeast(0)
                val lastLikeDate = if (obj.has("lastLikeDate") && !obj.isNull("lastLikeDate")) obj.optString("lastLikeDate", "") else null
                editor.putInt("$likeCountPrefix$recordKey", likeCount)
                backupLikeKeys.add(recordKey)
                if (lastLikeDate != null) {
                    editor.putString("$likeDatePrefix$recordKey", lastLikeDate)
                } else {
                    editor.remove("$likeDatePrefix$recordKey")
                }
                imported++
            }
            importedAny = imported > 0
        }

        // 清理本地残留的点赞键（源端已取消点赞的陈旧 like_count_*/like_date_*）
        for (entry in prefs.all.entries) {
            val key = entry.key as String
            val recordKey = when {
                key.startsWith(likeCountPrefix) -> key.removePrefix(likeCountPrefix)
                key.startsWith(likeDatePrefix) -> key.removePrefix(likeDatePrefix)
                else -> continue
            }
            if (recordKey !in backupLikeKeys) {
                editor.remove(likeCountPrefix + recordKey).remove(likeDatePrefix + recordKey)
            }
        }

        // 收藏合并（本地 ∪ 备份），避免空 favorites 备份清空本地已有收藏
        val favs = data.optJSONArray("favorites")
        if (favs != null) {
            val existing = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
            for (i in 0 until favs.length()) {
                val key = favs.optString(i, "")
                if (key.isNotBlank()) existing.add(key)
            }
            editor.putStringSet(KEY_FAVORITES, existing)
            importedAny = true
        }
        editor.apply()
        return importedAny
    }

    /** 导入关注作者 ID 集合（整体覆盖为备份值）。 */
    private fun importFollowedAuthors(followedArray: JSONArray) {
        val prefs = context.getSharedPreferences(PREFS_AUTHOR_FOLLOW, Context.MODE_PRIVATE)
        val ids = mutableSetOf<String>()
        for (i in 0 until followedArray.length()) {
            val id = followedArray.optString(i, "")
            if (id.isNotBlank()) ids.add(id)
        }
        prefs.edit().putStringSet("followed_authors", ids).apply()
    }

    // ===== 文件读写（原子写 + 64MB 上限） =====

    /**
     * 原子写入 JSON 到备份目录：tmp 写入 → round-trip 校验 .tmp → 删旧 → rename。
     * 校验失败只删 .tmp，旧备份保留，避免唯一备份被损坏的写入覆盖。
     */
    private fun writeJsonAtomic(dir: DocumentFile, json: JSONObject): Boolean {
        val name = BackupFileNames.QIMENG_BACKUP
        return try {
            val tempName = "${name}.tmp"
            dir.findFile(tempName)?.takeIf { it.isFile }?.delete()
            val tempFile = dir.createFile("application/json", tempName) ?: return false
            val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
            context.contentResolver.openOutputStream(tempFile.uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return false

            // round-trip 校验：JSON 可解析且内容一致才继续
            val verified = context.contentResolver.openInputStream(tempFile.uri)?.use { stream ->
                val readBack = String(stream.readBytes(), Charsets.UTF_8)
                JSONObject(readBack).toString(2) == json.toString(2)
            } ?: false
            if (!verified) {
                AppLog.e("BackupManager", "writeJsonAtomic verify failed (tmp) for $name")
                dir.findFile(tempName)?.delete()
                return false
            }

            dir.findFile(name)?.takeIf { it.isFile }?.delete()
            if (!tempFile.renameTo(name)) {
                AppLog.e("BackupManager", "writeJsonAtomic rename failed for $name")
                return false
            }
            true
        } catch (e: Exception) {
            AppLog.e("BackupManager", "writeJsonAtomic failed for $name: ${e.message}", e)
            dir.findFile("${name}.tmp")?.delete()
            false
        }
    }

    /** 读取备份主文件，带 64MB 上限校验（防恶意/损坏 JSON 导致 OOM），异常或超限返回 null。 */
    private fun readJson(dir: DocumentFile): JSONObject? {
        val name = BackupFileNames.QIMENG_BACKUP
        val file = dir.findFile(name)?.takeIf { it.isFile } ?: return null
        val maxBytes = 64L * 1024 * 1024
        val declaredLen = file.length()
        if (declaredLen in 1..Long.MAX_VALUE && declaredLen > maxBytes) {
            AppLog.w("Backup", "readJson 跳过 $name：文件 ${declaredLen / 1024}KB 超过上限 ${maxBytes / 1024}KB")
            return null
        }
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > maxBytes) {
                    AppLog.w("Backup", "readJson 跳过 $name：实际读取 ${bytes.size / 1024}KB 超过上限")
                    return@use null
                }
                JSONObject(String(bytes))
            }
        } catch (e: Exception) {
            AppLog.w("Backup", "readJson failed for $name: ${e.message}")
            null
        }
    }

    // ===== 泛型辅助 =====

    private fun <T> exportArray(items: List<T>, transform: (T) -> JSONObject): JSONArray =
        JSONArray().apply { items.forEach { put(transform(it)) } }

    private fun <T> importArray(arr: JSONArray?, parse: (JSONObject) -> T?): List<T> {
        if (arr == null) return emptyList()
        val result = mutableListOf<T>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parse(obj)?.let { result.add(it) }
        }
        return result
    }

    /** 读取可空 Long 字段（JSONObject.NULL 或缺失 → null）。 */
    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    /** 读取可空 String 字段（JSONObject.NULL 或缺失 → null）。 */
    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    companion object {
        private const val PREFS_MEDIA_DETAIL = "media_detail_prefs"
        private const val KEY_FAVORITES = "favorite_record_keys"
        private const val PREFS_AUTHOR_FOLLOW = "author_follow_prefs"
        /** 导入 likes 条目数上限，防 SharedPreferences 键膨胀 */
        private const val MAX_LIKE_ENTRIES = 5000
        /** 导出 history 条目数上限（本地表 pruneToLimit 为 500，导出放宽到 1000 保留更多上下文） */
        private const val HISTORY_EXPORT_LIMIT = 1000
        /** settings 表中的设备本地键：恢复时跳过（重装后备份目录 URI 无意义） */
        private const val KEY_BACKUP_DIRECTORY_URI = "backup_directory_uri"
        private const val KEY_BACKUP_DIRECTORY_NAME = "backup_directory_name"
    }
}
