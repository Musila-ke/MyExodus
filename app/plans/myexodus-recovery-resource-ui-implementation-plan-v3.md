# MyExodus — Recovery Resource UI — Implementation Plan v3 (scoped: recovery delta only)

Status: **COMPLETE 2026-09-19** (W1–W8 built; JVM suite green 721/0/0/0/97-classes at close, since grown to 725 with the ADR #64 liveness tests; docs synced per ADR #61; X6532 device pass with AppRulesStore v8→v9 migration verified). Date: 2026-09-19. Supersedes `myexodus-recovery-resource-ui-implementation-plan-v2.md` **for build purposes**; v2 is kept as archive (its own §16 OQ-1/OQ-2 record that it was authored without the repo).

**Why v3 exists.** v2 designs a complete second partner stack — mutual-QR pairing, Tink HPKE/ECDSA keysets, `pairs/{pairId}/events` Firestore layout, TTL, new outbox/WorkManager rails, new dashboard, new notifications. That stack **already ships** as Partner Pillar v2 (ADR #55, deployed 2026-09-18/19: rules live, Cloud Functions live, App Check enforced ADR #57). v3 keeps only the recovery-resource delta — V1 Req 8/10/19/20 + Task 3.1 + NFR 492 — and routes every partner-facing need through the shipped rails. §6 is the line-by-line audit proving nothing from v2 was dropped silently.

---

## 1. Scope

**Satisfies (authority: `app/docs/MyExodus V1.md`):**
- **Req 8** — block screen "manage urge"/"log urge" tap opens the recovery resource UI (max 1 tap, NFR-492). Block-screen button + widget "Manage urge" already exist and land on Recovery; the screen's *content* is what's missing.
- **Req 10** — a successful urge log MAINTAINS the restriction and routes the user to the device home screen (a log is not a bypass).
- **Req 13** — friction timer: **BUILT** (RecoveryTimer/RecoveryCountdown + Recovery tab + override). Untouched by this plan.
- **Req 19** — offline time-of-day willpower mapping; low-willpower windows get low-effort grounding first. Fully offline, local mapping.
- **Req 20** — Coaching Plan page (user-defined ordered emergency steps, saved locally, shown during interception) + mirrored on the partner dashboard; offline display edge case.
- **Task 3.1** — recovery resource UI with un-skippable friction timer (built), willpower-adaptive content mapping, escape hatch (built).

**Adjacent, NOT covered here (decide separately):** Req 15 partner motivational messages, Req 14 relapse reflection — not in the v2 plan either (see OPEN-6).

## 2. Already exists — reuse, do not rebuild

| Capability | Rail (files) | Status |
|---|---|---|
| Pairing (SAS/QR exchange, signed transcript, server registration) | `accountability/enrollment/*`, `accountability/sync/{PairingExchange,PairingRegistration,QrCameraDecoder,QrChunkCodec,QrReassembler,AnimatedQrRenderer}.kt`, `registerPairing` CF, `pairing_tokens` rules | ADR #41; deployed |
| Identity keys + signing | `accountability/crypto/{AccountabilityKeyStore,AccountabilitySigner,IdentityEpochStore}.kt` (TEE, biometric signing window) | ADR #46 |
| E2EE envelope + payload vocabulary | `accountability/serialization/{Envelope,CanonicalCbor,EventPayload}.kt`, `crypto/EventCipher.kt` | ADR #44 |
| Outbox → relay → receive pipeline | `accountability/state/Outbox*`, `sync/{MailboxOutboxDrainer,PartnerSyncWorker,PartnerSyncCoordinator,FirestoreMailboxReceiver,MailboxMessageHandler,MailboxEventProcessor,MailboxRejectionChecker}.kt` | ADR #44/#45/#53 |
| Relay backend + rate limits + retention | `mailboxes/{uid}/messages` + `acks`, `functions/` (relay/dispatch/sweep/cleanup, 7d/30d retention) | deployed; rules live |
| Kill switch | `PartnerRolloutGate` (Remote Config `partner_push_registration_enabled`) | ADR #56 |
| Partner dashboard ("Recent activity", chain status, approvals, unpair, wipe card) | `ui/partner/{PartnerScreen,PartnerDashboardSource,PartnerPresentation}.kt` | ADR #47 |
| Notifications | `PartnerNotifications` (channels `partner_alerts` HIGH, `approval_requests` HIGH) | ADR #51 |
| Unpair / shred / local-wipe detection | `BilateralDeletion.kt`, `deletePairData`, `LocalWipeDetector.kt` | ADR #49/#52 |
| DB encryption + key-unavailable handling | `AppRulesStore` (SQLCipher, Room 3, v8) via `AppRulesStoreFactory`, `DatabaseHealthStore` states | secure-persistence plan |
| Backup/transfer exclusion | `allowBackup=false` + `data_extraction_rules.xml` + `backup_rules.xml` | manifest, verified present |
| Biometric prompt pattern | `MainActivity.signWithBiometric` (BIOMETRIC_STRONG + CryptoObject — signing only; the gate pattern is reusable, the CryptoObject is not) | Step 13 |
| Recovery session rail (friction timer, resume, override, block-screen entry, widget entry) | `blocking/recovery/{RecoveryStateStore,RecoveryTimer,RecoveryCountdown}.kt`, `ui/recovery/RecoveryScreen.kt`, `BlockerActivity.openRecovery()`, `BlockOverlayView` button, widget `widget_manage_urge` | V1 UI workstream; device-verified |
| Coaching storage entity | `data/db/entity/CoachingStep.kt` + `CoachingStepDao` (step_order, action_text, associated_trigger_window) — schema v8, **unused by any UI** | reuse for W3 |
| Telemetry rail | `CrashReporting` non-fatals + `PartnerSyncTelemetry` | ADR #54 |

**Explicitly NOT building (v2 sections retired as duplicates):** v2 M3 (crypto core), M4 pairing machinery, M5 relay/outbox/rules/TTL/notifications, §13 backend deployment, and all Play-flavor items — there is no Play Store distribution (ADR #15; Play targets are dead constraints). FCM push already flows through the shipped mailboxes + relay functions.

## 3. The delta to build

### W1 — Recovery resource screen content (Req 8/19, Task 3.1)
The surface exists (`RecoveryScreen` tab; entry = block-screen button `overlay_open_recovery` → starts friction session → lands on Recovery tab; widget "Manage urge" → same tab). Add the missing content, driven by the existing session state:
- **Suggested activities section** — ordered by W4's willpower mapping (low window → low-effort grounding first: paced breathing, calming audio if it ships (OPEN-5); high/normal → coaching steps, values, journaling-style prompts).
- **Coaching steps list** — W3's steps, inline, offline.
- **Breathing pacer** — 4 s in / 6 s out; honor system "remove animations" (static text pacing); optional haptic tick (default off); rotating prompts every 30 s. Copy in `strings_recovery.xml`; no efficacy claims; "self-help tool, not medical treatment" footer exists on Settings→About. *(Footer **built 2026-09-23**, ADR #82: this line was true of the plan and false of the code — no About surface and no such string existed until Settings became an index with an About row. `AboutScreen` + `settings_about_disclaimer` now carry it.)*
- **Crisis link** — W6 → crisis screen ("Need urgent help?").
- **Urge-log action** — W2.
- **Timer** — reuse `RecoveryTimer`/`RecoveryCountdown` as-is. No new countdown math.
- **Open decision OPEN-1 (entry shape):** v2 M1 wants a dedicated `InterventionActivity` (PERF-1/2 targets). Default = **extend the existing tab** (max reuse; the 1-tap requirement is already met; add the dedicated activity only if a recorded latency measurement says the tab is too slow). The "Pause" launcher shortcut and Home button from v2 M1.1 build only under option A (or as a small Home card linking to Recovery under option B).

### W2 — Urge log + Req 10 exit (Req 10)
- Capture on the recovery screen: optional intensity 1–10 (skippable, one tap), technique used, outcome. Fields per OPEN-2. No free text ever leaves the device in the mirror (INV-13 analog).
- Submit → persist → **restriction stays active (no unlock)** → route to the device home screen (`Intent.ACTION_MAIN` + `CATEGORY_HOME` — verify on X6532; OEM home intents are the known trap).
- Distinct from the existing override (which retires the rule) — copy must not conflate them.

### W3 — Coaching Plan editor + display (Req 20, user half)
- **Reuse `CoachingStep` + `CoachingStepDao`** (entity has existed since schema v8, unused). Ordered steps, ≤5, ≤200 chars each (v2 FR-6 limits); `associated_trigger_window` semantics = OPEN-4 (default: display all steps, highlight matches).
- Editor reachable from Settings (new section) — list, add/edit/delete, reorder. Offline; local DB only.
- Display during interception = W1 section. Offline edge case is automatic (local reads).

### W4 — Willpower windows + resource catalog (Req 19)
- Pure Kotlin domain (no Android imports): `RiskWindowEvaluator` — `LocalDateTime` from `ZoneId.systemDefault()` at use time; `[start,end)` in minutes-of-day; overnight wrap (`t >= start || t < end`); day mask (bit0=Mon…bit6=Sun) applies to the day the window *starts* (after-midnight part uses previous day's bit); recompute every use, no skew detector.
- ≤3 windows, default one 20:00–06:00 all days, editable/disable-able; persisted in new `risk_window` table (W7).
- Static catalog with effort tiers in code (no table); low window → low-effort first. Contents are the user's research deliverable (coping techniques valid across addiction types — not presumed); implementation shape = phase/step data, one source of truth for the pacer's animation+timer.

### W5 — Partner mirror (Req 20, partner half) — on the shipped transport
- **New event through the existing rails**: extend the `TYPE_ACTIVITY` text convention (`"blocked:<pkg>"` / `"override:<pkg>"` → add `"recovery:<tech>"` + outcome, e.g. `"recovery:urge_surfing:completed"`) so `PartnerPresentation.parseActivityText` + the dashboard "Recent activity" section + `partner_alerts` notification all extend in place. (Alternative: new `EventPayload` type — more surface for no gain; OPEN-3, default = text prefix.)
- **Emission point:** session end (completed / abandoned / interrupted-with-reconciled-start), via the existing drain pattern — a row with `event_sequence == NULL` is linked by `PartnerSyncCoordinator` under the signing window. W2/W7's `recovery_session` rows with `event_sequence IS NULL` and a peer active are the queue. **[Post-v12 mechanics, ADR #111 (2026-09-28): the queue is now per pair — `recovery_mirror_link(session_id, pair_id, sequence)` decides pending (`event_sequence IS NULL AND NOT EXISTS(link for :pairId)`), so a finished session fans out to EVERY enrollment, one delivery per pair; `event_sequence` survives only as the pre-v12 legacy marker (such rows are excluded for every pair).]**
- Coarse data only: technique id + outcome + time. No intensity, no notes (INV-13).
- Unpaired / paused / kill-switch-off → row stays unlinked; no error. Dashboard copy stays non-shaming.
- **No rules change, no deploy gate** — the mailbox already carries arbitrary payloads ≤4096 B.

### W6 — Screen protection (credential gate removed 2026-11-08, ADR #275)
- `FLAG_SECURE` + `setRecentsScreenshotEnabled(false)` (API 33+) on recovery surfaces (tab host additions; block overlay already sets it).
- ~~`PrivacyGate`: BiometricPrompt ... 60 s unlock window ...~~ **Superseded by owner ruling 2026-11-08 (ADR #275): "Let it be direct."** The prompt was removed from every recovery surface; direct access, screenshot protection unchanged. Partner pairing/deletion signing prompts are unaffected.
- ~~Extract the shared gate helper from the `MainActivity.signWithBiometric` shape~~ — withdrawn with the gate above.

### W7 — Data rail: AppRulesStore v8 → v9
New tables (SQLCipher, so Req "urge logs encrypted at rest" is satisfied by construction):
- `recovery_session(id TEXT PK, technique TEXT, intensity_start INT?, intensity_end INT?, state TEXT, started_at_epoch_ms INT, ended_at_epoch_ms INT?, duration_sec INT?, zone_id TEXT, entry TEXT, false_alarm INT, event_sequence INT?)` — `event_sequence` mirrors `AttemptLog`'s chain-link pattern for W5.
- `risk_window(id INT PK auto, start_minute INT, end_minute INT, days_mask INT, enabled INT)`.
- Indices: `recovery_session(started_at_epoch_ms)`, `recovery_session(event_sequence)` (mirror-drain lookup).
- Explicit `MIGRATION_8_9` (SQL = Room-generated CREATEs from the exported schema JSON); registered in `AppRulesStoreFactory.build()` **and every migration-test list** — the recurring trap (a hand-listed migration array missing the newest step killed the accountability DB on-device once already; the rules store has the same failure mode).
- Session history screen (FR-9 analog): paged newest-first, privacy-gated (W6).
- Session recovery: `STARTED` rows older than 15 min → `INTERRUPTED` on app start after DB ready; if the mirror event was already sent, enqueue a reconciling end event (v2 M2.4 shape, adapted to the shipped outbox).

### W8 — Tests, docs, device
- JVM: `RiskWindowEvaluator` (overnight wrap, day mask, boundary/DST via injected Clock), technique/resource selection, plan limits (≤5/≤200), state machine, migration list coverage guard (mirrors the ADR #58 guard test).
- Instrumented: migration 8→9 on a real encrypted file (MigrationTestHelper), FLAG_SECURE set, privacy-gate timing with fake clock.
- Mirror tests: payload text round-trip through `parseActivityText`; drain links `recovery_session` rows; unpaired no-op.
- Device: X6532 first (`install -r`, never uninstall); home-intent route (W2); pacer with reduced-motion on; widget/block-screen entry re-check.
- Docs sync: `app/docs/` (V1 doc implementation status, Architecture Decisions for new decisions, Build & Dependencies for any new dependency — **none expected**), vault progress note.
- No new dependency is required by this plan. QR scanning already ships (`QrCameraDecoder` on CameraX + ZXing — both already in `libs.versions.toml`), so even a future pairing UI needs no v2 M4.3 ML Kit addition.

## 4. Open items (defaults the build uses until answered)

| # | Question | Default | Blocked |
|---|---|---|---|
| OPEN-1 | Intervention surface: dedicated `InterventionActivity` (v2 M1) vs extend the existing Recovery tab | Extend the tab; measure before adding a surface | W1 |
| OPEN-2 | Urge-log fields | intensity (1–10, skippable) + technique + outcome; nothing to partner | W2 |
| OPEN-3 | Mirror payload shape | `recovery:<tech>:<outcome>` text prefix on `TYPE_ACTIVITY` | W5 |
| OPEN-4 | `associated_trigger_window` semantics | Display all steps; highlight window matches | W3 |
| OPEN-5 | Calming audio ships? | No for V1 (no licensed content); text/haptic pacing only | W1 |
| OPEN-6 | Req 15 motivational messages — fold into this build or separate? | Separate (not in v2 plan; own sender UI) | — |
| OPEN-7 | "Pause" (v2 copy) vs shipped vocabulary ("Manage urge"/"Open MyExodus Recovery") | Shipped vocabulary | W1 |
| OPEN-8 | Urge logs in user-facing stats? | No for V1 | W7 |
| OPEN-9 | Pairing UI (Open Product Decision #16, still deferred) | Mirror verifiable via ADR #46 harness only; no pairing UI without a user ruling | — |
| OPEN-10 | Clinical review of technique copy | Owner task; build ships neutral copy, no efficacy claims | Release |

## 5. Deliberately deferred / not applicable

- v2 PERF-1/2/3 (InterventionActivity latency) — N/A until OPEN-1 says A; the tab path inherits the app's existing startup.
- v2 PERF-5/6/7/8, §13 rollout, §14 quota alerts, H-01 TTL billing, M7.6 store gates, M0.8 Play target-API, Data-safety/health-policy items — belong to the retired Play/relay scope; the shipped stack has its own monitoring (ADR #54, `MyExodus Monitoring.md`).
- v2 M6.3 `RecoveryDataDeleter` full protocol — the shipped `BilateralDeletion` + `deletePairData` + wipe flow covers deletion; a "delete recovery data" convenience entry (local recovery tables only, not pairing) is a small W7 add if wanted.
- v2 H-13 Anonymous Auth lifecycle check — the shipped pairing stack carries the same risk; already documented as R13-analog in partner-pillar records. Not reopened here.
- v2 security hardening (16 KB, dependency pinning, rules tests) — repo-level (Gate 2.5 tooling, ADR #17); no new native code or new deps in this plan.

## 6. Verification appendix — v2 audit (every section accounted for)

**FR-1** entry points → partial exists (block button, widget); W1 completes. **FR-2** rating → W2. **FR-3/4** techniques → W1/W4 (catalog is the user's research). **FR-5** session state machine → W7. **FR-6** plan editor → W3 (entity reused). **FR-7** risk windows → W4/W7. **FR-8** re-rating → W2 (folded). **FR-9** history → W7. **FR-10** pairing → EXISTS (machinery; UI = OPEN-9). **FR-11** sharing → EXISTS; mirror W5. **FR-12** dashboard → EXISTS; extend W5. **FR-13** notifications → EXISTS (`partner_alerts`). **FR-14** revoke/pause → EXISTS (BilateralDeletion); pause-sharing entry = small add if wanted. **FR-15** delete → EXISTS (see §5). **FR-16** crisis → W6/W1 (new). **FR-17** consent → EXISTS conceptually? **No** — no consent screen exists; it was part of the deferred pairing UX (OPEN-9); do not build ahead of the pairing-UI ruling.

**NFR-1** → §5. **NFR-2** offline-first → W1–W4 all local; mirror is best-effort by design. **NFR-3** invariants → below. **NFR-4** cost → shipped stack's monitoring already tracks this (Blaze active; ADR #54 budget $2/mo). **NFR-5** flavors → N/A (no Play build, ADR #15). **NFR-6** solo-maintainable → this plan adds no abstraction not named in §3.

**M0** baseline/audits → EXISTS (secure-persistence plan, ADR #17, backup rules verified present). M0.6/M7.4 16 KB → repo-level. M0.7/M0.8 → §5.
**M1** intervention shell → W1 (OPEN-1/7); benchmark harness → deferred with PERF.
**M2** persistence/plan/windows/history/gate → W2/W3/W4/W7/W6; kill-switch-at-insert → EXISTS (`PartnerRolloutGate` + unlinked rows simply aren't drained).
**M3** crypto core → EXISTS (do not build Tink/HPKE; shipped ECDSA-P256 + AES-GCM envelope is the wire format). **M4** pairing → EXISTS; UI deferred (OPEN-9). **M5** relay/outbox/inbound/notify → EXISTS; mirror W5 (no rules change, no deploy). **M6** lifecycle → EXISTS (health states, BilateralDeletion); crisis → W6; diagnostics → existing telemetry, add counters only if asked. **M7** hardening → repo practice.

**INV-1/12** intervention never blocked on DB → preserved (tab renders from in-memory state; mirror failures are silent). **INV-2** no plaintext outside encrypted DB → `recovery_session` lives in SQLCipher. **INV-3/4/5** key handling/backup → EXISTS rails. **INV-6/7/8** signed+encrypted, pinned keys, transactional append → EXISTS (EventCreator/ChainStateStore). **INV-9** no FGS/alarms → preserved (no new scheduling). **INV-10** generic notifications → preserved (reuse `partner_alerts`). **INV-11** FLAG_SECURE + gate → W6. **INV-13** relay holds no free text → W5 payload is fixed vocabulary. **INV-14** diagnostics opt-in → preserved. **INV-15/16** single partner / read gating → EXISTS (rules live).

**AC-1..4** → W1 (adapted; device + unit). **AC-5..9** → W7 (migration), W6 (secure/gate), backup EXISTS. **AC-10..16, AC-25..28** → EXISTS (partner-pillar device legs already passed; not re-run unless the mirror touches the chain — it does not, it adds a payload value). **AC-17/18** → EXISTS + W5 render. **AC-19..21** → EXISTS. **AC-22** crisis → W6. **AC-23** 16 KB → repo-level. **AC-24** kill switch → EXISTS.

**§9 error table** — applicable rows: DB-not-ready (silent drop, preserved), camera denied → N/A until OPEN-9, everything relay-related → EXISTS behavior. **§10 adversary table** → EXISTS rails; new copy rule W1 (no efficacy claims). **§11** → §5. **§12** → W8. **§13** → nothing new to deploy. **§14** → existing monitoring doc; optional counters. **§15 risks** → R1/R2/R3/R6/R8/R9/R13/R14/R15 all carry to the shipped stack as already-recorded; new risks: none material (no new crypto, no new network surface). **§4 packages / §6 flows F1–F7 / §7 milestones** → replaced by §2 reuse: F1 intervention runs on the existing surface (W1), F2–F4 are the shipped pipeline + the W5 payload value, F5–F7 shipped (pairing/revoke/key-loss); §7's M-gates become W1–W8 with W8's device pass.
**§5 decisions 1–26** → 1,2,4,6–14,20–24,26 govern infrastructure that ships (ADRs #41–#58 supersede them; build nothing); applying to kept work: 15 (rating + windows drive technique selection → W4), 16 (dedicated activity → OPEN-1), 17 (FLAG_SECURE → W6), 18 (privacy gate → W6), 19 (text-only → W5 payload is fixed vocabulary), 25 (single active partner → shipped constraint, INV-15).
**§16 OQ** → resolved: OQ-1 = V1 doc supplied; OQ-2 = this §2; OQ-3/4/8/10 = owner; OQ-5/6 → §5/OPEN-1; OQ-9 = existing reporter; OQ-11/12/13 → §5. **§17** → above. **§18 DoD** → W8 + docs sync, minus the retired Play/backend gates.

**Nothing from v2 is silently dropped**: every element is either built here (W1–W8), reused from the shipped stack (§2), deferred with a named ruling (§5), or an owner task (legal/clinical).
