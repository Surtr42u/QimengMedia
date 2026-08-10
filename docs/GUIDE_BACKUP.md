# GUIDE_BACKUP - 数据备份与迁移

## 实现路径

目标源码路径：`app/src/main/java/com/qimeng/media/backup/`

当前实现：
- `BackupManager`：从 Room 读取所有数据表 + SharedPreferences + AppPrefs，序列化为**单个** `qimeng_backup.json` 写入 SAF 授权目录；同时支持从该文件读取并恢复数据。
- `AutoSyncUseCase`：数据变化自动同步、App 进后台兜底同步、手动同步（统一写单文件）。
- `DataBackupFragment`：数据备份独立页面（我的页 → 数据备份），含备份位置、手动同步、导入恢复、空库防护提示、缓存与同步区块。

## 职责

管理 App 数据的 JSON 导入、导出和指定目录自动同步。
`qimeng_backup.json` 的格式定义、字段清单、实体映射见 `docs/DATA_MIGRATION_SPEC.md` 与 `docs/GUIDE_DATA.md`。

不管：数据实体字段定义（详见 GUIDE_DATA.md「目标实体」）、扫描流程（详见 GUIDE_SCAN.md「两套扫描体系」）

## 备份原则

- 只备份 App 自己的数据。
- 不备份原始媒体文件（只备份 `media_files` 表的元数据：文件名/大小/时间/类型等）。
- 不写入媒体文件所在目录，除非用户明确把备份目录选在那里。
- 指定备份文件夹后，数据变化自动同步。
- 手动导出和导入也必须保留。

## 文件规范（v2.0 单文件）

- 备份目录 `绮梦影库/` 根目录下只有**一个数据文件** `qimeng_backup.json`（不再拆 app数据/个人偏好/格式说明 三个子目录与 10+ 分文件）。
- JSON 使用 UTF-8，字段名自解释（fileName/viewCount/openedAtMillis），**AI 可直接读取该文件生成任意报告**，App 不再内置报告器。
- 顶层含 `format`（固定 `qimeng_backup`）、`schemaVersion`（当前 1）、`appIdentifier`、`exportedAtMillis`、`data`（17 个数据段）。
- data 段覆盖：mediaFiles / settings / authors / authorMediaRefs / tags / mediaTagRefs / albumRules / mediaStats / dailyBrowse / history / scanSources / timelineTags / cosWorks / likes / favorites / followedAuthorIds / appPrefs。
- **dailyBrowse 段（v1.14 新增）**：按天浏览聚合（每文件每天一行：次数/播放/时长），无限保留，是统计页趋势/窗口排行/沉浸时长的真相来源。备份聚合而非浏览历史明细——明细（history）仍只导出最近 1000 条供轻量展示，恢复后窗口统计与备份时完全一致。

### 隐私保护

- 备份文件包含用户个人行为数据，属于隐私数据
- 项目提供虚构占位数据的示例文件 `app/src/main/assets/qimeng_backup_example.json`，AI 代理应读取示例文件理解格式，不读取真实导出数据
- 格式规范详见 `docs/DATA_MIGRATION_SPEC.md`

## 写入原则

- 写入备份目录前确认用户授权（SAF）。
- 自动同步使用防抖（数据变化 30 秒 / 后台兜底 60 秒），避免每次小改动都写盘。
- 写入时使用临时文件再替换（tmp → round-trip 校验 → 删旧 → rename），避免半写入损坏；**校验提前到 rename 之前**（校验 `.tmp` 内容可解析且一致），校验通过才删旧文件、再 rename；校验失败只删 `.tmp`、保留旧备份。
- **v1.15 原子写加固**：rename 前旧备份先 `renameTo(.bak)` 暂存，新文件 rename 成功后删 `.bak`；任一步失败均保留至少一份有效备份（.bak 还原或退化先删旧再 rename），根治"删旧→rename 失败"导致唯一备份整体消失的问题。SAF provider 不支持 rename 旧文件时退化处理。
- **v1.15 写侧大小上限**：`buildBackupJson` 完成后检查 `bytes.size > MAX_BACKUP_BYTES`（64MB，与读侧共用常量），超限拒绝写入并记 `AppLog.w`，不删旧备份——避免大库导出超限照写成功但永远读不回。
- 导出失败提示用户，不影响数据库主数据。
- **空库覆盖防护**：自动同步（`triggerAutoSyncIfNeeded`/`triggerFullSync`）和手动同步（`triggerManualSync`）在调用 BackupManager 写入前，用 `MediaFileDao.hasAny()`（EXISTS + LIMIT 1）检测 `media_files` 表是否为空。空库时跳过写入、置 `AutoSyncUseCase.restoreSuggestion` 为 true 通知 UI 提示"建议先导入恢复"，避免卸载重装后空数据库反向覆盖旧备份导致数据永久丢失。`triggerManualSync` 空库时返回 false，UI 据此显示"本地数据为空，已暂停同步"而非"同步失败"。

## 导入原则

- 先解析 `schemaVersion`（**v1.15：导入时校验，备份 schemaVersion > 当前支持版本（1）则拒绝导入并记日志，防未来版本文件被静默错解析**）。
- 对缺失字段使用默认值。
- 对未知字段忽略但保留兼容空间。
- 找不到媒体文件时跳过未匹配记录（跨引用安全：authorMediaRefs/mediaTagRefs/mediaStats/history/timelineTags 仅当 recordKey 存在于 media_files 时才建立关联）。
- 导入前可提示是否覆盖、合并或跳过冲突。
- **media_files 合并策略**：本地 `media_files` 为空（全新重装未扫描）→ 导入备份 media_files 全量 → 浏览历史/统计/标签**立即可见**，无需重新扫描；本地非空（已扫描）→ 跳过 media_files（保留新扫描的有效 URI），引用数据按稳定 key 直接合并。
- **选目录后自动检测恢复**：用户在数据备份页选择备份目录后，`DataBackupFragment.saveBackupLocation` 调用 `BackupManager.peekBackupSummary`（只读，不写数据库）检测 `qimeng_backup.json` 是否存在。检测到数据则弹 `AlertDialog` 提示"X 个媒体文件 / Y 位作者 / Z 个标签 / W 条统计，是否导入恢复"，用户确认后调 `importFromDirectory` 恢复。同一目录只提示一次（用独立 SharedPreferences `restore_hint_prefs` 记录，key 为 `hint_shown_<完整URI>`，卸载重装后清空会重新提示）。

### 导入安全加固

备份 JSON 虽通常为用户自有数据，但仍做防御性加固，防恶意/损坏备份导致异常：

- **JSON 文件大小上限**：读取时单文件最大 64MB（预检 `DocumentFile.length()` + 读取后二次校验 `bytes.size`），超过则跳过并记 `AppLog.w`，防 OOM。正常备份 JSON 仅几 MB（数千文件约 1MB 级），不触及上限。
- **数值字段范围校验**：`mediaStats` 的 `viewCount`/`playCount`/`totalBrowseSeconds`/`updatedAtMillis`、`history` 的 `openedAtMillis`、`likes` 的 `likeCount`、`cosWorks` 的 `fileCount` 均用 `coerceAtLeast(0)` 钳制非负，防恶意备份注入负数/异常统计。
- **likes 条目数上限**：最多 5000 条，超过跳过剩余并记日志，防 SharedPreferences 键膨胀。
- **跨引用注入防护**：`authorMediaRefs`/`mediaTagRefs`/`mediaStats`/`history`/`timelineTags` 的 recordKey 仅当 `media_files` 中存在时才建立关联，不存在的 key 静默跳过，无法注入任意关联。
- **SAF 重新授权**：导入的 `uriString`（扫描源/备份目录）由 Android `ContentResolver` 在每次访问时重新授权，用户未授权的 URI 直接 `SecurityException`，无法逃逸到任意文件。

## 冲突处理

- 同一完整文件名记录冲突时按更新时间或用户选择处理。
- 作者、标签、相册规则按名称合并。
- 重复作者 ID 时合并关联文件。
- 统计数据按 recordKey 合并，数值钳制非负。
- **标签合并**：导入忽略备份 tagId、按 name `INSERT OR IGNORE` 去重后查回本地真实 tagId 建关联，避免 autoGenerate id 撞号导致标签及关联静默丢失（v1.15：恢复计数按 insert 返回值过滤 IGNORE 冲突，重复恢复不再虚增 count）。
- **收藏合并**：收藏改为「本地 ∪ 备份」合并，空 favorites 备份不再清空本地已有收藏；同时清理备份中已不存在的陈旧点赞键（`like_count_*`/`like_date_*`）。
- **点赞合并（v1.15）**：点赞恢复改「本地 ∪ 备份」并集合并（与收藏语义一致）——只导入备份中存在的新键，本地独有但备份中没有的点赞保留，不再静默删除（旧实现凡不在备份中的本地点赞键一律 remove）。
- **专辑规则（v1.15）**：导出改为全部规则（含 `enabled=0` 禁用规则），恢复按备份 enabled 值还原——旧实现只导出启用规则，禁用规则恢复后永久丢失。
- **时间轴标签去重**：导入前按 `(recordKey, timeMillis, name)` 查重（`TimelineTagDao.findExisting`），已存在则跳过——主键 autoGenerate 使 `@Upsert` 无法去重。
- **settings 导入**：纳入导入流程（恢复用户设置），但跳过设备本地键 `backup_directory_uri`/`backup_directory_name`（重装后备份目录 URI 无意义）。
- **导入事务性**：`importFromDirectory` 先解析全部数据段，再在**单一数据库事务**内导入数据库相关段落，避免中途异常留下"先序已入库、后序未导入"的半恢复状态；likes/favorites/followedAuthorIds/appPrefs 为 SharedPreferences 写入，事务外执行。

## 数据管理功能

- 保存位置选择：使用 SAF 选择备份保存目录，自动创建「绮梦影库」目录（备份主文件 `qimeng_backup.json` 写入其根目录），持久化目录 Uri 和显示名。设置成功后立即写入一份全量备份。
- **导入数据**：数据备份页顶部提供「选择导入目录并恢复」按钮（`importDirectoryLauncher`，`OpenDocumentTree`），点击直接弹文件夹选择器，选目录后 `importFromChosenDirectory` 调 `peekBackupSummary` 只读检测——检测到备份数据弹 `AlertDialog` 提示"X 个媒体文件 / Y 位作者 / Z 个标签 / W 条统计，是否导入恢复到本地"，确认后调 `MediaLibraryViewModel.importFromDirectoryUri` → `BackupManager.importFromDirectory` 恢复；无备份数据提示"该目录未找到备份数据"。**不改变已设置的备份位置**（与"设置备份位置"是两个独立动作）。另有两条导入路径：①空库防护提示区"导入恢复"按钮（从已设置备份位置导入，调 `importFromBackupDirectory`）；②选备份目录后自动检测恢复（`saveBackupLocation` → `peekBackupSummary` → `AlertDialog` → 恢复）。
- 自动同步开关：数据备份页内嵌「缓存与同步」区块提供自动同步开关（`AppPrefs.autoSync`），开启后数据变化自动写入备份目录。
  - 触发时机：
    - App 启动自动刷新完成（`autoRefreshAllSources`）→ `triggerAutoSyncIfNeeded()`（30秒防抖）
    - 扫描完成/增量刷新/TXT导入后 → `triggerAutoSyncIfNeeded()`
    - 退出详情页 → `triggerAutoSyncIfNeeded()`（详情页内的点赞/收藏/标签/时间轴标签修改）
    - 标签增删改、时间轴标签增删、作者关注切换、清空历史、手动添加作者、删除扫描源、自定义相册源 → `triggerAutoSyncIfNeeded()`
    - App 进入后台 → `triggerFullSync()`（60秒防抖兜底）
    - 手动同步 → `triggerManualSync()`（无视防抖）
  - 防抖：数据变化 30 秒 / 后台兜底 60 秒（v2.0 起两个入口统一写单文件，不再区分"只写 app数据"与"全量"——单文件全量成本可接受，流程大幅简化）
  - 前提：需先设置备份保存位置
  - 实现：`AutoSyncUseCase` → `BackupManager.exportToDirectory()`
- 手动同步：数据备份页"立即同步"按钮，无视防抖立即写入单文件。空库时被拦截返回 false，Toast 提示"本地数据为空，已暂停同步"。
- 恢复提示：选择备份目录后，App 用 `BackupManager.peekBackupSummary`（只读）检测目录是否已有备份数据，有则弹 `AlertDialog` 提示。用户确认后调 `importFromDirectory` 恢复；取消也标记已展示，同一目录不重复打扰。
- 空库覆盖防护：数据备份页在 `AutoSyncUseCase.restoreSuggestion` 为 true 时，顶部显示"⚠ 本地数据为空，已暂停自动同步，建议先导入恢复"提示行 + "导入恢复"按钮。防护阈值：`media_files` 表为空即触发。

## 导入导出流程

- 导出：`BackupManager.buildBackupJson` 读取所有 Room 表 + SharedPreferences + AppPrefs → 序列化为单个 JSON → 原子写入备份目录 `绮梦影库/qimeng_backup.json`。
- 导入：从备份目录读取 `qimeng_backup.json` → 反序列化 → 单事务写入 Room + SharedPreferences + AppPrefs。
- 备份 JSON 损坏时要提示用户，不允许崩溃。
- **导出过滤**：v2.0 起不再按 media_files 白名单过滤（旧版过滤是为避免已删除文件的残留数据出现在导出中）；现在 media_files 本身全量进备份，引用数据与之一致，无需过滤。恢复后引用数据按 recordKey 与 media_files 关联，缺失的引用由跨引用安全跳过兜底。

### schemaVersion

- 当前 `schemaVersion = 1`（format = `qimeng_backup`）。
- v2.0 重做，不兼容旧版三套产物（app数据 10 分文件 / personal_prefs.json / 报告 txt），旧格式备份不再读取。

## 个人偏好导出（已移除）

- **v2.0 删除**：App 内不再内置"个人偏好报告"生成器（`buildReportText` 9 章节 + `personal_prefs.json` + 自定义章节勾选）。
- 原因：报告是"展示"不是"数据"，`qimeng_backup.json` 字段自解释，需要报告时把备份文件直接交给 AI 现场生成即可，比 App 内置一套死章节更灵活、零维护。
- 如需报告，将 `qimeng_backup.json` 提供给任意 AI，AI 按 `docs/DATA_MIGRATION_SPEC.md` 读取并生成（总览/排行/收藏/关注等均可）。

## 与旧版本的差异（v2.0）

- 备份目录结构：`绮梦影库/` 根目录单文件 `qimeng_backup.json`（旧：app数据/ 10 分文件 + 个人偏好/ 2 文件 + 格式说明/ 示例目录）。
- `media_files` 全量元数据进备份（旧版从未导出）→ 恢复后浏览历史/统计/标签立即可用，不依赖重扫。
- 新增导出：`cos_works` 表（含 sourceUri）、`AuthorMediaCrossRef.isMatched`、`MediaFileEntity.sourceUri`、AppPrefs 全量（theme_mode/gridColumns/autoSync/customAlbumSources/recommendationPrefs）。
- 删除：个人偏好 JSON + 报告 TXT、格式说明/ 目录、示例文件（10 个 app_data_examples + FORMAT_SPEC.md + personal_prefs_example.json）。
- 自动同步：两个入口统一写单文件（旧：autoSyncToDirectory 只写 app数据/ 与 fullSyncToDirectory 三子目录）。
- 死代码清理：`LocalMediaRepository.recordHistory`（无调用方）删除。

## 修改注意事项

- 新增数据库字段时，JSON 格式必须在 `docs/DATA_MIGRATION_SPEC.md` 与 `docs/GUIDE_DATA.md` 中同步定义，本文件只关注读写流程是否有变。
- 修改导入导出逻辑需同步 `docs/GUIDE_ANDROID_COMPAT.md`（确认 SAF 写权限可用）。
- 数据迁移到新手机时，只需复制 `绮梦影库/` 目录（含 `qimeng_backup.json`）并在新手机上导入。
- 格式规范变更需同步 `docs/DATA_MIGRATION_SPEC.md`。

> 最后更新：2026-08-10（v1.15 备份加固）
