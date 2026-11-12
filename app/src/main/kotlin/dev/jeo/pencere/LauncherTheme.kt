package dev.jeo.pencere

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface

/** How the desktop is laid out: the original centred clock, or the tray with panels. */
enum class DesktopLayout(val labelRes: Int) {
    TRAY(R.string.layout_tray),
    CLASSIC(R.string.layout_classic),
}

/** Ready-made colour sets for the tray layout. */
enum class Palette(
    val labelRes: Int,
    val clock: Int,
    val accent: Int,
    val surface: Int,
    val text: Int,
    val muted: Int,
    /** Extra colours; the fourth marks the calendar line under the clock. */
    val icons: List<Int>,
) {
    MEADOW(
        R.string.palette_meadow,
        clock = 0xFFA6E3A1.toInt(),
        accent = 0xFFEBCB8B.toInt(),
        surface = 0xFF2E3440.toInt(),
        text = 0xFFD8DEE9.toInt(),
        muted = 0xFF8790A3.toInt(),
        icons = listOf(0xFFA3BE8C, 0xFF88C0D0, 0xFFF0A35E, 0xFF81A1C1, 0xFFA3BE8C).map(Long::toInt),
    ),
    OCEAN(
        R.string.palette_ocean,
        clock = 0xFF8FD3FE.toInt(),
        accent = 0xFF7DCFFF.toInt(),
        surface = 0xFF1E2A3A.toInt(),
        text = 0xFFD6E4F0.toInt(),
        muted = 0xFF7F95AD.toInt(),
        icons = listOf(0xFF7DCFFF, 0xFF89DDFF, 0xFFFFC777, 0xFF82AAFF, 0xFFC3E88D).map(Long::toInt),
    ),
    LAVENDER(
        R.string.palette_lavender,
        clock = 0xFFCBA6F7.toInt(),
        accent = 0xFFF5C2E7.toInt(),
        surface = 0xFF302D41.toInt(),
        text = 0xFFE0DEF4.toInt(),
        muted = 0xFF908CAA.toInt(),
        icons = listOf(0xFFA6E3A1, 0xFF89DCEB, 0xFFFAB387, 0xFFB4BEFE, 0xFFA6E3A1).map(Long::toInt),
    ),
    MONO(
        R.string.palette_mono,
        clock = 0xFFECEFF4.toInt(),
        accent = 0xFFD8DEE9.toInt(),
        surface = 0xFF1C1C1F.toInt(),
        text = 0xFFE5E5E5.toInt(),
        muted = 0xFF8A8A8F.toInt(),
        icons = listOf(0xFFE5E5E5, 0xFFE5E5E5, 0xFFE5E5E5, 0xFFE5E5E5, 0xFFE5E5E5).map(Long::toInt),
    ),
}

/** Colours offered when the accent or clock colour is set by hand. */
enum class Swatch(val labelRes: Int, val color: Int) {
    GREEN(R.string.color_green, 0xFFA6E3A1.toInt()),
    MINT(R.string.color_mint, 0xFF8FD6B4.toInt()),
    YELLOW(R.string.color_yellow, 0xFFEBCB8B.toInt()),
    ORANGE(R.string.color_orange, 0xFFF0A35E.toInt()),
    RED(R.string.color_red, 0xFFE06C75.toInt()),
    PINK(R.string.color_pink, 0xFFF5C2E7.toInt()),
    PURPLE(R.string.color_purple, 0xFFCBA6F7.toInt()),
    BLUE(R.string.color_blue, 0xFF82AAFF.toInt()),
    CYAN(R.string.color_cyan, 0xFF88C0D0.toInt()),
    WHITE(R.string.color_white, 0xFFECEFF4.toInt()),
}

/** How strongly app icons in the tray take on the theme's colours. */
enum class IconShade(val labelRes: Int, val strength: Float) {
    FULL(R.string.icon_shade_full, 1f),
    HALF(R.string.icon_shade_half, 0.5f),
    OFF(R.string.icon_shade_off, 0f),
}

enum class FontChoice(val labelRes: Int, val typeface: Typeface) {
    MONOSPACE(R.string.font_monospace, Typeface.MONOSPACE),
    SANS(R.string.font_sans, Typeface.SANS_SERIF),
    SERIF(R.string.font_serif, Typeface.SERIF),
}

/** Everything the tray layout and its windows are drawn with. */
data class LauncherTheme(
    val layout: DesktopLayout,
    val palette: Palette,
    val clock: Int,
    val accent: Int,
    val typeface: Typeface,
    val iconShade: IconShade,
) {
    /**
     * A duotone shader for icons: each pixel's brightness picks a colour between the theme's
     * surface (dark parts) and its clock colour (light parts). Weaker shades mix in the original
     * colours. Null when shading is off.
     */
    val iconFilter: ColorMatrixColorFilter? by lazy {
        val strength = iconShade.strength
        if (strength == 0f) return@lazy null
        val dark = surface
        val light = clock
        val duotone = FloatArray(20)
        listOf(Color.red(dark) to Color.red(light), Color.green(dark) to Color.green(light), Color.blue(dark) to Color.blue(light))
            .forEachIndexed { row, (from, to) ->
                val range = (to - from) / 255f
                duotone[row * 5] = range * 0.2126f
                duotone[row * 5 + 1] = range * 0.7152f
                duotone[row * 5 + 2] = range * 0.0722f
                duotone[row * 5 + 4] = from.toFloat()
            }
        duotone[18] = 1f
        val identity = ColorMatrix().array
        ColorMatrixColorFilter(FloatArray(20) { identity[it] * (1 - strength) + duotone[it] * strength })
    }

    val surface get() = palette.surface
    val text get() = palette.text
    val muted get() = palette.muted
    val isTray get() = layout == DesktopLayout.TRAY

    companion object {
        /** Hand-picked colours override the palette's own; a new palette clears them. */
        fun load(prefs: LauncherPrefs): LauncherTheme {
            val palette = prefs.palette
            return LauncherTheme(
                layout = prefs.desktopLayout,
                palette = palette,
                clock = prefs.clockColor ?: palette.clock,
                accent = prefs.accentColor ?: palette.accent,
                typeface = FontStore.typeface(prefs.appContext, prefs.fontKey),
                iconShade = prefs.iconShade,
            )
        }
    }
}

/** The same colour a step darker, for gradients that stay in one hue. */
fun Int.darker(factor: Float = 0.7f): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(this, hsv)
    hsv[2] *= factor
    return Color.HSVToColor(Color.alpha(this), hsv)
}

/** Same colour at a new opacity, 0..1. */
fun Int.withAlpha(alpha: Float): Int = (this and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)
