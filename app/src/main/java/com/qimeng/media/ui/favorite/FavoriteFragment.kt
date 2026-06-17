package com.qimeng.media.ui.favorite

import android.os.Bundle
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import com.qimeng.media.databinding.FragmentFavoriteBinding
import com.qimeng.media.ui.adapter.GroupedMediaAdapter
import com.qimeng.media.ui.browser.MediaGroupHelper
import com.qimeng.media.ui.browser.MediaRenderHelper
import com.qimeng.media.ui.browser.MediaPillsHelper
import com.qimeng.media.ui.widget.PinchZoomHelper
import com.qimeng.media.ui.widget.ColumnsRef
import com.qimeng.media.ui.library.MediaLibraryViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class FavoriteFragment : Fragment() {
    private var _binding: FragmentFavoriteBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var cachedMedia = emptyList<MediaFileEntity>()
    private var cachedFavoriteMedia = emptyList<MediaFileEntity>()
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
        _binding = FragmentFavoriteBinding.inflate(inflater, container, false)
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
        binding.favoriteBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.favoriteSwipeRefresh.setOnRefreshListener {
            binding.favoriteSwipeRefresh.isRefreshing = false
        }
        scrollListener = ViewTreeObserver.OnScrollChangedListener {
            _binding?.let { binding ->
                binding.favoriteSwipeRefresh.isEnabled = !binding.favoritePillsScroller.canScrollVertically(-1)
            }
        }
        scrollListener?.let { binding.favoritePillsScroller.viewTreeObserver.addOnScrollChangedListener(it) }

        setupRecycler()
        setupChips()
        observeData()
    }

    private fun setupRecycler() {
        gridLayoutManager = PinchZoomHelper.createGridLayoutManager(requireContext(), columnsRef.value, adapter)
        binding.favoriteRecycler.layoutManager = gridLayoutManager
        binding.favoriteRecycler.adapter = adapter
        binding.favoriteRecycler.itemAnimator = null
        binding.favoriteRecycler.setItemViewCacheSize(20)
        binding.favoriteRecycler.setRecycledViewPool(RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20)
        })


    }

    private fun setupChips() {
        binding.favoriteChipAll.setOnClickListener { setViewMode(VIEW_MODE_PARTITION) }
        binding.favoriteChipSource.setOnClickListener { setViewMode(VIEW_MODE_SOURCE) }
        binding.favoriteChipCharacter.setOnClickListener { setViewMode(VIEW_MODE_CHARACTER) }
        binding.favoriteChipType.setOnClickListener { setViewMode(VIEW_MODE_TYPE) }
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

                    val favPrefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val favKeys = favPrefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

                    val isCosPartition = selectedPartitions == setOf("COS")
                    val isAllPartition = selectedPartitions.containsAll(listOf("常规", "COS"))
                    val workingMedia = when {
                        isCosPartition -> cachedCosMedia
                        isAllPartition -> cachedMedia + cachedCosMedia
                        else -> cachedMedia
                    }
                    cachedFavoriteMedia = workingMedia.filter { it.recordKey in favKeys }
                    computeSourceGroups()
                }
            }
        }
    }

    private fun computeSourceGroups() {
        // 出处分组现在在render()中基于typed计算，这里只需触发render
        render()
    }

    private fun render() {
        if (_binding == null) return

        val isCosPartition = selectedPartitions == setOf("COS")
        val isAllPartition = selectedPartitions.containsAll(listOf("常规", "COS"))

        val typed = MediaRenderHelper.applyTypeFilter(cachedFavoriteMedia, filterType)

        // 分区→类型→出处→角色 逐层联动：出处和角色分组都基于类型筛选后的文件
        lifecycleScope.launch {
            val (sourceGroups, charGroups) = computeFavoriteGroupsAsync(typed, isCosPartition, isAllPartition)
            if (_binding == null) return@launch
            cachedSourceGroups = sourceGroups
            cachedCharGroups = charGroups
            updateFavoriteUI(typed, sourceGroups, charGroups, isCosPartition, isAllPartition)
        }
    }

    /** 协程内计算：出处分组 + 角色分组（含 baseForChars + mergeGroups） */
    private suspend fun computeFavoriteGroupsAsync(
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
        val charGroups = if (viewMode == VIEW_MODE_CHARACTER) {
            withContext(Dispatchers.Default) {
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
            }
        } else emptyMap()
        return Pair(sourceGroups, charGroups)
    }

    /** 协程外 UI 更新：药丸渲染 + displayed 计算 + fingerprint + adapter 提交 + 空状态 */
    private fun updateFavoriteUI(
        typed: List<MediaFileEntity>,
        sourceGroups: Map<String, List<MediaFileEntity>>,
        charGroups: Map<String, List<MediaFileEntity>>,
        isCosPartition: Boolean,
        isAllPartition: Boolean
    ) {
        when (viewMode) {
            VIEW_MODE_PARTITION -> renderPartitionPills()
            VIEW_MODE_SOURCE -> renderSourcePills(sourceGroups)
            VIEW_MODE_CHARACTER -> {
                // 注意：COS 分区下 renderCosWorkPills 传 typed（而非 charGroups），这是现有行为
                if (isCosPartition) renderCosWorkPills(typed) else renderCharPills(charGroups)
                selectedChars.retainAll(charGroups.keys)
            }
            VIEW_MODE_TYPE -> renderTypePills()
            else -> binding.favoritePillsScroller.visibility = View.GONE
        }
        if (viewMode != VIEW_MODE_SOURCE) selectedSources.clear()
        if (viewMode != VIEW_MODE_CHARACTER) selectedChars.clear()

        val displayed = MediaRenderHelper.computeDisplayed(
            typed, sourceGroups, charGroups, selectedSources, selectedChars
        )

        val label = when {
            isCosPartition -> "COS"
            isAllPartition -> "文件"
            else -> "常规"
        }
        binding.favoriteCount.text = "${displayed.size} $label"

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

        updateFavoriteEmptyState(displayed, isCosPartition)
        updateChipStyles()
    }

    private fun updateFavoriteEmptyState(displayed: List<MediaFileEntity>, isCosPartition: Boolean) {
        binding.favoriteEmptyText.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        binding.favoriteRecycler.visibility = if (displayed.isEmpty()) View.GONE else View.VISIBLE
        if (displayed.isEmpty() && isCosPartition) {
            binding.favoriteEmptyText.text = "没有COS收藏"
        } else if (displayed.isEmpty()) {
            binding.favoriteEmptyText.text = "还没有收藏\n在详情页点击收藏按钮添加"
        }
    }

    private fun renderSourcePills(sourceGroups: Map<String, List<MediaFileEntity>>) {
        MediaPillsHelper.renderSourcePills(
            requireContext(),
            binding.favoritePillsWrapper,
            binding.favoritePillsScroller,
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

    private fun renderCosWorkPills(media: List<MediaFileEntity>) {
        val workGroups = MediaGroupHelper.groupByCosWork(media, cachedAuthorMedia, cachedAuthors, cachedCosWorks)
        MediaPillsHelper.renderCosWorkPills(
            requireContext(),
            binding.favoritePillsWrapper,
            binding.favoritePillsScroller,
            workGroups,
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

    private fun renderCharPills(charGroups: Map<String, List<MediaFileEntity>>) {
        MediaPillsHelper.renderCharPills(
            requireContext(),
            binding.favoritePillsWrapper,
            binding.favoritePillsScroller,
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
        MediaPillsHelper.renderTypePills(
            requireContext(),
            binding.favoritePillsWrapper,
            binding.favoritePillsScroller,
            workingMedia,
            filterType,
            typePillsExpanded,
            onFilterChanged = { filterType = it },
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
            Triple(binding.favoriteChipAll, VIEW_MODE_PARTITION, "分区 ($partitionCount)"),
            Triple(binding.favoriteChipSource, VIEW_MODE_SOURCE, "作品 ($sourceCount)"),
            Triple(binding.favoriteChipCharacter, VIEW_MODE_CHARACTER, "角色 ($charCount)"),
            Triple(binding.favoriteChipType, VIEW_MODE_TYPE, "类型 ($typeCount)")
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
        val wrapper = binding.favoritePillsWrapper
        val scroller = binding.favoritePillsScroller
        wrapper.removeAllViews()

        scroller.visibility = View.VISIBLE
        val colors = ThemeHelper.resolve(requireContext())

        if (!partitionPillsExpanded) {
            scroller.visibility = View.GONE
            return
        }

        // 分区选项：全部 / 常规 / COS
        val favPrefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val favKeys = favPrefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        val defaultCount = cachedMedia.filter { it.recordKey in favKeys }.size
        val cosCount = cachedCosMedia.filter { it.recordKey in favKeys }.size
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
            val favPrefs2 = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val favKeys2 = favPrefs2?.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
            cachedFavoriteMedia = (cachedMedia + cachedCosMedia).filter { it.recordKey in favKeys2 }
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
                lastRenderHash = ""
                // 重新计算收藏列表
                val favPrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val favKeys = favPrefs?.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
                val workingMedia = when (name) {
                    "COS" -> cachedCosMedia
                    else -> cachedMedia
                }
                cachedFavoriteMedia = workingMedia.filter { it.recordKey in favKeys }
                computeSourceGroups()
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
        scrollListener?.let { _binding?.favoritePillsScroller?.viewTreeObserver?.removeOnScrollChangedListener(it) }
        scrollListener = null
        _binding = null
    }

    companion object {
        private const val PREFS_NAME = "media_detail_prefs"
        private const val KEY_FAVORITES = "favorite_record_keys"
        const val DEFAULT_COLUMNS = 3
        const val VIEW_MODE_PARTITION = "partition"
        const val VIEW_MODE_SOURCE = "source"
        const val VIEW_MODE_CHARACTER = "character"
        const val VIEW_MODE_TYPE = "type"
    }
}
