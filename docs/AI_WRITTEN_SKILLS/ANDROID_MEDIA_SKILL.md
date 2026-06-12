# ANDROID_MEDIA_SKILL - Android 图片与视频处理（项目工程约束）

## 来源与用途

本 Skill 是项目内自写通用 AI 参考规范，不是外部下载 Skill。仅保留本项目特有的工程约束，通用媒体知识见外部 Skill `android-media-files-sharing`。

## 本项目工程约束

- 绮梦影库视频详情页使用 Media3 ExoPlayer + 自定义 `BiliPlayerView`（B站风格控制器），App 页面通过原生 View 承载
- 视频详情页控制层按 B 站式需求定制：竖屏单击暂停/播放、横屏单击显隐UI/双击暂停/播放、长按2x倍速（竖屏可拖动锁定/退出，横屏临时2x）、倍速PopupWindow选择（0.5x/1x/1.5x/2x）、时间轴标签、全屏切换（500ms防抖）
- 视频点击播放按钮即计一次播放，同一次详情打开只计一次
- 列表不自动播放视频
- 禁止使用网络图片加载能力访问外网
- 读取失败时保留文件基础信息，标记元数据缺失，不让扫描中断

## 与项目文档的关系

实施前继续读取：

- `docs/GUIDE_SCAN.md`
- `docs/GUIDE_UI.md`
- `docs/GUIDE_DATA.md`

> 最后更新：2026-06-06
