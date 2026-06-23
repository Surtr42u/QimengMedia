# GUIDE_USAGE_MATRIX - 指南触发矩阵

本文件用于确保所有外部 Skill、自写通用 Skill、项目专属指南都能发挥作用。任何 AI 在修改代码前，必须按任务类型读取对应指南。

## 总规则

- 先读 `AI_README_FIRST.md`。
- 再读本文件。
- 触发词采用**模糊匹配**——用户说"这个界面改一下"即命中 UI，说"感觉有点卡"即命中性能，不需要精确命中某个词。
- 一个任务常命中多个模块，**必须全部读取**。
- 不确定是否命中时，宁可多读一个指南，不要跳过。
- 如果外部 Skill 与项目文档冲突，以项目文档为准。
- 外部 Skill 默认只读，不修改。

## 修改前快速自检（强制）

每次准备修改代码前，用 10 秒完成以下自检：

1. **用户说了什么** → 我能对应到哪个模块？
2. **矩阵命中哪些指南** → 我读了没有？
3. **这些指南里写了什么规则/禁忌** → 我记住了没有？
4. **改完后要更新哪些文档** → 我列好了没有？
5. **改的是增/删/改** → 增→走《新增功能模块文档流程》，删→走《删除功能强制规则》

如果任何一步不确定，先停下来补读，不要强行写代码。

## 触发判定原则

AI 在阅读用户请求后，应按以下规则判定触发模块：

1. 只要用户的表述**语义上涉及**某模块范畴，就算命中
2. 同义词、大白话、口语、情绪化表达都算命中（如"好慢"=性能，"丑"=UI，"不对"=算法）
3. 一个请求可能同时命中 3-4 个模块，全部都要读
4. 如果某个命中模块的触发词列表里没有用户原话，不代表不应触发——看语义

---

## 通用项目任务

适用任务：任何代码修改、项目结构修改、需求变更、文档变更。

必读：

- `docs/GUIDE_CODE_MAINTENANCE.md`（代码规范第一要务，必须在功能指南之前阅读）
- `README.md`
- `AGENTS.md`
- `AI_README_FIRST.md`
- `app/ai-skills/README.md`
- `app/ai-skills/external/DOWNLOADED_SOURCES.md`
- `docs/AI_WRITTEN_SKILLS/README.md`
- `docs/PROJECT_GUIDE.md`
- `docs/GUIDE_README.md`

---

## 代码规范（强制优先）

**本节是所有 AI 代理的第一要务。** 在阅读任何功能模块指南 之前，必须先读代码规范。

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 代码规范、编码规范、共享逻辑、Fragment 拆分、方法长度、代码重复、代码质量 | 怎么写代码、代码怎么组织、这个方法太长了、重复代码、复制粘贴、代码太乱、整理一下代码 |

必读：

- `docs/GUIDE_CODE_MAINTENANCE.md`（代码规范第一要务）
- 当前涉及模块的 `docs/GUIDE_*.md`

**注意：本节的触发优先级高于所有功能模块。即使用户请求的是 UI/数据/扫描等具体功能，AI 也必须先确认代码规范（共享逻辑、Fragment 拆分、方法长度等）再动手。**

功能指南是"写什么代码"的规则，代码规范是"怎么写代码"的规则。两者都必须遵守。

---

## Android 架构和模块分层

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 架构、MVVM、Repository、ViewModel、模块、重构、分层、包结构、依赖方向 | 代码结构、重新组织、拆分、合并、新建模块、挪一下代码、包名、整理一下、项目结构、基础框架、骨架 |

必读：

- `app/ai-skills/external/awesome-android-agent-skills/Agent.md`
- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-architecture-clean/SKILL.md`
- `app/ai-skills/external/android-lead-agent-skills/references/architecture.md`
- `docs/AI_WRITTEN_SKILLS/ANDROID_ARCHITECTURE_SKILL.md`
- `docs/PROJECT_GUIDE.md`

---

## Android View / XML / ViewBinding

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| Activity、Fragment、ViewBinding、XML、布局、RecyclerView、Adapter、底部导航、ViewPager | 列表、网格、适配器、item、每一项、行、列、切换页面、底部按钮、底部栏、tab、tab栏、页面切换、横滑、左右滑、xml、xml文件 |

必读：

- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-viewsystem-foundations/SKILL.md`
- `docs/AI_WRITTEN_SKILLS/ANDROID_VIEW_SKILL.md`
- `docs/GUIDE_UI.md`

---

## UI 设计、美化、组件和页面

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| UI、美化、高级感、页面、组件、胶囊、毛玻璃、媒体卡片、导航栏、详情页、布局、视觉风格 | 界面、页面、屏幕、这个页面、那个页、改一下、改改、调整看看、显示、样式、外观、丑、不好看、难看、太丑、美化一下、优化一下外观、看起来、圆角、卡片、卡片样式、封面、封面图、缩略图、标题、顶栏、底栏、状态栏、沉浸式、全屏、背景色、底色、间距、边距、margin、padding、对齐、居中、靠左、靠右、大小、太大、太小、变大、变小、改小一点、图标、icon、文字大小、字太小、字太大、加个东西、去掉、隐藏、显示、显隐、这个界面 |

必读：

- `app/ai-skills/external/rshzrh-ui-design-skills/skills/ui-android/SKILL.md`
- `app/ai-skills/external/rshzrh-ui-design-skills/skills/ui-android/anchors.md`
- `app/ai-skills/external/rshzrh-ui-design-skills/skills/ui-android/component-anatomy.md`
- `app/ai-skills/external/rshzrh-ui-design-skills/skills/ui-android/ban-patterns.md`
- `app/ai-skills/external/rshzrh-ui-design-skills/skills/ui-android/verification.md`
- `docs/AI_WRITTEN_SKILLS/ANDROID_UI_DESIGN_SKILL.md`
- `docs/AI_WRITTEN_SKILLS/ANDROID_VIEW_SKILL.md`
- `docs/GUIDE_UI.md`
- `docs/GUIDE_THEME.md`
- `docs/GUIDE_ANIMATION.md`

**这节极其容易被大白话命中。用户说"这个界面改一下"、"上面那一行去掉"、"卡片不好看"、"缩略图太小了"、"底栏改一下"都触发本节。**

---

## 主题、颜色、字体

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 主题、颜色、字体、深色、浅色、系统明暗、OLED、明暗模式、跟随系统 | 色的、配色、色值、颜色不对、颜色太亮、颜色太暗、黑了、换个颜色、换个色、暗色模式、亮色模式、黑夜模式、日间模式、手机深色模式、跟随系统、字体、字型、字号、字大小、粗体、斜体、字颜色、字色、偏红、偏蓝、偏黄、太刺眼、太暗了看不清、主题色、主色调、强调色、背景颜色、表面颜色、换主题、主题不生效 |

必读：

- `app/ai-skills/external/android-lead-agent-skills/references/theming-and-color.md`
- `app/ai-skills/external/rshzrh-ui-design-skills/skills/ui-android/anchors.md`
- `docs/AI_WRITTEN_SKILLS/ANDROID_UI_DESIGN_SKILL.md`
- `docs/GUIDE_THEME.md`
- `docs/GUIDE_UI.md`

---

## 动效和转场

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 动画、动效、转场、按钮反馈、列表动效、主题切换动画、卡片动画 | 过渡、淡入、淡出、渐变、滑入、滑出、弹窗、弹出、弹入、收起、展开、切换效果、切换动画、页面转场、跳转动画、回到、返回动画、点击效果、按压效果、按下去、feedback、跳动、震动、晃、闪现、闪烁、一闪一闪 |

必读：

- `app/ai-skills/external/rshzrh-ui-design-skills/skills/ui-android/motion.md`
- `app/ai-skills/external/android-lead-agent-skills/references/motion-and-animation.md`
- `docs/GUIDE_ANIMATION.md`
- `docs/GUIDE_UI.md`

---

## Room 数据库、实体、DAO、Repository

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| Room、数据库、Entity、DAO、Migration、Repository、统计、历史、标签、设置、作者关联 | 数据、保存数据、存下来、记录、数据丢了、数据没了、清空数据、重置数据、表、建表、加个字段、多记录一个、存一下、数据表、数据库、统计数、播放次数、查看次数、浏览时长、看了几次、收藏、标记喜欢、标签、打标签、贴标签、标签管理、历史、浏览记录、看过哪些、记录删不掉、标签不显示、标签对不上、关联不上 |

必读：

- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-room-database/SKILL.md`
- `app/ai-skills/external/android-lead-agent-skills/references/data-layer.md`
- `docs/AI_WRITTEN_SKILLS/ANDROID_ROOM_SKILL.md`
- `docs/GUIDE_DATA.md`
- `docs/GUIDE_BACKUP.md`

---

## 协程、Flow 和异步任务

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 协程、Flow、异步、扫描进度、取消、后台任务、withContext、viewModelScope | 后台、后台跑、异步、不同步、卡主线程、主线程、UI线程、不响应、假死、进度条、扫描进度、取消扫描、停下来、中断、并发、同时跑、一起做、等待、await |

必读：

- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-coroutines-flow/SKILL.md`
- `app/ai-skills/external/android-lead-agent-skills/references/coroutines-and-flow.md`
- `docs/AI_WRITTEN_SKILLS/KOTLIN_COROUTINES_SKILL.md`
- 当前相关项目指南。

---

## 文件扫描、SAF、权限和只读访问

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 扫描、文件夹选择、SAF、权限、Uri、DocumentFile、媒体目录、只读、Android 存储 | 选文件夹、选目录、添加文件夹、加个目录、常规目录、扫描目录、扫描文件夹、重新扫描、重扫、重建索引、找不到文件、没扫到、少了文件、没识别、文件不见了、权限不够、不给权限、读不了、打不开、拒绝访问、授权、获取权限、请求权限、允许访问、文件管理、目录管理、存储权限、文件访问、删不掉（注意：原文件只读，但可作为触发词识别意图）、改了文件后不更新 |

必读：

- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-permissions-activity-results/SKILL.md`
- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-media-files-sharing/SKILL.md`
- `docs/AI_WRITTEN_SKILLS/ANDROID_STORAGE_SAF_SKILL.md`
- `docs/GUIDE_SCAN.md`
- `docs/GUIDE_ANDROID_COMPAT.md`

---

## 图片、视频、媒体元数据和播放器

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 图片、视频、缩略图、封面、Coil、Media3、ExoPlayer、分辨率、时长、播放器、BiliPlayerView | 看图、看视频、播放、播放不了、不能播放、黑屏播放、没画面、有声音没画面、画面卡、视频卡、缓冲、加载中、加载不出来、封面图、视频封面、第一帧、缩略图模糊、缩略图不清楚、图加载慢、图片打不开、图片黑、大图、原图、清晰度、分辨率低、时长、视频长度、倍速、快进、快退、暂停、停止、进度条、播放条、拖动、拖进度、自动播放、下一个、上一个、横屏、竖屏、全屏播放、画中画、小窗播放 |

必读：

- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-media-files-sharing/SKILL.md`
- `app/ai-skills/external/android-lead-agent-skills/references/image-loading.md`
- `docs/AI_WRITTEN_SKILLS/ANDROID_MEDIA_SKILL.md`
- `docs/GUIDE_SCAN.md`
- `docs/GUIDE_UI.md`
- `docs/GUIDE_DATA.md`

---

## 备份、JSON、导入导出和迁移

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 备份、JSON、导入、导出、自动同步、迁移、新手机、备份目录、数据文件夹 | 备份一下、备份数据、导出数据、导出来、导入数据、导入进去、换手机了、新手机、移到新手机、数据搬过去、数据迁移、恢复数据、还原、备份文件、备份目录、存到哪里、备份位置、保存位置、备份失败、导入失败、导出失败、JSON 文件、json、格式不对、备份坏了、备份丢了、自动保存、自动备份、同步数据、数据同步 |

必读：

- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-serialization-offline-sync/SKILL.md`
- `docs/GUIDE_BACKUP.md`
- `docs/GUIDE_DATA.md`
- `docs/GUIDE_ANDROID_COMPAT.md`

---

## 作者管理

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 作者、作者管理、作者 TXT、作者详情、作者关联文件、作者数量、画师、制作者 | 谁做的、创作者、作者是谁、加了作者、作者列表、作者页、作者信息、导入作者、TXT 导入、txt文件、文本导入、作者名字、作者名、作者关联、关联作者、绑定作者、作者下的作品、作者作品 |

必读：

- `docs/GUIDE_AUTHOR.md`
- `docs/GUIDE_DATA.md`
- `docs/GUIDE_UI.md`
- `docs/GUIDE_BACKUP.md`

---

## 标签、历史、播放/查看统计

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 标签、手动标签、自动标签、历史、浏览记录、播放次数、查看次数、浏览时长 | 标签管理、加标签、删标签、改标签、标签颜色、标签没了、历史记录、浏览历史、看了什么、看过、最近浏览、播放记录、看了多少次、播放统计、统计信息、统计不对、统计数据、清历史、清记录、重置、清零、计数、计数不对、少算了、多算了、时长不对、没记录上 |

必读：

- `docs/GUIDE_DATA.md`
- `docs/GUIDE_UI.md`
- `docs/GUIDE_BACKUP.md`
- 如涉及推荐，读 `docs/GUIDE_ALGORITHM.md`。

---

## 推荐、排行榜、排序和相册分类

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 推荐、排行榜、排序、权重、热度、新颖度、相册规则、作品出处、最长匹配、其他相册 | 推荐不准、推荐不对、推荐的是什么、推荐的不好、给我推、推送、随机、随便看、刷视频、刷图、发现、刷不出来新的、老是这几个、重复、老是重复、同样的、没新意、要新的、排序不对、排错了、乱序、顺序、按时间排、按热度排、按名字排、热度不对、日榜、周榜、月榜、年榜、总榜、榜单、排行、top、排名前几、日期不对、分组错了、相册不对、相册分类、分错类了、作品出处不对、识别错、规则不对、种子规则、相册规则、筛选、过滤、搜索、按标签查、搜索不到、搜不出来、筛选不生效 |

**请注意：用户说"推荐不太行"、"推荐差"、"老是推一样的东西"、"排序乱"、"相册分错"都触发本节。**

必读：

- `docs/GUIDE_ALGORITHM.md`
- `docs/GUIDE_DATA.md`
- `docs/GUIDE_BACKUP.md`

---

## Android 版本兼容和未来系统适配

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| Android 16、Android 17、targetSdk、compileSdk、新手机、兼容、权限变化、系统升级、Android 版本 | 升级系统后不行了、更新系统后用不了、新版本 Android、安卓新版本、手机系统升级、闪退、崩溃、打开就闪退、白屏、黑屏、进不去、不兼容、不支持、报错、android17、android18 |

必读：

- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-permissions-activity-results/SKILL.md`
- `docs/GUIDE_ANDROID_COMPAT.md`
- `docs/GUIDE_SCAN.md`
- `docs/GUIDE_BACKUP.md`

---

## 性能优化

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 性能、卡顿、OOM、大量文件、滚动、启动速度、扫描速度、列表优化、内存、ANR | 慢、好慢、太慢了、卡、掉帧、卡帧、一顿一顿、不流畅、不跟手、延迟、响应慢、加载慢、打开慢、启动慢、黑屏加载、白屏加载、等太久、上滑卡、下滑卡、滑动掉帧、闪退、崩了、崩溃、内存不够、吃内存、发热、发烫、烫手、费电、耗电、太快没电、电池、文件太多慢了、图片多了卡、视频长了卡 |

必读：

- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-performance-observability/SKILL.md`
- `app/ai-skills/external/android-lead-agent-skills/references/performance.md`
- `docs/AI_WRITTEN_SKILLS/ANDROID_PERFORMANCE_SKILL.md`
- 当前相关项目指南。

**用户说"感觉卡顿"、"滑动有点掉帧"、"打开好慢"、"发烫"、"太卡了"都触发本节。**

---

## 测试和验证

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 测试、单元测试、UI 测试、构建验证、回归、真机验证、ADB、logcat、截图 | 验证一下、跑一下、试试能不能、检查、检查一下、测一下、构建、编译、build、能不能编译、编译过不了、崩溃日志、log、日志、截图、发截图、真机跑、安装试试、连手机、adb |

必读：

- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-testing-unit/SKILL.md`
- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-testing-ui/SKILL.md`
- `app/ai-skills/external/claude-adb-skill/SKILL.md`，仅真机/ADB 任务需要
- `docs/AI_WRITTEN_SKILLS/ANDROID_TESTING_SKILL.md`
- `docs/GUIDE_DEBUG.md`
- 当前相关项目指南。

---

## 真机调试与日志诊断

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 调试、日志、ADB、AppLog、闪退诊断、扫描卡死、logcat、run-as、真机调试 | 看一下日志、读日志、调试一下、为什么闪退、闪退了、卡住了、没反应、无响应、帮我看看日志、连手机调试、装一下试试、读一下运行日志、运行情况 |

必读：

- `docs/GUIDE_DEBUG.md`
- `docs/GUIDE_SCAN.md`（扫描相关问题时）
- `docs/GUIDE_ANDROID_COMPAT.md`（系统兼容问题时）

---

## 安全和隐私

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 隐私、安全、权限、只读、数据保护、备份安全、无网络、原文件保护 | 不要删我的文件、原文件保护、别动我原图、隐私安全、数据不会泄露吧、不上传网络、没联网、不需要网络、断网、离线、没权限也行、从外部打开 |

必读：

- `app/ai-skills/external/aotocom-android-agent-skills/skills/android-security-best-practices/SKILL.md`
- `app/ai-skills/external/android-lead-agent-skills/references/security.md`
- `docs/GUIDE_SCAN.md`
- `docs/GUIDE_BACKUP.md`
- `docs/GUIDE_ANDROID_COMPAT.md`

---

## 代码维护、重构和质量保障

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 重构、代码质量、静态分析、单元测试、代码审查、代码维护、Detekt、Lint、ktlint | 代码太乱、整理代码、代码规范、加测试、写测试、跑测试、代码检查、代码审查、质量检查、代码可读性、维护性 |

必读：

- `docs/GUIDE_CODE_MAINTENANCE.md`
- 当前涉及模块的 `docs/GUIDE_*.md`

---

## 文档同步

触发词：

| 正式词 | 大白话/口语 |
|---|---|
| 新增需求、修改需求、项目结构、文档、Skill、交接、换 AI | 加个文档、更新文档、文档不对、文档过时、项目文档、开发文档、交接给、换人接手、给别的 AI 用 |

必读：

- `AI_README_FIRST.md`
- `docs/GUIDE_README.md`
- `docs/PROJECT_GUIDE.md`
- 当前涉及模块的 `docs/GUIDE_*.md`

---

## 常见复合触发示例

下面列出典型大白话请求会命中哪些模块，帮助 AI 理解触发逻辑：

| 用户说 | 命中模块 |
|---|---|
| "这个界面改一下" | UI设计 + View/ViewBinding + 主题（可能） |
| "首页推荐改一下" | UI设计 + 推荐/排行 + 当前对应页面 GUIDE_UI |
| "感觉有点卡，滑动不太顺" | 性能优化 + View/ViewBinding |
| "播放视频黑屏" | 图片/视频/播放器 + 性能优化（可能） |
| "加一个标签功能" | Room数据库 + 标签/统计 + UI设计 + View/ViewBinding |
| "换手机了数据怎么搬" | 备份/迁移 + Android兼容 |
| "这个颜色不太好看" | 主题/颜色 + UI设计 |
| "缩略图太模糊了" | 图片/视频/播放器 + UI设计 |
| "后台扫描太慢" | 扫描/SAF + 协程/异步 + 性能优化 |
| "相册分类不对" | 推荐/相册分类 + Room数据库 |
| "作者信息导不进来" | 作者管理 + 备份/JSON + 扫描/SAF |

---

## 最终检查

实现前确认：

- 已读取任务命中的所有指南。
- 已理解冲突优先级。
- 没有修改外部 Skill 原文。

实现后确认：

- 代码能构建。
- 文档已同步。
- 原始媒体文件只读原则没有被破坏。

> 最后更新：2026-06-23