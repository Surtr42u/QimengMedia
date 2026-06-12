package com.qimeng.media.data.db.entity

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
    val fileName: String
)
