package com.qimeng.media.domain

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.qimeng.media.core.AppLog
import com.qimeng.media.core.ScanSourceLocks
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.ScanSourceEntity
import com.qimeng.media.data.repository.LocalMediaRepository
import com.qimeng.media.scan.MediaStoreScanner
import com.qimeng.media.scan.SafMediaScanner
import com.qimeng.media.scan.ScanUtils
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫描调度 UseCase：负责目录扫描、增量刷新、COS 扫描、删除扫描源等操作。
 * ViewModel 通过委托调用此 UseCase，保持公共 API 不变。
 */
class ScanUseCase(
    private val repository: LocalMediaRepository,
    private val mediaScanner: SafMediaScanner,
    private val mediaStoreScanner: MediaStoreScanner,
    private val application: Application,
    private val authorImportUseCase: AuthorImportUseCase
) {
    private var lastAutoRefreshTime = 0L

    // 自动刷新防重入：onResume 触发回前台刷新后，可能上一次大目录扫描尚未完成，
    // 若不拦截会导致两个并发扫描同时 upsert/delete 引发数据库锁竞争。
    // 与 isPregenerating 一样使用 AtomicBoolean 保证线程安全。
    private val isAutoRefreshing = AtomicBoolean(false)

    /** 扫描指定目录，返回扫描结果和扫描到的媒体文件列表 */
    suspend fun scanDirectory(uri: Uri, displayName: String? = null): ScanResult {
        val startedAtMillis = System.currentTimeMillis()
        AppLog.d("Scan", "scanDirectory: start, uri=$uri")
        var scannedMedia = emptyList<MediaFileEntity>()
        val uriString = uri.toString()

        try {
            val mediaFiles = resolveRecordKeyConflicts(
                tryMediaStoreScan(uri, uriString) ?: mediaScanner.scanTreeFast(uri, uriString),
                uriString
            )
            scannedMedia = mediaFiles

            val sourceName = displayName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: mediaStoreScanner.safUriToFilePath(uri)?.substringAfterLast('/')
                ?: DocumentFile.fromTreeUri(application, uri)?.name
                ?: "授权目录"

            // 按源互斥：与增量刷新/自动刷新/MediaStoreObserver 并发写同一源时，
            // 防止后完成方把先完成方新 upsert 的行当 stale 删除
            ScanSourceLocks.withSourceLock(uriString) {
                // 先登记源再写文件：防与启动期 cleanupOrphanFiles 并发时，
                // 新源文件先入库而被按"源不存在"误判孤儿删除
                repository.upsertScanSource(ScanSourceEntity(
                    uriString = uriString,
                    displayName = sourceName,
                    isBackupDirectory = false,
                    addedAtMillis = startedAtMillis,
                    lastScannedAtMillis = System.currentTimeMillis()
                ))

                mediaFiles.chunked(500).forEach { batch -> repository.upsertMedia(batch) }

                val scannedKeys = mediaFiles.map { it.recordKey }.toSet()
                val existingKeys = repository.getRecordKeysBySourceUri(uriString).toSet()
                val staleKeys = existingKeys.filter { it !in scannedKeys }
                if (staleKeys.isNotEmpty()) {
                    repository.deleteMediaAndRefs(staleKeys)
                    repository.deleteOrphanCosAuthors()
                }
            }

            // 扫描完成后重新匹配已有TXT导入，确保新文件也能被关联到作者
            try {
                authorImportUseCase.rematchAllTxtImports(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.d("Scan", "rematchAllTxtImports failed: ${e.message}")
            }

            // v1.7：检测大文件/大总量警告
            val warning = ScanResult.buildScanWarning(scannedMedia)
            return ScanResult.Success(mediaFiles.size, 1, scannedMedia, warning)
        } catch (e: CancellationException) {
            throw e
        } catch (exception: Exception) {
            AppLog.e("Scan", "scanDirectory failed: ${exception.message}", exception)
            return ScanResult.Error(exception.message ?: "扫描失败", scannedMedia)
        }
    }

    /** 增量刷新已添加的扫描源 */
    suspend fun refreshScanSource(uriString: String): ScanResult {
        var refreshMedia = emptyList<MediaFileEntity>()
        try {
            val uri = uriString.toUri()
            val mediaFiles = resolveRecordKeyConflicts(
                tryMediaStoreScan(uri, uriString) ?: mediaScanner.scanTreeFast(uri, uriString),
                uriString
            )
            refreshMedia = mediaFiles

            // 按源互斥：与手动扫描/自动刷新/MediaStoreObserver 并发写同一源时防止 stale 误删
            val updatedCount = ScanSourceLocks.withSourceLock(uriString) {
                val existingKeys = repository.getRecordKeysBySourceUri(uriString).toSet()

                // v1.x：不仅 upsert 新 key，同名覆盖/重编码文件元数据有变化时也更新，
                // 避免 sizeBytes/duration/width/height/modifiedAtMillis 永久陈旧
                val existingByKey = mediaFiles
                    .mapNotNull { it.recordKey.takeIf { key -> key in existingKeys } }
                    .distinct()
                    .mapNotNull { key -> repository.getMediaByKey(key)?.let { key to it } }
                    .toMap()
                val newOrUpdated = mediaFiles.filter { file ->
                    file.recordKey !in existingKeys ||
                        existingByKey[file.recordKey]?.let { ScanUtils.hasScanMetadataChanged(file, it) } == true
                }
                if (newOrUpdated.isNotEmpty()) {
                    newOrUpdated.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
                }

                val scannedKeys = mediaFiles.map { it.recordKey }.toSet()
                val staleKeys = existingKeys.filter { it !in scannedKeys }
                if (staleKeys.isNotEmpty()) {
                    repository.deleteMediaAndRefs(staleKeys)
                    repository.deleteOrphanCosAuthors()
                }

                // 刷新进行中源被用户删除时不复活 scan_sources 行（existing 为 null 说明源已不在库中）
                val existing = repository.getScanSources().find { it.uriString == uriString }
                if (existing != null) {
                    repository.upsertScanSource(ScanSourceEntity(
                        uriString = uriString,
                        displayName = existing.displayName,
                        isBackupDirectory = false,
                        addedAtMillis = existing.addedAtMillis,
                        lastScannedAtMillis = System.currentTimeMillis()
                    ))
                }
                newOrUpdated.size
            }

            // 刷新完成后重新匹配已有TXT导入，确保新文件也能被关联到作者
            try {
                authorImportUseCase.rematchAllTxtImports(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.d("Scan", "rematchAllTxtImports failed: ${e.message}")
            }

            return ScanResult.Success(updatedCount, 1, refreshMedia)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ScanResult.Error(e.message ?: "刷新失败", refreshMedia)
        }
    }

    /** 扫描 COS 目录 */
    suspend fun scanCosDirectory(uri: Uri, displayName: String? = null): ScanResult {
        var cosScanMedia = emptyList<MediaFileEntity>()
        AppLog.d("CosScan", "scanCosDirectory: start, uri=$uri")
        try {
            val uriString = uri.toString()
            val indexedAtMillis = System.currentTimeMillis()

            val (newCosMedia, cosMediaAuthorMap, newCosWorks) = scanCosMedia(uri, uriString, indexedAtMillis)
            cosScanMedia = newCosMedia

            val filePath = mediaStoreScanner.safUriToFilePath(uri)
            val sourceName = displayName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: filePath?.substringAfterLast('/')
                ?: "COS目录"

            // 按源互斥：与 COS 增量刷新/自动刷新并发写同一源时防止 stale 误删
            ScanSourceLocks.withSourceLock(uriString) {
                newCosMedia.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
                repository.upsertCosWorks(newCosWorks)

                val scannedKeys = newCosMedia.map { it.recordKey }.toSet()
                val existingCosKeys = repository.getCosRecordKeysBySourceUri(uriString).toSet()
                val staleKeys = existingCosKeys.filter { it !in scannedKeys }
                if (staleKeys.isNotEmpty()) {
                    repository.deleteMediaAndRefs(staleKeys)
                    repository.deleteOrphanCosAuthors()
                }

                repository.upsertScanSource(ScanSourceEntity(
                    uriString = uriString,
                    displayName = sourceName,
                    isCosDirectory = true,
                    addedAtMillis = System.currentTimeMillis(),
                    lastScannedAtMillis = System.currentTimeMillis()
                ))

                val authorNames = newCosWorks.map { it.authorName }.distinct()
                val authorEntities = authorNames.map { authorName ->
                    val authorId = "cos_" + generateAuthorId(authorName)
                    AuthorEntity(authorId = authorId, displayName = authorName, createdAtMillis = indexedAtMillis, updatedAtMillis = indexedAtMillis)
                }
                repository.upsertAllAuthors(authorEntities)

                val crossRefs = mutableListOf<AuthorMediaCrossRef>()
                for ((authorName, files) in cosMediaAuthorMap) {
                    val authorId = "cos_" + generateAuthorId(authorName)
                    for (file in files) {
                        crossRefs.add(AuthorMediaCrossRef(authorId = authorId, recordKey = file.recordKey, fileName = file.fileName, isMatched = true))
                    }
                }
                crossRefs.chunked(500).forEach { batch -> repository.upsertAllAuthorMedia(batch) }
            }

            AppLog.d("CosScan", "scanCosDirectory: success, files=${newCosMedia.size}, authors=${newCosWorks.map { it.authorName }.distinct().size}, works=${newCosWorks.size}")
            // v1.7：检测大文件/大总量警告
            val warning = ScanResult.buildScanWarning(newCosMedia)
            return ScanResult.Success(newCosMedia.size, 1, cosScanMedia, warning)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("CosScan", "scanCosDirectory failed: ${e.message}", e)
            return ScanResult.Error(e.message ?: "COS扫描失败", cosScanMedia)
        }
    }

    /** 增量刷新 COS 扫描源 */
    suspend fun refreshCosSource(uriString: String): ScanResult {
        var cosRefreshMedia = emptyList<MediaFileEntity>()
        try {
            val uri = uriString.toUri()
            val indexedAtMillis = System.currentTimeMillis()

            val (newCosMedia, cosMediaAuthorMap, newCosWorks) = scanCosMedia(uri, uriString, indexedAtMillis)
            cosRefreshMedia = newCosMedia

            val existingCosKeys = repository.getCosRecordKeysBySourceUri(uriString).toSet()

            // 按源互斥：与 COS 扫描/自动刷新并发写同一源时防止 stale 误删
            val updatedCount = ScanSourceLocks.withSourceLock(uriString) {
                // v1.x：对齐 refreshScanSource 模式——只加载"扫描结果中已存在库中的 key"的实体用于
                // 元数据比较，同名覆盖/重编码文件元数据（sizeBytes/duration/width/height/modifiedAtMillis）
                // 有变化时也更新，避免永久陈旧（旧实现只 upsert 新 key，覆盖文件元数据永远不更新）
                val existingCosByKey = newCosMedia
                    .mapNotNull { it.recordKey.takeIf { k -> k in existingCosKeys } }
                    .distinct()
                    .mapNotNull { k -> repository.getMediaByKey(k)?.let { k to it } }
                    .toMap()
                val newOrUpdated = newCosMedia.filter { file ->
                    file.recordKey !in existingCosKeys ||
                        existingCosByKey[file.recordKey]?.let { ScanUtils.hasScanMetadataChanged(file, it) } == true
                }
                if (newOrUpdated.isNotEmpty()) {
                    newOrUpdated.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
                }

                repository.upsertCosWorks(newCosWorks)

                // v1.x：刷新后清理该源已消失/改名的作品行（按当前扫描到的 workKey 集合判断，
                // 磁盘上已删除/改名的作品不再保留，避免 cos_works 行残留）
                val currentWorkKeys = newCosWorks.map { "${it.authorName}/${it.workName}" }.toSet()
                val disappearedWorkIds = repository.getCosWorksBySourceUri(uriString)
                    .filter { "${it.authorName}/${it.workName}" !in currentWorkKeys }
                    .map { it.id }
                if (disappearedWorkIds.isNotEmpty()) {
                    repository.deleteCosWorksByIds(disappearedWorkIds)
                    AppLog.d("CosScan", "refreshCosSource: cleaned ${disappearedWorkIds.size} disappeared works")
                }

                val scannedKeys = newCosMedia.map { it.recordKey }.toSet()
                val staleKeys = existingCosKeys.filter { it !in scannedKeys }
                if (staleKeys.isNotEmpty()) {
                    repository.deleteMediaAndRefs(staleKeys)
                    repository.deleteOrphanCosAuthors()
                }

                // 刷新进行中源被用户删除时不复活 scan_sources 行（existingCos 为 null 说明源已不在库中）
                val existingCos = repository.getScanSources().find { it.uriString == uriString }
                if (existingCos != null) {
                    repository.upsertScanSource(ScanSourceEntity(
                        uriString = uriString,
                        displayName = existingCos.displayName,
                        isCosDirectory = true,
                        addedAtMillis = existingCos.addedAtMillis,
                        lastScannedAtMillis = System.currentTimeMillis()
                    ))
                }

                val authorNames = newCosWorks.map { it.authorName }.distinct()
                val authorEntities = authorNames.map { authorName ->
                    AuthorEntity(authorId = "cos_" + generateAuthorId(authorName), displayName = authorName, createdAtMillis = indexedAtMillis, updatedAtMillis = indexedAtMillis)
                }
                repository.upsertAllAuthors(authorEntities)

                val crossRefs = mutableListOf<AuthorMediaCrossRef>()
                for ((authorName, files) in cosMediaAuthorMap) {
                    val authorId = "cos_" + generateAuthorId(authorName)
                    for (file in files) {
                        crossRefs.add(AuthorMediaCrossRef(authorId = authorId, recordKey = file.recordKey, fileName = file.fileName, isMatched = true))
                    }
                }
                crossRefs.chunked(500).forEach { batch -> repository.upsertAllAuthorMedia(batch) }
                newOrUpdated.size
            }

            return ScanResult.Success(updatedCount, 1, cosRefreshMedia)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ScanResult.Error(e.message ?: "COS刷新失败", cosRefreshMedia)
        }
    }

    /**
     * 自动增量刷新所有已添加目录（App启动/回前台时调用，带防抖 + 防重入）。
     *
     * 防抖：常规目录 [AUTO_REFRESH_INTERVAL] 内不重复刷新；COS 目录按各自 lastScannedAtMillis 判断 [COS_AUTO_REFRESH_INTERVAL]。
     * 防重入：[isAutoRefreshing] 保证同一时刻只有一个自动刷新在跑，避免 onResume 频繁触发导致并发扫描。
     */
    suspend fun autoRefreshAllSources(): List<MediaFileEntity> {
        // 防重入：上一次自动刷新尚未完成则直接跳过（compareAndSet 原子操作，线程安全）
        if (!isAutoRefreshing.compareAndSet(false, true)) {
            AppLog.d("Scan", "autoRefresh skipped: already running")
            return emptyList()
        }
        return try {
            doAutoRefreshAllSources()
        } finally {
            isAutoRefreshing.set(false)
        }
    }

    /**
     * 自动刷新实际执行逻辑（由 [autoRefreshAllSources] 在防重入保护下调用）。
     * 行数备案（v1.16 审查后 127 行，红色区）：多源顺序刷新编排（常规+COS 各含
     * 读→upsert→删 stale→源存在性确认→时间戳），v1.16 加 ScanSourceLocks 按源互斥与
     * CancellationException 透传后超 120 行；扫描编排天然顺序耦合，拆分会割裂单源临界区
     * 语义，按 GUIDE_CODE_MAINTENANCE §8「扫描编排」合理复杂度显式备案。
     */
    private suspend fun doAutoRefreshAllSources(): List<MediaFileEntity> {
        val now = System.currentTimeMillis()
        if (now - lastAutoRefreshTime < AUTO_REFRESH_INTERVAL) return emptyList()
        lastAutoRefreshTime = now

        val allSources = repository.getScanSources()
        val sources = allSources.filter { !it.isBackupDirectory && !it.isCosDirectory }
        val cosSources = allSources.filter {
            it.isCosDirectory && (now - (it.lastScannedAtMillis ?: 0L)) > COS_AUTO_REFRESH_INTERVAL
        }
        if (sources.isEmpty() && cosSources.isEmpty()) return emptyList()

        AppLog.d("Scan", "autoRefresh: sources=${sources.size}, cosSources=${cosSources.size}")
        val autoRefreshMedia = mutableListOf<MediaFileEntity>()

        try {
            for (source in sources) {
                try {
                    val uri = source.uriString.toUri()
                    val mediaFiles = resolveRecordKeyConflicts(
                        tryMediaStoreScan(uri, source.uriString) ?: mediaScanner.scanTreeFast(uri, source.uriString),
                        source.uriString
                    )
                    autoRefreshMedia.addAll(mediaFiles)
                    // 按源互斥：与手动扫描/MediaStoreObserver 增量并发写同一源时防止 stale 误删
                    ScanSourceLocks.withSourceLock(source.uriString) {
                        val existingKeys = repository.getRecordKeysBySourceUri(source.uriString).toSet()
                        // v1.x：同名覆盖/重编码文件元数据有变化时也更新（与 refreshScanSource 一致）
                        val existingByKey = mediaFiles
                            .mapNotNull { it.recordKey.takeIf { key -> key in existingKeys } }
                            .distinct()
                            .mapNotNull { key -> repository.getMediaByKey(key)?.let { key to it } }
                            .toMap()
                        val newFiles = mediaFiles.filter { file ->
                            file.recordKey !in existingKeys ||
                                existingByKey[file.recordKey]?.let { ScanUtils.hasScanMetadataChanged(file, it) } == true
                        }
                        if (newFiles.isNotEmpty()) {
                            newFiles.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
                        }
                        val scannedKeys = mediaFiles.map { it.recordKey }.toSet()
                        val staleKeys = existingKeys.filter { it !in scannedKeys }
                        if (staleKeys.isNotEmpty()) {
                            repository.deleteMediaAndRefs(staleKeys)
                        }
                        // 循环期间源可能已被用户删除：确认仍存在才写，防止 copy 复活已删源
                        if (repository.getScanSources().any { it.uriString == source.uriString }) {
                            repository.upsertScanSource(source.copy(lastScannedAtMillis = System.currentTimeMillis()))
                        }
                    }
                } catch (e: Exception) {
                    // 取消必须向上透传，不能当普通失败吞掉
                    if (e is CancellationException) throw e
                    com.qimeng.media.core.AppLog.d("Scan", "autoRefresh single dir failed: ${e.message}")
                }
            }
            for (source in cosSources) {
                try {
                    val uri = source.uriString.toUri()
                    val indexedAtMillis = System.currentTimeMillis()
                    val (newCosMedia, cosMediaAuthorMap, newCosWorks) = scanCosMedia(uri, source.uriString, indexedAtMillis)
                    autoRefreshMedia.addAll(newCosMedia)

                    // 按源互斥：与手动 COS 扫描/增量刷新并发写同一源时防止 stale 误删
                    ScanSourceLocks.withSourceLock(source.uriString) {
                        // 全量 upsert 所有扫描到的媒体（不只是新增），以同步 folderName 变化（结构3修复等）
                        newCosMedia.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
                        repository.upsertCosWorks(newCosWorks)
                        // v1.x：清理该源已消失/改名的作品行（与 refreshCosSource 一致，避免 cos_works 行残留）
                        val currentWorkKeys = newCosWorks.map { "${it.authorName}/${it.workName}" }.toSet()
                        val disappearedWorkIds = repository.getCosWorksBySourceUri(source.uriString)
                            .filter { "${it.authorName}/${it.workName}" !in currentWorkKeys }
                            .map { it.id }
                        if (disappearedWorkIds.isNotEmpty()) {
                            repository.deleteCosWorksByIds(disappearedWorkIds)
                            com.qimeng.media.core.AppLog.d("CosScan", "autoRefresh: cleaned ${disappearedWorkIds.size} disappeared works")
                        }
                        val authorNames = newCosWorks.map { it.authorName }.distinct()
                        val authorEntities = authorNames.map { authorName ->
                            AuthorEntity(authorId = "cos_" + generateAuthorId(authorName), displayName = authorName, createdAtMillis = indexedAtMillis, updatedAtMillis = indexedAtMillis)
                        }
                        repository.upsertAllAuthors(authorEntities)
                        val crossRefs = mutableListOf<AuthorMediaCrossRef>()
                        for ((authorName, files) in cosMediaAuthorMap) {
                            val authorId = "cos_" + generateAuthorId(authorName)
                            for (file in files) {
                                crossRefs.add(AuthorMediaCrossRef(authorId = authorId, recordKey = file.recordKey, fileName = file.fileName, isMatched = true))
                            }
                        }
                        crossRefs.chunked(500).forEach { batch -> repository.upsertAllAuthorMedia(batch) }
                        val scannedKeys = newCosMedia.map { it.recordKey }.toSet()
                        val existingCosKeys = repository.getCosRecordKeysBySourceUri(source.uriString).toSet()
                        val staleKeys = existingCosKeys.filter { it !in scannedKeys }
                        if (staleKeys.isNotEmpty()) {
                            repository.deleteMediaAndRefs(staleKeys)
                            repository.deleteOrphanCosAuthors()
                        }
                        // 循环期间源可能已被用户删除：确认仍存在才写，防止 copy 复活已删源
                        if (repository.getScanSources().any { it.uriString == source.uriString }) {
                            repository.upsertScanSource(source.copy(lastScannedAtMillis = System.currentTimeMillis()))
                        }
                    }
                } catch (e: Exception) {
                    // 取消必须向上透传，不能当普通失败吞掉
                    if (e is CancellationException) throw e
                    com.qimeng.media.core.AppLog.d("Scan", "autoRefresh single COS dir failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // 取消必须向上透传，不能当普通失败吞掉
            if (e is CancellationException) throw e
            // 自动刷新失败静默处理
            com.qimeng.media.core.AppLog.d("Scan", "autoRefreshAllSources failed: ${e.message}")
        }

        // 启动自动刷新后重新匹配已有TXT导入，确保新文件也能被关联到作者
        // （与 scanDirectory/refreshScanSource 行为一致，v1.13 补齐）
        try {
            authorImportUseCase.rematchAllTxtImports(this)
        } catch (e: Exception) {
            // 取消必须向上透传，不能当普通失败吞掉
            if (e is CancellationException) throw e
            AppLog.d("Scan", "autoRefresh rematchAllTxtImports failed: ${e.message}")
        }

        return autoRefreshMedia
    }

    /** 删除扫描源并清理孤立文件 */
    suspend fun deleteScanSource(uriString: String) {
        repository.deleteScanSource(uriString)

        // v1.12：优先按 sourceUri 精确删除（扫描时随文件入库的归属 tree URI）。
        // 旧版用文件 URI 前缀匹配扫描源 tree URI，因命名空间不同（content://media /
        // document / tree）恒匹配失败，导致删除源后文件残留、且启动清理误删全部文件。
        val sourceKeys = repository.getRecordKeysBySourceUri(uriString)
        if (sourceKeys.isNotEmpty()) {
            com.qimeng.media.core.AppLog.d("ScanUseCase", "deleteScanSource: uri=$uriString, sourceKeys=${sourceKeys.size}")
            repository.deleteMediaAndRefs(sourceKeys)
            repository.deleteOrphanAuthors()
            return
        }

        // v1.x 修复：旧回退用「文件 URI startsWith 剩余源 tree URI 前缀」判断归属，
        // 但 content://media / document 文件 URI 与 tree URI 命名空间不同恒不命中，
        // sourceUri==null 的旧版数据全部被当孤儿删除（误删其他源旧数据文件）。
        // 旧版数据无法可靠反推归属，按「宁可残留不可误删」原则保守保留，不做回退删除
        // （与 cleanupOrphanFiles 对 sourceUri==null 保守保留、GUIDE_SCAN.md「删除扫描源」第 3 条一致）。
    }

    /** 删除 COS 扫描源（v1.12：按 sourceUri 精确删除，避免误删其他源作品） */
    suspend fun deleteCosScanSource(uriString: String) {
        com.qimeng.media.core.AppLog.d("ScanUseCase", "deleteCosScanSource: uri=$uriString")
        repository.deleteCosScanSource(uriString)
    }

    /** 启动时清理孤立文件：扫描源已被删除但文件仍残留在数据库中的记录 */
    suspend fun cleanupOrphanFiles() {
        // 清理非COS孤立文件
        val remainingSources = repository.getScanSources().filter { !it.isBackupDirectory && !it.isCosDirectory }
        val activeUris = remainingSources.map { it.uriString }.toSet()
        val allKeysAndUris = repository.getNonCosKeysAndUris()
        val orphanKeys = if (activeUris.isEmpty()) {
            // 无扫描源：新数据（sourceUri 非空）全部视为孤儿；旧数据（sourceUri null）保守保留
            allKeysAndUris.filter { it.sourceUri != null }.map { it.recordKey }
        } else {
            allKeysAndUris.filter { (_, _, sourceUri) ->
                // 优先按归属列精确判断；null 表示旧版数据，无法反推归属，保守保留不删
                sourceUri != null && sourceUri !in activeUris
            }.map { it.recordKey }
        }
        if (orphanKeys.isNotEmpty()) {
            com.qimeng.media.core.AppLog.d("ScanUseCase", "cleanupOrphanFiles: nonCos orphans=${orphanKeys.size}")
            repository.deleteMediaAndRefs(orphanKeys)
            repository.deleteOrphanAuthors()
        }

        // 清理COS孤立文件（扫描源已删除但作品/文件残留）
        val remainingCosSources = repository.getScanSources().filter { it.isCosDirectory }
        val activeCosUris = remainingCosSources.map { it.uriString }.toSet()
        if (activeCosUris.isEmpty()) {
            // 没有COS扫描源，清理所有新版本COS数据；旧数据（sourceUri null）保守保留
            val allCosKeys = repository.getCosKeysAndUris()
                .filter { it.sourceUri != null }
                .map { it.recordKey }
            if (allCosKeys.isNotEmpty()) {
                com.qimeng.media.core.AppLog.d("ScanUseCase", "cleanupOrphanFiles: cos orphans=${allCosKeys.size} (no cos sources)")
                repository.deleteMediaAndRefs(allCosKeys)
                repository.deleteOrphanAuthors()
            }
        } else {
            val orphanCosKeys = repository.getCosKeysAndUris()
                .filter { (_, _, sourceUri) -> sourceUri != null && sourceUri !in activeCosUris }
                .map { it.recordKey }
            if (orphanCosKeys.isNotEmpty()) {
                com.qimeng.media.core.AppLog.d("ScanUseCase", "cleanupOrphanFiles: cos orphans=${orphanCosKeys.size} (source mismatch)")
                repository.deleteMediaAndRefs(orphanCosKeys)
                repository.deleteOrphanAuthors()
            }
        }

        // 清理历史残留：view_stats/view_history/timeline_tags/SharedPreferences 中
        // 引用了已不存在的 recordKey 的数据（修复旧版 deleteMediaAndRefs 未清理的遗留问题）
        cleanupStaleRefs()
    }

    /** 清理引用了已删除文件的残留数据（统计、历史、时间轴标签、SharedPreferences） */
    private suspend fun cleanupStaleRefs() {
        val validKeys = repository.getAllRecordKeys().toSet()
        if (validKeys.isEmpty()) return

        // v1.x：三处残留检测改用 repository 轻量投影（仅取 recordKey 列），
        // 替代旧的 observeAllStats/observeLatestHistory/getAllTimelineTags 全实体加载后
        // 在 Kotlin 侧过滤——对齐 GUIDE_DATA 轻量查询原则，避免大库下无谓全行物化
        // 清理 view_stats 残留
        val staleStatsKeys = repository.getViewStatsRecordKeys()
            .mapNotNull { if (it !in validKeys) it else null }
        if (staleStatsKeys.isNotEmpty()) {
            AppLog.d("ScanUseCase", "cleanupStaleRefs: stale view_stats=${staleStatsKeys.size}")
            repository.deleteMediaAndRefs(staleStatsKeys)
        }

        // 清理 view_history 残留
        val staleHistoryKeys = repository.getHistoryRecordKeys()
            .mapNotNull { if (it !in validKeys) it else null }
        if (staleHistoryKeys.isNotEmpty()) {
            AppLog.d("ScanUseCase", "cleanupStaleRefs: stale view_history=${staleHistoryKeys.size}")
            repository.deleteMediaAndRefs(staleHistoryKeys)
        }

        // 清理 timeline_tags 残留（DAO 查询已 DISTINCT，无需再次去重）
        val staleTimelineKeys = repository.getTimelineTagRecordKeys()
            .mapNotNull { if (it !in validKeys) it else null }
        if (staleTimelineKeys.isNotEmpty()) {
            AppLog.d("ScanUseCase", "cleanupStaleRefs: stale timeline_tags=${staleTimelineKeys.size}")
            repository.deleteMediaAndRefs(staleTimelineKeys)
        }

        // 清理 SharedPreferences 残留（点赞、收藏中引用了已删除文件的 recordKey）
        com.qimeng.media.core.MediaDetailPrefsCleaner.cleanOrphanEntries(application, validKeys)
    }

    /** 尝试使用 MediaStore 快速扫描 */
    private fun tryMediaStoreScan(uri: Uri, sourceUri: String): List<MediaFileEntity>? {
        val filePath = mediaStoreScanner.safUriToFilePath(uri) ?: return null
        val result = mediaStoreScanner.queryByFolderPath(filePath, sourceUri)
        return result.ifEmpty { null }
    }

    /**
     * 跨源 recordKey 冲突重映射（v1.12 修复）。
     *
     * 旧版判重仅限单次扫描的候选集内：两个扫描源各有同名文件（如各有一个 "1.jpg"）时，
     * 各自生成裸文件名 key，@PrimaryKey 的 upsert 相互整行覆盖，被覆盖文件消失且统计/
     * 作者关联丢失。这里在写入前把与**其他源**已占用 key 冲突的文件追加 pathHash 区分，
     * 保证同一 key 只属于一个物理文件。
     *
     * @param mediaFiles 本次扫描的文件（sourceUri 已填充）
     * @param sourceUri 当前扫描源的 tree URI
     */
    private suspend fun resolveRecordKeyConflicts(
        mediaFiles: List<MediaFileEntity>,
        sourceUri: String
    ): List<MediaFileEntity> {
        if (mediaFiles.isEmpty()) return mediaFiles
        // 库中 (key → 归属 sourceUri 集合)：识别跨源同名碰撞。sourceUri 为 null 的旧版
        // 数据无法判断归属，保守视为本源（不触发重映射，避免旧数据统计/历史丢失）。
        val keyOwnerUris = (repository.getNonCosKeysAndUris() + repository.getCosKeysAndUris())
            .groupBy({ it.recordKey }, { it.sourceUri })
            .mapValues { (_, uris) -> uris.filterNotNull().toSet() }
        val usedKeys = mutableSetOf<String>()
        return mediaFiles.map { file ->
            val owners = keyOwnerUris[file.recordKey].orEmpty()
            // 碰撞：库中该 key 已被【其他源】占用（sourceUri 非空且 ≠ 本次源），且本次扫描内未处理过
            if (owners.isNotEmpty() && owners.none { it == sourceUri } && file.recordKey !in usedKeys) {
                val newKey = "${file.recordKey} @ ${file.pathHash.take(8)}"
                AppLog.d("Scan", "resolveRecordKeyConflicts: '${file.recordKey}' -> '$newKey' (跨源同名)")
                usedKeys.add(file.recordKey)
                file.copy(recordKey = newKey, isDuplicateName = true)
            } else {
                usedKeys.add(file.recordKey)
                file
            }
        }
    }

    /** COS 媒体扫描公共方法 */
    private suspend fun scanCosMedia(
        uri: Uri,
        uriString: String,
        indexedAtMillis: Long
    ): Triple<List<MediaFileEntity>, Map<String, List<MediaFileEntity>>, List<CosWorkEntity>> {
        val filePath = mediaStoreScanner.safUriToFilePath(uri)
        AppLog.d("CosScan", "scanCosMedia: safUriToFilePath=$filePath, uri=$uriString")
        val cosResult = if (filePath != null) {
            mediaStoreScanner.queryCosFolder(filePath, uriString)
        } else null
        AppLog.d("CosScan", "scanCosMedia: MediaStore hit=${cosResult != null}, files=${cosResult?.mediaFiles?.size ?: 0}, authors=${cosResult?.authorFileMap?.keys?.joinToString(",") ?: "N/A"}")

        if (cosResult != null) {
            val resolvedMedia = resolveRecordKeyConflicts(cosResult.mediaFiles, uriString)
            val resolvedByUri = resolvedMedia.associateBy { it.uriString }
            // 重映射后 authorFileMap 里的文件引用同步替换为新 key 的对象，保证 crossRefs 用新 key
            val resolvedMap = cosResult.authorFileMap.mapValues { (_, files) ->
                files.mapNotNull { resolvedByUri[it.uriString] }
            }
            val works = cosResult.works.map { work ->
                CosWorkEntity(
                    authorName = work.authorName,
                    workName = work.workName,
                    folderUri = work.folderPath,
                    // v1.x 修复：fileCount 取该作品文件数，而非该作者全部作品文件数（一作者多作品会超计）
                    fileCount = cosResult.workFileCounts["${work.authorName}/${work.workName}"] ?: 0,
                    sourceUri = uriString,
                    indexedAtMillis = indexedAtMillis
                )
            }
            return Triple(resolvedMedia, resolvedMap, works)
        }

        // SAF 根目录打开失败（权限撤销等）必须按错误处理：返回空结果会被上层
        // 当"成功 0 文件"整源删除，抛错由外层 catch 走 Error 分支
        val root = DocumentFile.fromTreeUri(application, uri)
            ?: error("无法访问 COS 目录: $uriString")
        AppLog.d("CosScan", "SAF fallback: scanning entire tree, rootUri=$uriString")
        val allSafMedia = mediaScanner.scanTreeFast(root.uri, uriString).map { it.copy(isCosFile = true) }
        AppLog.d("CosScan", "SAF fallback: found ${allSafMedia.size} files")

        allSafMedia.take(3).forEach { file ->
            AppLog.d("CosScan", "SAF sample uri: ${file.uriString}")
        }

        val safAuthorMap = mutableMapOf<String, MutableList<MediaFileEntity>>()
        val safWorks = mutableListOf<CosWorkEntity>()
        val seenWorks = mutableSetOf<String>()
        // v1.x：workKey("作者/作品") → 该作品文件数，用于 fileCount 精确统计（对齐 MediaStore 分支）
        val safWorkFileCounts = mutableMapOf<String, Int>()
        // 收集 uriString → workName，用于将结构3子文件夹文件的 folderName 覆写为 workName
        val safWorkNameByUri = mutableMapOf<String, String>()
        // 根目录直接文件（无作者/作品层级）：跳过作者归属且不入库，
        // 防止随 resolvedMedia 入库成无作者归属的不可见记录
        val rootLevelUris = mutableSetOf<String>()

        val rootDocumentId = try {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            com.qimeng.media.core.AppLog.d("Scan", "getTreeDocumentId failed: ${e.message}")
            null
        }
        AppLog.d("CosScan", "SAF rootDocumentId=$rootDocumentId")

        for (file in allSafMedia) {
            val fileUri = file.uriString
            val docSegment = fileUri.substringAfter("/document/")
            val decodedDocSegment = try { java.net.URLDecoder.decode(docSegment, "UTF-8") } catch (_: Exception) { docSegment }

            val afterVolume = decodedDocSegment.substringAfter(":")
            val rootRelPath = rootDocumentId?.substringAfter(":") ?: ""
            val afterRoot = if (rootRelPath.isNotBlank() && afterVolume.startsWith(rootRelPath)) {
                afterVolume.removePrefix(rootRelPath).removePrefix("/")
            } else {
                afterVolume
            }

            val pathWithoutFile = afterRoot.substringBeforeLast("/")
            if (pathWithoutFile.isBlank()) {
                rootLevelUris.add(fileUri)
                continue
            }

            val segments = pathWithoutFile.split("/")
            val authorName = segments.getOrNull(0)?.trim().orEmpty()
            if (authorName.isBlank()) continue

            safAuthorMap.getOrPut(authorName) { mutableListOf() }.add(file)

            val workName = if (segments.size > 1) segments[1].trim() else authorName
            safWorkNameByUri[fileUri] = workName
            val workKey = "$authorName/$workName"
            // v1.x：按作品统计文件数（fix #9：fileCount 应为该作品文件数，而非作者全部作品文件数）
            safWorkFileCounts[workKey] = (safWorkFileCounts[workKey] ?: 0) + 1
            if (seenWorks.add(workKey)) {
                val workPath = if (segments.size > 1) {
                    "$rootRelPath/$authorName/$workName"
                } else {
                    "$rootRelPath/$authorName"
                }
                safWorks.add(CosWorkEntity(
                    authorName = authorName,
                    workName = workName,
                    folderUri = workPath,
                    fileCount = 0,
                    indexedAtMillis = indexedAtMillis
                ))
            }
        }
        // 覆写 folderName 为 workName：修复结构3（作者/作品/p1/文件）中 folderName="p1/GIF" 匹配不上 workName 的问题。
        // 根级文件（rootLevelUris）整行剔除，不入库
        val updatedAllSafMedia = allSafMedia
            .filter { it.uriString !in rootLevelUris }
            .map { file ->
                val wm = safWorkNameByUri[file.uriString]
                if (wm != null) file.copy(folderName = wm) else file
            }
        val updatedWorks = safWorks.map { work ->
            // v1.x 修复：fileCount 取该作品文件数（原先取作者全部作品文件数，一作者多作品会超计）
            work.copy(fileCount = safWorkFileCounts["${work.authorName}/${work.workName}"] ?: 0)
        }
        // 跨源 recordKey 冲突重映射（与常规源共享 media_files 主键表）
        val resolvedMedia = resolveRecordKeyConflicts(updatedAllSafMedia, uriString)
        val resolvedByUri = resolvedMedia.associateBy { it.uriString }
        val resolvedMap = safAuthorMap.mapValues { (_, files) ->
            files.mapNotNull { resolvedByUri[it.uriString] }
        }
        return Triple(resolvedMedia, resolvedMap, updatedWorks)
    }

    /** 生成 authorId */
    fun generateAuthorId(name: String): String {
        val normalized = name.trim().lowercase().replace("\\s+".toRegex(), "_")
        val cleaned = normalized.replace("[^\\p{L}\\p{N}_]".toRegex(), "")
        return if (cleaned.isNotBlank()) cleaned.take(64) else {
            val hash = Integer.toHexString(name.hashCode()).lowercase()
            "author_$hash"
        }
    }

    companion object {
        /** 自动刷新防抖：常规目录 30 秒内不重复刷新 */
        private const val AUTO_REFRESH_INTERVAL = 30_000L
        /** 自动刷新防抖：COS 目录 5 分钟内不重复刷新（5000+ 文件扫描成本高，频繁刷新得不偿失） */
        private const val COS_AUTO_REFRESH_INTERVAL = 300_000L
    }
}

/** 扫描操作结果，用于 UseCase 向 ViewModel 返回结构化数据 */
sealed class ScanResult {
    abstract val mediaFiles: List<MediaFileEntity>
    data class Success(
        val count: Int,
        val directoryCount: Int = 1,
        override val mediaFiles: List<MediaFileEntity>,
        /** v1.7：扫描警告信息（大文件/大总量），null 表示无警告 */
        val warning: String? = null
    ) : ScanResult()
    data class Error(val message: String, override val mediaFiles: List<MediaFileEntity>) : ScanResult()

    companion object {
        /** v1.7 大文件扫描警告阈值 */
        const val LARGE_FILE_THRESHOLD_BYTES = 100L * 1024 * 1024 // 100MB
        const val LARGE_TOTAL_THRESHOLD_BYTES = 10L * 1024 * 1024 * 1024 // 10GB

        /**
         * v1.7：检测扫描结果是否需要警告（单文件 >100MB 或总量 >10GB）。
         * 返回警告文案，无警告返回 null。
         */
        fun buildScanWarning(mediaFiles: List<MediaFileEntity>): String? {
            if (mediaFiles.isEmpty()) return null
            val totalBytes = mediaFiles.sumOf { it.sizeBytes }
            val largeFiles = mediaFiles.count { it.sizeBytes > LARGE_FILE_THRESHOLD_BYTES }

            val warnings = mutableListOf<String>()
            if (totalBytes > LARGE_TOTAL_THRESHOLD_BYTES) {
                val totalGB = totalBytes / (1024.0 * 1024 * 1024)
                warnings.add("扫描总量约 ${String.format(java.util.Locale.US, "%.1f", totalGB)} GB")
            }
            if (largeFiles > 0) {
                warnings.add("含 $largeFiles 个大文件(>100MB)")
            }
            return if (warnings.isEmpty()) null else warnings.joinToString("，") + "，扫描可能耗时"
        }
    }
}
