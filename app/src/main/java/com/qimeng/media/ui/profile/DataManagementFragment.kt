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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qimeng.media.R
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.ScanSourceEntity
import com.qimeng.media.data.db.entity.SettingEntity
import com.qimeng.media.databinding.FragmentDataManagementBinding
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.library.ScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 数据管理独立页面（v1.13）：我的页 → 数据管理。
 * 常规目录 / COS目录 / TXT导入作者 三区块平铺展示，不再使用弹层。
 * 导入备份数据的功能在「数据备份」独立页面（DataBackupFragment），本页不重复提供。
 */
class DataManagementFragment : Fragment() {

    private var _binding: FragmentDataManagementBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }

    private var cachedScanSources: List<ScanSourceEntity> = emptyList()
    private var cachedCosSources: List<ScanSourceEntity> = emptyList()
    private var cachedImportedTxtFiles: List<String> = emptyList()

    private lateinit var openDirectoryLauncher: ActivityResultLauncher<Uri?>
    private lateinit var cosDirectoryLauncher: ActivityResultLauncher<Uri?>
    private lateinit var authorTxtLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            persistReadPermission(uri)
            viewModel.scanDirectory(uri)
        }
        cosDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            persistReadPermission(uri)
            viewModel.scanCosDirectory(uri)
        }
        authorTxtLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            importAuthorsFromTxt(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDataManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val container = binding.contentContainer
        // 三区块平铺：常规目录 / COS目录 / TXT导入作者
        buildScanDirectorySection(container)
        buildCosDirectorySection(container)
        buildTxtImportSection(container)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.scanSources,
                    viewModel.cosScanSources,
                    viewModel.importedTxtFiles
                ) { scanSources, cosSources, txtSetting ->
                    Triple(
                        scanSources.filter { !it.isBackupDirectory && !it.isCosDirectory },
                        cosSources,
                        parseTxtFileList(txtSetting)
                    )
                }.collect { (scanSources, cosSources, txtFiles) ->
                    cachedScanSources = scanSources
                    cachedCosSources = cosSources
                    cachedImportedTxtFiles = txtFiles
                    rebuildSections()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 数据变化时重建三个区块（移除旧区块后按序重建） */
    private fun rebuildSections() {
        val container = binding.contentContainer
        container.removeAllViews()
        buildScanDirectorySection(container)
        buildCosDirectorySection(container)
        buildTxtImportSection(container)
    }

    private fun buildScanDirectorySection(container: LinearLayout) {
        val ctx = requireContext()
        container.addView(SheetUiHelper.sheetSectionTitle(ctx, "📁 常规目录"))

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

        container.addView(SheetUiHelper.sheetActionButton(ctx, "+ 选择文件夹") {
            if (viewModel.scanStatus.value is ScanStatus.Running) {
                Toast.makeText(requireContext(), "正在扫描中，请稍候", Toast.LENGTH_SHORT).show()
                return@sheetActionButton
            }
            try {
                openDirectoryLauncher.launch(null)
            } catch (e: IllegalStateException) {
                AppLog.e("DataMgmt", "openDirectoryLauncher unregistered", e)
                Toast.makeText(requireContext(), "请重试", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun buildCosDirectorySection(container: LinearLayout) {
        val ctx = requireContext()
        container.addView(SheetUiHelper.sheetSectionTitle(ctx, "📁 COS目录"))
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

        container.addView(SheetUiHelper.sheetActionButton(ctx, "+ 选择COS文件夹") {
            if (viewModel.scanStatus.value is ScanStatus.Running) {
                Toast.makeText(requireContext(), "正在扫描中，请稍候", Toast.LENGTH_SHORT).show()
                return@sheetActionButton
            }
            cosDirectoryLauncher.launch(null)
        })
    }

    private fun buildTxtImportSection(container: LinearLayout) {
        val ctx = requireContext()
        container.addView(SheetUiHelper.sheetSectionTitle(ctx, "📄 TXT导入作者"))

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
                        viewModel.refreshTxtImport(fileName) { _, message ->
                            // viewModelScope 不随页面销毁取消，刷新完成时 Fragment 可能已分离，
                            // requireContext() 会抛 IllegalStateException（真机实测崩溃），用可空 context 判空跳过
                            val ctx = context ?: return@refreshTxtImport
                            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                ))
            }
        }

        container.addView(SheetUiHelper.sheetSubText(ctx, "支持编号+作者名+来源+作品格式\n或每行一个作者名的旧格式"))
        container.addView(SheetUiHelper.sheetActionButton(ctx, "+ 选择 TXT 文件") {
            authorTxtLauncher.launch(arrayOf("text/plain"))
        })
    }

    private fun importAuthorsFromTxt(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use {
                        String(it.readBytes(), Charsets.UTF_8)
                    } ?: ""
                }
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

    private fun parseTxtFileList(setting: SettingEntity?): List<String> {
        val json = setting?.value ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            com.qimeng.media.core.AppLog.d("DataMgmt", "parseTxtFileList failed: ${e.message}")
            emptyList()
        }
    }

    companion object {
        fun newInstance(): DataManagementFragment = DataManagementFragment()
    }
}
