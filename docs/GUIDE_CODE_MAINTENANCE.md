# GUIDE_CODE_MAINTENANCE - 代码阅读、维护与质量保障

## 实现路径

目标源码路径：项目根目录

## 职责

指导 AI 代理和开发者进行代码阅读、静态分析、依赖管理、单元测试、代码重构和质量保障，确保项目代码可维护、可测试、高质量。

不管：具体业务功能实现、UI 设计、数据模型定义（这些由对应 GUIDE_*.md 负责）

## 代码阅读策略

### Grep + 精准读取（核心策略）

这是行业验证的靠谱策略，Claude Code、OpenAI Codex CLI、Cursor 等主流 AI 编码工具的核心做法：

- **原理**：代码搜索的关键词 95% 是标识符（类名36%、方法名41%、变量名18%），标识符是精确锚点，不会被改述
- **数据支撑**：单轮 Grep 检索效果超过 embedding RAG 基线（Exact Match 38.61% vs 24.99%）（GrepRAG, ISSTA '26）
- **搜索模式**：Grep 定位行号 → offset/limit 精准读取 → 调整关键词 → 再搜
- **优势**：零索引、零维护、零 embedding 成本；比全量读文件省 token
- **trade-off**：用更多搜索轮次换取简洁性，通过 prompt cache 和历史压缩控制 token 消耗

### 本项目的具体实践

- 使用 Grep 工具搜索类名/方法名/变量名定位代码
- 使用 Read 工具的 offset/limit 参数精准读取目标片段
- 禁止全文通读源码文件（AGENTS.md 源码隔离原则）
- 先读指南文档理解模块职责，再用 Grep 定位具体实现

## 静态分析工具

### Android Lint

- 用途：检测 Android 特有问题（未使用的资源、API 兼容性、性能问题、无障碍等）
- 运行：`./gradlew lint`
- 报告位置：`app/build/reports/lint-results-debug.html`
- 集成：已内置在 Android Gradle Plugin 中

### Detekt（Kotlin 静态分析）

- 用途：检测 Kotlin 代码异味、复杂度、命名规范、潜在 bug
- 适合本项目的规则集：
  - 复杂度检查：`ComplexCondition`、`NestedBlockDepth`、`LongMethod`（阈值 60 行）
  - 命名规范：`VariableNaming`、`FunctionNaming`、`ClassNaming`
  - 潜在 bug：`UnsafeCallOnNullableType`、`UncheckedCast`、`MayBeConst`
  - 代码异味：`DataClassContainsFunctions`、`TooManyFunctions`（阈值 15）
- 运行：`./gradlew detekt`

### ktlint（Kotlin 代码格式化）

- 用途：统一代码风格（缩进、空格、换行、导入排序）
- 运行：`./gradlew ktlintCheck` / `./gradlew ktlintFormat`
- 与 Detekt 互补：ktlint 管格式，Detekt 管逻辑

## 依赖管理

### Gradle Version Catalog

- 项目已使用 `gradle/libs.versions.toml` 管理依赖版本
- 检查依赖更新：`./gradlew dependencyUpdates`

### 依赖分析

- 用途：检测未使用的依赖、依赖冲突
- 工具：`./gradlew :app:dependencies` 查看依赖树

### ProGuard / R8

- Release 构建已启用 `isMinifyEnabled = true`
- 规则文件：`app/proguard-rules.pro`
- 保留策略：Room 实体类、数据模型、SourceMatcher、序列化类
- 验证命令：`./gradlew assembleRelease`
- 新增依赖时需检查是否需要添加 ProGuard keep 规则

## 单元测试

### 测试策略

- 纯逻辑优先：`MediaBrowserLogic`、`SourceMatcher`、`RecordKeyFactory` 不依赖 Android 框架，最容易测试
- ViewModel 测试：使用 `InstantTaskExecutorRule` + `Turbine`（Flow 测试）
- DAO 测试：使用 Room in-memory database

### 运行测试

- 全部测试：`./gradlew test`
- 单个测试类：`./gradlew test --tests "com.qimeng.media.MediaBrowserLogicTest"`

### 测试目录

- `app/src/test/java/com/qimeng/media/` — 单元测试
- `app/src/androidTest/java/com/qimeng/media/` — 仪器测试

## 代码解耦规范

### Fragment 解耦

- Fragment 间禁止直接调用方法（如 `MainActivity.showDetailFragment()`），应通过接口或共享 ViewModel 通信
- 新增页面交互应定义回调接口，由 Activity 实现
- Fragment 不应持有其他 Fragment 的引用

### ViewModel 职责边界

- 单个 ViewModel 方法数不超过 25 个
- 超过时按功能拆分为子 ViewModel 或 UseCase：
  - 扫描调度 → `ScanUseCase`
  - 缩略图预生成 → `ThumbnailUseCase`
  - 作者导入 → `AuthorImportUseCase`
  - 自动同步 → `AutoSyncUseCase`
- ViewModel 只做调度，不包含业务计算逻辑（计算逻辑放 UseCase 或工具类）

### 数据层解耦

- Repository 接口按功能域拆分查询方法，不写"万能查询"
- DAO 方法粒度：一个方法只做一件事
- 禁止在 Repository 实现中直接写 SQL（SQL 只在 DAO 中）

### UI 与逻辑分离

- Fragment 只做 UI 绑定和事件分发，不包含业务逻辑
- 数据转换（Entity → UI Model）在 ViewModel 或 UseCase 中完成
- 筛选/排序/分组逻辑集中在 `MediaBrowserLogic` 等工具类

## 编码规范

### 命名规范

- 类名：大驼峰（`MediaFileEntity`、`HomeFragment`）
- 方法名：小驼峰，动词开头（`recordView`、`deleteOrphanAuthors`）
- 变量名：小驼峰，布尔变量用 is/has/can 前缀（`isPregenerating`、`hasMore`）
- 常量：全大写下划线（`MAX_LOG_SIZE`、`DATABASE_NAME`）
- 布局 ID：蛇形小写，类型前缀（`rvHomeGrid`、`swipeRefresh`、`tvTitle`）

### 方法规范

- 单方法不超过 60 行（超过则提取子方法）
- 方法参数不超过 5 个（超过则封装为数据类）
- 嵌套不超过 3 层（超过则提取方法或使用 early return）

### 注释规范

- 关键业务逻辑必须添加行内注释（推荐算法、黑帧检测、匹配策略等）
- 公共方法添加 KDoc（参数说明、返回值说明）
- 禁止无意义注释（如 `// set visibility` 上面一行就是 `view.visibility = GONE`）
- 注释用中文

### 空安全规范

- 优先使用 `?.` 和 `?:` 处理可空类型
- 禁止 `!!` 强制解包（除非在 `onViewCreated` 中确认 View 已创建的 `binding get() = _binding!!`）
- ExoPlayer 等生命周期敏感对象使用 `?.let {}` 安全调用，不用 `!!`
- Fragment 中访问 binding 必须通过 `_binding?.` 安全调用
- 方法返回可空类型时，在 KDoc 中说明何时返回 null

## 代码重构指南

### 安全重构原则

- 小步修改：每次只改一个关注点
- 测试保护：重构前确保有测试覆盖
- 文档同步：重构后更新对应指南文档

### 常见重构场景

| 场景 | 方法 | 风险 |
|------|------|------|
| 提取方法 | 选中代码块 → Extract Function | 低 |
| 重命名 | IDE Refactor → Rename | 低 |
| 移动类 | IDE Refactor → Move | 中 |
| 拆分大类 | 先提取方法 → 再提取类 | 高 |
| 修改接口 | 先添加新方法（带默认实现）→ 逐步迁移 | 中 |

### 大型重构流程

1. 先添加测试覆盖目标代码
2. 小步重构，每步后运行测试
3. 每步提交一个 commit
4. 全部完成后更新文档

## 代码质量指标

### 本项目关注的质量指标

| 指标 | 目标 | 当前状态 | 检查方式 |
|------|------|----------|----------|
| 方法长度 | < 60 行 | 阈值 120（逐步收紧） | Detekt LongMethod |
| 圈复杂度 | < 10 | 阈值 20（逐步收紧） | Detekt CyclomaticComplexMethod |
| 嵌套深度 | < 4 层 | 达标 | Detekt NestedBlockDepth |
| 测试覆盖率 | > 60%（纯逻辑类 > 80%） | 已有3个测试类 | ./gradlew test |
| Lint 警告 | 0 个 Error | 命令行卡死，改用 AS | Android Studio Inspect Code |
| Detekt 问题 | 逐步减少 | 已配置 ignoreFailures | ./gradlew detekt |

### Detekt 配置

- 配置文件：`detekt.yml`（项目根目录）
- 运行：`./gradlew detekt`
- 报告：`app/build/reports/detekt/detekt.html`
- 当前设为 `ignoreFailures=true`（不阻断构建），后续逐步收紧阈值
- 已修复：UnusedPrivateMember、UnusedParameter、WildcardImport、NewLineAtEndOfFile 等

### 已有单元测试

| 测试类 | 覆盖目标 |
|--------|----------|
| `RecordKeyFactoryTest` | 主键生成逻辑（12 个测试） |
| `MediaBrowserLogicTest` | formatSize/dateLabel/groupByDate（14 个测试） |
| `SourceMatcherTest` | 出处匹配/变体/版本号合并/自定义出处/txt作品（18 个测试） |

## 共享逻辑与组合组件模式

### 设计原则

当多个 Fragment/页面存在相同或高度相似的逻辑时，必须提取为共享工具类，禁止复制粘贴后微调。共享逻辑分为两类：

| 类型 | 适用场景 | 示例 |
|------|----------|------|
| **计算型 object** | 纯数据计算，无 Android 依赖 | `MediaGroupHelper`、`MediaBrowserLogic` |
| **渲染型 object** | UI 组件构建，依赖 Context | `MediaPillsHelper`、`DetailSheetHelper` |

### 已有共享组件

| 组件 | 路径 | 职责 | 使用者 |
|------|------|------|--------|
| `MediaGroupHelper` | `ui/browser/MediaGroupHelper.kt` | 出处/角色/COS 分组计算 | AllFilesFragment, FavoriteFragment, BrowseHistoryFragment, AlbumDetailFragment, AuthorFilesFragment |
| `MediaPillsHelper` | `ui/browser/MediaPillsHelper.kt` | 出处/角色/分区/类型药丸渲染 | AllFilesFragment, FavoriteFragment, BrowseHistoryFragment, AlbumDetailFragment, AuthorFilesFragment |
| `DetailSheetHelper` | `ui/detail/DetailSheetHelper.kt` | 详情页 BottomSheet 弹窗构建 | MediaDetailFragment |
| `TimelineTagHelper` | `ui/detail/TimelineTagHelper.kt` | 视频时间轴标签弹窗交互 | MediaDetailFragment |
| `SheetUiHelper` | `ui/profile/SheetUiHelper.kt` | 弹窗 UI 组件构建（标题/行/按钮） | ProfileFragment |
| `DimenExt` | `ui/widget/DimenExt.kt` | dp/dpFloat 尺寸转换扩展函数（Int.dp(Context)/Float.dp(Context)/Int.dpFloat(Context)） | MediaDetailFragment, BiliPlayerView, HomeFragment, AlbumFragment, ZoomImageView, AuthorListFragment, MediaFilterSheet, GroupedMediaAdapter, TimelineTagHelper, SheetUiHelper, DetailSheetHelper |
| `MediaBrowserLogic` | `ui/browser/MediaBrowserLogic.kt` | 推荐/排行/筛选/日期分组/格式化工具 | HomeFragment, AllFilesFragment, AlbumDetailFragment, DetailSheetHelper, MediaDetailFragment, MediaThumbnailAdapter |
| `PinchZoomHelper` | `ui/widget/PinchZoomHelper.kt` | 双指缩放列数共享组件（ScaleGestureDetector 缩放逻辑 + GridLayoutManager 创建含 SpanSizeLookup）；ColumnsRef 类包装列数状态 | AllFilesFragment, AlbumDetailFragment, BrowseHistoryFragment, AuthorFilesFragment, FavoriteFragment |

### 共享胶囊筛选体系

所有带芯片栏+药丸筛选的页面统一调度于全部页（`AllFilesFragment`）的算法，通过 `MediaGroupHelper`（分组计算）和 `MediaPillsHelper`（药丸渲染）两个共享组件实现。各页面差异仅在于**芯片栏配置不同**（根据页面上下文省略某些维度），算法逻辑完全一致。

**核心原则**：全部页是"完整版"，其他页面是"按上下文裁剪版"。新增页面如需胶囊筛选，必须复用这两个共享组件，禁止自行实现分组或药丸渲染逻辑。

**芯片栏配置（按页面）**：

| 页面 | 分区 | 作品 | 角色 | 类型 | 省略原因 |
|------|------|------|------|------|----------|
| 全部页 | ✓ | ✓ | ✓ | ✓ | 完整版 |
| 收藏页 | ✓ | ✓ | ✓ | ✓ | 完整版 |
| 浏览历史 | ✓ | ✓ | ✓ | ✓ | 完整版 |
| 相册详情页(常规) | ✗ | ✗ | ✓ | ✓ | 已锁定出处（相册=按出处分组），无需分区和作品 |
| 相册详情页(COS) | ✗ | ✗ | ✓ | ✓ | 已锁定出处（COS出处=作者），无需分区和作品 |
| 作者详情页(常规) | ✗ | ✓ | ✓ | ✓ | 已锁定作者，无需分区 |
| 作者详情页(COS) | ✗ | ✗ | ✓ | ✓ | 已锁定作者且出处=作者，无需分区和作品 |

**算法调度规则**：

- **常规模式**：`groupBySource()`（作品分组）→ `groupByCharacter()`（角色分组），均使用 `SourceMatcher.matchAll()` 推断出处+角色
- **COS 模式**：作品分组按作者名（`AuthorMediaCrossRef` 关联），角色分组按作品名（`groupByCosWork()`，`CosWorkEntity.workName` 匹配）
- **递归筛选**：选了作品后角色只显示该作品下的角色（作品→角色联动）
- **药丸默认收起**：所有页面的药丸区域默认折叠，点击芯片展开，再次点击已选中芯片切换展开/折叠

### 新增共享组件的判断标准

当以下任一条件满足时，必须提取共享组件：

1. **3 个以上页面**存在相同逻辑（如药丸渲染、分组计算）
2. **2 个页面**的逻辑超过 50 行且结构高度相似
3. 新增页面时发现可以复用已有页面的逻辑

### 提取步骤

1. 在共享逻辑所在目录创建 `object` 类（计算型无状态，渲染型接受 Context 参数）
2. 将重复逻辑移入 object，方法签名保持通用
3. 各 Fragment 调用 object 方法替代本地实现
4. 删除 Fragment 中的重复私有方法
5. 更新本文档的"已有共享组件"表格

### 禁止事项

- 禁止为共享组件引入 Fragment/Activity 依赖（应通过参数传递）
- 禁止共享组件直接操作 ViewModel（应通过回调）
- 禁止在共享组件中硬编码字符串（应通过参数传入）

## 大型 Fragment 拆分规范

### 拆分标准

当 Fragment 超过以下阈值时，必须拆分：

| 指标 | 阈值 | 说明 |
|------|------|------|
| 总行数 | > 600 行 | 超过则必须拆分 |
| 方法数 | > 20 个 | 超过则提取子模块 |
| 单方法行数 | > 60 行 | 超过则提取子方法 |

### 拆分策略

| Fragment 类型 | 拆分方式 | 示例 |
|---------------|----------|------|
| 详情页 | 按功能域提取 Helper | `DetailSheetHelper`（弹窗）、图片加载、视频播放 |
| 列表页 | 按渲染域提取 Helper | `MediaPillsHelper`（药丸）、`MediaGroupHelper`（分组） |
| 设置页 | 按功能域提取子方法组 | 扫描配置、备份配置、主题配置 |

### 拆分步骤

1. 识别 Fragment 中的功能域（如弹窗、加载、播放、筛选）
2. 将功能域的私有方法提取为独立 object 类
3. Fragment 通过调用 object 方法替代本地实现
4. 确保拆分后 Fragment 只保留：生命周期方法、状态变量、事件分发
5. 运行 `./gradlew assembleDebug` + `./gradlew test` 验证

### 拆分后 Fragment 的理想结构

```kotlin
class XxxFragment : Fragment() {
    // 1. Binding
    // 2. ViewModel
    // 3. 状态变量
    // 4. onCreateView / onViewCreated / onDestroyView
    // 5. 事件分发方法（调用 Helper/UseCase）
    // 6. 数据观察方法
}
```

## 代码规范优先级（强制）

**本节是所有 AI 代理接手项目的第一要务。** 在阅读任何功能模块的指南之前，必须先理解本文件的编码规范。

### 优先级排序

1. **代码规范**（本文件 §6 编码规范 + §9 共享逻辑 + §10 拆分规范）— 所有代码修改的前提
2. **架构规范**（本文件 §5 代码解耦规范）— 分层和职责边界
3. **质量指标**（本文件 §8 代码质量指标）— 量化目标
4. **功能模块指南**（`docs/GUIDE_UI.md`、`docs/GUIDE_DATA.md` 等）— 具体业务规则

### 为什么代码规范优先于功能指南？

- 代码规范是"怎么写代码"的规则，功能指南是"写什么代码"的规则
- 不遵守代码规范的代码，即使功能正确，也会在维护时产生回归 bug
- 代码重复、方法过长、职责不清是项目腐化的根源
- 先看规范再写功能，比写完再改成本低 10 倍

### 强制自检

每次准备写代码前，用 30 秒完成以下自检：

1. 我要写的方法会超过 60 行吗？→ 超过则先规划子方法
2. 这个逻辑在其他 Fragment 里有没有？→ 有则用共享组件
3. 修改的 Fragment 会超过 600 行吗？→ 超过则先规划拆分
4. 新增的方法/类命名是否符合规范？→ 不符合则先调整命名
5. 这个修改需要更新哪些文档？→ 列出后同步更新

## 修改注意事项

- 重构不改变功能行为，只改善代码结构
- 重构后必须运行 `./gradlew assembleDebug` 确认编译通过
- 重构后必须运行 `./gradlew test` 确认测试通过
- 重构后必须更新对应指南文档
- 禁止在重构中夹带功能修改

> 最后更新：2026-06-10
