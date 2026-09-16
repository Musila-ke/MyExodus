# MyExodus Build & Dependencies

Current as of 2026-09-13. All versions verified together: `assembleDebug`, `lintDebug`, `testDebugUnitTest` all pass.

## Toolchain

| Component | Version | Notes |
|---|---|---|
| Gradle | 9.4.1 | Wrapper-pinned |
| Android Gradle Plugin | 9.2.1 | Latest stable; requires Gradle 9.4.1 |
| Kotlin | 2.2.10 (built-in) | Managed by AGP 9's built-in Kotlin. Do NOT apply `org.jetbrains.kotlin.android`; to change Kotlin version you'd set `android.builtInKotlin=false` and apply KGP yourself (not recommended — Google warns against opting out) |
| JDK | 17+ (Java 25 local) | AGP 9 minimum is 17 |
| compileSdk / targetSdk / minSdk | 37 / 36 / **26** | compileSdk 37 is **required** by current androidx (core-ktx 1.19.0 fails to resolve against 36). minSdk 26 per plan §3 (Slice 10, 2026-08-31); §16/§314 analytics confirmation pending |
| Compose BOM | 2026.08.00 | Compose 1.12; requires compileSdk 37 + AGP 9 |

## Libraries

| Group | Version | Purpose (per ADRs) |
|---|---|---|
| Room (`androidx.room3`) | 3.0.3 | Offline-first local SQLite (ADR #2). New KMP-based namespace; KSP-only, no KAPT. 3.0.2 (2026-08-28): @Transaction deadlock + closed-DB crash fixes on the SQLCipher driver path. 3.0.3 (2026-09-14): fixes the `IllegalMonitorStateException` regression in `TriggerBasedInvalidationTracker` (`@Transaction` wrapper DAO functions; ReentrantLock held across a suspension point → unlock on a non-owner thread; emulator-caught process death at friction-timer completion, ADR #35). Lockfile regenerated each bump (`:app:dependencies --write-locks` — locking is enforced); Trivy clean |
| androidx.sqlite | 2.7.0 | SQLiteDriver API that Room 3 sits on |
| SQLCipher (`net.zetetic:sqlcipher-android`) | 4.18.0 | Encryption at rest (ADR #2, #9). Legacy `android-database-sqlcipher` is deprecated + lacks 16KB-page support — never add it |
| Hilt (`com.google.dagger`) | 2.60.1 | Compile-time DI (ADR #5); 2.59+ supports AGP 9 |
| androidx.hilt:hilt-navigation-compose | 1.4.0 | Navigation ViewModel injection |
| androidx.hilt:hilt-work | 1.4.0 | `@HiltWorker` for the health-check + partner-sync workers (2026-08-26, 2026-08-30) |
| androidx.hilt:hilt-compiler | 1.4.0 (KSP) | **Required alongside hilt-work** (added 2026-09-16): the androidx processor that generates each `@HiltWorker`'s `WorkerAssistedFactory` + the map binding `HiltWorkerFactory` reads. Dagger's `com.google.dagger:hilt-compiler` does NOT cover workers. Without it every `@HiltWorker` fails instantiation at dispatch ("Could not create Worker … NoSuchMethodException `<init>[Context, WorkerParameters]`" → reflection fallback); XOS masked this because no WorkManager job ever runs there (ADR #27). Found by the emulator execution leg 2026-09-16; generated `*_AssistedFactory` classes verified in build output after the fix; **runtime-verified 2026-09-16 (emulator-5554, stock Android)**: `HealthCheckWorker` + `PartnerSyncWorker` logged `Worker result SUCCESS` on their genuine dispatch (was `NoSuchMethodException`), and `DailyMaintenanceWorkerInstrumentedTest#maintenanceRun_rollsStaleTrackerRow` passed (`OK (1 test)`) |
| KSP | 2.3.11 | Room + Hilt codegen; decoupled from Kotlin version since 2.3.0 |
| Firebase BoM | 34.18.0 | Event relay + partner alerts (ADR #3, #8) |
| Firebase Messaging | via BoM | Partner push notifications |
| Firebase Firestore | via BoM | Partner dashboard replica / sync store |
| Firebase Auth | via BoM | Anonymous sign-in for the pairing relay + mailbox (Slice 7) |
| androidx.biometric | 1.1.0 | BiometricPrompt signing prompts (debug-only — pairing harness; V1 UI moves it to main) |
| WorkManager | 2.11.2 | Time-range schedules, heartbeat self-check (feature plans); Enhanced Mode 15-min health check (2026-08-26). Application implements `Configuration.Provider` + `HiltWorkerFactory`; manifest removes `androidx.startup.InitializationProvider` (on-demand init, 2026-08-28). Worker instantiation additionally requires `androidx.hilt:hilt-compiler` on KSP (added 2026-09-16 — see that row) |
| Navigation Compose | 2.9.8 | App navigation |
| Lifecycle (runtime/viewmodel/runtime-compose) | 2.11.0 | MVVM state (ADR #4) |
| Kotlinx Coroutines | 1.11.0 | Reactive flows |
| core-ktx / activity-compose | 1.19.0 / 1.13.0 | Baseline androidx |
| Test: junit4 / ext-junit / espresso | 4.13.2 / 1.3.0 / 3.7.0 | Unit + instrumentation |
| Test: uiautomator | 2.3.0 | Instrumentation UiAutomation (compileSdk-37 stubs trimmed: `UiAutomation` flags 0x4/0x8 — see ADR #17 addendum) |
| BouncyCastle bcprov-jdk18on | 1.85.2 | Android Key Attestation certificate-chain parsing (protocol §8.1). Runtime version is past CVE-2025-14813's fixed 1.80.2 (the vulnerable 1.79 copy in the lockfile is `androidLintTool`-only — see scan section) |
| androidx.security:security-crypto | 1.1.0 | EncryptedSharedPreferences for §8.2 biometric-enrollment cached state + identity epoch |
| CameraX (core / camera2 / lifecycle) | 1.6.2 | Animated QR receiving via ImageAnalysis (protocol §6.3, plan §11: STRATEGY_KEEP_ONLY_LATEST + 640x480) |
| zxing:core | 3.5.4 | QR encode/decode for offline sync (protocol §6.3) |
| com.upokecenter:cbor (petero) | 4.5.6 | RFC 8949 deterministic CBOR encoding (protocol §6.2.1, ADR) |

## Pending items

- **`google-services` Gradle plugin**: not applied yet — it fails the build without `google-services.json`. Add both when the Firebase project is created.
- **SQLCipher + Room 3 wiring**: implemented 2026-08-25 (ADR #16) — SQLCipherDriver with Keystore-wrapped DEK, six Room 3 entities, raw-SQL CHECK constraints, recovery states. Instrumentation suite: 8/8 green on Infinix X6532/XOS (2026-08-25) — fresh create, restart, key-loss routing, cipher_version, corrupt-file recovery, schema constraints, reset-and-reopen, wrong-key rejection. WAL checkpoint on app-background wired (MyExodusApp). net.zetetic keep rules pre-seeded in proguard-rules.pro for the future release build.
- **Accountability-Partner Protocol** (plan: `app/plans/accountability-partner-protocol-implementation-plan.md`): phases 1-6 implemented 2026-08-28/29 — dual hardware keys + attestation, deterministic CBOR, SQLite hash chain + outbox, transcript-bound SAS enrollment, Firestore mailbox + animated QR sync, lifecycle (rotation/revocation/bilateral shredding). Lockfile regenerated + Trivy rescanned 2026-08-29. **Slice 7 pairing (§7 step 8 + §10) complete 2026-08-30** — full round trip paired both roles on the X6532 (SAS agreed), revocation verified, rules deployed to Firestore (2026-08-30). **2026-09-13 review pass:** two production gaps found in ADR #33 and fixed — `firebase.json` had no `indexes` key (the mailbox composite index `(pairId, partnerUid, createdAt)` was never actually deployed; now wired + deployed + built) and the production Hilt builder omitted `AccountabilityDatabase.MIGRATION_3_4` (an existing v3 `myexodus_accountability.db` failed every open; now registered, device-verified 3→4). Sync round trip re-verified on-device after both fixes (run 1 links 1 event, run 2 idempotent). Remaining: §12 device matrix, App Check enforcement (§18, user step).
- **App-Block Foreground Service (Slice 8, plan: `app/plans/myexodus-app-blocking-core-implementation-plan (1).md` §8.1–8.3)**: implemented 2026-08-30 — specialUse FGS (`PACKAGE_USAGE_STATS`, 1.5s `queryEvents` poll on ACTIVITY_RESUMED), `BlockerActivity` (back-proof, task-affinity "", recovery countdown + override via the §8.3 stores, unlock via Rule 3 expiry), §8.2 degraded states (APP_STOPPED via `getHistoricalProcessExitReasons`, USAGE_ACCESS_REVOKED, NOTIFICATIONS_OFF) with persistent alert notification + in-app banner + explicit re-enable, permission checks on MainActivity.onResume AND the FGS's own cycle, START_STICKY crash restart. New manifest permissions: `PACKAGE_USAGE_STATS` (tools:ignore), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` + specialUse property. On-device verified on X6532 2026-08-30 (block → back-no-op → recovery → override unlock → re-block; degraded matrix: force-stop → APP_STOPPED alert + re-enable, usage-access revoke/restore, POST_NOTIFICATIONS revoke; one XOS binder-wedge defect found and fixed — time-bounded poll ticks). Remaining: two-device/latency §12 runs (Slice 11), attempt-log + outbox (Slice 9).
- **Attempt Log + Partner Outbox (Slice 9, plan §6 + §14)**: implemented 2026-08-30 — `attempt_log` table in AppRulesStore (Room v3→4, `MIGRATION_3_4`), `AttemptRecorder` (best-effort, never throws) on the FGS detection + override paths, 15-min unique-KEEP `PartnerSyncWorker` running `PartnerSyncCoordinator` (reconcile unlinked attempts into chain events while the biometric auth window is warm → drain outbox → mailbox pull → backfill response; uid resolution from the pair doc at runtime, never persisted; payload `blocked:<pkg>` / `override:<pkg>` decryptable by the partner). `EventCreator.Created` carries the sequence; `DrainResult.Sent` carries the count; `AccountabilitySigner` opened for test fakes. On-device verified X6532 2026-08-30 (victim block → attempt row persisted, DB v4, integrity ok). Unit suite 335 green; lint zero (16 warnings cleared incl. two real pre-33/pre-26 runtime bugs). Remaining: two-device outbox round trip (Slice 11 §12).
- **Min SDK floor API 26 (Slice 10, plan §3)**: implemented 2026-08-31 — `minSdk` 24→26 in build.gradle.kts (UsageStatsManager + modern background-execution present at 26; §16/§314 analytics confirmation still pending). Pre-26 dead paths pruned (lint ObsoleteSdkInt ×7: receiver-registration branches, notification-channel guards, TYPE_PHONE fallback, pre-O early return, pre-26 settings intent fallback, mipmap-anydpi-v26 merged). Flavors: none by design — ADR #15 removed the Play flavor split (direct APK only). Unit suite 335 green, lint zero, install + smoke on X6532. ADR #26.
- **Slice 11 §12 device matrix + full verification (plan: `app/plans/myexodus-app-blocking-core-implementation-plan (1).md` §11 + §12)**: complete 2026-08-31 — functional suite (keyguard dance, tapjacking, browser/dialer/in-call) PASS, latency suite (30 cycles: detection p99 5ms / submission 43ms / presentation 80ms) PASS, fgs suite PASS, Task-Manager Stop PASS, multiwindow PASS (XOS skip-branch), **two-device round trip via one-device two-role through the real Firestore relay PASS** (attempt → linked chain event → mailbox drain server-confirmed → idempotent re-run; dedup by event ID, plan line 278). Emulator abandoned (guest instability; user directive) in favor of one-device two-role. Two production defects caught on-device and fixed: `AccountabilitySigner.beginSign` explicit-`AndroidKeyStore` provider lookup broken by the XOS BC-workaround shadow (provider dropped — default chain); `FirestoreMailboxReceiver.pull` collection query rejected by rules query-coverage (partnerUid equality filter added). Unit suite 335 green, lint zero. ADR #27. Remaining: OEM matrix (needs 2 more physical devices — user's call), §16/§314 minSdk analytics confirmation, App Check enforcement (§18, user step). Emulator split-screen pass 2026-09-14 (ADR #36): pane re-bound + Allow-branch bypass fixed, `RESULT: multiwindow=PASS` end-to-end (cover bounded to the victim pane, allowed-pane-poke regression green). NEXT: V1 UI workstream (research + color scheme first).
- **Enhanced Mode** (plan: `app/plans/myexodus-enhanced-mode.implementation-plan.md`): implemented 2026-08-26 — blocking engine, overlay, latency profiler, URL scanner, health check, Strict Mode UI, AAPM detection. Unit suite 93/93 green. Locks regenerated (`--write-locks`) for `androidx.hilt:hilt-work:1.4.0`; manifest gained `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `QUERY_ADVANCED_PROTECTION_MODE`. Remaining: on-device §12 verification matrix (ADR #17).

## Dependency locking

- `dependencyLocking { lockAllConfigurations() }` is enabled in `app/build.gradle.kts`; resolved versions are pinned in `app/gradle.lockfile` (checked in). This also guards against supply-chain drift.
- To update a dependency after a version bump: `./gradlew :app:dependencies --write-locks` (or `--update-locks <group:artifact>`). Regenerated 2026-09-16 for `androidx.hilt:hilt-compiler:1.4.0` (+ its `androidx.room:room-compiler-processing:2.8.4` transitive) — both variants build green afterward (debug + R8 release, 2026-09-16). **Scope trap (2026-09-16):** `--write-locks` only updates configurations the invoked tasks resolve; an assemble-only invocation (`:app:assembleDebug :app:assembleRelease --write-locks`) never resolves androidTest/unitTest variant configurations, so a later `connectedDebugAndroidTest` fails config-time with "Resolved 'X' which is not part of the dependency lock state". Use `:app:dependencies --write-locks` for full lock coverage.
- A root-level `settings-gradle.lockfile` also exists (version catalog lock).

## Vulnerability scanning — DONE (2026-08-24)

- **Tool**: Trivy 0.74.0, installed via the official install script (not brew — brew tried building go@1.26 from source on this macOS 12 machine and stalled; the prebuilt binary is the working path). Binary lives at `/usr/local/bin/trivy`.
- **Scan command** (uses the lockfile; first run downloads Trivy's vulnerability DB):
  `TRIVY_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-db TRIVY_JAVA_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-java-db trivy fs --scanners vuln --severity HIGH,CRITICAL app/gradle.lockfile`
  The ghcr mirror env vars are **required on this machine** — the default mirror (mirror.gcr.io) crawls at ~5 KiB/s and times out.
- **OWASP dependency-check plugin**: evaluated and **removed** from root `build.gradle.kts` (2026-08-24) — it required a free NVD API key and was superseded by Trivy.
- **Results (2026-08-24, re-confirmed 2026-08-29 after the protocol dependency additions)**: 34 findings (33 HIGH, 1 CRITICAL) — **all build/test-tooling only, none reach the APK**. Verified by lockfile configuration attribution: every Netty entry resolves only under `unified-test-platform-*` (emulator control / test core), and the vulnerable bcprov 1.79 copy only under `androidLintTool` + `unified-test-platform-android-test-plugin-result-listener-gradle`. The app's own runtime classpaths (compile/runtime for debug + release) carry bcprov 1.85.2, past the CVE-2025-14813 fixed version.
  - Netty 4.1.110/4.1.93 (DoS, HTTP/2 DDoS, request smuggling, hostname-verify bypass) — pulled by AGP's `unified-test-platform-*` configurations (emulator control, test core).
  - BouncyCastle bcprov 1.79 (CRITICAL CVE-2025-14813, GOST CTR) — pulled by `androidLintTool`.
  - **No app action needed**; fixes land when AGP bumps its bundled tooling. Re-run the scan above to confirm future state.

## Commands

- Build: `./gradlew :app:assembleDebug`
- Verify: `./gradlew :app:lintDebug :app:testDebugUnitTest`
- Clean: `./gradlew clean`
- Install on device: `./gradlew :app:installDebug`
- Regenerate locks: `./gradlew :app:dependencies --write-locks`
- Deploy Firestore rules + indexes: `firebase deploy --only firestore:rules,firestore:indexes` (from the project root; requires both keys wired in `firebase.json` — a missing `indexes` key makes the index deploy a silent no-op, learned 2026-09-13, ADR #33). Verify on the server afterward (Firebase console → Firestore → Indexes), not just the CLI's "Deploy complete".
