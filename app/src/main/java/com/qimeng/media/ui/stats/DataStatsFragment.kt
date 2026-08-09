package com.qimeng.media.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.data.db.entity.DailyBrowseEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.databinding.FragmentDataStatsBinding
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.ui.library.MediaLibraryViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 数据统计页（v1.7 新增，v1.14 重构）。
 *
 * 专业数据报表页面，包含：
 * - 时间范围切换（7天/30天/全部，全局联动所有统计）
 * - 总览数字卡片（总浏览/总播放/总时长/总文件数/总占用空间/平均浏览次数）
 * - 浏览趋势折线图（点击进入分类型趋势详情）
 * - 分布统计小入口（纯文字，点击进入原分布详情页）
 * - 常看文件卡片（文件名+浏览次数紧凑列表，点击进入常看详情）
 * - 常看作者与标签卡片（作者/标签混合 3 行，点击进入常看详情）
 *
 * v1.14 数据口径：窗口内统计（浏览/播放/时长/排行）全部基于按天浏览聚合
 * （daily_browse，无限保留），趋势图基于浏览历史明细（最近 500 条）；
 * 总文件数/总占用空间为库存静态值，不随时间变化。
 */
class DataStatsFragment : Fragment() {
    private var _binding: FragmentDataStatsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }

    private var currentRange: TimeRange = TimeRange.DAYS_7
    private var cachedHistory: List<com.qimeng.media.data.db.entity.ViewHistoryEntity> = emptyList()
    private var cachedMedia: List<MediaFileEntity> = emptyList()
    private var cachedDaily: List<DailyBrowseEntity> = emptyList()
    private var cachedAuthorData: List<Triple<String, String, Int>> = emptyList()
    private var cachedTagData: List<Pair<String, Int>> = emptyList()
    private var cachedAuthorMedia: List<com.qimeng.media.data.db.entity.AuthorMediaCrossRef> = emptyList()
    private var cachedMediaTags: List<com.qimeng.media.data.db.model.MediaTagName> = emptyList()

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
                renderAll()
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
        binding.trendCard.setOnClickListener {
            showStatsDetail(StatsDetailFragment.MODE_TYPE_TREND)
        }
        binding.topFilesCard.setOnClickListener {
            showStatsDetail(StatsDetailFragment.MODE_FAVORITES)
        }
        binding.topAuthorsCard.setOnClickListener {
            showStatsDetail(StatsDetailFragment.MODE_AUTHORS)
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
                    viewModel.history.collect { history ->
                        cachedHistory = history
                        renderTrend()
                    }
                }
                launch {
                    viewModel.allMedia.collect { media ->
                        cachedMedia = media
                        renderOverviewExtra()
                        renderTopFiles()
                        renderTopAuthors()
                    }
                }
                launch {
                    viewModel.allAuthorMedia.collect { authorMedia ->
                        cachedAuthorMedia = authorMedia
                        renderTopAuthors()
                    }
                }
                launch {
                    viewModel.dailyBrowse.collect { daily ->
                        cachedDaily = daily
                        renderOverview()
                        renderTopFiles()
                        renderTopAuthors()
                    }
                }
                launch {
                    viewModel.allMediaTags.collect { mediaTags ->
                        cachedMediaTags = mediaTags
                        cachedTagData = mediaTags
                            .groupBy { it.name }
                            .map { (name, list) -> name to list.size }
                            .sortedByDescending { it.second }
                        renderTopAuthors()
                    }
                }
            }
        }
    }

    /** 当前时间范围名字（与 StatsDetailFragment 时间胶囊约定一致） */
    private val currentRangeName: String get() = currentRange.name

    /** 窗口内浏览聚合（次数+播放+时长） */
    private fun windowDaily(): List<DailyBrowseEntity> {
        val cutoff = StatsFormatHelper.cutoffForRange(currentRangeName)
        return if (currentRange == TimeRange.ALL) cachedDaily else cachedDaily.filter { it.dayStartMillis >= cutoff }
    }

    /** 渲染总览数字卡片（第一行：浏览/播放/时长，随时间范围联动） */
    private fun renderOverview() {
        val window = windowDaily()
        val totalViews = window.sumOf { it.viewCount }
        val totalPlays = window.sumOf { it.playCount }
        val totalMinutes = window.sumOf { it.totalBrowseSeconds } / 60
        binding.totalViewsValue.text = StatsFormatHelper.formatNumber(totalViews)
        binding.totalPlayValue.text = StatsFormatHelper.formatNumber(totalPlays)
        binding.totalBrowseTimeValue.text = formatDuration(totalMinutes)
    }

    /** 渲染总览数字卡片（第二行：文件数/占用空间/平均浏览次数） */
    private fun renderOverviewExtra() {
        binding.totalFilesValue.text = StatsFormatHelper.formatNumber(cachedMedia.size)
        val totalBytes = cachedMedia.sumOf { it.sizeBytes }
        binding.totalSizeValue.text = StatsFormatHelper.formatSize(totalBytes)
        // 平均浏览次数 = 窗口内总浏览次数 / 窗口内有浏览记录的文件数
        val window = windowDaily()
        val filesWithViews = window.filter { it.viewCount + it.playCount > 0 }.map { it.recordKey }.distinct().size
        val totalViews = window.sumOf { it.viewCount + it.playCount }
        val avgViews = if (filesWithViews > 0) totalViews.toFloat() / filesWithViews else 0f
        binding.avgBrowseValue.text = String.format(Locale.US, "%.1f", avgViews)
    }

    /** 渲染浏览趋势折线图（基于浏览历史明细，随时间范围联动） */
    private fun renderTrend() {
        val points = when (currentRange) {
            TimeRange.DAYS_7 -> StatsFormatHelper.groupByDay(cachedHistory, 7)
            TimeRange.DAYS_30 -> StatsFormatHelper.groupByDay(cachedHistory, 30)
            TimeRange.ALL -> StatsFormatHelper.groupByWeek(cachedHistory)
        }
        binding.trendChart.setData(points)
    }

    /** 渲染常看文件列表（窗口内浏览聚合 Top 3，文件名 + 浏览次数） */
    private fun renderTopFiles() {
        val window = windowDaily()
        val top3 = window
            .filter { it.viewCount + it.playCount > 0 }
            .groupBy { it.recordKey }
            .map { (key, list) -> Triple(key, list.first().fileName, list.sumOf { it.viewCount + it.playCount }) }
            .sortedByDescending { it.third }
            .take(3)
        renderTextList(binding.topFilesList, top3.map { Triple(it.second, "${it.third} 次", it.first) })
    }

    /**
     * 渲染常看作者与标签列表（作者/标签混合 Top 3）。
     * 作者浏览 = 该作者关联文件在窗口内浏览聚合的次数和（allAuthorMedia → recordKey 匹配窗口浏览 key）。
     */
    private fun renderTopAuthors() {
        val window = windowDaily()
        val viewsByKey = window.associate { it.recordKey to (it.viewCount + it.playCount) }
        val browsedKeys = viewsByKey.filterValues { it > 0 }.keys

        // 作者浏览聚合（按浏览数值降序取前 2）
        val authorRows = cachedAuthorData.mapNotNull { (authorId, name, _) ->
            val views = cachedAuthorMedia
                .filter { it.authorId == authorId && it.recordKey in browsedKeys }
                .sumOf { viewsByKey[it.recordKey] ?: 0 }
            if (views > 0) Triple(name, views, authorId) else null
        }.sortedByDescending { it.second }.take(2)

        // 标签浏览聚合（按浏览数值降序取前 2）
        val tagRows = cachedMediaTags
            .filter { it.recordKey in browsedKeys }
            .groupBy { it.name }
            .map { (name, list) -> Triple(name, list.sumOf { viewsByKey[it.recordKey] ?: 0 }, name) }
            .sortedByDescending { it.second }
            .take(2)

        // 混合：作者在前 + 标签在后，共 3 行
        val mixed = (authorRows.map { Triple(it.first, "${it.second} 次", it.third) } +
            tagRows.map { Triple(it.first, "${it.second} 次", it.third) }).take(3)
        renderTextList(binding.topAuthorsList, mixed)
    }

    /** 渲染紧凑文本列表（标题 + 副标题 + 隐藏点击 id，整卡可点击进详情） */
    private fun renderTextList(container: LinearLayout, items: List<Triple<String, String, String>>) {
        container.removeAllViews()
        items.forEach { (title, subtitle, _) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 6f.dp(requireContext()).toInt()
                }
            }
            val titleView = TextView(requireContext()).apply {
                text = title
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextPrimary))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    marginEnd = 8f.dp(requireContext()).toInt()
                }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val valueView = TextView(requireContext()).apply {
                text = subtitle
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorPrimary))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            row.addView(titleView)
            row.addView(valueView)
            container.addView(row)
        }
        if (items.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "暂无数据"
                setTextColor(requireContext().resolveThemeColor(R.attr.qmColorTextSecondary))
                textSize = 12f
            }
            container.addView(empty)
        }
    }

    private fun renderAll() {
        renderOverview()
        renderOverviewExtra()
        renderTrend()
        renderTopFiles()
        renderTopAuthors()
    }

    private fun formatDuration(minutes: Long): String {
        val us = java.util.Locale.US
        return when {
            minutes >= 1440 -> String.format(us, "%.1f天", minutes / 1440.0)
            minutes >= 60 -> String.format(us, "%.1f时", minutes / 60.0)
            else -> "${minutes}分"
        }
    }

    private fun Float.dp(context: android.content.Context): Float =
        this * context.resources.displayMetrics.density

    private fun Int.dp(context: android.content.Context): Int =
        (this * context.resources.displayMetrics.density).toInt()

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
