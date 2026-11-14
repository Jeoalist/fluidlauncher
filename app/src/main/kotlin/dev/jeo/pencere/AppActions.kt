package dev.jeo.pencere

import android.app.Activity
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Keeps the system navigation hidden; the status bar is only hidden for a full-screen window. */
fun Activity.applySystemBars(hideStatusBar: Boolean) {
    val controller = window.insetsController ?: return
    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsets.Type.navigationBars())
    if (hideStatusBar) {
        controller.hide(WindowInsets.Type.statusBars())
    } else {
        controller.show(WindowInsets.Type.statusBars())
    }
}

/** [newWindow] opens another copy instead of bringing an existing window forward. */
fun Activity.launchApp(app: AppEntry, asWindow: Boolean, source: View, newWindow: Boolean = true) {
    val bounds = Rect().also(source::getGlobalVisibleRect)
    val prefs = LauncherPrefs(this)
    // An app switched to full screen in its menu always opens that way.
    val windowed = asWindow && !prefs.opensFullscreen(app.key)
    try {
        val scalable = windowed && app.user == Process.myUserHandle()
        // Over an app in real full screen the window floats above it instead of going home.
        val floating = scalable && MainActivity.isCovered && FloatingWindows.open(this, app, newWindow)
        val scaled = floating || scalable && AppWindows.current?.open(app, newWindow) == true
        if (scaled && !floating && MainActivity.isCovered) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (!scaled) WindowOpener(this).open(app, windowed, prefs.windowSize, bounds)
        prefs.recordOpened(app.key)
    } catch (_: RuntimeException) {
        Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * What holding an app offers: a switch that makes the app always open in full screen (off, the
 * default, opens it in a window), app info and uninstall. [onDone] runs after an entry is used;
 * [onLaunched] after one that leaves the screen, so a search screen can dismiss itself.
 */
fun Activity.appMenuContent(app: AppEntry, showTitle: Boolean, onDone: () -> Unit, onLaunched: () -> Unit): LinearLayout {
    val prefs = LauncherPrefs(this)
    val theme = LauncherTheme.load(prefs)
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(if (showTitle) 12 else 6), dp(10), dp(6))
        background = CardDrawable(theme.surface.withAlpha(0.97f), dp(18).toFloat(), density, intArrayOf(theme.accent, theme.accent.darker()))
    }
    if (showTitle) {
        content.addView(TextView(this).apply {
            text = app.label
            textSize = 13f
            typeface = theme.typeface
            setTextColor(theme.muted)
            setPadding(0, 0, 0, dp(4))
        })
    }
    content.addView(Switch(this).apply {
        text = getString(R.string.menu_open_fullscreen)
        textSize = 16f
        typeface = theme.typeface
        setTextColor(theme.text)
        isChecked = prefs.opensFullscreen(app.key)
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(theme.accent, theme.muted),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(theme.accent.withAlpha(0.5f), theme.muted.withAlpha(0.35f)),
        )
        switchPadding = dp(16)
        minHeight = dp(48)
        setOnCheckedChangeListener { view, checked ->
            prefs.setOpensFullscreen(app.key, checked)
            // The line under the switch says what the current choice does.
            val parent = view.parent as? LinearLayout ?: return@setOnCheckedChangeListener
            (parent.getChildAt(parent.indexOfChild(view) + 1) as? TextView)?.setText(fullscreenHint(checked))
        }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    content.addView(TextView(this).apply {
        setText(fullscreenHint(prefs.opensFullscreen(app.key)))
        textSize = 12f
        typeface = theme.typeface
        setTextColor(theme.muted)
        setPadding(0, 0, dp(8), dp(4))
    })
    fun entry(textRes: Int, onClick: () -> Unit) = content.addView(TextView(this).apply {
        text = getString(textRes)
        textSize = 16f
        typeface = theme.typeface
        setTextColor(theme.text)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(46)
        setOnClickListener {
            onDone()
            onClick()
            onLaunched()
        }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    entry(R.string.app_info) {
        getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, app.user, null, null)
    }
    entry(R.string.uninstall) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
    }
    return content
}

/** The same menu as a small window next to [anchor], for the search popup and the drawer. */
fun Activity.showAppMenu(app: AppEntry, anchor: View, onLaunched: () -> Unit = {}) {
    val density = resources.displayMetrics.density
    lateinit var popup: PopupWindow
    val content = appMenuContent(app, showTitle = true, onDone = { popup.dismiss() }, onLaunched = onLaunched)
    popup = PopupWindow(content, (260 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
        elevation = 8 * density
        setBackgroundDrawable(null)
    }
    popup.showAsDropDown(anchor)
}

private fun fullscreenHint(fullscreen: Boolean) =
    if (fullscreen) R.string.menu_open_fullscreen_hint_on else R.string.menu_open_fullscreen_hint
