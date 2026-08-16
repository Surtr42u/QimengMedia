package com.qimeng.media.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.data.db.entity.DailyBrowseEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.databinding.FragmentStatsDetailBinding
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.widget.LineChartView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 统计详情页（v1.7 新增，v1.14 重构）。
 *
 * 点击数据统计页的卡片后进入的全新详情界面，包含：
 * - 顶部返回栏 + 标题
 * - 统计摘要卡片（总和/均值/峰值等，根据模式动态生成）
 * - 辅助图表（趋势折线图，根据模式动态生成）
 * - 完整 Top 20 排行榜列表（可点击跳转文件详情/作者文件页）
 *
 * 支持四种模式：
 * - [MODE_TYPE_TREND]：分类型浏览趋势（图片/视频/动图各自趋势 + 本地 vs COS 来源趋势）
 * - [MODE_FAVORITES]：常看文件详情（热门/沉浸最长/反复回看/新晋热门）
 * - [MODE_AUTHORS]：常看作者与标签详情（两张独立排行卡：常看作者 Top 15 + 常看标签 Top 10，作者条目跳作者文件页，标签条目跳搜索页）
 * - [MODE_DISTRIBUTION]：分布统计详情（类型/来源库存 + 窗口浏览对比）
 *
 * 全部模式随时间范围（7天/30天/全部）联动：窗口内统计基于按天浏览聚合。
 */
class StatsDetailFragment : Fragment() {
    private var _binding: FragmentStatsDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }

    private val adapter by lazy {
        RankListAdapter { item -> item.id?.let { handleItemClick(it) } }
    }

    /** 标签排行列表 adapter（仅 MODE_AUTHORS 使用，与作者排行同卡片样式、同点击跳转规则） */
    private val tagAdapter by lazy {
        RankListAdapter { item -> item.id?.let { handleItemClick(it) } }
    }

    private var mode: String = MODE_TYPE_TREND
    private var timeRangeName: String = "DAYS_7"
    private var cachedDaily: List<DailyBrowseEntity> = emptyList()
    private var cachedCosKeys: Set<String> = emptySet()
    /** 类型趋势选择（图片/视频/动图），默认全选 */
    private val typeSelections = mutableSetOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.ANIMATED_IMAGE)
    /** 来源趋势选择（常规/COS），默认全选 */
    private val sourceSelections = mutableSetOf(KEY_NORMAL, KEY_COS)
    private val typeCapsules = linkedMapOf<String, TextView>()
    private val sourceCapsules = linkedMapOf<String, TextView>()
    private var typeChart: LineChartView? = null
    private var sourceChart: LineChartView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = arguments?.getString(ARG_MODE) ?: MODE_TYPE_TREND
        timeRangeName = arguments?.getString(ARG_TIME_RANGE) ?: "DAYS_7"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.backButton.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        binding.rankRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@StatsDetailFragment.adapter
            isNestedScrollingEnabled = false
        }
        binding.tagRankRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@StatsDetailFragment.tagAdapter
            isNestedScrollingEnabled = false
        }
        observeData()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // v1.14 修复：来源趋势 COS 判定依赖当前库存 cosMedia 的 recordKey 集合（daily_browse
                // 表无 isCosFile 字段），原两 collector 首帧数据不齐会导致先画错再重画闪烁。
                // 改为 combine 等待 dailyBrowse 与 cosMedia 数据齐备后再统一渲染。
                launch {
                    combine(viewModel.dailyBrowse, viewModel.cosMedia) { daily, cosMedia ->
                        daily to cosMedia
                    }.collect { (daily, cosMedia) ->
                        cachedDaily = daily
                        cachedCosKeys = cosMedia.map { it.recordKey }.toSet()
                        renderByMode()
                    }
                }
                when (mode) {
                    MODE_TYPE_TREND -> observeTypeTrendMode()
                    MODE_FAVORITES -> observeFavoritesMode()
                    MODE_AUTHORS -> observeAuthorsTagsMode()
                    MODE_DISTRIBUTION -> observeDistributionMode()
                }
            }
        }
    }

    /** 数据流就绪后按当前模式渲染（daily/stats 任一变化即重渲染） */
    private fun renderByMode() {
        when (mode) {
            MODE_TYPE_TREND -> renderTypeTrend()
            MODE_FAVORITES -> renderFavorites()
            MODE_AUTHORS -> renderAuthorsTags()
            MODE_DISTRIBUTION -> renderDistribution()
        }
    }

    /** 窗口内浏览聚合（按时间范围过滤） */
    private fun windowDaily(): List<DailyBrowseEntity> {
        val cutoff = StatsFormatHelper.cutoffForRange(timeRangeName)
        return if (timeRangeName == "ALL") cachedDaily else cachedDaily.filter { it.dayStartMillis >= cutoff }
    }

    private val rangeTitleSuffix: String
        get() = when (timeRangeName) {
            "DAYS_7" -> " · 近7天"
            "DAYS_30" -> " · 近30天"
            else -> " · 全部"
        }

    // ==================== 分类型浏览趋势模式 ====================

    private fun CoroutineScope.observeTypeTrendMode() {
        binding.titleText.text = "类型浏览趋势" + rangeTitleSuffix
        binding.listTitleText.text = "类型趋势"
        binding.rankListContainer.visibility = View.VISIBLE
        binding.tagRankContainer.visibility = View.GONE
        binding.distributionContainer.visibility = View.GONE
        binding.sortToggleText.visibility = View.GONE
        binding.listSubtitleText.text = "选择类型/来源，叠加对比各趋势"
    }

    /** 渲染分类型趋势：两张独立卡（类型一张 + 来源一张），各自可多选叠加 */
    private fun renderTypeTrend() {
        val container = binding.chartContainer
        if (container.childCount == 0) {
            container.addView(buildTrendCard("类型浏览趋势", listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.ANIMATED_IMAGE), typeSelections, typeCapsules) { typeChart = it })
            container.addView(buildTrendCard("来源浏览趋势", listOf(KEY_NORMAL, KEY_COS), sourceSelections, sourceCapsules) { sourceChart = it })
        }
        updateTrendCapsules(typeSelections, typeCapsules)
        updateTrendCapsules(sourceSelections, sourceCapsules)
        typeChart?.setMultiSeries(buildTypeSeries(windowDaily()))
        sourceChart?.setMultiSeries(buildSourceSeries(windowDaily()))
    }

    /** 构建一张趋势卡：标题 + 可多选胶囊行 + 折线对比图 */
    private fun buildTrendCard(
        title: String,
        keys: List<String>,
        selections: MutableSet<String>,
        capsules: LinkedHashMap<String, TextView>,
        onChart: (LineChartView) -> Unit
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(requireContext(), R.drawable.bg_stat_card)
            setPadding(12f.dp(requireContext()).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12f.dp(requireContext()).toInt() }

            val titleView = TextView(requireContext()).apply {
                text = title
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextPrimary))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            addView(titleView)

            addView(buildTrendCapsuleRow(keys, selections, capsules))

            val chart = LineChartView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    240f.dp(requireContext()).toInt()
                ).apply { topMargin = 8f.dp(requireContext()).toInt() }
            }
            onChart(chart)
            addView(chart)
        }
    }

    /** 构建单行可多选胶囊（选中填充主题色+反色文字，未选中透明+次级色） */
    private fun buildTrendCapsuleRow(
        keys: List<String>,
        selections: MutableSet<String>,
        capsules: LinkedHashMap<String, TextView>
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8f.dp(requireContext()).toInt() }
            keys.forEach { key ->
                val capsule = TextView(requireContext()).apply {
                    text = trendLabel(key)
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        34f.dp(requireContext()).toInt()
                    ).apply {
                        weight = 1f
                        if (key != keys.first()) marginStart = 6f.dp(requireContext()).toInt()
                    }
                    setOnClickListener { toggleTrend(key, selections, capsules) }
                }
                capsules[key] = capsule
                addView(capsule)
            }
        }
    }

    /** 切换选中态（该卡至少保留一个维度，避免空图） */
    private fun toggleTrend(key: String, selections: MutableSet<String>, capsules: LinkedHashMap<String, TextView>) {
        if (selections.size == 1 && key in selections) return
        if (!selections.add(key)) selections.remove(key)
        updateTrendCapsules(selections, capsules)
        val window = windowDaily()
        typeChart?.setMultiSeries(buildTypeSeries(window))
        sourceChart?.setMultiSeries(buildSourceSeries(window))
    }

    /** 刷新胶囊选中样式 */
    private fun updateTrendCapsules(selections: MutableSet<String>, capsules: LinkedHashMap<String, TextView>) {
        capsules.forEach { (key, tv) ->
            if (key in selections) {
                tv.setBackgroundResource(R.drawable.bg_capsule_primary)
                tv.setTextColor(requireContext().resolveThemeColor(R.attr.qmColorBg))
            } else {
                tv.background = null
                tv.setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextSecondary))
            }
        }
    }

    /** 构建类型趋势系列（选中的类型各一条线，7天/30天按天、全部按周） */
    private fun buildTypeSeries(window: List<DailyBrowseEntity>): List<LineChartView.Series> {
        return listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.ANIMATED_IMAGE).mapNotNull { type ->
            if (type !in typeSelections) return@mapNotNull null
            val points = when (timeRangeName) {
                "DAYS_7" -> StatsFormatHelper.groupDailyByType(window, type, 7)
                "DAYS_30" -> StatsFormatHelper.groupDailyByType(window, type, 30)
                else -> StatsFormatHelper.groupDailyByTypeWeek(window, type)
            }
            LineChartView.Series(trendLabel(type), trendColor(type), points)
        }
    }

    /** 构建来源趋势系列（选中的来源各一条线） */
    private fun buildSourceSeries(window: List<DailyBrowseEntity>): List<LineChartView.Series> {
        val series = mutableListOf<LineChartView.Series>()
        if (KEY_NORMAL in sourceSelections) {
            series.add(LineChartView.Series("常规", trendColor(KEY_NORMAL), sourceTrendPoints(window, false)))
        }
        if (KEY_COS in sourceSelections) {
            series.add(LineChartView.Series("COS", trendColor(KEY_COS), sourceTrendPoints(window, true)))
        }
        return series
    }

    /** 来源（常规/COS）趋势点：按天对齐的聚合点 */
    private fun sourceTrendPoints(window: List<DailyBrowseEntity>, isCos: Boolean): List<LineChartView.Point> =
        when (timeRangeName) {
            "DAYS_7" -> groupDailyByCos(window, cachedCosKeys, isCos, 7)
            "DAYS_30" -> groupDailyByCos(window, cachedCosKeys, isCos, 30)
            else -> groupDailyByCosWeek(window, cachedCosKeys, isCos)
        }

    /** 趋势维度显示名 */
    private fun trendLabel(key: String): String = when (key) {
        MediaType.IMAGE -> "图片"
        MediaType.VIDEO -> "视频"
        MediaType.ANIMATED_IMAGE -> "动图"
        KEY_NORMAL -> "常规"
        else -> "COS"
    }

    /** 趋势维度固定颜色（Material 300-400 级，明暗主题下均清晰可辨） */
    private fun trendColor(key: String): Int = when (key) {
        MediaType.IMAGE -> 0xFF4FC3F7.toInt()
        MediaType.VIDEO -> 0xFFFF8A65.toInt()
        MediaType.ANIMATED_IMAGE -> 0xFFAED581.toInt()
        KEY_NORMAL -> 0xFF7986CB.toInt()
        else -> 0xFFF06292.toInt()
    }

    /** 来源（常规/COS）趋势：按天对齐的聚合点 */
    private fun groupDailyByCos(
        rows: List<DailyBrowseEntity>,
        cosKeys: Set<String>,
        isCos: Boolean,
        days: Int
    ): List<LineChartView.Point> {
        val filtered = rows.filter { (it.recordKey in cosKeys) == isCos }
        return StatsFormatHelper.groupDailyByDay(filtered, days)
    }

    private fun groupDailyByCosWeek(
        rows: List<DailyBrowseEntity>,
        cosKeys: Set<String>,
        isCos: Boolean
    ): List<LineChartView.Point> {
        val filtered = rows.filter { (it.recordKey in cosKeys) == isCos }
        return StatsFormatHelper.groupDailyByWeek(filtered)
    }

    // ==================== 常看文件模式 ====================

    private fun CoroutineScope.observeFavoritesMode() {
        binding.titleText.text = "常看文件" + rangeTitleSuffix
        binding.listTitleText.text = "常看排行"
        binding.rankListContainer.visibility = View.VISIBLE
        binding.tagRankContainer.visibility = View.GONE
        binding.distributionContainer.visibility = View.GONE
        binding.sortToggleText.visibility = View.VISIBLE
        setupFilesSortToggle()
    }

    /** 文件排序切换胶囊：按热度(浏览+播放) / 按时长(沉浸) */
    private var filesSortByHeat = true
    private fun setupFilesSortToggle() {
        binding.sortToggleText.text = if (filesSortByHeat) "按热度" else "按时长"
        binding.sortToggleText.setOnClickListener {
            filesSortByHeat = !filesSortByHeat
            binding.sortToggleText.text = if (filesSortByHeat) "按热度" else "按时长"
            renderFavorites()
        }
    }

    /** 渲染常看文件：摘要 + 洞察 + 排行（按热度/时长） */
    private fun renderFavorites() {
        val window = windowDaily()
        val byKey = window
            .filter { it.viewCount + it.playCount > 0 || it.totalBrowseSeconds > 0 }
            .groupBy { it.recordKey }
        val totalViews = window.sumOf { it.viewCount + it.playCount }
        val totalSeconds = window.sumOf { it.totalBrowseSeconds }

        // 排行数据（key → (文件名, 次数, 时长)）
        val rankEntries = byKey.map { (key, list) ->
            Triple(
                key,
                list.first().fileName,
                list.sumOf { it.viewCount + it.playCount } to list.sumOf { it.totalBrowseSeconds }
            )
        }
        val sorted = if (filesSortByHeat) {
            rankEntries.sortedByDescending { it.third.first }
        } else {
            rankEntries.filter { it.third.second > 0 }.sortedByDescending { it.third.second }
        }
        val filesWithViews = byKey.size
        val avgViews = if (filesWithViews > 0) totalViews.toFloat() / filesWithViews else 0f

        renderSummary(
            listOf(
                SummaryItem("总浏览", StatsFormatHelper.formatNumber(totalViews)),
                SummaryItem("浏览时长", formatDuration(totalSeconds)),
                SummaryItem("常看文件", StatsFormatHelper.formatNumber(filesWithViews)),
                SummaryItem("平均浏览次数", String.format(Locale.US, "%.1f", avgViews))
            )
        )
        renderInsights(
            listOf("近${rangeLabel()}共浏览 $totalViews 次，停留 ${formatDuration(totalSeconds)}")
        )

        val maxValue = sorted.firstOrNull()?.let { if (filesSortByHeat) it.third.first else it.third.second.toInt() } ?: 0
        binding.listSubtitleText.text = "共 $filesWithViews 个有浏览记录的文件"
        val items = sorted.take(20).map { (key, name, counts) ->
            if (filesSortByHeat) {
                RankListAdapter.RankItem(
                    id = key,
                    title = name,
                    subtitle = "浏览 ${counts.first} 次 · 停留 ${formatDuration(counts.second)}",
                    value = counts.first,
                    valueLabel = "${counts.first} 次",
                    maxValue = maxValue
                )
            } else {
                RankListAdapter.RankItem(
                    id = key,
                    title = name,
                    subtitle = "浏览 ${counts.first} 次",
                    value = counts.second.toInt(),
                    valueLabel = formatDuration(counts.second),
                    maxValue = maxValue
                )
            }
        }
        adapter.submitList(items)
    }

    private fun rangeLabel(): String = when (timeRangeName) {
        "DAYS_7" -> "7天"
        "DAYS_30" -> "30天"
        else -> "全部时间"
    }

    // ==================== 常看作者与标签模式 ====================

    private fun CoroutineScope.observeAuthorsTagsMode() {
        binding.titleText.text = "常看作者与标签" + rangeTitleSuffix
        // v1.16 拆分：原单卡「常看排行」拆为「常看作者」+「常看标签」两张独立卡
        binding.listTitleText.text = "常看作者"
        binding.rankListContainer.visibility = View.VISIBLE
        binding.listSubtitleText.text = "按浏览聚合 · Top 15"
        binding.tagRankContainer.visibility = View.VISIBLE
        binding.tagListTitleText.text = "常看标签"
        binding.tagListSubtitleText.text = "按浏览聚合 · Top 10"
        binding.distributionContainer.visibility = View.GONE
        binding.sortToggleText.visibility = View.GONE
        launch {
            combine(viewModel.authors, viewModel.allAuthorMedia) { authors, refs ->
                authors to refs
            }.collect { (authors, refs) ->
                cachedAuthors = authors
                cachedAuthorRefs = refs
                renderAuthorsTags()
            }
        }
        launch {
            viewModel.allMediaTags.collect { tags ->
                cachedMediaTags = tags
                renderAuthorsTags()
            }
        }
    }

    private var cachedAuthors: List<com.qimeng.media.data.db.entity.AuthorEntity> = emptyList()
    private var cachedAuthorRefs: List<com.qimeng.media.data.db.entity.AuthorMediaCrossRef> = emptyList()
    private var cachedMediaTags: List<com.qimeng.media.data.db.model.MediaTagName> = emptyList()

    /** 渲染作者/标签浏览排行（两张独立卡：常看作者 Top 15 + 常看标签 Top 10，条目可点击跳作者文件页/搜索页） */
    private fun renderAuthorsTags() {
        val window = windowDaily()
        val viewsByKey = window
            .filter { it.viewCount + it.playCount > 0 }
            .associate { it.recordKey to (it.viewCount + it.playCount) }

        // 作者浏览聚合
        val authorRows = cachedAuthors.mapNotNull { author ->
            val views = cachedAuthorRefs
                .filter { it.authorId == author.authorId && it.recordKey in viewsByKey }
                .sumOf { viewsByKey[it.recordKey] ?: 0 }
            if (views > 0) Triple(author.authorId, author.displayName, views) else null
        }.sortedByDescending { it.third }

        // 标签浏览聚合
        val tagRows = cachedMediaTags
            .filter { it.recordKey in viewsByKey }
            .groupBy { it.name }
            .map { (name, list) -> Triple(name, name, list.sumOf { viewsByKey[it.recordKey] ?: 0 }) }
            .sortedByDescending { it.third }

        val totalViews = window.sumOf { it.viewCount + it.playCount }
        renderSummary(
            listOf(
                SummaryItem("作者", StatsFormatHelper.formatNumber(authorRows.size)),
                SummaryItem("标签", StatsFormatHelper.formatNumber(tagRows.size)),
                SummaryItem("浏览总次数", StatsFormatHelper.formatNumber(totalViews)),
                SummaryItem("Top 作者", authorRows.firstOrNull()?.second ?: "—")
            )
        )
        val insights = mutableListOf<String>()
        if (authorRows.isNotEmpty()) {
            insights.add("最常看作者「${authorRows.first().second}」，浏览 ${authorRows.first().third} 次")
        }
        if (tagRows.isNotEmpty()) {
            insights.add("最常看标签「${tagRows.first().first}」，浏览 ${tagRows.first().third} 次")
        }
        renderInsights(insights)

        val maxAuthor = authorRows.firstOrNull()?.third ?: 0
        val maxTag = tagRows.firstOrNull()?.third ?: 0
        val authorItems = authorRows.take(15).map { (id, name, views) ->
            RankListAdapter.RankItem(
                id = id,
                title = name,
                subtitle = "作者",
                value = views,
                valueLabel = "$views 次",
                maxValue = maxAuthor
            )
        }
        val tagItems = tagRows.take(10).map { (name, _, views) ->
            RankListAdapter.RankItem(
                id = "tag:$name",
                title = name,
                subtitle = "标签",
                value = views,
                valueLabel = "$views 次",
                maxValue = maxTag
            )
        }
        adapter.submitList(authorItems)
        tagAdapter.submitList(tagItems)
    }

    // ==================== 分布统计模式 ====================

    private fun CoroutineScope.observeDistributionMode() {
        binding.titleText.text = "分布统计详情"
        binding.rankListContainer.visibility = View.GONE
        binding.tagRankContainer.visibility = View.GONE
        binding.distributionContainer.visibility = View.VISIBLE
        launch {
            combine(viewModel.allMedia, viewModel.allStats) { media, stats ->
                Pair(media, stats)
            }.collect { (media, _) ->
                cachedMedia = media
                renderDistribution()
            }
        }
    }

    private var cachedMedia: List<MediaFileEntity> = emptyList()

    /** 渲染分布对比：类型/来源 ×（库存数量/大小 + 窗口浏览） */
    private fun renderDistribution() {
        val media = cachedMedia
        val window = windowDaily()
        val cosKeys = media.filter { it.isCosFile }.map { it.recordKey }.toSet()

        val imageCount = media.count { it.mediaType == MediaType.IMAGE }
        val videoCount = media.count { it.mediaType == MediaType.VIDEO }
        val animatedCount = media.count { it.mediaType == MediaType.ANIMATED_IMAGE }
        val cosCount = media.count { it.isCosFile }
        val normalCount = media.count { !it.isCosFile }
        val total = media.size.coerceAtLeast(1)

        val imageBytes = media.filter { it.mediaType == MediaType.IMAGE }.sumOf { it.sizeBytes }
        val videoBytes = media.filter { it.mediaType == MediaType.VIDEO }.sumOf { it.sizeBytes }
        val animatedBytes = media.filter { it.mediaType == MediaType.ANIMATED_IMAGE }.sumOf { it.sizeBytes }

        // 窗口浏览聚合（按类型/来源）
        val viewsByType = StatsFormatHelper.aggregateDailyByType(window, timeRangeName)
        val imageViews = viewsByType[MediaType.IMAGE]?.first ?: 0
        val videoViews = viewsByType[MediaType.VIDEO]?.first ?: 0
        val animatedViews = viewsByType[MediaType.ANIMATED_IMAGE]?.first ?: 0
        val sourceViews = StatsFormatHelper.aggregateDailyBySource(window, cosKeys, timeRangeName)
        val cosViews = sourceViews[true] ?: 0
        val normalViews = sourceViews[false] ?: 0

        renderSummary(
            listOf(
                SummaryItem("图片", "${imageCount} (${imageCount * 100 / total}%)"),
                SummaryItem("视频", "${videoCount} (${videoCount * 100 / total}%)"),
                SummaryItem("动图", "${animatedCount} (${animatedCount * 100 / total}%)"),
                SummaryItem("常规 / COS", "${normalCount} / ${cosCount}"),
                SummaryItem("图片总大小", StatsFormatHelper.formatSize(imageBytes)),
                SummaryItem("视频总大小", StatsFormatHelper.formatSize(videoBytes)),
                SummaryItem("总占用", StatsFormatHelper.formatSize(imageBytes + videoBytes + animatedBytes)),
                SummaryItem("窗口浏览", StatsFormatHelper.formatNumber(window.sumOf { it.viewCount + it.playCount }))
            )
        )

        val insights = mutableListOf<String>()
        insights.add("近${rangeLabel()}图片被浏览 $imageViews 次，视频 $videoViews 次，动图 $animatedViews 次")
        if (cosCount > 0) {
            insights.add("COS 文件 $cosCount 个（${cosCount * 100 / total}%），窗口浏览 $cosViews 次")
        }
        renderInsights(insights)

        // 分布对比卡片（库存数量/大小 + 窗口浏览）
        binding.distributionContainer.removeAllViews()
        val typeCard = createDistributionCard(
            title = "类型分布对比",
            subtitle = "库存数量/大小 vs 窗口浏览"
        )
        val maxCount = maxOf(imageCount, videoCount, animatedCount).coerceAtLeast(1)
        val maxBytes = maxOf(imageBytes, videoBytes, animatedBytes).coerceAtLeast(1L)
        val maxViews = maxOf(imageViews, videoViews, animatedViews).coerceAtLeast(1)
        listOf(
            Triple("图片", imageCount, imageBytes to imageViews),
            Triple("视频", videoCount, videoBytes to videoViews),
            Triple("动图", animatedCount, animatedBytes to animatedViews)
        ).forEach { (name, count, bytesAndViews) ->
            typeCard.addView(createDistributionRow(
                label = name,
                metrics = listOf(
                    DistributionMetric("数量", count.toString(), count, maxCount),
                    DistributionMetric("大小", StatsFormatHelper.formatSize(bytesAndViews.first), (bytesAndViews.first * 100 / maxBytes).toInt(), 100),
                    DistributionMetric("浏览", bytesAndViews.second.toString(), bytesAndViews.second, maxViews)
                )
            ))
        }
        binding.distributionContainer.addView(typeCard, distributionCardParams())

        val sourceCard = createDistributionCard(
            title = "来源分布对比",
            subtitle = "常规 vs COS"
        )
        val sMaxCount = maxOf(normalCount, cosCount).coerceAtLeast(1)
        val sMaxBytes = media.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        val sMaxViews = maxOf(normalViews, cosViews).coerceAtLeast(1)
        sourceCard.addView(createDistributionRow(
            label = "常规",
            metrics = listOf(
                DistributionMetric("数量", normalCount.toString(), normalCount, sMaxCount),
                DistributionMetric("大小", StatsFormatHelper.formatSize(media.filter { !it.isCosFile }.sumOf { it.sizeBytes }), (media.filter { !it.isCosFile }.sumOf { it.sizeBytes } * 100 / sMaxBytes).toInt(), 100),
                DistributionMetric("浏览", normalViews.toString(), normalViews, sMaxViews)
            )
        ))
        sourceCard.addView(createDistributionRow(
            label = "COS",
            metrics = listOf(
                DistributionMetric("数量", cosCount.toString(), cosCount, sMaxCount),
                DistributionMetric("大小", StatsFormatHelper.formatSize(media.filter { it.isCosFile }.sumOf { it.sizeBytes }), (media.filter { it.isCosFile }.sumOf { it.sizeBytes } * 100 / sMaxBytes).toInt(), 100),
                DistributionMetric("浏览", cosViews.toString(), cosViews, sMaxViews)
            )
        ))
        binding.distributionContainer.addView(sourceCard, distributionCardParams())
    }

    // ==================== 渲染辅助 ====================

    /** 统计摘要数据 */
    private data class SummaryItem(val label: String, val value: String)

    /** 渲染统计摘要卡片（2列网格，自动换行） */
    private fun renderSummary(items: List<SummaryItem>) {
        binding.summaryContainer.removeAllViews()
        val rows = items.chunked(2)
        rows.forEach { rowItems ->
            val row = LinearLayout(requireContext()).apply {
                layoutTransition = null
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (binding.summaryContainer.childCount > 0) {
                        topMargin = 8f.dp(requireContext()).toInt()
                    }
                }
            }
            rowItems.forEach { item ->
                val card = createSummaryCard(item)
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    if (rowItems.indexOf(item) == 0) {
                        marginEnd = 4f.dp(requireContext()).toInt()
                    } else {
                        marginStart = 4f.dp(requireContext()).toInt()
                    }
                }
                row.addView(card, params)
            }
            if (rowItems.size == 1) {
                val placeholder = View(requireContext())
                val params = LinearLayout.LayoutParams(0, 1).apply { weight = 1f }
                row.addView(placeholder, params)
            }
            binding.summaryContainer.addView(row)
        }
    }

    private fun createSummaryCard(item: SummaryItem): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(requireContext(), R.drawable.bg_stat_card)
            setPadding(12f.dp(requireContext()).toInt())
            val valueText = TextView(requireContext()).apply {
                text = item.value
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorPrimary))
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val labelText = TextView(requireContext()).apply {
                text = item.label
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextSecondary))
                textSize = 11f
            }
            addView(valueText)
            addView(labelText)
        }
    }

    /** 渲染数据洞察卡片（自动生成的文字摘要列表） */
    private fun renderInsights(insights: List<String>) {
        binding.insightContainer.removeAllViews()
        if (insights.isEmpty()) return
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(requireContext(), R.drawable.bg_stat_card)
            setPadding(12f.dp(requireContext()).toInt())
        }
        val title = TextView(requireContext()).apply {
            text = "数据洞察"
            setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextPrimary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(title)
        insights.forEach { text ->
            val item = TextView(requireContext()).apply {
                this.text = "· $text"
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextSecondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 6f.dp(requireContext()).toInt()
                }
            }
            card.addView(item)
        }
        binding.insightContainer.addView(card)
    }

    // ==================== 分布对比（保留原实现） ====================

    /** 分布卡片间距参数（卡片间留白，避免视觉重叠） */
    private fun distributionCardParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 12f.dp(requireContext()).toInt()
        }
    }

    /** 分布指标数据 */
    private data class DistributionMetric(
        val name: String,
        val valueText: String,
        val value: Int,
        val maxValue: Int
    )

    /** 创建分布对比卡片容器 */
    private fun createDistributionCard(title: String, subtitle: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(requireContext(), R.drawable.bg_stat_card)
            setPadding(12f.dp(requireContext()).toInt())
            val titleView = TextView(requireContext()).apply {
                text = title
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextPrimary))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val subtitleView = TextView(requireContext()).apply {
                text = subtitle
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextSecondary))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 2f.dp(requireContext()).toInt()
                }
            }
            addView(titleView)
            addView(subtitleView)
        }
    }

    /** 创建分布对比行（标签 + 多个指标的进度条） */
    private fun createDistributionRow(label: String, metrics: List<DistributionMetric>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12f.dp(requireContext()).toInt()
            }

            val labelView = TextView(requireContext()).apply {
                text = label
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextPrimary))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            addView(labelView)

            val metricsRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 6f.dp(requireContext()).toInt()
                }
            }
            metrics.forEach { metric ->
                val metricView = createMetricItem(metric)
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    val index = metrics.indexOf(metric)
                    if (index == 0) {
                        marginEnd = 6f.dp(requireContext()).toInt()
                    } else if (index == metrics.lastIndex) {
                        marginStart = 6f.dp(requireContext()).toInt()
                    } else {
                        marginStart = 3f.dp(requireContext()).toInt()
                        marginEnd = 3f.dp(requireContext()).toInt()
                    }
                }
                metricsRow.addView(metricView, params)
            }
            addView(metricsRow)
        }
    }

    /** 创建单个指标项（名称 + 进度条 + 数值） */
    private fun createMetricItem(metric: DistributionMetric): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val nameView = TextView(requireContext()).apply {
                text = metric.name
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextSecondary))
                textSize = 10f
            }
            val progressBar = android.widget.ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = if (metric.maxValue > 0) {
                    (metric.value.toFloat() / metric.maxValue * 100).toInt().coerceIn(1, 100)
                } else {
                    0
                }
                progressDrawable = AppCompatResources.getDrawable(requireContext(), R.drawable.bg_rank_progress)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    3f.dp(requireContext()).toInt()
                ).apply {
                    topMargin = 4f.dp(requireContext()).toInt()
                }
            }
            val valueView = TextView(requireContext()).apply {
                text = metric.valueText
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorPrimary))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 2f.dp(requireContext()).toInt()
                }
            }
            addView(nameView)
            addView(progressBar)
            addView(valueView)
        }
    }

    // ==================== 跳转与工具 ====================

    /** 点击条目跳转（标签 → 搜索文件列表，作者 → 作者文件页，文件 → 详情页） */
    private fun handleItemClick(id: String) {
        val activity = requireActivity() as? MainActivity ?: return
        when {
            id.startsWith("tag:") -> {
                // v1.14 修复：标签条目点击跳转搜索页时携带标签名作为初始查询词
                val tagName = id.removePrefix("tag:")
                activity.showSearchFragment(tagName)
            }
            mode == MODE_AUTHORS -> activity.showAuthorFiles(id)
            else -> activity.showDetailFragment(id)
        }
    }

    /** 格式化时长（秒 → 可读字符串，如 "1小时23分"） */
    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0秒"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分"
            minutes > 0 -> "${minutes}分${secs}秒"
            else -> "${secs}秒"
        }
    }

    private fun Float.dp(context: android.content.Context): Float =
        this * context.resources.displayMetrics.density

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val MODE_TYPE_TREND = "type_trend"
        const val MODE_FAVORITES = "favorites"
        const val MODE_AUTHORS = "authors"
        const val MODE_DISTRIBUTION = "distribution"

        /** 来源趋势维度 key（区别于 MediaType 常量） */
        private const val KEY_NORMAL = "source_normal"
        private const val KEY_COS = "source_cos"

        private const val ARG_MODE = "mode"
        private const val ARG_TIME_RANGE = "time_range"

        fun newInstance(mode: String, timeRange: String): StatsDetailFragment {
            return StatsDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode)
                    putString(ARG_TIME_RANGE, timeRange)
                }
            }
        }
    }
}
