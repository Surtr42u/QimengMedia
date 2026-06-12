package com.qimeng.media.ui.detail

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.qimeng.media.R
import com.qimeng.media.data.db.entity.TimelineTagEntity
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.ui.widget.dp

/**
 * 视频时间轴标签弹窗辅助类
 * 从 MediaDetailFragment 提取，负责添加/编辑/删除时间轴标签的 UI 交互
 */
internal object TimelineTagHelper {

    fun showAddTimelineTagDialog(
        context: Context,
        currentPositionMs: Long,
        formattedTime: String,
        onAdd: (timeMs: Long, name: String) -> Unit
    ) {
        val ctx = context
        val dialog = BottomSheetDialog(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(ctx), 20.dp(ctx), 24.dp(ctx), 24.dp(ctx))
            setBackgroundResource(R.drawable.bg_detail_sheet)
        }

        // 标题
        container.addView(TextView(ctx).apply {
            text = "添加时间轴标记"
            gravity = Gravity.CENTER
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8.dp(ctx))
        })
        // 当前时间
        container.addView(TextView(ctx).apply {
            text = "当前时间：$formattedTime"
            gravity = Gravity.CENTER
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
            textSize = 13f
            setPadding(0, 0, 0, 16.dp(ctx))
        })

        // 快捷标记区
        container.addView(TextView(ctx).apply {
            text = "快捷标记"
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
            textSize = 12f
            setPadding(0, 4.dp(ctx), 0, 8.dp(ctx))
        })
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16.dp(ctx))
        }
        presetRow.addView(createPresetTagButton(ctx, "❤️ 喜欢") {
            onAdd(currentPositionMs, "❤️ 喜欢")
            dialog.dismiss()
        })
        presetRow.addView(createPresetTagButton(ctx, "⭐ 收藏") {
            onAdd(currentPositionMs, "⭐ 收藏")
            dialog.dismiss()
        })
        container.addView(presetRow)

        // 自定义标记区
        container.addView(TextView(ctx).apply {
            text = "自定义标记"
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
            textSize = 12f
            setPadding(0, 4.dp(ctx), 0, 8.dp(ctx))
        })
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(ctx).apply {
            hint = "输入标签名称"
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
            setHintTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
            textSize = 14f
            setBackgroundResource(R.drawable.bg_empty_panel)
            setPadding(16.dp(ctx), 12.dp(ctx), 16.dp(ctx), 12.dp(ctx))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8.dp(ctx)
            }
            maxLines = 1
        }
        val addBtn = TextView(ctx).apply {
            text = "添加"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_capsule_primary)
            setPadding(20.dp(ctx), 12.dp(ctx), 20.dp(ctx), 12.dp(ctx))
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    onAdd(currentPositionMs, name)
                    dialog.dismiss()
                }
            }
        }
        inputRow.addView(input)
        inputRow.addView(addBtn)
        container.addView(inputRow)

        dialog.setContentView(container)
        dialog.show()
    }

    /** 创建快捷预设标签按钮 */
    fun createPresetTagButton(
        context: Context,
        text: String,
        onClick: () -> Unit
    ): TextView {
        val ctx = context
        return TextView(ctx).apply {
            this.text = text
            setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
            textSize = 14f
            setBackgroundResource(R.drawable.bg_empty_panel)
            setPadding(16.dp(ctx), 10.dp(ctx), 16.dp(ctx), 10.dp(ctx))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 8.dp(ctx)
            }
            setOnClickListener { onClick() }
        }
    }

    /** 长按标签芯片弹出操作菜单（跳转/删除） */
    fun showTimelineTagOptionsDialog(
        context: Context,
        tag: TimelineTagEntity,
        formattedTime: String,
        onSeek: (Long) -> Unit,
        onDelete: (Long) -> Unit
    ) {
        val ctx = context
        val options = arrayOf("跳转到此位置", "删除此标记")
        AlertDialog.Builder(ctx)
            .setTitle("$formattedTime ${tag.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onSeek(tag.timeMillis)
                    1 -> onDelete(tag.timelineTagId)
                }
            }
            .show()
    }
}
