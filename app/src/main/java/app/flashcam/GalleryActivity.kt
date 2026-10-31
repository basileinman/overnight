package app.flashcam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

/** Film-roll grid: three across, 2:3 frames, numbered like negatives. */
class GalleryActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private var photos: List<Roll.Photo> = emptyList()
    private lateinit var grid: GridView
    private lateinit var countText: TextView
    private val adapter = Adapter()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val title = mono("ROLL", 15f, Color.WHITE).apply { setTypeface(Typeface.MONOSPACE, Typeface.BOLD) }
        countText = mono("", 12f, 0x80FFFFFF.toInt()).apply { setPadding(dp(12), 0, 0, 0) }
        val spacer = View(this)
        val done = mono("DONE", 14f, 0xFFFF7A1F.toInt()).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(12), dp(6), 0, dp(6))
            setOnClickListener { finish() }
        }
        header.addView(title)
        header.addView(countText)
        header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(done)

        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(6)
            verticalSpacing = dp(10)
            setPadding(dp(14), dp(4), dp(14), dp(14))
            clipToPadding = false
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = this@GalleryActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                startActivity(Intent(this@GalleryActivity, ViewerActivity::class.java).putExtra("index", pos))
            }
        }

        root.addView(header)
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        loader.execute {
            val list = Roll.load(this)
            main.post {
                photos = list
                countText.text = "%03d FRAMES".format(list.size)
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun mono(text: String, sp: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
    }

    /** ImageView that is always 2:3 tall. */
    private class FrameImage(ctx: Context) : ImageView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, w * 3 / 2)
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = photos[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.VERTICAL
                val img = FrameImage(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFF161616.toInt())
                    tag = "img"
                }
                val num = mono("", 11f, 0x99FFFFFF.toInt()).apply {
                    setPadding(dp(2), dp(4), 0, 0)
                    tag = "num"
                }
                addView(img, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(num)
            }
            val p = photos[position]
            val img = cell.findViewWithTag<ImageView>("img")
            val num = cell.findViewWithTag<TextView>("num")
            num.text = "%03d".format(p.number)
            img.setTag(R.id.photo_id, p.id)
            val cached = cache.get(p.id)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(null)
                loader.execute {
                    val bmp = Roll.thumbnail(this@GalleryActivity, p, 360) ?: return@execute
                    cache.put(p.id, bmp)
                    main.post { if (img.getTag(R.id.photo_id) == p.id) img.setImageBitmap(bmp) }
                }
            }
            return cell
        }
    }
}
