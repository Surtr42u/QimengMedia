# GUIDE_ALGORITHM - 推荐与分类算法

> **本文档是推荐与分类算法的唯一权威文档。** 其他指南文档中涉及算法的详细描述应指向此文档，不在别处重复。

## 实现路径

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/qimeng/media/ui/browser/MediaBrowserLogic.kt` | 推荐/排行/筛选/分组核心逻辑 |
| `app/src/main/java/com/qimeng/media/ui/browser/MediaFilterSheet.kt` | 筛选面板 UI |
| `app/src/main/java/com/qimeng/media/ui/album/SourceMatcher.kt` | 出处匹配引擎 |
| `app/src/main/java/com/qimeng/media/core/ThumbnailLoader.kt` | 缩略图解码策略 |
| `app/src/main/java/com/qimeng/media/core/ThumbnailCache.kt` | 缩略图本地文件缓存 |

枚举与数据类（均在 `MediaBrowserLogic.kt` 内定义）：
- `MediaSortKey`、`MediaSortDirection`、`MediaViewRange`、`MediaPlayRange`、`MediaSizeRange`、`MediaRankingPeriod`、`MediaTagMode`、`MediaDateRange`
- `MediaFilterState`、`MediaDateGroup`

## 职责

管理推荐算法、排行榜逻辑、筛选面板枚举、出处匹配引擎和缩略图解码策略。

不管：数据存储（见 GUIDE_DATA.md）、UI 渲染（见 GUIDE_UI.md）、扫描流程（见 GUIDE_SCAN.md）

## 推荐算法（`recommend()`）

推荐引擎位于 `MediaBrowserLogic.kt` 的 `recommend()` 方法，采用 **自适应加权评分模型**，完全本地运行，不访问网络，不上传用户数据。

### 内部结构（2026-06-17 重构后）

`recommend()` 主体只做编排，具体逻辑拆分到 3 个私有子方法 + 2 个数据类：

| 子方法/数据类 | 职责 |
|---|---|
| `resolveWeights()` | 权重初始化（自定义偏好优先）+ 自适应回收（标签/点赞/历史为空时重新分配） |
| `computeNormDenominators()` | 计算归一化分母（engagement/browseSeconds/likes 最大值，至少为 1） |
| `shuffleBuckets()` | seed>0 时对 ±0.05 同分桶做确定性随机打散 |
| `RecommendWeights` | 9 维权重数据类 |
| `NormDenominators` | 3 维归一化分母数据类 |

10 维评分公式（tagRelevance/tagCollection/engagement/recency/likeScore/discovery/freshness/browseDepth/randomFactor/dailyPenalty）保留在 `recommend()` 主体的 `media.map {}` lambda 中，计算表达式逐字不变。

### 基本原则

- 推荐完全本地运行，不依赖网络。
- 推荐结果可解释，便于调试。
- 分数计算要稳定，避免每次打开随机大变。
- 随机扰动基于 seed 的确定性随机，同 seed 可复现。
- **自适应权重**：根据数据状态（标签/点赞/历史是否为空）动态调整权重，数据完善后自动恢复设计权重。
- **用户可配置权重**：`recommend()` 接受可选 `customPrefs: RecommendationPrefs?` 参数，用户可通过"我的→推荐偏好"调整各维度权重初始值；自定义权重作为自适应逻辑的起点，空维度回收仍然生效。偏好弹窗提供 4 个预设方案（均衡推荐/高记忆流行/深度探索/新鲜优先），预设定义位于 `ProfileFragment.recPrefPresets`，详见 `GUIDE_DATA.md` 推荐偏好预设表。

### 自适应权重机制

当某些维度数据为空时，其权重自动重新分配给更有效的维度，避免"空维度占权重但不区分文件"的问题：

| 数据状态 | 回收权重 | 分配给 |
|---|---|---|
| 标签为空 | tagRelevance(0.22) + tagCollection(0.15) = 0.37 | discovery ×50% + randomFactor ×50% |
| 点赞为空 | likeScore(0.05) | freshness |
| 无浏览历史 | engagement(0.10) | discovery |

**当前无标签、无点赞、无历史时的实际权重**：

| 维度 | 设计权重 | 实际权重 | 说明 |
|---|---|---|---|
| tagRelevance | 0.22 | 0 | 标签为空，权重回收 |
| tagCollection | 0.15 | 0 | 标签为空，权重回收 |
| engagement | 0.10 | 0 | 无历史，权重回收 |
| recency | 0.15 | 0.15 | 正常 |
| likeScore | 0.05 | 0 | 点赞为空，权重回收 |
| discovery | 0.20 | **0.485** | 接收了回收权重，鼓励探索 |
| freshness | 0.05 | **0.10** | 接收了 likeScore 权重 |
| browseDepth | 0.03 | 0.03 | 正常 |
| randomFactor | 0~0.30 | **0~0.485** | 接收了回收权重，刷新效果大幅增强 |

视频/图片分布由 `balanceVideoImage()` 自然混合策略负责，不在评分中重复处理。

**数据完善后的设计权重**（自动恢复）：

| 维度 | 权重 | 计算方式 |
|---|---|---|
| 标签相关性 (tagRelevance) | 0.22 | 统计每个标签在所有已浏览文件中出现的频次，归一化后取文件标签的平均相关性 |
| 标签合集 (tagCollection) | 0.15 | 文件标签集与用户 Top-20 偏好标签集的 Jaccard 相似度 |
| 互动热度 (engagement) | 0.10 | (viewCount + playCount) / 全库最大参与度，归一化到 0~1 |
| 时效 (recency) | 0.15 | 距上次浏览的天数衰减：<1天=1.0, <3天=0.8, <7天=0.5, <30天=0.2, 更久=0；从未浏览=0.3 |
| 发现 (discovery) | 0.20 | 1 - min(viewCount/5, 1)，浏览越少发现分越高 |
| 新鲜度 (freshness) | 0.05 | 入库时间衰减：<1天=1.0, <3天=0.7, <7天=0.4, <30天=0.2 |
| 点赞 (likeScore) | 0.05 | likeCount / 全库最大点赞数，归一化到 0~1 |
| 浏览深度 (browseDepth) | 0.03 | totalBrowseSeconds / 全库最大浏览时长，归一化到 0~1 |
| 随机扰动 (randomFactor) | 0~0.30 | 基于 seed 的确定性随机：`(recordKey.hashCode() xor seed).and(0xFFFF) / 65535 * maxRandom`，同 seed 可复现 |
| 每日去重 (dailyPenalty) | 0 / -0.8 | 固定惩罚项，**不计入 0.95 固定权重**：该文件今日已被展示过则 -0.8f，未展示过 0f。由 `recommend()` 的 `dailyShownCountMap` 参数传入，让已展示文件排到后面、新文件排到前面。详见下方「每日推荐去重」 |

**固定权重合计**：0.95（前 9 维），randomFactor 为额外加分（0~0.30 或自适应后 0~0.485），dailyPenalty 为惩罚项（0 / -0.8），三者共同构成 10 维最终评分。

### 标签相关性详解

标签相关性衡量单个标签与用户偏好的关联强度：
1. 遍历所有 viewCount > 0 的文件，统计每个标签在多少个已浏览文件中出现
2. 归一化：出现次数 / 最大出现次数 → 0~1 的相关性值
3. 文件的标签相关性 = 其所有标签相关性值的平均

示例：三个已浏览文件的标签分别为 `asd`、`bad`、`vca`，标签 "a" 出现 3 次（最高），归一化=1.0；"d" 出现 2 次，归一化≈0.67；"s/b/v/c" 各出现 1 次，归一化≈0.33。

### 标签合集详解

标签合集衡量文件标签集与用户偏好标签集的整体匹配度：
1. 取标签相关性排名前 20 的标签作为用户偏好标签集
2. 计算文件标签集与偏好标签集的 Jaccard 相似度 = |交集| / |并集|
3. 标签或偏好为空时返回默认值 0.15

### 辅助方法

| 方法 | 功能 |
|---|---|
| `buildTagRelevanceMap()` | 构建标签→相关性映射 |
| `tagRelevanceScore()` | 计算单文件标签相关性 |
| `tagCollectionScore()` | 计算单文件标签合集相似度 |
| `freshnessScore()` | 入库时间衰减函数 |
| `recencyScore()` | 浏览时效衰减函数 |

### 每日推荐去重

`recommend()` 接收 `dailyShownCountMap` 参数（`Map<String, Int>`），对已出现过的文件施加惩罚：
- 出现 0 次：无惩罚
- 出现 1 次及以上：-0.8（强惩罚，让已展示文件排到后面，新文件排到前面）

HomeFragment 中 `dailyShownCountMap` 记录逻辑：每次 render 后，将展示的文件计数 +1。下拉刷新时保留 `dailyShownCountMap`（不清空），确保已展示的文件被惩罚。每日零点自动清空。

### balanceVideoImage() 自然混合策略

评分排序后，调用 `balanceVideoImage()` 对结果做视频/图片自然混合：
1. 按媒体类型拆分为视频队列和图片队列，保持原始评分排序
2. 每次取元素时，按剩余数量比例随机决定取视频还是图片
3. 比例接近时约 50/50，某方少时自动偏向另一方
4. 视频和图片自然交错，不做连续分组，看起来像随机混合而非人工排列

**trade-off**：此操作会打乱评分排序，低评分视频可能排在高评分图片前面，牺牲部分排序精度换取浏览体验的视觉多样性。

### 同分桶随机打散

seed > 0 时（下拉刷新场景），将排序结果按分数分桶，**±0.05 内视为同分桶**（比精确同分更宽松，让更多文件进入同一桶），桶内用 `Random(seed)` 随机打散，打破近分项的固定排列，让每次刷新推荐列表有变化。

### 排序缓存机制

`HomeFragment` 内为每个 tab 独立维护排序缓存（`tabSortFingerprints`/`tabSortedItems`/`tabDisplayCounts`）。指纹包含 `media.size + tab + period + filterState.hashCode() + query + refreshSeed`。只有指纹变化才重排序且重置 `displayCount`。滚动到底只 `take(displayCount)` 从缓存取，不重排——缩略图位置永久固定。下拉刷新时清空所有 tab 缓存，重新推荐。左右滑动切换 tab 不重新推荐，恢复之前的排序结果。

`AllFilesFragment` 内 `GroupedMediaAdapter.submitMedia()` 自带 `lastKeys` 指纹，相同数据不调 `notifyDataSetChanged()`。

### 冷启动策略

- 新文件给予基础曝光（freshness 维度对入库时间短的文件加分）。
- 无历史时优先混合不同类型、作者、出处。
- 避免推荐页全是同一作者或同一出处。
- 从未浏览过的文件 recency 返回默认值 0.3，不会被完全忽略。

## 排行榜（`rank()`）

排行榜不使用个性化推荐，只使用热度排序。

### 热度排序逻辑

按 `viewCount + playCount + likeCount` 降序排列（`comparatorFor(VIEWS)` 实现）。`likeCount` 参与排行，点赞数直接加入总分。同分时按 `modifiedAtMillis` 降序。

### 周期筛选

通过 `MediaRankingPeriod` 枚举控制：日榜、周榜、月榜、年榜、总榜。

周期内通过 `view_history` 的 `openedAtMillis` 和 `view_stats` 的 `lastOpenedAtMillis` 判断文件是否属于该周期。`rank()` 方法调用 `keysInPeriod()` 筛选周期内的文件 recordKey，非总榜时过滤出周期内文件再排序。

## 筛选面板

### MediaFilterSheet + FilterConfig

Material `BottomSheetDialog` + `ChipGroup` 实现。`FilterConfig` 控制各筛选区域的显示/隐藏，新增页面只需传入对应 `FilterConfig` 即可复用筛选面板。自定义配置：`FilterConfig(showPlays = false, showTags = false)` 按需组合。

筛选同时作用于搜索结果：`applyFilter()` 接收 `query` 和 `filterState`，搜索和筛选同时生效。

### 筛选区域枚举表

| 区域 | 枚举 | 选项 |
|---|---|---|
| 排序方式 | `MediaSortKey` | 默认、文件日期、添加日期、观看次数、点击次数、文件大小、名字 |
| 顺位 | `MediaSortDirection` | 降序、升序 |
| 观看次数 | `MediaViewRange` | 全部、未观看、1-5次、5-20次、>20次 |
| 点击次数 | `MediaPlayRange` | 全部、未点击、1-5次、5-20次、>20次 |
| 文件大小 | `MediaSizeRange` | 全部、<1MB、1-10MB、10-50MB、>50MB |
| 时间范围 | `MediaDateRange` | 全部、今天、本周、本月、近三月、本年、按年份（NumberPicker 选择起始年-结束年） |
| 标签模式 | `MediaTagMode` | 模糊（含任一标签即显示）、精确（必须包含全部选中标签） |
| 标签 | `Set<String>` | 多选标签、长按删除、"+ 添加标签" |

### FilterConfig 预设表

| 预设配置 | 适用页面 | 排序 | 顺位 | 观看 | 点击 | 大小 | 时间 | 标签模式 | 标签 |
|---|---|---|---|---|---|---|---|---|---|
| `FOR_HOME` | 主页推荐/排行榜 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `FOR_ALL` | 全部页 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `FOR_AUTHOR` | 作者文件页 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `FOR_ALBUM` | 相册出处详情 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `FULL` | 全部显示 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

标签模式说明：模糊=选中标签中任一匹配即显示，精确=必须包含全部选中标签才显示。榜单排行已从筛选面板移除，排行榜功能由主页排行榜 tab 独立控制。

### applyFilter() 流程

1. 搜索过滤：文件名、文件夹名、标签中包含查询词
2. 观看次数范围过滤
3. 点击次数范围过滤
4. 文件大小范围过滤
5. 时间范围过滤
6. 标签过滤（模糊/精确模式）
7. 按 `comparatorFor(sortKey)` 排序，升序时取 reversed()

## 出处匹配引擎（SourceMatcher）

`SourceMatcher` 是出处匹配单例，位于 `app/src/main/java/com/qimeng/media/ui/album/SourceMatcher.kt`，采用 **SourceGroup 规范名 + 变体 + 角色检索表** 设计。

### 两层匹配优先级

1. **内置检测表**：~130 个 SourceGroup，覆盖热门游戏/动漫/影视，优先级最高
2. **自定义出处**：用户手动添加的分区名（通过长按卡片修改），自动加入识别算法

未匹配的文件归入"其他"。TXT 规则不再用于出处匹配（仅用于作者-文件关联）。

### SourceGroup 规范名+变体设计

- 每个 `SourceGroup` 包含一个规范名（canonical）、多个变体（variants）和角色列表（characters）
- 变体包括中文名、英文名、缩写、带空格/不带空格等不同写法
- 匹配时所有变体按长度降序排列（最长优先），确保"尼尔机械纪元"优先于"尼尔"匹配
- 匹配成功返回规范名，所有变体归入同一出处分区
- 版本号合并：铁拳7/8→"铁拳"，生化危机2/3/4/8→"生化危机"，最终幻想7/14/15/16→"最终幻想"

### 角色检索表

每个 `SourceGroup` 内含 `CharEntry` 角色列表，每个角色有规范名和别名列表：

- `CharEntry("好运姐", listOf("好运姐", "赏金", "赏金猎人", "Miss Fortune", "MF", "厄运小姐"))`
- 出处+角色名作为唯一标识，避免跨出处同名混淆（如"安娜"在守望先锋和铁拳中是不同角色）
- 角色别名按长度降序匹配，确保"Miss Fortune"优先于"MF"匹配
- `matchCharacters(fileName, sourceName)` 方法：先从文件名中去掉已匹配的出处部分（`stripSourceFromName`），再用剩余部分在角色别名表中做全文子串匹配，返回所有匹配到的角色列表（`List<String>`）。同一 canonical 不会因不同别名被重复添加（去重保护）
- `stripSourceFromName(fileName, sourceName)` 方法：利用 `SOURCE_VARIANT_MAP` 找到出处的所有变体，按长度降序从文件名开头去掉出处，避免出处中的字干扰角色匹配（如"崩坏星穹铁道"中的"星"不会被误匹配到"开拓者"）
- 多角色匹配：文件名包含多个角色别名时（如"守望先锋 天使+dva"），返回 `["天使", "dva"]`，按 canonical 字典序排序后显示为"dva+天使"，确保不同文件名顺序（"天使+dva"和"dva 天使"）归入同一药丸
- 多角色匹配使用区域重叠检测，避免同一位置被不同角色重复匹配
- 角色分隔符支持：`+`、`x`、`&` 均可作为多角色分隔符（子串匹配天然支持，无需特殊处理）
- `matchAll(fileName)` 方法：一次调用同时返回出处和角色列表（`Pair<String?, List<String>>`），避免对同一文件名重复调用 `match()`。支持多出处匹配：文件名含"+"时（如"恶魔战士+铁拳8  莫妮卡+莉莉"），按"+"分割分别匹配出处，再合并所有出处的角色结果，出处显示为"恶魔战士+铁拳"
- `matchCharacter(fileName, sourceName)` 方法：兼容旧接口，内部调用 `matchCharacters` 并用"+"拼接返回
- 角色匹配只走检索表，不做前缀去除或回退，未匹配归入"其他"
- 检索表覆盖约 130 个出处，涵盖游戏/动漫/小说，含高人气女角色及热门游戏中的非热门女角色

### 版本号合并

同一系列不同版本归入同一 SourceGroup，版本号写入 variants。例：`listOf("铁拳8", "铁拳7", "铁拳6", "铁拳", "Tekken 8", "Tekken 7", "Tekken")`

### 匹配流程

对每个文件名：去掉扩展名 → 去空格折叠 → 小写 → 依次尝试两层匹配（collapsed prefix match）→ 回退原始文件名前缀匹配 → 未匹配归入"其他"

**角色匹配流程**：先匹配出处 → 从文件名中去掉出处部分（`stripSourceFromName`）→ 用剩余部分在该出处的角色别名表中做全文子串匹配 → 支持多角色匹配，多个角色按 canonical 字典序排序后用"+"拼接 → 未匹配归入"其他"。"其他"在出处和角色药丸中始终排在最下面（不参与数量排序）。

**`stripSourceFromName` 数字保护**：去掉出处后，先去开头空白和标点（`^[\s+_\-()（）]+`），再去开头数字但保留后跟字母的（`^\d+(?![a-zA-Z])`），避免 "2B"、"9S" 等数字开头角色名被误删。

**合并检索**：全部/收藏/历史页使用 `matchAll()` 一次调用同时获取出处和角色，避免重复调用 `match()`。相册详情页已知出处，直接调用 `matchCharacters()`。

**COS 分区分组**（非 SourceMatcher，基于目录结构）：
- 出处 = 作者名（通过 `AuthorMediaCrossRef` 关联 `cos_` 前缀 authorId → `AuthorEntity.displayName`）
- 角色 = 作品名（`CosWorkEntity.workName`，通过 `MediaFileEntity.folderName` 匹配作品名；workName == authorName 时归入"其他"）
- 不在任何作者目录下的文件归入"其他"

**COS 分组性能（CosAuthorIndex / CosWorkIndex）**：`MediaGroupHelper` 提供两个预构建索引，批量/循环场景必须使用，禁止退回旧的"对每个文件线性扫描整个关联表"写法。
- `CosAuthorIndex`：`recordKey → 作者显示名`。`CosAuthorIndex.build(authorMedia, authors)` 一次构建 O(关联数+作者数)，`authorNameOf(recordKey)` 查找 O(1)。仅收录 `cos_` 前缀作者，"取首条匹配"语义等价于旧 `authorMedia.find { ... }`。
- `CosWorkIndex`：`作者名 → 作品名列表`。`CosWorkIndex.build(cosWorks)` 一次构建 O(作品数)，`worksOf(authorName)` 查找 O(1) 取作品集。
- 索引版查找：`findCosAuthorForMedia(media, index)` / `findCosCharacterForMedia(media, authorIndex, workIndex)`。
- `groupByCosAuthor` / `groupByCosWork` 内部已先建索引再遍历，全部页/收藏页/历史页走 groupBy 零改动自动受益。
- 旧签名 `findCosAuthorForMedia(media, authorMedia, authors)` / `findCosCharacterForMedia(media, authorMedia, authors, cosWorks)` 保留供单次查询与现有测试，**批量/循环场景禁用**（O(文件数×关联数) 嵌套扫描，全库数千 COS 文件时主线程卡顿）。
- 根因背景：旧实现对每个 COS 文件线性扫描整个 `authorMedia` 列表，复杂度 O(N×M) 且在主线程 `collect` 内执行。卡顿与"当前作者文件数"无关，只取决于全库 COS 规模——这正是"相同文件数量也卡顿"的原因。常规路径不卡：作者页常规用 `authorFiles`（crossRef 精确集合 O(1)），相册常规用 `SourceMatcher.match`（遍历~130 内置变体，常量级），均无 N×M 嵌套。

### 更新规范

AI 更新检索表时必须遵循以下规则，违反任何一条即为错误更新：

#### 新增 SourceGroup 规则

1. **查重优先**：新增前必须搜索 `BUILTIN_GROUPS` 中是否已存在相同 canonical 或变体重叠的 SourceGroup，禁止创建重复条目
2. **canonical 命名**：使用最通用的中文名，不带版本号（如"铁拳"而非"铁拳8"，"最终幻想"而非"最终幻想7"）
3. **variants 完整性**：必须包含中文名（带空格/不带空格）、英文名、常见缩写。例：`listOf("英雄联盟", "League of Legends", "LOL", "撸啊撸")`
4. **版本号合并**：同一系列不同版本归入同一 SourceGroup，版本号写入 variants。例：`listOf("铁拳8", "铁拳7", "铁拳6", "铁拳", "Tekken 8", "Tekken 7", "Tekken")`
5. **放置位置**：新 SourceGroup 追加到 `BUILTIN_GROUPS` 末尾（`listOf(` 的最后一个元素之前），不要插入到中间打乱现有顺序

#### 新增 CharEntry 规则

6. **查重优先**：在同一 SourceGroup 内搜索是否已存在相同角色（canonical 或别名重叠），禁止创建重复 CharEntry
7. **canonical 命名**：使用最常用的中文角色名。例：`CharEntry("好运姐", ...)` 而非 `CharEntry("Miss Fortune", ...)`
8. **aliases 完整性**：必须包含 canonical 自身、中文别名、英文名、常见昵称/缩写。例：`listOf("好运姐", "赏金", "赏金猎人", "Miss Fortune", "MF", "厄运小姐")`
9. **别名顺序**：canonical 自身放第一个，其余按长度降序排列（长名优先匹配，避免短名误匹配）
10. **出处+角色名唯一标识**：不同出处下的同名角色是不同角色（如守望先锋的"安娜"和铁拳的"安娜"），各自独立 CharEntry，不合并
11. **同一出处内禁止重复角色**：如发现同一角色出现两次 CharEntry（如之前的"停云"在星穹铁道中出现两次），必须合并为一个 CharEntry 并合并 aliases
12. **只写女性角色**：检索表仅收录女性角色，禁止添加男性角色。本 App 的核心使用场景是女性角色媒体库，男性角色无实际检索需求

#### 修改/删除规则

13. **修改 canonical**：必须同步更新所有引用该 canonical 的地方（`SOURCE_VARIANT_MAP`、`matchCharacter` 中的 `sourceCanonical` 比较等）
14. **删除 SourceGroup**：必须确认无其他代码引用该 canonical，且从 `BUILTIN_GROUPS` 完整移除，不留空壳
15. **禁止只删 UI 层**：删除角色或出处时，必须从 `BUILTIN_GROUPS` 中完整移除对应数据，不能只删 UI 引用

#### 验证规则

16. **构建验证**：每次修改后必须运行 `gradlew assembleDebug` 确认编译通过
17. **重复检查**：修改后必须搜索确认无重复 canonical 的 SourceGroup（之前出现过"守望先锋"和"光与影 33号远征队"各出现两次的 bug）
18. **文档同步**：修改后必须更新本节文档（如更新出处数量、新增规则说明等）

## 缩略图解码策略

### 三级策略（ThumbnailLoader）

**图片缩略图（loadImageThumbnail）**：

1. **ContentResolver.loadThumbnail**（最快，30-100ms）：仅 MediaStore URI（`content://media/`），直接读取系统预生成缩略图缓存
2. **BitmapFactory 降采样解码**（较快）：`decodeSampled()` 两次读取（bounds + 实际解码），`inSampleSize` 降采样
3. **ImageDecoder 回退**（API 28+）：处理 GIF 等 `BitmapFactory.decodeStream()` 不支持的格式，`setTargetSize()` 控制输出尺寸

**动图缩略图（loadImageThumbnail，mediaType=ANIMATED_IMAGE）**：与图片缩略图使用相同的三级策略，GIF 扩展名在扫描时被识别为 `ANIMATED_IMAGE` 类型

**视频缩略图（loadVideoThumbnail）**：

1. **ContentResolver.loadThumbnail**（最快，30-100ms）：仅 MediaStore URI（`content://media/`），直接读取系统预生成缩略图缓存
2. **MediaMetadataRetriever.getEmbeddedPicture**（较快）：读取视频内嵌封面（cover art），不需要解码视频流
3. **截帧+黑帧检测**（最慢）：`loadFirstFrame()` 复用同一个 `MediaMetadataRetriever`，固定时间策略 0ms→1s→2s→3s→5s，自动跳过纯黑帧

### 黑帧检测算法详解

`loadFirstFrame()` 在截帧后对帧做黑帧检测：
- 采样 100 个像素点
- 亮度 < 15 判定为纯黑帧
- 纯黑帧自动跳过，尝试下一个时间点截帧
- 复用同一个 `MediaMetadataRetriever` 实例，避免重复创建开销

### 缩略图加载流程（ThumbnailCache + Coil）

1. 先查 `ThumbnailCache.getThumbnailFile()` 是否存在 → 存在则 Coil 加载缓存文件（`allowHardware(true)` GPU 渲染更快）
2. 不存在则 Coil 加载原始 URI（图片 `allowHardware(true)`，视频 VideoFrameDecoder `allowHardware(false)`）
3. 后台 `ThumbnailLoader` 解码并保存到 `ThumbnailCache`（下次加载走缓存）

### 预生成动态并发策略

所有扫描方法完成后，调用 `pregenerateThumbnails()` 后台预生成缩略图到本地文件缓存（`ThumbnailCache`）。

- **先检查本地缓存**：`ThumbnailCache.isThumbnailCached()` 已缓存的自动跳过
- **图片**：`ThumbnailLoader.loadImageThumbnail()` 解码 → `ThumbnailCache.writeThumbnail()` 保存为 WebP
- **视频**：`ThumbnailLoader.loadVideoThumbnail()` 三级策略 → `ThumbnailCache.writeThumbnail()` 保存为 WebP
- **动态并发**：根据设备 CPU 核数和运存自动调整（`getConcurrency`）
  - 统一并发 = CPU 核数 × 1.5（上限 16）
  - 图片视频共用并发池，图片优先排列确保快任务先完成
  - 设备总内存<4GB时减半（用`ActivityManager.MemoryInfo.totalMem`判断，非App堆上限`memoryClass`）
  - 高端 8 核设备实测：12 并发

### cache_version 机制

版本升级时自动清除视频缩略图缓存重新生成（黑帧检测逻辑变更后需要重新缓存）。

## 日期分组

### groupByDate 逻辑

`groupByDate(items)` 将媒体文件按修改日期分组：
1. 对每个文件调用 `dateLabel(it.modifiedAtMillis)` 生成分组标签
2. 按标签 `groupBy` 分组
3. 每组生成 `MediaDateGroup(label, groupItems)`

### dateLabel 规则

| 条件 | 标签 |
|---|---|
| 与今天同一天 | "今天" |
| 距今 1 天 | "昨天" |
| 距今 2~6 天 | "周日"/"周一"/.../"周六"（按目标日期的星期） |
| 更早 | "yyyy-MM-dd" 格式 |
| millis ≤ 0 | "未知日期" |

## 修改注意事项

- 推荐算法完全本地运行，不依赖网络。
- 排序缓存（`cachedSortedItems`）不可在用户主动操作外触发重排。
- 新增筛选条件需同步 `MediaFilterState`、`MediaBrowserLogic.applyFilter()`、`MediaFilterSheet`。
- 新增推荐参数需同步更新推荐偏好设置和降序排列逻辑。
- `balanceVideoImage()` 会打乱评分排序，修改时需权衡精度与多样性。
- 随机扰动基于 seed 的确定性随机，同 seed 可复现，不要改为纯随机。
- SourceMatcher 更新必须遵循第 5 章的更新规范。
- 缩略图解码策略变更时需同步更新 `cache_version`。

## 验收清单

> 最后更新：2026-06-21
