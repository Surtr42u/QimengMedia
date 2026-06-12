package com.qimeng.media.domain

import android.app.Application
import androidx.core.net.toUri
import com.qimeng.media.backup.BackupManager
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.AppDatabase
import com.qimeng.media.data.prefs.AppPrefsManager
import com.qimeng.media.data.repository.LocalMediaRepository

/**
 * 自动同步 UseCase：负责在数据变化时自动写入备份目录。
 * 内置防抖机制，30秒内不重复触发。
 *
 * 触发时机：
 * - 扫描/刷新后 → triggerAutoSyncIfNeeded()（只写 app数据/）
 * - 退出详情页 → triggerAutoSyncForDetailExit()（只写 app数据/）
 * - App 进入后台 → triggerFullSync()（写 app数据/ + 个人偏好/）
 * - 手动同步 → triggerManualSync()（写 app数据/ + 个人偏好/，无视防抖）
 */
class AutoSyncUseCase(
    private val repository: LocalMediaRepository,
    private val appPrefsManager: AppPrefsManager,
    private val application: Application,
    private val database: AppDatabase
) {
    private var lastAutoSyncTime = 0L
    private var lastFullSyncTime = 0L
    private val AUTO_SYNC_DEBOUNCE_MS = 30_000L // 30秒防抖（app数据/）
    private val FULL_SYNC_DEBOUNCE_MS = 60_000L // 60秒防抖（全量同步）

    /** 扫描/刷新后触发：只写 app数据/，30秒防抖 */
    suspend fun triggerAutoSyncIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncTime < AUTO_SYNC_DEBOUNCE_MS) return

        val appPrefs = appPrefsManager.prefs.value
        if (!appPrefs.autoSync) return

        lastAutoSyncTime = now
        syncAppDataOnly()
    }

    /** 退出详情页触发：只写 app数据/（JSON 快），30秒防抖 */
    suspend fun triggerAutoSyncForDetailExit() {
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncTime < AUTO_SYNC_DEBOUNCE_MS) return

        val appPrefs = appPrefsManager.prefs.value
        if (!appPrefs.autoSync) return

        lastAutoSyncTime = now
        syncAppDataOnly()
    }

    /** App 进入后台触发：全量同步（app数据/ + 个人偏好/），60秒防抖 */
    suspend fun triggerFullSync() {
        val now = System.currentTimeMillis()
        if (now - lastFullSyncTime < FULL_SYNC_DEBOUNCE_MS) return

        val backupUri = repository.getSetting(KEY_BACKUP_DIRECTORY_URI)?.value
        if (backupUri.isNullOrBlank()) return

        lastFullSyncTime = now
        lastAutoSyncTime = now
        try {
            val manager = BackupManager(application)
            manager.fullSyncToDirectory(backupUri.toUri(), database, appPrefsManager)
            AppLog.d("AutoSync", "全量同步完成（后台触发）")
        } catch (e: Exception) {
            AppLog.e("AutoSync", "全量同步失败", e)
        }
    }

    /** 手动同步：无视防抖，立即全量同步，返回是否成功 */
    suspend fun triggerManualSync(): Boolean {
        val backupUri = repository.getSetting(KEY_BACKUP_DIRECTORY_URI)?.value
        if (backupUri.isNullOrBlank()) return false

        lastFullSyncTime = System.currentTimeMillis()
        lastAutoSyncTime = System.currentTimeMillis()
        return try {
            val manager = BackupManager(application)
            val result = manager.fullSyncToDirectory(backupUri.toUri(), database, appPrefsManager)
            AppLog.d("AutoSync", "手动同步完成")
            result
        } catch (e: Exception) {
            AppLog.e("AutoSync", "手动同步失败", e)
            false
        }
    }

    /** 只同步 app数据/ 子目录 */
    private suspend fun syncAppDataOnly() {
        try {
            val backupUri = repository.getSetting(KEY_BACKUP_DIRECTORY_URI)?.value
            if (backupUri.isNullOrBlank()) return

            val manager = BackupManager(application)
            manager.autoSyncToDirectory(backupUri.toUri(), database, appPrefsManager)
            AppLog.d("AutoSync", "自动同步完成（app数据）")
        } catch (e: Exception) {
            AppLog.e("AutoSync", "自动同步失败", e)
        }
    }

    companion object {
        const val KEY_BACKUP_DIRECTORY_URI = "backup_directory_uri"
    }
}
