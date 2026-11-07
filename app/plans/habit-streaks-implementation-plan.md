# Habit Tracking & Streaks (V1 Phase 2) — Implementation Specification (Revision 2)

*Revision 2026-10-17 (docs audit): §16's "whether Play Store distribution is ever planned" is closed — never (ADR #15, direct APK only); the other §16 questions are plan-time and were resolved or deferred during build, not current blockers. **Status 2026-10-22: ✅ BUILT** — habit streaks, milestones and the widget shipped (V1.md features 18–20 and ADRs #87/#104/#128/#166/#173); this file is the spec of record for intent, while the ADRs and V1.md carry shipped behavior.*

*Revision 2026-11-07 (owner ruling, ADR #265): REQ-12's ladder continues past 90 — six months (180) and one year (365) join 7/30/90. Rows are backfilled lazily on the next evaluation (`INSERT OR IGNORE`), with no schema change, so streaks and restores that predate the extension repair themselves. Home and Streaks show one stretch at a time (days 1–90, 91–180, 181–365), each restarting its count at the top left; a relapse returns to the first stretch.*

## 1. Feature Overview

Build the offline-first habit recovery tracking system: a days-free streak counter, a personalized withdrawal-expectation timeline, and milestone celebration (7/30/90 days) with notifications and in-app splash cards. The app runs fully offline (no GMS/FCM, no network dependency), is sideloaded, and must run correctly across all Android device skins (stock/Pixel, Samsung OneUI, Xiaomi/HyperOS, Oppo/ColorOS, Transsion/XOS, etc.), not one specific device.

All state is encrypted at rest. All streak/timeline computation happens on explicit lifecycle events (never inside a background worker the OS could suppress, and never merely because Compose recomposed). Background scheduling is used only to *trigger* re-evaluation and attempt notification delivery — it is never the source of truth for whether a streak advanced.

## 2. Requirements

- **REQ-11 (Days-Free Counter):** Compute elapsed days-free from persisted event timestamps, safe across app kills, device reboots, and timezone/DST changes.
- **REQ-12 (Milestones):** At 7, 30, and 90 days free — for the *current* recovery streak — show a persistent in-app splash card and attempt a system notification. Splash card is the guaranteed delivery path; notification is best-effort. Each streak (including streaks after a relapse) gets its own independent set of milestones.
- **REQ-18 (Withdrawal Timeline):** From onboarding-entered habit duration and severity, compute a 28-day expectation curve, mapped into four 7-day UI buckets. Presented to the user as a general expectation-setting tool, not a clinical measurement.
- **Streaks UI:** Home hero and Streaks tab render live, reactive streak state (`StateFlow`-backed), replacing static placeholders.
- Runs on minSdk 26+. No dependency on Play Services, FCM, or any network call.

## 3. Existing System Context

- Persistence: Room, currently unencrypted, currently in production use with real user data. This feature both adds new tables and migrates the existing database to encrypted storage — this is a live-data migration, not a greenfield schema.
- DI: Hilt.
- UI: Jetpack Compose.
- Deployment: direct APK sideload, no Play Store distribution in V1.
- Nothing outside the habit/streak/onboarding data path is in scope. Do not touch unrelated modules, screens, or DI graphs.

## 4. Architecture

**Database layer:** Room 3 (`androidx.room3`) using the `SQLiteDriver` contract, backed by SQLCipher's native driver integration (`net.zetetic:sqlcipher-android`, pin version `4.18.0`, the version that shipped official Room 3 / `SQLiteDriver` support — verify at implementation time whether a later 4.x patch has superseded it and pin whichever is current and stable then). `SupportFactory` (the pre-Room-3 `SupportSQLiteOpenHelper` bridge) is implemented as a documented fallback module behind the same repository interface, used only if on-device testing (§12) surfaces instability in the native driver path.

**Plaintext-to-encrypted migration:** this is a first-class, explicitly designed step (§8, Step 0) — not an assumed side effect of adding SQLCipher. Existing installs have a plaintext Room database that must be converted in place without data loss.

**Encryption:** 256-bit DEK generated via `SecureRandom`. Wrapped by a Keystore-resident AES-GCM-256 KEK. StrongBox is attempted first on every device; on `StrongBoxUnavailableException`, silently retry without StrongBox. Never hardcode StrongBox on or off.

**Key storage:** the DEK is already AES-GCM-wrapped and authenticated before it touches disk, so it does not need a second encryption layer on top. Store the wrapped-DEK ciphertext + IV + KEK alias + a schema version byte in a plain private-mode `SharedPreferences` file (not `EncryptedSharedPreferences`, which is deprecated as of `androidx.security.crypto` 1.1.0 and adds no real protection over an already-AEAD-wrapped blob).

**Key lifecycle:** raw unwrapped DEK held only in a `char[]`/`byte[]`. Cleared (`Arrays.fill(...,0)`) inside `RoomDatabase.Callback.onOpen()`, i.e. only after Room confirms the database connection is actually open — never immediately after `databaseBuilder(...).build()`.

**Time engine (`RecoveryTimer`):** wall-clock-first model with reboot-aware anomaly detection layered on top (§6, §8 Step 2). `Settings.Global.BOOT_COUNT` (persisted int, OS-managed) is the reboot signal. The engine persists an explicit **current-streak accumulator**, separate from the all-time record — see Decision 13 and §8 Step 2 for the exact field set; this was missing from the prior revision and is now fully specified.

**Streak identity:** every recovery period (from onboarding, or from any relapse reset) gets its own `streak_id`. Milestones, alarms, and accumulated time all key off `streak_id`, not just `habit_id`, so a second streak reaching day 7 is a distinct, independently-celebrated event from the first.

**Notification/state split:** the database is the sole source of truth for "has milestone N been reached for streak X." `AlarmManagerCompat.setAndAllowWhileIdle()` (inexact, no special permission required) is used as the wake trigger, backed by a `BOOT_COMPLETED` receiver that reconciles and reschedules all pending alarms after every reboot (alarms do not survive reboot on Android — this is unconditional platform behavior, not something exact-alarm status changes). On firing, the receiver re-reads the database before deciding whether to post anything, and claims the notification atomically to prevent duplicate posts.

**Computation:** all reads/writes go through Kotlin Coroutines on `Dispatchers.IO`, exposed to Compose as `Flow`/`StateFlow`. No database or crypto operation runs on the main thread. Evaluation is triggered by explicit lifecycle events (see Decision 9), never by Compose recomposition itself, and is serialized through a single repository-owned mutex so concurrent callers cannot race the persisted anchor backwards.

## 5. Architecture Decisions

1. **Room 3 + SQLCipher native `SQLiteDriver` (v4.18.0, pin exact version at implementation time), not Room 2.x.** Verified to exist and to be officially supported; using the modern coroutine-first driver avoids building on a path Google is deprecating.
2. **`SupportFactory`/`SupportSQLiteOpenHelper` bridge kept as a fallback module, not primary.** The native driver integration is new; a documented fallback bounds the risk without blocking on it.
3. **StrongBox: try-then-fallback, never hardcoded.** The target is all Android skins, some of which have StrongBox hardware; hardcoding it off throws away available security on capable devices.
4. **Reboot detection via `Settings.Global.BOOT_COUNT`, not a runtime-generated UUID.** A process-start UUID cannot distinguish "process was killed and restarted" from "device rebooted"; `BOOT_COUNT` is OS-managed and persists across app restarts. Treated as "the OS's boot counter," not assumed to have stronger guarantees (e.g. wraparound, missing-value) than documented — both are handled defensively in §8 Step 2.
5. **Wall-clock time is the default source of truth for streak calculation; monotonic time validates within-session continuity only.** A pure monotonic model can't survive reboots at all; wall-clock matches the user's mental model of "how many actual days has it been."
6. **Backward clock manipulation is actively prevented; forward clock manipulation combined with a reboot is not technically preventable offline, and this is an accepted, documented product risk for V1.** Closing it fully requires a trusted network time source or a server component, both excluded by the offline-first requirement. Mitigation is anomaly logging, not blocking legitimate use.
7. **Client-side signature verification and debugger detection are removed as gating/destructive mechanisms.** Both are trivially bypassable and carry real false-positive risk with no corresponding security benefit for an offline sideload target. Replaced with non-destructive local anomaly logging only.
8. **Milestone state is one row per `(streak_id, milestone_day)`, not per `(habit_id, milestone_day)` and not a single boolean.** A habit can have multiple streaks over its lifetime (relapse resets); each streak's milestones are independent events that must be independently achievable, notifiable, and acknowledgeable.
9. **Evaluation runs only on explicit triggers — app foreground, `ViewModel` init, alarm receiver firing, relapse/onboarding writes, and `BOOT_COMPLETED` — never on raw Compose recomposition.** UI composition frequency is not a reliable or appropriate persistence boundary; this decouples rendering from database writes and removes an entire class of write-amplification and race conditions.
10. **Notification triggers use `AlarmManagerCompat.setAndAllowWhileIdle()` (inexact), not `setAlarmClock()`.** REQ-12 explicitly defines notification delivery as best-effort; exact alarms require the user-revocable `SCHEDULE_EXACT_ALARM` special permission for no corresponding product benefit here. Inexact alarms still fire during Doze and require no special permission.
11. **A dedicated `BOOT_COMPLETED` receiver reconciles and reschedules all pending milestone alarms after every reboot.** Alarms do not survive reboot on Android, regardless of exact/inexact type — this is unconditional and must be handled explicitly, not assumed away by the earlier decision to avoid exact alarms.
12. **The withdrawal-intensity formula is explicitly labeled as a UX heuristic, not a clinical model, in both code comments and user-facing copy.** No clinical citation exists for the coefficients; presenting it as measured fact would mislead a vulnerable user base.
13. **The RecoveryTimer engine persists a `current_streak_elapsed_ms` field distinct from `all_time_max_streak_elapsed_ms`.** The prior revision under-specified this, risking either non-deterministic accumulation or the all-time record being displayed as the live streak after an anomaly. This decision closes that gap explicitly (see §8 Step 2 for the full field set and update rule).
14. **`android:allowBackup="false"` plus an explicit `data_extraction_rules.xml` with a `<device-transfer>` exclude on the database and key-storage `SharedPreferences` file.** `allowBackup="false"` alone blocks cloud/ADB backup but does not block Android's device-to-device transfer flow on API 31+; only an explicit `device-transfer` exclusion closes the Keystore-key-orphaning failure mode completely.
15. **DEK wrapped-blob storage uses plain private `SharedPreferences`, not `EncryptedSharedPreferences`.** The latter is deprecated (`androidx.security.crypto` 1.1.0) and adds no meaningful protection on top of a value that is already AES-GCM-wrapped and authenticated.
16. **The existing plaintext Room database is migrated to SQLCipher via an explicit one-time conversion routine run on first launch post-update (§8, Step 0), not via a Room `Migration` object.** Room migrations operate within one driver/encryption scheme; converting from unencrypted to encrypted storage is a distinct operation that must be designed, tested, and given explicit failure handling.
17. **A "day" is defined as 24 elapsed real hours (epoch-millis arithmetic), not a calendar-date boundary.** Matches the DST/timezone-safety requirement (REQ-11) without ambiguity about what a "day" means while traveling.

## 6. Data Flow

**First launch after this feature ships (existing installs only):**
1. App detects an existing plaintext `habit_db` and no wrapped-DEK record.
2. Runs the one-time plaintext→encrypted conversion (§8, Step 0) inside a guarded, resumable routine.
3. On success, all subsequent launches use the encrypted path exclusively; the plaintext file is deleted only after the encrypted copy is verified readable.
4. On failure, the app retains the plaintext database untouched and surfaces a recoverable error state rather than deleting anything.

**Onboarding (new habit / new streak):**
1. User submits habit duration + severity + `recovery_start_at` (defaults to now, editable within the constraints in §8 Step 3).
2. Single Room transaction (`withTransaction {}`) writes: `habit_progress` row, a new `streak_id`, and an initial `RecoveryTimer` anchor row keyed to that `streak_id` (`t_wall_last_observed`, `r_boot_last_observed`, `boot_count_last_observed`, `current_streak_elapsed_ms = 0`, `all_time_max_streak_elapsed_ms` carried over unchanged from any prior streak).
3. Milestone rows (`streak_id`, `milestone_day` = 7/30/90, all timestamps null) are pre-created for this streak.
4. The alarm reconciliation function (Decision-driven, §8 Step 4) computes and schedules the three inexact wake alarms for this streak from `recovery_start_at`.
5. On any write failure, the transaction rolls back completely; app returns to the onboarding-not-complete state on next launch. No partial state is ever visible.

**On-read evaluation (triggered by: app foreground, ViewModel init, alarm firing, `BOOT_COMPLETED`, or immediately after a relapse/onboarding write — never by Compose recomposition alone):**
1. Acquire the repository's evaluation mutex for this habit (serializes concurrent callers).
2. Read persisted anchor row + `habit_progress` off `Dispatchers.IO`.
3. Read live `Settings.Global.BOOT_COUNT`, `System.currentTimeMillis()`, `SystemClock.elapsedRealtime()`.
4. Run the evaluation algorithm (§8, Step 2) to derive the updated `current_streak_elapsed_ms`.
5. Persist updated anchor + accumulator values in the same transaction.
6. Release the mutex.
7. Emit result via `StateFlow<HabitState>`; Compose recomposes from it (read-only).
8. If any milestone for the active `streak_id` newly crosses its day threshold during this evaluation, stamp its `achieved_at` and surface the splash card.

**Relapse logging:**
1. User taps "log relapse." Action is debounced (disable control on tap, single in-flight write) to prevent duplicate rows from double-taps; a DB-level idempotency check (reject a second relapse write within a short window) is defense in depth.
2. Single transaction: insert relapse event (`relapsed_at` wall-clock timestamp, plus the `boot_count` and `elapsedRealtime` observed at logging time, for later ordering/anomaly analysis — not as a trust upgrade, see Decision 6); roll the prior streak's `current_streak_elapsed_ms` into `all_time_max_streak_elapsed_ms` if it's a new record; start a **new** `streak_id` with `recovery_start_at = now()`; pre-create that new streak's three milestone rows.
3. Cancel all pending alarms for the old `streak_id`; schedule fresh ones for the new `streak_id`.

**Milestone alarm firing:**
1. Inexact alarm wakes a `BroadcastReceiver`.
2. Receiver runs the on-read evaluation (bounded, via `goAsync()` with a hard internal timeout well under the ~10s OS budget; §8 Step 4 for the exact bound) — does not trust that the milestone is still valid just because the alarm fired.
3. If the milestone for this specific `streak_id` is confirmed achieved and `notification_sent_at IS NULL`, atomically claim it (`UPDATE milestone_events SET notification_sent_at=? WHERE streak_id=? AND milestone_day=? AND notification_sent_at IS NULL`) and only post the system notification if the claim succeeded (prevents duplicate posts from overlapping evaluations).
4. If `POST_NOTIFICATIONS` is denied, skip the system notification silently; the splash card (driven by `achieved_at`, independent of `notification_sent_at`) still renders next time the app opens.

**Boot reconciliation:**
1. `BOOT_COMPLETED` receiver fires (requires `RECEIVE_BOOT_COMPLETED`; handle the case where this broadcast is delayed or restricted on some OEMs by also reconciling on next app foreground as a backstop).
2. For every habit with an active (non-relapsed) streak, re-read persisted milestone state and reschedule any alarms for milestones not yet achieved and not yet passed.

**Catch-up addendum (2026-09-28, ADR #104):** reconciliation alone left crossings that landed in the reboot window lost forever — the milestone's alarm died with the previous boot, and step 2 deliberately never reschedules a projected-time-passed milestone, so nothing would ever post it (owner-reported on the day-7 crossing, task #14). The boot receiver now also runs Decision 9's listed `BOOT_COMPLETED` evaluation for every active habit and delivers any achieved-but-unclaimed milestone through the same atomic claim as the alarm path (`MilestoneAlerter.catchUpAll()`); the app-foreground evaluation boundary runs the same sweep for every active habit, so an alarm the platform suppressed or dropped is delivered at next open instead of never. Delivery remains best-effort (REQ-12) — the splash card stays the guaranteed path. Revised 2026-10-16 (finding #22, device-caught): the first foreground implementation delivered only the *displayed* habit (`deliverUnclaimed`), so an unfocused habit's missed crossing sat achieved-and-unclaimed with no retry — its day-7 alarm left the queue unposted and a 16-day uptime meant the boot sweep never ran; boot and foreground now share the one all-habits sweep, whose per-habit evaluation also re-arms each habit's projected alarms.

## 7. Implementation Sequence

1. **Plaintext→encrypted migration routine** — designed and tested in isolation before any other step touches the database, since it operates on real existing user data.
2. **Schema & encryption foundation** — Room 3 entities (including `streak_id` and the accumulator fields), SQLCipher native driver wiring, Keystore key wrap/unwrap, plain-`SharedPreferences` wrapped-key storage, `RoomDatabase.Callback` key-clear.
3. **Migration harness for future schema changes** — `Migration` objects + `MigrationTestHelper` test scaffold, established before any *future* entity change ships (distinct from Step 1's one-time encryption conversion).
4. **RecoveryTimer engine** — anchor + accumulator persistence, `BOOT_COUNT`-based reboot detection, wall-clock/monotonic evaluation algorithm, anomaly logging, mutex-serialized evaluation.
5. **Habit & streak repository** — onboarding transaction, relapse-logging transaction (with streak rollover), withdrawal formula (clamped), `StateFlow` exposure.
6. **Milestone & scheduling engine** — `milestone_events` table keyed by `streak_id`, inexact alarm scheduling/cancellation, `BroadcastReceiver` re-verification + atomic claim logic, `BOOT_COMPLETED` reconciliation receiver, notification channel + permission handling, splash card state.
7. **UI integration** — Compose screens collecting `StateFlow` via `collectAsStateWithLifecycle()`, explicit evaluation triggers wired at ViewModel/lifecycle boundaries (not inside composables), Home hero + Streaks tab, splash card component.
8. **Physical device validation pass** — full device matrix (§12) before release sign-off, including an explicit reboot-and-relaunch test and an existing-install-upgrade test.

## 8. Detailed Implementation Steps

### Step 0: Plaintext-to-Encrypted Migration (existing installs only)
- On app start, check for the existing plaintext `habit_db` file and the absence of a wrapped-DEK record.
- If both conditions hold: generate DEK/KEK as in Step 1, open the plaintext DB read-only, open a new SQLCipher-encrypted DB at a temporary path, copy all rows table-by-table inside a transaction on the destination.
- Verify the new encrypted DB is fully readable (spot-check row counts per table match source) before doing anything destructive.
- Atomically rename the temporary encrypted DB into place as `habit_db`; rename (don't delete) the original plaintext file to a `.bak` suffix.
- Only delete the `.bak` file after the app has successfully opened the new encrypted DB on a subsequent cold start (two-launch confirmation), to survive a crash mid-migration.
- If any step fails, leave the original plaintext DB untouched and functioning; surface a non-destructive "encryption upgrade pending" state and retry on next launch rather than blocking app use.
- If no existing plaintext DB is found (fresh install), skip this step entirely and create the encrypted DB directly via Step 1.

### Step 1: Schema & Encryption Foundation
- Dependencies: `net.zetetic:sqlcipher-android:4.18.0` (verify against current stable at implementation time), `androidx.room3:room3-runtime:<pinned stable version>`.
- `KeyRepository` interface with two implementations: `KeystoreKeyRepository` (production) and `FakeKeyRepository` (test, injected via Hilt in Robolectric/unit test builds).
- `KeystoreKeyRepository.getOrCreateKek()`: attempt `setIsStrongBoxBacked(true)`; on `StrongBoxUnavailableException`, retry with it unset. Log which level was actually obtained via `KeyInfo.getSecurityLevel()`.
- `KeyRepository.getOrCreateDek()`: on first run, generate 256-bit DEK via `SecureRandom`, encrypt with KEK (AES/GCM/NoPadding), store ciphertext + IV + KEK alias + schema-version byte in a dedicated, private-mode plain `SharedPreferences` file. On subsequent runs, decrypt and return.
- Database builder:
```kotlin
val driver = SQLCipherDriver(passphrase = dekBytes) // net.zetetic native SQLiteDriver integration
Room.databaseBuilder(context, AppDatabase::class.java, "habit_db")
    .setDriver(driver)
    .addCallback(object : RoomDatabase.Callback() {
        override fun onOpen(db: SQLiteConnection) {
            super.onOpen(db)
            Arrays.fill(dekBytes, 0) // only clear after confirmed open
        }
    })
    .build()
```
- Verify this sketch against the exact shipped `4.18.0` API surface before merging (exact class/parameter names); do not guess if they differ.
- Fallback module: `SupportFactory`-based `openHelperFactory()` implementation behind the same `DatabaseProvider` interface, feature-flagged off by default.

### Step 2: RecoveryTimer Engine
- Persisted fields per active streak: `t_wall_last_observed`, `r_boot_last_observed`, `boot_count_last_observed`, `current_streak_elapsed_ms`, `relapsed_at` (nullable, on the prior streak record). Persisted per habit (survives across streaks): `all_time_max_streak_elapsed_ms`.
- Evaluation algorithm, executed under the repository's per-habit mutex:
  1. Read `boot_count_current`, `t_wall_current`, `r_boot_current`.
  2. **Same boot session** (`boot_count_current == boot_count_last_observed`): `increment = r_boot_current - r_boot_last_observed` (monotonic, tamper-safe within session). `current_streak_elapsed_ms += increment`. If `t_wall_current < t_wall_last_observed`, log a backward-clock anomaly (informational only in this branch — monotonic time is what's actually used).
  3. **Reboot detected** (`boot_count_current != boot_count_last_observed`): `increment = t_wall_current - t_wall_last_observed`. If negative: backward-manipulation event — do not advance `current_streak_elapsed_ms`; log a critical anomaly; do not update the anchor (retry on next evaluation instead of persisting a bad state). If positive: `current_streak_elapsed_ms += increment` (Decision 6 — accepted risk, not blocked).
  4. Check against the current streak's own `relapsed_at`-equivalent guard: if this streak has already been superseded by a later relapse row (i.e. this evaluation is running against stale state), discard the result and re-fetch rather than write.
  5. Explicit relapse-rollback protection: when logging a *new* relapse, if `t_wall_current` at logging time is earlier than the current streak's `recovery_start_at`, reject the relapse write as invalid input rather than creating a negative-duration streak, and prompt the user to correct the device clock or the entered time.
  6. `all_time_max_streak_elapsed_ms = max(all_time_max_streak_elapsed_ms, current_streak_elapsed_ms)` — updated as a running record only, never read back as a substitute for `current_streak_elapsed_ms` in any anomaly-handling path.
  7. Persist `t_wall_last_observed`, `r_boot_last_observed`, `boot_count_last_observed`, `current_streak_elapsed_ms`, `all_time_max_streak_elapsed_ms` atomically, in the same transaction as the read that produced them (compare-and-update on a row version to catch the mutex being bypassed, as defense in depth).
- Day unit: `86_400_000L` ms. No calendar-date math anywhere in this engine.
- On very first anchor write for a habit, sanity-bound `t_wall_current`: reject if before the app's known build/release date or implausibly far in the future; if invalid, do not anchor yet, retry on next foreground.
- `BOOT_COUNT` is treated as "whatever the OS reports," not assumed monotonic-forever or gap-free; a missing/zero value is treated the same as any other reboot-detected transition, not as a special error.

### Step 3: Habit & Streak Repository
- Withdrawal formula with explicit clamp:
```kotlin
val intensity = (baseDecay * (1 + durationModifier) * (1 + severityModifier)).coerceIn(0.0, 2.0)
```
- Code comment and corresponding in-app copy note (wherever the timeline is shown) stating this is an estimation tool, not a clinical measurement.
- `recovery_start_at` editability constraints: may be backdated but not set later than the current wall-clock time at entry, and not earlier than any previously logged relapse for this habit. Editing it after onboarding is only permitted before the first evaluation has run (i.e. during onboarding review, not as a later "edit streak" feature) — a later "edit streak start" feature, if wanted, is out of scope for V1 and listed in Open Questions.
- All habit-state writes (onboarding, relapse log) wrapped in `RoomDatabase.withTransaction {}`.

### Step 4: Milestone & Scheduling Engine
- `milestone_events` table: `streak_id`, `milestone_day` (7/30/90), `achieved_at` (nullable), `notification_sent_at` (nullable), `acknowledged_at` (nullable). Unique constraint on `(streak_id, milestone_day)` — never a shared boolean, never keyed by `habit_id` alone.
- `reconcileScheduledMilestones(habitId)`: the single function responsible for computing "what alarms should exist right now" from durable state and making the OS match it. Called after onboarding, after relapse, after `BOOT_COMPLETED`, and on app foreground as a backstop. It cancels alarms that shouldn't exist and schedules ones that should — it does not assume any prior scheduling call succeeded.
- `PendingIntent` identity: unique per `(streak_id, milestone_day)` — e.g. request code derived from a stable hash of both — so different streaks' or different milestones' alarms never collide or replace one another.
- Scheduling call:
```kotlin
val pendingIntent = PendingIntent.getBroadcast(context, requestCodeFor(streakId, milestoneDay), intent, PendingIntent.FLAG_IMMUTABLE)
AlarmManagerCompat.setAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
```
- `BroadcastReceiver.onReceive()`: call `goAsync()`, run the on-read evaluation and atomic notification-claim (§6, Milestone alarm firing) inside a coroutine with an internal timeout (e.g. 8 seconds, safely under the OS `goAsync()` budget), call `pendingResult.finish()` in a `finally` block unconditionally.
- `BOOT_COMPLETED` receiver: manifest-registered, requires `RECEIVE_BOOT_COMPLETED`; on receipt, calls `reconcileScheduledMilestones()` for every habit with an active streak. Because some OEMs delay or restrict this broadcast, app-foreground evaluation also calls the same reconciliation function as a backstop, so a missed boot broadcast self-heals on next open.
- Splash card visibility: `achieved_at IS NOT NULL AND acknowledged_at IS NULL`, independent of `notification_sent_at`. Dismissing sets `acknowledged_at = now()`.

### Step 5: UI Integration
- `collectAsStateWithLifecycle()` on the repository's `StateFlow<HabitState>` in Home and Streaks screens — read-only consumption.
- Evaluation is triggered from the `ViewModel`'s `init {}` block and from an explicit `onResume`/foreground lifecycle callback, not from inside any `@Composable` function.
- `remember`/`derivedStateOf` for Compose-local derived values (e.g. formatted strings) only — the underlying streak math is not memoized at the algorithm level, since it's cheap and already de-duplicated by the trigger model above.
- Splash card renders at top of Home when any milestone for the active `streak_id` is unacknowledged.

## 9. Error Handling

- **Plaintext→encrypted migration failure:** original plaintext DB is preserved untouched; app surfaces a non-destructive pending-upgrade state and retries on next launch (§8, Step 0). Never deletes source data before the destination is verified.
- **`SQLiteException` / `KeyStoreException` on DB open (post-migration, steady state):** caught at the repository boundary, routed to a diagnostic recovery screen that distinguishes "wrong/missing key" from "corrupted database" (via `PRAGMA integrity_check`) before offering any reset. A full reset is a user-initiated, explicitly-warned last resort — never automatic.
- **Signature mismatch:** log locally, no destructive action.
- **Debugger detected:** no check performed; this gate is removed entirely.
- **Backward clock manipulation (same-session or post-reboot):** current streak's accumulator is not advanced for that evaluation; anomaly logged; retried on next evaluation rather than persisting a poisoned anchor.
- **Relapse logged with a wall-clock time earlier than the streak's start:** rejected as invalid input at write time (§8 Step 2.5), not silently accepted and then patched around later.
- **Forward clock jump after reboot:** accepted and applied (Decision 6); logged as informational anomaly only.
- **Notification permission denied:** skip system notification, rely on splash card.
- **Onboarding write failure:** transaction rollback returns app to not-onboarded state on next launch.
- **Concurrent relapse-log taps:** UI-level debounce plus DB-level idempotency window, as defense in depth.
- **Alarm fires against stale or already-notified state:** atomic claim (§8 Step 4) prevents duplicate posts; if the claim fails because another evaluation already handled it, the receiver does nothing further.
- **Missed/delayed `BOOT_COMPLETED` broadcast on a restrictive OEM:** app-foreground reconciliation is the backstop; no user-visible failure occurs, only a delayed notification (splash card is unaffected either way).

## 10. Security Requirements

- DEK: 256-bit, `SecureRandom`, never logged, never stored unwrapped.
- KEK: Keystore-resident AES-GCM-256; StrongBox attempted first, TEE fallback accepted, security level logged for diagnostics (not a gate).
- Wrapped DEK + IV + alias + schema version: plain private-mode `SharedPreferences`, since the value itself is already AEAD-protected (Decision 15).
- Raw DEK: `char[]`/`byte[]` only, never `String`. Cleared in `RoomDatabase.Callback.onOpen()`, not before.
- No client-side signature-fingerprint gating and no debugger-detection gating — do not implement either.
- `android:allowBackup="false"` **and** a `data_extraction_rules.xml` with `<device-transfer>` and `<cloud-backup>` exclusions covering the database file and the wrapped-key `SharedPreferences` file, referenced via `android:dataExtractionRules` in the manifest. `allowBackup="false"` alone does not block device-to-device transfer on API 31+ and must not be relied on as the sole control.
- No network calls anywhere in this feature; logging is local-file only.

## 11. Performance Requirements

- All DB and crypto work on `Dispatchers.IO`; enforce with StrictMode in debug builds as an application-boundary safety net, not as a guarantee against every possible library-internal code path.
- DAO queries use explicit column projections, not `SELECT *`, on any hot path.
- No numeric latency target is imposed for V1; validate qualitatively during the device matrix pass (§12) and file a follow-up if a specific low-end device shows visible jank.
- Compose-local derived formatting values are memoized (§8 Step 5); the streak-math itself is not, since evaluation is already trigger-gated rather than render-gated.

## 12. Testing Requirements

**JVM / Robolectric (pure logic):**
- `RecoveryTimer` evaluation algorithm: same-boot-session monotonic accumulation, reboot-detected wall-clock accumulation, backward-clock-during-session anomaly, backward-clock-after-reboot rejection, relapse-rollback-input rejection, first-run implausible-anchor rejection, and explicit verification that `all_time_max_streak_elapsed_ms` is never read back as `current_streak_elapsed_ms` in any code path. Use `ShadowSystemClock` for `elapsedRealtime()` simulation and a fake `BOOT_COUNT` provider for reboot-vs-same-session transitions.
- Withdrawal formula: boundary values for every duration/severity combination; monotonicity checks (HIGH severity never produces lower intensity than LOW at the same day/duration).
- Milestone schema: verify two streaks on the same habit can each independently achieve, notify, and acknowledge their own Day-7 milestone without interfering with each other.
- Notification claim idempotency: simulate two concurrent evaluations racing to claim the same milestone notification; verify exactly one post occurs.
- `FakeKeyRepository` for any test needing a DEK/KEK without real Keystore access.

**Instrumented (on-device, real APIs):**
- Real Keystore key generation/wrap/unwrap round trip, including StrongBox-try/fallback.
- Real SQLCipher native driver: cold install → key generation → app kill → relaunch → decrypt.
- Plaintext→encrypted migration routine against a realistic pre-populated plaintext database, including a simulated crash mid-migration (verify the `.bak` recovery path).
- `Migration` correctness via `MigrationTestHelper` for any future schema version bump.
- Real inexact-alarm scheduling/cancellation/reconciliation flow, including a simulated reboot (verify `BOOT_COMPLETED` reconciliation actually reschedules).
- Real `POST_NOTIFICATIONS` permission grant/deny paths.

**Physical device matrix (required before release sign-off):**
- At minimum: one stock/Pixel-class device, one Samsung OneUI device, one Xiaomi or Transsion device (budget MediaTek class).
- Verify on each: reboot behavior of the timer engine and alarm reconciliation, notification permission flow, cold-start database unlock, and the existing-install upgrade path (plaintext DB present → encrypted migration → app functions normally).

## 13. Deployment Requirements

- Release build: `compileSdk 37`; confirm the exact Kotlin/AGP/Compose BOM/Gradle versions against official release channels at implementation time — do not guess version strings.
- APK signed with v2/v3/v4 schemes, zip-aligned, for direct sideload install.
- ProGuard/R8: explicitly keep SQLCipher native classes and `.so` libraries from being stripped — verify the exact keep rule against the shipped `4.18.0` artifact's actual package names.
- Manifest must declare: `android:allowBackup="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"` pointing at the rules file from §10, `RECEIVE_BOOT_COMPLETED` permission for the boot-reconciliation receiver. Verify none of these are silently overridden by manifest merger from a library dependency.
- No CI/CD or staged-rollout requirement is imposed for V1 sideload distribution.

## 14. Monitoring Requirements

- All anomaly events (backward-clock-in-session, backward-clock-after-reboot, forward-jump-after-reboot, relapse-rollback-input-rejected, signature mismatch, first-run-implausible-anchor, migration failure/retry) logged to a local file: timestamp, event type, relevant anchor values, resolution taken.
- Track notification-posted vs. splash-card-shown counts locally, as a diagnostic signal for whether inexact-alarm delivery is being suppressed on a given device/OEM. No automated alerting exists in V1; this is a manual-review log only.

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| Plaintext→encrypted migration fails or corrupts data on an existing install | Low–Medium | Critical | Two-phase copy-then-verify-then-swap routine (§8 Step 0); original never deleted until destination is confirmed readable | Instrumented migration tests against realistic pre-populated DBs; local failure logging |
| SQLCipher native `SQLiteDriver` integration proves unstable on a real device | Medium | High | Documented `SupportFactory` fallback module behind the same interface | Instrumented test suite run before every release |
| `BOOT_COMPLETED` delayed/restricted on a given OEM | Medium | Low–Medium | App-foreground reconciliation is a backstop; splash card is unaffected regardless | Local notification-vs-splash-card counters |
| Forward clock manipulation after reboot inflates a streak | Medium | Medium | Accepted risk (Decision 6); backward manipulation and relapse-rollback-input are fully blocked | Anomaly log for manual review |
| Low-end MediaTek device shows SQLCipher read latency under battery-saver throttling | Medium | Low–Medium | No blocking mitigation for V1; validate qualitatively in device matrix | Manual QA during device matrix pass |
| Schema migration bug on a future app update | Low | High | `MigrationTestHelper` tests required before any *future* schema change ships (non-negotiable gate) | CI/manual test run pre-release |
| StrongBox present on some target devices but code assumes absence | Low (mitigated by design) | Medium | Try-then-fallback pattern implemented from the start | `KeyInfo.getSecurityLevel()` logged per device |
| Concurrent evaluators race the persisted anchor backwards | Low (mitigated by design) | Medium | Per-habit mutex + compare-and-update on anchor write | Anomaly log for any detected version conflict |

## 16. Open Questions

- Exact current stable versions for Kotlin, AGP, Gradle, and Compose BOM to pin — confirm against official release channels at implementation start.
- Whether Play Store distribution is ever planned; if so, revisit the removed signature-check decision (§5.7) and the manifest/signing setup.
- Acceptable device-matrix pass/fail bar for the low-end MediaTek performance check (§11) — no numeric latency target has been set.
- Whether product wants any user-facing messaging about the accepted forward-clock-manipulation-after-reboot limitation (Decision 6), or whether this stays purely internal/logged.
- Whether a later "edit an in-progress streak's start date" feature is wanted; V1 intentionally does not support editing `recovery_start_at` after onboarding review (§8 Step 3).
- Whether missed milestones (device offline/unopened past a milestone day) should show one combined "you missed some celebrations" card or all individually when the user next opens the app — current spec shows each independently via its own `achieved_at`/`acknowledged_at` state, which naturally supports either; confirm the desired UI treatment before building the splash-card stacking behavior.

## 17. Acceptance Criteria

- App builds and runs on minSdk 26+ without crashes, using the pinned Room 3 + SQLCipher native driver stack.
- An existing plaintext-database install upgrades to encrypted storage without data loss, verified by a before/after row-count and spot-check comparison.
- Streak counter advances correctly across a normal app-kill-and-relaunch cycle within the same boot session.
- Streak counter advances correctly across a genuine device reboot under normal (non-adversarial) use.
- Streak counter does not advance, and does not fall back to the all-time record, when a backward clock change is detected (same-session or post-reboot) — it holds its last valid accumulated value.
- A relapse cannot be logged with a timestamp earlier than the current streak's start; the write is rejected, not silently accepted.
- Two consecutive streaks on the same habit each independently achieve, notify, and allow acknowledgement of their own 7/30/90-day milestones.
- Milestone alarms are correctly rescheduled after a device reboot without requiring the user to open the app first.
- No duplicate milestone notification is posted even when two evaluations race to handle the same alarm.
- Database file on disk is SQLCipher-encrypted and cannot be opened without the derived key.
- App is excluded from both cloud backup and device-to-device transfer (verified via the manifest and `data_extraction_rules.xml`, not `allowBackup` alone).
- No network call of any kind occurs anywhere in this feature.
- No main-thread database or Keystore access occurs anywhere in this feature (StrictMode-clean in debug builds).

## 18. Definition of Done

- All implementation steps in §8 completed and code-reviewed, including the Step 0 migration routine.
- All JVM/Robolectric and instrumented tests in §12 passing, including the reboot-reconciliation and repeated-streak-milestone tests.
- Physical device matrix (§12) executed and passed on all three required device classes, including the existing-install upgrade path.
- `MigrationTestHelper` coverage in place for the shipped schema version (for future changes; the Step 0 conversion is tested separately per §12).
- Manifest confirmed to contain `allowBackup="false"`, the `dataExtractionRules` reference, and `RECEIVE_BOOT_COMPLETED` in the final merged/built APK.
- Release APK built, v2/v3/v4-signed, zip-aligned, and successfully sideload-installed on all three device-matrix devices with no critical or high-severity QA findings outstanding.
- Open Questions (§16) resolved or explicitly deferred with owner sign-off before release.
