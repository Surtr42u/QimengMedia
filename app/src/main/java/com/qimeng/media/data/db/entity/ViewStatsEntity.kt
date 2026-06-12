package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "view_stats",
    indices = [Index(value = ["fileName"]), Index(value = ["lastOpenedAtMillis"])]
)
data class ViewStatsEntity(
    @PrimaryKey val recordKey: String,
    val fileName: String,
    val viewCount: Int = 0,
    val playCount: Int = 0,
    val totalBrowseSeconds: Long = 0,
    val lastOpenedAtMillis: Long? = null,
    val updatedAtMillis: Long
)
