# ANDROID_PERFORMANCE_SKILL - Android 性能（项目工程约束）

## 来源与用途

本 Skill 是项目内自写通用 AI 参考规范，不是外部下载 Skill。仅保留本项目特有的工程约束，通用性能知识见外部 Skill `android-performance-observability`。

## 本项目工程约束

- 媒体列表分页或分批加载，不在 onBindViewHolder 做数据库查询、文件 IO 或复杂计算
- 滚动中避免启动大量视频首帧生成
- 大目录扫描分批写入数据库，扫描进度可显示，不阻塞 UI
- 毛玻璃效果要有降级方案
- 主题切换避免重建全部复杂资源
- 视频播放页优先保证播放流畅
- 备份和推荐不会长时间占用主线程

## 与项目文档的关系

实施前继续读取：

- `docs/GUIDE_UI.md`
- `docs/GUIDE_SCAN.md`
- `docs/GUIDE_DATA.md`
- `docs/GUIDE_ANIMATION.md`

> 最后更新：2026-06-06
