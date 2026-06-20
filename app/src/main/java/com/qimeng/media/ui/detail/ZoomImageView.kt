package com.qimeng.media.ui.detail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewTreeObserver
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min
import com.qimeng.media.core.GpuInfo
import com.qimeng.media.ui.widget.dp
import com.qimeng.media.ui.widget.dpFloat

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    var onSingleTap: (() -> Unit)? = null
    var onSwipe: ((direction: Int) -> Unit)? = null

    private val drawMatrix = Matrix()
    private val mappedRect = RectF()
    private val pendingScreenRect = RectF()
    private val windowLocation = IntArray(2)
    private var normalizedScale = 1f
    private var lastWindowX = Int.MIN_VALUE
    private var lastWindowY = Int.MIN_VALUE
    private var hasPendingScreenRect = false
    private var isGestureActive = false
    private var hasDisallowedIntercept = false
    private val resetLayerRunnable = Runnable {
        // 手势结束后按当前图尺寸智能恢复层类型（大图保持 HARDWARE，超大图回 SOFTWARE）
        if (!isGestureActive) {
            applyOptimalLayerType(drawable)
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isGestureActive = true
                removeCallbacks(resetLayerRunnable)
                // 手势期间切硬件加速（GPU 矩阵变换消除卡顿），超大图（超 GPU 纹理上限）除外
                if (shouldUseHardwareForCurrent()) {
                    setLayerType(LAYER_TYPE_HARDWARE, null)
                }
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
                if (!hasDisallowedIntercept) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    hasDisallowedIntercept = true
                }
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (normalizedScale > 1.1f) {
                    resetZoom()
                } else {
                    scaleTo(DOUBLE_TAP_SCALE, e.x, e.y)
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (normalizedScale <= 1.05f) return false
                if (!isGestureActive) {
                    isGestureActive = true
                    removeCallbacks(resetLayerRunnable)
                    // 手势期间切硬件加速，超大图（超 GPU 纹理上限）除外
                    if (shouldUseHardwareForCurrent()) {
                        setLayerType(LAYER_TYPE_HARDWARE, null)
                    }
                }
                drawMatrix.postTranslate(-distanceX, -distanceY)
                clampTranslation()
                imageMatrix = drawMatrix
                if (!hasDisallowedIntercept) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    hasDisallowedIntercept = true
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || normalizedScale > 1.05f) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > SWIPE_DISTANCE_DP.dpFloat(context) && abs(dx) > abs(dy) && abs(velocityX) > SWIPE_VELOCITY) {
                    onSwipe?.invoke(if (dx < 0f) 1 else -1)
                    return true
                }
                return false
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        clipToOutline = false
        // 初始默认 SOFTWARE（首帧加载前无图，SOFTWARE 安全）；
        // 加载图后由 applyOptimalLayerType 按尺寸智能切层：
        //   长边 <= GPU 纹理上限（GpuInfo 运行时探测）→ HARDWARE（GPU 直渲，4096 图 ~50ms→~5ms）
        //   长边 > GPU 上限 → SOFTWARE 回退（避免超 OpenGL 纹理限制渲染异常）
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private var lastDrawableWidth = 0
    private var lastDrawableHeight = 0
    private var overrideDrawableSize = false

    fun setDrawableSize(width: Int, height: Int) {
        lastDrawableWidth = width
        lastDrawableHeight = height
        overrideDrawableSize = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        if (drawable == null) overrideDrawableSize = false
        // 按图尺寸智能选层：GIF 用 HARDWARE（动画依赖硬件刷新）；
        // 普通图长边 <= GPU 纹理上限 → HARDWARE（GPU 直渲），超限 → SOFTWARE 回退
        if (!isGestureActive) {
            applyOptimalLayerType(drawable)
        }
        super.setImageDrawable(drawable)
        if (drawable == null || drawable.intrinsicWidth <= 0) {
            com.qimeng.media.core.AppLog.d("Detail", "setImageDrawable: drawable=null或尺寸无效 intrinsic=${drawable?.intrinsicWidth}x${drawable?.intrinsicHeight} override=$overrideDrawableSize")
            return
        }
        val bmp = (drawable as? BitmapDrawable)?.bitmap
        if (!overrideDrawableSize) {
            lastDrawableWidth = drawable.intrinsicWidth
            lastDrawableHeight = drawable.intrinsicHeight
        }
        com.qimeng.media.core.AppLog.d("Detail", "setImageDrawable: intrinsic=${drawable.intrinsicWidth}x${drawable.intrinsicHeight} bitmap=${bmp?.width}x${bmp?.height} lastDrawable=${lastDrawableWidth}x${lastDrawableHeight} override=$overrideDrawableSize view=${width}x${height}")
        if (width > 0 && height > 0) {
            resetZoom()
        } else {
            viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    viewTreeObserver.removeOnPreDrawListener(this)
                    if (width > 0 && height > 0) resetZoom()
                    return true
                }
            })
        }
    }

    override fun setImageBitmap(bm: Bitmap?) {
        // 按图尺寸智能选层（BitmapDrawable 包装后与 setImageDrawable 走同一判断）
        if (!isGestureActive) {
            applyOptimalLayerType(bm?.let { BitmapDrawable(resources, it) })
        }
        super.setImageBitmap(bm)
    }

    /**
     * 按当前 drawable 尺寸智能选择渲染层类型。
     * - GIF（AnimatedImageDrawable）→ LAYER_TYPE_HARDWARE（动画依赖硬件刷新帧）
     * - 普通图长边 <= GPU 纹理上限（GpuInfo 探测）→ LAYER_TYPE_HARDWARE（GPU 直渲，大图 ~50ms→~5ms）
     * - 普通图长边 > GPU 上限 → LAYER_TYPE_SOFTWARE 回退（避免超 OpenGL 纹理限制渲染异常）
     */
    private fun applyOptimalLayerType(drawable: Drawable?) {
        if (containsAnimatedDrawable(drawable)) {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            return
        }
        val longside = drawableLongSide(drawable)
        if (longside <= 0) {
            // 无尺寸信息（图尚未解码），保守用 SOFTWARE
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            return
        }
        val maxSize = GpuInfo.maxTextureSize()
        if (longside <= maxSize) {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        } else {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    /** 取 drawable 长边像素数；BitmapDrawable 用 bitmap 真实尺寸，其他用 intrinsicSize */
    private fun drawableLongSide(drawable: Drawable?): Int {
        if (drawable == null) return 0
        val bmp = (drawable as? BitmapDrawable)?.bitmap
        if (bmp != null) return maxOf(bmp.width, bmp.height)
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        return if (w > 0 && h > 0) maxOf(w, h) else 0
    }

    /**
     * 当前图是否适合硬件渲染（供手势期间判断：超大图手势时也不切 HARDWARE）。
     */
    private fun shouldUseHardwareForCurrent(): Boolean {
        val drawable = drawable ?: return false
        if (containsAnimatedDrawable(drawable)) return true
        val longside = drawableLongSide(drawable)
        if (longside <= 0) return false
        return longside <= GpuInfo.maxTextureSize()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val previousX = lastWindowX
        val previousY = lastWindowY
        super.onLayout(changed, left, top, right, bottom)
        if (hasPendingScreenRect) {
            restorePendingScreenRect()
        } else {
            preserveScreenPosition(previousX, previousY)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            clampScaleEnd()
            isGestureActive = false
            hasDisallowedIntercept = false
            parent?.requestDisallowInterceptTouchEvent(false)
            postDelayed(resetLayerRunnable, 100)
        }
        return handled || scaleDetector.isInProgress || true
    }

    override fun performClick(): Boolean = super.performClick()

    fun resetZoom() {
        configureBaseMatrix()
        imageMatrix = drawMatrix
    }

    fun preserveCurrentScreenRect() {
        if (lastDrawableWidth <= 0 || lastDrawableHeight <= 0) return
        mappedRect.set(0f, 0f, lastDrawableWidth.toFloat(), lastDrawableHeight.toFloat())
        drawMatrix.mapRect(mappedRect)
        getLocationOnScreen(windowLocation)
        pendingScreenRect.set(
            mappedRect.left + windowLocation[0],
            mappedRect.top + windowLocation[1],
            mappedRect.right + windowLocation[0],
            mappedRect.bottom + windowLocation[1]
        )
        hasPendingScreenRect = true
    }

    fun restorePreservedScreenRectIfNeeded() {
        if (hasPendingScreenRect) restorePendingScreenRect()
    }

    private fun configureBaseMatrix() {
        val dw = lastDrawableWidth
        val dh = lastDrawableHeight
        if (dw <= 0 || dh <= 0) return
        val viewW = width - paddingLeft - paddingRight
        val viewH = height - paddingTop - paddingBottom
        if (viewW <= 0 || viewH <= 0) return
        val scale = min(viewW.toFloat() / dw, viewH.toFloat() / dh)
        val dx = (viewW - dw * scale) / 2f + paddingLeft
        val dy = (viewH - dh * scale) / 2f + paddingTop
        com.qimeng.media.core.AppLog.d("Detail", "configureBaseMatrix: drawable=${dw}x${dh}, view=${width}x${height}, scale=$scale, dx=$dx, dy=$dy")
        drawMatrix.reset()
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        normalizedScale = 1f
    }

    private fun preserveScreenPosition(previousX: Int, previousY: Int) {
        getLocationOnScreen(windowLocation)
        if (drawable != null && previousX != Int.MIN_VALUE && previousY != Int.MIN_VALUE) {
            val dx = previousX - windowLocation[0]
            val dy = previousY - windowLocation[1]
            if (dx != 0 || dy != 0) {
                drawMatrix.postTranslate(dx.toFloat(), dy.toFloat())
                imageMatrix = drawMatrix
            }
        }
        lastWindowX = windowLocation[0]
        lastWindowY = windowLocation[1]
    }

    private fun restorePendingScreenRect() {
        if (lastDrawableWidth <= 0 || lastDrawableHeight <= 0) {
            hasPendingScreenRect = false
            return
        }
        getLocationOnScreen(windowLocation)
        mappedRect.set(0f, 0f, lastDrawableWidth.toFloat(), lastDrawableHeight.toFloat())
        drawMatrix.mapRect(mappedRect)
        val dx = pendingScreenRect.left - (mappedRect.left + windowLocation[0])
        val dy = pendingScreenRect.top - (mappedRect.top + windowLocation[1])
        if (dx != 0f || dy != 0f) {
            drawMatrix.postTranslate(dx, dy)
            imageMatrix = drawMatrix
        }
        hasPendingScreenRect = false
        lastWindowX = windowLocation[0]
        lastWindowY = windowLocation[1]
    }

    private fun clampScaleEnd() {
        if (normalizedScale < MIN_SCALE) {
            scaleTo(MIN_SCALE, width / 2f, height / 2f)
        } else if (normalizedScale > END_MAX_SCALE) {
            scaleTo(END_MAX_SCALE, width / 2f, height / 2f)
        } else {
            clampTranslation()
            imageMatrix = drawMatrix
        }
    }

    private fun clampTranslation() {
        if (lastDrawableWidth <= 0 || lastDrawableHeight <= 0) return
        mappedRect.set(0f, 0f, lastDrawableWidth.toFloat(), lastDrawableHeight.toFloat())
        drawMatrix.mapRect(mappedRect)
        val viewW = (width - paddingLeft - paddingRight).toFloat()
        val viewH = (height - paddingTop - paddingBottom).toFloat()
        var dx = 0f
        var dy = 0f
        if (mappedRect.width() <= viewW) {
            dx = (viewW - mappedRect.width()) / 2f - mappedRect.left + paddingLeft
        } else {
            if (mappedRect.left > paddingLeft) dx = paddingLeft - mappedRect.left
            if (mappedRect.right < viewW + paddingLeft) dx = viewW + paddingLeft - mappedRect.right
        }
        if (mappedRect.height() <= viewH) {
            dy = (viewH - mappedRect.height()) / 2f - mappedRect.top + paddingTop
        } else {
            if (mappedRect.top > paddingTop) dy = paddingTop - mappedRect.top
            if (mappedRect.bottom < viewH + paddingTop) dy = viewH + paddingTop - mappedRect.bottom
        }
        if (dx != 0f || dy != 0f) drawMatrix.postTranslate(dx, dy)
    }

    private fun scaleTo(targetScale: Float, px: Float, py: Float) {
        val factor = targetScale / normalizedScale
        scaleBy(factor, px, py)
    }

    private fun scaleBy(factor: Float, px: Float, py: Float) {
        if (abs(factor - 1f) < 0.001f) return
        val newScale = normalizedScale * factor
        if (newScale < MIN_SCALE || newScale > MAX_SCALE) return
        normalizedScale = newScale
        drawMatrix.postScale(factor, factor, px, py)
        clampTranslation()
        imageMatrix = drawMatrix
    }

    /** 检查Drawable是否包含AnimatedImageDrawable（Coil 3 ScaleDrawable的child字段） */
    private fun containsAnimatedDrawable(drawable: Drawable?): Boolean {
        if (drawable == null) return false
        if (drawable is android.graphics.drawable.AnimatedImageDrawable) return true
        // Coil 3 的 ScaleDrawable 将 AnimatedImageDrawable 存在 child 字段中
        try {
            val childField = drawable.javaClass.getDeclaredField("child")
            childField.isAccessible = true
            if (childField.get(drawable) is android.graphics.drawable.AnimatedImageDrawable) return true
        } catch (e: NoSuchFieldException) {
            // 预期路径：非 Coil ScaleDrawable 的普通 Drawable（如 BitmapDrawable）没有 child 字段，静默
        } catch (e: Exception) {
            com.qimeng.media.core.AppLog.d("Zoom", "checkAnimatedDrawable 异常: ${e.message}")
        }
        return false
    }

    companion object {
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 5f
        private const val END_MAX_SCALE = 5f
        private const val DOUBLE_TAP_SCALE = 1.8f
        private const val SWIPE_DISTANCE_DP = 60
        private const val SWIPE_VELOCITY = 800f
    }

}
