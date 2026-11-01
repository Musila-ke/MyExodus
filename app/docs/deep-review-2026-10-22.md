# Deep review — 2026-10-22

Owner mandate (2026-10-22): a deep, deep, deep and thorough code review and trainer, with
memory and docs brought fully up to date, followed by a double check — and stress testing is
part of it. Work is tracked as tasks #37–#47. This file is the persistent ledger the audit
protocol requires: the review map (#37), the file inventory and delta, every finding, and the
verification record. A verdict is only written here after the file was actually read in this
pass; carried verdicts are labelled as carried.

## Resume pointer (live — update at every step boundary)

**If the session was cut (power loss), read this first, then the task list, then continue.**

- **State at last update (2026-11-01): the two-AVD LOCAL-RIG is permanently fixed and a
  full pairing ceremony completed on it.** Cold-start root cause: the one-time
  `LegacyCachePurge` terminates the Firestore client and the rebuilt client was production-
  wired, discarding the emulator redirect; fixed with the `LegacyCachePurge.backendReapply`
  seam (set by `DebugAppCheckProvider`, invoked in the purge worker's `finally`; see the rig
  section below for evidence). Also corrected: `SET_BACKEND` needs `-n <component>` (no
  intent-filter), and host-memory pressure kills the Functions emulator — check
  `/tmp/local-rig-emulators.log` first. Current devices (2026-11-01): A `emulator-5554`, uid
  `FTJvVlaxSYiDLmBvCB2LYbRBBjMU` (EmuA); B `emulator-5556`, uid
  `HPTfYybBCZKuiyGf301bsMChUeJG` (EmuB); PIN 1234 both. Pair `FTJv…_HPTf…` active, epoch 6;
  both Plans pages empty, B availability sharing OFF. The earlier devices (uids `25tso4Wz…` /
  `5JLIedQHw…`) were re-provisioned; their pair doc is historical. All behavioural legs are
  closed; #59 and #61 were re-proven twice on this pair, and the #193 §9 plan prune is fixed
  and device-verified (section below). LOCAL-RIG evidence never upgrades to LIVE/PHYSICAL;
  FCM wake is a latency optimization because mailbox ingest posts the same notification (see
  `MailboxMessageHandler` note below).

- **State at last update (2026-10-23 late): #55 CLOSED — all eight consented partner-activity
  categories proven on the two-device rig over the real rails.** Streak update, relapse,
  milestone day 7 (the owner-sanctioned `PartnerJourneySeedInstrumentedTest` supplies the row —
  a real 7-day wait cannot fit a session — and the rest of the pipeline is production), message,
  availability check-in, app-block attempt (a real Chrome launch hit `BlockerActivity`; the
  sender chain carried `blocked:com.android.chrome` on two real attempts), recovery session (a
  real five-phase walkthrough → `recovery:sober:completed`), and coaching-plan edit (a real
  if-then item → the partner summary read "Coaching items 1"; count only, INV-13). Each was
  observed in the receiver's chain and dashboard projection. The one "Paired with <old label>"
  frame after the AVD snapshot quick-boot was investigated: no table contains that label and
  the enrollment row carries the current pair (created 1792758571090); it is restored UI state,
  corrected on the next render — no code change without evidence. Cleanup done: TEMP
  `PartnerRigDiagnosticsTest.kt` and `PartnerActivityDao.journalRecent` deleted; V1.md updated
  with the proof paragraph; vault + memory synced. **Update 2026-10-23 late:** #54 CLOSED
  (Insights period/date semantics, ADR #218), #59 CLOSED (pairing clock-skew stall, ADR #215),
  #60 CLOSED (plan-fidelity matrix incl. the new plan-publish notification test — see the #60
  section below), **#47 CLOSED** (stress/adversarial evidence map — see the #47 section below),
  **#52/#53 CLOSED** (monitoring re-check; two-device runbook), and S49-D3-3 FIXED (ack replay
  is read as "already acked"). The ceremony header after success now names the outcome instead
  of "Invite a partner" (owner report, fixed). **#43 CLOSED (2026-10-23):** commit `912af3c`,
  JVM 227/2,046/0/0/0, lint "No issues found", debug APK installed with `install -r` on the
  X6532 (md5 `e1ee7350c7e40fd7699cfb4605838662`, data intact, a11y binding restored, chip
  "Protecting") — see the #43 section below. **Owner routing decision (2026-10-23):**
  **per-subject only** — nothing is shared unless the block/streak explicitly names a partner;
  new streaks ask for the partner at creation. **Next:** build #56/#58 to that model, then
  #57 (rules cognitive-load redesign, Desert Dawn); #36 auth-blocked.

- **Owner mandate #2 (2026-10-22, tasks #48–#50):** two-device, real-user scenario sweep —
  partner notifications for uninstall / permission revocation / milestones / per-rule
  partner assignment / blocked-app-or-site access, multi-partner routing (is the RIGHT
  partner notified?), every feature run with real data, every combination, stress + edge
  cases. Auth friction (fingerprint every launch) acknowledged as its own future task.
  Verbatim mandate is stored in the vault note `Obsidian/MyExodus/MyExodus Progress.md`
  (entry dated 2026-10-22, "mandate #2").
- **Owner report 2026-10-23 (new tasks #54–#58, added while #49 runs; OPD #38/#39):**
  (a) **#56 per-block partner assignment** — verified today: sharing is per *pair* via
  `PairProfileSharing.shareCheckins` (one toggle covers every block), blocked-attempt events
  carry **no rule identity**, rule add/edit/disable/delete events do not exist, and no
  primary-partner concept exists. Owner requires a per-block picker, lifecycle/violation
  events routed only to that block's partner, and no cross-visibility or collisions with
  multiple partners. Same unmet intent as OPD #33. (b) **#55 Partner Activity has been zero
  from day one** — trace journal → per-pair consent/watermarks → mailbox → partner pull → UI
  and prove every consented category on the rig. (c) **#57 rules/block screens cognitive-load
  redesign** (owner: squeezed, too much text). (d) **#54 Insights period/date semantics** —
  verified: window is today + 6 local days (not Mon–Sun) and the 7-day metered total has no
  period label; "Screen time" is metered monitored-app/domain usage, not whole-device. Owner
  saw Friday vs Thu/Fri bar values that did not reconcile (5m/1m vs 12h48m total). (e) **#58
  primary-partner routing + streak partner-at-creation** — owner explicitly undecided; present
  options before building. The #49 sweep continues first; these tasks absorb what it exposes.
- **State at last update (2026-10-23 06:40):** #37–#42, #44–#46, #48, #50, **#51** complete;
  **#49 in progress** — Journeys A/B/C/E PASS on AVD A. Journey D's second paired run PASS through
  identity-death → re-pair → coaching delivery → **plan leg (D7, ADR #193)**: B checked task 1
  of A's "Morning reset"; the encrypted `plan_progress` envelope reached A's mailbox
  (`mailboxes/rYmVai…/messages/4830aa89-…`, createTime 01:15:56Z) and A's authored plan page
  shows "1 of 2 done" with "Your partner checks these off". A later `drain: 0` at 04:21:58 was
  a no-op pass — delivery had already happened, don't re-investigate. Findings S49-D3-1…3 stay
  open. Device state at 04:30 (re-check screen first — state-first rule): A (`emulator-5554`,
  uid rYmVai…) on the plan page; B (`emulator-5556`, uid VIQDm…) unlocked (PIN 1234, enter at
  814,1425) on plan detail — the signing window was warm then, but emit retries are safe.
  **Next, in order:** (1) B→A streak/milestone leg with consent — B Partner hub →
  "Choose what to share" → enable streak sharing, create/pick a habit, run the day-7 milestone
  recipe (ADR "Habit streaks" Verification item c: `settings put global auto_time 0`, advance
  the clock +7 d, reboot to bump `BOOT_COUNT`, root-deliver `BOOT_COMPLETED` to
  `MilestoneBootReceiver`), verify A mailbox doc + notification; (2) adversarial pairing legs — wrong code,
  expired code, SAS mismatch refusal, re-join on an already-active pair; (3) destructive unpair
  LAST (also exercises stale enrollment S49-D3-1). Then #47 stress/adversarial, #43 final
  verify/report (delete TEMP `PartnerRigDiagnosticsTest.kt` + `PartnerDao.journalRecent`, write
  ADR #214, full JVM suite + `:app:lintDebug` ALONE, commit, docs/vault/memory sync), #52
  Cloud alerts, #53 runbook (#51 DONE — see the block below). #36 blocked on auth + a second
  physical phone.
  Uncommitted tree: composer gate (`HabitRepository.sharesStreakWith`, PartnerScreen
  `canEncourage` + reload-on-view, strings), reachability rotation marker + 12 tests,
  `PartnerSyncCoordinatorTest` 44 tests, DI wiring.
- **#51 tools/skills stack — DONE 2026-10-23 06:15.** Claude Code 2.1.221. Marketplaces
  refreshed (official + understand-anything; `thedotmack` update hung >10 min and was stopped —
  dormant, no plugin installed from it). Plugin `understand-anything` **2.9.4 → 2.9.7** (restart
  applies). Git skills pulled: `ecc`, `humanizer`, `make-interfaces-feel-better`, `ponytail`
  updated; `motion-dev`, `op-fable` current; **`gstack` skipped** (dirty `package.json` +
  untracked `package-lock.json` — resolve before pulling). 0 dangling symlinks of 31.
  `~/.claude/CLAUDE.md` mapping fixed: removed 6 nonexistent names (`api-design-reviewer`,
  `tdd-guide`, `senior-mobile`, `mobile-app`, `database-designer`, `sql-database-assistant`),
  kept `review`/`simplify`/`security-review` (built-ins), added a Non-coding capabilities block.
  Inventory + update checklist: `~/.claude/STACK.md`. **OpenMontage** cloned to
  `~/tools/OpenMontage` (170 MB, shallow) and `make setup` run; Makefile used the active conda
  env (`/opt/anaconda3`) rather than a repo venv — piper-tts install was still running at
  handoff; when it exits run `make -C ~/tools/OpenMontage preflight` to confirm. MCP:
  chrome-devtools + shadcn-ui connected; **`magic` needs the owner's 21st.dev API key**
  (https://21st.dev/mcp).
- **Rig lifetime caveat (2026-10-23):** the Firebase emulators run in-memory with no
  `--import/--export-on-exit`; Auth-emulator accounts are not exported at all. If the host
  restarts or the emulators are killed, both devices' anonymous uids hit
  `INVALID_REFRESH_TOKEN` → identity rotation → re-pair (the D4 path, ADR #125) before any
  partner leg. Leave the emulators running to resume the live pair.
- **State at last update (18:05):** #37–#39, #41, #42, #45 complete. #45 close-out gates:
  lint alone 0/0/0; JVM 225 classes / 2,010 tests / 0 failures. **Matrix history:**
  - Run 1 aborted at 49 cells: `reset_scene` dismissed the wall while Gmail was still
    foreground; the next poll re-raised it and the standing wall re-evicted later launches
    with no fresh decision. Harness fix: HOME first, then `overlay-dismiss`; also documented
    the Gmail "New in Gmail" tour precondition (dismissed on the AVD today).
  - Run 2 wedged at A3: after HOME on API 37 an `am start -W` launch was deferred and never
    returned (zombie `timeout`, adbd held the host shell). Fix: no `-W` on reset/seed/final
    clear; settle 2 s after HOME, 3 s after start. Then A1/A2 failed to **cold-start ANRs**
    (exit-info + traces: dex loading/verification/GC, no product deadlock). Fixes: Gradle/
    Kotlin daemons stopped, explicit warm-up poll, `wait_snapshot` 12 s → **60 s**, animator
    scales 0.
  - Run 3 (`bhmby8zrx`, 17:37–17:53): 24 PASS, W6 boundary race, then a **30-cell chain
    WQ26→AW58**, every cell STALE-CARD with exactly one `block close ... evicted=true` and no
    fresh Gmail `decide`. Stopped at AW58; not valid beyond W25. **Root cause (definitive):**
    `OverlayController.debugClickDismiss` read only `views`; the stock (non-Transsion) host
    renders the wall as a **Dialog** kept in `dialogViews`, so every harness dismissal on the
    AVD returned false — a suite "PASS" that clicked nothing. Walls the earlier fixes seemed
    to clear were actually hidden by the own-activity content-changed path, which is
    race-dependent — hence the flakiness. The X6532 (Transsion → addView host) uses `views`,
    which is why the 2026-10-21 84/84 run was clean. **Fix:** read
    `views[displayId] ?: dialogViews[displayId]` (OverlayController.kt `debugClickDismiss`);
    rebuilt + `install -r`. **Device proof 18:02:** seeded always-on Gmail rule →
    `detected … launching blocker` + `decide gm -> Block`; dismiss #1 = "clicked the wall's
    Close", dismiss #2 = "no standing wall"; seeded rule cleared afterwards.
  - **Full 84-cell run COMPLETE (18:03–18:49): 84/84 PASS, 0 FAIL.** Artifacts:
    `/tmp/blocking-sweep-20261022-180332.tsv` + per-cell logs in
    `/tmp/blocking-sweep-20261022-180332-cells/`. Every verdict came from fresh engine
    evidence (decide / FGS poll / usage tick); no STALE-CARD, including W6 and the former
    30-cell chain region. This closes the matrix re-run on the sanctioned AVD.
  - **Next:** phone USB/two-device work (#50 → #36 device half → #49 → #47 → #43).
- **#50 headline:** the phone is BLOCKED (unreachable; probe loop `bswi3wvt1` alive, 120 s
  cadence); the live backend rejects emulator pairing (TEE attestation wall) **but the debug
  local rig (`SET_BACKEND` + `ATTESTATION_STUB=1`) can pair two AVDs and carry real
  notification delivery client-side** — runbook appended at the end of #50, to execute after
  the sweep. The owner-named escalation alerts (uninstall / permission revocation / rule
  change / violation / per-rule partner) are NOT implemented — only the five activity types,
  approval/plan/deletion/sync/SOS-nudge and DMS alerts exist. **#44 complete** (structural
  claims, paths, versions, ADR continuity; MilestoneSplashCard→MilestoneSectionCard fix in
  V1). **#40 complete** (72 memories verified; index line added; OPD pointer 35→37; existing
  owner amendment sanctions the AVD fallback). **#46 complete**
  (`app/docs/myexodus-system-tour.md`). #43, #47 pending; #49 queued after the sweep; #50
  partner-delivery legs need the local rig (second AVD) — phone still unreachable.
- **Devices (19:00):** phone **CONNECTED** over wireless adb — `LAN-ADDRESS-REDACTED:5555`
  (`product:X6532-OP`, transport_id 4) after the owner fixed the Wi-Fi; USB still only
  enumerates (irrelevant now). Current debug APK `install -r`'d to the phone (49,680,738 B,
  build 17:59 — data preserved, no uninstall). AVD `emulator-5554` online (MyExodus_API37);
  AVD `emulator-5556` (Lean_API34, headless, 1080x1920) online and provisioned for the rig.
- **Phone pairing leg is BLOCKED by device authentication, not connectivity:** both ceremony
  roles call `deps.sign` → `signWithAuthPrompt` → BiometricPrompt / device-credential confirm
  (`MainActivity.kt:802/948`); the owner is away, so no fingerprint/PIN can be given. This is
  the exact auth pain the owner named (#36), not a rig defect. Phone legs run as far as the
  ceremony and are recorded BLOCKED at the signature gate; the rig leg runs AVD↔AVD.
- **Local rig UP (19:01):** Firebase emulators auth 9099 / firestore 8080 / functions 5001 /
  UI 4000 (project `myexodus-53804`, `ATTESTATION_STUB=1`), log `/tmp/local-rig-emulators.log`.
  **Launcher gotcha:** under the nvm Node 24 CLI the functions discovery child was unreachable
  and the suite came up with **zero functions** ("User code failed to load … Timeout after
  10000") even though the child printed "Serving at port" and answered curl; started under
  `/usr/local/bin/node` (v22.17.1) all **11 functions load** (registerPairing verified:
  `{}` → INVALID_ARGUMENT, not 404). Always launch the suite with Node 22.
- **Reachability proven:** emulator → `10.0.2.2:9099/5001` OK; phone → `LAN-ADDRESS-REDACTED:9099` OK
  (nc). No further network setup needed for the AVD↔AVD rig.
- **#50 rig progress (19:25, session stopped on budget/ANR):** AVD A (`emulator-5554`) fully
  provisioned and through the real 5-step setup wizard (usage access, accessibility, overlay,
  notifications all Granted; FGS foreground; backend pref `10.0.2.2` proven). AVD B
  (`emulator-5556`, Lean_API34 headless, 1080x1920) provisioned: APK `install -r`, PIN `1234`
  set + verified, POST_NOTIFICATIONS granted, backend pref `10.0.2.2` proven (`MyExodusDebugBackend`),
  unlocked, app launched. A reached the real **Invite a partner** form and entered a name
  (device-credential/ceremony UI renders correctly). **Ceremony not completed**: under the
  loaded host (2 AVDs + emulator suite on 4 cores) both app processes cold-start **ANR**
  (`ANR in com.example.myexodus` → killed, `bg anr`), so UI state cannot be driven reliably;
  this is host resource starvation, not a product defect (single-instance runs are green).
- **New finding for #47/#50 (record):** two concurrently booted API-37/34 AVDs + Firebase
  suite on this laptop starve the app's cold start into ANR. Repeat the rig run on a quieter
  host (single AVD at a time or 8+ cores), or accept longer settle + ANR re-launch loops.
- **#50 COMPLETE (2026-10-22 late) — partner-delivery matrix proven end-to-end on the local
  two-AVD rig; task #50 marked done.** Rig: A = `emulator-5554` (API 37, clock deliberately
  jumped to Nov 5 for day-boundary legs), B = `emulator-5556` (API 34, headless); Firebase
  emulators under Node 22 with `ATTESTATION_STUB=1`; pair
  `GUIjbrrcmtZeot5K2Dbo0kbH8x1i_Zh09DTLvxUVHNV03f4d7SRkXClc7` epoch 2 (A sender seen as
  "ava", B receiver seen as "ben"). Legs and evidence:
  - **Coaching projection delivered, no alert:** B dashboard renders "Coaching items 2"
    (matches A's two coaching steps) and emits **no** partner alert/notification — count-only
    by design (`PartnerActivityProjection.coachingProgress`, INV-13).
  - **Recovery mirror delivered exactly once:** A's urge session COMPLETED (technique
    `urge_surfing`) → `syncOnce` = `SyncReport(eventsLinked=1, drained=true, drainedCount=1)`
    → chain `seq=15 dir=SENT payload=activity text='recovery:urge_surfing:completed'`; B
    holds exactly one seq-15 row, the feed shows one "Paced breathing — Completed · just
    now" row, and the tray shows one `partner_accountability_events_v1` notification ("New
    activity from ava").
  - **Idempotence / crash-window heal:** after a process restart the next `syncOnce` =
    `SyncReport(eventsLinked=0, drained=true, drainedCount=0)`; the chain is unchanged (still
    one seq 15) — no duplicate re-emission.
  - **D5 (no permission-revocation alert):** `GET_USAGE_STATS` revoked via appops on A, a
    sync cycle ran, B still held 15 events with no new notification. Expected: the
    owner-named escalation alerts (permission revocation / uninstall / rule changes /
    per-rule partner / violation) are not built. Grant restored (`appops … allow`).
  - **D4 (unpair / revocation, bilateral erasure):** A → End partnership → confirm →
    screen-lock PIN → A "Your data on this device has been erased / Waiting to confirm your
    partner's device…"; B pulled → local DB `events=0, enrollments=0` and UI "Your partner
    ended the partnership. Your data for it on this device has been erased."; A "Check now"
    → "Your partner's device has also erased its data." **The rig pair is now destroyed; a
    re-pair is required for any further partner legs.**
  - **D3 (multi-partner routing):** not runnable on a two-device rig (needs a third
    identity); covered historically 2026-09-29 (#39/#94, ADR #111) — recorded as carried,
    never re-faked.
  - **Finding (LOW, for #43):** the partner summary shows "Never synced yet"/"Never synced"
    when the partner simply has shared nothing (`mailboxWatermarkTsEpochMillis` is null
    because zero events were ever pulled) — the copy conflates "no successful pull" with
    "no events pulled". Not changed now (ADR #124 copy grammar / owner wording decisions).
  - **Residue, not a defect:** B's feed carries duplicate pre-fix rows (2× "reached 7 days",
    duplicate relapses) from defects already fixed and present on that rig's chain; seq 14
    and seq 15 each appear exactly once.
- **Next action (resume #49):** AVD A-side fresh state (`pm clear com.example.myexodus` on
  the AVD **only** — never the phone), then the #49 journey table below (A1–A9, B1–B5,
  C1–C7, D1–D5, E1–E5), re-pairing A↔B where a partner leg needs it → #47 stress/adversarial
  → #43 final double check + honest report + cleanup (delete `PartnerRigDiagnosticsTest` and
  `PartnerActivityDao.journalRecent`, restore A's clock). Phone legs stay BLOCKED at the
  signature gate (#36 auth) — never recorded as phone proof. Docs/memory/trainer
  (#44/#40/#46) are already complete.

## Method

- **Campaign of record:** the 2026-10-15 → 2026-10-17 exhaustive review closed at commit
  `6a9a4ff` (ADR #170): 329/329 main Kotlin files individually verdicted, findings #12–#66
  fixed with regressions, JVM 204 classes / 1,772 tests green, device 12/12. That verdict is
  reused only for files **unchanged** since `6a9a4ff`.
- **Fresh reads in this pass:** every file in the delta since `6a9a4ff` (272 files at the
  start of this review, plus the 4 uncommitted Home files), every file in each domain below,
  and the high-risk unchanged files named per domain. No file is assumed clean because an
  earlier campaign passed it.
- **Automated gates:** full JVM suite, `:app:lintDebug` **alone** (ADR #208), Functions Jest
  suites, `scripts/domainusage-ci-checks.sh`; results recorded under Verification.
- **Runtime/stress (#47):** API-37 AVD (sanctioned exception) and the X6532 whenever it is
  reachable; `install -r` only on the physical device, never uninstall.
- **Docs/memory (#40, #44):** every `app/docs/*.md`, `app/plans/*.md`, and every memory file
  checked against current code and git.

## Review map (#37)

| Task | Domain | Scope |
|---|---|---|
| #45 A | Blocking + usage accounting | `blocking/`, `blocking/usage/`, `domainusage/`, `vpn/`, rule engine/stores and their tests |
| #39 B | Data layer, crypto, migrations | `data/db/**`, `security/**`, `schemas/**`, stores/DAOs/entities, store factories, migration paths |
| #38 C | UI, view models, navigation, strings, a11y | `ui/**`, resource XML (strings/themes/layouts), navigation, accessibility semantics, view models |
| #41 D | Partner accountability + SOS | `accountability/**`, `functions/**`, `firestore.rules`, `firestore.indexes.json`, SOS surfaces |
| #42 E | Tests, build, manifests, security posture, deps | `app/src/test/**`, `app/src/androidTest/**`, `app/build.gradle*`, root Gradle, manifest, ProGuard rules, dependencies, exported components |
| #47 F | Stress + adversarial runtime | Cold start, process death, store failure, quota boundaries, huge rule sets, rapid input, airplane mode, reboot, clock moves, Doze; on AVD first, X6532 where reachable |
| #46 | Trainer | `app/docs/myexodus-system-tour.md` — guided deep-tour of the system |
| #44 | Docs audit | Every `app/docs/*.md` and `app/plans/*.md` vs current code |
| #40 | Memory audit | Every memory file vs code/git/docs |
| #43 | Double check | Re-run gates, full verification report, honest residue list |

## Delta since `6a9a4ff` (campaign close)

Twenty-five commits, 272 files, +33,256 / −1,834 lines. Composition: 129 main Kotlin files,
61 unit-test files, 6 androidTest files, 6 debug-only sources, 4 Room schema snapshots,
24 backend `functions/` files, 3 scripts, `firestore.rules`, `firestore.indexes.json`,
16 docs, 15 plans, manifest and 3 resource files. The delta is dominated by the Partner
Accountability workstream (Steps 1–12, ADRs #194–#207), the Insights page (ADR #210), the
lint-gate restoration (ADR #208), and the Home quick-action/stat-scope thread (ADR #211).
The full path list is the appendix at the end of this file.

Uncommitted at review start: `AppShell.kt`, `RecoverySession.kt`, `strings.xml` (Home
quick action — ADR #211) and `HomeScreen.kt` (stat scope — same ADR).

## #45 A — Blocking + usage accounting (complete)

### Files read line-by-line in this pass

Every delta file in the four domain directories since `6a9a4ff` (33 committed + the 7
uncommitted paths), every file changed by this review pass, plus these high-risk unchanged
files: `RulesSnapshot`, `RulesRepository`, `RulesSnapshotPublisher`, `RoleHolders`,
`EmergencyExclusionManager`, `ProtectionStatusMonitor`, `AttemptRecorder`, `UsageLedger`
(domainjournal), `UsageLedgerRepository` (append/rebuild/retention), `UsageTickLoop`,
`UsagePersistence`, `UsageGapRepair`, `UserStoppedDetector`, `UsageEventsExt`,
`AccountingOutageSource`, `AccountingDiagnostics`, `AccountingDiagnosticsState`,
`UsageDiagnosticsRecorder`, `UsageLedgerMapper`, `PlatformUsageReader`, `DomainUsageEngine`,
`DomainRuleProvider`, `AttributionResolver`, `FlowTap`, `DnsObservationStore`, `DnsMessage`,
`DnsShaper`, `TlsClientHelloParser`, `RuleCounter`, `RuleIndex`, `HostNormalizer`,
`FlowOwnerClassifier`, `BrowserPackages`, `DomainBlocklist`, `VpnPacket`, `RuleEditor`'s
canonicalization call sites, and their tests. New fresh reads closing the delta:
`BlockOverlayView`, `UsageAccountingEngine`, `AndroidDomainUsageHost`, `Clocks`, `DomainRule`,
`DomainUsageHost`, `EngineCounters`, `FlowOwner`, `InspectingTcpTap`, `SystemWallClock`,
`TcpSendWindow`.

Carried verdicts (unchanged since `6a9a4ff`, verdicted in ADR #170; not re-read whole):
`AccessibilityEventNormalizer`, `AppBlockScanner`, `BlockerActivity`, `BootReceiver`,
`BootReconciliation`, `DailyMaintenancePass`, `DailyMaintenanceWorker`,
`EnhancedModeHealthMonitor`, `ForegroundStateMachine`, `HealthCheckWorker`, `LatencyProfiler`,
`LockedBootReceiver`, `ProtectionActionReceiver`, `ProtectionNotifications`,
`ProtectionStatusStore`, `Rule`, `UsageAccess`, `blocking/recovery/**`,
`usage/AccountingOutagePrompt`, `usage/LedgerMaintenanceState`, `usage/UsageSessionMachine`,
`domainusage/DomainUsageSettings`, `EncryptedDnsResolvers`, `EngineMode`, `EngineStatus`,
`FlowControl`, `FlowRegistry`, `InspectionBufferPool`, `MeteringConfig`, `NeverBlockDomains`,
`OwnedBytes`, `PrivateDnsMonitor`, `PublicSuffixList`, `RuleUsageDaily`, `RuleUsageDao`,
`vpn/DnsMessages`, `EngineHealth`, `VpnLifecycle`.

### Findings fixed in this pass

**F45-1 (HIGH, correctness) — URL decision made on a fresh snapshot read (TOCTOU).**
`BrowserUrlScanner` resolved the matching URL rule (snapshot read 1) to fetch *that rule's*
usage, then called `BlockDecisionEngine.decideUrl`, which resolved the rule again (read 2). A
rules publish landing between the two reads decided against a different rule than the usage
was measured for — a replacement rule inheriting another rule's usage, or `Allow` after the
usage gate had been consulted. Fix: the resolved `UrlPattern` is passed to the new
`decideUrlFor(pattern, …)`; `decideUrl` now delegates to it. Regression:
`BlockingPipelineTest.urlDecision_usesTheResolvedRule_notAFreshSnapshotRead` (flipping engine:
first read shows the rule, every later read shows none; the decision must come from the first).

**F45-2 (HIGH, enforcement) — the block card could name and cover the wrong app.**
Three linked lifecycle defects:
(a) `coverBlockedApp` skipped rendering whenever *any* scene was shown. A warning banner, a URL
card, or another app's persistent block card can be up when a blocked app reaches the
foreground (notification-shade tap inside a banner's 5 s window). The app was then covered by
a foreign card, and dismissing that card left the app unblocked.
(b) `OverlayController.show` on block→block only refreshed bounds, so the foreign content
survived the rebind.
(c) `blockOtherRestrictedWindows` picked the largest window per display; right after a task
switch the blocked app's exit-animation window can still be largest, labeling and bounding the
card for an app that is no longer in front.
Fix: the scene-coverage gate accepts a block scene only when it names the deciding package
(`AppBlocked`/`DailyLimitBlocked`); block→block with different content rebinds it; the deciding
foreground package wins its own display while other displays keep the largest-window rule; a
rebind is not a new submission and records no pending-decision profile entry.
Coverage: `BlockingPipelineTest`, `BlockerAccessibilityServiceConstructionTest`,
`OverlayControllerTest` pin the contracts; the foreign-card and exit-animation scenarios are
#47 device legs because these edits are newer than the 2026-10-21 matrix run.

**F45-3 (HIGH, accounting) — gap repair could not credit a package foreground across the
whole interruption.** Both repair paths (`probeUncreditedWindows` and `diagnosticsPass`,
`EnforcementForegroundService`) queried foreground marks from the window start. A package that
was in the foreground for the entire interruption emits no ACTIVITY_RESUMED inside the gap
window, so its marks list was empty and the repair credited zero for real platform usage. Fix:
both paths query from local midnight (`minOf(midnight, window.first)` survives a
midnight-crossing race); credit is still clipped to `request.window` by
`UsageGapRepair.foregroundMsWithin`. The clipping mechanics are pinned by
`UsageGapRepairTest.foregroundMsWithin_clipsToTheWindowOnBothEnds` (a RESUMED at midnight −
300 s is credited only inside the window).

**F45-4 (MEDIUM, matching) — a DNS root trailing dot made a saved URL rule unmatchable.**
`UrlPattern.parseStructure` kept the trailing dot on the host. Browsers drop it and the
address-bar text the scanner reads is normalized, so a rule saved as `example.com.` — the
editor canonicalizes and validates through this same parser — could never match anything. Fix:
`removeSuffix(".")` with an empty-host guard, applied to rules and candidates alike.
Regression: `UrlPatternTest.trailingRootDot_isDroppedOnRuleAndCandidate`.

**F45-5 (MEDIUM, plan fidelity) — Decision 20's timezone half was never enforced.**
The day anchor is the `(localDateAnchor, timezoneIdAtAnchor)` pair; the plan (v2 §6 step 6) and
the ADR say any mismatch resets, including a timezone move. Four write paths
(`processUsageObservation`, `reconcileWithPlatform`, `creditReconciledWindow`,
`rolloverStaleDays`) compared the date alone, while the advisory `remainingBudgetMs` compared
both — after a same-date zone move the two disagreed about the day's usage. Fix: one
`DailyUsageTracker.isStaleFor(today, zoneId)` used at all five sites; every engine test fixture
now stamps the device zone instead of a hardcoded `"UTC"`; the misleading
`rollover_anyAnchorMismatchResets_coveredByTimezoneMoveCase` (it only covered a date mismatch)
is renamed to `rollover_futureDateAnchor_resets`, and a real timezone-move regression was added:
`UsageAccountingEngineTest.rollover_timezoneMove_sameDate_resets`. The same-date move
deliberately leaves the journal's day history intact while the live counter restarts — recorded
on the engine's invariant; `rebuildProjection` has no production caller.

**F45-6 (LOW, cleanup) — dead parameter removed.**
`DnsSinkholeVpnService.sendAck(flow, win = -1)` never used `win` (`buildTcpSegment` always
advertises 65535); the parameter and five call-site arguments are gone.

### Known limits (observed, not changed)

- `DnsObservationStore`'s pending map is keyed `version:id`, so two concurrent queries carrying
  the same DNS transaction id collapse into one slot. The resolver's retry plus the
  `dns_fallback_*` counters absorb the miss; fixing it would need a per-query token, a
  wire-format-independent redesign. Unchanged since ADR #189.
- Repair credit remains `max(platform, journal)` seeded through `MIGRATION_CARRYOVER`; a
  platform total that is lower than the journal is never allowed to lower usage.

### Verification (close-out, after every fix in this pass)

- `:app:lintDebug` run **alone** (ADR #208) → **BUILD SUCCESSFUL** 21m 16s, exit 0;
  `app/build/reports/lint-results-debug.txt` = **"No issues found."** (0 errors / 0 warnings /
  0 hints, no baseline).
- `:app:testDebugUnitTest` run **alone** → **BUILD SUCCESSFUL** 4m 45s, exit 0; aggregated
  from the JUnit XML (225 `TEST-*.xml` classes): **2,010 tests / 0 failures / 0 errors /
  0 skipped**. Progression on this tree: 1,987 (post-lint-fix) → 2,004 (Insights) → 2,010
  (this pass's regressions: `PlaintextConversionFileOpsTest` +2, `HabitRepositoryTest` +1,
  plus the F38/F45 fix regressions).
- `:app:assembleDebugAndroidTest` → BUILD SUCCESSFUL, exit 0.

## #38 C — UI, view models, navigation, strings, accessibility (complete)

### Files read line-by-line in this pass

The complete UI delta: all 29 present files under `ui/` plus the 4 widget files. `ui/streaks/
MilestoneSplashCard.kt` is in the delta because it was **deleted** (confirmed absent; the
milestone is now the modal `MilestoneReachedDialog`, which was read in full). UI delta read:

`AppShell`, `NavTile`, `NotchedNavBar`, `StatusPill`, `home/BlockingReadiness`, `home/HomeScreen`,
`insights/InsightsCharts`, `insights/InsightsPresentation`, `insights/InsightsScreen`,
`insights/InsightsViewModel`, `partner/PairingCeremonyScreen`, `partner/PartnerDashboardSource`,
`partner/PartnerPlanEditor`, `partner/PartnerPlansScreen`, `partner/PartnerScreen`,
`partner/SharingScreen`, `recovery/RecoveryScreen`, `rules/RuleEditor`, `rules/RulesScreen`,
`rules/WebsitePresetSheet`, `rules/WebsitePresets`, `settings/DomainUsageDebugSection`,
`settings/SettingsScreen`, `streaks/MilestoneReachedDialog`, `streaks/StreakCards`,
`streaks/StreaksPresentation`, `streaks/StreaksScreen`, `streaks/StreaksViewModel`,
`streaks/WithdrawalTimelineCard`, `widget/DesertDawnGlanceTheme`, `widget/DesertDawnWidget`,
`widget/WidgetActionActivity`, `widget/WidgetContent`.

Resources: `res/values/strings.xml` (1,216 lines, both halves), `res/values/strings_recovery.xml`,
and `res/drawable-nodpi/widget_preview.png` (verified 624×348 RGBA — the widget-preview slot).

App wiring read fresh: `MainActivity.kt`, `MyExodusApp.kt`, `ReceiverRegistration.kt`; the data
contract the Home quick action writes (`data/db/entity/RecoverySession.kt`); and the widget
surface's write path, `widget/WidgetActionViewModel.kt` (unchanged since `6a9a4ff`, read in full
because `WidgetActionActivity`'s dismissal guard depends on its state machine).

Carried verdicts (unchanged since `6a9a4ff`, verdicted in ADR #170; not re-read whole — the
36 remaining present UI files): `Adaptive`, `AppLabels`, `BackRow`, `BrandMark`, `LinkRow`,
`ScheduleFields`, `ScheduleFormat`, `SecureSurface`, `home/MilestoneSectionCard`,
`home/NudgeInboxViewModel`, `home/SosViewModel`, `partner/PartnerPresentation`,
`partner/PartnerSelection`, `recovery/BreathingPacer`, `recovery/BulletLine`,
`recovery/CoachingPlanScreen`, `recovery/CrisisScreen`, `recovery/HistorySummary`,
`recovery/PrivacyGate`, `recovery/RecoveryPrefs`, `recovery/RiskWindowsScreen`,
`recovery/SessionHistoryScreen`, `recovery/TechniqueRunner`, `recovery/UrgeLogSheet`,
`rules/AppPicker`, `settings/AboutScreen`, `settings/EnhancedModeScreen`,
`settings/NudgeSettingsViewModel`, `settings/ProtectionScreen`, `settings/SettingsRows`,
`setup/SetupScreen`, `streaks/HabitPickerSheet`, `streaks/HabitSetupCard`, `theme/Color`,
`theme/Theme`, `theme/Type`. (`UrgeLogSheet` is newly reachable from AppShell — ADR #211 — and
its contract was verified through that call site.)

### Findings fixed in this pass

**F38-1 (MEDIUM, false claim) — the Rules editor said the block was deleted while the list was
still loading.** Two entry paths reach the editor route with `rules == null`: process-death
restore and Home's block-row deep link (`openRuleId`). The old branch fell through to
`EditorUnavailable` ("This block was removed on another screen") until the Flow's first emission —
a claim about data the screen did not yet have. Fix: `RulesLoad.Failed` renders the retry card,
`editRuleId != null && rules == null` renders `RulesLoading`, and only a loaded-but-absent id
renders `EditorUnavailable`.

**F38-2 (MEDIUM, error surfacing) — a preset-batch failure vanished with its sheet.**
`blockPresetSites` reported partial-write failures only through `presetError`, which lives inside
the bottom sheet. Dismissing the sheet mid-batch deleted the message while the writes were still
completing — silent partial state. Fix: on failure with the sheet already dismissed
(`presetCategoryId == null`), the same message is mirrored to the list-level `errorMessage`.

**F38-3 (HIGH, mis-attribution) — a failed reload could show one partner's dashboard under
another partner's selection.** `reload()` keeps the last known state when the load throws, but it
also re-runs when the switcher changes `selectedPairId`; the failure path kept the cache
unconditionally, so pair A's dashboard could be presented as pair B's. Fix:
`keepsUnderSelection()` keeps a `Paired`/`Ended` state only when it names the selected pairing
(or when no selection exists); otherwise the screen returns to `Loading`.

**F38-4 (HIGH, ordering) — two post-await publishes bypassed the reload epoch guard.**
`sharesNothing` and `partnerStatus` are computed after two suspending reads and were written
*after* the epoch check that guards `state`, so an older reload could publish pair A's readings
on top of a newer reload's pair B. Fix: both results are held in locals, the epoch is re-checked
immediately before publishing, and a superseded reload publishes neither.

**F38-5 (MEDIUM, lifecycle) — `reset()` cleared an in-flight write's state.**
`WidgetActionViewModel.reset()` (called on `onNewIntent`) set urge/relapse back to `None` even
while a write coroutine was running. That state drives the action surface's dismissal gate, so
dismissal became possible mid-write — and finishing cancels `viewModelScope` under the repository
call. Fix: an in-flight outcome is never cleared by `reset()`; completed results still reset.

**F38-6 (MEDIUM, accessibility) — Home's quick-action FABs were unnamed buttons.**
The extended FAB's text slot did not reach the exported accessibility node on device (TalkBack
announced an unnamed button). Fix: the label is also set as the node's `contentDescription`
(`QuickAction`, AppShell).

### Verified in the delta (no change needed)

- **Insights empty state does not hide data.** `InsightsSnapshot.isEmpty` includes
  `recovery.allTime` and `habits`, not just blocking/usage — the concern that a recovery-only or
  habit-only account would see "nothing yet" is closed by the definition itself
  (`InsightsRepository.kt:61-66`).
- **Ceremony uid path cannot throw.** `PairUidResolver.ensureUid()` swallows a failed anonymous
  sign-in and returns null (`PartnerSyncCoordinator.kt:675`), and all three ceremony call sites
  map null to the offline copy. The unguarded `deps.myUid()` calls are safe by contract.
- **ADR #211 uncommitted delta verified.** Home's urge quick action opens the Recovery screen's
  own `UrgeLogSheet` and writes through `sessions.logOnly(entry = ENTRY_HOME)` (a habitless,
  rule-less row); the confirmation string is resolved in composition (lint ADR #208); the Home
  stat scope and the `RecoverySession.ENTRY_HOME` contract are consistent.
- **Strings.** All user-facing copy answers what/why/action, names no internals, and every
  format argument and plural quantity matches its call sites. `tools:ignore="PluralsCandidate"`
  is limited to the `%1$d:%2$02d` clock strings where a plural is not a plural.
- **Widget composition.** Every variant path stays under Glance's 10-child RemoteViews cap
  (tall: 7 direct children + one nested 3-child band), all three controls carry content
  descriptions, and the four declared sizes bracket the values the tested launchers report.

### Known limits (observed, not changed)

- Widget control targets are 28dp (narrow 2×2) and 36dp (wide variants) — below the 48dp touch
  guideline, constrained by three controls in a ~104dp 2×2 cell and owner-approved as icon-only
  (2026-10-16/17). RemoteViews hosts deliver no touch stream, so the hold-to-confirm ring cannot
  enlarge the target on the widget surface itself.
- `StreaksViewModel`'s repository writes (rename/delete/onboard/logRelapse) have no generic catch;
  whether they can throw is the repository contract, carried to #39 rather than guessed here.

## #39 B — Data layer, crypto, migrations, stores/DAOs (complete)

### Files read line-by-line in this pass

Every delta file in the domain: `data/db/ActivityLogStore`, `AppRulesStore` (schema v20,
28 entities, 20 forward + 9 downgrade migrations), `AppRulesStoreFactory`, the ten delta DAOs
(`AttemptLogDao`, `HabitProgressDao`, `MilestoneEventDao`, `PairSharingDao`,
`PartnerEventJournalDao`, `PartnerLivenessDao`, `PartnerMonitoringSnapshotDao`,
`PartnerPlanDao`, `RecoveryStreakDao`, `UsageLedgerDao`), the seven delta entities
(`DeviceLiveness`, `PairProfileSharing`, `PartnerEventJournal`, `PartnerMonitoringCheckin`,
`PartnerMonitoringSnapshot`, `PartnerPlan`, `PartnerPlanTask`), and the exported schemas
`17.json`–`20.json` (checked field-by-field against the entities).

The crypto/conversion seams, read in full because this pass edits or re-verifies them:
`PlaintextToEncryptedMigration`, `RulesStoreEncryption`, `SqlCipherRawKey`,
`KeyFetchingSQLCipherDriver`, `RulesStoreKeyManager`, `ActivityLogKeyManager`,
`ActivityLogSchema`, `AndroidDatabaseKeyManager`, `KeystoreWrappedDekManager`,
`DatabaseKeyManager`, `ProtectedPackageException`, `DatabaseHealthStore`,
`SchemaConstraints`, `RecoveryManager`, `DatabaseMigration`. The store consumers whose
failure contract this domain owns: `streaks/HabitRepository`, `streaks/MilestoneScheduler`,
`ui/streaks/StreaksViewModel`.

Carried verdicts (unchanged since `6a9a4ff`, verdicted in ADR #170; not re-read whole): the
other ten DAOs (`CoachingStepDao`, `DailyUsageTrackerDao`, `PartnerActivityDao`,
`PolicyVersionDao`, `RecoveryPolicyDao`, `RecoverySessionDao`, `RecoveryStateDao`,
`RestrictionRuleDao`, `RiskWindowDao`, `UserProfileDao`), the other 21 entities, and the
exported schemas `1.json`–`16.json`. `DailyUsageTracker` and `RecoverySession` were read in
this pass under #45/#38 respectively. The `security/**` entry in the review map resolves to
these `data/db` crypto/seam files — the app has no separate `security` package (verified).

### Findings fixed in this pass

**F39-1 (MEDIUM, conversion correctness) — a pre-v8 plaintext database kept its plaintext
window for an extra launch.** Encryption shipped at schema v7 (2026-09-14), so a store that
had not been opened since before v7 can predate NOT NULL columns added later — v7→v8 rebuilds
`daily_usage_tracker` with the `(local_date_anchor, timezone_id_at_anchor)` pair and no
defaults. The conversion copies only shared columns into a destination Room built at the
*current* schema, so the INSERT failed; the conversion reported `Pending`, the plaintext open
path let Room migrate the file, and only the *next* launch converted. Encryption is the point
of the migration, so the one-launch plaintext window is a defect, not an inconvenience. Fix:
the source is opened through Room at the current schema (`migratePlaintextSource`) after the
`.bak` backup and before the destination build, so a migration defect can never cost the
pre-migration rows and the copy always sees a current schema. A source-migration failure
defers the conversion with the file untouched; the normal plaintext open then surfaces the
underlying cause. Regressions:
`PlaintextConversionFileOpsTest.prepareForOpen_plaintext_migratesSourceAfterBackupAndBeforeDestinationBuild`
(order asserted: backup → migrate → build) and
`..._plaintextSourceMigrationFailure_defersAndLeavesTheFileAlone` (Pending, no destination
build, source bytes unchanged). ADR #37's conversion decision carries the dated amendment.
The encrypted round trip remains device-only (`RulesStoreConversionInstrumentedTest` —
SQLCipher cannot load under Robolectric).

**F39-2 (MEDIUM, resource leak) — ActivityLogStore could leak an opened SQLCipher handle.**
`open()` adopted the handle into `db` only after `applyPragmas`/`createSchema` succeeded; a
throw in either step abandoned the live handle (native connection + FD + raw-key array) while
`open()` returned false and was retried — leaking once per attempt. `reopenFresh` had the same
shape. Fix: an `opened` local owns the handle and the `finally` closes it unless it was
transferred; both paths zero their key material and close exactly once.

**F39-3 (HIGH, crash boundary) — HabitRepository let post-open store failures escape.**
`HabitRepository`'s KDoc promises writes are "refused with a defined result rather than
crashing, and alarm receivers therefore cannot take the app down", but only a null
`storeFactory.open()` was handled. Any failure inside a DAO or write transaction (I/O,
corruption surfacing late, a closed pool) propagated, and the callers are exactly the
background surfaces the promise names: `MilestoneAlerter` (alarm receiver),
`WidgetActionViewModel` (widget action), `ProtectionSnapshotWriter`, and the streaks
ViewModel launches. Fix: a `guardingStore` boundary on every public entry point — it rethrows
`CancellationException` untouched and maps other exceptions to that entry point's existing
defined result (`StreamUnavailable`/`StoreUnavailable`, the last emitted state, `emptyList`,
`null`, `Unit`). `scheduler.reconcile` runs in its own best-effort guard so a scheduling
failure can never report a durable write as unwritten (retrying a "failed" relapse or
onboarding could otherwise double-write). `StreaksViewModel.onboard` additionally maps
`RulesRepository.ensureActiveProfile` failures — a second store open outside this repository
— to `SetupError.STORE_UNAVAILABLE`; its other launches need no catch because every repository
call they make is now inside the boundary. Regression:
`HabitRepositoryTest.storeFailureAfterOpen_mapsToTheDefinedFailureResult_atEveryEntryPoint`
(closes the cached Room handle, then calls all eleven entry points; each returns its defined
result, `loadFailed` reports the Read, and acknowledgment returns instead of throwing). The
#38 known-limit entry above is resolved by this finding.

**F39-4 (LOW, cleanup) — orphaned KDoc.** The new source-migration method was inserted
between `buildTempDestination`'s KDoc and the method itself; the comment block was re-attached
to `buildTempDestination` (the review caught it in the same pass that wrote it).

### Verified in the delta (no change needed)

- **Schema/entity parity, v17–v20.** Scripted dump against the 28 entity classes: v17 = 24
  tables, v18 = 25 (+`partner_event_journal`), v19 = 28 (+`device_liveness`,
  `partner_monitoring_checkin`, `partner_monitoring_snapshot`), v20 = 28 (one column added).
  Column names, nullability, defaults, indices and foreign keys match exactly.
- **Migration coverage.** `ALL_MIGRATIONS` holds all 20 forward steps and all 9 downgrade
  steps; `AppRulesStoreMigrationTest#allMigrations_coverEveryVersionStepToTheDeclaredSchemaVersion`
  pins coverage to the version Room creates fresh. The downgrades exist because rollback is a
  supported reinstall path, not destructive migration.
- **Raw-key lifecycle.** The DEK is zeroed after the raw passphrase is derived; the raw
  passphrase is held for the database's lifetime and zeroed on connection close
  (`KeyFetchingSQLCipherDriver.ZeroingConnection`); every failure path zeroes both arrays
  (`SqlCipherRawKey`, `ActivityLogStore`, `DatabaseMigration`'s activity-log path). The
  wrong-key probe (`SQLITE_NOTADB`, code 26) is recognized by class *and* message because XOS
  interposers wrap the exception.
- **Conversion ordering and durability.** backup → source migration → destination build →
  one-transaction row copy (0-based reads / 1-based binds, `reset()` per row) → per-table row
  count verification → same-directory atomic swap → two-launch backup confirmation. Every
  pre-swap failure leaves the plaintext file usable.
- **Legacy split migration.** Counts are verified per table before the legacy file is
  deleted; `INSERT OR IGNORE` makes re-runs idempotent; NULL columns bind as NULL (not coerced
  0/""); the v16 consent columns are deliberately not copied (ADR #135's "nothing until you
  choose").
- **Keystore scheme.** StrongBox attempted first with TEE fallback on `ProviderException`;
  no user-authentication requirement (the store must open from background components);
  invalidation by biometric enrollment disabled; SharedPreferences access is lazy so a
  pre-unlock process start can construct the graph without touching CE storage.

### Known limits (observed, not changed)

- `DatabaseMigration.verifiedCounts` compares destination row counts to the legacy table's. A
  destination already holding rows from a prior partially completed run with *different* ids
  would keep failing verification. Unreachable in practice: the destination only ever receives
  this migration's rows, and `CONFLICT_IGNORE` dedupes re-runs; unchanged since the campaign of
  record.
- The conversion copies shared columns only, so a column added with a NOT NULL default in a
  newer schema would inherit Room's default rather than a legacy value. No such column exists
  today — v8's anchors were the only NOT NULL additions without defaults, and F39-1 removes
  them from the copy path by migrating the source first.

## #41 D — Partner accountability + SOS (complete)

### Files read line-by-line in this pass

**Backend — the whole tree.** All 25 top-level `functions/*.js`: transport/dispatch
(`fcm-transport`, `dispatch`, `dispatch-logic`, `dispatch-record`), relay (`relay`,
`relay-logic`), pairing (`pairing-guards`, `rate-window`), DMS (`dms`, `dms-logic`,
`dms-processor`), heartbeat (`heartbeat`, `heartbeat-logic`), SOS (`partner-sos`,
`partner-sos-logic`, `partner-sos-push`, `partner-sos-push-logic`, `partner-sos-scheduler`),
attestation (`attestation`, `proof`), maintenance (`cleanup`, `sweep`, `mailbox-retention`,
`index-build-guard`), and the entrypoint `index`. All 14 `functions/test/*.test.js` files plus
`test/helpers/der-pki.js`; every emulator suite (`firestore-rules`, `heartbeat`, `partner-sos`,
`partner-sos-push`, `partner-sos-scheduler`, `dms`) and `dms-soak.js`. `firestore.rules`
(244 lines, rule by rule) and `firestore.indexes.json` checked against every server query.

**Client — 56 of the 93 present `accountability/**` files, read fresh.** All 32 delta files in
the domain were read (delta split re-checked with `git diff --name-only 6a9a4ff..HEAD --
app/src/main/java/com/example/myexodus/accountability` plus the uncommitted tree; no delta
file is carried). The fresh-read set, by directory:

`BilateralDeletion`, `EventCreator`, `activity/PartnerActivityJournal`,
`activity/PartnerActivityReceiver`, `approval/ApprovalFlow`, `crypto/AccountabilityKeyStore`,
`crypto/AccountabilitySigner`, `crypto/AttestationVerifier`, `crypto/EventCipher`,
`enrollment/EnrollmentDao`, `enrollment/PairingFlow`, `monitoring/MonitoringSnapshotReceiver`,
`monitoring/MonitoringSnapshotRecorder`, `monitoring/PartnerDmsClient`,
`monitoring/PartnerHeartbeatClient`, `plan/PartnerPlanReceiver`,
`plan/PartnerPlanRepository`, `serialization/AccountabilityEventWire`,
`serialization/CanonicalCbor`, `serialization/Envelope`, `serialization/EventPayload`,
`serialization/PlanPayload`, `sos/NudgeEnvelope`, `sos/NudgeInboxRepository`,
`sos/NudgeRepository`, `sos/NudgeSettingsRepository`, `state/AccountabilityDatabase`,
`state/ChainEvent`, `state/ChainEventDao`, `state/ChainStateStore`, `state/Outbox`,
`state/OutboxDao`, `state/ProcessedEvent`, `state/ProcessedEventDao`, `state/UnpairState`,
`state/UnpairStateDao`, `sync/ChainRepairCoordinator`, `sync/FirestoreMailboxReceiver`,
`sync/FirestoreSchema`, `sync/MailboxEventProcessor`, `sync/MailboxMessageHandler`,
`sync/MailboxOutboxDrainer`, `sync/MailboxRejectionChecker`, `sync/OutboxBackoff`,
`sync/PairingExchange`, `sync/PairingRegistration`, `sync/PartnerAlertHealth`,
`sync/PartnerMessagingService`, `sync/PartnerNotifications`, `sync/PartnerNudgePush`,
`sync/PartnerPushParser`, `sync/PartnerRolloutGate`, `sync/PartnerSyncCoordinator`,
`sync/PartnerSyncSignals`, `sync/PartnerSyncWorker`, `sync/PushInstallationRegistrar`.

**SOS surfaces.** `ui/home/SosViewModel`, `ui/home/NudgeInboxViewModel`, and
`ui/settings/NudgeSettingsViewModel` read fresh here; the two delta pairing screens
(`ui/partner/PairingCeremonyScreen`, `ui/partner/PartnerScreen`) were read cover-to-cover in
#38 C, and `ui/partner/PartnerDashboardSource` in #38 C/#39 B.

Carried verdicts (unchanged since `6a9a4ff`, verdicted in ADR #170; not in the delta, verified
by the split above): the 37 remaining present files — `crypto/BiometricEnrollmentMonitor`,
`EcdsaLowS`, `Hkdf`, `IdentityEpochStore`, `IdentityMigration`, `SigningPath`;
`enrollment/CekDerivation`, `Ecdh`, `EnrollmentFlow`, `EnrollmentPayload`, `EnrollmentRecord`,
`EnrollmentTargeting`, `EnrollmentTranscript`, `PairingCeremonyTelemetry`, `PairingCode`,
`PairingCodec`, `PairingProof`, `PairingToken`, `PartnerLabel`;
`state/ApprovalRequest`, `ApprovalRequestDao`, `ChainHead`, `ChainHeadDao`, `IdentityEpoch`,
`IdentityEpochDao`, `LocalWipeDetector`, `SessionKeyManager`;
`sync/AnimatedQrRenderer`, `AppCheckInstaller`, `FirestoreTasks`, `MessageDelivery`,
`PairingReachabilityStore`, `PartnerLiveSync`, `PartnerSyncTelemetry`, `QrCameraDecoder`,
`QrChunkCodec`, `QrReassembler`.

### Findings fixed in this pass

**F41-1 (HIGH, test fidelity) — the SOS emulator suites pinned a wire contract the dispatcher
stopped using on 2026-10-04, and could no longer pass.** `partner-sos-push.emulator.js` asserted
`message.fids` in six places and `channelId: 'partner_nudges'`; its scripted-FCM callbacks also
called `m.fids.map(...)`, so any test that routed a message threw inside the double before its
assertions. `partner-sos-scheduler.emulator.js` asserted `fids` once. The shipped engine
(`partner-sos-push.js`) sends `{ tokens }` (D13 — token targeting, 2026-10-04) on channel
`partner_nudges_v3` (D14). Evidence: `npm run test:sos` before the fix → 38/44, failing exactly
T-F21, the target-snapshot test, T-F22, T-F24, the vanished-device test, and scheduler T-F19;
after → 44/44. Fix: assertions moved to `tokens` + `partner_nudges_v3`, and seed devices carry
per-install tokens (`tok-d1`…) so the target→token mapping stays asserted instead of collapsing
to one indistinguishable value. The routine gates (`npm test`, the JVM suite, lint) do not run
emulator suites, which is why the drift survived from 2026-10-04 to this review.

### Verified in the delta (no change needed)

- **SOS dispatch engine, end to end.** Lease claim is atomic and fences every post-send write;
  a queue missing under a pending nudge is repaired preserving the attempt budget; queue+nudge
  same-transaction finalization; suppression re-read after the lease; expiry finalized before
  any send; FCM errors split invalid-target / payload / transient; the attempt budget is capped;
  orphans and stale terminal queues are deleted. Now also pinned against the shipped token wire
  contract (F41-1).
- **DMS processor.** CAS on `(liveness_generation, last_received_at_ms)`, deterministic outbox
  ids, claim-before-send revalidation of pair/consent/state inside the transaction, fenced
  finalization, at-most-once LATE reminder marked before send, recovery notice collapsing,
  outage rebasing, circuit breaker, and the Step-12 operator gate that disables transitions and
  dispatch but deletes nothing.
- **`sendNudge` acceptance.** Kill switch read inside the transaction (uncached), membership and
  active-pair gates, replay/sender-mismatch/envelope-mismatch classification, cooldown before the
  Nairobi-day cap, blocked-at-acceptance suppression with no queue, rate state and nudge/queue
  writes in one transaction. Pull-based pair ids cannot be shaped into a path that resolves to an
  attacker-controlled document: any deeper path resolves outside the `pairings` doc schema and
  fails the authoritative `members` check.
- **Pairing §9/§10 guards.** `requestId` is doc-id-safe by regex, the request hash excludes it,
  the cache lookup precedes every other check, retention is 24 h, the per-UID window advances
  only on a genuine execution, and the parsed-request size ceiling is enforced before crypto.
- **Rules and indexes.** Mailbox create is a six-key allowlist gated on an active pair and the
  recipient identity; nudges allow expiry-gated `get` and ungated `list` (TTL lag is handled
  client-side); acks are recipient-only; pairing tokens are single-use; DMS/queue/config
  collections are admin-only. Every server query has a supporting index.
- **Heartbeat acceptance transaction.** Install-state survives identity rotation; ACCEPTED
  writes the recovery outbox row in the same transaction and suppresses a notice already
  recovered, clears `notice_state`, and carries the notification generation.
- **Payload blindness.** Relay wakes are `{v, type, messageId}` only; DMS notices carry fixed
  copy and a hashed per-pair collapse key; check-in reminders are fixed self-copy with their own
  collapse key. All pinned by `push-payload.test.js` and `relay-blindness.test.js`.
- **Client SOS chain.** `NudgeRepository` maps ambiguous transport outcomes to a retry-stable
  `(nudgeId, envelope)` and rethrows cancellation; `NudgeEnvelope` seals v1/v2 under a fixed AAD,
  bounds and NFC-normalizes text, sanitizes on open, and never throws; the inbox decrypts only
  the newest document and never silently acks an unreadable one; the settings switch is
  optimistic with revert; `SosViewModel` ignores taps mid-flight and replays the retained pair.

### Verification (this pass)

`npm test` in `functions/` → **147/147**. `npm run test:sos` (Firestore emulator) → **44/44**
(was 38/44 before F41-1).

### Known limits (observed, not changed)

- **Cancellation swallowed at best-effort boundaries.** `PartnerSyncCoordinator`'s step guards
  (`runCatching` around sync steps), `MailboxOutboxDrainer.bookkeeping`/`stillAuthorized`,
  `MailboxRejectionChecker.check`, and `PushInstallationRegistrar` catch `Exception`, which
  includes `CancellationException`. Accepted: these are explicitly step-isolated passes whose
  contract is "this step can never fail the sync it runs in"; after cancellation every
  subsequent suspend call rethrows immediately, so no meaningful work proceeds, and the
  documented self-healing paths cover the skipped bookkeeping. Rewriting them would trade a
  designed failure-isolation contract for cancellation purity with no observed defect.
- **FID → registration-token deviation (D13).** Documented in `nudgeFcmMessage`; revisit when
  the client migrates to FCM's `register()`/`onRegistered()` path. The doc id remains the
  ledger/ack identity either way.
- **Acceptance is not delivery.** A drop past acceptance surfaces as a terminal nudge status,
  never as a sender-visible failure; the sender is deliberately never told about suppression
  (AD-16/FR-10).

## #42 E — Tests, build, manifests, security posture, dependencies (complete)

### Files read line-by-line in this pass

All 73 delta files under `app/src/test`, `app/src/androidTest`, and `app/src/debug`
(25 in the final batch), the three manifests (`main`, `debug`, `androidTest`), the backup
and data-extraction XMLs, `accessibility_service_config.xml`, `app/proguard-rules.pro`,
`app/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, the wrapper
properties, and the merged release manifest produced by the build below. The 227 unit-test
files, 36 androidTest files, and 19 debug sources present today are covered by those reads
or by unchanged-since-`6a9a4ff` carry.

Each delta suite was checked for the thing tests actually have to do — fail when the code
breaks. Every one is tied to a dated device incident or ADR and none is tautological:
`RelayHotPathAllocationTest` (PF-6 zero-allocation guard), `TcpSendWindowTest` (ADR #175
3,991,319-byte post-zero-window body), `RuleCounterTest` (500-seed randomized
union-of-tails oracle), `UsageLedgerTest` (#188 toggle keeps the day's total, midnight
exact split, warning reseed), `DomainUsageCiChecksTest` (runs the real CI script against
scratch trees, tokens assembled from fragments so the test file does not trip its own
check), `BlockerAccessibilityServiceConstructionTest` (field-initializer-before-Hilt
crash), `BrowserUrlScannerTest` (11 s toolbar memo window, foreign-tree rejection,
fire-time address-bar eval), `RulesSnapshotTest`, `BrowserPackagesTest` (ADR #189
classification), `DomainBlocklistTest` (A-02 decision-time expiry), `RuleModeTest` (A-01
window≥limit, #18 resume clears lapsed expiry), `StreaksPresentationTest` (ADR #128/#176
floor-at-1), plus the two emulator suites edited under F41-1. Fixtures hold real semantics:
`FakeRuleUsageDao` implements true MAX upsert semantics with fault switches;
`DnsFixtures` writes wire-format DNS by hand rather than using the production encoder.

### Build and gate evidence

- `:app:assembleDebugAndroidTest` → **BUILD SUCCESSFUL** (1m 42s, exit 0), androidTest APK
  packaged from the current tree.
- `:app:processReleaseMainManifest` → exit 0. Merged release manifest:
  **0** occurrences of any debug QA component (DeviceVerification, DomainUsageTest,
  StreaksTestReceiver, DebugBackendReceiver, PairingTestActivity, SetupHarnessActivity,
  VpnSpikeActivity, DebugAppCheckProvider), **0** `usesCleartextTraffic`, **0**
  `android:debuggable`. The debug manifest exists only in the debug source set, so the
  release variant cannot see it.
- `scripts/domainusage-ci-checks.sh` → `domainusage CI checks: OK` (exit 0); the same
  script is exercised negatively by `DomainUsageCiChecksTest`.

### Security posture

- **Exported components, main:** launcher `MainActivity`; `BootReceiver` (BOOT_COMPLETED +
  MY_PACKAGE_REPLACED), `MilestoneBootReceiver` (BOOT_COMPLETED), `LockedBootReceiver`
  (LOCKED_BOOT_COMPLETED, directBootAware), `DesertDawnWidgetReceiver` (APPWIDGET_UPDATE +
  boot), `DnsSinkholeVpnService` (guarded by system-only `BIND_VPN_SERVICE`). The boot and
  widget actions are protected/system broadcasts; the widget receiver can at worst trigger
  a state re-read. Everything else — `ProtectionActionReceiver`,
  `PartnerMessagingService`, `EnforcementForegroundService`, `BlockerActivity`,
  `WidgetActionActivity`, `BlockerAccessibilityService` — is `exported=false`.
- **MainActivity accepts no external deep links** (no VIEW filter), so its export is the
  launcher contract only.
- **Debug harness never ships:** exported debug activities/receivers are driven by host
  `adb shell am` because shell cannot reach non-exported components; `usesCleartextTraffic`
  is debug-only. Both confirmed absent from the merged release manifest.
- **Backup:** `allowBackup=false` plus explicit cloud-backup and device-transfer exclusions
  of the database, sharedprefs, `rules_snapshot.json`, and `datastore/`.
- **Accessibility service:** exactly `typeWindowStateChanged|typeWindowContentChanged`,
  `notificationTimeout=0`, window-content retrieval and interactive-windows flag — no
  broader event surface.
- **Secrets:** no signing configs, no keystores, no credentials tracked. The only PEM
  files are public attestation test fixtures.
- **Network:** release manifest ships no cleartext permission; App Check is enforced on
  Firestore (debug-token path only for emulator tests).

### Dependencies

- Version catalog (`gradle/libs.versions.toml`) has **no dynamic versions** (no `+`, no
  `latest.release`, no version ranges).
- `settings.gradle.kts` uses `FAIL_ON_PROJECT_REPOS`, repositories limited to `google()` +
  `mavenCentral()`; `pluginManagement.google` is content-filtered to `com.android`,
  `com.google`, `androidx`.
- `app/gradle.lockfile` pins **560** artifacts (`lockAllConfigurations()`); wrapper pins
  Gradle 9.4.1.
- `proguard-rules.pro` keeps SQLCipher native/driver classes for the eventual R8 enablement;
  release currently runs with optimization disabled, so the file is inert but correct.

### Observations (recorded, not defects)

- **Remote Config vs ADR #171 wording.** `AccountabilityModule.providePartnerRolloutGate`
  still builds a `FirebaseRemoteConfig` (900 s fetch, default
  `partner_push_registration_enabled=true`, fail-open `runCatching`), while ADR #171's
  present-tense says RC "is retired". ADR line 645 already frames it correctly as still
  functioning until scheduled removal, and the fail-open default means the behaviour is
  always-on offline/unconfigured. Handed to **#44** for docs reconciliation; no code change
  in this pass.
- **`ensureActiveProfile()` at the two rules-UI call sites**
  (`RulesScreen.kt:310`, `RuleEditor.kt:294`) was on the watch list as unguarded. It is
  not: `RulesScreen` wraps the batch in `runCatching` → `presetFailureMessage`, and
  `RuleEditor` wraps save in `runCatching` → `EditorError(notSavedMessage(...))`. The
  concern is falsified; no change.
- **Release signing is unset.** No `signingConfigs` block exists; direct-APK release
  signing is the known pending cutover (one uninstall/reinstall when it lands), not a
  review finding.
- **No `verification-metadata.xml`.** Dependency checksum verification is off; lockfile
  pinning is the current integrity control. Acceptable for a solo direct-APK project;
  enable when a release pipeline exists.

## #44 — Docs audit: every `app/docs` and `app/plans` file vs current code (complete)

**Scope read:** all 18 `app/docs/*.md` and all 21 `app/plans/*.md` files enumerated; every file's
status block and every cross-document structural claim checked against code/current files. The
1.3 MB ADR file and V1's prose were not re-read line-by-line in this pass — that was the
2026-10-17 exhaustive audit's closing evidence (memory `project_docs_memory_exhaustive_audit`),
and the delta since then is ADR #171–#211, all authored and verified in the sessions that
shipped them. What this pass verified mechanically, across every file:

- **Referenced paths:** all 210 `app/src/...` paths extracted from docs/plans exist except the
  two `.../` placeholders and `MilestoneSplashCard.kt` (deleted; see fix 1). All `app/docs/*.md`,
  `scripts/*`, and `functions/*` references resolve; the only misses are explicitly future items
  (`app/docs/myexodus-system-tour.md` = task #46, `scripts/publish-release.sh` = an unimplemented
  OTA-plan step).
- **Versions:** Build & Dependencies matches `gradle/libs.versions.toml` + wrapper + app module
  exactly — AGP 9.2.1, Kotlin 2.2.10, KSP 2.3.11, Compose BOM 2026.08.00, Room 3.0.3, Hilt
  2.60.1, compile/target/min 37/36/26, Gradle 9.4.1. Lockfile entries = **560**, as documented.
- **Schemas:** `AppRulesStore` is version **20**, `AccountabilityDatabase` **14**; V1 and the
  plans' current-state addenda name v20/v14. The v13/v14 comment in `AppRulesStore.kt` describes
  journal/projection introduction versions, not the current version — accurate, left as-is.
- **ADR ledger:** headings #1–#211 present exactly once each — zero gaps, zero duplicates.
- **Plan status headers:** every plan carries one, and every one is honest about what shipped
  vs. what is archival. Added two missing BUILT statuses (fix 2).
- **Remote Config reconciliation (handed from #42):** no shipped doc claims RC is already gone.
  ADR #171 frames it as directed-but-pending; Build & Dependencies says "scheduled for removal";
  the OTA plan's header is an owner direction revision; SOS docs mark every RC reference
  historical. Code still contains exactly the one documented key/gate
  (`partner_push_registration_enabled` → `PartnerRolloutGate`), matching the docs. No edit needed.
- **V1 "Not built" claims spot-verified against code:** #21 quarantine/solo-mode (0 grep hits),
  #33 min-client-version check (0 hits), #28 `recovery_escrow_key` (schema-only). The
  owner-named partner escalation alerts stay correctly recorded as missing (V1 #22, OPD #32(iii)).
- **Process docs** (`engineering-decision-system`, `engineering-patterns-reference`,
  `master-workflow`): no dead Play/flavor claims.

**Fixes applied in this pass:** (1) `MyExodus V1.md` §12 — `MilestoneSplashCard` references
replaced with the shipped 2026-09-29 design (`MilestoneSectionCard`, permanent Home ladder with
loading/empty/error/success states); the ADR and the historical UI plan keep the old name as
record. (2) BUILT status lines added to `myexodus-secure-persistence-implementation-plan.md` and
`habit-streaks-implementation-plan.md`; the other plans already carried one.

## #40 — Memory audit: every memory file vs code/git/docs (complete)

**Scope:** all 72 memory files + `MEMORY.md` examined; 71 were already indexed, one
(`feedback_proof_not_assumptions.md`) was not — index line added. Frontmatter validated on all
72: every file has `name`, `description`, and a `type`; slugs are unique. Findings and fixes:

- **Backtick tokens** (paths, symbol names, test names) cross-checked against the repo: all
  misses adjudicated as expected non-code strings — emulator serials, session/UUID ids, external
  APIs (`PackageManagerInternal`, `linkWithCredential`), external service names
  (`myexodus-53804`, `us-central1`), dumpsys/logcat vocabulary, and historical names kept as
  record. No stale code-symbol claims found.
- **Referenced filenames:** all resolve except explicitly-future items (`docs/partner-sos-runbook.md`
  — recorded in V1 as not written; `MEMORY.md` — the index itself; generated/runtime paths such
  as `shared_prefs/*.xml`, `/tmp/tier_d_stress.sh`; `sonar.sh` — inside the wiped Sonar install,
  described as historical). `b6-soak-rerun.sh` exists at `app/build/tierb-evidence/` as stated.
- **Commit hashes:** every quoted hash that is claimed as a commit (`dea7d89`, `eab177a`,
  `6a9a4ff`) resolves; the only dead hashes are pre-2026-09-16-purge hashes that
  `project_no_git_remote.md` itself declares dead, UUIDs/numbers caught by the pattern, and the
  session ids in frontmatter.
- **Current-state claims spot-verified:** no git remote (`git remote -v` empty); zero product
  flavors; `DOMAIN_USAGE_MODE` = ENFORCE debug / OFF release; schema v20/v14; lockfile 560;
  AVD `MyExodus_API37` present. `project_next_session_queue.md` still matches the task list.
- **Open Product Decisions pointer updated:** vault now holds **37 items (27 open, 10 done)** —
  #36 (sign-in/account model) and #37 (name-the-person copy cases) were added after the previous
  35-item verification; the pointer memory and the index line now say 37 as of 2026-10-22.
- **Device ruling verified (relevant to today):** `device_physical_only.md` carries the owner
  amendment of 2026-10-19 — when the X6532 cannot be found on Wi-Fi after reconnect attempts,
  fall back to the API-37 AVD and state emulator-only evidence honestly. Today's AVD run is
  sanctioned by that amendment; pairing/attestation legs remain impossible there.

No memory contradicted current code or git state. The memory store is coherent for a cold-start
resume (see the Resume pointer at the top of this file).

## #46 — Trainer: guided deep-tour (complete)

`app/docs/myexodus-system-tour.md` written: 14 sections covering the product constraints, the
package→responsibility map, the three enforcement paths and decision precedence, usage
accounting/ledger, domain metering, the partner pillar in layers (identity, event contract,
consent/routing, transport, approvals/unpair, DMS, notifications), SOS, habits/streaks/
milestones/widget/insights, the UI shell and content rules, security posture, verification
recipes (JVM, lint-alone, instrumentation, debug QA suites, matrix sweep, device rules), known
limits (including the unbuilt escalation alerts and the attestation wall), a new-task checklist,
and cold-start resume protocol. Claims spot-verified against code while writing: poll interval
1.5 s, 10/5-minute warnings, widget 30-min period, heartbeat floor 15 min + 0–2 min jitter,
partner-sync WM 15 min CONNECTED, six tabs, channels, matrix shape. One correction made during
that check: the old 6-hour **repair cap** was removed by ADR #103 — the tour now says repair
windows are uncapped and the 6 h constant is clock-tamper detection, not a credit cap.

## #50 — Two-device partner-notification matrix (owner mandate #2)

**Hardware reality (evidence-backed):** the X6532 is unreachable (mdns advertises
`LAN-ADDRESS-REDACTED:5555`; the laptop is on `LAN-SUBNET-REDACTED`, route unreachable; `LAN-ADDRESS-REDACTED` and
`192.0.2.25` time out; a full `nc -z` scan of `LAN-SUBNET-REDACTED:5555` found nothing). The AVD
cannot be a pairing device at all: pairing requires hardware TEE attestation, and the emulator's
software keys cannot pass — the virtual-partner harness needs real hardware keys too (ADR #66
boundary, `pairing_emulator_closed`). Therefore **every real partner-delivery leg is BLOCKED
today**; what follows separates what is proven by tests/code from what needs two phones.

| # | Signal | Emitted by | Consent gate | Delivery / channel | Test evidence (JVM, 0 failures) | Device today |
|---|---|---|---|---|---|---|
| 1 | `streak_updated` | `HabitRepository` → activity journal | per-(pair,habit): `share_streak` ∧ streak flags | activity fan-out → mailbox → `partner_accountability_events_v1` | `MailboxMessageHandlerTest` 25, `PartnerNotificationsTest` 23, `PartnerSyncCoordinatorTest` 40 | BLOCKED (no pair) |
| 2 | `relapse_recorded` | same | `share_streak` ∧ `share_relapses` | notification names streak + days (wire v2) | `PartnerNotificationsTest.relapse_namesTheStreakDaysAndTime` | BLOCKED |
| 3 | `milestone_reached` | `HabitRepository.stampAchievedMilestones` | `share_streak` ∧ `share_milestones` | notification names streak + day | `PartnerNotificationsTest.milestone_namesTheStreakAndDay` | BLOCKED |
| 4 | `coaching_step_updated` | coaching repository | plan/step sharing scope | activity notification | `PartnerNotificationsTest` (activity path) | BLOCKED |
| 5 | `motivational_message` | partner composer | addressed pair + visible streak context | body unlocked, lockscreen generic | `MailboxMessageHandlerTest.motivationalMessage_carriesItsBodyIntoNotify`, `PartnerNotificationsTest.motivationalMessage_*` | BLOCKED |
| 6 | `approval_request` / `approval_response` | approval flow | pair-bound | `approval_requests` (HIGH, always alerts even mid-burst) | `PartnerNotificationsTest` 104/86, `PartnerApprovalInstrumentedTest` | BLOCKED |
| 7 | `deletion_request` (unpair) | unpair flow | pair-bound | shred + "partner ended the partnership" named notification | `BilateralDeletionTest` 21, `PartnerNotificationsTest.partnershipEnded_*` | BLOCKED |
| 8 | `sync_request` | chain repair | pair-bound | repair answer; notification deliberately suppressed | `MailboxMessageHandlerTest.appendedSyncRequest_*` | BLOCKED |
| 9 | plan progress | plan flow | plan consent | contract body routed to the accountability hook, never notified | `MailboxMessageHandlerTest.contractBody_*` | BLOCKED |
| 10 | availability snapshots | `PartnerSyncCoordinator`/heartbeat | `shareAvailability` | contract hook, no notification | `PartnerSyncCoordinatorTest`, `PartnerDmsClientTest` 12 | BLOCKED |
| 11 | DMS availability/ingestion alerts | server `dmsDeadlineWorker` | server-side relationship + consent check | `availability_alerts_v1` | functions DMS suites (28 emulator, 131 unit) recorded in ADR #201 | BLOCKED |
| 12 | check-in reminders | server DMS | consent | `checkin_reminders_v1` (DEFAULT) | functions DMS suites | BLOCKED |
| 13 | SOS nudge | `sendNudge` → queue → FCM | live pair + server kill switch | `partner_nudges_v3` (HIGH), inbox card | `NudgeRepositoryTest` 19, `NudgeInboxRepositoryTest` 12, `PartnerNudgePushTest` 6 | BLOCKED (needs pair) |
| 14 | Partner test alert | Partner tab | none — local self-test | `partner_accountability_events_v1` | `PartnerNotificationsTest.testAlert_*` | **PASS on AVD today**: posted, correct channel, "Partner alerts are working" |
| 15 | Uninstall / permission revocation / rule add-edit-delete / violation / per-rule partner / primary partner | — | — | **NOT IMPLEMENTED** | none | **GAP — planned escalation feature** |

**Multi-partner “is the RIGHT partner notified?”** — the routing contract is per pair:
`provideMailboxMessageHandler`'s notify lambda keys on `pairId`, resolves that enrollment's
partner display name, and `PartnerNotifications` uses per-`(type,pairId)` ids.
`PartnerNotificationsTest.twoPairs_coexist_eachNamedForItsOwnPartner` pins it. A live N-partner
delivery test needs N real paired partners; impossible on today's hardware.

**What the AVD did prove today (single-device scope):** notification permission path, channel
creation/retirement, the local test alert, and the FGS "Protection active" notification coexisting
with the partner channel. Nothing partner-to-partner.

**Local-rig escalation route (feasibility verified in code 2026-10-22, not yet run):** the
"AVD cannot pair" verdict holds only against the LIVE backend. The debug build ships a
deliberate local-suite rig: (a) client — `LocalRigAttestationVerifier` accepts a peer's
software-attested emulator chain only while `DebugBackend.isLocalRig(context)` (a pref set by
`am broadcast -n com.example.myexodus/.accountability.DebugBackendReceiver -a com.example.myexodus.debug.SET_BACKEND --es host <ip>`, applied at process
start; ports auth 9099 / firestore 8080 / functions 5001). **The receiver declares no
intent-filter, so an action-only `am broadcast -a …` resolves to nothing and writes no pref
(silent no-op — corrected 2026-10-31); the `-n` component is mandatory, same as the sibling
verification receivers.** (b) server — `ATTESTATION_STUB=1`
plus the emulator runtime flips `registerPairing`'s verifier to `{ok:true}` (`functions/index.js:243`).
Mailbox delivery is client-side: `MailboxMessageHandler`'s notify lambda posts through
`PartnerNotifications` when a sync pass ingests the mailbox (FCM only wakes the pass), so no
real push transport is needed to observe the partner's tray notification.
**Runbook for #50 on two AVDs** (host load 4.4 on 4 cores — do not boot a second AVD beside the
sweep): 1) `ATTESTATION_STUB=1 /usr/local/bin/node <firebase-tools lib>/bin/firebase.js
emulators:start --only auth,firestore,functions --project myexodus-53804` — **Node 22, not the
nvm Node 24 CLI** (Node 24 discovery times out and the suite serves zero functions; proven
19:00) — and confirm `✔ functions: Loaded functions definitions from source`; 2) boot
`Lean_API34` (1.5 GB) or `Lean_API34_B` (3 GB) headless; 3) install the debug APK on both,
`pm grant` POST_NOTIFICATIONS, set a PIN per AVD (`adb shell locksettings set-pin 1234` when no
credential exists — ADR #112 device-credential signing; both ceremony roles require
`deps.sign`, so a phone without its owner cannot complete pairing); 4) SET_BACKEND on both
(`10.0.2.2`) + force-stop/relaunch; 5) pair through the real Partner-tab ceremony; 6) exercise
signals 1–10/13 and a third-instance multi-partner routing leg, forcing the 15-min sync pass
with `cmd jobscheduler run -f com.example.myexodus <jobId>`; 7) unpair, then `CLEAR_BACKEND` +
`pm clear` before #49's fresh-user onboarding. Record as LOCAL-RIG evidence, never as
live-backend or physical-phone proof.

### Rig permanently repaired — 2026-10-31 (cold-start wiring, proven end-to-end)

**Root cause (device evidence):** every fresh install's first launch runs the one-time
`LegacyCachePurge`, which calls `FirebaseFirestore.getInstance().terminate()`. `terminate()`
deregisters the client; the next `getInstance()` builds a fresh instance with **production**
settings, discarding the emulator wiring `DebugBackend.apply()` installed at process start.
Symptom: the app reached the Partner screens but every pairing-token read/write went to the
live project (App Check 403 / `DEADLINE_EXCEEDED`), so the ceremony never progressed on a rig
that had just been wired correctly — and a process restart re-applied the wiring, which is why
the rig sometimes "worked after relaunch" and sometimes did not.

**Fix (production source untouched in effect):**
- `LegacyCachePurge.backendReapply: ((Context) -> Unit)?` — a `@Volatile` debug substitution
  point (same pattern as `AppCheckInstaller.factoryOverride`); always null in release.
- `DebugAppCheckProvider.onCreate()` (debug source set, runs before `Application.onCreate`)
  sets it to `DebugBackend.reapplyFirestore`.
- The purge worker's `finally` invokes it after `terminate()` settles, success or failure, so
  the replacement client exists before the main thread returns to bootstrap.
- `DebugBackend.reapplyFirestore` re-wires **Firestore only** — Auth/Functions instances are
  never deregistered and their `useEmulator` may be once-only.
- Regression test: `LegacyCachePurgeTest` (2 Robolectric tests — hook runs on a failed purge,
  hook does not run when nothing was purged).

**Proof (LOCAL-RIG, `Lean_API34_B` A + `Lean_API34` B, 2026-10-31):** B was `pm clear`ed and
`debug_backend.xml` restored, then launched cold — logcat showed
`debug backend -> 10.0.2.2 (auth 9099, firestore 8080, functions 5001)` →
`legacy Firestore disk cache purged` → `debug backend firestore re-wired -> 10.0.2.2:8080`;
the same first launch then completed join + SAS + confirm with no restart. A's invite
`2Z50-D2HD-JVTA-1` → token `b8acab08…` (responded 20:05:03Z) → A accepted B's chain
(`local rig: accepting peer chain … 3 cert(s)`) → SAS `50258` matched on both →
`pairings/25tso4WzSRVPf5Yt2hNSv4oEZkWr_5JLIedQHwmXQPFj23xVgF8fYASA2` `status=active`,
uidA/uidB correct (created 20:05:04Z, activated 20:05:47Z; epoch 2 on A's reused install,
B fresh). Both Partner pages named the other device ("Paired with emub" / "Paired with EmuA").

**Two rig traps found the same day:**
1. The action-only `SET_BACKEND` broadcast silently no-ops (receiver has no intent-filter) —
   always pass `-n`. `DebugBackendReceiver`'s KDoc now documents this.
2. Host memory exhaustion (~18 MB free on this 8 GB Mac) killed the Functions emulator
   runtime (`Failed to load function`) and every callable then failed; the client surfaced it
   as `DEADLINE_EXCEEDED` and the offer screen as the misleading "The invitation ran out".
   Freeing memory (`./gradlew --stop`) and confirming `✔ functions: Loaded functions
   definitions from source` restored the rig. Check the log before blaming the app.
   **Open UX finding:** poll timeouts and transport failures reuse the expiry copy; worth a
   distinct "couldn't reach the other phone" message.

### #59/#61 closed + ADR #193 §9 plan-prune fix — 2026-11-01 (LOCAL-RIG)

Rig at the start of this pass: A `emulator-5554` uid `FTJvVlaxSYiDLmBvCB2LYbRBBjMU` (EmuA),
B `emulator-5556` uid `HPTfYybBCZKuiyGf301bsMChUeJG` (EmuB), pair `FTJv…_HPTf…` active. Both
devices on the current debug APK (`install -r`).

**#61 adversarial pairing — all five legs PASSED on the rig** (the existing pair was untouched
throughout; only leg 3 mutates server token state, and it deletes the mutated doc):
1. Invalid-format code → local-parse rejection copy (`partner_ceremony_code_invalid`); no
   server call.
2. Well-formed but nonexistent token → read returns NotFound; UI shows "That code was already
   used" (`partner_ceremony_used_*`), the honest copy for a doc that no longer exists.
3. Expired token: `expiresAt` rewritten to the past via Firestore REST
   (`PATCH …?updateMask.fieldPaths=expiresAt`) → "The invitation ran out"
   (`partner_ceremony_expired_*`); the mutated doc was then deleted (DELETE 200).
4. SAS refusal: joiner stops → both land on the mismatch copy
   (`partner_ceremony_mismatch_*`), the token is cancelled, the existing pair keeps syncing.
5. Re-join with a consumed code: B entered the used invite `M9JE-QV7A-A1VC-7` → "Pairing
   didn't finish — That code was already used / Each invitation works once, and this one was
   already claimed. Ask for a new invitation, then try again." (Try again / Close). No
   identity minted, no state change.

**#59 bilateral unpair → re-pair — PASSED twice** (once before the fix below, once after, on
the same pair id — ids are deterministic from the two uids). Unpair is screen-lock gated (PIN
1234); the initiator waits on "Waiting to confirm your partner's device has also erased its
data" until the responder syncs, then flips to "Your partner's device has also erased its
data." The responder shows "Your partner ended the partnership. Your data for it on this
device has been erased." Re-pair: invite `KMHD-TG2Z-XBNB-8` → join → SAS `27257` on both →
joiner Confirm (PIN) → initiator "They match" (PIN) → both "You're paired". Server doc
`pairings/FTJv…_HPTf…` `status=active`, `pairingEpoch=6`.

**Found-and-fixed: ADR #193 §9 plan pruning.** `sweepActivity` built its orphan set only from
activity projections/links/consent rows, so a plan whose pair was retired — and which left no
other row — could never be seen and survived every unpair. Device evidence: A kept "Deep Link
leg 71 B2" across an unpair→re-pair while B's author copy was gone (B re-provisioned). Fix
(small, in the ADR's own design — prune on the next sweep, never inside `shredPair`):
- `PartnerPlanDao.pairedPairIds()` — distinct non-null `pair_id` values in `partner_plan`.
- `sweepActivity`'s candidate union includes it before `filterNot { enrolled }`.
- Regression test `PartnerSyncCoordinatorTest.sweep_prunesPlansOfAGonePair_keepingLiveAndPrivatePlans`
  (gone pair's plan deleted; the live pair's and a private plan survive). Class 55/55.
Honest evidence split: the JVM test isolates the plan-only candidate source; on the rig the
purge is user-visible (A's Plans went from the residue row to "No plans yet." after an
unpaired-time pass logged `pruning activity state for 1 unpaired pair(s)`), but that pair also
had monitoring rows, so the device run proves the end-to-end purge, not the candidate source
in isolation. After re-pair both Plans pages are empty; emulator `pairing_tokens` holds 0 docs.

**Rig compatibility finding (nudge inbox):** the bundled Firestore emulator cannot match
explicit nulls — `whereEqualTo(field, null)` returns 0 for a document whose field is
explicitly null (verified against firebase-tools 15.9.0), while production matches by
documented semantics. On the rig the inbox therefore received suppressed/acked documents: a
stale reach-out could re-render and the ack batch could include terminal rows the ack rule no
longer admits. Fix: rig builds set `EmulatorCompat.matchesExplicitNulls = false`, the query
omits the two null equalities, and `NudgeInboxRepository` filters `suppressed`/`acked`
client-side (production query and semantics unchanged). Pinned by the terminal-docs tests in
`NudgeInboxRepositoryTest`.

**Rig driving traps reconfirmed:** dump bounds sit ~72 px above screenshot pixels
(status-bar offset) — tap dump bounds (`/tmp/tap_text.sh`), never screenshot pixels;
`screencap` is the only authoritative visual (a dump transiently unbinds the a11y service);
the Partner tab does not reset the subpage `view` (tap the sub-page back arrow); "End
partnership" occurs twice (card heading + button) and the confirm dialog reuses the label —
tap the dialog action by its dump bounds; the BiometricPrompt is FLAG_SECURE (black
screencap) — drive it through the dump: EditText `[152,589][568,738]`, type 1234, Enter.

**Final gates (2026-11-01, this tree):** full JVM **265 classes / 2,413 tests / 0 failures /
0 errors / 0 skipped** (BUILD SUCCESSFUL); `:app:lintDebug` run ALONE → **"No issues found."**;
the new instrumented `RevealPrependedCardTest` ran **OK (1 test)** on `emulator-5554` via
`am instrument` (no uninstall — the epoch-6 pair and app data survived; A's blocker a11y
binding was re-cycled and the Home chip returned to "Protecting").

**Owner-named scenarios, mapped honestly:** "revoke a permission — is the partner notified?" →
no (not built). "Add a partner to a specific block/rule — are they notified?" → no per-rule
assignment exists. "Try to access blocked app/site — is the partner notified?" → **no**: the
partner-visible activity contract is exactly five types (`streak_updated`, `relapse_recorded`,
`milestone_reached`, `coaching_step_updated`, `motivational_message` — `EventPayload.ACTIVITY_TYPES`)
and a block/override attempt emits none of them; there is no per-attempt partner alert (V1 #6
partial). These are the escalation feature's scope, not bugs to patch silently inside this test
pass.

## #49 — Full real-user scenario sweep (owner mandate #2) (complete — see close-out)

Runs on the sanctioned API-37 AVD (`emulator-5554`) **after** the 84-cell matrix, starting from
a wiped install (`pm clear com.example.myexodus`, never uninstall). Every scenario records its
own evidence (logcat line, screenshot path, or on-screen state read back via `uiautomator dump`).
A scenario that cannot run is marked BLOCKED with the reason — never inferred from a unit test
alone. The partner legs are #50's local rig (two AVDs + Firebase emulators); #49 runs the
fresh-user journey up to the point pairing is required, then #50 supplies the paired device and
#49 continues with partner-visible features.

**Journey A — first run**
| ID | Scenario | Steps | Expected |
|---|---|---|---|
| A1 | Fresh install → onboarding | `pm clear`; launch | Wizard step 1 renders; no crash; Back does not skip the gate |
| A2 | Permission grant path | Walk accessibility → usage access → notifications | Each step's system screen opens; returning shows granted; denial shows the honest "protection off" state |
| A3 | Deny all, then grant later | Deny a11y in the wizard; finish | Home shows protection OFF with the exact fix path; Settings → Fix protection works |
| A4 | First app rule (always-on) | Rules → add app → pick Gmail → always-on | Rule listed; Gmail launch blocked (decide → Block); wall Close works (seam now proven) |
| A5 | First window rule | Add a window covering now | Outside window → Allow; inside → Block; day mask off → Allow |
| A6 | First daily limit | Limit 1 min, use past it | Warning at the first threshold; wall at the limit; usage tick evidence (`observe … Block`) |
| A7 | First website rule | Add site by editor + category preset | Browser blocked on matching host; other sites allowed; URL card names the site |
| A8 | Rule edit/delete | Change limit; delete rule | Snapshot bumps; behavior follows; deleted rule stops blocking |
| A9 | Empty states | Fresh profile, empty Rules/Streaks/Recovery/Insights | Each shows its honest empty state + CTA (no fabricated zeros) |

**Journey B — habits, milestones, relapse**
| ID | Scenario | Steps | Expected |
|---|---|---|---|
| B1 | Create habit + streak | Streaks → add habit → check in | Day 1 counts; Home reflects it |
| B2 | Milestone ladder | Seed/advance days to the next rung | Milestone notification (local) fires once; ladder UI updates |
| B3 | Relapse | Relapse → confirm | Streak resets to Day 1; dialog names the streak; Home/Streaks agree |
| B4 | Insights | Open Insights | Window stats reconcile with attempt log + ledger; empty sections honest |
| B5 | Widget | Place widget | Values match Streaks; stale caption honest; tap opens the right screen |

**Journey C — protection surfaces and recovery**
| ID | Scenario | Steps | Expected |
|---|---|---|---|
| C1 | Path A wall | Blocked app foreground | Wall covers, evicts, Close dismisses, app reopen re-blocks with a fresh decision |
| C2 | Path B poll | FGS poll with a11y down (`fgs-down` harness) | Detected → blocker; no false block for allowed apps |
| C3 | Override/Recovery | From wall → Open Recovery → override | Override retires the block for the stated window; log-only action keeps the block |
| C4 | Permission revoked mid-use | Settings → disable accessibility | Protection-off banner; no crash; Re-enable restores blocking |
| C5 | Process death | `am kill` / force-stop then relaunch | FGS re-arms per ADR #152; rules intact; no stale wall |
| C6 | Rotation / split screen | Rotate on a wall; split Gmail + allowed app | Wall follows correctly; no bypass (matrix Part C already covers focus rules) |
| C7 | Quota boundaries | at/over/rollover/day split | Matrix cells + ledger day-split cases; no double-count |

**Journey D — partner (executed with #50's local rig)**
| ID | Scenario | Steps | Expected |
|---|---|---|---|
| D1 | Pair two AVDs | Partner tab ceremony on both | Pair active; both cards show the partner |
| D2 | Activity fan-out | Trigger each of the five activity types | Ballot/mailbox sync; partner tray notification with correct name and channel |
| D3 | Multi-partner routing | Third instance pair | Each pair's notification names its own partner (JVM-pinned; local-rig best effort) |
| D4 | Revocation/unpair | Unpair from one side | Partner sees the ended-partnership notification; data teardown honest |
| D5 | Permission-revocation expectation | Revoke a11y while paired | **No partner alert — not built** (recorded gap, #15) |

**Journey E — resilience**
| ID | Scenario | Steps | Expected |
|---|---|---|---|
| E1 | Airplane mode | Toggle airplane; relaunch app | Local blocking unaffected; sync degrades gracefully |
| E2 | Clock moves | Move system clock ±1 day | Day keys roll with the engine's zone; quota resets honestly |
| E3 | Doze | `dumpsys deviceidle force-idle` | FGS/blocking behavior as documented; no crash |
| E4 | Reboot | `adb reboot`; wait; unlock | Data intact; a11y re-binds; protection resumes |
| E5 | Low-memory | Fill / background storm | No ANR attributable to the product (the earlier ANR traces were dex/GC) |

**Results table (filled during execution — AVD A = `emulator-5554`, fresh `pm clear` 2026-10-22 21:5x)**
| ID | Observed | Evidence | Verdict |
|---|---|---|---|
| A1 | Wizard Step 1 of 5 renders on first launch; Back exits to launcher, relaunch returns to Step 1 (gate not skipped) | screenshots `/tmp/s49_home_after_setup.png` lineage; `topResumedActivity` per step | PASS |
| A2 | All five system screens open and return granted (usage → disclosure → a11y → notifications → overlay); disclosure has no Skip; a11y enable confirmed `enabled_accessibility_services`=BlockerAccessibilityService, `accessibility_enabled=1` | `settings get secure`, per-step dumps + screenshots | PASS (denial paths exercised in A3) |
| A4 | Gmail always-on rule saved (Rules: "Always on · Blocked now"); Gmail launch → wall "Gmail is blocked / Blocked by your MyExodus rules" + eviction; Close → launcher; relaunch → wall again | `/tmp/s49_gmail_rule_saved.png`, `/tmp/s49_gmail_blocked.png`, `/tmp/s49_gmail_blocked_again.png`; logcat `detected com.google.android.gm — launching blocker` 22:18:01.776, `block close pkg=com.google.android.gm evicted=true` 22:18:06.6, fresh wall screenshot after second launch | PASS |
| A9 | Home empty (no habit/no blocks); Rules empty ("No app blocks yet"/"No websites blocked yet" + presets + CTAs); Streaks empty (setup form). **Recovery empty** (AVD B, fresh habit-less state): "No active block — A blocked app starts a session from the block screen", plus the risk-window strip and the Log an urge / Techniques / My plan / History / Need urgent help? spokes. Read through the accessibility tree, not a screenshot: the Recovery tab is deliberately `FLAG_SECURE` (W6/INV-11), so on-device `screencap` fails there by design — that itself confirms the privacy surface. **Insights empty** (after starting a habit on the rig so the spoke is reachable): Times blocked 0 / Overrides 0, chart copy "No blocked attempts or overrides this week.", "Screen time 0m / Today: 0m / No credited screen time this week.", Urge practice section present | `/tmp/s49_home_now.png`, `/tmp/s49_rules_empty.png`, `/tmp/s49_streaks_empty.png`; Recovery `uiautomator dump` texts (screencap rc=1, secure window); `/tmp/avdb_insights_empty.png` (0/0, both empty-chart messages) and its UI dump | PASS |
| B1 | Habit "Smoking" created (2–5 years, Moderate, Day 1, Free since Oct 22, 7/30/90 milestones 6/29/89 away); streak started; Home reflects the habit | `/tmp/s49_habit_filled.png`, `/tmp/s49_streak_started.png`, `/tmp/s49_home_habit.png` (Home visual check pending) | PASS (Home visual verify pending) |
| A5 | Rule 22:00–07:00 (overnight, default, current time 22:2x) → Gmail launch blocked with wall (FGS "launching blocker"); edited to 09:00–10:00 → "Not active now", Gmail WelcomeTour opens, `decide gm -> Allow` ×2; window 22:00–07:00 + Weekends-only (today Thu) → "Not active now", Gmail allowed, `decide gm -> Allow` ×2 | `/tmp/s49_window_rule_saved.png` (row: "10:00 PM–7:00 AM (overnight) · Blocked now"), `/tmp/s49_window_block.png`; `/tmp/s49_window_rule_outside.png` + logcat 22:22:51 Allow ×2; `/tmp/s49_daymask_rule.png` + 22:24:13 Allow ×2 | PASS (all three: in-window Block, out-of-window Allow, day-mask-off Allow) |
| A6 | Gmail limit 1 min: reconcile seeded 2,845,114 ms today (47 min) → immediate "Gmail is done for today" wall on launch, no warning, eviction (`block close evicted=true`); Photos limit 1 min (0 ms today) → level-1 banner "About 1 min of today's time left" at 33,994 ms, level-2 ShowWarning at 54,611 ms, Block at 64,621 ms + wall "Photos is done for today" + eviction | `/tmp/s49_limit_immediate_wall.png`, `/tmp/s49_photos_warn1.png`, `/tmp/s49_photos_block.png`; logcat `MyExodusUsage: observe … ShowWarning(level=1, remainingMs=26006)` 22:30:11.686, `ShowWarning(level=2, remainingMs=5389)` 22:30:31.208, `Block` 22:30:41.214; `MyExodusBlocker: block close pkg=…photos evicted=true` 22:30:42.588 | PASS (level-2 banner delivered per log; level-1 banner visually captured, level-2 image timing missed the banner — noted not a failure) |
| A7 | Website rule example.com saved "Always on · Blocked now" with VPN tunnel up (DnsSinkholeVpnService bound); Chrome → example.com: `nxdomain example.com v4 rule=policy written=true`, `MyExodusAttempts: outcome=BLOCKED rule=… target=example.com`, URL card "example.com is blocked" + "Go to a different site"; wikipedia.org → `url scan … 'wikipedia.org' -> Allow` ×3 and the page fully renders; category preset sheet reachable from the editor with 10 curated social sites, "nothing is blocked until you tap Block" | `/tmp/s49_website_block2.png`, `/tmp/s49_website_card.png`, `/tmp/s49_website_allowed3.png`, `/tmp/s49_preset_social.png`; logcat 22:33:13 `url scan 'example.com' -> Block`, 22:33:15 attempt BLOCKED, 22:33:15 `nxdomain … written=true`, 22:34:17 Allow | PASS (rule, sinkhole, attempt log, URL card, allow-other-sites, category presets) |
| A8 | Edited Gmail rule across modes (always-on → window → limit) with each save taking effect (A5/A6); example.com rule delete → confirm dialog "stops blocking immediately", snapshot `v7: 2 app, 1 url` → `v8: 2 app, 0 url`, then example.com renders with 0 nxdomain lines | logcat `MyExodusRules: Authoritative snapshot v8: 2 app, 0 url rules` 22:37:26.998; `/tmp/s49_deleted_allows2.png`; `grep -c "nxdomain example.com"` = 0 after deletion | PASS |
| B2 | Milestone day 7. Two clock experiments, both informative: (1) an **in-session** forward jump to Oct 30 did **not** advance the streak (monotonic `elapsedRealtime` path — anti-tamper works, logged `REBOOT_WALL_DELTA` only after a boot-count change); (2) force-stop + `boot_count` 999→1001 + clock → Nov 6 lands on the reboot-detected wall path → elapsed 610,214,609 ms (7.06 d), day-7 row achieved+sent, `MILESTONE_NOTIFICATION_POSTED`, notification posted on channel `streak_milestones` ("7 days free from Smoking" / "Your streak hit 7 days…"), in-app dialog "One week free" with ladder 7✓/30/90; Continue → acked | `streak-anomaly.log`: `REBOOT_WALL_DELTA bootCount=1001 … deltaMs=608403008 … ACCEPTED_WALL_DELTA` and `MILESTONE_NOTIFICATION_POSTED milestoneDay=7`; `streak-read` day=7 `achieved=1793959212333 sent=1793959215833 acked=1793959270852`; screenshots `/tmp/s49_b2_home_milestone.png`, `/tmp/s49_b2_notification_shade.png`, `/tmp/s49_b2_after_continue.png`; `dumpsys notification --noredact` extras | PASS (exactly one notification; ack persisted; no day-30/90 rows) |
| B3 | Relapse from the day-7 state: Home → "Log a relapse" → confirm dialog names the streak ("Smoking") and the run it ends ("7 days"); confirm → new streak Day 1, elapsed 0, best run 7 retained, milestone ladder reset ("7-day mark — 6 days to go") | `/tmp/s49_b3_relapse_dialog.png`, `/tmp/s49_b3_after_relapse.png`, `/tmp/s49_b3_home_day1.png`; store row new streak id `1fdb8dfe` elapsed=0; Home and Streaks read the same values | PASS |
| B4 | Insights reconcile under clock tamper: Home (all-time) 2 blocks / 4 times blocked; Insights initially showed 0 because the device clock was 15 days ahead (Nov 6) and the 4 attempts are stamped Oct 22 — outside the 7-day window, the honest semantic. Restoring the clock to Oct 22 makes Insights show **4** with the Thu bar = 4 and overrides 0, matching Home | `/tmp/s49_b4_insights.png`; after restore: Insights "Times blocked 4 / Overrides 0", chart Thu "blocked: 4, overrides: 0"; ledger audit `journal pkg=com.google.android.gm day=2026-11-06 sum=77125ms rows=3` (`src=PATH_B`). Direct `attempt_log` read is impossible by design — `myexodus_rules.db` is SQLCipher (random header confirmed on a pulled copy; key is Keystore-wrapped), so the UI two-point proof is the evidence | PASS (not a data-loss bug; window math verified both ways) |
| B5 | Widget placed from the launcher picker (the picker's own card is the static `previewImage`, so only a placed widget counts as evidence). Real render: "DAY 1 / 6 days to a 7-day mark" — identical to Streaks; tap opens the app on the Streaks tab (it restores the last spoke, Insights — tab-state behavior, not a wrong destination). Stale path: after a synthetic boot-count change the render logged `status=UNVERIFIED days=1 checkedAgo=74973` and the widget showed "Checked 1m ago" in place of the milestone line; a foreground re-verify cleared it (`status=PROTECTED checkedAgo=null`) | `/tmp/s49_b5_home_widget.png`, `/tmp/s49_b5_stale.png`; logcat `MyExodusWidget: widget view: status=UNVERIFIED days=1 checkedAgo=74973 milestone=7` → then `status=PROTECTED days=1 checkedAgo=null milestone=7`; `dumpsys appwidget` widget id=6, provider `DesertDawnWidgetReceiver`, updatePeriodMillis=1800000 | PASS (value parity, honest stale caption, tap target) |
| C1 | Path A wall, repeated across Gmail, Photos and YouTube: blocked app foregrounded → wall covers it with the app named and "Blocked by your MyExodus rules"; attempt row logged BLOCKED with the rule id; Close sends to launcher (evicted, not merely hidden); relaunch raises a fresh wall from a new decision | `/tmp/s49_c1_wall.png`, `/tmp/s49_c1_gmail_after8s.png`, `/tmp/s49_c1_youtube_wall.png`; logcat `detected com.google.android.youtube — launching blocker` 23:28:18.638, `MyExodusAttempts: outcome=BLOCKED rule=a389a9e0-72b7-4602-a1c9-86e6b7e05fcb target=com.google.android.youtube` 23:28:19.115, `block close … evicted=true` on earlier runs; `decide … -> Block` before each wall | PASS |
| C3 | Override path: "Use it now" records the override (attempt OVERRIDE, cooldown-gated) and retires the rule immediately; the rule moves to Rules → Deactivated with "Resume <app>", and resuming re-arms it (proven by the next block). Log-only path: wall → "Open MyExodus Recovery" (starts the seeded 60 s friction session) → "Log an urge" → intensity 4 → "Log it" writes one recovery row and routes home; the block **stayed enforced after the log** — YouTube relaunched 37 s later was still walled (`attempt BLOCKED` 23:28:19.115) — and the rule retired exactly at the 60 s friction deadline (~23:28:20.4, snapshot v16: 3→2 app rules). That deadline is the countdown-expiry unlock path the user started by entering Recovery, not the log: the log call itself touches no rule or recovery state (`RecoveryScreen.logOnly` → `sessions.logOnly` → `onRouteHome`). Timeline: session row stamped 23:27:41.973 → +60 s ≈ 23:28:20.4 → rule out; no other writer in the window | `/tmp/s49_c3_recovery_from_wall.png`, `/tmp/s49_c3_logonly_end.png`; session `664e55c1 technique=none state=COMPLETED entry=BLOCK_SCREEN dur=0`; logcat `MyExodusAttempts … BLOCKED` 23:28:19.115 then `Authoritative snapshot v16: 2 app, 0 url rules` 23:28:20.426 and `decide … -> Allow` at 23:28:26.9; recovery row + snapshot v15 (resumed) → v16 | PASS — log itself is not a bypass; the user-started friction window still expires on schedule, which is the documented second unlock path (ADR #31/#61). Documentation nuance recorded: the earlier Req 10 device proof sampled at 28 s *inside* the window, so it never contradicted this. |
| C2 | Path B poll with accessibility down (same run as C4): `settings get secure enabled_accessibility_services` = null, FGS log `tick 40: usage=true authority=DEGRADED_POLLING_ONLY`; Gmail under quota opened normally (no false block); once over its 1 m limit the poll detected it and raised the wall "Gmail is done for today" with eviction — Path B enforcement intact at polling latency | `/tmp/s49_c4_poll_wall.png` (wall, a11y down); logcat `MyExodusBlockerFgs: poll foreground=com.google.android.gm …`, `authority=DEGRADED_POLLING_ONLY`; Gmail welcome-tour screenshot with no wall at 62,021 ms vs 60,000 ms → wall on relaunch | PASS |
| C4 | Permission revoked mid-use: a11y disabled → Home chip Limited immediately; after the 60 s explainer delay the banner "Protection is limited … Turn it back on" with Re-enable appears; no crash; Path B kept blocking (see C2). The banner's Re-enable opened `Settings$AccessibilitySettingsActivity`; toggling "Use MyExodus blocker" raised the Android full-control dialog; Allow restored the service (settings back to the component, `dumpsys accessibility` bound) — chip returns to **Protecting** and the banner clears; over-quota Gmail re-blocked under Path A (`authority=FULL` ticks, wall re-shown) | `/tmp/s49_c4_limited_banner.png`, `/tmp/s49_c4_now.png` (Protecting, banner gone), `/tmp/s49_c4_gmail_clean.png` (wall after re-enable); logcat `tick 40: authority=FULL`, `block close … evicted=true` + fresh wall; Settings screen dumps | PASS — **trap recorded:** repeated `uiautomator dump` served a stale tree after the re-enable (it still showed the Limited banner while the screenshot showed Protecting); the screenshot is the authoritative read. Do not trust a dump taken without a forcing UI change in this session. |
| C5 | Process death: `am force-stop com.example.myexodus` → pid gone; relaunch → FGS re-arms immediately (`EnforcementForegroundService` `isForeground=true`, `createTime=-81ms` at the relaunch read), rules intact (Photos/Gmail 1 m limits still listed), no stale wall; over-limit Gmail blocked again after relaunch. Platform behavior confirmed separately: force-stop is a user stop and **clears the accessibility service setting** on this API 37 build (component → null/0) — the app correctly degrades to the Limited banner and Path B keeps enforcing | logcat `MyExodusBlockerFgs` ticks; `dumpsys activity services` FGS block; Rules dump; `/tmp/s49_c4_gmail_clean.png`; controlled test: `before: com.example.myexodus/…BlockerAccessibilityService` → `after-force-stop: null / enabled=0` (and again after re-enable via the UI → null) | PASS (FGS re-arm + rules intact); the a11y-setting clear is platform force-stop semantics, surfaced honestly by the app, not a product defect |
| C6 | Rotation on the wall: MainActivity, BlockerActivity and WidgetActionActivity all declare `android:screenOrientation="portrait"` (deliberate product choice, manifest comment) — `settings put system user_rotation 1`, `cmd window user-rotation lock 1` and `emu rotate` all left `mDisplayRotation=ROTATION_0` and the wall stayed up (screenshots), so a landscape hold cannot bypass it. Split-screen focus rules are the matrix's Part C cells C-15–C-19 (all proven 2026-10-21), not re-run here | `/tmp/s49_c6_wall_portrait.png`, `/tmp/s49_c6_wall_landscape2.png`, `/tmp/s49_c6_now.png` (wall intact through the rotation attempts); `dumpsys display` rotation 0; manifest lines 80/175/235 | PASS (portrait-locked by design; no bypass; split-screen covered by the matrix) |
| C7 | **Quota boundaries — exact limit, over limit, rollover, day split.** *At the limit:* JVM pins the equality edge — a 30 min limit goes `29:59 NoAction → 30:00 exactly Block`, and every later observation stays Block (re-arm), so the boundary is `>=`, not `>`. *Over the limit on device:* A6's 1 min Photos limit crossed 33,994 ms (level-1 warning) → 54,611 ms (level-2) → 64,621 ms (Block + wall + eviction); the matrix cells pin the engine's own edges — C-04 (limit 60,000, used 120,000 → Block; read-back `used=161278`) and C-05 (outside the window, spent quota → still Block, the owner's shape), C-06 (day mask off → Allow, on → Block), C-09 (warning thresholds), C-14 (multiple rules, independent counters). *Rollover:* post-ADR-212 the clock round-trip seeds the new day from that day's journal (2,987,783 ms) instead of minting a fresh budget — E2b's `rolledOver=true Block`, and today's steady E5 ticks are `rolledOver=false`. *Day split, no double-count:* `UsageLedgerMapper.splitAtDayBoundary` is pinned at the exact boundaries (an interval ending exactly at midnight belongs wholly to the closing day; one starting exactly at midnight wholly to the new day; a 50-day span conserves every millisecond and never goes negative), and ADR #181 fixed the one real double-count class (a day split re-opening pre-activity time) with journal/platform parity re-proven at 1,064 ms. B4's clock-tamper round trip showed the window math honestly (0 while the device clock sat 15 days ahead, then 4 on the restored day) | `UsageAccountingEngineTest.warningLifecycle_pendingRetriesThenDelivered_thenBlockAtLimit`; `UsageLedgerAdversarialTest.matrix_intervalEndingExactlyAtMidnight_belongsToTheDayItBegan`, `…StartingExactlyAtMidnight_belongsToTheNewDay`, `…fiftyDaySpan_conservesEveryMillisecond_andNeverGoesNegative`; `blocking-scenario-matrix-2026-10-20.md` cells C-04/C-05/C-06/C-09/C-14 (all PROVEN 2026-10-20/21); A6 device log `33,994 → 54,611 → 64,621 Block`; E2b `2987783ms/60000ms Block (rolledOver=true)`; B4 Insights 0→4 after clock restore | PASS |
| E1 | Airplane mode: `cmd connectivity airplane-mode enable` (`airplane_mode_on=1`, status-bar plane icon visible) → over-limit Gmail launched → Path A wall still raised, evicted on Close, and re-blocked on relaunch; FGS ticks continue with `usage=true authority=FULL`; no crash/ANR in the window. Blocking is entirely local, so connectivity loss cannot degrade enforcement; partner sync/heartbeats are the only network-dependent work and were not exercised on this device (no live enrollment) | `/tmp/s49_e1_airplane_block.png` (plane icon + "Gmail is done for today"); logcat `event pkg=com.google.android.gm type=32`, `poll foreground=… carried=…`, `block close … evicted=true` 23:44:45.211, re-block 23:44:54–55, `tick 120: usage=true authority=FULL degradation=null`, process pid 28641 alive, zero FATAL lines | PASS |
| E2 | **Clock ±1 day — BYPASS FOUND (S49-E2-1).** Gmail's 1 m daily limit was spent on Oct 22. Clock → Oct 23: next observation rolled the day and allowed Gmail (correct — new day). Clock → Oct 21: rolled again, allowed (new day). Clock → back to Oct 22: rolled a **third** time and the counter came up **fresh** — `observe com.google.android.gm +10077ms -> 10077ms/60000ms NoAction (rolledOver=true)` — although the usage journal still held the spent Oct 22 day. Two Settings changes defeated a daily limit. Root cause: Decision 20's rollover reset the live tracker to zero instead of seeding the new day from Σ ledger (the invariant `rebuildProjection` already restores). **Fixed** in `UsageAccountingEngine` (all four rollover sites) + ADR #212; the fix was then installed and re-proven on the device (E2b) | Pre-fix: `/tmp/s49_e2_forward_day.png` (launcher, Gmail evicted by the stale wall), `/tmp/s49_e2_forward_gmail2.png` (Gmail open on the fresh day), `/tmp/s49_e2_backward_gmail.png` (Gmail open on the rolled-back day), `/tmp/s49_e2_roundtrip_gmail.png` (Gmail open again on the spent day); logcat `observe … +0ms -> 0ms/60000ms NoAction (rolledOver=true)` 11:46:25.611, `observe … +9522ms -> 9522ms/60000ms NoAction (rolledOver=true)` 11:50:08.311, `observe … +10077ms -> 10077ms/60000ms NoAction (rolledOver=true)` 20:40:41.514 | FAIL → FIXED (ADR #212) |
| E2b | Post-fix re-run of the same round trip on the installed build. Oct 24 (empty journal) → `observe gm +0ms -> 0ms/60000ms NoAction (rolledOver=true)` (correct: a genuinely unused day starts at zero). Clock → Oct 22 (journal holds 2,987,783 ms for the day) → `observe com.google.android.gm +14025ms -> 2987783ms/60000ms Block (rolledOver=true)` and the "Gmail is done for today" wall rendered + evicted Gmail. The seeded number is the day's own journal total, not a fresh budget — the round-trip bypass is closed | `/tmp/s49_e2_fixed_oct24_open.png` (Gmail open on the empty day), `/tmp/s49_e2_fixed_roundtrip_blocked.png` (wall after returning to the spent day); logcat 23:55:08.668 Block, subsequent ticks `rolledOver=false` and still Block; JVM `rollover_anchorMismatch_seedsFromTheDaysJournal_notAFreshBudget` + `rollover_timezoneMove_sameDate_preservesJournaledUsage`, 215 tests across `blocking.usage` + `insights`, 0 failures | PASS |
| E3 | **Doze.** `dumpsys deviceidle force-idle` → `mForceIdle=true mState=IDLE mLightState=OVERRIDE`. FGS stayed `isForeground=true` (pid alive), a11y bound; over-limit Gmail launched under deep idle → engine blocked and the wall re-rendered on top ("Gmail is done for today"); `dumpsys deviceidle` still `mState=IDLE` while the tick reported `authority=FULL` after the a11y rebind. Midnight (Oct 23 00:00) rolled during the idle window; the persistent wall stayed until dismissed (documented contract: a wall is not self-dismissed by a day change; the next decision starts the new day). `unforce` → `mState=ACTIVE`. **Environment caveat (E-AVD-1):** after an APK replacement the enabled a11y service can sit in `Binding services:{}` with `Bound services:{}` empty — the app then honestly reports `DEGRADED_POLLING_ONLY` and Path B still enforces; toggling `enabled_accessibility_services` rebinds it (or just wait for the replacement's process restart). No app crash; rebinding is OS-owned. **Re-check `dumpsys accessibility` bound-state before trusting Path-A-only legs after any `install -r`** | `/tmp/s49_e3_gmail2.png` (wall under forced Doze); logcat `tick … authority=DEGRADED_POLLING_ONLY` → `authority changed — forcing monitoring snapshot + heartbeat` → `tick 60: usage=true authority=FULL degradation=null` (23:59:59) while `mForceIdle=true`; `observe gm +0ms -> 61000ms/60000ms Block (rolledOver=false)` (00:07:46) and `poll foreground=com.google.android.gm carried=com.google.android.gm` (23:58:35); `dumpsys accessibility` before/after the toggle | PASS |
| E3b | **Wall across a rotation — DEFECT FOUND (S49-E3-1), fixed the same session.** Both overlay hosts size the window from the bounds passed at show time and neither re-applied them on a display configuration change; `onConfigurationChanged` logged and did nothing. A daily-limit wall that was up when the display rotated from landscape (2400 px) to portrait stayed 2400 px wide: only the left slice of the card was visible and the Close button sat off-screen — reproduced on the AVD with the wall live. **Fix:** `OverlayController.refreshDisplayBounds()` recomputes each shown card's bounds (wall = display bounds, banner = warning strip) and re-applies them through the existing per-host refresh path; wired into `BlockerAccessibilityService.onConfigurationChanged` and `EnforcementForegroundService.onConfigurationChanged`. Regression: `OverlayControllerTest.displayConfigurationChange_reBoundsAShownWallToTheNewOrientation` (Robolectric display change → dialog attributes match the portrait metrics). Device proof: after the fix the same system rotation logged `config change: newWidthDp=411` → `display change: rebound display=0 content=DailyLimitBlocked` and the portrait screenshot shows the full card with Close — the exact frame that had been cut off before. Related real-world path: `BlockerActivity` (app-block card) is portrait-locked by design (C6), so the display itself will not rotate while it is the top activity; this fix covers the overlay-hosted daily-limit wall and the Path B card, which do live across rotations | Before: `/tmp/s49_e3_wall.png` (portrait screen, landscape-laid-out card, text/Close off-screen). After: `/tmp/s49_rot_gmail.png` (complete portrait card with Close), logcat `display change: rebound display=0 content=DailyLimitBlocked` at 00:11:05.727 (also 00:08:00–00:10:43 rebinds during the emulator's auto-rotate flaps); JVM `OverlayControllerTest` 20/20 | FAIL → FIXED |
| E4 | **Reboot.** `adb reboot` on the AVD, then unlock (secure PIN): durable state came back identical — `usage-read` after unlock returns the same rule (`enabled=true limit=60000 days=1111111 tz=Africa/Nairobi window=307..367`) and the same journal row (`used=62320 anchor=2026-10-23/Africa/Nairobi blocked=true`) as before the reboot; Rules shows Photos + Gmail and no websites. Protection resumed without an app launch: BOOT_COMPLETED was delivered (late, see caveats) and started the FGS (`isForeground=true`); after the guest additionally ANR-killed the process at 00:39:10, `START_STICKY` restarted the FGS in a new pid (6374) still foreground — two independent re-arm paths. Over-limit Gmail then blocked on first launch after unlock: `observe com.google.android.gm +0ms -> 63366ms/60000ms Block`, `+689ms -> 64055ms/60000ms Block`, wall rendered ("Gmail is done for today… Close") with eviction; the wall re-bounded to the current display after resume (`display change: rebound display=0 content=DailyLimitBlocked`). **Caveats (environment, not product):** (1) E-AVD-1 recurred — the enabled a11y service sat unbound after the reboot (`Bound services:{}`) and needed the `enabled_accessibility_services` toggle; the app reported `DEGRADED_POLLING_ONLY` and Path B kept enforcing meanwhile. (2) The booted guest was CPU-starved (pressure ~99.6%, memory pressure 0): SystemUI/launcher/GMS/inputmethod all ANR'd together, the app's own startup ANR'd twice pre-unlock and once post-unlock, and BOOT_COMPLETED sat ~10 min in the broadcast queue (`PENDING OFFLOAD`) before firing — all cleared once the guest settled; no app crash. (3) Semantics note: the Gmail rule's 05:07–06:07 window does **not** gate its daily quota — `BlockDecisionEngine` day-scopes the quota (`activeDaysOfWeek`) and treats the window as a separate schedule block, so blocking at 00:41 on a spent day is the documented contract, not a stray window match | Post-reboot `usage-read` (identical values), Rules dump; logcat `MyExodusBoot: android.intent.action.BOOT_COMPLETED received` 00:36:15, `MyExodusBlockerFgs` ticks with `isForeground=true` (pid 6374 after the restart), `MyExodusUsage: observe … Block` + `display change: rebound` 00:41:12; `/tmp/e4_gmail_block5.png` (full portrait wall); `dumpsys activity processes` ANR records + CPU-pressure readings; `dumpsys activity broadcasts` showed the delayed BootReceiver | PASS (data + two re-arm paths + enforcement); E-AVD-1 and guest starvation are AVD-environment caveats |
| E5 | **Low-memory + background storm.** Three pressure legs on the live app: (1) *background storm* — Photos/Gmail/YouTube/Chrome launched twice in quick succession with HOME between rounds; the FGS kept ticking (`tick 100` → `tick 140`) in the same pid, the a11y deferred transient `WSC`s (`deferred WSC dropped … window=false` while the next app came forward — the documented background-window guard), and the only ANRs were the **system's** (nexuslauncher focus ANR 00:44:55, Photos JobService 00:45:09), whose traces mention `com.example.myexodus` zero times. (2) *CPU storm* — four busy loops for 45 s (load 22.14 on the 1-CPU guest); FGS ticks continued (00:45:50, 00:46:22), pid 6374 alive, no FATAL/ANR. (3) *memory* — 700 MB into the guest tmpfs dropped `MemFree` from 627 MB to **107 MB**; `am send-trim-memory … RUNNING_CRITICAL` was delivered, the app stayed alive and the FGS foreground. Over-limit Gmail launched into that state cold-started slowly: its window did not exist for the first seconds, so the a11y deferred instead of blocking blind (the correct guard), then as soon as the window appeared the accounting engine blocked (`observe com.google.android.gm +0ms -> 64055ms/60000ms Block`), evicted it (`block close … evicted=true`) and rendered the wall, which re-bounded through a rotation. Releasing the fill restored `MemFree` to 937 MB; Close dismissed the wall and a relaunch re-blocked within seconds (`observe … 64232ms/60000ms Block`, fresh wall). The launcher widget also survived the storm with its correct "DAY 1 / 6 days to a 7-day mark" render | `/tmp/e5_wall_lowmem.png` (full "Gmail is done for today" wall at 107 MB free), `/tmp/e5_reblock.png` (wall after Close + relaunch), `/tmp/e5_mem_gmail.png` (widget intact on the launcher mid-storm); logcat `MyExodusBlockerFgs tick 100…220: usage=true authority=FULL`, `MyExodusUsage observe … Block`, `block close … evicted=true`, `display change: rebound display=0 content=DailyLimitBlocked`; `for f in /data/anr/anr_2026-10-23-00-4*; grep -c com.example.myexodus` = 0; zero `FATAL EXCEPTION` / `ANR in com.example.myexodus` lines; `/proc/meminfo` before/after | PASS — no ANR attributable to the product under storm, CPU saturation, or 107 MB free; enforcement held and recovered. Latency caveat: under this pressure a cold app can take ~12 s to show a blockable window (deferred until it exists), which is by design and only reachable at extreme system pressure |

**Journey D — first paired run (2026-10-23 01:3x–02:05, local rig A↔B, pair `4oZfLV6VbOg7TliGgVCHnULHkTx1_GUIjbrrcmtZeot5K2Dbo0kbH8x1i` epoch 2)**

| ID | Observed | Evidence | Verdict |
|---|---|---|---|
| D1 | Re-pair after #50's D4: ceremony completed on both AVDs with fresh anonymous identities (A "Ava", B "ben"); both cards show an active pair; epoch 2 | Partner-tab screens on both; Firestore pair doc status=active | PASS |
| D2 (blocked-app half) | Gmail always-on rule on B → launch → a11y `decide com.google.android.gm -> Block` → wall covers the app ("Gmail is blocked / Blocked by your MyExodus rules") → attempt journaled `outcome=BLOCKED rule=09205794-c206-4787-b128-c258ea35eba6 target=com.google.android.gm` **twice** (01:54:51.3 and 01:57:44.8). Attempt mirroring then stalled: B outbox stayed at 4 SENT because `emitPayload` silently returns `Emit.Stop` inside the cold 900 s signing window (`UserNotAuthenticatedException`). After a **real keyguard PIN unlock** (power off/on → swipe → PIN — `wm dismiss-keyguard` deposits no auth token), the next sync pass drained **7 events** at 02:01:23.977 (outbox 4 → **11 SENT**, B events=11), and A's Firestore mailbox went 4 → **11 docs**. A could not ingest them: A logs `INVALID_REFRESH_TOKEN` / Firestore `UNAUTHENTICATED` because the **Auth emulator lost its in-memory users on a suite restart** (its refresh-token secret rotates too; `accounts:query` = 0 users) while Firestore data persisted. A's received-events stayed 4 PENDING. The A-side feed row + tray notification therefore could not complete on this pairing session and are **re-run on the fresh pair below** | `/tmp/b_block_wall2.png` (wall over Gmail), `/tmp/b_bouncer.png` + `/tmp/b_unlocked.png` (credential gate), logcat `MyExodusAttempts: attempt logged: outcome=BLOCKED …` ×2, `drain: 7 event(s) delivered` 02:01:23.977; B `outbox: SENT|11`, `events=11`; A `events=4`, `enrollments=1`; A logcat `INVALID_REFRESH_TOKEN` / `UNAUTHENTICATED`; emulator `accounts:query` 0 users | **PARTIAL** — B-side journal + delivery PASS; A-side display blocked by rig auth-state loss, not by product code |

**Findings from the D run (recorded for #43; not silently patched):**

- **S49-D2-1 (MEDIUM, UX/operability):** with the signing key inside its 900 s time-bound window but
  un-authenticated, every outbound partner emit is dropped silently — no log line, no UI signal. The
  partner dashboard's "Synced … ago" caption reads only the **inbound** mailbox watermark
  (`PartnerDashboardSource.kt:134`), so a device whose events are stuck shows a plausible caption
  while its own journal is not being delivered. Nothing is lost (the outbox retries on the next
  authenticated pass — proven: 7 events drained once auth landed), but the user has no way to know a
  credential unlock is needed. Candidate fix (owner UI decision): surface "waiting for device unlock"
  when the outbox has pending items older than one sync interval.
- **S49-D2-2 (LOW-MEDIUM, documented behavior gap):** while APP_STOPPED degradation is active
  (`requiresExplicitReEnable`), the FGS scan is deliberately skipped, so app launches are walled by
  the accessibility path but **no attempt row is journaled** — the partner hears nothing about them,
  even after re-enable (nothing is backfilled). URL-host attempts are journaled by the a11y path
  itself and are unaffected. The first Gmail block of this run produced no row for exactly this
  reason until the Home "Re-enable" button cleared the stop state (`re-enable requirement cleared`).
  Evidence for the gate: `EnforcementForegroundService.kt:619` (`usageOk && !reenableRequired`) vs the
  a11y decide path which writes no app-block attempt.
- **Rig trap (for #53):** `uiautomator dump` binds UiAutomation, which transiently unbinds the app's
  accessibility service — a false "Limited" banner and `authority regained` log lines appear around
  every dump. Use `screencap` + logcat for block-path assertions on the AVDs.

**Journey D — second paired run (2026-10-23 02:19–04:10, fresh identities, pair `VIQDm49…_rYmVai…` epoch 2)**

Setup: the rig's Auth-emulator users were wiped again (both devices were dead identities after
the first run's loss — the exact state OPD #30 describes), then both devices were re-paired
through the real ceremony (invite code → SAS `45552` matched → device-credential approval on
both). New uids: A = `rYmVai…`, B = `VIQDm…` (`GET pairings/VIQDm…_rYmVai…` = active, members
both, epoch 2).

| ID | Observed | Evidence | Verdict |
|---|---|---|---|
| D3 (identity death) | With both auth accounts gone, the SDK kept `currentUser` set and every authenticated call failed `INVALID_REFRESH_TOKEN` (B log 03:18:16) — the pre-#213 stall. `PairUidResolver.ensureUid()` probed the cached token, saw the definitive auth rejection (never a network failure), signed out and minted a fresh anonymous uid, and recorded the rotation marker. Both devices rendered the "Can't reach your partner" card from the recorded denial | Device logcat `INVALID_REFRESH_TOKEN`; `pairing_reachability.xml` on both devices holds `denied_at_SCpsvX…` (= the old pair) once the identity rotated; dashboard card on both AVDs | PASS |
| D4 (re-pair after loss) | Ceremony completed on the already-populated devices: new pair active, epoch 2, card gone once the new roster row is selected. The old (dead) enrollment remains on both devices — see S49-D3-1 | Both dashes show the new pair; Firestore pair doc active | PASS |
| D5 (coaching message, A→B) | A enabled Share this streak (+ relapses/milestones) for the new pair → composer gate cleared → "Rig sweep re-pair proud of you" sent. Delivery chain proven end-to-end: A's reconcile linked the row, the drain wrote the mailbox doc, B pulled it (`pulledCount=1`), processed it, posted the notification, and wrote the per-installation ack. B's Home hub shows "Their message to you — Rig sweep re-pair proud of you" | B notification tray: `AvaA sent you a message` / `“Rig sweep re-pair proud of you”` (channel `partner_accountability_events_v1`); B hub dump line; mailbox `VIQDm…/messages/7d60009f…` with sender `rYmVai…`; ack doc `…/acks/fCQvY3FqSKyqBg-GuwNSnp` `ackedAt 2026-10-23T01:00:28Z`; B log `pulledCount=1` 04:06:15 | PASS |
| D6 (consent boundary, B→A) | B still shares nothing with A, so B emitted no state snapshot; A correctly shows "Nothing shared yet". No leak, no phantom event — the absence is consent, not a defect | A hub "Nothing shared yet"; B hub "Nothing shared yet — AvaA can't see your streaks…"; A mailbox (`rYmVai…`) has 0 docs | PASS |

**Findings from the second D run:**

- **S49-D3-1 (MEDIUM, UX/operability): the dead enrollment is never retired.** After the
  identity rotation both devices hold the old pair `SCpsvX…` forever: the roster shows it as a
  second row with the same partner name ("ben" ×2 on A, "AvaA" ×2 on B) and the same "Paired
  Oct 23, 2026" caption, the sync report reports `uidsResolved=false` on every pass, and every
  pass logs a rules `PERMISSION_DENIED` for it. The card only disappears by selecting the new
  row; the stale row stays and its `denied_at_*` flag can only be pruned by an explicit unpair.
  User impact: a permanently failing pair is indistinguishable from a live one in the roster,
  and the "Sync" health line can never read fully green. Candidate fix (owner UI decision):
  the unreachable card offers "Remove this pairing" (local retirement — the server document is
  already inaccessible), and/or re-pairing offers to retire the superseded enrollment.
- **S49-D3-2 (LOW, UX): identical roster captions.** Two pairings with the same partner name
  render identical rows (name + "Paired <date>"), so the user cannot tell which is live before
  tapping. Part of the same fix as D3-1.
- **S49-D3-3 (LOW, log hygiene): already-acked messages re-acked on every wake replay.**
  Re-delivery of an already-processed message correctly hits the create-only ack rule and is
  read as "already done", but it is logged at WARN as `ack retry failed … PERMISSION_DENIED`
  even though the first ack exists (verified in Firestore). Harmless; consider DEBUG level or
  "already acked" wording so a healthy rig does not look like a rules failure.
  **FIXED 2026-10-23:** the DI ack lambda now treats a create-only-rule `PERMISSION_DENIED` as
  "already acked" (the same reading the drainer already used at lines 524-530) and only real
  transport errors reach the handler's WARN. No retry behaviour changed; the 30-day cap still
  bounds cleanup.

**#49 close-out (2026-10-23).** Journeys A, B, C and E are PASS on the AVD with per-scenario
evidence in the results table above (A1–A9, B1–B5, C1–C7, E1–E5); Journey D is PASS through two
paired runs on the local rig, including the negative consent boundary (D6: nothing shared →
nothing received) and the re-pair/delivery chain (D4/D5/D7). Findings closed by this sweep:
**S49-E2-1** (clock round-trip defeated a daily limit — fixed in `UsageAccountingEngine` + ADR
#212, re-proven on device), **S49-E3-1** (wall stayed landscape-wide after rotation — fixed in
`OverlayController.refreshDisplayBounds`, device re-proven), plus **S49-D3-3** above. The VPN
UDP-truncation and screen-off self-heal fixes (ADRs #186/#187) are device-proven in their own
commits; they are not re-labelled as #49 findings here.
**S49-D3-1/D3-2** (dead enrollment / identical roster captions) are a UX decision touching the
shred/ack protocol — escalated to OPD #40 rather than changed silently. Adversarial pairing on
real devices, real-FCM delivery and the pair-consuming bilateral unpair are PHYSICAL legs with
no path on this rig (hardware attestation wall re-confirmed against the X6532 today); they are
written out as legs 1–6 of `app/docs/blocked-two-device-runbook.md` and remain BLOCKED, never
inferred from a JVM test.

**Cloud Monitoring alert review (owner request, 2026-10-22 night)**

- **Firing now:** 40 `Cloud Functions error rate > 5% (15m)` incidents (each listed twice — the policy has two conditions, so one incident sends two notifications), 17 of them on 2026-10-22; 6 `dispatch sweep not running` (2026-10-16/17, before the R-03 fix); 1 `registerPairing 429` (2026-10-15, test load). The `relay gave up` policy is disabled.
- **Culprit metrics:** `submitHeartbeat` 53 errored executions and `readPartnerStatus` 17 in 24 h; `dmsDeadlineWorker` 1, `setAvailabilityConsent` 1.
- **Root cause:** callable logs show `verifications {app: INVALID, auth: VALID}`, `Failed to validate AppCheck token … Decoding App Check token failed`, HTTP 401, `userAgent okhttp/4.12.0`, remote IP equal to this host's public IP. The paired **physical phone's debug build** has no registered App Check debug token (the console lists only four old emulator tokens; the fresh AVD generated `6da36980…`, exchange fails 403 "App attestation failed"), so Firebase's local interceptor substitutes a placeholder token and every callable is rejected before reaching our handler. The client is correct: `PartnerSyncCoordinator.syncOnce` early-returns before `checkIn()` when there are no enrollments (file line 154), so the unpaired AVD cannot be the caller; the AVD's own debug token is unregistered but it makes no live callables.
- **Impact:** the PA heartbeat/availability path is silently degraded on the debug phone (auth still valid; no crash). Release builds use Play Integrity, so this is a debug-build operational gap, not a shipped-code defect.
- **Fix:** register the phone's debug token when it is back on adb (read it from `DebugAppCheckProvider` logcat or set one explicitly), or stop background PA sync on a debug build with no token; add the release SHA-1 when release signing starts. Alert tuning (dedupe the policy's two conditions) is optional noise reduction, not the root fix.
- **Final re-check (2026-10-23, task #52):** 59 incidents listed, **57 CLOSED, 2 OPEN** —
  both are the two conditions of the same policy, opened together at 16:03:29Z on 2026-10-23.
  The error metrics behind them: **16 errors total in the 15:55–16:05Z window**, all on the same
  two callables as the earlier diagnosis — `readPartnerStatus` 12, `submitHeartbeat` 4 — and
  **zero errors after 16:05Z**. The two callables are the App Check-facing path; on the physical
  X6532 the stored App Check token now shows `provider=play_integrity` with a fresh
  `receivedAt` (16:16:55Z), and the failing window predates it. No new product defect: this is
  the known debug-build attestation operational gap, and the R-03 pinned `ok` series is what
  closes these incidents on the next successful observation (or the autoClose+24 h path).
  The duplicate incident per event is the policy's two conditions — noise reduction only, not
  a second fault ([gcp-api-recipe] alert semantics).

## #60 — Plan fidelity: recent features incl. partner notifications (complete)

**Mandate (owner, 2026-10-23).** "After we enter the code, the other device doesn't advance
its page… do a plan fidelity check for all the features that we've built recently, including
the notifying partners of certain issues like milestones, revocations, updates in coaching
plans, deletion of coaching plans… everything." The check is code-first: each item is traced
from its emitter through the journal/consent gate, the pair transport, the mailbox dispatch
and the notification surface, then matched to the plan/ADR that specified it and to the
test/device evidence that proves it. Deletion of coaching plans does not exist in the model
(`PlanWire` has exactly `plan_published` and `plan_progress`; the owner acknowledged this),
so it is recorded as not applicable rather than invented.

| # | Feature | Plan / ADR requirement | Code path | Evidence | Verdict |
|---|---|---|---|---|---|
| 1 | Other device advances after code entry | ADR #215: poll outlives the creator's clock by 5 min; the server's `Denied` is the authority; every wait names whose phone owes the next tap | `PairingFlow.awaitPartner` + `PairingCeremonyScreen` + `PartnerScreen` EndedCard | `PairingAdversarialTest` row 11 mutation-proven; `PairingFlowTest` 32/0; `PairingFailureCopyTest` 6/0; two-AVD device run (creator 223 s fast, joiner accepted at T0+660 s, both advanced to the same SAS 51399 and "You're paired") | PASS — fixed under #59 |
| 2 | Milestone notification | §4.6 / ADR #134: streak name, day count, when; generic lockscreen (FR-13) | `HabitRepository.kt:777` → `PartnerActivityJournal` (consent gate) → mailbox → `PartnerNotifications.notifyPartnerEvent` rich branch | `HabitRepositoryTest` journal row; `PartnerActivityJournalTest`; `PartnerNotificationsTest.milestone…`; rig 2026-10-23: day-7 seed → chain seq 3/4 → receiver "Days free 7 days, Milestones met 7" | PASS |
| 3 | Relapse notification | ADR #134 copy contract | `HabitRepository.kt:847` → journal → mailbox → rich branch | `PartnerNotificationsTest` relapse v2 + wire-v1 degradation; rig: chain seq 2, receiver "Relapses logged 1" | PASS |
| 4 | Streak update notification | §4.6 aggregate share | `HabitRepository.kt:484/741` → journal → mailbox → generic named copy | `PartnerSyncCoordinatorTest` streak case; rig: chain seq 1, receiver "Days free 1 day · Last shared 27m ago" | PASS |
| 5 | Coaching step update | INV-13: count-only projection; the step text never leaves the author's device | `RecoveryPlanRepository.kt:107/122/132` → journal (gated on `shareRecovery`) → mailbox → generic copy; partner summary renders "Coaching items N" | `RecoveryPlanRepositoryTest` emitter; `PartnerActivityJournalTest` consent rows; rig: real if-then item → "Coaching items 1" | PASS — generic copy is the design, not a gap |
| 6 | Partner plan published / edited | ADR #193 §6: alerts with the plan title, `onlyAlertOnce = false` so an edit re-alerts; `plan_progress` filtered out of the tray | `PlanWire.TYPE_PLAN_PUBLISHED` → `AccountabilityModule` notify lambda → `PartnerNotifications` plan branch | `PartnerPlanReceiverTest` 9; `PartnerPlanRepositoryTest` 7; `PlanWireCodecTest` 6; **new** `PartnerNotificationsTest.planPublished_namesThePlanTitle_andReAlertsOnEdit` (title/body, re-alert flag, generic public copy); two-device `plan_progress` delivery via the D7 rig leg (2026-10-23 01:15:56Z, "1 of 2 done") | PASS |
| 7 | Plan progress | ADR #193 §3/§4: honored only at the author's matching revision; silent convergence signal | `PartnerPlanReceiver` applies at the held revision; `AccountabilityModule` filters it out of the tray | `PartnerPlanReceiverTest.progress…` rows | PASS (silent by design) |
| 8 | Plan deletion | Not implemented — no deletion type exists | — | `PlanPayload.kt` defines only published/progress; ADR #193 honest limits | N/A (acknowledged) |
| 9 | Partnership ended (unpair) | ADR #114 / deletion composition: capture the name before the shred, shred, then notify "ended when / erased" | `AccountabilityModule.onDeletionRequest` → `PartnerNotifications.notifyPartnershipEnded` | `PartnerNotificationsTest` ended-copy test; rig D4: bilateral erasure with both devices' ended states | PASS |
| 10 | SOS nudge | SOS §8.17/FR-20: `partner_nudge` renders immediately from FCM, no Firestore read | `PartnerMessagingService` → `notifyPartnerNudge` | `PartnerNotificationsTest` nudge copy, tap extras, denied-permission no-op; `PartnerMessagingService` invalid-id refusal | PASS |
| 11 | Approval request/decision | Step 16: own channel, own tag, approval not an event | `notifyApprovalRequest/Decision` | `PartnerNotificationsTest` 25 incl. cross-pair hash-collision test; ADR #217 | PASS |
| 12 | Permission revocation / uninstall / rule-change alerts | Not part of the shipped partner-accountability contract; the escalation surface is the separately-noted next feature (`project_partner_accountability_escalation.md`, #56-#adjacent) | Monitoring-state events update the partner card snapshot; no tray alert | §8.4 / D5 rig run (2026-10-22): revocation left the event count unchanged, confirming no alert path | NOT BUILT — scoped as a future feature, not silently missing |
| 13 | Notification identity/collisions | ADR #217: (tag, id) for entries, data URI for tap identity, channels audited distinct | `PartnerNotifications`/`NotificationTapTarget` | `PartnerNotificationsTest` collision pair; `StreakNotificationsTest`; `ProtectionNotificationsTest` | PASS |

**Two-phone status (honest).** Production pairing with an emulator is impossible by design —
`AttestationVerifier.kt:37` rejects `SecurityLevel.SOFTWARE` and the server pins Google
hardware roots. Re-attempted today against the physical X6532: the phone rejected the AVD with
"The other phone's security identity didn't pass verification." (expected; not an app defect).
All two-device evidence above therefore comes from the sanctioned local rig (two AVDs +
Firebase emulators, `ATTESTATION_STUB=1`, `PartnerTestKeys`) or the X6532 in single-device
mode; a production-attestation two-phone leg remains blocked on a second physical phone (#53
runbook, #36).

**Docs updated:** ADR #193 status/honest limits now carry the D7 rig evidence instead of
"two-device delivery remains unverified".

**Fresh run (2026-10-23):** the focused JVM set covering this matrix is green — 118 tests, 0
failures (`PartnerNotificationsTest` 26 incl. the new plan-publish test, `PartnerPlanReceiverTest`
9, `PartnerPlanRepositoryTest` 15, `PlanWireCodecTest` 6, `PartnerActivityJournalTest` 15,
`RecoveryPlanRepositoryTest` 13, `HabitRepositoryTest` 34; `:app:testDebugUnitTest`, exit 0).

## #43 — Double check: full verification pass + honest report (complete)

**Commit:** `912af3c` — the tree carried the #54/#55/#59/#60/#61 fixes, the S49-D3-3 ack fix,
the plan-publish notification test, the ceremony header fix, the #47/#49 ledger close-outs and
`app/docs/blocked-two-device-runbook.md` (12 files, +379/−29).

**Gates, run in order and each alone:**
- Full JVM suite — **227 classes / 2,046 tests / 0 failures / 0 errors / 0 skipped**, exit 0
  (`:app:testDebugUnitTest`, 7 m 22 s, after every code edit).
- Android lint — **No issues found** (`:app:lintDebug`, 8 m 27 s, run alone).
- `:app:assembleDebug` — BUILD SUCCESSFUL.

**Device install (X6532, wireless adb `LAN-ADDRESS-REDACTED:5555`):** `install -r` only, never
uninstall or `pm clear`. APK md5 `e1ee7350c7e40fd7699cfb4605838662`;
`lastUpdateTime=2026-10-23 19:49:59`, `firstInstallTime=2026-09-28 23:18:55` (install identity
preserved). Cold start: Home rendered with data intact (streak "New AC" Day 1, free since
Oct 22; milestones; "Blocks hidden for privacy"). No FATAL lines in logcat.
- **Expected XOS side effect, restored in the same chain:** the update/force-stop dropped the
  accessibility binding (`enabled_accessibility_services=null`, chip "Limited", FGS polling
  still up). Re-set the service + `accessibility_enabled=1`; `dumpsys accessibility` shows
  "MyExodus blocker" bound and the Home chip reads **Protecting**. This is the known XOS
  behavior already in the device-testing memory, not a product defect — but the phone must not
  be left in "Limited".

**Honest residue:** two-phone runtime legs (real FCM, adversarial pairing on real devices,
bilateral unpair) are BLOCKED on a second physical phone and live in the runbook, not in this
pass; S49-D3-1/2 await the owner's UX call (OPD #40); the debug-build App Check token gap on
the phone is operational (documented under #52), and release uses Play Integrity.

## #47 F — Stress + adversarial runtime (complete)

**Scope (review map #37):** cold start, process death, store failure, quota boundaries, huge
rule sets, rapid input, airplane mode, reboot, clock moves, Doze — AVD first, the X6532 where
reachable. This pass did not restart the 2026-10-15 campaign; it re-validated each reviewed
surface against the campaign's evidence, ran the new adversarial legs the sweep exposed, and
recorded what remains hardware-blocked instead of inferring it.

### Evidence map

| Surface | Adversarial question | Evidence | Verdict |
|---|---|---|---|
| Blocking decision engine | Does every rule combination decide the same through both entry points? | `BlockDecisionEngineDomainStressTest` — 243,000-combination cross-product, one private `evaluateRule`; both Block and Allow present | PASS |
| Rule volume / UI scale | 2,000 apps + 2,000 sites: recall, probes, ANR | `RuleIndexVolumeStressTest` 6/6 (200k probes 889 ms); X6532 4,004-rule leg 0 ANRs, cold open 4.0 s flat (ADR #155) | PASS |
| Usage accounting | Clock ±1 day, month/leap/year boundaries, rollover at volume, 4-thread storms | S49-E2 found a real bypass (rollover seeded zero instead of the ledger) → **fixed ADR #212 + re-proven on device (E2b)**; `UsageLedgerBoundaryVolumeStressTest` 8, `RuleUsageDaoVolumeStressTest` 4, `UsageAccountingEngineTest` | PASS after fix |
| Enforcement lifecycle | Kill loops, force-stop, screen-off, airplane, Doze, reboot, permission revoke mid-op | Tier B B1–B6 (0 product defects), Tier C single-device kill loops / force-stop cycles / a11y rebind races (ADR #150, #152), device E-21 reboot leg | PASS |
| Store failure | Disk full at 55/16/2 MB free | **Tier B B7 found B-10** (Firestore SDK + WorkManager crash-loop) → **fixed ADR #154, re-verified at ~2 MB free**; own Room/SQLCipher paths absorbed SQLITE_FULL | PASS after fix |
| Partner transport | Crash between commit and local SENT; foreign/duplicate mailbox docs; ack replay | A4 crash-window (ADR #147), A8 watermark adversarial (ADR #148), rig D3 identity death → re-pair (ADR #125/#213), ack create-rule replay (S49-D3-3, fixed below) | PASS |
| Pairing under adverse clocks | Joiner accepts after the creator's wait expires | ADR #215: server `Denied` is authority, 5-minute skew allowance; `PairingAdversarialTest` row 11 mutation-proven; two-AVD run accepted at T0+660 s against a 240 s cap | PASS |
| Backend quotas | Rate/size/app-check enforcement under load | Tier D: 23 invocations, uniform 401s, 10/uid/hour 429 exactly, size guard precedes the limiter, 0 unhandled | PASS |
| Notifications | Collisions across tag/id/tap/channel | ADR #217; `PartnerNotificationsTest` collision pair; `StreakNotificationsTest`; `ProtectionNotificationsTest` | PASS |
| UI state transitions | Rotation with a live wall; rapid app switching; restored state | S49-E3 rotation found a real defect (wall off-screen after rotate) → **fixed + device re-proven**; restored-UI stale label after AVD snapshot investigated to no code defect (#55) | PASS after fix |

### New adversarial legs run for this task

- **Blocking matrix** — closed 2026-10-21 at **84/84 plus C-15–C-19** (real override, AVD
  reboot, day mask, magnitude) per ADRs #190/#191; no open case.
- **#55 rig** — consent boundary proven in the negative direction (D6: B shares nothing → A
  receives nothing, no phantom event) alongside the eight positive categories; the real Chrome
  launch hit `BlockerActivity` twice, and the day-7 milestone arrived through the production
  pipeline (seed supplies only the row).
- **Pairing stall (ADR #215)** — the owner-reported "other device doesn't advance" reproduced
  as a clock-skew race, fixed, and re-run on the rig to acceptance at T0+660 s.
- **Already-acked replay (S49-D3-3)** — fixed in the DI ack lambda: a create-only-rule
  `PERMISSION_DENIED` now means "already acked" (the drainer's existing reading), so a healthy
  rig no longer logs a rules failure on every wake replay.
- **Ceremony header after success (owner report 2026-10-23)** — the back-row label fell
  through to "Invite a partner" while the terminal card said "You're paired"; the label now
  tracks `CeremonyStep.Paired` (`partner_ceremony_back_title_paired`).

### Routed to the blocked runbook (never simulated)

Two-phone runtime legs remain physical-hardware work: kills mid-sync/mid-approval/mid-pairing,
approvals/SOS flips between two real devices, real FCM delivery, adversarial pairing on real
devices (wrong/expired code, SAS refusal, re-join), and the pair-consuming bilateral unpair
last. They are written out in `app/docs/blocked-two-device-runbook.md` (legs 1–6) and stay
BLOCKED until a second physical phone with its owner exists; LOCAL-RIG evidence is never
upgraded by association.

### Known limits (observed, not defects)

- AVD JVM jank can produce an unreproducible single probe miss (B-06) and stale uiautomator
  trees; every such case was re-run before being read.
- The physical phone's debug build still lacks a registered App Check token (operational gap
  documented under the #52 section); release builds use Play Integrity.
- The dead-enrollment roster issue (S49-D3-1/2) is a UX decision, escalated to OPD #40 — it
  touches the shred/ack protocol and was not changed silently.

## Appendix — delta file inventory (path list)

```
# app/src/main/java (129)
app/src/main/java/com/example/myexodus/MainActivity.kt
app/src/main/java/com/example/myexodus/MyExodusApp.kt
app/src/main/java/com/example/myexodus/ReceiverRegistration.kt
app/src/main/java/com/example/myexodus/accountability/BilateralDeletion.kt
app/src/main/java/com/example/myexodus/accountability/EventCreator.kt
app/src/main/java/com/example/myexodus/accountability/activity/PartnerActivityJournal.kt
app/src/main/java/com/example/myexodus/accountability/monitoring/MonitoringSnapshotReceiver.kt
app/src/main/java/com/example/myexodus/accountability/monitoring/MonitoringSnapshotRecorder.kt
app/src/main/java/com/example/myexodus/accountability/monitoring/PartnerDmsClient.kt
app/src/main/java/com/example/myexodus/accountability/monitoring/PartnerHeartbeatClient.kt
app/src/main/java/com/example/myexodus/accountability/plan/PartnerPlanReceiver.kt
app/src/main/java/com/example/myexodus/accountability/plan/PartnerPlanRepository.kt
app/src/main/java/com/example/myexodus/accountability/serialization/AccountabilityEventWire.kt
app/src/main/java/com/example/myexodus/accountability/serialization/EventPayload.kt
app/src/main/java/com/example/myexodus/accountability/serialization/PlanPayload.kt
app/src/main/java/com/example/myexodus/accountability/state/AccountabilityDatabase.kt
app/src/main/java/com/example/myexodus/accountability/state/ChainEvent.kt
app/src/main/java/com/example/myexodus/accountability/state/ChainEventDao.kt
app/src/main/java/com/example/myexodus/accountability/state/ChainStateStore.kt
app/src/main/java/com/example/myexodus/accountability/state/Outbox.kt
app/src/main/java/com/example/myexodus/accountability/state/OutboxDao.kt
app/src/main/java/com/example/myexodus/accountability/state/UnpairState.kt
app/src/main/java/com/example/myexodus/accountability/state/UnpairStateDao.kt
app/src/main/java/com/example/myexodus/accountability/sync/MailboxEventProcessor.kt
app/src/main/java/com/example/myexodus/accountability/sync/MailboxMessageHandler.kt
app/src/main/java/com/example/myexodus/accountability/sync/MailboxOutboxDrainer.kt
app/src/main/java/com/example/myexodus/accountability/sync/OutboxBackoff.kt
app/src/main/java/com/example/myexodus/accountability/sync/PairingExchange.kt
app/src/main/java/com/example/myexodus/accountability/sync/PartnerAlertHealth.kt
app/src/main/java/com/example/myexodus/accountability/sync/PartnerMessagingService.kt
app/src/main/java/com/example/myexodus/accountability/sync/PartnerNotifications.kt
app/src/main/java/com/example/myexodus/accountability/sync/PartnerPushParser.kt
app/src/main/java/com/example/myexodus/accountability/sync/PartnerSyncCoordinator.kt
app/src/main/java/com/example/myexodus/accountability/sync/PartnerSyncSignals.kt
app/src/main/java/com/example/myexodus/accountability/sync/PartnerSyncWorker.kt
app/src/main/java/com/example/myexodus/blocking/BlockOverlayView.kt
app/src/main/java/com/example/myexodus/blocking/BlockerAccessibilityService.kt
app/src/main/java/com/example/myexodus/blocking/BrowserUrlScanner.kt
app/src/main/java/com/example/myexodus/blocking/EmergencyExclusionManager.kt
app/src/main/java/com/example/myexodus/blocking/EnforcementForegroundService.kt
app/src/main/java/com/example/myexodus/blocking/OverlayController.kt
app/src/main/java/com/example/myexodus/blocking/RoleHolders.kt
app/src/main/java/com/example/myexodus/blocking/RulesSnapshot.kt
app/src/main/java/com/example/myexodus/blocking/usage/PlatformUsageReader.kt
app/src/main/java/com/example/myexodus/blocking/usage/UsageAccountingEngine.kt
app/src/main/java/com/example/myexodus/blocking/usage/UsageDiagnosticsRecorder.kt
app/src/main/java/com/example/myexodus/blocking/usage/UsageLedgerMapper.kt
app/src/main/java/com/example/myexodus/data/db/ActivityLogStore.kt
app/src/main/java/com/example/myexodus/data/db/AppRulesStore.kt
app/src/main/java/com/example/myexodus/data/db/AppRulesStoreFactory.kt
app/src/main/java/com/example/myexodus/data/db/dao/AttemptLogDao.kt
app/src/main/java/com/example/myexodus/data/db/dao/HabitProgressDao.kt
app/src/main/java/com/example/myexodus/data/db/dao/MilestoneEventDao.kt
app/src/main/java/com/example/myexodus/data/db/dao/PairSharingDao.kt
app/src/main/java/com/example/myexodus/data/db/dao/PartnerEventJournalDao.kt
app/src/main/java/com/example/myexodus/data/db/dao/PartnerLivenessDao.kt
app/src/main/java/com/example/myexodus/data/db/dao/PartnerMonitoringSnapshotDao.kt
app/src/main/java/com/example/myexodus/data/db/dao/PartnerPlanDao.kt
app/src/main/java/com/example/myexodus/data/db/dao/RecoveryStreakDao.kt
app/src/main/java/com/example/myexodus/data/db/dao/UsageLedgerDao.kt
app/src/main/java/com/example/myexodus/data/db/entity/DeviceLiveness.kt
app/src/main/java/com/example/myexodus/data/db/entity/PairProfileSharing.kt
app/src/main/java/com/example/myexodus/data/db/entity/PartnerEventJournal.kt
app/src/main/java/com/example/myexodus/data/db/entity/PartnerMonitoringCheckin.kt
app/src/main/java/com/example/myexodus/data/db/entity/PartnerMonitoringSnapshot.kt
app/src/main/java/com/example/myexodus/data/db/entity/PartnerPlan.kt
app/src/main/java/com/example/myexodus/data/db/entity/PartnerPlanTask.kt
app/src/main/java/com/example/myexodus/di/AccountabilityModule.kt
app/src/main/java/com/example/myexodus/domainusage/AndroidDomainUsageHost.kt
app/src/main/java/com/example/myexodus/domainusage/AttributionResolver.kt
app/src/main/java/com/example/myexodus/domainusage/Clocks.kt
app/src/main/java/com/example/myexodus/domainusage/DnsShaper.kt
app/src/main/java/com/example/myexodus/domainusage/DomainRule.kt
app/src/main/java/com/example/myexodus/domainusage/DomainRuleProvider.kt
app/src/main/java/com/example/myexodus/domainusage/DomainUsageEngine.kt
app/src/main/java/com/example/myexodus/domainusage/DomainUsageHost.kt
app/src/main/java/com/example/myexodus/domainusage/EngineCounters.kt
app/src/main/java/com/example/myexodus/domainusage/FlowOwner.kt
app/src/main/java/com/example/myexodus/domainusage/InspectingTcpTap.kt
app/src/main/java/com/example/myexodus/domainusage/RuleCounter.kt
app/src/main/java/com/example/myexodus/domainusage/SystemWallClock.kt
app/src/main/java/com/example/myexodus/domainusage/UsageLedger.kt
app/src/main/java/com/example/myexodus/domainusage/UsageTickLoop.kt
app/src/main/java/com/example/myexodus/insights/InsightsRepository.kt
app/src/main/java/com/example/myexodus/insights/InsightsWindow.kt
app/src/main/java/com/example/myexodus/settings/ProtectionChecks.kt
app/src/main/java/com/example/myexodus/streaks/HabitRepository.kt
app/src/main/java/com/example/myexodus/streaks/HabitState.kt
app/src/main/java/com/example/myexodus/streaks/MilestoneScheduler.kt
app/src/main/java/com/example/myexodus/ui/AppShell.kt
app/src/main/java/com/example/myexodus/ui/NavTile.kt
app/src/main/java/com/example/myexodus/ui/NotchedNavBar.kt
app/src/main/java/com/example/myexodus/ui/StatusPill.kt
app/src/main/java/com/example/myexodus/ui/home/BlockingReadiness.kt
app/src/main/java/com/example/myexodus/ui/home/HomeScreen.kt
app/src/main/java/com/example/myexodus/ui/insights/InsightsCharts.kt
app/src/main/java/com/example/myexodus/ui/insights/InsightsPresentation.kt
app/src/main/java/com/example/myexodus/ui/insights/InsightsScreen.kt
app/src/main/java/com/example/myexodus/ui/insights/InsightsViewModel.kt
app/src/main/java/com/example/myexodus/ui/partner/PairingCeremonyScreen.kt
app/src/main/java/com/example/myexodus/ui/partner/PartnerDashboardSource.kt
app/src/main/java/com/example/myexodus/ui/partner/PartnerPlanEditor.kt
app/src/main/java/com/example/myexodus/ui/partner/PartnerPlansScreen.kt
app/src/main/java/com/example/myexodus/ui/partner/PartnerScreen.kt
app/src/main/java/com/example/myexodus/ui/partner/SharingScreen.kt
app/src/main/java/com/example/myexodus/ui/recovery/RecoveryScreen.kt
app/src/main/java/com/example/myexodus/ui/rules/RuleEditor.kt
app/src/main/java/com/example/myexodus/ui/rules/RulesScreen.kt
app/src/main/java/com/example/myexodus/ui/rules/WebsitePresetSheet.kt
app/src/main/java/com/example/myexodus/ui/rules/WebsitePresets.kt
app/src/main/java/com/example/myexodus/ui/settings/DomainUsageDebugSection.kt
app/src/main/java/com/example/myexodus/ui/settings/SettingsScreen.kt
app/src/main/java/com/example/myexodus/ui/streaks/MilestoneReachedDialog.kt
app/src/main/java/com/example/myexodus/ui/streaks/MilestoneSplashCard.kt
app/src/main/java/com/example/myexodus/ui/streaks/StreakCards.kt
app/src/main/java/com/example/myexodus/ui/streaks/StreaksPresentation.kt
app/src/main/java/com/example/myexodus/ui/streaks/StreaksScreen.kt
app/src/main/java/com/example/myexodus/ui/streaks/StreaksViewModel.kt
app/src/main/java/com/example/myexodus/ui/streaks/WithdrawalTimelineCard.kt
app/src/main/java/com/example/myexodus/vpn/BrowserPackages.kt
app/src/main/java/com/example/myexodus/vpn/DnsSinkholeVpnService.kt
app/src/main/java/com/example/myexodus/vpn/DomainBlocklist.kt
app/src/main/java/com/example/myexodus/vpn/FlowOwnerClassifier.kt
app/src/main/java/com/example/myexodus/vpn/TcpSendWindow.kt
app/src/main/java/com/example/myexodus/vpn/VpnPacket.kt
app/src/main/java/com/example/myexodus/widget/DesertDawnGlanceTheme.kt
app/src/main/java/com/example/myexodus/widget/DesertDawnWidget.kt
app/src/main/java/com/example/myexodus/widget/WidgetActionActivity.kt
app/src/main/java/com/example/myexodus/widget/WidgetContent.kt

# app/src/main/res (3)
app/src/main/res/drawable-nodpi/widget_preview.png
app/src/main/res/values/strings.xml
app/src/main/res/values/strings_recovery.xml

# app/src/main/AndroidManifest.xml (1)
app/src/main/AndroidManifest.xml

# app/schemas (4)
app/schemas/com.example.myexodus.data.db.AppRulesStore/17.json
app/schemas/com.example.myexodus.data.db.AppRulesStore/18.json
app/schemas/com.example.myexodus.data.db.AppRulesStore/19.json
app/schemas/com.example.myexodus.data.db.AppRulesStore/20.json

# app/src/debug (6)
app/src/debug/java/com/example/myexodus/accountability/PairingTestActivity.kt
app/src/debug/java/com/example/myexodus/accountability/PairingTestEntryPoint.kt
app/src/debug/java/com/example/myexodus/accountability/PartnerTestIdentity.kt
app/src/debug/java/com/example/myexodus/accountability/RoundTripTestEntryPoint.kt
app/src/debug/java/com/example/myexodus/blocking/DeviceVerificationActivity.kt
app/src/debug/java/com/example/myexodus/domainusage/DomainUsageTestReceiver.kt

# app/src/test (61)
app/src/test/java/com/example/myexodus/accountability/BilateralDeletionTest.kt
app/src/test/java/com/example/myexodus/accountability/PairingHarnessIsolationTest.kt
app/src/test/java/com/example/myexodus/accountability/activity/PartnerActivityJournalTest.kt
app/src/test/java/com/example/myexodus/accountability/enrollment/EnrollmentDaoTest.kt
app/src/test/java/com/example/myexodus/accountability/enrollment/PairingTestHarness.kt
app/src/test/java/com/example/myexodus/accountability/monitoring/MonitoringSnapshotReceiverTest.kt
app/src/test/java/com/example/myexodus/accountability/monitoring/MonitoringSnapshotRecorderTest.kt
app/src/test/java/com/example/myexodus/accountability/monitoring/PartnerDmsClientTest.kt
app/src/test/java/com/example/myexodus/accountability/monitoring/PartnerHeartbeatClientTest.kt
app/src/test/java/com/example/myexodus/accountability/plan/PartnerPlanReceiverTest.kt
app/src/test/java/com/example/myexodus/accountability/plan/PartnerPlanRepositoryTest.kt
app/src/test/java/com/example/myexodus/accountability/serialization/AccountabilityEventContractTest.kt
app/src/test/java/com/example/myexodus/accountability/serialization/PlanWireCodecTest.kt
app/src/test/java/com/example/myexodus/accountability/state/AccountabilityDatabaseMigrationTest.kt
app/src/test/java/com/example/myexodus/accountability/state/ChainStateStoreTest.kt
app/src/test/java/com/example/myexodus/accountability/state/SessionKeyManagerTest.kt
app/src/test/java/com/example/myexodus/accountability/sync/MailboxEventProcessorTest.kt
app/src/test/java/com/example/myexodus/accountability/sync/MailboxMessageHandlerTest.kt
app/src/test/java/com/example/myexodus/accountability/sync/MailboxOutboxDrainerCrashWindowTest.kt
app/src/test/java/com/example/myexodus/accountability/sync/MailboxOutboxDrainerTest.kt
app/src/test/java/com/example/myexodus/accountability/sync/PartnerAlertHealthTest.kt
app/src/test/java/com/example/myexodus/accountability/sync/PartnerNotificationsTest.kt
app/src/test/java/com/example/myexodus/accountability/sync/PartnerPushParserTest.kt
app/src/test/java/com/example/myexodus/accountability/sync/PartnerSyncCoordinatorTest.kt
app/src/test/java/com/example/myexodus/blocking/BlockerAccessibilityServiceConstructionTest.kt
app/src/test/java/com/example/myexodus/blocking/BrowserUrlScannerTest.kt
app/src/test/java/com/example/myexodus/blocking/LivenessSnapshotTest.kt
app/src/test/java/com/example/myexodus/blocking/RulesSnapshotTest.kt
app/src/test/java/com/example/myexodus/blocking/usage/UsageAccountingEngineTest.kt
app/src/test/java/com/example/myexodus/data/db/AppRulesStoreMigrationTest.kt
app/src/test/java/com/example/myexodus/data/db/PartnerMonitoringSnapshotDaoTest.kt
app/src/test/java/com/example/myexodus/data/db/dao/PartnerEventJournalDaoTest.kt
app/src/test/java/com/example/myexodus/domainusage/AttributionResolverTest.kt
app/src/test/java/com/example/myexodus/domainusage/DnsShaperTest.kt
app/src/test/java/com/example/myexodus/domainusage/DomainRuleProviderTest.kt
app/src/test/java/com/example/myexodus/domainusage/DomainUsageCiChecksTest.kt
app/src/test/java/com/example/myexodus/domainusage/DomainUsageEngineHooksTest.kt
app/src/test/java/com/example/myexodus/domainusage/DomainUsageLifecycleTest.kt
app/src/test/java/com/example/myexodus/domainusage/DomainUsageTestFixtures.kt
app/src/test/java/com/example/myexodus/domainusage/FlowTapTest.kt
app/src/test/java/com/example/myexodus/domainusage/InspectingTcpTapTest.kt
app/src/test/java/com/example/myexodus/domainusage/RelayHotPathAllocationTest.kt
app/src/test/java/com/example/myexodus/domainusage/RuleCounterTest.kt
app/src/test/java/com/example/myexodus/domainusage/UsageLedgerTest.kt
app/src/test/java/com/example/myexodus/domainusage/UsageTickLoopTest.kt
app/src/test/java/com/example/myexodus/insights/InsightsRepositoryTest.kt
app/src/test/java/com/example/myexodus/insights/InsightsWindowTest.kt
app/src/test/java/com/example/myexodus/streaks/HabitRepositoryTest.kt
app/src/test/java/com/example/myexodus/ui/NamedSwitchTest.kt
app/src/test/java/com/example/myexodus/ui/home/DegradationCopyTest.kt
app/src/test/java/com/example/myexodus/ui/insights/InsightsPresentationTest.kt
app/src/test/java/com/example/myexodus/ui/partner/PartnerDashboardSourceTest.kt
app/src/test/java/com/example/myexodus/ui/rules/RuleModeTest.kt
app/src/test/java/com/example/myexodus/ui/rules/WebsitePresetsTest.kt
app/src/test/java/com/example/myexodus/ui/streaks/StreakPluralCopyTest.kt
app/src/test/java/com/example/myexodus/ui/streaks/StreaksPresentationTest.kt
app/src/test/java/com/example/myexodus/vpn/BrowserPackagesTest.kt
app/src/test/java/com/example/myexodus/vpn/DnsMessagesTest.kt
app/src/test/java/com/example/myexodus/vpn/DomainBlocklistTest.kt
app/src/test/java/com/example/myexodus/vpn/TcpSendWindowTest.kt
app/src/test/java/com/example/myexodus/widget/WidgetContentTest.kt

# app/src/androidTest (6)
app/src/androidTest/java/com/example/myexodus/accountability/monitoring/MonitoringSnapshotInstrumentedTest.kt
app/src/androidTest/java/com/example/myexodus/accountability/monitoring/PartnerDmsInstrumentedTest.kt
app/src/androidTest/java/com/example/myexodus/accountability/sync/PartnerHeartbeatInstrumentedTest.kt
app/src/androidTest/java/com/example/myexodus/accountability/sync/PartnerSyncScenariosInstrumentedTest.kt
app/src/androidTest/java/com/example/myexodus/data/db/AppRulesStoreMigrationInstrumentedTest.kt
app/src/androidTest/java/com/example/myexodus/ui/streaks/MilestoneReachedDialogTest.kt

# functions (24)
functions/dispatch-logic.js
functions/dispatch-record.js
functions/dms-logic.js
functions/dms-processor.js
functions/dms.js
functions/emulator-test/dms-soak.js
functions/emulator-test/dms.emulator.js
functions/emulator-test/firestore-rules.emulator.js
functions/emulator-test/heartbeat.emulator.js
functions/fcm-transport.js
functions/heartbeat-logic.js
functions/heartbeat.js
functions/index-build-guard.js
functions/index.js
functions/package-lock.json
functions/package.json
functions/relay-logic.js
functions/sweep.js
functions/test/dispatch-logic.test.js
functions/test/dms-logic.test.js
functions/test/heartbeat-logic.test.js
functions/test/index-build-guard.test.js
functions/test/push-payload.test.js
functions/test/relay-blindness.test.js

# scripts (3)
scripts/blocking-matrix-sweep.sh
scripts/domainusage-ci-checks.sh
scripts/enhanced-mode-device-test.sh

# firestore.rules (1)
firestore.rules

# firestore.indexes.json (1)
firestore.indexes.json

# app/docs (16)
app/docs/MyExodus Architecture Decisions.md
app/docs/MyExodus Build & Dependencies.md
app/docs/MyExodus Monitoring.md
app/docs/MyExodus V1.md
app/docs/blocking-scenario-matrix-2026-10-20.md
app/docs/confidence-sweep-2026-10-18.md
app/docs/domainusage-baseline-check.md
app/docs/domainusage-implementation-log.md
app/docs/domainusage-test-report.md
app/docs/engineering-decision-system.md
app/docs/engineering-patterns-reference.md
app/docs/master-workflow.md
app/docs/partner-accountability-compatibility-report-2026-10-22.md
app/docs/partner-sos-mapping.md
app/docs/project-audit-2026-10-18.md
app/docs/project-review-2026-10-20.md

# app/plans (15)
app/plans/app-update-ota-implementation-plan.md
app/plans/daily-usage-limit-enforcement-implementation-plan-v2.md
app/plans/daily-usage-limit-enforcement-implementation-plan-v3.md
app/plans/desert-dawn-widget-phase6-full-spec.md
app/plans/habit-streaks-implementation-plan.md
app/plans/myexodus-domain-usage-metering-implementation-plan (1).md
app/plans/myexodus-enhanced-mode.implementation-plan.md
app/plans/myexodus-recovery-resource-ui-implementation-plan-v2.md
app/plans/myexodus-secure-persistence-implementation-plan.md
app/plans/partner sos implenetation plan.md
app/plans/partner-accountability-event-delivery-implementation-plan.md
app/plans/partner-pairing-implementation-plan.md
app/plans/partner-pillar-implementation-plan-v2.md
app/plans/partner-visible-activity-layer-implementation-plan.md
app/plans/ui-ux-improvement-session.md
```

### Working tree beyond the committed delta

Present before this pass began (ADR #211): `ui/AppShell.kt`, `ui/home/HomeScreen.kt`,
`data/db/entity/RecoverySession.kt`, `res/values/strings.xml`.

Written by this review pass (findings F38-1…F38-6, F39-1…F39-4, F45-1…F45-6 and their
regressions): `blocking/BlockDecisionEngine.kt`, `blocking/BlockerAccessibilityService.kt`,
`blocking/BrowserUrlScanner.kt`, `blocking/EnforcementForegroundService.kt`,
`blocking/OverlayController.kt`, `blocking/UrlPattern.kt`,
`blocking/usage/UsageAccountingEngine.kt`, `data/db/ActivityLogStore.kt`,
`data/db/AppRulesStoreFactory.kt`, `data/db/PlaintextToEncryptedMigration.kt`,
`data/db/RulesStoreEncryption.kt`, `data/db/entity/DailyUsageTracker.kt`,
`streaks/HabitRepository.kt`, `ui/AppShell.kt`, `ui/partner/PartnerScreen.kt`,
`ui/rules/RulesScreen.kt`, `ui/streaks/StreaksViewModel.kt`, `vpn/DnsSinkholeVpnService.kt`,
`widget/WidgetActionViewModel.kt`; `test/.../BlockingPipelineTest.kt`,
`test/.../HabitRepositoryTest.kt`, `test/.../PlaintextConversionFileOpsTest.kt`,
`test/.../UrlPatternTest.kt`, `test/.../UsageAccountingEngineTest.kt`; finding F41-1's
`functions/emulator-test/partner-sos-push.emulator.js` and
`functions/emulator-test/partner-sos-scheduler.emulator.js`;
`docs/MyExodus Architecture Decisions.md`, `docs/MyExodus V1.md`, and this ledger.
