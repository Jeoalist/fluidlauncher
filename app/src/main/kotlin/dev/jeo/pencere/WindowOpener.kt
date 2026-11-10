package dev.jeo.pencere

import android.app.ActivityOptions
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager

/** [scale] sizes scaled windows; the width and height ratios size system freeform windows. */
enum class WindowSize(val widthRatio: Float, val heightRatio: Float, val scale: Float, val labelRes: Int) {
    SMALL(0.62f, 0.38f, 0.42f, R.string.window_size_small),
    MEDIUM(0.78f, 0.50f, 0.55f, R.string.window_size_medium),
    LARGE(0.92f, 0.66f, 0.72f, R.string.window_size_large),
}

/** Opens apps through the system freeform window manager, cascading new windows. */
class WindowOpener(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    fun open(app: AppEntry, windowed: Boolean, size: WindowSize, source: Rect?) {
        val options = if (windowed) freeformOptions(size) else null
        launcherApps.startMainActivity(app.component, app.user, source, options)
    }

    private fun freeformOptions(size: WindowSize): Bundle =
        ActivityOptions.makeBasic()
            .setLaunchBounds(nextBounds(size))
            .toBundle()
            // ActivityOptions has no public setter for the windowing mode, but reads this key.
            .apply { putInt(KEY_WINDOWING_MODE, WINDOWING_MODE_FREEFORM) }

    private fun nextBounds(size: WindowSize): Rect {
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val screen = metrics.bounds
        val bars = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        val width = (screen.width() * size.widthRatio).toInt()
        val height = (screen.height() * size.heightRatio).toInt()
        val freeX = screen.width() - width
        val freeY = screen.height() - height - bars.top - bars.bottom
        val step = (screen.width() * CASCADE_STEP).toInt()
        val left = if (freeX > 0) (freeX / 2 + cascade * step) % freeX else 0
        val top = bars.top + if (freeY > 0) (freeY / 3 + cascade * step) % freeY else 0
        cascade = (cascade + 1) % CASCADE_LENGTH
        return Rect(left, top, left + width, top + height)
    }

    private companion object {
        /** Shared because the launcher and the search screen each create their own opener. */
        var cascade = 0

        const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
        const val WINDOWING_MODE_FREEFORM = 5
        const val CASCADE_STEP = 0.06f
        const val CASCADE_LENGTH = 6
    }
}
