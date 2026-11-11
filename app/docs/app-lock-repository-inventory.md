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
- **D5:** `ActivityLogStore` has no live open/append/read caller besides `checkpoint()`
  (`MyExodusApp.kt:415`); verify in Phase 2 before including it in the gate — it may be a dead
  surface.
- **D6:** the partner signing gate and approval chain remain byte-for-byte untouched.
- **D7:** API 30+ per-use CryptoObject; API 26-29 legacy 30 s validity window; no app PIN.
- No contradiction with the plan was found; the Phase 1 stop-gate is not triggered.
