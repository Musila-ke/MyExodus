# MyExodus — Recovery Resource UI (V1 Req 8/10/19/20) — Implementation Plan

Status: FINAL for build. Date: 2026-09-19 (**Rev 2** — supersedes Rev 1). Audience: build model / engineer implementing literally.

**Audit revision 2026-10-17 (docs audit).** This file is the **archive**, not the build contract: `myexodus-recovery-resource-ui-implementation-plan-v3.md` supersedes it **for build purposes** and is COMPLETE (2026-09-19 — W1–W8 built, docs synced, ADR #61, X6532 device pass with the AppRulesStore v8→v9 migration). v2 was authored without repo access (§16 OQ-1/OQ-2 say so), which is why its M3–M5 partner stack (Tink HPKE/ECDSA keysets, `pairs/{pairId}/events`, `rr-*` outbox/poll workers, its own dashboard) designs a rail that already shipped as **Partner Pillar v2** (ADR #55, deployed 2026-09-18/19; App Check enforced, ADR #57); v3 §6 is the section-by-section audit proving nothing required was dropped. Present-tense items below that are dead and read as the record at writing: **NFR-5 and §13 "Flavors"** (Play flavor vs APK flavor — the app has no product flavors at all today, only debug/release builds), **M7 item 6 store gates, M7 item 8 staged rollout, M0.8/M7 submission target-API gates, OQ-4 and every Play-Console item** (no Play distribution, ever — ADR #15; a rollback is a new signed APK through the GitHub Releases channel, ADR #171), and **Decision 24's Remote Config kill switch** (ADR #158 moved the SOS kill switch server-side and ADR #171 retires Remote Config entirely; the shipped partner rails use `PartnerRolloutGate`/ADR #56). §10–§17 remain the historical record of what v2 specified.

**Revision note.** Rev 1 was independently audited at the plan level. This revision resolves every Critical and High finding (remote-deletion impossibility, outbox ordering, WorkManager `KEEP` scheduling gap, inbound failure classification and cursor atomicity, WAL-unsafe migration/quarantine copies, key-zeroization lifecycle, an overstated backup invariant, revocation not covering historical reads, a global sequence number that breaks under multiple partners, Anonymous Auth lifecycle risk, an undefined P95 benchmark methodology, an internally inconsistent worker timeout budget, a missing Play target-API release gate, 16 KB testing that only checked file alignment, and one-directional fingerprint verification during pairing) and the Medium/Low findings that change behavior (kill-switch gating at creation time, dashboard ordering by semantic sequence, honest "delivered" wording, TTL-vs-Rules retention enforcement, exact dependency/wire-format pinning, crisis-resource metadata). Changed subsections are marked **[REV2]**. Sections with no marker are unchanged from Rev 1.

Conventions: `<root>` = repo's existing base package (currently `com.example.myexodus`). Feature code lives in `<root>.recovery`. DB table prefix `rr_`. "MUST" = non-negotiable. Invariants are `INV-n` (section 10); every step cites the invariants it enforces.

---

## 1. Feature Overview

**What ships:** a one-tap, offline urge-intervention flow, a user-authored Coaching Plan, optional in-person-paired accountability-partner sharing (end-to-end encrypted, signed, over Firestore), and the privacy/recovery machinery around them.

**User-facing behavior**
1. User taps **Pause** (Home button or launcher shortcut). `InterventionActivity` opens instantly, no network/DB needed, optional 1–10 urge rating, then one technique runs: Urge Surfing (paced breathing), SOBER breathing space, Delay Timer, or Values Card.
2. Session outcome (completed / abandoned / interrupted) is logged locally. If a partner is paired and sharing is on, a signed, encrypted "session started / ended" event is queued (10-second false-alarm grace before sending).
3. Partner sees a neutral list of updates in a privacy-gated dashboard and gets a neutral local notification. User sees delivery status.
4. User edits Coaching Plan (If-Then plans, value statements) and risk windows behind a privacy gate.

**In scope (V1):** intervention shell; 4 techniques; technique selector; Coaching Plan text (≤5 If-Then, ≤5 values); risk windows; session history; privacy gate; backup exclusion; DB/keyset lifecycle hardening; mutual-QR partner pairing with **two-way** fingerprint confirmation **[REV2]**; signed+encrypted event sync (outbox, WorkManager) with **per-pair ordered delivery [REV2]**; partner dashboard; partner-poll notifications; revoke/pause/delete-recovery-data (**delete scope now explicit — this device's own data and this device's own relay events only, [REV2]**); crisis resources screen; consent screen; diagnostics (opt-in).

**Out of scope (V1) — do not build:** cognitive-restructuring thought records; photos/images; encrypted export/import; FCM push and any server-side function; foreground services; exact alarms; expedited work; Quick Settings tile; **more than one simultaneously-ACTIVE accountability partner per user [REV2 — was previously unstated; H-19]**; multi-device per user; CRDT/conflict resolution; Room major-version migration; changing DB key derivation; changes to the blocking engine, existing pairing key/escape code, or existing screens beyond adding entry points; iOS.

---

## 2. Requirements

### 2.1 Functional
| ID | Requirement |
|---|---|
| FR-1 | Entry points: Home-screen "Pause" button and static launcher shortcut labelled **Pause**. Both start `InterventionActivity`. No auth, network, DB, Keystore, Tink, or Firebase needed to render it. |
| FR-2 | Optional urge rating 1–10 (single screen, "Skip" one tap). Stored if given. |
| FR-3 | Technique selection per §8 M1.3. "Choose another" always visible. |
| FR-4 | Techniques: **Urge Surfing** (paced breathing 4s in / 6s out, prompt text rotates every 30s, duration 3/5/10 min, default 5); **SOBER** (5 screens Stop/Observe/Breathe/Expand/Respond, "Respond" shows user's If-Then plans); **Delay Timer** (10-min countdown, shows If-Then plans); **Values Card** (rotates user's value statements). Content in string resources; no efficacy claims in copy. |
| FR-5 **[REV2]** | Session state machine `STARTED → COMPLETED \| ABANDONED \| INTERRUPTED`. Persistence of the state transition to Room is **best-effort**: the intervention UI itself is never gated on storage (INV-1, INV-12), so if local storage is unavailable at the moment of writing, the transition may not be recorded — this is a weaker guarantee than "recovers after process death," which only holds when the DB was reachable. See M2.5. |
| FR-6 | Coaching Plan editor: ≤5 `IF_THEN`, ≤5 `VALUE` items, each ≤200 chars. Privacy-gated. |
| FR-7 | Risk windows: ≤3, local-time ranges with day mask; default one window 20:00–06:00 all days; editable/disable-able. Privacy-gated. |
| FR-8 | Optional end-of-session urge re-rating (skippable). |
| FR-9 | Session history list (local only). Privacy-gated. |
| FR-10 **[REV2]** | Partner pairing by mutual in-person QR exchange of public keys with **both sides displaying and the other explicitly confirming the partner's fingerprint** before ACTIVE (§8 M4; H-14). Consent screen first. **V1 supports exactly one ACTIVE accountability partner at a time** (H-19); starting a new pairing while one is ACTIVE is blocked until the existing one is revoked. |
| FR-11 | Sharing: send `s.start` (after 10s grace) and `s.end`; partner returns `rcpt` receipts. Only user→partner events; receipts partner→user. Per-pair delivery is strictly ordered by `seq` — see M5.4 **[REV2]**. |
| FR-12 | Partner dashboard (partner role) listing verified events, rendered in `(epoch, seq)` order **[REV2]**; user-side "sharing status" (**partner device acknowledged** / may-not-have-received) **[REV2 wording, M-08]**. |
| FR-13 | Partner notification: local, neutral, lock-screen-hidden. |
| FR-14 **[REV2]** | Revoke pairing (either side) — takes effect immediately for both new writes **and** reads of past relay events (H-10); pause sharing; delete this device's relayed events. |
| FR-15 **[REV2]** | "Delete recovery data" deletes: all local recovery tables, recovery keysets, the Keystore alias, recovery preference files, and every relay event **this device authored**. It does **not** and cannot delete relay events the partner device authored — that is outside this device's delete authority under the sender-owns-delete rule (M5.2) — and the confirmation copy says so plainly before double confirmation. See M6.3 for the full deletion protocol, including background retry of the relay-delete step. |
| FR-16 | Crisis resources screen from `assets/crisis_resources.json`, reachable from the intervention screen ("Need urgent help?"). Button hidden if file absent/empty in release; unit test fails the build if empty, invalid, or missing required per-entry metadata in release **[REV2, M-18]**. |
| FR-17 | Consent screen (first use of partner sharing) recording versioned consent; sharing disabled until accepted. |

### 2.2 Non-functional
- NFR-1 Latency: PERF-1..PERF-8 (section 11).
- NFR-2 Offline-first: FR-1..FR-9 work with no network, forever.
- NFR-3 Privacy: INV-1..INV-16 **[REV2 — two invariants added]**.
- NFR-4 **[REV2]** No new paid service in the common case; Firebase Spark plan is the default target for V1. TTL-triggered deletes are billed as ordinary Firestore delete operations under standard pricing and are **included in the same daily quota/cost tracking as writes** (H-01; see M5.1, R10, §14) — the team monitors quota headroom explicitly and makes a deliberate, budgeted decision before ever enabling Blaze, rather than discovering the conflict after a quota outage.
- NFR-5 Supports both build flavors (Play, APK). Play-only items are flavor-gated.
- NFR-6 Solo-maintainable: no abstraction not named in section 4.

### 2.3 Constraints
Kotlin, Jetpack Compose, existing DI. Existing Room major version and SQLCipher integration stay. Reference device: Infinix Smart 9 (XOS). **[REV2]** Exactly one ACTIVE accountability partner per user in V1 (H-19) — see FR-10, Decision 25.

---

## 3. Existing System Context

Facts this feature builds on (do not change):
- Native Kotlin Android app (Android-first V1), Room over SQLCipher, offline-first. Existing DB key mechanism (Keystore-wrapped random key) stays as is.
- Existing accountability-partner pairing key + escape code stored via DataStore + Tink `AeadSerializer` (pinned Tink alpha version). Leave untouched. The new pairing in this feature is separate (`rr_peer`, new keysets).
- Blocking engine (UsageStatsManager + overlay, local VpnService DNS sinkhole), Recovery/Rules/Settings screens exist. Only add entry points/routes; no redesign.
- Firebase is already used as the relay/back end; a Nairobi VPS exists for data-residency purposes. This feature uses Firestore only and does not touch the VPS.
- Flavors: Play and separately distributable APK. Current focus: APK.
- Package currently `com.example.myexodus`; may change — never hard-code it (use `<root>`/`BuildConfig.APPLICATION_ID`).

**Step 0 (M0) MUST record** in `docs/recovery-baseline.md`: Room version + module, SQLCipher artifact/version and integration path (Room 2 `SupportOpenHelperFactory` or Room 3 `SQLCipherDriver`), **exact pinned** Tink version (not "latest stable" — M-15), Firebase BoM, compileSdk/minSdk/targetSdk, AGP/Gradle/Kotlin, DI framework, crash reporter, Firebase Auth usage, **the project's Firebase Anonymous Auth automatic-account-cleanup setting (H-13; see M0.7)**, and **the current Google Play target-API requirement for new app updates as of this recording date (H-17; see M0.8 — look this up, it changes over time; do not copy a number from an old document)**. All instructions here are integration-agnostic: they touch entities, DAOs, repositories, and migrations only. Write the migration with the API of the Room version in the repo.

---

## 4. Architecture

### 4.1 Packages (`<root>.recovery`)
| Package | Responsibility |
|---|---|
| `ui.intervention` | `InterventionActivity`, `InterventionViewModel`, technique screens. First-frame path has zero data-layer dependency. |
| `ui.plan`, `ui.history`, `ui.partner`, `ui.pairing`, `ui.settings`, `ui.consent`, `ui.crisis` | Screens + ViewModels (UDF: `StateFlow<UiState>` + events). |
| `domain` | Pure Kotlin: `TechniqueSelector`, `RiskWindowEvaluator`, `SessionStateMachine`, models. No Android imports. |
| `data.db` | Entities, DAOs, migration (in existing `AppDatabase`). |
| `data.repo` | `SessionRepository`, `PlanRepository`, `OutboxRepository`, `PeerRepository`, `InboundRepository`, `KvRepository`. |
| `security` | `KeysetStore` (Tink keysets), `PrivacyGate`, `DbStateMonitor`, `RecoveryDataDeleter`. |
| `crypto` | `EnvelopeCodec` (sign, encrypt, decrypt, verify), `EnvelopeHeader`, `Fingerprint`. |
| `sync` **[REV2]** | `RelayClient` (interface) + `FirestoreRelayClient`, `OutboxWorker` (enforces per-pair head-of-line ordering, H-02), `OutboxScheduler` (new — owns the `rr-outbox-delayed` future trigger from the insertion path, H-03), `PartnerPollWorker`. |
| `notify` | `PartnerNotifier`. |

### 4.2 Component boundaries **[REV2]**
- **Intervention shell** (Activity + VM + static content) renders alone. It emits domain events (`SessionStarted`, `SessionEnded`) into an in-memory `Channel(capacity = 64, onBufferOverflow = DROP_OLDEST)` using `trySend` only (never a suspending `send` — the UI path must never be able to block on a full channel, M-02) consumed by `SessionRecorder`, which writes to Room on `Dispatchers.IO` when the DB is ready. If DB unavailable after a 30 s wait, or the channel drops an event under overflow, the event is dropped silently after the UI completes and a `dropped_event` diagnostics counter increments (INV-1, INV-12, INV-14).
- **Data layer** owns all persistence. ViewModels never touch DAOs directly.
- **Crypto layer** is stateless except for keysets loaded from `KeysetStore`. It has no Android UI or network code.
- **Sync layer** is the only code that talks to Firebase. Repositories call `OutboxRepository`; workers drain it. The kill switch (M5.8) is checked at **insertion** time in `SessionRepository`, not only inside the worker (M-09) — see M2.2.

### 4.3 State ownership
| State | Owner | Store |
|---|---|---|
| Plan items, risk windows, sessions, outbox, peers, inbound, seen-set, kv | Room (encrypted) | `rr_*` tables |
| HPKE + ECDSA private keysets | `KeysetStore` | Tink-encrypted file in `noBackupFilesDir`, wrapped by Keystore AEAD |
| DB passphrase | existing DB key provider | existing mechanism |
| Privacy-gate unlock timestamp | `PrivacyGate` | memory only |
| Session timer | `InterventionViewModel` | memory; persisted state row |
| **[REV2]** Pending relay-deletion marker | `RecoveryDataDeleter` | plaintext JSON file `noBackupFilesDir/recovery/pending_relay_delete.json` — deliberately **outside** the `rr_*` tables so it survives step 5 of M6.3 (H-12) |

### 4.4 Boundaries
- T1 this device: trusted.
- T2 partner device: trusted only via pinned public keys.
- T3 Firestore/network/Google infrastructure: untrusted for content; sees metadata (uid, pairId, eventId, epoch, seq, type, sizes, write times).
- T4 any backup/transfer channel: never carries recovery data through Android's own backup/transfer mechanisms (INV-5; see H-09 note in Decision 5 — this is a tested guarantee against Android's own mechanisms, not a universal claim about every OEM's transfer tooling).

---

## 5. Architecture Decisions

1. Stay on the repo's existing Room major + SQLCipher integration; Room 3 migration is a separate task — feature must not couple to a build-toolchain change (Room 3.0.3 is stable but SQLCipher's Room 3 support needs compileSdk 37). Ref: https://developer.android.com/jetpack/androidx/releases/room3
2. Do not change DB key derivation now — existing DBs would need a rekey/export; the intervention path is decoupled from DB open (INV-1) so KDF cost never blocks UX.
3. Keystore wrapping key is NOT user-authentication-bound — `KeyPermanentlyInvalidatedException` only occurs for auth-bound keys, and auth-binding would break background sync and one-tap use. Ref: https://developer.android.com/reference/android/security/keystore/KeyPermanentlyInvalidatedException
4. New private keysets are Tink keysets encrypted with a Keystore-backed AEAD and stored in `noBackupFilesDir` — matches existing DataStore+Tink pattern; `security-crypto` is deprecated. Ref: https://developer.android.com/reference/kotlin/androidx/security/crypto/package-summary
5. **[REV2]** Backups disabled app-wide (`allowBackup=false` + `data-extraction-rules` + `fullBackupContent` excluding every domain) — Auto Backup includes databases/files by default; this reliably excludes recovery data from **Android's own** cloud-backup and device-transfer mechanisms (verified by T-M0-3). Some OEM-specific device-transfer tools have been documented to behave differently from Android's standard mechanism (H-09) — this is a **documented residual risk**, tested against the M7 device matrix (Samsung, Xiaomi/Transsion, Pixel), not an absolute platform guarantee. Ref: https://developer.android.com/identity/data/autobackup
6. Key-unavailable never deletes anything; user chooses "start fresh" — key failure ≠ DB corruption; wrong wrapping key cannot decrypt old DB key.
7. **[REV2]** Pairing = mutual in-person QR exchange of public keys, pinned locally, with **both sides** displaying and requiring the other to explicitly confirm the partner's fingerprint before ACTIVE (H-14) — a one-directional check leaves the unchecked side substitutable by an attacker with physical access during the ceremony. Relay can never substitute keys (no remote key updates, INV-7).
8. Events = ECDSA-P256 signature (Tink `ECDSA_P256`) then HPKE `DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM` encryption — Tink states hybrid encryption gives no authenticity and points to digital signatures; ECDSA_P256 is Tink's documented default recommendation. Ref: https://developers.google.com/tink/exchange-data , https://developers.google.com/tink/digital-signature
9. HPKE `context_info` = binary envelope header — binds sender/recipient/pair/event/epoch/seq/type to the ciphertext (integrity only; authenticity comes from the signature).
10. Replay/duplicate control by event UUID doc ID + recipient seen-set + `(epoch, seq)` — HPKE is stateless; epoch resets survive DB reset.
11. Envelope carries `v` and `alg` — algorithm agility (Tink signals non-PQ signature key types will change).
12. **[REV2]** Firestore = ciphertext relay; Anonymous Auth (cleanup setting verified/resolved per M0.7, H-13) + App Check (Play Integrity provider, supported inside and outside Google Play) + Security Rules **that require `pair.state == 'active'` and an unexpired `expireAt` for reads as well as writes** (H-10, M-14 — revoke and expiry now cut off historical reads, not just new writes) + TTL 30 days as an eventual-cleanup backstop, not the access-control mechanism. Refs: https://firebase.google.com/docs/app-check/android , https://firebase.google.com/docs/firestore/ttl
13. Sync = Room outbox + plain `OneTimeWorkRequest` with `CONNECTED` constraint and exponential backoff; no FGS, exact alarms, or expedited work — expedited shows a foreground-service notification before Android 12 and is quota-limited; a persistent notification outs the user. Ref: https://developer.android.com/guide/background/persistent/getting-started/define-work
14. **[REV2]** Partner notifications by nominally-15-min periodic poll + live listener while dashboard open; no FCM in V1 — FCM needs a server component and budget. **Periodic WorkManager scheduling is inexact** (Doze/battery optimization can delay it); product copy says "checked periodically in the background," never promises a fixed interval (M-03).
15. Technique selected by urge rating and user-owned risk windows, not by a physiological clock model — ego-depletion evidence is contested; time is a soft default.
16. Dedicated `InterventionActivity`; entry via Home button + static shortcut — smallest surface that meets latency; tile deferred.
17. `FLAG_SECURE` + `setRecentsScreenshotEnabled(false)` (API 33+) on all recovery activities — blocks recents thumbnails and screenshots. Ref: https://developer.android.com/security/fraud-prevention/activities
18. Privacy gate (BiometricPrompt, biometric-or-device-credential, no CryptoObject) on plan/history/partner screens; intervention flow ungated — speed for the crisis moment, protection for data at rest in UI.
19. Text-only plan, no photos — removes plaintext image cache/file surface.
20. No export/import in V1 — key loss on phone change is accepted, documented, and mitigated by clear copy; V1.1 owns export.
21. Diagnostics opt-in default OFF; counters carry no user content — health-adjacent data.
22. Legal gates (DPIA, consent, controller registration, cross-border consent) are owner tasks that block public release, not build — Kenya DPA treats health status and similar as sensitive data and conditions out-of-Kenya processing of sensitive data on consent and safeguards. Ref: https://new.kenyalaw.org/akn/ke/act/2019/24/eng@2022-12-31
23. **[REV2]** CI gate for 16 KB page alignment **plus a runtime compatibility pass** on a 16 KB-page-size emulator/device (H-18 — static alignment checks alone don't prove the app runs correctly at 16 KB) — Play requires this for updates targeting current API levels; recorded in the release checklist rather than a hardcoded date, since Play's own timeline can shift. Ref: https://developer.android.com/guide/practices/page-sizes
24. **[REV2]** Kill switch via one Remote Config boolean `recovery_partner_sync_enabled` — Play staged rollout cannot roll back installs. The flag is checked at **outbox-insertion time** (`SessionRepository`) as well as inside the workers (M-09), so disabling it stops new events from being queued at all, not just from being sent — otherwise a backlog silently accumulates and floods out the moment the switch is re-enabled. This is an **operational convenience switch**, not a security containment control; if Remote Config is unreachable the app fails open (continues syncing) by design for V1 (accepted trade-off, M-10).
25. **[REV2]** V1 supports **exactly one ACTIVE accountability partner** per user (H-19). `rr_kv.nextSeq` is therefore a single global counter — safe only under this constraint. Supporting multiple simultaneous partners would require per-pair sequence streams (`nextSeq:<pairId>`) and is deferred to a later version.
26. **[REV2]** `OutboxWorker` sends the **lowest unsent `seq` per pair first** and never sends a higher `seq` for that pair while a lower one is still PENDING, even if the higher one's `notBefore` has already elapsed and the lower one's has not (H-02) — see M5.4.

---

## 6. Data Flow

**F1 Intervention (offline, critical path)**
`Tap Pause → InterventionActivity.onCreate (Compose, static content) → [optional rating] → TechniqueSelector (in-memory inputs: rating, risk windows from cached snapshot or defaults, favorite) → technique screen → user completes/exits → SessionEnded event`.
Risk windows and favorite come from a small `RecoveryPrefs` snapshot (DataStore preferences file in `noBackupFilesDir`) read async after first frame; before it loads, defaults apply. No DB in this path.

**F2 Logging**
`SessionStarted → SessionRecorder (IO) → Room txn: insert rr_session (+ if sharing on, kill switch enabled, and ACTIVE partner: insert rr_outbox s.start with seq++ and notBefore=start+10s)` — single transaction (INV-8). `SessionEnded → txn: update rr_session (+ outbox s.end)`. **[REV2]** The kill-switch check happens here, at insert time (see Decision 24, M2.2).

**F3 Outbound sharing [REV2]**
`OutboxWorker → for each ACTIVE pair, select its LOWEST-seq PENDING row (head-of-line ordering, never skip ahead to a later seq for the same pair) → if notBefore > now, skip that pair this run → else build header → sign → HPKE encrypt (context_info = header) → Firestore set pairs/{pairId}/events/{eventId} (per-write timeout 10s, bounded concurrency ≤5 across different pairs) → mark SENT → repeat for that pair's new lowest PENDING row`. A separate `rr-outbox-delayed` unique work, scheduled from the **insertion path** (not by the worker enqueuing its own successor — H-03), guarantees a future trigger exists for rows whose `notBefore` hasn't arrived yet. Failures per §9.

**F4 Inbound [REV2]**
`PartnerPollWorker (nominally 15 min, inexact) or live listener → query events after cursor → filter recipientUid == me → seen-set check → HPKE decrypt (context_info from doc fields) → verify signature with pinned peer sig key → schema validate → classify outcome (SUCCESS / PERMANENT_INVALID / RETRYABLE_LOCAL) → for SUCCESS or PERMANENT_INVALID, one transaction: insert rr_inbound (or rr_seen invalid marker) + rr_seen + enqueue rcpt + advance cursor, all together (H-06); for RETRYABLE_LOCAL, do not advance the cursor — retry on next cycle (H-05) → PartnerNotifier`.

**F5 Pairing [REV2]**
`A: consent → identity ready → QR-A (uid, pairId, sig pub, hpke pub) → B scans, confirms A's fingerprint → B pins A, shows QR-B → A scans, checks pairId, confirms B's fingerprint (two-way, H-14), pins B → A writes pairs/{pairId} (members [A,B], state active) → B's listener sees active pair with expected members → both ACTIVE`. Blocked if either side already has an ACTIVE peer (single-partner rule, H-19; must revoke first).

**F6 Revoke / pause / delete [REV2]**
Revoke: set pair `state='revoked'`, mark local peer REVOKED, cancel PENDING outbox. Because Rules now require `state=='active'` for reads too (H-10), revoke also immediately cuts off both members' relay read access to that pair's past events — already-delivered updates already downloaded to a device are unaffected. Pause: cancel PENDING outbox, set `sharing_paused`. Delete recovery data: §8 M6.3 — deletes this device's local data and this device's own authored relay events; cannot delete the partner's authored relay events (documented limitation, FR-15).

**F7 Key unavailable**
`DbStateMonitor detects open failure → state KeyUnavailable → app shows recovery screen; intervention flow still works (INV-1)`.

---

## 7. Implementation Sequence

Dependency order. Do not start a milestone until the previous one's exit checks pass.

| # | Milestone | Depends on | Exit |
|---|---|---|---|
| M0 | Baseline + invariants audit of existing code **[REV2: + Anonymous Auth cleanup check, Play target-API recording]** | — | `recovery-baseline.md` written; INV-3/4/5 checks pass or are fixed |
| M1 | Intervention shell (no persistence) + benchmark harness **[REV2: methodology defined]** | M0 | AC-1..AC-4 pass on reference device |
| M2 | Persistence, plan, windows, history, privacy gate, backup rules **[REV2: kill-switch-at-insert, interrupted-session reconciliation]** | M0, M1 | AC-5..AC-9 |
| M3 | Crypto core (keysets, envelope codec) **[REV2: exact wire encoding pinned]** | M0 | AC-10..AC-12 (unit) |
| M4 | Pairing + consent **[REV2: two-way fingerprint, single-partner rule]** | M2, M3 | AC-13, AC-25, AC-26 |
| M5 | Relay, outbox, inbound, dashboard, notifications **[REV2: ordering, atomicity, revoke/expiry read denial]** | M2, M3, M4 | AC-14..AC-18, AC-27, AC-28 |
| M6 | Lifecycle & safety: key-unavailable, revoke/delete, crisis, diagnostics **[REV2: WAL-safe copies, honest deletion protocol]** | M2, M5 | AC-19..AC-22 |
| M7 | Hardening & release gates **[REV2: 16 KB runtime test, current Play target-API check]** | all | Definition of Done |

---

## 8. Detailed Implementation Steps

### M0 — Baseline and invariant audit (existing code)

**M0.1 Record baseline. [REV2]** Create `docs/recovery-baseline.md` with the items in section 3. Pin all new dependency versions in the version catalog to an **exact, recorded release** — never "latest stable" resolved at build time (M-15); re-resolving "latest" between M0 and M3 can silently change crypto/toolchain behavior.

**M0.2 Threading of DB open (INV-1, INV-12).** Confirm DB is never opened on the main thread and not in `Application.onCreate` synchronously. If it is, move to lazy async provider (`Dispatchers.IO`). Add StrictMode (debug) `detectDiskReads/detectDiskWrites` penaltyLog.

**M0.3 Key-handling audit (INV-3, INV-4). [REV2]** Read the existing DB key provider and fix violations exactly as follows:
- The passphrase `ByteArray` MUST NOT be zeroed before the DB has actually opened. Room opens lazily. Test the **actual discovered SQLCipher integration's** passphrase-clearing behavior with an open → close → reopen cycle in the same process (H-08) **before** deciding whether the app also zeroes the array itself: if the SQLCipher factory/provider already clears or retains a reference to the same array internally, adding a second, app-level zero-fill can break a subsequent reopen. Either (a) rely on the library's own clear-after-open behavior if the test shows it's safe to reopen afterward, or (b) force-open (`openHelper.writableDatabase` / first query on IO) and only then zero, re-testing reopen after doing so. Never zero at factory construction. Record the decision and the open/close/reopen test result in `recovery-baseline.md`.
- All Keystore/decrypt failures (`GeneralSecurityException` incl. `UnrecoverableKeyException`, `KeyStoreException`, `InvalidKeyException`, `AEADBadTagException`, `ProviderException`, missing alias, `IOException` on blob read) map to one `DbState.KeyUnavailable`. No code path calls `deleteEntry`, deletes the DB, or regenerates the key automatically.
- Keystore master key spec: `PURPOSE_ENCRYPT|PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, 256-bit, **no** `setUserAuthenticationRequired`. StrongBox: try `setIsStrongBoxBacked(true)`; on `StrongBoxUnavailableException` regenerate without it. Existing keys are not regenerated.
- Add instrumented test T-M0-1: create DB → kill process → reopen → read row. Add T-M0-2: delete the Keystore alias (test hook) → relaunch → app reaches `KeyUnavailable`, DB file still present, no crash.

**M0.4 Backup exclusion (INV-5) + WAL-safe file operations. [REV2]** Manifest: `android:allowBackup="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, `android:fullBackupContent="@xml/backup_rules"`. Create both XML files excluding every domain (`root`, `file`, `database`, `sharedpref`, `external`, `device_root`, `device_file`, `device_database`, `device_sharedpref`) with `path="."`; in `data_extraction_rules.xml` repeat the excludes inside both `<cloud-backup>` and `<device-transfer>`. Test T-M0-3: `adb shell bmgr backupnow` / restore on emulator → no `rr_` data, no DB, no keysets restored; app starts clean. **New rule (H-07/M-13), used by every subsequent file-level DB copy or move in this plan (pre-migration copy in §13, quarantine move in M6.1):** before copying or moving the SQLCipher database, first stop new writes (cancel/await in-flight workers and repository writes) and either force a WAL checkpoint (`PRAGMA wal_checkpoint(TRUNCATE)`) or fully close the database handle — never copy the main `.db` file alone while its `-wal` file is non-empty and the handle is open, since the WAL is part of the database's persistent state.

**M0.5 Logging rule.** Add `SafeLog` (allowlisted keys only) and a CI grep failing on `Log.`/`println` inside `<root>.recovery` and on string templates containing plan/session fields. (This grep gate is a supplemental guard, not proof that sensitive values never reach crash reports, generated code, or third-party libraries — treat it as hygiene, not a security control, L-03.)

**M0.6 16 KB gate (decision 23). [REV2]** CI step: build release APK; run `zipalign -c -P 16 -v 4 <apk>`; for every bundled `.so` (including SQLCipher) confirm LOAD segment alignment 2\*\*14 with `llvm-objdump -p`. Fail build otherwise. This is a **static** check only — see M7.4 for the required **runtime** 16 KB validation (H-18), which this gate does not substitute for.

**M0.7 Anonymous Auth lifecycle check (H-13). [NEW, REV2]** Confirm in the Firebase console/Admin config whether automatic deletion of anonymous accounts inactive >30 days is enabled for this project. Accountability pairings are expected to live far longer than 30 days, so an auto-cleaned anonymous UID would silently invalidate a long-running pairing. If cleanup is enabled and can be disabled for this project, disable it. If it cannot be disabled, document the residual risk in `recovery-baseline.md` and treat M6.2's re-pair-on-identity-loss flow as the accepted mitigation (also update R13). Record the finding before M4 starts (OQ-11).

**M0.8 Play target-API release gate (H-17). [NEW, REV2]** Look up Google Play's **current** minimum target API requirement for new app updates as of this date and record it in `recovery-baseline.md`. Do not hardcode a specific API level in this plan — the requirement moves forward roughly annually. Add "repo `targetSdk` meets the current Play requirement, re-checked at M7 submission time" as a release gate (OQ-12, §13, §17).

### M1 — Intervention shell

**M1.1 Manifest & entry.** `InterventionActivity` (`exported=false` except via shortcut intent; `launchMode=singleTop`; night-friendly dark theme with solid `windowBackground` to avoid a white flash; `FLAG_SECURE` in `onCreate`; `setRecentsScreenshotEnabled(false)` if `SDK_INT >= 33`). Static shortcut `res/xml/shortcuts.xml` id `pause`, short label string `rr_shortcut_label` = **"Pause"**, targeting `InterventionActivity`; register via `<meta-data android:name="android.app.shortcuts">` on the launcher activity. Add Home button "Pause" to the existing Recovery/Home screen (no other UI change).

**M1.2 Startup discipline (INV-1).** `InterventionActivity` MUST NOT inject anything that touches Room, Keystore, Tink, Firebase, WorkManager, or network in its constructor/`onCreate`. Inject only `RecoveryPrefsSnapshot` (lazy, async) and the in-memory event `Channel`. Verify `Application.onCreate` does no new work in this feature (all new initializers are lazy).

**M1.3 `TechniqueSelector` (pure, domain).**
```
select(rating: Int?, inRiskWindow: Boolean, favorite: TechniqueId?): TechniqueId =
  when {
    rating != null && rating >= 7 -> URGE_SURFING
    inRiskWindow                  -> URGE_SURFING
    else                          -> favorite ?: SOBER
  }
```
`TechniqueId = URGE_SURFING | SOBER | DELAY_TIMER | VALUES_CARD`. "Choose another" lists all four.

**M1.4 `RiskWindowEvaluator` (pure, `java.time`).** Input `LocalDateTime` from `ZoneId.systemDefault()` at evaluation time. Window `[start,end)` in minutes-of-day; if `start <= end` inside iff `start <= t < end`; else (overnight) inside iff `t >= start || t < end`. Day mask (bit0=Mon … bit6=Sun) applies to the day the window *starts*: for the overnight after-midnight part use the previous day's bit. Recompute on every use; no clock-skew detector.

**M1.5 Screens/content.** Compose screens per FR-2/FR-4. Breathing pacer: animation 4s expand / 6s contract; honor system "remove animations" setting (static text pacing instead) and provide haptic tick on phase change (optional toggle, default off). Prompts (rotate every 30s): "Notice where you feel the urge in your body." / "Breathe out slowly. You don't have to act on it." / "Watch it rise and change. You are still here." All strings in `strings_recovery.xml`. Timers use `SystemClock.elapsedRealtime()` deltas; countdown survives configuration change via ViewModel; on process death the session ends as INTERRUPTED (M2.4). Footer text on Settings→About: "MyExodus is a self-help tool, not medical treatment." No efficacy claims anywhere. *(The footer's condition was **built 2026-09-23** (ADR #82) — it was specified here and recorded as shipped in three docs while no About surface existed; `AboutScreen` + `settings_about_disclaimer` now carry the exact sentence.)*

**M1.6 Benchmark harness + methodology. [REV2]** Add/extend `:macrobenchmark` with `StartupTimingMetric` for launching the `pause` shortcut intent (COLD and WARM) and a Baseline Profile generator covering `InterventionActivity` first frame → technique screen. Commit the profile. **Methodology (H-16):** 5 warm-up iterations (discarded) then 20 measured iterations per COLD/WARM condition on the reference device, rested ≥5 min, charger disconnected, battery ≥80%. Capture the **raw per-iteration `timeToInitialDisplayMs` samples** (not only the tool's own summary) and compute P50/P95/P99 from the sorted sample set yourself. Record sample count, all three percentiles, and device/thermal state in `docs/recovery-perf.md`. PERF-1/PERF-2 gate on time-to-**initial**-display only; do not conflate with time-to-full-display/interactive.

**Validate:** AC-1..AC-4. **Tests:** unit for `TechniqueSelector` (rating boundaries 6/7, window/no window, favorite null), `RiskWindowEvaluator` (overnight wrap, day-mask, DST/zone change via injected `Clock`), Compose UI tests for skip/choose-another; Macrobenchmark run on Infinix Smart 9 per the M1.6 methodology.

### M2 — Persistence, plan, windows, history, privacy gate

**M2.1 Schema (INV-2, INV-8).** Add to the existing database (version +1, explicit `Migration`, exported schema JSON committed, **no** destructive fallback). Migration SQL = the CREATE statements Room generates in the new exported schema.

| Table | Columns |
|---|---|
| `rr_plan_item` | `id TEXT PK`, `type TEXT` (`IF_THEN`/`VALUE`), `text TEXT`, `sortOrder INT`, `updatedAtEpochMs INT` |
| `rr_risk_window` | `id INT PK auto`, `startMinute INT`, `endMinute INT`, `daysMask INT`, `enabled INT` |
| `rr_session` | `id TEXT PK`, `technique TEXT`, `intensityStart INT?`, `intensityEnd INT?`, `state TEXT`, `startedAtEpochMs INT`, `endedAtEpochMs INT?`, `durationSec INT?`, `zoneId TEXT`, `entry TEXT` (`HOME`/`SHORTCUT`), `falseAlarm INT` |
| `rr_outbox` | `eventId TEXT PK`, `pairId TEXT`, `type TEXT` (`evt`/`rcp`), `payload TEXT`, `seq INT`, `epoch TEXT`, `notBeforeEpochMs INT`, `state TEXT` (`PENDING`/`SENT`/`CANCELLED`/`FAILED`), `attempts INT`, `lastError TEXT?`, `sessionId TEXT?`, `createdAtEpochMs INT`, `delivery TEXT` (`NONE`/`ACKNOWLEDGED`) **[REV2: renamed from DELIVERED, M-08]** |
| `rr_peer` | `pairId TEXT PK`, `peerUid TEXT`, `peerSigPub TEXT` (b64url), `peerHpkePub TEXT`, `role TEXT` (peer's role: `PARTNER`/`USER`), `label TEXT`, `state TEXT` (`PENDING`/`ACTIVE`/`REVOKED`), `pinnedAtEpochMs INT`, `fingerprint TEXT`, `fingerprintConfirmedByMe INT` **[REV2, H-14]** |
| `rr_inbound` | `eventId TEXT PK`, `pairId TEXT`, `senderUid TEXT`, `epoch TEXT`, `seq INT`, `type TEXT`, `payload TEXT`, `serverCreatedAtEpochMs INT`, `receivedAtEpochMs INT`, `readAtEpochMs INT?` |
| `rr_seen` | `eventId TEXT PK`, `receivedAtEpochMs INT`, `valid INT`, `retryCount INT` **[REV2, H-05]** |
| `rr_kv` | `key TEXT PK`, `value TEXT` |

Indices: `rr_session(startedAtEpochMs)`, `rr_outbox(state, notBeforeEpochMs, pairId, seq)` **[REV2: pairId/seq added to support head-of-line selection]**, `rr_inbound(pairId, epoch, seq)` **[REV2: was serverCreatedAtEpochMs — dashboard now orders by semantic seq, M-07]**.
`rr_kv` keys: `epoch` (16 hex chars, generated on first use), `nextSeq` (global — safe only under the single-active-partner rule, Decision 25), `consent_v1` (JSON `{version, acceptedAtEpochMs}`), `sharing_paused`, `favorite_technique`, `inbound_cursor:<pairId>` (`epoch|seq` **[REV2: was serverCreatedAtMillis|eventId — cursor now keyed by semantic order, consistent with M-07]**), `invalid_window:<pairId>`.

**M2.2 Repositories. [REV2]** Suspend APIs; every multi-table write in one transaction. `SessionRepository.start()` inserts session and, if sharing conditions hold (ACTIVE partner with role PARTNER, consent accepted, not paused, **and the cached Remote Config flag `recovery_partner_sync_enabled` is true — checked here, not only in the worker, M-09**), inserts outbox `s.start` in the same transaction with `seq = kv.nextSeq++`, and schedules `rr-outbox-delayed` with `initialDelay = notBefore - now` via `OutboxScheduler` (H-03). `SessionRepository.end()` likewise for `s.end`. `falseAlarm(sessionId)` within the 10 s grace sets outbox `CANCELLED` and session `ABANDONED, falseAlarm=1`. Payload JSON (kotlinx.serialization): `{"t":"s.start|s.end|rcpt","sid":"<uuid>","tech":"<id>","dur":<sec?>,"out":"completed|abandoned|interrupted","ts":<epochMs>,"tz":"<zoneId>"}`; receipt: `{"t":"rcpt","eid":"<eventId>","ts":<epochMs>}`. **No free text ever enters a payload (INV-13).**

**M2.3 `RecoveryPrefs` snapshot.** DataStore preferences file `recovery_prefs` in `noBackupFilesDir` holding non-sensitive cache for F1: risk windows (denormalized copy), `favorite_technique`. Updated by `PlanRepository`/settings writes. Loaded async by the intervention shell. Contains no plan text.

**M2.4 Session recovery. [REV2]** On app start after DB ready (IO): every `rr_session` in `STARTED` older than 15 minutes → `INTERRUPTED`, `endedAtEpochMs = startedAt + 15 min`. **If that session's corresponding `rr_outbox` `s.start` row is in state `SENT`** (it may already have reached the partner), also insert an outbox `s.end` row in the same transaction with `out: "interrupted"`, `ts = endedAtEpochMs`, `seq = kv.nextSeq++` — so the partner's dashboard never shows a start with no matching end (H-04). If the `s.start` row is still `PENDING` or was `CANCELLED` (never left this device), no reconciling event is needed: mark it `CANCELLED` instead.

**M2.5 Recorder.** `SessionRecorder` collects the intervention `Channel` (see 4.2 for capacity/overflow policy), waits for `DbState.Ready` (timeout 30 s then drops event, incrementing `dropped_event`), writes via repositories. Drop = no user-visible effect (INV-12); see FR-5 for the resulting persistence guarantee.

**M2.6 Plan/windows/history screens.** Compose screens with field limits (200 chars; item caps enforced in repo and UI). History is a paged list newest-first (Room `Flow` + simple `LIMIT/OFFSET` paging, page 50). All three screens wrapped by `PrivacyGate`.

**M2.7 `PrivacyGate` (INV-11).** `BiometricPrompt` with authenticators `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, no `CryptoObject`. Unlocked state valid for 60 s after success and until the app is stopped >60 s (ProcessLifecycleOwner `onStop` timestamp vs `elapsedRealtime`). If `BiometricManager.canAuthenticate(...)` reports no secure lock screen, gate is bypassed and a one-time banner explains that screens are not protected. Gate applies to: plan, windows, history, partner dashboard, pairing, partner settings. Not applied to Home, intervention flow, crisis screen, consent screen.

**M2.8 Screen protection (INV-11).** Every recovery activity/composable host sets `FLAG_SECURE` and (API 33+) `setRecentsScreenshotEnabled(false)`.

**Validate:** AC-5..AC-9. **Tests:** migration test (open prior-version encrypted DB file → migrate → `PRAGMA cipher_integrity_check` returns no rows, `PRAGMA foreign_key_check` empty → DAO smoke); repo transaction tests (kill between insert steps via injected failure: session and outbox both present or both absent); `RecoveryPrefs` round trip; gate timing with fake clock; UI test that FLAG_SECURE is set; **[REV2]** interrupted-session-with-SENT-start produces a reconciling `s.end` (AC-27); kill-switch-off produces zero new outbox rows, not merely unsent ones (M-09).

### M3 — Crypto core

**M3.1 Dependencies. [REV2]** `com.google.crypto.tink:tink-android`, exact version pinned per M0.1 (never re-resolved as "latest" at this point, M-15). Call `HybridConfig.register()` and `SignatureConfig.register()` once, lazily, in `KeysetStore` init (not in `Application.onCreate`).

**M3.2 `KeysetStore`.**
- Master AEAD: `AndroidKeystoreKmsClient` with URI `android-keystore://<applicationId>.recovery.keyset_master`; `generateNewAeadKey(uri)` if absent; non-auth-bound.
- Two keysets generated on first pairing consent: HPKE private (`KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM")`) and signature private (`KeyTemplates.get("ECDSA_P256")`).
- Persist each as one file in `noBackupFilesDir/recovery/keysets/{hpke,sig}.tink` using `TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, masterAead, associatedData)` with associated data = the ASCII file name; read with `parseEncryptedKeyset`. (If those symbols are absent in the pinned Tink version, use `KeysetHandle.write(BinaryKeysetWriter..., masterAead)` / `KeysetHandle.read(BinaryKeysetReader..., masterAead)` with the same file layout.)
- Any failure reading/decrypting a keyset → `IdentityState.Unavailable` (no automatic regeneration if peers are ACTIVE). Regeneration only through the explicit re-pair flow (M6.2) which first revokes all pairings.
- Public keysets exported with `TinkProtoKeysetFormat.serializeKeysetWithoutSecret(handle.publicKeysetHandle)`; b64url without padding for QR/DB.
- Reads/decrypts happen off the main thread.

**M3.3 Envelope wire format (exact). [REV2]** Definitions: `LP(x) = u16be(len(x)) || x`; strings UTF-8; `u64be` 8-byte big-endian.
```
HEADER  = LP("MYX1") || LP(alg) || LP(senderUid) || LP(recipientUid) || LP(pairId)
          || LP(eventId) || LP(epoch) || u64be(seq) || LP(type)
signInput = LP("myexodus/recovery/event/v1") || HEADER || payloadBytes
sig       = ECDSA_P256.sign(signInput)                      // PublicKeySign
plaintext = u16be(len(sig)) || sig || payloadBytes
ct        = HybridEncrypt(recipientHpkePub).encrypt(plaintext, contextInfo = HEADER)
```
`alg = "h1"` (HPKE X25519/HKDF-SHA256/AES-256-GCM + ECDSA-P256). `type ∈ {"evt","rcp"}`. `payloadBytes` = UTF-8 JSON exactly as produced (never re-serialized before verification). Max `ct` 4096 bytes; payload max 1024 bytes. **[REV2, M-16]** `sig` MUST use Tink's `ECDSA_P256` key template exactly as published — DER-encoded ECDSA signature with Tink's standard output prefix — not the `ECDSA_P256_IEEE_P1363` variant. Record the exact resolved Tink template/key-type string in `recovery-baseline.md` so a future Tink version change is a deliberate decision, not a silent wire-format break.

Decode order (all MUST pass, else discard as invalid): build HEADER from cleartext doc fields + doc path → `HybridDecrypt.decrypt(ct, HEADER)` → split sig/payload → verify with pinned `peerSigPub` over `signInput` → parse JSON with strict schema (unknown `t` rejected; field sizes bounded) → `senderUid` equals pinned `peerUid` and `recipientUid` equals own uid.

**M3.4 `Fingerprint`.** `SHA-256(LP("myexodus/fp/v1") || LP(sigPubBytes) || LP(hpkePubBytes))`; display first 24 hex chars in six groups of four.

**Validate:** AC-10..AC-12. **Tests (JVM/instrumented):** round trip; tampered `ct`; tampered each header field (each must fail decrypt); wrong recipient key; wrong sender signing key; forged sender (attacker HPKE-encrypts to victim with valid-looking header but own signature → fails verify); replay of identical doc → dedupe path; oversized ct; payload with unknown `t`; keyset file corruption → `Unavailable`; keyset survives process restart; **[REV2]** signature bytes match the DER/Tink-prefix encoding recorded in baseline (golden vector test, M-16).

### M4 — Consent and pairing

**M4.1 Consent screen (FR-17).** Shown before first pairing. Content (owner-approved copy in `strings_recovery.xml`): what is shared (session start/end, technique, duration, time), with whom (the paired partner only), that content is encrypted end-to-end but the relay sees when messages are sent and their size, that data leaves the country to Google infrastructure (cross-border), retention (30 days on relay; delivered updates stay on partner's phone), how to revoke/delete. Two explicit checkboxes: sensitive-data sharing; cross-border processing. Save `consent_v1` in `rr_kv`. Decline → sharing stays off; all other features work.

**M4.2 Identity bootstrap.** After consent: sign in with Firebase Anonymous Auth (once; persist — subject to the cleanup-setting finding from M0.7), generate keysets (M3.2), ensure `epoch`/`nextSeq` in `rr_kv`. Enable App Check (Play Integrity provider for release; debug provider for debug builds) in the app's Firebase init only when sharing is enabled.

**M4.3 QR payload (JSON, v1):** `{"v":1,"uid":"<firebase uid>","pairId":"<uuid v4>","sig":"<b64url>","hpke":"<b64url>"}`. Generation: ZXing core (`com.google.zxing:core`), error-correction M. Scanning: CameraX + ML Kit bundled barcode scanner (`com.google.mlkit:barcode-scanning`, bundled model — works without Play services). Camera permission requested at scan time only; denial shows manual instructions.

**M4.4 Flow (each side in person). [REV2 — two-way fingerprint confirmation, single-partner rule]**
0. Before starting: if either device already has an ACTIVE peer, block and prompt to revoke it first (H-19, Decision 25).
1. **User (A)** Settings → Add partner → consent → QR-A shown with fingerprint. Enter a local label for the partner (≤30 chars).
2. **Partner (B)** Settings → "I'm a partner" → scans QR-A → sees A's fingerprint on screen → **explicitly confirms it matches what A showed in person** → B inserts `rr_peer(pairId, A.uid, A.keys, role=USER, PENDING, fingerprintConfirmedByMe=1)` → B shows QR-B (same schema, same `pairId`).
3. **A** taps "Scan partner's code" → scans QR-B → requires `QR-B.pairId == pairId` and `v==1` → sees B's fingerprint on screen → **explicitly confirms it matches what B showed in person** (H-14 — this step was previously a silent pairId/version check only; the fingerprint ceremony must be symmetric) → inserts `rr_peer(pairId, B.uid, B.keys, role=PARTNER, PENDING, fingerprintConfirmedByMe=1)` → writes Firestore `pairs/{pairId}` `{v:1, members:[A.uid,B.uid], state:"active", createdBy:A.uid, createdAt: serverTimestamp}` → marks ACTIVE.
4. **B** listens `pairs/{pairId}` (snapshot); on doc with `members == {pinned A.uid, own uid}` and `state=="active"` → ACTIVE. Any other membership → mark REVOKED, show "Unexpected pairing" warning (INV-7).
5. A QR is single-use per side: regenerate `pairId` for every new pairing; a scanned pairId already present in `rr_peer` is rejected.

**M4.5 Key change rule (INV-7).** No code path updates `peerSigPub`/`peerHpkePub` on an existing `rr_peer`. A new key = new pairing (M4.4) after revoking the old one.

**Validate:** AC-13, AC-25, AC-26. **Tests:** two-device/emulator pairing E2E; QR with wrong pairId rejected; replayed old QR rejected; pairing when A's uid differs from pinned (reinstall) → warning + no ACTIVE; camera-denied path; **[REV2]** pairing blocked while an ACTIVE peer exists (AC-26); ACTIVE only reached after both `fingerprintConfirmedByMe` flags are set (AC-25).

### M5 — Relay, outbox, inbound, dashboard, notifications

**M5.1 Firestore data model. [REV2]**
```
pairs/{pairId}                      { v:1, members:[uidA,uidB], state:'active'|'revoked', createdBy, createdAt }
pairs/{pairId}/events/{eventId}     { v:1, alg:'h1', senderUid, recipientUid, epoch, seq, type:'evt'|'rcp',
                                      ct:<bytes>, createdAt:<serverTimestamp>, expireAt:<timestamp> }
```
`expireAt = startOfDayUTC(now) + 30 days` (client-computed, day-coarsened). No other fields. TTL policy on collection group `events`, field `expireAt` (deploy in M7). TTL deletion runs within roughly 24 h of expiry and is **not** instantaneous and **not** free of billing (TTL-triggered deletes are ordinary delete operations under standard Firestore pricing, counted in the same quota as writes, NFR-4/H-01) — app logic never relies on TTL timing for access control; **Rules enforce the expiry cutoff directly** (see M5.2, M-14).

**M5.2 Security Rules (`firestore.rules`, deploy with Firebase CLI). [REV2 — read authorization now checks pair state and expiry]**
```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function signedIn() { return request.auth != null; }
    function pairDoc(pairId) { return get(/databases/$(database)/documents/pairs/$(pairId)).data; }
    match /pairs/{pairId} {
      allow get: if signedIn() && request.auth.uid in resource.data.members;
      allow list: if false;
      allow create: if signedIn()
        && request.resource.data.keys().hasOnly(['v','members','state','createdBy','createdAt'])
        && request.resource.data.v == 1
        && request.resource.data.members is list
        && request.resource.data.members.size() == 2
        && request.auth.uid in request.resource.data.members
        && request.resource.data.createdBy == request.auth.uid
        && request.resource.data.state == 'active'
        && request.resource.data.createdAt == request.time;
      allow update: if signedIn()
        && request.auth.uid in resource.data.members
        && request.resource.data.diff(resource.data).affectedKeys().hasOnly(['state'])
        && request.resource.data.state == 'revoked';
      // NOTE (H-11): deleting this document does NOT cascade-delete its events
      // subcollection — Firestore never cascades. This allow-delete exists only
      // for local bookkeeping after a member has already removed their own
      // authored events (M6.3); it is not a mechanism for full relay deletion.
      allow delete: if signedIn() && request.auth.uid in resource.data.members;

      match /events/{eventId} {
        // [REV2] H-10 + M-14: reads now require the pair to still be active
        // AND the event to not yet be past its own retention cutoff, so both
        // revoke and expiry cut off historical access, not just new writes.
        allow get, list: if signedIn()
          && request.auth.uid in pairDoc(pairId).members
          && pairDoc(pairId).state == 'active'
          && resource.data.expireAt > request.time;
        allow create: if signedIn()
          && pairDoc(pairId).state == 'active'
          && request.auth.uid in pairDoc(pairId).members
          && request.resource.data.senderUid == request.auth.uid
          && request.resource.data.recipientUid in pairDoc(pairId).members
          && request.resource.data.recipientUid != request.auth.uid
          && request.resource.data.keys().hasOnly(['v','alg','senderUid','recipientUid','epoch','seq','type','ct','createdAt','expireAt'])
          && request.resource.data.v == 1
          && request.resource.data.alg == 'h1'
          && request.resource.data.type in ['evt','rcp']
          && request.resource.data.epoch is string && request.resource.data.epoch.size() == 16
          && request.resource.data.seq is int && request.resource.data.seq >= 0
          && request.resource.data.ct is bytes && request.resource.data.ct.size() <= 4096
          && request.resource.data.createdAt == request.time
          && request.resource.data.expireAt is timestamp
          && request.resource.data.expireAt <= request.time + duration.value(31, 'd');
        allow update: if false;
        // Sender-owns-delete: a member can only delete events THEY authored.
        // This is the structural reason FR-15/M6.3 cannot claim full relay
        // deletion from one device alone (C-01) — documented, not a bug.
        allow delete: if signedIn() && resource.data.senderUid == request.auth.uid;
      }
    }
    match /{document=**} { allow read, write: if false; }
  }
}
```
Never ship test-mode rules (they expire and are open). App Check: register the app (both signing certificates: Play and APK flavor) with the Play Integrity provider; enable enforcement for Firestore **before public release** (see M4.4/M0.8 — do not defer enforcement past internal testing given the Spark quota's exposure to abuse).

**M5.3 `RelayClient` (interface). [REV2 — method renamed for honesty, H-11]** `suspend fun putEvent(pairId, eventId, fields)`, `suspend fun fetchAfter(pairId, cursor, limit)`, `fun observePair(pairId)`, `suspend fun setPairRevoked(pairId)`, `suspend fun deleteMyOutboundEvents(pairId)` (was `deleteMyEvents` — renamed to make explicit that it can only ever remove events this uid authored, per the Rules' sender-owns-delete constraint), `suspend fun deletePair(pairId)` (local bookkeeping only — removes the parent doc; does **not** cascade to the `events` subcollection, H-11). `FirestoreRelayClient` implements with Firestore SDK. Doc ID = `eventId` (idempotent retries). Pass `withTimeout(10_000)` **[REV2: reduced from 20s per write, H-15 — see M5.4 for the concurrency/timeout budget]**.

**M5.4 `OutboxWorker`. [REV2 — ordering, scheduling, and timeout budget all reworked]**
- **Two unique work names.** `rr-outbox` (immediate trigger, `ExistingWorkPolicy.KEEP`, constraints `NetworkType.CONNECTED`, backoff `EXPONENTIAL` base 30 s) is enqueued after every outbox insert and on app start — this is the worker that actually drains PENDING rows. `rr-outbox-delayed` (future trigger, `ExistingWorkPolicy.REPLACE`, same constraints) is enqueued **from the outbox-insertion path** (`OutboxScheduler`, called by `SessionRepository`/`M2.4`/`M6.3` whenever a row with a future `notBefore` is inserted), with `initialDelay = notBefore - now` (H-03). This guarantees a future trigger always exists even while `rr-outbox`'s `KEEP` policy means it cannot itself schedule a successor under the same unique name. **No** expedited request, `setForeground`, foreground service, or `AlarmManager` anywhere in this path.
- **`doWork` (head-of-line per pair, H-02):** for each pair with an ACTIVE peer, select that pair's **lowest-`seq` PENDING row**. If its `notBeforeEpochMs > now`, skip that pair entirely for this run — never advance to a higher `seq` for the same pair just because its `notBefore` has already elapsed. Otherwise: build HEADER, sign, encrypt (M3.3), write the doc (`createdAt = FieldValue.serverTimestamp()`, `expireAt` per M5.1), mark `SENT`, then re-select that pair's new lowest PENDING row and repeat. Across all pairs in one run: at most 20 total sends, with **bounded concurrency of ≤5 concurrent Firestore writes across different pairs** (never concurrent within the same pair, to preserve per-pair ordering), each with a 10 s per-write timeout (M5.3). **Timeout/target budget (H-15):** the worker's *target* completion time for a run of ≤20 items is P95 ≤ 30 s under typical 3G-class conditions (PERF-5, measured, not a hard gate); the *hard ceiling* used to size the WorkManager execution window is ≤ 40 s (20 items ÷ 5 concurrency × 10 s per-write timeout, worst case). These are two different numbers for two different purposes — do not conflate them.
- Skip items whose peer is not ACTIVE (mark CANCELLED).

**M5.5 Inbound. [REV2 — failure classification, atomic cursor, honest scheduling language]**
- `PartnerPollWorker`: `PeriodicWorkRequest` nominal 15 min, unique `rr-partner-poll` (`KEEP`), `CONNECTED`; scheduled only when at least one peer with role USER (I am the partner) or PARTNER (I expect receipts) is ACTIVE; cancelled otherwise. **Periodic WorkManager scheduling is inexact** and can be delayed by Doze/battery optimization (M-03) — dashboard/status copy says "checked periodically in the background," never promises a fixed interval.
- Query per ACTIVE pair: `orderBy(createdAt).orderBy(documentId).startAfter(cursorTs, cursorId).limit(50)`; loop until <50. Client filters `recipientUid == myUid`. The exact index requirements for this query are verified empirically in M0/M5 testing against the actual Firestore SDK version in use; commit `firestore.indexes.json` if a composite index turns out to be required (M-04) — do not assume single-field indexes suffice without testing.
- Live listener (`RelayClient.observe…`) only while the dashboard is visible; uses the same processing function.
- **Processing per doc (H-05, H-06):** skip if `rr_seen` has `eventId`. Decode per M3.3 and classify the outcome into exactly one of:
  - `SUCCESS` — decrypt/verify/schema all pass.
  - `PERMANENT_INVALID` — decrypt, signature, or schema failure that cannot succeed on retry (wrong key, tampered ciphertext, malformed payload, unknown `t`).
  - `RETRYABLE_LOCAL` — keyset transiently unavailable, DB unavailable, or process restarted mid-processing.

  For `SUCCESS`: **one Room transaction** inserts `rr_inbound`, inserts `rr_seen(valid=1)`, enqueues an outbox `rcpt` (if `type=="evt"`, via `OutboxScheduler` so its own `notBefore`/delay is scheduled correctly), and advances the cursor — all four together, so a crash between any of them can never leave the cursor advanced past an event that wasn't durably recorded (H-06). For `PERMANENT_INVALID`: one transaction inserts `rr_seen(valid=0)`, increments the invalid-window counter, and advances the cursor. For `RETRYABLE_LOCAL`: do **not** advance the cursor for this doc; increment `rr_seen.retryCount` in a lightweight non-cursor-advancing record and re-attempt on the next poll/listener cycle (H-05); after 5 retryable failures for the same doc, demote it to `PERMANENT_INVALID` so a persistently broken payload doesn't retry forever. If the invalid count in a rolling hour exceeds 20 → show "Unexpected activity on this pairing" banner with Revoke action (no more processing until user acts) — note this threshold counts genuinely invalid events; a malicious but validly-signed partner cannot trigger it by design (their events pass verification even if semantically nonsensical, which is a separate, accepted risk — see Risk Register R3).
- Receipt handling (user side): decode `rcpt` → mark matching `rr_outbox.delivery = ACKNOWLEDGED` **[REV2, renamed from DELIVERED, M-08 — a signed receipt only proves the partner's device generated it, not that the person read it]** where `eventId == payload.eid`, store `last_receipt_at`.
- Prune `rr_seen` older than 45 days daily.
- Gap indicator: per `(senderUid, epoch)` track highest seq; if a received seq leaves a hole >0 that persists 24 h, show "Some updates may be missing" (informational).

**M5.6 Dashboard UI. [REV2 — render order, wording]** Partner role: verified events rendered in **`(epoch, seq)` order** (M-07 — semantic order, not relay arrival/`createdAt` order, which can differ after network disruption or worker retries), newest first: "Started a {technique} exercise", "Finished a {n}-minute {technique} exercise", "Ended a {technique} exercise early" (no shaming language). Unread badge; `readAtEpochMs` updated on view. User role: "Sharing with {label}", status "**Partner's device acknowledged**" / "Partner may not have received recent updates" (events SENT >24 h without an acknowledgement) **[REV2, M-08]**. Both behind `PrivacyGate`.

**M5.7 `PartnerNotifier`.** Channel `partner_updates` (`IMPORTANCE_DEFAULT`). One summary notification per poll batch: title = app name, text "New update", `VISIBILITY_PRIVATE` with a public version showing only the app name (`setPublicVersion`), tap → dashboard (gated). No session details in any notification (INV-10). Request `POST_NOTIFICATIONS` at first pairing on API 33+.

**M5.8 Kill switch. [REV2 — gating moved earlier, semantics clarified]** Firebase Remote Config boolean `recovery_partner_sync_enabled` (default true, `minimumFetchInterval` 12 h). When false: **no new outbox rows are created** — enforced in `SessionRepository` at insertion time (M2.2, M-09), not only inside the worker — and workers no-op; dashboard shows "Sharing is temporarily unavailable." This is an operational convenience switch, not a security containment control (M-10); if Remote Config itself is unreachable, the app fails open (continues syncing) — an accepted V1 trade-off given the 12 h fetch interval already limits how quickly the switch can take effect either way.

**Validate:** AC-14..AC-18, AC-27, AC-28. **Tests:** Firebase Emulator rules tests (`@firebase/rules-unit-testing`): non-member read/write denied; sender spoof denied; extra fields denied; ct >4096 denied; expireAt >31 d denied; update denied; delete by non-sender denied; write to revoked pair denied; **[REV2] read of a revoked pair's events denied for both former members (H-10); read of an expired event denied even before TTL has run (M-14)**. Worker tests with fake relay: backoff on `UNAVAILABLE`, idempotent double send, cancel on revoked, **[REV2] a pair with seq 1 PENDING (future notBefore) and seq 2 PENDING (eligible now) never sends seq 2 before seq 1 (H-02); a worker run that ends with a future-due row still has `rr-outbox-delayed` scheduled afterward (H-03)**. Airplane-mode test: create 5 sessions offline → reconnect → all delivered once, in strict per-pair seq order. Dedupe test: same doc processed twice → one row. Invalid-flood test: 25 garbage docs → banner, no crash, no notification storm. **[REV2]** Retryable-local-failure test: inject a transient keyset-unavailable error mid-processing → cursor does not advance → event is successfully processed on a later retry, not lost (H-05); simulated crash between decode-success and transaction-commit → event is neither duplicated nor lost on restart (H-06).

### M6 — Lifecycle and safety

**M6.1 `DbStateMonitor` + key-unavailable screen (INV-4, F7). [REV2 — WAL-safe quarantine move]** Sealed `DbState = Opening | Ready | KeyUnavailable | Corrupt`. On `KeyUnavailable`/`Corrupt`: keep intervention flow working (INV-1); recovery screens show a blocking-but-escapable screen "Your saved history can't be opened on this device". Actions: **Try again**; **Start fresh** (requires typed confirmation "START FRESH"): **first stops workers and awaits in-flight writes, then checkpoints/closes the DB handle (same WAL-safe procedure as M0.4, H-07/M-13)**, then moves DB files and key blob to `noBackupFilesDir/quarantine/<epochMs>/` (never deletes), creates a new key and DB, resets recovery `epoch` (new random), revokes all pairings locally and on relay if reachable; **Delete old data** available later in Settings (double confirm) for quarantined folders. Copy states plainly that history cannot be restored on this device and that the partner still has updates already delivered.

**M6.2 Identity loss.** If `KeysetStore` reports `Unavailable` or Firebase uid differs from pinned uids: mark all peers REVOKED locally, show "Pairing needs to be set up again", `epoch` regenerated; user re-pairs via M4. (This flow is the accepted mitigation for the Anonymous Auth cleanup risk identified in M0.7/H-13, if cleanup cannot be disabled.)

**M6.3 `RecoveryDataDeleter` (FR-15). [REV2 — full rewrite: honest scope, durable retry marker]** Renamed for honesty (H-11): the relay-side call is `deleteMyOutboundEvents`, not `deleteMyEvents` — it can only remove events this device authored (Rules enforce sender-owns-delete, M5.2). Events the partner authored are outside this device's delete authority and are **not** claimed as deleted. The confirmation UI states this plainly before double confirmation: *"This removes everything on this device and everything you've sent to your partner. Updates your partner sent to you are not removed by this action — revoke the pairing, or ask your partner to delete their own data too, for that."*

Order:
1. Confirm twice.
2. Cancel workers `rr-outbox`, `rr-outbox-delayed`, `rr-partner-poll`.
3. **Before touching any `rr_*` row**, write a durable marker file `noBackupFilesDir/recovery/pending_relay_delete.json` = `[{pairId, requestedAtEpochMs}, ...]` for every pair — deliberately **outside** the tables step 5 deletes, so the retry instruction survives that step (H-12; the original design stored this marker in `rr_kv`, which step 5 then destroyed).
4. For each pair: `deleteMyOutboundEvents(pairId)`, `setPairRevoked(pairId)` (best effort; local peer state updates regardless of remote success).
5. Delete all `rr_*` rows.
6. Delete keyset files, Keystore alias `…recovery.keyset_master`, `recovery_prefs`.
7. A WorkManager one-off (or an app-start check) reads `pending_relay_delete.json` and retries `deleteMyOutboundEvents` / `deletePair` for any pair still listed, removing that pair's entry from the marker file only once the remote call actually succeeds. After 7 days of failed retries, stop retrying and **log** the unresolved entry (never silently drop it) for manual follow-up.

`deletePair` is local bookkeeping only — it removes the parent doc but Firestore does **not** cascade-delete the `events` subcollection (H-11); the sender-owns-delete call in step 4 is what actually removes this device's ciphertext, and full relay cleanup for a pair requires both members to run this flow (or the 30-day TTL/expiry-enforced-reads in M5.2 to eventually make the partner's remaining events unreadable and then TTL-deleted). Keep Firebase Auth user (shared with other features) — app-wide account deletion, when built, MUST call `RecoveryDataDeleter` first. Play flavor: Settings shows "Delete recovery data" and a link to the web deletion page URL from `BuildConfig.DATA_DELETION_URL`.

**M6.4 Revoke/pause. [REV2 — copy updated for read-cutoff change]** Revoke: `setPairRevoked`; local `REVOKED`; cancel PENDING outbox. Because Rules now require `state=='active'` for reads (M5.2, H-10), revoke also immediately denies both members' relay read access to that pair's past events, not just new writes. Pause toggle: cancel PENDING outbox; new sessions skip outbox. Both immediate, no confirmation delays. Copy: "Revoking immediately stops new updates and stops relay access to past updates too. Anything already downloaded to your partner's phone stays there."

**M6.5 Crisis resources (FR-16). [REV2 — richer validation]** `assets/crisis_resources.json` schema `[{"name":"","description":"","phone":"","url":"","region":"","lastVerifiedAt":"","source":""}]` **[REV2: region/lastVerifiedAt/source fields added, M-18]**. Screen lists entries with tap-to-call/tap-to-open. Owner supplies verified content before release; unit test `CrisisResourcesReleaseTest` fails release builds if the file is missing/empty/invalid, if any entry lacks `region`/`lastVerifiedAt`/`source`, or if a `phone`/`url` field fails a basic format check — the original "present, non-empty, valid JSON" check verified structure but not that the resource values were current or usable (M-18).

**M6.6 Diagnostics (INV-14).** `Diagnostics` local counters: `db_open_ms` (bucketed), `key_unavailable`, `outbox_pending` (bucketed 0/1–5/6–20/21+), `send_fail_code`, `decrypt_fail`, `sig_fail`, `replay_drop`, `invalid_flood`, **`dropped_event` [REV2, M-02]**, **`inbound_retryable_local` count [REV2, H-05]**. Shown in a debug-only screen; uploaded via existing analytics/crash reporter only if the user opted in (default OFF); no user IDs beyond the reporter's own; no timestamps of individual sessions.

**Validate:** AC-19..AC-22. **Tests:** T-M0-2 extended (Start fresh leaves quarantine folder intact **and the quarantine move itself passes a WAL-consistency check, H-07**); delete-data test (this device's relay docs gone, keysets gone, Keystore alias gone, other app data intact; **partner-authored docs are untouched and the UI never claimed otherwise, C-01**); **pending_relay_delete.json retry test: force the request-time remote delete to fail → verify the marker survives step 5 and a later retry succeeds and clears it (H-12)**; revoke propagation across two devices (**including read denial, not just write denial, H-10**); crisis test (**including the new required metadata fields, M-18**).

### M7 — Hardening and release gates

1. Device matrix: Infinix Smart 9 (XOS) plus at least one Samsung, one Xiaomi, one Pixel/emulator API 34+, one Android Go/low-RAM device. Run AC list on each.
2. Benchmarks: run M1.6 on the reference device per its methodology; record P50/P95/P99 in `docs/recovery-perf.md`.
3. Deploy: Firestore rules, TTL policy (`gcloud firestore fields ttls update expireAt --collection-group=events --enable-ttl`), App Check registration **and enforcement** (see M5.2 — enforce before public release, not after a metrics-review delay), Remote Config default. **[REV2]** Re-confirm the Anonymous Auth cleanup setting (M0.7) is still as recorded.
4. Security tests: SEC checklist (section 10) executed; DB file entropy check (**a sanity check, not proof of correct encryption or key handling, L-01**); keyset file not readable as plaintext; `adb logcat` scan shows no plan/session/partner text; screenshot/recents/lock-screen notification tests.
5. **[REV2]** 16 KB gate: static alignment (M0.6) **plus a runtime pass on a 16 KB-page-size emulator/device** covering app startup, SQLCipher DB open, migrations, Firebase SDK init, QR scanner init, and crypto operations — the original plan only checked binary alignment, which does not prove runtime compatibility (H-18).
6. Store gates (Play flavor): Data safety form, account/data deletion web page + form entry, health/sensitive-content policy review; 16 KB gate (both static and runtime) green; **[REV2]** confirm repo `targetSdk` meets the **current** Play requirement recorded in M0.8, re-verified at this submission date (H-17).
7. Owner legal gates (blocking public release, not build): DPIA, consent copy approval, controller registration status, cross-border basis, clinical wording review.
8. Staged rollout per section 13.

---

## 9. Error Handling

| Situation | Detection | Behavior | User-visible |
|---|---|---|---|
| DB not ready during intervention | `DbState != Ready` | Intervention proceeds; recorder retries 30 s then drops event | none |
| Key/keyset unavailable | any `GeneralSecurityException`/`IOException`/missing alias | `KeyUnavailable`; never delete; M6.1 | recovery screen |
| DB corrupt (`SQLiteDatabaseCorruptException`, `cipher_integrity_check` fails) | open/health check | same as above, keep files | same |
| Relay unreachable / timeout | `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `withTimeout` | keep PENDING, `attempts++`, `Result.retry()` (30 s exp backoff, max delay by WorkManager) | none; status "queued" |
| Quota | `RESOURCE_EXHAUSTED` | retry with backoff; never drop; **[REV2]** counts against the quota-headroom alert in §14 (H-01) | none |
| `PERMISSION_DENIED` on send | read `pairs/{pairId}`: revoked → mark peer REVOKED, cancel outbox; missing/other → after 3 consecutive → `FAILED` | banner "Pairing needs attention" | banner |
| `UNAUTHENTICATED` / uid changed | auth state | M6.2 | re-pair prompt |
| **[REV2]** Inbound decrypt/verify failure, classified `PERMANENT_INVALID` | exception in M3.3 decode with no plausible transient cause | store `rr_seen(valid=0)`, count, advance cursor, no notification | none unless flood → banner |
| **[REV2, NEW]** Inbound failure classified `RETRYABLE_LOCAL` | keyset/DB transiently unavailable during processing | do NOT advance cursor; retry next poll/listener cycle; demote to `PERMANENT_INVALID` after 5 retries | none |
| Schema/unknown `t` | strict parser | treat as `PERMANENT_INVALID` | none |
| Duplicate inbound | `rr_seen` hit | ignore | none |
| Migration failure | Room exception | do not open; `Corrupt` path; no destructive fallback | recovery screen |
| Process death mid-session | M2.4 | session → `INTERRUPTED`; **[REV2]** if the s.start already reached the relay, a reconciling s.end is also queued (H-04) | history shows "Interrupted" |
| Clock/timezone change | `RiskWindowEvaluator` recompute | none needed | none |
| Camera denied | permission result | show manual pairing help | dialog |
| Notification permission denied | result | dashboard badge only | none |
| Remote Config kill switch | flag false | **[REV2]** no new outbox rows created at insertion time (M2.2), workers no-op | notice on dashboard |
| Firestore write success but ack lost | retry same `eventId` | idempotent overwrite of same content | none |
| Partner receipt missing >24 h | `SENT` without `ACKNOWLEDGED` **[REV2 rename]** | status text change | "may not have received" |
| **[REV2, NEW]** Relay-delete step of `RecoveryDataDeleter` fails at request time | exception from `deleteMyOutboundEvents`/`deletePair` | durable marker file retried by background job (M6.3, H-12) | none (silent retry); logged if unresolved after 7 days |

Rules: never surface stack traces or exception text to UI; never block intervention UI on any error; every catch either maps to a state above or rethrows — no empty catch.

---

## 10. Security Requirements

**Invariants [REV2 — two added]**
- INV-1 Intervention rendering never depends on DB, Keystore, Tink, Firebase, WorkManager, or network.
- INV-2 No plaintext plan/session data outside the encrypted DB; never logged.
- INV-3 Passphrase bytes are zeroed only after confirmed DB open and only if the discovered SQLCipher integration's own lifecycle allows a subsequent reopen (verified in M0.3, H-08); never at construction.
- INV-4 Key/DB failure never deletes DB, keys, or quarantine; only explicit user action removes data.
- INV-5 **[REV2]** No DB, key blob, keyset, or recovery pref file is included in Android's own backup or device-transfer mechanisms (verified by T-M0-3). OEM-specific transfer tools that bypass Android's standard mechanism are a documented residual risk (H-09), tested against the M7 device matrix, not covered by an absolute guarantee.
- INV-6 Every outbound event is signed then encrypted; every inbound event is decrypted, verified against the pinned key, schema-validated, and dedupe-checked before use.
- INV-7 Peer keys are set only by in-person pairing; never auto-updated.
- INV-8 Event row + outbox row + `seq` increment commit in one transaction.
- INV-9 No foreground service, exact alarm, or expedited work for sync.
- INV-10 Notifications never contain session details; lock screen shows generic text.
- INV-11 All recovery UIs use `FLAG_SECURE` (+ recents screenshot disabled API 33+); sensitive screens sit behind `PrivacyGate` when a secure lock screen exists.
- INV-12 Sync/persistence failures never block or delay the intervention UI.
- INV-13 Firestore holds only: uid, pairId, opaque ciphertext, and the envelope fields in M5.1. No free text, no plaintext session data.
- INV-14 Diagnostics contain no user content and are opt-in, default off.
- INV-15 **[NEW, REV2, H-19]** Exactly one peer may be in state `ACTIVE` per user at a time; starting a new pairing requires revoking the current one first.
- INV-16 **[NEW, REV2, H-10/M-14]** A pair not in state `active`, or an event past its own `expireAt`, is unreadable via Firestore Rules to both members — regardless of whether the TTL job has actually run yet.

**Adversary handling**
| Adversary | Defense |
|---|---|
| Person holding unlocked phone | `PrivacyGate` on data screens; neutral shortcut label "Pause"; neutral notifications; recents/screenshot blocking |
| Relay operator / network | E2EE; pinned keys; signed events; metadata exposure disclosed in consent |
| Rogue authenticated Firebase client | Rules (membership, `state=='active'` for read+write, sender==auth.uid, expiry, field/size limits — **[REV2]** read now gated identically to write, H-10); partner app reads only pinned pair paths; signature verify; flood banner |
| Malicious/coercive partner | Consent screen; user can pause/revoke instantly (now cuts off relay reads too); partner receives only coarse events; no data beyond the fixed payload; **[REV2, NFP-12 accepted risk]** a malicious partner can still send validly-signed but semantically pointless events — this cannot be prevented by signature/schema checks alone and is accepted for V1 (see Risk Register) |
| Lost/stolen locked device | FBE + SQLCipher + non-exportable Keystore keys |
| Rooted device / instrumentation | Out of protection scope; zeroization is best-effort only and is not relied on for security claims |

**Copy rule:** never claim "zero-knowledge" or "unbreakable". Approved wording: "End-to-end encrypted. The relay can see when updates are sent and how large they are."

**Other requirements [REV2]**
- SEC-1 Firebase App Check enabled (Play Integrity provider, both flavors' signing certs registered) **and enforced before public release** (moved up from "after M7 metrics review" — the Spark quota is trivially exhaustible by an unauthenticated write flood otherwise).
- SEC-2 Firestore rules exactly as M5.2 (including the read-side `state`/`expireAt` checks); rules unit tests in CI.
- SEC-3 `android:exported=false` on all new components except the launcher-shortcut path.
- SEC-4 Release build strips debug App Check provider.
- SEC-5 QR payload contains public keys only.
- SEC-6 `PrivacyGate` never stores credentials.
- SEC-7 Dependencies pinned to exact versions (M-15); run Gradle dependency-check/`osv-scanner` in CI; fail on High/Critical unresolved advisories.
- SEC-8 Legal gates (M7.7) complete before public release.

---

## 11. Performance Requirements

- PERF-1 Tap→first frame of `InterventionActivity`, **warm** process: P95 ≤ 100 ms on Infinix Smart 9, measured per the M1.6 methodology (20 iterations after 5 warm-up, raw-sample percentile, fixed thermal/battery state, `timeToInitialDisplayMs`) **[REV2, H-16]**.
- PERF-2 Same, **cold** process: P95 ≤ 800 ms on the reference device, same methodology **[REV2]**.
- PERF-3 No Room/Keystore/Tink/Firebase/WorkManager initialization before first frame (verified with Perfetto trace/`reportFullyDrawn` markers).
- PERF-4 DB open and all repository work on `Dispatchers.IO`; StrictMode debug clean on recovery paths.
- PERF-5 **[REV2]** `OutboxWorker`: 10 s per-write timeout, bounded concurrency ≤5 across pairs (never within a pair). *Target* (measured, not a hard gate): a run of ≤20 items completes in P95 ≤ 30 s on 3G-class network. *Hard ceiling* used to size the WorkManager execution window: ≤ 40 s worst case (H-15 — these are two different numbers for two different purposes; the original single "<30 s for 20 items with a 20 s per-write timeout" spec was internally inconsistent).
- PERF-6 Release APK size increase from this feature reported per ABI; ≤ 8 MB universal APK growth — this is a **release-artifact size budget**, not a claim about what a Play user actually downloads, since Play normally serves split/device-specific artifacts (L-02).
- PERF-7 Battery: only two scheduled jobs (`rr-outbox`/`rr-outbox-delayed` on demand, `rr-partner-poll` nominally every 15 min when paired); no wakelocks, no FGS.
- PERF-8 History list scrolls without dropped-frame regressions (Macrobenchmark `FrameTimingMetric` P95 within default budget on reference device).

---

## 12. Testing Requirements

Unit (JVM): `TechniqueSelector`, `RiskWindowEvaluator` (overnight, day mask, DST/zone via `Clock`), `SessionStateMachine`, `EnvelopeHeader` byte layout golden vectors **(including exact ECDSA signature encoding, M-16)**, `EnvelopeCodec` round-trip and every tamper case (M3), payload parser strictness, sequence/gap logic **including per-pair head-of-line ordering (H-02)**, outbox scheduling math **including the insertion-time `rr-outbox-delayed` guarantee (H-03)**.

Instrumented (device/emulator): SQLCipher file-backed only (never in-memory) — encrypted-DB migration from prior version; DB reopen after process kill; key-alias deletion → `KeyUnavailable` with files intact; backup/restore exclusion (`bmgr`); **WAL-safe pre-migration and quarantine copy (H-07)**; `KeysetStore` persistence including **open→close→reopen after zeroization (H-08)**; `PrivacyGate` (API 28–29 authenticator combination, API 30+); `FLAG_SECURE` and recents behavior; notification lock-screen visibility; process-death during session **including interrupted-session reconciling `s.end` (H-04)**; offline/online outbox in strict per-pair order; camera scan pairing E2E on two devices **with two-way fingerprint confirmation required (H-14)**; **pairing blocked while an ACTIVE peer exists (H-19)**; **revoked-pair and expired-event reads denied (H-10, M-14)**; **inbound retryable-vs-permanent classification and atomic cursor advancement (H-05, H-06)**; **pending-relay-delete marker survives local-data wipe and is retried (H-12)**.

Firebase emulator: full rules suite (M5 tests, including the new read-authorization cases above).

Benchmarks: PERF-1, PERF-2, PERF-8 on reference device, per the M1.6 methodology.

Security: logcat scan for plan/session/partner strings; DB header not plaintext SQLite (`file` shows data, high entropy — a sanity check, not proof of correct encryption, L-01); no `*.tink` readable JSON; heap/strings scan is NOT a pass criterion (INV-3 is hygiene, not a guarantee).

Manual scenarios: (1) lock-screen change while app installed → key still works; (2) new fingerprint enrolled → key still works; (3) factory-reset-and-restore from Google backup → no recovery data restored, app starts clean; (4) partner reinstall → re-pair required, old events not decryptable; (5) airplane mode day; (6) OEM battery-saver/force-stop then reopen → outbox drains in order; (7) night shift user edits risk window → selector follows; **[REV2] (8) attempt a second pairing while one is ACTIVE → blocked (H-19); (9) revoke a pair, then confirm the former partner's app can no longer read that pair's past events (H-10).**

CI: unit + rules tests on every PR; instrumented + benchmark before release; 16 KB static gate; grep gates (M0.5); `CrisisResourcesReleaseTest` on release (**now checking the extended metadata schema, M-18**).

---

## 13. Deployment Requirements

- Dependencies: pinned to exact versions in the version catalog (M-15); new: `tink-android`, ZXing core, ML Kit barcode-scanning (bundled), CameraX, `firebase-appcheck-playintegrity`, `firebase-appcheck-debug` (debug only), Remote Config, `androidx.biometric`, Macrobenchmark/Baseline Profile (test modules). Existing Firebase BoM reused.
- DB: version +1 explicit migration; schema JSON committed; forward-only. Take a pre-migration file copy into `noBackupFilesDir/premigration/` (kept until next successful launch of the following release) using the **WAL-safe copy procedure from M0.4** (H-07) — never leave dangling copies longer than that release cycle.
- Backend: deploy `firestore.rules` (with the read-side `state`/`expireAt` checks, M5.2); TTL policy on `events.expireAt`; App Check registration **and enforcement before public release** (SEC-1); Remote Config parameter; Firebase billing stays Spark by default, **with TTL-delete volume included in the quota-headroom tracking in §14** (H-01); **confirm the Anonymous Auth automatic-cleanup setting matches what was recorded in M0.7** (H-13).
- Flavors: APK flavor first; Play flavor adds Data-safety/deletion assets **and the current-Play-target-API check from M0.8** (H-17). Both build in CI.
- Rollout: internal → closed (≥10 real devices incl. Transsion) → staged 5% → 20% → 50% → 100% with ≥7 days per stage while the user base is small; halt criteria: key-unavailable rate >1% of active devices, any crash-loop report, outbox failure rate >5%, rules-denied spike, **Firestore usage trending to exceed 70% of daily free quota when TTL-delete volume is included (H-01)**. Kill switch M5.8 disables sync without a release.
- Rollback: no downgrade of DB schema; rollback = halt rollout + kill switch + ship fix-forward. Envelope `v/alg` future-proofs partner version mismatches: unknown `v` docs are ignored and counted, never crash.
- Docs: `docs/recovery-baseline.md`, `docs/recovery-perf.md`, rules test report, release checklist.

---

## 14. Monitoring Requirements

Signals (no user content; aggregated; opt-in for upload, INV-14): DB open time bucket; `key_unavailable` count; outbox pending bucket; send failure codes; decrypt/signature failures; replay drops; invalid-flood banner shown; worker run counts; **`dropped_event` count from the intervention Channel (M-02); inbound `RETRYABLE_LOCAL` vs `PERMANENT_INVALID` counts (H-05); `pending_relay_delete` entries older than 24 h (H-12)**.
Platform dashboards: Play Vitals (ANR/crash, startup), Firebase console (Firestore reads/writes/**deletes — including TTL-triggered deletes, H-01** — vs Spark quota, App Check request metrics, rules denies), Crashlytics/existing reporter (non-fatals only for state transitions above; custom keys allowlisted; never plan/session text).
Alert thresholds (manual review weekly at this scale): any spike in `PERMISSION_DENIED`; key-unavailable >1%; ANR >0.47% (Play bad-behavior threshold) or crash >1%; **Firestore usage (reads + writes + all deletes, TTL included) >70% of daily free quota [REV2, H-01]**.

---

## 15. Risk Register

| # | Risk | L | I | Mitigation | Detection |
|---|---|---|---|---|---|
| R1 | Key loss on phone change/reset loses history (no export in V1) | H | H | Clear copy at onboarding and Settings; quarantine not delete; V1.1 export planned | support reports; `key_unavailable` counter |
| R2 | Pairing MITM via physical QR substitution | L | H | In-person only; **[REV2] two-way** fingerprint confirmation (H-14); no remote key updates (INV-7) | user reports |
| R3 | Forged/replayed relay events; **[REV2]** a malicious but validly-signed partner can send semantically nonsensical events (NFP-12, accepted — signature checks can't catch semantic abuse) | M | H | Signatures + seen-set + rules; revoke available instantly | `sig_fail`, `replay_drop`, flood banner |
| R4 | Metadata exposure (timing/frequency) | H | M | Disclosed in consent; day-coarsened `expireAt`; no other metadata | n/a (accepted) |
| R5 | Partner coercion | M | H | Instant revoke/pause (**[REV2]** now also cuts relay reads, H-10); coarse events only; consent copy | user feedback |
| R6 | OEM kills background work → delayed sync | H | M | Plain WorkManager, retry on next open; UI shows delivery status; no promises of real-time (**[REV2]** copy now says "periodic," not "15 min," M-03) | delivery-status metrics |
| R7 | SQLCipher/Tink native or version incompatibility (16 KB, compileSdk) | M | H | Step 0 baseline; CI 16 KB **static and runtime [REV2, H-18]** gate; pinned exact versions (M-15) | CI failure |
| R8 | Wrong clinical framing/claims | M | M | No efficacy claims; self-help disclaimer; clinical review before release | review checklist |
| R9 | Privacy law non-compliance | M | H | Consent, deletion, DPIA, registration owner tasks; no release until done | release checklist |
| R10 **[REV2]** | Firestore quota/cost surprise, **including TTL-delete volume counting against the same quota as writes (H-01)** | L | M | Small payloads, retention-cutoff enforced by Rules not just TTL timing, explicit quota-headroom tracking including deletes, alert at 70% | Firebase console |
| R11 | Latency target missed on low-end device | M | M | Static first frame, baseline profile; measured M1 per defined methodology (H-16) | Macrobenchmark |
| R12 | Existing key-handling code violates INV-3/4 in ways audit missed | M | H | M0.3 tests T-M0-1/2, plus open→close→reopen zeroization test (H-08) | test failure |
| R13 **[REV2]** | Anonymous uid lost on reinstall, **or on automatic cleanup after 30 days of inactivity if that setting is enabled and cannot be disabled (H-13)**, breaks pairing | H | L | Re-pair flow M6.2; documented; **M0.7 verifies/resolves the cleanup setting before M4** | pairing-attention banner |
| R14 | Lock-screen absence disables privacy gate | M | M | Banner; documented limitation | n/a |
| R15 **[NEW, REV2]** | One device's "Delete recovery data" cannot remove the partner-authored events on the relay (C-01, H-11) — a user or reviewer may expect full deletion | M | M | UI states the limitation explicitly before confirmation; revoke + 30-day expiry-enforced-unreadability is the practical backstop for the partner's remaining events | support reports; UI copy review |
| R16 **[NEW, REV2]** | Single-active-partner limitation (H-19) frustrates users who want more than one accountability partner | L | L | Documented V1 scope decision; candidate for V1.1 if requested | support/user feedback |
| R17 **[NEW, REV2]** | The `pending_relay_delete.json` retry marker could itself be lost (e.g. swept up in a "Start fresh" quarantine move) before its retries succeed (H-12) | L | M | Marker lives outside both the DB and the quarantine path; M6.1's "Start fresh" does not touch `noBackupFilesDir/recovery/pending_relay_delete.json`; checked at every app start | retry-job logs |

---

## 16. Open Questions

Each has a default the build uses until answered. None blocks starting M0–M3.

| # | Question | Default used by build | Blocks | Owner |
|---|---|---|---|---|
| OQ-1 | Exact text of product Req 8/10/19/20 and their mapping to FR-1..FR-17 (spec was not supplied here). | FR list in §2 is authoritative. | Release sign-off | Product owner |
| OQ-2 | Room major version and SQLCipher integration currently in the repo; existing key-provider code. | Step 0 records; all steps are integration-agnostic. | Nothing (M0 answers) | Builder |
| OQ-3 | Firestore database location and Kenya DPA basis for cross-border processing (ss. 48–50), and role of the Nairobi VPS for this feature; DPIA and controller registration status. | Firestore-only relay; consent screen covers cross-border. | Public release | Owner / counsel |
| OQ-4 | Does Play policy classify Firebase Anonymous Auth here as "account creation" (in-app deletion + web deletion link)? | Ship "Delete recovery data" + web link in Play flavor. | Play release | Owner |
| OQ-5 | Repo `minSdk`; BiometricPrompt authenticator combination behavior on API 28–29 for `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`. | Test on API 28/29 device in M7; if unsupported there, fall back to `DEVICE_CREDENTIAL` only for those API levels. | M2.7 sign-off | Builder |
| OQ-6 | Are PERF-2 cold-start threshold (800 ms) and PERF-1 warm threshold (100 ms) achievable on Infinix Smart 9? | Thresholds as written; ratify after first M1 measurement per the defined methodology (H-16). | M1 exit | Owner |
| OQ-7 **[REV2]** | Does Tink Android currently expose ML-DSA-65 or Ed25519 (either is a reasonable alternative to ECDSA_P256 worth evaluating for simpler/deterministic signatures, L-06)? | V1 uses ECDSA_P256 with the exact DER/Tink-prefix encoding pinned in M3.3 (M-16); `alg` field allows later migration. | Nothing | Builder |
| OQ-8 | Verified crisis-resource entries (Kenya + international), now including region/lastVerifiedAt/source per entry (M-18). | Screen ships; release blocked if file empty or missing required metadata. | Public release | Owner |
| OQ-9 | Existing crash reporter and whether an app-wide PIN/lock already exists. | Use existing reporter; implement `PrivacyGate` as specified (reuse existing lock only if identical semantics). | Nothing | Builder |
| OQ-10 | Clinical review of technique wording and whether partner-facing update phrases are acceptable to target users. | Wording in §8 M1.5/M5.6. | Public release | Owner / clinician |
| OQ-11 **[NEW, REV2]** | Is Firebase Anonymous Auth automatic account cleanup enabled for this project (H-13)? | Assume enabled until M0.7 verifies otherwise; disable if possible, else document residual risk and rely on M6.2 re-pair. | M4 start | Builder |
| OQ-12 **[NEW, REV2]** | Current Google Play target-API requirement for new app updates at submission time (H-17) — this shifts over time; look it up fresh at M0 and again at M7. | Record actual value each time in `recovery-baseline.md`; gate release on meeting it. | Play release | Builder |
| OQ-13 **[NEW, REV2]** | Is a single-active-accountability-partner limitation acceptable for V1's target users, or is multi-partner support actually needed sooner (H-19)? | Single partner for V1 (Decision 25); revisit for V1.1 if user feedback (R16) says otherwise. | Nothing | Product owner |

---

## 17. Acceptance Criteria

Intervention & UX
- AC-1 Tapping the **Pause** shortcut or Home button opens `InterventionActivity`; works in airplane mode with app process killed and DB in `KeyUnavailable`.
- AC-2 PERF-1/PERF-2 measured per the M1.6 methodology and recorded; PERF-3 verified in a trace.
- AC-3 Technique selection follows M1.3 for all boundary cases (unit tests).
- AC-4 Breathing pacer and timers survive rotation; reduced-motion setting produces non-animated pacing.

Persistence & privacy
- AC-5 Migration from prior DB version succeeds on real encrypted DB; integrity checks clean.
- AC-6 Session + outbox rows are atomic (fault-injection test).
- AC-7 Plan/windows/history screens require `PrivacyGate` when a secure lock exists; intervention/crisis/consent do not.
- AC-8 `FLAG_SECURE` set on all recovery UIs; recents thumbnail hidden on API 33+ and screenshots blocked.
- AC-9 After `bmgr` backup/restore and device-transfer simulation on the M7 device matrix, no recovery data or key material is restored through Android's own mechanisms; app starts clean without crashing. **[REV2]** (This is verified against the tested device matrix, not claimed as a universal guarantee across every OEM's proprietary transfer tool — see INV-5/H-09.)

Crypto & sync
- AC-10 Envelope round-trip passes; every tamper/wrong-key/forged-sender case fails as specified; **[REV2]** signature encoding matches the exact Tink template pinned in M3.3 (M-16).
- AC-11 Keysets survive restart; keyset corruption yields `Unavailable`, no crash; **[REV2]** the DB also survives an open→close→reopen cycle after passphrase zeroization (H-08).
- AC-12 No plaintext plan/session/partner text in logcat or on disk outside the encrypted DB.
- AC-13 Two-device mutual QR pairing reaches ACTIVE on both; wrong-pairId and reused QR rejected; uid mismatch never ACTIVE.
- AC-14 Firebase Emulator rules suite passes (all cases in M5, **including revoked-pair and expired-event read denial, H-10/M-14**).
- AC-15 **[REV2]** Offline sessions queue and deliver exactly once, **strictly in per-pair `seq` order** (never a higher seq sent before a lower one for the same pair, H-02), after reconnect; retries idempotent.
- AC-16 **[REV2]** Inbound replay/duplicate/garbage handled: one stored row, correctly classified as `SUCCESS`/`PERMANENT_INVALID`/`RETRYABLE_LOCAL` (H-05), flood banner at threshold, no crash.
- AC-17 **[REV2]** Receipts update delivery status to "Partner's device acknowledged" (M-08); "may not have received" appears after 24 h without one.
- AC-18 Notifications show only generic text on lock screen; dashboard requires gate.

Lifecycle & release
- AC-19 Deleting Keystore alias in test → `KeyUnavailable`; files untouched; "Start fresh" (**[REV2] using the WAL-safe copy procedure, H-07**) moves data to quarantine and works.
- AC-20 **[REV2]** Revoke and pause take effect immediately on both devices; writes **and reads** to/from a revoked pair are denied by rules (H-10).
- AC-21 **[REV2]** "Delete recovery data" removes this device's local recovery rows, keysets, alias, prefs, and this device's own authored relay events (with background retry via the durable marker if the request-time attempt fails, H-12); it explicitly does **not** claim to remove the partner's authored relay events, and the UI says so (C-01). Other app data intact.
- AC-22 Crisis screen shows entries from JSON **including required metadata (M-18)**; release build test fails on empty/invalid/incomplete file.
- AC-23 **[REV2]** 16 KB gate green on **both** the static CI check (M0.6) and the runtime validation pass (M7.5, H-18); SQLCipher `.so` aligned.
- AC-24 Kill switch flag disables sync **including new outbox-row creation, not just sending** (M-09), without a release.
- AC-25 **[NEW, REV2]** Pairing reaches ACTIVE only after both sides have explicitly confirmed the other's fingerprint (H-14).
- AC-26 **[NEW, REV2]** Starting a new pairing while an ACTIVE peer already exists is blocked until that peer is revoked (H-19).
- AC-27 **[NEW, REV2]** A session recovered as `INTERRUPTED` whose `s.start` had already reached `SENT` produces a matching reconciling `s.end` (H-04); one whose `s.start` never left the device does not.
- AC-28 **[NEW, REV2]** Cursor advancement in inbound processing is transactional with event persistence: a simulated crash between decode-success and commit never loses or duplicates the event on restart (H-06).

---

## 18. Definition of Done

- All milestones M0–M7 exit checks pass; AC-1..AC-28 **[REV2: was AC-24]** pass on Infinix Smart 9 and the device matrix.
- INV-1..INV-16 **[REV2: was INV-14]** each have at least one automated or documented test/evidence link in the PR.
- Firestore rules deployed and tested (including the read-side state/expiry checks); TTL policy active; App Check registered **and enforced before public release** (moved up from a post-launch review, SEC-1).
- `docs/recovery-baseline.md` (**including the M0.7 Anonymous Auth cleanup finding and the M0.8 Play target-API recording**), `docs/recovery-perf.md`, release checklist committed; dependency versions pinned to exact releases (M-15); no High/Critical open advisories.
- No unresolved `TODO`/`FIXME` referencing incomplete recovery-feature functionality (a repo-hygiene lint, not proof of completeness — L-04); no debug provider or test-mode rules in release artifacts; `SafeLog` gates green.
- Owner legal gates (OQ-3, OQ-4, OQ-8, OQ-10) resolved before public release; otherwise the build stays in internal/closed testing.
- **[REV2]** OQ-11 (Anonymous Auth cleanup) and OQ-12 (current Play target-API requirement) explicitly resolved, not left at their build-time defaults, before public release.
- Change is limited to files named in this plan; no edits to blocking engine, existing pairing key/escape code, or unrelated screens.
