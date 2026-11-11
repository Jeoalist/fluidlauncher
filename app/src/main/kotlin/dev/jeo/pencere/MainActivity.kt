package dev.jeo.pencere

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
    private lateinit var modePill: TextView
    private lateinit var appsButton: View
    private lateinit var shizukuPill: TextView
    private lateinit var windowLayer: FrameLayout
    private lateinit var desktopLabel: TextView
    private lateinit var windows: AppWindows

    private var systemBars: Insets = Insets.NONE
    private var margin = 0
    private var draggingButton = false
    private val shizukuStateListener: () -> Unit = { main.post(::renderShizukuState) }
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
        modePill = findViewById(R.id.modePill)
        appsButton = findViewById(R.id.appsButton)
        shizukuPill = findViewById(R.id.shizukuPill)
        windowLayer = findViewById(R.id.windowLayer)
        desktopLabel = findViewById(R.id.desktopLabel)
        windows = AppWindows(windowLayer, prefs)
        AppWindows.current = windows

        margin = (16 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { _, insets ->
            systemBars = insets.getInsets(WindowInsets.Type.systemBars())
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
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            placeAppsButton()
            excludeBackGesture()
            keepWindowsInArea()
        }
        modePill.setOnClickListener {
            prefs.windowed = !prefs.windowed
            renderMode()
        }
        setUpDesktopHold()
        setUpAppsButton()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        panZoneHeight = (PAN_ZONE_HEIGHT_DP * resources.displayMetrics.density).toInt()
        windows.onDesktopChanged = ::renderDesktopLabel
        windows.onFullscreenChanged = ::applyFullscreen
        renderDesktopLabel(1)
        renderMode()
        shizukuPill.setOnClickListener { onShizukuPillClicked() }
        ShizukuBridge.onStateChanged = shizukuStateListener
        ShizukuBridge.start(this)
        renderShizukuState()
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
        val keepsFocus = launcherOnTop || ShizukuBridge.state == ShizukuBridge.State.READY
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
        windows.keepInArea(systemBars.top.toFloat())
    }

    override fun onResume() {
        super.onResume()
        applySystemBars(windows.isFullscreenVisible)
    }

    override fun onStart() {
        super.onStart()
        FloatingAppsButton.hide(this)
    }

    /** Something covers the launcher, such as an app in real full screen. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) FloatingAppsButton.show(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars(windows.isFullscreenVisible)
    }

    override fun onDestroy() {
        // A replacement instance may already have registered itself by the time this one dies.
        if (ShizukuBridge.onStateChanged === shizukuStateListener) ShizukuBridge.onStateChanged = {}
        if (AppWindows.current === windows) AppWindows.current = null
        windows.closeAll()
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
            val target = if (prefs.appsButtonOpensDrawer) DrawerActivity::class.java else SearchActivity::class.java
            startActivity(Intent(this, target).apply { sourceBounds = bounds })
        }
        var heldToClose = false
        val closeOnHold = Runnable {
            heldToClose = true
            appsButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            closeBackgroundApps()
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
                        view.performClick()
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
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
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
        val inZone = event.rawX <= root.width * PAN_ZONE_WIDTH_RATIO &&
            event.rawY >= zoneBottom - panZoneHeight && event.rawY <= zoneBottom
        val onButton = event.rawX >= appsButton.x && event.rawX <= appsButton.x + appsButton.width &&
            event.rawY >= appsButton.y && event.rawY <= appsButton.y + appsButton.height
        return inZone && !onButton && !windows.isOnTitleBar(event.rawX, event.rawY)
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

    private fun renderDesktopLabel(number: Int) {
        desktopLabel.text = getString(R.string.desktop_label, number)
    }

    /** Closes launchable apps that have no window here, leaving this launcher and the home app. */
    private fun closeBackgroundApps() {
        val service = ShizukuBridge.service
        if (service == null) {
            Toast.makeText(this, R.string.background_needs_shizuku, Toast.LENGTH_SHORT).show()
            return
        }
        val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        val kept = windows.openApps.map { it.packageName }.toSet() + packageName + home?.activityInfo?.packageName
        val launchable = AppCatalog.apps.map { it.packageName }.toSet()
        background.execute {
            val closed = try {
                service.openPackages().filter { it in launchable && it !in kept }.onEach(service::closePackage).size
            } catch (_: RemoteException) {
                0
            }
            main.post {
                val message = if (closed == 0) getString(R.string.background_none) else getString(R.string.background_closed, closed)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    private fun renderShizukuState() {
        val state = ShizukuBridge.state
        updateFocusable()
        if (state != ShizukuBridge.State.READY) windows.closeAll()
        val message = when (state) {
            ShizukuBridge.State.NOT_RUNNING -> R.string.shizuku_not_running
            ShizukuBridge.State.NEEDS_PERMISSION -> R.string.shizuku_needs_permission
            ShizukuBridge.State.CONNECTING -> R.string.shizuku_connecting
            ShizukuBridge.State.READY -> null
        }
        shizukuPill.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let(shizukuPill::setText)
    }

    private fun onShizukuPillClicked() {
        when (ShizukuBridge.state) {
            ShizukuBridge.State.NEEDS_PERMISSION -> ShizukuBridge.requestPermission()
            ShizukuBridge.State.NOT_RUNNING ->
                packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let(::startActivity)
            else -> Unit
        }
    }

    private fun renderMode() {
        modePill.setText(if (prefs.windowed) R.string.mode_window else R.string.mode_fullscreen)
    }

    /** Holding an empty spot on the desktop offers to open the launcher settings. */
    private fun setUpDesktopHold() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        val askToOpenSettings = Runnable {
            desktop.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            AlertDialog.Builder(this)
                .setMessage(R.string.open_settings_question)
                .setPositiveButton(R.string.open) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        desktop.setOnTouchListener { view, event ->
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

    private companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val CLOSE_BACKGROUND_HOLD_MS = 1500L
        private const val SETTINGS_HOLD_MS = 1500L
        private const val PAN_ZONE_HEIGHT_DP = 200
        private const val PAN_ZONE_WIDTH_RATIO = 0.6f
        private const val PAN_DIRECTION_RATIO = 1.5f
    }
}
