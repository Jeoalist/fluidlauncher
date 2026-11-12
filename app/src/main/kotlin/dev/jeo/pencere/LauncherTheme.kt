package dev.jeo.pencere

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    ;

    /** This ready-made theme as colours a custom theme can start from. */
    fun colors(name: String) = ThemeColors(KEY_PREFIX + this.name, name, clock, accent, surface, text, muted, icons[3])

    companion object {
        const val KEY_PREFIX = "preset:"
    }
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
/**
 * The colours of a theme, ready-made or made by hand in the theme editor. [key] is how the
 * settings refer to it: "preset:MEADOW" for a ready-made one, "custom:<id>" for a custom one.
 */
data class ThemeColors(
    val key: String,
    val name: String,
    val clock: Int,
    val accent: Int,
    val surface: Int,
    val text: Int,
    val muted: Int,
    /** The bar and time of the calendar line under the clock. */
    val line: Int,
) {
    val isCustom get() = key.startsWith(ThemeStore.CUSTOM_PREFIX)

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("name", name).put("clock", clock).put("accent", accent)
        .put("surface", surface).put("text", text).put("muted", muted).put("line", line)

    companion object {
        fun fromJson(json: JSONObject) = ThemeColors(
            json.getString("key"), json.getString("name"), json.getInt("clock"), json.getInt("accent"),
            json.getInt("surface"), json.getInt("text"), json.getInt("muted"), json.getInt("line"),
        )
    }
}

/** Ready-made themes and the ones people make, and which one is chosen. */
object ThemeStore {
    const val CUSTOM_PREFIX = "custom:"

    fun presets(context: Context) = Palette.entries.map { it.colors(context.getString(it.labelRes)) }

    fun custom(prefs: LauncherPrefs): List<ThemeColors> {
        val array = runCatching { JSONArray(prefs.customThemesJson) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { runCatching { ThemeColors.fromJson(array.getJSONObject(it)) }.getOrNull() }
    }

    fun all(context: Context, prefs: LauncherPrefs) = presets(context) + custom(prefs)

    /** The chosen theme; one that was deleted falls back to the first ready-made theme. */
    fun chosen(context: Context, prefs: LauncherPrefs): ThemeColors {
        val key = prefs.themeKey
        return all(context, prefs).firstOrNull { it.key == key } ?: presets(context).first()
    }

    /** Adds or replaces a custom theme and chooses it. */
    fun save(prefs: LauncherPrefs, theme: ThemeColors) {
        val themes = custom(prefs).filter { it.key != theme.key } + theme
        prefs.customThemesJson = JSONArray(themes.map { it.toJson() }).toString()
        prefs.themeKey = theme.key
    }

    fun delete(prefs: LauncherPrefs, key: String) {
        prefs.customThemesJson = JSONArray(custom(prefs).filter { it.key != key }.map { it.toJson() }).toString()
        if (prefs.themeKey == key) prefs.themeKey = Palette.KEY_PREFIX + Palette.MEADOW.name
    }

    fun newKey() = CUSTOM_PREFIX + UUID.randomUUID().toString()
}

data class LauncherTheme(
    val layout: DesktopLayout,
    val colors: ThemeColors,
    val typeface: Typeface,
    val iconShade: IconShade,
) {
    val clock get() = colors.clock
    val accent get() = colors.accent
    val line get() = colors.line

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

    val surface get() = colors.surface
    val text get() = colors.text
    val muted get() = colors.muted
    val isTray get() = layout == DesktopLayout.TRAY

    companion object {
        fun load(prefs: LauncherPrefs) = LauncherTheme(
            layout = prefs.desktopLayout,
            colors = ThemeStore.chosen(prefs.appContext, prefs),
            typeface = FontStore.typeface(prefs.appContext, prefs.fontKey),
            iconShade = prefs.iconShade,
        )
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
