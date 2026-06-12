package com.qimeng.media.core

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.core.net.toUri
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 缩略图加载工具，提供三种策略按优先级使用：
 *
 * 1. ContentResolver.loadThumbnail（最快，30-100ms）
 *    - 仅支持 MediaStore URI（content://media/external/...）
 *    - 直接读取系统预生成的缩略图缓存，零 CPU 解码开销
 *    - 内存占用极低：系统缓存管理，不占用 App 堆内存
 *
 * 2. MediaMetadataRetriever.getEmbeddedPicture（较快，读取视频内嵌封面）
 *    - 适用于有内嵌 cover art 的视频文件（如 MP4 with cover）
 *    - 比 getFrameAtTime 快得多，不需要解码视频帧
 *    - 仅对视频文件有效
 *
 * 3. 回退到 Coil VideoFrameDecoder（最慢，需要解码视频帧）
 *    - 由 MediaThumbnailAdapter 中的 Coil 加载处理
 *    - videoFrameMillis(0) 取首帧
 */
object ThumbnailLoader {
    private const val MAX_THUMB_WIDTH = 320
    /** 视频详情帧提取时间：3秒（微秒单位） */
    const val VIDEO_DETAIL_FRAME_US = 3_000_000L
    /** 视频缩略图黑帧检测递进时间点（毫秒单位） */
    private val FRAME_POSITIONS_MS = longArrayOf(0, 1_000, 2_000, 3_000, 5_000, 10_000, 20_000, 30_000)

    /** 判断 URI 是否为 MediaStore 格式（支持 loadThumbnail） */
    fun isMediaStoreUri(uriString: String): Boolean {
        return uriString.startsWith("content://media/")
    }

    /**
     * 使用 ContentResolver.loadThumbnail 加载缩略图（仅 MediaStore URI）
     * 系统级缓存，内存占用极低，速度最快
     */
    suspend fun loadSystemThumbnail(
        context: Context,
        uriString: String,
        width: Int = 480,
        height: Int = 270
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!isMediaStoreUri(uriString)) return@withContext null
        try {
            val uri = uriString.toUri()
            context.contentResolver.loadThumbnail(uri, Size(width, height), null)
        } catch (e: Exception) {
            AppLog.d("Thumb", "loadSystemThumbnail failed: ${e.message}")
            null
        }
    }

    /**
     * 读取视频内嵌封面（embedded cover art）
     * 优先级高于截帧，因为不需要解码视频流
     */
    suspend fun loadEmbeddedCover(
        context: Context,
        uriString: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = uriString.toUri()
            val pfd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            if (pfd != null) {
                retriever.setDataSource(pfd.fileDescriptor)
                pfd.close()
            } else {
                retriever.setDataSource(context, uri)
            }
            val coverBytes = retriever.embeddedPicture
            if (coverBytes != null) {
                val raw = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
                // 转为 RGB_565 减少内存占用（缩略图不需要透明度）
                raw?.let { convertTo565(it) }
            } else {
                null
            }
        } catch (e: Exception) {
            AppLog.d("Thumb", "loadEmbeddedCover failed: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { /* release() 失败无害，忽略 */ }
        }
    }

    /**
     * 获取视频缩略图：优先内嵌封面 → 回退首帧截取
     */
    suspend fun loadVideoThumbnail(
        context: Context,
        uriString: String,
        width: Int = 480,
        height: Int = 270
    ): Bitmap? = withContext(Dispatchers.IO) {
        // 策略1：MediaStore URI 用系统缩略图（最快）
        val systemThumb = loadSystemThumbnail(context, uriString, width, height)
        if (systemThumb != null) return@withContext systemThumb

        // 策略2：尝试读取内嵌封面
        val embeddedCover = loadEmbeddedCover(context, uriString)
        if (embeddedCover != null) return@withContext embeddedCover

        // 策略3：回退到首帧截取
        loadFirstFrame(context, uriString)
    }

    /**
     * 截取视频帧，自动跳过纯黑帧
     * 固定时间策略：0ms→1s→2s→3s→5s，找到非纯黑帧为止
     * 优化：复用同一个 MediaMetadataRetriever，避免重复创建开销
     *
     * 为什么不用帧率跳帧？因为 getFrameAtTime(OPTION_CLOSEST_SYNC) 只取关键帧(I帧)，
     * I帧间隔通常1-5秒，"跳3帧"在 CLOSEST_SYNC 下无实际意义，API直接跳到最近I帧。
     * 固定时间更简单可靠，不依赖 METADATA_KEY_CAPTURE_FRAMERATE（经常返回null）。
     *
     * 性能考虑：整个循环复用同一个 MediaMetadataRetriever 实例，
     * 避免每次 getFrameAtTime 都重新 setDataSource（耗时约50-100ms），
     * 仅在方法结束时 release 一次
     */
    suspend fun loadFirstFrame(
        context: Context,
        uriString: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = uriString.toUri()
            val pfd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            if (pfd != null) {
                retriever.setDataSource(pfd.fileDescriptor)
                pfd.close()
            } else {
                retriever.setDataSource(context, uri)
            }

            // 固定时间策略 0ms→1s→2s→3s→5s→10s→20s→30s 递进原因：
            // 0ms取首帧（最快），但很多视频开头是黑屏过渡（淡入、logo淡出）；
            // 逐步后移，覆盖常见黑屏过渡时长；
            // 如果前5个位置都是黑帧，继续尝试10s/20s/30s，避免返回黑帧缩略图
            val framePositions = FRAME_POSITIONS_MS
            var lastFrame: Bitmap? = null
            for (positionMs in framePositions) {
                try {
                    val frame = retriever.getFrameAtTime(
                        positionMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    if (frame != null && !isBlackFrame(frame)) {
                        AppLog.d("Thumb", "loadFirstFrame: non-black at ${positionMs}ms")
                        return@withContext convertTo565(frame)
                    }
                    if (frame != null && lastFrame == null) {
                        lastFrame = frame
                    } else {
                        frame?.recycle()
                    }
                } catch (e: Exception) {
                    AppLog.d("Thumb", "loadFirstFrame at ${positionMs}ms failed: ${e.message}")
                }
            }
            // 所有位置都是纯黑或失败，返回 0ms 的帧（有总比没有好）
            if (lastFrame != null) return@withContext convertTo565(lastFrame)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            AppLog.d("Thumb", "loadFirstFrame failed: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { /* release() 失败无害，忽略 */ }
        }
    }

    /**
     * 检测 Bitmap 是否为纯黑帧（采样检测，避免遍历全部像素）
     * 采样 100 个像素点，如果所有采样点的亮度都低于阈值则判定为纯黑
     *
     * 阈值15的依据：正常视频帧即使暗场景也有环境光/噪点，亮度通常>20；
     * 纯黑帧的像素值接近0，阈值15留出余量排除极暗但非纯黑的帧（如夜景），
     * 同时确保真正的黑帧（亮度0-5）被可靠检测
     */
    private fun isBlackFrame(bitmap: Bitmap, threshold: Int = 15, sampleCount: Int = 100): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true

        val stepX = (w / Math.sqrt(sampleCount.toDouble())).toInt().coerceAtLeast(1)
        val stepY = (h / Math.sqrt(sampleCount.toDouble())).toInt().coerceAtLeast(1)

        for (x in 0 until w step stepX) {
            for (y in 0 until h step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                // 亮度计算
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (luminance > threshold) return false
            }
        }
        return true
    }

    /**
     * 获取图片缩略图：MediaStore URI 用系统缩略图，否则降采样解码
     */
    suspend fun loadImageThumbnail(
        context: Context,
        uriString: String,
        width: Int = 480,
        height: Int = 270
    ): Bitmap? = withContext(Dispatchers.IO) {
        // 策略1：MediaStore URI 用系统缩略图（最快）
        val systemThumb = loadSystemThumbnail(context, uriString, width, height)
        if (systemThumb != null) return@withContext systemThumb

        // 策略2：降采样解码
        val sampled = decodeSampled(context, uriString.toUri(), MAX_THUMB_WIDTH)
        if (sampled != null) return@withContext sampled

        // 策略3：ImageDecoder 回退（处理 GIF 等 BitmapFactory 不支持的格式）
        decodeWithImageDecoder(context, uriString.toUri(), width, height)
    }

    private fun decodeSampled(context: Context, uri: Uri, targetWidth: Int): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)

                val scaleFactor = (options.outWidth / targetWidth).coerceAtLeast(1)
                val sampleSize = Integer.highestOneBit(scaleFactor).coerceAtLeast(1)

                context.contentResolver.openInputStream(uri)?.use { secondInput ->
                    BitmapFactory.decodeStream(secondInput, null, BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })
                }
            }
        }.getOrNull()
    }

    /**
     * 使用 ImageDecoder 解码图片（API 28+）
     * 作为 BitmapFactory 的回退方案，处理 GIF 等 BitmapFactory 不支持的格式
     */
    private fun decodeWithImageDecoder(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        return runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            convertTo565(bitmap)
        }.onFailure {
            AppLog.d("Thumb", "decodeWithImageDecoder failed for $uri: ${it.message}")
        }.getOrNull()
    }

    /**
     * 将 Bitmap 转为 RGB_565 格式，减少内存占用（缩略图不需要透明度）
     * 如果已经是 RGB_565 则直接返回
     */
    private fun convertTo565(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.RGB_565) return bitmap
        val converted = bitmap.copy(Bitmap.Config.RGB_565, false)
        bitmap.recycle()
        return converted ?: bitmap
    }
}
