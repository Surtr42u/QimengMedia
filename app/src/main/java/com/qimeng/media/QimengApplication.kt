package com.qimeng.media

import android.app.Application
import android.content.ComponentCallbacks2
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
import coil3.SingletonImageLoader
import okio.Path.Companion.toOkioPath
import com.qimeng.media.core.AppContainer
import com.qimeng.media.core.AppLog
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

    override fun onCreate() {
        super.onCreate()
        // 初始化文件日志（用于 logcat 不可读的设备）
        AppLog.init(this)

        // 全局异常处理：防止未捕获异常导致闪退
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("QimengMedia", "Uncaught exception on ${thread.name}", throwable)
        }

        // 初始化 MediaStoreObserver
        mediaStoreObserver = MediaStoreObserver(
            context = this,
            repository = appContainer.localMediaRepository,
            mediaStoreScanner = appContainer.mediaStoreScanner,
            scope = applicationScope
        )

        val imageLoader = ImageLoader.Builder(this)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
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
}
