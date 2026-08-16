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
        // v1.15 巡检：与各 setter 共用同一把锁（锁设计一致）。读-改-写（_prefs.value + saveToDisk）
        // 不进锁会与并发 setter 的写盘竞争，导致一方覆盖另一方刚写入的偏好、丢更新。
        return synchronized(lock) {
            try {
                val obj = JSONObject(json)
                // themeMode 非白名单值回退为导入前的当前值（而非硬编码默认），避免恢复备份悄悄改主题
                val imported = prefsFromJson(obj, _prefs.value.themeMode)
                _prefs.value = imported
                saveToDisk(imported)
                true
            } catch (e: Exception) {
                com.qimeng.media.core.AppLog.d("Prefs", "importFromJson failed: ${e.message}")
                false
            }
        }
    }

    private fun loadFromDisk(): AppPrefs {
        if (!prefsFile.exists()) return AppPrefs()
        return try {
            val json = prefsFile.readText()
            prefsFromJson(JSONObject(json))
        } catch (e: Exception) {
            // 偏好静默丢失不能只留调试日志：升级告警并保留 .corrupt 副本，便于排查损坏原因
            AppLog.w("AppPrefs", "loadFromDisk failed: ${e.message}", e)
            try {
                prefsFile.copyTo(File(prefsFile.parentFile, "$PREFS_FILE.corrupt"), overwrite = true)
            } catch (copyErr: Exception) {
                AppLog.w("AppPrefs", "loadFromDisk 保留 .corrupt 副本失败: ${copyErr.message}")
            }
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

        private fun prefsFromJson(obj: JSONObject, fallbackThemeMode: String = "system"): AppPrefs {
            val sources = mutableSetOf<String>()
            val sourcesArr = obj.optJSONArray("custom_album_sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    sources.add(sourcesArr.getString(i))
                }
            }
            // 解析推荐偏好，缺失/非法字段（NaN/无穷/负数/超 1）回退默认值
            val recJson = obj.optJSONObject("recommendation_prefs")
            val recommendationPrefs = if (recJson != null) {
                val defaults = RecommendationPrefs()
                RecommendationPrefs(
                    tagRelevance = sanitizeWeight(recJson.optDouble("tagRelevance", 0.22).toFloat(), defaults.tagRelevance),
                    tagCollection = sanitizeWeight(recJson.optDouble("tagCollection", 0.15).toFloat(), defaults.tagCollection),
                    engagement = sanitizeWeight(recJson.optDouble("engagement", 0.10).toFloat(), defaults.engagement),
                    recency = sanitizeWeight(recJson.optDouble("recency", 0.15).toFloat(), defaults.recency),
                    likeScore = sanitizeWeight(recJson.optDouble("likeScore", 0.05).toFloat(), defaults.likeScore),
                    discovery = sanitizeWeight(recJson.optDouble("discovery", 0.20).toFloat(), defaults.discovery),
                    freshness = sanitizeWeight(recJson.optDouble("freshness", 0.05).toFloat(), defaults.freshness),
                    browseDepth = sanitizeWeight(recJson.optDouble("browseDepth", 0.03).toFloat(), defaults.browseDepth),
                    maxRandom = sanitizeWeight(recJson.optDouble("maxRandom", 0.30).toFloat(), defaults.maxRandom)
                )
            } else {
                RecommendationPrefs()
            }
            return AppPrefs(
                customAlbumSources = sources,
                themeMode = sanitizeThemeMode(obj.optString("theme_mode", "system"), fallbackThemeMode),
                // 列数越界（0/负/超大）钳回 UI 合法区间：GridLayoutManager spanCount<=0 会直接崩溃
                gridColumnsHome = sanitizeGridColumns(obj.optInt("grid_columns_home", 2), HOME_COLUMNS_MIN, HOME_COLUMNS_MAX),
                gridColumnsAll = sanitizeGridColumns(obj.optInt("grid_columns_all", 3), GRID_COLUMNS_MIN, GRID_COLUMNS_MAX),
                gridColumnsAlbum = sanitizeGridColumns(obj.optInt("grid_columns_album", 3), GRID_COLUMNS_MIN, GRID_COLUMNS_MAX),
                autoSync = obj.optBoolean("auto_sync", false),
                recommendationPrefs = recommendationPrefs
            )
        }

        // ===== 导入/磁盘解析值钳制（纯函数，供 AppPrefsImportSanitizeTest 单测） =====

        /** 列数钳制：越界值拉回合法区间（GridLayoutManager spanCount<=0 崩溃，超上限缩略图过小） */
        fun sanitizeGridColumns(value: Int, minInclusive: Int, maxInclusive: Int): Int =
            value.coerceIn(minInclusive, maxInclusive)

        /** 主题模式白名单：非法串回退 fallback，防任意串入库后 UI 匹配不到分支 */
        fun sanitizeThemeMode(value: String, fallback: String): String =
            if (value in THEME_MODE_WHITELIST) value else fallback

        /** 推荐权重钳制：权重语义为 [0,1] 系数，NaN/无穷/负数/超 1 回退 fallback */
        fun sanitizeWeight(value: Float, fallback: Float): Float =
            if (value.isFinite() && value >= 0f && value <= 1f) value else fallback

        /** 主题模式合法值（AppCompatDelegate 昼夜模式语义） */
        private val THEME_MODE_WHITELIST = setOf("system", "light", "dark")

        /** 首页列数合法区间（HomeFragment 仅单/双列切换），默认 2 */
        private const val HOME_COLUMNS_MIN = 1
        private const val HOME_COLUMNS_MAX = 2

        /** 全部/相册页列数合法区间（PinchZoomHelper MIN/MAX），默认 3 */
        private const val GRID_COLUMNS_MIN = 2
        private const val GRID_COLUMNS_MAX = 5
    }
}
