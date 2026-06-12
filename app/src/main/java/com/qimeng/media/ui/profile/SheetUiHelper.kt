package com.qimeng.media.ui.profile

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.qimeng.media.R
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.ui.widget.dp

/**
 * 弹窗 UI 组件构建辅助类
 * 从 ProfileFragment 提取，负责统一构建底部弹窗的 UI 组件
 */
internal object SheetUiHelper {

    fun sheetContainer(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp(ctx), 18.dp(ctx), 20.dp(ctx), 28.dp(ctx))
        setBackgroundResource(R.drawable.bg_detail_sheet)
    }

    fun sheetTitle(ctx: Context, value: String): TextView = TextView(ctx).apply {
        text = value
        gravity = Gravity.CENTER
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, 10.dp(ctx))
    }

    fun sheetSubText(ctx: Context, value: String): TextView = TextView(ctx).apply {
        text = value
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
        textSize = 13f
        setPadding(0, 4.dp(ctx), 0, 12.dp(ctx))
    }

    fun sheetLabel(ctx: Context, value: String): TextView = TextView(ctx).apply {
        text = value
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
        textSize = 12f
        setPadding(0, 12.dp(ctx), 0, 6.dp(ctx))
    }

    fun sheetInfoRow(ctx: Context, name: String): TextView = TextView(ctx).apply {
        text = name
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
        textSize = 14f
        setBackgroundResource(R.drawable.bg_empty_panel)
        setPadding(16.dp(ctx), 12.dp(ctx), 16.dp(ctx), 12.dp(ctx))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 6.dp(ctx) }
    }

    fun sheetSourceRow(
        ctx: Context,
        name: String,
        uriString: String,
        onDelete: () -> Unit,
        onRefresh: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_empty_panel)
        setPadding(16.dp(ctx), 10.dp(ctx), 10.dp(ctx), 10.dp(ctx))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 6.dp(ctx) }

        // 左侧：名称 + 路径（垂直排列）
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = name
                setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
                textSize = 14f
            })
            // 显示路径（从 URI 中提取可读部分）
            val pathDisplay = uriString.substringAfter("tree/").substringAfter(":").replace("%3A", ":").replace("%2F", "/")
            if (pathDisplay.isNotBlank()) {
                addView(TextView(ctx).apply {
                    text = pathDisplay
                    setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
                    textSize = 11f
                    maxLines = 2
                    setSingleLine(false)
                })
            }
        })

        if (onRefresh != null) {
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_refresh)
                setPadding(8.dp(ctx), 8.dp(ctx), 8.dp(ctx), 8.dp(ctx))
                setColorFilter(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
                setOnClickListener {
                    onRefresh()
                }
            })
        }

        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_remove_item)
            setPadding(8.dp(ctx), 8.dp(ctx), 8.dp(ctx), 8.dp(ctx))
            setColorFilter(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
            setOnClickListener {
                onDelete()
            }
        })
    }

    fun sheetActionButton(
        ctx: Context,
        title: String,
        action: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = title
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorPrimary))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setBackgroundResource(R.drawable.bg_empty_panel)
        gravity = Gravity.CENTER
        setPadding(18.dp(ctx), 14.dp(ctx), 18.dp(ctx), 14.dp(ctx))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 6.dp(ctx) }
        setOnClickListener { action() }
    }

    fun sheetAction(
        ctx: Context,
        title: String,
        action: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = title
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
        textSize = 15f
        setBackgroundResource(R.drawable.bg_empty_panel)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(18.dp(ctx), 0, 18.dp(ctx), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            56.dp(ctx)
        ).apply { bottomMargin = 10.dp(ctx) }
        setOnClickListener { action() }
    }
}
