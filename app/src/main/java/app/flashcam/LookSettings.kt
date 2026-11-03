package app.flashcam

import android.content.Context
import org.json.JSONObject

/** Saved Look Lab settings per mode. Anything not saved uses the built-in look. */
object LookSettings {

    /** One adjustable knob: how to read/write it on Params, its range, and how to label the value. */
    class Knob(
        val key: String, val label: String, val min: Float, val max: Float,
        val get: (Look.Params) -> Float, val set: (Look.Params, Float) -> Look.Params,
        val fmt: (Float) -> String = { "%.2f".format(it) },
        val nightOnly: Boolean = false,
    )

    private fun signed(v: Float, mul: Float = 100f) = (if (v > 0.0005f) "+" else "") + "%.0f".format(v * mul)

    val knobs = listOf(
        Knob("exposure", "EXPOSURE", -2f, 2f, { it.exposure }, { p, v -> p.copy(exposure = v) }, { (if (it > 0) "+" else "") + "%.1f EV".format(it) }),
        Knob("warmth", "WARMTH", -1f, 1f, { it.warmth }, { p, v -> p.copy(warmth = v) }, { signed(it) }),
        Knob("tint", "TINT", -1f, 1f, { it.tint }, { p, v -> p.copy(tint = v) }, { v -> if (v > 0.005f) "MAG " + signed(v) else if (v < -0.005f) "GRN " + signed(v) else "0" }),
        Knob("contrast", "CONTRAST", -1f, 1f, { it.contrast }, { p, v -> p.copy(contrast = v) }, { signed(it) }),
        Knob("fade", "FADE", 0f, 0.15f, { it.fade }, { p, v -> p.copy(fade = v) }, { "%.0f".format(it * 1000) }),
        Knob("saturation", "SATURATION", 0.5f, 1.6f, { it.saturation }, { p, v -> p.copy(saturation = v) }, { "%.0f%%".format(it * 100) }),
        Knob("wbStrength", "AUTO WB", 0f, 1.5f, { it.wbStrength }, { p, v -> p.copy(wbStrength = v) }, { "%.0f%%".format(it * 100) }),
        Knob("grain", "GRAIN", 0f, 0.15f, { it.grain }, { p, v -> p.copy(grain = v) }, { "%.0f".format(it * 1000) }),
        Knob("grainSize", "GRAIN SIZE", 1f, 3f, { it.grainSize }, { p, v -> p.copy(grainSize = v) }, { "%.1f".format(it) }),
        Knob("halation", "HALATION", 0f, 0.4f, { it.halation }, { p, v -> p.copy(halation = v) }, { "%.0f".format(it * 100) }),
        Knob("stars", "STAR LIGHTS", 0f, 1f, { it.stars }, { p, v -> p.copy(stars = v) }, { "%.0f".format(it * 100) }),
        Knob("vignette", "VIGNETTE", 0f, 0.5f, { it.vignette }, { p, v -> p.copy(vignette = v) }, { "%.0f".format(it * 100) }),
        Knob("soften", "SHARPNESS", 0.5f, 1f, { it.soften }, { p, v -> p.copy(soften = v) }, { "%.0f".format((it - 0.5f) * 200) }),
    )

    private fun key(mode: Look.Mode) = if (mode == Look.Mode.AM) "look_night_v2" else "look_day_v2"
    private fun prefs(c: Context) = c.getSharedPreferences("flashcam_look", Context.MODE_PRIVATE)

    /** Built-in look for the mode (what "RESET TO DEFAULTS" shows). */
    fun builtIn(mode: Look.Mode, drag: Boolean = false) = Look.Params.forMode(mode, drag)

    /** Look actually used for new shots: built-in + any saved Look Lab changes. */
    fun params(c: Context, mode: Look.Mode, drag: Boolean = false): Look.Params {
        var p = builtIn(mode, drag)
        val s = prefs(c).getString(key(mode), null) ?: return p
        val o = try { JSONObject(s) } catch (_: Throwable) { return p }
        for (k in knobs) if (o.has(k.key)) p = k.set(p, o.optDouble(k.key).toFloat())
        return p
    }

    fun isCustom(c: Context, mode: Look.Mode) = prefs(c).getString(key(mode), null) != null

    fun save(c: Context, mode: Look.Mode, p: Look.Params) {
        val o = JSONObject()
        for (k in knobs) o.put(k.key, k.get(p).toDouble())
        prefs(c).edit().putString(key(mode), o.toString()).apply()
    }

    fun clear(c: Context, mode: Look.Mode) { prefs(c).edit().remove(key(mode)).apply() }

    /** Text you can send to Claude to bake into the official look. */
    fun export(mode: Look.Mode, p: Look.Params): String =
        (if (mode == Look.Mode.AM) "NIGHT" else "DAY") + " look: " + knobs.joinToString(", ") { "${it.key}=%.3f".format(it.get(p)) }
}
