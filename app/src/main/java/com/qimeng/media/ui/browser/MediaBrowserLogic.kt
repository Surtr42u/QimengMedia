package com.qimeng.media.ui.browser

import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.data.prefs.RecommendationPrefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MediaSortKey { DEFAULT, DATE, INDEXED_DATE, VIEWS, PLAYS, SIZE, NAME }
enum class MediaSortDirection { DESC, ASC }
enum class MediaViewRange { ALL, NONE, FEW, SOME, MANY }
enum class MediaPlayRange { ALL, NONE, FEW, SOME, MANY }
enum class MediaSizeRange { ALL, SMALL, MEDIUM, LARGE, XLARGE }
enum class MediaRankingPeriod { ALL, DAY, WEEK, MONTH, YEAR }
enum class MediaTagMode { FUZZY, EXACT }
enum class MediaDateRange { ALL, TODAY, WEEK, MONTH, QUARTER, YEAR, YEAR_RANGE }

data class MediaFilterState(
    val sortKey: MediaSortKey = MediaSortKey.DEFAULT,
    val sortDirection: MediaSortDirection = MediaSortDirection.DESC,
    val viewRange: MediaViewRange = MediaViewRange.ALL,
    val playRange: MediaPlayRange = MediaPlayRange.ALL,
    val sizeRange: MediaSizeRange = MediaSizeRange.ALL,
    val dateRange: MediaDateRange = MediaDateRange.ALL,
    val yearStart: Int = 2020,
    val yearEnd: Int = Calendar.getInstance().get(Calendar.YEAR),
    val selectedTags: Set<String> = emptySet(),
    val tagMode: MediaTagMode = MediaTagMode.FUZZY
)

object MediaBrowserLogic {
    // 推荐算法：自适应加权评分模型
    // 根据数据状态动态调整权重：标签/点赞为空时，将无效维度权重重新分配给 discovery/randomFactor
    // 数据完善后自动恢复设计权重，无需手动调参
    suspend fun recommend(
        media: List<MediaFileEntity>,
        stats: Map<String, ViewStatsEntity>,
        tagMap: Map<String, Set<String>>,
        likeCounts: Map<String, Int> = emptyMap(),
        limit: Int = media.size,
        seed: Int = 0,
        dailyShownCountMap: Map<String, Int> = emptyMap(),
        customPrefs: RecommendationPrefs? = null
    ): List<MediaFileEntity> = withContext(Dispatchers.Default) {
        if (media.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()

        // --- 数据状态检测 ---
        val hasTags = tagMap.values.any { it.isNotEmpty() }
        val hasLikes = likeCounts.values.any { it > 0 }
        val hasHistory = stats.values.any { it.viewCount > 0 }

        // --- 权重初始化 + 自适应回收 ---
        val weights = resolveWeights(customPrefs, hasTags, hasLikes, hasHistory)
        AppLog.d("Algo", "weights: tagRel=${weights.tagRelevance} tagCol=${weights.tagCollection} eng=${weights.engagement} " +
            "rec=${weights.recency} like=${weights.likeScore} disc=${weights.discovery} fresh=${weights.freshness} " +
            "depth=${weights.browseDepth} rand=${weights.maxRandom} " +
            "hasTags=$hasTags hasLikes=$hasLikes hasHistory=$hasHistory")

        val tagRelevanceMap = buildTagRelevanceMap(media, stats, tagMap)
        val topTags = tagRelevanceMap.entries
            .sortedByDescending { it.value }
            .take(20)
            .map { it.key }
            .toSet()
        val norms = computeNormDenominators(stats, likeCounts)

        val scored = media.map { item ->
            val itemStats = stats[item.recordKey]
            val viewCount = itemStats?.viewCount ?: 0
            val tags = tagsFor(item, tagMap)
            val likeCount = likeCounts[item.recordKey] ?: 0

            // 每日推荐去重：已展示过的文件强惩罚
            val dailyPenalty = when (dailyShownCountMap[item.recordKey] ?: 0) {
                0 -> 0f       // 首次出现，不惩罚
                else -> -0.8f // 已展示过，强惩罚，让新文件排到前面
            }

            val tagRelevance = tagRelevanceScore(tags, tagRelevanceMap) * weights.tagRelevance
            val tagCollection = tagCollectionScore(tags, topTags) * weights.tagCollection
            val engagement = min(
                (viewCount + (itemStats?.playCount ?: 0)).toFloat() / norms.maxEngagement, 1f
            ) * weights.engagement
            val recency = recencyScore(itemStats?.lastOpenedAtMillis, now) * weights.recency
            val likeScore = min(likeCount.toFloat() / norms.maxLikes, 1f) * weights.likeScore
            val discovery = (1f - min(viewCount / 5f, 1f)) * weights.discovery
            val freshness = freshnessScore(item.indexedAtMillis, now) * weights.freshness
            val browseDepth = min(
                (itemStats?.totalBrowseSeconds ?: 0L).toFloat() / norms.maxBrowseSeconds, 1f
            ) * weights.browseDepth
            val randomFactor = ((item.recordKey.hashCode() xor seed).and(0xFFFF).toFloat() / 65535f) * weights.maxRandom

            item to (tagRelevance + tagCollection + engagement + recency + likeScore +
                discovery + freshness + browseDepth + randomFactor + dailyPenalty)
        }.sortedByDescending { it.second }

        // seed > 0 时对近分区间做随机打散（±0.05 内视为同分桶）
        val result = if (seed > 0) shuffleBuckets(scored, seed) else scored.map { it.first }

        // 视频图片自然混合：按比例随机取，不做连续分组
        return@withContext balanceVideoImage(result).take(limit)
    }

    /** 推荐权重（9 维），由 resolveWeights 计算得出 */
    private data class RecommendWeights(
        val tagRelevance: Float, val tagCollection: Float, val engagement: Float,
        val recency: Float, val likeScore: Float, val discovery: Float,
        val freshness: Float, val browseDepth: Float, val maxRandom: Float
    )

    /** 归一化分母（3 维），由 computeNormDenominators 计算得出 */
    private data class NormDenominators(
        val maxEngagement: Float, val maxBrowseSeconds: Float, val maxLikes: Float
    )

    /** 权重初始化（自定义偏好优先）+ 自适应回收（标签/点赞/历史为空时重新分配权重） */
    private fun resolveWeights(
        customPrefs: RecommendationPrefs?, hasTags: Boolean, hasLikes: Boolean, hasHistory: Boolean
    ): RecommendWeights {
        var wTagRelevance = customPrefs?.tagRelevance ?: 0.22f
        var wTagCollection = customPrefs?.tagCollection ?: 0.15f
        var wEngagement = customPrefs?.engagement ?: 0.10f
        var wRecency = customPrefs?.recency ?: 0.15f
        var wLikeScore = customPrefs?.likeScore ?: 0.05f
        var wDiscovery = customPrefs?.discovery ?: 0.20f
        var wFreshness = customPrefs?.freshness ?: 0.05f
        var wBrowseDepth = customPrefs?.browseDepth ?: 0.03f
        var maxRandom = customPrefs?.maxRandom ?: 0.30f

        // 标签为空时：将 tagRelevance + tagCollection 的权重分配给 discovery 和 randomFactor
        if (!hasTags) {
            val reclaimed = wTagRelevance + wTagCollection  // 0.37
            wTagRelevance = 0f
            wTagCollection = 0f
            wDiscovery += reclaimed * 0.5f    // discovery: 0.15 + 0.185 = 0.335
            maxRandom += reclaimed * 0.5f     // randomFactor: 0.30 + 0.185 = 0.485
        }
        // 点赞为空时：将 likeScore 的权重分配给 freshness
        if (!hasLikes) {
            val reclaimed = wLikeScore  // 0.05
            wLikeScore = 0f
            wFreshness += reclaimed     // freshness: 0.05 + 0.05 = 0.10
        }
        // 无浏览历史时：engagement 无意义，分配给 discovery
        if (!hasHistory) {
            val reclaimed = wEngagement  // 0.10
            wEngagement = 0f
            wDiscovery += reclaimed      // discovery: 0.335 + 0.10 = 0.435（无标签无历史时）
        }
        return RecommendWeights(wTagRelevance, wTagCollection, wEngagement, wRecency, wLikeScore, wDiscovery, wFreshness, wBrowseDepth, maxRandom)
    }

    /** 计算归一化分母（engagement/browseSeconds/likes 的最大值，至少为 1） */
    private fun computeNormDenominators(
        stats: Map<String, ViewStatsEntity>, likeCounts: Map<String, Int>
    ): NormDenominators {
        val maxEngagement = stats.values
            .maxOfOrNull { (it.viewCount + it.playCount).toFloat() }
            ?.coerceAtLeast(1f) ?: 1f
        val maxBrowseSeconds = stats.values
            .maxOfOrNull { it.totalBrowseSeconds.toFloat() }
            ?.coerceAtLeast(1f) ?: 1f
        val maxLikes = likeCounts.values
            .maxOfOrNull { it.toFloat() }
            ?.coerceAtLeast(1f) ?: 1f
        return NormDenominators(maxEngagement, maxBrowseSeconds, maxLikes)
    }

    /** seed > 0 时对近分区间做随机打散（±0.05 内视为同分桶），确保同分项随机排序 */
    private fun shuffleBuckets(scored: List<Pair<MediaFileEntity, Float>>, seed: Int): List<MediaFileEntity> {
        val rng = java.util.Random(seed.toLong())
        val buckets = mutableListOf<MutableList<MediaFileEntity>>()
        var currentBucket = mutableListOf<MediaFileEntity>()
        var lastScore = Float.MAX_VALUE
        for ((item, score) in scored) {
            if (currentBucket.isNotEmpty() && score < lastScore - 0.05f) {
                buckets.add(currentBucket)
                currentBucket = mutableListOf()
            }
            currentBucket.add(item)
            lastScore = if (currentBucket.size == 1) score else minOf(lastScore, score)
        }
        if (currentBucket.isNotEmpty()) buckets.add(currentBucket)
        return buckets.flatMap { bucket ->
            if (bucket.size > 1) bucket.shuffled(rng) else bucket
        }
    }

    fun applyFilter(
        source: List<MediaFileEntity>,
        query: String,
        state: MediaFilterState,
        stats: Map<String, ViewStatsEntity>,
        tagMap: Map<String, Set<String>>,
        @Suppress("UNUSED_PARAMETER")
        history: List<ViewHistoryEntity> = emptyList(),
        likeCounts: Map<String, Int> = emptyMap(),
        searchContext: SearchContext? = null
    ): List<MediaFileEntity> {
        val normalizedQuery = query.trim().lowercase(Locale.getDefault())
        // 多关键词搜索：按空格拆分，每个关键词必须至少匹配一个维度（文件名/文件夹名/标签/SearchContext）
        val queryKeywords = if (normalizedQuery.isBlank()) emptyList()
            else normalizedQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }

        var items = source.asSequence()
            .filter { item ->
                queryKeywords.isEmpty() || queryKeywords.all { keyword ->
                    item.fileName.lowercase(Locale.getDefault()).contains(keyword) ||
                        item.folderName.lowercase(Locale.getDefault()).contains(keyword) ||
                        tagsFor(item, tagMap).any { it.lowercase(Locale.getDefault()).contains(keyword) } ||
                        searchContext?.matches(item, keyword) == true
                }
            }
            .filter { item -> matchesViewRange(stats[item.recordKey]?.viewCount ?: 0, state.viewRange) }
            .filter { item -> matchesPlayRange(stats[item.recordKey]?.playCount ?: 0, state.playRange) }
            .filter { item -> matchesSizeRange(item.sizeBytes, state.sizeRange) }
            .filter { item -> matchesDateRange(item.modifiedAtMillis, state) }
            .filter { item ->
                if (state.selectedTags.isEmpty()) return@filter true
                val tags = tagsFor(item, tagMap)
                if (state.tagMode == MediaTagMode.EXACT) state.selectedTags.all { it in tags }
                else state.selectedTags.any { it in tags }
            }
            .toList()

        val comparator = comparatorFor(state.sortKey, stats, emptySet(), likeCounts)
        items = if (state.sortDirection == MediaSortDirection.DESC) {
            items.sortedWith(comparator)
        } else {
            items.sortedWith(comparator.reversed())
        }
        return items
    }

    fun rank(
        source: List<MediaFileEntity>,
        period: MediaRankingPeriod,
        stats: Map<String, ViewStatsEntity>,
        history: List<ViewHistoryEntity>,
        likeCounts: Map<String, Int> = emptyMap()
    ): List<MediaFileEntity> {
        val periodKeys = keysInPeriod(period, history, stats)
        val ranked = if (period == MediaRankingPeriod.ALL) source else source.filter { it.recordKey in periodKeys }
        return ranked.sortedWith(comparatorFor(MediaSortKey.VIEWS, stats, periodKeys, likeCounts))
    }

    fun groupByDate(items: List<MediaFileEntity>): List<MediaDateGroup> {
        if (items.isEmpty()) return emptyList()
        return items.groupBy { dateLabel(it.modifiedAtMillis) }
            .map { (label, groupItems) -> MediaDateGroup(label, groupItems) }
    }

    private val sizeUnits = arrayOf("B", "KB", "MB", "GB")
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatSize(bytes: Long, zeroLabel: String = "0 B", decimals: Int = 1): String {
        if (bytes <= 0L) return zeroLabel
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < sizeUnits.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val d = if (unitIndex == 0) 0 else decimals
        return "%.${d}f %s".format(Locale.US, value, sizeUnits[unitIndex])
    }

    fun formatDate(millis: Long, fallback: String = "-"): String = if (millis <= 0L) {
        fallback
    } else {
        dateTimeFormat.format(Date(millis))
    }

    fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val weekLabels = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun dateLabel(millis: Long, now: Calendar? = null, todayStart: Calendar? = null): String {
        if (millis <= 0L) return "未知日期"
        val n = now ?: Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val ts = todayStart ?: Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffDays = ((ts.timeInMillis - target.timeInMillis) / 86_400_000L).toInt()
        return when {
            sameDay(n, target) -> "今天"
            diffDays == 1 -> "昨天"
            diffDays in 2..6 -> weekLabels[target.get(Calendar.DAY_OF_WEEK) - 1]
            else -> dateFormat.format(Date(millis))
        }
    }

    fun periodLabel(period: MediaRankingPeriod): String = when (period) {
        MediaRankingPeriod.ALL -> "总榜"
        MediaRankingPeriod.DAY -> "日榜"
        MediaRankingPeriod.WEEK -> "周榜"
        MediaRankingPeriod.MONTH -> "月榜"
        MediaRankingPeriod.YEAR -> "年榜"
    }

    private fun buildTagRelevanceMap(
        media: List<MediaFileEntity>,
        stats: Map<String, ViewStatsEntity>,
        tagMap: Map<String, Set<String>>
    ): Map<String, Float> {
        val mediaByKey = media.associateBy { it.recordKey }
        val tagViewCounts = mutableMapOf<String, Int>()
        stats.values.filter { it.viewCount > 0 }.forEach { stat ->
            val item = mediaByKey[stat.recordKey] ?: return@forEach
            tagsFor(item, tagMap).forEach { tag ->
                tagViewCounts[tag] = (tagViewCounts[tag] ?: 0) + 1
            }
        }
        if (tagViewCounts.isEmpty()) return emptyMap()
        val maxCount = tagViewCounts.values.max()
        return tagViewCounts.mapValues { (_, count) -> count.toFloat() / maxCount }
    }

    private fun tagRelevanceScore(tags: Set<String>, relevanceMap: Map<String, Float>): Float {
        if (tags.isEmpty() || relevanceMap.isEmpty()) return 0.2f
        return min(tags.map { relevanceMap[it] ?: 0f }.average().toFloat(), 1f)
    }

    private fun tagCollectionScore(tags: Set<String>, topTags: Set<String>): Float {
        if (tags.isEmpty() || topTags.isEmpty()) return 0.15f
        val intersection = tags.intersect(topTags).size
        val union = tags.union(topTags).size
        return if (union > 0) intersection.toFloat() / union else 0f
    }

    private fun freshnessScore(indexedAtMillis: Long, now: Long): Float {
        if (indexedAtMillis <= 0L) return 0f
        val ageDays = (now - indexedAtMillis) / 86_400_000f
        return when {
            ageDays < 1f -> 1f
            ageDays < 3f -> 0.7f
            ageDays < 7f -> 0.4f
            ageDays < 30f -> 0.2f
            else -> 0f
        }
    }

    private fun tagsFor(item: MediaFileEntity, tagMap: Map<String, Set<String>>): Set<String> {
        val manual = tagMap[item.recordKey].orEmpty()
        val inferred = item.fileName.substringBeforeLast('.').split('_', '-', ' ')
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { it.isNotBlank() && it.length <= 16 }
        return manual + inferred
    }

    /**
     * 视频图片自然混合：从排序结果中按比例随机取视频和图片，
     * 不做连续分组，让视频和图片自然交错，看起来像随机混合而非人工排列。
     *
     * 策略：每次取元素时，按剩余数量比例随机决定取视频还是图片，
     * 比例接近时约 50/50，某方少时自动偏向另一方。
     *
     * trade-off：此操作会打乱评分排序，低评分视频可能排在高评分图片前面，
     * 牺牲部分排序精度换取浏览体验的视觉多样性。
     */
    private fun balanceVideoImage(sorted: List<MediaFileEntity>): List<MediaFileEntity> {
        val videos = ArrayDeque(sorted.filter { it.mediaType.equals(MediaType.VIDEO, ignoreCase = true) })
        val images = ArrayDeque(sorted.filter { !it.mediaType.equals(MediaType.VIDEO, ignoreCase = true) })
        val result = mutableListOf<MediaFileEntity>()
        val rng = java.util.Random()

        while (videos.isNotEmpty() || images.isNotEmpty()) {
            // 按剩余数量比例随机选择类型，自然混合
            val pickVideo = when {
                videos.isEmpty() -> false
                images.isEmpty() -> true
                else -> rng.nextDouble() < videos.size.toDouble() / (videos.size + images.size)
            }
            val picked = if (pickVideo) videos.removeFirst() else images.removeFirst()
            result.add(picked)
        }
        AppLog.d("Home", "balanceVideoImage: videos=${sorted.count { it.mediaType.equals(MediaType.VIDEO, ignoreCase = true) }}, images=${sorted.count { !it.mediaType.equals(MediaType.VIDEO, ignoreCase = true) }}, resultTop5=${result.take(5).joinToString { if (it.mediaType.equals(MediaType.VIDEO, ignoreCase = true)) "V" else "I" }}")
        return result
    }

    private fun recencyScore(lastOpenedAtMillis: Long?, now: Long): Float {
        val last = lastOpenedAtMillis ?: return 0.3f
        val days = (now - last) / 86_400_000f
        return when {
            days < 1f -> 1f
            days < 3f -> 0.8f
            days < 7f -> 0.5f
            days < 30f -> 0.2f
            else -> 0f
        }
    }

    private fun comparatorFor(
        sortKey: MediaSortKey,
        stats: Map<String, ViewStatsEntity>,
        periodKeys: Set<String>,
        likeCounts: Map<String, Int> = emptyMap()
    ): Comparator<MediaFileEntity> = when (sortKey) {
        MediaSortKey.DEFAULT, MediaSortKey.DATE -> compareByDescending<MediaFileEntity> { it.modifiedAtMillis }
        MediaSortKey.INDEXED_DATE -> compareByDescending<MediaFileEntity> { it.indexedAtMillis }
        MediaSortKey.VIEWS -> compareByDescending<MediaFileEntity> {
            val stat = stats[it.recordKey]
            val periodBonus = if (periodKeys.isNotEmpty() && it.recordKey in periodKeys) 1_000_000 else 0
            val views = (stat?.viewCount ?: 0) + (stat?.playCount ?: 0)
            val likes = likeCounts[it.recordKey] ?: 0
            periodBonus + views + likes
        }.thenByDescending { it.modifiedAtMillis }
        MediaSortKey.PLAYS -> compareByDescending<MediaFileEntity> {
            val stat = stats[it.recordKey]
            stat?.playCount ?: 0
        }.thenByDescending { it.modifiedAtMillis }
        MediaSortKey.SIZE -> compareByDescending { it.sizeBytes }
        MediaSortKey.NAME -> compareBy { it.fileName.lowercase(Locale.getDefault()) }
    }

    private fun matchesViewRange(count: Int, range: MediaViewRange): Boolean = when (range) {
        MediaViewRange.ALL -> true
        MediaViewRange.NONE -> count == 0
        MediaViewRange.FEW -> count in 1 until 5
        MediaViewRange.SOME -> count in 5 until 20
        MediaViewRange.MANY -> count >= 20
    }

    private fun matchesPlayRange(count: Int, range: MediaPlayRange): Boolean = when (range) {
        MediaPlayRange.ALL -> true
        MediaPlayRange.NONE -> count == 0
        MediaPlayRange.FEW -> count in 1 until 5
        MediaPlayRange.SOME -> count in 5 until 20
        MediaPlayRange.MANY -> count >= 20
    }

    private fun matchesSizeRange(size: Long, range: MediaSizeRange): Boolean = when (range) {
        MediaSizeRange.ALL -> true
        MediaSizeRange.SMALL -> size < 1_000_000L
        MediaSizeRange.MEDIUM -> size in 1_000_000L until 10_000_000L
        MediaSizeRange.LARGE -> size in 10_000_000L until 50_000_000L
        MediaSizeRange.XLARGE -> size >= 50_000_000L
    }

    private fun matchesDateRange(millis: Long, state: MediaFilterState): Boolean {
        val bounds = dateRangeBounds(state) ?: return true
        return millis in bounds.first..bounds.second
    }

    private fun dateRangeBounds(state: MediaFilterState): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        return when (state.dateRange) {
            MediaDateRange.ALL -> null
            MediaDateRange.TODAY -> {
                val start = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                start to now
            }
            MediaDateRange.WEEK -> (now - 604_800_000L) to now
            MediaDateRange.MONTH -> (now - 2_592_000_000L) to now
            MediaDateRange.QUARTER -> (now - 7_776_000_000L) to now
            MediaDateRange.YEAR -> (now - 31_536_000_000L) to now
            MediaDateRange.YEAR_RANGE -> yearBounds(state.yearStart, state.yearEnd)
        }
    }

    private fun yearBounds(startYear: Int, endYear: Int): Pair<Long, Long> {
        val start = yearStartMillis(startYear)
        val end = Calendar.getInstance().apply {
            set(endYear, Calendar.DECEMBER, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        return start to end
    }

    private fun yearStartMillis(year: Int): Long = Calendar.getInstance().apply {
        set(year, Calendar.JANUARY, 1, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun periodCutoff(period: MediaRankingPeriod): Long? {
        val now = System.currentTimeMillis()
        return when (period) {
            MediaRankingPeriod.ALL -> null
            MediaRankingPeriod.DAY -> now - 86_400_000L
            MediaRankingPeriod.WEEK -> now - 604_800_000L
            MediaRankingPeriod.MONTH -> now - 2_592_000_000L
            MediaRankingPeriod.YEAR -> now - 31_536_000_000L
        }
    }

    private fun keysInPeriod(
        period: MediaRankingPeriod,
        history: List<ViewHistoryEntity>,
        stats: Map<String, ViewStatsEntity>
    ): Set<String> {
        val cutoff = periodCutoff(period) ?: return emptySet()
        val historyKeys = history.filter { it.openedAtMillis >= cutoff }.map { it.recordKey }
        val statKeys = stats.values.filter { (it.lastOpenedAtMillis ?: 0L) >= cutoff }.map { it.recordKey }
        return (historyKeys + statKeys).toSet()
    }

    private fun sameDay(first: Calendar, second: Calendar): Boolean =
        first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}

data class MediaDateGroup(
    val dateLabel: String,
    val items: List<MediaFileEntity>
)

/**
 * 搜索上下文：提供出处/角色/作者/作品名匹配能力
 * 用于首页搜索时匹配 COS 作者名/作品名和常规出处/角色名
 */
class SearchContext(
    private val cosAuthorForMedia: Map<String, String>,   // recordKey → COS作者名
    private val cosWorkForMedia: Map<String, String>,     // recordKey → COS作品名
    private val sourceForMedia: Map<String, String?>,     // recordKey → 出处名（常规文件）
    private val characterForMedia: Map<String, String?>   // recordKey → 角色名（常规文件）
) {
    /**
     * 多关键词匹配：将查询词按空格拆分，每个关键词必须至少匹配一个维度（出处/角色/作者/作品）。
     * 例如 "守望先锋 DVA" 拆为 ["守望先锋", "dva"]，
     * "守望先锋" 匹配出处，"dva" 匹配角色，两个都命中才算匹配成功。
     */
    fun matches(item: MediaFileEntity, normalizedQuery: String): Boolean {
        if (normalizedQuery.isBlank()) return false
        val keywords = normalizedQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (keywords.isEmpty()) return false
        // 单关键词：直接做 contains 匹配
        if (keywords.size == 1) return matchesSingleKeyword(item, keywords[0])
        // 多关键词：每个关键词都必须至少匹配一个维度
        return keywords.all { keyword -> matchesSingleKeyword(item, keyword) }
    }

    private fun matchesSingleKeyword(item: MediaFileEntity, keyword: String): Boolean {
        val key = item.recordKey
        cosAuthorForMedia[key]?.lowercase(Locale.getDefault())?.let {
            if (it.contains(keyword)) return true
        }
        cosWorkForMedia[key]?.lowercase(Locale.getDefault())?.let {
            if (it.contains(keyword)) return true
        }
        sourceForMedia[key]?.lowercase(Locale.getDefault())?.let {
            if (it.contains(keyword)) return true
        }
        characterForMedia[key]?.lowercase(Locale.getDefault())?.let {
            if (it.contains(keyword)) return true
        }
        return false
    }
}
