package com.qimeng.media.domain

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.ScanSourceEntity
import com.qimeng.media.data.repository.LocalMediaRepository
import com.qimeng.media.scan.MediaStoreScanner
import com.qimeng.media.scan.SafMediaScanner
import kotlinx.coroutines.flow.firstOrNull

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
    private val AUTO_REFRESH_INTERVAL = 30_000L // 30秒内不重复自动刷新
    private val COS_AUTO_REFRESH_INTERVAL = 300_000L // COS目录5分钟内不重复自动刷新

    /** 扫描指定目录，返回扫描结果和扫描到的媒体文件列表 */
    suspend fun scanDirectory(uri: Uri, displayName: String? = null): ScanResult {
        val startedAtMillis = System.currentTimeMillis()
        AppLog.d("Scan", "scanDirectory: start, uri=$uri")
        var scannedMedia = emptyList<MediaFileEntity>()

        try {
            val uriString = uri.toString()
            val mediaFiles = tryMediaStoreScan(uri) ?: mediaScanner.scanTreeFast(uri)
            scannedMedia = mediaFiles

            val sourceName = displayName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: mediaStoreScanner.safUriToFilePath(uri)?.substringAfterLast('/')
                ?: DocumentFile.fromTreeUri(application, uri)?.name
                ?: "授权目录"

            mediaFiles.chunked(500).forEach { batch -> repository.upsertMedia(batch) }

            val scannedKeys = mediaFiles.map { it.recordKey }.toSet()
            val prefix = if (uriString.endsWith("/")) uriString else "$uriString/"
            val existingKeys = repository.getRecordKeysByUriPrefix(prefix).toSet()
            val staleKeys = existingKeys.filter { it !in scannedKeys }
            if (staleKeys.isNotEmpty()) {
                repository.deleteMediaAndRefs(staleKeys)
                repository.deleteOrphanCosAuthors()
            }

            repository.upsertScanSource(ScanSourceEntity(
                uriString = uriString,
                displayName = sourceName,
                isBackupDirectory = false,
                addedAtMillis = startedAtMillis,
                lastScannedAtMillis = System.currentTimeMillis()
            ))

            // 扫描完成后重新匹配已有TXT导入，确保新文件也能被关联到作者
            try {
                authorImportUseCase.rematchAllTxtImports(this)
            } catch (e: Exception) {
                AppLog.d("Scan", "rematchAllTxtImports failed: ${e.message}")
            }

            return ScanResult.Success(mediaFiles.size, 1, scannedMedia)
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
            val mediaFiles = tryMediaStoreScan(uri) ?: mediaScanner.scanTreeFast(uri)
            refreshMedia = mediaFiles

            val prefix = if (uriString.endsWith("/")) uriString else "$uriString/"
            val existingKeys = repository.getRecordKeysByUriPrefix(prefix).toSet()

            val newOrUpdated = mediaFiles.filter { it.recordKey !in existingKeys }
            if (newOrUpdated.isNotEmpty()) {
                newOrUpdated.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
            }

            val scannedKeys = mediaFiles.map { it.recordKey }.toSet()
            val staleKeys = existingKeys.filter { it !in scannedKeys }
            if (staleKeys.isNotEmpty()) {
                repository.deleteMediaAndRefs(staleKeys)
                repository.deleteOrphanCosAuthors()
            }

            val existing = repository.getScanSources().find { it.uriString == uriString }
            repository.upsertScanSource(ScanSourceEntity(
                uriString = uriString,
                displayName = existing?.displayName ?: "",
                isBackupDirectory = false,
                addedAtMillis = existing?.addedAtMillis ?: System.currentTimeMillis(),
                lastScannedAtMillis = System.currentTimeMillis()
            ))

            // 刷新完成后重新匹配已有TXT导入，确保新文件也能被关联到作者
            try {
                authorImportUseCase.rematchAllTxtImports(this)
            } catch (e: Exception) {
                AppLog.d("Scan", "rematchAllTxtImports failed: ${e.message}")
            }

            return ScanResult.Success(newOrUpdated.size, 1, refreshMedia)
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

            newCosMedia.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
            repository.upsertCosWorks(newCosWorks)

            val scannedKeys = newCosMedia.map { it.recordKey }.toSet()
            val prefix = if (uriString.endsWith("/")) uriString else "$uriString/"
            val existingCosKeys = repository.getCosRecordKeysByUriPrefix(prefix).toSet()
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

            AppLog.d("CosScan", "scanCosDirectory: success, files=${newCosMedia.size}, authors=${newCosWorks.map { it.authorName }.distinct().size}, works=${newCosWorks.size}")
            return ScanResult.Success(newCosMedia.size, 1, cosScanMedia)
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

            val prefix = if (uriString.endsWith("/")) uriString else "$uriString/"
            val existingCosKeys = repository.getCosRecordKeysByUriPrefix(prefix).toSet()

            val newFiles = newCosMedia.filter { it.recordKey !in existingCosKeys }
            if (newFiles.isNotEmpty()) {
                newFiles.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
            }

            repository.upsertCosWorks(newCosWorks)

            val scannedKeys = newCosMedia.map { it.recordKey }.toSet()
            val staleKeys = existingCosKeys.filter { it !in scannedKeys }
            if (staleKeys.isNotEmpty()) {
                repository.deleteMediaAndRefs(staleKeys)
                repository.deleteOrphanCosAuthors()
            }

            val existingCos = repository.getScanSources().find { it.uriString == uriString }
            repository.upsertScanSource(ScanSourceEntity(
                uriString = uriString,
                displayName = existingCos?.displayName ?: "",
                isCosDirectory = true,
                addedAtMillis = existingCos?.addedAtMillis ?: System.currentTimeMillis(),
                lastScannedAtMillis = System.currentTimeMillis()
            ))

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

            return ScanResult.Success(newFiles.size, 1, cosRefreshMedia)
        } catch (e: Exception) {
            return ScanResult.Error(e.message ?: "COS刷新失败", cosRefreshMedia)
        }
    }

    /** 自动增量刷新所有已添加目录（App启动/恢复时调用，带防抖） */
    suspend fun autoRefreshAllSources(): List<MediaFileEntity> {
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
                    val mediaFiles = tryMediaStoreScan(uri) ?: mediaScanner.scanTreeFast(uri)
                    autoRefreshMedia.addAll(mediaFiles)
                    val prefix = if (source.uriString.endsWith("/")) source.uriString else "${source.uriString}/"
                    val existingKeys = repository.getRecordKeysByUriPrefix(prefix).toSet()
                    val newFiles = mediaFiles.filter { it.recordKey !in existingKeys }
                    if (newFiles.isNotEmpty()) {
                        newFiles.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
                    }
                    val scannedKeys = mediaFiles.map { it.recordKey }.toSet()
                    val staleKeys = existingKeys.filter { it !in scannedKeys }
                    if (staleKeys.isNotEmpty()) {
                        repository.deleteMediaAndRefs(staleKeys)
                    }
                    repository.upsertScanSource(source.copy(lastScannedAtMillis = System.currentTimeMillis()))
                } catch (e: Exception) { com.qimeng.media.core.AppLog.d("Scan", "autoRefresh single dir failed: ${e.message}") }
            }
            for (source in cosSources) {
                try {
                    val uri = source.uriString.toUri()
                    val indexedAtMillis = System.currentTimeMillis()
                    val (newCosMedia, cosMediaAuthorMap, newCosWorks) = scanCosMedia(uri, source.uriString, indexedAtMillis)
                    autoRefreshMedia.addAll(newCosMedia)

                    val prefix = if (source.uriString.endsWith("/")) source.uriString else "${source.uriString}/"
                    val existingCosKeys = repository.getCosRecordKeysByUriPrefix(prefix).toSet()
                    val newFiles = newCosMedia.filter { it.recordKey !in existingCosKeys }
                    if (newFiles.isNotEmpty()) {
                        newFiles.chunked(500).forEach { batch -> repository.upsertMedia(batch) }
                    }
                    repository.upsertCosWorks(newCosWorks)
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
                    val staleKeys = existingCosKeys.filter { it !in scannedKeys }
                    if (staleKeys.isNotEmpty()) {
                        repository.deleteMediaAndRefs(staleKeys)
                        repository.deleteOrphanCosAuthors()
                    }
                    repository.upsertScanSource(source.copy(lastScannedAtMillis = System.currentTimeMillis()))
                } catch (e: Exception) { com.qimeng.media.core.AppLog.d("Scan", "autoRefresh single COS dir failed: ${e.message}") }
            }
        } catch (e: Exception) {
            // 自动刷新失败静默处理
            com.qimeng.media.core.AppLog.d("Scan", "autoRefreshAllSources failed: ${e.message}")
        }

        return autoRefreshMedia
    }

    /** 删除扫描源并清理孤立文件 */
    suspend fun deleteScanSource(uriString: String) {
        repository.deleteScanSource(uriString)
        val remainingSources = repository.getScanSources().filter { !it.isBackupDirectory }
        val activePrefixes = remainingSources.map { src ->
            if (src.uriString.endsWith("/")) src.uriString else "${src.uriString}/"
        }
        val allKeysAndUris = repository.getNonCosKeysAndUris()
        val orphanKeys = if (activePrefixes.isEmpty()) {
            allKeysAndUris.map { it.recordKey }
        } else {
            allKeysAndUris.filter { (_, uri) ->
                activePrefixes.none { prefix -> uri.startsWith(prefix) }
            }.map { it.recordKey }
        }
        com.qimeng.media.core.AppLog.d("ScanUseCase", "deleteScanSource: uri=$uriString, remaining=${activePrefixes.size}, allNonCos=${allKeysAndUris.size}, orphans=${orphanKeys.size}")
        if (orphanKeys.isNotEmpty()) {
            repository.deleteMediaAndRefs(orphanKeys)
            repository.deleteOrphanAuthors()
        }
    }

    /** 删除 COS 扫描源 */
    suspend fun deleteCosScanSource(uriString: String) {
        com.qimeng.media.core.AppLog.d("ScanUseCase", "deleteCosScanSource: uri=$uriString")
        repository.deleteCosScanSource(uriString)
    }

    /** 启动时清理孤立文件：扫描源已被删除但文件仍残留在数据库中的记录 */
    suspend fun cleanupOrphanFiles() {
        // 清理非COS孤立文件
        val remainingSources = repository.getScanSources().filter { !it.isBackupDirectory && !it.isCosDirectory }
        val activePrefixes = remainingSources.map { src ->
            if (src.uriString.endsWith("/")) src.uriString else "${src.uriString}/"
        }
        val allKeysAndUris = repository.getNonCosKeysAndUris()
        val orphanKeys = if (activePrefixes.isEmpty()) {
            allKeysAndUris.map { it.recordKey }
        } else {
            allKeysAndUris.filter { (_, uri) ->
                activePrefixes.none { prefix -> uri.startsWith(prefix) }
            }.map { it.recordKey }
        }
        if (orphanKeys.isNotEmpty()) {
            com.qimeng.media.core.AppLog.d("ScanUseCase", "cleanupOrphanFiles: nonCos orphans=${orphanKeys.size}")
            repository.deleteMediaAndRefs(orphanKeys)
            repository.deleteOrphanAuthors()
        }

        // 清理COS孤立文件（扫描源已删除但作品/文件残留）
        val remainingCosSources = repository.getScanSources().filter { it.isCosDirectory }
        val activeCosUris = remainingCosSources.map { src ->
            if (src.uriString.endsWith("/")) src.uriString else "${src.uriString}/"
        }
        if (activeCosUris.isEmpty()) {
            // 没有COS扫描源，清理所有COS数据
            val allCosKeys = repository.getCosKeysAndUris().map { it.recordKey }
            if (allCosKeys.isNotEmpty()) {
                com.qimeng.media.core.AppLog.d("ScanUseCase", "cleanupOrphanFiles: cos orphans=${allCosKeys.size} (no cos sources)")
                repository.deleteMediaAndRefs(allCosKeys)
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

        // 清理 view_stats 残留
        val allStats = repository.observeAllStats().firstOrNull().orEmpty()
        val staleStatsKeys = allStats.mapNotNull { if (it.recordKey !in validKeys) it.recordKey else null }
        if (staleStatsKeys.isNotEmpty()) {
            AppLog.d("ScanUseCase", "cleanupStaleRefs: stale view_stats=${staleStatsKeys.size}")
            repository.deleteMediaAndRefs(staleStatsKeys)
        }

        // 清理 view_history 残留
        val allHistory = repository.observeLatestHistory().firstOrNull().orEmpty()
        val staleHistoryKeys = allHistory.mapNotNull { if (it.recordKey !in validKeys) it.recordKey else null }
        if (staleHistoryKeys.isNotEmpty()) {
            AppLog.d("ScanUseCase", "cleanupStaleRefs: stale view_history=${staleHistoryKeys.size}")
            repository.deleteMediaAndRefs(staleHistoryKeys)
        }

        // 清理 timeline_tags 残留
        val allTimelineTags = repository.getAllTimelineTags()
        val staleTimelineKeys = allTimelineTags.mapNotNull { if (it.recordKey !in validKeys) it.recordKey else null }.distinct()
        if (staleTimelineKeys.isNotEmpty()) {
            AppLog.d("ScanUseCase", "cleanupStaleRefs: stale timeline_tags=${staleTimelineKeys.size}")
            repository.deleteMediaAndRefs(staleTimelineKeys)
        }

        // 清理 SharedPreferences 残留（点赞、收藏中引用了已删除文件的 recordKey）
        com.qimeng.media.core.MediaDetailPrefsCleaner.cleanOrphanEntries(application, validKeys)
    }

    /** 尝试使用 MediaStore 快速扫描 */
    private fun tryMediaStoreScan(uri: Uri): List<MediaFileEntity>? {
        val filePath = mediaStoreScanner.safUriToFilePath(uri) ?: return null
        val result = mediaStoreScanner.queryByFolderPath(filePath)
        return result.ifEmpty { null }
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
            mediaStoreScanner.queryCosFolder(filePath)
        } else null
        AppLog.d("CosScan", "scanCosMedia: MediaStore hit=${cosResult != null}, files=${cosResult?.mediaFiles?.size ?: 0}, authors=${cosResult?.authorFileMap?.keys?.joinToString(",") ?: "N/A"}")

        if (cosResult != null) {
            val works = cosResult.works.map { work ->
                CosWorkEntity(authorName = work.authorName, workName = work.workName, folderUri = work.folderPath, fileCount = cosResult.authorFileMap[work.authorName]?.size ?: 0, indexedAtMillis = indexedAtMillis)
            }
            return Triple(cosResult.mediaFiles, cosResult.authorFileMap, works)
        }

        val root = DocumentFile.fromTreeUri(application, uri)
            ?: return Triple(emptyList(), emptyMap(), emptyList())
        AppLog.d("CosScan", "SAF fallback: scanning entire tree, rootUri=$uriString")
        val allSafMedia = mediaScanner.scanTreeFast(root.uri).map { it.copy(isCosFile = true) }
        AppLog.d("CosScan", "SAF fallback: found ${allSafMedia.size} files")

        allSafMedia.take(3).forEach { file ->
            AppLog.d("CosScan", "SAF sample uri: ${file.uriString}")
        }

        val safAuthorMap = mutableMapOf<String, MutableList<MediaFileEntity>>()
        val safWorks = mutableListOf<CosWorkEntity>()
        val seenWorks = mutableSetOf<String>()

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
            if (pathWithoutFile.isBlank()) continue

            val segments = pathWithoutFile.split("/")
            val authorName = segments.getOrNull(0)?.trim().orEmpty()
            if (authorName.isBlank()) continue

            safAuthorMap.getOrPut(authorName) { mutableListOf() }.add(file)

            val workName = if (segments.size > 1) segments[1].trim() else authorName
            val workKey = "$authorName/$workName"
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
        val updatedWorks = safWorks.map { work ->
            work.copy(fileCount = safAuthorMap[work.authorName]?.size ?: 0)
        }
        return Triple(allSafMedia, safAuthorMap, updatedWorks)
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
}

/** 扫描操作结果，用于 UseCase 向 ViewModel 返回结构化数据 */
sealed class ScanResult {
    abstract val mediaFiles: List<MediaFileEntity>
    data class Success(val count: Int, val directoryCount: Int = 1, override val mediaFiles: List<MediaFileEntity>) : ScanResult()
    data class Error(val message: String, override val mediaFiles: List<MediaFileEntity>) : ScanResult()
}
