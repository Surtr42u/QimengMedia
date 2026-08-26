package com.qimeng.media.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "media_tag_cross_refs",
    primaryKeys = ["recordKey", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["tagId"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tagId"]), Index(value = ["fileName"])]
)
data class MediaTagCrossRef(
    val recordKey: String,
    val tagId: Long,
    val fileName: String,
    /** v1.19：文件-标签关联时间。详情页标签弹窗按此倒序展示——新添加（含删除后重新添加）的标签置顶 */
    @ColumnInfo(defaultValue = "0")
    val createdAtMillis: Long = 0
)
