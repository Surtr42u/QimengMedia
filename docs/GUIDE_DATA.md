# GUIDE_DATA - 数据层

## 实现路径

目标源码路径：`app/src/main/java/com/qimeng/media/data/`

偏好管理路径：`app/src/main/java/com/qimeng/media/data/prefs/AppPrefsManager.kt`

## 职责

管理 Room 数据库、实体、DAO、Repository、ViewModel 数据流和应用内记录。

不管：UI 布局和交互（见 GUIDE_UI.md）、扫描流程（见 GUIDE_SCAN.md）、备份读写流程（见 GUIDE_BACKUP.md）

## 主键策略

App 记录默认使用完整文件名含扩展名作为主要关联键。

示例：

- `1.jpg` 与 `1.mp4` 是不同记录。
- `崩坏 星穹铁道 卡芙卡 1 (1).mp4` 是完整记录键。

重复完整文件名时启用高级区分：

- `1.mp4 @ Videos`
- `1.mp4 @ Download`

如果父文件夹名也重复，内部追加短路径哈希。

## 目标实体

- `MediaFileEntity`：媒体索引。
- `ViewStatsEntity`：播放/查看次数和浏览时长。
- `ViewHistoryEntity`：浏览历史，最多 500 条。
- `TagEntity`：全局标签。
- `MediaTagCrossRef`：文件与标签关联。
- `TimelineTagEntity`：视频时间轴标签（recordKey + timeMillis + name，按视频内时间点标记，DAO 已有 observeForVideo/upsert/deleteById）。**与文件标签完全独立**：不同实体、不同表、不同 DAO、不同备份 JSON（`tags.json` vs `timeline_tags.json`），无外键或数据交叉；文件标签是整个文件级别，时间轴标签是视频内某个时间点级别。
- `AlbumRuleEntity`：作品出处识别规则。
- `AuthorEntity`：作者。
- `AuthorMediaCrossRef`：作者与文件关联。
- `AuthorFileCount`：作者文件计数（authorId + fileCount，DAO 查询结果封装，非数据库表，无 @Entity 注解，是 data class）。
- `SettingEntity`：设置项（运行数据）。
- `CosWorkEntity`：COS 作者/作品层级关系。
- `AppPrefs`：用户偏好（独立 JSON 文件，`AppPrefsManager` 管理）。

## 数据库索引

| 表名 | 索引列 | 说明 |
|------|--------|------|
| `media_files` | `fileName` | 按文件名查询 |
| `media_files` | `mediaType` | 按类型查询（`getByType`） |
| `media_files` | `modifiedAtMillis` | 按修改时间排序 |
| `media_files` | `folderName` | 按文件夹分组 |
| `media_files` | `isCosFile` | COS/常规过滤（`observeNonCosMedia`） |
| `scan_sources` | `isCosDirectory` | COS 扫描源过滤 |
| `cos_works` | `authorName` | 按作者查询 |
| `cos_works` | `workName` | 按作品查询 |
| `cos_works` | `authorName, workName`（唯一） | 作者+作品联合唯一约束 |
| `cos_works` | `folderUri` | 按目录 URI 查找（孤立文件检测） |
| `authors` | `displayName` | 按显示名查询 |
| `album_rules` | `sourceName`（唯一） | 出处名唯一约束 |
| `tags` | `name`（唯一） | 标签名唯一约束 |
| `view_stats` | `fileName` | 按文件名查询统计 |
| `view_stats` | `lastOpenedAtMillis` | 按最近浏览时间排序 |
| `view_history` | `openedAtMillis` | 按浏览时间排序 |
| `view_history` | `mediaType` | 按类型过滤 |
| `timeline_tags` | `recordKey` | 按视频查询时间轴标签 |
| `timeline_tags` | `fileName` | 按文件名查询时间轴标签 |

新增索引需同步更新此表，并注意 Room schema 变更可能需要数据库迁移。

## 统计规则

- 图片进入详情页后查看次数 +1，同一次打开只计一次。
- 视频进入详情页后点击播放按钮播放次数 +1，同一次打开只计一次。
- 浏览时长记录详情页停留秒数。
- 历史记录同一文件只保留最新一条，上限 500 条。

## 标签数据规则

- 标签是 App 内记录，不写入原始文件。
- 一个标签可以关联多个文件，一个文件可以关联多个标签。
- 标签双向同步：浏览文件中添加的新标签自动同步到筛选面板的可选标签列表。
- 详情页标签管理：点击关闭图标仅移除文件与标签的关联（`removeTagFromMedia`），不删除标签选项本身。
- 筛选面板标签管理：长按标签可删除标签选项本身（`deleteTagById`），`MediaTagCrossRef` 外键 CASCADE 自动清理关联。
- 筛选面板支持"+ 添加标签"按钮创建不关联文件的新标签（`createTag`）。
- 标签数据必须支持 JSON 导出和导入。

## 作者数据规则

- 作者是 App 内记录，不写入原始文件。
- 一个作者可以关联多个图片或视频。
- 一个文件可以关联多个作者。
- 作者数据必须支持 JSON 导出和导入。

## 收藏

- 收藏状态使用 SharedPreferences 持久化，key 为 `favorite_record_keys`（StringSet）。
- 不在 Room 中建表，轻量级布尔标记。

## 作者关注

- 关注状态使用 SharedPreferences 持久化，独立文件 `author_follow_prefs`，key 为 `followed_authors`（StringSet，存储 authorId 集合）。
- 与收藏设计一致：轻量级布尔标记，不在 Room 中建表。
- ViewModel 方法：`toggleAuthorFollow(authorId)` 切换关注、`isAuthorFollowed(authorId)` 查询、`followedAuthorIds()` 获取全部已关注 ID。
- 详情页快速转跳弹窗中每个作者行右侧有关注按钮，作者管理页"特别关注"排序筛选已关注作者。

## 点赞

- 点赞使用 SharedPreferences 持久化，与收藏共用 `media_detail_prefs`。
- `like_date_<recordKey>`：记录最近一次点赞日期（yyyy-MM-dd），每日可点赞一次，次日重置为可点赞状态。
- `like_count_<recordKey>`：累计点赞次数（int），用于推荐算法和排行榜排序。
- 详情页下方 tab 点赞按钮：未点赞显示空心图标，今日已点赞显示填满图标。
- 排行榜排序逻辑：浏览次数(viewCount + playCount) + 点赞次数(likeCount)。
- 点赞计数缓存：`readLikeCounts()` 使用 `cachedLikeCounts` + `likeCountsDirty` 标记缓存，通过 `OnSharedPreferenceChangeListener` 监听点赞变化自动失效缓存，避免每次 render 遍历全部 SharedPreferences。

## 推荐算法

推荐引擎位于 `MediaBrowserLogic.kt` 的 `recommend()` 方法，采用 10 维加权评分模型。完整算法文档见 `GUIDE_ALGORITHM.md`。

## 备份 JSON 格式

备份数据以 JSON 写入用户指定的 SAF 目录，目录名为 `LocalMediaApp_Data/`。

### JSON 文件清单（共 10 个，当前实现 10 个）

| 文件 | 状态 | 对应实体 |
|---|---|---|
| `settings.json` | 已实现 | `SettingEntity` |
| `authors.json` | 已实现 | `AuthorEntity` + `AuthorMediaCrossRef` |
| `tags.json` | 已实现 | `TagEntity` + `MediaTagCrossRef` |
| `album_rules.json` | 已实现 | `AlbumRuleEntity` |
| `media_stats.json` | 已实现 | `ViewStatsEntity` |
| `history.json` | 已实现 | `ViewHistoryEntity` |
| `scan_sources.json` | 已实现 | `ScanSourceEntity` |
| `likes.json` | 已实现 | SharedPreferences 点赞+收藏数据 |
| `recommendation_prefs.json` | 已实现 | `RecommendationPrefs`（AppPrefs 内嵌，9 维推荐权重） |
| `timeline_tags.json` | 已实现 | `TimelineTagEntity` |

注意：`personal_prefs.json` 不在此列表中，它属于个人偏好导出（写入 `个人偏好/` 目录），详见下方"个人偏好数据导出"章节。

### authors.json 格式示例

```json
{
  "authors": [
    {
      "authorId": "kamihikoki_mmd",
      "displayName": "紙飛行機",
      "files": [
        "崩坏 星穹铁道 卡芙卡 1 (1).mp4",
        "崩坏 星穹铁道 卡芙卡 1 (2).mp4"
      ]
    }
  ]
}
```

## App 偏好持久化

App 偏好使用独立 JSON 文件存储，与 Room 数据库分离，方便迁移到新项目。

### 存储位置

`AppPrefsManager` 管理内部存储的 `app_prefs.json` 文件，路径为 `{filesDir}/app_prefs.json`。

### 数据结构

| 字段 | 类型 | 说明 |
|---|---|---|
| `custom_album_sources` | `Set<String>` | 用户自定义出处名（长按相册卡片添加） |
| `theme_mode` | `String` | 主题模式：system/light/dark |
| `grid_columns_home` | `Int` | 首页网格列数 |
| `grid_columns_all` | `Int` | 全部页网格列数 |
| `grid_columns_album` | `Int` | 相册详情页网格列数 |
| `auto_sync` | `Boolean` | 自动同步开关（数据变化自动写入备份目录） |
| `recommendation_prefs` | `RecommendationPrefs` | 推荐算法权重偏好（9 维，嵌套对象） |

### RecommendationPrefs 数据结构

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `tagRelevance` | `Float` | 0.22 | 标签相关性权重 |
| `tagCollection` | `Float` | 0.15 | 标签合集权重 |
| `engagement` | `Float` | 0.10 | 互动热度权重 |
| `recency` | `Float` | 0.15 | 浏览时效权重 |
| `likeScore` | `Float` | 0.05 | 点赞偏好权重 |
| `discovery` | `Float` | 0.20 | 新发现权重 |
| `freshness` | `Float` | 0.05 | 新鲜度权重 |
| `browseDepth` | `Float` | 0.03 | 浏览深度权重 |
| `maxRandom` | `Float` | 0.30 | 随机性上限 |

用户可通过"我的→推荐偏好"调整各维度权重，`MediaBrowserLogic.recommend()` 接收可选 `customPrefs` 参数覆盖默认权重；自适应权重逻辑（标签/点赞/历史为空时重新分配）仍然生效，customPrefs 仅作为初始值。

### 推荐偏好预设

弹窗上方提供 4 个预设方案，点击整行应用预设并同步更新所有 SeekBar：

| 预设名 | 描述 | tagRel | tagColl | engage | recency | like | discov | fresh | depth | random |
|--------|------|--------|---------|--------|---------|------|--------|-------|-------|--------|
| 均衡推荐 | 标签+时效+发现均衡搭配，适合日常浏览 | 0.22 | 0.15 | 0.10 | 0.15 | 0.05 | 0.20 | 0.05 | 0.03 | 0.30 |
| 高记忆流行 | 强化浏览时效和互动热度，重温常看内容 | 0.15 | 0.10 | 0.20 | 0.25 | 0.10 | 0.05 | 0.05 | 0.05 | 0.10 |
| 深度探索 | 强化标签相关性和新发现，挖掘冷门内容 | 0.30 | 0.20 | 0.05 | 0.05 | 0.02 | 0.30 | 0.02 | 0.05 | 0.40 |
| 新鲜优先 | 强化新鲜度和发现，优先展示最新入库内容 | 0.10 | 0.05 | 0.05 | 0.10 | 0.02 | 0.25 | 0.20 | 0.03 | 0.35 |

预设定义位于 `ProfileFragment.recPrefPresets`，新增预设需同步更新此表和代码。

### JSON 格式示例

```json
{
  "version": 1,
  "custom_album_sources": ["ACC", "XXX"],
  "theme_mode": "system",
  "grid_columns_home": 2,
  "grid_columns_all": 3,
  "grid_columns_album": 3,
  "auto_sync": false
}
```

### 设计原则

- **独立于 Room**：偏好数据不存入数据库，直接读写 JSON 文件，可被其他应用/项目直接使用
- **响应式**：`AppPrefsManager.prefs` 是 `StateFlow<AppPrefs>`，UI 层可直接观察变化
- **可迁移**：`exportToJson()` / `importFromJson()` 支持导出导入，方便数据迁移
- **自动学习**：用户手动添加的自定义出处名自动加入 `SourceMatcher` 识别算法

### 与 Room SettingEntity 的分工

| 数据类型 | 存储方式 | 示例 |
|---|---|---|
| 用户偏好 | `AppPrefsManager`（JSON 文件） | 自定义出处、主题模式、列数偏好 |
| 运行数据 | Room `SettingEntity` | 备份目录 Uri、已导入 TXT 文件列表 |
| 内容数据 | Room 各实体表 | 媒体文件、统计、标签、作者、历史 |

## 个人偏好数据导出

通过 ProfileFragment 数据管理弹层"导出偏好数据"触发，导出为 2 个文件（COS 和常规数据合并）：

| 文件 | 格式 | 用途 |
|---|---|---|
| `personal_prefs.json` | JSON | 完整数据备份，COS+常规统一 |
| `personal_prefs_report.txt` | 纯中文文本 | 给用户自己看，人类可读的偏好报告 |

### JSON 文件（给程序用）

`data` 区域包含 8 个部分：

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

### viewStats 新增字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `mediaType` | String | "image" 或 "video"，区分图片/视频 |
| `isCosFile` | Boolean | 是否 COS 文件 |

### cosWorks 完整字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `authorName` | String | 作者名 |
| `workName` | String | 作品名 |
| `folderUri` | String | 目录 URI |
| `fileCount` | Int | 文件数 |
| `viewCount` | Int | 查看次数之和 |
| `playCount` | Int | 播放次数之和 |
| `totalBrowseSeconds` | Long | 浏览时长之和（秒） |
| `likeCount` | Int | 点赞次数之和 |
| `favoriteCount` | Int | 收藏文件数 |
| `tags` | Object | 标签名→出现次数 |
| `files` | String[] | 该作品所有文件 recordKey |

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

`app/src/main/assets/personal_prefs_example.json` 包含虚构占位数据，展示所有字段和结构。

### 数据迁移规范

完整的格式规范和迁移教程见 `docs/DATA_MIGRATION_SPEC.md`。

## 修改注意事项

- 新增表或字段必须设计 Room migration。
- 新增数据库字段时必须同步更新上方的 JSON 格式和 `docs/GUIDE_BACKUP.md` 的导入导出流程。
- 修改个人偏好导出逻辑需同步 `docs/GUIDE_BACKUP.md` 和 `docs/DATA_MIGRATION_SPEC.md`。
- `personal_prefs.json` 写入 `个人偏好/` 目录，不在 `app数据/` 目录中。
- 新增实体时必须同步更新实体列表和 JSON 文件清单。
- 不允许把绝对路径作为唯一迁移依据。
- 允许保存只读 Uri、父文件夹名和路径哈希用于扫描定位与重复区分。
- 重建索引（`rebuildMediaIndex()`）只清空并重建 `media_files` 表，不清空统计、历史、标签、作者和设置表。
- 备份保存位置属于 App 偏好，使用 `settings` 表保存目录 Uri 和显示名。
- 扫描源使用 `ScanSourceEntity` 保存多个 SAF 目录；重新扫描时合并所有非备份扫描源后重建 `media_files` 索引。
- 删除扫描源时，自动清理不属于任何剩余扫描源的媒体文件（通过 URI 前缀匹配判断归属），同时级联删除对应的 `author_media_cross_refs`、`media_tag_cross_refs`，并清理无关联文件的孤立作者。
- **启动时孤立文件清理**：`ScanUseCase.cleanupOrphanFiles()` 在 ViewModel 初始化时自动执行，检测并删除没有对应扫描源的残留媒体文件（包括非COS和COS），修复历史遗留的孤立数据问题。
- `deleteMediaAndRefs(recordKeys)` 在事务中依次删除作者关联、标签关联、媒体文件，保证数据一致性。
- `deleteOrphanAuthors()` 删除 `author_media_cross_refs` 中无记录的作者，防止作者列表出现空壳条目。
- 轻量查询方法：`MediaFileDao.getNonCosKeysAndFileNames()` 返回 `NonCosKeyFileName(recordKey, fileName)`，用于作者导入匹配（`importAuthorsFromText`），避免全量加载 `MediaFileEntity`；Repository 接口和实现均已提供对应方法 `getNonCosKeysAndFileNames()`。
- 轻量查询方法：`MediaFileDao.getCosKeysAndUris()` 返回 `KeyAndUri(recordKey, uriString)`，用于 `cleanupOrphanFiles()` 中检测 COS 孤立文件，避免全量加载 `MediaFileEntity`；Repository 接口和实现均已提供对应方法 `getCosKeysAndUris()`。
- `MediaFileDao.observeNonCosMedia()` 直接返回 `WHERE isCosFile = 0` 的 Flow，替代各 Fragment 中 `observeAllMedia().filter{!isCosFile}` 的全量加载后 Kotlin 过滤模式。
- `MediaFileDao.getByType(mediaType)` 按类型查询，替代 `getAllMedia().filter{type}` 全表过滤。
- `ViewStatsDao.getByRecordKeys(recordKeys)` 批量查询，替代 `getAllByFileName().filter{in recordKeys}`。
- `TagDao.getCrossRefsByRecordKeys(recordKeys)` 批量查询，替代 `getAllCrossRefs().filter{in recordKeys}`。
- `AuthorDao.deleteAuthorsByIds(authorIds)` 批量删除，替代循环逐个 `deleteAuthor()`。
- `isPregenerating` 使用 `AtomicBoolean` 替代 `Boolean`，确保缩略图预生成的防重入检查线程安全。
- `importAuthorsFromText` 使用轻量查询 `getNonCosKeysAndFileNames()` + `findMatchingMediaLight()` 替代 `getAllMedia().filter { !it.isCosFile }`，避免加载全量 `MediaFileEntity`。
- TXT 导入时解析后的结构化数据（AuthorBlock 列表）序列化为 JSON 保存到 settings 表（key 为 `imported_txt_blocks_<fileName>`），用于扫描新文件后重新匹配（`rematchAllTxtImports`）；同时保存 TXT 文件 URI（key 为 `imported_txt_uri_<fileName>`），用于刷新时重新读取文件。
- `rematchAllTxtImports` 在常规扫描（scanDirectory/refreshScanSource）完成后自动调用，将已导入 TXT 的作品名与当前库中非 COS 文件重新匹配，确保新文件也能被关联到已有作者。
- `rematchSingleTxtImport` 对单个 TXT 文件重新匹配，用于刷新按钮的降级方案（URI 不可访问时用已保存 blocks 重新匹配）。
- `refreshTxtImport` 刷新逻辑：先尝试从保存的 URI 重新读取文件内容，成功则重新导入；失败则降级调用 `rematchSingleTxtImport`。
- `removeImportedTxtBlocks` 在删除 TXT 导入时清理对应的 blocks 数据。
- `removeImportedTxtUri` 在删除 TXT 导入时清理对应的 URI 数据。
- `deleteSetting(key)` 用于按 key 删除 settings 表中的单条记录，支持 blocks 和 URI 数据清理。

## UseCase 层

`MediaLibraryViewModel` 按职责拆分为 4 个 UseCase，文件位于 `app/src/main/java/com/qimeng/media/domain/`：

| UseCase | 职责 | 主要方法 |
|---|---|---|
| `ScanUseCase` | 扫描调度 | `scanDirectory`、`refreshScanSource`、`scanCosDirectory`、`refreshCosSource`、`autoRefreshAllSources`（延迟3秒启动，避免Room Flow触发UI闪烁）、`deleteScanSource`、`deleteCosScanSource`、`cleanupOrphanFiles`、`generateAuthorId` |
| `ThumbnailUseCase` | 缩略图预生成 | `pregenerateThumbnails`、`progress`（StateFlow：Idle/Running/Done，Running/Done 携带 `ThumbnailSource` 标识常规/COS来源）；队列机制：新请求排队等待当前任务完成后再执行，不会跳过（COS文件等也能被预生成） |
| `AuthorImportUseCase` | 作者导入 | `importAuthorsFromText`、`findMatchingMediaLight`、`rematchAllTxtImports`、`rematchSingleTxtImport`、`saveTxtUri`、`loadTxtUri`、`removeImportedTxtBlocks`、`removeImportedTxtUri` |
| `AutoSyncUseCase` | 自动同步 | `triggerAutoSyncIfNeeded`（扫描后，只写app数据/）、`triggerAutoSyncForDetailExit`（退出详情页，只写app数据/）、`triggerFullSync`（App后台，全量同步）、`triggerManualSync`（手动同步，全量，无视防抖） |

- ViewModel 通过 `AppContainer` 获取 UseCase 实例，委托调用
- ViewModel 保留：数据观察（Flow 属性）、ScanStatus 状态、统计记录、设置管理、标签管理、作者管理
- UseCase 返回 `ScanResult`（Success/Error），ViewModel 负责转换为 `ScanStatus` 更新 UI
- ViewModel 公共 API 不变，Fragment 无需修改

## COS 数据规则

- `CosWorkEntity` 记录 COS 作者/作品层级关系，字段：authorName、workName、folderUri、fileCount、indexedAtMillis
- `MediaFileEntity.isCosFile` 标记 COS 文件，COS 文件不出现在首页/全部/相册/收藏/浏览历史中
- `ScanSourceEntity.isCosDirectory` 标记 COS 扫描目录
- COS 作者关联使用 `AuthorMediaCrossRef`（扫描时直接记录 author→files 映射创建 crossRefs，不使用 URI 前缀匹配）
- COS 扫描流程和路径解析算法详见 `GUIDE_SCAN.md`
- COS 目录结构（三种文件夹格式）详见 `GUIDE_AUTHOR.md`
- 重建 COS 索引（`rebuildCosIndex()`）：清空旧 COS 文件的 `AuthorMediaCrossRef` + 清理孤立作者 + 清空 COS 媒体文件和 cos_works 表，不影响非 COS 数据
- 重建常规索引（`rebuildMediaIndex()`）：只清空非 COS 媒体文件，不影响 COS 数据
- 删除 COS 扫描源时，级联删除关联的 CosWorkEntity 和 COS 媒体文件，清理孤立作者
- COS 作者与常规作者隔离：COS 作者 authorId 以 `cos_` 前缀标识（如 `cos_rioko凉凉子`），与常规作者完全隔离，同名不会混淆
- authorId 生成规则：保留中文/日文等 Unicode 字符，只去除特殊符号，空格转下划线；示例：`rioko凉凉子` → `rioko凉凉子`，`水淼Aqua` → `水淼aqua`；COS 作者加 `cos_` 前缀；清洗后为空时用哈希值兜底：`author_abc12345`
- 孤立作者清理：删除扫描源时自动清理失去所有关联的作者（常规用 `deleteOrphanAuthors()`，COS 用 `deleteOrphanCosAuthors()`），确保作者列表不出现空壳条目
- 删除操作必须明确业务含义，不能误删媒体索引外的数据
- Migration 必须兼容 JSON 导入导出格式（见 GUIDE_BACKUP.md）
- 数据库版本升级必须同步项目文档（见 PROJECT_GUIDE.md）
- Repository 负责触发备份自动同步（见 GUIDE_BACKUP.md）
- Repository 负责把扫描结果合并进媒体索引（见 GUIDE_SCAN.md）
- Repository 负责历史上限、统计规则、作者关联规则
- 媒体列表查询避免一次加载无关大字段

> 最后更新：2026-06-13
