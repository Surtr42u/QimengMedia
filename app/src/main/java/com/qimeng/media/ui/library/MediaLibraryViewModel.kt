package com.qimeng.media.ui.library

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qimeng.media.QimengApplication
import com.qimeng.media.backup.BackupSummary
import com.qimeng.media.core.AppLog
import com.qimeng.media.core.ThumbnailCache
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.MediaTagCrossRef
import com.qimeng.media.data.db.entity.SettingEntity
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.data.db.entity.TimelineTagEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.domain.AuthorImportUseCase
import com.qimeng.media.domain.AutoSyncUseCase
import com.qimeng.media.domain.ScanResult
import com.qimeng.media.domain.ScanUseCase
import com.qimeng.media.domain.ThumbnailSource
import com.qimeng.media.domain.ThumbnailUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MediaLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val appContainer = (application as QimengApplication).appContainer
    private val repository = appContainer.localMediaRepository

    // UseCase 委托
    private val scanUseCase = appContainer.scanUseCase
    private val thumbnailUseCase = appContainer.thumbnailUseCase
    private val authorImportUseCase = appContainer.authorImportUseCase
    private val autoSyncUseCase = appContainer.autoSyncUseCase

    // 数据观察 Flow 属性（保留在 ViewModel）
    val allMedia = repository.observeAllMedia()
    val nonCosMedia = repository.observeNonCosMedia()
    val imageCount = repository.observeMediaCount(MediaType.IMAGE)
    val animatedImageCount = repository.observeMediaCount(MediaType.ANIMATED_IMAGE)
    val videoCount = repository.observeMediaCount(MediaType.VIDEO)
    val authors = repository.observeAuthors()
    val scanSources = repository.observeScanSources()
    val allStats = repository.observeAllStats()
    val allTags = repository.observeAllTags()
    val allMediaTags = repository.observeAllMediaTagNames()
    val history = repository.observeLatestHistory()
    /** v1.14：按天浏览聚合（无限保留，统计页趋势/窗口排行/沉浸时长唯一数据来源） */
    val dailyBrowse = repository.observeDailyBrowse()
    val backupDirectoryName = repository.observeSetting(KEY_BACKUP_DIRECTORY_NAME)
    val importedTxtFiles = repository.observeSetting(KEY_IMPORTED_TXT_FILES)
    val allAuthorMedia = repository.observeAllAuthorMedia()
    val authorFileCounts = repository.observeAuthorFileCounts()
    val customAlbumSources = appContainer.appPrefsManager.prefs
    val cosWorks = repository.observeCosWorks()
    val cosAuthors = repository.observeCosAuthors()
    val cosMedia = repository.observeCosMedia()
    val cosScanSources = repository.observeCosScanSources()

    // ScanStatus 状态（保留在 ViewModel，UI 状态属于 ViewModel）
    private val _scanStatus = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val scanStatus = _scanStatus.asStateFlow()

    // 缩略图预生成进度（委托 ThumbnailUseCase）
    val thumbnailProgress = thumbnailUseCase.progress

    // "建议恢复"信号（委托 AutoSyncUseCase）：空库时自动同步被拦截后置 true，UI 据此提示用户先导入恢复
    val restoreSuggestion = autoSyncUseCase.restoreSuggestion

    init {
        // 启动时清理孤立文件（扫描源已删除但文件仍残留）
        viewModelScope.launch(Dispatchers.IO) {
            try {
                scanUseCase.cleanupOrphanFiles()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // v1.x：取消异常透传，不吞
            } catch (e: Exception) {
                AppLog.e("VM", "cleanupOrphanFiles failed", e)
            }
        }
        // 启动时一次性重建作者关联（修复升级前遗留的跨TXT同名作者关联被覆盖的脏数据，仅首次启动执行）
        // 注：rebuildAssociationsOnceIfNeeded 内部已捕获重建失败异常（失败不置 flag 下次启动重试），
        // 外层 try-catch 仅兜底外部不可预期异常
        viewModelScope.launch(Dispatchers.IO) {
            try {
                authorImportUseCase.rebuildAssociationsOnceIfNeeded(scanUseCase)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // v1.x：取消异常透传，不吞
            } catch (e: Exception) {
                AppLog.e("VM", "rebuildAssociationsOnceIfNeeded failed", e)
            }
        }
        // 启动时从数据库预加载已有文件的缩略图到本地缓存
        viewModelScope.launch(Dispatchers.IO) {
            // 版本升级时清除视频缩略图缓存（黑帧检测需要重新生成）
            val prefs = getApplication<Application>().getSharedPreferences("thumb_cache_meta", android.content.Context.MODE_PRIVATE)
            val lastVersion = prefs.getInt("cache_version", 0)
            val currentVersion = 2 // 黑帧检测版本
            if (lastVersion < currentVersion) {
                AppLog.d("Scan", "init: cache version upgrade $lastVersion → $currentVersion, clearing video thumbnails")
                val videos = repository.getMediaByType(MediaType.VIDEO)
                ThumbnailCache.clearVideoThumbnails(getApplication(), videos.map { it.recordKey })
                prefs.edit { putInt("cache_version", currentVersion) }
            }

            val existingMedia = repository.getAllMedia()
            // v1.16：启动预生成前用全库 recordKey 对账清理孤儿缩略图缓存。
            // 替代旧版按数量淘汰最旧文件（enforceCacheLimit）——大库（文件数 > 5000）下
            // 数量淘汰会误删有效缓存，导致每次打开软件都重新缓存一部分缩略图。
            // 有效文件的缓存永不因数量上限被删，缓存目录有界性由本对账 + 删除文件时清理保证。
            ThumbnailCache.pruneOrphanThumbnails(getApplication(), existingMedia.map { it.recordKey }.toSet())
            if (existingMedia.isNotEmpty()) {
                AppLog.d("Scan", "init: pregenerating ${existingMedia.size} thumbnails from DB")
                // 按常规/COS分组，分别通过 ThumbnailUseCase 调用，确保进度 Flow 更新
                val (cosFiles, generalFiles) = existingMedia.partition { it.isCosFile }
                if (generalFiles.isNotEmpty()) {
                    thumbnailUseCase.pregenerateThumbnails(generalFiles, ThumbnailSource.GENERAL)
                }
                if (cosFiles.isNotEmpty()) {
                    thumbnailUseCase.pregenerateThumbnails(cosFiles, ThumbnailSource.COS)
                }
            }
        }
    }

    // ===== 扫描调度（委托 ScanUseCase）=====

    fun scanDirectory(uri: Uri, displayName: String? = null) {
        if (_scanStatus.value is ScanStatus.Running) return  // 防重入
        viewModelScope.launch(Dispatchers.IO) {
            _scanStatus.value = ScanStatus.Running
            var scannedMedia = emptyList<MediaFileEntity>()
            try {
                val result = scanUseCase.scanDirectory(uri, displayName)
                scannedMedia = result.mediaFiles
                _scanStatus.value = when (result) {
                    is ScanResult.Success -> ScanStatus.Success(result.count, result.directoryCount, result.warning)
                    is ScanResult.Error -> ScanStatus.Error(result.message)
                }
            } finally {
                if (_scanStatus.value is ScanStatus.Running) {
                    _scanStatus.value = ScanStatus.Idle
                }
                autoSyncUseCase.triggerAutoSyncIfNeeded()
                // 扫描完成后后台预生成缩略图到本地缓存
                thumbnailUseCase.pregenerateThumbnails(scannedMedia)
            }
        }
    }

    fun refreshScanSource(uriString: String) {
        if (_scanStatus.value is ScanStatus.Running) return  // 防重入
        viewModelScope.launch(Dispatchers.IO) {
            _scanStatus.value = ScanStatus.Running
            var refreshMedia = emptyList<MediaFileEntity>()
            try {
                val result = scanUseCase.refreshScanSource(uriString)
                refreshMedia = result.mediaFiles
                _scanStatus.value = when (result) {
                    is ScanResult.Success -> ScanStatus.Success(result.count, result.directoryCount)
                    is ScanResult.Error -> ScanStatus.Error(result.message)
                }
            } finally {
                if (_scanStatus.value is ScanStatus.Running) {
                    _scanStatus.value = ScanStatus.Idle
                }
                autoSyncUseCase.triggerAutoSyncIfNeeded()
                // 刷新完成后后台预生成缩略图到本地缓存
                thumbnailUseCase.pregenerateThumbnails(refreshMedia)
            }
        }
    }

    fun scanCosDirectory(uri: Uri, displayName: String? = null) {
        if (_scanStatus.value is ScanStatus.Running) return  // 防重入
        viewModelScope.launch(Dispatchers.IO) {
            _scanStatus.value = ScanStatus.Running
            var cosScanMedia = emptyList<MediaFileEntity>()
            try {
                val result = scanUseCase.scanCosDirectory(uri, displayName)
                cosScanMedia = result.mediaFiles
                _scanStatus.value = when (result) {
                    is ScanResult.Success -> ScanStatus.Success(result.count, result.directoryCount, result.warning)
                    is ScanResult.Error -> ScanStatus.Error(result.message)
                }
            } finally {
                if (_scanStatus.value is ScanStatus.Running) {
                    _scanStatus.value = ScanStatus.Idle
                }
                autoSyncUseCase.triggerAutoSyncIfNeeded()
                // COS扫描完成后后台预生成缩略图到本地缓存
                thumbnailUseCase.pregenerateThumbnails(cosScanMedia, ThumbnailSource.COS)
            }
        }
    }

    fun refreshCosSource(uriString: String) {
        if (_scanStatus.value is ScanStatus.Running) return  // 防重入
        viewModelScope.launch(Dispatchers.IO) {
            _scanStatus.value = ScanStatus.Running
            var cosRefreshMedia = emptyList<MediaFileEntity>()
            try {
                val result = scanUseCase.refreshCosSource(uriString)
                cosRefreshMedia = result.mediaFiles
                _scanStatus.value = when (result) {
                    is ScanResult.Success -> ScanStatus.Success(result.count, result.directoryCount)
                    is ScanResult.Error -> ScanStatus.Error(result.message)
                }
            } finally {
                if (_scanStatus.value is ScanStatus.Running) {
                    _scanStatus.value = ScanStatus.Idle
                }
                autoSyncUseCase.triggerAutoSyncIfNeeded()
                // COS刷新完成后后台预生成缩略图到本地缓存
                thumbnailUseCase.pregenerateThumbnails(cosRefreshMedia, ThumbnailSource.COS)
            }
        }
    }

    fun autoRefreshAllSources() {
        viewModelScope.launch(Dispatchers.IO) {
            // 按 GUIDE_SCAN 防重入设计：自动刷新是后台静默操作，不设置 ScanStatus.Running，
            // 不阻塞用户手动扫描（手动扫描入口的 Running 检查互不干扰）。
            // 并发防护由 ScanUseCase.autoRefreshAllSources 内部 isAutoRefreshing 承担。
            try {
                val autoRefreshMedia = scanUseCase.autoRefreshAllSources()

                // 注册 MediaStoreObserver 监听后续变化
                try {
                    val app = getApplication<QimengApplication>()
                    val allSources = repository.getScanSources()
                    val sources = allSources.filter { !it.isBackupDirectory && !it.isCosDirectory }
                    app.mediaStoreObserver.register(sources)
                } catch (e: Exception) { com.qimeng.media.core.AppLog.d("VM", "MediaStoreObserver register failed: ${e.message}") }

                // 自动刷新完成后预生成缩略图到本地缓存
                if (autoRefreshMedia.isNotEmpty()) {
                    thumbnailUseCase.pregenerateThumbnails(autoRefreshMedia)
                }

                // 自动刷新可能有新增/删除文件或作者关联变化，触发备份自动同步
                // （v1.13：补齐启动同步；triggerAutoSyncIfNeeded 内置 30 秒防抖 + autoSync 开关前置，
                // 不会因 App 内频繁操作反复写盘）
                autoSyncUseCase.triggerAutoSyncIfNeeded()
            } catch (e: Exception) {
                com.qimeng.media.core.AppLog.e("VM", "autoRefreshAllSources failed: ${e.message}", e)
            }
        }
    }

    fun deleteScanSource(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            scanUseCase.deleteScanSource(uriString)
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    fun deleteCosScanSource(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            scanUseCase.deleteCosScanSource(uriString)
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    // ===== 作者导入（委托 AuthorImportUseCase）=====

    fun importAuthorsFromText(text: String, fileName: String = "", uriString: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            authorImportUseCase.importAuthorsFromText(text, fileName, scanUseCase)
            // 保存TXT文件URI，用于刷新时重新读取
            if (uriString != null && fileName.isNotBlank()) {
                authorImportUseCase.saveTxtUri(fileName, uriString)
            }
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    /** 刷新TXT导入：先尝试从保存的URI重新读取文件，失败则用已保存的blocks重新匹配 */
    fun refreshTxtImport(fileName: String, onResult: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val uriString = authorImportUseCase.loadTxtUri(fileName)
            if (uriString != null) {
                // 方案A：尝试从URI重新读取文件内容
                try {
                    val uri = Uri.parse(uriString)
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        String(it.readBytes(), Charsets.UTF_8)
                    } ?: throw Exception("无法读取文件")
                    authorImportUseCase.importAuthorsFromText(text, fileName, scanUseCase)
                    autoSyncUseCase.triggerAutoSyncIfNeeded()
                    launch(Dispatchers.Main) { onResult(true, "已从文件重新导入") }
                    return@launch
                } catch (e: Exception) {
                    AppLog.d("AuthorImport", "refreshTxt: URI读取失败，降级为rematch: ${e.message}")
                }
            }
            // 方案B：URI不可用，用已保存的blocks重新匹配
            try {
                authorImportUseCase.rematchSingleTxtImport(fileName, scanUseCase)
                autoSyncUseCase.triggerAutoSyncIfNeeded()
                launch(Dispatchers.Main) { onResult(false, "文件不可访问，已用缓存数据重新匹配") }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onResult(false, "刷新失败：${e.message}") }
            }
        }
    }

    fun removeImportedTxtFile(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = repository.getSetting(KEY_IMPORTED_TXT_FILES)?.value ?: "[]"
            val arr = try { JSONArray(current) } catch (_: Exception) { JSONArray() }
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                if (arr.getString(i) != fileName) newArr.put(arr.getString(i))
            }
            repository.upsertSetting(
                SettingEntity(key = KEY_IMPORTED_TXT_FILES, value = newArr.toString(), updatedAtMillis = System.currentTimeMillis())
            )
            // 清理对应的blocks和URI数据
            authorImportUseCase.removeImportedTxtBlocks(fileName)
            authorImportUseCase.removeImportedTxtUri(fileName)
            // 删除 TXT 后从剩余 TXT 重建关联，清理该 TXT 产生的残留 crossRef
            try {
                authorImportUseCase.rebuildAssociationsFromBlocks(scanUseCase)
            } catch (e: Exception) {
                AppLog.d("AuthorImport", "removeTxt rebuild failed: ${e.message}")
            }
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    // ===== 自动同步（委托 AutoSyncUseCase）=====

    fun triggerAutoSyncIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    // ===== 统计记录（保留在 ViewModel）=====

    /**
     * 纯加载指定媒体实体（不触发 recordView）。
     *
     * 与 [loadAndRecordView] 的关系：本方法是其「加载」部分的提取，供详情页 viewCount
     * 会话级去重用（只查实体、回主线程回调，不写浏览统计）；[loadAndRecordView] 复用
     * [loadMediaInternal] 的加载逻辑后额外执行 recordView，对外行为不变（加载 + 记录）。
     * @param recordKey 媒体主键
     * @param onLoaded 主线程回调，查询结果（不存在时为 null）
     */
    fun loadMedia(recordKey: String, onLoaded: (MediaFileEntity?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val media = loadMediaInternal(recordKey)
            withContext(Dispatchers.Main) { onLoaded(media) }
        }
    }

    fun loadAndRecordView(recordKey: String, onLoaded: (MediaFileEntity?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // v1.x：复用 loadMedia 的纯加载逻辑（见 [loadMedia] 注释），加载完成后再记录浏览
            val media = loadMediaInternal(recordKey)
            if (media != null) {
                repository.recordView(recordKey, media.fileName)
            }
            withContext(Dispatchers.Main) { onLoaded(media) }
        }
    }

    /** 纯加载媒体实体（[loadMedia]/[loadAndRecordView] 共用的加载部分，不触发 recordView） */
    private suspend fun loadMediaInternal(recordKey: String): MediaFileEntity? =
        repository.getMediaByKey(recordKey)

    /** v1.14：视频真正开始播放时记一次播放（修复 playCount 从无写入点的问题） */
    fun recordPlay(recordKey: String, fileName: String) {
        if (recordKey.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.recordPlay(recordKey, fileName)
        }
    }

    fun updateBrowseDuration(recordKey: String, enterTimeMillis: Long) {
        if (recordKey.isBlank() || enterTimeMillis <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val elapsed = (System.currentTimeMillis() - enterTimeMillis) / 1000
            if (elapsed > 0 && repository.getMediaByKey(recordKey) != null) {
                repository.updateBrowseSeconds(recordKey, elapsed)
            }
        }
    }

    fun updateMediaMetadata(recordKey: String, width: Int?, height: Int?, durationMillis: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateMediaMetadata(recordKey, width, height, durationMillis)
        }
    }

    // ===== 设置管理（保留在 ViewModel）=====

    fun saveBackupDirectory(uri: Uri, displayName: String?, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            repository.upsertSetting(
                SettingEntity(
                    key = KEY_BACKUP_DIRECTORY_URI,
                    value = uri.toString(),
                    updatedAtMillis = now
                )
            )
            repository.upsertSetting(
                SettingEntity(
                    key = KEY_BACKUP_DIRECTORY_NAME,
                    value = displayName?.takeIf { it.isNotBlank() } ?: "已选择的备份目录",
                    updatedAtMillis = now
                )
            )
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    /**
     * 获取备份目录 URI（v1.7：自定义导出报告使用）。
     * @return 备份目录 URI 字符串，未设置时返回 null
     */
    suspend fun getBackupDirectoryUri(): String? = withContext(Dispatchers.IO) {
        repository.getSetting(KEY_BACKUP_DIRECTORY_URI)?.value
    }

    /**
     * 轻量检测备份目录是否已有可恢复数据（只读，不写数据库）。
     * 供 ProfileFragment 在用户选目录后调用，检测到数据则提示"是否导入恢复"。
     * @param uri 备份根目录 URI（绮梦影库/）
     * @return 备份数据量摘要，目录无备份数据时返回 null
     */
    suspend fun peekBackupSummary(uri: Uri): BackupSummary? = withContext(Dispatchers.IO) {
        appContainer.backupManager.peekBackupSummary(uri)
    }

    /**
     * 从已设置的备份目录导入恢复数据。
     * 供 ProfileFragment 确认恢复后调用，复用 BackupManager.importFromDirectory。
     * @param onComplete 回调，参数为成功导入的 JSON 文件数（0 表示无数据或失败）
     */
    fun importFromBackupDirectory(onComplete: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val uriStr = repository.getSetting(KEY_BACKUP_DIRECTORY_URI)?.value
            if (uriStr.isNullOrBlank()) {
                withContext(Dispatchers.Main) { onComplete(0) }
                return@launch
            }
            val count = try {
                appContainer.backupManager.importFromDirectory(
                    uriStr.toUri(), appContainer.database, appContainer.appPrefsManager
                )
            } catch (e: Exception) {
                AppLog.e("ViewModel", "importFromBackupDirectory failed", e)
                0
            }
            // 导入成功后清除"建议恢复"提示
            if (count > 0) autoSyncUseCase.clearRestoreSuggestion()
            withContext(Dispatchers.Main) { onComplete(count) }
        }
    }

    /**
     * 从用户主动选择的目录导入恢复数据（数据备份页"选择导入目录并恢复"入口）。
     * 与 [importFromBackupDirectory]（从已设置备份位置导入）不同，目录由用户临时选择，
     * 不改变已设置的备份位置。目录内无 "app数据" 子目录时直接读目录本身（BackupManager 兜底）。
     * @param uri 用户选择的备份根目录 URI（绮梦影库/ 或其 app数据 子目录）
     * @param onComplete 回调，参数为成功导入的 JSON 文件数（0 表示无数据或失败）
     */
    fun importFromDirectoryUri(uri: Uri, onComplete: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = try {
                appContainer.backupManager.importFromDirectory(
                    uri, appContainer.database, appContainer.appPrefsManager
                )
            } catch (e: Exception) {
                AppLog.e("ViewModel", "importFromDirectoryUri failed", e)
                0
            }
            // 导入成功后清除"建议恢复"提示
            if (count > 0) autoSyncUseCase.clearRestoreSuggestion()
            withContext(Dispatchers.Main) { onComplete(count) }
        }
    }

    /** 清除"建议恢复"提示信号（用户已知情或已恢复后调用） */
    fun clearRestoreSuggestion() = autoSyncUseCase.clearRestoreSuggestion()

    // ===== 标签管理（保留在 ViewModel）=====

    fun addTag(recordKey: String, tagName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val media = repository.getMediaByKey(recordKey) ?: return@launch
            val existingTag = repository.getTagByName(tagName)
            val tag = existingTag ?: repository.insertTag(
                TagEntity(
                    name = tagName,
                    createdAtMillis = System.currentTimeMillis()
                )
            ) ?: return@launch
            repository.upsertTagCrossRef(
                MediaTagCrossRef(
                    recordKey = recordKey,
                    tagId = tag.tagId,
                    fileName = media.fileName
                )
            )
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    fun removeTag(recordKey: String, tagId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeTagFromMedia(recordKey, tagId)
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    fun deleteTagById(tagId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTagById(tagId)
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    fun createTag(tagName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingTag = repository.getTagByName(tagName)
            if (existingTag == null) {
                repository.insertTag(TagEntity(name = tagName, createdAtMillis = System.currentTimeMillis()))
                autoSyncUseCase.triggerAutoSyncIfNeeded()
            }
        }
    }

    fun tagEntitiesForMedia(recordKey: String) = repository.observeTagEntitiesForMedia(recordKey)

    // ===== 时间轴标签 =====

    fun observeTimelineTags(recordKey: String) = repository.observeTimelineTags(recordKey)

    fun addTimelineTag(recordKey: String, fileName: String, timeMillis: Long, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.upsertTimelineTag(TimelineTagEntity(
                recordKey = recordKey,
                fileName = fileName,
                timeMillis = timeMillis,
                name = name,
                createdAtMillis = System.currentTimeMillis()
            ))
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    fun deleteTimelineTag(timelineTagId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTimelineTag(timelineTagId)
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    // ===== 作者管理（保留在 ViewModel）=====

    fun addAuthor(displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val authorId = scanUseCase.generateAuthorId(displayName)
            val now = System.currentTimeMillis()
            repository.upsertAuthor(
                AuthorEntity(
                    authorId = authorId,
                    displayName = displayName,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    fun authorsForMedia(recordKey: String) = repository.observeAuthorsForMedia(recordKey)

    fun mediaForAuthor(authorId: String) = repository.observeMediaForAuthor(authorId)

    // ===== 作者关注（SharedPreferences StringSet，与收藏一致）=====

    /** 切换作者关注状态，返回是否已关注 */
    fun toggleAuthorFollow(authorId: String): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_AUTHOR_FOLLOW, Context.MODE_PRIVATE)
        val followed = prefs.getStringSet(KEY_FOLLOWED_AUTHORS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val isFollowed = if (followed.contains(authorId)) {
            followed.remove(authorId)
            false
        } else {
            followed.add(authorId)
            true
        }
        prefs.edit { putStringSet(KEY_FOLLOWED_AUTHORS, followed) }
        // 关注状态存 SharedPreferences，不走详情页/扫描流程，需主动触发同步避免丢失
        viewModelScope.launch(Dispatchers.IO) { autoSyncUseCase.triggerAutoSyncIfNeeded() }
        return isFollowed
    }

    /** 查询作者是否已关注 */
    fun isAuthorFollowed(authorId: String): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_AUTHOR_FOLLOW, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_FOLLOWED_AUTHORS, emptySet())?.contains(authorId) == true
    }

    /** 获取所有已关注的作者ID集合 */
    fun followedAuthorIds(): Set<String> {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_AUTHOR_FOLLOW, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_FOLLOWED_AUTHORS, emptySet()).orEmpty()
    }

    // ===== 其他（保留在 ViewModel）=====

    fun observeHistory() = repository.observeLatestHistory()

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearHistory()
            autoSyncUseCase.triggerAutoSyncIfNeeded()
        }
    }

    fun addCustomAlbumSource(name: String) {
        appContainer.appPrefsManager.addCustomAlbumSource(name)
        // 自定义相册源属于推荐偏好，修改后触发同步
        viewModelScope.launch(Dispatchers.IO) { autoSyncUseCase.triggerAutoSyncIfNeeded() }
    }

    companion object {
        const val KEY_BACKUP_DIRECTORY_URI = "backup_directory_uri"
        const val KEY_BACKUP_DIRECTORY_NAME = "backup_directory_name"
        const val KEY_IMPORTED_TXT_FILES = "imported_txt_files"
        const val PREFS_AUTHOR_FOLLOW = "author_follow_prefs"
        const val KEY_FOLLOWED_AUTHORS = "followed_authors"
    }
}

sealed class ScanStatus {
    data object Idle : ScanStatus()
    data object Running : ScanStatus()
    data class Success(val count: Int, val directoryCount: Int = 1, val warning: String? = null) : ScanStatus()
    data class Error(val message: String) : ScanStatus()
}
