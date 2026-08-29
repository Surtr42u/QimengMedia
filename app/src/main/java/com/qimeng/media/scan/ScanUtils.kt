package com.qimeng.media.scan

import com.qimeng.media.core.RecordKeyFactory
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import java.security.MessageDigest

/**
 * 扫描器共享工具方法。
 *
 * MediaStoreScanner 和 SafMediaScanner 共用的类型判断、哈希计算、实体构建逻辑。
 */
object ScanUtils {

    /**
     * 根据文件扩展名判断媒体类型。
     * GIF 在 MediaStore.Images 中查询但被识别为 ANIMATED_IMAGE。
     * 映射真相源为 [MediaType.mediaTypeForExtension]，保持原签名委托以兼容扫描包调用方。
     */
    fun mediaTypeForExtension(extension: String): String? = MediaType.mediaTypeForExtension(extension)

    /**
     * SHA-256 前8字符短哈希，用于路径去重和 recordKey 高级区分。
     *
     * 编码显式 UTF-8：`toByteArray()` 无参重载使用平台默认字符集（Android 恒为 UTF-8，
     * 但 JVM 单元测试环境随平台变化），显式指定保证 pathHash 与运行环境无关、
     * 与 ThumbnailCacheKeys.hashKey 编码策略一致（v1.16 统一）。
     */
    fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        // v1.x 修复：Byte 为负数（如 -1）时 "%02x".format(byte) 直接格式化会输出 16 位
        // "ffffffffffffffff"，take(8) 后恒等于 "ffffffff"，导致不同文件 pathHash 互相冲突、
        // recordKey 主键互相覆盖。先转 Int 并掩码 0xFF 再按两位十六进制格式化。
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }.take(8)
    }

    /**
     * 判断同名文件在增量刷新时元数据是否发生变化。
     * 同名覆盖/重编码文件的 sizeBytes/modifiedAtMillis/durationMillis/width/height 任一变化
     * 即视为需要 upsert，避免增量刷新只写新 key 导致库中元数据永久陈旧。
     *
     * scanned 中为 null 的字段（如 SAF 回退路径不解码的 width/height/durationMillis）
     * 与库中真实值比较时视为"未知，不判变更"：null 判为变更会触发整行 upsert，
     * 把 null 永久覆盖库中真实值；非 null 且与库不同（含库为 null 的新增信息）仍判变更。
     */
    fun hasScanMetadataChanged(scanned: MediaFileEntity, existing: MediaFileEntity): Boolean {
        if (scanned.sizeBytes != existing.sizeBytes) return true
        if (scanned.modifiedAtMillis != existing.modifiedAtMillis) return true
        scanned.durationMillis?.let { if (it != existing.durationMillis) return true }
        scanned.width?.let { if (it != existing.width) return true }
        scanned.height?.let { if (it != existing.height) return true }
        return false
    }

    /**
     * 将候选列表转换为 MediaFileEntity 列表，处理重复文件名的 recordKey 生成。
     *
     * @param sourceUri 归属扫描源的 tree URI（如 content://.../tree/primary:COS）。
     *   随文件一起入库，供删除扫描源/启动清理按归属精确匹配（v1.12）。
     */
    fun buildEntities(
        candidates: List<MediaCandidate>,
        indexedAtMillis: Long,
        sourceUri: String
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
                sourceUri = sourceUri,
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
    /** 文件所在目录的完整路径（如 /storage/emulated/0/.../COS/作者/作品）。
     * v1.12 新增：MediaStore 路径解析 COS 作者/作品时用完整目录路径做相对路径分割，
     * 修复旧版用 folderName（直接父目录 leaf 名）removePrefix 恒失败、
     * COS 结构2/3 下作者被识别成作品名的问题。SAF 分支用 uriString 解析，此字段可为空。 */
    val directoryPath: String,
    val pathHash: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val width: Int?,
    val height: Int?,
    val durationMillis: Long?
)
