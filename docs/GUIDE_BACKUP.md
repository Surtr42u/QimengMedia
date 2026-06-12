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
- 作者关注状态随个人偏好导出（`personal_prefs_export.json` 的 `followedAuthorIds` 字段 + 每个作者的 `followed` 和 `viewCount` 字段）。
- 统计数据可选择覆盖或取最大值，具体以项目文档为准。

## 数据管理功能

- 保存位置选择：使用 SAF 选择备份保存目录，自动创建「绮梦影库」目录及「个人偏好」「app数据」子文件夹，持久化目录 Uri 和显示名。
- 自动同步开关：我的页提供自动同步开关（`AppPrefs.autoSync`），开启后数据变化自动写入备份目录。
  - 触发时机：
    - 扫描完成/增量刷新/TXT导入后 → `triggerAutoSyncIfNeeded()`（只写 app数据/）
    - 退出详情页 → `triggerAutoSyncForDetailExit()`（只写 app数据/，JSON 快）
    - App 进入后台 → `triggerFullSync()`（写 app数据/ + 个人偏好/，60秒防抖）
    - 手动同步 → `triggerManualSync()`（写 app数据/ + 个人偏好/，无视防抖）
  - 防抖：app数据/ 30秒防抖，全量同步 60秒防抖
  - 前提：需先设置备份保存位置
  - 实现：`AutoSyncUseCase` → `BackupManager.autoSyncToDirectory()` / `BackupManager.fullSyncToDirectory()`
- 手动同步：数据备份弹窗中提供"立即同步"按钮，无视防抖立即全量同步两个子目录。
- 自动同步状态展示。
- 扫描目录：从数据管理弹层添加媒体目录，并和已授权扫描目录一起重新索引。
- 清空历史记录。
- TXT 导入作者入口。

## 导入导出流程

- 导出：`BackupManager` 读取所有 Room 表 + `AppPrefsManager` 推荐偏好 → 序列化 JSON → 写入备份目录 `绮梦影库/app数据/`。
- 导入：从备份目录 `绮梦影库/app数据/` 读取 JSON → 反序列化 → 写入 Room + `AppPrefsManager`。
- 当前导出的 JSON 文件共 11 个：settings、authors、tags、album_rules、media_stats、history、scan_sources、likes、personal_prefs_export、recommendation_prefs、timeline_tags。
- 个人偏好导出：写入备份目录 `绮梦影库/个人偏好/`，包含偏好JSON和中文报告TXT。
- 导入时遇到不存在的文件，保留记录但标记未匹配。
- 备份 JSON 损坏时要提示用户，不允许崩溃。

## 验收清单

## 修改注意事项

- 新增数据库字段时，JSON 格式必须在 `docs/GUIDE_DATA.md` 中同步定义，本文件只关注读写流程是否有变。
- 修改导入导出逻辑需同步 `docs/GUIDE_ANDROID_COMPAT.md`（确认 SAF 写权限可用）。
- 数据迁移到新手机时，只需复制 `绮梦影库/` 目录并在新手机上导入。

> 最后更新：2026-06-10
