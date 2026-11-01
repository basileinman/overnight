package app.flashcam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
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

/** Film-roll grid: MAIN / ♥ tabs, three across, numbered frames, multi-select to share or delete. */
class GalleryActivity : ComponentActivity() {

    companion object {
        const val ORANGE = 0xFFFF7A1F.toInt()
        const val EXTRA_FAV_ONLY = "favOnly"
        const val EXTRA_INDEX = "index"
    }

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<Long, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    private var all: List<Roll.Photo> = emptyList()
    private var shown: List<Roll.Photo> = emptyList()

    private sealed class Item {
        class Card(val title: String, val sub: String, val frames: Int, val total: Int) : Item()
        class Divider(val text: String) : Item()
        class Pic(val index: Int) : Item()
    }
    private var items: List<Item> = emptyList()
    private var favOnly = false
    private var selecting = false
    private val selected = LinkedHashSet<Long>()

    private lateinit var grid: RecyclerView
    private lateinit var tabMain: TextView
    private lateinit var tabFav: TextView
    private lateinit var selectBtn: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var emptyText: TextView
    private val adapter = Adapter()

    private val deletePrompt = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        exitSelect(); reload()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // header: MAIN  ♥            SELECT  DONE
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        tabMain = label("MAIN", 15f, Color.WHITE).apply { setOnClickListener { setTab(false) } }
        tabFav = label("♥", 17f, 0x80FFFFFF.toInt()).apply {
            setPadding(dp(22), 0, dp(8), 0)
            setOnClickListener { setTab(true) }
        }
        selectBtn = label("SELECT", 14f, Color.WHITE).apply {
            setPadding(dp(12), dp(6), dp(18), dp(6))
            setOnClickListener { if (selecting) exitSelect() else enterSelect() }
        }
        val done = label("DONE", 14f, ORANGE).apply {
            setPadding(dp(6), dp(6), 0, dp(6))
            setOnClickListener { finish() }
        }
        header.addView(tabMain)
        header.addView(tabFav)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(selectBtn)
        header.addView(done)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (items.getOrNull(position) is Item.Pic) 1 else 3
                }
            }
            setPadding(dp(12), dp(4), dp(12), dp(90))
            clipToPadding = false
            adapter = this@GalleryActivity.adapter
            setItemViewCacheSize(30)
        }
        emptyText = label("", 13f, 0x66FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; visibility = View.GONE
        }

        column.addView(header)
        val body = FrameLayout(this)
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        body.addView(emptyText, FrameLayout.LayoutParams(-1, -1))
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // select-mode bottom bar: SHARE | DELETE
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xF20E0E0E.toInt())
            visibility = View.GONE
        }
        val share = label("SHARE", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(22))
            setOnClickListener { RollActions.share(this@GalleryActivity, selectedPhotos().map { it.uri }) }
        }
        val del = label("DELETE", 15f, ORANGE).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(22))
            setOnClickListener {
                val sel = selectedPhotos()
                if (sel.isNotEmpty() && RollActions.delete(this@GalleryActivity, sel.map { it.uri }, deletePrompt)) {
                    exitSelect(); reload()
                }
            }
        }
        bottomBar.addView(share, LinearLayout.LayoutParams(0, -2, 1f))
        bottomBar.addView(del, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, 0)
            bottomBar.setPadding(0, 0, 0, bars.bottom)
            grid.setPadding(dp(12), dp(4), dp(12), dp(24) + bars.bottom)
            insets
        }
        updateTabs()
    }

    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        if (resumedOnce) cache.evictAll()  // pick up edits/deletes made in the viewer
        resumedOnce = true
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun reload() {
        loader.execute {
            val dev = Rolls.tick(this)
            if (dev.isNotEmpty()) main.post { android.widget.Toast.makeText(this, "Your roll is developed", android.widget.Toast.LENGTH_SHORT).show() }
            val list = Roll.load(this)
            main.post { all = list; applyFilter() }
        }
    }

    private fun applyFilter() {
        val favs = Roll.favorites(this)
        shown = if (favOnly) all.filter { it.id in favs } else all
        emptyText.text = if (favOnly) "TAP ♥ ON A PHOTO TO SAVE IT HERE" else "NO PHOTOS YET"
        // build grid items: developing cards, then photos with a divider at the start of each developed roll
        val out = ArrayList<Item>()
        if (!favOnly) {
            Rolls.active(this)?.let { a ->
                if (a.used > 0) out.add(Item.Card("IN CAMERA", "${a.used} OF ${a.size} SHOT", a.used, a.size))
            }
            Rolls.lab(this).sortedBy { it.ready }.forEach { r ->
                out.add(Item.Card("DEVELOPING", Rolls.readyLabel(r.ready), r.used, r.size))
            }
        }
        val rollOf = HashMap<String, Rolls.Developed>()
        Rolls.developed(this).forEach { d -> d.names.forEach { rollOf[it] = d } }
        val seen = HashSet<String>()
        val fmt = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
        shown.forEachIndexed { i, p ->
            val d = rollOf[p.name]
            if (!favOnly && d != null && seen.add(d.id)) {
                out.add(Item.Divider("ROLL · " + fmt.format(java.util.Date(d.date)).uppercase() + " · ${d.names.size} FRAMES"))
            }
            out.add(Item.Pic(i))
        }
        items = out
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun setTab(fav: Boolean) {
        if (fav == favOnly) return
        favOnly = fav
        exitSelect()
        updateTabs()
        applyFilter()
    }

    private fun updateTabs() {
        tabMain.setTextColor(if (!favOnly) Color.WHITE else 0x80FFFFFF.toInt())
        tabFav.setTextColor(if (favOnly) ORANGE else 0x80FFFFFF.toInt())
        tabMain.background = if (!favOnly) underline() else null
    }

    private fun underline() = object : android.graphics.drawable.Drawable() {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE }
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; val w = dp(22).toFloat(); val h = dp(3).toFloat()
            c.drawRoundRect(b.left.toFloat(), b.bottom - h, b.left + w, b.bottom.toFloat(), h, h, p)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun enterSelect() {
        selecting = true; selected.clear()
        selectBtn.text = "CANCEL"
        bottomBar.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun exitSelect() {
        selecting = false; selected.clear()
        selectBtn.text = "SELECT"
        bottomBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun selectedPhotos() = shown.filter { it.id in selected }

    private fun label(text: String, sp: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.14f
    }

    /** ImageView that is always 2:3 tall. */
    private class FrameImage(ctx: Context) : ImageView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, w * 3 / 2)
        }
    }

    private inner class Holder(val root: View, val img: ImageView?, val num: TextView?, val check: View?, val text: TextView?) :
        RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = when (items[position]) { is Item.Pic -> 0; is Item.Card -> 1; is Item.Divider -> 2 }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == 1) {
                val box = LinearLayout(this@GalleryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    background = GradientDrawable().apply { setColor(0xFF141414.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0x33FF7A1F) }
                    layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(3), dp(4), dp(3), dp(12)) }
                }
                val t = label("", 13f, Color.WHITE)
                box.addView(t)
                return Holder(box, null, null, null, t)
            }
            if (viewType == 2) {
                val t = label("", 11f, 0x80FFFFFF.toInt()).apply {
                    setPadding(dp(4), dp(14), 0, dp(8))
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                }
                return Holder(t, null, null, null, t)
            }
            val cell = LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(3), dp(3), dp(3), dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
            val frame = FrameLayout(this@GalleryActivity)
            val img = FrameImage(this@GalleryActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF161616.toInt())
            }
            val check = View(this@GalleryActivity)
            frame.addView(img, FrameLayout.LayoutParams(-1, -2))
            frame.addView(check, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8); rightMargin = dp(8)
            })
            val num = label("", 12f, 0x99FFFFFF.toInt()).apply { setPadding(dp(1), dp(6), 0, 0) }
            cell.addView(frame)
            cell.addView(num)
            return Holder(cell, img, num, check, null)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            when (val it = items[position]) {
                is Item.Card -> {
                    val filled = "■".repeat(it.frames.coerceAtMost(36))
                    val empty = "□".repeat((it.total - it.frames).coerceIn(0, 36))
                    h.text!!.text = android.text.SpannableStringBuilder().apply {
                        append(it.title, android.text.style.ForegroundColorSpan(ORANGE), 0)
                        append("   " + it.sub + "\n\n")
                        append(filled + empty, android.text.style.ForegroundColorSpan(0x66FFFFFF), 0)
                    }
                }
                is Item.Divider -> h.text!!.text = it.text
                is Item.Pic -> bindPic(h, it.index, position)
            }
        }

        private fun bindPic(h: Holder, index: Int, position: Int) {
            val p = shown[index]
            val img = h.img!!; val check = h.check!!
            h.num!!.text = "%03d".format(p.number)
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            val on = p.id in selected
            check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.WHITE)
                setColor(if (on) ORANGE else 0x33000000)
            }
            img.alpha = if (selecting && !on) 0.85f else 1f
            h.root.setOnClickListener {
                if (selecting) {
                    if (!selected.remove(p.id)) selected.add(p.id)
                    notifyItemChanged(h.bindingAdapterPosition)
                } else {
                    startActivity(Intent(this@GalleryActivity, ViewerActivity::class.java)
                        .putExtra(EXTRA_INDEX, index).putExtra(EXTRA_FAV_ONLY, favOnly))
                }
            }
            h.root.setOnLongClickListener {
                if (!selecting) enterSelect()
                selected.add(p.id); notifyItemChanged(h.bindingAdapterPosition); true
            }
            img.tag = p.id
            val cached = cache.get(p.id)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(null)
                val edge = resources.displayMetrics.widthPixels * 3 / 2 / 3 * 3 / 2
                loader.execute {
                    val bmp = Roll.thumbnail(this@GalleryActivity, p, edge.coerceIn(400, 900)) ?: return@execute
                    cache.put(p.id, bmp)
                    main.post { if (img.tag == p.id) img.setImageBitmap(bmp) }
                }
            }
        }
    }
}
