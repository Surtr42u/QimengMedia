package com.qimeng.media.ui.stats

import com.qimeng.media.data.db.entity.DailyBrowseEntity
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

    /**
     * 今天 0 点往前 N 个完整自然日的零点（含今天）。
     * 与趋势图 groupDailyByDay 的日桶口径一致（首桶 = 今天 0 点 - (days-1) 天），
     * 保证汇总/排行卡片窗口与趋势图窗口对齐，消除"差出今天已过去半天"的口径错位。
     */
    fun naturalDayCutoff(days: Int, now: Long = System.currentTimeMillis()): Long =
        calOf(now).apply { add(Calendar.DAY_OF_YEAR, -(days - 1)) }.timeInMillis

    /** 时间范围（7天/30天/全部）的截止时刻（按完整自然日对齐，全部 = 0 不过滤）。 */
    fun cutoffForRange(rangeName: String, now: Long = System.currentTimeMillis()): Long = when (rangeName) {
        "DAYS_7" -> naturalDayCutoff(7, now)
        "DAYS_30" -> naturalDayCutoff(30, now)
        else -> 0L
    }

    /** 按天浏览聚合 → 折线图数据点（最近 days 天，按天对齐，正序） */
    fun groupDailyByDay(rows: List<DailyBrowseEntity>, days: Int, now: Long = System.currentTimeMillis()): List<LineChartView.Point> {
        val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
        val result = mutableListOf<LineChartView.Point>()
        for (i in days - 1 downTo 0) {
            val dayStart = calOf(now).apply { add(Calendar.DAY_OF_YEAR, -i) }.timeInMillis
            val count = rows.filter { it.dayStartMillis == dayStart }.sumOf { it.viewCount + it.playCount }
            result.add(LineChartView.Point(dateFormat.format(Date(dayStart)), count))
        }
        return result
    }

    /**
     * 按天浏览聚合 → 折线图数据点（「全部」范围：按数据实际跨度动态选粒度，全量不丢弃，正序）。
     *
     * v1.16 重写：原实现固定只画最近 12 周按周聚合——早于 12 周的数据被静默丢弃，
     * 与数字卡「全部」的全量口径不一致（数据集中在近期时视觉上"全堆一根柱"）；
     * 原周对齐受 locale 影响存在周日缺陷（见 [alignWeekStart]），本轮一并修复。
     *
     * 分桶规则：
     * - 跨度 ≤ 12 周 → 按周（对齐周一，标签 MM/dd）
     * - 周数 > 12 且月数 ≤ 24 → 按月（对齐月初，同年标签 MM月，跨年 yy/MM）
     * - 更长 → 按季（标签 yy/Qn）
     * 桶从最早数据所在周期铺到当前周期，各桶之和 = 全量总和（与数字卡口径一致）；
     * 数据不足 2 个周期时向前补一个 0 值桶保证折线可画；空数据返回空列表（图表走"暂无趋势数据"空态）。
     */
    fun groupDailyByWeek(rows: List<DailyBrowseEntity>, now: Long = System.currentTimeMillis()): List<LineChartView.Point> {
        if (rows.isEmpty()) return emptyList()
        val earliest = minOf(rows.minOf { it.dayStartMillis }, now)
        val weekCount = ((alignWeekStart(now) - alignWeekStart(earliest)) / WEEK_MILLIS).toInt() + 1
        return when {
            weekCount <= 12 -> {
                val buckets = bucketSequence(now, earliest, ::alignWeekStart) { it - WEEK_MILLIS }
                val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
                bucketize(rows, buckets, ::alignWeekStart) { dateFormat.format(Date(it)) }
            }
            monthsBetween(earliest, now) <= 24 -> {
                val buckets = bucketSequence(now, earliest, ::alignMonthStart) { retreatMonths(it, 1) }
                val pattern = if (sameYear(buckets.first(), buckets.last())) "MM月" else "yy/MM"
                val dateFormat = SimpleDateFormat(pattern, Locale.getDefault())
                bucketize(rows, buckets, ::alignMonthStart) { dateFormat.format(Date(it)) }
            }
            else -> {
                val buckets = bucketSequence(now, earliest, ::alignQuarterStart) { retreatMonths(it, 3) }
                bucketize(rows, buckets, ::alignQuarterStart) { quarterLabel(it) }
            }
        }
    }

    /** 按类型浏览聚合 → 折线图数据点（mediaType 过滤，dayStart 对齐） */
    fun groupDailyByType(rows: List<DailyBrowseEntity>, mediaType: String, days: Int, now: Long = System.currentTimeMillis()): List<LineChartView.Point> {
        val filtered = rows.filter { it.mediaType == mediaType }
        return groupDailyByDay(filtered, days, now)
    }

    /** 按类型浏览聚合 → 折线图数据点（「全部」粒度，委托 [groupDailyByWeek] 动态分桶） */
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

    // ===== v1.16：「全部」范围动态分桶辅助 =====

    /**
     * 从最早数据所在周期回溯生成升序桶起点列表，直到当前周期。
     * 仅 1 个周期时向前补一个桶（0 值），保证折线图至少两点可画。
     */
    private fun bucketSequence(
        now: Long,
        earliestData: Long,
        align: (Long) -> Long,
        retreat: (Long) -> Long
    ): List<Long> {
        val last = align(now)
        val first = align(earliestData)
        val descending = mutableListOf(last)
        var bucket = last
        while (bucket > first) {
            bucket = retreat(bucket)
            descending.add(bucket)
        }
        if (descending.size == 1) descending.add(retreat(bucket))
        return descending.asReversed()
    }

    /** 每行按桶起点归组求和（桶值 = 桶内各天 viewCount+playCount 之和），按桶序列输出数据点 */
    private fun bucketize(
        rows: List<DailyBrowseEntity>,
        buckets: List<Long>,
        align: (Long) -> Long,
        label: (Long) -> String
    ): List<LineChartView.Point> {
        val sums = HashMap<Long, Int>()
        for (row in rows) {
            sums.merge(align(row.dayStartMillis), row.viewCount + row.playCount, Int::plus)
        }
        return buckets.map { LineChartView.Point(label(it), sums[it] ?: 0) }
    }

    /** earliest 到 now 跨越的自然月数（含首尾两个月） */
    private fun monthsBetween(earliest: Long, now: Long): Int {
        val start = calOf(earliest)
        val end = calOf(now)
        return (end.get(Calendar.YEAR) - start.get(Calendar.YEAR)) * 12 +
            (end.get(Calendar.MONTH) - start.get(Calendar.MONTH)) + 1
    }

    private fun sameYear(a: Long, b: Long): Boolean =
        calOf(a).get(Calendar.YEAR) == calOf(b).get(Calendar.YEAR)

    /** 季度起点 → "yy/Qn" 短标签（如 26/Q3） */
    private fun quarterLabel(start: Long): String {
        val cal = calOf(start)
        val year = String.format(Locale.US, "%02d", cal.get(Calendar.YEAR) % 100)
        return "$year/Q${cal.get(Calendar.MONTH) / 3 + 1}"
    }

    private fun alignMonthStart(millis: Long): Long =
        calOf(millis).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

    private fun alignQuarterStart(millis: Long): Long =
        calOf(millis).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.MONTH, (get(Calendar.MONTH) / 3) * 3)
        }.timeInMillis

    private fun retreatMonths(millis: Long, count: Int): Long =
        calOf(millis).apply { add(Calendar.MONTH, -count) }.timeInMillis

    /** 时间戳时分秒毫秒清零的 Calendar（各周期对齐函数共享） */
    private fun calOf(millis: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /**
     * 将时间戳对齐到所在周的周一零点（与 locale 无关）。
     * v1.16 修复：原 set(DAY_OF_WEEK, MONDAY) 受 locale firstDayOfWeek 影响——
     * zh-CN（firstDayOfWeek=周日）下当天为周日会滚到下周一，使"本周"桶变成未来窗口恒为 0；
     * 改为按"距本周一已过天数"回退（周一=0 … 周日=6）。
     */
    private fun alignWeekStart(millis: Long): Long {
        val cal = calOf(millis)
        cal.add(Calendar.DAY_OF_YEAR, -((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7))
        return cal.timeInMillis
    }

    private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
}
