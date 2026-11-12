package dev.jeo.pencere

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Counts the notifications in the shade for the tray's bell, and remembers which apps have some
 * so the app list can mark them. Only runs once notification access is granted.
 */
class NotificationCounter : NotificationListenerService() {

    override fun onListenerConnected() = publish()

    override fun onListenerDisconnected() {
        update(0, emptySet())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = publish()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = publish()

    private fun publish() {
        // Group summaries repeat their children, and ongoing ones (music, downloads) are not news.
        val shown = activeNotifications.orEmpty().filter {
            it.isClearable && !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
        update(shown.size, shown.map { it.packageName }.toSet())
    }

    companion object {
        var count = 0
            private set
        var packages: Set<String> = emptySet()
            private set
        private val listeners = LinkedHashSet<() -> Unit>()

        fun addListener(listener: () -> Unit) {
            listeners += listener
        }

        fun removeListener(listener: () -> Unit) {
            listeners -= listener
        }

        private fun update(newCount: Int, newPackages: Set<String>) {
            count = newCount
            packages = newPackages
            listeners.toList().forEach { it() }
        }

        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val mine = ComponentName(context, NotificationCounter::class.java).flattenToString()
            return enabled.split(':').any { it == mine }
        }
    }
}
