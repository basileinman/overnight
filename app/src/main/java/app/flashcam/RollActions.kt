package app.flashcam

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest

/** Share and delete helpers used by the roll grid and the viewer. */
object RollActions {

    fun share(activity: Activity, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, if (uris.size == 1) "Share photo" else "Share ${uris.size} photos"))
    }

    /**
     * Deletes photos the app created directly. If Android won't allow that (e.g. after a reinstall),
     * falls back to the system "allow delete?" prompt. Returns true if deleted immediately.
     */
    fun delete(activity: Activity, uris: List<Uri>, prompt: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        if (uris.isEmpty()) return true
        val failed = ArrayList<Uri>()
        for (u in uris) {
            try {
                if (activity.contentResolver.delete(u, null, null) <= 0) failed.add(u)
            } catch (_: SecurityException) {
                failed.add(u)
            } catch (_: Throwable) {
                failed.add(u)
            }
        }
        if (failed.isEmpty()) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pi = MediaStore.createDeleteRequest(activity.contentResolver, failed)
            prompt.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
        return false
    }
}
