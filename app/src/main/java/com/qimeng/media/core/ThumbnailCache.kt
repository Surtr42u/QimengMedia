package com.qimeng.media.core

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 本地缩略图/截帧文件缓存
 *
 * 将缩略图和视频截帧保存为本地 WebP 文件，加载时直接读取文件，
 * 不需要重新解码原始文件，大幅提升 SAF URI 文件的缩略图加载速度。
 *
 * 缓存目录：`filesDir/thumbnails/`
 * 文件命名：`{hash(recordKey)}.webp`
 *
 * 统一策略：图片缩略图和视频截帧都保存到本地文件
 *
 * 缓存规模（v1.16）：有效文件的缓存永不因数量上限被删（旧版按 5000 文件数淘汰最旧，
 * 库文件 >5000 时会误删有效缓存导致每次启动重新缓存一部分缩略图）。
 * 目录规模与库文件数成正比，不残留孤儿由「删除文件时同步清理（deleteMediaAndRefs →
 * MediaCacheCleaner）+ 启动预生成前 pruneOrphanThumbnails 对账」保证。
 */
object ThumbnailCache {
    private const val DIR_NAME = "thumbnails"
    private const val COMPRESS_QUALITY = 80

    /**
     * v1.12：视频截帧并发上限。视频截帧每任务峰值持有 2 张原尺寸帧，
     * 4K 源 12 并发可达约 800MB 原生堆；固定低并发控制峰值内存。
     */
    private const val VIDEO_CONCURRENCY = 2

    // ===== 滚动暂停（列表滑动期间让出 IO） =====

    /**
     * 滚动暂停标志：列表滚动期间置 true，预生成在 processBatch 解码前循环等待，
     * 把磁盘 IO 让给滚动中的按需解码；停止滚动（IDLE）后恢复。
     * 由列表页 OnScrollListener 调用 [setScrollPaused] 维护。
     */
    @Volatile
    private var scrollPaused = false

    /** 滚动期间暂停/恢复缩略图预生成（列表页 OnScrollListener 调用） */
    fun setScrollPaused(paused: Boolean) {
        scrollPaused = paused
    }

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
        return File(cacheDir(context), "${ThumbnailCacheKeys.hashKey(recordKey)}.webp")
    }

    /** 获取缩略图文件对象（不检查是否存在），用于 Coil 直接加载 */
    fun getThumbnailFile(context: Context, recordKey: String): File {
        return thumbFile(context, recordKey)
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
        val skipped = java.util.concurrent.atomic.AtomicInteger(0)
        val generated = java.util.concurrent.atomic.AtomicInteger(0)
        val failed = java.util.concurrent.atomic.AtomicInteger(0)

        val concurrency = getConcurrency(context)

        // GIF（ANIMATED_IMAGE）不需要预生成静态缓存，缩略图保持动画由 Coil 直接加载原始 URI
        val images = mediaFiles.filter { it.mediaType == MediaType.IMAGE }
        val videos = mediaFiles.filter { it.mediaType == MediaType.VIDEO }
        val gifs = mediaFiles.filter { it.mediaType == MediaType.ANIMATED_IMAGE }
        // 图片优先（快），视频排后（慢）
        // v1.12：视频单独低并发——视频截帧每任务峰值持有 2 张原尺寸帧（getFrameAtTime +
        // convertTo565 副本），4K 源 12 并发可达约 800MB，低端机 OOM 风险；
        // 视频并发限制为 2，图片仍用全量并发
        val workItems = images + videos
        AppLog.d("ThumbCache", "pregenerate: total=$total, images=${images.size}, videos=${videos.size}, gifs=${gifs.size}(skipped), concurrency=$concurrency, videoConcurrency=$VIDEO_CONCURRENCY")

        // GIF 不需要预生成静态缓存，直接计入已完成
        if (gifs.isNotEmpty()) {
            skipped.addAndGet(gifs.size)
            onProgress?.invoke(skipped.get(), total)
        }

        // 批量预过滤已缓存文件：autoRefresh 等场景常出现"全部已缓存"（skipped=total, generated=0），
        // 旧实现仍走完整 coroutineScope + chunked(concurrency) + async 调度，每个文件一次 isThumbnailCached。
        // 此处先批量过滤，全量缓存时直接返回跳过并发池调度开销；部分缓存时只对未缓存文件走并发池。
        val uncached = workItems.filterNot { isThumbnailCached(context, it.recordKey) }
        val preSkipped = workItems.size - uncached.size
        if (preSkipped > 0) {
            skipped.addAndGet(preSkipped)
        }

        if (uncached.isEmpty()) {
            // 全量缓存命中：无需启动并发池，直接完成
            onProgress?.invoke(total, total)
            AppLog.d("ThumbCache", "pregenerateThumbnails: done, $total/$total processed (skipped=${skipped.get()}, generated=0, failed=0) [全量缓存命中，跳过并发池]")
            return@withContext
        }

        var count = skipped.get()
        // v1.12：图片与视频分离并发——图片全量并发（解码快、内存小），视频低并发
        // （截帧峰值内存大）。旧实现共享并发池，视频任务混入全量并发导致 4K 源
        // 批量预生成时原生堆峰值过高。
        val uncachedImages = uncached.filter { it.mediaType == MediaType.IMAGE }
        val uncachedVideos = uncached.filter { it.mediaType == MediaType.VIDEO }

        suspend fun processBatch(media: MediaFileEntity) {
            try {
                // 滚动暂停：用户滑动列表时预生成循环等待让出 IO（delay 挂起协程不占线程），
                // 避免与滚动触发的按需解码争抢磁盘导致掉帧；停止滚动后自动恢复
                while (scrollPaused) delay(100)
                // 二次检查：预过滤与实际解码间可能被其他线程预生成
                if (isThumbnailCached(context, media.recordKey)) {
                    skipped.incrementAndGet()
                    return
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
                // 取消必须透传：吞掉会让 runPool 的 async 任务无法随外层取消及时结束
                if (e is CancellationException) throw e
                failed.incrementAndGet()
                if (failed.get() <= 3) {
                    AppLog.e("ThumbCache", "thumbnail exception: ${media.fileName}", e)
                }
            }
        }

        suspend fun runPool(items: List<MediaFileEntity>, poolConcurrency: Int) {
            coroutineScope {
                items.chunked(poolConcurrency).forEach { batch ->
                    batch.map { media -> async { processBatch(media) } }.awaitAll()
                    count += batch.size
                    if (count % 200 == 0 || count >= total) {
                        AppLog.d("ThumbCache", "pregenerate: progress $count/$total (skipped=${skipped.get()}, generated=${generated.get()}, failed=${failed.get()})")
                    }
                    onProgress?.invoke(count, total)
                }
            }
        }

        runPool(uncachedImages, concurrency)
        runPool(uncachedVideos, VIDEO_CONCURRENCY)

        AppLog.d("ThumbCache", "pregenerateThumbnails: done, $total/$total processed (skipped=${skipped.get()}, generated=${generated.get()}, failed=${failed.get()})")
    }

    // ===== 缓存管理 =====

    /**
     * 孤儿缓存最小存活时长：仅清理写入超过该时长的孤儿文件。
     * 防护：validRecordKeys 是启动时 DB 快照，若 prune 遍历期间有其他线程
     * （adapter decodeJob / 用户触发扫描的预生成）并发写入新缓存，刚写入的文件
     * 不在快照内会被误删——限制只删「旧孤儿」后，新写入的缓存（mtime 新）自动跳过，
     * 下次启动再按新快照对账。真孤儿（删除文件/扫描源残留）删除路径已同步清理
     * （deleteMediaAndRefs → MediaCacheCleaner），此处 24h 延迟兜底无实际影响。
     */
    private const val ORPHAN_MIN_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * 清理孤儿缩略图缓存：删除 recordKey 不在 [validRecordKeys] 中、且写入超过 24 小时的缓存文件。
     *
     * v1.16：替代原 enforceCacheLimit（按 5000 文件数淘汰最旧）——旧逻辑在库文件 > 5000
     * 时，只要有一个未缓存文件触发预生成，就会按 lastModified 淘汰一批**有效**缓存砍回 5000，
     * 下次启动这些文件 isThumbnailCached=false 又被重新解码生成，形成稳态抖动：
     * 每次打开软件都会重新缓存一部分缩略图。改为孤儿对账后，有效文件的缓存
     * 永不因数量上限被删；缓存目录规模与库文件数成正比，随删除文件同步清理
     * （deleteMediaAndRefs → MediaCacheCleaner）+ 此处启动对账保持不残留孤儿。
     *
     * 调用时机：启动预生成前传入**全库** recordKey 集合（ViewModel init），
     * 不可传入局部批次集合（会把批次外的有效缓存误删）。
     */
    fun pruneOrphanThumbnails(context: Context, validRecordKeys: Set<String>) {
        try {
            val validHashes = validRecordKeys.mapTo(mutableSetOf()) { ThumbnailCacheKeys.hashKey(it) }
            val dir = cacheDir(context)
            val files = dir.listFiles() ?: return
            // 并发写防护：只清理写入超过 24h 的孤儿，避开并发预生成/adapter 刚写入的新缓存
            val cutoff = System.currentTimeMillis() - ORPHAN_MIN_AGE_MS
            var removed = 0
            for (file in files) {
                val isOldOrphan = file.isFile && file.lastModified() <= cutoff
                if (isOldOrphan && ThumbnailCacheKeys.hashFromFileName(file.name) !in validHashes && file.delete()) {
                    removed++
                }
            }
            if (removed > 0) {
                AppLog.d("ThumbCache", "pruneOrphanThumbnails: removed $removed orphan cache files (valid=${validRecordKeys.size})")
            }
        } catch (e: Exception) {
            AppLog.d("ThumbCache", "pruneOrphanThumbnails failed: ${e.message}")
        }
    }

    /**
     * 清除指定 recordKey 的缓存
     * 删除媒体文件时同步调用（deleteMediaAndRefs → MediaCacheCleaner），
     * 保证已删除文件的缓存不残留
     */
    fun removeCache(context: Context, recordKey: String) {
        thumbFile(context, recordKey).delete()
    }

    /**
     * 清除视频缩略图缓存（用于黑帧检测版本升级后重新生成）
     * 只删除对应视频的缩略图缓存文件
     */
    fun clearVideoThumbnails(context: Context, videoRecordKeys: List<String>) {
        videoRecordKeys.forEach { key ->
            thumbFile(context, key).delete()
        }
        AppLog.d("ThumbCache", "clearVideoThumbnails: cleared ${videoRecordKeys.size} video thumbnails")
    }
}
