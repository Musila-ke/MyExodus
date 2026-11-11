# App Lock — Phase 1 Repository Inventory

Plan: `app/plans/My-Exodus-App-Lock-Implementation-Plan-v2.md` §3 checks 1-10 (untracked; never committed).
Gate: no App Lock runtime code may be written before this document is committed. The plan's
`docs/...` path maps to this repository's sole docs folder, `app/docs/`.

Verified 2026-11-11 against source on `dev` at the time of writing.

## 1. Entry points and activity surfaces

- `MainActivity` — `MainActivity.kt:159` (`FragmentActivity`), Compose root at `:581`. No
  Navigation-Compose `NavHost` symbol exists in main sources; screen selection is Compose state.
  Manifest `:76-97`: exported launcher, `singleTop`, portrait, `configChanges`. Notification taps,
  widget and block-screen entry arrive through `onNewIntent` extras (manifest comment `:69-75`).
  No intent filter beyond MAIN/LAUNCHER; no external deep-link scheme.
- `BlockerActivity` — manifest `:198-207`, `ComponentActivity`, not exported, own task affinity.
- `WidgetActionActivity` — manifest `:260-268`, `ComponentActivity`, not exported, own affinity.
- No app privacy/unlock PIN gate exists anywhere (see §6). `SecureSurface.kt` (FLAG_SECURE
  mirroring, recovery surfaces only) is the only existing concealment mechanism.

## 2. Storage engines, files, keys, opening sites

Versions (`gradle/libs.versions.toml:12-16,32`): Room 3.0.3 (`androidx.room3`), SQLCipher 4.18.0
(`net.zetetic:sqlcipher-android`), Hilt 2.60.1, `androidx.biometric` 1.1.0.

| Store | Engine | File | Key | WAL |
|---|---|---|---|---|
| Rules | Room 3 over SQLCipher | `myexodus_rules_<uidHash>.db` (`LocalDatabaseMigration.kt:41`); unscoped `AppRulesStoreFactory.DB_NAME` | 32-byte random DEK wrapped by AES-256-GCM Android Keystore KEK, `setUserAuthenticationRequired(false)` (`KeystoreWrappedDekManager.kt`) | WAL; PASSIVE checkpoint `AppRulesStoreFactory.kt:67-76`, TRUNCATE during swap `:301-304,415-416` |
| Activity log | raw SQLCipher | `myexodus_activity_<uidHash>.db` (`LocalDatabaseMigration.kt:42`); base `ActivityLogSchema.kt:11` | own DEK via `ActivityLogKeyManager`, same wrap scheme | WAL required (`ActivityLogSchema.kt:28-35`); PASSIVE checkpoint `ActivityLogStore.kt:210` |
| Accountability | plain Room, **not SQLCipher** | `myexodus_accountability.db` (`AccountabilityModule.kt:137`, `AccountabilityDatabase.kt:378`) | none | Room default |

Rules opening sites: only `AppRulesStoreFactory.open()` `:51` → `openInternal()` `:89` →
`build()` `:209,392-395`, with `KeyFetchingSQLCipherDriver.open()` `:52` and raw-key conversion
(ADR #153). No other `Room.databaseBuilder(AppRulesStore...)` site exists. Other opening sites:
`ActivityLogStore.open()` `:42`; legacy re-key `DatabaseMigration.kt:76`; plaintext conversions
`LocalDatabaseMigration.kt:322,362,371` and `PlaintextToEncryptedMigration.kt:54,70`; restore and
recovery copies inside the factory.

The key is **not** PIN-derived: `KeystoreWrappedDekManager.getOrCreateKey()` generates random 32
bytes and wraps them; `RulesStoreEncryption.newDriver()` `:29` supplies the DEK per connection.

## 3. Consumers, singletons, workers, services

- `AppRulesStoreFactory` is the app-wide singleton: `@Volatile database` `:35`, cached `open()`
  `:51-53`.
- **101** `storeFactory.open()` call sites in main sources. Highest-risk groups:
  `AccountabilityModule` (`:565,704,798,830-848,1180`), `EnforcementForegroundService`
  (`:347,533`), `UsageAccountingEngine` (7 sites), `RecoveryStateStore`, `AttemptRecorder`,
  `InsightsRepository`, `BackupModule` `:128`, `PartnerSyncCoordinator`
  (`:282,388,680,709,885`). Regenerate with `grep -rn "storeFactory.open()" app/src/main`.
- No `android:process` in the manifest; no app-defined ContentProvider (the androidx.startup
  initializer is explicitly removed, manifest `:101-104`).
- WorkManager: `HealthCheckWorker` → EnhancedModeHealthMonitor (prefs only, lazily created for
  pre-unlock safety) + ProtectionStatusMonitor; `DailyMaintenanceWorker` → DailyMaintenancePass →
  UsageAccountingEngine (vault); `PartnerSyncWorker` → PartnerSyncCoordinator (vault);
  `UpdateCheckWorker`/`UpdateVerifyWorker` have no rules-store reference.
- Services: `EnforcementForegroundService` (vault reads), `BlockerAccessibilityService`,
  `DnsSinkholeVpnService`, `PartnerMessagingService` (FCM wake, no direct DB).
- `MyExodusApp` injects `ActivityLogStore` and calls only `checkpoint()` (`:73,415`).

## 4. Data classification

- **Vault (local-only):** rules, attempts, usage ledger, recovery state/sessions, streaks,
  partner activity journal/outbox/projections — all in the rules Room store; `DomainEvent` in the
  activity log.
- **Background-operational:** DE `rules_snapshot.json` (package IDs/domains + windows only;
  `RulesSnapshotPublisher`), BootReconciliationStore DE prefs, and the plain accountability DB
  (approval chain, identity epoch, outbox, processed events, unpair state).
- **Backend-restorable:** partner-delivered plans/messages and relay delivery. No server copy of
  the user's local rules/attempts/history exists; no resync path found.
- **Open item:** the plan's vault is singular; this app has two SQLCipher stores and one plain
  operational DB. The accountability DB is operational and outside the v1 vault boundary by
  evidence — recorded here, not silently included.

## 5. Boot and pre-unlock behavior

- `LockedBootReceiver` (manifest `:211-218`, `directBootAware`) handles `LOCKED_BOOT_COMPLETED`:
  loads the DE snapshot through `RulesSnapshotPublisher.loadDeviceProtected()` and marks
  `BootReconciliationStore`; no Room, no Keystore (receiver body and header comment).
- `BootReceiver` (`:106-116`) handles `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`: post-unlock
  authoritative load from the DB, which rewrites the DE copy.
- `MilestoneBootReceiver` (`:124-133`) is post-unlock `BOOT_COMPLETED`; the widget receiver
  (`:241-253`) also handles boot/package-replaced.
- Room/SQLCipher stores are credential-encrypted. Device-protected storage holds only the
  snapshot and boot-reconciliation prefs. Enforcement runs without UI unlock.

## 6. Existing PIN flows and partner approval

- No app privacy/unlock PIN exists (`appPin`/`privacyPin`/`lockPin`/`pin_hash` grep: no results).
  No PIN gate in `MainActivity`.
- The DB key is independent and high-entropy (§2), not PIN-derived.
- Partner signing gate: `MainActivity` `signWithAuthPrompt`/`biometricSign`/`deviceCredentialSign`
  (~`:1006-1240`), tied to `AccountabilityKeyStore`'s 900 s auth window. The biometric prompt is
  deliberately not bound to a CryptoObject so device-credential fallback remains (owner ruling
  2026-10-04). This is partner-held approval and MUST remain unchanged.
- Partner anti-disable approval lives in `ApprovalRequest`/`Outbox` in the plain accountability DB.

## 7. Build, backup, test baseline

- minSdk 26, targetSdk 36, compileSdk 37, versionCode 16 / versionName 1.1.14
  (`app/build.gradle.kts`).
- DI: Hilt `SingletonComponent` modules (`DatabaseModule`, `AccountabilityModule`, `BackupModule`).
- Backup: `android:allowBackup="false"` (manifest `:61`) plus `dataExtractionRules` /
  `fullBackupContent` excluding database, sharedpref, `rules_snapshot.json`, datastore. The app's
  own encrypted backup reads a logical snapshot through `storeFactory.open()`
  (`BackupModule.kt:128`, `BackupUploader`), never a raw DB copy — wrap-only preserves it.
- Tests: JVM suite incl. `AppRulesStoreMigrationTest`, `LocalDatabaseMigrationTest`,
  `SqlCipherRawKeyTest`, `PlaintextConversionFileOpsTest`; instrumented tests; functions Node
  suite.
- Release: signed APK, GitHub Releases updater through PackageInstaller (ADR #171). No generic
  feature-flag framework; only `BuildConfig.DEBUG` and the partner rollout gate.

## 8. §8.7 route decision (exactly one)

**Route 1 — wrap-only.** Plan text: "DB already has independent high-entropy secret: wrap-only
migration. Do not rekey, export, or change SQLCipher file format. No WAL checkpoint is required
solely to wrap the existing secret, though DB verification and safe Room lifecycle still apply."

Application: enrollment unwraps each store's existing DEK with the old KEK and re-wraps it under a
new auth-bound Keystore KEK; disable reverses. Applies to the rules store and — if Phase 2
confirms it is live — the activity-log store. No `sqlcipher_export`, no `PRAGMA rekey`, no
PIN-derived migration.

## 9. §4 component mapping

Reuse: `KeystoreWrappedDekManager` (pattern for `VaultKeyManager`), `KeyFetchingSQLCipherDriver`,
`AppRulesStoreFactory` (becomes `VaultDatabaseProvider`; generation-scoped close/reopen),
`RulesSnapshotPublisher` (`OperationalStateStore`), Hilt modules, `MainActivity`
(`FragmentActivity`/BiometricPrompt host), `SecureSurface` (FLAG_SECURE precedent).

New, absent today (grep-confirmed): `AppLockCoordinator`, `AndroidCredentialAuthenticator`,
`VaultKeyManager`, `VaultEnvelopeStore`, `VaultAccessGate`, `AppLockRootGate`,
`PendingNavigationHandler`, `AppLockSettings`, `AppLockTelemetry`.
`VaultDatabaseProvider` is a refactor of `AppRulesStoreFactory`, not a wrapper beside it.

## 10. Phase 1 decisions and open items

- **D1:** route 1 for both SQLCipher stores; no export/rekey/format change.
- **D2:** vault boundary = the two SQLCipher stores; the plain accountability DB stays
  operational and is flagged, not silently protected.
- **D3:** `AppRulesStoreFactory` gains generation-scoped open/close; the 101 `storeFactory.open()`
  sites are the migration surface and must not cache DAOs across a lease.
- **D4:** operational allowlist = the existing DE snapshot plus BootReconciliationStore; nothing
  new is replicated into device-protected storage.
- **D5 (resolved in Phase 2):** `ActivityLogStore` has no writer or reader. Its only live consumer
  is `activityLogStore.checkpoint()` (`MyExodusApp.kt:431`), and `checkpoint()` returns without
  opening when `db == null` (`ActivityLogStore.kt:206-219`), so a session that never opened the
  store performs no read. Route 1 still wraps its DEK for correctness (the file exists and holds
  ciphertext), but it cannot cause background reads while locked.
- **D6:** the partner signing gate and approval chain remain byte-for-byte untouched.
- **D7:** API 30+ per-use CryptoObject; API 26-29 legacy 30 s validity window; no app PIN.
- No contradiction with the plan was found; the Phase 1 stop-gate is not triggered.

## 11. Phase 2 status — UI shell and state

Plan gate (§7): "Cold launch, rotation, external intents and screen-off conceal protected UI;
existing PIN remains functional."

**Files changed**

- `applock/AppLockState.kt` (new): state enum; `showsProtectedUi` true only for DISABLED/UNLOCKED.
- `applock/AppLockStatusStore.kt` (new): durable prefs (`app_lock_status`) and the pure,
  fail-closed startup mapping (§4.2): absent/"disabled" → DISABLED; "enabled" +
  `active_pending_first_unlock`/`first_unlock_verified` → LOCKED; "enabled" with an interrupted
  journal stage (`staging`, `committing`, …) or a missing/unknown one → RECOVERY_REQUIRED; any
  other feature status → RECOVERY_REQUIRED (never silently DISABLED).
- `applock/AppLockCoordinator.kt` (new): in-process `StateFlow<AppLockState>` seeded from the
  store; session/attempt generations; `onScreenOff`, `lockManually`, `onHostStopped(changing)`,
  `onHostResumed`, and the §8.8 expected-external-operation token (single use, same session
  generation, `elapsedRealtime()` 60 s window, defeated by screen-off/manual lock, never
  persisted).
- `applock/AppLockRootGate.kt` (new): sole parent of the protected tree (§8.2); non-unlocked
  states render a scrollable, TalkBack-labeled lock shell instead.
- `MainActivity.kt`: host was already `FragmentActivity` (:162), no superclass change;
  `FLAG_SECURE` added synchronously before `setContent` when the state is not DISABLED;
  `onStop` reports the host boundary; the entire existing `MyExodusTheme` content is now inside
  `AppLockRootGate`.
- `MyExodusApp.kt`: process-scope `ACTION_SCREEN_OFF` dynamic receiver, guarded by
  `UserManager.isUserUnlocked`; the coordinator is `dagger.Lazy` so a locked-boot process never
  reads credential-encrypted prefs (the prefs are also lazy inside the store).
- `res/values/strings.xml`: eight lock-shell strings.
- `test/.../applock/AppLockCoordinatorTest.kt` (new): 15 Robolectric tests.

**Evidence**

- App Lock unit suite: 15 tests, 0 failures, 0 errors (Robolectric, sdk 34). Covers the startup
  truth table, fail-closed cases, fresh-process locked, screen-off revocation and generation
  increment, config-change session retention, app-switch revocation, token single-use/same
  generation, the 60 s boundary (60 000 ms valid, 60 001 invalid, backwards time invalid),
  manual-lock and screen-off token defeat, DISABLED no-ops, and unlock never surviving a new
  process.
- Phase 1 exit-gate regression suites: enforcement (`EnforcementForegroundServiceTest` 16,
  `EnforcementFgsEnsureTest` 2), boot reconciliation (4), scheduler (`MilestoneSchedulerTest` 5),
  background delivery (`MailboxOutboxDrainerTest` 13, `MailboxOutboxDrainerCrashWindowTest` 9,
  `PartnerSyncCoordinatorTest` 60, `MessageDeliveryTest` 9), DB migrations
  (`AppRulesStoreMigrationTest` 14, `LocalDatabaseMigrationTest` 2) — 0 failures, 0 errors.

**Gate accounting (honest split)**

- PASSED (unit-level): cold-process start locked; screen-off revokes; app switch revokes;
  configuration change keeps the session; external token semantics.
- VACUOUS: "existing PIN remains functional" — no app PIN exists (D7), so the clause has no
  subject. The plan's own instruction is no app PIN in v1.
- BLOCKED (device): visual concealment of the real UI across cold launch, rotation, external
  intents and screen-off. No AVD was connected during this window and no physical device has this
  build; to be executed at the next emulator/device slot. The mechanism is in place end to end in
  production code paths.
- DEFERRED to Phase 6: call sites that spawn trusted external activities do not yet call
  `beginExpectedExternalOperation`; until wired, an enabled lock keeps the app locked after any
  external launch (fail-closed, no bypass). No production path can set the feature "enabled"
  before Phase 5's enrollment writer, so Phase 2 ships dormant for every current user.
- FLAG_SECURE is applied when the state is not DISABLED, including RECOVERY_REQUIRED (ciphertext
  and lock UI can still be on screen).

**Next phase entry conditions (§7 Phase 3)**

`VaultDatabaseProvider` with the Mutex-atomic lease gate, generation-scoped Flow/Paging, and no
injectable standalone DAOs; existing SQLCipher key only (route 1); stale collectors and background
entry points must not read a closed vault. Entry requires this section's unit evidence to remain
green.

## 12. Phase 3 status — vault provider and lease gate

Plan gate (§7 Phase 3): "Lock/unlock with **existing key only**; stale collectors and background
entry points cannot read closed vault." Plan §8.3 names the target class `VaultDatabaseProvider`.

**Design**

- `AppRulesStoreFactory` remains the sole runtime opener of the rules SQLCipher store and now
  owns the §8.3 gate. The rename to `VaultDatabaseProvider` was **not** performed: it would touch
  289 source references for zero behavioral change; the existing class is the provider.
- `open()` throws the new typed `VaultLockedException` unless the app-lock state is DISABLED or
  UNLOCKED (`AppLockState.showsProtectedUi`). `DatabaseModule` supplies that predicate as
  `{ appLock.get().state.value.showsProtectedUi }` with `dagger.Lazy` so the coordinator (which
  reads credential-encrypted prefs) is never constructed in a locked-boot process.
- `withVault(block)` is the operation-scoped lease: admission and lease counting happen under the
  gate monitor, release happens in `finally`. A lease admitted before a lock starts completes;
  admission is refused once the lock transition begins.
- `lockVault(timeoutMs = 2000)` sets `closing`, increments `sessionGeneration`, waits for leases
  with a monotonic `System.nanoTime` deadline (Robolectric freezes `SystemClock.uptimeMillis`),
  then closes Room under the open-path monitor and re-admits. On timeout it returns false and the
  gate **stays closed**; a later call retries. It never force-closes under an active lease.
- Also gated: `checkpoint()` (silent no-op while closed), `resetAndReopen`, `stageRestoreCopy`,
  `verifyRestoreStaging`, `swapInRestore`, `openRecoveryCopy`. `ActivityLogStore` stays ungated
  per D5: it has no reader or writer, and `checkpoint()` returns before opening when no handle
  exists, so it cannot read the vault while locked.
- Lock ordering: `withVault` takes the gate monitor then the open monitor; `lockVault` releases
  the gate monitor before taking the open monitor, so no inversion exists.

**Files changed**

- `data/db/AppRulesStoreFactory.kt` — gate fields, `withVault`, `lockVault`, gated entry points.
- `data/db/VaultLockedException.kt` (new).
- `di/DatabaseModule.kt` — coordinator-backed predicate via `Lazy`.
- `test/.../data/db/VaultGateTest.kt` (new).

**Evidence**

- `VaultGateTest`: 7 tests, 0 failures, 0 errors (Robolectric, sdk 34). Covers disabled access;
  closed gate refusing `open`/`withVault`; block-result passthrough; checkpoint no-op; held-lease
  drain then close (generation +2, fresh handle afterwards); an undrained lock staying closed
  until a retry succeeds; and 6-thread × 25-iteration concurrent lease churn interleaved with 5
  lock/reopen cycles — only `VaultLockedException` observed, reads completed, final lock and
  reopen succeeded.
- `AppLockCoordinatorTest`: 15/15 unchanged.
- Full unit suite: 278 classes / 2,634 tests, 0 failures, 0 errors, 0 skipped (Robolectric, sdk
  34), including both App Lock suites above.
- `:app:lintDebug` alone (ADR #208): 0 errors, 2 warnings — both pre-existing and in files this
  change set does not touch (`ui/rules/BatchEditor.kt:102` ModifierParameter,
  `res/values/strings.xml:305` PluralsCandidate).
- Physical X6532 (.106), signed release build `install -r` (installed `base.apk` md5 matched the
  local artifact): cold launch renders the full protected UI and the vault opens normally while
  the feature is DISABLED; logcat shows no `VaultLockedException`, no `FATAL`, and the process
  stays resumed on `MainActivity`.
- Emulator-5556 (debug build of this change set; required because seeding the CE prefs needs
  `run-as`), `app_lock_status` seeded `feature_status=enabled` +
  `migration_journal=first_unlock_verified`, then cold start: the gate refused the vault —
  `VaultLockedException` thrown at `AppRulesStoreFactory.requireVaultAccess`
  (`AppRulesStoreFactory.kt:153`) from `MyExodusApp.kt:272`, `data_app_crash` recorded, splash
  dropped back to the launcher. This proves the DI predicate reads the real coordinator state and
  no protected content or vault handle is reachable while LOCKED; it also upgrades the Phase 6
  startup gap below from inference to verified behavior. The seeded prefs were deleted afterwards
  and a normal launch was re-verified.

**Gate accounting (honest split)**

- PASSED (unit): every runtime vault entry point refuses access while closed; leases drain before
  close; undrained locks keep the gate closed; concurrent lock/reopen churn is safe.
- PASSED (source, "existing key only"): the store still opens with its existing random DEK via
  `RulesStoreEncryption`; no new key derivation or wrapping was introduced in this phase.
- PASSED (source, single opener): the factory is the only runtime rules-store opener. Remaining
  direct SQLite openers are one-time startup migrations (`DatabaseMigration`,
  `LocalDatabaseMigration`, `PlaintextToEncryptedMigration`, `SqlCipherRawKey`) plus
  `ActivityLogStore`, all recorded in §2/§5 and owned by Phase 5. No Hilt module provides a
  rules-store DAO; `AccountabilityModule`'s DAO providers belong to the plain accountability DB
  (D2).
- DEFERRED to Phase 6 with the lock trigger: no production caller invokes `lockVault`, and
  `AppLockCoordinator.publishUnlocked` has no caller until the Phase 5 enrollment writer exists.
  The feature is therefore dormant for every current user and the close step cannot be exercised
  on a device yet. The predicate half **is** wired now: the moment any state other than
  DISABLED/UNLOCKED exists, every background `open()` throws.
- DEFERRED to Phase 6: §8.3.5 session-flow machinery (`StateFlow<VaultSession?>` + `flatMapLatest`
  + generation-owned scope). Verified there is no background or shared vault Flow collector to
  scope: the only live Room Flow collectors are seven composition-scoped
  `collectAsStateWithLifecycle` sites in `HomeScreen`/`RulesScreen`, disposed when the gate hides
  the tree, and `RulesRepository.observeRules` has no consumer. Shipping unwired session-flow
  plumbing now would be unverifiable dead code; it lands with the first session-shared collector
  and the Phase 6 lock trigger.
- RENAME DEVIATION (recorded): `VaultDatabaseProvider` is a rename of the existing factory, not a
  second class. Deferred as pure churn; §8.3 semantics are implemented in place.
- REMAINING RISK for Phase 6: direct `storeFactory.open()` call sites obtain a handle without a
  lease. Once the lock trigger is wired, an in-flight direct caller can have Room closed
  underneath it after the drain; the read then fails (Room's closed-object error — fail-closed,
  no plaintext exposure) instead of draining cleanly. Multi-statement execution paths must move
  to `withVault` before the trigger ships.
- PHASE 6 GAP (verified on emulator-5556, dormant today): `MyExodusApp.onCreate` launches startup
  maintenance that calls `rulesStoreFactory.open()` and `recoverySessions.interruptStale`
  unconditionally. With a locked state seeded, that launch threw `VaultLockedException`
  (`MyExodusApp.kt:272`) and the process died on the splash before the shell could render; §8.9
  deferral/retry (gate the maintenance behind the coordinator, defer while locked, run on unlock)
  must be wired before the feature can be enabled. It cannot fire for any current user because
  the only reachable startup state is DISABLED until the Phase 5 enrollment writer exists.

**§11 performance baseline (recorded 2026-11-11, end of Phase 3)**

Measured on the low-end physical X6532 (.106) running the signed release build, before any
auth-bound wrapping exists (plan §11: "early baseline at the end of Phase 3"):

- Cold process start (`am force-stop` then `am start -W`): `LaunchState: COLD`,
  `TotalTime 3592 ms`, `WaitTime 3749 ms`.
- SQLCipher rules-store **first actual open** (off-main): **239 ms** on
  `DefaultDispatcher-worker-2`, from the new fresh-open log line in `AppRulesStoreFactory.open()`.
  A cached hit logs nothing (ADR #153 cold-start attribution preserved).
- Room close latency: **not yet measurable** — no production caller invokes `lockVault` until the
  Phase 5/6 trigger; the timer now exists in `lockVault` and records it once wired. This is
  recorded honestly as deferred, not passed.

The instrumentation uses `System.nanoTime()` (Robolectric freezes `SystemClock`) and is silent on
cached hits so log-based cold-start attribution keeps working.

**Next phase entry conditions (§7 Phase 4)**

Disposable test database; API 30+ per-use `CryptoObject` key; API 26-29 legacy time-bound key;
envelope with generated IV and AAD; wrap+verify prompts; an invalid tag must never open the test
DB; real-device crypto tests for both API tiers. This section's unit evidence must stay green.

## 13. Phase 4 status — envelope and authentication-bound wrapping

Plan gate (§7 Phase 4): "Crypto on disposable test DB | API 30+ per-use key; API 26–29 single
time-bound key; envelope with generated IV, AAD, wrap+verify prompts | Real-device crypto tests
for both APIs; invalid tag never opens test DB."

**Design**

- `applock/crypto/VaultEnvelope` — versioned binary envelope, pure JVM. Header `"MXVE"` +
  format version 1, then tagged records (tag `u8`, length `u16`, big-endian, any order):
  key version `u32`, kind `u8` (1 = rules SQLCipher DEK), IV exactly 12 bytes, ciphertext
  including the 128-bit GCM tag with a 16-byte minimum. Strict parse rejects bad magic/version,
  unknown tags, duplicate tags, wrong record lengths, truncation, and any trailing bytes — every
  rejection is a `VaultEnvelopeException` and no partial envelope is returned.
- Canonical AAD (§8.4): domain string + format version + key version + kind + length-prefixed
  application ID + length-prefixed database ID, serialized with `DataOutputStream` (fixed
  big-endian, explicit UTF-8 lengths). No `.toString()` anywhere in the binding.
- `applock/crypto/VaultKeyManager` — AES-256/GCM/NoPadding Android Keystore key,
  `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, `setUserAuthenticationRequired(true)`. API 30+:
  `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)` (per-use).
  API 26-29: `setUserAuthenticationValidityDurationSeconds(30)` (single time-bound key, §8.6).
  Alias is versioned (`myexodus_vault_kek_v1`); an unknown envelope key version is refused before
  any cipher is created.
- `seal(cipher, …)` — the caller hands in the `Cipher` from `encryptionCipher()`; init happened
  with **no IV parameters**, so the Keystore generates it. The operation captures `cipher.iv`,
  requires 12 bytes, applies the AAD, calls `doFinal`, and encodes the envelope. `open(encoded, …)`
  parses strictly first, derives the AAD from the envelope's own stored key version/kind, then
  initializes `DECRYPT_MODE` with `GCMParameterSpec(128, iv)` and authenticates before releasing
  plaintext.
- `applock/AppLockAuthenticator` — capability preflight (`isDeviceSecure` + combined
  `canAuthenticate` on API 30+, `isDeviceSecure` on 26-29, which the Keyguard confirmation
  authorizes); API 30+ `authenticateCrypto()` hands the cipher to
  `BiometricPrompt.CryptoObject` with `setAllowedAuthenticators(BIOMETRIC_STRONG |
  DEVICE_CREDENTIAL)`, `setConfirmationRequired(true)` and no negative button, returns **only**
  the callback's cipher, and re-checks the caller's attempt predicate at the callback boundary;
  a 120-second timeout prevents a dropped OEM sheet from wedging later prompts (the X6532C wedge
  class). API 26-29 `legacyConfirmationIntent()` returns the Keyguard confirmation intent or
  null; the Phase 5 caller must use the cipher immediately on `RESULT_OK`.
- Verified against the pinned `androidx.biometric:biometric:1.1.0` bytecode: `build()` permits
  confirmation + combined authenticators, and forbids a negative button when device credential
  is allowed — the implementation follows exactly that contract (§8.5).
- Two authentication-bound operations are possible through the API; the enrollment flow (Phase 5)
  is what performs encrypt-auth + independent decrypt-verify. No new crypto library was added.

**Files**

- `applock/crypto/VaultEnvelope.kt` (new).
- `applock/crypto/VaultKeyManager.kt` (new).
- `applock/AppLockAuthenticator.kt` (new).
- `test/.../applock/crypto/VaultEnvelopeTest.kt` (new).

**Evidence (unit)**

- `VaultEnvelopeTest`: 11/11 green. Round-trip with generated 12-byte IV; fresh IV per seal;
  tampered ciphertext, tag and IV each fail closed (`AEADBadTagException`); wrong application ID
  and wrong database ID fail closed; canonical AAD changes with every bound field and length
  prefixes separate adjacent fields; malformed envelopes (empty, bad magic, bad version,
  truncation, trailing record) rejected; duplicate and unknown records rejected; wrong lengths /
  unknown kind / unsupported key version rejected; unwrapped secret is exactly 32 bytes.
- Full unit suite: 279 classes / 2,645 tests, 0 failures, 0 errors, 0 skipped.
- `:app:lintDebug` alone (ADR #208): 0 errors, same 2 pre-existing warnings (`BatchEditor.kt:102`,
  `res/values/strings.xml:305`); no new warnings from this change set.
- Robolectric **cannot** stand in for the Keystore: this project's Robolectric runtime has no
  `AndroidKeyStore` provider (`KeyStoreException: AndroidKeyStore not found`), so no fake
  key-store test is shipped. Key generation and auth enforcement are device-verified only.

**Gate accounting (honest split)**

- PASSED (unit): envelope/AAD/tamper semantics above — the "invalid tag never releases the
  secret" property at the boundary that produces the SQLCipher key. The vault open path receives
  a secret only from `open()`; any tag/parse failure throws before a key exists, so no database
  can be opened with it.
- NOT YET EXECUTABLE (carried to Phase 5): "real-device crypto tests for both APIs". The wrap
  and verify prompts are only reachable through the enrollment flow that Phase 5 builds; there is
  no production caller of `seal`/`open` yet. Emulator (API 34/37) verification with PIN 1234 and
  `adb emu finger touch` is the sanctioned vehicle for the CryptoObject legs; the physical X6532
  (owner) verifies the real prompt later.
- UNRESOLVED RELEASE BLOCKER (§12.2): there is **no physical API 26-29 handset** available. The
  legacy time-bound branch is code-complete but cannot be claimed as release-tested; per plan
  §12.2 it is recorded as an unresolved release blocker, not as coverage.

**Next phase entry conditions (§7 Phase 5)**

WAL-aware staged migration or wrap-only migration (the Phase 1 inventory selected wrap-only for
an install whose rules DB already has an independent random DEK); atomic staging/active envelope
persistence and migration journal in no-backup storage; enrollment encrypt-auth + decrypt-verify;
first-real-unlock cleanup; disable/recovery. The §12/§13 evidence above must stay green.

## 14. Phase 5 status — wrap-only migration, journal and recovery

**What shipped (plan §6.1, §8.7 route 1, §8.11, §8.12)**

- `applock/crypto/VaultSecretBundle.kt` — the plaintext payload of the single envelope: one
  framed 256-bit SQLCipher DEK per vault database (rules + activity log). Strict decode: exactly
  68 bytes, each frame exactly 32; any deviation is a `VaultEnvelopeException`, never a partial
  bundle. The plain accountability store is operational and never bundled (inventory D2).
- `applock/crypto/VaultEnvelopeStore.kt` — single-envelope persistence under
  `noBackupFilesDir/vault`: fsync'd `.tmp` write then same-directory `ATOMIC_MOVE`; staging and
  active are separate files; an interrupted `.tmp` is swept at startup and never read as an
  envelope. An envelope restored onto another device without the original Keystore key can only
  surface as RECOVERY_REQUIRED, never as a fresh install.
- `applock/VaultDatabase.kt`, `applock/VaultStoreKeys.kt` — the two vault database identities
  and the migration seam (`unwrapLegacy` / `adoptLegacy` / `deleteLegacyMaterial` / `verify`).
- `applock/VaultMigration.kt` — journal-first orchestrator: `enroll`, `disable`,
  `completeFirstUnlock`, `recoverInterrupted`.
- `data/db/SqlCipherVaultKeys.kt` — production seam over the existing Keystore-wrapped DEKs
  (`RulesStoreKeyManager.getOrCreateDek`, `ActivityLogKeyManager.getOrCreateKey`); `adoptLegacy`
  re-wraps through the same pre-feature KEK (`KeystoreWrappedDekManager.adoptKey`); `verify`
  opens the real SQLCipher file with the raw key and reads `sqlite_master` (a missing file is
  not an error and is never created).
- `applock/AppLockStatusStore.kt` — journal constants and `restoreActivePendingJournal`.
  Unknown/half-written durable state still maps to RECOVERY_REQUIRED (INV-10).
- `applock/crypto/VaultEnvelope.kt` — `KIND_VAULT_DEKS = 2` added to the supported set and bound
  into the canonical AAD, so a kind swap also fails the tag.
- `applock/crypto/VaultKeyManager.kt` — `kind` on `seal`; `generateAndroidKey` moved to the
  companion (constructor default lambdas cannot call instance methods).
- `di/DatabaseModule.kt` — providers for `VaultKeyManager`, `VaultEnvelopeStore`,
  `VaultStoreKeys`.

**Crash ordering (verified by tests)**

- Enroll: staging journal → unwrap both DEKs → prompt-authorized encrypt → durable staging write
  → read-back → a second, independently initiated prompt-authorized decrypt → bundle equality →
  both stores actually open with their DEKs → rename envelope to active → one atomic prefs edit
  (enabled + `active_pending_first_unlock`). A crash therefore never leaves "enabled" without its
  key. Any pre-commit failure discards staging and rolls the journal to none; a crash that leaves
  the staging journal is swept by `recoverInterrupted`, which also deletes the unused auth-bound
  alias. A clean cancel keeps the alias (harmless: it wraps nothing, and `createKey` never
  replaces existing material).
- Disable: `beginDisabling` → re-wrap each DEK under the pre-feature KEK → read it back through
  that wrap and open the real store → single `commitDisabled` edit → only then delete the
  auth-bound envelope and alias (best-effort; idempotent at startup). The only key is never
  deleted while the feature is enabled.
- `recoverInterrupted` is journal-first. DISABLED sweeps leftover staging/active artifacts and
  the alias. LOCKED requires the active envelope; a missing one is RECOVERY_REQUIRED with
  nothing touched. An interrupted disable returns to `active_pending_first_unlock` — never
  "verified", because premature marking would leave the pre-feature wrap usable as a bypass —
  and a verified first-unlock cleanup is finished idempotently. Unknown states are preserved
  untouched (INV-06/INV-10).

**Evidence (unit, this change set)**

- `VaultMigrationTest` 18/18: happy path (one envelope, enabled, pending journal, exactly two
  prompt calls); refusal when already enabled and when the vault cannot quiesce (no durable
  write); cancel at either prompt rolls back; a swapped verification key → `crypto_error`, no
  commit; a store the recovered key cannot open → `db_verify_failed` and the first failing
  verify short-circuits; crash-after-staging swept to DISABLED; enabled without its envelope →
  RECOVERY_REQUIRED with the journal preserved; unknown status + artifact preserved untouched;
  interrupted disable → LOCKED, journal restored, envelope kept; interrupted first-unlock
  cleanup completes at startup; `completeFirstUnlock` is idempotent and a no-op when disabled;
  disable round-trip re-wraps under the legacy KEK and removes the vault; a failed disabled-mode
  verify rolls the journal back and keeps the vault; disable refuses when not enabled or busy.
- `VaultSecretBundleCodecTest` 4/4: round-trip; a wrong DEK size is refused on encode; any total
  length deviation is rejected; a mis-framed DEK length is rejected.
- `VaultEnvelopeTest` 13/13 (adds the `KIND_VAULT_DEKS` round-trip and proves the kind is
  AAD-bound).
- Full JVM suite: 281 classes / 2,669 tests, 0 failures, 0 errors, 0 skipped.
- `:app:lintDebug` alone (ADR #208): 0 errors, the same 2 pre-existing warnings
  (`BatchEditor.kt:102`, `res/values/strings.xml:305`); no new warnings.

**Gate accounting (§7 Phase 5)**

- PASSED (unit): journal ordering, rollback, crash recovery, disable inversion, and the
  "enabled is never observable without its key" invariant, with real AES-GCM on a software key.
- The §7 clause "old PIN privacy route removed only after commit" is **vacuous**: inventory
  §6/D7 records that no app PIN exists (partner approval is the only existing gate), so there
  was no route to remove and nothing was deleted for it.
- A still-plaintext legacy store fails the strict DEK probe, so enrollment refuses
  non-destructively (`db_verify_failed`) and leaves the file untouched for the existing
  conversion-on-open path; route 3 (export/rekey) remains out of scope by the §8.7 decision.
- NOT YET EXECUTABLE (carried to Phase 6/7): the two prompt-authorized Keystore operations
  (per-use `CryptoObject` on API 30+, Keyguard window on 26-29) need a real prompt; they are
  emulator/manual-device legs. No production caller enables the feature yet, so the enrollment
  authorizer/prompt wiring and any enrollment UI are Phase 6 work.
- UNRESOLVED RELEASE BLOCKER (§12.2): the API 26-29 physical tier is unchanged.

**Phase 6 entry conditions**

Wire enrollment/disable to `VaultMigration` with an `AppLockAuthenticator`-backed
`VaultCipherAuthorizer`; gate the `MyExodusApp.kt:272` startup open behind the coordinator and
defer it while locked (the Phase 3 crash proved this gap); reconcile `LocalDatabaseMigration`
scope switching with the vault-enabled DEK source; backup compatibility and lock triggers. The
§12-§14 evidence above must stay green.
