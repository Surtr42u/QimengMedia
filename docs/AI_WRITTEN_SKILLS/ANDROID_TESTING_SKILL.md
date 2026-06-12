# ANDROID_TESTING_SKILL - Android 测试与验证（项目工程约束）

## 来源与用途

本 Skill 是项目内自写通用 AI 参考规范，不是外部下载 Skill。仅保留本项目特有的工程约束，通用测试知识见外部 Skill `android-testing-unit`。

## 本项目工程约束

- 优先测试不依赖真实手机 UI 的核心逻辑：文件名记录键生成、重复文件名高级区分、作者 TXT 解析、相册出处最长匹配、推荐打分、JSON 导入导出、Room DAO
- 算法和解析器用普通 JVM 测试，不需要 Android Context 的逻辑不要写成仪器测试
- DAO 使用内存数据库测试，测试历史记录 500 条上限、作者和文件多对多关系
- 涉及 SAF、真实 Uri、视频播放、图片缩放、备份目录写入时，需要真机或模拟器验证
- 协程使用 test dispatcher；扫描取消要可验证；备份失败不能破坏数据库数据

## 与项目文档的关系

实施前继续读取：

- `docs/PROJECT_GUIDE.md`
- 当前任务相关 `docs/GUIDE_*.md`

> 最后更新：2026-06-06
