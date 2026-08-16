package com.qimeng.media

import com.qimeng.media.data.db.entity.DailyBrowseEntity
import com.qimeng.media.ui.stats.StatsFormatHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * StatsFormatHelper「全部」范围动态分桶测试（v1.16）。
 *
 * 覆盖：周对齐周日缺陷修复、周/月/季粒度选择、总和守恒、空数据。
 * 时间戳用 java.time 按系统默认时区构造零点，与实现内 Calendar 的时区口径一致。
 */
class StatsFormatHelperTest {

    private fun dayMillis(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun row(day: String, view: Int = 1, play: Int = 0) = DailyBrowseEntity(
        recordKeyDay = "k@$day",
        recordKey = "k",
        fileName = "f.jpg",
        mediaType = "IMAGE",
        dayStartMillis = dayMillis(day),
        viewCount = view,
        playCount = play,
        updatedAtMillis = 0
    )

    // ==================== 周对齐（v1.16 周日缺陷修复） ====================

    @Test
    fun groupDailyByWeek_sunday_alignsCurrentWeekToThisMonday() {
        // now = 2026-08-16（周日）15:00，数据落在当天
        val now = dayMillis("2026-08-16") + 15 * 3600_000L
        val points = StatsFormatHelper.groupDailyByWeek(listOf(row("2026-08-16", view = 3)), now)
        // 仅 1 周数据 → 向前补一个 0 值周桶
        assertEquals(2, points.size)
        assertEquals("08/03", points[0].label)
        assertEquals(0, points[0].value)
        // 本周桶对齐到本周一 08/10，而非缺陷版的下周一 08/17
        assertEquals("08/10", points[1].label)
        assertEquals(3, points[1].value)
    }

    @Test
    fun groupDailyByWeek_monday_alignsToSelf() {
        val now = dayMillis("2026-08-10") + 10 * 3600_000L
        val points = StatsFormatHelper.groupDailyByWeek(listOf(row("2026-08-10", view = 2)), now)
        assertEquals("08/10", points.last().label)
        assertEquals(2, points.last().value)
    }

    // ==================== 动态粒度选择 ====================

    @Test
    fun groupDailyByWeek_spanWithin12Weeks_bucketsByWeek() {
        val now = dayMillis("2026-08-16") + 12 * 3600_000L
        val rows = listOf(row("2026-08-10"), row("2026-07-20"))
        val points = StatsFormatHelper.groupDailyByWeek(rows, now)
        // 07-20（周一）所在周到 08-16 所在周共 4 个周桶
        assertEquals(listOf("07/20", "07/27", "08/03", "08/10"), points.map { it.label })
    }

    @Test
    fun groupDailyByWeek_spanOver12WeeksWithin24Months_bucketsByMonth() {
        val now = dayMillis("2026-08-16") + 12 * 3600_000L
        val rows = listOf(row("2026-08-01"), row("2026-02-10"))
        val points = StatsFormatHelper.groupDailyByWeek(rows, now)
        // 约 27 周 > 12 周、7 个月 ≤ 24 → 按月；首末同年 → MM月 标签
        assertEquals(listOf("02月", "03月", "04月", "05月", "06月", "07月", "08月"), points.map { it.label })
    }

    @Test
    fun groupDailyByWeek_spanOver24Months_bucketsByQuarter() {
        val now = dayMillis("2026-08-16") + 12 * 3600_000L
        val rows = listOf(row("2026-08-01"), row("2023-12-15"))
        val points = StatsFormatHelper.groupDailyByWeek(rows, now)
        // 约 33 个月 > 24 → 按季，2023-Q4 到 2026-Q3 共 12 个季桶
        assertEquals(12, points.size)
        assertEquals("23/Q4", points.first().label)
        assertEquals("26/Q3", points.last().label)
    }

    // ==================== 总和守恒与空数据 ====================

    @Test
    fun groupDailyByWeek_allRowsSummed_bucketsTotalEqualsInputTotal() {
        val now = dayMillis("2026-08-16") + 12 * 3600_000L
        val rows = listOf(
            row("2026-08-16", view = 3, play = 1),
            row("2026-08-15", view = 2),
            row("2026-07-01", view = 5, play = 2),
            row("2026-03-10", view = 4),
            row("2024-01-01", view = 7)
        )
        val points = StatsFormatHelper.groupDailyByWeek(rows, now)
        // 无论落在哪个粒度的桶，各桶之和必须等于全量总和（与数字卡「全部」口径一致）
        assertEquals(rows.sumOf { it.viewCount + it.playCount }, points.sumOf { it.value })
    }

    @Test
    fun groupDailyByWeek_emptyRows_returnsEmpty() {
        // 空数据返回空列表，图表走「暂无趋势数据」空态
        assertTrue(StatsFormatHelper.groupDailyByWeek(emptyList(), dayMillis("2026-08-16")).isEmpty())
    }
}
