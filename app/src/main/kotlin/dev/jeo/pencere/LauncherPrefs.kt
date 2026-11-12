package dev.jeo.pencere

import android.content.Context

class LauncherPrefs(context: Context) {
    /** Kept for settings that point at the app's own files, such as imported fonts. */
    val appContext: Context = context.applicationContext
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

    /** Changes whenever the scrolling wallpaper is picked or removed, so the desktop reloads it. */
    var wallpaperVersion: Long
        get() = prefs.getLong(KEY_WALLPAPER_VERSION, 0L)
        set(value) = prefs.edit().putLong(KEY_WALLPAPER_VERSION, value).apply()

    var desktopLayout: DesktopLayout
        get() = enumOr(KEY_LAYOUT, DesktopLayout.TRAY)
        set(value) = bumpTheme { putString(KEY_LAYOUT, value.name) }

    /** Picking a palette drops hand-picked colours, so the palette shows as designed. */
    var palette: Palette
        get() = enumOr(KEY_PALETTE, Palette.MEADOW)
        set(value) = bumpTheme { putString(KEY_PALETTE, value.name).remove(KEY_ACCENT).remove(KEY_CLOCK) }

    var accentColor: Int?
        get() = if (prefs.contains(KEY_ACCENT)) prefs.getInt(KEY_ACCENT, 0) else null
        set(value) = bumpTheme { if (value == null) remove(KEY_ACCENT) else putInt(KEY_ACCENT, value) }

    var clockColor: Int?
        get() = if (prefs.contains(KEY_CLOCK)) prefs.getInt(KEY_CLOCK, 0) else null
        set(value) = bumpTheme { if (value == null) remove(KEY_CLOCK) else putInt(KEY_CLOCK, value) }

    var iconShade: IconShade
        get() = enumOr(KEY_ICON_SHADE, IconShade.FULL)
        set(value) = bumpTheme { putString(KEY_ICON_SHADE, value.name) }

    /** A built-in font's name, or an imported font's key from [FontStore]. */
    var fontKey: String
        get() = prefs.getString(KEY_FONT, null) ?: FontChoice.MONOSPACE.name
        set(value) = bumpTheme { putString(KEY_FONT, value) }

    /** Changes with every theme setting, so the desktop knows to redraw itself. */
    val themeVersion: Long get() = prefs.getLong(KEY_THEME_VERSION, 0L)

    /** Sort the app list panel by recent use instead of by name. */
    var panelByRecent: Boolean
        get() = prefs.getBoolean(KEY_PANEL_RECENT, false)
        set(value) = prefs.edit().putBoolean(KEY_PANEL_RECENT, value).apply()

    var panelGrid: Boolean
        get() = prefs.getBoolean(KEY_PANEL_GRID, false)
        set(value) = bumpTheme { putBoolean(KEY_PANEL_GRID, value) }




    /** Whether the calendar permission was already asked for, so it is asked only once. */
    var calendarAsked: Boolean
        get() = prefs.getBoolean(KEY_CALENDAR_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_CALENDAR_ASKED, value).apply()

    private inline fun <reified T : Enum<T>> enumOr(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private inline fun bumpTheme(change: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(change).putLong(KEY_THEME_VERSION, System.nanoTime()).apply()
    }

    /** Whether adbd trusts the launcher's key, from pairing with Wireless debugging. */
    var adbPaired: Boolean
        get() = prefs.getBoolean(KEY_ADB_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_ADB_PAIRED, value).apply()

    /** Apps switched to full screen in their menu; every other app opens in a window. */
    fun opensFullscreen(key: String) = key in prefs.getStringSet(KEY_FULLSCREEN_APPS, emptySet())!!

    fun setOpensFullscreen(key: String, fullscreen: Boolean) {
        val apps = prefs.getStringSet(KEY_FULLSCREEN_APPS, emptySet())!!.toMutableSet()
        if (fullscreen) apps += key else apps -= key
        prefs.edit().putStringSet(KEY_FULLSCREEN_APPS, apps).apply()
    }

    /** Whether windows are tiled on each desktop instead of pushing each other. */
    var tiling: Boolean
        get() = prefs.getBoolean(KEY_TILING, false)
        set(value) = prefs.edit().putBoolean(KEY_TILING, value).apply()

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
        const val KEY_WALLPAPER_VERSION = "wallpaper_version"
        const val KEY_LAYOUT = "desktop_layout"
        const val KEY_PALETTE = "palette"
        const val KEY_ACCENT = "accent_color"
        const val KEY_CLOCK = "clock_color"
        const val KEY_FONT = "font"
        const val KEY_TILING = "tiling"
        const val KEY_FULLSCREEN_APPS = "fullscreen_apps"
        const val KEY_ADB_PAIRED = "adb_paired"
        const val KEY_ICON_SHADE = "icon_shade"
        const val KEY_THEME_VERSION = "theme_version"
        const val KEY_PANEL_RECENT = "panel_recent"
        const val KEY_PANEL_GRID = "panel_grid"
        const val KEY_CALENDAR_ASKED = "calendar_asked"
        const val KEY_FLOATING_X = "floating_x"
        const val KEY_FLOATING_Y = "floating_y"
        const val SEPARATOR = "|"
        const val MAX_RECENTS = 7
    }
}
