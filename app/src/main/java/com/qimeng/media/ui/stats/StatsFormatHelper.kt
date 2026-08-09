package com.qimeng.media.ui.stats

import com.qimeng.media.data.db.entity.DailyBrowseEntity
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.ui.widget.LineChartView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 数据统计页共享格式化与聚合工具（v1.7，v1.14 增加按天浏览聚合计算）。
 *
 * 提取自 DataStatsFragment 与 StatsDetailFragment 中逐字重复的工具方法，
 * 消除重复代码（遵循 GUIDE_CODE_MAINTENANCE §9 共享逻辑原则）。
 *
 * 纯计算型 object，无 Android Context 依赖，可在任意线程调用。
 */
object StatsFormatHelper {

    /** 格式化数字（大数压缩显示：≥1万显示 X.X万，≥1千显示 X.Xk） */
    fun formatNumber(n: Int): String {
        val us = Locale.US
        return when {
            n >= 10000 -> String.format(us, "%.1f万", n / 10000.0)
            n >= 1000 -> String.format(us, "%.1fk", n / 1000.0)
            else -> n.toString()
        }
    }

    /** 格式化文件大小（字节 → KB/MB/GB） */
    fun formatSize(bytes: Long): String {
        val us = Locale.US
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format(us, "%.1fGB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format(us, "%.1fMB", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format(us, "%.1fKB", bytes / 1024.0)
            else -> "${bytes}B"
        }
    }

    // ===== v1.14：按天浏览聚合计算（daily_browse，无限保留，统计唯一真相来源） =====

    /** 时间范围（7天/30天/全部）的截止时刻。全部 = 0（不过滤）。 */
    fun cutoffForRange(rangeName: String, now: Long = System.currentTimeMillis()): Long = when (rangeName) {
        "DAYS_7" -> now - 7L * 24 * 60 * 60 * 1000
        "DAYS_30" -> now - 30L * 24 * 60 * 60 * 1000
        else -> 0L
    }

    /** 按天浏览聚合 → 折线图数据点（最近 days 天，按天对齐，正序） */
    fun groupDailyByDay(rows: List<DailyBrowseEntity>, days: Int, now: Long = System.currentTimeMillis()): List<LineChartView.Point> {
        val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
        val result = mutableListOf<LineChartView.Point>()
        val cutoff = now - days.toLong() * 24 * 60 * 60 * 1000
        for (i in days - 1 downTo 0) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = now
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val dayStart = cal.timeInMillis
            val count = rows.filter { it.dayStartMillis == dayStart }.sumOf { it.viewCount + it.playCount }
            result.add(LineChartView.Point(dateFormat.format(Date(dayStart)), count))
        }
        return result
    }

    /** 按天浏览聚合 → 折线图数据点（最近 12 周，对齐周一，正序） */
    fun groupDailyByWeek(rows: List<DailyBrowseEntity>, now: Long = System.currentTimeMillis()): List<LineChartView.Point> {
        val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
        val weekMillis = 7L * 24 * 60 * 60 * 1000
        val result = mutableListOf<LineChartView.Point>()
        for (i in 11 downTo 0) {
            val weekStart = alignWeekStart(now) - i * weekMillis
            val weekEnd = weekStart + weekMillis
            val count = rows.filter { it.dayStartMillis in weekStart until weekEnd }
                .sumOf { it.viewCount + it.playCount }
            result.add(LineChartView.Point(dateFormat.format(Date(weekStart)), count))
        }
        return result
    }

    /** 按类型浏览聚合 → 折线图数据点（mediaType 过滤，dayStart 对齐） */
    fun groupDailyByType(rows: List<DailyBrowseEntity>, mediaType: String, days: Int, now: Long = System.currentTimeMillis()): List<LineChartView.Point> {
        val filtered = rows.filter { it.mediaType == mediaType }
        return groupDailyByDay(filtered, days, now)
    }

    /** 按类型浏览聚合 → 折线图数据点（12 周粒度） */
    fun groupDailyByTypeWeek(rows: List<DailyBrowseEntity>, mediaType: String, now: Long = System.currentTimeMillis()): List<LineChartView.Point> {
        val filtered = rows.filter { it.mediaType == mediaType }
        return groupDailyByWeek(filtered, now)
    }

    /** 按类型 + 时间窗口聚合（次数+播放+时长），返回 key → 聚合值 */
    fun aggregateDailyByType(
        rows: List<DailyBrowseEntity>,
        rangeName: String,
        now: Long = System.currentTimeMillis()
    ): Map<String, Triple<Int, Int, Long>> {
        val cutoff = cutoffForRange(rangeName, now)
        return rows.filter { it.dayStartMillis >= cutoff }.groupBy { it.mediaType }.mapValues { (_, list) ->
            Triple(
                list.sumOf { it.viewCount + it.playCount },
                list.sumOf { it.playCount },
                list.sumOf { it.totalBrowseSeconds }
            )
        }
    }

    /** 来源（常规/COS）浏览聚合，key → 次数 */
    fun aggregateDailyBySource(
        rows: List<DailyBrowseEntity>,
        cosKeys: Set<String>,
        rangeName: String,
        now: Long = System.currentTimeMillis()
    ): Map<Boolean, Int> {
        val cutoff = cutoffForRange(rangeName, now)
        return rows.filter { it.dayStartMillis >= cutoff }.groupBy { it.recordKey in cosKeys }.mapValues { (_, list) ->
            list.sumOf { it.viewCount + it.playCount }
        }
    }

    /** 类型显示名（与 MediaType 常量对应） */
    fun typeDisplayName(mediaType: String): String = when (mediaType) {
        MediaType.IMAGE -> "图片"
        MediaType.VIDEO -> "视频"
        MediaType.ANIMATED_IMAGE -> "动图"
        else -> mediaType
    }

    /**
     * 按天分组聚合浏览量，生成折线图数据点。
     * @param history 浏览历史列表
     * @param days 天数（生成 days 个数据点，从 days-1 天前到今天）
     * @return 按时间正序排列的数据点列表（最早在前）
     */
    fun groupByDay(history: List<ViewHistoryEntity>, days: Int): List<LineChartView.Point> {
        val now = System.currentTimeMillis()
        val cutoff = now - days.toLong() * 24 * 60 * 60 * 1000
        val filtered = history.filter { it.openedAtMillis >= cutoff }
        val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
        val result = mutableListOf<LineChartView.Point>()
        for (i in days - 1 downTo 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayStart = cal.timeInMillis
            val dayEnd = dayStart + 24 * 60 * 60 * 1000
            val count = filtered.count { it.openedAtMillis in dayStart until dayEnd }
            result.add(LineChartView.Point(dateFormat.format(Date(dayStart)), count))
        }
        return result
    }

    /**
     * 按周分组聚合浏览量，生成折线图数据点。
     * v1.12 修复：原实现从最早记录起截取前 12 周，数据跨度超过 84 天时最新趋势丢失；
     * 改为从当前时间所在周向前取最近 12 周（含当前周），保证最新趋势始终可见。
     * @param history 浏览历史列表
     * @return 按时间正序排列的数据点列表（最近 12 周，早的在前）
     */
    fun groupByWeek(history: List<ViewHistoryEntity>): List<LineChartView.Point> {
        if (history.isEmpty()) return emptyList()
        val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
        val weekMillis = 7L * 24 * 60 * 60 * 1000
        val result = mutableListOf<LineChartView.Point>()
        for (i in 11 downTo 0) {
            // 从当前周向前回溯，每个桶对齐到所在周的周一零点
            val weekStart = alignWeekStart(System.currentTimeMillis()) - i * weekMillis
            val weekEnd = weekStart + weekMillis
            val count = history.count { it.openedAtMillis in weekStart until weekEnd }
            result.add(LineChartView.Point(dateFormat.format(Date(weekStart)), count))
        }
        return result
    }

    /** 将时间戳对齐到所在周的周一零点 */
    private fun alignWeekStart(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        return cal.timeInMillis
    }
}
