package app.flashcam

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** App settings (stored in the same prefs file the camera always used) and the settings sheet. */
object Settings {
    const val REPO = "basileinman/flash-cam"
    const val RELEASES_URL = "https://github.com/$REPO/releases"

    enum class Focus { AUTO, CLOSE, FAR }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("flashcam", Context.MODE_PRIVATE)

    fun focus(ctx: Context) = Focus.values().getOrElse(prefs(ctx).getInt("focusMode", 0)) { Focus.AUTO }
    fun setFocus(ctx: Context, f: Focus) = prefs(ctx).edit().putInt("focusMode", f.ordinal).apply()

    fun autoMode(ctx: Context) = prefs(ctx).getBoolean("autoMode", true)
    fun setAutoMode(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoMode", on).apply()

    fun rollSize(ctx: Context) = prefs(ctx).getInt("rollSize", 36)
    fun setRollSize(ctx: Context, n: Int) = prefs(ctx).edit().putInt("rollSize", n).apply()

    fun debug(ctx: Context) = prefs(ctx).getBoolean("debug", false)
    fun setDebug(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("debug", on).apply()

    fun introSeen(ctx: Context) = prefs(ctx).getBoolean("introSeen", false)
    fun setIntroSeen(ctx: Context, seen: Boolean) = prefs(ctx).edit().putBoolean("introSeen", seen).apply()

    fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) { "?" }

    // ---------- sheet ----------

    fun show(act: Activity) {
        val ctx = act
        fun dp(v: Int) = Ui.dpi(ctx, v)
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(ctx, "SETTINGS", Ui.TITLE, Ui.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Ui.text(ctx, "DONE", Ui.BUTTON, Ui.ORANGE).apply {
            setPadding(dp(12), dp(6), 0, dp(6)); setOnClickListener { d.dismiss() }
        })
        col.addView(head)

        fun section(title: String) {
            col.addView(Ui.text(ctx, title, Ui.TINY, Ui.DIM).apply { setPadding(0, dp(22), 0, dp(8)) })
        }
        fun note(text: String) {
            col.addView(Ui.body(ctx, text, Ui.SMALL, Ui.FAINT).apply { setPadding(0, dp(6), 0, 0) })
        }
        /** Row of pill choices; [selected] index highlighted. */
        fun choices(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val views = ArrayList<TextView>()
            fun paint(sel: Int) = views.forEachIndexed { i, v ->
                v.setTextColor(if (i == sel) Ui.ORANGE else Ui.DIM)
                v.background = Ui.button(ctx, i == sel)
            }
            labels.forEachIndexed { i, l ->
                val v = Ui.text(ctx, l, Ui.SMALL, Ui.DIM).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(11), 0, dp(11))
                    setOnClickListener { paint(i); onPick(i) }
                }
                views.add(v)
                row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6) })
            }
            paint(selected)
            col.addView(row)
        }
        fun action(label: String, sub: String? = null, onTap: () -> Unit): TextView {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.button(ctx, false)
                setOnClickListener { onTap() }
            }
            row.addView(Ui.text(ctx, label, Ui.SMALL, Ui.SOFT), LinearLayout.LayoutParams(0, -2, 1f))
            val right = Ui.text(ctx, sub ?: "›", Ui.SMALL, Ui.DIM)
            row.addView(right)
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            return right
        }

        section("NIGHT FOCUS")
        choices(listOf("AUTO", "CLOSE 3FT", "FAR 6FT"), focus(ctx).ordinal) { setFocus(ctx, Focus.values()[it]) }
        note("Auto follows the subject. Close and Far lock focus and flash power like a disposable.")

        section("NIGHT / DAY")
        choices(listOf("AUTO BY LIGHT", "MANUAL"), if (autoMode(ctx)) 0 else 1) { setAutoMode(ctx, it == 0) }

        section("ROLL SIZE")
        val sizes = listOf(36, 24, 12)
        choices(sizes.map { "$it" }, sizes.indexOf(rollSize(ctx)).coerceAtLeast(0)) { setRollSize(ctx, sizes[it]) }

        section("DEVELOPER")
        choices(listOf("SAVE ORIGINALS", "OFF"), if (debug(ctx)) 0 else 1) { setDebug(ctx, it == 0) }
        note("Saves each unprocessed frame to Pictures/Overnight/Originals for tuning the look.")
        action("LOOK LAB") { d.dismiss(); ctx.startActivity(Intent(ctx, LookLabActivity::class.java)) }

        section("APP")
        action("SHOW INTRO AGAIN") {
            setIntroSeen(ctx, false); d.dismiss()
            Toast.makeText(ctx, "The intro shows next time you open the camera", Toast.LENGTH_SHORT).show()
        }
        lateinit var updateRight: TextView
        updateRight = action("CHECK FOR UPDATES") { checkForUpdate(act, updateRight) }
        col.addView(Ui.text(ctx, "OVERNIGHT · v${versionName(ctx)}", Ui.TINY, Ui.FAINT).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(4))
        }, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(ctx).apply { background = Ui.card(ctx); addView(col) }
        d.setContentView(scroll)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((ctx.resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.7f)
        }
        d.show()
    }

    // ---------- updates ----------

    /**
     * Asks GitHub for the newest release and compares its version with this one.
     * Only works while the repo is public; otherwise it just opens the releases page.
     */
    private fun checkForUpdate(act: Activity, status: TextView) {
        status.text = "CHECKING…"
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result: Pair<String?, String?> = try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode != 200) null to null
                else {
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val assets = j.optJSONArray("assets")
                    var name: String? = null; var url: String? = null
                    for (i in 0 until (assets?.length() ?: 0)) {
                        val a = assets!!.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) { name = a.optString("name"); url = a.optString("browser_download_url") }
                    }
                    name to url
                }
            } catch (_: Throwable) { null to null }
            main.post {
                if (act.isFinishing) return@post
                val (name, url) = result
                // asset names look like Overnight-v0.30.33.apk
                val latest = name?.let { Regex("v(\\d+)\\.(\\d+)").find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                val mine = Regex("(\\d+)\\.(\\d+)").find(versionName(act))?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                when {
                    latest == null -> {
                        status.text = "OPEN ›"
                        FlashDialog.show(act, "Couldn't check", "The repo is private, so the app can't see new releases. Open the releases page instead? (You'll need to be signed in to GitHub.)",
                            listOf(FlashDialog.Option("Open releases", FlashDialog.Style.PRIMARY) { open(act, RELEASES_URL) }))
                    }
                    mine != null && compareValuesBy(latest, mine, { it.first }, { it.second }) <= 0 -> status.text = "UP TO DATE"
                    else -> {
                        status.text = "v${latest.first}.${latest.second} ›"
                        FlashDialog.show(act, "v${latest.first}.${latest.second} is out", "You have v${versionName(act)}. Download the new APK and open it to update.",
                            listOf(FlashDialog.Option("Download", FlashDialog.Style.PRIMARY) { open(act, url ?: RELEASES_URL) }))
                    }
                }
            }
        }.start()
    }

    private fun open(ctx: Context, url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Throwable) {}
    }

    @Suppress("unused") private fun unused(v: View) = v
}
