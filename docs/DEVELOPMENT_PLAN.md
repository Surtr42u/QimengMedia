# DEVELOPMENT_PLAN - 开发计划

## 阶段 0：AI Skill 与开发文档体系

- 建立 `README.md`。
- 建立 `app/ai-skills/`。
- 明确 `app/ai-skills/external/` 只放用户真正下载/导入的外部 Skill。
- 建立 `docs/AI_WRITTEN_SKILLS/` 保存项目内自写通用 AI 参考规范。
- 建立 `docs/` 项目专属指南。
- 明确外部 Skill 只读。
- 明确新 AI 读取顺序。

## 阶段 1：Android 基础架构

- 单 Activity + 多 Fragment。
- 底部四 Tab：首页、全部、相册、我的。
- ViewBinding。
- 基础主题入口。

当前状态：已完成。Fragment 使用 `add`+`addToBackStack` 叠加模式，详情页不销毁列表 view。

## 阶段 2：数据层与备份层

- Room 实体和 DAO（11 个实体、9 个 DAO、AppDatabase、LocalMediaRepository）。
- Repository 接口与默认实现（DefaultLocalMediaRepository）。
- JSON 导入导出模型（BackupModels、BackupFileNames、BackupRepository 接口）。
- 记录键工厂（RecordKeyFactory）。
- 自动同步到用户指定备份目录（后续接入文件读写）。

当前状态：数据骨架已完成，JSON 模型已定义。备份导出/导入底层已实现（7 个 JSON 文件 SAF 目录读写）。数据管理 UI 当前统一承载扫描目录、备份保存位置、相册规则和 TXT 导入作者。后续需实现自动同步。

## 阶段 3：扫描与索引

- SAF 选择媒体目录。
- 递归扫描图片和视频。
- 保存只读 Uri 和元数据。
- 重复文件名高级区分。

当前状态：已完成。双引擎扫描架构（MediaStore 优先 + SAF 回退），两套独立扫描体系（常规 + COS），scanTreeFast 跳过元数据解码提升性能，增量扫描（只扫新文件），MediaStoreObserver 实时监听常规目录变化，autoRefreshAllSources 后台静默刷新（不阻塞手动扫描），扫描防重入 + finally 保护，分批写入（每批 500 条），按需元数据解码（详情页按需解码并缓存），SAF 路径解析算法重写（URL decode + rootDocumentId），COS 路径解析支持 MediaStore 和 SAF 双路径。后续可考虑大图库网格展示（瀑布流）。

## 阶段 4：媒体浏览与详情

- 首页、全部、图片、视频列表。
- 图片详情。
- 视频详情。
- 播放/查看统计。
- 浏览历史。
- 标签。

当前状态：已完成。首页推荐/排行榜/COS 三 tab 切换，GestureDetector.onFling 横滑切换，全部页筛选，详情页沉浸式全屏+chrome 渐显渐隐动画（250ms alpha），视频缩略图取第3秒帧避免黑屏，GIF 色彩修复（ARGB_8888），原图加载（Size.ORIGINAL），AsyncListDiffer 替代 notifyDataSetChanged 防重排，缩略图确定性排序（recordKey hash）。`timeline_tags.json` 备份格式已定义。

## 阶段 5：相册与作者

- 作品出处相册规则。
- 相册页和详情页。
- 作者管理入口。
- 作者详情、编辑、TXT 导入。

当前状态：已完成。相册页按出处分组展示（SourceMatcher 120+ 出处检索表 + 角色检索表），COS 分区按作者分组展示（crossRefs 查询 + cachedCosAuthorNameToId 缓存），点击进入分组详情。作者管理支持列表+新增+详情页关联+TXT导入（含空格容错+扩展名区分），常规作者与 COS 作者隔离（cos_ 前缀），COS 目录结构三种格式自动识别，COS 作者扫描时自动创建并关联文件。标签支持添加和删除。

## 阶段 6：主题与 UI 打磨

- 统一组件。
- 跟随系统白天/深色模式。
- 胶囊、毛玻璃、渐变、贴纸标签。

当前状态：全局主题跟随手机系统白天/深色模式，标签管理已完整上线。后续可接入毛玻璃、渐变等高级视觉。

## 阶段 7：推荐与排行榜

- 本地推荐。
- 热度排行榜。
- 推荐偏好设置。

当前状态：已完成。推荐引擎 10 维评分算法已实现（tagRelevance+engagement+tagCollection+recency+discovery+freshness+likeScore+mediaTypeBalance+browseDepth+randomFactor）。排行榜基于查看+播放热度。筛选面板支持排序/顺位/观看次数/点击次数/文件大小/时间范围（含年份范围选择器）/标签模式/标签选择。`recommendation_prefs.json` 备份格式已定义。

## 阶段 8：稳定性与打包

- 大量文件性能。
- Android 新系统兼容检查。
- 数据损坏和权限异常处理。
- APK 打包。

当前状态：进行中。大量文件性能已优化（5000+ 文件扫描可接受，增量扫描 + 分批写入 + scanTreeFast），AppLog 文件日志工具已实现（Android 16 logcat 不可读的替代方案），ADB 真机调试流程已建立，多项闪退/卡死 Bug 已修复（ActivityResultLauncher 注册、OnScrollChangedListener 生命周期、ScanStatus 卡死、SAF 路径解析、COS 作者关联），全局 UncaughtExceptionHandler 已添加。自动同步已实现（AppPrefs.autoSync + 30秒防抖），兼容性检查已实现（CompatChecker 6项检查）。

---

## 统一待办清单

### 维护规则

- **功能做完后必须把对应的待办项改为"已完成"或删除。** 过时的待办比没有待办更危险。
- 新增功能如果产生了新的待办，在本文件新增对应阶段或追加到已有阶段"当前状态"的后续项中。
- 每次修改 `DEVELOPMENT_PLAN.md` 后更新底部时间戳。

> 最后更新：2026-06-12
