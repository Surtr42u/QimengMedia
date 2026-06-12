package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "author_media_cross_refs",
    primaryKeys = ["authorId", "recordKey"],
    foreignKeys = [
        ForeignKey(
            entity = AuthorEntity::class,
            parentColumns = ["authorId"],
            childColumns = ["authorId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["authorId"]), Index(value = ["recordKey"]), Index(value = ["fileName"])]
)
data class AuthorMediaCrossRef(
    val authorId: String,
    val recordKey: String,
    val fileName: String,
    val isMatched: Boolean
)
