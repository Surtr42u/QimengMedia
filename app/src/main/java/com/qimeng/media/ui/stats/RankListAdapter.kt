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

    companion object {
        /** 空态占位条目哨兵 id：与真实条目 id（recordKey/作者名/标签名）空间隔离，避免 DiffUtil 误判同一项 */
        private const val EMPTY_PLACEHOLDER_ID = "__empty__"

        /** 空态占位条目（v1.16）：数据为空时排行卡显示"暂无数据"，不再只剩卡片标题 */
        private val EMPTY_PLACEHOLDER = RankItem(
            id = EMPTY_PLACEHOLDER_ID,
            title = "暂无数据",
            subtitle = null,
            value = 0,
            valueLabel = ""
        )
    }

    /**
     * v1.16：空列表提交占位条目（文案与 DataStatsFragment.renderTextList 空态一致），
     * 调用方无需感知；有数据时按原样提交。
     */
    override fun submitList(list: List<RankItem>?) {
        if (list.isNullOrEmpty()) {
            super.submitList(listOf(EMPTY_PLACEHOLDER))
        } else {
            super.submitList(list)
        }
    }

    /** 双参重载同样拦截空列表，防止带 commitCallback 的调用绕过占位逻辑 */
    override fun submitList(list: List<RankItem>?, commitCallback: Runnable?) {
        if (list.isNullOrEmpty()) {
            super.submitList(listOf(EMPTY_PLACEHOLDER), commitCallback)
        } else {
            super.submitList(list, commitCallback)
        }
    }

    inner class ViewHolder(val binding: ItemRankListBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRankListBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        // v1.16：空态占位条目特殊处理——不显示排名序号、整行不可点击
        val isEmptyPlaceholder = item.id == EMPTY_PLACEHOLDER_ID
        with(holder.binding) {
            rankText.text = if (isEmptyPlaceholder) "—" else "${position + 1}"
            titleText.text = item.title
            subtitleText.text = item.subtitle ?: ""
            subtitleText.visibility = if (item.subtitle.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            valueText.text = item.valueLabel

            // 前三名排名数字高亮（占位条目不高亮）
            if (!isEmptyPlaceholder && position < 3) {
                rankText.setTextColor(root.context.resolveThemeColor(R.attr.qmColorPrimary))
            } else {
                rankText.setTextColor(root.context.resolveThemeColor(R.attr.qmColorTextSecondary))
            }

            // 进度条：相对第一名的百分比（占位条目 maxValue=0 自然落到 0 分支）
            // v1.14 修复：value<=0 或 max<=0 时显示 0，避免 0 值被 coerceIn 强制成 1% 且防止除零
            val percent = if (item.maxValue > 0 && item.value > 0) {
                (item.value.toFloat() / item.maxValue * 100).toInt().coerceIn(1, 100)
            } else {
                0
            }
            rankProgressBar.progress = percent

            root.setOnClickListener {
                if (!isEmptyPlaceholder && item.id != null) onItemClick(item)
            }
            root.isClickable = !isEmptyPlaceholder && item.id != null
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<RankItem>() {
        // v1.16 修复：身份只用 id 比较——并入 title 会导致同 id 换标题被误判为新条目（删除+新增动画），
        // 且不同 id 同标题的条目被误判为同一项（漏刷新）。内容差异由 areContentsTheSame 的全字段比较覆盖
        override fun areItemsTheSame(oldItem: RankItem, newItem: RankItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: RankItem, newItem: RankItem) = oldItem == newItem
    }
}
