package com.qimeng.media.domain

import android.app.Application
import androidx.core.net.toUri
import com.qimeng.media.backup.BackupManager
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.AppDatabase
import com.qimeng.media.data.prefs.AppPrefsManager
import com.qimeng.media.data.repository.LocalMediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自动同步 UseCase：负责在数据变化时自动写入备份目录的单文件全量 JSON（qimeng_backup.json）。
 * 内置防抖机制，30 秒内不重复触发。
 *
 * 触发时机：
 * - 扫描/刷新/标签/收藏/历史等数据变化后 → triggerAutoSyncIfNeeded()（30 秒防抖）
 * - App 进入后台 → triggerFullSync()（60 秒防抖，兜底同步）
 * - 手动同步 → triggerManualSync()（无视防抖）
 *
 * 空库覆盖防护：所有写入点在调用 BackupManager 前检测 media_files 是否为空，
 * 空库时跳过写入并置 [restoreSuggestion] 为 true，通知 UI 提示用户先导入恢复，
 * 避免卸载重装后空数据库反向覆盖旧备份导致数据永久丢失。
 */
class AutoSyncUseCase(
    private val repository: LocalMediaRepository,
    private val appPrefsManager: AppPrefsManager,
    private val application: Application,
    private val database: AppDatabase
) {
    private var lastAutoSyncTime = 0L
    private var lastFullSyncTime = 0L

    /**
     * "建议恢复"信号：空库时自动同步/手动同步被拦截后置 true，UI 据此显示提示。
     * 用户导入恢复后调 [clearRestoreSuggestion] 清除。
     */
    private val _restoreSuggestion = MutableStateFlow(false)
    val restoreSuggestion: StateFlow<Boolean> = _restoreSuggestion.asStateFlow()

    fun clearRestoreSuggestion() { _restoreSuggestion.value = false }

    /** 数据变化后触发：写单文件全量备份，30 秒防抖 */
    suspend fun triggerAutoSyncIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncTime < AUTO_SYNC_DEBOUNCE_MS) return

        val appPrefs = appPrefsManager.prefs.value
        if (!appPrefs.autoSync) return

        lastAutoSyncTime = now
        performSync("自动同步")
    }

    /** App 进入后台触发：写单文件全量备份，60 秒防抖 */
    suspend fun triggerFullSync() {
        val now = System.currentTimeMillis()
        if (now - lastFullSyncTime < FULL_SYNC_DEBOUNCE_MS) return

        lastFullSyncTime = now
        lastAutoSyncTime = now
        performSync("全量同步（后台触发）")
    }

    /** 手动同步：无视防抖，立即写单文件全量备份，返回是否成功 */
    suspend fun triggerManualSync(): Boolean {
        lastFullSyncTime = System.currentTimeMillis()
        lastAutoSyncTime = System.currentTimeMillis()
        return performSync("手动同步")
    }

    /**
     * 统一写备份文件：读备份 URI → 空库覆盖防护 → BackupManager 单文件导出。
     * 空库时跳过写入并置 [restoreSuggestion]，避免空数据覆盖旧备份。
     */
    private suspend fun performSync(logTag: String): Boolean {
        val backupUri = repository.getSetting(KEY_BACKUP_DIRECTORY_URI)?.value
        if (backupUri.isNullOrBlank()) return false

        if (!database.mediaFileDao().hasAny()) {
            AppLog.w("AutoSync", "数据库为空，跳过${logTag}以防覆盖旧备份")
            _restoreSuggestion.value = true
            return false
        }

        return try {
            val manager = BackupManager(application)
            val result = manager.exportToDirectory(backupUri.toUri(), database, appPrefsManager) > 0
            AppLog.d("AutoSync", "${logTag}完成")
            result
        } catch (e: Exception) {
            AppLog.e("AutoSync", "${logTag}失败", e)
            false
        }
    }

    companion object {
        const val KEY_BACKUP_DIRECTORY_URI = "backup_directory_uri"
        /** 30 秒防抖（数据变化自动同步） */
        private const val AUTO_SYNC_DEBOUNCE_MS = 30_000L
        /** 60 秒防抖（后台兜底同步） */
        private const val FULL_SYNC_DEBOUNCE_MS = 60_000L
    }
}
