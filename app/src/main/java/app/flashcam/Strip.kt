package app.flashcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.roundToInt

/** Stitches a burst into a vertical photo-booth style strip. */
object Strip {
    fun make(frames: List<Bitmap>, label: String): Bitmap {
        val fw = 720
        val pad = 40
        val gap = 28
        val footer = 90
        val heights = frames.map { (fw.toFloat() * it.height / it.width).roundToInt() }
        val totalH = pad + heights.sum() + gap * (frames.size - 1) + footer
        val totalW = fw + pad * 2

        val out = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(12, 10, 9))

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = pad.toFloat()
        frames.forEachIndexed { i, f ->
            val dst = RectF(pad.toFloat(), y, (pad + fw).toFloat(), y + heights[i])
            c.drawBitmap(f, null, dst, paint)
            y += heights[i] + gap
        }

        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 122, 31)
            textSize = 28f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.2f
        }
        c.drawText(label, pad.toFloat(), (totalH - footer / 2 + 10).toFloat(), tp)
        return out
    }
}
