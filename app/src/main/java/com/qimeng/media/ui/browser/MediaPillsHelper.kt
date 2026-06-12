package com.qimeng.media.ui.browser

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.qimeng.media.R
import com.qimeng.media.ThemeColors
import com.qimeng.media.ThemeHelper
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.ui.widget.FlowLayout

/**
 * 药丸渲染工具类，封装出处/角色/分区/类型药丸的共享渲染逻辑。
 * AllFilesFragment、FavoriteFragment、BrowseHistoryFragment 共用。
 */
object MediaPillsHelper {

    /** 创建 FlowLayout 实例 */
    fun createFlowLayout(context: Context): FlowLayout {
        return FlowLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 0)
        }
    }

    /** 创建单个药丸 TextView */
    fun createPill(context: Context, text: String, active: Boolean, colors: ThemeColors): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(if (active) colors.bg else colors.textSecondary)
            background = if (active) {
                context.getDrawable(R.drawable.bg_capsule_primary)
            } else {
                null
            }
            gravity = Gravity.CENTER
            val density = context.resources.displayMetrics.density
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (30 * density).toInt()
            )
        }
    }

    /** 渲染出处药丸列表 */
    fun renderSourcePills(
        context: Context,
        wrapper: LinearLayout,
        scroller: View,
        sourceGroups: Map<String, List<MediaFileEntity>>,
        selectedSources: MutableSet<String>,
        expanded: Boolean,
        onPillClick: () -> Unit,      // 胶囊项点击：更新选中状态，不收起
        onCollapse: () -> Unit         // 收起按钮点击
    ): Boolean {
        if (sourceGroups.isEmpty()) {
            scroller.visibility = View.GONE
            return false
        }
        wrapper.removeAllViews()
        scroller.visibility = View.VISIBLE
        val colors = ThemeHelper.resolve(context)

        if (!expanded) {
            scroller.visibility = View.GONE
            return false
        }

        val sorted = sourceGroups.entries.filter { it.key != "其他" }.sortedByDescending { it.value.size }
        val otherEntry = sourceGroups["其他"]
        val flow = createFlowLayout(context)

        val allPill = createPill(context, "全部 (${sourceGroups.values.sumOf { it.size }})", selectedSources.isEmpty(), colors)
        allPill.setOnClickListener {
            selectedSources.clear()
            onPillClick()
        }
        flow.addView(allPill)

        for ((sourceName, files) in sorted) {
            val pill = createPill(context, "$sourceName (${files.size})", sourceName in selectedSources, colors)
            pill.setOnClickListener {
                if (sourceName in selectedSources) selectedSources.remove(sourceName) else selectedSources.add(sourceName)
                onPillClick()
            }
            flow.addView(pill)
        }

        if (otherEntry != null) {
            val pill = createPill(context, "其他 (${otherEntry.size})", "其他" in selectedSources, colors)
            pill.setOnClickListener {
                if ("其他" in selectedSources) selectedSources.remove("其他") else selectedSources.add("其他")
                onPillClick()
            }
            flow.addView(pill)
        }

        val collapsePill = createPill(context, "收起 ▲", false, colors)
        collapsePill.setOnClickListener {
            onCollapse()
        }
        flow.addView(collapsePill)
        wrapper.addView(flow)
        return true
    }

    /** 渲染角色药丸列表 */
    fun renderCharPills(
        context: Context,
        wrapper: LinearLayout,
        scroller: View,
        charGroups: Map<String, List<MediaFileEntity>>,
        selectedChars: MutableSet<String>,
        expanded: Boolean,
        onPillClick: () -> Unit,
        onCollapse: () -> Unit
    ): Boolean {
        if (charGroups.isEmpty()) {
            scroller.visibility = View.GONE
            return false
        }
        wrapper.removeAllViews()
        scroller.visibility = View.VISIBLE
        val colors = ThemeHelper.resolve(context)

        if (!expanded) {
            scroller.visibility = View.GONE
            return false
        }

        val sorted = charGroups.entries.filter { it.key != "其他" }.sortedByDescending { it.value.size }
        val otherEntry = charGroups["其他"]
        val flow = createFlowLayout(context)

        val allPill = createPill(context, "全部 (${charGroups.values.sumOf { it.size }})", selectedChars.isEmpty(), colors)
        allPill.setOnClickListener {
            selectedChars.clear()
            onPillClick()
        }
        flow.addView(allPill)

        for ((charName, files) in sorted) {
            val pill = createPill(context, "$charName (${files.size})", charName in selectedChars, colors)
            pill.setOnClickListener {
                if (charName in selectedChars) selectedChars.remove(charName) else selectedChars.add(charName)
                onPillClick()
            }
            flow.addView(pill)
        }

        if (otherEntry != null) {
            val pill = createPill(context, "其他 (${otherEntry.size})", "其他" in selectedChars, colors)
            pill.setOnClickListener {
                if ("其他" in selectedChars) selectedChars.remove("其他") else selectedChars.add("其他")
                onPillClick()
            }
            flow.addView(pill)
        }

        val collapsePill = createPill(context, "收起 ▲", false, colors)
        collapsePill.setOnClickListener {
            onCollapse()
        }
        flow.addView(collapsePill)
        wrapper.addView(flow)
        return true
    }

    /** 渲染COS作品药丸列表 */
    fun renderCosWorkPills(
        context: Context,
        wrapper: LinearLayout,
        scroller: View,
        charGroups: Map<String, List<MediaFileEntity>>,
        selectedChars: MutableSet<String>,
        expanded: Boolean,
        onPillClick: () -> Unit,
        onCollapse: () -> Unit
    ) {
        wrapper.removeAllViews()
        val sorted = charGroups.entries.sortedByDescending { it.value.size }

        if (sorted.isEmpty()) {
            scroller.visibility = View.GONE
            return
        }

        if (!expanded) {
            scroller.visibility = View.GONE
            return
        }

        scroller.visibility = View.VISIBLE
        val flowLayout = createFlowLayout(context)
        val colors = ThemeHelper.resolve(context)

        val allPill = createPill(context, "全部 (${charGroups.values.sumOf { it.size }})", selectedChars.isEmpty(), colors)
        allPill.setOnClickListener {
            selectedChars.clear()
            onPillClick()
        }
        flowLayout.addView(allPill)

        for ((workName, files) in sorted) {
            val pill = createPill(context, "$workName (${files.size})", workName in selectedChars, colors)
            pill.setOnClickListener {
                if (workName in selectedChars) selectedChars.remove(workName) else selectedChars.add(workName)
                onPillClick()
            }
            flowLayout.addView(pill)
        }

        val collapsePill = createPill(context, "收起 ▲", false, colors)
        collapsePill.setOnClickListener {
            onCollapse()
        }
        flowLayout.addView(collapsePill)

        wrapper.addView(flowLayout)
    }

    /** 渲染类型药丸列表 */
    fun renderTypePills(
        context: Context,
        wrapper: LinearLayout,
        scroller: View,
        workingMedia: List<MediaFileEntity>,
        filterType: String?,
        expanded: Boolean,
        onFilterChanged: (String?) -> Unit,
        onPillClick: () -> Unit,
        onCollapse: () -> Unit
    ) {
        wrapper.removeAllViews()
        scroller.visibility = View.VISIBLE
        val colors = ThemeHelper.resolve(context)

        if (!expanded) {
            scroller.visibility = View.GONE
            return
        }

        val imageCount = workingMedia.count { it.mediaType == MediaType.IMAGE }
        val videoCount = workingMedia.count { it.mediaType == MediaType.VIDEO }
        val animCount = workingMedia.count { it.mediaType == MediaType.ANIMATED_IMAGE }

        val flow = createFlowLayout(context)
        val typeOptions = listOf(
            null to "全部 (${workingMedia.size})",
            MediaType.IMAGE to "图片 ($imageCount)",
            MediaType.VIDEO to "视频 ($videoCount)",
            MediaType.ANIMATED_IMAGE to "动图 ($animCount)"
        )
        for ((type, label) in typeOptions) {
            val pill = createPill(context, label, filterType == type, colors)
            pill.setOnClickListener {
                onFilterChanged(type)
                onPillClick()
            }
            flow.addView(pill)
        }

        val collapsePill = createPill(context, "收起 ▲", false, colors)
        collapsePill.setOnClickListener {
            onCollapse()
        }
        flow.addView(collapsePill)
        wrapper.addView(flow)
    }
}
