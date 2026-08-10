package com.qimeng.media.domain

import android.app.Application
import com.qimeng.media.core.AppLog
import com.qimeng.media.core.ThumbnailCache
import com.qimeng.media.data.db.entity.MediaFileEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 缩略图预生成 UseCase：负责后台预生成缩略图到本地文件缓存。
 * 使用队列机制：新请求排队等待当前任务完成后再执行，不会跳过。
 */
class ThumbnailUseCase(
    private val application: Application
) {
    // v1.15：@Volatile 保证 Main 线程读 / IO 线程 finally 写 false 的跨线程可见性，
    // 避免扫描完成回调与手动预生成并发时读到旧值同时启动两个预生成任务
    @Volatile
    private var isPregenerating = false

    private val _progress = MutableStateFlow<ThumbnailProgress>(ThumbnailProgress.Idle)
    val progress: StateFlow<ThumbnailProgress> = _progress.asStateFlow()

    /** 待处理的队列 */
    private val pendingQueue = ConcurrentLinkedQueue<PendingTask>()

    private data class PendingTask(
        val mediaFiles: List<MediaFileEntity>,
        val source: ThumbnailSource
    )

    /**
     * 后台预生成缩略图到本地文件缓存（ThumbnailCache）
     * 统一图片和视频策略：ThumbnailLoader 解码 → ThumbnailCache 保存为本地 WebP
     * 已缓存的文件自动跳过，只处理新增文件
     * 如果当前正在预生成，新请求会排队等待
     *
     * @param source 来源标识，用于UI区分常规/COS进度
     */
    fun pregenerateThumbnails(mediaFiles: List<MediaFileEntity>, coroutineScope: CoroutineScope, source: ThumbnailSource = ThumbnailSource.GENERAL) {
        if (mediaFiles.isEmpty()) {
            AppLog.d("Scan", "pregenerateThumbnails: empty list, skipped")
            return
        }
        if (isPregenerating) {
            AppLog.d("Scan", "pregenerateThumbnails: already running, queuing ${mediaFiles.size} files (${source.label})")
            pendingQueue.add(PendingTask(mediaFiles, source))
            return
        }
        startPregeneration(mediaFiles, coroutineScope, source)
    }

    private fun startPregeneration(mediaFiles: List<MediaFileEntity>, coroutineScope: CoroutineScope, source: ThumbnailSource) {
        isPregenerating = true
        _progress.value = ThumbnailProgress.Running(0, mediaFiles.size, source)
        AppLog.d("Scan", "pregenerateThumbnails: starting with ${mediaFiles.size} files (${source.label})")
        coroutineScope.launch(Dispatchers.IO) {
            try {
                ThumbnailCache.pregenerateThumbnails(application, mediaFiles) { done, total ->
                    _progress.value = ThumbnailProgress.Running(done, total, source)
                }
                _progress.value = ThumbnailProgress.Done(mediaFiles.size, source)
                AppLog.d("Scan", "pregenerateThumbnails: completed ${mediaFiles.size} files (${source.label})")
            } catch (e: CancellationException) {
                // v1.12：取消必须向上透传，不能吞掉（否则 finally 的排队任务也永远不会执行）
                throw e
            } catch (e: Exception) {
                _progress.value = ThumbnailProgress.Idle
                AppLog.e("Scan", "pregenerateThumbnails failed: ${e.message}", e)
            } finally {
                isPregenerating = false
                // 处理队列中的下一个请求
                val next = pendingQueue.poll()
                if (next != null) {
                    // v1.12：finally 内单独 try-catch——外层已取消时 withContext(Main) 会抛
                    // CancellationException，原实现无保护导致排队任务被吞、队列永不处理
                    try {
                        withContext(Dispatchers.Main) {
                            startPregeneration(next.mediaFiles, coroutineScope, next.source)
                        }
                    } catch (c: CancellationException) {
                        AppLog.d("Scan", "pregenerateThumbnails: cancelled while starting next task")
                    }
                }
            }
        }
    }
}

/** 缩略图预生成来源 */
enum class ThumbnailSource(val label: String) {
    GENERAL("常规"),
    COS("COS")
}

/** 缩略图预生成进度状态 */
sealed class ThumbnailProgress {
    object Idle : ThumbnailProgress()
    data class Running(val done: Int, val total: Int, val source: ThumbnailSource = ThumbnailSource.GENERAL) : ThumbnailProgress()
    data class Done(val total: Int, val source: ThumbnailSource = ThumbnailSource.GENERAL) : ThumbnailProgress()
}
