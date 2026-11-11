# Partner Delivery Integration Map (Phase 0)

Plan: `app/plans/My Exodus Reliable Partner Event Delivery-implementation-plan.md`
(untracked). Phase: §7 Phase 0, blocking gate. Date: 2026-11-11. Status: **gate PASSED —
implementation may proceed**, with the carries listed in §9.

This is the implementation artifact required by plan §3.2 and §16. Every fact below was
read from the current source/configuration, not inferred. File references are clickable
targets; section references match the plan.

## 0. Phase 0 gate verdict — approved E2EE is present

The plan's stop gate (§3.3, §16, I14) asks for four things. All four exist today:

| Requirement | Evidence |
|---|---|
| Approved partner E2EE codec | `accountability/enrollment/CekDerivation.kt` — ECDH P-256 → HKDF-SHA256 → 32-byte pairwise CEK, `deriveSessionKey(cek, epoch)`; `accountability/crypto/EventCipher.kt` — AES-256-GCM, deterministic chain nonce `HKDF(CEK,"nonce"‖sequence)`, random-nonce `encryptWithAad` for standalone envelopes |
| Recipient public-key registry readable from background work | `enrollments` table in `myexodus_accountability.db` (plain-FBE Room, NOT vaulted): `EnrollmentRecord.partner_signing_pubkey`, `partner_agreement_pubkey` (DER SPKI), `my_sender_key_id`, `pairId`, `pairing_epoch`; `EnrollmentDao.getByPairId/all()` |
| Background-key access | The agreement private key (`AccountabilityKeyStore.PURPOSE_AGREE_KEY` / pair-scoped alias, API 31+) is created with `setUserAuthenticationRequired(false)` — usable while screen-locked. The *signing* key is auth-bound; that is the defect to fix, not a missing dependency |
| Receiver decrypt path | `accountability/sync/MailboxEventProcessor.kt` — verify signature over envelope hash FIRST, then resolve session key by declared `keyEpoch` (no trial decryption), then `EventCipher` decrypt; `FirestoreMailboxReceiver` feeds it |

**Consequence:** no invented cryptography and no plaintext fallback are needed or allowed.
The three real defects this plan fixes are repository facts, not gate failures:

1. Partner journal/outbox live in the **vault-wrapped rules store** → background sync
   refuses while App Lock is closed (`PartnerSyncWorker.kt:46-51` → `Result.retry()`).
2. The chain signing key is **auth-bound with 900 s validity** → a cold window blocks
   background signing (`AccountabilityKeyStore.SIGNING_ALIAS`; `SigningPath` taxonomy).
3. There is **no server-side recipient encryption-key registry** — `pairings/{pairId}.proofs`
   stores only `publicKeyHash`. The plan's `recipientEnvelopes` key-ID mapping is supplied
   by Phase 2's registered-device records.

## 1. Kotlin components, schemas, vault graph, producers (§16.1)

App: package `com.example.myexodus`, minSdk 26, targetSdk 36, compileSdk 37. Hilt DI.

**Databases and the vault boundary**

| Store | Owner | Vault-wrapped? | Holds |
|---|---|---|---|
| `myexodus_accountability.db` v14 | `accountability/state/AccountabilityDatabase.kt` | **No** — plain-FBE Room by design (AEAD envelopes, hash pointers, enrollment public keys only) | `enrollments`, chain state, mailbox cursors |
| `myexodus_rules.db` | rules store behind `AppRulesStoreFactory` | **Yes** when App Lock enabled | `PartnerEventJournal`, `PartnerActivityOutbox`, `PartnerPlan`, `DeviceLiveness`, approval requests, rule state |
| activity DB | `VaultDatabase.ACTIVITY` | **Yes** when App Lock enabled | habit/urge logs |

Vault gate: `AppRulesStoreFactory` refuses to open while the DI predicate
`{ appLock.get().state.value.showsProtectedUi }` / `allowsVaultAccess` is false
(`di/DatabaseModule.kt`); `withVault` lease, drain-then-close `lockVault(timeoutMs)`,
`VaultLockedException`. Background code has no prompt path.

**Sync pipeline (existing)**

- `accountability/sync/PartnerSyncWorker.kt` — 15-min unique periodic `partner-sync`
  (CONNECTED constraint, `UPDATE` policy) + expedited one-shot `partner-sync-now`
  (`KEEP`); wake input keys `messageId`, `forceHeartbeat`; catches `VaultLockedException`
  and returns `Result.retry()`.
- `accountability/sync/PartnerSyncCoordinator.kt` — `syncOnce` under `syncMutex`:
  reconcile journal → chain events, repair protocol, drain outbox → mailbox, pull mailbox;
  `SyncReport.signingBlocked` surfaces the cold window.
- `accountability/sync/PartnerSyncSignals.kt` — `pulledEvents`, `activityEmitted`,
  `signingNeeded`, `ruleChangesJournaled`, `signingDeferred`.
- Signing: `accountability/crypto/AccountabilitySigner.kt` +
  `AccountabilityKeyStore.SIGNING_ALIAS` (`setUserAuthenticationRequired(true)`, 900 s);
  `SigningPath` = Biometric / DeviceCredential / Blocked. ADR #216 (cold window reported,
  never disguised) and ADR #296 (prompt at the act, foreground heals; background never
  prompts).
- Mailbox: `FirestoreMailboxReceiver`, `MailboxEventProcessor`, `MailboxOutboxDrainer`,
  `MailboxMessageHandler`, `MailboxRejectionChecker`, `ChainStateStore`, `SessionFence`.
- Push: `PushInstallationRegistrar` (FID = installationId, FCM token, `pushEligible`
  snapshot, full-row replace each pass; never throws).

**Current partner event producers (the capture points Phase 3 hooks)**

| Producer | Location | Signal |
|---|---|---|
| Rule journal write | `RulesRepository.onJournaled` wired in `di/DatabaseModule.kt` | `PartnerSyncSignals.ruleChangesJournaled` + `enqueuePartnerSyncNow` |
| Blocked attempt | `blocking/AttemptRecorder.kt`, `blocking/PartnerActivityReceiver.kt` | journal via `PartnerActivityJournal` |
| Streaks | `HabitRepository.emitForHabit` (476/485/599/634/776/816/886) | journal |
| Check-ins | `MainActivity.kt:875` → `PartnerActivityJournal.emitForProfile` | journal |
| Plans | `RecoveryPlanRepository.kt:106/121/131` | journal |
| Journal choke point | `PartnerActivityJournal.kt:154` | `activityEmitted.tryEmit(Unit)` |

**Existing → new component mapping (plan §3.2 interfaces)**

| New interface | Existing machinery to extend | Change |
|---|---|---|
| `PartnerEventRecorder` | `PartnerActivityJournal` | record into the new CE outbox, not only the vaulted journal |
| `ServiceEventOutbox` | — (new) | CE (plain-FBE Room) durable queue so workers need no vault |
| `ServicePayloadProtector` | `EventCipher` / `CekDerivation` | reuse as-is |
| `BackgroundEventSigner` | `AccountabilitySigner` | new non-auth P-256 Keystore key (I01); keep foreground key for approvals |
| `InstallationRegistrar` | `PushInstallationRegistrar` + `enrollments` | add device-registration record + recipient key IDs |
| `PartnerEventSyncWorker` | `PartnerSyncWorker` | same work names; drain CE outbox without vault |
| `DeviceContactWorker` | `PartnerHeartbeatClient` | signed latest-wins counter in the same liveness system |
| `EnforcementHealthSnapshot` | — (new) | required-capability snapshot |
| `PartnerDeliveryCoordinator` | `PartnerSyncCoordinator` | orchestrate new outbox alongside legacy path |
| `PartnerEventPayloadCodec` | `serialization/AccountabilityEventWire` | versioned JCS envelope |
| `PartnerDeliveryDiagnostics` | `SyncReport` logging | stage counters |

## 2. Firebase deployment (§16.2)

- Project `myexodus-53804` (number 913808045052); Functions Node 22,
  `firebase-functions` 7.4.0, `firebase-admin` 14.4.0, region `us-central1`.
- Callable App Check enforcement (`enforceAppCheck: true`) exists on 23 callables,
  including `registerPairing` (`functions/index.js:64`), `submitHeartbeat`
  (`functions/heartbeat.js:184`), `appendUserEvent` (`functions/event-append.js:162`),
  pair lifecycle, guest transition, DMS, backup, account deletion, `sendNudge`.
  `relayMailboxMessage` is a Firestore `onDocumentCreated` trigger
  (`mailboxes/{recipientUid}/messages/{messageId}`, `functions/relay.js:72`) — triggers
  cannot use callable App Check; authorization is server-side.
- Firestore paths in use (`accountability/sync/FirestoreSchema.kt`, `firestore.rules`):
  `pairings/{pairId}` (+ `system_events`, `nudges`, `nudgeState`, `nudgeSettings`),
  `mailboxes/{recipientUid}/messages/{messageId}` (+ `acks/{installationId}`; six
  `hasOnly` keys: `senderId`, `senderUid`, `pairId`, `payload`, `keyEpoch`, `timestamp`),
  `pairing_tokens/{tokenId}`, `users/{uid}/push_installations/{installationId}`,
  `dms_install_state`, `dms_notification_outbox`, `dms_ingestion_outages`,
  `rate-limits/*`, `idempotency/*`, `config/features`, `config/dms`.
- The plan's `partnerEvents` ledger, device registration, notification-job outbox and
  fanout records **do not exist yet** — Phase 1/2 add them. `dms_notification_outbox` is
  the liveness-notice outbox, not a per-event ledger.

## 3. Package identity, signing, App Check, FCM (§16.3)

- applicationId `com.example.myexodus`.
- Release signing config reads `signing.properties` from `~/.myexodus-release-key`
  (or `MYEXODUS_RELEASE_SIGNING_PROPERTIES`); never committed. Release APK built
  2026-11-11 cert: `CN=MyExodus Release, OU=Mobile, O=MyExodus, C=KE`, SHA-256
  `8f88188070952dde4c490a01ce8aa0407e9172ee4bbc035693ea795ab598854b`.
- App Check: `accountability/sync/AppCheckInstaller.kt` installs
  `PlayIntegrityAppCheckProviderFactory`; debug-only `factoryOverride`; installed at
  app start and before auth-dependent paths. Live backend rejects emulator pairing; the
  sanctioned two-AVD vehicle is the LOCAL rig (`ATTESTATION_STUB=1` + DebugBackend),
  never a production path.
- FCM: stable FID (`FirebaseInstallations`) is the installationId; token from
  `FirebaseMessaging`; registration row keyed by FID; `pushEligible` capability snapshot;
  token/FID refresh written every sync pass. No AndroidConfigV2/EAP in use (plan §8.6
  keeps the stable token path).

## 4. Existing liveness system (§16.4)

`accountability/monitoring/PartnerHeartbeatClient.kt`: sends `{installId, counter}` via
`submitHeartbeat`; counter persisted before send; client floor 15 min + ≤2 min jitter;
server `functions/heartbeat-logic.js` answers ACCEPTED / STALE / RATE_LIMITED with a 30 s
minimum, feeding `DMS_PARTNER_PUSH_MS` = 6 h partner deadline and the
`dms_notification_outbox` + LATE_BURST breaker. Unsigned, no capability map.

Phase 6 **extends this system**: adds a signed, latest-wins counter and
`EnforcementHealthSnapshot` in the same documents/heartbeat contract (AC15). Do not
create a second heartbeat pipeline.

## 5. Recipient registry and fanout (§16.5)

- Registry is local-only today: `enrollments` (see §0). Server-side `pairings` proofs
  carry `publicKeyHash` only — no recipient encryption-key registry, no key-revision
  mapping. Phase 2's `registerOrRotateDevice` records supply the `recipientEnvelopes`
  key-ID → authorized-partner mapping the plan requires (§8.4, I14).
- Fanout: no coded cap exists. The data model supports multiple pairings per UID
  (`pairId` = sorted uid pair; `EnrollmentDao.all()` iterates all). Currently tested
  production usage is one partner per user. Phase 1 must state the operational fanout
  bound used by the atomic fanout write; per-partner installation fanout remains N per
  partner installation.

## 6. Device/GMS matrix and App Check quota (§16.6)

| Device / runtime | API | Integrity path | Status |
|---|---|---|---|
| X6532 (owner, release v16/1.1.14) | physical | Play Integrity App Check, off-Play APK | **PASS (historical, while paired)** — completed pairing + mailbox sync against `enforceAppCheck` callables; S-matrix legs through 2026-11-11. Not currently paired (pair terminated at S33) |
| X6532C (partner, release v16/1.1.14) | physical | same | **PASS (historical, while paired)** — same live backend evidence; app data now absent (S33 uninstall), reinstall + re-pair required for Phase 8 |
| AVD API 34 x86_64 | 34 | debug App Check provider / LOCAL rig only | **SUPPORTED FOR TESTING ONLY** — live backend rejects |
| No-GMS / de-Googled devices | any | none | **UNSUPPORTED** — cannot obtain an App Check token; must be declared in the supported-device copy |

- Play Integrity production quota headroom: **not yet measured** (requires GCP quota
  query against project 913808045052). Carry to Phase 2 before enforcement rollout
  (AC18). Not a Phase 0 gate item: enforcement already runs in production for the
  existing callables.
- API 26-29 physical tier: **no device on hand** (also the App Lock release blocker).
  AC01 says "every tested AFU API level" — the map records this coverage gap; do not
  claim it.

## 7. Bug baselines (reproduction evidence)

| Condition | Baseline evidence |
|---|---|
| Cold signing window (>15 min screen-locked) | S31, `app/docs/partner-notification-scenarios.md` (2026-11-10): owner ignored the prompt; rows stayed journaled; delivery at 15:42:59 after owner lock/unlock at 15:42:44. Mechanism: ADR #216 / ADR #296 |
| App Lock vault closed | `PartnerSyncWorker.kt:46-51` logs `sync deferred: vault locked` → `Result.retry()`; `AppRulesStoreFactory` refuses to open. App Lock Phase 6 gate (2026-11-11): locked enforcement held the Clock daily-limit attempt in its outbox; process restart + unlock flushed `1/1` (`app-lock-repository-inventory.md` §15) |
| After reboot | `MyExodusApp.kt:330` enqueues the periodic sync only when a local session exists (post-unlock process start); worker retries while the vault is closed |
| After network loss | WorkManager CONNECTED constraint holds the pass; mailbox drain retries on the next pass (at-least-once; attempt log is local truth) |
| Source-level reproduction | Existing unit tests pin coordinator reconciliation/cold-window behavior; new outbox tests land in Phase 1/3 |

Diagnostics: the two failure conditions are already distinguishable in logs
(`SyncReport.signingBlocked`, `sync deferred: vault locked`, ADR #216 reporting), so no
temporary diagnostic code was added in Phase 0. If Phase 4 finds a stage the existing
logs cannot separate, add the diagnostic there and remove it before Phase 8.

## 8. Security-relevant facts for later phases

- `AccountabilityDatabase` plain-FBE justification holds only while it stores envelopes,
  hashes and public keys. The new `ServiceEventOutbox` must keep the same rule: locally
  wrapped ciphertext + routing metadata only, never plaintext partner content (I07).
- `SecurityFence`/`SessionFence` and `ownerUid` binding already exist; Phase 3 must bind
  every outbox row to `ownerUid` (R18).
- `AccountabilityKeyStore` never requests StrongBox as mandatory (I01 already satisfied).
- Existing mailbox 4096-byte payload limit and relay rate limit (10/pair/min, 200
  messages/sender/day) constrain envelope size and burst behavior (AC19).

## 9. Carries into later phases

1. Server-side recipient key registry + revision mapping (Phase 2).
2. Play Integrity quota headroom for the staged beta (Phase 2, AC18).
3. Operational fanout bound (Phase 1).
4. Physical API 26-29 execution tier (Phase 8, release blocker; no device).
5. `.107` currently has no app data (S33 uninstall) — a fresh `install -r` and re-pair
   are required before the Phase 8 two-device matrix.
