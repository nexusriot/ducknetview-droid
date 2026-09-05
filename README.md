# ducknetview-droid 🦆

Native Android port of the [ducknetview](../ducknetwiew) network-monitoring TUI.

Kotlin · Jetpack Compose (Material 3) · minSdk 29 · targetSdk 35

The TUI reads `/proc/net`, netlink `sock_diag` and other processes' `/proc`
entries. Android has blocked all three for unprivileged apps since Android 10,
so a straight port is impossible. This app recovers the same answers a
different way, and is explicit about the parts it cannot.

## Two engines

| | **API mode** (always on) | **Capture mode** (opt-in) |
|---|---|---|
| How | `TrafficStats`, `NetworkStatsManager`, `ConnectivityManager`, `WifiManager` | a local `VpnService`: all traffic is routed into a TUN device the app owns, parsed, and relayed onward through `protect()`ed sockets |
| Throughput, links, routes, DNS, Wi-Fi | ✅ | ✅ |
| Live connection table | ❌ | ✅ |
| Per-connection bytes, RTT | ❌ | ✅ exact — the proxy moves every byte |
| Closed-connection history | ❌ | ✅ with true lifetime totals |
| Per-app attribution | coarse | ✅ via `getConnectionOwnerUid` |
| Per-app firewall | ❌ | ✅ (refuse the upstream socket) |

No root. Capture needs one VPN consent dialog. Android permits a single active
VPN, so capture is unavailable while WireGuard/Tailscale is connected — API
mode stays useful in that case, and the UI says which mode it is in.

**Columns with no source in the current mode are hidden, not shown empty.**
That rule is inherited from the TUI, which hides its RX/TX columns on platforms
that cannot account for sockets.

## Screens

Seven navigation destinations —
`Overview · Links · Services · Apps · Conns · Routes · Events` — plus **Usage**
and **Settings**, reached from the top bar.

- **Overview** — security summary (exposed / public / watchlist / off-baseline,
  each tappable through to the filtered screen), gateway and DNS, TCP-handshake
  latency with history, throughput with session peaks and sparklines, top
  talkers, connection churn, external IP.
- **Links** — per-network detail, Wi-Fi dBm with a plain-language rating, link
  speeds, band and standard; hide-noise filter.
- **Services** — Android forbids enumerating other apps' listeners, so this
  **scans the device itself** (loopback + each local address). Same baseline
  semantics as the TUI: save, accept-one, clear, and off-baseline alerts.
- **Apps** — per-UID rows with connection counts, throughput, today's usage.
  CPU%/memory% of other processes is unavailable on Android and is omitted
  rather than faked.
- **Conns** — the flagship: live flows with per-connection bytes, RTT, age,
  scope colouring, watchlist highlighting, quick filters, reverse DNS,
  group-by-host, and closed-connection history.
- **Routes** — every simultaneously-active network's routes and DNS. The
  ARP/neighbour table is **not available on Android 10+** and the screen says
  so rather than leaving a silent gap.
- **Events** — the change log: services appearing, vanishing and *moving*
  (an exposure-widening move is an alert), watchlist hits, first contact with
  a new public host, network changes, threshold breaches.
- **Usage** — the TUI's `--usage` history: daily totals with a chart and the
  busiest day called out, per-day drill-down, and top apps / hosts over 7, 30
  or all retained days (40 days, top 50 apps and hosts per day). Per-app
  history needs the system Usage Access permission; the screen asks for it and
  still shows device-level history without it.
- **Settings** — capture toggle, refresh interval and units, alert thresholds,
  watchlist and latency-target editors, the metrics endpoint, snapshots, and
  exports.

Every list screen is **two-pane on a tablet** (list plus a live-updating detail
pane above 720 dp) and falls back to a modal sheet on a phone. The detail pane
holds the selected row's *identity* and re-resolves it from each new snapshot,
so it keeps updating instead of going stale, and falls back to a placeholder if
the row disappears.

### Search

`/`-style search on every table, with the TUI's two modes: **filter** hides
non-matching rows, **highlight** keeps them all and marks the matches — row
tint plus the matched substring marked inside the identifying column — with
prev/next buttons and an "n / m" counter to walk between them (the TUI's
`n` / `N`). A `re:` prefix makes the query a case-insensitive regular
expression, and an invalid one says so rather than silently matching nothing.
The match tint deliberately outranks the new-row and watchlist tints: the match
is what you are hunting for and must never be masked.

### Reaching the app quickly

A **Quick Settings tile** toggles capture without opening the app, and the
ongoing notification shows live rates, the connection count and the loudest
talker, with a **Stop capture** action. A first-run explainer covers the two
engines and the privacy stance, and every screen has an **info sheet** spelling
out what it can and cannot see in the current engine mode — the honesty rule is
only honest if the user can find the explanation.

### Snapshot viewer

Settings → open a `ducknetview --json` snapshot and the whole UI freezes onto
that file, marked with a dismissible `snapshot` chip in the top bar. The poll
loop idles while frozen rather than producing data nothing renders. This is the
TUI's `--from` mode, and it reads the TUI's own JSON — so a server snapshot can
be inspected on the phone:

```bash
ssh somehost ducknetview --json > snap.json   # then open snap.json in the app
```

## Alert rules

All opt-in, each rate-limited to once per minute per subject: per-app bps,
per-connection bps, RTT, connection-count growth (a leak), fan-out to many new
hosts at once (a scan), off-baseline listeners, watchlist hits. Delivery is a
notification, an optional webhook POST, and an optional broadcast Intent —
the Android equivalents of the TUI's `--on-alert` hook. Nothing runs a shell.

## Background jobs

Two WorkManager jobs are what make the app useful while nobody is looking:

- **Service scan**, every 6 h (battery-not-low), re-scans the device and alerts
  on any listener outside the saved baseline — the TUI's `--check-baseline`-in-
  cron story. It is only scheduled when a baseline exists, and each listener
  re-alerts at most once per 6 h, throttled in a store that survives process
  death.
- **Usage rollup**, every 12 h, folds the day's per-app totals into the daily
  history. It is idempotent: `NetworkStatsManager` reports cumulative day
  totals, so the rollup replaces the current day rather than adding to it.

## Privacy

Everything stays on the device. The only outbound requests the app makes are
ipify (optional), configured latency targets, the optional webhook, and reverse
DNS. **Packet contents are never captured or stored** — the proxy relays and
counts. This is a monitor, not a sniffer.

## Build

```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew assembleDebug
```

1105 unit tests and 19 instrumented tests; see **Running the tests** and
**Test on a device**.

## Test on a device

```bash
./run-device-e2e.sh [serial]
```

Installs both APKs, grants permissions, pre-authorises the VPN consent dialog
via `appops` (an instrumented test cannot tap it), smoke-launches, dumps the
visible UI, and runs the instrumented suites.

Verified on a PRITOM M10 tablet (Android 16 / API 36, arm64). There are 19
instrumented test methods; **17 do real work on a default run and all pass**,
and the remaining two skip unless given their arguments.

| Suite | Tests | Notes |
|---|---|---|
| `VpnCaptureE2ETest` | 7 | turns on capture, makes real requests, asserts flows appear with correct byte counters, RTT and UID attribution |
| `AppUiE2ETest` | 7 | drives the real app through every screen with the live engine, Room and DataStore behind it |
| `CaptureToUiE2ETest` | 2 | closes the loop: packets off the TUN become flows, flows become a snapshot, the snapshot renders as rows |
| `MetricsEndpointDeviceTest` | 1 (+1 opt-in) | enables the setting and scrapes the endpoint over a real socket; `-e holdSeconds N` holds it open for an external scrape |
| `CaptureBenchmarkTest` | opt-in | needs `-e benchUrl`; see **Capture cost** |

`VpnCaptureE2ETest` sets `VpnBridge.captureOwnTraffic` so the test process's own
traffic traverses the TUN, which is the only way to observe capture end to end.
Every suite foregrounds the app before starting the service (see **OEM
background managers**) and skips rather than failing falsely when the `appops`
grant is missing.

Benchmark:

```bash
./run-benchmark.sh <serial> [MiB]
```

Serves a generated blob from this machine over the LAN and measures the proxy
against a direct baseline. A LAN source is the point: measuring against a CDN
measures the CDN.

Linux hosts need a udev rule before adb can claim a device:

```bash
sudo sh -c 'echo "SUBSYSTEM==\"usb\", ATTR{idVendor}==\"1f3a\", MODE=\"0664\", GROUP=\"plugdev\"" > /etc/udev/rules.d/51-android.rules' && sudo udevadm control --reload-rules && sudo udevadm trigger
```

(`1f3a` is Allwinner; use your device's own vendor id from `lsusb`.)

## Design

`docs/DESIGN.md` is the as-built architecture and decision record: why there are
two engines, what each Android restriction forced, the buffer-ownership and
teardown rules in the capture path, the measured capture cost, and the
environment constraints found on real hardware.

## Layout

```
domain/      pure Kotlin, no Android imports: events, alerts, baseline,
             watchlist, search, sort, totals, usage, rates, and the CSV /
             JSON / Prometheus renderers
engine/      poll loop and snapshot assembly, plus the screen-off poll cadence
engine/api/  TrafficStats, ConnectivityManager, Wi-Fi, latency, self port-scan
engine/vpn/  VpnService, TUN loop, IP/TCP/UDP codec, flow table, socket proxy,
             and the packet buffer pool the relay path borrows from
data/        Room (events, usage, closed conns, hosts) + DataStore settings
data/work/   WorkManager jobs: scheduled service scan and usage rollup
service/     notifications, alert delivery, the Quick Settings tile, and the
             metrics HTTP server
ui/          Compose screens as pure functions of UiState + UiActions
```

Room's exported schemas in `app/schemas/` are committed on purpose: they are the
migration record, and without them a future migration cannot be validated
against the shipped database.

`domain/` has no Android dependency on purpose, so the event engine, alert
rules, baseline diffing, search, sort and rate maths are all plain JVM tests.

`app/src/test/.../arch/UiActionsWiredTest.kt` is a source-level guard against
this project's recurring defect: an action implemented in the ViewModel, a
feature described in this README, and no control anywhere that calls it. It
asserts every `UiActions` member has a caller in `ui/` or `MainActivity`.
Reflection can prove a method exists; only a source scan proves something
invokes it.

## Running the tests

```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew testDebugUnitTest
```

The Robolectric Compose suite is large enough to exhaust a default test JVM, so
`build.gradle.kts` recycles the test worker (`forkEvery = 30`) and gives it 3 GB
plus 1 GB of Metaspace. Without that, Gradle fails with
`Could not write XML test results for <class>` naming a **different** set of
classes each run — which reads like flaky tests but is the worker dying. Note
that two Gradle runs against this project at the same time produce the identical
message for an unrelated reason (they wipe `build/test-results`), so check
whether something else is building before reaching for the heap setting.

## Metrics endpoint

Enable it in Settings and the app serves Prometheus text on the LAN:

```bash
curl http://<device-ip>:9187/metrics
```

**42 metric families** (all gauges, all prefixed `ducknetview_`) covering
throughput, per-network counters, connections by scope, listeners by exposure,
off-baseline count, watchlist hits, latency, and per-app / per-host series. The
sample count varies with how many networks, apps and hosts are present — an
idle tablet served 47.

Per-app and per-host series are capped at 50 each, with an explicit
`ducknetview_series_truncated` gauge saying how many were dropped; silent
truncation is against the rules this project inherited. The TUI's
`iface_errors_total` and `connection_retransmits_total` are **not** emitted,
because Android exposes no source for either.

**The endpoint is unauthenticated** and serves device network metadata; it is
meant for a trusted network only.

## Capture cost

Measured on the PRITOM M10 over a LAN HTTP source, three alternating rounds
with the UI foregrounded in both arms (`./run-benchmark.sh <serial> 24`):

| | direct | captured |
|---|---|---|
| Throughput (median) | 5.76 MiB/s | 5.94 MiB/s |
| CPU per MiB (median) | 0.070 s | 0.570 s |

**Throughput is unaffected** — the Wi-Fi link is the bottleneck at ~6 MiB/s, not
the proxy, and the captured arm was actually the steadier of the two. **CPU is
8.1× higher per MiB**, which is the real cost and the thing that will drain a
battery.

#### Where that CPU is not

A JVM micro-benchmark against the real codec puts total byte-level codec cost at
about **1 ms/MiB — under 0.2% of the 570 ms/MiB measured on the tablet**. The
multiplier is therefore *not* in the packet arithmetic. What remains is GC
pressure on a mobile heap, per-packet syscalls, coroutine/thread handoffs, and
the structural cost of a userspace relay crossing the kernel boundary three
times per byte.

Acting on that, the per-packet path now pools its buffers and reuses its parse
scratch: roughly **1400 array allocations (~2.1 MiB of garbage) and 700 bulk
copies removed per MiB relayed**, plus ~11x fewer upstream read syscalls and one
coroutine resume per burst instead of per packet. The poll loop also backs off
while the screen is off, snapping back on screen-on.

⚠️ **Not yet re-measured on device** — the tablet was disconnected when this
landed. Re-run `./run-benchmark.sh <serial> 24` to see what it actually bought;
expect a real but moderate improvement, not an 8x collapse.

The biggest untaken lever is **raising the TUN MTU**, which would cut packets,
TUN writes and ACKs by roughly 10x at a stroke. It is deliberately not done: it
changes what every app puts on the wire through the TUN, and needs a device to
validate.

## OEM background managers

Some vendor builds ship an auto-start manager that silently drops
`startForegroundService` and notifications for apps the user has not allowed to
run in the background. On the PRITOM M10 this is `awbms`, and it shows up in
logcat as `startService return, because awbms skipService`. The symptom is the
capture toggle appearing to do nothing. Allow the app to run in the background
in the device's battery settings; starting capture from the foreground UI (the
normal path) is what the OEM manager permits.

## Known gaps

- **Retransmit counts** have no unprivileged source; the column is hidden
  rather than approximated.
- **ARP/neighbour table** is unavailable on Android 10+.
- **CPU%/memory% per app** is unavailable for other processes.
- The **Quick Settings tile cannot show the VPN consent dialog** (no tile can),
  so the first activation hands off to the app.
- Capture is a userspace TCP/UDP proxy in Kotlin. It is correct and it respects
  the peer's window, but it is CPU-bound well below line rate — see
  **Capture cost** for the measured numbers.
- **No CI** yet, and no F-Droid packaging. Play Store distribution would need
  declared justifications for `VpnService` and `QUERY_ALL_PACKAGES`.

## License

MIT
