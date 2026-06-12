package com.qimeng.media.ui.widget

import android.content.Context

/** dp 转 px（Int → Int） */
fun Int.dp(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()

/** dp 转 px（Float → Float） */
fun Float.dp(context: Context): Float =
    this * context.resources.displayMetrics.density

/** dp 转 px（Int → Float），用于需要浮点精度的场景 */
fun Int.dpFloat(context: Context): Float =
    this * context.resources.displayMetrics.density
