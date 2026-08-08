package com.qimeng.media.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.qimeng.media.R
import com.qimeng.media.resolveThemeColor

/**
 * 竖向柱状图（自绘 Canvas）。
 *
 * v1.7 新增：数据统计页的 Top 排行可视化。
 *
 * 设计要点：
 * - 竖向柱条 + 渐变填充 + 数值标签，专业报表风格
 * - 入场动画（柱条从底部生长）
 * - 点击柱条高亮 + 回调显示详情
 * - 空数据时显示"暂无数据"提示
 * - 使用主题色（qmColorPrimary）渐变
 *
 * 数据通过 [setData] 方法传入，调用后触发入场动画。
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一条柱状图数据：标签 + 数值 + 可选 ID（用于点击跳转） */
    data class BarEntry(val label: String, val value: Int, val id: String? = null)

    /** 点击柱条回调（返回索引和条目） */
    var onBarClick: ((BarEntry, Int) -> Unit)? = null

    private var entries: List<BarEntry> = emptyList()
    private var animatedProgress: Float = 0f
    private var selectedIndex: Int = -1

    // 画笔（颜色不在此初始化：主题色在 onDraw 中动态解析，见 v1.12 修复说明）
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.dp(context)
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.dp(context)
        textAlign = Paint.Align.CENTER
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        alpha = 60
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f.dp(context)
        textAlign = Paint.Align.CENTER
    }

    // 布局参数
    private val horizontalPaddingDp = 12
    private val topPaddingDp = 16
    private val bottomLabelAreaDp = 28
    private val barWidthDp = 28
    private val barGapDp = 12
    private val minChartHeightDp = 140

    private var animator: ValueAnimator? = null
    private val barRects: MutableList<RectF> = mutableListOf()
    // v1.12：LinearGradient 缓存（坐标或颜色变化时才重建），避免动画期间 onDraw 每帧 new Shader
    private var cachedGradient: LinearGradient? = null
    private var cachedGradientTop = 0f
    private var cachedGradientBottom = 0f
    private var cachedGradientStart = 0
    private var cachedGradientEnd = 0

    /**
     * 设置柱状图数据并触发入场动画。
     * @param entries 条目列表（已按 value 降序排列）
     */
    fun setData(entries: List<BarEntry>) {
        this.entries = entries
        selectedIndex = -1
        startEnterAnimation()
        invalidate()
    }

    private fun startEnterAnimation() {
        animator?.cancel()
        animatedProgress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                animatedProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val minHeight = (topPaddingDp + minChartHeightDp + bottomLabelAreaDp).dp(context)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            minHeight
        }
        setMeasuredDimension(width, height.coerceAtLeast(minHeight))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        barRects.clear()

        // v1.12 修复：主题色改为 onDraw 内动态解析（Context 为运行期主题上下文），
        // MainActivity 声明 configChanges=uiMode，系统明暗切换不重建 View，旧 lazy 缓存色不刷新；
        // onDraw 每帧解析有少量开销，可接受（统计页图表为低频重绘，非滚动热路径）。
        val barColor = context.resolveThemeColor(R.attr.qmColorPrimary)
        val barColorSoft = context.resolveThemeColor(R.attr.qmColorPrimarySoft)
        val textColorPrimary = context.resolveThemeColor(R.attr.qmColorTextPrimary)
        val textColorSecondary = context.resolveThemeColor(R.attr.qmColorTextSecondary)
        val dividerColor = context.resolveThemeColor(R.attr.qmColorDivider)

        gridPaint.color = dividerColor
        valuePaint.color = textColorPrimary
        emptyPaint.color = textColorSecondary

        if (entries.isEmpty()) {
            canvas.drawText("暂无排行数据", w / 2f, h / 2f, emptyPaint)
            return
        }

        val padH = horizontalPaddingDp.dp(context).toFloat()
        val topPad = topPaddingDp.dp(context).toFloat()
        val bottomLabel = bottomLabelAreaDp.dp(context).toFloat()
        val chartTop = topPad
        val chartBottom = h - bottomLabel
        val chartHeight = chartBottom - chartTop

        // 绘制水平网格线（3 条）
        for (i in 0..3) {
            val y = chartTop + chartHeight * i / 3f
            canvas.drawLine(padH, y, w - padH, y, gridPaint)
        }

        val maxValue = entries.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
        val barWidth = barWidthDp.dp(context).toFloat()
        val barGap = barGapDp.dp(context).toFloat()
        val totalBarsWidth = entries.size * barWidth + (entries.size - 1) * barGap
        val startX = if (totalBarsWidth < w - 2 * padH) {
            (w - totalBarsWidth) / 2f
        } else {
            padH
        }

        entries.forEachIndexed { index, entry ->
            val barLeft = startX + index * (barWidth + barGap)
            val barRight = barLeft + barWidth
            val fullBarHeight = (entry.value.toFloat() / maxValue) * chartHeight
            val animatedHeight = fullBarHeight * animatedProgress
            val barTop = chartBottom - animatedHeight
            val barRect = RectF(barLeft, barTop, barRight, chartBottom)
            barRects.add(barRect)

            // 渐变填充（选中时用纯色高亮，否则用渐变）
            // v1.12：LinearGradient 只在坐标或颜色变化时重建，动画期间 barTop 每帧变化，
            // 命中缓存条件即复用同一 Shader，避免动画 700ms 内每帧 new LinearGradient 的分配开销
            val paint = if (index == selectedIndex) {
                selectedBarPaint.shader = getCachedGradient(barTop, chartBottom, barColor, barColor)
                selectedBarPaint
            } else {
                barPaint.shader = getCachedGradient(barTop, chartBottom, barColor, barColorSoft)
                barPaint
            }
            val cornerRadius = 4f.dp(context)
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, paint)

            // 数值标签（动画完成后显示）
            if (animatedProgress >= 0.9f) {
                canvas.drawText(entry.value.toString(), (barLeft + barRight) / 2f, barTop - 4f.dp(context), valuePaint)
            }

            // X 轴标签（截断显示）
            val labelText = ellipsize(entry.label, barWidth + barGap, labelPaint)
            labelPaint.color = if (index == selectedIndex) textColorPrimary else textColorSecondary
            canvas.drawText(labelText, (barLeft + barRight) / 2f, h - 4f.dp(context), labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (entries.isEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                // 查找点击的柱条
                barRects.forEachIndexed { index, rect ->
                    // 扩大点击区域到整个列
                    val expandedRect = RectF(rect.left, 0f, rect.right, height.toFloat())
                    if (expandedRect.contains(x, y)) {
                        selectedIndex = if (selectedIndex == index) -1 else index
                        if (selectedIndex >= 0) {
                            onBarClick?.invoke(entries[selectedIndex], selectedIndex)
                        }
                        invalidate()
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * v1.12：获取柱条渐变 Shader。坐标与起止颜色均未变化时复用缓存实例，
     * 避免动画期间 onDraw 每帧 new LinearGradient（柱条高度每帧在变，但通常相邻帧差值很小，
     * 这里采用精确缓存：任一参数变化才重建）。
     */
    private fun getCachedGradient(top: Float, bottom: Float, startColor: Int, endColor: Int): LinearGradient {
        val cached = cachedGradient
        if (cached != null &&
            cachedGradientTop == top &&
            cachedGradientBottom == bottom &&
            cachedGradientStart == startColor &&
            cachedGradientEnd == endColor
        ) {
            return cached
        }
        cachedGradientTop = top
        cachedGradientBottom = bottom
        cachedGradientStart = startColor
        cachedGradientEnd = endColor
        return LinearGradient(0f, top, 0f, bottom, startColor, endColor, Shader.TileMode.CLAMP).also {
            cachedGradient = it
        }
    }

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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
