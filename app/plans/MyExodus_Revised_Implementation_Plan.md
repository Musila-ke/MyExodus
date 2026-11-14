# MyExodus Optional Accounts, Encrypted Backup/Restore, and Account Lifecycle — Implementation Plan

## 1. Feature Overview

Build optional persistent accounts for MyExodus while preserving anonymous guest use. V1 includes account authentication, safe guest-to-account transition, client-side encrypted backup/restore, local account isolation, secure pair lifecycle operations, and durable account deletion.

**Distribution model:** MyExodus is distributed as a signed APK directly, not through Google Play. Direct distribution is the primary release channel. Do not assume that sideloaded devices lack Google Play services, and do not assume that all Android devices have them. Core guest functionality and email/password authentication must work without Google Play services. Google sign-in and FCM are capability-dependent.

**V1 product boundary:** This feature provides encrypted snapshot backup and restore, not continuous multi-device synchronization. It does not merge concurrent remote snapshots, back up transient device state, export hardware-bound E2EE private keys, or automatically trust a new E2EE device. V1 does not implement E2EE key recovery or trusted-device approval. A new device must explicitly re-pair with partners; earlier device-key-encrypted history is shown as unavailable on that device.

**Release boundary:** Ship the Firestore/pair security hotfix as Release 0 before beginning account UI and backup work. Follow with Release 1 accounts and safe guest transition, Release 2 encrypted backup/restore, and Release 3 durable account deletion. Do not bundle all four releases into one rollout.

## 2. Requirements

### 2.1 Functional requirements

1. Preserve fully usable anonymous guest mode. No account may be required for existing local-only features.
2. When a guest links an unused Google or email/password credential, use Firebase `linkWithCredential` and preserve the anonymous UID and local data. Firebase documents this anonymous-upgrade flow at https://firebase.google.com/docs/auth/android/anonymous-auth.
3. When a credential is already associated with another Firebase UID, do not delete, unlink, or overwrite the existing account. Offer: cancel and remain a guest; sign in to the existing account and import guest logical data; or sign in and discard guest logical data after a separate destructive confirmation.
4. Do not terminate guest pairs before the target account sign-in succeeds. Create a non-destructive guest-transition intent while the guest session is active; finalize the transition only after the target account is authenticated. If sign-in fails or is cancelled, cancel or expire the intent without changing pairs.
5. After a successful switch to an existing account, guest data import is additive and idempotent. It must not import guest pair identities, E2EE keys, device state, push tokens, database keys, or outbox messages. If an import fails, keep the target account signed in and retain a local encrypted/quarantined guest snapshot for retry. “Undo” means re-running the import from quarantine; it does not restore the old anonymous Firebase identity or reactivate terminated pairs.
6. Keep a local guest-data quarantine for seven days after a successful import. Tell the user about this retention and offer “Delete quarantined guest data now.” Do not include the quarantine in Android Auto Backup or make it visible to the target account. A user who chose permanent discard gets an explicit confirmation and immediate local quarantine deletion after the server has accepted the guest-transition cleanup.
7. Support Google sign-in using Android Credential Manager on tested devices where the supported Google identity dependencies are available. Use the Web Application OAuth Client ID for `GetGoogleIdOption.setServerClientId`; also configure the Android package and signing-certificate fingerprints as required by Firebase: https://firebase.google.com/docs/auth/android/google-signin. User cancellation must not trigger another sign-in UI.
8. Email/password is the guaranteed account-authentication method on devices without Google services. Do not implement a custom OAuth backend exchange in v1. Do not promise browser Google sign-in on non-GMS devices. A future browser Google flow requires a separately approved device-matrix spike. Firebase-managed generic OAuth flows are documented for configured OIDC providers; they are not assumed to be a drop-in Google provider fallback: https://firebase.google.com/docs/auth/android/openid-connect.
9. Support email/password sign-up, sign-in, verification email, password reset and reauthentication. An unverified email/password account may sign in but cannot create backups, accept/create pairs, update protected cloud profile fields, or use other protected cloud mutations until the server verifies `emailVerified == true`. Do not reveal whether an email account exists before the user proves ownership of the credential.
10. Require a persistent, non-anonymous account before creating a cloud backup or key bundle. Guest users cannot upload backup objects because their cloud state has no account recovery path.
11. Back up only an explicit allowlist of logical state: habits, habit logs/execution records, streak source records, milestones, journal entries, recovery records, and portable preferences approved by the existing domain model. Never automatically add newly created database tables to the backup serializer.
12. Exclude transient/device-bound state: VPN/DNS runtime configuration, local app-block configuration, app-usage statistics, widget instances, local SQLCipher keys, Android Keystore aliases, FCM tokens, push routing records, outbox/network queues, per-device pairing secrets, E2EE private keys, and device-trust state.
13. Encrypt all backup content client-side before upload. Store immutable encrypted payload objects in Cloud Storage and small metadata manifests in Firestore. Do not place a full snapshot in a Firestore document; Firestore documents have a 1 MiB limit: https://firebase.google.com/docs/firestore/quotas.
14. V1 backup creation is user-initiated. The user enters the backup passphrase to unlock the account backup key; label it distinctly from the Firebase sign-in password. Firebase password reset/change never changes the backup passphrase or key bundle. Do not persist the passphrase or include it in WorkManager input. An already encrypted object upload may continue/resume in the background.
15. Enforce maximum uncompressed serialized size of 100 MiB, maximum compressed plaintext size of 20 MiB, and maximum uploaded binary object size of 21 MiB. The payload format is binary; do not base64-encode the full ciphertext into JSON. Reject over-limit snapshots before upload and explain the limit.
16. Use a stable random 256-bit Account Backup Key (ABK), wrapped separately by the passphrase-derived KEK and by a recovery-key-derived KEK. Each snapshot gets a fresh random 256-bit DEK; encrypt the snapshot with the DEK and wrap the DEK with the ABK. Store the current wrapped-ABK key bundle separately from immutable snapshots. Passphrase changes and recovery-key rotations update only the key bundle, not all snapshots.
17. Require user-chosen backup passphrases to be at least 16 characters and to score 4/4 with a maintained zxcvbn-style password-strength estimator. Reject common passwords and obvious repetitive/predictable patterns. Offer a generated six-word backup passphrase as the preferred choice; select each word uniformly with `SecureRandom` from a reviewed list of at least 7,776 unique words using unbiased index selection. The generated phrase is exempt from the estimator because its security comes from uniform generation. The separate 24-word recovery mnemonic recovers the ABK. Make clear that local attempt throttling does not prevent offline guessing against an acquired encrypted backup.
18. Generate a random 256-bit recovery key during key-bundle setup and encode it as a checksummed 24-word mnemonic representing the raw random entropy. Display it once on a secure screen, require acknowledgement that it was saved, and do not store it in plaintext on the device or server. The 24-word mnemonic is only an encoding of the random recovery key; do not apply a mnemonic seed KDF to it. Support resetting the backup passphrase using either the current backup passphrase or the recovery key, and support recovery-key rotation by unlocking the ABK with the current backup passphrase and replacing the recovery wrapper in the key bundle. Label the backup passphrase as separate from the Firebase sign-in password; changing/resetting the Firebase password must never change the backup key bundle, and changing the backup passphrase must never change Firebase credentials. Never represent this recovery key as an E2EE private-key recovery mechanism.
19. Restore through a staging database. Verify downloaded object generation, size and SHA-256; unwrap the snapshot DEK; authenticate/decrypt; decompress with limits; validate the schema and relationships; then activate the staged database atomically. A failed restore must leave the active database unchanged.
20. Store these local values in each UID-scoped SQLCipher database: `lastMaterializedRemoteBackupRevision` and `lastMaterializedRemoteBackupSha256`, which identify the exact cloud snapshot represented by current local state; and `highestObservedRemoteBackupRevision` and `highestObservedRemoteBackupSha256`, which form the installation's monotonic high-water anchor. Update the materialized pair only after successful restore or backup commit. Never decrease the high-water revision; for the same revision, reject a changed manifest/object hash as `REMOTE_BACKUP_EQUIVOCATION`. Every backup commit must use the materialized base, not a pointer fetched immediately before upload. If a remote current revision exists and the local base is missing/stale, return `BACKUP_BASE_REQUIRED` or `REMOTE_BACKUP_AHEAD`. If the server pointer moves behind the local high-water revision, or presents a different hash for the same high-water revision, return `REMOTE_BACKUP_ROLLBACK` or `REMOTE_BACKUP_EQUIVOCATION` and fail closed; ordinary replacement confirmation cannot bypass these errors. Otherwise require the user to restore the current snapshot or explicitly confirm `Replace cloud backup with this device's current local data`; replacement is bound to the exact current revision and hash (or an explicitly confirmed absent pointer) and must fail if either changes before commit. Preserve old snapshots under normal retention. On a new device, do not claim protection against a malicious server replaying an older but valid snapshot because no trusted local revision anchor exists. V1 uses revision compare-and-set, not a cryptographic manifest hash chain.
21. Pair invitations, acceptance, pair lifecycle changes and participant changes must use validated callable backend functions. The other participant must explicitly accept. Participant UIDs become immutable after activation.
22. All new user event-chain appends go through the authenticated `appendUserEvent` callable. Firestore rules deny direct client create/update/delete in `event_chain`; the callable validates the active pair and sender, preserves the existing event wire format, applies transactional per-UID/per-pair event and byte token buckets, and writes an immutable event through Admin SDK. Retries use the same event ID and are idempotent. The callable validates outer metadata and size; Firestore rules enforce participant reads and deny client writes. Neither can inspect encrypted semantic types. Backend-only lifecycle events live in a separate `system_events` collection and are not presented as E2EE peer-authenticated content.
23. Defer trusted-device approval and E2EE device recovery from v1. Every new device must explicitly re-pair with partners. Do not create a `TRUSTED` device registry, device-key recovery path, or new signature flow in this release.
24. Persistent-account deletion requires fresh reauthentication, is accepted durably by the backend, disables/revokes the account session at request time, and runs as an idempotent background job. Anonymous guest-data deletion uses a separate explicit confirmation endpoint for the active anonymous session.
25. Delete private profile data, device records, backup objects, backup manifests/key bundles, token mappings and user-owned private subcollections. Terminate every pair containing the UID regardless of status. Retain only the minimal pair tombstone and previously shared ciphertext required by the remaining participant; scrub the deleted user's display-name fields. Do not claim that ciphertext or plaintext already stored on another user's device has been erased.
26. Keep Firebase Identity Platform anonymous-user automatic cleanup disabled for this release. It can delete anonymous Auth records after 30 days without following the application's custom pair/data cleanup path. Guest cleanup must go through the explicit transition/deletion worker. Reconsider auto-cleanup only after a dedicated reconciliation mechanism is implemented and tested: https://firebase.google.com/docs/auth/android/anonymous-auth.
27. On GMS devices, FCM push is supported where the Google messaging dependencies work. On non-GMS devices, core functionality and in-app event refresh work without FCM; notifications are not guaranteed. Do not add an alternative push transport in this feature.
28. App Check is non-blocking in v1. Where Play Integrity App Check is used, select only provider settings explicitly exposed and documented by Firebase for outside-Google-Play distribution; do not assume an undocumented per-verdict policy or require Basic Integrity as a universal sideloaded baseline. Do not use the Preview reCAPTCHA provider as a guaranteed non-GMS fallback. Keep enforcement disabled, collect telemetry where supported, and use Auth, rules, callable authorization and rate limits as the actual controls. Current guidance: https://firebase.google.com/docs/app-check/android/play-integrity-provider. **[Amended 2026-10-31 by ADR #248: App Check enforcement is ON (owner ruling 2026-09-19) — Firestore project-wide and every callable `enforceAppCheck: true`. It is defense in depth, not the authorization boundary; non-GMS devices fail closed for cloud features only, and local core is unaffected.]**
29. Add server-side feature gates for sensitive callable operations. Client feature flags only hide UI and are not security controls. Deletion processing, revocation, cleanup and reconciliation workers must not be disabled by a general feature flag.
30. Keep the current APK signing identity and direct-distribution update process. Publish the minimum-client-version value through a server-readable configuration path. New clients must show an update-required message for unsupported versions. Old clients that cannot read the gate must fail closed when tightened rules deny unsafe writes; never retain a vulnerable rule solely for old-client compatibility.
31. Persistent-account sign-out closes and locks the active UID-scoped database, clears in-memory and Firebase SDK state, removes that installation’s push-token mapping and pauses account-scoped workers, but retains the encrypted UID-scoped database and Keystore-protected database key by default to avoid losing unbacked-up local history. Return to the app’s signed-out/guest entry flow according to the existing app behavior; never bind a new anonymous UID to the retained persistent-account database. The signed-out UI must not expose retained data. Provide a separate `Remove this account's local data from this device` action with explicit confirmation; it must warn about pending outbox events and require explicit discard before destroying them. A different UID must never open the retained database. Account deletion, unlike sign-out, removes the local account database and associated aliases after cleanup rules are satisfied.

### 2.2 Non-functional requirements

- Keep the repository's current `minSdk` if it is above API 26; otherwise keep API 26. Do not lower the minimum.
- Preserve existing guest behavior and domain logic unless a change is required by this feature or the Release 0 security fix.
- Make each cross-service operation recoverable. Do not assume Firestore, Cloud Storage and Firebase Authentication share one transaction.
- On first launch of the new APK, drain or explicitly discard legacy Firestore pending writes, terminate the legacy instance and clear its persistent cache before opening cloud data. Then configure the replacement instance for memory-only cache. SQLCipher is the durable offline source for domain state and the event outbox. On later account/session transitions, remove listeners, resolve pending writes while the old UID is active, terminate the old instance and create a fresh memory-cache instance. Do not render account-scoped or partner-scoped snapshots marked `isFromCache == true` after a transition; gate every result by UID plus session generation. Cache cleanup is not secure physical erasure.
- No secrets, passphrases, recovery keys, OAuth tokens/codes, database keys, backup plaintext or decrypted journal content in logs, analytics, crash breadcrumbs or support diagnostics.
- Call the feature client-side encrypted backup. Do not promise absolute zero-knowledge, complete deletion of copies held by partners, or physical erasure of flash storage.
- Every destructive flow must explain what will happen and must have a verified retry/failure path.

## 3. Existing System Context

The existing repository is not included in this document. Before changing code, inspect and map the actual implementation of:

- Firebase Auth anonymous identity and all sign-in/session listeners.
- SQLCipher database open/key lifecycle, migrations, entity IDs, foreign keys and derived aggregates.
- WorkManager outbox jobs and the exact point where an event becomes server-accepted/acknowledged.
- Firestore initialization, persistence settings, active snapshot listeners, pending writes, existing rules, indexes and pair/event data formats.
- Firebase Storage initialization, upload tasks, stored resumable session URIs and current bucket/rules.
- Existing pairing invitation and E2EE handshake, event ordering, signature checks and key ownership.
- FCM token registration, token-to-UID mapping and lock-screen notification payloads.
- Cloud Functions generation/runtime, deployment region, task queues, scheduled jobs and service-account permissions.
- Current Android manifest, Auto Backup/data-extraction rules, supported ABIs, package IDs, Firebase config and release-signing certificate fingerprints.
- The actual direct-APK release/update path and currently deployed minimum supported version.

Do not create parallel auth, database, pairing or crypto subsystems if equivalent components already exist. Extend the existing boundaries. Record current production collection paths and released-client write behavior before deploying rules changes.

### Required legacy database migration

The existing local SQLCipher database must survive the first upgrade. Add an idempotent migration from the legacy database path to the new UID-scoped path:

1. Detect the legacy database and the Firebase UID to which its current session belongs.
2. Create a migration journal with a stable operation ID and target UID.
3. Close all handles, copy/rekey or migrate using the repository's supported SQLCipher mechanism, and include WAL/SHM/journal state correctly.
4. Validate schema version, row counts, foreign-key relationships, stable entity IDs and representative logical checksums before switching the active-database pointer.
5. Keep the source copy until the migrated database has opened successfully and passed integrity checks; retain a bounded rollback copy for seven days, excluded from device backup and inaccessible to other UIDs.
6. On crash/relaunch, resume or safely roll back based on the migration journal. Never delete the only working copy on a failed migration.
7. Keep outbox and pairing state in the same UID's migrated database. A separate guest-to-existing-account import uses the logical-data allowlist and must not move outbox/pairing state.

### Existing Firestore offline state

Android Firestore persistence is enabled by default in older/default configurations and can retain cached documents and pending writes across app restarts and account changes. The new client uses memory-only cache; SQLCipher is the durable offline source for domain state and the event outbox. References: https://firebase.google.com/docs/firestore/manage-data/enable-offline and https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/MemoryCacheSettings.

**First-launch migration from the old APK:** run `LegacyFirestoreCacheMigration` before repositories, listeners or screens can access Firestore. While the old Firebase UID is still current, call `waitForPendingWrites()` on the legacy instance with a bounded timeout if connectivity is available. If pending writes cannot be acknowledged, block cloud access and identity switching until connectivity returns or the user explicitly confirms discarding the queued Firestore mutations. Remove listeners, terminate the legacy instance, call `clearPersistence()` only after termination, and await completion. If cache clearing fails, keep cloud access disabled and show a recoverable migration error. Then obtain a fresh instance and configure `FirebaseFirestoreSettings` with `setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())` before the first read/write/listener/query. Pin or upgrade to a stable Firebase Android SDK that supports this API. Do not substitute deprecated persistence switches or silently fall back to disk persistence. `clearPersistence()` efficiently drops the SDK cache and pending mutations; it is not a secure-erasure function.

**Each later identity switch:** freeze new SDK writes, remove every snapshot listener, call `waitForPendingWrites()` while the old Firebase UID is still current, and block switching if it cannot complete unless the user explicitly confirms discarding pending SDK writes. Then terminate the old Firestore instance and obtain a new instance configured for memory-only cache before using the new UID. Do not call `clearPersistence()` on routine switches: it is for the one-time legacy persistent-cache migration, not secure erasure. Never render cached/from-cache account or partner data after a UID transition until a server-backed result has been received for the active UID/session generation. Durable E2EE sends must live in the SQLCipher outbox, not only in Firestore's SDK queue. The SDK documents that `terminate()` alone does not cancel pending writes and that `waitForPendingWrites()` tasks are cancelled during user changes. Source: https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/FirebaseFirestore.

Also cancel old-UID Cloud Storage upload tasks and delete their locally stored resumable-upload session URIs after recording their reservation IDs for orphan reconciliation. Store session URIs only in encrypted app-private state because they can authorize continuation of an upload. A canceled/incomplete object remains eligible for cleanup only after the reservation/manifest reference checks and grace period. Firebase documents Android resumable-upload handling and session expiry at https://firebase.google.com/docs/storage/android/upload-files.

## 4. Architecture

### 4.1 Android client components

**AuthCoordinator**
- Owns Firebase Auth state transitions and current UID/session generation.
- Exposes immutable states: `GuestActive`, `PersistentActive`, `Authenticating`, `TransitionPrepared`, `TransitionFinalizing`, `ImportPending`, `ReauthRequired`, and `AuthError`.
- Does not mutate database files directly from an Auth listener. It coordinates `LocalSessionManager` and migration/import state machines.

**GoogleCredentialProvider**
- Uses Credential Manager with the registered Web Application Client ID where supported.
- Handles cancellation separately from provider absence and authentication failure.
- Does not open browser OAuth after user cancellation.
- Does not include a custom OAuth backend exchange in v1. Non-GMS devices use email/password. Browser Google OAuth is deferred.

**EmailPasswordAuthController**
- Handles sign-up, sign-in, verification, password reset, reauthentication and generic error messages.
- Refreshes the Firebase ID token after email verification and reauthentication before invoking protected operations.

**GuestTransitionCoordinator**
- Generates a transition ID and 256-bit capability, stores them in the encrypted local transition journal before any network call, prepares the server intent while the guest token is active, signs into the target account, finalizes server cleanup under the target account, and then imports or discards quarantined logical data according to the user's preselected intent.
- If target sign-in fails, it cancels the prepared transition and leaves pair state unchanged.
- If the app stops after target sign-in but before finalization, it resumes using the persisted transition capability; it never attempts to recreate the old anonymous UID.

**LocalSessionManager**
- Owns UID-scoped SQLCipher database paths/keys, legacy migration, quarantine, listener cleanup, Firestore persistence teardown, Storage-task cancellation, WorkManager account scoping, FCM token registration, in-memory cache clearing, and ordinary sign-out versus explicit local-data removal.
- Uses a monotonically increasing local `sessionGeneration` and `TRANSITIONING` state.

**BackupKeyManager / CryptoManager**
- Creates and unlocks the ABK key bundle, implements Argon2id and AES-256-GCM key wrapping, creates/validates binary snapshot envelopes, and handles passphrase change/recovery-key rotation.
- Holds unwrapped keys in memory only for the operation and clears available mutable byte buffers best-effort. Never claims complete JVM memory zeroization.

**BackupManager**
- Creates consistent SQLCipher snapshots from the allowlist, compresses, encrypts, reserves a Cloud Storage object, uploads it, asks the backend to commit a manifest and handles revision conflicts.
- Restores into staging and activates only after validation.

**PairService / EventOutbox**
- Uses callable backend functions for invitations and pair lifecycle changes.
- Sends user E2EE events from the existing SQLCipher outbox through `appendUserEvent`, preserving the existing E2EE payload/wire format. Retries reuse the same event ID. It does not invent a new E2EE device-trust protocol.

**DeletionClient**
- Performs provider reauthentication, submits a durable deletion request, shows pending/completed status and signs out locally only after the backend confirms durable acceptance.

### 4.2 Backend components

Implement these callable functions and workers. Use the existing Cloud Functions runtime unless the repository inventory shows it cannot meet a requirement; document any runtime change in the PR.

- `getClientCompatibility`: returns minimum supported client version and feature status. It is a UX compatibility signal, not an authorization control.
- `initializeUserProfile`: creates a minimal profile only if the Firebase Auth user exists, is enabled, and no deletion job/tombstone exists for the UID. Never repair/recreate a profile when deletion has started or completed.
- `prepareGuestTransition`: called while authenticated as the anonymous UID; accepts a client-generated transition ID and 256-bit capability, stores only the capability hash and creates one non-destructive transition record per guest UID. Limit to five preparation attempts per UID per rolling 24 hours and one unfinalized transition per guest UID. The normal preparation lease is 24 hours. Expiry changes the state to `EXPIRED_UNFINALIZED` but does not delete the capability hash or mutate guest data. Repeating the same ID/capability is idempotent. A cancelled transition is final and cannot be resumed.
- `finalizeGuestTransition`: called after successful sign-in to a persistent target UID; validates the one-time capability, target session and explicit import/discard intent, then idempotently terminates all guest pairs, writes system events, and enqueues guest cleanup. This completion path remains available when a new-transition feature gate is disabled. It accepts `PREPARED` within the 24-hour lease or `EXPIRED_UNFINALIZED` only after a fresh target-account reauthentication (`auth_time` no older than five minutes). It does not import local data; the client handles local quarantine import after finalization.
- `cancelGuestTransition`: guest-only, permanently cancels an unfinalized transition. A user who remains in the guest session after failed/cancelled target sign-in can cancel even an `EXPIRED_UNFINALIZED` record. Expiry never mutates pairs.
- `createPairInvitation`, `acceptPairInvitation`, `terminatePair`: validate participants, invitation token, account status, duplicate relationships and allowed state transitions. Enforce 10 invitation creations per UID per hour and at most 20 unexpired invitations per UID. Tokens contain at least 128 bits of cryptographic entropy, are stored only as SHA-256 hashes, expire within 24 hours, and are single-use. A token identifies the invitation; only the authenticated accepting UID becomes the recipient.
- `appendUserEvent`: the only client-supported path for adding a user E2EE event. It authenticates the sender, requires the sender profile to be `ACTIVE`, requires an active pair membership, rejects any UID with a deletion job, and constructs `senderUid` from the verified Firebase UID rather than trusting a client field. An anonymous UID may send only on an already-active legacy pair; a persistent email/password UID must have `emailVerified == true`. Validate the outer schema and ciphertext field cap. It writes via Admin SDK only after transactionally checking/updating per-UID and per-pair token buckets. Initial limits: per UID, capacity 30 events and 1 MiB of ciphertext, refilling at 2 events/second and 10 MiB/minute; per pair, capacity 60 events and 2 MiB of ciphertext, refilling at 4 events/second and 20 MiB/minute. Reject requests that exceed either bucket with a retry delay. Each ciphertext field is limited to 512 KiB encoded. If the event document ID already exists and every immutable request field matches, return the prior success without consuming rate-limit tokens; if any field differs, reject with `EVENT_ID_CONFLICT`. Rate limits are server-owned and cannot be reset by clients.
- `updateUserProfile`: validates profile fields and monotonically increments `profileVersion`. Propagation logic rereads the current profile and uses version checks, so out-of-order trigger delivery cannot restore old names.
- `initializeBackupKeyBundle`, `updateBackupKeyBundle`: create or compare-and-set the current wrapped-ABK document. Callers must be persistent, active accounts; email/password accounts must be verified.
- `beginBackup`: rate-limits the UID to five reservation attempts and 105 MiB of reserved uploads per rolling 24 hours and one active reservation, accepts the local database’s `lastMaterializedRemoteBackupRevision` plus its manifest hash, and compares them with the current server pointer before creating a reservation. If the local base revision and hash equal the current pointer/manifest revision and hash, create a normal reservation. If the base is missing while a pointer exists, the pointer is newer than the base, the same revision has a different hash, or the pointer is behind the base/high-water state, reject with `BACKUP_BASE_REQUIRED`, `REMOTE_BACKUP_AHEAD`, `REMOTE_BACKUP_EQUIVOCATION`, or `REMOTE_BACKUP_ROLLBACK`. Create a replacement reservation only after explicit user confirmation and record the exact current revision (including explicit absence) authorized for replacement.
- `extendBackupReservation`: extends an active upload reservation only for its authenticated owner, at most once per 10-minute heartbeat window and never beyond seven days from reservation creation.
- `commitBackupManifest`: verifies the reserved object and authenticated user, streams the exact uploaded object generation to compute SHA-256, checks object size and the exact current revision/hash against the reservation’s original local base or explicitly approved replacement revision/hash, then transactionally commits an immutable manifest and advances the current pointer. If there was no prior pointer, assert absence before creating revision 1. Never substitute a fresh server revision for the local database base.
- `getBackupManifest` or owner-only Firestore reads: only return manifests for the current authenticated owner, with account status checked.
- `requestAccountDeletion`: extracts the bearer Firebase ID token from the callable's raw HTTP Authorization header, verifies it with Admin SDK revocation checking enabled, and requires `uid` to match callable auth plus `auth_time` no older than five minutes. It also requires explicit confirmation, checks `email_verified` for email/password accounts and the server-side Auth record. In one durable transaction it creates/returns the deletion job and marks the profile pending, then disables the Auth user, revokes refresh tokens and enqueues cleanup. It returns accepted only after the job state is durable; if disable/revoke/enqueue fails, reconciliation retries the missing phase. Persist the operation ID locally before the call. `getDeletionStatus` may return only the minimal existing job status when the callable UID matches the job UID, even if the Auth user has been disabled; it must not permit any data mutation or bypass account-deletion checks.
- `requestGuestDataDeletion`: explicit, double-confirmed cleanup for the current anonymous UID. It uses the same cleanup worker and does not permit backup operations.
- `processAccountDeletion`: Cloud Tasks-backed idempotent worker, paginates all related pairs/objects, removes private user state, deletes Auth last, and records completion.
- Deploy a dedicated 1st-generation `functions.auth.user().onDelete()` trigger as an idempotent backstop for out-of-band Auth deletion; it only creates/ensures the cleanup job and enqueues the shared worker. Do not use the 2nd-generation Auth deletion trigger in production because the current Firebase documentation marks it Preview and says not to use it in production: https://firebase.google.com/docs/functions/auth-events. Normal operator/admin account removal must invoke the same deletion workflow so the profile is marked `PENDING_DELETION` and the durable job exists before Auth deletion; direct Console/Admin deletion is prohibited in routine operations. For an emergency out-of-band deletion, run reconciliation immediately and accept that an asynchronous trigger cannot make the delete-plus-cleanup sequence atomic. Scheduled reconciliation remains the backstop, and batch Auth deletions must still be reconciled because they do not fire individual delete events. References: https://firebase.google.com/docs/functions/1st-gen/auth-events and https://firebase.google.com/docs/functions/auth-events.
- Scheduled workers reconcile stale transition/deletion/reservation jobs and delete orphan backup objects only after a grace period and after confirming no active manifest references them.

Callable functions are not automatically retried as a complete transaction; configure the task queue explicitly and make each worker step idempotent. See https://firebase.google.com/docs/functions/task-functions.

### 4.3 Firestore data model

Use server-managed lifecycle state. Names below are normative unless existing deployed collections need a compatibility adapter.

- `users/{uid}`: minimal profile, `status` (`ACTIVE` or `PENDING_DELETION`), `displayName`, `profileVersion`, schema version and timestamps. Owner can read only. Clients cannot directly create/update/delete it; use validated callables.
- `users/{uid}/backupControl/keyBundle`: one current mutable wrapped-ABK bundle with `bundleFormatVersion`, stable `keyId`, monotonic `revision`, Argon2id configuration/salt, passphrase wrapper, recovery wrapper and timestamps. Client reads own bundle; only callables create/update it using expected-revision compare-and-set. Do not retain old server-side key bundle versions in this collection.
- `users/{uid}/backupControl/current`: current `backupId` and monotonic manifest revision. Owner read only; backend updates it.
- `users/{uid}/backupManifests/{backupId}`: immutable committed manifest with owner UID, backup ID, monotonic revision, object path, exact uploaded object generation, byte size, SHA-256 of the exact complete encoded object bytes, CRC32C, compressed/uncompressed byte lengths, envelope format version, schema version, key ID and timestamp. Store the whole-object SHA-256 **only here**, not inside the object it hashes. V1 has no cryptographic manifest hash chain; the revision pointer uses backend compare-and-set. Client cannot write manifests directly.
- `backupReservations/{backupId}`: server-only reservation with UID, allowed object path, local base revision, explicit-replacement mode and exact authorized current revision when applicable, status, expiry, maximum expiry (seven days after creation), creation time, upload-heartbeat time and commit state. Storage rules consult this reservation before permitting object creation.
- `guestTransitions/{transitionId}`: server-only guest UID, state, expiry, hash of one-time capability, selected intent, target UID once finalized, and cleanup job reference. Clients cannot read/write this collection directly.
- `pairs/{pairId}`: existing participant UIDs, pair status and minimal partner-visible display-name fields. Participant UIDs cannot change after activation. All lifecycle writes are server-managed.
- `pairInvitations/{invitationId}`: server-only inviter UID, SHA-256 hash of a random 128-bit-or-stronger token, expiry (24 hours maximum), status and accepted recipient UID/time. Never store the raw token. Clients cannot read or write invitation documents; invitation creation, token validation and acceptance are callable-mediated. Reuse the current share/deep-link UX so the recipient presents the opaque one-time token; the inviter cannot nominate a recipient UID as a substitute for recipient consent.
- `pairs/{pairId}/event_chain/{eventId}`: user-originated E2EE event records using the existing wire format, append-only. The `appendUserEvent` callable creates documents with immutable event ID, server-derived sender UID, protocol/schema version, `recordKind = USER_ENCRYPTED`, bounded ciphertext and server creation time. The semantic event type stays encrypted. Clients have read access only and cannot write these documents directly.
- `pairs/{pairId}/system_events/{systemEventId}`: backend-originated lifecycle events only. Clients can read as authorized participants; clients cannot create/update/delete. These are server-authenticated metadata events, not E2EE peer-authenticated content.
- `deletionJobs/{uid}`: server-only durable job state, stable operation ID, phase, cursor, attempts, timestamps and sanitized error category. Client status comes through a callable. Keep only a minimal tombstone for 30 days after completion, then purge.
- `deviceTokens/{tokenHash}`: server-only mapping of FCM token, current UID, installation generation and timestamp. Clients cannot read/write directly. Registration is an authenticated upsert that transfers the token only after validating the new UID; unregister old mapping on account change.
- `eventRateLimits/{scopeKey}`: server-only token-bucket state for `user_<uid>` and `pair_<pairId>` scopes, including event tokens, byte tokens and last-refill time. Clients cannot read/write these documents. The callable updates user and pair buckets transactionally with each new event; an idempotent retry for an already-created identical event does not consume tokens again. Expired inactive bucket documents are removed by scheduled cleanup.
- `serverFeatureGates/{featureName}`: server-only operational gates for sensitive operations. Missing gate means disabled for newly gated mutations. Never gate deletion cleanup/reconciliation with a general feature switch.
- `clientCompatibility/current`: server-published minimum client version and update message. Public read is allowed; no client writes.

Every collection not explicitly granted in the rules must be denied by default. Rules for event-chain ciphertext validate only the outer metadata, field allowlist and bounded field lengths; they cannot identify semantic event types encrypted inside the payload. Apply this exact client access matrix: owner-only read/no direct write for `users/{uid}`, `backupControl/keyBundle`, `backupControl/current`, and committed manifests (only while the account is active); no direct client read or write for `backupReservations`, `guestTransitions`, `deletionJobs`, `deviceTokens`, `eventRateLimits`, `serverFeatureGates`, and `pairInvitations`; callable-mediated access for invitations, event append and deletion status; public read/no write only for `clientCompatibility/current`; participant read/no direct lifecycle writes for pairs; participant read/no client write for `event_chain` and `system_events`. Client writes to user profiles, key bundles, manifest pointers/manifests, reservations, transition records, deletion jobs, device token mappings, rate-limit buckets, feature gates, invitations, event chains or system events are always denied. Test every path and query with the Emulator Suite.

### 4.4 Cloud Storage model

- Object path: `users/{uid}/backups/{backupId}.bin`.
- Each `backupId` is generated by `beginBackup`, random and immutable.
- Maximum object size is 21 MiB, `contentType` is `application/octet-stream`, and only a create at a reserved path is permitted. Deny overwrite, metadata mutation and client delete. Admin cleanup bypasses client rules.
- Client read is permitted only to the owner while their account is active and persistent. Always request server data for account and partner reads after identity transitions; never render an SDK `isFromCache` snapshot for the new UID until a server-backed result confirms the UID/session generation. The backend streams the exact object generation to compute SHA-256 before manifest commit. Do not trust client-supplied SHA-256 custom metadata as proof of the object's contents.
- Keep the bucket private. Do not put public download tokens or signed URLs in Firestore manifests.
- `beginBackup` permits at most one active reservation per UID and five reservations per rolling 24-hour period. Reservations expire after 30 minutes of inactivity. The client calls `extendBackupReservation` during active uploads and before resuming a saved session; the server extends at most once per 10-minute heartbeat and never beyond seven days from reservation creation. The server retains the current plus two previous committed snapshots and cleans unreferenced objects after a 24-hour grace period, checking committed manifests, live reservations and active restore references immediately before generation-aware deletion. Do not delete an object with a live reservation even if it is older than the grace period. Storage rules do not count total objects; the reservation service enforces upload count/byte limits, retained-object limits and per-UID quota counters. Project-level budget alerts are mandatory.
- Storage rules use a server-owned reservation document and enforce owner UID, active account state, reservation expiry, reserved path, create-only behavior, content type and size. Configure the documented Firestore access integration/IAM for cross-service Storage rule lookups if used: https://firebase.google.com/docs/storage/security/rules-conditions.

## 5. Architecture Decisions

- **Release sequencing:** ship Release 0 pair/profile security hardening before accounts because existing authorization defects cannot remain open during the feature build.
- **Authentication:** keep Firebase Auth and anonymous linking because it preserves an unused-credential guest UID and avoids migrating the identity system.
- **Google sign-in:** use Credential Manager on verified supported devices; use email/password as the guaranteed non-GMS path and do not ship a bespoke OAuth backend exchange in v1, reducing custom auth-critical surface.
- **Credential collision:** authenticate to the existing account and import/discard local guest data; never delete or transfer the existing credential automatically.
- **Guest transition:** use prepare/commit semantics so pair termination occurs only after target authentication succeeds.
- **Local storage:** migrate the existing database without data loss, then scope active databases and work by UID/session generation.
- **Firestore cache:** disable disk persistence for account/pair documents in the new client because sensitive cache entries can cross user sessions; use SQLCipher for offline domain state and outbox, suppress cached snapshots across UID transitions, and recreate a fresh Firestore instance after terminating/clearing the old one.
- **Backup product:** ship encrypted snapshot backup/restore, not live multi-device sync, because a monolithic encrypted snapshot cannot safely merge concurrent edits.
- **Backup key hierarchy:** use one random ABK wrapped by passphrase and recovery key, and a per-snapshot DEK wrapped by ABK, allowing passphrase/recovery rotation without rewriting snapshot payloads.
- **Backup object integrity:** store SHA-256 in the manifest and have the backend hash the exact object generation; this avoids a self-referential envelope hash and untrusted client hash metadata.
- **Crypto format:** use a fixed binary versioned envelope, AES-256-GCM with fresh 96-bit nonces, Argon2id `m=32768 KiB, t=3, p=1`, and a pinned maintained Argon2 implementation; do not silently weaken parameters.
- **Guest import:** merge only allowlisted logical entities additively with per-entity natural keys and an import journal; keep target singleton preferences unless the user explicitly chooses portable guest preferences.
- **Device recovery:** defer device-trust approval; require explicit re-pairing on a new device when the existing protocol cannot recover E2EE identity safely.
- **Pair/event writes:** use callables for invitations, pair lifecycle changes and every user E2EE event append. A transactional rate-limited callable is required because Firestore rules cannot reliably consume a per-user quota for direct client writes; the existing outbox retries by the same event ID.
- **Account deletion:** use a Cloud Tasks-backed idempotent worker, with session disable/revocation at request acceptance and a final sweep after Auth deletion.
- **Push behavior:** FCM is best-effort on GMS devices; non-GMS users use in-app refresh and do not receive a false promise of push support.
- **Attestation:** App Check telemetry only in v1; auth, Firestore rules, callable validation and rate limits remain the security boundary. **[Amended 2026-10-31 by ADR #248: enforcement is ON; the "telemetry only" half is superseded. The second half stands — App Check is still not the security boundary.]**
- **Release compatibility:** enforce safe server rules even if old clients lose unsafe mutations; distribute a signed hotfix and show update-required messaging in clients that implement the compatibility check.

## 6. Data Flow

### 6.1 Guest upgrade with an unused credential

1. Confirm current user is anonymous and the local guest database migration is complete.
2. Acquire the Google or email/password credential.
3. Call `linkWithCredential` on the current anonymous Firebase user.
4. If linking succeeds, verify that the returned UID equals the original guest UID.
5. Initialize/repair the user profile through the callable only if no deletion job exists and the Auth account is active.
6. Keep the existing UID-scoped database and outbox. Offer backup setup separately; do not force it as part of linking.
7. Refresh ID token and verify email status as applicable before protected cloud operations.

### 6.2 Credential collision and switching to an existing account

1. On a collision, show neutral text such as `This sign-in method cannot be added to the current guest session. Continue with the account you intend to use, or stay in guest mode.` Do not confirm that an email/account exists, display an account-specific identifier, or use `fetchSignInMethodsForEmail` to infer ownership before successful authentication.
2. Before switching, explain that guest pairs will be terminated after target authentication succeeds and must be re-created with partner consent; guest E2EE history and outbox are not imported. Ask whether the user wants to keep guest logical data for import or discard it after the transition. This choice must not reveal whether the target account exists.
3. Always create an encrypted local quarantine and transition journal before signing in to the target, even when discard is selected; keep it until transition finalization and any explicit import decision complete. Obtain a separate explicit confirmation before discarding quarantined data. Do not delete the source database yet.
4. Generate a random transition ID and a 256-bit one-time capability locally. Persist both in the encrypted transition journal before any network call. While the guest session is still active, call `prepareGuestTransition` with the transition ID and capability; the server stores only the capability hash. The prepare call is idempotent for the same transition ID/capability and does not mutate pairs, user data or Auth state. Allow only one unfinalized transition per guest UID.
5. Before changing Firebase Auth's current user, execute the identity-switch preflight in §8.7 while the anonymous UID is still active: stop writes, await pending Firestore SDK writes, remove listeners, and terminate the old Firestore instance. The one-time legacy persistent-cache purge is performed at new-version startup, not as a repeated switch operation. If this preflight cannot complete, do not switch identity; remain guest and retry or obtain explicit consent to discard pending SDK writes. Then attempt sign-in to the target account. If the user cancels or authentication fails, cancel the prepared transition when possible, clear the local transition capability only after server cancellation/expiry is recorded, keep the guest session and database active, and leave pair state untouched.
6. Once target sign-in succeeds, record the target UID in the encrypted transition journal and confirm the already persisted import/discard intent. Call `finalizeGuestTransition` under the target account only with that explicit intent. The function verifies the target account is persistent and active, consumes the capability once, binds the transition to that target UID, terminates guest pairs, writes deterministic backend system events, and enqueues cleanup for the old guest UID. If the user abandons the screen before finalization, leave `TRANSITION_FINALIZATION_PENDING`; do not mutate pairs or delete quarantine until the user resumes and confirms the same intent. If the process dies between successful sign-in and finalization, the capability is already persisted and the target session can resume finalization. If the 24-hour preparation lease elapsed, require target-account reauthentication before finalizing the retained `EXPIRED_UNFINALIZED` record.
7. If finalization temporarily fails, keep the target account authenticated, keep the quarantine and encrypted transition capability, show `Finish account switch`, and retry on next launch. Do not claim the transition is complete until the server accepts it. Do not try to restore the old anonymous session; anonymous users cannot be re-authenticated without linking a credential. After finalization succeeds, show that a seven-day undo replays the local logical-data import only; it cannot restore the guest UID, guest pairs or anonymous Firebase session. If the user chose discard, delete quarantine only after finalization and guest cleanup acceptance. If an accepted transition is finalized but import is later cancelled, preserve the quarantined snapshot until the seven-day deadline or explicit `Delete quarantine now`; do not offer restoration of the former guest identity/pairs.
8. For import intent, fetch the target account current backup pointer before modifying the target database. Store the materialized base revision/hash and highest-observed high-water revision/hash separately. If there is no target local database but a cloud snapshot exists, restore that snapshot into staging before guest import. If the current remote revision and manifest hash equal the local materialized base revision/hash, import guest data directly into the current target database. If the current pointer is below the local high-water revision or its same-revision hash differs from the high-water hash, stop import and return `REMOTE_BACKUP_ROLLBACK`/`REMOTE_BACKUP_EQUIVOCATION`; do not offer ordinary merge or replacement until server-side integrity is investigated. If local target state exists but its base revision/hash differs from a newer, non-rollback current remote pointer/manifest, preserve a consistent encrypted target-local snapshot and show the user a conflict choice: (a) restore the latest cloud snapshot, then merge the preserved target-local snapshot and guest snapshot additively; (b) keep current target-local state and merge guest data locally while leaving the cloud snapshot unchanged; or (c) defer/cancel the import. For option (a), run a dry-run for both imports and use the existing natural-key rules before atomic activation. Deduplicate same-habit/same-day logs and any uniqueness-constrained domain record; recalculate derived streaks/statistics from canonical records instead of merging cached aggregates. For option (b), mark `CLOUD_BASE_CONFLICT` and block new backup uploads until the user separately confirms exact-revision cloud replacement. The seven-day undo replays the quarantined logical-data import only and never restores old guest pairs or UID. If the user cannot unlock a needed cloud snapshot, set `IMPORT_PENDING`, retain all quarantines, and do not import into an empty/stale target database. Retain quarantine for seven days after successful import. For discard intent, delete the guest quarantine after transition finalization and cleanup acceptance.
9. The server cleanup worker removes private records for the guest UID only after finalization. An unfinalized transition lease can expire, but the server retains its hash-only recovery record until the guest cancels or target finalization succeeds; it never terminates pairs or deletes the guest account just because a lease elapsed. Alert on stale unfinalized records instead of automatically destroying guest state.

### 6.3 Guest logical-data import

1. Read the quarantined guest snapshot and current target database without mutating either.
2. Validate each record against current domain schema and foreign-key relations.
3. Use natural-key rules derived from the actual schema: deterministic IDs are deduplicated when the same logical record is present; conflicting same-ID records are assigned new IDs only when all references can be remapped safely.
4. Deduplicate same-habit/same-day logs and other unique domain records using actual unique constraints. Recalculate derived values such as streaks from canonical records when the existing code supports it; do not blindly import cached aggregates.
5. Keep target account singleton preferences by default. Let the user opt into importing the explicitly allowlisted portable preferences.
6. If any required entity cannot be migrated without violating schema or meaning, abort before activation, show the import summary/error and keep quarantine intact. Do not silently drop unsupported entities.
7. Run the import in a SQLCipher transaction or staging database with a stable import operation ID. A retry must not duplicate imported records.
8. The seven-day undo replays the logical import from quarantine after a new dry run. It does not recreate guest pairs, restore the old anonymous UID, or reverse the partner's termination event.

### 6.4 Backup key setup and snapshot creation

1. Require a persistent, active Firebase account; require `emailVerified == true` for email/password accounts.
2. On first backup setup, generate a random 32-byte ABK and random 32-byte recovery key. Encode the recovery key as a BIP-39-compatible 24-word mnemonic representing the raw random entropy and checksum only; do not apply mnemonic seed derivation. Show it once with `FLAG_SECURE`, prevent screenshots where Android permits, mark any explicitly copied clipboard value as sensitive, and require user acknowledgement. Do not save the recovery key locally or in the cloud.
3. Derive a 32-byte KEK from the custom backup passphrase using Argon2id (`m=32768 KiB`, `t=3`, `p=1`) and a fresh 16-byte random salt. Derive the recovery wrapping key using HKDF-SHA-256 with a random 16-byte salt and a fixed domain-separation label. Use separate 12-byte random AES-GCM nonces for passphrase and recovery wrappers.
4. Encrypt the ABK independently with the passphrase KEK and recovery KEK. Bind each AES-GCM wrapper with AAD containing the account UID, stable key ID, key-bundle format version, wrapper kind, and the relevant KDF/HKDF parameters and salt. Send only the wrapped key bundle to `initializeBackupKeyBundle`; use compare-and-set for all later updates.
5. To create a snapshot, prompt for the backup passphrase, derive the KEK and decrypt the current ABK wrapper. A recovery key may unlock the ABK if the passphrase was lost. Hold unwrapped keys only for the operation; never persist the passphrase or ABK in cleartext. **Amended 2026-11-14 by owner ruling (ADR #310):** one deliberate exception to "never persist the ABK" — the ABK is persisted on a phone only in a form wrapped by a non-exportable Android Keystore KEK, adopted on create and on every successful secret unlock, so the automatic daily pass can run without a user secret. The passphrase/recovery key remain the only portable unlock; the wrapped form is never uploaded or transferred.
6. Freeze a consistent SQLCipher read snapshot; serialize only the explicit allowlist. Do not include attachments/media or unbounded transient data in v1.
7. Serialize and compress through bounded streams into an app-private staging file; enforce the uncompressed and compressed size limits while streaming. Do not hold the entire serialized database, compressed payload and ciphertext simultaneously in heap memory. Exclude all staging paths from Android Auto Backup and device-to-device transfer.
8. Generate a random 32-byte snapshot DEK, encrypt the compressed staging file through bounded AES-256-GCM streaming updates with a fresh 12-byte nonce, and wrap the DEK with the ABK using a different fresh 12-byte nonce. Use distinct AAD contexts. Payload AAD binds UID, backup ID, schema version and envelope version. DEK-wrap AAD binds UID, backup ID, key ID and envelope version. On restore, stream GCM output only to a private, non-readable-by-application-code temporary compressed file until `doFinal()` validates the authentication tag; do not parse/decompress/read that file until authentication succeeds. On tag failure or process restart before the authenticated marker is written, delete the temporary file during cleanup. Do not claim physical erasure.
9. Encode the object using this exact binary envelope v1 format (network byte order, big-endian integers):
   - 8-byte ASCII magic `MYEXBK01`;
   - 2-byte unsigned envelope version (`1`);
   - 4-byte unsigned schema version;
   - 16-byte binary backup UUID;
   - 16-byte binary stable ABK key ID;
   - 12-byte payload GCM nonce;
   - 12-byte DEK-wrap GCM nonce;
   - 48-byte wrapped DEK (32-byte key plus 16-byte GCM tag);
   - 8-byte unsigned payload-ciphertext length, including its final 16-byte GCM tag;
   - exactly that many payload ciphertext bytes.
   Reject truncated, oversized, unknown-version and trailing-byte inputs. Do not store a whole-object hash inside the object.
10. Compute SHA-256 over the exact bytes of the complete encoded object. Keep this value client-side for comparison and submit it to `commitBackupManifest`; it is stored in the Firestore manifest only.
11. Read `lastMaterializedRemoteBackupRevision` and `lastMaterializedRemoteBackupSha256` from the local database and call `beginBackup` with both base values. Represent no prior snapshot as `(null, null)`; treat it as a normal initial backup only if the server pointer is also absent. If the server rejects the base, stop before upload and require restoration of the current remote snapshot or a separate explicit `Replace cloud backup with this device's current local data` confirmation. For replacement, bind the reservation to the exact current revision shown to the user. Then upload the exact immutable object to the reserved path. Persist the resumable upload session URI only in encrypted app-private storage bound to UID, session generation and backup ID. Firebase supports session continuation across process restarts; sessions expire after one week and must be re-created if expired: https://firebase.google.com/docs/storage/android/upload-files.
12. Call `commitBackupManifest`. The backend reads the reserved object generation as a stream and computes SHA-256 over the actual object bytes; it does not trust the client hash or custom metadata. It verifies object path, UID, reservation, generation, size, CRC32C metadata, full object hash, account state and expected current revision.
13. In a Firestore transaction, verify the current pointer still equals the reservation’s original local base revision/hash or the exact revision/hash authorized for explicit replacement. For an absent pointer, verify it is still absent and create revision `1`; otherwise create revision `currentRevision + 1`. If the pointer/revision/hash changed during upload, return `BACKUP_CONFLICT` and require a new operation. Advance the pointer and update `lastMaterializedRemoteBackupRevision` and `lastMaterializedRemoteBackupSha256` only after the server confirms commit. Advance the high-water revision/hash monotonically. No client writes the manifest or pointer directly.
14. After encryption, verify the encoded object length and SHA-256, then delete the serialized/plaintext and compressed staging files before upload begins. If deletion fails, mark the operation for immediate local cleanup and do not report backup preparation complete until the cleanup is retried; never claim flash-level secure erasure. On every app start, sweep stale plaintext/compressed staging files using the persisted operation journal, excluding any authenticated restore staging file still needed by an active restore. If commit succeeds, display backup success. If it fails, retain the previous pointer and local data; mark the object orphaned for cleanup after 24 hours, only after checking no manifest or active reservation references it.

### 6.5 Passphrase change and recovery-key rotation

1. Unlock the ABK with the current passphrase or the recovery key. A passphrase change requires a new passphrase; the old passphrase can be replaced using the recovery key if it was forgotten. Recovery-key rotation requires the current passphrase, so a lost recovery key can be replaced without changing the backup passphrase.
2. Change only `users/{uid}/backupControl/keyBundle` using expected-revision compare-and-set. Generate a fresh KDF salt and passphrase-wrapper nonce for a passphrase change, or a fresh recovery key/HKDF salt/recovery-wrapper nonce for recovery-key rotation. Keep the ABK and every immutable snapshot DEK wrapper unchanged: snapshots wrap each DEK under the stable ABK, not under the passphrase or recovery key directly. Therefore every retained snapshot remains decryptable through the new current key bundle without rewriting manifests or object bytes. A recovery-key rotation is complete only after the new recovery wrapper is committed and a decrypt/unwrap self-test succeeds; retain no old wrapper server-side.
3. Submit the new wrappers with the expected current key-bundle revision. The backend performs compare-and-set and rejects stale writes. If the CAS fails, fetch the current key bundle and retry only after re-unlocking the ABK and confirming the intended operation.
4. Do not retain old key-bundle versions in the active user collection. After a successful rotation, the old recovery key/passphrase no longer unlocks the current key bundle. A copy previously exfiltrated by an attacker cannot be revoked retroactively; document that limitation.
5. If an ABK itself is suspected compromised, passphrase/recovery-wrapper rotation is insufficient because the ABK still decrypts retained snapshot DEKs. V1 incident response is to pause backup operations, preserve local data, clearly warn that removing cloud snapshots is destructive, obtain confirmation, delete all retained cloud snapshots and the old key bundle, create a new ABK/recovery key and generate a fresh backup. Full re-encryption of prior snapshot history is outside v1.

### 6.6 Restore

1. Authenticate to the target persistent account and require email verification where applicable.
2. Fetch the current pointer and manifest; verify UID ownership, revision, expected schema/envelope version, object path, generation and byte-size limits.
3. Compare the current manifest revision/hash with `highestObservedRemoteBackupRevision` and `highestObservedRemoteBackupSha256`. Reject a lower revision as `REMOTE_BACKUP_ROLLBACK` and the same revision with a different hash as `REMOTE_BACKUP_EQUIVOCATION`. Never lower the high-water pair, even when the user explicitly restores an older retained snapshot; set only the materialized base pair to the selected snapshot after successful restore. Show an explicit warning before restoring an older retained snapshot. A subsequent upload from that older state requires the separate exact-revision cloud-replacement flow.
4. Download the exact object generation to a temporary file. Verify local SHA-256 against the manifest and check file length before parsing.
5. Parse the fixed binary envelope with strict length checks. Load the current key bundle. Validate algorithm/version/parameter allowlists before invoking Argon2id. Use the passphrase or recovery key to unwrap ABK, then unwrap the DEK and stream-decrypt the payload into an app-private temporary compressed file. Do not parse or decompress any bytes until GCM `doFinal()` succeeds and the full authentication tag has verified.
6. Decompress only after GCM authentication, streaming into the staging database/import pipeline with a hard uncompressed maximum of 100 MiB and a maximum 5:1 expansion ratio. Never allocate the full uncompressed object in one array. **[Amended 2026-10-31 by ADR #247: the expansion ratio is 100:1, not 5:1 — measured text-only history compresses 25–33:1, so the 5:1 bound refused every real snapshot (a 10-attempt snapshot already failed); the 100 MiB absolute maximum is unchanged.]** Delete unauthenticated and incomplete temporary/staging files on every error and at startup if left by a crash; only files marked authenticated after successful `doFinal()` may be consumed.
7. Validate all logical entities and relationships against the target schema; run only explicit versioned migrations. Do not run SQL or construct paths/table names from untrusted backup values.
8. Restore into a staging SQLCipher database. If local data exists, show a summary with `Keep Local`, `Restore Cloud Snapshot`, and `Cancel`; default to cancel/keep local. Swap the active database pointer atomically only after checks succeed.
9. Keep a recoverable pre-restore database until the user has successfully opened the restored app and passed integrity checks, then remove it according to the bounded cleanup policy.
10. A new device has no trusted local revision anchor. Do not claim to detect an attacker replaying an older valid backup on first restore. Tell the user the revision is the most recent one reported by the service.

### 6.7 Pair lifecycle and events

1. Preserve the existing pairing UI and E2EE payload/wire format, but route invitation creation, acceptance, termination and every new event append through validated callable functions.
2. Use a random invitation token with at least 128 bits of entropy, store only its SHA-256 hash, expire it after 24 hours, and accept it once. Limit invitation creation to 10 per UID per hour and no more than 20 unexpired pending invitations per UID. The recipient must authenticate as a persistent active account and explicitly accept. Reject self-pairing and duplicate active pairs.
3. Keep `userA`/`userB` participant UIDs immutable after activation. Only backend functions set lifecycle status or termination metadata. Pair records are readable only by participants.
4. `appendUserEvent` accepts only `pairId`, immutable `eventId`, protocol/schema version, existing protocol metadata fields and ciphertext. The server derives `senderUid` from verified Auth, checks active membership and account state, rejects UIDs with deletion jobs, allows anonymous sends only for existing active legacy pairs, and requires `emailVerified == true` for persistent email/password identities. Validate an exact field allowlist and limit encoded base64 ciphertext to 512 KiB. Do not use `request.resource.data.size()` as a byte count; it is the number of map keys. Never inspect encrypted semantic event types in server code.
5. Use transactional server-owned token buckets: per UID capacity 30 events/1 MiB ciphertext, refilling at 2 events/second and 10 MiB/minute; per pair capacity 60 events/2 MiB ciphertext, refilling at 4 events/second and 20 MiB/minute. A throttled append returns a retry delay and leaves the outbox item pending. Reuse the same event ID on retry. If the event already exists with identical immutable fields, return success without charging tokens; if the same ID is reused with different fields, return `EVENT_ID_CONFLICT`. Client Firestore rules deny all event-chain creates/updates/deletes; only the callable's Admin SDK write can append events.
6. Server-generated account-deleted/pair-terminated events use deterministic document IDs and are written only to `system_events`. Do not append literal plaintext strings pretending to be encrypted payloads. Render these as server-originated lifecycle notices, not partner-signed E2EE messages.
7. Do not implement trusted-device approval or E2EE recovery in v1. A new device must explicitly re-pair with partners; old ciphertext unavailable to that device is visibly labelled as unavailable.

### 6.8 Account deletion and guest cleanup

1. Persistent-account deletion UI explains that private profile/backups/devices/tokens will be removed; pair relationships will terminate; the remaining partner may retain previously shared ciphertext and a minimal tombstone; data already copied to another device cannot be recalled. Require the user to enter the explicit confirmation phrase and reauthenticate.
2. After provider reauthentication, force-refresh the Firebase ID token and submit the callable. The backend verifies the raw ID token with revocation checking enabled, checks that `auth_time` is no older than five minutes and matches the callable UID, and verifies `emailVerified` when required. Firebase defines `auth_time` as the authentication time rather than token issue time: https://firebase.google.com/docs/reference/admin/node/firebase-admin.auth.decodedidtoken. Test the claim after each supported provider's reauthentication flow.
3. In a server transaction, create/return `deletionJobs/{uid}` and set the existing profile to `PENDING_DELETION`. From this point, all client rules and callables deny new reads/writes that require an active account. Missing profile means denied, never active. Profile-repair endpoints must check for a deletion job and fail closed.
4. Disable the Auth user and revoke refresh tokens as part of request acceptance. If disabling/revocation or task enqueue fails, keep the durable job pending, do not report completion, and let reconciliation retry the missing stage. The job record and pending profile must be written before task enqueue so a lost enqueue response is recoverable.
5. The worker paginates all pairs containing the UID, regardless of status. For each pair, transact the termination state, scrub the deleted participant's display-name fields, and create/update a deterministic system event. Preserve the other participant's access to prior shared ciphertext according to the retention policy.
6. List and delete all Cloud Storage backup objects under the UID prefix, using object generation checks. Delete all backup manifests, key bundle, reservations, tokens, devices and all private subcollections. Use Firestore Admin `recursiveDelete` where appropriate for user-owned trees, and enumerate object storage separately. Firestore parent deletion does not cascade into subcollections: https://firebase.google.com/docs/firestore/manage-data/delete-data.
7. Only after pair cleanup and private-data cleanup, delete the user profile/tree and then delete the Firebase Auth user. Treat “user not found” at this stage as completed. Run a final object/profile sweep after Auth deletion to clean any orphan produced by an in-flight operation; the deletion-job guard must prevent that operation from successfully creating a new manifest or profile.
8. Mark the job `COMPLETED` only after verification of the cleanup stages. Keep the minimal deletion-job tombstone for 30 days, then purge. Shared pair tombstones retain only fields required to represent terminated relationship and historical ciphertext; scrub profile names, tokens, public key references and other private fields.
9. Implement guest deletion as an explicit double-confirmed path for the currently authenticated anonymous UID. It does not support backup/passphrase recovery. Use the same durable cleanup worker.
10. Keep a scheduled reconciler for stuck jobs, expired reservations, orphan objects, stale unfinalized guest transitions and Auth deletions performed outside the supported client flow. It must alert on stale unfinalized transitions rather than deleting guest data or pairs automatically. Disable Identity Platform anonymous auto-cleanup until this reconciliation is operational and verified.

### 6.9 Persistent-account sign-out and local-data removal

1. On ordinary sign-out, stop new writes and workers for the active UID, resolve pending Firestore SDK writes using §8.7, remove snapshot listeners, terminate the old Firestore instance, cancel Storage upload tasks and delete resumable-session metadata, unregister the FCM token mapping, close SQLCipher handles and clear in-memory repositories/caches. Run `clearPersistence()` only for the one-time legacy-cache migration described in §3, never as a routine sign-out or secure-erasure mechanism. Call `FirebaseAuth.signOut()` only after the teardown decision has been made and local retained data is safe; then route to the signed-out/guest entry flow.
2. Retain the encrypted UID-scoped SQLCipher database and its Keystore-protected key by default. Mark the UID session inactive so WorkManager cannot transmit outbox events while signed out. On a later sign-in to the same UID, reopen that UID's database and resume its outbox after validating the session generation. A different UID must use a different database path and key.
3. Provide a separate `Remove this account's local data from this device` action. Before deletion, show whether the outbox contains unacknowledged events and whether local state is newer than the last known cloud backup. Require explicit confirmation to discard any pending events or unbacked-up data. Delete the selected UID database, WAL/SHM/journal files, quarantine, related resumable upload state and only the Keystore aliases owned exclusively by that UID.
4. Never delete server pairs, cloud backup objects, account credentials or the Firebase Auth user as part of ordinary sign-out/local-data removal. Those are separate operations. Account deletion follows §6.8.

## 7. Implementation Sequence

Implement in the following release order. Do not start later release features while Release 0's security acceptance criteria remain unmet.

### Release 0 — Security hotfix; independent of account/backup feature work

1. Inventory current rules, pair writes, client versions and event format. Build negative rules tests from existing behavior.
2. Add backend callables for pair create/accept/terminate, `appendUserEvent`, system-event writes, and client compatibility metadata. Implement server-owned per-UID/per-pair token buckets for event append. Update the signed Release 0 APK to send outbox events through the callable before denying direct client event writes. Do not add trusted-device approval.
3. Harden `users` reads to owner-only and make profile mutations server-managed. Lock down pair membership/status fields. Require active account/pair state and verified sender UID in `appendUserEvent`. Deny all direct client creates/updates/deletes in `event_chain`, deny client writes to `system_events`, and deny writes to every server-owned collection.
4. Publish a minimum supported client version and update-required message for new clients. Existing clients that do not understand the message must fail closed when a denied operation hits tightened rules.
5. Deploy the signed Release 0 APK/update instructions through the current direct distribution channel. Do not retain vulnerable rules to preserve old-client behavior.
6. Verify anonymous auto-cleanup is disabled in Firebase Identity Platform. Determine whether it was ever enabled: disabling it does not cancel accounts already scheduled for cleanup. If it was previously enabled, inventory affected anonymous UIDs and complete the reviewed migration/mitigation plan before declaring guest data protected. Add job reconciliation before considering auto-cleanup in any future release.

### Release 1 — Accounts, local migration, and guest transition

7. Implement the idempotent legacy SQLCipher-to-UID-scoped database migration and tests.
8. Implement `AuthCoordinator`, Google Credential Manager, email/password authentication, email verification, reset and reauthentication.
9. Implement guest-transition preparation/finalization/cancellation callables and UI. Keep pairs unchanged until target authentication succeeds.
10. Implement UID/session-generation fencing, Firestore cache teardown, Storage task cancellation and FCM token reassociation.
11. Implement local quarantine and additive logical guest import with natural-key deduplication and crash-safe import journal.

### Release 2 — Encrypted backup/restore

12. Pin and test the Argon2id library; implement ABK key bundle, passphrase policy, recovery key UI and rotation.
13. Implement versioned binary snapshot envelope, authenticated key wrapping, payload encryption/decryption and corruption tests.
14. Implement server-side upload reservations, Storage rules, rate limits, streaming SHA-256 verification and manifest compare-and-set.
15. Implement staging restore, size limits, revision rollback prompts and failure cleanup.

### Release 3 — Account deletion and operations

16. Implement recent-auth-verified deletion request, immediate access disable/revocation, task-queue cleanup, scheduled reconciliation and out-of-band deletion backstop.
17. Add deletion/backup/pair metrics, alerts, runbooks and release rollback procedures.
18. Run the full failure-injection/device test matrix. Enable each feature flag gradually only after its release-specific gate passes.

## 8. Detailed Implementation Steps

### 8.1 Repository inventory and local migration

- Produce a code path map for auth, SQLCipher, pairing, E2EE outbox, Firestore, Storage, WorkManager, FCM, backup, profile and deletion.
- Record current Firebase Android BoM/SDK versions, Functions runtime/generation, current rule deployment, object metadata, Firestore offline persistence, database schema, and release signing identity.
- Add `LocalDatabaseMigration` with a durable journal, stable operation ID, checksums/counts, rollback copy and idempotent recovery after process death.
- Use a UID-scoped filename derived from a stable hash of the UID; never accept a filename/path from backup or UI input. Keep database encryption keys protected by Android Keystore.
- Test upgrades from the currently distributed APK, including a non-empty guest database, WAL/SHM files, pending outbox events, and a process kill at each migration step.
- Handle Android Keystore failures such as `KeyPermanentlyInvalidatedException`, missing aliases and `UnrecoverableKeyException` explicitly. Never delete/recreate a database key alias or encrypted database automatically after a key failure, and never loop app startup while retrying an unusable key. Preserve the encrypted files, enter a recoverable error state, and offer cloud restore with the backup passphrase/recovery mnemonic when available. If device-bound E2EE keys are lost, require the v1 re-pairing flow; v1 does not restore E2EE keys or historical ciphertext on another device.

### 8.2 Release 0 Firestore and pair security

- Add rules-unit-testing coverage through the Firebase Emulator Suite. Test every allowed and denied path before deploying.
- `users/{uid}`: owner read only; no client writes. Profiles are created/updated by callables. A missing profile or any status other than `ACTIVE` denies operations.
- `backupManifests`, `backupControl`, reservations, transitions, deletion jobs, device tokens, server feature gates and invitations: deny client writes; deny direct client reads unless specifically stated in the data model. All client requests go through owner-authorized callables where required.
- `pairs/{pairId}`: read only if authenticated UID equals immutable `userA` or `userB`; direct create/update/delete denied. Callables validate the invitation and status transition.
- `event_chain`: participant reads only; deny all client create/update/delete. `appendUserEvent` validates active participants, active sender profile, the fixed `USER_ENCRYPTED` record kind, supported schema version, exact field allowlist and bounded ciphertext before using Admin SDK to create immutable documents. Firestore rules must not claim to rate-limit direct event writes because direct event writes are prohibited.
- `system_events`: read only by participants; deny all client create/update/delete operations.
- Do not attempt to validate encrypted semantic types in rules. Enforce the outer record kind and schema; backend-owned system events are separated by collection and rule.
- Avoid `.size()` on the record map as a byte-size check. `Map.size()` counts keys. Check field allowlists and individual field lengths.
- Add server-side gate checks to new sensitive callables. A missing/disabled gate rejects new pair creation and backup reservations/commits. It may prevent starting a new guest transition, but it must never block cancellation or finalization/cleanup for a transition already prepared or target authentication already completed. Never block delete/revoke/cleanup/reconciliation workers through a general feature gate.
- Deploy the new callables and rules in the Release 0 window. If older APKs rely on direct unsafe pair writes, those writes must be denied; communicate the update and do not preserve the vulnerability.

### 8.3 Authentication implementation

- Use Firebase Auth; do not build a parallel account identity service.
- Google: Credential Manager with `GetGoogleIdOption`, Web Application Client ID, registered Android package/certificate configuration, and a cryptographically random one-time nonce. Pass the nonce through the supported Credential Manager request API, verify that the returned Google ID token contains the same nonce, and let Firebase validate the token issuer, signature and audience during credential exchange. Reject missing/mismatched nonce values; do not silently continue without nonce validation. Clear credential state on sign-out using the documented Google/Firebase integration pattern: https://firebase.google.com/docs/auth/android/google-signin.
- Distinguish cancellation from provider-unavailable exceptions. Cancellation returns to the previous screen. Do not immediately launch an alternative browser flow after cancellation.
- Do not implement the custom AppAuth/backend exchange in v1. On devices where Google sign-in is unavailable, offer email/password. Keep a future browser Google flow out of scope until its identity-provider configuration and real non-GMS behavior have been separately proven.
- Email/password: use generic errors for sign-in/reset requests before ownership is proven. Do not use `fetchSignInMethodsForEmail` as an account-existence oracle; email enumeration protection disables it for projects where protection is enabled. Official Firebase email-link/action guidance also documents the Dynamic Links shutdown and newer Hosting-based action links: https://firebase.google.com/docs/auth/android/email-link-auth. Configure and test the current Firebase action-link domain/app-link flow; do not depend on Firebase Dynamic Links.
- Set and test the Firebase “one account per email address” policy. Never manually merge accounts based only on equal email strings. Let verified provider credentials and Firebase collision semantics determine the next step.
- Use the Android `FirebaseAuthUserCollisionException` types/codes from the installed SDK; do not copy web SDK error-code assumptions into Kotlin.
- After email verification, refresh the token before calling protected endpoints. The server independently checks the Firebase Auth record's verified status/provider, not only a stale client boolean.
- Recent-auth flow: reauthenticate with the actual provider, force-refresh the ID token, and have the server validate the token with revocation checking and `auth_time <= 300 seconds`. Confirm behavior in staging for Google/email/password; reject if the claim is stale/missing rather than silently weakening the check. Firebase session behavior and revocation: https://firebase.google.com/docs/auth/admin/manage-sessions.

### 8.4 Guest transition and imported data

- Before preparing transition, require the user to pick import or discard, show pair-termination effects, and create the local quarantine if importing.
- `prepareGuestTransition` requires a valid anonymous Auth UID and an explicit transition intent. The client generates a random transition ID and a 256-bit one-time capability, then persists both in the encrypted transition journal before making the network call. The server stores only the capability hash, binds it to the transition ID and guest UID, and sets a 24-hour preparation lease. At lease expiry it marks the record `EXPIRED_UNFINALIZED` but retains the hash-only recovery record; repeated requests with the same ID/capability are idempotent. Preparation does not mutate pairs, user data, or Auth state.
- `finalizeGuestTransition` requires a valid authenticated persistent target UID, no pending deletion, the capability and the same transition ID. It accepts `PREPARED` before expiry; for `EXPIRED_UNFINALIZED`, require a target reauthentication with server-verified `auth_time` no older than five minutes. Atomically consume the capability and set `FINALIZING` so concurrent requests cannot duplicate work. Create the guest cleanup job and deterministic pair-termination events. Retries return the existing operation state. Keep the hash-only transition record until cancellation or successful finalization; do not let the scheduled reconciler delete it solely for age.
- The guest target is not recoverable after the Firebase current user switches; do not promise “return to guest.” On failed target sign-in, stay guest because finalization has not run. After target sign-in, retry finalization from persisted encrypted transition metadata. If the 24-hour lease elapsed, reauthenticate the target account and finalize the retained `EXPIRED_UNFINALIZED` record; expired/cancelled state must never be silently discarded.
- Use a local encrypted import journal and a stable `importOperationId`. The dry run calculates inserted, deduplicated and conflicting counts. User confirms before import commit. On failure, roll back the target DB transaction, retain quarantine and show `Retry import`.
- Derive natural keys and unique constraints from the real domain schema. Include duplicate same-day logs, habits with identical names, journal entries with different IDs but equal content, milestones, portable preferences and cached aggregate recomputation in tests.
- The import undo window is seven days and is disclosed before the operation. `Delete quarantine now` deletes the staged copy. No UI may imply that undo restores guest pairs, the old UID or the old session.

### 8.5 Backup key bundle, passphrase policy and binary envelope

- Pin a maintained Argon2id implementation after checking its current release, license, Android/ABI support and memory behavior. The selected dependency/version is committed and scanned; do not write a custom Argon2 implementation or silently substitute PBKDF2.
- Add unit tests against RFC 9106 known-answer vectors: https://www.rfc-editor.org/rfc/rfc9106. Use the current, reviewed BIP-39 English word list only for the recovery mnemonic, encode 256 bits of uniformly random entropy plus the defined checksum into 24 words, validate its checksum on input, and never derive a BIP-39 seed from it.
- Passphrase: for user-chosen passphrases, require at least 16 characters and zxcvbn-style score 4/4; reject common and predictable passwords. The generated option selects six words independently and uniformly with Android `SecureRandom` from a bundled, reviewed list of at least 7,776 unique words, using unbiased index selection; display the words with separators. Accept this generated option based on its generation method rather than a strength-estimator score. Do not treat local throttling as an offline-attack control.
- Initial ABK setup creates one random 32-byte ABK and one random 32-byte recovery key. Encode the recovery key with the official BIP-39 English word list as a 24-word mnemonic representing the raw entropy and checksum only; validate the checksum when entered and do not apply the BIP-39 PBKDF2 seed derivation. ABK wrappers use AES-256-GCM. The passphrase KEK is Argon2id-derived. The recovery KEK is HKDF-SHA-256-derived from the raw recovery key with a random 16-byte salt and the domain-separation label `MyExodus ABK recovery wrap v1`.
- `users/{uid}/backupControl/keyBundle` stores only encrypted ABK wrappers and metadata. No cleartext ABK, passphrase or recovery key is stored in Firestore, Storage, preferences, WorkManager arguments or logs. Include stable 16-byte `keyId` and `recoveryKeyId` values.
- Use exact AAD byte encodings; all integer fields are unsigned big-endian, all strings are UTF-8, and each UID is prefixed by a 2-byte unsigned big-endian byte length. Prefixes include the trailing zero byte shown as `\0`; do not use unordered JSON serialization as AAD.
  - Payload AAD: `ASCII("MYEXODUS-BACKUP-PAYLOAD-V1\0") || uint16be(uidUtf8Length) || uidUtf8 || backupId16 || uint32be(schemaVersion) || uint16be(envelopeVersion)`.
  - DEK-wrapper AAD: `ASCII("MYEXODUS-BACKUP-DEK-WRAP-V1\0") || uint16be(uidUtf8Length) || uidUtf8 || backupId16 || keyId16 || uint16be(envelopeVersion)`.
  - Passphrase ABK-wrapper AAD: `ASCII("MYEXODUS-ABK-PASSPHRASE-WRAP-V1\0") || uint16be(uidUtf8Length) || uidUtf8 || keyId16 || uint32be(bundleFormatVersion) || wrapperKindByte || kdfIdByte || uint32be(memoryKiB) || uint32be(iterations) || uint32be(parallelism) || salt16`.
  - Recovery ABK-wrapper AAD: `ASCII("MYEXODUS-ABK-RECOVERY-WRAP-V1\0") || uint16be(uidUtf8Length) || uidUtf8 || keyId16 || recoveryKeyId16 || uint32be(bundleFormatVersion) || wrapperKindByte || hkdfSalt16`.
  Exclude mutable bundle revision from AAD so an unchanged wrapper remains valid after another wrapper is rotated.
- Snapshot envelope layout is the fixed v1 binary format in §6.4. Use unsigned big-endian values and exact lengths. Unknown versions, invalid lengths, missing fields and trailing bytes must be rejected.
- The full-object SHA-256 is computed over exact bytes of the binary envelope and stored only in the manifest. The backend streams the exact Cloud Storage object generation and independently calculates SHA-256 before manifest commit. Also validate GCS/Firebase size and CRC32C metadata; CRC32C is a transport-corruption check, not a cryptographic authentication substitute. GCS checksum and object-generation metadata are documented at https://cloud.google.com/storage/docs/metadata.
- AAD construction, wrap formats, binary field offsets, nonce sizes, test vectors and version transitions must be covered by deterministic test fixtures. Use fresh nonces and never reuse a nonce with the same AES-GCM key.
- Key-bundle compare-and-set uses a numeric monotonic `revision`. On a known device, store the highest observed bundle revision in protected local state and reject unexplained rollback by default. A new device cannot prove a malicious server did not replay an old valid key bundle; document this limit and do not claim otherwise.

### 8.6 Backup reservation, upload and manifest commit

- `beginBackup` verifies a non-anonymous, active, verified account and the server feature gate; enforces five reservations per rolling 24 hours, the 105 MiB aggregate reservation limit and one outstanding reservation; compares `lastMaterializedRemoteBackupRevision` **and** `lastMaterializedRemoteBackupSha256` with the current server pointer and manifest; then creates a reservation with the exact base pair. In normal mode, require exact revision/hash equality (or both absent for the first backup). If a remote snapshot exists but the base is missing, reject with `BACKUP_BASE_REQUIRED`; if the remote revision is ahead, reject with `REMOTE_BACKUP_AHEAD`; if the same revision has a different hash, reject with `REMOTE_BACKUP_EQUIVOCATION`; if the current pointer is behind a known local high-water/base revision, reject with `REMOTE_BACKUP_ROLLBACK`. A separate `replaceCurrent` mode is available only after explicit user confirmation and only when the client's locally recorded high-water check detects no rollback/equivocation. Bind this reservation to the exact current revision and hash (or exact absence) shown to the user; if either changes, reject. Ordinary replacement cannot bypass rollback/equivocation errors.
- Firebase Storage rules deny all unreserved paths, overwrite attempts, owner mismatch, expired reservation, non-binary content type, and objects over 21 MiB. The rule checks a server-owned reservation. Configure and test the required Firestore lookup integration for Storage rules.
- Client upload is to a new object only. Use the Storage SDK's resumable URI support where available. Bind local session metadata to UID/session generation/backup ID; encrypt it at rest; clear it on account switch; cancel active tasks during logout. Extend the reservation before resume and during long uploads. If the upload session expired, the reservation reached its seven-day maximum, or the object source changed, start a new backup ID and orphan-clean the old reservation/object.
- `commitBackupManifest` validates the object path against the reservation, confirms ownership and generation, streams bytes through SHA-256, checks size and CRC32C, and verifies the current pointer still equals the reservation’s base revision or the exact explicitly authorized replacement revision. It must not trust a fresh client read of the pointer as proof that its local data includes that revision, and must not trust a client custom metadata hash.
- The backend atomically creates an immutable manifest and updates `backupControl/current` only if the reservation’s base/replacement revision and hash still match. A conflict returns `BACKUP_CONFLICT`; never overwrite the current pointer silently. After success, update the local database base revision to the returned manifest revision.
- Commit rejects if a deletion job exists, the user is not active, email verification is required but absent, or the server feature gate is off. If deletion and upload race, no manifest may be committed after deletion starts.
- Retain current plus two prior successful snapshots. Cleanup only deletes an old object after confirming that no current pointer, retained manifest, live reservation or active restore references it. Orphan cleanup uses a minimum 24-hour grace period and is idempotent.

### 8.7 Firestore, Storage, worker and token state across account changes

- Maintain one state owner for the active UID. Pin or upgrade to a stable Firebase Android SDK that supports `FirebaseFirestoreSettings.Builder.setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())` and configure it before any Firestore operation; disk persistence is prohibited in the new client. Do not fall back to deprecated persistence controls. At first launch, run the one-time legacy-cache purge from §3 before any query/listener/write. Before every switch, increment/invalidate the local session generation and stop scheduling new old-account work. Official API: https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/MemoryCacheSettings.
- Remove every Firestore snapshot listener, stop issuing Firestore writes and apply the old-user pending-write policy while the old user remains current. Await `waitForPendingWrites()` with a bounded deadline while online. If writes cannot be acknowledged, block account switching by default; permit switching only after explicit confirmation that pending Firestore SDK writes will be discarded.
- During an identity switch, after pending writes are acknowledged or explicitly discarded and listeners are removed, terminate the old Firestore instance and obtain a fresh instance configured with memory-only cache. Do not call `clearPersistence()` on every switch; it is used only in the first-launch migration before any Firestore operation to remove legacy disk persistence. `clearPersistence()` is not secure erasure, and `terminate()` alone does not cancel pending writes. Follow the Android SDK lifecycle requirements: https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/FirebaseFirestore.
- Treat SQLCipher outbox as the durable source for user E2EE sends. Do not rely on Firestore offline pending writes for outbox guarantees. Events remain bound to their original UID and event ID; the new account cannot send them.
- Cancel account-scoped WorkManager work and verify each worker checks UID/session generation immediately before remote send. Do not rely on cancellation alone; running jobs can continue.
- Cancel all Storage tasks for the old UID and delete encrypted local resumable-upload session records. A background upload is resumed only while the authenticated UID, generation and backup ID still match.
- Close SQLCipher handles, clear repositories/caches and remove old-account database files only after the user has chosen discard and quarantine/import conditions have been satisfied. Delete WAL/SHM/journal files as applicable. Delete Keystore aliases only when associated account data is being discarded, not during a recoverable transition.
- FCM token registration is a server-side upsert bound to authenticated UID and installation generation. On switch, unregister the old mapping, then register for the new UID. Token deletion may fail offline, so routing must verify current mapping/generation and never trust the client-supplied UID alone.
- Exclude database, quarantine, keys, upload session URIs, tokens, outbox and any temporary decrypted/compressed backup files from Android Auto Backup and device-to-device transfer. Configure legacy `fullBackupContent` and current `dataExtractionRules` as required by Android version. See https://developer.android.com/identity/data/autobackup.
- Ordinary persistent-account sign-out retains the encrypted UID-scoped database/key but closes all handles, makes the UID inactive, clears Firebase SDK cache state, removes the token mapping and pauses that UID’s workers. Provide a distinct destructive local-data-removal action; before executing it, surface pending outbox items and last-backup freshness, require explicit discard confirmation for unsent/unbacked-up data, and delete the database sidecars and only UID-exclusive key aliases. Do not delete cloud data or the Auth user on sign-out.
- Before guest import, compare the current remote pointer/manifest revision and hash with `lastMaterializedRemoteBackupRevision`/`lastMaterializedRemoteBackupSha256` and the monotonic `highestObservedRemoteBackupRevision`/`highestObservedRemoteBackupSha256`. If the current pointer is behind the high-water revision or the same revision has a different hash, stop with `REMOTE_BACKUP_ROLLBACK`/`REMOTE_BACKUP_EQUIVOCATION` and investigate; do not merge or replace. For a non-rollback divergence, preserve target-local state and use the explicit restore-and-merge / keep-local-and-defer-cloud-replacement / defer-import workflow in §6.2. Never restore over target-local changes without first staging them. If a required snapshot cannot be unlocked, mark import pending and preserve quarantine; block backup creation until the conflict is resolved so empty/stale local state cannot overwrite the cloud backup.

### 8.8 Pair functions and event validation

- `createPairInvitation` generates a random token (minimum 128 bits), stores only SHA-256(token), sets a maximum 24-hour expiry and a single-use state, and rate-limits by UID. Reuse the existing invite/share UX and deep-link format. Do not accept arbitrary participant UIDs from an untrusted client as proof of consent.
- Implement `appendUserEvent` as the only user-event write path. Validate Auth UID, active pair membership, active/non-deleting profile, event ID, existing protocol fields, field allowlist and ciphertext cap. In a Firestore transaction, check for an existing event ID first: return idempotent success only if immutable fields exactly match; otherwise reject `EVENT_ID_CONFLICT`. For a new event, atomically consume per-UID and per-pair token-bucket quotas and create the event document through Admin SDK. Initial per-UID limits are 30-event/1 MiB burst, 2 events/second refill and 10 MiB/minute sustained; per-pair limits are 60-event/2 MiB burst, 4 events/second refill and 20 MiB/minute sustained. Reject with a bounded `retryAfterMs` when either bucket is exhausted. Rate-limit documents are server-only and expire through scheduled cleanup. A timeout after commit is safe to retry because the event ID is stable.
- Deny all direct client writes to `event_chain` in Firestore rules. Client writes to `system_events` are also denied; system lifecycle notices use deterministic Admin-written document IDs.
- `acceptPairInvitation` requires a persistent active verified account, valid unexpired unused token, distinct UIDs and no duplicate active relationship. It atomically creates/activates the pair and consumes the invitation. Couple this with the existing E2EE handshake, not a new key protocol.
- `terminatePair` verifies caller is a participant, performs an allowed status transition, blocks new event writes and creates a deterministic `system_events` entry. It is safe to retry.
- `updateUserProfile` allows only approved profile fields. The profile version increments monotonically. Any display-name propagation job rereads the current profile and compares versions before writing pair-side projections; process in bounded chunks to avoid the Firestore batch limit (500 writes per batch reference: https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/FirebaseFirestore).
- `appendUserEvent` must validate exact outer keys and per-field sizes. Do not use map `.size()` to enforce bytes. Base64 ciphertext must be ASCII and `ciphertextB64.size() <= 512 * 1024`; other fields are individually capped. The outer record kind must be `USER_ENCRYPTED`. Encrypted content cannot be parsed by Firestore rules, so system event restrictions are achieved with a separate collection that denies all client writes.
- Keep event IDs immutable and deduplicate on read. Use the existing protocol sequence/hash fields if they exist; do not sort by `serverTimestamp` alone. Treat Firestore trigger delivery as at-least-once and unordered; triggers must be idempotent and must re-read authoritative current state where ordering matters: https://firebase.google.com/docs/functions/firestore-events.

### 8.9 Account-deletion worker and reconciliation

- `requestAccountDeletion` validates persistent account, fresh reauthentication, token revocation status and explicit confirmation. Verify `auth_time` server-side and check `emailVerified` where applicable. Never rely solely on client-provided timestamps.
- First transactionally create `deletionJobs/{uid}` and mark `users/{uid}.status = PENDING_DELETION`. A missing profile is always denied by rules. All callables check the deletion-job tombstone as well as profile status. This prevents the window where a still-valid ID token can recreate data while deletion is in progress. Normal operator tooling must create the job and mark the profile pending before deleting Auth; direct out-of-band deletion is emergency-only because the Auth trigger is asynchronous.
- Then disable the Auth user and revoke refresh tokens. All rules and server operations check `deletionJobs/{uid}` and require an existing profile with `status == ACTIVE`; a missing profile is always denied. Profile-repair initialization checks the job/tombstone before creating anything. Enqueue `processAccountDeletion` in Cloud Tasks. If disable/revoke/enqueue fails, retain the durable job and scheduled recovery retries the missing phase. Do not claim completion at request time.
- Use a stable job ID, persisted cursor, per-pair deterministic system-event IDs, bounded pagination and bounded writes. For a pair, update the existing system event by deterministic ID rather than append a new one after retry.
- Query `userA == uid` and `userB == uid` separately, without filtering to `ACTIVE`; deduplicate pair IDs and process `PENDING`, `ACTIVE`, `TERMINATED` and legacy statuses.
- Terminate all pairs, scrub deleted-user names and other private per-user fields, remove user-device/token references, and preserve the other participant's access to previously shared ciphertext subject to the product retention policy.
- Delete Cloud Storage objects via paginated prefix listing and generation-aware deletion. Recursively delete all private user subcollections; never assume deleting a Firestore parent removes descendants. Keep `deletionJobs/{uid}` outside the user tree until completion.
- Delete private user-owned data only after all relevant user write paths are blocked. Keep the server-side deletion job outside the user tree as the durable deny/tombstone. Remove the profile after private child cleanup, then delete the Auth user. Treat `USER_NOT_FOUND` as successful completion. Run a final sweep after Auth deletion; no create/commit path may proceed if a deletion job exists, and profile initialization must never recreate a missing profile when a deletion job/tombstone exists.
- Mark `COMPLETED` only after verification, keep a minimal job tombstone for 30 days, and then purge it. The scheduled reconciler detects stuck jobs, expired reservations, orphan objects and Auth/profile mismatches. The Auth delete trigger is only a backstop and is idempotent with the primary worker.
- Deletion is irreversible once the durable request is accepted; do not implement a cancellation window in v1. The UI must explain this before final confirmation.
- Guest deletion requires an active anonymous session plus a separate double confirmation; it runs the same cleanup job without pretending that an anonymous user has a recoverable identity.

### 8.10 Server feature gates and direct-APK compatibility

- Add server-controlled gate documents for new pair invitations/creation, new backup reservations/commits, and new guest-transition preparation/import initiation. A callable must read the gate before starting a new mutation. Do not gate completion/cancellation of an already-prepared guest transition, deletion, revocation, guest/persistent cleanup or recovery workers; accepted operations must remain finishable during a feature pause. Gate documents are writable only by privileged operators/deployment tooling.
- Deletion, revocation, guest/persistent cleanup and recovery workers must continue even when feature creation is paused.
- New clients read `clientCompatibility/current` at startup and before sensitive feature flows. If client version is below minimum, show the server-published update-required message and block only features the server has retired.
- The compatibility endpoint is not an authorization mechanism; rules and callables must independently validate every operation.
- Keep release signing key material out of the repository, document access controls and encrypted offline recovery. Verify package/certificate continuity for the direct APK. Do not implement a self-updater in this feature.

## 9. Error Handling

### Authentication and transitions

- **User cancels sign-in:** return to the previous session without browser fallback, pair mutation, quarantine deletion or state switch.
- **Credential already in use:** show neutral collision guidance; keep guest state; authenticate the intended target before finalizing any transition. Never delete the target account.
- **Target sign-in fails after transition preparation:** cancel/expire intent, keep guest account/database/pairs unchanged.
- **Target sign-in succeeds but finalization fails:** keep target account active, keep quarantine and transition capability, display `Finish account switch`, and retry idempotently. Do not return to a new anonymous session or claim rollback to the prior UID.
- **Import fails:** rollback target DB transaction, preserve quarantine, show counts/errors and allow retry. Do not terminate pairs twice or duplicate records on retry.
- **Network interruption:** do not destructively change local state before durable server acknowledgement. Retry operations with stable IDs.
- **Email unverified:** allow sign-in but return a clear `EMAIL_VERIFICATION_REQUIRED` from protected operations.
- **Recent auth stale/missing:** return `REAUTH_REQUIRED`; do not treat token refresh alone as fresh reauthentication.
- **OAuth/credential provider cancellation:** cancellation is a non-error state; record only a redacted metric.

### Firestore/Storage/session isolation

- **Pending Firestore writes cannot be acknowledged:** block account switch by default. If the user explicitly chooses to discard, stop writes, remove listeners, terminate the old in-memory Firestore instance, report the pending SDK mutations were discarded, and then continue with a fresh memory-cache instance. Do not call `clearPersistence()` for a routine switch; it is reserved for the first-launch legacy disk-cache migration. Never assume `terminate()` alone cancels server requests or safely reassigns queued writes.
- **First-launch legacy Firestore cache purge fails:** block Firestore-backed account access and identity switching, preserve local state and show a recoverable migration error. Do not continue with the legacy cache or re-enable disk persistence as a workaround.
- **Worker UID/generation mismatch:** stop without sending, mark old work as stale, and do not retry under the new account.
- **Storage upload interrupted:** resume only if session is valid and source file hash/length match; otherwise cancel the session and start a new backup ID. Orphan cleanup waits 24 hours and checks references.
- **Push unregister fails offline:** preserve cleanup retry metadata, but never route a push unless the server token mapping still matches the UID and installation generation.

### Sign-out and target account import

- **Ordinary sign-out:** close and lock the UID database, pause its workers and clear Firebase SDK state, but retain encrypted local data by default. Do not report cloud deletion or account deletion.
- **Remove local data from this device:** if pending outbox events or local changes newer than the last backup exist, require explicit discard confirmation. Cancellation leaves the database and key intact.
- **Existing target cloud snapshot is newer than local state:** pause guest import, retain quarantine and require target backup restore first. If the user cannot unlock the snapshot, mark `IMPORT_PENDING` and block backup upload until the conflict is resolved.

### Local Keystore and database key failures

- **Keystore alias missing or invalidated:** stop automatic database-open retries; preserve database, WAL/SHM/journal and quarantine files unchanged; show a recoverable local-storage error. Offer restore from an existing encrypted cloud backup using the backup passphrase or recovery mnemonic. Do not claim that the backup recovery key restores E2EE identity keys; re-pair where device-bound E2EE keys are lost.
- **Firebase password reset/change:** keep the backup key bundle unchanged. The Firebase credential and backup passphrase are separate secrets and must have separate labels and flows.

### Backup/restore

- **Wrong passphrase/recovery key:** show a generic unlock failure; retain the active DB and cloud objects. Local rate limiting is for casual user attempts only, not offline attack protection.
- **Invalid/unknown KDF parameters:** reject before allocating KDF memory. Only supported versioned parameter sets are allowed.
- **GCM tag failure/hash mismatch/truncated file:** abort restore, delete unauthenticated staging files, emit a redacted corruption metric, and keep active state unchanged.
- **Unsupported envelope/schema:** abort restore and request an app update; never perform a lossy downgrade.
- **Oversized/decompression-bomb payload:** reject before unbounded allocation/decompression.
- **Manifest conflict:** do not overwrite the current pointer; ask the user to refresh or explicitly retry from the latest revision. If local state is based on an older/missing remote revision, require the explicit restore/merge workflow or separately confirmed exact-revision replacement; do not fetch the latest pointer and use it as the local base automatically. A pointer lower than the locally materialized/high-water revision returns `REMOTE_BACKUP_ROLLBACK`; the same revision with a different SHA-256 returns `REMOTE_BACKUP_EQUIVOCATION`. Both fail closed and cannot be bypassed by ordinary replacement confirmation.
- **Backend hash computation fails:** do not commit manifest; preserve the previous backup and queue an orphan object for delayed cleanup.
- **Key-bundle CAS conflict:** reload current revision and require re-unlock/reconfirmation before retry; never overwrite a newer wrapper silently.
- **Backup storage unavailable:** report failure, do not claim backup succeeded, and keep offline app features usable.

### Deletion

- **Task enqueue/dispatch fails:** job remains durable; scheduled recovery re-enqueues it; UI shows pending, not completed.
- **A pair or object cleanup fails:** persist cursor and error category; retry idempotently. Do not create duplicate system events.
- **Auth deletion fails after data cleanup:** user remains disabled, profile missing/pending with a job tombstone, rules fail closed, and worker retries Auth deletion.
- **Auth user already absent:** treat Auth deletion stage as completed and run the final data sweep.
- **Duplicate request:** return the existing deletion job/status; do not start a second lifecycle.
- **Profile missing or deletion job present:** deny access and profile initialization; never fail open.

## 10. Security Requirements

1. Release 0 must close public profile reads and unsafe pair membership/status updates before account UI or backup work begins.
2. Firestore rules must default deny. Test all explicit allow paths and deny every unlisted collection.
3. User profile documents are owner-read-only; all profile writes and profile initialization use validated server functions.
4. Pair membership UIDs are immutable after activation. A pair can become active only through invitation acceptance. A participant cannot unilaterally add/replace the other participant.
5. User event writes require active pair, current account profile status `ACTIVE`, `senderUid == request.auth.uid`, exact outer schema/field allowlist and size bounds. Client writes to `system_events` are always denied.
6. `backupReservations`, manifests/pointers/key-bundles, guest transitions, deletion jobs, device tokens, server gates and invitation records cannot be written directly by clients. The user cannot choose an arbitrary target UID for a server-side cleanup or token registration.
7. Require non-anonymous persistent identity for backup key setup, reservation, manifest commit, import finalization into a cloud account and pair creation. Require verified email for email/password users on protected mutations.
8. For deletion and other sensitive server operations, extract the callable's bearer token from the raw request, call Admin `verifyIdToken(token, true)` for signature/expiry/revocation validation, compare its UID to the callable context, and enforce `auth_time` age <= 300 seconds. Test claim refresh following actual provider reauthentication; `getIdToken(true)` alone is not proof of fresh authentication. Deny disabled users and any UID with a deletion job or inactive/missing profile.
9. At deletion request time, mark the profile `PENDING_DELETION`; every rule/callable checks that state and checks the deletion-job record. Missing profile denies all account-owned access.
10. Backup secret hierarchy: 32-byte ABK, per-snapshot random 32-byte DEK, Argon2id-derived KEK and random recovery-key-derived KEK. Use fresh random 16-byte salts where specified and fresh 12-byte AES-GCM nonce for every distinct encryption/wrap operation. Never reuse a nonce with the same key.
11. Authenticate all envelope context with AAD. Payload AAD binds UID, backup ID, schema version and envelope version. DEK wrapper AAD binds UID, backup ID, key ID and envelope version. ABK wrapper AAD binds UID, stable key ID, bundle version, wrapper kind and KDF/HKDF parameters/salt.
12. Validate untrusted KDF settings before running Argon2id; permit only known supported parameter profiles. Never lower the KDF settings automatically to improve latency.
13. Require user-chosen backup passphrases to be at least 16 characters and score 4/4 with a maintained zxcvbn-style estimator; reject common/predictable values. Generate the six-word alternative uniformly with `SecureRandom` from a reviewed list of at least 7,776 unique words using unbiased index selection; exempt this generated phrase from the estimator. Keep it distinct from the separate 24-word recovery mnemonic. Explain that server-held ciphertext and wrapper metadata are vulnerable to offline guessing if the passphrase is weak.
14. Do not log plaintext, key material, passphrases, recovery keys, OAuth tokens/codes, Firestore payloads, object upload-session URIs or private journal content. Use best-effort clearing of mutable buffers; do not claim perfect JVM memory zeroization.
15. Display recovery keys once in a secure UI, prevent screenshots where supported, mark clipboard content sensitive if clipboard use is explicitly offered, and never persist the recovery key in plaintext. The recovery key cannot restore hardware-bound E2EE keys.
16. Enforce per-UID backup reservation quotas, object size limits, one active reservation, invitation rate limits, pair-creation quotas, and transactional per-UID/per-pair token-bucket limits for `appendUserEvent`. Direct event-chain writes are denied. All rate-limit state is server-only; do not trust client timestamps or counters. Apply a short fixed-window cap of 10 invitation creations per UID per hour and 20 pending invitations; cap backup reservations at five per UID per rolling 24 hours and one outstanding reservation. Add platform/provider sign-in abuse controls, Cloud budget alerts and orphan cleanup. App Check is not an authorization substitute.
17. App Check enforcement remains disabled in v1. For outside-Google-Play distribution, configure only documented Play Integrity settings and test real signed sideloaded APKs; do not require Basic Integrity. Do not depend on Preview reCAPTCHA or a custom attestation provider in v1. **[Amended 2026-10-31 by ADR #248: enforcement is ON (owner ruling 2026-09-19); the provider restrictions in this item still stand, and Identity Toolkit is deliberately unenforced.]**
18. Store no plaintext private key in Firestore/Storage. Do not automatically trust a new E2EE device. Use explicit re-pairing if the existing protocol has no supported recovery path.
19. Disable Android Auto Backup/device transfer for SQLCipher database, keys, cache, quarantine, outbox, FCM token, resumable upload URI and E2EE state. Configure both legacy and current Android backup rules.
20. During account switch, stop listeners, resolve or explicitly discard pending Firestore SDK writes while still under the old UID, terminate the old Firestore instance, cancel Storage tasks, delete old resumable URI metadata, increment session generation, and invalidate caches before opening the new UID's database. Disk persistence is disabled; the one-time legacy cache purge occurs before the first Firestore operation on upgrade.
21. Every worker rechecks UID/session generation immediately before remote writes. The server independently verifies UID ownership and account status.
22. Server feature gates are checked by every gated callable. Gate failure defaults to deny for creation/update mutations; cleanup/revocation workers remain enabled.
23. Disable Firebase Identity Platform anonymous automatic cleanup for this release; custom cleanup must not be bypassed.
24. Keep release signing credentials and OAuth configuration in controlled deployment secrets. Verify exact package/signing fingerprints. No OAuth client secret may be included in the APK.
25. State deletion limits explicitly: the service removes controlled private data, but it cannot erase copies previously downloaded by partners or physically guarantee flash-media erasure.

## 11. Performance Requirements

1. Preserve existing local-only features while offline. Backup creation and restore require appropriate network access; account-switch finalization and deletion display durable pending status if offline.
2. Support up to 20 MiB compressed snapshot plaintext, 100 MiB uncompressed serialized content and 21 MiB binary object. The payload is streamed to/from files; do not allocate multiple whole-payload copies in memory.
3. Encryption/decryption must use bounded buffers and app-private staging files. On restore, do not parse/decompress plaintext until the GCM tag succeeds. If plaintext staging is used, keep it inside private no-backup storage, never expose it to the active database before authentication, delete it on failure and clean stale staging files at startup.
4. Reject unsupported large backups before KDF/upload and show the supported size limit. Do not back up media/attachments in v1.
5. Argon2id with `m=32768 KiB`, `t=3`, `p=1` must run off the main thread. Benchmark p50/p95 latency and peak resident/heap memory on the lowest supported device and mid-range GMS devices. Target p95 below 2 seconds on the lowest supported device and below 1 second on representative mid-range devices. If the target fails, do not weaken parameters silently; revise supported-device policy or UX with security approval.
6. Use streaming SHA-256 for server-side object verification so the 21 MiB cap does not require buffering an entire object in function memory. Configure function memory/timeout for hashing and test at max object size.
7. Cloud Functions pair/profile fan-out and deletion use bounded pagination and batches below Firestore's 500-write limit. Profile propagation must not use a single unbounded batch.
8. Limit account transition/restore work to one active operation per UID/session generation; use unique WorkManager names/tags including a hashed UID and generation.
9. Backup reservations permit one active upload per UID and five reservation attempts per 24 hours. Retain current plus two prior committed backups; monitor object growth and clean orphans only after reference checks.
10. Stream Cloud Storage upload/download using resumable sessions where supported. Do not persist cleartext passphrases or unwrapped keys to make a background task resumable.
11. Firestore rules using `get()`/`exists()` for pair/profile checks incur rule-evaluation read cost and latency. Keep checks bounded, measure staging traffic and set budget alerts; correctness must not be removed to optimize rule reads.
12. UI must show progress/cancellation for KDF, backup upload/download, restore and import. Cancellation must remove staging files but must not delete the prior active database or current remote manifest.

## 12. Testing Requirements

### 12.1 Unit tests

- AuthCoordinator state transitions and cancellation behavior.
- Anonymous link success preserves UID/local DB.
- Credential collision does not delete/unlink target account.
- Guest transition preparation has no pair side effects; cancellation/expiry leaves guest state intact; expired transition finalization requires fresh target reauthentication; finalization is one-time and idempotent.
- Legacy SQLCipher migration recovery at every journal phase; missing/invalidated Keystore alias must preserve data and enter a recoverable error state.
- Firebase password reset/change never modifies the backup key bundle; backup passphrase change never changes Firebase Auth credentials.
- Import natural-key/deduplication and derived aggregate reconstruction; target cloud snapshot baseline resolution before import.
- Argon2id RFC known-answer vectors and exact parameters.
- ABK passphrase wrapping, recovery wrapping, passphrase change, recovery-key rotation and CAS conflict.
- Binary envelope encode/decode, byte offsets, big-endian fields, AAD reproducibility, invalid/truncated/oversized lengths and unknown versions.
- AES-GCM tampering, nonce generation uniqueness properties, wrong passphrase, wrong recovery key and corrupted DEK wrapper.
- Manifest revision conflict, same-revision hash equivocation, rollback/high-water handling, explicit replacement CAS, and monotonic high-water state when restoring an older retained snapshot.
- Server reservation quota/expiry, one active reservation, object generation checks, SHA-256 mismatch and orphan cleanup.
- Deletion job phases, idempotency, cursor retry, deterministic system events, Auth user absent, profile missing and final sweep.
- Profile propagation under duplicated/out-of-order trigger delivery.

### 12.2 Firebase Emulator Suite tests

- Anonymous user cannot read any other profile or query users.
- Owner can read only own profile, key bundle, current pointer and committed manifest metadata while active.
- Clients cannot write user profiles, key bundles, current manifest pointer, manifests, reservations, transitions, deletion jobs, device token mappings, server gates or invitations.
- Storage read/write/delete by wrong UID is denied; anonymous users are denied; unverified email/password users are denied; unreserved path, expired reservation, overwrite, invalid content type and oversized object are denied. Test reservation count/byte quota enforcement in callables, because Storage rules cannot count a user's total object set.
- Pair participant can read pair but cannot directly create/update/delete pair or change participants/status.
- Invitation can be accepted once and only by a valid target account; expired/replayed/self/duplicate invitation is rejected.
- Only participants can read the event chain; all direct client event creates/updates/deletes fail. `appendUserEvent` rejects inactive pairs, wrong/disabled/pending-deletion senders, unverified persistent email/password senders, malformed fields, oversized ciphertext and exhausted per-UID/per-pair token buckets; identical event retries are idempotent and conflicting event-ID reuse fails. Existing anonymous participants may continue only on already-active legacy pairs.
- Client writes to `event_chain` and `system_events` fail. `appendUserEvent` accepts a valid authorized append, returns idempotent success for exact retries, rejects event-ID collisions and reports bounded throttling without dropping the durable outbox item. Only Admin-backed callables can append user and lifecycle events.
- A missing profile, `PENDING_DELETION` profile or existing deletion job denies cloud operations; profile initialization cannot recreate it.
- Guest/target transition capability cannot be replayed or finalized by a different unauthenticated/anonymous target.

### 12.3 Device matrix

- GMS physical device with a sideloaded signed release APK.
- A second GMS OEM device (Samsung/Xiaomi or equivalent).
- One supported non-GMS device/ROM for email/password and core guest mode.
- The lowest supported RAM/ABI device, including 32-bit only if repository/product support policy includes it.
- At least one Android version at the actual minSdk and one current Android version.
- Actual release-signing package/certificate configuration, not debug credentials only.

Google sign-in must pass on supported GMS devices. Email/password, guest mode, backup/restore capability checks and in-app event refresh must not require Play services. App Check failures must not block v1 core functionality. **[Amended 2026-10-31 by ADR #248: local core and capability checks do not require Play services; the cloud calls behind backup/restore, pairing, partner sync and SOS fail closed on a non-GMS device.]**

### 12.4 Failure injection

- Kill the app during legacy database migration, guest quarantine creation, import, backup encryption, upload, manifest commit, restore, deletion and key-bundle rotation.
- Drop network after each server-side phase of guest transition; prove no pair termination happens before target authentication and retries do not duplicate operations.
- Fail target sign-in after transition preparation; guest session, local database, cloud profile and pairs remain intact. Verify that pair termination occurs only after target Firebase sign-in succeeds; after successful target sign-in followed by finalization failure, show pending completion, retain quarantine and capability, and never promise return to the anonymous identity.
- Let target sign-in succeed then kill before finalization; resume using the persisted capability.
- Upload object then fail manifest commit; verify old pointer remains and orphan is cleaned only after grace period.
- Alter object bytes, generation, size, CRC32C, SHA-256, envelope length, nonce, wrapped DEK, ciphertext and GCM tag; restore must fail without active DB mutation.
- Race two backup commits; only one expected-revision commit succeeds and no stale snapshot silently replaces current. Also sign in on a fresh/older device with a remote snapshot present and verify backup is blocked until restore or exact-revision replacement is explicitly confirmed. Test a target local database with unbacked-up edits plus a newer remote snapshot: both states must remain recoverable through the explicit merge choice.
- Rotate passphrase/recovery key with three retained snapshots; all snapshots remain decryptable with the current ABK key bundle and the old wrapper fails against the current bundle. Verify rotation only rewrites key-bundle metadata, not immutable snapshot objects.
- Switch accounts with active Firestore listeners, pending writes, memory cache, an active Storage upload, a running WorkManager worker and an FCM token mapped to the old UID. Verify disk persistence is disabled, no `isFromCache` snapshot is rendered for the new session, no old state is exposed or sent under the new UID, and pending writes are acknowledged or explicitly discarded before identity changes.
- Sign out of a persistent account with unbacked-up local data and pending outbox events: default sign-out retains encrypted local data and pauses sends; explicit local-data removal requires discard confirmation; signing into another UID cannot open the retained database.
- Sign into an existing account that has a cloud snapshot but no matching local materialized revision; verify guest import pauses until the target snapshot is restored or remains pending without overwriting the cloud pointer.
- Test deletion while backup upload/manifest commit/pair creation is in flight; once deletion is requested, no new manifest/pair/profile can be committed.
- Fail each deletion phase, replay tasks, deliver duplicate/out-of-order triggers, simulate out-of-band Auth deletion and prove eventual cleanup.
- Test recovery key display/clipboard privacy and stale staging/quarantine cleanup after process crash.
- Simulate missing/invalidated Keystore aliases and confirm startup does not loop, delete the database, or silently generate a replacement key.

### 12.5 Operational tests

- Verify server feature gates stop new mutations while leaving deletion/reconciliation enabled.
- Verify old-client permission-denied/update-required behavior after Release 0.
- Verify scheduled recovery enqueues stuck jobs and orphan cleanup never deletes a current or retained backup.
- Restore test environment from backups and run the deletion/backup runbook in staging.

## 13. Deployment Requirements

### 13.1 Release 0 security hotfix

1. Deploy pair lifecycle callable functions and server-authenticated `system_events` support.
2. Deploy hardened Firestore rules after emulator and staging tests. Restrict profile reads immediately; do not maintain an insecure compatibility exception.
3. Configure the server minimum-client-version record and update message.
4. Publish the correctly signed Release 0 APK through the current direct distribution channel. Communicate that older clients may be unable to create/update pairs until updated. Existing unsafe writes fail closed.
5. Confirm Firebase Identity Platform anonymous automatic cleanup is disabled.
6. Monitor permission denials and pair operation failures before proceeding.

### 13.2 Release 1 accounts and transition

1. Deploy profile initialize/update, guest transition and token register/unregister callables.
2. Ship legacy database migration and account isolation behind flags. Turn on only after migration and rollback-copy tests pass.
3. Configure Firebase Google provider credentials for the actual package and production signing certificate. Test Credential Manager on sideloaded GMS devices.
4. Enable email/password and verification flows. Keep non-GMS users on the guaranteed email/password path.
5. Enable account upgrade/collision transition for internal testers, then beta, then production.

### 13.3 Release 2 backup/restore

1. Deploy server gates, key-bundle functions, reservation/commit callables and Storage rules.
2. Verify Storage rule cross-service lookup IAM in staging.
3. Keep backup off until key/envelope vector tests, maximum-size streaming tests, hash verification and quota tests pass.
4. Enable for internal accounts first. Confirm successful restore after app reinstall and on a second device using the recovery key/passphrase.
5. Keep the previous two committed objects until the new manifest is committed and verified.

### 13.4 Release 3 deletion

1. Deploy the Cloud Tasks queue, deletion worker, scheduled reconciler and Auth deletion backstop.
2. Test complete deletion and every partial-failure stage in staging before making the UI entry point available.
3. Verify Auth user disable/revoke occurs at request acceptance and all rules/callables fail closed while pending.
4. Configure alerts and a manual remediation runbook before production enablement.

### 13.5 Rollback and compatibility

- Keep all client features behind flags, but also use server-side gates for sensitive mutations. Client flags alone are not rollback controls.
- A rollback may disable new account creation, backup creation or pair creation; it must not disable active deletion jobs, token revocation or cleanup.
- Do not roll Firestore rules back to the original insecure profile/pair rules. Roll back clients/functions while retaining safe authorization restrictions.
- Record the minimum client version and the exact feature set supported by each deployed APK. Keep the prior signed APK available through the direct-distribution channel.
- Do not implement a self-updater here. If an updater already exists, validate its signature/downgrade protections separately.

## 14. Monitoring Requirements

Emit structured, privacy-minimized metrics for:

- Auth anonymous-link success/failure, credential-conflict counts and cancellation.
- Legacy database migration success/failure/recovery.
- Guest transition prepared/finalized/expired/cancelled and import success/rollback/retry.
- Firestore account-switch cache-clear/pending-write failures and stale-worker rejection.
- Backup reservation denial, upload progress/failure, object hash verification, manifest commit/conflict, orphan cleanup, restore success/failure and restore revision rollback prompts.
- KDF runtime/memory failure buckets, envelope version mismatch and authenticated decryption failure; never include secrets or plaintext.
- Pair invitation acceptance/rejection, duplicate-pair attempts, `appendUserEvent` validation denials, per-UID/per-pair token-bucket throttles, event-ID conflicts and outbox retry outcomes.
- Deletion job age/status, phase failures, retries, final sweep failures and out-of-band deletion detection.
- App Check verdict/failure telemetry by supported device class where available; do not use it to block v1.
- FCM registration/unregistration and stale installation-generation drops.
- Per-UID backup quota, object count/bytes, function cost and daily Firebase/Cloud budget alerts.

Create alerts for: any deletion job stuck beyond the defined operational SLO; backup manifest hash failures; a spike in rules permission denials after Release 0; repeated account-switch cache-clear failures; growing orphan-object count; function/task failures; unexpected backup storage growth; and Auth/profile mismatches.

Logs must contain operation IDs, UID hash where possible, phase, error category and retry count. Redact emails where possible. Never log raw UID where not operationally necessary, invite tokens, FCM tokens, OAuth material, session URIs, backup plaintext, passphrases, recovery key or cryptographic key bytes.

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| Existing pair/profile authorization remains exploitable | High before Release 0 | Critical | Release 0 hardening first; default-deny rules and callable lifecycle | Emulator negative tests and permission-denial metrics |
| Legacy SQLCipher migration breaks existing guest state | Medium | High | Journaled migration, integrity checks and rollback copy | Migration metrics and upgrade tests |
| Anonymous Auth cleanup was previously enabled and existing guest UIDs remain scheduled for deletion | Unknown until project inspection | Critical for affected users | Inspect Identity Platform setting/history and affected `is_anon` UIDs; complete a reviewed mitigation before Release 1 | Auth/account inventory and user-impact report |
| Target sign-in fails after guest transition preparation | Medium | High | Preparation has no destructive side effects; expire/cancel intent | Transition phase metrics |
| Target sign-in succeeds but transition finalization is interrupted | Medium | High | Persist encrypted capability, idempotent finalize and retry UI | Pending transition age alert |
| Guest import duplicates logs or violates uniqueness | Medium | High | Schema-derived natural keys, dry run and idempotent transaction | Import validation/retry metrics |
| Weak passphrase permits offline guessing | Medium | High | Minimum length, strength estimator, generated phrase, Argon2id | Reject weak phrase; security review |
| ABK/key bundle rollback on new device | Low/Medium | High | CAS revision and local high-water check on known devices; honest new-device limitation | Revision rollback warning metric |
| Object hash or generation is trusted without verification | Low after fix | High | Backend streams exact generation and computes SHA-256 | Hash/generation mismatch alert |
| Backup object storage quota abuse | Medium | High | Reservation rate limits, one active reservation, 3-object retention, orphan cleanup | Per-UID object count/bytes and budget alerts |
| KDF memory/time causes low-end device failure | Medium | High | Pin/test library, benchmark real hardware, run off main thread | Runtime and memory failure buckets |
| Firestore pending cache leaks state across accounts | Medium | Critical | One-time legacy disk-cache purge; remove listeners, wait/discard under old UID, terminate old instance and create a fresh memory-cache instance | Account-switch failure tests and metrics |
| Old WorkManager task sends under new account | Medium | Critical | UID/session-generation fence immediately before send and server validation | Stale-worker rejection metric |
| Deletion allows profile/data resurrection | Medium | Critical | Pending profile + deletion-job guard, missing profile denied, disable/revoke, final sweep | Auth/profile mismatch and post-deletion write alerts |
| Task retry duplicates pair termination events | Medium | High | Deterministic event ID and idempotent transaction | Duplicate-event invariant tests |
| Event-chain flood causes partner denial of service or cost spike | Medium | High | All event writes pass through `appendUserEvent`; transactional per-UID/per-pair event and byte token buckets; 512 KiB event cap | Throttle metrics, pair event-rate alerts and budget alerts |
| User expects historical E2EE data on new phone | High | Medium/High | Explicit re-pairing and visibly unavailable history; no false recovery promise | Support topics and UX tests |
| Non-GMS users expect FCM/Google sign-in | Medium | Medium | Publish capability boundary; email/password and in-app refresh supported | Device-matrix QA and support metrics |
| App Check configuration locks out sideloaded users | Low in v1 because enforcement off | High | No hard enforcement; documented sideload settings; test signed APKs | App Check telemetry only |
| *(Amended 2026-10-31 by ADR #248: enforcement is ON, so this row's rating is superseded — sideloaded GMS devices pass; non-GMS devices lose cloud features by design, and that limitation is stated in the product docs.)* | | | | |
| Old APK breaks after rules hardening | High | Medium/High | Release 0 hotfix, compatibility message, fail-closed writes | Version-specific permission metrics |
| Recovery-key exposure through screen/clipboard | Low/Medium | High | Secure display, no persistence, sensitive clipboard handling | Manual security test |
| Deletion leaves shared PII/ciphertext beyond intended policy | Medium | High | Explicit scrub/tombstone retention policy and legal review | Deletion audit report |

## 16. Open Questions

Only repository-specific or external policy facts that cannot be determined from this plan remain open. All architectural decisions above are fixed.

1. **Current database schema:** Which existing entity IDs and unique constraints define natural keys for guest import, including same-day habit logs, journal entries, milestones, and derived streaks? Inspect the schema and document the exact mapping before implementing §6.3.
2. **Current E2EE/pair protocol:** What are the actual invitation handshake, event wire format, sequence/hash fields and existing partner-identity flow? Inspect and preserve them when implementing `appendUserEvent` and the existing pair UX. V1 does not implement trusted-device approval or E2EE recovery; new devices require explicit re-pairing.
3. **Firestore persistence and pending writes:** Is disk persistence enabled in the currently shipped APK, and which writes rely on it? Implement §3/§8.7 against the installed SDK version; if migration requires a special process-restart order, document and test it.
4. **Supported device floor:** Does the product officially support 32-bit-only devices and what is the lowest RAM profile? Keep the current minSdk and benchmark every supported ABI before enabling backup.
5. **Direct APK compatibility:** What in-app/update messaging mechanism do current versions support? New clients must implement the compatibility check. Older versions that cannot show it still fail closed under hardened rules; do not weaken rules to accommodate them.
6. **Cloud deployment:** Which Functions generation/runtime, region, bucket, and IAM configuration are deployed? Use the existing supported runtime unless it cannot implement the required task queue, streaming hash or reconciliation safely.
7. **Shared-data retention approval:** Product/legal owners must approve the stated retention of shared E2EE ciphertext, minimal pair tombstones and 30-day deletion-job tombstones, including how sender UID references are represented after account deletion. Do not claim regulatory compliance until this review is complete.
8. **Argon2 implementation pin:** Select a maintained implementation/version after verifying license, dependency provenance, API 26+ compatibility and ABI behavior. Record the exact artifact/version in the repository and preserve RFC test vectors. This is a dependency selection, not permission to change the KDF parameters.
9. **Anonymous auto-cleanup history:** Has Firebase Identity Platform automatic anonymous cleanup ever been enabled in this project? If yes, which anonymous UIDs are already scheduled for deletion? Do not launch the feature until the impact and mitigation for those existing scheduled deletions is reviewed; switching the setting off does not cancel already scheduled cleanup.
10. **Backup snapshot sizes:** Does the production database ever approach the 100 MiB uncompressed / 20 MiB compressed cap even without media? Measure representative and high-history fixtures before Release 2; if legitimate text-only snapshots exceed the cap, change the envelope/storage limits in a separately versioned spec, not by silently raising runtime limits. **[Resolved 2026-10-31 by ADR #247: measured 1,000–150,000 attempts at 25–33:1 compression; absolute caps never approached (150,000 attempts = 22.3 MB / 682 KB), but the 5:1 expansion bound refused every real snapshot, so it is now 100:1 (3× headroom). Test: `BackupPayloadTest.aHighHistoryTextOnlySnapshotStaysFarBelowTheCompressedCap`.]**

## 17. Acceptance Criteria

### Release 0 gate

1. Any authenticated or anonymous user cannot read/query another user's private profile.
2. Clients cannot directly change pair participants/status or write system lifecycle events.
3. Firestore rules deny all direct client `event_chain` creates/updates/deletes. `appendUserEvent` rejects non-participants, wrong/disabled/pending-deletion senders, inactive pairs, unknown fields and over-limit ciphertext; transactional UID/pair token buckets throttle floods, identical retries are idempotent and conflicting event-ID reuse is rejected.
4. Pair invitation/accept/terminate and event append operations are backend-validated and idempotent.
5. Security tests pass before rules are deployed. Older clients fail closed rather than retaining unsafe permissions.

### Release 1 gate

6. Existing guest mode continues working after upgrade, and the legacy SQLCipher migration preserves rows, relationships and outbox state through process termination at each migration phase.
7. Linking an unused credential preserves the original anonymous UID.
8. A credential collision never deletes/unlinks the existing account and never mutates guest pairs before successful target sign-in. The UI must disclose that successful finalization terminates guest pairs; if sign-in fails before finalization, no pair is changed; if finalization fails after target sign-in, the operation resumes idempotently and the app does not claim the former guest identity can be restored.
9. If target sign-in fails, guest session, database, pairs and outbox are intact. If target sign-in succeeds and finalization fails, quarantine and transition state survive restart and finalization can safely retry.
10. Guest import is additive, natural-key-aware, idempotent, transactional and recoverable for seven days; its undo wording never claims to restore the old guest identity or pairs.
11. Firestore cache/pending writes, Storage tasks, upload session URIs, WorkManager work, database handles, caches and FCM mappings cannot cross UID/session generation boundaries.
12. Google sign-in works on the supported sideloaded GMS matrix; email/password and core guest behavior work without Google services.
13. Ordinary persistent-account sign-out retains encrypted local data by default while clearing active SDK/session state; explicit local-data removal warns about and requires confirmation for unsent/unbacked-up data.
14. Guest-data import never merges into an empty/stale target database while a newer target cloud snapshot exists; restore/import stays pending and backup upload is blocked until that conflict is resolved.

### Release 2 gate

15. Backup payloads are client-encrypted and use the versioned binary envelope, ABK key bundle, per-snapshot DEK, Argon2id parameters, strict passphrase policy and recovery-key wrapper.
16. Passphrase change and recovery-key rotation update only the versioned key bundle through compare-and-set and leave all retained snapshots readable through the stable ABK; rotation succeeds only after an unwrap/decrypt self-test, and no old wrapper remains server-side.
17. Weak/common user-chosen passphrases are rejected; generated six-word passphrases use unbiased cryptographic selection from the specified wordlist; the recovery key is shown once with secure-screen/clipboard protections and no plaintext persistence. Firebase password reset/change leaves the backup key bundle untouched.
18. `beginBackup` enforces reservation quota/expiry. Clients cannot upload outside a reservation, overwrite objects, delete committed objects or upload over the size cap.
19. Backend recomputes SHA-256 over actual uploaded object bytes and verifies generation/size before manifest commit. The hash exists only in manifest metadata, never as a self-included hash field in the object.
20. Concurrent backup commits and stale-device uploads cannot silently replace newer current state. The client uses its locally materialized base revision and SHA-256; a missing/stale baseline requires restore or exact-revision replacement confirmation. Same-revision hash changes and lower pointer revisions are hard failures that ordinary replacement cannot bypass. The high-water revision/hash never decrease, including after an explicit restore of an older retained snapshot. The old pointer survives a failed upload/commit; orphan cleanup observes grace and reference checks.
21. Restore rejects corruption/tampering/unsupported versions/oversized payloads without mutating the active database. Restore conflicts require explicit user confirmation. No new-device anti-rollback guarantee is claimed.

### Release 3 gate

22. Deletion requires explicit confirmation and fresh server-verified authentication for persistent accounts; the backend checks `auth_time`, token revocation and account status.
23. The deletion request durably sets `PENDING_DELETION`, disables/revokes the Auth session, and enqueues a worker or remains recoverable by scheduled reconciliation.
24. Rules and callables deny new operations after deletion begins; missing profiles and existing deletion jobs never fail open or trigger automatic profile recreation. Existing accepted guest transitions and deletion/reconciliation workers remain finishable during feature-gate pauses.
25. Deletion processes every pair state, scrub fields, create deterministic system events, delete all private Storage/Firestore descendants, delete Auth last, and run a final sweep.
26. Replayed tasks, repeated requests, timeouts, out-of-band Auth deletion and failures at every phase converge without duplicate lifecycle events or false completion.
27. Shared ciphertext retained for partners and the limits of remote/physical erasure are accurately disclosed.

### Operational gate

28. Server feature gates can pause new mutations without disabling deletion/reconciliation.
29. Dashboards, alerts, privacy-minimized logs, quota/budget monitors and operator runbooks are available.
30. App Check enforcement remains disabled; direct-APK and non-GMS support claims match actual device test results. **[Amended 2026-10-31 by ADR #248: enforcement is ON; signed direct-APK distribution on GMS devices is the supported path, and non-GMS cloud unavailability is stated from provider behavior — no non-GMS device has been tested, which is the honest gap.]** Firestore uses memory-only cache for account/partner data, and SDK cache results cannot cross session boundaries. The one-time legacy disk-cache purge runs before any Firestore operation in the new APK; every identity switch waits for or explicitly discards pending old-UID SDK writes, removes listeners, terminates the old instance and starts a fresh memory-cache instance.
31. All Critical/High findings from the plan-specific audits that apply to the final plan are resolved, implemented as explicit constraints, or listed as a repository/policy dependency in §16. No unresolved contradiction remains in implementation instructions.

## 18. Definition of Done

The feature is complete only when all of the following hold:

1. Release 0 security hardening has shipped independently and the emulator suite proves the previously permissive profile/pair operations are denied.
2. The legacy SQLCipher migration and all guest-transition phases are crash-safe, idempotent and covered by automated tests on a non-empty database.
3. Existing-account collision handling preserves both the target cloud data and guest local snapshot until the user explicitly chooses import/discard.
4. No claim or code path attempts to restore the old anonymous identity after switching to an existing Firebase UID.
5. Guest data import includes documented natural keys, transactionality, dry-run summary, quarantine retention and retry/undo semantics.
6. Account switches and sign-out remove old listeners, reconcile or explicitly discard pending writes under the old UID, terminate the old Firestore instance and start a fresh memory-cache instance, cancel Storage jobs/URIs, fence WorkManager work and rebind tokens while retaining encrypted local data on ordinary sign-out. The legacy disk cache is purged once before the first Firestore operation in the new APK.
7. Backup key hierarchy, exact binary format, AAD, KDF settings, passphrase policy, recovery-key lifecycle and key-bundle CAS are documented and covered by test vectors. Firebase password reset/change cannot invalidate the independent backup passphrase/key hierarchy. Target cloud snapshot conflicts are resolved before guest import or any new backup can overwrite the pointer.
8. Server-side manifest commits verify the exact uploaded object's SHA-256 and generation before advancing the current pointer.
9. Backup/restore passes maximum-size, low-memory, interrupted-transfer, corrupted-object, concurrent-commit, passphrase-change and recovery-key-rotation tests.
10. Firestore and Storage rules have emulator/integration tests for all allowed/denied cases, including auxiliary collections, reservation checks, pending deletion and event-size boundaries.
11. V1 has no E2EE trusted-device approval UI, registry, device-key recovery path or protocol implementation. A fresh device requires explicit re-pairing and displays prior device-key-encrypted history as unavailable.
12. Account deletion is idempotent, task-queued, observable, token-revoking, fail-closed and tested through every partial failure and final sweep.
13. Anonymous automatic cleanup is disabled; scheduled reconciliation and out-of-band deletion handling are active.
14. App Check remains non-blocking, and supported GMS/non-GMS behavior is documented from real signed-APK tests. **[Amended 2026-10-31 by ADR #248: "non-blocking" now applies to local core only; cloud calls fail closed on non-GMS devices, and the behavior is documented rather than device-proven for non-GMS.]**
15. Server feature gates, client compatibility messaging, structured redacted metrics, alerts, backup/orphan cleanup and runbooks are deployed.
16. Release signing identity is preserved; no secret is committed; current APK release/update instructions and rollback procedure are documented.
17. No Critical or High implementation-blocking finding remains open outside the explicitly listed repository/policy questions in §16.

---

### Official technical references

- Firebase anonymous authentication and linking: https://firebase.google.com/docs/auth/android/anonymous-auth
- Firebase Google sign-in on Android: https://firebase.google.com/docs/auth/android/google-signin
- Firebase Android OIDC provider flow: https://firebase.google.com/docs/auth/android/openid-connect
- Firebase Auth session revocation: https://firebase.google.com/docs/auth/admin/manage-sessions
- Firebase ID token claims (`auth_time`, `email_verified`): https://firebase.google.com/docs/reference/admin/node/firebase-admin.auth.decodedidtoken
- Firestore offline persistence: https://firebase.google.com/docs/firestore/manage-data/enable-offline
- Android Firestore memory-only cache (`MemoryCacheSettings`): https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/MemoryCacheSettings
- Android Firestore persistence API (`waitForPendingWrites`, `terminate`, `clearPersistence`; persistent cache is not recommended when cached data could disclose information between user sessions): https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/FirebaseFirestore
- Firebase anonymous-user automatic cleanup (30-day eligibility and previously scheduled deletions): https://firebase.google.com/docs/auth/android/anonymous-auth
- Firestore quotas: https://firebase.google.com/docs/firestore/quotas
- Firestore deletion semantics: https://firebase.google.com/docs/firestore/manage-data/delete-data
- Firebase Storage rules and object size validation: https://firebase.google.com/docs/storage/security/rules-conditions
- Firebase Storage Android resumable uploads: https://firebase.google.com/docs/storage/android/upload-files
- Cloud Storage object generation and checksums: https://cloud.google.com/storage/docs/metadata
- Firebase Cloud Functions task queues: https://firebase.google.com/docs/functions/task-functions
- Firebase Auth deletion triggers and generation limitations: https://firebase.google.com/docs/functions/1st-gen/auth-events and https://firebase.google.com/docs/functions/auth-events
- Firebase Firestore trigger delivery behavior: https://firebase.google.com/docs/functions/firestore-events
- Firebase App Check Play Integrity settings for outside-Google-Play distribution: https://firebase.google.com/docs/app-check/android/play-integrity-provider
- Android Auto Backup/data extraction: https://developer.android.com/identity/data/autobackup
- Argon2: RFC 9106, https://www.rfc-editor.org/rfc/rfc9106
- OWASP Password Storage Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
