# CHANGELOG - 代码审查与重构历史

本文件归档项目历次代码审查、重构、性能优化的详细记录。当前有效的规则、备案、共享组件表见 `GUIDE_CODE_MAINTENANCE.md`。

## AI 署名约定

- 每个变更条目标注实际执行该改动的 AI 模型（如 `deepseek-v4-flash`），便于追溯每次改动由谁完成。
- **同一天同一 AI 完成的多个条目只需署一次名**：在该 AI 当天的第一个条目标注即可，当日其余条目视为同一 AI 执行；若当天中途换 AI，则在新 AI 的第一个条目另行标注。
- **多 AI 协作（并行代理）时各条目自行署名**：每个条目标注实际执行该改动的 AI。
- 模型名使用执行时的真实命名（品牌-版本），不写模糊描述（如"某个 AI"）。
- 历史条目（2026-08-08 及之前）不补录 AI 身份，本约定自 2026-08-09 起生效。

## 作者 TXT 数字开头文件名误判编号行修复 + findMatch 日志降频（2026-08-09）

> 执行 AI：deepseek-v4-flash

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

用户询问"打开 App 是否自带一次同步"，确认缺口后修复：启动自动刷新此前只刷媒体库，不重新关联 TXT 作者、不触发备份写盘。

| 文件 | 改动 |
|---|---|
| `ScanUseCase.doAutoRefreshAllSources` | 启动自动刷新所有源完成后追加 `rematchAllTxtImports(this)`，新文件自动关联 TXT 作者（与 scanDirectory/refreshScanSource 行为对齐） |
| `MediaLibraryViewModel.autoRefreshAllSources` | 刷新完成后追加 `autoSyncUseCase.triggerAutoSyncIfNeeded()`（只写 app数据/） |

**防抖确认**：不会因 App 内频繁操作反复备份——`triggerAutoSyncIfNeeded` 三重保护（30 秒防抖 `lastAutoSyncTime` 全触发点共用同一窗口 + `autoSync` 开关前置 + 空库/无备份目录跳过）；启动刷新自身也有 30 秒防抖，仅在启动/授权后执行一次。

> 最后更新：2026-08-08

## 我的页数据管理/备份改独立页面（2026-08-08）

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
