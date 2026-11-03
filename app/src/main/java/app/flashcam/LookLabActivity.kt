package app.flashcam

import android.content.ContentUris
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hidden tuning tool. Pick a debug original, drag sliders, see the developed result live.
 * Hold the photo to see the untouched original. SAVE makes it the look for new shots in that mode.
 */
class LookLabActivity : ComponentActivity() {

    private val ORANGE = GalleryActivity.ORANGE
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val look = Look()
    private val version = AtomicInteger(0)

    private data class Orig(val uri: Uri, val name: String) { val night get() = !name.endsWith("_PM.jpg") }

    private var originals: List<Orig> = emptyList()
    private var source: Bitmap? = null
    private var mode = Look.Mode.AM
    private var params = Look.Params()
    private var showingOriginal = false

    private lateinit var preview: ImageView
    private lateinit var tabNight: TextView
    private lateinit var tabDay: TextView
    private lateinit var statusText: TextView
    private lateinit var strip: LinearLayout
    private lateinit var sliderBox: LinearLayout
    private val sliders = ArrayList<Triple<LookSettings.Knob, SeekBar, TextView>>()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        // top: CLOSE   NIGHT DAY   SAVE
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(10))
        }
        top.addView(label("CLOSE", ORANGE, 14f).apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, -2, 1f))
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        tabNight = label("NIGHT", Color.WHITE, 14f).apply { setPadding(dp(8), 0, dp(10), 0); setOnClickListener { setMode(Look.Mode.AM, true) } }
        tabDay = label("DAY", Color.WHITE, 14f).apply { setPadding(dp(10), 0, dp(8), 0); setOnClickListener { setMode(Look.Mode.PM, true) } }
        tabs.addView(tabNight); tabs.addView(tabDay)
        top.addView(tabs, LinearLayout.LayoutParams(0, -2, 1.4f))
        top.addView(label("SAVE", ORANGE, 14f).apply { gravity = Gravity.END; setOnClickListener { saveLook() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)

        statusText = label("", 0x80FFFFFF.toInt(), 10f).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        // preview (hold = original)
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { showingOriginal = true; source?.let { setImageBitmap(it) }; statusText.text = "ORIGINAL (UNPROCESSED)" }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { showingOriginal = false; updateStatus(); render() }
                }
                true
            }
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1.1f))

        // originals strip
        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(8), dp(14), dp(4)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })

        // sliders
        sliderBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(8)) }
        root.addView(ScrollView(this).apply { addView(sliderBox) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // bottom: RESET TO DEFAULTS · REVERT TO SAVED · COPY
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        bottom.addView(button("RESET TO DEFAULTS") { params = LookSettings.builtIn(mode); syncSliders(); render(); toast("Showing the built-in look (not saved)") },
            LinearLayout.LayoutParams(0, -2, 1.3f).apply { rightMargin = dp(6) })
        bottom.addView(button("REVERT") { params = LookSettings.params(this, mode); syncSliders(); render() },
            LinearLayout.LayoutParams(0, -2, 0.8f).apply { rightMargin = dp(6) })
        bottom.addView(button("COPY") { copySettings() }, LinearLayout.LayoutParams(0, -2, 0.8f))
        root.addView(bottom)

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }

        buildSliders()
        setMode(Look.Mode.AM, false)
        worker.execute {
            val list = loadOriginals()
            main.post {
                originals = list
                buildStrip()
                if (list.isEmpty()) statusText.text = "NO ORIGINALS YET · TURN ON DEBUG (HOLD THE ROLL COUNTER) AND SHOOT"
                else pick(list[0])
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    // ---------- data ----------

    private fun loadOriginals(): List<Orig> {
        val out = ArrayList<Orig>()
        val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val (sel, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?" to arrayOf(Paths.ORIGINALS_REL)
        else @Suppress("DEPRECATION") ("${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/Overnight/Originals/%"))
        try {
            contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME), sel, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 40) {
                    out.add(Orig(ContentUris.withAppendedId(col, c.getLong(0)), c.getString(1) ?: ""))
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun pick(o: Orig) {
        worker.execute {
            val bmp = Roll.decodeSampled(this, o.uri, 1000) ?: return@execute
            main.post {
                source = bmp
                setMode(if (o.night) Look.Mode.AM else Look.Mode.PM, false)
            }
        }
    }

    // ---------- UI ----------

    private fun setMode(m: Look.Mode, userTap: Boolean) {
        mode = m
        params = LookSettings.params(this, m)
        tabNight.setTextColor(if (m == Look.Mode.AM) ORANGE else 0x66FFFFFF)
        tabDay.setTextColor(if (m == Look.Mode.PM) ORANGE else 0x66FFFFFF)
        syncSliders()
        render()
        if (userTap && source != null) toast(if (m == Look.Mode.AM) "Previewing NIGHT look" else "Previewing DAY look")
    }

    private fun buildStrip() {
        strip.removeAllViews()
        for (o in originals) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF1A1A1A.toInt())
                setOnClickListener { pick(o) }
            }
            strip.addView(iv, LinearLayout.LayoutParams(dp(46), dp(62)).apply { rightMargin = dp(6) })
            worker.execute {
                val t = Roll.decodeSampled(this, o.uri, 160)
                main.post { iv.setImageBitmap(t) }
            }
        }
    }

    private fun buildSliders() {
        for (k in LookSettings.knobs) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            val name = label(k.label, Color.WHITE, 11f)
            val value = label("", ORANGE, 11f).apply { gravity = Gravity.END }
            val bar = TickSeekBar(this).apply {
                max = 1000
                progressTintList = ColorStateList.valueOf(0x33FFFFFF)
                progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
                thumbTintList = ColorStateList.valueOf(ORANGE)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, prog: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val notch = prog / 50
                        if (s.tag != notch) { s.tag = notch; Haptics.tick(s) }
                        val v = k.min + (k.max - k.min) * prog / 1000f
                        params = k.set(params, v)
                        value.text = valueText(k, v)
                        scheduleRender()
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) { render() }
                })
            }
            row.addView(name, LinearLayout.LayoutParams(dp(104), -2))
            row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(value, LinearLayout.LayoutParams(dp(72), -2))
            sliderBox.addView(row)
            sliders.add(Triple(k, bar, value))
        }
    }

    private fun syncSliders() {
        for ((k, bar, value) in sliders) {
            val v = k.get(params)
            bar.progress = (((v - k.min) / (k.max - k.min)) * 1000f).toInt().coerceIn(0, 1000)
            (bar as? TickSeekBar)?.tick = ((k.get(LookSettings.builtIn(mode)) - k.min) / (k.max - k.min)).coerceIn(0f, 1f)
            value.text = valueText(k, v)
            (bar.parent as View).visibility = if (k.nightOnly && mode != Look.Mode.AM) View.GONE else View.VISIBLE
        }
        updateStatus()
    }

    /** Value label, with a dot when it differs from the built-in look. */
    private fun valueText(k: LookSettings.Knob, v: Float): String {
        val changed = kotlin.math.abs(v - k.get(LookSettings.builtIn(mode))) > (k.max - k.min) * 0.004f
        return k.fmt(v) + if (changed) " •" else ""
    }

    private fun updateStatus() {
        if (source == null && originals.isEmpty()) return
        statusText.text = (if (mode == Look.Mode.AM) "NIGHT" else "DAY") + " · " +
            (if (LookSettings.isCustom(this, mode)) "USING YOUR SAVED LOOK" else "USING BUILT-IN LOOK") + " · • = CHANGED FROM BUILT-IN"
    }

    private val renderRunnable = Runnable { render() }
    private fun scheduleRender() { main.removeCallbacks(renderRunnable); main.postDelayed(renderRunnable, 90) }

    private fun render() {
        val src = source ?: return
        if (showingOriginal) return
        val v = version.incrementAndGet()
        val p = params.copy(outLongEdge = 900, drag = false)
        worker.execute {
            if (v != version.get()) return@execute
            val out = try { look.process(src, p, 1234L) } catch (_: Throwable) { null }
            main.post { if (v == version.get() && !showingOriginal && out != null) preview.setImageBitmap(out) }
        }
    }

    private fun saveLook() {
        LookSettings.save(this, mode, params)
        syncSliders()
        toast((if (mode == Look.Mode.AM) "NIGHT" else "DAY") + " look saved · used for new shots")
    }

    private fun copySettings() {
        val text = LookSettings.export(mode, params)
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Overnight look", text))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share look settings"))
    }

    private fun button(text: String, onTap: () -> Unit) = label(text, Color.WHITE, 11f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(12), dp(4), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x14FFFFFF) }
        setOnClickListener { onTap() }
    }

    private fun label(text: String, color: Int, sp: Float) = Ui.text(this, text, Ui.scaled(sp), color)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Suppress("unused") private fun unused(g: ViewGroup, f: FrameLayout) = g to f
}
