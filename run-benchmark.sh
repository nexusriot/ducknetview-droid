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
SERVER_PID=""
# `kill %1` killed the subshell and left python holding the port, with its
# directory already removed underneath it. The next run then could not bind,
# the survivor answered 404 for the blob, and the test — which swallows
# transfer errors — reported 0.00 MiB/s instead of naming the cause. `exec`
# makes the subshell *become* python so $! is the process that must die.
trap 'if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null; fi; rm -rf "$BENCH_DIR"' EXIT

if command -v ss >/dev/null 2>&1 && ss -ltn "sport = :$PORT" 2>/dev/null | grep -q LISTEN; then
    echo "port $PORT is already in use on this host; a leaked server from an"
    echo "earlier run will serve 404 and the benchmark will measure nothing:"
    ss -ltnp "sport = :$PORT" 2>/dev/null | tail -n +2
    echo "free it, or re-run with BENCH_PORT=<other port>"
    exit 1
fi

echo "== generating a $((MIB * 2)) MiB blob =="
head -c $((MIB * 2 * 1048576)) /dev/urandom > "$BLOB"

# The device must reach this host, so bind the LAN address rather than
# localhost, and pick it from the route the device's subnet uses.
HOST_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | grep -oP 'src \K\S+' | head -1)"
echo "== serving on $HOST_IP:$PORT =="
(cd "$BENCH_DIR" && exec python3 -m http.server "$PORT" --bind 0.0.0.0 >/dev/null 2>&1) &
SERVER_PID=$!
sleep 1

# Prove the blob is actually fetchable before spending three rounds finding
# out that it is not.
if command -v curl >/dev/null 2>&1; then
    if ! curl -fsS --max-time 10 -o /dev/null -r 0-1023 "http://$HOST_IP:$PORT/blob.bin"; then
        echo "the blob is not being served at http://$HOST_IP:$PORT/blob.bin"
        exit 1
    fi
fi

$D shell appops set $PKG ACTIVATE_VPN allow >/dev/null 2>&1

echo "== running =="
# Clear first so the measurements below are this run's and not a previous
# one's, which is what made a fixed tail look necessary.
$D logcat -c >/dev/null 2>&1
$D shell am instrument -w \
    -e benchUrl "http://$HOST_IP:$PORT/blob.bin" \
    -e benchMib "$MIB" \
    -e class com.vlad.ducknetview.CaptureBenchmarkTest \
    $PKG.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -20

echo "== measurements =="
# The test prints two lines per round plus six summary lines, so a fixed tail
# silently drops the earliest rounds — including the cold one, which is the
# most expensive and the least safe to hide. Print every line from this run.
$D logcat -d 2>/dev/null | grep -oE "BENCH .*"
