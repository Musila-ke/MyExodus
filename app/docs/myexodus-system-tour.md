# MyExodus — Guided System Tour

A trainer, not a spec. The specs are elsewhere; this file teaches **how the pieces connect** and
how to trace a change from UI tap to device behaviour to server. Read this once end-to-end, then
use the "Trace it yourself" exercises when you touch a subsystem. When this tour and the code
disagree, **the code wins** and this file gets fixed.

Canonical sources:
- **Why decisions were made:** `app/docs/MyExodus Architecture Decisions.md` (ADRs #1–#211,
  continuous, append-only; later ADRs amend earlier ones in place with dated notes).
- **What the product promises:** `app/docs/MyExodus V1.md` (features + honest per-feature status).
- **What is built / what is archival:** status header at the top of every `app/plans/*.md`.
- **Live work state:** `app/docs/deep-review-2026-10-22.md` (Resume pointer), the task list, and
  `Obsidian/MyExodus/MyExodus Progress.md`.
- **Agent memory:** `~/.claude/projects/-Users-musila-AndroidStudioProjects-MyExodus/memory/`.

---

## 1. What the system is, in one minute

MyExodus is a local-first Android app that blocks selected apps and websites, tracks usage
against daily limits, records habit streaks and relapse history, and shares chosen activity with
an accountability partner over an end-to-end-encrypted relay. It is distributed as a direct APK
(no Play Store, ADR #15), so Play policy is never a constraint; it targets every OEM skin, not
just the test device; and each flavor would be a product decision (there are none today).

Two properties explain most of the architecture:
1. **Enforcement must work offline and without trust in the UI.** The decision engine, usage
   ledger and wall are local. The partner relay is the only network feature.
2. **The partner must never receive plaintext activity.** Events are encrypted client-side; the
   server routes sealed blobs and tracks dispatch/idempotency, not content.

---

## 2. The map (package → responsibility)

Root (`app/src/main/java/com/example/myexodus/`):

| Package | Owns | Start here |
|---|---|---|
| `MainActivity.kt`, `AppShell.kt`, `ui/` | App shell, 6-tab navigation, dialogs, theme, shared UI atoms | `MainActivity`, `ui/AppShell.kt` |
| `blocking/` | Rule model, decision engine, snapshots, Path A (accessibility) and Path B (foreground poll) enforcement, wall, status/health, boot reconciliation | `BlockDecisionEngine.kt`, `RulesRepository.kt`, `RulesSnapshotPublisher.kt` |
| `blocking/usage/` | Daily-limit accounting: usage events → sessions → ledger → tracker repair, outage detection/repair | `UsageAccountingEngine.kt`, `UsageLedgerRepository.kt`, `UsageGapRepair.kt` |
| `domainusage/` | Website metering: DNS/TLS observation, per-rule daily usage, engine state machine | `DomainUsageEngine.kt`, `DnsObservationStore.kt` |
| `vpn/` | Local DNS sinkhole VPN, packet pump, domain blocklist, VPN lifecycle | `DnsSinkholeVpnService.kt` |
| `accountability/` | Partner pillar: identity/attestation, pairing, crypto envelope, chain, outbox, mailbox sync, notifications, approvals, unpair, DMS/heartbeat, SOS | `accountability/sync/PartnerSyncCoordinator.kt`, `serialization/EventPayload.kt` |
| `accountability/state/` | Second encrypted DB (schema v14): enrollments, chain, outbox, processed events, epochs, unpair state | `AccountabilityDatabase.kt` |
| `streaks/` | Habits, dual-clock streak evaluation, milestones (alarms + catch-up), anomaly log | `HabitRepository.kt`, `StreakTimer.kt`, `MilestoneAlerter.kt` |
| `recovery/` | Recovery sessions, techniques, crisis resources, risk windows, mirror events | `RecoverySessionRepository.kt` |
| `data/db/` | Main encrypted DB (schema v21): entities, DAOs, store factory, migrations | `AppRulesStore.kt`, `AppRulesStoreFactory.kt` |
| `settings/` | Protection checks, Strict/Enhanced mode, setup state, widget settings | `ProtectionChecks.kt`, `StrictModeManager.kt` |
| `widget/` | Glance widget, snapshot writer, action activities | `DesertDawnWidget.kt`, `ProtectionSnapshotWriter.kt` |
| `insights/` | Seven-day local read model over the journals | `InsightsRepository.kt` |
| `di/` | Hilt wiring — no logic | `AccountabilityModule.kt` is the big one |
| `secrets/`, `telemetry/`, `notifications/` | Tink secrets, crash/startup tracing, notification-access helper | — |

Server (`functions/`): Cloud Functions (gen 2) — pairing registration + attestation,
mailbox relay + dispatch/retry/cleanup, SOS send/push/resume, heartbeat, DMS callables + deadline
worker. Rules in `firestore.rules`, indexes in `firestore.indexes.json`.

---

## 3. How a blocked app actually gets blocked

Three independent paths, one decision engine:

1. **Path A — accessibility** (`blocking/BlockerAccessibilityService.kt`). Watches window/activity
   events, normalizes them (`AccessibilityEventNormalizer`), asks `BlockDecisionEngine`, and shows
   the wall via `OverlayController`/`BlockerActivity`. Browser address bars are read through
   `BrowserUrlScanner` (the URL-bar quota path for website limits).
2. **Path B — foreground poll** (`blocking/EnforcementForegroundService.kt` +
   `AppBlockScanner`). Every 1.5 s (`POLL_INTERVAL_MILLIS`) while the service runs, queries the
   foreground app through usage access and asks the same engine. This is the rail that catches a
   cold start whose splash window Path A defers and drops.
3. **VPN — DNS sinkhole** (`vpn/DnsSinkholeVpnService.kt`). Blocks blocked hostnames at DNS and
   feeds observed domains to the metering engine. It is also the release-mode website enforcement
   rail.

**Decision precedence is the contract** (`BlockDecisionEngine`, proven by the 84-cell matrix):
```
expiry in the past        → ALLOW   (Rule 3 first)
today not in the rule's day mask → ALLOW (day scope gates everything)
always-on rule            → BLOCK
now inside the window     → BLOCK   (Rule 1)
usageToday ≥ dailyLimit   → BLOCK   (Rule 2; applies outside the window too)
otherwise                 → ALLOW
```
Every block decision is logged with its reason; the wall re-asserts while the app is foreground.
A standing wall survives HOME and survives activity restarts — only its Close/override path
dismisses it.

**Trace it yourself:** open `RulesSnapshotPublisher` → follow `RulesSnapshot` into
`BlockDecisionEngine.decideApp` → find the three call sites (`BlockerAccessibilityService`,
`AppBlockScanner`, `UsageTickLoop`) and note which one passes `usageTodayMs = null` (both Path A
and Path B do: the quota is accounted by the usage engine, not re-read there).

**Rules and time:** rules are per profile, with days as a 7-char mask, optional start/end minutes
(overnight windows supported), per-rule timezone, optional expiry epoch, an enabled/paused flag,
and `dailyLimitMs` for app rules. The editor validates duplicates and self-blocks; category
presets write to the same repository path as a manual save. A racing double profile insert fails
on the fixed PK — that is the intended idempotent bootstrap (`ensureActiveProfile`).

---

## 4. Usage accounting and the ledger

`blocking/usage/` is the daily-limit truth. Its system of record is the raw usage ledger, not
the tracker row:

- `UsageEventsExt`/`PlatformUsageReader` pull `UsageEvents`; `UsageSessionMachine` accumulates
  foreground spans into **credited** intervals.
- `UsageAccountingEngine` decides what counts and when to block; process-death repair windows are
  anchored at the newest journal observation (not at the death timestamp) and are **uncapped** —
  the old 6-hour repair cap was removed by ADR #103. The `CLOCK_JUMP_THRESHOLD_MS` (6 h) constant
  in `UsageLedgerRepository` is clock-tamper detection, not a credit cap. A **user-stop detector**
  separates deliberate force-stops from OS/background kills.
- `UsageLedgerRepository` persists typed rows (`CREDIT`, `GAP_DETECTED`, carryover/overlap kinds,
  `USER_STOPPED`, …) under a local day key; `UsageGapRepair` repairs dead windows from the
  platform exit record.
- `AccountingDiagnostics`/`AccountingDiagnosticsState` expose honest outage state:
  `AccountingOutagePrompt` offers the battery-exemption route; Home/Settings/notification display
  only unresolved windows (resolved-through watermark + day-split re-join, ADR #149).

The tracker row (`daily_usage_tracker`) is a read model. If it disagrees with the ledger, the
ledger wins; the repair pass re-derives it. Daily-limit **warnings** (10/5-minute heads-up) ride
the same feed (`UsageAccountingEngine` → `ProtectionNotifications`).

**Trace it yourself:** grep a ledger kind constant, then find every writer and reader. Confirm
that a mid-day rule edit calls `onDailyLimitChanged` so warnings reset and `blockedToday`
re-derives in both directions.

---

## 5. Website metering (domain usage)

Website limits are measured by the domain meter, not by the app limit:
- `DomainUsageEngine` consumes DNS observations (`DnsObservationStore`) and, where available,
  TLS ClientHello SNI (`TlsClientHelloParser`) through the VPN tap; `AttributionResolver` maps
  flows to packages; `RuleIndex`/`PublicSuffixList`/`HostNormalizer` reduce hostnames correctly;
  `RuleUsageDaily` stores per-rule daily usage with the local day key.
- Only **browser traffic** is metered for website rules (`BrowserPackages`, ADR #189): in-app
  webviews are out of scope, and the honest limits are recorded in OPD #35. Encrypted Client
  Hello (ECH) hides SNI, so sni_ok stays flat by design (`project_meter_oracle_fixes` memory).
- Engine mode is the §13 gate: **debug `ENFORCE`, release `OFF`** (`BuildConfig.DOMAIN_USAGE_MODE`);
  website limits are structurally gated in release until the owner lifts that (§13/OPD #27).
- The VPN is optional to run; the accessibility URL-bar quota path (`BrowserUrlScanner`) covers
  browser address-bar navigation independently.

---

## 6. The partner pillar, end to end

This is the deepest subsystem. Read it in layers:

### 6.1 Identity and keys
- Anonymous Firebase auth is the default identity. `AccountabilityKeyStore` holds a hardware
  signing key; `AttestationVerifier` verifies a PKIX chain to pinned Google roots (TEE/StrongBox,
  purpose SIGN, origin GENERATED). The server re-verifies attestation during pairing
  (`registerPairing` + `attestation.js`). **Software-key emulators cannot pair** — this is an
  architectural wall, not a harness gap.
- Pairing uses a typed Crockford code and a visual/QR exchange; per-pair agreement keys are
  derived (ECDH + HKDF, `Hkdf.kt`) and wrapped by `EventCipher` (CEK). Per-pair keys were
  introduced by ADR #106; a device-level key survives only while any enrollment remains.
- Identity epochs (`IdentityEpochStore`) and `BiometricEnrollmentMonitor` invalidate pairings
  when the enrollment set changes; `IdentityMigration` handles rotation. Orphaning by anonymous
  uid rotation is diagnosed with five guards (ADR #125) — do not simplify them.

### 6.2 Event contract
Two contracts ship side by side:

- `accountability/serialization/EventPayload.kt` — the legacy envelope body (wire **schema v2**
  with v1 read support): payload types `activity`, `deletion_request`, `sync_request`,
  `approval_request`, `approval_response` (+ plan progress), with activity types
  `streak_updated`, `relapse_recorded`, `milestone_reached`, `coaching_step_updated`,
  `motivational_message`. Wire v2 adds habit label, ended days and milestone day.
- `accountability/serialization/AccountabilityEventWire.kt` — the closed, versioned
  accountability body (ADR #196): kinds `ACCOUNTABILITY_EVENT`, `MONITORING_STATUS`,
  `GAP_MARKER`, `RULE_CONTROL`; categories `BLOCKED_APP_ATTEMPT`, `BLOCKED_DOMAIN_ATTEMPT`,
  `MONITORING_PERMISSION_CHANGED`, `MONITORING_COMPONENT_UNAVAILABLE`,
  `DEVICE_STATUS_CHANGED`, `RULE_CHANGED`. No free-text field exists except the UUID-format
  `eventId`; unknown keys are rejected, so a package name, URL or app label cannot ride.

Partner-visible **rule lifecycle** ships on `RULE_CONTROL` (ADR #226): a rule UUID plus one
closed verb (`CREATED`, `ASSIGNED`, `UNASSIGNED`, `UPDATED`, `DISABLED`, `DELETED`) and an
occurrence minute — never a target. Rows are journaled in the same Room transaction as the rule
write and delivered only to the rule's assigned pair.

**Still absent:** uninstall detection (`TARGET_APP_REMOVED`, plan req 17), backup-partner
release (`BACKUP_DELIVERY_RELEASED`, req 14/15), violation-storm summaries (req 4/5), and
daily streak summaries (req 18). Do not claim they exist; the remaining delta is recorded in
plan Appendix D.4.

### 6.3 Consent and routing
- Consent is a per-`(pair, habit)` matrix (`share_streak` ∧ the specific sharing flags in
  `pair_streak_sharing`), plus availability consent with a convergence marker
  (`availability_consent_pending`, schema v20). A message with no visible context is not sent.
- `RULE_CONTROL` routing is **not** gated by those activity flags: assigning a partner to a rule
  is the subject's own visibility choice for that route (plan Appendix D rows 21/22, ADR #226).
  `dueRuleChangesForPair` is the only query that reads the rule journal, and the legacy
  fan-out loop consumes monitoring categories only — an unassigned rule has no route and its
  mutations stay local. There is no backup recipient yet (req 14), and no `UNSUPERVISED`
  partner is ever notified on unpair (there is no eligible recipient).
- Fan-out is per pair: the journal row is written once, then mirrored per enrollment; each pair
  has its own mailbox, sequence and chain. `MailboxMessageHandler`'s notify lambda is keyed by
  `pairId` and resolves that pair's partner display name, so N partners get N correctly-named
  notifications. Per-pair tray ids (ADR #119) complete the routing contract.

### 6.4 Transport
- `Outbox` (durable, sequence-ordered) → `MailboxOutboxDrainer` → Firestore `relayMailboxMessage`
  → dispatch to the recipient's installations (`relay-dispatch/*`, at-least-once) → FCM wake
  (`PartnerMessagingService` → `FirestoreMailboxReceiver`) → `MailboxEventProcessor`:
  dedupe (`ProcessedEvent`) → verify signature → decrypt → chain append (`ChainEvent`/`ChainHead`)
  → ACK.
- The sender's "sent" means server acceptance; delivery is proven by the ACK path. Do not conflate
  them (ADR #115 deviation).
- `PartnerSyncCoordinator` orchestrates passes: reconcile, drain, pull, backfill, heartbeat,
  live-sync kick. WorkManager runs it periodically; `PartnerLiveSync`/send-kick adds realtime
  behavior for an open app.

### 6.5 Approvals, unpair, deletion
- Approval requests use their own mailbox message type and notification channel; responses are
  signed. StrictMode's partner-approval gate remains `false` by recorded decision.
- Unpair (`BilateralDeletion`) is bilateral shred: local shred is unconditional and immediate;
  remote erasure is acknowledgment-based and honestly reported. `UnpairState` has two facts:
  partner-device ack (`confirmed_at`) and server revocation (`revoked_at`, v14); `TERMINAL`
  requires both, the 7-day timeout reports `LOCAL_ONLY_TERMINATED`, never a false claim. The
  terminate write retries each pass and cancels pending envelopes (`CANCELLED_BY_REVOCATION`).

### 6.6 Availability/heartbeat (DMS)
- `PartnerHeartbeatClient` persists-before-send; `submitHeartbeat` is idempotent by `(uid,
  installId)` generation+1, 30 s server floor, `next_deadline_at` +6 h. Liveness piggybacks the
  enforcement FGS (10-min ticks) with a 15-min WorkManager fallback.
- Availability consent is a server callable (`setAvailabilityConsent`); status reads go through
  `readPartnerStatus`; ingestion outages through `recordIngestionOutage`. `dmsDeadlineWorker`
  runs every 5 minutes, writes a transactional notification outbox, and trips a circuit breaker
  (`config/dms.dispatch_hold=true`) on fleet-wide late bursts. All callables enforce App Check.

### 6.7 Notifications
`PartnerNotifications.kt` owns the immutable channels (`partner_accountability_events_v1`,
`approval_requests`, `partner_nudges_v3`, `sync_status`, `availability_alerts_v1`; legacy ids
exist only for cleanup/upgrade, and the retired `checkin_reminders_v1` is deleted at startup
because check-in is a silent probe, ADR #223). Channel importance is immutable — a spec change
needs a new channel id. The Partner tab's test alert is a **local
self-test for one device**, not a partner delivery proof.

**Trace it yourself:** pick `milestone_reached`. Follow `HabitRepository.stampAchievedMilestones`
→ journal → `EventPayload` encode → outbox → drainer → server relay → receiver → processor →
`MailboxMessageHandler` → notification. Then follow the consent gate backwards and note where a
missing consent stops the mirror (not the local journal).

---

## 7. SOS (reach out)

`accountability/sos/`: `NudgeRepository` builds a sealed envelope (`NudgeEnvelope`), calls the
`sendNudge` callable (App Check + server kill switch `config/features.partnerSosEnabled`, read
inside the transaction), writes a durable queue doc, and FCM pushes to the chosen partner's
installations. `NudgeInboxRepository` renders the card, acknowledge writes `ackedAt`, clears the
tray. The Reach-out FAB is structural (Home + live pair) — the client Remote Config gate was
removed (ADR #158). The scheduler `resumePendingNudges` and leased queue retries keep delivery
honest. The inbox test (`NudgeInboxDeviceInstrumentedTest`) proves the pull path independent of
FCM.

---

## 8. Habits, streaks, milestones, widget, insights

- **Habits** (`streaks/HabitRepository`): a habit has a label and an anchor; the streak is
  evaluated by `StreakTimer` with **two clocks** (wall + elapsed/monotonic) so a clock change or
  reboot cannot inflate it. `HabitState` carries the current run, best run, milestones and
  relapses. Relapse is explicit and confirm-dialog driven; it resets the live run and stamps the
  record.
- **Milestones** (`MilestoneScheduler`/`MilestoneAlerter`): one alarm per day 7/30/90 per habit,
  PendingRequest identity per habit, idempotent atomic claim. Reboots wipe alarms;
  `MilestoneBootReceiver` re-derives them and, with `HabitRepository.catchUpAll()`, delivers any
  achieved-but-unclaimed milestone at boot **and on app foreground** (`StreaksViewModel.refresh`,
  ADR #104/#164). POST_NOTIFICATIONS denial does not consume the claim. The Home milestone
  ladder (`ui/home/MilestoneSectionCard`) is permanent — the old dismissible splash was
  superseded on 2026-09-29.
- **Recovery** (`recovery/` + `ui/recovery`): technique catalog, risk windows, sessions
  (`RecoverySessionRepository`), crisis resources, and mirror events. Logging an urge is not
  bypassing a block; only the friction/cooldown override path retires a wall. The recovery
  timer is a persisted deadline + UI tick, not an exact alarm.
- **Widget** (`widget/`): Glance widget reading a frozen snapshot (`ProtectionSnapshotWriter`,
  `WidgetSnapshot`), icon-only controls, ContainerLow canvas. `updatePeriodMillis` is 30 min as
  the backstop (ADR #145); the update also runs on protection changes. The 10-child Glance cap
  is a hard constraint. Circle content includes a relapse circle (v2.2) and the streak never
  renders 0 (ADR #128).
- **Insights** (`insights/`): last seven **local** days (today + the 6 before it, stated on the
  page), one bounded store pass over attempt log, usage ledger day totals (credit kinds only),
  recovery sessions and habit records. The usage card is "Time in limited apps" — the ledger
  credits only apps with a daily limit; website-limit usage lives in `domainusage/` and is not
  shown here. Colour is never the only encoding; empty sections say so; store failure renders
  Unavailable with retry, never zeros. It is local-only and transmits nothing. ADR #210, #218.

---

## 9. UI shell, theme, and content rules

- `MainActivity` gates a first-run setup wizard when a required mechanism is missing, then hosts
  `AppShell` with a six-tab `NotchedNavBar` (the bar is full — new surfaces are subpages of dense
  hubs, per owner ruling). Navigation state is `rememberSaveable`; spokes use `BackRow` +
  `BackHandler`; dialogs are dialogs, not routes, when editor state must survive.
- Theme is **Desert Dawn**; typography adapts by window size class (standing rule), keeping `sp`;
  shared atoms (`StatusPill`, `NavTile`, `LinkRow`, `ScheduleFields`, `SecureSurface`) keep pages
  in one grammar. New UI follows the existing atoms rather than inventing a style.
- Error copy follows the three-question rule (what happened / why / what to do); backend
  internals never reach the screen. Empty, loading, error and success states are required, not
  optional — the Home milestone card is the model: title with the first frame, skeleton values,
  honest empty with CTA, error with retry.
- `ui/AppLabels.kt` resolves installed app labels (falling back to package strings when an app is
  gone). Privacy toggles hide counts with the list.

---

## 10. Security posture (tour level)

- **Local data:** `AppRulesStore` and `AccountabilityDatabase` are SQLCipher-encrypted; the DEK is
  wrapped by an Android Keystore key (`AppRulesStoreFactory`, `SecretsRepository`). Backup is
  disabled and exclusion rules are in the manifest; DB recovery path is explicit.
- **Partner data:** sealed client-side (CEK per pair), signed events, hash-chained per pair,
  server-side dedupe/idempotency. The relay is identity-blind to content.
- **Device integrity:** hardware attestation for pairing; App Check enforced on Firestore
  (identitytoolkit deliberately unenforced so anonymous sign-in can start); debug App Check token
  path only on debug builds for the emulator.
- **Surface:** exported components are enumerated and minimal; debug QA activities/receivers live
  only in the debug source set and are absent from the merged release manifest; cleartext is
  debug-only. Release signing is not configured yet — a known pending cutover, not an oversight.
- **Secrets/ops:** `google-services.json` and debug App Check secrets are console-registered and
  never committed; `gitleaks`/`opengrep`/Trivy recipes are in `gate25_tooling` memory.
  `firestore.rules` is deny-by-default with explicit per-feature matches; rules tests run in the
  emulator.

---

## 11. How to verify without fooling yourself

- **JVM tests:** `./gradlew :app:testDebugUnitTest` — aggregate from
  `app/build/test-results/testDebugUnitTest/TEST-*.xml`; never trust a piped tail's exit code.
  Current baseline: 225 classes / 2,010 tests / 0 failures.
- **Lint:** `./gradlew :app:lintDebug` **alone** (ADR #208 — combining it with tests in one
  invocation crashes the lint worker). "No issues found" is the gate.
- **Instrumentation:** `:app:assembleDebugAndroidTest` then the relevant instrumented suites; the
  AVD is sanctioned for API 37 and for debug-harness legs that do not need attestation.
- **Debug QA harness** (debug source set only): `DeviceVerificationActivity` suites
  (`functional|latency|multiwindow|browser|fgs|fgs-down|sync|seed-ui|clear-ui|usage-seed|
  usage-clear|appblock-seed|appblock-clear|overlay-dismiss|reenable`). The functional suite needs
  a host driver reacting to `MARKER: LOCK/WAKEUP/UNLOCK`; without it the overlay leg fails by
  design, not as a product defect.
- **Blocking matrix:** `scripts/blocking-matrix-sweep.sh <serial>` — 84 cells over shape × window ×
  quota × day × expiry, verdicts from fresh engine evidence; resumable with `SKIP=n`; writes a
  TSV. Each cell resets the scene through the real Close button.
- **Domain usage:** `scripts/domainusage-ci-checks.sh`, `domainusage-device-test.sh`,
  `domainusage-pf-run.sh` (PF gates), and the test server/probe scripts.
- **Device rules:** physical X6532 first, `install -r` only, never uninstall; API-37 AVD is the
  sanctioned exception (and the fallback when the phone can't be reached — state emulator-only
  evidence honestly; pairing/attestation legs are impossible there). Verify the current screen
  before every tap; use uiautomator bounds in physical pixels, not eyeballed screenshot coords.
- **Server:** Functions Jest suites, Firestore emulator rules tests, then deploy; verify
  deployment by reading back the deployed function/scheduler/ruleset state. Kill switches are
  server-side (`config/features.*`, `config/dms.dispatch_hold`).

---

## 12. Known limits you must not paper over

- **Partner escalation alerts are not built:** uninstall, essential-permission revocation,
  rule add/edit/delete, violation, per-rule partner assignment and primary-partner routing are
  planned (OPD #32(iii), escalation memory), not shipped. Existing partner events are the five
  activity types, approvals, plan progress, deletion/sync, SOS nudges and DMS availability.
- **No two-device pairing on the emulator** — attestation wall on both sides. A real two-phone
  leg needs two physical devices; the phone leg is BLOCKED when the X6532 is unreachable.
- **Release website limits are OFF** (`DOMAIN_USAGE_MODE`), by §13 gate; debug enforcement is the
  proven path. The honest limits disclosure is OPD #35.
- **Release signing and the GitHub Releases channel** are pending (ADR #171); no remote exists.
  OTA is a draft plan, not code.
- **Auth/sign-in friction** is a known workstream (OPD #36): anonymous-only today; the
  uid-preserving upgrade and key-recovery design are open.
- **Data sovereignty/export, quarantine/solo mode, rollback protection, escrow** are unbuilt
  (V1 feature table #21/#24/#28/#33) — the table is the honest source.

---

## 13. Where to start on a new task

1. Read the task's plan status header and the relevant V1 section; check the ADR ledger for
   amendments that supersede them.
2. Search before building: `grep` for the feature's nouns across `app/src/main` and `functions/`;
   the repo prefers extending the shipped seam over a parallel one.
3. Identify the boundary: UI → repository → store/engine → device or server. Keep decisions at
   the layer that owns them (the UI never re-derives streaks; the server never sees plaintext).
4. Write the failing test first for logic that can run on the JVM; use the debug harness for
   device-only behaviour; leave one runnable check behind.
5. Before declaring done: plan requirements checked one by one, verification evidence gathered
   (test names/runs, device logs), docs (`app/docs/`), vault progress note and memory synced, then
   commit with no AI attribution.

## 14. Cold-start resume

If a session was cut: read the **Resume pointer** at the top of
`app/docs/deep-review-2026-10-22.md`, then the task list, then the last three entries of
`Obsidian/MyExodus/MyExodus Progress.md`. The Resume pointer names the exact in-flight task,
device state, and next action. Never resume from a remembered summary when those files disagree —
the files win.
