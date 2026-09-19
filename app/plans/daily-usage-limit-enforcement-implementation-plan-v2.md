# Daily App Usage Limit & Pre-Block Warning Enforcement — Implementation Specification (v2)

**Status:** Approved for implementation — supersedes v1 in full
**Scope:** V1 Requirement 4 (daily per-app limit enforcement) and Requirement 5 (pre-block warnings)
**Consumer:** AI build agent — implement literally as written; do not re-derive architecture choices

**What changed from v1 and why (read once, then ignore — the sections below already contain the resolved design, not this summary):**
v1's biggest defect was treating "elapsed time since the last accessibility event" as a usage clock and treating `UsageStatsManager` polling as a simple drop-in substitute for it. Neither is a valid duration-measurement technique. v2 replaces both detection paths' internal accounting with an explicit session state machine (start/stop timestamps + a periodic ticker), makes exactly one path the accounting authority at any given moment (no double-counting), removes `AlarmManager` and `WorkManager` from anything timing-critical, corrects the overlay mechanism claims against verified platform behavior, and defines idempotent warning/block delivery. One claim from the second review round is explicitly rejected below (§5, Decision 1) because it was not supported by verified platform behavior — the resolution favors what was actually confirmed against current documentation and real working implementations, not the audit's assumption.

---

## 1. Feature Overview

The app tracks how long the user spends in specific other apps each day and enforces a daily time budget configured for each tracked app.

- **Requirement 4 — Enforcement:** When a tracked app's accumulated usage for the current day reaches its configured `daily_limit_ms`, the app is blocked from further use until the daily window resets.
- **Requirement 5 — Pre-block warnings:** Before the limit is reached, the user receives warnings inside the tracked app so they can wrap up before being blocked.

The system runs entirely on-device for its detection/tracking/warning/blocking logic, with no network calls in that path. It continues working, in an explicitly modeled and disclosed degraded state, across OEM battery management behavior, permission revocation, reboots, and Android's background-execution restrictions on `targetSdk 36`.

---

## 2. Requirements

**Functional**
- FR1: User configures a daily time limit per tracked app, minimum 1 minute, no fixed maximum.
- FR2: When accumulated usage for a tracked app on the current day reaches its limit, the app is blocked and the user is returned to the home screen (or, where eviction isn't possible, the block overlay itself fully occludes and disables interaction with the app).
- FR3: The user receives two in-context warnings before the block: at 10 and 5 minutes of remaining budget for limits ≥ 10 minutes; at 50% and 90% of the limit used for limits < 10 minutes.
- FR4: Usage accumulation resets once per day, anchored to the device's current local calendar date.
- FR5: The block state and day-tracking state survive device reboot without requiring the user to open the app first, subject to the Direct Boot storage constraint in §5 Decision 8.
- FR6: The user can see, at any time, whether enforcement is currently active or degraded, and why.
- FR7: Each warning is delivered at most once per threshold per day, and delivery is retried if the first attempt fails to actually reach the user (§9).
- FR8: Changing a tracked app's limit mid-day immediately recomputes thresholds and re-evaluates block/warning state against the new limit (§6, step 6).

**Non-functional**
- NFR1: No network calls in the detection, tracking, warning, or blocking code path. (Crash telemetry is a separate, disclosed, opt-out side channel — see Decision 12 — and is excluded from this guarantee's scope.)
- NFR2: Stored usage and limit data is encrypted at rest.
- NFR3: Duration and threshold math never uses wall-clock time as an input; only `SystemClock.elapsedRealtime()` deltas within a single boot session are used for duration.
- NFR4: The system never crash-loops when any single permission (Accessibility, Usage Access, notifications, overlay) is unavailable; it degrades to a defined, disclosed state instead.
- NFR5: At most one component writes to a given tracked app's usage accumulator at any moment (single accounting authority — see Decision 2); no two detectors independently add duration to the same counter.

**Explicitly out of scope for V1 (decided):**
- Split-screen / multi-window / picture-in-picture disambiguation of "which app is in use."
- Multi-user Android profiles and Work Profile usage visibility.
- Preventing Safe Mode boot or the user manually clearing app data — both are detected-where-possible and disclosed, never prevented (§5 Decision 11).
- In-app PIN/biometric lock to prevent disabling protection (V2 candidate).
- Sub-second accounting precision. The system's accounting resolution is bounded by its tick interval (§5 Decision 4); this is a stated, tested tolerance, not an aspiration to eliminate.
- **Website (per-domain) daily usage limits — split off as a separate future feature (decided 2026-09-14).** This plan enforces daily limits on app-package targets only. Req 4's text names "apps and websites"; per-domain website usage tracking is its own feature, to be planned and built on its own, because the OS exposes no per-URL duration data (`UsageStatsManager` is per-package) — per-site accounting would require extending the accessibility URL scanner into a duration feed, with known fuzziness (hidden address bar → last-known-host attribution) and split-budget semantics (a site reachable both via its own app and via a browser counts twice, as two independent targets). Note: limiting a *browser itself* (e.g., "Chrome: 30 min/day") is an ordinary package limit and already works under this plan with no extra build.

---

## 3. Existing System Context

- Room schema already contains `daily_limit_ms` and `daily_usage_tracker`; this feature wires them into real enforcement logic without redesigning the tracked-app schema's onboarding, app-selection UI, or any unrelated feature.
- An `AccessibilityService` and an overlay-capable blocking engine already exist in the codebase; this spec extends their event handling and adds a second, independent detection path. It does not rebuild either from scratch.
- Do not touch onboarding, app-selection UI, subscription/paywall logic, or any tracked-app-list feature not named in this document.

---

## 4. Architecture

**Core principle (this is the actual fix from v1): detectors observe, one accounting engine per tracked app decides, one persisted state owns the truth.** Neither detection path is allowed to independently add duration to the stored accumulator. Authority is assigned by a capability hierarchy, not by both paths racing to write.

```
                    ┌─────────────────────────────────────────────────┐
                    │  Room (SQLCipher) — per tracked app:             │
                    │  dailyLimitMs, dailyUsageTrackerMs,              │
                    │  localDateAnchor, timezoneIdAtAnchor,            │
                    │  warning1State, warning2State, blockedToday      │
                    └───────────────────────┬───────────────────────────┘
                                            │  (single Enforcement Transaction —
                                            │   read + accumulate + evaluate + write,
                                            │   one @Transaction DAO method,
                                            │   Dispatchers.IO.limitedParallelism(1))
                                            ▼
                    ┌─────────────────────────────────────────────────┐
                    │        Enforcement Accounting Engine              │
                    │  (pure, unit-testable; the ONLY place that       │
                    │   ever mutates dailyUsageTrackerMs)              │
                    └───────────────┬─────────────────────┬───────────┘
                                    │                     │
                     writes gated by "who is authority"  │
                                    │                     │
        ┌───────────────────────────┘                     └───────────────────────────┐
        ▼                                                                             ▼
┌────────────────────────────────────┐                          ┌────────────────────────────────────┐
│ Path A: AccessibilityService        │                          │ Path B: Continuous Foreground        │
│  - Session state machine:           │                          │ Service (specialUse)                 │
│    ACTIVE(pkg, startElapsedMs,      │                          │  - ALWAYS RUNNING once onboarded,    │
│           bootCount) | IDLE         │                          │    independent of Accessibility      │
│  - TYPE_WINDOW_STATE_CHANGED opens/ │                          │    state — this removes the          │
│    closes sessions                  │                          │    "who restarts it" problem         │
│  - Live ticker (every 10s while     │                          │  - Discovery loop: queryEvents()     │
│    ACTIVE) re-evaluates thresholds  │                          │    every 30s to detect which app is  │
│    without waiting for next event   │                          │    foreground (runs regardless of    │
│  - Overlay: TYPE_ACCESSIBILITY_     │                          │    whether it's a tracked app)       │
│    OVERLAY (no SYSTEM_ALERT_WINDOW  │                          │  - Session ticker: every 5s once a   │
│    needed — verified, see Decision  │                          │    tracked app is confirmed          │
│    1) + GLOBAL_ACTION_HOME eviction │                          │    foreground with <3 min remaining  │
│  - IS ACCOUNTING AUTHORITY whenever │                          │    budget (fine-grained near limit)  │
│    Accessibility is enabled+healthy │                          │  - Overlay: TYPE_APPLICATION_OVERLAY │
│                                      │                          │    (SYSTEM_ALERT_WINDOW) — IS the    │
│                                      │                          │    block, no HOME-launch dependency  │
│                                      │                          │  - IS ACCOUNTING AUTHORITY only when │
│                                      │                          │    Path A is confirmed unavailable   │
└──────────────────────────────────────┘                          └──────────────────────────────────────┘

Supporting components:
  - ProtectionStatusMonitor — arbitrates which path is authority right now; exposes FULL / DEGRADED_POLLING_ONLY / DEGRADED_NO_DETECTION
  - BootAndRebootReceiver + cold-start BOOT_COUNT check — reconciliation, device-protected-storage-only work (§5 Decision 8)
  - Daily WorkManager job — best-effort maintenance only, NOT the rollover mechanism (§5 Decision 5)
```

---

## 5. Architecture Decisions

1. **Path A draws its overlay via `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`, hosted from the bound `AccessibilityService`, and this does not require `SYSTEM_ALERT_WINDOW`.** This was disputed in review; it is confirmed correct against current Android reference documentation and multiple verified production implementations (e.g., accessibility-service-hosted overlays used specifically to *avoid* requesting `SYSTEM_ALERT_WINDOW`). The rejected counter-claim ("this is non-compliant and requires SAW") is not supported by evidence and is not adopted. What review correctly flagged as under-specified — input/focus handling — is addressed concretely in §8 Step 4: the block overlay must be focusable and touch-modal (so it actually intercepts input and back/home cannot dismiss it prematurely), while the warning overlay must be non-focusable and non-touch-modal (so it doesn't interfere with the app underneath). This distinction was missing in v1 and is now explicit.
2. **Exactly one path is the accounting authority at a time; the other only observes.** Path A is authority whenever Accessibility is enabled and its process is confirmed healthy. Path B is authority only when Path A is unavailable. This eliminates double-counting (a real defect in v1, where both paths could independently add duration to the same counter) without sacrificing detection coverage, because Path B is architected to run continuously regardless of authority state (Decision 3), so no handoff-triggered cold start is ever required.
3. **Path B runs continuously from shortly after onboarding, for as long as the OS allows, regardless of whether it currently holds accounting authority.** This directly resolves two v1 defects at once: (a) the "chicken-and-egg" problem where a poller that only runs while a tracked app is foreground has no mechanism left to notice the *next* time a tracked app becomes foreground, and (b) the "automatic takeover" problem, where starting a new foreground service reactively, after Accessibility is lost while the app is backgrounded, can be blocked by Android 15's background-FGS-start restrictions. Because Path B is already alive, no new background start is ever required at the moment authority needs to transfer — only a flag flip.
4. **No detection path infers duration from "elapsed time since the last event." Both paths use an explicit session state machine (start timestamp + boot count) plus a periodic ticker that re-evaluates thresholds while a session is open, independent of whether a new event has arrived.** Path A ticks every 10 seconds while a session is `ACTIVE`; Path B's discovery loop runs every 30 seconds to find the current foreground app, then tightens to a 5-second ticker once a tracked app is confirmed foreground with less than 3 minutes of remaining budget. This replaces v1's "clamp long gaps to 5 minutes" rule, which is removed entirely — that rule was a specific, avoidable source of under-enforcement (a real 30-minute session could previously be recorded as 5 minutes; that is no longer possible because the ticker, not the next event, drives accounting). The accepted accounting resolution is the tick interval itself (10s for Path A, 5–30s for Path B), which is documented as a tolerance, not hidden as a false precision claim.
5. **`AlarmManager` is not used anywhere in this feature, and `WorkManager`'s daily job is maintenance-only, not the correctness mechanism for day rollover.** Day rollover is evaluated transactionally, as the first step of every single accounting write (§6 step 6), by comparing the stored `localDateAnchor` to the device's current local calendar date — so rollover happens correctly the next time any tracked app is used, with or without the daily job ever running. `WorkManager`'s minimum periodic interval is 15 minutes and its execution time is not guaranteed to the minute (confirmed against current `PeriodicWorkRequest` reference documentation), so it is unsuitable as a correctness dependency and is used here only to proactively clear stale UI status (e.g., `blockedToday`) on days a tracked app is never opened at all.
6. **`isAccessibilityTool="false"`, with Play's standard non-accessibility-tool declaration and prominent-disclosure flow.** Current Play Console policy defines "screen time management" and "app usage tracking and limiting" as a recognized, legitimate use case distinct from being an accessibility tool; declaring `isAccessibilityTool="true"` for this app would misrepresent its purpose and risk policy action, independent of any technical benefit. Accept that Advanced Protection Mode (or any future platform mechanism that similarly gates on this flag) will revoke Accessibility for users who enable it — this is modeled generically (Decision 7), not hard-coded to one named platform feature.
7. **Model the state as `AccessibilityUnavailable`, not `AAPM-triggered`.** Whatever causes Accessibility to become unavailable — the user manually disabling it, an OEM auto-revoking it, or a platform security feature — produces the same observable signal and the same response (Path B takes accounting authority). The cause, where determinable, is logged for telemetry only; it never gates the response logic itself. This keeps the design correct against future platform changes without requiring a rewrite.
8. **`ACTION_LOCKED_BOOT_COMPLETED` never touches the SQLCipher-encrypted Room database or decrypts the Keystore-wrapped key.** Credential-protected storage (where the encrypted database lives by default) is inaccessible before first unlock; attempting Keystore decryption or a Room query at this point is a guaranteed failure, not a race condition to guard against. The locked-boot receiver is restricted to device-protected-storage-only operations: persisting a "needs reconciliation" flag via `Context.createDeviceProtectedStorageContext()`-backed `SharedPreferences`, and re-enqueuing the maintenance `WorkManager` job (`WorkManager`'s own database is device-protected-storage-safe). Actual reconciliation — reading the Room database, checking day rollover, restoring `ProtectionStatusMonitor` state — runs on `ACTION_USER_UNLOCKED` or the next app process start, whichever comes first.
9. **No PBKDF2.** The SQLCipher passphrase is a randomly generated 256-bit key, wrapped at rest by a hardware-backed `AndroidKeyStore` AES-256-GCM key. There is no user-entered passphrase in this app, so a password-stretching KDF adds CPU/battery cost on every key-decrypt with no corresponding security benefit.
10. **Usage Access (`PACKAGE_USAGE_STATS`), not `QUERY_ALL_PACKAGES`, gates `UsageStatsManager`.** `QUERY_ALL_PACKAGES` is a separate package-visibility permission and is not declared for this feature. If a future, unrelated feature needs full package-list visibility, that is a separate Play Console declaration outside this feature's scope — do not add it speculatively here.
11. **Self-defeat (Force-Stop, Clear Data, disabling Accessibility, Safe Mode, revoking overlay permission) is detected where technically possible and honestly disclosed; it is never prevented.** Force-Stop and Clear Data are OS-level actions the app cannot observe from inside its own killed/wiped process — the only correct response is honest reconciliation on next launch, not an attempt to prevent them. Safe Mode disables all third-party accessibility services and is out of scope by construction (the OS itself does this). Accessibility- and Usage-Access-revocation *are* detectable at runtime and drive `ProtectionStatusMonitor` transitions (§8 Step 6).
12. **Crash/diagnostic telemetry via Firebase Crashlytics only: opt-out, and scoped to crash/error/state-transition metadata — never usage content.** No package names, durations, or configured limits are ever transmitted. This is a disclosed exception to the "no network calls" framing for the enforcement path specifically (NFR1); the privacy disclosure to users distinguishes "enforcement runs entirely on-device" from "the app never makes a network call," because the second statement would be false with Crashlytics active.
13. **SQLCipher integration uses `net.zetetic:android-database-sqlcipher`'s `SupportFactory`, passed to `Room.databaseBuilder().openHelperFactory(...)`.** This is the documented, supported integration point; no custom SQLite driver work is required beyond this.
14. **Warning delivery and block-state transitions are idempotent and retryable, not fire-and-forget.** Each warning has an explicit state (`NOT_DUE`, `PENDING_DELIVERY`, `DELIVERED`) persisted in the same transaction that detects the threshold crossing; a warning is only marked `DELIVERED` after its overlay/notification actually attaches successfully, and `PENDING_DELIVERY` is retried on the next tick. `blockedToday` is treated as "block transition already emitted today," continuously re-validated against `dailyUsageTrackerMs >= dailyLimitMs`, not as an independent source of truth (this also makes Decision 15's mid-day limit-change behavior correct for free).
15. **Changing `dailyLimitMs` mid-day immediately recomputes both warning thresholds and resets both warning states to `NOT_DUE`, without resetting `dailyUsageTrackerMs`.** `blockedToday` is re-evaluated immediately against the new limit: a lowered limit already exceeded by today's usage blocks immediately; a raised limit that's no longer exceeded un-blocks immediately. This is a stated product decision, not left implicit.
16. **Path B's block is the overlay itself — a full-screen, focusable, touch-modal `TYPE_APPLICATION_OVERLAY` — never a launched `Activity`.** Android 14+ further restricts starting Activities from the background; depending on a background Activity as the block surface is a reliability risk this design avoids entirely by making the overlay sufficient on its own, matching how comparable shipped apps implement `SYSTEM_ALERT_WINDOW`-based blocking today.
17. **The battery-optimization exemption prompt is not a mandatory onboarding gate.** It is offered contextually — only after telemetry (or a first-run device check) indicates Path B's foreground service has actually been killed unexpectedly on that device — rather than requested unconditionally from every user during onboarding, which is unnecessary friction and an unjustified prerequisite if the architecture works without it on a given device.
18. **`android:foregroundServiceType="specialUse"` is a technically-available FGS category, not a pre-approved one.** The Play Console declaration for this specific use case (continuous, on-device usage-limit enforcement) is a required, reviewed submission step (§13), not an assumption baked into the architecture.
19. **A single, injected `@Singleton`-scoped repository method (`processUsageObservation(...)`) performs read + accumulate + threshold-evaluate + write as one Room `@Transaction`, executed on one shared `Dispatchers.IO.limitedParallelism(1)` dispatcher instance shared by every caller** (Accessibility service, foreground service, boot receiver, `WorkManager` job). This closes the read-modify-write race that a merely-serialized-writes design does not close on its own (two callers can still both read stale state before either writes; wrapping the whole cycle in one transaction prevents this).
20. **Day identity is stored as a (local date, IANA timezone ID) pair, not a bare date string.** Rollover policy: at every accounting transaction, compute "today" using the device's *current* timezone; if it differs from the stored date (regardless of why — including a timezone change), roll over. This is a deliberately simple, stated policy: a timezone change can cause an early or late rollover relative to true elapsed wall-clock hours, and that is an accepted consequence, not a defect requiring anti-abuse countermeasures.
21. **Feature rollout uses Play Console staged rollout percentage plus a local, build-time kill-switch default, not a remote server-side feature flag.** A network-fetched runtime flag would contradict the feature's own no-network-dependency framing and add operational complexity (caching, staleness, rollout-state uncertainty) not justified for V1.

---

## 6. Data Flow

1. **Onboarding (per tracked app):** User sets `dailyLimitMs` → written to Room → both warning thresholds computed and cached alongside the limit.
2. **Foreground-enter observation (either path):** A tracked package is confirmed foreground → if no session is open for it, open one: `ActiveSession(package, startElapsedRealtimeMs = now, bootCount = current)`. This does not yet write to Room.
3. **Tick (every 10s for Path A while `ACTIVE`; every 5–30s for Path B per Decision 4):** If a session is open and the current caller holds accounting authority (Decision 2) for this package, compute `sessionElapsedMs = elapsedRealtimeNow - session.startElapsedRealtimeMs` (only valid if `bootCount` still matches current boot count — otherwise discard the session and start a new one from now, per the reboot-mid-session fix below), call `processUsageObservation(package, sessionElapsedMs)` — this is the single transactional read+accumulate+evaluate+write described in Decision 19 — then reset the session's `startElapsedRealtimeMs` to now (so the next tick measures only the incremental delta, not the whole session again).
4. **Foreground-exit observation:** Tracked package leaves foreground → run one final tick exactly as in step 3, then close the session (`session = null`).
5. **Threshold evaluation (inside the transaction, after the accumulator is updated):** In order — if `dailyUsageTrackerMs >= dailyLimitMs`: set `blockedToday = true` if not already, return `Block`. Else if usage ≥ second threshold and `warning2State == NOT_DUE`: set `warning2State = PENDING_DELIVERY`, return `ShowWarning(2)`. Else if usage ≥ first threshold and `warning1State == NOT_DUE`: set `warning1State = PENDING_DELIVERY`, return `ShowWarning(1)`. Else `NoAction`.
6. **Day rollover (runs first, inside the same transaction, before step 5):** If the stored `(localDateAnchor, timezoneIdAtAnchor)` does not match today's actual local date under the device's current timezone, reset `dailyUsageTrackerMs = 0`, `blockedToday = false`, `warning1State = NOT_DUE`, `warning2State = NOT_DUE`, and update the anchor — before evaluating anything else in this transaction.
7. **Delivery confirmation:** After the transaction returns `ShowWarning`/`Block`, the caller attempts to render the corresponding overlay (or post the notification, for the secondary channel). On confirmed success, a follow-up write sets the corresponding state to `DELIVERED`. On failure or crash before confirmation, the state remains `PENDING_DELIVERY` and is retried on the next tick for that package.
8. **Reboot:** `BootAndRebootReceiver` (device-protected storage only, per Decision 8) or the cold-start `BOOT_COUNT` check re-enqueues the maintenance `WorkManager` job and, once the database is accessible (`ACTION_USER_UNLOCKED` or app open), triggers a `ProtectionStatusMonitor` refresh. No per-app alarms exist to re-arm.
9. **Protection status:** Recomputed on cold start, on the maintenance job's run, and on Path B's discovery-loop tick — reads current Accessibility-enabled state and Usage Access grant state, updates `protection_status`, and drives the persistent status notification when degraded.

---

## 7. Implementation Sequence

1. **Room schema finalization + SQLCipher wiring** — no dependencies.
2. **Key generation and Keystore wrapping** — depends on (1).
3. **Enforcement Accounting Engine** (`processUsageObservation` transaction, pure threshold logic, unit-testable without Android framework) — depends on (1).
4. **Path A: AccessibilityService session state machine + ticker + accessibility overlay** — depends on (3).
5. **Path B: continuous foreground service (discovery loop + adaptive session ticker) + `SYSTEM_ALERT_WINDOW` overlay** — depends on (3).
6. **ProtectionStatusMonitor + authority arbitration between (4) and (5)** — depends on (4), (5).
7. **Onboarding permission flows** — depends on (4), (5).
8. **Maintenance WorkManager job** (best-effort only) — depends on (1).
9. **Boot/cold-start reconciliation, split by storage class** — depends on (8).
10. **Warning/block overlay UI (Compose, hosted in both a `TYPE_ACCESSIBILITY_OVERLAY` window for Path A and a `TYPE_APPLICATION_OVERLAY` window for Path B)** — depends on (3); wired into (4) and (5).
11. **Full cross-OEM physical device QA pass, including the short-limit timing matrix (§12)** — depends on all prior steps.

---

## 8. Detailed Implementation Steps

**Step 1 — Room + SQLCipher**
- Add dependency: `net.zetetic:android-database-sqlcipher` (latest stable 4.x) + matching `androidx.sqlite:sqlite`.
- Tracked-app entity fields: `dailyLimitMs: Long`, `dailyUsageTrackerMs: Long`, `localDateAnchor: String` (ISO-8601 date), `timezoneIdAtAnchor: String` (IANA ID), `warning1State: String` (`NOT_DUE`/`PENDING_DELIVERY`/`DELIVERED`), `warning2State: String`, `blockedToday: Boolean`.
- Write a versioned Room migration; explicit defaults for existing rows: `dailyUsageTrackerMs = 0`, `warning1State/warning2State = NOT_DUE`, `blockedToday = false`, `localDateAnchor` = today's date at migration time, `timezoneIdAtAnchor` = device's current timezone at migration time. Do not use `fallbackToDestructiveMigration`.
- Construct `SupportFactory(passphrase, hook, clearPassphrase = true)`, pass via `.openHelperFactory(factory)`.
- After opening the DB, run one read query as a self-test; route any `SQLiteException`/decryption failure to the key-failure recovery flow (§9).
- Exclude the app's data directory (or at minimum the key-wrapper storage and the encrypted database) from Android Auto Backup, so a restored ciphertext key-wrapper is never paired with a new device's non-exportable Keystore key (§9 covers the resulting, expected failure mode).

**Step 2 — Key management**
- Generate a random 256-bit key via `SecureRandom` on first launch.
- Generate an `AndroidKeyStore` AES-256-GCM key (hardware-backed where available) at the same time.
- Encrypt the random key with the Keystore key; store only ciphertext + IV, outside the SQLCipher-encrypted DB (to avoid a circular dependency).
- On each process start: decrypt via the Keystore key, use as the SQLCipher passphrase, rely on `SupportFactory`'s `clearPassphrase = true` for zeroing (do not duplicate this in a way that races with it).

**Step 3 — Enforcement Accounting Engine**
- One `@Transaction` repository method: `suspend fun processUsageObservation(packageName: String, incrementMs: Long, nowElapsedRealtimeMs: Long): EnforcementAction`.
- Internally, in order: (a) read the row, (b) apply day-rollover check (§6 step 6) if needed, (c) add `incrementMs` to `dailyUsageTrackerMs`, (d) evaluate thresholds (§6 step 5) using the cached threshold pair, (e) persist the updated row, (f) return the resulting `EnforcementAction` (`NoAction` / `ShowWarning(1|2)` / `Block`).
- Threshold calculation (computed once at limit-set time, cached, recomputed on limit change per Decision 15):
  ```kotlin
  fun calculateWarningThresholdsMs(dailyLimitMs: Long): Pair<Long, Long> {
      val tenMin = 10 * 60_000L
      val fiveMin = 5 * 60_000L
      return if (dailyLimitMs >= tenMin) {
          (dailyLimitMs - tenMin) to (dailyLimitMs - fiveMin)
      } else {
          (dailyLimitMs / 2) to ((dailyLimitMs * 9) / 10)
      }
  }
  ```
- This method must not perform any I/O outside the single Room transaction, and must be unit-testable with an in-memory database or fake DAO — no Android framework dependency in the core logic.

**Step 4 — Path A: AccessibilityService**
- Maintain in-memory state: `activeSession: ActiveSession?` where `ActiveSession(packageName, startElapsedRealtimeMs, bootCountAtStart)`.
- On `TYPE_WINDOW_STATE_CHANGED` for a tracked package with no open session: open one (§6 step 2).
- On the same event type for a *different* package while a session is open for a tracked package: run a final tick for the closing session (§6 step 4), close it, then open a new session if the new foreground package is also tracked.
- While `activeSession != null`, run a ticker every 10 seconds (`delay(10_000)` loop scoped to the service, cancelled when the session closes) that: checks `bootCountAtStart == Settings.Global.getInt(BOOT_COUNT)` — if mismatched, discard the session without crediting any delta and open a fresh one anchored to now (this is the reboot-mid-session fix); otherwise compute the incremental delta since the last tick/event and call `processUsageObservation`.
- Only call `processUsageObservation` when `ProtectionStatusMonitor` reports Path A currently holds accounting authority (Decision 2); otherwise the tick still runs (for local session bookkeeping) but does not write.
- Overlay rendering: obtain `WindowManager` from the service context, use `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` (confirmed to not require `SYSTEM_ALERT_WINDOW` — Decision 1). Block overlay: `FLAG_NOT_TOUCH_MODAL` **not** set (so it is touch-modal and intercepts input) and focusable. Warning overlay: `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` set (so it does not block interaction with the app underneath) and auto-dismisses after 5 seconds or on tap.
- Block eviction: after the block overlay attaches successfully (confirmed via its own attach callback, not assumed), call `performGlobalAction(GLOBAL_ACTION_HOME)`.
- Re-arm on next foreground-enter: because `blockedToday` is checked on every `processUsageObservation` call (via the day-rollover-then-threshold sequence), any subsequent attempt to foreground the same tracked package the same day re-triggers the block overlay + eviction — the design does not rely on the original overlay remaining visually present (this fixes v1's undefined overlay-vs-eviction race).
- Manifest: `android:isAccessibilityTool="false"`.

*Implementation note (2026-09-15, user-ruled extension of the transition-only text above).* **Connect-time usage re-acquire.** The Step 4 rules above open a session only on WSC transitions, so when a11y (re)binds while a tracked app already sits foreground and that app emits no window churn, Path A opens no session until the next event — the app's usage goes uncredited under FULL authority (flagged as `Open Product Decisions` #10, §12 handoff leg; Path B has no such gap because its authority-regained poll widens one lookback and re-acquires). User ruling: build the symmetric fix. `onServiceConnected` now also runs `reacquireUsageSessionOnConnect()`: resolves `rootInActiveWindow` (decisive under XOS lag; bounded re-checks, `MAX_CONNECT_REACQUIRE_ATTEMPTS = 3` × `DEFERRED_WSC_MS = 400` ≈ 1.2 s worst case while the root resolves), skips when the active package is already sessioned / is the app itself / emergency-protected / not limit-tracked, else evaluates the schedule and calls `syncUsageSession` — the same open path as a WSC event. The session anchors at connect-now, never earlier: the pre-rebind void span stays uncredited, preferring visible under-count over double-count. Device-verified 2026-09-16 on the X6532 (rebind while Shazam foreground): `connect re-acquire pkg=com.shazam.android scheduleBlocked=false` at rebind; tracker read 35.8 s later showed `used=33927` ms — the [rebind→read] span credited minus sub-tick remainder, with the 6 s pre-rebind void span correctly absent. Earlier same-night runs at schedule-blocked time (`scheduleBlocked=true`) confirmed the schedule gate is evaluated, not bypassed, at connect.

**Step 5 — Path B: continuous foreground service**
- `EnforcementForegroundService`, `foregroundServiceType="specialUse"`, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` describing daily usage limit enforcement.
- Start only from a foreground context: immediately after onboarding completes, and re-started whenever the app itself is opened for any reason if not already running. Never attempt a bare background start.
- **Discovery loop** (runs continuously while the service is alive, independent of tracked-app state): every 30 seconds, call `UsageStatsManager.queryEvents()` for the window since the last check, find the most recent `MOVE_TO_FOREGROUND` event's package. If it's a tracked package, treat this as a foreground-enter observation (§6 step 2) if no session is already open for it.
- **Adaptive session ticker:** once a tracked package's session is open under Path B, tick every 30 seconds by default; if the row's current `(dailyLimitMs - dailyUsageTrackerMs)` is under 3 minutes, tighten the ticker to every 5 seconds until the session closes or a block fires. This bounds worst-case warning/block slip near the limit boundary to 5 seconds rather than 60 seconds, which was v1's defect (a 60-second poll cannot reliably catch a 1-minute limit's 30-second and 54-second thresholds).
- Before each tick or discovery-loop iteration, check `AppOpsManager.checkOpNoThrow(OPSTR_GET_USAGE_STATS, ...)`; if not granted, stop the loop and let `ProtectionStatusMonitor` set `DEGRADED_NO_DETECTION` rather than looping on failures.
- Only call `processUsageObservation` when Path B currently holds accounting authority (Decision 2).
- Overlay rendering: `TYPE_APPLICATION_OVERLAY`, requires `SYSTEM_ALERT_WINDOW`, requested during onboarding. The overlay is itself the block (Decision 16) — full-screen, focusable, touch-modal, no attempt to launch a separate blocking `Activity`.
- Apply `android:filterTouchesWhenObscured` by setting `composeView.filterTouchesWhenObscured = true` programmatically on the `ComposeView` instance hosted inside the overlay (this is a real `View` property, applicable because `ComposeView` is a `View` subclass — it does not need a Compose Modifier equivalent). Additionally set `WindowManager.LayoutParams.FLAG_SECURE` on the overlay window to prevent screenshot/screen-recording capture of the block screen.

*Implementation note (2026-09-15, device QA).* Two device-caught facts shape the shipped loop. (1) The plan's "30-second discovery loop" is implemented as the service's existing 1.5s `queryEvents` poll (same window-latest-resumed read) with the plan's adaptive ticker as the accounting cadence on top — recorded in the service's doc comment; measured cadence: 5s tight ticks (~6.0s observed with ≤1.5s poll-grid slip) under 3 min remaining, one baseline tick of slip at the crossing (the cadence refine reads the persisted budget before its own write — deliberate, see `accountUsage`). (2) **Render-gate defect (device-caught 2026-09-15):** the service's foreground observation was overwritten unconditionally from each poll's window, and an event-less poll (no new `ACTIVITY_RESUMED` in the narrow window) carries `null` — so every tick-originated verdict failed the render gate `lastObservedForegroundPackage != packageName` and silently dropped (engine logged repeated `ShowWarning`/`Block`, `OverlayController` logged nothing). Path A was immune (sticky a11y state). Fix: `currentForegroundAfterPoll(previous, latest) = latest ?: previous` — an event-less poll carries the observation forward; 3 JVM tests. Re-verified after the fix on the X6532: full short-limit matrix under Path B authority (a11y off, `DEGRADED_POLLING_ONLY`), all 8 rows × thresholds/block pass — open-eval at exact thresholds (+0ms), banners rendered +13–45ms, wall +16–50ms, close tick credits the full open→Home span (no duration lost), wall lifts ≤1.5s after Home; the tighten-to-5s boundary re-proven in one 3-minute-boundary session (baseline +33146ms delta, then +6037/+6036ms).

**Step 6 — ProtectionStatusMonitor**
- Singleton; recomputes from: Accessibility-enabled state (`AccessibilityManager.getEnabledAccessibilityServiceList`), Usage Access grant state (`AppOpsManager`), `SYSTEM_ALERT_WINDOW` grant state (`Settings.canDrawOverlays`).
- Authority assignment (Decision 2): `Path A authority` iff Accessibility enabled AND its process reports itself alive (e.g., a periodic heartbeat write the service performs while connected); else `Path B authority` iff Usage Access granted AND `SYSTEM_ALERT_WINDOW` granted; else neither — `DEGRADED_NO_DETECTION`.
- States: `FULL` (Path A authority), `DEGRADED_POLLING_ONLY` (Path B authority), `DEGRADED_NO_DETECTION` (neither).
- On any transition into a degraded state: post a persistent notification ("Protection is paused — tap to fix", deep-linking to the relevant Settings screen) and log a Crashlytics non-fatal event with only the transition type, cause where determinable, OEM, and API level — never package names or usage values.
- Re-evaluated on: cold start, the maintenance job's daily run, Path B's 30-second discovery-loop tick, and immediately on any Accessibility connect/disconnect callback.

*Implementation note (2026-09-16, regression-caught copy fix).* The APP_STOPPED banner/notification copy is constrained to the always-true loss: "MyExodus was stopped from the Task Manager. Time limits stay paused until you re-enable." The earlier copy ("Re-enable it to resume blocking") contradicted a healthy Path A — after a Task-Manager stop with a11y still bound, app/URL blocking continues (only the poll path — time limits + fallback detection — is gated off by `requiresExplicitReEnable`), so neither "blocking is off" nor "all good" is honest. Both surfaces (`HomeScreen.DegradationBanner`, `ProtectionNotifications.degraded`) carry the same line. Also: `recovery_idle_body` said "you tap 'I need to use this app'" — a button that never existed; now names the real one ("Open MyExodus Recovery").

*Implementation note (2026-09-19, task #10 audit).* Path A liveness is the **process's own report** (`BlockerAccessibilityService.connected`) — line 232 taken literally. The old `isServiceEnabled() && connected` AND fed a second, redundant input into the authority: by the code's own semantics the client-side `getEnabledAccessibilityServiceList` read can only add a false negative (list false while a bound instance is delivering events) or a false positive (list true while no instance is alive — the XOS fresh-process case), never a liveness fact the in-process flag lacks. The audit that removed it began from `uiautomator dump` readings of a "frozen Limited chip" that turned out to be a stale a11y node tree on this device (the fixed build's dump still said "Limited" while `screencap` showed "Protecting"), so the change is recorded as **hardening, not a confirmed-defect fix** — the rendered state after the change agrees with the authority flow (screenshot + FGS heartbeat + transition log). `EnhancedModeHealthMonitor.isServiceEnabled` counts a live instance as enabled too (`connected || listed`), so a disagreeing list read cannot fire the "blocker is off" alert or blank the Settings/Setup enablement rows. Regression tests: `ProtectionStatusMonitorAuthorityTest`, `EnhancedModeHealthMonitorTest` (ADR #64). Device trap recorded with it: verify UI state on XOS with `screencap`, not `uiautomator dump`, while an a11y service is active.

**Step 7 — Onboarding permission flows**
- Order: Usage Access (`ACTION_USAGE_ACCESS_SETTINGS`) → prominent-disclosure screen → Accessibility (deep link to Settings) → `POST_NOTIFICATIONS` runtime prompt (secondary warning channel only) → `SYSTEM_ALERT_WINDOW` request (needed for Path B regardless of whether Accessibility is granted, since Path B always runs per Decision 3) → start `EnforcementForegroundService` while the app is foregrounded.
- Do not request battery-optimization exemption here (Decision 17); that prompt is deferred to the reactive flow in Step 6/§9.
- Each screen states plainly what breaks if the permission is denied, matching actual `ProtectionStatusMonitor` behavior.

**Step 8 — Maintenance WorkManager job**
- `PeriodicWorkRequest`, once daily, flexible window (e.g., 00:05–00:35 local, using `setNextScheduleTimeOverride` or a flex interval) — this job's only purpose is to clear stale `blockedToday`/warning-status UI on days no tracked app is opened, and to refresh `ProtectionStatusMonitor`. It is explicitly not relied upon for rollover correctness (Decision 5) — rollover is guaranteed by the transactional check in §6 step 6 regardless of whether this job ever runs on time.
- Re-enqueue with `ExistingPeriodicWorkPolicy.KEEP` at every app process start, since periodic work registration is not guaranteed to survive every reboot path without explicit re-enqueue.

*Implementation note (2026-09-16, emulator execution leg — VERIFIED).* The X6532 runs no WorkManager jobs at all (ADR #27), so this job's real execution is provable only on stock Android — the emulator-5554 leg. Verified: **registration + pin** (dumpsys job shows `Trace tag: DailyMaintenanceWorker`, `Minimum latency` ≈ next 00:05) and that WorkManager's early-guard correctly refuses forced runs of not-yet-due periodic work (`cmd jobscheduler run -f …` → `WM-WorkerWrapper: Delaying execution … before schedule`); production-image `adb root` denial rules out a clock jump, so the execution proof is the instrumented test below. **Defect found by this leg (production, not test-only): every `@HiltWorker` failed instantiation on stock Android** — `WM-WorkerFactory: Could not instantiate … NoSuchMethodException <init>[Context, WorkerParameters]` (HiltWorkerFactory map lookup missed, WM fell back to reflection) for HealthCheckWorker + PartnerSyncWorker on their genuine 15-min dispatch. Root cause: `androidx.hilt:hilt-compiler` (the processor generating `WorkerAssistedFactory` bindings for `HiltWorkerFactory`) was never on the KSP classpath — only Dagger's `com.google.dagger:hilt-compiler`, which does not process `@HiltWorker`. XOS masked it because no WM job ever runs there. Fixed 2026-09-16 (KSP dep + lockfile regeneration; debug + R8 release both build green); generated `*_AssistedFactory` classes confirmed in build output. **Runtime re-verified same day:** on the warm-up launch, logcat showed `WM-WorkerWrapper: Starting work for …HealthCheckWorker` + `…PartnerSyncWorker` with `Worker result SUCCESS` on their genuine dispatches (previously `NoSuchMethodException`), and the execution-leg test below passed for DailyMaintenanceWorker — all three `@HiltWorker` classes instantiate and run on stock Android. Execution-leg test: `app/src/androidTest/.../DailyMaintenanceWorkerInstrumentedTest.kt` — seeds a stale tracker row, re-enqueues the same unique work with `UPDATE` + a past `setNextScheduleTimeOverride` (the legitimate way to make pinned periodic work due now), polls the real dispatch → rollover (`rolloverStaleDays` clears msUsed/warnings/blockedToday, anchor → today), then restores the production KEEP pin. `DAILY_MAINTENANCE_WORK_NAME` is `internal` (was `private`) so the test can target the same unique work — visibility only, no runtime change. Adjacent stock-Android smoke from the same leg (conclusion unaffected by the fix above): the §8 Step 4 daily-limit wall **rendered on stock Android** — deskclock walled at limit over `TYPE_ACCESSIBILITY_OVERLAY` ("Clock is done for today", `/tmp/qa/emu-wall6.png`) and evicted to the launcher ~1 s after attach (`/tmp/qa/emu-wall7.png`; logcat `observe … Block` → `performGlobalAction(HOME)`). **Execution-leg test result: PASS** — `DailyMaintenanceWorkerInstrumentedTest#maintenanceRun_rollsStaleTrackerRow`, `OK (1 test)` in 11.4 s (emulator-5554, 2026-09-16): the real WorkManager dispatch of the pinned daily-maintenance periodic work runs on stock Android and rolls the stale tracker row over. Two run-mechanics findings from getting this green, worth keeping: (1) one-shot Gradle `connectedDebugAndroidTest` failed with `INSTRUMENTATION: Process crashed` / `failed to complete startup` — the instrumentation process was ANR-killed because the fresh-install app's first cold start (interpreted/JIT) raced the host Gradle build (guest CPU pressure avg10 ≈ 99.6%) against Android's process-start watchdog. Reliable recipe: wait for guest idle (load < 4), `adb install -r` both APKs, `cmd package compile -m speed -f com.example.myexodus` (AOT), warm launch, then direct `adb shell am instrument -w -r -e class <class>`; this also proved the hilt-compiler fix end-to-end. (2) `--write-locks` on an assemble-only invocation does not resolve androidTest/unitTest variant configurations, so a later `connectedDebugAndroidTest` fails config-time with "…not part of the dependency lock state"; use `./gradlew :app:dependencies --write-locks` for full lock coverage.

**Step 9 — Boot / cold-start reconciliation (split by storage class — Decision 8)**
- `BootAndRebootReceiver`, `directBootAware="true"` on the receiver only.
- On `ACTION_LOCKED_BOOT_COMPLETED`: `goAsync()`, launch a coroutine scoped to the receiver's own lifecycle (not a bare unscoped `CoroutineScope`) on `Dispatchers.IO`, using only a `Context.createDeviceProtectedStorageContext()`-backed `SharedPreferences` to set a "needs reconciliation" flag and re-enqueue the maintenance `WorkManager` job, then `pendingResult.finish()` in `finally`. **Do not touch Room or the Keystore-wrapped key here.**
- On `ACTION_USER_UNLOCKED` (registered dynamically once the app process is running) or on the next app cold start, whichever comes first: if the "needs reconciliation" flag is set, open the Room database normally, run `ProtectionStatusMonitor` refresh, clear the flag.
- Cold-start `BOOT_COUNT` check (in `Application.onCreate`): compare `Settings.Global.getInt(BOOT_COUNT)` against the last stored value (in device-protected-storage-backed prefs, so it's readable pre-unlock too, though the comparison itself only needs to run post-unlock since it doesn't require Room). If different, treat exactly as the `ACTION_USER_UNLOCKED` case above.
- Guard both the receiver-driven and cold-start-driven reconciliation behind a single persisted "reconciled for boot count N" flag (stored in the same device-protected-storage prefs, not an in-memory `AtomicBoolean`, since an in-memory flag doesn't survive process death and doesn't protect against the two triggers arriving in separate processes).

**Step 10 — Overlay UI**
- `WarningOverlayContent(level: Int, remainingMs: Long)` — auto-dismisses after 5 seconds or on tap.
- `BlockOverlayContent(packageName: String)` — full-screen, no dismiss affordance.
- Both consume window insets explicitly (`windowOptOutEdgeToEdgeEnforcement` is ignored on `targetSdk 36` regardless of whether it's set).
- Hosted per the window-type and flag rules in Steps 4 and 5 respectively — the Compose content itself is shared between both hosts; only the surrounding `WindowManager.LayoutParams` differ.

*Implementation note (2026-09-15, device QA).* The daily-limit wall is the shipped `BlockOverlayView`, composed to the v1 block screen's rhythm minus its action row: title → 8dp → message → 32dp → `block_screen_hint` (user-directed; the bare title+message sheet read as unfinished and the hint is the wall's only escape guidance). No affordances (Decision 16). `FLAG_SECURE` holds in release; debug builds only lift it so the wall can be screencapped for visual review (`flagsForContent(content, debugAllowsCapture = BuildConfig.DEBUG)`, user-approved). Bounds stay bar-free (`displayBounds()`) so Home/Back keep working (device-caught 2026-09-13) — the status/nav strips show the underlying surface over the wall; accepted cosmetic limitation of overlay mode. After block eviction succeeds the wall hides 1s later (`BLOCK_EVICT_HIDE_MS`, gen-guarded) — without it a stranded wall held focus over the launcher (device-caught 2026-09-15). Full short-limit matrix re-verified on the X6532 after these changes: all 8 rows pass (thresholds, banners, block + re-arm, eviction, hide).

*Implementation note (2026-09-16, regression-caught bounds defect — fixed, DEVICE-VERIFIED).* **The cover can land at the animating task's rect on XOS.** Reproduced on the X6532 while resolving `Open Product Decisions`-adjacent regression work: bringing an *already-running* blocked app to the front (Calculator, "Open MyExodus Recovery" leg) showed the wall as a small inset card — `dumpsys window` read the overlay window as `mAttrs=(80,179)(559x1242)` instead of the correct `(0,80)(720x1440)` — with the app's own keys visible and tappable to the left, right and below it; the app's `=` key was drawn over the wall's corner. Cause: `OverlayController.displayBounds()` derives the bar-free rect from the display context's `currentWindowMetrics.windowInsets`, and for a moment after a task switch XOS reports the *animating task's* geometry there (left/right ≈80px, top/bottom ≈179px — not system bars), so the whole-display first show computes a small, offset rect. It then *stays* that way: nothing re-bounds a shown cover until the next block transition, and `blockOtherRestrictedWindows()` runs at the moment of the block, i.e. also mid-animation. Fix (2026-09-16): the two calls that open a cover were extracted to `coverBlockedApp()` and are now re-run once at `COVER_SETTLE_MS = 700` after a new block transition (skipped when the cover is already gone, generation-guarded like every other show). The redraw is the same whole-display-then-largest-window path the transition uses, so split-screen pane coverage is unchanged. Note for QA: the wall is also torn down by `uiautomator dump` on this device (the dump's accessibility connection displaces the service's overlay window) — take screenshots before dumping, or relaunch the blocked app; this is a harness artifact, not an app behavior.
**Re-verified on the X6532 the same day (gated build, `GATE EXIT=0`, 487 unit tests green):** pressing HOME and then `am start -n com.transsion.calculator/.Calculator` — the exact already-running-app task-switch that produced the inset card — now reads, 1.2 s after launch, `Window #0 Window{2f03a50 u0 com.example.myexodus}` with `mAttrs={(0,80)(720x1440) gr=TOP START CENTER … ty=APPLICATION_OVERLAY}` (screencap confirmed the wall edge-to-edge below the status bar: "Calculator is blocked"). The 700 ms settle re-run was never observed mid-animation at the wrong rect in this run.

*Implementation note (2026-09-16, regression-caught tracking leak — fixed, DEVICE-VERIFIED).* **An expired rule kept daily-limit enforcement.** User-reported on the X6532 after the reboot-regression leg: a rule expired by the Recovery override (`blockExpiresAt = now − 1`, the shared escape hatch) showed the row state "Expired" — yet usage kept accruing, the level-1 warning banner fired ("About N min of today's time left"), and the accounting engine would have returned `Block` at the limit. Root cause: expiry was honored by exactly one consumer (`BlockDecisionEngine` Rule 3 and the row formatter), while the two enforcement-side filters keyed on `isEnabled` only — `RulesSnapshot.build`'s "single filter point" skipped paused rules but not expired ones (so both services' `isLimitTracked` read the rule as tracked and opened sessions), and `UsageAccountingEngine`'s two rule lookups (`processUsageObservation`, `remainingBudgetMs`) did the same against the DB. Fix: one shared predicate `RestrictionRule.isExpiredAt(now)` (entity file, deduplicated out of `ScheduleFormat`), honored by the snapshot build (now takes `nowEpochMillis` — explicit time input, publisher passes wall clock at build) and by both engine lookups (wall clock read inside each transaction, so a rule that expires mid-session stops accruing at the next tick even under a stale snapshot). Behavior of every existing consumer is unchanged: `AppBlockScanner`, `appBlockedContent` and `BlockerActivity.stillBlocked` all reach the snapshot read only after a `Block` decision, which Rule 3 already turns into `Allow` for an expired rule. Tests: `RulesSnapshotTest#build_excludesExpiredRules_fromBothEnforcementLists`, `UsageAccountingEngineTest#expiredRule_isUntracked_noAccumulationNoWarning`. **Contract note:** the working definition established at Step 3 — "tracked" = enabled rule with `dailyLimitMs > 0` — is hereby qualified to *enabled **and non-expired***. This document is silent on expiry (Rule 3 predates it), and the Rules row had already been claiming the rule inactive while accounting still ran for it — the qualifier makes the two agree rather than changing either.
**On-device A/B (X6532, gated build, 2026-09-16):** the same Calculator rule was flipped between expired and active with everything else held constant. *Expired* (`Always on` + 1 m daily limit + `until Sep 15, 2026`, row reads "Expired"): Calculator foreground 80 s → `usage-read` = ABSENT, zero `MyExodusUsage` observe lines, no banner, no wall, focus stayed on Calculator. *Control — the identical rule with `Ends` cleared:* 65 s → `MyExodusUsage: observe com.transsion.calculator +10004ms -> 60031ms/120000ms ShowWarning(level=1, remainingMs=59969)` and `used=60031 … w1=DELIVERED`, i.e. the pipeline does observe this package — the ABSENT result was the fix, not a dead observer. A second expired configuration (2 m limit + 10:00 PM–7:00 AM window, expired) held the row frozen at `used=78754` across 60 s of foreground with zero observe lines, ruling out accrual-through-the-window. Separately, an *active* always-on rule walled Calculator within ~1 s of launch (`BlockDecisionEngine` line 137 blocks on `isAlwaysOn` unconditionally), which is what makes the expired case's 80 s of uninterrupted use meaningful. One measurement caveat worth keeping: a tracker read taken seconds after a limit change still reflects observations that fired while the rule was active — compare against a baseline taken *after* the change, not before it.

---

## 9. Error Handling

| Condition | Behavior |
|---|---|
| SQLCipher key decryption or passphrase failure (including a restored-backup ciphertext that doesn't match a new device's Keystore key) | Catch explicitly; never crash-loop. Show a one-time "protection data could not be read" screen; require the user to re-confirm configured limits — a visible, one-time reset, never a silent one. |
| Usage Access revoked at runtime | Detected by `ProtectionStatusMonitor` within one discovery-loop tick (≤30s) while Path B is running, or on next cold start otherwise. Drives the authority/status transition in Step 6. Never throws uncaught from a tick. |
| Accessibility disabled/revoked at runtime (any cause — Decision 7) | Detected via the Accessibility connect/disconnect callback (near-immediate) or the next `ProtectionStatusMonitor` refresh. Authority flips to Path B if it's eligible (already running — no cold start needed, Decision 3); otherwise `DEGRADED_NO_DETECTION`. |
| `SYSTEM_ALERT_WINDOW` revoked at runtime | Path B cannot render its overlay; `ProtectionStatusMonitor` detects via `Settings.canDrawOverlays()` and reflects `DEGRADED_NO_DETECTION` if Path A is also unavailable, or continues under Path A authority otherwise. |
| Both detection paths unavailable | No enforcement occurs; this is surfaced honestly via the persistent notification and in-app status — the app never fabricates a state it cannot actually detect. |
| Device reboot mid-session | The next tick or event for that package detects `bootCountAtStart != current BOOT_COUNT`, discards the pre-reboot session without crediting any delta, and opens a fresh session anchored to the current boot. No negative or wrapped deltas are possible because the boot-count check happens before any delta is computed. |
| Wall-clock jump (`ACTION_TIME_CHANGED`/`ACTION_TIMEZONE_CHANGED`) | Logged for telemetry; never used to reset or extend the accumulator (duration math is `elapsedRealtime()`-only). Day rollover reacts only to the calendar-date comparison in §6 step 6, which does react to a timezone change by design (Decision 20) — this is intentional, not a workaround. |
| Concurrent writes from multiple callers | Impossible by construction: every write goes through the single `processUsageObservation` transaction on one shared `Dispatchers.IO.limitedParallelism(1)` dispatcher (Decision 19), and only the current authority holder calls it (Decision 2) — there is no scenario with two simultaneous authoritative writers for the same package. |
| Reboot with `BOOT_COMPLETED` suppressed by OEM and app not opened | Recovered by the cold-start `BOOT_COUNT` check the next time the app process starts for any reason, including Path A detecting a tracked app launch (which starts the app's process). |
| Immediate/zero limit reaching the accounting engine | UI clamps minimum to 1 minute. If a non-positive value reaches `processUsageObservation` regardless, treat as "already at limit" and return `Block` immediately rather than dividing by zero. |
| Warning overlay/notification fails to attach or post | State remains `PENDING_DELIVERY` (Decision 14); retried on the next tick for that package until it succeeds or the day rolls over. |
| Split-screen / multi-window foreground ambiguity | Out of scope for V1. Both paths attribute usage to whichever package most recently reported foreground entry; no special handling is implemented. |

---

## 10. Security Requirements

- Usage/limit data at rest is encrypted via SQLCipher, keyed by a randomly generated 256-bit key wrapped by a hardware-backed `AndroidKeyStore` AES-256-GCM key (Decisions 9, 13). No password-based key derivation.
- No network calls anywhere in the detection/tracking/warning/blocking path (NFR1). Crashlytics (Decision 12) is the sole, disclosed, opt-out exception, carrying no usage content.
- All internal `Service`, `BroadcastReceiver`, and `Activity` components are `android:exported="false"`.
- All internal `PendingIntent`s carry `PendingIntent.FLAG_IMMUTABLE`.
- Block overlays set `WindowManager.LayoutParams.FLAG_SECURE` (screenshot/recording resistance) and `composeView.filterTouchesWhenObscured = true` (tapjacking resistance) — both applied as described in Step 5, not as an XML attribute on a nonexistent Compose Modifier.
- Declare `android.permission.PACKAGE_USAGE_STATS` (Usage Access) and `android.permission.SYSTEM_ALERT_WINDOW`; do not declare `QUERY_ALL_PACKAGES` for this feature (Decision 10).
- All duration/threshold math uses `SystemClock.elapsedRealtime()`; `System.currentTimeMillis()` is used only for display and the calendar-date rollover comparison (NFR3).
- `android:isAccessibilityTool="false"`; complete Play Console's non-accessibility-tool declaration citing app-usage-limiting as the purpose (Decision 6).
- The app's data directory (or at minimum the key-wrapper and encrypted DB) is excluded from Android Auto Backup, since a Keystore-wrapped key cannot be meaningfully restored to a different device or after a factory reset (§9 handles the resulting failure mode explicitly rather than leaving it undefined).

---

## 11. Performance Requirements

- Path A's ticker runs only while a session is `ACTIVE` (i.e., only while a tracked app is actually foregrounded), at a 10-second interval — negligible background cost.
- Path B's discovery loop runs continuously at a 30-second baseline while the service is alive (by design, per Decision 3), tightening to 5 seconds only when a tracked app is within 3 minutes of its limit. `queryEvents()` calls are lightweight; this is not equivalent to continuous fine-grained polling and should not measurably affect battery life beyond a normal always-on foreground service's baseline cost — verify this specifically in the physical-device QA pass (§12), since it is a new, examined cost relative to v1's "poll only while foreground" design.
- All Room/SQLCipher operations run on `Dispatchers.IO`; never the main thread.
- End-to-end block latency (foreground-change observation to visible, attached block overlay) is a measured, not assumed, quantity. Targets: **P95 < 150 ms on the mid-tier reference device for Path A; P95 < 5.5 s on the low-end reference device for Path B** (bounded by Path B's tightened 5-second ticker near the limit, plus overlay attach time) — these are the acceptance targets, not claims about a theoretical minimum. Do not report a latency figure anywhere in documentation or QA sign-off that wasn't produced by this benchmark.
- No KDF runs on the app cold-start hot path (Decision 9 removes this cost entirely).
- Neither service holds a partial wake lock beyond its active tick/discovery window.

*Implementation note (2026-09-15, device QA).* All figures below were produced by the §12 benchmark harness on the X6532 (low-end reference: 8 cores @ 1.7 GHz, Android 34 / INFINIX XOS); per this section's rule, no other latency figure is reportable from this QA pass. Raw evidence in the QA log (`/tmp/qa/results.md`, §11 sections).

- **Path A latency** (shortest 1-min limit, blocked app reopened from launcher, `LatencyProfiler` staged pairs, n=20): end-to-end P50 **154 ms**, P95 **237 ms**, max 237 ms (detection P50 1–6 ms / P95 36 ms is the a11y half; submission ~110 ms; presentation ~33 ms). The P95 < 150 ms target is specified for the *mid-tier* reference device — no mid-tier device exists in this setup, so that target is **unmeasured**; this is a low-end measurement and is recorded as such. Crossing-block case: wall visible 1220 ms after the exact-threshold crossing (~200 ms pipeline + ~1 s accumulated 10 s-tick scheduling drift; credit stayed exact).
- **Path B latency** (same scenario, a11y off, `DEGRADED_POLLING_ONLY`; anchor = ~4 Hz `topResumedActivity` probe, n=20): foreground → wall visible P50 **808 ms**, P90 894 ms, P95 **914 ms**, max 964 ms — meets the P95 < 5.5 s low-end target. Render pipeline alone P50 16 ms / max 310 ms, bounding the attach half of the mid-foreground crossing case (≤5 s tightened ticker + ≤310 ms attach). Empirically, a fresh show requires the previous wall hidden (a lingering wall re-shows via rebind, which logs nothing) — an instrumentation observation only. Datapoint for §16 OQ3: a launcher sojourn under ~1.5–2 s can be missed entirely by `queryEvents`; ~2.5 s+ is reliably seen.
- **Path B continuous-loop cost** (Decision 3's examined cost; screen off, `dumpsys battery unplug`, 554 s window, loop verified running start-to-end: tick 720 → 1080 = 360 ticks): 3.88 s CPU via `/proc/<pid>/stat` jiffies and 4.38 s via `dumpsys batterystats` per-uid cumulative (sources agree within 13%) = **0.70–0.79% of one core** (≈0.09% of the 8-core device) for the full 1.5 s-poll loop. The whole FGS lifetime including screen-on Path B latency traffic = 1.10% of one core. Both are under V1's <2% continuous-CPU NFR; the loop's cycles land on the lowest frequency bins.
- **Wake locks:** zero held by the app at both window ends (`dumpsys power`, t0/t1) — the partial-wake-lock clause holds.
- Recorded caveats: battery level stayed 82 throughout (a 9-minute window is below the 1% gauge resolution, so CPU-time is the reportable metric, not mAh); the screen woke ~23 s before the window ended (the loop cadence is screen-independent; contamination negligible and conservative); a11y was off, so the window measures Path B cost only. Path A's cost when no session is `ACTIVE` is by construction nil (ticker runs only during `ACTIVE`).

---

## 12. Testing Requirements

**Unit tests**
- `calculateWarningThresholdsMs`: both branches, the exact-10-minute boundary, zero/negative clamping.
- `processUsageObservation`: rollover-before-evaluation ordering, correct `EnforcementAction` for each threshold boundary, idempotent re-entry (calling it twice with the same observation does not double-count — verify the caller-side session-restart-after-tick behavior, not just the transaction itself).
- Reboot-mid-session discard logic: session with a stale `bootCountAtStart` is discarded and not credited.
- Mid-day limit change: thresholds recompute, warning states reset to `NOT_DUE`, `blockedToday` re-evaluates against the new limit in both directions (lowered-and-now-exceeded, raised-and-no-longer-exceeded).
- Reconciliation dedup: boot-receiver and cold-start paths do not both trigger reconciliation for the same boot count.

**Instrumented/integration tests**
- Path A: simulate a `TYPE_WINDOW_STATE_CHANGED` sequence with a long dwell (e.g., 40 minutes) and verify the ticker credits the full duration incrementally, not a clamped or truncated amount (this is the specific regression test for v1's removed 5-minute clamp defect).
- Path B: verify the discovery loop detects a tracked app becoming foreground without any prior "already polling for this app" state — i.e., specifically test the case where no tracked app was foreground when the service started, confirming there is no chicken-and-egg gap.
- Path B: verify the ticker tightens to 5 seconds once remaining budget drops under 3 minutes, and both warning thresholds for a 1-minute limit (30s, 54s) are each caught within one tick interval of crossing.
- Authority handoff: with Path A initially authoritative, disable Accessibility mid-session; verify Path B (already running) picks up accounting authority without any service (re)start, and no usage duration is lost or duplicated across the handoff.
- SQLCipher: verify the on-disk file is not readable by the stock, unkeyed SQLite driver.
- Room migration: verify existing rows with pre-existing dormant field values migrate losslessly with the specified defaults.
- Warning delivery retry: force an overlay-attach failure and verify the warning state remains `PENDING_DELIVERY` and is retried on the next tick, not silently marked delivered.

**Manual / physical-device QA matrix** (execute all scenarios on each device)
| Device class | Focus |
|---|---|
| Stock Pixel (current + previous major Android version) | Baseline correctness, `targetSdk 36` compliance |
| Samsung OneUI | Background/battery-optimization killing of the continuously-running Path B service |
| Xiaomi HyperOS/MIUI | Boot-broadcast suppression, autostart flow, cold-start `BOOT_COUNT` recovery |
| Low-end / low-RAM device (Transsion-class or equivalent) | Measured block latency against §11 targets for both paths, SQLCipher I/O jank, overlay render jank, Path B's continuous-loop battery cost |

**Scenarios:**
- Short-limit timing matrix: verify correct warning/block behavior for limits of 1, 2, 5, 9, exactly 10, 11, 30, and 60 minutes, under both Path A and Path B independently.
- Day rollover at local midnight while asleep; across a manual timezone change; across a DST transition — confirm the stated policy (Decision 20) behaves as specified, not merely "doesn't crash."
- Reboot with Accessibility previously enabled: Path A resumes without user action; verify no crash before PIN entry and correct state restoration after unlock.
- Reboot with `BOOT_COMPLETED` suppressed by OEM: cold-start `BOOT_COUNT` check recovers on first app launch.
- Revoke Accessibility manually mid-session with Path B already running: authority flips within one Path B tick; no gap where neither path is authoritative and no data is lost.
- Revoke Usage Access and `SYSTEM_ALERT_WINDOW` with Accessibility active: Path A continues under authority; Path B's loop stops cleanly without crash-looping.
- Force-Stop the app, then relaunch: verify honest reconciliation, no crash, no silently-wrong state — documented as an accepted-limitation scenario, not a "prevented" one.
- Concurrent rapid switching between two tracked apps: verify no double-counting or missed accumulation.
- Mid-day limit change while a warning is `PENDING_DELIVERY`: verify the reset-and-recompute behavior (Decision 15) rather than a stale or duplicate warning.

---

## 13. Deployment Requirements

- Ship the Room migration as a versioned migration with explicit defaults (Step 1); no destructive fallback.
- Submit and obtain approval for: the Play Console Accessibility non-accessibility-tool declaration (Decision 6), the Usage Access disclosure, and the `specialUse` FGS use-case declaration (Decision 18) — do not ship to production before all three are approved.
- Roll out via Play Console staged rollout percentage; retain a local, build-time kill-switch default as the emergency path (Decision 21) rather than a remote feature flag.
- Rollback: disabling the feature must cleanly stop both detection paths (cancel Path A's ticker and unregister its usage of the accessibility event stream for this feature specifically, stop Path B's foreground service, cancel the maintenance job) with no orphaned notification, overlay, or foreground service left running.
- No backend deployment required; this is a pure client release.

---

## 14. Monitoring Requirements

- Crashlytics non-fatal events for: SQLCipher key-failure recovery triggered, `ProtectionStatusMonitor` transitions (old/new state, cause where determinable, OEM, API level — no package names or usage values), Room migration failure, reconciliation dedup path taken, warning delivery entering `PENDING_DELIVERY` and its resolution (delivered vs. still pending after N retries).
- Crashlytics fatal crash reporting enabled specifically for the Accessibility service, the foreground service, and the boot receiver, since these run outside normal `Activity` lifecycle and are the highest-risk components for silent failure.
- No usage-content analytics (package names, durations, limits) are ever transmitted, matching the disclosure in Decision 12.
- Track, as an internal non-PII aggregate, the distribution of `protection_status` and current accounting authority (Path A vs. Path B) across the install base by OEM/API level — this is the operational signal for detecting a new OEM behavior or platform change before it becomes a support complaint.
- Track Path B's continuous-service uptime/kill rate by OEM, since its "always running" design (Decision 3) is a new cost relative to v1 and should be validated in production, not just in the QA matrix.

---

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| Play Console rejects or later suspends the app over Accessibility API use despite `isAccessibilityTool="false"` + disclosure | Low–Medium | High | Complete the declaration form accurately (Decision 6); Path B is fully functional as a compliant, non-Accessibility-dependent path | Play Console policy status; pre-launch policy review |
| Play Console does not approve the `specialUse` declaration for continuous usage-limit monitoring | Low–Medium | High | Submit the declaration early, before the rest of the release is finalized, so there's time to adjust if rejected | Play Console review status |
| Continuous Path B service (Decision 3) has a materially higher battery cost than anticipated | Medium | Medium | Physical-device battery measurement is a named QA task (§12); tune discovery-loop interval upward if needed, since correctness only requires it to be short enough relative to the shortest supported limit, not fixed at 30s | Battery profiling in QA; production uptime/kill-rate telemetry (§14) |
| OEM kills the Path B foreground service despite `specialUse` + (reactive) battery exemption | Medium | Medium | Accept as a known OEM behavior; Path A remains authoritative when available; reactive battery-exemption prompt (Decision 17) offered when this is detected | Physical device QA matrix; production `protection_status`/uptime telemetry |
| A future platform change further restricts Accessibility or UsageStats access beyond what's modeled | Medium | High | The generic `AccessibilityUnavailable` model (Decision 7) and the always-running Path B (Decision 3) already accommodate this class of change without a redesign | Monitoring dashboard by OEM/API level |
| Room migration on existing installs fails or loses data | Low | High | Versioned migration with explicit defaults, tested against a pre-populated fixture DB (§12) | Migration integration test; crash telemetry on migration failure |
| User perceives the persistent "Protection paused" notification as a bug | Medium | Medium | Clear, specific copy naming exactly what's off and a one-tap fix path | Support tickets tagged to this feature |

---

## 16. Open Questions

These remain genuinely open. Confirm using current official sources (Android Developers documentation, Google Play Console policy pages) at the time each is resolved, since these are exactly the kind of platform/policy details that shift between OS and policy revisions — do not resolve them from memory or from a third-party blog post.

1. **Exact current wording and options of the Play Console `specialUse` FGS declaration form and the non-accessibility-tool Accessibility declaration form** for this specific use case (continuous, on-device daily usage-limit enforcement) — confirm directly in Play Console at submission time.
2. **Current minimum/recommended SQLCipher-for-Android and Room version pairing** for the project's actual `compileSdk`/Kotlin version — confirm against the SQLCipher-for-Android release notes and the AndroidX Room release notes at implementation time.
3. **Realistic, current OEM-specific delay/behavior of `UsageStatsManager.queryEvents()`** (not `queryUsageStats(INTERVAL_DAILY)`, which this design does not rely on for live accounting) on the specific OEM builds in the QA matrix — this affects whether the chosen 30-second/5-second Path B intervals are actually sufficient in practice, and should be empirically measured on real devices rather than assumed from documentation alone.
4. **Whether the persistent "Protection active"/"Protection paused" notification should be user-dismissible**, and what enforcement does if it's dismissed while in a degraded state — a product decision, not resolved by this spec.
5. **Whether Advanced Protection Mode's accessibility-revocation behavior (or an equivalent future mechanism) has shipped to stable Android at release time**, and on which versions — affects how much of the install base is expected to run under Path B authority at launch, and how much QA/messaging weight that state should get. Confirm against current Android release notes at release-planning time, not against this document's account of it.

*Open-question status (2026-09-15, Step 11 close-out — §18 requires each resolved-and-folded or explicitly accepted):*
1. **Moot (folded).** No Play distribution (ADR #15) — the Play-only declarations do not exist for this product.
2. **Resolved (folded).** Room 3.0.3 + the lockfile-pinned SQLCipher pairing (ADR #36; the 3.0.2 `TriggerBasedInvalidationTracker` IllegalMonitorStateException on-device drove the bump).
3. **Datapoint recorded; OEM legs open.** Measured on the X6532: a launcher sojourn under ~1.5–2 s can be missed entirely by `queryEvents`, ~2.5 s+ is reliably seen (see §11 implementation note). The remaining OEM builds ride the open OEM matrix.
4. **Pending product call** — tracked as item 9 in `Obsidian/MyExodus/Open Product Decisions.md`; the conservative option ships meanwhile (notification `setOngoing(true)`, not user-dismissible).
5. **Deferred to release planning (accepted non-blocking for the V1 build).** Confirm AAPM availability against current Android release notes at release time — it shapes the expected share of installs on Path B authority and the launch messaging weight, not the build.

---

## 17. Acceptance Criteria

- A tracked app is blocked within the measured latency targets (§11) of reaching its configured daily limit, under whichever path currently holds accounting authority, on every device in the QA matrix, including the shortest supported (1-minute) limit.
- Both pre-block warnings fire at the correct thresholds for both the ≥10-minute and <10-minute limit branches, exactly once per threshold per day, with retry-on-failure delivery semantics verified.
- No two components ever independently add duration to the same tracked app's accumulator in the same accounting period (verified by the authority-handoff integration test in §12).
- Daily usage resets correctly at local midnight, including across a timezone change and a DST transition, per the stated policy (Decision 20).
- Accounting authority correctly and automatically transfers from Path A to Path B (and is reflected in `protection_status`) when Accessibility becomes unavailable for any reason, without a new foreground-service start being required (verified by the authority-handoff test, not merely asserted).
- No enforcement-path code makes a network call.
- The on-disk database is verified encrypted.
- No crash or crash-loop occurs under any condition in §9's table.
- Reboot recovery (both normal and OEM-suppressed `BOOT_COMPLETED`) restores correct day-state without touching Room/Keystore before first unlock, and without duplicate reconciliation.
- A 40-minute continuous dwell in a tracked app is credited in full (within one tick interval's tolerance), not truncated to a clamped value.

## 18. Definition of Done

- All items in §7 are complete and individually validated.
- All unit and instrumented tests in §12 pass in CI, including the authority-handoff and long-dwell regression tests specific to this revision.
- The full physical-device QA matrix has been executed and signed off, with latency and battery-cost results recorded against §11's targets.
- Play Console's Accessibility declaration, Usage Access disclosure, and `specialUse` declaration are all submitted and approved.
- Crashlytics events in §14 are confirmed arriving from at least one physical device per OEM in the QA matrix.
- The Room migration is tested against a fixture database seeded with pre-existing dormant field values and confirmed lossless.
- Feature kill-switch is confirmed to cleanly stop both paths with no orphaned service/notification/overlay state.
- Open Questions in §16 are either resolved and folded into this document as decisions, or explicitly accepted as launch-blocking/non-blocking by product ahead of release.
