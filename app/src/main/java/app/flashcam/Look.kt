package app.flashcam

import android.graphics.Bitmap
import kotlin.math.abs
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
 * Film flash look, calibrated against reference cam reference shots.
 *
 *  1. crop to 2:3 (35mm frame) and resize
 *  2. slight soften to kill phone over-sharpening
 *  3. optional drag blur (background streaks, subject sharp)
 *  4. film tone curve: green-tinged ~6% blacks, warm mids, cream highlights
 *  5. saturation (rolls off in highlights)
 *  6. star filter on small point lights
 *  7. faint halation on the brightest spots
 *  8. fine grain
 *
 * Real flash falloff comes from the camera underexposing, not from this code.
 */
class Look {

    companion object {
        private const val WB_TARGET_R = 1.203f
        private const val WB_TARGET_B = 0.899f
        private val NIGHT_TONE = intArrayOf(18, 22, 27, 32, 36, 41, 45, 48, 51, 54, 56, 58, 60, 61, 62, 63, 64, 65, 66, 67, 68, 70, 71, 72, 74, 75, 77, 79, 80, 82, 84, 86, 88, 89, 91, 93, 95, 97, 99, 101, 103, 106, 108, 111, 113, 116, 119, 122, 126, 129, 133, 137, 141, 145, 149, 153, 157, 161, 166, 170, 174, 178, 182, 186, 190, 194, 198, 202, 206, 210, 214, 219, 223, 227, 232, 236, 241, 245, 250, 255, 260, 265, 270, 276, 282, 288, 295, 301, 309, 316, 324, 333, 341, 350, 359, 368, 378, 387, 397, 406, 416, 425, 434, 443, 453, 462, 471, 480, 488, 497, 505, 513, 522, 529, 537, 544, 551, 558, 565, 572, 578, 584, 591, 596, 602, 608, 613, 619, 624, 629, 634, 638, 643, 648, 652, 656, 661, 665, 669, 673, 677, 680, 684, 688, 691, 694, 698, 701, 704, 707, 711, 714, 717, 720, 723, 727, 730, 733, 736, 739, 743, 746, 749, 752, 756, 759, 762, 766, 769, 772, 776, 779, 783, 786, 790, 794, 798, 802, 806, 810, 815, 820, 824, 829, 834, 839, 843, 848, 853, 858, 862, 867, 871, 875, 879, 882, 886, 889, 892, 895, 899, 902, 905, 907, 910, 913, 916, 919, 921, 924, 927, 929, 932, 934, 936, 939, 941, 943, 945, 947, 949, 951, 953, 955, 957, 959, 961, 963, 966, 968, 970, 973, 975, 978, 980, 982, 983, 985, 986, 987, 988, 989, 989, 990, 991, 991, 992, 993, 994, 994, 995, 996, 997, 997, 998, 999)
    }

    enum class Mode { AM, PM }

    data class Params(
        val mode: Mode = Mode.AM,
        val drag: Boolean = false,
        val outLongEdge: Int = 2352,
        val soften: Float = 0.62f,
        val vignette: Float = 0.12f,
        val saturation: Float = 1.0f,
        val halation: Float = 0.10f,
        val stars: Float = 0.45f,
        val glow: Float = 0.0f,
        val grain: Float = 0.065f,
        // Look Lab adjustments (0 = built-in look)
        val exposure: Float = 0f,     // EV
        val warmth: Float = 0f,       // -1 cool .. +1 warm
        val tint: Float = 0f,         // -1 green .. +1 magenta
        val contrast: Float = 0f,     // -1 .. +1 on top of the curve
        val fade: Float = 0f,         // 0 .. 0.15 lifted blacks
        val grainSize: Float = 1f,    // 1 fine .. 3 coarse
        val wbStrength: Float = 0.6f, // NIGHT auto white balance: 0 off .. 1 full
    ) {
        companion object {
            fun forMode(mode: Mode, drag: Boolean) = when (mode) {
                Mode.AM -> Params(mode = mode, drag = drag, soften = 0.9f, saturation = 0.95f)
                Mode.PM -> Params(
                    mode = mode, drag = drag,
                    saturation = 1.15f, halation = 0.06f, stars = 0f, grain = 0.055f, vignette = 0.12f,
                    glow = 0f, soften = 0.78f,
                )
            }
        }
    }

    private class Lut(val r: FloatArray, val g: FloatArray, val b: FloatArray)

    // NIGHT curves fitted jointly from three exposure-matched pairs (Pixel original -> reference cam shot)
    // NIGHT: one tone curve for all channels (matched to reference party shots) + adaptive white balance per photo
    private val nightTone = FloatArray(256) { NIGHT_TONE[it] / 1000f }
    private val toLin = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }
    private fun toSrgb(v: Float): Float { val c = v.coerceIn(0f, 1f); return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f }

    /** Pulls each photo partway toward reference's mildly warm neutral, then applies the NIGHT tone curve. */
    private fun nightLut(px: IntArray, p: Params): Lut {
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
        var i = 0
        while (i < px.size) {
            val c = px[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            val sat = (max(r, max(g, b)) - min(r, min(g, b))) / 255f
            if (l > 0.12f && l < 0.85f && sat < 0.25f) { sr += toLin[r]; sg += toLin[g]; sb += toLin[b]; n++ }
            i += 7
        }
        var gr = 1f; var gb = 1f
        if (n > 300 && sg > 0) {
            val mr = (sr / sg).toFloat(); val mb = (sb / sg).toFloat()
            gr = (WB_TARGET_R / mr).pow(p.wbStrength).coerceIn(0.6f, 1.7f)
            gb = (WB_TARGET_B / mb).pow(p.wbStrength).coerceIn(0.6f, 1.7f)
        }
        return composed(nightTone, nightTone, nightTone, gr, 1f, gb, p)
    }

    /**
     * Final per-channel LUT: input gain in linear light (exposure, warmth, tint, auto-WB),
     * then the mode's tone curve, then Look Lab contrast + fade.
     */
    private fun composed(tr: FloatArray, tg: FloatArray, tb: FloatArray, wr: Float, wg: Float, wb: Float, p: Params): Lut {
        val e = 2f.pow(p.exposure)
        val gR = wr * e * (1f + 0.15f * p.warmth) * (1f + 0.04f * p.tint)
        val gG = wg * e * (1f - 0.10f * p.tint)
        val gB = wb * e * (1f - 0.17f * p.warmth) * (1f + 0.04f * p.tint)
        fun post(y: Float): Float {
            val s = y * y * (3f - 2f * y)
            val c = (y + (s - y) * p.contrast).coerceIn(0f, 1f)
            return p.fade + c * (1f - p.fade)
        }
        fun ch(tone: FloatArray, gain: Float) = FloatArray(256) { v ->
            post(tone[(toSrgb(toLin[v] * gain) * 255f + 0.5f).toInt().coerceIn(0, 255)])
        }
        return Lut(ch(tr, gR), ch(tg, gG), ch(tb, gB))
    }
    private val lutPm = buildLut(
        // DAY: tuned against 7 matched DAY pairs: deep blacks, punchy contrast, yellow-green greys
        contrast = 0.95f,
        black = floatArrayOf(0.018f, 0.022f, 0.022f),
        white = floatArrayOf(0.985f, 0.985f, 0.940f),
        gamma = floatArrayOf(1.04f, 0.98f, 1.025f),
    )

    private fun buildLut(contrast: Float, black: FloatArray, white: FloatArray, gamma: FloatArray): Lut {
        val ch = Array(3) { FloatArray(256) }
        for (i in 0..255) {
            val x = i / 255f
            val s = x * x * (3f - 2f * x)
            val y = x + (s - x) * contrast
            for (c in 0..2) {
                ch[c][i] = black[c] + (white[c] - black[c]) * y.pow(gamma[c])
            }
        }
        return Lut(ch[0], ch[1], ch[2])
    }

    fun process(input: Bitmap, p: Params, seed: Long): Bitmap {
        val rnd = Random(seed)

        // 1. crop to 2:3
        val cropped = cropTo2x3(input)

        // 2. resize + slight soften
        val long = max(cropped.width, cropped.height)
        val scale = min(1f, p.outLongEdge.toFloat() / long)
        val w = max(1, (cropped.width * scale).roundToInt())
        val h = max(1, (cropped.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(
            cropped, max(1, (w * p.soften).roundToInt()), max(1, (h * p.soften).roundToInt()), true
        )
        if (cropped !== input && cropped !== small) cropped.recycle()
        val base = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== base) small.recycle()

        val px = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)

        // 3. drag blur (mask only used here)
        if (p.drag) {
            val mask = subjectMask(w, h)
            applyDrag(px, w, h, mask, rnd)
        }
        base.recycle()
        val lut = if (p.mode == Mode.AM) nightLut(px, p) else composed(lutPm.r, lutPm.g, lutPm.b, 1f, 1f, 1f, p)

        // 4-5. tone + saturation; keep pre-tone luma for highlight detection
        val n = w * h
        val rr = FloatArray(n)
        val gg = FloatArray(n)
        val bb = FloatArray(n)
        val rawL = FloatArray(n)
        for (y in 0 until h) {
            val cy = (y + 0.5f) / h - 0.5f
            for (x in 0 until w) {
                val i = y * w + x
                val cx = (x + 0.5f) / w - 0.5f
                val d2 = (cx * cx + cy * cy) / 0.5f
                val vig = 1f - p.vignette * d2 * sqrt(d2)

                val c = px[i]
                val ri = (c shr 16) and 255
                val gi = (c shr 8) and 255
                val bi = c and 255
                rawL[i] = (0.299f * ri + 0.587f * gi + 0.114f * bi) / 255f

                var r = lut.r[(ri * vig).toInt().coerceIn(0, 255)]
                var g = lut.g[(gi * vig).toInt().coerceIn(0, 255)]
                var b = lut.b[(bi * vig).toInt().coerceIn(0, 255)]

                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val sat = p.saturation - 0.35f * smooth(0.75f, 1f, l)
                r = l + (r - l) * sat
                g = l + (g - l) * sat
                b = l + (b - l) * sat
                rr[i] = r; gg[i] = g; bb[i] = b
            }
        }

        // 6. star filter
        if (p.stars > 0f) addStars(rr, gg, bb, rawL, w, h, p.stars)

        // 7. halation from only the hottest spots, plus optional soft haze
        val hot = FloatArray(n) { ((rawL[it] - 0.90f) / 0.10f).coerceIn(0f, 1f) }
        val hal = blurField(hot, w, h, max(4, max(w, h) / 60))
        val haze = if (p.glow > 0f) blurField(rawL, w, h, max(4, max(w, h) / 40)) else null

        // 8. grain + pack
        val out = IntArray(n)
        var state = rnd.nextLong() or 1L
        val coarse = if (p.grainSize > 1.05f) coarseNoise(w, h, p.grainSize, rnd) else null
        for (i in 0 until n) {
            val hb = hal[i] * p.halation
            val hz = if (haze != null) haze[i] * p.glow else 0f
            var r = rr[i] + hb + hz
            var g = gg[i] + hb * 0.55f + hz
            var b = bb[i] + hb * 0.25f + hz * 0.95f

            state = state xor (state shl 13); state = state xor (state ushr 7); state = state xor (state shl 17)
            val u1 = ((state ushr 40) and 0xFFFF).toFloat() / 65535f
            val u2 = ((state ushr 20) and 0xFFFF).toFloat() / 65535f
            val noise = coarse?.get(i) ?: (u1 + u2 - 1f)
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            val amp = p.grain * (1f - 0.65f * min(1f, abs(l - 0.40f) * 1.8f))
            r += noise * amp; g += noise * amp; b += noise * amp

            out[i] = (0xFF shl 24) or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** Clumpier grain: noise at a lower resolution, smoothly upscaled, re-scaled to keep the same strength. */
    private fun coarseNoise(w: Int, h: Int, size: Float, rnd: Random): FloatArray {
        val sw = max(2, (w / size).roundToInt()); val sh = max(2, (h / size).roundToInt())
        val small = FloatArray(sw * sh) { rnd.nextFloat() + rnd.nextFloat() - 1f }
        val out = FloatArray(w * h)
        val boost = 0.6f + 0.4f * size
        for (y in 0 until h) {
            val fy = (y + 0.5f) * sh / h - 0.5f
            val y0 = fy.toInt().coerceIn(0, sh - 1); val y1 = min(y0 + 1, sh - 1); val ty = (fy - y0).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val fx = (x + 0.5f) * sw / w - 0.5f
                val x0 = fx.toInt().coerceIn(0, sw - 1); val x1 = min(x0 + 1, sw - 1); val tx = (fx - x0).coerceIn(0f, 1f)
                val a = mix(small[y0 * sw + x0], small[y0 * sw + x1], tx)
                val b = mix(small[y1 * sw + x0], small[y1 * sw + x1], tx)
                out[y * w + x] = mix(a, b, ty) * boost
            }
        }
        return out
    }

    /**
     * DRAG composite: "screen" blend in linear light of the flash frame and the long ambient frame.
     * Light adds like a double exposure but can't blow past white.
     */
    fun dragComposite(flash: Bitmap, amb: Bitmap): Bitmap {
        val w = flash.width; val h = flash.height
        val a = if (amb.width == w && amb.height == h) amb else Bitmap.createScaledBitmap(amb, w, h, true)
        val pf = IntArray(w * h); val pa = IntArray(w * h)
        flash.getPixels(pf, 0, w, 0, 0, w, h); a.getPixels(pa, 0, w, 0, 0, w, h)
        if (a !== amb) a.recycle()
        for (i in pf.indices) {
            val f = pf[i]; val g = pa[i]
            fun ch(sh: Int): Int {
                val x = toLin[(f shr sh) and 255]; val y = toLin[(g shr sh) and 255]
                return (toSrgb(1f - (1f - x) * (1f - y)) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            pf[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pf, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Film-style double exposure: both frames added as light in linear space, each about -0.7 EV,
     * so dark areas of one let the other show through and brights blow out together.
     * Frame b is center-cropped/scaled to match a (e.g. after switching cameras).
     */
    fun doubleExposure(a: Bitmap, b0: Bitmap): Bitmap {
        val w = a.width; val h = a.height
        val b = fitTo(b0, w, h)
        val pa = IntArray(w * h); val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h); b.getPixels(pb, 0, w, 0, 0, w, h)
        if (b !== b0) b.recycle()
        val k = 0.62f
        for (i in pa.indices) {
            val x = pa[i]; val y = pb[i]
            fun ch(sh: Int): Int {
                val v = (toLin[(x shr sh) and 255] + toLin[(y shr sh) and 255]) * k
                return (toSrgb(v) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            pa[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pa, 0, w, 0, 0, w, h)
        return out
    }

    private fun fitTo(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val target = w.toFloat() / h
        var cw = src.width; var ch = src.height
        if (cw.toFloat() / ch > target) cw = (ch * target).roundToInt() else ch = (cw / target).roundToInt()
        val crop = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        val out = Bitmap.createScaledBitmap(crop, w, h, true)
        if (crop !== src && crop !== out) crop.recycle()
        return out
    }

    // ---------- crop ----------

    private fun cropTo2x3(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val portrait = h >= w
        val ratio = 1.5f
        var cw = w
        var ch = h
        if (portrait) {
            if (h.toFloat() / w < ratio) cw = (h / ratio).roundToInt() else ch = (w * ratio).roundToInt()
        } else {
            if (w.toFloat() / h < ratio) ch = (w / ratio).roundToInt() else cw = (h * ratio).roundToInt()
        }
        cw = cw.coerceIn(1, w); ch = ch.coerceIn(1, h)
        if (cw == w && ch == h) return src
        return Bitmap.createBitmap(src, (w - cw) / 2, (h - ch) / 2, cw, ch)
    }

    // ---------- star filter ----------

    private fun addStars(rr: FloatArray, gg: FloatArray, bb: FloatArray, rawL: FloatArray, w: Int, h: Int, strength: Float) {
        val f = 4
        val sw = (w + f - 1) / f
        val sh = (h + f - 1) / f
        // max-pool so tiny lights survive downsampling
        val hi = FloatArray(sw * sh)
        val avg = FloatArray(sw * sh)
        for (y in 0 until h) {
            val row = (y / f) * sw
            for (x in 0 until w) {
                val l = rawL[y * w + x]
                val v = ((l - 0.93f) / 0.07f).coerceIn(0f, 1f)
                val i = row + x / f
                if (v > hi[i]) hi[i] = v
                avg[i] += l / (f * f)
            }
        }
        // a star needs: clipped, tiny (not a bright wall/shirt/tile shine), and dark surroundings
        val brightArea = blurField(hi, sw, sh, 3)
        val surround = blurField(avg, sw, sh, 10)
        val pt = FloatArray(sw * sh) {
            val dark = (1f - surround[it] / 0.45f).coerceIn(0f, 1f)
            val tiny = (1f - brightArea[it] / 0.35f).coerceIn(0f, 1f)
            hi[it] * dark * tiny
        }

        data class P(val x: Int, val y: Int, val v: Float)
        val pts = ArrayList<P>()
        for (y in 2 until sh - 2) for (x in 2 until sw - 2) {
            val v = pt[y * sw + x]
            if (v < 0.45f) continue
            var isMax = true
            loop@ for (oy in -2..2) for (ox in -2..2) {
                if ((ox != 0 || oy != 0) && pt[(y + oy) * sw + x + ox] > v) { isMax = false; break@loop }
            }
            if (isMax) pts.add(P(x * f + f / 2, y * f + f / 2, v))
        }
        pts.sortByDescending { it.v }

        val baseLen = 0.045f * max(w, h)
        val angles = floatArrayOf(0.35f, 0.35f + 1.5708f, 0.35f + 0.7854f, 0.35f + 2.3562f)
        val weights = floatArrayOf(1f, 1f, 0.35f, 0.35f)
        for (pnt in pts.take(80)) {
            val len = baseLen * (0.45f + 0.55f * pnt.v)
            for (a in angles.indices) {
                val dx = cos(angles[a])
                val dy = sin(angles[a])
                for (sign in intArrayOf(-1, 1)) {
                    var s = 2f
                    while (s < len) {
                        val t = s / len
                        val inten = strength * pnt.v * weights[a] * (1f - t).pow(2.4f)
                        val x = (pnt.x + sign * dx * s).toInt()
                        val y = (pnt.y + sign * dy * s).toInt()
                        if (x in 0 until w && y in 0 until h) {
                            val i = y * w + x
                            rr[i] += inten; gg[i] += inten * 0.95f; bb[i] += inten * 0.78f
                        }
                        s += 1f
                    }
                }
            }
        }
    }

    // ---------- subject mask (drag mode only) ----------

    /** Center-weighted subject estimate (where the flash lands). Used only to keep the middle sharper in BLUR. */
    private fun subjectMask(w: Int, h: Int): FloatArray {
        val m = FloatArray(w * h)
        for (y in 0 until h) {
            val cy = ((y + 0.5f) / h - 0.45f) / 0.34f
            for (x in 0 until w) {
                val cx = ((x + 0.5f) / w - 0.5f) / 0.28f
                m[y * w + x] = exp(-(cx * cx + cy * cy))
            }
        }
        return m
    }

    // ---------- drag blur ----------

    private fun applyDrag(px: IntArray, w: Int, h: Int, mask: FloatArray, rnd: Random) {
        val ang = rnd.nextFloat() * 6.2831855f
        val len = 0.03f * max(w, h)
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
                val a = (1f - s) * 0.6f + s * 0.1f
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
