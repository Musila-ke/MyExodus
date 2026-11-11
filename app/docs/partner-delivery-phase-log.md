# Partner Delivery Implementation Log

Running log required by plan §7/§18. One entry per phase: exact touched files, tests,
migration status, deviations, carries. Plan:
`app/plans/My Exodus Reliable Partner Event Delivery-implementation-plan.md` (untracked).

Path note: the plan names `docs/…`; this repository keeps spec/implementation docs in
`app/docs/`, so both artifacts live there.

---

## Phase 0 — Inventory and baseline (2026-11-11)

**Status: PASSED (blocking gate).** Implementation may proceed.

**Scope:** plan §3.1–§3.5, §7 Phase 0, §16 items 1–6.

**Touched files**

- `app/docs/partner-delivery-integration-map.md` (new — the §3.2/§16 artifact)
- `app/docs/partner-delivery-phase-log.md` (new — this log)
- No production code, no schema, no manifest changes.

**Gate evidence (map §0)**

| Gate item | Result |
|---|---|
| Approved E2EE codec | Present — `CekDerivation.kt`, `EventCipher.kt` |
| Recipient public-key registry readable from background | Present — `enrollments` in plain-FBE `myexodus_accountability.db` |
| Background (screen-locked) key access | Present for the agreement key (non-auth-bound); signing key is auth-bound (the defect) |
| Receiver decrypt path | Present — `MailboxEventProcessor` verify-then-decrypt |
| One event traced detection → partner receipt | Traced: producer → `PartnerActivityJournal` → rules journal → coordinator reconcile/sign → mailbox outbox → `mailboxes/{uid}/messages` → relay trigger → inbox pull → `MailboxEventProcessor` |

**Bug baselines recorded (map §7)**

- Cold signing window: matrix S31 (2026-11-10) — rows journaled, prompt ignored, delivery
  15 s after owner unlock; ADR #216 / ADR #296.
- App Lock vault closed: `PartnerSyncWorker.kt:46-51` logs `sync deferred: vault locked`
  and returns `Result.retry()`; App Lock Phase 6 gate evidence (inventory §15) shows the
  locked path holding work until unlock.
- Reboot: sync enqueued at post-unlock process start (`MyExodusApp.kt:330`); worker
  retries while the vault is closed.
- Network loss: WorkManager CONNECTED constraint holds the pass; next pass retries.

**App Check / device matrix (map §6)**

- Release-signed direct APK cert SHA-256
  `8f88188070952dde4c490a01ce8aa0407e9172ee4bbc035693ea795ab598854b`; both physical
  X6532 handsets ran it and completed pairing + mailbox sync against `enforceAppCheck`
  callables on the live backend during the S-matrix legs (through 2026-11-11). The pair
  is currently terminated (S33) and `.107` has no app data — historical evidence, not a
  current pairing claim.
- Emulators/no-GMS: unsupported for production; the LOCAL rig (`ATTESTATION_STUB=1` +
  DebugBackend) is the only two-AVD vehicle.
- Play Integrity quota headroom: **not measured** (needs GCP quota query). Carried to
  Phase 2; not a gate item because enforcement already runs in production.

**Tests**

Phase 0 changed no code, so it added no tests; the bug baselines above are device logs
(matrix S31, App Lock Phase 6 evidence), not automated tests. The accountability JVM
baseline on the Phase 0 tree was captured 2026-11-11:
`:app:testDebugUnitTest --tests "com.example.myexodus.accountability.*"` — 810 tests
across 76 suites, 0 failures, 0 errors, 0 skipped (exit 0). Later phases must keep this
suite green.

**Migration status**

None — no database or schema change in Phase 0.

**Deviation note**

Plan §3.3 asks for diagnostic-only event-stage logging to reproduce cold-signing and
locked-vault failures. Both conditions are already distinguishable in existing logs
(`SyncReport.signingBlocked`, `sync deferred: vault locked`, ADR #216 reporting), so no
temporary diagnostic code was added. If a later phase needs a stage the logs cannot
separate, add it there and remove it before Phase 8.

**Carries**

1. Server-side recipient key registry + revision mapping — Phase 2.
2. Play Integrity quota headroom — Phase 2.
3. Operational fanout bound — Phase 1.
4. Physical API 26-29 tier (no device) — Phase 8 coverage gap, do not claim.
5. `.107` reinstall + re-pair before the Phase 8 two-device matrix (its data was removed
   at S33).

**Next:** Phase 1 — protocol, security schema and tests.

---

## Phase 1 — Protocol, security schema and tests (2026-11-11)

**Status: PASSED.** No ingest endpoint exists yet; the security-negative suite that
guards it is in place.

**Scope:** plan §7 Phase 1, §8.1 (outbox contract shape), §8.2 (signed envelope, JCS,
fixtures, limits), §8.3 (registration/rotation document schema), §8.4 (result codes,
idempotency contract), §10.10 (collections).

**Touched files**

- `app/src/main/java/com/example/myexodus/accountability/delivery/Jcs.kt` (new — RFC 8785
  canonicalizer constrained to the protocol value set; UTF-16 key order; rejects
  non-safe integers, unpaired surrogates, unsupported types)
- `app/src/main/java/com/example/myexodus/accountability/delivery/PartnerEventProtocol.kt`
  (new — v1 envelope model, kinds, issues, result codes, wire/batch types, validation,
  digest, limits)
- `app/src/main/java/com/example/myexodus/accountability/delivery/PartnerDeliveryClock.kt`
  (new — fake-clock seam; wall clock stamps events, monotonic clock bounds leases)
- `app/src/main/java/com/example/myexodus/accountability/delivery/PartnerDeliveryTransport.kt`
  (new — fake-transport seam; whole-request failure keeps exact signed bytes)
- `functions/partner-events.js` (new — server mirror of the same protocol; pure logic, no
  firebase-functions/admin import; also documents every §8.3/§10.10 document schema)
- `functions/test/fixtures/partner-event-jcs-v1.json` (new — shared golden fixture read by
  both suites: 4 valid events with canonical bytes + SHA-256, 4 generic JCS vectors, 18
  invalid events, one Node-signed P-256 signature vector)
- `functions/test/partner-events.test.js` (new — 13 server contract tests)
- `app/src/test/java/com/example/myexodus/accountability/delivery/JcsTest.kt` (new)
- `app/src/test/java/com/example/myexodus/accountability/delivery/PartnerEventProtocolTest.kt`
  (new)
- `firestore.rules` (modified — six server-only `partner*` collections with
  `allow read, write: if false`, plus the recipient-only `partnerMailboxes` read carrying
  the deletion-tombstone gate)
- `functions/emulator-test/firestore-rules.emulator.js` (modified — two new rules tests)

**Requirements verified**

| Plan requirement | How |
|---|---|
| `ownerUid` inside the signed bytes | Included in `toJcsValue()`; rebinding test asserts the digest changes (both languages) |
| Safe no-effect `RECIPIENT_KEY_STALE` | Sole code for which `allowsCiphertextRegeneration` is true; unknown codes fail closed (both languages) |
| Immutable after possible acceptance | Only `ACCEPTED`/`ALREADY_ACCEPTED` are acknowledged; `ALREADY_ACCEPTED` never regenerates |
| JCS canonicalization fixtures | Shared fixture consumed by Node and Kotlin; cross-language P-256 signature (Node signs, JCA verifies) proves byte parity |
| Stable IDs and sequence | UUIDv4 `eventId`/`installationId`, `sequence >= 1` per installation. Atomic allocation belongs to the Phase 3 outbox transaction (§8.1); the constraints are pinned here |
| Registration and rotation schemas | Field-level schemas documented in `partner-events.js` COLLECTIONS; documents are client-unreadable/client-unwritable in rules. Callables land in Phase 2 |
| Firestore collections/rules | Six server-only collections + recipient mailbox read; emulator tests deny all six both directions |
| Client/server error codes | `PartnerEventResultCode` / `RESULT` and `PartnerEventIssue` / `ISSUE` are the same closed sets |
| Event-type validation | Kind allowlist excludes server-generated kinds; per-kind metadata rules (BLOCK_ATTEMPT requires category, RULE_CHANGE_ATTEMPT requires ruleChange, CHECK_IN must be empty) |
| Fake clock and fake transport | `PartnerDeliveryClock` (system impl included), `PartnerDeliveryTransport` |
| Security-negative tests before an ingest endpoint | 18 shared invalid vectors + tamper/wrong-key/submission/batch/limits tests; no endpoint opened in this phase |

**Tests**

- `node --test functions/test/partner-events.test.js` — 13 tests, 13 pass.
- `:app:testDebugUnitTest --tests "com.example.myexodus.accountability.delivery.*"` —
  BUILD SUCCESSFUL (JcsTest + PartnerEventProtocolTest).
- `npm run test:rules` (from `functions/`) — 17 tests, 17 pass, including “partner
  mailboxes are recipient-read-only” and “partner delivery registry, ledger, job and
  liveness documents are server-only”; emulator exit code 0.

**Migration status**

None. No client code calls the new collections yet; rules deny clients in both
directions, so opening them is a server-side change with no data to migrate.

**Deviation note — duplicate JSON keys**

Plan §8.2 asks the wire to “reject ambiguous duplicate JSON keys.” Inside a Firebase
callable this is not implementable: the callable transport parses the request body
before the function receives `data`, so a duplicate-key document arrives as one parsed
object. The security property is preserved — the signature covers the RFC 8785
serialization of the parsed value, so any byte-level ambiguity that parses to the same
value verifies to exactly one signed event, and any different value fails signature
verification. A hand-written parser would be the only way to reject duplicates, which
§8.4 forbids for new endpoints. Recorded here rather than silently skipped.

**Fixture regeneration note**

`partner-event-jcs-v1.json` embeds a P-256 public key and signature over one golden
event. If a golden event or the envelope shape ever changes, regenerate the keypair,
signature and every `canonical`/`sha256` field together and rerun both suites; the two
suites disagree loudly on any partial change, which is the intended pinning.

**Carries**

1. Registration/rotation callables and attestation evaluation — Phase 2.
2. Play Integrity quota headroom measurement — Phase 2.
3. Event ID/sequence atomic allocator — Phase 3.
4. `RECIPIENT_KEY_STALE` response must carry the current key IDs/revision — Phase 2
   response shape.
5. Operational fanout bound (from Phase 0) — still Phase 1-scope schema: bounded batch
   size 20 is defined; the recipient fanout bound is enforced in Phase 2 ingress.

**Next:** Phase 2 — backend registration and ingress.

---

## Phase 2 — Backend registration and ingress (2026-11-11)

**Status: PASSED.** Both callable pairs are deployed and the §8.3/§8.4/§8.6/R08 backend
contracts are emulator-verified. No client calls them yet (Phase 3 is the first caller).

**Scope:** plan §7 Phase 2 (verbatim: callable v2 challenge issue/consume, Keystore
public-key registration, RKP-aware attestation trust evaluation including both currently
trusted Google roots, account/installation binding, App Check enforcement, event signature
verification, transactionally idempotent ledger/mailbox/notification jobs and explicit
per-event responses; verify backend tests and Firestore rules before client rollout),
§8.3.2–§8.3.8, §8.4, §8.6 (coalescing, job/mailbox 1:1, producer bound), R08.

**Touched files**

- `functions/partner-registration.js` (new) — `registerDeviceChallenge` and
  `registerOrRotateDevice` with handler-factory seams; challenge = 32 random bytes, only
  the SHA-256 of the raw bytes stored, 5-minute TTL, scoped to (uid, installationId,
  intent); registration = recomputed key IDs, proof of possession over the JCS message
  `{v:1, ownerUid, installationId, keyId, challenge}`, attestation branch, one transaction
  consuming the challenge and writing the signing registration + recipient key registry,
  rotation audit capped at 20 entries, idempotency replay via `idempotency/{requestId}`.
- `functions/partner-ingest.js` (new) — `ingestPartnerEvents`: batch validation (≤20
  events, 256 KiB), account tombstone gate, 240 batch/h per (uid, installation), one
  transaction per event with ledger duplicate-digest check first, signature verification
  against the registered SPKI, recipient-key/pairing re-read, `RECIPIENT_KEY_STALE`
  no-write response carrying current authorized keys, inline mailbox + notification job
  (block coalescing by minute) or a durable `partnerFanoutTasks` record above 20
  recipients, contact at most once per accepted batch, per-event result objects.
- `functions/partner-events.js` (modified) — `COLLECTIONS.fanoutTasks`; schema comment
  updated for the mailbox doc-id change, `recipientKeyId` on registrations, `intent` on
  challenges, `replacedByKeyId` on recipient keys, and the fanout document.
- `functions/pair-ids.js` (new) — `pairIdOf` moved out of `index.js` so ingest derives the
  same deterministic pair id without importing the whole deploy unit.
- `functions/rate-window.js` (modified) — `consumeLimit` (transactional fixed-window
  increments), now shared by pairing, registration and ingress.
- `functions/index.js` (modified) — exports `registerDeviceChallenge`,
  `registerOrRotateDevice`, `ingestPartnerEvents` plus the `_create*` seams; the stub rig
  (`FUNCTIONS_EMULATOR=1` + `ATTESTATION_STUB=1`) swaps the verifier and revocation source.
- `firestore.rules` (modified) — `partnerFanoutTasks` deny; the partner collection rules
  from Phase 1 are now RELEASED to production (see deploy below).
- `functions/emulator-test/partner-registration.emulator.js` (new — 17 tests)
- `functions/emulator-test/partner-ingest.emulator.js` (new — 14 tests)
- `functions/emulator-test/firestore-rules.emulator.js` (modified — `partnerFanoutTasks`
  added to the server-only rules test)
- `functions/package.json` (modified — `test:partner-registration`, `test:partner-ingest`)

**Requirements verified**

| Requirement | How |
|---|---|
| Callable v2 challenge issue/consume | Challenge suite: only the hash is stored, 5-min expiry, unauthenticated/invalid-intent refusals; registration consumes it in the same transaction as the writes |
| Keystore public-key registration | SPKI parsed and constrained to EC P-256; both key IDs recomputed from DER and never accepted from the request |
| RKP-aware attestation, both roots | `attestation.js` rewrite (Phase 1 tail): teardown-free nearest-root chain build including the active and Feb-2026 ECDSA roots, provisioning-info position OID 1.3.6.1.4.1.11129.2.1.30 checked immediately below the leaf KeyDescription, revocation fail-closed, verified boot, TEE/StrongBox tiering — 26/26 unit tests. The registration callable passes the pinned app package + release-cert digest and `requireVerifiedBoot: true`, and a PRESENTED chain that fails is an error (never a silent downgrade) |
| Account/installation binding | Registration keyed `{uid}:{installationId}`; event ownerUid/installationId must match the authenticated uid and the request's installation (emulator cases for both mismatches) |
| App Check enforcement | `enforceAppCheck: true` on all three callables |
| Event signature verification | Signature verified against the registered SPKI over the RFC 8785 bytes; `SIGNATURE_INVALID` and unknown-key `REENROLL_REQUIRED` paths tested |
| Transactionally idempotent ledger/mailbox/jobs | One transaction per event: ledger + mailbox + job (or the fanout task) commit together (R08); identical retries are `ALREADY_ACCEPTED`, a different digest under a live event id is `EVENT_ID_CONFLICT`; an accepted event stays accepted after key rotation |
| Explicit per-event responses | `{results: [{index, code, eventId?, issue?, reason?, currentRecipientKeys?, fanout?}]}`; a partial batch commits only the valid events |
| §8.6 coalescing, 1:1 mailbox/job, producer bound | Block pushes share `blk:{uid}:{installation}:{minute}`; every mailbox record has its distinct job; 240 batches/h per (uid, installation) |
| Backend tests and rules verified before client rollout | All suites below green before the rules/functions release |

**Tests**

- `npm test` (functions unit, no emulator) — 226 pass, 0 fail.
- `npm run test:partner-registration` — 17 pass, 0 fail.
- `npm run test:partner-ingest` — 14 pass, 0 fail.
- `npm run test:rules` — 17 pass, 0 fail.
- `node -e "require('./index.js')"` with a Functions-shaped `FIREBASE_CONFIG` — the three
  exports load (the bare local invocation fails earlier in the pre-existing
  `backup-manifest.js` bucket requirement, unrelated to this change).

**Deploy evidence**

- `firebase deploy --only functions:registerDeviceChallenge,functions:registerOrRotateDevice,functions:ingestPartnerEvents --project myexodus-53804` — all three
  "Successful create operation" (Node.js 22, 2nd gen, us-central1); `firebase functions:list`
  confirms them live.
- `firebase deploy --only firestore:rules --project myexodus-53804` — rules compiled and
  released, including the partner collections and the recipient-only `partnerMailboxes`
  read.

**Migration status**

None. No client writes or reads these collections yet; the rules deny clients in both
directions except the recipient's own encrypted mailbox read. No indexes were deployed:
every server query is single-field (`members array-contains`, `ownerUid ==`) and secondary
predicates are filtered in code, so no composite index is required.

**Deviations**

1. **§8.3.1 reauthentication is skipped for anonymous accounts.** `auth_time` cannot be
   within five minutes for an anonymous Firebase account without minting a new uid, which
   would orphan every pair (ADR #125). The device proof-of-possession signature and the
   scoped one-time challenge carry the same binding; recorded here as a deliberate,
   owner-approved carry-over of the existing identity model.
2. **The 2018 legacy attestation root is not trusted.** It is not in the current official
   Google root set. The attestation suite exercises the two current roots; Phase 8 must
   confirm the physical X6532 chain validates under them (carry below).
3. **Mailbox document id is `{eventId}__{recipientInstallationId}`,** not `{eventId}` as
   §10.10 sketched. A recipient account with two enrolled installations would otherwise
   collide on one document and silently drop one ciphertext; the schema requires one
   record per recipient installation (1:1 with the notification job). No data exists to
   migrate.
4. **A revoked partnership returns `RECIPIENT_KEY_STALE`, not `PERMANENT_REJECTED`.**
   §8.4 lists consent-revoked under permanent rejection, but `allowsCiphertextRegeneration`
   permits regeneration only for `RECIPIENT_KEY_STALE`; a permanent rejection would strand
   the event with no legal path. A revoked pairing is treated as a stale registry view and
   the response carries the remaining authorized keys.
5. **Above 20 recipients the transaction writes a `partnerFanoutTasks` record** instead of
   mailbox/job pairs (Firestore's 500-write transaction cap). Materializing that task is
   Phase 5 work; until then a >20-recipient event is durably accepted but not delivered.
   The 200-recipient protocol cap is enforced before the write.
6. **The partner-visible hardware-downgrade notice and the logout/unpair publish
   revocation are not in this phase.** The server refuses an unacknowledged downgrade and
   already denies delivery through any revoked pairing; the notice UI and the lifecycle
   callables are Phase 5/Phase 7.
7. **Contact is updated at most once per accepted batch, after the event transactions.**
   §8.4 requires once-per-batch; a failed contact write is logged and cannot roll back an
   accepted event (contact is advisory).

**Carries**

1. Play Integrity quota headroom — still not measured (needs a GCP quota query); not a
   gate because App Check enforcement already runs in production. Phase 8.
2. Physical X6532 attestation chain validation under the two current roots — Phase 8
   device leg.
3. Event ID/sequence atomic allocation — Phase 3 outbox transaction.
4. Fanout task materialization — Phase 5.
5. Hardware-downgrade partner notice — Phase 5.
6. Logout/unpair publish revocation — Phase 7.
7. `.107` reinstall + re-pair before the Phase 8 two-device matrix.

**Next:** Phase 3 — Android service outbox and recorder.

---

## Phase 3 — Android service outbox and recorder (2026-11-11)

**Status: PASSED.** The owner-bound outbox, the background AES source seal, the existing-E2EE
recipient adapter and the capture seam are implemented and tested. Producers do not call the
recorder yet — the journal-to-recorder switch is Phase 7.

**Scope:** plan §7 Phase 3 (verbatim: owner-bound service outbox DAO/repository,
background-safe AES wrapping retained through server acknowledgement and stale-recipient-key
regeneration, existing E2EE adapter, event ID/sequence transaction, payload limits and
capture seams; no UI unlock or UI database injection required), §6.1 steps 1–3, §8.1,
§8.2 event limits.

**Touched files**

- `app/src/main/java/.../accountability/delivery/ServiceOutboxEntities.kt` (new) —
  `InstallationState`, `PendingPartnerEvent`, `ServiceOutboxHealth` matching the §8.1
  column set exactly.
- `app/src/main/java/.../accountability/delivery/ServiceEventOutboxDao.kt` (new) — Room
  DAO; every multi-statement transition runs inside `ServiceEventOutbox`'s transaction.
- `app/src/main/java/.../accountability/delivery/ServiceEventOutbox.kt` (new) —
  repository: `ensureInstallation`, one-transaction `enqueue` (sequence bump + insert +
  capacity check), `applyActiveOwner`, `dueRows`, `acknowledge` (ACCEPTED /
  ALREADY_ACCEPTED only), guarded `applyRegeneratedEnvelopes`, bounded `cleanupAcked`,
  `health`.
- `app/src/main/java/.../accountability/delivery/ServicePayloadProtector.kt` (new) —
  `ServicePayloadProtector` seam + `KeystoreServicePayloadProtector` (alias
  `myexodus.service.payload.aes.v1`, AES-256-GCM, cipher-generated IV, not auth-bound).
- `app/src/main/java/.../accountability/delivery/ExistingE2eeRecipientEncryptor.kt` (new)
  — seals one payload per enrolled pair with the shipped `EventCipher`; fixed envelope AAD
  (`partner_event|v1|eventId|installationId|ownerUid|kind|recipientKeyId`); key ID =
  `base64url(SHA-256(SPKI DER))`; `recipientKeyId|version|encoding|payload` line codec;
  `OMITTED_DETAIL_MARKER`.
- `app/src/main/java/.../accountability/delivery/PartnerEventRecorder.kt` (new) — §6.1
  step 1 capture seam: validate → UUIDv4 → seal source → encrypt recipients → probe size at
  the maximum legal sequence → enqueue; stale-key regeneration path.
- `app/src/main/java/.../accountability/delivery/PartnerEventProtocol.kt` (modified) —
  `metadataIssue` is now `internal` so the recorder pre-validates before any crypto or DB
  work.
- `app/src/main/java/.../accountability/delivery/PartnerDeliveryClock.kt`,
  `PartnerDeliveryTransport.kt`, `Jcs.kt` (new in Phase 1, unchanged here).
- `app/src/main/java/.../accountability/state/AccountabilityDatabase.kt` (modified) —
  schema v15, `MIGRATION_14_15`, `MIGRATION_15_14`, both registered in `ALL_MIGRATIONS`.
- `app/src/test/java/.../accountability/delivery/PartnerEventRecorderTest.kt` (new — 15
  tests).
- `app/src/test/java/.../accountability/state/AccountabilityDatabaseMigrationTest.kt`
  (modified — two new tests; 17 total).

**Requirements verified**

| Requirement | How |
|---|---|
| Owner-bound outbox DAO/repository | `installation_state` has a unique `owner_uid` index; a second account gets a new installation ID and its own sequence space (`sequenceIsPerOwner_andASecondAccountGetsItsOwnInstallation`); rows carry `owner_uid` and suspension/resume is per active owner (`ownerSwitch_suspendsTheOtherAccountsRows_andResumesOnlyTheActiveOwners`) |
| Background-safe AES wrapping retained through ACK | Keystore AES-256-GCM key is not auth-bound and is independent of App Lock; source seal retained on every unacknowledged row; erased only for ACCEPTED / ALREADY_ACCEPTED (`acknowledgement_isLimitedToAcceptedOutcomes_andErasesEveryCiphertext`); seal failure records `LOCAL_SEAL_FAILED` and still delivers (`sealFailure_degradesRegeneration_butNeverDelivery`) |
| Stale-recipient-key regeneration | `regenerateRecipientEnvelopes` re-encrypts the retained source, keeps event ID, sequence and occurrence time; the DAO guards on `status != 'ACKED' AND wire_outcome IN ('NOT_SENT','RECIPIENT_KEY_STALE')` and zero rows updated means `NotPermitted` (`staleKeyRegeneration_replacesCiphertextForTheNewRecipient_andKeepsTheIdentity`, `regeneration_isClosedOnceAcceptanceWasPossible`) |
| Seal AAD schema binding | AAD is (`eventId`, `installationId`, `ownerUid`, `kind`, `schemaVersion`) per §8.1 line 219; regeneration opens with the row's stored `schema_version`, not the running app's version (`regeneration_opensTheSealedSourceWithTheRowsOwnSchemaVersion`) |
| Existing E2EE adapter | Envelopes decrypt through the shipped `EventCipher` + `EventCipher` AAD; envelope key ID equals `recipientKeyIdOf(SPKI)` (`record_storesAnOwnerBoundPendingRow_thatTheExistingE2eeRoundTrips`) |
| Event ID/sequence transaction | UUIDv4 event ID before any write; `bumpSequence` + row insert in one `withWriteTransaction`; a failed insert rolls the bump back |
| Payload limits | Probe event built at `9 007 199 254 740 991` (largest legal sequence, so the committed event is never larger) and validated against the 16 KiB cap; `TOO_LARGE` re-encrypts the marker and records `PAYLOAD_TOO_LARGE` while the sealed source keeps the original (`oversizedDetail_isReplacedByTheMarker_while_theSealedSourceKeepsTheOriginal`) |
| Safe metadata only | Closed-set allowlist validated before any storage (`record_rejectsDisallowedMetadata_beforeAnyStorage`) |
| No account, no row | `NoAccount` without a signed-in owner (`record_failsClosedWithoutASignedInOwner`) |
| Never submit incomplete ciphertext | Missing recipient key → `WAITING_FOR_RECIPIENT_KEY`; `dueRows` selects `PENDING` only (`record_withoutAnAuthorizedRecipientKey_waitsInsteadOfSubmittingIncompleteCiphertext`) |
| Queue capacity + incident | 10,000 rows / 50 MiB cap checked inside the enqueue transaction; `QUEUE_CAPACITY_EXCEEDED` persisted, no row overwritten, incident cleared by the next successful enqueue (`capacityExceeded_raisesAPersistentIncident_andNeverOverwritesAnUnacknowledgedRow`) |
| ACK erasure + bounded cleanup | `markAcked` erases source, envelopes and signature blob; ACKED rows deleted after seven days in batches of 200, unacknowledged rows never deleted (`cleanup_deletesOnlyAckedRowsPastTheSevenDayWindow`) |
| No UI unlock / UI DB injection | The recorder depends only on the plain-FBE `myexodus_accountability.db`, the non-auth Keystore protector, the enrollment list, and a clock — no vault open, no UI, no Compose state |

**Tests**

- `:app:testDebugUnitTest --tests "...PartnerEventRecorderTest"` — 15 tests, 0 failures,
  0 errors, 0 skipped.
- `:app:testDebugUnitTest --tests "...AccountabilityDatabaseMigrationTest"` — 17 tests,
  0 failures, 0 errors, 0 skipped (two new: v14→v15 forward against a real V12 file, and
  v15→v14 drop with pre-v15 data intact).
- `:app:compileDebugKotlin` — clean.
- Full `com.example.myexodus.accountability.*` regression suite re-run (below) — the
  Phase 0 baseline (810 tests) plus the 17 new tests stay green.

**Migration status**

Schema **v14 → v15**. `MIGRATION_14_15` creates the three tables and the three indexes
(unique `installation_state.owner_uid`; unique `(installation_id, sequence)`; the due-scan
index `(owner_uid, status, next_attempt_at_ms)`). `MIGRATION_15_14` (D-33 downgrade) drops
the three new tables and touches nothing older. Both registered in `ALL_MIGRATIONS`.

**Deviations from the plan (each deliberate)**

1. **The outbox lives in the existing plain-FBE `myexodus_accountability.db`** (three new
   tables, schema v15), not in a separate database file. The plan requires background
   readability without a UI unlock; the enrollment registry this encryptor reads already
   lives there, so a second DB would add a second open path for no benefit.
2. **Status vocabulary implemented now:** `PENDING`, `WAITING_FOR_RECIPIENT_KEY`, `ACKED`,
   `OWNER_AUTH_REQUIRED`, `PERMANENT_REJECTED`. §8.1 line 103's `LEASED` state and the
   `lease_until_ms`/`next_attempt_at_ms` semantics belong to the Phase 4 worker and are not
   written yet; `REENROLL_REQUIRED` is Phase 6/7.
3. **The recipient-envelope blob is an internal line codec**
   (`recipientKeyId|version|encoding|payload`), not JSON. All four fields are closed-set or
   base64url, so it is unambiguous and the Phase 4 signer can rebuild the envelope list
   without a parser.
4. **`dueRows` returns `PENDING` rows only.** A `WAITING_FOR_RECIPIENT_KEY` row carries no
   ciphertext; submitting it would violate §6.1's "never submit incomplete ciphertext".
   It becomes `PENDING` through regeneration or owner resume.
5. **No DI providers are wired yet.** Nothing consumes the recorder until Phase 4, and the
   producers keep using the legacy journal until Phase 7.
6. **Regeneration opens the seal with the row's stored `schema_version`** (§8.1's AAD
   definition). Binding the running app's version instead would strand sources sealed
   before a protocol bump; the accompanying test pins this.

**Carries**

1. **Terminal `PERMANENT_REJECTED` rows have no cleanup or source-erasure path yet.** The
   sentinel cleanup covers `ACKED` only, and the capacity counts every non-ACKED row.
   Phase 4 must define retention (erase the sensitive source at the terminal transition,
   then delete after a bounded window) when it writes that transition, before the Phase 8
   soak — otherwise permanently rejected rows can fill the queue and block new captures.
2. `WAITING_FOR_RECIPIENT_KEY` needs §8.1 line 311's >24 h safe diagnostic and seven-day
   operator incident — Phase 4/6 health work.
3. Lease/backoff, `LEASED`, signing-key `REENROLL_REQUIRED`, and the due-row worker —
   Phase 4.
4. Producer switch from the legacy journal to `PartnerEventRecorder` — Phase 7.
5. `.107` reinstall + re-pair before the Phase 8 two-device matrix.

**Next:** Phase 4 — background signing and worker.

## Phase 4 — Background signing and worker (2026-11-11)

**Status: PASSED (implementation and JVM tests). Device validation of screen-lock and
process-death is BLOCKED on the Phase 8 two-device matrix — not claimed here.**

**Scope:** plan §7 Phase 4 (verbatim: generate/register the background key; integrate
`SHA256withECDSA` JCS signing, WorkManager unique sync, token acquisition, leases,
partial-response reconciliation, backoff, outage recovery and key invalidation states),
§4.1/§4.3/§6.1 step 4, §8.1 retention lines, §8.2 signed envelope, §8.3 registration and
rotation, §8.5 WorkManager and triggers, §9 whole-request failures, and Phase 3 carry #1
(terminal `PERMANENT_REJECTED` retention).

**Touched files**

- `app/src/main/java/.../accountability/delivery/PartnerEventKeyStore.kt` (new) — Android
  Keystore P-256 (`SHA256withECDSA`) per installation; StrongBox on API 28+ with a
  `StrongBoxUnavailableException` fallback to TEE; `setAttestationChallenge` at keygen;
  `keyId = base64url(SHA-256(SPKI DER))`; chain read is challenge-checked.
- `app/src/main/java/.../accountability/delivery/PartnerEventRegistrar.kt` (new) — the §8.3
  registration half: `registerDeviceChallenge({installationId,intent})`, JCS message
  (`v`, `ownerUid`, `installationId`, `keyId`, `challenge`) signed with the fresh key,
  `registerOrRotateDevice` wire (standard-base64 SPKI, base64url signature, optional chain +
  challenge, debug-only `allowUnattested`), challenge-size/expiry checks, server-`keyId`
  equality before any commit, and `ROTATION_REQUIRED` / `ROTATION_NOT_ENROLLED` retried once
  with the other intent.
- `app/src/main/java/.../accountability/delivery/PartnerDeliveryTransport.kt` (modified) and
  `FirebasePartnerDeliveryTransport.kt` (new) — one `ingestPartnerEvents` callable round trip;
  Auth and App Check ride the callable protocol via `AppCheckInstaller.install`; a
  `RESOURCE_EXHAUSTED` whole-request failure becomes `DeliveryRateLimitedException`.
- `app/src/main/java/.../accountability/delivery/PartnerEventDrain.kt` (new) — mutexed
  claim → sign → submit → reconcile loop: batch limits, per-index result handling, lease
  release, `PERMANENT_REJECTED` source erasure, `RECIPIENT_KEY_STALE` regeneration for the
  server's authorized key set only, `RETRYABLE`/unknown/missing-index backoff, exponential
  delay (30 s base, 30 min cap, ≤25% jitter), fixed 300 s defer for rate limits, waiting-row
  re-probe, and cleanup of ACKED and rejected shells.
- `app/src/main/java/.../accountability/delivery/PartnerDeliveryCoordinator.kt` (new) —
  recorder commit kick (wake marker bound), in-process foreground drain carrying the App Lock
  verdict, and startup reconciliation of an armed-but-unscheduled marker.
- `app/src/main/java/.../accountability/delivery/PartnerEventSyncWorker.kt` (new) —
  `@HiltWorker`; immediate unique work (`APPEND_OR_REPLACE`), periodic 15-minute backstop
  (`UPDATE`), and a timed retry slot (`REPLACE`) carrying the drain's exact earliest due
  time; `NetworkType.CONNECTED`, WorkManager `EXPONENTIAL` 30 s backoff.
- `app/src/main/java/.../accountability/delivery/PartnerEventBootReceiver.kt` (new) — handles
  only `ACTION_BOOT_COMPLETED`, only after `UserManager.isUserUnlocked`, only with a signed-in
  account; schedules the backstop and one immediate pass.
- `app/src/main/java/.../di/PartnerDeliveryModule.kt` (new) — binds the outbox, protector,
  E2EE adapter, clock, transport, registrar, recorder, drain and coordinator; supplies the
  recipient SPKI and the `auth_time` verdict; `allowUnattested = BuildConfig.DEBUG`.
- `app/src/main/java/.../accountability/delivery/ServiceOutboxEntities.kt` (modified) —
  `installation_state.recipient_key_id`, `pending_partner_events.signing_signature` and
  `terminal_at_ms`, `service_outbox_wake_state`, `REJECTED_RETENTION_MILLIS`.
- `app/src/main/java/.../accountability/delivery/ServiceEventOutboxDao.kt` (modified) —
  registration update, guarded lease claim, due scan, lease release, retry scheduling,
  signature recording, re-enroll suspension/resume, waiting re-probe, ACK and terminal
  transitions, both cleanup queries, and the wake marker.
- `app/src/main/java/.../accountability/delivery/ServiceEventOutbox.kt` (modified) —
  repository wrappers for every transition above, `requestWake`/`clearWake`, terminal
  `permanentReject`, `cleanupRejected`.
- `app/src/main/java/.../accountability/delivery/PartnerEventProtocol.kt`,
  `PartnerEventRecorder.kt`, `ExistingE2eeRecipientEncryptor.kt` (modified) — wire map and
  result parsing, envelope decode, regeneration outcomes used by the drain.
- `app/src/main/java/.../accountability/state/AccountabilityDatabase.kt` (modified) — schema
  **v17**; `MIGRATION_15_16`, `MIGRATION_16_15`, `MIGRATION_16_17`, `MIGRATION_17_16`,
  all in `ALL_MIGRATIONS`.
- `app/src/main/java/.../MyExodusApp.kt` (modified) — startup periodic enqueue plus
  `reconcileOnStart()`; every foreground transition drains in-process with
  `appLockCoordinator.state.allowsVaultAccess`.
- `app/src/main/AndroidManifest.xml` (modified) — `RECEIVE_BOOT_COMPLETED` and the exported
  `PartnerEventBootReceiver` filtered to the system `BOOT_COMPLETED` action.
- `app/src/test/java/.../accountability/delivery/PartnerEventDrainTest.kt` (new — 15 tests).
- `app/src/test/java/.../accountability/delivery/PartnerEventRegistrarTest.kt` (new — 8 tests).
- `app/src/test/java/.../accountability/state/AccountabilityDatabaseMigrationTest.kt`
  (modified — two new tests; 19 total).

**Requirements verified**

| Requirement | How |
|---|---|
| Background key generation/registration | Registrar emits the exact documented challenge and register wires, signs the exact JCS message, and commits only on `keyId` equality (`enroll_signsTheDocumentedJcsMessage_andSendsTheExactWire`); chain-presented calls carry the matching `attestationChallenge` and never the opt-in (`attestedChain_isPresentedWithTheMatchingChallenge_andNeverTheOptIn`); a chainless release key stops before any register call (`missingChainWithoutTheDebugOptIn_isAttestationRequired_beforeAnyRegisterCall`) |
| `SHA256withECDSA` over JCS | The drain signs `PartnerEventProtocol.canonicalBytes(event)` with the row's recorded schema version; the signature is persisted and reused only when the canonical bytes and key still match (`acceptedBatch...`, `partialResponse...`, `transportFailure...`) |
| WorkManager unique sync | Three unique names: immediate `APPEND_OR_REPLACE`, periodic `UPDATE`, timed retry `REPLACE`; the persistent wake marker allows exactly one append per armed marker (`requestWake` true→true-false→rearm, pinned in the v15→v16 migration test and the coordinator contract) |
| Token acquisition | Firebase callable protocol supplies the ID token and App Check attestation automatically; no hand-written header parser (§8.4) |
| Leases | `claimDue` leases with a 120 s bound; a lease left by a killed process is reclaimed after expiry and redelivered (`leaseLeftByAKilledProcess_isReclaimedAfterExpiry`) |
| Partial-response reconciliation | Accepted index ACKed; an index with no result stays `PENDING`, records `RESULT_MISSING`/`WIRE_UNKNOWN`, and resends the identical JCS bytes and signature (`partialResponse_defersOnlyTheMissingIndex_asUnknown_andResendsTheExactBytes`) |
| Backoff | 30 s base doubling to the 30 min cap with jitter (`transportFailure_backsOffExponentially_andKeepsTheSignature`); `RESOURCE_EXHAUSTED` uses the fixed 300 s defer because callables expose no `Retry-After` (`quotaRejection_usesTheFixedLongDefer_withNoRetryAfterOnCallables`) |
| Outage recovery | A thrown whole-request failure schedules every row and keeps the exact signed bytes; an ambiguous outcome is never treated as acceptance (`transportFailure...`); waiting rows re-probe once per drain and are never submitted without ciphertext (`waitingRow_isReprobedOnceAnEnrollmentExists_thenDelivered`, `staleKey_withNoLocalEnrollment_parksTheRowWaiting`) |
| Key invalidation states | `RECIPIENT_KEY_STALE` re-encrypts only for the server's authorized keys and makes the row immediately due (`staleKey_regeneratesForTheAuthorizedKey_andIsDueAgainImmediately`); an EMPTY authorized set is passed through as an empty set, never `null` ("every local enrollment"), so the row parks `WAITING_FOR_RECIPIENT_KEY` instead of re-encrypting for revoked keys (`staleKey_withEmptyAuthorizedSet_parksWaiting_andNeverReencryptsForALocalEnrollment`); `REENROLL_REQUIRED` and a missing/mismatched signing key suspend the installation and keep every row, then a foreground-registered drain resumes (`reenrollRequired_suspendsTheInstallation_thenARegisteredDrainResumesEveryRow`, `missingSigningKey_waitsForAForegroundRegistration_andThenDelivers`) |
| Registration intent preconditions | `ROTATION_REQUIRED` and `ROTATION_NOT_ENROLLED` are each retried once with the other intent, with a challenge for that intent and a fresh key (`rotationPrecondition_isRetriedWithTheOtherIntent`, `rotationNotEnrolled_isRetriedAsEnroll_whenLocalStateClaimedActive`) |
| §8.3.1 foreground gate | The worker drains with `allowRegistration = false`; only the foreground pass carries the App Lock verdict, and a stale/absent auth makes no network call (`staleAuth_makesNoNetworkCall`) |
| §8.1 terminal retention (Phase 3 carry #1) | The terminal UPDATE erases source, envelopes, signed envelope and signature in the same write and records `terminal_at_ms`; the empty shell is deleted only after seven days in bounded batches (`serverPermanentRejection_erasesTheSource_keepsTheShell_andNeverResubmits`, `terminalShell_isDeletedOnlyAfterTheSevenDayWindow`) |
| Unreadable rows | An unsupported kind is `PERMANENT_REJECTED` without signing or sending anything (`unreadableRow_isPermanentlyRejected_withoutSigningAnything`) |
| Signed-out no-op | No owner means no claim, no signing, no network (`noSignedInOwner_isANoOp`) |
| §8.5 BFU/trigger rules | Receiver acts only on the system boot action and only after first unlock; no network-change manifest receiver was added; the periodic worker is a best-effort backstop |
| Migration | v15→v16 and v16→v17 forward paths run through Room's real migration and post-migration validation; rollback steps are registered (D-33) |

**Tests**

- `:app:testDebugUnitTest --tests "...PartnerEventDrainTest"` — 15 tests, 0 failures,
  0 errors, 0 skipped.
- `:app:testDebugUnitTest --tests "...PartnerEventRegistrarTest"` — 8 tests, 0 failures,
  0 errors, 0 skipped.
- `:app:testDebugUnitTest --tests "...AccountabilityDatabaseMigrationTest"` — 19 tests,
  0 failures, 0 errors, 0 skipped (two new: v15→v16 background-signing state and v16→v17
  terminal timestamp, both against real v12-origin files).
- `:app:compileDebugKotlin` / `:app:compileDebugUnitTestKotlin` — clean.

**Migration status**

Schema **v15 → v16 → v17**. v16 is additive: `installation_state.recipient_key_id`,
`pending_partner_events.signing_signature`, and the singleton
`service_outbox_wake_state(id, wake_requested)` table. v17 adds
`pending_partner_events.terminal_at_ms`. Both forward and both rollback steps are registered
in `ALL_MIGRATIONS`, and `allMigrations_coverEveryVersionStepToTheDeclaredSchemaVersion`
still passes for every step to v17.

**Deviations from the plan (each deliberate)**

1. **`auth_time` freshness is a caller-supplied verdict, and anonymous sessions skip it**
   (`user.isAnonymous ||` in `PartnerDeliveryModule.currentAuth`). Anonymous accounts have no
   reauthentication concept, and both physical test devices run anonymous sessions (ADR #125).
   Non-anonymous accounts still require `auth_time` within 300 s. The actual reauthentication
   UX for a hardware-assured account remains the §8.3.1 foreground work.
2. **No `Retry-After` on callables.** A whole-request `RESOURCE_EXHAUSTED` uses a fixed 300 s
   defer (`DrainLimits.rateLimitedDeferMs`); this is the one translation the transport
   performs, and it is marked in the drain's header comment.
3. **The capture kick uses a persistent singleton wake marker plus `APPEND_OR_REPLACE`**, not
   a bare append per event. The marker admits exactly one follow-up behind a running worker
   and the startup reconciliation clears a marker orphaned by a crash between the DB write and
   the WorkManager enqueue. This is §8.5's "bounded coalesced follow-up", made durable.
4. **Background drains never register a key.** Enrollment/rotation is foreground-only by
   §8.3.1; the worker passes `allowRegistration = false` and reports
   `registrationRequired` instead of signing with a suspended installation.
5. **Terminal rejection uses its own `terminal_at_ms` column** rather than reusing
   `acked_server_at_ms`. A row rejected long after capture would otherwise have zero shell
   retention (the timestamp would predate the rejection by more than seven days); the new
   column bounds retention from the actual transition.
6. **The cleanup of ACKED and rejected shells runs at the top of every drain**, not in a
   separate maintenance job. It is a bounded 200-row delete behind a `runCatching`, so it can
   never block a delivery pass.
7. **Registration `allowUnattested` is enabled only in debug builds**
   (`BuildConfig.DEBUG`). A release build with no attestation chain stops at
   `AttestationRequired`; unattested release enrollment needs the explicit §8.3.6 path, not
   an automatic downgrade.

**Post-gate review fix (2026-11-12)**

The post-implementation review found one fail-open path in the `RECIPIENT_KEY_STALE`
handler: `.takeIf { it.isNotEmpty() }` turned an empty server authorized-key set into
`null`, which `ExistingE2eeRecipientEncryptor` reads as "every local enrollment" — it
would have re-encrypted for a locally-known, possibly revoked recipient key. The drain
now passes the empty set through unchanged, so the regeneration filters to zero recipients
(`RecipientKeyUnavailable`), the null-envelope write parks the row `WAITING_FOR_RECIPIENT_KEY`
(clearing the stale signature and digest), and the row defers 900 s. Regression test
`staleKey_withEmptyAuthorizedSet_parksWaiting_andNeverReencryptsForALocalEnrollment` pins it.

**Carries**

1. **Device validation of screen-lock, process-death, Doze and real attestation chains is
   BLOCKED** on the Phase 8 two-device matrix. This entry claims JVM/unit evidence only.
2. `WAITING_FOR_RECIPIENT_KEY` still lacks §8.1's >24 h safe diagnostic and seven-day
   operator incident — Phase 6 health work.
3. Producer switch from the legacy journal to `PartnerEventRecorder` — Phase 7. Until then
   the new drain has no production producers except the existing capture seams.
4. `.107` reinstall + re-pair before the Phase 8 matrix.

**Next:** Phase 5 — partner delivery.

---

## Phase 5 — Partner delivery (2026-11-12)

**Status: PASSED (client + backend).** The recipient half ships and is deployed: mailbox
retrieval runs on every sync pass whether or not any push arrived, the notifier suite is
live in production, and the two device-facing registrations the notifier needs now exist.
Client-side projection into the partner UI is the Phase 7 seam (`onDelivered` no-op).

**Scope:** plan §7 Phase 5 (verbatim: wire existing partner mailbox, notification outbox,
Firestore trigger and sweeper, 60-second coalescing for noisy noncritical recipient pushes,
privacy-safe FCM messages, recipient deduplication and registration maintenance; implement
notification permission handling and mailbox retrieval independent of FCM), §8.6.

**Touched files**

- `app/src/main/java/com/example/myexodus/accountability/delivery/PartnerMailboxEntities.kt`
  (new) — `partner_mailbox_delivery` (composite PK `recipient_installation_id` +
  `mailbox_event_id`, status `DELIVERED`/`UNDECRYPTABLE`/`SKIPPED`) and
  `partner_mailbox_state` (`installation_id` PK, `watermark_created_at_ms`). No plaintext
  is stored (I07).
- `app/src/main/java/com/example/myexodus/accountability/delivery/PartnerMailboxDao.kt` (new)
  — receipt lookup, IGNORE-insert delivery row, watermark read/replace.
- `app/src/main/java/com/example/myexodus/accountability/delivery/PartnerMailboxReceiver.kt`
  (new) — FCM-independent `pull()`: query `createdAt >=` watermark ascending, receipt-before-
  watermark-advance, duplicates suppressed by `(installationId, messageId)`, missing/foreign/
  unknown documents terminal SKIPPED, unopenable envelopes terminal UNDECRYPTABLE (AC07),
  `onDelivered` exceptions propagate so the document is retried.
- `app/src/main/java/com/example/myexodus/accountability/delivery/PartnerMailboxDecryptor.kt`
  (new) — mirror of `ExistingE2eeRecipientEncryptor`: pair CEK from the enrollment's agreement
  key, AAD inputs taken from the mailbox document itself (sender installation/owner, kind,
  recipient key id).
- `app/src/main/java/com/example/myexodus/accountability/state/AccountabilityDatabase.kt`
  (modified) — schema v18 with `MIGRATION_17_18` / `MIGRATION_18_17` in `ALL_MIGRATIONS`.
- `app/src/main/java/com/example/myexodus/accountability/sync/FirestoreSchema.kt` (modified) —
  `partnerMailboxes` collection constant, `partnerMailboxDocsAfter` (the §8.6 mailbox query;
  composite index below), `partnerMailboxDocumentOf` (backend field names `ownerUid` /
  `installationId` map to the AAD sender inputs), `pushInstallationData` gains required
  `registrationKind`.
- `app/src/main/java/com/example/myexodus/accountability/sync/PushInstallationRegistrar.kt`
  (modified) — writes `registrationKind = TOKEN` and mirrors the same token+data under the
  owner-bound SERVICE installation id (`outbox.ensureInstallation`), which is the id
  `partnerRecipientKeys` registers and `partnerNotificationOutbox` jobs dispatch to. The
  mirror is secondary: resolve/write failures log and never fail the registration pass.
- `app/src/main/java/com/example/myexodus/accountability/sync/PartnerSyncCoordinator.kt`
  (modified) — `pullPartnerEvents` seam invoked once per account pass (after check-in,
  enrolled-only), `SyncReport.recipientEventsPulled`.
- `app/src/main/java/com/example/myexodus/accountability/sync/PartnerPushParser.kt` (modified)
  — `PARTNER_EVENT` wake (`{v:'1', type:'PARTNER_EVENT', eventId}` from
  `functions/partner-notify.js`) routes to `Sync(null)`; the mailbox pull reconciles by
  receipt watermark, so no expedited path is needed.
- `app/src/main/java/com/example/myexodus/di/PartnerDeliveryModule.kt` (modified) — decryptor
  and receiver providers; coordinator wiring in `AccountabilityModule.kt`.
- `firestore.rules` (modified) — `push_installations` `hasOnly` gains `registrationKind`,
  constrained to `FID`/`TOKEN`.
- `firestore.indexes.json` (modified) — `messages` composite index
  (`recipientInstallationId` ASC, `createdAt` ASC), COLLECTION scope.
- `functions/partner-notify.js`, `functions/partner-notify-logic.js` (new) — prompt
  trigger + every-minute sweeper: transaction claim with lease expiry, 60 s rolling window
  for `BLOCK_ATTEMPT` (server-owned group id shared with ingest via
  `partner-notify-logic.blockGroupId`), fanout materialization, `SENT_TO_FCM` with server
  time, invalid registration prunes the registration and never touches the mailbox,
  non-`TOKEN` kinds are never handed to the token-addressing API.
- `functions/partner-ingest.js` (modified) — mailbox documents now carry the §8.2 AAD inputs
  (`installationId`, `kind`, `recipientInstallationId`); shared `blockGroupId`.
- `functions/index.js` (modified) — exports `partnerNotifyTrigger`, `partnerNotifySweep`.
- Tests: `PartnerMailboxReceiverTest` (new — 9), `PartnerMailboxDecryptorTest` (new — 7),
  `AccountabilityDatabaseMigrationTest` (v17→v18), `PartnerSyncCoordinatorTest` (3 seam
  tests + per-pass assertion), `PushInstallationRegistrarTest` (5 mirror tests, key set),
  `FirestoreSchemaTest`, `PartnerPushParserTest`, `PushInstallationInstrumentedTest`,
  `functions/emulator-test/firestore-rules.emulator.js` (registrationKind).

**Requirements verified (§8.6)**

| Requirement | How |
|---|---|
| Mailbox retrieval independent of FCM | `pullPartnerEvents` runs from the periodic/foreground pass unconditionally in the enrolled branch; push is only a wake |
| One registration repository, per-account records | `PushInstallationRegistrar` (uid-scoped documents) + `PushMapping.unregister` on logout (shipped earlier) |
| Registration kind explicit; matching addressing | `registrationKind='TOKEN'` written; notifier refuses non-TOKEN rows before any send |
| Generic/privacy-safe push content | Data-only wake `{v,type,eventId}`; no payload, activity, or label leaves the server |
| POST_NOTIFICATIONS on 13+, denied → keep mailbox, show on open | Permission UX shipped earlier; the pull is the display path, projection lands Phase 7 |
| BLOCK_ATTEMPT coalescing, bypass, job id, claim/lease, SENT_TO_FCM | Backend Phase 2 + notifier logic; unit-tested in `functions/test/partner-notify-logic.test.js` |
| Client dedupe by mailbox id | `partner_mailbox_delivery` receipt checked before any side effect; duplicate re-read at the `>=` watermark counted, not reprocessed |
| Invalid registration pruned, mailbox retained | `reconcileOutcome` → `pruneRegistration: true`; mailbox documents are never deleted by any outcome |

**Deploy evidence (production, 2026-11-12)**

`firebase deploy --only firestore:indexes,firestore:rules,functions:partnerNotifyTrigger,functions:partnerNotifySweep,functions:ingestPartnerEvents --project myexodus-53804`
→ rules released; indexes deployed (composite `messages` index); `partnerNotifyTrigger` and
`partnerNotifySweep` created; `ingestPartnerEvents` updated. All succeeded.

**Tests**

- JVM: 133 across the 7 touched classes — PASSED.
- Backend: `npm test` — 242/242 PASSED.
- Rules emulator: `npm run test:rules` — 18/18 PASSED (incl. the new `registrationKind` test).
- Migration: v17→v18 via the real chain in `AccountabilityDatabaseMigrationTest` — PASSED.

**Deviations from the plan (each deliberate)**

1. **Only `TOKEN` registrations are written by this client.** FID-addressing send APIs in
   this SDK generation are `AndroidConfigV2` (pre-GA/EAP), which §8.6's first bullet forbids
   adding. The column exists in the rules vocabulary for future transports; the notifier
   refuses anything but `TOKEN` rather than guessing.
2. **The registrar mirrors the token under the service installation id** in addition to the
   FID-keyed SOS row. Without the mirror the notifier (which resolves
   `users/{uid}/push_installations/{recipientInstallationId}` using the service id) could
   never find a token — the delivery blocker this phase fixes. The mirror is best-effort;
   mailbox pull converges regardless.
3. **`PARTNER_EVENT` wakes perform a full sync pass** (`Sync(null)`) instead of a targeted
   fetch. The mailbox pull is watermark-reconciled and deduped, so a targeted path would be
   duplicate machinery for no gain.
4. **`UNDECRYPTABLE` is a terminal receipt** (AC07) rather than an unbounded retry: a
   shredded pair key or a foreign envelope cannot become readable on this device, and
   retrying would stall every later document behind the watermark.

**Carries**

1. **Display-on-open / recipient projection** — `onDelivered` is a no-op until Phase 7
   switches producers to `PartnerEventRecorder` and projects delivered events into partner
   UI state.
2. **Hardware push-leg validation** (real FCM delivery, Doze, denied permission) — Phase 8
   two-device matrix.
3. Phase 4 carries 2–4 remain open (>24 h diagnostics, producer switch, `.107` re-pair).

**Next:** Phase 6 — liveness integration.
