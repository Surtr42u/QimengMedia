package com.qimeng.media.core

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import kotlin.concurrent.thread

/**
 * GPU 纹理上限探测。
 *
 * 用于 [com.qimeng.media.ui.detail.ZoomImageView] 智能分层渲染判断：
 * 长边 <= GPU 纹理上限的图用 LAYER_TYPE_HARDWARE（GPU 直渲，4096 图从 ~50ms 降到 ~5ms），
 * 超限的图回退 LAYER_TYPE_SOFTWARE（保底，避免超 OpenGL 纹理限制渲染异常）。
 *
 * 实现原理：创建临时 EGL14 context → glGetIntegerv(GL_MAX_TEXTURE_SIZE) → 销毁 context。
 * minSdk=31 保证 EGL14/GLES31 全设备可用，无需额外依赖。
 *
 * 线程安全：探测在后台线程执行，结果缓存在 @Volatile 变量，后续调用直接返回缓存。
 * 首次调用若缓存未就绪会阻塞等待探测完成（探测本身很快，几十 ms）。
 */
object GpuInfo {
    private const val TAG = "GpuInfo"

    /** 探测失败时的保守默认值（与原 ZoomImageView 硬编码兜底一致） */
    private const val DEFAULT_MAX_TEXTURE_SIZE = 4096

    @Volatile private var cachedMaxTextureSize: Int = 0
    @Volatile private var probeStarted: Boolean = false
    private val probeLatch = java.util.concurrent.CountDownLatch(1)

    /**
     * 返回设备 GPU 最大纹理尺寸（正方形边长，如 4096 / 8192）。
     * 首次调用触发后台探测并阻塞等待结果；后续调用直接返回缓存。
     * 探测失败返回 [DEFAULT_MAX_TEXTURE_SIZE]。
     */
    fun maxTextureSize(context: Context): Int {
        ensureProbeStarted()
        probeLatch.await()
        return cachedMaxTextureSize
    }

    /**
     * 非阻塞版本：探测未完成时返回 [DEFAULT_MAX_TEXTURE_SIZE] 不等待。
     * 用于 ImageLoader 配置等不希望阻塞主线程的场景。
     * 后续实际加载图片时 maxTextureSize 会拿到探测后的真实值。
     */
    fun maxTextureSizeOrDefault(context: Context): Int {
        ensureProbeStarted()
        return if (cachedMaxTextureSize > 0) cachedMaxTextureSize else DEFAULT_MAX_TEXTURE_SIZE
    }

    private fun ensureProbeStarted() {
        if (!probeStarted) {
            synchronized(this) {
                if (!probeStarted) {
                    probeStarted = true
                    thread(start = true, name = "GpuInfo-Probe", isDaemon = true) {
                        val result = probeMaxTextureSize()
                        cachedMaxTextureSize = result
                        probeLatch.countDown()
                    }
                }
            }
        }
    }

    /** 在当前线程（应为后台线程）创建临时 EGL context 探测 GL_MAX_TEXTURE_SIZE */
    private fun probeMaxTextureSize(): Int {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display === EGL14.EGL_NO_DISPLAY) {
                AppLog.w(TAG, "GPU探测失败：eglGetDisplay 返回 NO_DISPLAY，回退默认$DEFAULT_MAX_TEXTURE_SIZE")
                return DEFAULT_MAX_TEXTURE_SIZE
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                AppLog.w(TAG, "GPU探测失败：eglInitialize 失败，回退默认$DEFAULT_MAX_TEXTURE_SIZE")
                return DEFAULT_MAX_TEXTURE_SIZE
            }

            // 请求 GLES2 config（GL_MAX_TEXTURE_SIZE 在 GLES2+ 均可查，用 GLES2 最通用）
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfig, 0)) {
                AppLog.w(TAG, "GPU探测失败：eglChooseConfig 失败，回退默认$DEFAULT_MAX_TEXTURE_SIZE")
                return DEFAULT_MAX_TEXTURE_SIZE
            }
            val config = configs[0]
                ?: run {
                    AppLog.w(TAG, "GPU探测失败：无匹配 EGLConfig，回退默认$DEFAULT_MAX_TEXTURE_SIZE")
                    return DEFAULT_MAX_TEXTURE_SIZE
                }

            // 创建 context（GLES2，足够查 GL_MAX_TEXTURE_SIZE）
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context === EGL14.EGL_NO_CONTEXT) {
                AppLog.w(TAG, "GPU探测失败：eglCreateContext 失败，回退默认$DEFAULT_MAX_TEXTURE_SIZE")
                return DEFAULT_MAX_TEXTURE_SIZE
            }

            // 创建 1x1 pbuffer surface 让 context current（查 GL 状态需 current context）
            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            if (surface === EGL14.EGL_NO_SURFACE) {
                AppLog.w(TAG, "GPU探测失败：eglCreatePbufferSurface 失败，回退默认$DEFAULT_MAX_TEXTURE_SIZE")
                return DEFAULT_MAX_TEXTURE_SIZE
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                AppLog.w(TAG, "GPU探测失败：eglMakeCurrent 失败，回退默认$DEFAULT_MAX_TEXTURE_SIZE")
                return DEFAULT_MAX_TEXTURE_SIZE
            }

            val sizeOut = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, sizeOut, 0)
            val size = sizeOut[0]
            if (size <= 0) {
                AppLog.w(TAG, "GPU探测失败：GL_MAX_TEXTURE_SIZE=$size 非法，回退默认$DEFAULT_MAX_TEXTURE_SIZE")
                return DEFAULT_MAX_TEXTURE_SIZE
            }
            AppLog.d(TAG, "GPU纹理上限=$size 设备=${Build.MODEL} GPU=${Build.HARDWARE}")
            return size
        } catch (e: Exception) {
            AppLog.w(TAG, "GPU探测异常，回退默认$DEFAULT_MAX_TEXTURE_SIZE err=${e.javaClass.simpleName}")
            return DEFAULT_MAX_TEXTURE_SIZE
        } catch (e: Error) {
            // EGL/GLES 可能抛 UnsatisfiedLinkError 等 Error，兜底
            AppLog.w(TAG, "GPU探测Error，回退默认$DEFAULT_MAX_TEXTURE_SIZE err=${e.javaClass.simpleName}")
            return DEFAULT_MAX_TEXTURE_SIZE
        } finally {
            if (surface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            if (display !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglTerminate(display)
            }
        }
    }
}
