package dev.jeo.pencere

import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.content.Intent
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
        FloatingAppsButton.suppressed = true
        FloatingAppsButton.hide(this)
    }

    override fun onStop() {
        super.onStop()
        FloatingAppsButton.suppressed = false
        // Back over an app in real full screen, which needs the button again.
        if (MainActivity.isCovered) FloatingAppsButton.show(this)
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
        if (requestCode != PICK_WALLPAPER || resultCode != RESULT_OK || uri == null) return
        // Copying a large image takes a moment; the row shows the result once it is done.
        Thread {
            val saved = runCatching { ScrollingWallpaper.save(this, uri) }.isSuccess
            runOnUiThread {
                if (!saved) Toast.makeText(this, R.string.wide_wallpaper_failed, Toast.LENGTH_SHORT).show()
                if (!isDestroyed) render()
            }
        }.start()
    }

    private fun choose(titleRes: Int, options: List<String>, selected: Int, onChosen: (Int) -> Unit) {
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
        const val TITLE_SP = 24f
        const val HEADER_SP = 14f
        const val ROW_SP = 17f
        const val VALUE_SP = 14f
        const val SECONDARY_ALPHA = 0.7f
    }
}
