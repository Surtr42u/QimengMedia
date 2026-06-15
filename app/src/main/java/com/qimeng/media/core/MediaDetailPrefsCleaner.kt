package com.qimeng.media.core

import android.content.Context

/**
 * 清理媒体详情相关的 SharedPreferences 数据
 *
 * 删除媒体文件时，需要同步清理点赞计数、点赞日期和收藏记录，
 * 否则这些数据会残留在 SharedPreferences 中占用空间。
 *
 * 设计为独立 object，避免 Repository 直接依赖 Context/SharedPreferences。
 * 由 UseCase 或 Repository 实现在需要时调用。
 */
object MediaDetailPrefsCleaner {
    private const val PREFS_MEDIA_DETAIL = "media_detail_prefs"
    private const val KEY_FAVORITES = "favorite_record_keys"
    private const val KEY_LIKE_COUNT_PREFIX = "like_count_"
    private const val KEY_LIKE_DATE_PREFIX = "like_date_"

    /**
     * 清理指定 recordKey 列表对应的 SharedPreferences 数据
     *
     * 包括：点赞计数(like_count_*)、点赞日期(like_date_*)、收藏记录(favorite_record_keys)
     *
     * @param context Application Context
     * @param recordKeys 需要清理的 recordKey 列表
     */
    fun cleanByRecordKeys(context: Context, recordKeys: List<String>) {
        if (recordKeys.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_MEDIA_DETAIL, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // 清理点赞计数和日期
        recordKeys.forEach { key ->
            editor.remove(KEY_LIKE_COUNT_PREFIX + key)
            editor.remove(KEY_LIKE_DATE_PREFIX + key)
        }

        // 清理收藏记录中对应的 key
        val favorites = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet()
        if (favorites != null && favorites.isNotEmpty()) {
            val before = favorites.size
            favorites.removeAll(recordKeys.toSet())
            if (favorites.size != before) {
                editor.putStringSet(KEY_FAVORITES, favorites)
            }
        }

        editor.apply()
    }

    /**
     * 清理 SharedPreferences 中引用了已删除文件的残留数据
     *
     * 遍历所有 like_count_* 和 favorite_record_keys，移除不在 validKeys 中的 recordKey。
     * 用于启动时一次性清理旧版 deleteMediaAndRefs 未清理的历史残留。
     *
     * @param context Application Context
     * @param validKeys 当前数据库中存在的 recordKey 集合
     */
    fun cleanOrphanEntries(context: Context, validKeys: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_MEDIA_DETAIL, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false

        // 清理点赞计数和日期中引用了已删除文件的条目
        for (entry in prefs.all.entries) {
            val key = entry.key as String
            if (key.startsWith(KEY_LIKE_COUNT_PREFIX) && entry.value is Int) {
                val recordKey = key.removePrefix(KEY_LIKE_COUNT_PREFIX)
                if (recordKey !in validKeys) {
                    editor.remove(key)
                    editor.remove(KEY_LIKE_DATE_PREFIX + recordKey)
                    changed = true
                }
            }
        }

        // 清理收藏记录中引用了已删除文件的条目
        val favorites = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet()
        if (favorites != null && favorites.isNotEmpty()) {
            val before = favorites.size
            favorites.removeAll { it !in validKeys }
            if (favorites.size != before) {
                editor.putStringSet(KEY_FAVORITES, favorites)
                changed = true
            }
        }

        if (changed) editor.apply()
    }
}
