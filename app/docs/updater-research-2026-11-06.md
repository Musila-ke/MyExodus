# In-App Update Channel, Research Dossier (2026-11-06)

**Feature:** self-update for a direct-APK Android app via GitHub Releases, with a Settings "Check for updates" surface.
**Audience:** the implementation engineer (this session), and any future maintainer changing the channel.
**Status of this document:** research record. The implementation contract is `app/docs/app-update-channel.md`; ADR #171 carries the decision record.
**Sources fetched for this dossier:** GitHub Docs (linking to releases, REST releases, REST rate limits), Android developer reference (`PackageInstaller`, `PackageInstaller.Session`, `PackageManager`, `PackageInfoCompat`, unknown-sources settings action), Android 12 behavior changes. Claims not directly fetched are marked as such. Where the evidence is missing, this document says so instead of guessing.

---

## 1. Executive Summary

The update channel is a trust problem wearing a download problem's clothes. GitHub Releases is only transport. The actual security boundary is a signed manifest pinned in the APK plus a certificate check of the downloaded APK against the installed app. GitHub's own docs support a stable, token-free URL to the latest release asset (`https://github.com/<owner>/<repo>/releases/latest/download/<asset>`), which lets the app avoid the GitHub REST API and its 60-requests/hour unauthenticated cap entirely. [High confidence, GitHub Docs: "Linking to releases"; "Rate limits for the REST API"]

The install itself is Android's `PackageInstaller` session flow. Two platform details dominate the failure surface: (1) the session status receiver's `PendingIntent` must be mutable on targetSdk 35+ or `Session.commit()` throws, and this app's targetSdk is 36, so a naive implementation fails 100% of installs; (2) the status broadcast is asynchronous and the process may be dead when it arrives, so install success cannot be the only reconciliation path. [High confidence, `PackageInstaller.Session` reference; Android 12 behavior changes]

The recommended architecture, proven parts only:

- Daily WorkManager check of a signed manifest at the stable GitHub URL. Fail closed to "channel not configured" when no key is embedded.
- Download only on explicit user action (the background check is cheap; the APK is ~50 MB).
- Verify, in order: manifest signature, package name, archive versionCode equals manifest, manifest versionCode greater than installed, SHA-256 of the file, signing-certificate set equal to the installed app's. Only then hand to `PackageInstaller`.
- One explicit install confirmation from the system; one in-app confirmation before that. No silent installs.
- Reconcile by comparing `BuildConfig.VERSION_CODE` with the last seen version on every launch, because the status broadcast is best-effort.

Two owner-gated facts block a working end-to-end channel today: **no release signing config exists** (so no installable release APK can be published), and **no manifest-signing key is pinned** (so the channel must ship fail-closed). Testers who install debug-signed APKs cannot later in-place update to release-signed APKs; Android treats them as different apps and requires uninstall, which loses data because `allowBackup=false`. Therefore the signing-key decision must be made before distributing any APK to testers, not after.

**MVP vs production-ready, explicitly:** MVP is manifest verification + download + verified install + Settings row. Production-ready additionally needs release signing custody with backups, a release runbook that publishes normal (non-prerelease) releases, a recovery path for a bad release, and device evidence on at least one OEM skin. This dossier distinguishes the two throughout; the implementation in this session targets MVP plus the runbook scaffolding, with the signing decision surfaced to the owner.

---

## 2. Architecture Options and Recommendation

### Option A, Play Store in-app updates (`PlayCore` `AppUpdateManager`)
**Why rejected:** the app is not distributed on Play (ADR #15) and cannot use Play's update rail. Not viable regardless of technical merit.
**Evidence:** project decision record, ADR #15; also Play Core docs state the API applies to apps distributed through Google Play.

### Option B, GitHub REST API polling (`api.github.com/repos/.../releases/latest`)
**What it is:** fetch release metadata as JSON, read `assets[].browser_download_url`, download.
**Advantages:** structured metadata; GitHub computes per-asset digests server-side (`digest` field), useful as an integrity cross-check.
**Disadvantages:** unauthenticated calls are limited to 60 requests/hour per originating IP, so a retry storm or a shared NAT can exhaust it; the response contains no signature, so a compromised repository produces a malicious update unless the manifest is signed independently (which makes the API's metadata redundant); adds a JSON schema dependency on GitHub's API evolution.
**Evidence:** GitHub REST docs (release object, `digest`); rate-limit docs (60/h unauthenticated, REST API scope). [High confidence]
**Verdict:** rejected for the update check. The same signed-manifest idea works on any transport, so choose the transport with no quota.

### Option C, GitHub stable release-asset URL plus a signed manifest (RECOMMENDED)
**What it is:** the release publishes two assets with fixed names: `update-manifest.json` (signed envelope) and the versioned APK. The app fetches `https://github.com/Musila-ke/MyExodus/releases/latest/download/update-manifest.json`, verifies the signature against a public key pinned in the APK, and reads versionCode, APK asset name, SHA-256, size, and notes.
**Advantages:**
- Stable URL documented by GitHub for public repos, no token, no API quota.
- "Latest" excludes drafts and prereleases (REST definition: most recent non-prerelease, non-draft, sorted by `created_at`), which conveniently prevents a staged beta from reaching users; the release process must publish normal releases.
- Transport-independent trust: a GitHub account takeover that swaps the APK still fails manifest verification. GitHub availability affects only the check, never app functionality.
- No new dependency; `HttpURLConnection`, `org.json`, and JCA are already used or available.
**Disadvantages:**
- One more file to build and sign per release (a small script).
- The stable URL's exact prerelease behavior for the web redirect is documented less precisely than the REST endpoint (see Gaps).
- No version history or channel selection; one "latest" for everyone.
**Evidence:** GitHub Docs "Linking to releases" (stable latest asset link, no auth); REST releases docs ("latest" definition). [High confidence for public repos; Medium for exact redirect semantics when the newest release is a prerelease]
**Verdict:** recommended.

### Option D, custom backend (existing Firebase Functions / Storage)
**What it is:** a callable or HTTPS endpoint returns the manifest; storage hosts the APK.
**Advantages:** full control over channels, staged rollout, and analytics.
**Disadvantages:** an always-on backend for a handful of testers; new auth surface; a second trust path to secure; costs and maintenance. ADR #171 explicitly retired Remote Config in favor of GitHub Releases; re-introducing a backend contradicts that direction.
**Verdict:** rejected on operational cost and decision conflict.

### Option E, Obtainium / F-Droid style external updater
**What it is:** user installs a third-party updater that watches the repo.
**Advantages:** zero updater code in the app.
**Disadvantages:** requires another app and a second install grant; update UX leaves the product; still needs release-asset naming discipline. Not a substitute for the owner's requested in-app row.
**Verdict:** rejected.

**Recommendation:** Option C, with the manifest verified before any APK bytes are trusted, and the APK installed only through `PackageInstaller`.

---

## 3. Best Practices

1. **Verify before download, verify again before install.** The signed manifest must be valid before the app spends the user's data; the downloaded file must be re-verified (hash, size, package, version, signer) before `PackageInstaller`. Checking the manifest alone is insufficient because the CDN payload is a separate object. [Why: two distinct compromise paths, manifest and asset. Evidence: ADR #171 trust contract; standard update-system layering.]
2. **Pin the trust anchor in the installed app.** The public key lives in the APK; a new release can only be authorized by a manifest signed with the corresponding private key. This breaks the "malicious release authorizes itself" loop. [Why: repository compromise should not equal app compromise. Trade-off: key rotation requires a shipped build that trusts the new key. Mitigation: key custody and, if ever needed, ship a release containing both keys before rotating.]
3. **Compare versionCode, never versionName.** String comparison breaks on `1.10` vs `1.9`. Android itself orders installs by versionCode. [Evidence: `PackageInfo` version fields; GitHub release practice. High confidence.]
4. **Require the signer set to match the installed app.** The OS installer enforces same-signer for updates anyway, but checking first turns a generic install failure into a clear, actionable error and blocks a wrong-key APK before the user is prompted. [Evidence: `PackageInfoCompat` docs warn `getSignatures` returns "some" certificates and is not for verification; `hasSignatures` with exact matching is the documented helper; `GET_SIGNING_CERTIFICATES` is the API 28+ path.]
5. **Mutable, explicit status `PendingIntent` on targetSdk 35+.** `Session.commit()` throws if the status receiver comes from an immutable `PendingIntent` when the caller targets 35+. Use `FLAG_UPDATE_CURRENT or FLAG_MUTABLE` on API 31+, `FLAG_UPDATE_CURRENT` below (FLAG_MUTABLE was added in API 31). [Evidence: `PackageInstaller.Session.commit` reference; Android 12 behavior changes made mutability explicit. High confidence. This app: targetSdk 36.]
6. **Stream to a `.part` file, hash while streaming, fsync, rename.** Avoids a half-written file being mistaken for complete, and keeps the final path atomic. [Why: network interruptions are normal. Trade-off: two file operations; negligible.]
7. **One in-flight install state.** Disable the action while checking, downloading, or installing. Two concurrent sessions waste storage and produce confusing duplicate status broadcasts. [Why: double-tap is the most common user input error.]
8. **Reconcile on launch, do not wait for the broadcast.** Compare `BuildConfig.VERSION_CODE` with the stored last seen version at startup. The process is routinely killed during commit; the broadcast is delivered to a manifest-declared receiver, but treating it as the only source of truth makes the UI depend on a best-effort channel. [Evidence: `PackageInstaller.Session` docs: "The process may be killed at any time"; broadcast delivery to a stopped package is not documented (see Gaps).]
9. **Background check, foreground download.** A daily check costs a few KB; a 50 MB download on a metered connection without consent is hostile. The system installer prompts regardless, so a background download saves time but not consent. [Trade-off with the earlier owner note that mentioned background download; rationale recorded in Final Recommendations. Evidence: `SessionParams.setRequireUserAction` and installer prompt behavior, Medium confidence on OEM specifics.]
10. **Fail closed and say so.** If no manifest public key is configured, the row says the channel is not set up. Never fall back to "trust the GitHub asset" or "verify only TLS". [Why: silence would look like "up to date" forever; the owner would not know the channel is inert. Evidence: ADR #171 trust contract.]
11. **No new identifiers, no update telemetry.** The check is a plain HTTPS GET. Do not add a device ID, install ID, or analytics event; the Settings crash-report promise (no app names, no usage times, no limits) must not be weakened by differential update behavior. [Project constraint, memory `project_next_session_queue`.]
12. **Keep the APK in app-private cache and clean it.** `cacheDir` is private; staged APKs older than a TTL and stale `.part` files are deleted on each check. [Why: 50 MB per version adds up; system cache clearing must not crash the flow.]
13. **Publish only normal releases on the production channel.** Drafts and prereleases are excluded from "latest" by definition, so marking a hotfix as prerelease silently strands users. The runbook makes this explicit. [Evidence: GitHub REST "latest" definition. Medium-high for the web redirect, see Gaps.]
14. **Never lower versionCode, never replace an asset under the same release.** Both create inconsistencies between what the manifest says and what clients already downloaded or installed. Ship a new release with a higher versionCode. [Why: monotonicity is what makes replay and rollback checks trivial.]

---

## 4. Security Analysis

**Trust model.** GitHub is transport, not authority. Authority is the manifest signing key pinned in the installed APK, plus Android's own signature enforcement at install time. Everything in this section follows from that split.

**Threat, network attacker (MITM, hostile Wi-Fi, DNS spoofing).**
- Scenario: serve a malicious manifest or APK.
- Controls: HTTPS to github.com; manifest signature verification; APK SHA-256 from the signed manifest; signer-certificate equality with the installed app. The certificate check also protects against a malicious *signed* APK by someone else, which the OS would reject anyway.
- Residual risk: an attacker who can serve a manifest signed by the real key. That requires key compromise, not network position.
- Certificate pinning of github.com: deliberately not used. It adds operational fragility (CDN rotation) without protecting the asset itself, and the signed manifest is a stronger, offline-verifiable control. [Evidence: update-system layering; standard OWASP MASVS-NETWORK guidance favors HTTPS plus independent integrity for this case.]

**Threat, compromised GitHub repository or account.**
- Scenario: attacker with repo write access replaces the APK asset and the manifest asset.
- Outcome: the app receives the malicious manifest, fails signature verification, and records INVALID_MANIFEST. The malicious APK is never downloaded. If the attacker also deletes releases, the check returns "no release"; the app remains functional.
- This is the concrete reason the manifest signature exists; a GitHub-only integrity check (the API `digest` field) would not help here because the attacker controls it too.

**Threat, downgrade and replay.**
- Scenario: serve an older signed manifest, or a manifest pointing at an older APK.
- Controls: `manifest.versionCode > installed` is required; the installer rejects downgrades independently; there is no "allowDowngrade" flag in the session params. Replaying an old manifest is a no-op.
- Manifest expiry is therefore unnecessary: a signed manifest can only ever move a client forward.

**Threat, path traversal / asset-name injection.**
- Scenario: a manifest names `../../databases/x` or an absolute path.
- Controls: asset names are validated against `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` ending in `.apk` before being joined to the base URL; file writes use fixed paths in `cacheDir/updates`, never the manifest string as a path. [Boundary validation; the manifest is untrusted input even when signed, because mistakes and future key misuse are possible.]

**Threat, malformed or hostile APK.**
- Scenario: a validly hashed but malformed archive triggers parser problems.
- Controls: hash is compared during streaming before the archive is parsed; the system parser (`getPackageArchiveInfo`) sees only bytes whose SHA-256 matched a signed manifest. Size cap 128 MiB prevents disk exhaustion. `PackageInstaller` performs its own validation; our checks are pre-filters, not replacements.
- Ordering matters: do not call `getPackageArchiveInfo` on unverified bytes. If a future change moves parsing earlier, it must still be behind the hash check.

**Threat, PendingIntent hijack.**
- Scenario: another app intercepts the install status and forges success.
- Controls: the status `PendingIntent` targets an explicit, non-exported receiver in this package. `FLAG_MUTABLE` is required by the platform for `commit()` at this targetSdk, but the intent has an explicit component, so no other app can receive it. The receiver still validates `EXTRA_STATUS` and never trusts a "success" without a version reconciliation on next launch.
- Note: mutable PendingIntents are a known footgun only when implicit or leaked. Ours is neither. [Evidence: PendingIntent guidance; `PackageInstaller.Session.commit` requirement.]

**Threat, unknown-sources permission misuse.**
- The app requests `REQUEST_INSTALL_PACKAGES`, which allows prompting the user to grant install permission *to this app only*. It does not grant silent install rights; the system prompt remains. This is inherent to any self-updater outside Play. [Evidence: `PackageManager.canRequestPackageInstalls`; `ACTION_MANAGE_UNKNOWN_APP_SOURCES` docs; ADR #171 records the unavoidable Android 13+ friction. High confidence for the mechanism, Medium for exact wording across OEMs.]

**Threat, signing-key compromise (the worst case).**
- Scenario: the manifest private key leaks. An attacker can authorize any APK. For an accountability app, a malicious update could disable blocking and monitoring.
- Controls: offline key, passphrase, separate machine or at least separate file with 0600 permissions, two cold backups (ADR #171 contract), never committed, never uploaded. The key must never be the same as the APK signing key.
- Detection: there is no revocation channel. The only recovery is shipping a build with a new pinned key, which itself requires the old key to sign that release, or a manual uninstall (data loss). This is a hard, honest limit of pinned-key update systems. [Documented as a known risk; mitigation is custody, not code.]

**OWASP mapping (realistic items only):**
- M5 / MASVS-NETWORK: insecure communication. Controlled by HTTPS plus signed manifest; no cleartext.
- M8 / MASVS-RESILIENCE (misconfiguration, insufficient binary protections): the install flow and mutable PendingIntent are the concrete misconfiguration risks; unknown-sources handling is the permission risk.
- M7 / code quality: status-code handling and state machine errors; addressed by the explicit failure enum and tests.
- MASVS-STORAGE: staged APK kept in private cache; no sensitive data in the manifest.

---

## 5. Reliability Analysis

| Failure | How it appears | Detection | Recovery |
|---|---|---|---|
| Network unavailable | Check throws `IOException` | Failure enum `OFFLINE`; row shows "Couldn't check" | Next daily cycle; manual retry; app remains fully functional (ADR #171 requirement) |
| GitHub outage / 5xx | Non-200 response | `SERVER` | Next cycle; manual retry |
| No release published yet | HTTP 404 | `NO_RELEASE`, treated as "up to date" with a neutral note | Normal state before the first release |
| Channel not configured (no pinned key) | Constant empty | `NOT_CONFIGURED` | Never contacts the network; row states the channel is not set up |
| Invalid / unsigned / tampered manifest | Verification fails | `INVALID_MANIFEST`; previous known state retained | Next cycle; no partial state |
| Download interrupted | Stream throws; `.part` remains | `DOWNLOAD` | `.part` deleted immediately; retry restarts from zero (no range resume, deliberately simple) |
| Hash or size mismatch | Post-stream comparison fails | `INTEGRITY` | File deleted; retry |
| Signer mismatch | Certificate fingerprints differ | `SIGNER_MISMATCH` | File deleted; user told to reinstall manually (rare, and a key-custody failure would already be in progress) |
| Wrong package name in APK | Archive package differs | `WRONG_PACKAGE` | File deleted |
| Archive version differs from manifest | Mismatch | `VERSION_MISMATCH` | File deleted |
| Downgrade | Manifest version not newer | Treated as up to date; installer would also refuse | No action |
| Unknown-sources permission missing/revoked | `canRequestPackageInstalls()` false | Row offers "Allow installs"; opens the system screen | Recheck on every resume; no reliance on a result intent |
| Install aborted by user | `STATUS_FAILURE_ABORTED` | Receiver maps to a message | State cleared; retry available |
| Install failure (invalid/conflict/incompatible/storage/timeout) | Specific status codes | Receiver maps each to a user-readable reason (no raw status text) | State cleared; retry; stale session abandoned |
| Process death during install | UI state lost | Next launch compares versionCode | If installed, the version changed and the upgrade notice path runs; otherwise state returns to idle |
| Status broadcast lost | No update in UI | Reconciliation on next launch via versionCode | UI corrects itself; broadcast is best-effort |
| Staged APK deleted by system cache cleaner | File missing at install time | File existence check before opening session | Re-download automatically or ask the user to retry |
| Double tap on "Download and install" | Second action ignored | Single in-flight state | Button disabled while busy |
| Concurrent worker check and manual check | Both write state | Harmless (read-mostly); unique periodic work | Last result wins |
| Clock skew | Throttle interval wrong | Timestamps are advisory only | No functional effect; checks may run more or less often |
| Storage full during download or session write | `IOException` | `DOWNLOAD` / install failure | File and session cleaned; message asks the user to free space |
| Bad release published | Users update to it | Manual report / device check | Publish a fixed release with a higher versionCode; raising `minSupportedVersionCode` does not force an update but makes the row's copy urgent |
| XOS aggressive process killing | Background check delayed | WorkManager skips/delays | Manual check in Settings always available; never security-critical |
| Key rotation needed | Old clients reject new manifests | Not detectable remotely | Ship an intermediate release trusting both keys before rotating; otherwise manual reinstall |

**Reliability principles applied:** the app never depends on the updater for core function (ADR #171, plan UN5); every background path has a manual equivalent; every write is derived from a verified source; every failure maps to a specific user-readable state.

---

## 6. Common Mistakes and Failure Patterns

Each entry follows the requested failure-research shape.

### 6.1 Immutable status PendingIntent (the targetSdk trap)
- **What went wrong:** apps built for Android 14+/targetSdk 34+ created an immutable `PendingIntent` for install status; `Session.commit()` throws and every self-update crashes or fails.
- **Why it seemed reasonable:** older sample code used `FLAG_UPDATE_CURRENT` alone, which was sufficient before explicit mutability was required.
- **Root cause:** Android 12 made mutability explicit; the install-status contract requires the system to fill in extras, so the PendingIntent must be mutable. At targetSdk 35+ the platform enforces it at `commit()`.
- **Outcome:** 100% install failure on the newest devices, often only after release.
- **How it could have been caught earlier:** test the commit path on a build with the current targetSdk, not an old sample.
- **Prevention / guard:** build the flags through one helper, unit-test that the helper sets `FLAG_MUTABLE` on API 31+, and keep the targetSdk bump in the release checklist so the test is revisited. This dossier exists partly because the app targets 36 today.

### 6.2 Signature-key change or loss
- **What went wrong:** an app is re-signed with a new key; in-place updates fail with a signature mismatch; users must uninstall and lose data.
- **Why it seemed reasonable:** the key was regenerated on a new machine, or a debug key was used for a "temporary" build.
- **Root cause:** Android identifies an app by package name plus signing certificate. There is no server-side recovery.
- **Outcome:** permanent user data loss; the update channel is dead for every existing install.
- **How it could have been caught earlier:** decide custody before the first distributed build; never distribute debug-signed APKs to testers.
- **Prevention / guard:** stable release keystore, two cold backups, signing runbook, and a pre-release check that the new APK's certificate matches the running app's. This repository currently has **no release signing config**, which is the top operational blocker (see §8).

### 6.3 The "latest" release silently skips prereleases
- **What went wrong:** a maintainer marks a release as a prerelease (or leaves it as a draft); the stable latest URL keeps serving the previous release, so users never see the update.
- **Why it seemed reasonable:** prerelease flags are a normal GitHub workflow for staging.
- **Root cause:** "latest" is defined as the most recent non-prerelease, non-draft release.
- **Outcome:** silent update starvation; tests on the publishing side may pass because the asset exists in the release page.
- **How it could have been caught earlier:** check the stable URL after publishing, not the release page.
- **Prevention / guard:** release runbook step "publish as a normal release" and a post-publish verification that `/releases/latest/download/update-manifest.json` returns the new versionCode.

### 6.4 versionName comparison instead of versionCode
- **What went wrong:** an updater compares version strings; `1.10` sorts below `1.9` lexicographically, so the new build is considered older.
- **Why it seemed reasonable:** versionName is the user-visible version and is human-readable.
- **Root cause:** string ordering is not numeric ordering.
- **Outcome:** updates stop exactly when versions cross a decimal boundary.
- **How it could have been caught earlier:** replay a fixture with `1.9` and `1.10`.
- **Prevention / guard:** the manifest carries `versionCode` as an integer; the checker compares integers only; a unit test covers `9` to `10` and beyond.

### 6.5 Downloading in the background on metered connections
- **What went wrong:** an updater auto-downloads ~50 MB whenever a release appears, including on mobile data, with no consent.
- **Why it seemed reasonable:** "background download" sounds like a better user experience.
- **Root cause:** conflating "check" (cheap, safe to automate) with "download" (expensive, should be user-initiated).
- **Outcome:** data charges, storage pressure, battery use, and user distrust.
- **How it could have been caught earlier:** model the byte cost and the user's connection; require an explicit consent point for large transfers.
- **Prevention / guard:** automatic checks only; download begins on an explicit tap; show size and progress.

### 6.6 Assuming the status broadcast is guaranteed
- **What went wrong:** UI waits for the install status broadcast; the app was killed or the broadcast was not delivered to a stopped package; the UI remains "Installing" forever.
- **Why it seemed reasonable:** the documentation describes the broadcast as the result channel.
- **Root cause:** process lifecycle and stopped-state delivery rules make the broadcast best-effort from the app's perspective.
- **Outcome:** a stuck state that only a reinstall or a new check clears.
- **How it could have been caught earlier:** kill the app during the installer prompt and reopen it.
- **Prevention / guard:** versionCode reconciliation at every launch; a timeout that resets an `INSTALLING` state older than 30 minutes; the receiver remains an optimization, not the source of truth.

### 6.7 Trusting GitHub's digest instead of a signature
- **What went wrong:** an updater verifies the APK against the digest returned by the same API; a compromised repository produces a matching malicious digest and the check passes.
- **Why it seemed reasonable:** GitHub published the digest, so it looked authoritative.
- **Root cause:** integrity against the transport is not authenticity against the publisher.
- **Outcome:** a compromised repo becomes a compromised app update.
- **How it could have been caught earlier:** ask who signs the metadata, not just who hosts it.
- **Prevention / guard:** pinned manifest key; the digest is at most an extra cross-check.

### 6.8 Path traversal from manifest fields
- **What went wrong:** an asset name is concatenated into a file path or URL, allowing `../` or an absolute path.
- **Why it seemed reasonable:** the manifest is produced by the same team, so it was treated as trusted.
- **Root cause:** signed does not mean infallible; parse-side validation is still required at the trust boundary.
- **Outcome:** file overwrite or fetch from an unintended host.
- **How it could have been caught earlier:** fuzz the manifest parser with traversal strings.
- **Prevention / guard:** strict asset-name regex; fixed local paths; unit tests for `..`, `/`, and absolute-looking names.

### 6.9 Stuck "update available" after a manual sideload
- **What went wrong:** the app relies only on its own install flow to update the version it shows.
- **Why it seemed reasonable:** the in-app path is the normal one.
- **Root cause:** state is stored, not derived; the installed version changed outside the app's knowledge.
- **Outcome:** the row keeps offering an update that is already installed.
- **How it could have been caught earlier:** sideload a newer APK and reopen the app.
- **Prevention / guard:** always compare the stored manifest versionCode against `BuildConfig.VERSION_CODE` when rendering; reconciliation on launch.

### 6.10 Unknown-sources grant treated as permanent
- **What went wrong:** install silently does nothing because the user revoked the install permission or never granted it.
- **Why it seemed reasonable:** the grant was obtained once during onboarding.
- **Root cause:** the permission is per-app and revocable; there is no reliable result callback from the settings screen.
- **Outcome:** taps appear to do nothing.
- **How it could have been caught earlier:** revoke the grant and retry.
- **Prevention / guard:** check `canRequestPackageInstalls()` before starting the download, recheck on every resume, and show an explicit "Allow installs" affordance.

---

## 7. Edge Cases (prioritized by likelihood x impact)

**High likelihood, high impact**
1. Unknown-sources permission absent or revoked. Guard: pre-check plus resume re-check plus a settings action.
2. Network interruption mid-download. Guard: `.part` file, streaming hash, delete on failure.
3. User taps the update action twice. Guard: single in-flight state; disabled action.
4. Process killed during install. Guard: launch-time version reconciliation; status broadcast is best-effort.
5. VersionCode is not newer (replay or manual sideload). Guard: integer comparison; no UI claim of "update available".
6. Manifest key not configured (fresh clone or keygen forgotten). Guard: fail closed and say "not configured"; never treat as up to date.

**Medium likelihood, high impact**
7. GitHub repository compromised. Guard: signed manifest; the APK is never fetched on a bad manifest.
8. Malformed/tampered manifest, including asset-name traversal. Guard: strict parser, signature first, regex, size caps, tests.
9. Storage full during download or session write. Guard: pre-download space check (advisory), catch `IOException`, clean up session and files.
10. Signer mismatch (wrong key or key rotation). Guard: certificate-set equality; clear message; no install attempt.
11. Wrong package name in the archive (a fork's APK). Guard: package-name check before install.
12. Bad release already installed. Guard: publish a higher-versionCode fix; document that forced update is not possible without a backend.

**Lower likelihood, medium impact**
13. GitHub outage. Guard: failure state, retry next cycle, core app unaffected.
14. Release created as a draft or prerelease by mistake. Guard: runbook and post-publish verification; document the recovery (edit the release to normal).
15. Clock skew. Guard: timestamps advisory only.
16. Cache cleaner deletes the staged APK between availability and install. Guard: existence check; re-download.
17. Concurrent worker and manual check. Guard: unique work; idempotent state writes.
18. Very slow CDN / large file on a weak connection. Guard: timeouts (connect 15 s, read 30 s), progress, cancel = delete.
19. `getPackageArchiveInfo` returns null on a truncated file. Guard: null is a failure, not a crash.
20. OEM installer customization (XOS/MIUI extra confirmation or scan prompt). Guard: treat as unknown during first device pass; the system prompt is the source of truth; document what was observed.

**Low likelihood, high impact**
21. Manifest signing key compromise. Guard: custody, backups, separation from the APK key; no remote revocation exists (honest limit).
22. APK signing key compromise. Guard: same custody rules; users cannot be protected by the app itself, but same-signer checks at least surface anomalies.
23. Android version behavior change for self-update (see Gaps: Android 14 update ownership). Guard: re-verify on each targetSdk bump; keep the install path isolated in one module.

---

## 8. Testing and Deployment Considerations

**Unit / JVM (must run in the normal gate)**
- Manifest parsing and verification: valid envelope, tampered payload, wrong key, truncated signature, wrong schema, non-positive versionCode, bad asset names (traversal, slash, missing `.apk`), oversized notes, oversize declared APK. Generate a throwaway P-256 keypair inside the test at runtime; never commit a private key.
- Version comparison: installed 2 vs manifest 3, equal versions, lower manifest version, large values.
- State store: first launch, upgrade detection, notice consumption, envelope round-trip, malformed stored envelope is ignored rather than crashing.
- PendingIntent flags helper: mutable on API 31+, no `FLAG_MUTABLE` on 26 to 30 (Robolectric).
- Status mapping: every `PackageInstaller` status code maps to a stable enum and a string resource; unknown codes map to a generic failure, never to success.
- Optional: checker against a loopback HTTP server (`com.sun.net.httpserver`) for 200, 404, 500, timeout mapping. This tests the transport code without external network.

**Instrumented (emulator, API 37 AVD)**
- ApkVerifier against the installed app's own APK: package name, versionCode, signer set. This proves the `PackageManager` path, including the API 26/27 fallback only on an appropriate AVD if available.
- If a debug-only channel configuration is added later: a full local end-to-end (v2 installed, v3 served locally, install completes). Not in MVP; requires cleartext only for localhost in a debug manifest.

**Device (physical X6532, and at least one emulator)**
- Row states: not configured, up to date, update available, failure, downloading.
- Unknown-sources flow: grant, revoke, re-grant, recheck on resume.
- Airplane mode: friendly failure, app fully usable.
- Post-update notice: simulated only when a real install happens; otherwise BLOCKED.
- Rotation guard: run `scripts/rotation-guard.sh <serial>` after any input injection.

**Evidence that cannot be produced yet, stated as BLOCKED**
- End-to-end install of a release-signed APK: blocked on the release keystore (no signing config exists).
- Real GitHub channel verification: blocked on the first published release and the pinned manifest key.
- Two-device/OEM coverage: only the X6532 plus emulators exist; do not claim OEM coverage.

**Release runbook (production-ready requirement)**
1. Decide and document the release keystore; keep it offline with two cold backups; never commit it.
2. Bump `versionCode` (integer, strictly increasing) and `versionName`.
3. Build the signed release APK; verify the certificate fingerprint equals the previous release.
4. Compute SHA-256 and size; produce the manifest JSON.
5. Sign the manifest with the offline manifest key; verify the signature before publishing.
6. Create a normal GitHub release tagged `v<versionName>` with assets `update-manifest.json` and the versioned APK. Do not mark it prerelease or draft.
7. Post-publish check: fetch the stable manifest URL in a clean shell, verify the signature with the public key, and confirm the versionCode.
8. Install the previous release on a clean emulator, run the in-app update, confirm the version and that data survived.
9. Record the evidence in the docs/progress notes; keep only the latest APK locally (project rule).

**Rollback**
- There is no true rollback: Android refuses downgrades. The recovery is a new release with a higher versionCode. Do not delete the bad release; it documents what shipped. If the manifest key still works, a new manifest/release moves users forward.

---

## 9. Research Gaps

Explicitly unresolved, with no guess substituted:

1. **Android 14+ "update ownership" rules.** The behavior-changes page fetched for this dossier did not contain the rule. Whether a non-system app's self-update is subject to additional ownership requirements on Android 14 to 16 is **unverified**. The design is self-update of the same package, which is the normal case, but this must be re-checked on the API 37 emulator and on each targetSdk bump.
2. **Exact prerelease semantics of the web `/releases/latest` redirect.** The REST definition of "latest" is documented; the web redirect's behavior when the newest release is a prerelease is not spelled out as precisely. Treated as Medium confidence. The runbook's post-publish check mitigates it; do not rely on a prerelease-plus-latest trick.
3. **CDN cache duration for release assets.** Not documented in the sources fetched. If an asset is replaced under the same name, clients may briefly see the old bytes. The design avoids asset replacement entirely; replacement is a runbook violation.
4. **Broadcast delivery to a stopped package.** Whether a `PendingIntent` broadcast from `PackageInstaller` reaches an app whose process was killed and which is in the stopped state is **undocumented in the fetched sources**. The design does not depend on it: versionCode reconciliation on launch is authoritative.
5. **Installer prompt behavior on targetSdk 36 and OEM skins.** Whether the system shows one prompt or OEM-specific extra dialogs (XOS/MIUI scanners) is not verified; device testing is required and the result recorded. Do not generalize from the emulator.
6. **`USER_ACTION_NOT_REQUIRED` for self-update.** Not investigated; the design assumes the system always shows its own confirmation (`USER_ACTION_REQUIRED`, the default). Do not attempt to suppress the system prompt.
7. **`getPackageArchiveInfo` with `GET_SIGNING_CERTIFICATES` on API 26 to 27.** The flag is API 28+; the plan branches to `GET_SIGNATURES` below. Actual archive parsing behavior on old OEM builds is unverified; the API 37 emulator cannot prove it. The signer check is defense-in-depth; the installer enforces the same signer regardless.
8. **GitHub asset size limits and long-term bandwidth policy.** The APK is ~51 MB, far below any known per-asset limit, but the exact documented ceiling was not re-fetched for this dossier. Public-repo bandwidth is provided by GitHub; no SLA is documented.
9. **WorkManager reliability under XOS.** The project has device evidence for health-check work being delayed but not lost; the same behavior is assumed for the update check. The manual check is the guaranteed path.
10. **Key rotation policy for pinned update keys.** The mechanisms (ship a dual-key build, or reinstall) are known at a high level, but no official Android or GitHub guidance specific to a pinned manifest key was found; this is a design decision recorded in §4 and ADR #171, not an externally verified practice.

---

## 10. Final Recommendations

**R1, Ship Option C (stable URL plus signed manifest).**
- Why: documented, token-free, quota-free, transport-independent trust.
- Evidence: GitHub Docs "Linking to releases"; REST "latest" definition; ADR #171.
- Trade-offs: one extra signed asset per release; one "latest" for all users.
- Risks: asset-name or release-flag mistakes (mitigated by runbook), CDN caching (avoided by never replacing assets).
- Mitigation: post-publish verification against the stable URL.

**R2, Verify manifest signature with a pinned ECDSA P-256 key, then verify the APK (hash, package, version, signer).**
- Why: repository compromise must not become app compromise; ADR #171 makes this contract mandatory.
- Evidence: `PackageInfoCompat` docs; ADR #171.
- Trade-offs: a key to custody; rotation is awkward.
- Risks: key loss strands the channel; key compromise is unrecoverable remotely.
- Mitigation: offline key, backups, separation from the APK key, documented rotation.

**R3, Use `PackageInstaller` with explicit mutable PendingIntent and a manifest-declared status receiver.**
- Why: the only supported self-install path; targetSdk 36 makes mutability mandatory.
- Evidence: `PackageInstaller.Session` reference; Android 12 behavior changes.
- Trade-offs: the system prompt is unavoidable; status handling adds code.
- Risks: the install fails if any flag is wrong (the 6.1 pattern).
- Mitigation: flag unit test, a single installer module, emulator evidence.

**R4, Background check only; download on explicit user action.**
- Why: a check is a few KB; the APK is ~50 MB. Consent belongs at the expensive step.
- Evidence: installer always prompts regardless of when bytes were downloaded (Medium confidence on prompt specifics); data-cost reasoning.
- Trade-offs: one extra tap and a wait at install time.
- Risks: none material; slower perceived update.
- Mitigation: show size and progress; offer retry.
- Note: this deviates from the earlier owner note that said "background download"; the deviation is deliberate and recorded here. If the owner overrides, restrict automatic downloads to unmetered networks and still require install consent.

**R5, Reconcile on launch via versionCode.**
- Why: broadcast delivery is best-effort; the process is killed by design during commit.
- Evidence: `PackageInstaller.Session` "process may be killed"; Gap 4.
- Trade-offs: a tiny amount of state in prefs.
- Risks: none; it removes a stuck state class.
- Mitigation: the one-time upgrade notice uses the same reconciliation.

**R6, Fail closed when the manifest key is not configured.**
- Why: a silent "up to date" on an unconfigured channel is indistinguishable from a working one.
- Evidence: ADR #171; honest-state principle.
- Trade-offs: an unconfigured build visibly lacks updates.
- Risks: none; the row is explicit.
- Mitigation: the keygen script prints the exact constant to paste.

**R7, Release signing custody is an owner decision that blocks everything downstream.**
- Why: no release signingConfig exists; debug-signed test APKs cannot later update to release-signed APKs, and uninstalling loses data because `allowBackup=false`.
- Evidence: `app/build.gradle.kts` (no signingConfig); Android signature identity; ADR #171 open decision.
- Trade-offs: creating and safeguarding a keystore now costs time; skipping it costs user data later.
- Risks: losing the key permanently breaks the channel.
- Mitigation: two cold backups, an offline machine, and a written custody record before the first tester APK.

**R8, No telemetry, no identifiers in the updater.**
- Why: the app's privacy promise is specific; update behavior must not weaken it.
- Evidence: Settings crash-report promise; owner direction.
- Trade-offs: less insight into update failures.
- Risks: failures must be reported by testers rather than measured.
- Mitigation: precise in-app failure messages and reason codes visible to the user; no network reporting.

**What not to build now:** staged rollout percentages, multiple channels, delta/patch updates, background silent downloads, a custom backend, or certificate pinning of GitHub. Each adds moving parts without retiring a current requirement.
