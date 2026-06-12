package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scan_sources",
    indices = [Index(value = ["isCosDirectory"])]
)
data class ScanSourceEntity(
    @PrimaryKey val uriString: String,
    val displayName: String,
    val isBackupDirectory: Boolean = false,
    val isCosDirectory: Boolean = false,
    val addedAtMillis: Long,
    val lastScannedAtMillis: Long? = null
)
