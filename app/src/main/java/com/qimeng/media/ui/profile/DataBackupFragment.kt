package com.qimeng.media.ui.profile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qimeng.media.R
import com.qimeng.media.databinding.FragmentDataBackupBinding
import com.qimeng.media.domain.ThumbnailProgress
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.widget.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 数据备份独立页面（v1.13）：我的页 → 数据备份。
 * 备份位置 / 手动同步 / 自定义导出 / 空库恢复提示 + 内嵌「缓存与同步」区块（缩略图缓存进度 + 自动备份开关）。
 * 导入备份数据也在此页：空库提示的"导入恢复"按钮、或选备份目录后自动检测恢复。
 */
class DataBackupFragment : Fragment() {

    private var _binding: FragmentDataBackupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }

    private var cachedBackupName: String? = null
    private lateinit var backupLocationLauncher: ActivityResultLauncher<Uri?>
    private lateinit var importDirectoryLauncher: ActivityResultLauncher<Uri?>

    // v1.15：缓存与同步区块的视图引用与收集器 Job。
    // buildCacheSyncSection 每次 rebuildContent 都会重建视图，若收集器每次也重新 launch 并持有
    // 局部视图引用，会在 viewLifecycleOwner.lifecycleScope 上累积永久收集器（旧收集器闭包持有
    // 已 removeAllViews 的旧视图，switch 状态闪跳）。修复：视图存为字段引用，收集器用 Job 引用
    // 先 cancel 旧的再启动新的，回调经字段安全访问当前视图。
    private var cacheStatusText: android.widget.TextView? = null
    private var cacheProgressBar: android.widget.ProgressBar? = null
    private var autoSyncSwitch: com.google.android.material.switchmaterial.SwitchMaterial? = null
    private var autoSyncDesc: android.widget.TextView? = null
    private var cacheCollectorJob: kotlinx.coroutines.Job? = null
    private var prefsCollectorJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backupLocationLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            saveBackupLocation(uri)
        }
        // "选择导入目录并恢复"：选择备份目录（绮梦影库/ 或其 app数据 子目录）直接导入恢复
        importDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            importFromChosenDirectory(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDataBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val container = binding.contentContainer
        rebuildContent(container)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.backupDirectoryName.collect { setting ->
                        cachedBackupName = setting?.value
                        rebuildContent(binding.contentContainer)
                    }
                }
                launch {
                    viewModel.restoreSuggestion.collect {
                        rebuildContent(binding.contentContainer)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 重建页面内容（备份位置/恢复提示变化时刷新） */
    private fun rebuildContent(container: LinearLayout) {
        container.removeAllViews()
        val ctx = requireContext()
        container.addView(SheetUiHelper.sheetSubText(ctx, "选择文件夹后，将创建「绮梦影库」目录\n备份数据（qimeng_backup.json）将写入其中"))

        // 导入数据区块：选择备份目录直接恢复（不改变已设置的备份位置）
        container.addView(SheetUiHelper.sheetSectionTitle(ctx, "📥 导入数据"))
        container.addView(SheetUiHelper.sheetSubText(ctx, "选择备份目录（绮梦影库/），恢复其中的数据"))
        container.addView(SheetUiHelper.sheetActionButton(ctx, "选择导入目录并恢复") {
            importDirectoryLauncher.launch(null)
        })

        // 空库覆盖防护提示：本地数据为空时自动同步已暂停，建议先导入恢复
        if (viewModel.restoreSuggestion.value) {
            container.addView(SheetUiHelper.sheetSubText(ctx, "⚠ 本地数据为空，已暂停自动同步\n建议先导入恢复，避免空数据覆盖旧备份"))
            container.addView(SheetUiHelper.sheetActionButton(ctx, "导入恢复") {
                importFromCurrentBackup()
            })
        }

        val currentName = cachedBackupName
        if (currentName != null) {
            container.addView(SheetUiHelper.sheetLabel(ctx, "当前备份位置"))
            container.addView(SheetUiHelper.sheetInfoRow(ctx, currentName))

            // 已设置备份位置时，显示"立即同步"按钮
            container.addView(SheetUiHelper.sheetLabel(ctx, "手动同步"))
            container.addView(SheetUiHelper.sheetActionButton(ctx, "立即同步") {
                if (cachedBackupName == null) {
                    Toast.makeText(requireContext(), "请先设置备份位置", Toast.LENGTH_SHORT).show()
                    return@sheetActionButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    Toast.makeText(requireContext(), "正在同步...", Toast.LENGTH_SHORT).show()
                    val app = requireActivity().application as com.qimeng.media.QimengApplication
                    val success = withContext(Dispatchers.IO) {
                        app.appContainer.autoSyncUseCase.triggerManualSync()
                    }
                    // 空库时 triggerManualSync 返回 false 且置 restoreSuggestion=true，
                    // 此时提示"数据为空已暂停"而非"同步失败"，避免误导用户
                    val msg = when {
                        success -> "同步完成"
                        viewModel.restoreSuggestion.value -> "本地数据为空，已暂停同步以防覆盖旧备份，建议先导入恢复"
                        else -> "同步失败，请检查备份目录"
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            })
        } else {
            container.addView(SheetUiHelper.sheetSubText(ctx, "尚未设置备份位置"))
        }

        container.addView(SheetUiHelper.sheetLabel(ctx, "设置备份位置"))
        container.addView(SheetUiHelper.sheetActionButton(ctx, "+ 选择文件夹") {
            backupLocationLauncher.launch(null)
        })

        // 内嵌「缓存与同步」区块：缩略图缓存进度 + 自动备份开关
        buildCacheSyncSection(container)
    }

    /** 从已设置的备份目录导入恢复（空库恢复提示按钮） */
    private fun importFromCurrentBackup() {
        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(requireContext(), "正在导入恢复...", Toast.LENGTH_SHORT).show()
            viewModel.importFromBackupDirectory { count ->
                if (count > 0) {
                    Toast.makeText(requireContext(), "已恢复 $count 个数据文件", Toast.LENGTH_SHORT).show()
                    viewModel.clearRestoreSuggestion()
                } else {
                    Toast.makeText(requireContext(), "恢复失败或无可恢复数据", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * "选择导入目录并恢复"：用户选择备份目录后检测是否有备份，有则确认后恢复。
     * 不改变已设置的备份位置（与"设置备份位置"是两个独立动作）。
     */
    private fun importFromChosenDirectory(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val summary = viewModel.peekBackupSummary(uri)
            if (summary == null || summary.existingFileCount == 0) {
                Toast.makeText(requireContext(), "该目录未找到备份数据", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dateText = summary.exportedAtMillis?.let {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(it))
            } ?: "未知时间"
            val message = "该目录已有备份数据（最近备份：$dateText）：\n" +
                "${summary.mediaFileCount} 个媒体文件 / ${summary.authorCount} 位作者 / " +
                "${summary.tagCount} 个标签 / ${summary.statsCount} 条统计\n\n" +
                "是否导入恢复到本地？"
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("导入数据")
                .setMessage(message)
                .setPositiveButton("导入") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        Toast.makeText(requireContext(), "正在导入恢复...", Toast.LENGTH_SHORT).show()
                        viewModel.importFromDirectoryUri(uri) { count ->
                            if (count > 0) {
                                Toast.makeText(requireContext(), "已恢复 $count 个数据文件", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "恢复失败或无可恢复数据", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 选择备份目录：创建「绮梦影库」目录、保存位置、立即写入一份全量备份、检测旧备份提示恢复 */
    private fun saveBackupLocation(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                persistWritePermission(uri)
                // 在选择的文件夹下创建"绮梦影库"目录（备份主文件 qimeng_backup.json 写入其根目录）
                val qmDirUri = withContext(Dispatchers.IO) {
                    val rootDir = DocumentFile.fromTreeUri(requireContext(), uri) ?: throw Exception("无法访问目录")
                    val qmDir = rootDir.findFile("绮梦影库") ?: rootDir.createDirectory("绮梦影库")
                        ?: throw Exception("无法创建绮梦影库目录")
                    qmDir.uri
                }
                viewModel.saveBackupDirectory(qmDirUri, "绮梦影库") {
                    // 设置成功后立即写入一份全量备份（单文件 qimeng_backup.json）
                    viewLifecycleOwner.lifecycleScope.launch {
                        Toast.makeText(requireContext(), "正在写入备份...", Toast.LENGTH_SHORT).show()
                        val app = requireActivity().application as com.qimeng.media.QimengApplication
                        val success = withContext(Dispatchers.IO) {
                            app.appContainer.autoSyncUseCase.triggerManualSync()
                        }
                        Toast.makeText(requireContext(),
                            if (success) "备份位置已设置，数据已写入 qimeng_backup.json"
                            else "备份位置已设置，但写入失败",
                            Toast.LENGTH_SHORT).show()
                    }
                    // 检测备份目录是否已有可恢复数据，有则提示用户导入恢复
                    // 用途：卸载重装后选了旧备份目录，App 主动提示合并旧数据，避免画像断档
                    viewLifecycleOwner.lifecycleScope.launch {
                        val summary = viewModel.peekBackupSummary(qmDirUri)
                        if (summary != null && summary.existingFileCount > 0) {
                            // 防重复：同一目录只提示一次（取消也算知情，不再打扰）
                            val prefs = requireContext().getSharedPreferences("restore_hint_prefs", android.content.Context.MODE_PRIVATE)
                            val hintKey = "hint_shown_$qmDirUri"
                            if (!prefs.getBoolean(hintKey, false)) {
                                withContext(Dispatchers.Main) { showRestoreConfirmDialog(summary, hintKey) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "设置失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 恢复确认弹窗：检测到备份目录有可恢复数据时弹出，提示用户是否导入恢复。
     * 用户确认或取消后都标记 hintKey 已展示，避免同一目录重复打扰。
     */
    private fun showRestoreConfirmDialog(
        summary: com.qimeng.media.backup.BackupSummary,
        hintKey: String
    ) {
        val dateText = summary.exportedAtMillis?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(it))
        } ?: "未知时间"
        val message = "该目录已有备份数据（最近备份：$dateText）：\n" +
            "${summary.mediaFileCount} 个媒体文件 / ${summary.authorCount} 位作者 / " +
            "${summary.tagCount} 个标签 / ${summary.statsCount} 条统计\n\n" +
            "是否导入恢复到本地？\n\n" +
            "（取消则只作为新的备份位置，不恢复旧数据）"

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("检测到备份数据")
            .setMessage(message)
            .setPositiveButton("导入恢复") { _, _ ->
                // 标记已展示（即使用户确认，也不再重复弹）
                requireContext().getSharedPreferences("restore_hint_prefs", android.content.Context.MODE_PRIVATE)
                    .edit { putBoolean(hintKey, true) }
                importFromCurrentBackup()
            }
            .setNegativeButton("取消") { _, _ ->
                // 取消也标记已展示，表示用户知情，不再重复打扰
                requireContext().getSharedPreferences("restore_hint_prefs", android.content.Context.MODE_PRIVATE)
                    .edit { putBoolean(hintKey, true) }
            }
            .show()
    }

    /** 缓存与同步区块：缩略图缓存实时进度 + 自动备份开关（原 ProfileFragment 内嵌逻辑迁移） */
    private fun buildCacheSyncSection(container: LinearLayout) {
        val ctx = requireContext()
        container.addView(SheetUiHelper.sheetSectionTitle(ctx, "💾 缓存与同步"))

        // 缩略图缓存进度（实时更新）
        val cacheStatusText = android.widget.TextView(ctx).apply {
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
            textSize = 14f
            setPadding(18.dp(ctx), 12.dp(ctx), 18.dp(ctx), 4.dp(ctx))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(cacheStatusText)
        this.cacheStatusText = cacheStatusText

        val progressBar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                8.dp(ctx)
            ).apply { leftMargin = 18.dp(ctx); rightMargin = 18.dp(ctx); bottomMargin = 8.dp(ctx) }
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 4f
                    setColor(ctx.resolveThemeColor(R.attr.qmColorDivider))
                },
                android.graphics.drawable.ClipDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 4f
                        setColor(ctx.resolveThemeColor(R.attr.qmColorPrimary))
                    },
                    android.view.Gravity.START,
                    android.graphics.drawable.ClipDrawable.HORIZONTAL
                )
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
        }
        container.addView(progressBar)
        this.cacheProgressBar = progressBar

        // 实时观察进度：收集器只维护一个（先 cancel 旧的再启动），回调经字段引用更新当前视图
        cacheCollectorJob?.cancel()
        cacheCollectorJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.thumbnailProgress.collect { progress ->
                    val statusText = cacheStatusText ?: return@collect
                    val bar = cacheProgressBar ?: return@collect
                    when (progress) {
                        is ThumbnailProgress.Idle -> {
                            statusText.text = "缩略图缓存：空闲"
                            bar.progress = 0
                        }
                        is ThumbnailProgress.Running -> {
                            val percent = if (progress.total > 0) progress.done * 100 / progress.total else 0
                            statusText.text = "缩略图缓存（${progress.source.label}）：$percent%（${progress.done}/${progress.total}）"
                            bar.progress = percent
                        }
                        is ThumbnailProgress.Done -> {
                            statusText.text = "缩略图缓存（${progress.source.label}）：已完成（${progress.total} 个文件）"
                            bar.progress = 100
                        }
                    }
                }
            }
        }

        // 自动同步开关
        val appPrefsManager = (requireActivity().application as com.qimeng.media.QimengApplication).appContainer.appPrefsManager

        // 开关区域：标题行 + 说明行
        val switchContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(ctx), 12.dp(ctx), 18.dp(ctx), 12.dp(ctx))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val switchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val switchLabel = android.widget.TextView(ctx).apply {
            text = "自动备份"
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switch = com.google.android.material.switchmaterial.SwitchMaterial(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        switchRow.addView(switchLabel)
        switchRow.addView(switch)
        this.autoSyncSwitch = switch

        val switchDesc = android.widget.TextView(ctx).apply {
            text = "数据变化时自动写入备份目录"
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
            textSize = 12f
            setPadding(0, 2.dp(ctx), 0, 0)
        }
        switchContainer.addView(switchRow)
        switchContainer.addView(switchDesc)
        this.autoSyncDesc = switchDesc
        container.addView(switchContainer)

        // 自动同步开关状态：收集器只维护一个（先 cancel 旧的再启动），回调经字段引用更新当前视图
        prefsCollectorJob?.cancel()
        prefsCollectorJob = viewLifecycleOwner.lifecycleScope.launch {
            appPrefsManager.prefs.collect { prefs ->
                val switch = autoSyncSwitch ?: return@collect
                val desc = autoSyncDesc ?: return@collect
                switch.setOnCheckedChangeListener(null)
                switch.isChecked = prefs.autoSync
                desc.text = if (prefs.autoSync) "已开启 · 数据变化时自动写入备份目录" else "未开启 · 数据仅保存在本地"
                switch.setOnCheckedChangeListener { _, isChecked ->
                    appPrefsManager.updateAutoSync(isChecked)
                }
            }
        }
    }

    private fun persistWritePermission(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    companion object {
        fun newInstance(): DataBackupFragment = DataBackupFragment()
    }
}
