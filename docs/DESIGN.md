# ducknetview-droid — design and decision record

The as-built architecture of the Android port of [ducknetview](../../ducknetwiew),
and the reasoning behind the decisions that are not obvious from the code.

> An earlier pre-implementation version of this document lived in the TUI repo
> as an untracked file and was lost to a `git clean`. This replaces it, and
> describes what was actually built rather than what was planned.

## 1. The problem

The TUI reads `/proc/net/*` (sockets, routes, ARP), netlink `sock_diag` for all
UIDs, and `/proc/<pid>/` of other processes. Android has blocked **all three**
for unprivileged apps since Android 10. Process signalling is limited to the
app's own process, and per-interface byte counters are not public API.

A passive port of the probe layer is therefore impossible. The app needs a
different primary data source.

## 2. Two engines

**Engine B — local `VpnService` (opt-in).** All device traffic is routed into a
TUN device the app owns. The app parses IP/TCP/UDP headers and relays each flow
onward through a `protect()`ed socket, which makes it the accountant for every
byte: exact per-connection counters, a live connection table, closed-flow
history with true lifetime totals, and per-app attribution via
`ConnectivityManager.getConnectionOwnerUid()`. Refusing to open the upstream
socket is the per-app firewall. No root; one consent dialog.

**Engine A — public APIs (always on).** `TrafficStats`,
`NetworkStatsManager`, `ConnectivityManager`/`LinkProperties`, `WifiManager`.
This is the degraded mode when the user declines capture, and the only mode
available while another VPN holds the single VPN slot Android permits.

**The governing rule, inherited from the TUI:** a column with no source in the
current mode is *hidden, not shown empty*. `Capabilities` carries what the
active engine can actually answer, and the UI keys off it.

## 3. Adaptations where a straight port was impossible

| TUI feature | Android | What was built |
|---|---|---|
| Ports tab (enumerate listeners) | forbidden for other apps | **self port-scan** of loopback + each local address, with the TUI's baseline semantics intact |
| Processes tab | other processes are hidden | **per-UID Apps screen**; CPU%/memory% omitted, not faked |
| Kill process | own process only | per-app **VPN firewall block**, exclude-from-VPN, App-Info deep link |
| ARP/neighbour table | unavailable on Android 10+ | the Routes screen says so explicitly |
| Retransmit counts | no unprivileged source | column hidden; approximation deliberately parked |
| `--on-alert` shell hook | no shell | notification + optional webhook POST + broadcast Intent |
| `--metrics` textfile | no cron | embedded HTTP endpoint, 42 gauge families |
| `--from snapshot` | — | SAF picker → frozen UI, reads the TUI's own JSON |

UDP is skipped by the self-scan: it is not reliably detectable by connect-probing.

## 4. Structure

`domain/` is pure Kotlin with **no Android imports**, on purpose — the event
engine, alert rules, baseline diffing, search, sort, rate maths and the CSV /
JSON / Prometheus renderers are all plain JVM tests. `engine/` collects,
`data/` persists, `ui/` renders as pure functions of `UiState` + `UiActions`,
and one `MainViewModel` is the only thing that knows about both.

One immutable `NetSnapshot` per poll tick feeds every screen — the direct
analogue of the TUI's single-enumeration design, and the reason the screens
cannot disagree with each other.

## 5. Decisions worth recording

**No retransmission timer in the TCP proxy.** The downstream side is a TUN file
descriptor handed to the local kernel, which does not lose segments. Flow
control *is* implemented: the peer's advertised window must be respected or a
fast download overruns its receive buffer.

**Teardown takes a snapshot before closing.** Two process-killing
`ConcurrentModificationException`s were found on device: closing flows while
iterating the map their callbacks remove from, under a *reentrant* lock that
hid the bug rather than preventing it. Teardown now copies and clears first,
`TcpConnection.jobs` is a `CopyOnWriteArrayList`, `close()` is idempotent, and
`onClosed` fires before job cancellation because one of those jobs is usually
the caller.

**Buffer ownership is explicit.** `TunWriter.acquire`/`submit`/`release`: a
producer borrows a buffer, fills it, submits it, and must never touch it again;
the write loop returns it to the pool. A buffer-reuse bug corrupts real user
traffic silently, so ownership is documented at every hand-off.

**`replaceDay` vs `mergeToday`.** `NetworkStatsManager` reports *cumulative*
day totals while the live engine reports *deltas*. The rollup worker replaces
the day; the engine adds to it. That distinction is what makes running the
rollup twice in a day idempotent, and both paths are pinned by tests.

**Cleartext is permitted.** A global `cleartextTrafficPermitted="false"` broke
the app's own probes and would silently break a user's plain-HTTP LAN webhook.
The app's one fixed endpoint (ipify) is HTTPS and no user data goes to third
parties, so enforcing TLS here protected nothing.

## 6. Measured cost

On a PRITOM M10 (Android 16 / API 36, arm64), LAN HTTP source, three
alternating rounds with the UI foregrounded in both arms:

- throughput: 5.76 MiB/s direct vs 5.94 MiB/s captured — **ratio 1.03**, the
  proxy does not throttle; the Wi-Fi link is the bottleneck
- CPU: 0.070 vs 0.570 s/MiB — **8.1×**

A JVM micro-benchmark then put total byte-level codec cost at ~1 ms/MiB, i.e.
**under 0.2%** of that 570 ms/MiB. The multiplier is not in the packet
arithmetic; it is GC pressure, per-packet syscalls, coroutine handoffs, and the
structural triple kernel crossing of a userspace relay. Buffer pooling and
reused parse scratch since removed ~1400 allocations and ~700 copies per MiB;
**that change is not yet re-measured on device.**

The largest untaken lever is raising the TUN MTU (~10× fewer packets, writes and
ACKs). It changes what every app puts on the wire through the TUN and needs a
device to validate.

## 7. Environment constraints discovered in the field

- **OEM background managers** (Allwinner's `awbms`) silently drop
  `startForegroundService` and notifications for apps not allowed to run in the
  background. The capture toggle appears to do nothing.
- **Espresso before 3.7.0 cannot inject input on API 36** —
  `InputManager.getInstance` was removed, which fails every Compose UI test.
- **VPN consent cannot be tapped from an instrumented test**; the harness
  pre-authorises it with `appops set <pkg> ACTIVATE_VPN allow`, and the suites
  skip rather than fail when that grant is missing.
- **Compose's `TestTag` merge policy keeps the first tag**, so a component
  writing `Modifier.testTag(default).then(modifier)` silently discards every
  caller-supplied tag.

## 8. Recurring defect class, and the guard

Three times, a feature was fully implemented, documented, and reachable from
nothing: `openSnapshot` had no control, `requestWifiPermission` had no caller,
and HIGHLIGHT search mode computed match ranges that no screen rendered.

`app/src/test/.../arch/UiActionsWiredTest.kt` now asserts every `UiActions`
member has a caller in `ui/` or `MainActivity`. Reflection proves a method
exists; only a source scan proves something invokes it.

## 9. Deliberate non-goals

Payload capture / PCAP export (this is a monitor, not a sniffer), root mode,
TLS interception, traffic shaping beyond per-app block, and any companion
daemon.
