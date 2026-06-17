package com.qimeng.media.scan

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.net.toUri
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.ScanSourceEntity
import com.qimeng.media.data.repository.LocalMediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 监听系统媒体库变化，自动增量更新指定目录的媒体文件。
 *
 * 工作原理：
 * 1. 注册 ContentObserver 监听 MediaStore.Images 和 MediaStore.Video 的变化
 * 2. 收到变化通知后，防抖 2 秒
 * 3. 对每个已注册的常规扫描目录，用 MediaStoreScanner 重新查询
 * 4. 增量 upsert 新文件，删除已移除的文件
 *
 * 注意：只监听常规目录（isCosDirectory=false），COS 目录不在系统媒体库中。
 */
class MediaStoreObserver(
    private val context: Context,
    private val repository: LocalMediaRepository,
    private val mediaStoreScanner: MediaStoreScanner,
    private val scope: CoroutineScope
) {
    private val resolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private var debounceJob: Job? = null

    // 已注册的常规扫描源（非 COS、非备份）
    private var registeredSources: List<ScanSourceEntity> = emptyList()

    private val imageObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            scheduleRefresh()
        }
    }

    private val videoObserver = object : ContentObserver(handler) {
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

    private fun scheduleRefresh() {
        debounceJob?.cancel()
        debounceJob = scope.launch(Dispatchers.IO) {
            delay(2000L) // 2 秒防抖
            refreshAllSources()
        }
    }

    private suspend fun refreshAllSources() {
        val sources = registeredSources
        if (sources.isEmpty()) return

        for (source in sources) {
            try {
                val filePath = mediaStoreScanner.safUriToFilePath(source.uriString.toUri())
                if (filePath == null) {
                    AppLog.w("AutoRefresh", "Cannot convert URI to path: ${source.uriString}")
                    continue
                }

                val mediaFiles = mediaStoreScanner.queryByFolderPath(filePath)
                if (mediaFiles.isEmpty()) continue

                // 增量更新
                val prefix = if (source.uriString.endsWith("/")) source.uriString else "${source.uriString}/"
                val existingKeys = repository.getRecordKeysByUriPrefix(prefix).toSet()

                val newOrUpdated = mediaFiles.filter { it.recordKey !in existingKeys }
                if (newOrUpdated.isNotEmpty()) {
                    repository.upsertMedia(newOrUpdated)
                    AppLog.d("AutoRefresh", "Auto-refreshed ${source.displayName}: +${newOrUpdated.size} files")
                }

                // 清理已删除的文件
                val scannedKeys = mediaFiles.map { it.recordKey }.toSet()
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

}
