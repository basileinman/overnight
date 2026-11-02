package app.flashcam

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shot settings stored inside each JPEG: standard EXIF (shutter, ISO, aperture, focal length, date, phone)
 * plus FlashCam's own settings as JSON in the UserComment tag. Travels with the photo when shared.
 */
object PhotoMeta {

    private const val PREFIX = "FlashCam:"

    /** Encode [bmp] as JPEG bytes with metadata attached. */
    fun encode(ctx: Context, bmp: Bitmap, quality: Int, meta: JSONObject?): ByteArray {
        val tmp = File.createTempFile("flashcam", ".jpg", ctx.cacheDir)
        try {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (meta != null) write(tmp, meta)
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    fun write(file: File, meta: JSONObject) {
        try {
            val ex = ExifInterface(file.absolutePath)
            ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            ex.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            ex.setAttribute(ExifInterface.TAG_SOFTWARE, "Overnight")
            val t = meta.optLong("time", System.currentTimeMillis())
            val d = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(t))
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, d)
            ex.setAttribute(ExifInterface.TAG_DATETIME, d)
            val expNs = meta.optLong("expNs", 0L)
            if (expNs > 0) ex.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (expNs / 1e9).toString())
            val iso = meta.optInt("iso", 0)
            if (iso > 0) ex.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
            val f = meta.optDouble("fNumber", 0.0)
            if (f > 0) ex.setAttribute(ExifInterface.TAG_F_NUMBER, f.toString())
            val fl = meta.optDouble("focalMm", 0.0)
            if (fl > 0) ex.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(fl * 100).roundToInt()}/100")
            val fl35 = meta.optInt("focal35", 0)
            if (fl35 > 0) ex.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, fl35.toString())
            val fired = meta.optString("flash") in setOf("PULSE", "TORCH", "SCREEN")
            ex.setAttribute(ExifInterface.TAG_FLASH, if (fired) "1" else "0")
            ex.setAttribute(ExifInterface.TAG_USER_COMMENT, PREFIX + meta.toString())
            ex.saveAttributes()
        } catch (_: Throwable) {
        }
    }

    /** Copy all metadata from one JPEG file to another (used when an edit re-saves the image). */
    fun copy(fromBytes: ByteArray, to: File) {
        try {
            val src = ExifInterface(fromBytes.inputStream())
            val dst = ExifInterface(to.absolutePath)
            for (tag in listOf(
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH, ExifInterface.TAG_USER_COMMENT,
            )) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            dst.saveAttributes()
        } catch (_: Throwable) {
        }
    }

    fun read(ctx: Context, uri: Uri): JSONObject? = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val c = ExifInterface(s).getAttribute(ExifInterface.TAG_USER_COMMENT) ?: return null
            val i = c.indexOf(PREFIX)
            if (i < 0) null else JSONObject(c.substring(i + PREFIX.length))
        }
    } catch (_: Throwable) {
        null
    }

    /** Human-readable rows for the viewer's INFO panel. */
    fun rows(m: JSONObject): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun add(k: String, v: String?) { if (!v.isNullOrBlank()) out.add(k to v) }
        add("MODE", m.optString("mode"))
        add("FOCUS", m.optString("focus"))
        add("FLASH", m.optString("flash"))
        add("EFFECT", m.optString("effect"))
        val n = m.optInt("burstOf", 1)
        add("SHOT", when { m.optBoolean("double") -> "DOUBLE EXPOSURE"; n > 1 -> "BURST ${m.optInt("burstIdx", 1)}/$n"; else -> "SINGLE" })
        val exp = m.optLong("expNs", 0L)
        if (exp > 0) add("SHUTTER", if (exp >= 500_000_000L) "%.1fs".format(exp / 1e9) else "1/${(1e9 / exp).roundToInt()}")
        if (m.optInt("iso", 0) > 0) add("ISO", m.optInt("iso").toString())
        if (m.optDouble("fNumber", 0.0) > 0) add("APERTURE", "f/%.1f".format(m.optDouble("fNumber")))
        if (m.optInt("focal35", 0) > 0) add("LENS", "${m.optInt("focal35")}mm equiv")
        val ev = m.optDouble("ev", 0.0)
        add("EXP COMP", (if (ev > 0) "+" else "") + "%.1f EV".format(ev))
        add("CAMERA", m.optString("camera"))
        if (m.has("rollFrame")) add("ROLL", "FRAME %02d / %d".format(m.optInt("rollFrame"), m.optInt("rollSize")))
        val t = m.optLong("time", 0L)
        if (t > 0) add("TAKEN", SimpleDateFormat("MMM d · h:mm a", Locale.US).format(Date(t)).uppercase())
        add("PHONE", m.optString("phone"))
        return out
    }
}
