# 外部 Skill 下载来源

本文件记录 `app/ai-skills/external/` 下外部 Skill 的来源、许可证和用途。外部 Skill 默认只读，除非用户明确要求替换或修改。

## 下载时间

2026-05-29

## 来源清单

### awesome-android-agent-skills

- 本地路径：`app/ai-skills/external/awesome-android-agent-skills/`
- 来源：`https://github.com/new-silvermoon/awesome-android-agent-skills`
- 许可证：Apache-2.0
- 已保存文件：`Agent.md`、`README.md`、`LICENSE`
- 用途：现代 Android 开发通用 Agent 提示。

### aotocom-android-agent-skills

- 本地路径：`app/ai-skills/external/aotocom-android-agent-skills/`
- 来源：`https://github.com/Aotocom/android-agent-skills`
- 许可证：MIT
- 已保存文件：`README.md`、`LICENSE`、部分 `skills/*/SKILL.md`
- 用途：Android 架构、View System、Room、权限、媒体、协程、离线同步、安全、性能、测试等模块化 Skill。

已保存模块：

- `android-architecture-clean`
- `android-viewsystem-foundations`
- `android-room-database`
- `android-media-files-sharing`
- `android-permissions-activity-results`
- `android-coroutines-flow`
- `android-serialization-offline-sync`
- `android-security-best-practices`
- `android-performance-observability`
- `android-testing-unit`
- `android-testing-ui`
- `android-ui-states-validation`

### rshzrh-ui-design-skills

- 本地路径：`app/ai-skills/external/rshzrh-ui-design-skills/`
- 来源：`https://github.com/rshzrh/ui-design-skills`
- 许可证：MIT
- 已保存文件：`README.md`、`LICENSE`、`skills/ui-android/`
- 用途：Android UI 设计锚点、组件结构、动效、图标、文案和验证清单。

### android-lead-agent-skills

- 本地路径：`app/ai-skills/external/android-lead-agent-skills/`
- 来源：`https://github.com/ayush016/android-lead-agent-skills`
- 许可证：MIT
- 已保存文件：`README.md`、`LICENSE`、`SKILL.md`、部分 `references/`、`assets/ui-excellence-checklist.md`
- 用途：Android Lead 级工程、性能、UI 质量、主题、图片加载、安全、测试等参考。
- 注意：该 Skill 较偏 Compose。本项目若发生冲突，以原生 View + ViewBinding 的项目文档为准。

### claude-adb-skill

- 本地路径：`app/ai-skills/external/claude-adb-skill/`
- 来源：`https://github.com/pengdev/claude-adb-skill`
- 许可证：MIT
- 已保存文件：`README.md`、`LICENSE`、`SKILL.md`、`tools/`
- 用途：ADB 真机安装、截图、logcat、UI 操作和验证。
- 注意：`tools/` 下包含外部脚本，只有在明确需要真机自动化验证时才执行。

## 冲突规则

外部 Skill 是通用约束，不一定完全适合本项目。如果外部 Skill 与以下内容冲突，优先级从高到低为：

1. 用户最新要求。
2. `docs/PROJECT_GUIDE.md`。
3. 项目专属 `docs/GUIDE_*.md`。
4. `docs/AI_WRITTEN_SKILLS/*.md`。
5. 本目录外部 Skill。
