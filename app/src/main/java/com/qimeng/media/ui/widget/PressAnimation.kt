package com.qimeng.media.ui.widget

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * 按钮按下反馈动画工具：缩放 + 快速回弹。
 *
 * 设计参考 Material 3 Expressive 的微交互：
 * - 按下时缩放到 0.92（轻微缩小，反馈按压感）
 * - 抬起时回弹到 1.0，用 AccelerateDecelerate 缓动
 * - 时长 100ms（Material short2 token），足够感知但不拖沓
 *
 * 使用方式：
 * ViewExtensions.addPressAnimation(button)
 */
fun View.addPressAnimation(
    pressedScale: Float = 0.92f,
    duration: Long = 100L
) {
    this.setOnTouchListener { v, event ->
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                animateScale(v, pressedScale, duration)
                false // 不消费事件，让 onClick 仍能触发
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                animateScale(v, 1f, duration)
                false
            }
            else -> false
        }
    }
}

private fun animateScale(view: View, scale: Float, duration: Long) {
    val scaleX = ObjectAnimator.ofFloat(view, "scaleX", scale)
    val scaleY = ObjectAnimator.ofFloat(view, "scaleY", scale)
    AnimatorSet().apply {
        playTogether(scaleX, scaleY)
        this.duration = duration
        interpolator = AccelerateDecelerateInterpolator()
        start()
    }
}

