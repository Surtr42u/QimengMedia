package com.qimeng.media

import com.google.common.truth.Truth.assertThat
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.scan.ScanUtils
import org.junit.Test

/**
 * ScanUtils.hasScanMetadataChanged 单元测试
 * 覆盖：SAF 回退实体 null 字段不判变更（防 null 覆盖库中真实值）、
 * 真实变更仍判定、相同值不判、null→非 null 新增信息判变更
 */
class ScanUtilsMetadataTest {

    private fun entity(
        sizeBytes: Long = 100L,
        modifiedAtMillis: Long = 1000L,
        width: Int? = null,
        height: Int? = null,
        durationMillis: Long? = null
    ) = MediaFileEntity(
        recordKey = "k",
        fileName = "a.jpg",
        displayName = "a",
        extension = "jpg",
        mediaType = "image",
        uriString = "content://x",
        folderName = "f",
        pathHash = "h",
        sizeBytes = sizeBytes,
        modifiedAtMillis = modifiedAtMillis,
        width = width,
        height = height,
        durationMillis = durationMillis,
        indexedAtMillis = 0L
    )

    // ==================== null 字段不判变更（SAF 回退路径防护） ====================

    @Test
    fun nullWidthHeight_againstExistingValues_notChanged() {
        // SAF 回退实体 width/height=null，库中有真实尺寸：未知不判变更，防 null 覆盖真实值
        val scanned = entity(width = null, height = null)
        val existing = entity(width = 1920, height = 1080)
        assertThat(ScanUtils.hasScanMetadataChanged(scanned, existing)).isFalse()
    }

    @Test
    fun nullDuration_againstExistingValue_notChanged() {
        // 扫描来源不解码时长（null）与库中真实时长比较：不判变更
        val scanned = entity(durationMillis = null)
        val existing = entity(durationMillis = 60_000L)
        assertThat(ScanUtils.hasScanMetadataChanged(scanned, existing)).isFalse()
    }

    // ==================== 真实变更仍判定 / 相同值不判 ====================

    @Test
    fun sizeChanged_stillDetected() {
        val scanned = entity(sizeBytes = 200L)
        val existing = entity(sizeBytes = 100L)
        assertThat(ScanUtils.hasScanMetadataChanged(scanned, existing)).isTrue()
    }

    @Test
    fun modifiedChanged_stillDetected() {
        val scanned = entity(modifiedAtMillis = 2000L)
        val existing = entity(modifiedAtMillis = 1000L)
        assertThat(ScanUtils.hasScanMetadataChanged(scanned, existing)).isTrue()
    }

    @Test
    fun nonNullWidthDiffers_stillDetected() {
        val scanned = entity(width = 1280, height = 720)
        val existing = entity(width = 1920, height = 1080)
        assertThat(ScanUtils.hasScanMetadataChanged(scanned, existing)).isTrue()
    }

    @Test
    fun identicalValues_notChanged() {
        val scanned = entity(width = 1920, height = 1080, durationMillis = 60_000L)
        val existing = entity(width = 1920, height = 1080, durationMillis = 60_000L)
        assertThat(ScanUtils.hasScanMetadataChanged(scanned, existing)).isFalse()
    }

    // ==================== null→非 null 新增信息判变更 ====================

    @Test
    fun newDurationInfo_isChange() {
        // 扫描拿到时长而库中为 null（旧数据缺元数据）：属于新增信息，应判变更触发 upsert
        val scanned = entity(durationMillis = 60_000L)
        val existing = entity(durationMillis = null)
        assertThat(ScanUtils.hasScanMetadataChanged(scanned, existing)).isTrue()
    }

    @Test
    fun nullFieldsAgainstNull_notChanged() {
        // 双方均 null：未知对未知，不判变更
        val scanned = entity()
        val existing = entity()
        assertThat(ScanUtils.hasScanMetadataChanged(scanned, existing)).isFalse()
    }
}
