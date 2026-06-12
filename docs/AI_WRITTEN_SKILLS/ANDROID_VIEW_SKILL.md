# ANDROID_VIEW_SKILL - Android View 与 ViewBinding（项目工程约束）

## 来源与用途

本 Skill 是项目内自写通用 AI 参考规范，不是外部下载 Skill。仅保留本项目特有的工程约束，通用 View/ViewBinding 知识见外部 Skill `android-viewsystem-foundations`。

## 本项目工程约束

- 使用原生 View + XML + ViewBinding，不主动引入 Compose
- Fragment 不直接访问数据库，不直接执行长耗时扫描
- 不把业务逻辑写进 Adapter
- 导航事件要集中处理，不在多个点击事件里散落写跳转
- 页面颜色和文字颜色不硬编码，使用主题系统或资源引用
- 媒体卡片只绑定当前 item 数据，不做数据库查询

## 与项目文档的关系

实施前继续读取：

- `docs/GUIDE_UI.md`
- `docs/GUIDE_THEME.md`
- 涉及数据时读取 `docs/GUIDE_DATA.md`

> 最后更新：2026-06-06
