# CHANGELOG - 代码审查与重构历史

本文件归档项目历次代码审查、重构、性能优化的详细记录。当前有效的规则、备案、共享组件表见 `GUIDE_CODE_MAINTENANCE.md`。

## AI 署名约定

- 每个变更条目标注实际执行该改动的 AI 模型（如 `deepseek-v4-flash`），便于追溯每次改动由谁完成。
- **每个完成/提交的记录都单独署名**：同一天同一 AI 完成多个条目时，每个条目都标注执行 AI，不合并、不省略。
- 多 AI 协作（并行代理）时各条目自行署名：每个条目标注实际执行该改动的 AI。
- 模型名使用执行时的真实命名（品牌-版本），不写模糊描述（如"某个 AI"）。
- 2026-06 及更早的历史条目不补录 AI 身份；2026-08-08 当天条目已于 2026-08-09 补录完毕。

## 硬编码清理与扩展名映射统一（v1.20，2026-08-29）

执行 AI：GLM-5.3-Flash

全库硬编码排查（Kotlin 字符串/数字、Kotlin 颜色、XML 资源三路扫描）后按方案基线清理真问题；页面级中文文案（2026-06-20 既有 Lint HardcodedText 豁免决策）、播放器固定色、图表图例色、路径/URI 字面量属「有意设计」保持现状。

| 类别 | 改动 |
|---|---|
| 布局颜色入主题 | 6 个布局 26 处 `@color/qm_*` → `?attr/qmColor*`（text_secondary×24、text_primary×1、bg×1），消除对「页面禁止硬编码 `@color/qm_*`」禁令的违规 |
| 缩略图占位符统一 | 新增 `drawable/qm_placeholder_bg.xml`（shape 引用 `?attr/qmColorChipBg`，明暗取值与原 qm_placeholder #F0F0F2/#2E2E2E 逐字节一致），`MediaThumbnailAdapter` 8 处 Coil placeholder/error 改用之；删除 values/values-night 两处 `qm_placeholder` 颜色定义（无残留引用） |
| 扩展名单一真相源 | `MediaType` 新增扩展名集合与 `mediaTypeForExtension()`（逻辑自 `ScanUtils` 迁入，ScanUtils 改委托、调用方零改动）；`AuthorImportUseCase` 媒体集合改引 MediaType，TXT 行识别容错集拆为 `MEDIA_NON_INDEXED_FILE_EXTENSIONS`（wmv/bmp/svg/tiff/tif）+ 文档类（txt/doc/pdf）；`MediaDetailFragment`/`MediaThumbnailAdapter` 散落 `equals("gif")/("png")` 改集合判断 |
| 浏览统计分类修复 | `DefaultLocalMediaRepository` 浏览兜底分类改走 MediaType 映射：mkv/avi/flv/webm 不再误判为 image（原仅认 mp4/mov）；无点文件名经 `MediaType.extensionOf`（显式空串默认）保持原兜底语义 |
| 共享组件文案参数化 | `LineChartView` 空态文案/气泡单位改构造参数 `emptyText`/`valueSuffix`（默认值不变，调用方零改动）；`GroupedMediaAdapter` 量词改 `countSuffix` 参数（HeaderHolder 透传）；默认渲染与改前逐字节一致 |
| 魔法数字收拢 | `BackupManager` `pruneToLimit(500)` → `LocalMediaRepository.HISTORY_LIMIT`；`SpngDecoder` 256MB → `LargeImageDecoder.MAX_DECODE_BYTES`（去 private）；`AuthorListFragment` 七天毫秒 → `StatsFormatHelper.WEEK_MILLIS`（去 private）；`MainActivity` 两处 `delay(3000)` → `SCAN_START_DELAY_MILLIS` |
| 重复文案常量化 | `DataBackupFragment` 3 对、`DataManagementFragment` 3 对同文件逐字重复 Toast 文案收为 private const（插值改 `.format()` 模板，文案逐字节一致） |
| 文档 | `GUIDE_THEME`（颜色清理与占位符选型依据）、`GUIDE_DATA`（MediaType 真相源）、`GUIDE_SCAN`（委托说明）、`GUIDE_AUTHOR`（扩展名判定口径）、`GUIDE_UI`（组件参数化合约）、`GUIDE_CODE_MAINTENANCE`（跨文件共享常量表） |

**审查**：reviewer 对抗审查抓出 2 处返工并已修复——`hasExt` 集合误收窄（wmv/bmp/svg/tiff/tif 恢复，22 个扩展名新旧布尔对照逐项一致）；兜底 `substringAfterLast('.')` 丢空串默认参数（无点文件名会误判，改用 `MediaType.extensionOf`）。

**验证**：`gradlew assembleDebug testDebugUnitTest` 通过（返工后重跑）；`gradlew --stop` 已执行。

## 详情页标签管理多行显示与增删卡顿修复（v1.19，2026-08-26）

执行 AI：deepseek-v4-flash

用户反馈：详情页标签管理弹窗标签区单行水平滚动、标签多时拥挤；标签增删偶发卡顿/有延迟（删除后 chip 短暂不消失）。

真机实测定位（真机 / Android 16，全库 18 个标签）：每次增删后 `refreshTagContent` 走「手动查库 → removeAllViews 全量重画全部 chip」，主线程阻塞 17~63ms（其中查询仅 4~19ms、写库 2~11ms），18 个 Material Chip 就要 20~60ms；且「立即查库重建」与异步写库竞态——实测删除完成后 8ms 的刷新查询仍命中旧数据（20:04:04.602 删库完成 → 查询读旧 → 重建后已删 chip 复活约 500ms，直到下一次操作才消失），即用户感知的"删除延迟"；连续快速增删时重建背靠背叠加。

| 类别 | 改动 |
|---|---|
| 提取 Helper | 新增 `ui/detail/TagSheetHelper.kt`（object）：承接标签管理弹窗的 Flow 驱动渲染——`combine(tagEntitiesForMedia, allTags)` 观察流发射后按 tagId 双指针 diff 同步两组 ChipGroup，只移除/新增变化段，未变化 chip 原实例保留；空态（暂无标签）与其他标签区显隐随流数据切换；返回 Job 供弹窗 `setOnDismissListener` 取消（防泄漏与重复监听） |
| 布局多行 | 两组「当前标签/其他标签」由 `HorizontalScrollView` 单行横滑改为 `ChipGroup` 多行自适应（chipSpacing 8dp/4dp，标签少一行、多则自动 2~N 行，弹窗外层 ScrollView 可上下滚动），消除拥挤 |
| 竞态修复 | 删除「手动查库 + removeAllViews 全量重建」路径（`refreshTagContent`/`activeTagChip`/`suggestTagChip` 删除）；渲染唯一数据源为 Room 观察流，写入完成后必然回放，不再读到旧数据、不再出现删除复活 |
| 排序调整 | 详情页标签弹窗排序改为「最近添加的标签置顶」（满足用户语义 abcd+新添 q→qabcd / 删 c→qabd / 再添 c→cqabd）：`MediaTagCrossRef` 新增 `createdAtMillis` 关联时间字段（数据库 v7→v8 Migration，旧关联按标签创建时间回填近似），当前标签组按关联时间倒序（新 DAO `observeTagEntitiesForMediaNewestFirst`）；其他标签组按标签创建时间倒序（`observeTagsNewestFirst`）；备份 JSON `mediaTagRefs` 段新增可选 `createdAtMillis`（旧备份缺省用导入时刻），仅详情页弹窗使用，筛选面板/统计等保持名字序 |
| 数据安全 | 数据层零改动：TagDao/TagEntity/MediaTagCrossRef/Repository/备份 JSON（`tags.json`）均不变；增删仍走 viewModel.addTag/removeTag，详情页删除仍只解除当前文件关联（全局删除仍在筛选面板 `deleteTagById`） |
| 文档 | `GUIDE_UI.md` 详情页标签管理描述；`GUIDE_CODE_MAINTENANCE.md` 备案行数（1423→1344，未跨档）+ 共享组件表 `TagSheetHelper`；`GUIDE_DEBUG.md` TagSheet 日志说明 |

**验证**：`:app:assembleDebug` + `./gradlew test` 通过；`gradlew --stop` 已执行；真机安装复测增量流更新日志（`render: 流更新 … 增量同步耗=Xms`，X 降至个位数 ms 量级）。

## 视频缩略图代表帧策略（v1.18，2026-08-24）

执行 AI：deepseek-v4-flash

用户反馈 App 视频缩略图效果不如电脑（Windows 资源管理器）。调查确认：电脑缩略图由 K-Lite 内的 Icaros 接管（自带 FFmpeg），核心策略为「内嵌封面优先 → 按视频时长 35% 偏移抓代表帧 → 黑白帧检测跳过不合格帧→自身缓存」；并用 ffprobe 实测用户参考库（376 个视频）**0 个带内嵌封面**，证实电脑效果 100% 来自 35% 代表帧而非封面。App 原实现为「系统缩略图 → 内嵌封面 → 固定时间 0ms→30s 截帧」，主路径取首帧导致片头黑屏/构图差。

| 类别 | 改动 |
|---|---|
| 策略顺序 | `ThumbnailLoader.loadVideoThumbnail` 调整为「内嵌封面 → 35% 代表帧 → 系统缩略图兜底」——系统缩略图帧位置不可控，仅作兜底（对齐 Icaros 覆盖 Windows 默认缩略图的体验） |
| 取帧位置 | `loadFirstFrame` 重构为 `loadRepresentativeFrame`：取视频时长（`METADATA_KEY_DURATION`）按 35% 偏移为主位置（对齐 Icaros 默认 offset=35%），参考 FFmpeg 抽帧对比（首帧俯视近景 vs 33s 完整构图）验证 35% 帧更符合"封面感" |
| 黑白检测 | `isBlackFrame` 扩展为 `isExtremeFrame`：采样 100 像素点，全部亮度 <15 判纯黑、全部 >240 判纯白（对齐 Icaros 黑白帧检测，默认替换阈值 8%），不合格按比例序列向两侧扩散（35%→25%→45%→15%→55%→5%→65%→0ms） |
| 异常路径 | 取不到时长时短路 0ms 帧（避免按比例序列对同一帧重复取 8 次） |
| 缓存失效 | `cache_version` 2→3（`MediaLibraryViewModel`），帧位置逻辑变更后自动清除视频缩略图缓存重新生成 |
| 死代码 | 删除无引用常量 `VIDEO_DETAIL_FRAME_US`（v1.16 移除详情帧缓存后的遗留） |
| 有意保留 | 列表/详情页实时显示仍走 Coil `videoFrameMillis(0)` 首帧（中段 seek 慢会加剧滑动掉帧，v1.15 结论）；本地 WebP 缓存由启动+扫描后预生成以 35% 帧填充，滚动时即显示代表帧 |
| 文档 | `GUIDE_ALGORITHM.md` 缩略图三级策略/黑白检测详解；`GUIDE_UI.md` 视频预览取首帧说明修正（原"提取3秒帧"为 v1.15 前过时描述）；`GUIDE_DEBUG.md` Thumb 日志样例 |

**验证**：`:app:assembleDebug` 通过；`gradlew --stop` 已执行。

## 首页搜索新增常规作者维度（v1.17，2026-08-20）

执行 AI：deepseek-v4-flash

用户报告首页搜索只支持视频/角色/出处/标签等维度，不支持作者搜索。调研确认：作者搜索此前只覆盖 COS 作者（`CosAuthorIndex` 仅收录 `cos_` 前缀 authorId），TXT 导入的常规作者完全未参与搜索匹配与补全建议。

| 类别 | 改动 |
|---|---|
| 新增索引 | `MediaGroupHelper.RegularAuthorIndex`：recordKey → 常规作者显示名列表（仅非 `cos_` 前缀作者，与 `CosAuthorIndex` 互斥互补），一个文件关联多个常规作者时列表收集并去重；循环内 O(1) 查找，替代逐条遍历 authorMedia 的 O(N×M) 嵌套扫描 |
| 搜索维度 | `SearchContext` 新增常规作者维度（recordKey → 多作者 `+` 拼接串，与角色维度 joinToString 写法一致），`matchesSingleKeyword` 追加 contains 匹配——搜常规作者名可命中其关联文件 |
| 补全建议 | `buildNameIndex` 新增「作者」类型标签，遍历常规文件提取实际有文件的常规作者（TXT 导入作者仅关联非 COS 文件，故只查常规 media，不显示孤立作者名） |
| 指纹增强 | `computeDataFingerprint` 追加 authors 全量与 authorMedia 前 64 条摘要——作者改名/新增、关联内容变化会触发索引重建（此前指纹不含作者维度，TXT 导入后缓存的补全建议不会更新） |
| 文档 | `GUIDE_UI.md` 搜索匹配维度/补全标签同步；`PROJECT_GUIDE.md` 首页功能描述同步 |

**验证**：新增 `RegularAuthorIndex` 6 个单测 + `SearchContext` 常规作者维度 3 个单测全部通过；`:app:assembleDebug` 通过；`gradlew --stop` 已执行。

## TXT导入刷新回调 Fragment 分离崩溃修复（v1.16，2026-08-19）

执行 AI：deepseek-v4-flash

用户插上真机读取崩溃专项日志，`crash_log.txt` 捕获到一条历史崩溃：`IllegalStateException: Fragment DataManagementFragment not attached to a context`（2026-08-17 05:27:44，闪退后 05:27:52 自动重启）。崩溃点 `DataManagementFragment.kt:216` ← `MediaLibraryViewModel.kt:300`。

| 类别 | 改动 |
|---|---|
| 根因 | 数据管理页「TXT 导入作者」刷新按钮调 `refreshTxtImport`，运行在 `viewModelScope + Dispatchers.IO`（**不随页面销毁取消**）。该次刷新重匹配 2 个 TXT（35+97 个 block，库中数千文件）耗时约 8 秒，匹配完成后回调回到主线程时用户已切走页面，fragment 已 detached，回调内 `requireContext()` 直接抛 `IllegalStateException` 崩溃。对比 `importAuthorsFromTxt` 路径用 `viewLifecycleOwner.lifecycleScope`（随页面销毁取消），只有刷新按钮这条路径会炸 |
| 修复 | 回调内改用可空 `context ?: return@refreshTxtImport` 判空，fragment 已分离时跳过弹 Toast 不再崩溃 |
| 日志佐证 | 同日几十条 ANRWatchdog 记录全部为 D 级降级日志（高负载推进中栈顶变化，非真 ANR），可忽略；最新几次启动日志干净 |

**验证**：`:app:assembleDebug` 通过；`gradlew --stop` 已执行。

## 浏览趋势「全部」全量动态分桶 + 修周对齐周日缺陷（v1.16，2026-08-16）

执行 AI：GLM-5.3

用户报告两个疑点：①首页缩略图滑动加载正常但详情页滑动"只有当前上限"；②数据 tab 浏览趋势的「全部」"怎么算的、显示全堆一起了"。调查结论与处置：

| 类别 | 改动 |
|---|---|
| 疑点①（不改） | 详情页滑动上限为**有意设计**：首页点击进详情只传 `adapter.currentList`（当前已分页加载的列表，非完整排序列表 `tabSortedItems`），GUIDE_UI.md 有记载；详情页窗口分页机制本身健全（前后向 +40 增量）。用户确认性能疑虑解除（其他六个入口一直传完整列表无问题、窗口分页不受影响）后仍拍板**维持现状不放开** |
| 疑点②根因 | 「全部」趋势实际是「最近 12 周按周合计」（`groupDailyByWeek` 固定 12 桶），早于 12 周的数据被静默丢弃；与数字卡「全部」真全量口径不一致（数据集中在近期时一根尖峰+平线，视觉"全堆一起"）；周标签仅 MM/dd 无"周"字样加剧误读 |
| 修复（用户拍板：真·全部+动态分桶） | `StatsFormatHelper.groupDailyByWeek` 重写为按数据实际跨度动态选粒度：≤12 周按周（MM/dd）、12 周~24 月按月（同年 MM月/跨年 yy/MM）、更长按季（yy/Qn），桶从最早数据所在周期铺到当前周期**全量不丢弃**，各桶之和 = 全量总和（与数字卡同口径）；数据不足 2 周期补前导 0 值桶保证折线可画；空数据返回空列表走图表"暂无趋势数据"空态。**纯展示层计算，daily_browse 记录方式（每文件每天一行、无限保留，v1.14 决策）零改动**；分类型趋势/来源趋势的「全部」委托同一函数自动同口径 |
| 附带缺陷修复 | `alignWeekStart` 原 `set(DAY_OF_WEEK, MONDAY)` 受 locale firstDayOfWeek 影响——zh-CN（firstDayOfWeek=周日）下当天为周日会滚到下周一，"本周"桶变未来窗口恒 0；改为与 locale 无关的"距周一已过天数"回退法（周一=0…周日=6） |
| 死代码清理 | 删除 `groupByDay`/`groupByWeek`（基于浏览历史明细的旧分桶实现，v1.15 改 daily_browse 后全项目无调用者）及 `ViewHistoryEntity`/未使用的 `MediaType` import；提取 `calOf` 时分秒清零辅助消除 4 处重复；顺带删 `groupDailyByDay` 中未使用的 `cutoff` 局部变量 |
| 测试 | 新增 `StatsFormatHelperTest`（7 用例）：周日/周一对齐、周/月/季粒度选择与标签、总和守恒、空数据 |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过；`gradlew --stop` 已执行。

## 崩溃专项日志 + 定位过快滑动闪退真凶（v1.16，2026-08-16）

执行 AI：GLM-5.3

用户报告"启动时偶发闪退、过快滑动容易闪退（不扫描时也出现）"，要求内置轻量闪退日志。实施中先读真机现有 app_log.txt，**直接抓到历史崩溃完整堆栈并锁定真凶**。

| 类别 | 改动 |
|---|---|
| 崩溃日志 | `AppLog.recordCrash`：UncaughtExceptionHandler 先写 `files/crash_log.txt`（与 app_log.txt 分离——后者 2MB 滚动截断会把崩溃记录冲掉），带年份时间/线程/完整堆栈，保最近 20 次，仅崩溃时写零日常开销；读法 `adb shell run-as com.qimeng.media cat files/crash_log.txt` |
| 真凶定位 | 历史崩溃堆栈：`SpngDecoder.decodePng` 的 `ByteBuffer.allocateDirect(134MB)`（4800×7000 PNG 原始像素）× LargeImageDecoder 并发 2 → 268MB 顶满 256MB Java 堆 → OutOfMemoryError 闪退在 main 线程抛出（协程 Suppressed 显示 Cancelling 状态，即快速滑动触发取消中仍完成分配）。对应场景：详情页过快滑动浏览超大图；与扫描无关 |
| 修复（用户拍板） | AndroidManifest 开启 `android:largeHeap="true"`：Java 堆上限 256MB→约 512MB（设备相关），2×134MB 大图解码叠加不再顶满；解码并发信号量本已封顶 2，叠加量不随上限增长，无新增 OOM 面。顺带 Coil 内存缓存（35% 堆）随大堆扩容，大图预载命中率提升。**不做项（用户决策）**：解码处 catch OOM 降级（极端 1 亿像素级单张图仍会崩，可接受）；大图字节加权并发（信号量封顶 2 后无必要，避免连续大图串行等待） |
| 复测结论（同日） | largeHeap 后真机复测详情页大图滑动（4200×5760/7680×4320 级）：帧数据证明轻微卡顿（翻页瞬间 30-77ms 帧、74-100% 超时窗）全部对应超大图 >4096 走 SOFTWARE 层的翻页重绘，与 largeHeap 无关（改动前同场景数据一致）；解码仅首次 729ms 后全缓存命中、内存稳定无 GC 卡顿、无 5 秒阻塞无崩溃。**超大图滑动轻卡为"始终原图"设计的固有代价，用户已接受为最终状态**。后续讨论过并放弃的优化路径（用户拍板放弃，勿再重复推销）：浏览态 4096 无损 GPU 副本+放大渐进解码（视觉无损但属设计变更）、纹理平铺全 GPU 渲染（工程量过大）、直接 GPU 硬加速大图（厂商驱动超大 bitmap+matrix 渲染错误，正确性不允许） |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest`（180 tests，0 failures）通过；`gradlew --stop` 已执行。

## 真机性能监控驱动的两项优化：扫描期预生成降速 + 看门狗降级记录带栈（v1.16，2026-08-16）

执行 AI：GLM-5.3

用户真机全面使用 + 后台性能监控（10 秒窗口帧统计/CPU/内存 + AppLog 轮询），发现两类问题并按用户拍板修复（预加载收紧方案用户认为会导致下一张大图等待更久，议定缓做待复测）：

| 类别 | 改动 |
|---|---|
| 扫描期主线程饥饿 | 监控实测：多源扫描 + 12 并发缩略图预生成叠加时 CPU/IO 打满，主线程 5-6 秒饥饿（看门狗降级记录 ×5，非死锁）+ 帧超时。修复：`ThumbnailCache` 新增 `withScanThrottle`（计数式 begin/end），包裹全部六个扫描入口（ScanUseCase 五入口 + MediaStoreObserver 增量刷新），扫描进行中图片预生成并发降为 2（`runPool` 改批间动态批大小，进行中的池在批边界即时生效），扫描结束自动恢复全速；视频并发恒低档无需再降 |
| 看门狗诊断盲区 | 降级记录（WORKING_SLOW）此前仅一行摘要无法定位代码位置；现附主线程栈顶前 8 行（`SLOW_LOG_STACK_LINES`），后续 5-6 秒级"推进中变慢"可直接定位慢在哪 |
| 已知不修（记录） | 超大图（50MB 级/8K 分辨率）详情页滑动卡顿为"始终原图不降采样"设计决策的已知 trade-off：监控实测滑动卡顿窗口 Native Heap 峰值达 2GB 级、CPU 六核满载、帧 95 分位 73-77ms、主线程 5-6 秒饥饿 ×3。预加载进一步收紧与浏览态降采样预览两个方案用户均未拍板，待扫描降速落地后复测再议 |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest`（180 tests，0 failures）通过；`gradlew --stop` 已执行。

## 自动同步防抖 CAS 快照竞态修复（v1.16，2026-08-16）

执行 AI：GLM-5.3

对 8-14 巡检成果做"存活代码逐项亲读复核"时发现：`AutoSyncUseCase.triggerAutoSyncIfNeeded`/`triggerFullSync` 的防抖检查与 `compareAndSet(lastAutoSyncTime.get(), now)` 用了两次独立的 `get()`——两个数据变化触发几乎同时到达时，都可能通过检查并各自用对方刚推进的新值成功 CAS，防抖被绕过导致偶发重复执行 `performSync`（重复写一次备份；原子写+空库防护兜底，无数据风险）。修复：检查与 CAS 改用同一局部快照值做期望值（`AutoSyncUseCase.kt:50-77`），CAS 失败即放弃，并发双写窗口关闭。

**同轮亲读复核结论**：8-14 巡检存活 16 项（分块数值/孤儿对账/监听配对/指纹回退/触摸决策等）全部真实落地且与 CHANGELOG 一致，无其他问题。

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest`（180 tests，0 failures）通过；`gradlew --stop` 已执行。

## 全项目审查修复：45 项 bug/漏洞全量闭环（v1.16，2026-08-16）

执行 AI：GLM-5.3（主 AI：审查派发/方案基线/点验/补丁/文档）+ 4 片 executor（扫描组/备份数据组/UI-A 组/UI-B 组）+ 1 轮 reviewer 对抗审查

用户要求完整审查项目 bug/漏洞并全部修复不留遗留清单。三片只读调研（数据+备份/扫描+UseCase/UI+ViewModel）产出 2 高危 + 14 中危 + 29 低危共 45 项，主 AI 点验 4 个关键项属实后按四组并行修复，reviewer 全新上下文对抗审查通过（含亲自重跑测试验证），全部遗留点由主 AI 闭合。**验证：`assembleDebug` + `testDebugUnitTest` 180 tests / 0 failures（153 旧 + 27 新）+ detekt 通过；`gradlew --stop` 已执行。**

### 高危（数据永久丢失 / 崩溃）

| 项 | 改动 |
|---|---|
| 整源误删防护 | `MediaStoreObserver` SAF 复核改 `runCatching.getOrNull()`：复核异常（权限撤销等）≠ 目录确认为空，失败跳过该源本轮不清理（原 `getOrDefault(空)` 会把 `.nomedia` 目录+SAF 授权失效场景的整源记录连带统计/历史/标签/作者关联永久删除） |
| 排行崩溃 | `AuthorListFragment` `submitList` 后立即 `notifyDataSetChanged`（diff 进行中全量 notify 触发 "Inconsistency detected" IOOBE）改移入 submitList commitCallback；`AuthorListAdapter.submitList` 增加双参重载透传 callback |

### 中危（14 项）

| 类别 | 改动 |
|---|---|
| 扫描·子目录误删 | `SafMediaScanner.safeListFiles` 失败返回 null 与"空目录"区分（原异常返回空数组→上层判 stale 删除），`scanDirectoryFast` 对 null 抛错使整轮跳过 |
| 扫描·COS 整源误删 | `ScanUseCase` COS SAF 回退 `fromTreeUri` 为 null 改 `error()` 抛出走 Error 分支（原返回空 Triple 当"0 文件"整源删除） |
| 扫描·并发互删 | 新增 `core/ScanSourceLocks.kt`（ConcurrentHashMap<String, Mutex> 按 sourceUri 串行化），六个扫描入口（手动/刷新/COS/自动×2/观察者）的"读 existingKeys→upsert→删 stale"临界区统一加锁，消除三方并发互删刚 upsert 的行 |
| 扫描·null 覆盖 | `ScanUtils.hasScanMetadataChanged` 对 SAF 回退实体的 null 元数据（width/height/duration）视为"未知不判变更"；`MediaStoreObserver` upsert 前用库中已有值回填 null 字段（防 Room 整行 upsert 把真实值覆盖为 null，SAF 主路径可见后可自愈） |
| 缩略图·队列 scope | `ThumbnailUseCase` 队列任务统一类内 appScope（SupervisorJob+IO）启动，`pregenerateThumbnails` 移除 scope 参数（8 处调用方同步），修复观察者任务落到已取消 viewModelScope 被静默丢弃 |
| TXT·分片存储 | `AuthorImportUseCase.saveTxtBlocks` 超 CursorWindow 2MB 单行静默丢失改分片存储（`#序号`+`#count`，单片 ≤500KB 字符），`loadTxtBlocks` 兼容旧单行格式，`removeImportedTxtBlocks` 清全部分片；切分/打包/拼合抽为 companion 纯函数并补 7 个测试用例 |
| 备份·原子写退化 | `BackupManager.writeJsonAtomic` 旧备份 rename .bak 失败时不再"先删旧再 rename 新"（连续 rename 失败会丢唯一备份），改放弃本次导出保留旧备份完整 |
| 备份·导入校验 | 全部 12 个导入实体映射主键/外键字段 `isBlank → skip`（recordKey/key/authorId/uriString/name/folderUri），空键不再入库被当"合法键"放行引用数据 |
| 备份·快照一致 | `buildBackupJson` 多表读取整体包 `db.withTransaction` 一致快照（原 tags 两查不一致致恢复丢关联），tags 单次查询复用；SharedPreferences 读留事务外 |
| 偏好·导入钳制 | `AppPrefsManager.importFromJson` 增 sanitizeGridColumns/themeMode 白名单/sanitizeWeight 三纯函数（gridColumns 按各页实际范围钳制、NaN/无穷回退默认），修复 gridColumns=0/负数致 GridLayoutManager 崩溃；补 12 用例测试 |
| UI·过期提交 | `GroupedMediaAdapter` 增 submitSeq 提交序号（4 提交方法递增、Async 完成校验丢弃过期结果），修复不受 renderJob 管控的旧 rows 晚到覆盖新列表 |
| UI·跨线程缓存 | `AllFilesFragment.computeAllGroupsAsync` Default 块只算局部（GroupsCacheSnapshot 快照带回），回主线程再回写 4 个共享缓存字段 |
| UI·预加载风暴 | `HomeFragment` 增 pendingPreload 标志（置位→diff commitCallback 复位），修复快速滑动基于未落地 itemCount 连续 +=20 一次装全库 |
| UI·空态 | `RankListAdapter` 覆写 submitList（单参+双参）：空列表显示"暂无数据"占位（不可点击不高亮），常看作者/标签卡空数据不再只剩标题 |

### 低危（29 项）

| 类别 | 改动 |
|---|---|
| 扫描组 | 观察者刷新期变更通知丢弃改"结束后 count>0 补一轮调度"；`registeredSources` 加 @Volatile + 删除死方法 updateSources；scanDirectory 先 upsertScanSource 再写文件（消除与孤儿清理并发的误删窗口）；5 处 `lastScannedAtMillis` upsert 前按 uriString 确认源仍存在（含 refreshScanSource/refreshCosSource 的 existing==null，不复活已删源）；ThumbnailUseCase 取消分支复位 progress Idle；ThumbnailCache.processBatch rethrow CancellationException；COS SAF 回退根目录直接文件不入库（无作者归属不可见记录）；10 处 catch(Exception) 前置 CancellationException 透传（含 scanDirectory 外层） |
| 备份组 | appPrefs 导入结果计数（原失败仍 count++）；导入 mediaMap 改 `getAllKeysAndFileNames` 轻量投影；dailyBrowse 先钳制再拼 PK（负数不再产生矛盾行/撞 PK）；sizeBytes/modifiedAtMillis/indexedAtMillis 负数钳制；3 处 LIKE 前缀查询加 `ESCAPE '\'` + repository `escapeLikePattern` 转义；rebuildCosIndex 读取移入事务+改 `deleteAllRefsChunked` 全引用级联；loadFromDisk 损坏日志升级 W 级+保留 .corrupt 副本 |
| UI 组 | RankListAdapter areItemsTheSame 只比 id（title 并入 contents 比较）；GroupedMediaAdapter 增 `updateThumbnailSize(columns)` 五页接入（All/Favorite/History/AuthorFiles/AlbumDetail，列数变化同步解码尺寸）；FavoriteFragment 收藏监听过滤移 Default；BrowseHistoryFragment computeRenderJob cancel-first；三页 CHARACTER 模式 selectedSources 死分支删除（与 AllFiles 统一）；StatsDetail renderFavorites/renderAuthorsTags + DataStats renderTopFiles/renderTopAuthors 聚合移 Default+序号防重（"全部"范围不再主线程卡顿）；AlbumFragment lastRenderHash 判定回主线程；MediaFilterSheet 统一 buildTagChip（动态 Chip 长按删除+同名查重）；MediaThumbnailAdapter semaphore 改 tryAcquire 循环+ensureActive 协作取消；MainActivity 两处裸 Handler.postDelayed 改 lifecycleScope.delay（Activity 销毁自动取消）+ tab 首次添加 commitAllowingStateLoss；SearchFragment SoftInputMode 进入前保存原值销毁还原（不再硬编码 ADJUST_RESIZE）+ 两处 postDelayed 回调 requireContext 改 context?；MediaDetailFragment 冷启动空库 5×300ms 有限重试（原空快照即弹栈闪退）+ 同源续播（currentMediaItem 同 uri 且非 IDLE 只 playWhenReady=true 保留进度）；ZoomImageView onTouchEvent 恒 true 死条件清理；BiliPlayerView player==null 停轮询（setPlayer/onAttachedToWindow 恢复点完备） |
| schema | `app/schemas/4.json` 经 `git log --all` 全历史确认从未提交过，MIGRATION_3_4 运行时逻辑在位不受影响，按基线跳过不编造 |

### 新增文件与测试

- 新增 `core/ScanSourceLocks.kt`（按 sourceUri 的共享扫描互斥）
- 新增测试：`ScanUtilsMetadataTest`（8 例：null 不判变更/真实变更判定/边界）、`AppPrefsImportSanitizeTest`（12 例：列数/主题白名单/权重 NaN）、`AuthorImportUseCaseTest` 追加分片分节（7 例：往返还原/旧格式兼容/片丢失回退/转义边界），全项目测试 153→180

### reviewer 对抗审查结论

**通过**。对抗方向（锁死锁/分片四场景/占位语义/续播状态机/事务阻塞/调用方同步/测试真实性/null 回填折衷）逐项核验；5 个非阻塞瑕疵全部由主 AI 闭合（AlbumDetail 初始路径经 setColumns 链已覆盖、RankListAdapter 补双参覆写、变少清理以推演+纯函数覆盖记录、#N key 碰撞加代码注释文档化、文档同步本轮完成）。已知理论边界（fileName 以 `#数字`/`#count` 结尾的分片 key 碰撞，需两个此类文件同时入库才触发）已在 `AuthorImportUseCase` 常量注释记录。

## 常看作者与标签详情页拆分双排行卡（v1.16，2026-08-16）

执行 AI：GLM-5.3

用户要求统计详情页（数据 tab「常看作者与标签」卡片点入）内的「常看排行」单卡拆为两张独立排行卡：「常看作者」Top 15 + 「常看标签」Top 10。主页面外部卡片保持作者/标签混合 Top 3 预览不变。

| 类别 | 改动 |
|---|---|
| 布局 | `fragment_stats_detail.xml` 新增 `tagRankContainer` 标签排行卡（`bg_stat_card` 同卡样式 + `tagListTitleText`/`tagListSubtitleText`/`tagRankRecyclerView`，默认 gone，仅 MODE_AUTHORS 显示） |
| 逻辑 | `StatsDetailFragment` 新增 `tagAdapter`（与作者排行共用点击回调：`tag:` 前缀跳搜索页、作者 id 跳作者文件页）；`observeAuthorsTagsMode` 设两卡标题/副标题（作者 Top 15 / 标签 Top 10），其余三模式显式隐藏 `tagRankContainer`；`renderAuthorsTags` 由 `adapter.submitList(authorItems + tagItems)`（混合 Top 20+20 单列表）改为作者 `take(15)` 与标签 `take(10)` 分别提交两张卡 |

**验证**：`:app:assembleDebug` 通过；`gradlew --stop` 已执行。

## 全项目深度巡检：扫描/数据/UI 跨功能联动修复（v1.16，2026-08-14）

执行 AI：deepseek-v4-flash（主 AI）+ 4 片 researcher + 3 片 executor + 2 轮 reviewer 对抗审查

用户要求深度查询可能出现的 bug 和跨功能联动导致的 bug，确保项目问题基本全部解决、逻辑一致、安全、代码规范。四片并行只读巡检（数据+备份/扫描+UseCase+核心/浏览筛选 UI 系/详情统计我的）后按方案基线修复，reviewer 两轮闭环（首轮发现 ThumbnailUseCase 取消路径缺陷，复审发现 observer 注册遗漏，均返工修复）。

| 类别 | 改动 |
|---|---|
| 高危·扫描误删 | `MediaStoreObserver` 空查询先走 SAF `scanTreeFast` 复核：MediaStore 对 `.nomedia`/Android 16 非标准目录恒查不到，空结果原逻辑整源删除（连带 stats/history/tags/作者关联）；SAF 复核有文件则以 SAF 结果为准，SAF 也为空才清理残留。`QimengApplication` 注入 `appContainer.mediaScanner` |
| 高危·SQLite 上限 | `MediaStoreObserver` 增量 upsert 分块 500；`rebuildAuthorAssociations` authors 分块 300（3 列）、crossRefs 分块 200（4 列）、`deleteCrossRefsByAuthorIds` 分块 500——规避旧设备 999 绑定参数上限（DELETE IN 单列 500 模式对多列 upsert 不成立，按 列数×条数<999 计算） |
| 高危·备份导入 | `BackupManager` 导入 authors/authorMediaRefs 分块 300/200；history 导入后 `pruneToLimit(500)` 对齐历史上限（导出上限 1000 维持不变） |
| 中危·预生成竞态 | `ThumbnailUseCase.isPregenerating` @Volatile 改 AtomicBoolean + compareAndSet 原子入队/启动；finally 处理队列 CAS 保护（失败放回队尾）；**取消路径修复**：CAS 成功后 withContext(Main) 抛 CancellationException 时复位标记并放回 next，杜绝任务丢失 + 标记永久卡 true |
| 中危·COS 刷新 | `refreshCosSource` 同名覆盖/重编码文件用 `hasScanMetadataChanged` 判定更新，对齐 `refreshScanSource`（修复元数据永久陈旧） |
| 中危·启动清理 | `cleanupStaleRefs` 三处全实体加载改轻量投影（新增 ViewStats/ViewHistory/TimelineTag DAO `getAllRecordKeys` + Repository 3 方法） |
| 中危·偏好锁 | `AppPrefsManager.importFromJson` 包 `synchronized(lock)`，与 setter 锁一致防并发丢更新 |
| 中危·自动同步 | `AutoSyncUseCase` 防抖时间戳改 AtomicLong + CAS，多协程并发只触发一次 |
| 中危·详情页统计 | `MediaDetailFragment` viewCount 会话级去重（`viewedRecordKeys`，仿 playedRecordKeys，横滑切回不重复计数）；`startVideoPlayback` 幂等防双击重置；点赞/收藏写入即 `triggerAutoSyncIfNeeded` |
| 中危·首页刷新 | `HomeFragment` fingerprint 追加 likeVersion/statsVersion（点赞监听器与 stats 变化驱动重排），修复详情页点赞/浏览后返回首页排行推荐不刷新 |
| 联动·收藏/关注 | `FavoriteFragment` 监听 KEY_FAVORITES 自动刷新（详情页切换收藏返回即更新）；`AuthorListFragment` 监听 followed_authors 自动刷新 |
| 联动·列表 | 全部/收藏/历史/作者文件页 submit 改 Async 变体（分组移出主线程）；`AllFilesFragment`/`AuthorFilesFragment` 指纹补 tags 维度（不含 stats） |

**回归修复（同日）**：浏览退出后首页/全部页/作者文件页概率性自动刷新——上一轮将浏览统计（stats）纳入渲染指纹，详情页浏览（viewCount/时长写入）后返回即触发重排导致内容跳动。修复：首页 fingerprint 仅保留 `likeVersion`（点赞是用户显式操作，点赞后返回刷新合理），浏览统计不再入指纹；全部页/作者文件页同样移除 stats 维度（按日期/出处分组、不按热度排序，浏览不影响列表内容）。编译 + 153 测试通过。
| 联动·相册 | `AlbumFragment` COS 分组补「其他」桶（无作者关联的 COS 文件不再不可见）；`AlbumDetailFragment` 切模式默认展开药丸；`MediaPillsHelper` COS 作品药丸「其他」排列表末尾 |
| 联动·统计/图表 | `DataStatsFragment.renderOverviewExtra` 序号防重（快速切范围旧协程不覆盖新结果）；`LineChartView` ACTION_DOWN 决策——命中数据点消费、空白放行父容器滚动 |
| 联动·列数持久化 | `PinchZoomHelper` onScaleEnd 回调 + Home/AllFiles/AlbumDetail/BrowseHistory/AuthorFiles/Favorite 六页读写 `AppPrefsManager.updateGridColumns*`（重启不再丢列数，备份 JSON 不再写死默认值） |
| ViewModel 健壮性 | init 两协程 try-catch；`loadMedia` 纯加载方法（`loadAndRecordView` 复用）；autoRefreshAllSources 恢复 GUIDE_SCAN 设计语义（不设 Running、静默不阻塞手动扫描，observer 注册必达） |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest`（153 tests，0 failures）通过；`gradlew --stop` 已执行。

| 文件 | 改动 |
|---|---|
| `scan/MediaStoreObserver.kt` | SAF 空查询复核 + upsert 分块 500 |
| `domain/ThumbnailUseCase.kt` | AtomicBoolean/CAS/取消路径复位放回 |
| `domain/ScanUseCase.kt` | refreshCosSource 元数据更新 + cleanupStaleRefs 轻量投影 |
| `domain/AutoSyncUseCase.kt` | 防抖 AtomicLong |
| `ui/library/MediaLibraryViewModel.kt` | init try-catch + loadMedia + autoRefresh 语义恢复 |
| `QimengApplication.kt` | 注入 SafMediaScanner + onFilesChanged 回调 rematch |
| `data/db/dao/{ViewStats,ViewHistory,TimelineTag}Dao.kt` | 新增 getAllRecordKeys 轻量投影 |
| `data/repository/{LocalMediaRepository,DefaultLocalMediaRepository}.kt` | 3 轻量方法 + rebuildAuthorAssociations 分块 |
| `backup/BackupManager.kt` | 导入分块 + history 裁剪 |
| `data/prefs/AppPrefsManager.kt` | importFromJson 加锁 |
| `ui/detail/MediaDetailFragment.kt` / `ui/main/HomeFragment.kt` / `ui/favorite/FavoriteFragment.kt` / `ui/author/AuthorListFragment.kt` / `ui/album/{AlbumFragment,AlbumDetailFragment}.kt` / `ui/browser/MediaPillsHelper.kt` / `ui/all/AllFilesFragment.kt` / `ui/history/BrowseHistoryFragment.kt` / `ui/author/AuthorFilesFragment.kt` / `ui/stats/DataStatsFragment.kt` / `ui/widget/{LineChartView,PinchZoomHelper}.kt` | UI 联动修复（见上表） |
| `docs/GUIDE_SCAN.md` / `GUIDE_DATA.md` / `GUIDE_BACKUP.md` / `GUIDE_UI.md` / `GUIDE_ALGORITHM.md` / `GUIDE_CODE_MAINTENANCE.md` / `DATA_MIGRATION_SPEC.md` / `PROJECT_GUIDE.md` / `CHANGELOG.md` | 文档同步（含陈旧描述修正：点赞并集语义/历史 500 条/URI 精确匹配/dailyPenalty 递增/updateTxtWorks 移除/itemViewCacheSize=20/点击空白收起功能移除/行数备案） |

## 缩略图缓存收尾：增量预生成 + 死代码清理 + 键逻辑单测化（v1.16，2026-08-14）

执行 AI：deepseek-v4-flash

用户要求不留任何遗留/搁置问题，对上一轮缩略图缓存修复做收尾。

| 类别 | 改动 |
|---|---|
| 增量预生成 | 遗留项：`MediaStoreObserver` 增量刷新不触发缩略图预生成，滚动中新增的视频文件本地 WebP 缓存要等下次启动才填充。新增构造参数 `onFilesChanged: (suspend (List<MediaFileEntity>) -> Unit)?`，增量刷新产生新增/更新文件后回调；`QimengApplication` 注入 `thumbnailUseCase.pregenerateThumbnails(files, applicationScope)`。防抖/单飞保持原样，回调内部自 launch，失败由 ThumbnailUseCase 兜底 |
| 死代码清理 | `ThumbnailCache.clearCache/cacheSize/cacheCount` 全项目无调用者，按删除规则移除（grep 确认后删除） |
| 键逻辑单测化 | 缓存文件名 hash 生成/提取从 `ThumbnailCache` 抽离为纯 JVM `core/ThumbnailCacheKeys.kt`，新增 `ThumbnailCacheKeysTest`（12 个测试：负字节格式化回归、UTF-8 编码、16 位 hex 长度、`_frame` 兼容提取、确定性）。期望值由外部工具按 SHA-256 预计算，防测试自证自 |
| 编码统一 | `hashKey` 显式 `toByteArray(Charsets.UTF_8)`（原无参重载用平台默认字符集；Android 恒为 UTF-8 无行为变化，JVM 单测环境随平台变化，显式指定保证文件名与运行环境无关、测试可复现）；同步将 `ScanUtils.shortHash` 编码显式 UTF-8 化，两套 hash 体系编码策略统一 |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest`（153 tests，0 failures）通过；`gradlew --stop` 已执行。

| 文件 | 改动 |
|---|---|
| `core/ThumbnailCacheKeys.kt` | 新增（纯 JVM 缓存键逻辑，hashKey/hashFromFileName） |
| `core/ThumbnailCache.kt` | hashKey 委托 ThumbnailCacheKeys；prune 用 hashFromFileName；删除 clearCache/cacheSize/cacheCount 死代码 |
| `scan/MediaStoreObserver.kt` | 新增 onFilesChanged 回调，增量新增/更新文件后触发缩略图预生成 |
| `QimengApplication.kt` | 注入 onFilesChanged 回调 |
| `test/ThumbnailCacheKeysTest.kt` | 新增 9 个单元测试 |
| `docs/GUIDE_ALGORITHM.md` / `docs/GUIDE_SCAN.md` / `docs/CHANGELOG.md` | 文档同步 |

## 修复"每次打开软件重新缓存一部分缩略图"（v1.16，2026-08-14）

执行 AI：deepseek-v4-flash

用户反馈每次打开软件都会重新缓存一部分缩略图。

| 类别 | 改动 |
|---|---|
| 根因 | `ThumbnailCache.enforceCacheLimit`（v1.12 加的 5000 文件数上限）在每次预生成后按 `lastModified` 淘汰最旧缓存砍回 5000。用户库文件数 > 5000 时，只要有一个未缓存文件触发预生成（新增/缓存损坏/cache_version 升级清视频缓存），就会误删约 (总数-5000) 个**有效**缓存 → 下次启动这批复读解码生成 → 又超限又删 → 稳态抖动，每次启动重缓存并重删固定比例缩略图 |
| 修复 | 移除按数量淘汰机制（`enforceCacheLimit` + `MAX_CACHE_FILES` + 预生成末尾调用）；新增 `ThumbnailCache.pruneOrphanThumbnails(validRecordKeys)` 孤儿对账——只删除 recordKey 不在库中、且写入超 24h 的缓存文件（24h 门槛避开并发预生成/adapter 刚写入的新缓存被旧 DB 快照误删），有效缓存永不因数量上限被删；`MediaLibraryViewModel` init 预生成前以全库 recordKey 执行对账。目录规模与库文件数成正比，不残留孤儿由「删除文件时同步清理（`deleteMediaAndRefs` → `MediaCacheCleaner`）+ 启动对账」保证 |
| 深挖 | 顺带清理 `ThumbnailCache.readFrame/writeFrame/frameFile`（`_frame.webp` 视频详情帧）死代码——全项目无调用者（详情页视频预览一直走 Coil `VideoFrameDecoder`），并修正 `GUIDE_UI.md` 中描述该链路的过时文档 |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过；`gradlew --stop` 已执行。

| 文件 | 改动 |
|---|---|
| `core/ThumbnailCache.kt` | 移除 enforceCacheLimit/MAX_CACHE_FILES/_frame 死代码；新增 pruneOrphanThumbnails |
| `ui/library/MediaLibraryViewModel.kt` | init 预生成前执行孤儿缓存对账 |
| `docs/GUIDE_ALGORITHM.md` / `docs/GUIDE_UI.md` / `docs/GUIDE_SCAN.md` / `docs/CHANGELOG.md` | 文档同步 |

## 移除 Skill 体系，只保留项目约束文档（2026-08-12）

执行 AI：deepseek-v4-flash

用户确认外部/自写 Skill 从未被实际触发（无强制加载机制，靠 AI 自觉读取），决定删除 Skill 体系，只保留每次必读的项目约束文档（AGENTS / AI_README_FIRST / GUIDE_*.md）。

| 类别 | 改动 |
|---|---|
| 背景 | `app/ai-skills/external/`（5 个外部包，Compose 路线与本项目原生 View 冲突）与 `docs/AI_WRITTEN_SKILLS/`（9 个自写技能）仅靠 `GUIDE_USAGE_MATRIX.md` 提示读取，无触发保障 |
| 决策 | 删除两个 Skill 目录；清理 `AI_README_FIRST.md` / `GUIDE_USAGE_MATRIX.md` / `GUIDE_README.md` / `PROJECT_GUIDE.md` / `DEVELOPMENT_PLAN.md` / `CLAUDE.md` / `AGENTS.md` / `copilot-instructions.md` 中所有 Skill 读取顺序、引用清单、修改规则、冲突优先级条目；自写技能中的薄弱约束已归并进 `GUIDE_THEME.md`（夜间可读性/明暗差异/设计克制）。原 `KOTLIN_COROUTINES_SKILL.md` 的"搜索输入使用 debounce"约束未归并——搜索页已用 nameIndex 预计算 + O(m) 补全匹配机制消除重查询压力，防抖无实际需求，直接废弃（`GUIDE_SCAN.md` 中的同源副本一并删除） |
| 改动 | 删除 `app/ai-skills/`、`docs/AI_WRITTEN_SKILLS/`；矩阵清理 32 处外部 + 12 处自写技能引用；`AI_README_FIRST.md` 总读取顺序 15 步精简为 9 步、新增模块流程 7 件事改为 6 件事 |

**验证**：全项目 grep 无 `ai-skills` / `AI_WRITTEN_SKILLS` 残留引用（仅本 CHANGELOG 记录）；两个目录已从 git 删除。

| 文件 | 改动 |
|---|---|
| `app/ai-skills/`、`docs/AI_WRITTEN_SKILLS/` | 整目录删除 |
| `AI_README_FIRST.md` / `AGENTS.md` / `CLAUDE.md` | 清 Skill 规则与读取顺序 |
| `docs/GUIDE_USAGE_MATRIX.md` | 清 43 处引用行与外部 Skill 规则 |
| `docs/GUIDE_README.md` / `docs/PROJECT_GUIDE.md` / `docs/DEVELOPMENT_PLAN.md` | 清目录树与阶段描述 |
| `.github/copilot-instructions.md` | 删外部 Skill 冲突说明 |
| `docs/GUIDE_THEME.md` | 归并自写技能薄弱约束（夜间可读性/明暗差异/设计克制） |
| `docs/CHANGELOG.md` | 本文档记录 |

## 详细信息弹窗移除"完成"按钮（v1.15，2026-08-12）

执行 AI：deepseek-v4-flash

用户询问"为什么一定要显示这个完成"，确认冗余后移除。

| 类别 | 改动 |
|---|---|
| 背景 | "完成"按钮是 v1.0 初始提交（e5ee7d1）就有的手动关闭按钮，位于所有 BottomSheet 弹窗底部 |
| 决策 | BottomSheet 已有两套标准关闭方式（下滑手势 / 点外部空白），"详细信息"弹窗的显式"完成"按钮冗余，移除 |
| 改动 | `showInfoSheet` 两条分支（解码/不解码）均不再添加 `sheetDone`；`sheetDone` 方法**保留**——标签管理弹窗、快速转跳弹窗仍在使用（经 grep 确认，删定义会破坏这两个弹窗），方法注释标注适用范围 |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过；`gradlew --stop` 已执行。

| 文件 | 改动 |
|---|---|
| `ui/detail/MediaDetailFragment.kt` | showInfoSheet 移除完成按钮 |
| `docs/GUIDE_UI.md` / `docs/CHANGELOG.md` | 文档同步 |

## 详情页信息弹窗"完成"按钮位置修复（v1.15，2026-08-12）

执行 AI：deepseek-v4-flash

用户反馈详情页信息弹窗"完成"按钮出现在尺寸和目录之间。

| 类别 | 改动 |
|---|---|
| 根因 | v1.15"先 show 再补解码"解码分支在初始视图就把"完成"按钮加进容器（排在"解码中…"占位行之后），解码完成后再追加 时长/目录/路径 被挤到按钮下方 → "完成"出现在尺寸和目录之间 |
| 修复 | 解码分支初始**不添加**"完成"按钮，解码完成补齐全部信息行后再在末尾添加，与正常分支顺序一致（信息行在前、完成按钮收尾）；解码期间用户仍可下滑关闭弹窗，不堵入口 |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过；`gradlew --stop` 已执行。

| 文件 | 改动 |
|---|---|
| `ui/detail/MediaDetailFragment.kt` | 解码分支完成按钮移到解码补齐后 |
| `docs/GUIDE_UI.md` / `docs/CHANGELOG.md` | 文档同步 |

## 详情页信息弹窗"解码中/解码完"双行修复（v1.15，2026-08-11）

执行 AI：deepseek-v4-flash

用户反馈详情页信息弹窗同时显示"解码中…"与"解码完成"两行"尺寸"。

| 类别 | 改动 |
|---|---|
| 根因 | v1.15"先 show 再补解码"实现为解码完成后**追加**一行"尺寸"，占位的"解码中…"行未被替换 → 弹窗出现两行"尺寸"（一行"解码中…"一行实际值），流程显示异常 |
| 修复 | `addInfoRow` 改为返回 value TextView 引用；解码分支占位行持有该引用，解码完成后**原地更新文本**（`sizeRowValue.text = formatDimensions(updated)`），不再追加重复行；`dialog.setContentView(container)` 重设视图刷新显示 |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过；`gradlew --stop` 已执行。

| 文件 | 改动 |
|---|---|
| `ui/detail/MediaDetailFragment.kt` | addInfoRow 返回 value 引用 + 占位行原地更新 |
| `docs/GUIDE_UI.md` / `docs/CHANGELOG.md` | 文档同步 |

## 首页滑动卡顿修复（v1.15，2026-08-11）

执行 AI：deepseek-v4-flash

用户反馈"首页下滑浏览会卡一下，疑似加载下一个页面"，研究定位根因后三处修复。

| 类别 | 改动 |
|---|---|
| 根因 | 首页触底瞬间才分页 +20 条 → 新条目批量 bind 时：①视频无缓存被 Coil VideoFrameDecoder 与后台 decodeJob（MediaMetadataRetriever 扫 8 点位）**双重解码同一首帧**，且 getFrameAtTime 阻塞式取消失效、滑走也停不下来；②启动期全库预生成（图片并发最高 16）+ 滚动按需解码 + Coil 三方抢磁盘 IO → 触底那帧明显掉帧 |
| A 分页提前 | `HomeFragment.onScrolled` 从"触底才加载"（`!canScrollVertically(1)`）改为**距已加载列表底部 ≤6 项提前加载**（`lastVisible >= itemCount - 1 - PRELOAD_THRESHOLD_ITEMS`），加 `displayCount >= items.size` 守卫防重复提交；新常量 `PRELOAD_THRESHOLD_ITEMS=6`——加载发生在滑动中途被动画掩盖，不再是触底瞬间的同步解码风暴 |
| B 去视频双解 | `MediaThumbnailAdapter.bind`：视频无缓存时 Coil 解首帧后直接 `return`，不再启动后台 decodeJob（同一首帧不再双解）；图片 decodeJob 保留；decodeJob 内 `isVideo` 死分支清理。视频本地 WebP 由启动预生成填充（下次 bind 走缓存文件分支），滚动期间 Coil 首帧走 memoryCacheKey 内存缓存。影响面：GroupedMediaAdapter 共享 Holder，全部/收藏/历史/搜索列表页一并生效 |
| C 滚动暂停预生成 | `ThumbnailCache` 加 `@Volatile scrollPaused` + `setScrollPaused()`，`processBatch` 解码前 `while(scrollPaused) delay(100)` 让出 IO；`HomeFragment.onScrollStateChanged` 非 IDLE 暂停、IDLE 恢复。滚动期间预生成不再与按需解码争磁盘 |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过；`gradlew --stop` 已执行。

| 文件 | 改动 |
|---|---|
| `ui/main/HomeFragment.kt` | 分页提前触发 + 滚动暂停预生成监听 |
| `ui/adapter/MediaThumbnailAdapter.kt` | 视频去双重解码 |
| `core/ThumbnailCache.kt` | scrollPaused 滚动暂停 |
| `docs/GUIDE_UI.md` / `docs/GUIDE_ALGORITHM.md` / `docs/GUIDE_CODE_MAINTENANCE.md` / `docs/CHANGELOG.md` | 文档同步 |

## 浏览趋势主页面数据源修复（v1.15，2026-08-11）

执行 AI：deepseek-v4-flash

用户反馈"浏览趋势内部正确外部不正确、数值对不上"，真机（adb + 导出数据库分析）定位根因并修复。

| 类别 | 改动 |
|---|---|
| 根因 | 主页面浏览趋势卡（外部）用 `viewModel.history`（浏览历史明细）分桶，history 同文件只保留最新一条且上限 500 → 多次浏览只算 1 次，数值必然偏小；详情页分类型趋势与数字卡均用 `daily_browse` 按天聚合（无限保留、每文件每天一行）→ 内外口径不一致。真机实测：08-11 聚合 view=11 而 history 仅 3 条 |
| 修复 | `DataStatsFragment.renderTrend()` 改用 `StatsFormatHelper.groupDailyByDay/groupDailyByWeek`（daily_browse 聚合，与详情页同源同口径）；删除 history collect 分支与 `cachedHistory` 死代码（`viewModel.history` 流仍被 5 个列表页筛选使用，保留） |
| 附带 | 布局 `fragment_data_stats.xml` 第二行指标文案"平均浏览深度"→"平均浏览次数"（v1.14 改名遗漏，代码早已用新名） |

**验证**：真机安装后截图视觉确认——数字卡 浏览22/播放6/时长5分 与 daily_browse 近 7 天实测（22/6/338s）完全一致；趋势图 08/10 峰值、08/11 回落形态与数据吻合；`assembleDebug` 通过。

| 文件 | 改动 |
|---|---|
| `ui/stats/DataStatsFragment.kt` | renderTrend 换 daily_browse 聚合 + 删 history 死代码 |
| `res/layout/fragment_data_stats.xml` | 平均浏览次数文案修正 |
| `docs/GUIDE_UI.md` | 数据源描述同步 |

## 全项目巡检修复（v1.15，2026-08-10）

执行 AI：deepseek-v4-flash

用户要求"看看项目有 bug 吗，全部修复"。分 6 组（扫描/数据、备份、统计、UI 常规、详情/播放/解码、构建/杂项）并行修复，经 reviewer 审查发现并修复 1 个 P0 回归（DATA LIKE 转义把递归通配符一并转义，自动刷新会清空常规扫描源数据），SQLite 验证后闭环。

| 类别 | 改动 |
|---|---|
| 数据丢失高危 | shortHash 负 Byte `%02x` 格式化恒等于 `"ffffffff"` 致 recordKey 主键互相覆盖；COS workName 判定 off-by-one（`> 2` → `>= 2`，结构2 作品被合并为作者名）；删除扫描源 legacy 回退用文件 URI startsWith tree URI 前缀恒不命中致整体误删（改为 null 保守保留，宁残留不可误删）；MediaStoreObserver 空查询跳过残留清理（目录清空后 DB 行残留） |
| 扫描 | 增量刷新只 upsert 新 key → 同名覆盖/重编码元数据永久陈旧（改"新 key 或元数据有变"）；防抖 cancel 无单飞保护 → `isRefreshing` AtomicBoolean；DATA LIKE 通配符转义（只转义前缀保留 `/%`，P0 修复）；cos_works.fileCount 语义（作者总文件数→作品文件数）；COS 刷新清理已消失/改名作品行（`CosWorkDao.deleteByIds`）；SafMediaScanner 捕获全部异常跳过目录 |
| 备份 | writeJsonAtomic rename 失败丢唯一备份（旧备份先改 .bak 暂存）；写侧补 64MB 上限与读侧对称；点赞恢复改并集合并（与收藏一致，不再静默删本地独有点赞）；禁用专辑规则进备份；schemaVersion 导入校验；authorId 截断统一（主键无长度约束，去掉 take(64)）；标签重复恢复 count 虚增（按 insert 返回值过滤 IGNORE） |
| 统计 | 常看作者功能完全失效修复（`cachedAuthorData` 从未赋值，补 viewModel.authors 收集）；标签排行点击跳搜索页携带标签（showSearchFragment 增 initialQuery 参数透传）；来源趋势 COS 判定改 combine 等待两流齐备（消除首帧错画+闪烁）；主线程 O(n²) 聚合移 Dispatchers.Default（renderTrendSeq 序号防护）；卡片/趋势图窗口口径统一（naturalDayCutoff 今天 0 点对齐）；平均浏览分母统一（含 totalBrowseSeconds>0）；进度条 0 值显示 1% 修复；删死代码 cachedTagData |
| UI 常规 | 作者文件页主线程全量筛选移协程（render 编排 + Default 计算）；作者页下拉刷新收集器累积（observeJob cancel，对齐 AllFilesFragment）；DataBackupFragment rebuildContent 收集器累积（视图字段化 + Job cancel，开关状态闪跳修复）；作者列表主线程重算移协程（消除 sortAndFilter 重复计算）；收藏页 COS 角色药丸重复分组（复用 charGroups）；GroupedMediaAdapter 异步提交；GIF 分支 bind 开头统一 cancel decodeJob；相册详情 preFingerprint 纳入 selectedChars；首页每日去重计数移到 submitList 后（滚动新露出项也计入） |
| 详情/播放/解码 | LargeImageDecoder bitmap 双引用崩溃（写 Coil 缓存前 copy 副本，根治"width and height must be > 0"）；showInfoSheet 防重弹窗 + viewLifecycleOwner + 先 show 再补解码；播放计数改会话级 `playedRecordKeys` 集合（滑走滑回不重复计）；BiliPlayerView 手势拖进度与轮询竞争（轮询跳过 isGestureDragging）、setPlayer 恢复倍速、attach/detach listener 对称、ENDED 态 startPlayback 先 seekTo(0)、duration<=0 忽略手势 seek；ZoomImageView onPreDraw 监听器累积（单例先 remove 再 add）；ThumbnailLoader lastFrame 泄漏回收；ThumbnailCache hashKey 负字节（同 shortHash 根因）；GpuInfo await 2s 超时兜底；AnrWatchdog tickTimestamp @Volatile；TimelineTagHelper 弹窗打开时暂停播放（标记时间点与实际画面一致） |
| 构建/杂项 | Release 签名接线（app/keystore.properties 读取，.gitignore 排除密码不入库，缺失产出未签名 APK）；AppPrefsManager 读改写加锁；ThumbnailUseCase isPregenerating @Volatile；MIGRATION_6_7 补 isCosFile 索引（老库升级全表扫修复） |

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过；P0 LIKE 转义修复经 SQLite 实测（目录含 `_` 时只匹配目标目录、不误匹配相邻目录，整体转义复现 0 行）；`gradlew --stop` 已执行。

| 文件 | 改动 |
|---|---|
| `scan/ScanUtils.kt` `scan/MediaStoreScanner.kt` `scan/MediaStoreObserver.kt` `scan/SafMediaScanner.kt` `domain/ScanUseCase.kt` `data/repository/DefaultLocalMediaRepository.kt` `data/repository/LocalMediaRepository.kt` `data/db/dao/CosWorkDao.kt` `core/RecordKeyFactory.kt` | 扫描/数据层修复 |
| `backup/BackupManager.kt` | 备份修复 |
| `ui/stats/DataStatsFragment.kt` `ui/stats/StatsDetailFragment.kt` `ui/stats/StatsFormatHelper.kt` `ui/stats/RankListAdapter.kt` `MainActivity.kt` `ui/search/SearchFragment.kt` | 统计修复 |
| `ui/author/AuthorFilesFragment.kt` `ui/author/AuthorListFragment.kt` `ui/profile/DataBackupFragment.kt` `ui/favorite/FavoriteFragment.kt` `ui/adapter/GroupedMediaAdapter.kt` `ui/adapter/MediaThumbnailAdapter.kt` `ui/album/AlbumDetailFragment.kt` `ui/main/HomeFragment.kt` `ui/browser/MediaFilterSheet.kt` | UI 常规修复 |
| `ui/detail/MediaDetailFragment.kt` `ui/detail/BiliPlayerView.kt` `ui/detail/ZoomImageView.kt` `ui/detail/TimelineTagHelper.kt` `core/LargeImageDecoder.kt` `core/ThumbnailLoader.kt` `core/ThumbnailCache.kt` `core/GpuInfo.kt` `core/AnrWatchdog.kt` | 详情/播放/解码修复 |
| `app/build.gradle.kts` `data/prefs/AppPrefsManager.kt` `domain/ThumbnailUseCase.kt` `data/db/AppDatabase.kt` | 构建/杂项修复 |
| `docs/GUIDE_SCAN.md` `docs/GUIDE_DATA.md` `docs/GUIDE_BACKUP.md` `docs/GUIDE_UI.md` `docs/GUIDE_README.md` `docs/PROJECT_GUIDE.md` `docs/CHANGELOG.md` | 文档同步 |

## 数据统计页重构 + 播放计数修复 + 按天浏览聚合（v1.14，2026-08-09）

执行 AI：deepseek-v4-flash

用户要求数据 tab 从"盘点库存"改为"指导喜好"（回答"我常看什么"），经多轮讨论定稿：上半部数字卡微调、分布统计简化、删除库存排行、新增常看排行、时间范围全局联动；同时修复 playCount 从无写入点恒为 0 的问题；引入按天浏览聚合表作为统计真相来源。

| 类别 | 改动 |
|---|---|
| 数据层 | 新增 `daily_browse` 按天浏览聚合表（每文件每天一行：次数/播放/时长，**无限保留**，统计页趋势/窗口排行/沉浸时长的唯一真相来源）；数据库 v5→v6 迁移建表；`recordView`/`updateBrowseSeconds`/新增 `recordPlay` 均同步写聚合（原子 +1/追加，miss 时 upsert 建行）；级联删除纳入 `deleteMediaAndRefs`/`deleteCosScanSource` |
| playCount 修复 | 全项目原无 `recordPlay` 写入点（图片/视频统一只记 viewCount，playCount 仅备份导入恢复）→ 新增 Repository `recordPlay` + DAO 原子 +1 + ViewModel `recordPlay` + `MediaDetailFragment.startVideoPlayback` 上报；同一次详情打开只计一次（预览→播放→退预览→再播放不重复，字段 `playRecordedForCurrentMedia`） |
| 主页面 | 时间胶囊**全局联动**（数字卡浏览/播放/时长、趋势、常看排行全随 7天/30天/全部 过滤，总文件数/总占用为库存静态值）；「平均浏览深度」改名「平均浏览次数」；删除库存环形图（分布卡改纯文字小入口）+ 作者/标签文件数 Top5 卡；新增「常看文件」卡（文件名+次数 Top3 文本列表）与「常看作者与标签」卡（窗口浏览聚合混合 Top3）；浏览趋势卡可点击进分类型趋势 |
| 详情页 | StatsDetailFragment 重构为四种模式：`MODE_TYPE_TREND`（分类型趋势，最终为**两张独立对比卡**：类型卡（图片/视频/动图）+ 来源卡（常规/COS），各卡胶囊可多选、不同颜色折线叠加同一时间轴 + 图例 + 点击气泡含系列名）、`MODE_FAVORITES`（常看文件，按热度/时长排序切换）、`MODE_AUTHORS`（常看作者+标签浏览排行，**标签条目可点击跳搜索页**，修复原 id=null 不可跳转）、`MODE_DISTRIBUTION`（原分布对比 + 窗口浏览维度）；删除旧 `MODE_FILES`/`MODE_TAGS` 模式；标题带时间范围后缀 |
| 图表组件 | `LineChartView` 新增多系列对比（`setMultiSeries`：多色折线 + 顶部图例 + 气泡含系列名）；删除无使用者的 `BarChartView`/`PieChartView`/`BrowseStatsChartView`（分布环形图与柱状排行卡移除后零引用）；`StatsFormatHelper` 新增按天聚合计算（groupDailyByDay/Week/ByType/aggregateDailyByType/BySource/cutoffForRange） |
| 备份 | `qimeng_backup.json` 16→17 段，新增 `dailyBrowse` 段（备份聚合而非明细；恢复后窗口统计与备份时一致） |

**验证**：`:app:assembleDebug` 构建通过；`gradlew --stop` 已执行。

| 文件 | 改动 |
|---|---|
| `data/db/entity/DailyBrowseEntity.kt` | 新增聚合实体 |
| `data/db/dao/DailyBrowseDao.kt` | 新增聚合 DAO（observeSince/getAll/incrementViewCount/incrementPlayCount/addBrowseSeconds/upsert/deleteByRecordKeys/clearAll） |
| `data/db/AppDatabase.kt` | 注册实体+DAO，v5→v6 迁移建表 |
| `data/repository/LocalMediaRepository.kt` + `DefaultLocalMediaRepository.kt` | 新增 `recordPlay`/`observeDailyBrowse`；`recordView`/`updateBrowseSeconds` 同步写聚合；级联删除聚合 |
| `ui/library/MediaLibraryViewModel.kt` | 新增 `dailyBrowse` Flow + `recordPlay` |
| `ui/detail/MediaDetailFragment.kt` | `startVideoPlayback` 上报播放（同次详情只计一次） |
| `ui/stats/DataStatsFragment.kt` | 全局联动 + 常看文件/常看作者标签卡 + 分布小入口 |
| `ui/stats/StatsDetailFragment.kt` | 四种新模式，删除旧 Modes，标签可跳转 |
| `ui/stats/StatsFormatHelper.kt` | 按天聚合计算扩展 |
| `backup/BackupManager.kt` | dailyBrowse 段导出/导入 |
| `ui/widget/BarChartView.kt`/`PieChartView.kt`/`BrowseStatsChartView.kt` | 删除（无使用者） |
| `res/layout/fragment_data_stats.xml` | 新卡片结构 |

## 备份 v2.0 单文件全量重构（2026-08-09）

执行 AI：deepseek-v4-flash

用户反馈备份太复杂（三套产物重复）、个人报告与数据 tab 重叠，要求简化：备份改为**单一 `qimeng_backup.json` 全量 JSON**（16 个数据段），删除 App 内个人偏好报告器与 personal_prefs 缝合格式，报告交给 AI 读备份 JSON 现场生成。

| 类别 | 改动 |
|---|---|
| 备份格式 | 旧三套产物（app数据 10 分文件 + personal_prefs.json + 9 章节报告 txt + 格式说明/ 示例目录）→ 单文件 `qimeng_backup.json`（format/schemaVersion/appIdentifier/exportedAtMillis + data 16 段） |
| 核心缺口补齐 | `media_files` 全 16 字段元数据进备份（旧版从未导出）→ 恢复后浏览历史/统计/标签**立即可用**，不依赖重扫，根治旧版重名文件 pathHash key 漂移被 `cleanupStaleRefs` 误删的问题；新增导出 `cos_works`（含 sourceUri）、`AuthorMediaCrossRef.isMatched`、`MediaFileEntity.sourceUri`、AppPrefs 全量（theme_mode/gridColumns/autoSync/customAlbumSources/recommendationPrefs） |
| 导入合并策略 | 本地 media_files 为空（重装未扫描）→ 导入备份 media_files 全量；本地非空（已扫描）→ 跳过 media_files 保留新 URI，引用数据按稳定 key 合并；跨引用安全（recordKey 不在 media_files 则跳过） |
| BackupManager | 重写：泛型 exportArray/importArray + 16 段实体↔JSON 映射，原子写（tmp→round-trip 校验→删旧→rename）+ 64MB 读取上限；保留安全加固（数值 coerceAtLeast(0)、likes 5000 上限）；删除 exportPersonalPrefs/buildReportText/全部 append*Section/ReportSectionOptions/exportFormatSpec（约 500 行） |
| BackupFileNames | 精简为 `QIMENG_BACKUP` 单常量 |
| AutoSyncUseCase | 4 同步入口合并为 2（triggerAutoSyncIfNeeded 30s 防抖 / triggerFullSync 60s 防抖 / triggerManualSync 无视防抖），统一写单文件；删除重复 3 次的空库防护代码为 1 处 performSync；`triggerAutoSyncForDetailExit` 删除，详情页改调 triggerAutoSyncIfNeeded |
| UI | DataBackupFragment：删「自定义导出」区块与 `showCustomReportExportDialog`；目录结构文案改单文件；saveBackupLocation 只建「绮梦影库/」根目录并立即写入全量备份；导入/恢复弹窗补"媒体文件数"维度 |
| assets | 删 FORMAT_SPEC.md、personal_prefs_example.json、app_data_examples/（10 个示例）；新增虚构 `qimeng_backup_example.json` |
| 死代码清理 | 删除 `LocalMediaRepository.recordHistory` / `DefaultLocalMediaRepository.recordHistory`（无调用方） |

**验证**：`:app:assembleDebug` 构建通过；`:app:testDebugUnitTest` 通过；`gradlew --stop` 已执行。

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/qimeng/media/backup/BackupManager.kt` | 重写为单文件全量导出/导入（~700 行，原 1567 行） |
| `app/src/main/java/com/qimeng/media/backup/BackupFileNames.kt` | 精简为单常量 |
| `app/src/main/java/com/qimeng/media/domain/AutoSyncUseCase.kt` | 合并同步入口，统一写单文件 |
| `app/src/main/java/com/qimeng/media/ui/profile/DataBackupFragment.kt` | 删自定义导出/改文案/改目录创建 |
| `app/src/main/java/com/qimeng/media/ui/detail/MediaDetailFragment.kt` | triggerAutoSyncForDetailExit → triggerAutoSyncIfNeeded |
| `app/src/main/java/com/qimeng/media/data/repository/LocalMediaRepository.kt` + `DefaultLocalMediaRepository.kt` | 删 recordHistory 死代码 |
| `app/src/main/assets/qimeng_backup_example.json` | 新增虚构示例 |
| `docs/GUIDE_BACKUP.md` / `GUIDE_DATA.md` / `DATA_MIGRATION_SPEC.md` / `PROJECT_GUIDE.md` / `GUIDE_UI.md` / `GUIDE_README.md` / `CHANGELOG.md` | 重写/更新为新格式 |

> 最后更新：2026-08-09

## 作者管理加搜索过滤（2026-08-09）

执行 AI：deepseek-v4-flash

作者管理页新增按作者名搜索：标题栏下方加整行常驻搜索框，输入即过滤作者列表。

| 类别 | 改动 |
|---|---|
| 布局 | `fragment_author_list.xml` 标题栏与胶囊行之间加 `authorSearchInput`（`bg_capsule_soft` 胶囊背景，样式对齐搜索页输入框，`?attr/qmColor*` 主题色） |
| 过滤逻辑 | `AuthorListFragment` 加 `searchQuery` 状态，`sortAndFilterAuthors()` 最前一层按 `displayName.contains(query, ignoreCase = true)` 模糊过滤（忽略大小写，多别名合并显示名任一命中即匹配）；与胶囊筛选（全部/常规/COS）、排序逐层叠加（搜索词 → 分类 → 排序） |
| 交互 | 输入即刷新（TextWatcher）；键盘搜索键收起输入法并取消焦点；搜索无结果时空状态显示「没有找到匹配「关键词」的作者」，有结果时恢复默认空状态文本 |
| 实现方式 | 纯内存过滤（作者量级数百，全量 Flow 已在内存），不改 DAO/Repository/ViewModel 链路 |

> 最后更新：2026-08-09

## 作者 TXT 数字开头文件名误判编号行修复 + findMatch 日志降频（2026-08-09）

执行 AI：deepseek-v4-flash

真机全流程监控（浏览/扫描/TXT 导入/详情页/搜索/备份全走一遍）后系统读日志发现两个问题并修复：

| 类别 | 问题 | 修复方式 |
|---|---|---|
| 功能错误（TXT 解析） | 作品文件名以数字开头（如 `2077  帕南.png`、`2077  朱迪+帕南.png`、`2077  帕南 2.jpg`）命中编号行正则 `数字 内容`，被拆成伪作者块：作者名解析成 `帕南.png`，该作品从真实作者（Takerskiy/Keu3D/Night Cry）的 works 中丢失，作者关联缺失 | `AuthorImportUseCase.parseAuthorBlocks` 编号行命中后校验：解析出的"作者名"全部带媒体扩展名（`looksLikeFileName`）则按作品行处理（作品区归入当前块 works，块外忽略）；`parseAuthorBlocks` 与 `AuthorBlock` 提升为 companion object 纯函数便于测试 |
| 日志刷屏 | `findMatchingMediaLight` 循环内逐条打 `findMatch` 日志，一次 TXT 重建刷 1252 条（占全量日志 51%），违反「循环内不加高频日志」原则 | 参照 `SourceMatcher.charMiss` 采样模式，每 50 次调用记 1 条 `findMatch(累计N)`（AtomicInteger 计数器） |
| 文档缺失 | `Thumb`/`ThumbCache`/`AuthorImport` 标签未收录进 GUIDE_DEBUG 标签约定表 | GUIDE_DEBUG 标签表补 3 行 + 采样日志原则补充 findMatch 采样示例 |

**测试**：新增 `AuthorImportUseCaseTest`（9 个用例，`parseAuthorBlocks` 纯逻辑：编号行/出处/作品区解析、括号别名去除、多别名合并、数字开头文件名误判修复、格式 C 与空文本边界），`:app:testDebugUnitTest` 通过；`assembleDebug` 构建通过。

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/qimeng/media/domain/AuthorImportUseCase.kt` | parseAuthorBlocks 数字开头文件名判定 + 提为 companion 纯函数；findMatch 日志采样 |
| `app/src/test/java/com/qimeng/media/AuthorImportUseCaseTest.kt` | 新增 9 个单元测试 |
| `docs/GUIDE_AUTHOR.md` | 格式规范新增「数字开头的作品文件名不误判编号行」条目 |
| `docs/GUIDE_DEBUG.md` | 标签约定表补 AuthorImport/Thumb/ThumbCache |
| `docs/GUIDE_CODE_MAINTENANCE.md` | 测试类表补 AuthorImportUseCaseTest，覆盖率为 7 类 140+ 测试 |

> 最后更新：2026-08-09

## 启动自动刷新补齐 TXT 重匹配与备份同步（2026-08-08）

执行 AI：deepseek-v4-flash

用户询问"打开 App 是否自带一次同步"，确认缺口后修复：启动自动刷新此前只刷媒体库，不重新关联 TXT 作者、不触发备份写盘。

| 文件 | 改动 |
|---|---|
| `ScanUseCase.doAutoRefreshAllSources` | 启动自动刷新所有源完成后追加 `rematchAllTxtImports(this)`，新文件自动关联 TXT 作者（与 scanDirectory/refreshScanSource 行为对齐） |
| `MediaLibraryViewModel.autoRefreshAllSources` | 刷新完成后追加 `autoSyncUseCase.triggerAutoSyncIfNeeded()`（只写 app数据/） |

**防抖确认**：不会因 App 内频繁操作反复备份——`triggerAutoSyncIfNeeded` 三重保护（30 秒防抖 `lastAutoSyncTime` 全触发点共用同一窗口 + `autoSync` 开关前置 + 空库/无备份目录跳过）；启动刷新自身也有 30 秒防抖，仅在启动/授权后执行一次。

> 最后更新：2026-08-08

## 我的页数据管理/备份改独立页面（2026-08-08）

执行 AI：deepseek-v4-flash

用户反馈"数据管理三层太复杂，不要弹窗"，将弹层改为两个独立页面。

| 类别 | 改动 |
|---|---|
| 新页面 | `DataManagementFragment`（数据管理独立页：常规目录/COS目录/TXT导入作者 三区块平铺，返回栏+可滚动）；`DataBackupFragment`（数据备份独立页：备份位置/立即同步/自定义导出/空库恢复提示 + 内嵌缓存与同步区块） |
| 路由 | `MainActivity` 新增 `showDataManagement()`/`showDataBackup()`；`syncBottomNavVisibilityWithBackStack` 加入两个新页面类型（进入隐藏底部导航） |
| 导入入口 | 数据备份页顶部保留「选择导入目录并恢复」按钮（弹文件夹选择器→检测→确认→恢复，不改变备份位置，`importFromDirectoryUri`）；数据管理页不重复提供导入 |
| ProfileFragment | 从 965 行瘦身到 259 行（绿色区）：删除 6 个弹层方法/4 个 launcher/5 个缓存字段/2 个权限方法/自定义导出/缓存同步区块，全部迁移至新页面；保留推荐偏好/兼容性检查弹层 + 扫描 Toast |
| 文案 | 数据管理行"数据管理\n常规/COS目录与TXT导入"；数据备份行"数据备份\n备份位置与自动同步" |
| 文档 | GUIDE_UI（我的页/实现路径）、GUIDE_BACKUP（数据管理功能/个人偏好导出）、GUIDE_SCAN（入口描述）、GUIDE_DATA、DATA_MIGRATION_SPEC（恢复触发方式）、PROJECT_GUIDE（功能列表/目录结构）、GUIDE_README、GUIDE_CODE_MAINTENANCE（ProfileFragment 备案 965→259）、CHANGELOG |

> 最后更新：2026-08-08

## 我的页数据管理两层化（2026-08-08）

执行 AI：deepseek-v4-flash

用户反馈"数据管理三层太复杂"，将"我的"页数据管理从三层弹层改为两层平铺结构。

| 类别 | 改动 |
|---|---|
| 入口行 | 布局新增 `backupRow`「数据备份」独立入口行；`dataManagementRow` 文案改为"数据管理：导入数据、常规目录、COS目录、TXT导入作者" |
| 数据管理弹层 | 原"数据管理 → 常规/COS/TXT/数据备份/缓存与同步"五子层改为**单弹层平铺四区块**：导入数据 / 常规目录 / COS目录 / TXT导入作者。内容超高时 ScrollView 包裹弹层内滚动 |
| 导入数据 | 新增 `importDirectoryLauncher`（OpenDocumentTree）+ `importFromChosenDirectory`（peekBackupSummary 检测 → AlertDialog 确认 → 恢复）；`MediaLibraryViewModel` 新增 `importFromDirectoryUri`（不改变已设置备份位置）；选错目录提示"该目录未找到备份数据" |
| 数据备份弹层 | 独立弹层（原 `showBackupLocationDialog` 改名 `showBackupDialog`），内嵌「缓存与同步」区块（原独立 `showCacheSyncDialog` 删除，缩略图缓存进度 + 自动备份开关原样并入） |
| 区块组件 | `SheetUiHelper` 新增 `sheetSectionTitle`（区块分隔标题）；三个目录/TXT 弹层改为 `buildScanDirectorySection`/`buildCosDirectorySection`/`buildTxtImportSection` 区块构建函数 |
| 文档 | GUIDE_UI「我的页」、GUIDE_BACKUP「数据管理功能/个人偏好导出」、GUIDE_DATA「个人偏好数据导出」、DATA_MIGRATION_SPEC「恢复触发方式」、PROJECT_GUIDE、GUIDE_CODE_MAINTENANCE 备案（ProfileFragment 906→965、ViewModel 36→37 方法） |

> 最后更新：2026-08-08

## 全项目审查与 P0 数据安全修复（2026-08-08）

执行 AI：deepseek-v4-flash

基于 22 代理（11 主审查 + 11 复核）全项目只读审查，确认 4 项灾难级缺陷并完成修复，DB schema v3→v5。

| 类别 | 问题 | 修复方式 |
|---|---|---|
| 数据丢失（灾难级） | 启动清理 `cleanupOrphanFiles` 用文件 URI 前缀匹配扫描源 tree URI，MediaStore（`content://media`）与 SAF（`document`）文件恒匹配失败 → 全部非 COS 文件被判孤儿删除，首次扫描后重启即全清库 | `media_files` 新增 `sourceUri` 归属列（v3→v4），扫描时随文件写入扫描源 tree URI；清理/删除按归属列精确匹配；旧数据（null）保守保留 |
| 数据丢失（灾难级） | 跨源同名文件 recordKey 相同，`@Upsert` 主键整行互相覆盖，被覆盖文件消失 | `resolveRecordKeyConflicts` 写入前检测库中该 key 已被其他源占用时追加 pathHash 生成新 key |
| 数据丢失（灾难级） | 删除 COS 源用 folderUri（文件系统路径）与扫描源 content URI 前缀匹配恒失败 → 删除任一 COS 源清空所有源 cos_works，且本源媒体文件删不掉 | `cos_works` 新增 `sourceUri`（v4→v5），唯一索引重构为 `(sourceUri, authorName, workName)` 支持多 COS 源共存；删除按归属列精确匹配 |
| 构建断裂 | `.gitignore` 的 `*.txt` 误伤 `app/src/main/cpp/CMakeLists.txt`，克隆仓库后 assembleDebug 直接失败 | .gitignore 增加 `!app/src/main/cpp/CMakeLists.txt` 白名单并入库 |
| 数据一致性 | `updateBrowseSeconds` 读-改-写未包事务，快速退出详情页并发丢更新 | `withTransaction` 包裹 |
| 扫描正确性 | `MediaStoreScanner.queryCosFolder` 作品去重 O(N×M) 线性扫描（SAF 分支已用 Set） | 对齐 SAF 分支改 `seenWorks` Set 去重 |
| 扫描正确性 | MediaStoreObserver/ScanUseCase 全部前缀匹配改按 `sourceUri` 精确匹配 | 上述各处替换 |

## P1 备份与崩溃风险修复（2026-08-08）

执行 AI：deepseek-v4-flash

| 类别 | 问题 | 修复方式 |
|---|---|---|
| 备份数据安全 | 原子写先删旧文件再 rename，rename 失败/校验失败时新旧皆失 | round-trip 校验提前到 rename 前（校验 .tmp），通过才删旧文件；失败保留旧备份 |
| 备份数据安全 | settings.json 只导出从不导入，恢复后用户设置永不还原 | 补 `importSettings` 纳入导入流程 |
| 备份数据安全 | 9 个 JSON 顺序导入无事务，中途异常库半恢复状态 | 先读全部 → 单一数据库事务导入 8 个表，prefs 事务外写入 |
| 备份数据安全 | `importTags` 直插备份 tagId 撞号静默丢标签 | 忽略备份 tagId，按 name INSERT OR IGNORE 后查回本地真实 id |
| 备份数据安全 | `importLikes` 空 favorites 备份清空本地收藏 | 收藏改「本地∪备份」合并 + 清理陈旧点赞键 |
| 备份数据安全 | timeline 标签 autoGenerate 主键 @Upsert 无法去重，重复导入翻倍 | 导入前按 (recordKey,timeMillis,name) 查重跳过 |
| 备份健壮性 | `writeFile` 忽略 rename 返回值假成功；autoSync 完全吞异常无日志；fullSync 忽略个人偏好子目录失败 | 检查返回值 + 补 AppLog.e + 汇总子目录成功状态 |
| 崩溃（OOM） | `LargeImageDecoder` 直解路径无尺寸/内存上限绕过 Coil maxBitmapSize | 解码前 inJustDecodeBounds 预算检查，超 256MB 回退 Coil 受控解码 |
| 崩溃（旧设备） | 删除大目录 `DELETE IN(:大列表)` 超 SQLite 999 绑定参数上限抛异常回滚 | `deleteMediaAndRefs`/`rebuildCosIndex`/COS 源删除统一按 500 分块 |
| 崩溃（OOM） | 视频截帧 12 并发峰值约 800MB 原生堆 | 图片/视频分离并发，视频固定 2 并发 |
| 崩溃（OOM） | `convertTo565` copy 失败先 recycle 再返回已回收 bitmap | 转换失败保留原图不 recycle |
| 资源泄漏 | `loadEmbeddedCover`/`loadFirstFrame` setDataSource 抛异常时 pfd 不关闭 | pfd 改 `use{}` 包裹 |
| 缓存治理 | filesDir 缩略图缓存无上限只增不减 | 超过 5000 文件按修改时间淘汰最旧 |
| 功能错误（COS） | MediaStore 路径 COS 作者解析用 leaf 目录名当相对路径，结构2/3 作者被识别成作品名 | 新增 `MediaCandidate.directoryPath` 完整目录路径，`removePrefix` 后分割 |

> 最后更新：2026-08-08

## P2 渲染层与 P3 规范清理（2026-08-08）

执行 AI：deepseek-v4-flash

基于 P0/P1 修复后，由 8 个并行修复代理完成 UI 渲染层与规范清理（多代理并行，文件域互斥）。

### 渲染竞态与协程管理
| 文件 | 修复 |
|---|---|
| HomeFragment | render 并发去重（renderJob cancel，挂起期间 tab 改写不再被旧结果覆盖）；onScrolled 目标 tab 未渲染时跳过 submitList(emptyList) 白屏闪烁 |
| AllFilesFragment | 下拉刷新取消旧收集器（不再叠加永久收集器）；computeAllGroupsAsync 内快照 selectedPartitions/selectedSources 消除 CME；setViewMode 清空残留选中；renderJob 去重；COS 指纹纳入自动刷新判定 |
| FavoriteFragment/BrowseHistoryFragment | 下拉刷新接线（重读 prefs/render）；collect 过滤排序移入 Default 线程；renderJob 去重 + viewLifecycleOwner scope；收藏页补 PinchZoomHelper；COS 分区角色渲染对齐 renderCosWorkPills |
| SearchFragment | 搜索历史改时间戳 JSON 有序存储（最新在前/上限20）；手动标签参与搜索（观察 allMediaTags）；doSearch/rebuildNameIndex 协程取消；保存后历史 chips 刷新 |
| AlbumDetailFragment/AuthorFilesFragment | render 指纹前置判定 + renderJob 去重 + viewLifecycleOwner scope |
| ThumbnailUseCase | 取消时 CancellationException 重抛（不再吞掉导致队列任务丢失） |
| MainActivity | 深色切换 recreate 后从 FragmentManager 重建 fragment 缓存 + findFragmentByTag 兜底，消除重复 fragment 叠加 |

### 功能与数据正确性
| 文件 | 修复 |
|---|---|
| StatsDetailFragment | 标签均值公式改为 关联总数/去重文件数 |
| StatsFormatHelper | groupByWeek 改为从当前周向前取最近 12 周（旧实现取最旧 12 周丢最新趋势） |
| AuthorListFragment | renderHash 加入文件数/浏览数维度 + refreshBindings |
| AuthorFilesFragment | render 指纹加入 stats/tags 维度 |
| MediaBrowserLogic | 每日推荐惩罚改随展示次数递增（-0.8×count，旧恒 -0.8 高频刷新后失效）；balanceVideoImage 复用 seed |
| AlbumFragment | 仅自定义出处变化时清空 SourceMatcher 全局缓存；分组改 matchAll 走缓存 |
| SourceGroupsData | 好运姐/女枪/厄运小姐合并单条目（去别名冲突）、蕾珂丝移除 Lux 别名、崩坏3 variants 去重、NIKEE→NIKKE |

### 崩溃/内存/规范
| 文件 | 修复 |
|---|---|
| 4 个图表 View | 主题色 lazy 缓存改 onDraw 动态解析（uiMode 切换后刷新）；顺带消除动画每帧 new LinearGradient/new Paint |
| MediaFilterSheet/AuthorListFragment | ContextCompat.getColor(qm_*) 改 ThemeHelper.resolve 主题色 |
| MediaGroupHelper | 落「其他」诊断日志计数采样（每 50 次 1 条） |
| AppLog | 日志截断改一次截断再追加（旧每次追加全量重写） |
| MediaGroupHelperTest | 6 处 Kotlin 原生 assert() 改 JUnit assertTrue（Gradle 默认 -ea 虽生效但可读性差） |
| 死代码 | 删除 BackupRepository/AuthorEditDialog 整文件、SourceMatcher txt 体系、ZoomImageView 3 方法、cachedAutoSync、cachedCosAuthorNameToId、PieChartView selectedIndex 分支、observeTags 空 collect、FilterConfig 冗余预设 |

### 文档同步
- GUIDE_CODE_MAINTENANCE 备案行数/测试数/方法数刷新（MediaDetailFragment 1291、ProfileFragment 906、BackupManager 1567、SourceGroupsData 1674、MediaLibraryViewModel 36 方法等）
- PROJECT_GUIDE 实体数 13→12、测试类统一 5 个、构建版本号不同步如实记录
- GUIDE_UI 双击倍率统一 1.8x、「滑动暂停缩略图加载」「onViewRecycled 清 drawable」「亮度调节」3 项无实现声明改为如实描述
- GUIDE_ALGORITHM 出处数统一 131、别名合并与拼写修正说明、新增拼写校验规则

> 最后更新：2026-08-08

## 审查残留补修（2026-08-08）

执行 AI：deepseek-v4-flash

初轮修复后自查对照审查清单，补修 4 项当时遗漏的问题。

| 类别 | 问题 | 修复方式 |
|---|---|---|
| 功能 | `MediaStoreObserver.updateSources` 从未被调用：会话内新增源不被监听、删除的源仍被持有并在刷新时 upsertScanSource 复活 | 每次刷新前从库重拉最新常规源列表（getScanSources 过滤），删除的源不再复活 |
| 数据安全 | `AutoSyncUseCase.triggerAutoSyncForDetailExit` 与 `triggerAutoSyncIfNeeded` 共享防抖字段：扫描刚同步完、紧接的退出详情页同步被吞掉 | 退出详情页改用独立 `lastDetailExitSyncTime` 防抖字段 |
| 偏好安全 | `AppPrefsManager.saveToDisk` writeText 截断写，进程被杀留下损坏 JSON、偏好静默丢失 | 原子写（tmp + rename，失败回退直接写） |
| 崩溃（旧设备） | `deleteAuthorsByIds` 大批量 `DELETE IN(:大列表)` 超 SQLite 999 绑定参数上限抛异常回滚 | 按 500 分块 |
| 数据一致性 | `rebuildAssociationsFromBlocks` 三段写（upsert 作者→删旧关联→插新关联）非同一事务，中途崩溃留下半状态 | Repository 新增 `rebuildAuthorAssociations` 单事务方法，UseCase 改调它 |

> 最后更新：2026-08-08

## 遗留问题收尾（2026-08-08）：版本同步 + 依赖升级 + 详情页 + 算法测试

执行 AI：deepseek-v4-flash

| 类别 | 内容 |
|---|---|
| 版本号 | `versionName` 1.7→**1.12**、`versionCode` 9→10，与功能版本同步 |
| 依赖升级 | Media3 1.4.1→**1.8.0**（落后 7 大版本→1.8 稳定线，媒体解析器安全修复）；androidx core-ktx 1.10.1→1.17.0、appcompat 1.6.1→1.7.1、material 1.10.0→1.13.0、activity-ktx 1.8.0→1.10.1、constraintlayout 2.1.4→2.2.1、lifecycle 2.9.4→2.10.0、Room 2.8.3→2.8.4；AGP 保持 9.2.1（9.3.0 需 Gradle 9.5.0，本环境 SSL 无法下载，toml 已注明） |
| 详情页-分页索引错位 | 新增 `sourceIndexByKey` 映射 + `loadedEndIndex` 区段边界；向前/向后加载严格不重叠、空段推进、位置显示精确映射——打开期间文件被删不再错项/重复/卡死 |
| 详情页-超大图预加载 | `isMemoryComfortable()` 加 native 堆判定（超大图走 native 堆，原 Java 堆度量失效）；窗口内超大图最多预载 1 张、内存紧张跳过全部；普通图预载数量不变 |
| 算法单测 | 新增 `MediaBrowserLogicRecommendTest` 25 个测试（recommend/rank/applyFilter 零覆盖补齐：seed 可复现、惩罚递增、类型平衡、范围/标签/关键词筛选、排序稳定性）；项目测试 132 个全通过 |
| 功能 bug | `comparatorFor(NAME)` 原用升序比较器导致 NAME 升/降序语义相反（其余键均 compareByDescending），统一为 compareByDescending 并更新对应测试 |

> 最后更新：2026-08-08

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
