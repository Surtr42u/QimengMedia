package com.qimeng.media.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.qimeng.media.R
import com.qimeng.media.resolveThemeColor
import kotlin.math.min

/**
 * 环形图（自绘 Canvas）。
 *
 * v1.7 新增：数据统计页的类型分布可视化。
 *
 * 设计要点：
 * - 环形（甜甜圈）样式 + 中心总览数字，专业报表风格
 * - 入场动画（扇区从 0 度展开）
 * - 纯展示，无点击交互（触摸事件不消费，便于父卡片接收点击）
 * - 空数据时显示"暂无数据"提示
 * - 多色调色板自动分配，区分各扇区
 * - 图例纵向排列，避免窄空间文字挤压
 *
 * 数据通过 [setData] 方法传入，调用后触发入场动画。
 */
class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一条扇区数据：标签 + 数值 + 可选 ID（用于点击跳转） */
    data class Slice(val label: String, val value: Int, val id: String? = null)

    private var slices: List<Slice> = emptyList()
    private var animatedProgress: Float = 0f
    // v1.12 删除：selectedIndex 恒为 -1（无写入路径，onTouchEvent 纯展示不消费触摸），其偏移分支与选中扇区显示分支为死代码，已移除

    // 画笔（颜色不在此初始化：主题色在 onDraw 中动态解析，见 v1.12 修复说明）
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val centerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f.dp(context)
        textAlign = Paint.Align.CENTER
    }
    private val centerValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f.dp(context)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val legendLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f.dp(context)
    }
    private val legendValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.dp(context)
        textAlign = Paint.Align.RIGHT
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f.dp(context)
        textAlign = Paint.Align.CENTER
    }

    // v1.12：多色调色板缓存（主题主色变化时重建），替代原 lazy 一次性缓存，
    // 解决 configChanges=uiMode 下系统明暗切换不重建 View 时配色不刷新的问题
    private var colorPalette: List<Int> = emptyList()
    private var cachedPalettePrimary = 0
    private var cachedPalettePrimarySoft = 0

    // 布局参数
    private val minSizeDp = 180
    private val ringThicknessDp = 24
    private val legendItemHeightDp = 18  // 每行图例高度
    private val legendTopPaddingDp = 8   // 图例区域顶部间距

    private var animator: ValueAnimator? = null

    /**
     * 设置扇区数据并触发入场动画。
     * @param slices 扇区列表（value=0 的项不在环形图上绘制扇区，但在图例中显示）
     */
    fun setData(slices: List<Slice>) {
        this.slices = slices
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
        val legendHeight = computeLegendHeight()
        val minHeight = (minSizeDp + legendHeight).dp(context)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            minHeight
        }
        setMeasuredDimension(width, height.coerceAtLeast(minHeight))
    }

    /** 根据当前 slice 数量计算图例区域高度（dp） */
    private fun computeLegendHeight(): Int {
        if (slices.isEmpty()) return 0
        return legendTopPaddingDp + slices.size * legendItemHeightDp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // v1.12 修复：主题色改为 onDraw 内动态解析（Context 为运行期主题上下文），
        // MainActivity 声明 configChanges=uiMode，系统明暗切换不重建 View，旧 lazy 缓存色不刷新
        val textColorPrimary = context.resolveThemeColor(R.attr.qmColorTextPrimary)
        val textColorSecondary = context.resolveThemeColor(R.attr.qmColorTextSecondary)
        val bgColor = context.resolveThemeColor(R.attr.qmColorBg)
        val primary = context.resolveThemeColor(R.attr.qmColorPrimary)
        val primarySoft = context.resolveThemeColor(R.attr.qmColorPrimarySoft)

        centerLabelPaint.color = textColorSecondary
        centerValuePaint.color = textColorPrimary
        legendLabelPaint.color = textColorPrimary
        legendValuePaint.color = textColorSecondary
        emptyPaint.color = textColorSecondary
        // 主题主色变化时重建调色板（明暗切换后主色不同，颜色才会随主题刷新）
        if (colorPalette.isEmpty() ||
            cachedPalettePrimary != primary ||
            cachedPalettePrimarySoft != primarySoft
        ) {
            cachedPalettePrimary = primary
            cachedPalettePrimarySoft = primarySoft
            colorPalette = listOf(
                primary,
                primarySoft,
                adjustColor(primary, 0.7f),
                adjustColor(primarySoft, 0.8f),
                adjustColor(primary, 1.3f),
                adjustColor(primarySoft, 1.2f)
            )
        }

        if (slices.isEmpty()) {
            canvas.drawText("暂无分布数据", w / 2f, h / 2f, emptyPaint)
            return
        }

        val total = slices.sumOf { it.value }.coerceAtLeast(1)
        val hasNonZero = slices.any { it.value > 0 }
        val legendHeight = computeLegendHeight().dp(context).toFloat()
        val chartAreaHeight = h - legendHeight
        val centerX = w / 2f
        val centerY = chartAreaHeight / 2f
        val radius = min(w / 2f, chartAreaHeight / 2f) - 8f.dp(context)
        val ringThickness = ringThicknessDp.dp(context).toFloat()

        val baseRect = RectF(
            centerX - radius, centerY - radius,
            centerX + radius, centerY + radius
        )

        // 绘制扇区（全部为0时跳过，只画图例）
        if (hasNonZero) {
            var startAngle = -90f // 从顶部开始
            slices.forEachIndexed { index, slice ->
                val sweepAngle = (slice.value.toFloat() / total) * 360f * animatedProgress

                slicePaint.color = colorPalette[index % colorPalette.size]
                canvas.drawArc(baseRect, startAngle, sweepAngle, true, slicePaint)

                startAngle += sweepAngle
            }

            // 绘制中心空心圆（形成环形效果）
            slicePaint.color = bgColor
            val innerRadius = radius - ringThickness
            canvas.drawCircle(centerX, centerY, innerRadius, slicePaint)

            // 中心总览数字（动画完成后显示）
            if (animatedProgress >= 0.95f) {
                canvas.drawText("总计", centerX, centerY - 8f.dp(context), centerLabelPaint)
                canvas.drawText(total.toString(), centerX, centerY + 14f.dp(context), centerValuePaint)
            }
        } else {
            // 全部为0时在图表区显示提示
            canvas.drawText("暂无数据", centerX, chartAreaHeight / 2f, emptyPaint)
        }

        // 绘制图例（底部）
        drawLegend(canvas, w, h, legendHeight, total)
    }

    private fun drawLegend(canvas: Canvas, w: Float, h: Float, legendHeight: Float, total: Int) {
        val legendTop = h - legendHeight
        val itemHeight = legendItemHeightDp.dp(context).toFloat()
        val topPadding = legendTopPaddingDp.dp(context).toFloat()
        val colorSize = 8f.dp(context)
        val horizontalPadding = 12f.dp(context)

        slices.forEachIndexed { index, slice ->
            val itemTop = legendTop + topPadding + index * itemHeight
            val textBaseline = itemTop + colorSize

            // 色块
            slicePaint.color = colorPalette[index % colorPalette.size]
            canvas.drawRect(horizontalPadding, itemTop, horizontalPadding + colorSize, itemTop + colorSize, slicePaint)

            // 标签（色块右侧）
            legendLabelPaint.textAlign = Paint.Align.LEFT
            val labelText = if (slice.label.length > 8) slice.label.substring(0, 8) + "…" else slice.label
            canvas.drawText(labelText, horizontalPadding + colorSize + 4f.dp(context), textBaseline, legendLabelPaint)

            // 数值（右对齐）
            val percent = (slice.value.toFloat() / total * 100).toInt()
            val valueText = "${slice.value}  ($percent%)"
            canvas.drawText(valueText, w - horizontalPadding, textBaseline, legendValuePaint)
        }
    }

    /**
     * 纯展示视图，不消费触摸事件，便于父级卡片接收点击，中心始终显示总计。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
    }

    /** 调整颜色亮度（factor < 1 变暗，> 1 变亮） */
    private fun adjustColor(color: Int, factor: Float): Int {
        val r = ((color shr 16) and 0xFF)
        val g = ((color shr 8) and 0xFF)
        val b = (color and 0xFF)
        val nr = (r * factor).toInt().coerceIn(0, 255)
        val ng = (g * factor).toInt().coerceIn(0, 255)
        val nb = (b * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
