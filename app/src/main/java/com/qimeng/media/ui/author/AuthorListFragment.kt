package com.qimeng.media.ui.author

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.ThemeHelper
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorFileCount
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.ViewStatsEntity
import com.qimeng.media.databinding.FragmentAuthorListBinding
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.widget.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthorListFragment : Fragment() {
    private var _binding: FragmentAuthorListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var sortMode = SORT_DEFAULT
    private var filterMode = FILTER_ALL
    private var searchQuery = ""
    private var cachedAuthors = emptyList<AuthorEntity>()
    private var cachedFileCounts = emptyList<AuthorFileCount>()
    private var cachedStats = emptyList<ViewStatsEntity>()
    private var cachedAuthorMedia = emptyList<AuthorMediaCrossRef>()
    private var lastRenderHash = ""
    /** 渲染计算协程任务：先取消旧任务再启动，避免快速输入/数据发射时旧协程晚完成覆盖新结果（v1.15） */
    private var renderJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuthorListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.authorBackButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.authorChipAll.setOnClickListener { setFilterMode(FILTER_ALL) }
        binding.authorChipDefault.setOnClickListener { setFilterMode(FILTER_DEFAULT) }
        binding.authorChipCos.setOnClickListener { setFilterMode(FILTER_COS) }

        binding.authorSortButton.setOnClickListener { showSortSheet() }

        // 搜索：按作者显示名模糊过滤，输入即刷新，与胶囊筛选/排序叠加生效
        binding.authorSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                lastRenderHash = ""
                renderAuthors()
            }
        })
        // 键盘搜索键收起输入法，避免遮挡列表
        binding.authorSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.authorSearchInput.windowToken, 0)
                binding.authorSearchInput.clearFocus()
                true
            } else {
                false
            }
        }

        setFilterMode(FILTER_ALL)
        observeData()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.authors,
                    viewModel.authorFileCounts,
                    viewModel.allStats,
                    viewModel.allAuthorMedia
                ) { authors, fileCounts, stats, authorMedia ->
                    Quad(authors, fileCounts, stats, authorMedia)
                }.collect { (authors, fileCounts, stats, authorMedia) ->
                    cachedAuthors = authors
                    cachedFileCounts = fileCounts
                    cachedStats = stats
                    cachedAuthorMedia = authorMedia
                    updateChipCount()
                    renderAuthors()
                }
            }
        }
    }

    /** 更新胶囊中的作者数量 */
    private fun updateChipCount() {
        val allCount = cachedAuthors.size
        val defaultCount = cachedAuthors.count { !it.authorId.startsWith("cos_") }
        val cosCount = cachedAuthors.count { it.authorId.startsWith("cos_") }
        binding.authorChipAll.text = "全部（$allCount）"
        binding.authorChipDefault.text = "常规（$defaultCount）"
        binding.authorChipCos.text = "COS（$cosCount）"
    }

    private fun renderAuthors() {
        if (_binding == null) return

        // v1.15：统计计算与排序整体移入 Dispatchers.Default（原实现每次输入按键/数据发射
        // 都在主线程全量重算 viewCounts/recentViewCounts，且 sortAndFilterAuthors 内部又
        // 重复计算一遍），主线程只做 UI 更新；renderJob 先 cancel 防快速输入时旧结果覆盖新结果
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val fileCountMap = cachedFileCounts.associateBy { it.authorId }
                val viewCounts = computeAuthorViewCounts()
                val recentViewCounts = computeAuthorRecentViewCounts()
                val sorted = sortAndFilterAuthors(fileCountMap, viewCounts, recentViewCounts)

                // v1.12 修复：hash 追加文件数/浏览数维度，浏览/文件数变化后列表副标题与排序可刷新
                val hash = buildString {
                    append(sorted.joinToString("|") { it.authorId })
                    append("|$sortMode|$filterMode")
                    append("|search=$searchQuery")
                    append("|counts=")
                    fileCountMap.entries.sortedBy { it.key }.forEach { (id, c) -> append("$id:${c.fileCount},") }
                    append("|views=")
                    viewCounts.entries.sortedBy { it.key }.forEach { (id, v) -> append("$id:$v,") }
                }
                AuthorRenderResult(fileCountMap, viewCounts, recentViewCounts, sorted, hash)
            }
            if (_binding == null) return@launch
            val (fileCountMap, viewCounts, recentViewCounts, sorted, hash) = result
            if (hash == lastRenderHash) return@launch
            lastRenderHash = hash

            binding.authorRecycler.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
            binding.authorEmptyText.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
            // 搜索无结果时显示搜索空状态，否则显示默认空状态
            binding.authorEmptyText.text = if (sorted.isEmpty() && searchQuery.isNotBlank()) {
                "没有找到匹配「$searchQuery」的作者"
            } else {
                "还没有添加作者\n通过数据管理导入 TXT 文件添加"
            }

            if (sorted.isEmpty()) return@launch

            val container = binding.authorRecycler
            if (container.adapter == null || container.layoutManager == null) {
                container.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
                container.adapter = AuthorListAdapter { author ->
                    val isCos = author.authorId.startsWith("cos_")
                    (requireActivity() as? MainActivity)?.showAuthorFiles(author.authorId, isCos)
                }
            }
            (container.adapter as? AuthorListAdapter)?.let { adapter ->
                adapter.cachedFileCounts = fileCountMap
                adapter.cachedAuthorViewCounts = viewCounts
                adapter.cachedAuthorRecentViewCounts = recentViewCounts
                adapter.sortMode = sortMode
                adapter.submitList(sorted)
                // v1.12：列表项内容相等时 AsyncListDiffer 不会 re-bind，手动刷新副标题等渲染数据
                adapter.refreshBindings()
            }
        }
    }

    /** 计算每个作者的总浏览次数（viewCount + playCount 之和） */
    private fun computeAuthorViewCounts(): Map<String, Int> {
        val statsByKey = cachedStats.associateBy { it.recordKey }
        val result = mutableMapOf<String, Int>()
        for (ref in cachedAuthorMedia) {
            val stat = statsByKey[ref.recordKey] ?: continue
            val total = stat.viewCount + stat.playCount
            if (total > 0) {
                result[ref.authorId] = (result[ref.authorId] ?: 0) + total
            }
        }
        return result
    }

    /** 计算每个作者7天内的浏览次数（viewCount + playCount 之和） */
    private fun computeAuthorRecentViewCounts(): Map<String, Int> {
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val statsByKey = cachedStats.associateBy { it.recordKey }
        val result = mutableMapOf<String, Int>()
        for (ref in cachedAuthorMedia) {
            val stat = statsByKey[ref.recordKey] ?: continue
            // 只统计7天内打开过的文件
            if ((stat.lastOpenedAtMillis ?: 0) < sevenDaysAgo) continue
            val total = stat.viewCount + stat.playCount
            if (total > 0) {
                result[ref.authorId] = (result[ref.authorId] ?: 0) + total
            }
        }
        return result
    }

    private fun sortAndFilterAuthors(
        fileCountMap: Map<String, AuthorFileCount>,
        viewCounts: Map<String, Int>,
        recentViewCounts: Map<String, Int>
    ): List<AuthorEntity> {
        // 先按搜索词过滤（显示名模糊匹配，忽略大小写，多别名合并名任一命中即可）
        var filtered = if (searchQuery.isBlank()) cachedAuthors else cachedAuthors.filter {
            it.displayName.contains(searchQuery, ignoreCase = true)
        }
        // 再按分类筛选
        filtered = when (filterMode) {
            FILTER_DEFAULT -> filtered.filter { !it.authorId.startsWith("cos_") }
            FILTER_COS -> filtered.filter { it.authorId.startsWith("cos_") }
            else -> filtered
        }
        // 再排序（viewCounts/recentViewCounts 由调用方一次性算好传入，避免每种排序各重算一遍）
        return when (sortMode) {
            SORT_FILE_COUNT -> filtered.sortedByDescending { author ->
                fileCountMap[author.authorId]?.fileCount ?: 0
            }
            SORT_VIEW_COUNT -> filtered.sortedByDescending { author ->
                viewCounts[author.authorId] ?: 0
            }
            SORT_FREQUENT -> filtered.sortedByDescending { author ->
                recentViewCounts[author.authorId] ?: 0
            }
            SORT_FOLLOWED -> {
                // 特别关注：只显示已关注的作者
                val followedIds = viewModel.followedAuthorIds()
                filtered.filter { it.authorId in followedIds }.sortedBy { it.displayName }
            }
            else -> filtered.sortedBy { it.displayName }
        }
    }

    /** renderAuthors 协程内计算结果封装（v1.15） */
    private data class AuthorRenderResult(
        val fileCountMap: Map<String, AuthorFileCount>,
        val viewCounts: Map<String, Int>,
        val recentViewCounts: Map<String, Int>,
        val sorted: List<AuthorEntity>,
        val hash: String
    )

    /** 排序选项 BottomSheet */
    private fun showSortSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val ctx = requireContext()
        // v1.12 修复：排序弹窗颜色改用 ThemeHelper.resolve 批量解析主题色，跟随系统明暗模式（弹窗不得用 ContextCompat.getColor(R.color.qm_*)）
        val colors = ThemeHelper.resolve(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(ctx), 18.dp(ctx), 20.dp(ctx), 28.dp(ctx))
            setBackgroundResource(R.drawable.bg_detail_sheet)
        }

        container.addView(TextView(ctx).apply {
            text = "排序方式"
            gravity = android.view.Gravity.CENTER
            setTextColor(colors.textPrimary)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 10.dp(ctx))
        })

        val options = listOf(
            SORT_DEFAULT to "默认排序",
            SORT_FOLLOWED to "特别关注",
            SORT_FREQUENT to "经常浏览",
            SORT_VIEW_COUNT to "浏览次数",
            SORT_FILE_COUNT to "文件数量"
        )

        for ((mode, label) in options) {
            val isActive = sortMode == mode
            container.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16.dp(ctx), 12.dp(ctx), 16.dp(ctx), 12.dp(ctx))
                setBackgroundResource(R.drawable.bg_empty_panel)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp(ctx) }
                setOnClickListener {
                    dialog.dismiss()
                    setSortMode(mode)
                }

                addView(TextView(ctx).apply {
                    text = label
                    setTextColor(if (isActive) colors.primary else colors.textPrimary)
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                if (isActive) {
                    addView(TextView(ctx).apply {
                        text = "✓"
                        setTextColor(colors.primary)
                        textSize = 16f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                }
            })
        }

        dialog.setContentView(container)
        dialog.show()
    }

    private fun setSortMode(mode: String) {
        sortMode = mode
        lastRenderHash = ""
        // 更新排序按钮文字
        val sortLabel = when (mode) {
            SORT_DEFAULT -> "默认排序"
            SORT_FREQUENT -> "经常浏览"
            SORT_FILE_COUNT -> "文件数量"
            SORT_VIEW_COUNT -> "浏览次数"
            SORT_FOLLOWED -> "特别关注"
            else -> "默认排序"
        }
        binding.authorSortButton.text = "$sortLabel ▾"
        renderAuthors()
    }

    private fun setFilterMode(mode: String) {
        filterMode = mode
        lastRenderHash = ""
        val colors = ThemeHelper.resolve(requireContext())
        // 更新三个胶囊的选中样式
        listOf(
            Triple(binding.authorChipAll, FILTER_ALL, "全部"),
            Triple(binding.authorChipDefault, FILTER_DEFAULT, "常规"),
            Triple(binding.authorChipCos, FILTER_COS, "COS")
        ).forEach { (chip, m, _) ->
            val isActive = mode == m
            chip.setBackgroundResource(if (isActive) R.drawable.bg_capsule_primary else 0)
            chip.setTextColor(if (isActive) colors.bg else colors.textSecondary)
        }
        renderAuthors()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val SORT_DEFAULT = "default"
        const val SORT_FREQUENT = "frequent"
        const val SORT_FILE_COUNT = "file_count"
        const val SORT_VIEW_COUNT = "view_count"
        const val SORT_FOLLOWED = "followed"

        const val FILTER_ALL = "all"
        const val FILTER_DEFAULT = "default"
        const val FILTER_COS = "cos"
    }
}

/** 四元组辅助类，用于 combine 四个 Flow */
private data class Quad<A, B, C, D>(
    val first: A, val second: B, val third: C, val fourth: D
)

class AuthorListAdapter(
    private val onItemClick: (AuthorEntity) -> Unit
) : RecyclerView.Adapter<AuthorListAdapter.Holder>() {

    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<AuthorEntity>() {
        override fun areItemsTheSame(oldItem: AuthorEntity, newItem: AuthorEntity) =
            oldItem.authorId == newItem.authorId
        override fun areContentsTheSame(oldItem: AuthorEntity, newItem: AuthorEntity) =
            oldItem == newItem
    })

    private var items: List<AuthorEntity>
        get() = differ.currentList
        set(value) = differ.submitList(value)
    var cachedFileCounts: Map<String, AuthorFileCount> = emptyMap()
    var cachedAuthorViewCounts: Map<String, Int> = emptyMap()
    var cachedAuthorRecentViewCounts: Map<String, Int> = emptyMap()
    var sortMode: String = AuthorListFragment.SORT_DEFAULT

    fun submitList(list: List<AuthorEntity>) {
        items = list
    }

    /** 强制刷新全部可见项：文件数/浏览数等渲染数据变化但列表项相同时，AsyncListDiffer 不会 re-bind，需手动触发 */
    fun refreshBindings() {
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        // v1.12 修复：作者卡片颜色改用 ThemeHelper.resolve 批量解析主题色，跟随系统明暗模式（不得用 ContextCompat.getColor(R.color.qm_*)）
        val colors = ThemeHelper.resolve(ctx)
        val card = MaterialCardView(ctx).apply {
            radius = 12f.dp(ctx)
            cardElevation = 0f
            strokeWidth = 1.dp(ctx)
            setStrokeColor(colors.divider)
            setCardBackgroundColor(colors.surface)
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp(ctx) }
        }

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(ctx), 16.dp(ctx), 16.dp(ctx), 16.dp(ctx))
        }

        val name = TextView(ctx).apply {
            setTextColor(colors.textPrimary)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            id = android.R.id.text1
        }

        val sub = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dp(ctx) }
            setTextColor(colors.textSecondary)
            textSize = 12f
            id = android.R.id.text2
        }

        inner.addView(name)
        inner.addView(sub)
        card.addView(inner)
        return Holder(card, name, sub)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val author = items[position]
        val isCos = author.authorId.startsWith("cos_")
        holder.name.text = if (isCos) "${author.displayName} · COS" else author.displayName
        val count = cachedFileCounts[author.authorId]?.fileCount ?: 0
        val viewCount = cachedAuthorViewCounts[author.authorId] ?: 0
        val recentCount = cachedAuthorRecentViewCounts[author.authorId] ?: 0
        holder.sub.text = when (sortMode) {
            AuthorListFragment.SORT_FILE_COUNT -> "$count 个文件"
            AuthorListFragment.SORT_VIEW_COUNT -> "浏览 $viewCount 次"
            AuthorListFragment.SORT_FREQUENT -> "近7天浏览 $recentCount 次"
            else -> "$count 个文件"
        }
        holder.itemView.setOnClickListener { onItemClick(author) }
    }

    class Holder(
        itemView: View,
        val name: TextView,
        val sub: TextView
    ) : RecyclerView.ViewHolder(itemView)
}
