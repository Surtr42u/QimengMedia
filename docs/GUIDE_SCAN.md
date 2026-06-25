# GUIDE_SCAN - 扫描与只读访问

## 实现路径

目标源码路径：`app/src/main/java/com/qimeng/media/scan/`

当前实现：

- `MediaStoreScanner`：基于 MediaStore ContentResolver 查询系统媒体数据库，毫秒级返回结果。常规扫描和 COS 扫描共用。
- `MediaStoreObserver`：基于 ContentObserver 监听 MediaStore 变化，自动增量更新已注册的常规扫描目录。5 秒防抖合并变更通知。**只监听常规目录，不监听 COS 目录。**
- `SafMediaScanner`：基于 SAF `DocumentFile` 递归扫描用户授权目录。MediaStore 查不到时的回退方案。
- `ScanUtils`：扫描器共享工具方法（`mediaTypeForExtension`、`shortHash`、`buildEntities`），MediaStoreScanner 和 SafMediaScanner 共用，消除重复代码。
- `ProfileFragment`：通过 `OpenDocumentTree` 获取目录授权并只持久化读权限；常规目录入口统一放在"数据管理/常规目录"。
- `MediaLibraryViewModel`：触发扫描、读取既有 `ScanSourceEntity`，统一调度双引擎。

## 两套扫描体系

本项目有**两套独立的扫描体系**，分别服务于不同场景，在数据结构、扫描逻辑、作者关联、相册分组上完全不同：

| 维度 | 常规扫描 | COS 扫描 |
|---|---|---|
| **用途** | 通用图片/视频文件扫描 | COS 图集专用扫描（按作者/作品目录结构） |
| **入口** | 数据管理 → 选择文件夹 | 数据管理 → 选择 COS 文件夹 |
| **目录结构** | 扁平或任意层级，无固定结构 | 固定三级：`COS根目录/作者名/作品名/文件` |
| **`isCosDirectory`** | `false` | `true` |
| **`isCosFile`** | `false` | `true` |
| **作者创建** | TXT 导入，手动关联文件 | 扫描时自动创建，自动关联文件 |
| **authorId 前缀** | 无前缀（如 `rioko凉凉子`） | `cos_` 前缀（如 `cos_rioko凉凉子`） |
| **作者关联方式** | TXT 文件名前缀匹配 | 扫描时记录 author→files 映射，直接创建 crossRefs |
| **相册分组** | 按出处（SourceMatcher 匹配文件名前缀） | 按作者（crossRefs 查询） |
| **自动刷新** | MediaStoreObserver 实时监听 + App 启动时自动刷新 | App 启动时自动刷新（5 分钟防抖），无 ContentObserver |
| **元数据解码** | 扫描时跳过，详情页按需解码 | 同左 |

## 双引擎扫描架构

| 引擎 | 数据源 | 速度 | 适用场景 | 权限 |
|---|---|---|---|---|
| **MediaStoreScanner** | 系统媒体数据库 | 毫秒级 | 常规扫描 + COS 扫描（优先） | READ_MEDIA_IMAGES / READ_MEDIA_VIDEO |
| **SafMediaScanner** | SAF DocumentFile 递归遍历 | 秒级 | MediaStore 查不到时的回退方案 | SAF 持久授权 |

---

## 常规扫描

### 扫描流程

1. 优先使用 MediaStore 快速查询（`queryByFolderPath`，2 次 SQL：图片+视频）
2. MediaStore 查不到时回退到 SAF `scanTreeFast`（跳过图片尺寸/视频元数据解码，只记录基本信息）
3. 数据库写入分批（每批 500 条）
4. `scanTreeFast` 中 `DocumentFile.fromTreeUri()?.name` 在 IO 线程调用，避免主线程 SAF IPC

### 入口方法

- `scanDirectory(uri, displayName?)`：首次添加目录
- `refreshScanSource(uriString)`：增量刷新已有目录
- `autoRefreshAllSources()`：App 启动后延迟 3 秒自动刷新所有常规目录（避免扫描写入数据库触发 Room Flow 导致 UI 闪烁）

### 实时监听

`MediaStoreObserver` 监听 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 和 `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` 的变化：
- 收到变化通知后 5 秒防抖（延长以合并更多变更通知，减少频繁拍照场景下的扫描开销）
- 防抖期间用 AtomicInteger 计数器统计合并的通知次数（仅用于 log 诊断），防抖结束后对所有常规扫描目录全量增量刷新
- 对每个已注册的常规扫描目录，用 MediaStoreScanner 重新查询
- 增量 upsert 新文件，删除已移除的文件
- **只监听常规目录**（`isCosDirectory=false`）

### URI 转换算法（`safUriToFilePath`）

将 SAF URI 转换为文件系统路径，用于 MediaStore 查询：
1. 从 SAF URI 中提取 `documentId`（格式：`volume:relativePath`）
2. `primary` 卷 → `/storage/emulated/0/relativePath`
3. 其他卷 → `/storage/volume/relativePath`
4. 转换后的路径用于 MediaStore 的 `DATA LIKE path/%` 查询条件

---

## COS 扫描

### 扫描流程

1. 优先使用 MediaStore 单次查询整个 COS 根目录（`queryCosFolder`，只需 2 次 MediaStore 查询：图片+视频）
2. 从文件路径中解析出作者名和作品名（COS 路径解析算法，见下）；**解析后将 `folderName` 覆写为 `workName`**（修复结构3 子文件夹如 `作者/作品/p1/` 或 `作者/作品/GIF/` 下文件归类为"其他"的问题：旧行为 folderName 存直接父文件夹"p1"/"GIF"，无法匹配 workName"作品"）
3. MediaStore 查不到时回退到 SAF 一次递归扫描整个根目录（`scanTreeFast`），从 SAF URI 路径解析作者/作品名；**同样覆写 folderName 为 workName**
4. 数据库写入分批（每批 500 条），避免长时间锁表导致 UI 卡顿
5. `autoRefreshAllSources` 对 COS 目录有 5 分钟防抖间隔，避免频繁扫描；COS 自动刷新现会**全量 upsert 所有扫描到的文件**（不仅是新增），以确保 folderName 等字段变更生效
6. 公共方法 `scanCosMedia()` 统一 MediaStore/SAF 双路径逻辑，`scanCosDirectory`/`refreshCosSource`/`autoRefreshAllSources` 共用

### 入口方法

- `scanCosDirectory(uri, displayName?)`：首次添加 COS 目录
- `refreshCosSource(uriString)`：增量刷新已有 COS 目录
- `autoRefreshAllSources()`：App 启动时自动刷新所有 COS 目录（5 分钟防抖）

### COS 路径解析算法（`queryCosFolder`）

从 MediaStore 返回的文件路径中解析作者名和作品名：
1. 去掉根目录前缀，得到相对路径
2. 按 `/` 分割路径段：`segments[0]` = 作者名
3. 如果 `segments.size > 2`：`segments[1]` = 作品名（有子目录结构）
4. 如果 `segments.size <= 2`：作品名 = 作者名（扁平结构，文件直接在作者目录下）
5. 使用 `associateBy` 建立 uriString→candidate 映射，避免 O(n²) 查找

### SAF 回退路径解析算法（`scanCosMedia` SAF 分支）

从 SAF document URI 中解析作者/作品名：
1. 获取根目录的 `rootDocumentId`（如 `primary:1/HHH/2  图/4  cos图集`）
2. 从文件 URI 中提取 `/document/` 之后的路径段
3. 对路径段做 `URLDecoder.decode(..., "UTF-8")` 解码（SAF URI 中中文是 URL 编码的）
4. 去掉 volume 前缀（`primary:`）得到完整相对路径
5. 去掉 `rootDocumentId` 的相对路径前缀，得到根目录之后的路径
6. 去掉最后的文件名（`substringBeforeLast("/")`），得到目录路径
7. 按 `/` 分割目录路径，`segments[0]` = 作者名，`segments[1]` = 作品名（如有）

**关键注意**：SAF URI 中的路径段是 URL 编码的（如 `%E8%B5%B0%E8%B7%AF` = "走路"），必须先 URL decode 再做路径分割。旧算法直接对 URL 编码的字符串做 `startsWith`/`removePrefix` 匹配，导致中文路径永远匹配不上，解析出 0 个作者。

### COS 作者关联

**关键设计**：COS 作者关联不再使用 URI 前缀匹配（因 MediaStore URI 与 SAF URI 格式不同会导致匹配失败），改为扫描时直接记录 author→files 映射来创建 crossRefs。

- `scanCosMedia()` 在扫描过程中维护 `cosMediaAuthorMap: Map<String, List<MediaFileEntity>>`
- 创建 crossRefs 时直接遍历映射，不依赖 `file.uriString.startsWith(workPrefix)`

### COS 目录结构

COS 目录的三种文件夹结构（作者→文件、作者→作品→文件、作者→作品→子文件夹→文件）详见 `GUIDE_AUTHOR.md` 的"COS 目录结构"章节。

---

## 共通机制

以下机制为常规扫描和 COS 扫描共用。

### 按需元数据解码

扫描时使用 `scanTreeFast`（跳过图片尺寸/视频元数据解码），详情页按需解码：
1. `MediaDetailFragment.showInfoSheet()` 检查 `width`/`height`/`durationMillis` 是否为 null
2. 为 null 时在 IO 线程调用 `decodeMediaMetadata()` 解码
3. 图片：`BitmapFactory.Options.inJustDecodeBounds` 读取尺寸
4. 视频：`MediaMetadataRetriever` 读取分辨率和时长
5. 解码后通过 `MediaFileDao.updateMetadata()` 更新数据库缓存，下次直接读取

### 扫描防重入

所有手动扫描入口（`scanDirectory`/`scanCosDirectory`/`refreshScanSource`/`refreshCosSource`）在 `ScanStatus.Running` 时直接 return，防止并发扫描导致数据库锁竞争和卡死。UI 层在扫描进行中时提示"正在扫描中，请稍候"。

**`autoRefreshAllSources()` 不设置 `ScanStatus.Running`**：自动刷新是后台静默操作，不应阻塞用户手动扫描。自动刷新失败也静默处理，不弹出 Toast。

**finally 保护**：所有手动扫描方法都有 `finally` 块，确保协程取消或异常时 `ScanStatus` 不会永远卡在 `Running`。

**预生成防重入**：内部 `isPregenerating` 使用 `AtomicBoolean` 保证线程安全，防止多个扫描同时触发缩略图预生成。

**资源释放**：`SafMediaScanner` 中 `MediaMetadataRetriever` 使用 `try-finally` 确保异常时也调用 `release()`，避免资源泄漏。

### 大文件扫描警告

`ScanResult.Success` 新增 `warning: String?` 字段，`ScanResult.buildScanWarning()` 检测：

- 单文件 > 100MB（`LARGE_FILE_THRESHOLD_BYTES`）
- 扫描总量 > 10GB（`LARGE_TOTAL_THRESHOLD_BYTES`）

触发时 `ScanStatus.Success.warning` 非空，`ProfileFragment.renderScanToast()` 在 Toast 中追加 `⚠ 警告内容` 并延长显示时间（`Toast.LENGTH_LONG`）。仅 `scanDirectory`/`scanCosDirectory`（首次扫描）检测，增量刷新不检测。

### 扫描后缩略图预生成

所有扫描方法完成后，会调用 `pregenerateThumbnails()` 后台预生成缩略图到本地文件缓存（`ThumbnailCache`）。缩略图解码三级策略和预生成动态并发机制详见 `GUIDE_ALGORITHM.md`。

- **先检查本地缓存**：`ThumbnailCache.isThumbnailCached()` 已缓存的自动跳过
- **批量预过滤短路**（2026-06-22 优化）：`ThumbnailCache.pregenerateThumbnails()` 入口先批量过滤已缓存文件，全量缓存命中时（如 autoRefresh 触发的预生成常出现 `skipped=total, generated=0`）直接返回，跳过 `coroutineScope` + `chunked(concurrency)` + `async` 并发池调度开销；部分缓存时只对未缓存文件走并发池，并发解码前二次检查防竞态
- **cache_version 机制**：版本升级时自动清除视频缩略图缓存重新生成
- **来源标识**：`pregenerateThumbnails()` 接受 `ThumbnailSource` 参数（`GENERAL`/`COS`），COS 扫描传入 `ThumbnailSource.COS`，进度 UI 区分显示"常规"/"COS"来源

**启动预生成**：ViewModel `init` 块从数据库读取已有文件的 `MediaFileEntity` 列表，立即开始预生成，不等扫描完成。

### URI 格式差异

- MediaStore URI：`content://media/external/images/media/123`
- SAF URI：`content://com.android.externalstorage.documents/tree/primary:XXX/document/primary:XXX/文件.jpg`
- 两种 URI 都存储在 `uriString` 字段中，**不可混用前缀匹配**

### MediaStore 查询限制

Android 16 上，非标准媒体目录（不在 Pictures/DCIM/Movies 等系统媒体库默认路径下的目录）可能不会被 MediaStore 索引。**目录中存在 `.nomedia` 文件时，MediaStore 会完全忽略该目录及其子目录**，此时 `queryCosFolder()` 和 `queryByFolderPath()` 返回空结果，自动回退到 SAF 扫描。这是系统行为，无法改变。

## 职责

管理用户授权目录、媒体文件扫描、元数据读取和索引重建。

不管：数据实体定义（详见 GUIDE_DATA.md「目标实体」）、备份流程（详见 GUIDE_BACKUP.md「导入导出流程」）、UI 交互（详见 GUIDE_UI.md）

## 扫描范围

- 只扫描用户通过 SAF 授权的目录。
- 支持一个或多个扫描目录。
- 支持递归扫描子文件夹。
- 每次添加新目录时，会合并所有已授权媒体目录重新索引，支持图片和视频混合目录。
- 不全手机扫描。

## 只读原则

- 禁止删除、移动、重命名原始文件。
- 禁止写入媒体文件所在目录。
- 禁止修改 EXIF、文件名、文件内容。
- 原始文件只用于读取和展示。

## 支持格式

图片：`jpg`、`jpeg`、`png`、`webp`。

动图：`gif`（识别为 `MediaType.ANIMATED_IMAGE`，非 `IMAGE`）。

视频：`mp4`、`mkv`、`avi`、`mov`、`flv`、`webm`。

**类型判断**：`queryMedia()` 中根据文件扩展名判断实际类型（`ScanUtils.mediaTypeForExtension()`），而非使用传入的 mediaType 参数。GIF 在 MediaStore.Images 中查询但被正确识别为 `ANIMATED_IMAGE`。

## 扫描字段

- 完整文件名。
- 扩展名。
- 文件类型。
- 文件大小。
- 修改时间。
- 父文件夹名。
- 只读 Uri（MediaStore 使用 content:// Uri，SAF 使用 content://...document Uri）。
- 分辨率（按需解码）。
- 视频时长（按需解码）。
- 路径哈希。

## 重新扫描

重新扫描会更新索引，但不删除 App 内统计、标签、作者、相册规则和主题偏好。

当前实现使用 `LocalMediaRepository.rebuildMediaIndex()` 只清空并重建 `media_files` 表，不清空统计、历史、标签、作者和设置表。

## 删除扫描源

删除扫描源时，系统自动执行以下清理：

1. 删除 `scan_sources` 表中对应记录。
2. 获取所有剩余非备份扫描源，通过 URI 前缀匹配判断每个媒体文件的归属。
3. 不属于任何剩余扫描源的媒体文件从 `media_files` 表中删除。
4. 级联删除对应的 `author_media_cross_refs` 和 `media_tag_cross_refs`。
5. 常规扫描源清理孤立作者用 `deleteOrphanAuthors()`，COS 扫描源用 `deleteOrphanCosAuthors()`。

统计和历史数据保留不删除，以便重新添加扫描源后恢复。

## 重建索引

重建索引用于重新计算媒体文件列表和重复文件名规则，不影响原始媒体文件。

## 修改注意事项

- Android 13+ 需要 READ_MEDIA_IMAGES / READ_MEDIA_VIDEO 运行时权限。
- Android 12 及以下使用 READ_EXTERNAL_STORAGE 权限。
- 选择文件夹时不在主线程调用 `DocumentFile.fromTreeUri()?.name`（SAF IPC 会卡顿/ANR），displayName 改在 ViewModel 的 IO 线程中计算。
- 如未来 Android 存储权限变化，必须同步 `GUIDE_ANDROID_COMPAT.md`。
- 扫描大量文件必须异步执行，避免阻塞 UI。
- 轻量查询方法（`getNonCosKeysAndFileNames`/`getCosKeysAndUris`）详见 `GUIDE_DATA.md`「修改注意事项」
- 作者导入匹配逻辑（`importAuthorsFromText`/`rematchAllTxtImports`）详见 `GUIDE_AUTHOR.md`「双向匹配机制」
- `isPregenerating` 使用 `AtomicBoolean` 替代 `Boolean`，确保缩略图预生成的防重入检查线程安全。
- 常规扫描完成后自动调用 `rematchAllTxtImports`，将已导入 TXT 的作品名与当前库中非 COS 文件重新匹配（详见 `GUIDE_AUTHOR.md`）

- 扫描、导入、导出必须支持取消
- 用户取消扫描时要停止后续索引写入
- 备份失败不能影响原始媒体文件（见 GUIDE_BACKUP.md）
- 搜索输入使用 debounce，避免每个字符都触发重查询

- 原始媒体文件只读，不创建、不删除、不重命名媒体文件（强制）
- 备份目录和媒体目录是两个概念，即使用户选到同一文件夹，也只能写 App 备份 JSON（见 GUIDE_BACKUP.md）
- 不依赖传统绝对路径作为主要访问方式
- 持久化保存 Uri 授权，不要求用户每次重新选择
- Uri 失效时提示重新授权
- Android 16+ 仍优先 SAF，不依赖 READ_EXTERNAL_STORAGE 旧逻辑（见 GUIDE_ANDROID_COMPAT.md）

> 最后更新：2026-06-25
