#!/system/bin/sh
# Starts FluidLauncher's display service from a computer, after every reboot.
# The launcher can also do this by itself once paired with Wireless debugging
# (Settings -> Display service).
#
#   adb shell sh -s < fluidlauncher-start.sh

package=dev.jeo.pencere
apk=$(pm path "$package" | head -n 1 | cut -d: -f2 | tr -d '\r')

if [ -z "$apk" ]; then
    echo "FluidLauncher is not installed." >&2
    exit 1
fi

if ps -A | grep -q fluidlauncher_display; then
    echo "The display service is already running."
    exit 0
fi

CLASSPATH="$apk" nohup app_process /system/bin --nice-name=fluidlauncher_display \
    dev.jeo.pencere.DisplayMain "$package.display" >/dev/null 2>&1 &

echo "The display service was started."
