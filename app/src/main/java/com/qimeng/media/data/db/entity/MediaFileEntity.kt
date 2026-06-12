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
        Index(value = ["isCosFile"])
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
    val indexedAtMillis: Long
)
