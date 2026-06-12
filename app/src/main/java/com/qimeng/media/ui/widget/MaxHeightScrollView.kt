package com.qimeng.media.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 最大高度为屏幕高度的一半
        val maxPx = (resources.displayMetrics.heightPixels / 2)
        val hSpec = MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, hSpec)
    }
}
