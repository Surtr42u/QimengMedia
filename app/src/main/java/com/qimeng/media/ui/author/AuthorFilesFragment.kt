package com.qimeng.media.ui.author

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
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.databinding.FragmentAuthorFilesBinding
import com.qimeng.media.ui.adapter.GroupedMediaAdapter
import com.qimeng.media.ui.browser.FilterConfig
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.ui.browser.MediaRenderHelper
import com.qimeng.media.ui.browser.MediaFilterSheet
import com.qimeng.media.ui.browser.MediaFilterState
import com.qimeng.media.ui.browser.MediaGroupHelper
import com.qimeng.media.ui.browser.MediaPillsHelper
import com.qimeng.media.ui.library.MediaLibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthorFilesFragment : Fragment() {
    private var _binding: FragmentAuthorFilesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var authorId: String = ""
    private var isCosAuthor = false
    private var viewMode = VIEW_MODE_SOURCE
    private var filterType: String? = null
    private var filterState = MediaFilterState()
    private var selectedSources = mutableSetOf<String>()
    private var selectedChars = mutableSetOf<String>()
    private var sourcePillsExpanded = false
    private var charPillsExpanded = false
    private var typePillsExpanded = false
    private val columnsRef = ColumnsRef(DEFAULT_COLUMNS)
    private var cachedMedia = emptyList<MediaFileEntity>()
    private var cachedCosMedia = emptyList<MediaFileEntity>()
    private var cachedCosWorks = emptyList<CosWorkEntity>()
    private var cachedAuthorMediaKeys = emptySet<String>()
    private var cachedCosAuthorMediaKeys = emptySet<String>()
    private var cachedAuthorMedia = emptyList<AuthorMediaCrossRef>()
    private var cachedAuthors = emptyList<AuthorEntity>()
    private var cachedStats = emptyList<ViewStatsEntity>()
    private var cachedTags = emptyList<MediaTagName>()
    private var cachedAllTags = emptyList<TagEntity>()
    private var cachedHistory = emptyList<ViewHistoryEntity>()
    private var cachedSourceGroups: Map<String, List<MediaFileEntity>> = emptyMap()
    private var cachedCharGroups: Map<String, List<MediaFileEntity>> = emptyMap()
    private var lastRenderHash = ""
    private var mediaFingerprint: String = ""
    private var observeStarted: Boolean = false
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var adapter: GroupedMediaAdapter
    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authorId = arguments?.getString(ARG_AUTHOR_ID).orEmpty()
        isCosAuthor = arguments?.getBoolean(ARG_IS_COS) ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAuthorFilesBinding.inflate(inflater, container, false)
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
        binding.authorFilesBackButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        // 药丸滚动时禁用下拉刷新
        scrollListener = ViewTreeObserver.OnScrollChangedListener {
            _binding?.let { binding ->
                binding.authorFilesSwipeRefresh.isEnabled = !binding.authorFilesPillsScroller.canScrollVertically(-1)
            }
        }
        scrollListener?.let { binding.authorFilesPillsScroller.viewTreeObserver.addOnScrollChangedListener(it) }

        // COS 作者默认角色模式，常规作者默认作品模式
        viewMode = if (isCosAuthor) VIEW_MODE_CHARACTER else VIEW_MODE_SOURCE

        setupRecycler()
        setupChips()
        setupActions()
        observeData()
    }

    private fun setupRecycler() {
        gridLayoutManager = PinchZoomHelper.createGridLayoutManager(requireContext(), columnsRef.value, adapter)
        binding.authorFilesRecycler.layoutManager = gridLayoutManager
        binding.authorFilesRecycler.adapter = adapter
        binding.authorFilesRecycler.itemAnimator = null
        binding.authorFilesRecycler.setItemViewCacheSize(20)
        binding.authorFilesRecycler.setRecycledViewPool(RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20)
        })

        PinchZoomHelper.setup(requireContext(), columnsRef, gridLayoutManager, binding.authorFilesRecycler)
    }

    private fun setupChips() {
        binding.chipAuthorSource.setOnClickListener { setViewMode(VIEW_MODE_SOURCE) }
        binding.chipAuthorCharacter.setOnClickListener { setViewMode(VIEW_MODE_CHARACTER) }
        binding.chipAuthorType.setOnClickListener { setViewMode(VIEW_MODE_TYPE) }
    }

    private fun setupActions() {
        binding.authorFilesSwipeRefresh.setOnRefreshListener {
            lastRenderHash = ""
            mediaFingerprint = ""
            observeStarted = false
            observeData()
            binding.authorFilesSwipeRefresh.isRefreshing = false
        }
        binding.authorFilesFilterButton.setOnClickListener {
            MediaFilterSheet.show(requireContext(), filterState, cachedAllTags, FilterConfig.FOR_AUTHOR,
                onApply = { next ->
                    filterState = next
                    lastRenderHash = ""
                    render()
                },
                onAddTag = { name -> viewModel.createTag(name) },
                onDeleteTag = { tagId -> viewModel.deleteTagById(tagId) }
            )
        }
        binding.authorFilesColumnText.setOnClickListener {
            setColumns(if (columnsRef.value >= PinchZoomHelper.MAX_COLUMNS) PinchZoomHelper.MIN_COLUMNS else columnsRef.value + 1)
        }
        setColumns(columnsRef.value)
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    combine(
                        viewModel.allMedia,
                        viewModel.cosMedia,
                        viewModel.cosWorks,
                        viewModel.allStats,
                        viewModel.allMediaTags
                    ) { media, cosMedia, cosWorks, stats, tags ->
                        arrayOf(media, cosMedia, cosWorks, stats, tags)
                    },
                    combine(viewModel.allTags, viewModel.history, viewModel.allAuthorMedia, viewModel.authors, viewModel.mediaForAuthor(authorId)) { allTags, history, authorMedia, authors, authorFiles ->
                        arrayOf(allTags, history, authorMedia, authors, authorFiles)
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
                    val authorFiles = arr[9] as? List<AuthorMediaCrossRef> ?: return@collect

                    cachedMedia = media
                    cachedCosMedia = cosMedia
                    cachedCosWorks = cosWorks
                    cachedStats = stats
                    cachedTags = tags
                    cachedAllTags = allTags
                    cachedHistory = history
                    cachedAuthorMedia = authorMedia
                    cachedAuthors = authors
                    cachedAuthorMediaKeys = authorFiles.map { it.recordKey }.toSet()

                    // COS 作者的文件：用索引 O(1) 查找，替代对全库 cosMedia 的 O(N×M) 嵌套扫描
                    // （旧实现对每个 COS 文件线性扫描整个 authorMedia，且连续 filter 两次，全库数千文件时主线程卡顿）
                    if (isCosAuthor) {
                        val author = authors.find { it.authorId == authorId }
                        val displayName = author?.displayName
                        val index = MediaGroupHelper.CosAuthorIndex.build(authorMedia, authors)
                        cachedCosAuthorMediaKeys = if (displayName != null) {
                            cosMedia.filter { m -> index.authorNameOf(m.recordKey) == displayName }
                                .map { it.recordKey }.toSet()
                        } else {
                            emptySet()
                        }
                    }

                    val fp = "${media.size}|${authorFiles.size}|${cosMedia.size}|${media.firstOrNull()?.recordKey.orEmpty()}"
                    if (fp != mediaFingerprint || !observeStarted) {
                        mediaFingerprint = fp
                        observeStarted = true
                        lastRenderHash = ""
                        render()
                    }
                }
            }
        }
    }

    private fun render() {
        if (_binding == null) return

        // 作者下的文件：常规作者用 cachedAuthorMediaKeys，COS 作者用 cachedCosAuthorMediaKeys
        val authorMedia = if (isCosAuthor) {
            cachedCosMedia.filter { it.recordKey in cachedCosAuthorMediaKeys }
        } else {
            cachedMedia.filter { it.recordKey in cachedAuthorMediaKeys }
        }

        // 类型筛选 + 筛选面板过滤
        val typed = MediaRenderHelper.applyTypeFilter(authorMedia, filterType)
        val stats = cachedStats.associateBy { it.recordKey }
        val tagMap = cachedTags.groupBy { it.recordKey }.mapValues { entry -> entry.value.map { it.name }.toSet() }
        val filtered = MediaBrowserLogic.applyFilter(typed, query = "", filterState, stats, tagMap, cachedHistory)

        // 作品/角色分组（异步计算）
        lifecycleScope.launch {
            val (sourceGroups, charGroups) = computeAuthorGroupsAsync(filtered)
            if (_binding == null) return@launch
            cachedSourceGroups = sourceGroups
            cachedCharGroups = charGroups
            updateAuthorUI(filtered, sourceGroups, charGroups)
        }
    }

    /** 协程内计算：作品分组 + 角色分组（含 baseForChars 计算） */
    private suspend fun computeAuthorGroupsAsync(
        filtered: List<MediaFileEntity>
    ): Pair<Map<String, List<MediaFileEntity>>, Map<String, List<MediaFileEntity>>> {
        val sourceGroups = withContext(Dispatchers.Default) {
            if (isCosAuthor) emptyMap() // COS 作者不需要作品分组（出处=作者）
            else MediaGroupHelper.groupBySource(filtered)
        }
        val charGroups = withContext(Dispatchers.Default) {
            if (viewMode == VIEW_MODE_CHARACTER) {
                val baseForChars = if (selectedSources.isNotEmpty() && !isCosAuthor) {
                    val sourceMediaSet = sourceGroups.filterKeys { it in selectedSources }.values.flatten().toSet()
                    filtered.filter { it in sourceMediaSet }
                } else filtered
                if (isCosAuthor) MediaGroupHelper.groupByCosWork(baseForChars, cachedAuthorMedia, cachedAuthors, cachedCosWorks)
                else MediaGroupHelper.groupByCharacter(baseForChars)
            } else emptyMap()
        }
        return Pair(sourceGroups, charGroups)
    }

    /** 协程外 UI 更新：药丸渲染 + displayed 计算 + fingerprint + adapter 提交 + 空状态 */
    private fun updateAuthorUI(
        filtered: List<MediaFileEntity>,
        sourceGroups: Map<String, List<MediaFileEntity>>,
        charGroups: Map<String, List<MediaFileEntity>>
    ) {
        // 渲染药丸区域
        when (viewMode) {
            VIEW_MODE_SOURCE -> {
                renderSourcePills(sourceGroups)
                selectedSources.retainAll(sourceGroups.keys)
            }
            VIEW_MODE_CHARACTER -> {
                renderCharPills(charGroups)
                selectedChars.retainAll(charGroups.keys)
            }
            VIEW_MODE_TYPE -> renderTypePills(filtered)
            else -> binding.authorFilesPillsScroller.visibility = View.GONE
        }
        if (viewMode != VIEW_MODE_SOURCE) selectedSources.clear()
        if (viewMode != VIEW_MODE_CHARACTER) selectedChars.clear()

        // 计算最终显示列表
        val displayed = MediaRenderHelper.computeDisplayed(
            filtered, sourceGroups, charGroups, selectedSources, selectedChars
        )

        binding.authorFilesSummaryText.text = "${displayed.size} 文件 · ${MediaBrowserLogic.formatSize(displayed.sumOf { it.sizeBytes })}"

        val renderHash = buildString {
            append(viewMode)
            append("|cos=")
            append(isCosAuthor)
            append("|")
            append(displayed.size)
            append("|")
            append(displayed.firstOrNull()?.recordKey.orEmpty())
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

        binding.authorFilesEmptyText.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        binding.authorFilesRecycler.visibility = if (displayed.isEmpty()) View.GONE else View.VISIBLE
        updateChipStyles()
    }

    private fun renderSourcePills(sourceGroups: Map<String, List<MediaFileEntity>>) {
        MediaPillsHelper.renderSourcePills(
            requireContext(),
            binding.authorFilesPillsWrapper,
            binding.authorFilesPillsScroller,
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
            binding.authorFilesPillsWrapper,
            binding.authorFilesPillsScroller,
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

    private fun renderTypePills(workingMedia: List<MediaFileEntity>) {
        MediaPillsHelper.renderTypePills(
            requireContext(),
            binding.authorFilesPillsWrapper,
            binding.authorFilesPillsScroller,
            workingMedia,
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

    private fun updateChipStyles() {
        val colors = ThemeHelper.resolve(requireContext())
        val sourceCount = cachedSourceGroups.size
        val charCount = cachedCharGroups.size
        val typeCount = 3 // 图片 + 视频 + 动图

        // COS 作者隐藏作品芯片
        binding.chipAuthorSource.visibility = if (isCosAuthor) View.GONE else View.VISIBLE

        val chips = mutableListOf<Triple<TextView, String, String>>()
        if (!isCosAuthor) {
            chips.add(Triple(binding.chipAuthorSource, VIEW_MODE_SOURCE, "作品 ($sourceCount)"))
        }
        chips.add(Triple(binding.chipAuthorCharacter, VIEW_MODE_CHARACTER, "角色 ($charCount)"))
        chips.add(Triple(binding.chipAuthorType, VIEW_MODE_TYPE, "类型 ($typeCount)"))

        chips.forEach { (chip, mode, label) ->
            val active = viewMode == mode
            chip.text = label
            chip.setBackgroundResource(if (active) R.drawable.bg_capsule_primary else 0)
            chip.setTextColor(if (active) colors.bg else colors.textSecondary)
        }
    }

    private fun setViewMode(mode: String) {
        // COS 作者不允许切到作品模式
        if (isCosAuthor && mode == VIEW_MODE_SOURCE) return

        if (mode == viewMode) {
            when (mode) {
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
        if (mode == VIEW_MODE_SOURCE) sourcePillsExpanded = false
        if (mode == VIEW_MODE_CHARACTER) charPillsExpanded = false
        if (mode == VIEW_MODE_TYPE) typePillsExpanded = false
        lastRenderHash = ""
        updateChipStyles()
        render()
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
        binding.authorFilesColumnText.setImageResource(iconRes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scrollListener?.let { _binding?.authorFilesPillsScroller?.viewTreeObserver?.removeOnScrollChangedListener(it) }
        scrollListener = null
        _binding = null
    }

    companion object {
        const val DEFAULT_COLUMNS = 3
        private const val ARG_AUTHOR_ID = "authorId"
        private const val ARG_IS_COS = "isCos"
        private const val VIEW_MODE_SOURCE = "source"
        private const val VIEW_MODE_CHARACTER = "character"
        private const val VIEW_MODE_TYPE = "type"

        fun newInstance(authorId: String, isCos: Boolean = false) = AuthorFilesFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_AUTHOR_ID, authorId)
                putBoolean(ARG_IS_COS, isCos)
            }
        }
    }
}
