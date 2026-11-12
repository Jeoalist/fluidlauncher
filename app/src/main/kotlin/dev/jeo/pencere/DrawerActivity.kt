package dev.jeo.pencere

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView

/** Full app drawer, an alternative to the small search popup chosen in the settings. */
class DrawerActivity : Activity() {
    private val prefs by lazy { LauncherPrefs(this) }
    private val adapter = AppAdapter()
    private lateinit var search: EditText
    private val onCatalogChanged = { adapter.filter(search.text.toString()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppsPanel.onShown(this)
        setContentView(R.layout.activity_drawer)
        search = findViewById(R.id.search)
        val grid = findViewById<GridView>(R.id.grid)
        val margin = (12 * resources.displayMetrics.density).toInt()
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            // A side camera cutout in landscape is kept clear as well.
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(bars.left + margin, bars.top + margin, bars.right + margin, bars.bottom)
            insets
        }
        grid.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
        })
        search.setOnEditorActionListener { view, action, _ ->
            val first = adapter.first()
            if (action == EditorInfo.IME_ACTION_GO && first != null) open(first, view)
            action == EditorInfo.IME_ACTION_GO
        }
        AppCatalog.addListener(onCatalogChanged)
        adapter.filter("")
    }

    override fun onResume() {
        super.onResume()
        preferChosenMode()
        applySystemBars(hideStatusBar = AppWindows.current?.isFullscreenVisible == true)
    }

    override fun onDestroy() {
        AppsPanel.onGone(this)
        AppCatalog.removeListener(onCatalogChanged)
        super.onDestroy()
    }

    private fun open(app: AppEntry, source: View) {
        launchApp(app, prefs.windowed, source)
        finish()
    }

    private inner class AppAdapter : BaseAdapter() {
        private var shown: List<AppEntry> = emptyList()

        fun first(): AppEntry? = shown.firstOrNull()

        fun filter(query: String) {
            shown = matchApps(AppCatalog.apps, query)
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].key.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            val app = shown[position]
            view.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.label).text = app.label
            view.setOnClickListener { open(app, it) }
            view.setOnLongClickListener {
                showAppMenu(app, it, ::finish)
                true
            }
            return view
        }
    }
}
