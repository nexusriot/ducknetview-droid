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
socket is the firewall (per app, per host, per network policy); a name seen
only in the handshake cuts the flow instead (§8c). No root; one consent dialog.

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
| Kill process | own process only | per-app **firewall block** and network policies, **per-flow reset** (RST to the app, upstream closed), exclude-from-VPN, App-Info deep link |
| ARP/neighbour table | unavailable on Android 10+ | **Hosts screen**: an opt-in ICMP sweep of the link's own prefix plus mDNS for names, plus one echo to `ff02::1` for IPv6. The Routes screen still states the platform limit and points at it |
| Retransmit counts | no unprivileged source | column hidden; approximation deliberately parked |
| ICMP (ping) | no raw sockets | **echo relayed through a Linux ping socket**; the rest of ICMP is not reachable |
| Resolver activity | other apps' DNS is invisible | **names read off the TUN**: DNS answers, TLS SNI where the resolver is encrypted, and the ClientHello inside a QUIC Initial where the handshake is not TLS at all |
| `--on-alert` shell hook | no shell | notification + optional webhook POST + broadcast Intent |
| `--metrics` textfile | no cron | embedded HTTP endpoint, 51 gauge families |
| `--from snapshot` | — | SAF picker → frozen UI, reads the TUI's own JSON |

UDP is skipped by the self-scan: it is not reliably detectable by connect-probing.

## 4. Structure

`domain/` is pure Kotlin with **no Android imports**, on purpose — the event
engine, alert rules, baseline diffing, search, sort, rate maths and the CSV /
JSON / Prometheus renderers are all plain JVM tests. `engine/` collects,
`data/` persists, `ui/` renders as pure functions of `UiState` + `UiActions`,
and `DuckApp.Deps` wires the engine to settings and storage for the life of the
process (so capture started from the tile, at boot or by automation is
configured with no screen open); `MainViewModel` adapts both to `UiState` /
`UiActions` for the screens.

One immutable `NetSnapshot` per poll tick feeds every screen — the direct
analogue of the TUI's single-enumeration design, and the reason the screens
cannot disagree with each other.

## 5. Decisions worth recording

**ICMP echo is relayed; the rest of ICMP is not.** The dispatcher originally
handled TCP and UDP and let everything else fall off the end of the `when`.
With a default route through the TUN that is not a gap in a table, it is a
regression in the device: turning capture on silently broke `ping` for every
app, with no event, no counter and no sentence anywhere saying so. The fix is a
Linux "ping" socket (`SOCK_DGRAM`/`IPPROTO_ICMP`), which Android opens to
ordinary apps subject to `net.ipv4.ping_group_range` — so no root, and the
kernel restricts it to echo and owns the identifier and checksum. The guest's
identifier is held in the flow and restored on the reply; replies are matched
to requests by sequence number, which is what makes the RTT column real for an
ICMP row rather than borrowed from a TCP handshake.

Two limits come with it and are both stated in the Conns info sheet rather than
left to be discovered. `getConnectionOwnerUid` answers for TCP and UDP only, so
an echo flow carries the unknown-uid sentinel and a per-app block does not stop
a ping. And ICMP errors — Time Exceeded, Destination Unreachable — are
delivered on a socket error queue that needs `MSG_ERRQUEUE`, which is not in
`OsConstants`: traceroute from another app therefore still gets no answers
while capture is on. That also settles why there is no in-app traceroute. It
was planned, and it is not implementable with public APIs at any privilege this
app has; promising it and shipping a hop list of timeouts would have been
worse than not shipping it.

The same socket gives the gateway probe a real echo RTT. The two methods are
labelled rather than blended, because they measure different things: a TCP
handshake also pays for the peer's accept path. A target the user wrote as
`host:port` is never answered with an echo — naming a port is naming a question
about that port.

**QUIC Initial packets are read, and that is still not interception.** SNI
kept the connection table legible on a device with Private DNS, and then
measured use showed where it stops: a browser page load is entirely UDP/443,
so there is no DNS answer and no TLS record anywhere in it. The Domains
screen — the whole point of which is to name what the device talks to — was
empty for the majority of a modern phone's traffic, and the info sheet said so
in as many words because there was nothing else to say.

There is now. RFC 9001 §5.2 protects Initial packets with keys derived by HKDF
from a **salt published in the RFC** and the Destination Connection ID the
client sends in the clear in the same packet. No secret takes part. The
protection exists to stop middleboxes ossifying the wire format, not to keep
the contents private, and anyone on the path can undo it. So the line drawn
for SNI holds unchanged: one field is read, it is a name, it is sent before
any key exchange, no key is touched, no certificate is substituted, and the
datagram is relayed byte for byte either way. Only the first datagram of a
flow is looked at, only when five bytes of header say it is an Initial of a
version this build knows, and nothing after the Initial is readable or read.

Three things fall out of the implementation worth recording. `TlsPeek`'s
ClientHello walk is now shared rather than copied — QUIC carries the same
handshake message with no record layer, and two hand-written walks over
attacker-supplied bytes are two bounds-checking surfaces that drift, with the
second being the one nobody re-reads. The test strategy is split on purpose:
the key schedule is pinned against the RFC's own worked example, and only the
framing is round-tripped. An implementation that derived keys its own
self-consistent way would encrypt and decrypt its own test packets perfectly
and read nothing a browser ever sent, so the normative vector is the part that
cannot be replaced by a round trip.

**And the first version did not work at all, which only the device showed.**
`TlsPeek` declines to reassemble a ClientHello split across TCP segments,
because there it is rare and buffering attacker-controlled bytes for a
cosmetic label is not a trade worth making. That reasoning was carried
straight over and it does not transfer: a modern ClientHello carries a
post-quantum key share and does not fit in one Initial packet, so for QUIC a
split hello is not the rare case but the ordinary one. On the M10 the first
build produced dozens of `udp/443` flows labelled `quic` in the connection
table and a name for **not one of them**, while every TCP/TLS flow beside
them was labelled correctly — a feature that passed its unit tests, shipped,
and did nothing. The unit tests could not have caught it: they built the
hellos they then read, and a hello this app builds fits in one packet.

The suite now carries that case: `VpnCaptureE2ETest` puts two Initials on the
wire with one ClientHello between them and asserts the name reaches Room. It
had to be pointed at a host that is actually listening on UDP/443 — aimed at
the gateway, the ICMP port-unreachable tore the relay's upstream socket down
between the two datagrams, the flow went with it, and the second Initial
arrived on a fresh assembler with no offset zero. That is a real property of
the engine rather than an artefact of the test, and it is recorded where the
assembler lives.

`QuicPeek.Assembler` now holds CRYPTO data across a flow's first few
datagrams under hard caps — four Initials, 16 KiB, sixty-four outstanding
ranges — and is dropped the moment the hello completes, a limit is passed, or
the flow sends anything that is not an Initial. Received data is tracked as
merged ranges rather than a high-water mark, because a client may send the
tail of its hello before the head and a mark forgets the tail was ever there.
The Initial-count and 16 KiB bounds and the out-of-order case are pinned by
tests that failed against the first implementation; the range cap is not yet. Re-measured on the device: twenty
names, `blog.cloudflare.com` and `www.google.com` among them, each attributed
to Chrome and labelled QUIC.

**The firewall now leaves evidence.** Blocking an app refused the flow, marked
it and retired it, and that was the whole story: no event, no counter, no
gauge. Because a blocked app has no live connections *by construction*, the
Apps screen showed it exactly as it shows an app that is not running — so
there was no way to tell a working block from a broken one, which is the same
honesty failure as the `paused` flag in §8b. Refusals are now observed on the
packet path, buffered for the poll tick the way DNS answers are (a blocked app
retries hard enough that anything more than an append would be felt there),
counted per app onto `AppRow.blockedAttempts`, and summarised in the log.

The summary matters as much as the record. One line per refusal would bury
everything else within a minute, so `BlockedEvents` emits once per app per
minute carrying the count accumulated since the last line — and the first
refusal is reported immediately, because the user has just pressed Block and
is watching. The level is INFO, deliberately: they asked for this, so a
refusal is the feature working, and WARN would put a notification and a
webhook POST behind every retry of an app they have already dealt with.

**Four event kinds existed that nothing could ever emit.** `CAPTIVE_PORTAL`,
`ENGINE_STARTED`, `ENGINE_STOPPED` and `ENGINE_REVOKED` were declared,
filtered on, and present in the CSV vocabulary, and no line anywhere
constructed one. `ENGINE_REVOKED` is the worst of them: Android permits a
single VPN, so another app connecting ends this one's capture with no error
and no tick — the one externally caused failure the app cannot prevent was
also the one it never mentioned. All four have producers now, and
`arch/EventKindsProducedTest` is the generalisation of `UiActionsWiredTest`
and `BridgeFieldsWrittenTest` to the third vocabulary the app publishes.

Two details in those producers are load-bearing. The lifecycle events come
from **one** collector on `VpnBridge.running` rather than two, because
`reportRevoked` sets its flag before detaching: by the time `running` goes
false the reason is already known, and collecting both flows would report a
revocation as a revocation *and* a stop. And the captive-portal producer is
keyed on what was last *reported* rather than on the previous row, with
clearing additionally requiring the link to validate — `onAvailable` fires
before `onCapabilitiesChanged`, so a re-registering link is published for one
rebuild carrying no capabilities at all, and comparing adjacent rows would
announce a sign-in the user never performed and then have nothing left to
compare against when the real one arrived.

**The neighbour sweep is the app's only active source, and is treated as
such.** Every other screen watches traffic that was happening anyway; this one
sends packets to hosts the user did not name. It therefore has a button and no
automatic refresh, it sweeps the device's own on-link IPv4 prefix (plus one
all-nodes echo on IPv6, below) and nothing else — no typed range, no routed network, nothing wider than a /22 — and it
skips the broadcast address, which is the one probe on the list that would
reach hosts nobody asked about. A prefix too wide returns **no** list rather
than a truncated one, because a partial sweep presented as a finished one is
the silent shortfall this project treats as a defect.

Names come from `NsdManager` rather than from a socket of ours, which buys
three things at once: no multicast permission, no multicast lock, and — while
capture is on — no traversal of our own TUN. IPv6 is not swept — an on-link
prefix there is a /64, so there is no list to walk — but one echo to the
all-nodes group `ff02::1` lists every IPv6 host that answers (§8c). The screen also states, in place of an empty table, that this is a
list of what *answered* — a host filtering ICMP and publishing no mDNS is on
the network and not on the screen, and a guest network is built so that
nothing answers.

**The budget's suppression is scoped to the cycle, not to a clock.** Every
other rule in `AlertRules` watches a subject that goes away — a connection
closes, a burst ends — so a cooldown plus `pruneFired` is enough. A cap sat on
top of stays breached for the rest of the month, and pruning drops the
cooldown entry two windows after it was set: the rule would fall silent for
two minutes and then fire again, every two minutes, until the cycle rolled
over. A test written for that property caught it. The state is now the highest
*band* already reported and the cycle it belongs to, reset when the cycle
moves, so a climb from 80% to 95% is two lines and sitting at 85% for three
weeks is none.

The projection is deliberately the simplest possible model — mean rate so far,
extended across the whole cycle. A weekday/weekend split or a trend fit would
be a better forecast on some devices and a worse one on others, and neither
can be explained in the line the card has room for. "At this rate" is a claim
the reader can check.

**Network trust is computed once, on the snapshot.** The verdict is a function
of a link and the user's settings, and two consumers need it: the event that
says "you have arrived somewhere you do not trust" and the automatic responses
that start capture and scan this device's listeners. Deriving it twice is how
the log ends up explaining an action that did not happen, or staying silent
about one that did, so the assembler computes `untrustedDefault` and both read
that. The per-network key is the SSID and not the interface — `wlan0` is
`wlan0` on every Wi-Fi network there has ever been, so keying trust on it
would mark every network at once — and not the platform netId either, for the
reason recorded under *Link identity is the interface* below.

**SNI is read; nothing is decrypted.** The connection table labelled rows from
DNS answers crossing the TUN, which is dead code on any device with Private DNS
switched on — the default on most modern Android. There the answers are
encrypted, no name is readable, and every row fell back to a bare IP with
nothing explaining why. The `server_name` in a TLS ClientHello is the one name
still in the clear, and it is read off the first in-order data segment of a
flow whose first byte is a handshake record: one byte comparison for every flow
that is not TLS, and no reassembly for the rare ClientHello that spans
segments, since buffering attacker-controlled bytes for a cosmetic label is not
a trade worth making.

This sits next to "payload capture is a non-goal", so it is worth being exact
about where the line now is. Two fields are read and no others: the answers in
a DNS response and the server name in a ClientHello. Both are names, both are
sent unencrypted, no key is touched and no certificate is substituted, and the
bytes are relayed unchanged either way. (Since widened to four reads under the
same rule — the name in a QUIC Initial, below, and the ServerHello's
negotiated version and cipher, §8c.) Encrypted Client Hello will take this
source away in time; when it does the field is simply absent and rows fall back
to addresses, which is the same degradation Private DNS already produces for
the DNS source.

**Names are a second event source, with its own producer.** `EventEngine`
derives everything from diffing two snapshots and owns `new_public_host`. Names
are not on a snapshot at all — they arrive from the capture engine's packet
path — so `DomainEvents` produces `new_domain` instead. Two producers reading
one source is what put every new host in the log twice once before (see *One producer for
`new_public_host`* below); these
two read different sources, and an address and a name are different subjects
even when they describe the same server. The observations are buffered and
written by the poll tick rather than where they are seen: launching a coroutine
and a database write behind every DNS answer on the device is a cost the packet
path should not carry.

**No retransmission timer in the TCP proxy.** The downstream side is a TUN file
descriptor handed to the local kernel, which does not lose segments. Flow
control *is* implemented: the peer's advertised window must be respected or a
fast download overruns its receive buffer.

**Link identity is the interface, and a link is down only after two missed
polls.** Android re-issues a network's netId whenever it re-registers — toggling
this app's own capture stepped `wlan0` from 785 to 786 while the link carried
traffic throughout — so keying on `NetworkRow.id` turned the capture switch into
a `WARN network_down wlan0 vanished` plus an alert notification. `EventEngine`
keys links on `transport/ifaceName`, and because `onLost` and `onAvailable` can
straddle a tick, a link must be missing for `DOWN_CONFIRM_POLLS` (2) consecutive
polls before it is reported down. The set of known links survives `reset()`, or
a capture restart would re-announce every network. This app's own `tun0` is
excluded by `OwnTunnel` (§7b).

**One producer for `new_public_host`.** There were two: `EventEngine.connEvents`
deduping in memory, and `EngineController.firstContactEvents` deduping through
the Room host store. Both ran on the same snapshot, so every new host wrote two
lines to the log — visible on device as paired entries one second apart. The
engine is now the only producer, and it is *seeded* from the persisted store at
start-up (`seedSeenHosts`), reporting back through `lastNewHosts` what the
caller should persist. That keeps the per-kind cap and suppression the engine
already owned while gaining the restart-survival the store was added for.
`HostSeenRepository.known()` existed for exactly this and had no caller — the
defect class in §8, caught by its own symptom rather than by the guard.

**The gateway is stored with its zone.** The Overview's headline latency read
"unreachable / connect failed" on every dual-stack network, because the default
route's next hop there is an IPv6 link-local and `RouteInfo.getGateway()` hands
back `fe80::…` with no zone on it. That address does not identify a host — the
same `fe80::` can exist on every interface — so `connect()` returns `EINVAL`,
which surfaces as a `ConnectException` whose message says neither "refused" nor
"unreachable" and so fell through to the generic "connect failed". Confirmed on
device: `nc fe80::…%wlan0 80` connects and `nc fe80::… 80` answers
`connect: Invalid argument`. `gatewayOf` now attaches the zone from the route's
own interface, which is by definition where the next hop is reachable, and the
probe returns 8 ms. The prober itself was never wrong: a refused connect already
counted as a valid round trip.

**The status banner says what happened, not what was attempted.** `startVpn`
announced "requesting VPN permission" unconditionally, but consent is asked for
only once; every later start left that sentence sitting in a banner describing
something that never occurred. The ViewModel now says "starting capture", and
`MainActivity` — which is what calls `VpnService.prepare` and therefore the only
thing that knows — reports consent or refusal. A `VpnBridge.running` collector
retires the message once the engine is actually up.

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
alternating rounds with the UI foregrounded in both arms, on a 5 GHz link
topping out near 11 MiB/s. Two independent runs, medians:

- throughput: 11.06 / 11.09 MiB/s direct vs 9.76 / 10.02 captured — **ratio
  0.88 / 0.90**
- CPU: 0.074 / 0.068 vs 0.366 / 0.373 s/MiB — **5.0× / 5.5×**

Captured CPU per MiB repeats to within 2%; the multiplier is noisier only
because its divisor is small, so the honest statement is "about 5×".

A JVM micro-benchmark then put total byte-level codec cost at ~1 ms/MiB, i.e.
**under 0.3%** of that ~370 ms/MiB. The multiplier is not in the packet
arithmetic; it is GC pressure, per-packet syscalls, coroutine handoffs, and the
structural triple kernel crossing of a userspace relay. Buffer pooling and
reused parse scratch removed ~1400 allocations and ~700 copies per MiB, and the
re-measurement says that bought **0.570 → ~0.37 s/MiB, a 35% cut**, taking the
multiplier from 8.1× to about 5×.

The cold round is the one that reproduces exactly: both runs put captured
round 0 at 7.97–7.98 MiB/s and ~0.5 s/MiB against ~10 MiB/s and ~0.35 s/MiB for
the warm rounds. A cold JIT and an empty buffer pool are a third of the
throughput and a third again of the CPU, which is what the user meets in the
first seconds after switching capture on.

**The throughput conclusion was revised by the same run.** The earlier ratio of
1.03 was measured on a ~6 MiB/s link and read as "the proxy does not throttle".
On a link fast enough to expose it, capture gives up ~12%: the old number
described the Wi-Fi bottleneck, not the proxy. A benchmark whose bottleneck sits
outside the thing being measured reports the bottleneck — the same reason this
harness insists on a LAN source instead of a CDN, applied one layer further in.

The largest lever, the TUN MTU (~10× fewer packets, writes and ACKs), is now a
user setting — 1500 by default, 4000 / 9000 / 16000 on request — validated
byte-exact at 9000 (§8d) but not re-benchmarked, which is why it is not the
default: it changes what every app puts on the wire through the TUN.

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
- **`targetSdk = 35` means edge-to-edge is compulsory on Android 15+**, and
  nothing warns about it. `Scaffold` hands its insets to the content lambda
  only, so the navigation rail and the status banner — which live outside that
  padding — were drawn under the system clock. The inset is now applied once to
  the row that holds all three, with the scaffold's own top inset removed so it
  is not paid twice. Robolectric renders with no system bars, so the whole suite
  passed while the device was visibly wrong: this class of bug needs a
  screenshot, not a test.
- **A screen not on the nav bar is a screen no suite visits.** The device suite
  drove all seven navigation destinations and never once opened **Usage**, which
  is reached from the top bar instead. The crash described in §8b therefore
  survived every green run until the icon was tapped by hand.
- **`getConnectionOwnerUid` does not answer for a socket that has closed.** It
  is a query about a *live* connection, so a short HTTP request can finish
  before the lookup runs and that flow keeps the `-1` sentinel. A capture test
  that pinned its assertion to whichever flow appeared first therefore failed
  intermittently; asserting that *some* flow attributes is both the property
  worth having and the one the platform can deliver.
- **`am instrument` exits 0 even when tests fail.** The verdict is in its output
  (`OK (n tests)` versus `FAILURES!!!`), which is why `run-device-e2e.sh` now
  parses the log it captures instead of trusting the exit status.
- **A leaked benchmark server reports a plausible lie.** `run-benchmark.sh`
  backgrounded its HTTP server in a subshell and trapped `kill %1`, which killed
  the subshell and left python holding the port with its blob directory already
  deleted. The next run could not bind, the survivor answered 404, and because
  the test swallows transfer errors the result came back as `0.00 MiB/s` with a
  `cpu_multiplier` computed from zeros — a number, not an error. The server is
  now `exec`ed so `$!` is the process that must die, the port is checked before
  binding, and the blob is fetched once before three rounds are spent on it.

### 7b. Third hardware round: what the platform redacts, and what was never widened

A third pass over the real device, 2026-10-23, against the 44-test instrumented
suite passing. Seven defects, and the suite caught none of them. This time the
reason was not that it was closed (§8b) but that five of the seven were the
*same shape*: a vocabulary grew and a hand-written consumer of it did not.

**The app could not name the Wi-Fi network it was on.** `WifiSource` reads two
sources and chained them with `?:` — which falls through on a *missing* source
and not on a *redacted* one. On API 31+ the authoritative one, the `WifiInfo`
hanging off `ConnectivityManager.getNetworkCapabilities()` read synchronously,
is **always** redacted: it has no caller identity to run the location check
against. Measured on the M10 with `ACCESS_FINE_LOCATION` granted, location
services on and the Activity in front, side by side in one call:

| source | SSID | BSSID |
|---|---|---|
| `getNetworkCapabilities().transportInfo` | `<unknown ssid>` | `02:00:00:00:00:00` |
| `WifiManager.getConnectionInfo()` | `"Hystax"` | the real one |
| callback + `FLAG_INCLUDE_LOCATION_INFO` | `"Hystax"` | the real one |

So the first source always answered, the second was never consulted, and the
Links screen rendered `SSID —`. The cost was not the blank field. **Network
trust is keyed on the SSID**, deliberately (`wlan0` is `wlan0` everywhere, and
netId flaps — see *Link identity is the interface* in §5), so `NetworkTrust.keyOf` returned null for every Wi-Fi
link: the Links screen drew no trust buttons at all, nothing the user marked
could be remembered, and the whole round-seven trust feature was inert on every
device that runs the location check. The fix merges the sources — the first
wins the radio numbers, the first that *has* a name wins the name. Verified:
`SSID Hystax` and the Trust / Untrusted buttons now render, and marking the
network writes `wifi:Hystax` to DataStore. `WifiIdentityDeviceTest` is the
guard, and it cannot be a JVM test: redaction is the platform deciding what a
*caller* may see, and Robolectric's shadow returns whatever the test put in it.

**Four of those vocabularies had grown, and their consumers had not** (the
fifth, the scan's port window, is below). This is §8's pattern — a declared
thing with no producer — inverted: a producer with consumers that predate it.
None of them is a compile error.

- **`NameSource` gained `QUIC` and the event log still said "resolved by
  DNS".** The wording was `if (source == SNI) "seen in TLS SNI" else "resolved
  by DNS"`, written when there were two sources. Read straight out of the
  device's own event table: `new_domain blog.cloudflare.com · resolved by DNS`
  for a name the Domains screen two taps away labelled QUIC — and the same for
  `split-hello.quic.test`, which the instrumented suite had itself put on the
  wire as a QUIC handshake with no DNS answer in it. The permanent log, the
  events CSV and the alert webhook all carried the false claim, and on a device
  with Private DNS — the case QUIC peeking exists for — that is every name the
  app knows. The provenance now lives on the enum. Two smaller instances of the
  same omission went with it: the Domains headline counted SNI alone and read
  "11 names · 1 from SNI" over ten QUIC rows, and the source badge was tinted
  for SNI only. `arch/NameSourcesReportedTest` forbids singling out a constant.
- **`NetSnapshot` gained six fields the exporter never wrote.** `neighbours`,
  its three sweep fields, `quota` and `untrustedDefault`. A snapshot exported
  seconds after a sweep that found twenty hosts carried none of them.
  `fullSnapshotRoundTrips` compares two whole objects and still missed it,
  because **the fixture was never widened either** — both sides held the
  defaults and the equality passed. The guard is now reflection over the
  class's declared fields (`SnapshotJsonTest.everyDeclaredFieldIsEncoded`),
  which a new field cannot be left out of. `LatencySample.method`
  was missing from the file too.
- **The Connections Share button exported a table nobody was looking at.** That
  screen renders one of three tables and `exportCurrentTable` called
  `Csv.conns` for all three: a screen reading "8 closed" produced fourteen
  `established` rows with no close time, lifetime or final totals, and "4 hosts"
  produced the flat table — against `Csv`'s own first line, "every table exports
  the view the user is looking at". `Csv.closed` and `Csv.groups` did not exist.
  `arch/ExportsTheVisibleTableTest` asserts the export branches on every mode
  flag the screen branches on, and that no `Csv` table is unreachable.
- **The Prometheus exporter blended the two latency methods.** DESIGN §5 says
  they are "labelled rather than blended, because they measure different
  things"; the screen labels them and the scrape did not, reporting
  `target="192.168.88.1:80"` for a reading taken by ICMP echo that never touched
  a port. There is a `method` label now, and an echo reports the host it
  actually probed.

**The app ran a server its own exposure screen could not see.** The Services
self-scan knocks on a fixed list of common ports, and the metrics endpoint had
been moved to 9191, which is not on it: the screen said "No listeners found …
the expected result for a phone that runs no server" while a scrape from another
machine on the LAN returned 139 lines of metrics, and the Overview "Exposed"
tile read 0. A full-range scan found it at once. The one listening socket this
app never has to guess about is its own, so both scan paths — the button and the
background worker that raises off-baseline alerts — now add it to the window,
and `arch/ScanWindowsAgreeTest` keeps the two paths looking at the same ports.
The default port, 9187, *is* on the common list, so this only ever bit a user
who moved the endpoint — which is exactly the user who went looking for it on
the screen that says what this device is exposing.

**Round six's fabricated outage survived for the one link this app owns.** That
round fixed the flapping-netId case and recorded it as "it fabricated Wi-Fi
outages, and caused them itself". The capture engine is a `VpnService`, so while
it runs the platform publishes `tun0` and this app monitors it like any other
link: every press of the capture switch wrote `INFO network_up tun0 (vpn)
appeared` and every release wrote **`WARN network_down tun0 (vpn) vanished`** —
a warning, so a notification and a webhook POST — one line away from the
`engine_stopped` entry that already said exactly that. Five such pairs
accumulated in an hour of testing. `OwnTunnel` recognises the tunnel by the two
addresses `DuckVpnService` configures (shared constants, not a second copy:
`tun0` is `tun0` for every VPN, as `wlan0` is), and the event layer tracks it
without reporting on it. Somebody else's VPN dropping keeps its event — it is
the one externally caused failure that ends capture.

Also corrected: a field holding twenty digits was refused as "not a whole
number", which it plainly is. The refusal was right and the reason was not, in
the one function whose entire job is saying why something was refused. And
`NETWORK_UNTRUSTED` named the interface — `wlan0 (wifi)`, which every café on
earth reads as — and deduped on it too, so arriving on a second untrusted SSID
was swallowed as "already reported". It now uses the trust key and label, the
same identity the feature is built on.

**What this round says about the guards.** Five of the seven faults are one
sentence: *a set grew and a hand-written reader of it did not.* `NameSource`,
`NetSnapshot`'s fields, the Connections screen's modes, the latency methods, the
scan's port window. `EventKindsProducedTest`, `UiActionsWiredTest` and `BridgeFieldsWrittenTest`
already encoded that rule for three vocabularies; this round added it for four more, and in
each case the guard was verified red against the unfixed tree before being kept.
The lesson generalises past any one of them: **an `if/else` over a set that can
grow is a latent fault, and the compiler will never ask about it.** An
exhaustive `when`, a property on the enum, or reflection over the declaration
will.

## 8. Recurring defect class, and the guards

Three times, a feature was fully implemented, documented, and reachable from
nothing: `openSnapshot` had no control, `requestWifiPermission` had no caller,
and HIGHLIGHT search mode computed match ranges that no screen rendered.

`app/src/test/.../arch/UiActionsWiredTest.kt` now asserts every `UiActions`
member has a caller in `ui/` or `MainActivity`. Reflection proves a method
exists; only a source scan proves something invokes it.

It has since been joined by ten more guards in the same package, each verified
red against the tree it was written for:

| Guard (`app/src/test/.../arch/`) | Forbids |
|---|---|
| `UiActionsWiredTest` | a `UiActions` member with no caller in `ui/` or `MainActivity` |
| `BridgeFieldsWrittenTest` | a mutable `VpnBridge` field that nothing assigns |
| `EventKindsProducedTest` | an `EventKind` constructed nowhere in `main/` outside `ui/` |
| `NameSourcesReportedTest` | a reader that singles out one `NameSource` instead of asking the enum |
| `ExportsTheVisibleTableTest` | a Share export that ignores a mode the Connections screen branches on, or an unreachable `Csv` table |
| `ScanWindowsAgreeTest` | the Services button and `ServiceScanWorker` scanning different port windows |
| `TablePipelineTest` | a `UiState` table assigned straight from the snapshot, bypassing filter, search and sort |
| `SortChipsSortTest` | a rendered sort chip whose ascending and descending orders are the same |
| `FlowClosePathsTest` | a flow-retire path that drops the app label or the block flag |
| `DomainEventsLabelledTest` | a `new_domain` event built from rows with no app label |
| `FrozenSnapshotTest` | live-device data (events, names, usage) reaching `UiState` while a snapshot is open |

### 8a. A green suite that tested nothing

The device suite ran 29 tests against a release of this app in which four
features were new, and passed — because not one of those tests touched any of
them. Hand-driving the device found three defects in an hour, two of which
(QUIC naming no flows at all, a source label contradicting the name beside
it) were in code the unit tests covered thoroughly. The lesson is narrower
than "write device tests": the unit tests were not weak, they were *closed* —
`QuicPeekTest` builds the hellos it then reads, and a hello this app builds
fits in one packet, so the suite could not have discovered that a real one
does not.

The follow-up had its own version of the same trap. The first
`NeighbourSweepDeviceTest` read the engine's snapshot without starting an
Activity, so the engine never ran, every case skipped on a violated
assumption, and `am instrument` reported `OK (7 tests)`. A suite that skips
is reported exactly like a suite that passes. It now opens with a case whose
only job is to fail when the setup did not work.

A fourth turned up on the device, and the guard does not cover it because it is
not a `UiActions` member: **`AppRow.todayRx` / `todayTx` were read in six places
— the Apps row, its detail sheet, the `today` sort column, the CSV and the JSON
export — and written in none.** `refreshUsage()` queried NetworkStatsManager on
the slow cadence and discarded the result, so every app reported `today 0 B`
however much it had moved. The missing line was the one that carried the answer
into the assembler.

The lesson generalises past `UiActions`: a field the UI renders needs a producer
as much as a button needs a handler, and "compiles and has a default" hides the
absence of one.

A fifth was found by writing the guard for it rather than by a device run.
`arch/EventKindsProducedTest` asserts every `EventKind` constant is
constructed somewhere in `main/` outside the UI that renders it, and it found
**four** orphans at once: `CAPTIVE_PORTAL` and the three engine-lifecycle
kinds. They are the same class as `openSnapshot` and `AppRow.todayRx` applied
to the third vocabulary the app publishes to the user, and the compiler cannot
see it — an enum constant nothing constructs is legal, and an exhaustive
`when` over it in the renderer makes it look thoroughly wired. The rule the
guard encodes: declaring a kind is a promise the app can report it, and a kind
with no source on Android belongs left out, the way retransmit counts are.

### 8b. The second class: two correct layers, a wrong seam

A device run found three defects that every existing test called fine, because
each lived *between* two components that were individually right and
individually tested.

- **`daily_usage.dayEpoch` held milliseconds.** `UsageHistorySource` queries
  NetworkStatsManager in milliseconds and was handing the query boundary
  straight to `DailyUsage`, whose contract is a day number. The screen's
  `LocalDate.ofEpochDay` threw and took the process down. The producer tests
  asserted milliseconds, the formatter tests passed day numbers, and the two
  never met. Fixed at the producer, with `MIGRATION_1_2` for devices that
  already stored the bad values and a formatter that labels an unrenderable key
  instead of throwing — a formatter is the wrong place to discover a unit
  mismatch.
- **Capture mode zeroed every device rate.** `SnapshotAssembler` pruned dead
  flows with `RateTracker.retain`, which drops every key it is *not* given — on
  a tracker it shared with `ApiEngine`. So each poll deleted `device.rx`,
  `net.*` and `uid.*`, the next sample re-baselined, and with capture on the
  throughput card, every link's rate and per-app throughput all read a flat
  `0 B/s` under a sustained multi-megabyte download. Flows now own a separate
  tracker; the two lifetimes were never compatible in one map.
- **Session totals were flow-only.** The grand total was accumulated from the
  flow deltas, which do not exist in API mode — the always-on default — so
  "Session total" read `0 B` forever, directly beneath two live rows fed by the
  device counters. It now integrates those same counters, so the card is
  consistent with itself and true in both modes.

`SnapshotAssemblerTest` is the guard for the last two: the assembler is where
the two engines meet the UI, and nothing tested it at all.

A fourth came out of the same run, and it is the purest example of the class:
**`snapshot.paused` was only ever published by a tick, and pausing is what stops
ticks.** Tapping Pause set the flag, the loop saw it and skipped its next tick,
and so the snapshot went on saying `paused = false` forever — the engine really
had stopped, while the button, the title marker and the `⏸ PAUSED` badge all
still showed a running app. Resuming worked, because a running loop does tick.
By hand it usually *looked* fine: the flag would land mid-tick and be picked up
by an assemble already in flight, which is a race, not a mechanism.
`applySettings` now republishes the flag, so the control is honest at the moment
it is pressed.

That one hid behind a test that clicked the button and asserted nothing —
`performClick()` twice with no expectation proves only that a tap does not
crash. `t06` now reads the control's own content description before and after,
which is what caught it.

The same run found the search counter summing matches across all four tables
while `n`/`N` could only walk the visible one — "3 matches" over a table showing
none. The counter and the highlighted set are now read off one `SearchResult`,
so the two cannot disagree again.

## 8c. Ninth round: the roadmap, and what it changed underneath

Twenty features from `docs/ROADMAP.md` landed together. Most are additive and
documented in the README; these are the decisions that shape the code.

**Settings reach the engine for the life of the process, not of the screen.**
`EngineController.applySettings` used to be called only by the ViewModel's
collector, so capture started from the Quick Settings tile with no Activity
ran with an *empty firewall* — a blocked app was not blocked. Start-on-boot,
the system's always-on VPN and automation made that the common case rather
than a corner, so `DuckApp.Deps.engineSettings` now owns the collection and
`DuckVpnService.startEngine` starts the poll loop itself. The ViewModel keeps
only the remembered tab.

**The firewall is compiled, never interpreted.** `domain/firewall/Firewall`
turns rules, the block list, per-app network policies, blocked uids and
over-budget uids into hash sets once per change; the packet path asks
`VpnBridge.verdict(uid, ip)` on a flow's first packet and pays a few probes.
Rules name packages and compile to uids, and a rule for a package that is not
installed compiles to *nothing* — the dangerous direction to fail in would be
a global rule. Two moments decide: at SYN with whatever name the device already
holds for the address (`VpnBridge.names`, a concurrent map separate from the
display cache, whose LRU order mutates on read), and at the first handshake
record, where an SNI or QUIC Initial naming a blocked host *cuts* a flow that
could not have been refused (`cutIfNamedBlocked`). Every refusal carries its
reason onto `Flow.blockReason`, the closed row and the log line.

**What a flow is flagged with comes from ports, addresses and cleartext
handshakes only.** Cleartext is a port table; encrypted DNS is port 853 or 443
to a fixed resolver list; deprecated TLS is the ServerHello's negotiated
version, read from the first upstream chunk of a flow whose client opened with
a TLS record (`TcpConnection.onServerHello`). The ServerHello read joins the
three name reads in the Privacy section: it is sent before any key exchange.
Closed rows are annotated as well as live ones — a plain-HTTP request is
usually over before the next poll sees it live.

**Recovery is a state machine beside the cooldown, not a use of it.**
`AlertRules.breaches` holds every subject a sustained rule fired on, refreshed
whenever it is over *whether or not the cooldown let it fire*; one minute
under emits `*_cleared`. A rule switched off drops its breaches silently —
"cleared" would claim a recovery when the app simply stopped looking.

**The 24-hour chart is the only chart on disk.** `MinuteAccumulator` emits a
minute only when the next one starts, integrates bytes over a capped gap (a
process asleep for an hour does not charge that hour to the minute it woke
in), and `series()` leaves unwatched minutes as zeros rather than closing the
gap. `MIGRATION_3_4` adds `minute_history` by hand; `MinuteMigrationTest`
validates it against the exported v4 schema.

**IPv6 hosts are asked, not swept.** One echo to `ff02::1%iface` through the
same unprivileged ping socket, reading every reply's source with `recvfrom`.
Whether the kernel delivers more than one reply to a ping socket was unknown
when this was written; on the M10 it does — one echo drew ten IPv6 hosts.

**Automation is an exported receiver that refuses by default.** Any app can
send a broadcast, so the switch is off until the user turns it on, and every
command — obeyed or refused — is an `automation` event. Starting capture from
it still requires prior consent, and Android's background FGS limits may
refuse it; that refusal is logged rather than hidden.

**A layout lesson the unit suite caught.** The row flags first went on the
row's first line, after a `weight(1f)` label: Compose gave the label the
remaining width and the flags *ten pixels*. They now get a line of their own,
drawn only when there is something to flag. A test asserting `isDisplayed`
found it; one asserting `exists` would have passed.

**`BridgeFieldsWrittenTest` was widened, not weakened.** It excluded the whole
of `DuckVpnService.kt` as a writer because the bridge lives there; but the
service class in that file is the engine's legitimate writer (`terminator`,
`alwaysOn`, `lockdown`). The scan now excludes only the `object VpnBridge`
body.

### 8d. The device round for §8c

All 52 instrumented tests passed on the M10 — and, as in every round before,
hand-driving found what they did not:

- **A refused connection hung instead of failing.** `TcpConnection.reject()`
  ran before `onSyn()` had recorded the SYN's sequence, so its RST
  acknowledged 0; the app's kernel discarded it as unacceptable and kept
  retransmitting the SYN until connect timed out. Every firewall refusal —
  including the original app block — cost the app its whole connect timeout,
  and a blocked app's retries were what filled the "354 connections refused"
  counts. `reject(syn)` now acknowledges `seq + 1`; `TcpRejectTest` pins it,
  and a refused `nc` on the device now fails in 0.04 s rather than 5.
- **The Hosts sweep swept this app's own tunnel.** With capture on, the
  default network is the TUN, so "the default link" was `tun0`: the banner
  read `10.215.173.1/30 + ff02::1 on tun0`. Pre-existing for IPv4; the IPv6
  probe made it visible. `LinkChoice.sweepLink` uses the link underneath.
- **Start-on-boot ran and was refused.** The receiver fired and requested
  capture; the OEM's `awbms` dropped the service start, silently. The code
  path is right and the platform is not the app's to fix, so the receiver now
  checks whether capture actually came up and logs `boot_start` either way.
- **Domains and Events blamed the wrong thing when a search emptied them**
  ("No names seen yet" over a store of 12 names); both now say the search or
  filter hides the rows, as the other screens already did.
- **A refusal's log line did not say which rule refused it**, so a policy
  block ("while the screen is off") read the same as an app block.

What worked first time on hardware: domain and address rules, the screen-off
policy (refused with the screen off, allowed on wake), per-flow reset, the
per-app host block, cleartext flagging from another uid, block-list and AS
imports through the system picker, owner labels, saved views and their
launcher shortcuts, Compare, automation (refused while off, obeyed and logged
when on), the widget's live rates and capture toggle, the 24-hour chart
surviving a reinstall, and a 20 MB transfer byte-exact at both MTU 1500 and
9000.

## 9. Deliberate non-goals

Payload capture / PCAP export (this is a monitor, not a sniffer), root mode,
TLS interception, traffic shaping (the firewall allows or refuses, it never
throttles), and any companion
daemon.

"TLS interception" means exactly that: decrypting, substituting a certificate,
or touching a key. Reading the cleartext `server_name` a client sends before
any key exchange is not that, and §5 records why it is in and where its line
sits. The same holds for the name in a QUIC Initial: its protection is derived
entirely from a salt printed in RFC 9001 and a connection id sent in the
clear, so removing it needs no secret and gives nothing a key would.

In-app traceroute is also out, for the reason §5 records — the ICMP
errors it needs cannot be read through any public API.
