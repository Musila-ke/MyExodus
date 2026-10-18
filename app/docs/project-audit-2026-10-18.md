# Project-wide confidence audit — 2026-10-18

Protocol: owner-supplied "Senior Software Engineering Reasoning Protocol" (deep project audit,
hidden failure discovery, evidence-based confidence). Investigation only — no production code was
rewritten, no schema/security changed, no ADR overridden. Findings are separated from proposals.

> **Status update (2026-10-18, after the audit).** Findings A-01, A-02, A-03 and A-04 were fixed
> under the owner's follow-up mandate, with regressions, device legs where possible, and ADRs
> #182–#185. The ledger rows below are kept in their original audit form; see the **Fix close-out
> addendum** at the end for current status and evidence. A-05, A-06, A-11 remain accepted
> limitations; A-12/A-13 remain blocked as stated.

## Scope and method (honest limits)

- Inventory: 330 main Kotlin files across 14 packages, 207 JVM test files, 31 androidTest files,
  12 docs, 20 plans, ADR headings #1–#181.
- This sweep did not re-read all 330 files. It re-read the highest-risk paths end-to-end
  (website engine, DNS/IP rule enforcement, VPN lifecycle, rule editor/validation, snapshots,
  recovery/backup, exported components, telemetry, GCP callables/rules) and re-used as evidence
  the completed line-by-line exhaustive review of 2026-10-17 (329/329 main files verdicted,
  findings #12–#66 fixed with regressions, ADR #170).
- Fresh verification run today: full JVM suite — **205 suites / 1,796 tests / 0 failures /
  0 errors / 0 skipped**.
- Device evidence referenced: ADR #181 (meter) and the 2026-10-18 UDP/QUIC leg (75,014 ms exact,
  `udp_dropped=1`, attribution isolation); R-03 live GCP verification (zero open incidents).
- Blocked items are named, never faked (section I).

## Persistent audit ledger

| ID | Area | Finding | Class | Severity | Confidence | Evidence |
|----|------|---------|-------|----------|------------|----------|
| A-01 | Rule editor ↔ engine semantics | `LimitExceedsWindow` rejects "limit > time window" claiming it "could never fire", but both engines accrue usage and fire the quota on day scope only — the quota **can** fire outside the window | Verified defect (copy + validation premise) | Medium | HIGH | `RuleEditor.kt:958`, strings.xml:259; `BlockDecisionEngine.kt:167`; `BlockerAccessibilityService.kt:753-756`; `EnforcementForegroundService.kt:888`; `UsageAccountingEngine.kt:277`; `DomainRuleProvider.kt:62`; ADR #138 records the requested validation |
| A-02 | Website rules with an end date | A lapsed website rule keeps being blocked/metered by the VPN engine until the next snapshot rebuild — expiry is filtered only at snapshot-build time and in the accessibility decision | Verified defect (two enforcement paths disagree) | Medium | HIGH | `RulesSnapshot.kt:40` (build-time filter); `DomainBlocklist.kt:44-52` (no clock/expiry); `DomainRule` has no expiry field; `DomainUsageEngine`/`UsageLedger` never read `blockExpiresAt`; rebuild only on publish (`DnsSinkholeVpnService.kt:294-308`, `MainActivity.kt:317-326`); app decisions do honour expiry (`BlockDecisionEngine.kt:159`) |
| A-03 | Release build | Website daily-limit rules are settable but inert (`DOMAIN_USAGE_MODE="OFF"`), and the UI shows no notice (`Dormant` → banner null) | Accepted limitation, owner decision pending | Medium (zero exposure today — release not distributed) | HIGH | `app/build.gradle.kts:35,42`; `RulesScreen.kt:177-184`; OPD #27 (vault); ADR #142 honest limits (c) |
| A-04 | INV-7 | Baseline VPN service logs qnames and destination IPs (`nxdomain ${query.qname}`, `tcp connect upstream dst=…`, hex dumps) — feature logs comply, baseline logs do not | Verified invariant-letter violation, documented carve-out | Low (logcat only; no upload/storage) | HIGH | `DnsSinkholeVpnService.kt:366,409,543,565,629,638`; test report §9 caveat; INV-7 text (plan line 229) |
| A-05 | DNS enforcement | A window-only website rule blocks all day at the DNS layer (the DNS engine has no clock) | Accepted design ceiling | Medium (UI implies a window) | HIGH | `DomainBlocklist.kt:17-29,47`; ADR #66 decision 2; V1 doc line 243 |
| A-06 | Attribution | ECH/DoH (Chrome GREASE ECH) flows are unattributed by design; enforcement backstops are the URL-bar path and DNS shaping | Accepted limitation, documented | Medium | HIGH | ADR #66/#172 consequences; `sni_ech` vs `sni_ok` (ADR #181); meter memory |
| A-07 | Meter / accounting | Website duration accrual is invariant-exact; overlapping flows counted once; idle tail bounded at 15 s; persistence byte-identical | Verified guarantee | — | HIGH | ADR #181 (500-seed oracle, device 55,708 ms, overlap 44,644 ms, restart identical); UDP leg 2026-10-18 (75,014 ms vs 75,000 prediction, `udp_dropped=1`) |
| A-08 | GCP monitoring | R-03 "no sweep execution" incident is closed; policy pins `status="ok"`; sweep executes ~1/min; zero open incidents | Verified resolved | — | HIGH | Live 2026-10-18: `projects.alerts` → 0 open; ok-series 358 points 07:27→13:24Z; policy 4769902381008384079 autoClose 3600 s; official absent-data semantics (docs.cloud.google.com/monitoring/alerts) |
| A-09 | Pairing security | Both callables enforce App Check; Firestore rules are least-privilege (function-only bookkeeping); envelopes AES-256-GCM with sequence-derived nonces; unreadable-pair epoch invalidation device-run | Verified guarantee (client/backend) | — | HIGH for code/rules; MEDIUM for end-to-end | `functions/index.js:64`, `functions/partner-sos.js:211`; `firestore.rules`; `EventCipher.kt:21-36`; ADR #113/#125 device legs; memory `pairing_emulator_closed` |
| A-10 | Data protection | `allowBackup=false` + cloud/transfer exclusions for every store; SQLCipher stores; reset only behind a dialog; destructive data/db suites emulator-guarded | Verified guarantee | — | HIGH | Manifest; `data_extraction_rules.xml`; `backup_rules.xml`; `MainActivity.kt:1159-1171`; `RecoveryManager.kt:74`; ADR #116/#122 |
| A-11 | Boot/locked boot | DE snapshot restores enforcement pre-unlock; stale expiry can persist in the DE cache until the authoritative load (bounded to the locked window) | Sub-case of A-02, bounded | Low | HIGH (code) / MEDIUM (live locked-boot run) | `RulesSnapshotPublisher.kt:84-99`, `BootReceiver.kt`; ADR #37 |
| A-12 | SOS | Backend deployed inert behind server kill switch; client FAB always present (RC gate removed); FAB-tap/repair device legs parked | Known state, owner-gated | Medium (feature incomplete by plan) | MEDIUM | ADR #158/#171; SOS memory; §12.3 legs previously green |
| A-13 | Two-device / platform legs | Pairing two-device legs, direct BOOT_COMPLETED delivery, live midnight rollover, and release-flavor device runs remain blocked | Unresolved uncertainty | — | — | Only one physical device; no safe reboot; no clock control; release APK not distributed |
| A-14 | Test evidence | Fresh full JVM suite green; prior device legs (PF 8/8, exhaustive review 12/12) unchanged | Verified evidence | — | HIGH | `testDebugUnitTest` today: 1,796/0/0/0 |

Severity scale: impact × likelihood × exposure × detectability × recovery. "Verified defect" =
code path proven by direct reading; no device run asserts the user-visible behaviour yet.

---

## A. Executive assessment

The project's tested core is in strong shape: metering is invariant-exact under an independent
oracle and three device ground-truth legs; the release-blocking GCP alert is genuinely closed with
live evidence; pairing crypto/App Check/rules and data-protection boundaries hold; the full JVM
suite is green today. Confidence is **not** uniform: two real inconsistencies were found in the
website-rule semantics (A-01 quota-vs-window, A-02 expiry at the DNS layer), both code-verified
but not device-run; a known release-honesty gap (A-03) and a documented INV-7 carve-out (A-04)
remain open decisions.

This is not a declaration of correctness. It is a statement that the areas inventoried have
proportional evidence, and that the important failure modes found are named above.

## B. Highest-priority findings

1. **A-02 (Medium, verified defect).** End-dated website rules stay enforced at DNS after expiry.
   Scenario: a website block with Ends = Friday; on Saturday Chrome still gets NXDOMAIN because
   the published blocklist has no clock and is only rebuilt when the app publishes a new snapshot.
   The accessibility path already allows the rule; the network path does not. Recovery is opening
   MyExodus. Failure surface: time boundaries + two enforcement authorities disagreeing.
2. **A-01 (Medium, verified defect).** The editor rejects a limit longer than the rule's window
   with a false explanation. Engines accrue and fire the quota on day scope only, so the limit can
   fire outside the window. The user cannot express "block 22:00–07:00 nightly and cap me at
   2 h/day". ADR #138's coherence premise conflicts with implemented engine semantics —
   product decision needed (fix copy/allow config, or make quota window-scoped).
3. **A-03 (Medium exposure when release ships; owner decision pending).** Release builds let
   users set website limits that silently do nothing and show no notice. OPD #27 records both
   readings; release is not distributed today.
4. **A-04 (Low).** Baseline VPN diagnostic logs print hostnames/IPs, violating INV-7's letter.
   Documented carve-out in the test report §9; logcat is adb-only in practice, so exposure is
   minimal. Smallest fix: debug-gate those lines.

## C. Confidence by feature

| Feature | Confidence | Basis / gap |
|---|---|---|
| Website duration metering | HIGH | Oracle + exact-ms device legs + UDP leg today; residual: ECH/DoH unattributed (A-06) |
| DNS blocking (static rules) | HIGH | JVM blocklist tests + device NXDOMAIN legs; limit-only rules correctly excluded |
| Website limits in release | LOW (inert by design) | A-03, OPD #27 |
| Rule config semantics (window/quota/expiry) | MEDIUM | A-01, A-02, A-05 |
| App blocking / usage accounting | HIGH on X6532 | PF 8/8, DW deltas 99.4 %/99.69 %, repair legs; OEM matrix beyond X6532 unrun |
| VPN lifecycle | HIGH on debug X6532 | ADR #140/#142 device legs; release path read-only |
| Rules engine / schedules / day scope | HIGH | JVM + device; A-01 copy inconsistency |
| Persistence / migrations / SQLCipher | HIGH | Migration and interrupted-migration instrumented tests on device+emulator; backup excluded; user-confirmed reset |
| Pairing / identity | MEDIUM–HIGH | Crypto/rules/epoch legs green; two-device end-to-end blocked |
| Activity layer | HIGH | Device leg 2026-09-29 |
| Notifications / INV-7 | HIGH | ADR #179 device-proven; PRIVATE visibility |
| SOS | MEDIUM | Backend inert, client ungated; FAB-tap legs parked |
| Streaks / milestones / widget | HIGH | Device-proven delivery catch-up and widget v3 |
| Recovery / override / signing | HIGH | Both signing-fallback legs proven |
| Build / distribution | MEDIUM | Debug pipeline proven; release signing not cut over; GitHub Releases pending |
| GCP backend / monitoring | HIGH | App Check enforced, rate-limiter probes, live alert verification |

## D. Cross-feature / architectural findings

1. **Two enforcement authorities for website rules** (accessibility decision engine vs DNS/VPN
   engine) is deliberate (ADR #66), but time predicates were only applied in the decision engine.
   A-02 is the unintentional consequence; A-05 is the accepted one.
2. **The published snapshot is a build-time projection.** Everything time-dependent that the DNS
   path enforces must either be re-evaluated at decision time or trigger a rebuild. Today neither
   happens at expiry.
3. **Rule-editor validation encodes a model of the engine.** A-01 shows that model drifting from
   `evaluateRule`. Validation copy should be derived from the same semantics the engines implement.
4. **Release honesty is a code-level gating problem, not a rollout problem.** The engine mode is
   known at composition; hiding or annotating limit controls when mode < ENFORCE is small and
   already partly designed (`domain_usage_banner_off` exists).
5. **INV-7 enforcement is scoped to the new feature package**; the baseline service predates it
   and is excluded by policy. That is a conscious boundary, not an oversight — but it should be
   closed before any logs leave the process (they do not today).

## E. Test / verification gaps and what each would establish

| Gap | What it would establish |
|---|---|
| No test for DNS enforcement after `blockExpiresAt` | That the network path agrees with the decision engine at expiry (would fail today) |
| No test for quota firing outside a time window vs the editor message | Which semantics the product actually wants (A-01) |
| No test asserting INV-7 over the baseline VPN logs | That no hostname/IP reaches logcat in any build |
| Two-device pairing end-to-end legs | Real relay/FCM/ack round trip across two attested devices |
| Release-flavor device smoke run | That release-mode limits are honestly inert and block rules work |
| Direct BOOT_COMPLETED run | That boot delivery (vs `MY_PACKAGE_REPLACED`) arms enforcement |
| Live midnight rollover | Real-clock day rollover (currently oracle/JVM only) |
| OEM matrix beyond X6532 | Generalisation of the accessibility/FGS paths |

Note: a high test count is not high confidence. The 1,796 green tests prove implemented behaviour;
they do not prove the intended product semantics where the two diverge (A-01/A-02 are exactly that
class — no test could pass while the user-visible behaviour was wrong, because no test asks).

## F. External research

| Question | Source | Documented behaviour | Effect |
|---|---|---|---|
| Why does a `conditionAbsent` alert stay open after data resumes? | Google Cloud Monitoring docs: `alerts/concepts-indepth`, `alerts/incidents-events` (verified 2026-10-18) | A time series is metric type + one label set; incidents close when an observation arrives for **that series** (or autoClose + 24 h). `autoClose` default 7 days, minimum 30 min. | R-03's old incident closed only via the 24 h path; fix (ADR #180) pins `status="ok"` so recovery resumes the same series. Live check today confirms. |
| Why is `sni_ok` flat during Chrome traffic? | RFC 9849 §6.2 (GREASE ECH) | Chromium sends GREASE ECH on every TLS connection | Correct parser behaviour (`Result.Ech`), flows DNS-attributed; not a meter defect. |
| Is logcat readable by other apps? | Not re-researched this sweep | Assumption: adb/system can read; third-party apps cannot without `READ_LOGS` | Only affects A-04 severity; recommendation unchanged. Marked as an assumption, not a verified fact. |

## G. Verified guarantees (evidence-backed, not a no-defects claim)

- Meter arithmetic: union-of-tails exactness (500-seed differential oracle), exact device totals
  (55,708 ms; overlap 44,644 ms), byte-identical totals across force-stop/relaunch, UDP leg
  75,014 ms with `udp_dropped=1` on the official counter line.
- Limit-only website rules are excluded from the DNS policy blocklist, so a daily limit never
  over-blocks at resolution time (`DomainBlocklist.kt:47`, JVM tests ×10).
- Static DNS rules are mode-independent and match by dot-boundary suffix with IDNA normalization.
- App-rule expiry is honoured at decision time (`BlockDecisionEngine.kt:159`).
- The rules snapshot drops disabled/expired rules at one filter point (`RulesSnapshot.kt:40`).
- Reset of all data requires an explicit dialog (`MainActivity.kt:1159-1171`, `RecoveryManager`).
- Backup/transfer of every store is excluded (`allowBackup=false` + extraction rules).
- Both callables enforce App Check; `.env`-independent admin FCM; bookkeeping collections are
  function-only in rules.
- Pairing envelopes are AES-256-GCM; deterministic nonces are safe because sequence monotonicity
  guarantees uniqueness; unreadable-pair epoch invalidation was device-run.
- FGS re-arm on every process start, widget JobScheduler backstop, and one all-habits milestone
  sweep at boot/foreground are device-proven.
- GCP sweep executes (~1/min, continuous ok-series) and zero incidents are open.

## H. Recommended action order

Immediate (before any release build):
1. **A-02** — make the DNS/engine paths consult `blockExpiresAt` at decision time (carry expiry in
   `DomainRule` / blocklist entries) or schedule a rebuild at the earliest expiry. Smallest,
   consistent with the single-filter-point design.
2. **A-01** — owner product ruling: quota window-scoped or day-scoped? Then either fix the
   validation copy/condition or gate accrual by window. Do not change silently; ADR #138 is on
   record.
3. **A-03** — rule OPD #27 before the first release APK (wire the existing banner/hide control).

Next hardening window:
4. **A-04** — debug-gate the baseline VPN diagnostic logs (or strip in release).

Essential verification when resources allow:
5. Two-device pairing/SOS legs; release-flavor smoke run; direct BOOT_COMPLETED leg.

Accepted limitations (documented, no action unless the product changes):
6. A-05 (window-only DNS), A-06 (ECH/DoH attribution), A-11 (locked-boot stale expiry sub-case).

Optional: OEM matrix runs; INV-7 CI extension over the baseline service.

## I. Remaining uncertainties and what would resolve them

| Uncertainty | Resolving evidence |
|---|---|
| Intended quota↔window semantics (A-01) | Owner ruling; then one JVM test encoding it |
| Whether end-dated website rules must unblock without an app open (A-02) | Fix + one JVM test + one device leg (expiry passes while Chrome browses) |
| Release-mode rollout timing (A-03) | OPD #27 ruling; release APK device smoke run |
| Logcat exposure model (A-04) | Android docs on `READ_LOGS` / logcat isolation (or accept the adb-only assumption) |
| Two-device pairing/SOS reality | Second physical device; blocked today |
| Direct boot delivery | A safe reboot run; boot code is identical to the device-proven `MY_PACKAGE_REPLACED` path |
| OEM generalisation | Runs on Samsung/Xiaomi/non-GMS hardware |
| Live midnight rollover | Root/clock-control device or an emulator date change; oracle/JVM evidence today |

*This report is investigation output. No code, schema, security control, or ADR was changed by
this audit.*

---

## J. Fix close-out addendum (2026-10-18)

Owner follow-up mandate: implement the §H action plan permanently, with tests and documentation,
and no git commits. Scope: A-01 through A-04. A-05/A-06/A-11 were not touched (accepted
limitations); no schema, security control, or release configuration changed.

| ID | Status | Fix | Evidence |
|----|--------|-----|----------|
| A-01 | **FIXED** | `ModeError.LimitExceedsWindow` and the `editor_limit_exceeds_window` copy removed; the real bounds (≥1 mechanism, non-zero window, 1 min–<24 h limit) remain | ADR #183; `RuleModeTest.validate_limitLongerThanItsWindow_isAccepted_dayScopedQuota` plus the retained boundary cases; engine semantics re-read at `BlockDecisionEngine.kt:164-170` |
| A-02 | **FIXED** | Expiry carried on `DomainRule`/blocklist entries and consulted at every decision and accrual point; snapshot rebuild is now an optimisation | ADR #182; 10 regressions across `DomainRuleProviderTest`, `DomainBlocklistTest`, `DnsShaperTest`, `AttributionResolverTest`, `UsageLedgerTest`, `RuleCounterTest`; **device: stale snapshot went NODATA `an=0` → forwarded `an=2` at expiry with the app never opened** |
| A-03 | **FIXED** | The Rules banner and editor caption state that website limits are unavailable outside ENFORCE; the limit switch is disabled for websites, an existing value is preserved | ADR #185; **device (debug override OFF): banner + caption rendered, node dump `checked=true, enabled=false` on "Limit daily use"**, other mechanisms enabled; state restored (mode ENFORCE, VPN ACTIVE, private DNS off) |
| A-04 | **FIXED (scope expanded)** | Every qname/IP/URL/hex diagnostic in `DnsSinkholeVpnService` **and `BrowserUrlScanner`** is behind `BuildConfig.DEBUG`; TUN writes extracted out of log expressions before gating; CI check 7 fails ungated sensitive logs | ADR #184; `DomainUsageCiChecksTest` 12/12 including both new fixtures; `assembleDebug` + `compileReleaseKotlin` successful |

**Audit correction.** A-04's evidence row listed only `DnsSinkholeVpnService`. During the fix, the
same violation was found in `BrowserUrlScanner` (raw address-bar text in two no-op paths, and the
address-bar discovery line). The audit missed it because it re-read the scanner for decision
semantics, not every log line. The fix covers both files and the CI check covers all main sources.

**Verification run.** Full JVM suite after the fixes: **205 suites / 1,811 tests / 0 failures /
0 errors / 0 skipped** (baseline at audit time: 1,796; +15 net). Both builds compile in debug and
release. Device state left as found: one original website rule, `mode=ENFORCE`, VPN `ACTIVE`,
private DNS `off`, launcher foreground.

**What this close-out does and does not change.**
- It closes the four *code-verified* defects. A-02 and A-03 now additionally have device legs; A-01
  is validation/copy only and needs none.
- It does **not** resolve OPD #27 (whether release should ever enable website metering). With A-03,
  release builds now tell the truth either way; the product decision remains open.
- It does **not** unblock A-12/A-13 legs (two-device pairing/SOS, direct BOOT_COMPLETED, live
  midnight rollover, release-flavor device run, OEM matrix). Those remain named, not faked.
- A-05 (window-only DNS over-block) and A-06 (ECH/DoH attribution) are unchanged by design; A-11's
  locked-boot stale-expiry sub-case is bounded and unchanged.
