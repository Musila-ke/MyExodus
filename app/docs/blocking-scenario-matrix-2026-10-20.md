# Blocking scenario matrix — campaign of 2026-10-20

Owner mandate: review the entire blocking engine and filter for web and mobile apps, then run
"every physically possible" use case/edge case — schedule × daily limit × block duration ×
days-of-week × rule toggling × recovery × multiple rule types × Wi-Fi/mobile data — and check
that blocks fire at the correct time. Evidence standard: facts, figures, proof; no guessing.
Escalations: line by line, file by file; each double/triple check is a full pass, no skimming.

This document is the campaign's ledger. A row is only marked PROVEN with a test name (JVM) or a
dated device log line (device). Rows not run are marked DEFERRED with the reason, never quietly
counted as covered.

## 1. Decision model under test (the authority)

`BlockDecisionEngine.evaluateRule` (`app/src/main/java/com/example/myexodus/blocking/BlockDecisionEngine.kt:146`):

1. **Rule 3 — expiry:** `blockExpiresAt > 0 && now > blockExpiresAt` ⇒ Allow.
2. **Rule 1 — schedule:** `now` inside `[timeRangeStart, timeRangeEnd)` in the rule's own IANA
   zone, on an active window day (overnight leg judged against the day the window started) ⇒ Block.
3. **Rule 2 — daily quota:** on an active day, `usageTodayMs >= dailyLimitMs` ⇒ Block. This is
   evaluated **outside** the schedule window too — the owner's 06:00–18:00 + 2 h rule still
   blocks after 18:00 once the quota is spent.
4. **Always-on:** on an active day ⇒ Block.
5. Day scope (`activeDaysOfWeek`, Monday-first) gates the whole rule — window, quota, and
   always-on are all inert on a day marked `0`.

Website rules run the same four rules (`decideUrl`); website metering is gated separately by
day scope + expiry (`DomainRuleProvider.enforcedToday`, `expiresAt`) and by the browser-owner
filter (ADR #189).

## 2. Part A — analytic decision matrix (JVM, exhaustive)

`BlockDecisionEngineCombinationSweepTest.sweep_engineMatchesAnIndependentOracleAcrossTheWholeMatrix`
runs the full Cartesian product and compares the engine against an oracle written independently
in `java.time` (the engine uses `Calendar`/`TimeZone` on purpose, so a shared bug cannot cancel):

| Axis | Values |
|---|---|
| Zone | UTC, America/New_York |
| Instant | Fri 23:30; Sat 03:00, 05:00, 06:00, 12:00, 13:00, 23:30; Sun 03:00; Mon 13:00 |
| Window (minutes since midnight) | none; 05:00–12:00; overnight 22:00–07:00; zero-length; full day; start-only; end-only |
| Day scope | all; weekend; Sat; Sun; none; six days; empty; eight chars |
| Always-on | false, true |
| Daily limit | 0, 2 h |
| Usage input | null, 0, −1, limit−1, limit, limit+1, Long.MAX_VALUE |
| Expiry | never, past, exact-now boundary, future |

**Cells: 2 × 9 × 7 × 8 × 2 × 2 × 7 × 4 = 112,896** (the factor product is asserted
independently of the axis lists — `BlockDecisionEngineCombinationSweepTest.kt:115` — so an
edit cannot silently shrink it). Re-run green on the current tree 2026-10-20 22:03:
11 tests, 0 failures. This is the combination coverage for the *decision*; the device matrix
below covers the plumbing that feeds it and reacts to it.

Hand-picked decision edges live in `BlockDecisionEngineScheduleTest` (DST, overnight, day
scope), `BlockModeMatrixTest`, and `BlockDecisionEngineDomainStressTest`.

## 3. Part B — JVM seam coverage (orchestration, accounting, input domain)

| Seam | Test class / evidence |
|---|---|
| App usage accounting, carryover identity, credit edge, warnings state machine | `UsageAccountingEngineTest` (204 tests incl. today's two regressions) |
| Ledger append-only contract, day split, retention, rebuild | `UsageLedgerTest`, `UsageLedgerAdversarialTest`, `UsageLedgerRepositoryTest`, `LedgerMaintenanceTest` |
| Gap repair algebra (floor, clips, overlap dedupe) | `UsageGapRepairTest`, `AccountingOutageSourceTest` |
| Session/interval math (stall, doubled tick, midnight split) | `RuleCounterTest` (incl. 500 randomized timelines), `UsageTickLoopTest` |
| Website metering interval, persistence, breaker | `UsagePersistenceTest`, `DomainUsageEngineBreakerTest`, `EngineModeTest` |
| Browser-owner policy (ADR #189) | `DomainUsageEngineHooksTest`, `FlowTapTest`, `BrowserPackagesTest` |
| Editor input domain (blank/zero/over-max/equal window/no mechanism) | `RuleModeTest.validate_*`, `SiteLimitTest.saveChain_*`, `clamp_keepsZeroAtZero_andBoundsTheRest` |
| Snapshot single-source enforcement predicate | `RulesSnapshotTest.isLimitTracked_*` |
| Persistence across restart (MAX never lowers) | `UsagePersistenceTest` |
| Block-screen pipeline, a11y + overlay construction | `BlockingPipelineTest`, `BlockerAccessibilityServiceConstructionTest`, `OverlayControllerTest` |

## 4. Part C — device matrix (X6532 primary; API-37 AVD sanctioned exception)

Status legend: **PROVEN** (evidence cited) · **RUN** (this campaign) · **PLANNED** ·
**CARRIED** (proven earlier, unchanged code) · **DEFERRED** (reason).

| ID | Rule shape | Step | Expected | Status |
|---|---|---|---|---|
| C-01 | App, always-on | Seed always-on rule for a spare app, open it | Block cover, no usage needed | PROVEN 2026-10-20 |
| C-02 | App, schedule only, inside window | Temp rule with window spanning now, open app | Blocked by Rule 1 | PROVEN 2026-10-20 |
| C-03 | App, schedule only, outside window | Same rule, window moved away, open app | Allowed | PROVEN 2026-10-20 |
| C-04 | App, quota only, used ≥ limit | Seed quota-only rule (limit 1 min, used 2 min), open app | Blocked by Rule 2 | PROVEN 2026-10-20 |
| C-05 | App, schedule + quota, **outside** window, used ≥ limit | Seed windowed rule outside its window with spent quota, open app | Blocked by Rule 2 (owner's shape) | PROVEN 2026-10-20 |
| C-06 | App, day scope off / on | Seed quota rule with today marked `0`, then `1` | Allow off-day; Block on-day | PROVEN 2026-10-20 |
| C-07 | App, expiry past / future | Always-on rule, backdate expiry via `rules-expire`, then restore | Allow past; Block future | PROVEN 2026-10-20 |
| C-08 | App, toggle off → use → on | Pause rule, use app, re-enable, audit | Usage carried once; journal == platform (ADR #190/#191) | PROVEN 2026-10-20 |
| C-09 | App, warning thresholds | Seed 30 min limit with 21 min used + engine notify | 10-min warning delivered once; no block | PROVEN 2026-10-20 |
| C-10 | Website, quota only | Temp rule (1 min), browse in Chrome | Accrues; at quota the flow is cut / page fails | PROVEN 2026-10-20 |
| C-11 | Website, always-on | Temp always-on rule, browse | DNS sinkhole blocks the page | PROVEN 2026-10-20 |
| C-12 | Website, schedule inside / outside | Temp rule with window spanning now, then moved away | Inside ⇒ blocked; outside ⇒ allowed (quota permitting) | PROVEN 2026-10-20 |
| C-13 | Wi-Fi → mobile data mid-session | Toggle Wi-Fi off while tunneling, browse, restore Wi-Fi | No crash; tunnel keeps forwarding; accrual continues | PROVEN 2026-10-20 |
| C-14 | Multiple rules simultaneously | Owner's live set: app rule + website quota + 2 always-on websites | Independent decisions/counters (day audit per package) | PROVEN (audit pairs, 2026-10-20) |
| C-15 | Screen-off session self-heal | Screen off/on while a tracked app runs | Session resumes, no dead window (ADR #187) | PROVEN 2026-10-21 (API-37 AVD) |
| C-16 | Process death → repair | Error-correlated kill (ANR) with app foreground, sticky restart | Gap repaired from the platform's foreground record (plan §7, ADR #103/#160/#191) | PROVEN 2026-10-21 (X6532). The literal `am force-stop` shape is **not** a repair case: ADR #160 clips the paused era and defers recovery until explicit re-enable — the matrix's original wording was wrong, now corrected |
| C-17 | Reboot persistence | Reboot, reopen app | Rules and counters intact | PROVEN 2026-10-21 on the sanctioned API-37 AVD (results log below). The X6532 is wireless-ADB only, so a physical reboot severs the link; the physical reboot leg was already proven 2026-10-18 (E-21) |
| C-18 | Recovery session × quota | Use recovery to retire a quota block, then re-enable the rule | Override retires the rule via Rule-3 expiry (source: `unlockRule` sets `blockExpiresAt = now-1`); next decision Allow; a re-enabled rule blocks again on the surviving counter | **PROVEN 2026-10-21 (X6532, real UI; owner-authorized).** Always-on temp rule → cover → "Open MyExodus Recovery" → "Use it now (override)" → "End the block": `attempt logged: outcome=OVERRIDE`; rule read-back `expiresAt=1792565222093` (stamped `now−1`); relaunch → `decide com.google.android.gm gen=3 -> Allow`, no wall; cleanup `usage rule/row … ABSENT`. The original quota-only premise was wrong: the daily-limit wall deliberately has no recovery button (`DailyLimitBlocked` case), so the escape hatch is exercised on the app wall, which is the same shared `startRecovery` → Recovery tab path |
| C-19 | Input domain on device (blank/zero/over-max limit, equal window) | Editor attempts | Rejected with the field-level message; nothing saved | PROVEN 2026-10-21 (X6532, website editor) |

## 5. Part D — what "every combination" means here (honest scope)

The **decision** cross-product is exhaustively enumerated: 112,896 cells (Part A), independent
oracle. The **device** matrix is not the same cross-product — running 112,896 device cells would
take weeks and add no information beyond plumbing. Instead each mechanism that feeds or reacts
to the decision is exercised at least once with real OS integration: schedule, quota, always-on,
day scope, expiry, toggling/carryover, warnings, website quota, website DNS, network switch,
multi-rule independence, process death, screen-off. Combinations of mechanisms on the *same*
rule are decided by the Part A sweep; their device plumbing is shared, and C-05 is the owner's
specific cross-mechanism shape verified end-to-end.

## 6. Results log

(Filled during execution; each entry cites the device log line or test name.)

### 2026-10-20 — pre-matrix defect work (recorded here for one ledger)

- C-08 pre-fix failure: `id=18115 RECONCILIATION credited=164998ms`, audit `diff=-164998ms`.
- Fix: ADR #191 (`CREDIT_EDGE_KINDS` includes MIGRATION_CARRYOVER). JVM: 13 classes / 204
  tests / 0 failures. Post-fix device: carryover 74,106 ms once; audit `diff=0ms`; no gap-repair
  line.

### 2026-10-20 — Part C execution (X6532, spare app `com.google.android.gm`)

- **C-01 PROVEN.** `appblock-seed --es pkg com.google.android.gm` → BlockerActivity dump:
  "Gmail is blocked" / "Blocked by your MyExodus rules"; no tracker usage needed.
- **C-04 PROVEN.** `usage-seed --es pkg … --ei limitMs 60000 --ei usedMs 120000 --ez quotaOnly true`;
  `usage-read` → `rule … limit=60000 alwaysOn=false window=null..null`, tracker `used=161278
  blocked=true`; launch evicted with the block screen.
- **C-05 PROVEN (owner's shape).** `usage-seed --ei limitMs 60000 --ei usedMs 120000` (default
  window = seed time + 5 h, i.e. **outside**); `usage-read` → `window=158..218` at 21:38,
  `used=161278`; screenshot at launch: "Gmail is done for today / Today's time limit is used
  up"; `MyExodusBlocker: block close pkg=com.google.android.gm evicted=true` at 21:38:06.606 and
  21:38:09.031. Rule 2 fired outside the schedule window, as specified.
- **Seam note (debug-only).** The existing seeds could not express a window spanning now, an
  always-on + expiry shape, or a scheduled website rule. Added optional `wStart`/`wEnd`,
  `alwaysOn`, `expiresInMs` to `usage-seed` and optional `alwaysOn`/`wStart`/`wEnd` to
  `du-addrule` (both debug source set, both still write through `RulesRepository`). Production
  code unchanged.
- **C-02 PROVEN (Rule 1, inside window).** `usage-seed --ei wStart 1273 --ei wEnd 1333
  --ei limitMs 3600000 --ei usedMs 0` at 21:43 (nowMin 1303); `usage-read` confirmed the
  window; Gmail launch → BlockerActivity focus 21:43:19; `block close
  pkg=com.google.android.gm evicted=true` ×4 at 21:43:56. Zero usage, so only the schedule
  could have fired.
- **C-03 PROVEN (outside window).** Same limit/usage, window moved to 164..224; Gmail kept
  focus, no `block close` line.
- **C-06 PROVEN (both legs).** Quota spent (60,000/60,000) with `days=1011111` (Tuesday off,
  Mon-first) → Gmail allowed; re-seeded `days=1111111` → blocked.
- **C-07 PROVEN (settled).** Always-on + future `blockExpiresAt=1792522006478` → blocked;
  always-on + past `blockExpiresAt=1792521836762` → allowed, read-back `enabled=true
  alwaysOn=true` with the past expiry. The first launch after the past-expiry write was still
  blocked and settled ~15 s later. Cause, measured: `rules-expire` is a raw UPDATE that does
  **not** bump the policy version, so no snapshot publish is triggered at all; the settle came
  from an unrelated reload. Production saves (editor, and the seed path) do bump — measured
  tonight at ≈2.0 s seed→publish (v137 at 21:56:50 after a 21:56:48 seed; v138 at 21:57:00).
  The sweep therefore waits for a newer `Authoritative snapshot vN` after every seed.
- **C-09 PROVEN (warning).** Quota-only, limit 1,800,000 ms, used 1,260,000 ms; 8 s of Gmail
  foreground produced `observe com.google.android.gm +0ms -> 1260000ms/1800000ms
  ShowWarning(level=1, remainingMs=540000)`; row `used=1286417 ... w1=DELIVERED w2=NOT_DUE
  blocked=false`. `notifyEngine` alone only resets warning state; warnings derive inside an
  accrual tick, which is why the leg needed foreground time.
- **C-10 PROVEN (website quota).** Temp rule `ae2d4f43…` on example.com, limitMs=60000
  (`du-addrule --el limitSec 45` clamps to the 60 s floor via `clampStoredLimitMs`); usage
  crossed 64,479 ms; block card "example.com is blocked / Blocked by your MyExodus rules".
- **C-11 PROVEN (website always-on).** Temp always-on rule `2b851398…` on iana.org →
  `nxdomain iana.org v4 rule=policy` ×6 → "iana.org is blocked" card.
- **C-12 PROVEN (website schedule, both ways).** Metered neverssl.com rule (18,000 s) with
  window 1281..1341: `url scan 'neverssl.com/?myexodus=inside' -> Block`. Window moved to
  171..231: `-> Allow`, and the redirect `brightrelaxedsilverplan.neverssl.com/online/ ->
  Allow`. Note: neverssl.com is an HTTP-only captive-portal page used solely to read the
  address bar — nothing entered or downloaded; every remaining website leg uses HTTPS only
  (owner instruction).
- **C-13 PROVEN (network switch mid-session).** Detached `svc wifi disable; sleep 25; svc wifi
  enable` while tunneling; ADB reconnected on the first attempt 21:53:22. During the gap
  `MyExodusBlockerFgs: tick … authority=FULL` at 21:52:55; after restore `DUPROBE
  status=Active mode=ENFORCE vpn=ACTIVE`; example.com usage 4,700 → 28,359 ms; HTTPS
  navigation allowed; flow owners logged `browser=true`.
- **Ground-truth check (owner question: is this what Digital Wellbeing sees?).** Seeded matrix
  numbers are synthetic by design. The real-usage authority is the platform reconcile record:
  live Instagram rule journal=12,822,612 ms vs platform=12,824,110 ms, `diff=1498ms` — within
  the documented ≤2 s binder seam (ADR #191), i.e. no undercount and DW-equivalent.

### 2026-10-20 — 84-cell device sweep, run 1 (stopped at 41/84 by harness contamination)

- Rows A1–WQ24: 24/24 matched their oracle, each verdict carried by a fresh
  `decide com.google.android.gm gen=… -> Block|Allow` line for that cell's launch.
- Rows WQ25 onward: **voided as engine evidence.** Last engine decision in the buffer is
  22:06:30.849 (`-> Allow`, WQ24); zero `decide` lines follow, while `block close` repeats at
  ~11.7 s cadence. Cause, measured: the WQ25 seed (quota `at`, used=limit) ran while Gmail was
  still foreground from WQ24's ALLOW cell; the usage engine's 10 s tick
  (`USAGE_TICK_MS`, BlockerAccessibilityService.kt:1156) read the spent quota for that
  still-front app and raised the `DailyLimitBlocked` wall at 22:06:31.092 — a path that logs
  no decide. The wall persists until its Close action (owner rulings 2026-09-27 / 2026-10-03),
  and while it is up our own MainActivity's content-changed events are not delivered (53
  own-package content-changed events before 22:06:31, **0 after**), so the sweep's card reset
  could never lift it. Every later ALLOW-expecting cell was re-evicted by that standing wall
  with no fresh decision. This is a harness defect, not an engine verdict.
- Harness fixes (debug source set only; production unchanged):
  - New `overlay-dismiss` suite in `DeviceVerificationActivity`: finds the wall's "Close" node
    in the overlay's own a11y tree and performs a real `ACTION_CLICK` — the same listener a
    finger runs. No wall ⇒ no-op. Host usage line updated.
  - `scripts/blocking-matrix-sweep.sh` resets the scene before every cell (Close + HOME), so a
    spent-quota seed never reaches the usage tick with the previous cell's app still foreground.
  - Verdicts now require a fresh `decide` line for the cell's own launch; a block close with no
    new decision is reported as `STALE-CARD` (counted FAIL, never as a Block).

### 2026-10-20 — 84-cell device sweep, run 2 (hardened harness)

- **A1 evidence-path gap.** The first launch
  after the scene reset cold-started Gmail; the only accessibility WSC was the splash window
  (`event pkg=com.google.android.gm type=32 winId=2858 winType=-1` at 22:20:46.494), which the
  service defers and drops (`deferred WSC dropped pkg=com.google.android.gm winId=2858
  window=false`). No accessibility `decide` line can exist for that launch. The block itself
  was correct and came from the FGS path, one tick later: `poll
  foreground=com.google.android.gm carried=com.google.android.gm` /
  `detected com.google.android.gm — launching blocker` at 22:20:46.628, and
  `BlockerActivity` took the window at 22:20:47.591. `AppBlockScanner.evaluate`
  (AppBlockScanner.kt:31) calls the same `BlockDecisionEngine.decideApp`; acceptance of that
  line as BLOCK evidence therefore does not weaken the oracle. The harness now accepts a fresh
  `detected $PKG — launching blocker` from `MyExodusBlockerFgs` as BLOCK evidence (ALLOW still
  requires `decide -> Allow`; the poll logs nothing on Allow), and records which path supplied
  the verdict in the TSV `evidence` column.
- **Run 2's 12 STALE-CARD failures were harness, not engine.** The `overlay-dismiss` suite was a
  no-op: it looked for the service's overlay in `getWindows()`, where it never appears, and
  returned before clicking. The standing wall from run 1 therefore survived every scene reset
  and re-evicted 12 of the 32 cells without a fresh decision (TSV
  `/tmp/blocking-sweep-20261020-222301.tsv`, W5…WQ30). The suite was fixed to click the Close
  node in the overlay's own accessibility tree (the same listener a finger runs), verified by a
  clean reset, and the same 32 cells were re-run as run 3a.

### 2026-10-20/21 — 84-cell device sweep, run 3 (complete: 84/84 PASS)

- **Run 3a** (22:44:50 start, `/tmp/blocking-sweep-20261020-224449.tsv`, evidence copies in
  `/tmp/blocking-sweep-20261020-224449-cells/`): cells A1–AW32, **32/32 PASS, 0 FAIL**,
  including every cell that STALE-CARDed in run 2. Verdict paths: 27 `decide`, 1 `fgs`,
  4 usage-tick.
- The run then **hung, not failed**, at AW33: the owner locked the phone for the night and an
  unbounded `adb shell am start -W` waited on the lock screen (the same wedge cost 5 h 12 min in
  the prior attempt). Killed, device state audited, aborted run's leftover cleaned
  (`usage cleared pkg=com.google.android.gm rule=rule-137562510296736`, no standing wall).
- **Harness hardening (debug/script only, production untouched):** `timeout 20` on every
  `am start -W`; `require_interactive` aborts with a resume hint if the phone is asleep or
  locked; `svc power stayon true` with an EXIT trap restoring `false`; `SKIP=<cell>` resume.
- **Run 3b** (04:08:09→04:34:31, `SKIP=33`, `/tmp/blocking-sweep-20261021-040806.tsv`,
  evidence copies in `/tmp/blocking-sweep-20261021-040806-cells/`): cells AW33–Q84, **52/52
  PASS, 0 FAIL**. Verdict paths: 48 `decide`, 4 usage-tick (WQ28/WQ29 and their day-off
  siblings — a quota-spent app outside its window logs `decide -> Allow` first and the wall
  rises from the 10 s usage tick a beat later, exactly the designed path).
- **Total for the cross-product: 84/84 PASS, 0 FAIL** (75 `decide`, 1 `fgs`, 8 usage-tick).
  Expected verdicts were computed independently by the script from the axes, never read from
  the engine. Post-run cleanup verified: QA rule deleted, no standing wall, `stay_on_while_
  plugged_in=0` restored, no other device state changed.
- **Full JVM suite re-run on the final tree (forced `--rerun-tasks`, 2026-10-21 05:08):**
  **206 suites / 1,825 tests / 0 failures / 0 errors / 0 skipped** — all 41 tasks executed,
  `BUILD SUCCESSFUL`. The 84-cell TSVs recounted from disk on 2026-10-21: 32 + 52 cells, every
  last field `PASS`, evidence mix 75/1/8 exactly as above.

### 2026-10-21 — C-15 screen-off self-heal (API-37 AVD, `emulator-5554`)

- **First attempt invalidated by stale trap state, then explained.** The 04:39 run read
  `usage row … used=0` on both sides of 45 s foreground because an earlier force-stop leg had
  left `w3=APP_STOPPED`'s re-enable requirement set; the FGS logged
  `tick 20: usage=true authority=FULL degradation=APP_STOPPED` and
  `gap recovery deferred: explicit re-enable required`. `adb … -a protection-reenable` cleared
  it (`re-enable requirement cleared — running credit-edge audit`) and the re-acquire path was
  seen on that same run: `MyExodusBlocker: session re-acquire pkg=com.android.settings
  scheduleBlocked=false` at 04:41:31 after screen-on + unlock. Accrual numbers from that run
  are not verdict evidence (tracker pinned at 0).
- **Clean re-run (04:43:37, log `/tmp/c15-screenoff-20261021-044337.log`).** Rule: Settings,
  quota-only 3,599,000 ms. Reads: **used=35,422 ms** at 04:45:15 (48 s after launch), screen
  off 04:45:18 (`mWakefulness=Dozing`) for 30 s, screen on + swipe at 04:45:49, second read
  **used=87,054 ms** at 04:46:38.
- **Verdict: PASS — no dead window.** Delta = 51,632 ms over 83.3 s wall between reads; actual
  foreground in that span was 2.7 s before sleep + 49.5 s after wake = 52.2 s, so the 30 s
  screen-off interval contributed **0 ms** and post-wake accrual resumed without loss (within
  the 1 s tick). No `session close` or watchdog line occurred — the session survived the
  screen-off, which is the stronger shape; the re-acquire-after-close path is independently
  evidenced by the first attempt's 04:41:31 line.

### 2026-10-21 — C-16 process death → gap repair (X6532, ADR #157 F4 shape re-run)

- **Setup.** Seeded quota-only Gmail rule (limitMs 3,600,000, usedMs 0) at 04:38:07, parked on
  HOME, then `run-as com.example.myexodus kill -STOP <pid>` (pid 17276) at 04:38:12 and launched
  Gmail. The platform reached its ANR watchdog at ~04:45:46 (7 min 34 s after the freeze, matching
  F4's 7.5 min) and showed `Application Not Responding: com.example.myexodus`; XOS holds the
  dialog until a choice, so the leg's 12-min poll would otherwise expire. The host tapped the
  dialog's **Close app** (`android:id/aerr_close`) — an ANR kill (`REASON_ANR`, error-correlated,
  the ADR #160 exemption) — at 04:47:32.
- **Restart + repair evidence (pid 26498):**
  - `04:47:43.562 MyExodusBlocker: session re-acquire pkg=com.google.android.gm scheduleBlocked=false`
  - `04:47:43.794 MyExodusBlockerFgs: gap repair: credited 1801813ms to com.google.android.gm over a 2173526ms uncredited window`
  - `usage row pkg=com.google.android.gm used=1801813` at 04:47:51.202; the 04:47:53.814 read
    (after the audit snapshot) shows **used=1811845** — the repair plus the live 10,032 ms
    ACCOUNTING row
- **Repair accuracy, measured against Digital Wellbeing itself.** The audit's platform intervals
  sum to exactly the day total (1,968,623 ms) and partition as: 146.8 s before the repair
  window, **1,801.9 s of foreground inside [04:11:30, 04:47:33]**, 20.0 s after the window end.
  The repair credited **1,801,813 ms = 1,801.8 s — a 100 ms match** with the platform foreground
  inside its window, including the full 562 s post-freeze Gmail interval. The window began at
  the last credit edge (04:11:19 wall), which is why it spans 36 min rather than just the 9.3 min
  freeze: the sweep's live journal had stopped appending at 04:11:19.
- **Day-audit residual fully decomposed.** `audit DELTA … journal=1862653ms platform=1968623ms
  diff=105970ms` = **20,000 ms** tail after the window end (covered by the live ACCOUNTING row
  `credited=10032ms wall=04:47:53`, which landed with the snapshot) + **85,970 ms** of platform
  foreground in 04:08:17–04:11:30 that the live journal never credited (the sweep's first four
  rapid synthetic launches, before the ledger's first row at 04:08:58; partial-tick loss at
  session boundaries). That 86 s is **outside** the repair window by design (repair starts at
  the last credit edge) and is a property of the synthetic burst, not of the repair.
- **Verdict: PASS.** Death → sticky restart → session re-acquire → repair crediting exactly the
  platform-recorded foreground of the uncredited window (100 ms), with every remaining
  millisecond of the day diff attributed. Matrix expectation corrected: a literal
  `am force-stop` is a user stop under ADR #160 (paused era clipped, explicit re-enable
  required); the repairable death shape is an error-correlated kill, which is what this leg
  produced.

### 2026-10-21 — C-19 editor input domain (X6532, website editor, real UI drive)

All four domain values were driven through the actual editor (Rules → Add website → target
`example.org`), Save via the real button, banner read from the accessibility tree:

- **Blank limit** (limit switch on, both fields empty) → Save →
  **"Enter at least 1 minute for the daily limit."** banner at the top.
- **Zero limit** (`0` in Hours, Minutes empty) → Save → same
  **"Enter at least 1 minute…"** banner. No `= blocked after …` preview is rendered for a
  0-minute total, matching `parseLimitTotalMinutes` returning null.
- **Over-max** (`24` in Hours) → inline preview changes to
  **"A daily limit must be under 24 hours. A day only has 24 hours, so this would never block —
  pick a shorter limit."**, and Save raises that same text as the top banner instead of saving.
- **Equal window** (schedule on; limit corrected to `1` — preview "= blocked after 1h a day";
  From dial set to 7:00 AM to match the default 7:00 AM To) → Save →
  **"Start and end time can’t be the same. Pick a different end time."**
- **Nothing saved in any case:** each rejection keeps the editor open with the target intact;
  backing out to the Rules list shows no `example.org` row (dump count 0). Evidence:
  `/tmp/c19-zero-banner.xml` + `/tmp/c19-zero-banner.png`; the other banners' dumps were read
  live from `/sdcard/ui.xml` (bounds + exact text quoted above).
- Phone left on HOME, no rule created, no standing wall.

### 2026-10-21 — limit-magnitude leg (X6532, quota shape)

The 84-cell sweep held the limit at 60,000 ms and varied only the state (under/at/over). This
leg varies the magnitude at each state: **2 h, 5 h, 10 h, and the editor-valid maximum
23 h 59 m** (a literal 24 h is editor-rejected — C-19). Expected verdicts computed by the
harness: under ⇒ ALLOW, at/over ⇒ BLOCK. **12/12 PASS**; TSV `/tmp/quota-magnitude-20261021-093619.tsv`,
per-cell logs `/tmp/quota-magnitude-20261021-093619-M*.log`. First run (09:33) failed 4 under
cells for a harness reason, not an engine one: used = limit − 1 ms leaves no headroom for the
cell's own ~22 s foreground accrual, so the first 10 s tick legitimately crossed the limit
(`observe … 7210001ms/7200000ms Block`); fixed with 120 s headroom and re-run 12/12.

### 2026-10-21 — day-mask leg (X6532; the 84 crossed only today-on vs today-off)

The 84-cell sweep's day axis was today's bit on/off (`1111111` vs today's char `'0'`); it never
varied the other day positions. This leg varies the mask itself across all three day-gated
shapes. Today = Wednesday 2026-10-21 ⇒ Monday-first index 2; expected verdicts computed by the
harness from the mask bit, never read from the engine. **12/12 PASS**, TSV
`/tmp/day-mask-20261021-095045.tsv`:

- always-on × masks: `1111111` BLOCK (decide), `1111100` Mon–Fri BLOCK (fgs), `0000011` Sat+Sun
  ALLOW (decide), `0010000` Wed-only BLOCK (decide), `0000100` Thu-only ALLOW (decide),
  `0000000` ALLOW (decide), `1111110` Mon–Sat BLOCK (fgs), `1101111` all-but-Wed ALLOW (decide).
- window-inside × `1111100` BLOCK (decide) / `0000011` ALLOW (decide).
- quota-at (60,000/60,000) × `1111100` BLOCK (usage tick) / `0000011` ALLOW (decide).

The `0010000` BLOCK + `0000100` ALLOW pair discriminates the Mon-first convention (a Sunday-first
engine would have swapped them); `1101111` ALLOW is the complement check. Malformed masks
(empty, 6-char, 8-char, non-binary) are rejected by both the editor and the seed suite
(`require(days.length == 7 …)`) — that domain stays JVM-only, covered by
`BlockDecisionEngineCombinationSweepTest` (`dayActiveOn` = `getOrNull(dow-1) == '1'`).

### 2026-10-21 — C-18 real override (X6532, driven through the shipped UI)

Owner-authorized; the override wrote a real `OVERRIDE` attempt into the owner's history and
started the 600 s cooldown, as intended. Temp always-on rule (`limit=0`, `days=1111111`),
Gmail launch → cover, "Open MyExodus Recovery" → Recovery tab → "Use it now (override)" →
"End the block". Evidence: `MyExodusAttempts: attempt logged: outcome=OVERRIDE
rule=rule-179725244703480 target=com.google.android.gm` at 09:47:02.088; read-back `usage rule
pkg=com.google.android.gm enabled=true limit=0 … alwaysOn=true … expiresAt=1792565222093`
(`now−1`); relaunch → `decide com.google.android.gm gen=3 -> Allow`, focus on Gmail, no wall;
cleanup read-back `usage rule … ABSENT` / `usage row … ABSENT`. Log
`/tmp/c18c-override-20261021-094639.log`.

Two harness facts learned here (both needed for future device runs): (1) the daily-limit wall
(`OverlayContent.DailyLimitBlocked`) intentionally carries **no** recovery button — only
`AppBlocked`/`UrlBlocked` do, so the override must be driven from an always-on/schedule app
wall; (2) the accessibility cover is **not visible to `uiautomator dump`** (an a11y service
cannot see its own overlay window), so it must be tapped by coordinate measured from the
captured 720×1600 frame — recovery button at (360, 902). A pipe (`tap_text … | tee`) also hides
the function's exit code; check the tap result before concluding.

### 2026-10-21 — C-17 reboot persistence (sanctioned API-37 AVD, `emulator-5554`)

The X6532 is wireless-ADB only; a reboot severs the transport mid-run, so this leg ran on the
one sanctioned AVD. The physical reboot persistence was already proven 2026-10-18 (E-21).
Pre-reboot seed 09:55:24: `usage rule pkg=com.android.chrome enabled=true limit=60000
days=1111111 tz=Africa/Nairobi alwaysOn=true window=null..null expiresAt=0`; row `used=45000
anchor=2026-10-21/Africa/Nairobi`. Reboot + `sys.boot_completed=1` after 66 s.

Post-reboot read 09:59:26 — **rule and row intact**: identical rule line (same limit, days,
alwaysOn, `expiresAt=0`); row `used=74322 blocked=true` = the seeded 45,000 ms **plus exactly**
Chrome's post-reboot foreground (29,322 ms), credited once by
`MyExodusUsage: reconcile com.android.chrome foreground=29322ms uncovered=190709/190709
credit=29322ms -> 74322ms/60000ms` and `MyExodusBlockerFgs: gap repair: credited 29322ms …
over a 190708ms uncredited window` (09:59:14) — no double-credit across the reboot.

Fresh Block on launch: `am start … chrome.Main` at 09:59:47 →
`MyExodusBlockerFgs: detected com.android.chrome — launching blocker` (09:59:47.552);
`BlockerActivity` present in the window list; frame `/tmp/c17-avd-block.png` (25,011 bytes).
Both enforcement services rebound after boot (a11y `BlockerAccessibilityService` bound;
`EnforcementForegroundService` `isForeground=true`). Cleanup 10:00:16: `usage rule … ABSENT` /
`usage row … ABSENT`; launcher HOME. Log `/tmp/c17-avd-20261021-095542.log`.

Harness trap learned: after an AVD reboot the app can be in the **stopped package state**, and
`am broadcast` without `--include-stopped-packages` silently delivers nothing (the first
post-reboot read printed no line and looked like missing persistence). Use
`--include-stopped-packages` (or start an app activity first) for the read-back.
