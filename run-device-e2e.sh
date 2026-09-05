#!/usr/bin/env bash
# End-to-end run against a connected Android device.
#
# Prerequisite (needs root, run once per host):
#   sudo sh -c 'echo "SUBSYSTEM==\"usb\", ATTR{idVendor}==\"1f3a\", MODE=\"0664\", GROUP=\"plugdev\"" > /etc/udev/rules.d/51-android.rules'
#   sudo udevadm control --reload-rules && sudo udevadm trigger
# then accept the USB-debugging prompt on the device.
set -uo pipefail

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG=com.vlad.ducknetview
SERIAL="${1:-}"
if [ -n "$SERIAL" ]; then ADB="$ADB -s $SERIAL"; fi

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail=0

step "Device"
$ADB wait-for-device
$ADB shell getprop ro.product.model
$ADB shell getprop ro.build.version.release
$ADB shell getprop ro.product.cpu.abi

step "Install"
$ADB install -r -t app/build/outputs/apk/debug/app-debug.apk || fail=1
$ADB install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || fail=1

step "Permissions"
for p in android.permission.POST_NOTIFICATIONS android.permission.ACCESS_FINE_LOCATION \
         android.permission.ACCESS_COARSE_LOCATION android.permission.NEARBY_WIFI_DEVICES; do
    $ADB shell pm grant $PKG $p 2>/dev/null
done
# Pre-authorise the VPN consent dialog, which an instrumented test cannot tap.
$ADB shell appops set $PKG ACTIVATE_VPN allow
$ADB shell appops set $PKG GET_USAGE_STATS allow 2>/dev/null
echo "ACTIVATE_VPN: $($ADB shell appops get $PKG ACTIVATE_VPN)"

step "Smoke launch"
$ADB logcat -c
$ADB shell am start -n $PKG/.MainActivity >/dev/null
sleep 6
if $ADB logcat -d | grep -q "FATAL EXCEPTION"; then
    echo "CRASH on launch:"; $ADB logcat -d | grep -A 20 "FATAL EXCEPTION" | head -30; fail=1
else
    echo "launched clean, pid=$($ADB shell pidof $PKG)"
fi

step "Visible UI"
$ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
$ADB shell cat /sdcard/ui.xml 2>/dev/null | tr '>' '>\n' \
    | grep -oE '(text|content-desc)="[^"]+"' | sed 's/^[a-z-]*="//;s/"$//' | grep -v '^$' | head -40

step "Instrumented suite"
$ADB shell am instrument -w -r \
    com.vlad.ducknetview.test/androidx.test.runner.AndroidJUnitRunner 2>&1 \
    | grep -vE "^INSTRUMENTATION_STATUS: (numtests|stream|current|id|test|class)=" | tail -40
rc=${PIPESTATUS[0]}

step "Result"
if [ "$fail" -ne 0 ]; then echo "FAILURES above"; exit 1; fi
echo "done"
