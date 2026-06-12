# 外部导入 Skill 放置区

本目录只放用户真正下载、复制或手动导入的外部 Skill 原文。

当前目录已放入 Android 开发和 UI 相关外部 Skill，来源和许可证见 `DOWNLOADED_SOURCES.md`。

## 可放入的外部 Skill 类型

- UI 设计 Skill
- Android View Skill
- Android 架构 Skill
- Android 存储/SAF Skill
- Room 数据库 Skill
- Kotlin 协程 Skill
- 媒体播放/图片加载 Skill

这些文件必须由用户放入，AI 不应把自己编写的 Skill 当成外部 Skill 放到这里。

## 当前目录

- `awesome-android-agent-skills/`：Android Agent 通用提示，Apache-2.0。
- `aotocom-android-agent-skills/`：Android 架构、View、Room、权限、媒体、测试等模块化 Skill，MIT。
- `rshzrh-ui-design-skills/`：Android UI 设计 Skill，MIT。
- `android-lead-agent-skills/`：Android Lead 工程 Skill，MIT。
- `claude-adb-skill/`：ADB 真机验证 Skill，MIT。

## 使用注意

- 本项目当前技术路线是原生 View + ViewBinding，不是 Compose。
- 如果外部 Skill 中出现 Compose 优先、网络优先、targetSdk 不同等建议，以 `docs/PROJECT_GUIDE.md` 和项目专属 `docs/GUIDE_*.md` 为准。
- `claude-adb-skill/tools/` 包含外部下载的脚本。未明确需要真机自动化验证时，不主动执行这些脚本。

## 修改规则

- 本目录文件默认禁止修改。
- 只有用户明确要求“修改外部 Skill”或“替换外部 Skill”时才允许改。
- 本目录文件不打进 APK。
- 换 AI 或换开发工具时，必须随完整源码目录一起交接。

## 冲突处理

如果本目录 Skill 与用户最新需求或 `docs/PROJECT_GUIDE.md` 冲突，以用户最新需求和项目专属文档为准。
