package app.flashcam

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The "direct flash at reference" look.
 *
 * Pipeline:
 *  1. downscale + soften (cheap-digicam softness)
 *  2. subject mask (ML Kit person segmentation, stands in for LiDAR depth)
 *  3. optional drag blur: background streaks, subject stays sharp (rear-curtain flash)
 *  4. flash falloff: subject pushed hot, background pulled down, vignette
 *  5. film tone curve: warm highlights, lifted/faded blacks, cream whites
 *  6. saturation (rolls off in blown highlights)
 *  7. halation + soft glow around hot spots
 *  8. grain
 */
class Look {

    data class Params(
        val outLongEdge: Int = 1800,
        val soften: Float = 0.62f,       // fraction of size to downsample to before upscaling
        val edgeGain: Float = 0.86f,     // exposure at frame edges (flash falloff)
        val centerGain: Float = 1.06f,   // exposure where the flash lands
        val vignette: Float = 0.38f,
        val saturation: Float = 1.14f,
        val halation: Float = 0.16f,
        val glow: Float = 0.04f,
        val grain: Float = 0.045f,
        val drag: Boolean = false,
    )

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    private val lutR = FloatArray(256)
    private val lutG = FloatArray(256)
    private val lutB = FloatArray(256)

    init {
        for (i in 0..255) {
            val x = i / 255f
            // gentle S-curve for contrast
            val s = x * x * (3f - 2f * x)
            val y = x + (s - x) * 0.60f
            // warm channel split: red lifted, blue pulled down
            var r = y.pow(0.94f)
            var g = y.pow(0.985f)
            var b = y.pow(1.05f) * 0.97f
            // faded film blacks + creamy (not pure white) whites
            r = 0.026f + r * (0.990f - 0.026f)
            g = 0.022f + g * (0.978f - 0.022f)
            b = 0.028f + b * (0.950f - 0.028f)
            lutR[i] = r
            lutG[i] = g
            lutB[i] = b
        }
    }

    fun process(input: Bitmap, p: Params, seed: Long): Bitmap {
        val rnd = Random(seed)

        // 1. size + soften
        val long = max(input.width, input.height)
        val scale = min(1f, p.outLongEdge.toFloat() / long)
        val w = max(1, (input.width * scale).roundToInt())
        val h = max(1, (input.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(
            input, max(1, (w * p.soften).roundToInt()), max(1, (h * p.soften).roundToInt()), true
        )
        val base = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== base) small.recycle()

        val px = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)

        // 2. subject mask, only used to keep the subject sharp in drag mode
        val mask = if (p.drag) subjectMask(base, w, h) else null
        base.recycle()

        // 3. drag blur
        if (mask != null) applyDrag(px, w, h, mask, rnd)

        // 4-6. flash falloff, tone, saturation
        val n = w * h
        val flash = FloatArray(n)
        for (y in 0 until h) {
            val cy = ((y + 0.5f) / h - 0.45f) / 0.45f
            for (x in 0 until w) {
                val cx = ((x + 0.5f) / w - 0.5f) / 0.40f
                flash[y * w + x] = exp(-(cx * cx + cy * cy))
            }
        }
        val rr = FloatArray(n)
        val gg = FloatArray(n)
        val bb = FloatArray(n)
        val luma = FloatArray(n)
        for (y in 0 until h) {
            val cy = (y + 0.5f) / h - 0.5f
            for (x in 0 until w) {
                val i = y * w + x
                val cx = (x + 0.5f) / w - 0.5f
                val d2 = (cx * cx + cy * cy) / 0.5f
                val vig = 1f - p.vignette * d2 * sqrt(d2)
                // smooth center-weighted flash falloff; no hard subject edges
                val gain = (p.edgeGain + (p.centerGain - p.edgeGain) * flash[i]) * vig

                val c = px[i]
                var r = ((c shr 16) and 255) / 255f * gain
                var g = ((c shr 8) and 255) / 255f * gain
                var b = (c and 255) / 255f * gain
                r = lutR[(r.coerceIn(0f, 1f) * 255f).toInt()]
                g = lutG[(g.coerceIn(0f, 1f) * 255f).toInt()]
                b = lutB[(b.coerceIn(0f, 1f) * 255f).toInt()]

                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val sat = p.saturation - 0.45f * smooth(0.72f, 1f, l)
                r = l + (r - l) * sat
                g = l + (g - l) * sat
                b = l + (b - l) * sat
                rr[i] = r; gg[i] = g; bb[i] = b
                luma[i] = l
            }
        }

        // 7. halation + glow
        val bright = FloatArray(n) { ((luma[it] - 0.78f) / 0.22f).coerceIn(0f, 1f) }
        val f = max(4, max(w, h) / 48)
        val hal = blurField(bright, w, h, f)
        val glow = blurField(luma, w, h, f)

        // 8. grain + pack
        val out = IntArray(n)
        var state = rnd.nextLong() or 1L
        for (i in 0 until n) {
            val hb = hal[i] * p.halation
            val gm = glow[i] * p.glow
            var r = rr[i] + hb + gm
            var g = gg[i] + hb * 0.45f + gm * 0.9f
            var b = bb[i] + hb * 0.18f + gm * 0.72f

            // xorshift triangle noise in [-1, 1]
            state = state xor (state shl 13); state = state xor (state ushr 7); state = state xor (state shl 17)
            val u1 = ((state ushr 40) and 0xFFFF).toFloat() / 65535f
            val u2 = ((state ushr 20) and 0xFFFF).toFloat() / 65535f
            val noise = u1 + u2 - 1f
            val amp = p.grain * (1f - 0.6f * min(1f, kotlin.math.abs(luma[i] - 0.45f) * 2f))
            r += noise * amp; g += noise * amp; b += noise * amp

            out[i] = (0xFF shl 24) or
                (to255(r) shl 16) or
                (to255(g) shl 8) or
                to255(b)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    // ---------- subject mask ----------

    private fun subjectMask(bmp: Bitmap, w: Int, h: Int): FloatArray {
        // radial "where the flash lands" falloff, slightly above center
        val radial = FloatArray(w * h)
        for (y in 0 until h) {
            val cy = ((y + 0.5f) / h - 0.45f) / 0.40f
            for (x in 0 until w) {
                val cx = ((x + 0.5f) / w - 0.5f) / 0.34f
                radial[y * w + x] = exp(-(cx * cx + cy * cy))
            }
        }

        val seg: FloatArray? = try {
            val m = Tasks.await(segmenter.process(InputImage.fromBitmap(bmp, 0)))
            val mw = m.width
            val mh = m.height
            val buf = m.buffer
            buf.rewind()
            val raw = FloatArray(mw * mh)
            buf.asFloatBuffer().get(raw)
            if (mw == w && mh == h) raw else {
                FloatArray(w * h) { i ->
                    val sx = ((i % w) * mw / w).coerceIn(0, mw - 1)
                    val sy = ((i / w) * mh / h).coerceIn(0, mh - 1)
                    raw[sy * mw + sx]
                }
            }
        } catch (t: Throwable) {
            null
        }

        var coverage = 0
        if (seg != null) for (v in seg) if (v > 0.5f) coverage++

        return if (seg == null || coverage < (w * h) / 100) {
            // nobody detected: gentle center-weighted flash
            FloatArray(w * h) { 0.55f + 0.45f * radial[it] }
        } else {
            val soft = blurField(seg, w, h, max(4, max(w, h) / 70))
            // light spill: background near the center still catches some flash
            FloatArray(w * h) { i ->
                val s = soft[i].coerceIn(0f, 1f)
                s + (1f - s) * 0.30f * radial[i]
            }
        }
    }

    // ---------- drag blur ----------

    private fun applyDrag(px: IntArray, w: Int, h: Int, mask: FloatArray, rnd: Random) {
        val ang = rnd.nextFloat() * 6.2831855f
        val len = 0.04f * max(w, h)
        val dx = cos(ang) * len
        val dy = sin(ang) * len
        val k = 10
        val out = IntArray(px.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0
                for (j in 0 until k) {
                    val t = j / (k - 1f)
                    val sx = (x + dx * t).toInt().coerceIn(0, w - 1)
                    val sy = (y + dy * t).toInt().coerceIn(0, h - 1)
                    val c = px[sy * w + sx]
                    sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
                }
                val i = y * w + x
                val s = mask[i].coerceIn(0f, 1f)
                val a = (1f - s) * 0.9f + s * 0.18f   // background streaks, faint ghost on subject
                val c = px[i]
                val r = mix(((c shr 16) and 255).toFloat(), sr / k.toFloat(), a)
                val g = mix(((c shr 8) and 255).toFloat(), sg / k.toFloat(), a)
                val b = mix((c and 255).toFloat(), sb / k.toFloat(), a)
                out[i] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
        System.arraycopy(out, 0, px, 0, px.size)
    }

    // ---------- helpers ----------

    /** Box-downsample by f, blur twice at low res, bilinear upsample. A cheap wide blur. */
    private fun blurField(src: FloatArray, w: Int, h: Int, f: Int): FloatArray {
        val sw = (w + f - 1) / f
        val sh = (h + f - 1) / f
        val small = FloatArray(sw * sh)
        val cnt = IntArray(sw * sh)
        for (y in 0 until h) {
            val row = (y / f) * sw
            for (x in 0 until w) {
                val i = row + x / f
                small[i] += src[y * w + x]
                cnt[i]++
            }
        }
        for (i in small.indices) if (cnt[i] > 0) small[i] /= cnt[i]
        repeat(2) { boxBlur3(small, sw, sh) }

        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, sw - 1)
                val tx = fx - x0
                val a = mix(small[y0 * sw + x0], small[y0 * sw + x1], tx)
                val b = mix(small[y1 * sw + x0], small[y1 * sw + x1], tx)
                out[y * w + x] = mix(a, b, ty)
            }
        }
        return out
    }

    private fun boxBlur3(a: FloatArray, w: Int, h: Int) {
        val src = a.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f; var c = 0
            for (oy in -1..1) {
                val yy = y + oy
                if (yy < 0 || yy >= h) continue
                for (ox in -1..1) {
                    val xx = x + ox
                    if (xx < 0 || xx >= w) continue
                    sum += src[yy * w + xx]; c++
                }
            }
            a[y * w + x] = sum / c
        }
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun to255(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
}
