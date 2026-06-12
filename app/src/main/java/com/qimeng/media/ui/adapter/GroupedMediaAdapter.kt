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

class GroupedMediaAdapter(
    private val onItemClick: (MediaFileEntity, List<MediaFileEntity>) -> Unit,
    private val lifecycleScope: LifecycleCoroutineScope? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val differ = AsyncListDiffer(this, RowDiffCallback())
    private var source: List<MediaFileEntity> = emptyList()
    private var lastKeys: String = ""

    /** 浏览时间映射：recordKey → openedAtMillis，用于历史页面按浏览时间分组 */
    var viewedAtMap: Map<String, Long> = emptyMap()

    /** 缩略图解码尺寸，列数越少尺寸越大（默认480x270） */
    var thumbnailWidth = 480
    var thumbnailHeight = 270

    fun submitMedia(items: List<MediaFileEntity>) {
        val fingerprint = items.hashCode().toString() + "_" + items.size
        if (fingerprint == lastKeys) return
        lastKeys = fingerprint
        source = items
        val newRows = buildList {
            groupByDateOrViewedAt(items).forEach { group ->
                add(Row.Header(group.dateLabel, group.items.size))
                group.items.forEach { add(Row.Media(it)) }
            }
        }
        differ.submitList(newRows)
    }

    fun submitMediaWithGroups(items: List<MediaFileEntity>, groups: Map<String, List<MediaFileEntity>>) {
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

    /** 按日期分组：如果有 viewedAtMap 则按浏览时间，否则按文件修改时间 */
    private fun groupByDateOrViewedAt(items: List<MediaFileEntity>): List<MediaDateGroup> {
        if (items.isEmpty()) return emptyList()
        val now = Calendar.getInstance()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return items.groupBy { item ->
            val millis = viewedAtMap[item.recordKey] ?: item.modifiedAtMillis
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
