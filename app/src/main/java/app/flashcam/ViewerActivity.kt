package app.flashcam

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.concurrent.Executors

/** Full-screen viewer: fluid swipe between frames, pinch-zoom, share / ♥ / delete / edit, burst loop. */
class ViewerActivity : ComponentActivity() {

    private val ORANGE = GalleryActivity.ORANGE
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private var photos: List<Roll.Photo> = emptyList()
    private var favOnly = false

    private lateinit var pager: ViewPager2
    private lateinit var numText: TextView
    private lateinit var heart: TextView
    private lateinit var loopBtn: TextView
    private lateinit var infoBtn: TextView
    private lateinit var infoPanel: LinearLayout
    private var showInfo = false
    private val adapter = PageAdapter()
    private lateinit var evRuler: EvRuler
    private lateinit var evBox: TextView
    private var holdEv = 0f

    private var looping = false
    private var loopFrames: List<Bitmap> = emptyList()
    private var loopIdx = 0
    private val loopTick = object : Runnable {
        override fun run() {
            if (!looping || loopFrames.isEmpty()) return
            currentImage()?.setImageBitmap(loopFrames[loopIdx % loopFrames.size])
            loopIdx++
            main.postDelayed(this, 170)
        }
    }

    private val deletePrompt = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload() }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favOnly = intent.getBooleanExtra(GalleryActivity.EXTRA_FAV_ONLY, false)
        val startIndex = intent.getIntExtra(GalleryActivity.EXTRA_INDEX, 0)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pager = ViewPager2(this).apply {
            adapter = this@ViewerActivity.adapter
            offscreenPageLimit = 1
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) { stopLoop(); updateBars() }
            })
        }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))
        evRuler = EvRuler(this).apply { range = 3f; visibility = View.GONE }  // left side, same as the camera
        root.addView(evRuler, FrameLayout.LayoutParams(dp(56), -1, Gravity.START).apply { topMargin = dp(90); bottomMargin = dp(140) })
        evBox = label("", ORANGE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xCC1E1E1E.toInt()); cornerRadius = dp(10).toFloat() }
            visibility = View.GONE
        }
        root.addView(evBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // top: CLOSE   001   EDIT
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }
        val close = label("CLOSE", ORANGE).apply { setOnClickListener { finish() } }
        numText = label("", 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        showInfo = getSharedPreferences("flashcam", MODE_PRIVATE).getBoolean("showInfo", false)
        infoBtn = label("INFO", Color.WHITE).apply {
            setPadding(0, 0, dp(22), 0)
            setOnClickListener {
                showInfo = !showInfo
                getSharedPreferences("flashcam", MODE_PRIVATE).edit().putBoolean("showInfo", showInfo).apply()
                updateBars()
            }
        }
        val edit = label("EDIT", Color.WHITE).apply {
            setOnClickListener {
                val p = current() ?: return@setOnClickListener
                stopLoop()
                startActivity(Intent(this@ViewerActivity, EditActivity::class.java).putExtra("uri", p.uri.toString()))
            }
        }
        top.addView(close, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(numText, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        right.addView(infoBtn); right.addView(edit)
        top.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // bottom: SHARE   ♥   DELETE   (+ LOOP for bursts)
        val bottomCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        loopBtn = label("▶ LOOP", Color.WHITE).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { if (looping) stopLoop() else startLoop() }
            visibility = View.GONE
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        val share = label("SHARE", Color.WHITE).apply {
            setOnClickListener { current()?.let { RollActions.share(this@ViewerActivity, listOf(it.uri)) } }
        }
        heart = label("♡", Color.WHITE).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setOnClickListener {
                val p = current() ?: return@setOnClickListener
                Roll.setFavorite(this@ViewerActivity, p.id, !Roll.isFavorite(this@ViewerActivity, p.id))
                updateBars()
            }
        }
        val del = label("DELETE", Color.WHITE).apply {
            gravity = Gravity.END
            setOnClickListener {
                val p = current() ?: return@setOnClickListener
                stopLoop()
                if (RollActions.delete(this@ViewerActivity, listOf(p.uri), deletePrompt)) reload()
            }
        }
        bottom.addView(share, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(heart, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(del, LinearLayout.LayoutParams(0, -2, 1f))
        infoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xD90A0A0A.toInt()); cornerRadius = dp(14).toFloat() }
            visibility = View.GONE
        }
        bottomCol.addView(infoPanel, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), 0, dp(14), dp(8)) })
        bottomCol.addView(loopBtn)
        bottomCol.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottomCol, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        loadList { pager.setCurrentItem(startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)), false); updateBars() }
    }

    override fun onResume() {
        super.onResume()
        if (photos.isNotEmpty()) reload()  // picks up edits
    }

    override fun onPause() {
        super.onPause()
        stopLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun loadList(then: () -> Unit) {
        loader.execute {
            var list = Roll.load(this)
            if (favOnly) { val f = Roll.favorites(this); list = list.filter { it.id in f } }
            main.post {
                photos = list
                adapter.notifyDataSetChanged()
                if (photos.isEmpty()) finish() else then()
            }
        }
    }

    private fun reload() {
        val keep = pager.currentItem
        loadList { pager.setCurrentItem(keep.coerceIn(0, photos.size - 1), false); updateBars() }
    }

    private fun current() = photos.getOrNull(pager.currentItem)

    private fun currentImage(): ZoomImageView? {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        return (rv.findViewHolderForAdapterPosition(pager.currentItem) as? PageHolder)?.img
    }

    private fun updateBars() {
        val p = current() ?: return
        numText.text = "%03d".format(p.number)
        val fav = Roll.isFavorite(this, p.id)
        heart.text = if (fav) "♥" else "♡"
        heart.setTextColor(if (fav) ORANGE else Color.WHITE)
        loopBtn.visibility = if (p.burstKey != null) View.VISIBLE else View.GONE
        loopBtn.text = if (looping) "■ STOP" else "▶ LOOP"
        infoBtn.setTextColor(if (showInfo) ORANGE else Color.WHITE)
        if (showInfo) loadInfo(p) else infoPanel.visibility = View.GONE
    }

    private fun loadInfo(p: Roll.Photo) {
        loader.execute {
            val meta = PhotoMeta.read(this, p.uri)
            main.post {
                if (current()?.id != p.id || !showInfo) return@post
                infoPanel.removeAllViews()
                val rows = meta?.let { PhotoMeta.rows(it) } ?: emptyList()
                if (rows.isEmpty()) {
                    infoPanel.addView(small("NO SHOT INFO · TAKEN BEFORE v0.18", 0x80FFFFFF.toInt()))
                } else {
                    // two columns of KEY  value
                    rows.chunked(2).forEach { pair ->
                        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
                        pair.forEach { (k, v) ->
                            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                            cell.addView(small(k, 0x73FFFFFF))
                            cell.addView(small(v, Color.WHITE).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
                            line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                        }
                        if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                        infoPanel.addView(line)
                    }
                }
                infoPanel.visibility = View.VISIBLE
            }
        }
    }

    private fun small(text: String, color: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextColor(color)
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.1f
    }

    // ---------- hold + slide to change exposure ----------

    private fun holdExposure(img: ZoomImageView, phase: Int, dy: Float) {
        when (phase) {
            0 -> {
                stopLoop()
                holdEv = 0f
                evRuler.ev = 0f; evRuler.visibility = View.VISIBLE
                evBox.text = "EXP\n+0.0 EV"; evBox.visibility = View.VISIBLE
            }
            1 -> {
                // full screen height = 6 EV (-3..+3)
                val e = (Math.round(dy / pager.height * 6f * 10f) / 10f).coerceIn(-3f, 3f)
                if (e != holdEv) {
                    holdEv = e
                    Haptics.tick(img)
                    evRuler.ev = e
                    evBox.text = "EXP\n" + (if (e > 0) "+" else "") + "%.1f EV".format(e)
                    img.colorFilter = if (e == 0f) null else android.graphics.ColorMatrixColorFilter(PhotoEdits.matrix(e))
                }
            }
            2 -> {
                evRuler.visibility = View.GONE
                evBox.visibility = View.GONE
                val p = current() ?: return
                val e = holdEv
                if (e == 0f) { img.colorFilter = null; return }
                loader.execute {
                    val ok = PhotoEdits.save(this, p.uri, PhotoEdits.matrix(e))
                    main.post {
                        if (!ok) android.widget.Toast.makeText(this, "Couldn't save exposure", android.widget.Toast.LENGTH_SHORT).show()
                        img.colorFilter = null
                        adapter.notifyItemChanged(pager.currentItem)
                    }
                }
            }
        }
    }

    // ---------- burst loop (flipbook of the 5 frames) ----------

    private fun startLoop() {
        val key = current()?.burstKey ?: return
        val frames = photos.filter { it.burstKey == key }.sortedBy { it.name }
        if (frames.size < 2) return
        looping = true; updateBars()
        val edge = resources.displayMetrics.heightPixels
        loader.execute {
            val bmps = frames.mapNotNull { Roll.decodeSampled(this, it.uri, edge) }
            main.post {
                if (!looping) return@post
                loopFrames = bmps; loopIdx = 0
                main.post(loopTick)
            }
        }
    }

    private fun stopLoop() {
        if (!looping && loopFrames.isEmpty()) return
        looping = false
        main.removeCallbacks(loopTick)
        loopFrames = emptyList()
        updateBars()
        adapter.notifyItemChanged(pager.currentItem)  // restore the still frame
    }

    private fun label(text: String, color: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.14f
    }

    private inner class PageHolder(val img: ZoomImageView) : RecyclerView.ViewHolder(img)

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = photos.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val v = ZoomImageView(this@ViewerActivity).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
            v.onHoldDrag = { phase, dy -> holdExposure(v, phase, dy) }
            return PageHolder(v)
        }
        override fun onBindViewHolder(h: PageHolder, position: Int) {
            val p = photos[position]
            h.img.tag = p.id
            h.img.setImageDrawable(null)
            loader.execute {
                val bmp = Roll.decodeSampled(this@ViewerActivity, p.uri, 2400) ?: return@execute
                main.post { if (h.img.tag == p.id) h.img.setImageBitmap(bmp) }
            }
        }
    }
}
