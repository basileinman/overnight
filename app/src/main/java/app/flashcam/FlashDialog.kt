package app.flashcam

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/** App-styled popup: dark card, letter-spaced labels, orange accents. Replaces the stock Android dialogs. */
object FlashDialog {

    enum class Style { PRIMARY, NORMAL, DANGER }
    data class Option(val label: String, val style: Style = Style.NORMAL, val action: () -> Unit)

    private const val ORANGE = 0xFFFF7A1F.toInt()

    fun show(ctx: Context, title: String, message: String? = null, options: List<Option>, cancel: String? = "CANCEL"): Dialog {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
        fun label(t: String, sp: Float, color: Int) = TextView(ctx).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.14f
        }

        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF121212.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x22FFFFFF)
            }
        }
        card.addView(label(title.uppercase(), 15f, Color.WHITE))
        if (message != null) {
            card.addView(label(message, 13f, 0x99FFFFFF.toInt()).apply {
                letterSpacing = 0.02f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(10), 0, dp(4))
            })
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        fun row(text: String, color: Int, filled: Boolean, onTap: () -> Unit) {
            val t = label(text.uppercase(), 14f, color).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    if (filled) setColor(0x1FFF7A1F) else setColor(0x0DFFFFFF)
                    if (filled) setStroke(dp(1), 0x66FF7A1F)
                }
                setOnClickListener { d.dismiss(); onTap() }
            }
            list.addView(t, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        options.forEach { o ->
            when (o.style) {
                Style.PRIMARY -> row(o.label, ORANGE, true, o.action)
                Style.NORMAL -> row(o.label, Color.WHITE, false, o.action)
                Style.DANGER -> row(o.label, 0xFFFF5A4A.toInt(), false, o.action)
            }
        }
        card.addView(list)
        if (cancel != null) {
            card.addView(label(cancel.uppercase(), 13f, 0x80FFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(14))
                setOnClickListener { d.dismiss() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        d.setContentView(card)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((ctx.resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.7f)
        }
        d.show()
        return d
    }

    /** Roll-size picker shared by the camera and roll page. */
    fun loadRoll(ctx: Context, title: String = "Load a roll", message: String? = "Photos stay hidden until the roll is developed.", onPick: (Int) -> Unit) =
        show(ctx, title, message, listOf(
            Option("36 exposures", Style.PRIMARY) { onPick(36) },
            Option("24 exposures") { onPick(24) },
            Option("12 exposures") { onPick(12) },
        ))

    @Suppress("unused") private fun unused(v: View) = v
}
