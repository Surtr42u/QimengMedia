package com.qimeng.media.ui.adapter

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.placeholder
import coil3.request.error
import coil3.request.allowHardware
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameMillis
import com.qimeng.media.R
import com.qimeng.media.core.AppLog
import com.qimeng.media.core.ThumbnailCache
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.core.ThumbnailLoader
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaThumbnailAdapter(
    private val onItemClick: (MediaFileEntity, List<MediaFileEntity>) -> Unit,
    private val lifecycleScope: LifecycleCoroutineScope? = null
) : ListAdapter<MediaFileEntity, MediaThumbnailAdapter.Holder>(DiffCallback()) {

    /** 缩略图解码尺寸，列数越少尺寸越大（默认480x270，1列时960x540） */
    var thumbnailWidth = 480
    var thumbnailHeight = 270

    /**
     * 限制 adapter 后台解码并发数，防止快速滑动时大量 decodeJob 与预生成争抢 IO 和内存导致 OOM。
     * 预生成使用自己的并发池（6线程），adapter 最多 2 个并发解码，避免叠加后超出内存上限。
     */
    companion object {
        private val adapterDecodeSemaphore = java.util.concurrent.Semaphore(2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_thumbnail, parent, false)
        return Holder(view, lifecycleScope)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.bind(item, thumbnailWidth, thumbnailHeight)
        holder.itemView.setOnClickListener { onItemClick(item, currentList) }
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        holder.decodeJob?.cancel()
    }

    class Holder(
        itemView: View,
        private val lifecycleScope: LifecycleCoroutineScope? = null
    ) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.thumbnailImage)
        private val durationBadge: TextView = itemView.findViewById(R.id.thumbnailDurationBadge)
        var decodeJob: Job? = null

        init {
            image.clipToOutline = true
            image.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 24f)
                }
            }
        }

        /**
         * 统一缩略图加载策略（图片和视频一致）：
         * 1. 先查本地缓存文件（ThumbnailCache），用 Coil 加载缓存文件（Coil 自动管理内存缓存）
         * 2. 没有缓存则后台解码并缓存，先用 Coil 加载原始 URI
         * 3. 解码完成后更新为缓存文件路径（下次滚动回来直接从 Coil 内存缓存读取）
         */
        fun bind(item: MediaFileEntity, tw: Int, th: Int) {
            val isVideo = item.mediaType == MediaType.VIDEO
            val isGif = item.fileName.substringAfterLast('.', "").equals("gif", ignoreCase = true)

            durationBadge.isVisible = isVideo && item.durationMillis != null
            durationBadge.text = item.durationMillis?.let(::formatDuration).orEmpty()

            // GIF缩略图直接加载原始URI（使用AnimatedImageDecoder产生动画），不走缓存文件
            // 设置memoryCacheKey确保滑动回来时从Coil内存缓存读取，避免重复解码
            if (isGif) {
                val uri = item.uriString.toUri()
                image.load(uri) {
                    crossfade(false)
                    placeholder(R.color.qm_placeholder)
                    error(R.color.qm_placeholder)
                    allowHardware(false)
                    memoryCacheKey(item.recordKey)
                    decoderFactory(AnimatedImageDecoder.Factory())
                }
                return
            }

            // 1. 先查本地缓存文件，用 Coil 加载（Coil 自动管理内存缓存，滚动回来极快）
            val cacheFile = ThumbnailCache.getThumbnailFile(itemView.context, item.recordKey)
            if (cacheFile.exists()) {
                image.load(cacheFile) {
                    crossfade(false)
                    placeholder(R.color.qm_placeholder)
                    error(R.color.qm_placeholder)
                    size(tw, th)
                    allowHardware(true)
                    memoryCacheKey(item.recordKey)
                }
                return
            }

            // 2. 没有缓存，先用 Coil 加载原始 URI（图片直接加载，视频截帧）
            val uri = item.uriString.toUri()
            if (isVideo) {
                image.load(uri) {
                    crossfade(false)
                    placeholder(R.color.qm_placeholder)
                    error(R.color.qm_placeholder)
                    size(tw, th)
                    // VideoFrameDecoder 不支持硬件 Bitmap，必须 false
                    allowHardware(false)
                    decoderFactory(VideoFrameDecoder.Factory())
                    videoFrameMillis(0)
                    memoryCacheKey(item.recordKey)
                }
            } else {
                image.load(uri) {
                    crossfade(false)
                    placeholder(R.color.qm_placeholder)
                    error(R.color.qm_placeholder)
                    size(tw, th)
                    allowHardware(true)
                    memoryCacheKey(item.recordKey)
                }
            }

            // 3. 后台解码并缓存到本地文件（下次加载直接从缓存文件读取）
            // 限制并发数防止快速滑动时大量解码与预生成争抢 IO/内存导致 OOM 卡死
            if (lifecycleScope != null) {
                val scope = lifecycleScope
                decodeJob?.cancel()
                decodeJob = scope.launch(Dispatchers.IO) {
                    // 预生成可能已在解码此文件，先检查缓存是否已存在
                    val cacheFile = ThumbnailCache.getThumbnailFile(itemView.context, item.recordKey)
                    if (cacheFile.exists()) return@launch
                    adapterDecodeSemaphore.acquire()
                    try {
                        // 再次检查，等待信号量期间预生成可能已创建缓存
                        if (ThumbnailCache.getThumbnailFile(itemView.context, item.recordKey).exists()) return@launch
                        val bitmap = if (isVideo) {
                            ThumbnailLoader.loadVideoThumbnail(itemView.context, item.uriString)
                        } else {
                            ThumbnailLoader.loadImageThumbnail(itemView.context, item.uriString)
                        }
                        if (bitmap != null) {
                            ThumbnailCache.writeThumbnail(itemView.context, item.recordKey, bitmap)
                        }
                    } finally {
                        adapterDecodeSemaphore.release()
                    }
                }
            }
        }

        private fun formatDuration(millis: Long): String = MediaBrowserLogic.formatDuration(millis)
    }

    private class DiffCallback : DiffUtil.ItemCallback<MediaFileEntity>() {
        override fun areItemsTheSame(old: MediaFileEntity, new: MediaFileEntity) =
            old.recordKey == new.recordKey
        override fun areContentsTheSame(old: MediaFileEntity, new: MediaFileEntity) =
            old == new
    }
}
