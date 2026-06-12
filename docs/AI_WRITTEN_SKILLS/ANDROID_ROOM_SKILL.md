# ANDROID_ROOM_SKILL - Room 数据库（项目工程约束）

## 来源与用途

本 Skill 是项目内自写通用 AI 参考规范，不是外部下载 Skill。仅保留本项目特有的工程约束，通用 Room 知识见外部 Skill `android-room-database`。

## 本项目工程约束

- 删除操作必须明确业务含义，不能误删媒体索引外的数据
- Migration 必须兼容 JSON 导入导出格式
- 数据库版本升级必须同步项目文档
- Repository 负责触发备份自动同步
- Repository 负责把扫描结果合并进媒体索引
- Repository 负责历史上限、统计规则、作者关联规则
- 媒体列表查询避免一次加载无关大字段（如正文内容、大文本字段）

## 与项目文档的关系

实施前继续读取：

- `docs/GUIDE_DATA.md`
- `docs/GUIDE_BACKUP.md`
- `docs/GUIDE_AUTHOR.md`

> 最后更新：2026-06-06
