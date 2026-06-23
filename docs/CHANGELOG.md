# CHANGELOG - 代码审查与重构历史

本文件归档项目历次代码审查、重构、性能优化的详细记录。当前有效的规则、备案、共享组件表见 `GUIDE_CODE_MAINTENANCE.md`。

## 已完成重构项（2026-06-17）

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

## 全项目审查清理（2026-06-20）

基于 detekt 实测 + 安全审查 + 死代码扫描的全项目代码审查。

| 类别 | 文件/项 | 处理方式 |
|---|---|---|
| 死资源（主题选择器回退残留） | `bg_color_circle.xml`、`bg_color_circle_small.xml`、`bg_preset_card.xml`、`bg_theme_drag_handle.xml`、`bg_theme_sheet.xml`、`ic_check.xml`、`ic_close.xml`、`theme_overlays.xml` | 删除。主题回退决策后未清理的 UI 残留，全项目零引用 |
| 未使用私有属性 | `BarChartView.bgColor`、`LineChartView.valueLabelPaint`、`PieChartView.selectedStrokePaint` | 删除。图表组件开发时预留但未接入绘制流程的画笔/颜色 |
| 未使用参数 | `GpuInfo.maxTextureSize(context)` 的 `context` 参数 | 删除参数 + 同步更新 3 处调用方。EGL14 探测不依赖 Context |
| 未使用参数 | `StatsDetailFragment.renderDistributionComparison` 的 `statsMap` 参数 | 删除参数 + 更新调用处 |
| 命名违规（VariableNaming） | `ScanUseCase` 的 `AUTO_REFRESH_INTERVAL`/`COS_AUTO_REFRESH_INTERVAL` | 移入 `companion object` 改为 `private const val` |
| 隐式默认 Locale（ImplicitDefaultLocale） | `BackupManager.exportPersonalPrefs`、`BiliPlayerView.formatMs` 的 `String.format` | 统一加 `Locale.US`，避免非拉丁 locale 下显示异常字符 |
| 安全加固（备份导入） | `BackupManager.readJson` | 单文件最大 64MB 上限，防恶意/损坏 JSON 导致 OOM |
| 安全加固（备份导入） | `importMediaStats`/`importHistory` | 数值字段 `coerceAtLeast(0)`，防恶意备份注入负数 |
| 安全加固（备份导入） | `importLikes` | 条目数上限 5000 + likeCount 钳制非负 |

**安全审查结论**（9 维度）：Manifest/网络/文件SAF/备份注入/SQL注入/反序列化/Intent/日志/硬编码密钥全部 CLEAN，无 CRITICAL/HIGH/MEDIUM 漏洞。

## WIP 代码审查（2026-06-20）

审查范围：v1.7 数据统计新模块（`ui/stats/`）、自绘图表 widget、DB schema v3、扫描优化、主题/导航等未提交 WIP。

| 类别 | 文件/项 | 处理 |
|---|---|---|
| ImplicitDefaultLocale（detekt 漏检） | `DataStatsFragment`（9 处）、`StatsDetailFragment`（9 处）、`ScanUseCase.buildScanWarning`（1 处） | 统一改 `String.format(Locale.US, ...)` |
| 文档与代码不一致 | `GUIDE_UI.md` 排行榜组件段写 `StatsDetailBottomSheet` | 修正为 `StatsDetailFragment` |

**WIP 审查待办项处理结果**：
1. `MediaStoreObserver` 精准增量死变量清理：移除 `pendingChangeUris` 和 `changedUris`，保留 `pendingChangeCount`。同步更新 `GUIDE_SCAN.md` 防抖时间 2秒→5秒。
2. stats 模块重复代码提取：新增 `ui/stats/StatsFormatHelper.kt` 共享 object，提取 `formatNumber`/`formatSize`/`groupByDay`/`groupByWeek` 4 个逐字重复方法。
3. `viewModel.allMedia` 全量加载（评估后不改）：stats 页复用已有 Flow 做 count/sum 聚合是合理的，改 DAO 聚合收益不抵成本。

## Android Lint + 死类扫描补查（2026-06-20）

**Android Lint**（命令行 `lintDebug` 实测约 2 分钟可完成）：

| 类别 | 项 | 处理 |
|---|---|---|
| Error: UseAppTint | `fragment_stats_detail.xml` 返回箭头用 `android:tint` | 改为 `app:tint` |
| Error: ResAuto ×3 | 3 个布局的 `xmlns:app` 拼写错误（少一个 `/`） | 修正为 `http://schemas.android.com/apk/res-auto` |
| Error: UnsafeOptInUsageError ×5 | `MainActivity` 引用 `MediaDetailFragment` 触发 Media3 opt-in 传播 | `build.gradle.kts` lint disable 加 `UnsafeOptInUsageError` |
| Warning: UnusedResources | 7 个 drawable + 2 个 color | 删除。全项目零引用 |
| Warning: DrawAllocation | `BarChartView.onDraw` 绘制循环内分配对象 | 未修，标记为待优化（条目仅 Top 5，影响可忽略） |

**Kotlin 死类扫描**：发现 `backup/BackupModels.kt` 整个文件是死代码（20 个 data class + 1 个常量，全项目零外部消费），已删除。

## Lint Warning 优化（2026-06-20，243→225）

**已修复**：UseCompatLoadingForDrawables（6 处）、NotShrinkingResources（1）、ConstantLocale/SelectedPhotoAccess/OldTargetApi（lint disable 附理由）。

**保留未修（附理由）**：HardcodedText/SetTextI18n（132，本地中文 App 有意硬编码）、DrawAllocation（15，图表 onDraw 内分配）、ContentDescription 等（22，无障碍提示）、UselessParent 等（5，布局微优化）、Icon 相关（20，启动器图标设计取舍）、ObsoleteDep 等（24，依赖版本升级属单独大动作）、ClickableViewAccessibility（6，自定义 View 手势逻辑）。

## 性能优化（2026-06-21）：COS 分组索引化

修复"点击 COS 作者/相册进列表时主线程卡顿"的问题。根因：COS 路径的作者/作品查找依赖线性扫描，复杂度 O(N×M) 且在主线程执行。

| 文件/方法 | 优化前 | 优化后 | 说明 |
|---|---|---|---|
| `MediaGroupHelper`（新增 `CosAuthorIndex`） | — | O(1) 查找 | `recordKey → 作者显示名` |
| `MediaGroupHelper`（新增 `CosWorkIndex`） | — | O(1) 取作品集 | `作者名 → 作品名列表` |
| `groupByCosAuthor` / `groupByCosWork` | O(N×M) | O(N+M) | 内部先建索引再遍历 |
| `AuthorFilesFragment`/`AlbumDetailFragment` collect 段 | 主线程线性扫描 | 建索引 + O(1) 查找 | 用户反馈的卡顿入口 |
| `SearchFragment` 索引构建 | 两个独立循环各嵌套扫描 | 合并为一个循环 + 索引查找 | 同根因一并修 |

新增 14 个索引单元测试覆盖正确性/边界/语义等价。

## 性能优化（2026-06-22）：真机性能监测三项开销修复

基于真机性能监测发现的三项可优化开销。

| 文件/方法 | 优化前 | 优化后 | 说明 |
|---|---|---|---|
| `AllFilesFragment` collect 块 | 同一 fingerprint render 两次 | 只调一次 `render()` | 首次进入耗时减半 |
| `SourceMatcher.matchAll` | 每次调用重新匹配 | 按 fileName 缓存，命中 O(1) | 消除重复匹配 CPU |
| `SourceMatcher` charMiss 日志 | 每次未命中记 1 条 | 计数器采样，每 50 次记 1 条 | 防日志刷屏 |
| `ThumbnailCache.pregenerateThumbnails` | 全量缓存命中仍走并发池调度 | 入口批量预过滤已缓存文件 | 全量命中时跳过并发池 |

## 全项目代码体检与文档备案补全（2026-06-22）

基于全项目代码审查（源码行数扫描 + `!!`/`runBlocking`/`GlobalScope`/空catch/死代码残留扫描 + 文档-代码一致性对照）。**未发现屎山、未发现功能性 bug、未发现死代码残留**。

| 类别 | 文件/项 | 处理 |
|---|---|---|
| 空安全规范违规 | `BackupManager.updateLatestExportedAt` 的 `latestExportedAt!!` | 改写为局部变量安全调用 |
| 备案表遗漏（红色区） | `BackupManager`（1294 行）、`SourceGroupsData`（1664 行） | 补备案 |
| 备案表遗漏（黄色区） | `StatsDetailFragment`（760 行）、`BiliPlayerView`（755 行） | 补备案 |
| 备案表措辞不准 | `MediaDetailFragment`/`ProfileFragment` 无具体行数 | 补实际行数 |
| 文档-配置不一致 | `detekt.yml` LongMethod 注释与实际不符 | 更新注释 |
| 技术债记录 | 8 个 Fragment 的 `combine + UNCHECKED_CAST` 多 Flow 合并模式重复 | 记入 `DEVELOPMENT_PLAN.md`「技术债」 |

> 最后更新：2026-06-23
