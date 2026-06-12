# ANDROID_STORAGE_SAF_SKILL - Android 存储与 SAF（项目工程约束）

## 来源与用途

本 Skill 是项目内自写通用 AI 参考规范，不是外部下载 Skill。仅保留本项目特有的工程约束，通用 SAF 知识见外部 Skill `android-permissions-activity-results`。

## 本项目工程约束

- 原始媒体文件只读，不创建、不删除、不重命名媒体文件
- 备份目录和媒体目录是两个概念，即使用户选到同一文件夹，也只能写 App 备份 JSON，不得修改媒体文件
- 不依赖传统绝对路径作为主要访问方式
- 持久化保存 Uri 授权，不要求用户每次重新选择
- 不假设 Uri 永久有效，失效时提示重新授权
- Android 16+ 仍优先 SAF，不依赖 READ_EXTERNAL_STORAGE 旧逻辑

## 与项目文档的关系

实施前继续读取：

- `docs/GUIDE_SCAN.md`
- `docs/GUIDE_BACKUP.md`
- `docs/GUIDE_ANDROID_COMPAT.md`

> 最后更新：2026-06-06
