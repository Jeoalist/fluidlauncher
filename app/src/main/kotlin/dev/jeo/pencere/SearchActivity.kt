package dev.jeo.pencere

import android.app.Activity
import android.graphics.Insets
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout

/** Small search popup shown next to the apps button; [getIntent]'s source bounds are that button. */
class SearchActivity : Activity() {
    private val prefs by lazy { LauncherPrefs(this) }
    private lateinit var root: View
    private lateinit var panel: View
    private lateinit var search: EditText
    private lateinit var slots: List<ImageView>

    private var bars: Insets = Insets.NONE
    private var margin = 0
    private var gap = 0
    private var results: List<AppEntry> = emptyList()
    private val onCatalogChanged = { showResults() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        root = findViewById(R.id.root)
        panel = findViewById(R.id.panel)
        search = findViewById(R.id.search)

        val density = resources.displayMetrics.density
        margin = (12 * density).toInt()
        gap = (10 * density).toInt()
        val iconPadding = (6 * density).toInt()
        val row = findViewById<LinearLayout>(R.id.icons)
        slots = List(SLOT_COUNT) { index ->
            ImageView(this).apply {
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                setOnClickListener { open(results[index], it) }
                setOnLongClickListener {
                    showAppMenu(results[index], it, ::finish)
                    true
                }
                row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
        }

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

    override fun onDestroy() {
        AppCatalog.removeListener(onCatalogChanged)
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
        results = matches(search.text.toString().trim()).take(SLOT_COUNT)
        slots.forEachIndexed { index, slot ->
            val app = results.getOrNull(index)
            slot.setImageDrawable(app?.icon)
            slot.contentDescription = app?.label
            slot.visibility = if (app == null) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun matches(query: String): List<AppEntry> {
        val apps = AppCatalog.apps
        if (query.isEmpty()) {
            val recent = prefs.recentKeys.mapNotNull { key -> apps.firstOrNull { it.key == key } }
            return recent + (apps - recent.toSet())
        }
        val needle = searchKey(query)
        return apps
            .map { it to matchRank(searchKey(it.label), needle) }
            .filter { it.second != NO_MATCH }
            .sortedBy { it.second }
            .map { it.first }
    }

    private fun matchRank(label: String, needle: String): Int = when {
        label.startsWith(needle) -> 0
        " $needle" in label -> 1
        needle in label -> 2
        else -> NO_MATCH
    }

    private fun open(app: AppEntry, source: View) {
        launchApp(app, prefs.windowed, source)
        finish()
    }

    private companion object {
        const val SLOT_COUNT = 4
        const val NO_MATCH = Int.MAX_VALUE
        val TURKISH_FOLDS = mapOf('ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u')

        /** Lets "cicek" match "Çiçeksepeti". */
        fun searchKey(text: String): String =
            text.lowercase(TURKISH).map { TURKISH_FOLDS[it] ?: it }.joinToString("")
    }
}
