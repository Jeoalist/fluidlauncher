package dev.jeo.pencere

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * Makes a theme from the wallpaper: the launcher's own scrolling wallpaper when one is picked,
 * otherwise the system one. The wallpaper's main colours are bent into roles that stay readable:
 * a dark surface, a light clock, a bright accent and a near-white text tinted with the main hue.
 */
object WallpaperTheme {
    /** Null when there is no wallpaper to read, such as a live wallpaper without colours. */
    fun generate(context: Context, key: String, name: String): ThemeColors? {
        val colors = wallpaperColors(context) ?: return null
        val primary = colors.primaryColor.toArgb()
        val secondary = colors.secondaryColor?.toArgb() ?: primary
        val tertiary = colors.tertiaryColor?.toArgb() ?: secondary
        // The most colourful of the three makes the best accent.
        val vivid = listOf(primary, secondary, tertiary).maxBy { saturation(it) }
        return ThemeColors(
            key = key,
            name = name,
            clock = shade(primary, saturation = 0.45f, value = 0.95f),
            accent = shade(vivid, saturation = 0.6f, value = 0.95f),
            surface = shade(primary, saturation = 0.35f, value = 0.18f, maxSaturation = true),
            text = shade(primary, saturation = 0.08f, value = 0.93f, maxSaturation = true),
            muted = shade(primary, saturation = 0.12f, value = 0.62f, maxSaturation = true),
            line = shade(tertiary, saturation = 0.45f, value = 0.85f),
        )
    }

    private fun wallpaperColors(context: Context): WallpaperColors? {
        val file = ScrollingWallpaper.file(context)
        if (file.exists()) {
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            BitmapFactory.decodeFile(file.path, options)?.let { return WallpaperColors.fromBitmap(it) }
        }
        return WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
    }

    private fun saturation(color: Int) = FloatArray(3).also { Color.colorToHSV(color, it) }[1]

    /**
     * The colour's hue at a set brightness. Its saturation is raised to at least [saturation], or
     * with [maxSaturation] held at most that, so dark and pale roles do not turn garish.
     */
    private fun shade(color: Int, saturation: Float, value: Float, maxSaturation: Boolean = false): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = if (maxSaturation) minOf(hsv[1], saturation) else maxOf(hsv[1], saturation)
        hsv[2] = value
        return Color.HSVToColor(hsv)
    }
}
