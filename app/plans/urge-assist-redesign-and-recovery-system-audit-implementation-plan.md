# MyExodus: Urge Assist Redesign and Recovery System Audit

**Status:** Awaiting owner approval. No application code has been modified.
**Date:** 2026-11-07
**Research foundation:** `app/plans/My_Exodus_Urge_Assist_Audited_Consolidated_Research.docx` (read in full: 1,228 extracted lines / 13,488 words)
**Scope owner note (2026-11-07):** the redesign covers every page and subpage under Recovery: the hub, the new Urge Assist flow, Techniques and all four technique runners, My plan and its editor, Risk windows, Friction timer, Get help now, and the History rows that new events produce. Settings entries that currently host recovery content are part of the move.

---

## 0. Ground rules for this plan

- **No code changes until the owner approves.** This document is the deliverable of steps 1-8 of the assignment; step 9 is a stop.
- **Preserve the architecture that already works:** local-first storage, best-effort writes, `FLAG_SECURE`, the privacy gate, the session state machine, the friction engine, the partner envelope, and direct-APK distribution.
- **No new permissions, no new backend, no new external service.** The intervention engine is deterministic Kotlin.
- **No fabricated organizations, numbers, or hours.** Every external resource below was checked on 2026-11-07; what could not be verified is listed as unverified and is not presented as fact.
- **Evidence labels are planning tools, not user-facing claims.** The app will not display "clinically proven" badges or efficacy percentages.

---

## A. Existing Implementation Inventory

### A1. Navigation and entry points

| Surface | File | Current behavior |
|---|---|---|
| Recovery tab | `ui/AppShell.kt` | One of six tabs: `HOME, RULES, RECOVERY, STREAKS, PARTNER, SETTINGS`. Passes `entryHint`, `habitId`. |
| Recovery deep link | `MainActivity.kt:1334-1343` | `EXTRA_RECOVERY_ENTRY` carries a `RecoverySession.ENTRY_*` constant; consumed by the shell after capture. |
| Block screen | `blocking/BlockerActivity.kt` | "Open MyExodus Recovery" starts the friction session and lands on the Recovery tab with `ENTRY_BLOCK_SCREEN`. |
| Widget | `widget/WidgetActionActivity.kt`, `WidgetActionViewModel.kt`, `widget/DesertDawnWidget.kt` | Hold-to-confirm "Log an urge" (`logOnly`, `ENTRY_WIDGET`, bound habit) and "Reach out" (same SOS machine). |
| Home FAB | `ui/AppShell.kt:889-930` | One quiet control expands to reach out, log a relapse, log an urge, with prerequisite dialogs. |
| No dedicated Urge Assist entry | - | There is no shortcut, no block-screen direct route, and no single "I need help now" action outside the tab. |

### A2. Recovery hub

`ui/recovery/RecoveryScreen.kt`.

- View constants `VIEW_CRISIS`, `VIEW_TECHNIQUES`, `VIEW_PLAN` (`:88-90`); technique sessions are selected by id; otherwise `HomeView`.
- `RecoveryScreen` (`:107-305`) loads live friction sessions, plan items, active risk window, profile id; ticks each session with `RecoveryCountdownTracker`; re-reads on `ON_RESUME`; wraps everything in `SecureSurface()`.
- `HomeView` (`:421-544`) order:
  1. Title and subtitle.
  2. Live `ActiveTimerCard` + `OverrideCard` per rule, or `IdleCard`.
  3. `LogUrgeCard` (hero when no live session).
  4. `LeaveAloneCard` when a session is live.
  5. "Try right now" heading, risk-window note, one `TechniqueCard` for `RecoveryResourceCatalog.recommended(rating = null, inRiskWindow)`.
  6. Two `NavTile`s: Techniques and My plan.
  7. Full-width crisis `NavTile` with `Icons.Outlined.SupportAgent` and error tint.
- `TechniqueCard` (`:546-603`): tinted circular icon disc, title, effort pill, two-line description, chevron.
- `LogUrgeCard` (`:803-862`): tinted circle, title, caption, chevron.
- `OverrideCard` (`:864-954`): tinted circle, destructive confirmation dialog.
- `LeaveAloneCard` (`:956-999`), `IdleCard` (`:1001-1039`): same icon-disc grammar.
- The current visual system is the pattern the owner called AI slop: every row is a rounded surface with a tinted circular icon badge.

### A3. Technique catalog and runners

- `recovery/RecoveryResourceCatalog.kt`:
  - Four code-defined techniques: `urge_surfing` (LOW, 4 in / 6 out, 3/5/10 min, default 5), `delay_timer` (LOW, 10 min, shows if-then plans), `sober` (MEDIUM, five phases, shows if-then), `values_card` (HIGH, shows values).
  - `ordered(inRiskWindow)` sorts by effort inside a risk window.
  - `recommended(rating, inRiskWindow)` returns `urge_surfing` when `rating >= 7` or in a risk window, otherwise `sober`. The hub always passes `rating = null`, so the intensity branch is unreachable from the UI.
- `ui/recovery/TechniqueRunner.kt`:
  - `PacedBreathing`: duration chooser 3/5/10 (default 5), start/pause/resume, optional haptic, "Do another set".
  - `DelayTimer`: 10 minutes, progress, if-then items inline.
  - `SoberWalkthrough`: five phases, Back/Next/Done, Stop.
  - `ValuesCard`: one value at a time.
  - `RunnerActions`: primary "I'm done", text "Stop".
- `ui/recovery/UrgeLogSheet.kt`: optional intensity 1-10 in a wrapping row; cancel/log; copy says logging keeps the block and is not shared. No category, no helpfulness, no note.
- `ui/recovery/RecoveryPrefs.kt`: only `hapticPacing` and a one-time no-lock banner.

### A4. Plan, risk windows, friction timer

- `recovery/RecoveryPlanRepository.kt`:
  - `windows()` seeds `RiskWindow.DEFAULT` (20:00-06:00 all days) on first read inside a transaction; `addWindow` enforces at most 3; `updateWindow`; `deleteWindow`; `activeWindow(now)`.
  - `items(profileId)`, `addItem` (blank rejected, trimmed, at most 200 chars, at most 5 per kind, emits `TYPE_COACHING_STEP_UPDATED` with a count only), `updateItem`, `deleteItem`, `moveItem` (no event).
- `ui/recovery/CoachingPlanScreen.kt`: privacy-gated editor for the three kinds; add/edit/delete/reorder behind a per-item overflow; 200-char counter; "Any time" / window filter chips.
- `ui/recovery/RiskWindowsScreen.kt`: privacy-gated editor; at most 3; seeded default; at least one remains; add/edit dialog with time fields and day chips; toggle; delete.
- `ui/recovery/FrictionTimerScreen.kt`: privacy-gated pane; reads `frictionSeconds()`; hours and minutes fields; validates 1 minute to 24 hours; saves; copy says it applies to new sessions.
- `blocking/recovery/RecoveryStateStore.kt`: dual-clock deadlines (wall clock + elapsedRealtime + boot session id); default 60 s; bounds 60 s to 24 h; override cooldown default 600 s.
- `recovery/RiskWindowEvaluator.kt`: `[start, end)`, overnight when `start > end`, day mask bit 0 = Monday applied to the day the window starts, `start == end` never active, first active window wins.
- `ui/recovery/PrivacyGate.kt` + `GatedContent` in `ui/recovery/SessionHistoryScreen.kt:534`: `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, no CryptoObject, 60-second memory-only window, no-lock banner. Gated: history, plan editor, risk windows, friction timer. Not gated: interventions, crisis.

### A5. Session data, mirror, and history

- `data/db/entity/RecoverySession.kt`: columns `id`, `technique`, `habit_id`, `intensity_start`, `intensity_end`, `state`, `started_at_epoch_ms`, `ended_at_epoch_ms`, `duration_sec`, `zone_id`, `entry`, `false_alarm`, `event_sequence`. States STARTED/COMPLETED/ABANDONED/INTERRUPTED; entries BLOCK_SCREEN/WIDGET/TAB/HOME; `TECHNIQUE_NONE = "none"`; stale bound 15 minutes.
- `data/db/dao/RecoverySessionDao.kt`: `page`, `count`, `countsByStateSince` grouped by state and technique, `interruptStale`, `pendingMirror` (terminal, technique != none, not linked for this pair, bounded by pairing time), `linkEvent`.
- `recovery/RecoverySessionRepository.kt`: best-effort writes; `start`; `finish` (COMPLETED or ABANDONED only, first terminal wins, emits `PartnerSyncSignals.activityEmitted`); `abandonQuietly` on an app-level scope; `logOnly` (born COMPLETED, technique none, never mirrored); `interruptStale`; `delete` removes the row then its links; `page`, `count`, `countsByStateSince`, `pendingMirror`, `linkEvent`.
- `recovery/RecoveryMirror.kt`: `recovery:<technique>:<completed|abandoned|interrupted>`, strict parse. Coarse: technique and outcome only.
- `ui/recovery/SessionHistoryScreen.kt` + `ui/streaks/HistoryStamp.kt` + `HistorySummary.kt`: History now lives in Streaks (owner ruling 2026-11-07), with habit filter, per-row delete, summary buckets Completed / Not finished / Logged only.

### A6. Partner support

- `ui/home/SosViewModel.kt`: Idle/Sending/Sent/RateLimited/NotSentOffline/CouldNotConfirm/Unavailable; explicit confirmation dialog with recipient roster; 5-second transient results; no background retry; retained envelope replay after uncertain delivery.
- `accountability/sos/NudgeEnvelope.kt`: v1 constant and v2 CBOR text inside the pair CEK; AAD `partner_nudge|v1|{pairId}|{nudgeId}|{senderUid}`; 1,024 wire-char cap; message at most 280 chars / 700 bytes; NFC normalization.
- `ui/HomeScreen.kt` `NudgeCardView`: recipient sees the newest reach-out, partner name, age, optional message, dismiss. No reply action (one-way by owner ruling).
- There is no prewritten support-request template, no quiet/about-no-reply option, and no in-flow suggestion to reach out.

### A7. Get Help / crisis

- `recovery/CrisisResources.kt`: flat model `CrisisResource(id, region, name, description, phone, sms, url, source, lastVerifiedAt)`, tolerant parser, region-first plus `GLOBAL` ordering.
- `app/src/main/assets/crisis_resources.json` (all `lastVerifiedAt` 2026-09-19): Kenya Red Cross 1199; Befrienders Kenya +254722178177; Kenya emergency 999; US 988; US Crisis Text Line; US SAMHSA; Find A Helpline (GLOBAL).
- `ui/recovery/CrisisScreen.kt`: parses the asset, filters by `Locale.getDefault().country`, renders name/description, then only phone (`ACTION_DIAL`) and URL (`ACTION_VIEW`), plus a verified label and a "not emergency services" footer.
- **The `sms` field is parsed but never rendered.** The Befrienders copy promises SMS/WhatsApp but the UI offers only a phone call.
- **No gambling, porn/CSB, gaming, shopping, or peer-recovery organizations.** No GRA self-exclusion entry.
- **US services are listed under a US region**, so a Kenya locale shows only the Kenya entries plus Find A Helpline. That part is correct, but the directory is too thin.

### A8. Settings and docs

- `ui/settings/SettingsScreen.kt:181-199`: panes for coaching plan, risk windows, friction timer.
- `ui/settings/SettingsScreen.kt:234-236`: pane constants.
- `ui/settings/SettingsScreen.kt:383-401`: the three index rows.
- `ui/settings/AboutScreen.kt`: "self-help tool, not medical treatment"; SOS disclaimer.
- `app/docs/MyExodus V1.md`: recovery requirements and ADRs #61, #88, #193/#214, #211, #267. The doc claims the Settings copy of the coaching plan was removed; the source still contains it. Source wins; this plan corrects the record.
- `app/docs/MyExodus Architecture Decisions.md`: recovery ADRs.

### A9. Tests

Existing: `RecoveryResourceCatalogTest`, `RiskWindowEvaluatorTest`, `RecoveryPlanRepositoryTest`, `RecoverySessionRepositoryTest`, `PrivacyGateTest`, `HistoryStampTest`, `HistorySummaryTest`, `RecoveryDelayCopyTest`, `RecoverySessionTest`, `RecoveryUnlockTest`, `RecoveryTimerTest`, `RecoveryCountdownTest`, `RecoveryStateStoreTest`, `PacerPauseTest`, `CrisisResourcesReleaseTest`.
There is no test for intervention selection, check-in branching, category rules, support-message templates, or directory channel rendering, because none of that exists yet.

---

## B. Gap Analysis

| # | Research requirement | Current state | Severity |
|---|---|---|---|
| G1 | A single obvious in-the-moment entry | The hub is a dashboard; the closest thing is one suggested technique card. No dedicated Urge Assist route. | Critical |
| G2 | One feasible action, chosen for person x context x moment | Fixed selector; the intensity branch is unreachable; no preference, category, mobility, quiet, or support state. | Critical |
| G3 | An immediately available alternative and "can't do that" path | Only "choose another technique" from the full list. No materially different alternative, no escape hatch. | Critical |
| G4 | Brief check-in and adaptation | "I'm done" opens the intensity log sheet and routes home. No Better/Same/Worse/Skip, no escalation on Worse. | Critical |
| G5 | Behavior-specific safeguards | All four techniques are behavior-agnostic. No gambling transaction interruption, self-exclusion, cue removal, checkout interruption, or gaming stop point. | Critical |
| G6 | Consent-based, no-response-fallback partner support | Reach out exists on Home and the widget, but the assist flow never proposes it; no templates; no quiet option; no "no reply needed" copy. | High |
| G7 | Human/professional directory with behavior coverage | Generic mental-health list only; no gambling, porn/CSB, gaming, shopping, peer recovery, or professional bodies. SMS dead. | Critical |
| G8 | Kenya-first localization | Only three Kenya entries; two are generic mental health and one is emergency. No GRA, GA Kenya, SMART Nairobi, 1190, Niskize, Mathari. | Critical |
| G9 | No inference from time or device activity | Late night already only changes technique order; that part is correct. But there is no explicit rule preventing future inference. | Medium |
| G10 | Preconfigured when calm | Only haptic pacing is stored. No mobility, quiet preference, disliked actions, helpful actions. | High |
| G11 | Friction timer reachable where the intent is | Buried in Settings, behind the privacy gate, two levels deep. | High |
| G12 | Plan and risk windows reachable where the intent is | Reading the plan is on Recovery, editing is in Settings; risk windows only in Settings. Duplicate navigation. | High |
| G13 | Non-punitive post-lapse support, protections preserved | Streak relapse logging is non-punitive and does not touch rules (verified in `StreaksViewModel`/`AppShell`), but there is no follow-up support offer and no explanation that blocks stay on. | High |
| G14 | Sensitive categories never auto-shared | No category exists yet. The existing mirror shares technique and outcome automatically. Any new action id must not leak category or action type. | Critical (design constraint) |
| G15 | Privacy surfaces | `FLAG_SECURE` and the gate are strong. The new flow, directory, and history rows must inherit them. | Retain |
| G16 | Offline-first | Techniques, plan, windows, friction, and the crisis asset are local. Only partner reach-out needs network, and it fails visibly. Preserve. | Retain |
| G17 | Explainable selection | None. The suggestion has no reason line. | High |
| G18 | Visual quality | Tinted icon discs and rounded card soup on every row; no editorial hierarchy; owner calls it AI slop. | High |
| G19 | Accessibility | Existing runners have basic controls; no systematic audit for TalkBack, font scale, reduced motion, or one-handed use. | Medium |
| G20 | Docs/source drift | V1.md says the Settings coaching-plan copy was removed; it still exists. | Medium |
| G21 | Helpful ratings vs. behavior change | No data is collected, which is safe. When we add check-ins, we must not present them as proof the behavior was prevented. | Design constraint |
| G22 | Escalation safety | Crisis is one tap from the hub, but the assist flow has no escalation panel and the check-in cannot route to help. | Critical |

---

## C. Retain / Modify / Remove / Add Matrix

### Retain (no behavioral change)

| Element | Why |
|---|---|
| Four technique runners (`TechniqueRunner.kt`) | Working, offline, tested; only the visual chrome changes. |
| `RecoveryResourceCatalog` data (effort, durations, phases, if-then flags) | Stable content; the engine consumes it. |
| `RiskWindowEvaluator` and its tests | Correct semantics; reused unchanged. |
| `RecoveryStateStore` and friction engine | Correct dual-clock behavior; only its UI moves. |
| `RecoverySession` state machine, first-terminal-wins, stale sweep | INV-5 and FR-5; unchanged. |
| Best-effort write contract and `abandonQuietly` | The intervention must never be blocked by storage. |
| `PrivacyGate` / `GatedContent` / `SecureSurface` / `FLAG_SECURE` | Strong privacy; new surfaces inherit it. |
| `NudgeEnvelope`, `SosViewModel`, rate limiting, confirmation dialog | Consent and failure handling already correct. |
| Local crisis asset + release test pattern | Offline safety; extended, not replaced. |
| History page design (ADR #267) | Recently approved; only new row types are added. |
| Partner mirror format for the four techniques | Coarse and safe; unchanged. |

### Modify

| Element | Change | Reason |
|---|---|---|
| Recovery hub composition | Replace card soup + icon discs with an editorial hierarchy: one primary action, hairline secondary rows, a "Prepare" section. | Cognitive load and visual quality. |
| Hub's "Try right now" card | Replace one fixed suggestion with the Urge Assist entry. | G1, G2. |
| `TechniquesView` | Practice-library framing; editorial rows; feedback ("Not for me") that feeds preferences. | Learning vs. in-the-moment separation. |
| `My plan` page | Read-only ledger plus in-place editor behind the gate; add a "What works for you" preferences section. | G10, G12, and removes the Settings round trip. |
| Risk windows | Move under Recovery -> Prepare; keep the gate. | G12. |
| Friction timer | Move under Recovery -> Prepare; add an inline "Change wait" link in the live session card; keep the gate. | G11, owner's explicit complaint. |
| `UrgeLogSheet` | Restyle; optional category; keep optional intensity; no forced fields. | G5, G10. |
| `CrisisResources` model/parser/asset and `CrisisScreen` | Behavior sections, verified fields, SMS/WhatsApp channels, Kenya-first ordering, new organizations. | G7, G8. |
| `RecoverySession` entity + DAO + repository | Add nullable `category`, `action_id`, `check_in`. Update summary bucketing. | G4, G14, G21. |
| History rows | Show action label and optional category; do not show category on the lock screen. | New event types. |
| Settings | Remove the three recovery index rows and panes. | Single home for recovery. |
| Mirror policy | Keep mirroring the four techniques; do not mirror new assist-only actions. | G14. |
| `RecoveryResourceCatalog.recommended` | Remove the unreachable rating branch; selection moves to the new engine. | Dead code and G2. |
| Docs | Correct the V1.md Settings claim; record this plan and ADRs. | G20. |

### Remove

| Element | Why |
|---|---|
| Tinted circular icon discs and rounded surfaces used as the default row grammar | The owner's AI-slop complaint; global rule bans colored boxes around icons. |
| `recommended(rating, inRiskWindow)` as the hub selector | Replaced by the deterministic engine; the intensity branch was unreachable. |
| Settings recovery panes and index rows (`PANE_COACHING_PLAN`, `PANE_RISK_WINDOWS`, `PANE_FRICTION_TIMER`) | Duplicate navigation; friction timer is the owner's cited extra work. |
| The separate `NavTile` row for Techniques and My plan plus the full-width crisis tile | Replaced by one coherent hierarchy. |
| Dead `sms` data that is parsed but never shown | Either render it or remove it; this plan renders it. |

### Add

| Element | Purpose |
|---|---|
| `UrgeAction` catalog + `UrgeSelectionEngine` (pure Kotlin) | G2, G5, G17. |
| Urge Assist flow: entry, recommendation, alternative, runner/step host, check-in, escalation | G1, G3, G4, G22. |
| Optional category chips and one conditional question (gambling transaction) | G5 without a questionnaire. |
| Prewritten support messages, quiet/no-reply option | G6. |
| Post-lapse support sheet that leaves rules untouched | G13. |
| "What works for you" preferences (can move, quiet preferred, disliked actions) | G10. |
| Behavior-specific actions and safeguards | G5. |
| Directory entries and channel rendering | G7, G8. |
| Delivery/entry shortcut from the block screen straight into Urge Assist | G1. |
| Tests for the engine, check-ins, directory channels, migration | Verification. |
| Documentation updates | G20. |

---

## D. Research-to-Feature Traceability

Evidence labels: **Strong** = directly supported by the cited research or clinical guidance; **Transferred** = evidence from adjacent populations or settings; **Community** = recovery-community experience, not trial evidence; **Hypothesis** = product reasoning, not validated.

| Proposed feature | Research basis | Evidence | Unresolved assumption |
|---|---|---|---|
| Single Urge Assist entry | Executive decision; SOS sequence | Hypothesis (product) | A single entry reduces time to action; unproven in this app. |
| One recommended action, not a menu | "One action, not a compulsory questionnaire"; plan-while-calm/execute-when-distressed | Transferred | One choice may not be better than two; Hick's law was explicitly not treated as proof. |
| "Can't do that" alternative | Feasibility and personalization principles | Hypothesis | The wording and timing of the alternative need device testing. |
| Brief check-in (Better/Same/Worse/Skip) | SOS sequence; adaptation | Hypothesis | Check-in burden may outweigh value for some users; Skip must be frictionless. |
| Deterministic selection | Explainability, no ML, offline-first | Strong (engineering principle) | The exact ranking weights are a product decision, not evidence. |
| Behavior-specific rules | Consolidated behavior sections | Mixed: Strong for gambling safeguards and access restriction; Transferred for cue removal; Community for sponsor contact | Cross-behavior transfer is not established. |
| Gambling transaction interrupt + self-exclusion | Gambling section; GRA process | Strong | The app cannot detect a transaction; it must ask or rely on self-report. |
| Financial safeguards | Research warning against promising unavailable bank features | Strong (caution) | Only GRA self-exclusion is verified in Kenya; no bank-level block is promised. |
| Porn/CSB cue removal, device separation | Porn/CSB section | Transferred/Community | No Kenya-specific CSB organization was verified. |
| Scrolling return-to-task | Social/short-video section | Hypothesis/Community | Ordinary use must not be pathologized. |
| Shopping checkout interrupt and deferral | Shopping section | Transferred | No local payment-access feature exists; only user-directed steps. |
| Gaming stop point | Gaming section | Community/Transferred | Save points and multiplayer sessions vary; the user chooses the point. |
| Partner support request | Accountability section; existing SOS | Community | Partner availability is never assumed; delivery failure must not block offline actions. |
| No auto-sharing of category | Consent and privacy sections | Strong (safety) | The existing technique mirror keeps working; new actions are local only. |
| Post-lapse support, protections preserved | Post-lapse section | Strong (safety) | The existing streak reset is already non-punitive; the follow-up is new. |
| Directory entries | Verified official sources (Section G) | Strong for existence/contacts, not for outcomes | Service hours and current operation can change; every entry carries a verification date. |
| Helpfulness signals feed preferences | Research warns helpful ratings are not behavior-change proof | Strong (caution) | We use them only to reorder suggestions, never to claim success. |

**Evidence boundaries adopted from the research:**

- Urge surfing is offered, not mandated; it is not the universal technique.
- Paced breathing is optional; no claim that it reduces craving for everyone.
- No cold exposure, TIPP, or "hold ice" default.
- No 15-minute guarantee, no "cravings peak at X minutes" claim.
- No assumption that a blocked app launch proves an urge or a diagnosis.
- No inference of being alone, tired, intoxicated, or suicidal from time of day or device activity.
- No single fixed escalation ladder; escalation is a user choice with safety-first paths.
- No automatic disabling of blocking after a lapse.

---

## E. Proposed User Experience

### E1. Information architecture

```
Recovery tab
├── Hub
│   ├── Active friction session (if any): timer, blocked target, override, "Change wait"
│   ├── Primary: "Right now" -> Urge Assist
│   ├── Support: "Reach out to <partner>" (if paired), "Get help now"
│   ├── "Log an urge" secondary row
│   └── Prepare
│       ├── My plan (read; edit behind gate)
│       │   └── What works for you (preferences)
│       ├── Techniques
│       │   └── Technique detail / runner
│       ├── Risk windows (gate)
│       └── Friction timer (gate)
├── Urge Assist
│   ├── Right now (category chips, one action, alternative, support, help)
│   ├── Action host (technique runner or guided step)
│   ├── Check-in (Better / Same / Worse / Skip)
│   ├── Escalation (support, directory, crisis)
│   └── Optional later reflection (never forced)
└── Get help now (directory)
```

History stays in Streaks. The hub carries no history link: owner call 2026-11-08 removed it as redundant (the Streaks tab is one tap away).

### E2. Screen specifications

#### E2.1 Recovery hub

- **Top:** "Recovery" in `headlineSmall`; habit chip or name; one-line subtitle only if needed.
- **Active session block:** the only raised surface on the page. Large countdown numeral in `displayLarge`, blocked app name, override row below it, and a "Change wait" text link that opens the friction editor. When no session is live, no idle card: a single hairline line "No block is holding right now" is enough.
- **Primary action:** a full-width editorial block, not a card with an icon disc.
  - Eyebrow: `RIGHT NOW`
  - Title: "Get one thing to do"
  - Body: "A short action chosen for this moment. You can change it."
  - Primary button: "Start"
  - **Revised 2026-11-08 (owner direction, final):** the hub is three zones, and tone — not hairlines — is what separates them. Zone A (header, habit line, "No block is holding right now" / live session) sits on the plain page surface. Zone B is the "right now" section as a **full-bleed tonal band**: edge-to-edge `surfaceContainerHigh`, content inset to the page frame, containing the eyebrow, the statement in `headlineLarge` (Fraunces), body, one full-width 56dp primary button with a trailing arrow, a hairline, then the support rows and "Log an urge" — all one surface. Zone C (Prepare) returns to the plain surface. The block is never wrapped in its own click target; the single button is the one affordance. The band scrolls with content and has no radius or shadow (tone, not a card).
- **Support rows:** two hairline rows with inline 20dp icons, no containers:
  - "Reach out to <partner>" -> opens the existing confirmation dialog with a prewritten message.
  - "Get help now" -> directory. Error tint on the label only, not a red card.
- **Log an urge:** a single text row with the existing `ic_log_urge`, "Log an urge", "Note it without lifting the block." It stays visible but is not the hero. Its sheet names the streak the row lands in ("Adds to your <habit> streak.") when a habit exists (owner direction 2026-11-08).
- **Prepare:** section label `PREPARE`, then rows: My plan, Techniques, Risk windows, Friction timer. Each has a one-line description. No icon discs; a 20dp inline icon and a chevron.
- **History link:** removed (owner call 2026-11-08); the Streaks tab is the history and is one tap away.

#### E2.2 Urge Assist: "Right now"

- **Header:** "Right now" `headlineLarge` (Fraunces allowed here, the only in-flow editorial serif), plus a one-line "This stays on your device."
- **Category chips:** one horizontally scrollable row, optional, no selection required:
  `Sex or porn`, `Betting`, `Scrolling`, `Shopping`, `Gaming`, `Something else`, `Not sure`.
  Default selection: `Not sure`. Selecting one re-ranks the suggestion immediately. No "Continue" button.
- **If category = Betting:** one conditional question appears under the chips, only when it changes the action: "Are you in the middle of a bet or deposit?" with `Yes`, `No`, `Not sure`. If Yes, the primary becomes "Close the betting app and step away," with self-exclusion as the second step. If the user skips, the general gambling action applies.
- **Primary action block:**
  - Eyebrow: `TRY THIS`
  - Title: the action label, e.g. "Put the phone out of reach."
  - Body: one sentence, no reading-heavy content.
  - "Why this" line in `bodySmall`, muted, e.g. "Because it is late and you asked for something quiet."
  - Primary button: "Start" (or "Call", "Message", "Open" depending on the action type).
  - Text button: "I can't do that" -> replaces the block with a materially different action and a short reason.
  - Text button: "Something else" -> shows two alternates, not a full catalog.
    **Revised 2026-11-07 (owner direction, ADR #270):** the two-alternate cap is superseded. Four ranked alternates show inline, then "Show all N options"; a one-tap "Breathe with me" shortcut sits under the primary when paced breathing is not the lead. The owner rejected the capped flow on the first device pass as too thin to help.
- **Quiet toggle:** a small "Quiet" chip when the action can produce sound or haptics; it disables audio and haptics for that run. It does not change the selected action unless the action is voice-based.
- **Support strip below:** "Reach out" and "Get help now", always present. If no partner is paired, "Reach out" opens the existing pairing guidance, not a dead action.
- **Exit:** the system back arrow returns to the hub. No confirmation, no penalty, no "are you sure."

#### E2.3 Action host

- **Technique actions** reuse the existing runners, restyled:
  - Breathing: one large circle, one line of instruction, Start/Pause, a thin remaining-time rule, no card stack.
  - Delay timer: large numeral, if-then plans inline, no nested cards.
  - Walkthrough: phase number and title, one body paragraph, Back/Next.
  - Values card: one value, Next.
- **Step actions** (e.g., put the phone out of reach): one sentence, a large "Done" button, and "I can't do that." No timer unless the action defines one.
- **Message actions:** open the existing SOS confirmation with a prewritten template. Show the recipient, the editable message, and the same delivery states. On failure, show the offline fallback and keep the self-guided options on screen.
- **Link actions** (self-exclusion, directory): explain exactly what will happen, show the verified contact fields first, and then the external link. Never auto-dial or auto-open.
- **Log-only:** opens the urge log sheet; no technique row. The assist screen remains behind it.

#### E2.4 Check-in

Shown after Done, after a timer completes, or after a support message is sent. Three choices plus Skip:

- "A bit better"
- "About the same"
- "Worse"
- "Skip"

No 0-10 slider, no mandatory note. Each is a single tap.

Branching:
- **Better:** "Keep going" (extend or repeat the same action) and "Finish". Finishing returns to the hub.
- **Same:** offer one materially different action. At most two rounds. After the second, the escalation panel appears.
- **Worse:** stop the current technique, do not offer it again in this episode, and show the escalation panel immediately.
- **Skip:** return to the hub; the session is recorded with `check_in = skipped`.

Record `check_in` on the session row. Never label the episode a failure.

#### E2.5 Escalation panel

- Title: "What would help now?"
- Options, in this order:
  1. "Reach out to <partner>" (prewritten support message).
  2. "Call a support line" (opens Get help now filtered to the current category).
  3. "Get help now" (full directory).
  4. "Stop here" (no penalty).
- If the user reports immediate danger anywhere in the flow, show the emergency block first: 999 / 112, 1199, and "Get help now." Do not run a technique first.
- Never auto-notify a partner. Never assume availability.

#### E2.6 Techniques page

- Title "Techniques"; subtitle "Practice when you are calm. Each takes a few minutes."
- Four editorial rows: title, one sentence, effort label, duration, and a "Practice" text button. No icon discs.
- A "Not for me" text action per row stores the action in the disliked set. A "Show this again" link in the same place clears it. This is the retry-later mechanism, user-controlled.
- Detail pages use the restyled runners.
- The risk-window note stays, but as one line under the title, not a separate card.

#### E2.7 My plan

- **Read state:** three groups (Steps, If-then plans, Values) in an editorial ledger with hairline separators. The item linked to the active window carries a small "now" pill. Empty state: "Nothing here yet. Add one small step you can actually do." with an "Add" button.
- **Edit state:** the existing `CoachingPlanScreen` logic moves here, behind `GatedContent(recovery_gate_title_plan)`. Add, edit, delete, move up/down.
- **What works for you:** a short preferences section (behind the same gate or a separate gate):
  - "I can usually move to another room" (Yes / No / Not sure).
  - "Quiet actions first" (toggle).
  - "Actions I don't want to see" (list of disliked actions with "Show again").
  - These are read by the engine; all optional.

#### E2.8 Risk windows

- Move the existing editor under Recovery -> Prepare. Keep the gate, the at-most-3 rule, the seeded default, and the "switch all off" path.
- Copy unchanged except the intro line, which now says these are used to put low-effort options first.

#### E2.9 Friction timer

- Move the existing editor under Recovery -> Prepare. Keep the gate and the 1 minute to 24 hours bounds.
- Add an inline "Change wait" link in the live session block on the hub. Both routes open the same screen.
- Copy: "The wait before a block lifts on its own. It applies to new sessions. A countdown already running keeps its length."

#### E2.10 Get help now

- Title "Get help now"; intro line; "Not emergency services unless stated" footer.
- Sections, Kenya first:
  1. **Immediate danger:** emergency 999 / 112.
  2. **Mental health and crisis:** Kenya Red Cross 1199, Befrienders Kenya, One2One 1190, Niskize, Find A Helpline.
  3. **Gambling:** GRA self-exclusion, Gamblers Anonymous Kenya, SMART Recovery Nairobi.
  4. **Sex or porn:** Sexaholics Anonymous (international), with a clear note about its specific definition of sobriety and no verified Kenya in-person meeting.
  5. **Gaming:** Gaming Addicts Anonymous (international online/phone).
  6. **Substance use (adjacent):** NACADA 1192, clearly scoped to alcohol and drugs.
  7. **Professional help:** Mathari National Teaching and Referral Hospital, KCPA / KPsyA professional bodies.
  8. **More:** Find A Helpline (directory, not a helpline).
- Each entry: name, one-line description, verified channel buttons (Call, SMS, WhatsApp, Website), hours, cost/eligibility, "Kenya" or "International" label, and "Verified 2026-11-07" in small text.
- Entries with unknown fields show "Not stated" or omit the row. No invented hours.
- Filter chips at the top: All / Urgent / Gambling / Sex or porn / Gaming / Mental health. The current assist category preselects the matching filter.
- Offline: the directory text is local. Phone/dial and web actions need telephony/network; show a one-line note when offline.

#### E2.11 History rows

- Existing rows unchanged for the four techniques.
- New Urge Assist rows: action label, optional category label, outcome, and check-in if present. Category labels only behind the existing privacy gate.
- Summary: add a small "Urge Assist" count if any event has `action_id` and `technique = none`; do not fold these into "Logged only."

#### E2.12 Post-lapse sheet

- Triggered after the existing relapse log completes. The streak reset itself is unchanged.
- Copy: "The block stays on. Nothing else was reset. If you want, there is support here."
- Buttons: "Reach out", "Get help now", "Not now."
- Never disable, weaken, or delete a rule.
- No reflection form during the event. A later "Review what happened" link may open History.

### E3. Visual system (Desert Dawn, light + dark)

**Diagnosis.** The current screens use the same grammar for everything: rounded surface + tinted circular icon badge + title/body + chevron. That is the generic AI pattern the owner rejected. It also flattens hierarchy: the timer, the log action, the technique catalog, and the crisis path all look equally important.

**Direction: "Field Ledger."** Warm sand paper, editorial type, hairline rules, one strong action per screen. The page should read like a well-set page, not a dashboard of widgets.

**Rules:**
- **No icon containers.** Icons render inline at 20dp, tinted with `onSurfaceVariant`, next to the label. The only filled shape on a page is the primary button.
- **Surfaces only for elevation that means something.** The live friction session is the only raised surface on the hub. Everything else is separated by 1dp `outlineVariant` rules and spacing.
- **One accent per screen.** Teal (`primary`) for the primary action and active states. Gold remains reserved for streaks/milestones (existing finding #21). Error tint only for destructive or emergency labels, never as a full red card.
- **Type hierarchy.** `headlineLarge`/`headlineSmall` for page titles; `titleMedium` for row titles; `bodyMedium` for one-sentence explanations; `labelMedium` for eyebrows and metadata. Fraunces is used only for the Urge Assist page title and large timer numerals, not for every heading.
- **Spacing.** 16dp side padding on compact, 24dp on medium/expanded (existing `rememberWindowWidth`). 24dp between sections, 12-16dp inside a block. Touch targets at least 48dp.
- **Dark mode.** Same structure; `surfaceContainerLow` rows, `outlineVariant` rules, no pure black. Check contrast of muted text on `SandDark` (use `MutedDark`, not alpha reductions).
- **Motion.** 150-250ms fades and 8-12dp translations for state changes; no continuous decorative animation. The breathing pacer remains the one intentional loop. Honor `prefers-reduced-motion` equivalent (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`) and reduce the pacer to text-only changes.
- **Copy.** Plain, neutral, no em dashes (ADR #264), no efficacy claims, no clinical labels. "Urge" is the user-facing word. "Practice," not "treatment." "Support line," not "hotline" where the service is not a crisis line.

**Signature move:** the "Right now" block. A large, quiet type composition with an eyebrow, a two-line promise, and one button. No card, no icon badge, no gradient. It is the only thing on the hub that looks like a destination.

**Anti-slop checklist for every recovery screen:**
- No tinted circular icon badges.
- No drop shadows on flat rows.
- No three equal cards in a row.
- No centered empty-state column on a blank page without a next action.
- No generic Material glyphs standing in for a concept when an existing app glyph fits.
- No color-only meaning.
- No emoji.

### E4. Accessibility

- Every actionable row has a single merged semantics node with a descriptive label; icon-only controls get `contentDescription`.
- Timer readouts announce changes at a polite cadence, not every second, when TalkBack is active.
- Check-in choices are radio-like but single-tap buttons with selected state announced.
- The pacer supports reduced motion and a text-only mode.
- All text scales with the system font scale; no fixed-height text containers on the hub or assist screens.
- Test at 200% font and with TalkBack on the X6532.

---

## F. Intervention Selection and Sequencing Rules

### F1. Action catalog

A code-defined catalog (no network, no database) of about 20 actions. Each action:

```kotlin
data class UrgeAction(
    val id: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val effort: EffortTier,
    val modality: Modality,          // PHYSICAL, ENVIRONMENT, SOCIAL, COGNITIVE, BREATHING, TIMER, ACCESS, ESCALATION
    val behaviors: Set<String>,      // porn, gambling, scrolling, shopping, gaming, other
    val runner: RunnerType,          // TECHNIQUE, STEP, MESSAGE, LINK, LOG_ONLY
    val techniqueId: String? = null,
    val requiresMovement: Boolean = false,
    val requiresQuiet: Boolean = false,
    val requiresPartner: Boolean = false,
    val externalTarget: String? = null,
)
```

Initial catalog (MVP):

| ID | Action | Modality | Behaviors | Notes |
|---|---|---|---|---|
| `move_device` | Put the phone out of reach | PHYSICAL | all | The canonical minimum action. |
| `leave_room` | Change rooms | ENVIRONMENT | all | Requires movement. |
| `close_cue` | Close the tab or app | ENVIRONMENT | porn, scrolling, shopping, gambling | Cue removal. |
| `leave_private_space` | Leave the private space | ENVIRONMENT | porn | Requires movement; non-shaming. |
| `open_plan` | Read your if-then plan | COGNITIVE | all | Existing plan items. |
| `read_values` | Read your values | COGNITIVE | all | Existing values card. |
| `breathing` | Paced breathing | BREATHING | all | Existing technique. |
| `urge_surfing` | Urge surfing | COGNITIVE | all | Existing technique; not mandatory. |
| `delay_timer` | Wait 10 minutes | TIMER | all | Existing technique. |
| `walkthrough` | Walk through the urge | COGNITIVE | all | Existing technique. |
| `partner_message` | Message your partner | SOCIAL | all | Requires a pair; consent per message. |
| `support_message` | Send a prepared support message | SOCIAL | all | Same envelope; three templates. |
| `get_help` | Get help now | ESCALATION | all | Opens the directory. |
| `small_task` | Do one small next task | COGNITIVE | scrolling, gaming, other | Return to the intended activity. |
| `log_only` | Just log it | LOG_ONLY | all | Minimum viable action; no technique. |
| `gambling_step_away` | Close the betting app and step away | ENVIRONMENT | gambling | First when a transaction is in progress. |
| `gambling_self_exclusion` | Start self-exclusion | ACCESS | gambling | Shows the verified GRA process. |
| `shopping_leave_checkout` | Leave checkout without paying | ENVIRONMENT | shopping | Never block essentials. |
| `gaming_stop_point` | Stop at a safe point | ENVIRONMENT | gaming | User chooses the point. |
| `return_to_task` | Return to what you meant to do | COGNITIVE | scrolling | Names the intended task generically. |
| `late_night_device` | Charge the phone outside the bedroom | ENVIRONMENT | porn, scrolling, gaming | Requires movement; factual, not an inference about sleep. |
| `safety_check` | Get immediate help | ESCALATION | all | For self-reported immediate danger only. |

### F2. Inputs

| Input | Source | How obtained |
|---|---|---|
| Category | User, optional | Chips on the assist screen, default "Not sure." Never inferred. |
| Entry source | Existing `ENTRY_*` | Block screen, widget, tab, home. Used only for logging, not for guessing intent. |
| Risk window | `RiskWindowEvaluator` | Local time. Orders low-effort actions; never used to infer mood. |
| Late-night flag | Local clock | Changes effort order and adds device-separation actions. Copy is factual ("It is late"), not an inference. |
| Mobility | Preferences | "I can usually move" Yes/No/Not sure. Filters movement actions. |
| Quiet preference | Preferences | Filters audio/haptic actions and sets quiet mode. |
| Disliked/helpful actions | Preferences | Reorders; disliked actions are hidden until "Show again." |
| Partner availability | Pairing state | Affects whether a social action is offered, never whether it is assumed available. |
| In-transaction (gambling) | One question | Only when category = betting; Yes/No/Not sure. |
| Immediate danger | User report | Only direct self-report; never inferred. |

**Never used as input:** time-derived emotional state, app-usage interpretation, blocked-app launch as proof of intent, device sensors, contacts, messages, location, biometrics.

### F3. Algorithm (deterministic, pure Kotlin)

```
select(input):
  1. If input.immediateDanger: return safety_check only.
  2. Build candidate list:
     a. If category is known: category list + general list.
     b. Else: general list.
  3. Safety filters:
     - If mobility == false: drop actions with requiresMovement.
     - If quiet == true: drop actions that require audio; set quiet mode on candidates that support it.
     - If no partner: drop requiresPartner actions.
     - If category != gambling: drop gambling-only actions.
     - If the user is driving (a future explicit input, not inferred): drop eyes-down/interactive actions.
  4. Preference filters:
     - Remove actions in dislikedActions.
     - Demote actions whose last check-in was "worse" in this episode (never show the same action twice in one episode).
     - Promote actions in helpfulActions.
  5. Ordering:
     - Category priority first (the per-behavior list order).
     - Then effort: LOW before MEDIUM before HIGH when in a risk window or late night; otherwise keep priority order.
     - Then variety: if the last action in this episode had the same modality, move a different modality up.
  6. Return primary + two alternates + a reason key.
  7. Reason key resolves to a short string resource; no dynamic personal data.
```

The engine is pure: inputs in, recommendation out. It has no Android dependency and no I/O. The composable passes the inputs.

### F4. Per-behavior priority

| Category | Order (first three) | Rules |
|---|---|---|
| Sex or porn | `move_device`, `leave_private_space`/`leave_room`, `close_cue`, then `open_plan`, `partner_message`, `breathing` | Late night adds `late_night_device`. No shame copy. Normal desire is not pathologized. |
| Betting | If in transaction: `gambling_step_away`, `gambling_self_exclusion`; else `gambling_self_exclusion`, `leave_room`, `partner_message`, `open_plan` | Never imply losses can be recovered. Never promise a bank block. Financial safeguard is the GRA process. Severe distress triggers the safety path. |
| Scrolling | `return_to_task`, `move_device`, `small_task`, `open_plan` | Ordinary use is not a disorder. The goal is returning to the intended activity, not abstinence. |
| Shopping | `shopping_leave_checkout`, `leave_room`, `open_plan`, `partner_message` | Never propose blocking essentials. Deferred purchase is a note to self, not a new list feature. |
| Gaming | `gaming_stop_point`, `move_device`, `small_task`, `open_plan` | The user chooses the stopping point; save points and multiplayer are acknowledged. |
| Something else / Not sure | `move_device`, `breathing`, `open_plan`, `small_task`, `partner_message` | No forced clinical category. |
| Immediate danger | `safety_check` | Emergency numbers first; no technique. |

### F5. Check-in branching and episode limits

- One episode = one assist session from entry to exit.
- Maximum two action rounds. After two "Same" results, escalate.
- "Worse" ends technique offers for that episode and shows the escalation panel.
- "Better" allows one extension, then offers Finish.
- "Skip" exits without judgment.
- The same action is never repeated in an episode after "Worse."

### F6. Escalation rules

- Escalation is offered, never automatic.
- Partner message is sent only after the existing confirmation dialog.
- If delivery fails, the app keeps the self-guided and directory options on screen and says the message was not sent.
- Emergency numbers are always reachable from the assist screen, without unlocking anything.
- No fixed ladder: the panel shows all options at once, ordered by the current category.

### F7. Worked scenarios (expected behavior)

| Scenario | Expected primary | Expected alternatives | Notes |
|---|---|---|---|
| 3 AM, porn urge | `move_device` | `leave_private_space`, `late_night_device`, `breathing` | Reason: "It is late, so this starts with the smallest physical step." No claim about sleep or mood. |
| Betting, in a deposit flow | `gambling_step_away` | `gambling_self_exclusion`, `get_help` | No loss-chasing reflection. Self-exclusion shows the verified process. |
| Scrolling at work | `return_to_task` | `move_device`, `small_task` | Names the intended activity generically. |
| Shopping at checkout | `shopping_leave_checkout` | `leave_room`, `open_plan` | No automatic block of essentials. |
| Gaming past intended stop | `gaming_stop_point` | `move_device`, `small_task` | User picks a safe point. |
| Very low motivation | `move_device` or `log_only` | `breathing` | One tiny action; decline allowed. |
| No partner, no network | `move_device` | `breathing`, `open_plan`, `get_help` (offline text) | Offline actions first; reach-out marked unavailable, not required. |
| Driving | `get_help` (hands-free note) or `delay_timer` | none interactive | No eyes-down exercise. Explicit input only. |
| Immediate danger | `safety_check` | none | 999 / 112, 1199, directory. |

---

## G. Get Help Directory Audit

**Verification method:** official websites, official PDFs, official legal sources, and official meeting finders, checked on 2026-11-07. A directory listing alone was not treated as proof of current operation. Where the official page did not state a field, it is marked "not stated."

### G1. Verified entries

| Organization | Behavior supported | Website | Verified contacts | Kenya / international | Methods | Hours | Eligibility / cost | Online from Kenya | Verification source | Last verified | Limitations |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Kenya Red Cross Society (KRCS) | Mental health / psychosocial / crisis | redcross.or.ke | 1199; +254 703 037 000; info@redcross.or.ke | Kenya | Phone; tele-counselling | 24/7 per 2026 press coverage and LifeLine International; current homepage does not list 1199 | Free, toll-free per the same sources; no referral needed per 2026 coverage | Yes (phone) | KRCS 2020 Annual Report; KRCS COVID-19 and flood updates; The Star 2026-09-10; LifeLine International | 2026-11-07 | 1199 is also a general emergency line; the 24/7 mental-health claim is not on the current homepage, so re-check before release. |
| Befrienders Kenya | Suicide prevention / emotional distress | befrienderskenya.org (currently "Coming Soon") | +254 722 178 177 (call/SMS/WhatsApp); befrienderskenya@gmail.com | Kenya | Phone, SMS, WhatsApp | Mon-Fri 9am-5pm per LifeLine International and Find A Helpline | Free volunteer service; not explicitly stated | Yes (phone/SMS/WhatsApp) | LifeLine International member page; Find A Helpline; official site currently unavailable | 2026-11-07 | Official website is down, so hours and channels come from directories. One directory shows conflicting hours. Confirm before publishing as 24/7 (it is not). |
| One2One 1190 (LVCT Health) | Counselling, information, referrals; broad topics | one2onekenya.org | 1190 (call/SMS); WhatsApp +254 700 121 121 | Kenya | Phone, SMS, WhatsApp, web chat | Open every day 8:00am-10:00pm on `one2onekenya.org/help/call` | Cost/toll-free not stated on the official page; do not claim free | Yes | Official One2One site | 2026-11-07 | Not a Ministry of Health line. 1190 is operated by LVCT Health. Cost and languages are not stated officially. |
| Niskize | Counselling line | niskize.co.ke | 0900 620 800; general +254 718 227 440 | Kenya | Phone | 24-hour counselling per the official call-centre page | Cost not stated; 0900 numbers may be charged; do not claim free | Yes (phone) | Official Niskize call-centre page | 2026-11-07 | Crisis support is not explicitly stated. Cost is unknown. |
| Kenya emergency services | Immediate danger | ca.go.ke (numbering plan) | 999; 112 | Kenya | Phone | 24/7 by definition of emergency service | Toll-free by regulation | Yes | Communications Authority of Kenya, Telecommunication Numbering Plan, May 2025 | 2026-11-07 | 112 is attributed variously to Kenya Police and the National Ambulance Dispatch Centre; both 999 and 112 are official. |
| Gambling Regulatory Authority (GRA) | Gambling self-exclusion | gra.go.ke/self-exclusion-process/ | Email support@bclb.go.ke; toll-free 0800-723-770; ACK Gardens Annex, Bishop Road, 1st Ngong Ave, Nairobi | Kenya | In person; email; phone | Mon-Fri 8:00am-5:00pm | Not stated | Yes | Official GRA page; Gambling Control (Conduct of Gambling Operations) Regulations, 2026 (Kenya Law) | 2026-11-07 | The page still carries BCLB branding and a BCLB email; the site presented an incomplete TLS chain and its footer contained injected SEO spam links on the check date. Treat the domain as official but verify contact details before telling a user to send personal documents. Exclusion runs 6 months to 5 years and cannot be ended early. |
| Gamblers Anonymous Kenya | Gambling | gamblersanonymous.org (international meeting finder) | Nairobi: 02 Koinange St, Saturday 5:00pm, closed meeting. Murang'a: Saturday and Sunday 6:30pm, note "Gamblers Addiction and Recovery Kenya." GA ISO +1 909 931 9056. | Kenya (meetings), international fellowship | In-person meetings | Meeting times above; confirm before attending | No fee stated on the listing; GA groups are self-supporting | Yes (in-person) | Official GA international meeting search, country = Kenya | 2026-11-07 | Meeting details change. The official page does not state cost. Closed meeting means attendees must have a desire to stop gambling. |
| SMART Recovery | Addictive behaviors, family and friends | smartrecovery.org; smartrecoveryinternational.org/meetings | No central phone/email published on the homepage; contact via the site | International, with a Nairobi online meeting | Online meetings, in-person where available | Nairobi meeting #1159: Saturdays 3:00-4:30pm in the device time zone; page calendar metadata shows 18:00-19:30 Africa/Nairobi, which conflicts with the displayed time | Free | Yes (online) | Official SMART Recovery Global meeting page #1159 | 2026-11-07 | The displayed time and calendar metadata conflict; tell users to confirm in the meeting finder. The Notes section says the join link is obtained from the facilitator or notes. |
| Sexaholics Anonymous (SA) | Sex/porn compulsive behavior | sa.org; sa.org/meetings_phone/ | saico@sa.org; +1 615-370-6062; US/Canada toll-free 866-424-8777 | International, no verified current Kenya in-person schedule | Phone/VOIP meetings; in-person where listed | Office weekdays 8am-4pm Central; phone meetings daily | Not stated; 7th-tradition self-supporting | Not confirmed from Kenya | Official SA site and phone-meeting page | 2026-11-07 | The phone page does not confirm international dial-in or access from Kenya. Meetings are closed and for adults only. SA defines sobriety specifically (married: no sex outside a marriage between a man and a woman; unmarried: no sex of any kind). Present as one option with that limitation, not as the default. An ESSAY article shows historical Nairobi activity, not a current schedule. |
| Gaming Addicts Anonymous (formerly CGAA) | Compulsive video gaming | gamingaddictsanonymous.org; cgaa.info | helpline@gaais.org; Zoom access help zoomaccess@gaais.org | International online/phone | Zoom, phone, Telegram | Daily online meetings; schedule on the site | Free; no dues or fees | Yes (online) | Official GAA site and online-meetings page | 2026-11-07 | Website is the fellowship's own; no professional clinical service. International dial-in numbers include US, Canada, Netherlands, UK, Australia. |
| NACADA | Alcohol and drug use (adjacent, not behavioral urges) | nacada.go.ke | 1192; info@nacada.go.ke; NSSF Building Block A, 18th Floor | Kenya | Phone; walk-in | 24-hour toll-free helpline per the official "How to get assistance" page | Free on Safaricom and Telkom per the official page | Yes | Official NACADA page | 2026-11-07 | Scope is alcohol and drugs. Do not present it as a gambling, porn, or gaming service. |
| Mathari National Teaching and Referral Hospital | Mental health treatment | mntrh.go.ke | +254-020-2337694; info@mntrh.go.ke | Kenya (Nairobi) | Outpatient, inpatient, telemental health | General/outpatient psychiatric services 24/7/365 per official pages; child clinic Wednesday 8am-1pm | Not stated; public facility | Yes (in person) | Official MNTRH site | 2026-11-07 | A referral hospital, not a helpline. Use for professional help, not as a crisis line. |
| Kenya Counselling and Psychological Association (KCPA) | Professional counsellors/psychologists | kcpa.or.ke | +254 726 068 933 / +254 724 092 933; kcpa@kenyacounsellingandpsychologicalassociation.org; AGIP House, 4th Floor, Room 422, Haile Sellasie Avenue, Nairobi | Kenya | Directory of practitioners via county representatives; professional body | Office hours not verified | Not stated | Yes (phone/web) | KCPA official PDFs and site | 2026-11-07 | No verified online "find a practitioner" search; contact the association. |
| Kenya Psychological Association (KPsyA) | Professional psychologists | kpsya.or.ke | No phone published in the checked pages | Kenya | Professional body; no verified public directory | Not stated | Not stated | Yes (web) | KPsyA official site | 2026-11-07 | No public finder verified; list as a professional body, not a service. |
| Find A Helpline | Directory | findahelpline.com | Web only | International, 175+ countries including Kenya | Web directory | Not applicable | Free directory | Yes | Official site (powered by ThroughLine) | 2026-11-07 | It is a directory, not a helpline. Listings are maintained by the directory, not by MyExodus. |
| 988 Suicide & Crisis Lifeline | US crisis | 988lifeline.org | 988 call/text; chat.988lifeline.org | US | Phone, SMS, chat | 24/7/365 | Free and confidential | Not a Kenya service | Official site | 2026-11-07 | Keep under the US region only. Do not present it to Kenyan users as a local option. |
| Crisis Text Line | US/CA/UK/IE crisis text | crisistextline.org | US 741741 (text HOME), Canada 686868, UK 85258, Ireland 50808 | US, Canada, UK, Ireland | SMS | 24/7 | Free; message and data rates may apply | Not a Kenya service | Official site | 2026-11-07 | Not available in Kenya. |
| SAMHSA National Helpline | US mental health/substance treatment referral | samhsa.gov/find-help/helplines/national-helpline | 1-800-662-4357; TTY 1-800-487-4889; text ZIP to 435748 | US | Phone, text | 24/7/365 | Free | Not a Kenya service | Official SAMHSA pages (site blocks some automated fetches; cross-checked via official pages and HHS citations) | 2026-11-07 | Referral service, not counselling. US only. |

### G2. Explicitly not published

| Candidate | Why not |
|---|---|
| 1503 "Mental Health Support" (Ministry of Health short code) | The Communications Authority numbering plan (May 2025) lists it as "Not in Service." Do not publish. |
| "1190 is the Ministry of Health mental health hotline" | 1190 is operated by LVCT Health / One2One. Do not attribute it to MoH. |
| A current Sexaholics Anonymous Nairobi meeting | Only historical ESSAY evidence; no current schedule in the official meeting finder. |
| Gamblers Anonymous virtual meetings "open worldwide" | The official virtual-meetings page says attendees may join any virtual meeting in any US state; it makes no worldwide claim. |
| Any 24/7 claim for Befrienders, One2One, Niskize, or GRA | Not supported by the official pages. |
| Any "free" claim for Niskize 0900 or One2One 1190 | Cost/toll-free status not stated officially. |
| Any specialized Kenyan shopping, social-media, or gaming clinic | No verified organization found. Do not invent one. |
| Any bank-level gambling block | The research explicitly warns against promising unavailable financial features. Only the GRA self-exclusion process is verified. |

### G3. Asset schema extension

Extend `CrisisResource` with optional fields, keeping the current flat fields:

```kotlin
data class CrisisResource(
    val id: String,
    val region: String,
    val name: String,
    val description: String,
    val phone: String,
    val sms: String,
    val whatsapp: String,
    val url: String,
    val sourceUrl: String,
    val behaviors: List<String>,   // mental_health, gambling, porn, gaming, substance, professional, emergency
    val kind: String,              // helpline, fellowship, regulator, hospital, directory, emergency
    val hours: String,
    val cost: String,
    val eligibility: String,
    val limitations: String,
    val lastVerifiedAt: String,
)
```

Parser remains tolerant: missing optional fields default to empty; a malformed entry is skipped, not fatal. `CrisisResourcesReleaseTest` is extended: at least one of phone/sms/whatsapp/url; phone/sms/whatsapp digits-only after normalization; URL https; `behaviors` non-empty; `lastVerifiedAt` within one year and not future; unique ids; GLOBAL fallback; regional-first ordering; every entry has a `kind` from the allowed set.

UI channel mapping:
- `phone` -> `Intent.ACTION_DIAL` (no permission).
- `sms` -> `Intent.ACTION_SENDTO` with `smsto:` (no permission).
- `whatsapp` -> `https://wa.me/<digits>` via `ACTION_VIEW`.
- `url` -> `ACTION_VIEW`, https only.

---

## H. Architecture Impact Assessment

### H1. Component view

```
RecoveryScreen (Compose)
  ├── Hub
  ├── UrgeAssistScreen ── UrgeSelectionEngine (pure)
  │     ├── TechniqueRunner (existing)
  │     ├── Step runner (new)
  │     ├── SosViewModel / NudgeEnvelope (existing)
  │     └── CrisisScreen (modified)
  ├── TechniquesView ── RecoveryResourceCatalog (existing)
  ├── PlanView / PlanEditor ── RecoveryPlanRepository (existing)
  ├── RiskWindowsView ── RecoveryPlanRepository (existing)
  └── FrictionTimerView ── RecoveryStateStore (existing)
```

No new service, no ViewModel required at first. The assist controller can live in the composable, calling the pure engine and existing repositories, matching the current `RecoveryScreen` pattern.

### H2. Data model

Extend `RecoverySession` with three nullable columns:

| Column | Type | Values | Purpose |
|---|---|---|---|
| `category` | TEXT | `porn`, `gambling`, `scrolling`, `shopping`, `gaming`, `other`, or NULL | Optional, user-selected. Never inferred. |
| `action_id` | TEXT | an `UrgeAction` id, or NULL | Which assist action ran. NULL for legacy rows. |
| `check_in` | TEXT | `better`, `same`, `worse`, `skipped`, or NULL | Check-in result. |

Schema version: 26. Forward migration adds the three columns. Rollback migration drops them or recreates the table, following the project's existing rollback pattern (do not assume `ALTER TABLE ... DROP COLUMN` works on all minSdk 26 SQLite builds). Export the schema. Add the migration to `ALL_MIGRATIONS`. Instrumented migration tests cover v25 -> v26 and v26 -> v25.

Why extend rather than add a table: the existing session row already models the episode, the technique, the duration, the entry, and the habit. Adding three nullable fields avoids a second log and keeps History, deletion, and backup working.

**Non-technique actions:** insert with `technique = RecoverySession.TECHNIQUE_NONE`, `state = COMPLETED`, `durationSec = 0`, and `action_id` set. They remain excluded from the partner mirror by the existing `technique != 'none'` clause. History must not count them as "Logged only"; the summary buckets rows with `action_id IS NULL` as log-only, and rows with `action_id` as Urge Assist.

**Preferences:** store in `RecoveryPrefs` DataStore, not a new table:
- `canMove: Boolean?`
- `quietPreferred: Boolean`
- `dislikedActions: Set<String>`
- `helpfulActions: Set<String>`

These are local, optional, and resettable per action. They do not affect the partner mirror.

### H3. New and modified files

**New:**
- `recovery/UrgeAction.kt` (catalog model)
- `recovery/UrgeCatalog.kt` (the ~22 actions)
- `recovery/UrgeSelectionEngine.kt` (pure Kotlin)
- `ui/recovery/UrgeAssistScreen.kt` (entry, recommendation, check-in, escalation)
- `ui/recovery/UrgeActionRunner.kt` (step/link/log-only host; delegates techniques)
- `ui/recovery/RecoveryPreferencesScreen.kt` ("What works for you")
- `ui/recovery/PostLapseSheet.kt`
- Tests: `UrgeSelectionEngineTest.kt`, `UrgeAssistFlowTest.kt`, `CrisisResourceChannelsTest.kt`, migration tests.

**Modified:**
- `ui/recovery/RecoveryScreen.kt` (hub IA, new views, technique entry points)
- `ui/recovery/TechniqueRunner.kt` (visual restyle only)
- `ui/recovery/CoachingPlanScreen.kt` (hosted from My plan; gate stays)
- `ui/recovery/RiskWindowsScreen.kt` (hosted from Prepare)
- `ui/recovery/FrictionTimerScreen.kt` (hosted from Prepare; inline link)
- `ui/recovery/CrisisScreen.kt` (behavior sections, channels)
- `ui/recovery/UrgeLogSheet.kt` (optional category, restyle)
- `ui/recovery/RecoveryPrefs.kt` (new keys)
- `ui/recovery/SessionHistoryScreen.kt` (new row types; summary bucket)
- `ui/settings/SettingsScreen.kt` (remove three rows and panes)
- `recovery/RecoveryResourceCatalog.kt` (remove the fixed `recommended` selector)
- `recovery/CrisisResources.kt` (fields, parser, ordering by behavior)
- `recovery/RecoverySessionRepository.kt` (pass category/action/check-in; summary helper)
- `data/db/entity/RecoverySession.kt`
- `data/db/dao/RecoverySessionDao.kt` (summary query respects `action_id`)
- `data/db/AppDatabase.kt` + migrations + schema export
- `assets/crisis_resources.json`
- `res/values/strings_recovery.xml` (new copy)
- `app/docs/MyExodus V1.md`, `app/docs/MyExodus Architecture Decisions.md`

**Unchanged:** `RecoveryStateStore`, `RecoveryTimer`, `RecoveryCountdownTracker`, `RiskWindowEvaluator`, `NudgeEnvelope`, `SosViewModel`, `RecoveryMirror`, the partnership protocol, and the blocking engine.

### H4. Partner sync impact

- No protocol change, no new message type, no new Firestore field.
- Existing technique sessions keep mirroring as `recovery:<technique>:<outcome>`.
- Assist-only actions (`action_id` with `technique = none`) are not mirrored. They are local by construction.
- Category is never transmitted.
- A user-initiated reach-out sends only the confirmation-dialog message. It does not attach the category or the action.
- This preserves the research rule: sensitive category disclosure requires explicit user action, and opening Urge Assist is not treated as a relapse.

### H5. Backup impact

- `RecoverySession` columns are included automatically in the existing guest snapshot because the table is copied. New columns carry through.
- DataStore preferences may not be included in the current backup; they are low-value and can be re-created. Note this in the docs; do not add a backup mechanism for them.
- No change to the backup keys or manifests.

### H6. Privacy and security

- `SecureSurface()` remains on the Recovery tab for all subviews, including Urge Assist and Get Help.
- The gate remains on plan editing, risk windows, friction timer, and History. The interventions and Get Help are never gated.
- No new permissions. No `SEND_SMS`; use `smsto:` intents. No location, contacts, usage access, or accessibility additions.
- No category or action data leaves the device except through the user's explicit reach-out message.
- The directory asset is local, so the safety path works offline.
- External links are https only; phone, SMS, and WhatsApp actions are explicit taps.
- The GRA entry shows the verified process and warns that exclusion cannot be ended early.

### H7. Performance

- The engine is O(n) over about 22 actions, pure, and called on category/selection changes only. No per-frame work.
- No new database work beyond the existing session insert/update. The preferences read is a cached DataStore flow.
- The hub keeps the existing `verticalScroll`; no new lazy list needed.
- History paging is unchanged.

### H8. Compatibility and migration

- v25 databases migrate forward with nullable columns; old rows read as category/action/check_in = NULL and render exactly as today.
- Rollback: recreate the table without the columns if `DROP COLUMN` is not supported, preserving rows.
- The bundled asset and strings change together, so there is no mixed-version asset problem.
- Removing the Settings panes is safe: the pane ids are in-memory constants, not persisted routes. A cold start cannot land on them.
- The block-screen shell still receives `EXTRA_RECOVERY_ENTRY`; add an initial-view hint so it opens Urge Assist. Back from Urge Assist goes to the hub, not back to the block screen.

### H9. Testing strategy

**Unit (JVM):**
- `UrgeSelectionEngineTest`: every category, null category, mobility filter, quiet filter, no-partner filter, disliked actions, helpful actions, risk window, late night, variety rule, escalation after two rounds, "worse" removes the action.
- `CrisisResourcesTest`: parsing, ordering, optional fields, malformed entries.
- `RecoverySessionSummaryTest`: log-only vs. Urge Assist bucketing.

**Robolectric:**
- `UrgeAssistFlowTest`: entry, recommendation, "can't do that," check-in branches, skip, escalation, exit.
- `CrisisScreenTest`: channel buttons render only for present fields; SMS/WhatsApp intents are constructed; no phone button for a web-only entry.
- `FrictionTimerRelocationTest`: the editor is reachable from Recovery and Settings no longer lists it.
- `PrivacyTest`: Urge Assist and Get Help are not gated; plan/risk/friction remain gated.

**Instrumented:**
- v25 -> v26 migration and rollback.
- Mode 1: the full assist flow with a live partner pair (local rig).
- Mode 2: offline, no partner, no network.
- Device: X6532, font scale 200%, TalkBack, dark and light, rotation locked.

**Release:**
- `CrisisResourcesReleaseTest` extended as above.
- Lint alone (ADR #208), never with tests in the same Gradle invocation.

### H10. Acceptance criteria (summary)

A build is not done until:
1. From the hub, "Right now" opens Urge Assist in one tap; from the block screen, it is the landing view.
2. The primary action reflects the selected category and the configured constraints; changing the category changes the recommendation without a Continue button.
3. "I can't do that" produces a materially different action; "Something else" shows at most two alternates. **Revised 2026-11-07 (ADR #270):** four alternates inline, then "Show all N options" for the rest of the ranked catalogue.
4. Check-in records better/same/worse/skipped; "worse" stops the technique and shows escalation; two "same" rounds escalate.
5. The session row stores category/action/check-in; History and the summary render them without counting them as "Logged only."
6. No partner message is sent without the confirmation dialog; delivery failure leaves the offline options usable.
7. Get Help shows Kenya-first sections, verified channels including SMS/WhatsApp, hours and cost only when verified, and a last-verified date.
8. GRA self-exclusion is present with the verified process and caveats.
9. The friction timer is reachable from Recovery in at most two taps and is no longer in Settings.
10. Plan editing, risk windows, and friction remain gated; Urge Assist and Get Help do not.
11. No new permission appears in the merged manifest.
12. No icon discs or colored icon boxes remain on any Recovery page.
13. Dark and light mode both pass a contrast check on muted text.
14. The v25 -> v26 migration passes, and old rows render unchanged.

---

## I. Risk and Safety Review

| Risk | Likelihood / impact | Mitigation | Verification |
|---|---|---|---|
| Sensitive category shown on a lock screen or recents | Medium / high | `FLAG_SECURE` on the whole tab; category chips inside the secure surface; no category in notifications or widget copy. | Device screenshot test with FLAG_SECURE. |
| Partner disclosure without consent | Low / high | Category and action never leave the device; reach-out is user-initiated with a confirmation dialog; no auto-notify. | Code review + flow test. |
| Emotion or intent inference from time/device | Medium / medium | Time only orders effort; copy states facts; no sensor or usage inference; blocked launch is not treated as an urge. | Engine input test: no time-derived mood input exists. |
| Gambling financial harm | Medium / high | Transaction interrupt first; verified GRA self-exclusion; never promise bank blocks; no loss-chasing advice. | Content review + directory test. |
| Financial abuse via "self-exclusion" delegation | Low / high | The app does not transfer money or control; it only explains the official individual process. | Content review. |
| Driving or hazardous context | Low / high | No interactive exercise while driving; explicit future input only; safety path first. | Engine test. |
| Crisis missed | Low / high | Emergency block always one tap away; "worse" and immediate danger route to help; 999/112 and 1199 local. | Flow test. |
| Shame after a lapse | Medium / high | Non-punitive copy; rules stay on; support offered; no reflection during the event; streak copy already non-punitive. | Copy review + flow test. |
| Compulsive checking of the app | Medium / medium | No streaks for Urge Assist, no counters on the hub, no notifications, no "days since" targets for assist events. | UI review. |
| Alert fatigue | Low / medium | No new notifications; in-app only. | Manifest review. |
| Overclaiming therapy | Medium / high | Neutral copy; no efficacy claims; About disclaimer stays; SA and other fellowships described as peer support. | Copy review. |
| Resource becomes stale or closes | Medium / high | Last-verified date on every entry; release test enforces a one-year window; no invented hours. | `CrisisResourcesReleaseTest`. |
| GRA site integrity | Medium / high | Show phone/email/office and the verified process; treat the website as secondary; do not auto-submit personal documents. | Manual verification before release. |
| Directory phone charges | Medium / medium | Show cost only when verified; label unknown; do not say "free" for 0900/1190. | Directory test. |
| New action leaks through mirror | Low / high | Mirror excludes `technique = none`; new actions use it; only the four techniques mirror. | DAO/repository test. |
| Migration data loss | Low / high | Nullable columns, forward and rollback tests, exported schema. | Instrumented migration test. |
| Removal of Settings panes breaks navigation | Low / medium | Pane ids are in-memory; add a redirect if a restored state references one. | Navigation test. |
| Accessibility regression | Medium / medium | 48dp targets, semantics, 200% font test, reduced-motion path, TalkBack pass. | Device checklist. |
| New visual system drifts from Desert Dawn | Medium / medium | Reuse theme tokens only; no hardcoded colors; review both modes. | Theme audit. |

---

## J. Phased Implementation Plan

### Phase 0 - Owner decisions (blocking)

Before code, confirm:
1. Move the coaching-plan, risk-window, and friction-timer editors entirely out of Settings into Recovery -> Prepare. (Recommended.)
2. Keep reading My plan ungated and editing gated. (Recommended.)
3. New assist-only actions are not mirrored to partners; only the four techniques mirror. (Recommended, required for privacy.)
4. Category chip labels: Sex or porn / Betting / Scrolling / Shopping / Gaming / Something else / Not sure.
5. The hub keeps "Log an urge" as a visible secondary row, not the hero. (Recommended.)
6. The block screen's "Open MyExodus Recovery" lands directly on Urge Assist.
7. Which directory entries to include from Section G (recommended: all Kenya entries plus SA, SMART, GAA, Find A Helpline; keep 988/CTL/SAMHSA under US only).
8. Visual direction approval: "Field Ledger" (one primary action, hairline rows, no icon discs).

### Phase 1 - Foundation: catalog, engine, data

**Scope:** pure engine + schema + repository plumbing. No visible UI change except test hooks.

Steps:
1. Add `UrgeAction`, `UrgeCatalog`, `UrgeSelectionEngine` (pure Kotlin, no Android imports).
2. Add nullable `category`, `action_id`, `check_in` to `RecoverySession`; bump schema to 26; forward and rollback migrations; export.
3. Add the DAO summary change: log-only bucket requires `action_id IS NULL`.
4. Add `RecoveryPrefs` keys for `canMove`, `quietPreferred`, `dislikedActions`, `helpfulActions`.
5. Add repository parameters for category/action/check-in; keep best-effort semantics.
6. Write `UrgeSelectionEngineTest` (all scenarios in F7) and migration tests.

**Dependencies:** none.
**Acceptance:** engine tests pass; migration forward/backward passes; existing recovery tests unchanged; no UI change; merged manifest has no new permission.
**Rollback:** revert the branch; the schema migration is additive.

### Phase 2 - Get Help rebuild

**Scope:** directory model, asset, screen.

Steps:
1. Extend `CrisisResource` and the parser with optional fields.
2. Rewrite `crisis_resources.json` with the verified entries and 2026-11-07 dates.
3. Rebuild `CrisisScreen`: behavior sections, channel buttons (call/SMS/WhatsApp/web), hours/cost/eligibility only when present, last-verified text.
4. Add filter chips and category preselection.
5. Extend `CrisisResourcesReleaseTest`.
6. Add a channel-intent test.

**Dependencies:** none; can ship before Urge Assist.
**Acceptance:** every entry has a verified channel; SMS and WhatsApp render where present; no US entry appears under Kenya; emergency 999/112 first; no invented hours; the screen works offline for text.
**Rollback:** revert the asset and screen; the parser is backward-compatible.

### Phase 3 - Recovery hub and navigation move

**Scope:** IA, visual system, relocation of Settings content.

Steps:
1. Rebuild `HomeView` with the "Field Ledger" hierarchy; remove icon discs.
2. Add the "Right now" primary block (routes to a placeholder assist view in this phase).
3. Add the Prepare section: My plan, Techniques, Risk windows, Friction timer.
4. Host `CoachingPlanScreen` from My plan behind the gate; add the read state.
5. Host `RiskWindowsScreen` and `FrictionTimerScreen` from Prepare behind the gate.
6. Remove the three Settings index rows and panes.
7. Add the inline "Change wait" link in the live session block.
8. ~~Add the "See history in Streaks" link.~~ Superseded 2026-11-08: the link was removed as redundant.
9. Block screen: land on Urge Assist when it exists (Phase 4), otherwise the hub.

**Dependencies:** Phase 1 (for the assist route placeholder).
**Acceptance:** friction timer reachable in two taps; Settings has no recovery rows; plan reading ungated and editing gated; risk windows and friction gated; no icon discs; dark and light pass; system back works from every subpage.
**Rollback:** restore the Settings rows; the nav changes are isolated.

### Phase 4 - Urge Assist flow

**Scope:** the full in-the-moment experience.

Steps:
1. Build `UrgeAssistScreen`: category chips, conditional gambling question, primary action block, "Why this," "I can't do that," "Something else," support strip, quiet toggle.
2. Build the action host: technique delegation, step runner, message runner (existing SOS), link runner, log-only.
3. Build the check-in and escalation panel.
4. Wire session rows: start on action launch, finish on Done/Stop with category/action/check-in; use `abandonQuietly` on dispose.
5. Route the block screen directly to Urge Assist.
6. Add the hub primary block.
7. Add "Not for me" / "Show again" on Techniques.
8. Add "What works for you" preferences.
9. Add the post-lapse support sheet after the existing relapse log.
10. Write flow tests and run the device pass.

**Dependencies:** Phases 1 and 3.
**Acceptance:** the Section H10 list items 1-6 and 8-11 pass; no partner auto-send; offline fallback works; check-in branches as specified; the same action never repeats after "worse"; two "same" rounds escalate.
**Rollback:** hide the assist entry; the data columns are additive.

### Phase 5 - History and polish

**Scope:** new row types, summary bucket, accessibility, docs.

Steps:
1. Render Urge Assist rows in History with action and optional category labels.
2. Add the Urge Assist summary bucket.
3. Accessibility pass: semantics, 200% font, TalkBack, reduced motion.
4. Dark/light contrast audit across all Recovery pages.
5. Update `app/docs/MyExodus V1.md` and `MyExodus Architecture Decisions.md`; correct the Settings claim.
6. Update the Obsidian progress note (notes only).
7. Device verification on the X6532: full flow, block-screen entry, widget, offline, partner rig, privacy gate, rotation guard.

**Dependencies:** Phases 2-4.
**Acceptance:** History renders old and new rows correctly; no contrast failures; docs match source; device evidence recorded as PASSED with evidence or BLOCKED.
**Rollback:** revert the UI commit; data remains compatible.

### Implementation order and size

| Phase | Relative size | Can ship alone? |
|---|---|---|
| 1 Foundation | Medium | No (no user-visible change) |
| 2 Get Help | Medium | Yes (safety improvement) |
| 3 Hub/IA | Medium-large | Yes |
| 4 Urge Assist | Large | No (depends on 1 and 3) |
| 5 History/polish | Medium | No (depends on 4) |

Recommended sequence: 1 -> 2 -> 3 -> 4 -> 5. Phase 2 can ship independently if the owner wants the directory fixed immediately.

### Definition of done

- All acceptance criteria in Section H10 pass with evidence.
- Tests added and green.
- Lint run alone and clean.
- Device pass on the X6532 recorded as PASSED with evidence or BLOCKED with the reason.
- Docs and memory updated, with no stale claims.
- No new permissions, no new backend, no self-authorship in commits.

---

## K. Open questions for the owner

1. Confirm the three Settings panes move to Recovery -> Prepare.
2. Confirm assist-only actions are local and not mirrored.
3. Confirm the category list and the one gambling question.
4. Confirm the hub hierarchy: "Right now" primary, "Log an urge" secondary.
5. Confirm the directory scope, especially including KCPA/KPsyA/Mathari/NACADA and keeping the US services under a US region.
6. Confirm whether the widget should gain an "I need help now" action (currently log urge and reach out).
7. Confirm whether an app-icon long-press shortcut for Urge Assist is wanted (manifest static shortcut, no permission).
8. Confirm the visual direction before the UI phase begins.

---

## L. Verification log for this audit

**Read in full or inspected:**
- Research document (converted text, 1,228 lines).
- All files listed in Section A.
- `RestrictionRule`, `RecoveryStateStore`, `RecoverySessionDao`, `RecoverySession`, `RecoverySessionRepository`, `RecoveryMirror`, `NudgeEnvelope`, `SosViewModel`, `SettingsScreen`, `AppShell`, `MainActivity` recovery extras, `BlockerActivity`.
- Theme palette and type scale.

**Verified online on 2026-11-07:**
- gra.go.ke and gra.go.ke/self-exclusion-process/ (content fetched; incomplete TLS chain noted).
- sa.org and sa.org/meetings_phone/.
- smartrecovery.org and the SMART Global meeting #1159 page.
- gamblersanonymous.org international meeting search for Kenya; virtual-meetings page (US-only).
- gamingaddictsanonymous.org and cgaa.info.
- redcross.or.ke (homepage plus official 2020/2024 PDFs).
- befrienderskenya.org (currently "Coming Soon").
- one2onekenya.org and one2onekenya.org/help/call.
- niskize.co.ke call-centre page.
- nacada.go.ke/how-get-assistance.
- mntrh.go.ke.
- kcpa.or.ke and kpsya.or.ke (professional bodies).
- findahelpline.com.
- 988lifeline.org.
- crisistextline.org.
- samhsa.gov national helpline pages (cross-checked; some automated fetches were blocked).
- Communications Authority of Kenya numbering plan, May 2025 (999/112; 1503 not in service).
- Kenya Law, Gambling Control (Conduct of Gambling Operations) Regulations, 2026 (self-exclusion form, scope, durations, register).

**Could not verify:**
- A current official KRCS page listing 1199 hours. The number and 24/7 claim come from official KRCS documents and 2026 press coverage, not the current homepage.
- A current SA Nairobi in-person meeting or international dial-in confirmation.
- The official Befrienders Kenya site (down).
- Cost/toll-free status for 1190 and Niskize.
- A Kenya-specific porn/CSB, gaming, or shopping organization. None was found; none is invented.
- A Ministry of Health mental-health helpline. 1503 is listed as not in service; do not publish it.

---

## M. Stop

This is the end of the investigation, audit, and plan. No application code has been modified. Implementation starts only after the owner approves the plan and answers the blocking decisions in Phase 0.
