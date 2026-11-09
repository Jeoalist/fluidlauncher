package dev.jeo.pencere

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/** Runs in a Shizuku-started process with shell privileges, not in the launcher's process. */
class DisplayService(context: Context) : IDisplayService.Stub() {
    // The display service checks the caller's package against its uid, which is shell's here.
    private val shell = context.createPackageContext("com.android.shell", 0)
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()

    private val inputManagerType = Class.forName("android.hardware.input.InputManagerGlobal")
    private val inputManager = inputManagerType.getMethod("getInstance").invoke(null)
    private val injectInputEvent =
        inputManagerType.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    private val setDisplayId =
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)

    override fun destroy() {
        displays.values.forEach(VirtualDisplay::release)
        exitProcess(0)
    }

    override fun createDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int {
        val display = shell.getSystemService(DisplayManager::class.java)
            .createVirtualDisplay(name, width, height, dpi, surface, DISPLAY_FLAGS)
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
