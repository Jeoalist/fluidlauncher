package dev.jeo.pencere

import kotlin.math.pow
import android.view.animation.PathInterpolator
import android.view.animation.LinearInterpolator
import android.graphics.Shader
import android.graphics.LinearGradient
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.text.TextWatcher
import android.text.Editable
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.hypot

/**
 * The tray layout of the desktop. [backdrop] sits behind the app windows and holds the clock and
 * the app list; [chrome] sits above them and holds the side tray and the A–Z index, so these
 * stay reachable however windows are arranged.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class TrayDesktop(
    private val activity: Activity,
    private val prefs: LauncherPrefs,
    private val actions: Actions,
) {
    interface Actions {
        fun onDesktopPicked(number: Int)
        fun onStatusClicked()
        fun onEmptySpotHeld()
    }

    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Number) = (value.toFloat() * density).toInt()

    val backdrop = FrameLayout(activity)
    val chrome = FrameLayout(activity)

    private var theme = LauncherTheme.load(prefs)
    private var insets: Insets = Insets.NONE
    private var desktop = 1
    private var keyboardHeight = 0

    // Clock block.
    private val clockBlock = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val clock = TextClock(activity)
    private val date = TextView(activity)
    private val event = TextView(activity)
    private val status = TextView(activity)

    // Side tray.
    private val desktopTray = DesktopPicker(activity, AppWindows.DESKTOP_COUNT, KANJI_NUMBERS) { actions.onDesktopPicked(it) }

    // The index first: the panel fills it with letters as soon as it loads its apps.
    private val index = AlphabetIndex()
    private val panel = AppListPanel()

    init {
        buildClock()
        backdrop.addView(clockBlock, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        backdrop.addView(panel)
        chrome.addView(desktopTray, FrameLayout.LayoutParams(dp(DESKTOP_BUTTON_DP * AppWindows.DESKTOP_COUNT + 12), dp(TRAY_HEIGHT_DP)))
        chrome.addView(index, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        // The index wave leans out to the left of the index itself.
        chrome.clipChildren = false
        attachEmptySpotHold()
        backdrop.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        // The launcher draws behind the keyboard, so the list makes room for it itself.
        backdrop.setOnApplyWindowInsetsListener { _, windowInsets ->
            val keyboard = windowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            if (keyboard != keyboardHeight) {
                keyboardHeight = keyboard
                place()
            }
            windowInsets
        }
        applyTheme(theme)
    }

    var visible: Boolean = true
        set(value) {
            field = value
            val visibility = if (value) View.VISIBLE else View.GONE
            backdrop.visibility = visibility
            chrome.visibility = visibility
        }

    /** The top of the desktop picker on screen as drawn, which windows stay above. */
    val pickerTop: Float get() = desktopTray.y + desktopTray.height * (1 - desktopTray.scaleY)

    /** The picker's left and right edges on screen as drawn; windows beside it may reach lower. */
    val pickerLeft: Float get() = desktopTray.x + desktopTray.width * (1 - desktopTray.scaleX) / 2
    val pickerRight: Float get() = desktopTray.x + desktopTray.width * (1 + desktopTray.scaleX) / 2

    /** Whether a screen point is on the desktop picker, which takes its own sideways drags. */
    fun isOnPicker(x: Float, y: Float): Boolean {
        if (!visible) return false
        return x >= pickerLeft && x <= pickerRight && y >= pickerTop && y <= desktopTray.y + desktopTray.height
    }

    fun setInsets(bars: Insets) {
        insets = bars
        place()
    }

    // ---- Content updates -------------------------------------------------------------------

    fun setDesktop(number: Int) {
        desktop = number
        desktopTray.setDesktop(number)
    }

    /** Apps with notifications get a dot in the list. */
    fun refreshNotificationDots() = panel.refreshDots()

    /** Shows why windows cannot be scaled yet, or nothing once the service runs. */
    fun setStatus(message: String?) {
        status.text = message.orEmpty()
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    /** Date and the calendar line; called every minute and when the launcher comes back. */
    fun refreshMinute() {
        val locale = activity.resources.configuration.locales[0]
        date.text = SimpleDateFormat("EEEE, d MMMM", locale).format(Date()).lowercase(locale)
        val next = CalendarEvents.next(activity)
        if (next == null) {
            event.visibility = View.GONE
            return
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        val line = SpannableStringBuilder()
        line.appendStyled("▎ ", theme.line)
        line.appendStyled(next.title, theme.text)
        line.appendStyled("  ·  ", theme.muted)
        line.appendStyled("${time.format(Date(next.begin))}–${time.format(Date(next.end))}", theme.line)
        event.text = line
        event.visibility = View.VISIBLE
    }

    private fun SpannableStringBuilder.appendStyled(text: String, color: Int) {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun applyTheme(newTheme: LauncherTheme) {
        theme = newTheme
        val font = newTheme.typeface
        clock.typeface = Typeface.create(font, Typeface.BOLD)
        clock.setTextColor(newTheme.clock)
        listOf(date, status).forEach {
            it.typeface = font
            it.setTextColor(newTheme.text)
        }
        event.typeface = font
        status.setTextColor(newTheme.accent)
        desktopTray.theme = newTheme
        setDesktop(desktop)
        panel.applyTheme()
        index.invalidate()
        refreshMinute()
    }

    // ---- Building ---------------------------------------------------------------------------

    private fun buildClock() {
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.textSize = 64f
        clock.includeFontPadding = false
        clock.setShadowLayer(12f, 0f, 2f, 0x66000000)
        date.textSize = 17f
        date.setPadding(0, dp(6), 0, 0)
        event.textSize = 16f
        event.setPadding(0, dp(10), 0, 0)
        status.textSize = 13f
        status.setPadding(0, dp(10), 0, 0)
        status.setOnClickListener { actions.onStatusClicked() }
        listOf(date, event, status).forEach { it.setShadowLayer(8f, 0f, 1f, 0x66000000) }
        clockBlock.addView(clock)
        clockBlock.addView(date)
        clockBlock.addView(event)
        clockBlock.addView(status)
    }


    /** Holding an empty spot opens the settings question, as on the classic desktop. */
    private fun attachEmptySpotHold() {
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        val hold = Runnable {
            backdrop.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            actions.onEmptySpotHeld()
        }
        backdrop.setOnTouchListener { view, motion ->
            when (motion.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = motion.rawX
                    downY = motion.rawY
                    view.postDelayed(hold, SETTINGS_HOLD_MS)
                }
                MotionEvent.ACTION_MOVE ->
                    if (hypot(motion.rawX - downX, motion.rawY - downY) > touchSlop) view.removeCallbacks(hold)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.removeCallbacks(hold)
            }
            true
        }
    }

    private fun place() {
        val width = backdrop.width
        val height = backdrop.height
        if (width == 0 || height == 0) return
        // The desktop picker lies along the bottom, centred.
        val trayWidth = dp(DESKTOP_BUTTON_DP * AppWindows.DESKTOP_COUNT + 12)
        val trayTop = height - insets.bottom - dp(12) - dp(TRAY_HEIGHT_DP)
        desktopTray.x = (width - trayWidth) / 2f
        desktopTray.y = trayTop.toFloat()
        // A wide, short screen gets a smaller picker, standing on the same bottom line, so more of
        // the bottom is left to windows.
        val pickerScale = if (width > height) LANDSCAPE_PICKER_SCALE else 1f
        desktopTray.pivotX = trayWidth / 2f
        desktopTray.pivotY = dp(TRAY_HEIGHT_DP).toFloat()
        desktopTray.scaleX = pickerScale
        desktopTray.scaleY = pickerScale
        clockBlock.x = (insets.left + dp(24)).toFloat()
        clockBlock.y = (insets.top + dp(28)).toFloat()
        // Tall screens put the app list under the clock; a wide, short one puts it beside the
        // clock, or it would get barely a row.
        val wide = width > height
        val listLeft = if (wide) maxOf((clockBlock.x + clockBlock.width).toInt() + dp(24), (width * 0.38f).toInt()) else insets.left + dp(12)
        val listTop = if (wide) insets.top + dp(12) else (clockBlock.y + clockBlock.height).toInt() + dp(12)
        val bottom = if (keyboardHeight > 0) height - keyboardHeight - dp(8) else trayTop - dp(8)
        // A docked keyboard leaves the list at least a few rows instead of nothing.
        val listBottom = maxOf(bottom, listTop + dp(MIN_LIST_DP))
        val indexLeft = width - insets.right - dp(RAIL_WIDTH_DP) - dp(2)
        // The rail spans the list's height and the whole width, so its wave can reach over the list.
        resizeLater(index, FrameLayout.LayoutParams.MATCH_PARENT, listBottom - listTop)
        index.y = listTop.toFloat()
        panel.place(listLeft, listTop, indexLeft - dp(4), listBottom)
    }

    /**
     * Sizes a child after the current layout pass: a size set during one is not applied until
     * something else lays the screen out again, which left the rail at a stale height. Only on a
     * change, since a new size lays the parent out again, which calls [place] again.
     */
    private fun resizeLater(view: View, width: Int, height: Int) {
        val current = view.layoutParams
        if (current != null && current.width == width && current.height == height) return
        view.post { view.layoutParams = FrameLayout.LayoutParams(width, height) }
    }

    /**
     * The clock, the app list and the rail belong to the first desktop: they slide away with it,
     * [page] being how far the desktops have moved, in desktops (0 on the first, 1 on the second...).
     */
    fun followDesktops(page: Float) {
        if (page > 0f) {
            panel.clearSearch()
            panel.collapseMenu()
        }
        val shift = -page * backdrop.width
        backdrop.translationX = shift
        // The desktop picker stays on every desktop; only the rail belongs to the first one.
        index.translationX = shift
    }

    fun release() {
        panel.release()
    }

    /** Empties the search and puts the keyboard away, as when leaving the desktop. */
    fun clearSearch() = panel.clearSearch()

    // ---- App list panel ---------------------------------------------------------------------

    /** A card listing every app, sortable by name or by recent use, as a list or a grid. */
    private inner class AppListPanel : LinearLayout(activity) {
        private val header = LinearLayout(activity)
        private val search = EditText(activity)
        private var query = ""
        private val sort = ImageView(activity)
        private val grid = GridView(activity)
        private val adapter = Adapter()
        private var apps: List<AppEntry> = emptyList()
        private val onCatalog = { reload() }

        // Declared before init: setting up the list already scrolls it and draws rows.
        /** Each app's rail section, worked out once per list instead of on every jump. */
        private var sections: List<Char> = emptyList()
        private var dotDrawable = GradientDrawable()

        init {
            orientation = VERTICAL
            header.orientation = HORIZONTAL
            header.gravity = Gravity.CENTER_VERTICAL
            header.setPadding(dp(12), 0, 0, 0)
            search.textSize = 16f
            search.isSingleLine = true
            search.hint = activity.getString(R.string.search_hint)
            search.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            search.setPadding(dp(14), dp(8), dp(14), dp(8))
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    query = text?.toString().orEmpty()
                    reload()
                    // An empty result has no first row; selecting it crashed the launcher.
                    if (apps.isNotEmpty()) grid.setSelection(0)
                }
            })
            // Enter opens the best match, like the search popup.
            search.setOnEditorActionListener { view, _, _ ->
                apps.firstOrNull()?.let { open(it, view) }
                true
            }
            sort.setImageResource(R.drawable.ic_panel_sort)
            sort.contentDescription = activity.getString(R.string.panel_sort)
            header.addView(search, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            sort.setPadding(dp(8), dp(8), dp(8), dp(8))
            header.addView(sort, LayoutParams(dp(38), dp(38)))
            addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(PANEL_HEADER_DP)))
            grid.adapter = adapter
            grid.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) = Unit
                override fun onScroll(view: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                    index.setCurrent(sections.getOrNull(first))
                }
            })
            grid.verticalSpacing = dp(2)
            grid.isVerticalScrollBarEnabled = false
            grid.selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            grid.setPadding(0, 0, 0, dp(10))
            grid.clipToPadding = false
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

            sort.setOnClickListener {
                prefs.panelByRecent = !prefs.panelByRecent
                reload()
                applyTheme()
            }
            AppCatalog.addListener(onCatalog)
            applyLayoutMode()
            reload()
        }

        fun applyTheme() {
            dotDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accent)
            }
            IconCache.setFilter(listOf(theme.iconShade, theme.surface, theme.clock), theme.iconFilter)
            warmIcons()
            search.typeface = theme.typeface
            search.setTextColor(theme.text)
            search.setHintTextColor(theme.muted)
            search.background = CardDrawable(theme.surface.withAlpha(SEARCH_ALPHA), dp(18).toFloat())
            search.highlightColor = theme.accent.withAlpha(0.4f)
            sort.setColorFilter(if (prefs.panelByRecent) theme.accent else theme.muted)
            // The list or grid choice lives in the settings and comes back with the theme.
            applyLayoutMode()
            adapter.notifyDataSetChanged()
        }

        private fun applyLayoutMode() {
            grid.numColumns = if (prefs.panelGrid) GRID_COLUMNS else 1
            adapter.notifyDataSetChanged()
        }

        private fun reload() {
            val catalog = AppCatalog.apps
            apps = if (query.isNotBlank()) {
                matchApps(catalog, query)
            } else if (prefs.panelByRecent) {
                val recent = prefs.recentKeys.mapNotNull { key -> catalog.firstOrNull { it.key == key } }
                recent + catalog.filter { it !in recent }
            } else {
                catalog
            }
            sections = apps.map(::initial)
            expandedKey = null
            adapter.notifyDataSetChanged()
            index.setLetters(sections)
            warmIcons()
        }


        private fun warmIcons() {
            val list = apps
            IconCache.warm(list, iconSize()) {}
        }

        fun refreshDots() = adapter.notifyDataSetChanged()

        private fun open(app: AppEntry, source: View) {
            collapseMenu()
            activity.launchApp(app, prefs.windowed, source)
            clearSearch()
        }

        /** Empties the box and puts the keyboard away. */
        fun clearSearch() {
            if (!search.hasFocus() && search.text.isEmpty()) return
            if (search.text.isNotEmpty()) search.text.clear()
            search.clearFocus()
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        }

        /** Jumps the list to the first app under [letter]; ☆ is the top and # anything not a letter. */
        fun scrollTo(letter: Char) {
            val position = when (letter) {
                STAR -> 0
                else -> sections.indexOf(letter)
            }
            if (position >= 0) grid.setSelection(position)
        }

        /** Fills the given part of the desktop; it is part of the desktop, not a window. */
        fun place(left: Int, top: Int, right: Int, bottom: Int) {
            val width = right - left
            val height = (bottom - top).coerceAtLeast(dp(PANEL_HEADER_DP))
            resizeLater(this, width, height)
            x = left.toFloat()
            y = top.toFloat()
        }

        fun release() = AppCatalog.removeListener(onCatalog)

        /**
         * The parts of a row, kept with it so a scrolled-away row can be filled again. [view] holds
         * the app's [line] and, under it, the [menu] slot that opens when the app is held.
         */
        private inner class Row(
            val view: LinearLayout,
            val line: LinearLayout,
            val icon: ImageView,
            val dot: View,
            val label: TextView,
            val menu: FrameLayout,
            val grid: Boolean,
        ) {
            var menuFor: String? = null
        }

        /** The app whose menu is open inside the list, pushing the apps below it down. */
        private var expandedKey: String? = null
        private var menuAnimator: ValueAnimator? = null

        /**
         * Opens the app's menu right under it in the list. The rows below slide down to make room,
         * so nothing hides behind it. In the grid there is no room under a cell, so the menu
         * opens as a small window instead.
         */
        private fun expandMenu(app: AppEntry, row: Row) {
            if (row.grid) {
                activity.showAppMenu(app, row.line)
                return
            }
            if (expandedKey != null && expandedKey != app.key) {
                expandedKey = null
                adapter.notifyDataSetChanged()
            }
            expandedKey = app.key
            fillMenu(app, row)
            row.menu.measure(
                MeasureSpec.makeMeasureSpec(row.view.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            animateMenu(row.menu, 0, row.menu.measuredHeight) {
                row.menu.layoutParams = row.menu.layoutParams.apply { height = LayoutParams.WRAP_CONTENT }
                // Bring the whole menu into view when it opened near the bottom.
                val overflow = row.view.bottom - grid.height + grid.paddingBottom
                if (overflow > 0) grid.smoothScrollBy(overflow, MENU_MS.toInt())
            }
        }

        /** Closes the open menu, sliding the rows below back up. */
        fun collapseMenu() {
            val key = expandedKey ?: return
            expandedKey = null
            val row = (0 until grid.childCount).map { grid.getChildAt(it).tag as? Row }.firstOrNull { it?.menuFor == key }
            if (row == null) {
                adapter.notifyDataSetChanged()
                return
            }
            animateMenu(row.menu, row.menu.height, 0) {
                row.menu.visibility = View.GONE
                row.menu.removeAllViews()
                row.menuFor = null
            }
        }

        private fun fillMenu(app: AppEntry, row: Row) {
            row.menu.removeAllViews()
            row.menu.addView(activity.appMenuContent(app, showTitle = false, onDone = ::collapseMenu, onLaunched = {}))
            row.menu.visibility = View.VISIBLE
            row.menuFor = app.key
        }

        private fun animateMenu(menu: View, from: Int, to: Int, onEnd: () -> Unit) {
            menuAnimator?.cancel()
            menu.layoutParams = menu.layoutParams.apply { height = from }
            menuAnimator = ValueAnimator.ofInt(from, to).apply {
                duration = MENU_MS
                interpolator = FAST_OUT_SLOW_IN
                addUpdateListener {
                    menu.layoutParams = menu.layoutParams.apply { height = it.animatedValue as Int }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
                })
                start()
            }
        }

        private fun iconSize() = dp(if (prefs.panelGrid) 38 else 28)

        private fun newRow(gridMode: Boolean): Row {
            val cell = LinearLayout(activity).apply { orientation = VERTICAL }
            val row = LinearLayout(activity)
            row.orientation = if (gridMode) VERTICAL else HORIZONTAL
            row.gravity = if (gridMode) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(6), dp(8), dp(6))
            val size = iconSize()
            val iconFrame = FrameLayout(activity)
            val icon = ImageView(activity)
            iconFrame.addView(icon, FrameLayout.LayoutParams(size, size))
            val dot = View(activity)
            iconFrame.addView(dot, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.END or Gravity.TOP))
            row.addView(iconFrame, LayoutParams(size + dp(4), size + dp(4)))
            val label = TextView(activity).apply {
                setShadowLayer(8f, 0f, 1f, SHADOW)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (gridMode) {
                    textSize = 10f
                    gravity = Gravity.CENTER
                } else {
                    textSize = 15f
                    setPadding(dp(14), 0, 0, 0)
                }
            }
            row.addView(label, LayoutParams(if (gridMode) LayoutParams.MATCH_PARENT else 0, LayoutParams.WRAP_CONTENT, if (gridMode) 0f else 1f))
            cell.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            val menu = FrameLayout(activity).apply {
                setPadding(dp(8), 0, dp(4), dp(8))
                visibility = View.GONE
            }
            cell.addView(menu, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return Row(cell, row, icon, dot, label, menu, gridMode).also { cell.tag = it }
        }

        private inner class Adapter : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = apps[position].key.hashCode().toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = apps[position]
                val gridMode = prefs.panelGrid
                val row = (convertView?.tag as? Row)?.takeIf { it.grid == gridMode } ?: newRow(gridMode)
                row.icon.setImageBitmap(IconCache.get(app, iconSize()))
                row.dot.visibility = if (app.packageName in NotificationCounter.packages) View.VISIBLE else View.GONE
                row.dot.background = dotDrawable
                row.label.text = app.label
                row.label.typeface = theme.typeface
                row.label.setTextColor(theme.text)
                row.line.setOnClickListener { open(app, it) }
                row.line.setOnLongClickListener {
                    expandMenu(app, row)
                    true
                }
                // A recycled row may carry another app's menu; only the held app shows one.
                if (app.key == expandedKey && !row.grid) {
                    if (row.menuFor != app.key) fillMenu(app, row)
                    row.menu.layoutParams = row.menu.layoutParams.apply { height = LayoutParams.WRAP_CONTENT }
                } else if (row.menuFor != null) {
                    row.menu.visibility = View.GONE
                    row.menu.removeAllViews()
                    row.menuFor = null
                }
                return row.view
            }
        }
    }

    /** The rail section an app sorts under: A–Z with Turkish letters folded in, everything else "#". */
    private fun initial(app: AppEntry): Char {
        val c = searchKey(app.label).firstOrNull()?.uppercaseChar() ?: return '#'
        return if (c in 'A'..'Z') c else '#'
    }

    // ---- Alphabet rail --------------------------------------------------------------------

    /**
     * The alphabet rail, as in immergreen-launcher by Deniz Tunç, which follows Niagara Launcher.
     * The letters sit at the bottom of the rail, each with the same height. While a finger is on
     * it, every letter is pushed towards the screen's middle by a bump around the finger,
     * offset = A · 2^(−4·Δy² / (railHeight·A)), where A is [RAIL_LIFT_DP] plus how far the finger
     * has been pulled off the rail, so the bump grows taller and wider as it is pulled out. A
     * bubble with the letter rides on its crest. Past either end the rail slides with the finger.
     *
     * The view covers the whole list area so the wave and bubble can be drawn over the list; it
     * only takes touches that start on the rail itself.
     */
    private inner class AlphabetIndex : View(activity) {
        private var letters: List<Char> = listOf(STAR)
        private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val easing = PathInterpolator(0.4f, 0f, 0.2f, 1f)

        private var dragging = false
        private var fingerX = 0f
        private var fingerY = 0f
        private var current: Char? = null

        /** The section the list shows at its top, marked when no finger is on the rail. */
        private var section: Char? = null

        /** How far the rail has slid to follow a finger past its ends. */
        private var shift = 0f
        private var bubbleX = 0f
        private var bubbleY = 0f

        /** 0 at rest, 1 while pressed; the wave and the rail's slide ride on it. */
        private var press = 0f
        private var pressAnimator: ValueAnimator? = null

        /** 0 … 1 while the bubble appears or goes. */
        private var show = 0f
        private var showAnimator: ValueAnimator? = null
        private var shownLetter = 'A'

        /** The letters' fly-in when the launcher comes back: 0 at the start, 1 when done. */
        private var fly = 1f
        private var flyAnimator: ValueAnimator? = null

        /** The bold face of the theme's font, made once per font rather than on every frame. */
        private var boldFor: Typeface? = null
        private var bold: Typeface = Typeface.DEFAULT_BOLD
        private val boldFace: Typeface
            get() {
                if (boldFor !== theme.typeface) {
                    boldFor = theme.typeface
                    bold = Typeface.create(theme.typeface, Typeface.BOLD)
                }
                return bold
            }

        private val itemHeight get() = dp(RAIL_ITEM_DP).toFloat().coerceAtMost(height / letters.size.coerceAtLeast(1).toFloat())
        private val railHeight get() = letters.size * itemHeight

        /** Centre x of the letters: the strip along the right edge. */
        private val railX get() = width - insets.right - dp(RAIL_WIDTH_DP) / 2f

        /** Where the rail rests, bottom-anchored like Niagara, in thumb reach. */
        private val restTop get() = height - railHeight

        fun setLetters(sections: List<Char>) {
            letters = listOf(STAR) + sections.distinct().sortedBy { if (it == '#') '~' else it }
            invalidate()
        }

        fun setCurrent(letter: Char?) {
            if (letter == section) return
            section = letter
            invalidate()
        }

        /** The letters fly in from the screen edge, later ones a bit later. */
        fun flyIn() {
            flyAnimator?.cancel()
            flyAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FLY_IN_MS
                interpolator = LinearInterpolator()
                addUpdateListener {
                    fly = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun animate(from: Float, to: Float, old: ValueAnimator?, onValue: (Float) -> Unit): ValueAnimator {
            old?.cancel()
            return ValueAnimator.ofFloat(from, to).apply {
                duration = PRESS_MS
                interpolator = easing
                addUpdateListener {
                    onValue(it.animatedValue as Float)
                    invalidate()
                }
                start()
            }
        }

        private fun setRailPressed(pressed: Boolean) {
            pressAnimator = animate(press, if (pressed) 1f else 0f, pressAnimator) { press = it }
        }

        private fun setBubble(visible: Boolean) {
            showAnimator = animate(show, if (visible) 1f else 0f, showAnimator) { show = it }
        }

        /** How far the crest reaches: the lift plus the sideways pull, kept clear of the far edge. */
        private fun reach(x: Float): Float {
            val room = railX
            val pull = kotlin.math.abs(railX - x)
            return (dp(RAIL_LIFT_DP) + pull).coerceIn(0f, kotlin.math.max(0f, room - bubbleGap() - dp(16)))
        }

        private fun bubbleGap() = dp(12) + dp(BUBBLE_DP) / 2f

        private fun waveOffset(reach: Float, dy: Float): Float =
            if (reach <= 0f || railHeight <= 0f) 0f else reach * 2f.pow(-4f * dy * dy / (railHeight * reach))

        /** Only the finger's height picks the letter; its x just pulls the wave further out. */
        private fun drive(x: Float, y: Float) {
            if (!dragging) {
                dragging = true
                shift = 0f
                setRailPressed(true)
            }
            fingerX = x
            fingerY = y
            // Past either end the whole rail slides along with the finger, within the view.
            val top = restTop + shift
            shift = when {
                y < top -> kotlin.math.max(y, 0f) - restTop
                y > top + railHeight -> kotlin.math.min(y, height.toFloat()) - railHeight - restTop
                else -> shift
            }
            val railTop = restTop + shift
            val i = ((y - railTop) / itemHeight).toInt().coerceIn(0, letters.lastIndex)
            val letter = letters[i]
            if (letter != current) {
                if (current == null) setBubble(true)
                current = letter
                shownLetter = letter
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                panel.scrollTo(letter)
            }
            // The bubble snaps magnetically to the letter's centre.
            val centre = railTop + (i + 0.5f) * itemHeight
            val f = ((y - centre) / itemHeight).coerceIn(-0.5f, 0.5f)
            bubbleY = centre + (2f * f).pow(3) / 2f * itemHeight
            bubbleX = railX - (reach(x) + bubbleGap())
            invalidate()
        }

        private fun release() {
            dragging = false
            current = null
            setRailPressed(false)
            setBubble(false)
        }

        override fun onTouchEvent(motion: MotionEvent): Boolean {
            when (motion.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Only the strip along the edge belongs to the rail; the list gets the rest.
                    val stripLeft = width - insets.right - dp(RAIL_WIDTH_DP + INDEX_TOUCH_MARGIN_DP)
                    if (motion.x < stripLeft) return false
                    drive(motion.x, motion.y)
                }
                MotionEvent.ACTION_MOVE -> if (dragging) drive(motion.x, motion.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) release()
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            if (letters.isEmpty() || height == 0) return
            val item = itemHeight
            val highlight = if (dragging) current else section
            // Shrinks the letters when the rail is shorter than the alphabet, so they never overlap.
            letterPaint.textSize = minOf(sp(RAIL_LETTER_SP), item * 0.85f)
            letterPaint.setShadowLayer(6f, 0f, 2f, 0xB3000000.toInt())
            val edgeDistance = width - railX
            letters.forEachIndexed { i, letter ->
                val centre = restTop + shift * press + (i + 0.5f) * item
                // The fly-in comes from the right edge; the wave bulges left, towards the list.
                var dx = flyIn(fly, i, letters.size) * edgeDistance
                if (press > 0f) dx -= waveOffset(reach(fingerX), centre - fingerY) * press
                val isHighlight = letter == highlight
                letterPaint.typeface = if (isHighlight) boldFace else theme.typeface
                letterPaint.color = if (isHighlight) theme.accent else theme.text
                val baseline = centre - (letterPaint.descent() + letterPaint.ascent()) / 2
                canvas.drawText(if (letter == STAR) "☆" else letter.toString(), railX + dx, baseline, letterPaint)
            }
            drawBubble(canvas)
        }

        /** The letter under the finger in a bubble on the wave's crest, with the window border. */
        private fun drawBubble(canvas: Canvas) {
            if (show <= 0f) return
            val radius = dp(BUBBLE_DP) / 2f * (0.5f + 0.5f * show)
            val alpha = (show * 255).toInt()
            bubblePaint.color = theme.surface.withAlpha(0.95f)
            bubblePaint.alpha = (alpha * 0.95f).toInt()
            canvas.drawCircle(bubbleX, bubbleY, radius, bubblePaint)
            val stroke = dp(2).toFloat()
            borderPaint.strokeWidth = stroke
            borderPaint.shader = LinearGradient(
                bubbleX - radius, bubbleY - radius, bubbleX + radius, bubbleY + radius,
                intArrayOf(theme.accent, theme.accent.darker()), null, Shader.TileMode.CLAMP,
            )
            borderPaint.alpha = alpha
            canvas.drawCircle(bubbleX, bubbleY, radius - stroke / 2, borderPaint)
            letterPaint.textSize = sp(BUBBLE_LETTER_SP) * (0.5f + 0.5f * show)
            letterPaint.typeface = boldFace
            letterPaint.color = theme.accent
            letterPaint.alpha = alpha
            letterPaint.clearShadowLayer()
            val baseline = bubbleY - (letterPaint.descent() + letterPaint.ascent()) / 2
            canvas.drawText(if (shownLetter == STAR) "☆" else shownLetter.toString(), bubbleX, baseline, letterPaint)
        }
    }

    /**
     * The letters' fly-in, also from immergreen-launcher after Niagara: each letter enters from
     * the screen edge, later ones a bit later, with an overshoot and a dip that is stronger
     * towards the top. Returns 0 (in place) … 1 (fully off screen) for letter [i] of [n].
     */
    private fun flyIn(p: Float, i: Int, n: Int): Float {
        if (p >= 1f) return 0f
        val s = 1f / ((n - 1) * 0.05f + 1f)
        val t = ((p - 0.05f * s * i) / (s * (if (n <= 1) 1f else i / (n - 1f) * -0.7f + 1f))).coerceIn(0f, 1f)
        val eased = FAST_OUT_SLOW_IN.getInterpolation(t)
        val u = t - 1f
        val overshoot = (11f * u + 10f) * u * u + 1f
        val dip = if (t <= 0.5f) 0f else -0.45f * kotlin.math.sin((t - 0.5f) / 0.5f * Math.PI.toFloat()).pow(2)
        val weight = if (n > 1) 0.6f - i / (n - 1f) * 0.6f else 0.6f
        return (eased - (overshoot + dip)) * weight + (1f - eased)
    }

    private fun sp(value: Float) = value * activity.resources.displayMetrics.scaledDensity

    /** Called when the launcher comes back, like Niagara's letter fly-in. */
    fun flyInRail() = index.flyIn()

    private companion object {
        val KANJI_NUMBERS = listOf("一", "二", "三", "四", "五")
        const val STAR = '☆'
        const val TRAY_HEIGHT_DP = 52
        const val LANDSCAPE_PICKER_SCALE = 0.8f
        const val DESKTOP_BUTTON_DP = 50

        /** Extra touch area around the index letters, on every side. */
        const val INDEX_TOUCH_MARGIN_DP = 4
        const val SHADOW = 0xAA000000.toInt()
        const val SEARCH_ALPHA = 0.45f
        const val PANEL_HEADER_DP = 52
        const val GRID_COLUMNS = 4

        // The alphabet rail, with immergreen-launcher's defaults.
        const val RAIL_ITEM_DP = 19
        const val MIN_LIST_DP = 140
        const val RAIL_LETTER_SP = 14f
        const val RAIL_WIDTH_DP = 30
        const val RAIL_LIFT_DP = 64
        const val BUBBLE_DP = 56
        const val BUBBLE_LETTER_SP = 24f
        const val PRESS_MS = 150L
        const val MENU_MS = 220L
        const val FLY_IN_MS = 650L
        val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        const val SETTINGS_HOLD_MS = 1500L
    }
}
