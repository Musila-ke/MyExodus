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

**Deployment status — LIVE (2026-11-05).** Deployed with
`firebase deploy --only firestore:rules`. The deployed ruleset was re-read through the Firebase
Rules API (`projects/myexodus-53804/rulesets/ab0f8b65-f6bc-4aff-97fd-bf4cb8ff6021`) and contains
`allow get` plus `allow list: if false` in the `pairing_tokens` block.
The functions lockfile fix (body-parser 2.4.0) was deployed the same day: all 36 functions
updated and verified `ACTIVE` with fresh `updateTime`s through the Cloud Functions v2 API.

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

## SonarQube re-run — 2026-11-05

SonarQube 26.8.0.126808 was reinstalled locally (fresh database) and the whole tree re-analysed
with the Kotlin analyzer: 57,234 ncloc, coverage 16.7 % line (JaCoCo from
`:app:createDebugUnitTestCoverageReport`), duplication 1.2 %. Results and triage:

- **3 vulnerabilities — none real.** Every one is `text:S6389` (bidirectional control character)
  in `PartnerLabelTest` / `EnrollmentPayloadTest`: the tests deliberately feed U+202E
  (RIGHT-TO-LEFT OVERRIDE) through the encoder/sanitizer to prove the label is stripped. The
  flagged lines are the spoofing regression tests, not shipped code.
- **0 security hotspots.**
- **31 bugs, minor.** 30 × `kotlin:S899` "do something with the Boolean returned by delete" —
  best-effort temp-file cleanup where a failed delete is intentionally ignored; 1 ×
  `kotlin:S1143` return in `finally` at `ui/AppLabels.kt:72` — a documented bounded retry loop
  (`Snapshot.apply()` conflict/retry) whose non-throwing body makes it safe.
- **287 code smells.** Cognitive complexity (`kotlin:S3776`, 108), duplicated string literals
  (`S1192`, 72), long parameter lists (`S107`, 48), empty blocks (`S108`, 15), plus minor rules.
  Same family triaged as non-blocking in the 2026-09-13 run.

**Remediation pass (same day).** The mechanical and correctness-relevant findings were fixed
rather than filed — owner ruling: do not ignore even minor issues. A second analysis on the same
project key (2026-11-05 20:56 +0300, coverage regenerated from
`:app:createDebugUnitTestCoverageReport`) confirms every targeted rule closed:

| Rule | Before | After | Fix |
|---|---|---|---|
| S899 ignored `File.delete()` | 30 | 0 | shared `File.deleteOrWarn(tag)` (logs a failed delete) |
| S1143 `return` in `finally` | 1 | 0 | bounded retry loop in `ui/AppLabels.kt`, no `finally` |
| S108 empty catch | 15 | 0 | VPN teardown `Closeable.closeQuietly()` (+1 GMS-less comment), two account catches documented |
| S1066 collapsible `if` | 4 | 0 | merged conditions |
| S1871 identical branches | 4 | 2 | merged two; two left by design (`healthLabel` is declared on both data classes, not the sealed interface) |
| S1481 unused local | 3 | 1 | removed two; `PartnerDashboardSource.decode` is used — analyzer false positive |
| S6619 useless null check | 5 | 5 | all five verified false positives on nullable declarations |

Post-fix totals: **0 bugs**, 262 code smells, 3 vulnerabilities (the deliberate bidi spoofing
fixtures above), 0 hotspots, reliability rating A (1.0), coverage unchanged at 16.7 % line,
ncloc 57,251. Compile, full JVM suite, and Android lint (`:app:lintDebug` alone per ADR #208)
were green after the changes.

Coverage is a floor, not the suite's reach: classes exercised only inside Robolectric's sandbox
show 0 in the report even when their isolated tests pass.

## Live DAST — owner-authorized, 2026-11-05

Scope: the deployed backend only (`us-central1-myexodus-53804.cloudfunctions.net` and the
Firestore REST API), a handful of requests, no data written, no availability testing.

- **Callables without credentials:** `POST {"data":{}}` to `registerPairing`,
  `createPairInvitation`, `sendNudge` → `401 UNAUTHENTICATED`; body is the generic error
  envelope, no stack trace, no internal detail. A malformed body → `400 INVALID_ARGUMENT`.
- **CORS:** the callable endpoint reflects an arbitrary `Origin` in
  `Access-Control-Allow-Origin` and allows `POST`. Not a finding here: callables authenticate
  with an explicit `Authorization: Bearer` token (no cookies or ambient credentials), so a
  hostile origin gains no authenticated request, and App Check remains enforced server-side.
- **Android API key:** an anonymous sign-in attempted with the key from `google-services.json`
  is refused from the host — `403 PERMISSION_DENIED`, "Requests from this Android client
  application are blocked" (the package + debug-SHA-1 restriction). A host attacker cannot mint
  tokens with the shipped key; only the app's own signed build can.
- **Firestore REST without App Check/auth:** list `pairing_tokens` → `403 PERMISSION_DENIED`.
- **Not reachable from the host by design:** a valid app-scoped token requires the app's App
  Check attestation, so the authenticated callable surface could not be exercised from this
  machine. That is the control working; those legs belong to the on-device break-in pass.

## Accepted / remaining

- Debug exported components remain reachable by `adb shell` on debug installs — accepted:
  debug-only, no secrets beyond the install's own state, and the owner installs debug builds.
- No App Check-bearing authenticated DAST from a host was possible (see above); authenticated
  callable semantics were assessed from source and the emulator rules suite, and remain covered
  by the on-device adversarial pass.
