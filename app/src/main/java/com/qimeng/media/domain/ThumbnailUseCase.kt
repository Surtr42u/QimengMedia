package com.qimeng.media.domain

import android.app.Application
import com.qimeng.media.core.AppLog
import com.qimeng.media.core.ThumbnailCache
import com.qimeng.media.data.db.entity.MediaFileEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 缩略图预生成 UseCase：负责后台预生成缩略图到本地文件缓存。
 * 使用队列机制：新请求排队等待当前任务完成后再执行，不会跳过。
 */
class ThumbnailUseCase(
    private val application: Application
) {
    // v1.x：预生成任务统一用类内应用级 scope 启动——旧实现经调用方传入的 scope
    //（viewModelScope）启动，队列中下一任务沿用"上一任务的 scope"，观察者经
    // applicationScope 提交的任务可能落到已取消的 viewModelScope 上被静默丢弃。
    // UseCase 为容器级单例，此 scope 随 App 存活
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // v1.x：AtomicBoolean 保证 check-then-act 原子性——旧实现 @Volatile 的「检查 + 置位」两步分离，
    // 两个线程可同时通过检查并发启动两个预生成任务；compareAndSet 将检查与置位合并为原子操作
    private val isPregenerating = AtomicBoolean(false)

    private val _progress = MutableStateFlow<ThumbnailProgress>(ThumbnailProgress.Idle)
    val progress: StateFlow<ThumbnailProgress> = _progress.asStateFlow()

    /** 待处理的队列（只存数据与来源，不携带 scope，启动统一用 [appScope]） */
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
    fun pregenerateThumbnails(mediaFiles: List<MediaFileEntity>, source: ThumbnailSource = ThumbnailSource.GENERAL) {
        if (mediaFiles.isEmpty()) {
            AppLog.d("Scan", "pregenerateThumbnails: empty list, skipped")
            return
        }
        // v1.x：compareAndSet 原子完成「检查是否在跑 + 置为运行中」，失败说明已有任务在跑，
        // 当前请求入队；成功则只有本调用启动预生成，杜绝两线程同时通过检查的竞态
        if (!isPregenerating.compareAndSet(false, true)) {
            AppLog.d("Scan", "pregenerateThumbnails: already running, queuing ${mediaFiles.size} files (${source.label})")
            pendingQueue.add(PendingTask(mediaFiles, source))
            return
        }
        startPregeneration(mediaFiles, source)
    }

    private fun startPregeneration(mediaFiles: List<MediaFileEntity>, source: ThumbnailSource) {
        _progress.value = ThumbnailProgress.Running(0, mediaFiles.size, source)
        AppLog.d("Scan", "pregenerateThumbnails: starting with ${mediaFiles.size} files (${source.label})")
        appScope.launch {
            try {
                ThumbnailCache.pregenerateThumbnails(application, mediaFiles) { done, total ->
                    _progress.value = ThumbnailProgress.Running(done, total, source)
                }
                _progress.value = ThumbnailProgress.Done(mediaFiles.size, source)
                AppLog.d("Scan", "pregenerateThumbnails: completed ${mediaFiles.size} files (${source.label})")
            } catch (e: CancellationException) {
                // 取消同样复位 Idle（仅 Exception 分支复位会导致取消后 _progress 停留 Running）；
                // 取消必须向上透传，不能吞掉（否则 finally 的排队任务也永远不会执行）
                _progress.value = ThumbnailProgress.Idle
                throw e
            } catch (e: Exception) {
                _progress.value = ThumbnailProgress.Idle
                AppLog.e("Scan", "pregenerateThumbnails failed: ${e.message}", e)
            } finally {
                isPregenerating.set(false)
                // 处理队列中的下一个请求（应用级 scope 启动，不受上一个任务取消影响）
                val next = pendingQueue.poll()
                if (next != null) {
                    // v1.x：启动下一个任务前再次 CAS 保护——CAS 失败说明有并发调用已抢先
                    // 启动新的预生成（该调用会自行处理队列），把 next 放回队列末尾保证
                    // 「队列任务不丢失」；CAS 成功则本线程接管启动 next
                    if (!isPregenerating.compareAndSet(false, true)) {
                        pendingQueue.add(next)
                    } else {
                        startPregeneration(next.mediaFiles, next.source)
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
