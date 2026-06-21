# ANDROID_ARCHITECTURE_SKILL - Android 架构分层（项目工程约束）

## 来源与用途

本 Skill 是项目内自写通用 AI 参考规范，不是外部下载 Skill。仅保留本项目特有的工程约束，通用架构知识见外部 Skill `android-architecture-clean`。

## 本项目工程约束

- 推荐分层：ui/、data/、scan/、domain/、backup/、core/
- data 不依赖 ui；推荐算法（`MediaBrowserLogic`，位于 `ui/browser`）不直接依赖 Fragment；backup 不直接操作原始媒体文件
- ViewModel 暴露 UI state，不暴露数据库 Entity 给 UI 直接修改
- Repository 负责协调 DAO、扫描结果和备份同步
- Repository 方法命名表达业务动作，例如 recordVideoPlay、assignAuthorToMedia
- 列表、加载、空状态、错误状态统一建模，不用多个零散 Boolean 表示复杂页面状态

## 与项目文档的关系

架构设计完成后继续读取：

- `docs/PROJECT_GUIDE.md`
- `docs/GUIDE_DATA.md`
- `docs/GUIDE_UI.md`

> 最后更新：2026-06-06
