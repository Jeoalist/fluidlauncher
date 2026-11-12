package dev.jeo.pencere

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * First-run guide drawn over the desktop. It teaches window handling on a practice window, and
 * each step waits until the person has done the gesture. Gestures follow the current settings.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class TutorialOverlay(
    context: Context,
    private val prefs: LauncherPrefs,
    private val appsButton: View,
    private val onFinished: () -> Unit,
) : FrameLayout(context) {

    private sealed interface Step {
        data object Move : Step
        data object Resize : Step
        data class Gesture(val gesture: WindowGesture, val action: WindowAction) : Step
        data object Close : Step
        data object AppsButton : Step
        data object Desktops : Step
        data object Settings : Step
        data object Done : Step
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val steps: List<Step> = buildList {
        add(Step.Move)
        add(Step.Resize)
        WindowGesture.entries.forEach { gesture ->
            val action = prefs.windowAction(gesture)
            if (action != WindowAction.NONE) add(Step.Gesture(gesture, action))
        }
        add(Step.Close)
        add(Step.AppsButton)
        add(Step.Desktops)
        add(Step.Settings)
        add(Step.Done)
    }
    private var index = 0
    private val step get() = steps[index]

    /** How many times the current gesture step's action has been done, for ones that need undoing. */
    private var performed = 0
    private var completing = false

    private val card = LinearLayout(context)
    private val counter = TextView(context)
    private val title = TextView(context)
    private val body = TextView(context)
    private val primary = TextView(context)
    private val skip = TextView(context)
    private val demo = DemoWindow()

    // Where the step wants attention: the apps button or the desktop swipe zone.
    private var highlight: RectF? = null
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = ACCENT
    }
    private val highlightFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFC857 }

    private var topInset = 0
    private var bottomInset = 0
    private var downX = 0f
    private var downY = 0f
    private val holdOnDesktop = Runnable { if (step == Step.Settings) complete() }

    init {
        setBackgroundColor(DIM)
        setWillNotDraw(false)
        isClickable = true
        buildCard()
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(demo)
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topInset = bars.top
            bottomInset = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.mandatorySystemGestures()).bottom
            requestLayout()
            insets
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!demo.placed) post(::render) }
        // The card grows with its text; the practice window must stay below it.
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (demo.visibility == View.VISIBLE && demo.y < areaTop()) demo.y = areaTop()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val margin = (MARGIN_DP * density).toInt()
        card.layout(margin, topInset + margin, right - left - margin, topInset + margin + card.measuredHeight)
        demo.layout(0, 0, demo.measuredWidth, demo.measuredHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val margin = (MARGIN_DP * density).toInt()
        card.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth - margin * 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        demo.measure(
            MeasureSpec.makeMeasureSpec(demo.windowWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(demo.windowHeight, MeasureSpec.EXACTLY),
        )
    }

    /** The practice area starts below the card, so the window never hides the instructions. */
    private fun areaTop() = card.bottom + (MARGIN_DP * density)

    private fun areaBottom() = height - bottomInset.toFloat()

    private fun buildCard() {
        val padding = (18 * density).toInt()
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(padding, padding, padding, padding)
        card.background = GradientDrawable().apply {
            setColor(CARD_COLOR)
            cornerRadius = 20 * density
            setStroke(density.toInt(), 0x33FFFFFF)
        }
        counter.setTextColor(ACCENT)
        counter.textSize = 13f
        title.setTextColor(Color.WHITE)
        title.textSize = 20f
        title.setPadding(0, (4 * density).toInt(), 0, (6 * density).toInt())
        body.setTextColor(0xDDFFFFFF.toInt())
        body.textSize = 15f
        body.setLineSpacing(0f, 1.15f)
        card.addView(counter)
        card.addView(title)
        card.addView(body)

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        listOf(skip, primary).forEach {
            it.textSize = 15f
            it.gravity = Gravity.CENTER
            val horizontal = (16 * density).toInt()
            it.setPadding(horizontal, (8 * density).toInt(), horizontal, (8 * density).toInt())
        }
        skip.setTextColor(0xAAFFFFFF.toInt())
        skip.text = context.getString(R.string.tutorial_skip)
        skip.setOnClickListener { finish() }
        primary.setTextColor(Color.BLACK)
        primary.background = GradientDrawable().apply {
            setColor(ACCENT)
            cornerRadius = 18 * density
        }
        primary.setOnClickListener { if (step == Step.Done) finish() else next() }
        buttons.addView(skip)
        buttons.addView(primary)
        card.addView(buttons)
    }

    private fun render() {
        val total = steps.size - 1
        counter.text = if (step == Step.Done) "" else context.getString(R.string.tutorial_counter, index + 1, total)
        val (titleText, bodyText) = texts(step)
        title.text = titleText
        body.text = bodyText
        primary.text = context.getString(if (step == Step.Done) R.string.tutorial_finish else R.string.tutorial_next_step)
        primary.alpha = if (step == Step.Done) 1f else 0.85f
        skip.visibility = if (step == Step.Done) View.GONE else View.VISIBLE
        // Measure the card first, because the practice window is placed below it.
        post {
            demo.visibility = if (step in WINDOW_STEPS || step is Step.Gesture) View.VISIBLE else View.GONE
            if (demo.visibility == View.VISIBLE && !demo.placed) demo.reset()
            highlight = when (step) {
                Step.AppsButton -> buttonRect()
                Step.Desktops -> panZone()
                else -> null
            }
            invalidate()
        }
    }

    private fun texts(step: Step): Pair<String, String> = when (step) {
        Step.Move -> s(R.string.tutorial_move_title) to s(R.string.tutorial_move_body)
        Step.Resize -> s(R.string.tutorial_resize_title) to s(R.string.tutorial_resize_body)
        is Step.Gesture -> {
            val how = s(step.gesture.titleRes)
            val title = s(step.action.labelRes)
            val extra = when (step.action) {
                WindowAction.FILL -> s(R.string.tutorial_fill_body)
                WindowAction.ROTATE -> s(R.string.tutorial_rotate_body)
                WindowAction.REAL_FULLSCREEN -> s(R.string.tutorial_real_fullscreen_body)
                WindowAction.BACK -> s(R.string.tutorial_back_body)
                WindowAction.CLOSE -> s(R.string.tutorial_close_gesture_body)
                WindowAction.NONE -> ""
            }
            title to context.getString(R.string.tutorial_gesture_body, how, extra)
        }
        Step.Close -> s(R.string.tutorial_close_title) to s(R.string.tutorial_close_body)
        Step.AppsButton -> s(R.string.tutorial_apps_title) to s(R.string.tutorial_apps_body)
        Step.Desktops -> s(R.string.tutorial_desktops_title) to s(R.string.tutorial_desktops_body)
        Step.Settings -> s(R.string.tutorial_settings_title) to s(R.string.tutorial_settings_body)
        Step.Done -> s(R.string.tutorial_done_title) to s(R.string.tutorial_done_body)
    }

    private fun s(res: Int) = context.getString(res)

    /** Shows a short "well done" before moving on, so the person sees that the gesture worked. */
    private fun complete() {
        if (completing) return
        completing = true
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        counter.text = context.getString(R.string.tutorial_well_done)
        postDelayed({
            completing = false
            next()
        }, SUCCESS_PAUSE_MS)
    }

    private fun next() {
        removeCallbacks(holdOnDesktop)
        if (index < steps.lastIndex) index++
        performed = 0
        demo.restoreShape()
        render()
    }

    private fun finish() {
        prefs.tutorialDone = true
        onFinished()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = highlight ?: return
        val radius = if (step == Step.AppsButton) rect.width() / 2 else 16 * density
        canvas.drawRoundRect(rect, radius, radius, highlightFill)
        canvas.drawRoundRect(rect, radius, radius, highlightPaint)
    }

    private fun buttonRect(): RectF {
        val location = IntArray(2).also(appsButton::getLocationInWindow)
        val mine = IntArray(2).also(::getLocationInWindow)
        val grow = 8 * density
        val left = (location[0] - mine[0]).toFloat()
        val top = (location[1] - mine[1]).toFloat()
        return RectF(left - grow, top - grow, left + appsButton.width + grow, top + appsButton.height + grow)
    }

    /** The same bottom-left strip in which the desktop takes a sideways swipe. */
    private fun panZone(): RectF {
        val bottom = areaBottom()
        return RectF(0f, bottom - PAN_ZONE_HEIGHT_DP * density, width * PAN_ZONE_WIDTH_RATIO, bottom)
    }

    /** The overlay takes every touch, so the desktop under it cannot be changed by accident. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                if (step == Step.Settings) postDelayed(holdOnDesktop, SETTINGS_HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (hypot(dx, dy) > touchSlop) removeCallbacks(holdOnDesktop)
                val zone = panZone()
                if (step == Step.Desktops && zone.contains(downX, downY) &&
                    abs(dx) > SWIPE_DISTANCE_DP * density && abs(dx) > abs(dy) * 1.5f
                ) {
                    complete()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdOnDesktop)
                val tapped = hypot(event.x - downX, event.y - downY) <= touchSlop
                if (step == Step.AppsButton && tapped && buttonRect().contains(event.x, event.y)) complete()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(holdOnDesktop)
        }
        return true
    }

    private fun onDemoMoved(distance: Float) {
        if (step == Step.Move && distance > MOVE_DISTANCE_DP * density) complete()
    }

    private fun onDemoResized(change: Float) {
        if (step == Step.Resize && change > RESIZE_DISTANCE_DP * density) complete()
    }

    private fun onDemoGesture(gesture: WindowGesture) {
        val current = step
        val action = prefs.windowAction(gesture)
        demo.show(action)
        if (current !is Step.Gesture || current.gesture != gesture) return
        performed++
        // A full screen toggle is only learnt once it has also been undone.
        val needed = if (action == WindowAction.FILL) 2 else 1
        if (performed >= needed) complete()
    }

    private fun onDemoClosed() {
        if (step == Step.Close) complete() else postDelayed({ demo.reset() }, SUCCESS_PAUSE_MS)
    }

    /** Looks like an app window, but only plays back what the real one would do. */
    private inner class DemoWindow : FrameLayout(context) {
        private val titleHeight = (TITLE_HEIGHT_DP * density).toInt()
        private val buttonWidth = (BUTTON_WIDTH_DP * density).toInt()
        private val minWidth = (MIN_WIDTH_DP * density).toInt()
        private val label = TextView(context)
        private val feedback = TextView(context)
        var windowWidth = 0
            private set
        var windowHeight = 0
            private set
        var placed = false
            private set
        private var filled: RectF? = null
        private var landscape = false

        init {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(density.toInt(), 0x55FFFFFF)
            }
            setPadding(1, 1, 1, 1)
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(titleBar(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, titleHeight))
            val content = FrameLayout(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF2B5876.toInt(), 0xFF4E4376.toInt()),
                )
            }
            label.setTextColor(Color.WHITE)
            label.textSize = 15f
            label.gravity = Gravity.CENTER
            label.text = s(R.string.tutorial_sample_app)
            feedback.setTextColor(ACCENT)
            feedback.textSize = 15f
            feedback.gravity = Gravity.CENTER
            feedback.alpha = 0f
            content.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            content.addView(
                feedback,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                    bottomMargin = (24 * density).toInt()
                },
            )
            column.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        /** Back to the starting size in the middle of the practice area. */
        fun reset() {
            filled = null
            landscape = false
            val area = areaBottom() - areaTop()
            windowWidth = (this@TutorialOverlay.width * START_WIDTH_RATIO).toInt()
            windowHeight = minOf((windowWidth * PHONE_RATIO).toInt() + titleHeight, (area * 0.8f).toInt())
            x = (this@TutorialOverlay.width - windowWidth) / 2f
            y = areaTop() + (area - windowHeight) / 2f
            alpha = 1f
            visibility = View.VISIBLE
            label.text = s(R.string.tutorial_sample_app)
            placed = true
            requestLayout()
        }

        /** Undoes full screen or a sideways turn once their step is over. */
        fun restoreShape() {
            if (filled != null) toggleFill()
            if (landscape) toggleLandscape()
        }

        fun show(action: WindowAction) {
            when (action) {
                WindowAction.NONE -> Unit
                WindowAction.FILL -> toggleFill()
                WindowAction.ROTATE -> toggleLandscape()
                WindowAction.REAL_FULLSCREEN -> flash(s(R.string.tutorial_real_fullscreen_feedback))
                WindowAction.BACK -> flash(s(R.string.tutorial_back_feedback))
                WindowAction.CLOSE -> close()
            }
        }

        private fun toggleFill() {
            val saved = filled
            if (saved == null) {
                filled = RectF(x, y, x + windowWidth, y + windowHeight)
                x = 0f
                y = areaTop()
                windowWidth = this@TutorialOverlay.width
                windowHeight = (areaBottom() - areaTop()).toInt()
            } else {
                filled = null
                x = saved.left
                y = saved.top
                windowWidth = saved.width().toInt()
                windowHeight = saved.height().toInt()
            }
            requestLayout()
        }

        private fun toggleLandscape() {
            if (filled != null) return
            landscape = !landscape
            val contentHeight = windowHeight - titleHeight
            val area = this@TutorialOverlay
            windowHeight = minOf(windowWidth + titleHeight, (areaBottom() - areaTop()).toInt())
            windowWidth = minOf(contentHeight, area.width)
            x = x.coerceIn(0f, (area.width - windowWidth).toFloat())
            y = y.coerceIn(areaTop(), areaBottom() - windowHeight)
            label.text = s(if (landscape) R.string.tutorial_sample_landscape else R.string.tutorial_sample_app)
            requestLayout()
        }

        private fun flash(message: String) {
            feedback.text = message
            feedback.animate().cancel()
            feedback.alpha = 1f
            feedback.animate().alpha(0f).setStartDelay(FEEDBACK_MS).setDuration(FADE_MS).start()
        }

        private fun close() {
            animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                visibility = View.GONE
                placed = false
                onDemoClosed()
            }.start()
        }

        private fun titleBar(): View {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(TITLE_COLOR)
            }
            bar.addView(grip(), LinearLayout.LayoutParams(buttonWidth, titleHeight))
            bar.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            bar.addView(TextView(context).apply {
                text = "✕"
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (step == Step.Close || step !in WINDOW_STEPS && step !is Step.Gesture) close() else nudge()
                }
            }, LinearLayout.LayoutParams(buttonWidth, titleHeight))
            attachTitleGestures(bar)
            return bar
        }

        /** Closing early would hide the window the current step needs, so it only wobbles. */
        private fun nudge() {
            animate().translationXBy(12 * density).setDuration(60).withEndAction {
                animate().translationXBy(-12 * density).setDuration(60).start()
            }.start()
        }

        private fun attachTitleGestures(bar: View) {
            val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
            var startX = 0f
            var startY = 0f
            var downRawX = 0f
            var downRawY = 0f
            var dragging = false
            var taps = 0
            var lastTap = 0L
            val dispatchTaps = Runnable {
                if (taps == 2) onDemoGesture(WindowGesture.TITLE_DOUBLE_TAP)
                taps = 0
            }
            val hold = Runnable {
                bar.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onDemoGesture(WindowGesture.TITLE_HOLD)
            }
            bar.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = x
                        startY = y
                        dragging = false
                        bar.postDelayed(hold, HOLD_MS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (hypot(dx, dy) > touchSlop) {
                            dragging = true
                            bar.removeCallbacks(hold)
                        }
                        if (dragging && filled == null) {
                            x = (startX + dx).coerceIn(-windowWidth / 2f, this@TutorialOverlay.width - windowWidth / 2f)
                            y = (startY + dy).coerceIn(areaTop(), areaBottom() - titleHeight * 2)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        bar.removeCallbacks(hold)
                        if (dragging) {
                            onDemoMoved(hypot(x - startX, y - startY))
                        } else {
                            bar.removeCallbacks(dispatchTaps)
                            taps = if (event.eventTime - lastTap <= doubleTapTimeout) taps + 1 else 1
                            lastTap = event.eventTime
                            val tripleUnused = prefs.windowAction(WindowGesture.TITLE_TRIPLE_TAP) == WindowAction.NONE
                            when {
                                taps >= 3 -> {
                                    taps = 0
                                    onDemoGesture(WindowGesture.TITLE_TRIPLE_TAP)
                                }
                                taps == 2 && tripleUnused -> dispatchTaps.run()
                                else -> bar.postDelayed(dispatchTaps, doubleTapTimeout)
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> bar.removeCallbacks(hold)
                }
                true
            }
        }

        /** Same as the real window: a tap is the grip gesture, a drag resizes from the top-left. */
        private fun grip() = TextView(context).apply {
            text = "◤"
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.LTGRAY)
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0f
            var startY = 0f
            var startWidth = 0
            var startHeight = 0
            var resizing = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = this@DemoWindow.x
                        startY = this@DemoWindow.y
                        startWidth = windowWidth
                        startHeight = windowHeight
                        resizing = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop) resizing = true
                        if (resizing && filled == null) {
                            val right = startX + startWidth
                            val bottom = startY + startHeight
                            windowWidth = (startWidth - (event.rawX - downRawX)).toInt().coerceIn(minWidth, right.toInt())
                            windowHeight = (startHeight - (event.rawY - downRawY)).toInt()
                                .coerceIn(titleHeight + minWidth / 2, (bottom - areaTop()).toInt())
                            this@DemoWindow.x = right - windowWidth
                            this@DemoWindow.y = bottom - windowHeight
                            requestLayout()
                        }
                    }
                    MotionEvent.ACTION_UP -> if (resizing) {
                        onDemoResized(abs(windowWidth - startWidth) + abs(windowHeight - startHeight).toFloat())
                    } else {
                        onDemoGesture(WindowGesture.GRIP_TAP)
                    }
                }
                true
            }
        }
    }

    private companion object {
        val WINDOW_STEPS = setOf(Step.Move, Step.Resize, Step.Close)
        const val DIM = 0xB3000000.toInt()
        const val CARD_COLOR = 0xFF1B1B20.toInt()
        const val TITLE_COLOR = 0xEE1B1B20.toInt()
        const val ACCENT = 0xFFFFC857.toInt()
        const val MARGIN_DP = 16
        const val TITLE_HEIGHT_DP = 24
        const val BUTTON_WIDTH_DP = 44
        const val MIN_WIDTH_DP = 140
        const val START_WIDTH_RATIO = 0.55f
        const val PHONE_RATIO = 1.8f
        const val MOVE_DISTANCE_DP = 60
        const val RESIZE_DISTANCE_DP = 40
        const val SWIPE_DISTANCE_DP = 80
        const val HOLD_MS = 1500L
        const val SETTINGS_HOLD_MS = 1500L
        const val SUCCESS_PAUSE_MS = 700L
        const val FEEDBACK_MS = 900L
        const val FADE_MS = 250L

        // Must match the desktop's own swipe zone in MainActivity.
        const val PAN_ZONE_HEIGHT_DP = 200
        const val PAN_ZONE_WIDTH_RATIO = 0.6f
    }
}
