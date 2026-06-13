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
| **热度分** | 用于排行排序的综合评分，图片和视频计算公式不同 |
| **偏好度** | 作者的综合偏好评分，基于关联文件的浏览/点赞/收藏数据 |

## 1. 核心原则

1. **AI 只写代码，不看内容**：按字段名和类型读取 JSON，不需要理解文件名的含义
2. **文件名是关联键**：所有数据通过 `recordKey`（完整文件名含扩展名）关联
3. **格式稳定**：`schemaVersion` 标识格式版本，新增字段不影响旧代码
4. **隐私声明**：此文件包含用户个人行为数据，只按格式规范机械处理，禁止阅读和分析具体内容

## 2. 目录结构

导出目录 `绮梦影库/` 包含三个子目录：

```
绮梦影库/
├── 格式说明/                              ← 规范 + 示例（AI 读取此目录理解格式）
│   ├── FORMAT_SPEC.md                     ← 本文件
│   ├── personal_prefs_example.json        ← 个人偏好示例
│   └── app_data_examples/                 ← App 数据示例
│       ├── settings_example.json
│       ├── authors_example.json
│       ├── tags_example.json
│       ├── album_rules_example.json
│       ├── media_stats_example.json
│       ├── history_example.json
│       ├── scan_sources_example.json
│       ├── likes_example.json
│       ├── recommendation_prefs_example.json
│       └── timeline_tags_example.json
├── app数据/                               ← App 数据（AI 写代码处理，不读内容）
│   ├── settings.json
│   ├── authors.json
│   ├── tags.json
│   ├── album_rules.json
│   ├── media_stats.json
│   ├── history.json
│   ├── scan_sources.json
│   ├── likes.json
│   ├── recommendation_prefs.json
│   └── timeline_tags.json
└── 个人偏好/                              ← 个人数据（AI 写代码处理，不读内容）
    ├── personal_prefs.json
    └── personal_prefs_report.txt
```

**使用方式**：
- AI 读取 `格式说明/` 目录理解格式
- AI 写代码处理 `app数据/` 和 `个人偏好/` 中的真实数据，不读内容

## 3. JSON 文件结构

```json
{
  "schemaVersion": 2,
  "exportedAtMillis": 1700000000000,
  "appIdentifier": "com.qimeng.media",
  "data": { ... }
}
```

> schemaVersion 变更记录：
> - **1** → **2**：viewStats 新增 `mediaType`/`isCosFile`；新增 `cosWorks`/`followedAuthorIds` 部分；authors 新增 `viewCount`/`followed`/`tags`

## 4. data 区域 — 8 个部分

每个部分列出：字段名、类型、说明、关联关系

| 部分 | 说明 |
|---|---|
| favorites | 收藏的文件 recordKey 列表 |
| likes | 点赞次数和日期 |
| tags | 标签定义 |
| mediaTags | 文件与标签的关联 |
| viewStats | 观看/播放次数和时长（含 mediaType 和 isCosFile） |
| authors | 作者及关联文件（含 viewCount、followed、tags） |
| followedAuthorIds | 关注的作者 ID 列表 |
| cosWorks | COS 作品数据（含统计、标签、文件列表） |

### favorites

```json
"favorites": ["示例文件A.mp4", "示例图片B.jpg"]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| (数组元素) | String | 文件的 recordKey |

### likes

```json
"likes": [{ "recordKey": "示例文件A.mp4", "likeCount": 3, "lastLikeDate": "2026-01-15" }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 文件完整名 |
| likeCount | Int | 累计点赞次数 |
| lastLikeDate | String? | 最近点赞日期（yyyy-MM-dd），可为 null |

### tags

```json
"tags": [{ "tagId": 1, "name": "示例标签X", "createdAtMillis": 1700000000000 }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| tagId | Long | 标签 ID（迁移时可不保留，按 name 去重） |
| name | String | 标签名（唯一，迁移的关键匹配字段） |
| createdAtMillis | Long | 创建时间戳 |

### mediaTags

```json
"mediaTags": [{ "recordKey": "示例文件A.mp4", "fileName": "示例文件A.mp4", "tagName": "示例标签X" }]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 文件完整名 |
| fileName | String | 文件名（通常与 recordKey 相同） |
| tagName | String | 标签名（关联 tags.name） |

### viewStats

```json
"viewStats": [{
  "recordKey": "示例文件A.mp4",
  "fileName": "示例文件A.mp4",
  "mediaType": "video",
  "isCosFile": false,
  "viewCount": 5,
  "playCount": 3,
  "totalBrowseSeconds": 120,
  "lastOpenedAtMillis": 1700000000000,
  "updatedAtMillis": 1700000000000
}]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 文件完整名 |
| fileName | String | 文件名 |
| mediaType | String | "image" 或 "video" |
| isCosFile | Boolean | 是否 COS 文件 |
| viewCount | Int | 查看次数 |
| playCount | Int | 播放次数 |
| totalBrowseSeconds | Long | 累计浏览时长（秒） |
| lastOpenedAtMillis | Long? | 最近打开时间戳，可为 null |
| updatedAtMillis | Long | 统计更新时间戳 |

### authors

```json
"authors": [{
  "authorId": "示例作者ID",
  "displayName": "示例作者名",
  "files": ["示例文件A.mp4"],
  "tags": { "示例标签X": 1 },
  "viewCount": 8,
  "followed": false
}]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| authorId | String | 作者唯一 ID（COS 作者以 cos_ 前缀标识） |
| displayName | String | 作者显示名 |
| files | String[] | 该作者关联的文件 recordKey 列表 |
| tags | Object | 标签名→该作者文件中出现次数 |
| viewCount | Int | 所有关联文件的 viewCount+playCount 之和 |
| followed | Boolean | 是否已关注 |

### followedAuthorIds

```json
"followedAuthorIds": ["cos_示例COS作者"]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| (数组元素) | String | 已关注的作者 ID |

### cosWorks

```json
"cosWorks": [{
  "authorName": "示例COS作者",
  "workName": "示例作品名",
  "folderUri": "content://...",
  "fileCount": 10,
  "viewCount": 80,
  "playCount": 0,
  "totalBrowseSeconds": 300,
  "likeCount": 5,
  "favoriteCount": 2,
  "tags": { "示例标签Y": 8 },
  "files": ["示例COS图片C.jpg"]
}]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| authorName | String | 作者名 |
| workName | String | 作品名 |
| folderUri | String | 目录 URI |
| fileCount | Int | 文件数 |
| viewCount | Int | 查看次数之和 |
| playCount | Int | 播放次数之和 |
| totalBrowseSeconds | Long | 浏览时长之和（秒） |
| likeCount | Int | 点赞次数之和 |
| favoriteCount | Int | 收藏文件数 |
| tags | Object | 标签名→出现次数 |
| files | String[] | 该作品所有文件 recordKey |

## 5. 部分导入指南

按场景只取需要的部分：

| 我只需要 | 读取哪些部分 | 怎么用 |
|---|---|---|
| 标签体系 | tags + mediaTags | 按 name 创建标签，按 recordKey+tagName 建立关联 |
| 作者信息 | authors + followedAuthorIds | 按 displayName 创建作者，followedAuthorIds 标记关注 |
| 收藏状态 | favorites | recordKey 列表，找到文件就标记收藏 |
| 点赞数据 | likes | recordKey + likeCount，写入点赞计数 |
| 浏览统计 | viewStats | recordKey + 各计数字段，合并时取较大值 |
| COS 作品结构 | cosWorks | authorName + workName + folderUri，重建作品层级 |
| 标签+作者（不要统计） | tags + mediaTags + authors | 跳过 viewStats/likes/favorites |
| 图片数据 | viewStats（isCosFile=false, mediaType="image"） | 过滤后导入 |
| 视频数据 | viewStats（isCosFile=false, mediaType="video"） | 过滤后导入 |
| COS 数据 | viewStats（isCosFile=true）+ cosWorks | 过滤后导入 |

### App 数据部分导入

| 我只需要 | 读取哪些文件 | 怎么用 |
|---|---|---|
| 作者信息 | authors.json | 按 displayName 创建作者 |
| 标签体系 | tags.json | 按 name 创建标签 + mediaTags 建立关联 |
| 浏览统计 | media_stats.json | 按 recordKey 写入统计，合并取较大值 |
| 浏览历史 | history.json | 按 recordKey + openedAtMillis 写入历史 |
| 收藏+点赞 | likes.json | favorites 标记收藏，likes 写入点赞计数 |
| 出处规则 | album_rules.json | 按 sourceName 创建规则 |
| 扫描源 | scan_sources.json | 按 uriString 添加扫描目录 |
| 推荐偏好 | recommendation_prefs.json | 9 维权重直接写入 |
| 时间轴标签 | timeline_tags.json | 按 recordKey+timeMillis+name 创建标签 |
| 应用设置 | settings.json | 按 key-value 写入设置 |

## 6. 关联键说明

- **recordKey**：文件名含扩展名，是所有部分的关联键（favorites/likes/mediaTags/viewStats/authors.files/cosWorks.files）
- **tagName**：关联 tags 和 mediaTags
- **authorId**：关联 authors 和 followedAuthorIds
- COS 作者 authorId 以 `cos_` 前缀标识

## 7. 热度分计算规则（供新 App 排行参考）

| 类型 | 公式 |
|---|---|
| 图片 | viewCount + likeCount + (收藏 ? 5 : 0) |
| 视频 | viewCount + playCount + floor(totalBrowseSeconds / 60) + likeCount + (收藏 ? 5 : 0) |

作者偏好度 = 所有关联文件的 (viewCount + playCount) 之和 + 每个收藏文件 +5 + 每个文件的 likeCount 之和

## 8. App 数据备份格式

备份目录 `绮梦影库/app数据/` 包含以下 JSON 文件，每个文件均遵循统一外层结构：

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { ... } }
```

### settings.json

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { "items": [{ "key": "示例设置键", "value": "示例值", "updatedAtMillis": ... }] } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| key | String | 设置项键名 |
| value | String | 设置项值 |
| updatedAtMillis | Long | 更新时间戳 |

### authors.json

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { "authors": [{ "authorId": "示例作者ID", "displayName": "示例作者名", "files": [], "createdAtMillis": ..., "updatedAtMillis": ... }] } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| authorId | String | 作者唯一 ID |
| displayName | String | 作者显示名 |
| files | String[] | 关联文件 recordKey 列表（当前导出为空数组） |
| createdAtMillis | Long | 创建时间戳 |
| updatedAtMillis | Long | 更新时间戳 |

### tags.json

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { "tags": [{ "tagId": 1, "name": "示例标签X", "createdAtMillis": ... }], "mediaTags": [{ "recordKey": "示例文件A.mp4", "fileName": "示例文件A.mp4", "tagName": "示例标签X" }] } }
```

tags 部分：

| 字段 | 类型 | 说明 |
|---|---|---|
| tagId | Long | 标签 ID |
| name | String | 标签名（唯一） |
| createdAtMillis | Long | 创建时间戳 |

mediaTags 部分：

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 文件完整名 |
| fileName | String | 文件名 |
| tagName | String | 标签名（关联 tags.name） |

### album_rules.json

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { "rules": [{ "sourceName": "示例出处A", "enabled": true, "createdAtMillis": ..., "updatedAtMillis": ... }] } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| sourceName | String | 出处名（唯一） |
| enabled | Boolean | 是否启用 |
| createdAtMillis | Long | 创建时间戳 |
| updatedAtMillis | Long | 更新时间戳 |

### media_stats.json

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { "items": [{ "recordKey": "示例文件A.mp4", "fileName": "示例文件A.mp4", "viewCount": 5, "playCount": 3, "totalBrowseSeconds": 120, "lastOpenedAtMillis": ..., "updatedAtMillis": ... }] } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 文件完整名 |
| fileName | String | 文件名 |
| viewCount | Int | 查看次数 |
| playCount | Int | 播放次数 |
| totalBrowseSeconds | Long | 累计浏览时长（秒） |
| lastOpenedAtMillis | Long? | 最近打开时间戳，可为 null |
| updatedAtMillis | Long | 统计更新时间戳 |

### history.json

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { "items": [{ "recordKey": "示例文件A.mp4", "fileName": "示例文件A.mp4", "mediaType": "video", "openedAtMillis": ... }] } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 文件完整名 |
| fileName | String | 文件名 |
| mediaType | String | "image" 或 "video" |
| openedAtMillis | Long | 打开时间戳 |

### scan_sources.json

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { "sources": [{ "uriString": "content://...", "displayName": "示例扫描目录A", "isBackupDirectory": false, "addedAtMillis": ..., "lastScannedAtMillis": ... }] } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| uriString | String | SAF 目录 URI |
| displayName | String | 显示名 |
| isBackupDirectory | Boolean | 是否备份目录 |
| addedAtMillis | Long | 添加时间戳 |
| lastScannedAtMillis | Long | 最近扫描时间戳 |

### likes.json

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { "likes": [{ "recordKey": "示例文件A.mp4", "likeCount": 3, "lastLikeDate": "2026-01-15" }], "favorites": ["示例文件A.mp4"] } }
```

likes 部分：

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 文件完整名 |
| likeCount | Int | 累计点赞次数 |
| lastLikeDate | String? | 最近点赞日期（yyyy-MM-dd），可为 null |

favorites 部分：

| 字段 | 类型 | 说明 |
|---|---|---|
| (数组元素) | String | 收藏的文件 recordKey |

### recommendation_prefs.json

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { "tagRelevance": 0.22, "tagCollection": 0.15, "engagement": 0.10, "recency": 0.15, "likeScore": 0.05, "discovery": 0.20, "freshness": 0.05, "browseDepth": 0.03, "maxRandom": 0.30 } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| tagRelevance | Float | 标签相关性权重 |
| tagCollection | Float | 标签合集权重 |
| engagement | Float | 互动热度权重 |
| recency | Float | 浏览时效权重 |
| likeScore | Float | 点赞偏好权重 |
| discovery | Float | 新发现权重 |
| freshness | Float | 新鲜度权重 |
| browseDepth | Float | 浏览深度权重 |
| maxRandom | Float | 随机性上限 |

### timeline_tags.json

```json
{ "schemaVersion": 1, "exportedAtMillis": ..., "data": { "items": [{ "recordKey": "示例文件A.mp4", "fileName": "示例文件A.mp4", "timeMillis": 30000, "name": "示例时间轴标签X", "createdAtMillis": ... }] } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| recordKey | String | 视频文件完整名 |
| fileName | String | 文件名 |
| timeMillis | Long | 视频内时间点（毫秒） |
| name | String | 标签名 |
| createdAtMillis | Long | 创建时间戳 |

## 9. 注意事项

1. AI 只写代码不看内容：迁移代码按字段名和类型读取 JSON，不需要理解文件名的含义
2. 文件名是关联键：所有偏好数据通过 recordKey 关联到具体文件
3. 不依赖绝对路径：recordKey 不含路径，仅含文件名
4. 标签按 name 去重：迁移标签时按标签名去重，不依赖 tagId
5. 统计取较大值：合并观看统计时取新旧较大值
6. 收藏是布尔标记：文件要么在收藏列表中，要么不在
7. 编码 UTF-8：所有 JSON 文件使用 UTF-8 编码
8. 缺失字段用默认值：数值型默认 0，字符串默认空，布尔型默认 false
9. 未知字段忽略：导入时遇到未知字段应忽略，保持前向兼容
10. mediaType 区分图片视频：影响热度分计算公式
11. isCosFile 区分 COS 文件：COS 文件有独立的 cosWorks 数据

最后更新时间：2026-06-13
