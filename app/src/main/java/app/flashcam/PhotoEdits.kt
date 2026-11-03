package app.flashcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import kotlin.math.pow

/** Shared exposure / white balance / contrast edit, used by the edit screen and the viewer's hold-to-expose. */
object PhotoEdits {

    fun matrix(ev: Float, wb: Float = 0f, contrast: Float = 0f): ColorMatrix {
        val m = ColorMatrix()
        val g = 2f.pow(ev * 0.6f)
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

    /**
     * Edit as per-channel lookup tables: exposure + white balance gains, then a contrast curve that keeps
     * black at black and white at white (no grey veil). Negative = softer midtones, positive = punchier.
     */
    fun lut(ev: Float, wb: Float, contrast: Float): Array<IntArray> {
        val g = 2f.pow(ev * 0.6f)
        val gains = floatArrayOf(g * (1f + 0.14f * wb), g * (1f + 0.02f * wb), g * (1f - 0.16f * wb))
        val k = if (contrast >= 0f) contrast * 1.4f else contrast * 0.9f
        return Array(3) { ch -> IntArray(256) { v ->
            val x = (v / 255f * gains[ch]).coerceIn(0f, 1f)
            val s = x * x * (3f - 2f * x)
            ((x + (s - x) * k).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        } }
    }

    fun applyLut(src: Bitmap, t: Array<IntArray>): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val r = t[0]; val g = t[1]; val b = t[2]
        for (i in px.indices) {
            val c = px[i]
            px[i] = (0xFF shl 24) or (r[(c shr 16) and 255] shl 16) or (g[(c shr 8) and 255] shl 8) or b[c and 255]
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Re-develops the photo with [transform], keeping shot info. Returns encoded bytes, or null if decode failed. */
    fun render(ctx: Context, uri: Uri, transform: (Bitmap) -> Bitmap): ByteArray? {
        val src = Roll.decodeSampled(ctx, uri, 4000) ?: return null
        val out = transform(src); if (out !== src) src.recycle()
        val orig = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Throwable) { null }
        val tmp = java.io.File.createTempFile("overnight", ".jpg", ctx.cacheDir)
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        out.recycle()
        if (orig != null) PhotoMeta.copy(orig, tmp)
        return tmp.readBytes().also { tmp.delete() }
    }

    /** Re-develops the photo file in place, keeping its shot info. Returns false if it couldn't write. */
    fun save(ctx: Context, uri: Uri, m: ColorMatrix): Boolean {
        val src = Roll.decodeSampled(ctx, uri, 4000) ?: return false
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) })
        src.recycle()
        val orig = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Throwable) { null }
        val tmp = java.io.File.createTempFile("overnight", ".jpg", ctx.cacheDir)
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        out.recycle()
        if (orig != null) PhotoMeta.copy(orig, tmp)
        val bytes = tmp.readBytes(); tmp.delete()
        return try { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null } catch (_: Throwable) { false }
    }
}
