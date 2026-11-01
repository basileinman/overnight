package app.flashcam

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/** Full-screen frame viewer. Swipe left/right to move through the roll. */
class ViewerActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var photos: List<Roll.Photo> = emptyList()
    private var index = 0
    private lateinit var image: ZoomImageView
    private lateinit var numText: TextView

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = intent.getIntExtra("index", 0)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ZoomImageView(this)
        root.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val back = label("‹ ROLL", Color.WHITE).apply { setOnClickListener { finish() } }
        numText = label("", 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        val share = label("SHARE", 0xFFFF7A1F.toInt()).apply { setOnClickListener { share() } }
        bar.addView(back, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(numText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(share, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { })
        share.gravity = Gravity.END
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // swipe left = older frame (list is newest first); only when not zoomed in
        image.onSwipe = { dir -> show(if (dir < 0) index + 1 else index - 1) }

        loader.execute {
            val list = Roll.load(this)
            main.post {
                photos = list
                show(index)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun label(text: String, color: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.12f
    }

    private fun show(i: Int) {
        if (photos.isEmpty()) { finish(); return }
        index = i.coerceIn(0, photos.size - 1)
        val p = photos[index]
        numText.text = "%03d".format(p.number)
        val edge = 2400  // full photo resolution so zooming stays sharp
        loader.execute {
            val bmp = Roll.decodeSampled(this, p.uri, edge)
            main.post { if (photos.getOrNull(index)?.id == p.id) image.setImageBitmap(bmp) }
        }
    }

    private fun share() {
        val p = photos.getOrNull(index) ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, p.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share photo"))
    }
}
