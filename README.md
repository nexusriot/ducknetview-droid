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
| Names the device asked for | ❌ | ✅ from DNS answers and TLS SNI |
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

Nine navigation destinations —
`Overview · Links · Services · Apps · Conns · Domains · Routes · Hosts · Events`
— plus **Usage** and **Settings**, reached from the top bar.

- **Overview** — security summary (exposed / public / watchlist / off-baseline,
  each tappable through to the filtered screen), gateway and DNS, path latency
  with history, throughput with session peaks and sparklines, top talkers,
  connection churn, external IP. The gateway is measured with a real **ICMP
  echo** where the kernel allows an unprivileged ping socket, and by timing a
  TCP handshake where it does not; each reading says which, because the two are
  not the same measurement.
- **Links** — per-network detail, Wi-Fi dBm with a plain-language rating, link
  speeds, band and standard; hide-noise filter. A **captive portal** is flagged
  here and in the log, because every other reading taken through such a link —
  latency, external IP, reverse DNS, the webhook — is answered by the portal
  rather than by the host it names. Each network can also be marked **trusted
  or untrusted**, keyed on its SSID rather than on `wlan0`.
- **Services** — Android forbids enumerating other apps' listeners, so this
  **scans the device itself** (loopback + each local address). Same baseline
  semantics as the TUI: save, accept-one, clear, and off-baseline alerts.
- **Apps** — per-UID rows with connection counts, throughput, today's usage,
  and — for a blocked app — how many connections the firewall has refused.
  That counter exists because a blocked app has no live connections *by
  construction*, so without it the screen showed a working block exactly as it
  shows an app that is not running. CPU%/memory% of other processes is
  unavailable on Android and is omitted rather than faked.
- **Conns** — the flagship: live flows with per-connection bytes, RTT, age,
  scope colouring, watchlist highlighting, quick filters, reverse DNS,
  group-by-host, and closed-connection history. **ICMP echo is relayed too**,
  so `ping` keeps working while capture is on and its rows carry a measured
  round trip — the dispatcher used to know only TCP and UDP, which meant
  turning capture on silently broke ping for every app on the device. The rest
  of ICMP is not relayed and the screen says so: replies such as Time Exceeded
  arrive on a socket error queue no public API can read.
- **Domains** — every name this device asked for, who asked, how often, and
  what it resolved to. Three sources, and each row says which: **DNS** answers
  read off the TUN, **TLS SNI** where no DNS answer was visible, and the
  ClientHello inside a **QUIC Initial** where the handshake is not TLS at all.
  The second keeps the screen useful on a device with Private DNS switched on
  — the default on most modern Android. The third closes what was the real
  blind spot: a browser page load is entirely UDP/443, producing no DNS answer
  and no TLS record, so the majority of a modern phone's traffic left this
  screen empty. QUIC Initial packets are protected with keys derived from a
  salt **published in RFC 9001** and the connection id the client sends in the
  clear, so no secret takes part and anyone on the path can read the name —
  the protection is there to stop middleboxes freezing the wire format, not to
  keep the contents private. Only a connection's opening Initial packets are
  read, under hard caps, and the buffer is dropped as soon as the handshake
  moves on: a modern ClientHello carries a post-quantum key share and does not
  fit one packet, so reading just the first one found a name for none of the
  QUIC flows on the test tablet.
  None of this is TLS interception: every name here is sent in the clear
  before any key exchange, nothing that needs a secret is decrypted, no
  certificate is substituted, and the bytes are relayed unchanged either way.
- **Routes** — every simultaneously-active network's routes and DNS. The
  kernel's ARP/neighbour table is **not available on Android 10+**; the screen
  says so and points at Hosts, which answers the question the other way round.
- **Hosts** — other devices on this link. Android has not exposed the
  neighbour table to apps since version 10, so this asks the network instead:
  an **ICMP echo** to each address in the link's own IPv4 prefix through the
  same unprivileged ping socket the gateway probe uses, plus **mDNS** through
  the system's NSD daemon for names and device types. It is the only active
  source in the app, so it has a button and never refreshes itself, it sweeps
  only the device's own on-link prefix, and it skips the broadcast address.
  **This is a list of what answered, not a census** — a host that filters ICMP
  and publishes no mDNS is on the network and not on this screen, and a guest
  network is built so that nothing answers at all. The screen says that rather
  than showing an empty table and implying the network is empty.
- **Events** — the change log: services appearing, vanishing and *moving*
  (an exposure-widening move is an alert), watchlist hits, first contact with
  a new public host, network changes, threshold breaches.
- **Usage** — the TUI's `--usage` history: daily totals with a chart and the
  busiest day called out, per-day drill-down, and top apps / hosts over 7, 30
  or all retained days (40 days, top 50 apps and hosts per day). Per-app
  history needs the system Usage Access permission; the screen asks for it and
  still shows device-level history without it. With a **data budget**
  configured it also answers the question a metered plan actually raises —
  *am I going to run out* — with the cycle's usage and the cycle's elapsed time
  as two bars, the projection at the current rate, and the date the allowance
  runs out if that rate holds. Cycle totals are read live from
  `NetworkStatsManager` rather than summed from the rollup, so a cap written
  against mobile data is measured against mobile data.
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

Two budget rules work differently and deliberately so: **% of cap** and
**projected over cap** are suppressed per *billing cycle* rather than on a
clock. Every other rule watches a subject that goes away; a cap sat on top of
stays breached for the rest of the month, so a time-based cooldown would fall
silent and then start again every couple of minutes until the cycle rolled
over. The percentage rule fires once per 10% band, so climbing from 80% to 95%
is two lines and sitting at 85% for three weeks is none.

Refused connections are logged too, as INFO rather than a warning — the user
asked for the block, so a refusal is the feature working, and anything louder
would notify on every retry of an app they have already dealt with. One line
per app per minute carries the count since the last one.

Arriving on a network marked **untrusted** (or, optionally, one never seen
before) is logged, and can automatically start capture and scan this device's
own listeners — the checks worth running on a café network and the ones
nobody remembers. The first capture on a device still needs its consent
dialog, which only an open app can show, so until that has been granted once
the arrival asks rather than starts.

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

Two fields are read out of payloads and nothing else is: the answers in a DNS
response, and the `server_name` in a TLS ClientHello. Both are names, both are
sent in the clear, and both exist so a connection can be labelled with
something a person recognises instead of a bare address. Nothing is decrypted,
no certificate is substituted, and the bytes are relayed unchanged. The name
store keeps 30 days and has a Clear button.

## Build

```bash
export JAVA_HOME=/opt/android-studio/jbr
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug
```

`local.properties` is deliberately not committed, so a fresh clone has no
`sdk.dir` and the build stops at *"SDK location not found"* until `ANDROID_HOME`
points at an SDK with **platform 35** and **platform-tools** installed. Writing
`sdk.dir=/path/to/Sdk` into `local.properties` works too; the environment
variable is preferred because it does not tempt anyone to commit the file.

1480 unit tests and 44 instrumented tests; see **Running the tests** and
**Test on a device**.

## Test on a device

```bash
./run-device-e2e.sh [serial]
```

Installs both APKs, grants permissions, pre-authorises the VPN consent dialog
via `appops` (an instrumented test cannot tap it), smoke-launches, dumps the
visible UI, and runs the instrumented suites.

The script **fails the run when the suite fails**. That is worth stating because
it did not always: `am instrument` exits 0 whether the tests passed or not, so
the verdict has to be read out of its output, and until that was fixed a red
suite still printed `done` and exited 0. It also recovers from the one install
failure that is not a real failure — a debug APK built on another machine cannot
upgrade the installed one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so the script
removes the stale package and retries rather than stopping.

Verified on a PRITOM M10 tablet (Android 16 / API 36, arm64). There are 44
instrumented test methods; **42 do real work on a default run**, and the
remaining two skip unless given their arguments. The ICMP case skips as well on
a kernel that does not permit unprivileged ping sockets, which is a
configuration (`net.ipv4.ping_group_range`) rather than a guarantee.

| Suite | Tests | Notes |
|---|---|---|
| `VpnCaptureE2ETest` | 14 | turns on capture, makes real requests, asserts flows appear with correct byte counters, RTT and UID attribution; pings through the TUN; proves names learnt from a TLS handshake **and from a QUIC Initial split across two datagrams** reach storage with the right source; blocks this app's own uid and asserts the refusals are counted |
| `AppUiE2ETest` | 12 | drives the real app through every screen with the live engine, Room and DataStore behind it, including a real LAN sweep driven from the Hosts screen's own button |
| `NeighbourSweepDeviceTest` | 8 | sweeps this device's real link and checks every row credits a source its own fields support |
| `UsageMigrationDeviceTest` | 5 | runs the `daily_usage` schema migration on the device's own SQLite |
| `CaptureToUiE2ETest` | 2 | closes the loop: packets off the TUN become flows, flows become a snapshot, the snapshot renders as rows |
| `MetricsEndpointDeviceTest` | 1 (+1 opt-in) | enables the setting and scrapes the endpoint over a real socket; `-e holdSeconds N` holds it open for an external scrape |
| `CaptureBenchmarkTest` | opt-in | needs `-e benchUrl`; see **Capture cost** |

`NeighbourSweepDeviceTest` starts an Activity before anything else, and has a
case whose only job is to assert that it worked. Its first version did not,
so the engine never ran, every case skipped on "no IPv4 prefix on the default
link" — and the runner printed `OK (7 tests)` for a suite that had done
nothing. An assumption that is always violated looks exactly like a passing
test.

Two of `AppUiE2ETest`'s cases exist because the suite used to drive only the
seven *navigation* destinations. **Usage** is reached from the top bar instead,
so nothing ever composed it — and the day keys the rollup stored were
milliseconds where the screen expected day numbers, which meant opening it by
hand on the tablet killed the app with `Invalid value for EpochDay`. A screen
reachable by a different route is still a screen.

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
measures the CDN. The host must be on the device's subnet — the script binds
the LAN address rather than localhost for exactly that reason — and it clears
logcat first so the measurements printed are the ones it just produced.

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
against the shipped database. `MIGRATION_1_2` is the first of them: the column
layout did not change, only the meaning of `daily_usage.dayEpoch`, which held a
local midnight in *milliseconds* under a name the rest of the app reads as a day
number. The conversion runs in Kotlin rather than SQL because dividing by
86_400_000 lands on the previous day everywhere east of UTC.

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

**49 metric families** (all gauges, all prefixed `ducknetview_`) covering
throughput, per-network counters, connections by scope, listeners by exposure,
off-baseline count, watchlist hits, latency, per-app / per-host series, refused
connections per app, the data budget (cap, used, projected, days left — emitted
only when one is configured, since a budget of zero scraped as a real series
reads as an exhausted allowance on any dashboard that divides by it), and the
truncation gauge below. The sample count varies with how many networks, apps and
hosts are present — an idle tablet scraped over the LAN served 46 samples in
7.6 kB. `PrometheusTest` pins the family count so this number cannot drift again.

Per-app and per-host series are capped at 50 each, with an explicit
`ducknetview_series_truncated` gauge saying how many were dropped; silent
truncation is against the rules this project inherited. The TUI's
`iface_errors_total` and `connection_retransmits_total` are **not** emitted,
because Android exposes no source for either.

**The endpoint is unauthenticated** and serves device network metadata; it is
meant for a trusted network only.

## Capture cost

Measured on the PRITOM M10 over a LAN HTTP source, three alternating rounds
with the UI foregrounded in both arms (`./run-benchmark.sh <serial> 24`), on a
5 GHz link that tops out near 11 MiB/s. Two independent runs, medians:

| | direct | captured | |
|---|---|---|---|
| Throughput | 11.06 / 11.09 MiB/s | 9.76 / 10.02 MiB/s | ratio **0.88 / 0.90** |
| CPU per MiB | 0.074 / 0.068 s | 0.366 / 0.373 s | **5.0× / 5.5×** |

**CPU is roughly 5× higher per MiB**, which is the real cost and the thing that
will drain a battery. **Throughput costs about 10%.** Captured CPU per MiB
repeats to within 2% across runs; the *multiplier* is the noisier figure only
because the direct baseline it divides by is small, so read it as "about five",
not as 4.97.

An earlier run on a ~6 MiB/s link reported 8.1× CPU and *no* throughput cost at
all. Both halves of that moved: the buffer pooling below took the CPU multiplier
down, and the faster link removed the bottleneck that had been masking the
proxy's own ceiling. **"Capture does not slow the link" was a property of the
slow link, not of the proxy** — on a link fast enough to expose it, the proxy is
the limit.

Per-round numbers, since the medians hide the shape (cold round first):

| round | direct MiB/s | captured MiB/s | direct s/MiB | captured s/MiB |
|---|---|---|---|---|
| 0 | 10.93 / 10.95 | **7.98 / 7.97** | 0.059 / 0.052 | **0.513 / 0.484** |
| 1 | 11.06 / 11.36 | 11.23 / 10.02 | 0.080 / 0.068 | 0.354 / 0.373 |
| 2 | 11.40 / 11.09 | 9.76 / 10.32 | 0.074 / 0.073 | 0.366 / 0.332 |

Round 0 is the reproducible one: both runs put the cold captured arm at 7.97–7.98
MiB/s and ~0.5 s/MiB, a third slower and a third more expensive than the rounds
after it, paying for a cold JIT and an empty buffer pool. **The first megabytes
after capture starts are the worst ones the user will see.** Later rounds vary by
more than the difference between the arms — captured round 1 of run A came out
*above* its direct pair — so treat the medians as the result and any single
warm round as noise.

#### Where that CPU is not

A JVM micro-benchmark against the real codec puts total byte-level codec cost at
about **1 ms/MiB — under 0.3% of the 366 ms/MiB now measured on the tablet**. The
multiplier is therefore *not* in the packet arithmetic. What remains is GC
pressure on a mobile heap, per-packet syscalls, coroutine/thread handoffs, and
the structural cost of a userspace relay crossing the kernel boundary three
times per byte.

Acting on that, the per-packet path now pools its buffers and reuses its parse
scratch: roughly **1400 array allocations (~2.1 MiB of garbage) and 700 bulk
copies removed per MiB relayed**, plus ~11x fewer upstream read syscalls and one
coroutine resume per burst instead of per packet. The poll loop also backs off
while the screen is off, snapping back on screen-on.

**Re-measured on device**: that work took captured CPU from 0.570 to ~0.37
s/MiB, a **35% cut**, and the multiplier from 8.1× to about 5×. As predicted it
is a real but moderate improvement and not an 8x collapse — the remaining cost
is structural, so the relay's three kernel crossings per byte are now the floor.

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
- **ARP/neighbour table** is unavailable on Android 10+. The Hosts screen
  answers the question by active sweep instead, which finds only hosts that
  answer an echo or publish mDNS, and only on IPv4.
- **CPU%/memory% per app** is unavailable for other processes.
- The **Quick Settings tile cannot show the VPN consent dialog** (no tile can),
  so the first activation hands off to the app.
- Capture is a userspace TCP/UDP proxy in Kotlin. It is correct and it respects
  the peer's window, but it is CPU-bound well below line rate, and on a link
  faster than about 10 MiB/s it costs throughput as well — see **Capture cost**
  for the measured numbers.
- **No CI** yet, and no F-Droid packaging. Play Store distribution would need
  declared justifications for `VpnService` and `QUERY_ALL_PACKAGES`.

## License

MIT
