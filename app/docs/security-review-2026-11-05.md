# Security review — 2026-11-05

Final pre-release security pass, run alongside the project cleanup. Guidance-mode review using
the Cloudflare `security-audit` method (source-first, concrete boundary + result required) with
the project tool stack: Opengrep, Trivy, Gitleaks, Android lint, and the Firestore rules
emulator. Scope: the whole repository — app source, debug source set, androidTest/JVM tests,
`firestore.rules`, `functions/`, and build/config files.

## Confirmed finding (fixed)

**`pairing_tokens` could be enumerated by any authenticated user — medium.**
`firestore.rules` granted `allow read` on the collection. Read covers both `get` and `list`,
and a list query constrained by `expiresAt` is evaluable, so any signed-in user (anonymous
sign-in is enabled) could list every live invitation and receive its **document id** — which is
also the `respondToPairInvitation` possession capability — plus `creatorUid` and the initiator
payload. Emulator-proven: a plain list was denied, an `expiresAt`-constrained list returned the
live ids, and a get by id (the QR/typed-code holder's path) succeeded.

Fix: split the rule into `allow get` (by id, live document only) and `allow list: if false`,
matching the nudges rule's existing split. Regression test
`pairing invitations cannot be enumerated — the id is the capability` covers get-live succeeds,
get-expired fails, plain list fails, constrained list fails, anonymous list fails. Rules suite
**15/15 green**.

**Deployment status — NEEDS VALIDATION.** Editing the file does not deploy it: the currently
deployed ruleset predates this split. The owner must run
`firebase deploy --only firestore:rules` and re-read the deployed source before the fix is live.

## Dependency results

- **App lockfile** (`gradle.lockfile`, Trivy): 45 findings (41 HIGH, 4 CRITICAL), every one
  configuration-attributed to `unified-test-platform-*`, `androidLintTool`, unit-test or KSP
  classpaths. Shipped runtime resolves bcprov 1.85.2; the Guava 32.1.3-android
  deserialization-DoS advisory has no reachable path in the app. No change required.
- **Functions** (`functions/package-lock.json`): body-parser 2.3.0 carried CVE-2026-111181
  (HIGH, DoS via unhandled decompression-stream error). Bumped to 2.4.0 via
  `npm update body-parser` (still inside express 5.2.1's range); rescan 0 HIGH/CRITICAL.
- **Secrets**: Gitleaks (worktree + history) 0 leaks. `.cache/` is allowlisted as regenerable
  tool state (web-search cache, localhost test cert).
- **SAST**: Opengrep custom rules, 0 findings across 483 parsed files. Its Kotlin parser reports
  a syntax error on `ui/settings/AccountPane.kt` (the compiler accepts the file), so that one
  file was covered by lint plus a manual grep — it has no logging or intent surface. One
  remediation: the debug `push-read` seam logged the FCM registration value; it now logs
  presence only (the value is a delivery credential, read server-side from `push_installations`).
- **Android lint** (ADR #208, run alone): 0 errors / 0 warnings / 0 hints.

## Controls re-checked (no finding)

App Check enforced on every callable; pairing attestation PKIX-pinned to Google hardware roots
and `SecurityLevel.SOFTWARE` rejected; PendingIntents immutable; `allowBackup=false` with
extraction exclusions; encrypted backup and E2EE mailbox; deletion requires recent re-auth;
event nonce uniqueness; no WebView surface; debug exported components are debug-variant only and
have no in-app senders.

## Accepted / remaining

- Debug exported components remain reachable by `adb shell` on debug installs — accepted:
  debug-only, no secrets beyond the install's own state, and the owner installs debug builds.
- The deployed Firestore rules lag the repository (see above) — the only open security item.
- No dynamic/DAST pass against the live backend was run in this session; callable behavior was
  assessed from source, the deployed-function inventory, and existing device evidence.
