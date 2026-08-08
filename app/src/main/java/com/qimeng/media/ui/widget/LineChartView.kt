package com.qimeng.media.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.qimeng.media.R
import com.qimeng.media.resolveThemeColor

/**
 * 折线/面积趋势图（自绘 Canvas）。
 *
 * v1.7 新增：数据统计页的时间趋势可视化。
 *
 * 设计要点：
 * - 渐变面积填充 + 折线 + 数据点，专业报表风格
 * - 入场动画（折线从左到右展开，面积渐显）
 * - 点击数据点显示数值（通过回调通知 Fragment 显示 Tooltip）
 * - 空数据时显示"暂无数据"提示
 * - 使用主题色（qmColorPrimary）保持视觉一致
 *
 * 数据通过 [setData] 方法传入，调用后触发入场动画。
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一条趋势数据：X 轴标签 + Y 轴数值 */
    data class Point(val label: String, val value: Int)

    /** 点击数据点回调 */
    var onPointClick: ((Point, Int) -> Unit)? = null

    private var points: List<Point> = emptyList()
    private var animatedProgress: Float = 0f
    /** 最近一次 onDraw 计算的数据点坐标，供 onTouchEvent 命中使用 */
    private var pointCoords: List<Triple<Point, Float, Float>> = emptyList()
    /** 当前选中的数据点索引（-1 表示无选中），点击数据点高亮并显示数值气泡 */
    private var selectedIndex: Int = -1

    // 画笔（颜色不在此初始化：主题色在 onDraw 中动态解析，见 v1.12 修复说明）
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f.dp(context)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val horizontalPaddingDp = 16
    private val topPaddingDp = 20
    private val bottomLabelAreaDp = 24
    private val pointRadiusDp = 4
    private val minChartHeightDp = 120

    private var animator: ValueAnimator? = null

    // v1.12：区域渐变 Shader 缓存（坐标与颜色变化时才重建），
    // 图表数据不变时 chartTop/chartBottom/lineColor 均固定，动画与重绘期间可直接复用
    private var cachedAreaGradient: LinearGradient? = null
    private var cachedAreaGradientTop = 0f
    private var cachedAreaGradientBottom = 0f
    private var cachedAreaGradientColor = 0

    /**
     * 设置趋势数据并触发入场动画。
     * @param points 数据点列表（按时间顺序排列）
     */
    fun setData(points: List<Point>) {
        this.points = points
        selectedIndex = -1
        startEnterAnimation()
        invalidate()
    }

    private fun startEnterAnimation() {
        animator?.cancel()
        animatedProgress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
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

        // v1.12 修复：主题色改为 onDraw 内动态解析（Context 为运行期主题上下文），
        // MainActivity 声明 configChanges=uiMode，系统明暗切换不重建 View，旧 lazy 缓存色不刷新
        val lineColor = context.resolveThemeColor(R.attr.qmColorPrimary)
        val textColorSecondary = context.resolveThemeColor(R.attr.qmColorTextSecondary)
        val dividerColor = context.resolveThemeColor(R.attr.qmColorDivider)
        val bgColor = context.resolveThemeColor(R.attr.qmColorBg)

        gridPaint.color = dividerColor
        labelPaint.color = textColorSecondary
        emptyPaint.color = textColorSecondary
        pointFillPaint.color = bgColor

        if (points.isEmpty()) {
            canvas.drawText("暂无趋势数据", w / 2f, h / 2f, emptyPaint)
            return
        }

        val padH = horizontalPaddingDp.dp(context).toFloat()
        val topPad = topPaddingDp.dp(context).toFloat()
        val bottomLabel = bottomLabelAreaDp.dp(context).toFloat()
        val chartLeft = padH
        val chartRight = w - padH
        val chartTop = topPad
        val chartBottom = h - bottomLabel
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        // 绘制水平网格线（3 条）
        for (i in 0..3) {
            val y = chartTop + chartHeight * i / 3f
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }

        val maxValue = points.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
        val stepX = if (points.size > 1) chartWidth / (points.size - 1) else chartWidth

        // 计算各点坐标
        val coords = points.mapIndexed { index, point ->
            val x = chartLeft + index * stepX
            val y = chartBottom - (point.value.toFloat() / maxValue) * chartHeight
            Triple(point, x, y)
        }
        // 保存供 onTouchEvent 命中检测使用
        pointCoords = coords

        // 动画进度：只绘制到 progress 比例的位置
        val visibleEnd = (coords.size - 1) * animatedProgress
        val visibleIndex = visibleEnd.toInt()
        val fraction = visibleEnd - visibleIndex

        // 1. 绘制渐变面积
        if (coords.size >= 2 && visibleIndex >= 0) {
            val areaPath = Path()
            areaPath.moveTo(coords[0].second, chartBottom)
            areaPath.lineTo(coords[0].second, coords[0].third)

            for (i in 1..visibleIndex) {
                areaPath.lineTo(coords[i].second, coords[i].third)
            }
            // 动画末端的插值点
            if (visibleIndex < coords.size - 1 && fraction > 0) {
                val nextIdx = visibleIndex + 1
                val interpX = coords[visibleIndex].second + (coords[nextIdx].second - coords[visibleIndex].second) * fraction
                val interpY = coords[visibleIndex].third + (coords[nextIdx].third - coords[visibleIndex].third) * fraction
                areaPath.lineTo(interpX, interpY)
                areaPath.lineTo(interpX, chartBottom)
            } else {
                areaPath.lineTo(coords[visibleIndex.coerceAtMost(coords.size - 1)].second, chartBottom)
            }
            areaPath.close()

            // 渐变填充（主题色从上到下渐淡）
            // v1.12：坐标与颜色不变时复用缓存 Shader，避免动画 800ms 内每帧 new LinearGradient
            if (cachedAreaGradient == null ||
                cachedAreaGradientTop != chartTop ||
                cachedAreaGradientBottom != chartBottom ||
                cachedAreaGradientColor != lineColor
            ) {
                cachedAreaGradientTop = chartTop
                cachedAreaGradientBottom = chartBottom
                cachedAreaGradientColor = lineColor
                cachedAreaGradient = LinearGradient(
                    0f, chartTop, 0f, chartBottom,
                    lineColor, lineColor,
                    Shader.TileMode.CLAMP
                )
            }
            areaPaint.shader = cachedAreaGradient
            areaPaint.alpha = 40
            canvas.drawPath(areaPath, areaPaint)
            areaPaint.alpha = 255
        }

        // 2. 绘制折线
        if (coords.size >= 2) {
            val linePath = Path()
            linePath.moveTo(coords[0].second, coords[0].third)
            for (i in 1..visibleIndex) {
                linePath.lineTo(coords[i].second, coords[i].third)
            }
            if (visibleIndex < coords.size - 1 && fraction > 0) {
                val nextIdx = visibleIndex + 1
                val interpX = coords[visibleIndex].second + (coords[nextIdx].second - coords[visibleIndex].second) * fraction
                val interpY = coords[visibleIndex].third + (coords[nextIdx].third - coords[visibleIndex].third) * fraction
                linePath.lineTo(interpX, interpY)
            }
            linePaint.color = lineColor
            canvas.drawPath(linePath, linePaint)
        }

        // 3. 绘制数据点（仅动画完成后显示）
        if (animatedProgress >= 0.95f) {
            val pointRadius = pointRadiusDp.dp(context).toFloat()
            coords.forEach { (point, x, y) ->
                // 外圈
                pointPaint.color = lineColor
                canvas.drawCircle(x, y, pointRadius, pointPaint)
                // 内圈（背景色填充，形成空心点效果）
                canvas.drawCircle(x, y, pointRadius * 0.5f, pointFillPaint)
            }
        }

        // 4. 绘制 X 轴标签（稀疏显示，避免重叠）
        val labelInterval = if (points.size > 7) points.size / 6 else 1
        coords.forEachIndexed { index, (point, x, _) ->
            if (index % labelInterval == 0 || index == coords.size - 1) {
                canvas.drawText(point.label, x, h - 4f.dp(context), labelPaint)
            }
        }

        // 5. 绘制选中点高亮 + 数值气泡（动画完成后才响应）
        if (selectedIndex >= 0 && selectedIndex < coords.size && animatedProgress >= 0.95f) {
            drawSelectedPoint(canvas, coords[selectedIndex], chartLeft, chartRight, chartTop, lineColor)
        }
    }

    /**
     * 绘制选中数据点的高亮圆圈和数值气泡。
     * 气泡位置优先在点上方，空间不足时切换到下方，避免超出图表区域。
     * @param lineColor 当前主题主色（onDraw 已解析，传入避免重复解析）
     */
    private fun drawSelectedPoint(
        canvas: Canvas,
        coord: Triple<Point, Float, Float>,
        chartLeft: Float,
        chartRight: Float,
        chartTop: Float,
        lineColor: Int
    ) {
        val (point, x, y) = coord
        val pointRadius = pointRadiusDp.dp(context).toFloat()

        // 高亮圆圈（比普通数据点大）
        pointPaint.color = lineColor
        canvas.drawCircle(x, y, pointRadius * 1.8f, pointPaint)
        canvas.drawCircle(x, y, pointRadius * 0.9f, pointFillPaint)

        // 数值气泡
        val labelText = "${point.label}  ${point.value}次"
        val bubblePadH = 8f.dp(context)
        val bubblePadV = 5f.dp(context)
        val bubbleTextSize = 11f.dp(context)
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            alpha = 235
            style = Paint.Style.FILL
        }
        val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = bubbleTextSize
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val textWidth = bubbleTextPaint.measureText(labelText)
        val bubbleW = textWidth + bubblePadH * 2
        val bubbleH = bubbleTextSize + bubblePadV * 2

        // 气泡位置：优先在点上方，空间不足则下方
        val bubbleX = (x - bubbleW / 2).coerceIn(chartLeft, chartRight - bubbleW)
        val bubbleYAbove = y - pointRadius * 2 - bubbleH - 4f.dp(context)
        val bubbleYBelow = y + pointRadius * 2 + 4f.dp(context)
        val bubbleY = if (bubbleYAbove < chartTop) bubbleYBelow else bubbleYAbove

        val rect = RectF(bubbleX, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH)
        val radius = 4f.dp(context)
        canvas.drawRoundRect(rect, radius, radius, bubblePaint)

        val textX = bubbleX + bubbleW / 2
        val textY = bubbleY + bubbleH / 2 - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2
        canvas.drawText(labelText, textX, textY, bubbleTextPaint)
    }

    /**
     * 处理点击：找到距离触摸点最近的数据点（48dp 阈值内），高亮并显示数值气泡。
     * 点击空白处取消选中。动画进行中不响应点击。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && pointCoords.isNotEmpty() && animatedProgress >= 0.95f) {
            val touchX = event.x
            val touchY = event.y
            var minDist = Float.MAX_VALUE
            var minIdx = -1
            pointCoords.forEachIndexed { idx, (_, px, py) ->
                val dx = touchX - px
                val dy = touchY - py
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (dist < minDist) {
                    minDist = dist
                    minIdx = idx
                }
            }
            // 48dp 命中阈值，方便手指点击
            val threshold = 48f.dp(context)
            val newSelected = if (minDist < threshold) minIdx else -1
            if (newSelected != selectedIndex) {
                selectedIndex = newSelected
                invalidate()
                if (selectedIndex >= 0) {
                    onPointClick?.invoke(pointCoords[selectedIndex].first, selectedIndex)
                }
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
