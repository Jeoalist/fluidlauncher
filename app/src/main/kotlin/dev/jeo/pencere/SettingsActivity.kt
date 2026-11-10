package dev.jeo.pencere

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The launcher's own settings, opened by holding an empty spot on the desktop. */
class SettingsActivity : Activity() {
    private val prefs by lazy { LauncherPrefs(this) }
    private lateinit var list: LinearLayout
    private var padding = 0

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
        render()
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
        val size = prefs.windowSize
        addRow(getString(R.string.setting_window_size), getString(size.labelRes)) {
            choose(R.string.setting_window_size, WindowSize.entries.map { getString(it.labelRes) }, size.ordinal) {
                prefs.windowSize = WindowSize.entries[it]
            }
        }
        val closeOnHold = prefs.closeBackgroundOnHold
        val buttonChoices = listOf(R.string.action_close_background, R.string.action_none)
        addRow(getString(R.string.setting_button_hold), getString(buttonChoices[if (closeOnHold) 0 else 1])) {
            choose(R.string.setting_button_hold, buttonChoices.map(::getString), if (closeOnHold) 0 else 1) {
                prefs.closeBackgroundOnHold = it == 0
            }
        }
        addRow(getString(R.string.wallpaper), null) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.wallpaper)))
        }
        addRow(getString(R.string.settings), null) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
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
        const val TITLE_SP = 24f
        const val HEADER_SP = 14f
        const val ROW_SP = 17f
        const val VALUE_SP = 14f
        const val SECONDARY_ALPHA = 0.7f
    }
}
