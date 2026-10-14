# Phase 6 — Revised Implementation Specification (Full, Standalone)

*This supersedes the Phase 4 specification in its entirety. It is fact-checked against current Android/AndroidX documentation (see the accompanying Phase 5 reconciliation document for what changed and why) and is ready to hand to an implementing engineer or AI coding agent without needing to cross-reference the earlier draft.*

> **Shipped-state amendment (2026-09-27 — this spec is the design intent; ADR #40 and ADR #87 are the shipped record).** The deltas a reader must know before treating any line below as current:
> - **No `:feature:widget` module** — code lives in `:app` under the `widget` package (user ruling, ADR #40).
> - **Not two variants** — §1/FR1–FR2's 2×1 and 4×2 grew to four compositions (COMPACT, NARROW, EXPANDED, TALL) routed by the size a launcher *reports*, after the 2026-09-27 sweep measured that launchers match a declared preset only when it *contains* the cell (ADR #87). EXPANDED remains the 4×2 shape; no tested launcher selects it.
> - **Worker backstop** — Decision 14's `onEnabled`/`onDisabled` scheduling is not used; the app's existing 15-minute `HealthCheckWorker` drives the writer (ADR #40). **2026-10-14 (ADR #145):** `updatePeriodMillis` moves 0 → 1800000 (the platform minimum) — the platform's own periodic `ACTION_APPWIDGET_UPDATE` broadcast is the only delivery path that can cold-start/thaw the app process when every app-side writer is dead, which is exactly the staleness class the widget-staleness investigation surfaced. The app-side writers remain the primary driver; the platform update is a second, independent backstop. A force-stopped app receives no broadcasts at all — no widget mechanism can reach it (hard platform limit).
> - **§16 Q5 is closed** (2026-09-27): the *streak number* shows its age ("Checked 2h ago") when the record can no longer be proven current; the *status word* keeps the original silent-UNVERIFIED ruling. Q2 was closed by ADR #40 (authority, not `blocksNow`); Q3 stands as decided.
> - **Freshness proof** — the record carries `StreakSample` (accumulator + verified clocks); a process start with a young sample performs no durable read (ADR #87).

## 1. Feature Overview
A Jetpack Glance home-screen widget for the Desert Dawn app, in two responsive variants:
- **2×1 Compact**: streak count only.
- **4×2 Expanded**: streak count, protection status, "Manage urge" deep link.

The widget is a passive, read-only projection of app state. It never evaluates protection logic itself.

Its governing rule, stated precisely: **the widget must never render `PROTECTED` from a snapshot it cannot confirm is fresh (under 30 minutes old), correctly clocked, and valid since the last reboot.** This bounds what the app computes and submits to the launcher. It does **not** guarantee what pixels a launcher continues to display if update delivery itself stalls — Android's widget-host caching means a previously submitted view can persist past the point a fresh one would have superseded it. That residual gap is closed operationally (event-driven updates, a periodic backstop, and forced refresh on any launcher-initiated interaction), not promised away in the product copy.

## 2. Requirements

**Functional**
- FR1: 2×1 variant shows streak count.
- FR2: 4×2 variant shows streak count, protection status, and a "Manage urge" action.
- FR3: Protection status is one of `PROTECTED`, `UNPROTECTED`, `UNVERIFIED`.
- FR3a: Enforcement status is tracked internally as a tri-state (`ACTIVE` / `INACTIVE` / `UNKNOWN`), not a boolean. `policy=ACTIVE` with `enforcement=UNKNOWN` must render `UNVERIFIED` — never `UNPROTECTED` (that would falsely claim enforcement is confirmed off) and never `PROTECTED`.
- FR4: Tapping "Manage urge" opens the urge-management screen with a correct back stack.
- FR5: Widget copy defaults to **discreet mode** — no wording that discloses recovery/addiction context.
- FR6: Discreet/Detailed mode is sourced from an explicit, named location in the data model (§5, Decision 10) and a change to it triggers `updateAll()`.

**Non-functional**
- NFR1: `provideGlance`'s pre-render work targets p99 < 500ms as a project-level SLO on representative devices, not a platform-enforced contract. The only hard rule is: no blocking I/O and no expensive domain computation on the main thread.
- NFR2: Combined `RemoteViews` payload across both responsive sizes targets under 500KB as a project-defined conservative budget, measured against actual Glance 1.1.1 output — not cited as an Android platform limit.
- NFR3: Widget process model: main application process only (no process isolation).
- NFR4: No secrets, tokens, or personal logs ever enter widget-visible state.
- NFR5: The widget must re-request evaluation whenever a session becomes newly visible or interactive (tap, resize, `onAppWidgetOptionsChanged`), since an active composition cannot be assumed to keep itself current beyond its own short session lifetime (see §5, Decision 4′).

## 3. Existing System Context
- **Core app** owns the `blocksNow` protection engine and the Room database.
- **New**: a `:feature:widget` Gradle module, plus a `ProtectionSnapshotWriter` in the core app's domain layer.
- Open question carried forward: whether `blocksNow` can expose a genuine tri-state enforcement signal, or whether "unknown" needs to be synthesized at the integration boundary (e.g., no enforcement heartbeat received yet). See §16, Q2.

## 4. Architecture
```text
┌─────────────────────────── Application Process ───────────────────────────┐
│  blocksNow engine ──emits──> ProtectionSnapshotWriter                     │
│         │                          ▼                                      │
│         │                 Preferences DataStore                           │
│         │                 ("widget_protection_snapshot")                  │
│         │                          ▼                                      │
│         │                 immediately calls DesertDawnWidget.updateAll()  │
│  WorkManager (15-min periodic, inexact) / BOOT_COMPLETED / tap-to-refresh │
│         ▼                                                                 │
│  GlanceAppWidgetReceiver → DesertDawnWidget.provideGlance()               │
│         collects DataStore flow (not one-shot read) for session lifetime  │
│         (session: up to ~10 min pre-render, ~45s post-render, extended    │
│          by interaction — see Decision 4′)                                │
│         calls updateAppWidgetState() + provideContent() per emission      │
│                          ▼                                                 │
│  Glance Framework converts composition → RemoteViews                      │
└──────────────────────────┼─────────────────────────────────────────────┘
                            │  Binder IPC — launcher may cache the last
                            │  successfully submitted RemoteViews if a
                            ▼  later update is delayed or fails
                 ┌────────────────────────┐
                 │   Launcher Process      │
                 └────────────────────────┘
```

## 5. Architecture Decisions
1. **Materialized snapshot, not live evaluation, inside `provideGlance`.**
2. **Preferences DataStore, not Room, as the widget-facing store.**
3. **Global (not per-glanceId) state.**
4′. **Use `PreferencesGlanceStateDefinition` for persisted display state, and have `provideGlance` collect the DataStore flow (not a one-shot `.first()`) for the life of the active session.** This is not a compile-time requirement — `provideContent {}` called with a captured value would also compile — it's a correctness requirement: it's the only way a composition still alive when a second snapshot write lands within its own short session window renders the new value instead of the one it captured at start. It does **not** extend how long a session stays alive; it only prevents that session from serving stale data while it *is* alive. The event-driven `updateAll()` call on every write (Decision 8) is what actually reaches the widget across sessions — the two work together, neither is sufficient alone.
5′. **Two-field protection model, with enforcement as a tri-state, not a boolean.**
   ```text
   policy=ACTIVE, enforcement=ACTIVE   → PROTECTED
   policy=ACTIVE, enforcement=UNKNOWN  → UNVERIFIED
   policy=ACTIVE, enforcement=INACTIVE → UNPROTECTED
   policy=INACTIVE                      → UNPROTECTED (regardless of enforcement — confirm with product, §16 Q3)
   ```
6′. **Reboot detected via `Settings.Global.BOOT_COUNT`, gated on API ≥ 24 (confirmed: added at API level 24).** For API 23, reboot detection explicitly falls back to `elapsedRealtime()` regression only — a documented, weaker signal, accepted as a product trade-off rather than silently defaulted.
7′. **The 30-minute freshness ceiling is a render-time policy the app enforces on itself, not a guarantee about what the launcher displays.** Operational mitigations for the residual gap: (a) event-driven `updateAll()` on every snapshot write, (b) a 15-minute WorkManager periodic backstop (acknowledged inexact under Doze/battery optimization — this is documented WorkManager behavior, not a defect in this design), (c) forcing a fresh `provideGlance` pass on `onAppWidgetOptionsChanged` and on tap, since those are moments the launcher is already actively requesting content.
8. **Event-driven `updateAll()` on every snapshot write, plus the periodic backstop.**
9. **No runtime feature flag** — accepted because the feature is isolated and binary rollback via Play Console staged rollout is the agreed operational mitigation (not because it provides instant kill-switch behavior, which it doesn't).
10. **Discreet mode is the default widget copy, and its setting lives in the same `widget_protection_snapshot` DataStore as the rest of the projection**, written by the same `ProtectionSnapshotWriter` whenever the app-level setting changes, using the same `updateAll()` trigger path. This keeps "what causes a widget refresh" to one auditable list of writers instead of two.
11. **Main process only, no widget-specific process isolation.**
12′. **Dependency: `androidx.glance:glance-appwidget:1.1.1` — confirmed the current stable release** as of the latest verifiable AndroidX release data. (`1.2.0` is release-candidate only as of this writing; re-check `developer.android.com/jetpack/androidx/releases/glance` at implementation start before upgrading, and re-run the full test matrix if you do — don't upgrade opportunistically mid-implementation.)
13. **Tap actions use Glance's `actionStartActivity()`** rather than a manually constructed `TaskStackBuilder` + raw `PendingIntent.send()`, to avoid re-deriving Android 14+/15+ background-activity-launch restrictions by hand. A `TaskStackBuilder`-based exception is permitted only if `actionStartActivity` genuinely can't express the required back-stack behavior, and must then be separately tested on Android 14/15/16.
14. **Worker scheduling is owned by widget lifecycle**: enqueue the periodic backstop in `onEnabled()` (first widget instance created), cancel it in `onDisabled()` (last instance removed). App-process startup does not independently manage this schedule.

## 6. Data Flow
1. `blocksNow` engine produces a new evaluation result (policy state, enforcement state — tri-state — streak).
2. `ProtectionSnapshotWriter` writes a `ProtectionSnapshot` to DataStore, including the discreet/detailed setting.
3. Immediately after, calls `DesertDawnWidget().updateAll(context)`.
4. `provideGlance` collects the DataStore flow. On each qualifying emission (for the life of the current session — see Decision 4′), it computes `DisplayedStatus`, calls `updateAppWidgetState`, and calls `provideContent {}` with the new value.
5. Glance converts the composition to `RemoteViews` and submits it via `AppWidgetManager`; the launcher renders it, and may continue showing it if a later submission is delayed or fails.

## 7. Implementation Sequence
1. Add dependencies (`glance-appwidget:1.1.1`, `glance-material3:1.1.1`, `datastore-preferences:<current stable>`) and create `:feature:widget` module.
2. Define `ProtectionSnapshot` (policy, enforcement tri-state, streak, timestamps, boot count, discreet-mode flag) and the DataStore instance.
3. Implement `ProtectionSnapshotWriter`, sourced from every relevant `blocksNow` transition and every discreet-mode setting change.
4. Implement `DesertDawnWidget` using `PreferencesGlanceStateDefinition` and the collect-based `provideGlance` pattern (Decision 4′).
5. Implement 2×1 and 4×2 Composables under `SizeMode.Responsive`, treating the two `DpSize`s as responsive breakpoints, not literal launcher-cell identities (validate actual rendered size on Pixel Launcher, One UI, and HyperOS — see §12).
6. Implement the tap action with `actionStartActivity()` (Decision 13).
7. Register manifest entries — Receiver **must** be `android:exported="true"` (confirmed: the launcher's `AppWidgetHost` broadcasts from a different process/UID and would otherwise be blocked).
8. Wire WorkManager backstop to `onEnabled()`/`onDisabled()` (Decision 14), plus tap-to-refresh.
9. Add backup exclusion, specified separately for Android 12+ (`data-extraction-rules`) and Android 11 and below (legacy backup), covering both cloud backup and device-to-device transfer (§10).
10. Write Robolectric tests, including the specific regression tests in §12.

## 8. Detailed Implementation Steps

### Step 1 — Dependencies
```kotlin
implementation("androidx.glance:glance-appwidget:1.1.1")
implementation("androidx.glance:glance-material3:1.1.1")
implementation("androidx.datastore:datastore-preferences:<current stable>")
```

### Step 2 — Snapshot model and writer
```kotlin
val KEY_POLICY_ACTIVE = booleanPreferencesKey("policy_active")
val KEY_ENFORCEMENT_STATE = stringPreferencesKey("enforcement_state") // "ACTIVE" | "INACTIVE" | "UNKNOWN"
val KEY_STREAK = intPreferencesKey("streak")
val KEY_EVAL_UTC = longPreferencesKey("eval_utc")
val KEY_EVAL_ELAPSED = longPreferencesKey("eval_elapsed")
val KEY_EVAL_BOOT_COUNT = intPreferencesKey("eval_boot_count") // absent/sentinel on API 23
val KEY_DISCREET_MODE = booleanPreferencesKey("discreet_mode")

val Context.widgetSnapshotDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "widget_protection_snapshot"
)
```

### Step 3 — Freshness computation and `provideGlance`
```kotlin
enum class EnforcementStatus { ACTIVE, INACTIVE, UNKNOWN }
enum class DisplayedStatus { PROTECTED, UNPROTECTED, UNVERIFIED }

private const val MAX_STALE_MILLIS = 30 * 60 * 1000L
private const val CLOCK_SKEW_TOLERANCE_MILLIS = 5 * 60 * 1000L // widened from 2 min for real-world NTP sync drift

fun computeDisplayedStatus(prefs: Preferences?, context: Context): DisplayedStatus {
    if (prefs == null) return DisplayedStatus.UNVERIFIED

    val policyActive = prefs[KEY_POLICY_ACTIVE] ?: false
    val enforcement = when (prefs[KEY_ENFORCEMENT_STATE] ?: "UNKNOWN") {
        "ACTIVE" -> EnforcementStatus.ACTIVE
        "INACTIVE" -> EnforcementStatus.INACTIVE
        else -> EnforcementStatus.UNKNOWN
    }
    val evalUtc = prefs[KEY_EVAL_UTC] ?: 0L
    val evalElapsed = prefs[KEY_EVAL_ELAPSED] ?: 0L
    val evalBootCount = prefs[KEY_EVAL_BOOT_COUNT]

    val nowUtc = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val currentBootCount = Settings.Global.getInt(
            context.contentResolver, Settings.Global.BOOT_COUNT, -1
        )
        if (evalBootCount == null || currentBootCount != evalBootCount) {
            return DisplayedStatus.UNVERIFIED
        }
    } else {
        // API 23 fallback: weaker signal, reboot detected only via elapsed-time regression.
        if (nowElapsed < evalElapsed) return DisplayedStatus.UNVERIFIED
    }

    val deltaUtc = nowUtc - evalUtc
    val deltaElapsed = nowElapsed - evalElapsed
    val clockAnomaly = abs(deltaUtc - deltaElapsed) > CLOCK_SKEW_TOLERANCE_MILLIS
    if (clockAnomaly || deltaUtc > MAX_STALE_MILLIS) return DisplayedStatus.UNVERIFIED

    return when {
        !policyActive -> DisplayedStatus.UNPROTECTED
        enforcement == EnforcementStatus.ACTIVE -> DisplayedStatus.PROTECTED
        enforcement == EnforcementStatus.UNKNOWN -> DisplayedStatus.UNVERIFIED
        else -> DisplayedStatus.UNPROTECTED
    }
}

class DesertDawnWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(120.dp, 60.dp), DpSize(260.dp, 110.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Collect, not .first(): lets a still-live session react to a second write inside
        // its own short lifetime, instead of rendering an immutable initial capture.
        context.widgetSnapshotDataStore.data
            .map { prefs -> computeDisplayedStatus(prefs, context) to (prefs?.get(KEY_DISCREET_MODE) ?: true) }
            .distinctUntilChanged()
            .collect { (status, discreet) ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) {
                    it[stringPreferencesKey("displayed_status")] = status.name
                    it[booleanPreferencesKey("discreet_mode")] = discreet
                }
                provideContent {
                    GlanceTheme(colors = DesertDawnGlanceColorScheme.colors) {
                        DesertDawnWidgetContent(status = status, discreet = discreet)
                    }
                }
            }
    }
}
```

### Step 4 — Receiver, worker lifecycle, and Manifest
```kotlin
class DesertDawnWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DesertDawnWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // preserves APPWIDGET_* dispatch to onUpdate/onDeleted/etc.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            CoroutineScope(Dispatchers.IO).launch { glanceAppWidget.updateAll(context) }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetBackstopScheduler.enqueue(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetBackstopScheduler.cancel(context)
    }
}
```
**Manifest requirements:**
- `DesertDawnWidgetReceiver`: **`android:exported="true"`** (confirmed necessary; the system launcher's `AppWidgetHost` broadcasts `APPWIDGET_UPDATE` from a different process/UID, and `exported="false"` would silently block it).
- Intent filters: `APPWIDGET_UPDATE`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`.

### Step 5 — Deep link / tap action
```kotlin
GlanceModifier.clickable(
    actionStartActivity<UrgeManagementActivity>()
)
```
Fallback (only if `actionStartActivity` cannot express the required back-stack behavior — treat as an explicit, separately tested exception):
```kotlin
class ManageUrgeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(context, UrgeManagementActivity::class.java).setPackage(context.packageName)
        val pendingIntent = TaskStackBuilder.create(context)
            .addParentStack(UrgeManagementActivity::class.java)
            .addNextIntent(intent)
            .getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        pendingIntent?.send()
    }
}
```
If used, this path must be validated end-to-end on Android 14, 15, and 16 against background-activity-launch restrictions before shipping.

## 9. Error Handling
- **DataStore read failure/timeout**: emits `UNVERIFIED`.
- **Missing snapshot**: `UNVERIFIED`.
- **Reboot/clock anomaly**: `UNVERIFIED`.
- **Enforcement state absent or unparseable**: treated as `UNKNOWN`, never coerced to `INACTIVE`.
- **`RemoteViews` payload exceeds budget**: fix layout complexity at build time; treat the 500KB figure as the project's own trip-wire, not a platform error condition to branch on at runtime.
- **Launcher-side render/inflation failure**: not observable from the app process (see §14) — do not build error handling that assumes it is.

## 10. Security Requirements
- Every `PendingIntent`: `FLAG_IMMUTABLE` + explicit `ComponentName`.
- `DesertDawnWidgetReceiver`: `android:exported="true"` (required for `AppWidgetManager` delivery — this is a functional requirement, not a security weakening; the receiver only processes standard AppWidget intents and Glance's own validated `ActionCallback` intents).
- Widget state contains only primitives — no tokens, secrets, or free-text personal logs.
- Backup exclusion of the DataStore file specified separately for: Android 12+ (`data-extraction-rules`), Android 11 and below (legacy `android:fullBackupContent`), cloud backup, and device-to-device transfer. Test an actual restore/migration, not just the XML configuration — a restored stale snapshot must still resolve to `UNVERIFIED` via the boot-count/clock checks, but any other restored widget-adjacent state (e.g., discreet-mode setting, widget instance configuration) should be checked for a defined migration behavior too, since it isn't automatically covered by the DataStore exclusion.
- Discreet mode default-on.
- The DataStore snapshot should be understood as an **application-private state transport**, not a tamper-resistant or cryptographically verified integrity mechanism — app-private storage limits ordinary access by other apps, but nothing here should be described or relied upon as attack-resistant.

## 11. Performance Requirements
- `provideGlance` read path on `Dispatchers.IO`, wrapped with a timeout (500ms as a project SLO, not a platform ceiling).
- 500KB `RemoteViews` payload — project-defined conservative budget, to be measured against actual output rather than assumed.
- No network calls in the widget module.
- No arbitrary layout-depth cap unless profiling shows an actual problem; use measured payload size and render performance as the real constraints instead of a fixed number.

## 12. Testing Requirements
- Unit tests for `computeDisplayedStatus`: NTP-style skew (within and beyond the 5-minute tolerance), reboot detection (API 24+ boot-count path and API 23 fallback path separately), missing snapshot, and each tri-state enforcement branch (`ACTIVE`/`INACTIVE`/`UNKNOWN` under both `policyActive` values).
- Integration test proving `updateAll()` on a snapshot write updates a **currently running** `provideGlance` session's rendered output — not just that `updateAll()` was called (this is the regression test for the collect-based pattern in Decision 4′; a test that only asserts `updateAll()` fired does not prove the fix).
- Test that the widget correctly renders as compact vs. expanded across a range of actual `DpSize` values reported by real launchers, not just the two declared breakpoint values.
- Worker lifecycle test: backstop enqueued on `onEnabled()`, cancelled on `onDisabled()`.
- Backup/restore test: actual restore of the excluded DataStore path (and of any other widget-adjacent state) on Android 12+ and pre-12, plus a device-to-device transfer scenario.
- Manual matrix across distinct kill states: normal process death, launcher swipe-away, low-memory eviction, `Settings`-initiated force-stop, OEM task-cleaner, and full reboot — treated as separate cases, not one "app killed" bucket.
- Manual matrix: Stock/Pixel Launcher, Samsung One UI, Xiaomi HyperOS — covering cell sizing, responsive-layout selection, and stale-view behavior after backgrounding.
- Tap-action test on Android 14, 15, and 16 specifically for background-activity-launch behavior.

## 13. Deployment Requirements
- No runtime feature flag; binary rollback via Play Console staged rollout is the accepted operational mitigation (explicitly not an instant kill-switch for already-installed versions).
- Backward-compatible layout resource IDs.
- Re-verify the Glance dependency version against `developer.android.com/jetpack/androidx/releases/glance` immediately before implementation begins, since library versions can move between spec-writing and coding.

## 14. Monitoring Requirements
- App-side telemetry (reliably observable): composition started, composition success/failure, DataStore read failure/timeout, `updateAll()` invocation and outcome, worker execution and deferral.
- Explicitly **not** treated as observable from app-side telemetry: launcher-side `RemoteViews` inflation failures or launcher crashes. `onCompositionError()` covers exceptions in the app/widget-processing path, not the launcher process — do not build a monitoring story that assumes otherwise. For that failure class, rely on OEM device testing, crash diagnostics, and user-facing support signals instead.
- Track WorkManager deferral rates as a signal of how often the 15-minute backstop is actually running late, since that's documented as inexact under Doze/battery optimization rather than a bug to "fix."

## 15. Risk Register
| Risk | Mitigation |
|---|---|
| OEM battery management defers periodic updates | Tap-to-refresh and options-changed-triggered refresh always available |
| `RemoteViews` payload exceeds budget | 500KB project budget, measured against real output, manual OEM matrix |
| NTP skew false positives | 5-minute tolerance threshold |
| Launcher continues displaying a stale, previously-submitted `RemoteViews` past the 30-minute window | Not fully eliminable; mitigated via event-driven updates + backstop + forced refresh on interaction; consider a visible "last confirmed" indicator in copy (§16 Q5). Since 2026-10-14 (ADR #145) the backstop set includes the platform's own `updatePeriodMillis` broadcast, which reaches the widget with the app process dead or freezer-frozen; only a force-stopped app is beyond reach (no broadcasts at all — hard platform limit, heal is a user launch) |
| A live composition renders a stale captured value during a rapid second change | Collect-based `provideGlance` (Decision 4′) |
| `enforcement=UNKNOWN` silently treated as `INACTIVE` | Tri-state enforcement model (Decision 5′) |
| Background-activity-launch restrictions block the tap action on Android 14+ | `actionStartActivity()` preferred over manual `PendingIntent.send()` (Decision 13) |

## 16. Open Questions
1. Confirm the app's actual `minSdk`. If 23 is genuinely required, confirm product accepts the weaker API-23 reboot-detection fallback in Decision 6′.
2. Does `blocksNow` expose policy and enforcement as genuinely distinct, tri-state-capable signals, or does "unknown enforcement" need to be synthesized at the integration boundary (e.g., no heartbeat received within an expected interval)?
3. Is `policy=INACTIVE` always `UNPROTECTED` regardless of enforcement state, or should some combination instead resolve to `UNVERIFIED`? Decision 5′ makes an assumption here that needs product sign-off.
4. Exact final widget copy strings for discreet vs. detailed mode, and the complete state/action matrix for what "Manage urge" vs. "Refresh" do in each `DisplayedStatus`.
5. Given the launcher-caching gap can't be fully engineered away (§1, §7 Decision 7′), does product want any visible indication of "last confirmed" time in the copy, or is silent `UNVERIFIED` sufficient?
6. Exact Activity class names for backstack construction, if the `TaskStackBuilder` fallback in Step 5 ends up being needed.

## 17. Acceptance Criteria
- Widget renders correctly in both sizes across the real-world `DpSize` range reported by tested launchers, not just the two declared breakpoints.
- Shows `UNVERIFIED` when stale, rebooted, clock-skewed, or when enforcement status is `UNKNOWN` — never `PROTECTED` or `UNPROTECTED` in those cases.
- A composition still active when a second snapshot write lands within its own session window reflects the new value without requiring a new session.
- Deep link opens with a correct back stack, verified on Android 14, 15, and 16.
- Worker backstop is present exactly while at least one widget instance exists, verified by test.
- No crashes on tested OEM launchers.
- Documentation (code comments and any product-facing spec) states the freshness rule as a render-time bound the app enforces on itself, not a guarantee about what the launcher physically displays.

## 18. Definition of Done
- All implementation steps complete and code-reviewed, including the collect-based `provideGlance` pattern and the tri-state enforcement model.
- Unit/Robolectric suite passing, including the "live session reflects a second write" regression test.
- Manual OEM matrix executed (Pixel Launcher, One UI, HyperOS) across the distinct kill-state test cases in §12.
- Backup/restore and device-to-device transfer tested, not just configured.
- Monitoring events flowing in staging, scoped to what's actually app-side observable.
- Open Questions (§16) resolved with product sign-off, particularly Q2, Q3, and Q5.
- Glance dependency version re-verified as current stable immediately before implementation start.
