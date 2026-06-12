# GUIDE_README - 项目指南索引与快速导航

本目录的 `GUIDE_*.md` 是绮梦影库项目专属工程指南文档。

## 三类文档的区别

| 类型 | 位置 | 性质 | 修改权限 |
|---|---|---|---|
| 外部下载 Skill | `app/ai-skills/external/` | 第三方通用规范 | 只读，禁止修改 |
| 自写通用 Skill | `docs/AI_WRITTEN_SKILLS/` | 项目内自写通用规范 | 可随项目维护 |
| 项目专属指南 | `docs/GUIDE_*.md` | 绮梦影库专属指南 | 必须与代码同步 |

## 模块索引（按关注领域）

| 文档 | 管什么 | 核心内容 |
|---|---|---|
| `GUIDE_UI.md` | 所有页面的文件路径、布局结构、导航行为、RecyclerView 适配器 | 详情页叠加模式、首页芯片栏、全部页日期分组、筛选面板 |
| `GUIDE_DATA.md` | Room 实体、DAO、主键策略、统计规则、收藏、备份 JSON 格式、UseCase 层 | 11 个实体、10 个 DAO、4 个 UseCase、完整文件名主键、7 个 JSON 文件格式 |
| `GUIDE_THEME.md` | 9 个 `?attr/qmColor*` 颜色属性、系统白天/夜间两套主题、代码中使用方式 | `values` / `values-night`、`ThemeHelper.resolve()`、系统栏明暗同步 |
| `GUIDE_SCAN.md` | SAF 授权目录、双引擎扫描（MediaStore+SAF）、两套扫描体系、COS 路径解析 | MediaStore 优先 + SAF 回退、scanTreeFast、按需元数据解码 |
| `GUIDE_ALGORITHM.md` | 推荐公式 10 维权重、排行榜周期、筛选面板全字段、排序缓存 | tagRelevance 0.22 + engagement 0.15 + tagCollection 0.15 + discovery 0.15 等 |
| `GUIDE_BACKUP.md` | 备份目录管理、导入导出流程图、数据管理弹层功能 | BackupManager、SAF 目录选择、自动同步 |
| `GUIDE_AUTHOR.md` | 作者管理 UI 入口、TXT 解析规则、COS 目录结构 | 常规作者 vs COS 作者、TXT 格式示例、COS 三种目录结构 |
| `GUIDE_ANIMATION.md` | 已实现动效列表、待实现动效清单、动效原则 | 详情页淡入淡出、系统栏显隐、明暗模式 |
| `GUIDE_ANDROID_COMPAT.md` | Android 16+ 兼容策略、新系统 10 项检查清单 | SAF 持久授权、targetSdk 升级流程 |
| `GUIDE_DEBUG.md` | 真机调试流程、AppLog 文件日志、ADB 诊断模式 | Android 16 logcat 不可读、run-as 读取日志、闪退/卡死诊断 |
| `GUIDE_CODE_MAINTENANCE.md` | 代码阅读策略、静态分析工具、单元测试、重构指南、编码规范 | Grep+精准读取、Detekt、10维评分测试、UseCase 拆分规范 |

## 你想做什么？（快速导航）

### 我想改/加一个页面
→ `GUIDE_UI.md`（看页面结构） → `GUIDE_THEME.md`（颜色规则） → `ANDROID_VIEW_SKILL.md`（View/Adapter 规范）

### 我想改主题或颜色
→ `GUIDE_THEME.md`（9 个属性 + 代码示例） → `GUIDE_UI.md`（确保各页面统一）

### 我想加/改数据库字段
→ `GUIDE_DATA.md`（实体列表 + JSON 格式） → `ANDROID_ROOM_SKILL.md`（Migration 规范） → `GUIDE_BACKUP.md`（确认导入导出流程需不需要改）

### 我想改推荐/排序逻辑
→ `GUIDE_ALGORITHM.md`（公式 + 缓存） → `GUIDE_DATA.md`（统计规则）

### 我想改扫描逻辑
→ `GUIDE_SCAN.md`（SAF + 格式 + 字段） → `ANDROID_STORAGE_SAF_SKILL.md`（通用规范） → `GUIDE_ANDROID_COMPAT.md`（确认权限不变）

### 我想改备份/导入导出
→ `GUIDE_BACKUP.md`（流程 + JSON 规范） → `GUIDE_DATA.md`（JSON 格式定义）

### 我想改作者管理
→ `GUIDE_AUTHOR.md`（UI + TXT 规则） → `GUIDE_DATA.md`（实体定义） → `GUIDE_BACKUP.md`（确认 JSON 同步）

### App 太卡了
→ `ANDROID_PERFORMANCE_SKILL.md`（通用优化） → `GUIDE_SCAN.md`（扫描性能） → `GUIDE_UI.md`（列表性能）

### 闪退了/卡死了/需要看日志
→ `GUIDE_DEBUG.md`（AppLog + ADB 诊断） → `GUIDE_SCAN.md`（扫描相关问题时）

### 换新 Android 系统了
→ `GUIDE_ANDROID_COMPAT.md`（10 项检查 + 适配流程） → `GUIDE_SCAN.md`

### 我要删一个功能
→ 读 `AI_README_FIRST.md` 的《删除功能的强制规则》 → 逐层删代码（UI→VM→Repo→DAO） → 同步更新 `PROJECT_GUIDE.md` + 对应 `GUIDE_*.md`

### 我要加一个全新模块（现有文档覆盖不了）
→ 读 `AI_README_FIRST.md` 的《新增功能模块的文档流程》 → 复制 `docs/_GUIDE_TEMPLATE.md` → 按 7 步清单执行

## 修改后必须同步文档

新增或修改功能 → 更新 `PROJECT_GUIDE.md` + 对应 `GUIDE_*.md`

删除功能 → **必须**同步删除代码和文档，映射关系见 `AI_README_FIRST.md` 的《文档维护规则》和《删除功能的强制规则》

所有人（AI 或程序员）改代码前请先读 `AGENTS.md` 和 `AI_README_FIRST.md`。

> 最后更新：2026-06-06
