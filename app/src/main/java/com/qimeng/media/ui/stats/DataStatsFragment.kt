package com.qimeng.media.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.databinding.FragmentDataStatsBinding
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.widget.BarChartView
import com.qimeng.media.ui.widget.BrowseStatsChartView
import com.qimeng.media.ui.widget.LineChartView
import com.qimeng.media.ui.widget.PieChartView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 数据统计页（v1.7 新增）。
 *
 * 专业数据报表页面，包含：
 * - 时间范围切换（7天/30天/全部）
 * - 总览数字卡片（总浏览/总播放/总时长/总文件数/总占用空间/平均浏览深度）
 * - 浏览趋势折线图
 * - 分布统计卡片（类型分布 + 来源分布，两个环形图合并展示）
 * - 热门文件 Top 5 柱状图卡片（点击弹出完整排行榜 BottomSheet）
 * - 作者文件数 Top 5 横向柱条卡片（点击弹出完整排行榜 BottomSheet）
 * - 标签文件数 Top 5 横向柱条卡片（点击弹出完整排行榜 BottomSheet）
 *
 * 交互设计：
 * - 点击热门文件/作者/标签卡片 → 弹出 BottomSheet 显示完整 Top 20 排行榜
 * - 排行榜条目可点击跳转文件详情页/作者文件页
 * - 环形图纯展示，无点击交互
 * - 图表入场动画
 * - 时间范围切换重新聚合数据
 */
class DataStatsFragment : Fragment() {
    private var _binding: FragmentDataStatsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }

    private var currentRange: TimeRange = TimeRange.DAYS_7
    private var cachedHistory: List<ViewHistoryEntity> = emptyList()
    private var cachedStats: List<ViewStatsEntity> = emptyList()
    private var cachedMedia: List<com.qimeng.media.data.db.entity.MediaFileEntity> = emptyList()
    private var cachedAuthorData: List<Triple<String, String, Int>> = emptyList()
    private var cachedTagData: List<Pair<String, Int>> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDataStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTimeRangeCapsule()
        setupCardClicks()
        observeData()
    }

    private fun setupTimeRangeCapsule() {
        val rangeViews = listOf(binding.range7Days, binding.range30Days, binding.rangeAll)
        rangeViews.forEachIndexed { index, textView ->
            textView.setOnClickListener {
                currentRange = when (index) {
                    0 -> TimeRange.DAYS_7
                    1 -> TimeRange.DAYS_30
                    else -> TimeRange.ALL
                }
                updateRangeSelection(index)
                renderCharts()
            }
        }
        updateRangeSelection(0)
    }

    /** 更新胶囊选中态：选中项填充主题色背景+反色文字，未选中透明+次级文字色 */
    private fun updateRangeSelection(selectedIndex: Int) {
        val rangeViews = listOf(binding.range7Days, binding.range30Days, binding.rangeAll)
        rangeViews.forEachIndexed { index, textView ->
            if (index == selectedIndex) {
                textView.setBackgroundResource(R.drawable.bg_capsule_primary)
                textView.setTextColor(requireContext().resolveThemeColor(R.attr.qmColorBg))
            } else {
                textView.background = null
                textView.setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextSecondary))
            }
        }
    }

    private fun setupCardClicks() {
        binding.topFilesCard.setOnClickListener {
            showStatsDetail(StatsDetailFragment.MODE_FILES)
        }
        binding.topAuthorsCard.setOnClickListener {
            showStatsDetail(StatsDetailFragment.MODE_AUTHORS)
        }
        binding.topTagsCard.setOnClickListener {
            showStatsDetail(StatsDetailFragment.MODE_TAGS)
        }
        binding.distributionCard.setOnClickListener {
            showStatsDetail(StatsDetailFragment.MODE_DISTRIBUTION)
        }
    }

    /** 进入统计详情全新界面 */
    private fun showStatsDetail(mode: String) {
        (requireActivity() as? MainActivity)?.showStatsDetail(mode, currentRange.name)
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allStats.collect { stats ->
                        cachedStats = stats
                        renderOverview()
                        renderTopFiles()
                    }
                }
                launch {
                    viewModel.history.collect { history ->
                        cachedHistory = history
                        renderTrend()
                    }
                }
                launch {
                    viewModel.allMedia.collect { media ->
                        cachedMedia = media
                        renderTypeDistribution()
                        renderSourceDistribution()
                        renderOverviewExtra()
                    }
                }
                launch {
                    combine(viewModel.authors, viewModel.authorFileCounts) { authors, counts ->
                        val authorMap = authors.associateBy { it.authorId }
                        counts.mapNotNull { afc ->
                            authorMap[afc.authorId]?.let { author ->
                                Triple(author.authorId, author.displayName, afc.fileCount)
                            }
                        }.sortedByDescending { it.third }
                    }.collect { authorData ->
                        cachedAuthorData = authorData
                        renderTopAuthors()
                    }
                }
                launch {
                    viewModel.allMediaTags.collect { mediaTags ->
                        // 按标签名聚合文件数
                        cachedTagData = mediaTags
                            .groupBy { it.name }
                            .map { (name, list) -> name to list.size }
                            .sortedByDescending { it.second }
                        renderTopTags()
                    }
                }
            }
        }
    }

    /** 渲染总览数字卡片（第一行：浏览/播放/时长） */
    private fun renderOverview() {
        val totalViews = cachedStats.sumOf { it.viewCount }
        val totalPlays = cachedStats.sumOf { it.playCount }
        val totalMinutes = cachedStats.sumOf { it.totalBrowseSeconds } / 60
        binding.totalViewsValue.text = StatsFormatHelper.formatNumber(totalViews)
        binding.totalPlayValue.text = StatsFormatHelper.formatNumber(totalPlays)
        binding.totalBrowseTimeValue.text = formatDuration(totalMinutes)
    }

    /** 渲染总览数字卡片（第二行：文件数/占用空间/平均浏览深度） */
    private fun renderOverviewExtra() {
        binding.totalFilesValue.text = StatsFormatHelper.formatNumber(cachedMedia.size)
        val totalBytes = cachedMedia.sumOf { it.sizeBytes }
        binding.totalSizeValue.text = StatsFormatHelper.formatSize(totalBytes)
        // 平均浏览深度 = 总浏览次数 / 有浏览记录的文件数
        val filesWithViews = cachedStats.count { it.viewCount + it.playCount > 0 }
        val totalViews = cachedStats.sumOf { it.viewCount + it.playCount }
        val avgDepth = if (filesWithViews > 0) totalViews.toFloat() / filesWithViews else 0f
        binding.avgBrowseValue.text = String.format(java.util.Locale.US, "%.1f", avgDepth)
    }

    /** 渲染浏览趋势折线图 */
    private fun renderTrend() {
        val now = System.currentTimeMillis()
        val cutoff = when (currentRange) {
            TimeRange.DAYS_7 -> now - 7L * 24 * 60 * 60 * 1000
            TimeRange.DAYS_30 -> now - 30L * 24 * 60 * 60 * 1000
            TimeRange.ALL -> 0L
        }
        val filtered = cachedHistory.filter { it.openedAtMillis >= cutoff }
        val points = when (currentRange) {
            TimeRange.DAYS_7 -> StatsFormatHelper.groupByDay(filtered, 7)
            TimeRange.DAYS_30 -> StatsFormatHelper.groupByDay(filtered, 30)
            TimeRange.ALL -> StatsFormatHelper.groupByWeek(filtered)
        }
        binding.trendChart.setData(points)
    }

    /**
     * 类型分布配置：媒体类型 → 显示名称。
     * 未来新增 MediaType 时只需在此列表追加一项，渲染自动适配。
     */
    private val typeDistributionConfig = listOf(
        MediaType.IMAGE to "图片",
        MediaType.VIDEO to "视频",
        MediaType.ANIMATED_IMAGE to "动图"
    )

    /** 渲染类型分布环形图（纯展示，无点击，始终显示所有类型含0值） */
    private fun renderTypeDistribution() {
        // 数据驱动渲染，未来新增类型只需在 typeDistributionConfig 追加一项
        val slices = typeDistributionConfig.map { (type, displayName) ->
            val count = cachedMedia.count { it.mediaType == type }
            PieChartView.Slice(displayName, count)
        }
        binding.typeDistributionChart.setData(slices)
    }

    /** 渲染来源分布环形图（纯展示，无点击，始终显示所有来源含0值） */
    private fun renderSourceDistribution() {
        val cosCount = cachedMedia.count { it.isCosFile }
        val normalCount = cachedMedia.count { !it.isCosFile }
        val slices = listOf(
            PieChartView.Slice("常规", normalCount),
            PieChartView.Slice("COS", cosCount)
        )
        binding.sourceDistributionChart.setData(slices)
    }

    /** 渲染热门文件 Top 5 柱状图（卡片内预览） */
    private fun renderTopFiles() {
        val top5 = cachedStats
            .filter { it.viewCount + it.playCount > 0 }
            .sortedByDescending { it.viewCount + it.playCount }
            .take(5)
            .map { entity ->
                BarChartView.BarEntry(
                    label = entity.fileName,
                    value = entity.viewCount + entity.playCount,
                    id = entity.recordKey
                )
            }
        binding.topFilesChart.setData(top5)
    }

    /** 渲染作者文件数 Top 5 横向柱条（卡片内预览） */
    private fun renderTopAuthors() {
        val top5 = cachedAuthorData.take(5).map { (authorId, name, count) ->
            BrowseStatsChartView.BarEntry(
                label = name,
                value = count,
                id = authorId
            )
        }
        binding.topAuthorsChart.setData(top5)
    }

    /** 渲染标签文件数 Top 5 横向柱条（卡片内预览） */
    private fun renderTopTags() {
        val top5 = cachedTagData.take(5).map { (name, count) ->
            BrowseStatsChartView.BarEntry(
                label = name,
                value = count,
                id = null // 标签暂不支持跳转
            )
        }
        binding.topTagsChart.setData(top5)
    }

    private fun renderCharts() {
        renderOverview()
        renderTrend()
        renderTopFiles()
    }

    private fun formatDuration(minutes: Long): String {
        val us = java.util.Locale.US
        return when {
            minutes >= 1440 -> String.format(us, "%.1f天", minutes / 1440.0)
            minutes >= 60 -> String.format(us, "%.1f时", minutes / 60.0)
            else -> "${minutes}分"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 双击 Tab 回到顶部 */
    fun scrollToTop() {
        _binding?.let { binding ->
            binding.scrollView.smoothScrollTo(0, 0)
        }
    }

    private enum class TimeRange { DAYS_7, DAYS_30, ALL }
}
