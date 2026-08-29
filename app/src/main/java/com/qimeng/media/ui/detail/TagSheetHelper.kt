package com.qimeng.media.ui.detail

import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.qimeng.media.R
import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.widget.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 详情页标签管理弹窗辅助类（从 MediaDetailFragment 提取）。
 *
 * 职责：负责"当前标签/其他标签"两组 ChipGroup 的 Flow 驱动渲染——
 * 标签增删后数据库变化经 Flow 自动同步，chip 按双指针 diff 只做增量增删。
 * 与旧实现（每次增删手动查库 + removeAllViews 全量重画，18 个标签实测
 * 每次主线程阻塞 20~60ms；且"立即查库重建"会与异步写库竞态读到旧数据，
 * 表现为删除后 chip 短暂复活）的区别：
 * - chip 只在变化段重建，未变化的 chip 原实例保留，不再闪动；
 * - 渲染唯一数据源是 Room 观察流，写入完成后必然回放，不会读到旧状态。
 *
 * 数据安全：本类零写入，仅观察 Flow（tagEntitiesForMedia/allTags）与触发
 * viewModel.addTag/removeTag；标签实体、关联表、备份 JSON 格式均不变。
 */
internal object TagSheetHelper {

    /**
     * 绑定弹窗内容区到标签观察流，并构建两组多行自适应 ChipGroup。
     *
     * @param content 弹窗内 ScrollView 的内容容器（VERTICAL），本方法在其中追加
     *                "当前标签/其他标签" 两组区域（含空态提示，其他标签区无数据时隐藏）
     * @return 收集 Job，弹窗关闭时必须 [cancel]（否则 Flow 持续收集并泄漏引用）
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        ctx: Context,
        viewModel: MediaLibraryViewModel,
        recordKey: String,
        content: LinearLayout
    ): Job {
        content.addView(sectionTitle(ctx, "当前标签"))
        val emptyHint = sectionTitle(ctx, "暂无标签")
        content.addView(emptyHint)
        val currentGroup = chipGroup(ctx)
        content.addView(currentGroup)

        val suggestSection = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        suggestSection.addView(sectionTitle(ctx, "其他标签"))
        val suggestGroup = chipGroup(ctx)
        suggestSection.addView(suggestGroup)
        content.addView(suggestSection)
        suggestSection.visibility = View.GONE

        fun activeChip(tag: TagEntity): Chip = Chip(ContextThemeWrapper(ctx, R.style.ThemeOverlay_QimengTagChip)).apply {
            text = tag.name
            setTag(tag.tagId)
            // 当前标签=已选中语义：不启用 checkable（防 Filter chip 点击本体自动 toggle 取消实底），
            // 直接实底主题色 + 背景色文字，与筛选药丸选中态同一套胶囊语言
            isCheckable = false
            chipBackgroundColor = ColorStateList.valueOf(ctx.resolveThemeColor(R.attr.qmColorPrimary))
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorBg))
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                // 详情页删除：仅解除当前文件与该标签的关联，不删除标签选项本身
                viewModel.removeTag(recordKey, tag.tagId)
                Toast.makeText(ctx, "标签已移除", Toast.LENGTH_SHORT).show()
            }
        }

        fun suggestChip(tag: TagEntity): Chip = Chip(ContextThemeWrapper(ctx, R.style.ThemeOverlay_QimengTagChip)).apply {
            text = tag.name
            setTag(tag.tagId)
            isCheckable = false
            setOnClickListener {
                viewModel.addTag(recordKey, tag.name)
                Toast.makeText(ctx, "标签已添加：${tag.name}", Toast.LENGTH_SHORT).show()
            }
        }

        return lifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.tagEntitiesForMediaNewestFirst(recordKey),
                viewModel.allTagsNewestFirst
            ) { current, all ->
                // 其他标签 = 全库标签 - 当前文件已关联标签
                current to all.filter { allTag -> current.none { it.tagId == allTag.tagId } }
            }.collect { (currentTags, otherTags) ->
                val syncStart = SystemClock.uptimeMillis()
                syncChipGroup(currentGroup, currentTags) { activeChip(it) }
                emptyHint.visibility = if (currentTags.isEmpty()) View.VISIBLE else View.GONE
                suggestSection.visibility = if (otherTags.isNotEmpty()) View.VISIBLE else View.GONE
                syncChipGroup(suggestGroup, otherTags) { suggestChip(it) }
                AppLog.d(
                    "TagSheet",
                    "render: 流更新 当前=${currentTags.size} 其他=${otherTags.size} 增量同步耗=${SystemClock.uptimeMillis() - syncStart}ms"
                )
            }
        }
    }

    /**
     * 增量同步 chip 组：组内 chip 与目标列表按 tagId 双指针 diff，
     * 只移除/新增中间变化段，未变化的 chip 原实例保留（避免重画与闪动）。
     * 列表按"创建时间倒序 + 名字升序"（DAO ORDER BY 保证，新标签置顶），
     * 组内 chip 顺序与之一一对应，任意位置的插入/删除均能被前缀+后缀匹配正确对位。
     */
    private fun syncChipGroup(
        group: ChipGroup,
        tags: List<TagEntity>,
        chipFactory: (TagEntity) -> Chip
    ) {
        fun idAt(index: Int): Long? = (group.getChildAt(index) as? Chip)?.tag as? Long
        val childCount = group.childCount
        val size = tags.size

        // 公共前缀对齐
        var head = 0
        while (head < childCount && head < size && idAt(head) == tags[head].tagId) head++
        // 已完全一致：无需改动
        if (head == childCount && head == size) return
        // 公共后缀对齐
        var tail = 0
        while (tail < childCount - head && tail < size - head &&
            idAt(childCount - 1 - tail) == tags[size - 1 - tail].tagId
        ) tail++

        // 移除旧段（倒序 remove，防止索引错位）
        var remove = childCount - tail
        while (remove > head) {
            remove--
            group.removeViewAt(remove)
        }
        // 补齐新段（按列表顺序插入原位置，保持 name 排序）
        for (i in head until size - tail) {
            group.addView(chipFactory(tags[i]), i)
        }
    }

    /** 多行自动换行芯片组：标签少时一行，多时自动 2~N 行，弹窗内可上下滚动 */
    private fun chipGroup(ctx: Context): ChipGroup = ChipGroup(ctx).apply {
        isSingleSelection = false
        chipSpacingHorizontal = 8.dp(ctx)
        chipSpacingVertical = 4.dp(ctx)
    }

    private fun sectionTitle(ctx: Context, value: String): TextView = TextView(ctx).apply {
        text = value
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
        textSize = 13f
        setPadding(0, 6.dp(ctx), 0, 6.dp(ctx))
    }
}
