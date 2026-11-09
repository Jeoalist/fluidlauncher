#!/bin/sh
# Starts Shizuku over USB after a phone reboot.
dir=$(adb shell pm path moe.shizuku.privileged.api | head -1 | cut -d: -f2 | tr -d '\r' | sed 's#/base.apk##')
exec adb shell "$dir/lib/arm64/libshizuku.so"
