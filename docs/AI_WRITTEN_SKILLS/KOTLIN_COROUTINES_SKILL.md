# KOTLIN_COROUTINES_SKILL - Kotlin 协程与 Flow（项目工程约束）

## 来源与用途

本 Skill 是项目内自写通用 AI 参考规范，不是外部下载 Skill。仅保留本项目特有的工程约束，通用协程知识见外部 Skill `android-coroutines-flow`。

## 本项目工程约束

- 扫描、导入、导出和推荐计算必须支持取消
- 用户取消扫描时要停止后续索引写入
- 备份失败不能影响原始媒体文件
- 搜索输入使用 debounce，避免每个字符都触发重查询
- 错误要转成用户可理解的状态，不让 App 直接崩溃

## 与项目文档的关系

实施前继续读取：

- `docs/GUIDE_SCAN.md`
- `docs/GUIDE_BACKUP.md`
- `docs/GUIDE_DATA.md`

> 最后更新：2026-06-06
