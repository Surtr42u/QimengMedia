package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_tags",
    indices = [Index(value = ["recordKey"]), Index(value = ["fileName"])]
)
data class TimelineTagEntity(
    @PrimaryKey(autoGenerate = true) val timelineTagId: Long = 0,
    val recordKey: String,
    val fileName: String,
    val timeMillis: Long,
    val name: String,
    val createdAtMillis: Long
)
