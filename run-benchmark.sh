#!/usr/bin/env bash
# Measures the capture proxy's throughput and CPU cost against a direct
# baseline, using a large file served from THIS machine over the LAN so the
# number reflects the proxy rather than someone's CDN.
#
#   ./run-benchmark.sh <device-serial> [MiB]
set -uo pipefail

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
SERIAL="${1:?usage: run-benchmark.sh <device-serial> [MiB]}"
MIB="${2:-32}"
PORT="${BENCH_PORT:-8099}"
PKG=com.vlad.ducknetview
D="$ADB -s $SERIAL"

BENCH_DIR="$(mktemp -d)"
BLOB="$BENCH_DIR/blob.bin"
trap 'kill %1 2>/dev/null; rm -rf "$BENCH_DIR"' EXIT

echo "== generating a $((MIB * 2)) MiB blob =="
head -c $((MIB * 2 * 1048576)) /dev/urandom > "$BLOB"

# The device must reach this host, so bind the LAN address rather than
# localhost, and pick it from the route the device's subnet uses.
HOST_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | grep -oP 'src \K\S+' | head -1)"
echo "== serving on $HOST_IP:$PORT =="
(cd "$BENCH_DIR" && python3 -m http.server "$PORT" --bind 0.0.0.0 >/dev/null 2>&1) &
sleep 1

$D shell appops set $PKG ACTIVATE_VPN allow >/dev/null 2>&1

echo "== running =="
$D shell am instrument -w \
    -e benchUrl "http://$HOST_IP:$PORT/blob.bin" \
    -e benchMib "$MIB" \
    -e class com.vlad.ducknetview.CaptureBenchmarkTest \
    $PKG.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -20

echo "== measurements =="
$D logcat -d 2>/dev/null | grep -oE "BENCH .*" | tail -10
