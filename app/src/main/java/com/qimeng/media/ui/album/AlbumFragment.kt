package com.qimeng.media.ui.album

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.databinding.FragmentAlbumBinding
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.widget.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumFragment : Fragment() {
    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var cachedGroups: Map<String, List<MediaFileEntity>> = emptyMap()
    private var cachedCosGroups: Map<String, List<MediaFileEntity>> = emptyMap()
    // v1.12 删除：cachedCosAuthorNameToId 只写不读死字段（含 cosNameToId 映射构建），已移除
    private var hasReceivedData = false  // Flow首次发射前不显示空状态，避免闪烁
    private var lastRenderHash: String = ""
    // v1.12：记录上次传入 SourceMatcher 的 customAlbumSources，
    // 仅在其变化时才调 updateCustomSources（否则 collect 每次发射都清空全局 matchAllCache）
    private var lastCustomSources: Set<String> = emptySet()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // View 重建时重置渲染哈希，确保 computeGroups 不会因哈希相同而跳过渲染
        lastRenderHash = ""
        // 立即渲染缓存数据，避免等待 Flow 重新发射期间显示空白
        if (cachedGroups.isNotEmpty()) {
            renderAlbums()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.nonCosMedia,
                    viewModel.allAuthorMedia,
                    viewModel.customAlbumSources,
                    combine(viewModel.cosWorks, viewModel.cosMedia, viewModel.authors) { cosWorks, cosMedia, authors ->
                        CosGroupData(cosWorks, cosMedia, authors.filter { it.authorId.startsWith("cos_") })
                    }
                ) { nonCosMedia, authorMedia, prefs, cosData ->
                    Quad(nonCosMedia, authorMedia, prefs, cosData)
                }.collect { (nonCosMedia, authorMedia, prefs, cosData) ->
                    // v1.12：仅自定义出处集合变化时才更新 SourceMatcher 全局缓存，
                    // 避免无关数据变化（如媒体列表刷新）清空所有页面的 matchAllCache
                    if (prefs.customAlbumSources != lastCustomSources) {
                        lastCustomSources = prefs.customAlbumSources
                        SourceMatcher.updateCustomSources(prefs.customAlbumSources)
                    }
                    computeGroups(nonCosMedia, authorMedia, cosData.cosWorks, cosData.cosMedia.filter { it.isCosFile }, cosData.cosAuthors)
                }
            }
        }
    }

    private data class CosGroupData(
        val cosWorks: List<CosWorkEntity>,
        val cosMedia: List<MediaFileEntity>,
        val cosAuthors: List<AuthorEntity>
    )
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private suspend fun computeGroups(
        mediaFiles: List<MediaFileEntity>,
        authorMedia: List<AuthorMediaCrossRef>,
        @Suppress("UNUSED_PARAMETER")
        cosWorks: List<CosWorkEntity>,
        cosMedia: List<MediaFileEntity>,
        cosAuthors: List<AuthorEntity>
    ) {
        // v1.16：Default 块只算数据与 renderHash，回主线程再比较/赋值/渲染——
        // 旧实现在 Default 线程读写 lastRenderHash/cachedGroups/cosGroups 共享字段，
        // 与主线程（renderAlbums、启动缓存渲染）存在并发窗口
        val (groups, cosGroups, renderHash) = withContext(Dispatchers.Default) {
            // 常规文件按出处分组
            // v1.12：改用 matchAll().first 走全局缓存，替代无缓存 match()，
            // 批量分组时同一文件名只匹配一次
            val groups = mutableMapOf<String, MutableList<MediaFileEntity>>()
            for (media in mediaFiles) {
                val source = SourceMatcher.matchAll(media.fileName).first ?: "其他"
                groups.getOrPut(source) { mutableListOf() }.add(media)
            }

            // COS 文件按作者分组（使用 crossRefs 而非 URI 前缀匹配）
            val cosAuthorIdToName = cosAuthors.associate { it.authorId to it.displayName }
            val cosRecordKeys = cosMedia.map { it.recordKey }.toSet()
            val cosMediaByKey = cosMedia.associateBy { it.recordKey }
            val cosGroups = mutableMapOf<String, MutableList<MediaFileEntity>>()
            for (ref in authorMedia) {
                if (!ref.authorId.startsWith("cos_")) continue
                if (ref.recordKey !in cosRecordKeys) continue
                val file = cosMediaByKey[ref.recordKey] ?: continue
                val authorName = cosAuthorIdToName[ref.authorId] ?: continue
                cosGroups.getOrPut(authorName) { mutableListOf() }.add(file)
            }

            // v1.15：COS 分组兜底「其他」桶——未出现在任何作者分组 key 下的 COS 文件
            // （无 AuthorMediaCrossRef 关联、作者名缺失等）归入「其他」，避免此类文件在相册页整体不可见
            val groupedKeys = cosGroups.values.flatten().map { it.recordKey }.toSet()
            val ungroupedCos = cosMedia.filter { it.recordKey !in groupedKeys }
            if (ungroupedCos.isNotEmpty()) {
                cosGroups.getOrPut("其他") { mutableListOf() }.addAll(ungroupedCos)
            }

            val renderHash = buildString {
                append(groups.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${it.value.size}" })
                append("|cos|")
                append(cosGroups.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${it.value.size}" })
            }
            Triple(groups, cosGroups, renderHash)
        }

        // 主线程统一比较 + 赋值 + 渲染（computeGroups 从主线程 collect 调用，恢复后仍在主线程）
        if (renderHash == lastRenderHash) return
        lastRenderHash = renderHash
        cachedGroups = groups
        cachedCosGroups = cosGroups
        hasReceivedData = true
        renderAlbums()
    }

    private fun renderAlbums() {
        if (_binding == null) return
        binding.albumListContainer.removeAllViews()

        val hasRegular = cachedGroups.isNotEmpty()
        val hasCos = cachedCosGroups.isNotEmpty()

        if (!hasRegular && !hasCos) {
            if (!hasReceivedData) return  // Flow还没发射数据，不显示空状态，避免闪烁
            binding.albumEmptyText.text = "扫描后可按出处分组查看媒体。"
            binding.albumEmptyText.visibility = View.VISIBLE
            binding.albumListContainer.visibility = View.GONE
            return
        }

        binding.albumEmptyText.visibility = View.GONE
        binding.albumListContainer.visibility = View.VISIBLE

        // 常规出处分区
        if (hasRegular) {
            val sorted = cachedGroups.entries.sortedByDescending { it.value.size }
            sorted.forEach { (name, items) ->
                binding.albumListContainer.addView(createAlbumCard(name, items, isCos = false))
            }
        }

        // COS 作者分区
        if (hasCos) {
            // 添加分隔标题
            val ctx = requireContext()
            binding.albumListContainer.addView(TextView(ctx).apply {
                text = "COS 作者"
                setTextColor(ctx.resolveThemeColor(R.attr.qmColorPrimary))
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(4.dp(requireContext()), 24.dp(requireContext()), 4.dp(requireContext()), 12.dp(requireContext()))
            })
            val sorted = cachedCosGroups.entries.sortedByDescending { it.value.size }
            sorted.forEach { (name, items) ->
                binding.albumListContainer.addView(createAlbumCard(name, items, isCos = true))
            }
        }
    }

    private fun createAlbumCard(name: String, items: List<MediaFileEntity>, isCos: Boolean): MaterialCardView {
        val ctx = requireContext()
        val imageCount = items.count { it.mediaType == MediaType.IMAGE }
        val animatedImageCount = items.count { it.mediaType == MediaType.ANIMATED_IMAGE }
        val videoCount = items.size - imageCount - animatedImageCount

        return MaterialCardView(ctx).apply {
            radius = 16.dp(requireContext()).toFloat()
            cardElevation = 0f
            strokeWidth = 1.dp(requireContext())
            setStrokeColor(ctx.resolveThemeColor(R.attr.qmColorDivider))
            setCardBackgroundColor(ctx.resolveThemeColor(R.attr.qmColorSurface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp(requireContext()) }

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20.dp(requireContext()), 20.dp(requireContext()), 20.dp(requireContext()), 20.dp(requireContext()))

                addView(TextView(ctx).apply {
                    text = name
                    setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
                    textSize = 18f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })

                addView(TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8.dp(requireContext()) }
                    val prefix = if (isCos) "COS · " else ""
                    text = "$prefix${items.size} 个文件 · 图片 $imageCount / 视频 $videoCount"
                    setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
                    textSize = 13f
                })
            })

            setOnClickListener {
                if (isCos) {
                    // COS 作者分区：进入 COS 相册详情页
                    (requireActivity() as? MainActivity)?.showAlbumDetail(name, isCos = true)
                } else {
                    (requireActivity() as? MainActivity)?.showAlbumDetail(name)
                }
            }
        }
    }

    fun scrollToTop() {
        (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
