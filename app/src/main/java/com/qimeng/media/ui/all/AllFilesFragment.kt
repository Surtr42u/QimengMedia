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
import com.qimeng.media.ui.library.MediaLibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ComputeResult(
    val sourceGroups: Map<String, List<MediaFileEntity>>,
    val charGroups: Map<String, List<MediaFileEntity>>,
    val filtered: List<MediaFileEntity>,
    val totalSize: Long
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
    private var selectedPartitions = mutableSetOf<String>()
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
        gridLayoutManager = PinchZoomHelper.createGridLayoutManager(requireContext(), columnsRef.value, adapter)
        binding.allFilesRecycler.layoutManager = gridLayoutManager
        binding.allFilesRecycler.adapter = adapter
        binding.allFilesRecycler.itemAnimator = null
        binding.allFilesRecycler.setItemViewCacheSize(20)
        binding.allFilesRecycler.setRecycledViewPool(RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20)
        })

        PinchZoomHelper.setup(requireContext(), columnsRef, gridLayoutManager, binding.allFilesRecycler)
    }

    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    private fun setupActions() {
        binding.allSwipeRefresh.setOnRefreshListener {
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
            MediaFilterSheet.show(requireContext(), filterState, cachedAllTags, FilterConfig.FOR_ALL,
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
        viewLifecycleOwner.lifecycleScope.launch {
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

                    computeSourceGroups()

                    val fp = "${media.size}|${media.firstOrNull()?.recordKey.orEmpty()}"
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
        val isCosPartition = "COS" in selectedPartitions
        val workingMedia = if (isCosPartition) cachedCosMedia else cachedMedia
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

        viewLifecycleOwner.lifecycleScope.launch {
            val computeStart = System.currentTimeMillis()
            val computeResult = withContext(Dispatchers.Default) {
                val stats = cachedStats.associateBy { it.recordKey }
                val tagMap = cachedTags.groupBy { it.recordKey }.mapValues { entry -> entry.value.map { it.name }.toSet() }
                val typed = when (filterType) {
                    MediaType.IMAGE -> workingMedia.filter { it.mediaType == MediaType.IMAGE }
                    MediaType.VIDEO -> workingMedia.filter { it.mediaType == MediaType.VIDEO }
                    MediaType.ANIMATED_IMAGE -> workingMedia.filter { it.mediaType == MediaType.ANIMATED_IMAGE }
                    else -> workingMedia
                }

                // 缓存键：只在数据/filterType/partition/selectedSources变化时重算分组
                val newSourceKey = "$isCosPartition|$filterType|$dataVersion"
                val newCharKey = "$isCosPartition|$filterType|${selectedSources.sorted()}|$dataVersion"

                if (newSourceKey != sourceGroupsKey) {
                    cachedSourceGroups = if (isCosPartition) MediaGroupHelper.groupByCosAuthor(typed, cachedAuthorMedia, cachedAuthors)
                        else MediaGroupHelper.groupBySource(typed)
                    sourceGroupsKey = newSourceKey
                }

                val needCharGroups = viewMode == VIEW_MODE_CHARACTER || selectedChars.isNotEmpty()
                if (needCharGroups && newCharKey != charGroupsKey) {
                    val baseForChars = if (selectedSources.isNotEmpty()) {
                        val sourceMediaSet = cachedSourceGroups.filterKeys { it in selectedSources }.values.flatten().toSet()
                        typed.filter { it in sourceMediaSet }
                    } else typed
                    cachedCharGroups = if (isCosPartition) MediaGroupHelper.groupByCosWork(baseForChars, cachedAuthorMedia, cachedAuthors, cachedCosWorks) else MediaGroupHelper.groupByCharacter(baseForChars)
                    charGroupsKey = newCharKey
                }

                val srcGroups = cachedSourceGroups
                val chrGroups = cachedCharGroups

                var displayed = typed
                if (selectedSources.isNotEmpty()) {
                    val sourceMediaSet = srcGroups.filterKeys { it in selectedSources }.values.flatten().toSet()
                    displayed = displayed.filter { it in sourceMediaSet }
                }
                if (selectedChars.isNotEmpty()) {
                    val charMediaSet = chrGroups.filterKeys { it in selectedChars }.values.flatten().toSet()
                    displayed = displayed.filter { it in charMediaSet }
                }

                val result = MediaBrowserLogic.applyFilter(displayed, query = "", filterState, stats, tagMap, cachedHistory)
                val size = result.sumOf { it.sizeBytes }
                ComputeResult(srcGroups, chrGroups, result, size)
            }

            AppLog.d("AllFiles", "render: compute=${System.currentTimeMillis() - computeStart}ms, mode=$viewMode, workSize=${workingMedia.size}")

            if (_binding == null) return@launch

            val compute = computeResult
            val sourceGroups = compute.sourceGroups
            val charGroups = compute.charGroups
            val filtered = compute.filtered
            val totalSize = compute.totalSize

            cachedSourceGroups = sourceGroups
            cachedCharGroups = charGroups

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

            val fingerprint = buildString {
                append(viewMode)
                append("|partitions=")
                append(selectedPartitions.sorted().joinToString(","))
                append("|")
                append(filtered.size)
                append("|")
                append(filtered.firstOrNull()?.recordKey.orEmpty())
                append("|src=")
                append(selectedSources.sorted().joinToString(","))
                append("|chr=")
                append(selectedChars.sorted().joinToString(","))
                append("|ft=")
                append(filterType.orEmpty())
                append("|typeExp=")
                append(typePillsExpanded)
                if (viewMode == VIEW_MODE_SOURCE) {
                    append("|")
                    sourceGroups.entries.sortedBy { it.key }.forEach { (k, v) ->
                        append("$k:${v.size},")
                    }
                }
                if (viewMode == VIEW_MODE_CHARACTER) {
                    append("|")
                    charGroups.entries.sortedBy { it.key }.forEach { (k, v) ->
                        append("$k:${v.size},")
                    }
                }
            }
            val label = if (isCosPartition) "COS" else "文件"
            binding.allFilesSummaryText.text = "${filtered.size} $label · ${MediaBrowserLogic.formatSize(totalSize)}"
            if (fingerprint != lastRenderedFingerprint) {
                lastRenderedFingerprint = fingerprint
                when (viewMode) {
                    VIEW_MODE_CHARACTER -> if (selectedChars.isEmpty()) adapter.submitMediaWithGroups(filtered, charGroups) else adapter.submitMedia(filtered)
                    VIEW_MODE_SOURCE -> if (selectedSources.isEmpty()) adapter.submitMediaWithGroups(filtered, sourceGroups) else adapter.submitMedia(filtered)
                    else -> adapter.submitMedia(filtered)
                }
            }

            updateChipStyles()
            AppLog.d("AllFiles", "render: total=${System.currentTimeMillis() - renderStart}ms, filtered=${filtered.size}, fp=$fingerprint")
        }
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
        val partitionCount = 2 // 默认 + COS
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

        // 分区选项：全部 / 默认（普通文件） / COS
        val partitions = listOf("默认" to cachedMedia.size, "COS" to cachedCosMedia.size)
        val totalCount = cachedMedia.size + cachedCosMedia.size
        val flow = MediaPillsHelper.createFlowLayout(requireContext())

        val allPill = MediaPillsHelper.createPill(requireContext(), "全部 ($totalCount)", false, colors)
        allPill.setOnClickListener {
            selectedPartitions.clear()
            selectedSources.clear()
            selectedChars.clear()
            lastRenderedFingerprint = ""
            computeSourceGroups()
        }
        flow.addView(allPill)

        for ((name, count) in partitions) {
            val isActive = when (name) {
                "默认" -> "COS" !in selectedPartitions
                "COS" -> "COS" in selectedPartitions
                else -> false
            }
            val pill = MediaPillsHelper.createPill(requireContext(), "$name ($count)", isActive, colors)
            pill.setOnClickListener {
                when (name) {
                    "默认" -> {
                        selectedPartitions.remove("COS")
                        selectedSources.clear()
                        selectedChars.clear()
                    }
                    "COS" -> {
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

    private fun renderTypePills() {
        val isCosPartition = "COS" in selectedPartitions
        val workingMedia = if (isCosPartition) cachedCosMedia else cachedMedia
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
