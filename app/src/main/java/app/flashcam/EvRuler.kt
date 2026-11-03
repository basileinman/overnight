package app.flashcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** Vertical exposure ruler shown while holding + sliding on the viewfinder. +2 EV at top, -2 at bottom. */
class EvRuler @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var range = 2f
        set(v) { field = v; invalidate() }
    var ev = 0f
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); strokeWidth = 1.5f * d }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7A1F.toInt(); strokeWidth = 3f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt(); textSize = 11f * d; typeface = Typeface.MONOSPACE
    }

    private fun yFor(v: Float): Float {
        val top = paddingTop + 24f * d
        val bottom = height - paddingBottom - 24f * d
        return top + (range - v) / (2f * range) * (bottom - top)
    }

    override fun onDraw(c: Canvas) {
        val x = 18f * d
        c.drawLine(x, yFor(range), x, yFor(-range), line)
        var v = -range
        while (v <= range + 0.001f) {
            val y = yFor(v)
            val major = abs1(v) < 0.01f || abs1(v - Math.round(v)) < 0.01f
            c.drawLine(x - (if (major) 8f else 4f) * d, y, x + (if (major) 8f else 4f) * d, y, line)
            if (major) {
                val label = if (v > 0) "+${v.toInt()}" else "${v.toInt()}"
                c.drawText(label, x + 12f * d, y + 4f * d, text)
            }
            v += 0.5f
        }
        val my = yFor(ev.coerceIn(-range, range))
        c.drawLine(x - 10f * d, my, x + 10f * d, my, mark)
    }

    private fun abs1(f: Float) = if (f < 0) -f else f
}
