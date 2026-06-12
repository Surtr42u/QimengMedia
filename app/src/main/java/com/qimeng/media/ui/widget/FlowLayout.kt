package com.qimeng.media.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    var horizontalSpacing = dpToPx(6)
    var verticalSpacing = dpToPx(4)

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthLimit = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var currentX = 0
        var currentY = 0
        var rowHeight = 0
        var maxRight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (currentX + childWidth > widthLimit && currentX > 0) {
                currentX = 0
                currentY += rowHeight + verticalSpacing
                rowHeight = 0
            }

            rowHeight = maxOf(rowHeight, childHeight)
            maxRight = maxOf(maxRight, currentX + childWidth)
            currentX += childWidth + horizontalSpacing
        }

        val totalHeight = currentY + rowHeight + paddingTop + paddingBottom
        val totalWidth = maxRight + paddingLeft + paddingRight
        setMeasuredDimension(
            resolveSize(totalWidth, widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val widthLimit = r - l - paddingLeft - paddingRight
        var currentX = paddingLeft
        var currentY = paddingTop
        var rowHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (currentX + childWidth > paddingLeft + widthLimit && currentX > paddingLeft) {
                currentX = paddingLeft
                currentY += rowHeight + verticalSpacing
                rowHeight = 0
            }

            child.layout(currentX, currentY, currentX + childWidth, currentY + childHeight)
            rowHeight = maxOf(rowHeight, childHeight)
            currentX += childWidth + horizontalSpacing
        }
    }
}
