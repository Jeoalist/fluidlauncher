package dev.jeo.pencere

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Insets
import android.graphics.Rect
import android.os.Bundle
import android.widget.FrameLayout
import android.content.Intent
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.concurrent.Executors
import kotlin.math.hypot

/** Small popup beside the apps button: open apps while empty, search results while typing. */
class SearchActivity : Activity() {
    private val prefs by lazy { LauncherPrefs(this) }
    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: View
    private lateinit var panel: View
    private lateinit var search: EditText
    private lateinit var row: LinearLayout

    private var bars: Insets = Insets.NONE
    private var margin = 0
    private var gap = 0
    private var slotWidth = 0
    private var iconPadding = 0
    private var results: List<AppEntry> = emptyList()

    /** Running apps outside the launcher's windows, such as ones in real full screen. */
    private var fullscreenKeys: Set<String> = emptySet()
    private val onCatalogChanged = { showResults() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppsPanel.onShown(this)
        setContentView(R.layout.activity_search)
        root = findViewById(R.id.root)
        panel = findViewById(R.id.panel)
        search = findViewById(R.id.search)
        row = findViewById(R.id.icons)

        val density = resources.displayMetrics.density
        margin = (12 * density).toInt()
        gap = (10 * density).toInt()
        iconPadding = (6 * density).toInt()
        slotWidth = (PANEL_WIDTH_DP * density).toInt() / SLOTS_ACROSS

        root.setOnClickListener { finish() }
        root.setOnApplyWindowInsetsListener { _, insets ->
            bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            placePanel()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placePanel() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = showResults()
        })
        search.setOnEditorActionListener { view, action, _ ->
            val first = results.firstOrNull()
            if (action == EditorInfo.IME_ACTION_GO && first != null) open(first, view)
            action == EditorInfo.IME_ACTION_GO
        }
        AppCatalog.addListener(onCatalogChanged)
        showResults()
        search.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        applySystemBars(hideStatusBar = AppWindows.current?.isFullscreenVisible == true)
    }

    override fun onDestroy() {
        AppsPanel.onGone(this)
        AppCatalog.removeListener(onCatalogChanged)
        background.shutdown()
        super.onDestroy()
    }

    /** Sits below the button when it fits, otherwise above it, and never behind the keyboard. */
    private fun placePanel() {
        if (root.width == 0 || panel.width == 0) return
        val anchor = intent.sourceBounds ?: Rect(root.width / 2, bars.top, root.width / 2, bars.top)
        val top = bars.top + margin
        val bottom = root.height - bars.bottom - margin
        val below = anchor.bottom + gap
        val above = anchor.top - gap - panel.height
        panel.x = (anchor.centerX() - panel.width / 2).coerceIn(margin, root.width - margin - panel.width).toFloat()
        val preferred = if (below + panel.height <= bottom) below else above
        panel.y = preferred.coerceAtMost(bottom - panel.height).coerceAtLeast(top).toFloat()
        panel.visibility = View.VISIBLE
    }

    private fun showResults() {
        val query = search.text.toString().trim()
        if (query.isEmpty()) {
            loadOpenApps()
        } else {
            render(matchApps(AppCatalog.apps, query), holdToClose = false)
        }
    }

    /** Asks the privileged service off the main thread, since it is a blocking binder call. */
    private fun loadOpenApps() {
        val service = ShizukuBridge.service
        val windows = AppWindows.current?.openApps.orEmpty()
        val catalog = AppCatalog.apps
        background.execute {
            val running = try {
                service?.openPackages()
            } catch (_: RemoteException) {
                null
            }
            val others = running?.mapNotNull { pkg -> catalog.firstOrNull { it.packageName == pkg } }
                ?: prefs.recentKeys.mapNotNull { key -> catalog.firstOrNull { it.key == key } }
            main.post {
                if (!isDestroyed && search.text.isBlank()) {
                    if (running != null) fullscreenKeys = others.map { it.key }.toSet() - windows.map { it.key }.toSet()
                    render((windows + others).distinctBy { it.key }, true)
                }
            }
        }
    }

    private fun render(apps: List<AppEntry>, holdToClose: Boolean) {
        results = apps
        row.removeAllViews()
        // The list of open apps always starts with a way back to the home screen.
        if (holdToClose) row.addView(homeIcon(), LinearLayout.LayoutParams(slotWidth, LinearLayout.LayoutParams.MATCH_PARENT))
        apps.forEach { app ->
            val icon = ImageView(this).apply {
                setImageDrawable(app.icon)
                contentDescription = app.label
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            }
            if (holdToClose) {
                attachHoldToClose(icon, app)
            } else {
                icon.setOnClickListener { open(app, it) }
                icon.setOnLongClickListener {
                    showAppMenu(app, it, ::finish)
                    true
                }
            }
            row.addView(icon, LinearLayout.LayoutParams(slotWidth, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun homeIcon() = FrameLayout(this).apply {
        setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        val glyph = ImageView(context).apply {
            setBackgroundResource(R.drawable.fab)
            setImageResource(R.drawable.ic_home)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val inner = iconPadding * 2
            setPadding(inner, inner, inner, inner)
            contentDescription = getString(R.string.home_screen)
        }
        addView(glyph, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        glyph.setOnClickListener {
            startActivity(Intent(this@SearchActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachHoldToClose(icon: View, app: AppEntry) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var closed = false
        val hold = Runnable {
            closed = true
            icon.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            closeApp(app)
        }
        icon.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    closed = false
                    main.postDelayed(hold, CLOSE_HOLD_MS)
                }
                MotionEvent.ACTION_MOVE ->
                    if (hypot(event.rawX - downX, event.rawY - downY) > touchSlop) main.removeCallbacks(hold)
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(hold)
                    if (!closed && hypot(event.rawX - downX, event.rawY - downY) <= touchSlop) {
                        if (app.key in fullscreenKeys) switchTo(app, icon) else open(app, icon, newWindow = false)
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(hold)
            }
            true
        }
    }

    private fun closeApp(app: AppEntry) {
        AppWindows.current?.closeApp(app.key)
        render(results - app, holdToClose = true)
        val service = ShizukuBridge.service ?: return
        background.execute {
            try {
                service.closePackage(app.packageName)
            } catch (_: RemoteException) {
                // The service died, which closes its windows as well.
            }
        }
    }

    /** Brings a running full-screen app's own task forward, keeping it in full screen. */
    private fun switchTo(app: AppEntry, source: View) {
        try {
            WindowOpener(this).open(app, false, prefs.windowSize, Rect().also(source::getGlobalVisibleRect))
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun open(app: AppEntry, source: View, newWindow: Boolean = true) {
        launchApp(app, prefs.windowed, source, newWindow)
        finish()
    }

    private companion object {
        const val SLOTS_ACROSS = 4
        const val PANEL_WIDTH_DP = 280
        const val CLOSE_HOLD_MS = 2000L
    }
}
