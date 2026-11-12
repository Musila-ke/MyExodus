# Partner Notification Scenario Matrix

Owner directive 2026-11-09: with the Kelvin↔John pair live, exercise every partner-visible
notification the rules, batches, attempts, streaks and messages can produce, plus the partner
dashboard's accuracy and its empty-share copy. **Source answers below were read from the code
first; the device run only confirms or contradicts them.** No scenario may be marked PASSED on a
guess.

**Reset 2026-11-09 (owner order, fresh start):** the Kelvin↔John pair was ended on both phones
(X6532C erased locally and confirmed the partner device's erasure; X6532 accepted the remote end
and now shows "No partner yet"), and every rule was deleted on both phones (X6532: the Adult
batch of 32 website rules plus the standalone Adobe Scan app rule; X6532C: the Social_time batch
of 10 plus the standalone Adobe Scan and AI Gallery app rules). Both Rules pages read "No app
blocks yet" / "No websites blocked yet". The results below are kept as the pre-ADR-#288 record;
the matrix is re-run from an empty pair on the ADR #288 build.

## Source map (what actually sends what)

| Fact | Source |
|---|---|
| Rule-lifecycle rows are journaled **only when the rule/batch is assigned to a partner** (`assigned_pair_id`) | `blocking/RulesRepository.kt:226-269` (`journalRows`), `:282-306` (`batchJournalRows`) |
| A batch journals **one batch-level row** naming the batch + item count, never one row per item | `RulesRepository.kt:282-306` |
| Verbs emitted: CREATED, ASSIGNED, UNASSIGNED, UPDATED, DISABLED, DELETED, EXPIRED (natural expiry is EXPIRED, not DISABLED) | `RulesRepository.kt:254-267`, `:91-127`, `:195-218` |
| Rows route only to the assigned pair and are sent as E2EE RULE_CONTROL | `sync/PartnerSyncCoordinator.kt:482-497`, `:752-799` |
| Receiving phone stores the row then posts a notification **only if a row was stored** (redelivery cannot double-post) | `di/AccountabilityModule.kt:1108-1151` |
| Rule notification copy names the rule/batch: single rule by label; batch by batch name + item count; member rule also names its parent batch | `sync/PartnerNotifications.kt:300-384`, strings `partner_rule_event_*` / plurals `partner_rule_event_*_batched` |
| Blocked/override attempts share only when the pair's **Check-ins** consent is on; each attempt is one `TYPE_ACTIVITY` | `PartnerSyncCoordinator.kt:394-410`, `blocking/AttemptRecorder.kt:21-55` |
| Attempt text carries `blocked:<target>|<batch>`; the notification copy is "«app» was blocked in the «batch» batch." — batch name included | `PartnerSyncCoordinator.kt:869-876`, `PartnerNotifications.kt:256-293`, string `partner_activity_blocked_in_batch` |
| Relapse/milestone/streak rows require the pair's per-habit streak consent; relapse additionally needs Relapses on, milestone additionally needs Milestones on | `accountability/activity/PartnerActivityJournal.kt:161-188` |
| STREAK_UPDATED is emitted at day-crossings (one per clean day) and when sharing is first enabled for a habit | `streaks/HabitRepository.kt:482-487`, `:739-745` |
| MILESTONE_REACHED fires once per newly stamped milestone day | `HabitRepository.kt:762-788` |
| RELAPSE_RECORDED fires with the ended streak's day count | `HabitRepository.kt:852-856` |
| **Habit creation emits nothing** (a new habit is shared with nobody until chosen) | `HabitRepository.kt:235-309` |
| **Habit deletion emits nothing**; consent rows are deleted, already-delivered projections are not recalled | `HabitRepository.kt:589-629` |
| **Un-sharing a streak emits nothing** | `HabitRepository.kt:457-491` |
| Motivational message requires at least one habit shared with that pair (composer explains + routes) | `PartnerActivityJournal.kt:74-89`, `:183-184`, `PartnerScreen.kt:1516-1528` |
| Every activity payload except approvals and plan-progress posts a notification — **including the daily STREAK_UPDATED snapshot**, whose copy is the generic "New activity from X / Open MyExodus to see what changed." | `AccountabilityModule.kt:1056-1070`, `PartnerNotifications.kt:197-250` |
| The partner **feed** deliberately excludes daily streak snapshots (noise by design) — so the generic daily notification points at an activity list that will not show a new row | `ui/partner/PartnerPresentation.kt:152-170` |
| "How they're doing" renders days free, relapses, milestones, coaching count, last-event age, pinned message; each received activity event applies its snapshot wholesale | `PartnerScreen.kt:1445-1508`, `PartnerActivityReceiver.kt:27-52`, `PartnerPresentation.kt:184-196` |
| Empty-share copy exists per page: activity "Nothing has been shared with you yet. This page fills in when your partner turns on sharing."; rules "No rule updates yet…"; check-ins "Nothing reported yet…"; plans "No plans yet…" | strings `partner_activity_none_shared`, `partner_rules_empty_*`, `partner_checkins_empty_*`, `partner_plans_empty_*` |
| Disable-approval flow has **no production caller** — turning a watched rule off is immediate, not an approval request | `accountability/approval/ApprovalFlow.kt:58`; callers only in tests |
| Monitoring snapshots state held grants only on Step 3 contract v3+; unverifiable readings degrade to v2 and claim nothing | `serialization/AccountabilityEventWire.kt` (`SCHEMA_VERSION = 3`, `caps`), `monitoring/MonitoringSnapshotRecorder.kt`, `data/db/entity/PartnerEventJournal.kt` (`forEvent`) |
| A lost grant alerts only when a live rule in the recipient's own feed needs it; one notification per transition, recovery silent (ADR #299) | `monitoring/MonitoringSnapshotReceiver.kt` (`lostCapabilities`), `monitoring/RuleCapabilityGap.kt` (`rulesNeeding`), `di/AccountabilityModule.kt` (`onAccountabilityEvent`), `sync/PartnerNotifications.kt` (`notifyPermissionLost`) |

## Matrix

Columns: **#**, **scenario**, **expected (source)**, **device result**.

### A. Rules and batches (assign to partner first)

| # | Scenario | Expected from source | Result |
|---|---|---|---|
| S1 | Create a single block, assigned to partner | Partner notification "You were added as accountability partner on «label»." (CREATED) + Rules page row | **PASSED 2026-11-10** (release v9, warm window): standalone AI Gallery block created on .106 with John Maingi picked in the editor ("Watched by John Maingi" before Save). Sender `Authoritative snapshot v97 → v98 (8 app rules)`, PartnerSyncWorker 16:05:06.187, `drain: 1 event(s) delivered` 16:05:17.972; .107 tray same per-pair tag, "Rule update from Kelvin Musila" / "You were added as accountability partner on AI Gallery." when=1794315923696. Rules page row re-checked after delivery: Apps section shows "AI Gallery — Always on · Blocked now · Watched by John Maingi" (the 2026-11-09 PARTIAL is now closed) |
| S2 | Assign partner to an existing unassigned block (edit) | UNASSIGNED to nobody, ASSIGNED to partner → "You were added as accountability partner on «label»." | PASSED 2026-11-10 (warm window): assigned John Maingi to the existing unassigned Adobe Scan rule; the receiver's Rules & blocks lists "Adobe Scan — Rule assigned to you · 3m ago" and the notification "You were added as accountability partner on Adobe Scan" was delivered (mailbox 22:21:20.249Z). The same act auto-enabled blocked-app check-ins for the pair (ADR #295) |
| S3 | Modify a watched block (change app/expiry while still assigned) | UPDATED → "«label» was changed." | **PASSED 2026-11-10.** Edited Adobe Scan "Every day → Weekdays" on .106 while the §8.1 window was cold: no notification then (drains 0, `KEY_USER_NOT_AUTHENTICATED`), row journaled — exactly the deferral ADR #294 prescribes. After the owner unlocked the sender, delivery ran silently on the foreground pass: receiver tray shows `partner:event:P9XVq…` "Rule update from Kelvin Musila" / "Adobe Scan was changed." (when=1794265379639). Copy matches the expected cell verbatim. Defer-then-deliver proven end to end |
| S4 | Turn a watched block off (toggle) | DISABLED → "«label» was turned off." (no approval request) | **PASSED 2026-11-10** (release v9, warm window): toggled the shared AI Gallery rule off on .106; the row moves to the Deactivated section by design (`RulesScreen.kt:875-877`). Sender `drain: 1 event(s) delivered` 16:06:22.834; .107 "Rule update from Kelvin Musila" / "AI Gallery was turned off." when=1794315988684. No approval request anywhere — DISABLED is told, not asked |
| S5 | Delete a watched block | DELETED → "«label» was deleted." | **PASSED 2026-11-10** (release v9, warm window): deleted the paused AI Gallery rule via Deactivated → Remove all (confirm dialog said "1 rule will be deleted permanently"). Sender `drain: 1 event(s) delivered` 16:08:03.906; .107 "AI Gallery was deleted." when=1794316088620. Exactly one event for the one rule |
| S6 | Remove the partner from a watched block | UNASSIGNED → "You were removed from «label»." | **PASSED 2026-11-10** (release v9, warm window): edited the shared Google Assistant rule and picked "No partner" on .106 (header flipped to "Not shared with a partner" before Save). Sender `drain: 1 event(s) delivered` 16:09:52.564; .107 "You were removed from Google Assistant." when=1794316198865. Sender Apps row now reads "Not shared with a partner". Earlier drain 0 at 16:09:21 was a pass whose journal snapshot preceded the commit (worker latency), not a lost row |
| S7 | Block naturally expires (set duration runs out) | EXPIRED → "«label» ended. Its set duration ran out." | |
| S8 | Create a batch with partner assigned | One batch row → "You were added as accountability partner on the «batch» batch (N items)." | **PASSED 2026-11-10** (warm window): "S9batch" created with John Maingi picked at creation; one batch-level journal row; sender `drain: 1 event(s) delivered` 15:49:19.598; .107 tray "Rule update from Kelvin Musila" / "You were added as accountability partner on the S9batch batch (1 item)." when=1794314964943 — one row naming the batch + item count, not one row per member |
| S9 | Modify / turn off / delete a watched batch; remove partner from it | One batch-level row per action naming the batch, never N item rows | **PASSED 2026-11-10** (warm window), all verb legs on device, each one batch-level row carrying the batch name + item count on .107: pause → "The S9batch batch was turned off (1 item)." when=1794315196459 (sender `drain: 1` 15:53:11.712); resume → "The S9batch batch was changed (1 item)." when=1794315348596 (drain 1 15:55:42.730); delete → "The S9batch batch was deleted (1 item)." when=1794315404752 (drain 1 15:56:39.953); remove partner on a second batch → "You were removed from the S9leave batch (1 item)." when=1794315597477 (drain 1 15:59:47.042). Deleting the now-unshared S9leave journaled nothing (no route pair), so no stray notification — correct. Earlier `drain: 0` readings mid-legs were observation windows that closed before WorkManager's pass (worker latency 8-24 s), not lost rows |
| S10 | Blocked attempt on a standalone watched block | "«partner» tried to open a blocked app / «app» was blocked." — only while Check-ins is on for that pair, and the rule assignment turns Check-ins on automatically (ADR #295); switching it off silences the alerts and tells the partner so | PASSED 2026-11-10: blocked Adobe Scan on .106 → exactly ONE `partner:event` notification on .107 (the 30 s dedupe collapsed the launch-grace retry) and one "Adobe Scan — Blocked · 1m ago" row on the receiver's Rules & blocks (mailbox 22:22:37.969Z). The copy path ("tried to open a blocked app / «app» was blocked") was device-proven earlier on the 2026-10-31 build (ADR #257) |
| S11 | Blocked attempt on a rule inside a batch | "«partner» tried to open a blocked app / «app» was blocked in the «batch» batch." — the batch name must appear (owner ruling) | **PASSED 2026-11-10** (release v9, warm window): launched Adobe Scan on .106 — a member of the shared "Morning" batch (rule `03d172ac`, "Watched by John Maingi"). Attempt logged 16:12:06.391, `Authoritative snapshot v101`, sender `drain: 1 event(s) delivered` 16:12:21.827. .107 `partner:event` tag: title "Kelvin Musila tried to open a blocked app" / text "Adobe Scan was blocked in the Morning batch." when=1794316347979 — the batch name appears exactly as ruled. Receiver feed shows exactly ONE row ("Adobe Scan — Blocked · Morning batch · just now") despite a second `drain: 1` line at 16:12:43.669 — no duplicate row or alert. **Staged re-run PASSED 2026-11-12 (release 1.1.20, v22):** Calculator blocked under the shared "Battery batch" — sender `drain: claimed=1, accepted=1` 15:42:41.271; .107 tray title "Kelvin Musila tried to open a blocked app" / text "Calculator was blocked in the Battery batch batch." `when=1794487368003`; .107 Partner → Rules & blocks lists "Calculator · Blocked · Battery batch batch · 2m ago" (31 updates) and the activity feed shows the same row. The v21 staged build produced no notification and no row; v22's `PartnerAttemptAlert` path closes that gap |
| S12 | Override used instead of a block | "«partner» used an override / «app» was opened with an override (in the «batch» batch)." | **BLOCKED — not producible in v9 (source-verified 2026-11-10).** The receiver copy and wire mapping exist (`partner_activity_override_*`; `PartnerSyncCoordinator.kt:903` maps `AttemptLog.OUTCOME_OVERRIDE` to the `override:` verb), but nothing in production writes that outcome: `AttemptRecorder` hardcodes `OUTCOME_BLOCKED`, the only other `AttemptLog` constructor is the backup codec, and the immediate override was removed from the Recovery hub in `fbf3ad4` (owner ruling 2026-11-08, task #55) — the friction timer remains a settings editor only. The leg cannot be exercised until the owner rules whether a friction-completion opening counts as a shared override or the surface stays permanently gone |
| S31 | Cold §8.1 window: make a partner-visible rule change, then ignore/cancel the device-authentication prompt (owner-requested leg, added 2026-11-10) | ADR #296: the act prompts once; a cancelled/ignored prompt leaves the rows journaled and nothing is delivered; the deferred rows deliver on the first later authenticated (warm) pass with the normal copy | **PASSED 2026-11-10.** Google Assistant paused then resumed on .106 while the §8.1 window was cold; the sheet prompted and was ignored/cancelled (rows stayed journaled, no delivery). Owner locked and unlocked the phone (keystore2 "Successfully unlocked with biometric" 15:42:44) → deferred rows delivered 15:42:59; .107 tray "Rule update from Kelvin Musila" / "Google Assistant was changed." when=1794314579596; receiver Rules & blocks gained "Google Assistant — Rule turned off" then "Rule updated"; events-today 7→9. Sender's next periodic pass 15:43:37 `drain: 0` (queue empty). Owner confirmed on device |
| S34 | Batch lifecycle full matrix on the two-AVD LOCAL-RIG (owner-requested gap close, added 2026-11-11): create-with-partner → violation → edit/add member → remove partner → re-add partner → delete | Every verb journals ONE batch-level row with the batch name + current item count; `editBatchAndBump` commits membership+journal atomically (RestrictionRuleDao.kt:143-157); ASSIGNED/UNASSIGNED/EVERBOS map in PartnerNotifications.kt:414-460 | **PASSED 2026-11-11 (LOCAL-RIG: A=emulator-5558 "EmuAEmu" ↔ B=emulator-5556 "EmuB", ATTESTATION_STUB=1 emulators + debug build).** Batch "Late night" (Chrome+Calendar, watched by EmuB). (1) add-at-creation: A `drain: 1` 04:1x; B "Rule update from EmuAEmu" / "You were added as accountability partner on the Late night batch (2 items)." (2) violation: Chrome launched on A, polling path carried it (`poll foreground=com.android.chrome carried=com.android.chrome`), A `drain: 1` 04:19:28, B SyncReport pulledCount=1 04:19:29, B "EmuAEmu tried to open a blocked app" / "Chrome was blocked in the Late night batch." (3) edit (added Contacts → 3 items, partner retained): tap batch title opened Edit batch, Contacts checked, Save changes; no prompt (warm window); A `drain: 1` 04:21:50, B pulledCount=1 04:21:53, B "Rule update from EmuAEmu" / "The Late night batch was changed (3 items)." (4) remove partner: A `drain: 1` 04:23:29, B "You were removed from the Late night batch (3 items)." (5) re-add partner via "Add a partner" → EmuB: A `drain: 1` 04:24:06, B "You were added as accountability partner on the Late night batch (3 items)." (6) delete: confirm dialog "and its 3 rules will be removed"; A `drain: 1` 04:24:37, B "The Late night batch was deleted (3 items)." All six legs one row per action, correct item counts, Edit batch reachable by tapping the batch title. Rig quirk: B's ack writes get PERMISSION_DENIED on the emulator (rules-correct, callable-only path upstream) — delivery is unaffected because the notification fires on APPENDED in the same pass |

### B. Streaks in the partnership

| # | Scenario | Expected from source | Result |
|---|---|---|---|
| S13 | Create a new streak | Two cases since ADR #286. No partner picked ("No one"): **no partner notification** (new habit is shared with nobody). Partner picked in the form's "Share with" section: one targeted STREAK_SHARED snapshot on creation (all three switches on, ADR #291) → "«partner» shared a streak with you / You were added to «streak»." and the streak lands in What you share automatically | **PASSED 2026-11-10 (release v9, warm window), both cases.** Case A: created "Matrix Quiet" with the default "No one" chip checked — streak landed on the sender ("Days free from Matrix Quiet", day 1) and produced no streak sync at all: no `drain` line, and the receiver's newest partner notification stayed S20's message (no new `when`). Case B: created "Matrix Shared" with the first "John Maingi" chip picked ("A partner you pick sees this streak automatically." note shown) — `MyExodusPartnerSync: drain: 1 event(s) delivered` 16:21:15.353, receiver .107 tray title "Kelvin Musila shared a streak with you", text "You were added to Matrix Shared.", when=1794316881233 (16:21:21.233), plus the in-app "New activity from your partner" feed post. Share-with chips lists the John enrollment twice (dead duplicate enrollment, cleanup queued post-matrix; either reaches the same device) |
| S14 | Turn on streak sharing for a habit with the pair | One targeted STREAK_SHARED snapshot (ADR #291) → "«partner» shared a streak with you / You were added to «streak»."; the streak appears in the partner's per-streak selector | **PASSED 2026-11-10 (release v9, warm window)**, closing the 2026-11-09 PARTIAL. Matrix Quiet → Manage streak → Accountability partner → first "John Maingi" chip; sheet confirmed "John Maingi can see this streak now." `drain: 1 event(s) delivered` 16:22:54.600; receiver .107 tray title "Kelvin Musila shared a streak with you", text "You were added to Matrix Quiet.", when=1794316979425 (16:22:59.425). Selector with two live projections verified under S26. The 2026-11-09 PARTIAL's late arrival was the cold signing window, not a lost row — warm path delivers in one pass |
| S15 | A clean day passes while sharing is on | STREAK_UPDATED day-crossing → generic "New activity from X" notification; dashboard days-free updates for that streak only | |
| S16 | Log a relapse with Relapses shared | "X logged a relapse / «streak» · ended a N-day streak · «when»" | **PASSED 2026-11-10 (release v9, warm window).** Matrix Quiet detail → Log a relapse → confirm "Log it" → support sheet ("The block stays on / Nothing else was reset.") → Not now. Sender `drain: 1 event(s) delivered` 16:24:03.745; receiver .107 tray title "Kelvin Musila logged a relapse", text "Matrix Quiet · ended a 1-day streak · Nov 10, 2026 4:23 PM", when=1794317048851 (16:24:08.851). Receiver "How they're doing" under Matrix Quiet reads Days free 1, Relapses logged 1, Milestones "None yet" (`total_relapses` bumped by `recordRelapse`, journal stamps `habit.totalRelapses` — `HabitRepository.kt:857`, `PartnerActivityJournal.kt:134`) |
| S17 | Reach a milestone day with Milestones shared | "X reached a milestone / «streak» · reached N days · «when»" | |
| S18 | Delete a streak that was shared | **Notification** since ADR #291: "«partner» deleted a streak / «streak» was deleted." Journaled per currently-sharing pair before the consent rows are removed; the receiver drops that streak's projection row and leaves the others alone | **PASSED 2026-11-10 (release v9, warm window).** Matrix Shared → Manage streak → Delete streak → confirm "Delete". Sender `drain: 1 event(s) delivered` 16:26:54.797; receiver .107 tray title "Kelvin Musila deleted a streak", text "Matrix Shared was deleted.", when=1794317219844 (16:26:59.844). Receiver "How they're doing" dropped the Matrix Shared chip and now reads "Viewing Matrix Quiet" (single projection) with Matrix Quiet's own numbers intact — the deletion left the other streak alone |
| S19 | Turn sharing off for a streak | **Notification** since ADR #286: "X stopped sharing a streak / You no longer see «streak»." (generic lockscreen copy). The revoke survives the consent flip by design (row's own snapshot); the receiver drops that streak's projection row — the others stay | **PASSED 2026-11-10 (release v9, warm window)**, closing the 2026-11-09 RECEIVER BLOCKED. Matrix Quiet II → Manage streak → Accountability partner → uncheck the first "John Maingi" chip; sheet confirmed "John Maingi no longer sees this streak." The journal gate needs only the consent row's existence (the STOP survives the flip by design — `PartnerActivityJournal.anyPairCouldReceive`), and delivery followed three empty ticks: sender `drain: 1 event(s) delivered` 16:29:58.663. Receiver .107 tray title "Kelvin Musila stopped sharing a streak", text "You no longer see Matrix Quiet II.", when=1794317404257 (16:30:04.257) + in-app "New activity from your partner". Receiver "How they're doing" dropped the last projection: no "Viewing" chip and no streak stats remain — with Matrix Shared already deleted (S18), the STOP leaves zero projected streaks. Same warm-path scheduling window seen at S18: act → next drain tick, not a lost row |
| S25 | Rename a shared streak | **Notification** since ADR #291: "«partner» renamed a streak / It is now called «new name»." The partner's selector and row label re-read the new name (relabel, same row — the id is the key) | **PASSED 2026-11-10 (release v9, warm window).** Matrix Quiet → Manage streak → name field cleared and re-typed "Matrix Quiet II" (field verified exact before save) → Save name. Sender `drain: 1 event(s) delivered` 16:28:50.042; receiver .107 tray title "Kelvin Musila renamed a streak", text "It is now called Matrix Quiet II.", when=1794317335349 (16:28:55.349). Receiver "How they're doing" re-entry reads "Viewing Matrix Quiet II" — same row relabeled, numbers intact |
| S26 | Multiple streaks shared: switching and accuracy | The partner's "How they're doing" shows a selector when more than one streak is projected, "Viewing «streak»" when exactly one is; each streak shows its own days free / relapses / milestones, and an event on streak A never moves streak B's numbers | **PASSED 2026-11-10 (release v9).** With two projections live, the receiver's page showed the selector chips "Matrix Quiet" / "Matrix Shared". After S16's relapse on Matrix Quiet: that chip read Days free 1, Relapses logged **1**, Milestones "None yet", "Last shared 1m ago"; switching to Matrix Shared read Days free 1, Relapses logged **0**, Milestones "None yet", "Last shared 4m ago" — an event on streak A left streak B's numbers untouched. After S18 deleted Matrix Shared, the page read **"Viewing Matrix Quiet"** — the exactly-one branch — with Matrix Quiet's numbers carried over unchanged |

### C. Messages and dashboard

| # | Scenario | Expected from source | Result |
|---|---|---|---|
| S20 | Send a partner message (after at least one streak is shared) | Recipient notification "X sent you a message / «quoted text»"; sender sees "Your latest message"; receiver pins it alongside its snapshot (ADR #135 path, unchanged by #285) | **PASSED 2026-11-10** (release v9): Kelvin → John "Hello John, v9 matrix message" from Partner → How they're doing → Write a message. Signing window warm (owner had just used the phone), no prompt; sender `drain: 1 event(s) delivered` 16:15:54.478. .107 tray: "Kelvin Musila sent you a message" / "“Hello John, v9 matrix message”". Sender's page then showed "Your latest message — Hello John, v9 matrix message" (composer closed after delivery, ~20 s "Sending…" while the row drained) |
| S21 | Try to send a message with nothing shared | Composer opens directly (ADR #285 supersedes #284): send succeeds, reconcile ships the message snapshot-less, receiver pins it and posts the text notification, and the dashboard does **not** fill (nothing was shared) | **COVERED by S20 under ADR #285 (2026-11-10).** The pre-send gate no longer exists in the shipped build — `ComposeMessageCard` opens the composer unconditionally ("a supervisor who shares nothing is the message's whole audience"), so the S21 precondition cannot change behavior; S20 device-proved the open → send → notify → received path on v9. The old ADR #284 blocked-sheet flow is unreachable and stays obsolete |
| S22 | "How they're doing" completeness | Shows days free, relapses, milestones, coaching items, last-event age, pinned message, chart + recent activity + rule changes; each snapshot applies wholesale | **PASSED 2026-11-10 (release v9).** One receiver state (after the S14-style re-share of Matrix Quiet II, `drain: 1` 16:32:44.705) renders the full set: "Viewing Matrix Quiet II" (exactly-one branch), Days free 1 with "Last shared just now" (last-event age), "Relapses logged 1", "Milestones met — None yet", "Coaching items 0", "Their message to you — Hello John, v9 matrix message" (pinned), "Shared events per day" chart (7 weekday bars, Today 23), "Recent activity" with Events today/last 7 days 23. Rule changes live on the Rules & blocks subpage, captured row by row in S2/S4/S5/S8/S9/S11. Wholesale application proven by the S26 isolation (one event never moved the other streak) and the S16 relapse flipping all of the selected streak's numbers in one received row (`PartnerScreen.kt:1467-1570` renders selector → hero → three StatColumns → pin) |
| S23 | Accuracy check after each scenario above | Dashboard counts/rows match what was actually received; last-event age advances | **PASSED 2026-11-10 (release v9), checked after every leg.** S16: one "Log it" → Relapses logged 1 on Matrix Quiet while Matrix Shared stayed 0 (`total_relapses` bump, `HabitRepository.kt:857`). S18: deleting Matrix Shared dropped exactly that chip, "Viewing Matrix Quiet" carried its numbers unchanged. S19: the STOP row removed the last projection entirely (no chip, no stats). S25: rename relabeled the same row with numbers intact. S14-style re-share: Days free 1 + "Last shared just now" after the S16 relapse at 16:23. Last-event age advanced across observations ("1m ago" → "4m ago" per streak in S26; "just now" at 16:33). Feed totals (Events today 23 / last 7 days 23) matched the received rows rendered in Recent activity |
| S24 | Each page where the user shared nothing | Partner sees per-page empty copy; activity page says nothing has been shared yet (owner wants the "you can ask them to" nudge added) | **PASSED on v12/1.1.10, 2026-11-10 evening (fresh pair), all four surfaces in both directions.** How they're doing (.106 and .107): "Recent activity 0 / Events today 0 / Events last 7 days 0" plus "Nothing has been shared with you yet. This page fills in when your partner turns on sharing. You can ask them to." — the owner-requested nudge, no fabricated data. Their phone (.106 and .107): "Not shared" with the honest device-availability explanation and the exact path "Partner tab, then What you share, then Device availability". Rules & blocks (.106 and .107): "No rule updates yet / When a rule or batch you watch changes, or a blocked app is opened, it shows up here." — consent-independent, never claims sharing. Plans: .106 showed the real shared plan; the nothing-shared shape was .107's "No plans yet / A plan is a short checklist…" copy before the S27 leg |

### D. Shared plans (ADR #193)

| # | Scenario | Expected from source | Result |
|---|---|---|---|
| S27 | Create a plan with the partner | One TYPE_PLAN_PUBLISHED on creation → "«partner» shared a plan with you / Plan: «title»" (`partner_plan_title_named`, `partner_plan_body_named`); the plan appears on the receiver's Plans page under "From your partner" | **PASSED on v12/1.1.10, 2026-11-10 evening (fresh pair).** John (.107) created "Evening routine" (1 task) for Kelvin (.106): saved at 20:34 local, epoch 1794332082; the receiver's tray posted "John Maingi shared a plan with you" / "Plan: Evening routine" (tag `partner:event:6OzMb…`, record 0x0acf6424, `when=1794332089.980`) — ~8 s after save, time-isolated this run, closing the previous PARTIAL gap. Receiver Plans page read "From your partner — Evening routine, 0 of 1 done · From John Maingi". The receiver checked the task → "1 of 1 done" on both phones, and .107 held zero `partner:event:` notifications (TYPE_PLAN_PROGRESS filtered, as designed). The edit leg (S28) added "Read for 10 minutes" (saved epoch 1794332202) → the tray record replaced the creation under the same tag (new id 0x0fac86c1, `when=1794332209.498`, same "shared a plan with you" copy), and the receiver detail re-read "1 of 2 done" with the checked state preserved across the revision bump |
| S28 | Modify a shared plan (title/tasks) | Same TYPE_PLAN_PUBLISHED with a full payload and a bumped revision → same "shared a plan with you" copy; the receiver's detail re-reads the edited content; no extra notification type | **PASSED 2026-11-10.** .106 edit saved 04:30:54 local → cold sheet at 04:30:55.659 (ADR #296 prompt) → cancellation → `drain: 1 event(s) delivered` 04:31:40.263; Firestore message `c28e85ed` 2026-11-10T01:31:41.272Z; .107 notification at 04:31:47 ("Kelvin Musila shared a plan with you", "Plan: Evening routine…"), and .107's detail now shows the edited task "No screens after 10 pmx" |
| S29 | Recipient checks / unchecks a plan task | No notification (TYPE_PLAN_PROGRESS is filtered before notifications); the check is journaled as a progress row and the author's detail shows "N of 4 done" once a pass delivers it | SENDER-LOCAL PASSED, RECEIVER BLOCKED 2026-11-10: five toggles on .107 flipped the checkbox and counter locally ("1 of 4 done" ↔ "2 of 4 done") and journaled progress; nothing reached the Kelvin mailbox (newest `3ea66cd3` 01:02:12.515Z) because .107's §8.1 window stayed cold and the sheet was never answered. Blocked on owner authentication; the journal heals on the next warm pass. **HEALED 2026-11-10 09:49 EAT:** the pending progress was delivered as one coalesced `plan_progress` row (`fb6caac4`, 06:49:15.947Z) at the next warm pass, and the author's app consumed it before its 10:02:04 pass (`pulledCount=0`, `signingBlocked=false`), so the authored copy applies the recipient's checked set. Author-side Plans page confirmed 2026-11-10 14:35 EAT: "2 of 4 done · With John Maingi" |
| S30 | Author checks a task on their own copy | Nothing is sent — the author's copy is display-only (`setTaskDone` returns before journaling) | **PASSED 2026-11-10.** The author's detail renders hollow progress dots with "«partner» checks these off; their progress shows here"; tapping task 1 left "0 of 4 done" unchanged, produced no sync/activity log lines, and John's mailbox stayed at 60 messages |

### E. Device loss

| # | Scenario | Expected from source | Result |
|---|---|---|---|
| S32 | Uninstall MyExodus on the partner's phone while shared content exists | **With Device availability off (this leg's configuration): no notification of any kind.** An uninstall runs no app code, so it sends no wire event, and the pair documents are untouched; an event dispatch hitting the dead token stays silent server-side (`functions/dispatch.js` `isTokenInvalid` → `markInstallationStale` + a warn log), and the app has no read path to the partner's installations. The DMS notice path has nothing to work with here (no heartbeat state, no availability consent). `pairingUnreachable` does **not** fire for an uninstall — it requires a definitive PERMISSION_DENIED read of the pair document (`PartnerSyncCoordinator.kt:1006-1019`), which an uninstall does not cause. The remaining phone keeps its last-delivered projections and the pairing listing; only the sync age grows. **With Device availability ON the server now raises the notice on the first dead-token probe — S35.** | **PASSED 2026-11-10 evening (owner-ordered final leg, run last).** Live tracked content first: .106 (Kelvin) held John's shared plan "Evening routine" (1 of 2 done), pairing "Synced 3m ago". `adb uninstall com.example.myexodus` on .107 at epoch 1794332368 (`Success`; only the stale `.test` APK remains). Observed on .106 for 60 s and beyond: the notification set was unchanged — still exactly the one plan-edit record (id 0x0fac86c1, `when=1794332209.498`), no new `partner:event:` row, no revocation or "partner left" message; the hub kept "John Maingi · Shared activity is verified", "Plans — 1 to do", "Pairing — Synced 3m ago". Re-checked live ~9 min after the uninstall: same single record, still nothing new. Absence-of-signal matches the source; the aging "Synced" is the documented last-delivered cache, not an error claim. Rotation guard run on both serials afterwards (`accelerometer_rotation=0`) |
| S33 | Device availability ON, with a shared rule and streak tied, then uninstall the partner's phone | (a) The last delivered snapshot keeps rendering until it exceeds two recorder cycles — then Protection reads Unknown, never a carried-forward Off (`PartnerDashboardSource.kt:194-213`, `MONITORING_STALE_MILLIS` line 265). (b) Phone check-ins are server-derived at read time from the last accepted heartbeat: Checking in <45 min, Late 45–90, Not checking in ≥90 (`dms-logic.js:94-106`). (c) The shipped detector is the DMS `AVAILABILITY_6H` notice: `deadlineDecision` (`dms-logic.js:165-192`) makes ONE partner notice due 6 h after the last accepted heartbeat, recipient = the consenting active partner (`consentingPartner`, `dms-logic.js:128-137`); dispatch revalidates relationship + consent (`suppressionReason`, `dms-logic.js:240-258`). Copy is deliberately honest: "Partner check-in" / "A paired device has not checked in recently. This is not proof the app was removed or blocking was turned off." (string set in `dms-processor.js`). Since 2026-11-11 the FIRST hard signal also fires that same notice early: the 45-minute silent liveness probe is rejected with a provider-confirmed dead token (`isTokenInvalid`) → `createUninstallNotice` (`dms-processor.js`), which writes the identical `AVAILABILITY_6H` outbox row and clears both deadlines — proven in the emulator suite, S35. A user-level live-sibling guard (`hasLiveSibling`, `dms-logic.js:146-152`) supersedes the row instead of notifying when another installation of the same user reported within 6 h | **PASSED on v12/1.1.10, 2026-11-10 evening (fresh pair; owner-ordered device-availability + uninstall leg with rules/streaks tied).** Tracked content first: .107 (John) shared streak "Smoking" (1–2 years, Moderate) and a website rule youtube.com "Block all day" assigned to Kelvin; .106 received "Rule update from John Maingi — You were added as accountability partner on youtube.com." and read "Smoking · 1 day free". Device availability enabled on .107 (consent `updatedAtMs` 1794334130747) → snapshot delivered only after a force-stop + relaunch forced the recorder's FGS-start snapshot past the consent watermark; .106's Their phone then read Protection Off / "Updated just now" / Checking in / within the hour. Uninstall on .107 at epoch 1794334246 (`Success`), 20 s after the last accepted heartbeat 1794334226010 (21:10:26 EAT). For ≥60 s after the uninstall .106's notification set was unchanged — no uninstall event, as designed. Firestore live at 21:12 EAT: `dms_install_state` `QianW…_eslgZqeWQNG6kOegYR8wt7` `notice_state: NONE`, `next_deadline_at 2026-11-11T00:10:26.010Z` (03:10:26 EAT) — the six-hour partner notice is ARMED; pairing doc `status: active`, `availabilityConsent[John].enabled=true` (updated 1794334130747), no terminationReason. Their phone at 21:14 EAT truthfully read Protection Off (fresh snapshot) and Checking in / within the hour; expected aging: Late ≈21:55, Not checking in ≈22:40, bucket 1–6 h ≈22:10, Protection → Unknown ≈23:10 (2 h after receipt), six-hour notice ≈03:10 EAT. The immediate dead-token notice did not exist in the deployed worker during this run (implemented 2026-11-11), so the ≈21:55 probe's token rejection only tombstoned the install silently; that path is device-unproven and carries S35's emulator proof instead. Post-run resolution: the pairing was terminated at 2026-11-10T19:50:39.168Z (22:50:39 EAT) by .106, three hours before the deadline, so the 03:10 notice was correctly suppressed at dispatch (relationship closed) — the physical six-hour leg cannot be re-proven on this pair |

| S35 | Immediate uninstall notice on the DMS path (added 2026-11-11): Device availability ON, partner consenting, then the monitored install's FCM token dies (uninstall/data-clear) | `livenessProbeDecision` (`dms-logic.js:209-232`) probes once at 45 min; `isTokenInvalid` on the probe reply → `createUninstallNotice` (`dms-processor.js`) in a CAS transaction (`liveness_generation`, `last_received_at_ms`) that re-checks pairing + consent (`consentingPartner`) and live siblings (`hasLiveSibling`), writes the same `AVAILABILITY_6H` outbox row (same honest copy, same deterministic id), sets `notice_state: NOTIFIED`, bumps `outage_generation`, and clears `next_deadline_at`/`late_deadline_at` so the 6 h path cannot duplicate. A live sibling instead marks the row `SUPERSEDED` and never notifies. Probe-created notices count toward the LATE_BURST breaker (a fleet-wide token invalidation holds dispatch). An accepted heartbeat revives NOTIFIED/SUPERSEDED to `NONE` and re-arms both deadlines | **PASSED on the DMS emulator suite 2026-11-11 (40/40, `npm run test:dms`); physical E2E BLOCKED — .107 was uninstalled and returned, no second handset remains.** Five new emulator tests: a provider-confirmed dead token notifies immediately (same-pass dispatch `sent 1`, state NOTIFIED with `last_notified_generation` set and both deadlines deleted, no second notice an hour later); a live sibling supersedes the immediate notice (zero sends, no outbox row); a live sibling supersedes the six-hour deadline row; an accepted heartbeat revives a `SUPERSEDED` row to `NONE` with both deadlines re-armed and writes no RECOVERY row; immediate notices count toward the fleet breaker (`late_burst_threshold: 1` → `dispatch_hold: true`, rows stay PENDING). Pure suite 206/206 green. No client change: the partner receives the existing SYNC_AVAILABLE data push, just earlier |

### F. Permission loss on a rule shared with the partner (ADR #299, owner directive 2026-11-10)

The monitoring snapshot now states the grants the sender held (Step 3 contract v3, `caps`).
The recipient derives `before - after` and alerts only when one of **its own feed's live
rules** needs the lost grant. One aggregated notification per transition; recovery is silent.

Every posted alert also leaves one row per lost grant in `partner_protection_gap` (30-day
retention), which renders on **Rules & blocks** and in the **recent activity** feed; the
notification tap opens **Their phone** (the report reads Limited with the newest gap named).
Check the feed row and the landing page on the same run as each S34 leg.

**Owner ruling 2026-11-11 — the pill follows protection quality, not detection authority.**
Usage access, overlay, and VPN consent never move `ProtectionAuthority` while accessibility is
alive (`ProtectionStatusMonitor.authorityFor`), so the partner pill could read Active while the
user side read Limited — the owner caught exactly this on the usage leg and approved aligning
it. The sender now journals `DEGRADED` whenever the authority is FULL and a blocking-essential
grant is missing: usage, overlay, or VPN consent while a website rule is live — the identical
`hasLiveWebsiteRule` predicate and gap set the user chip uses (`BlockingReadiness`). The
receiver's existing DEGRADED rendering shows the Limited pill with the gap line, and restore
journals ACTIVE again (notification-silent: the lost set is empty). Accessibility needs no such
mapping — losing it drops authority itself. Pinned by
`MonitoringSnapshotRecorderTest.usageOrOverlayGap_underFullAuthority_isJournaledAsDegraded_andRestoreIsActive`
and `vpnGap_degradesOnlyWhileAWebsiteRuleIsLive`; a failed or unreadable rules read never
downgrades (an unknown is not a loss). Device proof on v15/1.1.13: usage revoke → Limited with
"Usage access is off, so app blocking and limits run on weaker checks · YouTube Music" within
45 s; overlay revoke → Limited with the overlay line; both restores returned Active silently
(owner watched the flip live, 2026-11-11 ~01:00 EAT).

**Owner ruling 2026-11-11 — the capability matrix stays conservative, do not narrow it.**
The owner noticed the usage line naming an "Always on" APP rule (YouTube Music, no daily limit)
and asked whether usage access should only count for rules with a limit. It stays: usage access
is also the polling fallback's detection channel (`UsageStatsManager.queryEvents`,
`EnforcementForegroundService.pollLoop`; `DEGRADED_POLLING_ONLY` requires `usageAccess &&
overlayGranted`, `ProtectionStatusMonitor.authorityFor`), and the user-side
`BlockingReadiness.essentialGap` already counts `!usageAccess` for every rule — narrowing only
the partner side would recreate a user/partner divergence. `RuleCapabilityGap.needs` therefore
keeps APP → {ACCESSIBILITY, USAGE_ACCESS, OVERLAY}. Precision would need `hasLimit` on the
`RuleIdentity` wire (`AccountabilityEventWire` v4) plus the same change in `BlockingReadiness`,
both sides together; deferred until the wire is next touched.

| # | Scenario | Expected from source | Result |
|---|---|---|---|
| S34a | Revoke accessibility on a phone that runs an APP rule assigned to the partner | Snapshot journals immediately (caps change, `MONITORING_PERMISSION_CHANGED`); partner's tray: "Protection gap on «partner»'s phone" / "Accessibility is off, so app blocks start late · «rule»" | **PASSED 2026-11-10 (owner-verified).** .107's tray showed the accessibility alert and the feed row appeared; the owner re-enabled it and protection returned to Active. Delivery landed 3 m 41 s after the revoke (tray `when=1794346360380`) because the idle phone sat in a cold §8.1 signing window (#296) — deferred, not dropped |
| S34b | Revoke usage access (accessibility still on) | Authority does not move; the permission-check sampling still journals; partner sees the usage line naming the rule | **PASSED 2026-11-11 00:37:40–00:38:00 EAT (v14/1.1.12).** Usage revoked on .106 → .107's tray showed the usage line naming YouTube Music 20 s later; feed row written. An earlier 00:18 run matched. **Defect caught here:** the partner pill still read Active while the user side read Limited → owner-approved fix above; **closed on v15/1.1.13:** revoke at epoch 1794348110 → .107's Their phone read Limited with the usage line within 45 s; restore at 1794348173 → Active, gap line gone, no notification |
| S34c | Revoke "Display over other apps" | Same path as S34b → overlay line | **PASSED 2026-11-11 00:26:32–00:26:56 EAT (v14/1.1.12).** Overlay denied on .106 → notification 24 s later; feed row written. **Pill alignment proven on v15/1.1.13:** revoke at epoch 1794348236 → Limited with "Display over other apps is off, so the block screen can't cover a blocked app · YouTube Music"; restore → Active |
| S34d | Turn off the website filter / VPN consent with a WEBSITE rule shared | VPN line; the rule named is the website rule | **PASSED 2026-11-12 (v22/1.1.20 staged), G4.** VPN consent revoked with the live website rule inside the shared Battery batch: revoke T=1794491127 → .107 tray `when=1794491154669` (+27 s) "The website filter is off, so website rules aren't enforced · Battery batch"; tray unchanged afterwards (38 records). Restore + re-enable proven in G5 |
| S35 | Revoke a grant **no live rule needs** (e.g. usage access with only a website rule shared) | Silent — no notification, no tray change; the snapshot still advances the card | **PASSED 2026-11-12 (v22/1.1.20 staged), G5.** With the shared batch paused (no live rule), the VPN revoke's caps-loss snapshot drained (`claimed=1` 16:51:02) and the partner card advanced ("Updated just now") yet the tray stayed silent for 2.5+ min — the receiver's `rulesNeeding` filter working as designed |
| S36 | Grant restored | Silent (lost set empty); the partner's next snapshot shows the filled grant set | **Recovery proven twice:** the S34a accessibility restore returned the pill to Active (owner-verified), and every S34b probe restore re-journals ACTIVE with no alert. Re-enable latency measurement still owed (task #144) |

**Known harness artifact — a gap alert during a `uiautomator dump` battery
(explained 2026-11-12, owner asked why S34a-style alerts fired while the user side read
Protecting).** The ACCESSIBILITY capability is probed from the live binding
(`AccountabilityModule.kt:1071` reads `BlockerAccessibilityService.connected.get()`, cleared
on unbind at `BlockerAccessibilityService.kt:376`), not from the secure setting. On this XOS
device a `uiautomator dump` unbinds and rebinds the service for a few seconds; when the FGS
permission-check sample (`EnforcementForegroundService.kt:753`, every 20 ticks) lands inside
that window, the snapshot journals the missing grant as `MONITORING_PERMISSION_CHANGED`
(`MonitoringSnapshotRecorder.kt:82-96`) and the partner gets the truthful-at-that-instant gap
alert. The rebind restores the grant, the next snapshot carries an empty lost set, and the
restore is silent (S36). Decision: no code change — probing the live binding is the honest
signal; the settings string would hide real unbinds (force-stops, crashes) the partner must
hear about. Expect one-off gap alerts after harness dumps on the subject phone; they are not
reproducible without injected tooling.

### G. v22 staged battery re-run on the live pair (2026-11-12, release 1.1.20 v22)

Owner-ordered re-run of the streak, plan, batch, permission and uninstall legs on the
**staged transport** (`PartnerEventRecorder` → `ServiceEventOutbox` → `PartnerEventSyncWorker`
→ callable → `PartnerMailboxReceiver` → `PartnerObservationProjection`), release APK on both
phones, warm §8.1 windows on the acting phone. Timings are act → receiver tray `when`; the
uninstall leg follows as the last entry of the battery (S32/S33 evidence, re-run subject).

| # | Leg | Result |
|---|---|---|
| G1 | S34a re-run: accessibility revoke with live instrumentation | **PASSED.** Alert 39 s after the revoke (tray), naming Battery batch; partner pill Limited; restore → Active in 38 s, silent; user side read Protecting |
| G2 | S34b re-run: usage access revoke | **PASSED.** Usage alert 32 s; restore 30 s, silent |
| G3 | S34c re-run: overlay revoke | **PASSED.** Round trip: notify 22 s; restore 30 s, silent |
| G4 | S34d re-run: VPN consent revoke with a live WEBSITE rule shared | **PASSED.** Revoke T=1794491127 → .107 tray `when=1794491154669` (+27 s) "The website filter is off, so website rules aren't enforced · Battery batch"; nothing after (38 records) |
| G5 | S35 re-run: revoke a grant no live rule needs, then restore | **PASSED.** Battery batch paused (T_PAUSE=1794491368; receiver "The Battery batch batch was turned off (2 items)." `when=1794491377471`, +9 s), VPN consent revoked at T_SILENT_REVOKE=1794491437 — the caps-loss snapshot drained (`claimed=1` 16:51:02) and the partner card advanced ("Updated just now") but the tray stayed silent for 2.5+ min (no live rule needed the grant). Consent restored at T_RESTORE=1794491654 (`ACTIVATE_VPN: allow`), editor consent card cleared; batch resumed at T_RESUME=1794491676 → receiver "…was changed (2 items)." `when=1794491681301` (+5 s) |
| G6 | S9 re-run: modify a watched batch (pause → resume → add member → remove member) | **PASSED.** Every verb ONE batch-level row with the correct item count: pause → "was turned off (2 items)." +9 s; resume → "was changed (2 items)." +5 s; add Bible (3 items) T_SAVE_MODIFY=1794491759 → "was changed (3 items)." `when=1794491764956` (+5 s); remove Bible T_REVERT=1794491813 → "was changed (2 items)." `when=1794491818610` (+5 s). Local state restored to 2 items, enabled |
| G7 | S13/S14 re-run: share a streak | **PASSED.** Share T=1794491880 → "Kelvin Musila shared a streak with you" / "You were added to Battery Streak II." `when=1794491885401` (+5 s) |
| G8 | S16 re-run: log a relapse with Relapses shared | **PASSED.** Relapse T=1794491910 → "Kelvin Musila logged a relapse" / "Battery Streak II · ended a 1-day streak · Nov 12, 2026 4:58 PM" `when=1794491914151` (+4 s) — wire-v2 named copy |
| G9 | S19 re-run: turn sharing off, then re-share | **PASSED.** Unshare T=1794491990 → "Kelvin Musila stopped sharing a streak" / "You no longer see Battery Streak II." `when=1794491995581` (+5 s). Re-shared afterwards (`when=1794492020911`) and left shared for the uninstall leg |
| G10 | S28 re-run: modify a shared plan (add a task) | **PASSED.** Edit added "Stretch for 5 minutes" (T=1794492084; sender "Saved. It reaches your partner at the next sync.", "1 of 3 done"). .107 synced to "1 of 3 done · From Kelvin Musila" and posted "Kelvin Musila shared a plan with you" / "Plan: Evening routine" `when=1794492109253` (+25 s) — the ADR #193 design (PLAN_PUBLISHED is the one plan alert; progress is filtered) |
| G11 | S29 re-run: recipient checks a plan task | **PASSED.** .107 checked "Read for 10 minutes" → local "2 of 3 done"; .106's authored detail re-read "2 of 3 done" once delivered. No partner notification on either side: .106 has no `partner:event` record (only its normal protection-status notification), and .107's partner record still reads the G10 plan post (`when=1794492109253`) — progress syncs silently, as designed |

Battery end state: Battery batch enabled (2 items), Battery Streak II shared (Day 1),
"Evening routine" shared with John at 2 of 3 done, VPN consent restored. The uninstall leg
runs last, with Device availability ON and this state tied.

## Owner run order (restated 2026-11-09)

The partnership is live; run these in this order, each with device evidence, and check
S23 (accuracy) after every one:

1. **Rules, as separate cases:** create a new block assigned to the partner (S1); assign the
   partner to a block that already exists but was unassigned (S2, distinct from S1).
2. **Lifecycle on watched rules:** modify (S3), deactivate (S4), delete (S5), remove the
   partner (S6), natural expiry (S7). For each: does the partner receive the notification?
3. **Batches:** create a batch rule, then invite the partner to it (S8); modify / deactivate /
   delete / remove partner (S9).
4. **Violations:** partner-side (watched user) attempts a blocked app outside a batch (S10),
   inside a batch (S11), and uses an override (S12). **Ruling: the copy must name the BATCH,
   not the individual item** (owner 2026-11-09; also memory `project_rule_identity_batches`).
5. **Streaks in the partnership:** add/assign the partner to a streak, then relapse (S16),
   milestone (S17), delete the streak (S18), un-share (S19); also confirm a new streak alone
   notifies nobody (S13) and a clean day while shared (S15). Delete and un-share both notify
   (ADR #291 / #286). Then rename a shared streak (S25) and switch between two shared streaks
   (S26), checking each streak's numbers against what was actually sent.
6. **Message:** send one (S20). Send requires the device-key biometric prompt — owner-gated;
   the owner must complete the fingerprint for this leg.
7. **"How they're doing" accuracy (S22/S23):** after every scenario above, verify every
   rendered number/row (days free, relapses, milestones, coaching items, last-event age,
   pinned message, chart, recent activity, rule changes) matches what was actually received.
8. **Nothing-shared feedback (S24):** on each partner page where the viewer shares nothing,
   the partner sees honest per-page empty copy. The activity page now ends "You can ask them
   to." Check whether check-ins, plans and rules need the same feedback; note that the Rules
   page is consent-independent (see finding below), so its empty copy must not claim sharing.

## Findings to decide (recorded, not silently changed)

1. **Daily generic streak notification vs. silent feed** — STREAK_UPDATED posts "New activity
   from X", but the activity feed intentionally hides daily snapshots. Either the notification
   is noise or the feed is hiding an event it promised. Owner decision needed.
2. **Streak deletion / un-sharing is silent** — the partner's dashboard can keep showing the
   last days-free snapshot indefinitely. **Resolved:** un-sharing notifies since ADR #286
   ("stopped sharing a streak"); deleting notifies since ADR #291 ("deleted a streak"), and
   both remove that streak's projection row on the receiver. Verified by test, device leg
   pending (S18/S19/S25).
3. **Attempts are not scoped to the assigned rule** — Check-ins sharing sends blocked attempts
   for every rule, including unassigned ones (batch name included via attempt text).
4. **Empty-share copy lacked the "ask them to" nudge** the owner asked for. **Resolved
   2026-11-09 (ADR #284):** the activity empty state now ends "You can ask them to.", and the
   composer's sharing gate is solved in place (per-habit Share inside the dialog) with inline
   saving/failure feedback — no page round trip, no lost draft. Device-proven on the X6532.
5. **Partner rows can sit queued until a warm signing pass.** Observed 2026-11-09: the X6532C
   dashboard stayed empty, then filled in one burst on a later sync pass; its tray then held
   the earlier-journaled Adobe Scan rule row (`partner:event:P9XV…`, "You were added as
   accountability partner on Adobe Scan."). Partner-visible signing defers queued rows while
   the 900s biometric window is cold and heals on the next warm pass
   (`PartnerSyncCoordinator.kt:54-65`, `:144-156`), so this is the designed fallback, not
   loss. Open question: whether the delayed fill is acceptable or needs a foreground heal.
   **Owner response 2026-11-09: not acceptable — notifications must land seconds after
   sent. ADR #288 fixes the two deferrable transport hops (relay wake is now HIGH priority;
   wake, liveness probe and post-mutation pass run expedited). The 900s signing window is
   deliberately unchanged (NFR4): a cold window still defers the send until the next device
   authentication. Device latency measurement after the owner-ordered reset is the remaining
   proof.**
   **Device measurement 2026-11-09 (warm window):** a Reach Out from Kelvin posted on John's
   tray in about 1 second (`partner_nudge` id 2005, channel `partner_nudges_v3`,
   "Accountability Check-in / Kelvin Musila reached out. Check in when you can."), and the
   S20 message arrived with the receiver pinned and notified; the owner confirmed both. The
   seconds-latency target is met with a warm signing window; a cold window still defers by
   design. Re-measure after the ADR #288 functions deploy and the expedited-work APK land.
   **Resolution (ADR #292, owner-approved B + A, 2026-11-09; B revised by ADR #294):** a share
   toggle asks for the delivery pass at the moment of the act, but the request is silent — a
   cold window never raises the device-key sheet. The Partner page's sync card says the
   updates are waiting and offers Check now (`PartnerSyncSignals.signingDeferred`). Automatic
   delivery still never prompts. Device re-measure of the cold-share leg remains.
   **Generalized (ADR #293, owner mandate 2026-11-09; reversed by ADR #294 the same day):** the
   first version routed every user-act rules write through the prompting heal; on the live
   pair that raised the biometric sheet over a rule save and the owner rejected it ("this is
   supposed to be automatic why the hell do i have to input this"). ADR #294 then made every
   rules write kick a silent pass, but the wait-for-unlock cost drew the 2026-11-10 ruling
   ("i realy hate how maybe ive been using my phone for a while but have to lock and unlock
   it so notifications reach instantly its stupid"). **Current rule (ADR #296):** every
   partner-visible act runs the delivery pass; warm delivers silently, cold with rows queued
   raises the device-auth prompt right there ("Share your updates") and delivers on success.
   Nothing queued never prompts; a cancelled prompt keeps the rows for the next trigger.
6. **Cold-window retry hot loop (found and fixed 2026-11-10).** While S3 sat cold, the
   foreground ran a full partner sync pass every ~15 s indefinitely: each blocked pass emits
   `PartnerSyncSignals.signingNeeded`, and `MainActivity`'s collector reran the pass without
   checking warmth, re-emitting the signal. Evidence: `keystore2: matching auth token is
   expired` + continuous `drain: 0 event(s) delivered` lines. The retry is now the point of
   the feature (ADR #296 prompts on cold), so the loop is closed by the delivery mutex, which
   spans the prompt: a signal re-emitted by the blocked pass finds the mutex held and is
   dropped, not stacked into a second sheet. A cancelled prompt ends the attempt; the next
   trigger starts a new one.
7. **Messaging requires sharing, which locks out a pure supervisor.** MOTIVATIONAL_MESSAGE is
   journaled only when at least one streak is shared with the pair
   (`PartnerActivityJournal.kt:183-184`); the UI mirrors it (`PartnerScreen.kt:426` →
   `HabitRepository.kt:444`). The gate exists because the message row carries the sender's
   progress snapshot and a snapshot-less message is classified Malformed
   (`EventPayload.kt:126`). A partner who only supervises (shares nothing) cannot send text.
   Proposed, awaiting ruling: allow a snapshot-less message, accept null `stateAfter` for
   messages only, and have the receiver apply a snapshot only when present. This would make
   ADR #284's in-place Share rows unnecessary; the empty-state nudge stays.
8. **A lost prompt continuation wedged cold delivery for the process lifetime (found and
   fixed 2026-11-10).** On the X6532C, toggles from 04:22 to 04:35 journaled progress and the
   app-scope kick kept hitting `KEY_USER_NOT_AUTHENTICATED`, but no sheet ever appeared: an
   earlier `BiometricPrompt` continuation had been dropped (no callback), suspending
   `deliverQueuedPartnerEvents` while it held `signingHealMutex`. HOME + relaunch released it
   and the next toggle prompted in 0.3 s. Fixed by bounding the prompt wait
   (`withTimeoutOrNull` 120 s, ADR #296 amendment); a timeout cancels the sheet and returns
   `Cancelled`, releasing the mutex. Re-proven on device: instant sheet, clean BACK cancel,
   fresh sheet on the next toggle.
9. **An edited plan reuses the "shared a plan with you" copy.** TYPE_PLAN_PUBLISHED covers
   both creation and edits and only `partner_plan_title_named` exists, so a modification
   notification reads like a first share; the body does name the plan. Owner decision:
   add a distinct edit title or keep one copy.
10. **The 2026-11-10 enrollment wipe (found and fixed in 1.1.8, ADR #297).** Owner report:
    both phones online on the live pair, the sender's Insights showed Matrix Quiet II at
    7-day best / current run 1 day / 2 relapses, the receiver's "How they're doing" still read
    Days free 1 / Relapses "None yet" / Milestones 0, a relapse on the sender produced no
    notification, and the sender's Partner tab read "Pairing data was lost". Proven chain:
    Hilt field injection built the accountability Room store inside `super.onCreate()`, before
    `LocalDatabaseMigration.start()` chose the scope (`MyExodusApp.kt:82`, `:160`, `:249`), so
    the live store bound the legacy `myexodus_accountability.db` name (`dumpsys dbinfo`);
    the 7-day rollback sweep deletes the legacy names as disposable copies
    (`LocalDatabaseMigration.kt:621-632`), and the next cold start recreated the file empty →
    canary present + enrollments empty → `LocalWipeDetector` wiped the pair's journal state →
    RELAPSE events no longer journaled → no mailbox row, no notification. The receiver had
    bound the scoped store, so it kept the pair and a frozen pre-wipe projection. Fixed by
    Provider indirection (the store is built only after `start()`), source-guarded copies, the
    one-time `adoptPreFixLiveStore` recovery, and identity-scoped `forgetScope`; see ADR #297
    for the full analysis and honest limits. Matrix cells marked PASSED before this finding
    (S1-S2, S4-S6, S8-S9, S10-S11, S13-S14, S16, S18-S20, S22-S23, S25) ran while the sender's
    enrollment was still live; the wipe happened later the same day (the S29/S30 plans pass and
    the relapse-without-notification report sit after it). **Resolution (v10, 2026-11-10
    evening):** the scoped DB is open on both phones; the receiver's enrollment was recovered
    by the adoption; the sender's was not recoverable (no surviving file held it) and a fresh
    re-pair was completed at 18:33-18:35 EAT. Both Partner tabs now read "Since Nov 10, 2026 ·
    Shared activity is verified" against each other. The matrix cells above therefore ran on
    the previous pair; legs re-run on the fresh pair must be marked as such.

## Device run ground rules

- Physical X6532 (Kelvin) + X6532C (John), release builds only; each cell names the build it
  ran on (v8/1.1.6 through v12/1.1.10 across 2026-11-09–10, several different pairings);
  `uiautomator dump` before every tap.
- Evidence per scenario: recipient `dumpsys notification` line (tag/id + title/text) and/or
  screenshot; sender state screenshot where the scenario changes a page.
- After any injected input: `scripts/rotation-guard.sh` on both serials, verify
  `accelerometer_rotation=0`.
- Anything not runnable today (e.g., a real milestone day, natural expiry in hours) is marked
  BLOCKED with the reason, never PASSED by inference.
11. **A fresh pairing reused the previous session's partner view (found and fixed in 1.1.9, ADR #298).**
    Owner report: "we terminated partnership but its showing the old partnerships data accurate
    but i thought we start a fresh?" and "look at the inisghts page its saying in matrix quit 2
    i have 2 relapses in partner page in how theyer doing partner sees 1 ... what is the source
    of truth". Two mechanisms, one display and one retention:
    - **By design, the two pages read different stores.** Insights is the author's local truth
      (`InsightsRepository.kt:150`); "How they're doing" is the receiver-local last-delivered
      projection (`AccountabilityModule.kt:782-810`, `PartnerScreen.kt:1475`/`:1534`). It
      showed 1 because ADR #297's wipe ate the second-relapse delivery; the projection froze.
      A lagging partner view is the cache design, not a sync defect.
    - **Ending a partnership never cleared the partner-view rows.** AD-07's pairId is
      deterministic, so the re-pair reused it; `ChainStateStore.wipePair` clears only chain
      tables, and the orphan sweep skips any id that is enrolled again
      (`PartnerSyncCoordinator.kt:678-703`). Fixed by pruning the rules-store partner rows on
      every unpair (`BilateralDeletion.shredPair`) and again at pairing completion
      (`PairingFlow.finalizePairing`, before the enrollment lands). Device proof of the
      destructive leg (end partnership → page clears → fresh pair shows "Nothing shared yet")
      **PASSED on 1.1.9, 2026-11-10 evening**, invitation code N8EV-FMH0-AJ5E-2, safety
      compare 45783: .106 ended the partnership (owner biometric) → .107 received the
      revocation and erased its copy (the stale "Matrix Quiet II · 1 day free" card vanished)
      → .106 confirmed "Your partner's device has also erased its data." → both read "No
      partner yet" → the same accounts re-paired → both Partner tabs read the new session
      ("Since Nov 10, 2026", "Shared activity is verified", "Nothing shared yet").
    - **The 1.1.9 prune was still incomplete (fixed in 1.1.10, ADR #298 Amendment).** After
      that successful re-pair, .106's "How they're doing" still showed "Your latest message —
      Hello John, v9 matrix message". Root cause: `latestSentMessageText`
      (`PartnerActivityDao.kt:104-111`) reads targeted `partner_activity_outbox` rows by
      `target_pair_id` with **no enrollment-time gate** (unlike every delivery query), and the
      prune never deleted those rows — the deterministic pairId handed them to the new
      session. The same audit found undelivered `partner_event_journal` rule rows inflating
      the Rules "waiting to be sent" banner counter. Both deletes now live in
      `prunePartnerActivity` (`deleteTargetedJournalFor`, `deleteRuleRowsFor`).
    - **Device proof on 1.1.10, 2026-11-10 evening:** the same accounts re-paired on v12
      (invitation code 6MDM-W7WG-2R68-3, safety compare 11610) after having sent "Hello
      John, v9 matrix message" under v9. .106's "How they're doing" no longer showed the
      message card — `deleteTargetedJournalFor` is device-proven. `deleteRuleRowsFor` has no
      display proof from this run because both rule stores were empty at unpair (no pending
      banner to observe); it is a plain column delete, covered by the JVM suite and
      lintDebug. The fix is not retroactive: rows created before 1.1.10 still need one
      unpair on 1.1.10+.
12. **An uninstall now raises the availability notice on the first hard signal, not only at six
    hours (updated 2026-11-11; six-hour detector recorded 2026-11-10, S33).** An uninstall runs no
    app code, so no wire event exists; the Firestore pair documents survive, so the remaining
    phone's reads still succeed, and `pairingUnreachable` never fires (S32). What IS shipped, and
    was live-verified in S33: when the monitored member enabled Device availability, the DMS
    deadline worker makes one partner notice due 6 h after the last accepted heartbeat
    (`deadlineDecision`, `dms-logic.js:165-192`), addressed only to the consenting active partner
    (`consentingPartner`, `dms-logic.js:133-137`) and revalidated at dispatch (`suppressionReason`,
    `dms-logic.js:240-258`). The copy is deliberately honest — "A paired device has not checked in
    recently. This is not proof the app was removed or blocking was turned off." — because a
    force-stop, doze or a removed app all look identical server-side. **Since 2026-11-11 the only
    hard signal fires that same notice early instead of only tombstoning the install:** the
    45-minute silent liveness probe (`livenessProbeDecision`, `dms-logic.js:209-232`) is rejected
    with a provider-confirmed dead token (`isTokenInvalid`), and `createUninstallNotice`
    (`dms-processor.js`) writes the identical `AVAILABILITY_6H` outbox row — same copy, same
    deterministic id, same dispatch-time consent revalidation — in a CAS transaction on
    (`liveness_generation`, `last_received_at_ms`), then sets `notice_state: NOTIFIED` and clears
    both deadlines so the six-hour path cannot duplicate. Detection latency is ≈45 min plus the
    5-minute scheduler cadence, bounded by FCM's own token-invalidation lag; a force-stop without
    uninstall leaves the token valid and keeps only the six-hour notice, so the honest-copy rule
    survives. The user-level live-sibling guard (`hasLiveSibling`, `dms-logic.js:146-152`) marks
    the row `SUPERSEDED` instead of notifying when another installation of the same user reported
    within 6 h — availability is read user-level (`readPartnerStatus`), so a replaced or
    reinstalled phone must never page the partner; this also fixes the pre-existing false positive
    where a stale install row fired at its own 6 h deadline after a reinstall. Probe-created
    notices feed the LATE_BURST breaker, so a fleet-wide token invalidation holds dispatch instead
    of paging every partner. An accepted heartbeat revives NOTIFIED/SUPERSEDED to `NONE` and
    re-arms both deadlines; a revived NOTIFIED row follows the normal recovery-notice path.
    Emulator proof in S35 (40/40, five new tests); the physical E2E leg is BLOCKED — no second
    handset, .107 was uninstalled and returned.
