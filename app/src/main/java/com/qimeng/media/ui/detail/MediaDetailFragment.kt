package com.qimeng.media.ui.detail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
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
import androidx.media3.exoplayer.ExoPlayer
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.load
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.size.Size
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameMillis
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.qimeng.media.MainActivity
import com.qimeng.media.R
import com.qimeng.media.ThemeHelper
import com.qimeng.media.isNightMode
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.data.db.entity.TimelineTagEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.core.AppLog
import com.qimeng.media.core.LargeImageDecoder
import com.qimeng.media.databinding.FragmentMediaDetailBinding
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.widget.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var chromeVisible = true
    private var tagJob: Job? = null
    private var timelineTagJob: Job? = null
    private var themeBgColor: Int = 0
    private var isTransitioning = false
    private var isVideoPlaying = false
    private var exoPlayer: ExoPlayer? = null
    private var videoPreviewJob: Job? = null

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
            TimelineTagHelper.showAddTimelineTagDialog(
                requireContext(),
                pos,
                formatted
            ) { timeMs, name ->
                val media = currentMedia ?: return@showAddTimelineTagDialog
                viewModel.addTimelineTag(media.recordKey, media.fileName, timeMs, name)
            }
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
            val allMedia = viewModel.allMedia.firstOrNull().orEmpty()
            val sourceKeys = (requireActivity() as? MainActivity).orEmptySourceKeys()
            val byKey = allMedia.associateBy { it.recordKey }
            mediaList = if (sourceKeys.isNotEmpty()) {
                sourceKeys.mapNotNull { byKey[it] }
            } else {
                allMedia
            }

            if (mediaList.none { it.recordKey == key }) {
                mediaList = allMedia.find { it.recordKey == key }?.let { listOf(it) }.orEmpty()
            }

            if (mediaList.isEmpty()) {
                parentFragmentManager.popBackStack()
                return@launch
            }

            currentIndex = mediaList.indexOfFirst { it.recordKey == key }.takeIf { it >= 0 } ?: 0
            showMediaAt(currentIndex, updatePreviousDuration = false)
        }
    }

    private fun MainActivity?.orEmptySourceKeys(): List<String> = this?.currentDetailSourceRecordKeys().orEmpty()

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

        binding.detailFileName.text = "%d/%d".format(index + 1, mediaList.size)
        updateLikeButton()
        updateFavoriteButton()
        observeTags(media.recordKey)
        observeTimelineTags(media.recordKey)
        viewModel.loadAndRecordView(media.recordKey) { }

        if (media.mediaType == MediaType.IMAGE || media.mediaType == MediaType.ANIMATED_IMAGE) {
            stopVideoPlayback()
            videoPreviewJob?.cancel()
            binding.detailVideoPlayer.isVisible = false
            binding.videoTouchOverlay.isVisible = false
            binding.videoPlayButton.isVisible = false
            binding.detailBottomDock.isVisible = chromeVisible
            binding.detailImageView.isVisible = true
            binding.detailImageView.setImageDrawable(null)
            binding.detailImageView.setBackgroundColor(0)
            val isGif = media.fileName.substringAfterLast('.', "").equals("gif", ignoreCase = true)
            val isPng = media.fileName.substringAfterLast('.', "").equals("png", ignoreCase = true)
            // 优先用 LargeImageDecoder 直接解码（PNG 用 libspng，其他用 decodeFileDescriptor），
            // 失败时回退到 Coil 标准流程
            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = LargeImageDecoder.decodeCurrentImage(
                    requireContext(), media.uriString.toUri(), media.recordKey, isGif, isPng
                )
                if (bitmap != null && !bitmap.isRecycled && currentRecordKey == media.recordKey) {
                    binding.detailImageView.setImageBitmap(bitmap)
                } else if (currentRecordKey == media.recordKey) {
                    // 回退到 Coil 标准流程
                    binding.detailImageView.load(media.uriString.toUri()) {
                        crossfade(false)
                        size(Size.ORIGINAL)
                        allowHardware(false)
                        memoryCacheKey(media.recordKey)
                        if (isGif) {
                            bitmapConfig(Bitmap.Config.ARGB_8888)
                            decoderFactory(AnimatedImageDecoder.Factory())
                        }
                    }
                }
            }
            preloadAround(index)
        } else {
            isVideoPlaying = false
            binding.detailVideoPlayer.isVisible = false
            binding.detailBottomDock.isVisible = chromeVisible
            binding.detailImageView.isVisible = true
            binding.detailImageView.setBackgroundColor(Color.BLACK)
            videoPreviewJob?.cancel()
            videoPreviewJob = viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = loadVideoFrame(media.uriString)
                if (bitmap != null && !bitmap.isRecycled && currentRecordKey == media.recordKey) {
                    binding.detailImageView.setImageBitmap(bitmap)
                } else if (currentRecordKey == media.recordKey) {
                    binding.detailImageView.load(media.uriString.toUri()) {
                        crossfade(false)
                        allowHardware(false)
                        decoderFactory(VideoFrameDecoder.Factory())
                        videoFrameMillis(3_000)
                    }
                }
            }
            binding.videoTouchOverlay.isVisible = true
            updateVideoPlayButtonVisibility()
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
                        if (si && abs(event.x - sx) < 12.dp(requireContext()) && abs(event.y - sy) < 12.dp(requireContext()) && System.currentTimeMillis() - sms < 400) {
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
            preloadAround(index)
        }
    }

    private fun startVideoPlayback(player: BiliPlayerView) {
        val media = currentMedia ?: return
        isVideoPlaying = true
        binding.detailImageView.isVisible = false
        binding.detailVideoPlayer.isVisible = true
        binding.videoTouchOverlay.isVisible = false
        binding.videoPlayButton.isVisible = false
        setChromeVisible(false)
        player.setVideoUri(media.uriString)
        player.startPlayback()
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
        binding.detailImageView.setBackgroundColor(Color.BLACK)
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

    // 预加载 disposable 列表，切换时统一取消
    private val preloadDisposables = mutableListOf<coil3.request.Disposable>()

    /** 判断当前内存是否充裕，允许额外预渲染 */
    private fun isMemoryComfortable(): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMb = runtime.maxMemory() / 1024 / 1024
        // 已用内存低于堆上限 50% 视为充裕
        return usedMb < maxMb * 0.5
    }

    /** 双向预加载：基础1前2后，内存充裕时+1每侧（2前3后） */
    private fun preloadAround(index: Int) {
        // 取消旧预加载请求
        preloadDisposables.forEach { it.dispose() }
        preloadDisposables.clear()

        val loader = SingletonImageLoader.get(requireContext())
        val extra = if (isMemoryComfortable()) 1 else 0
        val backCount = 1 + extra   // 基础1 + 充裕1
        val forwardCount = 2 + extra // 基础2 + 充裕1

        val start = maxOf(0, index - backCount)
        val end = minOf(mediaList.lastIndex, index + forwardCount)

        for (i in start..end) {
            if (i == index) continue // 当前正在显示，不需要预加载
            val media = mediaList[i]
            val isGif = media.fileName.substringAfterLast('.', "").equals("gif", ignoreCase = true)
            val request = LargeImageDecoder.buildPreloadRequest(
                requireContext(),
                media.uriString.toUri(),
                media.recordKey,
                media.mediaType == MediaType.VIDEO,
                isGif
            )
            val disposable = loader.enqueue(request)
            preloadDisposables.add(disposable)
        }
    }

    private suspend fun loadVideoFrame(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(requireContext(), uriString.toUri())
            retriever.getFrameAtTime(3_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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
        isTransitioning = false
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

    private fun observeTags(recordKey: String) {
        tagJob?.cancel()
        tagJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.tagEntitiesForMedia(recordKey).collect { }
        }
    }

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
                    // 不关闭对话框，刷新标签列表
                    refreshTagContent(tagContent, ctx, media.recordKey, dialog)
                }
            }
        }
        inputRow.addView(input)
        inputRow.addView(addButton)
        container.addView(inputRow)
        container.addView(sheetDone(ctx, dialog))
        dialog.setContentView(container)
        dialog.show()

        // 首次加载标签
        refreshTagContent(tagContent, ctx, media.recordKey, dialog)
    }

    /** 刷新标签管理对话框的内容区域 */
    private fun refreshTagContent(
        tagContent: LinearLayout,
        ctx: Context,
        recordKey: String,
        dialog: BottomSheetDialog
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentTags = viewModel.tagEntitiesForMedia(recordKey).firstOrNull().orEmpty()
            val allTags = viewModel.allTags.firstOrNull().orEmpty()
            tagContent.removeAllViews()

            val currentLabel = sheetSubText(ctx, "当前标签")
            tagContent.addView(currentLabel)
            if (currentTags.isEmpty()) {
                tagContent.addView(sheetSubText(ctx, "暂无标签"))
            } else {
                val activeRow = HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    setPadding(0, 4.dp(requireContext()), 0, 8.dp(requireContext()))
                }
                val activeStrip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                currentTags.forEach { tag ->
                    activeStrip.addView(activeTagChip(ctx, recordKey, tag) {
                        // 删除后刷新列表
                        refreshTagContent(tagContent, ctx, recordKey, dialog)
                    })
                }
                activeRow.addView(activeStrip)
                tagContent.addView(activeRow)
            }

            val otherTags = allTags.filter { it.name !in currentTags.map { t -> t.name } }
            if (otherTags.isNotEmpty()) {
                tagContent.addView(sheetSubText(ctx, "其他标签"))
                val suggestRow = HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    setPadding(0, 4.dp(requireContext()), 0, 8.dp(requireContext()))
                }
                val suggestStrip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                otherTags.forEach { tag ->
                    suggestStrip.addView(suggestTagChip(ctx, recordKey, tag) {
                        // 添加后刷新列表
                        refreshTagContent(tagContent, ctx, recordKey, dialog)
                    })
                }
                suggestRow.addView(suggestStrip)
                tagContent.addView(suggestRow)
            }
        }
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

    private fun activeTagChip(ctx: Context, recordKey: String, tag: TagEntity, onRemoved: () -> Unit = {}): Chip = Chip(ctx).apply {
        text = tag.name
        isCloseIconVisible = true
        setOnCloseIconClickListener {
            viewModel.removeTag(recordKey, tag.tagId)
            (parent as? ViewGroup)?.removeView(this)
            Toast.makeText(ctx, "标签已移除", Toast.LENGTH_SHORT).show()
            onRemoved()
        }
    }

    private fun suggestTagChip(
        ctx: Context, recordKey: String, tag: TagEntity, onAdded: () -> Unit = {}
    ): Chip = Chip(ctx).apply {
        text = tag.name
        setOnClickListener {
            viewModel.addTag(recordKey, tag.name)
            Toast.makeText(ctx, "标签已添加：${tag.name}", Toast.LENGTH_SHORT).show()
            onAdded()
        }
    }

    private fun showInfoSheet(media: MediaFileEntity) {
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx)
        val container = sheetContainer(ctx)
        container.addView(sheetTitle(ctx, "详细信息"))
        addInfoRow(container, "文件名", media.fileName)
        addInfoRow(container, "日期", formatDate(media.modifiedAtMillis))
        addInfoRow(container, "大小", formatSize(media.sizeBytes))
        addInfoRow(container, "类型", "${media.mediaType}/${media.extension.ifBlank { "unknown" }}")

        // 如果 width/height/durationMillis 为 null，按需解码
        if (media.width == null || media.height == null || (media.mediaType == MediaType.VIDEO && media.durationMillis == null)) {
            lifecycleScope.launch {
                val updated = decodeMediaMetadata(media)
                addInfoRow(container, "尺寸", formatDimensions(updated))
                updated.durationMillis?.let { addInfoRow(container, "时长", formatDuration(it)) }
                addInfoRow(container, "目录", updated.folderName)
                addInfoRow(container, "路径", updated.uriString)
                container.addView(sheetDone(ctx, dialog))
                dialog.setContentView(container)
                dialog.show()
            }
        } else {
            addInfoRow(container, "尺寸", formatDimensions(media))
            media.durationMillis?.let { addInfoRow(container, "时长", formatDuration(it)) }
            addInfoRow(container, "目录", media.folderName)
            addInfoRow(container, "路径", media.uriString)
            container.addView(sheetDone(ctx, dialog))
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
        tagJob?.cancel()
        timelineTagJob?.cancel()
        videoPreviewJob?.cancel()
        // 取消所有预加载请求，释放预渲染内存
        preloadDisposables.forEach { it.dispose() }
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
                autoSyncUseCase.triggerAutoSyncForDetailExit()
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

    private fun sheetDone(ctx: Context, dialog: BottomSheetDialog): TextView = TextView(ctx).apply {
        text = "完成"
        gravity = Gravity.CENTER
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorPrimary))
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 18.dp(requireContext()), 0, 0)
        setOnClickListener { dialog.dismiss() }
    }

    private fun addInfoRow(container: LinearLayout, key: String, value: String) {
        val ctx = container.context
        container.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10.dp(requireContext()), 0, 10.dp(requireContext()))
            addView(TextView(ctx).apply {
                text = key
                setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
                textSize = 13f
            }, LinearLayout.LayoutParams(72.dp(requireContext()), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(ctx).apply {
                text = value
                setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
                textSize = 13f
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
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

        fun newInstance(recordKey: String) = MediaDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_RECORD_KEY, recordKey) }
        }
    }
}
