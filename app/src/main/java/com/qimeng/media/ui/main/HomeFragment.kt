package com.qimeng.media.ui.main

import android.os.Bundle
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.ThemeHelper
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.data.db.model.MediaTagName
import com.qimeng.media.ui.adapter.MediaThumbnailAdapter
import com.qimeng.media.ui.browser.FilterConfig
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.ui.browser.MediaFilterSheet
import com.qimeng.media.ui.browser.MediaFilterState
import com.qimeng.media.ui.widget.dp
import com.qimeng.media.ui.browser.MediaRankingPeriod
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.search.SearchFragment
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {
    private var homeRecycler: RecyclerView? = null

    private var swipeRefresh: SwipeRefreshLayout? = null
    private var chipRecommend: TextView? = null
    private var chipRanking: TextView? = null
    private var chipCos: TextView? = null
    private var rankPeriodContainer: LinearLayout? = null
    private var searchInput: EditText? = null
    private var filterButton: ImageView? = null
    private var columnButton: ImageView? = null
    private var rankChips: Map<MediaRankingPeriod, TextView> = emptyMap()
    private var layoutManager: GridLayoutManager? = null

    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var currentTab = TAB_RECOMMEND
    private var rankPeriod = MediaRankingPeriod.DAY
    private var columns = GRID_COLUMNS
    private var displayCount = INITIAL_DISPLAY_COUNT
    private var query = ""
    private var filterState = MediaFilterState()
    private var cachedMedia = emptyList<MediaFileEntity>()
    private var cachedCosMedia = emptyList<MediaFileEntity>()
    private var cachedCosWorks = emptyList<CosWorkEntity>()
    private var cachedAuthorMedia = emptyList<AuthorMediaCrossRef>()
    private var cachedAuthors = emptyList<AuthorEntity>()
    private var cachedStats = emptyList<ViewStatsEntity>()
    private var cachedTags = emptyList<MediaTagName>()
    private var cachedAllTags = emptyList<TagEntity>()
    private var cachedHistory = emptyList<ViewHistoryEntity>()
    private var lastRenderedKeys: String = ""
    // 每个 tab 独立维护排序缓存，左右滑动切换 tab 时不重新推荐
    private var tabSortFingerprints = mutableMapOf<Int, String>()
    private var tabSortedItems = mutableMapOf<Int, List<MediaFileEntity>>()
    private var tabDisplayCounts = mutableMapOf<Int, Int>()
    private var refreshSeed = 0
    // 每日推荐去重：记录当天已展示过的 recordKey
    private var dailyShownCountMap: MutableMap<String, Int> = mutableMapOf()
    private var dailyShownDate: String = ""
    private lateinit var adapter: MediaThumbnailAdapter
    private var flingConsumedAt = 0L
    private var cachedLikeCounts: Map<String, Int> = emptyMap()
    private var likeCountsDirty = true
    private val likePrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith(KEY_LIKE_COUNT_PREFIX)) {
            likeCountsDirty = true
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecycler()
        setupActions()
        observeData()
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(likePrefsListener)
    }

    private fun bindViews(view: View) {
        swipeRefresh = view.findViewById(R.id.homeSwipeRefresh)
        homeRecycler = view.findViewById(R.id.homeRecycler)

        chipRecommend = view.findViewById(R.id.chipRecommend)
        chipRanking = view.findViewById(R.id.chipRanking)
        chipCos = view.findViewById(R.id.chipCos)
        rankPeriodContainer = view.findViewById(R.id.homeRankPeriodContainer)
        searchInput = view.findViewById(R.id.homeSearchInput)
        filterButton = view.findViewById(R.id.homeFilterButton)
        columnButton = view.findViewById(R.id.homeColumnButton)
        rankChips = mapOf(
            MediaRankingPeriod.DAY to view.findViewById(R.id.chipRankDay),
            MediaRankingPeriod.WEEK to view.findViewById(R.id.chipRankWeek),
            MediaRankingPeriod.MONTH to view.findViewById(R.id.chipRankMonth),
            MediaRankingPeriod.YEAR to view.findViewById(R.id.chipRankYear)
        )
    }

    private fun setupRecycler() {
        adapter = MediaThumbnailAdapter(
            onItemClick = { media, source ->
                if (System.currentTimeMillis() - flingConsumedAt < 300) return@MediaThumbnailAdapter
                (requireActivity() as? MainActivity)?.showDetailFragment(media.recordKey, source.map { it.recordKey })
            },
            lifecycleScope = viewLifecycleOwner.lifecycleScope
        )
        layoutManager = GridLayoutManager(requireContext(), columns)
        homeRecycler?.layoutManager = layoutManager
        homeRecycler?.adapter = adapter
        homeRecycler?.setItemViewCacheSize(20)
        homeRecycler?.itemAnimator = null
        updateThumbnailSize()
        homeRecycler?.setRecycledViewPool(RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20)
        })
        homeRecycler?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                    displayCount += DISPLAY_INCREMENT
                    tabDisplayCounts[currentTab] = displayCount
                    val items = tabSortedItems[currentTab].orEmpty()
                    adapter.submitList(items.take(displayCount))
                }
            }
        })
    }

    private fun setupActions() {
        swipeRefresh?.setOnRefreshListener {
            refreshSeed++
            com.qimeng.media.core.AppLog.d("Home", "swipeRefresh: seed=$refreshSeed, dailyShown=${dailyShownCountMap.size}")
            // 清空所有 tab 的排序缓存，强制重新推荐
            tabSortFingerprints.clear()
            tabSortedItems.clear()
            tabDisplayCounts.clear()
            // 保留 dailyShownCountMap，让之前展示过的文件被惩罚，新文件排到前面
            lastRenderedKeys = ""
            displayCount = INITIAL_DISPLAY_COUNT
            viewLifecycleOwner.lifecycleScope.launch {
                render()
            }
            swipeRefresh?.isRefreshing = false
        }
        chipRecommend?.setOnClickListener { setTab(TAB_RECOMMEND) }
        chipRanking?.setOnClickListener { setTab(TAB_RANKING) }
        chipCos?.setOnClickListener { setTab(TAB_COS) }
        rankChips.forEach { (period, chip) -> chip.setOnClickListener { setRankPeriod(period) } }
        columnButton?.setOnClickListener { toggleColumns() }
        filterButton?.setOnClickListener {
            MediaFilterSheet.show(requireContext(), filterState, cachedAllTags, FilterConfig.FOR_HOME,
                onApply = { next ->
                    filterState = next
                    displayCount = INITIAL_DISPLAY_COUNT
                    viewLifecycleOwner.lifecycleScope.launch {
                        render()
                    }
                },
                onAddTag = { name -> viewModel.createTag(name) },
                onDeleteTag = { tagId -> viewModel.deleteTagById(tagId) }
            )
        }
        // 搜索栏点击跳转搜索界面
        searchInput?.isFocusable = false
        searchInput?.isFocusableInTouchMode = false
        searchInput?.setOnClickListener {
            (requireActivity() as? com.qimeng.media.MainActivity)?.showSearchFragment()
        }

        // 水平滑动手势切换 tab：推荐 → COS → 排行榜，左滑下一个，右滑上一个
        val gestureDetector = android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.rawX - e1.rawX
                val dy = e2.rawY - e1.rawY
                // 水平滑动距离足够且速度足够，且水平位移大于垂直位移
                if (abs(dx) > 50.dp(requireContext()) && abs(velocityX) > 300 && abs(dx) > abs(dy) * 1.5f) {
                    val tabOrder = listOf(TAB_RECOMMEND, TAB_COS, TAB_RANKING)
                    val currentIndex = tabOrder.indexOf(currentTab)
                    val nextTab = if (dx < 0f) {
                        // 左滑 → 下一个 tab
                        tabOrder.getOrNull(currentIndex + 1) ?: currentTab
                    } else {
                        // 右滑 → 上一个 tab
                        tabOrder.getOrNull(currentIndex - 1) ?: currentTab
                    }
                    if (nextTab != currentTab) {
                        flingConsumedAt = System.currentTimeMillis()
                        setTab(nextTab)
                    }
                    return true
                }
                return false
            }
        })
        homeRecycler?.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                return false
            }
        })
        updateChipStates()
        setColumns(columns)
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 重新收集 Flow 时需要重新 submitList（避免白屏），但不重置排序缓存（避免不必要刷新）
                lastRenderedKeys = ""
                combine(
                    combine(
                        viewModel.nonCosMedia,
                        viewModel.cosMedia,
                        viewModel.cosWorks,
                        viewModel.allStats,
                        viewModel.allMediaTags
                    ) { media, cosMedia, cosWorks, stats, tags ->
                        arrayOf(media, cosMedia, cosWorks, stats, tags)
                    },
                    combine(viewModel.allTags, viewModel.history, viewModel.allAuthorMedia, viewModel.authors) { allTags, history, authorMedia, authors ->
                        arrayOf(allTags, history, authorMedia, authors)
                    }
                ) { arr1, arr2 ->
                    arr1 + arr2
                }.collect @Suppress("UNCHECKED_CAST") { arr ->
                    val media = arr[0] as? List<MediaFileEntity> ?: return@collect
                    val cosMedia = arr[1] as? List<MediaFileEntity> ?: return@collect
                    val cosWorks = arr[2] as? List<CosWorkEntity> ?: return@collect
                    val stats = arr[3] as? List<ViewStatsEntity> ?: return@collect
                    val tags = arr[4] as? List<MediaTagName> ?: return@collect
                    val allTags = arr[5] as? List<TagEntity> ?: return@collect
                    val history = arr[6] as? List<ViewHistoryEntity> ?: return@collect
                    val authorMedia = arr[7] as? List<AuthorMediaCrossRef> ?: return@collect
                    val authors = arr[8] as? List<AuthorEntity> ?: return@collect

                    cachedMedia = media
                    cachedCosMedia = cosMedia
                    cachedCosWorks = cosWorks
                    cachedAuthorMedia = authorMedia
                    cachedAuthors = authors
                    cachedStats = stats
                    cachedTags = tags
                    cachedAllTags = allTags
                    cachedHistory = history

                    render()

                    // 后台预构建搜索索引，用户打开搜索页时索引已就绪
                    launch(Dispatchers.Default) {
                        SearchFragment.prebuildNameIndex(
                            cachedMedia, cachedCosMedia, cachedCosWorks, cachedAuthorMedia, cachedAuthors
                        )
                    }
                }
            }
        }
    }

    private fun setTab(tab: Int) {
        // 保存当前 tab 的排序缓存
        tabDisplayCounts[currentTab] = displayCount
        currentTab = tab
        // 恢复目标 tab 的排序缓存（如果有），否则使用默认值
        displayCount = tabDisplayCounts[tab] ?: INITIAL_DISPLAY_COUNT
        updateChipStates()
        rankPeriodContainer?.visibility = if (tab == TAB_RANKING) View.VISIBLE else View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            render()
        }
    }

    private fun setRankPeriod(period: MediaRankingPeriod) {
        rankPeriod = period
        displayCount = INITIAL_DISPLAY_COUNT
        updateChipStates()
        viewLifecycleOwner.lifecycleScope.launch {
            render()
        }
    }

    private fun toggleColumns() {
        columns = if (columns == 1) 2 else 1
        layoutManager?.spanCount = columns
        columnButton?.setImageResource(if (columns == 1) R.drawable.ic_grid_1 else R.drawable.ic_grid_2)
        updateThumbnailSize()
    }

    private fun setColumns(count: Int) {
        columns = count.coerceIn(1, 2)
        layoutManager?.spanCount = columns
        columnButton?.setImageResource(if (columns == 1) R.drawable.ic_grid_1 else R.drawable.ic_grid_2)
        updateThumbnailSize()
    }

    /** 根据列数调整缩略图分辨率：1列用960x540，2列用480x270 */
    private fun updateThumbnailSize() {
        if (columns == 1) {
            adapter.thumbnailWidth = 960
            adapter.thumbnailHeight = 540
        } else {
            adapter.thumbnailWidth = 480
            adapter.thumbnailHeight = 270
        }
    }

    private fun updateChipStates() {
        val colors = ThemeHelper.resolve(requireContext())
        listOf(
            chipRecommend to (currentTab == TAB_RECOMMEND),
            chipRanking to (currentTab == TAB_RANKING),
            chipCos to (currentTab == TAB_COS)
        ).forEach { (chip, active) ->
            chip?.setBackgroundResource(if (active) R.drawable.bg_capsule_primary else 0)
            chip?.setTextColor(if (active) colors.bg else colors.textSecondary)
        }
        rankChips.forEach { (period, chip) ->
            val active = period == rankPeriod
            chip.setBackgroundResource(if (active) R.drawable.bg_capsule_primary else 0)
            chip.setTextColor(if (active) colors.bg else colors.textSecondary)
        }
    }

    private suspend fun render() {
        val stats = cachedStats.associateBy { it.recordKey }
        val tagMap = cachedTags.groupBy { it.recordKey }.mapValues { entry -> entry.value.map { it.name }.toSet() }
        val likeCounts = readLikeCounts()

        // 每日重置去重记录
        val today = dailyDateFormat.format(java.util.Date())
        if (dailyShownDate != today) {
            dailyShownDate = today
            dailyShownCountMap.clear()
        }

        val workingMedia = if (query.isNotBlank()) {
            // 搜索时合并常规+COS文件
            cachedMedia + cachedCosMedia
        } else if (currentTab == TAB_COS) {
            cachedCosMedia
        } else {
            cachedMedia
        }
        val newFp = "${workingMedia.size}|$currentTab|$rankPeriod|${filterState.hashCode()}|$query|$refreshSeed"
        val oldFp = tabSortFingerprints[currentTab].orEmpty()
        com.qimeng.media.core.AppLog.d("Home", "render: fp=$newFp, oldFp=$oldFp, mediaSize=${workingMedia.size}, displayCount=$displayCount")
        if (newFp != oldFp) {
            tabSortFingerprints[currentTab] = newFp
            val filtered = withContext(Dispatchers.Default) {
                MediaBrowserLogic.applyFilter(workingMedia, query, filterState, stats, tagMap, cachedHistory, likeCounts)
            }
            val sorted = if (query.isNotBlank()) {
                filtered
            } else {
                if (currentTab == TAB_RANKING) {
                    withContext(Dispatchers.Default) {
                        MediaBrowserLogic.rank(filtered, rankPeriod, stats, cachedHistory, likeCounts)
                    }
                } else {
                    // 读取推荐偏好，默认值时不传 customPrefs 避免不必要的对象创建
                    val recPrefs = (requireActivity().application as com.qimeng.media.QimengApplication)
                        .appContainer.appPrefsManager.prefs.value.recommendationPrefs
                    val isDefault = recPrefs == com.qimeng.media.data.prefs.RecommendationPrefs()
                    MediaBrowserLogic.recommend(filtered, stats, tagMap, likeCounts, filtered.size, refreshSeed, dailyShownCountMap,
                        if (isDefault) null else recPrefs)
                }
            }
            tabSortedItems[currentTab] = sorted
            if (displayCount > sorted.size) {
                displayCount = sorted.size
                tabDisplayCounts[currentTab] = displayCount
            }
            // 记录本次展示的文件到每日去重列表（已展示的文件下次推荐会被 -0.8 惩罚）
            sorted.take(displayCount).forEach { item ->
                val count = dailyShownCountMap[item.recordKey] ?: 0
                dailyShownCountMap[item.recordKey] = count + 1
            }
            com.qimeng.media.core.AppLog.d("Home", "render: tab=$currentTab, seed=$refreshSeed, items=${sorted.size}, dailyShown=${dailyShownCountMap.size}, top3=${sorted.take(3).joinToString { it.fileName }}")
        }

        val cachedSortedItems = tabSortedItems[currentTab].orEmpty()
        val items = if (query.isNotBlank()) cachedSortedItems else cachedSortedItems.take(displayCount)
        val keys = items.joinToString("|") { it.recordKey }
        if (keys != lastRenderedKeys) {
            lastRenderedKeys = keys
            adapter.submitList(items)
        }
    }

    private fun readLikeCounts(): Map<String, Int> {
        if (!likeCountsDirty) return cachedLikeCounts
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val counts = mutableMapOf<String, Int>()
        for (entry in prefs.all.entries) {
            val key = entry.key as String
            if (key.startsWith(KEY_LIKE_COUNT_PREFIX) && entry.value is Int) {
                val recordKey = key.removePrefix(KEY_LIKE_COUNT_PREFIX)
                counts[recordKey] = entry.value as Int
            }
        }
        cachedLikeCounts = counts
        likeCountsDirty = false
        return counts
    }

    fun scrollToTop() {
        homeRecycler?.stopScroll()
        (homeRecycler?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(likePrefsListener)
        swipeRefresh = null
        homeRecycler = null

        chipRecommend = null
        chipRanking = null
        chipCos = null
        rankPeriodContainer = null
        searchInput = null
        filterButton = null
        columnButton = null
        rankChips = emptyMap()
        layoutManager = null
    }

    companion object {
        private val dailyDateFormat = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        const val GRID_COLUMNS = 2
        const val TAB_RECOMMEND = 0
    const val TAB_COS = 1
    const val TAB_RANKING = 2
        const val INITIAL_DISPLAY_COUNT = 40
        const val DISPLAY_INCREMENT = 20
        private const val PREFS_NAME = "media_detail_prefs"
        private const val KEY_LIKE_COUNT_PREFIX = "like_count_"
    }
}
