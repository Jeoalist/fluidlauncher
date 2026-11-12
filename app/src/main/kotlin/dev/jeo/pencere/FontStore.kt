package dev.jeo.pencere

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Fonts imported from files, kept as copies in the app's own storage. The chosen font is saved
 * as a key: a built-in [FontChoice] name, or [FILE_PREFIX] and a file name for an imported one.
 */
object FontStore {
    const val FILE_PREFIX = "file:"
    private val cache = HashMap<String, Typeface>()

    private fun dir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    fun imported(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()

    fun keyOf(file: File) = FILE_PREFIX + file.name

    fun nameOf(file: File) = file.nameWithoutExtension

    /** The typeface for a saved key; an unreadable or deleted font falls back to monospace. */
    fun typeface(context: Context, key: String): Typeface {
        if (key.startsWith(FILE_PREFIX)) {
            val name = key.removePrefix(FILE_PREFIX)
            cache[name]?.let { return it }
            val file = File(dir(context), name)
            val loaded = if (file.exists()) Typeface.Builder(file).build() else null
            if (loaded != null) cache[name] = loaded
            return loaded ?: Typeface.MONOSPACE
        }
        return FontChoice.entries.firstOrNull { it.name == key }?.typeface ?: Typeface.MONOSPACE
    }

    /** What the settings show for a saved key. */
    fun label(context: Context, key: String): String =
        if (key.startsWith(FILE_PREFIX)) {
            File(key.removePrefix(FILE_PREFIX)).nameWithoutExtension
        } else {
            context.getString((FontChoice.entries.firstOrNull { it.name == key } ?: FontChoice.MONOSPACE).labelRes)
        }

    /**
     * Copies a picked font file in and checks that Android can read it. Returns the new font's
     * key, or null when the file is not a usable font. Slow, so call it off the main thread.
     */
    fun import(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val original = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "font.ttf"
        val temporary = File(dir(context), ".import")
        resolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { input.copyTo(it) } } ?: return null
        if (Typeface.Builder(temporary).build() == null) {
            temporary.delete()
            return null
        }
        val target = uniqueFile(context, safeName(original))
        temporary.renameTo(target)
        return keyOf(target)
    }

    fun delete(context: Context, file: File) {
        cache.remove(file.name)
        file.delete()
    }

    /** Keeps letters, digits and a few marks, so the name is a safe file name. */
    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().ifEmpty { "font.ttf" }
        return if (cleaned.contains('.')) cleaned else "$cleaned.ttf"
    }

    private fun uniqueFile(context: Context, name: String): File {
        val folder = dir(context)
        var file = File(folder, name)
        var number = 2
        while (file.exists()) {
            file = File(folder, "${name.substringBeforeLast('.')} ($number).${name.substringAfterLast('.')}")
            number++
        }
        return file
    }
}
