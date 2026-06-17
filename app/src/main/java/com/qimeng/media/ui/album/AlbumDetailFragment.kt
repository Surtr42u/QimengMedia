package com.qimeng.media.ui.album

import android.os.Bundle
import android.view.LayoutInflater
import com.qimeng.media.ui.widget.PinchZoomHelper
import com.qimeng.media.ui.widget.ColumnsRef
import android.view.View
import android.view.ViewGroup
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
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.data.db.model.MediaTagName
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.databinding.FragmentAlbumDetailBinding
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

class AlbumDetailFragment : Fragment() {
    private var _binding: FragmentAlbumDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var albumName: String? = null
    private var isCosAlbum = false
    private var filterState = MediaFilterState()
    private var filterType: String? = null
    private val columnsRef = ColumnsRef(DEFAULT_COLUMNS)
    private var viewMode = VIEW_MODE_CHARACTER
    private var selectedChars = mutableSetOf<String>()
    private var charPillsExpanded = false
    private var typePillsExpanded = false
    private var cachedCharGroups: Map<String, List<MediaFileEntity>> = emptyMap()
    private var cachedAlbumMedia = emptyList<MediaFileEntity>()
    private var cachedCosMedia = emptyList<MediaFileEntity>()
    private var cachedCosWorks = emptyList<CosWorkEntity>()
    private var cachedAuthorMedia = emptyList<AuthorMediaCrossRef>()
    private var cachedAuthors = emptyList<AuthorEntity>()
    private var cachedStats = emptyList<ViewStatsEntity>()
    private var cachedTags = emptyList<MediaTagName>()
    private var cachedAllTags = emptyList<TagEntity>()
    private var cachedHistory = emptyList<ViewHistoryEntity>()
    private var lastRenderedFingerprint: String = ""
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var adapter: GroupedMediaAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albumName = arguments?.getString(ARG_ALBUM_NAME)
        isCosAlbum = arguments?.getBoolean(ARG_IS_COS) ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAlbumDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val name = albumName ?: run { parentFragmentManager.popBackStack(); return }
        adapter = GroupedMediaAdapter(
            onItemClick = { media, source ->
                (requireActivity() as? MainActivity)?.showDetailFragment(media.recordKey, source.map { it.recordKey })
            },
            lifecycleScope = viewLifecycleOwner.lifecycleScope
        )
        binding.albumDetailTitle.text = name
        binding.albumDetailBack.setOnClickListener { parentFragmentManager.popBackStack() }

        setupRecycler()
        setupChips()
        setupActions()
        observeData(name)
    }

    private fun setupRecycler() {
        gridLayoutManager = PinchZoomHelper.createGridLayoutManager(requireContext(), columnsRef.value, adapter)
        binding.albumDetailRecycler.layoutManager = gridLayoutManager
        binding.albumDetailRecycler.adapter = adapter
        binding.albumDetailRecycler.itemAnimator = null
        binding.albumDetailRecycler.setItemViewCacheSize(20)
        binding.albumDetailRecycler.setRecycledViewPool(RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20)
        })

        PinchZoomHelper.setup(requireContext(), columnsRef, gridLayoutManager, binding.albumDetailRecycler)
    }

    private fun setupChips() {
        binding.albumDetailChipCharacter.setOnClickListener { setViewMode(VIEW_MODE_CHARACTER) }
        binding.albumDetailChipType.setOnClickListener { setViewMode(VIEW_MODE_TYPE) }
    }

    private fun setupActions() {
        binding.albumDetailFilterButton.setOnClickListener {
            MediaFilterSheet.show(requireContext(), filterState, cachedAllTags, FilterConfig.FOR_ALBUM,
                onApply = { next ->
                    filterState = next
                    lastRenderedFingerprint = ""
                    render()
                },
                onAddTag = { viewModel.createTag(it) },
                onDeleteTag = { viewModel.deleteTagById(it) }
            )
        }
        binding.albumDetailColumnButton.setOnClickListener {
            setColumns(if (columnsRef.value >= PinchZoomHelper.MAX_COLUMNS) PinchZoomHelper.MIN_COLUMNS else columnsRef.value + 1)
        }
        setColumns(columnsRef.value)
    }

    private fun observeData(name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    combine(
                        viewModel.nonCosMedia,
                        viewModel.cosMedia,
                        viewModel.cosWorks,
                        viewModel.allStats,
                        viewModel.allMediaTags
                    ) { nonCosMedia, cosMedia, cosWorks, stats, tags ->
                        arrayOf(nonCosMedia, cosMedia, cosWorks, stats, tags)
                    },
                    combine(
                        viewModel.allTags,
                        viewModel.history,
                        viewModel.allAuthorMedia,
                        viewModel.authors
                    ) { allTags, history, authorMedia, authors ->
                        arrayOf(allTags, history, authorMedia, authors)
                    }
                ) { arr1, arr2 ->
                    arr1 + arr2
                }.collect @Suppress("UNCHECKED_CAST") { arr ->
                    val nonCosMedia = arr[0] as? List<MediaFileEntity> ?: return@collect
                    val cosMedia = arr[1] as? List<MediaFileEntity> ?: return@collect
                    val cosWorks = arr[2] as? List<CosWorkEntity> ?: return@collect
                    val stats = arr[3] as? List<ViewStatsEntity> ?: return@collect
                    val tags = arr[4] as? List<MediaTagName> ?: return@collect
                    val allTags = arr[5] as? List<TagEntity> ?: return@collect
                    val history = arr[6] as? List<ViewHistoryEntity> ?: return@collect
                    val authorMedia = arr[7] as? List<AuthorMediaCrossRef> ?: return@collect
                    val authors = arr[8] as? List<AuthorEntity> ?: return@collect

                    cachedStats = stats
                    cachedTags = tags
                    cachedAllTags = allTags
                    cachedHistory = history
                    cachedCosMedia = cosMedia
                    cachedCosWorks = cosWorks
                    cachedAuthorMedia = authorMedia
                    cachedAuthors = authors

                    if (isCosAlbum) {
                        // COS 相册：按作者名过滤
                        cachedAlbumMedia = cosMedia.filter { mediaFile ->
                            val authorName = MediaGroupHelper.findCosAuthorForMedia(mediaFile, authorMedia, authors)
                            authorName == name
                        }
                    } else {
                        // 常规相册：按出处名过滤
                        cachedAlbumMedia = if (name == "其他") {
                            nonCosMedia.filter { m -> SourceMatcher.match(m.fileName) == null }
                        } else {
                            nonCosMedia.filter { m -> SourceMatcher.match(m.fileName) == name }
                        }
                    }

                    lastRenderedFingerprint = ""
                    render()
                }
            }
        }
    }

    private fun render() {
        if (_binding == null) return
        val stats = cachedStats.associateBy { it.recordKey }
        val tagMap = cachedTags.groupBy { it.recordKey }.mapValues { e -> e.value.map { it.name }.toSet() }

        lifecycleScope.launch {
            val (filtered, charGroups) = computeAlbumGroupsAsync(stats, tagMap)
            if (_binding == null) return@launch
            cachedCharGroups = charGroups
            updateAlbumUI(filtered, charGroups)
        }
    }

    /** 协程内计算：类型筛选 + applyFilter + 角色分组 */
    private suspend fun computeAlbumGroupsAsync(
        stats: Map<String, ViewStatsEntity>,
        tagMap: Map<String, Set<String>>
    ): Pair<List<MediaFileEntity>, Map<String, List<MediaFileEntity>>> {
        val filtered = withContext(Dispatchers.Default) {
            val typed = MediaRenderHelper.applyTypeFilter(cachedAlbumMedia, filterType)
            MediaBrowserLogic.applyFilter(typed, query = "", filterState, stats, tagMap, cachedHistory)
        }
        val charGroups = withContext(Dispatchers.Default) {
            if (viewMode == VIEW_MODE_CHARACTER) {
                if (isCosAlbum) MediaGroupHelper.groupByCosWork(filtered, cachedAuthorMedia, cachedAuthors, cachedCosWorks)
                else MediaGroupHelper.groupByCharacter(filtered)
            } else emptyMap()
        }
        return Pair(filtered, charGroups)
    }

    /** 协程外 UI 更新：药丸渲染 + displayed 计算 + fingerprint + adapter 提交 + 空状态 */
    private fun updateAlbumUI(
        filtered: List<MediaFileEntity>,
        charGroups: Map<String, List<MediaFileEntity>>
    ) {
        // 渲染药丸区域
        when (viewMode) {
            VIEW_MODE_CHARACTER -> {
                renderCharPills(charGroups)
                selectedChars.retainAll(charGroups.keys)
            }
            VIEW_MODE_TYPE -> renderTypePills(filtered)
            else -> binding.albumDetailPillsScroller.visibility = View.GONE
        }
        if (viewMode != VIEW_MODE_CHARACTER) selectedChars.clear()

        val displayed = MediaRenderHelper.computeDisplayed(
            filtered, emptyMap(), charGroups, emptySet(), selectedChars
        )

        binding.albumDetailCount.text = "${displayed.size} 文件 · ${MediaBrowserLogic.formatSize(displayed.sumOf { it.sizeBytes })}"

        val renderHash = buildString {
            append(viewMode)
            append("|cos=")
            append(isCosAlbum)
            append("|")
            append(displayed.size)
            append("|")
            append(displayed.firstOrNull()?.recordKey.orEmpty())
            append("|sel=")
            append(selectedChars.sorted().joinToString(","))
            append("|ft=")
            append(filterType.orEmpty())
            append("|typeExp=")
            append(typePillsExpanded)
            if (viewMode == VIEW_MODE_CHARACTER) {
                append("|")
                charGroups.entries.sortedBy { it.key }.forEach { (k, v) ->
                    append("$k:${v.size},")
                }
            }
        }
        if (renderHash != lastRenderedFingerprint) {
            lastRenderedFingerprint = renderHash
            if (viewMode == VIEW_MODE_CHARACTER && selectedChars.isEmpty()) {
                adapter.submitMediaWithGroups(filtered, charGroups)
            } else {
                adapter.submitMedia(displayed)
            }
        }
        binding.albumDetailEmptyText.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        binding.albumDetailRecycler.visibility = if (displayed.isEmpty()) View.GONE else View.VISIBLE
        updateChipStyles()
    }

    private fun renderCharPills(charGroups: Map<String, List<MediaFileEntity>>) {
        MediaPillsHelper.renderCharPills(
            requireContext(),
            binding.albumDetailPillsWrapper,
            binding.albumDetailPillsScroller,
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

    private fun renderTypePills(workingMedia: List<MediaFileEntity>) {
        MediaPillsHelper.renderTypePills(
            requireContext(),
            binding.albumDetailPillsWrapper,
            binding.albumDetailPillsScroller,
            workingMedia,
            filterType,
            typePillsExpanded,
            onFilterChanged = { type -> filterType = type },
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

    private fun updateChipStyles() {
        val colors = ThemeHelper.resolve(requireContext())
        val charCount = cachedCharGroups.size
        val typeCount = 3 // 图片 + 视频 + 动图
        listOf(
            Triple(binding.albumDetailChipCharacter, VIEW_MODE_CHARACTER, "角色 ($charCount)"),
            Triple(binding.albumDetailChipType, VIEW_MODE_TYPE, "类型 ($typeCount)")
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
                VIEW_MODE_CHARACTER -> charPillsExpanded = !charPillsExpanded
                VIEW_MODE_TYPE -> typePillsExpanded = !typePillsExpanded
            }
            lastRenderedFingerprint = ""
            updateChipStyles()
            render()
            return
        }
        viewMode = mode
        selectedChars.clear()
        if (mode == VIEW_MODE_CHARACTER) charPillsExpanded = false
        if (mode == VIEW_MODE_TYPE) typePillsExpanded = false
        lastRenderedFingerprint = ""
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
        binding.albumDetailColumnButton.setImageResource(iconRes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_ALBUM_NAME = "albumName"
        const val ARG_IS_COS = "isCos"
        const val DEFAULT_COLUMNS = 3
        private const val VIEW_MODE_CHARACTER = "character"
        private const val VIEW_MODE_TYPE = "type"

        fun newInstance(albumName: String, isCos: Boolean = false): AlbumDetailFragment = AlbumDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ALBUM_NAME, albumName)
                putBoolean(ARG_IS_COS, isCos)
            }
        }
    }
}
