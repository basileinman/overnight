package app.flashcam

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Decides if it's daytime from the clock alone (no location permission).
 * Uses a standard sunrise/sunset calculation. Longitude is estimated from the
 * phone's time zone; latitude defaults to 35°N, which covers most of the US well.
 * Seasons are handled: summer evenings stay DAY longer than winter ones.
 */
object SunClock {
    private const val DEFAULT_LAT = 35.0
    // treat the first/last ~20 minutes around sunrise/sunset as night (it's already dim)
    private const val MARGIN_MIN = 20.0

    fun isDaytime(now: Calendar = Calendar.getInstance()): Boolean {
        val tz: TimeZone = now.timeZone
        val stdOffsetH = tz.rawOffset / 3_600_000.0
        val dstH = if (tz.inDaylightTime(now.time)) tz.dstSavings / 3_600_000.0 else 0.0
        val lon = stdOffsetH * 15.0
        val lat = Math.toRadians(DEFAULT_LAT)

        val day = now.get(Calendar.DAY_OF_YEAR)
        val g = 2 * Math.PI / 365.0 * (day - 1)
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
            0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val cosH = (cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl))) - tan(lat) * tan(decl)
        if (cosH <= -1) return true   // midnight sun
        if (cosH >= 1) return false   // polar night
        val ha = Math.toDegrees(acos(cosH))

        val noonMin = 720 - 4 * lon - eqTime + (stdOffsetH + dstH) * 60
        val sunrise = noonMin - 4 * ha + MARGIN_MIN
        val sunset = noonMin + 4 * ha - MARGIN_MIN
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60.0 + now.get(Calendar.MINUTE)
        return nowMin in sunrise..sunset
    }
}
