# GUIDE_UI - UI 与页面结构

## 实现路径

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/qimeng/media/MainActivity.kt` | 单 Activity + 底部导航 + fragment 路由（`showDetailFragment` 等） |
| `app/src/main/java/com/qimeng/media/ThemeHelper.kt` | 运行时主题色解析 |
| `app/src/main/java/com/qimeng/media/ui/main/HomeFragment.kt` | 首页：推荐/排行榜/筛选 |
| `app/src/main/java/com/qimeng/media/ui/search/SearchFragment.kt` | 搜索：三层状态（空/补全/结果）、搜索历史、推荐搜索、补全推荐（`SearchSuggestAdapter`） |
| `app/src/main/java/com/qimeng/media/ui/all/AllFilesFragment.kt` | 全部：日期分组/缩放列数/筛选 |
| `app/src/main/java/com/qimeng/media/ui/album/AlbumFragment.kt` | 相册出处列表（长按修改分区名） |
| `app/src/main/java/com/qimeng/media/ui/album/AlbumDetailFragment.kt` | 相册出处详情（角色/类型筛选+药丸+COS支持） |
| `app/src/main/java/com/qimeng/media/ui/album/SourceMatcher.kt` | 出处匹配引擎（SourceGroup规范名+变体，三层优先级匹配） |
| `app/src/main/java/com/qimeng/media/ui/profile/ProfileFragment.kt` | 我的：扫描状态/推荐偏好/兼容性检查/数据管理与数据备份入口行 |
| `app/src/main/java/com/qimeng/media/ui/profile/DataManagementFragment.kt` | 数据管理独立页：常规目录/COS目录/TXT导入作者 平铺（v1.13） |
| `app/src/main/java/com/qimeng/media/ui/profile/DataBackupFragment.kt` | 数据备份独立页：备份位置/同步/导入恢复/缓存与同步（v1.13，v2.0 移除自定义导出） |
| `app/src/main/java/com/qimeng/media/ui/stats/DataStatsFragment.kt` | 数据统计页：专业数据报表（总览数字卡/浏览趋势/常看文件/常看作者标签/分布入口，v1.14 时间范围全局联动） |
| `app/src/main/java/com/qimeng/media/core/CompatChecker.kt` | 兼容性检查工具（6项检查） |
| `app/src/main/java/com/qimeng/media/ui/detail/ZoomImageView.kt` | 自定义缩放 ImageView（**智能分层渲染**：`applyOptimalLayerType()` 按图尺寸选层，GIF→`LAYER_TYPE_HARDWARE`，普通图长边≤4096（硬件渲染安全阈值）→`LAYER_TYPE_HARDWARE`（GPU直渲），>4096→`LAYER_TYPE_SOFTWARE`（保正确性，超大图GPU驱动无法正确应用matrix缩放）；手势期间用 `shouldUseHardwareForCurrent()` 同阈值判断；`lastDrawableWidth/Height` 记录实际尺寸防止Coil降采样导致matrix错乱；缩放范围 0.5x~5x，双击放大1.8x，近1.0的scale factor跳过减少无效重绘） |
| `app/src/main/java/com/qimeng/media/ui/detail/BiliPlayerView.kt` | B站风格视频播放器（手势控制/倍速/静音/进度拖拽） |
| `app/src/main/java/com/qimeng/media/core/LargeImageDecoder.kt` | 大图软解优化（libspng PNG 加速/decodeFileDescriptor 直接解码/并发限制/预加载请求构建） |
| `app/src/main/java/com/qimeng/media/core/GpuInfo.kt` | GPU 纹理上限探测（EGL14 创建临时 context 查 `GL_MAX_TEXTURE_SIZE`，供 ZoomImageView 智能分层 + ImageLoader maxBitmapSize 配置用；探测失败回退 4096） |
| `app/src/main/java/com/qimeng/media/core/SpngDecoder.kt` | libspng JNI 桥接（PNG 解码加速，ARM NEON 优化） |
| `app/src/main/java/com/qimeng/media/ui/browser/MediaGroupHelper.kt` | 出处/角色/COS 分组计算（AllFiles/Favorite/BrowseHistory/AlbumDetail/AuthorFiles 共用） |
| `app/src/main/java/com/qimeng/media/ui/browser/MediaPillsHelper.kt` | 出处/角色/分区/类型药丸渲染（AllFiles/Favorite/BrowseHistory/AlbumDetail/AuthorFiles 共用） |
| `app/src/main/java/com/qimeng/media/ui/browser/MediaRenderHelper.kt` | 渲染共享计算工具（applyTypeFilter/computeDisplayed/buildFingerprint，5 个 Fragment 共用） |
| `app/src/main/res/layout/fragment_media_detail.xml` | 详情页布局：`BiliPlayerView` + ZoomImageView + 透明覆盖层 + chrome |
| `app/src/main/java/com/qimeng/media/ui/favorite/FavoriteFragment.kt` | 收藏页（分区/作品/角色/类型筛选+药丸+COS芯片） |
| `app/src/main/res/layout/fragment_favorite.xml` | 收藏页布局 |
| `app/src/main/java/com/qimeng/media/ui/adapter/MediaThumbnailAdapter.kt` | 缩略图列表适配器（Coil + DiffUtil） |
| `app/src/main/java/com/qimeng/media/ui/adapter/GroupedMediaAdapter.kt` | 日期分组适配器（复用 MediaThumbnailAdapter.Holder） |
| `app/src/main/java/com/qimeng/media/ui/browser/MediaBrowserLogic.kt` | 推荐/排行/筛选/日期分组逻辑/`SearchContext`（出处/角色/作者/作品名搜索匹配） |
| `app/src/main/java/com/qimeng/media/ui/browser/MediaFilterSheet.kt` | 底部筛选面板（万能筛选，`FilterConfig` 控制可见筛选项） |
| `app/src/main/java/com/qimeng/media/ui/author/AuthorListFragment.kt` | 作者列表（全部/常规/COS胶囊筛选+按名搜索+排序） |
| `app/src/main/java/com/qimeng/media/ui/author/AuthorFilesFragment.kt` | 作者文件浏览页（作品/角色/类型筛选+药丸+COS支持+日期分组/缩放列数） |
| `app/src/main/java/com/qimeng/media/ui/history/BrowseHistoryFragment.kt` | 浏览历史（分区/作品/角色/类型筛选+药丸+COS芯片） |
| `app/src/main/java/com/qimeng/media/ui/library/MediaLibraryViewModel.kt` | 共享 ViewModel |
| `app/src/main/java/com/qimeng/media/ui/widget/PressAnimation.kt` | 按下反馈动画扩展函数 `View.addPressAnimation()`（缩放 0.92→1.0，100ms） |

## 职责

管理 App UI 布局、页面结构、交互逻辑和组件规范。

不管：数据实体定义（详见 GUIDE_DATA.md「目标实体」）、推荐算法细节（详见 GUIDE_ALGORITHM.md「推荐算法」）、主题颜色定义（详见 GUIDE_THEME.md「颜色字段」）

## 导航结构

底部导航五 Tab（`MainActivity.setupActions` → `showCachedFragment`）：首页、全部、相册、数据、我的。Tab切换使用 `show/hide` 方式（非 `replace`），所有Tab Fragment同时存活，切换时只切换可见性，无闪烁无重建。

双击当前 Tab 回到顶部：`setOnItemSelectedListener` 检测 400ms 内同一 Tab 二次点击，调用 `scrollToTop()`。`scrollToTop()` 通过 `fragmentCache[selectedTabId]` 定位当前可见 Fragment（不用 `findFragmentById`，因 `add/hide` 模式下多 Fragment 共存同一容器，`findFragmentById` 可能返回非当前 Tab 的 Fragment）。RecyclerView 类 Fragment（首页、全部）使用 `stopScroll()` + `LinearLayoutManager.scrollToPositionWithOffset(0, 0)` 确保远距离滚动后也能精确回到顶部（`scrollToPosition(0)` 在滚动距离过大时不可靠）；ScrollView 类 Fragment（相册、我的）使用 `smoothScrollTo(0, 0)`。

覆盖页面（`add` 叠加，不 destroy 底层 Fragment）：详情页、作者列表、作者文件浏览页、相册详情、浏览历史、收藏页、COS专区、搜索页

覆盖页面入栈时底部导航隐藏（`addOnBackStackChangedListener` 中判断 topFragment 类型设 `GONE`），返回后自动恢复 `VISIBLE`。`MainActivity.onResume()` 中调用 `syncBottomNavVisibilityWithBackStack()` 重新同步底部导航可见性——App 从后台恢复时 BackStack 未变化、`BackStackChangedListener` 不触发，但 `bottomNavigation.visibility` 可能被系统重置为 `VISIBLE`，导致详情页等覆盖页面上层显示外面 tab；该方法与 `BackStackChangedListener` 共用同一逻辑，确保两种路径状态一致。`syncBottomNavVisibilityWithBackStack()` 覆盖所有覆盖页面类型：`MediaDetailFragment`/`AuthorListFragment`/`AuthorFilesFragment`/`AlbumDetailFragment`/`BrowseHistoryFragment`/`FavoriteFragment`/`SearchFragment`/`StatsDetailFragment`。Activity 重建时（`savedInstanceState != null`）使用 `isRestoringState` 标志位防止系统恢复 `selectedItemId` 触发 `setOnItemSelectedListener` 误执行 `showCachedFragment`（其中 `popBackStack(INCLUSIVE)` 会清空覆盖页面 BackStack）。

返回列表时底层 fragment 保持存活，无闪烁。

## 芯片栏配置对比

各页面的"分区/作品/角色"芯片栏配置不同，是按页面功能定位设计的。**所有页面统一调度于全部页的算法**（`MediaGroupHelper` + `MediaPillsHelper`），差异仅在于芯片栏配置。详见 `GUIDE_CODE_MAINTENANCE.md`「共享胶囊筛选体系」。

| 页面 | 左侧芯片 | 右侧芯片 | 说明 |
|---|---|---|---|
| 全部页 | 分区 (N) / 作品 (N) / 角色 (N) / 类型 (N) | — | 芯片文字带选项数量（不含"全部"和"收起"）；"类型"芯片展开后显示"全部/图片/视频/动图"药丸；"分区"芯片展示"全部 (N) / 常规 (M) / COS (K)"药丸，可折叠 |
| 收藏页 | 分区 (N) / 作品 (N) / 角色 (N) / 类型 (N) | — | 同全部页 |
| 浏览历史 | 分区 (N) / 作品 (N) / 角色 (N) / 类型 (N) | — | 同全部页 |
| 作者文件浏览页(常规) | 作品 (N) / 角色 (N) / 类型 (N) | — | **无"分区"**——已锁定作者维度；算法与全部页一致（MediaGroupHelper） |
| 作者文件浏览页(COS) | 角色 (N) / 类型 (N) | — | **无"分区"和"作品"**——COS作者出处=作者已锁定；角色按作品名分组（groupByCosWork） |
| 相册详情页(常规) | 角色 (N) / 类型 (N) | — | **无"分区"和"作品"**——相册已按出处分组，进入详情页已锁定出处；算法与全部页一致（MediaGroupHelper） |
| 相册详情页(COS) | 角色 (N) / 类型 (N) | — | **无"分区"和"作品"**——COS相册出处=作者已锁定；角色按作品名分组（groupByCosWork） |
| 首页 | 推荐 / COS / 排行榜 | — | 独立的三 tab 切换，不是分区/出处/角色体系 |
| 作者管理 | 全部 (N) / 常规 (N) / COS (N) | 排序 ▾ | 胶囊切换筛选作者类型，排序对所有分类生效 |

**设计原则**：
- 芯片文字显示选项数量（如"作品 (12)"），数量不含"全部"和"收起"药丸
- "分区"芯片统一用于切换"全部/常规/COS"分区，带数量显示和折叠功能
- "类型"芯片点击展开药丸区域，显示"全部/图片/视频/动图"筛选药丸，与作品/角色药丸共用同一药丸区域
- 相册页自带出处分组，详情页不需要再加"作品"和"分区"芯片
- 折叠时药丸区域完全隐藏，不显示摘要行

## 下拉刷新

- 首页、全部、收藏、浏览历史、作者管理、作者文件浏览均支持 `SwipeRefreshLayout` 下拉刷新
- 首页/全部：重置排序缓存并重新观察数据流
- 首页推荐：B站式刷新，下拉时 `refreshSeed++` 强制重新推荐，清空所有 tab 的排序缓存（`tabSortFingerprints`/`tabSortedItems`/`tabDisplayCounts`），推荐结果通过 `.shuffled()` 全量随机重排，确保每次下拉内容明显变化；视频图片自然混合（`balanceVideoImage`，按比例随机取，不做连续分组）
- **左右滑动切换 tab 不重新推荐**：每个 tab（推荐/COS/排行榜）独立维护排序缓存，左右滑动切换 tab 时恢复之前的排序结果和滚动位置，不触发重新推荐
- **熄屏亮屏/底部 Tab 切换不刷新**：`observeData()` 使用 `repeatOnLifecycle(STARTED)` 重新收集 Flow 时，不清空排序缓存和渲染指纹，数据未变化时 `render()` 跳过重排和 UI 更新
- **点赞后返回自动重排、浏览退出保持原样（v1.15）**：render fingerprint 追加 `likeVersion` 维度——详情页点赞（likePrefsListener 递增 likeVersion）后返回首页强制重排，排行/推荐随之更新；**不纳入浏览统计**——普通浏览（viewCount/时长）退出后首页保持原样，避免"浏览→返回"被统计变化触发自动重排导致内容跳动
- 收藏：重新从 SharedPreferences 读取收藏键并匹配媒体
- 浏览历史/作者管理：下拉即刷新，数据由 Room Flow 自动驱动
- **SwipeRefreshLayout 系统栏 inset**：SwipeRefreshLayout 根布局**不要**设置 `android:fitsSystemWindows="true"`，否则会与 `MainActivity` 主容器的 inset padding 重复应用，导致内容下沉一个状态栏高度。系统栏 padding 统一由 `MainActivity` 的 `setOnApplyWindowInsetsListener` 在主容器层处理，与数据页/相册页（ScrollView 根布局）保持一致。涉及布局：`fragment_home.xml`、`fragment_all_files.xml`、`fragment_author_files.xml`、`fragment_browse_history.xml`、`fragment_favorite.xml`

## 首页

布局：`res/layout/fragment_home.xml`
- 顶部一行：`[首页标题] [搜索框] [筛选图标] [网格图标]`
- 网格图标随列数切换：1列显示 `ic_grid_1`，2列显示 `ic_grid_2`
- 二级 chip：推荐 / COS / 排行榜（三选一 tab，左右横滑切换），3 个胶囊切换按钮通过 `addPressAnimation()` 添加按下缩放反馈
- 排行榜 chip：日榜 / 周榜 / 月榜 / 年榜
- COS chip：切换到 COS 推荐模式，只显示 `isCosFile=true` 的媒体
- 双列 RecyclerView（`MediaThumbnailAdapter`，Coil 加载，`itemViewCacheSize=20`）
- **距底部 ≤6 项提前增量加载下一页，不重排**（v1.15：原"滚动到底增量加载"在触底最后一帧同步 +20 条并立即批量 bind，新条目视频双重解码 + 预生成抢 IO 集中在那帧 → 明显"卡一下"；改提前加载让解码发生在滑动中途被动画掩盖）
- 左右横滑切换推荐/COS/排行榜：使用 `GestureDetector.onFling` 检测快速水平滑动，阈值 50dp + 速度 300
- 点击缩略图 → `MainActivity.showDetailFragment()` → `add` 模式，传当前页面已分页加载显示的文件列表（`adapter.currentList`，非完整排序列表 `tabSortedItems[currentTab]`），详情页仅可左右滑动浏览当前推荐页已可见的文件，数目与推荐页显示一致
- 搜索框不可聚焦，点击跳转独立搜索界面（`SearchFragment`，通过 `MainActivity.showSearchFragment()` add 模式）
- **搜索范围**：搜索时合并常规+COS文件（`cachedMedia + cachedCosMedia`），非搜索时按当前 tab 选择数据源
- **搜索匹配维度**：除文件名/文件夹名/标签外，通过 `SearchContext` 额外匹配出处名（SourceMatcher）、角色名（SourceMatcher.matchAll）、COS作者名（MediaGroupHelper.findCosAuthorForMedia）、COS作品名（MediaGroupHelper.findCosCharacterForMedia）
- 点赞计数缓存机制详见 `GUIDE_DATA.md`「点赞」章节
- 每日推荐去重 `dailyShownCountMap`（`Map<String, Int>`）：已展示的文件下次推荐被 -0.8 惩罚，让新文件排到前面；下拉刷新时保留 map 不清空，每日零点自动清空

## 搜索页

布局：`res/layout/fragment_search.xml`
- 顶部搜索栏：`[返回] [搜索输入框] [搜索按钮]`
- **三层状态切换**：
  - **空状态（STATE_EMPTY）**：搜索框下方显示「推荐搜索」ChipGroup + 「搜索历史」ChipGroup（有历史时显示，含清除按钮）
  - **补全状态（STATE_SUGGEST）**：输入文字时显示补全推荐列表（RecyclerView，一行一个，从短到长排序），每行左侧搜索图标+文字+右侧类型标签（出处/角色/COS作者/COS作品）
  - **结果状态（STATE_RESULT）**：搜索结果网格（3列 RecyclerView + 空提示），搜索栏保留搜索词
- **补全推荐机制**：预计算名字索引（`nameIndex`，数据变化时通过 `rebuildNameIndexAsync()` 在 `Dispatchers.Default` 异步重建），输入时 O(m) 匹配名字列表不遍历文件，最多显示 10 条；点击补全项自动填入搜索框并触发搜索
- **名字索引缓存**：`cachedNameIndex`（companion object）跨实例复用，`cachedDataFingerprint`（各列表 size + 前64条 recordKey hashCode）精确检测数据变化，数据未变时跳过重建直接使用缓存索引，避免每次打开搜索页重复遍历所有文件做 SourceMatcher 匹配
- **搜索索引预构建**：HomeFragment 数据加载完成后在 `Dispatchers.Default` 后台调用 `SearchFragment.prebuildNameIndex()` 预构建索引，用户点击搜索时 `cachedNameIndex` 已就绪，首次打开也无延迟
- **SearchContext 缓存**：`cachedSearchContextCache` 与 nameIndex 同步构建并缓存，`doSearch()` 优先使用缓存避免重复遍历文件做 SourceMatcher 匹配；`buildNameIndex()` 合并为一次 `matchAll()` 遍历同时提取出处和角色
- **搜索历史**：SharedPreferences 存储（`search_history`），最多 20 条，去重后最新在前
- **推荐搜索**：从名字索引随机取 10 条作为推荐；`onViewCreated` 中优先用 `cachedNameIndex` 立即填充（消除首次视觉延迟），数据流到达后指纹未变则跳过重建
- **结果状态下点击搜索栏**：切回补全状态，可修改搜索词
- **返回导航**：结果→空状态（清空搜索词）→补全→空状态→空状态→返回首页（popBackStack）
- **搜索匹配维度**：文件名/文件夹名/标签 + `SearchContext`（出处名、角色名、COS作者名、COS作品名）
- **搜索结果**：使用 `GroupedMediaAdapter`（与全部页一致，日期分组+3列网格），搜索和 `buildSearchContext()` 均在 `Dispatchers.Default` 异步执行；支持双指缩放列数 2-5 列（`ScaleGestureDetector`）
- **键盘适配**：搜索页使用 `SOFT_INPUT_ADJUST_NOTHING`，键盘弹出时不调整布局，避免底部 tab 显示在键盘上方；退出搜索页恢复 `SOFT_INPUT_ADJUST_RESIZE`。底部导航通过 `BackStackChangedListener` 中 `SearchFragment` 判断隐藏（`GONE`），确保键盘弹出时底部 tab 不可见

## 全部页

布局：`res/layout/fragment_all_files.xml`
- 标题 + 统计行 + 筛选图标 + 列数图标
- 列数图标随列数切换：2列 `ic_grid_2`、3列 `ic_grid_3`、4列 `ic_grid_4`、5列 `ic_grid_5`
- 芯片栏：分区 / 作品 / 角色 / 类型
- **分区模式**：直接列出各数据源分区药丸（「全部 (N)」所有文件 + 「常规 (M)」普通文件 + 「COS (K)」COS文件），点击切换选中状态（与出处/角色模式一致）；**默认「全部」高亮**（不是"默认/常规"），「全部」显示常规+COS合并后的所有文件，「常规」只显示非COS文件，选中COS后只显示COS文件；「全部」模式下使用 `mergeGroups()` 合并常规和COS的分组结果，相同 key（如"其他"）的文件列表会拼接而非覆盖；以后可扩展更多分区
- **类型模式**：点击"类型"芯片展开药丸区域，显示"全部 (N) / 图片 (M) / 视频 (K) / 动图 (L)"药丸，点击药丸筛选对应类型；折叠时显示当前类型名（如"图片 ▼"）
- **出处模式**：非COS分区按出处分组（SourceMatcher.match()），COS分区按作者名分组（通过 AuthorMediaCrossRef 关联 cos_ 前缀 authorId → AuthorEntity.displayName）；显示作品药丸筛选栏，未选作品时按分组标题显示，选作品后只显示该作品文件；点击已选中的作品药丸可取消选择
- **角色模式**：非COS分区按角色分组（SourceMatcher.matchCharacter()），COS分区按作品名分组（CosWorkEntity.workName，通过 folderName 匹配作品名）；作品和角色递归筛选——选了作品后角色只显示该作品下的角色
- **筛选联动规则**：分区→类型→作品→角色 逐层联动。作品和角色药丸的计数和显示都基于类型筛选后的文件，选类型后作品药丸只显示该类型下有文件的作品，角色药丸只显示该作品+该类型下有文件的角色
- 每个作品/角色独立显示药丸，不合并单文件作品到"其他"；"其他"药丸始终排在列表最下面（不参与数量排序），位于"收起 ▲"之前
- 日期分组 RecyclerView（`GroupedMediaAdapter` + `GridLayoutManager` + `SpanSizeLookup`）
- **异步渲染**：`render()` 只做编排（立即隐藏药丸栏 + 启动协程），耗时计算在 `computeAllGroupsAsync()` 的 `Dispatchers.Default` 中执行（类型筛选 `MediaRenderHelper.applyTypeFilter` + 缓存键判定 + 出处/角色分组 `MediaGroupHelper.groupBySource/groupByCharacter/groupByCosAuthor/groupByCosWork` + `MediaBrowserLogic.applyFilter`），协程外 `updateAllUI()` 只做 UI 更新（药丸渲染 + `MediaRenderHelper.buildFingerprint` 指纹判定 + adapter 提交）；收起胶囊时在协程外立即隐藏 scroller 避免旧UI残留
- **collect 块不重复 render**（2026-06-22 修复）：数据变化 collect 回调中只调用一次 `render()`（经 fingerprint 检查），不再先调 `computeSourceGroups()` 再调 `render()`——`computeSourceGroups()` 已退化为 `render()` 的空壳委托，两者连调会导致同一 fingerprint 的 render 执行两次（实测全部页首次进入浪费 210ms，每次数据更新浪费 ~47ms）。`computeSourceGroups()` 保留供分区药丸点击回调（`renderPartitionPills` 内）触发 render
- **分组缓存**：`cachedSourceGroups`/`cachedCharGroups` 通过缓存键（`sourceGroupsKey`/`charGroupsKey`，由 partition+filterType+dataVersion+selectedSources 组成）判断是否需要重算。点击胶囊项只更新选中状态+filter，不重新 groupBy；仅数据变化（`dataVersion++`）、filterType 变化、selectedSources 变化时才重算分组。COS 5518 文件场景下首次 groupBy 约 600-800ms，缓存命中后 render 降至 <50ms
- 双指缩放列数 2-5 列（`ScaleGestureDetector`）
- **列数持久化（v1.15）**：双指缩放结束与列数按钮切换都会把新列数写入 `AppPrefsManager`（首页→`updateGridColumnsHome`，全部/浏览历史/作者文件/收藏→`updateGridColumnsAll`，相册详情→`updateGridColumnsAlbum`），各页初始化时从 `app_prefs.json` 读取作为初始值，重启后保留
- 筛选面板（`MediaFilterSheet`）：标签区域支持选择/取消选择标签、长按删除标签（从数据库删除标签选项及所有关联）、"+ 添加标签"按钮创建新标签
- `fingerprint` 机制（`MediaRenderHelper.buildFingerprint`）：viewMode + partitions + displayed.size + displayed.firstRecordKey + selectedSources + selectedChars + filterType + typePillsExpanded + 分组摘要（SOURCE 模式含 sourceGroups entries，CHARACTER 模式含 charGroups entries）
- **数据指纹补 tags 维度（v1.15）**：全部页 `mediaFingerprint` 追加 tags（recordKey:tagId）维度（对齐 AuthorFilesFragment），标签增删（详情页标签管理）后返回全部页自动重渲染；**不纳入浏览统计（stats）**——全部页按日期/出处分组不按热度排序，浏览只改变 viewCount 不影响列表内容，纳入会导致"浏览退出→返回"无意义重渲染（作者文件页同此策略）
- **列表提交异步化（v1.15）**：全部/收藏/浏览历史/作者文件四页的 `submitMediaWithGroups`/`submitMedia` 已改为 `GroupedMediaAdapter` 的 `submitMediaWithGroupsAsync`/`submitMediaAsync` 变体（分组行组装与指纹计算在 `Dispatchers.Default`，主线程只提交），大库下不再卡顿
- 空状态：无数据时不显示任何提示文本，保持空白

## 详情页

布局：`res/layout/fragment_media_detail.xml`
- 详情页按 4 层组织：纯背景层、透明媒体层、上下轻量图标操作层、沉浸隐藏层
- `detailContentContainer`（FrameLayout）包裹媒体视图（ZoomImageView + BiliPlayerView + videoTouchOverlay + videoPlayButton），左右滑动切换时仅该容器做平移动画，顶栏和底栏不动
- chrome 显示态跟随系统白天/夜间主题，白天浅底深色图标，夜间黑底浅色图标；chrome 隐藏态始终黑底沉浸
- 图片（含 GIF 动图）：`ZoomImageView`，`showMediaAt()` 中 `MediaType.IMAGE` 和 `MediaType.ANIMATED_IMAGE` 均走图片分支（隐藏视频播放器 UI），GIF 动图使用 `AnimatedImageDecoder` 加载动画帧；使用 Coil `size(Size.ORIGINAL)` 加载原图（**设计决策：详情页始终显示完整原图，不降采样**，用户点击查看的就是原始分辨率图片），淡入淡出滑动；始终 edge-to-edge 全屏布局，系统栏显隐不触发布局变化，避免图片重新居中；**智能分层渲染**（2026-06-20 优化，2026-06-21 修复超大图渲染阈值，2026-06-23 去重）：`ZoomImageView.setImageDrawable` 调用 `applyOptimalLayerType()` 按图尺寸智能选层（`setImageBitmap` 不再单独调用，由 `super.setImageBitmap` 链式触发的 `setImageDrawable` 统一负责，消除同图二次判断冗余）——GIF（`AnimatedImageDrawable`）→ `LAYER_TYPE_HARDWARE`（动画依赖硬件刷新帧）；普通图长边 ≤ **硬件渲染安全阈值 4096**（`HARDWARE_RENDER_SAFE_SIZE`）→ `LAYER_TYPE_HARDWARE`（GPU 直渲，4096 图绘制从 ~50ms 降到 ~5ms，消除来回切大图卡顿）；普通图长边 > 4096 → `LAYER_TYPE_SOFTWARE`（CPU 软件渲染，正确应用 MATRIX 缩放）。**⚠️ 阈值选择根因**（2026-06-21 修复）：原方案用 `GpuInfo.maxTextureSize()`（探测 `GL_MAX_TEXTURE_SIZE`，旗舰机实测可达 16384）作阈值，但这是 OpenGL 理论上限，厂商 GPU 驱动对超大 bitmap（如 8000x8000=256MB）无法正确应用 MATRIX 缩放——超大图走 HARDWARE 时 matrix 失效，图按原始像素从左上角绘制（表现为左上角小方块、不居中）。4096 是业界公认稳定值（Glide/Fresco 默认上限，Android Canvas 软件层 Skia MAXMIMUM_BITMAP_SIZE=32766 远大于此不会崩），与 `GpuInfo` 探测失败回退值 `DEFAULT_MAX_TEXTURE_SIZE=4096` 一致。手势期间（双指缩放/拖拽）同样用 `shouldUseHardwareForCurrent()` **同 4096 阈值**判断，超大图手势时保持 SOFTWARE。不再使用 `Bitmap.Config.HARDWARE`（原 ≤4096px 用 HARDWARE 的方案会导致 HARDWARE Bitmap 在 SOFTWARE 层上无法绘制，大图白屏卡死）；**两个独立阈值需区分**：①渲染安全阈值 4096（`HARDWARE_RENDER_SAFE_SIZE`，HARDWARE/SOFTWARE 分层判断，保渲染正确性）②Coil maxBitmapSize = `gpuMax`（`GpuInfo.maxTextureSize()`，解码降采样上限，放开让 8192 解原图）。两者独立，改其一不影响另一个；**Coil maxBitmapSize 放开**（2026-06-20）：Coil3 默认 `maxBitmapSize=4096` 会把超大图降采样（8192→4096），偏离"始终原图"决策。`QimengApplication` ImageLoader 配置 `.maxBitmapSize(Size(gpuMax, gpuMax))` 放开到 GPU 上限，让 8192/7680 等超大图显示原图。代价是内存上升（超大图原图 ARGB_8888 ~150MB/张，浏览多张超大图 Native Heap 峰值可达 ~900MB），靠 Coil LRU 35% + onTrimMemory + 超大图预渲染收紧控制；**超大图 SOFTWARE 渲染 trade-off**（2026-06-21）：超大图（>4096）走 SOFTWARE 比 HARDWARE 慢（CPU 逐像素处理，8000x8000 量级可能 100ms+），但保证 matrix 缩放正确、显示位置正常。保正确性优先于渲染速度，这是已知 trade-off；**缩放范围**：0.5x~5x，双击放大1.8x；**性能优化**：近1.0的scale factor跳过减少无效重绘，`requestDisallowInterceptTouchEvent` 手势期间只调用一次；退出详情页时取消所有加载/预加载请求、释放ImageView drawable，确保全尺寸bitmap被回收
- 视频（仅 `MediaType.VIDEO`）：未播放时用 `ZoomImageView` 显示视频帧预览（透明背景，透出主题色，与图片分支一致；优先使用 `MediaMetadataRetriever` 提取首帧，失败时回退到 Coil VideoFrameDecoder），中央显示 ▶ 播放按钮；单击预览区直接启动播放（进入 BiliPlayerView），横滑浏览其他文件。播放时 BiliPlayerView 接管触摸
- **视频播放幂等（v1.15）**：`startVideoPlayback` 开头以 `if (isVideoPlaying) return` 做幂等保护，双击/快速重复点击不会重复 setVideoUri 重建 MediaItem 导致播放被重置
- **点赞/收藏写入即触发自动同步（v1.15）**：`toggleLike`/`toggleFavorite` 写入 SharedPreferences 后调用 `viewModel.triggerAutoSyncIfNeeded()`（30 秒防抖），不再等退出详情页才落盘
- 按返回键：播放中先退到 chrome 浏览模式（暂停+chrome显示），再按退回首页
- 按需解码媒体元数据（`showInfoSheet`）：**v1.15 防重复弹窗 + 先 show 再补解码 + 占位行原地更新 + 无"完成"按钮**——`infoSheetShowing` 标志防解码期间重复点击堆叠多个对话框；解码协程改 `viewLifecycleOwner.lifecycleScope`（Fragment 退栈后自动取消，不再操作已销毁视图）；先展示已有行与"解码中…"占位，解码完成后**原地替换占位行文本**（`addInfoRow` 返回 value TextView 引用，避免追加重复"尺寸"行）；详细信息弹窗**无"完成"按钮**（BottomSheet 下滑手势 / 点外部空白关闭即可，显式关闭按钮冗余，v1.0 历史遗留已删除；`sheetDone` 保留供标签管理/快速转跳弹窗使用）。
- 时间轴标签弹窗（v1.15）：打开添加标记弹窗前暂停播放，dismiss 后恢复（`wasPlaying` 快照）——标记时间点与实际画面一致，不再因播放中弹窗而偏移。
- 顶部操作层：返回（胶囊图标） / 当前序号 / 信息（胶囊图标），背景使用 `bg_detail_top_gradient` 渐变遮罩（从 `qmColorBg` 90% 不透明度渐变到透明），与背景层一体无分割感，点击信息图标弹出 BottomSheet 显示文件名/出处/尺寸/时长等详细信息（区分图片和视频）；尺寸/时长等元数据在扫描时未解码（`scanTreeFast` 跳过），打开信息面板时按需解码并缓存到数据库
- 底部操作层：点赞（胶囊图标）/ 收藏（胶囊图标）/ 标签（胶囊图标）/ 快速转跳（胶囊图标），四个图标均匀分布居中，背景使用 `bg_detail_bottom_gradient` 渐变遮罩（从透明渐变到 `qmColorBg` 90% 不透明度），过长图片时 tab 用渐变背景遮挡图片内容而非透明覆盖；收藏图标区分空心/实心状态；标签点击弹出 BottomSheet 标签管理，当前文件标签以水平滚动标签列表显示在上方（权重最高），其他可用标签以水平滚动列表显示在下方，点击关闭图标仅移除文件与标签关联不删除标签选项，输入框可添加新标签；快速转跳点击弹出 BottomSheet 显示当前文件关联的作者列表，点击作者名跳转到作者文件浏览页
- **按钮按下反馈动画**：详情页 6 个按钮（返回/info/点赞/收藏/标签/快速转跳）通过 `View.addPressAnimation()` 扩展函数添加按下缩放反馈（ACTION_DOWN 缩放 0.92，ACTION_UP 回弹 1.0，100ms `AccelerateDecelerateInterpolator`），增强触控感知
- 弹窗：`BottomSheetDialog`，颜色统一用 `resolveThemeColor()` 跟随主题
- 单击显隐 chrome（alpha 动画）
- 左右滑动切换：`moveBy()` 先调用 `showMediaAt()` 更新内容，再对 `detailContentContainer` 做 200ms DecelerateInterpolator 滑入动画，确保新内容立即可见
- 预加载：`preloadAround()` 双向预加载，基础 1 前 2 后（阅读 D 时预渲染 C/D/E/F），内存充裕时 +1 每侧（2 前 3 后）；**超大图收紧**（2026-06-20 首版：当前图长边 > 4096（`HUGE_IMAGE_THRESHOLD`）时收紧到 1 前 1 后；**v1.12 升级为逐项限量**：改为对窗口内每项判断是否超大图——邻项本身是超大图（8192≈150MB/张）时窗口内最多预载 `MAX_PRELOAD_HUGE=1` 张（距当前图最近的优先），内存紧张（native 堆已用 ≥60%，`Debug.getNativeHeapAllocatedSize`）时跳过全部超大图预载；普通图预加载数量不受影响（仍 1前2后 / 内存充裕 2前3后））；每次切换取消所有旧预加载并重新预加载范围内所有项（Coil 内部缓存去重，已命中的项预加载会直接跳过，不会重复解码）；退出详情页时统一取消预加载；**placeholder 保留画面**：所有 Coil load 设置 `placeholder(当前drawable)`，加载期间保留旧画面不清空，根治来回滑动闪白；**Coil 缓存上限**：堆的 35%；**主线程同步缓存检查**（2026-06-19 优化）：`showImage()` 先在主线程同步检查 Coil 内存缓存（LruCache 线程安全），命中则直接 `loadOriginalImageWithCoil` 走 Coil load，跳过 `LargeImageDecoder` 的 `withContext(Dispatchers.IO)` 线程切换开销（40MB+ 超大图场景下省 13-34ms）；未命中才异步走 `LargeImageDecoder` 解码；**大图解码优化**（`LargeImageDecoder`，绕过 Coil Decoder 链的自定义解码器）：内部解码逐级回退——Coil 内存缓存命中（仅检查是否存在，不取出 Bitmap 对象——避免缓存驱逐时 recycle 导致 ImageView 绘制已回收 bitmap 崩溃；命中时返回 null 让 Fragment 走 Coil load 路径，Coil 命中内存缓存零延迟且正确管理 bitmap 生命周期）→ PNG 用 `SpngDecoder`（libspng，ARM NEON 优化，比系统 libpng 快 2-3 倍）→ `BitmapFactory.decodeFileDescriptor`（跳过 InputStream 中间层，比 Coil 的 decodeStream 快 ~10-20%）→ `BitmapFactory.decodeStream`（异常回退）；`LargeImageDecoder` 全部失败则由 Fragment 回退到 Coil 标准流程；解码成功后写入 Coil 内存缓存供后续访问零延迟；并发解码信号量限制（大图同时解码不超过 2 张，防止内存峰值）；预加载使用 Coil 标准流程（`size(Size.ORIGINAL)` + `memoryCacheKey(recordKey)`），自动利用内存/磁盘缓存；**视频帧取首帧**：详情页视频预览和预加载统一用 `videoFrameMillis(0)` 取首帧，避免 3 秒帧 seek 开销（详见 GUIDE_DEBUG.md 性能约束）；**视频预览缓存命中**（2026-06-22 优化）：`showVideo()` 与 `showImage()` 同策略，先在主线程同步检查 Coil 内存缓存（预加载已将视频帧写入缓存时命中），命中则直接 `loadVideoFrameWithCoil` 走 Coil load 零延迟显示，跳过 `MediaMetadataRetriever` 的 IO 线程打开+解码开销（消除"图片滑到视频卡一下"）；未命中才异步走 `loadVideoFrame`（MediaMetadataRetriever）；**滑动防抖**：`moveBy()` 设置 `isTransitioning` 后延迟 200ms 重置，覆盖异步加载窗口防止快速连续滑动竞态；**切换不闪白**：`showImage()`/`showVideo()` 不立即清空当前画面，保留旧图直到新内容加载完成；**bitmap 回收防护**：所有 `setImageBitmap` 调用前检查 `!bitmap.isRecycled`，已回收 bitmap 自动回退到 Coil load；**诊断日志**（2026-06-20 新增，消除 GUIDE_DEBUG 观察盲区）：`showImage()` 缓存命中分支打 `Detail: showImage: 命中内存缓存 key=xxx`，未命中分支打 `缓存未命中，异步解码` + 解码完成 `解码完成 key=xxx 耗时=Nms 尺寸=WxH`（耗时用 `SystemClock.uptimeMillis()` 包住 `decodeCurrentImage`）/ 回退 `回退Coil key=xxx 耗时=Nms`；`preloadAround()` 入口打 `preload: 范围[start..end] 当前=index 内存充裕=B 入队=N`；`LargeImageDecoder` 各回退路径打 `LargeImage: libspng/fileDescriptor 解码成功|失败`、`回退到 Coil 标准流程`。日志样例与命中率诊断方法详见 GUIDE_DEBUG.md「缩略图/详情页不显示诊断」

## BiliPlayerView 视频播放器

- 基于 Media3 ExoPlayer + PlayerView（`useController=false`），自定义 B站风格控制器
- 文件：`ui/detail/BiliPlayerView.kt`
- 控制器 UI：顶部栏（返回按钮）、底部栏（播放/暂停、静音按钮、SeekBar进度条、当前时间/总时间、右侧按钮组：时间轴标签图标、倍速文字按钮、快速转跳图标、全屏按钮）
- 时间轴标签（与文件标签完全独立，不同实体/表/DAO/备份JSON，互不影响）：底部栏进度条上方显示标签芯片（横向滚动），点击跳转到标记位置，长按弹出操作菜单（跳转/删除）；时间轴标签图标按钮弹出 BottomSheet 添加标签对话框，含快捷标记（❤️ 喜欢/⭐ 收藏）和自定义输入；预设标签芯片有对应颜色区分（红色/金色半透明背景）
- 手势控制（竖屏）：单击切换播放/暂停、双击无功能、水平滑动拖拽进度（同时显示进度条+视频内容跟随seek）
- 手势控制（横屏）：单击显隐控制器、双击切换播放/暂停（B站横屏逻辑）；进入横屏默认隐藏控制器
- 长按倍速：长按屏幕2倍速播放，松手恢复原速；竖屏长按时下方出现"⬇ 拖动到此处锁定倍速"提示（B站竖屏逻辑），拖动到该区域可锁定2x倍速；横屏长按只有临时2x，无锁定提示，长按时隐藏UI，想锁定倍速只能点击倍速按钮选择；已锁定倍速时再长按显示"⬇ 拖动到此处退出倍速"，拖到下方退出锁定回到1x，期间保持2x
- 滑动进度自适应：短视频（≤30s）最大滑动范围=视频时长，中等视频（30s~2min）最大60s，长视频最大120s
- 静音：默认静音（ExoPlayer volume=0），底部栏静音按钮切换，开启声音后 ExoPlayer volume=1 跟随系统音量
- 手势指示器：中央半透明浮层显示当前操作（进度偏移）
- 顶部指示器：长按倍速/静音切换/快进快退等操作提示显示在顶部（微透明，不遮挡视频中心）
- 手势进度时同步更新底部进度条和时间显示，视频内容跟随seek位置
- 中央操作指示：播放/暂停/快进/快退/静音/取消静音图标短暂显示后自动消失
- 倍速：离散档位 0.5x / 1x / 1.5x / 2x；默认显示"倍速"文字，点击从按钮位置向上弹出深色背景选项列表（PopupWindow），选择后按钮文字变为对应倍速（如"0.5倍"/"1.5倍"/"2倍"），1x时显示"倍速"；当前选中项高亮显示
- 全屏按钮（B站逻辑）：底部栏右下角全屏按钮，仅横屏视频（宽>高）点击可切换为横屏全屏（`SENSOR_LANDSCAPE`），竖屏视频点击无反应；全屏状态下按钮图标切换为退出全屏，点击恢复竖屏（`PORTRAIT`）；切换媒体/退出播放/退出详情页时自动恢复竖屏方向
- 控制器自动隐藏：5秒无操作后自动隐藏
- ExoPlayer 生命周期：Fragment `onViewCreated` 创建并配置音频属性，`onPause` 暂停，`onResume` 恢复，`onDestroyView` 释放
- 音频焦点：ExoPlayer 自动管理（`setAudioAttributes(handleAudioFocus=true)`）
- 视频预览：优先使用 Coil `VideoFrameDecoder`（利用内存/磁盘缓存，`size(1440,2560)`），失败回退 `MediaMetadataRetriever`（`openAssetFileDescriptor` + `decodeFileDescriptor`，提取3秒帧 `OPTION_CLOSEST_SYNC`）
- Coil 全局配置：Coil 3.4.0，`QimengApplication` 通过 `SingletonImageLoader.setSafe` 注册 `VideoFrameDecoder.Factory()` + `AnimatedImageDecoder.Factory()` 到 ImageLoader；内存缓存25%堆大小；磁盘缓存20%磁盘空间（首次解码后缓存到磁盘，后续加载无感）
- 缩略图缓存策略：Coil `size(480,270)` 限制缩略图解码尺寸；RecyclerView `setItemViewCacheSize(20)` + `RecycledViewPool(max=20)`；`onViewRecycled` 时取消未完成的缩略图解码任务（`decodeJob?.cancel()`）；扫描/导入时即开始缓存缩略图到磁盘

## 数据统计页

专业数据报表页面，位于底部导航"数据"Tab（相册与我的之间）。v1.14 重构为「浏览导向」：时间范围全局联动，窗口统计基于按天浏览聚合（daily_browse，无限保留），删除库存排行（作者/标签文件数 Top5）。

**页面结构**（卡片式布局，所有卡片统一使用 `bg_stat_card` 背景）：
- 时间范围切换胶囊组（7天/30天/全部，`bg_capsule_soft` 容器 + 选中项 `bg_capsule_primary` 填充），**全局联动**：切换后数字卡/趋势/排行/分布浏览全部跟随过滤
- 总览数字卡片（两行 6 个指标，前 4 个随时间范围联动，总文件数/总占用为库存静态值）：
  - 第一行：总浏览次数 / 总播放次数 / 总浏览时长（窗口内聚合）
  - 第二行：总文件数 / 总占用空间 / 平均浏览次数（窗口内总浏览 ÷ 窗口内有浏览的文件数，v1.14 由「平均浏览深度」改名）
  - **快速切换防覆盖（v1.15）**：`renderOverviewExtra` 增加序号防重（`renderOverviewSeq`，回写 UI 前校验仍是最新），与 `renderTrend` 的 `renderTrendSeq` 模式一致，快速切换时间范围时旧协程晚完成不再覆盖新结果
- 浏览趋势折线图卡片（`LineChartView`，**v1.15 起基于按天浏览聚合 daily_browse** 而非浏览历史明细——history 同文件只保留最新一条且上限 500，多次浏览只算 1 次导致趋势图数值偏小、与详情页/数字卡对不上；改后主页面与详情页分类型趋势同源同口径，渐变面积填充 + 折线 + 数据点 + **点击数据点高亮并显示数值气泡**；**点击卡片进入分类型趋势详情**，右上角"分类型趋势 ›"）
- 分布统计小入口卡片（纯文字「类型与来源的库存构成 / 查看详情 ›」，**点击进入原分布详情页**；v1.14 移除库存环形图，简化外形）
- 常看文件卡片（文件名 + 浏览次数紧凑文本列表 Top 3，**点击进入常看详情**）
- 常看作者与标签卡片（作者/标签按窗口浏览聚合混合 Top 3，**点击进入常看详情**；v1.15 修复"常看作者"完全失效——`cachedAuthorData` 从未被赋值，已补 `viewModel.authors` 收集）

**交互设计**：
- 点击趋势/常看文件/常看作者与标签/分布统计卡片 → 进入 `StatsDetailFragment` 全新详情界面（`MainActivity.showStatsDetail`），携带当前时间范围
- 详情页排行榜条目可点击跳转：文件 → 文件详情页，作者 → 作者文件页，标签 → 搜索页（v1.14 修复原标签不可跳转问题）
- 时间范围切换重新聚合所有区块（7天/30天按天分组，全部按周分组）
- 排行榜前三名排名数字高亮（主题色）
- 卡片右上角"查看全部 ›"/"查看详情 ›"提示可点击展开

**统计详情页**（`StatsDetailFragment`，v1.14 重构为四种模式）：
- 顶部返回栏 + 动态标题（含时间范围后缀"· 近7天/近30天/全部"）
- 统计摘要卡片（2列网格，根据模式动态生成）
- 数据洞察卡片（自动生成的文字摘要）
- 完整 Top 20 排行榜列表（`RankListAdapter`，`nestedScrollingEnabled=false` 随 ScrollView 滚动，每行带相对第一名的进度条）
- 四种模式：
  - `MODE_TYPE_TREND`：类型浏览趋势——**两张独立对比卡**：「类型浏览趋势」卡（图片/视频/动图胶囊可多选，不同颜色折线叠加）+「来源浏览趋势」卡（常规/COS 胶囊可多选叠加），各卡一张 240dp 折线图（`LineChartView.setMultiSeries`，顶部图例 + 点击气泡含系列名）；基于按天浏览聚合。**v1.15：来源趋势 COS 判定改 combine 等待 dailyBrowse+cosMedia 两流齐备再渲染**（消除首帧 cachedCosKeys 为空先画错再重画的闪烁；数据固有限制：daily_browse 无 COS 标记字段，COS 文件删除后其历史浏览仍归为常规）
  - `MODE_FAVORITES`：常看文件——窗口内浏览聚合排行，支持「按热度/按时长」排序切换（副标题含停留时长）
  - `MODE_AUTHORS`：常看作者与标签——**两张独立排行卡**（v1.16 由单卡拆分）：「常看作者」Top 15 + 「常看标签」Top 10，按窗口浏览聚合，作者条目可点击跳作者文件页，标签条目可点击跳搜索页（**v1.15：`showSearchFragment(initialQuery)` 携带标签名跳转，修复打开空白搜索页**）
  - `MODE_DISTRIBUTION`：分布统计详情——类型/来源 ×（库存数量/大小 + 窗口浏览）对比卡片

**数据源**（v1.14 起）：
- 窗口统计/排行/沉浸时长：`viewModel.dailyBrowse`（按天浏览聚合，无限保留，唯一真相来源）
- 浏览趋势：`viewModel.dailyBrowse`（按天浏览聚合，v1.15 起主页面与详情页同源；旧用 `viewModel.history` 明细分桶因同文件只留最新一条且上限 500 导致数值偏小）
- 类型/来源分布库存：`viewModel.allMedia`（MediaFileEntity，按 mediaType/isCosFile 内存分组）
- 作者浏览聚合：`viewModel.authors` + `viewModel.allAuthorMedia`（关联 recordKey 匹配窗口浏览）
- 标签浏览聚合：`viewModel.allMediaTags`（MediaTagName，关联 recordKey 匹配窗口浏览）

**自绘图表组件**（`ui/widget/`）：
- `LineChartView`：折线/面积趋势图，入场动画 + 网格线 + 稀疏 X 轴标签；**v1.14 支持多系列对比**（`setMultiSeries`：不同颜色折线叠加同一时间轴 + 顶部图例 + 点击气泡含系列名，多系列不画面积避免叠加杂乱）；**v1.15 触摸拦截修复**——`onTouchEvent` 改为 ACTION_DOWN 决策：命中数据点（48dp 阈值内）才消费事件保持点击高亮/气泡，未命中返回 false 放行外层 NestedScrollView/ScrollView 滚动，图表区域内不再无法滚动页面；原 `BarChartView`/`PieChartView`/`BrowseStatsChartView` 因删除库存环形图与柱状排行卡已无使用者，随 v1.14 移除

**排行榜组件**（`ui/stats/`）：
- `RankListAdapter`：通用排行榜列表 Adapter，支持点击跳转，前三名高亮，副标题可选
- `StatsDetailFragment`：统计详情页（全新界面，非 BottomSheet），点击卡片进入，展示 Top 20 完整列表 + 统计摘要 + 数据洞察

## 我的页

- 图片/视频数量卡片
- 收藏 / 浏览历史 / 作者管理 / 主题色彩 / 推荐偏好 / 数据管理 / 数据备份
- 行间距统一由 `QimengProfileRow` style 的 `layout_marginBottom="12dp"` 控制
- 推荐偏好：点击弹出 BottomSheet，显示预设方案列表（均衡推荐/高记忆流行/深度探索/新鲜优先），点击整行应用预设；当前匹配的预设高亮显示（bg_capsule_primary 背景），未选中预设使用 bg_empty_panel 背景
- 兼容性检查：检查 Android 版本、SAF 授权、存储空间、MediaStore 可用性、备份写入权限、设备信息
- **数据管理行**（v1.13 起为独立页面 `DataManagementFragment`，不再是弹层）：`MainActivity.showDataManagement()` 进入。页面平铺三个区块——常规目录 / COS目录 / TXT导入作者，均为完整页面内容（返回栏 + 区块 + 可滚动）。
  - **常规目录**：已添加目录列表（每行目录名+删除按钮）+ "+ 选择文件夹"添加新目录（扫描中拦截）。
  - **COS目录**：已添加COS目录列表（含刷新/删除）+ "+ 选择COS文件夹"。
  - **TXT导入作者**：已导入TXT文件列表（文件名+🔄刷新+×删除）+ 格式说明 + "+ 选择 TXT 文件"。
  - 导入备份数据不在此页（已移至数据备份页）。
- **数据备份行**（v1.13 起为独立页面 `DataBackupFragment`）：`MainActivity.showDataBackup()` 进入。页面内容：
  - **导入数据**（页面顶部）："选择导入目录并恢复"按钮，点击直接弹文件夹选择器，选备份目录后检测到备份数据弹 `AlertDialog` 确认后恢复（不改变已设置的备份位置）。
  - 备份位置：目录结构说明（选择文件夹后创建「绮梦影库」目录，备份数据 `qimeng_backup.json` 写入其中）、当前备份位置、"+ 选择文件夹"设置新位置（选择后自动创建「绮梦影库」目录并立即写入一份全量备份）、"立即同步"、空库覆盖防护提示（⚠ 本地数据为空，建议先导入恢复 + "导入恢复"按钮，从已设置备份目录恢复）。
  - **缓存与同步区块（并入数据备份页）**：缩略图缓存实时进度（百分比+文件数+进度条，观察 thumbnailProgress Flow）+ 自动备份开关（标题"自动备份"+ SwitchMaterial + 状态说明行：已开启/未开启），开启后数据变化自动写入备份目录（30秒防抖），需先设置备份保存位置。
  - v2.0 起移除"自定义导出/自定义章节导出报告"入口（App 不再内置报告器，报告交给 AI 读备份 JSON 生成）。
- 数据管理/数据备份页面与弹层禁止使用系统默认列表对话框，必须使用 `?attr/qmColor*` 或 `resolveThemeColor()` 保持主题一致。
- 主题色彩：`themeRow` **不可点击**（仅跟随手机系统明暗模式，无 App 内切换入口）。`QimengProfileRow` 默认 `clickable=false`，不设置 `OnClickListener`，点击无任何反应。详见 GUIDE_THEME.md「明暗模式系统」

## 详情页沉浸浏览

- 进入详情页时 `MainActivity.setDetailMode(true)` 清除主容器系统栏 padding，详情页始终 edge-to-edge 全屏布局；退出详情页时 `setDetailMode(false)` 恢复主容器 padding。
- 单击媒体切换沉浸模式：显隐 App 顶栏/底部操作栏 + 显隐系统状态栏/导航栏，`syncSystemBars()` 始终保持 `setDecorFitsSystemWindows(false)`，仅通过 `WindowInsetsControllerCompat` 控制系统栏显隐，不触发布局变化，避免图片重新居中。
- 详情页显示 chrome 时：白天模式使用浅底深色系统栏/图标，夜间模式使用黑底浅色系统栏/图标；隐藏 chrome 时统一黑底并隐藏系统栏和上下操作层。
- 顶部操作栏通过 `WindowInsetsCompat.Type.statusBars()` 动态添加顶部 padding，底部操作栏通过 `WindowInsetsCompat.Type.navigationBars()` 动态添加底部 padding，确保 chrome 内容不被系统栏遮挡。
- 视频预览模式下，▶ 播放按钮跟随 chrome 显隐：chrome 可见时显示，chrome 隐藏时隐藏。
- 视频播放返回预览时，先清空旧 drawable（`setImageDrawable(null)`），避免旧图片残留导致短暂异常尺寸；`setImageBitmap` 触发 `setImageDrawable` 自动调用 `resetZoom()`；**左右滑动切换时不再先清空图片**，新图加载完成后直接替换旧图，避免切换闪烁
- 详情页左右滑动切换媒体后，返回键先回到上一个已浏览媒体；没有内部浏览历史时才退出详情页。

## 缩略图加载

- **本地缓存优先**（`ThumbnailCache`）：缩略图保存为本地 WebP 文件（`filesDir/thumbnails/`），用 Coil 加载缓存文件；**GIF 例外**：GIF 缩略图跳过本地缓存（缓存是静态帧），直接从原始 URI 加载并使用 `AnimatedImageDecoder`（API 28+）或 `GifDecoder`（低版本）产生动画 drawable
- 缩略图解码三级策略和预生成机制详见 `GUIDE_ALGORITHM.md`
- **动态分辨率**：`MediaThumbnailAdapter` 和 `GroupedMediaAdapter` 支持 `thumbnailWidth`/`thumbnailHeight` 属性，首页1列时使用 960x540，2列时使用 480x270，其他页面默认 480x270
- 详情页视频预览：先查 Coil 内存缓存（预加载） → Coil `VideoFrameDecoder`（`videoFrameMillis(0)` 取首帧）。v1.16 移除 `ThumbnailCache.readFrame/writeFrame` 详情帧缓存（`_frame.webp`）——全项目无调用者，视频预览一直走 Coil 首帧
- **列表页视频不双解（v1.15）**：列表（首页/全部/收藏/历史/搜索等，`MediaThumbnailAdapter.Holder` 共享）视频无本地缓存时只用 Coil `VideoFrameDecoder` 解一次首帧（`videoFrameMillis(0)` + `memoryCacheKey` 走内存缓存），不再额外起 `MediaMetadataRetriever` decodeJob——同一首帧双解且 `getFrameAtTime` 阻塞式取消不掉会加剧滑动掉帧；视频本地 WebP 由启动预生成填充，下次 bind 走缓存文件分支
- 详情页预加载：`preloadAround()` 双向预加载（1前2后，内存充裕时2前3后），每次切换取消所有旧预加载并重新预加载范围内所有项（Coil 内部缓存去重，已命中的项预加载会直接跳过）；使用 `LargeImageDecoder.buildPreloadRequest()` 构建请求
- 占位色用 `R.color.qm_placeholder`（亮色 `#E0E0E0`，暗色 `#2A2A2A`），避免深色模式下占位与背景同色
- 视频缩略图右下角显示纯文字时长，格式为 `m:ss` 或 `h:mm:ss`，不使用胶囊底

## Fragment 生命周期注意事项

- `ActivityResultLauncher` 必须在 `Fragment.onCreate()` 中注册（而非类属性级别），否则 ViewPager2 回收 Fragment 后 launcher 会变成 unregistered 状态，调用 `launch()` 时抛出 `IllegalStateException`。涉及 Fragment：ProfileFragment。
- `ViewTreeObserver.OnScrollChangedListener` 必须保存为 Fragment 字段，在回调中通过 `_binding?.let` 做 null 安全访问，并在 `onDestroyView()` 中移除监听器。涉及 Fragment：AllFilesFragment。
- 退出详情页时触发的自动同步使用 `ProcessLifecycleOwner.get().lifecycleScope`（非 GlobalScope），确保随 App 进程生命周期自动取消。涉及 Fragment：MediaDetailFragment。

## 公共 UI 工具

- **dp 尺寸转换**：统一使用 `com.qimeng.media.ui.widget` 包下的 `Int.dp(context)`、`Float.dp(context)`、`Int.dpFloat(context)` 扩展函数，禁止各 Fragment/View 私有定义 dp 方法
- **PinchZoomHelper**（`ui/widget/PinchZoomHelper.kt`）：双指缩放列数共享组件，统一管理 `ScaleGestureDetector` 缩放逻辑和 `GridLayoutManager` 创建（含 `SpanSizeLookup`）。使用页面：AllFilesFragment、AlbumDetailFragment、BrowseHistoryFragment、AuthorFilesFragment、FavoriteFragment。SearchFragment 因 `resultAdapter` nullable 不使用共享组件。`ColumnsRef` 类包装列数状态，允许共享组件修改 Fragment 的列数

## UI 约束

- XML 颜色全部用 `?attr/qmColor*`，禁止 `@color/qm_*`。
- Kotlin 代码用 `ctx.resolveThemeColor(R.attr.qmColor*)` 或 `ThemeHelper.resolve(context)` / `ThemeHelper.resolveGenerated(context)`（优先从 ThemeManager 获取热更新颜色）。
- 缩略图加载参数：Coil size(480,270) crossfade(false)；原始URI加载 allowHardware(false)（纯软件渲染），本地缓存文件加载 allowHardware(true)（GPU加速）。
- `QimengCapsuleChip` 样式：`height=32dp minWidth=48dp paddingHorizontal=14dp textSize=12sp`（100dp 圆角胶囊）。
- 底部导航：`elevation=8dp` 与内容区分层，选中指示器 `Widget.Qimeng.BottomNavigationView.ActiveIndicator`（pill 形状，`qmColorPrimarySoft` 背景），选中态 `qmColorPrimary`，label 始终显示。
- 卡片统一无描边，靠 bg/surface/surfaceSoft 三层色差制造层次（详见 GUIDE_THEME.md「设计理念」）。
- 圆角规范：卡片 16dp / 统计卡 20dp / 胶囊 100dp / 底部 Sheet 28dp。
- **按下反馈动画**：可点击的图标按钮和胶囊切换按钮统一使用 `View.addPressAnimation()` 添加按下缩放反馈（0.92→1.0），增强触控感知。已在详情页 6 按钮、首页 3 胶囊启用。
- **渐变遮罩**：详情页顶部/底部操作栏使用 `bg_detail_top_gradient` / `bg_detail_bottom_gradient` 渐变遮罩替代纯色背景，与媒体内容层一体无分割感。
- **SwipeRefreshLayout**：根布局**不要**设置 `android:fitsSystemWindows="true"`，系统栏 padding 由 `MainActivity` 主容器统一处理，避免重复下沉。
- **主题切换**：无 App 内切换入口，仅跟随手机系统明暗模式。`ProfileFragment` 的"主题色彩"行**不可点击**（`QimengProfileRow` 默认 `clickable=false`，不设置 `OnClickListener`），不弹窗、不调用 `setDefaultNightMode()`。系统明暗模式变化时，`MainActivity.onConfigurationChanged` 检测 `uiMode` 变化并 `recreate()`，让所有 `?attr` 颜色和 `resolveThemeColor()` 调用重新生效。详见 GUIDE_THEME.md「明暗模式系统」。

## 万能筛选组件

`MediaFilterSheet` + `FilterConfig` 实现万能筛选，各页面通过 `FilterConfig` 控制显示哪些筛选项。完整的筛选区域枚举和预设配置详见 `GUIDE_ALGORITHM.md`。

- 筛选同时作用于搜索结果：`applyFilter()` 接收 `query` 和 `filterState`，搜索和筛选同时生效
- 新增页面只需传入对应 `FilterConfig` 即可复用筛选面板
- 自定义配置：`FilterConfig(showPlays = false, showTags = false)` 按需组合

## 相册出处分区

相册 Tab 按出处（游戏/动漫/影视作品名）对媒体文件分组显示，点击进入详情页可按角色浏览或全部浏览。

### 出处匹配引擎 SourceMatcher

`SourceMatcher` 是出处匹配单例，采用 **SourceGroup 规范名 + 变体 + 角色检索表** 设计。完整的匹配算法、更新规范和三层优先级详见 `GUIDE_ALGORITHM.md`。

- 匹配成功返回规范名，所有变体归入同一出处分区
- 版本号合并：铁拳7/8→"铁拳"，生化危机2/3/4/8→"生化危机"
- 检索表覆盖 131 个出处

### 药丸容器

- 出处/角色药丸使用 `FlowLayout`（`com.qimeng.media.ui.widget.FlowLayout`）自动换行显示
- 替代了之前的 `HorizontalScrollView` + `LinearLayout` 单行滚动方案
- 药丸过多时自动换到下一行，无需横向滑动即可快速找到目标
- **展开/折叠功能**：点击已选中的芯片可切换药丸列表的展开/折叠状态
  - 折叠时：药丸区域完全隐藏（scroller.visibility = GONE），不显示摘要药丸行
  - 展开时：显示全部药丸 + 末尾"收起 ▲"药丸，点击可折叠
  - 切换到新模式时默认展开
  - **多选不退出**：点击胶囊项（出处/角色/类型等）只更新选中状态，不收起胶囊栏；只有点击"收起 ▲"按钮才收起。`MediaPillsHelper` 的回调拆分为 `onPillClick`（胶囊项点击，不收起）和 `onCollapse`（收起按钮点击，设 `expanded=false`）
  - **相册详情页切模式默认展开（v1.15）**：`AlbumDetailFragment.setViewMode` 切到角色/类型时默认展开药丸（`charPillsExpanded = true`/`typePillsExpanded = true`），与其他页面一致；COS 作品药丸「其他」与出处/角色药丸一致始终排列表末尾（`renderCosWorkPills` 已对齐）
  - **收起即时响应**：`render()` 开头立即根据 expanded 状态隐藏 scroller 和清空 wrapper，避免异步计算期间旧UI残留
  - 涉及 Fragment：AllFilesFragment、FavoriteFragment、BrowseHistoryFragment、AlbumDetailFragment、AuthorFilesFragment
  - 状态变量：`sourcePillsExpanded`、`charPillsExpanded`、`partitionPillsExpanded`、`typePillsExpanded`

### COS 作者分区

- 相册分区页面底部显示"COS 作者"分隔标题，下方按作者分组展示 COS 文件
- COS 分区卡片显示 "COS · N 个文件 · 图片 X / 视频 Y"
- 点击 COS 分区卡片跳转到 COS 相册详情页（`showAlbumDetail(name, isCos=true)`）
- COS 分组逻辑：遍历 `cosWorks`，按 `authorName` 归类，通过 `AuthorMediaCrossRef` 查询每个作者关联的文件
- **COS 分组「其他」兜底（v1.15）**：`AlbumFragment.computeGroups` 末尾把 cosMedia 中未出现在任何作者分组 key 下的文件（无 crossRef 关联/作者名缺失）归入「其他」桶，避免此类 COS 文件在相册页整体不可见

### 相册详情页

- 顶部芯片栏：角色 / 类型（常规和 COS 相册一致，均无分区和作品芯片）
- 常规相册：已锁定出处，角色按 `MediaGroupHelper.groupByCharacter()` 分组（与全部页算法一致）
- COS 相册：已锁定作者（出处=作者），角色按 `MediaGroupHelper.groupByCosWork()` 分组（按作品名）
- 角色筛选药丸：角色模式下，芯片栏下方显示自动换行的角色药丸列表（`FlowLayout`，"全部" + 各角色名+文件数），点击药丸筛选只显示该角色内容，再次点击取消筛选
- 类型筛选药丸：类型模式下展开"全部/图片/视频/动图"药丸
- 支持筛选面板（`FilterConfig.FOR_ALBUM`）、列数切换、双指缩放

### 相册刷新机制

- **AlbumFragment**：每次 Flow emit 都重新执行 `computeGroups`（含 SourceMatcher 匹配），用 `renderHash`（所有分区名+文件数拼接）判断是否需要重新渲染 UI，避免无变化时重复绘制
- **AlbumDetailFragment**：每次 Flow emit 都重新过滤当前出处文件并执行 `render`（拆分为 `render()` 编排 + `computeAlbumGroupsAsync()` 协程计算 + `updateAlbumUI()` UI 更新，接入 `MediaRenderHelper.applyTypeFilter/computeDisplayed`），用 `renderHash`（含 viewMode + isCosAlbum + 文件数 + 角色分组摘要，保留独立 buildString 不接入 buildFingerprint）判断是否需要重新渲染
- **关键**：两个页面的缓存 hash 都包含分组结果摘要，所以 SourceMatcher 规则变化（如新增检索表条目）导致分组结果不同时，会自动触发 UI 刷新

### 缩略图加载策略

- **分辨率**：默认 480×270，首页1列时 960×540
- **滚动暂停预生成（v1.15）**：`ThumbnailCache.setScrollPaused()`——列表滚动期间（非 `SCROLL_STATE_IDLE`）预生成 `processBatch` 解码前循环等待让出 IO，避免与滚动触发的按需解码争抢磁盘导致掉帧，停止滚动自动恢复；已接线：首页 `HomeFragment` OnScrollListener
- **滑动任务取消**：`onViewRecycled` 时取消已完成回收项的未完成任务（图片 decodeJob）
- **覆盖页面**：HomeFragment、AllFilesFragment、AlbumDetailFragment、AuthorFilesFragment、FavoriteFragment

## 浏览历史

- 芯片栏：分区 / 作品 / 角色 / 类型
- **全部模式**：平铺显示所有浏览历史文件，药丸区域显示"全部/常规/COS"数据类型药丸
- **作品模式**：按出处分组（SourceMatcher.match()），显示作品药丸筛选栏，支持多选作品筛选，未选作品时按分组标题显示，选作品后只显示选中作品的文件
- **角色模式**：按角色分组（SourceMatcher.matchCharacter()），显示角色药丸筛选栏，支持多选角色筛选，逻辑与全部页角色模式一致
- 布局：`fragment_browse_history.xml`（返回+标题+清空按钮 + 芯片栏 + 出处药丸 + 角色药丸 + 计数 + RecyclerView + 空状态）
- 药丸容器：`historyPillsScroller`（MaxHeightScrollView）+ `historyPillsWrapper`（LinearLayout），内部使用 `FlowLayout` 自动换行
- 适配器：`GroupedMediaAdapter`（支持分组标题头），3列网格
- 双指缩放列数 2-5 列（`ScaleGestureDetector`）
- 滑动暂停缩略图加载
- `fingerprint` 机制（`MediaRenderHelper.buildFingerprint`）：viewMode + partitions + displayed.size + displayed.firstRecordKey + selectedSources + selectedChars + filterType + typePillsExpanded + 分组摘要
- 数据观察：combine 7 个 Flow（allMedia, cosMedia, cosWorks, allStats, allMediaTags, allTags, history）
- COS 模式空状态文本："没有COS浏览记录"；常规/全部模式空状态文本："没有浏览记录"（`updateHistoryEmptyState`）

## 收藏页

- 芯片栏：分区 / 作品 / 角色 / 类型
- **全部模式**：药丸区域显示"全部/常规/COS"数据类型药丸，点击切换 COS 模式
- **作品模式**：按出处分组（SourceMatcher.match()），显示作品药丸筛选栏，支持多选作品
- **角色模式**：按角色分组（SourceMatcher.matchCharacter()），显示角色药丸筛选栏，支持多选角色；COS 模式下显示作品药丸（renderCosWorkPills）
- 药丸容器：`favoritePillsScroller`（MaxHeightScrollView）+ `favoritePillsWrapper`（LinearLayout），内部动态添加 FlowLayout
- 出处/角色筛选状态：`selectedSources: MutableSet<String>` / `selectedChars: MutableSet<String>`（多选集合）
- 布局：`fragment_favorite.xml`（返回+标题 + 芯片栏 + 药丸滚动容器 + 计数 + RecyclerView + 空状态）
- 适配器：`GroupedMediaAdapter`（支持分组标题头），3列网格
- 收藏存储机制详见 `GUIDE_DATA.md`「收藏」章节
- **详情页返回自动刷新（v1.15）**：注册 `media_detail_prefs` 的 `OnSharedPreferenceChangeListener`，`KEY_FAVORITES` 变化时调用 `refreshFavoriteData()`（详情页 toggleFavorite 只写 SharedPreferences，9 流 combine 不含收藏键数据源，原实现仅下拉刷新才感知），`onDestroyView` 注销监听
- 滑动暂停缩略图加载
- `fingerprint` 机制（`MediaRenderHelper.buildFingerprint`）：与浏览历史一致，fingerprint 中 `selectedSources.sorted().joinToString(",")` 和 `selectedChars.sorted().joinToString(",")`；空状态由 `updateFavoriteEmptyState` 处理（COS 模式"没有COS收藏"，常规模式"还没有收藏"）

## COS 模式（集成到现有页面）

COS 不是独立页面，而是集成到首页、全部、收藏、浏览历史中的切换模式。

### 首页 COS tab

- 首页芯片栏：推荐 / COS / 排行榜（三选一，左右横滑切换）
- COS tab 使用推荐算法但只处理 `isCosFile=true` 的媒体
- 横滑手势支持三个 tab 间切换
- 空状态：无数据时不显示任何提示文本，保持空白

### 全部/收藏/浏览历史的 COS 切换

- 全部模式下药丸区域显示"全部/常规/COS"数据类型药丸（FlowLayout），扁平芯片风格（30dp高、选中bg_capsule_primary、未选中无背景），与出处/角色药丸样式一致
- 默认"全部"选中（高亮，不是"默认/常规"），"全部"显示常规+COS合并后的所有文件（使用 `mergeGroups()` 合并分组），点击"常规"只显示非COS文件，点击"COS"切换到 COS 模式
- COS 模式下：
  - 出处分组 = 按作者名分组（通过 `AuthorMediaCrossRef` 关联 `cos_` 前缀 authorId → `AuthorEntity.displayName`）
  - 角色分组 = 按作品名分组（`CosWorkEntity.workName`，通过 `MediaFileEntity.folderName` 匹配作品名）
  - 正常模式下不显示 COS 文件（`filter { !it.isCosFile }`）
- 切换 COS 模式时清空选中状态，重新计算分组

### COS 数据流

- `viewModel.cosWorks`：观察 CosWorkEntity 列表（Flow）
- `viewModel.cosMedia`：观察 isCosFile=true 的 MediaFileEntity 列表（Flow）
- COS 文件不出现在正常模式下的首页/全部/相册/收藏/浏览历史中

### COS 扫描

- 入口：我的页 → 数据管理（独立页面 `DataManagementFragment`）→ COS目录
- 选择 COS 根文件夹后，按 作者/作品/图片 三层结构扫描
- 扫描逻辑详见 `GUIDE_SCAN.md`，目录结构详见 `GUIDE_AUTHOR.md`
- 重新扫描：添加新 COS 目录时，重新扫描所有 COS 目录并重建 COS 索引
- 删除 COS 目录：删除扫描源及关联的作品和媒体文件，级联清理作者关联

### COS 数据备份

COS 数据（cos_works、COS 媒体文件、COS 作者关联）随备份主文件 `qimeng_backup.json` 全量导出，格式详见 `DATA_MIGRATION_SPEC.md` 和 `GUIDE_BACKUP.md`

- 使用原生 View + XML + ViewBinding，不主动引入 Compose
- Fragment 不直接访问数据库，不直接执行长耗时扫描
- 不把业务逻辑写进 Adapter
- 导航事件集中处理，不在多个点击事件里散落写跳转
- 页面颜色和文字颜色不硬编码，使用主题系统（详见 GUIDE_THEME.md「颜色字段」）
- 媒体卡片只绑定当前 item 数据，不做数据库查询

- 性能约束：媒体列表分页加载，不在 onBindViewHolder 做数据库查询或文件 IO
- 毛玻璃效果要有降级方案
- 主题切换避免重建全部复杂资源
- 视频播放页优先保证播放流畅

- UI 设计原则：先保证信息层级再做装饰；媒体内容是主角，按钮和卡片不能喧宾夺主
- 多主题要有明显差异，不只是换主色（详见 GUIDE_THEME.md「设计理念」）
- 毛玻璃、胶囊、渐变、贴纸风格要统一，不要每页一套
- 禁止只做"白底列表 + 默认按钮"的普通工具 UI
- 禁止为了美观牺牲文件名、作者、统计等核心信息可读性
- 视频详情页使用 Media3 ExoPlayer + 自定义 BiliPlayerView（B站风格控制器），控制层按 B 站式需求定制
- 视频点击播放按钮即计一次播放，同一次详情打开只计一次（v1.14 起真实写入 playCount：`startVideoPlayback` 时经 `ViewModel.recordPlay` 上报，预览→播放→退预览→再播放不重复计数；**v1.15 改为会话级 `playedRecordKeys` 集合**：播放 A → 横滑 B → 滑回 A → 再播放 A 也不重复计数，退出详情时清空）
- 列表不自动播放视频
- 禁止使用网络图片加载能力访问外网

> 最后更新：2026-08-14（v1.15 巡检：详情页 viewCount 会话级去重/播放幂等、首页指纹加点赞维度（浏览统计不入指纹防内容跳动）、收藏页与作者页 SP 监听、相册 COS 其他桶与切模式默认展开、全部页/作者文件页指纹补 tags 维度（不含 stats）、列表提交异步化、LineChartView 触摸放行滚动、统计页序号防重、列数持久化）

## 应用图标

- **Adaptive Icon（API 26+）**：`mipmap-anydpi/ic_launcher.xml`，前景 = `drawable/ic_launcher_foreground.png`（各密度 PNG，木纹 XB 雕刻图居中），背景 = `drawable/ic_launcher_background.xml`（纯色 `#DDBC98`）
- **Legacy 图标（API < 26 回退）**：`mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` + `ic_launcher_round.png`（完整图标 PNG）
- **启动画面**：已隐藏图标（透明），仅显示 `qm_bg` 纯色背景；配置在 `values/themes.xml` 的 `Base.Theme.绮梦影库` 中
- 更换图标需同步替换：前景 PNG（5 密度）、背景 XML、mipmap PNG（5 密度 × 2 文件）、AndroidManifest 中的 `android:icon`/`android:roundIcon` 引用（当前指向 `@mipmap/ic_launcher`）
