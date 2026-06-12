package com.qimeng.media.domain

import android.app.Application
import com.qimeng.media.core.AppLog
import com.qimeng.media.core.ThumbnailCache
import com.qimeng.media.data.db.entity.MediaFileEntity
import kotlinx.coroutines.CoroutineScope
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
            } catch (e: Exception) {
                _progress.value = ThumbnailProgress.Idle
                AppLog.e("Scan", "pregenerateThumbnails failed: ${e.message}", e)
            } finally {
                isPregenerating = false
                // 处理队列中的下一个请求
                val next = pendingQueue.poll()
                if (next != null) {
                    withContext(Dispatchers.Main) {
                        startPregeneration(next.mediaFiles, coroutineScope, next.source)
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
