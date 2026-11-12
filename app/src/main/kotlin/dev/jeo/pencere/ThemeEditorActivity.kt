package dev.jeo.pencere

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Makes or edits a custom theme: a name and six colours, each picked on a colour square or typed
 * as a hex code, with a live preview of the tray at the top. It can also start from the wallpaper.
 */
class ThemeEditorActivity : Activity() {
    private val prefs by lazy { LauncherPrefs(this) }
    private lateinit var colors: ThemeColors
    private lateinit var list: LinearLayout
    private lateinit var preview: Preview
    private lateinit var name: EditText
    private var padding = 0

    /** Each role's label, getter and setter, in the order they are listed. */
    private val roles: List<Triple<Int, (ThemeColors) -> Int, (ThemeColors, Int) -> ThemeColors>> = listOf(
        Triple(R.string.role_accent, { it.accent }, { t, c -> t.copy(accent = c) }),
        Triple(R.string.role_clock, { it.clock }, { t, c -> t.copy(clock = c) }),
        Triple(R.string.role_surface, { it.surface }, { t, c -> t.copy(surface = c) }),
        Triple(R.string.role_text, { it.text }, { t, c -> t.copy(text = c) }),
        Triple(R.string.role_muted, { it.muted }, { t, c -> t.copy(muted = c) }),
        Triple(R.string.role_line, { it.line }, { t, c -> t.copy(line = c) }),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padding = (16 * resources.displayMetrics.density).toInt()
        colors = startingColors()
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        build()
    }

    override fun onStart() {
        super.onStart()
        FloatingAppsButton.ownScreenStarted(this)
    }

    override fun onStop() {
        super.onStop()
        FloatingAppsButton.ownScreenStopped(this)
    }

    override fun onResume() {
        super.onResume()
        preferChosenMode()
    }

    /** An existing custom theme to edit, a copy of the chosen theme, or the wallpaper's colours. */
    private fun startingColors(): ThemeColors {
        val editKey = intent.getStringExtra(EXTRA_EDIT)
        ThemeStore.custom(prefs).firstOrNull { it.key == editKey }?.let { return it }
        val newName = getString(R.string.theme_new_name, ThemeStore.custom(prefs).size + 1)
        if (intent.getBooleanExtra(EXTRA_FROM_WALLPAPER, false)) {
            WallpaperTheme.generate(this, ThemeStore.newKey(), getString(R.string.theme_wallpaper_name))?.let { return it }
        }
        return ThemeStore.chosen(this, prefs).copy(key = ThemeStore.newKey(), name = newName)
    }

    private fun build() {
        list.removeAllViews()
        addText(getString(R.string.theme_editor_title), 24f, padding * 2)
        preview = Preview()
        list.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (190 * resources.displayMetrics.density).toInt()).apply {
            setMargins(padding, padding / 2, padding, padding)
        })
        name = EditText(this).apply {
            setText(colors.name)
            hint = getString(R.string.theme_name)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(32))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    colors = colors.copy(name = s?.toString().orEmpty())
                }
            })
        }
        list.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(padding, 0, padding, padding / 2)
        })
        roles.forEach { (label, get, set) -> addColorRow(getString(label), get(colors)) { colors = set(colors, it); refresh() } }
        addButton(getString(R.string.theme_from_wallpaper)) {
            val generated = WallpaperTheme.generate(this, colors.key, colors.name)
            if (generated == null) {
                Toast.makeText(this, R.string.theme_wallpaper_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                colors = generated
                build()
            }
        }
        addButton(getString(R.string.theme_save), primary = true) {
            val named = colors.copy(name = colors.name.trim().ifEmpty { getString(R.string.theme_new_name, 1) })
            ThemeStore.save(prefs, named)
            finish()
        }
        if (ThemeStore.custom(prefs).any { it.key == colors.key }) {
            addButton(getString(R.string.theme_delete)) {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.theme_delete_question, colors.name))
                    .setPositiveButton(R.string.theme_delete) { _, _ ->
                        ThemeStore.delete(prefs, colors.key)
                        finish()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Redraws the preview and the swatches after a colour changed. */
    private fun refresh() {
        preview.invalidate()
        swatches.forEachIndexed { i, (dot, hex) ->
            val color = roles[i].second(colors)
            (dot.background as GradientDrawable).setColor(color)
            hex.text = hexOf(color)
        }
    }

    private val swatches = ArrayList<Pair<View, TextView>>()

    private fun addColorRow(title: String, color: Int, onPicked: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding * 3 / 4, padding, padding * 3 / 4)
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        val size = (32 * resources.displayMetrics.density).toInt()
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke((1 * resources.displayMetrics.density).toInt(), 0x55FFFFFF)
            }
        }
        row.addView(dot, LinearLayout.LayoutParams(size, size))
        row.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(padding, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val hex = TextView(this).apply {
            text = hexOf(color)
            typeface = Typeface.MONOSPACE
            alpha = 0.7f
        }
        row.addView(hex)
        val index = swatches.size
        swatches += dot to hex
        row.setOnClickListener { pickColor(title, roles[index].second(colors), onPicked) }
        list.addView(row)
    }

    /** A colour square and hue bar, plus a field to type a hex code; the preview follows live. */
    private fun pickColor(title: String, start: Int, onPicked: (Int) -> Unit) {
        val before = colors
        val picker = ColorPickerView(this).apply { color = start }
        val hex = EditText(this).apply {
            setText(hexOf(start))
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(7))
        }
        var typing = false
        picker.onChanged = { color ->
            if (!typing) hex.setText(hexOf(color))
            onPicked(color)
        }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val parsed = runCatching { Color.parseColor(s.toString().let { if (it.startsWith("#")) it else "#$it" }) }.getOrNull() ?: return
                if (s?.length != 7 && s?.length != 6) return
                typing = true
                picker.color = parsed
                onPicked(parsed)
                typing = false
            }
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(picker)
            addView(hex)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel) { _, _ ->
                colors = before
                refresh()
            }
            .show()
    }

    private fun hexOf(color: Int) = "#%06X".format(color and 0xFFFFFF)

    private fun addText(text: String, sizeSp: Float, topPadding: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setPadding(padding, topPadding, padding, padding / 2)
        list.addView(this)
    }

    private fun addButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
        list.addView(TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(padding, padding * 3 / 4, padding, padding * 3 / 4)
            background = GradientDrawable().apply {
                cornerRadius = 22 * resources.displayMetrics.density
                setColor(if (primary) this@ThemeEditorActivity.colors.accent else 0x22FFFFFF)
            }
            if (primary) setTextColor(Color.BLACK)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(padding, padding / 2, padding, padding / 2)
        })
    }

    /** A small tray: clock, date, calendar line, an app row, a window edge and the desktop picker. */
    private inner class Preview : View(this@ThemeEditorActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            val c = colors
            val w = width.toFloat()
            val h = height.toFloat()
            paint.reset()
            paint.isAntiAlias = true
            paint.color = c.surface
            canvas.drawRoundRect(0f, 0f, w, h, 18 * d, 18 * d, paint)
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textSize = 40 * d
            paint.color = c.clock
            canvas.drawText("12:34", 16 * d, 52 * d, paint)
            paint.typeface = Typeface.MONOSPACE
            paint.textSize = 13 * d
            paint.color = c.text
            canvas.drawText("thursday, 12 november", 16 * d, 74 * d, paint)
            paint.color = c.line
            canvas.drawText("▎", 16 * d, 94 * d, paint)
            paint.color = c.text
            canvas.drawText("Meeting", 30 * d, 94 * d, paint)
            paint.color = c.muted
            canvas.drawText(" · ", 92 * d, 94 * d, paint)
            paint.color = c.line
            canvas.drawText("13:00–14:00", 116 * d, 94 * d, paint)
            // An app row with its notification dot.
            paint.color = c.muted
            canvas.drawRoundRect(16 * d, 108 * d, 40 * d, 132 * d, 6 * d, 6 * d, paint)
            paint.color = c.accent
            canvas.drawCircle(40 * d, 110 * d, 4 * d, paint)
            paint.color = c.text
            paint.textSize = 15 * d
            canvas.drawText("App", 52 * d, 126 * d, paint)
            // An active window's edge.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * d
            paint.shader = android.graphics.LinearGradient(w * 0.62f, 16 * d, w - 16 * d, 130 * d, c.accent, c.accent.darker(), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(w * 0.62f, 16 * d, w - 16 * d, 130 * d, 14 * d, 14 * d, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.textSize = 12 * d
            paint.color = c.muted
            canvas.drawText("ws 1", w * 0.62f + 12 * d, 36 * d, paint)
            // The desktop picker with the first desktop chosen.
            val top = h - 44 * d
            paint.color = c.text.withAlpha(0.08f)
            canvas.drawRoundRect(16 * d, top, w - 16 * d, h - 10 * d, 17 * d, 17 * d, paint)
            val slot = (w - 32 * d) / 5
            paint.color = Color.WHITE.withAlpha(0.14f)
            canvas.drawRoundRect(20 * d, top + 4 * d, 16 * d + slot - 4 * d, h - 14 * d, 10 * d, 10 * d, paint)
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textSize = 15 * d
            listOf("一", "二", "三", "四", "五").forEachIndexed { i, label ->
                paint.color = if (i == 0) c.accent else c.clock
                canvas.drawText(label, 16 * d + slot * (i + 0.5f), h - 21 * d, paint)
            }
            paint.textAlign = Paint.Align.LEFT
        }
    }

    companion object {
        const val EXTRA_EDIT = "edit"
        const val EXTRA_FROM_WALLPAPER = "from_wallpaper"
    }
}
