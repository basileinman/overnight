package app.flashcam

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore

/** Where photos live. Everything goes through here so the folder name is set in one place. */
object Paths {
    const val ROLL = "Overnight"                 // Pictures/Overnight
    const val ORIGINALS = "Overnight/Originals"  // Pictures/Overnight/Originals (debug)
    const val ROLL_REL = "Pictures/Overnight/"
    const val ORIGINALS_REL = "Pictures/Overnight/Originals/"
    const val GIFS = "Overnight/GIFs"; const val GIFS_REL = "Pictures/Overnight/GIFs/"

    private const val OLD_ROLL_REL = "Pictures/FlashCam/"
    private const val OLD_ORIG_REL = "Pictures/FlashCam/Originals/"

    /** One-time move of photos from the old Pictures/FlashCam folders. Safe to call every launch. */
    fun migrate(ctx: Context) {
        val prefs = ctx.getSharedPreferences("flashcam", Context.MODE_PRIVATE)
        if (prefs.getBoolean("migratedToOvernight", false) || Build.VERSION.SDK_INT < 29) return
        var failed = 0
        for ((from, to) in listOf(OLD_ROLL_REL to ROLL_REL, OLD_ORIG_REL to ORIGINALS_REL)) {
            val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            try {
                ctx.contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf(from), null)?.use { c ->
                    while (c.moveToNext()) {
                        val uri = android.content.ContentUris.withAppendedId(col, c.getLong(0))
                        try {
                            val v = ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, to) }
                            if (ctx.contentResolver.update(uri, v, null, null) <= 0) failed++
                        } catch (_: Throwable) { failed++ }
                    }
                }
            } catch (_: Throwable) { failed++ }
        }
        // Developed-roll bookkeeping uses file names, which don't change, so rolls stay grouped.
        if (failed == 0) prefs.edit().putBoolean("migratedToOvernight", true).apply()
    }
}
