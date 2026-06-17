# AI_README_FIRST - AI 接手强制阅读规则

任何 AI、代码助手或开发者在修改绮梦影库项目前，必须先读本文件。

## 你是哪种情况？

### 首次接手项目（以前没做过这个项目）

按《总读取顺序》完整走一遍（约 14 步），建好全局认知。

### 日常任务（已经了解项目，来做具体修改）

1. **读 `docs/GUIDE_CODE_MAINTENANCE.md`**（代码规范是写代码的前提，必须先于功能指南阅读）
2. 打开 `docs/GUIDE_USAGE_MATRIX.md`，匹配用户请求的触发词
3. 只读命中的 Skill 和文档
4. 基于文档定位代码片段修改
5. 改完后更新对应文档 + 回复末尾加 `📋` 签名

## 总读取顺序（首次接手用）

1. `README.md`
2. `AI_README_FIRST.md`
3. `AGENTS.md`
4. **`docs/GUIDE_CODE_MAINTENANCE.md`**（代码规范第一要务，必须在功能指南之前阅读）
5. `docs/GUIDE_USAGE_MATRIX.md`
6. `app/ai-skills/README.md`
7. `app/ai-skills/external/README.md`
8. `app/ai-skills/external/DOWNLOADED_SOURCES.md`
9. 与任务相关的外部 Skill
10. `docs/AI_WRITTEN_SKILLS/README.md`
11. 与任务相关的 `docs/AI_WRITTEN_SKILLS/*.md`
12. `docs/PROJECT_GUIDE.md`
13. `docs/GUIDE_README.md`
14. 与任务相关的 `docs/GUIDE_*.md`
15. 对应源码（仅限本文档允许的片段读取，禁止全文通读）

完整任务触发矩阵见 `docs/GUIDE_USAGE_MATRIX.md`。

## 源码隔离原则

AI 代理理解项目时，**禁止直接通读完整源码文件**。必须通过以下文档体系理解项目：

- `README.md` + `docs/PROJECT_GUIDE.md`：项目全貌、架构、页面结构、目录
- `docs/GUIDE_*.md`：各模块详细规则、数据流、文件职责
- `docs/AI_WRITTEN_SKILLS/*.md`：通用开发规范和参考

仅在以下情况才允许读取源码文件：

- 修改现有代码的具体实现细节（方法签名、变量名、逻辑修正）
- 文档中未覆盖的代码段，且必须**只读目标相关片段**，禁止全文通读
- 读取后如果发现文档未记录的信息，必须先更新文档再改代码

修改代码时，应该基于文档中对模块和文件职责的描述进行修改，而不是逐行通读源码后重写。

## 代码规范优先原则（强制）

**代码规范是所有 AI 代理接手项目的第一要务。** 在阅读任何功能模块的指南之前，必须先读 `docs/GUIDE_CODE_MAINTENANCE.md`。

### 为什么？

- 代码规范是"怎么写代码"的规则，功能指南是"写什么代码"的规则
- 不遵守代码规范的代码，即使功能正确，也会在维护时产生回归 bug
- 代码重复、方法过长、职责不清是项目腐化的根源
- 先看规范再写功能，比写完再改成本低 10 倍

### 强制规则

1. 首次接手项目时，`docs/GUIDE_CODE_MAINTENANCE.md` 必须在功能指南之前阅读
2. 日常任务时，写代码前必须先确认代码规范（共享逻辑、Fragment 拆分、方法长度等）
3. 新增逻辑时，先检查是否已有共享组件可复用，禁止复制粘贴
4. 修改 Fragment 时，若会进入黄色警戒区(>600 行)，需评估是否为合理复杂度（数据汇总/手势回调/扫描编排等），必要时规划拆分并在 `docs/GUIDE_CODE_MAINTENANCE.md` §8 备案

## 回复签名（强制）

**每次完成代码修改任务后，AI 必须在回复末尾签上本次实际读取过的文档清单。** 

这是用户判断项目文档体系是否起效的唯一手段。如果回复末尾没有签名行，说明 AI 跳过了文档，用户应要求其补读。

格式要求：
- 回复末尾另起一行，以 `📋 已读取文档：` 开头
- 列出**本次确实读取过的**文档文件名（不含冗长路径前缀）
- 未读取的文档不得列入
- 纯对话/回答问题/不做代码修改时不需要签名

示例：
```
📋 已读取文档：GUIDE_UI.md, GUIDE_THEME.md, GUIDE_ANIMATION.md
```

含外部 Skill 时：
```
📋 已读取文档：rshzrh-ui/SKILL.md, ANDROID_UI_DESIGN_SKILL.md, GUIDE_UI.md, GUIDE_THEME.md
```

## 文档维护规则

项目文档是 AI 理解项目的唯一入口，数据和规则必须保持与代码同步：

| 代码变更类型 | 必须同步的文档 |
|---|---|
| 新增/删除/移动文件 | `docs/PROJECT_GUIDE.md` 目录结构 + 实现进度 |
| 新增功能模块 | 按本文《新增功能模块的文档流程》7 步执行 |
| 修改页面行为/布局 | `docs/GUIDE_UI.md` |
| 修改数据实体/DAO/策略 | `docs/GUIDE_DATA.md`（含 JSON 格式定义）；如导入导出流程变更，同步 `docs/GUIDE_BACKUP.md` |
| 修改主题/颜色/字体 | `docs/GUIDE_THEME.md` |
| 修改扫描/SAF 逻辑 | `docs/GUIDE_SCAN.md` |
| 修改推荐/排行算法 | `docs/GUIDE_ALGORITHM.md` |
| 修改作者管理 | `docs/GUIDE_AUTHOR.md` |
| 修改动画/转场 | `docs/GUIDE_ANIMATION.md` |
| 修改系统兼容逻辑 | `docs/GUIDE_ANDROID_COMPAT.md` |
| 修改备份/导入导出 | `docs/GUIDE_BACKUP.md` |
| 修改调试/日志/ADB | `docs/GUIDE_DEBUG.md` |
| 修改代码结构/重构/测试 | `docs/GUIDE_CODE_MAINTENANCE.md` |
| 新任务触发关系 | `docs/GUIDE_USAGE_MATRIX.md` |
| 影响通用开发规范 | `docs/AI_WRITTEN_SKILLS/*.md` |
| 影响外部 Skill 使用说明 | `app/ai-skills/README.md` 或 `app/ai-skills/external/README.md`（不修改外部 Skill 原文） |

文档更新优先级：先更新 `docs/PROJECT_GUIDE.md`，再更新对应 `docs/GUIDE_*.md`，最后如有需要再更新 `docs/AI_WRITTEN_SKILLS/*.md`。

如果发现文档描述与代码实际情况不符，必须先确认哪个是正确版本，再统一为正确版本。

## 删除功能的强制规则

**删除一个功能或 UI 入口时，禁止只删 UI 层而保留底层代码和文档。** 必须同时完成：

1. **删代码**：从 UI → ViewModel → Repository 接口 → Repository 实现 → DAO（无其他调用者时）逐层清理，不留死代码
2. **删文档**：检查 `docs/PROJECT_GUIDE.md`、对应 `docs/GUIDE_*.md`、`docs/DEVELOPMENT_PLAN.md` 中对该功能的所有描述
3. **验证**：确认无其他代码引用被删除的方法，文档无残留描述

出现"UI 已删但底层代码/文档还在"属于不可接受的错误。

## 新增功能模块的文档流程

**当一个新功能不属于任何已有 GUIDE_*.md 的覆盖范围时，AI 必须自行建立文档骨架，不能让新功能成为"无文档功能"。**

### 判断标准：什么时候需要新建指南？

如果新功能同时满足以下两条，必须新建 `docs/GUIDE_XXX.md`：

- 有独立的 UI 入口（新的 Fragment/Activity/页面）
- 有独立的 Room 实体/DAO 或独立的算法/规则逻辑

如果只是对现有模块的扩展（如详情页加个按钮），只需更新已有的指南。

### 新建模块必须完成的 7 件事

| 步骤 | 做什么 | 产出 |
|---|---|---|
| 1 | 建项目专属指南 | 创建 `docs/GUIDE_XXX.md`，参照 `docs/_GUIDE_TEMPLATE.md` 模板 |
| 2 | 如涉及新技术领域，建自写 Skill | 创建 `docs/AI_WRITTEN_SKILLS/NEW_SKILL.md`（可选，不是必做） |
| 3 | 更新项目总览 | `docs/PROJECT_GUIDE.md`：实现进度加一行 + 目录结构加对应文件 |
| 4 | 更新触发矩阵 | `docs/GUIDE_USAGE_MATRIX.md`：新增触发词节 + 更新复合触发示例表 |
| 5 | 更新模块索引 | `docs/GUIDE_README.md`：模块索引表加一行 + 快速导航加场景 |
| 6 | 更新维护规则表 | 本文档《文档维护规则》表格加新行 |
| 7 | 更新开发计划 | `docs/DEVELOPMENT_PLAN.md`：新增阶段或阶段内新增待办项 |

### GUIDE_XXX.md 最少要写什么

参照 `docs/_GUIDE_TEMPLATE.md`，最少包含：

1. **实现路径**：涉及哪些源文件，各管什么
2. **职责边界**：这个模块管什么、不管什么
3. **数据/规则**：涉及哪些实体、算法、策略
4. **修改注意事项**：动这个模块时必须同步哪些其他文档
5. **时间戳**：最后更新日期

如果功能还有备份/主题关联，也要写清楚哪些数据要进 JSON、哪些颜色要用 `?attr/qmColor*`。

## 任务级触发规则

所有任务的指南触发关系必须以 `docs/GUIDE_USAGE_MATRIX.md` 为唯一权威来源。本文件不再单独列出高频任务清单。

任务发生时，按以下步骤查矩阵：

1. 打开 `docs/GUIDE_USAGE_MATRIX.md`
2. 根据用户请求的语义匹配触发词（矩阵已覆盖大量口语/大白话表达）
3. 读取命中的所有外部 Skill、自写 Skill、项目专属指南
4. 如果用户请求不在任何已知触发词范围内，也至少读 `docs/PROJECT_GUIDE.md` + `docs/GUIDE_README.md`

## 工具调用增强原则

AI 代理在实现功能、修复 Bug、设计方案时，必须积极调用可用工具（联网搜索、网页抓取等）获取外部信息，**禁止闭门造车**。

### 何时必须调用工具

| 场景 | 应调用的工具 | 目的 |
|---|---|---|
| 不熟悉的 API / 框架用法 | WebSearch → WebFetch | 确认 API 签名、参数、行为 |
| 平台兼容性问题（版本差异、厂商适配） | WebSearch | 查找官方兼容性说明和已知 Issue |
| 性能优化方案选择 | WebSearch | 对比多种方案的性能数据和社区反馈 |
| 非显而易见的 Bug | WebSearch | 搜索该问题的已知原因和社区解决方案 |
| 多种实现方案可选 | WebSearch + WebFetch | 对比优缺点、兼容性风险后选择最优方案 |
| 第三方库升级 / 新依赖引入 | WebSearch → WebFetch | 确认版本兼容性、迁移指南、Breaking Changes |

### 信息源质量分级

| 等级 | 来源 | 使用方式 |
|---|---|---|
| **优先使用** | Android Developers 官方文档、Kotlin 官方文档、GitHub 官方仓库 Issue/Release、Jetpack 组件源码注释 | 直接参考，高可信度 |
| **可参考** | StackOverflow 高票回答（被采纳的）、Medium/Dev.to 知名开发者文章 | 交叉验证后采用 |
| **禁止使用** | 来路不明的博客复制粘贴站、无出处代码片段、未标明版本的过时教程、可能植入恶意代码的第三方下载站 | 不得采纳 |

### 外部代码使用规则

从搜索结果中获取的代码片段**只能作为理解思路和 API 用法的参考**，禁止照搬。必须：

1. 根据项目现有架构（ViewModel → Repository → DAO）进行适配
2. 遵循项目命名规范和数据模型
3. 确保与项目代码风格一致
4. 不得引入项目未使用的第三方依赖

## 代码自审（强制）

**每次完成代码修改、准备向用户回复之前，AI 必须对本次所有代码变更进行一次自审。** 这是防止低级错误流入用户设备的最后防线。

### 自审清单

| 审查项 | 检查内容 |
|---|---|
| **逻辑正确性** | 修改是否真正解决了问题？边界条件是否遗漏？空指针/越界风险？ |
| **线程安全** | 数据库操作是否在 `Dispatchers.IO`？UI 更新是否在主线程？并发竞态？ |
| **性能影响** | 是否引入了全量加载（`getAll()`）？主线程耗时操作？大数据集 OOM 风险？ |
| **架构一致性** | 是否遵循 ViewModel → Repository → DAO 分层？命名风格是否统一？ |
| **副作用** | 修改是否影响了其他模块？是否需要同步更新其他文件？ |
| **文档同步** | 是否已更新对应的 `docs/GUIDE_*.md` 和 `docs/PROJECT_GUIDE.md`？ |

### 自审结果处理

- 自审发现问题 → **必须先修复再回复用户**
- 不得将已知问题留给用户发现
- 如果修复引入新问题，重新走自审流程

## 禁止跳过指南

如果任务明显属于某个模块，但 AI 没有先读取对应指南，应该停止实现并先补读指南。

如果任务不在已知模块范围内，也必须去 `docs/GUIDE_USAGE_MATRIX.md` 查找对应模块。
