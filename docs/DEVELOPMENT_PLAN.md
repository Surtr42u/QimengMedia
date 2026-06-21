# DEVELOPMENT_PLAN - 开发计划

本文件只记录各阶段的**目标与当前状态**，具体实现细节见 `PROJECT_GUIDE.md`「当前实现进度」。

## 阶段 0：AI Skill 与开发文档体系

- 目标：建立 AI 接手规则、外部/自写 Skill 体系、项目专属指南。
- 状态：已完成。`README.md` / `AI_README_FIRST.md` / `AGENTS.md` / `CLAUDE.md` / `GEMINI.md` / `.github/copilot-instructions.md` 就位；`app/ai-skills/external/` 放外部下载 Skill（只读），`docs/AI_WRITTEN_SKILLS/` 放自写通用规范，`docs/GUIDE_*.md` 放项目专属指南，`docs/GUIDE_USAGE_MATRIX.md` 为任务触发矩阵。

## 阶段 1：Android 基础架构

- 目标：单 Activity + 多 Fragment + 底部导航 + ViewBinding + 主题入口。
- 状态：已完成。底部五 Tab（首页、全部、相册、数据、我的），Fragment 用 `add`+`addToBackStack` 叠加模式，详情页不销毁列表 view。

## 阶段 2：数据层与备份层

- 目标：Room 实体/DAO/Repository + JSON 导入导出 + 记录键工厂 + 自动同步。
- 状态：已完成。12 个实体、10 个 DAO、`AppDatabase`、`LocalMediaRepository`/`DefaultLocalMediaRepository`、`RecordKeyFactory`；`BackupManager` 用 `JSONObject` 手工序列化，导出 **10 个** app数据 JSON 文件（settings/authors/tags/album_rules/media_stats/history/scan_sources/likes/recommendation_prefs/timeline_tags）+ 个人偏好导出（`personal_prefs.json`/`personal_prefs_report.txt`）；自动同步已实现（`AutoSyncUseCase`）。

## 阶段 3：扫描与索引

- 目标：SAF 选目录 + 递归扫描图片视频 + 只读 Uri 与元数据 + 重复文件名区分。
- 状态：已完成。双引擎（MediaStore 优先 + SAF 回退）+ 两套体系（常规 + COS）；scanTreeFast 跳过元数据解码；MediaStoreObserver 实时监听常规目录（5 秒防抖）；autoRefreshAllSources 后台静默刷新；扫描防重入 + finally 保护；分批写入（每批 500 条）；按需元数据解码；SAF 路径解析（URL decode + rootDocumentId）。

## 阶段 4：媒体浏览与详情

- 目标：首页/全部/图片/视频列表 + 图片详情 + 视频详情 + 统计 + 历史 + 标签。
- 状态：已完成。首页推荐/排行榜/COS 三 tab + onFling 横滑切换；详情页沉浸式全屏 + chrome 显隐动画；`ZoomImageView` 智能分层渲染（4096 阈值，详见 GUIDE_UI）；`BiliPlayerView` B 站风格播放器；原图加载（Size.ORIGINAL）；GIF 动图支持；AsyncListDiffer 防重排；缩略图三级解码策略 + 本地缓存 + 预生成；视频时间轴标签。

## 阶段 5：相册与作者

- 目标：作品出处相册规则 + 相册页/详情页 + 作者管理入口 + 作者详情/编辑/TXT 导入。
- 状态：已完成。`SourceMatcher` 130+ 出处检索表 + 角色检索表；COS 分区按作者分组；作者管理列表+编辑+TXT 导入（空格容错 + 序号括号容错 + 统一重建语义）；常规作者与 COS 作者隔离（`cos_` 前缀）；COS 目录三种结构自动识别。

## 阶段 6：主题与 UI 打磨

- 目标：统一组件 + 跟随系统明暗 + 胶囊/渐变等视觉。
- 状态：已完成。11 个 `?attr/qmColor*` 主题属性 + `values`/`values-night` 双套；`ThemeHelper` 运行时解析；v1.10 起仅跟随手机系统明暗模式（无 App 内切换入口，`themeRow` 不可点击）；数据统计页自绘图表（折线/柱状/环形/排行）。

## 阶段 7：推荐与排行榜

- 目标：本地推荐 + 热度排行榜 + 推荐偏好设置。
- 状态：已完成。推荐引擎 10 维评分（tagRelevance/tagCollection/engagement/recency/likeScore/discovery/freshness/browseDepth/randomFactor 9 维固定权重 + dailyPenalty 惩罚项）+ 自适应权重 + balanceVideoImage 自然混合；排行榜按 viewCount+playCount+likeCount 热度排序，日/周/月/年/总榜；筛选面板万能配置（FilterConfig）；推荐偏好 9 维可调 + 4 预设。

## 阶段 8：稳定性与打包

- 目标：大量文件性能 + 新系统兼容 + 异常处理 + APK 打包。
- 状态：进行中。5000+ 文件扫描性能已优化；`AppLog` 文件日志（Android 16 logcat 不可读的替代）；`AnrWatchdog` ANR 监控（idle/冻结/推进三重误报抑制）；全局 UncaughtExceptionHandler；自动同步已实现；`CompatChecker` 6 项兼容性检查；全项目安全审查 9 维度 CLEAN；detekt + Android Lint 集成；ProGuard/R8 启用。
- 待办：APK 正式打包发布。

---

## 统一待办清单

- APK 正式打包发布（阶段 8）。

### 维护规则

- 功能做完后必须把对应待办项改为"已完成"或删除。过时的待办比没有待办更危险。
- 新增功能产生的待办，追加到对应阶段"状态"或本清单。
- 每次修改本文件后更新底部时间戳。

> 最后更新：2026-06-21
