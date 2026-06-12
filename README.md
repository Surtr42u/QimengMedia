<div align="center">

# 绮梦影库

**纯本地 Android 媒体库 — 你的图片与视频，只属于你**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![API: 31+](https://img.shields.io/badge/API-31%2B-brightgreen.svg)](https://android-arsenal.com/api?level=31)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-purple.svg)](https://kotlinlang.org)
[![Target SDK: 36](https://img.shields.io/badge/Target%20SDK-36-orange.svg)](https://developer.android.com/about/versions/16)

[功能特性](#-功能特性) · [截图预览](#-截图预览) · [技术架构](#-技术架构) · [快速开始](#-快速开始) · [贡献指南](#-贡献指南)

</div>

---

## 为什么做这个 App？

绮梦影库的核心理念很简单：

- **零网络** — 不申请网络权限，所有数据只存在你手机上
- **零上传** — 你的图片和视频永远不会离开你的设备
- **只读扫描** — 只索引你授权的目录，不移动、不修改、不删除你的任何文件

---

## ✨ 功能特性

### 智能浏览

- **首页推荐** — 基于本地浏览习惯的多维度推荐算法（10 维评分权重可自定义）
- **排行榜** — 日榜 / 周榜 / 月榜 / 年榜，按浏览次数和点赞数排序
- **搜索** — 按出处、角色、作者、作品名快速检索，输入即补全

### 媒体管理

- **双引擎扫描** — MediaStore 优先 → SAF 回退，兼容 .nomedia 目录
- **虚拟相册** — 自动按作品出处归类，点击即进入相册详情
- **作者管理** — TXT 文本导入作者与作品关联，支持重新匹配
- **标签系统** — 为文件添加自定义标签，支持批量操作
- **收藏夹** — 一键收藏，独立浏览页

### 播放与查看

- **图片详情页** — 原始分辨率加载，双指缩放，双击还原，左右预加载
- **视频播放器** — 手势暂停/倍速/静音，长按 2x 倍速
- **视频时间轴标签** — 播放中添加时间点标记，一键跳转回看
- **全屏播放** — 横屏视频自动支持全屏切换，竖屏视频保持竖屏

### 筛选与排序

- **胶囊筛选** — 按分区 / 作品 / 角色 / 类型多维度筛选，支持联动
- **双指缩放** — 2~5 列无级缩放，自由调整浏览密度
- **排序面板** — 按名称 / 大小 / 时间 / 观看次数排序，支持升降序

### 数据安全

- **完整备份** — 11 类数据 JSON 导出，包含偏好、统计、标签、作者等
- **自动同步** — 指定备份目录后自动写入，支持增量同步
- **导入恢复** — 从备份目录一键恢复所有数据

### 个性化

- **明暗主题** — 跟随系统自动切换，全局统一主题属性体系
- **推荐偏好** — 9 个权重维度自由调节，4 种预设方案一键切换
- **COS 分类** — 按作者 / 作品独立筛选体系，与常规文件互不干扰

---

## 📸 截图预览

> 以下截图来自实际运行效果

| 首页推荐 | 全部文件 | 相册 | 详情页 |
|:---:|:---:|:---:|:---:|
| *首页推荐与排行榜* | *胶囊筛选与双指缩放* | *按出处分组虚拟相册* | *原图查看与视频播放* |

---

## 🏗️ 技术架构

| 类别 | 技术 | 说明 |
|:---|:---|:---|
| 语言 | Kotlin | 100% Kotlin |
| 架构 | 单 Activity + 多 Fragment + MVVM | ViewModel → Repository → DAO 分层 |
| UI | 原生 View + ViewBinding | 不使用 Compose，纯传统视图 |
| 数据库 | Room | 9 个 DAO，KSP 编译 |
| 图片加载 | Coil 3.4 | 自定义 LargeImageDecoder + libspng 加速 PNG |
| 视频播放 | Media3 ExoPlayer | 自定义 BiliPlayerView 控制器 |
| 文件访问 | SAF + MediaStore | 双引擎扫描，兼容 .nomedia |
| 异步 | Kotlin Coroutines + Flow | 全协程化，IO/Default/Main 调度 |
| 缩略图 | 三级策略 + 本地缓存 | loadThumbnail → 内嵌封面 → 首帧截取 + 黑帧检测 |
| 原生 | libspng (NDK/CMake) | ARM NEON 优化 PNG 解码，MIT 协议 |
| 构建 | Gradle Kotlin DSL + Version Catalog | 统一依赖管理 |
| 静态分析 | Detekt + Android Lint | 代码质量保障 |

### 项目结构

```
app/src/main/java/com/qimeng/media/
├── backup/          # 数据备份与导入导出
├── core/            # 核心工具（AppLog, ThumbnailCache, LargeImageDecoder 等）
├── data/            # 数据层（Room 数据库、Repository、偏好设置）
├── domain/          # 业务用例（ScanUseCase, ThumbnailUseCase 等）
├── scan/            # 扫描引擎（MediaStoreScanner, SafMediaScanner）
├── ui/              # UI 层
│   ├── adapter/     # 列表适配器
│   ├── album/       # 相册模块
│   ├── all/         # 全部文件
│   ├── author/      # 作者管理
│   ├── browser/     # 浏览逻辑、筛选、推荐算法
│   ├── detail/      # 详情页、播放器、缩放控件
│   ├── favorite/    # 收藏
│   ├── history/     # 浏览历史
│   ├── library/     # ViewModel
│   ├── main/        # 首页
│   ├── profile/     # 个人页
│   ├── search/      # 搜索
│   └── widget/      # 自定义控件（PinchZoomHelper, FlowLayout 等）
├── MainActivity.kt
├── QimengApplication.kt
└── ThemeHelper.kt
```

---

## 🚀 快速开始

### 环境要求

- Android Studio (Meeko 或更新版本)
- JDK 17
- Android 设备或模拟器 (API 31+)

### 构建与运行

1. **克隆仓库**
   ```bash
   git clone https://github.com/QimengMedia/QimengMedia.git
   cd QimengMedia
   ```

2. **用 Android Studio 打开项目**

3. **等待 Gradle Sync 完成**

4. **连接 Android 设备或启动模拟器**（API 31+）

5. **点击 Run** 或执行：
   ```bash
   ./gradlew assembleDebug
   ```

### 运行测试

```bash
# 单元测试
./gradlew test

# 静态分析
./gradlew detekt
```

---

## ⚠️ 重要声明

> **本项目 100% 由 AI 生成，作者没有任何编程基础。**
>
> 作者（一个完全不会写代码的人）出于个人兴趣爱好，第一次尝试使用 AI 工具开发了本应用。项目中的每一行代码、每一个功能，都是作者通过与 AI 反复对话、描述需求，由 AI 生成并迭代修改而来。
>
> 因此：
> - 部分功能基于作者个人的文件目录结构和使用习惯定制，**并非完全通用**
> - 代码质量参差不齐，可能存在不规范、不优雅甚至不合理的地方
> - 如果你发现任何问题，欢迎提 Issue 或 PR，**但请不要期望作者能看懂或修复**
>
> 作者将项目开源，是希望它能对同样想用 AI 做点东西的人有所启发。如果它能帮到你，那是意外之喜。

---

## 🤖 AI 完全开发

本项目 100% 由 AI 编码完成，因此也配备了一套完整的 AI 协作规范文档，方便后续其他 AI 工具接手修改。

### AI 接手必读

任何 AI 或开发者在修改代码前，必须按顺序阅读：

**首次接手**：`README.md` → `AI_README_FIRST.md` → `AGENTS.md` → `docs/GUIDE_USAGE_MATRIX.md` → 对应 `docs/GUIDE_*.md`

**日常任务**：打开 `docs/GUIDE_USAGE_MATRIX.md` 匹配触发词 → 只读命中的指南 → 改代码 + 同步文档

### 文档索引

| 文档 | 说明 |
|:---|:---|
| `AI_README_FIRST.md` | AI 接手强制阅读规则 |
| `AGENTS.md` / `CLAUDE.md` / `GEMINI.md` | 不同 AI 工具的接手入口 |
| `.github/copilot-instructions.md` | GitHub Copilot 指令 |
| `docs/GUIDE_*.md` | 各模块详细开发指南（15+ 篇） |
| `docs/GUIDE_USAGE_MATRIX.md` | 任务触发词与指南映射 |
| `docs/PROJECT_GUIDE.md` | 项目总览与架构说明 |

---

## 🤝 贡献指南

欢迎贡献！请遵循以下流程：

1. **Fork** 本仓库
2. 创建特性分支 (`git checkout -b feature/your-feature`)
3. 提交修改 (`git commit -m 'feat: add some feature'`)
4. 推送分支 (`git push origin feature/your-feature`)
5. 创建 **Pull Request**

### 提交规范

| 类型 | 说明 |
|:---|:---|
| `feat` | 新功能 |
| `fix` | 修复 Bug |
| `refactor` | 重构（不改变功能） |
| `docs` | 文档更新 |
| `style` | 代码格式调整 |
| `test` | 测试相关 |
| `chore` | 构建/工具变更 |

### 注意事项

- 修改代码前请先阅读 `AI_README_FIRST.md` 和对应的 `docs/GUIDE_*.md`
- 代码和文档必须在同一个 commit 中更新
- PR 需要通过 Review 后才会合并

---

## 📄 第三方开源许可

本项目使用了以下开源库：

| 库 | 许可证 |
|:---|:---|
| [Kotlin](https://kotlinlang.org) | Apache 2.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) (core, appcompat, activity, constraintlayout, lifecycle, room, documentfile, swiperefreshlayout) | Apache 2.0 |
| [Material Components](https://github.com/material-components/material-components-android) | Apache 2.0 |
| [Coil](https://github.com/coil-kt/coil) | Apache 2.0 |
| [Media3 ExoPlayer](https://github.com/androidx/media) | Apache 2.0 |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache 2.0 |
| [libspng](https://github.com/randy408/libspng) | MIT |
| [Detekt](https://github.com/detekt/detekt) | Apache 2.0 |

---

## 📜 许可证

本项目基于 [MIT License](LICENSE) 开源。

Copyright (c) 2026 绮梦影库
