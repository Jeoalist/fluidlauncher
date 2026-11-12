package dev.jeo.pencere

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/** The launcher's own wide wallpaper, kept as a copy in the app's files. */
object ScrollingWallpaper {
    private const val FILE_NAME = "scrolling_wallpaper"

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun exists(context: Context) = file(context).exists()

    /** Copies the picked image, so it survives the picker's permission running out. */
    fun save(context: Context, uri: Uri) {
        val target = file(context)
        val temporary = File(context.filesDir, "$FILE_NAME.new")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().use { input.copyTo(it) }
        } ?: error("Could not read $uri")
        temporary.renameTo(target)
        LauncherPrefs(context).wallpaperVersion = System.currentTimeMillis()
    }

    fun remove(context: Context) {
        file(context).delete()
        LauncherPrefs(context).wallpaperVersion = System.currentTimeMillis()
    }
}

/**
 * Draws [ScrollingWallpaper] across all desktops: the image covers the screen and moves by
 * [progress], from its left edge on the first desktop to its right edge on the last.
 */
class WallpaperView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var loadedVersion = -1L

    /** 0 on the first desktop, 1 on the last. */
    var progress = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    val hasImage get() = bitmap != null

    /** Reloads when the image was changed in the settings; [onLoaded] tells whether one is shown. */
    fun refresh(onLoaded: (Boolean) -> Unit) {
        val version = LauncherPrefs(context).wallpaperVersion
        if (version == loadedVersion) return
        loadedVersion = version
        val file = ScrollingWallpaper.file(context)
        // Decoded to about the screen's longer side, which is all that is ever shown at once.
        val longSide = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        loader.execute {
            val decoded = if (file.exists()) {
                try {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        val scale = minOf(1f, longSide.toFloat() / info.size.height)
                        decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                    }
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            main.post {
                bitmap = decoded
                visibility = if (decoded != null) VISIBLE else GONE
                invalidate()
                onLoaded(decoded != null)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        val scale = max(width / image.width.toFloat(), height / image.height.toFloat())
        val extra = image.width * scale - width
        matrix.setScale(scale, scale)
        matrix.postTranslate(-extra * progress.coerceIn(0f, 1f), (height - image.height * scale) / 2f)
        canvas.drawBitmap(image, matrix, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loader.shutdown()
    }
}
