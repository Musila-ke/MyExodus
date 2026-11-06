# App Update Channel (plan v1.1, implemented 2026-11-06)

MyExodus updates itself from a dedicated public, releases-only GitHub repository. GitHub is
transport only: trust comes from a signed release policy pinned inside the APK, and Android's
package manager enforces signing continuity at install time.

Checks are automatic. Downloads and install initiation are user-driven. There is no Google Play
distribution (ADR #15), no GitHub REST call, no API token, no custom update server and no custom
foreground transfer service.

This document describes implementation plan v1.1
(`app/plans/direct-apk-self-updater-plan.md`). It supersedes the v1 updater shipped earlier on
2026-11-06 (ADR #171 revision that day). The v1 files `update/UpdateManifest.kt`,
`update/ApkDownloader.kt` and their tests are deleted, not retained.

## 1. Repositories and release types

The source repository stays private. It is never fetched by the app.

The update channel uses a separate public releases-only repository, supplied at build time:

```bash
./gradlew :app:assembleRelease -Pmyexodus.updateRepository=<owner>/<releases-repo>
```

`app/build.gradle.kts` writes that value into `BuildConfig.UPDATE_RELEASE_REPOSITORY`. With no
property the value is empty and the channel is fail-closed: `UpdateConfig.configured` is false,
no worker is enqueued, and Settings shows `NOT_CONFIGURED`.

Two release types exist in that repository:

| Release | Tag | Contents | Visibility |
|---|---|---|---|
| Artifact | `artifact-v<versionCode>` | the exact signed universal APK | immutable **prerelease** |
| Policy | `policy-<six-digit sequence>` | only `update-manifest.json` | immutable **latest** |

Artifact releases are prereleases so they can never become GitHub's `latest`. Each policy
release is explicitly designated latest. The app reads one URL:

```
https://github.com/<UPDATE_RELEASE_REPOSITORY>/releases/latest/download/update-manifest.json
```

That `latest` link is discovery only. APK downloads always use the immutable version-pinned URL
carried inside the signed policy.

**Live status, 2026-11-06:** `https://github.com/Musila-ke/myexodus-releases` and its manifest
path both return HTTP 404. Step 2 (distribution baseline) is owner-gated and has not been
provisioned, so no live channel exists yet.

## 2. Trust chain

```
update-manifest.json (max 32 KiB)
  envelope keys exactly: format, keyId, payloadBase64, signatureBase64
  format = myexodus-update-envelope-v1, keyId = primary-v1
  base64 payload (max 16 KiB) and base64 DER signature
       |
       v  SHA256withECDSA over the exact decoded payload bytes,
          against one pinned ECDSA P-256 SPKI public key
  signed policy payload
       common: schemaVersion=1, packageName=runtime package, channel=stable,
               positive policySequence, positive issuedAtEpochSeconds,
               mode OFFER|HALT, rolloutPercent 0..100, 32-lowercase-hex cohortSalt
       OFFER: release {versionCode, versionName, minSdk, targetSdk, apkUrl,
                       apkSizeBytes, apkSha256Hex, releaseNotes}
       HALT:  no release, rolloutPercent must be 0
       |
       v  monotonic policy and version floors, URL validation, cohort eligibility
  DownloadManager -> app-specific external files
       |
       v  exact byte count + SHA-256 over the platform stream
          archive package / versionCode / minSdk / targetSdk
       |
       v  fresh signed policy immediately before the first commit
          PackageInstaller MODE_FULL_INSTALL, hashed and fsynced session stream
       |
       v  Android package manager decides signing continuity
```

Envelope and payload key sets are exact. A missing or extra key fails verification. Base64 must
be canonical (padded, exact re-encode); malformed or non-canonical encoding is rejected before
signature use. A signature is ASN.1 DER, never raw `r || s`. Parsing, size, type and bound
failures are terminal for that response.

`apkUrl` validation (`UpdatePolicy.kt`):

- scheme `https`, authority exactly `github.com`, no user info, query, fragment or explicit port
- path exactly `/<repo>/releases/download/artifact-v<versionCode>/<safe-name>.apk`
- the tag version equals the signed `versionCode`
- one flat filename matching `[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.apk`; no `/`, `\`, `.` or `..`
- `/releases/latest/` can never authorize an APK

Release bounds: `apkSizeBytes` positive and at most `MAX_UPDATE_APK_BYTES` (250 MiB, one
compile-time constant shared by client and publisher), SHA-256 as 64 lowercase hex,
`versionName` 1 to 64 printable characters with no newlines, notes up to 4000 printable
characters where newlines are allowed, `minSdk` positive, `targetSdk` at least `minSdk`.

**Cohort.** The device generates one 128-bit `SecureRandom` installation ID on first use and
stores it in `update_state` prefs. It is never transmitted. Eligibility is
`SHA256(cohortId || UTF8(salt))`, first 8 bytes as an unsigned big-endian integer, modulo 10000,
eligible when the bucket is below `rolloutPercent * 100`. The same device and salt are stable; a
new salt can move it.

**Floors.** `UpdateStateStore` persists the highest accepted policy sequence with its payload
hash, and a highest trusted version code initialized from the installed version. A lower
sequence is `POLICY_ROLLBACK`; an equal sequence with a different payload hash is
`POLICY_CONFLICT`; an identical replay is accepted. The version floor never lowers, including
after a manual app downgrade or `ensureVersionFloor` with a lower installed version.

`issuedAtEpochSeconds` is display metadata. A wrong device clock does not reject an otherwise
valid policy. Seven days without a successful verified fetch projects `CHECK_STALE` instead of
claiming the app is current.

## 3. Where the code lives

| File | Responsibility |
|---|---|
| `update/UpdateConfig.kt` | build-time repo, pinned key, format/key id, caps, timeouts, cadence |
| `update/UpdatePolicy.kt` | envelope signature verification, strict payload validation, URL rules, cohort bucket |
| `update/UpdateCrypto.kt` | SHA-256 helpers, SPKI public-key decode |
| `update/UpdateChecker.kt` | bounded HTTPS fetch and `PolicyFetch` result |
| `update/UpdateStateStore.kt` | independent check and attempt records, floors, cohort ID, launch marker |
| `update/UpdateCoordinator.kt` | single main-process owner: check, download start, verify, install, cancel, reconciliation |
| `update/UpdateDownloadController.kt` | DownloadManager enqueue/query/open/cancel, owned-file sweep |
| `update/ApkVerifier.kt` | exact stream hash and archive identity checks |
| `update/UpdateInstaller.kt` | pre-commit gate, PackageInstaller session, dual hash, fsync, commit |
| `update/UpdateInstallReceiver.kt` | installer status mapping into the owned attempt |
| `update/UpdatePackageReplacedReceiver.kt` | post-replace reconciliation and protection marker |
| `update/UpdatePackageInfo.kt` | shared installed-versionCode read |
| `update/UpdateCheckWorker.kt` | 24 h periodic discovery and 24 h foreground join |
| `update/UpdateDiagnostics.kt` | bounded local diagnostics |
| `ui/settings/UpdateViewModel.kt` | phases, actions, truthful status projection |
| `ui/settings/UpdateSettingsSection.kt` | Settings row, update dialog, upgrade notice |

The app starts the channel only after unlock in `MyExodusApp` (the protection store is
credential-encrypted before first unlock): it enqueues periodic work and the 24 h foreground
check, then calls `UpdateCoordinator.onMainProcessStart()`. `MainActivity.onResume` also joins
`update_check_foreground` when 24 hours have elapsed.

## 4. Discovery

`UpdateCheckWorker`:

- unique periodic work `update_check`, 24 h repeat with 2 h flex, `CONNECTED`, `KEEP`
- unique one-time work `update_check_foreground`, `KEEP`, only when 24 h have elapsed
- retries only transient failures: `NETWORK_UNAVAILABLE`, `NETWORK_TIMEOUT`, `HOST_THROTTLED`,
  `HOST_UNAVAILABLE`
- the metadata fetch itself never transfers an APK and never installs

**Revision 2026-11-06 (owner-approved awareness and auto-download).** After an `UPDATED`
background check, `UpdateCoordinator.backgroundCheckFinished(foreground)` runs once:

- background checks (`foreground == false`) post the neutral "Update available" notification at
  most once per offered `versionCode`;
- when the standing `Download updates automatically` opt-in is on, and the device is connected,
  unmetered and not roaming, the same verified `startDownload` path starts the transfer; the
  pre-commit install gate is untouched.

Foreground checks do not post the notification; the one-prompt-per-version dialog covers that
surface.

Manual checks from Settings use `UpdateCoordinator.check(manual = true)`. A manual request is
throttled for five minutes after a successful check. The five-minute throttle never applies to
the pre-commit policy refresh.

`HttpsPolicyClient` bounds:

- HTTPS only, no cache, `Accept: application/json`, `User-Agent: MyExodus-Updater`
- 10 s connect, 15 s read, 30 s overall; each connect and read is capped to the remaining
  overall budget
- at most five redirects, each HTTPS and to `github.com` or `*.githubusercontent.com`, no user
  info; the overall deadline still applies across redirects
- body read stops as soon as it would exceed 32 KiB
- 404 -> `NO_STABLE_POLICY`; 403 or 429 -> `HOST_THROTTLED`; 5xx or any other non-200 ->
  `HOST_UNAVAILABLE`; TLS/IO -> `NETWORK_UNAVAILABLE`; timeout -> `NETWORK_TIMEOUT`

A verified policy is projected to display state in `UpdateCoordinator.project`:

| Condition | Display status |
|---|---|
| `HALT` | `HALTED` |
| `OFFER` release at or below the installed version | `UP_TO_DATE` |
| release below the trusted version floor | `NOT_IN_ROLLOUT` |
| device not in the signed cohort | `NOT_IN_ROLLOUT` |
| otherwise | `AVAILABLE` |

Discovery only changes the check record and the floors. It never overwrites an active download,
staging operation or pending install.

## 5. Durable state and attempt lifecycle

Both records live in the `update_state` SharedPreferences file. State-critical writes use
`commit()`. All updater state is excluded from backup because `data_extraction_rules.xml` and
`backup_rules.xml` exclude every shared-preference file and `allowBackup` is false.

`UpdateAttempt` states:

```
READY only after verification:
DOWNLOAD_STARTING -> DOWNLOADING -> VERIFYING -> READY_TO_INSTALL
  -> [NEEDS_INSTALL_PERMISSION -> READY_TO_INSTALL]
  -> STAGING -> INSTALLING -> [AWAITING_USER_CONFIRMATION] -> SUCCEEDED
Any active state -> CANCELLED | FAILED
INSTALLING / AWAITING_USER_CONFIRMATION -> UPDATE_STATUS_UNKNOWN when platform evidence is lost
```

Only one attempt is active. The attempt is persisted before `DownloadManager.enqueue`, so a
process death cannot leave an untracked transfer. A process-local `Mutex` serializes commands;
durable attempt and session IDs establish ownership across process death and duplicate
callbacks.

`startDownload` refuses unless: a verified cached policy exists, the mode is `OFFER`, the
release is above both the installed version and the trusted floor, the cohort is eligible, the
network is connected and not roaming, metered use has this attempt's explicit consent, the
app-specific destination exists, and free space exceeds twice the APK size plus 64 MiB.

`startDownload` has two callers: the Settings Download action and
`backgroundCheckFinished`, the latter only under the standing auto-download opt-in. Both run the
same checks; the automatic path always passes `meteredConsent = false`, so it can never start a
metered transfer. The opt-in is a preference in `update_state` (`KEY_AUTO_DOWNLOAD`, default
false). Turning it off affects future automatic starts only; it does not cancel an in-flight
attempt.

Cancellation removes only the owned DownloadManager entry, abandons only an uncommitted owned
session, deletes only that attempt's directory, and stores `CANCELLED`. Terminal leftovers from
other attempts are swept on the next main-process start.

Reconciliation sources:

- `onMainProcessStart`: initialize the version floor, sweep stale files, record an upgrade
  notice, sync the protection marker, reconcile the owned attempt
- `reconcileAfterReplace` and the startup path: if the installed version is at or above the
  attempt target, the attempt becomes `SUCCEEDED` and its files are deleted; a committed
  session that no longer exists becomes `UPDATE_STATUS_UNKNOWN`
- `DOWNLOAD_STARTING` with no download ID becomes `DOWNLOAD_FAILED`

## 6. Download

`UpdateDownloadController` uses DownloadManager with:

- MIME `application/vnd.android.package-archive`
- `setDestinationInExternalFilesDir(..., "updates/<attemptId>/release.apk")`
- neutral `VISIBILITY_VISIBLE` notification ("MyExodus update" / "Downloading an app update")
- roaming always forbidden; metered allowed only with this attempt's consent
- `UpdateDownloadReceiver` (non-exported, `DOWNLOAD_COMPLETE`) correlates the broadcast to the
  owned attempt's `downloadId`, then enqueues the expedited `update_verify` work. The verify
  worker re-reads the transfer, retries while still downloading, and posts the neutral "Update
  ready to install" notification only once `READY_TO_INSTALL` is reached. Neither notification
  can start an install.
- no public Downloads fallback, no FileProvider fallback, no WorkManager or foreground-service
  transfer fallback

Success is never inferred from DownloadManager's displayed byte count. The completed file is
opened with `openDownloadedFile` and streamed through `ApkVerifier.verifyBytes`, then archive
checks run against the app-specific path:

- exact signed byte count and SHA-256
- installed version and trusted floor still below the target
- `minSdk` supported on this device
- archive parses, package equals the runtime package, `versionCode`, `minSdk` and `targetSdk`
  equal the signed values

No custom certificate-set equality is performed. Android decides signing continuity.

## 7. Install

`UpdateCoordinator.install` requires `READY_TO_INSTALL` or `NEEDS_INSTALL_PERMISSION`, checks the
installed version can still be replaced, and checks unknown-sources authorization. It then
fetches and verifies a **fresh** signed policy over HTTPS. The commit is authorized only when:

- mode is `OFFER`
- the release has the same `versionCode`, SHA-256, size and URL as the attempt
- the policy sequence is not below the attempt's authorized sequence
- the device is still in the signed cohort

A `HALT`, a different release, an ineligible cohort, a network failure or a floor rejection
keeps the verified artifact and stores `READY_TO_INSTALL` with
`WAITING_FOR_POLICY_CONFIRMATION`. There is no offline first commit.

`UpdateInstaller.stageAndCommit`:

1. rechecks `canRequestPackageInstalls()`
2. creates `SessionParams(MODE_FULL_INSTALL)` with the app package and signed size
3. API 33+: `PACKAGE_SOURCE_DOWNLOADED_FILE` and the signed URL as originating URI
   (informational, not a restricted-settings bypass)
4. API 31+: `USER_ACTION_NOT_REQUIRED` when supported; the OS may still return
   `STATUS_PENDING_USER_ACTION`
5. persists the returned session ID immediately
6. copies with a 64 KiB buffer into `openWrite("base.apk", 0, signedSize)`, counting and hashing
   the exact bytes written
7. calls `opened.fsync(output)` on the still-open session stream (never casts to
   `FileOutputStream`), then closes the stream
8. rejects a byte-count or digest difference without committing
9. rechecks install permission and that the installed version is still below the target;
   otherwise `INSTALL_CONFLICT`
10. commits with an explicit, non-exported `UpdateInstallReceiver` status `PendingIntent`,
    unique per attempt, mutable on API 31+ (`FLAG_UPDATE_CURRENT` plus `FLAG_MUTABLE`)

`UpdateInstallReceiver` correlates the attempt ID and session ID, ignores stale or duplicate
callbacks, and maps:

| Installer result | Stored outcome |
|---|---|
| `STATUS_SUCCESS` and installed version reached | `SUCCEEDED` |
| `STATUS_SUCCESS` but installed version not reached | `UPDATE_STATUS_UNKNOWN` |
| `STATUS_PENDING_USER_ACTION` | `AWAITING_USER_CONFIRMATION`, prompt URI stored |
| `STATUS_FAILURE_ABORTED` / `INVALID` | `INSTALL_FAILED` |
| `STATUS_FAILURE_BLOCKED` | `INSTALL_BLOCKED` |
| `STATUS_FAILURE_CONFLICT` | `INSTALL_CONFLICT` |
| `STATUS_FAILURE_INCOMPATIBLE` | `RELEASE_INCOMPATIBLE` |
| `STATUS_FAILURE_STORAGE` | `INSUFFICIENT_STORAGE` |
| `STATUS_FAILURE_TIMEOUT` (API 34+) | `INSTALL_TIMEOUT` |
| developer-verification reason extra present | `INSTALL_VERIFICATION_BLOCKED` |
| anything else | `UPDATE_STATUS_UNKNOWN` |

The developer-verification extra
(`android.content.pm.extra.DEVELOPER_VERIFICATION_FAILURE_REASON`, a String constant since API
36.1) is read by its inlined literal. Its presence signals verification failure; the accompanying
status code is not guaranteed, so the extra wins. On older releases the read is a default miss
and the normal status mapping applies.

`STATUS_PENDING_USER_ACTION` is never treated as acceptance and never launches UI from the
receiver. `Continue installation` in Settings or the notification starts the stored system
intent from a resumed activity.

## 8. Post-replace and protection continuity

`UpdatePackageReplacedReceiver` (manifest-declared, non-exported,
`ACTION_MY_PACKAGE_REPLACED`) uses `goAsync()` on IO, calls `reconcileAfterReplace()` and
`syncProtectionMarker()`, then finishes. It starts no foreground service.

Installed target version or newer wins over a lost callback. A missing result is not an
automatic failure. `protectionResumptionRequired` becomes true only when the existing
protection authority or store reports a real gap, and clears automatically once protection is
healthy again. Settings shows the neutral repair action and link to Protection; no silent
claim of health is made, and partner status keeps reporting the real state.

## 9. UI and diagnostics

`UpdateViewModel` projects the snapshot into these phases and actions:

| Phase | Row subtitle | Primary action |
|---|---|---|
| `IDLE` | Check for a new version | Check for updates |
| `NOT_CONFIGURED` | This build has no update channel | none |
| `CHECKING` | Checking for updates | none |
| `UP_TO_DATE` | Up to date, checked <relative time> | Check again |
| `HALTED` | Updates are temporarily paused | Check again |
| `NOT_IN_ROLLOUT` | No update offered right now | Check again |
| `CHECK_STALE` | Couldn't confirm the latest version | Check again |
| `AVAILABLE` | Version <name> is ready to download | Download (metered variant when needed) |
| `DOWNLOADING` / `PAUSED` / `VERIFYING` | progress / paused / verifying | Cancel download |
| `READY_TO_INSTALL` | Ready to install | Install |
| `NEEDS_PERMISSION` | Allow app installs to continue | Allow installation from this source |
| `STAGING` / `INSTALLING` | Preparing / waiting for the system installer | none |
| `AWAITING_CONFIRMATION` | Waiting for your confirmation | Continue installation |
| `STATUS_UNKNOWN` | Update status unknown | Check status |
| `FAILED` | Update needs attention | Retry |

`FAILED` retries transient download failures directly. Authority failures
(`NO_STABLE_POLICY`, `POLICY_INVALID`, `POLICY_ROLLBACK`, `POLICY_CONFLICT`, `HALTED`,
`NOT_IN_ROLLOUT`, `RELEASE_INCOMPATIBLE`) trigger a fresh check instead. A newer successful
check outranks an older failed attempt so a re-offered release cannot be shadowed permanently.
Every dialog also offers Close, the official release page in decision phases, Cancel download in
cancellable phases, and Copy diagnostic summary.

Settings shows, per plan §14: installed version, last authenticated metadata fetch (or "No
verified update check yet"), and the last installation outcome (succeeded / failed with
mapped reason / not confirmed). The one-time post-upgrade notice uses the signed release notes
when the installed version matches the offered release, otherwise generic copy.

**Awareness surfaces (revision 2026-11-06):**

- The dialog holds a `Download updates automatically` switch bound to the standing opt-in.
  Copy states the actual limit: downloads over Wi-Fi only, install still asks.
- `UpdateAvailablePrompt` shows at most once per offered `versionCode`, on app open only, after
  the offer survives the rollout and staleness projection. Review closes the prompt, switches to
  the Settings tab and requests the update dialog through a monotonic one-shot token; Later
  dismisses that version.
- Notification taps carry `MainActivity.EXTRA_OPEN_UPDATE`. `onCreate` and `onNewIntent` both
  route it to the Settings tab and the same dialog-open token, so a cold start and a warm tap
  behave identically. The extra is removed after handling so a state restore cannot re-route.
- Channel `update_availability` (importance default) is created on first post and suppressed
  entirely when notification permission is denied (`NotificationAccess.canPost`). Copy never
  names activity, partner, account or protection state.

`UpdateDiagnostics` keeps at most 200 entries and 14 days in `update_diagnostics` prefs. Entries
hold timestamp, error/event code, API level, policy sequence, mode, version code, bytes,
coarse duration, and DownloadManager/installer status. They never hold cohort ID or hash,
account or partner identity, screen activity, protection events, IP address or keys. The only
egress is the user-initiated Copy diagnostic summary action.

## 10. Failure mapping

| Stored error | User-facing intent |
|---|---|
| `NO_STABLE_POLICY` | No update has been published yet |
| `NETWORK_UNAVAILABLE` / `NETWORK_TIMEOUT` | Couldn't reach GitHub; check connection |
| `HOST_THROTTLED` / `HOST_UNAVAILABLE` | GitHub didn't answer as expected; try later |
| `POLICY_INVALID`, `POLICY_ROLLBACK`, `POLICY_CONFLICT` | Security check failed; nothing installed |
| `HALTED` | Updates temporarily paused; nothing new can download or install |
| `NOT_IN_ROLLOUT` | No update offered to this device |
| `WAITING_FOR_POLICY_CONFIRMATION` | Couldn't confirm against the latest policy; download kept |
| `DOWNLOAD_SERVICE_UNAVAILABLE` / `DOWNLOAD_DESTINATION_UNAVAILABLE` | Download service or storage problem |
| `DOWNLOAD_FAILED` | Transfer didn't finish |
| `APK_INTEGRITY_FAILURE` | Fingerprint mismatch; bad file deleted |
| `RELEASE_INCOMPATIBLE` | Can't install on this device; use the release page |
| `INSUFFICIENT_STORAGE` | Free space and retry |
| `NEEDS_INSTALL_PERMISSION` | Allow installs from this source |
| `INSTALL_UI_BLOCKED`, `INSTALL_BLOCKED` | Android blocked the installer; no security bypass advice |
| `INSTALL_VERIFICATION_BLOCKED` | Android couldn't verify the installer; retry or release page |
| `INSTALL_CONFLICT` | Incompatible with the installed app; no uninstall or downgrade |
| `INSTALL_TIMEOUT` | Installer timed out; retry |
| `UPDATE_STATUS_UNKNOWN` | Android didn't report the result; check what version is running |
| `PROTECTION_RESUME_REQUIRED` | Reopen Protection; partner status still reports the truth |
| `INSTALL_FAILED` | Generic installer failure; retry or use the release page |

Raw exception text and platform status numbers never reach the UI. They are stored or shown
only in the local diagnostic summary when applicable.

## 11. Publisher runbook

The publisher script is `scripts/update-manifest-sign.sh`. It produces exactly the envelope the
client verifies.

```bash
RELEASE_REPOSITORY=<owner>/<releases-repo> \
scripts/update-manifest-sign.sh offer <apk> <versionCode> <versionName> \
    <policySequence> <rolloutPercent> [notes-file]

RELEASE_REPOSITORY=<owner>/<releases-repo> \
scripts/update-manifest-sign.sh halt <policySequence>
```

Environment: `RELEASE_REPOSITORY` (required), `CHANNEL` (default `stable`), `COHORT_SALT`
(generated with `openssl rand -hex 16` when unset; exactly 32 lowercase hex), `KEY_FILE`
(default `~/.myexodus-update-key/update-manifest-key.pem`), `STATE_FILE` (default
`~/.myexodus-update-key/publisher-state.json`), `AAPT` (auto-detected from `ANDROID_HOME`,
`ANDROID_SDK_ROOT` or the macOS SDK path), `OUT` (default beside the APK, or the current
directory for `halt`).

Publisher checks:

- `versionCode`, `policySequence` and `rolloutPercent` are non-negative integers; rollout is
  0..100
- the APK filename matches the safe asset pattern and `versionName` is 1 to 64 printable
  characters
- `aapt dump badging` agrees with the supplied package, `versionCode` and `versionName`, and
  supplies `minSdk`/`targetSdk`
- APK size is at most 250 MiB
- notes are at most 4000 characters with no control characters other than newline, carriage
  return or tab
- the artifact URL is built, never supplied: `artifact-v<versionCode>/<apk filename>`
- payload is compact JSON and at most 16 KiB; the envelope is at most 32 KiB
- `openssl dgst -sha256 -sign` signs the exact payload bytes, then `openssl dgst -verify`
  self-checks the signature

The publisher state file refuses a `policySequence` at or below the highest published one and,
for `offer`, a `versionCode` at or below the highest published one. State advances only after
the envelope is written, so a failed run does not burn a sequence.

Publication steps:

1. Build and sign the exact universal APK with the permanent release identity, with a
   `versionCode` above every published or installed version.
2. Run the script for the intended cohort, for example 5 percent.
3. Publish the APK as an immutable prerelease tagged `artifact-v<versionCode>`.
4. Publish `update-manifest.json` as an immutable non-prerelease tagged
   `policy-<six-digit-sequence>`, designate it latest, and verify the public
   `/releases/latest/download/update-manifest.json` serves that envelope.
5. Confirm the APK downloads anonymously from its version-pinned URL and its SHA-256 equals the
   signed digest.
6. On a device: Settings, Updates, Check, Download, Install, and confirm the installed version.
7. Raise the cohort only through a new higher-sequence policy (5 to 25 to 100). Halt with a new
   higher-sequence `HALT`, never by editing an old release.

A policy release may reference the same artifact with a new salt or percentage. Only the policy
sequence must increase.

## 12. Bad releases and revert semantics

**Android does not install a lower versionCode.** There is no in-place downgrade and the updater
never attempts one. The publisher also refuses to sign a lower `versionCode` than it already
published, and the client keeps both its policy-sequence floor and its version floor after any
manual downgrade.

**Recovery is fix-forward with the same signing identity:**

1. Publish a new `HALT` policy with a higher sequence to stop new downloads and new first
   commits. This is not an instant remote kill switch: a session already committed to Android
   is owned by Android and is not abandoned.
2. Build the corrected APK with the **same release signing key** and a **higher versionCode**
   than the bad release.
3. Publish its artifact and a new higher-sequence `OFFER` policy.
4. Devices still on the bad version update normally. Devices that never installed it are
   covered by the same offer.

**Do not fix a bad release by lowering the versionCode, uninstalling, or switching signing
keys.** `allowBackup` is false, so uninstall loses app data. A debug-signed build cannot
update a release-signed install in place, and the reverse is also true. Switching keys requires
uninstall and data loss, so the release identity decision must happen before tester hand-off.

A policy `HALT` blocks new downloads and new first commits only after a device fetches it.
Cached or stale CDN content can delay that, which is why the client re-verifies a fresh policy
immediately before the first commit.

## 13. Key custody

- **Policy manifest key:** `~/.myexodus-update-key/update-manifest-key.pem`, offline, never in
  the repo or CI. Public half pinned in `UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64`. The publisher
  state file lives beside it. Losing the private key freezes every installed build at its
  current version; rotation requires a trusted higher-version app that pins the next key.
  Back up the private key twice in encrypted independent locations and test a restore.
- **Release APK signing key:** does not exist yet (owner-gated). It must be permanent once
  created. Back it up twice and test recovery before any tester APK is distributed.
- Manifest signing and APK signing are separate keys. Neither belongs on the device, in the
  public releases repo, or in ordinary CI.

## 14. Verification evidence

**PASSED (JVM, 2026-11-06):**

- `./gradlew :app:compileDebugKotlin` succeeded after the v1.1 rewrite and again after the
  2026-11-06 awareness/auto-download revision.
- `./gradlew :app:testDebugUnitTest --tests "com.example.myexodus.update.*"` succeeded: the
  policy fixture suite (OpenSSL-signed fixture verified by JCA, tampering, wrong pin, malformed
  and non-canonical base64, exact key sets, URL rejections, rollout buckets, clock independence),
  the durable-state suite (rollback/conflict/replay, version floor after manual downgrade,
  independent check/attempt records, JSON round trip, upgrade launch marker, auto-download and
  notification/prompt markers surviving a new store instance, and the legacy Int
  `last_seen_version_code` read found by the hardware rehearsal) and the install flow suite
  (mutable status PendingIntent from API 31, complete installer status mapping).
- `UpdateAutoDownloadTest` pins the truth table: transfer starts only with consent, an
  `AVAILABLE` offer, and a connected, unmetered, non-roaming network; every other display state
  and network condition refuses.
- The publisher script was exercised end to end with the production key: the envelope had
  exactly the four client keys, canonical base64, the client's payload key sets, replay and
  versionCode downgrade were refused, HALT wrote rollout 0, and the final envelope verified
  with the pinned public key (`openssl dgst -sha256 -verify`: Verified OK).

**PASSED (physical X6532, debug-signed, 2026-11-07, owner-authorised UI takeover):**

- N to N+1 rehearsal. Canonical install (versionCode 2, `1.1`) was replaced in place with a
  rehearsal build (versionCode 5, `1.1.3-rehearsal`) carrying a deliberately changed
  `settings_title`. `adb install -r` reported `Success`, package state read
  `versionCode=5 versionName=1.1.3-rehearsal`, and the app opened on Settings showing
  "Settings rehearsal A" plus the one-time "now on version 1.1.3-rehearsal" notice. Data
  carried over: the account, streak day, rules and protection state were still present.
- **Upgrade crash found and fixed during the rehearsal.** The canonical install pre-dated the
  updater rework, and that older build stored `update_state.last_seen_version_code` with
  `putInt`; the new store read it with `getLong`, so `recordLaunch` threw
  `ClassCastException: Integer cannot be cast to Long` on every launch and the process died
  before the UI appeared (logcat, 00:45, repeated per launch). `UpdateStateStore.recordLaunch`
  now reads Int or Long from the stored value and still writes Long; the regression is pinned
  by `UpdateStateStoreTest.upgradeLaunchReadsTheLegacyIntEncodingWithoutCrashing`. The fixed
  build launched cleanly and the post-upgrade notice proved the legacy value was read (2 to 5).
- Downgrade refusal. `adb install` of the older APK (versionCode 4) over versionCode 5 failed
  with `INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 4 is older
  than current 5`, and the installed package stayed at 5.
- Manual debug rollback. The canonical APK (versionCode 2, `1.1`, "Settings" restored) installed
  with `adb install -r -d`, package state returned to `versionCode=2 versionName=1.1`, and the
  Settings screen showed the original title and About "Version 1.1". No data was lost.
- `accelerometer_rotation=0` verified after the injected taps (`scripts/rotation-guard.sh`).

**BLOCKED (owner-gated or hardware):**

- Live channel end to end: no releases-only repository exists yet (HTTP 404), and Step 2 is
  owner-gated.
- Release-signed N to N+1 install on hardware: no release keystore exists. A debug-signed APK
  cannot exercise the signing-continuity leg against a release-signed install.
- Installer-session `fsync` with a real `openWrite` stream, duplicate and stale status intents,
  fresh pre-commit `HALT`, protection allow-window expiry, and post-replace protection health
  need instrumented or physical-device runs with a release-signed APK.
- Android 13+ restricted-settings re-enable behavior after a self-update needs a sideloaded,
  release-signed build on hardware.
- 5 to 25 to 100 percent cohort progression, GitHub immutable-release settings and the public
  `latest` mapping need the owner-provisioned repository and publication approval.

None of the blocked items is reported as passed.
