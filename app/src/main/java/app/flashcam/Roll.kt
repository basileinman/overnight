package app.flashcam

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlin.math.max

/** The app's own photo roll: everything saved to Pictures/FlashCam (not the debug originals). */
object Roll {

    data class Photo(val id: Long, val uri: Uri, val number: Int, val name: String) {
        /** Burst frames are saved as FLASH_<stamp>_<n>.jpg; returns <stamp> or null. */
        val burstKey: String? get() {
            val m = BURST.matchEntire(name) ?: return null
            return m.groupValues[1]
        }
        val isStrip get() = name.endsWith("_strip.jpg")
    }

    private val BURST = Regex("FLASH_(\\d{8}_\\d{6})_(\\d)\\.jpg")

    /** Newest first; numbers count up from the first photo ever taken (001, 002, ...). */
    fun load(context: Context): List<Photo> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            args = arrayOf("Pictures/FlashCam/")
        } else {
            @Suppress("DEPRECATION")
            val data = MediaStore.Images.Media.DATA
            selection = "$data LIKE ? AND $data NOT LIKE ?"
            args = arrayOf("%/Pictures/FlashCam/%", "%/Originals/%")
        }
        val sort = "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC"
        val out = ArrayList<Photo>()
        try {
            context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                var n = 0
                while (c.moveToNext()) {
                    n++
                    val id = c.getLong(idCol)
                    out.add(Photo(id, ContentUris.withAppendedId(collection, id), n, c.getString(nameCol) ?: ""))
                }
            }
        } catch (_: Throwable) {
        }
        out.reverse()
        return out
    }

    // ---------- favorites ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("flashcam_roll", Context.MODE_PRIVATE)

    fun favorites(ctx: Context): Set<Long> =
        prefs(ctx).getStringSet("fav", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun isFavorite(ctx: Context, id: Long) = id in favorites(ctx)

    fun setFavorite(ctx: Context, id: Long, fav: Boolean) {
        val s = favorites(ctx).map { it.toString() }.toMutableSet()
        if (fav) s.add(id.toString()) else s.remove(id.toString())
        prefs(ctx).edit().putStringSet("fav", s).apply()
    }

    // ---------- decoding ----------

    /** Sharp grid thumbnail: decode the real file at ~[longEdge] px (system thumbnails are too soft). */
    fun thumbnail(context: Context, p: Photo, longEdge: Int): Bitmap? = decodeSampled(context, p.uri, longEdge)

    /** Decode a photo so its long edge is roughly [maxEdge]. */
    fun decodeSampled(context: Context, uri: Uri, maxEdge: Int): Bitmap? = try {
        val r = context.contentResolver
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val bmp = r.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bmp != null && max(bmp.width, bmp.height) > maxEdge * 1.3f) {
            val s = maxEdge.toFloat() / max(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true).also { if (it !== bmp) bmp.recycle() }
        } else bmp
    } catch (_: Throwable) {
        null
    }
}
