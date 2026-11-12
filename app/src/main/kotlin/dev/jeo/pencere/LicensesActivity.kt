package dev.jeo.pencere

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The open source licenses of everything bundled in the app. The list shows each library with
 * its copyright and license; tapping one shows the full license texts, as the licenses ask.
 */
class LicensesActivity : Activity() {
    private data class Library(
        val name: String,
        val copyright: String,
        val license: String,
        val url: String,
        /** Text files under assets/licenses, shown one after the other. */
        val files: List<String>,
        val note: String? = null,
    )

    private val libraries = listOf(
        Library("FluidLauncher", "Copyright (c) 2026 Jeoalist", "MIT", "https://github.com/Jeoalist/fluidlauncher", listOf("fluidlauncher-mit.txt")),
        Library(
            "libadb-android", "Copyright 2021 Muntashir Al-Islam", "Apache-2.0",
            "https://github.com/MuntashirAkon/libadb-android", listOf("apache-2.0.txt"),
            note = "Dual licensed under GPL-3.0-or-later or Apache-2.0; used here under Apache-2.0.",
        ),
        Library(
            "spake2-java", "Copyright Muntashir Al-Islam", "LGPL-3.0",
            "https://github.com/MuntashirAkon/spake2-java", listOf("lgpl-3.0.txt", "gpl-3.0.txt"),
            note = "Used unmodified. Its source code is available at the link above, and FluidLauncher " +
                "can be rebuilt from its own source with a modified version of it. Its Ed25519 code is " +
                "adapted from EdDSA-Java (CC0).",
        ),
        Library(
            "Conscrypt", "Copyright The Android Open Source Project", "Apache-2.0",
            "https://github.com/google/conscrypt", listOf("conscrypt-notice.txt", "apache-2.0.txt"),
        ),
        Library(
            "BoringSSL", "Copyright The BoringSSL Authors, The OpenSSL Project and Eric Young", "Apache-2.0 / OpenSSL / ISC",
            "https://boringssl.googlesource.com/boringssl", listOf("boringssl.txt", "boringssl-openssl.txt"),
            note = "Built into Conscrypt's native library. Newer BoringSSL code is under Apache-2.0; " +
                "older code it carries is under the OpenSSL, SSLeay and ISC licenses, both shown below.",
        ),
        Library(
            "Bouncy Castle", "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc.", "Bouncy Castle License (MIT)",
            "https://www.bouncycastle.org", listOf("bouncycastle.txt"),
        ),
        Library(
            "AndroidX Annotation", "Copyright The Android Open Source Project", "Apache-2.0",
            "https://developer.android.com/jetpack/androidx", listOf("apache-2.0.txt"),
        ),
        Library(
            "Kotlin Standard Library", "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors", "Apache-2.0",
            "https://kotlinlang.org", listOf("apache-2.0.txt"),
        ),
    )

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
        val index = intent.getIntExtra(EXTRA_LIBRARY, -1)
        if (index in libraries.indices) showTexts(libraries[index]) else showList()
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

    private fun showList() {
        addText(getString(R.string.licenses_title), 24f, padding * 2)
        addText(getString(R.string.licenses_intro), 14f, 0).alpha = 0.7f
        libraries.forEachIndexed { index, library ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding * 3 / 4, padding, padding * 3 / 4)
                val outValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener {
                    startActivity(Intent(this@LicensesActivity, LicensesActivity::class.java).putExtra(EXTRA_LIBRARY, index))
                }
            }
            row.addView(TextView(this).apply {
                text = library.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            row.addView(TextView(this).apply {
                text = "${library.license} · ${library.copyright}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                alpha = 0.7f
            })
            list.addView(row)
        }
    }

    /** The full texts of one library's licenses, with its source link. */
    private fun showTexts(library: Library) {
        addText(library.name, 24f, padding * 2)
        addText("${library.license}\n${library.copyright}", 14f, 0).alpha = 0.7f
        library.note?.let { addText(it, 14f, 0) }
        addText(library.url, 14f, 0).apply {
            setTextColor(0xFF82AAFF.toInt())
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(library.url))) }
        }
        library.files.forEach { file ->
            val text = assets.open("licenses/$file").bufferedReader().use { it.readText() }
            // Keeps the license's own line breaks and scrolls sideways, instead of breaking
            // its 80-column lines in the middle.
            val body = TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                setHorizontallyScrolling(true)
                setTextIsSelectable(true)
                setPadding(padding, padding, padding, padding / 2)
            }
            list.addView(HorizontalScrollView(this).apply { addView(body) })
            list.addView(View(this).apply { minimumHeight = padding })
        }
    }

    private fun addText(text: String, sizeSp: Float, topPadding: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setPadding(padding, topPadding, padding, padding / 2)
        list.addView(this)
    }

    private companion object {
        const val EXTRA_LIBRARY = "library"
    }
}
