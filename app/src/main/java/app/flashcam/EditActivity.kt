package app.flashcam

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.pow

/** Exposure / white balance / contrast. Live preview via a color filter, so it's instant. */
class EditActivity : ComponentActivity() {

    private val ORANGE = GalleryActivity.ORANGE
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var uri: Uri
    private lateinit var preview: ImageView

    private var ev = 0f          // -2..+2
    private var wb = 0f          // -1 (cool) .. +1 (warm)
    private var contrast = 0f    // -1..+1
    private val bars = ArrayList<Triple<SeekBar, TextView, Int>>()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = Uri.parse(intent.getStringExtra("uri") ?: run { finish(); return })

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }
        top.addView(label("CANCEL", ORANGE, 15f).apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("EDIT", Color.WHITE, 15f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("SAVE", ORANGE, 15f).apply { gravity = Gravity.END; setOnClickListener { save() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)

        val imgBox = FrameLayout(this)
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
        imgBox.addView(preview, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(40), dp(6), dp(40), dp(6)) })
        root.addView(imgBox, LinearLayout.LayoutParams(-1, 0, 1f))

        val reset = label("RESET", ORANGE, 14f).apply {
            gravity = Gravity.END; setPadding(dp(22), dp(10), dp(28), dp(6))
            setOnClickListener { ev = 0f; wb = 0f; contrast = 0f; bars.forEach { it.first.progress = it.third }; apply() }
        }
        root.addView(reset, LinearLayout.LayoutParams(-1, -2))
        root.addView(slider("EXPOSURE", 40, 20) { p, t -> ev = (p - 20) / 10f; t.text = (if (ev >= 0) "+" else "") + "%.1f EV".format(ev) })
        root.addView(slider("WHITE BALANCE", 100, 50) { p, t ->
            wb = (p - 50) / 50f
            t.text = when { p == 50 -> "ORIGINAL"; wb > 0 -> "WARM +%d".format(p - 50); else -> "COOL %d".format(p - 50) }
        })
        root.addView(slider("CONTRAST", 100, 50) { p, t -> contrast = (p - 50) / 50f; t.text = "%d".format(p - 50) })

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom + dp(10)); insets
        }

        worker.execute {
            val bmp = Roll.decodeSampled(this, uri, 1600)
            main.post { if (bmp == null) finish() else { preview.setImageBitmap(bmp); apply() } }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun slider(name: String, max: Int, start: Int, onChange: (Int, TextView) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(18))
        }
        val title = label(name, Color.WHITE, 13f)
        val value = label("", ORANGE, 13f).apply { gravity = Gravity.END }
        val bar = SeekBar(this).apply {
            this.max = max; progress = start
            progressTintList = ColorStateList.valueOf(0x33FFFFFF)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            thumbTintList = ColorStateList.valueOf(ORANGE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { onChange(p, value); apply() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        onChange(start, value)
        bars.add(Triple(bar, value, start))
        row.addView(title, LinearLayout.LayoutParams(dp(130), -2))
        row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value, LinearLayout.LayoutParams(dp(90), -2))
        return row
    }

    private fun matrix(): ColorMatrix {
        val m = ColorMatrix()
        val g = 2f.pow(ev * 0.6f)  // perceptual-ish exposure in display space
        m.setScale(g * (1f + 0.14f * wb), g * (1f + 0.02f * wb), g * (1f - 0.16f * wb), 1f)
        val c = 1f + 0.6f * contrast
        val o = 128f * (1f - c)
        m.postConcat(ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, o,
            0f, c, 0f, 0f, o,
            0f, 0f, c, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )))
        return m
    }

    private fun apply() {
        preview.colorFilter = if (ev == 0f && wb == 0f && contrast == 0f) null else ColorMatrixColorFilter(matrix())
    }

    private fun save() {
        if (ev == 0f && wb == 0f && contrast == 0f) { finish(); return }
        val m = matrix()
        worker.execute {
            try {
                val src = Roll.decodeSampled(this, uri, 4000) ?: throw IllegalStateException("decode")
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) })
                src.recycle()
                val wrote = try {
                    contentResolver.openOutputStream(uri, "wt")?.use { out.compress(Bitmap.CompressFormat.JPEG, 94, it) } == true
                } catch (_: Throwable) { false }
                if (!wrote) saveCopy(out)
                out.recycle()
                main.post { finish() }
            } catch (t: Throwable) {
                main.post { Toast.makeText(this, "Couldn't save edit", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** Fallback when the original can't be overwritten: save the edit as a new frame. */
    private fun saveCopy(bmp: Bitmap) {
        val name = "FLASH_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_edit.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FlashCam")
        }
        val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(u)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) }
    }

    private fun label(text: String, color: Int, sp: Float) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.14f
    }
}
