package app.flashcam

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Album pieces shared by the roll page and the album page. */
object AlbumUi {
    const val EXTRA_ALBUM = "albumId"
    const val STYLE_STACKS = 0
    const val STYLE_STRIPS = 1

    fun style(c: Context) = c.getSharedPreferences("flashcam", Context.MODE_PRIVATE).getInt("albumStyle", STYLE_STACKS)
    fun setStyle(c: Context, s: Int) = c.getSharedPreferences("flashcam", Context.MODE_PRIVATE).edit().putInt("albumStyle", s).apply()

    fun open(act: Activity, a: Albums.Album) {
        act.startActivity(Intent(act, AlbumActivity::class.java).putExtra(EXTRA_ALBUM, a.id))
        act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** Text box dialog for an album title. Empty = no title (the date alone). */
    fun askTitle(ctx: Context, heading: String, initial: String?, confirm: String, onDone: (String?) -> Unit) {
        fun dp(v: Int) = Ui.dpi(ctx, v)
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            background = Ui.card(ctx)
        }
        col.addView(Ui.text(ctx, heading.uppercase(), Ui.TITLE, Ui.WHITE))
        col.addView(Ui.body(ctx, "Optional. The date always shows; a title appears under it.", Ui.SMALL, Ui.FAINT).apply {
            setPadding(0, dp(8), 0, dp(12))
        })
        val input = EditText(ctx).apply {
            setText(initial ?: "")
            setSelection(text.length)
            hint = "e.g. Beach fire"
            setHintTextColor(0x55FFFFFF)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(24))
            background = Ui.button(ctx, false)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(input, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancel = Ui.text(ctx, "CANCEL", Ui.BUTTON, Ui.DIM).apply { setPadding(dp(14), dp(16), dp(14), dp(4)); setOnClickListener { d.dismiss() } }
        val ok = Ui.text(ctx, confirm.uppercase(), Ui.BUTTON, Ui.ORANGE).apply {
            setPadding(dp(14), dp(16), 0, dp(4))
            setOnClickListener { d.dismiss(); onDone(input.text.toString().trim().ifEmpty { null }) }
        }
        input.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { ok.performClick(); true } else false }
        row.addView(cancel); row.addView(ok)
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(col)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((ctx.resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.7f)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        d.show()
        input.requestFocus()
        input.postDelayed({ (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }, 150)
    }

    /** "Add to album": a new album, or one of your existing ones. */
    fun pickAlbum(act: Activity, photos: List<Roll.Photo>, all: List<Roll.Photo>, done: () -> Unit) {
        if (photos.isEmpty()) return
        val n = photos.size
        val mine = Albums.manualList(act, all).take(5)
        val opts = ArrayList<FlashDialog.Option>()
        opts.add(FlashDialog.Option("New album", FlashDialog.Style.PRIMARY) {
            askTitle(act, "New album", null, "Create") { t ->
                Albums.create(act, t, photos)
                Toast.makeText(act, "Album made · $n photo" + (if (n == 1) "" else "s"), Toast.LENGTH_SHORT).show()
                done()
            }
        })
        mine.forEach { a ->
            val label = (a.title?.uppercase()?.plus(" · ") ?: "") + Albums.dateStamp(a) + " · ${a.photos.size}"
            opts.add(FlashDialog.Option(label) {
                Albums.add(act, a.id, photos)
                Toast.makeText(act, "Added to ${a.title ?: "album"}", Toast.LENGTH_SHORT).show()
                done()
            })
        }
        FlashDialog.show(act, "Add $n photo" + (if (n == 1) "" else "s") + " to", null, opts)
    }

    /** One album tile: cover (stack or strip), LED date, optional LED title, count. */
    class Tile(ctx: Context, val style: Int) : LinearLayout(ctx) {
        val stack = if (style == STYLE_STACKS) StackCover(ctx) else null
        val strip = if (style == STYLE_STRIPS) StripCover(ctx) else null
        private val date = Albums.ledView(ctx, "", if (style == STYLE_STACKS) 13f else 14f)
        private val title = Albums.ledView(ctx, "", 9.5f, dim = true)
        private val count = Ui.text(ctx, "", Ui.TINY, Ui.DIM)

        init {
            orientation = VERTICAL
            val dp = { v: Int -> Ui.dpi(ctx, v) }
            if (style == STYLE_STACKS) {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(6), dp(4), dp(18))
                addView(stack, LayoutParams(-1, -2))
                listOf(date, title, count).forEach { it.gravity = Gravity.CENTER }
                addView(date, LayoutParams(-1, -2).apply { topMargin = dp(4) })
                addView(title, LayoutParams(-1, -2).apply { topMargin = dp(5) })
                addView(count, LayoutParams(-1, -2).apply { topMargin = dp(5) })
            } else {
                setPadding(dp(2), dp(10), dp(2), dp(14))
                val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM }
                val left = LinearLayout(ctx).apply { orientation = VERTICAL }
                left.addView(date); left.addView(title, LayoutParams(-2, -2).apply { topMargin = dp(5) })
                head.addView(left, LayoutParams(0, -2, 1f))
                head.addView(count)
                head.setPadding(dp(2), 0, dp(2), dp(8))
                addView(head, LayoutParams(-1, -2))
                addView(strip, LayoutParams(-1, -2))
            }
            listOf(date, title).forEach { it.setPadding(dp(2), dp(2), dp(2), dp(2)) }   // room for the glow
        }

        fun bind(a: Albums.Album) {
            stack?.bind(a); strip?.bind(a)
            date.text = Albums.ledText(Albums.dateStamp(a))
            title.text = a.title?.let { Albums.ledText(it) } ?: ""
            title.visibility = if (a.title.isNullOrBlank()) GONE else VISIBLE
            count.text = Albums.subtitle(a)
        }
    }

    @Suppress("unused") private fun unused(t: TextView) = t
}
