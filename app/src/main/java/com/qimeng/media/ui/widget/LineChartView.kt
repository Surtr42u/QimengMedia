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
 * - 空数据时显示空态提示（文案 [emptyText]，默认"暂无趋势数据"）
 * - 使用主题色（qmColorPrimary）保持视觉一致
 *
 * v1.14 支持多系列对比：新增 [setMultiSeries]，多条不同颜色的线画在同一张图上
 * （顶部图例 + 点击气泡含系列名；多系列不画面积避免叠加杂乱），
 * 供分类型/来源浏览趋势对比使用；单系列 [setData] 行为保持不变。
 *
 * 数据通过 [setData] 或 [setMultiSeries] 方法传入，调用后触发入场动画。
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    /** 空态提示文案（默认"暂无趋势数据"，调用方可覆写） */
    private val emptyText: String = "暂无趋势数据",
    /** 数值气泡单位后缀（默认"次"，调用方可覆写） */
    private val valueSuffix: String = "次"
) : View(context, attrs, defStyleAttr) {

    /** 一条趋势数据：X 轴标签 + Y 轴数值 */
    data class Point(val label: String, val value: Int)

    /** 一条对比系列：名称 + 固定颜色 + 数据点（各系列点数与 X 轴标签需一致，保证对齐） */
    data class Series(val label: String, val color: Int, val points: List<Point>)

    /** 点击数据点回调 */
    var onPointClick: ((Point, Int) -> Unit)? = null

    /** 画布坐标条目（含系列信息，供点击命中与气泡显示） */
    private class CoordEntry(
        val point: Point,
        val x: Float,
        val y: Float,
        val seriesLabel: String = "",
        val seriesColor: Int = 0
    )

    private var points: List<Point> = emptyList()
    private var multiSeries: List<Series> = emptyList()
    private var multiSeriesMode = false
    private var animatedProgress: Float = 0f
    /** 最近一次 onDraw 计算的数据点坐标，供 onTouchEvent 命中使用 */
    private var pointCoords: List<CoordEntry> = emptyList()
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
    private val topPaddingMultiDp = 36
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
        multiSeriesMode = false
        multiSeries = emptyList()
        this.points = points
        selectedIndex = -1
        startEnterAnimation()
        invalidate()
    }

    /**
     * 设置多条对比系列（v1.14）。各系列点数与 X 轴标签应一致，保证在同一时间轴对齐。
     * @param series 系列列表（空列表显示"暂无趋势数据"）
     */
    fun setMultiSeries(series: List<Series>) {
        multiSeriesMode = true
        this.multiSeries = series
        this.points = emptyList()
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
        val topPad = if (multiSeriesMode) topPaddingMultiDp else topPaddingDp
        val minHeight = (topPad + minChartHeightDp + bottomLabelAreaDp).dp(context)
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

        if (multiSeriesMode) {
            drawMultiSeries(canvas, w, h)
        } else {
            drawSingleSeries(canvas, w, h)
        }
    }

    // ==================== 单系列绘制（主页面趋势图，行为不变） ====================

    private fun drawSingleSeries(canvas: Canvas, w: Float, h: Float) {
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
            canvas.drawText(emptyText, w / 2f, h / 2f, emptyPaint)
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
            CoordEntry(point, x, y)
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
            areaPath.moveTo(coords[0].x, chartBottom)
            areaPath.lineTo(coords[0].x, coords[0].y)

            for (i in 1..visibleIndex) {
                areaPath.lineTo(coords[i].x, coords[i].y)
            }
            // 动画末端的插值点
            if (visibleIndex < coords.size - 1 && fraction > 0) {
                val nextIdx = visibleIndex + 1
                val interpX = coords[visibleIndex].x + (coords[nextIdx].x - coords[visibleIndex].x) * fraction
                val interpY = coords[visibleIndex].y + (coords[nextIdx].y - coords[visibleIndex].y) * fraction
                areaPath.lineTo(interpX, interpY)
                areaPath.lineTo(interpX, chartBottom)
            } else {
                areaPath.lineTo(coords[visibleIndex.coerceAtMost(coords.size - 1)].x, chartBottom)
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
            linePath.moveTo(coords[0].x, coords[0].y)
            for (i in 1..visibleIndex) {
                linePath.lineTo(coords[i].x, coords[i].y)
            }
            if (visibleIndex < coords.size - 1 && fraction > 0) {
                val nextIdx = visibleIndex + 1
                val interpX = coords[visibleIndex].x + (coords[nextIdx].x - coords[visibleIndex].x) * fraction
                val interpY = coords[visibleIndex].y + (coords[nextIdx].y - coords[visibleIndex].y) * fraction
                linePath.lineTo(interpX, interpY)
            }
            linePaint.color = lineColor
            linePaint.strokeWidth = 3f.dp(context)
            canvas.drawPath(linePath, linePaint)
        }

        // 3. 绘制数据点（仅动画完成后显示）
        if (animatedProgress >= 0.95f) {
            val pointRadius = pointRadiusDp.dp(context).toFloat()
            coords.forEach { entry ->
                // 外圈
                pointPaint.color = lineColor
                canvas.drawCircle(entry.x, entry.y, pointRadius, pointPaint)
                // 内圈（背景色填充，形成空心点效果）
                canvas.drawCircle(entry.x, entry.y, pointRadius * 0.5f, pointFillPaint)
            }
        }

        // 4. 绘制 X 轴标签（稀疏显示，避免重叠）
        drawXAxisLabels(canvas, coords.map { it.point }, w, h)

        // 5. 绘制选中点高亮 + 数值气泡（动画完成后才响应）
        if (selectedIndex >= 0 && selectedIndex < coords.size && animatedProgress >= 0.95f) {
            drawSelectedPoint(canvas, coords[selectedIndex], chartLeft, chartRight, chartTop, lineColor)
        }
    }

    // ==================== 多系列对比绘制（v1.14） ====================

    private fun drawMultiSeries(canvas: Canvas, w: Float, h: Float) {
        val textColorSecondary = context.resolveThemeColor(R.attr.qmColorTextSecondary)
        val dividerColor = context.resolveThemeColor(R.attr.qmColorDivider)
        val bgColor = context.resolveThemeColor(R.attr.qmColorBg)

        gridPaint.color = dividerColor
        labelPaint.color = textColorSecondary
        emptyPaint.color = textColorSecondary
        pointFillPaint.color = bgColor

        if (multiSeries.isEmpty()) {
            canvas.drawText(emptyText, w / 2f, h / 2f, emptyPaint)
            return
        }

        val padH = horizontalPaddingDp.dp(context).toFloat()
        val topPad = topPaddingMultiDp.dp(context).toFloat()
        val bottomLabel = bottomLabelAreaDp.dp(context).toFloat()
        val chartLeft = padH
        val chartRight = w - padH
        val chartTop = topPad
        val chartBottom = h - bottomLabel
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        // 水平网格线（3 条）
        for (i in 0..3) {
            val y = chartTop + chartHeight * i / 3f
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }

        // 统一 Y 轴最大值（所有系列的最大值），保证对比在同一量级
        val maxValue = multiSeries.maxOf { series ->
            series.points.maxOfOrNull { it.value } ?: 0
        }.coerceAtLeast(1)
        val totalPoints = multiSeries.maxOfOrNull { it.points.size } ?: 0
        val stepX = if (totalPoints > 1) chartWidth / (totalPoints - 1) else chartWidth

        // 预计算各系列坐标
        val seriesCoords = multiSeries.map { series ->
            series.points.mapIndexed { index, point ->
                val x = chartLeft + index * stepX
                val y = chartBottom - (point.value.toFloat() / maxValue) * chartHeight
                CoordEntry(point, x, y, series.label, series.color)
            }
        }
        // 点击命中：收集全部系列的点
        pointCoords = seriesCoords.flatten()

        // 动画进度
        val visibleEnd = (totalPoints - 1) * animatedProgress
        val visibleIndex = visibleEnd.toInt()
        val fraction = visibleEnd - visibleIndex

        // 逐系列绘制折线 + 数据点（多系列不画面积，避免叠加杂乱）
        seriesCoords.forEachIndexed { si, coords ->
            val series = multiSeries[si]
            if (coords.size >= 2) {
                val linePath = Path()
                linePath.moveTo(coords[0].x, coords[0].y)
                for (i in 1..visibleIndex) {
                    linePath.lineTo(coords[i].x, coords[i].y)
                }
                if (visibleIndex < coords.size - 1 && fraction > 0) {
                    val nextIdx = visibleIndex + 1
                    val interpX = coords[visibleIndex].x + (coords[nextIdx].x - coords[visibleIndex].x) * fraction
                    val interpY = coords[visibleIndex].y + (coords[nextIdx].y - coords[visibleIndex].y) * fraction
                    linePath.lineTo(interpX, interpY)
                }
                linePaint.color = series.color
                linePaint.strokeWidth = 2.5f.dp(context)
                canvas.drawPath(linePath, linePaint)
            }
            // 数据点（仅动画完成后）
            if (animatedProgress >= 0.95f) {
                val pointRadius = pointRadiusDp.dp(context).toFloat()
                coords.forEach { entry ->
                    pointPaint.color = series.color
                    canvas.drawCircle(entry.x, entry.y, pointRadius, pointPaint)
                    canvas.drawCircle(entry.x, entry.y, pointRadius * 0.5f, pointFillPaint)
                }
            }
        }

        // X 轴标签（各系列标签一致，取第一个系列）
        val labels = multiSeries.first().points
        drawXAxisLabels(canvas, labels, w, h)

        // 顶部图例（色块 + 系列名）
        drawLegend(canvas, chartLeft, chartRight, topPad, textColorSecondary)

        // 选中点气泡（动画完成后才响应）
        if (selectedIndex >= 0 && selectedIndex < pointCoords.size && animatedProgress >= 0.95f) {
            val entry = pointCoords[selectedIndex]
            drawSelectedPoint(canvas, entry, chartLeft, chartRight, chartTop, entry.seriesColor)
        }
    }

    /** 绘制 X 轴标签（稀疏显示，避免重叠） */
    private fun drawXAxisLabels(canvas: Canvas, labels: List<Point>, w: Float, h: Float) {
        if (labels.isEmpty()) return
        val labelInterval = if (labels.size > 7) labels.size / 6 else 1
        val padH = horizontalPaddingDp.dp(context).toFloat()
        val chartLeft = padH
        val chartRight = w - padH
        val chartWidth = chartRight - chartLeft
        val stepX = if (labels.size > 1) chartWidth / (labels.size - 1) else chartWidth
        labels.forEachIndexed { index, point ->
            if (index % labelInterval == 0 || index == labels.size - 1) {
                val x = chartLeft + index * stepX
                canvas.drawText(point.label, x, h - 4f.dp(context), labelPaint)
            }
        }
    }

    /** 绘制顶部图例（多系列模式：色块 + 系列名，横向均分） */
    private fun drawLegend(canvas: Canvas, chartLeft: Float, chartRight: Float, topPad: Float, textColor: Int) {
        val n = multiSeries.size
        if (n == 0) return
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f.dp(context)
            textAlign = Paint.Align.LEFT
        }
        val swatchW = 8f.dp(context)
        val swatchH = 8f.dp(context)
        val gap = 4f.dp(context)
        val total = chartRight - chartLeft
        multiSeries.forEachIndexed { i, series ->
            val centerX = chartLeft + total * (i + 0.5f) / n
            val textW = legendPaint.measureText(series.label)
            val swatchX = centerX - (textW + gap + swatchW) / 2f
            // 色块
            legendPaint.color = series.color
            canvas.drawRect(swatchX, topPad - 14f.dp(context), swatchX + swatchW, topPad - 14f.dp(context) + swatchH, legendPaint)
            // 名称
            legendPaint.color = textColor
            canvas.drawText(series.label, swatchX + swatchW + gap, topPad - 7f.dp(context), legendPaint)
        }
    }

    /**
     * 绘制选中数据点的高亮圆圈和数值气泡。
     * 气泡位置优先在点上方，空间不足时切换到下方，避免超出图表区域。
     * @param lineColor 当前线颜色（单系列为主题色，多系列为对应系列色）
     */
    private fun drawSelectedPoint(
        canvas: Canvas,
        entry: CoordEntry,
        chartLeft: Float,
        chartRight: Float,
        chartTop: Float,
        lineColor: Int
    ) {
        val pointRadius = pointRadiusDp.dp(context).toFloat()

        // 高亮圆圈（比普通数据点大）
        pointPaint.color = lineColor
        canvas.drawCircle(entry.x, entry.y, pointRadius * 1.8f, pointPaint)
        canvas.drawCircle(entry.x, entry.y, pointRadius * 0.9f, pointFillPaint)

        // 数值气泡（多系列含系列名，如"视频  06/15  12次"）
        val labelText = if (entry.seriesLabel.isEmpty()) {
            "${entry.point.label}  ${entry.point.value}$valueSuffix"
        } else {
            "${entry.seriesLabel}  ${entry.point.label}  ${entry.point.value}$valueSuffix"
        }
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
        val bubbleX = (entry.x - bubbleW / 2).coerceIn(chartLeft, chartRight - bubbleW)
        val bubbleYAbove = entry.y - pointRadius * 2 - bubbleH - 4f.dp(context)
        val bubbleYBelow = entry.y + pointRadius * 2 + 4f.dp(context)
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
     *
     * v1.15 交互语义调整：图表区域约占 240dp，原 onTouchEvent 恒返回 true 会吞掉
     * 所有触摸，导致图表区域内无法滚动外层 NestedScrollView/ScrollView。
     * 改为 ACTION_DOWN 决策：命中数据点（48dp 阈值内）则消费事件并保持原有
     * 高亮/气泡逻辑（UP 时生效）；未命中数据点立即返回 false 放行父容器滚动
     * （DOWN 返回 false 后后续事件不再到达本 view，因此必须在 DOWN 决策）。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            // 动画进行中不响应点击，未命中数据点时放行父容器滚动
            val hit = pointCoords.isNotEmpty() && animatedProgress >= 0.95f &&
                hitIndexAt(event.x, event.y) >= 0
            return hit
        }
        if (event.action == MotionEvent.ACTION_UP && pointCoords.isNotEmpty() && animatedProgress >= 0.95f) {
            val newSelected = hitIndexAt(event.x, event.y)
            if (newSelected != selectedIndex) {
                selectedIndex = newSelected
                invalidate()
                if (selectedIndex >= 0) {
                    onPointClick?.invoke(pointCoords[selectedIndex].point, selectedIndex)
                }
            }
        }
        return true
    }

    /** 返回距离触摸点最近的数据点下标（48dp 命中阈值内），未命中返回 -1 */
    private fun hitIndexAt(touchX: Float, touchY: Float): Int {
        var minDist = Float.MAX_VALUE
        var minIdx = -1
        pointCoords.forEachIndexed { idx, entry ->
            val dx = touchX - entry.x
            val dy = touchY - entry.y
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist < minDist) {
                minDist = dist
                minIdx = idx
            }
        }
        // 48dp 命中阈值，方便手指点击
        val threshold = 48f.dp(context)
        return if (minDist < threshold) minIdx else -1
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
