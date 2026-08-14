package com.qimeng.media.scan

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.net.toUri
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.ScanSourceEntity
import com.qimeng.media.data.repository.LocalMediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 监听系统媒体库变化，自动增量更新指定目录的媒体文件。
 *
 * 工作原理：
 * 1. 注册 ContentObserver 监听 MediaStore.Images 和 MediaStore.Video 的变化
 * 2. 收到变化通知后，防抖 [DEBOUNCE_MS] 毫秒（v1.7：2秒→5秒，合并更多变更）
 * 3. 防抖期间用计数器统计合并的变更通知次数，防抖结束后对所有常规扫描目录全量增量刷新
 * 4. 对每个已注册的常规扫描目录，用 MediaStoreScanner 重新查询
 * 5. 增量 upsert 新文件，删除已移除的文件
 * 6. 增量新增/更新文件通过 [onFilesChanged] 回调触发缩略图预生成（v1.16）
 *
 * 注意：只监听常规目录（isCosDirectory=false），COS 目录不在系统媒体库中。
 */
class MediaStoreObserver(
    private val context: Context,
    private val repository: LocalMediaRepository,
    private val mediaStoreScanner: MediaStoreScanner,
    private val safMediaScanner: SafMediaScanner,
    private val scope: CoroutineScope,
    /** v1.16：增量刷新产生新增/更新文件时回调（用于触发缩略图预生成填充本地 WebP 缓存） */
    private val onFilesChanged: (suspend (List<MediaFileEntity>) -> Unit)? = null
) {
    private val resolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private var debounceJob: Job? = null

    // v1.7：防抖期间统计变更通知次数，用于 log 诊断合并效果（轻量 AtomicInteger，无 Set 收集开销）
    private val pendingChangeCount = AtomicInteger(0)

    // v1.x：自动刷新单飞保护。防抖 cancel 只能取消挂起点，旧 job 已进 DB 事务时
    // 新 job 并发执行同一 source 的 upsert/delete 会产生竞态，用 AtomicBoolean 保证
    // 同一时刻只有一个刷新任务在跑（与 ScanUseCase.isAutoRefreshing 思路一致）
    private val isRefreshing = AtomicBoolean(false)

    // 已注册的常规扫描源（非 COS、非备份）
    private var registeredSources: List<ScanSourceEntity> = emptyList()

    private val imageObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            scheduleRefresh()
        }

        // 兼容旧回调（部分系统只调用此方法）
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            scheduleRefresh()
        }
    }

    private val videoObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            scheduleRefresh()
        }

        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            scheduleRefresh()
        }
    }

    private var isRegistered = false

    /**
     * 注册 ContentObserver 并设置扫描源列表。
     * @param sources 常规扫描源列表（不含 COS 和备份目录）
     */
    fun register(sources: List<ScanSourceEntity>) {
        registeredSources = sources
        if (!isRegistered) {
            resolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                imageObserver
            )
            resolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                videoObserver
            )
            isRegistered = true
            AppLog.d("AutoRefresh", "ContentObserver registered")
        }
    }

    fun unregister() {
        if (isRegistered) {
            resolver.unregisterContentObserver(imageObserver)
            resolver.unregisterContentObserver(videoObserver)
            isRegistered = false
            AppLog.d("AutoRefresh", "ContentObserver unregistered")
        }
    }

    fun updateSources(sources: List<ScanSourceEntity>) {
        registeredSources = sources
    }

    /**
     * 防抖合并多次变更通知为一次刷新（v1.7）。
     * 计数器统计防抖期间的通知次数，用于 log 诊断合并效果。
     *
     * 单飞保护（v1.x）：debounceJob.cancel() 只能取消挂起点，旧 job 已进入 DB 事务时
     * 新 job 会并发执行同一 source 的 upsert/delete 造成竞态。
     * [isRefreshing] 若已有任务在跑则直接丢弃本次触发（下次系统变更通知会再次调度），
     * 保证同一时刻只有一个自动刷新在执行。
     */
    private fun scheduleRefresh() {
        val count = pendingChangeCount.incrementAndGet()
        if (isRefreshing.get()) return

        debounceJob?.cancel()
        debounceJob = scope.launch(Dispatchers.IO) {
            isRefreshing.set(true)
            try {
                delay(DEBOUNCE_MS) // v1.7：5 秒防抖（原 2 秒）
                refreshAllSources(count)
            } finally {
                isRefreshing.set(false)
            }
        }
    }

    private suspend fun refreshAllSources(mergedChangeCount: Int = 1) {
        // v1.12：每次刷新前从库重拉最新常规源——旧实现仅 register() 时冻结快照，
        // 且 updateSources 从未被调用：会话内新增源不被监听、删除的源仍被持有并
        // 在刷新时 upsertScanSource 复活。这里实时同步源列表，删除的源不再复活。
        val sources = runCatching {
            repository.getScanSources().filter { !it.isBackupDirectory && !it.isCosDirectory }
        }.getOrElse { registeredSources }
        if (sources.isEmpty()) {
            pendingChangeCount.set(0)
            return
        }

        pendingChangeCount.set(0)

        AppLog.d("AutoRefresh", "refreshAllSources: mergedChanges=$mergedChangeCount, sources=${sources.size}")

        for (source in sources) {
            try {
                val filePath = mediaStoreScanner.safUriToFilePath(source.uriString.toUri())
                if (filePath == null) {
                    AppLog.w("AutoRefresh", "Cannot convert URI to path: ${source.uriString}")
                    continue
                }

                val mediaFiles = mediaStoreScanner.queryByFolderPath(filePath, source.uriString)

                // v1.x：MediaStore 查询为空时先走 SAF 复核再决定是否清理残留——
                // .nomedia / Android 16 非标准目录 MediaStore 恒查不到，空结果会被误判为
                // "目录清空"整源删除（连带统计/历史/标签）。SAF 复核有文件则以 SAF 结果
                // 为准（对齐 ScanUseCase 手动/自动刷新路径的空→SAF 回退语义），
                // SAF 也为空才确认目录真被清空并清理残留（保留 v1.15 的残留清理语义）。
                val safeMediaFiles = if (mediaFiles.isEmpty()) {
                    runCatching { safMediaScanner.scanTreeFast(source.uriString.toUri(), source.uriString) }
                        .getOrDefault(mediaFiles)
                } else {
                    mediaFiles
                }

                // 增量更新（v1.12：按 sourceUri 精确匹配，避免 URI 前缀跨命名空间失效）
                val existingKeys = repository.getRecordKeysBySourceUri(source.uriString).toSet()

                // v1.x：只加载"扫描结果中已存在库中的 key"的实体用于元数据比较，
                // 避免全表加载与无谓全量 upsert（SAF 回退路径尺寸字段为 null 时也不会被覆盖成空）
                val existingByKey = safeMediaFiles
                    .mapNotNull { it.recordKey.takeIf { key -> key in existingKeys } }
                    .distinct()
                    .mapNotNull { key -> repository.getMediaByKey(key)?.let { key to it } }
                    .toMap()

                // v1.x：不仅 upsert 新 key，同名覆盖/重编码文件元数据有变化时也更新，
                // 避免 sizeBytes/duration/width/height/modifiedAtMillis 永久陈旧。
                val newOrUpdated = safeMediaFiles.filter { file ->
                    file.recordKey !in existingKeys ||
                        existingByKey[file.recordKey]?.let { ScanUtils.hasScanMetadataChanged(file, it) } == true
                }
                if (newOrUpdated.isNotEmpty()) {
                    // v1.x：分块 upsert 避免大变更（数千文件）单次调用超过 SQLite 绑定参数上限
                    //（旧设备 999）抛 SQLiteException 导致整源刷新失败（与 ScanUseCase 分块模式一致）
                    newOrUpdated.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
                    AppLog.d("AutoRefresh", "Auto-refreshed ${source.displayName}: +${newOrUpdated.size} files")
                    // v1.16：增量新增/更新文件同步触发缩略图预生成，填充本地 WebP 缓存。
                    // 视频文件无缓存时滚动中只有 Coil 首帧内存缓存（adapter 不再后台双解），
                    // 本地文件缓存依赖此处 + 启动预生成填充；图片滚动中 adapter 会按需解码兜底。
                    // 回调内部自行 launch，不阻塞增量刷新；异常已由 ThumbnailUseCase 内部兜底。
                    runCatching { onFilesChanged?.invoke(newOrUpdated) }
                }

                // 清理已删除的文件（v1.x：目录被清空导致查询为空时也要执行 staleKeys 清理，
                // 旧实现空查询直接 continue，跳过下方清理导致 DB 行残留。空查询先经上方
                // SAF 复核——SAF 仍有文件则走 upsert 分支，走到这里说明 SAF 复核也为空，
                // 目录确被清空或已不可访问，按 staleKeys 清理残留，对齐 v1.15 语义）
                val scannedKeys = safeMediaFiles.map { it.recordKey }.toSet()
                val staleKeys = existingKeys.filter { it !in scannedKeys }
                if (staleKeys.isNotEmpty()) {
                    repository.deleteMediaAndRefs(staleKeys)
                    AppLog.d("AutoRefresh", "Auto-cleaned ${source.displayName}: -${staleKeys.size} files")
                }

                // 更新扫描时间
                repository.upsertScanSource(source.copy(lastScannedAtMillis = System.currentTimeMillis()))
            } catch (e: Exception) {
                AppLog.e("AutoRefresh", "Auto-refresh failed for ${source.displayName}", e)
            }
        }
    }

    companion object {
        // v1.7：防抖时间从 2 秒延长到 5 秒，合并更多变更通知，减少频繁拍照场景下的扫描开销
        private const val DEBOUNCE_MS = 5000L
    }

}
