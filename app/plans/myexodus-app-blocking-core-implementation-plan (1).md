# MyExodus V1 — App Blocking Core: Implementation Specification
Status: Approved for implementation (Revision 2 — incorporates corrections from a second independent audit round). All decisions below are final unless listed in §16.
---
## 1. Feature Overview
MyExodus is an Android app that helps adult users (18+) reduce compulsive phone-based behaviors (social media, betting, adult content) through a friction-plus-accountability model rather than absolute technical enforcement. V1 ships two blocking capabilities:
- **App Blocking**: when the user opens an app they've listed as restricted, MyExodus detects this and interposes a friction/recovery screen before the underlying app can be used.
- **Website/Domain Blocking**: a local VPN blackholes DNS resolution for a user-configured domain list, for traffic that resolves through the path the VPN controls.
Both mechanisms are **best-effort deterrents**, not unbypassable locks. The product's actual safety net is friction (a timed delay before override) plus an accountability partner who is notified of block events and can see read-only statistics. V1 explicitly does not attempt device-owner-grade enforcement, kernel-level packet inspection, or resistance to a user who deliberately works around the app at the OS level (Safe Mode, uninstall, Task Manager force-stop). These are documented, accepted limitations, not defects.
V1 ships as two build flavors from one codebase: a Play Store flavor and a separately distributed APK flavor. Both flavors use identical blocking mechanics in V1. No flavor uses AccessibilityService or Device Owner in V1.
---
## 2. Requirements
### Functional
- User can create, edit, delete rules specifying: target app package(s) or domain(s), active time windows (explicit timezone, day-of-week, start/end local time), and friction duration.
- When a restricted app is foregrounded during an active rule window, the user is shown a blocking screen within a bounded, measured latency (see §11) instead of being able to use the target app.
- The blocking screen offers: wait out the friction timer, or invoke an "escape hatch" override, which is always eventually reachable and never technically blocked (see §10).
- Restricted domains fail DNS resolution for traffic routed through the MyExodus VPN. This is explicitly scoped as **DNS-resolution-layer blocking**, not "blocks the website" in an absolute sense (see §10, §16, known bypass list below).
- An accountability partner can be paired via QR code and receives block-event notifications plus a read-only stats view, delivered with at-least-once, deduplicated delivery.
- Streak counts and block counts persist across app restarts, device reboots, and app updates without data loss, using an explicit durability and clock model (see §5, §9).
### Non-Functional
- Must remain installable and compliant on Google Play (Play flavor) under current sensitive-permissions, foreground-service, and VPN policy.
- Must run on a solo-developer budget of 9,000 KES and a 3–6 month timeline — this rules out a custom packet-inspection/MITM engine and a full userspace TCP/IP stack for V1.
- Must degrade gracefully (never crash, never silently disable enforcement without telling the user, never take down the device's general internet connectivity) when OS permissions are revoked, alarms are denied, the VPN is revoked, or the user stops the app via the system Task Manager.
- Partner data classified and handled per an explicit data inventory (see §10); Kenya hosting is a deliberate architectural choice for the categories that warrant it, not a claim that Kenyan law mandates it for all data.
### Explicit Non-Requirements (V1)
- No deterministic, unbypassable app blocking. Consumer Mode is the only mode; Device Owner / managed-device enforcement is not built.
- No AccessibilityService-based detection. Deferred to a future "Enhanced Mode" (V2+), gated on a fresh Play-policy review at that time.
- No deep packet inspection, no TLS interception, no custom Root CA, no full userspace packet router. Web blocking is DNS-resolution-layer only, with a documented, user-facing list of what it does not cover (Private DNS/DoH/DoT, hardcoded IPs, direct-IP connections, other VPNs active instead of MyExodus's).
- No resistance to Safe Mode, secondary-user-profile switching, app cloning, or the Android 13+ Task Manager "Stop" action. These are documented user-side bypasses (see §9, §15), not defects.
---
## 3. Existing System Context
- Native Kotlin, Android-first V1.
- Room database, encrypted via SQLCipher, offline-first. System of record for rules, streaks, block/attempt history, and recovery state.
- Firebase used as the relay layer for partner pairing and dashboard sync; a Nairobi VPS hosts the specific data categories identified in the data inventory (§10) as warranting local residency. This pairing/relay layer already exists as an architectural decision outside this spec; this document defines what the blocking core sends into it.
- Package name is currently `com.example.myexodus` (placeholder — must be changed to the final application ID before any release build).
- Minimum supported Android version: **API 26 (Android 8.0)**, chosen as the floor where `UsageStatsManager` and modern background-execution restrictions are both present and well-documented; devices below this are out of scope for V1. Target/compile SDK tracks the latest stable Android release at build time. This must be confirmed against actual analytics of the target user base before final commitment (see §16).
- Nothing in this spec touches the behavioral-content reference documents or unrelated app screens. Only the blocking engine, rule CRUD, recovery flow, and their direct data/security dependencies are in scope.
---
## 4. Architecture
```
                        MyExodus App
                              │
                 ┌────────────┴────────────┐
                 │      Policy Domain       │
                 │  (Rules, RecoveryPolicy, │
                 │   PolicyVersion — Room   │
                 │   is the sole authority) │
                 └────────────┬────────────┘
                              │  PolicySnapshot(version) — versioned, observed via Flow
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
 App-Block Engine      DNS-Block Engine      Partner Sync Engine
 (foreground service    (VpnService,           (transactional outbox
  + UsageStatsManager    full-tunnel + DNS      → Firebase relay →
  polling + BlockerActivity,                    Nairobi VPS for
  not a raw overlay)     protect(), Private     residency-classified
                          DNS detection)         data only)
        │                     │                     │
        └──────────┬──────────┴──────────┬──────────┘
                   ▼                     ▼
            Room (SQLCipher)      DataStore + Tink
            — rules, streaks,     AeadSerializer
              attempt log,        — pairing key,
              recovery state      escape code only
              (each engine caches
               its own version;
               Room is never
               bypassed as the
               source of truth)
```
Trust boundary: the **Policy Domain** (Room) is the single source of truth for what should be blocked and why. Every other representation — the App-Block Engine's in-memory rule set, the DNS-Block Engine's in-memory domain set, the Device-Protected-Storage boot snapshot — is an explicitly versioned **cache** of that authority, never a second authority. The UI (Compose/MVI) renders state; it never owns enforcement decisions.
---
## 5. Architecture Decisions
1. **No Device Owner / managed-device mode in V1.** Provisioning requires factory reset before an ordinary user can set it up — incompatible with normal Play install flow.
2. **No AccessibilityService in V1.** Avoids Play declaration/review risk in V1; revisit for V2 "Enhanced Mode" only after a dedicated policy review at that time. Note for that future review: Android's Advanced Protection Mode (opt-in, Android 16+/17) can block non-tool apps from the Accessibility API entirely when a user has that mode enabled — any V2 design must handle that state gracefully.
3. **UsageStatsManager polling + a dedicated `BlockerActivity` is the app-block mechanism — not a raw `TYPE_APPLICATION_OVERLAY`.** An overlay View does not participate in the back stack, cannot reliably intercept the Back button, is hidden by target apps that request `FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (common in games/banking/video apps), and struggles with IME focus. A dedicated Activity, launched with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP` and an overridden back-press handler, is used instead. A transient overlay may still be drawn for the first few frames purely to cover content while the Activity is launching, then immediately dismissed — it is a bridge, not the blocking UI itself.
4. **No push-based, real-time "foreground app changed" API exists for third-party apps on any current Android version.** `UsageStatsManager.registerAppUsageObserver()` fires only when a cumulative usage-time budget is exceeded, not on every foreground transition — it is not a substitute for polling. Detection remains poll-based (`UsageStatsManager.queryEvents()`, reading `ACTIVITY_RESUMED` events), and this is documented as a best-effort, non-real-time detection mechanism, not upgraded based on an unverified push API.
5. **DNS sinkhole via local VpnService is the sole web-block mechanism, implemented as a full-tunnel VPN with explicit non-DNS forwarding — not a DNS-only route.** `VpnService` routes by IP subnet, not by port. To intercept DNS at all, the VPN must capture `0.0.0.0/0` and `::/0`, and any traffic that is not itself a DNS query on port 53/853 must be forwarded to the real network via a `protect()`-ed upstream socket. See §8.4 for the full data-path specification. This is explicitly scoped as best-effort, plain-DNS-layer blocking; it does not intercept Android's system-level Private DNS (DNS-over-TLS on port 853, on by default as "Automatic" on many devices) — the app must detect when Private DNS is active and disclose the resulting blocking gap to the user rather than silently under-delivering.
6. **`AlarmManager.setExactAndAllowWhileIdle` is not used in V1.** It adds a special-access permission, battery cost, and Play-policy friction for a use case (periodic rule re-evaluation) that a running foreground service plus inexact wake-ups already covers. If a genuine gap is found during testing, its introduction requires a dedicated justification, not a default inclusion.
7. **WorkManager used only for ≥15-minute-interval housekeeping (partner sync, streak rollups), never for time-critical recovery or VPN-rebind loops.** WorkManager's periodic minimum interval is 15 minutes; anything faster silently clamps.
8. **Recovery countdown uses a dual-clock model: same-boot precision from `SystemClock.elapsedRealtime()`, plus a reboot-safe wall-clock deadline (`Instant`/epoch millis) as the durable, persisted value.** A bare persisted `elapsedRealtime` value is invalid after any reboot, because that clock resets to (approximately) zero at boot. The wall-clock deadline is what survives reboot; the elapsed-realtime value is recomputed fresh each boot session for sub-second precision and is never itself the persisted source of truth. See §8.3 for the full model, including clock-jump detection.
9. **MVI governs UI state transitions only.** The authoritative recovery deadline and rule state live in the Room-backed Policy Domain, not in ViewModel/reducer state. UI cannot be the source of truth for "is this app currently blocked."
10. **SQLCipher-encrypted Room remains system of record for all rule/streak/history data** (already decided outside this spec). No migration to DataStore for this data. Destructive migration fallback (`fallbackToDestructiveMigrationOnDowngrade`) is **not** enabled on the production database build variant — see §9 for why "warn before destructive migration" and "let Room silently run its own destructive fallback" are incompatible, and what replaces it.
11. **DataStore + Tink `AeadSerializer` (androidx.datastore:datastore-tink) used only for the small secrets set: pairing key material and escape/recovery codes.** Pin the exact alpha version (`1.3.0-alpha10` at time of writing) in the version catalog; isolate all access behind a `SecretsRepository` interface so the dependency is swappable without touching call sites. Define explicit Keystore-key-loss behavior (§9) rather than assuming the key always exists.
12. **`EncryptedSharedPreferences` and `androidx.security:security-crypto` (`EncryptedFile` included) are not used anywhere in this codebase.** That library is deprecated; Tink is Google's stated forward direction for this exact use case. `EncryptedFile` was considered as a lower-risk fallback and rejected for the same reason the original design moved away from `EncryptedSharedPreferences` in the first place — it lives in the same deprecated artifact.
13. **No app-level single-writer coroutine dispatcher for Room by default.** Rely on Room + SQLCipher's own transaction handling and WAL. Add `Dispatchers.IO.limitedParallelism(1)` only for a specific write path if profiling shows contention there. WAL is configured with `synchronous=NORMAL`; this is a conscious choice that trades a small power-loss durability window for write throughput — acceptable here because the worst case is losing the most recent in-flight write (e.g., one attempt-log row), not corrupting the database, and is not in tension with the "no data loss across updates/reboots" requirement, which concerns application-level migrations and reboot handling, not power-loss durability at the SQLite layer.
14. **Sensitive schedule data needed pre-unlock lives in Device-Protected Storage as a versioned cache (never a second authority); pairing keys and escape codes never do.** The DE snapshot stores only which package IDs/domains are currently subject to a rule and their active windows — data that does not need application-level encryption on top of Android's own device-level encryption of DE storage. It is read via a `LOCKED_BOOT_COMPLETED` receiver (not `BOOT_COMPLETED`, which fires only after first unlock) and is reconciled against Room's authoritative state as soon as credential-protected storage becomes available.
15. **The escape hatch is never technically blocked.** It may be time-gated (friction) and it always notifies the accountability partner, but the user can always eventually reach it, and uninstalling the app is always available as an ultimate fallback (with the consequence of data loss, clearly disclosed). This is both an ethical requirement and a direct Play policy requirement (Accessibility/Device Admin policies explicitly prohibit "prevent the ability for users to disable or uninstall any app or service") — treat it as a hard product-wide invariant regardless of mechanism used.
16. **Overlay/blocking-screen coverage does not and cannot guarantee the target app's process stops running, nor that it survives every target-app anti-overlay defense.** The spec accepts and documents this limitation (§9, §15) rather than mis-describing the mechanism as suspension.
17. **Package/app-inventory discovery for rule creation does not request `QUERY_ALL_PACKAGES`.** Rule creation offers launcher-visible apps (queried via a targeted, launcher-intent-scoped query) plus manual package-name entry as a fallback for apps not surfaced that way. Broad package visibility is a Play-restricted, sensitive capability this feature does not need.
---
## 6. Data Flow
**App-block event:**
```
Foreground service polls UsageEvents (short interval, foreground-service-backed)
   → reads ACTIVITY_RESUMED events (not the deprecated MOVE_TO_FOREGROUND constant)
   → matches package against active Rule from the current PolicySnapshot
   → if rule active: launch BlockerActivity (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP),
     with an optional transient overlay drawn first purely to cover content during the
     Activity's launch latency
   → log attempt event to Room (attempt log table) inside the same transaction that
     writes an outbox row for partner notification
   → outbox worker delivers the partner-notification event with an idempotency key;
     server deduplicates by event ID
```
**Recovery / escape hatch:**
```
Recovery starts
   → Room transaction writes: deadlineWallClockUtc, deadlineElapsedRealtimeThisBoot, ruleId
   → UI renders remaining = deadlineElapsedRealtimeThisBoot - SystemClock.elapsedRealtime()
     while within the same boot session
   → on any process/activity recreation within the same boot: recomputed identically
     (same-boot elapsed-realtime is authoritative for sub-second precision)
   → on app resume after a reboot: elapsedRealtimeThisBoot value is invalid (new boot),
     so remaining = deadlineWallClockUtc - Instant.now(), read from wall clock instead
   → if a wall-clock jump greater than a defined threshold is detected between reads
     (user changed the clock, or NTP correction), flag the recovery state as
     "time-anomaly detected" and freeze at the last known-good remaining value rather
     than silently shortening or lengthening the countdown
   → user requests escape
       → if before deadline (by whichever clock is authoritative for this boot session):
         friction UI stays
       → if user forces override: allowed always, event logged, partner notified
       → if deadline passed: escape hatch unlocks normally
```
**DNS block:**
```
VpnService.Builder configures:
   - addAddress() for the TUN interface
   - addRoute("0.0.0.0/0") and addRoute("::/0") — full tunnel, required to intercept
     DNS at all, per VpnService's subnet-based (not port-based) routing model
   - addDnsServer() pointing at the local sinkhole
   - protect() applied to the upstream sockets the app itself opens, so the VPN does
     not recursively capture its own outbound traffic
Packet loop reads from the TUN file descriptor:
   - UDP/TCP port 53 and TCP port 853 destined packets → parsed by the local
     resolver/sinkhole; blocked-domain queries answered locally (NXDOMAIN); all other
     queries forwarded to a real upstream resolver over the protected socket
   - all other traffic (everything not on 53/853) → forwarded byte-for-byte to the
     real network via a protected socket, so ordinary connectivity is preserved
   - if the device's Private DNS setting is "Automatic" or a specific hostname
     (DNS-over-TLS on port 853 to a resolver outside this control), that traffic still
     transits the tunnel as opaque TLS on port 853 unless separately intercepted;
     V1 detects this system setting via Settings.Global (PRIVATE_DNS_MODE) at VPN
     start and warns the user that web blocking is degraded while it is enabled,
     rather than silently under-delivering
onRevoke(): mark engine state = REVOKED, fire a persistent notification (see §9 for
   notification-permission caveats), and stop. Do not auto-attempt rebind — offer an
   explicit "Re-enable protection" user action instead.
```
**Rule edit:**
```
User edits rule in Compose UI (MVI)
   → intent → domain layer validates → Room transaction commits new rule + increments
     PolicyVersion
   → both engines observe PolicyVersion via Flow and reload their in-memory snapshot;
     a documented maximum staleness window applies between commit and both engines
     observing the new version
```
---
## 7. Implementation Sequence
1. **Policy Domain module** — Rule, RecoveryPolicy, PolicyVersion/PolicySnapshot models; Room schema (SQLCipher); repository layer; explicit schedule model (ZoneId, days-of-week, local start/end time, midnight-crossing and DST handling defined and unit-tested). Nothing else can be built correctly before this exists.
2. **Recovery state machine (MVI)** — dual-clock (elapsed-realtime + wall-clock) deadline model, escape-hatch flow, clock-jump detection, wired to Policy Domain, no engines yet. Fully unit-testable in isolation, including reboot-simulation and clock-change tests.
3. **VPN proof-of-concept (spike, before full App-Block Engine work)** — minimal `VpnService` implementation proving: full-tunnel routes established, a blocked domain returns NXDOMAIN, an allowed domain resolves normally, ordinary HTTPS browsing still works, IPv6 doesn't leak unblocked traffic, Private DNS state is detected, and stopping the VPN restores normal connectivity. Do not proceed to the full DNS-Block Engine build until this spike passes on at least two physical devices.
4. **App-Block Engine** — foreground service (`specialUse` type) + UsageStatsManager polling (`ACTIVITY_RESUMED`) + `BlockerActivity` + permission-state detection (usage access, overlay-if-used-as-bridge, notification) with graceful degradation UI for each; `ApplicationExitInfo.REASON_USER_REQUESTED` check on startup to detect a Task-Manager "Stop."
5. **DNS-Block Engine (full build)** — built on the validated spike from step 3; add domain-matching semantics (case normalization, subdomain policy, IDNA/punycode handling), engine health state machine (`STOPPED/STARTING/ACTIVE/DEGRADED/FAILED/REVOKED`), and `onRevoke` handling.
6. **Direct Boot storage split** — move only the minimal enforcement schedule into Device-Protected Storage as a versioned cache; register `LOCKED_BOOT_COMPLETED`; verify boot-time behavior before first user unlock, including reconciliation against Room once CE storage is available.
7. **Secrets storage (DataStore + Tink)** — pairing key and escape code storage behind `SecretsRepository`; explicit Keystore-key-loss detection and recovery path (force re-pairing/escape-code reset rather than crash or permanent lockout).
8. **Partner pairing protocol + transactional outbox** — one-time pairing token → authenticated exchange → device key registration → partner authorization → revocable long-term credential; outbox-pattern event delivery with idempotency keys, wired to the existing Firebase relay / Nairobi VPS pipeline for the data categories the inventory (§10) assigns there.
9. **Onboarding flow** — permission request sequencing (usage access → notification → VPN consent → battery-optimization exemption offered as optional, not required), each with an explicit fallback UI if denied or later revoked.
10. **Analytics/Stats (Phase 2 scope)** — streak and block counters derived from the attempt log, using an explicit timezone policy for "day" boundaries (§9); not blocking for Phase 1 release.
---
## 8. Detailed Implementation Steps
### 8.1 Foreground detection service and blocking screen
- Runs as a foreground service with type `specialUse`; declare and justify per Play's foreground service type requirements before submission — this declaration is reviewed during Play submission, budget time for it.
- Polls `UsageStatsManager.queryEvents()` at a fixed interval while the service is alive, reading `ACTIVITY_RESUMED` (not the deprecated `MOVE_TO_FOREGROUND` constant — same underlying value, but use the current, non-deprecated name). Document the exact interval chosen and its measured detection latency (see §11) — no zero-latency or push-based-detection claim is made anywhere.
- On detecting a target package's foreground event, launch `BlockerActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP`; override `onBackPressed()`/the predictive-back callback in that Activity so the Back gesture cannot dismiss it back into the target app.
- Do not rely solely on a `TYPE_APPLICATION_OVERLAY` as the blocking UI. A transient overlay may be drawn for the first frames purely to cover content while `BlockerActivity` is starting, then dismissed once the Activity is visible.
- Document explicitly, as an accepted V1 limitation: target apps that set `FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (common in games, banking, and video apps) can suppress a transient overlay; this does not defeat the `BlockerActivity` itself, since it is a full Activity, not a window overlay type subject to that flag.
- Overlay/Activity draw does **not** call any background-process-kill API in V1. Do not imply the target app's process is stopped — document that it may continue running, consuming battery/network, behind the blocking screen.
- On startup (including after a crash), do not attempt to query "is my overlay currently visible" — no reliable Android API exposes this. Instead, recompute from current state: is a rule active right now for the current foreground app, per Room/PolicySnapshot? If yes, (re-)launch `BlockerActivity`. Do not build a stale-overlay-detection mechanism that doesn't exist on the platform.
### 8.2 Permission and lifecycle detection
- Check usage-access and (if used) overlay-bridge grant state on every `onResume()` of `MainActivity` **and** independently from within the foreground service's own periodic check — do not rely on `onResume()` alone, since the user can revoke a permission while MyExodus is never brought back to the foreground.
- On every foreground-service startup, check `ActivityManager.getHistoricalProcessExitReasons()` for `ApplicationExitInfo.REASON_USER_REQUESTED` since the last known-good state. If found, the app was stopped via the Android 13+ Task Manager "Stop" action — treat this as a first-class enforcement-loss event: mark protection degraded, explain why in-app, and require an explicit user action to re-enable, rather than silently resuming as if nothing happened.
- On any permission loss, downgrade to a clearly-surfaced "protection degraded" state (persistent notification, subject to the notification-permission caveats in §9, plus an in-app banner) — not a silent failure.
### 8.3 Recovery / MVI state machine
```kotlin
data class RecoveryState(
    val deadlineWallClockUtcMillis: Long,
    val deadlineElapsedRealtimeThisBoot: Long,
    val ruleId: String
)
fun remainingSeconds(state: RecoveryState, sameBootSession: Boolean): Long = when {
    sameBootSession ->
        max(0L, (state.deadlineElapsedRealtimeThisBoot - SystemClock.elapsedRealtime()) / 1000)
    else ->
        max(0L, (state.deadlineWallClockUtcMillis - System.currentTimeMillis()) / 1000)
}
```
- Persist both `deadlineWallClockUtcMillis` and `deadlineElapsedRealtimeThisBoot` to Room on creation of the recovery state. Track a boot-session identifier (e.g., derived from a value that changes across reboots) to know which clock is authoritative on any given read.
- Detect large discontinuities in wall-clock time between reads (manual clock change, large NTP correction) and freeze the displayed countdown at its last known-good value rather than silently jumping — surface a "time change detected" state to the user instead.
- Do not decrement an in-memory Int each tick as the source of truth, in either clock mode.
### 8.4 DNS engine
- Full data-path and routing model as specified in §6 — full-tunnel (`0.0.0.0/0`, `::/0`), `protect()`-ed upstream sockets, explicit forwarding of all non-53/853 traffic, and explicit Private DNS detection/disclosure. This is a hard requirement, not an implementation detail left to the coding agent's discretion — an under-specified DNS-only route will break the device's general internet connectivity.
- Maintain an explicit engine health state: `STOPPED / STARTING / ACTIVE / DEGRADED / FAILED / REVOKED`, each carrying a structured reason code (e.g., `FAILED_TUN_SETUP`, `FAILED_DNS_BIND`, `FAILED_POLICY_LOAD`) for diagnostics. Do not conflate "VPN interface up" with "filtering functioning" — check both independently before reporting `ACTIVE`.
- Domain matching semantics must be explicit and tested: case-insensitive comparison, trailing-dot normalization, IDNA/punycode handling, and a documented policy on whether blocking `example.com` also blocks `www.example.com` and other subdomains (default: yes, suffix-match on registrable domain, documented to the user).
- On `onRevoke()`: transition to `REVOKED`, fire a persistent high-priority notification, and stop. Do not schedule an aggressive rebind loop; WorkManager cannot run faster than 15-minute intervals regardless, and repeatedly trying to reclaim a VPN slot the user (or another VPN app) took back is a hostile pattern, not a resilience feature. Offer the user an explicit "Re-enable protection" action instead.
- If the user has configured MyExodus as an Always-on VPN with "Block connections without VPN" enabled, and the VPN process crashes or fails to (re)bind, the device loses all connectivity until the user intervenes. This must be surfaced clearly in onboarding *before* the user enables that OS-level combination, with an explicit warning of this failure mode — it is not solvable purely in-app once the OS-level setting is on.
- Document to the user, plainly, what this engine does not cover: Private DNS/DoH/DoT traffic, hardcoded-IP connections that skip DNS entirely, and any traffic while a different VPN is active instead of MyExodus's.
### 8.5 Direct Boot storage
- Register a `LOCKED_BOOT_COMPLETED` receiver (not `BOOT_COMPLETED`, which fires only after the user's first unlock) to restore enforcement from the Device-Protected-Storage snapshot before first unlock.
- The DE snapshot holds only: which package IDs/domains are currently subject to a rule, and their active time windows — data that does not require application-level encryption beyond Android's own device-level encryption of DE storage (DE storage is not "plaintext on disk"; it is encrypted with a device-bound key that doesn't require user credential unlock, which is a different, weaker guarantee than credential-protected storage, not the absence of encryption).
- Pairing keys, escape codes, and full rule details (friction-duration reasoning, partner identity) stay exclusively in the default credential-protected, SQLCipher-encrypted Room database.
- Treat the DE snapshot as a versioned cache, not a second policy authority: store `policyVersion` and a checksum alongside the snapshot data, write it via a temp-file-plus-atomic-rename strategy so a crash mid-write cannot leave a corrupt/partial snapshot, and reconcile it against Room's authoritative state as the first action once CE storage becomes available after unlock.
### 8.6 Secrets storage
```kotlin
val aead = keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
val aeadSerializer = AeadSerializer(
    aead = aead,
    wrappedSerializer = PairingSecretsSerializer,
    associatedData = "myexodus_secrets.pb".encodeToByteArray()
)
```
- Pin `androidx.datastore:datastore-tink` to the exact version used in the version catalog (currently `1.3.0-alpha10`) — do not use a floating version range for this dependency.
- Wrap all reads/writes behind a `SecretsRepository` interface; no call site depends directly on the DataStore/Tink API surface, so the alpha dependency can be swapped without touching business logic.
- Explicitly handle Keystore key loss (e.g., after a factory-reset-adjacent event, or Keystore corruption on some OEM devices): if a decrypt operation fails with a key-not-found/invalid-key error while encrypted data is present, treat this as corrupted secrets state, not a crash — surface a "your pairing/escape code needs to be reset" recovery flow (re-pair with partner, generate a new escape code) rather than looping on decrypt failures or locking the user out permanently.
### 8.7 Sensitive-screen hardening
- On the PIN-entry, escape-code entry, and recovery-friction screens: set `filterTouchesWhenObscured(true)` on the root layout, and set `View.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)` (API 34+) on the sensitive input nodes. Correct XML attribute value is `"yes"`.
- Apply `FLAG_SECURE` on these same screens to block screenshots/screen-recording capture of PIN and escape-code entry.
- Do not call `Window.setHideOverlayWindows(true)` for the full duration these screens are visible. Its only officially documented guarantee is preventing non-system overlay windows from drawing on top of the app's window — do not additionally claim it specifically preserves or breaks clipboard/volume/permission-dialog behavior beyond what is officially documented; verify actual behavior on target OS versions during testing rather than asserting it either way in this document. If used at all, scope it to the exact seconds of PIN entry.
---
## 9. Error Handling
| Failure | Required behavior |
|---|---|
| Usage-access permission revoked | Foreground service detects on its own check cycle (not just `onResume`); surface persistent notification (subject to notification-permission caveat below); app-block degrades to "off" state, clearly labeled, not silently. |
| Overlay permission revoked (if a transient overlay bridge is used) | Same pattern as above; `BlockerActivity` itself does not depend on this permission and continues to function without the transient overlay bridge. |
| VPN revoked by user or another VPN app | Transition to `REVOKED` state, persistent notification, explicit user-initiated re-enable — no auto-rebind loop. |
| Always-on VPN + "Block connections without VPN" enabled, and MyExodus's VPN process crashes/fails to bind | Device loses all connectivity until user intervenes at the OS level; this is disclosed at the point the user enables that OS combination (§8.4), not discovered later as a surprise. |
| App stopped via Android 13+ Task Manager "Stop" action | Detected on next startup via `ApplicationExitInfo.REASON_USER_REQUESTED`; treated as a first-class enforcement-loss state, not a routine restart — explained in-app, requires explicit re-enable. |
| Notification permission (`POST_NOTIFICATIONS`) denied or channel disabled | The FGS may still be running, but "persistent notification" cannot be assumed visible in the drawer. Model this as a distinct state and show the degraded-protection indicator inside the app itself, not only via a notification that may not be shown. |
| Database corruption on app update | Room migrations are explicit per version; `fallbackToDestructiveMigrationOnDowngrade()` is **not** enabled on the production build, because Room performs a destructive fallback automatically inside `Room.databaseBuilder(...).build()`/first-open, before any application code has a chance to show a warning — those two requirements ("automatic fallback" and "warn before it runs") cannot both hold. Instead: no destructive fallback in production; on an unhandled migration path, detect it, quarantine the existing database file, attempt an export/backup if possible, and show an explicit in-app recovery/reset UI before any data is discarded. |
| Crash between OS-level enforcement activation and DB persistence of that fact | Do not attempt to query "is my overlay/Activity currently visible" — no such API exists. On next startup, simply recompute from current Room/PolicySnapshot state and current foreground app whether enforcement should be active right now, and act accordingly. |
| Reboot occurs mid rule-activation | `LOCKED_BOOT_COMPLETED` receiver reads the DE-storage schedule snapshot directly, before first unlock; does not wait for CE/Room availability to restore the minimal schedule; reconciles against Room once CE storage becomes available. |
| DE snapshot and Room diverge (e.g., Room commit succeeded, DE-snapshot write failed, then reboot) | DE snapshot carries its own `policyVersion` + checksum; on reconciliation after unlock, Room's version always wins and the DE snapshot is rewritten to match — the DE snapshot is a cache, never authoritative on its own. |
| Keystore key for pairing/escape secrets is lost or invalid while encrypted DataStore data still exists | Treated as corrupted secrets state; user is guided through an explicit re-pair/reset-escape-code flow rather than the app crashing or the user being permanently locked out. |
| Partner-notification delivery: crash after DB commit but before send, or send succeeds but response is lost and a retry duplicates it | Transactional outbox pattern: the block-event write and the outbox-event write happen in the same Room transaction; a separate delivery worker sends with an idempotency key; the server deduplicates by event ID. Neither a missed notification nor a duplicate is silently possible by design. |
| Native/engine version mismatch (future, if any native component is added) | Not applicable in V1 — no native/Go component is built for V1's DNS engine; pure Kotlin implementation only. Revisit only if a future scoping decision introduces one. |
---
## 10. Security Requirements
- The escape hatch must never be technically unreachable. It may impose a timed delay and a partner-notification side effect, but the user must always be able to eventually disable a rule or uninstall the app through normal Android mechanisms (with disclosed data-loss consequence). This is a direct Play Store Accessibility/Device Admin policy requirement and applies here as a hard product-wide invariant regardless of mechanism used.
- **Partner pairing protocol** (replaces the previously unspecified "pair via QR code"):
  1. MyExodus generates a single-use, time-limited pairing token and encodes it in the QR code.
  2. The partner's device exchanges that token for device-identity registration through the existing Firebase relay, establishing a long-term, rotatable credential — not the raw QR token itself — as the ongoing authorization.
  3. The pairing token is invalidated after first successful use or expiry, whichever comes first.
  4. Either party can revoke the pairing from within the app; revocation immediately invalidates the long-term credential server-side.
  5. Re-pairing after revocation or a lost device requires generating a fresh token; there is no "recover the old pairing" path, by design, since that would reintroduce the account-takeover risk this protocol exists to avoid.
- **Data classification for residency decisions** replaces the prior blanket claim. Before any cross-border or Nairobi-hosting decision is finalized, classify each data category actually collected (blocked package/domain list, block-attempt events, partner identity, pairing credential, escape-code hash) against: is it sensitive personal data under the Kenya DPA, what is the lawful basis for processing it, and does it fall under a category the Cabinet Secretary has specifically designated as requiring in-Kenya processing under DPA §50. Kenyan hosting for a given category is then a deliberate architecture choice for that category, not a claim that all app data is legally required to reside there. This classification exercise is a prerequisite deliverable, not optional documentation (see §16).
- Pairing keys and escape codes: Tink AEAD via DataStore, Android Keystore-backed, associated-data bound to prevent ciphertext-swapping, with the key-loss recovery path defined in §8.6/§9.
- Sensitive input screens (PIN, escape code, recovery friction): touch-filtering, `FLAG_SECURE`, and accessibility-data-sensitive marking as specified in §8.7. These reduce (not eliminate) accessibility-based scraping/tapjacking and screen-capture risk from other apps; they are not represented as a complete anti-malware boundary.
- No custom Root CA, no TLS interception, in V1.
- Package name `com.example.myexodb` must be replaced with the production application ID before any signed release build; release-blocking checklist item.
---
## 11. Performance Requirements
- App-block detection latency (time between target app's `ACTIVITY_RESUMED` event and `BlockerActivity` fully visible) must be measured on at least one low-tier and one mid-tier physical device (p50/p95/p99/worst-observed) before any latency figure appears in documentation or user-facing copy. No zero-latency or unmeasured-number claim is made anywhere.
- DNS resolution overhead added by the local sinkhole (full-tunnel path) must be benchmarked against unfiltered resolution time on the same devices, including confirming ordinary HTTPS browsing and IPv6 connectivity are unaffected, before claiming any specific overhead figure.
- Compose recomposition optimizations (deferred state reads) are applied **after** profiling shows the countdown UI causes measurable jank — not built in speculatively.
- Foreground-service polling interval is a tunable constant; document the chosen value and the measured battery-drain tradeoff once benchmarked on-device — do not carry forward an unmeasured interval as final.
---
## 12. Testing Requirements
- **Permission-state tests**: use `adb shell appops set` / `adb shell pm revoke` to programmatically grant and revoke usage-access and notification permissions during automated test runs; assert correct fallback UI renders for each.
- **Recovery state-machine tests**: (a) process-death and recreation mid-countdown reproduces the same `remainingSeconds` within the same boot session; (b) simulated reboot (elapsed-realtime reset) correctly switches to wall-clock-derived remaining time; (c) a simulated large clock jump triggers the time-anomaly freeze state rather than silently altering the countdown.
- **VPN engine tests**: full-tunnel routes established; blocked domain returns NXDOMAIN; allowed domain resolves normally; ordinary HTTPS browsing works while the VPN is active; IPv6 traffic doesn't leak around the block; stopping the VPN restores normal connectivity; Private DNS ("Automatic" and a specific hostname) is correctly detected and surfaced as a degraded-blocking warning rather than silently failing; a second VPN app taking over correctly triggers `onRevoke()` handling with no rebind-loop attempts inside a 15-minute observation window.
- **Overlay/Activity test**: confirm `BlockerActivity` launches correctly (not just a `TYPE_APPLICATION_OVERLAY`) against at least one app known to set `FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (e.g., a banking or game app), and confirm the Back button/gesture cannot dismiss it back into the target app.
- **Task Manager "Stop" test**: force-stop MyExodus's foreground-service process via the Android 13+ Task Manager UI or ADB equivalent; verify `ApplicationExitInfo.REASON_USER_REQUESTED` is detected on next launch and the degraded-state flow triggers correctly.
- **Reboot test**: force-reboot device with an active rule window; verify the DE-storage schedule snapshot restores enforcement via `LOCKED_BOOT_COMPLETED` before first unlock; verify no attempt is made to read CE-protected secrets during this window; verify reconciliation against Room occurs correctly once unlocked.
- **Migration test**: simulate an unhandled schema migration path and confirm the quarantine/export/recovery-UI flow triggers — confirm no destructive migration occurs invisibly before the warning is shown.
- **Keystore-loss test**: simulate Keystore key invalidation with existing encrypted secrets present; confirm the re-pair/reset-escape-code recovery flow triggers rather than a crash or lockout.
- **Outbox/idempotency test**: simulate a lost server response after a successful send and confirm the retry does not produce a duplicate partner notification (dedup by event ID).
- **Play policy self-check** (manual, pre-submission): confirm every manifest entry for `SYSTEM_ALERT_WINDOW` (if used as a bridge), `PACKAGE_USAGE_STATS`, the `specialUse` foreground service type, and VPN usage has a corresponding, currently-valid Play Console declaration — re-check against the live Play Console policy pages at submission time, not against this document.
---
## 13. Deployment Requirements
- Two build flavors (Play, APK) from one codebase; V1 blocking mechanics are identical across both.
- Replace `com.example.myexodus` application ID before first signed build of either flavor.
- Room schema migrations versioned and tested going forward from the first shipped schema version; destructive fallback disabled on production builds per §9/§10.
- `androidx.datastore:datastore-tink` version pinned explicitly in the version catalog; track upstream release notes for promotion out of alpha and re-evaluate this pin at that point.
- Minimum supported Android version (API 26, per §3) declared in `minSdk`; confirm against actual target-user-base analytics before this is treated as final (see §16).
- Rollback plan: if a shipped version regresses enforcement, the rollback path is a standard Play staged-rollout halt / APK re-distribution of the last known-good build — no server-side kill switch exists in V1 scope; document this as a manual operational step for the solo developer.
---
## 14. Monitoring Requirements
- Client-side attempt log (Room) plus the transactional outbox delivery-status field are the source of truth for "did a block event fire and was it delivered to the partner." No separate server-side monitoring pipeline is required for V1 beyond what already flows through the partner sync layer.
- Track, at minimum, per device: current permission-grant state for usage-access/notification/VPN, last-known DNS-engine health state (with structured failure reason codes per §8.4), and whether a Task-Manager-stop event was detected since the last known-good session. Surface degraded/failed states to the user directly inside the app; do not rely solely on notifications, which may not be visible (§9).
- No SLO claim (e.g., an uptime percentage) is made anywhere in-app or in marketing copy until real measured data exists from §11/§12 testing.
---
## 15. Risk Register
| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| Detection latency lets user briefly see blocked app content | High | Medium | Minimize polling interval within battery budget; documented as a known limitation in-app | Measured latency benchmark (§11) |
| Target app suppresses a transient overlay bridge via `FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` | Medium | Medium | `BlockerActivity` (a real Activity, not a window overlay type) is the actual blocking mechanism; the overlay is only a brief cover during Activity launch | Explicit test against a known app using this flag (§12) |
| Overlay/Activity covers UI but target app keeps running/consuming battery/network | High | Low-Medium | Documented limitation; no process-kill attempted in V1 | User-visible battery stats; not actively monitored by app |
| Private DNS (DoT) or DoH bypasses the DNS sinkhole entirely | High | High | Detected via `Settings.Global.PRIVATE_DNS_MODE`; user explicitly warned of degraded blocking rather than silent failure; user-facing copy scoped to "blocks plain-DNS-resolved domains," never "blocks all websites" | Automated Private DNS state test (§12) |
| VPN full-tunnel misconfiguration breaks general device connectivity | Medium (mitigated by spike-first sequencing) | Critical | Dedicated VPN proof-of-concept spike (§7 step 3) before full engine build; explicit forwarding of all non-53/853 traffic | VPN test suite (§12) |
| User enables Always-on VPN + "Block connections without VPN," then MyExodus's VPN crashes | Medium | Critical | Explicit warning disclosed before the user enables that OS combination; not solvable purely in-app once set | User-reported connectivity loss; no fully automatic detection possible from inside a crashed process |
| User stops MyExodus via Android 13+ Task Manager "Stop" | Medium-High | High | Detected via `ApplicationExitInfo.REASON_USER_REQUESTED` on next launch; explicit degraded-state flow | Startup check (§8.2) |
| Recovery timer computed incorrectly across reboot | Was Critical, now mitigated | High | Dual-clock model (§8.3); reboot and clock-jump tests (§12) | Automated reboot-simulation test |
| Partner notification lost or duplicated | Medium | Medium | Transactional outbox + idempotency key + server-side dedup (§9) | Outbox delivery-status tracking (§14) |
| Play Store rejects app over a permission/FGS-type declaration | Medium | Critical (blocks release) | Pre-submission Play-policy self-check (§12); no AccessibilityService/Device Admin/`QUERY_ALL_PACKAGES` in V1 avoids the largest of these risks | Manual review against live policy pages before each submission |
| Kenya DPA compliance built on an overstated legal premise | Was High, now mitigated | High | Data-classification exercise (§10) replaces the blanket residency claim; Nairobi hosting used deliberately per category, not assumed mandatory for everything | Legal/compliance review of the data inventory before partner-sync goes live |
| Keystore key loss locks user out of pairing/escape secrets permanently | Low-Medium | High | Explicit key-loss detection and re-pair/reset recovery flow (§8.6/§9) | Keystore-loss test (§12) |
| Solo-developer timeline/budget overrun from over-engineering (e.g., building DPI, a full packet router, or unnecessary exact-alarm/battery-exemption flows) | Medium | High | V1 scope explicitly excludes all of these (§2, §5); VPN spike-first sequencing (§7) catches the highest-risk unknown early | Sprint/milestone tracking against §7 sequence |
| `androidx.datastore:datastore-tink` alpha API introduces a breaking change before promotion to stable | Low-Medium | Medium | Pinned version + isolated behind `SecretsRepository` interface | Dependency update review at each version bump |
---
## 16. Open Questions
These are the only genuinely unresolved items. Everything else in this document is a closed decision.
1. **Minimum supported Android version (API 26 assumed in §3)** — needs confirmation against actual or projected target-user-base device analytics before being treated as final; chosen provisionally based on API availability, not user data.
2. **DE-storage confidentiality for the boot-time schedule snapshot (§8.5)** — is Android's device-level encryption of DE storage sufficient for this product's specific user base (compulsive behavior around adult content, betting), or does even "which packages are on a block list" warrant application-level encryption despite the added Direct Boot complexity that would reintroduce? This is a product/ethics call, not a technical one.
3. **Exact foreground-service polling interval** — no default is fixed yet; to be chosen after the on-device battery/latency benchmarking in §11, not guessed in advance.
4. **Data-classification exercise for Kenya DPA / Nairobi-hosting decisions (§10)** — the classification framework is specified here; the actual classification of each real data category (sensitive under the Act? which categories, if any, fall under a §50 designation?) is a compliance deliverable that must be completed, ideally with legal input, before partner-sync ships live data.
5. **When (if ever) to revisit AccessibilityService for a V2 "Enhanced Mode"** — contingent on a fresh, dedicated Play policy review at that future point, and on handling Android's Advanced Protection Mode opt-in restriction gracefully. Not a V1 blocker.
6. **Whether the current package placeholder (`com.example.myexodus`) has a decided final application ID yet** — needed before first signed build, not before development starts.
---
## 17. Acceptance Criteria
- A rule created for an app or domain results in `BlockerActivity` appearing when that app is opened, or the configured domain failing DNS resolution while the VPN is active and Private DNS is off, within the measured latency documented per §11.
- Revoking usage-access, notification, or VPN permission produces a visible, non-silent degraded-state indication within one service check cycle.
- Killing the app process mid-countdown and reopening it (without a reboot) reproduces the same remaining time (±1 second) via the same-boot elapsed-realtime path; simulating a reboot produces the correct remaining time via the wall-clock path instead.
- A device reboot during an active rule window restores enforcement from Device-Protected Storage before first user unlock, without touching credential-protected secrets, and reconciles correctly against Room once unlocked.
- Using the Android 13+ Task Manager "Stop" action against MyExodus is detected on next launch and produces an explicit degraded-state flow, not a silent resume.
- Enabling MyExodus's VPN, then activating Private DNS, produces a visible in-app warning that web blocking is degraded — not a silent gap.
- The escape hatch is reachable in every tested scenario, including after full permission revocation and after a simulated Keystore key loss — no state exists where the user is truly locked out.
- Pairing key and escape code are unrecoverable from a plaintext dump of Device-Protected Storage.
- No manifest permission ships without a corresponding, currently-valid Play Console declaration for the Play flavor.
- Stopping the local VPN at any point restores the device's normal, unfiltered internet connectivity, confirmed by the VPN test suite (§12).
## 18. Definition of Done
- All items in §7 Implementation Sequence complete and individually testable per §12, with the VPN proof-of-concept spike (step 3) passed on at least two physical devices before the full DNS engine build began.
- §11 performance measurements recorded with real device data (no placeholder/estimated figures remain in shipped documentation or in-app copy).
- §15 Risk Register reviewed with no Critical-impact item left without an implemented mitigation.
- §16 Open Questions 1, 4, and 6 resolved before release; 2 and 3 resolved before or during the relevant implementation step; 5 explicitly deferred, not silently dropped.
- Play-policy self-check (§12) performed against live Play Console policy pages within 7 days of submission.
- No AccessibilityService, Device Owner/DPC, packet-inspection/MITM, `QUERY_ALL_PACKAGES`, or custom-CA code exists anywhere in the V1 codebase — confirmed by code search as a release gate.
- No use of `AlarmManager.setExactAndAllowWhileIdle` or the `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` permissions anywhere in the V1 codebase, per §5 Decision 6.
