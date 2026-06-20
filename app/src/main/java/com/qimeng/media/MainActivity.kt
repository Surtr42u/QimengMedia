package com.qimeng.media

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.qimeng.media.core.AppLog
import com.qimeng.media.databinding.ActivityMainBinding
import com.qimeng.media.ui.album.AlbumFragment
import com.qimeng.media.ui.all.AllFilesFragment
import com.qimeng.media.ui.album.AlbumDetailFragment
import com.qimeng.media.ui.author.AuthorListFragment
import com.qimeng.media.ui.author.AuthorFilesFragment
import com.qimeng.media.ui.favorite.FavoriteFragment
import com.qimeng.media.ui.history.BrowseHistoryFragment
import com.qimeng.media.ui.detail.MediaDetailFragment
import com.qimeng.media.ui.main.HomeFragment
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.profile.ProfileFragment
import com.qimeng.media.ui.search.SearchFragment
import com.qimeng.media.ui.stats.StatsDetailFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var detailSourceRecordKeys: List<String> = emptyList()
    private var detailMode = false
    private var lastTabId = 0
    private var lastTabClickTime = 0L

    private val fragmentCache = mutableMapOf<Int, Fragment>()
    private var lastNightMode = 0
    // Activity 重建时（savedInstanceState != null），系统恢复 bottomNavigation.selectedItemId
    // 可能触发 setOnItemSelectedListener，导致 showCachedFragment 误执行 popBackStack 清空覆盖页面。
    // 用此标志位在重建期间跳过 listener 的默认行为，保留 BackStack 中的覆盖页面（如详情页）。
    private var isRestoringState = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            // 延迟3秒启动扫描，让UI先稳定渲染
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                ViewModelProvider(this)[MediaLibraryViewModel::class.java].autoRefreshAllSources()
            }, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredTheme()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lastNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeColors()

        // M8: 配置变更后 fragmentCache 中的 Fragment 引用已失效，必须清空
        if (savedInstanceState != null) {
            fragmentCache.clear()
            // 标记正在重建状态，防止系统恢复 selectedItemId 时 listener 误触发 showCachedFragment
            // 清空覆盖页面 BackStack（如详情页），导致详情页上方出现底部 tab
            isRestoringState = true
        }

        // L7: 恢复 detailSourceRecordKeys
        detailSourceRecordKeys = savedInstanceState?.getStringArrayList("detail_source_keys")?.toList() ?: emptyList()

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            if (!detailMode) {
                val systemBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            }
            insets
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            // Activity 重建期间，系统恢复 selectedItemId 会触发此 listener。
            // 此时 BackStack 中可能还有覆盖页面（如详情页），不应执行 showCachedFragment
            // （其中 popBackStack(INCLUSIVE) 会清空覆盖页面，导致详情页上方出现底部 tab）。
            // 直接返回 true 让 selectedItemId 恢复生效，但不切换 Fragment。
            if (isRestoringState) {
                isRestoringState = false
                return@setOnItemSelectedListener true
            }

            val now = System.currentTimeMillis()
            val isDoubleTap = item.itemId == lastTabId && (now - lastTabClickTime) < 400
            lastTabId = item.itemId
            lastTabClickTime = now

            if (isDoubleTap) {
                scrollToTop()
                true
            } else {
                when (item.itemId) {
                    R.id.navigation_home -> showCachedFragment(R.id.navigation_home) { HomeFragment() }
                    R.id.navigation_all -> showCachedFragment(R.id.navigation_all) { AllFilesFragment() }
                    R.id.navigation_album -> showCachedFragment(R.id.navigation_album) { AlbumFragment() }
                    R.id.navigation_stats -> showCachedFragment(R.id.navigation_stats) { com.qimeng.media.ui.stats.DataStatsFragment() }
                    R.id.navigation_profile -> showCachedFragment(R.id.navigation_profile) { ProfileFragment() }
                    else -> false
                }
            }
        }

        supportFragmentManager.addOnBackStackChangedListener {
            syncBottomNavVisibilityWithBackStack()
            val topFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            AppLog.d("MainActivity", "BackStackChanged: topFragment=${topFragment?.javaClass?.simpleName}, bottomNav=${binding.bottomNavigation.visibility}")
        }

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.navigation_home
        }

        // 请求媒体权限，权限获取后延迟启动自动刷新（避免扫描写入数据库触发Room Flow导致UI闪烁）
        requestMediaPermissions()
    }

    private fun requestMediaPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needsRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needsRequest) {
            permissionLauncher.launch(permissions)
        } else {
            // 延迟3秒启动扫描，让UI先稳定渲染
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                ViewModelProvider(this)[MediaLibraryViewModel::class.java].autoRefreshAllSources()
            }, 3000)
        }
    }

    override fun onResume() {
        super.onResume()
        applyThemeColors()
        // 重置重建标志位：listener 若未被触发（selectedItemId 未变），此处兜底重置，
        // 防止后续用户首次点击 tab 被误判为重建回调而忽略。
        isRestoringState = false
        // 修复：App 从后台恢复时，bottomNavigation.visibility 可能被系统重置为 VISIBLE，
        // 但 BackStackChangedListener 不会触发（BackStack 没变化），导致详情页上层显示外面 tab。
        // 这里主动同步一次 BackStack 状态，确保详情页等全屏 Fragment 在前台时 tab 隐藏。
        syncBottomNavVisibilityWithBackStack()
    }

    /** 根据 BackStack 栈顶 Fragment 类型同步 bottomNavigation 可见性 */
    private fun syncBottomNavVisibilityWithBackStack() {
        val topFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        binding.bottomNavigation.visibility = when {
            topFragment is MediaDetailFragment ||
            topFragment is AuthorListFragment ||
            topFragment is AuthorFilesFragment ||
            topFragment is AlbumDetailFragment ||
            topFragment is BrowseHistoryFragment ||
            topFragment is FavoriteFragment ||
            topFragment is SearchFragment ||
            topFragment is StatsDetailFragment -> View.GONE
            else -> View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("detail_source_keys", ArrayList(detailSourceRecordKeys))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val newNight = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (newNight != lastNightMode) {
            lastNightMode = newNight
            recreate()
        }
    }

    private fun showCachedFragment(tabId: Int, factory: () -> Fragment): Boolean {
        val fragment = fragmentCache.getOrPut(tabId) { factory() }
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()
        // 隐藏所有当前可见的Tab Fragment
        fragmentCache.values.forEach { f ->
            if (f.isAdded && !f.isHidden) transaction.hide(f)
        }
        if (fragment.isAdded) {
            // 已添加过，直接show
            transaction.show(fragment)
        } else {
            // 首次添加
            fm.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            transaction.add(R.id.fragmentContainer, fragment)
        }
        transaction.commit()
        return true
    }

    private fun scrollToTop() {
        val selectedTabId = binding.bottomNavigation.selectedItemId
        val topFragment = fragmentCache[selectedTabId]
        when (topFragment) {
            is HomeFragment -> topFragment.scrollToTop()
            is AllFilesFragment -> topFragment.scrollToTop()
            is AlbumFragment -> topFragment.scrollToTop()
            is com.qimeng.media.ui.stats.DataStatsFragment -> topFragment.scrollToTop()
            is ProfileFragment -> topFragment.scrollToTop()
        }
    }

    fun showDetailFragment(recordKey: String, sourceRecordKeys: List<String> = emptyList()) {
        detailSourceRecordKeys = sourceRecordKeys
        binding.bottomNavigation.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .add(R.id.fragmentContainer, MediaDetailFragment.newInstance(recordKey))
            .addToBackStack(null)
            .commit()
    }

    fun currentDetailSourceRecordKeys(): List<String> = detailSourceRecordKeys

    fun showAuthorList() {
        binding.bottomNavigation.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .add(R.id.fragmentContainer, AuthorListFragment())
            .addToBackStack(null)
            .commit()
    }

    fun showAuthorFiles(authorId: String, isCos: Boolean = false) {
        binding.bottomNavigation.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .add(R.id.fragmentContainer, AuthorFilesFragment.newInstance(authorId, isCos))
            .addToBackStack(null)
            .commit()
    }

    fun showAlbumDetail(albumName: String, isCos: Boolean = false) {
        binding.bottomNavigation.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .add(R.id.fragmentContainer, AlbumDetailFragment.newInstance(albumName, isCos))
            .addToBackStack(null)
            .commit()
    }

    fun showHistory() {
        binding.bottomNavigation.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .add(R.id.fragmentContainer, BrowseHistoryFragment())
            .addToBackStack(null)
            .commit()
    }

    fun showFavorites() {
        binding.bottomNavigation.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .add(R.id.fragmentContainer, FavoriteFragment())
            .addToBackStack(null)
            .commit()
    }

    /** 进入统计详情页（mode 见 StatsDetailFragment.MODE_*） */
    fun showStatsDetail(mode: String, timeRange: String) {
        binding.bottomNavigation.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .add(R.id.fragmentContainer, StatsDetailFragment.newInstance(mode, timeRange))
            .addToBackStack(null)
            .commit()
    }

    fun showSearchFragment() {
        binding.bottomNavigation.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .add(R.id.fragmentContainer, SearchFragment())
            .addToBackStack(null)
            .commit()
    }

    fun getBottomNavigationInfo(): String {
        return "visibility=${binding.bottomNavigation.visibility}/height=${binding.bottomNavigation.height}/width=${binding.bottomNavigation.width}"
    }

    fun setDetailMode(active: Boolean) {
        detailMode = active
        if (active) {
            binding.main.setPadding(0, 0, 0, 0)
        } else {
            val insets = ViewCompat.getRootWindowInsets(binding.main)
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            if (insets != null) {
                binding.main.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            }
        }
    }

    private fun applyStoredTheme() {
        setTheme(R.style.Theme_绮梦影库)
        // 项目设计决策（v1.10 起）：仅跟随手机系统明暗模式，不调用 setDefaultNightMode()。
        // 系统 DayNight 自动选择 values/values-night，无需 App 内手动切换。
    }

    @Suppress("DEPRECATION")
    fun applyThemeColors() {
        // 直接从 ?attr 解析颜色（跟随系统明暗模式，values/values-night 自动切换）
        val c = ThemeHelper.resolve(this)
        val lightBars = !isNightMode()
        window.statusBarColor = c.bg
        window.navigationBarColor = c.surface
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }
}
