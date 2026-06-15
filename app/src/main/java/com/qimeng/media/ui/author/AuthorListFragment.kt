package com.qimeng.media.ui.author

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AuthorListFragment : Fragment() {
    private var _binding: FragmentAuthorListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var sortMode = SORT_DEFAULT
    private var filterMode = FILTER_ALL
    private var cachedAuthors = emptyList<AuthorEntity>()
    private var cachedFileCounts = emptyList<AuthorFileCount>()
    private var cachedStats = emptyList<ViewStatsEntity>()
    private var cachedAuthorMedia = emptyList<AuthorMediaCrossRef>()
    private var lastRenderHash = ""

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

        val fileCountMap = cachedFileCounts.associateBy { it.authorId }
        val sorted = sortAndFilterAuthors(fileCountMap)

        val hash = sorted.joinToString("|") { it.authorId } + "|$sortMode|$filterMode"
        if (hash == lastRenderHash) return
        lastRenderHash = hash

        binding.authorRecycler.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
        binding.authorEmptyText.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE

        if (sorted.isEmpty()) return

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
            adapter.cachedAuthorViewCounts = computeAuthorViewCounts()
            adapter.cachedAuthorRecentViewCounts = computeAuthorRecentViewCounts()
            adapter.sortMode = sortMode
            adapter.submitList(sorted)
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

    private fun sortAndFilterAuthors(fileCountMap: Map<String, AuthorFileCount>): List<AuthorEntity> {
        // 先按分类筛选
        val filtered = when (filterMode) {
            FILTER_DEFAULT -> cachedAuthors.filter { !it.authorId.startsWith("cos_") }
            FILTER_COS -> cachedAuthors.filter { it.authorId.startsWith("cos_") }
            else -> cachedAuthors
        }
        // 再排序
        return when (sortMode) {
            SORT_FILE_COUNT -> filtered.sortedByDescending { author ->
                fileCountMap[author.authorId]?.fileCount ?: 0
            }
            SORT_VIEW_COUNT -> {
                val viewCounts = computeAuthorViewCounts()
                filtered.sortedByDescending { author ->
                    viewCounts[author.authorId] ?: 0
                }
            }
            SORT_FREQUENT -> {
                val recentCounts = computeAuthorRecentViewCounts()
                filtered.sortedByDescending { author ->
                    recentCounts[author.authorId] ?: 0
                }
            }
            SORT_FOLLOWED -> {
                // 特别关注：只显示已关注的作者
                val followedIds = viewModel.followedAuthorIds()
                filtered.filter { it.authorId in followedIds }.sortedBy { it.displayName }
            }
            else -> filtered.sortedBy { it.displayName }
        }
    }

    /** 排序选项 BottomSheet */
    private fun showSortSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(ctx), 18.dp(ctx), 20.dp(ctx), 28.dp(ctx))
            setBackgroundResource(R.drawable.bg_detail_sheet)
        }

        container.addView(TextView(ctx).apply {
            text = "排序方式"
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.qm_text_primary))
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
                    setTextColor(if (isActive) ContextCompat.getColor(ctx, R.color.qm_primary) else ContextCompat.getColor(ctx, R.color.qm_text_primary))
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                if (isActive) {
                    addView(TextView(ctx).apply {
                        text = "✓"
                        setTextColor(ContextCompat.getColor(ctx, R.color.qm_primary))
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

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val card = MaterialCardView(ctx).apply {
            radius = 12f.dp(ctx)
            cardElevation = 0f
            strokeWidth = 1.dp(ctx)
            setStrokeColor(ContextCompat.getColor(ctx, R.color.qm_divider))
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.qm_surface))
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
            setTextColor(ContextCompat.getColor(ctx, R.color.qm_text_primary))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            id = android.R.id.text1
        }

        val sub = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dp(ctx) }
            setTextColor(ContextCompat.getColor(ctx, R.color.qm_text_secondary))
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
