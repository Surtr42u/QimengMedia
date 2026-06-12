package com.qimeng.media.scan

import com.qimeng.media.core.RecordKeyFactory
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import java.security.MessageDigest
import java.util.Locale

/**
 * 扫描器共享工具方法。
 *
 * MediaStoreScanner 和 SafMediaScanner 共用的类型判断、哈希计算、实体构建逻辑。
 */
object ScanUtils {

    /**
     * 根据文件扩展名判断媒体类型。
     * GIF 在 MediaStore.Images 中查询但被识别为 ANIMATED_IMAGE。
     */
    fun mediaTypeForExtension(extension: String): String? = when (extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp" -> MediaType.IMAGE
        "gif" -> MediaType.ANIMATED_IMAGE
        "mp4", "mkv", "avi", "mov", "flv", "webm" -> MediaType.VIDEO
        else -> null
    }

    /**
     * SHA-256 前8字符短哈希，用于路径去重和 recordKey 高级区分。
     */
    fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(8)
    }

    /**
     * 将候选列表转换为 MediaFileEntity 列表，处理重复文件名的 recordKey 生成。
     */
    fun buildEntities(
        candidates: List<MediaCandidate>,
        indexedAtMillis: Long
    ): List<MediaFileEntity> {
        val byFileName = candidates.groupBy { it.fileName }
        val folderCountsByFileName = byFileName.mapValues { (_, items) ->
            items.groupingBy { it.folderName }.eachCount()
        }

        return candidates.map { candidate ->
            val sameNameItems = byFileName.getValue(candidate.fileName)
            val isDuplicateName = sameNameItems.size > 1
            val isDuplicateFolder = folderCountsByFileName
                .getValue(candidate.fileName)
                .getValue(candidate.folderName) > 1
            val recordKey = if (isDuplicateName) {
                RecordKeyFactory.fromDuplicateFileName(
                    fileName = candidate.fileName,
                    folderName = candidate.folderName,
                    shortPathHash = candidate.pathHash.takeIf { isDuplicateFolder }
                )
            } else {
                RecordKeyFactory.fromFileName(candidate.fileName)
            }

            MediaFileEntity(
                recordKey = recordKey,
                fileName = candidate.fileName,
                displayName = candidate.displayName,
                extension = candidate.extension,
                mediaType = candidate.mediaType,
                uriString = candidate.uriString,
                folderName = candidate.folderName,
                pathHash = candidate.pathHash,
                sizeBytes = candidate.sizeBytes,
                modifiedAtMillis = candidate.modifiedAtMillis,
                width = candidate.width,
                height = candidate.height,
                durationMillis = candidate.durationMillis,
                isDuplicateName = isDuplicateName,
                indexedAtMillis = indexedAtMillis
            )
        }
    }
}

/**
 * 扫描候选文件，MediaStoreScanner 和 SafMediaScanner 共用。
 */
data class MediaCandidate(
    val fileName: String,
    val displayName: String,
    val extension: String,
    val mediaType: String,
    val uriString: String,
    val folderName: String,
    val pathHash: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val width: Int?,
    val height: Int?,
    val durationMillis: Long?
)
