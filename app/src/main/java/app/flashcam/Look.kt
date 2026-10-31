package app.flashcam

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
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
        private val FIT_R = intArrayOf(22, 24, 25, 27, 28, 30, 31, 33, 34, 36, 37, 38, 40, 42, 43, 45, 47, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 81, 84, 86, 90, 93, 97, 101, 106, 111, 115, 120, 125, 129, 134, 137, 141, 145, 148, 150, 153, 156, 158, 160, 162, 165, 167, 169, 171, 173, 176, 178, 181, 183, 186, 189, 192, 195, 198, 202, 205, 209, 212, 216, 220, 224, 229, 233, 238, 243, 248, 253, 258, 263, 268, 273, 279, 284, 289, 294, 299, 304, 309, 314, 319, 324, 329, 334, 338, 343, 348, 353, 358, 363, 367, 372, 377, 381, 386, 391, 396, 402, 407, 413, 419, 426, 433, 439, 446, 453, 460, 466, 472, 478, 484, 489, 494, 499, 504, 509, 513, 517, 522, 526, 530, 534, 539, 543, 547, 551, 555, 560, 564, 568, 572, 576, 579, 583, 587, 590, 593, 596, 599, 602, 605, 608, 611, 614, 617, 620, 622, 625, 628, 631, 634, 637, 640, 642, 645, 648, 651, 653, 656, 659, 662, 664, 667, 670, 673, 675, 678, 681, 684, 687, 691, 694, 697, 700, 704, 707, 710, 714, 717, 721, 724, 727, 731, 734, 737, 740, 743, 746, 749, 752, 754, 757, 759, 761, 764, 766, 768, 770, 772, 774, 776, 777, 779, 781, 783, 785, 786, 788, 790, 792, 794, 795, 797, 799, 801, 803, 805, 806, 808, 810, 812, 813, 815, 817, 819, 820, 822, 824, 825, 827, 828, 830, 831, 833, 834, 836, 837, 838, 839, 840)
        private val FIT_G = intArrayOf(32, 34, 35, 37, 38, 40, 42, 43, 45, 47, 48, 50, 51, 53, 55, 56, 58, 59, 61, 63, 65, 68, 72, 76, 81, 86, 93, 100, 107, 114, 121, 128, 134, 140, 145, 150, 154, 158, 161, 165, 168, 171, 174, 177, 180, 183, 186, 190, 193, 197, 200, 204, 208, 212, 216, 221, 225, 229, 234, 238, 243, 247, 252, 256, 260, 265, 269, 274, 279, 283, 288, 293, 298, 302, 307, 312, 317, 321, 326, 330, 335, 339, 344, 348, 352, 357, 361, 365, 370, 374, 379, 384, 389, 394, 400, 406, 412, 418, 425, 432, 440, 447, 455, 462, 469, 476, 483, 490, 496, 502, 508, 514, 519, 524, 529, 534, 538, 542, 546, 550, 554, 558, 562, 566, 570, 574, 578, 582, 586, 589, 593, 596, 599, 602, 605, 608, 611, 614, 617, 619, 622, 625, 628, 630, 633, 636, 639, 642, 646, 649, 652, 656, 659, 663, 667, 671, 675, 679, 684, 688, 692, 695, 699, 702, 705, 708, 711, 714, 716, 719, 721, 723, 725, 727, 729, 731, 733, 734, 736, 737, 738, 740, 741, 742, 743, 745, 746, 747, 748, 750, 751, 753, 755, 757, 759, 761, 764, 766, 769, 772, 775, 777, 780, 783, 786, 789, 792, 795, 798, 801, 804, 807, 810, 813, 816, 819, 822, 825, 828, 831, 834, 837, 840, 842, 845, 848, 851, 854, 857, 860, 863, 866, 869, 872, 875, 878, 881, 884, 887, 890, 893, 896, 899, 902, 905, 908, 911, 913, 916, 919, 922, 924, 927, 929, 931, 933)
        private val FIT_B = intArrayOf(28, 30, 32, 34, 37, 39, 41, 43, 45, 47, 49, 51, 53, 54, 56, 58, 61, 64, 68, 72, 77, 83, 90, 96, 103, 110, 116, 122, 128, 134, 138, 143, 147, 151, 155, 159, 163, 167, 171, 175, 179, 183, 187, 192, 196, 201, 206, 211, 216, 221, 226, 231, 237, 242, 248, 254, 259, 265, 271, 277, 283, 289, 295, 300, 306, 312, 318, 323, 329, 335, 341, 347, 354, 361, 368, 376, 384, 393, 402, 411, 420, 429, 437, 446, 454, 461, 468, 475, 481, 487, 492, 497, 502, 507, 512, 516, 521, 526, 530, 535, 540, 545, 549, 554, 559, 563, 567, 571, 575, 578, 582, 585, 588, 591, 595, 598, 601, 604, 608, 611, 614, 618, 621, 624, 627, 631, 634, 637, 639, 642, 645, 647, 650, 652, 654, 657, 659, 661, 662, 664, 666, 668, 669, 671, 672, 674, 675, 677, 678, 679, 681, 682, 683, 684, 685, 687, 688, 689, 690, 691, 693, 694, 695, 696, 698, 699, 700, 702, 703, 705, 707, 709, 711, 713, 715, 718, 720, 722, 725, 727, 730, 732, 734, 737, 739, 742, 744, 746, 749, 751, 753, 756, 758, 761, 763, 765, 768, 770, 773, 775, 777, 780, 782, 785, 787, 789, 792, 794, 797, 799, 801, 804, 806, 809, 811, 813, 816, 818, 820, 823, 825, 828, 830, 832, 835, 837, 840, 842, 844, 847, 849, 852, 854, 856, 859, 861, 864, 866, 868, 871, 873, 876, 878, 880, 883, 885, 887, 890, 892, 894, 897, 899, 901, 902, 904, 905)
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
    ) {
        companion object {
            fun forMode(mode: Mode, drag: Boolean) = when (mode) {
                Mode.AM -> Params(mode = mode, drag = drag)
                Mode.PM -> Params(
                    mode = mode, drag = drag,
                    saturation = 0.96f, halation = 0.04f, stars = 0f, grain = 0.040f, vignette = 0.06f,
                    glow = 0.07f,
                )
            }
        }
    }

    private class Lut(val r: FloatArray, val g: FloatArray, val b: FloatArray)

    // reference curves fitted directly from a matched pair: Pixel flash-pulse original -> reference cam shot
    private val lutAm = Lut(
        FloatArray(256) { FIT_R[it] / 1000f },
        FloatArray(256) { FIT_G[it] / 1000f },
        FloatArray(256) { FIT_B[it] / 1000f },
    )
    private val lutPm = buildLut(
        contrast = 0.12f,
        black = floatArrayOf(0.095f, 0.115f, 0.125f),
        white = floatArrayOf(0.955f, 0.950f, 0.905f),
        gamma = floatArrayOf(1.03f, 0.99f, 0.97f),
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

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    fun process(input: Bitmap, p: Params, seed: Long): Bitmap {
        val rnd = Random(seed)
        val lut = if (p.mode == Mode.AM) lutAm else lutPm

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
            val mask = subjectMask(base, w, h)
            applyDrag(px, w, h, mask, rnd)
        }
        base.recycle()

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
        for (i in 0 until n) {
            val hb = hal[i] * p.halation
            val hz = if (haze != null) haze[i] * p.glow else 0f
            var r = rr[i] + hb + hz
            var g = gg[i] + hb * 0.55f + hz
            var b = bb[i] + hb * 0.25f + hz * 0.95f

            state = state xor (state shl 13); state = state xor (state ushr 7); state = state xor (state shl 17)
            val u1 = ((state ushr 40) and 0xFFFF).toFloat() / 65535f
            val u2 = ((state ushr 20) and 0xFFFF).toFloat() / 65535f
            val noise = u1 + u2 - 1f
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            val amp = p.grain * (1f - 0.65f * min(1f, abs(l - 0.40f) * 1.8f))
            r += noise * amp; g += noise * amp; b += noise * amp

            out[i] = (0xFF shl 24) or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
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

    private fun subjectMask(bmp: Bitmap, w: Int, h: Int): FloatArray {
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
            radial
        } else {
            val soft = blurField(seg, w, h, max(4, max(w, h) / 70))
            FloatArray(w * h) { soft[it].coerceIn(0f, 1f) }
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
                val a = (1f - s) * 0.9f + s * 0.18f
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
