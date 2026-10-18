# Domain Usage Metering — Implementation Log

**Plan:** `app/plans/myexodus-domain-usage-metering-implementation-plan (1).md` (final, post-audit revision 2).
**Step order:** the plan's §7 sequence, S0 → S11. S0's record lives in
`app/docs/domainusage-baseline-check.md`; its two rulings and the S1 check substitution are ADR #70.

This file is the per-step record: what landed, what deviated and why, and what was verified how.
It is updated at each step boundary per the plan-fidelity protocol.

---

## S1 — Foundation (2026-09-20)

**Landed**

| Requirement | Artifact |
|---|---|
| S1.1 package + `MeteringConfig` (§2.3) + `EngineMode` + `EngineStatus` | `domainusage/MeteringConfig.kt`, `EngineMode.kt`, `EngineStatus.kt` |
| S1.2 clocks | `domainusage/Clocks.kt` (`MonotonicClock`/`SystemMonotonicClock`, `WallClock`), `domainusage/SystemWallClock.kt` |
| S1.3 mode flag | `app/build.gradle.kts` — `buildConfigField("String", "DOMAIN_USAGE_MODE")`: debug `"ENFORCE"`, release `"OFF"`; override store in `domainusage/DomainUsageSettings.kt`; resolution in `EngineMode.effectiveMode` |
| S1.4 CI check | `scripts/domainusage-ci-checks.sh` (clock confinement), Gradle `domainUsageCiChecks` (Exec) wired into `check` |
| S1.5 circuit breaker | `domainusage/DomainUsageEngine.kt` — `hook()` (inline), `onHookException()`, dormant-until-restart, `Degraded(INTERNAL_ERROR)` |
| S1.6 PSL pinning | `app/src/main/assets/public_suffix_list.dat` + `.provenance` (source URL, retrieval date, list commit, SHA-256), verified by the same CI script |
| §14 counters | `domainusage/EngineCounters.kt` — all 38 names, `Counter.tlsClientHelloSeen()` for the revised health metric |

The `SystemWallClock.kt` file split is load-bearing: S1.4 allows wall-clock calls in that file
only, so the class is not in `Clocks.kt` even though it is one line.

**Deviations (recorded, not silent)**

1. **`WallClock.today(zone: ZoneId)` instead of the plan's `today()` no-arg** (S1.2). §4.8 requires
   the zone to be captured once in `start()` as `anchoredZone` and never re-read mid-session; a
   no-arg `today()` reading `ZoneId.systemDefault()` per call would violate that. The zone is a
   parameter; the Engine passes its anchored zone. The `ZoneId.systemDefault()` read the plan names
   happens once, in `start()` (S7).
2. **Filesystem `grep` instead of `git grep`** in the S1.4 check — ADR #70 §3.
3. **S1.3's DataStore override has no reader yet.** The mechanism (`DomainUsageSettings.modeOverride`,
   `EngineMode.effectiveMode`) is implemented and unit-tested, but nothing constructs a
   `DomainUsageEngine` until the S6/S7 wiring — carried forward on the S6 task entry.

**Verified**

- `./gradlew :app:compileDebugKotlin` green.
- `./gradlew :app:domainUsageCiChecks` green; `:app:domainUsageCiChecks` is the first node of the
  `:app:check` graph (`--dry-run`, verified).
- `./gradlew :app:testDebugUnitTest`: **789 tests, 0 failures, 0 errors, 0 skipped** (772 before S1;
  +17 new: `EngineModeTest` 6, `DomainUsageEngineBreakerTest` 7 = U-12, `DomainUsageCiChecksTest` 4 = U-13).
- U-13 is enforced against the real script: a planted `Instant.now()` in a `domainusage` test file
  (split-token literal, since the test file is itself inside `domainusage`) fails the check, a
  violation outside `domainusage` passes, and an appended-byte PSL asset fails the checksum.
- The S1.4 check caught the test file's own fixture literals on first run — evidence the check
  scans test sources as U-13 requires.

**Carried forward (open, tracked)**

- `./gradlew :app:check` cannot complete: `:app:lintAnalyzeDebug` fails resolving
  `debugLintChecksClasspath`, whose lock state still lists five artifacts no longer in the dependency
  graph (`firebase-appcheck-debug-testing:19.4.1`, `androidx.test:annotation:1.0.1`,
  `androidx.test:core:1.5.0`, `androidx.test:monitor:1.6.0`, `androidx.test.services:storage:1.4.2`).
  Pre-existing (the lock state is committed and unmodified), unrelated to S1, and not fixed here —
  regenerating the locks is the user's call. The static check runs green when invoked directly.
- Build flavors: plan §13 assumes a Play flavor and an APK flavor; `app/build.gradle.kts` currently
  has none (Play distribution was dropped — ADR #15). S11 decides the §13 mapping; nothing in S1–S10
  depends on flavors.
- S7's 60 s counter log line (§14) is not written yet; it lands with the status/lifecycle step, where
  the debug-only gate lives.

---

## S2 — Host handling and rules (2026-09-20)

**Landed**

| Requirement | Artifact |
|---|---|
| S2.1 `HostNormalizer.normalize` (S2.1 result set: `Ok`/`HasPath`/`IpLiteral`/`TooBroad`/`NeverBlock`/`EncryptedDnsResolver`/`Invalid`) | `domainusage/HostNormalizer.kt` |
| S2.2 `normalizeLegacy` (discards path/query/fragment, same checks) | same file (`pathIsError = false`) |
| S2.3 `PublicSuffixList` with synchronous `fromAsset` + `parse` | `domainusage/PublicSuffixList.kt` |
| S2.4 `NeverBlockDomains` (probes + `HELP_RESOURCE_DOMAINS`, exact + subdomain) | `domainusage/NeverBlockDomains.kt` |
| S2.5 `EncryptedDnsResolvers` (8 hosts, exact + subdomain) | `domainusage/EncryptedDnsResolvers.kt` |
| S2.6 `DomainRuleProvider` (`rules` Flow + `snapshot()`, clamp, `rules_invalid`, revision) | `domainusage/DomainRuleProvider.kt` |
| S2.7 `RuleIndex` per §4.5 (longest-wins to 2 labels, never-block/resolver null, dedup + `rules_duplicate_pattern`) | `domainusage/RuleIndex.kt` |
| S2.8 FR-2 uniqueness check (exact pattern, other rule only) | **Superseded at S9** — the editor needs the collision set over raw rows (paused/expired included), so the check lives in `ui/rules/RuleEditor.kt` (`siteLimitTarget` + `siteLimitPatternsOf`, tested in `SiteLimitTest`); the anticipatory `RuleIndex.duplicatePatternFor` was production-dead and removed (see the S9 entry) |
| FR-1 rule model | `domainusage/DomainRule.kt` |

**Deviations and interpretations (recorded, not silent)**

1. **`HostNormalizer.normalize(input, psl)` carries the PSL in the signature** — the plan writes
   `normalize(input)`. The list is loaded once at init (S2.3) and passed explicitly (project rule:
   explicit dependencies, no hidden shared state).
2. **`HasPath` is returned only when the host itself validates.** The plan's literal step order puts
   the path check before the IP/label/PSL checks; here the host portion runs the full pipeline first,
   so `1.2.3.4/admin` reports `IpLiteral`, not a suggestion the next save would reject. The normal
   case (`example.com/forum`) is unchanged and is what U-1 pins.
3. **`_` labels are rejected for rule input.** The plan's parenthetical "`_` … for SNI matching only"
   is satisfied on the S4.3 parser side, which validates SNI names itself with `[a-z0-9._-]`; no
   unused `allowUnderscore` flag was added to the normalizer (no rule pattern can contain `_`, so an
   underscore SNI simply matches no rule).
4. **`id` is the existing rule id as-is, and `pattern` comes from `UrlPattern.hostAscii`.** Rule ids
   in this codebase are Strings (the plan's `existingId.toString()` assumes numeric); `hostAscii` is
   the ASCII/punycode canonical host the SNI and DNS paths compare against.
5. **`dailyLimitMs <= 0` is "limit not enabled", not a clamp target.** The plan's clamp governs
   positive values outside `[MIN_DAILY_LIMIT_MS, MAX_DAILY_LIMIT_MS]`; 0 is the app's own
   "always-on / no limit" convention (`RuleEditor`: `dailyLimitMs > 0` ⇔ limit enabled), and a
   negative stored value is degenerate data, so it is treated as disabled rather than clamped.
6. **Revisions start at 0 in memory** and increment only on an observed pattern change (D-32 as
   written); the counter resets with the process (see the carry-forward below).

**Verified**

- `./gradlew :app:compileDebugKotlin` green.
- `./gradlew :app:testDebugUnitTest --tests 'com.example.myexodus.domainusage.*'`: **52 tests,
  0 failures** (17 before S2; +35: U-1 `HostNormalizerTest` 14, U-2 `PublicSuffixListTest` 5 +
  `PublicSuffixListAssetTest` 1, U-3 `RuleIndexTest` 9, `DomainRuleProviderTest` 6).
- `./gradlew :app:domainUsageCiChecks` green (the new test files contain no wall-clock tokens).
- Full suite `./gradlew :app:testDebugUnitTest`: **824 tests, 0 failures, 0 errors, 0 skipped**
  (789 before S2).

**Carried forward (open, tracked)**

- **PSL synchronous load at init (S2.3) has no call site yet** — `PublicSuffixList.fromAsset` is
  built and tested, but the Engine that owns the single instance is constructed at S6/S7; wired there.
- **S2.8's editor wiring is S9 per §7** — closed at S9, but not through `RuleIndex.duplicatePatternFor`:
  the editor's collision set spans raw rows the index never holds (paused/expired limits), so the check
  was rebuilt in the editor against `HostNormalizer.normalizeLegacy` and the dead index helper removed
  (see the S9 entry).
- **Plan gap flagged for ruling (D-32 × §6.8):** revisions are in-memory (S2.6 as written), but
  §6.8's startup reload keys today's totals by `(rule_id, revision)`. After a process restart the
  provider's counter is back at 0 while today's row may be at revision N, so a pattern edited before
  the restart would lose today's already-counted usage for that rule. Implemented plan-literally;
  the fix (seed the counter from the persisted `MAX(revision)` per rule id) belongs to S7's
  persistence step and waits on a user ruling.

## S3 — DNS message layer, shaper, observation store

**Landed**

| Requirement | Artifact |
|---|---|
| S3.1 `DnsMessage` parser (12-byte header, QDCOUNT/opcode check, ≤32 pointer hops, ≤255 name bytes, backward pointers only, ≤64 records, owner/type/class/TTL/RDATA preserved, A + CNAME extracted, other types skipped by RDLENGTH) | `domainusage/DnsMessage.kt` (`parse`, `Parsed.Query`/`Response`/`NotStandard`/`Failed`) |
| S3.2 Response builder (flags QR=1/AA=0/TC=0/RD copied/RA=1, ID copied, question echoed, ANCOUNT=0/NSCOUNT=1/ARCOUNT=0, SOA authority per §8) | `domainusage/DnsMessage.kt` (`buildNegativeReply`) |
| S3.3 `DnsShaper.decide` per §6.1's mode- and rule-scoped table; counters `dns_queries`, `dns_nxdomain_served`, `dns_nodata_aaaa`, `dns_nodata_https`, `dns_nodata_svcb`, `dns_parse_fail` | `domainusage/DnsShaper.kt` |
| §6.2 alias-chain response walk | `DnsShaper.onResponse` (owner must equal the current chain target; CNAME advances; A stores `ip → QNAME`) |
| S3.4 `DnsObservationStore` (synchronized, 16-byte IPv4-mapped key, access-ordered LRU on IPs, ≤8 names per IP, monotonic-only expiry) | `domainusage/DnsObservationStore.kt` |
| S3.5 Baseline wiring | **Deferred to S6** — §7's step table places it there (`S6: Attribution + Baseline flow/DNS wiring; mode- and rule-scoped shaping`); see carry-forwards |

**Deviations and interpretations (recorded, not silent)**

1. **`Parsed.NotStandard` is distinct from `Parsed.Failed`.** §6.1 step 2 requires both a parse
   failure and a non-standard query to `Forward`; the plan does not say whether QDCOUNT ≠ 1 /
   opcode ≠ 0 count as `dns_parse_fail`. They are split so `dns_parse_fail` stays a structural-
   fidelity signal (§14 health): malformed bytes ⇒ `Failed` (counted), well-formed but
   non-standard ⇒ `NotStandard` (not counted). Both forward unmodified and neither observes.
2. **`DnsShaper.decide` takes `isExhausted: (DomainRule) -> Boolean`** rather than reaching into
   the ledger. §6.1 sentence 3 is mode- and rule-*match*-scoped; the exhaustion fact is owned by
   the ledger (S5) and supplied by the Engine, keeping the shaper a pure decision function of its
   inputs (explicit dependencies).
3. **The §6.1 table is implemented literally: it does not consult `enforcedToday`.** Day scope
   gates *accrual* (§6.5 step 4 / U-10), not shaping. Consequence, flagged for ruling: on a day
   outside a rule's active scope, AAAA/SVCB/HTTPS queries to that rule's host still receive NODATA
   (IPv4 fallback, the D-14 SNI-visibility measure) even though the rule is not tracking that day.
   If unintended, the fix is one line — skip the NODATA branch when `!rule.enforcedToday`.
4. **Name equalisation.** All parsed names (question, owner, CNAME target) are lowercased at parse
   time; the echoed question section keeps the query's original casing byte-for-byte (S3.2) so
   answer matching is case-insensitive without altering what goes back on the wire.
5. **Zero-length TTL is honored** (`clamp(ttl, 0, DNS_TTL_CAP_S)`, no floor) — a 0-TTL answer is
   stored and is immediately expired, matching §16 item R-TTL-FLOOR and U-7.

**Verified**

- `./gradlew :app:compileDebugKotlin` / `:app:compileDebugUnitTestKotlin` green.
- `./gradlew :app:testDebugUnitTest --tests 'com.example.myexodus.domainusage.*'`: **89 tests,
  0 failures** (52 before S3; +37: U-4 `DnsMessageTest` 9, U-5 `DnsReplyBuilderTest` 3,
  U-6 `DnsShaperTest` 16, U-7 `DnsObservationStoreTest` 9).
- Requirement-by-requirement: U-4 covers compression loops (self/forward pointers), truncation,
  oversize label/name, QDCOUNT ≠ 1, owner tracking through a CNAME chain, targeted structural
  mutations at every header/count/pointer position, and 100 000 seeded fuzz mutations with no
  throw; U-5 asserts re-parse, question echo (casing preserved), header flags/counts and the full
  SOA (TTL + minimum = negative TTL, MNAME/RNAME, serial/refresh/retry/expire, RDLENGTH
  consistency); U-6 covers every qtype × {`OFF`,`ACCOUNT`,`ENFORCE`} × {no match, live,
  exhausted} with `OFF`/`ACCOUNT` asserted never to shape; U-7 covers TTL 0, the TTL cap,
  per-IP eviction, IP-LRU eviction, refresh-on-re-record, expiry, clear, and the 4-byte/
  IPv4-mapped key equivalence.
- `./gradlew :app:domainUsageCiChecks` green.
- Full suite `./gradlew :app:testDebugUnitTest`: **861 tests, 0 failures, 0 errors, 0 skipped**
  (824 before S3).

**Carried forward (open, tracked)**

- **S3.5 Baseline wiring is S6 per §7** — the dispatcher route for UDP/53, the
  `onDnsQuery`/`onDnsResponse` call sites, the `Respond` src/dst swap, and the `clear()` call in
  `stop()` (S3.4's own line) are all Baseline/Engine wiring and land with S6.
- **`enforcedToday` × NODATA (deviation 3 above) awaits a user ruling** — one-line fix available.

## S4 — TLS ClientHello parser, buffer pool

**Landed**

| Requirement | Artifact |
|---|---|
| S4.1/S4.2 `TlsClientHelloParser.feed(bytes, off, len)` — result set `NeedMore`/`Sni`/`Ech`/`NoSni`/`NotTls`/`Malformed`/`TooLarge`; records walked as one logical handshake stream, extension scan, ECH wins over SNI | `domainusage/TlsClientHelloParser.kt` |
| S4.3 Bounds — every length checked against its enclosing structure; SNI decoded as ASCII only after validation (`[a-z0-9._-]`, ≤ 253 bytes, labels 1–63) | same file (`Cursor.fits`, `readServerName`, `validateHost`) |
| S4.4 `InspectionBufferPool` — `INSPECT_POOL_SIZE` pre-allocated buffers, `ConcurrentLinkedDeque` (lock-free), `acquire()` null + `inspect_pool_exhausted` when empty, slot returned only inside `OwnedBytes.release()` (idempotent) | `domainusage/InspectionBufferPool.kt`, `domainusage/OwnedBytes.kt` |
| S4.6 result → counter mapping (`sni_ok`/`sni_none`/`sni_ech`/`sni_notls`/`sni_malformed`/`sni_toolarge`; `NeedMore` counts nothing) | `TlsClientHelloParser.countInto` |
| U-8 fixtures | `app/src/test/resources/tls/fixture_{a,b,c,d}_*.bin` |
| S4.5 Inspection timeout | **Deferred to S6** — see carry-forwards |

**Deviations and interpretations (recorded, not silent)**

1. **Fixtures B/C were synthetic when S4 landed; both are now real captures (S10).** U-8 names a
   Chrome-stable capture (PQ, and ECH) plus a Chrome capture on an Android 17 device. As originally
   landed, B/C/D were generated Chrome-shaped ClientHellos (full Chrome extension order,
   `X25519MLKEM768` key share, `0xFE0D` with a plausible outer `ECHClientHello` payload); A is
   genuine (`ssl.MemoryBIO` `wrap_bio(server_hostname="example.com")`, OpenSSL bytes).
   During S10 the reference X6532 ran Chrome 153.0.8010.47 against a host listener
   (`scripts/domainusage-tls-hello-capture.py`); **B and C were replaced with captures from that
   session** — B is `hello-01.bin` (arrived as two TCP segments, 1448 B + 546 B, pcap-verified) and
   C is `hello-06.bin` (smallest-ECH in the session). All 20 captures carried `0xFE0D`: Chrome
   GREASEs ECH toward hosts publishing no ECH config, and RFC 9849 §6.2 makes GREASE
   indistinguishable from a real config by design, so no Chrome hello *without* ECH exists to
   capture and B/C are `Result.Ech` with their plaintext SNI (a spec-mandated public cover name,
   `du-b.192.0.2.99.nip.io`) deliberately ignored. Per-fixture source, date, device, client and
   SHA-256 live in `app/src/test/resources/tls/FIXTURES.provenance`. **Fixture D is now a real
   capture too (2026-09-29, #92):** Chrome 145.0.7632.218 on the API 37 (`MyExodus_API37`) image
   against the same host listener, `hello-03.bin` of 3 (1730 B, ECH extension 186 B, same
   smallest-ECH convention as C); same GREASE-ECH shape, plaintext SNI `du-b.10.0.2.2.nip.io`
   ignored, and it carries the same X25519MLKEM768 (0x11EC) key-share group, which its U-8 case
   asserts. The report's §3.4 carries the companion I-12b run on the same image.
2. **A non-ClientHello handshake message ⇒ `NoSni`, not `Malformed`.** §8 S4.2 fixes `NotTls` for
   the record-level checks but does not name the handshake-type mismatch. A well-formed TLS
   handshake that simply is not a ClientHello (e.g. a ServerHello) carries no SNI and is not
   structurally broken, so it takes the `NoSni` path — which §4.6 attributes identically
   (DNS fallback) while keeping `sni_malformed` a true parser-health signal.
3. **A handshake stream interrupted by a non-handshake record ⇒ `Malformed`.** Also unnamed in
   §8; "concatenate consecutive handshake-record payloads" is the structure, so a different record
   type where the continuation is required is a structural violation. Same §4.6 consequence as
   `NoSni`; it counts `sni_malformed`.
4. **SNI names are lowercased *before* the character-set validation.** §8 S4.3 lists
   "validated … lowercased"; validating first would reject a mixed-case SNI outright. DNS names
   are case-insensitive (`RuleIndex.match` lowercases too), so casing is normalized and the
   lowercased form is what is validated — `MiXeD.Example` ⇒ `Sni("mixed.example")`.
5. **The first `name_type == 0` entry is the one that counts**, even when its name is invalid
   (⇒ `NoSni`); later `host_name` entries in the same list are skipped. §8 says "take the first
   `name_type == 0`", so a later entry cannot rescue an invalid first one.
6. **A record may be cut short by the caller's range.** The parser decides `NeedMore` only when it
   actually needs a byte that has not arrived, rather than requiring a whole declared record
   payload up front. This is what makes the every-byte-boundary split decide *exactly* on the
   last byte of the ClientHello; it never reads past `off + len` (pinned by U-8's fuzz
   differential — bytes placed past `len` cannot change the result).

**Verified**

- `./gradlew :app:compileDebugKotlin` / `:app:compileDebugUnitTestKotlin` green.
- `./gradlew :app:testDebugUnitTest --tests 'com.example.myexodus.domainusage.*'`: **111 tests,
  0 failures** (89 before S4; +22: U-8 `TlsClientHelloParserTest` 18, `InspectionBufferPoolTest` 4).
- Requirement-by-requirement: fixtures A (real `ssl.MemoryBIO` ClientHello ⇒ `Sni("example.com")`),
  B (real Chrome PQ + GREASE ECH ⇒ `Ech`, ignored plaintext SNI, `0x11ec` key share asserted),
  C/D (ECH ⇒ `Ech`, plaintext SNI ignored); every
  fixture fed at **every** byte boundary (all `NeedMore` until the final byte, with trailing junk
  past `len` proving no read past the buffer) and re-framed across **two TLS records** (split
  points 1–600 and the last byte); multiple extensions with SNI last; a non-`host_name` entry
  first; missing extensions ⇒ `NoSni`; wrong content type and record version ⇒ `NotTls`; oversize
  declared length ⇒ `TooLarge`; record length > 16384, session-id/extension-block/extension
  overflow, partial extension header and odd cipher-suite length ⇒ `Malformed`; hostname
  validation (lowercasing, `_`, empty name, space, `/`, leading/trailing dot, label > 63,
  > 253 bytes, non-ASCII) ⇒ `NoSni`; counter mapping for all six terminal results; 100 000
  seeded fuzz mutations with the differential no-read-past-`len` assertion plus targeted
  structural mutations at every early field boundary.
- `./gradlew :app:domainUsageCiChecks` green.
- Full suite `./gradlew :app:testDebugUnitTest`: **883 tests, 0 failures, 0 errors, 0 skipped**
  (861 before S4).

**Carried forward (open, tracked)**

- **S4.5's inspection timeout is S6 with the tap**, exactly as §8 S4.5 describes it ("the flow's
  tap starts a `MAX_INSPECTION_HOLD_MS` deadline at `tcpFlowOpened`"): the deadline is evaluated
  when data arrives or the flow closes, so it cannot exist before the §6.3 tap does. U-8's
  "inspection-timeout path (simulated stalled feed past `MAX_INSPECTION_HOLD_MS`)" leg lands with
  that tap, together with `tls443_flows`, `inspect_timeout`, the `OwnedBytes` wrapping at each
  terminal result, and `onClosed`'s `release()`.
- **S10 needs an Android 17 image for I-12b** (ECH on by platform default) and real Chrome
  captures for fixtures B–D if the project later wants them byte-for-byte. — **Closed 2026-09-29
  (task #92):** the `MyExodus_API37` AVD filled the image need; I-12b PASSed on it (`sni_ech=0→4`,
  page renders "You are using ECH.") and Fixture D became a real Chrome 145 capture from the same
  image (U-8 re-ran 19/0). Fixtures B–D are now all real captures. Report §3.4.

## S5 — Ledger and registry (2026-09-20)

**Note on this record:** the session working S5 was interrupted (hard process kill) mid-verification.
The code and tests below were already on disk; on re-entry the KSP incremental cache was corrupted
by the kill (`StreamCorruptedException` in `:app:kspDebugUnitTestKotlin`), which was cleared
(`app/build/kspCaches` deleted — regenerable build output), and every verification below then ran
green. No S5 source change was needed after the interruption.

**Landed**

| Requirement | Artifact |
|---|---|
| S5.1 `RuleCounter` (§4.7 interval identity: `totalMs`, `sessionStart`, `lastActivityElapsed`, `lastAccountedElapsed`, `generation`, `exhausted`, `enforcedToday`, `dailyLimitMs`, `changedSincePersist`) | `domainusage/RuleCounter.kt` |
| S5.2 `UsageLedger` — `ConcurrentHashMap` by `"$ruleId:$revision"`, snapshot swap w/ seeded totals + D-32 fresh-revision counters, stale-counter drop, exhaustion re-eval | `domainusage/UsageLedger.kt` |
| S5.3 `FlowRegistry` — per-rule concurrent set, `MAX_TRACKED_FLOWS_PER_RULE` cap + `flow_registry_capped`, `resetAll` sweep | `domainusage/FlowRegistry.kt`, `domainusage/FlowControl.kt` |
| S5.4 Tick loop — `domainusage-tick` cadence (`delay(TICK_MS)`), persist hand-off every `PERSIST_EVERY_TICKS`, rollover hand-off, fault tolerance | `domainusage/UsageTickLoop.kt` |
| S5.5 allocation-free `touch` | `RuleCounter.touch` (atomics + plain fields only; no allocation on the relay path) |
| S5.6 `usageTodayMs(ruleId)` (current revision) | `UsageLedger.usageTodayMs` |
| Day rollover split (§6.5 step 2 / §6.7) | `UsageLedger.rolloverTo`, `RuleCounter.splitDayAt`/`resetForNewDay` |
| §4.9 snapshot/acknowledge seam (generation-guarded) | `UsageLedger.dirtySnapshot`/`acknowledge`, `UsageSnapshot`, `DayRollover` |

**Deviations and interpretations (recorded, not silent)**

1. **`RuleCounter.sessionStart` is an `AtomicLong`, not the plain `Long` of S5.1's field list.**
   §4.7 has `touch` (relay thread) starting new sessions while `splitDayAt`/`resetForNewDay` (tick
   thread) reset it at a day boundary — two writers, so it gets the same atomic treatment as
   `lastActivityElapsed`. Pure tick-thread fields (`lastAccountedElapsed`, `generation`,
   interval scratch) stay plain.
2. **Tick-loop faults map to the `hook_exceptions` counter.** §14's 38-name set has no tick-loop
   counter (same substitution as S1's breaker); a `VirtualMachineError` is rethrown after
   `fatal_vm_error`, everything else is counted and the loop continues — a dead tick loop would
   silently stop all metering.
3. **`PERSIST_EVERY_TICKS` is derived** (`PERSIST_INTERVAL_MS / TICK_MS`) rather than a second
   hard-coded cadence constant, so §6.6 has one source of truth.
4. **`tick()` returns the rollover instead of acting on it.** §6.6's flush and §6.7's final-write
   belong to the db thread (INV-1: only the db thread touches Room); the tick loop hands both
   outcomes to suspend callbacks that S7 wires, keeping the ledger Room-free.
5. **A dirty stale counter is not dropped** — `dropStaleCounters` waits until its pending write is
   acknowledged. S5.2 says "drop counters for removed rules once their `FlowRegistry` entry is
   empty"; dropping before the write would silently lose up to `PERSIST_INTERVAL_MS` of accrued
   usage, so the drop additionally requires `!changedSincePersist` (pinned by
   `aRemovedRulesCounterWaitsForItsPendingWrite`).
6. **The `FlowRegistry` cap check is racy by one** (two racing registrations can both pass the
   size check). The cap bounds sweep work, not correctness; an off-by-a-few is not worth a lock
   (documented in code).

**Verified**

- `./gradlew :app:compileDebugKotlin` green; `:app:domainUsageCiChecks` green (the S1.4 clock
  confinement scan covers the five new sources and four new test files).
- `./gradlew :app:testDebugUnitTest --tests 'com.example.myexodus.domainusage.*'`: **147 tests,
  0 failures** (111 before S5; +36: `RuleCounterTest` 12 = U-14, `UsageLedgerTest` 17 = U-10,
  `UsageTickLoopTest` 3, `FlowRegistryTest` 4).
- Full suite `./gradlew :app:testDebugUnitTest`: **919 tests, 0 failures, 0 errors, 0 skipped**
  (883 before S5).
- Requirement-by-requirement against §12.1:
  - **U-14** (all five named legs): burst mid-interval books only time since arrival; a session
    across many ticks accrues once per millisecond; a tick invoked 60 s late books the whole gap
    in one shot with no per-invocation cap (the stall-undercounting regression); a doubled tick
    books nothing the second time; idling exactly at `T_IDLE_MS` still counts the boundary.
  - **U-10**: 20 s flow ⇒ 35 000 ms then stops; 8 overlapping flows count once; a single
    mid-interval packet credits 5 s not 10 s; idle stops after the tail; rollover splits exactly
    at the boundary (including a midnight falling between ticks, asserted to the millisecond);
    backward wall jump cannot reopen the day (INV-3) and accrual continues; forward jump advances
    the key; the day key follows the anchored zone, not the system default; `enforcedToday=false`
    ⇒ no accrual; limit reached ⇒ `exhausted` + open flows swept in `ENFORCE` and never swept in
    `ACCOUNT`; limit raised ⇒ cleared on the next tick without re-sweeping.
  - **§4.9 seam**: `acknowledge` keeps the dirty flag when the generation moved between snapshot
    and commit (`acknowledgeKeepsADirtyFlagWhenTheGenerationMovedOn`) — the U-11 race leg that
    lives inside the ledger; the row-level half (`MAX` upsert, retry, reload) is S7's U-11.
  - **S5.3 cap**: a flow past `MAX_TRACKED_FLOWS_PER_RULE` is counted (`flow_registry_capped`) and
    simply not swept; tracked flows still are.

**Carried forward (open, tracked)**

- **U-10's "chunks < 512 B ⇒ 0" leg lands with the S6 tap.** §4.7 places the
  `MIN_ACTIVITY_BYTES` filter on the caller ("called from relay threads for every relayed chunk
  ≥ `MIN_ACTIVITY_BYTES`"); the tap that makes those calls is S6 work, so the leg is tested there.
- **`seededTotals` has no producer until S7** — `applyRules(rules, seededTotals)` is the seam;
  startup load (and the D-32 revision-seeding ruling below) is S7's.
- **The tick loop is not launched anywhere yet** — engine lifecycle, dispatcher and the S7
  callbacks arrive with S6/S7 wiring.
- **The D-32 × §6.8 revision-seeding gap (S2 carry-forward) resolves at S7** and still awaits a
  user ruling: after a restart the in-memory revision counter is 0 while today's persisted row may
  be at revision N. Implemented plan-literally; fix is seeding `MAX(revision)` per rule id.
- **Still open from S3:** the `enforcedToday` × NODATA ruling (one-line fix available).

## S6 — Attribution and Baseline wiring (2026-09-20)

**Landed**

| Requirement | Artifact |
|---|---|
| S6.1 `AttributionResolver` — SNI authority (FR-3a, no DNS override on a valid miss), §4.6 DNS agreement rule (D-11/INV-8), counters `dns_fallback_attributed`/`dns_fallback_ambiguous` | `domainusage/AttributionResolver.kt` |
| S6.2 `InspectingTcpTap` (TCP/443 hold → decide → forward, `MAX_INSPECTION_HOLD_MS` timeout, INV-13 buffer hand-off) + `ActivityTap` (DNS-fallback taps), `PassThroughTap`, `GuardedTap` | `domainusage/InspectingTcpTap.kt`, `domainusage/FlowTap.kt` |
| S6.2 Baseline TCP wiring — tap at flow creation, `onClientData` routing (`Hold`/`Forward`/`PassThrough`/`Reset`), `onServerData(len)` on the relay, `onClosed()` on teardown/sweep/stop | `vpn/DnsSinkholeVpnService.kt` (`handleTcp`, `connectUpstream`, `relayLoop`, `teardown`, `sweepLoop`, `stopAll`) |
| S6.3 UDP wiring — `udpFlowOpened` at first datagram per **5-tuple** (ADR #70), per-datagram `onClientData`, `Drop` before any socket exists | `vpn/DnsSinkholeVpnService.kt` (`forwardUdp`, `udpKey`, `udpReceiveLoop`, `UdpFlow`) |
| S6.4 `FlowRegistry` registration/deregistration by both taps | `FlowTap.kt`, `InspectingTcpTap.decide` |
| Engine hook surface (`onDnsQuery`, `onDnsResponse`, `tcpFlowOpened`, `udpFlowOpened`, `updateRules`, `counterFor`) | `domainusage/DomainUsageEngine.kt` |
| Hilt provider (build-configured mode; `start()` applies the debug override later) | `di/DomainUsageModule.kt` |

**Deviations and interpretations (recorded, not silent)**

1. **Taps are guarded at the seam, not inside `engine.hook`.** The Baseline calls tap methods
   directly, so INV-6 cannot be satisfied by the engine's inline `hook`. The engine therefore
   returns every tap wrapped in `GuardedTap`: ordinary exceptions ⇒ `hook_exceptions` + pass-through
   + inner `onClosed()` (so a fault cannot strand a pooled buffer or a registry entry), and a
   `VirtualMachineError` ⇒ `fatal_vm_error` + rethrow (D-35). `InspectingTcpTap` additionally
   recovers *locally* (`failOpen()` forwards whatever it already held), because a guard-level
   fallback for a mid-hold fault would otherwise drop client bytes the device has already acked.
2. **A held chunk is acked as if forwarded.** On `Hold` the Baseline advances `ackToDevice` without
   writing upstream; the tap forwards the whole accumulation later. A device retransmit of a held
   chunk therefore re-ACKs instead of re-inspecting it (the existing re-ACK branch), and no acked
   byte can be lost or replayed.
3. **The DNS decision site composes blocklist-first.** `handleDns` keeps the blocklist's NXDOMAIN
   as the first answer and only then consults the shaper, so a query is answered exactly once by
   exactly one authority (S0 hazard 1).
4. **UDP enforcement is per datagram, not per flow.** A UDP 5-tuple has no per-flow reader thread
   to signal, so `UdpFlow.reset()` is a documented no-op; `UdpActivityTap` re-checks
   `ENFORCE && counter.exhausted` on every client datagram, counts `udp_dropped` and returns
   `Reset` (the Baseline drops that datagram). The ledger's exhausted flag stays the single source
   of truth (INV-4). §6.10's ICMP Port-Unreachable nudge — which belongs at this drop site — is S8.
5. **`udpFlowOpened` is consulted before any socket exists**, so a `Drop` never opens a
   `DatagramSocket`; the `UdpFlow`/tap objects are created after admission.
6. **`connectUpstream` re-checks `flow.closed` after connect.** An exhausted-rule admission resets
   the flow at SYN time; without the check a late connect success would resurrect a flow whose RST
   is already on the wire (the relay thread would then talk to a closed flow). `teardown` likewise
   removes flows by identity (`flows.remove(key, flow)`), so a reset cannot evict a same-key retry.
7. **A full inspection buffer ends the hold as `sni_toolarge`.** `ClientDataResult` forwards one
   byte range per chunk, so a chunk that does not fit cannot be preserved; the hold ends and the
   flow is DNS-fallback-attributed (or unmetered). Reachable only with a ClientHello fragmented
   across ~77 TLS records — the parser rejects genuinely oversize messages within its first bytes
   (documented in `InspectingTcpTap.hold`).
8. **Zero rules shape nothing without a special case (FR-12).** An empty `RuleIndex` makes every
   hook observably pass-through; the engine adds no separate dormancy flag — only its internal
   counters move (pinned by `theCounterSetIsUntouchedByAnEmptyRuleSnapshot`).
9. **The 5-tuple UDP key is the Baseline's change, not the engine's** (ADR #70): `udpKey` now
   includes the device-side source address/port, so two devices/ports talking to one destination
   get separate admissions, sockets and taps. One `padded` extension of the pre-existing key
   format — no new structure.

**Verified**

- `./gradlew :app:compileDebugKotlin` green; `:app:domainUsageCiChecks` green.
- `./gradlew :app:testDebugUnitTest --tests 'com.example.myexodus.domainusage.*'`: **191 tests,
  0 failures** (147 before S6; +44 = `AttributionResolverTest` 10, `InspectingTcpTapTest` 14,
  `EngineHooksTest` 12, `FlowTapTest` 8).
- Full suite `./gradlew :app:testDebugUnitTest`: **963 tests, 0 failures, 0 errors, 0 skipped**
  (919 before S6).
- Requirement-by-requirement against §12.1 / §8 S6:
  - **U-9** — SNI match; a valid unmatched SNI is null **and** the DNS path is proven to have had a
    candidate (no override); single candidate ⇒ attributed; two names one rule ⇒ attributed; two
    names different rules ⇒ `dns_fallback_ambiguous`; *one rule one NONE — in both arrival orders*
    ⇒ ambiguous (D-11); all-unmatched ⇒ `None`; expired candidates ignored; IPv4-mapped form.
  - **U-10's carried `< 512 B` leg** — `chunksUnder512BytesAreNotActivity` (and the ≥ threshold
    leg on both directions): the tap, where §4.7 places the filter, now owns it.
  - **S6.2 tap contract** — split ClientHello held then forwarded as one piece with exactly the
    held bytes; a valid-SNI miss stays unattributed despite a matching DNS observation; ECH ⇒ DNS
    fallback (never blocked for being ECH); a stalled hello times out (`inspect_timeout`) and its
    held bytes ride along; `ENFORCE` + exhausted ⇒ `flow_resets_new` + `reset()` + `Reset` +
    buffer returned through close; `ACCOUNT` + exhausted ⇒ forwarded, no reset; pool exhausted at
    open ⇒ metered non-inspecting tap; dormancy mid-hold forwards what was held.
  - **INV-13** — close during the hold returns the slot; close *after* a `Forward` does not (the
    Baseline's `release()` does, exactly once).
  - **D-32/FR-14** — a flow keeps the revision it opened against across an edit; a flow opened
    after the edit binds the new revision.
  - **S6.3/UDP** — drop before a socket exists (and without `reset()`), allow + register + meter,
    unattributed pass-through, `udp_dropped` on the per-datagram check, `ACCOUNT` never drops.
  - **Hook scoping** — `OFF` short-circuits all four hooks with zero counters; the DNS seam shapes
    only in `ENFORCE` for an exhausted matching rule; a forward query's trailing buffer bytes are
    not parsed (`len`, not `array.size`); `onDnsResponse` feeds the observation store through the
    engine seam.

**Carried forward (open, tracked)**

- **Baseline wiring legs are device-verified (S10/§12.2), not JVM-testable.** The tap internals
  are covered above; the socket/TUN integration of `Hold`/`Forward`/`Reset` and the UDP 5-tuple
  key ride on I-1…I-23 in S10.
- **§6.10's ICMP Port-Unreachable nudge at the UDP drop site is S8** (`icmp_injected`,
  `RawPacketBuilder`, INV-14).
- **Mode is still build-configured:** the debug DataStore override is applied by `start()` (S7) —
  `engine.mode` is the seam and carries `internal set`.
- Unchanged from S5: the D-32 × §6.8 revision-seeding gap (user ruling pending), `seededTotals`
  has no producer until S7, the tick loop is not launched until S7, and S3's `enforcedToday`
  × NODATA ruling is still open.

---

## S7 — Persistence, lifecycle, status (2026-09-20)

**Landed**

| Requirement | Artifact |
|---|---|
| S7.1 entity — `(rule_id, revision, day_key)` composite PK, no foreign keys, no hostnames/IPs/timestamps beyond `day_key` (NFR-4, S-4) | `domainusage/RuleUsageDaily.kt` |
| S7.1 migration — Room v9→v10 `CREATE TABLE IF NOT EXISTS rule_usage_daily`, DDL verbatim from the exported v10 schema (composite PK's implicit index included, which Room validates on open); `exportSchema = true`; `fallbackToDestructiveMigrationOnDowngrade()` deliberately **not** used | `data/db/AppRulesStoreFactory.kt` (`MIGRATION_9_10`, `ALL_MIGRATIONS`) |
| S7.1 downgrade (D-33) — `MIGRATION_10_9` drops only `rule_usage_daily`, registered in the same `addMigrations` list (the builder selects by `(from, to)` regardless of direction), so a v9 rollback APK reopens the file with every other table intact | `data/db/AppRulesStoreFactory.kt` (`MIGRATION_10_9`) |
| S7.1 DAO — `upsertMax` (`INSERT OR IGNORE` then `UPDATE … MAX(active_ms, :v)` in one `@Transaction`), `deleteOlderThan`, `loadDay`, `maxRevisions` | `domainusage/RuleUsageDao.kt` |
| S7.2 `UsagePersistence` — generation-guarded `MAX` flush, 3-consecutive-failure degradation, `db_flush_ok`/`db_flush_fail`, retention delete that never throws | `domainusage/UsagePersistence.kt` |
| S7.3 `start()` per §6.8 — mode resolution (S1.3 debug override), zone anchoring, `max(today, persisted)` day key (INV-3), startup load on `domainusage-db`, D-32 revision seeding before the first snapshot adapts, ledger attach + `applyRules(snapshot, totals)`, tick loop, rule-snapshot collector on the tick thread, Private-DNS monitor | `domainusage/DomainUsageEngine.kt` (`start`), `domainusage/UsageTickLoop.kt` |
| S7.3 `stop()` per §6.9 — single-execution guard, cancel tick, one bounded `runBlocking` flush, monitor unregister, store/registry clear, VPN-stopped notification | `domainusage/DomainUsageEngine.kt` (`stop`, `flushOnStop`) |
| S7.3 service wiring — `start()` after `establish()`, `stop()` from `onDestroy`/`onRevoke`, `seedRevisions` bound to the provider (closes S6's carried D-32 gap) | `vpn/DnsSinkholeVpnService.kt` |
| S7.4 notification — dedicated `domain_usage_status` channel, one notification, suppressed without permission (both the 33+ runtime permission and the channel-level switch), copy from string resources. Since ADR #179 (2026-10-18) the warning names the rule's own site and is `VISIBILITY_PRIVATE` (a secure lock screen conceals the text); logs, exceptions and storage still carry no hostnames | `domainusage/AndroidDomainUsageHost.kt` |
| S7.5 `PrivateDnsMonitor` (D-34) — `NET_CAPABILITY_INTERNET` + `NOT_VPN` callback, per-`Network` strict flag in a `ConcurrentHashMap`, recomputed from the tracked set on every `onLinkPropertiesChanged`/`onCapabilitiesChanged`/`onLost`, so the status can never outlive the network it was seen on; strict = `isPrivateDnsActive() && privateDnsServerName != null` | `domainusage/PrivateDnsMonitor.kt` |
| S7.6 status — `StateFlow<EngineStatus>`, composed highest-severity-first | `domainusage/DomainUsageEngine.kt` (`refreshStatus`) |

**Deviations and interpretations (recorded, not silent)**

1. **`loadDay(dayKey)`, not the spec's `loadDay(day_key, rule_ids_and_revisions)`.** The plan's
   signature assumed the engine would filter by a caller-supplied `(rule_id, revision)` set. The
   shipped query returns the whole day and the engine keys it by `(ruleId, revision)` when it
   builds the ledger — the caller's set is exactly the loaded rows' keys, so passing it in would
   have been the same filter one layer down. The signature that *did* need widening is the other
   direction: D-32's seeding needs the max revision across **all retained days** (the last edit may
   have been yesterday), which the spec's DAO had no source for — added as `maxRevisions()`.
2. **The lifecycle guard rests back at `NOT_STARTED`, not `STOPPED`.** The plan's `RUNNING →
   STOPPING → STOPPED` reads as terminal; the Engine is a process-wide singleton and a new VPN
   session must start the same instance again. `stop()` is still exactly-once *per session* (a
   second call finds `NOT_STARTED` and bails), and the breaker's `dormantUntilRestart` flag still
   outlives the session by design.
3. **The tripped breaker outranks the lifecycle gate in `refreshStatus()`.** U-12 requires the trip
   to stay visible for the rest of the process; a `Dormant`-first composition would erase it the
   moment `stop()` runs. Order is: breaker → not-RUNNING → strict DNS → db degraded → no ledger/no
   rules → `Active`.
4. **A failed **startup load** is counted as `db_flush_fail`** and degrades immediately
   (`Degraded(DB_ERROR)`), cleared by the next success. The plan gave the counter pair for the
   flush cycle only; the load shares the "db write/read did not land" meaning, and a separate
   counter would have been a second name for one condition. The retry loop re-attempts the *open*
   (`dao()` returning null, e.g. the rules store in a recovery state) as well as the query.
5. **`tamper_private_dns` is edge-triggered.** The counter increments only on the false→true
   transition of the recomputed strictness — a repeated callback while already strict is not a
   second tamper event. (D-34 asks for "strict ⇒ counter++"; read literally on every callback it
   would count network churn as tampering.)
6. **`tamper_vpn_revoked` is counted by `onVpnRevoked()`**, called by the revoke path — deliberately
   not coupled inside `stop()`, so an ordinary `onDestroy` teardown never counts a tamper.
7. **`PrivateDnsMonitor` is registered unconditionally; the API floor lives in the host.** The plan
   places the monitor at API 28+ while the app's minSdk is 26; `AndroidDomainUsageHost` returns a
   no-op unregister below 28 rather than the Engine branching on `Build.VERSION`.
8. **A registration failure is not fatal** — the monitor is a warning surface, not a gate: an
   exception from `registerNetworkCallback` logs and yields a no-op unregister, and `start()`
   proceeds. (There is no `Degraded` reason for "monitor unavailable"; a device that cannot register
   simply reports no strict-DNS degradation.)

**Test-harness notes (S7 changed how the Engine must be driven under test)**

- The Engine runs on **real single-thread executors** in `DomainUsageLifecycleTest`: `stop()`'s
  bounded `runBlocking` flush needs real threads, and a virtual-time tick loop left running would
  make `runTest`'s final drain spin. Waits are **spin-count-bounded** (600 × 25 ms) rather than
  clock-reads, keeping §1.4's CI check (wall-clock calls confined to `SystemWallClock`) green.
- The failed-startup-load test must launch `start()` on its **own real dispatcher**: `start()`
  parks inside the retry loop until the load succeeds, and a launch on the test dispatcher would
  resume on the (blocked, spinning) test thread and never attach the ledger.

**Verified**

- `./gradlew :app:compileDebugKotlin` green; `:app:domainUsageCiChecks` green.
- `./gradlew :app:testDebugUnitTest --tests 'com.example.myexodus.domainusage.*'`: **220 tests,
  0 failures** (191 before S7; +29 = `UsagePersistenceTest` 9, `DomainUsageLifecycleTest` 12,
  plus the breaker-ordering case).
- Full suite `./gradlew :app:testDebugUnitTest`: **992 tests, 0 failures, 0 errors, 0 skipped**
  across 127 classes (963 before S7).
- Instrumented migration coverage — `AppRulesStoreMigrationInstrumentedTest`, **7 tests** on the
  X6532 (`am instrument` → `OK (7 tests)`), including `migrate9To10_addsTheUsageTable_andPreserves
  EveryEarlierTable`, `migrate10To9_dropsOnlyTheUsageTable_leavingAValidV9Database`, and
  `interruptedMigration_rollsBackAtomically_leavingTheOldSchemaAndData`.
- Requirement-by-requirement against §8 S7.1–S7.6:
  - **S7.1** — entity/PK/no-FK, both migration legs registered and schema-validated by Room on open
    (instrumented), DDL verbatim (composite PK index included), no destructive-downgrade fallback.
  - **S7.2** — 3-consecutive-failure degradation with a success in between resetting the run; a
    committed generation clears the dirty flag, an accrual *during* the commit does not; `MAX`
    never lowers a stored total (INV-5); the startup load also runs retention for the loaded day;
    an unavailable store is retried, never crashing the caller.
  - **S7.3** — `OFF` never starts and every hook stays pass-through; the debug override lowers the
    mode before anything loads; start loads today's totals, seeds D-32 revisions and goes `Active`;
    a persisted day key ahead of today is adopted and never moved backward (INV-3); zero rules stay
    `Dormant` while a later-added rule meters immediately (FR-12); a failed load degrades then goes
    `Active` on retry; strict Private DNS degrades the banner and counts the tamper on the rising
    edge only; a tripped breaker outranks every other reason and survives `stop()`; `stop()` flushes,
    acks and notifies exactly once; `stop()` without rules posts no notification; a second session
    restarts the same Engine and reloads the persisted total.
  - **S7.4** — `hasRules && mode != OFF` gating verified in `DomainUsageLifecycleTest`
    (`stopWithoutRulesPostsNoNotification`); permission gate and channel are host-side.
  - **S7.5** — strictness recomputation and the rising-edge counter are covered by
    `strictPrivateDnsDegrades_theBanner_andCountsTamperOnTheRisingEdgeOnly`; the per-network
    teardown (`onLost` removing the entry before recomputing) is the reason a stale strict flag
    cannot outlive its network.
  - **S7.6** — `status` is a `StateFlow`, asserted at every lifecycle transition above.

**Carried forward (open, tracked)**

- **S8** — §6.10's ICMP Port-Unreachable nudge at the UDP drop site (`icmp_injected`,
  `RawPacketBuilder`, INV-14); `addDisallowedApplication(BuildConfig.APPLICATION_ID)` (INV-9);
  removing `allowBypass()`; the builder changes S0's P8 recorded.
- **S9** — whether the `PRIVATE_DNS` degradation should render as a banner while the session is
  otherwise `Dormant` is a UI-layer question the S7 status composition deliberately leaves open
  (`refreshStatus` reports the degradation regardless of rule count; the banner copy is S9's).
- **S10/§12.2** — the Baseline wiring legs (socket/TUN integration of `Hold`/`Forward`/`Reset`,
  the UDP 5-tuple key, `start()`/`stop()` against real `onDestroy`/`onRevoke`) remain
  device-verified only.
- **S3 ruling still open** — `enforcedToday` × NODATA.

*(Superseded the same day: the S8 items above closed at S8 — see the next section.)*

## S8 — Enforcement and VPN builder (2026-09-20)

**Landed**

| Requirement | Artifact |
|---|---|
| S8.1/§6.10 the ICMP Port-Unreachable nudge — one packet per dropped datagram, written to the TUN, counted `icmp_injected`; a refused or invalid build counts `icmp_build_fail` and falls back to the silent drop it replaced (§9) | `vpn/DnsSinkholeVpnService.kt` (`nudgePortUnreachable`), `domainusage/DomainUsageEngine.kt` (`onIcmpNudge`) |
| S8.1 second nudge site — a mid-tuple `Reset` (rule exhausted while the flow was open) refuses the datagram with the same ICMP nudge, not a silent drop | `vpn/DnsSinkholeVpnService.kt` (`forwardUdp`, `ClientDataResult.Reset` branch) |
| S8.1 RST counters at the relay's RST injection — `rst_injected` / `rst_build_fail` decided by whether `writeTun` accepted the segment | `vpn/DnsSinkholeVpnService.kt` (`sendRst`), `domainusage/DomainUsageEngine.kt` (`onRstNudge`) |
| S8.2 packet construction (the plan's `RawPacketBuilder`; see deviation 3) — IPv4 ICMP type 3 code 3 and IPv6 ICMPv6 type 1 code 4, addresses swapped, quoting the original IP header + the bytes that carry the UDP ports, with IP and ICMP checksums (v6 over the pseudo-header); every length/offset validated against the input first, `null` on anything inconsistent (INV-14) | `vpn/VpnPacket.kt` (`buildIcmpPortUnreachable`) |
| S8.3 `addDisallowedApplication(BuildConfig.APPLICATION_ID)` as the builder's first line, with the INV-9 comment | `vpn/DnsSinkholeVpnService.kt` (`Builder().apply`) |
| S8.3 `allowBypass()` — confirmed absent (`grep -rn "allowBypass" app/src/` empty) | — |
| S8.4 foreground type, notification, start/restart logic — untouched; nothing in the S8 diff over the service is any of these | — |
| S8 verification harness (INV-9 consequence, see deviation 6) — app-side gates (`routes`, `private_dns`, `stop`) plus the hold-window handshake; traffic-path gates moved to the shell uid | `debug/…/vpn/VpnSpikeActivity.kt`, `scripts/dns-sinkhole-device-test.sh` |

**Deviations and interpretations (recorded, not silent)**

1. **ICMPv6 is implemented alongside IPv4, beyond §6.10's IPv4-only text.** ADR #70 ruling 1 keeps
   the TUN dual-stack, so an exhausted-rule datagram arriving over IPv6 would otherwise be dropped
   silently — defeating §6.10's whole purpose (fast client fallback) on half the traffic. Same
   nudge, RFC 4443 type 1 code 4, checksum over the IPv6 pseudo-header.
2. **"the first 8 bytes of the original UDP payload" is read as the first 8 bytes of the IP
   payload.** RFC 792's "original datagram's data" is the header plus its first 8 bytes; for UDP
   those 8 bytes *are* the UDP header, which is what carries the ports the client matches the error
   against. A payload shorter than 8 bytes is quoted only as far as it goes
   (`minOf(8, payloadLength)`).
3. **No new `RawPacketBuilder` class.** The plan made it conditional ("if not already present per
   S0/P9-P10"); the baseline's `VpnPacket` object already builds TCP/UDP and IPv4/IPv6 packets with
   checksums, so the ICMP construction was added there instead of re-homing every existing builder
   into a new class that would own half the file.
4. **`rst_injected`/`rst_build_fail` count every RST the relay writes, not only rule-driven ones.**
   `sendRst` is the single injection site (today the unreachable-family abort; §6.3's rule-driven
   resets reach the device the same way), and the counter's meaning — "every RST written into the
   TUN, however it was decided" — is one name for one event.
5. **S8.3's "do not add IPv6 addresses or routes" is honored as "add nothing new".** The builder's
   pre-existing `addAddress(TUN_V6, …)`/`addRoute("::", 0)` are untouched (ADR #70 ruling 1); the
   S8 change to the builder is exactly the one added line.
6. **The device harness is split across two uids, because INV-9 makes the old one dishonest.**
   `addDisallowedApplication` excludes the app from its own TUN, so the spike's in-app traffic gates
   could no longer touch the sinkhole. The pre-fix run proved the harness had gone blind rather than
   green: `dns_blocked`/`ipv6_sinkhole` failed (12 s of retries, `rcode=-1` — the query never
   entered the TUN) while `dns_allowed`/`https_200` *passed* — over the physical network, not
   through the relay. A passing gate that never traversed the thing under test is worse than a
   failing one. Now: app-side gates stay in-app (routes via Java — XOS denies `ip` to the app uid;
   Private DNS read; stop lifecycle), traffic-path gates run from the shell uid, which the builder
   does not exclude. The app holds the VPN open for the script through a handshake file
   (`/sdcard/Android/data/<pkg>/files/spike-host-done`, writable by both) with a 120 s deadline so a
   killed script cannot leak a live VPN. The shell cannot drive the service itself —
   `am start-foreground-service` from uid 2000 is refused: `BIND_VPN_SERVICE` is app/system-only.
   The client's "unknown host" alone is not decisive (the physical resolver also fails the probe
   domain), so the script asserts the service's own `nxdomain … rule=policy written=true` line
   delta — the sinkhole answering — alongside the client output.
7. **`private_dns` is a SKIP on an image with no setting.** Bare-AOSP emulator images leave
   `private_dns_mode` unset; the spike reports SKIP rather than inventing a verdict, and SKIP never
   counts as a pass (`ALL=FAIL` only if a gate FAILs). The product's real detection path is D-34's
   `PrivateDnsMonitor` (S7, JVM-verified); I-14/I-14b verify it per-device in S10. The X6532 — a
   real device with a real setting — reports `mode=opportunistic` and PASSes with the DoT-bypass
   disclosure, so the gate's live path is exercised too.

**Verified**

- `./gradlew :app:compileDebugKotlin` and `:app:assembleDebug` green; `:app:domainUsageCiChecks` green.
- Full JVM suite `./gradlew :app:testDebugUnitTest`: **997 tests, 0 failures, 0 errors, 0 skipped**
  across 127 classes; domainusage scope **221 tests across 25 classes**. `VpnPacketTest` gained 4
  ICMP cases (checksum/vector cross-checked against an independent Python implementation; v4 56 B,
  v6 96 B, short-payload quoting, INV-14 nulls).
- **Emulator-5554** — `PASS: VPN spike (app-side + traffic-path gates)`. App-side:
  `routes=PASS private_dns=SKIP stop=PASS`. Traffic-path: `dns_blocked` PASS
  (`nxdomain blocked.example.com v4 rule=policy written=true`), `dns_allowed` PASS (172.66.147.243
  resolved through the relay), `stop_restores` PASS (post-stop resolution works), 8 relay flows
  observed.
- **X6532 (physical device)** — same script, full `PASS`, and the gate set was stronger on real
  hardware: `routes=PASS`, `private_dns=PASS mode=opportunistic` (the device's real Private DNS
  setting also drove the service to `DEGRADED PRIVATE_DNS_ACTIVE` — the disclosure surface on real
  input, not just a synthetic banner), `stop=PASS`; `dns_blocked` PASS (`written=true`),
  `dns_allowed` PASS, `stop_restores` PASS; 67 relay flows, with the three seeded policy rules
  loading as `policy blocklist: N host rules` snapshots v73→v76. (Consent for the harness run was
  granted via `cmd appops set … ACTIVATE_VPN allow` after installing the fresh APK — XOS revokes
  grants on reinstall, and the dialog needs a physical tap.)
- Requirement-by-requirement against §8 S8.1–S8.4:
  - **S8.1** — all shaping sites sit inside the mode gate that S5/S6 already pinned:
    `UdpAdmission.Drop` exists only under `ENFORCE && counter.exhausted`
    (`anExhaustedRuleDropsTheUdpFlowBeforeAnythingIsOpened`, `accountModeAllowsAnExhaustedUdpFlow`),
    DNS shaping only under `ENFORCE` (`dnsQueriesAreShapedOnlyByAnExhaustedRuleInEnforce`), and the
    S8 additions are the two nudge sites plus the RST counter — no new shaping, no new mode surface.
  - **S8.2** — `buildIcmpPortUnreachable` returns `null` (→ counted build failure → silent drop)
    for any address-family/length/offset inconsistency; the four `VpnPacketTest` cases pin the
    bytes and the refusals.
  - **S8.3** — `addDisallowedApplication` present; `allowBypass` absent; the exclusion's *effect*
    proven on-device (the app's own sockets provably no longer traverse its TUN — the pre-fix run
    above).
  - **S8.4** — no diff hunks in the FGS type, the notification, or the start/restart logic.
- **Open for S10:** the script's own hard gate — "must pass on the X6532 AND one more physical
  device before Slice 4" — has the X6532 leg from this run; the second physical device rides with
  the §12 matrix (I-1…I-23), including I-11/I-11b (QUIC fallback and its non-regression), which
  need a real QUIC client (the shell has no HTTP tool; the synthetic legs are S10's).

## S9 — UI: editor chain, notices, usage line, status banner (2026-09-20)

**Landed**

| Requirement | Artifact |
|---|---|
| S9.1 save chain (normalize → FR-2 uniqueness → clamp) | `ui/rules/RuleEditor.kt` — `save()`, `siteLimitTarget`, `siteLimitPatternsOf`, `clampStoredLimitMs` |
| S9.1 error table incl. `DuplicatePattern` + the HasPath "Use it" action | `RuleEditor.kt` (`EditorError`, `ErrorBanner` action slot) + `res/values/strings.xml` (`editor_site_limit_*`) |
| S9.2 scope + connection-level notices under the limit fields (websites) | `RuleEditor.kt` (`editor_limit_scope_notice`, `editor_limit_connection_notice`) |
| S9.3 "Used today: X / Y min" per live limited website rule, ≤ 5 s refresh | `ui/rules/RulesScreen.kt` (`RuleRow`, 5 s ticker) + `DomainUsageEngine.usageTodayMs(ruleId): Long?` |
| S9.4 status banner from `EngineStatus`, shown only with ≥ 1 live limited site | `RulesScreen.kt` (`engineBannerMessage`, `hasLimitedSiteRule`), `ErrorBanner(onDismiss = null)` |
| S9.5 existing toolkit + string resources | Material3 `ErrorBanner`/`ModeRow` reuse; 13 new strings (`editor_site_limit_*` ×8, the two notices, `rules_used_today`, the two banner lines), no new UI dependency |
| Wiring — engine + PSL into the UI | `MainActivity.kt` (Hilt inject), `ui/AppShell.kt` |

**Deviations and interpretations (recorded, not silent)**

1. **The chain runs only on a website save that enables a limit** (`!isApp && limitOn`) — a website
   save without a limit keeps V1 semantics. S9.1's error set is limit-framed, and path-scoped website
   blocks are a shipped V1 feature (`UrlPattern.pathPrefix`, matched with a segment boundary): running
   `normalize` unconditionally would make `example.org/forum` — a legitimate blocklist target —
   unsaveable, a V1 regression the plan does not ask for. The metering engine never sees such a rule
   (`DomainRuleProvider.adaptOne` returns null for `dailyLimitMs <= 0`). Verified both directions on
   device (below).
2. **FR-2's collision set is wider than "another enabled rule": every other website rule with a
   limit, paused and expired included.** §4.5/S2.8 say "enabled". A paused or expired rule can be
   re-enabled from the Rules list without passing the editor chain, and it would then collide in
   `RuleIndex`, whose dedup silently drops one side — exactly the silent loss the check exists to
   prevent. Rejecting the save now is the survivable failure: a visible message while the conflicting
   rule is on screen, instead of a rule that silently stops metering later. Patterns are normalized
   through the same `HostNormalizer.normalizeLegacy` the provider uses, so "same pattern" has one
   definition. Residual: a pre-check duplicate pair re-enabled from the pause switch still reaches
   the index dedup — that is the backstop's documented job (§4.5), with
   `rules_duplicate_pattern` as its signal.
3. **`RuleIndex.duplicatePatternFor` was removed as production-dead.** S2's carry-forward said the
   editor would call it; it could not serve: it matches over the *live* index (limit rules only,
   enabled, unexpired) while the editor needs the wider set over raw stored rows, and an unsaved rule
   is in no snapshot to begin with. Keeping both would have left two divergent definitions of
   "duplicate pattern" for one check. Its test went with it; the two S2 log lines above are marked
   superseded.
4. **`usageTodayMs(ruleId)` returns `Long?`, null until the startup load attaches the ledger.** A `0`
   before that would be a false claim (S9.3's row must not lie); the row hides the line until the
   engine can answer. Reads are safe from the UI thread — they read a volatile total the tick thread
   writes (S5.1).
5. **The S9.2 notices render while the h/m fields do** (`limitEnabled && !alwaysOn`). With the switch
   off nothing is metered; two paragraphs of limitation copy under an off switch would be noise.
6. **S9.4's banner is gated on a live limited website rule and has no dismiss** (user rulings
   2026-09-20). "Live" mirrors the publisher's own filter (`RulesSnapshot`: enabled and unexpired),
   so the banner can only appear when the engine's status explains something real — and reads red in
   the ratified ADR #59 error token. No dismiss because it states a system condition, not a user
   action; it leaves when its cause does.
7. **Website limits are new user-visible surface**: the "Limit daily use" `ModeRow` is no longer
   app-gated — the mechanism was always in the model (`dailyLimitMs`), only unreachable from the
   editor. `usageAccountingEngine.onDailyLimitChanged` stays app-only (that tracker accounts packages,
   not hosts) — unchanged V1 behavior.

**Verified**

- `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:domainUsageCiChecks` green.
- Full JVM suite `./gradlew :app:testDebugUnitTest`: **1005 tests, 0 failures, 0 errors, 0 skipped
  across 128 classes** (997/127 before S9). S9 added `SiteLimitTest` 8 (the 7-case error table
  including the PSL exact/wildcard/exception rules, duplicate-exact-match-only with subdomains
  allowed, the collision set, the clamp, the banner gate) + `DomainUsageLifecycleTest`'s
  `usageToday_msIsNullUntilTheLedgerLoads_thenReadsTheCurrentRevision`; the removal in §3 took one
  back. domainusage scope 221 tests across 25 classes.
- **X6532 (physical, `install -r`)** — requirement by requirement:
  - **S9.1** — the website editor offers "Limit daily use" (h/m fields + preview appear);
    `https://example.org/watch` is refused with 'Limits apply to a whole site. Use "example.org"
    instead.' + a "Use it" button; the button fills the field and clears the banner; the rule then
    saves and lists as "example.org · 30m daily limit". The duplicate path: saving the same site with
    a limit again shows "This site already has a limit set." (the site-limit copy, not the generic
    duplicate message) and the editor stays open.
  - **S9.2** — both notices render under the h/m fields, verbatim.
  - **S9.3** — with a live VPN session the rule's row reads "Used today: 0 / 30 min".
  - **S9.4** — with strict Private DNS set, the Rules screen shows 'Private DNS is on, so website
    limits may not apply…' at the top; **no banner under opportunistic, by design** — the engine
    flags STRICT only (D-34's monitor, `privateDnsServerName != null`), unlike the baseline service
    that reports DEGRADED for both. The I-14/I-14b device legs cover this divergence in S10.
  - **Non-regression** — a no-limit website rule keeps V1 path semantics: `example.org/forum` saved
    and listed as an always-on block ("Blocked now").
  - **Device restored afterwards**: `private_dns_mode=opportunistic` (specifier row deleted), both
    test rules deleted through the editor UI (the delete confirm dialog verified en route).

**Open for S10**

- §12's S9-touching items ride with the matrix: the instrumented legs, the second physical device,
  and I-14/I-14b for the strict-vs-opportunistic banner divergence. Two claims here are code-level,
  not yet test-pinned: the 5 s refresh of the usage line / banner gate (a Compose-side ticker; the
  X6532 run showed the line appearing but did not time the cadence) and the expiry-driven banner
  drop (the gate function is JVM-asserted in `SiteLimitTest`; the recomposition it feeds is not).
  Both want a screen-on read in S10.

**Closed 2026-09-30** — both claims were pinned on the X6532 in a 6-minute screen-on session
(app foreground, no interaction; report §7 item 7): the usage line re-read live (`0 / 60 min` at
58,185 ms accrued → `1` at 112,354 ms → `2`) and the expiry flip landed **≈3.6–4.6 s after the
expiry epoch** (host/device skew-corrected) — the rule's row pill flipped to `Expired` and the
strict-DNS banner dropped between two captures 2 s apart, with no store change and nothing but
`nowMillis` moving. Restore: `private_dns_mode` back to `off` (specifier untouched), rule deleted,
tunnel `ACTIVE` re-verified. **Harness seam added for it:** `du-addrule` takes an optional
`--el expiresInSec <n>` (debug receiver) — the editor's picker is day-granular (end of the
selected day) and clock moves are root-only/emulator-scoped, so a seconds-away expiry had no
other instrument; `blockExpiresAt` is stamped from the engine's own `wallClock` (the S1.4 guard
keeps raw clock calls out of `domainusage`).

---

## S10 — Verification: the 2026-09-22 X6532 phone session (2026-09-22)

**What this closes:** the phone half of §12.2 (I-11/I-13 `ACCOUNT` halves, I-17) and PF-1/PF-2/
PF-6/PF-7/PF-8. Full results and reasoning in `domainusage-test-report.md` §4.2; this entry records
the *decisions*, which is what the log is for.

**The compression ruling.** The owner's 2026-09-21 deferral set aside a nine-hour night for seven
phone legs. On 2026-09-22 the owner asked whether that could be shortened "while still achieving
accurate, complete, correct results". It could, because six of the seven did not need a night at
all — they are bounded measurements (a leg either executed correctly or it did not) and the
nine-hour framing existed to span PF-5's drain, not to make a 30-second throughput leg credible.
The session ran those six in 35 minutes on the charger. The seventh — and PF-3/PF-4, which share
PF-1/PF-2's withdrawn-A/B taint — were left **open** rather than shortened: duration *is* the
measurement for a soak and a drain, so a compressed version would have to be reported as something
other than the gate it claims to be. Recorded here because "we cut the test short" and "we cut the
test suite's coverage" are different acts and only the second is sound.

**Harness defects found and fixed while running it** (all in `scripts/domainusage-pf-run.sh`):
`ready_probe()` read a byte delta from a counter only `POST /sink` moves, so it could never
succeed — it had never run live before this session; `vpn_down()` treated an empty logcat read as
"the tunnel stopped"; `probe()` now retries an empty read; `check_mode()` requires `status=Active`
for non-`OFF` legs; `seed_rules()` verifies its own readback and aborts rather than running a leg
against zero rules (the failure that produced a report line reading `engine runs mode=ACCOUNT, leg
requested OFF` — two inert builds compared). The mode-cycle semantics themselves were confirmed by
hand: `ACTION_START` on a live tunnel is a no-op, so an override only takes effect after a
stop→start, which is what `set_mode()` now does.

**PF-7.** FAILed in-batch (`p99=171.7 ms` vs 150) and PASSed on the idle phone immediately after,
three times (`125.7 / 121.9 / 119.1`). The whole distribution moved, not the tail, and the
`p99/p50` ratio was unchanged — a slower environment, not a slower flush path. Scored PASS on the
idle runs with the in-batch sample preserved in the report. The residual question is whether §11's
bound should carry a load qualifier; that is the owner's call, flagged rather than decided.

**PF-3 and PF-4 (second block, 13:39–16:22, `/tmp/du-night.log`).** Both ran at full duration with
modes pinned and readback-verified, no WARN lines in the log — no mode drift, no invalid legs, no
app death. PF-3 PASSed at **1.00× OFF** (`63947j` vs `63717j`, bound `73539j`), superseding run 3's
withdrawn numbers.

PF-4 reported **FAIL** on the recorded legs and was then **re-scored PASS** — the second harness
criterion of the day shown to be the problem rather than the code. The three legs' thread deltas
read **+6 / +2 / −3** against a `+5` tolerance, and because `threads_ok` is sticky across legs,
leg 1's single extra thread vetoed the gate while the same legs' heap record was `[−1, −1, +5] MB` —
median **−1 MB**, the process smaller at the end. Diagnosis repeated item 9's method: a
per-thread-name dump during a leg (metering threads flat — `vpn-tun`/`vpn-sweep`/`vpn-dns4`/
`vpn-dns6`, one each) plus a 5-minute trend `170 → 116 → 110 → 110 → 111 → 111 → 107` — keep-alive
wander, not monotonic growth. **Owner ruling 2026-09-22: tolerance +5 → +10**, still an order of
magnitude below what a real per-flow leak over ≥ 10,000 churned flows would retain. Harness
(`PF4_THR0 + 10`) and plan §11 synced together; the legs were re-scored, not re-run — the criterion
changed, the measurement did not, the same practice PF-7's bound recalibration used. The heap/thread
aggregation asymmetry (median vs sticky worst-case) is deliberate and left alone; the tolerance was
the miscalibrated variable, not the aggregation. Report §5.1 item 15.

**PF-5's charging guard** tested only `USB powered|AC powered`, so a Qi pad under the phone would
have run the drain legs while charging and reported arithmetic on a filling battery as a valid
measurement. Fixed to cover `Wireless powered` too — report §5.1 item 16.

**Also done:** `scripts/domainusage-phone-night-run.sh` trimmed to the legs still open —
`DU_ONLY=PF-3,PF-4` on the charger, now `DU_ONLY=PF-5` alone — its hard-coded host IP replaced by
`DU_HOST` with the current value as default
(the LAN IP had changed after a reboot and the script would have failed at its first probe), and a
concurrency guard added — it previously waited only for the *emulator* batch, so starting it while
a phone session ran would have driven the same device twice and invalidated both. Wireless adb was
enabled with `adb tcpip 5555` while on USB: it works on this build, contrary to the script's header
claim, and does not survive a phone reboot.

## Review sweep — rollover finals retry (2026-10-17)

**Finding.** §6.7's rollover final was written exactly once (`persistRollover` →
`UsagePersistence.flush`), and `flush` swallowed ordinary failures and returned `Unit`. By then the
ledger's split has already moved each counter into the new day, and `dirtySnapshot()` stamps the
*current* day key — so one transient db failure at midnight permanently dropped the outgoing day's
row. §6.6/U-11's "next cycle writes the newer cumulative value" provably cannot reach it: the old
total no longer exists in the counter.

**Fix.** `UsagePersistence.flush` returns `Boolean` (true also for an empty row set; false for a
failed attempt; `CancellationException` still rethrows), and `DomainUsageEngine.persistRollover`
retries the finals every `RETRY_MS` until written, then runs retention and stores the new day key.
The retry sits outside `hook(Unit)` on purpose: it is not a relay-path hook, so breaker dormancy
must not skip a data write, and db-scope cancellation ends it through `delay`. Mid-day callers may
ignore the return — the still-set dirty flag re-emits the row next cycle.

**Regression.** `DomainUsageLifecycleTest.aFailedRolloverFinalIsRetried_untilTheOutgoingDaysRowIsWritten`
runs a real midnight crossing with the DAO refusing every write: the outgoing row is absent while
failing and the persisted day key stays on the old day; both land only after the outage clears. A
mid-day flush cannot fake the row because it stamps the new day key. `UsagePersistenceTest` pins
the true/false return contract. Focused run: 14 + 9 tests, 0 failures, BUILD SUCCESSFUL.

## Website quota in the URL path + FR3 warnings (2026-10-17)

**Finding (device-caught).** A 30-minute instagram.com daily limit showed `30 / 30 min` while the
site kept loading in Chrome, with no warning. Two independent defects: (1) `BrowserUrlScanner`
called `engine.decideUrl(canonical, System.currentTimeMillis())` without `usageTodayMs`, and
`BlockDecisionEngine` evaluates the quota branch only when that input is provided — the URL path
could enforce always-on, schedules and expiry, never the daily limit. Old build: `url scan
gen=128 'instagram.com' -> Allow` at 20:10:13–20:10:26, after the 20:09:26 exhaustion
(`dns_nxdomain_served` 0→313, `flow_resets_sweep` 0→12). The VPN path enforced the attributable
flows; Chrome's DoH/ECH traffic was unattributed (`sni_ech` 169→284, `flows_unattributed`
418→626) — the documented D-18/S-12/R-7/INV-8 limit, which the URL path does not depend on.
(2) No website-limit warning mechanism existed at all; the thresholds lived only in the
package-keyed `UsageAccountingEngine`.

**Fix.** `BrowserUrlScanner` gains `usageTodayMs: (ruleId) -> Long?` (default null = fail open) and
passes the matched rule's total into `decideUrl`; `BlockerAccessibilityService` wires
`DomainUsageEngine.usageTodayMs`. `UsageLedger.takeLimitWarnings()` reuses
`UsageAccountingEngine.calculateWarningThresholdsMs`; the fired flags live on `RuleCounter`, seeded
from the persisted total on attach (no restart replay), re-seeded on a limit change, reset at
rollover, and gated to the current revision. `UsageTickLoop` gains an `onTick` hook (inside the
existing exception guard) that posts `DomainUsageHost.postWebsiteLimitWarning`;
`AndroidDomainUsageHost` renders notification id 3 on `domain_usage_status` with INV-7-safe copy.
No metering, attribution, DNS, relay or persistence code changed. (Superseded in part 2026-10-18 by
ADR #179: the warning now names the rule's own site — see the dated section below.)

**Cross-browser.** Usage is keyed `(ruleId, revision)` with no app identity, so every browser's
traffic to the site feeds one counter; toolbar discovery is per package (`url_bar`,
`url_bar_text`, `location_bar_text`), so the same exhausted counter decides in each. Regression:
`exhaustedLimit_blocksTheSameSiteInEveryDiscoveredBrowser` drives Chrome, Brave and Firefox.

**Device proof (X6532, install -r, no data clear).** Rules screen: `instagram.com — 30m daily
limit — Used today: 30 / 30 min`. New build: Chrome `url scan gen=3 'instagram.com' -> Block`
(21:01:24–21:02:00) with the `UrlBlocked` overlay; Brave `gen=6 ... -> Block` (21:02:39–21:02:45)
with the same overlay. Warning leg: limit temporarily raised to 41m → usage 30→32 → notification
id=3, "Website limit almost used up" / "9 min left on a website limit today. Tap to review your
rules." (no hostname); rule then restored to 30m. Internet sanity: example.com rendered through
the VPN and the counters kept advancing.

**The 27→30 move.** Display floors whole minutes (`usedMs / 60_000`, `RulesScreen.kt:692`); the
ledger books one monotonic interval per tick, so accrual cannot exceed wall time (pinned by
`RuleCounterTest`, `eightOverlappingFlowsOnOneRuleCountOnce`, and the idle-tail test). The jump
therefore required ≥2:01 of real accrual — domain-level totals include background/keep-alive flows
plus the 15 s tail. The exact source is unrecoverable post-hoc (per-rule totals are not logged;
INV-7), recorded as unproven detail rather than a second defect.

**Regression.** Full JVM suite: **1780 tests / 0 failures / 0 errors / 0 skipped** (+8:
`BrowserUrlScannerTest` exhausted/under/unavailable + cross-browser; `UsageLedgerTest`
once-per-threshold, seeded-total no-replay, limit-change re-arm, superseded-revision silence).

**Follow-on, same day (ADR #174):** the scanner's address-bar edit-session gate added three
regressions (`editing_autocompletePreview_defersTheScan`, `editing_commitAfterEditing_blocksNormally`,
`editing_evaluatedAtFireTime_notEventTime`), taking the full suite to **1783 tests / 0 failures /
0 errors / 0 skipped** (BUILD SUCCESSFUL 12m38s). Metering, accounting and warnings are untouched.

## Relay send-window flow control — the download-stall fix (2026-10-18, ADR #175)

Owner report: browser downloads slow, a small WhatsApp video hung then sat at 53% until the VPN
was turned off. Root cause: the userspace relay synthesized the upstream→device TCP side with no
send window, no retransmission and no zero-window probing; a paused device reader caused ~4 MB to
be written past its advertised window and discarded by the kernel, wedging the flow permanently
(device-proven freeze at 6,495,000 of 10,485,760 bytes). Fix: `vpn/TcpSendWindow.kt` (SND.UNA /
SND.WND, in-flight queue, RTO retransmission 1 s→8 s, zero-window probing), window-paced relay
writes, bounded `SO_TIMEOUT` reads, a bounded FIN drain, and the per-packet hot-path logging
deleted.

**Device verification (X6532, 2026-10-18).** `speedtest.tele2.net/10MB.zip` (`Content-Length:
10,485,760`; measured header 240 bytes), reader `kill -STOP`ped 8 s: the file held at exactly
1,243,200 bytes for the whole pause, then resumed and completed at **10,486,000 bytes = header +
full body**, `nc` exiting on its own. A clean run completed the same file in ~40 s (~262 KB/s on
the test WiFi), matching the first-5-second rate — no first-pass throughput cost.

**Regression.** Full JVM suite: **1792 tests / 0 failures / 0 errors / 0 skipped**
(`TcpSendWindowTest` 6/6; `+9` from 1783 = 6 relay + 3 cover-target tests). The one mid-sweep
failure was an unrelated same-millisecond ordering tie in `AttemptLogTest`, fixed with `id`
tie-breakers in `AttemptLogDao` (ADR #178).

## Site-named limit warnings — the dated section (2026-10-18, ADR #179)

Owner report: the warning said "9 min left on a website limit today" without naming the site. INV-7's
notification clause was amended on owner direction (logs/exceptions/storage still never carry
hostnames): `RuleCounter` carries the rule's normalized `pattern`, `LimitWarning` passes it through
`takeLimitWarnings()` to `DomainUsageHost.postWebsiteLimitWarning(level, remainingMs, pattern)`, and
notification id 3 renders "%1$s: %2$d min left today…" with `VISIBILITY_PRIVATE` (a secure lock
screen conceals the text). `RuleEditor`'s scope notice now states metering spans every app,
including the site's own app.

**JVM.** `domainusage` **257 tests, 0 failures/errors/skips**; full suite **205 classes / 1,792
tests / 0/0/0** (2026-10-18 14:26). Existing warning assertions were tightened to pin the pattern.

**Device (X6532, 2026-10-18, debug APK `install -r`, SHA-256 `8b18fb1a…64aa5`).** Rule
`instagram.com` at 30 min; Instagram driven to `Used today: 25 / 30 min`; notification id 3 posted
at `when=1792324576827` (14:56:16.827 EAT), channel `domain_usage_status`, `vis=PRIVATE`:
**"instagram.com: 4 min left today. Tap to review your rules."** The remaining minutes floor (the
crossing left 4.99 min). Rule restored to its original 30 m, VPN `VALIDATED`, enforcement FGS up.
A temporary 30→25 edit during the run fired nothing, correctly: thresholds for limits ≥ 10 min are
`limit − 10` / `limit − 5`, and `seedWarnings` marks already-crossed thresholds as fired so a
lowered limit never warns retroactively.

## Meter invariant-exactness — the differential-oracle fixes (2026-10-18, ADR #181)

Owner mandate: no chance of a wrong duration. A 500-seed differential oracle (independent
union-of-tails merge, mixed sub-tick / idle-tail / idle-gap timelines, half splitting the day)
found two defects the hand-written U-14 cases missed:

1. **Stalled tick lost a closed streak.** `touch`'s `sessionStart` reset on an idle gap discarded
   the prior streak when no `recompute` ran during it (INV-16 violated). Fix: relay threads enqueue
   a closed streak into `RuleCounter.pendingStreaks`; `recompute`/`splitDayAt` drain it, clamped by
   `lastAccountedElapsed` so racing duplicates book nothing. The per-chunk path stays allocation-free
   except when a gap actually closes a streak.
2. **Day split credited idle pre-activity time.** The no-live-interval branch pulled `sessionStart`
   back to the midnight boundary even when the new streak started after it (+3,181 ms, oracle seed
   14). Fix: `sessionStart = max(boundary, sessionStart)` in both branches; `book(boundary)` already
   enforces the floor. `resetForNewDay` clears the queue/sentinel; unenforced counters discard.

The debug Protection page now also renders **exact per-rule totals** (`debugTotals()`, pattern →
`totalMs`, current revision) — the ground-truth seam for the device legs below.

**JVM.** New regressions: stalled-streak (16,000 ms), several gaps before one tick (31,000 ms),
midnight-stalled split (10,000/6,000), plus the 500-seed oracle. `domainusage` **29 suites / 261
tests / 0/0/0**; full suite **205 suites / 1,796 tests / 0 failures / 0 errors / 0 skipped**.

**Device (X6532, 2026-10-18; debug APK installed with `install -r`, on-device SHA-256 matched).**
Temporary rule `speedtest.tele2.net`, shell `nc` transfers through the tunnel.
- Duration: wall 1,751 + 25,284 ms; prediction (windows + 2 tails) **57,035 ms**; exact **55,708 ms**
  (difference = DNS + connect + first-chunk latency, 1,327 ms).
- Idle: 34 s between runs → wall-clock meter would read ≈75 s; exact total excluded it.
- Overlap: two parallel 10 MB downloads, wall 30,277 ms → delta **44,644 ms** vs ≈44.8 s union
  prediction (sum would be ≈90 s).
- Display: 100,352 ms → `Used today: 1 / 720 min`; 1,805,473 ms → `30 / 30 min` (floor division).
- Persistence: force-stop + relaunch → totals byte-identical (100,352 / 1,805,473), new PID, VPN up.
- Isolation: Chrome + Brave browsing (53 new TCP/443 flows) left both rule totals unchanged.
  `sni_ech` 11→37 is Chromium GREASE ECH (`Result.Ech`, DNS fallback; RFC 9849 §6.2), not a parser
  failure — `sni_malformed`/`sni_toolarge`/`inspect_*` all 0, `flow_resets_new` 0.

Honest limits: the window start is bounded to the `nc` wall window (not an ms-exact relay
timestamp); midnight rollover is oracle/JVM-proven, not device-run; a hard kill may lose up to
`PERSIST_INTERVAL_MS` (10 s) by design.

**UDP/QUIC leg (same device day).** Temp `example.org` rule (1 m/day), resolved through the tunnel
to 104.20.26.136, driven with toybox `nc -u -q 1`. Two single 600-byte datagrams >15 s apart booked
exactly **30,000 ms** (two tails). A sustained loop — 17 accepted datagrams over 30.3 s — plus one
tail read **75,014 ms** vs 75,000 ms predicted (+14 ms tick alignment). The 18th datagram opened
after exhaustion and was dropped: `udp_dropped=1`; `hook_exceptions=0`, `fatal_vm_error=0`,
`flow_resets_new=0`. The site-named warning fired ("example.org: less than a minute left today").
Temp rule deleted; the original four rules restored. The single wedged `nc` was a harness quirk (a
dropped UDP flow leaves toybox netcat blocked on read), not an engine defect.

**R-03 live check (same day, read-only).** Policy 4769902381008384079 read back with the pinned
`status = "ok"` filter; `autoClose` 3600 s; zero open incidents (`projects.alerts`); the ok-series
is continuous (358 points, 07:27Z→13:24Z). The 2026-10-18 warning the owner saw was the close of
the billing incident, not a new alert (ADR #180).

**Audit-fix batch (2026-10-18, owner mandate; ADRs #182–#185).** The same-day project audit
(`project-audit-2026-10-18.md`) found four actionable defects; all four are now fixed.

- **A-02 — expiry at decision time (ADR #182).** `DomainRule` carries `expiresAt`;
  `AttributionResolver`, `DnsShaper.decide(nowMillis)`, and `DomainBlocklist` all refuse an expired
  match, and `DnsSinkholeVpnService.handleDns` passes wall time. `RuleCounter.markExpiry` /
  `retargetExpiry` clamp accrual to the expiry instant on the monotonic clock; `UsageLedger.tick`
  marks and skips lapsed counters. The publish-time filter stays but is no longer load-bearing.
  Device: a stale snapshot shaped AAAA to NODATA (`an=0`) before expiry and forwarded the real
  Cloudflare record (`an=2`) after it, with the app never opened.
- **A-01 — false validation removed (ADR #183).** The editor no longer rejects a limit longer than
  the rule's window. The engines accrue all day and fire the quota on day scope
  (`BlockDecisionEngine` Rule 2); the window is an additional block period (Rule 1). Only the
  real bounds remain.
- **A-04 — INV-7 closed for release (ADR #184).** Every qname/IP/URL/hex diagnostic in
  `DnsSinkholeVpnService` and `BrowserUrlScanner` is now behind `BuildConfig.DEBUG`; the two log
  lines that wrote the TUN inline were restructured so release still writes packets. CI check 7
  fails any ungated sensitive `Log.` line in main sources. (The audit's A-04 row missed the
  scanner; the fix covers it.)
- **A-03 — honest non-enforcing UI (ADR #185).** Outside ENFORCE, the Rules list shows an
  unavailable banner and the editor disables the website daily-limit switch with a caption; an
  existing limit is preserved, app limits are unaffected. Device-proven with the debug override:
  banner + caption rendered, "Limit daily use" node `checked=true, enabled=false`, other mechanisms
  enabled; then the override was cleared, the process restarted, and the device returned to
  `mode=ENFORCE`, VPN `ACTIVE`, private DNS `off`, original rule intact.

**Suite after the batch: 205 suites / 1,811 tests / 0 failures / 0 errors / 0 skipped**
(+15 net over the audit baseline of 1,796). `assembleDebug` and `compileReleaseKotlin` both
succeed.
