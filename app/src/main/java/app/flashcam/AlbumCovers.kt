package app.flashcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import java.util.concurrent.Executors

/**
 * Shared in-memory thumbnails for album covers. Nothing is written to disk: covers are decoded
 * small from the original photos (Android's own thumbnail cache when available) and kept in RAM.
 */
object Thumbs {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    fun get(id: Long): Bitmap? = cache.get(id)

    fun load(ctx: Context, p: Roll.Photo, edge: Int = 360, done: (Bitmap) -> Unit) {
        cache.get(p.id)?.let { done(it); return }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = try {
                if (android.os.Build.VERSION.SDK_INT >= 29)
                    app.contentResolver.loadThumbnail(p.uri, android.util.Size(edge * 2 / 3, edge), null)
                else null
            } catch (_: Throwable) { null } ?: Roll.thumbnail(app, p, edge) ?: return@execute
            cache.put(p.id, bmp)
            main.post { done(bmp) }
        }
    }
}

private fun centerCrop(c: Canvas, b: Bitmap, dst: RectF, paint: Paint) {
    val s = maxOf(dst.width() / b.width, dst.height() / b.height)
    val w = dst.width() / s; val h = dst.height() / s
    val l = (b.width - w) / 2f; val t = (b.height - h) / 2f
    c.save(); c.clipRect(dst)
    c.drawBitmap(b, android.graphics.Rect(l.toInt(), t.toInt(), (l + w).toInt(), (t + h).toInt()), dst, paint)
    c.restore()
}

/** Style A: a small fanned stack of prints, like an envelope of photos back from the lab. */
class StackCover(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1EADF.toInt() }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val empty = Paint().apply { color = 0xFF1C1C1C.toInt() }
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var photos: List<Roll.Photo> = emptyList()
    private val bmps = HashMap<Long, Bitmap>()
    private var angles = floatArrayOf(-11f, 8f, 1.5f)
    private var spread = 0f   // 0..1, animates the fan opening when tapped
    private var key = ""

    fun bind(album: Albums.Album) {
        key = album.id
        photos = album.covers(3).reversed()   // newest on top
        val h = album.id.hashCode()
        angles = floatArrayOf(-8f - (h and 7), 5f + ((h shr 3) and 7), ((h shr 6) and 5) - 2f)
        spread = 0f
        bmps.clear()
        photos.forEach { p ->
            Thumbs.get(p.id)?.let { bmps[p.id] = it } ?: Thumbs.load(context, p) { b -> if (key == album.id) { bmps[p.id] = b; invalidate() } }
        }
        invalidate()
    }

    /** Fan the prints open, then run [then]. */
    fun open(then: () -> Unit) {
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { spread = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { then(); postDelayed({ spread = 0f; invalidate() }, 400) }
            })
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width * 1.05f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val pw = width * 0.52f; val ph = pw * 1.5f
        val border = 3.5f * d
        val n = photos.size
        photos.forEachIndexed { i, p ->
            val a = angles[(i + 3 - n).coerceIn(0, 2)] * (1f + spread * 0.9f)
            val dx = (i - (n - 1) / 2f) * spread * pw * 0.28f
            c.save()
            c.translate(cx + dx, cy)
            c.rotate(a)
            val r = RectF(-pw / 2, -ph / 2, pw / 2, ph / 2)
            c.drawRect(RectF(r.left + 1.5f * d, r.top + 2.5f * d, r.right + 1.5f * d, r.bottom + 2.5f * d), shade)
            c.drawRect(r, paper)
            val img = RectF(r.left + border, r.top + border, r.right - border, r.bottom - border * 3.2f)
            val b = bmps[p.id]
            if (b != null) centerCrop(c, b, img, bp) else c.drawRect(img, empty)
            c.restore()
        }
        if (n == 0) {
            c.drawRect(RectF(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2), empty)
        }
    }
}

/** Style B: a strip of film with four frames from the album. */
class StripCover(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val base = Paint().apply { color = 0xFF1B140F.toInt() }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val empty = Paint().apply { color = 0xFF262019.toInt() }
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var photos: List<Roll.Photo> = emptyList()
    private val bmps = HashMap<Long, Bitmap>()
    private var key = ""

    fun bind(album: Albums.Album) {
        key = album.id
        photos = album.covers(4)
        bmps.clear()
        photos.forEach { p ->
            Thumbs.get(p.id)?.let { bmps[p.id] = it } ?: Thumbs.load(context, p) { b -> if (key == album.id) { bmps[p.id] = b; invalidate() } }
        }
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        // four 2:3 frames across, plus the sprocket rails
        val frameW = (width - 5 * 4 * d) / 4f
        setMeasuredDimension(width, (frameW * 1.5f + 2 * 11 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rail = 11 * d
        c.drawRect(0f, 0f, w, h, base)
        // sprocket holes
        val hw = 5 * d; val hh = 4 * d; val step = 13 * d
        var x = 4 * d
        while (x + hw < w) {
            c.drawRoundRect(RectF(x, (rail - hh) / 2, x + hw, (rail + hh) / 2), 1 * d, 1 * d, hole)
            c.drawRoundRect(RectF(x, h - (rail + hh) / 2, x + hw, h - (rail - hh) / 2), 1 * d, 1 * d, hole)
            x += step
        }
        val gap = 4 * d
        val fw = (w - 5 * gap) / 4f
        for (i in 0 until 4) {
            val l = gap + i * (fw + gap)
            val r = RectF(l, rail, l + fw, h - rail)
            val p = photos.getOrNull(i)
            val b = p?.let { bmps[it.id] }
            if (b != null) centerCrop(c, b, r, bp) else c.drawRect(r, empty)
        }
    }

    @Suppress("unused") private val m = Matrix()
}
