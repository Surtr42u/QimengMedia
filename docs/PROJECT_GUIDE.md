# PROJECT_GUIDE - 绮梦影库项目总览

## 项目定位

绮梦影库是一个纯本地 Android 媒体库 App，用于只读扫描用户指定目录中的图片和视频，提供浏览、统计、标签、作者管理、相册分类、推荐、多主题和数据迁移能力。

## 当前基准

- 版本：v1.10
- App 名称：绮梦影库
- Package：`com.qimeng.media`
- Minimum SDK：Android 12 / API 31
- Target SDK：Android 16 / API 36
- 开发语言：Kotlin
- UI：原生 View + ViewBinding；视频播放器使用 Media3 ExoPlayer + 自定义 BiliPlayerView 控制器
- 架构目标：单 Activity + 多 Fragment + MVVM
- 推荐分层：ui/、data/、scan/、algorithm/、backup/、core/、animation/
- data 不依赖 ui；algorithm 不直接依赖 Fragment；backup 不直接操作原始媒体文件
- ViewModel 暴露 UI state，不暴露数据库 Entity 给 UI 直接修改
- Repository 负责协调 DAO、扫描结果和备份同步
- Repository 方法命名表达业务动作
- 列表、加载、空状态、错误状态统一建模，不用多个零散 Boolean
- 数据库目标：Room
- 图片加载目标：Coil 3.4.0
- 视频播放目标：Media3 / ExoPlayer
- 文件访问目标：SAF 文件夹授权

## 当前实现进度

- 已建立 AI Skill 与项目专属指南体系。
- 已新增根目录 `AI_README_FIRST.md`、`AGENTS.md`、`CLAUDE.md`、`GEMINI.md`、`.github/copilot-instructions.md`，让不同 AI 工具都能发现接手规则。
- 已新增 `docs/GUIDE_USAGE_MATRIX.md`，作为完整任务级指南触发矩阵，防止任何模块任务跳过对应指南。
- 已区分真正外部下载 Skill 与项目内自写 Skill：`app/ai-skills/external/` 放用户导入或明确下载的外部原文，`docs/AI_WRITTEN_SKILLS/` 放 AI 自写通用参考规范。
- 已下载 Android 开发和 UI 相关外部 Skill，来源记录在 `app/ai-skills/external/DOWNLOADED_SOURCES.md`。
- 已启用 ViewBinding。
- 已实现 `MainActivity` 作为单 Activity 主容器。
- 已实现底部五 Tab： 首页、全部、相册、数据、我的。
- 已启用 Room + KSP，建立完整数据层。
- 已实现 10 个 DAO、`AppDatabase`、`LocalMediaRepository`、`DefaultLocalMediaRepository`。
- 已建立 `RecordKeyFactory`、`MediaType` 常量、备份 JSON 模型。
- 已新增 `QimengApplication`、`AppContainer`、`MediaLibraryViewModel`。
- 已实现 SAF 扫描。
- 已创建所有页面 Fragment：`HomeFragment`、`AllFilesFragment`、`AlbumFragment`、`ProfileFragment`、`MediaDetailFragment`、`AuthorListFragment`、`AlbumDetailFragment`、`BrowseHistoryFragment`。
- 已实现图片/视频详情页（全屏查看 + Media3 ExoPlayer），原图加载，左右预加载，淡入淡出滑动动画。
- 已接入 Media3 ExoPlayer 作为视频播放引擎，详情页通过自定义 `BiliPlayerView`（B站风格控制器）承载视频播放器；主 UI 仍保持原生 View + ViewBinding。
- 视频详情页支持暂停时透明覆盖层拦截触摸实现横滑浏览、点击播放切 BiliPlayerView 控件接管；全屏播放中按返回先退出全屏回竖屏继续播放，再按返回回预览模式。
- 视频播放控制栏已集成自定义 B站风格控制器（BiliPlayerView），底部栏含播放/暂停、静音、SeekBar进度条、时间轴标签图标、倍速文字按钮（0.5x/1x/1.5x/2x PopupWindow 选择）、快速转跳图标、全屏按钮；竖屏单击暂停/播放、横屏单击显隐UI/双击暂停/播放；长按2x倍速（竖屏可拖动锁定/退出，横屏临时2x）；全屏按钮500ms防抖。
- 已实现自定义 `ZoomImageView`（双指缩放、双击缩放/还原、单击显隐 chrome；统一纯软件渲染 `LAYER_TYPE_SOFTWARE` + `ARGB_8888`，仅 GIF AnimatedImageDrawable 时切换 `LAYER_TYPE_HARDWARE`）。
- 已实现相册页（按出处分组，点击进入相册详情）。
- 已实现作者管理、标签功能、备份导出/导入。
- 已实现作者文件浏览页（AuthorFilesFragment），从作者管理页卡片点击或详情页快速转跳进入，逻辑和 UI 与全部页一致。
- 已实现全局明/暗两套主题，跟随手机系统白天/深色模式；`?attr/qmColor*` 主题属性体系全局生效。
- 已新增 `ThemeHelper.kt` 运行时主题色解析，所有 Kotlin 代码统一使用。
- 已实现首页推荐/排行榜、日周月年榜、搜索、筛选、1/2 列切换、横滑切 tab。
- 已实现全部页日期分组、默认 3 列、双指缩放 2-5 列、筛选、类型筛选。
- 已实现筛选面板 `MediaFilterSheet.kt`（排序/升降序/观看次数/文件大小/榜单周期/时间范围/标签）。
- 已实现推荐引擎和浏览器逻辑 `MediaBrowserLogic.kt`（多维度推荐算法、排行榜、日期分组、筛选、排序）。推荐算法 10 维评分：tagRelevance(0.22) + engagement(0.10) + tagCollection(0.15) + recency(0.15) + discovery(0.20) + freshness(0.05) + likeScore(0.05) + mediaTypeBalance(0.05) + browseDepth(0.03) + randomFactor(0~0.30)，每次刷新结果不同。评分排序后 `balanceVideoImage()` 视频图片自然混合（按比例随机取，不做连续分组）。排行榜排序=浏览次数+点赞次数。
- 已实现排序缓存机制：`cachedSortedItems` + `sortFingerprint`，只在 tab/period/filter/query 变化时重排，增量加载只从缓存取。
- 已实现详情页 `add` 叠加模式：列表 fragment view 不销毁，返回零闪烁。
- 已实现 `GroupedMediaAdapter` 日期分组 RecyclerView 适配器，复用 `MediaThumbnailAdapter.Holder`。
- 已实现浏览历史页、标签增删、作者 TXT 导入、种子相册规则、浏览时长记录。
- 已实现收藏功能（SharedPreferences 持久化），我的页新增收藏入口，收藏页缩略图网格点击进详情。
- 详情页上方 tab 已精简为返回（胶囊图标）+ 序号 + 信息（胶囊图标），点击信息图标弹出文件详情 BottomSheet；下方 tab 精简为点赞/收藏/标签/快速转跳，快速转跳点击弹出关联作者列表 BottomSheet，点击作者名跳转作者文件浏览页。
- 已调整"我的/数据管理"：移除独立扫描、重置统计、种子规则和 TXT 导入行，统一放入项目主题底部弹层；弹层提供常规目录、COS目录、备份保存位置、TXT 导入作者、缓存与同步；扫描支持多目录合并索引图片和视频。缓存与同步从独立行移入数据管理弹窗。
- UI 已精简：搜索栏与标题同排、芯片缩至 30dp、纯矢量图标无文字、底部无边距色条。
- 缩略图加载参数（Coil 3.4 `size(480,270) crossfade(false) allowHardware(false)`），`itemViewCacheSize=20`，`RecycledViewPool(max=20)`。
- 缩略图加载使用 `ThumbnailLoader` 三级策略（`ContentResolver.loadThumbnail` → `getEmbeddedPicture` → 首帧截取+黑帧检测），完整策略详见 `GUIDE_ALGORITHM.md`「缩略图解码策略」。
- **本地缩略图缓存**（`ThumbnailCache`）：缩略图和视频详情帧保存为本地 WebP 文件（`filesDir/thumbnails/`），加载时先查本地缓存，没有再走解码流程并缓存。Coil 加载缓存文件时 `allowHardware(true)` GPU 渲染更快。`MediaThumbnailAdapter` 后台解码用信号量限制并发（最多 2 个），且解码前检查缓存文件是否已被预生成创建，避免与预生成争抢 IO/内存导致 OOM 卡死。
- 扫描完成后后台预生成缩略图到本地缓存（`pregenerateThumbnails`），已缓存的文件自动跳过。**统一并发池**：根据设备 CPU 核数和运存自动调整（`getConcurrency`），并发=CPU核数×1.5（上限16），图片视频共用并发池，图片优先排列，设备总内存<4GB时减半。ViewModel `init` 块启动时即从数据库读取已有文件，按常规/COS分组分别通过 `ThumbnailUseCase` 预生成，进度 Flow 正确更新到 UI。
- `cache_version` 机制：版本升级时自动清除视频缩略图缓存重新生成（黑帧检测逻辑变更后需要重新缓存）。
- 详情页预加载基础 1 前 2 后（内存充裕时 2 前 3 后），视频详情帧也使用 `ThumbnailCache` 缓存。详情页图片始终加载完整原图（不降采样），这是设计决策：用户点击查看的就是原始分辨率。
- **大图软解优化**（`LargeImageDecoder`，绕过 Coil Decoder 链的自定义解码器）：内部解码逐级回退——Coil 内存缓存命中（仅检查是否存在，不取出 Bitmap 对象——避免缓存驱逐时 recycle 导致 ImageView 绘制已回收 bitmap 崩溃；命中时返回 null 让 Fragment 走 Coil load 路径，Coil 命中内存缓存零延迟且正确管理 bitmap 生命周期）→ PNG 用 libspng（ARM NEON 优化，比系统 libpng 快 2-3 倍）→ `BitmapFactory.decodeFileDescriptor`（跳过 InputStream 中间层，比 Coil 的 decodeStream 快 ~10-20%）→ `BitmapFactory.decodeStream`（异常回退）；`LargeImageDecoder` 全部失败则由 Fragment 回退到 Coil 标准流程；解码成功后写入 Coil 内存缓存供后续访问零延迟；并发解码信号量限制（大图同时解码不超过 2 张，防止内存峰值）；预加载使用 Coil 标准流程自动利用缓存。
- **libspng 集成**（`SpngDecoder` + `app/src/main/cpp/spng/`）：v0.7.4（MIT 协议），CMake + JNI 桥接，使用 NDK 自带 zlib，ARM NEON 优化在 arm64-v8a/armeabi-v7a 自动启用；仅详情页当前 PNG 图片使用，解码失败静默回退到 BitmapFactory，不影响其他流程。
- **详情页预渲染策略**：双向预加载，基础 1 前 2 后（阅读 D 时预渲染 C/D/E/F），内存充裕时 +1 每侧（2 前 3 后）；每次切换取消所有旧预加载并重新预加载范围内所有项（Coil 内部缓存去重，已命中的项预加载会直接跳过，不会重复解码）；退出详情页时统一取消预加载；**视频帧取首帧**：详情页预览和预加载统一 `videoFrameMillis(0)` 取首帧，消除 3 秒帧 seek 开销。
- **内存安全**：`QimengApplication.onTrimMemory` 三级回收——RUNNING_CRITICAL 移除 3/4 缓存条目（保留最近 1/4）、RUNNING_LOW 移除一半、UI_HIDDEN 保留最近 1 个条目移除其余预渲染；cache.keys 按 LRU 顺序迭代，take/dropLast 保留最近访问的条目。
- **详情页分页加载**：详情页初始加载 40 个媒体（从点击项开始），滑动接近边界（距末尾 5 项）时自动加载 40 个；向前滑动接近开头时也自动加载 40 个并调整索引；显示 "实际位置/推荐总数"，不影响推荐机制。
- 底部导航 `elevation=0dp + itemRippleColor透明 + activeIndicator隐藏`。
- `TagDao` 新增 `observeTagEntitiesForMedia`、`observeAllMediaTagNames`、`getCrossRefsByRecordKeys` 查询。
- `AuthorDao` 新增 `deleteAuthorsByIds` 批量删除。
- `ViewStatsDao` 新增 `getByRecordKeys` 批量查询。
- `MediaFileDao` 新增 `observeNonCosMedia`（非COS Flow）、`getByType`（按类型查询）。
- `MediaBrowserLogic.formatSize/formatDate/formatDuration/dateLabel/groupByDate` 等共享工具方法（formatDate/formatDuration 统一供 MediaDetailFragment、MediaThumbnailAdapter 调用）。
- 当前所有核心功能已完成。
- 已实现推荐偏好设置（"我的→推荐偏好"），用户可调整推荐算法 9 个权重维度（标签相关性/标签合集/互动热度/浏览时效/点赞偏好/新发现/新鲜度/浏览深度/随机性），`MediaBrowserLogic.recommend()` 接受可选 `customPrefs` 参数覆盖默认权重。偏好弹窗提供 4 个预设方案（均衡推荐/高记忆流行/深度探索/新鲜优先），预设定义位于 `ProfileFragment.recPrefPresets`。
- 已实现视频时间轴标签（BiliPlayerView 时间轴标签图标按钮 + 标签芯片），用户可在视频播放时添加/删除时间点标记，点击标签跳转到标记位置，长按弹出操作菜单（跳转/删除）。
- 已实现 `recommendation_prefs.json` 和 `timeline_tags.json` 备份导入导出，JSON 文件清单 10 个全部已实现。
- 已修复详情页点击 chrome/系统栏显隐时图片和视频跳动：详情页始终 edge-to-edge 全屏布局，`syncSystemBars()` 保持 `setDecorFitsSystemWindows(false)` 不变，仅通过 `WindowInsetsControllerCompat` 控制系统栏显隐，不触发布局变化；`MainActivity.setDetailMode()` 在详情页激活时清除主容器系统栏 padding，退出时恢复。
- 已补充详情页渲染诊断日志并修复日志噪音（2026-06-20，真机调试发现）：① `MediaDetailFragment.showImage` 补缓存命中/未命中/解码完成(含耗时)/回退Coil 日志、`preloadAround` 补范围日志、`LargeImageDecoder` 补各回退路径（libspng/fileDescriptor）日志，消除 GUIDE_DEBUG 承诺但代码缺失的观察盲区（现可统计原图缓存命中率）；② `AnrWatchdog` 新增 idle 识别（栈顶为 nativePollOnce/next/Looper.loop 跳过）+ 冻结自检（实际 sleep 远超预期则跳过并重置时间戳），修复设备息屏/后台守护线程被冻结产生的 `主线程阻塞 128317ms` 递增误报；③ `HomeFragment.render` 日志从短路判断前移到 fingerprint 变化块内，消除详情页切换时底层 Home 无关 Flow emit 触发的日志噪音（双重短路本身健全，零开销）；④ `ZoomImageView.containsAnimatedDrawable` 对 NoSuchFieldException 静默（非 Coil ScaleDrawable 无 child 字段是预期），消除每张图 `checkAnimatedDrawable failed` warning 刷屏。
- 已修复详情页退后台再回来显示外面 tab：`MainActivity.onResume()` 新增 `syncBottomNavVisibilityWithBackStack()` 调用，根据 BackStack 栈顶 Fragment 类型同步 `bottomNavigation` 可见性；该方法与 `addOnBackStackChangedListener` 共用同一逻辑。背景：App 从后台恢复时 BackStack 未变化、`BackStackChangedListener` 不触发，但 `bottomNavigation.visibility` 可能被系统重置为 `VISIBLE`，导致详情页等覆盖页面上层显示外面 tab。
- 已修复点击主题色彩闪退：回退到原始方案（v1.10 设计决策），`ProfileFragment.themeRow` 点击只显示 Toast "已跟随手机明暗模式"，不弹窗、不调用 `setDefaultNightMode()`；删除 `showThemeModeDialog()` 和 `updateThemeModeSelection()` 方法；`MainActivity.applyStoredTheme()` 只调用 `setTheme()` 不调用 `setDefaultNightMode()`。项目仅跟随手机系统明暗模式，`AppPrefs.themeMode` 字段保留用于备份 JSON 兼容性但无 UI 入口修改。
- 详情页 chrome 已改为四层结构：纯背景层、透明居中文件层、上下轻量图标操作层、沉浸隐藏层；白天显示浅底深色图标，夜间显示黑底浅色图标，沉浸后系统栏和上下操作层消失且文件不移动。视频先显示同步背景的预览层和 ▶ 播放按钮，单击预览区切换 chrome（与图片一致），点击播放按钮才启动 BiliPlayerView 播放器 UI。
- 已将 `DetailVideoPlayer` 替换为 `BiliPlayerView`（B站风格自定义控制器），基于 Media3 ExoPlayer + PlayerView，支持手势控制（竖屏单击暂停/横屏单击显隐UI）、长按2x倍速（竖屏拖动锁定/退出）、倍速PopupWindow选择、时间轴标签、全屏切换（500ms防抖）。
- 已替换应用图标为木纹 XB 雕刻图标：Adaptive Icon 前景 PNG（drawable 各密度）+ 背景色 `#DDBC98` + mipmap 完整图标回退（5 密度 PNG）；启动画面已隐藏图标仅显示纯色背景。
- 已将 `MediaLibraryViewModel` 按职责拆分为 4 个 UseCase（`ScanUseCase`、`ThumbnailUseCase`、`AuthorImportUseCase`、`AutoSyncUseCase`），ViewModel 保留数据观察、统计记录、设置管理、标签管理、作者管理和 ScanStatus 状态，公共 API 不变。
- 已对 `BackupManager` 报告生成进行重构（密封类 `RankEntry` 消除 `!!`、`PersonalPrefsReportData` 数据类封装 24 参数、`buildReportText` 按章节提取 10 个子方法），导出 JSON 格式和 TXT 报告内容零变化。
- 已删除死代码 `DetailSheetHelper.kt`（从未被调用，详情页 BottomSheet 逻辑在 `MediaDetailFragment` 内部实现）。
- 已基于 detekt 实证数据重构高圈复杂度方法：`MediaDetailFragment.showMediaAt`（CC 29→5，提取 showImage/showVideo/setupVideoTouchOverlay/isTapGesture/needsMetadataDecode）、`MediaBrowserLogic.recommend`（CC 31→6，提取 resolveWeights/computeNormDenominators/shuffleBuckets）、清理 8 处死代码（seekRelative/extractInt/resolver/formatSeconds/showRootFragment/THUMB_SIZE/MediaStoreObserver.trigger参数/SafMediaScanner.extractInt）。
- 已完成 5 个 Fragment.render 重构（CC 22-48→<20）：新增 `MediaRenderHelper` 纯计算型 object（封装 applyTypeFilter/computeDisplayed/buildFingerprint），5 个 Fragment 共享；每个 Fragment 拆分为 render()编排 + computeXxxGroupsAsync()协程计算 + updateXxxUI()UI更新；`MediaFilterSheet.show`（CC 35→<20）拆分为 show()编排 + appendTimeRangeSection/appendTagsSection/buildFooter + FilterStateHolder。
- `AutoSyncUseCase` 支持四种触发时机：扫描后（只写app数据/）、退出详情页（只写app数据/）、App后台（全量同步）、手动同步（全量同步，无视防抖）；数据备份弹窗新增"立即同步"按钮。
- `deleteMediaAndRefs` 扩展：事务内删除6张表（新增 view_stats/view_history/timeline_tags），事务外清理 SharedPreferences（点赞/收藏）和缓存（Coil 内存缓存 + ThumbnailCache）；启动时 `cleanupStaleRefs()` 清理历史残留数据。
- `authors.json` schemaVersion 升至 2：导出时 files 数组填入关联 recordKey，导入时恢复 AuthorMediaCrossRef。
- 个人偏好 TXT 报告 COS 排行按作品（文件夹）聚合显示，常规文件不变。

## 核心原则

- 原始媒体文件只读，禁止删除、移动、重命名或写入。
- App 只扫描用户指定并授权的目录。
- 不申请网络权限，不做网络请求。
- App 内记录默认以完整文件名含扩展名作为主要关联键。
- 重复完整文件名时，使用父文件夹名和短路径哈希高级区分。
- App 数据可以清空、重置、导入、导出和自动同步。
- UI 颜色、字体、胶囊、毛玻璃、渐变必须统一管理。
- Compose 不作为新增页面默认技术路线。
- 新增或修改功能后，必须同步更新相关项目指南文档。

## 主要功能模块

- 扫描：读取用户授权目录中的图片和视频。
- 数据：保存媒体索引、统计、历史、标签、作者、相册规则、设置。
- UI：首页、全部、相册、数据统计、我的、详情页、设置页、作者管理页。
- 主题：跟随系统白天/深色模式，全局明/暗两套显示逻辑。
- 推荐：基于本地统计、标签、作者、相册出处和偏好计算。
- 备份：导入导出 JSON，指定备份目录后自动同步。
- Android 兼容：面向 Android 16 及以上，未来适配新系统。

## 页面结构

底部导航保持五个 Tab：

- 首页
- 全部
- 相册
- 数据
- 我的

作者管理不放到底部 Tab，入口位于"我的"页面的独立功能行。常规目录入口也放在"我的"页面，首页只承担浏览和推荐展示职责。

## 项目目录目标

```text
QimengMedia/
├── AGENTS.md
├── AI_README_FIRST.md
├── CLAUDE.md
├── GEMINI.md
├── README.md
├── app/
│   ├── ai-skills/
│   │   ├── README.md
│   │   └── external/              # 放用户导入或明确下载的外部 Skill
│   ├── build.gradle.kts
│   └── src/main/
│       ├── cpp/                     # NDK/CMake 原生代码
│       │   ├── CMakeLists.txt       # libspng + JNI 桥接构建配置
│       │   ├── spng_jni.c           # libspng JNI 桥接（PNG 解码）
│       │   └── spng/                # libspng v0.7.4 源码（MIT 协议）
│       │       ├── spng.c
│       │       ├── spng.h
│       │       └── LICENSE
│       └── java/com/qimeng/media/
│           ├── MainActivity.kt
│           ├── QimengApplication.kt
│           ├── ThemeHelper.kt
│           ├── core/
│           │   ├── AppContainer.kt
│           │   ├── RecordKeyFactory.kt
│           │   ├── ThumbnailLoader.kt
│           │   ├── ThumbnailCache.kt
│           │   ├── LargeImageDecoder.kt
│           │   ├── SpngDecoder.kt
│           │   ├── AppLog.kt
│           │   ├── AnrWatchdog.kt
│           │   ├── CompatChecker.kt
│           │   ├── MediaCacheCleaner.kt
│           │   └── MediaDetailPrefsCleaner.kt
│           ├── data/
│           │   ├── db/
│           │   │   ├── AppDatabase.kt
│           │   │   ├── dao/
│           │   │   ├── entity/
│           │   │   │   ├── AuthorMediaCrossRef.kt
│           │   │   │   └── AuthorFileCount.kt
│           │   │   └── model/MediaTagName.kt
│           │   ├── model/MediaType.kt
│           │   ├── prefs/
│           │   │   └── AppPrefsManager.kt
│           │   └── repository/
│           ├── scan/
│           │   ├── SafMediaScanner.kt
│           │   ├── MediaStoreScanner.kt
│           │   ├── MediaStoreObserver.kt
│           │   └── ScanUtils.kt
│           ├── backup/
│           │   ├── BackupManager.kt
│           │   ├── BackupModels.kt
│           │   ├── BackupFileNames.kt
│           │   └── BackupRepository.kt
│           ├── domain/
│           │   ├── ScanUseCase.kt
│           │   ├── ThumbnailUseCase.kt
│           │   ├── AuthorImportUseCase.kt
│           │   └── AutoSyncUseCase.kt
│           └── ui/
│               ├── adapter/
│               │   ├── MediaThumbnailAdapter.kt
│               │   └── GroupedMediaAdapter.kt
│               ├── browser/
│               │   ├── MediaBrowserLogic.kt
│               │   ├── MediaFilterSheet.kt
│               │   ├── MediaGroupHelper.kt
│               │   ├── MediaPillsHelper.kt
│               │   └── MediaRenderHelper.kt
│               ├── main/HomeFragment.kt
│               ├── all/AllFilesFragment.kt
│               ├── album/
│               │   ├── AlbumFragment.kt
│               │   ├── AlbumDetailFragment.kt
│               │   ├── SourceMatcher.kt
│               │   └── SourceGroupsData.kt
│               ├── profile/
│               │   ├── ProfileFragment.kt
│               │   └── SheetUiHelper.kt
│               ├── detail/
│               │   ├── MediaDetailFragment.kt
│               │   ├── TimelineTagHelper.kt
│               │   ├── ZoomImageView.kt
│               │   └── BiliPlayerView.kt
│               ├── favorite/
│               │   └── FavoriteFragment.kt
│               ├── author/
│               │   ├── AuthorListFragment.kt
│               │   ├── AuthorFilesFragment.kt
│               │   └── AuthorEditDialog.kt
│               ├── history/BrowseHistoryFragment.kt
│               ├── library/MediaLibraryViewModel.kt
│               ├── search/SearchFragment.kt
│               ├── stats/                       # 数据统计页（专业报表）
│               │   ├── DataStatsFragment.kt     # 数据统计主页面
│               │   ├── StatsDetailFragment.kt   # 统计详情页（全新界面）
│               │   └── RankListAdapter.kt       # 排行榜列表 Adapter（点击跳转）
│               └── widget/
│                   ├── BarChartView.kt          # 竖向柱状图（自绘 Canvas）
│                   ├── BrowseStatsChartView.kt  # 浏览统计柱状图（自绘 Canvas）
│                   ├── DimenExt.kt
│                   ├── FlowLayout.kt
│                   ├── LineChartView.kt         # 折线/面积趋势图（自绘 Canvas）
│                   ├── MaxHeightScrollView.kt
│                   ├── PieChartView.kt          # 环形图（自绘 Canvas）
│                   ├── PinchZoomHelper.kt
│                   └── PressAnimation.kt        # 按下反馈动画扩展函数 addPressAnimation()
├── docs/
│   ├── AI_WRITTEN_SKILLS/          # 项目内自写通用 AI 参考规范
│   ├── PROJECT_GUIDE.md
│   ├── GUIDE_README.md
│   ├── GUIDE_UI.md
│   ├── GUIDE_DATA.md
│   ├── GUIDE_SCAN.md
│   ├── GUIDE_THEME.md
│   ├── GUIDE_ALGORITHM.md
│   ├── GUIDE_BACKUP.md
│   ├── GUIDE_ANIMATION.md
│   ├── GUIDE_ANDROID_COMPAT.md
│   ├── GUIDE_AUTHOR.md
│   ├── GUIDE_DEBUG.md
│   ├── GUIDE_CODE_MAINTENANCE.md
│   └── GUIDE_USAGE_MATRIX.md
├── detekt.yml
└── gradle/
    └── libs.versions.toml
```

## 构建配置

`gradle.properties` 关键配置：
- `org.gradle.jvmargs=-Xmx4g`：Gradle/AAPT2 堆内存 4GB（原 2GB 不足导致 `processDebugResources` 偶发崩溃）
- 不使用 `kotlin.compiler.execution.strategy=in-process`：Kotlin 编译使用独立 daemon 进程，避免与 AAPT2 争抢堆内存
- `kotlin.daemon.jvmargs=-Xmx2g`：Kotlin daemon 独立堆内存

## 开发阶段

1. 建立 AI Skill 与文档体系。
2. 搭建 Android 基础架构与底部导航。
3. 建立 Room 数据和 JSON 备份结构。
4. 实现 SAF 扫描与只读媒体索引。
5. 实现媒体列表、详情页、统计、历史、标签。
6. 实现按作品出处分类的虚拟相册。
7. 实现作者管理与文件关联。
8. 实现多主题、统一组件和 UI 打磨。
9. 实现本地推荐和排行榜。
10. 完成设置、数据管理、兼容检查和打包。

## 交接规则

换 AI、换开发工具或交接项目时，必须传完整源码目录，不只传 APK。

> 最后更新：2026-06-19
