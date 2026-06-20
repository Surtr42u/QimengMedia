package com.qimeng.media.ui.stats

import android.os.Bundle
import android.view.Gravity
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
 * 统计详情页（v1.7 新增）。
 *
 * 点击数据统计页的卡片后进入的全新详情界面，包含：
 * - 顶部返回栏 + 标题
 * - 统计摘要卡片（总和/均值/峰值等，根据模式动态生成）
 * - 辅助图表（趋势折线图或柱状图，根据模式动态生成）
 * - 完整 Top 20 排行榜列表（可点击跳转文件详情/作者文件页）
 *
 * 支持四种模式：
 * - [MODE_FILES]：热门文件详情（摘要 + 趋势 + Top 20）
 * - [MODE_AUTHORS]：作者详情（摘要 + Top 20）
 * - [MODE_TAGS]：标签详情（摘要 + Top 20）
 * - [MODE_DISTRIBUTION]：分布统计详情（类型/来源汇总 + 文件列表）
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

    private var mode: String = MODE_FILES
    private var timeRangeName: String = "DAYS_7"
    /** 文件模式排序维度：true=按热度(viewCount+playCount)，false=按浏览时长 */
    private var filesSortByHeat = true
    /** 缓存文件模式最新数据，用于排序切换时重新渲染 */
    private var cachedFilesStats: List<com.qimeng.media.data.db.entity.ViewStatsEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = arguments?.getString(ARG_MODE) ?: MODE_FILES
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
        observeData()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                when (mode) {
                    MODE_FILES -> observeFilesMode()
                    MODE_AUTHORS -> observeAuthorsMode()
                    MODE_TAGS -> observeTagsMode()
                    MODE_DISTRIBUTION -> observeDistributionMode()
                }
            }
        }
    }

    // ==================== 文件模式 ====================

    private fun CoroutineScope.observeFilesMode() {
        binding.titleText.text = "文件浏览排行"
        binding.listTitleText.text = "完整排行榜"
        // 文件模式：显示排行榜，隐藏分布对比，启用排序切换
        binding.rankListContainer.visibility = android.view.View.VISIBLE
        binding.distributionContainer.visibility = android.view.View.GONE
        binding.sortToggleText.visibility = android.view.View.VISIBLE
        setupFilesSortToggle()

        launch {
            viewModel.allStats.collect { stats ->
                cachedFilesStats = stats
                renderFilesRanking(stats)
            }
        }

        launch {
            viewModel.history.collect { history ->
                renderTrendChart(history)
            }
        }
    }

    /** 设置文件模式排序切换胶囊 */
    private fun setupFilesSortToggle() {
        binding.sortToggleText.text = if (filesSortByHeat) "按热度" else "按时长"
        binding.sortToggleText.setOnClickListener {
            filesSortByHeat = !filesSortByHeat
            binding.sortToggleText.text = if (filesSortByHeat) "按热度" else "按时长"
            renderFilesRanking(cachedFilesStats)
        }
    }

    /** 渲染文件排行榜（根据当前排序维度） */
    private fun renderFilesRanking(stats: List<com.qimeng.media.data.db.entity.ViewStatsEntity>) {
        val sorted = if (filesSortByHeat) {
            stats.filter { it.viewCount + it.playCount > 0 }
                .sortedByDescending { it.viewCount + it.playCount }
        } else {
            stats.filter { it.totalBrowseSeconds > 0 }
                .sortedByDescending { it.totalBrowseSeconds }
        }

        // 统计摘要
        val totalViews = stats.sumOf { it.viewCount }
        val totalPlays = stats.sumOf { it.playCount }
        val filesWithViews = stats.count { it.viewCount + it.playCount > 0 }
        val totalInteractions = totalViews + totalPlays
        val avgDepth = if (filesWithViews > 0)
            totalInteractions.toFloat() / filesWithViews else 0f
        val topFile = sorted.firstOrNull()
        renderSummary(
            listOf(
                SummaryItem("总浏览", StatsFormatHelper.formatNumber(totalViews)),
                SummaryItem("总播放", StatsFormatHelper.formatNumber(totalPlays)),
                SummaryItem("有浏览文件", StatsFormatHelper.formatNumber(filesWithViews)),
                SummaryItem("平均浏览深度", String.format(java.util.Locale.US, "%.1f", avgDepth))
            )
        )

        // 数据洞察
        val insights = mutableListOf<String>()
        if (topFile != null) {
            val topCount = if (filesSortByHeat) topFile.viewCount + topFile.playCount else topFile.totalBrowseSeconds.toInt()
            val topLabel = if (filesSortByHeat) "总浏览量" else "总浏览时长"
            val topValue = if (filesSortByHeat) "$topCount 次" else formatDuration(topFile.totalBrowseSeconds)
            val share = if (totalInteractions > 0 && filesSortByHeat) topCount * 100 / totalInteractions else 0
            insights.add("最热门文件「${topFile.fileName}」${topLabel} $topValue" + (if (filesSortByHeat && totalInteractions > 0) "，占 $share%" else ""))
        }
        if (sorted.size >= 2 && filesSortByHeat) {
            val top1 = sorted[0].viewCount + sorted[0].playCount
            val top2 = sorted[1].viewCount + sorted[1].playCount
            if (top2 > 0) {
                val ratio = top1.toFloat() / top2
                insights.add("第一名热度是第二名的 ${String.format(java.util.Locale.US, "%.1f", ratio)} 倍")
            }
        }
        insights.add("共 ${stats.size} 个文件，其中 $filesWithViews 个有浏览记录（占 ${if (stats.isNotEmpty()) filesWithViews * 100 / stats.size else 0}%）")
        renderInsights(insights)

        // 排行榜
        binding.listSubtitleText.text = if (filesSortByHeat) {
            "共 ${sorted.size} 个有浏览记录的文件"
        } else {
            "共 ${sorted.size} 个有浏览时长记录的文件"
        }
        val maxValue = if (filesSortByHeat) {
            sorted.firstOrNull()?.let { it.viewCount + it.playCount } ?: 0
        } else {
            sorted.firstOrNull()?.totalBrowseSeconds?.toInt() ?: 0
        }
        val items = sorted.take(20).map { entity ->
            if (filesSortByHeat) {
                RankListAdapter.RankItem(
                    id = entity.recordKey,
                    title = entity.fileName,
                    subtitle = "浏览 ${entity.viewCount} · 播放 ${entity.playCount}",
                    value = entity.viewCount + entity.playCount,
                    valueLabel = "${entity.viewCount + entity.playCount} 次",
                    maxValue = maxValue
                )
            } else {
                RankListAdapter.RankItem(
                    id = entity.recordKey,
                    title = entity.fileName,
                    subtitle = "浏览 ${entity.viewCount} · 播放 ${entity.playCount}",
                    value = entity.totalBrowseSeconds.toInt(),
                    valueLabel = formatDuration(entity.totalBrowseSeconds),
                    maxValue = maxValue
                )
            }
        }
        adapter.submitList(items)
    }

    // ==================== 作者模式 ====================

    private fun CoroutineScope.observeAuthorsMode() {
        binding.titleText.text = "作者文件数排行"
        binding.listTitleText.text = "完整排行榜"
        // 作者模式：显示排行榜，隐藏分布对比和排序切换
        binding.rankListContainer.visibility = android.view.View.VISIBLE
        binding.distributionContainer.visibility = android.view.View.GONE
        binding.sortToggleText.visibility = android.view.View.GONE

        launch {
            combine(viewModel.authors, viewModel.authorFileCounts) { authors, counts ->
                val authorMap = authors.associateBy { it.authorId }
                counts.mapNotNull { afc ->
                    authorMap[afc.authorId]?.let { author ->
                        Triple(author.authorId, author.displayName, afc.fileCount)
                    }
                }.sortedByDescending { it.third }
            }.collect { authorData ->
                // 统计摘要
                val totalAuthors = authorData.size
                val totalFiles = authorData.sumOf { it.third }
                val topAuthor = authorData.maxByOrNull { it.third }
                val avgFiles = if (totalAuthors > 0) totalFiles.toFloat() / totalAuthors else 0f
                val maxCount = topAuthor?.third ?: 0
                renderSummary(
                    listOf(
                        SummaryItem("作者总数", StatsFormatHelper.formatNumber(totalAuthors)),
                        SummaryItem("关联文件总数", StatsFormatHelper.formatNumber(totalFiles)),
                        SummaryItem("平均每位作者", String.format(java.util.Locale.US, "%.1f 个", avgFiles)),
                        SummaryItem("文件最多", topAuthor?.second ?: "—")
                    )
                )

                // 数据洞察
                val insights = mutableListOf<String>()
                if (topAuthor != null && totalAuthors > 0) {
                    val share = if (totalFiles > 0) maxCount * 100 / totalFiles else 0
                    insights.add("「${topAuthor.second}」拥有 $maxCount 个文件，占总量的 $share%")
                }
                if (authorData.size >= 2) {
                    val top1Count = authorData[0].third
                    val top2Count = authorData[1].third
                    if (top2Count > 0) {
                        insights.add("第一名文件数是第二名的 ${String.format(java.util.Locale.US, "%.1f", top1Count.toFloat() / top2Count)} 倍")
                    }
                }
                val multiFileAuthors = authorData.count { it.third > 1 }
                insights.add("有 $multiFileAuthors 位作者拥有多个文件，${totalAuthors - multiFileAuthors} 位仅有单个文件")
                renderInsights(insights)

                // 排行榜
                binding.listSubtitleText.text = "共 $totalAuthors 位作者"
                val items = authorData.take(20).map { (authorId, name, count) ->
                    RankListAdapter.RankItem(
                        id = authorId,
                        title = name,
                        subtitle = null,
                        value = count,
                        valueLabel = "$count 个作品",
                        maxValue = maxCount
                    )
                }
                adapter.submitList(items)
            }
        }
    }

    // ==================== 标签模式 ====================

    private fun CoroutineScope.observeTagsMode() {
        binding.titleText.text = "标签文件数排行"
        binding.listTitleText.text = "完整排行榜"
        // 标签模式：显示排行榜，隐藏分布对比和排序切换
        binding.rankListContainer.visibility = android.view.View.VISIBLE
        binding.distributionContainer.visibility = android.view.View.GONE
        binding.sortToggleText.visibility = android.view.View.GONE

        launch {
            viewModel.allMediaTags.collect { mediaTags ->
                val tagData = mediaTags
                    .groupBy { it.name }
                    .map { (name, list) -> name to list.size }
                    .sortedByDescending { it.second }

                // 统计摘要
                val totalTags = tagData.size
                val totalRelations = tagData.sumOf { it.second }
                val topTag = tagData.maxByOrNull { it.second }
                val avgFiles = if (totalTags > 0) totalRelations.toFloat() / totalTags else 0f
                val maxCount = topTag?.second ?: 0
                renderSummary(
                    listOf(
                        SummaryItem("标签总数", StatsFormatHelper.formatNumber(totalTags)),
                        SummaryItem("关联总数", StatsFormatHelper.formatNumber(totalRelations)),
                        SummaryItem("平均每个标签", String.format(java.util.Locale.US, "%.1f 个文件", avgFiles)),
                        SummaryItem("关联最多", topTag?.first ?: "—")
                    )
                )

                // 数据洞察
                val insights = mutableListOf<String>()
                if (topTag != null && totalTags > 0) {
                    val share = if (totalRelations > 0) maxCount * 100 / totalRelations else 0
                    insights.add("标签「${topTag.first}」关联 $maxCount 个文件，占关联总量的 $share%")
                }
                val singleFileTags = tagData.count { it.second == 1 }
                insights.add("$singleFileTags 个标签仅关联 1 个文件，${totalTags - singleFileTags} 个标签关联多个文件")
                if (totalTags > 0) {
                    insights.add("平均每个文件被标记 ${String.format(java.util.Locale.US, "%.1f", if (totalRelations > 0) totalTags.toFloat() else 0f)} 个标签")
                }
                renderInsights(insights)

                // 排行榜
                binding.listSubtitleText.text = "共 $totalTags 个标签"
                val items = tagData.take(20).map { (name, count) ->
                    RankListAdapter.RankItem(
                        id = null,
                        title = name,
                        subtitle = null,
                        value = count,
                        valueLabel = "$count 个文件",
                        maxValue = maxCount
                    )
                }
                adapter.submitList(items)
            }
        }
    }

    // ==================== 分布统计模式 ====================

    private fun CoroutineScope.observeDistributionMode() {
        binding.titleText.text = "分布统计详情"
        // 分布统计模式：隐藏排行榜区域，显示分布对比区域
        binding.rankListContainer.visibility = android.view.View.GONE
        binding.distributionContainer.visibility = android.view.View.VISIBLE

        // 同时收集 media 和 stats，结合两者渲染
        launch {
            combine(viewModel.allMedia, viewModel.allStats) { media, stats ->
                Pair(media, stats)
            }.collect { (media, stats) ->
                // 类型分布汇总
                val imageCount = media.count { it.mediaType == MediaType.IMAGE }
                val videoCount = media.count { it.mediaType == MediaType.VIDEO }
                val animatedCount = media.count { it.mediaType == MediaType.ANIMATED_IMAGE }
                val cosCount = media.count { it.isCosFile }
                val normalCount = media.count { !it.isCosFile }
                val total = media.size.coerceAtLeast(1)

                val imageBytes = media.filter { it.mediaType == MediaType.IMAGE }.sumOf { it.sizeBytes }
                val videoBytes = media.filter { it.mediaType == MediaType.VIDEO }.sumOf { it.sizeBytes }
                val animatedBytes = media.filter { it.mediaType == MediaType.ANIMATED_IMAGE }.sumOf { it.sizeBytes }
                val totalBytes = imageBytes + videoBytes + animatedBytes

                // 各类型平均大小
                val avgImageSize = if (imageCount > 0) imageBytes / imageCount else 0L
                val avgVideoSize = if (videoCount > 0) videoBytes / videoCount else 0L

                // 各类型浏览量（结合 stats）
                val statsMap = stats.associateBy { it.recordKey }
                val imageViews = media.filter { it.mediaType == MediaType.IMAGE }
                    .sumOf { statsMap[it.recordKey]?.viewCount ?: 0 }
                val videoPlays = media.filter { it.mediaType == MediaType.VIDEO }
                    .sumOf { statsMap[it.recordKey]?.playCount ?: 0 }
                val animatedViews = media.filter { it.mediaType == MediaType.ANIMATED_IMAGE }
                    .sumOf { statsMap[it.recordKey]?.viewCount ?: 0 }
                val cosViews = media.filter { it.isCosFile }
                    .sumOf { (statsMap[it.recordKey]?.viewCount ?: 0) + (statsMap[it.recordKey]?.playCount ?: 0) }
                val normalViews = media.filter { !it.isCosFile }
                    .sumOf { (statsMap[it.recordKey]?.viewCount ?: 0) + (statsMap[it.recordKey]?.playCount ?: 0) }

                renderSummary(
                    listOf(
                        SummaryItem("图片", "${imageCount} (${imageCount * 100 / total}%)"),
                        SummaryItem("视频", "${videoCount} (${videoCount * 100 / total}%)"),
                        SummaryItem("动图", "${animatedCount} (${animatedCount * 100 / total}%)"),
                        SummaryItem("常规 / COS", "${normalCount} / ${cosCount}"),
                        SummaryItem("图片总大小", StatsFormatHelper.formatSize(imageBytes)),
                        SummaryItem("视频总大小", StatsFormatHelper.formatSize(videoBytes)),
                        SummaryItem("总占用", StatsFormatHelper.formatSize(totalBytes)),
                        SummaryItem("视频平均大小", StatsFormatHelper.formatSize(avgVideoSize))
                    )
                )

                // 数据洞察
                val insights = mutableListOf<String>()
                insights.add("图片 ${imageCount} 个（${imageCount * 100 / total}%），平均每个 ${StatsFormatHelper.formatSize(avgImageSize)}")
                insights.add("视频 ${videoCount} 个（${videoCount * 100 / total}%），平均每个 ${StatsFormatHelper.formatSize(avgVideoSize)}")
                if (animatedCount > 0) {
                    insights.add("动图 ${animatedCount} 个（${animatedCount * 100 / total}%）")
                }
                val cosPercent = if (cosCount > 0) cosCount * 100 / total else 0
                insights.add("COS 文件 ${cosCount} 个（${cosPercent}%），常规文件 ${normalCount} 个")
                if (totalBytes > 0) {
                    val videoSpaceShare = videoBytes * 100 / totalBytes
                    insights.add("视频占用 ${StatsFormatHelper.formatSize(videoBytes)}，占总空间的 $videoSpaceShare%")
                }
                // 浏览量洞察
                val totalViews = imageViews + videoPlays + animatedViews
                if (totalViews > 0) {
                    insights.add("图片被浏览 ${imageViews} 次，视频被播放 ${videoPlays} 次，动图被浏览 ${animatedViews} 次")
                }
                renderInsights(insights)

                // 分布对比卡片（替代排行榜）
                renderDistributionComparison(
                    media = media,
                    typeData = listOf(
                        TypeDistributionData("图片", imageCount, imageBytes, imageViews, MediaType.IMAGE),
                        TypeDistributionData("视频", videoCount, videoBytes, videoPlays, MediaType.VIDEO),
                        TypeDistributionData("动图", animatedCount, animatedBytes, animatedViews, MediaType.ANIMATED_IMAGE)
                    ),
                    sourceData = SourceDistributionData(
                        normalCount = normalCount,
                        cosCount = cosCount,
                        normalBytes = media.filter { !it.isCosFile }.sumOf { it.sizeBytes },
                        cosBytes = media.filter { it.isCosFile }.sumOf { it.sizeBytes },
                        normalViews = normalViews,
                        cosViews = cosViews
                    )
                )
            }
        }
    }

    /** 类型分布数据 */
    private data class TypeDistributionData(
        val name: String,
        val count: Int,
        val bytes: Long,
        val views: Int,
        val mediaType: String
    )

    /** 来源分布数据 */
    private data class SourceDistributionData(
        val normalCount: Int,
        val cosCount: Int,
        val normalBytes: Long,
        val cosBytes: Long,
        val normalViews: Int,
        val cosViews: Int
    )

    /**
     * 渲染分布对比卡片（仅分布统计模式使用，替代排行榜）。
     * 包含两个卡片：
     * - 类型分布对比：数量/大小/浏览量三维度横向对比
     * - 来源分布对比：常规 vs COS 的数量/大小/浏览量对比
     */
    private fun renderDistributionComparison(
        media: List<com.qimeng.media.data.db.entity.MediaFileEntity>,
        typeData: List<TypeDistributionData>,
        sourceData: SourceDistributionData
    ) {
        binding.distributionContainer.removeAllViews()

        // 卡片1：类型分布对比
        val typeCard = createDistributionCard(
            title = "类型分布对比",
            subtitle = "按数量、占用空间、浏览量三个维度对比各类型"
        )
        val maxCount = typeData.maxOf { it.count }.coerceAtLeast(1)
        val maxBytes = typeData.maxOf { it.bytes }.coerceAtLeast(1L)
        val maxViews = typeData.maxOf { it.views }.coerceAtLeast(1)
        typeData.forEach { data ->
            typeCard.addView(createDistributionRow(
                label = data.name,
                metrics = listOf(
                    DistributionMetric("数量", data.count.toString(), data.count, maxCount),
                    DistributionMetric("大小", StatsFormatHelper.formatSize(data.bytes), (data.bytes * 100 / maxBytes).toInt(), 100),
                    DistributionMetric("浏览", data.views.toString(), data.views, maxViews)
                )
            ))
        }
        binding.distributionContainer.addView(typeCard, distributionCardParams())

        // 卡片2：来源分布对比
        val sourceCard = createDistributionCard(
            title = "来源分布对比",
            subtitle = "常规文件 vs COS 文件的多维度对比"
        )
        val sourceMaxCount = maxOf(sourceData.normalCount, sourceData.cosCount).coerceAtLeast(1)
        val sourceMaxBytes = maxOf(sourceData.normalBytes, sourceData.cosBytes).coerceAtLeast(1L)
        val sourceMaxViews = maxOf(sourceData.normalViews, sourceData.cosViews).coerceAtLeast(1)
        sourceCard.addView(createDistributionRow(
            label = "常规",
            metrics = listOf(
                DistributionMetric("数量", sourceData.normalCount.toString(), sourceData.normalCount, sourceMaxCount),
                DistributionMetric("大小", StatsFormatHelper.formatSize(sourceData.normalBytes), (sourceData.normalBytes * 100 / sourceMaxBytes).toInt(), 100),
                DistributionMetric("浏览", sourceData.normalViews.toString(), sourceData.normalViews, sourceMaxViews)
            )
        ))
        sourceCard.addView(createDistributionRow(
            label = "COS",
            metrics = listOf(
                DistributionMetric("数量", sourceData.cosCount.toString(), sourceData.cosCount, sourceMaxCount),
                DistributionMetric("大小", StatsFormatHelper.formatSize(sourceData.cosBytes), (sourceData.cosBytes * 100 / sourceMaxBytes).toInt(), 100),
                DistributionMetric("浏览", sourceData.cosViews.toString(), sourceData.cosViews, sourceMaxViews)
            )
        ))
        binding.distributionContainer.addView(sourceCard, distributionCardParams())
    }

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

            // 标签行
            val labelView = TextView(requireContext()).apply {
                text = label
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextPrimary))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            addView(labelView)

            // 指标行（横向排列）
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
            // 如果只有一个 item，补一个占位
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

    /** 渲染浏览趋势折线图（仅文件模式使用） */
    private fun renderTrendChart(history: List<com.qimeng.media.data.db.entity.ViewHistoryEntity>) {
        if (mode != MODE_FILES) {
            binding.chartContainer.removeAllViews()
            return
        }
        binding.chartContainer.removeAllViews()
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(requireContext(), R.drawable.bg_stat_card)
            setPadding(12f.dp(requireContext()).toInt())
        }
        val title = TextView(requireContext()).apply {
            text = "浏览趋势"
            setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextPrimary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val chart = LineChartView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                180f.dp(requireContext()).toInt()
            ).apply {
                topMargin = 8f.dp(requireContext()).toInt()
            }
        }
        val points = when (timeRangeName) {
            "DAYS_7" -> StatsFormatHelper.groupByDay(history, 7)
            "DAYS_30" -> StatsFormatHelper.groupByDay(history, 30)
            else -> StatsFormatHelper.groupByWeek(history)
        }
        chart.setData(points)
        card.addView(title)
        card.addView(chart)
        binding.chartContainer.addView(card)
    }

    /** 点击条目跳转 */
    private fun handleItemClick(id: String) {
        when (mode) {
            MODE_AUTHORS -> (requireActivity() as? MainActivity)?.showAuthorFiles(id)
            else -> (requireActivity() as? MainActivity)?.showDetailFragment(id)
        }
    }

    // ==================== 工具方法 ====================

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
        const val MODE_FILES = "files"
        const val MODE_AUTHORS = "authors"
        const val MODE_TAGS = "tags"
        const val MODE_DISTRIBUTION = "distribution"

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
