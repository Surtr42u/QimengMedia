package com.qimeng.media.ui.profile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.ScanSourceEntity
import com.qimeng.media.data.db.entity.SettingEntity
import com.qimeng.media.data.prefs.RecommendationPrefs
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.ui.widget.dp
import com.qimeng.media.databinding.FragmentProfileBinding
import com.qimeng.media.domain.ThumbnailProgress
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.library.ScanStatus
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }
    private var lastScanMessage: String = ""
    private var cachedScanSources: List<ScanSourceEntity> = emptyList()
    private var cachedCosSources: List<ScanSourceEntity> = emptyList()
    private var cachedAuthors: List<AuthorEntity> = emptyList()
    private var cachedImportedTxtFiles: List<String> = emptyList()
    private var cachedBackupName: String? = null
    private lateinit var openDirectoryLauncher: ActivityResultLauncher<Uri?>
    private lateinit var backupLocationLauncher: ActivityResultLauncher<Uri?>
    private lateinit var authorTxtLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var cosDirectoryLauncher: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            persistReadPermission(uri)
            viewModel.scanDirectory(uri)
        }
        backupLocationLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            saveBackupLocation(uri)
        }
        authorTxtLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            importAuthorsFromTxt(uri)
        }
        cosDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            persistReadPermission(uri)
            viewModel.scanCosDirectory(uri)
        }
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
            showDataManagementDialog()
        }

        binding.compatCheckRow.setOnClickListener {
            showCompatCheckDialog()
        }

        binding.themeRow.setOnClickListener {
            Toast.makeText(requireContext(), "已跟随手机明暗模式", Toast.LENGTH_SHORT).show()
        }

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
                    viewModel.backupDirectoryName.collect { setting ->
                        cachedBackupName = setting?.value
                        binding.backupLocationStatusText.text = setting?.value?.let { name ->
                            "数据备份：$name"
                        } ?: "数据备份：未设置"
                    }
                }
                launch {
                    viewModel.scanSources.collect { sources ->
                        cachedScanSources = sources.filter { !it.isBackupDirectory && !it.isCosDirectory }
                    }
                }
                launch {
                    viewModel.cosScanSources.collect { sources ->
                        cachedCosSources = sources
                    }
                }
                launch {
                    viewModel.authors.collect { authors ->
                        cachedAuthors = authors
                    }
                }
                launch {
                    viewModel.importedTxtFiles.collect { setting ->
                        cachedImportedTxtFiles = parseTxtFileList(setting)
                    }
                }
                launch {
                    viewModel.thumbnailProgress.collect { _ ->
                        // 缩略图进度现在在缓存与同步弹窗中显示
                    }
                }
                launch {
                    val appPrefsManager = (requireActivity().application as com.qimeng.media.QimengApplication).appContainer.appPrefsManager
                    appPrefsManager.prefs.collect { prefs ->
                        cachedAutoSync = prefs.autoSync
                    }
                }
            }
        }
    }

    private fun saveBackupLocation(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                persistWritePermission(uri)
                // 在选择的文件夹下创建"绮梦影库"目录结构
                val (prefsDirUri, appDataDirUri) = withContext(Dispatchers.IO) {
                    val rootDir = DocumentFile.fromTreeUri(requireContext(), uri) ?: throw Exception("无法访问目录")
                    val qmDir = rootDir.findFile("绮梦影库") ?: rootDir.createDirectory("绮梦影库")
                        ?: throw Exception("无法创建绮梦影库目录")
                    val prefsDir = qmDir.findFile("个人偏好") ?: qmDir.createDirectory("个人偏好")
                        ?: throw Exception("无法创建个人偏好目录")
                    val appDataDir = qmDir.findFile("app数据") ?: qmDir.createDirectory("app数据")
                        ?: throw Exception("无法创建app数据目录")
                    Pair(prefsDir.uri, appDataDir.uri)
                }
                // 保存绮梦影库目录作为备份位置（复用上方 IO 线程中已创建的目录）
                val qmDir = DocumentFile.fromTreeUri(requireContext(), uri)
                    ?.findFile("绮梦影库")
                if (qmDir == null) {
                    Toast.makeText(requireContext(), "无法找到绮梦影库目录", Toast.LENGTH_LONG).show()
                    return@launch
                }
                viewModel.saveBackupDirectory(qmDir.uri, "绮梦影库") {
                    // 设置成功后立即导出偏好数据到"个人偏好"子文件夹
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val db = com.qimeng.media.data.db.AppDatabase.getInstance(requireContext())
                            val manager = com.qimeng.media.backup.BackupManager(requireContext())
                            val success = manager.exportPersonalPrefsToFile(prefsDirUri, db)
                            if (success) {
                                Toast.makeText(requireContext(), "备份位置已设置，偏好数据已导出", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "备份位置已设置，但偏好导出失败", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "偏好导出失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "设置失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDataManagementDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val ctx = requireContext()
        val container = SheetUiHelper.sheetContainer(ctx)
        container.addView(SheetUiHelper.sheetTitle(ctx, "数据管理"))
        container.addView(SheetUiHelper.sheetAction(ctx, "常规目录") {
            showScanDirectoryDialog()
        })
        container.addView(SheetUiHelper.sheetAction(ctx, "COS目录") {
            showCosDirectoryDialog()
        })
        container.addView(SheetUiHelper.sheetAction(ctx, "TXT导入作者") {
            showTxtImportDialog()
        })
        container.addView(SheetUiHelper.sheetAction(ctx, "数据备份") {
            showBackupLocationDialog()
        })
        container.addView(SheetUiHelper.sheetAction(ctx, "缓存与同步") {
            showCacheSyncDialog()
        })
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showScanDirectoryDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val ctx = requireContext()
        val container = SheetUiHelper.sheetContainer(ctx)
        container.addView(SheetUiHelper.sheetTitle(ctx, "常规目录"))

        val sources = cachedScanSources
        if (sources.isEmpty()) {
            container.addView(SheetUiHelper.sheetSubText(ctx, "暂无已添加的目录"))
        } else {
            container.addView(SheetUiHelper.sheetLabel(ctx, "已添加目录"))
            for (source in sources) {
                container.addView(SheetUiHelper.sheetSourceRow(ctx, source.displayName, source.uriString,
                    onDelete = {
                        viewModel.deleteScanSource(source.uriString)
                        Toast.makeText(requireContext(), "已移除：${source.displayName}", Toast.LENGTH_SHORT).show()
                    },
                    onRefresh = {
                        viewModel.refreshScanSource(source.uriString)
                        Toast.makeText(requireContext(), "正在增量刷新：${source.displayName}", Toast.LENGTH_SHORT).show()
                    }
                ))
            }
        }

        container.addView(SheetUiHelper.sheetLabel(ctx, "添加新目录"))
        container.addView(SheetUiHelper.sheetActionButton(ctx, "+ 选择文件夹") {
            if (viewModel.scanStatus.value is ScanStatus.Running) {
                Toast.makeText(requireContext(), "正在扫描中，请稍候", Toast.LENGTH_SHORT).show()
                return@sheetActionButton
            }
            try {
                openDirectoryLauncher.launch(null)
            } catch (e: IllegalStateException) {
                AppLog.e("Profile", "openDirectoryLauncher unregistered", e)
                Toast.makeText(requireContext(), "请重试", Toast.LENGTH_SHORT).show()
            }
        })

        dialog.setContentView(container)
        dialog.show()
    }

    private fun showCosDirectoryDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val ctx = requireContext()
        val container = SheetUiHelper.sheetContainer(ctx)
        container.addView(SheetUiHelper.sheetTitle(ctx, "COS目录"))
        container.addView(SheetUiHelper.sheetSubText(ctx, "选择COS文件夹，按 作者/作品/图片 三层结构扫描"))

        val sources = cachedCosSources
        if (sources.isEmpty()) {
            container.addView(SheetUiHelper.sheetLabel(ctx, "暂无COS目录"))
        } else {
            container.addView(SheetUiHelper.sheetLabel(ctx, "已添加COS目录"))
            for (source in sources) {
                container.addView(SheetUiHelper.sheetSourceRow(ctx, source.displayName, source.uriString,
                    onDelete = {
                        viewModel.deleteCosScanSource(source.uriString)
                        Toast.makeText(requireContext(), "已移除：${source.displayName}", Toast.LENGTH_SHORT).show()
                    },
                    onRefresh = {
                        viewModel.refreshCosSource(source.uriString)
                        Toast.makeText(requireContext(), "正在增量刷新：${source.displayName}", Toast.LENGTH_SHORT).show()
                    }
                ))
            }
        }

        container.addView(SheetUiHelper.sheetLabel(ctx, "添加COS目录"))
        container.addView(SheetUiHelper.sheetActionButton(ctx, "+ 选择COS文件夹") {
            if (viewModel.scanStatus.value is ScanStatus.Running) {
                Toast.makeText(requireContext(), "正在扫描中，请稍候", Toast.LENGTH_SHORT).show()
                return@sheetActionButton
            }
            cosDirectoryLauncher.launch(null)
        })

        dialog.setContentView(container)
        dialog.show()
    }

    private fun showBackupLocationDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val ctx = requireContext()
        val container = SheetUiHelper.sheetContainer(ctx)
        container.addView(SheetUiHelper.sheetTitle(ctx, "数据备份"))
        container.addView(SheetUiHelper.sheetSubText(ctx, "选择文件夹后，将创建「绮梦影库」目录\n├ 个人偏好/（偏好JSON+报告TXT）\n└ app数据/（自动同步的数据库JSON）"))

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
                    Toast.makeText(requireContext(),
                        if (success) "同步完成" else "同步失败，请检查备份目录",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        } else {
            container.addView(SheetUiHelper.sheetSubText(ctx, "尚未设置备份位置"))
        }

        container.addView(SheetUiHelper.sheetLabel(ctx, "设置备份位置"))
        container.addView(SheetUiHelper.sheetActionButton(ctx, "+ 选择文件夹") {
            backupLocationLauncher.launch(null)
        })

        dialog.setContentView(container)
        dialog.show()
    }

    private fun showTxtImportDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val ctx = requireContext()
        val container = SheetUiHelper.sheetContainer(ctx)
        container.addView(SheetUiHelper.sheetTitle(ctx, "TXT导入作者"))

        val txtFiles = cachedImportedTxtFiles
        if (txtFiles.isEmpty()) {
            container.addView(SheetUiHelper.sheetSubText(ctx, "暂无已导入的TXT文件"))
        } else {
            container.addView(SheetUiHelper.sheetLabel(ctx, "已导入文件（${txtFiles.size}）"))
            for (fileName in txtFiles) {
                container.addView(SheetUiHelper.sheetSourceRow(ctx, fileName, fileName,
                    onDelete = {
                        viewModel.removeImportedTxtFile(fileName)
                        Toast.makeText(requireContext(), "已移除：$fileName", Toast.LENGTH_SHORT).show()
                    },
                    onRefresh = {
                        // 直接刷新：从URI重新读取文件，失败则用缓存重新匹配
                        viewModel.refreshTxtImport(fileName) { fromFile, message ->
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        }
                    }
                ))
            }
        }

        container.addView(SheetUiHelper.sheetLabel(ctx, "导入新文件"))
        container.addView(SheetUiHelper.sheetSubText(ctx, "支持编号+作者名+来源+作品格式\n或每行一个作者名的旧格式"))
        container.addView(SheetUiHelper.sheetActionButton(ctx, "+ 选择 TXT 文件") {
            authorTxtLauncher.launch(arrayOf("text/plain"))
        })
        dialog.setContentView(container)
        dialog.show()
    }

    private fun importAuthorsFromTxt(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = requireContext().contentResolver.openInputStream(uri)?.use {
                    String(it.readBytes(), Charsets.UTF_8)
                } ?: ""
                val fileName = getFileName(uri)
                // 持久化URI读取权限，用于后续刷新
                persistReadPermission(uri)
                viewModel.importAuthorsFromText(text, fileName, uri.toString())
                Toast.makeText(requireContext(), "作者导入完成", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导入失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) return it.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "unknown.txt"
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
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

    private fun renderScanToast(status: ScanStatus) {
        val message = when (status) {
            ScanStatus.Idle -> return
            ScanStatus.Running -> "正在扫描常规目录..."
            is ScanStatus.Success -> "扫描完成：${status.directoryCount} 个目录，共 ${status.count} 个文件"
            is ScanStatus.Error -> "扫描失败：${status.message}"
        }
        if (message != lastScanMessage) {
            lastScanMessage = message
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private var cachedAutoSync = false



    fun scrollToTop() {
        (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, 0)
    }

    private fun showCacheSyncDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val ctx = requireContext()
        val container = SheetUiHelper.sheetContainer(ctx)
        container.addView(SheetUiHelper.sheetTitle(ctx, "缓存与同步"))

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

        // 实时观察进度
        val progressJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.thumbnailProgress.collect { progress ->
                when (progress) {
                    is ThumbnailProgress.Idle -> {
                        cacheStatusText.text = "缩略图缓存：空闲"
                        progressBar.progress = 0
                    }
                    is ThumbnailProgress.Running -> {
                        val percent = if (progress.total > 0) progress.done * 100 / progress.total else 0
                        cacheStatusText.text = "缩略图缓存（${progress.source.label}）：$percent%（${progress.done}/${progress.total}）"
                        progressBar.progress = percent
                    }
                    is ThumbnailProgress.Done -> {
                        cacheStatusText.text = "缩略图缓存（${progress.source.label}）：已完成（${progress.total} 个文件）"
                        progressBar.progress = 100
                    }
                }
            }
        }

        // 自动同步开关
        val appPrefsManager = (requireActivity().application as com.qimeng.media.QimengApplication).appContainer.appPrefsManager

        // 开关区域：标题行 + 说明行
        val switchContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(ctx), 12.dp(ctx), 18.dp(ctx), 12.dp(ctx)
            )
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

        val switchDesc = android.widget.TextView(ctx).apply {
            text = "数据变化时自动写入备份目录"
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
            textSize = 12f
            setPadding(0, 2.dp(ctx), 0, 0)
        }
        switchContainer.addView(switchRow)
        switchContainer.addView(switchDesc)
        container.addView(switchContainer)

        viewLifecycleOwner.lifecycleScope.launch {
            appPrefsManager.prefs.collect { prefs ->
                switch.setOnCheckedChangeListener(null)
                switch.isChecked = prefs.autoSync
                switchDesc.text = if (prefs.autoSync) "已开启 · 数据变化时自动写入备份目录" else "未开启 · 数据仅保存在本地"
                switch.setOnCheckedChangeListener { _, isChecked ->
                    appPrefsManager.updateAutoSync(isChecked)
                }
            }
        }

        dialog.setOnDismissListener { progressJob.cancel() }
        dialog.setContentView(container)
        dialog.show()
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

    private fun parseTxtFileList(setting: SettingEntity?): List<String> {
        val json = setting?.value ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            com.qimeng.media.core.AppLog.d("Profile", "parseTxtFileList failed: ${e.message}")
            emptyList()
        }
    }

}
