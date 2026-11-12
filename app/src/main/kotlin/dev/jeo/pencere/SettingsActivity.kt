package dev.jeo.pencere

import android.text.style.TypefaceSpan
import android.text.SpannableString
import android.graphics.Typeface
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The launcher's own settings, opened by holding an empty spot on the desktop. */
class SettingsActivity : Activity() {
    private val prefs by lazy { LauncherPrefs(this) }
    private lateinit var list: LinearLayout
    private var padding = 0

    override fun onStart() {
        super.onStart()
        DisplayBridge.addListener(serviceListener)
        FloatingAppsButton.ownScreenStarted(this)
    }

    override fun onStop() {
        super.onStop()
        DisplayBridge.removeListener(serviceListener)
        FloatingAppsButton.ownScreenStopped(this)
    }

    override fun onResume() {
        super.onResume()
        preferChosenMode()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padding = (16 * resources.displayMetrics.density).toInt()
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    private fun render() {
        list.removeAllViews()
        addText(getString(R.string.settings_title), TITLE_SP, padding * 2)
        addDisplayService()
        addAppearance()
        addHeader(R.string.settings_windows)
        WindowGesture.entries.forEach { gesture ->
            val current = prefs.windowAction(gesture)
            addRow(getString(gesture.titleRes), getString(current.labelRes)) {
                choose(gesture.titleRes, WindowAction.entries.map { getString(it.labelRes) }, current.ordinal) {
                    prefs.setWindowAction(gesture, WindowAction.entries[it])
                }
            }
        }
        addHeader(R.string.settings_general)
        val windowed = prefs.windowed
        val openChoices = listOf(R.string.mode_window, R.string.mode_fullscreen)
        addRow(getString(R.string.setting_open_apps), getString(openChoices[if (windowed) 0 else 1])) {
            choose(R.string.setting_open_apps, openChoices.map(::getString), if (windowed) 0 else 1) {
                prefs.windowed = it == 0
            }
        }
        val size = prefs.windowSize
        addRow(getString(R.string.setting_window_size), getString(size.labelRes)) {
            choose(R.string.setting_window_size, WindowSize.entries.map { getString(it.labelRes) }, size.ordinal) {
                prefs.windowSize = WindowSize.entries[it]
            }
        }
        val rates = supportedRefreshRates()
        if (rates.size > 1) {
            val current = chosenRefreshRate()
            addRow(getString(R.string.setting_refresh_rate), getString(R.string.refresh_rate_hz, current)) {
                choose(R.string.setting_refresh_rate, rates.map { getString(R.string.refresh_rate_hz, it) }, rates.indexOf(current)) {
                    prefs.refreshRate = rates[it]
                    preferChosenMode()
                }
            }
        }
        val opensDrawer = prefs.appsButtonOpensDrawer
        val tapChoices = listOf(R.string.apps_button_search, R.string.apps_button_drawer)
        addRow(getString(R.string.setting_button_tap), getString(tapChoices[if (opensDrawer) 1 else 0])) {
            choose(R.string.setting_button_tap, tapChoices.map(::getString), if (opensDrawer) 1 else 0) {
                prefs.appsButtonOpensDrawer = it == 1
            }
        }
        val floatingValue = when {
            !FloatingAppsButton.canShow(this) -> R.string.floating_needs_permission
            prefs.floatingButton -> R.string.on
            else -> R.string.off
        }
        addRow(getString(R.string.setting_floating_button), getString(floatingValue)) {
            if (FloatingAppsButton.canShow(this)) {
                prefs.floatingButton = !prefs.floatingButton
                render()
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        val closeOnHold = prefs.closeBackgroundOnHold
        val buttonChoices = listOf(R.string.action_close_background, R.string.action_none)
        addRow(getString(R.string.setting_button_hold), getString(buttonChoices[if (closeOnHold) 0 else 1])) {
            choose(R.string.setting_button_hold, buttonChoices.map(::getString), if (closeOnHold) 0 else 1) {
                prefs.closeBackgroundOnHold = it == 0
            }
        }
        addLanguageRow()
        addRow(getString(R.string.setting_tutorial), null) {
            prefs.tutorialDone = false
            finish()
        }
        val hasWide = ScrollingWallpaper.exists(this)
        addRow(getString(R.string.setting_wide_wallpaper), getString(if (hasWide) R.string.wide_wallpaper_set else R.string.wide_wallpaper_none)) {
            if (!hasWide) {
                pickWideWallpaper()
            } else {
                val choices = listOf(R.string.wide_wallpaper_pick, R.string.wide_wallpaper_remove)
                choose(R.string.setting_wide_wallpaper, choices.map(::getString), -1) {
                    if (it == 0) pickWideWallpaper() else ScrollingWallpaper.remove(this)
                }
            }
        }
        addRow(getString(R.string.wallpaper), null) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.wallpaper)))
        }
        addRow(getString(R.string.settings), null) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        addRow(getString(R.string.licenses_title), null) { startActivity(Intent(this, LicensesActivity::class.java)) }
    }

    /** Language names are shown in their own language, so each is readable to its speakers. */
    private fun addLanguageRow() {
        val localeManager = getSystemService(LocaleManager::class.java)
        val tags = listOf("", "en", "tr")
        val names = listOf(getString(R.string.language_system), "English", "Türkçe")
        val current = localeManager.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language.orEmpty()
        val selected = tags.indexOf(current).coerceAtLeast(0)
        addRow(getString(R.string.setting_language), names[selected]) {
            choose(R.string.setting_language, names, selected) {
                // The system recreates the screens in the new language.
                localeManager.applicationLocales = LocaleList.forLanguageTags(tags[it])
            }
        }
    }

    private fun pickWideWallpaper() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        @Suppress("DEPRECATION")
        startActivityForResult(pick, PICK_WALLPAPER)
    }

    @Deprecated("Plain activities have no result API replacement")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == PICK_FONT) {
            Thread {
                val key = runCatching { FontStore.import(this, uri) }.getOrNull()
                runOnUiThread {
                    if (key == null) {
                        Toast.makeText(this, R.string.font_import_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        prefs.fontKey = key
                    }
                    if (!isDestroyed) render()
                }
            }.start()
            return
        }
        if (requestCode != PICK_WALLPAPER) return
        // Copying a large image takes a moment; the row shows the result once it is done.
        Thread {
            val saved = runCatching { ScrollingWallpaper.save(this, uri) }.isSuccess
            runOnUiThread {
                if (!saved) Toast.makeText(this, R.string.wide_wallpaper_failed, Toast.LENGTH_SHORT).show()
                if (!isDestroyed) render()
            }
        }.start()
    }

    /**
     * The display service that scales windows: its state, pairing with Wireless debugging (done
     * once) and whether it can start by itself after a reboot.
     */
    private fun addDisplayService() {
        addHeader(R.string.settings_service)
        val state = DisplayBridge.state
        val stateText = when (state) {
            DisplayBridge.State.READY -> R.string.service_state_running
            DisplayBridge.State.STARTING -> R.string.service_state_starting
            DisplayBridge.State.NOT_RUNNING -> R.string.service_state_stopped
            DisplayBridge.State.NEEDS_PAIRING -> R.string.service_state_unpaired
        }
        addRow(getString(R.string.setting_service_state), getString(stateText)) {
            if (state == DisplayBridge.State.NOT_RUNNING) {
                DisplayBridge.startNow { result ->
                    if (result != DisplayStarter.Result.STARTED) {
                        val message = when (result) {
                            DisplayStarter.Result.NO_WIRELESS_DEBUGGING -> R.string.service_needs_wireless_debugging
                            DisplayStarter.Result.NEEDS_PAIRING -> R.string.service_needs_pairing
                            else -> R.string.pairing_start_failed
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        val paired = prefs.adbPaired
        addRow(getString(R.string.setting_pairing), getString(if (paired) R.string.pairing_state_paired else R.string.pairing_state_unpaired)) {
            startPairing()
        }
        val autoStart = DisplayBridge.canSwitchWirelessDebugging(this)
        addRow(getString(R.string.setting_auto_start), getString(if (autoStart) R.string.on else R.string.auto_start_after_pairing)) {}
    }

    /**
     * Pairing needs the code from the system's own dialog, typed into a notification, so this asks
     * for notifications first, then starts listening and opens the developer options.
     */
    private fun startPairing() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS_REQUEST)
            return
        }
        PairingService.start(this)
        Toast.makeText(this, R.string.pairing_instructions, Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }

    private val serviceListener: () -> Unit = { if (!isDestroyed) render() }

    private fun addAppearance() {
        addHeader(R.string.settings_appearance)
        val layout = prefs.desktopLayout
        addRow(getString(R.string.setting_layout), getString(layout.labelRes)) {
            choose(R.string.setting_layout, DesktopLayout.entries.map { getString(it.labelRes) }, layout.ordinal) {
                prefs.desktopLayout = DesktopLayout.entries[it]
            }
        }
        val theme = LauncherTheme.load(prefs)
        val palette = prefs.palette
        addRow(getString(R.string.setting_palette), getString(palette.labelRes)) {
            choose(R.string.setting_palette, Palette.entries.map { dotted(getString(it.labelRes), it.clock, it.accent) }, palette.ordinal) {
                prefs.palette = Palette.entries[it]
            }
        }
        addColorRow(R.string.setting_accent, theme.accent, prefs.accentColor == null) { prefs.accentColor = it }
        addColorRow(R.string.setting_clock_color, theme.clock, prefs.clockColor == null) { prefs.clockColor = it }
        addFontRows()
        val grid = prefs.panelGrid
        val listChoices = listOf(R.string.panel_as_list, R.string.panel_as_grid)
        addRow(getString(R.string.setting_panel_layout), getString(listChoices[if (grid) 1 else 0])) {
            choose(R.string.setting_panel_layout, listChoices.map(::getString), if (grid) 1 else 0) {
                prefs.panelGrid = it == 1
            }
        }
        val shade = prefs.iconShade
        addRow(getString(R.string.setting_icon_shade), getString(shade.labelRes)) {
            choose(R.string.setting_icon_shade, IconShade.entries.map { getString(it.labelRes) }, shade.ordinal) {
                prefs.iconShade = IconShade.entries[it]
            }
        }
        val notifications = NotificationCounter.hasAccess(this)
        addRow(getString(R.string.setting_notification_access), getString(if (notifications) R.string.on else R.string.off)) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val calendar = CalendarEvents.canRead(this)
        addRow(getString(R.string.setting_calendar), getString(if (calendar) R.string.on else R.string.off)) {
            if (calendar || shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) || !prefs.calendarAsked) {
                prefs.calendarAsked = true
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), CALENDAR_REQUEST)
            } else {
                // Android stops asking after a refusal, so the app's own settings page is the way back.
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    /**
     * Built-in and imported fonts, each shown in its own face, and a last entry that imports a
     * new font file. A second row removes imported fonts once there are any.
     */
    private fun addFontRows() {
        val current = prefs.fontKey
        val imported = FontStore.imported(this)
        val keys = FontChoice.entries.map { it.name } + imported.map(FontStore::keyOf)
        addRow(getString(R.string.setting_font), FontStore.label(this, current)) {
            val options = keys.map { key -> inFace(FontStore.label(this, key), FontStore.typeface(this, key)) } +
                getString(R.string.font_import)
            choose(R.string.setting_font, options, keys.indexOf(current)) {
                if (it < keys.size) prefs.fontKey = keys[it] else pickFont()
            }
        }
        if (imported.isNotEmpty()) {
            addRow(getString(R.string.setting_remove_font), getString(R.string.fonts_imported, imported.size)) {
                choose(R.string.setting_remove_font, imported.map(FontStore::nameOf), -1) {
                    val file = imported[it]
                    if (prefs.fontKey == FontStore.keyOf(file)) prefs.fontKey = FontChoice.MONOSPACE.name
                    FontStore.delete(this, file)
                }
            }
        }
    }

    private fun inFace(text: String, typeface: Typeface): CharSequence =
        SpannableString(text).apply { setSpan(TypefaceSpan(typeface), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    private fun pickFont() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, FONT_TYPES)
        @Suppress("DEPRECATION")
        startActivityForResult(pick, PICK_FONT)
    }

    /** First choice follows the palette; the rest are fixed colours. */
    private fun addColorRow(titleRes: Int, current: Int, followsPalette: Boolean, onChosen: (Int?) -> Unit) {
        val currentName = if (followsPalette) {
            getString(R.string.color_from_palette)
        } else {
            Swatch.entries.firstOrNull { it.color == current }?.let { getString(it.labelRes) } ?: "#%06X".format(current and 0xFFFFFF)
        }
        addRow(getString(titleRes), currentName) {
            val options = listOf<CharSequence>(getString(R.string.color_from_palette)) +
                Swatch.entries.map { dotted(getString(it.labelRes), it.color) }
            val selected = if (followsPalette) 0 else Swatch.entries.indexOfFirst { it.color == current } + 1
            choose(titleRes, options, selected) { onChosen(if (it == 0) null else Swatch.entries[it - 1].color) }
        }
    }

    /** A name led by coloured dots, so colours can be told apart in the list. */
    private fun dotted(name: String, vararg colors: Int): CharSequence {
        val text = SpannableStringBuilder()
        colors.forEach { color ->
            val start = text.length
            text.append("● ")
            text.setSpan(ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text.append(name)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATIONS_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startPairing()
        render()
    }

    private fun choose(titleRes: Int, options: List<CharSequence>, selected: Int, onChosen: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                onChosen(which)
                dialog.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addHeader(textRes: Int) {
        addText(getString(textRes), HEADER_SP, padding).apply { alpha = SECONDARY_ALPHA }
    }

    private fun addText(text: String, sizeSp: Float, topPadding: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setPadding(padding, topPadding, padding, padding / 2)
        list.addView(this)
    }

    private fun addRow(title: String, value: String?, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 3 / 4, padding, padding * 3 / 4)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_SP)
        })
        if (value != null) {
            row.addView(TextView(this).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, VALUE_SP)
                alpha = SECONDARY_ALPHA
            })
        }
        list.addView(row)
        list.addView(View(this).apply { minimumHeight = 1 })
    }

    private companion object {
        const val PICK_WALLPAPER = 1
        const val CALENDAR_REQUEST = 2
        const val PICK_FONT = 3
        const val NOTIFICATIONS_REQUEST = 4

        // Font files arrive under many types; some file managers only offer them as plain data.
        val FONT_TYPES = arrayOf(
            "font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
            "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream",
        )
        const val TITLE_SP = 24f
        const val HEADER_SP = 14f
        const val ROW_SP = 17f
        const val VALUE_SP = 14f
        const val SECONDARY_ALPHA = 0.7f
    }
}
