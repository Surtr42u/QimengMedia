package com.qimeng.media.ui.search

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import com.qimeng.media.ui.widget.PinchZoomHelper
import com.qimeng.media.ui.widget.ColumnsRef
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.ThemeColors
import com.qimeng.media.ThemeHelper
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.ui.album.SourceMatcher
import com.qimeng.media.ui.adapter.GroupedMediaAdapter
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.ui.browser.MediaFilterState
import com.qimeng.media.ui.browser.MediaGroupHelper
import com.qimeng.media.ui.browser.SearchContext
import com.qimeng.media.ui.library.MediaLibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SearchFragment : Fragment() {

    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }

    // Views
    private var searchInput: EditText? = null
    private var searchBack: ImageView? = null
    private var searchAction: TextView? = null
    private var emptyState: ScrollView? = null
    private var recommendChips: ChipGroup? = null
    private var historySection: LinearLayout? = null
    private var historyChips: ChipGroup? = null
    private var historyClear: ImageView? = null
    private var suggestList: RecyclerView? = null
    private var resultState: FrameLayout? = null
    private var resultList: RecyclerView? = null
    private var emptyHint: TextView? = null

    // Data
    private var cachedMedia = emptyList<MediaFileEntity>()
    private var cachedCosMedia = emptyList<MediaFileEntity>()
    private var cachedCosWorks = emptyList<CosWorkEntity>()
    private var cachedAuthorMedia = emptyList<AuthorMediaCrossRef>()
    private var cachedAuthors = emptyList<AuthorEntity>()
    private var cachedStats = emptyList<ViewStatsEntity>()
    private var nameIndex: List<SearchSuggestItem> = emptyList()
    private var cachedSearchContext: SearchContext? = null
    private var currentState = STATE_EMPTY
    private var chipColors: ThemeColors? = null
    private val columnsRef = ColumnsRef(3)
    private lateinit var gridLayoutManager: GridLayoutManager

    // 搜索历史
    private val historyPrefs by lazy { requireContext().getSharedPreferences("search_history", Context.MODE_PRIVATE) }

    // Adapter
    private var suggestAdapter: SearchSuggestAdapter? = null
    private var resultAdapter: GroupedMediaAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 搜索页键盘弹出时不调整布局，避免底部tab显示在键盘上方
        requireActivity().window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val bnInfo = (requireActivity() as? MainActivity)?.getBottomNavigationInfo().orEmpty()
        AppLog.d("Search", "onViewCreated: softInputMode=ADJUST_NOTHING, bottomNav=$bnInfo")
        bindViews(view)
        setupSearchInput()
        setupAdapters()
        setupBackNavigation()
        showState(STATE_EMPTY)
        // 搜索历史来自SharedPreferences，立即显示，不等待数据流
        loadHistoryImmediately()
        // 优先用缓存索引立即填充推荐搜索（消除首次视觉延迟）
        if (cachedNameIndex.isNotEmpty()) {
            nameIndex = cachedNameIndex
            lastAppliedVersion = getCurrentVersion()
            loadHistoryAndRecommend()
        }
        observeData()

        // 自动弹出键盘
        searchInput?.postDelayed({
            searchInput?.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchInput, 0)
        }, 200)
    }

    private fun bindViews(view: View) {
        searchInput = view.findViewById(R.id.searchInput)
        searchBack = view.findViewById(R.id.searchBack)
        searchAction = view.findViewById(R.id.searchAction)
        emptyState = view.findViewById(R.id.searchEmptyState)
        recommendChips = view.findViewById(R.id.searchRecommendChips)
        historySection = view.findViewById(R.id.searchHistorySection)
        historyChips = view.findViewById(R.id.searchHistoryChips)
        historyClear = view.findViewById(R.id.searchHistoryClear)
        suggestList = view.findViewById(R.id.searchSuggestList)
        resultState = view.findViewById(R.id.searchResultState)
        resultList = view.findViewById(R.id.searchResultList)
        emptyHint = view.findViewById(R.id.searchEmptyHint)

        searchBack?.setOnClickListener { handleBack() }
        searchAction?.setOnClickListener { doSearch() }
        historyClear?.setOnClickListener {
            historyPrefs.edit { clear() }
            historySection?.visibility = View.GONE
        }
    }

    private fun setupSearchInput() {
        searchInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val input = s?.toString().orEmpty()
                if (input.isNotBlank()) {
                    updateSuggestions(input)
                    showState(STATE_SUGGEST)
                } else {
                    showState(STATE_EMPTY)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        searchInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        // 在结果状态下，点击搜索栏切回补全状态
        searchInput?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && currentState == STATE_RESULT) {
                val input = searchInput?.text?.toString().orEmpty()
                if (input.isNotBlank()) {
                    updateSuggestions(input)
                    showState(STATE_SUGGEST)
                } else {
                    showState(STATE_EMPTY)
                }
            }
        }
    }

    private fun setupAdapters() {
        suggestAdapter = SearchSuggestAdapter { text ->
            searchInput?.setText(text)
            searchInput?.setSelection(text.length)
            doSearch()
        }
        suggestList?.adapter = suggestAdapter
        suggestList?.layoutManager = LinearLayoutManager(requireContext())

        resultAdapter = GroupedMediaAdapter(
            onItemClick = { item, list ->
                (requireActivity() as? MainActivity)?.showDetailFragment(item.recordKey, list.map { it.recordKey })
            },
            lifecycleScope = viewLifecycleOwner.lifecycleScope
        )
        gridLayoutManager = GridLayoutManager(requireContext(), columnsRef.value).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (resultAdapter?.isHeader(position) == true) spanCount else 1
            }
        }
        resultList?.layoutManager = gridLayoutManager
        resultList?.adapter = resultAdapter
        resultList?.itemAnimator = null
        resultList?.setItemViewCacheSize(20)

        // 双指缩放列数（与全部页一致）
        resultList?.let { PinchZoomHelper.setup(requireContext(), columnsRef, gridLayoutManager, it) }
    }

    private fun setupBackNavigation() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    this@SearchFragment.handleBack()
                }
            }
        )
    }

    private fun handleBack() {
        when (currentState) {
            STATE_RESULT -> {
                searchInput?.setText("")
                showState(STATE_EMPTY)
                showKeyboard()
            }
            STATE_SUGGEST -> {
                searchInput?.setText("")
                showState(STATE_EMPTY)
                showKeyboard()
            }
            else -> {
                hideKeyboard()
                (requireActivity() as? MainActivity)?.supportFragmentManager?.popBackStack()
            }
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                combine(
                    viewModel.nonCosMedia,
                    viewModel.cosMedia,
                    viewModel.cosWorks
                ) { media, cosMedia, cosWorks ->
                    arrayOf(media, cosMedia, cosWorks)
                },
                combine(
                    viewModel.allStats,
                    viewModel.allAuthorMedia,
                    viewModel.authors
                ) { stats, authorMedia, authors ->
                    arrayOf(stats, authorMedia, authors)
                }
            ) { arr1, arr2 ->
                arr1 + arr2
            }.collect @Suppress("UNCHECKED_CAST") { arr ->
                val media = arr[0] as? List<MediaFileEntity> ?: return@collect
                val cosMedia = arr[1] as? List<MediaFileEntity> ?: return@collect
                val cosWorks = arr[2] as? List<CosWorkEntity> ?: return@collect
                val stats = arr[3] as? List<ViewStatsEntity> ?: return@collect
                val authorMedia = arr[4] as? List<AuthorMediaCrossRef> ?: return@collect
                val authors = arr[5] as? List<AuthorEntity> ?: return@collect

                cachedMedia = media
                cachedCosMedia = cosMedia
                cachedCosWorks = cosWorks
                cachedStats = stats
                cachedAuthorMedia = authorMedia
                cachedAuthors = authors

                // 异步重建名字索引，避免卡顿
                rebuildNameIndexAsync()
            }
        }
    }

    // ===== 名字索引 =====

    private fun rebuildNameIndexAsync() {
        val fingerprint = computeDataFingerprint(cachedMedia, cachedCosMedia, cachedCosWorks, cachedAuthorMedia, cachedAuthors)
        // 数据指纹未变且缓存非空时跳过重建，直接使用已有索引
        if (cachedNameIndex.isNotEmpty() && fingerprint == cachedDataFingerprint) {
            nameIndex = cachedNameIndex
            cachedSearchContext = cachedSearchContextCache
            AppLog.d("Search", "rebuildNameIndex: skipped (fingerprint unchanged, ${cachedNameIndex.size} items)")
            return
        }
        val idxStart = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            val (items, searchContext) = withContext(Dispatchers.Default) {
                val idx = buildNameIndex(cachedMedia, cachedCosMedia, cachedCosWorks, cachedAuthorMedia, cachedAuthors)
                val ctx = buildSearchContextFromData(cachedMedia, cachedCosMedia, cachedAuthorMedia, cachedAuthors, cachedCosWorks)
                idx to ctx
            }

            nameIndex = items
            cachedSearchContext = searchContext
            // 更新 companion 缓存（跨实例复用）和指纹
            updateCache(items, fingerprint)
            cachedSearchContextCache = searchContext
            AppLog.d("Search", "rebuildNameIndex: ${items.size} items, time=${System.currentTimeMillis() - idxStart}ms")
            // 仅当缓存版本变化时刷新推荐搜索UI（避免重复填充导致闪烁）
            val currentVersion = getCurrentVersion()
            if (currentVersion != lastAppliedVersion) {
                lastAppliedVersion = currentVersion
                loadHistoryAndRecommend()
            }
        }
    }

    // ===== 补全推荐 =====

    private fun updateSuggestions(input: String) {
        val normalized = input.trim().lowercase(Locale.getDefault())
        val matched = nameIndex.filter {
            it.text.lowercase(Locale.getDefault()).contains(normalized)
        }.sortedBy { it.text.length }.take(10)

        suggestAdapter?.submitList(matched)
    }

    // ===== 搜索执行 =====

    private fun doSearch() {
        val query = searchInput?.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) return
        val searchStart = System.currentTimeMillis()

        hideKeyboard()
        saveHistory(query)

        viewLifecycleOwner.lifecycleScope.launch {
            val allMedia = cachedMedia + cachedCosMedia
            val statsMap = cachedStats.associateBy { it.recordKey }
            // 优先使用缓存的 SearchContext，避免重复计算 SourceMatcher
            val searchContext = cachedSearchContext ?: withContext(Dispatchers.Default) { buildSearchContext() }

            val filtered = withContext(Dispatchers.Default) {
                MediaBrowserLogic.applyFilter(
                    allMedia, query, MediaFilterState(), statsMap, emptyMap(), emptyList(), emptyMap(), searchContext
                )
            }

            AppLog.d("Search", "doSearch: query=$query, total=${System.currentTimeMillis() - searchStart}ms, results=${filtered.size}")

            if (filtered.isEmpty()) {
                resultAdapter?.submitMedia(emptyList())
                emptyHint?.visibility = View.VISIBLE
                resultList?.visibility = View.GONE
            } else {
                emptyHint?.visibility = View.GONE
                resultList?.visibility = View.VISIBLE
                resultAdapter?.submitMedia(filtered)
            }
            showState(STATE_RESULT)
        }
    }

    private fun buildSearchContext(): SearchContext {
        return buildSearchContextFromData(cachedMedia, cachedCosMedia, cachedAuthorMedia, cachedAuthors, cachedCosWorks)
    }

    // ===== 状态切换 =====

    private fun showState(state: Int) {
        currentState = state
        emptyState?.visibility = if (state == STATE_EMPTY) View.VISIBLE else View.GONE
        suggestList?.visibility = if (state == STATE_SUGGEST) View.VISIBLE else View.GONE
        resultState?.visibility = if (state == STATE_RESULT) View.VISIBLE else View.GONE
    }

    // ===== 搜索历史 =====

    private fun saveHistory(query: String) {
        val history = historyPrefs.getStringSet("keys", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        history.remove(query)
        history.add(query)
        if (history.size > 20) {
            val list = history.toList()
            history.clear()
            history.addAll(list.takeLast(20))
        }
        historyPrefs.edit { putStringSet("keys", history) }
    }

    private fun getHistory(): List<String> {
        return historyPrefs.getStringSet("keys", emptySet())?.toList()?.reversed() ?: emptyList()
    }

    /** 立即加载搜索历史（来自SharedPreferences，无需等待数据流） */
    private fun loadHistoryImmediately() {
        val history = getHistory()
        if (history.isNotEmpty()) {
            historySection?.visibility = View.VISIBLE
            historyChips?.removeAllViews()
            for (item in history) {
                historyChips?.addView(makeChip(item) {
                    searchInput?.setText(item)
                    searchInput?.setSelection(item.length)
                    doSearch()
                })
            }
        }
    }

    private fun loadHistoryAndRecommend() {
        // 推荐搜索
        if (recommendChips?.childCount == 0 && nameIndex.isNotEmpty()) {
            recommendChips?.removeAllViews()
            val recommended = nameIndex.shuffled().take(10)
            for (item in recommended) {
                recommendChips?.addView(makeChip(item.text) {
                    searchInput?.setText(item.text)
                    searchInput?.setSelection(item.text.length)
                    doSearch()
                })
            }
        }

        // 搜索历史（仅在尚未加载时填充，避免重复）
        if (historyChips?.childCount == 0) {
            val history = getHistory()
            if (history.isNotEmpty()) {
                historySection?.visibility = View.VISIBLE
                for (item in history) {
                    historyChips?.addView(makeChip(item) {
                        searchInput?.setText(item)
                        searchInput?.setSelection(item.length)
                        doSearch()
                    })
                }
            } else {
                historySection?.visibility = View.GONE
            }
        }
    }

    private fun makeChip(text: String, onClick: () -> Unit): Chip {
        // 缓存主题颜色，避免每个 chip 都重新解析主题属性
        val colors = chipColors ?: ThemeHelper.resolve(requireContext()).also { chipColors = it }
        return Chip(requireContext()).apply {
            this.text = text
            textSize = 12f
            setChipBackgroundColorResource(android.R.color.transparent)
            setTextColor(colors.textPrimary)
            setEnsureMinTouchTargetSize(false)
            chipStrokeWidth = 0f
            chipIcon = null
            isChipIconVisible = false
            isCloseIconVisible = false
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun showKeyboard() {
        searchInput?.postDelayed({
            searchInput?.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchInput, 0)
        }, 100)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput?.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 恢复默认softInputMode
        @Suppress("DEPRECATION")
        requireActivity().window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        searchInput = null
        searchBack = null
        searchAction = null
        emptyState = null
        recommendChips = null
        historySection = null
        historyChips = null
        historyClear = null
        suggestList = null
        resultState = null
        resultList = null
        emptyHint = null
        suggestAdapter = null
        resultAdapter = null
        chipColors = null
    }

    companion object {
        const val STATE_EMPTY = 0
        const val STATE_SUGGEST = 1
        const val STATE_RESULT = 2

        // ===== nameIndex 缓存（跨实例复用，避免每次从零重建） =====
        /** 缓存的推荐搜索索引 */
        var cachedNameIndex: List<SearchSuggestItem> = emptyList()
            private set
        /** 缓存版本号，数据变化时递增 */
        private var cacheVersion = 0L
        /** 上次缓存使用的版本号，用于判断是否需要刷新UI */
        var lastAppliedVersion = 0L
        /** 缓存的数据指纹，用于判断数据是否变化 */
        var cachedDataFingerprint: Long = 0L
            private set
        /** 缓存的 SearchContext，与 nameIndex 同步构建，避免 doSearch 时重复计算 SourceMatcher */
        var cachedSearchContextCache: SearchContext? = null
            private set

        /** 计算数据指纹，用于判断是否需要重建索引 */
        fun computeDataFingerprint(
            media: List<MediaFileEntity>,
            cosMedia: List<MediaFileEntity>,
            cosWorks: List<CosWorkEntity>,
            authorMedia: List<AuthorMediaCrossRef>,
            authors: List<AuthorEntity>
        ): Long {
            var hash = 0L
            hash = hash * 31L + media.size
            hash = hash * 37L + cosMedia.size
            hash = hash * 41L + cosWorks.size
            hash = hash * 43L + authorMedia.size
            hash = hash * 47L + authors.size
            // 内容摘要：取前 64 个元素的 hashCode 求和，避免全量 hashCode 开销
            hash = hash * 53L + media.take(64).sumOf { it.recordKey.hashCode().toLong() }
            hash = hash * 59L + cosMedia.take(64).sumOf { it.recordKey.hashCode().toLong() }
            return hash
        }

        /** 构建名字索引（纯计算，无 Android 依赖，可在任意线程调用） */
        fun buildNameIndex(
            media: List<MediaFileEntity>,
            cosMedia: List<MediaFileEntity>,
            cosWorks: List<CosWorkEntity>,
            authorMedia: List<AuthorMediaCrossRef>,
            authors: List<AuthorEntity>
        ): List<SearchSuggestItem> {
            val result = mutableListOf<SearchSuggestItem>()

            // 常规出处+角色：一次 matchAll 遍历同时提取出处和角色
            val allSources = mutableSetOf<String>()
            val sourceCharPairs = mutableSetOf<Pair<String, String>>()
            for (m in media) {
                val (source, characters) = SourceMatcher.matchAll(m.fileName)
                if (source != null) {
                    allSources.add(source)
                    for (char in characters) { sourceCharPairs.add(source to char) }
                }
            }
            for (source in allSources) { result.add(SearchSuggestItem("出处", source)) }
            for ((source, char) in sourceCharPairs) { result.add(SearchSuggestItem("角色", "$source $char")) }

            // COS作者 + COS作品：建索引一次，遍历一遍 cosMedia 同时提取作者和作品
            // （旧实现两个独立 for 循环各对全库 cosMedia 做嵌套线性扫描，此处合并并改 O(1) 查找）
            val cosAuthorIndex = MediaGroupHelper.CosAuthorIndex.build(authorMedia, authors)
            val cosWorkIndex = MediaGroupHelper.CosWorkIndex.build(cosWorks)
            val cosAuthors = mutableSetOf<String>()
            val cosWorkNames = mutableSetOf<String>()
            for (m in cosMedia) {
                val authorName = MediaGroupHelper.findCosAuthorForMedia(m, cosAuthorIndex)
                if (authorName != "其他") cosAuthors.add(authorName)
                val workName = MediaGroupHelper.findCosCharacterForMedia(m, cosAuthorIndex, cosWorkIndex)
                if (workName != "其他") cosWorkNames.add(workName)
            }
            for (name in cosAuthors) { result.add(SearchSuggestItem("COS作者", name)) }
            for (name in cosWorkNames) { result.add(SearchSuggestItem("COS作品", name)) }

            return result
        }

        /** 构建 SearchContext（纯计算，无 Android 依赖，可在任意线程调用） */
        fun buildSearchContextFromData(
            media: List<MediaFileEntity>,
            cosMedia: List<MediaFileEntity>,
            authorMedia: List<AuthorMediaCrossRef>,
            authors: List<AuthorEntity>,
            cosWorks: List<CosWorkEntity>
        ): SearchContext {
            val cosAuthorMap = mutableMapOf<String, String>()
            val cosWorkMap = mutableMapOf<String, String>()
            val sourceMap = mutableMapOf<String, String?>()
            val characterMap = mutableMapOf<String, String?>()

            // 建索引一次，循环内 O(1) 查找替代 O(N×M) 嵌套扫描
            val cosAuthorIndex = MediaGroupHelper.CosAuthorIndex.build(authorMedia, authors)
            val cosWorkIndex = MediaGroupHelper.CosWorkIndex.build(cosWorks)
            for (m in cosMedia) {
                val authorName = MediaGroupHelper.findCosAuthorForMedia(m, cosAuthorIndex)
                if (authorName != "其他") cosAuthorMap[m.recordKey] = authorName
                val workName = MediaGroupHelper.findCosCharacterForMedia(m, cosAuthorIndex, cosWorkIndex)
                if (workName != "其他") cosWorkMap[m.recordKey] = workName
            }
            for (m in media) {
                val (source, characters) = SourceMatcher.matchAll(m.fileName)
                if (source != null) sourceMap[m.recordKey] = source
                if (characters.isNotEmpty()) characterMap[m.recordKey] = characters.sorted().joinToString("+")
            }
            return SearchContext(cosAuthorMap, cosWorkMap, sourceMap, characterMap)
        }

        /** 预构建搜索索引（供首页等提前调用，用户打开搜索页时索引已就绪） */
        fun prebuildNameIndex(
            media: List<MediaFileEntity>,
            cosMedia: List<MediaFileEntity>,
            cosWorks: List<CosWorkEntity>,
            authorMedia: List<AuthorMediaCrossRef>,
            authors: List<AuthorEntity>
        ) {
            val fingerprint = computeDataFingerprint(media, cosMedia, cosWorks, authorMedia, authors)
            if (cachedNameIndex.isNotEmpty() && fingerprint == cachedDataFingerprint) return
            val items = buildNameIndex(media, cosMedia, cosWorks, authorMedia, authors)
            val ctx = buildSearchContextFromData(media, cosMedia, authorMedia, authors, cosWorks)
            updateCache(items, fingerprint)
            cachedSearchContextCache = ctx
        }

        /** 更新缓存（仅在索引内容变化时递增版本） */
        fun updateCache(newIndex: List<SearchSuggestItem>, fingerprint: Long) {
            if (cachedNameIndex.size != newIndex.size || cachedDataFingerprint != fingerprint) cacheVersion++
            cachedNameIndex = newIndex
            cachedDataFingerprint = fingerprint
        }

        /** 获取当前缓存版本 */
        fun getCurrentVersion(): Long = cacheVersion
    }
}

// ===== 补全推荐数据类 =====

data class SearchSuggestItem(
    val type: String,
    val text: String
)

// ===== 补全推荐 Adapter =====

class SearchSuggestAdapter(
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<SearchSuggestAdapter.ViewHolder>() {

    private val items = mutableListOf<SearchSuggestItem>()

    fun submitList(newItems: List<SearchSuggestItem>) {
        val oldSize = items.size
        items.clear()
        items.addAll(newItems)
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        if (items.isNotEmpty()) notifyItemRangeInserted(0, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_suggest, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.text.text = item.text
        holder.typeLabel.text = item.type
        holder.itemView.setOnClickListener { onItemClick(item.text) }
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.suggestText)
        val typeLabel: TextView = view.findViewById(R.id.suggestTypeLabel)
    }
}
