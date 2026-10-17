# MyExodus Secure Persistence Layer — Implementation Plan

## 1. Feature Overview

MyExodus needs a local, encrypted, relational persistence layer for user profiles, blocking rules, usage telemetry, habit progress, coaching content, and synchronization events. The database runs fully offline, encrypts its contents at rest, and protects its encryption key using Android Keystore. It is built with Room 3 and SQLCipher for Android, targeting a single-device, solo-developer V1 with no server-side key recovery.

This spec supersedes all prior draft research on this feature. Every open question from that research and its audits is resolved below. Where a prior draft claimed unverified numbers, hardened-enterprise threat protection, or undecided trade-offs, those are replaced here with a single, buildable decision.

**Audit revision 2026-10-17.** This spec shipped. Current pins are Room **3.0.3**, SQLCipher 4.18.0, androidx.sqlite 2.7.0 (`gradle/libs.versions.toml`) — the 3.0.1 references below are the versions at writing. The "Play Store upload" wording in §13 is dead: distribution is direct APK only (ADR #15); read any such mention as "first release build."

## 2. Requirements

### Functional
- Persist six entities: UserProfile, RestrictionRule, DailyUsageTracker, HabitProgress, CoachingStep, DomainEvent.
- Support Room DAO-based CRUD and transactions for all entities.
- Enforce that package `com.example.myexodus` can never appear as a `blocked_package` value, on insert or update.
- Enforce exactly one active UserProfile row at a time.
- Prevent duplicate `(profile_id, package_name, date_epoch)` usage records.
- Prevent duplicate `(profile_id, blocked_package)` rule records.
- Support idempotent DomainEvent creation for later sync (sync transport itself is out of scope for this spec).

### Non-Functional
- Database file is unreadable without the correct key (SQLCipher AES-256 page encryption).
- Database encryption key is never hardcoded and never stored in plaintext.
- No database key or decrypted row data appears in logs.
- Database work never runs on the main thread.
- No automatic destructive deletion on ordinary open failures.

## 3. Existing System Context

Before writing any code, inventory the current app module for:
- Any existing Room database class, entities, or DAOs.
- Any existing Hilt `@Module` providing a database instance.
- Current `minSdk`/`targetSdk` in `build.gradle`.
- Current `AndroidManifest.xml` backup configuration (`android:allowBackup`, `android:fullBackupContent`, `dataExtractionRules`).
- **Whether any unencrypted (plaintext) Room database has ever shipped to a real device.** This determines whether Step 6a (plaintext-to-encrypted migration) is required at all. If no unencrypted build has ever reached a user's device, skip Step 6a entirely — it is dead code solving a problem that doesn't exist yet, and adding it unconditionally would violate this project's own preference against unnecessary complexity. See Open Question in Section 16.

If a Room database already exists in the project (encrypted or not), this spec's schema and key manager replace it via a Room migration path (Step 6), not a rewrite. Do not modify unrelated app modules (UI, AccessibilityService/UsageStatsManager detection logic, accountability-partner sync) — those are out of scope for this feature.

## 4. Architecture

```
Application
    |
    v
Repositories
    |
    v
Room 3 DAOs / Transactions
    |
    v
SQLiteDriver (androidx.sqlite)
    |
    v
SQLCipherDriver (net.zetetic) — encrypted SQLite file
    |
    v
DatabaseKeyManager
    |
    +--> Android Keystore AES-256-GCM KEK (StrongBox if available, else TEE)
    |
    +--> Wrapped 256-bit DEK (SharedPreferences, private mode)
```

**DatabaseKeyManager** — creates/retrieves the KEK, generates the DEK on first run, wraps/unwraps it, classifies key-related failures. Never touches DAOs or database files directly.

**DatabaseFactory** — loads the SQLCipher native library, obtains the DEK from DatabaseKeyManager, builds the `SQLCipherDriver`, constructs the Room database, registers migrations.

**RecoveryManager** — the only component permitted to delete database files, and only after failure classification (Section 9) determines deletion is the correct and user-confirmed action.

## 5. Architecture Decisions

1. **Room 3.0.1, using the `androidx.room3` group and artifact coordinates** (`androidx.room3:room3-runtime:3.0.1`, `androidx.room3:room3-compiler:3.0.1` via KSP) — Room 3 lives in a renamed package/Maven group, not `androidx.room`. All imports (`androidx.room3.RoomDatabase`, etc.) and DAO methods (suspend functions or `Flow` return types — Room 3 is coroutines-first) must use this namespace. Chosen because it's the current stable Room line and drops the legacy SupportSQLite dependency this project doesn't need.
2. **SQLCipher for Android 4.18.0 + androidx.sqlite 2.7.0**, using the official `SQLCipherDriver` documented for Room 3 integration. Chosen over the legacy `SupportOpenHelperFactory` wrapper because it avoids the older single-connection locking model.
3. **`CHECK` constraints and partial (`WHERE`-clause) unique indexes are created via raw SQL in a `RoomDatabase.Callback.onCreate()`, not via Room entity annotations.** Room's `@Index` and entity annotations cannot express either feature. This applies to the Rule 4 constraint on `RestrictionRule` and the single-active-profile index on `UserProfile` — both are raw SQL, both must also be re-applied in every future destructive-recreate path (fresh installs) and re-verified after any schema migration that recreates the table.
4. **Rule 4 constraint is case- and whitespace-insensitive by construction:** `CHECK (TRIM(LOWER(blocked_package)) != 'com.example.myexodus')`. A bare case-sensitive check would let `com.example.MyExodus` or a trailing-space variant through.
5. **Plaintext-to-encrypted database migration (Step 6a) is conditional, not automatic.** It only exists in the codebase if Section 3's inventory confirms an unencrypted database has previously shipped. If this is a fresh, never-shipped-unencrypted install base, `DatabaseFactory` builds directly against `SQLCipherDriver` with no pre-flight file inspection.
3. **WAL journal mode enabled.** Chosen for read/write concurrency during telemetry writes. WAL still permits only one writer at a time — no code path may assume concurrent writes will run in parallel; batch writes into short, sequential transactions instead.
4. **No fixed connection-pool size configured.** Room's own pool defaults are used as-is. Do not hardcode a pool size; only tune after profiling shows an actual bottleneck.
5. **DEK: random 256-bit key, generated once, wrapped by an Android Keystore AES-256-GCM KEK.** StrongBox is requested opportunistically (`setIsStrongBoxBacked(true)`) with a caught fallback to standard TEE-backed Keystore on devices without a Secure Element — StrongBox is not a hard requirement given target device range. The KEK is generated with `setUserAuthenticationRequired(false)` and `setInvalidatedByBiometricEnrollment(false)` — this key protects local app data, not a user-facing credential, and must survive the user changing their device PIN or enrolling a new fingerprint. Do **not** pin a fixed `GCMParameterSpec`/IV into the `KeyGenParameterSpec` — Android Keystore generates a fresh random IV per encryption operation automatically; hardcoding one would break GCM's security guarantee through nonce reuse.
6. **`android:allowBackup="false"`** for this app. Chosen because Android Auto Backup can restore the encrypted database file to a new device/account without the matching Keystore-wrapped key, guaranteeing an unrecoverable-database state. Disabling backup entirely eliminates this failure class outright rather than requiring a recovery-token system this project has no server infrastructure to support.
7. **No server-side key escrow or recovery token.** The app is offline-first with no account system. If the Keystore key is permanently lost (factory reset, Keystore wipe), the local database is unrecoverable. This is an accepted, documented trade-off, not a defect to engineer around in V1.
8. **DomainEvent immutability enforced at the DAO layer only** (no UPDATE/DELETE DAO methods exposed), not at the database or cryptographic layer. A tamper-evident ledger is out of scope for V1 given project scope and timeline. Its `installation_id` is a UUID generated once on first app launch and persisted in a private `SharedPreferences` file — not derived from anything that changes on database reset — so sync idempotency survives a local database reset.
9. **No destructive automatic recovery**, and orphaned `.corrupt` files are surfaced, not silently abandoned. A database open failure triggers classification (Section 9), not deletion. `DatabaseFactory` checks for a leftover `*.db.corrupt` file on every startup and exposes a "recoverable data found" state to the UI if one exists, rather than letting it sit unreferenced on disk. Deletion of the live database only happens through an explicit, user-confirmed "Reset MyExodus data" action.
10. **All timestamps stored as epoch milliseconds UTC**; `date_epoch` stored as UTC epoch-day integer (days since epoch, not seconds/millis) to keep daily aggregation timezone-safe.
11. **Benchmark-driven claims dropped.** No specific throughput or latency numbers are treated as requirements. Performance is measured against the app's own target device class post-implementation (Section 11).

## 6. Data Flow

### Startup / database open
```
App start
  -> DatabaseKeyManager.getOrCreateDek()
       -> KEK exists in Keystore? use it : generate it (StrongBox, fallback TEE)
       -> Wrapped DEK exists in SharedPreferences? unwrap it : generate + wrap new DEK
  -> DatabaseFactory.build(dek)
       -> [CONDITIONAL — only if Section 3 confirms a plaintext DB has previously shipped]
          Is existing db file plaintext (first 16 bytes == "SQLite format 3")?
            -> yes: run sqlcipher_export() migration (Step 6a), replace plaintext file with encrypted one
       -> System.loadLibrary("sqlcipher")
       -> SQLCipherDriver(dek)
       -> Room.databaseBuilder(...).setDriver(...).setJournalMode(WAL).addCallback(SchemaConstraintsCallback).build()
  -> On startup, also: check for orphaned "*.db.corrupt" file -> if present, expose "recoverable data found" UI state
  -> On success: app proceeds normally
  -> On failure: RecoveryManager.classify(exception) (see Section 9)
```

### Normal write
```
Repository -> DAO -> Room transaction -> SQLCipherDriver -> encrypted page write
```

### Rule 4 enforcement
```
INSERT or UPDATE on restriction_rules
  -> SQLite evaluates CHECK (TRIM(LOWER(blocked_package)) != 'com.example.myexodus')
  -> match (case/whitespace-insensitive)? -> statement fails, SQLiteConstraintException
  -> DAO catches SQLiteConstraintException -> throws domain-level ProtectedPackageException
```

## 7. Implementation Sequence

1. Confirm answer to Section 3's plaintext-migration question, then pin dependency versions: `androidx.room3:room3-runtime:3.0.1` / `androidx.room3:room3-compiler:3.0.1` (KSP), `androidx.sqlite:sqlite:2.7.0`, `net.zetetic:sqlcipher-android:4.18.0`, matching Kotlin/KSP versions, in `build.gradle`. Confirm project's current `minSdk` meets the driver's minimum; raise it if not.
2. Set `android:allowBackup="false"` in the manifest.
3. Implement `DatabaseKeyManager` (Section 8).
4. Implement `DatabaseFactory` wiring the key manager into `SQLCipherDriver` and Room, behind a Hilt `@Singleton` provider.
   - 4a. **[Conditional]** If Step 1's answer confirmed a plaintext DB has shipped: implement the pre-flight plaintext detection + `sqlcipher_export()` migration routine before the Room builder runs (Section 8). Skip entirely otherwise.
5. Implement the six entities and DAOs. Add `CHECK`/partial-index constraints via a `RoomDatabase.Callback.onCreate()`, not entity annotations.
6. Write the initial Room migration (or fresh schema if no prior database exists), re-applying the raw-SQL constraints from Step 5 in the migration's `onCreate`/`onMigrate` path too.
7. Implement `RecoveryManager`: failure-classification flow (Section 9) plus the startup check for an orphaned `*.db.corrupt` file.
8. Wire DAO-level exception translation for the Rule 4 `CHECK` constraint violation.
9. Write unit tests (fake `DatabaseKeyManager`) and instrumentation tests (real Keystore, real SQLCipher) per Section 12.
10. Manual test pass: fresh install, force-stop, reboot, low-storage simulation, factory-reset-equivalent (clear app data), and (if Step 4a applies) an install seeded with a plaintext database — to confirm graceful behavior rather than a crash loop.

## 8. Detailed Implementation Steps

### DatabaseKeyManager
```kotlin
interface DatabaseKeyManager {
    fun getOrCreateDek(): ByteArray
    fun classifyFailure(e: Throwable): KeyFailure
    fun resetKeyMaterial() // used only by RecoveryManager after user confirmation
}
```
- KEK: AES/GCM/NoPadding, generated via `KeyGenParameterSpec` in `AndroidKeyStore`, StrongBox requested via `setIsStrongBoxBacked(true)` wrapped in try/catch that retries without StrongBox on `StrongBoxUnavailableException`. Set `setUserAuthenticationRequired(false)` and `setInvalidatedByBiometricEnrollment(false)` explicitly — otherwise OEM/OS defaults vary and the key can be silently invalidated by a PIN change or new fingerprint enrollment, triggering an unnecessary "data lost" recovery flow. Do **not** set an `AlgorithmParameterSpec`/fixed IV on the `KeyGenParameterSpec` — let Android Keystore generate a fresh random IV per encrypt call at `Cipher.init()` time; a hardcoded IV causes GCM nonce reuse across encryptions, which is a critical cryptographic break, not a simplification.
- DEK: 256-bit `SecureRandom` value, generated once, encrypted with the KEK, stored (IV + ciphertext + tag) in a private `SharedPreferences` file.
- Do not store the DEK as a `String` anywhere. Pass the `ByteArray` directly to `SQLCipherDriver`. Do not zero the array yourself before the driver has consumed it — let the driver own its lifecycle. (Known limitation: the JVM has no secure-memory primitive, so the plaintext DEK will remain in heap memory until garbage-collected; this is accepted for V1 and not solvable without a custom native wrapper.)
- `installation_id`: a UUID generated once on first launch (if absent), stored in a private `SharedPreferences` entry separate from the wrapped DEK, and injected into the repository layer wherever `DomainEvent` rows are created. It must never be regenerated on database reset, or DomainEvent sync idempotency breaks.

### Schema constraints (apply to all six entities)

Room entity annotations (`@Entity`, `@Index`, `@ColumnInfo`) define the base table and ordinary unique indexes. `CHECK` constraints and any partial (`WHERE`-clause) index — neither of which Room's annotation processor can generate — are created via raw `execSQL()` calls in a `RoomDatabase.Callback.onCreate()`, and must be re-issued in any migration path that recreates these tables:

- `UserProfile`: partial unique index (raw SQL, via callback) — `CREATE UNIQUE INDEX ux_user_active ON user_profile(is_active) WHERE is_active = 1;` — enforces single active profile.
- `RestrictionRule`: `CHECK (TRIM(LOWER(blocked_package)) != 'com.example.myexodus')` (raw SQL, via callback — case/whitespace-insensitive); unique index on `(profile_id, blocked_package)` (Room `@Index(unique = true)` is sufficient here); `CHECK (severity_level IN ('LOW','MEDIUM','HIGH','CRITICAL'))` (raw SQL, via callback).
- `DailyUsageTracker`: unique index on `(profile_id, package_name, date_epoch)` — Room `@Index(unique = true)`.
- `HabitProgress`, `CoachingStep`: as originally scoped, foreign keys `ON DELETE CASCADE` to their parent.
- `DomainEvent`: unique index on `(installation_id, event_id)` — Room `@Index(unique = true)` — both columns `NOT NULL`; no DAO `@Update`/`@Delete` methods defined.

### Plaintext-to-encrypted migration (Step 6a — conditional, see Section 3)

If required: on startup, before building the Room database, `DatabaseFactory` reads the first 16 bytes of the existing db file. If they match the literal string `SQLite format 3`, the file is plaintext. Open it with the standard (unencrypted) `android.database.sqlite.SQLiteDatabase`, `ATTACH` a new file as an encrypted database using the DEK as a raw key, run `sqlcipher_export()` to copy all data into the encrypted attachment, `DETACH`, then replace the original file with the newly encrypted one. Only after this succeeds does the normal `SQLCipherDriver` open path run. If this step is not required per Section 3, do not implement it — do not add pre-flight file-format inspection to a codebase with no plaintext database in the wild.

### RecoveryManager failure classes
| Exception observed | Classification | Action |
|---|---|---|
| `SQLiteConstraintException` (Rule 4 / uniqueness) | Application-level validation error | Surface to caller, do not touch files |
| `SQLiteNotADatabaseException` / code 26, DEK unwrap succeeded | Corruption or version mismatch | Preserve files, show "database issue" state, offer manual reset |
| DEK unwrap fails (`KeyPermanentlyInvalidatedException`, missing wrapped DEK) | Key unavailable | Preserve files, show "data unavailable, key lost" state, offer manual reset (destroys files only on confirmation) |
| `SQLiteDatabaseCorruptException` | Confirmed corruption | Rename to `<name>.db.corrupt`, create fresh database, and immediately record a persistent flag (not just an in-memory state) that a `.corrupt` file exists |
| I/O error, storage full | Transient/environmental | Retry with backoff; surface storage-full UI state if persistent |

Manual reset (user-confirmed only) deletes `.db`, `.db-wal`, `.db-shm`, any `.db.corrupt` file, and the wrapped-key entry, then regenerates a fresh DEK and empty database.

**Orphan detection:** every app startup, `DatabaseFactory`/`RecoveryManager` checks for a `*.db.corrupt` file regardless of whether the current open succeeded. If one exists, the UI must expose a "recoverable data found" affordance (view/export/discard) — a renamed corrupt file is never left silently on disk with no path back to the user's attention. This check runs every launch until the user explicitly discards or exports the `.corrupt` file.

## 9. Error Handling

- Every database-open failure is classified per the table above before any file is touched.
- No code path deletes `.db`/`.db-wal`/`.db-shm` without going through `RecoveryManager`, and `RecoveryManager` only performs destructive deletion after explicit user confirmation or a confirmed-corruption classification that also preserves a renamed copy of the original file.
- `SQLITE_BUSY` conditions are retried with a bounded backoff (3 attempts, 200ms/500ms/1000ms), not a blanket 10-second timeout on every operation.
- Rule 4 violations surface as a typed `ProtectedPackageException`, caught at the DAO boundary, never as a raw `SQLiteConstraintException` in application code.
- If `.db-wal`/`.db-shm` are missing but the main `.db` file opens successfully (e.g. a storage-cleaner app removed them), let SQLite recreate them on next write; do not treat their absence alone as a failure condition.

## 10. Security Requirements

- Database key material exists only as a `ByteArray`, never a `String`, never logged, never written to disk unencrypted.
- KEK is Keystore-resident and non-exportable; StrongBox used opportunistically, TEE as fallback — this is documented as sufficient for the current threat model (offline extraction of the device or its storage), not as protection against a compromised, actively-running app process, which is out of scope.
- `android:allowBackup="false"` set explicitly; no reliance on `dataExtractionRules` exclusion lists as the primary control.
- Rule 4 (`CHECK` constraint) covers both INSERT and UPDATE by construction.
- No raw SQL execution is exposed outside of migrations.

## 11. Performance Requirements

No numeric targets are inherited from prior research. After implementation, measure on the actual target low/mid-range device class:
- Cold database open time (KEK unwrap + DEK unwrap + Room build).
- Single insert and 50-row batch insert latency.
- Concurrent read while a write transaction is in progress (confirm no ANR).

Optimize only if a measured number causes a visible UX problem (e.g., cold open blocking app launch); do not pre-optimize KDF iteration counts or connection pooling ahead of a demonstrated need.

## 12. Testing Requirements

**Unit tests (JVM, fake `DatabaseKeyManager`):**
- Rule 4 rejection on insert and on update, including a mixed-case package name (`com.example.MyExodus`) and a trailing-space variant — both must be rejected.
- Single-active-profile constraint.
- Duplicate usage-record rejection.
- DomainEvent duplicate `(installation_id, event_id)` rejection.

**Instrumentation tests (real device/emulator, real Keystore + SQLCipher):**
- Fresh database creation and encrypted read/write round-trip.
- Migration from a prior schema version (once one exists) preserves data, and re-verifies the raw-SQL `CHECK`/partial-index constraints still exist after migration.
- App restart re-opens the existing encrypted database correctly.
- Simulated `KeyPermanentlyInvalidatedException` path routes to the "key lost" recovery state, not a silent crash.
- Confirm `PRAGMA cipher_version` returns a value at runtime (encryption is actually active, not silently bypassed by a conflicting native SQLite library).
- A `.db.corrupt` file placed on disk before launch is detected and surfaced as a "recoverable data found" UI state, not silently ignored.
- **[Conditional — only if Step 4a applies]** Seed a plaintext SQLite database, launch the app, confirm it migrates to encrypted format with all data intact and `PRAGMA cipher_version` confirms encryption afterward.

## 13. Deployment Requirements

- Dependency versions (Room, SQLCipher, androidx.sqlite, Kotlin, KSP) pinned exactly in `build.gradle`, not ranges.
- Verify ProGuard/R8 keep rules for `net.zetetic.**` classes in the release build before first Play Store upload.
- Manual data-preservation test before shipping any future schema change: install current version with sample data, upgrade, confirm data intact and encryption still functional.

## 14. Monitoring Requirements

Given solo-dev scope, monitoring is local logging only (no crash-reporting backend required for V1):
- Log (non-sensitive) counts of: database-open failures by classification, Rule 4 rejections, recovery actions taken.
- Never log key material, decrypted row contents, or raw SQL parameters.

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Keystore key lost (factory reset, OS-level Keystore wipe) | Low | Critical (unrecoverable local data) | Documented accepted trade-off; manual reset flow avoids crash loop |
| Native library version mismatch after a dependency bump | Low | Critical | Version pinning; `PRAGMA cipher_version` check in instrumentation test |
| WAL file growth under sustained telemetry writes | Medium | Medium | Periodic `PRAGMA wal_checkpoint(PASSIVE)` on app background plus SQLite's own automatic checkpoint (default every ~1000 pages). Deliberately not using `TRUNCATE`: it can block while a reader is active, which is a worse UX trade than occasional WAL growth for this app's write volume. |
| Rule 4 constraint accidentally dropped in a future migration | Low | High | Instrumentation test asserting the CHECK constraint exists, run in CI before every release |
| StrongBox unavailable on target device | Medium | Low | Handled via caught fallback to TEE at key-generation time |

## 16. Open Questions

- **Has any build of MyExodus with an unencrypted Room database ever been installed on a real device (including your own test devices, TestFlight-equivalent, or early testers)?** If yes, Step 4a (plaintext migration) is required and must be built before release. If no, skip it entirely — do not build speculative migration code for a database that has never existed unencrypted in the field.
- Is a future V2 sync layer expected to introduce server-side key escrow, which would change Decision 7 above? (Not needed for V1; flag for revisit if accountability-partner sync expands to include recovery.)
- What is the actual telemetry retention period for `DomainEvent` before local pruning is needed? Not required for initial ship but should be decided before `DomainEvent` volume becomes large.
- Exact target minimum Android API level for the device range this app ships to in Kenya — confirms whether the SQLCipher/Room driver combination's minSdk floor is compatible without raising the project's current minSdk.
- Actual SQLCipher AES-256 CPU/battery overhead on the low-end device tier this app targets, under sustained telemetry writes — not measured yet, worth a quick spike before assuming it's negligible.

## 17. Acceptance Criteria

- Database is unreadable without the correct key (verified by attempting to open the raw file with a wrong/no key in a test).
- No database key or decrypted content appears in logcat output.
- Rule 4 rejects `com.example.myexodus` on both insert and update.
- Only one `UserProfile` row can have `is_active = 1` at a time.
- Duplicate `(profile_id, package_name, date_epoch)` and `(profile_id, blocked_package)` rows are rejected.
- Rule 4 rejects mixed-case and trailing-whitespace variants of the protected package, not just an exact-case match.
- App does not crash-loop on a simulated key-loss or corruption scenario; it reaches a defined recovery UI state instead.
- A `.db.corrupt` file left on disk from a prior corruption event is detected on next launch and surfaced to the user, not silently orphaned.
- `android:allowBackup="false"` confirmed in the built manifest.
- All dependency coordinates use the `androidx.room3` group, and the project builds successfully against it.
- All listed instrumentation tests pass on at least one real or emulated device running the project's actual minSdk.

## 18. Definition of Done

1. All dependency versions pinned and building successfully.
2. `DatabaseKeyManager`, `DatabaseFactory`, `RecoveryManager` implemented as specified.
3. All six entities and their constraints implemented and migrated cleanly from any pre-existing schema.
4. Unit and instrumentation test suites in Section 12 passing.
5. Manual test pass (fresh install, restart, simulated key loss, simulated corruption) completed with no crash loops.
6. Acceptance criteria in Section 17 verified and checked off.
7. Open Questions in Section 16 either resolved or explicitly deferred with owner sign-off.
