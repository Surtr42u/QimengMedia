package com.qimeng.media.scan

import android.content.Context
import android.provider.MediaStore
import android.net.Uri
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.MediaFileEntity
import java.util.Locale

/**
 * 基于 MediaStore 的快速媒体扫描器。
 *
 * 与 SafMediaScanner（DocumentFile 递归遍历）不同，本扫描器直接查询系统媒体数据库，
 * 毫秒级返回结果，无需逐文件 IPC 调用。
 *
 * 适用场景：常规扫描目录 + COS 目录。
 * 回退方案：MediaStore 查不到时使用 SafMediaScanner。
 */
class MediaStoreScanner(private val context: Context) {

    private val resolver = context.contentResolver

    /**
     * 一次性查询整个 COS 根目录，从文件路径中解析出作者名和作品名。
     * 相比逐作品文件夹查询（188次 MediaStore 查询），此方法只需 2 次查询（图片+视频）。
     *
     * @param rootFolderPath COS 根目录的文件系统路径
     * @return CosScanResult 包含所有媒体文件、作者→文件映射、作品列表
     */
    fun queryCosFolder(rootFolderPath: String): CosScanResult? {
        val normalizedPath = rootFolderPath.trimEnd('/')
        val indexedAtMillis = System.currentTimeMillis()
        AppLog.d("CosScan", "queryCosFolder: path=$normalizedPath")

        // 诊断：先查父目录看 MediaStore 是否有数据
        val parentPath = normalizedPath.substringBeforeLast("/")
        if (parentPath.isNotBlank() && parentPath != normalizedPath) {
            val parentCount = countMediaInPath(parentPath)
            AppLog.d("CosScan", "queryCosFolder: parent=$parentPath, parentCount=$parentCount")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATA
        )

        val candidates = mutableListOf<MediaCandidate>()

        queryMedia(
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection = projection,
            pathPrefix = normalizedPath,
            output = candidates
        )

        val videoProjection = projection.toMutableList().apply {
            if (!contains(MediaStore.Video.VideoColumns.DURATION)) {
                add(MediaStore.Video.VideoColumns.DURATION)
            }
        }.toTypedArray()
        queryMedia(
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection = videoProjection,
            pathPrefix = normalizedPath,
            output = candidates
        )

        if (candidates.isEmpty()) return null

        // 从文件路径解析作者名和作品名
        // 路径格式: /storage/emulated/0/.../COS根目录/作者名/作品名/文件.jpg
        // 或: /storage/emulated/0/.../COS根目录/作者名/文件.jpg（无作品子目录）
        val rootPrefix = "$normalizedPath/"
        val authorFileMap = mutableMapOf<String, MutableList<MediaFileEntity>>()
        val works = mutableListOf<CosWorkInfo>()
        // 收集 uriString → workName，用于将结构3子文件夹文件的 folderName 覆写为 workName
        val workNameByUri = mutableMapOf<String, String>()

        // 先构建所有 entity
        val entities = ScanUtils.buildEntities(candidates, indexedAtMillis).map { it.copy(isCosFile = true) }

        // 建立 uriString → candidate 映射，避免 O(n²) 查找
        val candidateByUri = candidates.associateBy { it.uriString }

        for (entity in entities) {
            // 从 filePath 中提取作者名
            val candidate = candidateByUri[entity.uriString] ?: continue
            val relativePath = candidate.folderName.removePrefix(rootPrefix)
            val segments = relativePath.split("/")

            // segments[0] = 作者名, segments[1] = 作品名或文件名, segments[2+] = 子目录或文件名
            val authorName = segments.getOrNull(0)?.trim().orEmpty()
            if (authorName.isBlank()) continue

            authorFileMap.getOrPut(authorName) { mutableListOf() }.add(entity)

            // 判断作品名：如果 segments[1] 是目录（有 segments[2]），则作品名=segments[1]；否则作品名=作者名
            val workName = if (segments.size > 2) segments[1].trim() else authorName
            workNameByUri[candidate.uriString] = workName
            val workPath = if (segments.size > 2) "$rootPrefix$authorName/$workName" else "$rootPrefix$authorName"

            // 去重添加作品
            if (works.none { it.authorName == authorName && it.workName == workName }) {
                works.add(CosWorkInfo(authorName = authorName, workName = workName, folderPath = workPath))
            }
        }

        // 覆写 folderName 为 workName：修复结构3（作者/作品/p1/文件）中 folderName="p1" 匹配不上 workName 的问题
        val updatedEntities = entities.map { entity ->
            val wm = workNameByUri[entity.uriString]
            if (wm != null) entity.copy(folderName = wm) else entity
        }

        return CosScanResult(
            mediaFiles = updatedEntities,
            authorFileMap = authorFileMap,
            works = works
        )
    }

    /**
     * COS 扫描结果
     */
    data class CosScanResult(
        val mediaFiles: List<MediaFileEntity>,
        val authorFileMap: Map<String, List<MediaFileEntity>>,
        val works: List<CosWorkInfo>
    )

    /**
     * COS 作品信息（用于构建 CosWorkEntity）
     */
    data class CosWorkInfo(
        val authorName: String,
        val workName: String,
        val folderPath: String
    )

    /**
     * 查询指定目录路径下的所有媒体文件。
     * @param folderPath 文件系统路径，如 "/storage/emulated/0/Pictures/某文件夹"
     * @return 匹配的 MediaFileEntity 列表
     */
    fun queryByFolderPath(folderPath: String): List<MediaFileEntity> {
        val normalizedPath = folderPath.trimEnd('/')
        val indexedAtMillis = System.currentTimeMillis()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATA
        )

        // 查询图片和视频
        val candidates = mutableListOf<MediaCandidate>()

        // 查询图片
        queryMedia(
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection = projection,
            pathPrefix = normalizedPath,
            output = candidates
        )

        // 查询视频
        val videoProjection = projection.toMutableList().apply {
            if (!contains(MediaStore.Video.VideoColumns.DURATION)) {
                add(MediaStore.Video.VideoColumns.DURATION)
            }
        }.toTypedArray()
        queryMedia(
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection = videoProjection,
            pathPrefix = normalizedPath,
            output = candidates
        )

        return ScanUtils.buildEntities(candidates, indexedAtMillis)
    }

    private fun queryMedia(
        uri: Uri,
        projection: Array<String>,
        pathPrefix: String?,
        output: MutableList<MediaCandidate>
    ) {
        val selection = if (pathPrefix != null) {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        } else null
        val selectionArgs = if (pathPrefix != null) {
            arrayOf("$pathPrefix/%")
        } else null

        resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            AppLog.d("CosScan", "queryMedia: uri=$uri, pathPrefix=$pathPrefix, count=${cursor.count}")
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val durationCol = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)

            while (cursor.moveToNext()) {
                val fileName = cursor.getString(nameCol)?.trim().orEmpty()
                if (fileName.isEmpty()) continue

                val filePath = cursor.getString(dataCol).orEmpty()
                val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

                // 验证扩展名并根据扩展名确定实际类型（GIF在MediaStore.Images中但应为ANIMATED_IMAGE）
                val actualMediaType = ScanUtils.mediaTypeForExtension(extension) ?: continue

                val id = cursor.getLong(idCol)
                val contentUri = if (actualMediaType == com.qimeng.media.data.model.MediaType.IMAGE || actualMediaType == com.qimeng.media.data.model.MediaType.ANIMATED_IMAGE) {
                    Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                } else {
                    Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                }

                // 从文件路径提取文件夹名
                val folderName = filePath.substringBeforeLast('/', "").substringAfterLast('/', "")

                output += MediaCandidate(
                    fileName = fileName,
                    displayName = fileName.substringBeforeLast('.', fileName),
                    extension = extension,
                    mediaType = actualMediaType,
                    uriString = contentUri.toString(),
                    folderName = folderName.ifBlank { "Unknown" },
                    pathHash = ScanUtils.shortHash(filePath),
                    sizeBytes = cursor.getLong(sizeCol).coerceAtLeast(0L),
                    modifiedAtMillis = cursor.getLong(dateCol).let { if (it > 0) it * 1000L else 0L },
                    width = cursor.getInt(widthCol).takeIf { it > 0 },
                    height = cursor.getInt(heightCol).takeIf { it > 0 },
                    durationMillis = durationCol.takeIf { it >= 0 }?.let { cursor.getLong(it) }?.takeIf { it > 0 }
                )
            }
        }
    }

    /**
     * 统计指定路径下的媒体文件数量（诊断用）
     */
    private fun countMediaInPath(pathPrefix: String): Int {
        val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
        val args = arrayOf("$pathPrefix/%")
        var count = 0
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { count += it.count }
        resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { count += it.count }
        return count
    }

    /**
     * 从 SAF URI 中提取文件系统路径。
     * 如 content://com.android.externalstorage.documents/tree/primary:Pictures/XXX
     * → /storage/emulated/0/Pictures/XXX
     */
    fun safUriToFilePath(safUri: Uri): String? {
        val documentId = android.provider.DocumentsContract.getTreeDocumentId(safUri)
        val parts = documentId.split(":")
        if (parts.size != 2) return null
        val volume = parts[0]
        val relativePath = parts[1]
        val basePath = when (volume) {
            "primary" -> "/storage/emulated/0"
            else -> "/storage/$volume"
        }
        return "$basePath/$relativePath"
    }
}
