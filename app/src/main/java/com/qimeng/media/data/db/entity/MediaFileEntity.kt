package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_files",
    indices = [
        Index(value = ["fileName"]),
        Index(value = ["mediaType"]),
        Index(value = ["modifiedAtMillis"]),
        Index(value = ["folderName"]),
        Index(value = ["isCosFile"]),
        // uriString 索引：用于 getRecordKeysByUriPrefix/getCosRecordKeysByUriPrefix 等 LIKE 前缀查询
        // SQLite 对 LIKE 'prefix%' 可使用索引加速（v1.7 索引审计补漏）
        Index(value = ["uriString"]),
        // sourceUri 索引：扫描源归属（tree URI），用于删除扫描源/启动清理孤儿文件的精确匹配
        Index(value = ["sourceUri"])
    ]
)
data class MediaFileEntity(
    @PrimaryKey val recordKey: String,
    val fileName: String,
    val displayName: String,
    val extension: String,
    val mediaType: String,
    val uriString: String,
    val folderName: String,
    val pathHash: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: Long? = null,
    val isDuplicateName: Boolean = false,
    val isCosFile: Boolean = false,
    val indexedAtMillis: Long,
    /** 归属扫描源的 tree URI（如 content://.../tree/primary:COS）。v1.12 新增：
     * 修复旧版用 URI 前缀匹配判断归属时 MediaStore（content://media）与 SAF（document）文件
     * 永远匹配不上扫描源 tree URI，导致启动清理误删全部文件、删除 COS 源清空全部作品的问题。
     * null 表示旧版本迁移数据（无法反推归属，清理时保守保留）。 */
    val sourceUri: String? = null
)
