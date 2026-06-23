# PROJECT_GUIDE - 绮梦影库项目总览

## 项目定位

绮梦影库是一个纯本地 Android 媒体库 App，用于只读扫描用户指定目录中的图片和视频，提供浏览、统计、标签、作者管理、相册分类、推荐、多主题和数据迁移能力。

## 当前基准

- 版本：v1.11
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
- 数据库目标：Room
- 图片加载目标：Coil 3.4.0
- 视频播放目标：Media3 / ExoPlayer
- 文件访问目标：SAF 文件夹授权

## 当前实现进度

> 历次代码审查、重构、性能优化的详细记录见 `docs/CHANGELOG.md`。本节仅保留当前状态结论。

### 架构与基础设施

- 单 Activity + 多 Fragment + MVVM，ViewModel → Repository → DAO 分层
- Room + KSP，10 个 DAO、`AppDatabase`、`LocalMediaRepository`
- ViewModel 按职责拆分为 4 个 UseCase（Scan/Thumbnail/AuthorImport/AutoSync）
- Coil 3.4 图片加载 + Media3 ExoPlayer 视频 + libspng PNG 加速（NDK/CMake）
- SAF + MediaStore 双引擎扫描，常规扫描 + COS 扫描两套独立体系

### 页面与功能

- 底部五 Tab：首页、全部、相册、数据、我的
- 首页：推荐/排行榜/搜索，10 维推荐算法，日周月年榜
- 全部/收藏/浏览历史/作者文件浏览/相册详情：日期分组、胶囊筛选（分区/作品/角色/类型）、双指缩放列数
- 详情页：图片原图加载 + 智能分层渲染 + 左右预加载；视频 BiliPlayerView（B站风格控制器）+ 时间轴标签
- 作者管理：TXT 导入 + COS 扫描自动创建，双向匹配机制
- 数据统计页：折线趋势/环形分布/柱状排行/横向柱条
- 数据备份：10 类 JSON 导出导入 + 个人偏好报告 + 自动同步 + 恢复提示 + 空库覆盖防护

### 主题与图标

- 明暗主题跟随系统，`?attr/qmColor*` 主题属性体系全局生效（v1.11 起中性灰度方案）
- 木纹 XB 雕刻 Adaptive Icon

### 代码质量

- Detekt + Android Lint + ktlint 静态分析，GitHub Actions CI
- 7 个核心测试类覆盖纯逻辑（RecordKeyFactory/MediaBrowserLogic/SourceMatcher/MediaGroupHelper/MediaRenderHelper）
- 当前已备案黄色/红色区项详见 `GUIDE_CODE_MAINTENANCE.md`「当前已备案的黄色区项」

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
│           │   ├── GpuInfo.kt
│           │   ├── CompatChecker.kt
│           │   ├── MediaCacheCleaner.kt
│           │   └── MediaDetailPrefsCleaner.kt
│           ├── data/
│           │   ├── db/
│           │   │   ├── AppDatabase.kt
│           │   │   ├── dao/          # 10 个 DAO（MediaFile/ViewHistory/ViewStats/TimelineTag/AlbumRule/Author/Tag/Setting/ScanSource/CosWork）
│           │   │   ├── entity/       # 13 个实体（MediaFileEntity/ScanSourceEntity/CosWorkEntity/SettingEntity/AuthorEntity/AuthorMediaCrossRef/AlbumRuleEntity/TimelineTagEntity/MediaTagCrossRef/TagEntity/ViewHistoryEntity/ViewStatsEntity/AuthorFileCount）
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
│               │   ├── StatsFormatHelper.kt     # 统计页共享格式化与聚合（formatNumber/formatSize/groupByDay/groupByWeek）
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

> 最后更新：2026-06-23
