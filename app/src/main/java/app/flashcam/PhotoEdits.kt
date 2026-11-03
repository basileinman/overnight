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
