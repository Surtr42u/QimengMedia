# DATA_MIGRATION_SPEC - 绮梦影库数据迁移规范

> 本文件是数据格式规范和迁移教程，**不含任何用户实际数据**。
> AI 代理阅读本文件后，应能在**不查看具体数据内容**的前提下，编写数据迁移代码或在新 App 中自动导入这些数据。
> **AI 不需要理解文件内容含义，只需按格式规范编写读取和写入代码。**

## 核心原则

1. **AI 只写代码，不看内容**：AI 读完本规范后，应编写自动导入代码。代码按字段名和类型读取 JSON，不需要理解文件名的含义。
2. **文件名是关联键**：所有数据通过 `recordKey`（完整文件名含扩展名）关联。新 App 只需建立 `旧recordKey → 新文件标识` 的映射。
3. **格式稳定**：`schemaVersion` 标识格式版本，新增字段不影响旧代码。

## 主键策略：recordKey

```
recordKey = 文件名.扩展名
```

- `1.jpg` 与 `1.mp4` 是不同记录
- 文件名重复时追加父文件夹名：`1.mp4 @ Videos`
- 迁移时只需匹配文件名，不依赖路径

## 导出文件结构

导出时生成两个文件：

| 文件 | 格式 | 用途 | 谁用 |
|---|---|---|---|
| `personal_prefs_export.json` | JSON | 完整原始数据，用于程序导入 | 新 App 的迁移代码 |
| `personal_prefs_report.txt` | 纯中文文本 | 人类可读的偏好报告 | 用户自己看 |

**迁移代码只需读取 JSON 文件，TXT 报告与迁移无关。**

### JSON 文件结构

```json
{
  "schemaVersion": 1,
  "exportedAtMillis": 1700000000000,
  "appIdentifier": "com.qimeng.media",
  "data": { ... }
}
```

### TXT 报告结构

纯中文文本，章节包括：
- 【总览】统计数字
- 【点赞最多的文件 Top 20】
- 【观看最多的文件 Top 20】
- 【收藏的文件】全部列出
- 【使用最多的标签 Top 20】
- 【偏好度最高的作者 Top 20】
- 【所有标签】全部列出
- 【所有作者】全部列出

## data - 完整原始数据

**这个区域是给程序读取的**，迁移代码只需按字段名和类型解析即可。

### data 包含的数据部分

| 部分 | 说明 | 必需 |
|---|---|---|
| `favorites` | 收藏的文件列表 | 是 |
| `likes` | 点赞次数和日期 | 是 |
| `tags` | 标签定义 | 是 |
| `mediaTags` | 文件与标签的关联 | 是 |
| `viewStats` | 观看/播放次数和时长 | 是 |
| `authors` | 作者及关联文件 | 是 |

### favorites - 收藏

```json
"favorites": [
  "文件名1.mp4",
  "文件名2.jpg"
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| (数组元素) | String | 文件的 recordKey |

**迁移代码**：遍历数组，每个 recordKey 在新 App 中查找文件，找到则标记为收藏。

### likes - 点赞

```json
"likes": [
  {
    "recordKey": "文件名1.mp4",
    "likeCount": 5,
    "lastLikeDate": "2026-05-30"
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `recordKey` | String | 文件完整名 |
| `likeCount` | Int | 累计点赞次数 |
| `lastLikeDate` | String? | 最近点赞日期（yyyy-MM-dd），可为 null |

**迁移代码**：遍历数组，按 recordKey 找文件，写入 likeCount。lastLikeDate 可选。

### tags - 标签定义

```json
"tags": [
  {
    "tagId": 1,
    "name": "高质量",
    "createdAtMillis": 1700000000000
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `tagId` | Long | 标签 ID（迁移时可不保留，按 name 去重） |
| `name` | String | 标签名（唯一，迁移的关键匹配字段） |
| `createdAtMillis` | Long | 创建时间戳 |

**迁移代码**：遍历数组，在新 App 中按 name 创建标签。记录 `旧tagId → 新标签ID` 映射。

### mediaTags - 文件与标签关联

```json
"mediaTags": [
  {
    "recordKey": "文件名1.mp4",
    "fileName": "文件名1.mp4",
    "tagName": "高质量"
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `recordKey` | String | 文件完整名 |
| `fileName` | String | 文件名（通常与 recordKey 相同） |
| `tagName` | String | 标签名（关联 tags.name） |

**迁移代码**：遍历数组，用 recordKey 找文件、tagName 找标签，两边都找到则建立关联。

### viewStats - 观看统计

```json
"viewStats": [
  {
    "recordKey": "文件名1.mp4",
    "fileName": "文件名1.mp4",
    "viewCount": 12,
    "playCount": 8,
    "totalBrowseSeconds": 3600,
    "lastOpenedAtMillis": 1700000000000,
    "updatedAtMillis": 1700000000000
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `recordKey` | String | 文件完整名 |
| `fileName` | String | 文件名 |
| `viewCount` | Int | 查看次数 |
| `playCount` | Int | 播放次数 |
| `totalBrowseSeconds` | Long | 累计浏览时长（秒） |
| `lastOpenedAtMillis` | Long? | 最近打开时间戳，可为 null |
| `updatedAtMillis` | Long | 统计更新时间戳 |

**迁移代码**：遍历数组，按 recordKey 找文件，写入 viewCount/playCount/totalBrowseSeconds。已有统计取较大值。

### authors - 作者及关联文件

```json
"authors": [
  {
    "authorId": "author_1",
    "displayName": "作者名",
    "files": ["文件名1.mp4", "文件名2.jpg"]
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `authorId` | String | 作者唯一 ID |
| `displayName` | String | 作者显示名 |
| `files` | String[] | 该作者关联的文件 recordKey 列表 |

**迁移代码**：遍历数组，在新 App 中按 displayName 创建作者，然后遍历 files 建立文件关联。

## 迁移代码模板

AI 代理可直接使用以下模板编写迁移代码，**无需理解文件内容含义**：

```python
import json

def migrate(source_path, new_app):
    with open(source_path, 'r', encoding='utf-8') as f:
        export = json.load(f)

    if export.get('schemaVersion') != 1:
        raise ValueError("不支持的 schemaVersion")

    data = export['data']

    # 1. 建立文件名映射
    all_old_keys = set()
    all_old_keys.update(data.get('favorites', []))
    all_old_keys.update(item['recordKey'] for item in data.get('likes', []))
    all_old_keys.update(item['recordKey'] for item in data.get('mediaTags', []))
    all_old_keys.update(item['recordKey'] for item in data.get('viewStats', []))
    for author in data.get('authors', []):
        all_old_keys.update(author.get('files', []))

    file_mapping = new_app.build_file_mapping(all_old_keys)

    # 2. 迁移标签
    tag_name_map = {}
    for tag in data.get('tags', []):
        new_id = new_app.create_tag(tag['name'])
        tag_name_map[tag['name']] = new_id

    # 3. 迁移标签关联
    for mt in data.get('mediaTags', []):
        file_id = file_mapping.get(mt['recordKey'])
        tag_id = tag_name_map.get(mt['tagName'])
        if file_id and tag_id:
            new_app.link_tag(file_id, tag_id)

    # 4. 迁移收藏
    for rk in data.get('favorites', []):
        file_id = file_mapping.get(rk)
        if file_id:
            new_app.set_favorite(file_id, True)

    # 5. 迁移点赞
    for item in data.get('likes', []):
        file_id = file_mapping.get(item['recordKey'])
        if file_id:
            new_app.set_like_count(file_id, item['likeCount'])

    # 6. 迁移观看统计
    for stat in data.get('viewStats', []):
        file_id = file_mapping.get(stat['recordKey'])
        if file_id:
            new_app.merge_view_stats(file_id, stat)

    # 7. 迁移作者
    for author in data.get('authors', []):
        author_id = new_app.create_author(author['displayName'])
        for rk in author.get('files', []):
            file_id = file_mapping.get(rk)
            if file_id and author_id:
                new_app.link_author(file_id, author_id)

    return len(file_mapping)
```

## 完整备份格式参考

如需迁移全部数据（包括应用配置），备份目录 `LocalMediaApp_Data/` 包含以下 JSON 文件：

| 文件名 | schemaVersion | 内容 |
|---|---|---|
| `settings.json` | 1 | 应用设置项（key-value） |
| `authors.json` | 1 | 作者及关联文件 |
| `tags.json` | 1 | 标签定义 + 文件标签关联 |
| `album_rules.json` | 1 | 相册出处规则 |
| `media_stats.json` | 1 | 观看统计 |
| `history.json` | 1 | 浏览历史 |
| `scan_sources.json` | 1 | 扫描源目录 |
| `likes.json` | 1 | 点赞 + 收藏 |

每个文件顶层结构均为：
```json
{
  "schemaVersion": 1,
  "exportedAtMillis": 1700000000000,
  "data": { ... }
}
```

## App 偏好格式参考

App 偏好存储在 `{filesDir}/app_prefs.json`，格式：

```json
{
  "version": 1,
  "custom_album_sources": ["ACC", "XXX"],
  "theme_mode": "system",
  "grid_columns_home": 2,
  "grid_columns_all": 3,
  "grid_columns_album": 3
}
```

## 注意事项

1. **AI 只写代码不看内容**：迁移代码按字段名和类型读取 JSON，不需要理解文件名的含义。
2. **文件名是关联键**：所有偏好数据通过 recordKey 关联到具体文件。
3. **不依赖绝对路径**：recordKey 不含路径，仅含文件名。
4. **标签按 name 去重**：迁移标签时按标签名去重，不依赖 tagId。
5. **统计取较大值**：合并观看统计时取新旧较大值。
6. **收藏是布尔标记**：文件要么在收藏列表中，要么不在。
7. **编码 UTF-8**：所有 JSON 文件使用 UTF-8 编码。
8. **缺失字段用默认值**：数值型默认 0，字符串默认空，布尔型默认 false。
9. **未知字段忽略**：导入时遇到未知字段应忽略，保持前向兼容。
10. **summary 区域可忽略**：迁移代码只需读取 `data` 区域，`summary` 是给用户看的。

> 最后更新：2026-06-02
