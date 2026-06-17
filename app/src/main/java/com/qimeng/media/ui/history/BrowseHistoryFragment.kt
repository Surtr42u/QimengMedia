package com.qimeng.media.ui.history

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
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.data.db.model.MediaTagName
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.databinding.FragmentBrowseHistoryBinding
import com.qimeng.media.ui.adapter.GroupedMediaAdapter
import com.qimeng.media.ui.browser.MediaGroupHelper
import com.qimeng.media.ui.browser.MediaPillsHelper
import com.qimeng.media.ui.browser.MediaRenderHelper
import com.qimeng.media.ui.widget.FlowLayout
import com.qimeng.media.ui.library.MediaLibraryViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseHistoryFragment : Fragment() {
    private var _binding: FragmentBrowseHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var cachedHistory = emptyList<ViewHistoryEntity>()
    private var cachedMedia = emptyList<MediaFileEntity>()
    private var cachedHistoryMedia = emptyList<MediaFileEntity>()
    private var cachedSourceGroups: Map<String, List<MediaFileEntity>> = emptyMap()
    private var cachedCharGroups: Map<String, List<MediaFileEntity>> = emptyMap()
    private var viewMode = ""
    private var filterType: String? = null
    private var selectedSources = mutableSetOf<String>()
    private var selectedChars = mutableSetOf<String>()
    private var selectedPartitions = mutableSetOf("常规", "COS")
    private var partitionPillsExpanded = true
    private var sourcePillsExpanded = true
    private var charPillsExpanded = true
    private var typePillsExpanded = true
    private var lastRenderHash = ""
    private var cachedCosMedia = emptyList<MediaFileEntity>()
    private var cachedCosWorks = emptyList<CosWorkEntity>()
    private var cachedAuthorMedia = emptyList<AuthorMediaCrossRef>()
    private var cachedAuthors = emptyList<AuthorEntity>()
    private var cachedStats = emptyList<ViewStatsEntity>()
    private var cachedTags = emptyList<MediaTagName>()
    private var cachedAllTags = emptyList<TagEntity>()
    private val columnsRef = ColumnsRef(DEFAULT_COLUMNS)
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var adapter: GroupedMediaAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowseHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = GroupedMediaAdapter(
            onItemClick = { media, source ->
                (requireActivity() as? MainActivity)?.showDetailFragment(media.recordKey, source.map { it.recordKey })
            },
            lifecycleScope = viewLifecycleOwner.lifecycleScope
        )
        // 浏览历史按浏览时间分组
        adapter.viewedAtMap = cachedHistory.associate { it.recordKey to it.openedAtMillis }
        binding.historyBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.historyClearBtn.setOnClickListener { viewModel.clearHistory() }
        binding.historySwipeRefresh.setOnRefreshListener {
            binding.historySwipeRefresh.isRefreshing = false
        }
        scrollListener = ViewTreeObserver.OnScrollChangedListener {
            _binding?.let { binding ->
                binding.historySwipeRefresh.isEnabled = !binding.historyPillsScroller.canScrollVertically(-1)
            }
        }
        scrollListener?.let { binding.historyPillsScroller.viewTreeObserver.addOnScrollChangedListener(it) }

        setupRecycler()
        setupChips()
        observeData()
    }

    private fun setupRecycler() {
        gridLayoutManager = PinchZoomHelper.createGridLayoutManager(requireContext(), columnsRef.value, adapter)
        binding.historyRecycler.layoutManager = gridLayoutManager
        binding.historyRecycler.adapter = adapter
        binding.historyRecycler.itemAnimator = null
        binding.historyRecycler.setItemViewCacheSize(20)
        binding.historyRecycler.setRecycledViewPool(RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20)
        })

        // 双指缩放列数（与全部页一致）
        PinchZoomHelper.setup(requireContext(), columnsRef, gridLayoutManager, binding.historyRecycler)
    }

    private fun setupChips() {
        binding.historyChipAll.setOnClickListener { setViewMode(VIEW_MODE_PARTITION) }
        binding.historyChipSource.setOnClickListener { setViewMode(VIEW_MODE_SOURCE) }
        binding.historyChipCharacter.setOnClickListener { setViewMode(VIEW_MODE_CHARACTER) }
        binding.historyChipType.setOnClickListener { setViewMode(VIEW_MODE_TYPE) }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                    // 同步浏览时间映射到 adapter
                    adapter.viewedAtMap = history.associate { it.recordKey to it.openedAtMillis }
                    computeAndRender()
                }
            }
        }
    }

    private fun computeAndRender() {
        val historyKeys = cachedHistory.map { it.recordKey }.toSet()
        if (historyKeys.isEmpty()) {
            cachedHistoryMedia = emptyList()
            cachedSourceGroups = emptyMap()
            cachedCharGroups = emptyMap()
            render()
            return
        }
        val isCosPartition = selectedPartitions == setOf("COS")
        val isAllPartition = selectedPartitions.containsAll(listOf("常规", "COS"))
        val workingMedia = when {
            isCosPartition -> cachedCosMedia
            isAllPartition -> cachedMedia + cachedCosMedia
            else -> cachedMedia
        }
        // 按浏览时间（openedAtMillis）倒序排列，而非文件修改时间
        val openedAtMap = cachedHistory.associate { it.recordKey to it.openedAtMillis }
        val historyMedia = workingMedia.filter { it.recordKey in historyKeys }
            .sortedByDescending { openedAtMap[it.recordKey] ?: 0L }
        if (historyMedia.isEmpty()) {
            cachedHistoryMedia = emptyList()
            cachedSourceGroups = emptyMap()
            cachedCharGroups = emptyMap()
            render()
            return
        }

        cachedHistoryMedia = historyMedia
        computeSourceGroups()
    }

    private fun computeSourceGroups() {
        // 出处分组现在在render()中基于typed计算，这里只需触发render
        render()
    }

    private fun render() {
        if (_binding == null) return

        val isCosPartition = selectedPartitions == setOf("COS")
        val isAllPartition = selectedPartitions.containsAll(listOf("常规", "COS"))

        val typed = MediaRenderHelper.applyTypeFilter(cachedHistoryMedia, filterType)

        // 分区→类型→出处→角色 逐层联动：出处和角色分组都基于类型筛选后的文件
        lifecycleScope.launch {
            val (sourceGroups, charGroups) = computeHistoryGroupsAsync(typed, isCosPartition, isAllPartition)
            if (_binding == null) return@launch
            cachedSourceGroups = sourceGroups
            cachedCharGroups = charGroups
            updateHistoryUI(typed, sourceGroups, charGroups, isCosPartition)
        }
    }

    /** 协程内计算：出处分组 + 角色分组（含 baseForChars + mergeGroups） */
    private suspend fun computeHistoryGroupsAsync(
        typed: List<MediaFileEntity>,
        isCosPartition: Boolean,
        isAllPartition: Boolean
    ): Pair<Map<String, List<MediaFileEntity>>, Map<String, List<MediaFileEntity>>> {
        val sourceGroups = withContext(Dispatchers.Default) {
            if (isCosPartition) MediaGroupHelper.groupByCosAuthor(typed, cachedAuthorMedia, cachedAuthors)
            else if (isAllPartition) {
                val regular = MediaGroupHelper.groupBySource(typed.filter { !it.isCosFile })
                val cos = MediaGroupHelper.groupByCosAuthor(typed.filter { it.isCosFile }, cachedAuthorMedia, cachedAuthors)
                mergeGroups(regular, cos)
            } else MediaGroupHelper.groupBySource(typed)
        }
        val charGroups = withContext(Dispatchers.Default) {
            if (viewMode == VIEW_MODE_CHARACTER) {
                val baseForChars = if (selectedSources.isNotEmpty()) {
                    val sourceMediaSet = sourceGroups.filterKeys { it in selectedSources }.values.flatten().toSet()
                    typed.filter { it in sourceMediaSet }
                } else typed
                if (isCosPartition) MediaGroupHelper.groupByCosWork(baseForChars, cachedAuthorMedia, cachedAuthors, cachedCosWorks)
                else if (isAllPartition) {
                    val regular = MediaGroupHelper.groupByCharacter(baseForChars.filter { !it.isCosFile })
                    val cos = MediaGroupHelper.groupByCosWork(baseForChars.filter { it.isCosFile }, cachedAuthorMedia, cachedAuthors, cachedCosWorks)
                    mergeGroups(regular, cos)
                } else MediaGroupHelper.groupByCharacter(baseForChars)
            } else emptyMap()
        }
        return Pair(sourceGroups, charGroups)
    }

    /** 协程外 UI 更新：药丸渲染 + displayed 计算 + fingerprint + adapter 提交 + 空状态 */
    private fun updateHistoryUI(
        typed: List<MediaFileEntity>,
        sourceGroups: Map<String, List<MediaFileEntity>>,
        charGroups: Map<String, List<MediaFileEntity>>,
        isCosPartition: Boolean
    ) {
        when (viewMode) {
            VIEW_MODE_PARTITION -> renderPartitionPills()
            VIEW_MODE_SOURCE -> {
                renderSourcePills(sourceGroups)
                selectedSources.retainAll(sourceGroups.keys)
            }
            VIEW_MODE_CHARACTER -> {
                renderCharPills(charGroups)
                selectedChars.retainAll(charGroups.keys)
            }
            VIEW_MODE_TYPE -> renderTypePills()
            else -> binding.historyPillsScroller.visibility = View.GONE
        }
        if (viewMode != VIEW_MODE_SOURCE) selectedSources.clear()
        if (viewMode != VIEW_MODE_CHARACTER) selectedChars.clear()

        val displayed = MediaRenderHelper.computeDisplayed(
            typed, sourceGroups, charGroups, selectedSources, selectedChars
        )

        binding.historyCount.text = "${displayed.size} 文件"

        val renderHash = MediaRenderHelper.buildFingerprint(
            MediaRenderHelper.FingerprintParams(
                viewMode = viewMode,
                partitions = selectedPartitions.toSet(),
                displayed = displayed,
                selectedSources = selectedSources.toSet(),
                selectedChars = selectedChars.toSet(),
                filterType = filterType,
                typePillsExpanded = typePillsExpanded,
                sourceGroups = sourceGroups,
                charGroups = charGroups
            )
        )
        if (renderHash != lastRenderHash) {
            lastRenderHash = renderHash
            when {
                viewMode == VIEW_MODE_CHARACTER && selectedChars.isEmpty() ->
                    adapter.submitMediaWithGroups(displayed, charGroups)
                viewMode == VIEW_MODE_SOURCE && selectedSources.isEmpty() ->
                    adapter.submitMediaWithGroups(displayed, sourceGroups)
                else ->
                    adapter.submitMedia(displayed)
            }
        }

        updateHistoryEmptyState(displayed, isCosPartition)
        updateChipStyles()
    }

    private fun updateHistoryEmptyState(displayed: List<MediaFileEntity>, isCosPartition: Boolean) {
        binding.historyEmptyText.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        binding.historyRecycler.visibility = if (displayed.isEmpty()) View.GONE else View.VISIBLE
        if (displayed.isEmpty() && isCosPartition) {
            binding.historyEmptyText.text = "没有COS浏览记录"
        } else if (displayed.isEmpty()) {
            binding.historyEmptyText.text = "没有浏览记录"
        }
    }

    private fun renderSourcePills(sourceGroups: Map<String, List<MediaFileEntity>>) {
        MediaPillsHelper.renderSourcePills(
            requireContext(),
            binding.historyPillsWrapper,
            binding.historyPillsScroller,
            sourceGroups,
            selectedSources,
            sourcePillsExpanded,
            onPillClick = {
                lastRenderHash = ""
                render()
            },
            onCollapse = {
                sourcePillsExpanded = false
                lastRenderHash = ""
                render()
            }
        )
    }

    private fun renderCharPills(charGroups: Map<String, List<MediaFileEntity>>) {
        MediaPillsHelper.renderCharPills(
            requireContext(),
            binding.historyPillsWrapper,
            binding.historyPillsScroller,
            charGroups,
            selectedChars,
            charPillsExpanded,
            onPillClick = {
                lastRenderHash = ""
                render()
            },
            onCollapse = {
                charPillsExpanded = false
                lastRenderHash = ""
                render()
            }
        )
    }

    private fun renderTypePills() {
        val isCosPartition = selectedPartitions == setOf("COS")
        val isAllPartition = selectedPartitions.containsAll(listOf("常规", "COS"))
        val workingMedia = when {
            isCosPartition -> cachedCosMedia
            isAllPartition -> cachedMedia + cachedCosMedia
            else -> cachedMedia
        }
        val historyKeys = cachedHistory.map { it.recordKey }.toSet()
        val historyWorkingMedia = workingMedia.filter { it.recordKey in historyKeys }
        MediaPillsHelper.renderTypePills(
            requireContext(),
            binding.historyPillsWrapper,
            binding.historyPillsScroller,
            historyWorkingMedia,
            filterType,
            typePillsExpanded,
            onFilterChanged = { type -> filterType = type },
            onPillClick = {
                lastRenderHash = ""
                render()
            },
            onCollapse = {
                typePillsExpanded = false
                lastRenderHash = ""
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

    private fun updateChipStyles() {
        val colors = ThemeHelper.resolve(requireContext())
        val partitionCount = 2 // 常规 + COS
        val sourceCount = cachedSourceGroups.size
        val charCount = cachedCharGroups.size
        val typeCount = 3 // 图片 + 视频 + 动图
        listOf(
            Triple(binding.historyChipAll, VIEW_MODE_PARTITION, "分区 ($partitionCount)"),
            Triple(binding.historyChipSource, VIEW_MODE_SOURCE, "作品 ($sourceCount)"),
            Triple(binding.historyChipCharacter, VIEW_MODE_CHARACTER, "角色 ($charCount)"),
            Triple(binding.historyChipType, VIEW_MODE_TYPE, "类型 ($typeCount)")
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
            lastRenderHash = ""
            updateChipStyles()
            render()
            return
        }
        viewMode = mode
        selectedSources.clear()
        selectedChars.clear()
        if (mode == VIEW_MODE_PARTITION) partitionPillsExpanded = true
        if (mode == VIEW_MODE_SOURCE) sourcePillsExpanded = true
        if (mode == VIEW_MODE_CHARACTER) charPillsExpanded = true
        if (mode == VIEW_MODE_TYPE) typePillsExpanded = true
        lastRenderHash = ""
        updateChipStyles()
        render()
    }

    private fun renderPartitionPills() {
        val wrapper = binding.historyPillsWrapper
        val scroller = binding.historyPillsScroller
        wrapper.removeAllViews()

        scroller.visibility = View.VISIBLE
        val colors = ThemeHelper.resolve(requireContext())

        if (!partitionPillsExpanded) {
            scroller.visibility = View.GONE
            return
        }

        // 分区选项：全部 / 常规 / COS
        val historyKeys = cachedHistory.map { it.recordKey }.toSet()
        val defaultCount = cachedMedia.filter { it.recordKey in historyKeys }.size
        val cosCount = cachedCosMedia.filter { it.recordKey in historyKeys }.size
        val totalCount = defaultCount + cosCount
        val partitions = listOf("常规" to defaultCount, "COS" to cosCount)
        val flow = MediaPillsHelper.createFlowLayout(requireContext())

        val isAllSelected = selectedPartitions.containsAll(listOf("常规", "COS"))
        val allPill = MediaPillsHelper.createPill(requireContext(), "全部 ($totalCount)", isAllSelected, colors)
        allPill.setOnClickListener {
            selectedPartitions.clear()
            selectedPartitions.addAll(listOf("常规", "COS"))
            selectedSources.clear()
            selectedChars.clear()
            lastRenderHash = ""
            computeAndRender()
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
                lastRenderHash = ""
                computeAndRender()
            }
            flow.addView(pill)
        }

        val collapsePill = MediaPillsHelper.createPill(requireContext(), "收起 ▲", false, colors)
        collapsePill.setOnClickListener {
            partitionPillsExpanded = false
            lastRenderHash = ""
            render()
        }
        flow.addView(collapsePill)

        wrapper.addView(flow)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scrollListener?.let { _binding?.historyPillsScroller?.viewTreeObserver?.removeOnScrollChangedListener(it) }
        scrollListener = null
        _binding = null
    }

    companion object {
        const val DEFAULT_COLUMNS = 3
        const val VIEW_MODE_PARTITION = "partition"
        const val VIEW_MODE_SOURCE = "source"
        const val VIEW_MODE_CHARACTER = "character"
        const val VIEW_MODE_TYPE = "type"
    }
}
