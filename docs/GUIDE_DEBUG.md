# GUIDE_DEBUG - 真机调试、日志诊断与性能监测

## 实现路径

涉及文件：

| 文件 | 职责 |
|---|---|
| `core/AppLog.kt` | 文件日志工具，同时写 logcat 和内部文件 |
| `core/AnrWatchdog.kt` | ANR 监控：主线程阻塞 5 秒记录堆栈到 AppLog |
| `QimengApplication.kt` | 初始化 AppLog、AnrWatchdog、Debug 模式 StrictMode、全局 UncaughtExceptionHandler |

## 职责

管理真机调试全流程：日志抓取、性能监测、闪退诊断、功能异常定位。日志与性能数据统一通过 AppLog 文件输出，一次读取即可同时获取运行日志和性能指标。

不管：业务逻辑实现（详见 GUIDE_ALGORITHM.md「推荐算法」、GUIDE_AUTHOR.md「TXT 导入规则」等各功能指南）、UI 交互（详见 GUIDE_UI.md）

---

## 第一部分：运行日志

### 为什么需要文件日志

Android 16 上 `adb logcat -d` 对非 root 设备返回空缓冲区（`0 B readable`），无法通过传统 logcat 读取应用日志。因此项目内置 `AppLog` 工具，将关键日志同时写入应用内部文件，通过 `adb shell run-as` 读取。

### AppLog 工具

#### 设计

- 同时写 `android.util.Log`（logcat）和内部文件 `files/app_log.txt`
- 文件大小限制 2MB，超限时保留最后 500 行
- 每行格式：`MM-dd HH:mm:ss.SSS LEVEL/TAG: message`
- 全局 UncaughtExceptionHandler 将未捕获异常也写入文件
- **线程安全**：`writeLog()` 使用 `synchronized` 锁保护，多线程并发写入不会丢失日志或损坏文件

#### API

```kotlin
AppLog.d(tag, msg)  // DEBUG
AppLog.i(tag, msg)  // INFO
AppLog.w(tag, msg, throwable?)  // WARN，可选异常堆栈
AppLog.e(tag, msg, throwable?)  // ERROR，可选异常堆栈
```

#### 日志标签约定

| 标签 | 用途 |
|---|---|
| `Scan` | 常规扫描（scanDirectory/refreshScanSource） |
| `CosScan` | COS 扫描（scanCosDirectory/scanCosMedia/queryCosFolder） |
| `Detail` | 详情页加载（图片/视频预览、解码、预渲染范围） |
| `Home` | 首页推荐刷新 |
| `AutoSync` | 自动同步 |
| `AutoRefresh` | MediaStore 自动增量刷新（MediaStoreObserver） |
| `AppPrefs` | 偏好管理（AppPrefsManager 写入失败等） |
| `Profile` | 数据管理页面操作 |
| `QimengMedia` | 全局异常、Application 生命周期、内存回收（onTrimMemory） |
| `ANRWatchdog` | 主线程 ANR 监控（idle 识别 + 冻结自检后仅真卡才记录） |
| `GpuInfo` | GPU 纹理上限探测（`GPU纹理上限=N 设备=xxx GPU=xxx`，启动时记录一次） |
| `LargeImage` | 大图解码逐级回退路径（libspng/decodeFileDescriptor/decodeStream 命中或回退 Coil） |
| `Zoom` | ZoomImageView 反射检测异常（仅非预期异常才记录，正常 NoSuchField 静默） |
| `SourceMatcher` | 出处/角色匹配未命中采样日志（`charMiss(N): file=...`，每 50 次未命中记 1 条，N 为累计未命中总数） |

#### 添加日志的原则

1. **关键操作入口**加日志：方法开始、成功/失败
2. **关键数据**加日志：文件数量、作者数量、查询结果
3. **分支路径**加日志：MediaStore hit/miss、SAF fallback
4. **异常捕获**加日志：catch 块中用 `AppLog.e(tag, msg, exception)`
5. **避免高频日志**：循环内部不加日志，避免 2MB 限制过快触发
6. **采样日志**：大量数据取前 3 条样本（如 `allSafMedia.take(3).forEach { AppLog.d(...) }`）；高频未命中事件用计数器采样（如 `SourceMatcher.charMiss` 每 50 次记 1 条 `charMiss(N): ...`，N 为累计总数，既防刷屏又保留诊断计数）

---

## 第二部分：性能监测

### 监测方法

项目使用 **Android Studio Profiler (Perfetto)** 作为性能分析工具，不内置任何性能监测 SDK 或脚本，避免影响 App 体积和运行时性能。

### 性能数据采集流程

1. **Android Studio → Profiler → CPU/Memory**：实时查看 CPU 使用率、内存分配、线程活动
2. **Perfetto trace 导出**：录制 trace 后导出 `.perfetto-trace` 文件，在 [ui.perfetto.dev](https://ui.perfetto.dev) 深入分析
3. **AppLog 时间戳辅助定位**：通过日志时间戳与 Profiler 时间线对照，精确定位性能瓶颈

### 关键性能指标与阈值

| 指标 | 正常范围 | 异常阈值 | 日志标签 |
|---|---|---|---|
| 扫描 600 文件 | < 5s | > 15s | `Scan`/`CosScan` |
| 缩略图首屏加载 | < 500ms | > 2s | `Detail` |
| 详情页图片解码 | < 1s | > 3s | `Detail` |
| 详情页图片绘制（切图渲染层） | HARDWARE < 10ms / SOFTWARE 30~100ms（超大图 >4096 走 SOFTWARE 可能 100ms+，保 matrix 正确性优先于速度） | SOFTWARE > 200ms | `Detail`（`configureBaseMatrix` 时间间隔；智能分层后 ≤4096 走 HARDWARE ~5ms，>4096 走 SOFTWARE 保正确性——超大 bitmap HARDWARE 层 matrix 缩放失效，详见 GUIDE_UI.md「智能分层渲染」） |
| 视频帧截取 | < 500ms/帧 | > 2s/帧 | `Detail` |
| 数据库批量写入 500 条 | < 200ms | > 1s | `Scan` |
| 内存占用（缩略图列表） | < 200MB | > 400MB | Profiler |
| 内存占用（详情页浏览超大原图） | < 600MB | > 1GB（靠 LRU+onTrimMemory 不崩即可） | Profiler（放开 maxBitmapSize 后浏览 8192 原图 Native Heap 峰值 ~900MB） |
| Coil 内存缓存命中率 | > 60% | < 30% | `Detail`（`showImage: 命中内存缓存`/`缓存未命中，异步解码` 计数统计） |

### 性能问题快速定位

#### 缩略图加载慢/不显示

1. 读日志，搜索 `Detail` 标签
2. 检查 `loadVideoPreview: coilSuccess/coilFailed` — Coil 是否成功截帧
3. 检查 `preloadThumbnails: queued N thumbnails` — 预加载是否执行
4. 用 Profiler 观察 CPU：是否有大量 `MediaMetadataRetriever` 线程
5. 常见原因：
   - `videoFrameMillis(3000)` 导致 seek 开销大 → 改用 `videoFrameMillis(0)` 取首帧
   - 同时解码视频帧数过多 → 减少 RecyclerView 缓存（`setItemViewCacheSize(20)`）
   - SAF URI + `Size.ORIGINAL` 导致 Binder IPC 卡住 → 改用 `decodeFileDescriptor`

#### 列表滑动卡顿

1. 用 Profiler 录制 CPU trace，查看主线程是否有 IO 操作
2. 搜索日志中是否有主线程数据库查询
3. 常见原因：
   - 主线程 SAF `DocumentFile` 操作 → 移到 `Dispatchers.IO`
   - 全量加载 `MediaFileEntity` → 改用轻量查询（只取需要的字段）
   - RecyclerView 缓存过大 → 减少 `setItemViewCacheSize`

#### 内存溢出/OOM

1. Profiler → Memory，观察内存曲线
2. 检查是否有大 Bitmap 未释放
3. 常见原因：
   - 详情页 `Size.ORIGINAL` 加载超大图 → 使用 `decodeFileDescriptor` + `inSampleSize`
   - 缩略图缓存数量过多 → 减少 RecyclerView pool size
   - 预加载过多全尺寸图片 → 限制预加载数量（当前基础 1 前 2 后，内存充裕时 +1 每侧即 2 前 3 后）

#### 扫描速度慢

1. 读日志，搜索 `Scan`/`CosScan` 标签
2. 检查 `MediaStore hit=true/false` — 是否命中 MediaStore 快速路径
3. 检查 `SAF fallback: found N files` — SAF 扫描文件数
4. 常见原因：
   - 非 MediaStore 索引目录 → SAF 递归扫描慢（5000+ 文件需 90+ 秒）
   - 非增量扫描 → 应使用 `refreshScanSource` 而非 `scanDirectory`

### Coil 缓存策略

| 场景 | 内存缓存 | 磁盘缓存 | 尺寸 |
|---|---|---|---|
| 缩略图列表 | 35% 可用内存 | 20% 磁盘空间 | 480×270 |
| 详情页图片 | 缓存（LargeImageDecoder 解码后写入 + 内部检查命中返回 null 让 Fragment 走 Coil load） | 不缓存 | Size.ORIGINAL |
| 详情页视频预览 | 缓存 | 缓存 | 1440×2560 |
| 预加载（图片） | 缓存 | 缓存 | Size.ORIGINAL |
| 预加载（视频） | 缓存 | 缓存 | 720×1280 |

**注意**：详情页退出时只释放 ImageView drawable 和取消请求，**不清空内存缓存**（避免 600+ 缩略图同时重新加载）。

**详情页缓存去重机制**（原始方案 + 主线程同步优化）：
- `showImage()` 先在主线程同步检查 Coil 内存缓存（`isCoilMemoryCacheHit`，LruCache 线程安全），命中则直接 `loadOriginalImageWithCoil` 走 Coil load，跳过 `LargeImageDecoder` 的 `withContext(Dispatchers.IO)` 线程切换开销（40MB+ 超大图场景下省 13-34ms）
- 未命中才异步走 `LargeImageDecoder.decodeCurrentImage`，内部再次检查缓存（防预加载刚完成写入），命中返回 null → Fragment 回退 Coil load 命中缓存零延迟；未命中则解码（PNG 用 libspng，其他用 decodeFileDescriptor），成功写入缓存 + setImageBitmap
- `showVideo()` 直接走 `loadVideoFrame`（MediaMetadataRetriever），失败回退 Coil VideoFrameDecoder
- `preloadAround()` 每次切换取消所有旧预加载并重新预加载范围内所有项；Coil 内部缓存去重，已命中的项预加载会直接跳过，不会重复解码

---

## 第三部分：ADB 调试流程

### 环境准备

```powershell
# ADB 路径（替换为你的 SDK 路径）
$adb = "C:\Users\<用户名>\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# 查看已连接设备
& $adb devices

# 指定设备（替换为你的设备序列号）
$serial = "<设备序列号>"
```

### 安装与启动

```powershell
# 编译
cd <项目根目录>; .\gradlew.bat assembleDebug

# 安装
& $adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk

# 清空旧日志 + 重启 App
& $adb -s $serial shell "am force-stop com.qimeng.media"
& $adb -s $serial shell "run-as com.qimeng.media sh -c 'echo > files/app_log.txt'"
& $adb -s $serial shell "am start -n com.qimeng.media/.MainActivity"
```

### 读取日志

```powershell
# 方式1：通过 ProcessStartInfo（推荐，避免 PowerShell CLIXML 编码问题导致中文乱码）
$pinfo = [System.Diagnostics.ProcessStartInfo]::new()
$pinfo.FileName = "$adbPath"                       # adb.exe 绝对路径
$pinfo.Arguments = "-s $serial shell run-as com.qimeng.media cat files/app_log.txt"
$pinfo.RedirectStandardOutput = $true              # 必须配 UseShellExecute=$false + CreateNoWindow=$true
$pinfo.RedirectStandardError = $true
$pinfo.UseShellExecute = $false
$pinfo.CreateNoWindow = $true
$p = [System.Diagnostics.Process]::Start($pinfo)
[System.IO.File]::WriteAllText("$outDir\app_log.txt", $p.StandardOutput.ReadToEnd(), [System.Text.Encoding]::UTF8)
$p.WaitForExit()

# 方式2：直接输出（中文可能乱码，仅供快速查看）
& $adb -s $serial shell "run-as com.qimeng.media cat files/app_log.txt"
```

### 清空日志

```powershell
& $adb -s $serial shell "run-as com.qimeng.media sh -c 'echo > files/app_log.txt'"
```

### 实时监控

```powershell
# 注意：Android 16 logcat 可能不可读，依赖 AppLog 文件日志
# 定期读取最新日志（每 5 秒）
while ($true) {
    # 读取文件日志（同上方式1）
    Start-Sleep 5
}
```

---

## 第四部分：诊断模式

### 闪退诊断

1. 复现闪退操作
2. 重新打开 App（闪退后 AppLog 文件仍保留）
3. 读取日志，查找 `E/QimengMedia: Uncaught exception` 行
4. 根据堆栈定位问题

### 扫描卡死诊断

1. 触发扫描操作
2. 读取日志，查看：
   - `scanCosDirectory: start` — 扫描是否启动
   - `MediaStore hit=true/false` — 是否命中 MediaStore
   - `SAF fallback: found N files` — SAF 扫描文件数
   - `scanCosDirectory: success` — 扫描是否完成
3. 如果日志停在 `start` 没有 `success`，说明扫描卡住或 ScanStatus 未恢复

### COS 作者/相册不显示诊断

1. 触发 COS 扫描
2. 读取日志，查看：
   - `authors=N, works=M` — N=0 说明路径解析失败
   - `SAF sample uri` — 确认 URI 格式
   - `SAF rootDocumentId` — 确认根路径
3. 如果 authors=0，检查 SAF 路径解析算法是否正确处理了 URL 编码

### COS 作品/角色分组"其他"诊断

当 COS 文件在全部页/收藏页等页面的角色/作品药丸中显示为"其他"时，搜索 `CosScan` 标签中的 `groupByCosWork 诊断`：

1. 打开对应页面（全部页/收藏页等），触发分组渲染
2. 读取日志，搜索 `groupByCosWork 诊断`：
   - `落「其他」N个文件，采样前3条` — 显示采样文件的 `fileName`、`author`、`folderName`、`works`（该作者所有注册作品名）
3. 按采样信息判定根因：
   - **folderName 不在 works 列表中**（如 `folderName="p1"`，`works=[作品名A, 作品名B]`）→ **结构3 子文件夹 bug**：文件放在作品目录的子文件夹 (p1/p2) 下，直接父文件夹名匹配不上作品名。需修复分组逻辑。
   - **works 只有作者名且等于 folderName**（如 `author="蠢沫沫" folderName="蠢沫沫" works=[蠢沫沫]`）→ **结构1 设计行为**：文件直接放在作者目录下，workName==authorName 被归入"其他"。
   - **author="其他"** → **作者关联失败**：该文件的 recordKey 未关联任何 cos_ 前缀作者，扫描期路径解析失败或 crossRef 丢失。配合上方「COS 作者/相册不显示诊断」排查扫描侧。

### 缩略图/详情页不显示诊断

1. 打开对应页面
2. 读取日志，搜索 `Detail` 标签：
   - `showImage: 命中内存缓存 key=xxx` — Coil 内存缓存命中，走快速分支（零延迟，跳过异步解码）
   - `showImage: 缓存未命中，异步解码 key=xxx` — 进入 LargeImageDecoder 异步解码
   - `showImage: 解码完成 key=xxx 耗时=Nms 尺寸=WxH` — 异步解码成功（耗时反映大图解码性能）
   - `showImage: 回退Coil key=xxx 耗时=Nms` — LargeImageDecoder 全部回退路径失败，改走 Coil 标准流程
   - `preload: 范围[start..end] 当前=index 内存充裕=B 入队=N` — 预渲染范围（基础 1 前 2 后，内存充裕时 2 前 3 后）
   - `loadVideoPreview: coilSuccess/coilFailed` — 视频帧截取是否成功
   - `loadVideoPreview: retrieverSuccess/allFailed` — 回退方案是否成功
3. 搜索 `LargeImage` 标签查看解码逐级回退路径：
   - `LargeImage: libspng 解码成功/失败` — PNG 走 libspng 的结果
   - `LargeImage: fileDescriptor 解码成功/失败` — BitmapFactory.decodeFileDescriptor 的结果
   - `LargeImage: 回退到 Coil 标准流程` — 所有回退路径均失败
4. 如果只有 `缓存未命中` 没有 `解码完成`/`回退Coil`，说明异步解码被取消（快速滑动切走）或卡住
5. 用 Profiler 检查 CPU 和内存，确认是否有资源竞争

**原图缓存命中率诊断**：统计 `showImage: 命中内存缓存` 出现次数占总 `showImage` 次数的比例。正常左右滑动场景命中率应 > 60%（预渲染生效）；频繁 `缓存未命中` 说明预渲染范围未覆盖或内存回收过于激进（检查 `QimengMedia: onTrimMemory` 日志）。

### 详情页闪退诊断（IllegalArgumentException: width and height must be > 0）

1. 复现：详情页点击图片后闪退
2. 读取日志，查找 `E/QimengMedia: Uncaught exception` + `Bitmap.createScaledBitmap` 堆栈
3. 根因：`LargeImageDecoder` 从 Coil 内存缓存直接取出 Bitmap 对象，缓存驱逐时 bitmap 被 recycle，ImageView 绘制已回收 bitmap 崩溃
4. 修复后行为：
   - 缓存命中时仅检查是否存在（`isInCoilMemoryCache`），返回 null 让 Fragment 走 Coil load 路径，Coil 正确管理 bitmap 生命周期
   - `MediaDetailFragment` 所有 `setImageBitmap` 调用前增加 `!bitmap.isRecycled` 检查，已回收 bitmap 自动回退到 Coil load

### 性能劣化诊断

1. 使用 Android Studio Profiler 录制 CPU trace
2. 对照 AppLog 时间戳定位具体操作
3. 检查主线程是否有 IO/数据库操作
4. 检查是否有大量并发请求（如同时解码多个视频帧）

---

## 已知限制

- **Android 16 logcat 不可读**：`adb logcat -d` 返回空缓冲区，必须使用 AppLog 文件日志
- **SAF 扫描速度**：5000+ 文件 SAF 递归扫描需 90+ 秒，这是 SAF IPC 固有限制
- **MediaStore 非标准目录**：用户自定义路径（如 `/storage/emulated/0/1/HHH/`）不被 MediaStore 索引，COS 扫描始终走 SAF 回退
- **PowerShell 编码**：直接 `adb shell` 输出中文可能乱码（GBK 解码 UTF-8），使用 ProcessStartInfo + 文件中转可避免
- **视频帧截取性能**：`videoFrameMillis(3000)` 需 seek 到 3 秒位置，对大视频文件 CPU 开销大；缩略图和详情页预览统一使用 `videoFrameMillis(0)` 取首帧（详情页于 2026-06-19 对齐缩略图策略，消除 seek 开销）；详情页 `loadVideoFrame` 回退路径同样取首帧（`getFrameAtTime(0, OPTION_CLOSEST_SYNC)`）

---

## ANR 监控与 StrictMode

### ANR 监控（AnrWatchdog）

`core/AnrWatchdog.kt` 在 `QimengApplication.onCreate()` 中启动，自动检测主线程阻塞。

**工作原理**：
1. 后台守护线程每 3 秒向主线程 post 一个 tick 任务，记录时间戳
2. 后台线程检查时间戳是否更新
3. 主线程阻塞超过 5 秒未更新 → 判定为 ANR，记录主线程堆栈到 AppLog
4. 同一次 ANR 30 秒内不重复记录，避免日志刷屏

**误报抑制（idle 识别 + 冻结自检 + 推进识别）**：
- **idle 识别**：判定 ANR 前先检查主线程堆栈**前 3 行**，若出现 `MessageQueue.nativePollOnce` / `MessageQueue.next` / `Looper.loop`（含 `Looper.loopOnce`），说明主线程在 idle 等待消息（非真卡），跳过记录。检查前若干行而非仅栈顶，可覆盖栈顶为 `Looper.loopOnce` 内部系统调用（如 `PerfettoTrace.begin` 埋点）的 idle。早期版本会把"主线程空闲但 watchdog 守护线程被系统冻结/doze"误判为 ANR，产生 `主线程阻塞 128317ms` 这类递增巨量误报。
- **冻结自检**：守护线程 sleep 醒来后对比"预期 sleep 时长(3s)"与"实际 sleep 时长"，若实际远超预期（> 预期 × FREEZE_RATIO，默认 3 倍 = 9s），判定为 watchdog 自身被系统冻结，本轮跳过判定并重置 tickTimestamp，避免冻结累积产生误报。
- **推进识别（高负载误报抑制，2026-06-20 新增）**：高负载下（如 12 并发缩略图预生成）主线程做真实 UI 工作（列表绑定/滚动/渲染/View 构造/视频事件）时单次操作变慢，tick Runnable 排在主线程队列后无法及时执行 → tickTimestamp 不更新 → 误判阻塞。但此时主线程仍在推进（每个检查周期抓到的栈顶不同），非死锁。判定规则（`classifyBlock`）：
  - 连续 `CONSECUTIVE_SAME_TOP_LIMIT`（默认 3，约 9 秒）个检查周期栈顶相同（卡同一处不动）→ 报真 ANR（`BlockKind.STUCK_ANR`，E 级日志）
  - 栈顶在变化（推进中）→ 降级为 debug 日志（`BlockKind.WORKING_SLOW`，D 级 `主线程慢 Xms（高负载推进中，栈顶变化），非死锁，降级记录`），不报 ANR
  - 阻塞超过 `HARD_ANR_THRESHOLD_MS`（默认 30 秒）硬阈值兜底 → 无论栈顶是否变化都报 ANR，防罕见的"栈顶循环变化死循环"漏报
  - idle 命中 → 跳过（`BlockKind.IDLE`）
  - 实测依据：数千文件 COS 缩略图预生成负载下出现数条误报，栈顶各不相同（系统框架内部组件），阻塞约 6 秒，App 全程响应正常 → 推进变慢而非死锁。推进识别将其从 E 级 ANR 降为 D 级 debug。

**日志识别**：
- 搜索 `ANRWatchdog` 标签的 **E 级**日志（`主线程阻塞 Xms（连续 N 次栈顶相同或超硬阈值 30000ms），疑似 ANR`）= 真 ANR，堆栈栈顶应在业务代码（解码/IO/锁）且连续多次不变。
- **D 级**日志（`主线程慢 Xms（高负载推进中，栈顶变化），非死锁，降级记录`）= 高负载下推进变慢，非真 ANR，可忽略或用于性能观察。
- 旧版本（v1.7）的 E 级 `主线程阻塞 Xms（超过阈值 5000ms），疑似 ANR` 若栈顶在系统框架内部（非业务代码）且阻塞 6 秒左右，多为推进变慢误报，v1.8 已降级为 D 级。

**注意**：仅记录日志，不弹窗、不杀进程。ANR 发生时 App 仍可能响应（取决于阻塞是否解除）。

### StrictMode（仅 Debug 构建）

`QimengApplication.enableStrictMode()` 在 Debug 构建中开启，Release 不受影响。

**检测项**：
- ThreadPolicy：主线程磁盘读写、网络操作（penaltyLog 打印到 logcat）
- VmPolicy：SQLite 对象泄漏、Closable 对象泄漏、Activity 泄漏

**使用方式**：Debug 构建运行时，logcat 中搜索 `StrictMode` 标签查看违规。不使用 penaltyDeath 避免调试时频繁崩溃。

---

## 修改注意事项

- 新增关键操作入口时，必须添加 AppLog 日志
- 日志标签遵循约定（Scan/CosScan/Detail/Home/AutoSync/Profile/QimengMedia）
- 修改 AppLog 后需确认文件写入权限（`run-as` 可访问 `files/` 目录）
- 不要在循环体内添加高频日志
- 性能优化后需用 Profiler 验证效果，不能仅凭日志判断
- **敏感隐私内容清理（强制）**：完成调试并将结果写入文档时，必须删除真实设备型号（如 GPU/CPU 具体型号、芯片代号）、真实设备序列号、真机调试产生的具体数据快照（如某次实测的精确文件数/误报数/阻塞毫秒值）、用户图库的真实文件统计。一律泛化为通用描述（"旗舰 GPU 实测可达 16384"、"数千文件负载"、"阻塞约 6 秒"）。性能阈值表中的通用指标（如"扫描 600 文件 < 5s"）可保留，但不得绑定到某台具体设备。文档中不引用真实截图，如需配图用占位说明或应用图标等非隐私素材。

## 文档同步

修改调试/性能相关功能时必须同步：

- `docs/GUIDE_DEBUG.md`
- `docs/PROJECT_GUIDE.md`（如新增文件）
- `docs/GUIDE_SCAN.md`（如修改扫描日志标签）
- `docs/GUIDE_UI.md`（如修改缩略图/缓存策略）

> 最后更新：2026-06-25
