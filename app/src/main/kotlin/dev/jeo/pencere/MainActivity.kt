package dev.jeo.pencere

import android.content.IntentFilter
import android.content.Context
import android.content.BroadcastReceiver
import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.LauncherApps
import android.graphics.Insets
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : Activity() {
    private val prefs by lazy { LauncherPrefs(this) }
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }
    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: View
    private lateinit var desktop: View
    private lateinit var appsButton: View
    private lateinit var statusPill: TextView
    private lateinit var windowLayer: FrameLayout
    private lateinit var desktopLabel: TextView
    private lateinit var windows: AppWindows

    private var systemBars: Insets = Insets.NONE
    private var margin = 0
    private var draggingButton = false
    private val serviceStateListener: () -> Unit = { renderServiceState() }
    private var launcherOnTop = false
    private var appContentTouched = false

    private enum class PanState { IDLE, ARMED, PANNING }

    private var panState = PanState.IDLE
    private var panDownX = 0f
    private var panDownY = 0f
    private var touchSlop = 0
    private var panZoneHeight = 0
    private var gestureBottomInset = 0

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reloadApps()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reloadApps()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reloadApps()
        override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = reloadApps()
        override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = reloadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        desktop = findViewById(R.id.desktop)
        appsButton = findViewById(R.id.appsButton)
        statusPill = findViewById(R.id.statusPill)
        windowLayer = findViewById(R.id.windowLayer)
        desktopLabel = findViewById(R.id.desktopLabel)
        windows = AppWindows(windowLayer, prefs)
        AppWindows.current = windows

        margin = (16 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { _, insets ->
            // The bars' room whether they show or not: they hide while a filled window is in view,
            // and following that would move every window and resize its app on each desktop turn.
            systemBars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            // A camera cutout on a side in landscape is room nothing should be placed in either.
            sideInsets = Insets.max(systemBars, insets.getInsets(WindowInsets.Type.displayCutout()))
            desktop.setPadding(
                systemBars.left + margin,
                systemBars.top + margin,
                systemBars.right + margin,
                systemBars.bottom + margin,
            )
            placeAppsButton()
            gestureBottomInset = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.mandatorySystemGestures(),
            ).bottom
            excludeBackGesture()
            keepWindowsInArea()
            tray.setInsets(sideInsets)
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (::tray.isInitialized) root.post(::updateWindowBottom)
            placeAppsButton()
            excludeBackGesture()
            keepWindowsInArea()
        }
        setUpDesktopHold()
        setUpAppsButton()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        panZoneHeight = (PAN_ZONE_HEIGHT_DP * resources.displayMetrics.density).toInt()
        windows.onDesktopChanged = {
            renderDesktopLabel(it)
            tray.setDesktop(it)
        }
        wallpaper = WallpaperView(this)
        (root as FrameLayout).addView(wallpaper, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        windows.onScrolled = ::scrollWallpaper
        setUpTray()
        windows.onFullscreenChanged = ::applyFullscreen
        renderDesktopLabel(1)
        statusPill.setOnClickListener { onStatusClicked() }
        DisplayBridge.addListener(serviceStateListener)
        DisplayBridge.start(this)
        renderServiceState()
        launcherApps.registerCallback(packageCallback, main)
        reloadApps()
    }

    /**
     * A focusable launcher is raised by the first tap on the desktop, which hides every window
     * above it, so it only accepts focus while nothing is on top of it.
     */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        launcherOnTop = isTopResumedActivity
        updateFocusable()
    }

    /**
     * Only the display's focused window can hide the system bars, so the launcher normally stays
     * focusable. It lets go while an app window is in use, or the keyboard would be tied to it.
     */
    private fun updateFocusable() {
        val keepsFocus = launcherOnTop || DisplayBridge.state == DisplayBridge.State.READY
        if (keepsFocus && !appContentTouched) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    private fun applyFullscreen(visible: Boolean) {
        applySystemBars(visible)
        keepWindowsInArea()
    }

    private fun keepWindowsInArea() {
        windows.keepInArea(systemBars.top.toFloat(), sideInsets.left.toFloat(), sideInsets.right.toFloat())
    }

    private var sideInsets: Insets = Insets.NONE

    override fun onResume() {
        super.onResume()
        preferChosenMode()
        wallpaper.refresh {}
        if (prefs.themeVersion != appliedThemeVersion) applyTheme()
        // Coming back to the desktop: the alphabet rail's letters fly in.
        if (desktopPage == 0f) tray.flyInRail()
        // The launcher is in front, so the copy of its button meant for covered moments goes away.
        FloatingAppsButton.hide(this)
        tray.refreshMinute()
        renderNotificationCount()
        askForCalendarOnce()
        if (!prefs.tutorialDone && tutorial == null) showTutorial()
        applySystemBars(windows.isFullscreenVisible)
        // Back in front, a window's display can be left drawing at a stale buffer size, showing its
        // app unscaled in a corner. The surfaces are attached afresh now and once more a little
        // later, in case the system reconfigures the displays after this.
        windows.refreshSurfaces()
        root.postDelayed({ windows.refreshSurfaces() }, SURFACE_REFRESH_DELAY_MS)
    }

    /** The home icon in the open-apps row asks for the home screen: the first desktop. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_GO_HOME, false)) windows.goToDesktop(1)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(minuteReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        startedCount++
        FloatingAppsButton.hide(this)
    }

    /** Something covers the launcher, such as an app in real full screen. */
    override fun onStop() {
        super.onStop()
        unregisterReceiver(minuteReceiver)
        // A focused search box would bring the keyboard back on its own on return.
        tray.clearSearch()
        startedCount--
        if (!isChangingConfigurations) FloatingAppsButton.show(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars(windows.isFullscreenVisible)
    }

    override fun onDestroy() {
        // A replacement instance may already have registered itself by the time this one dies.
        DisplayBridge.removeListener(serviceStateListener)
        if (AppWindows.current === windows) AppWindows.current = null
        windows.closeAll()
        tray.release()
        NotificationCounter.removeListener(notificationListener)
        launcherApps.unregisterCallback(packageCallback)
        background.shutdown()
        super.onDestroy()
    }

    private fun setUpAppsButton() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        appsButton.setOnClickListener { button ->
            val bounds = Rect().also(button::getGlobalVisibleRect)
            AppsPanel.toggle(this, bounds, newTask = false)
        }
        // One tap opens the apps; three switch between tiling and pushing windows. A single tap
        // waits a moment to be sure no more taps follow.
        var taps = 0
        val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        val dispatchTaps = Runnable {
            if (taps in 1..2) appsButton.performClick()
            taps = 0
        }
        var heldToClose = false
        val closeOnHold = Runnable {
            heldToClose = true
            appsButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            closeBackgroundApps(this, keepForeground = false)
        }
        appsButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = view.x
                    startY = view.y
                    draggingButton = false
                    heldToClose = false
                    if (prefs.closeBackgroundOnHold) view.postDelayed(closeOnHold, CLOSE_BACKGROUND_HOLD_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (hypot(dx, dy) > touchSlop) {
                        draggingButton = true
                        view.removeCallbacks(closeOnHold)
                    }
                    if (draggingButton) {
                        view.x = (startX + dx).coerceIn(buttonMinX(), buttonMaxX())
                        view.y = (startY + dy).coerceIn(buttonMinY(), buttonMaxY())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(closeOnHold)
                    if (draggingButton) {
                        saveAppsButtonPosition()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && !heldToClose) {
                        view.removeCallbacks(dispatchTaps)
                        taps++
                        if (taps >= 3) {
                            taps = 0
                            toggleTiling()
                        } else {
                            view.postDelayed(dispatchTaps, doubleTapTimeout)
                        }
                    }
                    draggingButton = false
                }
            }
            true
        }
    }

    /**
     * Lets touches reach the desktop and windows as usual, and only takes over once a drag that
     * started in the pan zone turns out to be horizontal, so the zone can be large.
     */
    /** The first-run guide, drawn over everything else while it runs. */
    private var tutorial: TutorialOverlay? = null

    private fun showTutorial() {
        val overlay = TutorialOverlay(this, prefs, appsButton) {
            tutorial?.let((root as FrameLayout)::removeView)
            tutorial = null
        }
        tutorial = overlay
        (root as FrameLayout).addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay.requestApplyInsets()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // The guide takes every touch itself, without the desktop's swipes and holds.
        if (tutorial != null) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                appContentTouched = windows.isOnContent(event.rawX, event.rawY)
                updateFocusable()
                panState = if (canStartPan(event)) PanState.ARMED else PanState.IDLE
                panDownX = event.rawX
                panDownY = event.rawY
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (panState == PanState.ARMED) panState = PanState.IDLE
            MotionEvent.ACTION_MOVE -> if (panState == PanState.ARMED) updateArmedPan(event)
        }
        if (panState != PanState.PANNING) return super.dispatchTouchEvent(event)
        val distance = event.rawX - panDownX
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> windows.pan(distance)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                windows.endPan(distance)
                panState = PanState.IDLE
            }
        }
        return true
    }

    private fun canStartPan(event: MotionEvent): Boolean {
        val zoneBottom = root.height - gestureBottomInset
        // On a short, wide screen the fixed height would cover most of it; a quarter is enough.
        val zoneHeight = minOf(panZoneHeight, root.height / 4)
        val inZone = event.rawX <= root.width * PAN_ZONE_WIDTH_RATIO &&
            event.rawY >= zoneBottom - zoneHeight && event.rawY <= zoneBottom
        val onButton = event.rawX >= appsButton.x && event.rawX <= appsButton.x + appsButton.width &&
            event.rawY >= appsButton.y && event.rawY <= appsButton.y + appsButton.height
        // The desktop picker scrubs through desktops itself.
        val onPicker = tray.isOnPicker(event.rawX, event.rawY)
        // Sideways drags inside an app (sliders, carousels) belong to the app.
        val onApp = windows.isOnContent(event.rawX, event.rawY)
        return inZone && !onButton && !onPicker && !onApp && !windows.isOnTitleBar(event.rawX, event.rawY)
    }

    private fun updateArmedPan(event: MotionEvent) {
        val dx = abs(event.rawX - panDownX)
        val dy = abs(event.rawY - panDownY)
        if (dx > touchSlop && dx > dy * PAN_DIRECTION_RATIO) {
            panState = PanState.PANNING
            windows.beginPan()
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        } else if (dy > touchSlop) {
            panState = PanState.IDLE
        }
    }

    /** The zone touches the left edge, where the system would otherwise read a back gesture. */
    private fun excludeBackGesture() {
        val bottom = root.height - gestureBottomInset
        root.systemGestureExclusionRects = listOf(Rect(0, bottom - panZoneHeight, panZoneHeight / 2, bottom))
    }

    private var desktopNumber = 1
    private lateinit var wallpaper: WallpaperView

    /** Moves our own wide wallpaper, or the system one where the system supports moving it. */
    private fun scrollWallpaper(progress: Float) {
        wallpaper.progress = progress
        followDesktops(progress * (AppWindows.DESKTOP_COUNT - 1))
        val token = root.windowToken ?: return
        WallpaperManager.getInstance(this).apply {
            setWallpaperOffsetSteps(1f / (AppWindows.DESKTOP_COUNT - 1), 0f)
            setWallpaperOffsets(token, progress, 0.5f)
        }
    }

    private fun renderDesktopLabel(number: Int) {
        desktopNumber = number
        desktopLabel.text = getString(R.string.desktop_label, number)
    }

    /** Keeps the windows alive through a language change by redrawing texts instead of restarting. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderDesktopLabel(desktopNumber)
        renderServiceState()
    }

    /** Closes launchable apps that have no window here, leaving this launcher and the home app. */
    private fun buttonMinX() = (systemBars.left + margin).toFloat()
    private fun buttonMaxX() = (root.width - systemBars.right - margin - appsButton.width).toFloat()
    private fun buttonMinY() = (systemBars.top + margin).toFloat()
    private fun buttonMaxY() = (root.height - systemBars.bottom - margin - appsButton.height).toFloat()

    private fun placeAppsButton() {
        if (draggingButton) return
        appsButton.x = buttonMinX() + (buttonMaxX() - buttonMinX()) * prefs.buttonX
        appsButton.y = buttonMinY() + (buttonMaxY() - buttonMinY()) * prefs.buttonY
    }

    private fun saveAppsButtonPosition() {
        prefs.buttonX = (appsButton.x - buttonMinX()) / (buttonMaxX() - buttonMinX())
        prefs.buttonY = (appsButton.y - buttonMinY()) / (buttonMaxY() - buttonMinY())
    }

    private fun reloadApps() {
        background.execute {
            val loaded = loadApps(this)
            main.post { AppCatalog.publish(loaded) }
        }
    }

    private fun renderServiceState() {
        val state = DisplayBridge.state
        updateFocusable()
        if (state != DisplayBridge.State.READY) {
            windows.closeAll()
            FloatingWindows.closeAll()
        }
        val message = when (state) {
            DisplayBridge.State.NEEDS_PAIRING -> R.string.service_needs_pairing
            DisplayBridge.State.NOT_RUNNING -> R.string.service_not_running
            DisplayBridge.State.STARTING -> R.string.service_starting
            DisplayBridge.State.READY -> null
        }
        statusPill.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let(statusPill::setText)
        tray.setStatus(message?.let(::getString))
    }

    /** Not paired yet: the settings explain pairing. Paired: try starting it now. */
    private fun onStatusClicked() {
        when (DisplayBridge.state) {
            DisplayBridge.State.NEEDS_PAIRING -> startActivity(Intent(this, SettingsActivity::class.java))
            DisplayBridge.State.NOT_RUNNING -> DisplayBridge.startNow()
            else -> Unit
        }
    }


    private fun toggleTiling() {
        windows.tiling = !windows.tiling
        appsButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        Toast.makeText(this, if (windows.tiling) R.string.tiling_on else R.string.tiling_off, Toast.LENGTH_SHORT).show()
    }

    /** In the tray layout windows stay above the desktop picker along the bottom. */
    private fun updateWindowBottom() {
        if (!LauncherTheme.load(prefs).isTray) {
            windows.setBottom(0f)
            return
        }
        // On a wide screen, windows clear of the picker on either side may go down to the bottom.
        val wide = root.width > root.height
        windows.setBottom(
            tray.pickerTop - margin / 2f,
            if (wide) (root.height - systemBars.bottom - margin / 2f) else 0f,
            // The picker's own edges: tiles already keep a gap, and a margin here made a tile just
            // beside the picker count as over it.
            tray.pickerLeft,
            tray.pickerRight,
        )
    }

    private fun askToOpenSettings() {
        AlertDialog.Builder(this)
            .setMessage(R.string.open_settings_question)
            .setPositiveButton(R.string.open) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Tray layout ---------------------------------------------------------------------------

    private lateinit var tray: TrayDesktop
    private var appliedThemeVersion = Long.MIN_VALUE
    private val notificationListener: () -> Unit = { main.post(::renderNotificationCount) }

    private val minuteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = tray.refreshMinute()
    }

    private fun setUpTray() {
        tray = TrayDesktop(this, prefs, object : TrayDesktop.Actions {
            override fun onDesktopPicked(number: Int) = windows.goToDesktop(number)


            override fun onStatusClicked() = this@MainActivity.onStatusClicked()

            override fun onEmptySpotHeld() = askToOpenSettings()
        })
        val layers = root as FrameLayout
        layers.addView(tray.backdrop, layers.indexOfChild(desktop) + 1, matchParent())
        layers.addView(tray.chrome, layers.indexOfChild(windowLayer) + 1, matchParent())
        NotificationCounter.addListener(notificationListener)
        applyTheme()
    }

    /** How far the desktops have moved, in desktops, for parts that belong to the first one. */
    private var desktopPage = 0f

    /**
     * In the tray layout the tray lives on the first desktop and the apps button on the others,
     * where there is no tray to open apps from; both fade with the slide between them.
     */
    private fun followDesktops(page: Float) {
        desktopPage = page
        tray.followDesktops(page)
        val tray = LauncherTheme.load(prefs).isTray
        val shown = if (tray) page.coerceIn(0f, 1f) else 1f
        appsButton.alpha = shown
        appsButton.visibility = if (shown > 0f) View.VISIBLE else View.INVISIBLE
    }

    private fun matchParent() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    /** Switches between the classic and tray desktops and restyles windows to match. */
    private fun applyTheme() {
        appliedThemeVersion = prefs.themeVersion
        val theme = LauncherTheme.load(prefs)
        desktop.visibility = if (theme.isTray) View.GONE else View.VISIBLE
        tray.visible = theme.isTray
        tray.applyTheme(theme)
        tray.setDesktop(desktopNumber)
        windows.theme = theme
        windows.firstDesktopIsHome = theme.isTray
        root.post(::updateWindowBottom)
        followDesktops(desktopPage)
    }

    private fun renderNotificationCount() = tray.refreshNotificationDots()

    /** The tray shows the next calendar event; the permission is asked for once, when it first appears. */
    private fun askForCalendarOnce() {
        if (!LauncherTheme.load(prefs).isTray || prefs.calendarAsked || CalendarEvents.canRead(this)) return
        prefs.calendarAsked = true
        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), CALENDAR_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CALENDAR_REQUEST) tray.refreshMinute()
    }

    /** Holding an empty spot on the desktop offers to open the launcher settings. */
    private fun setUpDesktopHold() {
        attachSettingsHold(desktop)
        // Touches nothing else takes reach the root: on the tray layout's other desktops that is
        // the empty desktop itself, which should open the settings as well.
        attachSettingsHold(root)
    }

    private fun attachSettingsHold(target: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        val askToOpenSettings = Runnable {
            target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            askToOpenSettings()
        }
        target.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    view.postDelayed(askToOpenSettings, SETTINGS_HOLD_MS)
                }
                MotionEvent.ACTION_MOVE ->
                    if (hypot(event.rawX - downX, event.rawY - downY) > touchSlop) view.removeCallbacks(askToOpenSettings)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.removeCallbacks(askToOpenSettings)
            }
            true
        }
    }

    companion object {
        /**
         * Launcher screens between start and stop. A count rather than a flag, because when the
         * launcher is recreated the old screen can stop after the new one has started.
         */
        private var startedCount = 0

        /** True while another app covers the launcher. */
        val isCovered get() = startedCount == 0
        const val CLOSE_BACKGROUND_HOLD_MS = 1500L
        private const val SETTINGS_HOLD_MS = 1500L
        private const val SURFACE_REFRESH_DELAY_MS = 500L
        const val EXTRA_GO_HOME = "dev.jeo.pencere.GO_HOME"
        private const val PAN_ZONE_HEIGHT_DP = 200
        private const val PAN_ZONE_WIDTH_RATIO = 0.6f
        private const val PAN_DIRECTION_RATIO = 1.5f
        private const val CALENDAR_REQUEST = 2
    }
}
