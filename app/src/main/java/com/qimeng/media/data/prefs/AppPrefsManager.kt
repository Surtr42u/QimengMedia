package com.qimeng.media.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.qimeng.media.core.AppLog

data class RecommendationPrefs(
    val tagRelevance: Float = 0.22f,
    val tagCollection: Float = 0.15f,
    val engagement: Float = 0.10f,
    val recency: Float = 0.15f,
    val likeScore: Float = 0.05f,
    val discovery: Float = 0.20f,
    val freshness: Float = 0.05f,
    val browseDepth: Float = 0.03f,
    val maxRandom: Float = 0.30f
)

data class AppPrefs(
    val customAlbumSources: Set<String> = emptySet(),
    val themeMode: String = "system",
    val gridColumnsHome: Int = 2,
    val gridColumnsAll: Int = 3,
    val gridColumnsAlbum: Int = 3,
    val autoSync: Boolean = false,
    val recommendationPrefs: RecommendationPrefs = RecommendationPrefs()
)

class AppPrefsManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefsFile = File(appContext.filesDir, PREFS_FILE)

    // v1.15：读-改-写与磁盘写共享同一把锁，避免多线程并发 setter 时丢更新/写盘竞争
    private val lock = Any()

    private val _prefs = MutableStateFlow(loadFromDisk())
    val prefs = _prefs.asStateFlow()

    val customAlbumSources get() = _prefs.value.customAlbumSources

    fun addCustomAlbumSource(name: String) {
        synchronized(lock) {
            val current = _prefs.value
            if (name in current.customAlbumSources) return
            val updated = current.copy(customAlbumSources = current.customAlbumSources + name)
            _prefs.value = updated
            saveToDisk(updated)
        }
    }

    fun removeCustomAlbumSource(name: String) {
        synchronized(lock) {
            val current = _prefs.value
            if (name !in current.customAlbumSources) return
            val updated = current.copy(customAlbumSources = current.customAlbumSources - name)
            _prefs.value = updated
            saveToDisk(updated)
        }
    }

    fun updateThemeMode(mode: String) {
        synchronized(lock) {
            val current = _prefs.value
            if (current.themeMode == mode) return
            val updated = current.copy(themeMode = mode)
            _prefs.value = updated
            saveToDisk(updated)
        }
    }

    fun updateGridColumnsHome(columns: Int) {
        synchronized(lock) {
            val current = _prefs.value
            if (current.gridColumnsHome == columns) return
            val updated = current.copy(gridColumnsHome = columns)
            _prefs.value = updated
            saveToDisk(updated)
        }
    }

    fun updateGridColumnsAll(columns: Int) {
        synchronized(lock) {
            val current = _prefs.value
            if (current.gridColumnsAll == columns) return
            val updated = current.copy(gridColumnsAll = columns)
            _prefs.value = updated
            saveToDisk(updated)
        }
    }

    fun updateGridColumnsAlbum(columns: Int) {
        synchronized(lock) {
            val current = _prefs.value
            if (current.gridColumnsAlbum == columns) return
            val updated = current.copy(gridColumnsAlbum = columns)
            _prefs.value = updated
            saveToDisk(updated)
        }
    }

    fun updateAutoSync(enabled: Boolean) {
        synchronized(lock) {
            val current = _prefs.value
            if (current.autoSync == enabled) return
            val updated = current.copy(autoSync = enabled)
            _prefs.value = updated
            saveToDisk(updated)
        }
    }

    fun updateRecommendationPrefs(prefs: RecommendationPrefs) {
        synchronized(lock) {
            val current = _prefs.value
            if (current.recommendationPrefs == prefs) return
            val updated = current.copy(recommendationPrefs = prefs)
            _prefs.value = updated
            saveToDisk(updated)
        }
    }

    fun exportToJson(): String {
        return prefsToJson(_prefs.value).toString(2)
    }

    fun importFromJson(json: String): Boolean {
        return try {
            val obj = JSONObject(json)
            val imported = prefsFromJson(obj)
            _prefs.value = imported
            saveToDisk(imported)
            true
        } catch (e: Exception) {
            com.qimeng.media.core.AppLog.d("Prefs", "importFromJson failed: ${e.message}")
            false
        }
    }

    private fun loadFromDisk(): AppPrefs {
        if (!prefsFile.exists()) return AppPrefs()
        return try {
            val json = prefsFile.readText()
            prefsFromJson(JSONObject(json))
        } catch (e: Exception) {
            com.qimeng.media.core.AppLog.d("Prefs", "loadFromDisk failed: ${e.message}")
            AppPrefs()
        }
    }

    private fun saveToDisk(prefs: AppPrefs) {
        try {
            val dir = prefsFile.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            // v1.12：原子写（tmp + rename）——旧实现 writeText 截断写，进程在写入中
            // 被杀会留下损坏的 JSON，下次启动 loadFromDisk 失败回退默认值、用户偏好静默丢失。
            val tmpFile = File(dir, "$PREFS_FILE.tmp")
            tmpFile.writeText(prefsToJson(prefs).toString())
            if (!tmpFile.renameTo(prefsFile)) {
                // rename 失败（极端情况）回退直接写，至少保证内容落盘
                prefsFile.writeText(prefsToJson(prefs).toString())
                tmpFile.delete()
            }
        } catch (e: Exception) {
            AppLog.w("AppPrefs", "saveToDisk failed: ${e.message}")
        }
    }

    companion object {
        const val PREFS_FILE = "app_prefs.json"
        const val VERSION = 1

        private fun prefsToJson(prefs: AppPrefs): JSONObject {
            val sourcesArr = JSONArray()
            prefs.customAlbumSources.sorted().forEach { sourcesArr.put(it) }
            val recPrefs = prefs.recommendationPrefs
            val recJson = JSONObject().apply {
                put("tagRelevance", recPrefs.tagRelevance)
                put("tagCollection", recPrefs.tagCollection)
                put("engagement", recPrefs.engagement)
                put("recency", recPrefs.recency)
                put("likeScore", recPrefs.likeScore)
                put("discovery", recPrefs.discovery)
                put("freshness", recPrefs.freshness)
                put("browseDepth", recPrefs.browseDepth)
                put("maxRandom", recPrefs.maxRandom)
            }
            return JSONObject().apply {
                put("version", VERSION)
                put("custom_album_sources", sourcesArr)
                put("theme_mode", prefs.themeMode)
                put("grid_columns_home", prefs.gridColumnsHome)
                put("grid_columns_all", prefs.gridColumnsAll)
                put("grid_columns_album", prefs.gridColumnsAlbum)
                put("auto_sync", prefs.autoSync)
                put("recommendation_prefs", recJson)
            }
        }

        private fun prefsFromJson(obj: JSONObject): AppPrefs {
            val sources = mutableSetOf<String>()
            val sourcesArr = obj.optJSONArray("custom_album_sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    sources.add(sourcesArr.getString(i))
                }
            }
            // 解析推荐偏好，缺失字段使用默认值
            val recJson = obj.optJSONObject("recommendation_prefs")
            val recommendationPrefs = if (recJson != null) {
                RecommendationPrefs(
                    tagRelevance = recJson.optDouble("tagRelevance", 0.22).toFloat(),
                    tagCollection = recJson.optDouble("tagCollection", 0.15).toFloat(),
                    engagement = recJson.optDouble("engagement", 0.10).toFloat(),
                    recency = recJson.optDouble("recency", 0.15).toFloat(),
                    likeScore = recJson.optDouble("likeScore", 0.05).toFloat(),
                    discovery = recJson.optDouble("discovery", 0.20).toFloat(),
                    freshness = recJson.optDouble("freshness", 0.05).toFloat(),
                    browseDepth = recJson.optDouble("browseDepth", 0.03).toFloat(),
                    maxRandom = recJson.optDouble("maxRandom", 0.30).toFloat()
                )
            } else {
                RecommendationPrefs()
            }
            return AppPrefs(
                customAlbumSources = sources,
                themeMode = obj.optString("theme_mode", "system"),
                gridColumnsHome = obj.optInt("grid_columns_home", 2),
                gridColumnsAll = obj.optInt("grid_columns_all", 3),
                gridColumnsAlbum = obj.optInt("grid_columns_album", 3),
                autoSync = obj.optBoolean("auto_sync", false),
                recommendationPrefs = recommendationPrefs
            )
        }
    }
}
