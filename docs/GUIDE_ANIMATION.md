# GUIDE_ANIMATION - 动效与转场

## 实现路径

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/qimeng/media/ui/detail/MediaDetailFragment.kt` | 详情页滑动切换动画 |
| `app/src/main/res/layout/fragment_media_detail.xml` | 详情页系统栏显隐切换动画 |
| `app/src/main/java/com/qimeng/media/MainActivity.kt` | 系统明/暗模式下的主题色和系统栏外观同步 |
| `app/src/main/java/com/qimeng/media/ui/browser/MediaFilterSheet.kt` | 筛选面板 BottomSheet 弹出/收起 |
| `app/src/main/java/com/qimeng/media/ui/detail/BiliPlayerView.kt` | B站风格播放器控制器显隐动画、手势指示器动画 |

## 职责

管理 App 内所有动效和转场的实现规范与原则。

不管：主题颜色（见 GUIDE_THEME.md）、页面布局（见 GUIDE_UI.md）

## 已实现动效

- **详情页左右滑动切换动画**：`moveBy()` 先调用 `showMediaAt()` 更新内容，再对 `detailContentContainer` 做 200ms `DecelerateInterpolator` 滑入动画，确保新内容立即可见。动画期间 `isTransitioning` 阻止重复触发，动画前重置 `ZoomImageView` 缩放。布局中 `detailContentContainer`（FrameLayout）包裹媒体视图（ZoomImageView + BiliPlayerView + videoTouchOverlay + videoPlayButton），仅该容器参与平移动画，顶栏和底栏不动。
- **详情页进入/退出**：`add` 叠加模式，底层 Fragment 不销毁，返回零闪烁。
- **系统栏显隐**：详情页点击切换沉浸模式，显示 chrome 时按系统白天/夜间设置系统栏图标明暗，隐藏 chrome 时统一黑底并隐藏上下轻量图标操作层；`syncSystemBars()` 始终保持 `setDecorFitsSystemWindows(false)`，仅通过 `WindowInsetsControllerCompat` 控制系统栏显隐，不触发布局变化，避免图片重新居中。
- **明暗主题**：跟随手机系统 DayNight，系统切换后全 UI 使用 `values` / `values-night` 颜色。
- **筛选面板**：Material `BottomSheetDialog` 自带上滑弹出/下滑收起。
- **按钮反馈**：胶囊按钮使用 `?attr/selectableItemBackground` 系统波纹。

## 动效原则

- 动效必须轻量，不影响滚动和视频播放。
- 列表动效不应在大数据量下造成卡顿。
- 所有动效时长和插值器集中管理。
- 动效风格应服务主题，避免每个页面单独乱写。

## 修改注意事项

- 如某动效影响性能，优先删除或降级。
- 不为装饰效果牺牲媒体浏览流畅度。
- 新增动效需同步 `docs/GUIDE_UI.md` 和 `docs/GUIDE_THEME.md`。

> 最后更新：2026-06-21
