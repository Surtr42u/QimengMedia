package com.qimeng.media.ui.widget

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.GridLayoutManager

/**
 * 双指缩放列数共享组件。
 * 统一管理 RecyclerView 双指缩放切换列数的逻辑，与全部页一致。
 */
object PinchZoomHelper {

    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 5

    /**
     * 创建 ScaleGestureDetector 并绑定到 RecyclerView 的触摸事件。
     *
     * @param context Context
     * @param columns 当前列数（会被直接修改）
     * @param baseColumns 基础列数（缩放结束后更新，可为 null 表示不需要更新）
     * @param layoutManager GridLayoutManager（spanCount 会被直接修改）
     * @param recyclerView 目标 RecyclerView
     * @param minCols 最小列数（默认 MIN_COLUMNS）
     * @param maxCols 最大列数（默认 MAX_COLUMNS）
     * @return 创建的 ScaleGestureDetector
     */
    fun setup(
        context: Context,
        columns: ColumnsRef,
        layoutManager: GridLayoutManager,
        recyclerView: android.view.View,
        minCols: Int = MIN_COLUMNS,
        maxCols: Int = MAX_COLUMNS
    ): ScaleGestureDetector {
        var pinchStartSpan = 0f
        var pinchBaseCols = columns.value
        var pinchCurrentCols = columns.value

        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchStartSpan = detector.currentSpan
                pinchBaseCols = columns.value
                pinchCurrentCols = columns.value
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (pinchStartSpan <= 0f) return false
                val ratio = detector.currentSpan / pinchStartSpan
                val target = (pinchBaseCols / ratio).toInt().coerceIn(minCols, maxCols)
                if (target != pinchCurrentCols) {
                    pinchCurrentCols = target
                    columns.value = target
                    layoutManager.spanCount = target
                    layoutManager.spanSizeLookup?.invalidateSpanIndexCache()
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                columns.value = pinchCurrentCols
                columns.baseValue = pinchCurrentCols
            }
        })

        recyclerView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) recyclerView.performClick()
            false
        }

        return scaleDetector
    }

    /**
     * 创建带 SpanSizeLookup 的 GridLayoutManager。
     * 统一管理分组网格布局的 spanSizeLookup 逻辑：header 占满整行，item 占 1 格。
     *
     * @param context Context
     * @param spanCount 列数
     * @param adapter GroupedMediaAdapter（用于 isHeader 判断）
     * @return 配置好的 GridLayoutManager
     */
    fun createGridLayoutManager(
        context: android.content.Context,
        spanCount: Int,
        adapter: com.qimeng.media.ui.adapter.GroupedMediaAdapter
    ): GridLayoutManager {
        val lm = GridLayoutManager(context, spanCount)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeader(position)) lm.spanCount else 1
        }
        return lm
    }
}

/**
 * 列数引用包装，允许共享组件修改 Fragment 的列数状态。
 * Fragment 需要创建 ColumnsRef 实例并传递给 PinchZoomHelper。
 */
class ColumnsRef(
    initialValue: Int,
    initialBaseValue: Int = initialValue
) {
    var value: Int = initialValue
    var baseValue: Int = initialBaseValue
}
