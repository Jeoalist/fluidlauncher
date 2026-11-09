package dev.jeo.pencere

/** In-memory app list shared by the launcher and the search screen. Main thread only. */
object AppCatalog {
    var apps: List<AppEntry> = emptyList()
        private set

    private val listeners = LinkedHashSet<() -> Unit>()

    fun publish(loaded: List<AppEntry>) {
        apps = loaded
        listeners.toList().forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}
