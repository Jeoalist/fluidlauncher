package dev.jeo.pencere

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * App icons drawn once into bitmaps at list size, with the theme's shading already applied.
 * Drawing an adaptive icon masks its layers every time, and the shading is a colour matrix;
 * doing that for every row while the list jumps along the alphabet rail made it stutter.
 */
object IconCache {
    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val warmer = Executors.newSingleThreadExecutor()

    /** Changes when the shading changes, so old bitmaps are no longer looked up. */
    @Volatile
    private var generation = 0
    private var filter: ColorFilter? = null
    private var filterKey: Any? = null

    /** [key] identifies the shading, such as the theme's colours; a new one starts afresh. */
    @Synchronized
    fun setFilter(key: Any?, newFilter: ColorFilter?) {
        if (key == filterKey) return
        filterKey = key
        filter = newFilter
        generation++
        cache.evictAll()
    }

    /** The cached bitmap, or null while it has not been drawn yet. */
    fun peek(app: AppEntry, size: Int): Bitmap? = cache.get(keyOf(app, size))

    /** Draws it now if needed; on the main thread only for the rows on screen. */
    fun get(app: AppEntry, size: Int): Bitmap {
        val key = keyOf(app, size)
        cache.get(key)?.let { return it }
        return render(app, size).also { cache.put(key, it) }
    }

    /** Draws every icon in the background, so scrolling finds them ready. */
    fun warm(apps: List<AppEntry>, size: Int, onReady: () -> Unit) {
        val start = generation
        warmer.execute {
            for (app in apps) {
                if (generation != start) return@execute
                val key = keyOf(app, size)
                if (cache.get(key) == null) cache.put(key, render(app, size))
            }
            onReady()
        }
    }

    private fun keyOf(app: AppEntry, size: Int) = "$generation/$size/${app.key}"

    private fun render(app: AppEntry, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // A copy of the drawable: the original is shared with other screens and threads.
        val drawable = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
        drawable.setBounds(0, 0, size, size)
        val canvas = Canvas(bitmap)
        val shading = filter
        if (shading == null) {
            drawable.draw(canvas)
        } else {
            // Drawn into a layer so the shading applies to the whole icon at once.
            val paint = Paint().apply { colorFilter = shading }
            canvas.saveLayer(null, paint)
            drawable.draw(canvas)
            canvas.restore()
        }
        return bitmap
    }

    private const val MAX_BYTES = 24 * 1024 * 1024
}
