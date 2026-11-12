package dev.jeo.pencere

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.Calendar

/** An event shown under the tray clock. */
data class UpcomingEvent(val title: String, val begin: Long, val end: Long)

object CalendarEvents {
    fun canRead(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** The event happening now, or else the next one today; all-day events are left out. */
    fun next(context: Context): UpcomingEvent? {
        if (!canRead(context)) return null
        val now = System.currentTimeMillis()
        val endOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, endOfDay)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getInt(3) == 1) continue
                    val end = cursor.getLong(2)
                    if (end <= now) continue
                    return@use UpcomingEvent(cursor.getString(0).orEmpty(), cursor.getLong(1), end)
                }
                null
            }
        } catch (_: SecurityException) {
            null
        }
    }
}
