package dev.jeo.pencere;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

interface IDisplayService {
    int createDisplay(String name, int width, int height, int dpi, in Surface surface) = 1;
    void releaseDisplay(int displayId) = 2;
    void launch(int displayId, String component, boolean newTask) = 3;
    oneway void injectMotion(int displayId, in MotionEvent event) = 4;
    oneway void injectKey(int displayId, in KeyEvent event) = 5;
    void resizeDisplay(int displayId, int width, int height, int dpi) = 6;
    List<String> openPackages() = 7;
    void closePackage(String packageName) = 8;
}
