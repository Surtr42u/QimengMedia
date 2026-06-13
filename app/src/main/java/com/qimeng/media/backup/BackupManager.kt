package com.qimeng.media.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.AppDatabase
import com.qimeng.media.data.db.entity.AlbumRuleEntity
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.ScanSourceEntity
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.data.prefs.AppPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BackupManager(private val context: Context) {

    suspend fun autoSyncToDirectory(dirUri: Uri, database: AppDatabase, appPrefsManager: AppPrefsManager): Boolean = withContext(Dispatchers.IO) {
        try {
            // 自动同步写入"app数据"子文件夹
            val appDataDir = ensureSubDirectory(dirUri, "app数据") ?: dirUri
            val count = exportToDirectory(appDataDir, database, appPrefsManager)
            count > 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun exportToDirectory(dirUri: Uri, database: AppDatabase, appPrefsManager: AppPrefsManager): Int = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return@withContext 0
        val now = System.currentTimeMillis()
        var count = 0

        /**
         * 原子写入JSON文件：先写入临时文件，成功后再重命名
         * 避免写入过程中崩溃导致文件损坏
         */
        fun writeJson(name: String, json: JSONObject) {
            try {
                // 1. 写入临时文件
                val tempName = "${name}.tmp"
                dir.findFile(tempName)?.takeIf { it.isFile }?.delete()
                val tempFile = dir.createFile("application/json", tempName) ?: return
                val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
                
                context.contentResolver.openOutputStream(tempFile.uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                    // FileDescriptor.sync() 在 Android SAF 中不总是可用，我们尽量确保数据写入
                    // 在 ContentResolver OutputStream 中我们通过 flush 来保证
                } ?: return

                // 2. 删除旧文件（如果存在）
                dir.findFile(name)?.takeIf { it.isFile }?.delete()
                
                // 3. 重命名临时文件为最终文件名
                tempFile.renameTo(name)
                count++
            } catch (e: Exception) {
                AppLog.d("BackupManager", "writeJson failed for $name: ${e.message}")
                // 清理临时文件
                dir.findFile("${name}.tmp")?.delete()
            }
        }

        writeJson(BackupFileNames.SETTINGS, exportSettings(database, now))
        writeJson(BackupFileNames.AUTHORS, exportAuthors(database, now))
        writeJson(BackupFileNames.TAGS, exportTags(database, now))
        writeJson(BackupFileNames.ALBUM_RULES, exportAlbumRules(database, now))
        writeJson(BackupFileNames.MEDIA_STATS, exportMediaStats(database, now))
        writeJson(BackupFileNames.HISTORY, exportHistory(database, now))
        writeJson(BackupFileNames.SCAN_SOURCES, exportScanSources(database, now))
        writeJson(BackupFileNames.LIKES, exportLikes(now))
        writeJson(BackupFileNames.RECOMMENDATION_PREFS, exportRecommendationPrefs(appPrefsManager, now))
        writeJson(BackupFileNames.TIMELINE_TAGS, exportTimelineTags(database, now))
        count
    }

    suspend fun importFromDirectory(dirUri: Uri, database: AppDatabase, appPrefsManager: AppPrefsManager): Int = withContext(Dispatchers.IO) {
        // 从"app数据"子文件夹读取
        val appDataDirUri = findSubDirectory(dirUri, "app数据") ?: dirUri
        val dir = DocumentFile.fromTreeUri(context, appDataDirUri) ?: return@withContext 0
        var count = 0

        fun readJson(name: String): JSONObject? {
            val file = dir.findFile(name) ?: return null
            return try {
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    JSONObject(String(stream.readBytes()))
                }
            } catch (e: Exception) { com.qimeng.media.core.AppLog.d("Backup", "readJson failed for $name: ${e.message}"); null }
        }

        readJson(BackupFileNames.AUTHORS)?.let { importAuthors(it, database); count++ }
        readJson(BackupFileNames.TAGS)?.let { importTags(it, database); count++ }
        readJson(BackupFileNames.ALBUM_RULES)?.let { importAlbumRules(it, database); count++ }
        readJson(BackupFileNames.MEDIA_STATS)?.let { importMediaStats(it, database); count++ }
        readJson(BackupFileNames.HISTORY)?.let { importHistory(it, database); count++ }
        readJson(BackupFileNames.SCAN_SOURCES)?.let { importScanSources(it, database); count++ }
        readJson(BackupFileNames.LIKES)?.let { importLikes(it); count++ }
        readJson(BackupFileNames.RECOMMENDATION_PREFS)?.let { importRecommendationPrefs(it, appPrefsManager); count++ }
        readJson(BackupFileNames.TIMELINE_TAGS)?.let { importTimelineTags(it, database); count++ }
        count
    }

    private suspend fun exportSettings(db: AppDatabase, now: Long): JSONObject {
        val items = JSONArray()
        db.settingDao().getAll().forEach { s ->
            items.put(JSONObject().apply {
                put("key", s.key)
                put("value", s.value)
                put("updatedAtMillis", s.updatedAtMillis)
            })
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", now)
            put("data", JSONObject().put("items", items))
        }
    }

    private suspend fun exportAuthors(db: AppDatabase, now: Long): JSONObject {
        val authors = JSONArray()
        db.authorDao().getAllAuthors().forEach { author ->
            val files = JSONArray()
            // For now, empty files array (will be filled when author-media cross refs are added)
            authors.put(JSONObject().apply {
                put("authorId", author.authorId)
                put("displayName", author.displayName)
                put("files", files)
                put("createdAtMillis", author.createdAtMillis)
                put("updatedAtMillis", author.updatedAtMillis)
            })
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", now)
            put("data", JSONObject().put("authors", authors))
        }
    }

    private suspend fun exportTags(db: AppDatabase, now: Long): JSONObject {
        val allTags = db.tagDao().getAllTags()
        val tags = JSONArray()
        allTags.forEach { tag ->
            tags.put(JSONObject().apply {
                put("tagId", tag.tagId)
                put("name", tag.name)
                put("createdAtMillis", tag.createdAtMillis)
            })
        }
        val mediaTags = JSONArray()
        val tagIdToName = allTags.associateBy { it.tagId }
        db.tagDao().getAllCrossRefs().forEach { xref ->
            val tagName = tagIdToName[xref.tagId]?.name ?: ""
            mediaTags.put(JSONObject().apply {
                put("recordKey", xref.recordKey)
                put("fileName", xref.fileName)
                put("tagName", tagName)
            })
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", now)
            put("data", JSONObject().put("tags", tags).put("mediaTags", mediaTags))
        }
    }

    private suspend fun exportAlbumRules(db: AppDatabase, now: Long): JSONObject {
        val rules = JSONArray()
        db.albumRuleDao().getEnabledRulesByPriority().forEach { rule ->
            rules.put(JSONObject().apply {
                put("sourceName", rule.sourceName)
                put("enabled", rule.enabled)
                put("createdAtMillis", rule.createdAtMillis)
                put("updatedAtMillis", rule.updatedAtMillis)
            })
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", now)
            put("data", JSONObject().put("rules", rules))
        }
    }

    private suspend fun exportMediaStats(db: AppDatabase, now: Long): JSONObject {
        val items = JSONArray()
        db.viewStatsDao().getAllByFileName().forEach { stats ->
            items.put(JSONObject().apply {
                put("recordKey", stats.recordKey)
                put("fileName", stats.fileName)
                put("viewCount", stats.viewCount)
                put("playCount", stats.playCount)
                put("totalBrowseSeconds", stats.totalBrowseSeconds)
                put("lastOpenedAtMillis", stats.lastOpenedAtMillis)
                put("updatedAtMillis", stats.updatedAtMillis)
            })
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", now)
            put("data", JSONObject().put("items", items))
        }
    }

    private suspend fun exportHistory(db: AppDatabase, now: Long): JSONObject {
        val items = JSONArray()
        db.viewHistoryDao().getLatest(1000).forEach { history ->
            items.put(JSONObject().apply {
                put("recordKey", history.recordKey)
                put("fileName", history.fileName)
                put("mediaType", history.mediaType)
                put("openedAtMillis", history.openedAtMillis)
            })
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", now)
            put("data", JSONObject().put("items", items))
        }
    }

    private suspend fun exportScanSources(db: AppDatabase, now: Long): JSONObject {
        val sources = JSONArray()
        db.scanSourceDao().getAllSources().forEach { source ->
            sources.put(JSONObject().apply {
                put("uriString", source.uriString)
                put("displayName", source.displayName)
                put("isBackupDirectory", source.isBackupDirectory)
                put("addedAtMillis", source.addedAtMillis)
                put("lastScannedAtMillis", source.lastScannedAtMillis)
            })
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", now)
            put("data", JSONObject().put("sources", sources))
        }
    }

    private fun exportRecommendationPrefs(appPrefsManager: AppPrefsManager, now: Long): JSONObject {
        val prefs = appPrefsManager.prefs.value.recommendationPrefs
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", now)
            put("data", JSONObject().apply {
                put("tagRelevance", prefs.tagRelevance)
                put("tagCollection", prefs.tagCollection)
                put("engagement", prefs.engagement)
                put("recency", prefs.recency)
                put("likeScore", prefs.likeScore)
                put("discovery", prefs.discovery)
                put("freshness", prefs.freshness)
                put("browseDepth", prefs.browseDepth)
                put("maxRandom", prefs.maxRandom)
            })
        }
    }

    private fun importRecommendationPrefs(json: JSONObject, appPrefsManager: AppPrefsManager) {
        val data = json.optJSONObject("data") ?: return
        val prefs = com.qimeng.media.data.prefs.RecommendationPrefs(
            tagRelevance = data.optDouble("tagRelevance", 0.22).toFloat(),
            tagCollection = data.optDouble("tagCollection", 0.15).toFloat(),
            engagement = data.optDouble("engagement", 0.10).toFloat(),
            recency = data.optDouble("recency", 0.15).toFloat(),
            likeScore = data.optDouble("likeScore", 0.05).toFloat(),
            discovery = data.optDouble("discovery", 0.20).toFloat(),
            freshness = data.optDouble("freshness", 0.05).toFloat(),
            browseDepth = data.optDouble("browseDepth", 0.03).toFloat(),
            maxRandom = data.optDouble("maxRandom", 0.30).toFloat()
        )
        appPrefsManager.updateRecommendationPrefs(prefs)
    }

    private suspend fun importAuthors(json: JSONObject, db: AppDatabase) {
        val data = json.optJSONObject("data") ?: return
        val arr = data.optJSONArray("authors") ?: return
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val authorId = obj.optString("authorId", "author_$i")
            db.authorDao().upsertAuthor(
                AuthorEntity(
                    authorId = authorId.take(64),
                    displayName = obj.optString("displayName", ""),
                    createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                    updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis())
                )
            )
        }
    }

    private suspend fun importTags(json: JSONObject, db: AppDatabase) {
        val data = json.optJSONObject("data") ?: return
        val tags = data.optJSONArray("tags") ?: return
        for (i in 0 until tags.length()) {
            val obj = tags.optJSONObject(i) ?: continue
            val tag = com.qimeng.media.data.db.entity.TagEntity(
                tagId = obj.optLong("tagId", 0),
                name = obj.optString("name", ""),
                createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis())
            )
            db.tagDao().insert(tag)
        }
    }

    private suspend fun importAlbumRules(json: JSONObject, db: AppDatabase) {
        val data = json.optJSONObject("data") ?: return
        val rules = data.optJSONArray("rules") ?: return
        for (i in 0 until rules.length()) {
            val obj = rules.optJSONObject(i) ?: continue
            db.albumRuleDao().upsert(
                AlbumRuleEntity(
                    sourceName = obj.optString("sourceName", ""),
                    enabled = obj.optBoolean("enabled", true),
                    createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                    updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis())
                )
            )
        }
    }

    private suspend fun importMediaStats(json: JSONObject, db: AppDatabase) {
        val data = json.optJSONObject("data") ?: return
        val items = data.optJSONArray("items") ?: return
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            db.viewStatsDao().upsert(
                ViewStatsEntity(
                    recordKey = obj.optString("recordKey", ""),
                    fileName = obj.optString("fileName", ""),
                    viewCount = obj.optInt("viewCount", 0),
                    playCount = obj.optInt("playCount", 0),
                    totalBrowseSeconds = obj.optLong("totalBrowseSeconds", 0),
                    lastOpenedAtMillis = if (obj.has("lastOpenedAtMillis") && !obj.isNull("lastOpenedAtMillis")) obj.optLong("lastOpenedAtMillis") else null,
                    updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis())
                )
            )
        }
    }

    private suspend fun importHistory(json: JSONObject, db: AppDatabase) {
        val data = json.optJSONObject("data") ?: return
        val items = data.optJSONArray("items") ?: return
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            db.viewHistoryDao().upsert(
                ViewHistoryEntity(
                    recordKey = obj.optString("recordKey", ""),
                    fileName = obj.optString("fileName", ""),
                    mediaType = obj.optString("mediaType", "image"),
                    openedAtMillis = obj.optLong("openedAtMillis", System.currentTimeMillis())
                )
            )
        }
    }

    private suspend fun importScanSources(json: JSONObject, db: AppDatabase) {
        val data = json.optJSONObject("data") ?: return
        val sources = data.optJSONArray("sources") ?: return
        for (i in 0 until sources.length()) {
            val obj = sources.optJSONObject(i) ?: continue
            db.scanSourceDao().upsert(
                ScanSourceEntity(
                    uriString = obj.optString("uriString", ""),
                    displayName = obj.optString("displayName", ""),
                    isBackupDirectory = obj.optBoolean("isBackupDirectory", false),
                    addedAtMillis = obj.optLong("addedAtMillis", System.currentTimeMillis()),
                    lastScannedAtMillis = if (obj.has("lastScannedAtMillis") && !obj.isNull("lastScannedAtMillis")) obj.optLong("lastScannedAtMillis") else null
                )
            )
        }
    }

    private fun exportLikes(now: Long): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_MEDIA_DETAIL, Context.MODE_PRIVATE)
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
        val favorites = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
        val favItems = JSONArray()
        favorites.forEach { key -> favItems.put(key) }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", now)
            put("data", JSONObject().apply {
                put("likes", items)
                put("favorites", favItems)
            })
        }
    }

    private fun importLikes(json: JSONObject) {
        val data = json.optJSONObject("data") ?: return
        val prefs = context.getSharedPreferences(PREFS_MEDIA_DETAIL, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val likes = data.optJSONArray("likes")
        if (likes != null) {
            for (i in 0 until likes.length()) {
                val obj = likes.optJSONObject(i) ?: continue
                val recordKey = obj.optString("recordKey", "")
                if (recordKey.isBlank()) continue
                val likeCount = obj.optInt("likeCount", 0)
                val lastLikeDate = if (obj.has("lastLikeDate") && !obj.isNull("lastLikeDate")) obj.optString("lastLikeDate", "") else null
                editor.putInt("like_count_$recordKey", likeCount)
                if (lastLikeDate != null) {
                    editor.putString("like_date_$recordKey", lastLikeDate)
                }
            }
        }
        val favs = data.optJSONArray("favorites")
        if (favs != null) {
            val favSet = mutableSetOf<String>()
            for (i in 0 until favs.length()) {
                val key = favs.optString(i, "")
                if (key.isNotBlank()) favSet.add(key)
            }
            editor.putStringSet(KEY_FAVORITES, favSet)
        }
        editor.apply()
    }

    suspend fun exportPersonalPrefs(database: AppDatabase): Pair<JSONObject, String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val db = database

        val detailPrefs = context.getSharedPreferences(PREFS_MEDIA_DETAIL, Context.MODE_PRIVATE)
        val favSet = detailPrefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()

        // 构建 recordKey -> MediaFileEntity 映射（用于 mediaType 和 isCosFile）
        val allMediaFiles = db.mediaFileDao().getAll()
        val mediaFileMap = allMediaFiles.associateBy { it.recordKey }

        // COS 数据
        val cosMedia = db.mediaFileDao().observeCosMedia().firstOrNull().orEmpty()
        val cosRecordKeys = cosMedia.map { it.recordKey }.toSet()
        val cosWorks = db.cosWorkDao().getAll()
        val cosStats = db.viewStatsDao().getByRecordKeys(cosRecordKeys.toList())

        val favorites = JSONArray()
        favSet.forEach { key -> if (key.isNotBlank()) favorites.put(key) }

        val likes = JSONArray()
        val likeCountPrefix = "like_count_"
        val likeDatePrefix = "like_date_"
        val likeMap = mutableMapOf<String, Pair<Int, String?>>()
        for (entry in detailPrefs.all.entries) {
            val key = entry.key as String
            if (key.startsWith(likeCountPrefix) && entry.value is Int) {
                val recordKey = key.removePrefix(likeCountPrefix)
                val likeCount = entry.value as Int
                val lastLikeDate = detailPrefs.getString(likeDatePrefix + recordKey, null)
                likeMap[recordKey] = Pair(likeCount, lastLikeDate)
                likes.put(JSONObject().apply {
                    put("recordKey", recordKey)
                    put("likeCount", likeCount)
                    put("lastLikeDate", lastLikeDate ?: JSONObject.NULL)
                })
            }
        }

        val tags = JSONArray()
        val tagList = db.tagDao().getAllTags()
        tagList.forEach { tag ->
            tags.put(JSONObject().apply {
                put("tagId", tag.tagId)
                put("name", tag.name)
                put("createdAtMillis", tag.createdAtMillis)
            })
        }

        val mediaTags = JSONArray()
        val tagIdToName = tagList.associateBy { it.tagId }
        val crossRefs = db.tagDao().getAllCrossRefs()
        crossRefs.forEach { xref ->
            val tagName = tagIdToName[xref.tagId]?.name ?: ""
            mediaTags.put(JSONObject().apply {
                put("recordKey", xref.recordKey)
                put("fileName", xref.fileName)
                put("tagName", tagName)
            })
        }

        // viewStats 新增 mediaType 和 isCosFile 字段
        val viewStats = JSONArray()
        val statsList = db.viewStatsDao().getAllByFileName()
        val statsMap = mutableMapOf<String, ViewStatsEntity>()
        statsList.forEach { stats ->
            statsMap[stats.recordKey] = stats
            val mediaFile = mediaFileMap[stats.recordKey]
            viewStats.put(JSONObject().apply {
                put("recordKey", stats.recordKey)
                put("fileName", stats.fileName)
                put("viewCount", stats.viewCount)
                put("playCount", stats.playCount)
                put("totalBrowseSeconds", stats.totalBrowseSeconds)
                put("lastOpenedAtMillis", stats.lastOpenedAtMillis ?: JSONObject.NULL)
                put("updatedAtMillis", stats.updatedAtMillis)
                put("mediaType", mediaFile?.mediaType ?: "image")
                put("isCosFile", mediaFile?.isCosFile ?: false)
            })
        }

        val authors = JSONArray()
        val authorList = db.authorDao().getAllAuthors()
        val authorIdToName = authorList.associate { it.authorId to it.displayName }
        val authorMediaList = db.authorDao().observeAllAuthorMedia().firstOrNull().orEmpty()
        val authorFilesMap = mutableMapOf<String, MutableList<String>>()
        authorMediaList.forEach { xref ->
            authorFilesMap.getOrPut(xref.authorId) { mutableListOf() }.add(xref.recordKey)
        }

        val recordKeyTags = mutableMapOf<String, MutableSet<String>>()
        crossRefs.forEach { xref ->
            val tagName = tagIdToName[xref.tagId]?.name ?: return@forEach
            recordKeyTags.getOrPut(xref.recordKey) { mutableSetOf() }.add(tagName)
        }

        val authorTagsMap = mutableMapOf<String, MutableMap<String, Int>>()
        authorFilesMap.forEach { (authorId, files) ->
            val tagsCount = mutableMapOf<String, Int>()
            files.forEach { rk ->
                recordKeyTags[rk]?.forEach { tag ->
                    tagsCount[tag] = (tagsCount[tag] ?: 0) + 1
                }
            }
            if (tagsCount.isNotEmpty()) {
                authorTagsMap[authorId] = tagsCount
            }
        }

        authorList.forEach { author ->
            val files = JSONArray()
            (authorFilesMap[author.authorId] ?: emptyList()).forEach { files.put(it) }
            val authorObj = JSONObject().apply {
                put("authorId", author.authorId)
                put("displayName", author.displayName)
                put("files", files)
            }
            val authorTags = authorTagsMap[author.authorId]
            if (authorTags != null && authorTags.isNotEmpty()) {
                val tagsObj = JSONObject()
                authorTags.entries.sortedByDescending { it.value }.forEach { (tagName, count) ->
                    tagsObj.put(tagName, count)
                }
                authorObj.put("tags", tagsObj)
            }
            authors.put(authorObj)
        }

        // 读取已关注的作者ID列表
        val followPrefs = context.getSharedPreferences(PREFS_AUTHOR_FOLLOW, Context.MODE_PRIVATE)
        val followedIds = followPrefs.getStringSet("followed_authors", emptySet()).orEmpty()
        val followedArray = JSONArray()
        followedIds.sorted().forEach { followedArray.put(it) }

        // 给每个作者对象加上浏览次数和关注标记
        for (i in 0 until authors.length()) {
            val authorObj = authors.optJSONObject(i) ?: continue
            val authorId = authorObj.optString("authorId", "")
            var totalViews = 0
            authorFilesMap[authorId]?.forEach { rk ->
                totalViews += (statsMap[rk]?.viewCount ?: 0) + (statsMap[rk]?.playCount ?: 0)
            }
            authorObj.put("viewCount", totalViews)
            authorObj.put("followed", followedIds.contains(authorId))
        }

        // cosWorks：从原 exportCosPrefs 合并，扩展字段
        val cosWorksJson = JSONArray()
        val cosStatsMap = cosStats.associateBy { it.recordKey }
        // 构建 COS 作品的文件列表映射（folderUri -> recordKeys）
        val cosWorkFilesMap = mutableMapOf<String, MutableList<String>>()
        cosMedia.forEach { file ->
            cosWorks.forEach { work ->
                val prefix = if (work.folderUri.endsWith("/")) work.folderUri else "${work.folderUri}/"
                if (file.uriString.startsWith(prefix)) {
                    cosWorkFilesMap.getOrPut(work.folderUri) { mutableListOf() }.add(file.recordKey)
                }
            }
        }
        cosWorks.forEach { work ->
            val workFiles = cosWorkFilesMap[work.folderUri].orEmpty()
            var viewCountSum = 0
            var playCountSum = 0
            var totalBrowseSecondsSum = 0L
            var likeCountSum = 0
            var favoriteCount = 0
            val workTags = mutableMapOf<String, Int>()
            workFiles.forEach { rk ->
                viewCountSum += cosStatsMap[rk]?.viewCount ?: 0
                playCountSum += cosStatsMap[rk]?.playCount ?: 0
                totalBrowseSecondsSum += cosStatsMap[rk]?.totalBrowseSeconds ?: 0L
                likeCountSum += likeMap[rk]?.first ?: 0
                if (rk in favSet) favoriteCount++
                recordKeyTags[rk]?.forEach { tag ->
                    workTags[tag] = (workTags[tag] ?: 0) + 1
                }
            }
            val workObj = JSONObject().apply {
                put("authorName", work.authorName)
                put("workName", work.workName)
                put("folderUri", work.folderUri)
                put("fileCount", work.fileCount)
                put("viewCount", viewCountSum)
                put("playCount", playCountSum)
                put("totalBrowseSeconds", totalBrowseSecondsSum)
                put("likeCount", likeCountSum)
                put("favoriteCount", favoriteCount)
            }
            if (workTags.isNotEmpty()) {
                val tagsObj = JSONObject()
                workTags.entries.sortedByDescending { it.value }.forEach { (tagName, count) ->
                    tagsObj.put(tagName, count)
                }
                workObj.put("tags", tagsObj)
            }
            val filesArray = JSONArray()
            workFiles.forEach { filesArray.put(it) }
            workObj.put("files", filesArray)
            cosWorksJson.put(workObj)
        }

        // 统计数据
        val totalBrowseSeconds = statsList.sumOf { it.totalBrowseSeconds }
        val totalViews = statsList.sumOf { it.viewCount.toLong() }
        val totalPlays = statsList.sumOf { it.playCount.toLong() }

        val tagFreq = mutableMapOf<String, Int>()
        crossRefs.forEach { xref ->
            val name = tagIdToName[xref.tagId]?.name ?: return@forEach
            tagFreq[name] = (tagFreq[name] ?: 0) + 1
        }

        val authorScoreMap = mutableMapOf<String, Int>()
        authorFilesMap.forEach { (authorId, files) ->
            var score = 0
            files.forEach { rk ->
                score += (statsMap[rk]?.viewCount ?: 0) + (statsMap[rk]?.playCount ?: 0)
                if (rk in favSet) score += 5
                score += likeMap[rk]?.first ?: 0
            }
            authorScoreMap[authorId] = score
        }

        // 常规/COS 收藏文件数
        val normalFavCount = favSet.count { it !in cosRecordKeys }
        val cosFavCount = favSet.count { it in cosRecordKeys }
        // 常规/COS 文件总数
        val normalFileCount = allMediaFiles.count { !it.isCosFile }
        val cosFileCount = allMediaFiles.count { it.isCosFile }

        val reportText = buildReportText(
            favSet = favSet,
            likeMap = likeMap,
            statsList = statsList,
            statsMap = statsMap,
            mediaFileMap = mediaFileMap,
            tagFreq = tagFreq,
            authorScoreMap = authorScoreMap,
            followedIds = followedIds,
            authorIdToName = authorIdToName,
            authorFilesMap = authorFilesMap,
            authorTagsMap = authorTagsMap,
            tagList = tagList,
            authorList = authorList,
            totalBrowseSeconds = totalBrowseSeconds,
            totalViews = totalViews,
            totalPlays = totalPlays,
            normalFileCount = normalFileCount,
            cosFileCount = cosFileCount,
            normalFavCount = normalFavCount,
            cosFavCount = cosFavCount,
            cosRecordKeys = cosRecordKeys,
            now = now
        )

        JSONObject().apply {
            put("schemaVersion", 2)
            put("exportedAtMillis", now)
            put("appIdentifier", "com.qimeng.media")
            put("data", JSONObject().apply {
                put("favorites", favorites)
                put("likes", likes)
                put("tags", tags)
                put("mediaTags", mediaTags)
                put("viewStats", viewStats)
                put("authors", authors)
                put("followedAuthorIds", followedArray)
                put("cosWorks", cosWorksJson)
            })
        } to reportText
    }

    private fun buildReportText(
        favSet: Set<String>,
        likeMap: Map<String, Pair<Int, String?>>,
        statsList: List<ViewStatsEntity>,
        statsMap: Map<String, ViewStatsEntity>,
        mediaFileMap: Map<String, com.qimeng.media.data.db.entity.MediaFileEntity>,
        tagFreq: Map<String, Int>,
        authorScoreMap: Map<String, Int>,
        followedIds: Set<String>,
        authorIdToName: Map<String, String>,
        authorFilesMap: Map<String, List<String>>,
        authorTagsMap: Map<String, Map<String, Int>>,
        tagList: List<com.qimeng.media.data.db.entity.TagEntity>,
        authorList: List<com.qimeng.media.data.db.entity.AuthorEntity>,
        totalBrowseSeconds: Long,
        totalViews: Long,
        totalPlays: Long,
        normalFileCount: Int,
        cosFileCount: Int,
        normalFavCount: Int,
        cosFavCount: Int,
        cosRecordKeys: Set<String>,
        now: Long
    ): String {
        val sb = StringBuilder()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))

        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  绮梦影库 · 个人偏好报告")
        sb.appendLine("  导出时间：$date")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()

        // 1. 【总览】
        sb.appendLine("【总览】")
        sb.appendLine("  文件总数：常规 $normalFileCount / COS $cosFileCount")
        sb.appendLine("  收藏文件数：常规 $normalFavCount / COS $cosFavCount")
        sb.appendLine("  有点赞的文件数：${likeMap.size}")
        sb.appendLine("  标签总数：${tagList.size}")
        sb.appendLine("  作者总数：${authorList.size}")
        sb.appendLine("  关注作者数：${followedIds.size}")
        sb.appendLine("  总查看次数：$totalViews")
        sb.appendLine("  总播放次数：$totalPlays")
        val hours = String.format("%.1f", totalBrowseSeconds / 3600.0)
        sb.appendLine("  总浏览时长：$hours 小时")
        sb.appendLine()

        // 热度分计算
        data class HotEntry(
            val recordKey: String,
            val hotScore: Int,
            val isCosFile: Boolean,
            val mediaType: String,
            val viewCount: Int,
            val playCount: Int,
            val totalBrowseSeconds: Long,
            val likeCount: Int,
            val isFavorite: Boolean
        )

        val hotEntries = statsList.map { stats ->
            val mf = mediaFileMap[stats.recordKey]
            val isCos = mf?.isCosFile ?: (stats.recordKey in cosRecordKeys)
            val mType = mf?.mediaType ?: "image"
            val likeCount = likeMap[stats.recordKey]?.first ?: 0
            val isFav = stats.recordKey in favSet
            val hotScore = if (mType == "video") {
                stats.viewCount + stats.playCount + (stats.totalBrowseSeconds / 60).toInt() + likeCount + (if (isFav) 5 else 0)
            } else {
                stats.viewCount + likeCount + (if (isFav) 5 else 0)
            }
            HotEntry(stats.recordKey, hotScore, isCos, mType, stats.viewCount, stats.playCount, stats.totalBrowseSeconds, likeCount, isFav)
        }

        fun HotEntry.formatLine(): String {
            val favMark = if (isFavorite) " / ★收藏" else ""
            return if (mediaType == "video") {
                val mins = totalBrowseSeconds / 60
                "$recordKey [视频] — 播放 $playCount 次 / 浏览 $mins 分钟 / 点赞 $likeCount$favMark"
            } else {
                "$recordKey [图片] — 查看 $viewCount 次 / 点赞 $likeCount$favMark"
            }
        }

        // 2. 【总 Top 30】
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("【总 Top 30】（COS+常规混合，按热度分降序）")
        hotEntries.sortedByDescending { it.hotScore }.take(30).forEachIndexed { i, entry ->
            sb.appendLine("  ${i + 1}. ${entry.formatLine()}")
        }
        sb.appendLine()

        // 3. 【常规 Top 20】
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("【常规 Top 20】（只显示非 COS 文件）")
        hotEntries.filter { !it.isCosFile }.sortedByDescending { it.hotScore }.take(20).forEachIndexed { i, entry ->
            sb.appendLine("  ${i + 1}. ${entry.formatLine()}")
        }
        sb.appendLine()

        // 4. 【COS Top 20】
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("【COS Top 20】（只显示 COS 文件）")
        hotEntries.filter { it.isCosFile }.sortedByDescending { it.hotScore }.take(20).forEachIndexed { i, entry ->
            sb.appendLine("  ${i + 1}. ${entry.formatLine()}")
        }
        sb.appendLine()

        // 5. 【作者 Top 20】
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("【作者 Top 20】（按偏好度降序）")
        authorScoreMap.entries.sortedByDescending { it.value }.take(20).forEachIndexed { i, (authorId, score) ->
            val name = authorIdToName[authorId] ?: authorId
            val fileCount = authorFilesMap[authorId]?.size ?: 0
            val followedMark = if (authorId in followedIds) " ★" else ""
            val tags = authorTagsMap[authorId]
            val tagPart = if (tags != null && tags.isNotEmpty()) {
                " / 标签：" + tags.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}(${it.value})" }
            } else ""
            sb.appendLine("  ${i + 1}. $name$followedMark — ${fileCount} 个文件 / 偏好度 $score$tagPart")
        }
        sb.appendLine()

        // 6. 【关注的作者】
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("【关注的作者】（共 ${followedIds.size} 个）")
        if (followedIds.isEmpty()) {
            sb.appendLine("  暂无关注作者")
        } else {
            followedIds.mapNotNull { id -> authorIdToName[id]?.let { id to it } }
                .sortedByDescending { (id, _) -> authorScoreMap[id] ?: 0 }
                .forEachIndexed { i, (id, name) ->
                    val fileCount = authorFilesMap[id]?.size ?: 0
                    val score = authorScoreMap[id] ?: 0
                    sb.appendLine("  ${i + 1}. $name — ${fileCount} 个文件 / 偏好度 $score")
                }
        }
        sb.appendLine()

        // 7. 【标签 Top 10】
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("【标签 Top 10】（按关联文件数降序）")
        tagFreq.entries.sortedByDescending { it.value }.take(10).forEachIndexed { i, (name, count) ->
            sb.appendLine("  ${i + 1}. $name — ${count} 个文件")
        }
        sb.appendLine()

        // 8. 【所有标签】
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("【所有标签】（共 ${tagList.size} 个）")
        tagList.sortedByDescending { tagFreq[it.name] ?: 0 }.forEach { tag ->
            val count = tagFreq[tag.name] ?: 0
            sb.appendLine("  · ${tag.name} ($count 个文件)")
        }
        sb.appendLine()

        // 9. 【收藏的文件】
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("【收藏的文件】（共 ${favSet.size} 个）")
        favSet.sorted().forEach { sb.appendLine("  · $it") }
        sb.appendLine()

        // 10. 排行说明 + 报告结束
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("【排行说明】")
        sb.appendLine("  热度分计算规则：")
        sb.appendLine("    图片：查看次数 + 点赞次数 + (收藏 ? 5 : 0)")
        sb.appendLine("    视频：查看次数 + 播放次数 + 浏览分钟数 + 点赞次数 + (收藏 ? 5 : 0)")
        sb.appendLine("  作者偏好度 = 所有关联文件的 (查看次数+播放次数) 之和 + 每个收藏文件 +5 + 每个文件的点赞次数之和")
        sb.appendLine()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  报告结束")
        sb.appendLine("═══════════════════════════════════════")

        return sb.toString()
    }

    suspend fun exportPersonalPrefsToFile(dirUri: Uri, database: AppDatabase): Boolean = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return@withContext false

        /**
         * 原子写入文件：先写入临时文件，成功后再重命名
         */
        fun writeFile(name: String, mimeType: String, content: ByteArray): Boolean {
            return try {
                val tempName = "${name}.tmp"
                dir.findFile(tempName)?.delete()
                val tempFile = dir.createFile(mimeType, tempName) ?: return false
                
                context.contentResolver.openOutputStream(tempFile.uri)?.use { out ->
                    out.write(content)
                    out.flush()
                } ?: return false

                dir.findFile(name)?.delete()
                tempFile.renameTo(name)
                true
            } catch (e: Exception) {
                AppLog.d("BackupManager", "writeFile failed for $name: ${e.message}")
                dir.findFile("${name}.tmp")?.delete()
                false
            }
        }

        val (json, reportText) = exportPersonalPrefs(database)
        val success1 = writeFile(BackupFileNames.PERSONAL_PREFS, "application/json", json.toString(2).toByteArray(Charsets.UTF_8))
        val success2 = writeFile(BackupFileNames.PERSONAL_PREFS_REPORT, "text/plain", reportText.toByteArray(Charsets.UTF_8))

        success1 && success2
    }

    private fun formatSeconds(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}小时${minutes}分钟" else "${minutes}分钟"
    }

    companion object {
        private const val PREFS_MEDIA_DETAIL = "media_detail_prefs"
        private const val KEY_FAVORITES = "favorite_record_keys"
        private const val PREFS_AUTHOR_FOLLOW = "author_follow_prefs"
    }

    /** 全量同步：同时写入 app数据/、个人偏好/ 和 格式说明/ 三个子目录 */
    suspend fun fullSyncToDirectory(dirUri: Uri, database: AppDatabase, appPrefsManager: AppPrefsManager): Boolean = withContext(Dispatchers.IO) {
        try {
            // 同步 app数据/
            val appDataDir = ensureSubDirectory(dirUri, "app数据") ?: dirUri
            val count = exportToDirectory(appDataDir, database, appPrefsManager)
            // 同步 个人偏好/
            val prefsDir = ensureSubDirectory(dirUri, "个人偏好")
            if (prefsDir != null) {
                exportPersonalPrefsToFile(prefsDir, database)
            }
            // 同步 格式说明/（仅在目录不存在时写入，避免每次同步覆盖）
            val specDir = ensureSubDirectory(dirUri, "格式说明")
            if (specDir != null) {
                exportFormatSpec(specDir)
            }
            count > 0
        } catch (e: Exception) {
            AppLog.e("BackupManager", "fullSyncToDirectory failed", e)
            false
        }
    }

    /** 将格式说明文件（规范 + 示例）写入指定目录 */
    private fun exportFormatSpec(specDirUri: Uri) {
        val dir = DocumentFile.fromTreeUri(context, specDirUri) ?: return

        fun copyAsset(assetPath: String, destName: String, mimeType: String) {
            try {
                // 已存在则跳过，避免每次同步覆盖
                if (dir.findFile(destName) != null) return
                val content = context.assets.open(assetPath).readBytes()
                val tempName = "${destName}.tmp"
                dir.findFile(tempName)?.delete()
                val tempFile = dir.createFile(mimeType, tempName) ?: return
                context.contentResolver.openOutputStream(tempFile.uri)?.use { out ->
                    out.write(content)
                    out.flush()
                } ?: return
                tempFile.renameTo(destName)
            } catch (e: Exception) {
                AppLog.d("BackupManager", "copyAsset failed for $assetPath: ${e.message}")
            }
        }

        // 写入 FORMAT_SPEC.md
        copyAsset("FORMAT_SPEC.md", BackupFileNames.FORMAT_SPEC, "text/markdown")
        // 写入 personal_prefs_example.json
        copyAsset("personal_prefs_example.json", BackupFileNames.PERSONAL_PREFS_EXAMPLE, "application/json")

        // 写入 app_data_examples/ 子目录
        val examplesDir = dir.findFile(BackupFileNames.APP_DATA_EXAMPLES_DIR)
            ?.takeIf { it.isDirectory }
            ?: dir.createDirectory(BackupFileNames.APP_DATA_EXAMPLES_DIR)
            ?: return

        fun copyExampleAsset(assetPath: String, destName: String) {
            try {
                if (examplesDir.findFile(destName) != null) return
                val content = context.assets.open(assetPath).readBytes()
                val tempName = "${destName}.tmp"
                examplesDir.findFile(tempName)?.delete()
                val tempFile = examplesDir.createFile("application/json", tempName) ?: return
                context.contentResolver.openOutputStream(tempFile.uri)?.use { out ->
                    out.write(content)
                    out.flush()
                } ?: return
                tempFile.renameTo(destName)
            } catch (e: Exception) {
                AppLog.d("BackupManager", "copyExampleAsset failed for $assetPath: ${e.message}")
            }
        }

        copyExampleAsset("app_data_examples/settings_example.json", BackupFileNames.SETTINGS_EXAMPLE)
        copyExampleAsset("app_data_examples/authors_example.json", BackupFileNames.AUTHORS_EXAMPLE)
        copyExampleAsset("app_data_examples/tags_example.json", BackupFileNames.TAGS_EXAMPLE)
        copyExampleAsset("app_data_examples/album_rules_example.json", BackupFileNames.ALBUM_RULES_EXAMPLE)
        copyExampleAsset("app_data_examples/media_stats_example.json", BackupFileNames.MEDIA_STATS_EXAMPLE)
        copyExampleAsset("app_data_examples/history_example.json", BackupFileNames.HISTORY_EXAMPLE)
        copyExampleAsset("app_data_examples/scan_sources_example.json", BackupFileNames.SCAN_SOURCES_EXAMPLE)
        copyExampleAsset("app_data_examples/likes_example.json", BackupFileNames.LIKES_EXAMPLE)
        copyExampleAsset("app_data_examples/recommendation_prefs_example.json", BackupFileNames.RECOMMENDATION_PREFS_EXAMPLE)
        copyExampleAsset("app_data_examples/timeline_tags_example.json", BackupFileNames.TIMELINE_TAGS_EXAMPLE)
    }

    /** 在dirUri下查找名为subDirName的子目录，返回其URI，不存在则返回null */
    fun findSubDirectory(dirUri: Uri, subDirName: String): Uri? {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return null
        return dir.findFile(subDirName)?.takeIf { it.isDirectory }?.uri
    }

    /** 在dirUri下确保存在名为subDirName的子目录，返回其URI，失败则返回null */
    private fun ensureSubDirectory(dirUri: Uri, subDirName: String): Uri? {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return null
        val existing = dir.findFile(subDirName)
        if (existing != null && existing.isDirectory) return existing.uri
        return dir.createDirectory(subDirName)?.uri
    }

    private suspend fun exportTimelineTags(db: AppDatabase, now: Long): JSONObject {
        val tags = db.timelineTagDao().getAll()
        val items = JSONArray()
        tags.forEach { tag ->
            items.put(JSONObject().apply {
                put("recordKey", tag.recordKey)
                put("fileName", tag.fileName)
                put("timeMillis", tag.timeMillis)
                put("name", tag.name)
                put("createdAtMillis", tag.createdAtMillis)
            })
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", now)
            put("data", JSONObject().put("items", items))
        }
    }

    private suspend fun importTimelineTags(json: JSONObject, db: AppDatabase) {
        val items = json.optJSONObject("data")?.optJSONArray("items") ?: return
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            db.timelineTagDao().upsert(com.qimeng.media.data.db.entity.TimelineTagEntity(
                recordKey = obj.optString("recordKey", ""),
                fileName = obj.optString("fileName", ""),
                timeMillis = obj.optLong("timeMillis", 0),
                name = obj.optString("name", ""),
                createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis())
            ))
        }
    }
}
