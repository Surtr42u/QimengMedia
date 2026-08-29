# PROJECT_GUIDE - 绮梦影库项目总览

## 项目定位

绮梦影库是一个纯本地 Android 媒体库 App，用于只读扫描用户指定目录中的图片和视频，提供浏览、统计、标签、作者管理、相册分类、推荐、多主题和数据迁移能力。

## 当前基准

- 版本：v1.15（`versionName=1.15` / `versionCode=11`，2026-08-10 全项目巡检修复后同步；v1.17 搜索新增常规作者维度，版本号未递增）
- App 名称：绮梦影库
- Package：`com.qimeng.media`
- Minimum SDK：Android 12 / API 31
- Target SDK：Android 16 / API 36
- 开发语言：Kotlin
- UI：原生 View + ViewBinding；视频播放器使用 Media3 ExoPlayer + 自定义 BiliPlayerView 控制器
- 架构目标：单 Activity + 多 Fragment + MVVM
- 推荐分层：ui/、data/、scan/、domain/、backup/、core/
- data 不依赖 ui；推荐算法（`MediaBrowserLogic`，位于 `ui/browser`）不直接依赖 Fragment；backup 不直接操作原始媒体文件
- ViewModel 暴露 UI state，不暴露数据库 Entity 给 UI 直接修改
- Repository 负责协调 DAO、扫描结果和备份同步
- Repository 方法命名表达业务动作
- 列表、加载、空状态、错误状态统一建模，不用多个零散 Boolean
- 数据库目标：Room 2.8.4（数据库版本 7，v1.15 补 isCosFile 索引）
- 图片加载目标：Coil 3.4.0
- 视频播放目标：Media3 1.8.0 / ExoPlayer
- 文件访问目标：SAF 文件夹授权

## 当前实现进度

> 历次代码审查、重构、性能优化的详细记录见 `docs/CHANGELOG.md`。本节仅保留当前状态结论。

### 架构与基础设施

- 单 Activity + 多 Fragment + MVVM，ViewModel → Repository → DAO 分层
- Room + KSP，11 个 DAO、`AppDatabase`、`LocalMediaRepository`
- ViewModel 按职责拆分为 4 个 UseCase（Scan/Thumbnail/AuthorImport/AutoSync）
- Coil 3.4 图片加载 + Media3 1.8.0 ExoPlayer 视频 + libspng PNG 加速（NDK/CMake）
- SAF + MediaStore 双引擎扫描，常规扫描 + COS 扫描两套独立体系

### 页面与功能

- 底部五 Tab：首页、全部、相册、数据、我的
- 首页：推荐/排行榜/搜索（文件名/标签/出处/角色/作者/COS作者/COS作品多维度匹配，v1.17 起支持常规作者），10 维推荐算法，日周月年榜
- 全部/收藏/浏览历史/作者文件浏览/相册详情：日期分组、胶囊筛选（分区/作品/角色/类型）、双指缩放列数
- 详情页：图片原图加载 + 智能分层渲染 + 左右预加载；视频 BiliPlayerView（B站风格控制器）+ 时间轴标签；标签管理弹窗 ChipGroup 多行自适应 + Flow 驱动增量刷新（v1.19，增删标签不再全量重建/不复活）
- 作者管理：TXT 导入 + COS 扫描自动创建，双向匹配机制；作者列表支持按作者名搜索过滤
- 数据统计页：总览数字卡/浏览趋势（点击进分类型趋势，「全部」范围按跨度动态分桶周/月/季全量不丢弃，v1.16）/常看文件/常看作者标签/分布入口，时间范围全局联动，窗口统计基于按天浏览聚合（v1.14 重构；v1.16 常看详情页拆常看作者 Top 15 + 常看标签 Top 10 两张独立排行卡）
- 数据备份：单文件全量 JSON（`qimeng_backup.json`，17 个数据段含 media_files 元数据与按天浏览聚合）+ 自动同步 + 恢复提示 + 空库覆盖防护（v2.0 重构，v1.14 新增 dailyBrowse 段）
- 我的页数据管理（v1.13 独立页面）：数据管理（`DataManagementFragment`）与数据备份（`DataBackupFragment`）均为独立页面，不再使用弹层；数据管理页平铺 常规目录/COS目录/TXT导入作者 三区块，数据备份页含 备份位置/同步/导入恢复/缓存与同步 区块（v2.0 起移除自定义章节导出报告）

### 主题与图标

- 明暗主题跟随系统，`?attr/qmColor*` 主题属性体系全局生效（v1.11 起中性灰度方案）
- 木纹 XB 雕刻 Adaptive Icon

### 代码质量

- Detekt + Android Lint + ktlint 静态分析，GitHub Actions CI
- 5 个核心测试类覆盖纯逻辑（RecordKeyFactory/MediaBrowserLogic/SourceMatcher/MediaGroupHelper/MediaRenderHelper）
- 当前已备案黄色/红色区项详见 `GUIDE_CODE_MAINTENANCE.md`「当前已备案的黄色区项」
- v1.15 全项目巡检：修复 8 个高危（数据丢失/崩溃级）与 40+ 中低危问题，reviewer 审查闭环（详见 CHANGELOG）
- v1.15 第二轮巡检（2026-08-14）：UI 跨功能联动缺陷批量修复——详情页 viewCount 会话级去重/播放幂等/点赞收藏写入即同步、首页指纹加点赞维度（浏览统计不入指纹，浏览退出保持原样）、收藏页与作者页 SP 监听自动刷新、相册 COS 其他桶与切模式默认展开、全部页/作者文件页指纹补 tags 维度（不含 stats）、列表提交异步化、统计页序号防重、LineChartView 触摸放行滚动、双指缩放与列数按钮列数持久化

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
│           │   ├── ScanSourceLocks.kt
│           │   ├── ThumbnailLoader.kt
│           │   ├── ThumbnailCache.kt
│           │   ├── LargeImageDecoder.kt
│           │   ├── SpngDecoder.kt
│           │   ├── AppLog.kt
│           │   ├── AnrWatchdog.kt
│           │   ├── GpuInfo.kt
│           │   ├── CompatChecker.kt
│           │   ├── MediaCacheCleaner.kt
│           │   └── MediaDetailPrefsCleaner.kt
│           ├── data/
│           │   ├── db/
│           │   │   ├── AppDatabase.kt
│           │   │   ├── dao/          # 11 个 DAO（MediaFile/ViewHistory/ViewStats/DailyBrowse/TimelineTag/AlbumRule/Author/Tag/Setting/ScanSource/CosWork）
│           │   │   ├── entity/       # 13 个实体（MediaFileEntity/ScanSourceEntity/CosWorkEntity/SettingEntity/AuthorEntity/AuthorMediaCrossRef/AlbumRuleEntity/TimelineTagEntity/MediaTagCrossRef/TagEntity/ViewHistoryEntity/ViewStatsEntity/DailyBrowseEntity）
│           │   │   └── model/MediaTypeName.kt
│           │   ├── model/MediaType.kt
│           │   ├── prefs/
│           │   │   └── AppPrefsManager.kt
│           │   └── repository/       # LocalMediaRepository.kt（接口）+ DefaultLocalMediaRepository.kt（实现）
│           ├── scan/
│           │   ├── SafMediaScanner.kt
│           │   ├── MediaStoreScanner.kt
│           │   ├── MediaStoreObserver.kt
│           │   └── ScanUtils.kt
│           ├── backup/
│           │   ├── BackupManager.kt
│           │   └── BackupFileNames.kt
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
│               │   ├── DataManagementFragment.kt   # v1.13 数据管理独立页（常规/COS/TXT 平铺）
│               │   ├── DataBackupFragment.kt       # v1.13 数据备份独立页（含缓存与同步）
│               │   └── SheetUiHelper.kt
│               ├── detail/
│               │   ├── MediaDetailFragment.kt
│               │   ├── TimelineTagHelper.kt
│               │   ├── TagSheetHelper.kt        # v1.19 标签管理弹窗流驱动渲染（ChipGroup 多行 + Flow 增量 diff）
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
│               │   ├── DataStatsFragment.kt     # 数据统计主页面（v1.14：时间范围全局联动 + 常看文件/常看作者标签卡）
│               │   ├── StatsDetailFragment.kt   # 统计详情页（全新界面，四种模式）
│               │   ├── StatsFormatHelper.kt     # 统计页共享格式化与聚合（formatNumber/formatSize/groupByDay/groupByWeek + v1.14 按天聚合计算）
│               │   └── RankListAdapter.kt       # 排行榜列表 Adapter（点击跳转）
│               └── widget/
│                   ├── DimenExt.kt
│                   ├── FlowLayout.kt
│                   ├── LineChartView.kt         # 折线/面积趋势图（自绘 Canvas）
│                   ├── MaxHeightScrollView.kt
│                   ├── PinchZoomHelper.kt
│                   └── PressAnimation.kt        # 按下反馈动画扩展函数 addPressAnimation()
├── docs/
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
│   └── GUIDE_README.md
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

1. 建立 AI 接手规则与文档体系。
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

> 最后更新：2026-08-14
