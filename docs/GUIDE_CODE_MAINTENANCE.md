# GUIDE_CODE_MAINTENANCE - 代码阅读、维护与质量保障

## 实现路径

目标源码路径：项目根目录

## 职责

指导 AI 代理和开发者进行代码阅读、静态分析、依赖管理、单元测试、代码重构和质量保障，确保项目代码可维护、可测试、高质量。

不管：具体业务功能实现、UI 设计、数据模型定义（这些由对应 GUIDE_*.md 负责）

## 代码阅读策略

### Grep + 精准读取（核心策略）

这是行业验证的靠谱策略，Claude Code、OpenAI Codex CLI、Cursor 等主流 AI 编码工具的核心做法：

- **原理**：代码搜索的关键词 95% 是标识符（类名36%、方法名41%、变量名18%），标识符是精确锚点，不会被改述
- **数据支撑**：单轮 Grep 检索效果超过 embedding RAG 基线（Exact Match 38.61% vs 24.99%）（GrepRAG, ISSTA '26）
- **搜索模式**：Grep 定位行号 → offset/limit 精准读取 → 调整关键词 → 再搜
- **优势**：零索引、零维护、零 embedding 成本；比全量读文件省 token
- **trade-off**：用更多搜索轮次换取简洁性，通过 prompt cache 和历史压缩控制 token 消耗

### 本项目的具体实践

- 使用 Grep 工具搜索类名/方法名/变量名定位代码
- 使用 Read 工具的 offset/limit 参数精准读取目标片段
- 禁止全文通读源码文件（AGENTS.md 源码隔离原则）
- 先读指南文档理解模块职责，再用 Grep 定位具体实现

## 静态分析工具

### Android Lint

- 用途：检测 Android 特有问题（未使用的资源、API 兼容性、性能问题、无障碍等）
- 运行：`./gradlew lint`
- 报告位置：`app/build/reports/lint-results-debug.html`
- 集成：已内置在 Android Gradle Plugin 中

### Detekt（Kotlin 静态分析）

- 用途：检测 Kotlin 代码异味、复杂度、命名规范、潜在 bug
- 适合本项目的规则集：
  - 复杂度检查：`ComplexCondition`、`NestedBlockDepth`、`LongMethod`（阈值 120 行，与 `detekt.yml` 一致）
  - 命名规范：`VariableNaming`、`FunctionNaming`、`ClassNaming`
  - 潜在 bug：`UnsafeCallOnNullableType`、`UncheckedCast`、`MayBeConst`
  - 代码异味：`DataClassContainsFunctions`、`TooManyFunctions`（参考 §8 质量指标三档警戒线，detekt.yml 未单独配置此项）
- 运行：`./gradlew detekt`

### ktlint（Kotlin 代码格式化）

- 用途：统一代码风格（缩进、空格、换行、导入排序）
- 运行：`./gradlew ktlintCheck` / `./gradlew ktlintFormat`
- 与 Detekt 互补：ktlint 管格式，Detekt 管逻辑

## 依赖管理

### Gradle Version Catalog

- 项目已使用 `gradle/libs.versions.toml` 管理依赖版本
- 检查依赖更新：`./gradlew dependencyUpdates`

### 依赖分析

- 用途：检测未使用的依赖、依赖冲突
- 工具：`./gradlew :app:dependencies` 查看依赖树

### ProGuard / R8

- Release 构建已启用 `isMinifyEnabled = true`
- 规则文件：`app/proguard-rules.pro`
- 保留策略：Room 实体类、数据模型、SourceMatcher、序列化类
- 验证命令：`./gradlew assembleRelease`
- 新增依赖时需检查是否需要添加 ProGuard keep 规则

## 单元测试

### 测试策略

- 纯逻辑优先：`MediaBrowserLogic`、`SourceMatcher`、`RecordKeyFactory` 不依赖 Android 框架，最容易测试
- ViewModel 测试：使用 `InstantTaskExecutorRule` + `Turbine`（Flow 测试）
- DAO 测试：使用 Room in-memory database

### 运行测试

- 全部测试：`./gradlew test`
- 单个测试类：`./gradlew test --tests "com.qimeng.media.MediaBrowserLogicTest"`

### 测试目录

- `app/src/test/java/com/qimeng/media/` — 单元测试
- `app/src/androidTest/java/com/qimeng/media/` — 仪器测试

## 代码解耦规范

### Fragment 解耦

- Fragment 间禁止直接调用方法（如 `MainActivity.showDetailFragment()`），应通过接口或共享 ViewModel 通信
- 新增页面交互应定义回调接口，由 Activity 实现
- Fragment 不应持有其他 Fragment 的引用

### ViewModel 职责边界

- 单个 ViewModel 方法数目标 ≤25 个（绿色区），可至 25-35（黄色警戒区，需文档说明理由，如多为单行委托或 UI 回调密集）
- 超过 35 个（红色区）时按功能拆分为子 ViewModel 或 UseCase：
  - 扫描调度 → `ScanUseCase`
  - 缩略图预生成 → `ThumbnailUseCase`
  - 作者导入 → `AuthorImportUseCase`
  - 自动同步 → `AutoSyncUseCase`
- ViewModel 只做调度，不包含业务计算逻辑（计算逻辑放 UseCase 或工具类）
- **当前 `MediaLibraryViewModel` 约 32 个方法**：多为单行委托 + UI 回调（like/favorite），下沉会破坏可读性，属黄色区合理状态，已在本文档备案

### 数据层解耦

- Repository 接口按功能域拆分查询方法，不写"万能查询"
- DAO 方法粒度：一个方法只做一件事
- 禁止在 Repository 实现中直接写 SQL（SQL 只在 DAO 中）

### UI 与逻辑分离

- Fragment 只做 UI 绑定和事件分发，不包含业务逻辑
- 数据转换（Entity → UI Model）在 ViewModel 或 UseCase 中完成
- 筛选/排序/分组逻辑集中在 `MediaBrowserLogic` 等工具类

## 编码规范

### 命名规范

- 类名：大驼峰（`MediaFileEntity`、`HomeFragment`）
- 方法名：小驼峰，动词开头（`recordView`、`deleteOrphanAuthors`）
- 变量名：小驼峰，布尔变量用 is/has/can 前缀（`isPregenerating`、`hasMore`）
- 常量：全大写下划线（`MAX_LOG_SIZE`、`DATABASE_NAME`）
- 布局 ID：蛇形小写，类型前缀（`rvHomeGrid`、`swipeRefresh`、`tvTitle`）

### 方法规范

- 单方法行数参考三档警戒线（§8 质量指标）：绿色 ≤80 行、黄色 80-120 行（需 KDoc 说明合理理由，如数据汇总/手势回调/扫描编排）、红色 >120 行（必须重构或显式备案）
- 方法参数不超过 5 个（超过则封装为数据类，参考 `BackupManager.PersonalPrefsReportData`）
- 嵌套不超过 3 层（超过则提取方法或使用 early return）

### 注释规范

- 关键业务逻辑必须添加行内注释（推荐算法、黑帧检测、匹配策略等）
- 公共方法添加 KDoc（参数说明、返回值说明）
- 禁止无意义注释（如 `// set visibility` 上面一行就是 `view.visibility = GONE`）
- 注释用中文

### 空安全规范

- 优先使用 `?.` 和 `?:` 处理可空类型
- 禁止 `!!` 强制解包（除非在 `onViewCreated` 中确认 View 已创建的 `binding get() = _binding!!`）
- ExoPlayer 等生命周期敏感对象使用 `?.let {}` 安全调用，不用 `!!`
- Fragment 中访问 binding 必须通过 `_binding?.` 安全调用
- 方法返回可空类型时，在 KDoc 中说明何时返回 null

## 代码重构指南

### 安全重构原则

- 小步修改：每次只改一个关注点
- 测试保护：重构前确保有测试覆盖
- 文档同步：重构后更新对应指南文档

### 常见重构场景

| 场景 | 方法 | 风险 |
|------|------|------|
| 提取方法 | 选中代码块 → Extract Function | 低 |
| 重命名 | IDE Refactor → Rename | 低 |
| 移动类 | IDE Refactor → Move | 中 |
| 拆分大类 | 先提取方法 → 再提取类 | 高 |
| 修改接口 | 先添加新方法（带默认实现）→ 逐步迁移 | 中 |

### 大型重构流程

1. 先添加测试覆盖目标代码
2. 小步重构，每步后运行测试
3. 每步提交一个 commit
4. 全部完成后更新文档

## 代码质量指标

### 核心理念：按功能实际需要决定代码规模，而非压行数

行数只是最初的气味信号。真正决定是否需要重构的指标是：**圈复杂度/认知复杂度（是否难以理解）、模块内聚度与耦合度（改一处是否牵动多处）、重复代码率、可测试性（能否轻松写单元测试），以及该模块的 bug 频率和变更热区**。

文件级不超过 1000 行、函数圈复杂度不超过 10-15 等可作为参考阈值，但核心永远是"修改和维护是否容易"，而不是单纯压行数。

### 三档警戒线（弹性规范）

| 指标 | 绿色（常规） | 黄色（警戒，需备案） | 红色（需重构） |
|------|------|------|------|
| Fragment/类总行数 | ≤600 | 600-1000 | >1000 |
| 单方法行数 | ≤80 | 80-120 | >120 |
| 方法数（ViewModel） | ≤25 | 25-35 | >35 |
| 圈复杂度 | ≤10 | 10-20 | >20 |
| 嵌套深度 | ≤3 | 3-4 | >4 |
| 方法参数 | ≤5 | 5-8 | >8 |

**黄色区处理**：在方法/类 KDoc 注释说明"为何超长合理"。典型合理超长场景：
- 数据汇总/报告生成（如 `BackupManager.exportPersonalPrefs` 汇总 23 个数据源）
- 手势回调/事件分发（如 `BiliPlayerView.onTouchEvent` 天然多分支）
- 扫描编排/流程调度（如 `ScanUseCase.autoRefreshAllSources` 多源顺序刷新）
- 设置项弹窗编排（如 `ProfileFragment` 多设置项顺序组装）

**红色区处理**：必须重构或显式备案（在本文档记录不可重构的技术原因）。

### 当前项目状态对照

| 指标 | 目标 | 当前状态 | 检查方式 |
|------|------|----------|----------|
| 方法长度 | 绿色区 | 阈值 120（detekt.yml），黄色区已备案 | Detekt LongMethod |
| 圈复杂度 | 绿色区 | 阈值 20（detekt.yml），逐步收紧 | Detekt CyclomaticComplexMethod |
| 嵌套深度 | 绿色区 | 达标 | Detekt NestedBlockDepth |
| 测试覆盖率 | > 60%（纯逻辑类 > 80%） | 已有 5 个核心测试类 | ./gradlew test |
| Lint 警告 | 0 个 Error | 命令行卡死，改用 AS | Android Studio Inspect Code |
| Detekt 问题 | 逐步减少 | 已配置 ignoreFailures | ./gradlew detekt |

### 当前已备案的黄色区项（基于 detekt 实证数据 2026-06-17）

| 文件/方法 | 圈复杂度 | 区位 | 备案理由 |
|---|---|---|---|
| `BiliPlayerView.onTouchEvent` | 31 | 黄色（合理复杂度） | 触摸事件状态机，11 个状态变量在 DOWN/MOVE/UP 间流转，时序敏感，拆分有 bug 风险 |
| `MediaDetailFragment` | LargeClass | 黄色（类总行数） | 详情页 chrome 四层结构 + 图片/视频双分支 + 预渲染，已提取 showImage/showVideo/setupVideoTouchOverlay |
| `ProfileFragment` | LargeClass | 黄色（类总行数） | 设置项弹窗编排，硬拆破坏可读性 |
| `MediaLibraryViewModel` | 32 方法 | 黄色（方法数） | 多为单行委托 + UI 回调（like/favorite），下沉会变乱 |
| `ScanUseCase.autoRefreshAllSources` | 24 | 黄色（合理复杂度） | 扫描编排：多源顺序刷新（常规+COS），每源有新增/删除/更新，拆分破坏流程可读性 |
| `ScanUseCase.scanCosMedia` | 22 | 黄色（合理复杂度） | COS 扫描编排，同上 |
| `SourceMatcher.matchCharacters` | 21 | 黄色（合理复杂度） | 角色匹配算法：两层匹配（变体表+别名表）+ 区域重叠检测，拆分破坏匹配完整性 |
| `AuthorImportUseCase.parseAuthorBlocks` | 20 | 黄色（合理复杂度） | TXT 解析：块识别+作者提取+文件关联，解析逻辑天然多分支 |
| `MainActivity.onCreate` | 20 | 黄色（合理复杂度） | 生命周期初始化：底部导航+Fragment+权限+主题，初始化顺序敏感 |
| `BackupManager.exportPersonalPrefs` | ~280 行 | 黄色（方法行数） | 报告生成汇总 23 个数据源，天然复杂，已封装 PersonalPrefsReportData + 章节子方法 |
| `SourceGroupsData` | ~1664 行 | 绿色（纯数据声明） | 圈复杂度=0，BUILTIN_GROUPS 出处+角色检索表，无逻辑 |

### 已完成重构项（本次 2026-06-17）

| 文件/方法 | 重构前 CC | 重构后 CC | 重构方式 |
|---|---|---|---|
| `MediaDetailFragment.showMediaAt` | 29 | ~5 | 提取 showImage/showVideo/setupVideoTouchOverlay/isTapGesture/needsMetadataDecode |
| `MediaBrowserLogic.recommend` | 31 | ~6 | 提取 resolveWeights/computeNormDenominators/shuffleBuckets + RecommendWeights/NormDenominators 数据类 |
| `MediaDetailFragment` ComplexCondition ×2 | 4 | 1 | 提取 isTapGesture/needsMetadataDecode 辅助函数 |
| `AllFilesFragment.render` | 48 | <20 | 拆分为 render()编排 + computeAllGroupsAsync()协程计算 + updateAllUI()UI更新，接入 MediaRenderHelper.applyTypeFilter/computeDisplayed/buildFingerprint |
| `FavoriteFragment.render` | 44 | <20 | 同 AllFilesFragment，额外提取 updateFavoriteEmptyState() |
| `BrowseHistoryFragment.render` | 40 | <20 | 同 AllFilesFragment，额外提取 updateHistoryEmptyState() |
| `AuthorFilesFragment.render` | 36 | <20 | 拆分为 render() + computeAuthorGroupsAsync() + updateAuthorUI()，接入 MediaRenderHelper.applyTypeFilter/computeDisplayed |
| `AlbumDetailFragment.render` | 22 | <20 | 拆分为 render() + computeAlbumGroupsAsync() + updateAlbumUI()，接入 MediaRenderHelper.applyTypeFilter/computeDisplayed |
| `MediaFilterSheet.show` | 35 | <20 | 拆分为 show()编排 + appendTimeRangeSection + appendTagsSection + buildFooter，引入 FilterStateHolder 解决闭包问题 |
| `MediaRenderHelper`（新增） | - | - | 纯计算型 object，封装 applyTypeFilter/computeDisplayed/buildFingerprint，5 个 Fragment 共享，消除重复代码 |

### Detekt 配置

- 配置文件：`detekt.yml`（项目根目录）
- 运行：`./gradlew detekt`
- 报告：`app/build/reports/detekt/detekt.html`
- 当前设为 `ignoreFailures=true`（不阻断构建），后续逐步收紧阈值
- 已修复：UnusedPrivateMember、UnusedParameter、WildcardImport、NewLineAtEndOfFile 等

### 已有单元测试

| 测试类 | 覆盖目标 |
|--------|----------|
| `RecordKeyFactoryTest` | 主键生成逻辑（12 个测试） |
| `MediaBrowserLogicTest` | formatSize/dateLabel/groupByDate（14 个测试） |
| `SourceMatcherTest` | 出处匹配/变体/版本号合并/自定义出处/txt作品（18 个测试） |
| `MediaGroupHelperTest` | 分组算法（partition/source/character 模式） |
| `MediaRenderHelperTest` | applyTypeFilter 类型筛选/computeDisplayed 选中状态过滤/buildFingerprint 渲染指纹构建（18 个测试） |

### detekt CI 集成

- **GitHub Actions**：`.github/workflows/detekt.yml`，push/PR 到 main 时自动运行 detekt + 单元测试
- **本地 pre-commit hook**：`.githooks/pre-commit`，提交前自动运行 detekt
  - 启用方式：`git config core.hooksPath .githooks`
  - 跳过方式：`git commit --no-verify`（不推荐）
- detekt 配置 `ignoreFailures=true`，仅警告不阻断构建

## 共享逻辑与组合组件模式

### 设计原则

当多个 Fragment/页面存在相同或高度相似的逻辑时，必须提取为共享工具类，禁止复制粘贴后微调。共享逻辑分为两类：

| 类型 | 适用场景 | 示例 |
|------|----------|------|
| **计算型 object** | 纯数据计算，无 Android 依赖 | `MediaGroupHelper`、`MediaBrowserLogic`、`MediaRenderHelper` |
| **渲染型 object** | UI 组件构建，依赖 Context | `MediaPillsHelper` |

### 已有共享组件

| 组件 | 路径 | 职责 | 使用者 |
|------|------|------|--------|
| `MediaGroupHelper` | `ui/browser/MediaGroupHelper.kt` | 出处/角色/COS 分组计算 | AllFilesFragment, FavoriteFragment, BrowseHistoryFragment, AlbumDetailFragment, AuthorFilesFragment |
| `MediaPillsHelper` | `ui/browser/MediaPillsHelper.kt` | 出处/角色/分区/类型药丸渲染 | AllFilesFragment, FavoriteFragment, BrowseHistoryFragment, AlbumDetailFragment, AuthorFilesFragment |
| `MediaRenderHelper` | `ui/browser/MediaRenderHelper.kt` | 渲染共享计算（applyTypeFilter 类型筛选/computeDisplayed 选中状态过滤/buildFingerprint 渲染指纹构建） | AllFilesFragment, FavoriteFragment, BrowseHistoryFragment, AuthorFilesFragment（AlbumDetailFragment 仅用 applyTypeFilter/computeDisplayed，不用 buildFingerprint） |
| `TimelineTagHelper` | `ui/detail/TimelineTagHelper.kt` | 视频时间轴标签弹窗交互 | MediaDetailFragment |
| `SheetUiHelper` | `ui/profile/SheetUiHelper.kt` | 弹窗 UI 组件构建（标题/行/按钮） | ProfileFragment |
| `DimenExt` | `ui/widget/DimenExt.kt` | dp/dpFloat 尺寸转换扩展函数（Int.dp(Context)/Float.dp(Context)/Int.dpFloat(Context)） | MediaDetailFragment, BiliPlayerView, HomeFragment, AlbumFragment, ZoomImageView, AuthorListFragment, MediaFilterSheet, GroupedMediaAdapter, TimelineTagHelper, SheetUiHelper |
| `MediaBrowserLogic` | `ui/browser/MediaBrowserLogic.kt` | 推荐/排行/筛选/日期分组/格式化工具 | HomeFragment, AllFilesFragment, AlbumDetailFragment, MediaDetailFragment, MediaThumbnailAdapter |
| `PinchZoomHelper` | `ui/widget/PinchZoomHelper.kt` | 双指缩放列数共享组件（ScaleGestureDetector 缩放逻辑 + GridLayoutManager 创建含 SpanSizeLookup）；ColumnsRef 类包装列数状态 | AllFilesFragment, AlbumDetailFragment, BrowseHistoryFragment, AuthorFilesFragment, FavoriteFragment |
| `BrowseStatsChartView` | `ui/widget/BrowseStatsChartView.kt` | 浏览统计柱状图（自绘 Canvas，水平柱条 + 主题色 + 空状态提示，Top N 文件热度展示） | DataStatsFragment |
| `LineChartView` | `ui/widget/LineChartView.kt` | 折线/面积趋势图（自绘 Canvas，渐变面积 + 折线 + 数据点 + 入场动画 + 点击数据点高亮并显示数值气泡，48dp 命中阈值，气泡位置自适应上下） | DataStatsFragment, StatsDetailFragment |
| `BarChartView` | `ui/widget/BarChartView.kt` | 竖向柱状图（自绘 Canvas，渐变填充 + 点击高亮 + 入场动画） | DataStatsFragment |
| `PieChartView` | `ui/widget/PieChartView.kt` | 环形图（自绘 Canvas，多色调色板 + 中心总览 + 纵向图例 + 入场动画，纯展示不消费触摸事件，0值在图例显示但不绘制扇区） | DataStatsFragment |
| `RankListAdapter` | `ui/stats/RankListAdapter.kt` | 通用排行榜列表 Adapter（点击跳转，前三名高亮，副标题可选，进度条相对第一名百分比，DiffUtil 局部更新） | DataStatsFragment, StatsDetailFragment |
| `StatsDetailFragment` | `ui/stats/StatsDetailFragment.kt` | 统计详情页（点击卡片进入全新界面，统计摘要+数据洞察+辅助图表+Top 20 排行榜/分布对比卡片，四种模式，文件模式支持热度/时长排序切换，分布模式显示类型/来源两张对比卡片，浏览趋势图支持点击数据点显示数值气泡） | DataStatsFragment |
| `PressAnimation` | `ui/widget/PressAnimation.kt` | 按钮按下反馈动画（View.addPressAnimation() 扩展函数，缩放 0.92 + 100ms AccelerateDecelerateInterpolator） | MediaDetailFragment, HomeFragment |
| `GpuInfo` | `core/GpuInfo.kt` | GPU 纹理上限探测（EGL14 创建临时 context 查 `GL_MAX_TEXTURE_SIZE`，懒加载+@Volatile 缓存，探测失败回退 4096） | QimengApplication（ImageLoader maxBitmapSize 配置）, ZoomImageView（智能分层渲染判断） |

### 共享胶囊筛选体系

所有带芯片栏+药丸筛选的页面统一调度于全部页（`AllFilesFragment`）的算法，通过三个共享组件实现：`MediaGroupHelper`（分组计算）、`MediaPillsHelper`（药丸渲染）、`MediaRenderHelper`（渲染共享计算：类型筛选/选中状态过滤/渲染指纹构建）。各页面差异仅在于**芯片栏配置不同**（根据页面上下文省略某些维度），算法逻辑完全一致。

**核心原则**：全部页是"完整版"，其他页面是"按上下文裁剪版"。新增页面如需胶囊筛选，必须复用这三个共享组件，禁止自行实现分组、药丸渲染或渲染指纹构建逻辑。

**芯片栏配置（按页面）**：

| 页面 | 分区 | 作品 | 角色 | 类型 | 省略原因 |
|------|------|------|------|------|----------|
| 全部页 | ✓ | ✓ | ✓ | ✓ | 完整版 |
| 收藏页 | ✓ | ✓ | ✓ | ✓ | 完整版 |
| 浏览历史 | ✓ | ✓ | ✓ | ✓ | 完整版 |
| 相册详情页(常规) | ✗ | ✗ | ✓ | ✓ | 已锁定出处（相册=按出处分组），无需分区和作品 |
| 相册详情页(COS) | ✗ | ✗ | ✓ | ✓ | 已锁定出处（COS出处=作者），无需分区和作品 |
| 作者详情页(常规) | ✗ | ✓ | ✓ | ✓ | 已锁定作者，无需分区 |
| 作者详情页(COS) | ✗ | ✗ | ✓ | ✓ | 已锁定作者且出处=作者，无需分区和作品 |

**算法调度规则**：

- **常规模式**：`groupBySource()`（作品分组）→ `groupByCharacter()`（角色分组），均使用 `SourceMatcher.matchAll()` 推断出处+角色
- **COS 模式**：作品分组按作者名（`AuthorMediaCrossRef` 关联），角色分组按作品名（`groupByCosWork()`，`CosWorkEntity.workName` 匹配）
- **递归筛选**：选了作品后角色只显示该作品下的角色（作品→角色联动）
- **药丸默认收起**：所有页面的药丸区域默认折叠，点击芯片展开，再次点击已选中芯片切换展开/折叠

### mergeGroups() 分组合并模式

当分区模式为「全部」时，需要将常规和 COS 两组分组结果合并为一个 Map。相同 key（尤其是"其他"）的文件列表必须拼接，不能覆盖。

**方法签名**：
```kotlin
private fun mergeGroups(regular: Map<String, List<MediaFileEntity>>, cos: Map<String, List<MediaFileEntity>>): Map<String, List<MediaFileEntity>>
```

**用途**：在「全部」分区模式下，出处分组和角色分组都需要合并常规与 COS 的结果。

**使用位置**：AllFilesFragment、FavoriteFragment、BrowseHistoryFragment（各 Fragment 内部私有方法，逻辑一致）。

**合并逻辑**：
1. 遍历 regular 的每个 key，将文件列表加入结果
2. 遍历 cos 的每个 key，若 key 已存在则拼接文件列表（`existing + cosFiles`），否则新建条目
3. 确保"其他"等重复 key 的文件不会丢失

### 新增共享组件的判断标准

当以下任一条件满足时，必须提取共享组件：

1. **3 个以上页面**存在相同逻辑（如药丸渲染、分组计算）
2. **2 个页面**的逻辑超过 50 行且结构高度相似
3. 新增页面时发现可以复用已有页面的逻辑

### 提取步骤

1. 在共享逻辑所在目录创建 `object` 类（计算型无状态，渲染型接受 Context 参数）
2. 将重复逻辑移入 object，方法签名保持通用
3. 各 Fragment 调用 object 方法替代本地实现
4. 删除 Fragment 中的重复私有方法
5. 更新本文档的"已有共享组件"表格

### 禁止事项

- 禁止为共享组件引入 Fragment/Activity 依赖（应通过参数传递）
- 禁止共享组件直接操作 ViewModel（应通过回调）
- 禁止在共享组件中硬编码字符串（应通过参数传入）

## Fragment/类规模弹性规范

### 判定标准（三档警戒线）

Fragment/类规模按 §8 三档警戒线判定，不设硬性"必须拆分"行数：

| 区位 | 类总行数 | 处理方式 |
|------|------|------|
| 绿色 | ≤600 行 | 无需特别处理 |
| 黄色 | 600-1000 行 | 需在本文件 §8「当前已备案的黄色区项」备案，说明合理理由 |
| 红色 | >1000 行 | 必须拆分，或显式备案不可重构的技术原因 |

### 何时应拆分（真正信号）

不要只看行数。出现以下任一情况时，即使未到红色区也应拆分：

1. **圈复杂度高**：单方法圈复杂度 >15，嵌套深、分支多，难以理解
2. **低内聚高耦合**：一个类承担多个不相关功能域，改一处牵动多处
3. **重复代码**：与其他 Fragment/类存在 >50 行高度相似逻辑
4. **可测试性差**：核心逻辑无法独立写单元测试
5. **bug 频繁/变更热区**：该类频繁出 bug 或频繁被修改

### 何时不应拆分（合理复杂度）

以下场景的超长属合理复杂度，应靠备案合法化而非强行拆分：

- **数据汇总/报告生成**：天然需要汇总多数据源（如 `BackupManager.exportPersonalPrefs`）
- **手势回调/事件分发**：天然多分支（如 `BiliPlayerView.onTouchEvent`）
- **扫描编排/流程调度**：多源顺序刷新（如 `ScanUseCase.autoRefreshAllSources`）
- **纯数据声明**：圈复杂度=0（如 `SourceGroupsData` 1664 行检索表）
- **设置项弹窗编排**：顺序组装多设置项（如 `ProfileFragment`）

### 拆分策略（进入红色区或出现真正信号时）

| Fragment 类型 | 拆分方式 | 示例 |
|---------------|----------|------|
| 详情页 | 按功能域提取 Helper | `TimelineTagHelper`（时间轴标签）、图片加载、视频播放 |
| 列表页 | 按渲染域提取 Helper | `MediaPillsHelper`（药丸）、`MediaGroupHelper`（分组） |
| 设置页 | 按功能域提取子方法组 | 扫描配置、备份配置、主题配置 |

### 拆分步骤

1. 识别 Fragment 中的功能域（如弹窗、加载、播放、筛选）
2. 将功能域的私有方法提取为独立 object 类
3. Fragment 通过调用 object 方法替代本地实现
4. 确保拆分后 Fragment 只保留：生命周期方法、状态变量、事件分发
5. 运行 `./gradlew assembleDebug` + `./gradlew test` 验证
6. 更新本文件 §8「当前已备案的黄色区项」

### 拆分后 Fragment 的理想结构

```kotlin
class XxxFragment : Fragment() {
    // 1. Binding
    // 2. ViewModel
    // 3. 状态变量
    // 4. onCreateView / onViewCreated / onDestroyView
    // 5. 事件分发方法（调用 Helper/UseCase）
    // 6. 数据观察方法
}
```

## 代码规范优先级（强制）

**本节是所有 AI 代理接手项目的第一要务。** 在阅读任何功能模块的指南之前，必须先理解本文件的编码规范。

### 优先级排序

1. **代码规范**（本文件 §6 编码规范 + §9 共享逻辑 + §10 拆分规范）— 所有代码修改的前提
2. **架构规范**（本文件 §5 代码解耦规范）— 分层和职责边界
3. **质量指标**（本文件 §8 代码质量指标）— 量化目标
4. **功能模块指南**（`docs/GUIDE_UI.md`、`docs/GUIDE_DATA.md` 等）— 具体业务规则

### 为什么代码规范优先于功能指南？

- 代码规范是"怎么写代码"的规则，功能指南是"写什么代码"的规则
- 不遵守代码规范的代码，即使功能正确，也会在维护时产生回归 bug
- 代码重复、方法过长、职责不清是项目腐化的根源
- 先看规范再写功能，比写完再改成本低 10 倍

### 强制自检

每次准备写代码前，用 30 秒完成以下自检：

1. 我要写的方法会进入黄色区(>80 行)吗？→ 若是数据汇总/手势回调/扫描编排等合理复杂度则可，需 KDoc 说明；否则先规划子方法
2. 这个逻辑在其他 Fragment 里有没有？→ 有则用共享组件
3. 修改的 Fragment 会进入黄色区(>600 行)吗？→ 若是合理复杂度则可，需在本文件 §8 备案；否则先规划拆分
4. 新增的方法/类命名是否符合规范？→ 不符合则先调整命名
5. 这个修改需要更新哪些文档？→ 列出后同步更新

## 修改注意事项

- 重构不改变功能行为，只改善代码结构
- 重构后必须运行 `./gradlew assembleDebug` 确认编译通过
- 重构后必须运行 `./gradlew test` 确认测试通过
- 重构后必须更新对应指南文档
- 禁止在重构中夹带功能修改

> 最后更新：2026-06-17
