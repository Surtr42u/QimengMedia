package com.qimeng.media.ui.detail

import android.content.Context
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.qimeng.media.R
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.ui.browser.MediaBrowserLogic
import com.qimeng.media.ui.library.MediaLibraryViewModel
import com.qimeng.media.ui.widget.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 详情页 BottomSheet 弹窗构建工具。
 * 从 MediaDetailFragment 提取，负责标签管理、快速转跳、文件信息弹窗的 UI 构建。
 */
object DetailSheetHelper {

    fun sheetContainer(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp(ctx), 18.dp(ctx), 20.dp(ctx), 28.dp(ctx))
        setBackgroundResource(R.drawable.bg_detail_sheet)
    }

    fun sheetTitle(ctx: Context, textValue: String): TextView = TextView(ctx).apply {
        text = textValue
        gravity = Gravity.CENTER
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, 12.dp(ctx))
    }

    fun sheetSubText(ctx: Context, textValue: String): TextView = TextView(ctx).apply {
        text = textValue
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
        textSize = 13f
        setPadding(0, 6.dp(ctx), 0, 6.dp(ctx))
    }

    fun sheetDone(ctx: Context, dialog: BottomSheetDialog): TextView = TextView(ctx).apply {
        text = "完成"
        gravity = Gravity.CENTER
        setTextColor(ctx.resolveThemeColor(R.attr.qmColorPrimary))
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, 18.dp(ctx), 0, 0)
        setOnClickListener { dialog.dismiss() }
    }

    fun addInfoRow(container: LinearLayout, key: String, value: String) {
        val ctx = container.context
        container.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10.dp(ctx), 0, 10.dp(ctx))
            addView(TextView(ctx).apply {
                text = key
                setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextSecondary))
                textSize = 13f
            }, LinearLayout.LayoutParams(72.dp(ctx), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(ctx).apply {
                text = value
                setTextColor(ctx.resolveThemeColor(R.attr.qmColorTextPrimary))
                textSize = 13f
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    fun activeTagChip(ctx: Context, recordKey: String, tag: TagEntity, viewModel: MediaLibraryViewModel): Chip = Chip(ctx).apply {
        text = tag.name
        isCloseIconVisible = true
        setOnCloseIconClickListener {
            viewModel.removeTag(recordKey, tag.tagId)
            (parent as? android.view.ViewGroup)?.removeView(this)
            Toast.makeText(ctx, "标签已移除", Toast.LENGTH_SHORT).show()
        }
    }

    fun suggestTagChip(
        ctx: Context, recordKey: String, tag: TagEntity, viewModel: MediaLibraryViewModel, dialog: BottomSheetDialog
    ): Chip = Chip(ctx).apply {
        text = tag.name
        setOnClickListener {
            viewModel.addTag(recordKey, tag.name)
            Toast.makeText(ctx, "标签已添加：${tag.name}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    fun formatDate(millis: Long): String = MediaBrowserLogic.formatDate(millis)

    fun formatSize(bytes: Long): String = MediaBrowserLogic.formatSize(bytes, zeroLabel = "-", decimals = 2)

    fun formatDuration(millis: Long): String = MediaBrowserLogic.formatDuration(millis)

    fun formatDimensions(media: MediaFileEntity): String {
        val width = media.width ?: return "-"
        val height = media.height ?: return "-"
        return "${width}x${height}"
    }

}
