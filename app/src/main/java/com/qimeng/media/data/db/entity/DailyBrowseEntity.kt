package com.qimeng.media.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 按天浏览聚合（v1.14 新增）。
 *
 * 每文件每天一行，无限保留（不做裁剪），是统计页趋势图/窗口内排行/沉浸时长的唯一数据来源。
 * 与 [ViewHistoryEntity]（浏览历史明细，仅保留最近 500 条）并存：
 * - 聚合表：无限历史、备份主数据、统计真相来源
 * - 明细表：最近轻量展示，不入备份
 */
@Entity(
    tableName = "daily_browse",
    indices = [Index(value = ["dayStartMillis"]), Index(value = ["recordKey"])]
)
data class DailyBrowseEntity(
    @PrimaryKey val recordKeyDay: String,
    val recordKey: String,
    val fileName: String,
    val mediaType: String,
    val dayStartMillis: Long,
    val viewCount: Int,
    val playCount: Int = 0,
    val totalBrowseSeconds: Long = 0,
    val updatedAtMillis: Long
)
