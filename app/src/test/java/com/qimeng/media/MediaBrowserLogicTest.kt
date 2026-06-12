package com.qimeng.media

import com.google.common.truth.Truth.assertThat
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.ui.browser.MediaDateGroup
import org.junit.Test
import java.util.Calendar

/**
 * MediaBrowserLogic 工具方法纯逻辑单元测试
 * 覆盖：formatSize、dateLabel、groupByDate
 * 注意：formatDate 在源码中不存在，跳过
 */
class MediaBrowserLogicTest {

    // ==================== formatSize ====================

    @Test
    fun formatSize_zeroBytes_returnsZeroB() {
        assertThat(MediaBrowserLogic.formatSize(0L)).isEqualTo("0 B")
    }

    @Test
    fun formatSize_negativeBytes_returnsZeroB() {
        assertThat(MediaBrowserLogic.formatSize(-1L)).isEqualTo("0 B")
    }

    @Test
    fun formatSize_oneByte_returns1B() {
        assertThat(MediaBrowserLogic.formatSize(1L)).isEqualTo("1 B")
    }

    @Test
    fun formatSize_1023Bytes_returns1023B() {
        assertThat(MediaBrowserLogic.formatSize(1023L)).isEqualTo("1023 B")
    }

    @Test
    fun formatSize_1KB_returns1_0KB() {
        assertThat(MediaBrowserLogic.formatSize(1024L)).isEqualTo("1.0 KB")
    }

    @Test
    fun formatSize_1MB_returns1_0MB() {
        assertThat(MediaBrowserLogic.formatSize(1024L * 1024)).isEqualTo("1.0 MB")
    }

    @Test
    fun formatSize_1GB_returns1_0GB() {
        assertThat(MediaBrowserLogic.formatSize(1024L * 1024 * 1024)).isEqualTo("1.0 GB")
    }

    @Test
    fun formatSize_1500KB_returnsCorrectMB() {
        // 1500 * 1024 = 1536000 字节 = 1.5 MB（会自动进位到 MB）
        val result = MediaBrowserLogic.formatSize(1500L * 1024)
        assertThat(result).isEqualTo("1.5 MB")
    }

    @Test
    fun formatSize_2_5MB_returnsCorrectMB() {
        // 2.5 MB = 2621440 字节
        val result = MediaBrowserLogic.formatSize((2.5 * 1024 * 1024).toLong())
        assertThat(result).isEqualTo("2.5 MB")
    }

    @Test
    fun formatSize_largeGB_returnsCorrectGB() {
        // 5 GB
        val result = MediaBrowserLogic.formatSize(5L * 1024 * 1024 * 1024)
        assertThat(result).isEqualTo("5.0 GB")
    }

    // ==================== dateLabel ====================

    @Test
    fun dateLabel_zeroMillis_returnsUnknownDate() {
        assertThat(MediaBrowserLogic.dateLabel(0L)).isEqualTo("未知日期")
    }

    @Test
    fun dateLabel_negativeMillis_returnsUnknownDate() {
        assertThat(MediaBrowserLogic.dateLabel(-1L)).isEqualTo("未知日期")
    }

    @Test
    fun dateLabel_today_returnsToday() {
        val today = Calendar.getInstance().timeInMillis
        assertThat(MediaBrowserLogic.dateLabel(today)).isEqualTo("今天")
    }

    @Test
    fun dateLabel_yesterday_returnsYesterday() {
        // 构造"昨天 0 点"的时间戳，确保 diffDays 恰好为 1
        val yesterday = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertThat(MediaBrowserLogic.dateLabel(yesterday)).isEqualTo("昨天")
    }

    @Test
    fun dateLabel_threeDaysAgo_returnsWeekday() {
        // 3 天前应返回星期几
        val threeDaysAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -3)
        }.timeInMillis
        val result = MediaBrowserLogic.dateLabel(threeDaysAgo)
        val weekdays = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        assertThat(result).isIn(weekdays)
    }

    @Test
    fun dateLabel_farPast_returnsFormattedDate() {
        // 很久以前的日期应返回 yyyy-MM-dd 格式
        val farPast = Calendar.getInstance().apply {
            set(2023, Calendar.JUNE, 15, 12, 0, 0)
        }.timeInMillis
        val result = MediaBrowserLogic.dateLabel(farPast)
        assertThat(result).matches("\\d{4}-\\d{2}-\\d{2}")
    }

    // ==================== groupByDate ====================

    @Test
    fun groupByDate_emptyList_returnsEmptyList() {
        assertThat(MediaBrowserLogic.groupByDate(emptyList())).isEmpty()
    }

    @Test
    fun groupByDate_singleItem_returnsSingleGroup() {
        val now = Calendar.getInstance().timeInMillis
        val item = fakeEntity("key1", now)
        val groups = MediaBrowserLogic.groupByDate(listOf(item))
        assertThat(groups).hasSize(1)
        assertThat(groups[0].dateLabel).isEqualTo("今天")
        assertThat(groups[0].items).hasSize(1)
    }

    @Test
    fun groupByDate_sameDayItems_groupedTogether() {
        val now = Calendar.getInstance().timeInMillis
        val item1 = fakeEntity("key1", now)
        val item2 = fakeEntity("key2", now - 1000) // 同一天，差 1 秒
        val groups = MediaBrowserLogic.groupByDate(listOf(item1, item2))
        assertThat(groups).hasSize(1)
        assertThat(groups[0].items).hasSize(2)
    }

    @Test
    fun groupByDate_differentDays_separateGroups() {
        val today = Calendar.getInstance().timeInMillis
        val yesterday = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        val item1 = fakeEntity("key1", today)
        val item2 = fakeEntity("key2", yesterday)
        val groups = MediaBrowserLogic.groupByDate(listOf(item1, item2))
        assertThat(groups).hasSize(2)
    }

    @Test
    fun groupByDate_threeDifferentDays_threeGroups() {
        val today = Calendar.getInstance().timeInMillis
        val yesterday = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        val twoDaysAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -2)
        }.timeInMillis
        val items = listOf(
            fakeEntity("key1", today),
            fakeEntity("key2", yesterday),
            fakeEntity("key3", twoDaysAgo)
        )
        val groups = MediaBrowserLogic.groupByDate(items)
        assertThat(groups).hasSize(3)
    }

    // ==================== 辅助方法 ====================

    /** 构造一个最小化的 MediaFileEntity 用于测试 */
    private fun fakeEntity(
        recordKey: String,
        modifiedAtMillis: Long
    ): MediaFileEntity = MediaFileEntity(
        recordKey = recordKey,
        fileName = "$recordKey.jpg",
        displayName = recordKey,
        extension = "jpg",
        mediaType = "image",
        uriString = "content://test/$recordKey",
        folderName = "TestFolder",
        pathHash = "hash_$recordKey",
        sizeBytes = 1024L,
        modifiedAtMillis = modifiedAtMillis,
        indexedAtMillis = modifiedAtMillis
    )
}
