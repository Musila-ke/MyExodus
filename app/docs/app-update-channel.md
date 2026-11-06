# App Update Channel (ADR #171, implemented 2026-11-06)

The app updates itself from GitHub Releases. GitHub is transport only; trust comes from a
manifest signed with a key pinned inside the APK, plus the APK's own signing certificate.
Research and rejected alternatives: `app/docs/updater-research-2026-11-06.md`.

## 1. Channel layout

Every release carries exactly two assets:

| Asset | Name | Purpose |
|---|---|---|
| APK | `myexodus-<versionName>.apk` (any name matching `[A-Za-z0-9][A-Za-z0-9._-]*.apk`) | the installable |
| Manifest envelope | `update-manifest.json` | signed description of that APK |

The app fetches `https://github.com/Musila-ke/MyExodus/releases/latest/download/update-manifest.json`.
No GitHub API call is made, so the unauthenticated 60 requests/hour/IP quota does not apply.
GitHub's "latest" is the newest published release that is neither a draft nor a prerelease:
a prerelease is never offered, and a release marked latest by hand overrides publish order.

## 2. Trust chain

```
update-manifest.json
  └─ signature (ECDSA P-256 / SHA-256, DER, base64)        verified against UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64
  └─ payload (base64 JSON)
       ├─ schema == 1, versionCode > 0, versionName bounded and printable
       ├─ apkAsset matches a single flat filename (no separators or "..")
       ├─ apkSha256 = 64 hex chars, apkSizeBytes within 1..128 MiB
       └─ minSupportedVersionCode in 0..versionCode, notes <= 4000 printable chars
                    ↓ download
staged APK
  ├─ manifest.versionCode > installed versionCode           else DOWNGRADE
  ├─ size and SHA-256 equal the manifest                   else INTEGRITY
  ├─ parses as an APK (getPackageArchiveInfo)              else INVALID_APK
  ├─ package name == com.example.myexodus                  else WRONG_PACKAGE
  ├─ archive versionCode == manifest.versionCode           else VERSION_MISMATCH
  └─ signer certificate set == installed app's set         else SIGNER_MISMATCH
                    ↓
PackageInstaller MODE_FULL_INSTALL session → user confirms the system installer
```

Anything that fails a check is deleted or discarded; a failure is never offered as an install.
`minSupportedVersionCode` is validated and stored but does not yet force updates.

## 3. Where the code lives

| File | Responsibility |
|---|---|
| `update/UpdateConfig.kt` | channel URL, pinned public key, size caps, cadence |
| `update/UpdateCrypto.kt` | SHA-256 helpers, SPKI public-key decode |
| `update/UpdateManifest.kt` | envelope verification + strict payload validation |
| `update/UpdateChecker.kt` | manifest fetch (15 s connect / 30 s read), result model, failure enum |
| `update/ApkDownloader.kt` | staged download `.part` → `<versionCode>.apk`, digest+size check, 7-day sweep |
| `update/ApkVerifier.kt` | full APK identity/signature verification before install |
| `update/UpdateInstaller.kt` | `canRequestPackageInstalls` gate, unknown-sources settings intent, session commit |
| `update/UpdateInstallReceiver.kt` | installer status → stored state; launches the user-action prompt |
| `update/UpdateStateStore.kt` | prefs: last check, stored envelope, failure, install status, last seen version |
| `update/UpdateCheckWorker.kt` | 24 h periodic check (CONNECTED), only when a key is configured |
| `ui/settings/UpdateViewModel.kt` | state machine, download/install/retry, post-upgrade notice |
| `ui/settings/UpdateSettingsSection.kt` | Settings row + dialog + notice |

The check worker is enqueued in `MyExodusApp` after unlock (the rules store is
credential-encrypted and unreachable before first unlock). Settings also re-reads install
permission and installer status on every resume.

## 4. State machine (Settings row → dialog)

| Phase | Row subtitle | Dialog action |
|---|---|---|
| `IDLE` | Check for a new version | Check now |
| `NOT_CONFIGURED` | This build has no update channel | none |
| `CHECKING` | Checking for updates… | none |
| `UP_TO_DATE` | Up to date, checked <when> | Check again |
| `NO_RELEASE` | No release published yet | Check again |
| `AVAILABLE` | Version <name> is ready to download | Download and install / Allow app installs |
| `DOWNLOADING` | Downloading: <n>% | none |
| `INSTALLING` | Waiting for the system installer | none (no Close) |
| `FAILED` | Couldn't check for updates (or install wording) | Try again |

The row carries an INFO tone when an update is available and ATTENTION when the last check
failed. One-time post-upgrade notice: when the stored manifest's versionCode equals the newly
installed one, `recordLaunch` reports an upgrade and the release notes are shown once.

Install reconciliation on launch is authoritative, because the installer status broadcast is
best-effort: PENDING younger than 30 min → INSTALLING; older → FAILED/INSTALL; SUCCEEDED →
cleared; ABORTED → back to AVAILABLE; BLOCKED → PERMISSION; STORAGE → STORAGE; other → INSTALL.

## 5. Failure mapping (user-visible copy)

| Failure | Copy intent |
|---|---|
| `OFFLINE` | GitHub unreachable: check connection, retry |
| `SERVER` | Unexpected HTTP status (not 404): retry later |
| `NO_RELEASE` | Nothing published yet |
| `INVALID_MANIFEST` | Failed the security check, nothing installed |
| `DOWNLOAD` | Transfer did not finish |
| `INTEGRITY` | Hash/size mismatch, file deleted |
| `INVALID_APK` | Not a parseable APK, file deleted |
| `WRONG_PACKAGE` | Different package, file deleted |
| `VERSION_MISMATCH` / `DOWNGRADE` | Version disagreement, file deleted / refused |
| `SIGNER_MISMATCH` | Different signing key, file deleted |
| `PERMISSION` | Android blocks installs: open "install unknown apps" |
| `STORAGE` | Not enough free space (16 MiB headroom is required on top of the APK) |
| `INSTALL` | Installer failed or timed out: retry or download manually |

Every message states what happened, why, and what to do. Raw exceptions and installer status
codes never reach the UI.

## 6. Key custody

- Channel manifest key: `~/.myexodus-update-key/update-manifest-key.pem` (0600, dir 0700).
  Public half pinned in `UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64`. **Back the private key up.**
  Loss means every installed build stays on its current version forever, because the pinned
  public key cannot be rotated remotely. Rotation requires shipping a new APK signed with the
  old key first, pinning both keys, then trusting the new one. Never commit the private key.
- Release APK signing key: **does not exist yet (owner decision).** Every already-installed
  debug-signed build cannot be updated in place by a release-signed APK (signature mismatch is
  enforced), so switching keys requires an uninstall and, because `allowBackup=false`, loses
  app data. Decide custody before handing tester APKs out.

## 7. Release runbook

1. Build the release APK with the permanent release key (once it exists) and a versionCode
   strictly greater than the installed one.
2. Generate the envelope:
   ```
   scripts/update-manifest-sign.sh <apk> <versionCode> <versionName> [notes-file] [minSupportedCode]
   ```
   Writes `update-manifest.json` beside the APK.
3. Publish a GitHub release tagged `v<versionName>` (do **not** tick "pre-release" — the app
   reads `/releases/latest/`), uploading the APK under its exact filename plus
   `update-manifest.json`. Asset names must not be renamed after signing.
4. Verify the live channel:
   ```
   curl -fsSL https://github.com/Musila-ke/MyExodus/releases/latest/download/update-manifest.json
   ```
   The response must be the envelope JSON, not a 404. A 404 surfaces in-app as NO_RELEASE.
5. On a device with an older build: Settings → Updates → Check now → Download and install →
   grant "install unknown apps" when prompted → confirm the system installer. Confirm the
   post-upgrade notice shows the release notes once.

Rotating the channel key (rare, requires a key-bridge release): see §6.

## 8. Verification evidence (2026-11-06)

- `./gradlew :app:compileDebugKotlin` — clean.
- `./gradlew :app:testDebugUnitTest` (full suite) — 2446 tests, 0 failures / 0 errors /
  0 skipped. `:app:lintDebug` — clean (exit 0, ADR #208: lint run alone). Gitleaks on the
  worktree — no leaks (the channel private key is outside the repo).
- `./gradlew :app:testDebugUnitTest --tests "com.example.myexodus.update.*"` — 30 tests,
  0 failures: `UpdateManifestTest` (valid field parse, blank key, tampered-after-signing,
  foreign key, malformed envelope, schema, path-escape asset names, digest shape/uppercase,
  size bounds, version/minSupported consistency, control characters in versionName/notes),
  `UpdateCheckerTest` (loopback HTTP: available, up-to-date, 404, 500, unverified manifest,
  blank key, unreachable host), `UpdateStateStoreTest` (launch/upgrade bookkeeping, envelope
  retention and clearing, install status round-trip), `UpdateInstallFlowTest` (mutable status
  PendingIntent from API 31, no flag before, full installer-status mapping).
- Cross-implementation check: a dummy APK manifest signed by `scripts/update-manifest-sign.sh`
  with the real channel key verified (`Signature SHA256withECDSA`) under the pinned public key
  in a plain JVM.
- Live channel: the public manifest URL returns HTTP 404 today, so the app correctly shows
  NO_RELEASE until the first release is published.
- **Device (X6532, physical, 2026-11-06 22:19–22:20 local; debug build `install -r`):** after
  launch the enqueued worker's 404 check landed, Settings showed the Updates row with
  "No release published yet"; tapping it opened the dialog with the NO_RELEASE copy and
  Close / Check again; Check again re-ran the live request and returned to the same state with
  nothing installed. Screenshots captured; rotation guard run afterward
  (`accelerometer_rotation=0`).
- **BLOCKED:** end-to-end install on hardware. No release APK keystore exists, so there is no
  releasable APK, and a debug-signed APK cannot exercise the signer-equality leg against a
  release-signed install. The permission screen, AVAILABLE/DOWNLOADING/INSTALLING states and
  the offline failure path remain device-untested until the first real release exists (the
  failure mapping itself is unit-tested).

## 9. Deliberate deviation from the earlier direction

The owner's 2026-11-06 direction mentioned a background download. Shipped instead: the daily
job fetches and verifies only the manifest; the APK downloads after the user taps Download and
install. Reasons (research §10 R4): a forced background download burns metered data on a
version the user may not want yet, and Android's installer still needs a foreground user
confirmation, so background work cannot finish an install anyway. If the owner wants
background staging later, `ApkDownloader` is already callable from the worker; only the
worker's scope and a storage-notification policy would change.
