# ducknetview-droid — feature roadmap

Suggestions from a code read on 2026-10-30, against `3604b93`. Each entry says
why it is worth doing, what it builds on, and where the honesty rule (README:
*columns with no source are hidden, not shown empty*) constrains it. Effort is
S (a day), M (a few days), L (a week or more).

Ordered by value for effort within each tier.

**Status (2026-10-30): all twenty are implemented** — see README and DESIGN
§8c. Some shipped narrower than written here: ALPN is not recorded (#17, version
and cipher are); automation is gated by an in-app switch, not a permission
(#14); the widget uses RemoteViews, not Glance (#13); there is no cleartext
quick filter or HTTP `Host:` read, only the row flag (#4); the v3→v4 migration
is covered by a JVM test, not a device test (#5); boot start logs its own
`boot_start` kind rather than `engine_started` (#2). What remains open from them: the firewall acts on new connections only;
F-Droid has store text but no release signing or reproducible build; the TUN
MTU is a user experiment, not a measured default.

## Tier 1 — closes a gap in something the app already does

### 1. Recovery events (`*_cleared`) — S
The TUI raises `throughput_process_cleared`, `latency_cleared` etc. once a
subject has been back under its threshold for a minute, including when it went
away. This app has no counterpart: `AlertRules` fires `app_bps`,
`connection_bps` and `rtt_ms` and never says they ended, so a webhook consumer
(the Android `--on-alert`) is paged and never resolved, and the event log
records beginnings with no ends. `AlertRules` already keeps per-subject fired
state; add the cleared transition as INFO, with the TUI's labels. `fanout` and
`conn_growth` deliberately get none (a burst is a moment, not a state). The
existing `EventKindsProducedTest` already forces each new kind to have a
producer.

### 2. Survive a reboot: always-on and start-on-boot — S
Capture stops at reboot and nothing says so. The manifest already holds
`RECEIVE_BOOT_COMPLETED` (used only by WorkManager). Two pieces:
- declare `android.net.VpnService.SUPPORTS_ALWAYS_ON` and make `onStartCommand`
  treat a system (always-on) start as a normal start — it already does
  implicitly, so the work is mostly the info sheet and Settings explaining the
  system's *Always-on VPN* / *Block connections without VPN* switches and what
  lockdown means when the app's own engine stops;
- an opt-in "start capture after boot" setting, valid only once consent exists
  (`VpnService.prepare() == null`), logging `engine_started` with a reason.

### 3. Background data per app — S/M
"Which app used data while I wasn't using it" is the question a metered user
actually asks, and `NetworkStatsManager` answers it without capture:
`queryDetailsForUid` buckets carry `STATE_FOREGROUND` vs `STATE_DEFAULT`.
`UsageHistorySource` ignores the state today. Add a foreground/background split
to the Usage screen's per-app rows and an optional alert ("app X moved > N MB
in the background today"). Works in API mode, which is the mode most users
stay in.

### 4. Cleartext connection flagging — S
A security monitor should say when an app talks plain HTTP, FTP, Telnet, POP3,
IMAP or SMTP-without-STARTTLS. Port-based classification needs no payload at
all; optionally the HTTP `Host:` header can name the flow, which is the same
class of read as SNI (sent in the clear, a name, nothing decrypted) and must be
recorded in the Privacy section if added. Surfaces: a `cleartext` badge and
quick filter on Conns, a count on the Overview security summary, a once-per-app
`cleartext_flow` INFO event, a Prometheus gauge.

### 5. Last 24 hours on disk — S/M
TUI parity ("per-minute throughput peaks kept on disk, so *when did that spike
happen* is still answerable after a restart"). Every chart in this app dies
with the process, and Android kills processes far more readily than a terminal
user does. A Room table of 1440 per-minute rows (rx/tx peak + mean), written by
the poll tick, pruned on write; an Overview chart. Needs a v3→v4 migration with
a `UsageMigrationDeviceTest`-style device test.

### 6. CI — S
README "Known gaps" still lists no CI. A GitHub Actions workflow running
`testDebugUnitTest` (with the existing `forkEvery`/heap settings) and
`assembleDebug`, plus the `arch/` guards which are the project's real safety
net. Instrumented tests stay manual (they need the VPN `appops` grant and a
real link).

## Tier 2 — new capability on top of existing machinery

### 7. Domain and host firewall — M/L
The firewall is all-or-nothing per uid. The name machinery (DNS answers, SNI,
QUIC Initial) and the rdns cache already map flows to names, so a block list
of domains, IPs and CIDRs — global or per app — is the natural next step, with
refusals counted through the existing `BlockedAttempt` path. Be precise about
the limits in the info sheet: an IP/CIDR or DNS-learnt name can be refused at
SYN time; an SNI/QUIC-only name is known only *after* the upstream connect, so
those flows are cut at the first client record rather than refused; Private DNS
hides the DNS route entirely. A hosts-format list import (tracker lists) makes
it a privacy feature, and lets the Domains screen badge tracker domains even
when nothing is blocked.

### 8. Per-flow actions on Conns — S/M
The TUI's `x` (signal the process) has no Android equivalent, but the proxy owns
both ends of every flow: **terminate this connection** (RST towards the app,
close upstream) and **block this remote for this app** (feeds item 7). Both are
exact in capture mode and absent — hidden, not greyed — in API mode.

### 9. Per-app policy by network — M
NetGuard-style rules on the existing firewall: block an app on mobile only, on
metered links, on untrusted networks (reuses trust profiles), or while the
screen is off. `blockedUids` becomes a small policy per uid; the decision stays
in the packet path, evaluated against the current underlying link
(`LinkChoice.underlyingLabel` already exists for this).

### 10. Saved views — M
TUI parity (`V`): a named bundle of tab, search + mode, sort per table, quick
filters, network/uid filter, group/closed/totals/rDNS toggles. On Android it
also wants **launcher shortcuts** (long-press the icon → "VPN traffic, busiest
first"). All the state already lives in `AppSettings`; the work is the bundle,
the picker and restoring it in one step.

### 11. Network owner (ASN) column — M
TUI parity (`asn_database`). rDNS is silent for most cloud addresses; an
offline ip2asn TSV, imported through SAF and indexed into a sorted range table,
gives an OWNER column on Conns/grouped hosts and an AS line in the detail pane.
Nothing bundled, no lookup leaves the device, the column does not exist without
a file — exactly the TUI's contract.

### 12. Snapshot diff — M
TUI parity (`--diff`): compare two snapshots — or a snapshot against the live
device — for listeners gained/lost, apps that appeared, first-contact hosts and
names, links that went away. The decode path and the frozen-view plumbing
already exist; this is a pure `domain/` diff plus one screen. Useful for "what
changed on this phone since last week".

### 13. Home-screen widget — S/M
Rates, capture state with a toggle, and the loudest talker, via Glance. The
Quick Settings tile and notification already compute all of it; the widget is
a third consumer of the same state.

### 14. Automation entry points — S
The app *emits* a broadcast on alert but accepts nothing. Exported,
permission-protected intents to start/stop capture, run a service scan, run a
LAN sweep and export a snapshot would let Tasker/Automate drive it (e.g. "scan
when I join a hotel network"). Capture start must still refuse without prior
consent.

### 15. Encrypted-DNS awareness — S
When a flow goes to a known DoH/DoT/DoQ resolver (port 853, or 443 to a short
list of public resolver addresses), say so on the row and in the Domains info
sheet: that app's names are not missing by accident. Pure classification,
mirrors how the screen already explains Private DNS and QUIC.

## Tier 3 — larger or needs device validation first

### 16. Raise the TUN MTU — M (measure first)
Already identified as the biggest untaken performance lever (~10× fewer
packets, writes and ACKs). Ship behind a setting, re-run `run-benchmark.sh`,
and validate PMTU behaviour on mobile links before making it a default.

### 17. TLS metadata — M
The ServerHello is cleartext in TLS 1.2 and 1.3 (the negotiated version sits in
`supported_versions`), so the negotiated version and ALPN can be recorded like
SNI. Flags apps still negotiating TLS 1.0/1.1. Requires reading the first
*downstream* record, which `TlsPeek` does not do today.

### 18. IPv6 on the Hosts screen — M (validate on device)
The sweep refuses IPv6 because a /64 cannot be walked. An echo to the
all-nodes multicast group `ff02::1` would be answered by every v6 host on the
link at once — if the kernel's unprivileged ping socket accepts a multicast
destination and delivers multiple replies. Unknown; prove it on the M10 before
designing anything.

### 19. Per-app data budgets — S/M
`Quota` handles one device-wide cap. A per-app allowance (warn, or auto-block
via the firewall, when an app exceeds N MB per cycle) reuses the cycle maths
and the cycle-scoped suppression already proven for the device budget.

### 20. Packaging — M
F-Droid metadata and a reproducible release build; Play would additionally need
the `VpnService` and `QUERY_ALL_PACKAGES` declarations README already names.

## Deliberately not suggested

Payload capture / PCAP, TLS interception, root mode and traceroute — see
DESIGN §9. A LAN port scanner for *other* hosts is also left out: the Hosts
screen is scoped to discovery of what answers, and widening the app's one
active source into probing other people's devices changes what the app is.
