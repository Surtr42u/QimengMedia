package com.qimeng.media.ui.stats

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qimeng.media.R
import com.qimeng.media.databinding.ItemRankListBinding
import com.qimeng.media.resolveThemeColor

/**
 * 排行榜列表 Adapter（v1.7 数据统计页）。
 *
 * 用于展示完整排行榜（Top 10），每行显示排名、标题、副标题、数值，
 * 点击整行触发回调（跳转文件详情/作者文件页）。
 */
class RankListAdapter(
    private val onItemClick: (RankItem) -> Unit
) : ListAdapter<RankListAdapter.RankItem, RankListAdapter.ViewHolder>(DiffCallback) {

    /** 排行榜条目 */
    data class RankItem(
        val id: String?,
        val title: String,
        val subtitle: String?,
        val value: Int,
        val valueLabel: String,
        /** 本批次最大值（用于计算进度条百分比，第一名=100%） */
        val maxValue: Int = value
    )

    inner class ViewHolder(val binding: ItemRankListBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRankListBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            rankText.text = "${position + 1}"
            titleText.text = item.title
            subtitleText.text = item.subtitle ?: ""
            subtitleText.visibility = if (item.subtitle.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            valueText.text = item.valueLabel

            // 前三名排名数字高亮
            if (position < 3) {
                rankText.setTextColor(root.context.resolveThemeColor(R.attr.qmColorPrimary))
            } else {
                rankText.setTextColor(root.context.resolveThemeColor(R.attr.qmColorTextSecondary))
            }

            // 进度条：相对第一名的百分比
            val percent = if (item.maxValue > 0) {
                (item.value.toFloat() / item.maxValue * 100).toInt().coerceIn(1, 100)
            } else {
                0
            }
            rankProgressBar.progress = percent

            root.setOnClickListener {
                if (item.id != null) onItemClick(item)
            }
            root.isClickable = item.id != null
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<RankItem>() {
        override fun areItemsTheSame(oldItem: RankItem, newItem: RankItem) = oldItem.id == newItem.id && oldItem.title == newItem.title
        override fun areContentsTheSame(oldItem: RankItem, newItem: RankItem) = oldItem == newItem
    }
}
