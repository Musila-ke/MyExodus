# MyExodus — Daily App Usage Limit Enforcement — Implementation Plan v3 (scoped: outage-detection + repair delta)

**Status, as built 2026-09-22 (ADR #73) — read this before re-scoping.** **D3: BUILT, device-verified three times — the 2026-09-28 round (#13, ADR #103) found and fixed three implementation defects in the shipped build: the repair cap contradicted this file's §5 D3 "No cap" (removed), the window anchored at the death timestamp instead of the specified `(last_credited_wall_ms, now]` (now anchored at the newest journal observation), and the user-stop detector read instrumented/background force-stops as user acts (narrowed).** **D1: only its *downstream input* is live** — the repair reads the same platform exit record D1's detection leg would persist, so a dead window is computed and acted on, but **nothing counts, stores, or displays outages**: verified absent by grep (no local kill counter, no `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` anywhere in `app/src/main/`, no outage surface in the UI). **D1's two other legs are NOT built: (i) the local outage count/largest-window record, (ii) the conditional exemption prompt + three-surface outage display.** **D2: NOT built** (accounting journal, schema v10→v11). Owner ruling 2026-09-22 ("do what you gotta do") approved the shipped scope; the remaining legs are open product work, tracked as item 23 in `Obsidian/MyExodus/Open Product Decisions.md`. Supersedes `daily-usage-limit-enforcement-implementation-plan-v2.md` **for build purposes** on the sections it touches. v2 remains the archive of what shipped (steps, ADR trail, device-QA records) and is not edited by this file.

**Status addendum, 2026-10-15 (ADR #149).** D1's outage display is re-scoped after the owner-reported 18×/18h35m over-count: the count/largest report now describes only windows the platform has **not** accounted for (resolved-through watermark advanced by every numeric recovery answer; day-split siblings re-join into one interruption), and the exemption offer keeps its surface through resolution via the eligible gap's own span. Journal rows are never deleted on resolution — resolved ≠ deleted.

**Build state, 2026-09-22.** Built from D1/D3 — `blocking/usage/UsageGapRepair.kt` (pure span/event algebra), the exit-record read and repair sweep in `EnforcementForegroundService` (startup, off the main thread, once per service start), 12 JVM tests in `UsageGapRepairTest`. The battery-exemption request is **not** newly built (D1's prompting leg): `SettingsScreen` already renders the battery row with a Fix action into `batteryOptimizationSettingsIntent()`, which satisfies Decision 17 as shipped. The exemption was granted on the X6532 by hand the same day, so the leading hypothesis is mitigated on the owner's device independently of the code.

**Why v3 exists.** v2 shipped, and the owner reported that on 2026-09-21 a 2-hour Instagram limit (`Always on` = all days) did not hold: the owner read ~2h36m in Digital Wellbeing against the 2-hour limit while the app stayed usable; Android's own retained record for that date now totals 151.3 min (2h31m) over 29 sessions. A pre-block warning did fire and a block did land later that day. On 2026-09-22 the enforcement chain was measured end-to-end on the X6532 and **every tested mechanism was correct** — so the defect is not in the counting math, the thresholds, or the block path. This file records what was measured, what that rules out, the three structural gaps that are proven by inspection, and the minimum delta that makes the next occurrence self-diagnosing and the system self-correcting.

---

## 1. What was measured (device X6532, Android 34 / XOS, 2026-09-22)

| Test | Method | Result |
|---|---|---|
| Continuous dwell fidelity | Tracked app foreground 22m52s, MyExodus backgrounded; counter via the `usage-read` debug seam vs wall clock | **0.994×** (22m44s counted) |
| Live chain, 15-min limit | Counter sampled every 30 s until block | limit 900 s consumed in ~905 s = **1.006×**; `blocked=true` @904.8 s; wall rendered; eject to launcher fired |
| Warning thresholds (limit ≥ 10 min branch) | Same run | warning1 NOT_DUE @284.6 s → DELIVERED @324.7 s (target 300) · warning2 NOT_DUE @584.6 s → DELIVERED @624.7 s (target 600) |
| App-hopping (10 hops, dwells 4/12/25/45/90/4/18/30/60/120 s) | Counter delta vs Android's own `ACTIVITY_RESUMED/PAUSED` pairs over the same window | **1.042×** — 424 s counted vs 407.0 s ground truth over 9 sessions |
| Kill/restart cost | 12 LMKD kills observed over 4 h, phone on charger | restart in **~1 s** (sticky FGS); a deliberate crash leg restarted in 22 s |
| Ground truth for the complaint day | `dumpsys usagestats` for 2026-09-21 | Instagram 151.3 min over 29 sessions |

## 2. What this rules out

- **Counting drift / miscounting is not the defect.** Every ratio measured is ≥ 0.994 and the two that deviate deviate *upward* (over-counting), which is the opposite of "usable past the limit."
- **Threshold math and warning delivery are not the defect.** Both thresholds fired within one tick of target; the owner's "10 minutes left" popup is `limit_warning_title` from this same ladder.
- **The block path is not the defect.** Block, wall render, and eject all fired live, and were re-verified in the app-hopping run.
- **App-hopping does not lose time.** It over-counts slightly (launcher sojourns under ~1.5–2 s are invisible to `queryEvents` in the *ground-truth* leg, per v2 §16 Q3 — the ground truth under-counts, we don't).

**What it cannot rule out:** the complaint day itself. It is not reconstructable. Decision 20 rolls the tracker row over in place, so 2026-09-21's anchor — our own counter at day's end — was overwritten at the first write after midnight. There is no history, no per-day record, and no journal on device. This is itself a finding (§3.3).

## 3. Proven structural findings (inspection and device state — not inference)

### 3.1 v2 §15 row 4's mitigation is architecturally void
§15 row 4 ("OEM kills the Path B foreground service…") mitigates with *"Path A remains authoritative when available."* That sentence requires Path A and Path B to be separable failure domains. They are not:

```
$ grep -rn "android:process" app/src/*/AndroidManifest.xml
(none — single process)
```

Path A (the accessibility service), Path B (the foreground service), and the VPN all share one ProcessRecord. One kill takes all of them; there is no "Path A remains" state. The mitigation as written cannot hold on any device, not just OEMs.

### 3.2 The battery exemption is neither granted nor asked for
Verified on the X6532, 2026-09-22:

```
$ adb shell dumpsys deviceidle whitelist | grep -i myexodus
(no match)
$ adb shell dumpsys appops --package com.example.myexodus | grep RUN_IN_BACKGROUND
RUN_IN_BACKGROUND (default):
```

And in the tree: `grep -rn "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" app/src/main/` → no matches. `ProtectionChecks.batteryExempt` (`app/src/main/java/com/example/myexodus/settings/ProtectionChecks.kt:42`) only *reads and displays* the state. There is nowhere in the app that requests it.

v2 **Decision 17** specifies this prompt: *"It is offered contextually — only after telemetry (or a first-run device check) indicates Path B's foreground service has actually been killed unexpectedly on that device."* The parenthetical already permits a local, network-free device check, so completing this needs no amendment — it is an **unbuilt v2 requirement**, not a v3 change.

v2 §12 already records what the missing exemption costs on this exact hardware: *"XOS kills the FGS-hosting process as `cached idle & background restricted` and the START_STICKY restart was not prompt (~17 min, ended by a manual launch) with battery optimization not exempt."* **The ~1 s restart measured on 2026-09-22 was on the charger**; the 17-minute figure is the off-charger, non-exempt condition — which is the state the phone is actually in.

### 3.3 Nothing detects or records an outage
There is no local kill counter, no uptime record, and no "I was dead for N minutes" signal anywhere in the tree (`grep -rn "restartCount|killDetected|outage|deadWindow" app/src/main/` → no matches). `ProtectionDegradation.APP_STOPPED` is a *user-force-stop* flag (`requiresExplicitReEnable`), not an OS-kill detector. §14 asks for "Path B's continuous-service uptime/kill rate by OEM" — that line is currently satisfiable only by Crashlytics, which is opt-out and network-dependent, and therefore cannot inform the device itself.

## 4. Leading hypothesis (stated as such — not proven)

The complaint day's shape — counter correct at some point (warnings fired), app usable for a long stretch, Digital Wellbeing recording 2h31m — is consistent with an **accounting-authority outage**: a window in which the single process was dead, so nothing accrued *and* nothing blocked. §3.2 supplies the mechanism and a measured precedent (up to ~17 min per kill on this device, un-exempt, off-charger), and §3.3 explains why we cannot confirm it after the fact. It is not proven and cannot be retro-proven, because the evidence self-destructed (§2).

Two secondary candidates remain open, both cheap to instrument rather than to argue about:
- the block verdict being dropped when the threshold crosses while the tracked app is not foreground (`BlockerAccessibilityService.renderUsageAction`, the `isActiveForeground` guard) and re-arming only on next entry;
- an overlay attach that fails on XOS (the OEM path is `TYPE_APPLICATION_OVERLAY`) while the counter keeps climbing — invisible failure, correct accounting.

## 5. Deltas

### D1 — Complete Decision 17: local outage detection + the exemption prompt
No amendment needed; this is v2 work that was never built.

- Count process restarts locally: a boot-count + a monotonic heartbeat written on the enforcement path lets the next process start compute the dead window (last heartbeat wall-clock → now). Persist the count and the largest window seen.
- When a dead window over a threshold (proposed: 60 s) is recorded, surface the battery-exemption request (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) in the existing Settings "Fix" row (`ProtectionChecks.batteryExempt` already feeds that row) — and only then. Decision 17's "not a mandatory onboarding gate" is preserved.
- Surface the outage honestly in the existing protection-status UI, using the same three-surface pattern as the APP_STOPPED leg (ADR #65 addendum): notification + Home + Settings.

### D2 — Durable local accounting journal (stop the evidence self-destructing)
New append-only table in `AppRulesStore` (schema v10 → v11, versioned migration, no destructive fallback — v2 §13's rule still applies). One row per noteworthy accounting event, **local only, never transmitted** (Decision 12 and NFR1 both still hold):

`(ts, package, kind, credited_ms, span_ms, source, boot_count, authority)`

with `kind ∈ {TICK, SESSION_CLOSE, REPAIR, OUTAGE_DETECTED, AUTHORITY_CHANGE, BLOCK, WARNING}` and `source ∈ {PATH_A, PATH_B, REPAIR}`. Retention: keep N days (proposed 14), pruned by the existing maintenance WorkManager job. This is what makes "I don't trust the number" answerable: the counter stops being an opaque scalar and becomes a reconstructable sum.

### D3 — Bounded repair on resumption, using `queryEvents`
This is the only delta that changes accounting behaviour, and the only one that needs v2 text amended.

**Amends v2:** NFR3 (duration math uses only `elapsedRealtime` deltas within a boot session) — add a carve-out: *"except the bounded repair path, which derives duration from `UsageStatsManager.queryEvents()` timestamps within a single boot session; the repair is a corrective top-up, never a primary accounting input."*

**Does not amend Decision 4.** Decision 4 forbids inferring duration from *"elapsed time since the last event."* The repair reads the platform's own explicit `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` pairs — it infers nothing from elapsed time. §16 Q3 expressly contemplates `queryEvents()` for live accounting on this device, with the measured caveat that sojourns under ~1.5–2 s may be missed (an under-count in the repair, accepted and logged).

Design, bounded deliberately:
- **Trigger:** a tracked package opening a session, and on authority acquisition — never on a timer.
- **Watermark:** the tracker row gains `last_credited_wall_ms` and `last_credited_boot_count` (same v11 migration). No watermark, or a boot-count mismatch → skip (a fresh day or a cross-boot span has nothing safe to repair).
- **Span:** `(last_credited_wall_ms, now]`, and only if span ≥ 20 s (2× `USAGE_TICK_MS`) — below that it is ordinary transition jitter and repairing it risks double-crediting the normal close-tick tail.
- **Credit:** the summed foreground intervals for that package, clipped to the span, submitted as **one** observation through the existing single transactional writer (`processUsageObservation`, Decision 19) so threshold/warning/block evaluation runs once, in order, with no new race.
- **No cap.** A cap would re-introduce under-counting, which is the defect class this delta exists to remove. The floor and the clip are the only bounds.
- **Log:** every repair writes a `REPAIR` journal row (span, credited, wall delta) and a §14 Crashlytics non-fatal (counts only — no package names, per Decision 12).

## 6. Explicitly NOT changed

- Warning thresholds, the warning state machine (Decision 14), and the block/eject path — all measured correct on device (§1). No touch.
- The 10 s ticker, the session state machine, Decision 2's single-authority rule, Decision 19's transactional writer, Decision 20's day identity.
- The overlay mechanism, including the XOS `TYPE_APPLICATION_OVERLAY` OEM path.
- No network call is added to the enforcement path; the journal is on-device and unreadable outside SQLCipher.

## 7. Testing and acceptance deltas

**Device verification (X6532, 2026-09-22, D1/D3 as built).** With the exemption now granted the sticky restart is ~1 s, far under the 20 s floor, so the leg used the only lever that holds the process down long enough: `am force-stop` (which, notably, also drops the app's accessibility binding until it is re-bound — the app's existing degraded surfaces cover that state). Script `/tmp/du-gap-test.sh`: Bolt foregrounded, MyExodus force-stopped, 183 s of tracked-app use with the app dead, then relaunched. Result: `gap repair: credited 182853ms to ee.mtakso.client over a 183059ms unobserved window`, and the stored counter moved `1339746 → 1522599` — a credit of exactly 182 853 ms, i.e. the dead window absorbed to within 0.1% of the platform's own record. Threshold/warning state read back NOT_DUE/`blocked=false`, correct for 25.4 min against Bolt's 60-min limit. Note for future legs: a `usage-read` broadcast issued within ~200 ms of the repair races its commit and reads the pre-repair row — sleep past it or re-read. Re-run after the binder calls were moved onto the isolated tick pool (see below): 90 s dead window, counter `1522599 → 1625197` = 102.6 s absorbed; Path B's own 30 s poll cadence bounds what a live path could have credited to ~29 s, so the remainder is the repair. Full JVM suite green at 1033 tests, 0 failures.

**Device verification round 3 (X6532, 2026-09-28, ADR #103 — the #13 undercount report).** The owner's acceptance bar: the tracker must track Digital Wellbeing within a small delta on a real session; no re-claim without that measurement. Three defects were found in the shipped D3 build and fixed first (the 6 h cap — this file's §5 D3 says "No cap"; the death-anchored window — §5 D3's span is `(last_credited_wall_ms, now]`; the user-stop detector misreading instrumentation/background force-stops, which the device's own exit-info still shows as reason=10 subreason=21 "start instr"/"finished inst"). Legs, in order: **defect signatures (pre-fix)** — a frozen-then-ANR-killed window measured `gap repair: credited 20226ms over a 20225ms window` when the death was reachable, and the aged-baseline leg produced GAP rows totalling 28.84M ms with **no** reconciliation credit (the cap refusal); **freeze-prefix repair (post-fix)** — SIGSTOP 06:41:51 → watchdog ANR kill 06:49:19 → restart 06:49:22, `gap repair: credited 452909ms to com.instagram.android over a 452908ms unobserved window` (`id=4817 RECONCILIATION credited=452909ms span=452909ms`; the earned interval chains end-to-start from the previous row's elapsed end — zero double-credit, by construction), where pre-fix the same window was a sub-floor silent no-op and 452.9 s was permanently lost; **cap removal (post-fix)** — an 8 h aged baseline credited `id=4830 RECONCILIATION credited=3006191ms span=24742164ms` (a 6.87 h span the old cap refused), then §8.12's `ledger-correct` reversed it exactly (`CORRECTION credited=-3006191ms`, projections rewritten, totals netted); **DW delta, app path** — Instagram live session journal 598,935 ms vs platform 602,418 ms = **99.4%**; **DW delta, domain path** — radioparadise.com metered 501,506 ms vs Chrome's platform foreground 503,052 ms over the same instant = **99.69%** (wall-probe cross-check +0.12%; a never-visited control rule stayed 0 ms). Standing day residual 178,444 ms is the pre-fix morning freeze hole — permanent for that day only (nothing may re-measure a span its baseline already passed); the fix governs forward.

**Binder isolation (added during implementation, not in the original delta).** The FGS documents that a usage-stats or appops binder call can wedge indefinitely on this hardware (on-device finding 2026-08-30) and therefore runs *every* binder call on a throwaway executor thread under a hard timeout. The repair is the first thing outside the poll loop to make such calls, so its exit-record read and its `queryEvents` now run through the same pool (`onBinderThread`); a wedge abandons a thread and skips the repair rather than stalling anything. `queryEvents` is the exact call class that finding named, so this is not defensive padding.

- **Unit:** repair span algebra (floor, clip, no-watermark, boot-mismatch, cross-midnight), repair never double-credits a normal tick sequence, journal retention pruning.
- **Instrumented:** kill the process with the tracked app foreground for > 20 s, resume, and assert the counter lands within one tick of Android's ground truth for the window; assert an OUTAGE_DETECTED row and the exemption prompt surface.
- **AC addition:** *a tracked app used across a process kill is credited within one tick interval of the platform's own record for the window, and the outage is recorded locally.*
- **AC addition:** *on a device where a > 60 s dead window has been recorded, the battery-exemption request is offered without any network round-trip.*

## 8. Open questions for the owner

1. **Approve D3?** It is the only behavioural change and it amends NFR3. Alternative: ship D1+D2 only (diagnose now, repair later once the next occurrence confirms the hypothesis).
2. **Grant the battery exemption on the X6532?** It is currently not granted (§3.2), and it is the cheapest fix for the leading hypothesis. No code change required — one tap in Settings.
3. **Journal retention** — 14 days proposed.
4. **ADR?** §3.1 (single-process void) and §3.2 (Decision 17 unbuilt) are architecture-level facts. Proposed as one ADR entry in `app/docs/MyExodus Architecture Decisions.md` alongside the amendment.

## 9. Audit — v2 disposition

| v2 section | Disposition |
|---|---|
| §1–§4 (overview, requirements, context, architecture) | Unchanged |
| §5 Decisions 1–21 | Unchanged except: Decision 4 clarified (not amended — see D3), Decision 17 completed (unbuilt v2 requirement) |
| §6–§14 (flow, steps, error handling, security, perf, testing, deploy, monitoring) | Unchanged; §14 gains the local kill/uptime signal D1 specifies |
| §15 Risk row 4 | Mitigation text is void (§3.1); replacement mitigation is D1 + D3 |
| §16 Q3 | Resolved datapoint unchanged; now also the mechanism D3 relies on |
| §17 acceptance criteria | Unchanged; two additions in §7 above |
| §18 DoD | Unchanged; D1–D3 add their own verification before any claim of "done" |
| NFR3 | Amended by D3 (narrow carve-out) |
