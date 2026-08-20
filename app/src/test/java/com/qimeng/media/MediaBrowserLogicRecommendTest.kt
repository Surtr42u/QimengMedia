package com.qimeng.media

import com.google.common.truth.Truth.assertThat
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.ui.browser.MediaFilterState
import com.qimeng.media.ui.browser.MediaPlayRange
import com.qimeng.media.ui.browser.MediaRankingPeriod
import com.qimeng.media.ui.browser.MediaSizeRange
import com.qimeng.media.ui.browser.MediaSortDirection
import com.qimeng.media.ui.browser.MediaSortKey
import com.qimeng.media.ui.browser.MediaTagMode
import com.qimeng.media.ui.browser.MediaViewRange
import com.qimeng.media.ui.browser.SearchContext
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * MediaBrowserLogic 核心算法单元测试：recommend / rank / applyFilter。
 *
 * 基于当前 v1.12 实现编写：
 * - recommend() 的每日惩罚改为 `-0.8 × shownCount`（随展示次数递增，不再恒 -0.8）；
 * - balanceVideoImage() 新增 seed 参数，同 seed 下整个推荐结果可复现；
 * - 全部为纯 JVM 逻辑，直接构造数据调用，不 mock 生产代码。
 *
 * 说明：applyFilter() 本身没有 mediaType 维度（类型筛选在 MediaRenderHelper.applyTypeFilter，
 * 已有 MediaRenderHelperTest 覆盖），本类对 applyFilter 用 viewRange/playRange/sizeRange
 * 覆盖"范围类筛选"分支，并覆盖标签筛选与多关键词 query。
 */
class MediaBrowserLogicRecommendTest {

    private val now = System.currentTimeMillis()

    // ==================== 测试数据构造 ====================

    private fun makeMedia(
        recordKey: String,
        mediaType: String = MediaType.IMAGE,
        sizeBytes: Long = 1024L,
        modifiedAtMillis: Long = now,
        indexedAtMillis: Long = now - 3_600_000L,
        folderName: String = "TestFolder",
        fileName: String = "$recordKey.jpg"
    ): MediaFileEntity = MediaFileEntity(
        recordKey = recordKey,
        fileName = fileName,
        displayName = fileName.substringBeforeLast('.'),
        extension = fileName.substringAfterLast('.', ""),
        mediaType = mediaType,
        uriString = "content://test/$recordKey",
        folderName = folderName,
        pathHash = "hash_$recordKey",
        sizeBytes = sizeBytes,
        modifiedAtMillis = modifiedAtMillis,
        indexedAtMillis = indexedAtMillis
    )

    private fun makeStats(
        recordKey: String,
        viewCount: Int = 0,
        playCount: Int = 0,
        totalBrowseSeconds: Long = 0,
        lastOpenedAtMillis: Long? = null
    ): ViewStatsEntity = ViewStatsEntity(
        recordKey = recordKey,
        fileName = recordKey,
        viewCount = viewCount,
        playCount = playCount,
        totalBrowseSeconds = totalBrowseSeconds,
        lastOpenedAtMillis = lastOpenedAtMillis,
        updatedAtMillis = now
    )

    // ==================== recommend() ====================

    @Test
    fun recommend_emptyList_returnsEmpty() = runBlocking<Unit> {
        // 空列表直接短路返回 emptyList，不计算
        val result = MediaBrowserLogic.recommend(emptyList(), emptyMap(), emptyMap())
        assertThat(result).isEmpty()
    }

    @Test
    fun recommend_singleItem_returnsIt() = runBlocking<Unit> {
        val item = makeMedia("only")
        val result = MediaBrowserLogic.recommend(listOf(item), emptyMap(), emptyMap())
        assertThat(result).hasSize(1)
        assertThat(result[0].recordKey).isEqualTo("only")
    }

    @Test
    fun recommend_withData_returnsAllByDefault() = runBlocking<Unit> {
        // limit 默认为 media.size，应返回全部条目（全排列）
        val media = listOf(makeMedia("key1"), makeMedia("key2"), makeMedia("key3"))
        val result = MediaBrowserLogic.recommend(media, emptyMap(), emptyMap())
        assertThat(result).hasSize(3)
        // Truth 的 containsExactlyElementsIn 默认无视顺序，符合"全排列"断言
        assertThat(result.map { it.recordKey })
            .containsExactlyElementsIn(media.map { it.recordKey })
    }

    @Test
    fun recommend_limit_truncatesResult() = runBlocking<Unit> {
        // limit 小于总数时只返回前 limit 条，且结果必须是输入的子集
        val media = listOf(makeMedia("key1"), makeMedia("key2"), makeMedia("key3"))
        val mediaKeys = media.map { it.recordKey }
        val result = MediaBrowserLogic.recommend(media, emptyMap(), emptyMap(), limit = 2)
        assertThat(result).hasSize(2)
        val resultKeys = result.map { it.recordKey }
        assertThat(resultKeys).containsAnyIn(mediaKeys)
        // 输入必须包含结果的全部元素（子集关系验证）
        assertThat(mediaKeys).containsAtLeastElementsIn(resultKeys)
    }

    @Test
    fun recommend_sameSeed_producesIdenticalOrder() = runBlocking<Unit> {
        // v1.12 修复点：seed 同时传入 shuffleBuckets 与 balanceVideoImage，
        // 同 seed 下整个推荐结果（含视频图片混合）必须可复现
        val media = listOf(
            makeMedia("keyA", mediaType = MediaType.VIDEO),
            makeMedia("keyB"),
            makeMedia("keyC", mediaType = MediaType.VIDEO),
            makeMedia("keyD"),
            makeMedia("keyE", mediaType = MediaType.VIDEO),
            makeMedia("keyF")
        )
        val first = MediaBrowserLogic.recommend(media, emptyMap(), emptyMap(), seed = 42)
        val second = MediaBrowserLogic.recommend(media, emptyMap(), emptyMap(), seed = 42)
        assertThat(second.map { it.recordKey }).isEqualTo(first.map { it.recordKey })
    }

    @Test
    fun recommend_dailyShownPenalty_increasesWithShownCount() = runBlocking<Unit> {
        // v1.12：每日惩罚 = -0.8 × shownCount（展示次数越多排得越靠后，不再恒 -0.8）。
        // 全部为图片且 seed=0（跳过 shuffleBuckets），balanceVideoImage 保持评分顺序，
        // 因此结果顺序可直接断言：未展示 > 展示1次 > 展示5次。
        // 惩罚差值：shownFive 最多 -4.0，即使 randomFactor 差异最大 0.485 也无法翻盘。
        val media = listOf(
            makeMedia("unshownA"),
            makeMedia("unshownB"),
            makeMedia("shownOnce"),
            makeMedia("shownFive")
        )
        val result = MediaBrowserLogic.recommend(
            media = media,
            stats = emptyMap(),
            tagMap = emptyMap(),
            seed = 0,
            dailyShownCountMap = mapOf("shownOnce" to 1, "shownFive" to 5)
        )
        assertThat(result).hasSize(4)
        // 未展示的两个文件排在最前（顺序由 randomFactor 决定，不断言顺序）
        assertThat(result.take(2).map { it.recordKey })
            .containsExactly("unshownA", "unshownB")
        // 展示 1 次在展示 5 次之前，展示 5 次必须在最后
        assertThat(result[2].recordKey).isEqualTo("shownOnce")
        assertThat(result[3].recordKey).isEqualTo("shownFive")
    }

    @Test
    fun recommend_balanceVideoImage_mixedResult_isPermutationOfInput() = runBlocking<Unit> {
        // balanceVideoImage：视频图片自然混合，结果是输入的全排列；
        // 类型序列应发生交错（存在相邻异类），不是"视频全部在前"的原始分组顺序
        val media = listOf(
            makeMedia("v1", mediaType = MediaType.VIDEO),
            makeMedia("v2", mediaType = MediaType.VIDEO),
            makeMedia("v3", mediaType = MediaType.VIDEO),
            makeMedia("v4", mediaType = MediaType.VIDEO),
            makeMedia("v5", mediaType = MediaType.VIDEO),
            makeMedia("v6", mediaType = MediaType.VIDEO),
            makeMedia("i1"),
            makeMedia("i2"),
            makeMedia("i3"),
            makeMedia("i4"),
            makeMedia("i5"),
            makeMedia("i6")
        )
        val result = MediaBrowserLogic.recommend(media, emptyMap(), emptyMap(), seed = 7)
        assertThat(result).hasSize(12)
        assertThat(result.map { it.recordKey })
            .containsExactlyElementsIn(media.map { it.recordKey })
        // 原始输入是视频在前，混合后不应保持该顺序
        assertThat(result.map { it.recordKey }).isNotEqualTo(media.map { it.recordKey })
        // 两种类型都出现在结果中，且发生至少一次类型交错
        val types = result.map { it.mediaType }.toSet()
        assertThat(types).contains(MediaType.VIDEO)
        assertThat(types).contains(MediaType.IMAGE)
        val transitions = result.zipWithNext().count { (a, b) -> a.mediaType != b.mediaType }
        assertThat(transitions).isGreaterThan(0)
    }

    // ==================== rank() ====================

    @Test
    fun rank_emptySource_returnsEmpty() {
        val result = MediaBrowserLogic.rank(
            emptyList(), MediaRankingPeriod.ALL, emptyMap(), emptyList()
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun rank_singleItem_returnsIt() {
        val item = makeMedia("key1")
        val result = MediaBrowserLogic.rank(
            listOf(item), MediaRankingPeriod.ALL, emptyMap(), emptyList()
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].recordKey).isEqualTo("key1")
    }

    @Test
    fun rank_allPeriod_sortsByViewsPlaysLikes() {
        // 热度排序 = viewCount + playCount + likeCount 降序
        val a = makeMedia("keyA", modifiedAtMillis = now)
        val b = makeMedia("keyB", modifiedAtMillis = now - 1_000)
        val c = makeMedia("keyC", modifiedAtMillis = now - 2_000)
        val stats = mapOf(
            "keyA" to makeStats("keyA", viewCount = 100),
            "keyB" to makeStats("keyB", viewCount = 50, playCount = 10),
            "keyC" to makeStats("keyC")
        )
        val result = MediaBrowserLogic.rank(
            listOf(a, b, c), MediaRankingPeriod.ALL, stats, emptyList(),
            likeCounts = mapOf("keyC" to 70)
        )
        // keyA = 100，keyC = 70（点赞计入总分），keyB = 60（50 观看 + 10 播放）
        assertThat(result.map { it.recordKey })
            .isEqualTo(listOf("keyA", "keyC", "keyB"))
    }

    @Test
    fun rank_tieScore_newerModifiedAtFirst() {
        // 同分时按 modifiedAtMillis 降序，新文件在前
        val newer = makeMedia("keyNew", modifiedAtMillis = now)
        val older = makeMedia("keyOld", modifiedAtMillis = now - 100_000)
        val stats = mapOf(
            "keyNew" to makeStats("keyNew", viewCount = 10),
            "keyOld" to makeStats("keyOld", viewCount = 10)
        )
        val result = MediaBrowserLogic.rank(
            listOf(older, newer), MediaRankingPeriod.ALL, stats, emptyList()
        )
        assertThat(result.map { it.recordKey }).isEqualTo(listOf("keyNew", "keyOld"))
    }

    @Test
    fun rank_dayPeriod_onlyIncludesPeriodFiles() {
        // 周期筛选：非总榜只保留周期内有浏览记录的文件
        // 周期内来源：view_history.openedAtMillis 或 view_stats.lastOpenedAtMillis >= cutoff
        val history = listOf(
            ViewHistoryEntity("keyRecentHistory", "f.jpg", MediaType.IMAGE, now),
            ViewHistoryEntity("keyOldHistory", "f.jpg", MediaType.IMAGE, now - 10L * 86_400_000L)
        )
        val stats = mapOf(
            "keyRecentStat" to makeStats(
                "keyRecentStat", viewCount = 5, lastOpenedAtMillis = now - 3_600_000L
            )
        )
        val result = MediaBrowserLogic.rank(
            listOf(
                makeMedia("keyRecentHistory"),
                makeMedia("keyOldHistory"),
                makeMedia("keyRecentStat")
            ),
            MediaRankingPeriod.DAY, stats, history
        )
        // keyOldHistory 超出日榜周期被排除；剩余两项中 keyRecentStat(5) 热度高于 keyRecentHistory(0)
        assertThat(result.map { it.recordKey })
            .isEqualTo(listOf("keyRecentStat", "keyRecentHistory"))
    }

    // ==================== applyFilter() ====================

    @Test
    fun applyFilter_emptySource_returnsEmpty() {
        val result = MediaBrowserLogic.applyFilter(
            emptyList(), "", MediaFilterState(), emptyMap(), emptyMap()
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun applyFilter_noFilter_returnsAllSortedByModifiedDesc() {
        // 无 query 无筛选时返回全部，默认按 modifiedAtMillis 降序
        val source = listOf(
            makeMedia("key1", modifiedAtMillis = 1_000),
            makeMedia("key2", modifiedAtMillis = 3_000),
            makeMedia("key3", modifiedAtMillis = 2_000)
        )
        val result = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(), emptyMap(), emptyMap()
        )
        assertThat(result.map { it.recordKey }).isEqualTo(listOf("key2", "key3", "key1"))
    }

    @Test
    fun applyFilter_equalModifiedAt_keepsInputOrder() {
        // 同分（同 modifiedAt）时排序稳定，保持输入顺序（边界：同分数排序稳定性）
        val source = listOf(
            makeMedia("keyA", modifiedAtMillis = 1_000),
            makeMedia("keyB", modifiedAtMillis = 1_000)
        )
        val result = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(), emptyMap(), emptyMap()
        )
        assertThat(result.map { it.recordKey }).isEqualTo(listOf("keyA", "keyB"))
    }

    @Test
    fun applyFilter_viewRange_eachBucketFiltersCorrectly() {
        // 观看次数范围筛选：NONE=0 / FEW=1-4 / SOME=5-19 / MANY>=20
        val source = listOf(
            makeMedia("key0"), makeMedia("key3"), makeMedia("key10"), makeMedia("key25")
        )
        val stats = mapOf(
            "key0" to makeStats("key0"),
            "key3" to makeStats("key3", viewCount = 3),
            "key10" to makeStats("key10", viewCount = 10),
            "key25" to makeStats("key25", viewCount = 25)
        )
        val none = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(viewRange = MediaViewRange.NONE), stats, emptyMap()
        )
        assertThat(none.map { it.recordKey }).containsExactly("key0")
        val few = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(viewRange = MediaViewRange.FEW), stats, emptyMap()
        )
        assertThat(few.map { it.recordKey }).containsExactly("key3")
        val some = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(viewRange = MediaViewRange.SOME), stats, emptyMap()
        )
        assertThat(some.map { it.recordKey }).containsExactly("key10")
        val many = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(viewRange = MediaViewRange.MANY), stats, emptyMap()
        )
        assertThat(many.map { it.recordKey }).containsExactly("key25")
    }

    @Test
    fun applyFilter_playRange_filtersByPlayCount() {
        // 点击次数范围筛选（与观看次数同区间语义）
        val source = listOf(makeMedia("key0"), makeMedia("key2"))
        val stats = mapOf(
            "key0" to makeStats("key0"),
            "key2" to makeStats("key2", playCount = 2)
        )
        val none = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(playRange = MediaPlayRange.NONE), stats, emptyMap()
        )
        assertThat(none.map { it.recordKey }).containsExactly("key0")
        val few = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(playRange = MediaPlayRange.FEW), stats, emptyMap()
        )
        assertThat(few.map { it.recordKey }).containsExactly("key2")
    }

    @Test
    fun applyFilter_sizeRange_filtersByFileSize() {
        // 文件大小范围筛选（applyFilter 无 mediaType 维度，此处覆盖"范围类"类型筛选分支）：
        // SMALL<1MB / MEDIUM 1-10MB / LARGE 10-50MB / XLARGE>=50MB
        val source = listOf(
            makeMedia("small", sizeBytes = 500_000L),
            makeMedia("medium", sizeBytes = 5_000_000L),
            makeMedia("large", sizeBytes = 20_000_000L),
            makeMedia("xlarge", sizeBytes = 100_000_000L)
        )
        val small = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(sizeRange = MediaSizeRange.SMALL), emptyMap(), emptyMap()
        )
        assertThat(small.map { it.recordKey }).containsExactly("small")
        val medium = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(sizeRange = MediaSizeRange.MEDIUM), emptyMap(), emptyMap()
        )
        assertThat(medium.map { it.recordKey }).containsExactly("medium")
        val large = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(sizeRange = MediaSizeRange.LARGE), emptyMap(), emptyMap()
        )
        assertThat(large.map { it.recordKey }).containsExactly("large")
        val xlarge = MediaBrowserLogic.applyFilter(
            source, "", MediaFilterState(sizeRange = MediaSizeRange.XLARGE), emptyMap(), emptyMap()
        )
        assertThat(xlarge.map { it.recordKey }).containsExactly("xlarge")
    }

    @Test
    fun applyFilter_tagFuzzy_anySelectedTagMatches() {
        // 标签模糊模式：命中任一选中标签即显示
        val source = listOf(makeMedia("key1"), makeMedia("key2"), makeMedia("key3"))
        val tagMap = mapOf(
            "key1" to setOf("tagA"),
            "key2" to setOf("tagB"),
            "key3" to setOf("tagA", "tagB")
        )
        val result = MediaBrowserLogic.applyFilter(
            source, "",
            MediaFilterState(selectedTags = setOf("tagA"), tagMode = MediaTagMode.FUZZY),
            emptyMap(), tagMap
        )
        assertThat(result.map { it.recordKey }).containsExactly("key1", "key3")
    }

    @Test
    fun applyFilter_tagExact_requiresAllSelectedTags() {
        // 标签精确模式：必须包含全部选中标签才显示，与模糊模式对比
        val source = listOf(makeMedia("key1"), makeMedia("key2"), makeMedia("key3"))
        val tagMap = mapOf(
            "key1" to setOf("tagA"),
            "key2" to setOf("tagB"),
            "key3" to setOf("tagA", "tagB")
        )
        val fuzzy = MediaBrowserLogic.applyFilter(
            source, "",
            MediaFilterState(selectedTags = setOf("tagA", "tagB"), tagMode = MediaTagMode.FUZZY),
            emptyMap(), tagMap
        )
        // 模糊：含任一标签即可，三个文件都命中
        assertThat(fuzzy.map { it.recordKey }).containsExactly("key1", "key2", "key3")
        val exact = MediaBrowserLogic.applyFilter(
            source, "",
            MediaFilterState(selectedTags = setOf("tagA", "tagB"), tagMode = MediaTagMode.EXACT),
            emptyMap(), tagMap
        )
        // 精确：只有同时含 tagA 和 tagB 的 key3 命中
        assertThat(exact.map { it.recordKey }).containsExactly("key3")
    }

    @Test
    fun applyFilter_query_singleKeywordMatchesFileName() {
        // 单关键词 query：匹配文件名（大小写不敏感）
        val source = listOf(
            makeMedia("key1", fileName = "cat.jpg"),
            makeMedia("key2", fileName = "dog.jpg"),
            makeMedia("key3", fileName = "cat_dog.jpg"),
            makeMedia("key4", fileName = "CatPic.jpg")
        )
        val result = MediaBrowserLogic.applyFilter(
            source, "cat", MediaFilterState(), emptyMap(), emptyMap()
        )
        // key1/key3/key4 命中（key4 大写被 lowercase 归一化）
        assertThat(result.map { it.recordKey }).containsExactly("key1", "key3", "key4")
    }

    @Test
    fun applyFilter_query_multiKeyword_allMustMatch() {
        // 多关键词 query：按空格拆分，每个关键词都必须至少命中一个维度
        val source = listOf(
            makeMedia("key1", fileName = "cat.jpg"),
            makeMedia("key2", fileName = "dog.jpg"),
            makeMedia("key3", fileName = "cat_dog.jpg")
        )
        val result = MediaBrowserLogic.applyFilter(
            source, "cat dog", MediaFilterState(), emptyMap(), emptyMap()
        )
        // 只有 cat_dog.jpg 同时包含 cat 和 dog
        assertThat(result.map { it.recordKey }).containsExactly("key3")
    }

    @Test
    fun applyFilter_query_matchesFolderAndTag() {
        // query 可命中文件夹名与标签维度
        val source = listOf(
            makeMedia("key1", folderName = "Games"),
            makeMedia("key2", fileName = "plain.jpg")
        )
        val tagMap = mapOf("key2" to setOf("Action"))
        val byFolder = MediaBrowserLogic.applyFilter(
            source, "games", MediaFilterState(), emptyMap(), emptyMap()
        )
        assertThat(byFolder.map { it.recordKey }).containsExactly("key1")
        val byTag = MediaBrowserLogic.applyFilter(
            source, "action", MediaFilterState(), emptyMap(), tagMap
        )
        assertThat(byTag.map { it.recordKey }).containsExactly("key2")
    }

    @Test
    fun applyFilter_query_matchesRegularAuthorViaSearchContext() {
        // v1.17：SearchContext 新增常规作者维度，搜作者名可命中其关联文件
        val source = listOf(
            makeMedia("key1", fileName = "pic1.jpg"),
            makeMedia("key2", fileName = "pic2.jpg")
        )
        val ctx = SearchContext(
            cosAuthorForMedia = emptyMap(),
            cosWorkForMedia = emptyMap(),
            sourceForMedia = emptyMap(),
            characterForMedia = emptyMap(),
            regularAuthorForMedia = mapOf("key1" to "凉子", "key2" to "阿伟")
        )
        val result = MediaBrowserLogic.applyFilter(
            source, "凉子", MediaFilterState(), emptyMap(), emptyMap(), searchContext = ctx
        )
        assertThat(result.map { it.recordKey }).containsExactly("key1")
    }

    @Test
    fun applyFilter_query_multiKeywordAuthorPlusFileName() {
        // 多关键词：一个词命中常规作者、一个词命中文件名，每词至少命中一个维度才算匹配
        val source = listOf(
            makeMedia("key1", fileName = "picture.jpg"),
            makeMedia("key2", fileName = "photo.jpg")
        )
        val ctx = SearchContext(
            cosAuthorForMedia = emptyMap(),
            cosWorkForMedia = emptyMap(),
            sourceForMedia = emptyMap(),
            characterForMedia = emptyMap(),
            regularAuthorForMedia = mapOf("key1" to "凉子", "key2" to "阿伟")
        )
        val result = MediaBrowserLogic.applyFilter(
            source, "凉子 picture", MediaFilterState(), emptyMap(), emptyMap(), searchContext = ctx
        )
        assertThat(result.map { it.recordKey }).containsExactly("key1")
    }

    @Test
    fun applyFilter_query_regularAuthorIgnoredWhenNoContext() {
        // 未传 SearchContext 时搜作者名不应命中（保证旧行为不变，不误伤文件名相同的情况）
        val source = listOf(
            makeMedia("key1", fileName = "凉子.jpg"),
            makeMedia("key2", fileName = "pic2.jpg")
        )
        val byAuthorName = MediaBrowserLogic.applyFilter(
            source, "凉子", MediaFilterState(), emptyMap(), emptyMap()
        )
        // key1 靠文件名命中，key2 的作者关联不存在
        assertThat(byAuthorName.map { it.recordKey }).containsExactly("key1")
    }

    @Test
    fun applyFilter_sortByName_ascDirectionFollowsCurrentComparator() {
        // v1.12：NAME 排序方向已修复——comparatorFor(NAME) 改为 compareByDescending，
        // applyFilter 对 ASC 取 reversed() 后 NAME+ASC 正确输出升序（A→Z）。
        // 旧实现 NAME 用 compareBy（升序），导致 NAME+ASC 输出降序（Z→A），与 UI 语义相反。
        val source = listOf(
            makeMedia("key1", fileName = "banana.jpg"),
            makeMedia("key2", fileName = "apple.jpg"),
            makeMedia("key3", fileName = "cherry.jpg")
        )
        val result = MediaBrowserLogic.applyFilter(
            source, "",
            MediaFilterState(sortKey = MediaSortKey.NAME, sortDirection = MediaSortDirection.ASC),
            emptyMap(), emptyMap()
        )
        assertThat(result.map { it.recordKey }).isEqualTo(listOf("key2", "key1", "key3"))
    }

    @Test
    fun applyFilter_sortByDateAscending_oldestFirst() {
        // ASC 方向对 DATE 生效：旧文件在前（comparatorFor(DATE) 为降序，ASC 反转成升序）
        val source = listOf(
            makeMedia("key1", modifiedAtMillis = 3_000),
            makeMedia("key2", modifiedAtMillis = 1_000),
            makeMedia("key3", modifiedAtMillis = 2_000)
        )
        val result = MediaBrowserLogic.applyFilter(
            source, "",
            MediaFilterState(sortKey = MediaSortKey.DATE, sortDirection = MediaSortDirection.ASC),
            emptyMap(), emptyMap()
        )
        assertThat(result.map { it.recordKey }).isEqualTo(listOf("key2", "key3", "key1"))
    }
}
