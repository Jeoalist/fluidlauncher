package dev.jeo.pencere

import android.content.Context

class LauncherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("pencere", Context.MODE_PRIVATE)

    var windowed: Boolean
        get() = prefs.getBoolean(KEY_WINDOWED, true)
        set(value) = prefs.edit().putBoolean(KEY_WINDOWED, value).apply()

    /** Screen refresh rate in Hz; 0 means the fastest the screen offers. */
    var refreshRate: Int
        get() = prefs.getInt(KEY_REFRESH_RATE, 0)
        set(value) = prefs.edit().putInt(KEY_REFRESH_RATE, value).apply()

    /** Whether the first-run guide was finished or skipped. */
    var tutorialDone: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_TUTORIAL_DONE, value).apply()

    var windowSize: WindowSize
        get() = WindowSize.valueOf(prefs.getString(KEY_SIZE, WindowSize.MEDIUM.name)!!)
        set(value) = prefs.edit().putString(KEY_SIZE, value.name).apply()

    /** Button position as a 0..1 fraction of the free area, so it survives rotation. */
    var buttonX: Float
        get() = prefs.getFloat(KEY_BUTTON_X, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_BUTTON_X, value).apply()

    var buttonY: Float
        get() = prefs.getFloat(KEY_BUTTON_Y, 0.95f)
        set(value) = prefs.edit().putFloat(KEY_BUTTON_Y, value).apply()

    fun windowAction(gesture: WindowGesture): WindowAction =
        prefs.getString(gesture.key, null)
            ?.let { name -> WindowAction.entries.firstOrNull { it.name == name } }
            ?: gesture.default

    fun setWindowAction(gesture: WindowGesture, action: WindowAction) =
        prefs.edit().putString(gesture.key, action.name).apply()

    /** False opens the small search popup, the default; true opens the full app drawer. */
    var appsButtonOpensDrawer: Boolean
        get() = prefs.getBoolean(KEY_APPS_BUTTON_DRAWER, false)
        set(value) = prefs.edit().putBoolean(KEY_APPS_BUTTON_DRAWER, value).apply()

    /** Whether the apps button floats over other apps while the launcher is behind them. */
    var floatingButton: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_BUTTON, true)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_BUTTON, value).apply()

    var floatingX: Float
        get() = prefs.getFloat(KEY_FLOATING_X, 0.97f)
        set(value) = prefs.edit().putFloat(KEY_FLOATING_X, value).apply()

    var floatingY: Float
        get() = prefs.getFloat(KEY_FLOATING_Y, 0.45f)
        set(value) = prefs.edit().putFloat(KEY_FLOATING_Y, value).apply()

    var closeBackgroundOnHold: Boolean
        get() = prefs.getBoolean(KEY_CLOSE_BACKGROUND_ON_HOLD, true)
        set(value) = prefs.edit().putBoolean(KEY_CLOSE_BACKGROUND_ON_HOLD, value).apply()

    /** Keys of apps opened from this launcher, most recent first. */
    val recentKeys: List<String>
        get() = prefs.getString(KEY_RECENTS, null)?.split(SEPARATOR).orEmpty()

    fun recordOpened(key: String) {
        val updated = (listOf(key) + recentKeys.filter { it != key }).take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENTS, updated.joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val KEY_WINDOWED = "windowed"
        const val KEY_SIZE = "window_size"
        const val KEY_BUTTON_X = "button_x"
        const val KEY_BUTTON_Y = "button_y"
        const val KEY_RECENTS = "recent_keys"
        const val KEY_CLOSE_BACKGROUND_ON_HOLD = "close_background_on_hold"
        const val KEY_APPS_BUTTON_DRAWER = "apps_button_drawer"
        const val KEY_FLOATING_BUTTON = "floating_button"
        const val KEY_REFRESH_RATE = "refresh_rate"
        const val KEY_TUTORIAL_DONE = "tutorial_done"
        const val KEY_FLOATING_X = "floating_x"
        const val KEY_FLOATING_Y = "floating_y"
        const val SEPARATOR = "|"
        const val MAX_RECENTS = 7
    }
}
