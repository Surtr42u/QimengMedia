package com.qimeng.media.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SafMediaScanner(private val context: Context) {
    private val resolver = context.contentResolver

    /**
     * 快速扫描目录树，跳过图片尺寸和视频元数据解码（仅读取视频时长）。
     * 当前唯一使用的扫描方法，scanTree（含完整元数据解码）已移除。
     */
    suspend fun scanTreeFast(rootUri: Uri): List<MediaFileEntity> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, rootUri)
        require(root != null && root.isDirectory) { "无法读取选择的文件夹" }
        val rootName = root.name?.ifBlank { null } ?: "Root"
        val indexedAtMillis = System.currentTimeMillis()
        val candidates = buildList {
            scanDirectoryFast(directory = root, folderName = rootName, output = this)
        }
        ScanUtils.buildEntities(candidates, indexedAtMillis)
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

    private fun MediaMetadataRetriever.extractInt(keyCode: Int): Int? =
        extractMetadata(keyCode)?.toIntOrNull()?.takeIf { it > 0 }

    private fun MediaMetadataRetriever.extractLong(keyCode: Int): Long? =
        extractMetadata(keyCode)?.toLongOrNull()?.takeIf { it > 0L }

    private fun safeListFiles(directory: DocumentFile): Array<DocumentFile> = try {
        directory.listFiles()
    } catch (_: SecurityException) {
        emptyArray()
    }
}
