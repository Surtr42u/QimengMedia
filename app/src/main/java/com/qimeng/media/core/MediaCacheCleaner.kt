package com.qimeng.media.core

import android.content.Context
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache

/**
 * 清理媒体文件相关的缓存数据
 *
 * 删除媒体文件时，需要同步清理 Coil 内存缓存和本地缩略图缓存，
 * 避免已删除文件的缓存残留在设备上占用空间。
 *
 * Coil 磁盘缓存的 key 由内部 CacheKeyer 生成，无法通过 recordKey 或 URI 精确匹配，
 * 因此不做逐条清理，依赖 LRU 策略自然淘汰。真正占空间的是 ThumbnailCache 本地文件缓存。
 *
 * 设计为独立 object，避免 Repository 直接依赖 Coil API 和 Context。
 */
object MediaCacheCleaner {

    /**
     * 清理指定 recordKey 列表对应的缓存
     *
     * 包括：Coil 内存缓存、本地缩略图文件缓存
     *
     * @param context Application Context
     * @param recordKeys 需要清理的 recordKey 列表
     */
    fun cleanByRecordKeys(context: Context, recordKeys: List<String>) {
        if (recordKeys.isEmpty()) return
        cleanCoilMemoryCache(context, recordKeys)
        cleanThumbnailCache(context, recordKeys)
    }

    /** 清理 Coil 内存缓存（key 为 recordKey） */
    private fun cleanCoilMemoryCache(context: Context, recordKeys: List<String>) {
        val cache = SingletonImageLoader.get(context).memoryCache ?: return
        recordKeys.forEach { key ->
            cache.remove(MemoryCache.Key(key))
        }
    }

    /** 清理本地缩略图文件缓存 */
    private fun cleanThumbnailCache(context: Context, recordKeys: List<String>) {
        recordKeys.forEach { key ->
            ThumbnailCache.removeCache(context, key)
        }
    }
}
