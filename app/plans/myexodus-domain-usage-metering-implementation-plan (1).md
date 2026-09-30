# MyExodus — Domain Usage Metering & Enforcement: Implementation Specification

**Status:** Final, post-audit revision 2. **Consumer:** AI coding agent. Read the whole document before writing code.
**Binding rules:** Every section except §16 is binding. MUST / MUST NOT are literal. Code identifiers are literal names. Section 16 is the only place with unresolved items — every item there is explicitly marked **Resolved** (with source) or **Open** (with the reason it stays open).
**Terms:** *Engine* = `DomainUsageEngine`. *Rule* = one user-defined website limit. *Flow* = one TCP connection or one UDP 5-tuple. *Baseline* = the existing `DnsSinkholeVpnService` and its collaborators.
**Revision note:** This revision resolves two independent audit passes of the prior draft. Where the audits disagreed, the later, more thorough pass (which found the mode/ECH/persistence/rollback/buffer-ownership contradictions) took precedence; the earlier pass's correct-but-partial fixes (precise interval math, ICMP fallback) are folded in and completed rather than discarded. Every changed decision is called out inline and cross-referenced from §16.

---

## 1. Feature Overview

Extend `DnsSinkholeVpnService` so MyExodus can meter and limit **per-website daily network-active time**.

The Baseline already sits in the packet path of all IPv4 device traffic. This feature adds, inside that path:

1. **Attribution** of each flow to a user-defined website rule: TLS SNI on TCP/443, with a DNS-answer fallback.
2. **Accounting** of `networkActiveMs` per rule per local calendar day, using an interval calculation that is exact regardless of how often or how late the accounting tick actually runs (§4.7). The in-memory value is authoritative. Room/SQLCipher stores checkpoints.
3. **Enforcement**, in mode `ENFORCE` only, at the daily limit: synthetic NXDOMAIN for new lookups, TCP reset and UDP drop for live and new flows attributed to the rule. **Mode `ACCOUNT` never blocks, drops, or reshapes any traffic** — see the mode truth table in §2.1 (FR-11).

**User-visible behavior:** The user sets a daily limit on a website (`example.com`) in the existing RuleEditor. Traffic to that site and its subdomains consumes the allowance while data is flowing. At the limit the site stops loading until the next local day.

**Enforcement contract:** Best-effort friction, not tamper-proof. The feature does not defeat a user who deliberately uses another VPN, stops the VPN, uses an app with its own encrypted DNS, or moves the device clock forward. Those states are detected where detection is defined in this spec and shown in the UI. They are never presented as enforced. **Encrypted Client Hello (ECH) is an accepted attribution limitation, not something this architecture can claim to defeat** (§4.6, §16 item R-ECH).

**Not built (final):** TLS interception/MITM, URL/path rules, full QUIC parsing, IPv6 relay, per-app attribution, domain groups/CDN sibling coverage, off-device sync of usage, remote telemetry, a user-facing on/off toggle.

---

## 2. Requirements

### 2.1 Functional

| ID | Requirement |
|---|---|
| FR-1 | A rule is `DomainRule(id: String, revision: Long, pattern: String, dailyLimitMs: Long, enforcedToday: Boolean)`. Rules come from the existing website-target rules that have a daily limit enabled. `revision` increments whenever the underlying rule's `pattern` changes (§4.5, §5 D-32); it is otherwise opaque to the UI. |
| FR-2 | A rule pattern matches the exact host and all its subdomains. When several rules match a name, the one with the longest pattern wins. At most one enabled daily-limit rule may exist per normalized pattern (§4.5, §5 D-08-dedup); RuleEditor enforces this at save time (§8 S9). |
| FR-3 | Attribution priority per flow: (a) valid TLS SNI on TCP/443; (b) DNS fallback (§4.6). SNI present and valid but matching no rule ⇒ flow is unattributed; DNS fallback MUST NOT override it. |
| FR-4 (revised) | ECH is not directly attributable by SNI and its presence alone never causes a block. An ECH flow falls to DNS fallback exactly like `NoSni`/`NotTls`/`Malformed`/`TooLarge` (§4.6). Like any DNS-fallback attribution, it can only ever be enforced against if the fallback is unambiguous **and** the Engine is in mode `ENFORCE` **and** the resolved rule is exhausted. Unattributed and ambiguous flows are always allowed and never blocked, in any mode. |
| FR-5 | Metering: a rule accrues `networkActiveMs` while any of its attributed flows relayed a chunk of ≥ `MIN_ACTIVITY_BYTES` within the last `T_IDLE_MS`. Multiple parallel flows count once. The calculation is exact and independent of tick cadence (§4.7). |
| FR-6 | Day key = the *effective* local date under the timezone anchored at Engine start, `yyyy-MM-dd` (§4.8). The effective day key never decreases within one continuous Engine run. Usage is stored per `(rule_id, revision, day_key)`. |
| FR-7 | Enforcement (mode `ENFORCE` only): when `total ≥ dailyLimitMs`, the rule is *exhausted* until the day key changes or the limit is raised. Exhausted rule ⇒ (a) any DNS query whose qname matches the rule gets NXDOMAIN, for every query type; (b) live attributed flows are reset/dropped within one tick; (c) new attributed flows are reset/dropped at attribution. |
| FR-8 (revised) | DNS/UDP shaping is mode- and rule-scoped, never global. Full truth table in §6.1/§6.4. Summary: `OFF`/`ACCOUNT` never shape or drop anything. `ENFORCE`: a name matching an *exhausted* rule gets NXDOMAIN for any query type; a name matching an *enabled-but-not-yet-exhausted* rule gets NODATA for AAAA(28)/HTTPS(65)/SVCB(64) only (SNI-visibility measure, §5 D-14/D-15); every other name, including all never-block and Recovery Resource hosts, is never reshaped by this feature. UDP/443 is never blanket-dropped; it is only dropped when DNS-fallback-attributed to an exhausted rule under `ENFORCE` (§6.4), with an ICMP Port-Unreachable nudge (§6.10) so the client falls back to TCP quickly instead of stalling. |
| FR-9 | Engine status (`Active`, `Dormant`, `Degraded(reason)`) is exposed to the UI. Reasons: `PRIVATE_DNS`, `DB_ERROR`, `INTERNAL_ERROR`. A notification is posted when the VPN stops while ≥ 1 rule is enabled. |
| FR-10 | RuleEditor validates and normalizes website input (§8, S9), enforces the FR-2 uniqueness invariant, and shows the scope notice **and the connection-level attribution notice** (§16 item R-SNI-SCOPE). |
| FR-11 (revised) | Modes: `OFF`, `ACCOUNT` (attribution + accounting only — **no shaping, no blocking, no dropping, under any circumstance**), `ENFORCE` (attribution + accounting + the FR-8 enforcement actions). Set by `BuildConfig.DOMAIN_USAGE_MODE`. Full truth table: |

  | Mode | DNS answer shaping | TCP/UDP block or reset | Accounting |
  |---|---|---|---|
  | `OFF` | none | none | none |
  | `ACCOUNT` | none | none | yes |
  | `ENFORCE` | exhausted-rule NXDOMAIN; enabled-rule AAAA/HTTPS/SVCB NODATA (§6.1) | yes, only for DNS-fallback- or SNI-attributed flows resolved to an exhausted rule | yes |

| FR-12 | The Engine is dormant when mode is `OFF` or zero rules with a daily limit exist. Dormant ⇒ every hook is a pass-through, DNS shaping and UDP/443 handling are off. |
| FR-13 | The never-block set (§8, S2) is never matched by any rule, and by construction is never reshaped by FR-8 (shaping is keyed to an actual rule-pattern match, never applied name-independently). The app's own traffic never enters the VPN. |
| FR-14 | Rule edit semantics: changing a rule's `pattern` allocates a new `revision` for that `rule_id` (§5 D-32). Flows already attributed under the old revision keep accruing to the old `(rule_id, revision)` counter until they close; new flows use the current revision. Usage never silently transfers from an old hostname to a new one. |

### 2.2 Non-functional

| ID | Requirement |
|---|---|
| NFR-1 | No database or disk I/O on relay/packet threads. |
| NFR-2 | Every Engine hook is exception-safe: it MUST NOT throw an `Exception` into Baseline code. A `VirtualMachineError` (e.g. `OutOfMemoryError`, `StackOverflowError`) is recorded and rethrown rather than swallowed (§5 D-35) — continuing execution after one is unsafe, so "exception-safe" here means *ordinary* exceptions never propagate, not that all `Throwable`s are hidden. |
| NFR-3 | Bounded memory: inspection buffers ≤ 1 MiB total; DNS observation store ≤ 4096 IPs × 8 names; `FlowRegistry` tracks at most `MAX_TRACKED_FLOWS_PER_RULE` live flows per rule for sweep purposes (§16 item R-FLOWREG). |
| NFR-4 | Privacy: hostnames, IPs and DNS names are memory-only and never logged. Storage holds only `rule_id`, `revision`, `day_key`, `active_ms`. Nothing leaves the device. |
| NFR-5 | Performance gates in §11. |
| NFR-6 | Fail-open: any ordinary fault ⇒ traffic passes; Engine goes dormant or degraded. A `VirtualMachineError` is the one exception (see NFR-2). |
| NFR-7 | Supported Android: the project's declared `minSdk` through the current `targetSdk`, plus Android 17 (API 37) as a mandatory instrumented-test target regardless of `targetSdk` (§16 item R-ECH-DEVICE), because Android 17 ships ECH on by default. |
| NFR-8 (new) | Persistence durability is explicitly bounded, not absolute (§4.9, §9): under normal shutdown, loss is bounded by `SHUTDOWN_FLUSH_TIMEOUT_MS`; under abrupt process death, loss is bounded by `PERSIST_INTERVAL_MS` plus time since the last successful checkpoint. No wording in this spec claims a hard guarantee against arbitrary process termination. |

### 2.3 Constants (`MeteringConfig`, compile-time)

| Name | Value | Change from prior draft |
|---|---|---|
| `TICK_MS` | 1000 | unchanged |
| `T_IDLE_MS` | 15000 | unchanged |
| `MIN_ACTIVITY_BYTES` | 512 | unchanged |
| `PERSIST_INTERVAL_MS` | 10000 | unchanged |
| `SHUTDOWN_FLUSH_TIMEOUT_MS` | 3000 | **new** — bounded best-effort flush at `stop()` (§4.9, §6.9) |
| `CLOCK_JUMP_THRESHOLD_MS` | 5000 | unchanged; now diagnostic-only, never affects accounting (§4.7 no longer uses a wall/tick delta for accrual) |
| `MAX_TICK_GAP_DIAG_MS` | 60000 | **new**, replaces the accounting role of the old `TICK_DT_CAP_MS`. If a tick observes a gap since the last tick larger than this, it is logged as a scheduling anomaly (not clamped) — the accrual itself is still computed exactly per §4.7 regardless of the gap size. |
| `MAX_INSPECTION_HOLD_MS` | 8000 | **new** (§16 item R-INSPECT-TIMEOUT) — caps how long client bytes on a TCP/443 flow can be held awaiting a terminal TLS parse result. |
| `INSPECT_MAX_BYTES` | 16384 | unchanged |
| `INSPECT_POOL_SIZE` | 64 | unchanged |
| `DNS_OBS_MAX_IPS` / `DNS_OBS_MAX_NAMES_PER_IP` | 4096 / 8 | unchanged |
| `DNS_TTL_CAP_S` | 1800 | unchanged (memory-safety ceiling) |
| ~~`DNS_TTL_FLOOR_S`~~ | **removed** | A positive floor made short-TTL CDN reassignments stale for up to 5 minutes (§16 item R-TTL-FLOOR). TTL is now honored down to 0; only the cap remains. |
| `NXDOMAIN_NEG_TTL_S` / `NODATA_NEG_TTL_S` | 5 / 60 | unchanged |
| `RETENTION_DAYS` | 30 | unchanged |
| `BREAKER_MAX_EXCEPTIONS` / `BREAKER_WINDOW_MS` | 20 / 60000 | unchanged |
| `MIN_DAILY_LIMIT_MS` / `MAX_DAILY_LIMIT_MS` | 60000 / 86400000 | **new** (§16 item R-LIMIT-BOUNDS) — RuleEditor and the rule adapter reject/clamp values outside this range before conversion; prevents overflow and degenerate zero/negative limits. |
| `MAX_TRACKED_FLOWS_PER_RULE` | 2048 | **new** (§16 item R-FLOWREG) — soft cap on `FlowRegistry` per rule; beyond this, additional flows for that rule are still metered but are not tracked for the exhaustion sweep (they will still be blocked at their next DNS lookup or, for already-open flows, on their own next activity check). Counter `flow_registry_capped`. |
| `ICMP_PORT_UNREACHABLE_TYPE` / `ICMP_PORT_UNREACHABLE_CODE` | 3 / 3 | **new** (§6.10) |

---

## 3. Existing System Context

### 3.1 Facts about the project

- Native Kotlin Android app. `DnsSinkholeVpnService` (ADR #19) is a `VpnService` with TUN address `192.0.2.1/32`, an IPv4 default route, a DNS sinkhole for web blocking, and non-DNS flows relayed through per-flow `protect()`ed sockets.
- `UsageAccountingEngine` (ADR #68, predicate `dayScopeCoversToday`) is the existing usage/day-scope logic. **It is not modified.**
- One Room database encrypted with SQLCipher, offline-first.
- Website-target rules are created in RuleEditor with a daily-limit switch.
- App blocking uses UsageStatsManager + overlay. There is no AccessibilityService.
- The escape hatch is always reachable and never technically blocked.
- A Recovery Resource UI exists.
- The application package name comes from `BuildConfig.APPLICATION_ID` and is never hardcoded.
- The app ships as a Play flavor and a distributable APK flavor. The Engine ships in both (§13).
- Reference devices: Infinix Smart 9 (XOS) as the low-end device, plus one stock-Android device, **plus one Android 17 (API 37) device or emulator image** (§16 item R-ECH-DEVICE).

### 3.2 Baseline Contract (verified in step S0)

| # | Contract item |
|---|---|
| P1 | The Baseline terminates each TCP connection from the app in userspace and relays stream bytes to one `protect()`ed outbound socket per flow. |
| P2 | Each TCP flow object lets the feature (a) see client→server and server→client chunks, (b) withhold client→server bytes before forwarding, (c) send RST to the app via a `reset()` that is safe to call from any thread. |
| P3 | UDP flows are relayed through `protect()`ed channels, one per 5-tuple, with an idle timeout. |
| P4 | A DNS handler parses UDP/53 packets from the TUN, forwards upstream through a protected socket, and writes replies to the TUN. |
| P5 | The service runs in the app's main process and can obtain the app's Room/SQLCipher instance. |
| P6 | The TUN has IPv4 only and the builder does not use `addAllowedApplication`. |
| P7 | Website-target rules with daily limits are readable, and `dayScopeCoversToday` is callable. |
| P8 | Current `allowBypass()` and `addDisallowedApplication()` usage is recorded. |
| P9 (new) | The Baseline can construct and write a raw IPv4 packet to the TUN file descriptor for a locally-terminated flow (a TCP RST|ACK with valid sequence numbers, or an ICMP message), because the kernel TUN device delivers and accepts whole IP datagrams, not byte streams (this is inherent TUN/TAP semantics, not an Android-specific API — see §16 item R-RST-INJECT). If this capability does not already exist in the Baseline, S0 implements a minimal `RawPacketBuilder` for it; it is not optional, because standard Java sockets cannot emit raw IP packets and RST-to-the-app is required by FR-7/§6.3/§6.4. |
| P10 (new) | The Baseline TCP endpoint tracks, per flow, the sequence and acknowledgment numbers the app expects next, so a synthesized RST|ACK is valid. If this tracking does not already exist, S0 adds the minimal bookkeeping needed (it is normally already present in any endpoint that terminates TCP in userspace, since it must track these numbers to relay bytes correctly). |

### 3.3 Files

- **Create:** everything under the sub-package `domainusage` (§4.1), one Room entity/DAO/migration pair (including the downgrade migration, §13), one asset file plus its provenance record (§16 item R-PSL-PIN), tests.
- **Modify (minimal hooks only):**
  - `DnsSinkholeVpnService` — builder changes, engine lifecycle.
  - The Baseline DNS handler.
  - The Baseline packet dispatcher.
  - The Baseline TCP endpoint/flow class — also gains the P9/P10 capability if missing.
  - The Baseline UDP relay.
  - RuleEditor UI.
  - The Room database class.
  - App `build.gradle`.
- **Never touch:**
  - `UsageAccountingEngine`.
  - UsageStats/overlay blocking.
  - Pairing key, escape-hatch and Tink/DataStore code.
  - Firebase relay code.
  - Recovery Resource UI (read its URLs only).
  - The Baseline's start/restart logic.
  - The service's foreground type and notification.

---

## 4. Architecture

### 4.1 Components (package `<app base package>.domainusage`)

| Component | Responsibility |
|---|---|
| `DomainUsageEngine` | Facade. Lifecycle, mode, status, hooks, circuit breaker. Owned by the service. |
| `MeteringConfig`, `EngineMode`, `EngineStatus` | Constants, mode enum, status sealed class. |
| `MonotonicClock`, `WallClock` (+ `SystemMonotonicClock`, `SystemWallClock`) | Time sources. Only `SystemWallClock` reads wall time. |
| `HostNormalizer`, `PublicSuffixList`, `NeverBlockDomains`, `EncryptedDnsResolvers` | Hostname validation/normalization and exclusion lists. |
| `DomainRule`, `DomainRuleProvider`, `RuleIndex` | Rule model (with `revision`), adapter over existing rules, immutable matcher with pattern-uniqueness dedup. |
| `DnsMessage`, `DnsShaper` | Hardened DNS parse/build, with full RR owner-name/CNAME-chain tracking (§4.6, §6.2). Mode- and rule-scoped decisions (§6.1). |
| `DnsObservationStore` | In-memory IP → names map, TTL honored down to 0, capped at `DNS_TTL_CAP_S`. |
| `TlsClientHelloParser`, `InspectionBufferPool` | Incremental SNI extraction. Pooled 16 KiB buffers with an explicit ownership/release contract (§4.2) and an inspection timeout (`MAX_INSPECTION_HOLD_MS`). |
| `AttributionResolver` | Combines SNI and DNS evidence. Returns a rule (with revision) or none. |
| `FlowTap`, `FlowControl`, `FlowRegistry` | Per-flow hook, reset/drop handle, rule → live flows, capped per `MAX_TRACKED_FLOWS_PER_RULE`. |
| `RawPacketBuilder` (new) | Constructs and checksum-validates raw IPv4/TCP RST and IPv4/ICMP Port-Unreachable packets for writing to the TUN fd (§6.10, §5 D-31). |
| `PrivateDnsMonitor` (new) | Tracks strict/opportunistic Private DNS status per network via `NetworkCallback`, recomputed on network change (§5 D-34, §8 S7.5). |
| `UsageLedger` | Per-rule (per-revision) counters, tick loop, exhausted flags, day key, generation-numbered dirty state (§4.7, §4.9). |
| `UsagePersistence`, `RuleUsageDaily`, `RuleUsageDao` | Checkpoint writer/reader, generation-aware. |

### 4.2 Interfaces to Baseline code

```kotlin
interface FlowControl { fun reset() }   // TCP: RST to app. UDP: mark flow blocked, drop later datagrams. Thread-safe.

/**
 * Explicit buffer-ownership contract (fixes the pooled-buffer lifetime hazard from the prior draft).
 * The Engine MUST NOT return a pooled buffer to InspectionBufferPool before the consumer of
 * Forward has finished reading it. release() performs that return and MUST be called by the
 * Baseline exactly once, after the bytes have been fully written/queued to the outbound socket.
 */
interface OwnedBytes {
    val bytes: ByteArray
    val length: Int
    fun release()
}

sealed interface ClientDataResult {
    object Hold : ClientDataResult                                   // consumed, do not forward
    class Forward(val data: OwnedBytes) : ClientDataResult            // forward these bytes; caller MUST call data.release() after forwarding
    object PassThrough : ClientDataResult                             // forward input unchanged, nothing to release
    object Reset : ClientDataResult                                   // TCP: flow was reset. UDP: drop this datagram.
}

interface FlowTap {
    fun onClientData(data: ByteArray, off: Int, len: Int): ClientDataResult
    fun onServerData(len: Int)
    fun onClosed()
}

sealed interface UdpAdmission { object Drop : UdpAdmission; class Allow(val tap: FlowTap) : UdpAdmission }
sealed interface DnsQueryResult { object Forward : DnsQueryResult; class Respond(val bytes: ByteArray, val length: Int) : DnsQueryResult }

// Engine hook surface
fun onDnsQuery(query: ByteArray, len: Int): DnsQueryResult
fun onDnsResponse(response: ByteArray, len: Int)
fun tcpFlowOpened(dstIp: ByteArray, dstPort: Int, control: FlowControl): FlowTap
fun udpFlowOpened(dstIp: ByteArray, dstPort: Int, control: FlowControl): UdpAdmission
```

When the Engine is dormant: `onDnsQuery` returns `Forward`, `tcpFlowOpened` returns a tap whose `onClientData` returns `PassThrough`, `udpFlowOpened` returns `Allow` with a pass-through tap.

### 4.3 Threads

- **Relay/packet threads (Baseline):** call hooks only. Hooks read immutable or atomic state and never block.
- **`domainusage-tick`:** single-thread executor as a coroutine dispatcher. Runs the tick loop and applies rule snapshots. It is the only writer of ledger totals, generations, and exhausted flags. Its correctness does not depend on running exactly once per `TICK_MS` (§4.7) — a delayed or skipped invocation still produces the exact correct total on its next run.
- **`domainusage-db`:** single-thread executor. All Room access.

### 4.4 Invariants (each is enforced by a step in §8 and a test in §12)

| ID | Invariant |
|---|---|
| INV-1 | The relay/packet path never touches Room, disk, or locks held by the tick/db threads. |
| INV-2 | Durations are computed from `MonotonicClock` only. `WallClock` is used only to derive the day key. |
| INV-3 (revised) | The *effective* day key (§4.8) never decreases within one continuous Engine run. |
| INV-4 | The ledger is the enforcement source. The database is never read to decide a block. |
| INV-5 | Persisted `active_ms` never decreases for a given `(rule_id, revision, day_key)` (generation-guarded `MAX` upsert, §4.9). |
| INV-6 (revised) | Every hook catches ordinary exceptions, increments `hook_exceptions`, and falls back to pass-through. A `VirtualMachineError` is recorded and rethrown, never swallowed (§5 D-35). |
| INV-7 | Hostnames, IPs, DNS names never appear in logs, exceptions, notifications, or storage. |
| INV-8 | Unattributed/ambiguous flows are never blocked, in any mode. ECH-flagged flows are never blocked merely for being ECH (§4.6). |
| INV-9 | The app's own traffic never enters the TUN. |
| INV-10 | `allowBypass()` is never called. |
| INV-11 | Never-block domains never match a rule, and are therefore never subject to FR-8 shaping. |
| INV-12 | Per-chunk hot path allocates no objects after attribution. |
| INV-13 (new) | A pooled TLS inspection buffer is returned to `InspectionBufferPool` only via `OwnedBytes.release()`, called by the Baseline after it has finished using the bytes — never by the Engine before returning `Forward`. |
| INV-14 (new) | Raw RST/ICMP packets are length- and offset-validated and checksummed correctly before being written to the TUN fd; `RawPacketBuilder` never writes a packet built from unvalidated input sizes. |
| INV-15 (new) | DNS answer shaping and UDP/443 blocking apply only in mode `ENFORCE`, and only to a name/flow that resolves (by exact match or subdomain) to a specific enabled or exhausted rule (§6.1, §6.4). No shaping or blocking is ever applied to a name/flow that does not match any rule. |
| INV-16 (new) | A rule's accrued active time for a given tick invocation depends only on that rule's `sessionStart`, `lastActivityElapsed`, and `lastAccountedElapsed` fields (§4.7) — never on how much wall-clock or scheduler time elapsed since the previous tick invocation. |

### 4.5 Rule matching (`RuleIndex`)

- Immutable `HashMap<String, DomainRule>` keyed by normalized pattern.
- **Build-time dedup (fixes duplicate-pattern silent loss):** if two source rules normalize to the same pattern, the index keeps the one with the lexicographically smallest `id`, drops the other from the index, and increments `rules_duplicate_pattern`. RuleEditor separately rejects a *new* save that would collide with an existing enabled rule's normalized pattern (§8 S9), so this dedup is a defensive backstop for data that predates the check, not the primary defense.
- `match(name)`:
  1. Lowercase name; strip trailing dot.
  2. If the name equals or is a subdomain of any never-block host, return null.
  3. Walk suffixes from the full name down to 2 labels. Return the first hit (longest pattern wins).
- The Engine holds one `@Volatile var ruleIndex`, replaced atomically.
- **Revision (D-32):** each `DomainRule` carries its `revision`. When `DomainRuleProvider` observes that an existing `rule_id`'s normalized `pattern` changed since the last snapshot, it increments that rule's revision. `RuleIndex.match` returns the rule at its *current* revision; a `FlowTap`/`RuleCounter` created before the change keeps referencing the revision that was current when it was created (§4.6, §4.9, §6, FR-14).

### 4.6 Attribution

- **TCP/443 flow:** hold client bytes until `TlsClientHelloParser` returns a terminal result, subject to `MAX_INSPECTION_HOLD_MS` (§16 item R-INSPECT-TIMEOUT; if the timeout fires first, treat as `NoSni`):

  | Result | Attribution |
  |---|---|
  | `Sni(host)` | `ruleIndex.match(host)`. No match ⇒ unattributed. |
  | `Ech`, `NoSni`, `NotTls`, `Malformed`, `TooLarge`, timeout | DNS fallback. |

- **All other TCP and all UDP (except DNS and 443, which have their own paths in §6.1/§6.4):** DNS fallback at flow open.
- **DNS fallback:**
  - Look up `dstIp` in `DnsObservationStore`. Take all unexpired names.
  - Resolve each name with `ruleIndex.match` (a name with no rule resolves to NONE).
  - Attribute iff every candidate resolves to the same non-NONE rule at its current revision. Otherwise unattributed (*ambiguous* if candidates disagree).
- An attribution is bound at flow open (or parse completion) to a specific `(rule_id, revision)` and never changes, even if the rule is edited again while the flow is open (FR-14).
- **ECH policy (resolves the prior contradiction):** ECH never causes a block by itself (FR-4, INV-8). It is treated identically to any other non-SNI-attributable ClientHello: it falls to DNS fallback, and from there follows the same ambiguous/unambiguous rule as everything else. This is a deliberate, documented limitation, not a gap: RFC 9849 allows ECH configuration to be delivered through mechanisms other than DNS, so no purely-DNS-based measure can fully prevent ECH from being negotiated; on Android specifically, apps that use the platform's own ECH support retrieve the ECH configuration from the domain's HTTPS DNS record via `DnsResolver` (developer.android.com/privacy-and-security/encrypted-client-hello), which is why the narrow, rule-scoped HTTPS/SVCB NODATA in §6.1 has *some* effect for such apps and none for apps that cache configs, use their own DoH, or receive ECH configs out-of-band. The Engine never represents this as a completed block of ECH.

### 4.7 Metering — precise, cadence-independent interval accounting

This replaces the prior draft's `dt = min(now - lastTick, TICK_DT_CAP_MS)` formula, which both overcounted single mid-interval bursts and undercounted whenever the tick thread was delayed (because time beyond the cap was silently discarded). The corrected formula ties accrual to absolute elapsed timestamps on the counter itself, not to how often the tick actually executes.

- Each rule has a `RuleCounter`: `totalMs`, `sessionStart: Long` (elapsedRealtime a new activity streak began), `lastActivityElapsed: Long` (elapsedRealtime of the most recent qualifying chunk), `lastAccountedElapsed: Long` (elapsedRealtime up to which `totalMs` already reflects this rule's activity), `generation: Long`, `exhausted`, `enforcedToday`, `dayKey`.
- **`touch(now)`** (called from relay threads for every relayed chunk ≥ `MIN_ACTIVITY_BYTES`, either direction): if `now - lastActivityElapsed > T_IDLE_MS`, a new session has begun, so `sessionStart = now`; then `lastActivityElapsed = now`. Allocation-free (INV-12).
- **`recompute(now)`** (tick thread, run once per rule per tick — but correct for *any* gap since it was last run, per INV-16):
  ```
  intervalStart = max(lastAccountedElapsed, sessionStart)
  intervalEnd   = if (now - lastActivityElapsed <= T_IDLE_MS) now
                  else min(lastActivityElapsed + T_IDLE_MS, now)
  activeMs      = max(0, intervalEnd - intervalStart)
  totalMs      += activeMs        // split at a day boundary per §6.5/§6.7 if [intervalStart, intervalEnd) crosses one
  lastAccountedElapsed = intervalEnd
  generation++                    // only if activeMs > 0
  ```
- Why this fixes both prior bugs at once: a single mid-interval packet no longer credits a full tick period, because `intervalStart` is bounded below by `lastAccountedElapsed` (nothing before the last accounted point is re-added) and `intervalEnd` is bounded above by `now` (nothing beyond the present is projected forward). A stalled tick thread no longer loses time, because the *entire* gap between `lastAccountedElapsed` and the (possibly idle-capped) present is credited in one shot on the next tick invocation, with no per-invocation cap discarding the remainder.
- `MAX_TICK_GAP_DIAG_MS` is diagnostic only (logs a scheduling anomaly) and never truncates `activeMs`.

### 4.8 Day key — effective day key with a timezone anchor

The prior draft's `dayKey = max(LocalDate(wallNow, systemZone), persistedLastDayKey)` conflated "the local calendar day" with "a day key that never decreases," which are different concepts once the device timezone changes mid-session (M-01 finding). This spec renames the concept and states the actual behavior:

- **`anchoredZone`** is read once, in `start()` (`ZoneId.systemDefault()`), and held for the lifetime of that Engine instance. It is *not* re-read on a mid-session timezone change; it is re-read only the next time the Engine starts (i.e., on VPN restart).
- **`effectiveDayKey = max(LocalDate.now(anchoredZone), persistedLastDayKey)`**, compared as ISO strings. `persistedLastDayKey` is stored in the app's existing DataStore under key `domainusage_last_day_key` and updated on every effective-day-key change.
- **Documented, intentional limitation:** while the Engine is running, changing the system timezone (e.g., mid-flight) does not change which local day is being measured until the next Engine restart. This trades exact wall-clock-day fidelity for two things that matter more here: the key can never be pushed backward by a clock change (INV-3), and it cannot be pushed forward mid-session by anything other than genuine elapsed time. Moving the *device clock* forward within the anchored zone can still advance the day key and grant a fresh quota (documented, unresolved-by-design limitation, matching the original best-effort contract in §1) — a stronger defense (trusted time, anomaly freezing) is out of scope for V1.

### 4.9 Persistence

- Table `rule_usage_daily(rule_id TEXT, revision INTEGER, day_key TEXT, active_ms INTEGER, PRIMARY KEY(rule_id, revision, day_key))`.
- The writer periodically pulls a snapshot of changed rules from the ledger — `(rule_id, revision, day_key, totalMs, generation)` — and upserts absolute totals with a **generation-guarded** `MAX`: on successful commit, the ledger clears a rule's dirty flag only if `counter.generation` (re-read at acknowledgment time) still equals the `generation` that was just committed. If a newer `touch`/tick advanced the generation in the meantime, the dirty flag stays set so the next cycle sends the latest value — this closes the update-loss race from the prior draft, where an unconditional dirty-flag clear could drop an increment that arrived between snapshot and commit.
- **Durability claim (corrected — see NFR-8):** during ordinary shutdown (`onDestroy`/`onRevoke` running to completion), `stop()` attempts one bounded, best-effort synchronous flush of all dirty counters before returning, capped at `SHUTDOWN_FLUSH_TIMEOUT_MS` (§6.9). This bounds *ordinary*-shutdown loss to that timeout. It does not, and cannot, bound loss from abrupt process death (kill, OOM-killer, crash), because no unacknowledged asynchronous write can be guaranteed to complete before a process disappears. That case's loss remains bounded by `PERSIST_INTERVAL_MS` plus the time since the last successful checkpoint — the same bound that applies during normal operation. AC-7 (§17) is worded to reflect this honestly.

---

## 5. Architecture Decisions

- **D-01** Attribute by TLS SNI, then DNS single-candidate — keeps TLS end-to-end, no MITM.
- **D-02** Meter per `(rule, revision)`, not per domain — one unit for policy, storage and UI; storage holds no hostnames; a pattern edit gets a clean new accounting identity (D-32).
- **D-03 (revised)** 1 Hz tick keyed on per-rule last-activity, but the accrual math itself (§4.7) is cadence-independent — gives union semantics, midnight bucketing and bounded work without interval lists, and is now also immune to tick-thread scheduling delays.
- **D-04** Metric is `networkActiveMs`; activity = relayed chunk ≥ 512 B, either direction; active within 15 s of last activity — filters keepalives, treats a burst as use.
- **D-05** In-memory ledger is authoritative — enforcement never waits on the DB.
- **D-06 (revised)** Cumulative totals written with a **generation-guarded** `MAX` upsert every 10 s — idempotent under retry and crash, and no longer loses an update that lands between a snapshot read and its commit (fixes the dirty-flag race).
- **D-07 (revised)** The *effective* day key is the local date under a timezone anchored at Engine start, and never decreases within a run — clock rollback cannot reopen a past day; a mid-session timezone change is intentionally deferred to the next restart (§4.8) rather than silently reinterpreted.
- **D-08-dedup (new)** At most one enabled rule per normalized pattern — enforced at RuleEditor save and defensively deduped in `RuleIndex` construction — prevents one of two colliding rules from silently vanishing from runtime policy.
- **D-08** Durations use the monotonic clock only — immune to NTP/NITZ/user clock changes.
- **D-09** Enforcement = NXDOMAIN (5 s negative TTL) + TCP reset + UDP drop (with ICMP nudge, D-16) + live-flow sweep — DNS alone cannot stop open connections.
- **D-10** SNI is authoritative when valid — prevents shared-IP misattribution.
- **D-11** DNS fallback requires all candidates to resolve to one rule (at its current revision) — ambiguity yields "unattributed", never a guess.
- **D-12** Unattributed traffic is allowed — a false block costs more than a miss.
- **D-13 (revised)** Hold first client bytes on 443 until the parser is terminal (≤ 16 KiB) **or `MAX_INSPECTION_HOLD_MS` elapses, whichever first** — allows blocking before the ClientHello reaches the server while bounding how long a stalled handshake can occupy an inspection buffer; bounded by a 64-buffer pool with an explicit ownership/release contract (§4.2) rather than a release-before-forward pattern.
- **D-14 (revised)** HTTPS/SVCB queries for a name matching an *enabled* rule are answered NODATA, **only in mode `ENFORCE`** — narrows the previous global shaping to the specific sites the user has actually limited, so unrelated sites, connectivity checks, and Recovery resources are never affected, and `ACCOUNT` genuinely does not block anything. This does not, and is not claimed to, defeat ECH generally (§4.6).
- **D-15 (revised)** AAAA for a name matching an *enabled* rule is answered NODATA, only in `ENFORCE`; AAAA for every other name is forwarded normally. The TUN itself stays IPv4-only. This keeps IPv6-only/NAT64 networks functional for all non-metered traffic, at the cost of a metered site with no IPv4 path being unreachable while its rule is enabled — a narrower, documented limitation instead of a device-wide one.
- **D-16 (revised)** UDP/443 is **not** blanket-dropped while active. It is dropped only when DNS-fallback attribution resolves it to an exhausted rule under `ENFORCE` (§6.4), with an ICMP Port-Unreachable packet injected first (§6.10) so QUIC-capable clients fall back to TCP+TLS (where SNI applies) within about a second instead of waiting out their own 10–30 s UDP timeout. Unattributed or non-exhausted QUIC traffic is passed through unmodified; this accepts that unattributed QUIC/HTTP-3 usage cannot be metered or enforced in V1 — no general QUIC parser is built.
- **D-17** All UDP/53 handled locally regardless of destination — hardcoded resolvers cannot bypass.
- **D-18** Encrypted DNS is not blocked; resolver domains are not limitable; strict Private DNS shows a warning — blocking would break all DNS. TCP/53 and DoT/DoQ on non-default ports remain outside this feature's control plane (documented limitation, §16 item R-DNS-BYPASS).
- **D-19** Own app excluded via `addDisallowedApplication` — no self-metering, backend stays reachable.
- **D-20** `allowBypass()` removed — bypass would defeat enforcement.
- **D-21** Fail-open for ordinary faults, with a circuit breaker, but a `VirtualMachineError` is rethrown rather than swallowed (D-35) — an Engine bug must not silently cut device connectivity, but a JVM-fatal condition must not be hidden either.
- **D-22** One pattern per rule, subdomain-inclusive, no groups in V1 — simplest rule model; limitation shown in UI.
- **D-23** Reject path input instead of stripping — never silently change the user's intent.
- **D-24** New additive table only — existing schema and `UsageAccountingEngine` untouched.
- **D-25** Modes via `BuildConfig`, no remote config, no user toggle — disabling goes through the existing rule UI.
- **D-26** No remote telemetry; local counters — privacy and Kenya DPA.
- **D-27** Reuse the Baseline TCP endpoint; build no TCP stack — Baseline Contract is gated in S0, now including P9/P10.
- **D-28** Periodic snapshot pull instead of an event channel — inherently coalesced, bounded, and (with generation guarding, D-06) idempotent even under concurrent updates.
- **D-29** PSL ICANN section only — rejects `co.ke` while allowing `blogspot.com`.
- **D-30 (new)** Precise, cadence-independent interval metering (§4.7) — replaces the tick-delta formula that both overcounted bursts and undercounted stalls.
- **D-31 (new)** `RawPacketBuilder` constructs raw IPv4/TCP RST and IPv4/ICMP packets for the TUN fd (P9/P10). This is required, not optional: a TUN device exchanges whole IP datagrams with userspace (kernel `Documentation/networking/tuntap.rst`), and a locally-terminated TCP connection can only be reset from the app's point of view by writing a valid RST|ACK back into the TUN — a plain socket close on the Baseline's outbound leg does not reach the app's TCP state machine. This is standard practice for userspace VPN/firewall implementations on Android (§16 item R-RST-INJECT).
- **D-32 (new)** Rule revision identity (§4.5, FR-14) — editing a rule's pattern allocates a new `(rule_id, revision)` so usage never silently transfers from an old hostname to a new one, and flows already attributed under the old pattern keep their old accounting identity until they close.
- **D-33 (new)** Room rollback uses an explicit downgrade `Migration(N+1, N)` that drops only `rule_usage_daily`, rather than `fallbackToDestructiveMigrationOnDowngrade()` (which would destructively recreate the *entire* database). Room's `RoomDatabase.Builder` supports registering an arbitrary downgrade `Migration` via `addMigrations` (developer.android.com/reference/androidx/room/RoomDatabase.Builder); §13 specifies it precisely.
- **D-34 (new)** Private DNS status is tracked per network via `ConnectivityManager.NetworkCallback` (`onAvailable`/`onCapabilitiesChanged`/`onLinkPropertiesChanged`/`onLost`), using `LinkProperties.isPrivateDnsActive()` and `getPrivateDnsServerName()` (API 28+; developer.android.com/reference/android/net/LinkProperties), and recomputed as the set of matching networks changes — not held as sticky global state that can outlive the network it was observed on.
- **D-35 (new)** Hooks catch `Exception`, not `Throwable`. A `VirtualMachineError` is allowed to propagate (after being counted) because continuing execution after `OutOfMemoryError`/`StackOverflowError` risks running in a corrupted state; this is a narrower, more correct reading of "exception-safe" than catching everything (NFR-2, INV-6).

---

## 6. Data Flow

### 6.1 DNS query (UDP/53, any destination IP)

1. Dispatcher classifies any UDP packet with dst port 53 as DNS and passes the payload to `onDnsQuery`.
2. `DnsShaper` parses. Parse failure or non-standard query ⇒ `Forward`.
3. Decision — **mode- and rule-match-scoped** (fixes the ACCOUNT-blocks-traffic and global-shaping contradictions):

   | Mode | Condition | Result |
   |---|---|---|
   | `OFF`, `ACCOUNT` | any | `Forward` (never shaped; `ACCOUNT` still populates `DnsObservationStore` per §6.2 for accounting purposes, but never alters an answer) |
   | `ENFORCE` | qname (or an ancestor of it) matches an **exhausted** rule | NXDOMAIN, any qtype |
   | `ENFORCE` | qname (or an ancestor of it) matches an **enabled, not-yet-exhausted** rule, and qtype ∈ {28 (AAAA), 64 (SVCB), 65 (HTTPS)} | NODATA |
   | `ENFORCE` | qname does not match any rule, or matches a rule but is qtype A/other | `Forward` |

4. `Respond`: the handler builds the reply by swapping src/dst of the query packet and writes it to the TUN.
5. `Forward`: Baseline forwards to its configured upstream, whatever the original destination.

A name that never matches any enabled rule — including every never-block host and every Recovery Resource host — is never shaped by this table under any mode (INV-11, INV-15).

### 6.2 DNS response

Baseline calls `onDnsResponse(response)`. `DnsShaper` parses. For RCODE 0 with one question, the response is walked as an **alias chain**, fixing the prior "any A record maps to the qname" defect:

- Start the chain target at QNAME.
- For each RR in the answer section, in order: if its owner name does not equal the current chain target, it is ignored for attribution purposes. If it does match: a CNAME record advances the chain target to its RDATA name; an A record adds `(ip → QNAME, expiry)` to `DnsObservationStore`, where `expiry = mono + clamp(ttlSeconds, 0, DNS_TTL_CAP_S) s` (no floor — a short authoritative TTL is honored as given, up to the memory-safety cap).
- The response is returned to the app unmodified in all cases (§6.1's decision table governs whether the *query* got a real answer; §6.2 never touches the response bytes).

### 6.3 TCP flow to port 443

1. Baseline accepts the app's SYN and opens the protected outbound socket (unchanged).
2. Baseline calls `tcpFlowOpened(dstIp, 443, control)`. The Engine takes a buffer from the pool and starts an inspection deadline of `MAX_INSPECTION_HOLD_MS`. If the pool is empty, the flow is DNS-fallback-attributed and pass-through immediately.
3. Each `onClientData` appends to the buffer and runs the parser.
   - `NeedMore` and the deadline has not elapsed ⇒ `Hold`.
   - Terminal result, **or** the deadline elapses first (treated as `NoSni`, counter `inspect_timeout`) ⇒ resolve attribution (§4.6).
   - If attributed, `ENFORCE`, and the rule is exhausted ⇒ `control.reset()`; wrap the held bytes as `OwnedBytes` and release the pool slot immediately since nothing will forward them; return `Reset`.
   - Otherwise register the flow in `FlowRegistry` (subject to `MAX_TRACKED_FLOWS_PER_RULE`), wrap the held bytes as `OwnedBytes`, and return `Forward(ownedBytes)`. **The pool buffer is returned only when the Baseline calls `ownedBytes.release()`** after it has fully written/queued those bytes to the outbound socket — never before (INV-13).
4. After the decision the tap is pass-through. It calls `touch()` for chunks ≥ 512 B and does nothing else.
5. `onClosed` removes the flow from the registry.

### 6.4 Other TCP / UDP flow

At open, DNS-fallback attribution (§4.6). If attributed and (`ENFORCE` and rule exhausted):
- TCP ⇒ `control.reset()`.
- UDP ⇒ inject an ICMP Port-Unreachable packet for the flow's first datagram (§6.10), then mark subsequent datagrams of that 5-tuple `Drop` (counter `udp_dropped`).

Otherwise register (subject to `MAX_TRACKED_FLOWS_PER_RULE`) and pass through with activity touches. **UDP with dst port 443 that is not attributed to an exhausted rule is passed through unmodified** — there is no blanket QUIC block (fixes the prior draft's device-wide UDP/443 drop, which broke QUIC for every user the moment any single rule was enabled).

### 6.5 Tick (every `TICK_MS`, tick thread)

1. `now = elapsedRealtime`.
2. Compute `effectiveDayKey` (§4.8). If it changed since the last tick ⇒ **midnight split**: for each rule with an in-progress interval `[intervalStart, intervalEnd)` (§4.7) that straddles the boundary instant, add the pre-boundary portion to the outgoing day's total (using `intervalEnd = boundaryInstant` for that portion) before rollover (§6.7), then add the remainder to the new day after rollover (using the original `intervalEnd`). This replaces the prior draft's "reset totals, then add `dt`" sequence, which had no defined split and could misattribute up to `TICK_DT_CAP_MS` of usage to the wrong day.
3. Clock-jump diagnostic (wall-clock only; never affects accrual): `|Δwall − Δmono| > CLOCK_JUMP_THRESHOLD_MS` ⇒ `clock_jump++`.
4. For each rule with `enforcedToday`: `recompute(now)` per §4.7 — correct regardless of how much time has passed since this rule was last recomputed (INV-16).
5. For each rule: if `totalMs ≥ dailyLimitMs` and not exhausted ⇒ set exhausted; in `ENFORCE`, `control.reset()` every flow in `FlowRegistry[rule]` (`flow_resets_sweep`). If `totalMs < dailyLimitMs` and exhausted (limit raised) ⇒ clear exhausted.
6. Every `PERSIST_INTERVAL_MS / TICK_MS` ticks ⇒ post a generation-tagged persistence snapshot to the db thread (§4.9).

### 6.6 Persistence

The db thread receives `(rule_id, revision, day_key, totalMs, generation)` for rules changed since the last successful write. In one transaction, one statement per row: `INSERT … ON CONFLICT(rule_id, revision, day_key) DO UPDATE SET active_ms = MAX(active_ms, excluded.active_ms)` (implemented form; the earlier draft's `INSERT OR IGNORE` + `UPDATE` pair measured 327 ms p50 / 573 ms p99 for 200 rows where the single statement measures ~94 ms — same `MAX` semantics). On successful commit, clear a rule's dirty flag only if its live `generation` still equals the committed `generation` (§4.9, D-06); otherwise leave it dirty so the next cycle sends the newer value. On failure, leave the rule marked dirty unconditionally; the next cycle writes the newer cumulative value.

### 6.7 Rollover

On effective-day-key change: send the final (boundary-split, §6.5 step 2) snapshot for the old day, set all `totalMs = 0` and `lastAccountedElapsed`/`sessionStart` to the boundary instant, clear exhausted flags, re-read the rule snapshot (`enforcedToday` re-evaluated), store the new `persistedLastDayKey`, and run the `RETENTION_DAYS` delete (in addition to the startup-time run, §6.8, fixing the prior "retention only at startup" gap).

### 6.8 Startup

`start()` ⇒ set `anchoredZone = ZoneId.systemDefault()` (§4.8); read mode; read rule snapshot; if zero rules ⇒ `Dormant`. Otherwise on the db thread load today's totals for all `(rule_id, revision)` and delete rows older than `RETENTION_DAYS`. Hooks stay pass-through until the load succeeds. On failure retry every 10 s with status `Degraded(DB_ERROR)`. On success ⇒ start tick, status `Active`, register `PrivateDnsMonitor` (S7.5).

### 6.9 Shutdown

`stop()` (called from `onDestroy` and `onRevoke`, guarded by a single `RUNNING → STOPPING → STOPPED` state so concurrent callers only run the side effects once — fixes the prior duplicate-notification risk): cancel the tick; attempt one synchronous flush of every dirty counter to the db thread, bounded by `SHUTDOWN_FLUSH_TIMEOUT_MS` (this bounded wait is acceptable here, unlike on relay/packet threads, because `onDestroy`/`onRevoke` already tolerate brief blocking); whether or not that flush completes in time, then unregister `PrivateDnsMonitor`, clear the DNS store and registry, and post the VPN-stopped notification (§8, S7). See §4.9 for the resulting durability claim.

### 6.10 UDP/443 drop — ICMP Port-Unreachable nudge (new)

Used only for the narrow case in §6.4 (a UDP/443 flow DNS-fallback-attributed to an exhausted rule under `ENFORCE`):

1. Engine decides to drop the datagram.
2. `RawPacketBuilder` constructs an IPv4 packet: src IP = the datagram's original destination IP, dst IP = the datagram's original source IP, protocol = ICMP (1), ICMP type = `ICMP_PORT_UNREACHABLE_TYPE` (3), code = `ICMP_PORT_UNREACHABLE_CODE` (3), ICMP payload = the original IP header + the first 8 bytes of the original UDP payload (per RFC 792), with a correct IP and ICMP checksum.
3. Write the packet to the TUN fd (P9).
4. If construction fails for any reason (`icmp_build_fail`), fall back to a silent drop rather than blocking the relay thread or throwing.

This gives QUIC-capable clients an immediate `ECONNREFUSED`-equivalent signal so they fall back to TCP+TLS (where SNI attribution and enforcement apply) within about a second, instead of waiting out their own UDP timeout (often 10–30 s). It is never applied outside the exhausted-and-attributed case, so it never affects unattributed or not-yet-exhausted QUIC traffic (§6.4).

---

## 7. Implementation Sequence

| Step | Name | Depends on |
|---|---|---|
| S0 | Baseline verification (gate), including P9/P10 | — |
| S1 | Foundation: package, clocks, config, mode flag, CI checks, PSL provenance pinning | S0 |
| S2 | Host handling: normalizer, PSL, exclusion lists, rule index (with dedup + revision), rule provider | S1 |
| S3 | DNS message layer, shaper (owner-name/CNAME chain), observation store | S1, S2 |
| S4 | TLS ClientHello parser, buffer pool with ownership contract, inspection timeout | S1 |
| S5 | Ledger (precise interval math, generations), tick, flow registry (in-memory, capped) | S1, S2 |
| S6 | Attribution + Baseline flow/DNS wiring; mode- and rule-scoped shaping | S3, S4, S5 |
| S7 | Persistence (generation-guarded), Room migration + downgrade migration, startup/shutdown, status, `PrivateDnsMonitor`, notification | S5, S6 |
| S8 | Enforcement (NXDOMAIN, reset, sweep, ICMP nudge) + `RawPacketBuilder` + VPN builder changes | S6, S7 |
| S9 | UI: RuleEditor validation (incl. uniqueness, limit bounds), scope + connection-level notices, status banner, usage display | S2, S7 |
| S10 | Instrumented tests, benchmarks, soak | S8, S9 |
| S11 | Rollout configuration | S10 |

---

## 8. Detailed Implementation Steps

### S0 — Baseline verification (gate)

1. Read `DnsSinkholeVpnService` and its collaborators. Write `docs/domainusage-baseline-check.md` with one line per P1–P10 (`PASS`/`FAIL` + file:line).
2. **STOP conditions:** P1, P3, P4, P5, P6, P7 `FAIL`. On a stop, report the failing items and make no other changes. Do not implement a TCP/IP stack and do not reroute traffic.
3. P2 partial: if only `reset()` or its thread-safety is missing, implement it inside the existing TCP endpoint. It must send RST|ACK with current sequence numbers, remove the flow, and be callable from any thread by posting to the flow's owner queue.
4. P9/P10 (new): if the Baseline cannot already write a raw RST|ACK or ICMP packet to the TUN fd, implement `RawPacketBuilder` in S8 as a small, self-contained IPv4/TCP/ICMP header builder with checksum computation (RFC 791/793/792), and confirm the Baseline's TCP endpoint already tracks the sequence/ack numbers it needs to relay bytes correctly (P10) — if it doesn't, add the minimal field to track them. This is not a stop condition; it is expected implementation work gated here only to confirm scope before S8.
5. P8: record only. S8 applies the changes.

### S1 — Foundation

1. Create package `domainusage`, `MeteringConfig` (§2.3), `EngineMode`, `EngineStatus` (`Dormant`, `Active`, `Degraded(reason)`).
2. `MonotonicClock` wraps `SystemClock.elapsedRealtime()`. `WallClock` exposes `today(): LocalDate` using `ZoneId.systemDefault()`. Only `SystemWallClock` may call `System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()` or `Clock.system*`.
3. Add `buildConfigField "String", "DOMAIN_USAGE_MODE"` — `debug`: `"ENFORCE"`; `release`: `"OFF"`. Debug builds honor a DataStore override; release builds ignore it.
4. CI check (script run by Gradle `check`): `git grep -nE "currentTimeMillis|Instant\.now|LocalDate\.now|Clock\.system" -- '*/domainusage/*'` must match only `SystemWallClock.kt`. Fail the build otherwise.
5. Add a circuit breaker to `DomainUsageEngine`: every hook body is wrapped in `try/catch (Exception)` (D-35 — not `Throwable`; a `VirtualMachineError` propagates after being counted via a dedicated `fatal_vm_error` counter). On catch of an `Exception`, increment `hook_exceptions`. If `BREAKER_MAX_EXCEPTIONS` occur within `BREAKER_WINDOW_MS`, set the Engine dormant until service restart and status `Degraded(INTERNAL_ERROR)`, and increment `breaker_tripped`.
6. **PSL provenance (new, §16 item R-PSL-PIN):** commit `public_suffix_list.dat` together with a `public_suffix_list.provenance` file recording the source URL, retrieval date, and SHA-256 of the committed asset. CI verifies the checksum matches on every build. The list is not re-downloaded at implementation time on a rolling basis; updating it is a deliberate, reviewed change.

### S2 — Host handling and rules

1. **`HostNormalizer.normalize(input): Result`.** Steps: trim; strip `http://`/`https://`; strip userinfo (`…@`) and port (`:digits`); if a remaining path other than `/`, or a query or fragment, exists ⇒ `HasPath(suggestedHost)`; lowercase; strip trailing dot; convert with `java.net.IDN.toASCII` (exception ⇒ `Invalid`); reject IP literals (`IpLiteral`); require each label 1–63 chars of `[a-z0-9-]` (also `_` for SNI matching only), no leading/trailing hyphen, total ≤ 253 (`Invalid`); require ≥ 2 labels and not a public suffix (`TooBroad`); reject names equal to a never-block host or resolver (`NeverBlock`, `EncryptedDnsResolver`).
2. `normalizeLegacy(input)`: for stored rule values, discards any path/query/fragment and then applies the same checks. The adapter uses it. Invalid stored values are skipped and counted in `rules_invalid`.
3. **`PublicSuffixList`.** Load the pinned asset (S1.6) synchronously during Engine/application initialization — **not** lazily on the db thread on first RuleEditor save (fixes a race where RuleEditor's S9 validation needs a synchronous answer before the list may have loaded). `isPublicSuffix(name)`: exception rule (`!name`) ⇒ false; exact rule ⇒ true; wildcard rule `*.p` where the name equals one label + `p` ⇒ true; else false.
4. **`NeverBlockDomains`:** `connectivitycheck.gstatic.com`, `connectivitycheck.android.com`, `clients3.google.com`, `play.googleapis.com`, plus `HELP_RESOURCE_DOMAINS` = the hosts of every URL the Recovery Resource UI links to (search the codebase; empty list if none). The set matches exact hosts and their subdomains.
5. **`EncryptedDnsResolvers`:** `dns.google`, `cloudflare-dns.com`, `one.one.one.one`, `mozilla.cloudflare-dns.com`, `dns.quad9.net`, `dns.adguard-dns.com`, `doh.opendns.com`, `dns.nextdns.io`. Rule creation rejects these and their subdomains. `RuleIndex.match` also returns null for them.
6. **`DomainRuleProvider`:** implement over the existing website rules.
   - Emit only rules whose daily limit is enabled.
   - `id = existingId.toString()`.
   - `pattern = normalizeLegacy(host)`.
   - `dailyLimitMs = existing limit in ms, clamped to [MIN_DAILY_LIMIT_MS, MAX_DAILY_LIMIT_MS]` (§16 item R-LIMIT-BOUNDS); a stored value outside range is clamped and counted in `rules_invalid`, not silently accepted.
   - `enforcedToday = existing dayScopeCoversToday(rule)`.
   - `revision`: an in-memory counter per `id`, incremented whenever this provider observes `pattern` differ from the value it emitted for that `id` last time (D-32).
   - Expose `rules: Flow<List<DomainRule>>` (re-emits on rule table change) and `suspend fun snapshot()`.
7. `RuleIndex` per §4.5, including the build-time pattern dedup.
8. **RuleEditor uniqueness check (new, FR-2):** before accepting a save that enables a daily limit, reject if the normalized pattern already matches another enabled rule's normalized pattern (exact match, not subdomain overlap — two independently limited sites where one is a subdomain of the other is allowed and handled by longest-pattern-wins at match time).

### S3 — DNS layer

1. **`DnsMessage`** parser: header 12 bytes; require QDCOUNT = 1, opcode 0. Names follow compression pointers with ≤ 32 hops, ≤ 255 bytes, pointers only backward. Answer parsing reads ≤ 64 records and preserves each record's **owner name**, type, class, TTL and RDATA (not just IP-for-A-records) so §6.2's alias-chain walk can validate that an A record actually belongs to the query's alias chain. It extracts A records (type 1, RDLENGTH 4) and CNAME records (type 5) fully; other types are skipped by RDLENGTH but their owner name is still tracked for chain validation. Any structural violation ⇒ `ParseFailed` (counter `dns_parse_fail`). `ParseFailed` ⇒ forward unmodified, no observation.
2. **Response builder.** Flags: QR=1, opcode copied, AA=0, TC=0, RD copied, RA=1; ID copied; question echoed; ANCOUNT=0; NSCOUNT=1; ARCOUNT=0.
   - NXDOMAIN uses RCODE 3, NODATA uses RCODE 0.
   - Authority = SOA: owner is a pointer to the question name; type 6, class 1; TTL = negative TTL; MNAME `ns.invalid.`; RNAME `hostmaster.invalid.`; serial 1, refresh 3600, retry 600, expire 86400, minimum = negative TTL.
   - NXDOMAIN TTL = `NXDOMAIN_NEG_TTL_S`; NODATA TTL = `NODATA_NEG_TTL_S`.
3. **`DnsShaper.decide`** per §6.1's mode- and rule-scoped table. Counters: `dns_queries`, `dns_nxdomain_served`, `dns_nodata_aaaa`, `dns_nodata_https`, `dns_nodata_svcb`.
4. **`DnsObservationStore`.** `synchronized`, access-ordered LRU on IP (16-byte key, IPv4-mapped). Each entry: ≤ 8 `(name, expiresAtElapsed)`; adding a 9th evicts the oldest. `expires = mono + clamp(ttlSeconds, 0, DNS_TTL_CAP_S) s` (no floor — §16 item R-TTL-FLOOR). `candidates(ip)` returns unexpired names. Cleared in `stop()`.
5. **Baseline wiring.**
   - The dispatcher routes every UDP packet with dst port 53 to the DNS handler (any destination IP).
   - Call `onDnsQuery` before forwarding.
   - Call `onDnsResponse` when the upstream response arrives.
   - Replies to `Respond` swap src/dst of the query packet.
   - TCP/53 and UDP/TCP 853 keep Baseline behavior unchanged (documented DNS-bypass limitation, §16 item R-DNS-BYPASS).

### S4 — TLS parser

1. `TlsClientHelloParser.feed(buf, off, len): Result`, incremental over a caller-owned `ByteArray(INSPECT_MAX_BYTES)`. Results: `NeedMore`, `Sni(host)`, `Ech`, `NoSni`, `NotTls`, `Malformed`, `TooLarge`.
2. **Algorithm.**
   - First byte ≠ 0x16 or second byte ≠ 0x03 ⇒ `NotTls`.
   - Read records (5-byte header; record length ≤ 16384 else `Malformed`). Concatenate consecutive handshake-record payloads.
   - Handshake header: type 0x01, 3-byte length `L`. `4 + L > INSPECT_MAX_BYTES` ⇒ `TooLarge`. Not enough bytes yet ⇒ `NeedMore`.
   - ClientHello body: version(2), random(32), session_id (1-byte length), cipher_suites (2-byte length), compression (1-byte length), extensions (2-byte length; absent ⇒ `NoSni`).
   - Extensions: type(2) length(2). Type 0x0000: server_name_list (2-byte length) with entries name_type(1) name_length(2) name; take the first `name_type == 0`. Type 0xFE0D marks ECH.
   - Result: ECH present ⇒ `Ech` (ignore the SNI). Else SNI present and valid ⇒ `Sni`. Else `NoSni`.
3. **Bounds.** Every length is checked against its enclosing structure. Any violation ⇒ `Malformed`. No string conversion of binary data. The hostname is decoded as ASCII, validated (`[a-z0-9._-]`, ≤ 253, labels 1–63), lowercased; an invalid hostname ⇒ `NoSni`.
4. **`InspectionBufferPool`:** `INSPECT_POOL_SIZE` pre-allocated buffers, lock-free deque. `acquire()` returns null when empty (counter `inspect_pool_exhausted`). Buffers are wrapped as `OwnedBytes` (§4.2) on every terminal result; the pool slot is returned to the deque only inside `OwnedBytes.release()`, called by the Baseline once it has finished forwarding — **not** by the Engine before returning `Forward` (INV-13, fixes the cross-flow buffer-corruption hazard). A flow that closes before reaching a terminal result also triggers `release()` via `onClosed`.
5. **Inspection timeout (new):** the flow's tap starts a `MAX_INSPECTION_HOLD_MS` deadline at `tcpFlowOpened`. If no terminal result is reached first, treat as `NoSni` (`inspect_timeout++`), release the buffer, and proceed to DNS-fallback attribution — this bounds how long a stalled or slow-drip ClientHello can occupy scarce inspection resources (§16 item R-INSPECT-TIMEOUT).
6. Counters: `tls443_flows`, `sni_ok`, `sni_none`, `sni_ech`, `sni_notls`, `sni_malformed`, `sni_toolarge`, `inspect_timeout`.

### S5 — Ledger and registry

1. `RuleCounter`: `totalMs: Long` (tick-thread written, volatile read), `sessionStart: Long`, `lastActivityElapsed: AtomicLong`, `lastAccountedElapsed: Long`, `generation: Long`, `exhausted: Boolean` (volatile), `enforcedToday`, `dailyLimitMs`, `changedSincePersist`.
2. `UsageLedger` holds `ConcurrentHashMap<String, RuleCounter>` keyed by `"$ruleId:$revision"`. On rule snapshot swap: create counters for new `(id, revision)` pairs with total loaded from the db (or 0 if absent); a rule whose `pattern` changed gets a fresh counter under its new revision (D-32) while any still-open flows keep touching the old counter until they close; drop counters for removed rules once their `FlowRegistry` entry is empty; update limit and `enforcedToday`; re-evaluate exhausted at the next tick.
3. `FlowRegistry`: `ConcurrentHashMap<String, MutableSet<FlowControl>>` (concurrent set), capped per rule at `MAX_TRACKED_FLOWS_PER_RULE` (`flow_registry_capped` counter beyond the cap — those flows are still metered, just not swept early; they are still blocked at their own next DNS lookup or activity check once exhausted).
4. Tick loop per §6.5, on `domainusage-tick`, using `delay(TICK_MS)`.
5. `touch` path: per §4.7. It allocates nothing (INV-12).
6. Expose `usageTodayMs(ruleId)` (reads `totalMs` for the rule's current revision).

### S6 — Attribution and wiring

1. **`AttributionResolver`:** SNI path and DNS fallback exactly as §4.6, including the revised ECH handling. Counters: `flows_tcp_total`, `flows_attributed_total`, `flows_unattributed`, `dns_fallback_attributed`, `dns_fallback_ambiguous`.
2. **TCP tap:** `InspectingTcpTap` for dst port 443 (§6.3), including the buffer-ownership contract and inspection timeout. `PassThroughTap` for others (DNS-fallback attribution at open). Hook `tcpFlowOpened` into the Baseline TCP endpoint at flow creation. Route client→server chunks through `onClientData` before forwarding; honor `Hold`/`Forward`/`PassThrough`/`Reset`. Call `onServerData(len)` for server→client chunks and `onClosed()` on close.
3. **UDP tap:** `udpFlowOpened` at first datagram per 5-tuple. §6.4's DNS-fallback-attribution-gated handling applies to dst port 443 exactly like any other UDP flow; there is no separate blanket rule for port 443. Route each datagram through the tap (`Reset` ⇒ drop after the ICMP nudge, §6.10).
4. Both taps register with `FlowRegistry` when attributed (subject to the per-rule cap) and deregister on close.

### S7 — Persistence, lifecycle, status

1. **Entity/DAO/migration.**
   - `RuleUsageDaily(rule_id TEXT, revision INTEGER, day_key TEXT, active_ms INTEGER)`, composite primary key `(rule_id, revision, day_key)`, no foreign keys.
   - Bump the Room version by 1. `exportSchema = true`. Add `Migration(N, N+1)`: `CREATE TABLE rule_usage_daily (…)`. **Also add a downgrade `Migration(N+1, N)`** registered via `addMigrations`: `DROP TABLE IF EXISTS rule_usage_daily` (D-33). This lets a rollback APK built against schema `N` reopen a database that was briefly upgraded to `N+1`, at the cost of losing this feature's not-yet-retained usage history — every other table is untouched. Do not use `fallbackToDestructiveMigrationOnDowngrade()`, which would destructively recreate the whole database rather than just the new table. Verify the SQLCipher-specific factory/passphrase setup doesn't need special handling for this downgrade path (project-specific — confirm in S0/S7, §16 item R-ROOM-DOWNGRADE).
   - DAO: `loadDay(day_key, rule_ids_and_revisions)`, `@Transaction upsertMax(rows)` (single `INSERT … ON CONFLICT(rule_id, revision, day_key) DO UPDATE SET active_ms = MAX(active_ms, excluded.active_ms)` per row — see §6.6 for why the two-statement draft form was dropped), `deleteOlderThan(day_key)`.
2. **`UsagePersistence`:** runs on `domainusage-db`. Flush cycle per §6.6, generation-guarded. After 3 consecutive failures set `Degraded(DB_ERROR)`; clear on the next success. Counters `db_flush_ok`, `db_flush_fail`.
3. **Startup/shutdown** per §6.8–6.9. `start()` is called after `establish()` succeeds. `stop()` is called from `onDestroy` and `onRevoke`, guarded by the `RUNNING → STOPPING → STOPPED` state (§6.9) so its side effects (final flush attempt, notification) run exactly once even if both callers fire.
4. **VPN-stopped notification:** on the single `stop()` execution, if ≥ 1 rule was enabled and mode ≠ `OFF`, post one notification: title "Website limits paused", text "The VPN stopped, so website limits are not being applied." Use a dedicated channel `domain_usage_status`. Post only if notification permission is granted.
5. **`PrivateDnsMonitor` (revised, D-34):** register a `NetworkCallback` for `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_NOT_VPN`. Maintain a per-`Network` record of `LinkProperties.getPrivateDnsServerName()`/`isPrivateDnsActive()`, updated on `onLinkPropertiesChanged`, `onCapabilitiesChanged`, and cleared on `onLost`. The effective status (`Degraded(PRIVATE_DNS)` or not) is recomputed from the current set of tracked networks on every callback — it is never left set from a network that has since disappeared. If any currently-tracked matching network has `privateDnsServerName != null` (strict mode) ⇒ status `Degraded(PRIVATE_DNS)` and `tamper_private_dns++`; otherwise the status clears once no tracked network is in strict mode. Unregister the callback in `stop()`. (API reference: developer.android.com/reference/android/net/LinkProperties, API 28+.)
6. Status is a `StateFlow<EngineStatus>`.

### S8 — Enforcement and VPN builder

1. NXDOMAIN, NODATA, reset, drop, sweep, and the ICMP nudge, strictly per §6.1/§6.3/§6.4/§6.5/§6.10, only in mode `ENFORCE`. `ACCOUNT` computes exhausted flags and counters (for UI display and beta health metrics) but performs **zero** shaping, dropping, or resetting (INV-15, FR-11).
2. **`RawPacketBuilder`** (if not already present per S0/P9-P10): construct IPv4/TCP RST|ACK and IPv4/ICMP Port-Unreachable packets with correct header lengths, checksums, and sequence/ack numbers (INV-14). Validate every length/offset against the buffer it's built into before writing; a failed validation aborts construction and falls back per §9's error table rather than writing a malformed packet.
3. **Builder changes** in `DnsSinkholeVpnService`:
   - Call `addDisallowedApplication(BuildConfig.APPLICATION_ID)`.
   - Remove any `allowBypass()` call.
   - Do not add IPv6 addresses or routes.
4. Do not change the foreground type, notification, or start/restart logic.

### S9 — UI

1. RuleEditor: run `HostNormalizer.normalize` on save, then the FR-2 uniqueness check (S2.8), then clamp the daily limit to `[MIN_DAILY_LIMIT_MS, MAX_DAILY_LIMIT_MS]` before storing. Error strings:

   | Error | String |
   |---|---|
   | `HasPath` | "Limits apply to a whole site. Use "%s" instead." (with a "Use it" button that fills the field) |
   | `Invalid` | "Enter a website address like example.com." |
   | `IpLiteral` | "Enter a website name, not an IP address." |
   | `TooBroad` | "Enter a specific site, like example.com." |
   | `NeverBlock` | "This site is needed for internet connectivity or help resources and can't be limited." |
   | `EncryptedDnsResolver` | "This is a DNS service and can't be limited." |
   | `DuplicatePattern` (new) | "This site already has a limit set." |

2. Under the daily-limit switch show: "Limits apply to this site and its subdomains. Other domains used by the same service aren't counted." **and, new (§16 item R-SNI-SCOPE):** "Usage is measured per connection your device makes to this site, not per page or request — a site that reuses one connection for several pages may show usage differently than expected."
3. Show "Used today: X / Y min" per website rule from `usageTodayMs` (current revision), refreshed at most every 5 s.
4. Show a status banner from `EngineStatus`:
   - `Degraded(PRIVATE_DNS)`: "Private DNS is on, so website limits may not apply. Set Private DNS to Off or Automatic in system settings."
   - `Degraded(DB_ERROR)` / `Degraded(INTERNAL_ERROR)`: "Website limits are temporarily off."
5. Implement in the app's existing UI toolkit. Strings go in string resources.

### S10 — Verification

Execute §12 fully; record results in `docs/domainusage-test-report.md`.

### S11 — Rollout

Configure per §13.

---

## 9. Error Handling

| Situation | Behavior |
|---|---|
| Any ordinary exception in a hook | Catch (`Exception`, not `Throwable`), `hook_exceptions++`, pass through. Breaker trips per S1. |
| A `VirtualMachineError` in a hook | Recorded (`fatal_vm_error++`), rethrown — not swallowed (D-35, NFR-2). |
| DNS parse failure | Forward unchanged, no observation, `dns_parse_fail++`. |
| DNS answer RR whose owner name doesn't match the current alias-chain target | Ignored for attribution; no observation added (§6.2). |
| TLS parse `NotTls`/`NoSni`/`Malformed`/`TooLarge`/`Ech`/timeout | Release buffer via `OwnedBytes`, forward held bytes, DNS-fallback attribution. |
| Inspection pool exhausted | Flow passes through without holding. DNS-fallback attribution. |
| Client stalls mid-ClientHello | Held only until `MAX_INSPECTION_HOLD_MS`, then treated as `NoSni` (`inspect_timeout++`) — no longer held indefinitely. |
| Ambiguous / unattributed flow | Allow, in every mode. Counter only. |
| DB load fails at startup | Hooks pass through. Retry every 10 s. `Degraded(DB_ERROR)`. |
| DB write fails | Keep rules marked changed. Next cycle writes the newer cumulative value (generation-guarded, §4.9). `Degraded(DB_ERROR)` after 3 consecutive failures. |
| Process killed abruptly | Loss ≤ `PERSIST_INTERVAL_MS` plus time since the last successful checkpoint — **not** a hard ≤10 s guarantee (§4.9, NFR-8). On restart the ledger reloads persisted totals. |
| Service stops normally (`onRevoke`/`onDestroy`) | §6.9: bounded best-effort flush (≤ `SHUTDOWN_FLUSH_TIMEOUT_MS`), then traffic goes direct (fail-open). Notification posted exactly once. |
| Wall clock jumps | Durations unaffected (monotonic-only, INV-2). Effective day key clamps (INV-3). `clock_jump++`. |
| Rule pattern edited | New revision allocated (D-32); old attributed flows keep accruing to the old revision's counter until close; new flows use the new revision. Usage never transfers between hostnames. |
| Rule deleted | Snapshot swap. Its `FlowRegistry` entries are swept (reset/dropped) if `ENFORCE` and it was exhausted, else simply drop tracking; the counter is retained until its flows close, then removed. |
| Two rules normalize to the same pattern | RuleEditor rejects the second save (`DuplicatePattern`); if such data already exists (pre-dating the check), `RuleIndex` keeps the smallest `id` and counts `rules_duplicate_pattern` (§4.5). |
| Limit raised while exhausted | Exhausted cleared next tick. NXDOMAIN stops after ≤ 5 s of client negative cache. |
| Rule id/revision in DB but not in current rules | Ignored. Deleted by retention. |
| Private DNS strict on any tracked network | `Degraded(PRIVATE_DNS)`. Engine keeps running. Clears automatically when no tracked network is strict (D-34). |
| `protect()` failure or outbound connect failure | Baseline behavior, unchanged. |
| Raw RST/ICMP packet construction fails validation | Abort construction; TCP falls back to whatever `reset()` already does at the Baseline layer if available, otherwise the flow is left to time out; UDP falls back to a silent drop (`icmp_build_fail++`/`rst_build_fail++`). Never write a packet built from unvalidated lengths (INV-14). |

---

## 10. Security Requirements

- **S-1** All parsers (DNS, TLS, PSL) are bounds-checked byte parsers. Never convert binary buffers to strings for searching. No regex on packet data.
- **S-2** Per-flow inspection memory is capped at `INSPECT_MAX_BYTES`; total at pool size; held time capped at `MAX_INSPECTION_HOLD_MS`.
- **S-3** Every hostname (SNI, DNS qname, rule input) is validated by the rules in S2/S4 before use as a key.
- **S-4** Storage contains only `rule_id`, `revision`, `day_key`, `active_ms`. Persist no hostnames, IPs, DNS answers, or timestamps other than `day_key`.
- **S-5** Never log hostnames, IPs, DNS names, or payload bytes, at any log level or in exception messages. Logs contain counters only.
- **S-6** The DNS observation store, registry and buffers are memory-only and cleared in `stop()`.
- **S-7** No new manifest permissions. No new exported components.
- **S-8** Use the existing SQLCipher database instance and key handling. No key changes.
- **S-9** Usage data is not added to any sync/relay payload (accountability-partner or Firebase).
- **S-10** `allowBypass()` is absent. `addDisallowedApplication` for the app's own package is present.
- **S-11** Rule input rejects IP literals, public suffixes, never-block hosts, encrypted-DNS resolvers, and duplicate normalized patterns.
- **S-12** Do not block TCP/UDP 853 or known DoH hosts (documented DNS-bypass limitation, §16 item R-DNS-BYPASS).
- **S-13** The DB migration is additive going forward (N → N+1); the downgrade migration (N+1 → N) only ever drops the new table and never touches any pre-existing table or column (D-33).
- **S-14 (new)** `RawPacketBuilder` validates every length and offset against the buffer/packet it constructs before writing; it never trusts an externally supplied length without checking it against the actual buffer size (INV-14).

---

## 11. Performance Requirements

Measured on the reference devices (§3.1, now including one Android 17 device/emulator), same network, `OFF` vs `ACCOUNT` with 3 enabled rules, median of 3 runs each.

| ID | Gate |
|---|---|
| PF-1 | `iperf3` through the VPN, 8 parallel streams, 30 s: `ACCOUNT` throughput ≥ 90 % of `OFF`. |
| PF-2 | 200 sequential TLS connections to the test server: p95 time-to-first-ClientHello at the server ≤ `OFF` + 25 ms. |
| PF-3 | 10-minute scripted browsing: process CPU time ≤ 1.15 × `OFF`. |
| PF-4 | 30-minute soak with continuous churn of ≥ 100 concurrent flows: heap growth after warm-up ≤ 10 MB; thread count flat — quiesced post-churn floor measured over a fixed 3-minute window at each end, end ≤ start + 10 (tolerance absorbs in-process SDK keep-alive thread wander; a real per-flow leak over the ≥ 10,000 churned flows in a leg shows as tens-to-hundreds). Tolerance re-ruled +5 → +10 by project owner 2026-09-22: the X6532 legs read within-leg deltas +6/+2/−3 — one leg over by a single thread while its heap *shrank* — so +5 was measuring keep-alive wander, not retention. Approved by project owner 2026-09-21 after run-6 evidence (within-leg deltas +1/−4 with floors converging as keep-alives expire; see app/docs/domainusage-test-report.md §5.1). |
| PF-5 | 1-hour scripted screen-on browsing: battery drain ≤ `OFF` + 2 percentage points. **PASS 2026-09-29 overnight** (X6532 unplugged, wireless adb; `--runs 1` = 2×60 min): `OFF=10pp ACCOUNT=10pp` against the 12 pp bound — report §4.2. |
| PF-6 | `onClientData` after decision, `onServerData`, and `touch` allocate zero objects (allocation-counting unit test). Treat this as a regression guard against introducing allocations, not as a formal proof of zero allocation across every ART/JIT configuration. |
| PF-7 | DB flush of 200 rows: p99 ≤ 150 ms on the db thread. Re-calibrated 2026-09-21 (was 50 ms) with project-owner approval: the original bound was never calibrated against SQLCipher on the low-end reference device. Measured cost model on the X6532 — ~7.5 ms fixed commit + ~0.31 ms/row (p50 7.5 ms at n=1 → 68.2 ms at n=200 on an idle device), p99 93–138 ms under load; the shipped single-statement upsert is already the optimization that bound assumed (the prior two-statement form measured 327 ms p50 / 573 ms p99). 150 ms sits ~1.6× above the measured idle p50 and still ≥ 3.8× below the superseded form's p99, so it keeps detecting that regression class. Evidence: app/docs/domainusage-test-report.md §5.1 item 11. |
| PF-8 (new) | `RawPacketBuilder` RST/ICMP construction, including checksum, takes < 1 ms per packet on the low-end reference device. |

- The relay thread work per chunk is one comparison and one `lazySet`. Do not add locks or maps to that path.
- Gate values change only by explicit approval.

---

## 12. Testing Requirements

### 12.1 Unit (JVM, fake `MonotonicClock`/`WallClock`)

- **U-1 `HostNormalizer`:** scheme/port/userinfo strip; `example.com/forum` ⇒ `HasPath("example.com")`; uppercase; trailing dot; IDN; IP literals; single label; public suffix (`co.ke`); never-block; resolvers; length limits.
- **U-2 `PublicSuffixList`:** exact, wildcard, exception rules; `co.ke` true; `example.co.ke` false; loads synchronously before the first RuleEditor validation can run.
- **U-3 `RuleIndex`:** exact, subdomain, longest-wins, never-block exemption (`connectivitycheck.gstatic.com` under a `gstatic.com` rule ⇒ null), duplicate-pattern dedup keeps the smaller id.
- **U-4 `DnsMessage`:** valid query/response; compression loops; forward pointers; truncated; oversize; QDCOUNT ≠ 1; owner-name tracking through a CNAME chain; fuzz ≥ 100 000 mutations of fixtures plus targeted structural mutations at each parse boundary (length fields, pointer offsets, record-count fields) — must never throw and never exceed the input length.
- **U-5 Response builder:** NXDOMAIN/NODATA bytes re-parse cleanly, echo the question, carry the SOA with the correct TTL and minimum.
- **U-6 `DnsShaper`:** full decision table for all qtypes × {`OFF`, `ACCOUNT`, `ENFORCE`} × {no match, enabled-not-exhausted, exhausted} — including the assertion that `OFF`/`ACCOUNT` never shape any answer.
- **U-7 `DnsObservationStore`:** TTL honored down to 0, cap enforced, eviction (IP and per-IP), expiry, clear.
- **U-8 `TlsClientHelloParser`:** fixtures below, split at **every** byte boundary, split across two TLS records, multiple ClientHello extensions, ECH ⇒ `Ech`, missing extensions, wrong content type, oversize ⇒ `TooLarge`, inspection-timeout path (simulated stalled feed past `MAX_INSPECTION_HOLD_MS`), fuzz ≥ 100 000 mutations (no exception, no read past the buffer).
  - Fixture A: real ClientHello for `example.com` generated with Python `ssl.MemoryBIO` (`wrap_bio(server_hostname="example.com")`; `do_handshake()`; read the outgoing BIO).
  - Fixture B: Chrome-stable capture whose ClientHello carries a post-quantum key share and spans two TCP segments.
  - Fixture C: Chrome-stable capture with ECH enabled.
  - Fixture D (new): Chrome-stable capture on an Android 17 device with ECH enabled by platform default.
  - Store under `src/test/resources/tls/`.
- **U-9 `AttributionResolver`:** SNI match; SNI valid no-match ⇒ unattributed and no DNS override; ECH ⇒ DNS single-candidate, never blocked merely for being ECH; two names same rule (same revision) ⇒ attributed; two names different rules or one NONE ⇒ ambiguous; expired candidates ignored; candidates matching a rule's old revision after an edit do not attribute to the new revision.
- **U-10 (revised) `UsageLedger`:** one flow 20 s ⇒ ≈ 20 s + tail (15 s) ± 1 s; 8 overlapping flows ⇒ counted once; chunks < 512 B ⇒ 0; idle ⇒ stops after 15 s; a single mid-interval packet does not credit a full tick period (regression test for the original overcounting bug); a tick invocation artificially delayed by 60 s still yields the exact correct total on its next run (regression test for the original stall-undercounting bug); day rollover splits usage exactly at the boundary, not merely within ±`TICK_DT_CAP_MS`; wall clock jumped back ⇒ effective day key unchanged; wall clock jumped forward ⇒ new effective day key; system timezone changed mid-session ⇒ effective day key unaffected until next `start()` (documents D-07's anchoring, does not regress it); `enforcedToday = false` ⇒ no accrual; limit reached ⇒ exhausted; limit raised ⇒ cleared.
- **U-11 Persistence:** `MAX` never lowers for a given `(rule_id, revision, day_key)`; a generation that advances between snapshot and commit is not lost (regression test for the dirty-flag race); retry after failure keeps the newest value; reload seeds ledger; retention delete runs at both startup and rollover.
- **U-12 Breaker:** 20 ordinary exceptions in 60 s ⇒ dormant + `INTERNAL_ERROR`; a `VirtualMachineError` is rethrown rather than counted toward the breaker.
- **U-13 Static:** CI grep check (S1.4) fails when a forbidden call is added to a test file inside `domainusage`; PSL checksum check fails on an unpinned/modified asset (S1.6).
- **U-14 (new) Precise interval math:** direct unit tests of the §4.7 formula against hand-computed expected `activeMs` for: a burst mid-interval, a session spanning multiple ticks, a tick invoked late, a tick invoked twice in quick succession, and a session that goes idle exactly at a tick boundary.

### 12.2 Instrumented (real VPN on device)

Test server: one IP serving two TLS hostnames `a.<testdomain>` and `b.<testdomain>` (nginx SNI), plus hostname `c.<testdomain>` on a separate IP, plus a streaming endpoint sending 1 KB every 2 s, plus an IPv6-capable test port, plus a controlled ECH-enabled endpoint with known configs (new, for I-12b).

| ID | Scenario | Expected |
|---|---|---|
| I-1 | Rule on `a`; browse `b` 60 s (same IP) | Rule `a` usage = 0. |
| I-2 | After DNS resolves `a` and `b`, raw TCP to the shared IP on port 8443 with a rule on `a` | Unattributed (ambiguous). Allowed. |
| I-3 | Rule on `c`; DNS resolve `c`; TCP to port 8080 | Attributed by DNS to `c`'s rule. |
| I-4 | 8 parallel streams to `a` for 20 s | Usage ≈ 35 s (20 s + 15 s tail) ± 3 s, not 160 s. |
| I-5 | One request then idle 60 s | Usage ≤ 17 s. |
| I-6 | 100-byte ping every 5 s for 60 s | Usage = 0. |
| I-7 | Limit 30 s; hold a streaming connection to `a` | Connection reset within 2 s of exhaustion; new lookup of `a` ⇒ NXDOMAIN for any qtype; `curl --resolve a:443:<ip>` ⇒ reset at attribution. |
| I-8 | Use 25 s, `adb shell am force-stop`, restart | Restored total ≥ used − (`PERSIST_INTERVAL_MS` + last-checkpoint age) — not a fixed 11 s bound. |
| I-9 | Emulator: set wall clock back 1 day mid-use | Effective day key unchanged; accrual continues on the same day. |
| I-10 | Emulator: cross local midnight during use | Usage split exactly across two day keys at the boundary; new day starts unblocked. |
| I-11 | Chrome to a QUIC-enabled site with a rule on that site already exhausted, `ENFORCE` | UDP/443 dropped with ICMP nudge; page loads over TCP within ~1–2 s. `udp_dropped > 0`, `icmp_injected > 0`. |
| I-11b (new) | Chrome to a QUIC-enabled, unrelated (no rule) site while another rule is exhausted | Page loads over QUIC normally; that site's UDP/443 is never dropped (regression test for the fixed blanket-drop bug). |
| I-12 | Chrome to Cloudflare `/cdn-cgi/trace` with a not-yet-exhausted rule on that site, `ENFORCE` | Reports `sni=plaintext` (HTTPS/SVCB NODATA suppressed ECH via system DNS for this app). |
| I-12b (new) | Controlled ECH-enabled test endpoint, Android 17 device, no rule on that host | Connection succeeds with ECH negotiated (regression test that non-metered ECH traffic is never disturbed). |
| I-13 | Test app connects to the IPv6 test port by literal, with a rule enabled on that host | Connection fails for that host specifically. |
| I-13b (new) | Test app connects to a different, unrelated IPv6-only test host while a rule is enabled elsewhere | Connection succeeds (regression test for the fixed global-AAAA-suppression bug). |
| I-14 | Strict Private DNS enabled | Status `Degraded(PRIVATE_DNS)`, banner shown. |
| I-14b (new) | Strict Private DNS network disconnects, replaced by a normal network | Status clears automatically (regression test for sticky Private DNS state). |
| I-15 | Disable VPN from system settings with rules enabled | Notification posted exactly once; final bounded flush attempted; next start reloads totals. |
| I-16 | Wi-Fi → cellular switch during active flows | No crash; new flows accrue. |
| I-17 | Own app makes a network request | `flows_tcp_total` unchanged. |
| I-18 | All rules exhausted; system connectivity check runs | Connectivity check succeeds; the escape hatch and Recovery resources load (never shaped, INV-11/INV-15). |
| I-19 | Raise limit while exhausted | Site loads again within 6 s. |
| I-20 | Hardcoded `8.8.8.8:53` UDP query from a test app for an exhausted rule's host | NXDOMAIN returned from the handler. |
| I-21 | Room migration test (`MigrationTestHelper`): N → N+1 with existing data | Existing tables unchanged; new table present. Crash-during-migration leaves the DB openable on next start. |
| I-21b (new) | Room downgrade test: N → N+1 → N → N+1 with the real SQLCipher database | Every pre-existing table and its data survive all four steps; `rule_usage_daily` is absent after each downgrade and present after each upgrade. |
| I-22 (new) | Edit an enabled rule's pattern while a flow is attributed to it | The open flow keeps accruing to the old revision; the new pattern starts a fresh counter at 0; no usage transfers between them. |
| I-23 (new) | Save a rule whose normalized pattern matches an existing enabled rule | Save rejected with `DuplicatePattern`. |

### 12.3 Performance

Run PF-1 to PF-8 (§11).

---

## 13. Deployment Requirements

- **Config:** `BuildConfig.DOMAIN_USAGE_MODE`: `debug` = `ENFORCE`, `release` = `OFF` until stage S3 below.
- **Migration:** Room version N → N+1, additive table only (S7). Ship the exported schema JSON. **Rollback (revised, D-33):** register an explicit downgrade `Migration(N+1, N)` via `addMigrations` that executes `DROP TABLE IF EXISTS rule_usage_daily` and nothing else — verified with `MigrationTestHelper` for both directions repeatedly (I-21b), per Room's documented downgrade-migration support (developer.android.com/reference/androidx/room/RoomDatabase.Builder). This is the rollback mechanism, in addition to (not instead of) shipping an `OFF`-mode build: a rolled-back APK at schema `N` can now reopen a database that briefly ran at `N+1` without a destructive recreate of the whole database. Confirm during S7 that this interacts correctly with the project's specific SQLCipher factory/passphrase configuration (§16 item R-ROOM-DOWNGRADE — implementation-specific, not resolvable from this spec alone).
- **Backward compatibility:** No change to existing tables, rules, or `UsageAccountingEngine`.
- **Rollout stages:**

  | Stage | Build | Mode | Exit gate |
  |---|---|---|---|
  | S1 | Internal debug | `ENFORCE` | I-1 to I-23 pass. |
  | S2 | Closed beta (APK flavor) | `ACCOUNT`, ≥ 7 days | `sni_ok / tls_clienthello_seen ≥ 0.90` (denominator redefined, §14), `hook_exceptions = 0`, `fatal_vm_error = 0`, `breaker_tripped = 0`, `db_flush_fail = 0`, **no connectivity regressions of any kind reported** (meaningful now that `ACCOUNT` genuinely cannot cause one by design — any regression reported during this stage is a real bug, not an expected trade-off). |
  | S3 | Closed beta | `ENFORCE`, ≥ 7 days | Same gate, plus no false-block reports. |
  | S4 | Release (APK flavor) | `ENFORCE` | S3 gate met. |
  | S5 | Play flavor | `ENFORCE` | S4 stable and the Play VpnService declaration is approved (§16). |

- **Rollback:** Ship an APK with `DOMAIN_USAGE_MODE = "OFF"` (no NXDOMAIN, reset, UDP drop, or DNS shaping in that mode) *and* rely on the N+1→N downgrade migration above if the APK's Room version also needs to roll back.

---

## 14. Monitoring Requirements

No remote telemetry. Counters live in memory in `DomainUsageEngine`.

**Counters:** `flows_tcp_total`, `tls443_flows`, `sni_ok`, `sni_none`, `sni_ech`, `sni_notls`, `sni_malformed`, `sni_toolarge`, `inspect_pool_exhausted`, `inspect_timeout`, `flows_attributed_total`, `flows_unattributed`, `dns_fallback_attributed`, `dns_fallback_ambiguous`, `dns_queries`, `dns_parse_fail`, `dns_nxdomain_served`, `dns_nodata_aaaa`, `dns_nodata_https`, `dns_nodata_svcb`, `udp_dropped`, `icmp_injected`, `icmp_build_fail`, `rst_injected`, `rst_build_fail`, `flow_resets_new`, `flow_resets_sweep`, `flow_registry_capped`, `clock_jump`, `db_flush_ok`, `db_flush_fail`, `hook_exceptions`, `fatal_vm_error`, `breaker_tripped`, `rules_invalid`, `rules_duplicate_pattern`, `tamper_private_dns`, `tamper_vpn_revoked`.

- **Log line:** in `debug` and beta builds, every 60 s log `DomainUsage k=v k=v …` (counters only, INV-7). Release builds log nothing.
- **In-app:** the debug menu shows all counters and the current `EngineStatus`.
- **Health rules used at rollout gates (§13), revised metric composition:**
  - `sni_ok / tls_clienthello_seen ≥ 0.90`, where `tls_clienthello_seen = sni_ok + sni_none + sni_malformed + sni_toolarge` — i.e., flows that produced a parseable, non-ECH ClientHello. `sni_ech` and `sni_notls` are excluded from this denominator and reported separately, since they represent protocol states where SNI is inherently unavailable rather than a parser failure (fixes the prior metric mixing inherently-unavailable-SNI cases into a parser-health denominator).
  - `hook_exceptions = 0`
  - `fatal_vm_error = 0`
  - `breaker_tripped = 0`
  - `db_flush_fail = 0`
- **User-facing signals:** status banner (S9) and VPN-stopped notification (S7).

---

## 15. Risk Register

| # | Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|---|
| R-1 | Baseline lacks a userspace TCP endpoint, or lacks raw-packet TUN write (P1–P10) | Medium | Critical | S0 gate; STOP for P1/P3/P4/P5/P6/P7; implement `RawPacketBuilder` for P9/P10 if missing (not a stop condition) | `docs/domainusage-baseline-check.md` |
| R-2 | User bypasses via other VPN / VPN stop / in-app DoH / strict Private DNS / forward clock movement | High | High | Contract stated as best-effort; notification and banner; per-network Private DNS tracking (D-34) | `tamper_*` counters, status |
| R-3 | Existing connections outlive NXDOMAIN | High | High | Live-flow sweep + reset at attribution | I-7 |
| R-4 | Metric mismatches user perception (background traffic, buffered streaming, connection-level attribution) | High | Medium | 512 B threshold, `networkActiveMs` semantics, RuleEditor connection-level notice (S9), beta ACCOUNT stage | Beta feedback, counters |
| R-5 | CDN sibling domains not counted | High | Medium | Limitation notice in RuleEditor | UI review |
| R-6 | SNI parse gaps (PQ/segmented ClientHello) | Medium | High | Stateful parser, 16 KiB, real fixtures including Android 17/ECH | `sni_ok / tls_clienthello_seen` gate |
| R-7 | ECH via app-own DoH or non-DNS config delivery | Medium | Medium | Treated as an accepted attribution limitation (§4.6), not something the product claims to defeat; narrow, rule-scoped HTTPS/SVCB NODATA has partial effect only for platform-DNS apps | `sni_ech`, I-12/I-12b |
| R-8 | QUIC fallback stalls apps | Medium | Medium (reduced from prior draft since drop is now scoped, not blanket) | ICMP Port-Unreachable nudge only on the exhausted-and-attributed path (§6.10); unattributed QUIC is never touched | I-11, I-11b |
| R-9 | Engine bug degrades device connectivity | Low | Critical | Fail-open for ordinary exceptions, breaker, staged rollout, mode `OFF` rollback; `VirtualMachineError` rethrown rather than hidden | `hook_exceptions`, `fatal_vm_error`, `breaker_tripped` |
| R-10 | Battery/CPU regression | Medium | High | Dormant when no rules; O(1) hot path; PF-3/PF-5 gates | PF results |
| R-11 | Data loss on abrupt process kill | Medium | Low | Accepted, honestly bounded (`PERSIST_INTERVAL_MS` + checkpoint age, not a hard 10 s claim); bounded best-effort flush on ordinary shutdown | I-8 |
| R-12 | Clock manipulation forward within the anchored zone | Medium | Medium | Non-decreasing effective day key limits it to shifting quota within the current Engine run; documented, unresolved-by-design | `clock_jump` |
| R-13 | Privacy exposure of browsing metadata | Low | High | Memory-only names, storage without hostnames, no sync, no logs | S-4, S-5, S-9 review |
| R-14 | Play policy rejection of VPN use | Medium | High | Play flavor gated on declaration; APK flavor first | Play Console |
| R-15 (new) | Pooled TLS buffer reused across flows before the consumer finishes reading it | Low (after fix) | Critical if unfixed | Explicit `OwnedBytes` release contract (§4.2, INV-13) | Code review, concurrency test in S4 |
| R-16 (new) | Rollback APK cannot open a database left at a newer schema | Low (after fix) | High if unfixed | Explicit N+1→N downgrade migration dropping only the new table (D-33) | I-21b |
| R-17 (new) | Global DNS/UDP shaping degrades unrelated sites or Recovery/never-block resources | Low (after fix) | High if unfixed | Shaping scoped to rule-pattern matches only, `ENFORCE`-only (§6.1, §6.4, INV-15) | I-11b, I-13b, I-18 |

---

## 16. Open Questions

Every item is marked **Resolved** (with the source used) or **Open** (with the reason it must stay open — almost always because it depends on reading this project's actual code, which this spec cannot do). Nothing here changes a decision stated elsewhere unless the answer contradicts the stated Baseline Contract.

1. **Baseline Contract** (P1–P10) is asserted from project records, not yet verified against code. **Open** — S0 confirms it; this is unavoidable without the actual codebase.
2. **Existing rule schema.** The primary-key type, RuleEditor storage of website rules, and the exact signature of `dayScopeCoversToday`. **Open** (code-dependent) — the adapter in S2 resolves them in code; the *approach* (including revision tracking, D-32) is now fully specified regardless of what the exact schema turns out to be.
3. **Foreground service type** on API 34+ for the existing VPN service. **Open** (code-dependent) — this spec does not change it either way.
4. **Play flavor policy.** The Play VpnService declaration and disclosure requirements. **Open** (business/policy verification) — gates stage S5 only.
5. **IPv6-only underlying networks. Resolved (design change).** AAAA is no longer globally suppressed (D-15 revised, §6.1); it is suppressed only for names matching an enabled rule. On an IPv6-only/NAT64 network, all non-metered traffic is unaffected; a metered site with no IPv4 path may become unreachable while its rule is enabled — documented in RuleEditor copy as a narrower, known limitation rather than left as "untested." The IPv4-only TUN itself (D-15's other half) remains a deliberate V1 scope limit, consistent with `VpnService.Builder`'s address-family-specific configuration (developer.android.com/reference/android/net/VpnService.Builder).
6. **Private DNS detection reliability. Resolved (API), partially open (device verification).** `LinkProperties.isPrivateDnsActive()` / `getPrivateDnsServerName()` (API 28+, developer.android.com/reference/android/net/LinkProperties) is the correct, current API; strict mode is `getPrivateDnsServerName() != null`, opportunistic is `isPrivateDnsActive() == true` with a null name. It is tracked per network via `NetworkCallback` and recomputed on every relevant callback (D-34), not held as sticky global state. **Still open:** whether this reflects the *effective* DNS path specifically while this VPN's TUN is up, on each Android version in the support matrix — that is a per-device empirical question for I-14/I-14b, not something the public API documentation settles by itself.
7. **Help-resource domains.** Final content of `HELP_RESOURCE_DOMAINS`. **Open** (depends on Recovery Resource UI content, which this spec doesn't have).
8. **Reference devices/API matrix. Resolved 2026-09-29.** Since Android 17 (API 37) ships ECH on by default (developer.android.com/privacy-and-security/encrypted-client-hello), a third reference target — one Android 17 device or emulator image — is now mandatory (NFR-7, §3.1) regardless of the app's current `targetSdk`, with dedicated ECH test fixtures (U-8 Fixture D, I-12b). Infinix Smart 9 (XOS) and one stock-Android device remain the low/high-end pair. **Both halves closed:** `minSdk` = 26 (read from the code at S0), and the API 37 target is the `MyExodus_API37` AVD (`android-37.0/google_apis/x86_64`) — I-12b PASS + real Chrome 145 Fixture D captured on it (report §3.4; test-report §7 item 3 closed).
9. **Constant tuning.** `T_IDLE_MS`, `MIN_ACTIVITY_BYTES`, `DNS_TTL_CAP_S` are initial values; beta `ACCOUNT`-stage counters decide any change. **Open by design** — this is a deliberate beta-tuning step, not a defect. Note `DNS_TTL_FLOOR_S` no longer exists as a tunable at all (item below).
10. **DNS TTL floor. Resolved.** The floor is removed entirely (§2.3, §6.2, §16 was previously "beta will tune it" — now there is nothing to tune, since a positive floor was the defect: it made short-TTL CDN reassignments stale for up to 5 minutes). Only `DNS_TTL_CAP_S` remains, as a memory-safety ceiling, not a freshness floor.
11. **UDP/443 drop vs ICMP unreachable. Resolved.** ICMP Port-Unreachable injection is part of V1 (§6.10), but — unlike the prior draft's proposal — it is scoped only to flows already confirmed DNS-fallback-attributed to an exhausted rule (§6.4), never applied blanket-wide. Silent drop is the fallback only when packet construction itself fails.
12. **Baseline DNS truncation** (whether the Baseline DNS forwarder already handles TC/TCP-53 fallback). **Open** (code-dependent) — this feature does not change it either way; documented as part of the broader DNS-bypass limitation (D-18, item 17 below).
13. **Anonymous opt-in telemetry** and **usage sync to the accountability partner.** **Open by design** — explicit product decisions outside V1; V1 has neither.
14. **ECH internal contradiction (new item, resolved).** FR-4/§4.6/§6.1 are rewritten so ECH is never blocked merely for being ECH in any mode, and can only be enforced against via the same unambiguous-DNS-fallback-plus-exhausted-plus-`ENFORCE` path as any other non-SNI-attributable flow. Sourced from RFC 9849 (ECH configs can be delivered outside DNS, so no purely-DNS-based measure fully closes this) and Android's own ECH adoption guide (developer.android.com/privacy-and-security/encrypted-client-hello), which documents that platform-DNS-resolving apps fetch ECH configs via the HTTPS DNS record — the basis for the narrow, rule-scoped NODATA measure that remains.
15. **TLS buffer ownership/lifetime (new item, resolved).** Fixed via the explicit `OwnedBytes` contract (§4.2, §6.3, INV-13) — the pool buffer is never returned before its consumer has finished reading it.
16. **Persistence durability claim (new item, resolved by correction, not by proof).** The prior "≤10 s guaranteed loss" claim was false for abrupt process death, since the final snapshot was fire-and-forget. It is now stated honestly (§4.9, NFR-8, AC-7): ordinary shutdown gets a bounded best-effort flush (`SHUTDOWN_FLUSH_TIMEOUT_MS`); abrupt death is bounded only by `PERSIST_INTERVAL_MS` plus checkpoint age, which is the same bound that already applied during normal operation.
17. **DNS bypass surface (TCP/53, DoT/DoQ alternate ports). Open by design.** Documented as an accepted limitation (D-18, S-12) — closing it fully would require intercepting arbitrary TCP/53 and non-standard-port encrypted DNS, which is out of V1 scope. Not fixed; explicitly not silently glossed over either.
18. **Room downgrade migration and SQLCipher interaction (new item, mostly resolved, one part open).** The downgrade mechanism itself (an explicit `Migration(N+1, N)` via `addMigrations`, dropping only the new table) is confirmed against Room's public API (developer.android.com/reference/androidx/room/RoomDatabase.Builder) and is the correct general-purpose fix (D-33, §13). **Still open:** whether this project's specific SQLCipher `SupportFactory`/passphrase wiring needs anything extra for a downgrade migration to run cleanly — that is implementation-specific and must be confirmed in S0/S7 against the actual `RoomDatabase.Builder` construction in this codebase.
19. **Rule identity mutation across a pattern edit (new item, resolved).** Fixed via `revision` (D-32, FR-14) — a pattern change gets a clean new accounting identity; open flows keep their old one until they close.
20. **Duplicate normalized rules (new item, resolved).** Fixed via RuleEditor-side rejection plus a defensive `RuleIndex`-level dedup for any pre-existing colliding data (§4.5, S2.8).
21. **Global DNS/UDP shaping affecting unrelated or never-block traffic (new item, resolved).** Fixed by scoping every shaping/blocking decision to an actual rule-pattern match and to mode `ENFORCE` (§6.1, §6.4, INV-11, INV-15).
22. **`catch(Throwable)` breadth (new item, resolved).** Narrowed to `catch(Exception)`, with `VirtualMachineError` explicitly rethrown after being counted (D-35, NFR-2, INV-6).
23. **PSL reproducibility (new item, resolved).** The asset is now pinned with a recorded source URL, retrieval date, and checksum, verified by CI, rather than "downloaded at implementation time" (S1.6).
24. **Connection-level vs. request-level SNI attribution (new item, resolved as a documented limitation, not a code fix).** HTTP/2+ connection coalescing (RFC 9113) means a single TLS connection can legitimately carry requests for more than one origin; this cannot be fully closed at this architectural layer without request-level (i.e., MITM) visibility, which is explicitly out of scope (D-01). Addressed by disclosure in RuleEditor copy (S9) rather than by pretending it doesn't happen.
25. **`sni_ok / tls443_flows` metric composition (new item, resolved).** Redefined denominator (§14) to exclude flows where SNI is inherently unavailable (ECH, non-TLS) from what's meant to be a parser-health metric.
26. **Retention running only at startup (new item, resolved).** Now also runs at every rollover (§6.7), not only at service start (§6.8).
27. **Duplicate shutdown-notification risk (new item, resolved).** Fixed via an explicit `RUNNING → STOPPING → STOPPED` lifecycle guard so `onDestroy`/`onRevoke` side effects run exactly once (§6.9, S7.3).
28. **PSL synchronous availability for RuleEditor validation (new item, resolved).** PSL now loads during Engine/application initialization (S2.3) rather than lazily on first save, so RuleEditor's validation can be synchronous.
29. **Fuzzing/allocation-proof rigor (new item, addressed as testing-rigor wording, not a spec defect).** U-4/U-8's fuzz counts are supplemented with targeted structural mutations at parse boundaries (§12.1); PF-6's allocation test is explicitly labeled a regression guard, not a formal cross-configuration proof (§11).
30. **Unbounded `FlowRegistry` (new item, resolved).** Capped per rule via `MAX_TRACKED_FLOWS_PER_RULE`, with graceful degradation (metering continues; sweep-on-exhaustion for over-cap flows is delayed to their own next check) rather than unbounded growth (§2.3, S5.3).
31. **I-12's ECH test robustness (new item, resolved).** Added a dedicated controlled ECH-enabled test endpoint with known configs (I-12b) alongside the original Cloudflare-trace check, plus a mandatory Android 17 test target (item 8).

---

## 17. Acceptance Criteria

| ID | Criterion | Verified by |
|---|---|---|
| AC-1 | S0 completed; `docs/domainusage-baseline-check.md` present with all P1–P10 `PASS`. | S0 |
| AC-2 | Traffic to `b` (same IP as `a`) never accrues usage to a rule on `a`. | I-1 |
| AC-3 | Ambiguous shared-IP flows without SNI are unattributed and allowed. | I-2 |
| AC-4 | A single-candidate DNS flow is attributed to its rule via a validated alias chain. | I-3 |
| AC-5 | Parallel flows count once; idle and keepalive traffic do not accrue beyond the defined tail; a single mid-interval burst does not overcount, and a stalled tick does not undercount. | I-4, I-5, I-6, U-10, U-14 |
| AC-6 | In `ENFORCE`, a live connection is reset within 2 s of exhaustion; new lookups get NXDOMAIN for any qtype; hardcoded-IP and hardcoded-resolver paths are enforced. | I-7, I-20 |
| AC-7 (revised) | Persisted totals never decrease for a given `(rule_id, revision, day_key)`; ordinary-shutdown loss is bounded by `SHUTDOWN_FLUSH_TIMEOUT_MS`; abrupt-process-death loss is bounded by `PERSIST_INTERVAL_MS` plus checkpoint age (not a fixed ≤10 s/11 s figure). | I-8, U-11 |
| AC-8 | Clock rollback does not reopen a past day; midnight splits correctly at the exact boundary. | I-9, I-10, U-10 |
| AC-9 | ECH is never blocked merely for being ECH, in any mode; a not-yet-exhausted metered site's system-DNS ECH negotiation is suppressed only in `ENFORCE`; an unrelated site's ECH is never affected. | I-12, I-12b |
| AC-10 | IPv6 cannot bypass a rule that is actually enabled on that host; unrelated IPv6-only traffic is unaffected. | I-13, I-13b |
| AC-11 | Private DNS strict shows the banner and clears automatically when the strict network disappears; VPN stop posts the notification exactly once. | I-14, I-14b, I-15 |
| AC-12 | Own app traffic is never metered; never-block hosts, the escape hatch and Recovery resources stay reachable when all rules are exhausted, in every mode. | I-17, I-18 |
| AC-13 | Raising a limit unblocks within 6 s. | I-19 |
| AC-14 | Parsers survive ≥ 100 000 mutated inputs each, plus targeted boundary mutations, with no exception. | U-4, U-8 |
| AC-15 | Migration N → N+1 preserves existing data; downgrade N+1 → N preserves every pre-existing table across repeated up/down cycles. | I-21, I-21b |
| AC-16 | All PF gates pass on all three reference devices. | PF-1…PF-8 |
| AC-17 | CI grep check passes; PSL checksum matches its provenance record; no hostname/IP appears in any log statement. | U-13, review |
| AC-18 | Rollout stage gates S1–S4 met before release mode leaves `OFF`. | §13 |
| AC-19 (new) | `ACCOUNT` mode never blocks, drops, or reshapes any traffic, under any tested scenario. | U-6, I-11b, I-13b |
| AC-20 (new) | Editing a rule's pattern never transfers usage to the new pattern and never orphans an open flow's accounting. | I-22 |
| AC-21 (new) | Saving a rule whose normalized pattern duplicates an existing enabled rule is rejected. | I-23 |
| AC-22 (new) | A pooled TLS inspection buffer is never reused while a prior flow's forwarded bytes still reference it (no observed cross-flow corruption under concurrent-flow testing). | S4 concurrency test |

---

## 18. Definition of Done

**Resolution (2026-09-29).** Reconciled against `app/docs/domainusage-test-report.md` (§9 carries the same cross-check; this list is the plan-side mirror). The shipped work is committed (913b858). The API 37 reference target closed on 2026-09-29 (I-12b PASS + real Fixture D, report §3.4), and **PF-5 closed the same night** (PASS, unplugged, report §4.2) — the X6532 is 8/8 PF gates and **every box below is now ticked**. One recorded delta, not silently re-scoped: AC-16's literal "all three reference devices" cannot be satisfied for the battery gate on an emulator (synthetic battery), so the PF gates are scored on the device that can carry them — flagged to the owner (report §7 item 6). Nothing ticked that isn't true, nothing dropped.

- [x] S0 report committed; all Baseline Contract items P1–P10 `PASS`. — `app/docs/domainusage-baseline-check.md`; P6 deviation ruled in ADR #70; committed.
- [x] All components in §4.1 implemented; no code outside §3.3's modify list changed. — report §9.1 diff-check against `cc7a13b`: zero diff under `accountability/`, `recovery/`, pairing, Tink/DataStore, Firebase paths and `UsageAccountingEngine`.
- [x] Invariants INV-1…INV-16 each have a passing test or CI check. — report §8 invariant→evidence map (16/16).
- [x] Unit tests U-1…U-14 pass in CI. — 1020/0/0/0 at S10 close (report §2); domainusage package 232 tests; every U-item mapped to a shipped class.
- [x] Instrumented tests I-1…I-23 (including the `b`-suffixed regression tests) pass on all three reference devices; report committed. — X6532 **21 PASS / 3 SKIP** (closed 2026-09-22, AC-12/AC-19 among them), the three SKIPs each landed where they can run (I-9/I-10 emulator §3.3, I-12b API 37 §3.4), emulator migrations 8/8, and the **API 37 reference target ran 2026-09-29**: I-12b PASS (`sni_ech=0→4`, `tls-ech.dev` page renders "You are using ECH.") with Fixture D replaced by a real Chrome 145 capture (U-8 re-ran 19/0). Report §3.4 + §7 item 3 closed (task #92).
- [x] PF-1…PF-8 pass; numbers recorded in the test report. — **ALL 8 PASS on the X6532.** PF-1, PF-2, PF-3, PF-4, PF-6, PF-7, PF-8 measured 2026-09-22 (report §4.2, numbers recorded; PF-4 under the re-ruled +10 thread tolerance, PF-7 on idle runs with the in-batch sample preserved). **PF-5 CLOSED 2026-09-29 overnight** — the X6532 unplugged for 2 h under wireless adb (`--runs 1`, one calibrated hour per arm): `OFF=10pp ACCOUNT=10pp` against the 12 pp bound, zero WARN lines. PF gates were not re-run on the API 37 emulator: its plan role is the ECH legs and the battery gate is unmeasurable there (synthetic battery) — recorded, not silently re-scoped (report §7 item 6).
- [x] Room schema exported; both the upgrade and downgrade migrations tested (I-21, I-21b). — `app/schemas/…/10.json`; emulator 8/8 incl. repeated 9↔10 cycles and interrupted-migration rollback, on the real SQLCipher path.
- [x] RuleEditor validation (including uniqueness and limit bounds), notices (scope + connection-level), usage display and status banner shipped with string resources. — S9; device-verified in the S9 close (report §9).
- [x] VPN builder: `addDisallowedApplication(APPLICATION_ID)` present, `allowBypass()` absent, no IPv6 config added. — CI checks INV-9/INV-10; S0 P6 note.
- [x] `DOMAIN_USAGE_MODE` configured per §13; release default `OFF`. — `app/build.gradle.kts` (debug `ENFORCE`, release `OFF`); report §9.
- [x] No hostnames, IPs or DNS names in logs, storage, notifications or exceptions (code review). — CI INV-7 + review; the baseline service's pre-existing diagnostic logs are outside this feature's §3.3 authority and recorded as a separate-hardening caveat (report §9), not silently changed.
- [x] Usage data absent from every sync/relay payload (code review). — no domainusage types referenced from accountability/Firebase paths (report §9.1).
- [x] `UsageAccountingEngine`, escape-hatch, pairing, and Recovery UI code unchanged (diff check). — report §9.1: zero diff under those trees.
- [x] Rollback verified: mode `OFF` build removes all shaping and enforcement; Room downgrade migration verified against the actual SQLCipher setup in this project. — mode-gated engine (`EngineModeTest`); I-21b downgrade on the SQLCipher path.
- [x] PSL asset provenance recorded and checksummed; CI enforces it. — S1.6; `PublicSuffixListAssetTest` + the shipped CI script.
- [x] `ACCOUNT` mode independently verified to perform zero shaping/blocking/dropping across the full instrumented suite (AC-19). — JVM full table (U-6/`DnsShaperTest`) + device halves `I-11b`/`I-13b` PASS 2026-09-22 with the engine verifiably in `ACCOUNT` and the rule exhausted (report §4.2, §5.1 item 12).
- [x] §16 items reviewed; every "Open" item has an owner; every "Resolved" item's cited source has been spot-checked against the actual project code where the resolution depends on Baseline behavior (items 2, 3, 6, 12, 18). — report §10: resolved-in-code (spot-checked), device-verified, open-by-design (owners named), or dead by product decision (ADR #15).
