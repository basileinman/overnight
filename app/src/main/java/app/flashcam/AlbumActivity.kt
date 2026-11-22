package app.flashcam

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** One album: LED date header, its photos, SHARE ALL, title / remove / delete. */
class AlbumActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var all: List<Roll.Photo> = emptyList()
    private var album: Albums.Album? = null
    private var selecting = false
    private val selected = LinkedHashSet<Long>()

    private lateinit var date: TextView
    private lateinit var title: TextView
    private lateinit var count: TextView
    private lateinit var grid: RecyclerView
    private lateinit var selectBtn: TextView
    private lateinit var bar: LinearLayout
    private lateinit var shareSel: TextView
    private lateinit var removeSel: TextView
    private val adapter = Adapter()

    private val deletePrompt = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { exitSelect(); reload() }

    private fun dp(v: Int) = Ui.dpi(this, v)
    private val id get() = intent.getStringExtra(AlbumUi.EXTRA_ALBUM) ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(18), dp(6))
        }
        val back = Ui.text(this, "‹ ALBUMS", Ui.BUTTON, Ui.ORANGE).apply {
            setPadding(dp(4), dp(8), dp(12), dp(8)); setOnClickListener { finish() }
        }
        selectBtn = Ui.text(this, "SELECT", Ui.BUTTON, Ui.SOFT).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { if (selecting) exitSelect() else enterSelect() }
        }
        val more = Ui.text(this, "•••", Ui.BUTTON, Ui.SOFT).apply {
            setPadding(dp(12), dp(8), dp(4), dp(8)); setOnClickListener { menu() }
        }
        head.addView(back)
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(selectBtn); head.addView(more)

        // LED header: date (main), title (sub), count
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(14))
        }
        date = Albums.ledView(this, "", 24f).apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
        title = Albums.ledView(this, "", 12f, dim = true).apply { setPadding(dp(2), dp(6), dp(2), dp(2)) }
        count = Ui.text(this, "", Ui.SMALL, Ui.DIM).apply { setPadding(dp(2), dp(10), 0, 0) }
        val shareAll = Ui.text(this, "SHARE ALL", Ui.BUTTON, Ui.ORANGE).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = Ui.button(this@AlbumActivity, true)
            setOnClickListener { album?.let { a -> RollActions.share(this@AlbumActivity, a.photos.map { it.uri }) } }
        }
        info.addView(date); info.addView(title); info.addView(count)
        info.addView(shareAll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AlbumActivity, 3)
            adapter = this@AlbumActivity.adapter
            clipToPadding = false
            setPadding(dp(12), 0, dp(12), dp(90))
        }
        col.addView(head); col.addView(info)
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xF20E0E0E.toInt()); visibility = View.GONE
        }
        shareSel = Ui.text(this, "SHARE", Ui.BUTTON, Ui.WHITE).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20))
            setOnClickListener { RollActions.share(this@AlbumActivity, sel().map { it.uri }) }
        }
        removeSel = Ui.text(this, "REMOVE", Ui.BUTTON, Ui.ORANGE).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20))
            setOnClickListener { removeSelected() }
        }
        bar.addView(shareSel, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(removeSel, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, 0); bar.setPadding(0, 0, 0, b.bottom)
            grid.setPadding(dp(12), 0, dp(12), dp(24) + b.bottom)
            insets
        }
    }

    override fun onResume() { super.onResume(); reload() }
    override fun onDestroy() { super.onDestroy(); loader.shutdownNow() }

    private fun reload() {
        loader.execute {
            val list = Roll.load(this)
            val a = Albums.find(this, list, id)
            main.post {
                if (isFinishing) return@post
                all = list; album = a
                if (a == null) { finish(); return@post }
                date.text = Albums.ledText(Albums.dateStamp(a))
                title.text = a.title?.let { Albums.ledText(it) } ?: ""
                title.visibility = if (a.title.isNullOrBlank()) View.GONE else View.VISIBLE
                count.text = Albums.subtitle(a)
                removeSel.text = if (a.kind == Albums.Kind.MANUAL) "REMOVE" else "DELETE"
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun photos() = album?.photos ?: emptyList()
    private fun sel() = photos().filter { it.id in selected }

    private fun menu() {
        val a = album ?: return
        val manual = a.kind == Albums.Kind.MANUAL
        FlashDialog.show(this, Albums.dateStamp(a) + (a.title?.let { " · ${it.uppercase()}" } ?: ""), null, listOf(
            FlashDialog.Option(if (a.title == null) "Add a title" else "Rename") {
                AlbumUi.askTitle(this, if (a.title == null) "Add a title" else "Rename", a.title, "Save") { t ->
                    Albums.rename(this, a.id, t); reload()
                }
            },
            FlashDialog.Option(if (manual) "Delete album" else "Hide album", FlashDialog.Style.DANGER) {
                FlashDialog.show(this, if (manual) "Delete this album?" else "Hide this album?",
                    "Your photos stay in ALL. " + (if (manual) "Only the album goes away." else "It won't come back on its own."),
                    listOf(FlashDialog.Option(if (manual) "Delete album" else "Hide album", FlashDialog.Style.DANGER) {
                        Albums.delete(this, a.id); finish()
                    }))
            },
        ))
    }

    private fun enterSelect() { selecting = true; selected.clear(); selectBtn.text = "CANCEL"; bar.visibility = View.VISIBLE; counts(); adapter.notifyDataSetChanged() }
    private fun exitSelect() { selecting = false; selected.clear(); selectBtn.text = "SELECT"; bar.visibility = View.GONE; adapter.notifyDataSetChanged() }
    private fun counts() {
        val n = selected.size
        shareSel.text = if (n > 0) "SHARE $n" else "SHARE"
        val manual = album?.kind == Albums.Kind.MANUAL
        removeSel.text = (if (manual) "REMOVE" else "DELETE") + if (n > 0) " $n" else ""
    }

    private fun removeSelected() {
        val a = album ?: return
        val s = sel(); if (s.isEmpty()) return
        val n = s.size; val plural = if (n == 1) "" else "s"
        if (a.kind == Albums.Kind.MANUAL) {
            FlashDialog.show(this, "Remove $n photo$plural from this album?", "They stay in ALL.",
                listOf(FlashDialog.Option("Remove", FlashDialog.Style.DANGER) { Albums.remove(this, a.id, s); exitSelect(); reload() }))
        } else {
            FlashDialog.show(this, "Delete $n photo$plural", "This removes them from the roll and your phone's gallery.",
                listOf(FlashDialog.Option("Delete", FlashDialog.Style.DANGER) {
                    if (RollActions.delete(this, s.map { it.uri }, deletePrompt)) { exitSelect(); reload() }
                }))
        }
    }

    private class Cell(ctx: android.content.Context) : ImageView(ctx) {
        override fun onMeasure(w: Int, h: Int) { val x = MeasureSpec.getSize(w); setMeasuredDimension(x, x * 3 / 2) }
    }

    private inner class VH(val box: FrameLayout, val img: ImageView, val check: View) : RecyclerView.ViewHolder(box)

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = photos().size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val box = FrameLayout(this@AlbumActivity).apply {
                setPadding(dp(3), dp(3), dp(3), dp(3)); layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
            val img = Cell(this@AlbumActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF161616.toInt()) }
            val check = View(this@AlbumActivity)
            box.addView(img, FrameLayout.LayoutParams(-1, -2))
            box.addView(check, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(8) })
            return VH(box, img, check)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = photos()[pos]
            val on = p.id in selected
            h.check.visibility = if (selecting) View.VISIBLE else View.GONE
            h.check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setStroke(dp(2), Color.WHITE); setColor(if (on) Ui.ORANGE else 0x33000000)
            }
            h.img.tag = p.id
            h.img.setImageBitmap(Thumbs.get(p.id))
            if (Thumbs.get(p.id) == null) Thumbs.load(this@AlbumActivity, p, 480) { b -> if (h.img.tag == p.id) h.img.setImageBitmap(b) }
            h.box.setOnClickListener {
                if (selecting) {
                    if (!selected.remove(p.id)) selected.add(p.id)
                    notifyItemChanged(h.bindingAdapterPosition); counts()
                } else {
                    startActivity(Intent(this@AlbumActivity, ViewerActivity::class.java)
                        .putExtra(GalleryActivity.EXTRA_INDEX, pos)
                        .putStringArrayListExtra(ViewerActivity.EXTRA_NAMES, ArrayList(photos().map { it.name })))
                }
            }
            h.box.setOnLongClickListener {
                if (!selecting) enterSelect()
                selected.add(p.id); notifyItemChanged(h.bindingAdapterPosition); counts(); true
            }
        }
    }
}
