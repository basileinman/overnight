package app.flashcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * One place for the app's look: colors, text sizes, fonts, button shapes and the small drawn icons.
 * Every screen builds its labels through [Ui.text] so sizes and spacing stay consistent.
 */
object Ui {
    const val ORANGE = 0xFFFF7A1F.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val SOFT = 0xE6FFFFFF.toInt()     // primary text on black
    const val DIM = 0x99FFFFFF.toInt()      // secondary text
    const val FAINT = 0x66FFFFFF            // hints, empty states
    const val OFF = 0x80FFFFFF.toInt()      // inactive toggles
    const val DANGER = 0xFFFF5A4A.toInt()
    const val CARD = 0xFF121212.toInt()
    const val CARD_LINE = 0x22FFFFFF

    // text sizes (sp). Kept small on purpose: the photos are the loud part.
    const val TITLE = 13f
    const val BUTTON = 12.5f
    const val BODY = 12f
    const val SMALL = 10.5f
    const val TINY = 9f

    /** Older call sites pass the previous (larger) sizes; this shrinks them to the new scale. */
    fun scaled(sp: Float) = sp * 0.86f

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    fun dpi(ctx: Context, v: Int) = dp(ctx, v.toFloat()).toInt()

    /** The standard label: medium weight, wide letter spacing (uppercase UI text). */
    fun text(ctx: Context, s: CharSequence, sp: Float = BUTTON, color: Int = SOFT) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = MEDIUM
        letterSpacing = 0.12f
        includeFontPadding = false
    }

    /** Sentence text (dialog messages, intro copy). */
    fun body(ctx: Context, s: CharSequence, sp: Float = BODY, color: Int = DIM) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = REGULAR
        letterSpacing = 0.02f
        setLineSpacing(0f, 1.3f)
    }

    fun card(ctx: Context) = GradientDrawable().apply {
        setColor(CARD); cornerRadius = dp(ctx, 16f); setStroke(dpi(ctx, 1), CARD_LINE)
    }

    /** Button background: filled orange outline for the main action, faint grey otherwise. */
    fun button(ctx: Context, primary: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(ctx, 10f)
        if (primary) { setColor(0x1FFF7A1F); setStroke(dpi(ctx, 1), 0x66FF7A1F) } else setColor(0x0DFFFFFF)
    }

    // ---------- drawn icons ----------

    enum class Icon { SINGLE, BURST, DOUBLE, NO_EFFECT, BLUR, DRAG, GEAR }

    /** Simple line icons drawn in code (no image files), tinted by [color]. */
    class IconDrawable(private val ctx: Context, var icon: Icon, var color: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(ctx, 1.5f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(c: Canvas) {
            p.color = color; fill.color = color
            val b = bounds
            val s = minOf(b.width(), b.height()).toFloat()
            val cx = b.exactCenterX(); val cy = b.exactCenterY()
            val u = s / 24f   // drawn on a 24-unit grid
            fun r(l: Float, t: Float, rr: Float, bb: Float) = RectF(cx + l * u, cy + t * u, cx + rr * u, cy + bb * u)
            when (icon) {
                Icon.SINGLE -> c.drawRoundRect(r(-5f, -7f, 5f, 7f), 1.5f * u, 1.5f * u, p)
                Icon.BURST -> {
                    c.drawRoundRect(r(-7f, -6f, 1f, 6f), 1.5f * u, 1.5f * u, p)
                    c.drawLine(cx + 4f * u, cy - 5f * u, cx + 4f * u, cy + 5f * u, p)
                    c.drawLine(cx + 7f * u, cy - 4f * u, cx + 7f * u, cy + 4f * u, p)
                }
                Icon.DOUBLE -> {
                    c.drawCircle(cx - 2.5f * u, cy, 5.5f * u, p)
                    c.drawCircle(cx + 2.5f * u, cy, 5.5f * u, p)
                }
                Icon.NO_EFFECT -> {
                    c.drawCircle(cx, cy, 6f * u, p)
                    c.drawCircle(cx, cy, 1.2f * u, fill)
                }
                Icon.BLUR -> {
                    c.drawCircle(cx + 3f * u, cy, 4.5f * u, p)
                    c.drawLine(cx - 8f * u, cy - 3f * u, cx - 3f * u, cy - 3f * u, p)
                    c.drawLine(cx - 9f * u, cy, cx - 3.5f * u, cy, p)
                    c.drawLine(cx - 8f * u, cy + 3f * u, cx - 3f * u, cy + 3f * u, p)
                }
                Icon.DRAG -> {
                    // a light streak ending in a frozen subject
                    val path = Path().apply {
                        moveTo(cx - 9f * u, cy + 5f * u)
                        cubicTo(cx - 5f * u, cy + 5f * u, cx - 4f * u, cy - 4f * u, cx + 1f * u, cy - 3f * u)
                    }
                    c.drawPath(path, p)
                    c.drawCircle(cx + 4.5f * u, cy - 2f * u, 3.5f * u, fill)
                }
                Icon.GEAR -> {
                    c.drawCircle(cx, cy, 3f * u, p)
                    c.drawCircle(cx, cy, 7f * u, p)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        val x0 = cx + (7f * u) * Math.cos(a).toFloat(); val y0 = cy + (7f * u) * Math.sin(a).toFloat()
                        val x1 = cx + (9.5f * u) * Math.cos(a).toFloat(); val y1 = cy + (9.5f * u) * Math.sin(a).toFloat()
                        c.drawLine(x0, y0, x1, y1, p)
                    }
                }
            }
        }

        override fun setAlpha(alpha: Int) { p.alpha = alpha; fill.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

/** Faint corner marks on the viewfinder: just enough to frame the shot. */
class FrameMarks @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF; strokeWidth = 1f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE
    }

    override fun onDraw(c: Canvas) {
        val inset = 12f * d; val len = 12f * d
        val l = inset; val t = inset; val r = width - inset; val b = height - inset
        c.drawLine(l, t, l + len, t, p); c.drawLine(l, t, l, t + len, p)
        c.drawLine(r, t, r - len, t, p); c.drawLine(r, t, r, t + len, p)
        c.drawLine(l, b, l + len, b, p); c.drawLine(l, b, l, b - len, p)
        c.drawLine(r, b, r - len, b, p); c.drawLine(r, b, r, b - len, p)
    }
}
