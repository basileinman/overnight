package app.flashcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.SeekBar

/** SeekBar with a small center/default notch so you can find your way back to it. */
class TickSeekBar(ctx: Context) : SeekBar(ctx) {
    /** 0..1 position of the notch, or negative for none. */
    var tick = -1f
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND }

    @Synchronized override fun onDraw(c: Canvas) {
        if (tick >= 0f) {
            val left = paddingLeft.toFloat(); val right = (width - paddingRight).toFloat()
            val x = left + tick * (right - left)
            val cy = height / 2f
            c.drawLine(x, cy - 7f * d, x, cy + 7f * d, paint)
        }
        super.onDraw(c)
    }
}
