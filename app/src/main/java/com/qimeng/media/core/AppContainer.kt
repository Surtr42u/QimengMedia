package com.qimeng.media.core

import android.app.Application
import android.content.Context
import com.qimeng.media.backup.BackupManager
import com.qimeng.media.data.db.AppDatabase
import com.qimeng.media.data.prefs.AppPrefsManager
import com.qimeng.media.data.repository.DefaultLocalMediaRepository
import com.qimeng.media.data.repository.LocalMediaRepository
import com.qimeng.media.domain.AuthorImportUseCase
import com.qimeng.media.domain.AutoSyncUseCase
import com.qimeng.media.domain.ScanUseCase
import com.qimeng.media.domain.ThumbnailUseCase
import com.qimeng.media.scan.MediaStoreScanner
import com.qimeng.media.scan.SafMediaScanner

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }
    val localMediaRepository: LocalMediaRepository by lazy { DefaultLocalMediaRepository(database, appContext as Application) }
    val mediaScanner: SafMediaScanner by lazy { SafMediaScanner(appContext) }
    val mediaStoreScanner: MediaStoreScanner by lazy { MediaStoreScanner(appContext) }
    val backupManager: BackupManager by lazy { BackupManager(appContext) }
    val appPrefsManager: AppPrefsManager by lazy { AppPrefsManager(appContext) }

    // UseCase 懒加载（authorImportUseCase 先于 scanUseCase 初始化）
    val authorImportUseCase: AuthorImportUseCase by lazy { AuthorImportUseCase(localMediaRepository) }
    val scanUseCase: ScanUseCase by lazy { ScanUseCase(localMediaRepository, mediaScanner, mediaStoreScanner, appContext as Application, authorImportUseCase) }
    val thumbnailUseCase: ThumbnailUseCase by lazy { ThumbnailUseCase(appContext as Application) }
    val autoSyncUseCase: AutoSyncUseCase by lazy { AutoSyncUseCase(localMediaRepository, appPrefsManager, appContext as Application, database) }
}
