# GUIDE_THEME - 主题与全局样式

## 实现路径

- 主题属性定义：`res/values/attrs.xml`（`qmColorBg`, `qmColorSurface`, `qmColorSurfaceSoft`, `qmColorPrimary`, `qmColorPrimarySoft`, `qmColorAccent`, `qmColorAccentSoft`, `qmColorTextPrimary`, `qmColorTextSecondary`, `qmColorChipBg`, `qmColorDivider`）
- 主题样式定义：`res/values/themes.xml`、`res/values-night/themes.xml`
- 颜色资源：`res/values/colors.xml`（白天）、`res/values-night/colors.xml`（夜间）
- 运行时解析：`ThemeHelper.kt`（直接从 `?attr` 解析，跟随系统明暗模式）
- 明暗模式持久化：`AppPrefsManager.kt`（`themeMode` 字段：system/light/dark，字段保留用于备份兼容性，v1.10 起无 UI 入口修改，始终为 "system"）
- 明暗模式应用：`MainActivity.applyStoredTheme()`（启动时仅调用 `setTheme()`，**不调用 `setDefaultNightMode()`**，由系统 DayNight 自动选择 `values` / `values-night`）
- 明暗模式切换 UI：**无**（v1.10 设计决策：`ProfileFragment.themeRow` 不可点击，仅跟随手机系统明暗模式）
- MainActivity 主题应用：`MainActivity.kt`（`applyThemeColors()` 更新状态栏/导航栏颜色）
- 视频播放器：使用 Media3 ExoPlayer + 自定义 `BiliPlayerView`（B站风格控制器），详情页顶部栏/底部栏/倍速按钮/系统栏已统一使用项目主题色 `?attr/qmColor*`。

## 职责

管理 App 主题颜色属性、明暗模式切换（跟随系统/浅色/深色）。

不管：页面布局（见 GUIDE_UI.md）、动效实现（见 GUIDE_ANIMATION.md）

## 设计理念：温暖极简主义

采用"温暖极简主义"设计语言：
- **暖色调**：与木纹雕刻图标（#DDBC98）同色系，告别冷灰感
- **层次感**：通过 bg/surface/surfaceSoft 三层色差制造深度，不依赖描边
- **强调色**：`qmColorAccent`（琥珀色），用于进度条/数据可视化/选中高亮
- **无描边**：所有卡片去掉 1dp 描边，纯色填充 + 色差分层
- **统一圆角**：卡片 16dp / 统计卡 20dp / 胶囊 100dp / 底部 Sheet 28dp

## 明暗模式系统

### 三种明暗模式

| 模式 | themeMode 值 | AppCompatDelegate 常量 | 说明 |
|---|---|---|---|
| 跟随系统（默认） | `system` | `MODE_NIGHT_FOLLOW_SYSTEM` | 根据手机系统明暗模式自动切换 |
| 浅色模式 | `light` | `MODE_NIGHT_NO` | 始终使用浅色主题 |
| 深色模式 | `dark` | `MODE_NIGHT_YES` | 始终使用深色主题 |

### 持久化与应用

- **存储**：`AppPrefs.themeMode`（`AppPrefsManager` 管理，JSON 文件持久化）。字段保留用于备份兼容性，但 v1.10 起无 UI 入口切换，始终为默认值 "system"
- **启动应用**：`MainActivity.applyStoredTheme()` 在 `super.onCreate()` 前调用 `setTheme(R.style.Theme_绮梦影库)`，**不调用 `setDefaultNightMode()`**——项目设计决策为仅跟随手机系统明暗模式，由系统 DayNight 自动选择 `values` / `values-night`
- **切换应用**：无 App 内切换入口。`ProfileFragment` 的"主题色彩"行**不可点击**（`QimengProfileRow` 默认 `clickable=false`，不设置 `OnClickListener`），不弹窗、不调用 `setDefaultNightMode()`。用户如需切换明暗模式，请到手机系统设置中修改
- **颜色解析**：`ThemeHelper.resolve()` / `Context.resolveThemeColor()` 直接从 `?attr` 读取，由系统 DayNight 自动选择 `values` / `values-night`

### 明暗模式切换 UI

- 入口：我的页 → "主题色彩"行
- 行为：**不可点击**（v1.10 设计决策：仅跟随手机系统明暗模式，无 App 内切换入口）。`QimengProfileRow` 默认 `clickable=false`，不设置 `OnClickListener` 即不可点击，点击无任何反应
- 设计决策（v1.10 起）：取消主题定制系统（ThemeManager/ThemePreset），仅保留跟随手机系统的明暗模式。`AppPrefs.themeMode` 字段保留用于备份 JSON 兼容性，但无 UI 入口修改，始终为默认值 "system"
- 用户如需切换明暗模式，请到手机系统设置中修改，App 会自动跟随

## 已实现的 2 套系统主题

| 主题 | 标识 | XML 样式 |
|---|---|---|
| 白天模式 | 系统非深色 | `Theme.绮梦影库` + `res/values/colors.xml` |
| 夜间模式 | 系统深色 | `Theme.绮梦影库` + `res/values-night/colors.xml` |

## 颜色字段（11 个 ?attr/）

| 属性名 | 用途 | 白天示例 / 夜间示例 |
|---|---|---|
| `qmColorBg` | 页面/bar 背景（暖白/暖黑） | `#F2F1ED` / `#0F0E0D` |
| `qmColorSurface` | 卡片/底栏/弹层背景（纯白/深灰） | `#FFFFFF` / `#1C1A17` |
| `qmColorSurfaceSoft` | 统计卡背景（暖灰） | `#E8E6E0` / `#262320` |
| `qmColorPrimary` | 主强调色（图标/按钮/选中） | `#5D544A` / `#DDBC98` |
| `qmColorPrimarySoft` | 主色半透明（选中指示器背景） | `#1A5D544A` / `#26DDBC98` |
| `qmColorAccent` | 强调色（进度条/数据可视化） | `#A0734A` / `#DDBC98` |
| `qmColorAccentSoft` | 强调色半透明 | `#1AA0734A` / `#26DDBC98` |
| `qmColorTextPrimary` | 主文字（暖黑/暖白） | `#2A2520` / `#F2EDE5` |
| `qmColorTextSecondary` | 次文字/hint（暖灰） | `#7A7068` / `#B0A69A` |
| `qmColorChipBg` | 胶囊背景（暖灰） | `#E5E1D9` / `#2A2622` |
| `qmColorDivider` | 分割线（暖灰） | `#D5D0C6` / `#332E28` |

颜色由 `values/colors.xml` 和 `values-night/colors.xml` 静态定义，系统 DayNight 自动选择。

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

// 单个颜色（直接从 ?attr 解析，跟随系统明暗模式）
val color = ctx.resolveThemeColor(R.attr.qmColorPrimary)

// 批量
val c = ThemeHelper.resolve(context)
chip.setTextColor(c.primary)
chip.setBackgroundColor(c.chipBg)
```

**切换明暗模式**：
```kotlin
// v1.10 起：无 App 内切换入口，仅跟随手机系统明暗模式
// ProfileFragment.themeRow 不可点击（QimengProfileRow 默认 clickable=false，不设置 OnClickListener）
// 用户如需切换，请到手机系统设置中修改，App 自动跟随
```

**判断当前明暗**：
```kotlin
// MainActivity.kt
setTheme(R.style.Theme_绮梦影库)

// ThemeHelper.kt
context.isNightMode()
```

## 圆角规范（5 档统一）

| 档位 | dp | 用途 |
|---|---|---|
| extraSmall | 4 | 小标签、进度条圆角 |
| small | 8 | 紧凑组件 |
| medium | 16 | 卡片、行项、输入框 |
| large | 20 | 统计卡、大卡片 |
| extraLarge | 28 | 底部 Sheet 顶部、详情页控件 |
| pill | 100 | 胶囊按钮、切换组件 |

## 底部导航样式

- `BottomNavigationView` 使用 `elevation=8dp` 与内容区分层
- 选中指示器：`Widget.Qimeng.BottomNavigationView.ActiveIndicator`（pill 形状，`qmColorPrimarySoft` 背景）
- 选中态图标/文字：`qmColorPrimary`
- 未选中态：`qmColorTextSecondary`
- label 始终显示（`labelVisibilityMode="labeled"`）

## 规则

- 页面禁止硬编码 `@color/qm_*`，必须用 `?attr/qmColor*`。
- 抽屉/弹层代码内用 `resolveThemeColor()` 或 `ThemeHelper.resolve()`，不能用 `ContextCompat.getColor(R.color.qm_*)`。
- `MainActivity.applyStoredTheme()` 在 `super.onCreate()` 前固定应用 `Theme.绮梦影库`，**不调用 `setDefaultNightMode()`**，由系统 DayNight 自动选择 `values` / `values-night` 颜色。
- 详情页 chrome 显示态：白天为浅底深色图标，夜间为黑底浅色图标；chrome 隐藏态始终黑底沉浸。
- **启动画面（Android 12+）**：`windowSplashScreenAnimatedIcon` 设为透明，`windowSplashScreenBackground` 使用 `qm_bg` 色；启动时不显示应用图标，仅闪一下纯色背景后进入主界面。修改启动画面属性需同步更新 `values/themes.xml` 和 `values-night/themes.xml`。
- 新增颜色字段必须同时更新 `attrs.xml`、`res/values/colors.xml`、`res/values-night/colors.xml`、`themes.xml`、`values-night/themes.xml`、`ThemeHelper.kt`。
- **禁止用描边做层次**：卡片分层靠 bg/surface/surfaceSoft 色差，不靠 1dp stroke。
- **强调色使用场景**：`qmColorAccent` 仅用于数据可视化（进度条、图表高亮）、选中态强调，不用于普通文字或背景。
- **明暗模式切换**：v1.10 起无 App 内切换入口，仅跟随手机系统明暗模式。`ProfileFragment` 的"主题色彩"行**不可点击**（`QimengProfileRow` 默认 `clickable=false`，不设置 `OnClickListener`），不弹窗、不调用 `setDefaultNightMode()`。

> 最后更新：2026-06-19（主题色彩行改为不可点击，仅跟随手机系统明暗模式）
