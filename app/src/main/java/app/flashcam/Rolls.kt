package app.flashcam

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Disposable-style rolls.
 *  - Load a roll (12/24/36). Shots go to a private folder: no peeking.
 *  - A roll is finished when it's full or you SEND TO LAB.
 *  - Finished rolls develop at the next 8:00 AM: photos move into the roll + a notification.
 *  - An unfinished roll untouched for 14 days sends itself to the lab.
 */
object Rolls {

    data class Active(val id: String, val size: Int, val used: Int, val started: Long, val last: Long) {
        val left get() = size - used
    }
    data class Lab(val id: String, val size: Int, val used: Int, val ready: Long)
    data class Developed(val id: String, val used: Int, val date: Long, val names: Set<String>)

    private const val STALE_MS = 14L * 24 * 3600 * 1000
    private const val CHANNEL = "rolls"
    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences("flashcam_rolls", Context.MODE_PRIVATE)
    fun dir(c: Context, id: String) = File(c.filesDir, "rolls/$id").apply { mkdirs() }

    // ---------- state ----------

    fun active(c: Context): Active? = synchronized(lock) {
        val s = prefs(c).getString("active", null) ?: return null
        val o = JSONObject(s)
        Active(o.getString("id"), o.getInt("size"), o.getInt("used"), o.getLong("started"), o.getLong("last"))
    }

    private fun setActive(c: Context, a: Active?) {
        prefs(c).edit().putString("active", a?.let {
            JSONObject().put("id", it.id).put("size", it.size).put("used", it.used)
                .put("started", it.started).put("last", it.last).toString()
        }).commit()
    }

    fun lab(c: Context): List<Lab> = synchronized(lock) {
        val arr = JSONArray(prefs(c).getString("lab", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Lab(it.getString("id"), it.getInt("size"), it.getInt("used"), it.getLong("ready")) }
    }

    private fun setLab(c: Context, list: List<Lab>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("size", it.size).put("used", it.used).put("ready", it.ready)) }
        prefs(c).edit().putString("lab", arr.toString()).commit()
    }

    fun developed(c: Context): List<Developed> = synchronized(lock) {
        val arr = JSONArray(prefs(c).getString("developed", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val n = o.getJSONArray("names")
            Developed(o.getString("id"), o.getInt("used"), o.getLong("date"), (0 until n.length()).map { n.getString(it) }.toSet())
        }
    }

    private fun addDeveloped(c: Context, d: Developed) {
        val list = (developed(c) + d).takeLast(60)
        val arr = JSONArray()
        list.forEach { x -> arr.put(JSONObject().put("id", x.id).put("used", x.used).put("date", x.date).put("names", JSONArray(x.names.toList()))) }
        prefs(c).edit().putString("developed", arr.toString()).commit()
    }

    // ---------- actions ----------

    fun load(c: Context, size: Int): Active = synchronized(lock) {
        val now = System.currentTimeMillis()
        val a = Active("r$now", size, 0, now, now)
        setActive(c, a)
        a
    }

    /** Reserve frames for a shot. Returns how many frames this shot may use (0 if no roll). */
    fun reserve(c: Context, want: Int): Int = synchronized(lock) {
        val a = active(c) ?: return 0
        val n = minOf(want, a.left)
        if (n > 0) setActive(c, a.copy(used = a.used + n, last = System.currentTimeMillis()))
        n
    }

    /** Ends the active roll and schedules development for the next 8 AM. */
    fun sendToLab(c: Context): Unit = synchronized(lock) {
        val a = active(c) ?: return
        setActive(c, null)
        if (a.used == 0) { dir(c, a.id).deleteRecursively(); return }
        setLab(c, lab(c) + Lab(a.id, a.size, a.used, next8am()))
        schedule(c)
    }

    /** Hidden test hook: develop a lab roll right now. */
    fun developNow(c: Context, id: String): List<Developed> {
        synchronized(lock) { setLab(c, lab(c).map { if (it.id == id) it.copy(ready = 0L) else it }) }
        return developDue(c)
    }

    fun discardActive(c: Context): Unit = synchronized(lock) {
        val a = active(c) ?: return
        setActive(c, null)
        dir(c, a.id).deleteRecursively()
        Unit
    }

    /** Called on app open: stale-roll safety net + develop anything that's due. Returns rolls developed now. */
    fun tick(c: Context): List<Developed> {
        synchronized(lock) {
            val a = active(c)
            if (a != null && a.used > 0 && System.currentTimeMillis() - a.last > STALE_MS) sendToLab(c)
        }
        return developDue(c)
    }

    private val devLock = Any()

    fun developDue(c: Context): List<Developed> = synchronized(devLock) { developDueLocked(c) }

    private fun developDueLocked(c: Context): List<Developed> {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) { lab(c).filter { it.ready <= now } }
        val done = ArrayList<Developed>()
        for (r in due) {
            val d = File(c.filesDir, "rolls/${r.id}")
            val files = d.listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()
            val names = HashSet<String>()
            for (f in files) if (publish(c, f)) names.add(f.name)
            d.deleteRecursively()
            val dev = Developed(r.id, r.used, now, names)
            synchronized(lock) {
                setLab(c, lab(c).filter { it.id != r.id })
                addDeveloped(c, dev)
            }
            done.add(dev)
        }
        if (done.isNotEmpty()) schedule(c)
        return done
    }

    /** Copies a developed frame into Pictures/FlashCam so it shows up in the roll and gallery. */
    private fun publish(c: Context, f: File): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FlashCam")
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FlashCam")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, f.name).absolutePath)
            }
        }
        val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) false else {
            c.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            true
        }
    } catch (_: Throwable) {
        false
    }

    /** Save a processed frame into the active roll's private folder. */
    fun saveFrame(c: Context, rollId: String, bmp: Bitmap, name: String) {
        File(dir(c, rollId), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    fun next8am(from: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun readyLabel(ready: Long): String {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ready))
        return if (day == today) "READY 8:00 AM" else "READY " + SimpleDateFormat("EEE", Locale.US).format(Date(ready)).uppercase() + " 8:00 AM"
    }

    // ---------- alarm + notification ----------

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 0, Intent(c, DevelopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = lab(c).minOfOrNull { it.ready }
        if (next == null) { am.cancel(pi); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    fun notifyReady(c: Context, rolls: List<Developed>) {
        if (rolls.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Developed rolls", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val frames = rolls.sumOf { it.names.size }
        val open = PendingIntent.getActivity(c, 1, Intent(c, GalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CHANNEL) else android.app.Notification.Builder(c)
        val n = b.setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (rolls.size == 1) "Your roll is ready" else "${rolls.size} rolls are ready")
            .setContentText("$frames frames developed")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(42, n)
    }
}

/** Fires at 8 AM (and after reboot) to develop finished rolls. */
class DevelopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) Rolls.schedule(app)
                Rolls.notifyReady(app, Rolls.developDue(app))
            } finally {
                result.finish()
            }
        }.start()
    }
}
