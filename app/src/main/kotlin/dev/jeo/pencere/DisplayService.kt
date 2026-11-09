package dev.jeo.pencere

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

/** Runs in the shell-privileged process started by [DisplayMain], not in the launcher's. */
class DisplayService(private val shell: Context) : IDisplayService.Stub() {
    // Built directly so that it reports the shell package; the system's own instance reports "android".
    private val displayManager = DisplayManager::class.java
        .getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }
        .newInstance(shell)
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()

    private val inputManagerType = Class.forName("android.hardware.input.InputManagerGlobal")
    private val inputManager = inputManagerType.getMethod("getInstance").invoke(null)
    private val injectInputEvent =
        inputManagerType.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    private val setDisplayId =
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)

    override fun createDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int {
        val display = displayManager.createVirtualDisplay(name, width, height, dpi, surface, DISPLAY_FLAGS)
        val id = display.display.displayId
        displays[id] = display
        return id
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
    }

    override fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int) {
        displays[displayId]?.resize(width, height, dpi)
    }

    /** Most recently used first, across every display. */
    override fun openPackages(): List<String> =
        recentTasks().mapNotNull { packageOf(it) }.distinct()

    /** Same effect as swiping an app away in the system's recents screen. */
    override fun closePackage(packageName: String) {
        val type = Class.forName("android.app.ActivityTaskManager")
        val service = type.getMethod("getService").invoke(null)!!
        val removeTask = service.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
        recentTasks().filter { packageOf(it) == packageName }.forEach { removeTask.invoke(service, it.taskId) }
    }

    private fun recentTasks() = shell.getSystemService(ActivityManager::class.java)
        .getRecentTasks(MAX_RECENT_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE)

    private fun packageOf(task: ActivityManager.RecentTaskInfo) =
        task.baseIntent.component?.packageName ?: task.baseActivity?.packageName

    override fun launch(displayId: Int, component: String) {
        ProcessBuilder("am", "start", "--display", displayId.toString(), "-n", component)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    override fun injectMotion(displayId: Int, event: MotionEvent) {
        setDisplayId.invoke(event, displayId)
        injectInputEvent.invoke(inputManager, event, INJECT_MODE_ASYNC)
    }

    private companion object {
        const val INJECT_MODE_ASYNC = 0
        const val MAX_RECENT_TASKS = 40

        // Values of hidden DisplayManager.VIRTUAL_DISPLAY_FLAG_* constants.
        const val FLAG_PUBLIC = 1 shl 0
        const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val FLAG_TRUSTED = 1 shl 10
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val DISPLAY_FLAGS = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
            FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
    }
}
