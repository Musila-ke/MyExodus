# MyExodus — Enhanced Mode: Foreground Detection & Blocking Overlay

## Final Implementation Specification (Post-Audit, Resolved)

*Status revision 2026-10-17 (docs audit): built and device-proven; this is the spec of record. §3's "V1 ships with UsageStatsManager… Play Store distributed" describes the pre-Enhanced-Mode baseline, not today's app — there is no Play distribution (ADR #15). Later device evidence supersedes the §12 status paragraphs as they age: ADR #17/#30/#63 (XOS fixes, ANR class, latency), the split-screen ADR (multi-window verified PASS 2026-09-14), and the X6532C second-unit runs (2026-10-01). The §12 question on whether the p99 metric should exclude deliberate deferral wait is still unresolved — it is a metric-definition ruling for the owner, not a build gap.*

*Revision 2026-11-14 (ADR #308, owner ruling): the in-app Enhanced Mode master switch and Strict Mode are removed — blocking is always on. §2's Strict Mode requirement, Decision 6's "in-app Strict Mode friction only", Decision 15's Strict Mode clause, §8 Step 11 and §12's Strict Mode acceptance line are historical records, not shipped behavior. §8 Step 12's AAPM detection stands and now renders as the Protection pane's accessibility-row supporting line.*

---

## 1. Feature Overview

Enhanced Mode is a real-time app- and URL-blocking engine for MyExodus's distributable-APK (sideload) build. It detects when the user opens an app or navigates to a URL on their own restriction list and renders a full-screen blocking overlay within the latency budget defined below. It runs as a bound `AccessibilityService`. It requests no `SYSTEM_ALERT_WINDOW` permission and implements no OS-level anti-removal mechanism. The user can always uninstall or disable the app through normal Android settings; impulsive in-app disabling is discouraged only through the in-app "Strict Mode" friction mechanism.

This feature ships only in the distributable-APK build, not the Play Store build. It layers on top of the existing V1 friction + accountability-partner architecture. V1's `UsageStatsManager`-based flow is untouched.

---

## 2. Requirements

**Functional**
- Detect foreground app transitions and match them against the user's app-restriction ruleset.
- Detect and block specific URLs inside supported browsers (Chrome, Firefox, Samsung Internet at launch).
- Render a full-screen, touch-blocking overlay when a match occurs; remove it the moment the foreground state stops matching.
- Provide an in-app "Strict Mode" that adds a 24-hour cooldown before Enhanced Mode can be disabled from within the app. Accountability-partner override is feature-flagged off until the partner-approval protocol (§16) ships separately.

**Non-functional**
- Detection-to-overlay latency: `<1000ms` at p99, measured per §12, on real target hardware.
- Frame-presentation latency (decision made → overlay actually visible): `<100ms` at p99, measured via `Window.OnFrameMetricsAvailableListener` attached to the overlay's own `Window` (§8 Step 5).
- Background CPU: `<2%` average, measured on target hardware.
- Life-safety: the overlay must never render over the live default dialer, `ROLE_EMERGENCY` holder, active call UI, or keyguard/lock-screen surfaces, on the device matrix defined in §12. This is an empirically-validated guarantee for the tested matrix, not a formal proof for all Android configurations.
- No behavior in this feature may use a technique prohibited by Google Play's Accessibility API policy, even though this build isn't Play-distributed.

**Out of scope**
- Play Store distribution of Enhanced Mode.
- Enterprise/MDM (`DevicePolicyManager`) deployment.
- VPN-based network-level URL filtering.
- Accountability-partner cryptographic-approval protocol design (tracked separately; see §16).

---

## 3. Existing System Context

- V1 ships with `UsageStatsManager`-based detection, no `AccessibilityService`, Play Store distributed. Untouched by this feature.
- An existing SQLCipher-backed database holds rules and activity/attempt logs. This spec migrates that database as described in §7 Step 1 and §8 Step 1.
- Package name is `com.example.myexodus` (placeholder; not this spec's concern).
- Accountability-partner feature exists as a separate module; this spec integrates with it only at the Strict Mode gate (§8 Step 10), behind a feature flag until that module's crypto protocol ships.

---

## 4. Architecture

```
BlockerAccessibilityService
   │
   ├─▶ EmergencyExclusionManager     — runs first, every event, before any other logic
   │
   ├─▶ AccessibilityEventNormalizer  — extracts package/window, assigns generation number
   │
   ├─▶ ForegroundStateMachine        — tracks lastKnownPackage, lastKnownWindow, generation
   │
   ├─▶ BlockDecisionEngine           — reads AtomicReference<RulesSnapshot>, decides block/allow
   │
   ├─▶ BrowserUrlScanner             — content-changed events, browser-package-scoped only
   │
   └─▶ OverlayController             — owns per-display overlay Dialog instances
            │
            └─▶ LatencyProfiler      — FrameMetrics-based staged timing

RulesRepository
   ├─▶ AppRulesStore (Room/SQLite, unencrypted, FBE-protected at rest)
   └─▶ ActivityLogStore (net.zetetic:sqlcipher-android, encrypted)

RulesSnapshotPublisher
   ├─▶ Fast path: loads last-known-good snapshot from a small on-disk cache synchronously
   │              at onServiceConnected(), before the authoritative DB load completes
   └─▶ Authoritative path: loads from AppRulesStore on a background thread, then
                           AtomicReference.set() with the new immutable RulesSnapshot

StrictModeManager (app UI layer, not the AccessibilityService)
   └─▶ Gatekeeps disabling Enhanced Mode from MyExodus's own settings screen
```

- The database is never opened, queried, or written on the `onAccessibilityEvent` hot path. All rule lookups hit the in-memory `RulesSnapshot` only.
- No `SYSTEM_ALERT_WINDOW` permission is requested anywhere in this feature.
- No always-on `ForegroundService` is used for keepalive.

---

## 5. Architecture Decisions

1. **Detection: `AccessibilityService` subscribing to both `TYPE_WINDOW_STATE_CHANGED` (app-level transitions) and `TYPE_WINDOW_CONTENT_CHANGED` (URL-bar changes, browser packages only).** `TYPE_WINDOW_STATE_CHANGED` alone cannot see in-page navigation; content-changed events are filtered to the browser allowlist in code so they never touch the general app-blocking hot path.
2. **Overlay type: `TYPE_ACCESSIBILITY_OVERLAY`, presented via a lightweight `Dialog` subclass rather than a bare `WindowManager.addView(View, …)` call.** A `Dialog` exposes a real `Window`, which `OnFrameMetricsAvailableListener` requires; a raw added `View` does not have one to attach to.
3. **No always-on `ForegroundService`.** The bound `AccessibilityService` binding is already system-managed and high-priority; FGS reliability is validated empirically in §12 rather than assumed unnecessary or assumed required.
4. **Cache: immutable `RulesSnapshot` behind `AtomicReference`.** Atomic, consistent reads during concurrent rule updates, no partial-update states.
5. **Database split: standard Room/SQLite for the `rules` table; `net.zetetic:sqlcipher-android:4.18.0` (+ `androidx.sqlite:sqlite:2.7.0`) for the `activity_log` table only.** The user's own app/URL blocklist is not sensitive data and Android FBE already encrypts it at rest; the activity/attempt log feeds the accountability-partner feature and keeps encryption, `secure_delete=ON`, `cipher_memory_security=ON` unconditionally.
6. **Anti-removal: in-app Strict Mode friction only.** No OS-level Settings interception, no `GLOBAL_ACTION_HOME` redirection, no covering of uninstall/force-stop controls. OS-level uninstall/disable always works immediately and is never intercepted.
7. **No Restricted-Settings-bypass tooling, no instructions to disable Advanced Protection Mode.** AAPM-active devices (Android 17.2+, API 36+) are a detected, unsupported configuration (§8 Step 11), not a workaround target.
8. **`isAccessibilityTool` is deliberately NOT declared.** MyExodus is not a screen reader, switch-input system, voice-input tool, or Braille-access app under Google's accessibility-tool definition, so declaring it would misrepresent the app. The consequence — automatic accessibility-grant revocation under AAPM — is accepted and surfaced to the user (§8 Step 11), not worked around.
9. **Emergency-surface exclusion runs before every other step in the event pipeline, checking both `RoleManager.ROLE_DIALER` and `RoleManager.ROLE_EMERGENCY`, plus `TYPE_SYSTEM` window classification, plus a static OEM fallback list as a supplemental signal only.** Any exception or ambiguity resolves to "protected" (fail-open — never block).
10. **Multi-window handled via `getWindowsOnAllDisplays()` with `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, one overlay `Dialog` per matched window, each carrying its own `displayId`.** `WindowManager.addView()` defaults to the primary display; overlays on secondary/foldable displays require an explicit per-display `WindowManager` obtained via `context.createDisplayContext(display)`.
11. **Latency staged into three explicit measurements: detection (event timestamp → decision timestamp), submission (decision → `dialog.show()` call/return), and presentation (`dialog.show()` → first `FrameMetrics.TOTAL_DURATION` callback on the dialog's `Window`).** These are reported and tested separately; none is used as a stand-in for another.
12. **Private Space and Work Profile contexts are explicitly unsupported.** No cross-profile mechanism exists for a primary-profile-bound `AccessibilityService` to see or overlay apps in an isolated profile.
13. **Snapshot lifecycle is explicit: `UNINITIALIZED → LOADING → READY(version) → DEGRADED(reason)`.** `LOADING` is bridged by a synchronously-loaded last-known-good snapshot from a small on-disk cache, so the blocking engine is never in a state where "no rules loaded yet" is misread as "no rules exist."
14. **URL matching runs against a canonicalized model, never raw address-bar text (§8 Step 8).** Canonicalization is a defined, testable transform, not left to the matching code's discretion.
15. **The accountability-partner cryptographic-approval path is out of this spec's scope and ships behind a feature flag, off by default.** Strict Mode ships v1 with the 24-hour cooldown path only.

---

## 6. Data Flow

1. System fires `TYPE_WINDOW_STATE_CHANGED` (app transition) or `TYPE_WINDOW_CONTENT_CHANGED` (in-browser navigation, browser packages only).
2. `BlockerAccessibilityService.onAccessibilityEvent()` timestamps entry (`SystemClock.elapsedRealtimeNanos()`) and extracts `event.getPackageName()`.
3. `EmergencyExclusionManager.isProtected(packageName, windowInfo)` runs first. If `true`, or the package/window is ambiguous, or the check throws, the pipeline stops here — overlay hidden/not shown, no further processing.
4. `AccessibilityEventNormalizer` assigns a monotonically increasing generation number to this transition and updates `ForegroundStateMachine`.
5. For `TYPE_WINDOW_STATE_CHANGED` events: `BlockDecisionEngine` reads the current `RulesSnapshot` via `AtomicReference.get()` and checks `packageName` against it. O(1) lookup, no I/O.
6. For `TYPE_WINDOW_CONTENT_CHANGED` events on a browser-allowlisted package: `BrowserUrlScanner` captures the current generation, applies a 300ms debounce, then extracts and canonicalizes the URL (§8 Step 8) and runs it through the same `BlockDecisionEngine` path. If a newer generation has been recorded by the time the scan result returns, the result is discarded.
7. If restricted: `OverlayController.show(generation, displayId)` — attaches the display-scoped overlay `Dialog` only if `generation` is still the latest known.
8. If allowed and overlay currently shown: `OverlayController.hide(generation, displayId)`, same generation check.
9. `LatencyProfiler` records the three staged durations from Decision 11 and appends them to a rolling local buffer.

---

## 7. Implementation Sequence

1. **Database split & migration** — stand up Room/SQLite for `rules`; stand up `net.zetetic:sqlcipher-android:4.18.0` for `activity_log`; write the one-time migration script (§8 Step 1).
2. **RulesRepository + RulesSnapshotPublisher** — on-disk fast-path cache, async authoritative load, `AtomicReference<RulesSnapshot>` publication.
3. **EmergencyExclusionManager** — implemented and unit/device-tested before any overlay code is written.
4. **BlockerAccessibilityService core** — event filtering (both event types), `ForegroundStateMachine`, `BlockDecisionEngine`, wired to steps 2–3.
5. **OverlayController** — per-display `Dialog`-based overlay, `TYPE_ACCESSIBILITY_OVERLAY`, attach/detach, touch-blocking.
6. **LatencyProfiler** — `FrameMetrics` integration on the overlay `Dialog`'s `Window`, staged logging.
7. **BrowserUrlScanner** — canonicalization model, debounced node scan, generation-aware, gated to the browser allowlist.
8. **Boot receiver** — `ACTION_BOOT_COMPLETED` → async cache pre-warm.
9. **Health-check** — `WorkManager` periodic job (§8 Step 9) plus a live `AccessibilityManager.AccessibilityStateChangeListener` while the process is running.
10. **StrictModeManager** (app UI) — 24-hour cooldown gate; partner-approval path stubbed behind a feature flag.
11. **AAPM/unsupported-configuration detection** — `AdvancedProtectionManager` check, unsupported-config screen.

Steps 1–3 block everything after them. Step 10 can be built in parallel with 4–9.

---

## 8. Detailed Implementation Steps

### Step 1 — Database
- `AppRulesStore`: standard Room database, no encryption layer. FBE (default on all supported API levels) covers at-rest protection for this non-sensitive data.
- `ActivityLogStore`: `net.zetetic:sqlcipher-android:4.18.0` + `androidx.sqlite:sqlite:2.7.0` (current pinned release as of this spec — re-verify before each dependency bump, do not float on "latest"). Open via `net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(File, char[], …)`; the `char[]` overload is a real, supported entry point on this library. Key material is Android Keystore-wrapped, decrypted into the `char[]` immediately before the open call, and zeroed (`Arrays.fill(key, '\u0000')`) immediately after keying completes. Never stored as a `String` or a static field.
- PRAGMAs on `ActivityLogStore`: `journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=5000`, `secure_delete=ON`, `cipher_memory_security=ON`. These four remain fixed; do not tune them without a documented profiling result showing a specific one is the bottleneck.
- **Migration:** on first launch post-update, a background-thread script opens the old combined SQLCipher database with the existing key, copies `rules` rows into the new `AppRulesStore` (Room), and copies `activity_log` rows into the new `ActivityLogStore` (re-keyed with a freshly generated Keystore-wrapped key). Row counts are verified to match before the old database file is deleted. On any mismatch or read failure, the old file is preserved and migration is retried on next launch — the app must not delete a database it wasn't able to fully verify.

### Step 2 — Rules cache
- `RulesSnapshot` is an immutable data class: `Map<String, Rule>` for apps, `List<UrlPattern>` for URL rules (canonicalized form, see Step 8), a `version: Long`.
- Fast path: at `onServiceConnected()`, synchronously read a small serialized snapshot file (last successfully published `RulesSnapshot`, written to disk on every republish) and publish it immediately via `AtomicReference.set()`. This eliminates the "enabled but uninitialized" gap — the engine always has *some* snapshot, even if briefly stale.
- Authoritative path: `RulesSnapshotPublisher.loadFromDb()` runs on a background thread immediately after the fast path, and again after every successful write transaction. It builds a full new `RulesSnapshot`, writes it to the on-disk cache file, then calls `AtomicReference.set()`.
- Ruleset size cap: 2,000 app rules and 2,000 URL patterns. Beyond that, new rule additions are rejected in the UI with an explanatory message. At this scale, linear URL-pattern matching runs in single-digit milliseconds; no trie or indexed structure is required.
- State model: `UNINITIALIZED` (before fast path completes) → `LOADING` (fast-path snapshot published, authoritative load in flight) → `READY(version)` (authoritative snapshot published) → `DEGRADED(reason)` (authoritative load failed; fast-path/last-known-good snapshot remains in use, reason logged). `BlockDecisionEngine` treats `LOADING` and `DEGRADED` identically to `READY` for decision purposes — it always decides against whatever snapshot is currently published, never against "no rules."

### Step 3 — EmergencyExclusionManager
- On `onServiceConnected()` and on `ACTION_DEFAULT_DIALER_CHANGED`: call `RoleManager.getRoleHolders(RoleManager.ROLE_DIALER)` and `RoleManager.getRoleHolders(RoleManager.ROLE_EMERGENCY)` (where available on the API level) and cache both as live-protected package sets.
- Maintain a small static fallback list (AOSP Dialer, Google Phone, known OEM dialer packages including Infinix/XOS) used only as a supplemental signal when the `RoleManager` queries return empty.
- Exclude by window type: any `AccessibilityWindowInfo` of type `TYPE_SYSTEM` classified as keyguard or incoming-call UI.
- `isProtected(packageName, windowInfo)` returns `true` if `packageName` is null/empty, in either live role set, in the fallback list, the window is a protected system window, or the check itself throws. This runs first, unconditionally, on every `onAccessibilityEvent` call.

### Step 4 — BlockerAccessibilityService
- `res/xml/accessibility_service_config.xml`: `accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"`, `notificationTimeout="0"`, `canRetrieveWindowContent="true"`, `flagRetrieveInteractiveWindows="true"`.
- Content-changed events are checked against the browser allowlist (`AccessibilityEventNormalizer`) before any node traversal runs; for all other packages, a content-changed event is a no-op with no tree walk performed.
- `ForegroundStateMachine` fields: `lastKnownPackage`, `lastKnownWindowId`, `lastDetectionTimeNanos`, `generation` (monotonic `Long`).
- If `event.getPackageName()` is null/empty and the transition isn't caught by the emergency check, treat it as unknown immediately and hide the overlay — no retain window. (Stale-event protection is handled entirely by the generation check, not by holding onto a possibly-wrong package name.)

### Step 5 — OverlayController
- The overlay is a custom `Dialog` (no title bar, `Window.FEATURE_NO_TITLE`), created once per display it's needed on and reused. `dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)`.
- `WindowManager.LayoutParams`: `flags = FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`, `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`. Do not set `FLAG_NOT_TOUCHABLE`.
- Override `onTouchEvent()` on the dialog's root view to return `true` unconditionally, consuming all touches.
- **OEM touch-passthrough exception:** if the device-matrix test in §12 shows a given OEM/version routes touches through a non-focusable `TYPE_ACCESSIBILITY_OVERLAY` to the app beneath it, ship an OEM-keyed configuration entry that drops `FLAG_NOT_FOCUSABLE` for that OEM/version combination specifically. On those devices only, the overlay will take focus and pause the underlying app; this is an accepted trade-off, scoped narrowly, not a global default.
- `show(generation, displayId)` / `hide(generation, displayId)` both check the passed generation against `ForegroundStateMachine.generation` before acting.
- `WindowManager.addView()` / `dialog.show()` failure (bad token, already-attached, revoked permission, dead system server) is caught, logged with the specific failure category, and leaves the overlay in a known "not shown" state. It never crashes the service.
- Multi-window: for each `AccessibilityWindowInfo` returned by `getWindowsOnAllDisplays()` that resolves to a restricted package, extract `displayId` via `AccessibilityWindowInfo.getDisplayId()`, obtain a display-scoped `Context` via `context.createDisplayContext(displayManager.getDisplay(displayId))`, and show that display's dedicated overlay `Dialog` instance, bounded to that window's `getBoundsInScreen()`.

### Step 6 — LatencyProfiler
- Attach `Window.OnFrameMetricsAvailableListener` to each overlay `Dialog`'s `Window` at dialog-creation time (this is why Decision 2 uses a `Dialog` instead of a bare added `View`).
- Log the three durations from Decision 11 per block event.
- Persist locally (rolling buffer, last 500 events) for local p50/p95/p99 computation; write asynchronously, never on the hot path.
- Sample at 100% in debug/QA builds, 1% in production builds to bound overhead.

### Step 7 — Browser package allowlist
- `com.android.chrome` (`id/url_bar`), `org.mozilla.firefox` (`id/url_bar_text`), `com.sec.android.app.sbrowser` (`id/location_bar_text`) at launch.
- Content-changed node scans (Step 8) are invoked only for packages on this allowlist.
- View-ID lookup failure (browser update changed the ID): silent no-op, no block, no crash, diagnostic event logged. This is a documented capability limitation per browser/version, tracked as ongoing maintenance (§14, §16), not solved once and forgotten.
- **Revision 2026-09-13 (superseded in the shipped code):** the package allowlist was replaced by runtime discovery — `BrowserUrlScanner.viewIdFor` probes a small set of known toolbar view-IDs (`url_bar`, `url_bar_text`, `location_bar_text`) inside the event package's own tree on first sight and memoizes the result per package; a package that shows no toolbar is retried after 10 s, so no browser needs a code change to be scanned and a false negative cannot outlive the retry window. A browser whose toolbar ID is in none of the candidates still needs one list entry (the §16 maintenance point). Rationale: real devices carry arbitrary browsers, and an allowlist can only cover ones someone listed (user-raised 2026-09-13).

### Step 8 — URL canonicalization & matching
- Before matching, transform the extracted address-bar string as follows: lowercase the host; decode punycode/IDN to Unicode for comparison and also retain the raw punycode form (match against both); strip a default port (80 for http, 443 for https) and compare an explicit non-default port as part of the host component; strip userinfo (`user@`) entirely before comparison; ignore path, query string, and fragment unless the stored `UrlPattern` explicitly includes a path prefix; treat `http` and `https` as equivalent unless the pattern specifies a scheme; match on registrable domain plus any subdomain the pattern specifies (`example.com` matches `sub.example.com`; it does not match `example.com.evil.com`). **A scheme-less candidate — the address bar's normal display state (verified on-device 2026-08-27: Chrome shows `example.com` for `http://example.com`) — states no scheme and therefore matches any scheme-pinned pattern; only an explicit candidate scheme is compared against the pattern's pin.**
- `blob:`, `data:`, and `javascript:` schemes are never matched against restriction rules and are always allowed through — they are not addressable, user-navigable URLs in the sense this feature restricts.
- 300ms debounce (per Data Flow step 6) before running the node scan after a content-changed signal.
- **Revision 2026-10-17 (autocomplete edit-session gate; ADR #174).** The omnibox writes autocomplete completions into the same address-bar node the scan reads, so a typed `insta` previewing `instagram.com` decided and blocked a site the user never opened — repeatedly (owner report: *"it checks as you type and blocks … over and over"*). The scanner now reads the bar node's **`isFocused`** state at fire time and defers any decision while the bar owns focus — not even Allow, which would dismiss a still-blocked page's card. The state is read at fire time (a commit inside the debounce window must still decide), and a node/browser that never reports focus fails open, so the gate can only suppress a false block, never a real one. An earlier attempt gated on `AccessibilityWindowInfo.isVisible`; that method does not exist on the API 34 runtime or the SDK stubs (device-proven `NoSuchMethodException`), so it was never armed — record corrected, no reflection remains.

### Step 9 — Boot receiver
- `ACTION_BOOT_COMPLETED` receiver triggers the fast-path on-disk snapshot load synchronously, then `RulesSnapshotPublisher.loadFromDb()` asynchronously. No UI, no blocking work in the receiver itself.

### Step 10 — Health-check
- `WorkManager` periodic job at the OS-minimum interval (~15 minutes) checks `AccessibilityManager.getEnabledAccessibilityServiceList()` for this service. This is documented and tested as **eventual** detection, not continuous monitoring.
- In addition, while the app process is alive, register an `AccessibilityManager.AccessibilityStateChangeListener` to catch a mid-session disablement sooner than the next periodic job tick.
- If disabled: surface a local notification/in-app banner. Never attempt to silently re-enable the service — that isn't possible without user action.

### Step 11 — StrictModeManager (app UI)
- Lives entirely in MyExodus's own settings UI, not the `AccessibilityService`.
- v1 ships with the 24-hour cooldown path only. The accountability-partner cryptographic-approval path exists as a feature flag, default off, until that module's protocol (key generation, registration, revocation, replay prevention, offline approval — see §16) is specified and implemented as its own effort.
- Disabling the accessibility service directly from Android's own Settings/Accessibility screen always succeeds immediately; this is normal OS behavior and is never intercepted or delayed.

### Step 12 — Unsupported-configuration detection
- Declare `<uses-permission android:name="android.permission.QUERY_ADVANCED_PROTECTION_MODE" />`.
- On `onServiceConnected()` and on every health check, query `AdvancedProtectionManager` for AAPM status (API 36+ devices).
- Because `isAccessibilityTool` is deliberately not declared (Decision 8), an AAPM-active device will auto-revoke MyExodus's accessibility grant and prevent re-granting while AAPM remains on (confirmed current platform behavior, Android 17.2+). When this state is detected, show an in-app "Enhanced Mode isn't supported with Advanced Protection enabled on this device" screen. No instructions to disable AAPM are shown anywhere in the app.
- For standard Restricted Settings (Android 13+ sideload block), show only the legitimate flow: App Info → three-dot menu → "Allow restricted settings."

---

## 9. Error Handling

- **Emergency-exclusion check failure of any kind → fail open (never block).**
- **Null/empty package name:** treated as unknown immediately, overlay hidden. No retain window.
- **DB lock contention (`ActivityLogStore`):** handled by `busy_timeout=5000`; if still failing after timeout, log and skip the write — never crash the service.
- **`dialog.show()` / `addView()` failure:** caught, categorized (already-attached / invalid display / permission-revoked / dead system server / bad parameters), logged, overlay stays "not shown"; next valid transition retries normally.
- **Accessibility service disabled/unbound:** the platform handles rebinding natively per the documented `AccessibilityService` lifecycle; no custom restart logic. The health check (Step 10) only detects and alerts.
- **Browser view-ID lookup failure:** silent no-op, logged, no fallback guessing heuristics.
- **Stale/reordered events:** the generation-number check is the single source of truth across both the overlay path and the URL-scan path; any result carrying an older generation than current state is a no-op everywhere.
- **AAPM-active or Android 17.2+ auto-revocation:** treated as an unsupported configuration, surfaced to the user (Step 12), never worked around.
- **DB migration failure/mismatch (Step 1):** old database file preserved, migration retried on next launch, app continues to function with V1 detection in the interim.

---

## 10. Security Requirements

- No `SYSTEM_ALERT_WINDOW` permission requested anywhere in this feature.
- Overlay must consume all touch events (no `FLAG_NOT_TOUCHABLE`) — verified by the tapjacking test (§12), per-OEM.
- `ActivityLogStore` key material: Keystore-wrapped, decrypted into a `char[]` only immediately before the open call, zeroed after keying. Never stored as a `String` or a static field.
- `secure_delete` and `cipher_memory_security` remain ON for `ActivityLogStore` unconditionally, with no tuning exception.
- No monitoring of `com.android.settings`, no `GLOBAL_ACTION_HOME` usage, no covering of system uninstall/force-stop UI, anywhere in this feature.
- No session-installer spoofing (SAI-style) tooling and no instructions for disabling Advanced Protection Mode appear anywhere in the app.
- `isAccessibilityTool` is not declared; the standard non-accessibility-tool disclosure is shown to the user before the service can be enabled.
- Data minimization: no raw screen content, URLs, or app-usage data are logged or transmitted beyond what `rules`/`activity_log` are explicitly designed to store for the accountability-partner feature, and beyond the local-only latency/CPU diagnostics in §14.

---

## 11. Performance Requirements

- No database I/O inside `onAccessibilityEvent()` under any circumstance.
- No regex compilation inside `onAccessibilityEvent()` — URL patterns are pre-compiled/pre-canonicalized at snapshot build time.
- No blocking calls inside `onAccessibilityEvent()`.
- Allocation rate inside the hot path is measured via Android Studio Profiler/Perfetto and reported per §12; the target is a documented low budget (set from first-measurement baseline during QA), not an assumed zero — the pre-inflated overlay view and reused dialog instances are the mechanism that keeps this low, not a hard "zero" contract.
- Event filtering strictly limited to `typeWindowStateChanged` and `typeWindowContentChanged` (the latter gated to the browser allowlist before any further work happens).

---

## 12. Testing Requirements

- **Life-safety test (release-blocking):** automate switching to the live default-dialer and `ROLE_EMERGENCY` packages (via `RoleManager` query, never a hardcoded name) and to in-call UI; assert the overlay never appears and no touch is intercepted. Run on: idle, outgoing emergency call, outgoing ordinary call, incoming call, in-call UI, lock screen, locked device, emergency dialer, reboot, screen wake, keyguard dismissal, and call transition while an overlay is already visible. Required on at least one physical Infinix/XOS device plus two additional OEM devices (e.g., Samsung OneUI, Xiaomi MIUI).
- **Tapjacking test, per OEM in the matrix above:** dispatch touch coordinates beneath an active overlay; assert zero events received underneath. Where this fails, apply the OEM-keyed exception from §8 Step 5 and re-test.
- **Latency test:** automate app-switch-to-restricted-app cycles on real target hardware; capture the three staged durations from Decision 11; compute p50/p95/p99 against §2 targets.
- **Freezer survival test:** `adb shell am freeze <package>` / `am unfreeze` cycle; verify the service resumes without ANR or crash.
- **Cache-consistency test:** concurrent rule writes during active transitions; assert `BlockDecisionEngine` never reads a partially-applied ruleset.
- **Stale-event test:** inject an out-of-order/delayed accessibility event and a late URL-scan result; assert neither can re-show or hide an overlay after a newer transition has already resolved.
- **Multi-window test:** split-screen and, where hardware allows, a folded/unfolded transition, with one restricted and one allowed app visible; assert only the correct pane on the correct `displayId` is overlaid.
- **Browser URL test:** matched-URL block case; canonicalization edge cases from §8 Step 8 (punycode, default port, subdomain, userinfo-stripping, `blob:`/`data:` exclusion); and a browser-update-simulated (missing view ID) case asserting silent no-op, not a crash or false block.
- **AAPM/unsupported-config test:** simulate AAPM-active state on an API 36+ device or emulator image; assert the unsupported-configuration screen appears and no bypass path is offered.
- **DB migration test:** run the migration script against a snapshot of the old combined database; assert row counts match and the old file is only deleted after verification.

**§12 device-matrix status (Infinix X6532/XOS, 2026-08-27/28):** harness = debug-only `DeviceVerificationActivity` in the main APK (instrumentation impossible on XOS user builds — `am instrument` processes never bind the accessibility service; `Context.startInstrumentation` from an app process is AOSP-denied), driven by `scripts/enhanced-mode-device-test.sh` via logcat MARKER protocol. Functional suite (life-safety ×3 incl. user-assisted keyguard unlock, tapjacking, browser URL): **PASS**. Freezer survival: **PASS**. Browser URL blocking required four on-device root-cause fixes (same-window WSC generation flapping, scheme-less omnibox text, page-subtree event source, null-window WSC flapping) — all landed in code with unit tests; §8 Step 8 amended for the scheme-less rule. Latency (measured 2026-08-28, 30-cycle suite, fresh per-suite buffer): detection p99 12ms, submission p99 78ms, **total detection→overlay p99 317ms — PASS vs the <1000ms §2 budget**; **presentation p99 227ms (p50 73ms) — MISS vs the <100ms sub-target** (device first-frame scheduling under the launch-animation storm; see latency-suite notes below). Null-window WSC delayed-entry fix (400ms deferred re-check) verified in-run (drop path observed, no generation corruption). Multi-window: **N/A on this device** — XOS build offers no split-screen (verified 2026-08-28, suite self-skip logged); verification moves to the matrix devices. AAPM unsupported-config: pending API 36+ device. Remaining matrix devices: Samsung OneUI, Xiaomi MIUI. **Post-matrix review changes (2026-08-28 five-axis review — functional re-run REQUIRED on next device connection):** WSC decisions now gated on `window.isActive`/`rootInActiveWindow` (background interactive windows can no longer Allow-hide the overlay); per-bind state machine + overlay (no longer @Singleton — a Hiber re-bind would have carried stale generation state); URL scans skipped while the app-level decision is Block; per-window root sweep only on new block transitions; publisher single-flight → Mutex; migration zeroed-key + NULL-coercion fixes; schema priming before migration. See ADR #17 review addendum for the full list. **Post-review device re-run (2026-08-28, final APK, 15:23):** full script green — functional PASS (life-safety ×3 incl. user-assisted unlock, tapjacking PASS, browser URL cycle), freezer PASS, **latency PASS: detection p99 8ms, submission p99 33ms, presentation p99 91ms (first back-to-back PASS; p50s 2/25/25ms), total detection→overlay p99 ≈132ms vs the <1000ms §2 budget**, multi-window self-skip (no split-screen on this build, as recorded). The re-run found and fixed two real show-vanish bugs: recycled-window getter throws swallowed by accessibility dispatch (window reads now runCatching everywhere) and emergency-exclusion hiding on background protected-package flaps (hide now gated on active-foreground, same rule as the WSC decide gate); screen-off hides deterministically via ACTION_SCREEN_OFF (XOS keyguard WSC ~6s late). Result: 90 consecutive latency cycles across three runs with zero vanish failures. **Presentation-p99 finding (supersedes the note below):** XOS WMS defers the overlay's first frame when added mid-transition — 227/162/185ms in the first 3 back-to-back runs, 67/81ms standalone, 91ms back-to-back on the final code: a flaky per-device first-frame property, not run-order-deterministic, documented rather than gamed; the total budget is still met by ~7.5× margin. **Re-runs 2026-09-19 (task #9 sweep, post-ADR-#63 APK):** detection p50/p95/p99 = 2/8/10 ms and 2/13/14 ms across two runs (p99 1699 ms on the pre-fix APK — the ANR class ADR #63 removed); submission 34/44/46 ms; presentation p50/95/99 = 40/110/131 ms (run 7) and 62/84/113 ms (run 5) — the miss reproduces in a stable band, so it is a property of this device, not a one-off. Attribution note: the XOS presentation stage is `addView` → first `OnPreDraw` (a Main-thread traversal), not the stock `FrameMetrics.TOTAL_DURATION` path — so the tail is either WMS first-frame deferral under the launch transition or Main-thread scheduling under the suite's own launch storm; the staged measurement cannot distinguish them, and the ADR #63 sweep already removed the one known app-side Main-thread blocker class. Total detection→overlay p99 ≈ 179 ms vs the <1000 ms §2 budget. Lint zero findings, compile warnings zero, Room 3.0.2 (Trivy clean). **FGS-down wall addendum (2026-09-13):** the debug harness gained an `fgs-down` suite — stops `ForegroundBlockerService` in-process, then exits via the HOME intent (device-caught: a plain `finish()` or `finishAndRemoveTask()` leaves `MainActivity` the shared task's top and its `onResume` restarts the FGS within ~0.5 s). Verified on X6532: with the service down, the a11y cover alone walls a restricted app (zero `BlockerActivity` starts, service count 0, 20 s+ persistence through the Hiber `float_window` event); the cover's recovery button opens the Recovery tab with the live 60 s session and the FGS restarts ~65 ms after the MainActivity start. Harness hazard noted: a `uiautomator dump` run in the same window correlated with a one-off cover vanish that did not reproduce without it. Victim-APK caveat: the victim (`com.example.myexodus.test`, the androidTest APK launched standalone) has no Kotlin stdlib of its own, so a leaked touch crashes it (`ClassNotFoundException: kotlin.jvm.internal.Intrinsics`) before the `VictimTouch` line logs — the tapjacking assertion in `scripts/enhanced-mode-device-test.sh` counts the crash's `Process: com.example.myexodus.test,` line as a leak as well. **X6532C run (2026-10-01, second physical unit, two-device test day):** latency suite ×3, 30 cycles each, all cycles blocked correctly. detection p50/p95/p99 = 26/1263/2268, 7/1514/1759, 10/939/1073 ms; submission p99 179–336; presentation p99 329–415. Steady state excellent (p50 detection 7–26 ms), but ~2/30 victim launches per run ride the deliberate deferred-WSC re-check loop (400 ms × up to 6 attempts, `BlockerAccessibilityService` deferredWsc) because the victim's window is not the active root for >1.6 s under cold launch on this unit — the p99 tail therefore MISSES the <1000 ms §2 check on X6532C in all 3 runs (A baseline under the same code: p99 10–14 ms). Presentation p99 329–415 ms repeats the known <100 ms sub-target miss (~3× the A band). **Open question for an owner/plan ruling: should the §2 p99 metric exclude deliberate deferral wait (metric definition vs the ADR'd deferral trade), or is the C-unit tail a genuine §2 miss?** Harness bug found and fixed the same day: `DeviceVerificationActivity.seed()` wrote the stale comma-format `activeDaysOfWeek = "0,1,2,3,4,5,6"`, which the mask parser (`dayActiveOn`) reads as inactive on every day except Wednesday — the seeded rule decided `Allow` and overlays never showed on first run (device-caught; fixed to `"1111111"`, matching the production RuleEditor; rebuilt + `install -r` on X6532C). X6532C is still Transsion — the Samsung OneUI / Xiaomi MIUI matrix rows remain open.

**Latency-suite measurement notes (2026-08-28):** the suite initially failed on stale data — `LatencyProfiler.percentilesMillis()` reads a shared 500-entry rolling buffer that mixes suites when the accessibility service re-binds mid-run (XOS Hiber disconnects the service between suites; service-scoped state machine + controller are recreated, the app-scoped profiler buffer survives). Fixed: `LatencyProfiler.reset()` called at suite start (unit-tested). Also added: a submission-missing diagnostic (`recordSubmission` logs when a generation has no decision entry) and a `show()` gate diagnostic (which gate no-ops). Neither fired in the corrected run — the earlier "one submission across many decides" anomaly was the buffer/rebind contamination, not a missing-entry bug. The addView (XOS) presentation stage measures first-pre-draw after `addView`; the Dialog (stock) stage measures `FrameMetrics.TOTAL_DURATION` — two different definitions, noted for the matrix devices.

---

## 13. Deployment Requirements

- Distributed as the sideload/distributable-APK build only; not submitted to Google Play in this configuration.
- Sideload onboarding shows only the legitimate Restricted Settings flow (App Info → ⋮ → "Allow restricted settings").
- Database migration runs on a background thread during `onServiceConnected()`, never on the main thread.
- Rollback: if Enhanced Mode fails to initialize (DB open failure, cache load failure), the service disables overlay functionality gracefully and logs the failure. It never leaves a stuck overlay and never crash-loops the app.

---

## 14. Monitoring Requirements

- Log p50/p95/p99 for all three staged latencies (§8 Step 6), locally, on a rolling basis.
- Log `RulesSnapshot` state transitions (`UNINITIALIZED`/`LOADING`/`READY`/`DEGRADED`) and version numbers.
- Log every emergency-exclusion fail-open trigger, as a signal to review, not an error.
- Log health-check results (service enabled/disabled over time) to help diagnose OEM-driven permission resets.
- Log browser view-ID lookup failures per browser package/version, feeding the ongoing maintenance process in §16.
- No screen content, URLs, or personally identifying data leave the device as part of this monitoring; it is local-only diagnostic logging.

---

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| Emergency-surface blocking failure | Low (mitigated by design) | Fatal | Dual-role `RoleManager` check + fail-open on any ambiguity | Life-safety test, every release, on the full device matrix |
| OEM touch passthrough on non-focusable overlay | Medium | High | Per-OEM tapjacking test; `FLAG_NOT_FOCUSABLE` exception list | Tapjacking test suite, per OEM |
| OEM (XOS) permission reset disables service silently | Medium | High | Health check + live state-change listener + user alert | WorkManager periodic check + `AccessibilityStateChangeListener` |
| Browser update breaks URL-bar view ID | Medium | Medium (URL blocking only; app blocking unaffected) | Silent no-op + diagnostic log; allowlist maintained on an ongoing basis | Field logs of lookup failures |
| AAPM/Android 17.2+ auto-revokes accessibility grant | Confirmed, live | High | Explicit detection + unsupported-configuration messaging | `AdvancedProtectionManager` check |
| `ActivityLogStore` key-derivation latency on low-end hardware | Unknown until measured | Medium (log writes are off the hot path, so this does not affect blocking latency) | Singleton connection, keying only in `onServiceConnected()` | Startup latency measurement in QA |
| Multi-window overlay mismatch | Low (mitigated by design) | Medium | Per-window, per-display decision logic and dedicated overlay instances | Multi-window test |
| DB migration data loss | Low (mitigated by design) | High | Row-count verification before old file deletion; retry on next launch | DB migration test |

---

## 16. Open Questions

- Exact XOS/Infinix cgroup-freezer timing behavior across Infinix/TECNO/itel models and XOS versions — no vendor documentation exists; must be measured empirically during QA.
- Real p50/p95/p99 detection and render latency figures on actual target hardware — not knowable before the §12 test suite runs against physical devices.
- `ActivityLogStore` key-derivation timing on `sqlcipher-android:4.18.0` on low-end target hardware — not yet measured.
- Long-term maintenance cadence for the browser view-ID allowlist as browsers update or migrate to Compose-based UI with no stable ID exposure — needs an ongoing process owner, not a one-time fix.
- Design of the accountability-partner cryptographic-approval protocol for Strict Mode override (key generation, registration, revocation, replay prevention, offline approval, lost/compromised partner device recovery) — tracked as a separate spec; this feature ships without it, behind a flag.
- Whether AAPM behavior changes in future Android releases beyond 17.2 — re-verify close to each dependency/target-SDK bump.
- Whether a future "Device Owner/MDM"-backed enforcement tier is ever built for users who want stronger guarantees than in-app friction can offer — explicitly out of scope for this version, not decided against permanently.

---

## 17. Acceptance Criteria

- Overlay appears within the measured p99 detection-to-overlay latency budget on target hardware, with the actual measured number recorded against the `<1000ms` target.
- Frame-presentation p99, measured via `FrameMetrics` on the overlay `Dialog`'s `Window`, recorded against the `<100ms` target.
- Background CPU usage measured and recorded against the `<2%` target during idle monitoring on target hardware.
- Zero tapjacking on every device in the test matrix, or a documented, narrowly-scoped `FLAG_NOT_FOCUSABLE` exception per OEM/version where needed.
- Zero emergency-call/keyguard blocking across every scenario in §12's life-safety test, on every device in the matrix.
- No Settings interception, no `GLOBAL_ACTION_HOME` usage, no SAI-style spoofing, no AAPM-bypass instructions exist anywhere in the shipped code or UI copy.
- Strict Mode correctly gates in-app disabling and never interferes with native OS uninstall/disable.
- Multi-window test passes: only the correct pane on the correct `displayId` is overlaid.
- AAPM-active and Restricted-Settings states are detected and shown as their respective informational screens, never worked around.
- DB migration completes with verified row-count parity before the old database file is removed.

---

## 18. Definition of Done

- All items in §17 verified and recorded with actual measured numbers, not targets restated as results.
- Full test suite in §12 passing on at least one physical Infinix/XOS device and two additional non-Infinix reference devices.
- Security review confirms: no `SYSTEM_ALERT_WINDOW` permission, no prohibited Accessibility API usage per current Google Play policy language, no anti-removal/bypass tooling present, `isAccessibilityTool` correctly omitted.
- Code review confirms zero hot-path disk I/O, zero hot-path regex compilation, and a measured (not assumed) low hot-path allocation rate.
- Monitoring (§14) is live and producing local latency/CPU/state-transition data.
- Rollback path (§13) verified: simulated DB-open failure does not crash the app or leave a stuck overlay.
- DB migration script verified against a real pre-migration database snapshot with row-count parity confirmed.
