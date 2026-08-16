package com.qimeng.media.ui.all

import android.os.Bundle
import android.view.LayoutInflater
import com.qimeng.media.ui.widget.PinchZoomHelper
import com.qimeng.media.ui.widget.ColumnsRef
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.ThemeHelper
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.data.db.model.MediaTagName
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.databinding.FragmentAllFilesBinding
import com.qimeng.media.ui.adapter.GroupedMediaAdapter
import com.qimeng.media.ui.browser.FilterConfig
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.ui.browser.MediaFilterSheet
import com.qimeng.media.ui.browser.MediaFilterState
import com.qimeng.media.ui.browser.MediaGroupHelper
import com.qimeng.media.ui.browser.MediaPillsHelper
import com.qimeng.media.ui.browser.MediaRenderHelper
import com.qimeng.media.ui.library.MediaLibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ComputeResult(
    val sourceGroups: Map<String, List<MediaFileEntity>>,
    val charGroups: Map<String, List<MediaFileEntity>>,
    val filtered: List<MediaFileEntity>,
    val totalSize: Long,
    // v1.16：带回计算后的新缓存键，由主线程统一回写 sourceGroupsKey/charGroupsKey
    val sourceKey: String,
    val charKey: String
)

/**
 * 分组缓存主线程快照（v1.16）：render 启动前在主线程取当前缓存值传入，
 * Default 块内不再读写共享缓存字段，计算结果统一回主线程回写，
 * 使 renderJob.cancel() 能安全阻止已跑完的计算回写过期分组。
 */
private data class GroupsCacheSnapshot(
    val sourceGroups: Map<String, List<MediaFileEntity>>,
    val sourceKey: String,
    val charGroups: Map<String, List<MediaFileEntity>>,
    val charKey: String
)

class AllFilesFragment : Fragment() {
    private var _binding: FragmentAllFilesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var filterType: String? = null
    private var filterState = MediaFilterState()
    private var viewMode = ""
    private var selectedPartitions = mutableSetOf("常规", "COS")
    private var partitionPillsExpanded = true
    private var selectedSources = mutableSetOf<String>()
    private var selectedChars = mutableSetOf<String>()
    private var sourcePillsExpanded = true
    private var charPillsExpanded = true
    private var typePillsExpanded = true
    private var cachedSourceGroups: Map<String, List<MediaFileEntity>> = emptyMap()
    private var cachedCharGroups: Map<String, List<MediaFileEntity>> = emptyMap()
    private var sourceGroupsKey = ""   // 缓存键：partition+filterType+dataVersion
    private var charGroupsKey = ""     // 缓存键：partition+filterType+selectedSources+dataVersion
    private var dataVersion = 0        // 数据变化时递增，用于判断分组是否需要重算
    private val columnsRef = ColumnsRef(DEFAULT_COLUMNS)

    // v1.15：App 偏好管理器（列数持久化用）——从 AppContainer 获取，允许为 null
    private val appPrefsManager: com.qimeng.media.data.prefs.AppPrefsManager?
        get() = (requireActivity().application as? com.qimeng.media.QimengApplication)
            ?.appContainer?.appPrefsManager

    private var cachedMedia = emptyList<MediaFileEntity>()
    private var cachedCosMedia = emptyList<MediaFileEntity>()
    private var cachedCosWorks = emptyList<CosWorkEntity>()
    private var cachedAuthorMedia = emptyList<AuthorMediaCrossRef>()
    private var cachedAuthors = emptyList<AuthorEntity>()
    private var cachedStats = emptyList<ViewStatsEntity>()
    private var cachedTags = emptyList<MediaTagName>()
    private var cachedAllTags = emptyList<TagEntity>()
    private var cachedHistory = emptyList<ViewHistoryEntity>()
    private var lastRenderedFingerprint: String = ""
    private var mediaFingerprint: String = ""
    private var observeStarted: Boolean = false
    // v1.12：数据收集器 job 引用——下拉刷新会重复调 observeData() 叠加永久收集器，
    // 刷新前取消旧收集器，避免每个数据变更被 N 个收集器重复处理
    private var observeJob: kotlinx.coroutines.Job? = null
    // v1.12：render 协程 job——旧 render 晚完成会覆盖新选中态 UI，启动新 render 前取消旧的
    private var renderJob: kotlinx.coroutines.Job? = null
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var adapter: GroupedMediaAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAllFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = GroupedMediaAdapter(
            onItemClick = { media, source ->
                (requireActivity() as? MainActivity)?.showDetailFragment(media.recordKey, source.map { it.recordKey })
            },
            lifecycleScope = viewLifecycleOwner.lifecycleScope
        )
        setupRecycler()
        setupActions()
        observeData()
    }

    private fun setupRecycler() {
        // v1.15：列数持久化——启动/重建时从 AppPrefsManager 读取上次保存的列数作为初始值
        val savedColumns = appPrefsManager?.prefs?.value?.gridColumnsAll ?: DEFAULT_COLUMNS
        columnsRef.value = savedColumns
        columnsRef.baseValue = savedColumns
        gridLayoutManager = PinchZoomHelper.createGridLayoutManager(requireContext(), columnsRef.value, adapter)
        binding.allFilesRecycler.layoutManager = gridLayoutManager
        binding.allFilesRecycler.adapter = adapter
        binding.allFilesRecycler.itemAnimator = null
        binding.allFilesRecycler.setItemViewCacheSize(20)
        binding.allFilesRecycler.setRecycledViewPool(RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20)
        })

        // v1.15：双指缩放结束后把新列数持久化到 AppPrefsManager（重启保留）
        PinchZoomHelper.setup(requireContext(), columnsRef, gridLayoutManager, binding.allFilesRecycler,
            onColumnsChanged = { newCols ->
                appPrefsManager?.updateGridColumnsAll(newCols)
                // v1.16：列数变化同步缩略图解码尺寸（屏宽/列数估算），2 列不模糊、5 列不浪费
                adapter.updateThumbnailSize(newCols)
            })
        // v1.16：初始 setup 按当前列数设定缩略图解码尺寸
        adapter.updateThumbnailSize(columnsRef.value)
    }

    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    private fun setupActions() {
        binding.allSwipeRefresh.setOnRefreshListener {
            // v1.12：先取消旧收集器，避免每次下拉叠加一个永久收集器
            observeJob?.cancel()
            lastRenderedFingerprint = ""
            mediaFingerprint = ""
            observeStarted = false
            observeData()
            binding.allSwipeRefresh.isRefreshing = false
        }
        scrollListener = ViewTreeObserver.OnScrollChangedListener {
            _binding?.let { binding ->
                binding.allSwipeRefresh.isEnabled = !binding.allPillsScroller.canScrollVertically(-1)
            }
        }
        scrollListener?.let { binding.allPillsScroller.viewTreeObserver.addOnScrollChangedListener(it) }
        binding.allChipAll.setOnClickListener { setViewMode(VIEW_MODE_PARTITION) }
        binding.allChipSource.setOnClickListener { setViewMode(VIEW_MODE_SOURCE) }
        binding.allChipCharacter.setOnClickListener { setViewMode(VIEW_MODE_CHARACTER) }
        binding.allChipType.setOnClickListener { setViewMode(VIEW_MODE_TYPE) }
        binding.allFilterButton.setOnClickListener {
            MediaFilterSheet.show(requireContext(), filterState, cachedAllTags, FilterConfig.FULL,
                onApply = { next ->
                    filterState = next
                    render()
                },
                onAddTag = { name -> viewModel.createTag(name) },
                onDeleteTag = { tagId -> viewModel.deleteTagById(tagId) }
            )
        }
        binding.allColumnText.setOnClickListener {
            setColumns(if (columnsRef.value >= PinchZoomHelper.MAX_COLUMNS) PinchZoomHelper.MIN_COLUMNS else columnsRef.value + 1)
        }
        setColumns(columnsRef.value)
    }

    private fun observeData() {
        observeJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 重新收集 Flow 时需要重新 submitMedia（避免白屏），但不重置数据指纹（避免不必要刷新）
                lastRenderedFingerprint = ""
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
                    dataVersion++  // 数据变化，标记分组缓存失效

                    // 注：此处不再调用 computeSourceGroups()——它已退化为 render() 的空壳委托，
                    // 与下方 render() 重复执行（fingerprint 相同时白白浪费 45-210ms 主线程时间）。
                    // 分区药丸点击回调（renderPartitionPills 内）仍通过 computeSourceGroups() 触发 render。
                    // v1.12：指纹纳入 COS 侧数据（cosMedia/cosWorks 的首条与数量）——旧指纹只含
                    // nonCosMedia，COS 文件新增/作品表变更时 fp 不变 → 全部页不自动重渲染
                    // v1.15：指纹追加 tags 维度——详情页标签管理（显式操作）后自动重渲染；
                    // 不纳入浏览统计（stats）：全部页按日期/出处分组不按热度排序，浏览只改变
                    // viewCount 不影响列表内容，纳入会导致"浏览退出→返回"无意义重渲染
                    val fp = buildString {
                        append("${media.size}|${media.firstOrNull()?.recordKey.orEmpty()}|${cosMedia.size}|${cosMedia.firstOrNull()?.recordKey.orEmpty()}|${cosWorks.size}")
                        append("|tags=")
                        tags.forEach { append("${it.recordKey}:${it.tagId},") }
                    }
                    if (fp != mediaFingerprint || !observeStarted) {
                        mediaFingerprint = fp
                        observeStarted = true
                        render()
                    }
                }
            }
        }
    }

    private fun setColumns(next: Int) {
        columnsRef.value = next.coerceIn(PinchZoomHelper.MIN_COLUMNS, PinchZoomHelper.MAX_COLUMNS)
        gridLayoutManager.spanCount = columnsRef.value
        gridLayoutManager.spanSizeLookup?.invalidateSpanIndexCache()
        // v1.15：列数按钮切换同样持久化（双指缩放已在 onColumnsChanged 回调持久化）
        appPrefsManager?.updateGridColumnsAll(columnsRef.value)
        // v1.16：列数变化同步缩略图解码尺寸
        adapter.updateThumbnailSize(columnsRef.value)
        val iconRes = when (columnsRef.value) {
            2 -> R.drawable.ic_grid_2
            3 -> R.drawable.ic_grid_3
            4 -> R.drawable.ic_grid_4
            5 -> R.drawable.ic_grid_5
            else -> R.drawable.ic_grid_3
        }
        binding.allColumnText.setImageResource(iconRes)
    }

    private fun render() {
        if (_binding == null) return
        val isCosPartition = selectedPartitions == setOf("COS")
        val isAllPartition = selectedPartitions.containsAll(listOf("常规", "COS"))
        val workingMedia = when {
            isCosPartition -> cachedCosMedia
            isAllPartition -> cachedMedia + cachedCosMedia
            else -> cachedMedia
        }
        val renderStart = System.currentTimeMillis()

        // 立即根据展开状态设置胶囊栏可见性，避免异步计算期间旧UI残留
        val pillsExpanded = when (viewMode) {
            VIEW_MODE_PARTITION -> partitionPillsExpanded
            VIEW_MODE_SOURCE -> sourcePillsExpanded
            VIEW_MODE_CHARACTER -> charPillsExpanded
            VIEW_MODE_TYPE -> typePillsExpanded
            else -> false
        }
        if (!pillsExpanded) {
            binding.allPillsScroller.visibility = View.GONE
            binding.allPillsWrapper.removeAllViews()
        }

        // v1.12：启动去重后的 render——取消上一个未完成的 render 协程，
        // 防止旧 Default 计算晚完成覆盖新选中态 UI
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val computeStart = System.currentTimeMillis()
            // v1.16：主线程先快照缓存初值——Default 块内只读快照计算，不再直接写共享缓存字段
            val cacheIn = GroupsCacheSnapshot(cachedSourceGroups, sourceGroupsKey, cachedCharGroups, charGroupsKey)
            val computeResult = computeAllGroupsAsync(workingMedia, isCosPartition, isAllPartition, cacheIn, viewMode)

            AppLog.d("AllFiles", "render: compute=${System.currentTimeMillis() - computeStart}ms, mode=$viewMode, workSize=${workingMedia.size}")

            if (_binding == null) return@launch
            // v1.16：回主线程统一回写缓存字段（withContext 恢复点检查取消，
            // renderJob.cancel() 后此处不会执行，过期分组不再回写覆盖新列表）
            cachedSourceGroups = computeResult.sourceGroups
            sourceGroupsKey = computeResult.sourceKey
            cachedCharGroups = computeResult.charGroups
            charGroupsKey = computeResult.charKey
            updateAllUI(computeResult, isCosPartition, isAllPartition, renderStart)
        }
    }

    /** 协程内计算：类型筛选 + 缓存键判定 + 出处/角色分组 + applyFilter（缓存读写均走快照与返回值） */
    private suspend fun computeAllGroupsAsync(
        workingMedia: List<MediaFileEntity>,
        isCosPartition: Boolean,
        isAllPartition: Boolean,
        cacheIn: GroupsCacheSnapshot,
        viewModeSnapshot: String
    ): ComputeResult = withContext(Dispatchers.Default) {
        val stats = cachedStats.associateBy { it.recordKey }
        val tagMap = cachedTags.groupBy { it.recordKey }.mapValues { entry -> entry.value.map { it.name }.toSet() }
        val typed = MediaRenderHelper.applyTypeFilter(workingMedia, filterType)

        // v1.12：快照选中集合——主线程药丸点击会 clear()/addAll() 修改可变 Set，
        // 直接在 Default 线程迭代 sorted()/filterKeys 可能 ConcurrentModificationException
        val partitionsSnapshot = selectedPartitions.toSet().sorted().joinToString("+")
        val sourcesSnapshot = selectedSources.toSet().sorted().joinToString("+")

        // 缓存键：只在数据/filterType/partition/selectedSources变化时重算分组
        val newSourceKey = "$partitionsSnapshot|$filterType|$dataVersion"
        val newCharKey = "$partitionsSnapshot|$filterType|$sourcesSnapshot|$dataVersion"

        // v1.16：局部变量承载缓存命中/重算结果，Default 块内不写共享字段
        var srcGroups = cacheIn.sourceGroups
        var srcKey = cacheIn.sourceKey
        if (newSourceKey != srcKey) {
            srcGroups = when {
                isCosPartition -> MediaGroupHelper.groupByCosAuthor(typed, cachedAuthorMedia, cachedAuthors)
                isAllPartition -> {
                    val regular = MediaGroupHelper.groupBySource(typed.filter { !it.isCosFile })
                    val cos = MediaGroupHelper.groupByCosAuthor(typed.filter { it.isCosFile }, cachedAuthorMedia, cachedAuthors)
                    mergeGroups(regular, cos)
                }
                else -> MediaGroupHelper.groupBySource(typed)
            }
            srcKey = newSourceKey
        }

        val needCharGroups = viewModeSnapshot == VIEW_MODE_CHARACTER || selectedChars.isNotEmpty()
        var chrGroups = cacheIn.charGroups
        var chrKey = cacheIn.charKey
        if (needCharGroups && newCharKey != chrKey) {
            // v1.12：快照化判断与过滤，避免并发修改
            val sourcesSnapshotSet = selectedSources.toSet()
            val baseForChars = if (sourcesSnapshotSet.isNotEmpty()) {
                val sourceMediaSet = srcGroups.filterKeys { it in sourcesSnapshotSet }.values.flatten().toSet()
                typed.filter { it in sourceMediaSet }
            } else typed
            chrGroups = when {
                isCosPartition -> MediaGroupHelper.groupByCosWork(baseForChars, cachedAuthorMedia, cachedAuthors, cachedCosWorks)
                isAllPartition -> {
                    val regular = MediaGroupHelper.groupByCharacter(baseForChars.filter { !it.isCosFile })
                    val cos = MediaGroupHelper.groupByCosWork(baseForChars.filter { it.isCosFile }, cachedAuthorMedia, cachedAuthors, cachedCosWorks)
                    mergeGroups(regular, cos)
                }
                else -> MediaGroupHelper.groupByCharacter(baseForChars)
            }
            chrKey = newCharKey
        }

        // v1.12：快照传入，避免 Default 线程迭代可变 Set 的 CME
        val sourcesSnapshotSet = selectedSources.toSet()
        val charsSnapshotSet = selectedChars.toSet()
        val displayed = MediaRenderHelper.computeDisplayed(
            typed, srcGroups, chrGroups, sourcesSnapshotSet, charsSnapshotSet
        )

        val result = MediaBrowserLogic.applyFilter(displayed, query = "", filterState, stats, tagMap, cachedHistory)
        val size = result.sumOf { it.sizeBytes }
        ComputeResult(srcGroups, chrGroups, result, size, srcKey, chrKey)
    }

    /** 协程外 UI 更新：药丸渲染 + fingerprint + adapter 提交 + 计时 */
    private fun updateAllUI(
        compute: ComputeResult,
        isCosPartition: Boolean,
        isAllPartition: Boolean,
        renderStart: Long
    ) {
        val sourceGroups = compute.sourceGroups
        val charGroups = compute.charGroups
        val filtered = compute.filtered
        val totalSize = compute.totalSize

        when (viewMode) {
            VIEW_MODE_PARTITION -> renderPartitionPills()
            VIEW_MODE_SOURCE -> renderSourcePills(sourceGroups)
            VIEW_MODE_CHARACTER -> {
                if (isCosPartition) renderCosWorkPills(charGroups) else renderCharPills(charGroups)
                selectedChars.retainAll(charGroups.keys)
            }
            VIEW_MODE_TYPE -> renderTypePills()
            else -> binding.allPillsScroller.visibility = View.GONE
        }

        val fingerprint = MediaRenderHelper.buildFingerprint(
            MediaRenderHelper.FingerprintParams(
                viewMode = viewMode,
                partitions = selectedPartitions.toSet(),
                displayed = filtered,
                selectedSources = selectedSources.toSet(),
                selectedChars = selectedChars.toSet(),
                filterType = filterType,
                typePillsExpanded = typePillsExpanded,
                sourceGroups = sourceGroups,
                charGroups = charGroups
            )
        )
        val label = when {
            isCosPartition -> "COS"
            isAllPartition -> "文件"
            else -> "常规"
        }
        binding.allFilesSummaryText.text = "${filtered.size} $label · ${MediaBrowserLogic.formatSize(totalSize)}"
        if (fingerprint != lastRenderedFingerprint) {
            lastRenderedFingerprint = fingerprint
            // v1.15：改用 Async 变体——分组行组装与指纹计算移入 Default 线程，大库下主线程不再卡顿
            when (viewMode) {
                VIEW_MODE_CHARACTER -> if (selectedChars.isEmpty()) adapter.submitMediaWithGroupsAsync(filtered, charGroups, viewLifecycleOwner.lifecycleScope) else adapter.submitMediaAsync(filtered, viewLifecycleOwner.lifecycleScope)
                VIEW_MODE_SOURCE -> if (selectedSources.isEmpty()) adapter.submitMediaWithGroupsAsync(filtered, sourceGroups, viewLifecycleOwner.lifecycleScope) else adapter.submitMediaAsync(filtered, viewLifecycleOwner.lifecycleScope)
                else -> adapter.submitMediaAsync(filtered, viewLifecycleOwner.lifecycleScope)
            }
        }

        updateChipStyles()
        AppLog.d("AllFiles", "render: total=${System.currentTimeMillis() - renderStart}ms, filtered=${filtered.size}, fp=$fingerprint")
    }

    fun scrollToTop() {
        binding.allFilesRecycler.stopScroll()
        gridLayoutManager.scrollToPositionWithOffset(0, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scrollListener?.let { _binding?.allPillsScroller?.viewTreeObserver?.removeOnScrollChangedListener(it) }
        scrollListener = null
        _binding = null
    }

    private fun updateChipStyles() {
        val colors = ThemeHelper.resolve(requireContext())
        val partitionCount = 2 // 常规 + COS
        val sourceCount = cachedSourceGroups.size
        val charCount = cachedCharGroups.size
        val typeCount = 3 // 图片 + 视频 + 动图
        listOf(
            Triple(binding.allChipAll, VIEW_MODE_PARTITION, "分区 ($partitionCount)"),
            Triple(binding.allChipSource, VIEW_MODE_SOURCE, "作品 ($sourceCount)"),
            Triple(binding.allChipCharacter, VIEW_MODE_CHARACTER, "角色 ($charCount)"),
            Triple(binding.allChipType, VIEW_MODE_TYPE, "类型 ($typeCount)")
        ).forEach { (chip, mode, label) ->
            val active = viewMode == mode
            chip.text = label
            chip.setBackgroundResource(if (active) R.drawable.bg_capsule_primary else 0)
            chip.setTextColor(if (active) colors.bg else colors.textSecondary)
        }
    }

    private fun setViewMode(mode: String) {
        if (mode == viewMode) {
            when (mode) {
                VIEW_MODE_PARTITION -> partitionPillsExpanded = !partitionPillsExpanded
                VIEW_MODE_SOURCE -> sourcePillsExpanded = !sourcePillsExpanded
                VIEW_MODE_CHARACTER -> charPillsExpanded = !charPillsExpanded
                VIEW_MODE_TYPE -> typePillsExpanded = !typePillsExpanded
            }
            lastRenderedFingerprint = ""
            updateChipStyles()
            render()
            return
        }
        viewMode = mode
        if (mode == VIEW_MODE_PARTITION) partitionPillsExpanded = true
        if (mode == VIEW_MODE_SOURCE) sourcePillsExpanded = true
        if (mode == VIEW_MODE_CHARACTER) charPillsExpanded = true
        if (mode == VIEW_MODE_TYPE) typePillsExpanded = true
        // v1.12：切换模式清空残留选中（对齐 Favorite/History 的 setViewMode）——
        // 旧实现不清 selectedSources/selectedChars，角色模式选中后切到分区模式，
        // computeDisplayed 仍按旧选中过滤，列表被残留选中项误过滤
        selectedSources.clear()
        selectedChars.clear()
        lastRenderedFingerprint = ""
        updateChipStyles()
        render()
    }

    private fun renderPartitionPills() {
        val wrapper = binding.allPillsWrapper
        val scroller = binding.allPillsScroller
        wrapper.removeAllViews()

        scroller.visibility = View.VISIBLE
        val colors = ThemeHelper.resolve(requireContext())

        if (!partitionPillsExpanded) {
            scroller.visibility = View.GONE
            return
        }

        // 分区选项：全部 / 常规 / COS
        val partitions = listOf("常规" to cachedMedia.size, "COS" to cachedCosMedia.size)
        val totalCount = cachedMedia.size + cachedCosMedia.size
        val flow = MediaPillsHelper.createFlowLayout(requireContext())

        val isAllSelected = selectedPartitions.containsAll(listOf("常规", "COS"))
        val allPill = MediaPillsHelper.createPill(requireContext(), "全部 ($totalCount)", isAllSelected, colors)
        allPill.setOnClickListener {
            selectedPartitions.clear()
            selectedPartitions.addAll(listOf("常规", "COS"))
            selectedSources.clear()
            selectedChars.clear()
            lastRenderedFingerprint = ""
            computeSourceGroups()
        }
        flow.addView(allPill)

        for ((name, count) in partitions) {
            val isActive = when (name) {
                "常规" -> selectedPartitions.contains("常规") && !selectedPartitions.contains("COS")
                "COS" -> selectedPartitions.contains("COS") && !selectedPartitions.contains("常规")
                else -> false
            }
            val pill = MediaPillsHelper.createPill(requireContext(), "$name ($count)", isActive, colors)
            pill.setOnClickListener {
                when (name) {
                    "常规" -> {
                        selectedPartitions.clear()
                        selectedPartitions.add("常规")
                        selectedSources.clear()
                        selectedChars.clear()
                    }
                    "COS" -> {
                        selectedPartitions.clear()
                        selectedPartitions.add("COS")
                        selectedSources.clear()
                        selectedChars.clear()
                    }
                }
                lastRenderedFingerprint = ""
                computeSourceGroups()
            }
            flow.addView(pill)
        }

        val collapsePill = MediaPillsHelper.createPill(requireContext(), "收起 ▲", false, colors)
        collapsePill.setOnClickListener {
            partitionPillsExpanded = false
            lastRenderedFingerprint = ""
            render()
        }
        flow.addView(collapsePill)

        wrapper.addView(flow)
    }

    private fun computeSourceGroups() {
        // 出处分组现在在render()中基于typed计算，这里只需触发render
        render()
    }

    private fun renderCosWorkPills(charGroups: Map<String, List<MediaFileEntity>>) {
        MediaPillsHelper.renderCosWorkPills(
            requireContext(),
            binding.allPillsWrapper,
            binding.allPillsScroller,
            charGroups,
            selectedChars,
            charPillsExpanded,
            onPillClick = {
                lastRenderedFingerprint = ""
                render()
            },
            onCollapse = {
                charPillsExpanded = false
                lastRenderedFingerprint = ""
                render()
            }
        )
    }

    /** 合并常规和COS分组，同key时合并文件列表而非覆盖（如"其他"） */
    private fun mergeGroups(
        regular: Map<String, List<MediaFileEntity>>,
        cos: Map<String, List<MediaFileEntity>>
    ): Map<String, List<MediaFileEntity>> {
        val merged = regular.toMutableMap()
        for ((key, value) in cos) {
            merged[key] = merged[key]?.plus(value) ?: value
        }
        return merged
    }

    private fun renderTypePills() {
        val isCosPartition = selectedPartitions == setOf("COS")
        val isAllPartition = selectedPartitions.containsAll(listOf("常规", "COS"))
        val workingMedia = when {
            isCosPartition -> cachedCosMedia
            isAllPartition -> cachedMedia + cachedCosMedia
            else -> cachedMedia
        }
        MediaPillsHelper.renderTypePills(
            requireContext(),
            binding.allPillsWrapper,
            binding.allPillsScroller,
            workingMedia,
            filterType,
            typePillsExpanded,
            onFilterChanged = { filterType = it },
            onPillClick = {
                lastRenderedFingerprint = ""
                render()
            },
            onCollapse = {
                typePillsExpanded = false
                lastRenderedFingerprint = ""
                render()
            }
        )
    }

    private fun renderSourcePills(sourceGroups: Map<String, List<MediaFileEntity>>) {
        MediaPillsHelper.renderSourcePills(
            requireContext(),
            binding.allPillsWrapper,
            binding.allPillsScroller,
            sourceGroups,
            selectedSources,
            sourcePillsExpanded,
            onPillClick = {
                lastRenderedFingerprint = ""
                render()
            },
            onCollapse = {
                sourcePillsExpanded = false
                lastRenderedFingerprint = ""
                render()
            }
        )
    }

    private fun renderCharPills(charGroups: Map<String, List<MediaFileEntity>>) {
        MediaPillsHelper.renderCharPills(
            requireContext(),
            binding.allPillsWrapper,
            binding.allPillsScroller,
            charGroups,
            selectedChars,
            charPillsExpanded,
            onPillClick = {
                lastRenderedFingerprint = ""
                render()
            },
            onCollapse = {
                charPillsExpanded = false
                lastRenderedFingerprint = ""
                render()
            }
        )
    }

    companion object {
        const val DEFAULT_COLUMNS = 3
        const val VIEW_MODE_PARTITION = "partition"
        const val VIEW_MODE_SOURCE = "source"
        const val VIEW_MODE_CHARACTER = "character"
        const val VIEW_MODE_TYPE = "type"
    }
}
