# GUIDE_THEME - 主题与全局样式

## 实现路径

- 主题属性定义：`res/values/attrs.xml`（`qmColorBg`, `qmColorSurface`, `qmColorSurfaceSoft`, `qmColorPrimary`, `qmColorPrimarySoft`, `qmColorTextPrimary`, `qmColorTextSecondary`, `qmColorChipBg`, `qmColorDivider`）
- 主题样式定义：`res/values/themes.xml`、`res/values-night/themes.xml`
- 颜色资源：`res/values/colors.xml`（白天）、`res/values-night/colors.xml`（夜间）
- 运行时解析：`app/src/main/java/com/qimeng/media/ThemeHelper.kt`
- MainActivity 主题应用：`MainActivity.kt`（固定 `Theme.绮梦影库`，跟随系统 DayNight）
- 我的页主题入口：`ProfileFragment.kt` 仅提示“跟随手机明暗模式”，不再循环自定义主题
- 视频播放器：使用 Media3 ExoPlayer + 自定义 `BiliPlayerView`（B站风格控制器），详情页顶部栏/底部栏/倍速按钮/系统栏已统一使用项目主题色 `?attr/qmColor*`。

## 职责

管理 App 主题颜色属性、明暗模式切换和全局样式规则。

不管：页面布局（见 GUIDE_UI.md）、动效实现（见 GUIDE_ANIMATION.md）

## 已实现的 2 套系统主题

| 主题 | 标识 | XML 样式 |
|---|---|---|
| 白天模式 | 系统非深色 | `Theme.绮梦影库` + `res/values/colors.xml` |
| 夜间模式 | 系统深色 | `Theme.绮梦影库` + `res/values-night/colors.xml` |

## 颜色字段（9 个 ?attr/）

| 属性名 | 用途 | 白天示例 / 夜间示例 |
|---|---|---|
| `qmColorBg` | 页面/bar 背景 | `#F7F7F7` / `#000000` |
| `qmColorSurface` | 卡片/底栏/弹层背景 | `#FFFFFF` / `#000000` |
| `qmColorSurfaceSoft` | 统计卡背景 | `#EFEFEF` / `#141414` |
| `qmColorPrimary` | 主强调色（图标/按钮/选中） | `#4A4A4A` / `#D9D9D9` |
| `qmColorPrimarySoft` | 主色半透明 | `#1F4A4A4A` / `#26D9D9D9` |
| `qmColorTextPrimary` | 主文字 | `#151515` / `#F2F2F2` |
| `qmColorTextSecondary` | 次文字/hint | `#5E5E5E` / `#B8B8B8` |
| `qmColorChipBg` | 胶囊背景 | `#EAEAEA` / `#1D1D1D` |
| `qmColorDivider` | 分割线 | `#DCDCDC` / `#252525` |

## 使用方式

**XML 布局/drawable**：
```xml
android:background="?attr/qmColorBg"
android:textColor="?attr/qmColorTextPrimary"
```

**Kotlin 代码**：
```kotlin
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.ThemeHelper

// 单个颜色
val color = ctx.resolveThemeColor(R.attr.qmColorPrimary)

// 批量
val c = ThemeHelper.resolve(context)
chip.setTextColor(c.primary)
chip.setBackgroundColor(c.chipBg)
```

**明暗模式**：
```kotlin
// MainActivity.kt
setTheme(R.style.Theme_绮梦影库)

// ThemeHelper.kt
context.isNightMode()
```

## 规则

- 页面禁止硬编码 `@color/qm_*`，必须用 `?attr/qmColor*`。
- 抽屉/弹层代码内用 `resolveThemeColor()` 或 `ThemeHelper.resolve()`，不能用 `ContextCompat.getColor(R.color.qm_*)`。
- `MainActivity.applyStoredTheme()` 在 `super.onCreate()` 前固定应用 `Theme.绮梦影库`，由系统 DayNight 选择 `values` / `values-night` 颜色。
- 详情页 chrome 显示态：白天为浅底深色图标，夜间为黑底浅色图标；chrome 隐藏态始终黑底沉浸。
- **启动画面（Android 12+）**：`windowSplashScreenAnimatedIcon` 设为透明，`windowSplashScreenBackground` 使用 `qm_bg` 色；启动时不显示应用图标，仅闪一下纯色背景后进入主界面。修改启动画面属性需同步更新 `values/themes.xml` 和 `values-night/themes.xml`。
- 新增颜色字段必须同时更新 `attrs.xml`、`res/values/colors.xml`、`res/values-night/colors.xml`、`themes.xml`、`values-night/themes.xml`、`ThemeHelper.kt`。

> 最后更新：2026-06-04
