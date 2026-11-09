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

## Matrix

Columns: **#**, **scenario**, **expected (source)**, **device result**.

### A. Rules and batches (assign to partner first)

| # | Scenario | Expected from source | Result |
|---|---|---|---|
| S1 | Create a single block, assigned to partner | Partner notification "You were added as accountability partner on «label»." (CREATED) + Rules page row | PARTIAL 2026-11-09: receiver tray record `partner:event:P9XV…` id 1949173998, title "Rule update from Kelvin Musila", text "You were added as accountability partner on Adobe Scan." Delivered late on a warm sync pass (finding 5), not at create time. Rules page row not yet re-checked after delivery |
| S2 | Assign partner to an existing unassigned block (edit) | UNASSIGNED to nobody, ASSIGNED to partner → "You were added as accountability partner on «label»." | |
| S3 | Modify a watched block (change app/expiry while still assigned) | UPDATED → "«label» was changed." | |
| S4 | Turn a watched block off (toggle) | DISABLED → "«label» was turned off." (no approval request) | |
| S5 | Delete a watched block | DELETED → "«label» was deleted." | |
| S6 | Remove the partner from a watched block | UNASSIGNED → "You were removed from «label»." | |
| S7 | Block naturally expires (set duration runs out) | EXPIRED → "«label» ended. Its set duration ran out." | |
| S8 | Create a batch with partner assigned | One batch row → "You were added as accountability partner on the «batch» batch (N items)." | |
| S9 | Modify / turn off / delete a watched batch; remove partner from it | One batch-level row per action naming the batch, never N item rows | |
| S10 | Blocked attempt on a standalone watched block | "«partner» tried to open a blocked app / «app» was blocked." — only while Check-ins is on for that pair | |
| S11 | Blocked attempt on a rule inside a batch | "«partner» tried to open a blocked app / «app» was blocked in the «batch» batch." — the batch name must appear (owner ruling) | |
| S12 | Override used instead of a block | "«partner» used an override / «app» was opened with an override (in the «batch» batch)." | |

### B. Streaks in the partnership

| # | Scenario | Expected from source | Result |
|---|---|---|---|
| S13 | Create a new streak | Two cases since ADR #286. No partner picked ("No one"): **no partner notification** (new habit is shared with nobody). Partner picked in the form's "Share with" section: one targeted STREAK_SHARED snapshot on creation (all three switches on, ADR #291) → "«partner» shared a streak with you / You were added to «streak»." and the streak lands in What you share automatically | SENDER PASSED 2026-11-09 (.106, release): created "Device Leg" with John Maingi picked in the setup form's "Share with" chips (section note: "A partner you pick sees this streak automatically. Disconnect it anytime in What you share."). What you share immediately listed "Device Leg — Shared with John Maingi". RECEIVER BLOCKED: the creation snapshot is queued in the cold signing window (see finding 5); no tray record on .107 yet, so the partner-visible leg is not passed |
| S14 | Turn on streak sharing for a habit with the pair | One targeted STREAK_SHARED snapshot (ADR #291) → "«partner» shared a streak with you / You were added to «streak»."; the streak appears in the partner's per-streak selector | PARTIAL 2026-11-09: enabled from the blocked composer (ADR #284), sheet flipped in place, hub card cleared. The snapshot arrived late, not lost: the X6532C dashboard filled on a later warm sync pass. Delay matches the cold signing window deferring queued rows (`PartnerSyncCoordinator.kt:54-65`, `:144-156`); not yet isolated to one observe-and-wait pass |
| S15 | A clean day passes while sharing is on | STREAK_UPDATED day-crossing → generic "New activity from X" notification; dashboard days-free updates for that streak only | |
| S16 | Log a relapse with Relapses shared | "X logged a relapse / «streak» · ended a N-day streak · «when»" | |
| S17 | Reach a milestone day with Milestones shared | "X reached a milestone / «streak» · reached N days · «when»" | |
| S18 | Delete a streak that was shared | **Notification** since ADR #291: "«partner» deleted a streak / «streak» was deleted." Journaled per currently-sharing pair before the consent rows are removed; the receiver drops that streak's projection row and leaves the others alone | |
| S19 | Turn sharing off for a streak | **Notification** since ADR #286: "X stopped sharing a streak / You no longer see «streak»." (generic lockscreen copy). The revoke survives the consent flip by design (row's own snapshot); the receiver drops that streak's projection row — the others stay | SENDER PASSED 2026-11-09 (.106, release): Device Leg's per-streak sheet had all three switches on; flipping the master to false left What you share showing "Device Leg — Not shared" while Test Habit stayed "Shared with John Maingi". RECEIVER BLOCKED: `MyExodusPartnerSync: drain: 0 event(s) delivered` on the sender — the STOP row is queued in the cold signing window (ADR #259); no tray record on .107, so the notification text is not yet device-proven |
| S25 | Rename a shared streak | **Notification** since ADR #291: "«partner» renamed a streak / It is now called «new name»." The partner's selector and row label re-read the new name (relabel, same row — the id is the key) | |
| S26 | Multiple streaks shared: switching and accuracy | The partner's "How they're doing" shows a selector when more than one streak is projected, "Viewing «streak»" when exactly one is; each streak shows its own days free / relapses / milestones, and an event on streak A never moves streak B's numbers | |

### C. Messages and dashboard

| # | Scenario | Expected from source | Result |
|---|---|---|---|
| S20 | Send a partner message (after at least one streak is shared) | Recipient notification "X sent you a message / «quoted text»"; sender sees "Your latest message"; receiver pins it alongside its snapshot (ADR #135 path, unchanged by #285) | PASSED 2026-11-09: Kelvin → John "Hello John, testing now" arrived; receiver pinned the message and posted the notification; sender shows the latest message. No biometric prompt because the 900s signing window was still warm from the pairing ceremony (~2 min earlier). Owner confirmed. A cold-window send still defers until device authentication (NFR4, unchanged) |
| S21 | Try to send a message with nothing shared | Composer opens directly (ADR #285 supersedes #284): send succeeds, reconcile ships the message snapshot-less, receiver pins it and posts the text notification, and the dashboard does **not** fill (nothing was shared) | OBSOLETE for the ADR #284 flow 2026-11-09: blocked sheet listed Test Habit + Share, tapping Share flipped the same sheet to the composer. The gate itself was removed by ADR #285 — retest pending on the new build |
| S22 | "How they're doing" completeness | Shows days free, relapses, milestones, coaching items, last-event age, pinned message, chart + recent activity + rule changes; each snapshot applies wholesale | |
| S23 | Accuracy check after each scenario above | Dashboard counts/rows match what was actually received; last-event age advances | |
| S24 | Each page where the user shared nothing | Partner sees per-page empty copy; activity page says nothing has been shared yet (owner wants the "you can ask them to" nudge added) | |

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
   **Resolution (ADR #292, owner-approved B + A, 2026-11-09):** a share toggle now asks for
   the delivery pass at the moment of the act, so a cold window raises the device-key prompt
   right there; if the prompt is cancelled, the Partner page's sync card says the updates are
   waiting and offers Check now (`PartnerSyncSignals.signingDeferred`). Automatic delivery
   still never prompts. Device re-measure of the cold-share leg remains.
   **Generalized (ADR #293, owner mandate 2026-11-09):** the same delivery-at-act now covers
   every rules write — save, edit, pause/resume, batch create/toggle/delete, disconnect,
   unlock — because `RulesRepository.journaled()` splits user-act rows from the automatic
   expiry sweep and emits `PartnerSyncSignals.deliveryRequested` for the former. The backlog
   batch-flush ("all notifications came at once") no longer happens for user acts; only a
   cancelled prompt defers, and only the sweep stays silent-by-design.
6. **Messaging requires sharing, which locks out a pure supervisor.** MOTIVATIONAL_MESSAGE is
   journaled only when at least one streak is shared with the pair
   (`PartnerActivityJournal.kt:183-184`); the UI mirrors it (`PartnerScreen.kt:426` →
   `HabitRepository.kt:444`). The gate exists because the message row carries the sender's
   progress snapshot and a snapshot-less message is classified Malformed
   (`EventPayload.kt:126`). A partner who only supervises (shares nothing) cannot send text.
   Proposed, awaiting ruling: allow a snapshot-less message, accept null `stateAfter` for
   messages only, and have the receiver apply a snapshot only when present. This would make
   ADR #284's in-place Share rows unnecessary; the empty-state nudge stays.

## Device run ground rules

- Physical X6532 (Kelvin) + X6532C (John), release v8/1.1.6; `uiautomator dump` before every tap.
- Evidence per scenario: recipient `dumpsys notification` line (tag/id + title/text) and/or
  screenshot; sender state screenshot where the scenario changes a page.
- After any injected input: `scripts/rotation-guard.sh` on both serials, verify
  `accelerometer_rotation=0`.
- Anything not runnable today (e.g., a real milestone day, natural expiry in hours) is marked
  BLOCKED with the reason, never PASSED by inference.
