package com.qimeng.media

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.video.VideoFrameDecoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.maxBitmapSize
import coil3.size.Size
import coil3.SingletonImageLoader
import okio.Path.Companion.toOkioPath
import com.qimeng.media.core.AppContainer
import com.qimeng.media.core.AppLog
import com.qimeng.media.core.AnrWatchdog
import com.qimeng.media.core.GpuInfo
import com.qimeng.media.scan.MediaStoreObserver
import com.qimeng.media.scan.MediaStoreScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class QimengApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var mediaStoreObserver: MediaStoreObserver
    private lateinit var anrWatchdog: AnrWatchdog

    override fun onCreate() {
        super.onCreate()
        // 初始化文件日志（用于 logcat 不可读的设备）
        AppLog.init(this)

        // v1.7：Debug 构建开启 StrictMode，早发现主线程 IO/网络违规
        if (isDebugBuild()) {
            enableStrictMode()
        }

        // v1.7：启动 ANR 监控（主线程阻塞 5 秒记录到 AppLog）
        anrWatchdog = AnrWatchdog()
        anrWatchdog.start()

        // 全局异常处理：记录日志后委托给系统默认处理器，避免吞掉异常导致主线程静默死亡（ANR）
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("QimengMedia", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 初始化 MediaStoreObserver
        mediaStoreObserver = MediaStoreObserver(
            context = this,
            repository = appContainer.localMediaRepository,
            mediaStoreScanner = appContainer.mediaStoreScanner,
            scope = applicationScope
        )

        // 放开 maxBitmapSize 到 GPU 纹理上限（默认 Coil 限制 4096，会让 8192 等超大图降采样，
        // 偏离"详情页始终显示原图"设计）。探测值来自 GpuInfo（实测 Adreno 750=16384）。
        // 探测失败回退 4096（与原默认一致，不更差）。启动时同步探测一次（几十 ms，可接受）。
        val gpuMax = GpuInfo.maxTextureSize()
        val imageLoader = ImageLoader.Builder(this)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .maxBitmapSize(Size(gpuMax, gpuMax))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(this@QimengApplication, 0.35)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache").toOkioPath())
                    .maxSizePercent(0.20)
                    .build()
            }
            .build()
        SingletonImageLoader.setSafe { imageLoader }

        // 监听 App 前后台切换，进入后台时触发全量同步
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // App 进入后台，触发全量同步（app数据/ + 个人偏好/）
                applicationScope.launch(Dispatchers.IO) {
                    try {
                        appContainer.autoSyncUseCase.triggerFullSync()
                    } catch (e: Exception) {
                        AppLog.e("QimengMedia", "后台同步失败", e)
                    }
                }
            }
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val cache = SingletonImageLoader.get(this).memoryCache ?: return
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // 系统内存极度紧张，移除3/4缓存条目（保留最近1/4，避免清空后全部重新加载）
                val keyList = cache.keys.toList()
                val removeCount = keyList.size * 3 / 4
                keyList.take(removeCount).forEach { cache.remove(it) }
                AppLog.d("QimengMedia", "onTrimMemory RUNNING_CRITICAL: 移除 ${removeCount}/${keyList.size} 缓存条目")
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // 系统内存紧张，移除一半缓存条目
                val keyList = cache.keys.toList()
                val removeCount = keyList.size / 2
                keyList.take(removeCount).forEach { cache.remove(it) }
                AppLog.d("QimengMedia", "onTrimMemory RUNNING_LOW: 移除 ${removeCount}/${keyList.size} 缓存条目")
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // UI 不可见时，移除非当前查看项的预渲染缓存
                val keyList = cache.keys.toList()
                // 保留最近 1 个条目（当前查看的），移除其余预渲染
                // cache.keys 按 LRU 顺序迭代（最久未访问→最近访问），dropLast(1) 保留最后一个（最近访问的）
                if (keyList.size > 1) {
                    val removeCount = keyList.size - 1
                    keyList.dropLast(1).forEach { cache.remove(it) }
                    AppLog.d("QimengMedia", "onTrimMemory UI_HIDDEN: 移除 ${removeCount} 预渲染缓存")
                }
            }
        }
    }

    /** 判断当前是否为 Debug 构建（通过 ApplicationInfo.FLAG_DEBUGGABLE，无需 BuildConfig） */
    private fun isDebugBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * v1.7：Debug 模式开启 StrictMode，检测主线程 IO/网络违规。
     * - 仅 Debug 构建生效，Release 不受影响
     * - 检测到违规时打印日志到 logcat（penaltyLog），不弹窗（penaltyDialog 干扰调试）
     * - 不使用 penaltyDeath() 避免调试时频繁崩溃
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
        AppLog.d("QimengMedia", "StrictMode enabled (Debug build)")
    }
}
