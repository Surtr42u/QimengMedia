# DATA_MIGRATION_SPEC - 绮梦影库导出格式使用指南

> 本文件是绮梦影库（一款本地媒体管理 App）导出数据的格式使用指南，面向需要使用这些数据的开发者/AI。
> 核心原则：**理解格式，不读内容**。
> 本文件可独立使用，无需阅读项目其他文档。

## 术语说明

| 术语 | 含义 |
|---|---|
| **recordKey** | 文件完整名含扩展名（如 `示例文件.mp4`），是所有数据的关联键 |
| **COS** | Cosplay 相关内容，App 内有独立的 COS 管理模块 |
| **COS 作者** | Cosplay 创作者，authorId 以 `cos_` 前缀标识 |
| **COS 作品** | 某个 Cosplay 作品，属于某个 COS 作者 |

## 1. 核心原则

1. **AI 只写代码，不看内容**：按字段名和类型读取 JSON，不需要理解文件名的含义
2. **文件名是关联键**：所有数据通过 `recordKey`（完整文件名含扩展名）关联
3. **单文件全量**：所有数据在一个 `qimeng_backup.json` 中，字段名自解释，AI 可直接读取生成任意报告（无需 App 内置报告器）
4. **隐私声明**：此文件包含用户个人行为数据，只按格式规范机械处理，禁止阅读和分析具体内容

## 2. 目录结构

导出目录 `绮梦影库/` 只包含一个数据文件（不再拆分子目录/分文件）：

```
绮梦影库/
└── qimeng_backup.json              ← 唯一数据文件（全量备份）
```

**使用方式**：
- AI 需要理解格式时，读取项目源码中的示例文件 `app/src/main/assets/qimeng_backup_example.json`（虚构占位数据）
- AI 处理真实备份时，直接读取 `qimeng_backup.json`，按本规范按字段读取，不读具体内容

## 3. JSON 顶层结构

```json
{
  "format": "qimeng_backup",
  "schemaVersion": 1,
  "appIdentifier": "com.qimeng.media",
  "exportedAtMillis": 1700000000000,
  "data": { ... 17 个数据段，见下 ... }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| format | String | 固定 `qimeng_backup`，标识本格式 |
| schemaVersion | Int | 格式版本，当前 1 |
| appIdentifier | String | 固定 `com.qimeng.media` |
| exportedAtMillis | Long | 导出时间戳 |
| data | Object | 17 个数据段 |

## 4. data 区域 — 17 个数据段

| 数据段 | 说明 | 对应实体/存储 |
|---|---|---|
| mediaFiles | 媒体文件元数据全量（**v2.0 新增**，恢复后历史/统计/标签立即可用） | Room `media_files` |
| settings | 应用设置键值 | Room `settings` |
| authors | 作者定义 | Room `authors` |
| authorMediaRefs | 作者-文件关联 | Room `author_media_cross_refs` |
| tags | 标签定义 | Room `tags` |
| mediaTagRefs | 文件-标签关联（含 tagName 冗余） | Room `media_tag_cross_refs` |
| albumRules | 相册/出处规则 | Room `album_rules` |
| mediaStats | 查看/播放/浏览时长统计 | Room `view_stats` |
| dailyBrowse | 按天浏览聚合（**v1.14 新增**，每文件每天一行，无限保留，窗口统计真相来源） | Room `daily_browse` |
| history | 浏览历史 | Room `view_history` |
| scanSources | 扫描源 | Room `scan_sources` |
| timelineTags | 视频时间轴标签 | Room `timeline_tags` |
| cosWorks | COS 作品定义 | Room `cos_works` |
| likes | 点赞数据 | SharedPreferences `media_detail_prefs` |
| favorites | 收藏的文件 recordKey 列表 | SharedPreferences `media_detail_prefs` |
| followedAuthorIds | 关注作者 ID 列表 | SharedPreferences `author_follow_prefs` |
| appPrefs | App 偏好（主题/网格列数/自动同步/推荐权重） | `app_prefs.json` |

### mediaFiles

```json
"mediaFiles": [{
  "recordKey": "示例文件A.mp4",
  "fileName": "示例文件A.mp4",
  "displayName": "示例文件A",
  "extension": "mp4",
  "mediaType": "video",
  "uriString": "content://media/external/images/media/10001",
  "folderName": "示例文件夹",
  "pathHash": "a1b2c3d4",
  "sizeBytes": 52428800,
  "modifiedAtMillis": 1700000000000,
  "width": 1280,
  "height": 720,
  "durationMillis": 300000,
  "isDuplicateName": false,
  "isCosFile": false,
  "indexedAtMillis": 1700000000000,
  "sourceUri": "content://.../tree/primary%3A示例"
}]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 关联键（唯一）：普通文件为文件名；重名文件为「文件名 @ 文件夹名」（同文件夹内重名追加 `#哈希`） |
| fileName / displayName / extension | String | 文件名 / 显示名 / 扩展名 |
| mediaType | String | image / video / animated_image |
| uriString | String | 文件内容 URI（重装重扫后变化，恢复后旧 URI 不可用时重新扫描即可） |
| folderName | String | 所在文件夹名 |
| pathHash | String | 路径哈希（重名文件 key 的组成部分） |
| sizeBytes / modifiedAtMillis | Long | 大小 / 修改时间 |
| width / height / durationMillis | Int/Long? | 尺寸与时长，可为 null |
| isDuplicateName / isCosFile | Boolean | 是否重名文件 / 是否 COS 文件 |
| indexedAtMillis | Long | 入库时间戳 |
| sourceUri | String? | 归属扫描源 tree URI，可为 null |

### settings

```json
"settings": [{ "key": "示例设置键", "value": "示例值", "updatedAtMillis": 1700000000000 }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| key | String | 设置项键名（恢复时跳过设备本地键 `backup_directory_uri`/`backup_directory_name`） |
| value | String | 设置项值 |
| updatedAtMillis | Long | 更新时间戳 |

### authors

```json
"authors": [{ "authorId": "示例作者ID", "displayName": "示例作者名", "createdAtMillis": ..., "updatedAtMillis": ... }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| authorId | String | 作者唯一 ID（COS 作者以 `cos_` 前缀标识） |
| displayName | String | 作者显示名 |
| createdAtMillis / updatedAtMillis | Long | 创建/更新时间戳 |

### authorMediaRefs

```json
"authorMediaRefs": [{ "authorId": "示例作者ID", "recordKey": "示例文件A.mp4", "fileName": "示例文件A.mp4", "isMatched": true }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| authorId | String | 关联 authors.authorId |
| recordKey | String | 关联 mediaFiles.recordKey |
| fileName | String | 文件名 |
| isMatched | Boolean | 是否为自动匹配（TXT 导入命中） |

### tags

```json
"tags": [{ "tagId": 0, "name": "示例标签X", "createdAtMillis": 1700000000000 }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| tagId | Long | 标签 ID（恢复时忽略，按 name 去重） |
| name | String | 标签名（唯一，迁移的关键匹配字段） |
| createdAtMillis | Long | 创建时间戳 |

### mediaTagRefs

```json
"mediaTagRefs": [{ "recordKey": "示例文件A.mp4", "tagName": "示例标签X" }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 关联 mediaFiles.recordKey |
| tagName | String | 标签名（关联 tags.name，冗余写出便于 AI 直接理解） |

### albumRules

```json
"albumRules": [{ "sourceName": "示例出处A", "enabled": true, "createdAtMillis": ..., "updatedAtMillis": ... }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| sourceName | String | 出处名（唯一） |
| enabled | Boolean | 是否启用 |
| createdAtMillis / updatedAtMillis | Long | 创建/更新时间戳 |

### mediaStats

```json
"mediaStats": [{
  "recordKey": "示例文件A.mp4",
  "fileName": "示例文件A.mp4",
  "viewCount": 5,
  "playCount": 3,
  "totalBrowseSeconds": 120,
  "lastOpenedAtMillis": 1700000000000,
  "updatedAtMillis": 1700000000000
}]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 关联 mediaFiles.recordKey |
| fileName | String | 文件名 |
| viewCount / playCount | Int | 查看 / 播放次数 |
| totalBrowseSeconds | Long | 累计浏览时长（秒） |
| lastOpenedAtMillis | Long? | 最近打开时间戳，可为 null |
| updatedAtMillis | Long | 统计更新时间戳 |

### history

```json
"history": [{ "recordKey": "示例文件A.mp4", "fileName": "示例文件A.mp4", "mediaType": "video", "openedAtMillis": 1700000000000 }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 关联 mediaFiles.recordKey |
| fileName | String | 文件名 |
| mediaType | String | image / video |
| openedAtMillis | Long | 打开时间戳 |

### dailyBrowse（v1.14 新增）

按天浏览聚合段，每文件每天一行，**无限保留**（不做裁剪）。恢复后统计页的趋势图、窗口排行、沉浸时长与备份时完全一致。备份的是聚合数据而非浏览历史明细（明细 history 仅保留最近 500 条，与 `view_history` 上限一致）。

```json
"dailyBrowse": [{
  "recordKey": "示例文件A.mp4", "fileName": "示例文件A.mp4", "mediaType": "video",
  "dayStartMillis": 1700000000000, "viewCount": 3, "playCount": 1,
  "totalBrowseSeconds": 120, "updatedAtMillis": 1700000100000
}]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 关联 mediaFiles.recordKey |
| fileName | String | 文件名 |
| mediaType | String | image / video / animated_image |
| dayStartMillis | Long | 当天零点时间戳（天粒度分桶键） |
| viewCount | Int | 当天浏览次数 |
| playCount | Int | 当天播放次数 |
| totalBrowseSeconds | Long | 当天累计浏览时长（秒） |
| updatedAtMillis | Long | 当天最后更新时间戳 |

### scanSources

```json
"scanSources": [{ "uriString": "content://...", "displayName": "示例扫描目录A", "isBackupDirectory": false, "isCosDirectory": false, "addedAtMillis": ..., "lastScannedAtMillis": ... }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| uriString | String | SAF 目录 URI（恢复后由系统按用户授权重新授权） |
| displayName | String | 显示名 |
| isBackupDirectory / isCosDirectory | Boolean | 备份目录标志 / COS 扫描目录标志 |
| addedAtMillis | Long | 添加时间戳 |
| lastScannedAtMillis | Long? | 最近扫描时间戳，可为 null |

### timelineTags

```json
"timelineTags": [{ "recordKey": "示例文件A.mp4", "fileName": "示例文件A.mp4", "timeMillis": 30000, "name": "示例时间轴标签X", "createdAtMillis": ... }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 视频文件完整名 |
| fileName | String | 文件名 |
| timeMillis | Long | 视频内时间点（毫秒） |
| name | String | 标签名 |
| createdAtMillis | Long | 创建时间戳 |

### cosWorks

```json
"cosWorks": [{ "authorName": "示例COS作者", "workName": "示例作品名", "folderUri": "content://...", "fileCount": 10, "indexedAtMillis": ..., "sourceUri": "content://..." }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| authorName / workName | String | 作者名 / 作品名 |
| folderUri | String | 作品文件夹路径 |
| fileCount | Int | 文件数 |
| indexedAtMillis | Long | 入库时间戳 |
| sourceUri | String | 归属 COS 扫描源 tree URI |

### likes

```json
"likes": [{ "recordKey": "示例文件A.mp4", "likeCount": 3, "lastLikeDate": "2026-01-15" }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 关联 mediaFiles.recordKey |
| likeCount | Int | 累计点赞次数 |
| lastLikeDate | String? | 最近点赞日期（yyyy-MM-dd），可为 null |

### favorites

```json
"favorites": ["示例文件A.mp4", "示例图片B.jpg"]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| (数组元素) | String | 收藏的文件 recordKey |

### followedAuthorIds

```json
"followedAuthorIds": ["cos_示例COS作者"]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| (数组元素) | String | 已关注的作者 ID（关联 authors.authorId） |

### appPrefs

```json
"appPrefs": {
  "version": 1,
  "custom_album_sources": ["ACC", "XXX"],
  "theme_mode": "system",
  "grid_columns_home": 2,
  "grid_columns_all": 3,
  "grid_columns_album": 3,
  "auto_sync": true,
  "recommendation_prefs": {
    "tagRelevance": 0.22, "tagCollection": 0.15, "engagement": 0.10,
    "recency": 0.15, "likeScore": 0.05, "discovery": 0.20,
    "freshness": 0.05, "browseDepth": 0.03, "maxRandom": 0.30
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| version | Int | app_prefs.json 内部版本 |
| custom_album_sources | String[] | 用户自定义出处名 |
| theme_mode | String | system / light / dark |
| grid_columns_home / all / album | Int | 各页面网格列数 |
| auto_sync | Boolean | 自动备份开关 |
| recommendation_prefs | Object | 推荐算法 9 维权重（默认值见示例） |

## 5. 部分导入指南

按场景只取需要的部分：

| 我只需要 | 读取哪些部分 | 怎么用 |
|---|---|---|
| 媒体文件清单 | mediaFiles | recordKey + fileName + mediaType + folderName，重建文件列表 |
| 标签体系 | tags + mediaTagRefs | 按 name 创建标签，按 recordKey+tagName 建立关联 |
| 作者信息 | authors + authorMediaRefs + followedAuthorIds | 按 authorId 创建作者，authorMediaRefs 建关联，followedAuthorIds 标记关注 |
| 收藏状态 | favorites | recordKey 列表，找到文件就标记收藏 |
| 点赞数据 | likes | recordKey + likeCount，写入点赞计数 |
| 浏览统计 | mediaStats + dailyBrowse | mediaStats 各计数字段；dailyBrowse 按天聚合（每文件每天次数/播放/时长，恢复窗口统计） |
| 浏览历史 | history | recordKey + openedAtMillis，按时间倒序还原 |
| COS 作品结构 | cosWorks + mediaFiles（isCosFile=true） | 重建作品层级 |
| 视频时间轴标签 | timelineTags | recordKey + timeMillis + name |
| 推荐权重 | appPrefs.recommendation_prefs | 9 维权重直接写入 |

## 6. 关联键说明

- **recordKey**：文件名含扩展名，是几乎所有数据段的关联键（mediaFiles/settings 外的全部段落都引用它）
- **tagName**：关联 tags 和 mediaTagRefs
- **authorId**：关联 authors / authorMediaRefs / followedAuthorIds
- COS 作者 authorId 以 `cos_` 前缀标识
- recordKey 生成规则：普通文件 = 文件名；重名文件 = 「文件名 @ 文件夹名」（同文件夹内重名追加 `#路径哈希`）。前两类在重装重扫后稳定不变；仅带路径哈希的 key 在重装后可能变化（哈希基于 SAF 授权路径），此类历史数据无法自动匹配回文件，但数据仍完整保留在备份中，可人工/AI 按 fileName 关联。

## 7. 恢复流程（写回绮梦影库）

备份 JSON 双向可用：既能读出来做分析（其他 App），也能写回绮梦影库恢复数据。本节说明绮梦影库的恢复机制。

### 恢复触发方式

1. **选择备份目录后检测**：用户在"数据备份 → 选择文件夹"选定目录后，App 检测 `qimeng_backup.json` 是否存在，检测到备份数据时弹窗提示"检测到备份数据：X 个媒体文件 / Y 位作者 / Z 个标签 / W 条统计，是否导入恢复？"，用户确认后调用 `BackupManager.importFromDirectory` 恢复。
   - 同一目录只提示一次（用设备本地 SharedPreferences 记录，卸载重装后清空，重新选择会再提示）
2. **数据备份页主动导入**：数据备份页顶部"选择导入目录并恢复"按钮——弹文件夹选择器选备份目录后检测到备份数据即弹窗确认，确认后调 `importFromDirectory` 恢复，不改变已设置的备份位置。另有空库提示区"导入恢复"按钮（从已设置备份位置恢复）。
3. **空库覆盖防护**：本地数据库 `media_files` 表为空时，自动同步和手动同步都会被拦截，暂停写入备份目录，并在数据备份页显示"本地数据为空，已暂停自动同步，建议先导入恢复"提示，避免空数据库反向覆盖旧备份。

### 导入语义

`importFromDirectory` 从 `qimeng_backup.json` 读取全部数据段并在**单一数据库事务**内写入：

| 数据段 | 导入方式 | 冲突处理 |
|---|---|---|
| mediaFiles | **仅本地空库时导入全量**（本地已扫描时跳过，保留新扫描的有效 URI） | 本地有数据则跳过 |
| settings | upsert | 跳过设备本地键（backup_directory_uri/name） |
| tags | 按 name INSERT OR IGNORE | 标签定义按 name 合并，不覆盖现有 |
| mediaTagRefs | 按 tagName 查本地真实 tagId 建关联 | recordKey 本地不存在则跳过（防孤儿关联） |
| authors | upsert 作者 | 重复 authorId 覆盖 |
| authorMediaRefs | upsert 关联 | recordKey 本地不存在则跳过 |
| albumRules | upsert 规则 | 按 sourceName 合并 |
| mediaStats | upsert 统计 | recordKey 本地不存在则跳过，数值钳制非负 |
| dailyBrowse | upsert 按天聚合 | recordKey 本地不存在则跳过，数值钳制非负（v1.14 新增） |
| history | upsert 历史 | recordKey 本地不存在则跳过 |
| scanSources | upsert 扫描源 | 按 uriString 合并 |
| timelineTags | upsert，按 (recordKey,timeMillis,name) 查重 | 已存在则跳过 |
| cosWorks | upsert | 按 (sourceUri,authorName,workName) 唯一索引合并 |
| likes | 写 SharedPreferences | 按 recordKey 并集合并（本地 ∪ 备份），只导入备份中存在的新键，本地独有键保留（v1.15 语义） |
| favorites | 写 SharedPreferences | 本地 ∪ 备份合并 |
| followedAuthorIds | 写 SharedPreferences | 整体覆盖为备份值 |
| appPrefs | 写 app_prefs.json | 整体覆盖 |

**跨引用安全**：authorMediaRefs/mediaTagRefs/mediaStats/history/timelineTags 的 recordKey 仅当本地 `media_files` 中存在时才建立关联，不存在的 key 静默跳过，无法注入无效关联。

**合并策略关键点**：media_files 进备份 + 本地空库时导入 → 恢复后浏览历史/统计/标签**立即可见**，无需重新扫描；非重名与「文件名 @ 文件夹名」两类 recordKey 重扫后稳定不变，不会被扫描清理误删。

### 给其他 App 开发者的提示

1. **读取侧**：照本规范的字段定义读取 JSON，recordKey 是关联键
2. **写入侧（恢复到绮梦影库）**：复用上述导入语义——按 recordKey/authorId/name 合并，不删除现有数据
3. **空库防护**：若新 App 也有"自动同步写入备份"功能，建议在本地数据库为空时暂停写入，避免空数据覆盖旧备份
4. **schemaVersion 兼容**：导入时按 schemaVersion 分支处理，缺失字段用默认值，未知字段忽略；**v1.15 起备份的 schemaVersion 大于当前支持版本（1）时拒绝导入并记日志**（防未来版本文件被静默错解析），等于或小于则正常导入

最后更新时间：2026-08-10（v1.15：schemaVersion 导入校验说明；v2.0 单文件全量重构：media_files 进备份，合并旧 10 分文件 + personal_prefs + 报告 txt 为单一 qimeng_backup.json）
