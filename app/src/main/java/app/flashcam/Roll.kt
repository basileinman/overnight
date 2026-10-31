package app.flashcam

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlin.math.max

/** The app's own photo roll: everything saved to Pictures/FlashCam (not the debug originals). */
object Roll {

    data class Photo(val id: Long, val uri: Uri, val number: Int)

    /** Newest first; numbers count up from the first photo ever taken (001, 002, ...). */
    fun load(context: Context): List<Photo> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
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
                var n = 0
                while (c.moveToNext()) {
                    n++
                    val id = c.getLong(idCol)
                    out.add(Photo(id, ContentUris.withAppendedId(collection, id), n))
                }
            }
        } catch (_: Throwable) {
        }
        out.reverse()
        return out
    }

    fun thumbnail(context: Context, p: Photo, px: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(p.uri, Size(px, px * 3 / 2), null)
        } else {
            decodeSampled(context, p.uri, px * 3 / 2)
        }
    } catch (_: Throwable) {
        null
    }

    /** Decode a photo so its long edge is roughly [maxEdge]. */
    fun decodeSampled(context: Context, uri: Uri, maxEdge: Int): Bitmap? = try {
        val r = context.contentResolver
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        r.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Throwable) {
        null
    }
}
