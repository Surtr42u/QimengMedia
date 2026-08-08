package com.qimeng.media.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.data.prefs.RecommendationPrefs
import com.qimeng.media.databinding.FragmentProfileBinding
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.library.ScanStatus
import com.qimeng.media.ui.widget.dp
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var lastScanMessage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 数据管理/数据备份已迁移至独立页面（DataManagementFragment/DataBackupFragment），
        // SAF launcher 等逻辑随之迁移，本页不再注册。
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.favoriteRow.setOnClickListener {
            (requireActivity() as? MainActivity)?.showFavorites()
        }

        binding.authorManagementRow.setOnClickListener {
            (requireActivity() as? MainActivity)?.showAuthorList()
        }

        binding.dataManagementRow.setOnClickListener {
            (requireActivity() as? MainActivity)?.showDataManagement()
        }

        binding.backupRow.setOnClickListener {
            (requireActivity() as? MainActivity)?.showDataBackup()
        }

        binding.compatCheckRow.setOnClickListener {
            showCompatCheckDialog()
        }

        // 主题色彩行不可点击（v1.10 设计决策：仅跟随手机系统明暗模式，无 App 内切换入口）。
        // QimengProfileRow 默认 clickable=false，不设置 OnClickListener 即不可点击。

        binding.recommendationPrefsRow.setOnClickListener {
            showRecommendationPrefsDialog()
        }

        binding.historyRow.setOnClickListener {
            (requireActivity() as? MainActivity)?.showHistory()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.imageCount, viewModel.videoCount) { imageCount, videoCount ->
                        imageCount to videoCount
                    }.collect { (imageCount, videoCount) ->
                        binding.imageCountCard.text = "图片\n${imageCount}"
                        binding.videoCountCard.text = "视频\n${videoCount}"
                    }
                }
                launch {
                    viewModel.scanStatus.collect { status -> renderScanToast(status) }
                }
                launch {
                    // 备份位置状态（数据管理/数据备份已迁移至独立页面，此处仅保留底部状态文字）
                    viewModel.backupDirectoryName.collect { setting ->
                        binding.backupLocationStatusText.text = setting?.value?.let { name ->
                            "数据备份：$name"
                        } ?: "数据备份：未设置"
                    }
                }
            }
        }
    }


    /** 推荐偏好预设：名称 + 描述 + 各维度权重值 */
    private data class RecPrefPreset(
        val name: String,
        val description: String,
        val values: RecommendationPrefs
    )

    private val recPrefPresets = listOf(
        RecPrefPreset("均衡推荐", "标签+时效+发现均衡搭配，适合日常浏览",
            RecommendationPrefs()),
        RecPrefPreset("高记忆流行", "强化浏览时效和互动热度，重温常看内容",
            RecommendationPrefs(tagRelevance = 0.15f, tagCollection = 0.10f, engagement = 0.20f, recency = 0.25f, likeScore = 0.10f, discovery = 0.05f, freshness = 0.05f, browseDepth = 0.05f, maxRandom = 0.10f)),
        RecPrefPreset("深度探索", "强化标签相关性和新发现，挖掘冷门内容",
            RecommendationPrefs(tagRelevance = 0.30f, tagCollection = 0.20f, engagement = 0.05f, recency = 0.05f, likeScore = 0.02f, discovery = 0.30f, freshness = 0.02f, browseDepth = 0.05f, maxRandom = 0.40f)),
        RecPrefPreset("新鲜优先", "强化新鲜度和发现，优先展示最新入库内容",
            RecommendationPrefs(tagRelevance = 0.10f, tagCollection = 0.05f, engagement = 0.05f, recency = 0.10f, likeScore = 0.02f, discovery = 0.25f, freshness = 0.20f, browseDepth = 0.03f, maxRandom = 0.35f))
    )

    private fun showRecommendationPrefsDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val ctx = requireContext()
        val container = SheetUiHelper.sheetContainer(ctx)
        container.addView(SheetUiHelper.sheetTitle(ctx, "推荐偏好"))
        container.addView(SheetUiHelper.sheetSubText(ctx, "选择预设方案快速调整推荐策略"))

        val appPrefsManager = (requireActivity().application as com.qimeng.media.QimengApplication)
            .appContainer.appPrefsManager
        var currentPrefs = appPrefsManager.prefs.value.recommendationPrefs

        // 判断当前偏好匹配哪个预设
        fun matchPreset(prefs: RecommendationPrefs): Int {
            for ((i, preset) in recPrefPresets.withIndex()) {
                if (prefs == preset.values) return i
            }
            return -1
        }

        // 预设行视图列表，用于更新选中样式
        val presetRows = mutableListOf<LinearLayout>()

        container.addView(SheetUiHelper.sheetLabel(ctx, "预设方案"))
        for ((index, preset) in recPrefPresets.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp(ctx), 12.dp(ctx), 16.dp(ctx), 12.dp(ctx))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp(ctx) }
                addView(TextView(ctx).apply {
                    text = preset.name
                    setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
                    textSize = 14f
                })
                addView(TextView(ctx).apply {
                    text = preset.description
                    setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
                    textSize = 12f
                    setPadding(0, 2.dp(ctx), 0, 0)
                })
                setOnClickListener {
                    currentPrefs = preset.values
                    appPrefsManager.updateRecommendationPrefs(currentPrefs)
                    updatePresetSelection(ctx, presetRows, index)
                }
            }
            container.addView(row)
            presetRows.add(row)
        }

        // 初始化选中状态
        val initialSelected = matchPreset(currentPrefs)
        updatePresetSelection(ctx, presetRows, initialSelected)

        dialog.setContentView(container)
        dialog.show()
    }

    /** 更新预设行的选中视觉状态 */
    private fun updatePresetSelection(
        ctx: android.content.Context,
        rows: List<LinearLayout>,
        selectedIndex: Int
    ) {
        val surfaceColor = ctx.resolveThemeColor(R.attr.qmColorSurface)
        val dividerColor = ctx.resolveThemeColor(R.attr.qmColorDivider)
        val primaryColor = ctx.resolveThemeColor(R.attr.qmColorPrimary)
        for ((i, row) in rows.withIndex()) {
            val isSelected = i == selectedIndex
            val gd = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 28f * ctx.resources.displayMetrics.density
                setColor(surfaceColor)
                setStroke(
                    (1 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    if (isSelected) primaryColor else dividerColor
                )
            }
            row.background = gd
        }
    }


    private fun showCompatCheckDialog() {
        val result = com.qimeng.media.core.CompatChecker.check(requireContext())
        val dialog = BottomSheetDialog(requireContext())
        val ctx = requireContext()
        val container = SheetUiHelper.sheetContainer(ctx)
        container.addView(SheetUiHelper.sheetTitle(ctx, "兼容性检查"))

        for (item in result.items) {
            val icon = when (item.status) {
                com.qimeng.media.core.CompatStatus.OK -> "✓"
                com.qimeng.media.core.CompatStatus.WARN -> "⚠"
                com.qimeng.media.core.CompatStatus.ERROR -> "✗"
            }
            container.addView(SheetUiHelper.sheetInfoRow(ctx, "$icon  ${item.name}\n${item.detail}"))
        }

        dialog.setContentView(container)
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderScanToast(status: ScanStatus) {
        val message = when (status) {
            ScanStatus.Idle -> return
            ScanStatus.Running -> "正在扫描常规目录..."
            is ScanStatus.Success -> buildString {
                append("扫描完成：${status.directoryCount} 个目录，共 ${status.count} 个文件")
                // v1.7：大文件/大总量警告
                status.warning?.let { append("\n⚠ $it") }
            }
            is ScanStatus.Error -> "扫描失败：${status.message}"
        }
        if (message != lastScanMessage) {
            lastScanMessage = message
            // 含警告时延长显示时间，确保用户能看到
            val duration = if (status is ScanStatus.Success && status.warning != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(requireContext(), message, duration).show()
        }
    }

    fun scrollToTop() {
        (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, 0)
    }
}
