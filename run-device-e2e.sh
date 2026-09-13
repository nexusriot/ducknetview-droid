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
# A debug APK signed with a different machine's debug key cannot upgrade the
# one already on the device; the only fix is to remove the old package, so do
# it here rather than making every run start with a manual uninstall.
install_apk() {
    local apk="$1" pkg="$2" out
    out="$($ADB install -r -t "$apk" 2>&1)" && { echo "$out"; return 0; }
    echo "$out"
    case "$out" in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*signatures\ do\ not\ match*)
            echo "-> signature mismatch, removing $pkg and retrying"
            $ADB uninstall "$pkg" >/dev/null 2>&1
            $ADB install -r -t "$apk"
            ;;
        *) return 1 ;;
    esac
}
install_apk app/build/outputs/apk/debug/app-debug.apk "$PKG" || fail=1
install_apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$PKG.test" || fail=1

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
# `am instrument` exits 0 even when tests fail — the verdict is only in its
# output — so keep the whole log and judge from that. Without this the suite
# could go red and this script would still print "done" and exit 0.
run_log="$(mktemp)"
trap 'rm -f "$run_log"' EXIT
$ADB shell am instrument -w -r \
    com.vlad.ducknetview.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$run_log" \
    | grep -vE "^INSTRUMENTATION_STATUS: (numtests|stream|current|id|test|class)=" | tail -40
rc=${PIPESTATUS[0]}

if [ "$rc" -ne 0 ]; then
    echo "adb could not run the suite (exit $rc)"; fail=1
elif grep -qE "^(FAILURES!!!|INSTRUMENTATION_RESULT: shortMsg)" "$run_log"; then
    echo "instrumented FAILURES:"
    grep -E "^INSTRUMENTATION_STATUS: stack=|^Tests run:" "$run_log" | head -20
    fail=1
elif ! grep -qE "^OK \([0-9]+ tests?\)" "$run_log"; then
    echo "no OK line from the runner: the suite did not finish"; fail=1
else
    echo "runner says: $(grep -oE "^OK \([0-9]+ tests?\)" "$run_log" | tail -1)"
    # Skips are legitimate here (the two opt-in tests), but a run where the
    # capture suites all skipped has proved nothing, so make it visible. The
    # runner reports a skip as an AssumptionViolatedException in its own output;
    # "assumption failed" is logcat's wording for the same thing and never
    # appears here.
    skipped=$(grep -c "AssumptionViolatedException" "$run_log" || true)
    echo "assumption-skipped: $skipped (2 expected: benchUrl, holdSeconds)"
    if [ "$skipped" -gt 2 ]; then
        echo "more skipped than the two opt-in tests — capture may not have run:"
        grep -oE "AssumptionViolatedException: [^\"]*" "$run_log" | sort -u | head
    fi
fi

step "Result"
if [ "$fail" -ne 0 ]; then echo "FAILURES above"; exit 1; fi
echo "done"
