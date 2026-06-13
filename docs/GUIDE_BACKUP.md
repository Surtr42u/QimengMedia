# GUIDE_BACKUP - 数据备份与迁移

## 实现路径

目标源码路径：`app/src/main/java/com/qimeng/media/backup/`

当前实现：
- `BackupManager`：从 Room 读取所有数据表，生成 JSON 文件写入 SAF 授权目录；同时支持从 JSON 读取并恢复数据。
- `ProfileFragment`：通过"数据管理"行打开项目主题底部弹层，统一承载扫描目录、保存位置、相册规则、TXT 导入作者；保存位置写入 `settings` 表。

## 职责

管理 App 数据的 JSON 导入、导出和指定目录自动同步。
各 JSON 文件的格式定义、字段清单、实体映射见 `docs/GUIDE_DATA.md`。

不管：数据实体字段定义（见 GUIDE_DATA.md）、扫描流程（见 GUIDE_SCAN.md）

## 备份原则

- 只备份 App 自己的数据。
- 不备份原始媒体文件。
- 不写入媒体文件所在目录，除非用户明确把备份目录选在那里。
- 指定备份文件夹后，数据变化自动同步。
- 手动导出和导入也必须保留。

## 文件规范

- JSON 使用 UTF-8。
- 每个 JSON 文件包含 `schemaVersion`。
- 字段名稳定、可读。
- 文件名可读（`settings.json`、`authors.json` 等）。
- 不导出原始媒体内容。
- 不把绝对路径作为唯一迁移依据。

### 隐私保护

- 所有导出文件包含用户个人行为数据，属于隐私数据
- 项目提供虚构占位数据的示例文件，AI 代理应读取示例文件理解格式，不读取真实导出数据
- 示例文件位置：
  - 个人偏好：`app/src/main/assets/personal_prefs_example.json`
  - App 数据：`app/src/main/assets/app_data_examples/*.json`（10 个文件）
  - 格式说明目录（导出后）：`绮梦影库/格式说明/`，包含 `FORMAT_SPEC.md`、`personal_prefs_example.json`、`app_data_examples/`（10 个示例文件）
- 格式规范详见 `docs/DATA_MIGRATION_SPEC.md`

## 写入原则

- 写入备份目录前确认用户授权。
- 自动同步使用防抖，避免每次小改动都写盘。
- 写入时使用临时文件再替换，避免半写入损坏。
- 导出失败提示用户，不影响数据库主数据。

## 导入原则

- 先解析 `schemaVersion`。
- 对缺失字段使用默认值。
- 对未知字段忽略但保留兼容空间。
- 找不到媒体文件时保留未匹配记录。
- 导入前可提示是否覆盖、合并或跳过冲突。

## 冲突处理

- 同一完整文件名记录冲突时按更新时间或用户选择处理。
- 作者、标签、相册规则按名称合并。
- 重复作者 ID 时合并关联文件。
- 作者关注状态随个人偏好导出（`personal_prefs.json` 的 `followedAuthorIds` 字段 + 每个作者的 `followed` 和 `viewCount` 字段）。
- 统计数据可选择覆盖或取最大值，具体以项目文档为准。

## 数据管理功能

- 保存位置选择：使用 SAF 选择备份保存目录，自动创建「绮梦影库」目录及「app数据」「个人偏好」「格式说明」子文件夹，持久化目录 Uri 和显示名。
- 自动同步开关：我的页提供自动同步开关（`AppPrefs.autoSync`），开启后数据变化自动写入备份目录。
  - 触发时机：
    - 扫描完成/增量刷新/TXT导入后 → `triggerAutoSyncIfNeeded()`（只写 app数据/）
    - 退出详情页 → `triggerAutoSyncForDetailExit()`（只写 app数据/，JSON 快）
    - App 进入后台 → `triggerFullSync()`（写 app数据/ + 个人偏好/，60秒防抖）
    - 手动同步 → `triggerManualSync()`（写 app数据/ + 个人偏好/，无视防抖）
  - 防抖：app数据/ 30秒防抖，全量同步 60秒防抖
  - 前提：需先设置备份保存位置
  - 实现：`AutoSyncUseCase` → `BackupManager.autoSyncToDirectory()` / `BackupManager.fullSyncToDirectory()`
- 手动同步：数据备份弹窗中提供"立即同步"按钮，无视防抖立即全量同步两个数据子目录（app数据/ + 个人偏好/）。
- 自动同步状态展示。
- 扫描目录：从数据管理弹层添加媒体目录，并和已授权扫描目录一起重新索引。
- 清空历史记录。
- TXT 导入作者入口。

## 导入导出流程

- 导出：`BackupManager` 读取所有 Room 表 + `AppPrefsManager` 推荐偏好 → 序列化 JSON → 写入备份目录 `绮梦影库/app数据/`。
- 导入：从备份目录 `绮梦影库/app数据/` 读取 JSON → 反序列化 → 写入 Room + `AppPrefsManager`。
- 当前导出的 JSON 文件共 10 个：settings、authors、tags、album_rules、media_stats、history、scan_sources、likes、recommendation_prefs、timeline_tags。每个 JSON 文件在 `app/src/main/assets/app_data_examples/` 目录下有对应的虚构占位数据示例文件（如 `settings_example.json`），AI 代理应读取示例文件理解格式，而非读取真实导出数据。
- 个人偏好导出：写入备份目录 `绮梦影库/个人偏好/`，包含偏好JSON和中文报告TXT（`personal_prefs.json` 和 `personal_prefs_report.txt` 不算在 app数据/ 的文件列表中）。
- 格式说明目录：`绮梦影库/格式说明/`，包含格式使用指南和示例文件，仅在首次同步时写入，已存在则跳过。
- 导入时遇到不存在的文件，保留记录但标记未匹配。
- 备份 JSON 损坏时要提示用户，不允许崩溃。

## 个人偏好导出

通过 ProfileFragment 数据管理弹层"导出偏好数据"触发，导出为 2 个文件（COS 和常规数据合并）：

| 文件 | 格式 | 用途 |
|---|---|---|
| `personal_prefs.json` | JSON | 完整数据备份，COS+常规统一，含统计/标签/作者/作品 |
| `personal_prefs_report.txt` | 纯中文文本 | 给用户自己看，人类可读的偏好报告 |

### JSON 文件（完整数据备份）

`data` 区域包含 8 个部分（COS+常规统一）：

| 部分 | 来源 | 说明 |
|---|---|---|
| `favorites` | SharedPreferences `favorite_record_keys` | 收藏的文件 recordKey 列表（全部） |
| `likes` | SharedPreferences `like_count_*` / `like_date_*` | 点赞次数 + 最近点赞日期（全部） |
| `tags` | Room `TagEntity` | 标签定义（全部） |
| `mediaTags` | Room `MediaTagCrossRef` + `TagEntity` | 文件与标签关联（全部） |
| `viewStats` | Room `ViewStatsEntity` + `MediaFileEntity` | 观看/播放次数 + 浏览时长 + mediaType + isCosFile（全部） |
| `authors` | Room `AuthorEntity` + `AuthorMediaCrossRef` | 作者及关联文件列表 + viewCount + followed + tags（全部，COS+常规统一） |
| `followedAuthorIds` | SharedPreferences `author_follow_prefs` | 关注的作者 ID 列表 |
| `cosWorks` | Room `CosWorkEntity` + `ViewStatsEntity` + `MediaFileEntity` | COS 作品数据，含统计/标签/文件列表 |

JSON 可完整还原 TXT 报告（viewStats 含 mediaType 和 isCosFile，可区分图片/视频和 COS/常规）。

格式规范和字段定义详见 `docs/DATA_MIGRATION_SPEC.md`。

### 文本报告（给用户看）

纯中文文本，包含以下章节：
- 【总览】文件总数（常规/COS）、收藏数（常规/COS）、标签数、作者数、关注作者数、总查看/播放次数、总浏览时长
- 【总 Top 30】COS+常规混合，按热度分降序
- 【常规 Top 20】非 COS 文件
- 【COS Top 20】COS 文件
- 【作者 Top 20】不分 COS/常规，按偏好度降序
- 【关注的作者】全部列出
- 【标签 Top 10】按关联文件数降序
- 【所有标签】全部列出（名称 + 关联文件数）
- 【收藏的文件】全部列出
- 排行说明

热度分计算：
- 图片：viewCount + likeCount + (收藏 ? 5 : 0)
- 视频：viewCount + playCount + floor(浏览秒/60) + likeCount + (收藏 ? 5 : 0)

作者偏好度 = 所有关联文件的 (viewCount + playCount) 之和 + 每个收藏文件 +5 + 每个文件的 likeCount 之和

### 示例文件

`app/src/main/assets/personal_prefs_example.json` 包含虚构占位数据，展示所有字段和结构。AI 代理应读取此示例文件理解格式，而非读取真实导出数据。

### 与旧版本的差异

- COS 偏好不再单独导出（原 `cos_prefs_export.json` 和 `cos_prefs_report.txt` 已删除）
- COS 数据合并到 `personal_prefs.json` 的 `cosWorks` 部分
- `personal_prefs_export.json` 重命名为 `personal_prefs.json`
- 新增 `格式说明/` 子目录，包含 `FORMAT_SPEC.md`、`personal_prefs_example.json`、`app_data_examples/`，仅在首次同步时写入
- `viewStats` 新增 `mediaType` 和 `isCosFile` 字段
- `cosWorks` 扩展了统计字段（viewCount/playCount/totalBrowseSeconds/likeCount/favoriteCount/tags/files）

## 验收清单

## 修改注意事项

- 新增数据库字段时，JSON 格式必须在 `docs/GUIDE_DATA.md` 中同步定义，本文件只关注读写流程是否有变。
- 修改导入导出逻辑需同步 `docs/GUIDE_ANDROID_COMPAT.md`（确认 SAF 写权限可用）。
- 数据迁移到新手机时，只需复制 `绮梦影库/` 目录并在新手机上导入。
- 格式规范变更需同步 `docs/DATA_MIGRATION_SPEC.md`。

> 最后更新：2026-06-13
