package com.qimeng.media.ui.stats

import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.ui.widget.LineChartView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 数据统计页共享格式化与聚合工具（v1.7）。
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
     * 按周分组聚合浏览量（全部时间范围），生成折线图数据点。
     * 从最早记录开始按 7 天分桶，最多 12 个数据点，避免横轴过密。
     * @param history 浏览历史列表
     * @return 按时间正序排列的数据点列表
     */
    fun groupByWeek(history: List<ViewHistoryEntity>): List<LineChartView.Point> {
        if (history.isEmpty()) return emptyList()
        val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
        val sorted = history.sortedBy { it.openedAtMillis }
        val earliest = sorted.first().openedAtMillis
        val now = System.currentTimeMillis()
        val weekMillis = 7L * 24 * 60 * 60 * 1000
        val weekCount = ((now - earliest) / weekMillis).toInt() + 1
        val result = mutableListOf<LineChartView.Point>()
        for (i in 0 until weekCount.coerceAtMost(12)) {
            val weekStart = earliest + i * weekMillis
            val weekEnd = weekStart + weekMillis
            val count = history.count { it.openedAtMillis in weekStart until weekEnd }
            result.add(LineChartView.Point(dateFormat.format(Date(weekStart)), count))
        }
        return result
    }
}
