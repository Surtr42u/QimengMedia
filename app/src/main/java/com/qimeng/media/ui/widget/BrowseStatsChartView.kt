package com.qimeng.media.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.qimeng.media.resolveThemeColor
import com.qimeng.media.R

/**
 * 浏览统计柱状图（自绘 Canvas）。
 *
 * v1.7 新增：在"我的"页面展示 Top N 文件的浏览热度（viewCount + playCount）。
 *
 * 设计要点：
 * - 纯自绘，无第三方依赖，轻量高效
 * - 水平柱状图，每行显示文件名（截断）+ 热度柱条 + 数值
 * - 使用主题色（qmColorPrimary）保持视觉一致
 * - 空数据时显示"暂无浏览数据"提示
 * - 固定行高，通过 data 条数动态计算高度
 *
 * 数据通过 [setData] 方法传入，调用后 invalidate 触发重绘。
 */
class BrowseStatsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一条柱状图数据：文件名 + 热度值 + 可选 ID（用于点击跳转） */
    data class BarEntry(val label: String, val value: Int, val id: String? = null)

    private var entries: List<BarEntry> = emptyList()

    // 主题色（在 onDraw 中读取，确保主题切换后刷新）
    private val barColor: Int by lazy { context.resolveThemeColor(R.attr.qmColorPrimary) }
    private val textColorPrimary: Int by lazy { context.resolveThemeColor(R.attr.qmColorTextPrimary) }
    private val textColorSecondary: Int by lazy { context.resolveThemeColor(R.attr.qmColorTextSecondary) }
    private val surfaceColor: Int by lazy { context.resolveThemeColor(R.attr.qmColorSurface) }

    // 画笔
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f.dp(context)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f.dp(context)
        textAlign = Paint.Align.RIGHT
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f.dp(context)
        textAlign = Paint.Align.CENTER
    }

    // 布局参数（dp）
    private val rowHeightDp = 32
    private val labelMaxWidthDp = 120
    private val barHeightDp = 14
    private val horizontalPaddingDp = 12
    private val topPaddingDp = 8
    private val bottomPaddingDp = 8
    private val barStartMarginDp = 8 // 标签与柱条间距
    private val valueEndMarginDp = 4 // 数值与右边缘间距

    /**
     * 设置柱状图数据并触发重绘。
     * @param entries Top N 条目列表（已按 value 降序排列）
     */
    fun setData(entries: List<BarEntry>) {
        this.entries = entries.take(MAX_ENTRIES)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // 高度 = 上下内边距 + 行数 * 行高（空数据时固定一个行高显示提示）
        val rows = if (entries.isEmpty()) 1 else entries.size
        val height = (topPaddingDp + rows * rowHeightDp + bottomPaddingDp).dp(context)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (entries.isEmpty()) {
            emptyPaint.color = textColorSecondary
            canvas.drawText("暂无浏览数据", w / 2f, h / 2f, emptyPaint)
            return
        }

        val padH = horizontalPaddingDp.dp(context).toFloat()
        val labelMaxWidth = labelMaxWidthDp.dp(context).toFloat()
        val barHeight = barHeightDp.dp(context).toFloat()
        val rowHeight = rowHeightDp.dp(context).toFloat()
        val topPad = topPaddingDp.dp(context).toFloat()
        val barStartMargin = barStartMarginDp.dp(context).toFloat()
        val valueEndMargin = valueEndMarginDp.dp(context).toFloat()

        val maxValue = entries.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
        // 柱条绘制区域：标签最大宽度之后到数值之前
        val barAreaStart = padH + labelMaxWidth + barStartMargin
        val barAreaEnd = w - padH - valueEndMargin - 30f.dp(context) // 留 30dp 给数值文本
        val barAreaWidth = (barAreaEnd - barAreaStart).coerceAtLeast(1f)

        textPaint.color = textColorPrimary
        valuePaint.color = textColorSecondary
        barPaint.color = barColor

        entries.forEachIndexed { index, entry ->
            val rowTop = topPad + index * rowHeight
            val rowCenterY = rowTop + rowHeight / 2f

            // 1. 绘制文件名标签（超长截断）
            val labelText = ellipsize(entry.label, labelMaxWidth, textPaint)
            canvas.drawText(labelText, padH, rowCenterY + textPaint.textHeight() / 2f, textPaint)

            // 2. 绘制柱条背景（浅色轨道）
            val barTop = rowCenterY - barHeight / 2f
            val barRect = RectF(barAreaStart, barTop, barAreaEnd, barTop + barHeight)
            // 背景轨道使用 surface 色（低对比度）
            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = surfaceColor
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(barRect, barHeight / 2f, barHeight / 2f, trackPaint)

            // 3. 绘制柱条前景（按比例填充）
            val ratio = entry.value.toFloat() / maxValue
            val barFillEnd = barAreaStart + barAreaWidth * ratio
            val fillRect = RectF(barAreaStart, barTop, barFillEnd, barTop + barHeight)
            canvas.drawRoundRect(fillRect, barHeight / 2f, barHeight / 2f, barPaint)

            // 4. 绘制数值文本（柱条右侧）
            val valueText = entry.value.toString()
            canvas.drawText(valueText, w - padH - valueEndMargin, rowCenterY + valuePaint.textHeight() / 2f, valuePaint)
        }
    }

    /** 截断文本以适应指定宽度 */
    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) {
            end--
        }
        return if (end > 0) text.substring(0, end) + ellipsis else ellipsis
    }

    /** 获取画笔文字高度（用于垂直居中） */
    private fun Paint.textHeight(): Float = descent() - ascent()

    companion object {
        /** 最多展示的条目数 */
        const val MAX_ENTRIES = 5
    }
}
