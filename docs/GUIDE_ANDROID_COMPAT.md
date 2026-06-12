# GUIDE_ANDROID_COMPAT - Android 系统兼容指南

## 实现路径

目标源码路径：`app/src/main/java/com/qimeng/media/core/CompatChecker.kt`

## 职责

记录绮梦影库在 Android 16 及未来系统上的兼容策略，方便换新手机、新系统、新 AI 时快速适配。

不管：扫描逻辑（见 GUIDE_SCAN.md）、备份流程（见 GUIDE_BACKUP.md）

## 当前基准

- 最低支持系统：Android 12 / API 31
- 当前目标：Android 16 / API 36
- 未来目标：优先适配 Android 17、18、19、20 及更高版本

## 兼容原则

- App 面向 Android 12 及以上，不考虑 Android 11 及以下。
- 新 Android 版本通常向后兼容已安装 App。
- 每次升级 `targetSdk` 或 `compileSdk` 后，必须检查权限、文件访问、媒体读取和后台限制。
- 原始媒体文件永远只读。
- 优先使用 SAF 文件夹授权。
- 不依赖旧版存储权限逻辑。
- 不申请网络权限。

## 权限原则

- 不申请写入外部媒体文件权限。
- 不申请网络权限。
- 能用 SAF 用户授权解决的，不使用过宽权限。
- 新系统新增媒体访问限制时，优先适配 SAF 流程。

## 新系统适配检查项

每次适配新 Android 版本时必须检查：

1. SAF 文件夹选择器是否仍支持持久授权。
2. 图片和视频读取权限是否变化。
3. 媒体访问是否新增部分访问限制。
4. 备份目录 JSON 写入是否仍可用。
5. Media3 / ExoPlayer 是否需要升级。
6. Coil 是否需要升级。
7. Room 是否需要数据库迁移。
8. 后台扫描是否受新系统限制。
9. 通知、前台任务和电池策略是否影响扫描。
10. APK 是否仍不包含开发指南文档。

## 兼容性检查工具

`CompatChecker`（`core/CompatChecker.kt`）提供运行时兼容性检查，检查项：

| 检查项 | OK 条件 | WARN 条件 | ERROR 条件 |
|---|---|---|---|
| Android 版本 | API 31+ | — | API < 31 |
| SAF 授权 | 至少1个持久授权 | 无授权 | — |
| 可用存储 | ≥500MB | 50-500MB | <50MB |
| MediaStore | 可正常查询 | — | 查询异常 |
| 备份写入 | 有写入权限的持久授权 | 无写入权限 | — |
| 设备信息 | 始终OK | — | — |

入口：我的页 → 兼容性检查行，结果以 BottomSheet 展示。

## 适配操作流程

1. 查看新 Android 版本行为变更。
2. 升级依赖和 compileSdk。
3. 运行构建和基础安装。
4. 测试目录选择、扫描、播放、图片查看、备份写入。
5. 检查权限弹窗和失败提示。
6. 同步更新兼容文档。

## 验收清单

## 文档同步

因新 Android 系统导致代码调整时，必须同步更新：

- `docs/PROJECT_GUIDE.md`
- `docs/GUIDE_ANDROID_COMPAT.md`
- `docs/GUIDE_SCAN.md`
- `docs/GUIDE_BACKUP.md`

> 最后更新：2026-06-05
