# AGENTS - 绮梦影库 AI 代理规则

本文件是 AI 代理规则的快速索引。**完整规则见 `AI_README_FIRST.md`**（读取顺序、源码隔离、代码规范优先、回复签名、文档维护、删除规则、新增模块流程、工具调用增强、代码自审）。

本文件仅收录 `AI_README_FIRST.md` 未覆盖的独有规则：git 提交约束、构建验证规则、禁止行为清单、冲突优先级。

修改任何代码或文档前，必须先读取 `AI_README_FIRST.md`。

## git 提交约束（强制）

修改代码时，**代码和文档必须在同一个 commit 中**。不允许代码和文档分两次提交。

commit message 格式：`类型(模块): 简述 | 文档: 已更新XXX`

示例：
```
fix(data): 删除死代码resetStats/clearUsageData | 文档: GUIDE_DATA.md, PROJECT_GUIDE.md
feat(ui): 详情页加倍速按钮 | 文档: GUIDE_UI.md, GUIDE_THEME.md
```

**多 AI 协作**：提交前必须 `git pull`。如果发现自己的修改会覆盖别人的文档改动，先合并再提交。不应该出现两个 commit 交替修改同一个文件。

## 构建验证规则

AI 运行 `gradlew assembleDebug` 等构建命令后，**必须执行 `gradlew --stop` 停止 Gradle daemon**。原因：Windows 上命令行 Gradle daemon 会持有文件锁，导致 Android Studio 点击运行时报错（daemon 冲突、构建锁冲突）。停止 daemon 不影响下次构建速度（Android Studio 会自动启动自己的 daemon）。

## 禁止行为

- 禁止跳过指南直接写代码。
- 禁止修改代码后回复末尾不加 `📋` 已读取文档签名。
- 禁止在签名中列入未实际读取的文档。
- 禁止通读完整源码文件来理解项目。
- 禁止修改 `app/ai-skills/external/` 下外部 Skill 原文，除非用户明确要求。
- 禁止新增功能后不更新项目文档。
- 禁止删除功能时只删 UI 层而保留底层实现代码和文档。
- 禁止修改、删除、移动用户原始媒体文件。
- 禁止凭模糊记忆编写不熟悉的 API/框架代码，必须先搜索确认。
- 禁止照搬外部搜索到的代码片段，必须适配项目架构后重写。
- 禁止使用低质量信息源（复制粘贴站、无出处代码、未标明版本的教程）。
- 禁止完成代码修改后跳过自审直接回复用户。
- 禁止在项目文档中写入真实设备型号/GPU/CPU 具体型号、真实设备序列号、真机调试数据快照（某次实测的精确文件数/误报数/阻塞毫秒值）、用户图库真实文件统计或真实用户截图。完成调试并将结果写入文档时必须泛化为通用描述（如"旗舰 GPU 实测可达 16384"、"数千文件负载"、"阻塞约 6 秒"），详见 `docs/GUIDE_DEBUG.md`「修改注意事项」之敏感隐私内容清理。

## 冲突优先级

1. 用户最新要求。
2. `docs/PROJECT_GUIDE.md`。
3. `docs/GUIDE_*.md`。
4. `docs/GUIDE_USAGE_MATRIX.md`（触发规则定义）。
5. `docs/AI_WRITTEN_SKILLS/*.md`。
6. `app/ai-skills/external/*`。

如果发现自己没有读取当前任务对应指南，必须停止实现并补读。
