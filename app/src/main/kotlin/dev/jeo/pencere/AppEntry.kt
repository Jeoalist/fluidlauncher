package dev.jeo.pencere

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator
import java.util.Locale

data class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: Drawable,
) {
    val packageName: String get() = component.packageName
}

val TURKISH: Locale = Locale.forLanguageTag("tr-TR")

/** Reads every launchable app across profiles; slow, so call it off the main thread. */
fun loadApps(context: Context): List<AppEntry> {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val users = context.getSystemService(UserManager::class.java)
    val collator = Collator.getInstance(TURKISH)
    return users.userProfiles
        .flatMap { user ->
            val serial = users.getSerialNumberForUser(user)
            launcherApps.getActivityList(null, user).map { info ->
                AppEntry(
                    key = "${info.componentName.flattenToString()}#$serial",
                    label = info.label.toString(),
                    component = info.componentName,
                    user = user,
                    icon = info.getBadgedIcon(0),
                )
            }
        }
        .filterNot { it.packageName == context.packageName && it.user == Process.myUserHandle() }
        .sortedWith(compareBy(collator) { it.label })
}
