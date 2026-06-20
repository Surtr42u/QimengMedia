package com.qimeng.media.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil3.asImage
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.size.Size
import coil3.gif.AnimatedImageDecoder
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * 大图解码优化器。
 *
 * 解码优先级（逐级回退，保证功能不受影响）：
 * 0. Coil 内存缓存命中 → 直接返回（0ms，预加载已解码的情况）
 * 1. PNG 图片 → libspng（ARM NEON 优化，比系统 libpng 快 2-3 倍）
 * 2. 所有图片 → BitmapFactory.decodeFileDescriptor（跳过 InputStream 中间层）
 * 3. 回退 → BitmapFactory.decodeStream
 * 4. 最终回退 → Coil 标准流程（在 MediaDetailFragment 中处理）
 *
 * 关键设计：先查缓存再解码，预加载的图片不会被重复解码。
 *
 * 技术说明：
 * - Android 5.0+ 的 BitmapFactory 底层已使用 libjpeg-turbo（通过 Skia），
 *   JPEG 解码无需额外 JNI 集成
 * - PNG 解码使用 libspng（v0.7.4，MIT 协议），使用 NDK 自带 zlib，
 *   ARM NEON 优化在 armeabi-v7a/arm64-v8a 上自动启用
 * - libspng 解码失败时静默回退到 BitmapFactory，不影响用户体验
 */
object LargeImageDecoder {

    private const val TAG = "LargeImageDecoder"

    // 大图并发解码限制：同时最多 2 张大图解码，防止内存峰值
    private val largeDecodeSemaphore = Semaphore(2)

    /**
     * 解码当前显示的图片。
     *
     * 最优路径：先查 Coil 内存缓存（预加载可能已放入），命中则 0ms 返回；
     * 未命中再走文件解码，解码成功后写入缓存供后续访问零延迟。
     *
     * @param context 上下文
     * @param uri 图片 URI
     * @param recordKey 缓存键
     * @param isGif 是否为 GIF 动图
     * @param isPng 是否为 PNG 图片（根据文件扩展名判断）
     * @return 解码后的 Bitmap，失败返回 null（调用方应回退到 Coil）
     */
    suspend fun decodeCurrentImage(
        context: Context,
        uri: Uri,
        recordKey: String,
        isGif: Boolean,
        isPng: Boolean = false
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (isGif) return@withContext null // GIF 交给 Coil AnimatedImageDecoder 处理

        // 第零优先级：检查 Coil 内存缓存（预加载可能已放入）
        // 缓存命中时不直接返回 Bitmap 对象，因为 Coil 缓存驱逐时会 recycle bitmap，
        // 而 setImageBitmap 持有的 drawable 仍引用已回收的 bitmap，绘制时崩溃
        // （IllegalArgumentException: width and height must be > 0）。
        // 返回 null 让调用方走 Coil load 路径，Coil 会命中内存缓存零延迟完成，
        // 且正确管理 bitmap 生命周期。
        if (isInCoilMemoryCache(context, recordKey)) {
            AppLog.d(TAG, "内存缓存命中: $recordKey")
            return@withContext null
        }

        // 缓存未命中，从文件解码
        largeDecodeSemaphore.acquire()
        try {
            // PNG 优先尝试 libspng（比系统 libpng 快 2-3 倍）
            if (isPng && SpngDecoder.isAvailable) {
                val bitmap = SpngDecoder.decodePng(context, uri)
                if (bitmap != null) {
                    AppLog.d(TAG, "解码路径=libspng key=$recordKey 尺寸=${bitmap.width}x${bitmap.height}")
                    return@withContext bitmap
                }
                AppLog.d(TAG, "libspng失败回退BitmapFactory key=$recordKey")
            } else if (isPng) {
                AppLog.d(TAG, "PNG但libspng不可用，走BitmapFactory key=$recordKey")
            }

            // 通用路径：decodeFileDescriptor（比 Coil 的 decodeStream 更快）
            val fdBitmap = decodeWithFileDescriptor(context, uri)
            if (fdBitmap != null) {
                AppLog.d(TAG, "解码路径=decodeFileDescriptor key=$recordKey 尺寸=${fdBitmap.width}x${fdBitmap.height}")
                return@withContext fdBitmap
            }
            AppLog.d(TAG, "decodeFileDescriptor失败，回退decodeStream key=$recordKey")
            null
        } catch (e: Exception) {
            // 回退到 BitmapFactory.decodeStream
            AppLog.d(TAG, "解码异常回退decodeStream key=$recordKey err=${e.javaClass.simpleName}")
            try {
                val streamBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = false
                    })
                }
                if (streamBitmap != null) {
                    AppLog.d(TAG, "解码路径=decodeStream(异常回退) key=$recordKey 尺寸=${streamBitmap.width}x${streamBitmap.height}")
                }
                streamBitmap
            } catch (e2: Exception) {
                AppLog.w(TAG, "全部解码路径失败 key=$recordKey err=${e2.javaClass.simpleName}")
                null
            }
        } finally {
            largeDecodeSemaphore.release()
        }
    }?.also { bitmap ->
        // 解码成功，写入 Coil 内存缓存
        cacheBitmapInCoil(context, recordKey, bitmap)
    }

    /**
     * 检查 Coil 内存缓存中是否存在指定 recordKey 的条目。
     * 不直接取出 Bitmap 对象，避免缓存驱逐时 bitmap 被 recycle 导致崩溃。
     */
    private fun isInCoilMemoryCache(context: Context, recordKey: String): Boolean {
        return try {
            val loader = coil3.SingletonImageLoader.get(context)
            val cache = loader.memoryCache ?: return false
            cache[MemoryCache.Key(recordKey)] != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 使用 AssetFileDescriptor + decodeFileDescriptor 解码。
     * 比 decodeStream 更快，因为直接走文件描述符 mmap 读取，
     * 跳过 InputStream 中间层的拷贝开销。
     */
    private fun decodeWithFileDescriptor(context: Context, uri: Uri): Bitmap? {
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
        try {
            val fd = afd.fileDescriptor ?: return null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            }
            return BitmapFactory.decodeFileDescriptor(fd, null, options)
        } finally {
            try { afd.close() } catch (_: Exception) {}
        }
    }

    /**
     * 将解码后的 Bitmap 写入 Coil 内存缓存。
     * 后续通过 Coil 加载同一 recordKey 时可直接命中缓存，零延迟。
     */
    private fun cacheBitmapInCoil(context: Context, recordKey: String, bitmap: Bitmap) {
        try {
            val loader = coil3.SingletonImageLoader.get(context)
            val cache = loader.memoryCache ?: return
            val drawable = BitmapDrawable(context.resources, bitmap)
            val image = drawable.asImage()
            val key = MemoryCache.Key(recordKey)
            // 检查缓存是否已存在（可能预加载已放入），避免覆盖
            if (cache[key] != null) return
            cache[key] = MemoryCache.Value(image)
        } catch (_: Exception) {
            // 缓存写入失败不影响显示
        }
    }

    /**
     * 构建预加载 ImageRequest（供 preloadAround 使用）。
     * 使用 Coil 标准流程，自动利用内存/磁盘缓存。
     * 视频帧取首帧（0ms）而非 3 秒帧，避免 seek 开销（详见 GUIDE_DEBUG.md 性能约束）。
     */
    fun buildPreloadRequest(
        context: Context,
        uri: Uri,
        recordKey: String,
        isVideo: Boolean,
        isGif: Boolean
    ): ImageRequest {
        return ImageRequest.Builder(context)
            .data(uri)
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .memoryCacheKey(recordKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .apply {
                if (isVideo) {
                    decoderFactory(VideoFrameDecoder.Factory())
                    videoFrameMillis(0)
                }
                if (isGif) {
                    bitmapConfig(Bitmap.Config.ARGB_8888)
                    decoderFactory(AnimatedImageDecoder.Factory())
                }
            }
            .build()
    }
}
