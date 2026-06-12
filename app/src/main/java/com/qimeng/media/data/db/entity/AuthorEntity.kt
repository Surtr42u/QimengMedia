package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "authors",
    indices = [Index(value = ["displayName"])]
)
data class AuthorEntity(
    @PrimaryKey val authorId: String,
    val displayName: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)
