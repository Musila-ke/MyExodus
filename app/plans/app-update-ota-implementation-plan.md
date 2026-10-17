# App Update (OTA) — Implementation Specification

**Status:** Draft for approval — **direction revised 2026-10-17 (ADR #171, owner ruling): the update source is GitHub Releases for both development and production, and Firebase Remote Config is removed entirely.** This revision supersedes the Firebase-specific parts of §0/§1/§3/§4/§8 below (Firestore `releases/latest` manifest, Firebase Storage APK bucket, `storage.rules`): read them as the requirements/verification contract, not the transport. What survives unchanged: the signed-manifest trust anchor, SHA-256 + signing-certificate verification before `PackageInstaller`, the Settings/Home surfaces, the "install unknown apps" grant row, and every UR/UN requirement. The replacement transport design (release asset layout, manifest signing, dev vs production channel split, rate-limit handling) is explicitly deferred to the implementation session — see ADR #171 "Open decisions".
**Target Audience:** Implementation engineer / coding agent
**Stack:** Kotlin, Jetpack Compose, Hilt, Firebase (Firestore, Cloud Storage), WorkManager
**minSdk:** 26 (Android 8.0)
**Depends on:** Firebase project (Blaze, already live), Firestore rules pipeline (`firestore.rules` + emulator suite), Settings-screen row pattern (`settings_*` rows with Fix actions), `ProtectionSnapshotWriter`-style background refresh precedent

---

## 0. Why this exists

MyExodus ships as a directly-installed APK — there is no Play Store channel (ADR #15), so there is also no Play update mechanism. Without a planned rail, every release requires the user to manually fetch and sideload a new APK. This plan adds an in-app update check with a verified download and user-confirmed install: the Android sideload equivalent of an OTA prompt.

**Rejected alternative — Firebase App Distribution in-app alerts:** Firebase's own documentation positions the full `firebase-appdistribution` SDK for pre-release/test variants and directs shipping apps to keep it out of release builds. It also requires every device to sign in with a Google account and be registered as a tester — wrong friction for a partner's phone and a privacy mismatch for an accountability tool. It stays available as the internal dev/testing distribution channel, not the user rail.

---

## 1. Feature Overview

Builds:
- A **release manifest** in Firestore (`releases/latest`): versionCode, versionName, APK URL, SHA-256, size, notes, minimum-supported versionCode.
- An **APK hosting bucket path** in Firebase Storage (`releases/myexodus-<versionCode>.apk`), publicly readable, write-denied to all clients.
- A **client update checker**: fetches the manifest on foreground (throttled), compares against the installed versionCode, and surfaces an update card in Settings + a dismissible Home banner when an update is available.
- A **verified installer flow**: download APK → verify SHA-256 against the manifest → verify the APK's signing certificate equals the installed app's signing certificate → hand off to the system installer via `PackageInstaller` session (or `ACTION_VIEW` intent with a `FileProvider` URI). The install confirmation is always the user's explicit tap in the system dialog.
- An **"Install unknown apps" grant row** in Settings (same pattern as the overlay/usage-permission rows) for `REQUEST_INSTALL_PACKAGES` — the app cannot prompt for its own install without it.

Explicitly not built: silent/background installation (not possible without device-owner privileges), delta/patch updates (full APK is small), Play in-app-update APIs (no Play).

---

## 2. Requirements

### Functional
- UR1: The app checks `releases/latest` at most once per 24 h, on app foreground (piggybacked on the existing resume path, plus a WorkManager daily job for devices the user rarely opens — best-effort, mirroring the partner-sync cadence honesty).
- UR2: When `manifest.versionCode > installedVersionCode`, Settings shows an "Update available" row (version + notes) and Home shows a dismissible banner. Dismissal persists per versionCode.
- UR3: Tapping the update downloads the APK to app-private cache, verifies SHA-256 and the signing certificate, then opens the system install prompt. Progress is shown inline (button spinner + percent for >10 s per the UX timing standards).
- UR4: When `installedVersionCode < manifest.minimumSupportedVersionCode`, the update is presented as **required** (non-dismissible banner, Settings row pinned, copy explains why in plain language). This is the rail for security-fix releases; it never blocks app *function* — blocking enforcement is out of scope (see AD-U4).
- UR5: After a successful install, the app detects its own version change (`MY_PACKAGE_REPLACED` or versionCode compare on next launch) and clears any dismissed/available state for the superseded version.
- UR6: The Settings row for "Install unknown apps" deep-links to `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this package, mirroring existing permission rows.

### Non-Functional
- UN1: **The signing certificate is the trust anchor.** An APK whose signing certificate does not exactly match the installed app's certificate is deleted and never handed to the installer. (Android enforces same-signer for updates anyway; refusing earlier gives a clear error instead of an opaque system failure.)
- UN2: Manifest fetch and APK download go over HTTPS only; the APK is verified against the manifest's SHA-256 before use.
- UN3: No new data leaves the device for this feature beyond the manifest `GET`; no device identifier is sent (no query params, no auth needed — the manifest and APK are public artifacts).
- UN4: The downloaded APK lives only in `cacheDir` and is deleted after install hand-off, on failure, or after 7 days.
- UN5: The feature must be fully inert offline: all failures degrade to "couldn't check for updates", never to a broken app shell.
- UN6: Storage and bandwidth stay inside Blaze free tiers for the user base size; no new budget risk beyond the existing NFR4 alert.

---

## 3. Existing System Context

**Reused as-is:**
- The Firebase project + `google-services.json` already configured (Firestore, anonymous auth, App Check installer) — the manifest read uses the existing Firestore instance; unauthenticated reads are allowed by rules (public artifact).
- The Settings screen's permission-row pattern (`settings_overlay_*` rows + Fix/Open actions) for the unknown-sources grant.
- The resume-path re-check pattern in `MainActivity` (permission states are re-read on every `ON_RESUME`).
- The `desert_dawn_widget_info` receiver precedent (`MY_PACKAGE_REPLACED`) for post-update detection.
- The Firestore emulator rules-test suite for the new `releases` collection rule.

**New:**
- `storage.rules` for the APK bucket (read: true; write: false) deployed alongside `firestore.rules`.
- Manifest document shape (Section 4), a release-publish script/checklist (Section 8 Step 1).
- Client package `update/` (checker, downloader, verifier, installer) + a Settings/Home surface.

---

## 4. Data Model & Decisions

### Firestore: `releases/latest`
```
releases/latest = {
  versionCode: 12,                  // monotonic, matches app build.gradle versionCode
  versionName: "0.9.2",
  apkUrl: "https://firebasestorage.googleapis.com/.../myexodus-12.apk",  // or storage path
  sha256: "hex…",                   // of the exact uploaded bytes
  sizeBytes: 12345678,
  notes: "What changed, in the user's words",   // shown in the update card
  minimumSupportedVersionCode: 10,  // below this, the update is 'required'
  publishedAt: <serverTimestamp>
}
```
Rules: `match /releases/{doc} { allow read: if true; allow write: if false; }` (publishing is a console/CLI admin action; clients can never write). A history of past releases is optional — YAGNI for now, `latest` alone is the contract.

### Decisions
- **AD-U1 — Manifest in Firestore, APK in Storage.** Firestore is already deployed with a rules pipeline and emulator test suite; reusing it costs one rule block. The APK is a blob and belongs in Storage. Rejected: static JSON on Firebase Hosting (adds a third service for no benefit); App Distribution (Section 0).
- **AD-U2 — Signature pinning over certificate pinning of the transport.** The APK's signing certificate is checked against the *installed* app's certificate (`GET_SIGNING_CERTIFICATES`); this survives keystore compromise on the hosting side, which HTTPS pinning of a CDN would not. SHA-256 from the manifest protects integrity against corruption, not against a malicious manifest — the signature check is what makes a malicious manifest harmless.
- **AD-U3 — `PackageInstaller` session with user confirmation.** The system installer dialog is mandatory and is the final user consent. No `INSTALL_PACKAGES` privilege, no device-owner paths (a consumer accountability app must not behave like an MDM).
- **AD-U4 — "Required" means loudly-prompted, never enforced.** The restricted device's user is exactly the party with an incentive to avoid updates. Enforcement still must not depend on an update being installed, so a required update is presented persistently but never gates blocking functionality. (The alternative — gating features on version — would let an update prompt become a bypass vector; rejected.)
- **AD-U5 — Version monotonicity is enforced client-side.** A manifest versionCode lower than or equal to the installed versionCode is ignored (no downgrade path — a rollback pushed through this rail would be a way to reintroduce a fixed vulnerability).
- **AD-U6 — No release notes markdown/HTML.** Plain text only, rendered via Compose `Text`; nothing here parses fetched content as markup.

### Files (client)
```
app/src/main/java/com/example/myexodus/update/
  UpdateManifest.kt      — data class + fail-closed decoder (mirrors widget codec style)
  UpdateChecker.kt       — fetch + throttle + compare (pure seams for JVM tests)
  ApkVerifier.kt         — sha256 + signing-certificate compare
  ApkDownloader.kt       — HTTP download to cacheDir with progress flow
  UpdateInstaller.kt     — PackageInstaller session hand-off
  UpdateStateStore.kt    — last-check timestamp, dismissed versionCode (Preferences DataStore)
  UpdateUi.kt            — Settings row + Home banner composables
```
Plus: `MainActivity` resume hook, Settings screen row, Home banner slot, `AndroidManifest.xml` (`REQUEST_INSTALL_PACKAGES`, FileProvider if the intent path is used), `res/xml/file_paths.xml`, `storage.rules`, strings.

---

## 5. Client Flows

### Check (UR1)
1. Foreground resume → `UpdateChecker.checkIfDue(now)` (24 h throttle via `UpdateStateStore.lastCheckAt`).
2. Fetch `releases/latest` (10 s timeout). On failure: record nothing, stay silent (UN5).
3. `versionCode > installed` → store `Available(manifest)` state; below `minimumSupportedVersionCode` → `Required(manifest)`.
4. UI observes this state (Settings row + Home banner).

### Download + verify + install (UR3)
1. User taps Update → `ApkDownloader` streams to `cacheDir/updates/myexodus-<versionCode>.apk` with a progress `Flow<Float>`.
2. Verify size → SHA-256 → parse APK (`PackageManager.getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)`) → package name matches → signing certificate digest equals the installed app's.
3. Any mismatch: delete the file, show the standard three-part error (what/why/action), log a telemetry event (`update_verify_failed`).
4. Hand the file to `PackageInstaller` session (`setActionIntentSender` with the system's confirmation; `ACTION_INSTALL_PACKAGE` as the fallback path on OEMs that reject sessions). If "unknown sources" is not granted, route to the Settings row instead of failing.
5. On `STATUS_PENDING_USER_ACTION`: fire the confirmation intent. On success: clear cached APK + available state. On failure: keep the state, surface the status code in plain language.

### Post-update (UR5)
`MY_PACKAGE_REPLACED` receiver (existing widget receiver pattern) → clear dismissed/available state and re-run the check; the widget's existing receiver already handles its own refresh.

---

## 6. Publishing a release (UR — developer side)

Section 8 Step 1 defines `scripts/publish-release.sh` (or a documented manual checklist):
1. `./gradlew :app:assembleRelease` (release signing key — **must be the same keystore forever**; losing it orphans every installed device).
2. `sha256sum` the APK; upload to `gs://<project>.appspot.com/releases/myexodus-<versionCode>.apk` (immutable cache headers).
3. `firebase deploy --only storage` on first setup; write `releases/latest` with the values above.
4. Verify from a device on the previous version.

This step is deliberately a script/checklist, not a Cloud Function — publishing is a low-frequency, human-gated action, and a function that accepts APK uploads would be a remote-code-delivery endpoint worth not existing (YAGNI + attack-surface reduction).

---

## 7. Error Handling

| Condition | Handling |
|---|---|
| Manifest fetch fails / offline | Silent; retry next foreground after throttle window. Never blocks anything (UN5). |
| Manifest malformed / unknown fields | Fail-closed decode → treat as "no update". Log `update_manifest_invalid`. |
| Manifest versionCode ≤ installed | Ignored (AD-U5). |
| Download interrupted | Resume discarded; restart on next tap. Partial file deleted. |
| SHA-256 mismatch | File deleted; "couldn't verify the update" error (no retry loop — a repeated mismatch means the release is broken; surface "try again later"). |
| Signing certificate mismatch | File deleted; `update_verify_failed` telemetry; user copy: "This update isn't from the same source as your app. It was not installed and has been deleted." |
| "Install unknown apps" not granted | Update card explains it; tap routes to the system settings screen (UR6). |
| PackageInstaller session rejected (OEM) | Fallback to `ACTION_VIEW` install intent; if that also fails, offer "download APK and install manually" with the file path. |
| Install user-cancelled | State retained; user can retry from the same card. |
| Storage object missing (raced publish) | 404 → treat as manifest/app mismatch → error with "try again later". |
| Device signature change (reinstalled with a new key) | Android refuses the update at the system level; the in-app error says the app was reinstalled from a different source. |

---

## 8. Security Requirements

- APK signature check is mandatory before hand-off (AD-U2) — implemented as an explicit code path with its own test, never "trusted because it came from our bucket".
- `releases` collection is read-only for all clients; `storage.rules` denies writes entirely. Publish = admin credentials only.
- No analytics/session data attached to manifest fetches (UN3): the request carries no uid, no device id; Firestore anonymous auth is not required for this read (rule `allow read: if true`).
- The download URL may be a tokenized Storage URL; treat it as public anyway (UN3 makes it harmless — the signature check is the gate).
- Cache APK is app-private (`cacheDir`), FIPS-irrelevant content, deleted per UN4.
- Telemetry (existing CrashReporting/Sentry wiring) logs only event names + versionCodes, never URLs or file contents.

---

## 9. Testing Requirements

**JVM (pure seams):**
- Manifest fail-closed decode: missing field, wrong type, negative versionCode, unknown field names.
- Version comparison: newer/equal/older; `minimumSupportedVersionCode` boundary (below/at/above).
- Throttle: 24 h boundary, clock-skew future timestamp (mirror `syncFreshness`'s skew rule — future reads as "checked", never re-spams).
- Dismissal persistence: dismissed versionCode suppresses banner but not the Settings row; a newer versionCode un-suppresses.

**Instrumented (device):**
- Verify step: good APK passes; a re-signed APK with the same package/version is rejected (the critical security test — build a deliberately mis-signed APK in the test fixture).
- Install happy path on the X6532: update card → download → system dialog → installed → version code changed → state cleared.
- Unknown-sources row deep-link opens the right system screen.

**Manual matrix:**
- Offline check → silent. Airplane-mode mid-download → clean failure. Required-update banner on an old build. Post-update relaunch shows no stale banner.

---

## 10. Open Questions (owner decisions)

1. **Release signing key custody** — the whole rail depends on one keystore existing for the app's lifetime; where it is backed up (and whether the release build is signed locally or in CI) is a decision the project owner must make before Step 1 of publishing.
2. **Who publishes** — if the plan is a solo maintainer flow, the script/checklist above is enough; if a second person will publish, the publish step should move behind admin-only credentials in the Firebase console (documented, still manual).
3. **Cadence** — 24 h is a default; a shorter window costs nothing meaningful (a single small document read) but is not needed for this user base. Confirmed at implementation.
