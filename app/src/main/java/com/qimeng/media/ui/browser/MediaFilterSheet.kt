package com.qimeng.media.ui.browser

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.qimeng.media.R
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.ui.widget.dp
import java.util.Calendar

data class FilterConfig(
    val showSort: Boolean = true,
    val showDirection: Boolean = true,
    val showViews: Boolean = true,
    val showPlays: Boolean = true,
    val showSize: Boolean = true,
    val showTimeRange: Boolean = true,
    val showTagMode: Boolean = true,
    val showTags: Boolean = true
) {
    companion object {
        val FULL = FilterConfig()
        val FOR_HOME = FilterConfig()
        val FOR_ALL = FilterConfig()
        val FOR_AUTHOR = FilterConfig()
        val FOR_ALBUM = FilterConfig()
    }
}

object MediaFilterSheet {
    private val CURRENT_YEAR = Calendar.getInstance().get(Calendar.YEAR)
    private const val YEAR_MIN = 2010

    /** 筛选状态持有者，解决子方法间传递 var state 的闭包问题 */
    private class FilterStateHolder(var value: MediaFilterState)

    fun show(
        context: Context,
        current: MediaFilterState,
        tags: List<TagEntity>,
        config: FilterConfig = FilterConfig.FULL,
        onApply: (MediaFilterState) -> Unit,
        onAddTag: (String) -> Unit = {},
        onDeleteTag: (Long) -> Unit = {}
    ) {
        val dialog = BottomSheetDialog(context)
        val holder = FilterStateHolder(current)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.qm_surface))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(context), 12.dp(context), 20.dp(context), 12.dp(context))
        }
        val scroll = NestedScrollView(context).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.62f).toInt()
        ))

        content.addView(headerLabel(context, "筛选"))

        if (config.showSort) {
            content.addView(section(context, "排序方式"))
            content.addView(singleGroup(context, sortOptions(), holder.value.sortKey) { holder.value = holder.value.copy(sortKey = it) })
        }
        if (config.showDirection) {
            content.addView(section(context, "顺位"))
            content.addView(singleGroup(context, directionOptions(), holder.value.sortDirection) { holder.value = holder.value.copy(sortDirection = it) })
        }
        if (config.showViews) {
            content.addView(section(context, "观看次数"))
            content.addView(singleGroup(context, viewRangeOptions(), holder.value.viewRange) { holder.value = holder.value.copy(viewRange = it) })
        }
        if (config.showPlays) {
            content.addView(section(context, "点击次数"))
            content.addView(singleGroup(context, playRangeOptions(), holder.value.playRange) { holder.value = holder.value.copy(playRange = it) })
        }
        if (config.showSize) {
            content.addView(section(context, "文件大小"))
            content.addView(singleGroup(context, sizeRangeOptions(), holder.value.sizeRange) { holder.value = holder.value.copy(sizeRange = it) })
        }
        val (startPicker, endPicker) = if (config.showTimeRange) {
            appendTimeRangeSection(content, context, holder)
        } else {
            Pair(null, null)
        }
        if (config.showTagMode) {
            content.addView(section(context, "标签模式"))
            content.addView(singleGroup(context, tagModeOptions(), holder.value.tagMode) { holder.value = holder.value.copy(tagMode = it) })
        }
        if (config.showTags) {
            appendTagsSection(content, context, holder, tags, onAddTag, onDeleteTag)
        }

        root.addView(buildFooter(context, holder, startPicker, endPicker, onApply, dialog))
        dialog.setContentView(root)
        dialog.behavior.isDraggable = true
        dialog.show()
    }

    /** 时间范围块：含 NumberPicker 年份选择，返回 (startPicker, endPicker) 供 footer 读取 */
    private fun appendTimeRangeSection(
        content: LinearLayout, context: Context, holder: FilterStateHolder
    ): Pair<NumberPicker?, NumberPicker?> {
        content.addView(section(context, "时间范围"))
        val yearRangeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (holder.value.dateRange == MediaDateRange.YEAR_RANGE) View.VISIBLE else View.GONE
        }
        val startPicker = NumberPicker(context).apply {
            minValue = YEAR_MIN
            maxValue = CURRENT_YEAR
            value = holder.value.yearStart.coerceIn(YEAR_MIN, CURRENT_YEAR)
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val endPicker = NumberPicker(context).apply {
            minValue = YEAR_MIN
            maxValue = CURRENT_YEAR
            value = holder.value.yearEnd.coerceIn(YEAR_MIN, CURRENT_YEAR)
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val dashLabel = TextView(context).apply {
            text = "—"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.qm_text_primary))
            setPadding(8.dp(context), 0, 8.dp(context), 0)
        }
        yearRangeRow.addView(startPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        yearRangeRow.addView(dashLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        yearRangeRow.addView(endPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(singleGroup(context, dateRangeOptions(), holder.value.dateRange) { newRange ->
            holder.value = holder.value.copy(dateRange = newRange)
            yearRangeRow.visibility = if (newRange == MediaDateRange.YEAR_RANGE) View.VISIBLE else View.GONE
        })
        content.addView(yearRangeRow)
        return Pair(startPicker, endPicker)
    }

    /** 标签块：Chip 选择/长按删除/添加标签对话框 */
    private fun appendTagsSection(
        content: LinearLayout, context: Context, holder: FilterStateHolder,
        tags: List<TagEntity>, onAddTag: (String) -> Unit, onDeleteTag: (Long) -> Unit
    ) {
        content.addView(section(context, "标签"))
        val tagGroup = ChipGroup(context).apply { isSingleLine = false }
        tags.forEach { tag ->
            tagGroup.addView(Chip(context).apply {
                text = tag.name
                isCheckable = true
                isChecked = tag.name in holder.value.selectedTags
                setOnClickListener {
                    val next = holder.value.selectedTags.toMutableSet()
                    if (isChecked) next.add(tag.name) else next.remove(tag.name)
                    holder.value = holder.value.copy(selectedTags = next)
                }
                setOnLongClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("删除标签")
                        .setMessage("确定删除标签「${tag.name}」？删除后所有文件的该标签关联将一并移除。")
                        .setPositiveButton("删除") { _, _ ->
                            onDeleteTag(tag.tagId)
                            (parent as? ViewGroup)?.removeView(this)
                            val next = holder.value.selectedTags.toMutableSet()
                            next.remove(tag.name)
                            holder.value = holder.value.copy(selectedTags = next)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            })
        }
        val addTagBtn = TextView(context).apply {
            text = "+ 添加标签"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.qm_primary))
            setPadding(0, 8.dp(context), 0, 8.dp(context))
            setOnClickListener {
                val input = EditText(context).apply {
                    hint = "标签名称"
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                }
                AlertDialog.Builder(context)
                    .setTitle("添加标签")
                    .setView(input)
                    .setPositiveButton("添加") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) {
                            onAddTag(name)
                            // 立即在 ChipGroup 中添加新标签，消除延迟感
                            val newChip = Chip(context).apply {
                                text = name
                                isCheckable = true
                                isChecked = false
                                setOnClickListener {
                                    val next = holder.value.selectedTags.toMutableSet()
                                    if (isChecked) next.add(name) else next.remove(name)
                                    holder.value = holder.value.copy(selectedTags = next)
                                }
                            }
                            tagGroup.addView(newChip)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        content.addView(tagGroup)
        content.addView(addTagBtn)
    }

    /** 底部按钮栏：重置 + 应用筛选（含年份范围交叉校验） */
    private fun buildFooter(
        context: Context, holder: FilterStateHolder,
        startPicker: NumberPicker?, endPicker: NumberPicker?,
        onApply: (MediaFilterState) -> Unit, dialog: BottomSheetDialog
    ): LinearLayout {
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20.dp(context), 12.dp(context), 20.dp(context), 24.dp(context))
        }
        footer.addView(actionButton(context, "重置", primary = false) {
            dialog.dismiss()
            onApply(MediaFilterState())
        }, LinearLayout.LayoutParams(0, 48.dp(context), 1f).apply { marginEnd = 8.dp(context) })
        footer.addView(actionButton(context, "应用筛选", primary = true) {
            dialog.dismiss()
            if (holder.value.dateRange == MediaDateRange.YEAR_RANGE) {
                val sp = startPicker
                val ep = endPicker
                if (sp != null && ep != null) {
                    val start = sp.value.coerceAtMost(ep.value)
                    val end = ep.value.coerceAtLeast(sp.value)
                    holder.value = holder.value.copy(yearStart = start, yearEnd = end)
                }
            }
            onApply(holder.value)
        }, LinearLayout.LayoutParams(0, 48.dp(context), 1f).apply { marginStart = 8.dp(context) })
        return footer
    }

    private fun <T> singleGroup(
        context: Context,
        options: List<Pair<T, String>>,
        current: T,
        onSelected: (T) -> Unit
    ): ChipGroup = ChipGroup(context).apply {
        isSingleSelection = true
        isSingleLine = false
        options.forEach { (value, label) ->
            addView(Chip(context).apply {
                text = label
                isCheckable = true
                isChecked = value == current
                setOnClickListener { onSelected(value) }
            })
        }
    }

    private fun headerLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(ContextCompat.getColor(context, R.color.qm_text_primary))
        setPadding(0, 4.dp(context), 0, 10.dp(context))
    }

    private fun section(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(ContextCompat.getColor(context, R.color.qm_text_secondary))
        setPadding(0, 14.dp(context), 0, 4.dp(context))
    }

    private fun actionButton(context: Context, text: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(if (primary) R.drawable.bg_capsule_primary else R.drawable.bg_capsule_soft)
            setTextColor(ContextCompat.getColor(context, if (primary) R.color.qm_bg else R.color.qm_text_primary))
            setOnClickListener { onClick() }
        }

    private fun sortOptions() = listOf(
        MediaSortKey.DEFAULT to "默认",
        MediaSortKey.DATE to "文件日期",
        MediaSortKey.INDEXED_DATE to "添加日期",
        MediaSortKey.VIEWS to "观看次数",
        MediaSortKey.PLAYS to "点击次数",
        MediaSortKey.SIZE to "文件大小",
        MediaSortKey.NAME to "名字"
    )

    private fun directionOptions() = listOf(
        MediaSortDirection.DESC to "降序",
        MediaSortDirection.ASC to "升序"
    )

    private fun viewRangeOptions() = listOf(
        MediaViewRange.ALL to "全部",
        MediaViewRange.NONE to "未观看",
        MediaViewRange.FEW to "1-5次",
        MediaViewRange.SOME to "5-20次",
        MediaViewRange.MANY to ">20次"
    )

    private fun playRangeOptions() = listOf(
        MediaPlayRange.ALL to "全部",
        MediaPlayRange.NONE to "未点击",
        MediaPlayRange.FEW to "1-5次",
        MediaPlayRange.SOME to "5-20次",
        MediaPlayRange.MANY to ">20次"
    )

    private fun sizeRangeOptions() = listOf(
        MediaSizeRange.ALL to "全部",
        MediaSizeRange.SMALL to "<1MB",
        MediaSizeRange.MEDIUM to "1-10MB",
        MediaSizeRange.LARGE to "10-50MB",
        MediaSizeRange.XLARGE to ">50MB"
    )

    private fun dateRangeOptions() = listOf(
        MediaDateRange.ALL to "全部",
        MediaDateRange.TODAY to "今天",
        MediaDateRange.WEEK to "本周",
        MediaDateRange.MONTH to "本月",
        MediaDateRange.QUARTER to "近三月",
        MediaDateRange.YEAR to "本年",
        MediaDateRange.YEAR_RANGE to "按年份"
    )

    private fun tagModeOptions() = listOf(
        MediaTagMode.FUZZY to "模糊（含任一）",
        MediaTagMode.EXACT to "精确（含全部）"
    )

}
