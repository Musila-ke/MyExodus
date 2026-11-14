# Security scans and final gates — 2026-11-14

Owner queue item #152 (security + quality scans) closeout for the 1.1.29 line. Every tool below
ran against this working tree after the #151 review fixes and the named SonarQube fixes; the
exact commands, versions, and outputs are recorded so the next closeout can re-run them. Tool
versions: Opengrep 1.33.1, Gitleaks 8.30.1, Trivy 0.76.2, SonarQube Community Build 26.8.0.126808
(SonarScanner CLI 8.4.0.16527, local `localhost:9000`).

## Opengrep (SAST)

Two runs over the same tree, both limited to git-tracked files (516 at scan time).

- **Project rules (7 Kotlin rules in `scripts/`)** — scanning 516 files, 30 partially parsed
  (Kotlin parser limitation, not findings): **0 findings**. Log: `/private/tmp/opengrep-custom.log`.
- **Community registry (1,074 rules loaded, 64 applicable to Kotlin)** — **22 findings across 9
  file groups, 2 rule types**, all reviewed:
  - `kotlin.lang.security.gcm-detection` (8 files, 21 findings: `EventCipher`,
    `ServicePayloadProtector`, `BackupCrypto`, `BackupSnapshotCipher`, `VaultKeyManager`,
    `KeystoreWrappedDekManager`, `GuestTransition`, `KeystoreGuestQuarantine`). Advisory only —
    every site feeds a nonce that is either
    random (SecureRandom / `Cipher`-generated) or derived per message from a keyed derivation
    (`EventCipher.nonce(cek, sequence)`), never a fixed IV with a reused key. Confirmed by reading
    each site. No change.
  - `kotlin.lang.security.unencrypted-socket` — `vpn/DnsSinkholeVpnService.kt:651`. Plaintext TCP
    DNS forwarding to the tunnel's own resolver is the VPN's protocol design; the app's own
    traffic is unaffected. No change.
  - 3 files partially parsed (same parser limitation). Log: `/private/tmp/opengrep-auto.log`.

## Gitleaks (secrets)

- `gitleaks git . --redact -v` — 213 commits / 20.58 MB scanned: **0 leaks**.
- `gitleaks dir . --redact -v` — 17.56 MB worktree scanned: **0 leaks**. `.cache/` remains
  allowlisted as regenerable tool state (`.gitleaks.toml`). Logs: `/private/tmp/gitleaks-git.log`,
  `/private/tmp/gitleaks-dir.log`.

## Trivy (dependencies)

- **App** — `trivy fs --scanners vuln --severity HIGH,CRITICAL app/gradle.lockfile` (ghcr DB
  mirror env vars required on this machine): **45 findings (41 HIGH, 4 CRITICAL)**, unchanged
  from the 2026-11-05 run. 44 are configuration-scoped to `androidLintTool`,
  `unified-test-platform-*`, KSP/Hilt and unit-test classpaths and never enter the APK. The one
  shipped finding is `com.google.guava:guava:32.1.3-android` (CVE-2026-102554, deserialization
  DoS) — **accepted risk, disposition and unreachability argument recorded in `app/docs/MyExodus
  Build & Dependencies.md` ("Final gate re-run (2026-11-14)")**; the bump is deferred to a
  dedicated release because Firebase BoM 34.18.0 pins the 32.1.3 line. Runtime bcprov is 1.85.2,
  past every flagged bcprov CVE. Output: `/private/tmp/trivy-app.json`.
- **Functions** — `functions/package-lock.json`: **0 HIGH/CRITICAL**. Output:
  `/private/tmp/trivy-functions.json`.

## SonarQube (quality) — first scan, fresh project

Full scan of the final tree (same `MyExodus` project, local server): **342 unique open issues**
(334 code smell, 5 bug, 3 vulnerability) across 24 rules. The mass rules are the accepted
baseline from prior campaigns (S3776 complexity 133, S1192 duplicated literal 85, S107 parameter
count 62); the remaining 62 were read individually. **19 were fixed in source**, the rest carry a
recorded verdict (below). Re-scan and post-fix count: see "Post-fix re-scan".

### Disposition table (all 342)

| Rule | Count | Disposition |
|---|---|---|
| S3776 | 133 | Accepted baseline — complexity in long-lived screens/services; prior campaigns' verdict, refactor not justified at final wrap |
| S1192 | 85 | Accepted baseline — duplicated literals |
| S107 | 62 | Accepted baseline — parameter count |
| S6619 | 11 | Reviewed individually — every site is a defensive null check (`!!` behind a preceding check, `?.let`, `!= null` guards); no live defect |
| S6517 | 9 | Accepted baseline |
| S6511 | 7 | Accepted baseline |
| S899 | 5 | **Fixed** — `VaultEnvelopeStore` now deletes through the shared `util/FileDeletion.kt deleteOrWarn` (a deletion failure logs instead of being swallowed; throwing would break `VaultRecoveryReset.erase()`'s deliberately tolerant path) |
| S1125 | 4 | **Fixed ×2** — `BlockerAccessibilityService:820` (`!(a && b) && c` short-circuit form) and `SqlCipherVaultKeys:49` (`if (!isWrongKey) throw; false`). **False positive ×2** — `UrgeAssistScreen:521/:527`: the subject is a nullable `Boolean` read from a transaction result; the analyzer's suggested form does not compile and the explicit `== true/false` is the intended check |
| S1313 | 4 | False positive — the four hardcoded addresses are the DNS sinkhole's own sink/upstream constants (`DnsSinkholeVpnService:112-119`), not endpoints chosen at runtime |
| text:S6389 | 3 | False positive — bidi control characters are the *payloads* of the label-sanitizer tests (`EnrollmentPayloadTest`, `PartnerLabelTest`) |
| S1871 | 2 | No action — `AttestationVerifier:179` distinct-trigger branches share the revoke body deliberately; `PartnerScreen:2648` keeps its branches explicit for copy divergence |
| S6532 | 2 | **Fixed** — `BackupUploader:183` sealed-object consistency is now a `check(...) {...}`; `PlaintextToEncryptedMigration:176` throws via `error(...)` |
| S6516 | 2 | **Fixed ×1** — `AccountModule:195` `PushMapping` SAM lambda (suspend SAM conversion verified). **No action ×1** — `BlockerAccessibilityService:167` `Runnable` needs `this` for `postDelayed(this, ...)` |
| S1481 | 2 | **Fixed ×1** — `UrgeAssistScreen:129` unused `context`. **False positive ×1** — `PartnerDashboardSource:135` `decode` is used at the row-mapping call a few lines below |
| S1874 | 2 | **Fixed** — `UpdateChecker` `java.net.URL(...)` constructors replaced with `URI(...).toURL()`, both inside the existing `runCatching` so malformed URLs still degrade to `HOST_UNAVAILABLE` |
| S108 | 1 | **Fixed** — `PartnerEventRecorder:167` empty catch now carries the "row is durable; a failed kick only delays it" comment inside the block |
| S6615 | 1 | **Fixed** — `PartnerSyncCoordinator:729` dead `anchor = null` assignment removed; the value is now local to its only reader branch |
| S4144 | 1 | No action — `AppLockCoordinator:156` bodies are identical on purpose: distinct lock triggers (screen-off vs. background) share one revoke routine |
| S6514 | 1 | No action — `BackupPayload:304` `CappedOutputStream`: converting to `by` delegation would newly delegate `close()`, which the class intentionally does not forward |
| S6526 | 1 | **Fixed** — `RecoveryPolicyDao` abstract class → interface (matches the majority DAO convention; Room supports it) |
| S6524 | 1 | **Fixed** — `FlowRegistry:38` local declared as read-only `Set<FlowControl>`; the map stays mutable |
| S1172 | 1 → 2 | **Fixed ×2** — `AppShell`'s unused `attempts: AttemptRecorder` parameter and the `MainActivity` injection removed; the re-scan then surfaced a second unused parameter in the same function (`rulesRepository`, line 251, a duplicate of the used `repository`), also removed with its `MainActivity` argument |
| S6531 | 1 | **Fixed** — `PairingCeremonyScreen:247` redundant cast dropped; the `is Rejected, null ->` branch already smart-casts |
| S1135 | 1 | False positive — `PartnerScreen:3071` the highlighted token is the KDoc link `[todo]` on `PlanHubStatus`, not a TODO marker |

Fixed: **19** (S899 ×5, S1874 ×2, S6532 ×2, S1125 ×2, and one each of S1481, S108, S6615, S6516,
S6526, S6524, S1172, S6531). Verdicts are judgments on this analyzer run only; the scanner
reported unresolved test-only imports ("Missing 'sonar.java.test.libraries'"), so every
FP verdict above was confirmed by reading the source, not by trusting the analyzer.

### Post-fix re-scan

Two re-scans ran with the baseline's exact scope (`sonar.sources=app/src/main`, 516 Kotlin files;
`sonar.tests=app/src/test`, `sonar.java.binaries=app/build/tmp/kotlin-classes/debug`), against a
live local server, project `MyExodus`:

- **Intermediate re-scan (after the first 19 fixes):** 330 open — 18 of the 19 fixes cleared
  (S1172 did not), and Sonar newly reported 6 XML issues on `AndroidManifest.xml` (xml:S5322 ×5,
  xml:S5332 ×1) that the baseline had not raised. CE task `b33c7b88-d829-4444-b8c8-bd61e09c0f3a`.
- **Final re-scan (after the last two fixes):** **328 open**, CE task
  `f04d6d66-a914-4552-a954-a438accfad7b`, analysis `baad47d1-a52f-4a20-82f7-c2608294b382`.
  Composition: S3776 ×133, S1192 ×85, S107 ×62, S6619 ×11, S6517 ×9, S6511 ×7, S1313 ×4,
  text:S6389 ×3, S1125 ×2, S1871 ×2, xml:S5322 ×5, and one each of S1135, S1481, S4144, S6514,
  S6516. Every one carries a verdict above or below; nothing is silently dropped.

Two issues fixed between the intermediate and final scans:

- **S1172 (second site)** — the first fix removed `AppShell`'s unused `attempts: AttemptRecorder`
  parameter, but the re-scan flagged a *different* unused parameter in the same function:
  `rulesRepository` (a duplicate of the used `repository`). Removed with its `MainActivity`
  argument. The `@Inject rulesRepository` field in `MainActivity` stays — it has live callers.
- **xml:S5332 (cleartext)** — `AndroidManifest.xml:59` flagged the implicit cleartext default.
  On API 26-27 the platform default is `true`, so the release build allowed cleartext without
  saying so. Fixed by declaring `android:usesCleartextTraffic="false"` on the application
  element; the debug manifest overrides it back to `true` (`tools:replace`) for the local HTTP
  QA rig. Verified the only release HTTP client (`HttpsPolicyClient` in `UpdateChecker.kt`)
  already rejects any non-HTTPS URL, so no release behavior changes.

**xml:S5322 ×5 — verified no action.** The five sites are the exported receivers
`BootReceiver`, `PartnerEventBootReceiver`, `MilestoneBootReceiver`, `LockedBootReceiver`, and
`DesertDawnWidgetReceiver`. Four register only `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` or
`MY_PACKAGE_REPLACED`; all three are in AOSP's `<protected-broadcast>` list
(`core/res/AndroidManifest.xml`), so only the system can send them — a third-party app cannot
invoke those receivers. The widget receiver is exported because the launcher's AppWidgetHost
must reach it; its own `onReceive` branch also acts only on the two protected actions, and any
foreign `APPWIDGET_UPDATE` (not protected) is forwarded to Glance, which merely re-renders the
app's own widget — no intent data reaches app state. Android lint raised nothing on these
declarations.

## Compiler warnings (final tree)

Two deprecations surface when compiling the debug main source set:

- `Icons.Outlined.HelpOutline` — **fixed**: switched to
  `Icons.AutoMirrored.Outlined.HelpOutline` (`UrgeAssistScreen`).
- `KeyguardManager.createConfirmDeviceCredentialIntent` (deprecated in the Java API used through
  the platform) — **accepted**: the replacement is a `BiometricPrompt` `DEVICE_CREDENTIAL`
  authentication flow, which changes the partner-signing credential flow's behavior; that is a
  feature change, not a final-wrap edit. The current call is functional and its deprecation is a
  warning, not a defect.

## Final gates

Both ran on the post-fix tree (the 20 source edits above, including the two last fixes):

- **JVM unit suite** — `./gradlew :app:testDebugUnitTest`: **297 classes / 2,839 tests /
  0 failures / 0 errors / 0 skipped**, BUILD SUCCESSFUL (log `/tmp/final-suite-5.log`; counts
  aggregated from `app/build/test-results/testDebugUnitTest/*.xml`).
- **Android lint** — `./gradlew :app:lintDebug` run alone (ADR #208), after the suite:
  **"No issues found."** — 0 errors, 0 warnings (report `app/build/reports/lint-results-debug.*`,
  log `/tmp/lint-final-3.log`, exit 0). This run includes the final `AndroidManifest.xml`
  change, so the manifest merge is also verified.

## Honest limits

- SonarQube analysis had no test classpath (unresolved-import warnings), so its rule coverage on
  test sources is partial; the two text:S6389 test findings and the S1481 test-adjacent FP were
  judged by reading the source.
- Opengrep's Kotlin parser skipped or partially parsed 30 files (custom run) / 3 files
  (community run); those files are covered by the compiler, lint, and the SonarQube pass.
- Trivy findings are lockfile facts, not exploitability proofs; the one shipped finding carries
  its reachability argument in the dependency doc.
- ZAP/Nuclei (DAST) do not run in this gate: they need a deployed target and authorization; the
  platform gate is Android lint (ADR #208).
