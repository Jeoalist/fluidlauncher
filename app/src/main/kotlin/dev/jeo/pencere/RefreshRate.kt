package dev.jeo.pencere

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/** Refresh rates the built-in screen offers at its current resolution, fastest first. */
fun Context.supportedRefreshRates(): List<Int> {
    val display = builtInDisplay() ?: return emptyList()
    return sameResolutionModes(display).map { it.refreshRate.roundToInt() }.distinct().sortedDescending()
}

/** The rate chosen in the settings, or the fastest one when nothing was chosen. */
fun Context.chosenRefreshRate(): Int {
    val chosen = LauncherPrefs(this).refreshRate
    val supported = supportedRefreshRates()
    return if (chosen in supported) chosen else supported.firstOrNull() ?: DEFAULT_RATE
}

/**
 * Asks for the chosen rate. Without this some phones, such as realme ones, run a launcher they do
 * not know at 60 Hz.
 */
fun WindowManager.LayoutParams.preferChosenMode(context: Context) {
    val display = context.builtInDisplay() ?: return
    val rate = context.chosenRefreshRate()
    val mode = sameResolutionModes(display).minByOrNull { abs(it.refreshRate - rate) } ?: return
    preferredDisplayModeId = mode.modeId
}

fun Activity.preferChosenMode() {
    window.attributes = window.attributes.apply { preferChosenMode(this@preferChosenMode) }
}

private fun sameResolutionModes(display: Display): List<Display.Mode> {
    val current = display.mode
    return display.supportedModes.filter {
        it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
    }
}

// An application context has no display of its own, so this asks for the built-in one.
private fun Context.builtInDisplay(): Display? =
    getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

private const val DEFAULT_RATE = 60
