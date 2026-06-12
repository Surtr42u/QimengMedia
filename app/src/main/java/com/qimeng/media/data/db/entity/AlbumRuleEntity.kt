package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "album_rules",
    indices = [Index(value = ["sourceName"], unique = true)]
)
data class AlbumRuleEntity(
    @PrimaryKey(autoGenerate = true) val albumRuleId: Long = 0,
    val sourceName: String,
    val enabled: Boolean = true,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)
