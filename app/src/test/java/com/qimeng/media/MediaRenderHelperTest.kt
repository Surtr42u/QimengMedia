package com.qimeng.media

import com.google.common.truth.Truth.assertThat
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.ui.browser.MediaRenderHelper
import org.junit.Test

/**
 * MediaRenderHelper 纯逻辑单元测试。
 *
 * 覆盖三个核心方法：
 * - applyTypeFilter：按媒体类型筛选
 * - computeDisplayed：按选中出处/角色计算最终显示列表
 * - buildFingerprint：渲染指纹构建（用于判断是否需要提交 adapter）
 *
 * MediaRenderHelper 是纯计算 object，无 Android 依赖，可直接 JVM 测试。
 */
class MediaRenderHelperTest {

    // ==================== 测试数据构造 ====================

    private fun makeMedia(recordKey: String, mediaType: String): MediaFileEntity = MediaFileEntity(
        recordKey = recordKey,
        fileName = "$recordKey.jpg",
        displayName = recordKey,
        extension = "jpg",
        mediaType = mediaType,
        uriString = "content://test/$recordKey",
        folderName = "TestFolder",
        pathHash = "hash_$recordKey",
        sizeBytes = 1024L,
        modifiedAtMillis = 1000L,
        isCosFile = false,
        indexedAtMillis = 1000L
    )

    private val image1 = makeMedia("img1", MediaType.IMAGE)
    private val image2 = makeMedia("img2", MediaType.IMAGE)
    private val video1 = makeMedia("vid1", MediaType.VIDEO)
    private val anim1 = makeMedia("anim1", MediaType.ANIMATED_IMAGE)

    private val allMedia = listOf(image1, image2, video1, anim1)

    // ==================== applyTypeFilter ====================

    @Test
    fun applyTypeFilter_null_returnsAll() {
        // filterType=null 表示"全部"，应原样返回
        assertThat(MediaRenderHelper.applyTypeFilter(allMedia, null)).hasSize(4)
    }

    @Test
    fun applyTypeFilter_image_returnsOnlyImages() {
        val result = MediaRenderHelper.applyTypeFilter(allMedia, MediaType.IMAGE)
        assertThat(result).hasSize(2)
        assertThat(result.map { it.recordKey }).containsExactly("img1", "img2")
    }

    @Test
    fun applyTypeFilter_video_returnsOnlyVideos() {
        val result = MediaRenderHelper.applyTypeFilter(allMedia, MediaType.VIDEO)
        assertThat(result).hasSize(1)
        assertThat(result[0].recordKey).isEqualTo("vid1")
    }

    @Test
    fun applyTypeFilter_animatedImage_returnsOnlyAnimated() {
        val result = MediaRenderHelper.applyTypeFilter(allMedia, MediaType.ANIMATED_IMAGE)
        assertThat(result).hasSize(1)
        assertThat(result[0].recordKey).isEqualTo("anim1")
    }

    @Test
    fun applyTypeFilter_emptyInput_returnsEmpty() {
        assertThat(MediaRenderHelper.applyTypeFilter(emptyList(), MediaType.IMAGE)).isEmpty()
    }

    @Test
    fun applyTypeFilter_unknownFilterType_returnsAll() {
        // 未知 filterType 走 else 分支，返回全部
        assertThat(MediaRenderHelper.applyTypeFilter(allMedia, "unknown")).hasSize(4)
    }

    // ==================== computeDisplayed ====================

    @Test
    fun computeDisplayed_noSelections_returnsBase() {
        // 未选中任何出处/角色时，返回 base 原样
        val result = MediaRenderHelper.computeDisplayed(
            base = allMedia,
            sourceGroups = emptyMap(),
            charGroups = emptyMap(),
            selectedSources = emptySet(),
            selectedChars = emptySet()
        )
        assertThat(result).hasSize(4)
    }

    @Test
    fun computeDisplayed_selectedSources_filtersToSourceMedia() {
        val sourceGroups = mapOf(
            "出处A" to listOf(image1, image2),
            "出处B" to listOf(video1)
        )
        val result = MediaRenderHelper.computeDisplayed(
            base = allMedia,
            sourceGroups = sourceGroups,
            charGroups = emptyMap(),
            selectedSources = setOf("出处A"),
            selectedChars = emptySet()
        )
        assertThat(result).hasSize(2)
        assertThat(result.map { it.recordKey }).containsExactly("img1", "img2")
    }

    @Test
    fun computeDisplayed_selectedChars_filtersToCharMedia() {
        val charGroups = mapOf(
            "角色X" to listOf(image1, video1)
        )
        val result = MediaRenderHelper.computeDisplayed(
            base = allMedia,
            sourceGroups = emptyMap(),
            charGroups = charGroups,
            selectedSources = emptySet(),
            selectedChars = setOf("角色X")
        )
        assertThat(result).hasSize(2)
        assertThat(result.map { it.recordKey }).containsExactly("img1", "vid1")
    }

    @Test
    fun computeDisplayed_bothSelections_appliesIntersection() {
        // 同时选中出处和角色时，取交集
        val sourceGroups = mapOf("出处A" to listOf(image1, image2, video1))
        val charGroups = mapOf("角色X" to listOf(image1, video1, anim1))
        val result = MediaRenderHelper.computeDisplayed(
            base = allMedia,
            sourceGroups = sourceGroups,
            charGroups = charGroups,
            selectedSources = setOf("出处A"),
            selectedChars = setOf("角色X")
        )
        // 交集为 image1 + video1
        assertThat(result).hasSize(2)
        assertThat(result.map { it.recordKey }).containsExactly("img1", "vid1")
    }

    @Test
    fun computeDisplayed_multipleSources_appliesUnion() {
        val sourceGroups = mapOf(
            "出处A" to listOf(image1),
            "出处B" to listOf(video1)
        )
        val result = MediaRenderHelper.computeDisplayed(
            base = allMedia,
            sourceGroups = sourceGroups,
            charGroups = emptyMap(),
            selectedSources = setOf("出处A", "出处B"),
            selectedChars = emptySet()
        )
        assertThat(result).hasSize(2)
        assertThat(result.map { it.recordKey }).containsExactly("img1", "vid1")
    }

    @Test
    fun computeDisplayed_selectionWithNoMatchingMedia_returnsEmpty() {
        val sourceGroups = mapOf("出处A" to listOf(image1))
        val result = MediaRenderHelper.computeDisplayed(
            base = allMedia,
            sourceGroups = sourceGroups,
            charGroups = emptyMap(),
            selectedSources = setOf("不存在的出处"),
            selectedChars = emptySet()
        )
        assertThat(result).isEmpty()
    }

    // ==================== buildFingerprint ====================

    @Test
    fun buildFingerprint_sameParams_producesSameFingerprint() {
        val params = MediaRenderHelper.FingerprintParams(
            viewMode = MediaRenderHelper.VIEW_MODE_PARTITION,
            partitions = setOf("A", "B"),
            displayed = listOf(image1),
            selectedSources = emptySet(),
            selectedChars = emptySet(),
            filterType = null,
            typePillsExpanded = false,
            sourceGroups = emptyMap(),
            charGroups = emptyMap()
        )
        val fp1 = MediaRenderHelper.buildFingerprint(params)
        val fp2 = MediaRenderHelper.buildFingerprint(params)
        assertThat(fp1).isEqualTo(fp2)
    }

    @Test
    fun buildFingerprint_differentViewMode_producesDifferentFingerprint() {
        val baseParams = MediaRenderHelper.FingerprintParams(
            viewMode = MediaRenderHelper.VIEW_MODE_PARTITION,
            partitions = setOf("A"),
            displayed = listOf(image1),
            selectedSources = emptySet(),
            selectedChars = emptySet(),
            filterType = null,
            typePillsExpanded = false,
            sourceGroups = emptyMap(),
            charGroups = emptyMap()
        )
        val fp1 = MediaRenderHelper.buildFingerprint(baseParams)
        val fp2 = MediaRenderHelper.buildFingerprint(baseParams.copy(viewMode = MediaRenderHelper.VIEW_MODE_SOURCE))
        assertThat(fp1).isNotEqualTo(fp2)
    }

    @Test
    fun buildFingerprint_differentDisplayedSize_producesDifferentFingerprint() {
        val baseParams = MediaRenderHelper.FingerprintParams(
            viewMode = MediaRenderHelper.VIEW_MODE_PARTITION,
            partitions = setOf("A"),
            displayed = listOf(image1),
            selectedSources = emptySet(),
            selectedChars = emptySet(),
            filterType = null,
            typePillsExpanded = false,
            sourceGroups = emptyMap(),
            charGroups = emptyMap()
        )
        val fp1 = MediaRenderHelper.buildFingerprint(baseParams)
        val fp2 = MediaRenderHelper.buildFingerprint(baseParams.copy(displayed = listOf(image1, image2)))
        assertThat(fp1).isNotEqualTo(fp2)
    }

    @Test
    fun buildFingerprint_differentSelections_producesDifferentFingerprint() {
        val baseParams = MediaRenderHelper.FingerprintParams(
            viewMode = MediaRenderHelper.VIEW_MODE_PARTITION,
            partitions = setOf("A"),
            displayed = listOf(image1),
            selectedSources = emptySet(),
            selectedChars = emptySet(),
            filterType = null,
            typePillsExpanded = false,
            sourceGroups = emptyMap(),
            charGroups = emptyMap()
        )
        val fp1 = MediaRenderHelper.buildFingerprint(baseParams)
        val fp2 = MediaRenderHelper.buildFingerprint(baseParams.copy(selectedSources = setOf("出处A")))
        assertThat(fp1).isNotEqualTo(fp2)
    }

    @Test
    fun buildFingerprint_sourceMode_includesSourceGroupSizes() {
        // VIEW_MODE_SOURCE 模式下，指纹应包含 sourceGroups 的 size 信息
        val params = MediaRenderHelper.FingerprintParams(
            viewMode = MediaRenderHelper.VIEW_MODE_SOURCE,
            partitions = setOf("A"),
            displayed = listOf(image1),
            selectedSources = emptySet(),
            selectedChars = emptySet(),
            filterType = null,
            typePillsExpanded = false,
            sourceGroups = mapOf("出处A" to listOf(image1, image2)),
            charGroups = emptyMap()
        )
        val fp = MediaRenderHelper.buildFingerprint(params)
        // 指纹中应包含出处A:2 的痕迹
        assertThat(fp).contains("出处A:2")
    }

    @Test
    fun buildFingerprint_characterMode_includesCharGroupSizes() {
        val params = MediaRenderHelper.FingerprintParams(
            viewMode = MediaRenderHelper.VIEW_MODE_CHARACTER,
            partitions = setOf("A"),
            displayed = listOf(image1),
            selectedSources = emptySet(),
            selectedChars = emptySet(),
            filterType = null,
            typePillsExpanded = false,
            sourceGroups = emptyMap(),
            charGroups = mapOf("角色X" to listOf(image1))
        )
        val fp = MediaRenderHelper.buildFingerprint(params)
        assertThat(fp).contains("角色X:1")
    }

    @Test
    fun buildFingerprint_emptyDisplayed_producesValidFingerprint() {
        val params = MediaRenderHelper.FingerprintParams(
            viewMode = MediaRenderHelper.VIEW_MODE_PARTITION,
            partitions = emptySet(),
            displayed = emptyList(),
            selectedSources = emptySet(),
            selectedChars = emptySet(),
            filterType = null,
            typePillsExpanded = false,
            sourceGroups = emptyMap(),
            charGroups = emptyMap()
        )
        // 空列表不应抛异常，且 firstOrNull() 应返回空字符串
        val fp = MediaRenderHelper.buildFingerprint(params)
        assertThat(fp).isNotEmpty()
    }
}
