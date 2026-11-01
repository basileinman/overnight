package app.flashcam

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

/**
 * ImageView with pinch-to-zoom, drag to pan, and double-tap to zoom in/out.
 * When not zoomed, horizontal flings are passed to [onSwipe] so the viewer can change photos.
 */
class ZoomImageView(ctx: Context) : ImageView(ctx) {

    var onSwipe: ((dir: Int) -> Unit)? = null   // -1 = swipe left, +1 = swipe right

    private val m = Matrix()
    private var baseScale = 1f
    private var zoom = 1f
    private val maxZoom = 6f
    private val density = resources.displayMetrics.density

    init {
        scaleType = ScaleType.MATRIX
    }

    val isZoomed get() = zoom > 1.02f

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (zoom * d.scaleFactor).coerceIn(1f, maxZoom)
            val f = target / zoom
            zoom = target
            m.postScale(f, f, d.focusX, d.focusY)
            clampAndApply()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!isZoomed) return false
            m.postTranslate(-dx, -dy)
            clampAndApply()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) {
                reset()
            } else {
                zoom = 2.5f
                m.postScale(2.5f, 2.5f, e.x, e.y)
                clampAndApply()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (isZoomed) return false
            val s = e1 ?: return false
            val dx = e2.x - s.x
            if (kotlin.math.abs(dx) > 60 * density && kotlin.math.abs(dx) > kotlin.math.abs(e2.y - s.y)) {
                onSwipe?.invoke(if (dx < 0) -1 else 1)
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        if (isZoomed || event.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        post { reset() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    /** Fit the whole photo on screen, centered. */
    fun reset() {
        val d = drawable ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        baseScale = min(vw / dw, vh / dh)
        zoom = 1f
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((vw - dw * baseScale) / 2f, (vh - dh * baseScale) / 2f)
        imageMatrix = m
    }

    /** Keep the photo from being dragged off-screen; center it on any axis smaller than the view. */
    private fun clampAndApply() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        val vw = width.toFloat(); val vh = height.toFloat()
        val dx = when {
            r.width() <= vw -> vw / 2f - r.centerX()
            r.left > 0 -> -r.left
            r.right < vw -> vw - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= vh -> vh / 2f - r.centerY()
            r.top > 0 -> -r.top
            r.bottom < vh -> vh - r.bottom
            else -> 0f
        }
        m.postTranslate(dx, dy)
        imageMatrix = m
    }
}
