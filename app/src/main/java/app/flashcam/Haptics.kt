package app.flashcam

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** The little "click" you feel on each slider notch. */
object Haptics {
    fun tick(v: View) {
        val c = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        v.performHapticFeedback(c)
    }
}
