package com.qimeng.media.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.qimeng.media.R
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.ui.browser.MediaDateGroup
import com.qimeng.media.ui.widget.dp
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupedMediaAdapter(
    private val onItemClick: (MediaFileEntity, List<MediaFileEntity>) -> Unit,
    private val lifecycleScope: LifecycleCoroutineScope? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val differ = AsyncListDiffer(this, RowDiffCallback())
    private var source: List<MediaFileEntity> = emptyList()
    private var lastKeys: String = ""

    /**
     * 提交序号（防过期覆盖）：每次提交（同步/异步）递增。
     * 异步计算在 Default 线程完成后若序号已过期（期间发生新提交）则丢弃结果，
     * 避免调用方 lifecycleScope 不受 renderJob.cancel() 管控时，旧列表晚到覆盖新列表。
     * 仅在主线程读写，无并发问题。
     */
    private var submitSeq = 0

    /** 浏览时间映射：recordKey → openedAtMillis，用于历史页面按浏览时间分组 */
    var viewedAtMap: Map<String, Long> = emptyMap()

    /** 缩略图解码尺寸，列数越少尺寸越大（默认480x270） */
    var thumbnailWidth = 480
    var thumbnailHeight = 270

    /** 附着的 RecyclerView 上下文（v1.16：updateThumbnailSize 估算屏宽用） */
    private var recyclerViewContext: android.content.Context? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recyclerViewContext = recyclerView.context
    }

    /**
     * 按列数更新缩略图解码尺寸（v1.16）：按屏宽/列数估算单列宽度，clamp 到 240..1440，
     * 高度按 16:9 推导。列数变化（PinchZoom 回调/初始 setup）时调用，
     * 避免固定 480x270 导致 2 列模糊、5 列浪费。
     */
    fun updateThumbnailSize(columns: Int) {
        if (columns <= 0) return
        val widthPixels = recyclerViewContext?.resources?.displayMetrics?.widthPixels
            ?: android.content.res.Resources.getSystem().displayMetrics.widthPixels
        val estimated = widthPixels / columns
        thumbnailWidth = estimated.coerceIn(240, 1440)
        thumbnailHeight = thumbnailWidth * 9 / 16
    }

    fun submitMedia(items: List<MediaFileEntity>) {
        submitSeq++ // 同步提交同样使在途异步提交过期
        val fingerprint = items.hashCode().toString() + "_" + items.size
        if (fingerprint == lastKeys) return
        lastKeys = fingerprint
        source = items
        val newRows = buildList {
            groupByDateOrViewedAt(items, viewedAtMap).forEach { group ->
                add(Row.Header(group.dateLabel, group.items.size))
                group.items.forEach { add(Row.Media(it)) }
            }
        }
        differ.submitList(newRows)
    }

    /**
     * 异步提交（v1.12）：在 Default 线程计算 items.hashCode() 指纹与日期分组，主线程提交。
     * 解决 submitMedia() 在调用方主线程执行 O(n) 指纹计算与分组造成的卡顿。
     * lastKeys 指纹去重语义与同步路径完全一致；viewedAtMap 在主线程快照后再供后台线程只读。
     */
    fun submitMediaAsync(items: List<MediaFileEntity>, scope: LifecycleCoroutineScope) {
        val viewedAtSnapshot = viewedAtMap
        val seq = ++submitSeq // 记录本次提交序号，计算期间若有新提交则本次结果过期
        scope.launch {
            val (fingerprint, rows) = withContext(Dispatchers.Default) {
                val fp = items.hashCode().toString() + "_" + items.size
                val newRows = buildList {
                    groupByDateOrViewedAt(items, viewedAtSnapshot).forEach { group ->
                        add(Row.Header(group.dateLabel, group.items.size))
                        group.items.forEach { add(Row.Media(it)) }
                    }
                }
                fp to newRows
            }
            // 回到主线程提交：lastKeys/source 保持主线程单线程读写，与同步路径语义一致
            // 序号已过期（期间发生新提交）则丢弃，避免旧列表晚到覆盖新列表
            if (seq != submitSeq) return@launch
            if (fingerprint == lastKeys) return@launch
            lastKeys = fingerprint
            source = items
            differ.submitList(rows)
        }
    }

    fun submitMediaWithGroups(items: List<MediaFileEntity>, groups: Map<String, List<MediaFileEntity>>) {
        submitSeq++ // 同步提交同样使在途异步提交过期
        val fingerprint = items.hashCode().toString() + "_g_" + groups.keys.hashCode()
        if (fingerprint == lastKeys) return
        lastKeys = fingerprint
        source = items
        val newRows = buildList {
            groups.forEach { (label, groupItems) ->
                add(Row.Header(label, groupItems.size))
                groupItems.forEach { add(Row.Media(it)) }
            }
        }
        differ.submitList(newRows)
    }

    /**
     * 异步提交（v1.12）：在 Default 线程计算 groups.keys.hashCode() 指纹并组装行，主线程提交。
     * lastKeys 指纹去重语义与同步路径一致。
     */
    fun submitMediaWithGroupsAsync(
        items: List<MediaFileEntity>,
        groups: Map<String, List<MediaFileEntity>>,
        scope: LifecycleCoroutineScope
    ) {
        val seq = ++submitSeq // 记录本次提交序号，计算期间若有新提交则本次结果过期
        scope.launch {
            val (fingerprint, rows) = withContext(Dispatchers.Default) {
                val fp = items.hashCode().toString() + "_g_" + groups.keys.hashCode()
                val newRows = buildList {
                    groups.forEach { (label, groupItems) ->
                        add(Row.Header(label, groupItems.size))
                        groupItems.forEach { add(Row.Media(it)) }
                    }
                }
                fp to newRows
            }
            // 序号已过期（期间发生新提交）则丢弃，避免旧列表晚到覆盖新列表
            if (seq != submitSeq) return@launch
            if (fingerprint == lastKeys) return@launch
            lastKeys = fingerprint
            source = items
            differ.submitList(rows)
        }
    }

    /** 按日期分组：如果有 viewedAtMap 则按浏览时间，否则按文件修改时间 */
    private fun groupByDateOrViewedAt(
        items: List<MediaFileEntity>,
        viewedAt: Map<String, Long>
    ): List<MediaDateGroup> {
        if (items.isEmpty()) return emptyList()
        val now = Calendar.getInstance()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return items.groupBy { item ->
            val millis = viewedAt[item.recordKey] ?: item.modifiedAtMillis
            MediaBrowserLogic.dateLabel(millis, now, todayStart)
        }.map { (label, groupItems) -> MediaDateGroup(label, groupItems) }
    }

    fun isHeader(position: Int): Boolean = differ.currentList.getOrNull(position) is Row.Header

    override fun getItemViewType(position: Int): Int = when (differ.currentList[position]) {
        is Row.Header -> VIEW_TYPE_HEADER
        is Row.Media -> VIEW_TYPE_MEDIA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderHolder(TextView(parent.context).apply {
                setTextColor(context.resolveThemeColor(R.attr.qmColorPrimary))
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(4.dp(context), 18.dp(context), 4.dp(context), 10.dp(context))
            })
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_thumbnail, parent, false)
            MediaThumbnailAdapter.Holder(view, lifecycleScope)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = differ.currentList[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Media -> {
                val mediaHolder = holder as MediaThumbnailAdapter.Holder
                mediaHolder.bind(row.item, thumbnailWidth, thumbnailHeight)
                mediaHolder.itemView.setOnClickListener { onItemClick(row.item, source) }
            }
        }
    }

    override fun getItemCount(): Int = differ.currentList.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MediaThumbnailAdapter.Holder) {
            holder.decodeJob?.cancel()
        }
    }

    private class HeaderHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
        fun bind(header: Row.Header) {
            textView.text = "${header.label}  ${header.count} 项"
        }
    }

    sealed class Row {
        data class Header(val label: String, val count: Int) : Row()
        data class Media(val item: MediaFileEntity) : Row()
    }

    private class RowDiffCallback : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean {
            return when {
                oldItem is Row.Header && newItem is Row.Header -> oldItem.label == newItem.label
                oldItem is Row.Media && newItem is Row.Media -> oldItem.item.recordKey == newItem.item.recordKey
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean {
            return oldItem == newItem
        }
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 1
        const val VIEW_TYPE_MEDIA = 2
    }
}
