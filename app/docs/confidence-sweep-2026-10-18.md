# Project-wide confidence sweep — 2026-10-18

Owner mandate: for every feature, name exactly where confidence was below 100 %, research the
official sources, run tests, and replace assumption with proof. This file is the **confidence
ledger**. The defect ledger is `project-audit-2026-10-18.md` (findings A-01…A-14); the V1 status
matrix (`MyExodus V1.md`, requirement verdict table) is the scope authority.

**Honesty rule for this file:** an untested claim is never written as proven. It is either a
**blocked leg** (with the reason) or an **accepted limitation** (with the product decision that
accepted it). "100 %" below means *100 % of what can be evidenced today*, not a promise about
untested environments; the residual column says exactly what remains.

## Method

1. Inventory the shipped surface from the code tree and the V1 requirement verdict table.
2. For each feature, state the claim being relied on and the evidence that proves it.
3. Where the evidence was missing, produce it: JVM regression, device leg, API-37 emulator leg,
   or an official-documentation check (research playbook: internal-first, primary sources).
4. Anything that still cannot be tested is named and left open — never marked done.

## Evidence index

| ID | Evidence | Produced |
|---|---|---|
| E-01 | Full JVM suite: **205 suites / 1,811 tests / 0 failures / 0 errors / 0 skipped** | 2026-10-18, this session |
| E-02 | `assembleDebug` and `compileReleaseKotlin` green | 2026-10-18, this session |
| E-03 | `scripts/domainusage-ci-checks.sh` all checks incl. INV-7 ungated-log scan: OK | 2026-10-18, this session |
| E-04 | X6532: A-02 expiry leg — stale snapshot AAAA `an=0` before expiry, forwarded `an=2` after expiry with the app never opened | 2026-10-18 |
| E-05 | X6532: A-03 honest-UI leg — website-limit switch node `checked=true, enabled=false` outside ENFORCE; device restored to ENFORCE | 2026-10-18 |
| E-06 | X6532: meter exactness — device totals 55,708 ms (overlap 44,644 ms), UDP 75,014 ms, byte-identical totals across force-stop/relaunch | recorded, re-cited |
| E-07 | X6532: PF 8/8; DayWrite deltas 99.4 % (apps) / 99.69 % (domains) | recorded, re-cited |
| E-08 | X6532: both signing-fallback legs, FGS process-start re-arm, widget JobScheduler backstop, milestone catch-up, activity-layer device leg | recorded, re-cited |
| E-09 | API-37 emulator: edge-to-edge renders correctly; predictive back works (Settings → Protection pane → BACK → Settings list → BACK → Home, activity intact) | 2026-10-18, this sweep |
| E-10 | API-37 emulator: `EnforcementForegroundService` runs as `specialUse` (`types=0x40000000`, `isForeground=true`); background FGS start from a cold process is allowed under targetSdk 36 — `Background started FGS: Allowed … code:SYSTEM_ALLOW_LISTED … targetSdkVersion:36` (battery whitelist) | 2026-10-18, this sweep |
| E-11 | API-37 emulator: **direct `BOOT_COMPLETED` delivered** — `MyExodusBoot: android.intent.action.BOOT_COMPLETED received` 17:32:22.751, process started by the broadcast, FGS tick 20 s later, VPN `startRequested=true`, `du-status vpn=ACTIVE day=2026-10-18`. (The emulator's ordered boot queue took ~79 s to reach the app because 65 system receivers drain first; delivery is the claim, not latency.) | 2026-10-18, this sweep |
| E-12 | API-37 emulator: **live midnight rollover** — clock set to 2026-10-19 00:01:30, engine tick advanced `day=2026-10-18 → 2026-10-19`, persisted `domainusage_last_day_key=2026-10-19`, `clock_jump=0`; clock restored and network time re-enabled | 2026-10-18, this sweep |
| E-13 | Official docs: since Android 4.1 (API 16) "only privileged system apps can be granted access to read `logcat`, by declaring the `READ_LOGS` permission" (`developer.android.com/privacy-and-security/risks/log-info-disclosure`) — closes the A-04 logcat-exposure assumption | 2026-10-18, this sweep |
| E-14 | Official docs: apps targeting Android 16 get mandatory edge-to-edge and default predictive back; the Android 16 behaviour-change pages list no target-16 changes affecting VPN, accessibility, JobScheduler, notifications or boot delivery (`developer.android.com/about/versions/16/behavior-changes-16`, `/behavior-changes-all`) | 2026-10-18, this sweep |
| E-15 | Official docs: `AppWidgetProviderInfo.updatePeriodMillis` updates "at most once every 30 minutes" and is not exact; WorkManager periodic work minimum 15 minutes and not exact — the widget's 30-minute JobScheduler backstop sits inside documented platform behaviour (ADR #145) | 2026-10-18, this sweep |
| E-16 | GCP: App Check enforced on Firestore, live alert verification, the R-03 success-series fix (ADR #180), zero open incidents | 2026-10-18 |
| E-17 | X6532: pairing crypto/enrollment/epoch legs green; two-device end-to-end remains blocked (one physical device) | recorded, re-cited |
| E-18 | Persistence: migration and interrupted-migration instrumented tests on device + emulator; backup/transfer excluded (`allowBackup=false` + extraction rules) | recorded, re-cited |
| E-19 | Recovery: both signing-fallback legs proven; SOS server kill switch verified; client Remote Config gate removed (ADR #158) | recorded, re-cited |
| E-20 | Operational: wireless ADB link healthy — 5/5 round trips at 50–110 ms on the same serial (not a product feature; recorded for the testing workflow) | 2026-10-18, this sweep |

## Feature ledger

Confidence applies to the **shipped** portion. "Partial" and "Not built" rows are the V1
status-matrix verdicts; their gaps are tracked product decisions, not hidden defects.

### Core blocking and metering

| Feature (Req) | State | Confidence | Proof | Residual / blocked |
|---|---|---|---|---|
| Active restriction blocking — Path A accessibility (1, 17) | Shipped | 100 % evidenced | JVM `BlockDecisionEngine*` suites + combination sweeps; PF 8/8 (E-07); decision path has no network call (offline by construction) | OEM matrix beyond X6532 — blocked (one device) |
| Time-range schedules (2) | Shipped | 100 % evidenced | ADR #68; `BlockDecisionEngineScheduleTest`; boot reinstatement | — |
| Day-of-week scheduling (3) | Shipped | 100 % evidenced | ADR #68/69 D5; schedule tests incl. overnight | — |
| App daily usage limits (4) | Shipped | 100 % evidenced | ADR #94/#103; PF 8/8; DayWrite 99.4 % (E-07); `UsageAccountingEngineTest` (202 usage tests) | — |
| Website daily limits via URL bar (4) | Shipped | 100 % evidenced in debug; release inert **by design** | ADR #172/#174/#179; site-named warning device-proven; release-mode UI honest (A-03, E-05) | OPD #27 (enable metering in release?) — owner product decision |
| Website duration metering (DNS/TLS/UDP) | Shipped | 100 % of attributable flows | Union-of-tails differential oracle (500 seeds); exact device totals incl. overlap and UDP (E-06) | ECH/DoH flows are unattributed by design (A-06, ADR #66) |
| Static DNS blocking | Shipped | 100 % evidenced | `DomainBlocklistTest` (incl. limit-only exclusion); device NXDOMAIN legs; suffix matching with IDNA normalization | — |
| Rule config semantics — window/quota/expiry (5) | Shipped half | 100 % evidenced after A-01/A-02 | A-01 removal (ADR #183); expiry-at-decision-time in DNS + engine (ADR #182, E-04); JVM regressions ×10 | "Clock state machine" prose is stronger than the shipped construction (wall-clock expiry); matrix already marks Partial |
| VPN lifecycle / tunnel re-arm | Shipped | 100 % evidenced | ADR #140/#142 device legs; E-11 boot re-arm on API-37 | — |
| Enforcement FGS keepalive (30) | Shipped | 100 % evidenced | E-08 (X6532); E-10/E-11 (API-37, targetSdk 36 background-start allowed) | — |
| Block overlay / tapjacking (NFR-S10) | Shipped | 100 % evidenced | ADR #177 full-width cover device-proven; `setFilterTouchesWhenObscured`; `BlockOverlayViewTest` | — |
| Friction timer / recovery / override | Shipped | 100 % evidenced | ADR #61; recovery suites; signing fallback both legs (E-08) | — |
| Boot behaviour | Shipped | 100 % evidenced | direct `BOOT_COMPLETED` on API-37 (E-11); `MY_PACKAGE_REPLACED` on X6532 (ADR #152) | Direct boot on the **physical** X6532 remains unrun (no safe reboot) — stated, not faked |
| Day rollover (live clock) | Shipped | 100 % evidenced | live rollover on API-37 (E-12) + JVM clock-jump tests (backward jump cannot reopen the day) | Physical-device live midnight remains unrun — emulator leg covers the same code path |

### Accountability pillar

| Feature (Req) | State | Confidence | Proof | Residual / blocked |
|---|---|---|---|---|
| Pairing enrollment / crypto / epochs | Shipped | 100 % of single-device surface | X6532 epoch legs (E-17); adversarial pairing tests; AES-256-GCM envelopes; attestation verifier | Two-device end-to-end — blocked (second physical device absent) |
| Activity layer (7) | Shipped | 100 % evidenced | ADR #115; device leg 2026-09-29 | — |
| Partner notifications (6) | Shipped half | 100 % of shipped copy | ADR #115/#119/#134/#179; INV-7 amended for site-named limit warnings; PRIVATE visibility | Partner-aware wording for block/recovery copy is a tracked product call |
| Motivational messages (15) | Shipped | 100 % evidenced | ADR #115/#121/#135; consent matrix tests | — |
| Multi-partner roster / dual-role (16) | Partial | 100 % of structural surface | ADR #99/#111; §19.5; fan-out fixed | StrictMode partner-approval gate stays `false` — recorded open decision |
| Sync queue / outbox (27) | Partial | 100 % of shipped half | cap + idempotency + watermark stress tests; relay rejection checker | Prioritised eviction (retain relapse/tamper) not built — tracked |
| SOS (35) | Shipped (backend inert) | 100 % of server/client controls | Steps 0–13; server kill switch verified; ADR #158 | FAB-tap + repair device legs owner-gated (A-12); two-device legs blocked |
| DPA deletion / backup exclusions (29) | Partial | 100 % of shipped half | `BilateralDeletionTest`; 14-day purge; `allowBackup=false` | No user-facing local export/deletion surface; server retention prose revision tracked |

### Habits, recovery, widget

| Feature (Req) | State | Confidence | Proof | Residual / blocked |
|---|---|---|---|---|
| Days-free counter (11) | Shipped | 100 % evidenced | `StreakTimer` dual-clock; device legs | — |
| Milestones / celebrations (12) | Shipped | 100 % evidenced | ADR #104 catch-up (all-habits sweep at boot/foreground), ADR #164 finding #22; device-proven | — |
| Widget (36) | Shipped | 100 % evidenced | v2.2/v3 (ADR #166/#173), 10-child Glance cap; 30-min backstop inside documented platform behaviour (E-15) | — |
| Recovery resource UI (8) | Shipped | 100 % evidenced | ADR #61; device | — |
| Block-attempt tracking (9), return-home (10) | Shipped | 100 % evidenced | `attempt_log`, Home counts, W2 | — |
| Onboarding / habit timeline (18), urges (19) | Shipped | 100 % evidenced | ADR #37/#61; suites | — |
| Custom coaching (20) | Partial | 100 % of user half | ADR #61 W3/W5 | Partner visibility of steps is open decision #12 |
| Relapse flow (14) | Partial | 100 % of shipped half | ADR #115/#134/#135; non-punitive copy tests | "Reflection interface" is the confirm dialog — documented deviation |

### Platform, build, backend

| Feature | State | Confidence | Proof | Residual / blocked |
|---|---|---|---|---|
| Android 16 targeting behaviour | Shipped | 100 % evidenced | edge-to-edge + predictive back device-emulated (E-09); official change list shows no other target-16 impact on the core paths (E-14) | — |
| Persistence / migrations / SQLCipher | Shipped | 100 % evidenced | E-18; `DatabaseConstraintsTest`, migration tests, raw-key tests | — |
| Tamper / degradation model (23) | Partial | 100 % of shipped half | `EnhancedModeHealthMonitor`, degradation copy tests, `ProtectionStatusMonitor*` | `Settings.Secure` package-presence probe not implemented — tracked |
| Attestation footprints (26) | Shipped | 100 % evidenced | `AttestationVerifier` PKIX to both pinned Google roots, TEE/StrongBox, origin GENERATED | — |
| Package querying (31), pre-block warnings (32) | Shipped | 100 % evidenced | user-selected rules only; threshold matrix + device QA (ADR #86) | — |
| GCP backend / monitoring | Shipped | 100 % evidenced | E-16; App Check enforced; rate-limiter probes | — |
| Build / distribution | Partial | Debug pipeline 100 %; release compile green | E-02 | Release signing cutover + GitHub Releases + release-flavor device run — pending owner direction; OPD #27 |

### Not built (scope state, not confidence gaps)

21 (solo quarantine), 24 (sovereignty portal), 25 (escape hatch as written), 33 (rollback
protection). Partial: 5, 6, 14, 16, 20, 22, 23, 27, 28, 29. By design: 34 (local read model with
staleness buckets, no 15-minute TTL). These are the V1 status matrix's own verdicts
(`MyExodus V1.md`, requirement verdict table) and remain tracked product decisions — the sweep
found no silent divergence between the matrix and the code.

## What this sweep added

- Closed the audit's two "essential verification" legs that were previously stated blocked, on
  the sanctioned API-37 emulator: **direct `BOOT_COMPLETED`** (delivery + FGS + VPN re-arm) and
  **live midnight rollover** (day-key advance + persistence).
- Closed the A-04 research assumption with the official `READ_LOGS` documentation.
- Verified Android 16 target behaviour on a real target-36 build (edge-to-edge, predictive back,
  cold-process background FGS start under the battery whitelist).
- Re-confirmed A-01…A-04 fixes: suite 205/1,811/0/0/0, both builds green, CI checks OK.

**No new product defect was found in this sweep.** That is a statement about the evidence run,
not a claim that no defect exists — the blocked legs below are exactly where a defect could
still hide.

Adjacent test-hygiene fix (owner-reported, same date): the recurring auto-rotate flip is
harness-induced (injected input hitting the Quick Settings tile; the app has no
`WRITE_SETTINGS` and zero rotation writes). `scripts/enhanced-mode-device-test.sh` now forces
`accelerometer_rotation=0` on exit instead of restoring a snapshot that may itself be a stale
ON; ADR #129's deferred item (c) is closed. The X6532 was re-checked at `0`.

## Blocked legs (stated, never faked)

| Leg | Reason | What would close it |
|---|---|---|
| Two-device pairing / SOS / live relay round trip | Only one physical device | A second attested physical device |
| Direct `BOOT_COMPLETED` on the physical X6532 | No safe reboot (data-loss history; see device memory) | Owner-approved reboot run |
| Live midnight rollover on the physical X6532 | No clock control | Root/controlled-clock device run |
| Release-flavor device smoke run | Release signing not cut over; no release distribution yet | Signed release APK + device install |
| OEM matrix beyond X6532 | One device | Runs on Samsung/Xiaomi/non-GMS hardware |
| SOS FAB-tap / repair legs | Owner-gated (A-12) | Owner scheduling |
| Website metering in release | Product decision OPD #27 | Owner ruling |
| Prioritised eviction, recovery-phrase escrow, sovereignty portal, rollback protection | Not built; tracked partial/not-built requirements | Product scope decision |

## Cleanup state

- X6532: `mode=ENFORCE`, VPN ACTIVE, original rule intact, private DNS off, launcher foreground.
- API-37 emulator: clock restored and automatic network time re-enabled; the running instance
  carries the test rule and the advanced day key — the `default_boot` snapshot is not overwritten
  (shutdown without exit-save preserves it).
- Working tree: all changes uncommitted; the owner handles git writes.

*This ledger is investigation output plus the A-01…A-04 fixes recorded in
`project-audit-2026-10-18.md` §J and ADRs #182–#185.*
