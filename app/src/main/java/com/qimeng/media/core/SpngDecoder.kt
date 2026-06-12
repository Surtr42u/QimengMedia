package com.qimeng.media.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.nio.ByteBuffer

/**
 * libspng PNG 解码器（JNI 桥接）。
 *
 * 通过 libspng（C 库，ARM NEON 优化）解码 PNG 图片，
 * 比系统 BitmapFactory（libpng）快 2-3 倍。
 *
 * 仅用于详情页大图 PNG 解码加速，不影响其他图片加载流程。
 * 解码失败时自动回退到 BitmapFactory，保证功能不受影响。
 *
 * 解码流程（单次 fd 打开）：
 * 1. 打开 ParcelFileDescriptor
 * 2. nativeGetPngInfo(fd) → 获取宽高（C 层 lseek 回到文件头）
 * 3. ByteBuffer.allocateDirect(width * height * 4) → 预分配像素内存
 * 4. nativeDecodePngToBuffer(fd, buffer) → libspng 直接写入 buffer
 * 5. Bitmap.createBitmap() + copyPixelsFromBuffer() → 生成 Bitmap
 * 6. 关闭 ParcelFileDescriptor
 *
 * 安全保障：
 * - JNI 方法失败时返回 null/false，Kotlin 端回退到 BitmapFactory
 * - ParcelFileDescriptor 在 finally 中关闭，不会泄漏
 * - 超大图（>256MB 像素数据）拒绝解码，防止 OOM
 */
object SpngDecoder {

    private const val TAG = "SpngDecoder"

    /** JNI 库是否已加载成功 */
    var isAvailable = false
        private set

    init {
        try {
            System.loadLibrary("spng_jni")
            isAvailable = true
            AppLog.d(TAG, "libspng JNI 库加载成功")
        } catch (e: UnsatisfiedLinkError) {
            isAvailable = false
            AppLog.e(TAG, "libspng JNI 库加载失败，PNG 将回退到 BitmapFactory", e)
        }
    }

    /**
     * 使用 libspng 解码 PNG 图片。
     *
     * @param context 上下文
     * @param uri 图片 URI
     * @return 解码后的 Bitmap，失败返回 null（调用方应回退到 BitmapFactory）
     */
    fun decodePng(context: Context, uri: Uri): Bitmap? {
        if (!isAvailable) return null

        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        try {
            val fd = pfd.fd

            // 第一步：获取 PNG 信息（宽高）
            // C 层 nativeGetPngInfo 会在返回前 lseek 回到文件头
            val info = nativeGetPngInfo(fd)
            if (info == null) return null

            val width = info[0]
            val height = info[1]

            if (width <= 0 || height <= 0 || width > 65536 || height > 65536) {
                AppLog.w(TAG, "PNG 尺寸异常: ${width}x${height}，回退到 BitmapFactory")
                return null
            }

            // 防止 OOM：估算内存占用，超过 256MB 拒绝解码
            val estimatedBytes = width.toLong() * height * 4
            if (estimatedBytes > 256L * 1024 * 1024) {
                AppLog.w(TAG, "PNG 过大 (${estimatedBytes / 1024 / 1024}MB)，回退到 BitmapFactory")
                return null
            }

            // 第二步：分配 DirectByteBuffer，libspng 直接写入
            val buffer = ByteBuffer.allocateDirect(width * height * 4)

            // 第三步：解码到 buffer（fd 已被 lseek 回到文件头，无需重新打开）
            val success = nativeDecodePngToBuffer(fd, buffer)
            if (!success) {
                AppLog.w(TAG, "libspng 解码失败，回退到 BitmapFactory")
                return null
            }

            // 第四步：从 buffer 创建 Bitmap
            buffer.rewind()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap

        } catch (e: Exception) {
            AppLog.w(TAG, "libspng 解码异常，回退到 BitmapFactory", e)
            return null
        } finally {
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    // ===== JNI native 方法 =====

    /**
     * 获取 PNG 图片信息。
     * C 层会在返回前通过 lseek 将 fd 回到文件头，供后续 nativeDecodePngToBuffer 使用。
     * @param fd 文件描述符（ParcelFileDescriptor.fd）
     * @return int[3] { width, height, stride }，失败返回 null
     */
    private external fun nativeGetPngInfo(fd: Int): IntArray?

    /**
     * 解码 PNG 到 DirectByteBuffer。
     * @param fd 文件描述符（应已被 nativeGetPngInfo lseek 回到文件头）
     * @param buffer 预分配的 DirectByteBuffer（大小 >= width * height * 4）
     * @return 成功返回 true，失败返回 false
     */
    private external fun nativeDecodePngToBuffer(fd: Int, buffer: ByteBuffer): Boolean
}
