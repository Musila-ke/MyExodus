# Domain Usage Metering — S0 Baseline Check

**Plan:** `app/plans/myexodus-domain-usage-metering-implementation-plan (1).md` §8 S0.
**Date:** 2026-09-20. **Verified against:** branch `dev`, commit `cc7a13b` working tree.
**Scope:** verify Baseline Contract P1–P10 against the actual `DnsSinkholeVpnService` and its
collaborators before any feature code is written (AC-1). Path note: the plan says
`docs/domainusage-baseline-check.md`; this project's docs folder is `app/docs/`, used here.

## Verdict summary

| # | Contract item | Result |
|---|---|---|
| P1 | Userspace TCP termination + per-flow protected relay | **PASS** |
| P2 | (a) see both directions (b) withhold client bytes (c) thread-safe `reset()` | **PARTIAL → fixed in S0** |
| P3 | UDP relay: protected channel per flow, idle timeout | **PASS with deviation** (flow keyed by destination, not 5-tuple) |
| P4 | DNS handler: parse UDP/53 from TUN, forward through protected socket, write replies | **PASS** |
| P5 | Main process; Room/SQLCipher instance obtainable | **PASS** |
| P6 | TUN IPv4-only; no `addAllowedApplication` | **FAIL as written** — TUN is dual-stack (deliberate, device-proven); no `addAllowedApplication` ✔. **Ruling requested (see below)** |
| P7 | Website rules with daily limits readable; `dayScopeCoversToday` callable | **PASS** |
| P8 | `allowBypass()` / `addDisallowedApplication()` usage recorded | **Recorded:** neither exists anywhere (`grep` clean) |
| P9 | Can write raw IPv4 packets (RST, ICMP) to the TUN fd | **PASS** for RST|ACK/ UDP; **ICMP builder does not exist** — S8 adds it (expected work) |
| P10 | Per-flow TCP seq/ack tracking for a valid synthesized RST|ACK | **PASS** |

Per plan §8 S0.2 the STOP list is P1, P3, P4, P5, P6, P7. P1/P4/P5/P7 pass, so the only
stop-triggering findings are **P6 (literal FAIL, beneficial deviation)** and **P3 (deviation,
fix scoped)**. P2's partial is S0's own work item (S0.3) and is done. No feature code was started
before the rulings. **Status: gate CLOSED 2026-09-20 — both rulings recorded in ADR #70
(`app/docs/MyExodus Architecture Decisions.md`): keep the dual-stack TUN; extend the UDP relay key
to the 5-tuple at S6. Step records from S1 on live in `app/docs/domainusage-implementation-log.md`.**

## Evidence per item

**P1 — PASS.** `DnsSinkholeVpnService.connectUpstream()` (`vpn/DnsSinkholeVpnService.kt:399-421`)
opens one `Socket` per flow, `protect()`s it, connects, and hands it to a per-flow
`relayLoop()` (`:462-499`). Device-side segments are handled by `handleTcp()` (`:346-397`).
This is the ADR #19 transparent relay, 3× ALL=PASS on the X6532 (ADR #19, gates 16/17/18).

**P2 — PARTIAL, fixed in S0.**
- (a) Both directions visible: client→server in `handleTcp()` (`:376-389`), server→client in
  `relayLoop()` (`:466-476`).
- (b) Client bytes can be withheld before forwarding: the forward is a single
  `flow.socket!!.outputStream.write(...)` (`:383`); a hook can return without reaching it.
- (c) **Was missing.** `sendRst()` (`:424-433`) and `teardown()` (`:501-505`) existed but were
  only reachable from the flow's own threads. Fixed per S0.3: `TcpFlow` is now an `inner`
  class with `reset()` (`:612-631`), which sends RST|ACK at the current sequence numbers and
  tears the flow down. Thread-safety: the TUN write is a single syscall (whole-packet
  datagram) and already happens concurrently from the pump/relay/DNS threads; `closed` is the
  atomic guard. The baseline has no per-flow owner queue, so a direct call is equivalent to
  the plan's suggested posting mechanism (documented in the method's KDoc).

**P3 — PASS with deviation.** `forwardUdp()` (`:318-333`) relays non-53 UDP through a
`protect()`ed `DatagramSocket`; `sweepLoop()` (`:511-526`) enforces the 300 s idle timeout
(`FLOW_IDLE_SWEEP_MS`, `:87`). **Deviation:** the flow key is
`"<dstIp>(<version>):<dstPort>"` (`:319`) — destination-keyed, not the 5-tuple, with a
single-requester-per-destination reply path (documented `ponytail:` ceiling in the baseline).
Consequence: two apps talking to the same server:port share one relay socket and the second
app's replies are misrouted — a pre-existing baseline limitation. The plan's UDP design
(`UdpAdmission` per 5-tuple, §4.2/§6.4) assumes true per-flow keying, and §3.3's modify list
sanctions "The Baseline UDP relay". **Ruling (user, 2026-09-20): extend the key to the full
5-tuple when the UDP tap is wired (S6)**, which also fixes the misrouting. Small, but it is a
behavioral change to a device-proven component, so it is called out here rather than slipped
in during S0.

**P4 — PASS.** `handleDns()` (`:275-285`) parses UDP/53 (any destination IP, including the
advertised TUN-subnet resolvers), answers blocked qnames locally with NXDOMAIN, else
`forwardUpstream()` (`:287-295`) sends via the protected `dnsSocket4/6`; `dnsReceiveLoop()`
(`:297-315`) re-addresses and writes replies to the TUN, keyed by transaction id.
Note for S3/S6: the new `DnsShaper` decision must be reconciled with this existing
blocklist-NXDOMAIN decision point — one query must not be decided twice (S3.5 wiring).

**P5 — PASS.** The service is declared without `android:process`
(`AndroidManifest.xml`, `DnsSinkholeVpnService` block) → main process. It is
`@AndroidEntryPoint` and already injects `RulesSnapshotPublisher`
(`DnsSinkholeVpnService.kt:69-72`); `DatabaseModule.provideAppRulesStoreFactory()` provides
the singleton `AppRulesStoreFactory`, whose `open()` returns the SQLCipher-encrypted Room
database (`RulesStoreEncryption.newDriver()`, `AppRulesStoreFactory.build()`). The engine can
inject the same factory. Current schema version: **9** (`AppRulesStore.kt:56`) → the feature's
migration is **9 → 10** (plus the 10 → 9 downgrade, below).

**P6 — FAIL as written.** The builder adds `fd00:14:14::1/128` and a `::/0` route
(`DnsSinkholeVpnService.kt:130-133`) — the TUN is **dual-stack**, not IPv4-only. No
`addAllowedApplication` is used ✔. The dual stack is deliberate and device-proven:
ADR #19 records "TUN 192.0.2.1 + fd00:14:14::1, MTU 1400" as the accepted sinkhole
architecture, with `ipv6_sinkhole` as one of the seven spike gates that passed 3× on-device.
Plan conflicts this deviation creates:
- §5 D-15 ("The TUN itself stays IPv4-only") and §8 S8.3 ("Do not add IPv6 addresses or
  routes") describe/assume a state that does not exist; S8.3's literal instruction (do not
  *add*) is satisfiable, but nothing in the plan authorizes *removing* the existing IPv6
  config — and removing it would reroute IPv6 device traffic outside the TUN (S0.2 forbids
  rerouting, and it would silently create a much larger unmetered hole than the AAAA
  suppression it was meant to avoid).
- §16 item 5's NAT64 reasoning ("a metered site with no IPv4 path may become unreachable
  while its rule is enabled") still holds as a *cost of the AAAA-NODATA measure*, because
  that measure is DNS-level and host-specific; but the plan's stated *benefit* of the
  IPv4-only TUN (IPv6 traffic is not visible) does not apply: with a dual-stack TUN, IPv6
  traffic is in the packet path and is attributable by the same SNI/DNS-fallback logic
  (16-byte addresses are already the model in the plan's own `DnsObservationStore`).
**Ruling (user, 2026-09-20): keep the dual-stack TUN exactly as shipped.** Implement FR-8's
shaping table unchanged (it remains binding and its cost is narrow — host-specific,
rule-enabled only). An ADR records that D-15's "IPv4-only" sentence is inapplicable to this
baseline, which is strictly *more* capable (metering and enforcement cover IPv6 flows;
I-13's literal-IPv6 check passes by attribution rather than by AAAA suppression). S8.3's
"do not add IPv6" stands as "add nothing new"; the existing IPv6 configuration is untouched
(stripping it would reroute device IPv6 traffic outside the TUN — a larger unmetered hole,
and contrary to the delivered ADR #19 architecture).

**P7 — PASS.** `UrlPattern` carries `dailyLimitMs`, `activeDaysOfWeek`, `timezone`,
`blockExpiresAt` (`blocking/UrlPattern.kt:36-48`) and is published in
`RulesSnapshot.urlRules` only for enabled, unexpired WEBSITE rules
(`blocking/RulesSnapshot.kt:33-59`). `dayScopeCoversToday(activeDaysOfWeek, timezone)`
exists as an `internal fun` in the same module (`blocking/BlockDecisionEngine.kt:30`).
Signature note: the plan's §8 S2.6 phrase "`dayScopeCoversToday(rule)`" adapts to this
two-string signature at the adapter. Also confirmed: `RuleEditor` currently gates the limit
switch to app targets (`limitEnabled = isApp && limitEnabled`, `ui/rules/RuleEditor.kt:158,
188`) — un-gating it with validation is S9's job, as planned.

**P8 — recorded.** `grep -rn "allowBypass|addDisallowedApplication|addAllowedApplication"`
over `app/src/main/java` returns nothing. S8 adds `addDisallowedApplication(APPLICATION_ID)`
(INV-9/S-10); there is no `allowBypass()` to remove (S8.3's "remove" step is a no-op, kept as
a regression guard). **Closed at S8** — the line is in the builder and the exclusion is proven
on-device; the harness rework it forced is in `domainusage-implementation-log.md` (S8, deviation 6).

**P9 — PASS (RST path already exists).** `sendRst()` (`:424-433`) builds and writes a full
IPv4/IPv6 TCP RST|ACK to the TUN fd via `VpnPacket.buildTcpSegment()`
(`vpn/VpnPacket.kt:183-231`) — complete with IPv6/IPv4 pseudo-header checksums
(`:223-229`) and the on-device-verified IPv4 header-checksum requirement. `writeTun()`
(`:259-264`) is the fd writer. **Missing:** an ICMP Port-Unreachable builder (RFC 792) for
§6.10 — does not exist; S8 implements it (expected implementation work per S0.4, not a stop).
**Closed at S8** — `VpnPacket.buildIcmpPortUnreachable` (v4 + ICMPv6), wired at both drop sites.

**P10 — PASS.** `TcpFlow` tracks `ackToDevice` and `seqToDevice` (`:594-610`), maintained on
every relayed byte on both paths (`:383-384`, `:468-469`, `:392`), and `sendRst()` builds its
segment from exactly those values at call time. The synthesized RST|ACK is therefore valid
for the device's TCP state machine (this is the mechanism the existing connect-failure RST
already relies on).

## Additional facts S0 was asked to resolve (§16 items)

- **Item 2 (rule schema): resolved.** `RestrictionRule` (`data/db/entity/`) → `UrlPattern`
  (`ruleId: String`) → `RulesSnapshot.urlRules`; `isEnabled`/`isExpiredAt` filter is applied
  at snapshot build. `DomainRule.revision` (D-32) has no existing counterpart — the provider
  tracks it in memory, as the plan specifies.
- **Item 3 (foreground service type): resolved.** The VPN service is **not** a foreground
  service and declares no `foregroundServiceType` (manifest block). The plan changes nothing
  here; S7's VPN-stopped notification is a plain channel notification, consistent with the
  baseline's existing revoke notification (`postRevokeNotification()`, `:574-592`, channel
  `vpn_spike` → the feature's `domain_usage_status` is a separate channel).
- **Item 7 (help-resource domains): resolved.** `HELP_RESOURCE_DOMAINS` =
  `988lifeline.org`, `findahelpline.com`, `befrienderskenya.org`, `crisistextline.org`,
  `redcross.or.ke`, `samhsa.gov` (hosts of every URL in `assets/crisis_resources.json`,
  rendered by the Recovery Resource UI; no other outbound help links exist in the UI code).
- **Item 8 (SDK matrix): resolved.** `minSdk 26`, `targetSdk 36`, `compileSdk 37`
  (`app/build.gradle.kts:12-21`). Android 17 (API 37) coverage is therefore a
  *test-target* requirement only (NFR-7), not a min support change.
- **Item 12 (baseline DNS truncation): resolved.** The baseline handles UDP/53 only; TCP/53
  rides the transparent relay byte-for-byte with no parsing (class doc, `:64-65`), so a
  TC-flagged UDP reply can be retried over TCP and stays connectable — the feature cannot see
  or shape that path (documented DNS-bypass limitation, unchanged).
- **Item 18 (Room downgrade + SQLCipher): resolved — mechanism confirmed.** Room **3.0.3**
  (`androidx.room3`, versions catalog) with SQLCipher 4.18.0 via
  `AppRulesStoreFactory.build()` → `.addMigrations(*ALL_MIGRATIONS)`, conditional
  `.setDriver(encryption.newDriver())`, schemas exported to `app/schemas/`. Verified in
  room3-runtime sources: `findMigrationPath` handles downgrades (`migrateUp = end > start`)
  and `isMigrationRequired` returns true on downgrade unless destructive-downgrade is
  allowed — so without a registered `Migration(10, 9)`, a rollback APK would *error* on open,
  and with it registered the migration runs and the result is schema-validated (which
  `DROP TABLE IF EXISTS rule_usage_daily` satisfies — every other table is untouched). No
  SQLCipher-specific handling is needed: the driver is builder-level and migrations run
  through it. D-33 holds as specified.
- **Item 1 (contract asserted from records):** this document is that verification.

## Integration hazards recorded for later steps (not blockers)

1. **Two DNS decision sites.** The existing `DomainBlocklist` NXDOMAIN path and the new
   shaper must compose into one decision before `onDnsQuery` wiring (S3.5/S6). The plan's
   `Respond` must not double-answer a query the blocklist already answered, and vice versa.
2. **Room is `androidx.room3`** (Room 3, not 2.x) — migration + DAO code must use the
   `room3` API surface (`androidx.room3.migration.Migration` with suspend `migrate(connection)`).
3. **Two Private DNS mechanisms** will coexist: the baseline's settings-string detection at
   start (`detectPrivateDns()`, `:204-210`, feeds `EngineHealth` → DEGRADED) and the plan's
   per-network `PrivateDnsMonitor` (D-34, S7.5). S7 decides how they report; the plan forbids
   touching start/restart logic, so the existing mechanism stays.
4. **UDP flow keying** (P3 above) must be extended at S6 wiring.
5. **The baseline TUN carries IPv6 end-to-end.** Any feature code that assumes IPv4-only
   inputs (e.g. 4-byte address arrays) must instead pass the existing 16-byte/`isV6` forms
   (`VpnPacket.IpHeader`). The plan's 16-byte `DnsObservationStore` keys already match this.

## S0 checklist (plan §8 S0.1–S0.5)

- [x] S0.1 report written with one line per P1–P10 (this file).
- [x] S0.2 stop list evaluated — P6 deviation reported for ruling; no S1+ work started.
- [x] S0.3 P2 partial (`reset()`) implemented inside the existing TCP endpoint.
- [x] S0.4 P9/P10 scope confirmed: RST path exists; ICMP builder is the only new raw-packet
      work, scheduled into S8 as the plan allows.
- [x] S0.5 P8 recorded (neither `allowBypass()` nor exclusions currently used).
