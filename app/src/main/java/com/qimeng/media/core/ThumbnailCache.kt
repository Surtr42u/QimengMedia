package com.qimeng.media.core

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 本地缩略图/截帧文件缓存
 *
 * 将缩略图和视频截帧保存为本地 WebP 文件，加载时直接读取文件，
 * 不需要重新解码原始文件，大幅提升 SAF URI 文件的缩略图加载速度。
 *
 * 缓存目录：`app_cache/thumbnails/`
 * 文件命名：`{hash(recordKey)}.webp`（缩略图）和 `{hash(recordKey)}_frame.webp`（视频详情帧）
 *
 * 统一策略：图片缩略图和视频截帧都保存到本地文件
 */
object ThumbnailCache {
    private const val DIR_NAME = "thumbnails"
    private const val COMPRESS_QUALITY = 80

    /**
     * 根据设备性能动态计算并发数
     * CPU核数 + 运存大小 → 统一并发数
     *
     * 策略：
     * - 并发 = CPU核数 × 1.5，上限16
     * - 低内存（<4GB）时减半，避免OOM
     * - 图片视频共用并发池，视频少时图片可占满，反之亦然
     *
     * 示例：
     * - 8 Gen 3（8核16GB）：12并发
     * - 中端机（8核6GB）：12并发
     * - 低端机（4核2GB）：3并发
     */
    fun getConcurrency(context: Context): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val memMB = getDeviceTotalMemoryMB(context)
        val base = (cores * 3 / 2).coerceAtMost(16).coerceAtLeast(2)
        return if (memMB < 4096) (base / 2).coerceAtLeast(2) else base
    }

    /** 获取设备总内存（MB），用于判断是否低内存设备 */
    private fun getDeviceTotalMemoryMB(context: Context): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            ((mi?.totalMem ?: 0L) / (1024 * 1024)).toInt().coerceAtLeast(2048)
        } catch (e: Exception) {
            com.qimeng.media.core.AppLog.d("Thumb", "getDeviceTotalMemory failed: ${e.message}")
            2048 // 默认按低内存设备处理
        }
    }

    private fun cacheDir(context: Context): File {
        // 使用 filesDir 而非 cacheDir，避免系统低存储时自动清理缓存
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 缩略图文件名：{hash}.webp */
    private fun thumbFile(context: Context, recordKey: String): File {
        return File(cacheDir(context), "${hashKey(recordKey)}.webp")
    }

    /** 获取缩略图文件对象（不检查是否存在），用于 Coil 直接加载 */
    fun getThumbnailFile(context: Context, recordKey: String): File {
        return thumbFile(context, recordKey)
    }

    /** 视频详情帧文件名：{hash}_frame.webp */
    private fun frameFile(context: Context, recordKey: String): File {
        return File(cacheDir(context), "${hashKey(recordKey)}_frame.webp")
    }

    private fun hashKey(key: String): String {
        // 使用 SHA-256 前16字符，避免 hashCode 碰撞
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray())
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    // ===== 缩略图（列表页用，480x270） =====

    /** 读取本地缓存的缩略图，不存在返回 null */
    suspend fun readThumbnail(context: Context, recordKey: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = thumbFile(context, recordKey)
        if (!file.exists()) return@withContext null
        try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            AppLog.d("ThumbCache", "readThumbnail failed: ${e.message}")
            null
        }
    }

    /** 将缩略图 Bitmap 保存到本地文件 */
    suspend fun writeThumbnail(context: Context, recordKey: String, bitmap: Bitmap): Unit = withContext(Dispatchers.IO) {
        try {
            val file = thumbFile(context, recordKey)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, COMPRESS_QUALITY, fos)
            }
        } catch (e: Exception) {
            AppLog.d("ThumbCache", "writeThumbnail failed: ${e.message}")
        }
    }

    /** 检查缩略图是否已缓存 */
    fun isThumbnailCached(context: Context, recordKey: String): Boolean {
        return thumbFile(context, recordKey).exists()
    }

    // ===== 视频详情帧（详情页用，1440x2560） =====

    /** 读取本地缓存的视频详情帧，不存在返回 null */
    suspend fun readFrame(context: Context, recordKey: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = frameFile(context, recordKey)
        if (!file.exists()) return@withContext null
        try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            AppLog.d("ThumbCache", "readFrame failed: ${e.message}")
            null
        }
    }

    /** 将视频详情帧 Bitmap 保存到本地文件 */
    suspend fun writeFrame(context: Context, recordKey: String, bitmap: Bitmap): Unit = withContext(Dispatchers.IO) {
        try {
            val file = frameFile(context, recordKey)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, COMPRESS_QUALITY, fos)
            }
        } catch (e: Exception) {
            AppLog.d("ThumbCache", "writeFrame failed: ${e.message}")
        }
    }

    // ===== 批量预生成并缓存 =====

    /**
     * 为指定文件列表预生成缩略图并保存到本地缓存。
     * 优化策略：
     * - 图片优先排列（降采样解码极快），视频排后（截帧慢）
     * - 统一并发池，图片视频共享并发数
     * - 已缓存的自动跳过
     */
    suspend fun pregenerateThumbnails(
        context: Context,
        mediaFiles: List<MediaFileEntity>,
        onProgress: ((Int, Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val total = mediaFiles.size
        var count = 0
        val skipped = java.util.concurrent.atomic.AtomicInteger(0)
        val generated = java.util.concurrent.atomic.AtomicInteger(0)
        val failed = java.util.concurrent.atomic.AtomicInteger(0)

        val concurrency = getConcurrency(context)

        // GIF（ANIMATED_IMAGE）不需要预生成静态缓存，缩略图保持动画由 Coil 直接加载原始 URI
        val images = mediaFiles.filter { it.mediaType == MediaType.IMAGE }
        val videos = mediaFiles.filter { it.mediaType == MediaType.VIDEO }
        val gifs = mediaFiles.filter { it.mediaType == MediaType.ANIMATED_IMAGE }
        // 图片优先（快），视频排后（慢），共用并发池
        val workItems = images + videos
        AppLog.d("ThumbCache", "pregenerate: total=$total, images=${images.size}, videos=${videos.size}, gifs=${gifs.size}(skipped), concurrency=$concurrency")

        // GIF 不需要预生成静态缓存，直接计入已完成
        if (gifs.isNotEmpty()) {
            skipped.addAndGet(gifs.size)
            count += gifs.size
            onProgress?.invoke(count, total)
        }

        // 统一并发池：图片视频共享，图片优先排列确保快任务先完成
        coroutineScope {
            workItems.chunked(concurrency).forEach { batch ->
                batch.map { media ->
                    async {
                        try {
                            if (isThumbnailCached(context, media.recordKey)) {
                                skipped.incrementAndGet()
                                return@async
                            }
                            val isVideo = media.mediaType == MediaType.VIDEO
                            val bitmap = if (isVideo) {
                                ThumbnailLoader.loadVideoThumbnail(context, media.uriString)
                            } else {
                                ThumbnailLoader.loadImageThumbnail(context, media.uriString)
                            }
                            if (bitmap != null) {
                                writeThumbnail(context, media.recordKey, bitmap)
                                generated.incrementAndGet()
                            } else {
                                failed.incrementAndGet()
                                if (failed.get() <= 3) {
                                    AppLog.w("ThumbCache", "${if (isVideo) "video" else "image"} thumbnail failed: ${media.fileName}")
                                }
                            }
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            if (failed.get() <= 3) {
                                AppLog.e("ThumbCache", "thumbnail exception: ${media.fileName}", e)
                            }
                        }
                    }
                }.awaitAll()
                count += batch.size
                if (count % 200 == 0 || count == total) {
                    AppLog.d("ThumbCache", "pregenerate: progress $count/$total (skipped=${skipped.get()}, generated=${generated.get()}, failed=${failed.get()})")
                }
                onProgress?.invoke(count, total)
            }
        }

        AppLog.d("ThumbCache", "pregenerateThumbnails: done, $count/$total processed (skipped=${skipped.get()}, generated=${generated.get()}, failed=${failed.get()})")
    }

    // ===== 缓存管理 =====

    /** 获取缓存目录大小（字节） */
    fun cacheSize(context: Context): Long {
        val dir = cacheDir(context)
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 获取缓存文件数量 */
    fun cacheCount(context: Context): Int {
        val dir = cacheDir(context)
        return dir.listFiles()?.count { it.isFile } ?: 0
    }

    /** 清除所有缓存 */
    fun clearCache(context: Context) {
        val dir = cacheDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    /** 清除指定 recordKey 的缓存 */
    fun removeCache(context: Context, recordKey: String) {
        thumbFile(context, recordKey).delete()
        frameFile(context, recordKey).delete()
    }

    /**
     * 清除视频缩略图缓存（用于黑帧检测后重新生成）
     * 只删除不含 _frame 后缀的缓存文件中，对应视频的缩略图
     */
    fun clearVideoThumbnails(context: Context, videoRecordKeys: List<String>) {
        videoRecordKeys.forEach { key ->
            thumbFile(context, key).delete()
        }
        AppLog.d("ThumbCache", "clearVideoThumbnails: cleared ${videoRecordKeys.size} video thumbnails")
    }
}
