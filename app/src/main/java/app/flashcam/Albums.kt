package app.flashcam

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Albums are only lists of photo names; no images are copied or cached on disk.
 *  - NIGHT: automatic, one per outing (a new one starts after a few hours with no shots)
 *  - ROLL: automatic, every developed roll
 *  - MANUAL: made from selected photos
 * Any album can get a title; automatic ones can be hidden (photos are never touched).
 */
object Albums {
    enum class Kind { NIGHT, ROLL, MANUAL }

    data class Album(
        val id: String,
        val kind: Kind,
        val title: String?,
        val photos: List<Roll.Photo>,   // newest first, same order as the roll
    ) {
        val start get() = photos.minOfOrNull { it.taken } ?: 0L
        val end get() = photos.maxOfOrNull { it.taken } ?: 0L
        /** A few photos to show on the cover, skipping burst strips. */
        fun covers(n: Int): List<Roll.Photo> {
            val pool = photos.filter { !it.isStrip }.ifEmpty { photos }
            if (pool.size <= n) return pool
            return (0 until n).map { i -> pool[i * (pool.size - 1) / (n - 1).coerceAtLeast(1)] }
        }
    }

    private const val GAP_MS = 3L * 3600 * 1000   // a quiet gap this long starts a new night
    private const val MIN_NIGHT = 3               // fewer shots than this don't make an album

    private fun prefs(c: Context) = c.getSharedPreferences("flashcam_albums", Context.MODE_PRIVATE)

    // ---------- storage ----------

    private class Store(val manual: JSONArray, val titles: JSONObject, val hidden: JSONArray)

    private fun read(c: Context): Store {
        val p = prefs(c)
        return Store(
            JSONArray(p.getString("manual", "[]")),
            JSONObject(p.getString("titles", "{}")),
            JSONArray(p.getString("hidden", "[]")),
        )
    }

    private fun write(c: Context, s: Store) = prefs(c).edit()
        .putString("manual", s.manual.toString())
        .putString("titles", s.titles.toString())
        .putString("hidden", s.hidden.toString())
        .apply()

    private fun manualIndex(s: Store, id: String) = (0 until s.manual.length()).firstOrNull { s.manual.getJSONObject(it).getString("id") == id }

    /** New album from photos; returns its id. */
    fun create(c: Context, title: String?, photos: List<Roll.Photo>): String = synchronized(this) {
        val s = read(c)
        val id = "m" + System.currentTimeMillis()
        s.manual.put(JSONObject().apply {
            put("id", id)
            put("title", title?.trim()?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            put("names", JSONArray(photos.map { it.name }))
        })
        write(c, s); id
    }

    fun add(c: Context, id: String, photos: List<Roll.Photo>) = synchronized(this) {
        val s = read(c); val i = manualIndex(s, id) ?: return
        val o = s.manual.getJSONObject(i)
        val names = LinkedHashSet<String>().apply {
            val a = o.getJSONArray("names"); for (k in 0 until a.length()) add(a.getString(k))
            photos.forEach { add(it.name) }
        }
        o.put("names", JSONArray(names.toList()))
        write(c, s)
    }

    fun remove(c: Context, id: String, photos: List<Roll.Photo>) = synchronized(this) {
        val s = read(c); val i = manualIndex(s, id) ?: return
        val drop = photos.map { it.name }.toSet()
        val o = s.manual.getJSONObject(i)
        val a = o.getJSONArray("names")
        o.put("names", JSONArray((0 until a.length()).map { a.getString(it) }.filter { it !in drop }))
        write(c, s)
    }

    fun rename(c: Context, id: String, title: String?) = synchronized(this) {
        val s = read(c)
        val t = title?.trim()?.takeIf { it.isNotEmpty() }
        val i = manualIndex(s, id)
        if (i != null) s.manual.getJSONObject(i).put("title", t ?: JSONObject.NULL)
        else if (t == null) s.titles.remove(id) else s.titles.put(id, t)
        write(c, s)
    }

    /** Manual albums are deleted; automatic ones are hidden. Photos stay either way. */
    fun delete(c: Context, id: String) = synchronized(this) {
        val s = read(c)
        val i = manualIndex(s, id)
        if (i != null) s.manual.remove(i) else s.hidden.put(id)
        write(c, s)
    }

    /** Manual albums, newest first, for the "add to album" picker. */
    fun manualList(c: Context, all: List<Roll.Photo>) = build(c, all).filter { it.kind == Kind.MANUAL }

    // ---------- building ----------

    /** Every album to show, newest first. [all] is the full photo list from Roll.load. */
    fun build(c: Context, all: List<Roll.Photo>): List<Album> = synchronized(this) {
        val s = read(c)
        val byName = all.associateBy { it.name }
        val hidden = (0 until s.hidden.length()).map { s.hidden.getString(it) }.toSet()
        val out = ArrayList<Album>()

        // rolls
        val inRoll = HashSet<String>()
        Rolls.developed(c).forEach { d ->
            val ps = all.filter { it.name in d.names }
            inRoll.addAll(d.names)
            val id = "roll:" + d.id
            if (ps.isNotEmpty()) out.add(Album(id, Kind.ROLL, s.titles.optString(id).takeIf { it.isNotEmpty() }, ps))
        }

        // nights: everything not on a roll, split at long quiet gaps
        val loose = all.filter { it.name !in inRoll && it.taken > 0 }.sortedBy { it.taken }
        var cur = ArrayList<Roll.Photo>()
        fun flush() {
            if (cur.size >= MIN_NIGHT) {
                val id = "night:" + cur.first().name
                out.add(Album(id, Kind.NIGHT, s.titles.optString(id).takeIf { it.isNotEmpty() }, cur.reversed()))
            }
            cur = ArrayList()
        }
        for (p in loose) {
            if (cur.isNotEmpty() && p.taken - cur.last().taken > GAP_MS) flush()
            cur.add(p)
        }
        flush()

        // manual
        for (k in 0 until s.manual.length()) {
            val o = s.manual.getJSONObject(k)
            val a = o.getJSONArray("names")
            val ps = (0 until a.length()).mapNotNull { byName[a.getString(it)] }.sortedByDescending { it.taken }
            out.add(Album(o.getString("id"), Kind.MANUAL, if (o.isNull("title")) null else o.optString("title"), ps))
        }

        out.filter { it.id !in hidden && (it.photos.isNotEmpty() || it.kind == Kind.MANUAL) }
            .sortedByDescending { if (it.photos.isEmpty()) Long.MAX_VALUE else it.end }
    }

    fun find(c: Context, all: List<Roll.Photo>, id: String) = build(c, all).firstOrNull { it.id == id }

    // ---------- date stamp ----------

    /**
     * Film date-stamp style: '26 11 21, or a range like '26 11 21-23.
     * Shots before 6 AM count as the previous evening, so one night out keeps one date.
     */
    fun dateStamp(a: Album): String {
        if (a.photos.isEmpty()) return "-- -- --"
        fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t - 6L * 3600 * 1000 }
        val s = day(a.start); val e = day(a.end)
        fun yy(c: Calendar) = "%02d".format(c.get(Calendar.YEAR) % 100)
        fun mm(c: Calendar) = "%02d".format(c.get(Calendar.MONTH) + 1)
        fun dd(c: Calendar) = "%02d".format(c.get(Calendar.DAY_OF_MONTH))
        val first = "'${yy(s)} ${mm(s)} ${dd(s)}"
        return when {
            s.get(Calendar.YEAR) == e.get(Calendar.YEAR) && s.get(Calendar.DAY_OF_YEAR) == e.get(Calendar.DAY_OF_YEAR) -> first
            s.get(Calendar.YEAR) == e.get(Calendar.YEAR) && s.get(Calendar.MONTH) == e.get(Calendar.MONTH) -> "$first-${dd(e)}"
            s.get(Calendar.YEAR) == e.get(Calendar.YEAR) -> "$first-${mm(e)} ${dd(e)}"
            else -> "$first-'${yy(e)} ${mm(e)} ${dd(e)}"
        }
    }

    fun subtitle(a: Album): String {
        val n = a.photos.size
        val kind = when (a.kind) { Kind.ROLL -> "ROLL · "; else -> "" }
        return kind + "$n PHOTO" + (if (n == 1) "" else "S")
    }

    // ---------- LED text ----------

    private var led: Typeface? = null

    fun ledFont(c: Context): Typeface =
        led ?: (try { ResourcesCompat.getFont(c, R.font.dseg14) } catch (_: Throwable) { null } ?: Typeface.MONOSPACE).also { led = it }

    /** The LED font draws a space narrow; '!' is its full-width blank. Lowercase isn't supported, so uppercase. */
    fun ledText(s: String) = s.uppercase().replace(' ', '!')

    /** Orange date-stamp text with a soft glow, like the date burned into a print. */
    fun ledView(c: Context, text: String, sp: Float, dim: Boolean = false) = TextView(c).apply {
        typeface = ledFont(c)
        this.text = ledText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        val col = if (dim) 0xCCFF7A3A.toInt() else 0xFFFF7A3A.toInt()
        setTextColor(col)
        setShadowLayer(sp * 0.45f, 0f, 0f, 0x99FF5A1A.toInt())
        includeFontPadding = false
        letterSpacing = 0.04f
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
}
