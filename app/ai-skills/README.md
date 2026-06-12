# AI Skill 读取规则

本目录只保存用户真正下载或手动导入的外部 Skill，用于让不同 AI 或开发工具在接手项目前先读取通用约束原文。

## 读取顺序

1. 项目根目录 `README.md`
2. `AI_README_FIRST.md`
3. `AGENTS.md`
4. `docs/GUIDE_USAGE_MATRIX.md`
5. 本文件 `app/ai-skills/README.md`
6. `app/ai-skills/external/*.md`，如果任务命中外部 Skill
7. `docs/AI_WRITTEN_SKILLS/README.md`
8. 与当前任务相关的 `docs/AI_WRITTEN_SKILLS/*.md`
9. `docs/PROJECT_GUIDE.md`
10. `docs/GUIDE_README.md`
11. 与当前任务相关的 `docs/GUIDE_*.md`
12. 对应源码目录

任务级强制阅读矩阵见 `docs/GUIDE_USAGE_MATRIX.md`。

## 外部 Skill 修改规则

- `external/` 下的文件必须来自用户手动放入或网上下载。
- 这些文件默认只读，AI 禁止主动修改。
- 除非用户明确说“修改外部 Skill”或“替换外部 Skill”，否则只能读取。
- 外部 Skill 不参与 APK 打包，只作为源码交接资料保存。

## 当前外部 Skill 状态

- 当前已下载 Android 开发和 UI 相关外部 Skill，详见 `app/ai-skills/external/DOWNLOADED_SOURCES.md`。
- 外部 Skill 来源包括 Apache-2.0 或 MIT 许可证仓库。
- AI 自写的通用参考规范不放这里，已放入 `docs/AI_WRITTEN_SKILLS/`。
- 如果用户之后继续下载外部 Skill，把 `.md` 文件或 Skill 目录放入 `app/ai-skills/external/`。

## 冲突处理

如果外部 Skill 与用户最新需求或 `docs/PROJECT_GUIDE.md` 冲突，以用户最新需求和 `docs/PROJECT_GUIDE.md` 为准。
