# MyExodus Direct-APK Self-Updater — Implementation Plan

**Document status:** Build specification · **Version:** 1.1 · **Revised:** 2026-11-06  
**Audience:** Android implementation agent, release engineer, QA engineer  
**Distribution:** Direct APK only; no Google Play distribution, MDM, device-owner privileges, or custom update backend.

**Implementation rule:** Inspect the repository before editing, retain existing application identity and signing continuity, implement the gates below in order, and do not fabricate repository-specific values. **A failed safety gate blocks publication, not unrelated safe coding work.**

## 1. Feature Overview

Build an in-app updater for the existing **MyExodus** Android app. Discover signed update policy over HTTPS, offer only approved stable universal APKs, download them after user consent using Android `DownloadManager`, verify their exact size/hash and package metadata, and install with `PackageInstaller` without uninstalling the existing app or losing user data.

Use a **dedicated public, releases-only GitHub repository** (not the private source repository). Android clients fetch one signed `update-manifest.json` through `/releases/latest/download/`; this `latest` link is **discovery only**. APK downloads always use immutable, version-specific release asset URLs. No GitHub REST API, GitHub credential, custom updater server, custom foreground transfer service, or additional database is required.

The signed metadata is a **release policy**, not merely an APK pointer. It carries a monotonic policy sequence, an `OFFER` or `HALT` action, and a deterministic percentage rollout. Publishing a newer signed policy release can expand a cohort or halt new installations without editing immutable releases. It cannot retroactively stop installations already committed to Android or guarantee immediate revocation through a stale CDN.

**Update checks are automatic; install initiation is always user-driven.** **Revision 2026-11-06 (owner-approved):** a download may also be automatic, but only after the user turns on the explicit standing opt-in described in §8.7. On Android API 31+, request unattended *self-update* only when supported and always handle the system confirmation path. Never request privileged `INSTALL_PACKAGES`, suppress Play Protect, disable device security, automatically uninstall, or downgrade. Preserve MyExodus's protection and partner-accountability rules throughout the update lifecycle.

**Revision 2026-11-06 — owner-approved awareness and auto-download change.** The app now surfaces automatic discovery: a neutral `update_availability` notification when a background check finds an offered version, one in-app prompt per offered version on app open, and a "Update ready to install" notification once a transfer is verified. Automatic transfer is a separate standing opt-in ("Download updates automatically", default **off**): only when it is on may a successful background check start the `DownloadManager` transfer, and only on a connected, unmetered, non-roaming network. Installing still requires a per-install user confirmation. This revision changes who may *start a download*, never who may *commit an install*; read R01/R06, the §7 step 5/6 gates, §8.7 and §17 with it.

## 2. Requirements

### Functional requirements

- **R01 — Discovery:** Schedule one unique 24-hour periodic WorkManager metadata check (two-hour flex, connected network, `KEEP`); additionally check on foreground entry when 24 hours have elapsed since the last attempt and on explicit manual request (five-minute successful-request throttle). The check itself never downloads an APK; a post-check transfer is permitted only under the standing auto-download opt-in (§8.7, revision 2026-11-06).
- **R02 — Release endpoint:** Use a single **verified public releases-only repository** configured as immutable build-time `UPDATE_RELEASE_REPOSITORY`. Fetch `https://github.com/<UPDATE_RELEASE_REPOSITORY>/releases/latest/download/update-manifest.json` without a client API token. Resolve its actual value from release configuration before a build is approved; do not ship placeholders.
- **R03 — Manifest trust:** Verify one ECDSA P-256 `SHA256withECDSA`/ASN.1 DER signature over exact decoded payload bytes with one pinned production verification public key. Reject malformed envelopes before authoritative use; keep the private signing key offline with two tested encrypted backups.
- **R04 — Signed rollout / halt:** Enforce increasing `policySequence`, `OFFER`/`HALT` modes, and signed `rolloutPercent` (0–100). Calculate stable eligibility from a locally random installation cohort ID and the signed per-release salt. `HALT` or rollout 0 prevents *new downloads and new installer commits*; do not cancel an already committed Android operation blindly.
- **R05 — Artifact selection:** Offer only a stable-channel universal APK for the installed `applicationId`, a permitted version-specific GitHub asset reference, matching declared package/version/SDK and an Android `versionCode` greater than installed and not below the previously trusted release floor. Android alone decides final signing/lineage compatibility.
- **R06 — Network consent:** Show version, plain-text release notes and exact signed APK size. Download only after user action — a per-release Download tap, or the explicit standing auto-download opt-in (§8.7); request additional per-download metered-data consent, and block roaming.
- **R07 — Download:** Use Android `DownloadManager` to an app-specific external-files path, persist download identity, handle pause/failure, and never expose an unverified APK through a completed-download open action or public Downloads fallback.
- **R08 — Verification and install:** Require exact byte count, SHA-256, archive package/version checks, an installer-session staging digest, install-source authorization, and a **fresh online signed policy check immediately before first commit**. Use `PackageInstaller.MODE_FULL_INSTALL`, `Session.fsync(out)` if invoked, and a mutable explicit status `PendingIntent`. Support `STATUS_PENDING_USER_ACTION` without background activity launches.
- **R09 — Durable recovery:** Persist one active update attempt, one owned installer session, download ID and result identity; reconcile after process death, reboot, activity return and package replacement. A process-local `Mutex` never replaces durable coordination.
- **R10 — State:** Persist independent discovery state and installation-attempt state; do not permit an ongoing check to overwrite an active download, staging operation or pending user approval.
- **R11 — Protection continuity:** Before enabling production rollout, prove that the protection blocker does not prevent the system installation/unknown-source screens. If it does, implement only a time-boxed, narrow, approved system-UI update allowance without disabling protection or bypassing partner authorization. Validate protection, data and accountability state after installation and guide user recovery when needed.
- **R12 — Source restrictions:** Do not create a Google Play flavor, request battery-optimization exemptions solely for the updater, weaken Android restricted settings, ask users to disable Play Protect, or add automatic remote telemetry.
- **R13 — Publication safety:** Publish signed APK artifacts separately from immutable signed policy releases. Release to a small percentage cohort, enlarge through subsequent higher-sequence signed policy releases, and halt through a newer signed `HALT` policy. All recovery APKs use a **higher** Android version code.

### Nonfunctional invariants

- No install or download may be authorized by unverified metadata, an invalid policy, ineligible rollout cohort, or an APK that fails exact-byte verification.
- No modification of the existing application ID, release signing identity, private application data or accountability consent model; do not uninstall to fix incompatibility.
- Only one active download/installation attempt across restarts and app processes. Never abandon another component's or a still-pending legitimate installer session.
- A newer signed policy sequence must never be replaced by an older policy from a cache, server, restore or downgraded app. A manual app downgrade does **not** reset the trusted floor.
- No app-owned private keys or publication tokens on the device; no sensitive protection or recovery data in diagnostics.
- Android may defer background work, require user action, or restrict background service startup. Never promise a specific unattended outcome or exact daily timing.
- The updater cannot weaken core blocking just to open Settings or installer UI. Any scoped exception must preserve existing owner/partner approval rules.

## 3. Existing System Context

**Required Step 1 repository inventory:** Inspect the actual Android module/build variants, package/application ID, min/target SDK, manifest and `android:process` values, APK packaging, signing config and deployed tester signing cert fingerprints. Locate the existing `Application`, Hilt, WorkManager, HTTP client, DataStore/Room, notification and Compose conventions; locate accessibility blocker, VPN/filtering and protection heartbeat/partner-status paths; inspect whether backup/data-extraction rules cover updater state. Record paths and findings in the PR. **Do not invent filenames, class names, permission state or existing integrations.**

Keep the current app identity, supported API baseline, dependencies, database schema and existing protection architecture intact. Use current injection and persistence if available; otherwise add only AndroidX WorkManager for metadata checking and Preferences DataStore for a tiny updater record. Hilt is used only if already present. If custom WorkManager configuration is needed, merge the `androidx.startup.InitializationProvider` and remove just `androidx.work.WorkManagerInitializer` metadata with `tools:node="remove"`; provide the injected `WorkerFactory` through the existing `Application` and check the merged manifest.

**Process ownership:** Run coordinator initialization, WorkManager scheduling and session reconciliation only in the app's designated **main process**. Workers and installer receivers delegate to the main-process state owner; use multiprocess-safe storage if code review demonstrates updater components actually span processes. Do not open two DataStore instances for the same file from different processes.

**Backup:** Exclude updater-only state (cohort identity, download/session IDs, highest-seen policy/release floors, pending install states) from backup and device-transfer restore. On a detected restore/data reset, clear only ephemeral updater attempts and rebuild installed-version state; never import session IDs from another device. Keep the rollback floor during normal app updates and process restarts. Losing updater-only data during a genuine device restore is acceptable, not losing application data.

**Protection integration is narrow but in scope:** Probe accessibility overlays for Settings/Package Installer interception, VPN/other filter state, background start restrictions, permission persistence, and partner-notification logic. If these interfere with legitimate OS installer screens, implement an explicit narrowly scoped, short-lived installation UI allowance for verified active update attempts after required user/partner authorization. Never globally pause or disable the protection engine. Reuse existing initialization/health APIs after `MY_PACKAGE_REPLACED`; do not start a prohibited foreground service from a receiver.

Suggested feature component responsibilities (rename to match existing code conventions): `UpdateManifestClient`, `UpdateSignatureVerifier`, `UpdatePolicyValidator`, `UpdateCheckWorker`, `UpdateCoordinator`, `UpdateStateStore`, `UpdateDownloadController`, `UpdateArtifactVerifier`, `UpdateInstaller`, `UpdateInstallStatusReceiver`, `UpdatePackageReplacedReceiver`, `UpdateViewModel`, and a minimal `ProtectionUpdateIntegration` adapter.

## 4. Architecture

**Publisher boundary:** The owner builds and validates a universal APK and signs it with the permanent MyExodus application signing key. A separate offline ECDSA P-256 key signs the exact policy JSON bytes. Neither private key enters app code, the public releases repository or ordinary CI. Signing artifacts and restoration backups are owner controlled.

**Hosting boundary:** The dedicated public releases-only GitHub repository contains (A) **immutable artifact releases**, tagged `artifact-v<versionCode>`, carrying the signed universal APK, and (B) **immutable stable policy releases**, tagged `policy-<zero-padded-sequence>`, carrying only `update-manifest.json`. Publish artifact releases as **prereleases** so they cannot become `latest`. Explicitly set each signed stable policy release as `latest` and verify the public `/releases/latest/download/update-manifest.json` resolves to its asset. A later policy release may point at the *same* APK and adjust rollout/halt without editing older releases. The source repository remains private.

**Device trust boundary:** One pinned manifest public key authenticates one bounded envelope. The signed payload authorizes policy action, cohort percentage and a version-specific APK URL/digest. The OS package manager validates final APK signing continuity; do not recreate signature-lineage rules in client code.

**Execution boundary:** Unique WorkManager work only fetches/verifies small signed policies. `DownloadManager` manages transfers. One coordinator stores discovery and install states separately. `PackageInstaller` stages and commits. A manifest-declared, non-exported receiver processes callbacks and an app-replaced receiver requests non-blocking reconciliation.

**Protection boundary:** Installation runs only following explicit user action and existing authorization. The app permits exactly the relevant trusted Android installer/permission UI during a short update window if the blocker would otherwise intercept it. After replacement, existing components check actual protection health and request user intervention when OS restrictions prevent restoration.

**Rollout safety boundary:** Before committing a new installation session, retrieve and verify a fresh signed policy authorizing the exact APK and cohort. New `HALT` policy blocks new commits after the new policy is observed. Already committed sessions remain Android-owned; cached or replayed signed policies cannot provide an instantaneous global revocation guarantee.

## 5. Architecture Decisions

| Decision | Rationale |
|---|---|
| Dedicated public releases-only GitHub repository | Exposes binaries but not private protection implementation source. |
| Static signed `latest/download` policy discovery, no REST client polling | Avoids unauthenticated shared-IP REST quotas; a 304 exemption is not guaranteed without authentication. |
| Separate immutable artifact and policy releases | Allows signed rollout adjustments and emergency halt without mutating immutable assets. |
| Version-specific APK asset URLs | Stops moving `latest` references from changing downloaded binaries. |
| One active offline-backed ECDSA P-256 verification key | Provides metadata authenticity with smaller key-compromise surface. |
| Monotonic `policySequence` and highest-seen version floor | Rejects old policies without trusting a reset caused by manual APK downgrades. |
| Signed percentage cohorts using a local random installation ID | Supports test rollouts without server-side user identity or telemetry. |
| WorkManager for discovery only | Keeps deferrable work small and preserves background execution quotas. |
| User-initiated DownloadManager transfer | Provides system-managed downloads without a custom foreground service. |
| Private app-specific transfer destination; no public fallback | Prevents accidental unverified APK sharing and storage-permission sprawl. |
| Android package manager is signing authority | Avoids incorrect custom certificate-history matching. |
| `PackageInstaller.Session.fsync(out)` followed by stream close | Uses the official session API, not a `FileOutputStream` cast. |
| Conditional unattended self-update with prompted fallback | Honors actual Android eligibility and user control. |
| Existing storage and separate discovery/attempt records | Avoids extra databases and prevents background checks clobbering installations. |
| Narrow, approval-preserving protection integration | Prevents self-blocking without creating an evasion pathway. |
| Reconcile on startup and package replacement | Handles callbacks lost when self-updating terminates the process. |
| Fix-forward and signed policy halt | Prevents new defective installs when noticed; avoids data-destructive downgrades. |
| No Play Protect bypass, auto telemetry or battery whitelist prompts | Keeps app behavior safe, privacy-preserving and platform-compliant. |

## 6. Data Flow

1. Main-process startup creates unique periodic discovery; foreground/manual requests start or join the same discovery operation without touching the active installation record.
2. Fetch the bounded `latest/download/update-manifest.json` envelope over HTTPS, verify the detached embedded signature over decoded payload bytes, validate signed `policySequence`, channel, package ID and mode, then persist the authenticated policy identity. A lower sequence than previously seen is rejected.
3. If `HALT`, suppress new release downloads and installs, display a neutral suspended-updates message and retain current app. If `OFFER`, verify target release identity and compute cohort eligibility from the signed release salt and the device-local random cohort ID; show `UP_TO_DATE`, `NOT_IN_ROLLOUT` or `AVAILABLE` without overwriting the active install state.
4. User selects **Download**; show exact signed bytes and plain-text release notes, require cellular consent if metered, forbid roaming. Persist attempt ID and enqueue a unique `DownloadManager` request to a private app-specific destination. Recover by ID/status on lifecycle events.
5. On verified DownloadManager success, stream the file through exact count/SHA-256 verification and archive package/version preflight. Persist `READY_TO_INSTALL` with artifact identity.
6. User selects **Install**. Confirm unknown-source authorization; check that the protection blocker will permit required Android Settings/installer UI without disabling protections. Start the short, explicitly authorized scoped installation window if the existing blocker requires it.
7. **Before the first `PackageInstaller.commit()`**, perform a fresh HTTPS fetch and verification of the newest signed policy. Require `OFFER`, same target APK/hash, cohort eligibility and nondecreasing policy sequence; if fresh policy cannot be verified, defer commit without discarding the verified artifact. Reject new commits for `HALT` or ineligible cohorts.
8. Stage through `MODE_FULL_INSTALL`; persist owned session ID, stream and rehash actual bytes, optionally call `session.fsync(out)`, close every write stream, and commit via a unique mutable PendingIntent targeting a non-exported manifest receiver.
9. If Android requests user action, persist `AWAITING_USER_CONFIRMATION` and surface the system intent from a resumed activity or user-tapped notification. Never treat the request as acceptance or launch a background UI trampoline.
10. Reconcile installed version and owned package-installer sessions after any callback, restart, reboot, or package replacement. Check accessibility/VPN/filter/database/accountability state, show neutral repair guidance if a control needs user authorization, and close the scoped installation allowance.
11. Release owner publishes an initial small-cohort policy, then progressively higher-sequence `OFFER` policies and, if needed, a higher-sequence `HALT` policy. New APK corrections always use a higher Android `versionCode`.

## 7. Implementation Sequence

Execute in dependency order and record the stated gate evidence.

1. **Repository + protection inventory:** Record actual IDs/SDKs/processes, signed tester APKs, persistence/backup, installed services and whether blocker intercepts OS install/unknown-sources UI. **Gate:** no speculative service bypass; no signing identity change.
2. **Distribution baseline:** Provision a separate public releases-only GitHub repository; pin its build-time URL; configure release publishing/immutability and offline production key backup/recovery. **Gate:** source stays private and public artifacts are anonymously retrievable.
3. **Policy protocol and publisher fixtures:** Implement one signed envelope, versioned policy schema (`OFFER`/`HALT`), SHA-256, policy sequence, cohort salt, deterministic bucket calculation and real OpenSSL/JCA fixture tests. **Gate:** tampering and rollback tests fail closed.
4. **Durable state + main-process owner:** Implement independent check and attempt records, one active attempt, cohort ID, backup exclusion, atomic update rules and process gating. **Gate:** restart, multi-process and restored-state tests pass.
5. **Metadata discovery:** Add bounded fetch, signature/policy validation and rollout projections; integrate unique periodic worker and manual checks. **Revision 2026-11-06:** a successful background check posts a neutral available notification (once per offered versionCode, background checks only) and, when the standing opt-in is on, starts the verified download on an unmetered, non-roaming connection. **Gate:** background checks never overwrite a pending install, and start a transfer only under the standing auto-download opt-in; otherwise they only notify.
6. **User download:** Add DownloadManager, metered/roaming policy, neutral notification copy, private destination, progress, cancel/retry and orphan reconciliation; the completion broadcast posts one "ready to install" notice after verification. **Gate:** interruption/device restart produces no duplicate transfer.
7. **Installer and scoped protection bridge:** Add preflight hash, package checks, pre-commit signed-policy refresh, scoped system UI allowance (only if necessary), PackageInstaller session and secure status callbacks. **Gate:** signed N→N+1 install succeeds and original protection cannot block authorized OS UI.
8. **Post-replace continuity:** Add `MY_PACKAGE_REPLACED` health/reconciliation hook, platform-safe repair notification and consent-preserving partner-state handling. **Gate:** core protection functionality recovers or displays a truthful prompt without inventing silent background restart capability.
9. **UI and diagnostics:** Wire Compose, state projection, neutral lock-screen copy, plain-text release notes and local diagnostic summary. **Gate:** every state provides a valid action or clear terminal status.
10. **Rollout/halt and full QA:** Exercise prerelease artifact + stable signed policy discovery, 5%→25%→100% rollout, halt policy, cached old policy, fresh-commit gate, user prompt, OEM devices and restricted settings. **Gate:** no unsafe release reaches ineligible clients in tests.
11. **Production gate:** Perform actual upgrade on signed test devices, register appropriate Android developer-verification account/certificate, complete disaster/compromise runbooks, publish artifact, sign stable policy, verify the external endpoints and launch staged rollout. **Gate:** Sections 17 and 18 are satisfied.

## 8. Detailed Implementation Steps

### 8.1 Signed update policy and manifest envelope

Store **one** UTF-8 asset `update-manifest.json` (maximum **32 KiB**) with exactly the envelope keys `format`, `keyId`, `payloadBase64`, and `signatureBase64`. Encode an exact UTF-8 JSON payload as Base64 and sign those **original bytes**, not a reserialized map. Signature is ASN.1 DER ECDSA P-256 with SHA-256, never raw `r || s`.

```json
{
  "format": "myexodus-update-envelope-v1",
  "keyId": "primary-v1",
  "payloadBase64": "<base64-of-payload-bytes>",
  "signatureBase64": "<base64-of-DER-signature>"
}
```

The signed payload (maximum **16 KiB**) has a strictly validated common header:

```json
{
  "schemaVersion": 1,
  "packageName": "<existing-production-applicationId>",
  "channel": "stable",
  "policySequence": 100,
  "mode": "OFFER",
  "rolloutPercent": 5,
  "cohortSalt": "0123456789abcdef0123456789abcdef",
  "issuedAtEpochSeconds": 1794000000,
  "release": {
    "versionCode": 100001,
    "versionName": "1.0.1",
    "minSdk": 26,
    "targetSdk": 35,
    "apkUrl": "https://github.com/<verified-public-release-repo>/releases/download/artifact-v100001/myexodus-v100001-universal.apk",
    "apkSizeBytes": 45678901,
    "apkSha256Hex": "<64-lowercase-hex-characters>",
    "releaseNotes": "Reliability and security improvements."
  }
}
```

For a halt, sign the same common fields with `"mode":"HALT"`, `"rolloutPercent":0`, and **omit `release`**. All placeholders and numbers in examples are schema illustrations, not production configuration or actual GitHub assets. The publisher produces real package values, URL, APK length and digest from the already-signed production artifact.

- Only accept `schemaVersion=1`, `channel=stable`, the runtime production package name, and one known key ID (`primary-v1`). Require `policySequence` to be a positive 64-bit integer and strictly greater than the stored highest sequence for *different* policies. If equal, only accept exact same authenticated payload hash. Reject lower-sequence payloads as `POLICY_ROLLBACK` and retain the last verified policy for display only.
- `OFFER` requires all release fields, positive `apkSizeBytes <= MAX_UPDATE_APK_BYTES`, integer positive `versionCode`, bounded `versionName` and plain-text release notes, 64 lowercase hex digest and allowed SDK values; `HALT` must not provide a release object and cannot initiate download/commit.
- `apkUrl` must be HTTPS with host `github.com`, exactly the configured releases-only owner/repo and a concrete `/releases/download/artifact-v<signed-versionCode>/<safe-filename>.apk` path. Forbid fragments, credentials, traversal, query parameters and an asset URL under `/latest/`. The publisher validates that tag and file are actually present; the client validates their syntactic and signed relationships.
- Generate one app-local **128-bit random installation cohort ID** with a secure PRNG at first use; keep it private and exclude it from backup. Compute `SHA256(cohortIdBytes || UTF8(cohortSalt))`; interpret the first eight bytes as an unsigned big-endian integer and calculate `bucket = value % 10000`. User is eligible iff `bucket < rolloutPercent * 100`. Same device and same salt give stable membership; a different salt permits a new rollout cohort. Never send the ID or bucket to GitHub.
- Persist `highestTrustedPolicySequence`, `highestTrustedVersionCode` (greatest valid offered version observed), and the payload digest atomically after verification. Initialize highest trusted version to **at least** installed version. **Never lower either floor on a manual app downgrade.** An installed lower version may update to a newly signed offer at or above the stored version floor; otherwise show stale policy / no eligible update, not an unsafe downgrade.
- Treat `issuedAtEpochSeconds` as signed operator/display metadata, **not a hard reject based on the potentially incorrect device clock**. A never-changing valid latest policy cannot be distinguished securely from a freeze attack without a trusted online freshness authority. Display `CHECK_STALE` after seven days without successful fetch.
- Use `java.util.Base64.getDecoder()` on API 26+; reject invalid encoding and enforce a consistent canonical representation without writing a custom PEM parser. Parse signed JSON with the project's strict JSON library, configure duplicate-field rejection if supported, and validate all types and bounds. Any parsing failure is terminal for that response.
- Generate/verify signatures with `openssl dgst -sha256 -sign ...` and JCA `Signature.getInstance("SHA256withECDSA")`, `KeyFactory("EC")`, `X509EncodedKeySpec` (SPKI public key). Keep the private key offline, encrypt two backups and test restoring them. Key rotation requires a vetted higher-version MyExodus APK containing the next pinned public key; an untrusted manifest cannot grant itself new trusted keys.

### 8.2 HTTP and discovery

- Configure `UPDATE_RELEASE_REPOSITORY` once before first production deployment. Point it at the dedicated public releases-only GitHub repository. Resolve the final endpoint from that verified constant; never use the private source repository for public update assets. If the repository moves, retain signed GitHub redirects where they work, and provide a **manual, same-signer higher-version APK update** if the old endpoint becomes inaccessible; no arbitrary remote URL repointing.
- Fetch the signed envelope through HTTPS, with normal TLS hostname validation, **10 s connect / 15 s read / 30 s overall** bounds, maximum five HTTPS redirects to `github.com` or known GitHub asset domains, and maximum 32 KiB + 1 byte read. No token, no GitHub REST API and no HTTP body trusted until its signature is verified. Redirect restrictions apply to *metadata fetches*, not to DownloadManager's system-managed binary redirects, whose bytes are authenticated by size/hash.
- Use one successful-request five-minute manual throttle, unique periodic work `KEEP` with 24-hour repeat + two-hour flex, and foreground checks after 24 hours. Keep check scheduling independent from active download/install attempts. Use bounded WorkManager backoff for transient errors; do **not** claim the built-in backoff provides jitter.
- Distinguish no stable policy (404), invalid metadata, 403/429 throttling, 5xx, TLS, timeout and storage/persistence errors. Retain the previous verified policy only for UI context after discovery failure; **do not authorize a new install from it** if fresh verification is unavailable at the installation gate.
- GitHub REST ETag `304` requests are not universally exempt from the unauthenticated 60/IP/hour primary limit; official GitHub guidance ties the no-count guarantee to correctly authorized requests. Do not embed an API credential in an APK. Always validate the public `latest` mapping in publisher tests; CDN staleness is an availability risk, not permission to bypass verification.

### 8.3 State, process isolation and reconciliation

Persist two orthogonal objects using existing transactional storage or Preferences DataStore and a single main-process coordinator:

1. `UpdateCheckState`: `checkStatus` (`IDLE`, `CHECKING`, `SUCCESS`, `FAILED`), last attempt time, last signed-success time, latest verified policy sequence/hash, `displayStatus` (`UP_TO_DATE`, `AVAILABLE`, `HALTED`, `NOT_IN_ROLLOUT`, `POLICY_ROLLBACK`, `CHECK_STALE`) and last check error. A check changes **only** this object and the global policy/version floors.
2. `UpdateAttempt`: `attemptId`, state (`DOWNLOAD_STARTING`, `DOWNLOADING`, `VERIFYING`, `READY_TO_INSTALL`, `NEEDS_INSTALL_PERMISSION`, `STAGING`, `INSTALLING`, `AWAITING_USER_CONFIRMATION`, `UPDATE_STATUS_UNKNOWN`, `SUCCEEDED`, `CANCELLED`, `FAILED`), authorized `policySequence`, release identity/hash/size, `downloadId`, destination, `sessionId`, timestamps, metered consent, committed flag and error. Installation commands change only this object unless they explicitly consume a new verified policy.

**Transitions:** `AVAILABLE → DOWNLOAD_STARTING → DOWNLOADING → VERIFYING → READY_TO_INSTALL → [NEEDS_INSTALL_PERMISSION → READY_TO_INSTALL] → STAGING → INSTALLING → [AWAITING_USER_CONFIRMATION] → SUCCEEDED`. `DOWNLOAD_STARTING/DOWNLOADING/VERIFYING/READY_TO_INSTALL/STAGING` may transition to `CANCELLED` or `FAILED`; `AWAITING_USER_CONFIRMATION` may transition to `CANCELLED`, `FAILED`, `SUCCEEDED` or `UPDATE_STATUS_UNKNOWN` upon platform evidence. `UPDATE_STATUS_UNKNOWN` must be reconciled against installed version, session state and retained artifact before any retry. Completed/canceled/failed attempts may be archived and a new verified offer can start a new attempt.

- A discovery request while an attempt is active updates discovery only and may mark **new initiation** ineligible under a verified `HALT`; it never resets active progress. Never auto-abandon already-committed sessions on a newer policy or timeout.
- Guard in-process commands with a `Mutex`, but enforce single ownership through persisted attempt/session state and Android's IDs across process death. Initialize and schedule only in the designated main process, and use the correct multiprocess-safe persistence if any actual worker process shares state.
- Exclude all updater-only state from backup/device transfer and detect unusable restored IDs. On genuine state reset, initialize version floor from the installed package; on ordinary process death/update keep the highest-seen policy and version floors unchanged.
- Correlate callbacks with attempt ID + session ID, ignore duplicate/obsolete callbacks, and never let an injected/stale callback override actual installed-version evidence.

### 8.4 Download implementation

- Use `DownloadManager.Request` with MIME `application/vnd.android.package-archive`, `setDestinationInExternalFilesDir(...)` under `updates/<attemptId>/release.apk` and `VISIBILITY_VISIBLE`. Set a **neutral notification title and description** without a sensitive product purpose, user/cohort IDs or installation UUID.
- Handle null external-files directory and `IllegalStateException`/file errors as `DOWNLOAD_SERVICE_UNAVAILABLE` or `DOWNLOAD_DESTINATION_UNAVAILABLE`. **Do not fallback to public Downloads** or an exported FileProvider. Ask the user to free storage/retry; do not clear the system DownloadManager cache for them.
- `setAllowedOverRoaming(false)` always; `setAllowedOverMetered(false)` unless the user explicitly acknowledges this release's download on a metered connection. Consent is per attempt, never global.
- Persist the attempt before `enqueue`, then persist the download ID and reconcile any partial enqueue by the app-owned unique private destination. Query owned IDs via `DownloadManager`, including `PAUSED`, `RUNNING`, `PENDING`, `FAILED`, and `SUCCESSFUL`; treat missing completion broadcasts as normal.
- Verify completed bytes through `openDownloadedFile(downloadId)` or the platform resolver, not by assuming an app-private file path is always directly readable. Never trust DownloadManager's displayed byte count alone.
- DownloadManager controls its own redirects. The client does not enforce the metadata HTTP redirect count/host policy on binary transfers. Signed size and final SHA-256 remain mandatory; wrong content is never staged.
- Cancel only the owned download. On failures clean only the attempt's own file and ID. Do not silently implement `WorkManager`/foreground-service fallback. Any later such design requires Android 14+ `FOREGROUND_SERVICE_DATA_SYNC` and current platform-specific execution checks.

### 8.5 APK validation and installer

- Stream the downloaded APK twice as designed: (1) preflight verify exact signed byte length + SHA-256 and archive package/version/min SDK, and (2) hash and count bytes actually written into the installer session. Do not load the APK into RAM. API-specific `getPackageArchiveInfo` calls must handle null/invalid archives; **do not perform custom certificate-set equality**.
- Re-fetch and verify the newest signed policy immediately before the **first session commit**. Require authorized `OFFER`, identical target version/hash/size/URL, stable cohort eligibility and nondecreasing sequence. A newly observed `HALT` or 0% rollout prevents new commit. If network/policy validation fails, retain verified download and display `WAITING_FOR_POLICY_CONFIRMATION`; no offline first commit.
- Check `canRequestPackageInstalls()` before staging and again before commit. Show `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` only after user action; recheck on return. Never attempt to alter Android restricted settings programmatically.
- Implement a narrow UI-coordination hook only if actual blocker tests show interception of the Android installer or unknown-source Settings. The hook only permits verified installer/system-settings destinations for the active authorized attempt, expires after **10 minutes** or immediately on terminal attempt, and does not exempt unrelated browsers, adult content, websites or general Settings. Existing partner approval and audit-event semantics remain enforced.
- Create `PackageInstaller.SessionParams(MODE_FULL_INSTALL)`, set app package and APK size. On API 33+ set `setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)` and `setOriginatingUri` to the signed download URL (informational, **not** a restricted-settings bypass). On API 31+, call `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` for the app's own update and declare `UPDATE_PACKAGES_WITHOUT_USER_ACTION`; Android applies its documented target-SDK/installer eligibility requirements and may return `STATUS_PENDING_USER_ACTION`. The UX always supports a normal user prompt.
- Save the returned session ID immediately. Open `session.openWrite("base.apk", 0, signedSize)` and copy using a 64 KiB buffer while updating a second SHA-256. Call **`session.fsync(out)` on the still-open stream when syncing is required**; this is the documented API (API 21+) and **not** `out as FileOutputStream` or `out.fd.sync()`. Close all openWrite streams before `session.commit(...)`. If hash/size differs, abandon this owned session and never commit. Handle `IOException`, space exhaustion and all platform errors.
- Before commit, recheck unknown-source authorization, version and ownership. Create a manifest-declared **non-exported** `UpdateInstallStatusReceiver`, use an explicit intent with unique request code/attempt/session association, and pass a mutable broadcast `PendingIntent` (API 31+: `FLAG_MUTABLE`; satisfy target API 35+ commit requirement). Validate correlation and reject stale callback injection.
- For `STATUS_PENDING_USER_ACTION`, persist `AWAITING_USER_CONFIRMATION`, extract `Intent.EXTRA_INTENT` using the appropriate API getter and present the OS-provided system activity only in a resumed Activity or via an actual user-tapped notification PendingIntent; do not background-launch from a receiver. If notifications unavailable, retain **Continue installation** in Settings, reconcile session and initiate a new approved attempt only when permitted.
- Map `STATUS_FAILURE_ABORTED`, `_BLOCKED`, `_CONFLICT`, `_INCOMPATIBLE`, `_INVALID`, `_STORAGE`, `_TIMEOUT` (API 34+) and unknown codes separately. For Play Protect, policy or developer-verification failures show platform explanation and contact/retry guidance **without telling users to disable protective scanning**. Inspect `EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON` only where the running SDK exposes it; older versions may only expose generic/aborted/blocked status.

### 8.6 Package-replacement and protection continuity

- On main-process startup, returned activity, and `ACTION_MY_PACKAGE_REPLACED`, reconcile installed `longVersionCode`, package name and owned installer session. Installed target version or newer wins over lost callback. Do not treat a missing result as automatic failure. Inspect `getSessionInfo()` and only owned orphan sessions; leave a legitimate pending user action alone.
- On `MY_PACKAGE_REPLACED`, invoke **only lightweight, already permitted** existing health/state queries for accessibility/filters/VPN, active blocking rules, encrypted DB availability and partner-accountability state. Do not start a forbidden foreground service from the receiver; schedule permitted follow-up and display repair actions when the app next becomes visible.
- Before the user commits, show neutral copy that Android may briefly interrupt active protection during package replacement. Persist a `protectionResumptionRequired` marker for any actual disabled control. When a control cannot be automatically restored lawfully, post a **neutral notification** where notification permission and OS constraints permit, and show a persistent in-app **Open to resume protection** action until validated healthy.
- Do **not** suppress or falsify partner/offline alerts. If the existing accountability protocol distinguishes maintenance interruptions, tag a proven update-initiated interruption with a bounded reason/time and preserve evidence; keep real prolonged protection-off events visible according to existing consent and escalation rules. If no such maintenance reason exists, emit the normal truthful status and do not add a silent exemption.
- On API 33+ QA, specifically check that a self-update does not re-trigger a restricted-settings inability to re-enable Accessibility. Validate both previously enabled access and a legitimate user re-enable flow on API 33+, with `setPackageSource(PACKAGE_SOURCE_DOWNLOADED_FILE)`. Block rollout if the app cannot restore protection without unsafe instruction.
- Close the scoped installer UI allowance after confirmed success, failure, cancellation or ten-minute expiry. Reconcile from local durable state before resuming download/install attempts after a reboot.

### 8.7 UI and notifications

Present **Check for updates**, **Download**, **Cancel download**, **Install**, **Allow installation from this source**, **Continue installation**, **Retry**, **Open official release page**, and **Open to resume protection** when applicable. Show a stable available version, exact signed APK size and **plain text** release notes only (no HTML/Markdown auto-link execution). Show `HALTED` as temporarily unavailable and `NOT_IN_ROLLOUT` as no update offered; don't expose bucket IDs.

Use neutral notification/lock-screen text that follows existing MyExodus privacy conventions. Never claim success before installed-version reconciliation, never present a pending Android prompt as cancellation, and never suggest disabling Play Protect, restricted settings, Accessibility safeguards or antivirus as an installation fix.

**Revision 2026-11-06 — awareness surfaces (owner-approved).**
- Channel `update_availability` (importance default), two fixed notification IDs (available, ready). Copy names only the version and the action; it carries no activity, partner, account or protection context. `POST_NOTIFICATIONS` denial is respected silently.
- "Update available" is posted only by a successful **background** check, at most once per offered `versionCode`. Its tap opens MainActivity with `EXTRA_OPEN_UPDATE`, lands on the Settings tab and asks the existing Settings dialog to open (one-shot token).
- One in-app "Update available" prompt is shown per offered `versionCode`, on app open only, after the offer survives the rollout/staleness projection. Review opens the same Settings dialog; Later dismisses that version.
- "Update ready to install" is posted after the expedited verify worker reaches `READY_TO_INSTALL`. It never auto-installs; the user opens the app and confirms.
- The Settings dialog carries a "Download updates automatically" switch, persisted as standing consent, default off. When on, a successful background check may call the same `startDownload` path with `meteredConsent = false`; the transfer still requires connected, unmetered, non-roaming network, a verified policy, an eligible cohort and version floor. Turning it off stops future automatic starts and never cancels an in-flight attempt.
- Installing remains a separate user action under R08 and §6 step 7 (fresh policy before commit). No notification can commit an install.

## 9. Error Handling

| Event | Durable outcome | Required response |
|---|---|---|
| First metadata 404 | `NO_STABLE_POLICY` | Display no published update; retry only on normal schedule/manual check. |
| Network/TLS/timeout | `NETWORK_UNAVAILABLE` / `NETWORK_TIMEOUT` | Keep existing attempt; bounded WorkManager backoff; no stale authorization of new commits. |
| 403/429/5xx | `HOST_THROTTLED` / `HOST_UNAVAILABLE` | Respect `Retry-After` and retry bounds; never flood release host. |
| Bad envelope/signature/schema | `POLICY_INVALID` | Reject before cohort selection or APK download; no retry loop for same response. |
| Lower `policySequence` / conflicting equal sequence | `POLICY_ROLLBACK` / `POLICY_CONFLICT` | Reject; keep higher verified floor. |
| Signed halt or zero rollout | `HALTED` / `NOT_IN_ROLLOUT` | Hide new download/install actions; do not abandon already committed sessions. |
| Fresh pre-commit policy unavailable | `WAITING_FOR_POLICY_CONFIRMATION` | Keep verified APK and offer Retry; never commit offline against stale authority. |
| Wrong signed APK/package/SDK/version | `RELEASE_INCOMPATIBLE` | Reject artifact; never install. |
| Missing or unusable DownloadManager destination | `DOWNLOAD_DESTINATION_UNAVAILABLE` | Explain storage/service issue; no fallback to public Downloads. |
| Network pause, metered denial, roaming | `DOWNLOADING_PAUSED` / `AVAILABLE` | Preserve owned download ID or do not enqueue. |
| APK size or SHA-256 mismatch | `APK_INTEGRITY_FAILURE` | Delete only owned bad artifact and session; one explicit clean retry permitted. |
| No install-source permission | `NEEDS_INSTALL_PERMISSION` | Guide to official OS settings on user interaction; recheck after return. |
| Restricted-settings or blocked installer UI | `INSTALL_UI_BLOCKED` | Do not weaken protection; review authorized scoped allowance/OS prompts. |
| Storage full while staging | `INSUFFICIENT_STORAGE` | Close/abandon owned uncommitted session; show actionable storage guidance. |
| `STATUS_PENDING_USER_ACTION` | `AWAITING_USER_CONFIRMATION` | Present platform UI from foreground or user action; do not auto-cancel. |
| User explicitly declines OS prompt | `CANCELLED` only on confirmed abort | Preserve app data; allow new user action later. |
| Blocked by Play Protect/device policy | `INSTALL_BLOCKED` | Report platform decision; never instruct disabling protection/scanning. |
| Developer verification problem | `INSTALL_VERIFICATION_BLOCKED` | Report available official reason, status/extra when supported, and owner support route. |
| Callback absent / process killed | `UPDATE_STATUS_UNKNOWN` | Compare installed version and session state before retry/cleanup. |
| Service not running after update | `PROTECTION_RESUME_REQUIRED` | Display neutral repair notice; do not misreport healthy state or suppress partner status. |
| Incompatible signer or lower APK version | `INSTALL_CONFLICT` | Do not uninstall or downgrade; require publisher fix-forward/data-safe owner recovery. |

Retry only transient failures. Never turn a pending system request into `FAILED` based on elapsed time alone. Error reporting and state transitions are idempotent; cancellation never affects unrelated work.

## 10. Security Requirements

1. Authenticate policy by one pinned ECDSA P-256 SPKI public key and signature over exact decoded bytes. Pin the production trust anchor in app code; never accept public keys delivered by an untrusted manifest.
2. Require monotonic policy sequence and nondecreasing trusted release floor, correct app ID, `stable` channel, bounded payload, signed rollout and hash-authenticated immutable APK reference. **Do not reset version or policy floors after a manual downgrade.**
3. Keep application APK signing and manifest signing keys separate and offline. Back up each in two encrypted independent locations and perform recovery drills. Rotate the manifest key through a higher-version same-signer app update; define compromise response in the private release runbook.
4. Never perform custom `SigningInfo` certificate-array equality checks as a final signing decision. Android PackageManager/PackageInstaller enforces update-signature compatibility. Verify published APK with `apksigner` in the owner pipeline.
5. Download into app-specific storage and verify both final download hash and bytes staged into the installer session; no public directory, auto-open notification or exported FileProvider fallback.
6. Installer callback receiver must be explicit and non-exported, correlated with an owned persisted session/attempt and idempotent. Mutable PendingIntent for session callback; no background activity trampoline.
7. Do not disable or broadly bypass blocking, VPN, Accessibility, Play Protect, restricted settings or Android permission controls. Any short-lived OS-install-screen allowance must be tied to an authenticated user-initiated attempt and honor existing partner authorization.
8. Keep signed policy/rollout host free of private code; the public release-only repo contains distributable assets/policy only. No client REST token, release secret, signer private key, account/partner data or sensitive events in release requests.
9. Do not leak local cohort identifiers, installer session IDs, recovery content or account identifiers in public notifications. Release notes are rendered as non-executable plain text.
10. Register the production package and signing certificate with the official Android Developer Console **full-distribution path** before public unrestricted rollout. Limited-distribution registration is for a genuinely limited, individually authorized set of at most 20 devices and is not the public release solution.
11. Exclude ephemeral updater state from backup/transfer; validate installed version, IDs and session ownership after process restart/restore. No automatic trust-floor reset on downgrade.
12. If GitHub repo is renamed, moved or suspended, do not accept a new arbitrary remote host. Use the documented externally distributed, same-signer higher-version APK recovery path.

## 11. Performance Requirements

- Metadata envelope <= **32 KiB**, decoded payload <= **16 KiB**. Perform network, parsing, signature verification and hashing off the UI thread; use bounded read/timeouts.
- Enforce `MAX_UPDATE_APK_BYTES = 250L * 1024 * 1024` as a **single release-build configurable constant** shared by publisher and client, not a server-controlled override. Refuse larger artifacts at publication and before download. Raising the limit requires a verified app release.
- Never buffer an entire APK. Use a 64 KiB copy/hash buffer, verify exact signed size/hash before staging, and hash counted bytes during `PackageInstaller` staging.
- Use DownloadManager for transfers; no new foreground dataSync worker/service. If a future custom transfer is separately approved, specify Android 14+ service type/permission and Android 15/16 job limits in its own plan.
- Check sufficient storage for download + session staging + a conservative margin; no fixed multiplier is guaranteed. Catch actual storage failures and clean only updater-owned files.
- Persist progress no more often than about once a second and durable phase transitions immediately; avoid synchronous protection checks or network work during cold start.
- Manual successful-check throttle: five minutes; periodic discovery: 24-hour repeat with two-hour flex and OS-deferred execution. Pre-commit revalidation is not subject to manual-check throttling.
- Only one active downloaded artifact and one owned install session; purge terminal leftovers safely on next reconciliation.

## 12. Testing Requirements

**Automated JVM:** exact OpenSSL DER/JCA fixture, corrupt signatures, wrong pinned key, malformed Base64/JSON/size/type, invalid URL, wrong package/channel/SDK, replayed lower policy, equal-sequence changed payload, signed halt, 0/5/25/100% rollout, cohort determinism and cross-release salt, incorrect device clock, max APK size boundary, manual downgrade floor retention, nullable storage and independent check/attempt state.

**Android integration:** WorkManager initializer/DI merged-manifest check, main-process-only scheduling, backup exclusion, DownloadManager status reconciliation, missing callback, process death before saving download ID, no public download fallback, notification privacy, changing metered/roaming network, corrupted downloaded file, APK preflight, installer-session `session.fsync(out)` with the actual openWrite stream, duplicate/stale status intents, fresh pre-commit `HALT`, and protected UI allow-window expiry.

**Device E2E acceptance:** Use release-signed version N → N+1 (not a reinstall), confirm original signing ID, data persistence, actual prompted install, conditional unattended behavior where OS permits, negative tests for wrong-signer and wrong-package APK, permission denial/revocation, low storage, app kill/reboot, Android developer verification prompts, blocked Play Protect install and recovery.

**Protection-critical tests:** Enable representative Accessibility, blocking overlay, VPN/filter and partner consent state using synthetic accounts. Verify Settings and system installer screens are not wrongly blocked; confirm tightly scoped allowance cannot be used to open unrelated blocked screens. On replacement, verify permissions, Accessibility state, VPN status, DB access, rules/heartbeats and truthful partner reporting. Specifically test Android 13+ restricted-settings reenable behavior on sideloaded app self-update. If restored protection requires user action, verify a neutral in-app/notification recovery instruction, not a prohibited background FGS start.

**Supported-API risk matrix:** Run core emulator tests on API 26, 28, 31, 33, 34, 35, 36 and 37 if images are available. Prioritize real-device full signed N→N+1 with **one Samsung and one non-Samsung/OEM family** including Android 13+; record unavailable API images and any unsupported claims. A missing mandatory safety test blocks rollout to that unverified device category rather than creating fictional pass evidence.

**Release-policy rehearsal:** Publish immutable artifact prerelease, then policy release explicitly designated latest; confirm signed 5% cohort, 25%, 100%, and separate higher-policy-sequence `HALT` behavior. Verify `latest` endpoint after each publication, correct APK URL hash, stale CDN behavior, loss of network at commit, and manual recovery via higher-version same-signer APK. An already committed session must not be silently abandoned by a newly observed halt.

## 13. Deployment Requirements

1. **Signing identity:** Inventory existing tester install signing fingerprints and current `applicationId` before distributing an updater-bearing build. Keep production identity stable. Create a permanent owner-controlled signing keystore only if none exists; back it up twice and prove recovery. Never instruct users to uninstall to fix an incompatible signing identity.
2. **Public distribution repository:** Provision and verify a **separate public releases-only repository** under an owner-controlled GitHub account. Pin its owner/repo constant in the updater release build. Keep source, signing material and internal automation private. Verify unsigned anonymous reads of the release URL.
3. **Build:** CI produces repeatable unsigned/unpublished candidates from a pinned source revision. Sign vetted release APKs on owner-controlled secure signing environment; validate certificate, version, APK structure, min SDK, package identity, hash and supported platform signatures with `apksigner`.
4. **Manifest signing:** Produce policy JSON from the exact signed APK, version-specific artifact URL, byte length and digest. Sign exact JSON bytes offline with `primary-v1`; build the single envelope and run client-verifier fixture before upload. Publisher refuses any non-monotonic policy sequence or attempted Android `versionCode` downgrade.
5. **Artifact publication:** Enable GitHub immutable-release settings. Publish each **artifact** as an immutable **prerelease** tagged `artifact-v<versionCode>`, containing the exact signed universal APK. Confirm it downloads anonymously from the version-pinned URL and matches the signed digest. Do not put stable `latest` discovery metadata in artifact releases.
6. **Policy publication:** Publish a separate immutable **non-prerelease** `policy-<zero-padded-sequence>` release containing only the signed `update-manifest.json`, explicitly designate it **latest**, and verify `/releases/latest/download/update-manifest.json` resolves to this exact signed envelope. Never modify immutable releases. Changes to rollout percentage or halt require a **new, higher `policySequence` release**, which can reference the same artifact or contain only HALT. Publisher must refuse to promote until all external checks pass.
7. **Cohort progression and halt:** After internal release-signed QA, publish 5% `OFFER`; then publish signed 25% and 100% policies only after actual opt-in tester feedback and protection health checks. To halt, publish a new `HALT` policy with a higher sequence. Existing clients block **new** installs after they fetch it; do not describe this as instant removal of a prior APK or a fully reliable emergency remote kill switch.
8. **Developer registration:** Select **full distribution** in the official Android Developer Console for public MyExodus and register the signing certificate/package before unrestricted release. Limited-distribution accounts may only serve explicitly authorized tester devices within the documented 20-device limit.
9. **Recovery:** Bad versions are repaired by a tested, same-signing-identity APK with a **higher** versionCode. If the updater itself is broken or release repo moves, offer a separately documented manual install path, never a downgrade or uninstall without a data-preservation plan.
10. **Release ownership:** Keep GitHub MFA/least privilege, local signing approval, clean-machine postpublication verify, backups/restore drill and a private key-compromise incident runbook. Release approval captures source revision, asset hash, selected cohort, policy sequence, OS device results and protection-health checks.

**Official implementation references:**
- [Android PackageInstaller.Session (including `fsync(OutputStream)`)](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session)
- [Android PackageInstaller.SessionParams (self-update, package source)](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams)
- [Android PackageInstaller status and developer-verification extras](https://developer.android.com/reference/android/content/pm/PackageInstaller)
- [Android DownloadManager.Request](https://developer.android.com/reference/android/app/DownloadManager.Request)
- [WorkManager configuration](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration)
- [GitHub REST conditional-request rules](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)
- [GitHub unauthenticated rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
- [Android developer verification and distribution models](https://developer.android.com/developer-verification/guides)
- [Android restricted settings](https://support.google.com/android/answer/12623953)
- [GitHub immutable releases](https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/establish-provenance-and-integrity/prevent-release-changes)

## 14. Monitoring Requirements

Keep only **local, bounded, privacy-preserving** updater diagnostics: up to 200 entries or 14 days, whichever is smaller. Record verified policy sequence, mode, release version code, API level, anonymized attempt-local error code, coarse duration/bytes, DownloadManager status and installer status. Do not log cohort ID/hash, partner/account identity, screen activity, protection events, sensitive app purpose, IP address or signing secrets. No automatic Crashlytics/analytics dependency.

Settings displays installed version, last authenticated metadata fetch, currently offered release/rollout status, last installation outcome and actionable failure. Provide **Copy diagnostic summary** explicitly initiated by user and excluding identifiers. A week without successful verification displays `CHECK_STALE` rather than a false assertion that the installed app is up to date.

Release-owner monitoring verifies that the public latest policy points to the intended signed envelope, every referenced APK downloads anonymously and hashes correctly, the rollout percentage matches release approval, and HALT promotion can be exercised. Track opt-in tester update success and post-replacement protection-health evidence manually; do not infer population-wide safety from absence of telemetry.

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| APK signing key loss/compromise | Low | Critical | Two encrypted independent backups, recovery drill, restricted offline custody | Signing rehearsal and release review |
| Manifest key loss/compromise | Low | High | One backed-up offline key; rotate only through trusted app update | Key custody audit and verified signatures |
| Signed stale/frozen policy | Medium | High | Monotonic sequence, signed latest check, stale warning; no claim of perfect freeze prevention | Policy rollback/stale-check records |
| Wrong/mixed release asset | Medium | High | Immutable version-pinned APK, exact signed size and SHA-256 | Preflight/staging hash failure |
| Defective signed release | Medium | Critical | 5/25/100% rollout, signed halt for new commits, fix-forward | Tester acceptance, post-install checks |
| Protection blocks installer Settings/UI | Unknown until repository test | High | Narrow 10-minute approved allowance; no global disable | Real blocked-UI test |
| Restricted settings re-applied on update | Device dependent | High | Android 13+ self-update permission regression tests; publisher block if unsafe | API33+ restricted-settings test |
| VPN/Accessibility off after replace | Device dependent | Critical | Health check, lawful restoration, neutral prompt, truthful partner state | Post-package-replace test |
| Multiple process coordination | Unknown until repository inspection | High | Main-process single owner; correct storage process model | Process/manifest inventory and race tests |
| Download interrupted or storage unavailable | Medium | Medium | DownloadManager status recovery, private destination, owned-only cleanup | Transfer/storage tests |
| Permission denied or Play Protect policy | Device dependent | High | Official user prompt/blocked status, no bypass instruction | Installer status code and real devices |
| Developer-verification rollout | Region/device dependent | High | Public full-distribution registration and device acceptance | Certified-device install tests |
| GitHub repo rename/suspension | Low | High | Dedicated repo; manual same-signer higher-version path | Publisher endpoint probe |
| False partner protection-off or silent gap | Device dependent | High | Actual state reporting and documented maintenance reason only when real | Partner synthetic-account QA |
| User data lost after signing mismatch/uninstall | Low | Critical | Signing identity gate, no auto-uninstall, migration test | Signed N→N+1 rehearsal |

## 16. Open Questions

**No competing implementation architectures remain.** The following *real repository and owner-controlled release facts* must be resolved by Step 1 or before public release; the agent must not invent them:

1. **Actual existing app facts:** `applicationId`, `minSdk`, `targetSdk`, source module, build flavors, process layout, existing WorkManager/Hilt/Persistence and backup/data-extraction setup. Read from repository.
2. **Signing continuity:** Existing tester signing certificate fingerprints, whether initial public tester installations exist, and availability/recoverability of their production APK signing key. Verify from artifacts/devices.
3. **Protection hooks:** Whether Accessibility blocks system installer/settings screens; how VPN/filter/accessibility services recover after package replace; which partner-alert/approval rules apply. Inspect code and run explicit device tests; **do not silently waive protection**.
4. **Public releases repo identifier:** Create/provision the dedicated public releases-only repo and fill the fixed `UPDATE_RELEASE_REPOSITORY` release-build constant. Never derive from a private source repo by assumption.
5. **Publisher secrets and artifact identity:** The real pinned public verification key, offline backups and signing owner, exact universal APK name, monotonic version codes, SHA-256 and policy sequence. Generate from the verified signed artifacts and owner environment.
6. **Device support and rollout owner:** Actual supported OEM/API devices available for mandatory protection tests, owner of release promotion and responder to a HALT/failed install incident. Record in private release runbook.

**Publication blocker:** Any unresolved item affecting signing continuity, blocker/installer UI access, post-update protection, public endpoint identity or official developer registration must be resolved before distribution; continue only safe implementation/testing while it remains unknown.

## 17. Acceptance Criteria

- [ ] Dedicated public **releases-only** repository is anonymously readable; private source and keys remain private; signed policy `latest` resolves correctly without an API token.
- [ ] A signed OFFER for the correct installed application produces `AVAILABLE` only when version and cohort are eligible; wrong/malformed data cannot trigger a download.
- [ ] Verified lower-policy replay, same-sequence changed content, bad signature, invalid APK URL and wrong package are rejected. App downgrades never lower the highest trusted sequence or release floor.
- [ ] Signed 5%, 25% and 100% policies preserve deterministic cohorts, and a newer HALT policy prevents new download/commit after retrieval; older cached policy cannot override HALT.
- [ ] Background checks do not clobber an active attempt; updater initializes once in main process, resumes after process death, and excludes stale session IDs from device-transfer backup.
- [ ] APK transfer begins only after explicit consent — a per-release Download tap, or the standing auto-download opt-in acting on an offered, eligible version — respects metered acknowledgement/no roaming (automatic starts require unmetered, non-roaming), uses app-specific storage and never falls back to public Downloads.
- [ ] Downloaded and staged byte counts and SHA-256 are exact; `session.fsync(out)` if called uses Android's official `PackageInstaller.Session.fsync` and no unsafe FileOutputStream cast.
- [ ] Fresh signed policy is reverified before first session commit; network failure or HALT defers new commit without touching already committed legitimate sessions.
- [ ] Android permissions, installer callback PendingIntent and confirmed user action work; conditional unattended self-update is never presumed guaranteed.
- [ ] Blocker does not obstruct authorized OS installation; any temporary allowance is narrowly scoped/approved and expires without allowing unrelated evasion.
- [ ] Android 13+ restricted-settings and package source paths, service reauthorization and Play Protect blocks are tested and never circumvented programmatically.
- [ ] Release-signed N→N+1 preserves private app data, protection rules, enabled service state or honest repair prompts, and partner status reflects actual protection state.
- [ ] Real-device signed upgrade, recovery after interruption, publication cohort workflow and operator HALT/fix-forward runbook pass before public launch.

## 18. Definition of Done

- [ ] All Sections 7–8 implementation gates complete; PR documents actual repository paths, process model and any minimal protection hook without unrelated refactoring.
- [ ] Signed policy schema/fixtures, stable cohort and HALT logic, rollback-floor retention, check/attempt state, DownloadManager recovery, PackageInstaller statuses and installer-session `fsync` receive passing tests.
- [ ] Required physical-device Android N→N+1 upgrade, protection continuity, Android 13+ restricted settings, permission denial, process death and pending confirmation tests are recorded with real evidence.
- [ ] App and manifest retain existing IDs/signing continuity, correctly non-exported status receivers, no privileged installer permissions, and no broad battery/accessibility/Play Protect bypass.
- [ ] Dedicated public releases-only repo, immutable versioned assets, higher-sequence signed policy and 5% rollout/halt publication rehearsal are externally verifiable.
- [ ] Production and manifest signing keys are separately stored, offline encrypted backups tested, APK signer verified, and developer full-distribution registration completed before public rollout.
- [ ] Sensitive updater/protection data never leaves the app except for user-initiated sanitized diagnostics, and notifications disclose no personal context.
- [ ] Owner approves a signed release checklist, protection-health evidence, manual recovery/fix-forward procedure and incident halt policy runbook.

**A successful download or installation callback alone is not completion. The updated app must remain usable, preserve user data, and maintain or truthfully report the state of its protection features.**
