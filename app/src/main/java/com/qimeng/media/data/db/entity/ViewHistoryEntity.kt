package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "view_history",
    indices = [Index(value = ["openedAtMillis"]), Index(value = ["mediaType"])]
)
data class ViewHistoryEntity(
    @PrimaryKey val recordKey: String,
    val fileName: String,
    val mediaType: String,
    val openedAtMillis: Long
)
