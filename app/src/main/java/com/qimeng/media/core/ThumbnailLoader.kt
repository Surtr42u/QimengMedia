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
 * 1. MediaMetadataRetriever.getEmbeddedPicture（有内嵌封面时最优）
 *    - 适用于有内嵌 cover art 的视频文件（如 MP4 with cover）
 *    - 比 getFrameAtTime 快得多，不需要解码视频帧，画面质量最可控
 *    - 仅对视频文件有效；注意实际图库内嵌封面覆盖率很低（参考库 376 个视频
 *      实测 0 个带封面），主流视频无封面，此策略"有就用"
 *
 * 2. 截取"代表帧"（主路径，v1.18 对齐 Windows 资源管理器缩略图体验）
 *    - 取视频时长 35% 处帧（对齐 Icaros 默认偏移），黑/白帧自动跳过向两侧扩散
 *    - 电脑缩略图友好的原因：Windows 用系统缩略图管道 + Icaros 这类 Shell 扩展
 *      按"偏移百分比"抓中段画面，而不是首帧——视频开头多为黑屏/片头过渡
 *
 * 3. ContentResolver.loadThumbnail（兜底，30-100ms）
 *    - 仅支持 MediaStore URI（content://media/external/...）
 *    - 直接读取系统预生成的缩略图缓存，零 CPU 解码开销，内存占用极低
 *    - 系统缩略图帧位置不可控（可能黑帧/构图差），故只作兜底
 */
object ThumbnailLoader {
    private const val MAX_THUMB_WIDTH = 320
    /** 代表帧递进偏移比例：主选 35%（对齐 Icaros 默认 offset=35%），黑/白帧不合格按此序列向两侧扩散 */
    private val FRAME_RATIOS = doubleArrayOf(0.35, 0.25, 0.45, 0.15, 0.55, 0.05, 0.65, 0.0)

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
            // v1.12：pfd 用 use{} 包裹——旧实现 setDataSource 抛异常时 pfd 不关闭泄漏 fd
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: retriever.setDataSource(context, uri)
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
     * 获取视频缩略图：优先级 内嵌封面 → 35% 代表帧（黑白检测）→ 系统缩略图兜底
     * v1.18 调整顺序（原系统缩略图第一）：系统缩略图帧位置不受控，先于代表帧
     * 会出现"能用系统图就不切代表帧"的结果，与电脑缩略图体验不符
     */
    suspend fun loadVideoThumbnail(
        context: Context,
        uriString: String,
        width: Int = 480,
        height: Int = 270
    ): Bitmap? = withContext(Dispatchers.IO) {
        // 策略1：内嵌封面（Icaros UseCoverArt 同款，有封面直接用）
        val embeddedCover = loadEmbeddedCover(context, uriString)
        if (embeddedCover != null) return@withContext embeddedCover

        // 策略2：35% 代表帧（Icaros 默认 offset，黑/白帧自动跳过）
        val representativeFrame = loadRepresentativeFrame(context, uriString)
        if (representativeFrame != null) return@withContext representativeFrame

        // 策略3：系统缩略图兜底（仅 MediaStore URI 支持）
        loadSystemThumbnail(context, uriString, width, height)
    }

    /**
     * 截取视频"代表帧"，自动跳过纯黑/纯白帧。
     * 位置策略（v1.18 对齐 Icaros）：主位置 = 视频时长 × 35%，不合格按比例序列
     * 向两侧扩散（35%→25%→45%→…），全部失败回退首个取到的帧（有总比没有好）。
     *
     * 为什么不用帧率跳帧？因为 getFrameAtTime(OPTION_CLOSEST_SYNC) 只取关键帧(I帧)，
     * I帧间隔通常1-5秒，"跳3帧"在 CLOSEST_SYNC 下无实际意义，API直接跳到最近I帧。
     * 比例偏移更简单可靠，且不依赖 METADATA_KEY_CAPTURE_FRAMERATE（经常返回null）；
     * 时长本身通过 METADATA_KEY_DURATION 获取（稳定返回）。
     *
     * 性能考虑：整个循环复用同一个 MediaMetadataRetriever 实例，
     * 避免每次 getFrameAtTime 都重新 setDataSource（耗时约50-100ms），
     * 仅在方法结束时 release 一次
     */
    suspend fun loadRepresentativeFrame(
        context: Context,
        uriString: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = uriString.toUri()
            // v1.12：pfd 用 use{} 包裹——旧实现 setDataSource 抛异常时 pfd 不关闭泄漏 fd
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: retriever.setDataSource(context, uri)

            // 时长（毫秒）用于按比例计算取帧位置；取不到时长时只能退化 0ms 帧
            // （不必按比例序列重复取同一帧 8 次）
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0) {
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) return@withContext convertTo565(frame)
                return@withContext null
            }

            var lastFrame: Bitmap? = null
            for (ratio in FRAME_RATIOS) {
                try {
                    val positionMs = (durationMs * ratio).toLong()
                    val frame = retriever.getFrameAtTime(
                        positionMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    if (frame != null && !isExtremeFrame(frame)) {
                        val percent = (ratio * 100).toInt()
                        AppLog.d("Thumb", "loadRepresentativeFrame: good frame at ${percent}% (${positionMs}ms)")
                        // v1.15：找到合格帧时先回收暂存的 lastFrame（不合格帧），避免泄漏一张 Bitmap
                        lastFrame?.recycle()
                        return@withContext convertTo565(frame)
                    }
                    if (frame != null && lastFrame == null) {
                        lastFrame = frame
                    } else {
                        frame?.recycle()
                    }
                } catch (e: Exception) {
                    AppLog.d("Thumb", "loadRepresentativeFrame at ${(ratio * 100).toInt()}% failed: ${e.message}")
                }
            }
            // 所有位置都是黑/白帧或失败，返回第一个取到的帧（有总比没有好）
            if (lastFrame != null) return@withContext convertTo565(lastFrame)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            AppLog.d("Thumb", "loadRepresentativeFrame failed: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { /* release() 失败无害，忽略 */ }
        }
    }

    /**
     * 检测 Bitmap 是否为"极端帧"（纯黑或纯白，均不适合当缩略图）。
     * 采样 100 个像素点，全部亮度低于 [minLuma] 判定纯黑，全部高于 [maxLuma] 判定纯白。
     *
     * 阈值依据：正常视频帧即使暗场景也有环境光/噪点，亮度通常>20（纯黑帧≈0）；
     * 对应地正常画面总有暗部，纯白帧≈255。minLuma=15 / maxLuma=240 留出余量排除
     * 极暗（夜景）与极亮（曝光过度）但非极端帧。对齐 Icaros 黑白帧检测思路
     * （Icaros 默认替换阈值 8%，检测"too dark or too light"帧并前移重找）。
     */
    private fun isExtremeFrame(bitmap: Bitmap, minLuma: Int = 15, maxLuma: Int = 240, sampleCount: Int = 100): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true

        val stepX = (w / Math.sqrt(sampleCount.toDouble())).toInt().coerceAtLeast(1)
        val stepY = (h / Math.sqrt(sampleCount.toDouble())).toInt().coerceAtLeast(1)

        var allDark = true
        var allBright = true
        for (x in 0 until w step stepX) {
            for (y in 0 until h step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                // 亮度计算
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (luminance >= minLuma) allDark = false
                if (luminance <= maxLuma) allBright = false
                // 已同时排除黑/白两种可能，提前结束
                if (!allDark && !allBright) return false
            }
        }
        return allDark || allBright
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
     *
     * v1.12：copy() 返回 null（OOM/分配失败）时不再 recycle 原图后再返回已回收的
     * bitmap——旧实现先 recycle 再 `return converted ?: bitmap`，调用方拿到已回收的
     * bitmap 继续 compress 会抛 IllegalStateException。改为转换失败直接返回原图。
     */
    private fun convertTo565(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.RGB_565) return bitmap
        val converted = bitmap.copy(Bitmap.Config.RGB_565, false)
        if (converted == null) {
            AppLog.w("Thumb", "convertTo565: copy 失败（内存不足），保留原图")
            return bitmap
        }
        bitmap.recycle()
        return converted
    }
}
