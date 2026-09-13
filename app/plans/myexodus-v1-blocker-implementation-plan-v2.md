# MyExodus — V1 App UI Phase 1 — Implementation Specification (v2)

Supersedes `myexodus-v1-blocker-implementation-plan.md`. Distribution: Google Play (primary). Target: all Android OEMs.

> **Implementation decisions (user-confirmed 2026-09-12) — deviations from this document, recorded BEFORE implementation starts:**
> 1. **Distribution: direct APK only — ADR#15 stands.** Every Google Play requirement in this document (FGS declaration, accessibility/permission forms, closed-track policy validation, Play bundle format, §15's Play-rejection risks) is a dead constraint, recorded as non-binding. §8.4's `specialUse` subtype property is still harmless and may stay.
> 2. **This document is executed as the UI-page specification only** (Rules / Recovery / Settings screens, their states, and the UI-layer requirements: optimistic-write handling §6.1/§9, `LazyColumn key` §11, `collectAsStateWithLifecycle` §11, honest status rows §2). The **enforcement re-architecture is NOT implemented** — the shipped, device-verified system stays: Room3 `RestrictionRule` (inline `timeRangeStart/End` + `activeDaysOfWeek` + `timezone`) over `BlockDecisionEngine` instead of `RuleEntity`/`ScheduleEntity`; `ForegroundBlockerService` (`UsageStatsManager.queryEvents` poll, launched from the FGS — NOT from `onAccessibilityEvent`, so the background-activity-start drop cited in §5 does not apply) instead of the accessibility-overlay API; the always-on FGS **is** the detector, so §5's session-scoped lifetime is rejected as a reliability regression on XOS; `RecoveryTimer`'s dual-clock + freeze-on-anomaly (§12-tested) stays instead of §8.2's boot-anchor model. The §12 device matrix already PASSED on X6532 (functional, latency, tapjacking, FGS, Task-Manager Stop).
> 3. **Recovery friction: V1 doc Req 13 governs** — the un-skippable 3-to-5-minute countdown only (built, §12-tested). §2's breathing-hold + typed-challenge stages are dropped as undocumented product behavior.
> 4. The superseded `myexodus-v1-blocker-implementation-plan.md` was never committed to `app/plans/` — this document is the effective plan of record.
> 5. **UI Phase 1.5 — website Interception Escape Hatch pause card (user-directed 2026-09-13).** Not part of this document's three pages; it is V1-doc conformance work on the shipped blocking path (V1 State 2 + the Interception Escape Hatch glossary entry + NFR "≤1 tap to recovery" + NFR-S10). The shipped URL block overlay was message-only, so a user who landed on a blocked site had no in-overlay action and no way to reach the Recovery dashboard — the escape hatch the V1 doc requires did not exist. Now: the overlay renders an interactive card naming the blocked site with two actions — "Open MyExodus Recovery" (starts the same `RecoveryStateStore` session the app block screen starts, then deep-links to the Recovery tab) and "Go to a different site" (loads `about:blank` in the same browser; the blocked tab re-blocks on return). Two further V1-spec gaps closed in the same pass: URL blocks now increment the Req 9 attempt log (State 2 requires `access_attempts_count`), and the overlay sets `filterTouchesWhenObscured` with an opaque background (NFR-S10). **The enforcement mechanism is unchanged** — no `attachAccessibilityOverlayToDisplay`, no new services, no schema change; §5's overlay-API architecture decision stays rejected per deviation 2. App-window blocks kept the message-only cover at this point (no action to offer); amended twice more the same day — see below. Amended 2026-09-13 (same-day device-test sweep, ADR #30): the URL card's device-caught persistence defects and the nav-bar touch loss are fixed and re-verified on X6532; the app-window cover now sizes to the display (a cold launch's sub-window event could freeze it at dialog size — AnkiDroid 684×563, caught on-device). Amended again the same day (user-directed one block language across app and URL blocks, ADR #31 + #32): the app block screen (`BlockerActivity`) became the card's twin — app name as hero, the same single "Open MyExodus Recovery" action, no countdown UI of its own (the Recovery tab owns every countdown and unlock, one `unlockRule` call site) — and the app-window cover then became the same card too (app named, the single recovery action, no different-site action; rule id from the published snapshot, absent only in the decide/show race with a deletion, where the card states the block without an action). The cover is the instant bridge and the whole wall when the FGS is down; device-verified on X6532 the same day (cover card renders and yields to `BlockerActivity`, the cover's own recovery button opens the Recovery tab with the live session, Back swallowed on the block screen, Home always exits). The persistent FGS-down leg was staged and verified the same day via the harness `fgs-down` suite: with the FGS stopped, the cover alone walls a restricted app (no `BlockerActivity` start; 20 s+ persistence), its recovery button opens the Recovery tab with the live session, and the FGS restarts on the MainActivity resume (~65 ms). Also the same day (ADR #30's sweep): Brave was first added to the scanner's package map (address-bar id verified from the installed APK's resources), then the map itself was replaced by runtime toolbar discovery — a package allowlist can only cover browsers someone listed, and real devices carry arbitrary ones (user-raised 2026-09-13). Any browser exposing a conventional toolbar id is now scanned with no code change; see ADR #30 and the enhanced-mode plan's Step 7 revision.

**What changed from v1, and why (stated once, here, not re-litigated below):** v1 had a fatal defect — it called `startActivity()` from `onAccessibilityEvent` to show the block screen, which Android's background-activity-start restrictions silently drop, making the feature non-functional in its main use case. Fixed with a native accessibility-overlay API. Also fixed: an invalid `WorkManager.setExpedited()` usage, an overstated "must survive OEM killing" requirement rewritten as best-effort with measurable recovery objectives, a Play-policy misstatement about `FLAG_SECURE`, missing exact-alarm reboot/grant-broadcast handling, and several data-model and idempotency gaps. Everything below is the corrected, final state.

---

## 1. Feature Overview

Build three screens — Rules, Recovery, Settings — plus the hardened background enforcement engine backing them, for a screen-time/app-blocking product. Rules manages CRUD blocking schedules. Recovery is a high-friction, timed flow a user must complete to temporarily lift a block. Settings surfaces permission state, battery-restriction status, and enforcement integrity health. Enforcement must reconstruct correctly after process death, reboot, permission changes, and OEM background restrictions, on any Android OEM skin.

## 2. Requirements

- Rules screen: create, edit, delete, enable/disable scheduled blocks. One Rule = one target app package. A Rule owns one or more Schedule entries (many-to-one), each with its own day-of-week, start/end time, and IANA timezone ID. Schedules use a half-open interval: start inclusive, end exclusive (`[start, end)`). A Schedule with `start == end` is rejected at input validation — not a valid state.
- Recovery screen: three-stage friction flow (breathing-hold, typed-challenge, 5-minute countdown) gating any block override.
- Settings screen: separate, distinct status rows for accessibility permission, exact-alarm permission, Android battery-optimization exemption status, OEM-specific background-restriction status (these are two different things — do not merge into one boolean), overlay-display capability, and enforcement integrity status (Verified / Unverified / Unavailable / Failed).
- Enforcement must provide best-effort continuity and deterministic recovery across process death, reboot, manual clock/timezone changes, Doze/App Standby, and OEM background restrictions — subject to platform- and OEM-enforced termination and explicit user revocation, which no app can override. This is measured by defined recovery objectives (§9), not an absolute-survival claim.
- User configuration writes (rule create/edit/toggle) must never display as saved unless durably committed.
- Only one block/recovery surface may be active at a time for a given foreground package; duplicate accessibility events must not spawn duplicate surfaces.
- Must satisfy all documented Play policy requirements (FGS declaration, Accessibility disclosure/consent, Permission Declaration Form) and pass pre-release closed-track policy validation. This is an external release gate, not something engineering can unilaterally guarantee — see §17 wording.

## 3. Existing System Context

Assumed baseline (confirm in Open Questions if the actual repo differs): Kotlin, Jetpack Compose, Room, Hilt, a `BlockerAccessibilityService` already exists in some form. `minSdk = 26` (Android 8.0), `targetSdk` = current Play-required level at build time — decided now so implementation isn't blocked; confirm/override in Open Questions if product needs a different floor. This spec adds/hardens:
- `RulesScreen.kt`, `RecoveryScreen.kt`, `SettingsScreen.kt` (new)
- `AppDatabase.kt`, `RuleEntity.kt`, `ScheduleEntity.kt`, `RecoveryStateEntity.kt`, DAOs, `Migration*.kt`
- `TimeUtils.kt`, `ScheduleEvaluator.kt`, `RecoveryStateStore.kt` (new)
- `BlockerAccessibilityService.kt` (harden existing), `OverlayManager.kt` (new), `TimerExpirationReceiver.kt`, `BootRescheduleReceiver.kt` (new)
- `BlockerForegroundService.kt` (new, scoped lifetime — see §5)
- `PermissionManager.kt`, `OemBatteryHelper.kt` (new)
- Do not touch unrelated app modules (onboarding, monetization, etc.) outside what's listed here.

## 4. Architecture

**Single process.** UI (Compose, MVVM), the `BlockerAccessibilityService`, and a session-scoped foreground service all run in the app's default process.

- **UI layer:** Jetpack Compose, MVVM. ViewModels expose `StateFlow` collected via `collectAsStateWithLifecycle()`.
- **Data layer:** Room. Single database instance. `exportSchema = true`. `fallbackToDestructiveMigration()` permitted only in the `debug` build variant (via a build-config flag), never in `release`/`staging`.
- **Enforcement layer:**
  - `BlockerAccessibilityService`: detects foreground app via `AccessibilityEvent.getPackageName()`; on match, invokes `OverlayManager` — never `startActivity()`.
  - `OverlayManager`: on API 34+ (Android 14+), uses `AccessibilityService.attachAccessibilityOverlayToDisplay()` — a native accessibility overlay API that draws over any app directly from the bound service, requiring no `SYSTEM_ALERT_WINDOW` permission. On API 26–33, falls back to a `TYPE_APPLICATION_OVERLAY` window via `WindowManager`, gated on `Settings.canDrawOverlays()`, with its own onboarding/disclosure flow.
  - `BlockerForegroundService`: **not permanently running.** Started only when a Recovery session is active or a block surface is currently displayed; stopped when neither is true. `foregroundServiceType="specialUse"`.
  - `RecoveryStateStore`: durable timer state in Room, immutable-deadline model (§8.2).
  - `AlarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, ...)`: used for two things only — (a) firing exactly at the Recovery timer's deadline, and (b) waking at the next known schedule-boundary transition. Not used for generic periodic polling.

## 5. Architecture Decisions

- **Overlay via `attachAccessibilityOverlayToDisplay`, not `startActivity()`.** Android's background-activity-start restrictions (Android 10+) silently drop activity launches from a service that isn't itself in the foreground. The accessibility-overlay API is purpose-built to let a bound `AccessibilityService` draw over the current window without that restriction and without `SYSTEM_ALERT_WINDOW`. Available API 34+; devices below that use the `SYSTEM_ALERT_WINDOW` overlay path as a documented, separately-onboarded fallback.
- **Foreground service is session-scoped, not always-on.** The primary enforcement trigger is the accessibility event stream, which runs independent of any FGS. The FGS's only job is keeping the process alive with elevated priority during an active block/Recovery session, when losing the surface mid-interaction would be a real user-facing failure. Running it permanently added a battery/notification/Play-declaration cost without a demonstrated reliability benefit tied to actual user-facing work — the definition Android's FGS policy requires.
- **`specialUse` FGS type, scoped as above.** No standard FGS category fits "show a full-screen enforcement surface." Declare `FOREGROUND_SERVICE_SPECIAL_USE`, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` describing the session-scoped behavior precisely, and submit Play Console justification before release.
- **No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` direct-exemption permission**, unchanged from v1 — this app's category doesn't qualify per Android's official acceptable-use table.
- **`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` opens the settings list; it does not itself grant the exemption.** UI copy and code comments must say exactly that — the user still makes the change manually.
- **Android battery-optimization state and OEM background-restriction state are tracked and displayed separately**, not merged into one "battery restricted" boolean — they're different mechanisms with different remedies.
- **Accessibility Service is NOT declared `isAccessibilityTool`**, unchanged from v1 — "monitoring apps" are explicitly excluded. Prominent in-app disclosure + affirmative consent (§8.7) required; disclosure text must describe exactly what the shipped service configuration actually reads, not an idealized superset.
- **Exact alarms handled at four trigger points**, not just app-foreground: `canScheduleExactAlarms()` check on foreground, the `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` grant broadcast, a `BOOT_COMPLETED` receiver that reschedules any pending alarm from persisted state, and reconciliation against persisted state on each check. Alarms are cancelled by the OS on shutdown and must be explicitly rescheduled after boot — this is documented, standard Android alarm behavior, not an edge case.
- **When exact alarms are unavailable, fall back to a plain (non-expedited) `PeriodicWorkRequest`** as a coarse recovery mechanism only, with its minimum-interval and inexact-timing semantics explicitly accepted. `setExpedited()` applies to `OneTimeWorkRequest`, not periodic work, and is not used here.
- **Play Integrity used for attestation only**, App Access Risk treated as optional/EAP-gated — unchanged from v1.
- **Keystore/attestation health surfaced as a soft signal** (Verified/Unverified/Unavailable/Failed) — unchanged from v1.
- **Timer state stored as an immutable deadline** — unchanged from v1 in mechanism, but the guarantee is now stated precisely (§8.2): same-boot-session protection is solid; post-reboot protection is explicitly best-effort and documented as such, not implied to be equivalent.
- **Distribution: Google Play, primary** — unchanged from v1.
- **`FLAG_SECURE` is used to protect sensitive Recovery/Settings screens from screenshots and non-secure-display capture** — it is a protection developers apply, not itself a general Play *requirement*; the actual Play requirement is that apps respect *other* apps' `FLAG_SECURE` declarations. Both facts are now stated correctly and separately (§10).
- **Enforcement state is modeled as an explicit state machine** (§8.9): `ENFORCING`, `DEGRADED`, `SERVICE_DISABLED`, `SERVICE_DISCONNECTED`, `POLICY_UNAVAILABLE`, `RECOVERY_ACTIVE`, `UNKNOWN`. "Last known decision wins" only applies within `DEGRADED`/`UNKNOWN`, bounded by policy version and a `nextKnownTransition` timestamp — it does not mean a stale `BLOCK` decision persists indefinitely past a schedule's own expiry.

## 6. Data Flow

1. User edits a Rule/Schedule in Compose UI → ViewModel → Repository → Room write (transaction) → UI reflects state only after the write's `Result` confirms success; on failure, UI reverts and shows an explicit error.
2. Room emits updated Flow → ViewModel → UI recomposes (`LazyColumn`, `key = { it.id }`) — and separately → `BlockerAccessibilityService`'s Flow collector updates its in-memory cache. Cache staleness bound: cache reflects a committed write within one Flow-emission cycle (test this explicitly, §9).
3. On `AccessibilityEvent` (`TYPE_WINDOW_STATE_CHANGED`): check `event.packageName` against the in-memory blocked-package set. Match → check for an already-active surface for that package (idempotency guard, §8.3) → if none, invoke `OverlayManager`.
4. `OverlayManager` draws the block/Recovery surface via the native accessibility overlay (API 34+) or the `SYSTEM_ALERT_WINDOW` fallback (API 26–33). Before drawing, re-verifies the currently foreground package still matches (guards against F-03: user switched apps between the event firing and the surface rendering).
5. `AlarmManager` wakeup at a schedule boundary or Recovery deadline → re-run schedule evaluation / clear timer state → reconcile in-memory cache.
6. Recovery timer: immutable-deadline write once at start (§8.2); every read recomputes from the stored deadline, never from a decremented counter.

## 7. Implementation Sequence

1. **Database & entities** — `RuleEntity` (package, enabled), `ScheduleEntity` (ruleId FK, dayOfWeek, start, end, ianaZoneId), `RecoveryStateEntity`, DAOs, migrations, `exportSchema = true`.
2. **Time utilities** — `TimeUtils.kt`, `ScheduleEvaluator.kt` (half-open interval, midnight-crossover-correct).
3. **RecoveryStateStore** — immutable-deadline timer persistence.
4. **Repositories & ViewModels** — `RuleRepository`, `RulesViewModel`, `RecoveryViewModel`, `SettingsViewModel`.
5. **Compose screens** — RulesScreen, RecoveryScreen, SettingsScreen, "Desert Dawn" `MaterialTheme` `ColorScheme` tokens (Compose-native, not XML resources).
6. **OverlayManager** — accessibility-overlay path (API 34+) and `SYSTEM_ALERT_WINDOW` fallback (API 26–33), with the idempotency guard and foreground-package re-verification.
7. **BlockerAccessibilityService hardening** — explicit `CoroutineScope(SupervisorJob() + Dispatchers.IO)` created in `onServiceConnected()`, cancelled in `onDestroy()`; package-only event filtering; cache wired to Room Flow.
8. **BlockerForegroundService** — session-scoped start/stop tied to active block/Recovery surface lifecycle.
9. **PermissionManager** — accessibility, exact-alarm (all four trigger points), overlay-capability, Android battery-exemption, and OEM-restriction status tracking, each surfaced distinctly in Settings.
10. **Accessibility & overlay disclosure flows** — prominent in-app disclosure before each permission request, worded to match actual configured service behavior.
11. **Play Integrity attestation (optional, feature-flagged)** — unchanged from v1.
12. **Exact-alarm reboot rescheduling** — `BootRescheduleReceiver` reads persisted deadline/schedule-boundary state and re-arms alarms after `BOOT_COMPLETED`.

## 8. Detailed Implementation Steps

### 8.1 Schedule evaluation (half-open interval, midnight-crossover-correct)
```kotlin
fun isActive(current: LocalTime, start: LocalTime, end: LocalTime): Boolean =
    if (start < end) current >= start && current < end
    else current >= start || current < end   // overnight range, e.g. 22:00–07:00
```
`start == end` is rejected at the UI/repository validation layer before it ever reaches this function — it is not a valid Schedule state. Convert current UTC instant into the Schedule's IANA zone before comparing.

### 8.2 Timer persistence (immutable deadline, precisely bounded guarantee)
On timer start, write once:
- `timerDeadlineElapsedRealtime = SystemClock.elapsedRealtime() + durationMillis`
- `timerBootAnchorWall = System.currentTimeMillis() - SystemClock.elapsedRealtime()`
- `timerDeadlineWallFallback = System.currentTimeMillis() + durationMillis`

On every read:
```kotlin
val currentBootAnchor = System.currentTimeMillis() - SystemClock.elapsedRealtime()
val rebootDetected = abs(currentBootAnchor - storedBootAnchorWall) > 5_000
val remainingMillis = if (!rebootDetected) {
    timerDeadlineElapsedRealtime - SystemClock.elapsedRealtime()   // solid: monotonic, same boot session
} else {
    timerDeadlineWallFallback - System.currentTimeMillis()          // best-effort: post-reboot wall-clock fallback
}
```
**Documented guarantee, precisely stated (do not weaken or overstate this elsewhere in the codebase or UI copy):** within the same boot session, the timer cannot be shortened by manual clock changes. After a reboot, expiry falls back to wall-clock arithmetic, which a technical user can still manipulate by changing the system clock post-reboot — this is an accepted V1 limitation, not solved by the 5-second boot-anchor check (that check only detects that a reboot occurred, it does not make the wall-clock fallback tamper-proof). A trusted external time source would be required to close this gap and is out of scope for V1.

The Recovery timer additionally arms an exact alarm at the deadline (§8.5) as the authoritative expiration signal — the Room-persisted deadline is the source of truth for *display* and *recovery after process death*, the alarm is what actually fires the state transition even if the app is fully backgrounded.

### 8.3 Accessibility service event handling (with idempotency)
```kotlin
private val activeSurfacePackage = AtomicReference<String?>(null)

override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
    val pkg = event.packageName?.toString() ?: return
    if (pkg !in blockedPackagesCache) return
    if (activeSurfacePackage.get() == pkg) return   // idempotency guard: don't relaunch if already showing
    activeSurfacePackage.set(pkg)
    overlayManager.showBlockScreen(pkg) { activeSurfacePackage.compareAndSet(pkg, null) } // cleared on dismiss
}
```
Do not call `event.source` or traverse `AccessibilityNodeInfo` for V1's package-level blocking.

### 8.4 Foreground service — session-scoped
```xml
<service
    android:name=".BlockerForegroundService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Keeps the app process active while a screen-time block or recovery countdown is actively displayed to the user" />
</service>
```
Started from `OverlayManager.showBlockScreen()`; stopped when the surface is dismissed and no Recovery timer is active. This is genuinely user-perceptible ongoing work (the block/Recovery surface itself), satisfying Android's FGS justification criteria directly — not "runs permanently to raise process priority."

### 8.5 Exact alarm scheduling and recovery
```kotlin
// At Recovery timer start:
val intent = Intent(context, TimerExpirationReceiver::class.java)
val pi = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsed, pi)
```
Handle all four trigger points:
1. `canScheduleExactAlarms()` check on app foreground → if false, show Settings banner + `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` launch.
2. Register for `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` → on receipt, re-arm any pending alarm.
3. `BootRescheduleReceiver` on `BOOT_COMPLETED` → read persisted deadline/schedule-boundary state from Room → re-arm alarms with the remaining duration.
4. If exact-alarm permission is denied/revoked and no fallback has been armed: schedule a plain `PeriodicWorkRequest` (minimum interval, inexact) as a coarse catch-up mechanism only. Never call `setExpedited()` on it — that API is for `OneTimeWorkRequest`. The accessibility-event path remains the primary enforcement mechanism regardless of alarm/WorkManager state.

### 8.6 Battery and OEM restriction guidance
```kotlin
val powerManager = context.getSystemService(PowerManager::class.java)
if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
    // Opens the settings list; does NOT itself grant the exemption — user must act.
    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}
```
Settings screen shows this as one status row ("Android battery optimization: exempt / not exempt"). A second, separate status row ("OEM background restrictions") is informational-only for V1 — driven by the `Build.MANUFACTURER`-keyed deep-link table, not by any API that can programmatically detect OEM-specific restriction state (none exists generically). Never declare or request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

### 8.7 Accessibility & overlay prominent disclosure
Before requesting the accessibility toggle: full-screen disclosure, worded to match exactly the service's configured event types and data use (foreground package name only, nothing else), requiring an explicit affirmative tap before proceeding to `ACTION_ACCESSIBILITY_SETTINGS`. Before requesting `SYSTEM_ALERT_WINDOW` (API 26–33 devices only): a separate, equivalent disclosure explaining why the overlay permission is needed, before `ACTION_MANAGE_OVERLAY_PERMISSION`.

### 8.8 Play Integrity (feature-flagged module)
Unchanged from v1: attestation-only, `CLOSE_UNKNOWN_ACCESS_RISK`/`CLOSE_ALL_ACCESS_RISK` if App Access Risk is ever enabled, 24-hour cached verdict on failure/timeout, never blocks core usage.

### 8.9 Enforcement state machine
```
ENFORCING            — accessibility connected, policy fresh, no active overlay
RECOVERY_ACTIVE      — Recovery surface displayed, timer running
DEGRADED             — policy read failed or stale beyond nextKnownTransition; last known decision applies with a visible Settings warning
SERVICE_DISABLED     — user (or OS) disabled the accessibility service; enforcement inactive; high-priority notification shown
SERVICE_DISCONNECTED — service unbound but not user-disabled (e.g. mid-restart); treated as DEGRADED with a shorter grace window
POLICY_UNAVAILABLE   — Room read failure; fails closed (last cached in-memory rule set continues to apply) until resolved
UNKNOWN              — startup, before first successful policy read; treated as DEGRADED, fails closed
```
The app never displays "protected"/"enforcing" UI copy while in any state other than `ENFORCING` or `RECOVERY_ACTIVE`.

## 9. Error Handling

- **DB write failure:** repository layer catches it; UI shows explicit "Not saved — [reason]"; in-memory cache used by the accessibility service is not updated until the write succeeds; enforcement fails closed (a failed disable-rule write leaves the rule enforced).
- **Accessibility service disabled at runtime:** transition to `SERVICE_DISABLED`; post a high-priority notification directing the user to re-enable it — the app cannot self-heal this, Android does not allow apps to re-enable their own revoked accessibility service.
- **Overlay draw fails (`BadTokenException` or `attachAccessibilityOverlayToDisplay` returns `OVERLAY_RESULT_INVALID`/`OVERLAY_RESULT_INTERNAL_ERROR`):** clear the idempotency guard for that package, log the failure, retry once on the next matching event; if it fails twice in a row for the same package, surface a Settings warning rather than looping silently.
- **Exact alarm permission revoked after being granted:** detected via `canScheduleExactAlarms()` on next foreground and via the grant-state broadcast; falls back to `PeriodicWorkRequest`; event-driven enforcement is unaffected.
- **Play Integrity call fails/times out:** integrity status = "Unavailable"; core blocking continues unaffected.
- **Migration failure:** never destructive outside `debug`. Caught in `MigrationTestHelper` tests pre-release, not handled at runtime in release builds.
- **Target app changes between event and surface render (F-03):** `OverlayManager` re-checks the current foreground package immediately before drawing; if it no longer matches the triggering package, the draw is skipped.

## 10. Security Requirements

- `FLAG_SECURE` set on Recovery and Settings screens — a protection this app applies to its own sensitive screens; separately, this app also respects other apps' `FLAG_SECURE` declarations (a distinct Play requirement) and never attempts to bypass them.
- Accessibility API usage limited to deterministic, rule-based logic only. No autonomous decision-making.
- Prominent in-app disclosure + affirmative consent before both the accessibility toggle and (API 26–33 only) the overlay permission request.
- Keystore/attestation results surfaced as Verified/Unverified/Unavailable/Failed — never "root detected" without corroborating evidence.
- Idempotency guard (§8.3) prevents duplicate overlay surfaces, which is also a stability/security property (repeated `WindowManager.addView()` calls without cleanup are a resource-leak and potential-crash vector, not just a UX annoyance).

## 11. Performance Requirements

- `onAccessibilityEvent` handler: package-string comparison and idempotency check only on the hot path; target under 2ms typical execution.
- `LazyColumn` for Rules list: explicit `key = { it.id }`.
- `collectAsStateWithLifecycle()` everywhere Room Flows feed Compose.
- No static allocation-size ceilings; measure via Compose recomposition counts and frame-time profiling on the lowest-RAM device in the OEM test matrix (§12).
- `BlockerForegroundService`'s session-scoped lifetime (§5) is itself a performance requirement: it must not run, and must not hold its notification, outside an active block/Recovery surface.

## 12. Testing Requirements

- **Unit:** `ScheduleEvaluator` — same-day, overnight, and boundary-exact cases against the `[start, end)` convention; adjacent schedules (`10:00–11:00`, `11:00–12:00`) must not both match at exactly 11:00. `TimeUtils` — simulated reboot, simulated NTP correction shortly after boot (confirm no-op given the immutable-deadline model), simulated post-reboot wall-clock manipulation (confirm the documented best-effort behavior, not a false "immune" result).
- **Integration:** `MigrationTestHelper` for every schema version transition against a pre-populated fixture.
- **Accessibility/overlay lifecycle (new, from audit):**
  - Overlay renders correctly across: normal single-window navigation, split-screen, picture-in-picture, launcher transitions, system dialogs, notification shade, and rapid app-switching (verifies the idempotency guard and foreground-re-verification, §8.3/§6.4).
  - Accessibility service disconnects while a block/Recovery surface is displayed — verify defined behavior (Recovery timer continues via the independently-armed alarm; surface state is defined, not left ambiguous).
  - Rapid repeated accessibility events for the same package produce exactly one surface, not duplicates.
  - Rule edited while the accessibility service holds a stale cache — verify cache updates within one Flow-emission cycle.
- **Device (OEM matrix — three physical devices minimum):**
  1. An aggressive-background-killer OEM device (Infinix/Tecno XOS or Xiaomi MIUI).
  2. A moderately-restrictive OEM device (Oppo/ColorOS or Samsung One UI).
  3. A close-to-stock device (Pixel or Android One).
- **Per-device test script**, with failure classes labeled distinctly (process death via `force-stop` ≠ user-initiated stop via the Android 13+ Task Manager ≠ OEM background-clear ≠ random memory-pressure kill — these are different failure modes, do not conflate them in test reports):
  - `adb shell am force-stop <pkg>` during an active Recovery timer → relaunch → verify timer resumes from correct remaining duration via the alarm-driven deadline, not a reset.
  - Full device reboot during an active Recovery timer → verify `BootRescheduleReceiver` re-arms the alarm and the timer resolves at the correct (best-effort, per §8.2) time.
  - `adb shell cmd appops set <pkg> RUN_IN_BACKGROUND ignore`.
  - Each OEM's own task-manager/background-clear action, observed directly on-device (not simulated via adb).
- **Manual Play Console dry-run:** submit FGS declaration and Accessibility Permission Declaration Form to a closed testing track before public release.

## 13. Deployment Requirements

- Standard Gradle build, Play App Bundle format. `minSdk = 26`, `targetSdk` = current Play-required level.
- `/schemas` committed to VCS; CI fails the build if a Room schema change ships without a migration file.
- `fallbackToDestructiveMigration()` gated behind a `debug`-only build config flag; a CI check fails any `release`/`staging` build where it's reachable.
- Play Console pre-submission checklist: FGS declaration + justification (reflecting the session-scoped behavior, §8.4), Accessibility API Permission Declaration Form, prominent-disclosure flow evidence, Data Safety section completed.
- Ensure no dead code paths from the earlier UsageStatsManager/APK-distribution track (see Open Questions) are shipped alongside this AccessibilityService-based track.

## 14. Monitoring Requirements

- Log `BlockerAccessibilityService` lifecycle events (`onServiceConnected`, `onInterrupt`, `onDestroy`) tagged with manufacturer/model, to build an empirical per-OEM kill-frequency picture.
- Log enforcement state-machine transitions (§8.9), not just service lifecycle — this is what actually tells you how often users experience degraded protection.
- Log exact-alarm permission grant/revoke transitions and which of the four trigger points (§8.5) detected each change.
- Log overlay draw failures and idempotency-guard rejections (should be non-zero-but-rare; a spike indicates a specific-OEM overlay bug).
- Log Play Integrity request outcomes (success/timeout/unavailable) as a coarse status bucket only, never verdict payload contents.
- Track rule-write failure rate as a first-class metric.

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| OEM kills accessibility service or session-scoped FGS despite whitelisting | High | High | Event-driven re-establishment on service reconnect; `SERVICE_DISABLED`/`SERVICE_DISCONNECTED` states drive explicit user notification rather than silent failure; monitoring (§14) tracks real per-OEM frequency |
| Play rejects `specialUse` FGS justification | Medium | High | Session-scoped justification (genuine user-perceptible work) is a materially stronger case than an always-on service; submit to closed testing first |
| Play rejects app for accessibility policy non-compliance | Low–Medium | High | Disclosure flow built to the documented letter of policy; Permission Declaration Form submitted pre-launch |
| Overlay permission (API 26–33 fallback) denied | Medium | High | Clear onboarding; on those API levels only, denial degrades that one code path — the API 34+ native overlay path is unaffected |
| Post-reboot timer bypass by a technical user | Low (requires deliberate effort) | Medium | Accepted, explicitly documented trade-off; not solvable without a trusted external time source, out of scope for V1 |
| App Access Risk EAP never granted | Medium | Low | Feature-flagged, zero product dependency |
| Google ultimately declines the specific product/disclosure presentation | Low–Medium | High | This is an external review outcome, not an engineering-controllable risk; budget time for a closed-track review cycle before any public launch date commitment |

## 16. Open Questions

- **App Access Risk EAP enrollment** — apply if not already done; ship with the feature flag off until confirmed.
- **Priority OEM list for the deep-link table** — which manufacturers matter most for the actual target user base? Affects §8.6 ordering only, not blocking for ship.
- **"Soft Enforcement" exact behavior** — when integrity status is Unavailable/Failed, does the block still apply but skip the Recovery friction challenge? Needs a product decision before Recovery-screen branching is finalized.
- **Reboot timer policy — explicit product choice needed:** does elapsed time during a reboot count toward the cooldown (current design: yes, via wall-clock fallback), or should a reboot instead pause/fail-closed until the app can re-establish a trustworthy reference? The current implementation silently picks the first option; confirm that's actually the intended product behavior before it ships, since it's a user-facing policy decision, not just a technical default.
- **Reconcile with the earlier UsageStatsManager/no-AccessibilityService/APK-distribution track** on record from a prior planning session — this spec is AccessibilityService-based and Play-primary, which is architecturally incompatible with that earlier direction. Confirm which is the actual current product direction before implementation starts.
- **Actual current codebase conventions** — confirm DI framework (Hilt assumed), package structure, and existing `BlockerAccessibilityService` implementation before Step 7 (§7) starts.
- **Backup/restore behavior** for Room data, active Recovery timer, and permission state across device transfers — not yet specified; needs a product decision (e.g., does a restored device start with enforcement in `UNKNOWN` until the user re-confirms permissions?).

## 17. Acceptance Criteria

- Overnight schedule (22:00–07:00) correctly blocks at 23:00 and at 02:00; does not block at 12:00; adjacent same-day schedules do not double-match at their shared boundary.
- Block overlay renders correctly across the accessibility/overlay lifecycle test matrix (§12), including split-screen and rapid app-switching, without duplicate surfaces.
- Recovery timer resumes with correct remaining duration after `adb shell am force-stop` during an active countdown, driven by the independently-armed exact alarm.
- Recovery timer resolves according to the documented post-reboot fallback policy (§8.2) after a full device reboot mid-countdown — not claimed as "immune to manipulation," claimed as "resolves per the documented policy."
- Manually advancing the system clock forward during an active countdown, within the same boot session, does not shorten it.
- Zero destructive migrations reachable in any non-debug build variant.
- `SERVICE_DISABLED` notification fires within one periodic-check interval of the accessibility service being externally disabled.
- No crash or ANR when Play Integrity request fails or times out, or when an overlay draw fails.
- Satisfies all documented Play policy requirements; closed-track policy validation completed before public release (external gate, tracked separately from code-complete status).

## 18. Definition of Done

- All code merged to main; all unit and integration tests passing in CI, including the new accessibility/overlay lifecycle suite (§12).
- Verified on all three OEM-matrix physical devices, with force-stop, reboot, and each OEM's own background-clear tested and labeled as distinct failure classes.
- No `fallbackToDestructiveMigration()` reachable outside `debug`.
- Play Console FGS and Accessibility declarations submitted; closed-track policy review completed with no unresolved blocking feedback.
- Monitoring (§14) live and reporting in the closed testing build, including state-machine transition logging.
- Open Questions (§16) resolved or explicitly deferred with product sign-off — in particular the reboot-timer policy choice and the conflicting prior architecture track — before wide release.
