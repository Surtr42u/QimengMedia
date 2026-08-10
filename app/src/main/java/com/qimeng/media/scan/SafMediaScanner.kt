package com.qimeng.media.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SafMediaScanner(private val context: Context) {

    /**
     * 快速扫描目录树，跳过图片尺寸和视频元数据解码（仅读取视频时长）。
     * 当前唯一使用的扫描方法，scanTree（含完整元数据解码）已移除。
     *
     * @param sourceUri 归属扫描源的 tree URI（随文件入库，供按源删除/清理，v1.12）
     */
    suspend fun scanTreeFast(rootUri: Uri, sourceUri: String): List<MediaFileEntity> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, rootUri)
        require(root != null && root.isDirectory) { "无法读取选择的文件夹" }
        val rootName = root.name?.ifBlank { null } ?: "Root"
        val indexedAtMillis = System.currentTimeMillis()
        val candidates = buildList {
            scanDirectoryFast(directory = root, folderName = rootName, output = this)
        }
        ScanUtils.buildEntities(candidates, indexedAtMillis, sourceUri)
    }

    private fun scanDirectoryFast(
        directory: DocumentFile,
        folderName: String,
        output: MutableList<MediaCandidate>
    ) {
        for (child in safeListFiles(directory)) {
            when {
                child.isDirectory -> {
                    val childFolderName = child.name?.ifBlank { null } ?: folderName
                    scanDirectoryFast(child, childFolderName, output)
                }
                child.isFile -> {
                    val fileName = child.name?.trim().orEmpty()
                    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
                        .lowercase(Locale.ROOT)
                    val mediaType = ScanUtils.mediaTypeForExtension(extension) ?: continue
                    val uri = child.uri
                    val pathHash = ScanUtils.shortHash(uri.toString())
                    // 视频文件异步读取时长（轻量操作，不读帧）
                    val durationMillis = if (mediaType == MediaType.VIDEO) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            retriever.extractLong(MediaMetadataRetriever.METADATA_KEY_DURATION)?.takeIf { it > 0 }
                        } catch (_: Exception) { null } finally { retriever.release() }
                    } else null
                    output += MediaCandidate(
                        fileName = fileName,
                        displayName = fileName.substringBeforeLast('.', fileName),
                        extension = extension,
                        mediaType = mediaType,
                        uriString = uri.toString(),
                        folderName = folderName,
                        // SAF 分支在 ScanUseCase.scanCosMedia 用 uriString document 段解析
                        // COS 作者/作品，不依赖 directoryPath，填根相对路径即可
                        directoryPath = "",
                        pathHash = pathHash,
                        sizeBytes = child.length().coerceAtLeast(0L),
                        modifiedAtMillis = child.lastModified().takeIf { it > 0L } ?: 0L,
                        width = null,
                        height = null,
                        durationMillis = durationMillis
                    )
                }
            }
        }
    }

    private fun MediaMetadataRetriever.extractLong(keyCode: Int): Long? =
        extractMetadata(keyCode)?.toLongOrNull()?.takeIf { it > 0L }

    /**
     * 安全列出目录内容：单个子目录访问失败（权限被拒、IO 异常等）时跳过该目录继续扫描，
     * 不中断整个扫描流程。v1.x：由仅捕获 SecurityException 扩展为捕获 IOException 等所有异常。
     */
    private fun safeListFiles(directory: DocumentFile): Array<DocumentFile> = try {
        directory.listFiles()
    } catch (e: Exception) {
        AppLog.w("SafScan", "listFiles failed, skip directory: ${directory.uri}", e)
        emptyArray()
    }
}
