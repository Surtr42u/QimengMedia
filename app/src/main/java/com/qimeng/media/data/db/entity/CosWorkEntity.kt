package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cos_works",
    indices = [
        Index(value = ["authorName"]),
        Index(value = ["workName"]),
        Index(value = ["authorName", "workName"], unique = true),
        Index(value = ["folderUri"])
    ]
)
data class CosWorkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val authorName: String,
    val workName: String,
    val folderUri: String,
    val fileCount: Int = 0,
    val indexedAtMillis: Long
)
