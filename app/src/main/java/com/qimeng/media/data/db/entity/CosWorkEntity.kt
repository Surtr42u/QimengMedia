package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cos_works",
    indices = [
        Index(value = ["authorName"]),
        Index(value = ["workName"]),
        // 唯一性从 (authorName, workName) 改为 (sourceUri, authorName, workName)：
        // v1.12 支持多个 COS 扫描源共存（旧版同作者同作品名跨源会被 Room upsert 的
        // 唯一索引静默丢弃）；保留 authorName 索引供既有查询使用
        Index(value = ["authorName", "workName"]),
        Index(value = ["sourceUri", "authorName", "workName"], unique = true),
        Index(value = ["folderUri"])
    ]
)
data class CosWorkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val authorName: String,
    val workName: String,
    val folderUri: String,
    val fileCount: Int = 0,
    val indexedAtMillis: Long,
    /** 归属 COS 扫描源的 tree URI。v1.12 新增：删除扫描源时按此字段精确匹配，避免
     * 旧版用 folderUri（文件系统路径）与扫描源 content URI 前缀匹配恒失败、
     * 删除任一 COS 源清空所有源作品的问题。 */
    val sourceUri: String = ""
)
