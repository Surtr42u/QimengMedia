package com.qimeng.media.ui.detail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.gif.AnimatedImageDecoder
import coil3.load
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.request.placeholder
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameMillis
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.ThemeHelper
import com.qimeng.media.isNightMode
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.TimelineTagEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.core.AppLog
import com.qimeng.media.core.LargeImageDecoder
import com.qimeng.media.databinding.FragmentMediaDetailBinding
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.widget.dp
import com.qimeng.media.ui.widget.addPressAnimation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.media3.common.util.UnstableApi
class MediaDetailFragment : Fragment() {
    private var _binding: FragmentMediaDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaLibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[MediaLibraryViewModel::class.java]
    }

    private var startRecordKey: String? = null
    private var mediaList: List<MediaFileEntity> = emptyList()
    private var currentIndex = 0
    private var currentMedia: MediaFileEntity? = null
    private var currentRecordKey: String? = null
    private var enterTimeMillis = 0L
    /** v1.14：同一次详情打开视频是否已记过播放（预览→播放→退预览→再播放不重复计数） */
    // v1.15：同一次详情打开的"已计播放"视频集合（会话级）。
    // 原 playRecordedForCurrentMedia 在 showMediaAt 时重置，播放 A → 横滑 B → 滑回 A → 再播放
    // 会重复计播放；改为集合后同一会话内每个视频只计一次，退出详情时清空。
    private val playedRecordKeys = mutableSetOf<String>()

    // v1.15：同一次详情打开的"已计浏览"文件集合（会话级）。
    // 仿 playedRecordKeys 为 viewCount 做去重——showMediaAt 每次横滑切换都会触发
    // loadAndRecordView，滑走滑回会重复 +1；改为集合后同一会话内每个文件只计一次浏览。
    private val viewedRecordKeys = mutableSetOf<String>()

    /** v1.15：详细信息弹窗是否已展示（防重复弹窗，dismiss 时复位） */
    private var infoSheetShowing = false
    private var chromeVisible = true
    // v1.12 删除：tagJob 仅服务于 observeTags 空 collect（collect{} 丢弃全部值），随死代码一并移除
    private var timelineTagJob: Job? = null
    private var themeBgColor: Int = 0
    private var isTransitioning = false
    private var isVideoPlaying = false
    private var exoPlayer: ExoPlayer? = null
    private var videoPreviewJob: Job? = null

    // --- 分页加载（问题4）：详情页初始加载 PAGE_SIZE 个，滑动接近边界时增量加载 ---
    private var sourceKeys: List<String> = emptyList()
    private var sourceByKey: Map<String, MediaFileEntity> = emptyMap()
    // v1.12 修复：原实现用 loadedStartIndex + mediaList.size 推算已加载区段末尾，mapNotNull
    // 过滤已删除 key 后 mediaList 长度小于源 key 数，导致向前加载区间重叠产生重复项。
    // 改为显式维护已加载区段两端边界 [loadedStartIndex, loadedEndIndex)（均为 sourceKeys 下标）。
    private var loadedStartIndex = 0   // 已加载区段在 sourceKeys 中的起始下标（含）
    private var loadedEndIndex = 0     // 已加载区段在 sourceKeys 中的结束下标（不含）
    // key → sourceKeys 下标映射，用于精确显示当前位置（源列表含已删除 key 时计数不漂移）
    private var sourceIndexByKey: Map<String, Int> = emptyMap()
    private var totalSourceCount = 0   // 推荐列表总数（用于显示 "index/total"）
    private var hasPagination = false  // 是否启用分页（sourceKeys 非空时启用）

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startRecordKey = arguments?.getString(ARG_RECORD_KEY)
        enterTimeMillis = System.currentTimeMillis()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        themeBgColor = requireContext().resolveThemeColor(R.attr.qmColorBg)

        (requireActivity() as? MainActivity)?.setDetailMode(true)

        val bottomDockBasePaddingBottom = binding.detailBottomDock.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.detailTopBar) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, sb.top, v.paddingRight, v.paddingBottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.detailBottomDock) { v, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomDockBasePaddingBottom + navBar.bottom)
            insets
        }

        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            setAudioAttributes(audioAttributes, true)
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        }
        exoPlayer?.let { binding.detailVideoPlayer.setPlayer(it) }
        binding.detailVideoPlayer.onBack = { closeDetail() }
        binding.detailVideoPlayer.onFullscreen = { toggleFullscreen() }
        binding.detailVideoPlayer.onBookmark = {
            val player = binding.detailVideoPlayer
            val pos = player.currentPositionMs
            val formatted = player.currentPositionFormatted
            // v1.15：打开标记弹窗前暂停播放——原实现在播放中打开弹窗，展示期间视频继续播放，
            // 标记时间点（打开时快照）与实际画面时间偏差。dismiss 后恢复播放。
            val wasPlaying = exoPlayer?.isPlaying == true
            exoPlayer?.pause()
            TimelineTagHelper.showAddTimelineTagDialog(
                requireContext(),
                pos,
                formatted,
                onAdd = { timeMs, name ->
                    val media = currentMedia ?: return@showAddTimelineTagDialog
                    viewModel.addTimelineTag(media.recordKey, media.fileName, timeMs, name)
                },
                onDismiss = {
                    if (wasPlaying) exoPlayer?.play()
                }
            )
        }
        binding.detailVideoPlayer.onJump = { showJumpSheet() }
        binding.detailVideoPlayer.onTagLongPress = { tag ->
            TimelineTagHelper.showTimelineTagOptionsDialog(
                requireContext(),
                tag,
                binding.detailVideoPlayer.formatMs(tag.timeMillis),
                onSeek = { pos -> binding.detailVideoPlayer.seekToPosition(pos) },
                onDelete = { id -> viewModel.deleteTimelineTag(id) }
            )
        }

        binding.detailBackButton.setOnClickListener { closeDetail() }
        binding.detailInfoButton.setOnClickListener { currentMedia?.let(::showInfoSheet) }
        binding.detailLikeBtn.setOnClickListener { toggleLike() }
        binding.detailFavoriteBtn.setOnClickListener { toggleFavorite() }
        binding.detailTagBtn.setOnClickListener { showTagSheet() }
        binding.detailJumpBtn.setOnClickListener { showJumpSheet() }

        // 按钮按下反馈动画（Material 3 Expressive 微交互）
        listOf(
            binding.detailBackButton,
            binding.detailInfoButton,
            binding.detailLikeBtn,
            binding.detailFavoriteBtn,
            binding.detailTagBtn,
            binding.detailJumpBtn
        ).forEach { it.addPressAnimation() }

        binding.detailImageView.onSingleTap = { toggleChrome() }
        binding.detailImageView.onSwipe = { moveBy(it) }
        binding.videoPlayButton.setOnClickListener {
            val player = binding.detailVideoPlayer
            startVideoPlayback(player)
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeDetail()
            }
        })
        setChromeVisible(true, animate = false)
        loadDetailSource()
    }

    @Suppress("DEPRECATION")
    private fun syncSystemBars(visible: Boolean) {
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val lightBars = visible && !requireContext().isNightMode()
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            val colors = ThemeHelper.resolve(requireContext())
            window.statusBarColor = colors.bg
            window.navigationBarColor = colors.surface
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun loadDetailSource() {
        val key = startRecordKey ?: run { parentFragmentManager.popBackStack(); return }
        viewLifecycleOwner.lifecycleScope.launch {
            // v1.16 修复：allMedia.firstOrNull() 是一次性快照，冷启动扫描未完成时库为空，
            // 原实现直接走到下方 popBackStack 闪退回上一页；此处有限重试（最多 5 次 × 300ms）
            // 等待扫描写入，仍为空才走原有弹栈退出逻辑。delay 可取消，随视图销毁安全终止
            var allMedia = viewModel.allMedia.firstOrNull().orEmpty()
            var retries = 0
            while (allMedia.isEmpty() && retries < DETAIL_SOURCE_MAX_RETRY) {
                delay(DETAIL_SOURCE_RETRY_INTERVAL_MS)
                allMedia = viewModel.allMedia.firstOrNull().orEmpty()
                retries++
            }
            val srcKeys = (requireActivity() as? MainActivity).orEmptySourceKeys()
            val byKey = allMedia.associateBy { it.recordKey }

            sourceByKey = byKey

            if (srcKeys.isNotEmpty()) {
                // 启用分页：从点击项开始加载 PAGE_SIZE 个
                sourceKeys = srcKeys
                totalSourceCount = srcKeys.size
                hasPagination = true

                val startIndex = srcKeys.indexOf(key)
                if (startIndex >= 0) {
                    loadedStartIndex = startIndex
                    val end = minOf(srcKeys.size, startIndex + PAGE_SIZE)
                    loadedEndIndex = end
                    mediaList = srcKeys.subList(startIndex, end).mapNotNull { byKey[it] }
                } else {
                    // key 不在 sourceKeys 中，回退为仅显示该项
                    mediaList = allMedia.find { it.recordKey == key }?.let { listOf(it) }.orEmpty()
                    sourceKeys = mediaList.map { it.recordKey }
                    loadedStartIndex = 0
                    loadedEndIndex = sourceKeys.size
                    totalSourceCount = mediaList.size
                    hasPagination = false
                }
            } else {
                // 无 sourceKeys，使用全部媒体（不分页）
                sourceKeys = allMedia.map { it.recordKey }
                totalSourceCount = allMedia.size
                hasPagination = false
                loadedStartIndex = 0
                loadedEndIndex = sourceKeys.size
                mediaList = allMedia
            }

            if (mediaList.none { it.recordKey == key }) {
                mediaList = allMedia.find { it.recordKey == key }?.let { listOf(it) }.orEmpty()
                sourceKeys = mediaList.map { it.recordKey }
                loadedStartIndex = 0
                loadedEndIndex = sourceKeys.size
                totalSourceCount = mediaList.size
                hasPagination = false
            }

            // v1.12 修复：建立 key → sourceKeys 下标映射，位置显示不再依赖 loadedStartIndex 推算，
            // 源列表含已删除 key 时计数不漂移、不显示错项
            sourceIndexByKey = sourceKeys.withIndex().associate { (i, k) -> k to i }

            if (mediaList.isEmpty()) {
                parentFragmentManager.popBackStack()
                return@launch
            }

            currentIndex = mediaList.indexOfFirst { it.recordKey == key }.takeIf { it >= 0 } ?: 0
            showMediaAt(currentIndex, updatePreviousDuration = false)
        }
    }

    private fun MainActivity?.orEmptySourceKeys(): List<String> = this?.currentDetailSourceRecordKeys().orEmpty()

    // --- 分页加载（问题4）：接近边界时增量加载，避免一次性加载全部 ---

    /** 向后加载更多（追加到 mediaList 末尾） */
    private fun loadMoreForward() {
        if (!hasPagination) return
        if (loadedEndIndex >= sourceKeys.size) return
        val newEnd = minOf(sourceKeys.size, loadedEndIndex + PAGE_SIZE)
        val newItems = sourceKeys.subList(loadedEndIndex, newEnd).mapNotNull { sourceByKey[it] }
        if (newItems.isNotEmpty()) {
            mediaList = mediaList + newItems
        }
        // v1.12 修复：无论本段是否映射到实体（文件被删），都推进已加载区段末尾，
        // 防止下次加载与已加载区间重叠产生重复项（原实现按 mediaList.size 推算会少推）
        loadedEndIndex = newEnd
        AppLog.d("MediaDetail", "分页向后加载: +${newItems.size}, 已加载 ${mediaList.size}/$totalSourceCount")
    }

    /** 向前加载更多（插入到 mediaList 开头，需调整 currentIndex） */
    private fun loadMoreBackward() {
        if (!hasPagination || loadedStartIndex == 0) return
        val newStart = maxOf(0, loadedStartIndex - PAGE_SIZE)
        val newItems = sourceKeys.subList(newStart, loadedStartIndex).mapNotNull { sourceByKey[it] }
        if (newItems.isNotEmpty()) {
            mediaList = newItems + mediaList
            // v1.12 修复：currentIndex 须按实际插入项数调整。mapNotNull 过滤已删除 key 后
            // newItems.size 可能小于源 key 数，原实现按源 key 数 offset 调整会导致
            // currentIndex 越过 mediaList 末尾（overshoot）显示错项
            currentIndex += newItems.size
        }
        // v1.12 修复：空段（该 source 区段全为已删除 key）也推进已加载区段起点，
        // 防止反复加载同一段导致向前导航在过滤区卡死、无法到达更早数据
        loadedStartIndex = newStart
        AppLog.d("MediaDetail", "分页向前加载: +${newItems.size}, 已加载 ${mediaList.size}/$totalSourceCount")
    }

    /** 检查是否需要加载更多数据（接近边界时触发） */
    private fun loadMoreIfNeeded() {
        if (!hasPagination) return
        // 向后：距离末尾不足阈值时加载
        if (currentIndex >= mediaList.size - LOAD_MORE_THRESHOLD) {
            loadMoreForward()
        }
        // 向前：距离开头不足阈值且前面还有数据时加载
        if (currentIndex <= LOAD_MORE_THRESHOLD - 1 && loadedStartIndex > 0) {
            loadMoreBackward()
        }
    }

    private fun showMediaAt(index: Int, updatePreviousDuration: Boolean = true) {
        if (index !in mediaList.indices) return
        if (updatePreviousDuration) updateCurrentDuration()

        // 横屏全屏状态下切换媒体时，恢复竖屏
        if (binding.detailVideoPlayer.getFullscreen()) {
            restorePortraitOrientation()
        }

        val media = mediaList[index]
        currentIndex = index
        currentMedia = media
        currentRecordKey = media.recordKey
        enterTimeMillis = System.currentTimeMillis()
        // v1.15：播放计数按会话级集合去重（见 playedRecordKeys 声明），切媒体不再重置

        // 分页加载：接近边界时增量加载新数据
        loadMoreIfNeeded()

        // 显示 "实际位置/总数"（分页时 totalSourceCount 为推荐列表总数）
        // v1.12 修复：改用 key → sourceKeys 下标精确映射；原 loadedStartIndex + currentIndex
        // 在源列表含已删除 key 时计数漂移，无法反映真实源位置
        val displayIndex = sourceIndexByKey[currentRecordKey]?.plus(1)
            ?: (loadedStartIndex + currentIndex + 1)
        binding.detailFileName.text = "%d/%d".format(displayIndex, totalSourceCount)
        updateLikeButton()
        updateFavoriteButton()
        observeTimelineTags(media.recordKey)
        // v1.15：viewCount 会话级去重——同一次详情打开每个文件只计一次浏览，
        // 首次浏览走 loadAndRecordView（记一次 viewCount），重复浏览（滑走滑回）只纯加载不计数
        val onMediaLoaded: (MediaFileEntity?) -> Unit = { }
        if (viewedRecordKeys.add(media.recordKey)) {
            viewModel.loadAndRecordView(media.recordKey, onMediaLoaded)
        } else {
            viewModel.loadMedia(media.recordKey, onMediaLoaded)
        }

        when (media.mediaType) {
            MediaType.IMAGE, MediaType.ANIMATED_IMAGE -> showImage(media)
            else -> {
                showVideo(media)
                setupVideoTouchOverlay()
            }
        }
        // v1.12 修复：loadMoreBackward 向前加载会在 showMediaAt 内改动 currentIndex，
        // 预加载须围绕当前实际显示项（currentIndex）而非入参 index，否则预加载窗口错位
        preloadAround(currentIndex)
    }

    /** 显示图片（IMAGE/ANIMATED_IMAGE），LargeImageDecoder 优先 + Coil 回退 */
    private fun showImage(media: MediaFileEntity) {
        AppLog.d("Detail", "showImage: 进入 key=${media.recordKey} file=${media.fileName} type=${media.mediaType}")
        stopVideoPlayback()
        videoPreviewJob?.cancel()
        binding.detailVideoPlayer.isVisible = false
        binding.videoTouchOverlay.isVisible = false
        binding.videoPlayButton.isVisible = false
        binding.detailBottomDock.isVisible = chromeVisible
        binding.detailImageView.isVisible = true
        // 保留旧画面作为 placeholder，避免切换瞬间闪白
        binding.detailImageView.setBackgroundColor(0)
        val isGif = media.fileName.substringAfterLast('.', "").equals("gif", ignoreCase = true)
        val isPng = media.fileName.substringAfterLast('.', "").equals("png", ignoreCase = true)

        // 主线程同步检查 Coil 内存缓存：命中则直接 Coil load，跳过 LargeImageDecoder 的 IO 线程切换
        // （40MB+ 超大图场景下，线程切换开销 13-34ms，同步命中可零延迟显示）
        // Coil 的 LruCache 是线程安全的，主线程读取安全
        if (isCoilMemoryCacheHit(media.recordKey)) {
            AppLog.d("Detail", "showImage: 命中内存缓存 key=${media.recordKey} gif=$isGif")
            loadOriginalImageWithCoil(media, isGif)
            return
        }

        // 缓存未命中，异步解码（PNG 用 libspng，其他用 decodeFileDescriptor），解码成功写入缓存 + setImageBitmap；
        // 解码失败回退 Coil 标准流程
        AppLog.d("Detail", "showImage: 缓存未命中，异步解码 key=${media.recordKey} png=$isPng gif=$isGif")
        viewLifecycleOwner.lifecycleScope.launch {
            val startMs = SystemClock.uptimeMillis()
            val bitmap = LargeImageDecoder.decodeCurrentImage(
                requireContext(), media.uriString.toUri(), media.recordKey, isGif, isPng
            )
            val costMs = SystemClock.uptimeMillis() - startMs
            if (bitmap != null && !bitmap.isRecycled && currentRecordKey == media.recordKey) {
                AppLog.d("Detail", "showImage: 解码完成 key=${media.recordKey} 耗时=${costMs}ms 尺寸=${bitmap.width}x${bitmap.height}")
                binding.detailImageView.setImageBitmap(bitmap)
            } else if (currentRecordKey == media.recordKey) {
                // 回退到 Coil 标准流程，placeholder 保留当前画面
                AppLog.d("Detail", "showImage: 回退Coil key=${media.recordKey} 耗时=${costMs}ms")
                loadOriginalImageWithCoil(media, isGif)
            }
        }
    }

    /** 主线程同步检查 Coil 内存缓存是否命中（LruCache 线程安全） */
    private fun isCoilMemoryCacheHit(recordKey: String): Boolean {
        return try {
            val cache = SingletonImageLoader.get(requireContext()).memoryCache ?: return false
            cache[coil3.memory.MemoryCache.Key(recordKey)] != null
        } catch (_: Exception) {
            false
        }
    }

    /** 通过 Coil 加载原图（统一封装，placeholder 保留当前画面避免闪白） */
    private fun loadOriginalImageWithCoil(media: MediaFileEntity, isGif: Boolean) {
        val tag = media.recordKey
        binding.detailImageView.load(media.uriString.toUri()) {
            placeholder(binding.detailImageView.drawable)
            crossfade(false)
            size(Size.ORIGINAL)
            allowHardware(false)
            memoryCacheKey(media.recordKey)
            if (isGif) {
                bitmapConfig(Bitmap.Config.ARGB_8888)
                decoderFactory(AnimatedImageDecoder.Factory())
            }
            listener(
                onSuccess = { _, result ->
                    if (result is SuccessResult) {
                        val img = result.image
                        AppLog.d("Detail", "coilLoad: 成功 key=$tag image尺寸=${img.width}x${img.height}")
                    }
                },
                onError = { _, result ->
                    AppLog.e("Detail", "coilLoad: 失败 key=$tag error=${(result as? ErrorResult)?.throwable?.message}")
                }
            )
        }
    }

    /** 显示视频预览帧，loadVideoFrame 优先 + Coil VideoFrameDecoder 回退 */
    private fun showVideo(media: MediaFileEntity) {
        isVideoPlaying = false
        binding.detailVideoPlayer.isVisible = false
        binding.detailBottomDock.isVisible = chromeVisible
        binding.detailImageView.isVisible = true
        // 保留当前画面直到视频帧加载完成，减少切换空白
        videoPreviewJob?.cancel()
        AppLog.d("Detail", "showVideo: key=${media.recordKey} uri=${media.uriString}")
        // 主线程同步检查 Coil 内存缓存：预加载已将视频帧写入缓存时命中可零延迟显示，
        // 跳过 MediaMetadataRetriever 的 IO 开销，消除"图片滑到视频卡一下"（与 showImage 同策略）
        if (isCoilMemoryCacheHit(media.recordKey)) {
            AppLog.d("Detail", "showVideo: 命中内存缓存 key=${media.recordKey}")
            loadVideoFrameWithCoil(media)
            binding.videoTouchOverlay.isVisible = true
            updateVideoPlayButtonVisibility()
            return
        }
        // 缓存未命中，异步 MediaMetadataRetriever 取首帧，失败回退 Coil
        videoPreviewJob = viewLifecycleOwner.lifecycleScope.launch {
            val startMs = SystemClock.uptimeMillis()
            val bitmap = loadVideoFrame(media.uriString)
            val costMs = SystemClock.uptimeMillis() - startMs
            if (bitmap != null && !bitmap.isRecycled && currentRecordKey == media.recordKey) {
                AppLog.d("Detail", "showVideo: 首帧截取成功 key=${media.recordKey} 耗时=${costMs}ms 尺寸=${bitmap.width}x${bitmap.height}")
                binding.detailImageView.setBackgroundColor(0)
                binding.detailImageView.setImageBitmap(bitmap)
            } else if (currentRecordKey == media.recordKey) {
                // 回退到 Coil VideoFrameDecoder，placeholder 保留当前画面
                AppLog.d("Detail", "showVideo: 首帧失败回退Coil key=${media.recordKey} 耗时=${costMs}ms")
                loadVideoFrameWithCoil(media)
            }
        }
        binding.videoTouchOverlay.isVisible = true
        updateVideoPlayButtonVisibility()
    }

    /** 通过 Coil 加载视频帧（统一封装，placeholder 保留当前画面，videoFrameMillis(0) 取首帧） */
    private fun loadVideoFrameWithCoil(media: MediaFileEntity) {
        binding.detailImageView.setBackgroundColor(0)
        binding.detailImageView.load(media.uriString.toUri()) {
            placeholder(binding.detailImageView.drawable)
            crossfade(false)
            allowHardware(false)
            memoryCacheKey(media.recordKey)
            decoderFactory(VideoFrameDecoder.Factory())
            videoFrameMillis(0)
        }
    }

    /** 设置视频触摸覆盖层：单击播放、横滑切换 */
    private fun setupVideoTouchOverlay() {
        var sx = 0f; var sy = 0f; var sms = 0L; var si = false
        binding.videoTouchOverlay.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = event.x; sy = event.y
                    sms = System.currentTimeMillis()
                    si = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (si && (abs(event.x - sx) > 12.dp(requireContext()) || abs(event.y - sy) > 12.dp(requireContext()))) si = false
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isTapGesture(event, sx, sy, sms, si)) {
                        view.performClick()
                        startVideoPlayback(binding.detailVideoPlayer)
                    } else if (!si) {
                        val dx = event.x - sx; val dy = event.y - sy
                        if (abs(dx) > abs(dy)) moveBy(if (dx < 0f) 1 else -1)
                    }
                    si = false; true
                }
                else -> true
            }
        }
    }

    /** 判定是否为单击手势（未滑动 + 位移阈值 + 时间阈值） */
    private fun isTapGesture(event: MotionEvent, sx: Float, sy: Float, sms: Long, si: Boolean): Boolean {
        val threshold = 12.dp(requireContext())
        return si && abs(event.x - sx) < threshold && abs(event.y - sy) < threshold && System.currentTimeMillis() - sms < 400
    }

    /** 判定是否需要按需解码元数据（宽高任一为 null，或视频时长为 null） */
    private fun needsMetadataDecode(media: MediaFileEntity): Boolean {
        return media.width == null || media.height == null || (media.mediaType == MediaType.VIDEO && media.durationMillis == null)
    }

    private fun startVideoPlayback(player: BiliPlayerView) {
        val media = currentMedia ?: return
        // v1.15：幂等保护——播放中再次点击（双击/快速重点播放）直接返回，
        // 防止重复 setVideoUri 重建 MediaItem + prepare 导致播放被重置
        if (isVideoPlaying) return
        isVideoPlaying = true
        binding.detailImageView.isVisible = false
        binding.detailVideoPlayer.isVisible = true
        binding.videoTouchOverlay.isVisible = false
        binding.videoPlayButton.isVisible = false
        setChromeVisible(false)
        // v1.16 修复：播放中关闭退到预览（closeDetail→showVideoPreview）时 player 只 pause 未清源，
        // 同一 uri 再点播放若仍走 setMediaItem+prepare 会进度归零。此处同源且非 IDLE（stop 后为
        // IDLE，须重 prepare）时跳过重建，直接续播保留进度；否则按原逻辑准备新源
        val sameSource = exoPlayer?.currentMediaItem?.localConfiguration?.uri == media.uriString.toUri() &&
            exoPlayer?.playbackState != Player.STATE_IDLE
        if (!sameSource) {
            player.setVideoUri(media.uriString)
        }
        player.startPlayback()
        // v1.14：视频真正开始播放时记一次播放（修复 playCount 从无写入点，恒为 0 的问题）
        // v1.15：同一次详情打开每视频只计一次（会话级集合，滑走滑回不重复计数）
        if (playedRecordKeys.add(media.recordKey)) {
            viewModel.recordPlay(media.recordKey, media.fileName)
        }
    }

    private fun stopVideoPlayback() {
        if (!isVideoPlaying) return
        isVideoPlaying = false
        exoPlayer?.stop()
        binding.detailVideoPlayer.isVisible = false
    }

    private fun showVideoPreview() {
        isVideoPlaying = false
        exoPlayer?.pause()
        // 退出全屏播放时恢复竖屏
        if (binding.detailVideoPlayer.getFullscreen()) {
            restorePortraitOrientation()
        }
        binding.detailVideoPlayer.isVisible = false
        binding.detailImageView.isVisible = true
        binding.detailImageView.setBackgroundColor(0)
        binding.videoTouchOverlay.isVisible = true
        updateVideoPlayButtonVisibility()
        val media = currentMedia ?: return
        videoPreviewJob?.cancel()
        videoPreviewJob = viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = loadVideoFrame(media.uriString)
            if (bitmap != null && !bitmap.isRecycled && currentRecordKey == media.recordKey) {
                binding.detailImageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun updateVideoPlayButtonVisibility() {
        val isVideoPreview = currentMedia?.mediaType == MediaType.VIDEO && !isVideoPlaying
        binding.videoPlayButton.isVisible = isVideoPreview && chromeVisible
    }

    // 预加载 disposable 映射（recordKey → disposable），支持按 key 精确取消（问题5）
    private val preloadDisposables = mutableMapOf<String, coil3.request.Disposable>()

    /** 判断当前内存是否充裕，允许额外预渲染 */
    @Suppress("DEPRECATION")
    private fun isMemoryComfortable(): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMb = runtime.maxMemory() / 1024 / 1024
        // 已用内存低于堆上限 50% 视为充裕
        if (usedMb >= maxMb * 0.5) return false
        // v1.12 修复：超大图 Bitmap 分配在 native 堆，Java 堆占用不足以代表真实内存压力；
        // native 堆已用比例过高时同样视为内存紧张，收紧预加载范围
        val nativeAllocatedMb = Debug.getNativeHeapAllocatedSize() / 1024 / 1024
        val nativeTotalMb = Debug.getNativeHeapSize() / 1024 / 1024
        return nativeTotalMb <= 0 || nativeAllocatedMb < nativeTotalMb * 0.6
    }

    /** 是否为超大图（长边超过阈值，原图体积大，预加载需限量防 OOM） */
    private fun isHugeImage(media: MediaFileEntity): Boolean =
        maxOf(media.width ?: 0, media.height ?: 0) > HUGE_IMAGE_THRESHOLD

    /**
     * 双向预加载：基础1前2后，内存充裕时+1每侧（2前3后）。
     *
     * 设计说明（回退到原始方案 2026-06-19）：
     * - 每次切换取消所有旧预加载并重新预加载范围内所有项
     * - Coil 内部缓存去重：已命中的项预加载会直接跳过，不会重复解码
     * - 不在 Fragment 层做 isCoilCacheHit/isPreloadInProgress 提前判断（原始方案简洁且正确）
     * - 不主动移除缓存条目，完全交给 Coil LRU + onTrimMemory 管理
     * - 保留 Map 结构用于精确取消单个预加载（避免 List 的全量遍历）
     */
    private fun preloadAround(index: Int) {
        // 取消所有旧预加载请求（Coil 内部缓存去重，重新入队已命中的会直接跳过）
        preloadDisposables.values.forEach { it.dispose() }
        preloadDisposables.clear()

        val loader = SingletonImageLoader.get(requireContext())
        val memoryComfortable = isMemoryComfortable()
        // 超大图(长边>4096)原图体积大(8192≈150MB/张)，预渲染过多会 OOM。
        // 当前图是超大图时收紧预渲染范围（1前1后），否则维持基础 1前2后 / 内存充裕 2前3后。
        val currentLongside = mediaList.getOrNull(index)?.let { maxOf(it.width ?: 0, it.height ?: 0) } ?: 0
        val isHugeCurrent = currentLongside > HUGE_IMAGE_THRESHOLD
        val extra = if (memoryComfortable && !isHugeCurrent) 1 else 0
        val backCount = if (isHugeCurrent) 1 else 1 + extra   // 超大图1前 / 普通1+充裕1
        val forwardCount = if (isHugeCurrent) 1 else 2 + extra // 超大图1后 / 普通2+充裕1

        val start = maxOf(0, index - backCount)
        val end = minOf(mediaList.lastIndex, index + forwardCount)

        var enqueued = 0
        var hugeEnqueued = 0  // 本轮已入队的超大图数量
        for (i in start..end) {
            if (i == index) continue // 当前正在显示，不需要预加载
            if (i !in mediaList.indices) continue
            val media = mediaList[i]
            // v1.12 修复：原实现仅按"当前图"是否超大图收紧，当前为小图、邻项为 8192
            // 超大图时仍 2前3后 全量预载（5×约150MB≈750MB native，OOM 风险）。改为逐项判断：
            // 邻项本身是超大图时限量预载（窗口内最多 1 张），内存紧张时跳过全部超大图预载
            if (isHugeImage(media)) {
                if (!memoryComfortable || hugeEnqueued >= MAX_PRELOAD_HUGE) continue
                hugeEnqueued++
            }
            val isGif = media.fileName.substringAfterLast('.', "").equals("gif", ignoreCase = true)
            val request = LargeImageDecoder.buildPreloadRequest(
                requireContext(),
                media.uriString.toUri(),
                media.recordKey,
                media.mediaType == MediaType.VIDEO,
                isGif
            )
            val disposable = loader.enqueue(request)
            preloadDisposables[media.recordKey] = disposable
            enqueued++
        }
        // Coil 内部缓存去重：已命中的项 enqueue 会直接跳过不解码，enqueued 为本轮新入队数（不含命中的）
        AppLog.d("Detail", "preload: 范围[$start..$end] 当前=$index 内存充裕=$memoryComfortable 超大图=$isHugeCurrent 入队=$enqueued")
    }

    private suspend fun loadVideoFrame(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(requireContext(), uriString.toUri())
            // 取首帧（0ms），避免 seek 开销，与预加载 videoFrameMillis(0) 对齐（详见 GUIDE_DEBUG.md）
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun moveBy(direction: Int) {
        val targetIndex = currentIndex + direction
        if (targetIndex !in mediaList.indices) return
        if (isTransitioning) return
        binding.detailImageView.resetZoom()
        isTransitioning = true
        showMediaAt(targetIndex)
        // 防抖：延迟重置过渡标志，覆盖异步加载窗口，防止快速连续滑动竞态（问题3）
        viewLifecycleOwner.lifecycleScope.launch {
            delay(200)
            isTransitioning = false
        }
    }

    private fun toggleChrome() {
        setChromeVisible(!chromeVisible)
    }

    @Suppress("DEPRECATION")
    private fun setChromeVisible(visible: Boolean, animate: Boolean = true) {
        chromeVisible = visible
        syncSystemBars(visible)
        val chromeColor = if (visible) themeBgColor else Color.BLACK
        applyDetailBackground(chromeColor)
        requireActivity().window.statusBarColor = chromeColor
        requireActivity().window.navigationBarColor = chromeColor
        val tabBg = if (visible) {
            (0xF2 shl 24) or (themeBgColor and 0x00FFFFFF)
        } else {
            Color.TRANSPARENT
        }
        binding.detailTopBar.setBackgroundColor(tabBg)
        binding.detailBottomDock.setBackgroundColor(tabBg)
        updateVideoPlayButtonVisibility()
        if (!animate) {
            val alpha = if (visible) 1f else 0f
            binding.detailTopBar.alpha = alpha
            binding.detailBottomDock.alpha = alpha
            binding.detailTopBar.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            binding.detailBottomDock.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            return
        }
        binding.detailTopBar.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(250L)
            .withStartAction { if (visible) binding.detailTopBar.visibility = View.VISIBLE }
            .withEndAction { if (!visible) binding.detailTopBar.visibility = View.INVISIBLE }
            .start()
        binding.detailBottomDock.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(250L)
            .withStartAction { if (visible) binding.detailBottomDock.visibility = View.VISIBLE }
            .withEndAction { if (!visible) binding.detailBottomDock.visibility = View.INVISIBLE }
            .start()
    }

    // v1.12 删除：observeTags 空 collect 死代码（collect{} 丢弃全部值，无任何消费点），已移除

    private fun observeTimelineTags(recordKey: String) {
        timelineTagJob?.cancel()
        timelineTagJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.observeTimelineTags(recordKey).collect { tags ->
                if (currentRecordKey == recordKey) {
                    binding.detailVideoPlayer.updateTimelineTags(tags)
                }
            }
        }
    }

    private fun toggleLike() {
        val media = currentMedia ?: return
        val today = todayDateString()
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastLikeDate = prefs.getString(KEY_LIKE_DATE_PREFIX + media.recordKey, null)
        if (lastLikeDate == today) {
            Toast.makeText(requireContext(), "今天已经点过赞了", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit {
            putString(KEY_LIKE_DATE_PREFIX + media.recordKey, today)
        }
        val currentCount = prefs.getInt(KEY_LIKE_COUNT_PREFIX + media.recordKey, 0)
        prefs.edit {
            putInt(KEY_LIKE_COUNT_PREFIX + media.recordKey, currentCount + 1)
        }
        // v1.15：点赞写入后触发自动同步，不再等退出详情页才落盘（与 toggleAuthorFollow 一致）
        viewModel.triggerAutoSyncIfNeeded()
        updateLikeButton()
        Toast.makeText(requireContext(), "已点赞", Toast.LENGTH_SHORT).show()
    }

    private fun updateLikeButton() {
        val media = currentMedia ?: return
        val today = todayDateString()
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastLikeDate = prefs.getString(KEY_LIKE_DATE_PREFIX + media.recordKey, null)
        val likedToday = lastLikeDate == today
        binding.detailLikeBtn.setImageResource(
            if (likedToday) R.drawable.ic_detail_like_filled else R.drawable.ic_detail_like
        )
    }

    private fun todayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun toggleFavorite() {
        val media = currentMedia ?: return
        val favorites = favoriteKeys().toMutableSet()
        val added = if (favorites.contains(media.recordKey)) {
            favorites.remove(media.recordKey)
            false
        } else {
            favorites.add(media.recordKey)
            true
        }
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY_FAVORITES, favorites) }
        // v1.15：收藏写入后触发自动同步，不再等退出详情页才落盘（与 toggleAuthorFollow 一致）
        viewModel.triggerAutoSyncIfNeeded()
        updateFavoriteButton()
        Toast.makeText(requireContext(), if (added) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
    }

    private fun updateFavoriteButton() {
        val media = currentMedia ?: return
        val isFav = favoriteKeys().contains(media.recordKey)
        binding.detailFavoriteBtn.setImageResource(
            if (isFav) R.drawable.ic_detail_favorite_filled else R.drawable.ic_detail_favorite
        )
    }

    private fun favoriteKeys(): Set<String> = requireContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(KEY_FAVORITES, emptySet()).orEmpty()

    private fun showTagSheet() {
        val media = currentMedia ?: return
        val ctx = requireContext()
        AppLog.d("TagSheet", "open: 标签管理弹窗打开 recordKey=${media.recordKey}")
        val dialog = BottomSheetDialog(ctx)
        val container = sheetContainer(ctx)
        container.addView(sheetTitle(ctx, "标签管理"))

        val tagScroll = ScrollView(ctx)
        val tagContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        tagScroll.addView(tagContent)
        container.addView(tagScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 标签区域：ChipGroup 多行自适应 + 数据库 Flow 驱动增量刷新（见 TagSheetHelper）
        val tagFlowJob = TagSheetHelper.bind(viewLifecycleOwner, ctx, viewModel, media.recordKey, tagContent)

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp(requireContext()), 0, 12.dp(requireContext()))
        }
        val input = EditText(ctx).apply {
            hint = "输入标签"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
            setHintTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
            layoutParams = LinearLayout.LayoutParams(0, 48.dp(requireContext()), 1f)
        }
        val addButton = TextView(ctx).apply {
            text = "+"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorBg))
            setBackgroundResource(R.drawable.bg_capsule_primary)
            layoutParams = LinearLayout.LayoutParams(52.dp(requireContext()), 48.dp(requireContext())).apply { marginStart = 10.dp(requireContext()) }
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.addTag(media.recordKey, name)
                    Toast.makeText(ctx, "标签已添加：$name", Toast.LENGTH_SHORT).show()
                    input.text.clear()
                    // 标签列表由 TagSheetHelper 的 Flow 自动刷新，无需手动重建
                }
            }
        }
        inputRow.addView(input)
        inputRow.addView(addButton)
        container.addView(inputRow)
        container.addView(sheetDone(ctx, dialog))
        // 弹窗关闭时取消 Flow 收集（后续标签变化不再操作已销毁视图）
        dialog.setOnDismissListener { tagFlowJob.cancel() }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showJumpSheet() {
        val media = currentMedia ?: return
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx)
        val container = sheetContainer(ctx)
        container.addView(sheetTitle(ctx, "快速转跳"))

        val listContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(listContent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        container.addView(sheetDone(ctx, dialog))
        dialog.setContentView(container)
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val crossRefs = viewModel.authorsForMedia(media.recordKey).firstOrNull().orEmpty()
            val allAuthors = viewModel.authors.firstOrNull().orEmpty()
            val authorMap = allAuthors.associateBy { it.authorId }
            listContent.removeAllViews()

            if (crossRefs.isEmpty()) {
                listContent.addView(sheetSubText(ctx, "当前文件未关联作者"))
            } else {
                val sectionLabel = sheetSubText(ctx, "关联作者")
                listContent.addView(sectionLabel)
                crossRefs.forEach { ref ->
                    val author = authorMap[ref.authorId] ?: return@forEach
                    // 作者行：左侧作者名 + 右侧关注按钮
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 12.dp(requireContext()), 0, 12.dp(requireContext()))
                    }
                    val nameView = TextView(ctx).apply {
                        text = author.displayName
                        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener {
                            dialog.dismiss()
                            val isCos = author.authorId.startsWith("cos_")
                            (requireActivity() as? MainActivity)?.showAuthorFiles(author.authorId, isCos)
                        }
                    }
                    // 关注按钮：已关注显示实心星，未关注显示空心星
                    val followBtn = ImageView(ctx).apply {
                        val isFollowed = viewModel.isAuthorFollowed(author.authorId)
                        setImageResource(if (isFollowed) R.drawable.ic_followed else R.drawable.ic_follow)
                        setColorFilter(ctx.resolveThemeColor(R.attr.qmColorPrimary))
                        setPadding(8.dp(requireContext()), 4.dp(requireContext()), 0, 4.dp(requireContext()))
                        setOnClickListener {
                            val nowFollowed = viewModel.toggleAuthorFollow(author.authorId)
                            setImageResource(if (nowFollowed) R.drawable.ic_followed else R.drawable.ic_follow)
                            Toast.makeText(ctx, if (nowFollowed) "已关注" else "已取消关注", Toast.LENGTH_SHORT).show()
                        }
                    }
                    row.addView(nameView)
                    row.addView(followBtn)
                    listContent.addView(row)
                    listContent.addView(View(ctx).apply {
                        setBackgroundColor(ctx.resolveThemeColor(R.attr.qmColorDivider))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        )
                    })
                }
            }
        }
    }

    private fun showInfoSheet(media: MediaFileEntity) {
        // v1.15：防重复弹窗——解码期间用户可重复点击详情按钮，避免堆叠多个对话框
        if (infoSheetShowing) return
        infoSheetShowing = true
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx).apply {
            setOnDismissListener { infoSheetShowing = false }
        }
        val container = sheetContainer(ctx)
        container.addView(sheetTitle(ctx, "详细信息"))
        addInfoRow(container, "文件名", media.fileName)
        addInfoRow(container, "日期", formatDate(media.modifiedAtMillis))
        addInfoRow(container, "大小", formatSize(media.sizeBytes))
        addInfoRow(container, "类型", "${media.mediaType}/${media.extension.ifBlank { "unknown" }}")

        // 如果 width/height/durationMillis 为 null，按需解码
        if (needsMetadataDecode(media)) {
            // v1.15：先 show 对话框并展示可用的行，解码完成后补齐。
            // 原实现解码完成才 show，解码期间无反馈且持 Fragment 级 lifecycleScope
            // 协程引用 dialog，Fragment 退栈后仍操作已销毁视图。
            val sizeRowValue = addInfoRow(container, "尺寸", "解码中…")
            // v1.15 补修：无"完成"按钮——BottomSheet 靠下滑手势 / 点外部空白关闭，
            // 显式关闭按钮冗余（v1.0 历史遗留，已删除）。解码完成原地更新占位行 +
            // 追加 时长/目录/路径 即可。
            dialog.setContentView(container)
            dialog.show()
            viewLifecycleOwner.lifecycleScope.launch {
                val updated = decodeMediaMetadata(media)
                // v1.15 补修：原地更新"解码中…"占位行的文本，而非追加新行。
                // 原实现解码完成后再 addInfoRow("尺寸") 追加，弹窗同时出现两行
                // "尺寸"（一行"解码中…"一行实际值），流程显示异常。
                sizeRowValue.text = formatDimensions(updated)
                updated.durationMillis?.let { addInfoRow(container, "时长", formatDuration(it)) }
                addInfoRow(container, "目录", updated.folderName)
                addInfoRow(container, "路径", updated.uriString)
                dialog.setContentView(container)
            }
        } else {
            addInfoRow(container, "尺寸", formatDimensions(media))
            media.durationMillis?.let { addInfoRow(container, "时长", formatDuration(it)) }
            addInfoRow(container, "目录", media.folderName)
            addInfoRow(container, "路径", media.uriString)
            dialog.setContentView(container)
            dialog.show()
        }
    }

    /**
     * 按需解码媒体元数据（width/height/durationMillis），解码后更新数据库
     */
    private suspend fun decodeMediaMetadata(media: MediaFileEntity): MediaFileEntity {
        var updated = media
        withContext(Dispatchers.IO) {
            try {
                val uri = media.uriString.toUri()
                if (media.mediaType == MediaType.IMAGE && (media.width == null || media.height == null)) {
                    val bounds = context?.contentResolver?.openInputStream(uri)?.use { stream ->
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, options)
                        options.outWidth.takeIf { it > 0 } to options.outHeight.takeIf { it > 0 }
                    }
                    if (bounds != null) {
                        updated = updated.copy(width = bounds.first, height = bounds.second)
                    }
                } else if (media.mediaType == MediaType.VIDEO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(requireContext(), uri)
                        if (media.width == null || media.height == null) {
                            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 }
                            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 }
                            if (w != null && h != null) {
                                updated = updated.copy(width = w, height = h)
                            }
                        }
                        if (media.durationMillis == null) {
                            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }
                            if (dur != null) {
                                updated = updated.copy(durationMillis = dur)
                            }
                        }
                    } catch (e: RuntimeException) {
                        AppLog.w("MediaDetail", "视频元数据解码失败: ${media.recordKey}", e)
                    } finally {
                        retriever.release()
                    }
                }
                // 更新数据库缓存
                if (updated != media) {
                    viewModel.updateMediaMetadata(updated.recordKey, updated.width, updated.height, updated.durationMillis)
                }
            } catch (e: Exception) {
                AppLog.w("MediaDetail", "媒体元数据解码异常: ${media.recordKey}", e)
            }
        }
        return updated
    }

    private fun updateCurrentDuration() {
        val key = currentRecordKey ?: return
        viewModel.updateBrowseDuration(key, enterTimeMillis)
        enterTimeMillis = System.currentTimeMillis()
    }

    private var lastFullscreenToggleTime = 0L

    private fun closeDetail() {
        // 全屏播放中按返回：先退出全屏回到竖屏继续播放，不退出详情页
        if (isVideoPlaying && binding.detailVideoPlayer.getFullscreen()) {
            restorePortraitOrientation()
            return
        }
        if (isVideoPlaying) {
            showVideoPreview()
            setChromeVisible(true)
            restorePortraitOrientation()
            return
        }
        updateCurrentDuration()
        playedRecordKeys.clear()
        viewedRecordKeys.clear()  // v1.15：退出详情页时同步清空 viewCount 会话级去重集合
        restorePortraitOrientation()
        restoreMainWindow()
        syncSystemBars(true)
        (requireActivity() as? MainActivity)?.setDetailMode(false)
        (requireActivity() as? MainActivity)?.applyThemeColors()
        parentFragmentManager.popBackStack()
    }

    @Suppress("SourceLockedOrientationActivity")
    private fun toggleFullscreen() {
        val activity = activity ?: return
        // 防抖：800ms内不重复触发
        val now = System.currentTimeMillis()
        if (now - lastFullscreenToggleTime < 800) return
        lastFullscreenToggleTime = now
        val player = binding.detailVideoPlayer
        if (player.getFullscreen()) {
            // 退出全屏，恢复竖屏
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            player.setFullscreen(false)
            syncSystemBars(false)
        } else {
            // B站逻辑：只有横屏视频才进入全屏，竖屏视频点击无反应
            if (!player.isLandscapeVideo()) return
            // 使用固定横屏方向，避免 SENSOR_LANDSCAPE 在两个横屏方向间切换导致乱闪
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            player.setFullscreen(true)
            syncSystemBars(false)
        }
    }

    /** 恢复竖屏方向，退出全屏时调用 */
    @Suppress("SourceLockedOrientationActivity")
    private fun restorePortraitOrientation() {
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding.detailVideoPlayer.setFullscreen(false)
    }

    override fun onPause() {
        super.onPause()
        if (isVideoPlaying) {
            exoPlayer?.pause()
        }
        updateCurrentDuration()
    }

    override fun onResume() {
        super.onResume()
        if (isVideoPlaying) {
            exoPlayer?.play()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 全屏播放时，方向切换后重新隐藏系统栏，防止旋转时系统栏闪现
        if (isVideoPlaying && binding.detailVideoPlayer.getFullscreen()) {
            view?.post { syncSystemBars(false) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updateCurrentDuration()
        timelineTagJob?.cancel()
        videoPreviewJob?.cancel()
        // 取消所有预加载请求，释放预渲染内存
        preloadDisposables.values.forEach { it.dispose() }
        preloadDisposables.clear()
        exoPlayer?.release()
        exoPlayer = null
        restorePortraitOrientation()
        restoreMainWindow()
        syncSystemBars(true)
        (requireActivity() as? MainActivity)?.setDetailMode(false)
        (requireActivity() as? MainActivity)?.applyThemeColors()
        // 退出详情页时触发自动同步（只写 app数据/，JSON 快）
        // 使用 processLifecycleOwner 确保同步不受 Fragment 生命周期影响，但随 App 进程销毁而取消
        val autoSyncUseCase = (requireActivity().application as? com.qimeng.media.QimengApplication)
            ?.appContainer?.autoSyncUseCase
        if (autoSyncUseCase != null) {
            ProcessLifecycleOwner.get().lifecycleScope.launch(
                kotlinx.coroutines.Dispatchers.IO
            ) {
                autoSyncUseCase.triggerAutoSyncIfNeeded()
            }
        }
        _binding = null
    }

    private fun applyDetailBackground(color: Int) {
        binding.detailRoot.setBackgroundColor(color)
        requireActivity().findViewById<View>(R.id.main)?.setBackgroundColor(color)
    }

    @Suppress("DEPRECATION")
    private fun restoreMainWindow() {
        val mainView = requireActivity().findViewById<View>(R.id.main) ?: return
        mainView.setBackgroundColor(themeBgColor)
        val window = requireActivity().window
        window.statusBarColor = themeBgColor
        val navColor = ThemeHelper.resolve(requireContext()).surface
        window.navigationBarColor = navColor
    }

    private fun sheetContainer(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp(requireContext()), 18.dp(requireContext()), 20.dp(requireContext()), 28.dp(requireContext()))
        setBackgroundResource(R.drawable.bg_detail_sheet)
    }

    private fun sheetTitle(ctx: Context, textValue: String): TextView = TextView(ctx).apply {
        text = textValue
        gravity = Gravity.CENTER
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, 12.dp(requireContext()))
    }

    private fun sheetSubText(ctx: Context, textValue: String): TextView = TextView(ctx).apply {
        text = textValue
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
        textSize = 13f
        setPadding(0, 6.dp(requireContext()), 0, 6.dp(requireContext()))
    }

    /** 弹窗底部"完成"关闭按钮（标签管理/快速转跳弹窗使用；详细信息弹窗 v1.15 起不再使用） */
    private fun sheetDone(ctx: Context, dialog: BottomSheetDialog): TextView = TextView(ctx).apply {
        text = "完成"
        gravity = Gravity.CENTER
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorPrimary))
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 18.dp(requireContext()), 0, 0)
        setOnClickListener { dialog.dismiss() }
    }

    /**
     * 添加一行"键: 值"信息行。
     * @return value TextView 引用，供调用方按需原地更新文本（如"解码中…"占位替换）
     */
    private fun addInfoRow(container: LinearLayout, key: String, value: String): TextView {
        val ctx = container.context
        val valueView = TextView(ctx).apply {
            text = value
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
            textSize = 13f
            setTextIsSelectable(true)
        }
        container.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10.dp(requireContext()), 0, 10.dp(requireContext()))
            addView(TextView(ctx).apply {
                text = key
                setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
                textSize = 13f
            }, LinearLayout.LayoutParams(72.dp(requireContext()), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(valueView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        return valueView
    }

    private fun formatDate(millis: Long): String = MediaBrowserLogic.formatDate(millis)

    private fun formatSize(bytes: Long): String = MediaBrowserLogic.formatSize(bytes, zeroLabel = "-", decimals = 2)

    private fun formatDuration(millis: Long): String = MediaBrowserLogic.formatDuration(millis)

    private fun formatDimensions(media: MediaFileEntity): String {
        val width = media.width ?: return "-"
        val height = media.height ?: return "-"
        return "${width}x${height}"
    }

    companion object {
        private const val ARG_RECORD_KEY = "recordKey"
        private const val PREFS_NAME = "media_detail_prefs"
        private const val KEY_FAVORITES = "favorite_record_keys"
        private const val KEY_LIKE_DATE_PREFIX = "like_date_"
        private const val KEY_LIKE_COUNT_PREFIX = "like_count_"
        /** 分页每页加载数量 */
        private const val PAGE_SIZE = 40
        /** 距离边界多少项时触发预加载更多 */
        private const val LOAD_MORE_THRESHOLD = 5
        /** 超大图阈值：长边超过此值视为超大图，预渲染范围收紧(1前1后)防 OOM */
        private const val HUGE_IMAGE_THRESHOLD = 4096
        /** 预加载窗口内最多预载的超级大图张数（8192 原图 ≈150MB/张，超过 1 张易 OOM，v1.12 新增） */
        private const val MAX_PRELOAD_HUGE = 1
        /** v1.16：冷启动库为空时 loadDetailSource 的最大重试次数（5 × 300ms = 1.5s） */
        private const val DETAIL_SOURCE_MAX_RETRY = 5
        /** v1.16：每次重试的间隔毫秒数 */
        private const val DETAIL_SOURCE_RETRY_INTERVAL_MS = 300L

        fun newInstance(recordKey: String) = MediaDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_RECORD_KEY, recordKey) }
        }
    }
}
