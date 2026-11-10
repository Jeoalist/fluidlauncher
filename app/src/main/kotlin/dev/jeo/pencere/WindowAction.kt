package dev.jeo.pencere

/** Something a window gesture can do, chosen in the launcher settings. */
enum class WindowAction(val labelRes: Int) {
    NONE(R.string.action_none),
    FILL(R.string.action_fill),
    ROTATE(R.string.action_rotate),
    REAL_FULLSCREEN(R.string.action_real_fullscreen),
    BACK(R.string.action_back),
    CLOSE(R.string.action_close),
}

/** A window gesture whose action can be changed; [default] is the original behaviour. */
enum class WindowGesture(val key: String, val titleRes: Int, val default: WindowAction) {
    TITLE_DOUBLE_TAP("gesture_title_double", R.string.gesture_title_double, WindowAction.FILL),
    TITLE_TRIPLE_TAP("gesture_title_triple", R.string.gesture_title_triple, WindowAction.ROTATE),
    TITLE_HOLD("gesture_title_hold", R.string.gesture_title_hold, WindowAction.REAL_FULLSCREEN),
    GRIP_TAP("gesture_grip_tap", R.string.gesture_grip_tap, WindowAction.BACK),
}
