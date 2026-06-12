# AI_WRITTEN_SKILLS - 项目内自写通用 AI 参考规范

本目录保存由本项目开发过程中整理的通用 Android/Kotlin AI 参考规范。

## 重要边界

- 这里的文件不是网上下载的外部 Skill 原文。
- 这里的文件由项目维护者或 AI 根据公开资料和项目目标整理。
- 这些文件属于项目内文档，可随项目需要修改。
- 真正外部下载或用户导入的 Skill 只能放在 `app/ai-skills/external/`。

## 精简原则

自写 Skill **只保留本项目特有的工程约束**（B 类），不重复以下内容：

- **A 类（通用知识）**：外部 Skill 已覆盖的通用 Android/Kotlin 知识，不重复写。自写 Skill 中用"通用 XX 知识见外部 Skill `xxx`"指向。
- **C 类（GUIDE 已有）**：项目专属业务规则已在 `docs/GUIDE_*.md` 中描述，不重复写。自写 Skill 中用"详见 GUIDE_XXX.md"指向。

这样自写 Skill 保持精简，避免三层文档之间内容重复。

## 与 docs/GUIDE_*.md 的关系

- `AI_WRITTEN_SKILLS/`：本项目特有的工程约束（通用 Android 技术在本项目的特殊用法/限制），例如"不用 Compose"、"原始媒体文件只读"、"Migration 必须兼容 JSON 导出"。
- `docs/GUIDE_*.md`：绮梦影库项目专属指南，例如作者管理、相册分类、备份格式、主题字段。
- **B 类约束同时存在于两处**：自写 Skill 保留原文，对应 GUIDE 的「修改注意事项」中也有副本。修改时必须同步两处。

## 强制同步规则

**本目录文件必须与对应 `docs/GUIDE_*.md` 保持同步。** 修改下列组合中任一文件时，必须检查另一文件是否也要更新：

| 自写 Skill | 对应的项目专属指南 | 关系 |
|---|---|---|
| `ANDROID_ARCHITECTURE_SKILL.md` | `PROJECT_GUIDE.md`（架构层描述） | 架构约束落地于项目结构 |
| `ANDROID_VIEW_SKILL.md` | `GUIDE_UI.md` | View 约束落地于具体页面 |
| `ANDROID_ROOM_SKILL.md` | `GUIDE_DATA.md` | Room 约束落地于具体实体/DAO |
| `ANDROID_STORAGE_SAF_SKILL.md` | `GUIDE_SCAN.md` | SAF 约束落地于扫描实现 |
| `ANDROID_UI_DESIGN_SKILL.md` | `GUIDE_UI.md` + `GUIDE_THEME.md` | 设计约束落地于页面和主题 |
| `ANDROID_MEDIA_SKILL.md` | `GUIDE_SCAN.md` + `GUIDE_UI.md` | 媒体约束落地于扫描和详情页 |
| `ANDROID_PERFORMANCE_SKILL.md` | `GUIDE_UI.md` + `GUIDE_SCAN.md` | 性能约束落地于列表和扫描 |
| `KOTLIN_COROUTINES_SKILL.md` | `GUIDE_SCAN.md` | 协程约束落地于扫描异步逻辑 |
| `ANDROID_TESTING_SKILL.md` | `GUIDE_DEBUG.md` | 测试约束落地于调试流程 |

## 当前文件（9 个）

- `ANDROID_ARCHITECTURE_SKILL.md`
- `ANDROID_VIEW_SKILL.md`
- `ANDROID_ROOM_SKILL.md`
- `ANDROID_STORAGE_SAF_SKILL.md`
- `ANDROID_UI_DESIGN_SKILL.md`
- `ANDROID_MEDIA_SKILL.md`
- `ANDROID_PERFORMANCE_SKILL.md`
- `KOTLIN_COROUTINES_SKILL.md`
- `ANDROID_TESTING_SKILL.md`

> 最后更新：2026-06-06
