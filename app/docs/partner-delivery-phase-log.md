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

---

## Phase 6 — Liveness integration (2026-11-12)

**Status: PASSED (client + backend), deployed with the rollout flag OFF.** The separate
signed, latest-wins contact path ships end to end: device signing/contact scheduling, the
`submitDeviceContact` callable, the five-minute evaluator, and permission-aware notices on
the existing outbox/dispatcher. Nothing changes for users until
`config/features.device_liveness_alerts_v1_enabled` is set — the legacy DMS worker remains
in charge while the flag is unset, and the liveness worker stays inert.

**Scope:** plan §7 Phase 6 (verbatim: use the existing heartbeat plumbing "where present;
implement a **separate stateless/latest-wins signed heartbeat** and required-enforcement
health state, server 5-minute evaluator, 90-minute probe threshold, 20-minute probe window,
conservative 240-minute alert grace (360 in configured sleep windows), outage episodes,
automated incident suppression/recovery, permission-aware partner alerts and dashboard
diagnostics. No second parallel heartbeat system"), §6.3 and §8.7.

**Touched files**

Backend (new):
- `functions/device-liveness-logic.js` — pure decisions: `validateContact` +
  `contactMessage` (JCS-signed shape), `evaluationDecision` (90-min stale → episode +
  one probe; 20-min window; 240-min grace constant, 360-min sleep constant; 6-h probe rate
  cap), statuses `ACTIVE`/`CONTACT_STALE`/`UNAVAILABLE`/`MONITORING_UNAVAILABLE`,
  enforcement `HEALTHY`/`DEGRADED`, notice types, `livenessDocId`.
- `functions/device-contact.js` — `submitDeviceContact` callable (Auth + App Check, P-256
  signature over the canonical contact, transactional strictly-increasing counter accept,
  probe echo, enforcement transition, legacy DMS bridge) and `deviceLivenessWorker`
  (five-minute evaluator: stale detection, episode/probe allocation, bounded wake probe,
  grace alert, atomic recovery, fleet-incident suppression).
Backend (modified):
- `functions/index.js` — exports `submitDeviceContact`, `deviceLivenessWorker` and the
  emulator seams.
- `functions/dms-logic.js` — liveness notice types share the one outbox;
  `suppressionReason` routes liveness rows to `partnerDeviceLiveness` and suppresses
  `MONITORING_PAUSED`/`RECOVERED_BEFORE_DISPATCH`/`SUPERSEDED`.
- `functions/dms-processor.js` — dispatcher selects the state collection by notice family,
  family-scoped collapse keys, enforcement notice copy, shared `dispatchOutbox`/
  `requeueExpiredClaims`/`sendDmsPush` exports, and the legacy worker stands down entirely
  when the v1 flag is on (two schedulers must never dispatch the same outbox).
- `functions/partner-registration.js` — exports `p256Spki` for contact signature checks.
- `functions/package.json` — `test:liveness` script.

Client (new):
- `app/src/main/java/com/example/myexodus/accountability/delivery/DeviceContactProtocol.kt`
  — v1 wire types; canonical JCS bytes; `submitPayload` = flat contact + `contactSignature`
  (base64url, 64–128 chars); reply mapping (`ACCEPTED` / `STALE`+counter /
  `INVALID_PROBE`); probe regex mirror; `MAX_COUNTER = 9_007_199_254_740_991`.
- `.../delivery/DeviceContactClient.kt` — the §6.3 sender: owner → installation →
  active registration + key-id match → health snapshot → counter allocation → sign →
  `submitDeviceContact`. Every pre-send miss is `Skipped`; a STALE reply aligns the local
  counter forward; nothing is persisted for replay.
- `.../delivery/DeviceContactWorker.kt` — one fresh contact per run; probe input validated
  against the server regex before sending; bounded retry (`MAX_RETRIES = 2`) only for
  send failures/STALE; 15-minute periodic (`UPDATE`, CONNECTED), immediate now (`KEEP`),
  probe request expedited only on API 31+.
Client (modified):
- `.../delivery/ServiceEventOutboxDao.kt` / `ServiceEventOutbox.kt` — `heartbeatCounter`
  read/write plus transactional `allocateHeartbeatCounter` (cap-checked) and
  `alignHeartbeatCounter` (only raises). No schema change: `installation_state.heartbeat_counter`
  already existed at v15; the database stays at v18.
- `.../sync/PartnerPushParser.kt` — `LivenessProbe(probeId?)`; a malformed probe id is
  ignored, never repaired; an id-less legacy probe still routes.
- `.../sync/PartnerMessagingService.kt` — a signed probe posts the silent user-visible
  status entry and enqueues the signed contact; the id-less legacy probe keeps the forced
  heartbeat path until Phase 7.
- `.../sync/PartnerNotifications.kt` + `res/values/strings.xml` — LOW-importance
  `device_status_v1` channel, id 2006, generic "My Exodus is checking its connection" copy.
- `di/PartnerDeliveryModule.kt` — `DeviceContactClient` provider: capability truth from
  `BlockerAccessibilityService.connected`, `DnsSinkholeVpnService.state`, `false` for
  device admin (none implemented), `NotificationAccess.canPost`; required mask from
  `getActiveRules()` filtered `isEnabled && !isExpiredAt` (ACCESSIBILITY) and
  `hasLiveWebsiteRule` (VPN); any unreadable store returns a null snapshot so the contact
  is skipped rather than fabricated.
- `MyExodusApp.kt` — cadence + immediate contact on process start, a contact on every
  foreground transition, and a process-lifetime connectivity-recovery callback
  (`NET_CAPABILITY_INTERNET`) sending a contact when the first network returns; all
  session-gated.
- `.../delivery/PartnerEventBootReceiver.kt` — permitted restart re-arms the cadence and
  sends immediately.

**Tests**

- JVM: full `:app:testDebugUnitTest` — 2774 tests, 0 failures, 0 errors, 0 skipped (292
  classes). Phase 6 additions: `DeviceContactProtocolTest` (6 — two full server-produced
  golden canonical strings, probe presence/absence, submit payload, reply mapping, counter
  ceiling, probe regex) and `PartnerPushParserTest` (11 — probe id routing and malformed-id
  rejection).
- Backend unit: `npm test` — 265/265 PASSED (23 new).
- Backend emulator: `npm run test:liveness` — 13/13 PASSED (real callable + evaluator,
  independent hand-written JCS rebuild of the signed bytes, rollout-flag handover,
  MONITORING_UNAVAILABLE pause).
- Release build: `:app:assembleRelease` — BUILD SUCCESSFUL.

**Deploy evidence (production, 2026-11-12)**

`firebase deploy` of `submitDeviceContact`, `deviceLivenessWorker` and the updated
`dmsDeadlineWorker` to `myexodus-53804` — exit 0; functions created/updated. Evaluator
remains inert while `config/features.device_liveness_alerts_v1_enabled` is unset.

**Requirements verified (§6.3 / §8.7)**

| Requirement | How |
|---|---|
| Separate stateless/latest-wins heartbeat, own counter | Own column namespace + own callable; never touches `pending_partner_events`, event ids, sequence, mailbox or notification outbox |
| Do not retain/replay offline heartbeat calls | Nothing persisted for sending; each opportunity allocates a fresh counter (a crash burns the counter, never reuses it) |
| Versioned JCS contact signed with the registered background key | `DeviceContactProtocolTest` pins the server's own canonical strings; `submitPayload` adds `contactSignature` |
| Required mask derives from the active policy | ACCESSIBILITY iff an enabled, unexpired rule exists; VPN iff `hasLiveWebsiteRule`; no device-admin surface |
| 15-minute periodic + foreground/restart/connectivity opportunities | `enqueueDeviceContact` (UPDATE) + now-contact at process start, every foreground transition, boot, and first reachable network |
| Probe: silent, user-visible status entry first | `notifyLivenessProbe` (LOW channel) then the probe work request |
| Expedited only API 31+, normal below | `Build.VERSION.SDK_INT >= S` gate in `deviceContactProbeRequest` |
| Probe answered only by signed contact with current probe id + newer counter | `probeId` echoed in the signed bytes; server validates window/id/counter transactionally |
| Unknown/undeliverable heartbeat is unavailable, never proof | Null snapshot skips the contact; silence only feeds the server's uncertainty path |
| Permission-aware probes and notices | `notificationPermissionGranted` reported; client skips the local post when denied but still answers; server uses notice/suppression state for dispatch |
| One outbox/dispatcher, no second heartbeat system | Liveness notices ride the existing DMS outbox; the legacy worker stands down when the v1 flag is on |

**Deviations from the plan (each deliberate)**

1. **Monitoring-enrollment state is not a wire field.** §6.3's contact description ends
   with "and current monitoring-enrollment state", but §8.7's protocol v1 (and the
   deployed `validateContact`) allowlist exactly `{v, ownerUid, installationId, keyId,
   heartbeatCounter, deviceReportedAtMs, enforcement{...}, probeId?}`. Enrollment and
   partner consent are checked server-side at decision and dispatch time (§8.7), so the
   client does not invent a field the server rejects.
2. **A failed contact retries at most twice** (`MAX_RETRIES = 2`, standard WorkManager
   backoff) instead of literally one call per opportunity. Each attempt allocates a fresh
   counter and re-signs, so this is not replay; the bound exists because the probe answer
   window is only 20 minutes.
3. **No sleep-window configuration exists in this build**, so the evaluator always uses the
   240-minute default grace; the 360-minute constant is defined and ready for a configured
   schedule (§8.7 defers tuning to server configuration).
4. **The legacy id-less DMS probe path stays alive** (forced heartbeat via the existing
   partner-sync worker) until Phase 7 retires the legacy worker; the rollout flag keeps
   production on the legacy path, so removing it early would go silent.
5. **Connectivity recovery is a process-lifetime `NetworkCallback`, foreground/boot are
   WorkManager enqueues** — outside a live process the CONNECTED constraint on the
   periodic request is the recovery mechanism; the callback only shortens the common
   in-process case.
6. **The contact is bridged into the legacy DMS state** (`dms_install_state`) by the
   callable so an accepted signed contact also satisfies the legacy partner-notice path
   while both systems coexist (Phase 6 backend; no client coupling).

**Carries**

1. **Device validation is BLOCKED** on the Phase 8 two-device matrix: real FCM probe →
   notification → expedited contact latency, Doze/overnight behavior, denied-permission
   probe, and end-to-end `DEVICE_UNAVAILABLE`/`DEVICE_RECONNECTED` copy. This entry claims
   JVM + backend-emulator evidence only.
2. **Rollout flag flip is an owner decision** after hardware evidence — the evaluator is
   deployed but inert.
3. **Phase 7** retires the legacy DMS probe/worker and switches producers to
   `PartnerEventRecorder`; until then both notice families share the one outbox.
4. **No dashboard diagnostics surface yet** — server state is persisted
   (`partnerDeviceLiveness`); the in-app diagnostics view remains §8.7 dashboard work.
5. **Only `device_liveness_alerts_v1_enabled` exists.** §8.7's other three rollout
   controls (`delivery_ingest_v1_enabled`, `delivery_client_sync_v1_enabled`,
   `partner_notify_v1_enabled`) belong to Phase 7's staged-flag work; none of them are
   implemented yet, so their pause semantics are unproven.
6. **App Lock + liveness interaction risk (unshipped feature).** When App Lock lands, a
   locked vault makes `AppRulesStoreFactory.open()` throw `VaultLockedException`; the
   capability provider turns that into a null snapshot and the contact is skipped. A vault
   left locked past 90 minutes leaves the probe unanswered, and past the 240-minute grace
   the partner can receive `DEVICE_UNAVAILABLE` for a device that is actually running.
   Revisit when App Lock ships (task #149): either exempt the liveness snapshot from vault
   gating or record a bounded "monitoring unavailable" state instead of silence.

**Next:** Phase 7 — legacy migration, lifecycle and rollout.

---

## Phase 7 — Legacy migration, lifecycle and rollout (2026-11-12)

### Unit 1 — the four rollout controls (§8.7)

Plan line: "Use separate rollout controls `delivery_ingest_v1_enabled`,
`delivery_client_sync_v1_enabled`, `partner_notify_v1_enabled`, and
`device_liveness_alerts_v1_enabled`. Paused ingest returns retryable maintenance; paused
client sync continues local recording; paused notifier retains mailbox/jobs; paused
liveness exposes `MONITORING_UNAVAILABLE` without fabricating tamper signals. These
independent controls are intentional safety boundaries, not unnecessary feature flags."

**Touched files**

| File | Change |
|---|---|
| `functions/partner-ingest.js` | `delivery_ingest_v1_enabled === false` → `HttpsError('unavailable', 'MAINTENANCE')` after auth, before payload validation and before the rate budget is charged. |
| `functions/partner-notify.js` | `notifierPaused()` helper; `dispatchJob` returns `'PAUSED'` before any job read; `runPartnerNotifySweep` skips dispatch after fanout materialization, so mailbox rows and jobs are still created and retained. |
| `functions/device-contact.js` | Reads `config/features` inside the submit transaction; while paused an accepted contact stores `STATUS_MONITORING_UNAVAILABLE`, otherwise `STATUS_ACTIVE`. The legacy `dms_install_state` bridge is untouched. |
| `app/src/main/java/.../accountability/sync/PartnerRolloutGate.kt` | `clientSyncEnabled()` + `DELIVERY_CLIENT_SYNC_CONFIG_KEY` (Remote Config, default ON = kill switch). |
| `app/src/main/java/.../accountability/delivery/PartnerEventDrain.kt` | `syncEnabled` constructor param (default `{ true }`); `run` claims nothing while paused. |
| `app/src/main/java/.../di/PartnerDeliveryModule.kt` | Wires `syncEnabled = { gate.clientSyncEnabled() }`. |
| `app/src/main/java/.../di/AccountabilityModule.kt` | Sets `delivery_client_sync_v1_enabled → true` in the Remote Config defaults before the first read. |

**Tests**

| Suite | Result |
|---|---|
| `functions/test/partner-notify.test.js` (new, 2 tests) | 2/2 |
| `functions` `npm test` (full pure suite) | 267/267 |
| `npm run test:partner-ingest` (emulator) | 15/15 — the new pause test asserts zero ledger/mailbox/job writes and zero rate charge while paused, then `ACCEPTED` after resume |
| `npm run test:liveness` (emulator) | 14/14 — the new pause test asserts `MONITORING_UNAVAILABLE` + `lastAcceptedContactAtMs` while paused, `ACTIVE` on the first contact after resume |
| `PartnerEventDrainTest` (new paused test) | pass — 0 claimed, 0 submitted, row stays due, the normal drain accepts it after re-enable |

**Design decisions**

1. The notifier pause check sits in `dispatchJob` (single choke point covering both the
   trigger and the sweep) plus one sweep-level skip so the sweep does not pay 50 config
   reads per minute. A paused dispatch returns `'PAUSED'`, not an error — the job stays
   `due` for the next pass after re-enable.
2. Ingest pause returns the existing maintenance error shape the client already treats as
   retryable (`FirebasePartnerDeliveryTransport` retries on `unavailable`), so no client
   change was needed. Nothing is acknowledged and nothing is dropped; the exact signed
   bytes resubmit unchanged.
3. Liveness pause is evaluated at contact time, not evaluation time: the stored status
   carries the pause, so the existing evaluator (`MONITORING_PAUSED` → `{action:'NONE'}`)
   is the second line of defence, and the first contact after re-enable restores `ACTIVE`
   with no operator write.
4. Client sync pause changes no recording path — only the drain's claim loop is gated.
   The 15-minute periodic worker remains the re-entry path after re-enable
   (`Result()` carries `earliestNextAttemptMs = null`, so no immediate retry is scheduled).
5. All four flags are kill switches: unset or `true` = enabled. Absence of the flag never
   disables a path.

### Unit 2 — dual-acceptance backend policy (§13.3)

Plan line: "Server dual-acceptance is per-installation and versioned; never allow a
migrated installation to force a legacy-signature downgrade." And §13.5: "Old APK
installations remain supported during a documented compatibility window; expose a server
metric for legacy usage."

**Touched files**

| File | Change |
|---|---|
| `functions/event-append-logic.js` | Optional `installationId` (UUID v4); allowlist is now required 5 + optional 1; new `INSTALLATION_ID_INVALID`. |
| `functions/event-append.js` | Inside the append transaction, when `installationId` names an existing `partnerDeviceSigningRegistrations/{uid}:{id}` doc (any status, incl. `REVOKED`), throw `HttpsError('failed-precondition', 'SIGNED_TRANSPORT_REQUIRED')` before any write. On `appended`, log `legacyUserEventAccepted` (hashed uid + `installationIdentified` boolean). |
| `functions/test/event-append-logic.test.js` | New pure test: optional/malformed installation ids. |
| `functions/test/relay-blindness.test.js` | Allowlist assertion now expects 6 keys. |
| `functions/emulator-test/event-append.emulator.js` | New emulator test covering five dual-acceptance cases; `partnerDeviceSigningRegistrations` cleared per test. |

**Tests**

| Suite | Result |
|---|---|
| `functions` `npm test` (full pure suite) | 268/268 |
| `npm run test:append` (emulator) | 11/11 — new test asserts: omitting `installationId` still appends; an identified migrated installation gets `SIGNED_TRANSPORT_REQUIRED` with zero writes; the same id under another uid is a different installation; `REVOKED` still blocks; a committed event replays after registration. |

**Design decisions**

1. The check runs after the replay check (a committed event stays retrievable even when
   its installation later registers) and before the profile/pair/rate gates, matching
   §8.8's "timeout-after-commit retries are safe".
2. Registration existence — not `status: ACTIVE` — blocks the legacy path. A `REVOKED`
   registration proves the installation migrated; §8's lifecycle line ("Revoked
   registrations and partnerships never regain access") forbids treating revocation as a
   downgrade lane.
3. The legacy `appendUserEvent` path still legitimately carries messages, streaks,
   sessions, attempts and plans in this release, so it cannot reject every registered
   installation wholesale. The guard catches version skew (a migrated client on the old
   path); it cannot catch a client that omits `installationId`. That is the documented
   compatibility-window limit, covered by the §13.5 metric rather than a false claim of
   enforcement.

**Deployment (§13.1 ordering — server before any client migration).** The Phase 7 server
half was deployed to production on 2026-11-12 before any client migration can be enabled:

```
firebase deploy --only functions:appendUserEvent,functions:ingestPartnerEvents,functions:partnerNotifyTrigger,functions:partnerNotifySweep,functions:submitDeviceContact,functions:deviceLivenessWorker --project myexodus-53804
```

All six updated successfully (Cloud Functions API `updateTime` = 2026-11-12T00:17-00:21Z).
No rules or index changes were part of Phase 7. The first attempt failed in the CLI's
10-second local source-analysis step (`User code failed to load … Timeout after 10000`);
a retry on an idle machine succeeded with no code change — a CLI flake, not a source
defect. The producer flag remains OFF, so no client is migrating yet; the deployed server
accepts both transports in the meantime.

### Unit 3 — staged rule-lifecycle producer (§13.3/§13.4, plan line 184)

Plan line: "Add idempotent locked-vault migration performed when foreground unlock is
already available, dual-acceptance backend policy, key recovery/rotation, restoration
detection, staged flags, telemetry, rollback and tests." §13.4: "Migrate journaled rule
rows only under normal foreground unlock, keep stable event IDs, verify counts and digests,
checkpoint progress, and resume after a crash." §13.5: "`BLOCK_ATTEMPT` and `CHECK_IN` keep
the legacy path this release; there is no producer for them."

**Touched files**

| File | Change |
|---|---|
| `app/src/main/java/.../accountability/delivery/PartnerRuleChangeProducer.kt` | New. `PartnerRuleChangeTransport` = `LEGACY`/`SIGNED`/`ACKNOWLEDGED`/`DEFERRED`; decides per journal row. The outbox event id is the journal row's own UUID (`requestedEventId`); envelopes seal for `row.routePairId` only. |
| `app/src/main/java/.../accountability/sync/PartnerSyncCoordinator.kt` | `reconcilePair` consults the transport before the legacy emit: the `chain.hasEvent` crash-window heal runs first, then LEGACY emits the chain row, SIGNED deletes the journal row (the outbox row is the checkpoint), ACKNOWLEDGED clears it, DEFERRED retains it for the next pass. |
| `app/src/main/java/.../accountability/sync/PartnerRolloutGate.kt` | `ruleProducerStagedEnabled()` reads `delivery_producer_rules_v1_enabled` with **default false** — the only flag with inverted polarity (staged transport is opt-in; the three kill switches stay default-on). |
| `app/src/main/java/.../di/PartnerDeliveryModule.kt` | Provides the producer (`staged = { gate.ruleProducerStagedEnabled() }`, label resolver). |
| `app/src/main/java/.../di/AccountabilityModule.kt` | Wires `ruleChangeTransport = ruleChangeProducer::transport` into the coordinator. |

**Tests**

| Suite | Result |
|---|---|
| `PartnerRuleChangeProducerTest` (6 tests) | 6/6 — flag off records nothing; flag on records one signed copy under the journal identity and route pair; crash-window pending row stays SIGNED without re-recording; already-ACKED row reports ACKNOWLEDGED; recorder failure defers and leaves the journal row; v1 row without identity migrates with the neutral name. |
| `PartnerSyncCoordinatorTest` (transport tests) | 65/65 whole suite — `ruleJournal_signedTransport_takesTheRowOffTheJournal_withoutChaining` (no chain write, journal row leaves the work list, still counts as linked); `ruleJournal_eventAlreadyOnTheChain_isHealed_withoutConsultingTheTransport` (the heal precedes transport selection). |

**Design decisions**

1. An existing outbox row wins over the flag. A crash after enqueue but before the journal
   delete reports SIGNED again without re-recording — the migration is idempotent at the
   transport-selection point, not merely at the outbox.
2. The journal UUID as outbox event id is what makes redelivery a no-op end to end: the
   recipient feed's `(pairId, eventId)` primary key dedupes a copy that arrives on both
   transports during the staged window.
3. Staged-off is the shipped default and stays fully functional: the legacy chain path is
   untouched, and the flag only diverts journal rows that still exist (a migrated row never
   moves backward — its journal copy is gone).
4. A v1 journal row predates rule identity, so the identity is resolved at migration time
   from the live rule; when the rule is already gone the neutral `RuleIdentity("Restriction
   rule", APP)` is used rather than dropping or blocking the change.
5. `BLOCK_ATTEMPT` and `CHECK_IN` had no producer when this unit landed — an explicit §13.5
   scope decision, not an omission. Superseded for attempts by commit `f93f3e0` ("Route every
   partner-visible event through the staged signed transport", 2026-11-12): the coordinator's
   `attemptRecord` now stages `BLOCK_ATTEMPT` behind the same flag, and the v22 fix below gave
   those deliveries their recipient-side notification and history row. `CHECK_IN` still has no
   staged producer and stays on the legacy path.

### Unit 4 — vault-gated idempotent migration and recipient projection (§13.4)

Plan line: "Migration runs only when the vault is already open through the normal foreground
unlock… The recipient feed's `(pairId, eventId)` primary key makes any redelivery a no-op."
A migrated rule change must land in the same dashboard feed and notification the legacy
chain path produced.

**Touched files**

| File | Change |
|---|---|
| `app/src/main/java/.../accountability/rulecontrol/PartnerRuleChangeProjection.kt` | New. `onDelivered` accepts a decrypted `RULE_CHANGE_ATTEMPT` document, opens the vault via `AppRulesStoreFactory.open()`, inserts the same `PartnerRuleChange` feed row the chain path writes, and notifies once. Non-rule kinds and malformed payloads are ignored without touching the store. |
| `app/src/main/java/.../di/PartnerDeliveryModule.kt` | `providePartnerMailboxReceiver(onDelivered = projection::onDelivered)`; new `providePartnerRuleChangeProjection`. |
| `PartnerMailboxReceiver` | Unchanged — an exception from `onDelivered` (the locked-vault path) leaves the mailbox document unreceipted, so it retries after the next foreground unlock. |

**Tests**

| Suite | Result |
|---|---|
| `PartnerRuleChangeProjectionTest` (4 tests) | 4/4 — delivered change becomes the feed row and notifies once; redelivery is a no-op and never notifies twice; other kinds / malformed documents are ignored without touching the store; closed vault propagates so the mailbox document stays unreceipted. |
| `PartnerEventDrainTest` (stale-key + waiting) | 17/17 whole suite — regeneration and waiting-row delivery in one pass (see Unit 5/6). |

**Design decisions**

1. Vault gating is fail-safe by construction: the projection never opens the store itself
   from background; it calls the gate-backed factory, and `VaultLockedException` propagating
   out of `onDelivered` is the mechanism that keeps the document unreceipted. Migration
   therefore happens only after the normal foreground unlock, never from a background
   unlock of its own.
2. The feed row shape is the legacy `PartnerRuleChange` row — same columns, same
   notification — so the dashboard and notification read identically whether a change
   arrived on the old or new transport. Identity fields come from the signed payload.
3. The notification fires only on first insert (`inserted == true`); duplicate delivery stays
   silent. The `(pairId, eventId)` primary key is the only dedupe state needed.

### Unit 5 — key recovery and rotation (§8.1/§13.4)

Plan line: "Key recovery/rotation." §8.1: the drain re-encrypts a stale recipient envelope
"from the retained sealed source for the server's current authorized key set only" — a
revoked key is never a re-encryption target.

**Touched files**

| File | Change |
|---|---|
| `app/src/main/java/.../accountability/delivery/PartnerEventDrain.kt` | Stale branch regenerates from the sealed source for the authorized set (empty set passed through as empty, never null → park `WAITING_FOR_RECIPIENT_KEY`); `regenerateWaiting` re-probes waiting rows once per drain with a 900 s defer. |
| `app/src/main/java/.../accountability/delivery/PartnerEventRecorder.kt` | `regenerateRecipientEnvelopes` selects envelopes for the routed pair only, keeps the event identity and sealed source, and refuses once acceptance was possible or when ownership/source is gone. |
| `app/src/main/java/.../accountability/delivery/PartnerEventRegistrar.kt` | Re-enrollment paths: `REENROLL_REQUIRED` state, foreground-only registration, rotate-intent retry. |

**Tests**

| Suite | Result |
|---|---|
| `PartnerEventRecorderTest` (18 tests) | 18/18 — includes `staleKeyRegeneration_replacesCiphertextForTheNewRecipient_andKeepsTheIdentity`, `staleKeyRegeneration_keepsTheRoutedPairScope`, `regeneration_isClosedOnceAcceptanceWasPossible`, `regeneration_requiresOwnershipAndALiveSealedSource`, `regeneration_opensTheSealedSourceWithTheRowsOwnSchemaVersion`, `sealFailure_degradesRegeneration_butNeverDelivery`. |
| `PartnerEventRegistrarTest` (8 tests) | 8/8 — includes `rotationPrecondition_isRetriedWithTheOtherIntent`, `rotationNotEnrolled_isRetriedAsEnroll_whenLocalStateClaimedActive`, `serverKeyIdMismatch_isRetryable_andNothingIsCommitted`. |
| `PartnerMailboxReceiverTest` (9 tests) | 9/9 — delivered/duplicate/receipt paths the projection rides on. |

**Design decisions**

1. An empty authorized set is passed through as empty and parks the row `WAITING_FOR_RECIPIENT_KEY` — no ciphertext is ever written for a key the server has not currently
   authorized, and a revoked key is never a regeneration target.
2. Regeneration is bounded, not unlimited: once the row could have been accepted (an
   acceptance-possible state was observed), the sealed-source path closes and only the
   already-signed bytes can resend. This is what keeps a `SourceUnavailable` row from
   spinning.
3. Waiting rows re-probe once per drain with a 900 s defer; when the partner's new key
   appears, the row regenerates, re-signs and delivers in the same pass and the §14 counter
   fires (Unit 6).

### Unit 6 — §14 telemetry rails

Plan line (§14): "pending count, oldest pending age, … recipient-key stale re-encryption
count, oldest `WAITING_FOR_RECIPIENT_KEY`, … required-enforcement health and current phase
flag." Metadata stays an event name plus a coarse bucket (Decision 12); nothing
customer-facing is ever logged.

**Touched files**

| File | Change |
|---|---|
| `app/src/main/java/.../accountability/sync/PartnerSyncTelemetry.kt` | New events `partner_delivery_phase rule_producer=legacy\|staged` (first event of every enrolled pass), `partner_waiting_recipient_key age=<bucket>`, `partner_recipient_key_regenerated`; existing outbox-age and notifications-denied events unchanged. Every read is `runCatching`-wrapped so telemetry can never take the pass down. |
| `app/src/main/java/.../accountability/delivery/ServiceEventOutboxDao.kt` | `oldestWaitingCreatedAt()` — `MIN(created_local_at_ms)` over `WAITING_FOR_RECIPIENT_KEY` (null when nothing waits). |
| `app/src/main/java/.../accountability/delivery/PartnerEventDrain.kt` | `onRecipientKeyRegenerated: () -> Unit` seam; fires on every successful `Regenerated` in both the stale-response branch and `regenerateWaiting`. |
| `app/src/main/java/.../di/AccountabilityModule.kt` | Wires the gate's staged flag and the waiting-age query into `PartnerSyncTelemetry`. |
| `app/src/main/java/.../di/PartnerDeliveryModule.kt` | Wires the counter to `CrashReporting.nonFatal(EVENT_RECIPIENT_KEY_REGENERATED)`. |
| `app/docs/MyExodus Monitoring.md` | §1 instrumentation table gained the three new events with their emit points. |

**Tests**

| Suite | Result |
|---|---|
| `PartnerSyncTelemetryTest` (8 tests) | 8/8 — unpaired pass reports nothing; phase flag always first on enrolled passes; denied-only when notifications cannot post; fully-degraded pass order (phase, denied, waiting bucket, pending bucket); bucket edges (15 m / 1 h / 24 h exact boundaries); waiting absent when nothing waits; failing reads never throw and never take the pass down; `latencyBucket` edges unchanged. |
| `PartnerEventDrainTest` (17 tests) | 17/17 — new `waitingRow_deliversOnceTheRecipientKeyAppears_andCountsTheRegeneration` (parked row, zero counter, key appears, 900 s elapsed → one regeneration + accepted), and the stale-key test asserts the counter exactly once. |

**Design decisions**

1. The phase event is first and unconditional on enrolled periodic passes. Dashboard
   "Check now" never reports — the §14 placement rule (periodic worker only) predates this
   unit and still holds.
2. Coarse buckets only (`under_15m`/`under_1h`/`under_24h`/`over_24h`); the regeneration
   counter carries no dimensions at all. Decision 12.
3. The counter rides the shared Crashlytics rail through a DI-built `() -> Unit`, the same
   seam precedent as the send-latency metric — `PartnerEventDrain` keeps no dependency on
   the sync package.

### Unit 7 — rollback runbooks and compatibility window (§13.9/§13.5)

Plan line: "Produce runbooks for transport/App Check outage, signing/recipient key loss, and
liveness false-positive. Persist rollout state, log registration tier …, and document the
compatibility window." §13 item 7: rollback pauses transmission or dispatch only — never
disables security checks, deletes the outbox, purges legacy aliases, or fabricates liveness
state.

**Touched files**

| File | Change |
|---|---|
| `app/docs/partner-delivery-runbooks.md` | New, §13.9 deliverable. Control-surface table (7 switches, location, default, effect); Runbook A transport/App Check outage with detect signals, immediate actions (keep App Check enforcement on), least-disruptive rollback order and server-first/producer-last restore order; Runbook B signing/recipient-key loss and migration crash recovery; Runbook C liveness false-positive, fleet-incident declaration and DLQ handling; §13.5 compatibility-window policy. |
| `app/docs/MyExodus Monitoring.md` | The three new §14 events (Unit 6) documented alongside the existing rails. |

**Tests**

| Suite | Result |
|---|---|
| — | No executable surface; every control, default and error code in the runbook is a source read of the shipped code on 2026-11-12 (files and functions named inline in the runbooks), not an assumption. |

**Design decisions**

1. The compatibility window is policy, not a timer: no legacy purge in this release; the
   window closes for a kind only after the producer flag has been enabled in production AND
   `legacyUserEventAccepted` shows no unmigrated senders for 30 consecutive days. Cleanup
   then ships as a separate release, never as a flag flip.
2. `legacyUserEventAccepted` with `senderHash` + `installationIdentified` is the window's
   counter; `installationIdentified = true` on an accepted legacy event is a routing bug and
   is documented as such.
3. The runbooks link, never duplicate: attestation/revocation lives in
   `MyExodus Architecture Decisions.md`; lost devices link to
   `blocked-two-device-runbook.md` leg 5, `partner-sos-runbook.md` and
   `MyExodus Identity & Account Transition.md`.

### Unit 8 — restoration detection (§13.4)

Plan line: "Add … restoration detection." A replacement install must not inherit the old
installation's signing key or recipient key, and no device-migration path may carry the
accountability state elsewhere.

**Evidence (source, 2026-11-12)**

| Control | Evidence |
|---|---|
| App-level backup off | `app/src/main/AndroidManifest.xml:61` — `android:allowBackup="false"`. Blocks cloud and ADB backup on every API level. |
| API 31+ device-to-device transfer | `app/src/main/res/xml/data_extraction_rules.xml` — `cloud-backup` and `device-transfer` both exclude `database/`, `sharedpref/`, `rules_snapshot.json` and `files/datastore/`. Every accountability store is Keystore-bound; a transferred copy would be permanently unreadable and would orphan the Keystore references, so nothing transfers. |
| Pre-31 full backup | `app/src/main/res/xml/backup_rules.xml` — the same four exclusions, belt-and-braces under `allowBackup="false"`. |
| In-app encrypted backup | `app/src/main/java/.../backup/BackupPayload.kt:76-83` allowlist is habit/streak/milestone/attempt/session/coaching/riskWindow/policy — no enrollment, partner keys, service outbox, journal or mailbox rows. |
| Consequence | A replacement install has no signing key and no enrollment state, so it cannot masquerade as the old installation; registration is installation-scoped. The lost sender's pair is torn down from the surviving side (Runbook B → `blocked-two-device-runbook.md` leg 5). |

**Design decision**

1. Restoration is detected structurally, not heuristically: there is no restore path that
   carries the keys, so the app never has to guess whether it is a restored identity. The
   only re-entry is fresh enrollment from a surviving side.

**Phase 7 complete.** All unit work for plan line 184 is implemented and tested; the server
half of Units 1-2 was deployed and emulator-verified. Device verification of Phase 7 behavior
on the two physical phones is the Phase 8 release matrix, not a Phase 7 unit.

---

## Phase 8 — release verification (2026-11-12)

Plan line 186: "Run unit, emulator, integration, end-to-end and real-device matrix
including overnight Doze/probe, enforcement-disabled, stale-recipient-key, FCM registration
and direct-APK integrity quota scenarios; verify partner E2EE compatibility; measure
latency; complete acceptance evidence and operational runbook; publish signed APK via
existing GitHub Releases updater only after release gates pass."

Every automated tier below was run on the final tree. The physical matrix was executed to
the limit of the hardware actually present (two Transsion X6532, API 34); every remaining
§12 leg is recorded BLOCKED with its gate, never claimed as passed.

### Release artifact under test

| Item | Value |
|---|---|
| App | versionCode 16 / versionName 1.1.14 (`dumpsys package` on both phones) |
| APK | `app/build/outputs/apk/release/app-release.apk`, 39,120,593 B, file SHA-256 `c981836401cf11c3a57a3a6e4fdf3dedac0d5553bdbc2e52648c26608cd42b9d` |
| Signer certificate | SHA-256 `8f88188070952dde4c490a01ce8aa0407e9172ee4bbc035693ea795ab598854b` — identical to the Phase 0 direct-APK release identity (`apksigner verify --print-certs`) |
| Installed | `.106` lastUpdateTime 2026-11-12 03:27:49; `.107` 03:28:28; both `install -r`, no uninstall |
| GitHub Releases | none published (`gh release list` empty) — publish stays gated on this matrix |

### Live environment at verification time

| Item | State |
|---|---|
| Devices | 2× Infinix X6532 (Transsion), Android 14 / API 34, GMS 26.43.30, release-signed APK, live pair |
| Functions | the six Phase-7 functions, deployed 2026-11-12T00:17–00:21Z, unchanged since |
| `config/features` | `partnerSosEnabled: true` only — ingest/notify enabled by absence, liveness OFF by absence |
| Remote Config | no `delivery_*` keys: producer OFF, client sync ON, push registration ON |
| `partnerDeviceSigningRegistrations` | 2 ACTIVE docs (one per phone), `integrityTier = ACTIVE_HARDWARE`, revision 1, no revocation; created 00:29:44Z and 00:32:38Z, ~2 minutes after install — the shipped release build registered itself with real hardware attestation |
| `partnerDeviceLiveness` | 1 doc (`.106`): `status = MONITORING_UNAVAILABLE`, `enforcementState = HEALTHY`, `lastHeartbeatCounter = 1` — contact stored with the alert flag off, matching plan §13.6 |
| `config/device_liveness_incident` | absent (404) |
| Cloud Logging | 0 ERROR entries in the last 8 h (`entries:list`) |
| App Check quota (7 d) | 5 `serviceruntime.googleapis.com/quota/rate/net_usage` series; peak/day 184 debug-token exchanges, 75 / 59 integrity exchanges. No 70 % quota alert policy exists — see Gaps |

### Test tiers (final tree)

| Tier | Result |
|---|---|
| `functions` pure unit | 268/268 PASSED (Phase 7 record; Phase 8 changed no functions code) |
| Firestore rules + emulator suites | `test:rules`, `test:append`, `test:partner-registration`, `test:partner-ingest`, `test:liveness`, `test:dms` — chained run exit 0 |
| Android full unit suite | 2804 tests. First run exposed one failure: `RuleControlReceiverTest.redeliveredEvent_isANoOp_andKeepsTheFirstRow` still asserted the pre-Phase-7 `onEvent = true` on redelivery. The shipped contract is `true` then `false` (duplicate insert returns −1 so the caller cannot re-notify); the stale assertion was corrected. Focused class re-run green (4/4); full-suite re-run green — 295 suites, 2804 tests, 0 failures / 0 errors / 0 skipped. |
| Phase 0 baseline | 810 accountability tests on the pre-plan tree; all remain green inside the full suite |

### Acceptance matrix

| AC | Verdict | Evidence / gate |
|---|---|---|
| AC01 | PARTIAL | Signing key is non-auth-bound at source (`PartnerEventKeyStore:130` `setUserAuthenticationRequired(false)`); background signing unit-covered in Phase 4. AFU screen-locked >15 min execution on hardware is BLOCKED — needs an owner-attended locked-device window |
| AC02 | PARTIAL | Stable ID, atomic insert and redelivery no-op covered by unit + emulator suites; process-death / reboot / 1 h airplane on hardware BLOCKED (no unattended device window) |
| AC03 | PASSED | Emulator append + notify suites cover death after commit, lost response, lease reclaim and duplicate trigger; one durable job set |
| AC04 | PASSED | Emulator attribution suite: one-time challenge, bad chain, tamper, revoked key, cross-account and unauthorized-partnership rejection; live hardware shows only ACTIVE registrations |
| AC05 | PARTIAL | Both physical phones pass real outside-Play App Check with the release-signed APK at `ACTIVE_HARDWARE`. The rest of the §12 device matrix is BLOCKED (no Pixel/Samsung/API 26/30/33/35+ hardware); invalid-build rejection is emulator-covered only |
| AC06 | PARTIAL | New signing key requires no recent auth or unlocked screen (source); automatic registration with no prompt observed twice. The >15 min locked-screen sign leg is BLOCKED on the owner. The existing cold-window BiometricPrompt (ADR #296) is the legacy chain and remains enforced |
| AC07 | PASSED | Phase 0 P0 E2EE gate + relay-blindness tests; sealed payload allowlist rejects unknown keys; no plaintext in server logs or FCM payload (source + tests); lost-key historical ciphertext policy documented in Runbook B |
| AC08 | PASSED | On-device leg 2026-11-13 00:12 EAT on release v29/1.1.27: POST_NOTIFICATIONS revoked on the partner phone (verified `granted=false`, zero active MyExodus notification records at baseline), a message sent from the subject phone landed in `partnerMailboxes/Q6Tj…/messages/1487da23-4079-4be6-90ef-02b6034c7d53__ea93a071…` (createTime 00:12:51, sequence 189, kind `ACCOUNTABILITY_UPDATE`, E2EE payload) and rendered in the partner's feed ("From Kelvin Musila — Sent just now — Quiet-mode") with the honest "Notifications are off / Open notification settings" banner; zero notification records were posted throughout. Ack semantics verified in source: `partner-ingest.js:241` writes the mailbox record and returns ACCEPTED in the same transaction, while the notification job is a separate `partnerNotificationOutbox` document with its own `SENT_TO_FCM` status — dispatch can never stand in for the mailbox receipt. Permission restored to `granted=true` afterwards; duplicate-FCM dedupe remains unit + emulator covered (mailbox keys are `eventId__installationId`) |
| AC09 | PASSED for the overnight false-positive criterion — leg 2026-11-13T00:48:06Z → 06:40Z (03:48–09:40 EAT); sustained-absence alert path remains emulator-covered only | Liveness emulator suite exit 0: probe at 90 min, 20 min window, 240/360 min grace, one alert per episode, incident suppression. Leg began after the epoch-8 re-pair on release v30/1.1.28: `config/features.device_liveness_alerts_v1_enabled` verified `true` (Firestore flag, not Remote Config); baselines `partnerDeviceLiveness` `.106 P9XVqUX…:ae3926b5…` counter **253** ACTIVE (lastAccepted 00:47:49Z) and `.107 Q6Tjmy…:ea93a071…` counter **62** ACTIVE (lastAccepted 00:47:39Z), `alertedAt`/outageEpisodeId null on both. Arming prerequisite found and fixed: the AC10 teardown + re-pair cleared `availabilityConsent` and the alert fan-out is availability-consent gated (`device-contact.js`; `dms-logic.js` `consentEnabled` fails closed) — without it the leg would have produced zero partner notifications. Toggling Device availability on both phones converged app-written consent for **both** uids (P9XVqUX `updatedAtMs` 1794530799416, Q6Tjmy 1794530805000). Also restored before arming: .107 had been force-stopped (OneKeyClean) with enforcement degraded — protection re-enabled and accessibility binding verified. Owner locked both phones; .107 is the monitored device, unplugged at 81% discharging (charging blocks Doze); both rotation guards `accelerometer_rotation=0`. Interim checkpoints (server-side only, phones untouched): 04:12 EAT — probe not yet due (last contact 03:47:39 EAT), no alert fields, zero outbox rows. 05:31 EAT — first probe episode: sweep 02:20:15Z logged `probed: 1`/`stale: 2`; .107 `lastProbeSentAtMs` 02:20:14.787Z and the probe was answered at 02:20:26.038Z (+11.3 s, counter 62→63), probe fields cleared, status ACTIVE / HEALTHY, `alertedAt`/`outageEpisodeId` null, still zero availability outbox rows; .106 counter 260 ACTIVE; consent true for both uids, flag still true. **Final result (owner stop 09:40 EAT):** ~71 five-minute sweeps, every one quiet except the single probe pass; .107 counter 62 → **67** (contact roughly hourly while locked — consistent with Doze maintenance windows), status ACTIVE / HEALTHY throughout, `alertedAtMs` and `outageEpisodeId` never set, **zero** `partnerNotificationOutbox` rows for either recipient for the whole window, no duplicate probes, no `failed` sweep counts. Verdict against the AC09 wording: 90-min staleness produced at most one probe and no immediate alert; the signed response inside 20 min cleared it silently; duplicate schedulers produced no duplicate/false alerts. Honest boundary: the monitored phone never went dark past the 240/360-minute grace, so the one-alert-per-episode `DEVICE_UNAVAILABLE` + `DEVICE_RECONNECTED` pair was not exercised on hardware overnight (emulator suite + AC15's degraded/restored leg remain its coverage). **Owner ruling 2026-11-13: the hardware re-proof of the sustained-absence alert is WAIVED — assumed to pass ("we have to assume"); the emulator suite + AC15 remain the recorded evidence and no further overnight is to be scheduled.** Flag restored `device_liveness_alerts_v1_enabled=false` with read-back, rotation guards verified on both serials, .107 returned to USB-only adb for hand-back. **Open defect found while arming (owner deferred the fix):** .107's local Device-availability toggle ON did not push server consent across several forced sync passes and showed no user-visible failure once the pending flag cleared — server consent had to be converged by an app-side toggle cycle; a toggle that cannot reach the server should surface a persistent "not synced" state. Result recorded at the stop signal |
| AC10 | PASSED | Pair-lifecycle and registration-revocation emulator suites PASSED, plus the device teardown leg on release v30/1.1.28, both X6532, 2026-11-13 ~02:18 EAT. Teardown from .106: .107 received the named termination notification and the local erase copy, both phones rendered "Your data … has been erased"; server audit `pairings/<pairId>/system_events/pair_terminated` = `{type: PAIR_TERMINATED, actorUid: P9XVqUX…, createdAt: 2026-11-12T23:16:21.907Z}`. Re-pair ceremony completed (invitation `FT3B-WRJT-9XA1-F`, safety code 97044 compared on both phones); pair active again at `pairingEpoch` 4, `activatedAt` 23:18:19.741Z (prior epoch 2). No-further-delivery proven at source: `event-append.js:125` throws `PAIR_NOT_ACTIVE` for a non-active pair, `firestore.rules:65` makes mailbox records callable-only (`allow create, update, delete: if false`), `partner-ingest.js:180` treats an inactive pairing as stale, and teardown shreds local enrollments/keys (BilateralDeletion, ADR #113/§19.5). Honest boundary: no third-party observer recorded during the terminated window; the queue-preservation half of the criterion is the AC16 re-encryption path, since unpair cancels PENDING envelopes by design (`CANCELLED_BY_REVOCATION`). Screenshots `/tmp/ac-legs/106-12,107-12,106-15,106/107-18,106/107-19-pre,106-20..22` |
| AC11 | PARTIAL | Migration and transport-state unit tests PASSED (crash-window heal, stable event id, retain-on-defer); on-device journal migration BLOCKED — producer flag OFF, no journal rows on the phones |
| AC12 | MEASURED — targets met for the tested condition | Matrix G14 (production ledger, staged transport, warm/unlocked path): enqueue→server acceptance p95 699 ms (n=32, target ≤10 s); acceptance→FCM acceptance p95 2,140 ms (n=40, target ≤5 s). Scope: 2× Transsion API 34, online, no OEM suppression; the all-events view (n=120, includes background/Doze/offline enqueues) reads p95 12.57 s — outside the warm-path target by design, not a regression |
| AC13 | PASSED | No new FGS (manifest unchanged; the existing periodic worker carries the path), no Direct Boot telemetry queue, no identity delegation, no routine foreground-auth dependency |
| AC14 | PASSED | This change set touches no blocking/enforcement code path; app-lock behaviour unchanged (file-set verified) |
| AC15 | PASSED for degraded/restored on hardware; overnight false-positive leg BLOCKED | 2026-11-13 21:42–21:50Z on the live pair (release v29/1.1.27), `device_liveness_alerts_v1_enabled` ON for the window and restored OFF immediately after (read-back verified). Required-capability loss (accessibility disabled on the subject phone) → liveness doc counter 229 `DEGRADED` with `ACCESSIBILITY` missing, one outbox row `…229.ENFORCEMENT_DEGRADED` `SENT` (provider accepted=2) → partner phone rendered "Partner check-in — Kelvin Musila's phone reports that a required protection is turned off. Open the app to review it." (`availability_alerts_v1`, tag `partner:availability:<pairId>`). Restore → counter 230 `HEALTHY`, one row `…230.ENFORCEMENT_RESTORED` `SENT` (accepted=2) → "…its required protection is back on."; same tag, so the restored notice replaces the degraded one. Exactly one row per transition and no duplicates across four transitions (226/227/229/230). Live doc reports required capabilities `ACCESSIBILITY` + `VPN` (both have live rules). Optional-protection clause is unit-covered (`required capabilities drive the health state; optional protections never do`); a live optional toggle is not constructible on this device (no device-admin surface, both listed capabilities are required). Defect found and fixed during the leg: the deployed `dmsDeadlineWorker`/`deviceLivenessWorker` predated commit 9746e6a and sent a notification block with `type: SYNC_AVAILABLE`, which a foregrounded app parses as a generic wake — no alert ever rendered; after redeploying those two functions the data-only `PARTNER_AVAILABILITY` payload renders both notices. The overnight Doze false-positive leg runs as the AC09 entry above (armed 2026-11-13T00:48:06Z, evaluated at the owner's stop signal) |
| AC16 | PASSED | Device leg 2026-11-13 ~03:40 EAT on release v30/1.1.28, both X6532. Rotation trigger was the epoch-8 re-pair (`pairingEpoch` 4→8, `activatedAt` 2026-11-13T00:31:12.125Z), which revoked the prior pair's recipient keys — the same rotation class `IdentityMigration.migrate()` produces, without needing the owner's lock credential. A pending event from .107 (`383f899a-1f0c-48e1-99b1-58b5e4c9ccf7`, installation `ea93a071…`) was stale-rejected three times (23:54:27Z, 00:09:28Z, 00:24:38Z) with **zero server writes** — mailbox stayed at 17 records, no ledger entry. Re-encryption from the AES-sealed local source against the server-advertised authorized key set produced exactly **one** accepted record: ledger `partnerEvents/Q6Tjmy…/installations/ea93a071…/events/383f899a-…` = `{status: ACCEPTED, sequence: 15, deviceOccurredAtMs: 1794527665934, recipientKeyIds: ["lQiDbfOqJ39RqXHe44Mwz0Sp6W_PqAQon79d9mkog8Y"]}` and mailbox `partnerMailboxes/P9XVqUX…/messages/383f899a-…__ae3926b5-…` exists (sequence 15); recipient inbox 17→18. Original eventId, sequence and deviceOccurredAt preserved; the envelope changed only under the definitive `RECIPIENT_KEY_STALE` path. Server/client source bounds re-verified the same day (`partner-ingest.js:148-196`, `partner-events.js` `allowsCiphertextRegeneration()`, `PartnerEventRecorder.regenerateRecipientEnvelopes`, `ServiceEventOutboxDao.replaceRecipientEnvelopes` limited to NOT_SENT/RECIPIENT_KEY_STALE) |
| AC17 | NOT RUN — WAIVED by the owner (2026-11-13) | The on-device switch needs a second signed-in account and destroys the live pair; the owner waived it for the last two-device window ("im assuming it will pass"). Emulator attribution tests cover cross-account denial and distinct installation ids. Source re-verified 2026-11-13: `partner-ingest.js:105/108` reject `OWNER_UID_MISMATCH` / `INSTALLATION_MISMATCH`; `ServiceEventOutboxDao.suspendOtherOwners` marks other-owner rows `OWNER_AUTH_REQUIRED` and `resumeOwner` restores only for the legitimate owner; `PartnerEventDrain` uploads with `row.ownerUid`; `InstallationState` has a unique `owner_uid` index so a second account gets its own installation ID and sequence space; `PartnerEventRecorder` refuses cross-account regeneration. **This is not a PASS** — record as waived, not proven |
| AC18 | PARTIAL | RKP dual-root / forged / revoked fixtures PASSED in emulator tests; live `ACTIVE_HARDWARE` accepted on both phones; a deliberate non-GMS outcome is not runnable here (both devices are GMS) — recorded as not executed |
| AC19 | PASSED at backend tier | Matrix G15: 200-event noncritical burst in the ingest emulator suite (16/16) — every batch accepted, all 200 mailbox entries and 200 notification jobs preserved, all sharing one per-minute push group. The ≤1-push-per-60 s half is covered by `claimDecision` unit tests (no emulator test — honest gap). A physical 200-event UI burst was not attempted (not sane to drive through the UI) |

### §12 physical-device matrix coverage

| Axis | Covered |
|---|---|
| API 26 / 30 / 33 / 34 / 35+ | **34 only** (2 devices) |
| Pixel/stock, Samsung, Transsion-class | **Transsion only** |
| AFU screen-locked, overnight idle, reboot-before-first-unlock, first unlock, force-stop, Doze, notification denial, offline recovery, key re-enrollment | notification denial **PASSED** 2026-11-13 (AC08); the rest BLOCKED on an owner-attended window |
| Direct-APK release build | **yes** — signed release APK on both phones |
| Real non-Play App Check | **yes** — `ACTIVE_HARDWARE` registrations on both |
| Deliberate non-GMS / failed-integrity classification | **not executed** — no non-GMS hardware; emulator rejection tests cover the negative path |
| RKP dual-root fixtures | emulator fixtures only |
| Boot receiver delivery, Android 12+ | BLOCKED — requires a reboot-before-first-unlock sequence |

### Release gates

- **GitHub Releases publish: NOT TRIGGERED.** The §13.8 gate requires the release matrix to
  pass; the owner-gated legs above are BLOCKED, not passed.
- Rollout flags remain at shipped defaults; producer OFF. The liveness gate was enabled only
  for the 2026-11-13 AC15 window and restored to absent afterwards (Firestore read-back).
- The 2026-11-13 AC15 leg also redeployed `dmsDeadlineWorker` and `deviceLivenessWorker`
  (build at commit 9746e6a) so the data-only `PARTNER_AVAILABILITY` payload reaches v29
  clients; `deviceLivenessWorker` stays inert while the liveness gate is OFF.

### Gaps opened by this phase

1. **No 70 % App Check quota alert policy** (plan §14). Observed usage is far below any
   plausible limit (peak 184/day debug-token exchanges), but the required rail is absent.
   The quota metric's labels do not expose the limit value, and the per-metric daily limits
   are not published in official docs (published figures cover provider attestation, not
   these quota names); the Firebase console is the source for the denominator. Decision:
   add the policy before `delivery_producer_rules_v1_enabled` is ever enabled in production;
   the legacy path in use today is unaffected.
2. **Owner-attended device legs** (Doze/overnight, AFU, notification-denied, rotation,
   account switch, burst, latency) are queued as one Phase 8 device window, not dropped.

### Code review (final pass)

- No CRITICAL or HIGH findings. Informational: telemetry `oldestWaitingCreatedAt()` is not
  owner-scoped (aggregate only, no row data); the producer maps every non-ACKED existing
  outbox row to SIGNED (intended migration invariant); legacy `appendUserEvent` cannot
  detect clients that omit `installationId` (documented §13.5 compatibility limit).
- Found and fixed during this phase: `RuleControlReceiverTest` asserted the old redelivery
  contract; the implementation was correct and the stale assertion was updated.

### Runbook correction

`app/docs/partner-delivery-runbooks.md`: `device_liveness_alerts_v1_enabled` was documented
as "absent = enabled"; the source (`functions/device-contact.js`) requires an explicit
`true` and returns `LIVENESS_V1_OFF` otherwise. Fixed in the control-surface table and
Runbook C, matching plan §13.6.

**Phase 8 record status.** All automated tiers are green on the final tree. The physical
matrix is PARTIAL: the release-signed direct APK and real App Check `ACTIVE_HARDWARE`
registration are proven on both phones; every remaining §12 axis and every owner-attended
acceptance leg is BLOCKED and enumerated above. Publish stays gated.

---

## Post-Phase-8 fixes — 2026-11-12 (v21 / 1.1.19)

Owner-reported defects from a live v20 session, both fixed, shipped and verified on the
two-phone production pair the same day.

### 1. Partner pages did not repaint on staged-mailbox deliveries

**Symptom.** Sitting on Partner → Rules & blocks, a delivered event (Google Assistant rule
assigned) produced a notification but no new row; the row appeared only after leaving the
subpage and re-entering. The page also kept stale relative times ("6 hours ago" frozen while
open).

**Root cause.** Two independent gaps.
- `runPartnerSyncPass` emitted `PartnerSyncSignals.pulledEvents` only when the legacy
  chain's `pulledCount > 0 || unpairAdvanced`. The staged transport counts deliveries in
  `SyncReport.recipientEventsPulled` (`pullPartnerEvents`, wired at
  `AccountabilityModule.kt:970`), so staged pulls repainted nothing.
- Relative labels called `System.currentTimeMillis()` at composition; nothing recomposed
  while the page sat idle.

**Fix.**
- `accountability/sync/PartnerSyncWorker.kt` — repaint condition now
  `report.pulledCount > 0 || report.recipientEventsPulled > 0 || report.unpairAdvanced`.
- `ui/partner/PartnerScreen.kt` — `LocalPartnerNowMillis` CompositionLocal fed by a 30 s
  `LaunchedEffect` ticker (same cadence as Home); every partner label reads the
  CompositionLocal instead of the wall clock. Provider wraps the hub, every subpage and the
  plans early-return; `PartnerPlansScreen` reads the same local.

**Device evidence (v21, both phones).** With the partner phone parked on Rules & blocks
(28 updates), the subject toggled the Google Assistant rule off:
- Partner screen went 28 → 29 updates with a new top row "Google Assistant · Rule turned
  off · just now" **without any navigation**, while the heads-up "Rule update from Kelvin
  Musila — Google Assistant was turned off." posted.
- 90 s later, untouched: the same rows read "2m ago" and "24m ago" (were "just now" and
  "22m ago") — labels age in place.

### 2. End-to-end partner alert latency ≈ 88 s

**Root cause.** The sender-side capture kick (`partnerEventSyncNowRequest`) lacked
`.setExpedited(...)` while the wake/probe/post-mutation requests already had it, so the
first hop waited for an ordinary JobScheduler slot. WorkManager 2.11.2 bytecode was checked
first: no enqueue class references expedited restrictions, so expedited is legal inside the
`APPEND_OR_REPLACE` chain.

**Fix.** `accountability/delivery/PartnerEventSyncWorker.kt` — capture kick is expedited
with `RUN_AS_NON_EXPEDITED_WORK_REQUEST` (quota spillover degrades, never drops; the outbox
row stays pending). Pinned by `PartnerWakeExpeditedTest`.

**Measured sample (v21, one healthy-path event).** Tap 14:55:27 → sender drain
`claimed=1, accepted=1` 14:55:31.730 (+4.7 s) → receiver FCM wake 14:55:32.702 (+5.7 s) →
receiver pass `recipientEventsPulled=1` 14:55:35.969 (+9.0 s). Earlier v20 sample on the
same pair was ~88 s.

**Carry.** A single sample is not the plan §12 p95 measurement; the burst/p95 leg and the
remaining Phase 8 device legs stay open.

### 3. Review finding — raw NUL byte in `deterministicEventId`

The review of the v21 change set found the separator in
`PartnerEventProtocol.deterministicEventId` was a literal NUL byte embedded in the source
string, which makes grep/diff tooling treat the file as binary and is fragile through any
encoding pass. Replaced with the explicit unicode escape — the runtime value is
byte-identical, so event ids already staged under the old source stay stable. The contract
(stable across retries, v4 shape, distinct per source row and progress set) is pinned by
`PartnerEventProtocolTest`.

---

## Post-v21 fix — 2026-11-12 (v22 / 1.1.20)

### Staged blocked attempts reached the recipient silently

**Symptom (owner battery, v21).** A blocked launch on the subject phone was accepted by the
backend (`drain: claimed=1, accepted=1`) and pulled by the partner phone
(`recipientEventsPulled=1`), yet the partner got **no notification and no Rules & blocks
row** — the exact half of S11 that had passed on v9 through the legacy chain path went dark
when v21 moved attempts onto the staged transport.

**Root cause (two independent gaps).**
- `PartnerObservationProjection.onDelivered` routed only `ACCOUNTABILITY_UPDATE` and
  `MONITORING_UPDATE`; `BLOCK_ATTEMPT` fell through to the unknown-kind branch, so no
  notification was posted. The v21 sender had already started staging attempts
  (`PartnerSyncCoordinator.attemptRecord`), so the recipient half was the missing link.
- Staged attempts write no `ChainEvent` rows, and a state-less `TYPE_ACTIVITY` payload is
  classified `Malformed` by `PartnerActivityReceiver` (attempts are not partner activity
  state). The dashboard's legacy attempt-history source reads chain rows, so even a
  notification would have had no row behind it.

**Fix.**
- `accountability/delivery/PartnerObservationProjection.kt` — `BLOCK_ATTEMPT` now routes to
  `onAccountabilityUpdate` (notification via `PartnerNotifications.notifyPartnerEvent`, same
  copy as the legacy path), and a delivered attempt persists a `PartnerAttemptAlert` row in
  the rules store with the sender's occurrence time and the 30-day `PartnerEventJournal`
  retention.
- `data/db/entity/PartnerAttemptAlert.kt` + `dao/PartnerAttemptAlertDao.kt` — new
  pair-scoped table `partner_attempt_alert` (primary key `(pair_id, event_id)` so
  redelivery dedupes; `IGNORE` on conflict), `AppRulesStore` v30 with
  `MIGRATION_29_30`/`MIGRATION_30_29`, schema `30.json` exported.
- `ui/partner/PartnerPresentation.kt` + `PartnerDashboardSource.kt` — `attemptAlert(row)`
  decodes the stored text through the same parser the legacy rows use; staged rows merge
  into both `alerts` (chart/today counts/recent) and `ruleHistory` (Rules & blocks).
- `di/AccountabilityModule.kt` — `attemptAlerts` reads the DAO (30-day window, feed limit);
  `data/db/PartnerActivityPrune.kt` — the table is wiped by the orphan sweep, account purge,
  unpair shred, and pairing completion like every other pair-scoped row.

**Tests.** `PartnerObservationProjectionTest` (new): a delivered `BLOCK_ATTEMPT` becomes
exactly one row with text/appLabel/occurrence/expiry and a redelivery dedupes;
`ACCOUNTABILITY_UPDATE` and an eventId-less `BLOCK_ATTEMPT` write nothing.
`PartnerDashboardSourceTest`: staged attempt rows feed both `alerts.recent` and
`ruleHistory` with the batch label. `AppRulesStoreMigrationTest` green; focused partner
suites green.

**Device evidence (v22, both phones on release 1.1.20).** Calculator
(`com.transsion.calculator`) is a member of the shared "Battery batch":
- .106 logcat: `MyExodusAttempts: block logged target=com.transsion.calculator` 15:42:34.810;
  `MyExodusPartnerEvents: drain: Result(claimed=1, accepted=1, …)` 15:42:41.271.
- .107 tray (`dumpsys notification --noredact`): tag
  `partner:event:P9XVqUX138cSgaFSUsyhFyrz5Sp2_x333CTYB4iSkwvOFAatGvBM5LZu1`, title
  "Kelvin Musila tried to open a blocked app", text "Calculator was blocked in the Battery
  batch batch." `when=1794487368003`.
- .107 Partner → Rules & blocks now lists "Calculator · Blocked · Battery batch batch ·
  2m ago" as the newest row (31 updates), and the activity feed shows the same row — the
  notification and the history row agree.

Copy note: the batch is named "Battery batch", so the template's "…in the «name» batch"
renders "Battery batch batch". That is the batch name the owner chose, not a duplicating
template; the S11 ruling (the batch name must appear) holds.

## Post-v27 fix — 2026-11-12 (v28 / 1.1.26)

### Offline block → reboot → unlock → delivery: the boot path was dropping every resume

**Symptom (reported path, #176 reboot/boot).** A block recorded while the phone is offline
must survive a reboot and reach the partner after unlock without opening My Exodus. The
first attempt at this leg (earlier today) was invalidated: the pre-reboot in-process pass
had already staged and delivered the block. Re-running with a genuinely offline block
exposed the real defect.

**Root causes (three, fixed together).**
- `PartnerEventBootReceiver` guarded on `FirebaseAuth.currentUser`, but the boot broadcast
  starts a fresh process where the persisted user is still restoring, so `currentUser` is
  null for a window — every post-reboot resume was silently dropped (observed 2026-11-12:
  receivers delivered, zero work enqueued). The workers already fence themselves on the
  session, so the guard is removed; the receiver now also requests a full sync pass
  (`enqueuePartnerSyncNow`) so an attempt that was never staged pre-reboot still reaches the
  outbox.
- `MyExodusApp`'s `userUnlockedReceiver` scheduled partner delivery only when
  `localSessionManager.current() != null`, which can also be null mid-restore. It now
  schedules unconditionally, like the boot receiver.
- The staged event stamped `occurredAtEpochMillis` at record time only; the staged wire
  event now carries the attempt's original `occurredAtEpochMillis` through
  `PartnerEventProducer` → `PartnerEventRecorder` (`deviceOccurredAtMs = occurredAtMs`), so
  a delayed event reports when it happened, not when it was staged.

**Device evidence (v28 / 1.1.26, release, `install -r` on both phones).**
- 22:01:55 `.106` offline (airplane ON + `svc wifi disable`; ping to the gateway failed):
  `MyExodusAttempts: block logged: rule=d865636c-df5a-4643-8c41-cf928cd8d481
  target=com.ichi2.anki`; overlay shown and dismissed; no drain ran (offline).
- 22:02:56 `adb reboot` with the network still off. BOOT_COMPLETED dispatched 22:03:55;
  `MyExodusPush: boot wake — resuming partner delivery` at 22:04:48 **while airplane=1 and
  Wi-Fi off** — the receiver runs without network and without opening the app.
- 22:04:57 network restored (`cmd connectivity airplane-mode disable; svc wifi enable`;
  ping OK). The app was never opened.
- 22:05:23 backend accepted the event (`receivedAt=1794510323849`):
  `partnerEvents/P9XVqUX138cSgaFSUsyhFyrz5Sp2/installations/ae3926b5-…/events/269dc0d0-…`,
  `kind=BLOCK_ATTEMPT`, `category=BLOCKED_APP_ATTEMPT`, `status=ACCEPTED`, `sequence=165`,
  and **`deviceOccurredAtMs=1794510115435` = 22:01:55**, the original block time, not the
  22:05 staging time — the timestamp fix holds on the wire.
- 22:05:29 `.107` posted the partner notice (`partner_accountability_events_v1`, tag
  `partner:event:P9XVqUX138cSgaFSUsyhFyrz5Sp2_Q6TjmyFsPUccwVDlnUaiKBuEDPP2`), heads-up
  22:05:29.955→22:05:35.63, still present in the shade at 22:06.
- Latency after network restoration: block → backend acceptance 26 s; acceptance →
  recipient notification 5.4 s (v28 online path).

**Auxiliary XOS findings recorded (device runbook).** (1) After a sideload `install -r`,
writes to `enabled_accessibility_services` are silently reverted because the
`ACCESS_RESTRICTED_SETTINGS` app-op is `ignore`; `appops set com.example.myexodus
ACCESS_RESTRICTED_SETTINGS allow` restores the ability to enable the service, and XOS's App
info page has no ⋮ overflow to grant it from the UI. (2) XOS airplane mode keeps Wi-Fi
enabled by default ("keep Wi-Fi in airplane mode" is ON), so `svc wifi disable` must be
added for a true offline state. Both belong in `app/docs/workstation-tooling.md`/device
runbook at the wrap.

## Post-v30 fix — 2026-11-13 (v31 / 1.1.29, task #194)

### Rule-toggle and sharing-revoke updates never reached the partner's feeds

**Symptom (owner directive 2026-11-12).** A rule switch-off surfaced correctly through
`RULE_CONTROL` (Rules & blocks + activity feed + tap-to-Rules notification). The sharing
switches did not: switching **Device availability**, **Blocked-app check-ins**, or a
**streak's sharing** off posted the consent notification but left no row in Rules & updates
or Recent activity, and the availability/check-ins notifications tapped into a feed that
never mentioned the change. A rule switch-off therefore looked recorded while the
availability switch-off looked like it vanished.

**Root cause.** The three revoke payloads are snapshot-less and carry empty text
(`TYPE_AVAILABILITY_SHARING_STOPPED`, `TYPE_CHECKINS_SHARING_STOPPED`,
`TYPE_STREAK_SHARING_STOPPED` — the last one carrying only `habitLabel`). Since v21 they
deliver through the staged transport, which writes **no chain rows**, and `alertOf` /
`parseActivityText` can only present text/state — so nothing fed `alerts` or `ruleHistory`.
The notification was the only surface the revoke ever reached.

**Fix (mirrors the `PartnerAttemptAlert` precedent).**
- `data/db/entity/PartnerSharingAlert.kt` + `dao/PartnerSharingAlertDao.kt` — pair-scoped
  `partner_sharing_alert` (primary key `(pair_id, event_id)`, `IGNORE` on conflict so a
  redelivery dedupes; `kind` stores the wire consent type, `label` the streak name when the
  revoke is habit-anchored). `AppRulesStore` v31 with
  `MIGRATION_30_31`/`MIGRATION_31_30`, schema `31.json`.
- `accountability/delivery/PartnerObservationProjection.kt` — every delivered
  `SHARING_STOPPED_TYPES` payload persists the row (sender occurrence time, 30-day
  `PartnerEventJournal` retention) before the notification path runs.
- `ui/partner/PartnerPresentation.kt` — `PartnerAlert.SharingChanged(change, at, label)`
  plus wire-map `SharingChange`; `alertOf` maps the three types (the legacy chain path keeps
  parity) and `sharingAlert(row)` decodes the stored row.
- `ui/partner/PartnerDashboardSource.kt` + `di/AccountabilityModule.kt` — stored rows fold
  into `ruleAlerts`, so they surface in both the week `alerts` feed and `ruleHistory`
  (Rules & blocks), same 5-row/limit handling as the other receiver-side rows.
- `PartnerScreen.AlertRow` — label per change (streak name or "Blocked-app check-ins" /
  the Device availability title), kind "Stopped sharing", `VisibilityOff` glyph.
- `data/db/PartnerActivityPrune.kt` — the new table joins the orphan/unpair/purge sweep.

**Scope ruling (recorded, not silent).** Only revokes produce a feed row: availability ON
emits nothing by the owner ruling of 2026-11-12 (notify on off only), and check-ins/streak
revokes share this one code path — the row family covers all three rather than leaving two
of the three still invisible. Block-toggle rule changes were already fully surfaced through
`PartnerRuleChange` (verified from source, no change needed).

**Tests.** `PartnerObservationProjectionTest`: a delivered availability revoke and a streak
revoke each persist exactly one row with kind/label/occurrence/expiry, redelivery dedupes.
`PartnerPresentationTest`: `alertOf` maps all three types (streak label kept, blank label →
null) and `sharingAlert` maps rows, unknown kind → null. `PartnerDashboardSourceTest`: a
stored revoke lands in `alerts.recent` and `ruleHistory`. `AppRulesStoreMigrationTest`:
30→31 creates the table, rollback drops only it and keeps sentinel rows; the
`ALL_MIGRATIONS` coverage guard passes.

**Device evidence.** Tests + source verification only at the time of writing: the partner
surfaces need the second phone, which was handed back on 2026-11-13 after the AC09 window.
Recorded BLOCKED, not PASSED — to be observed on the next borrowed-device window (turn
availability off on .106 → expect "Stopped sharing · Device availability" in the partner's
Rules & updates and Recent activity).

---

## 2026-11-13 — Remote Config removal (#177, ADR #171)

**Change.** The last client-side remote-configuration SDK is gone. The live surface had grown
to three Remote Config keys read by `PartnerRolloutGate`; all three retired in one change:

| Retired key | Old default | Replacement |
|---|---|---|
| `partner_push_registration_enabled` | ON | `runPartnerSyncPass` always calls `registrar.register()` |
| `delivery_client_sync_v1_enabled` | ON | `PartnerEventDrain.run` always proceeds past the uid gate |
| `delivery_producer_rules_v1_enabled` | **OFF** (opposite polarity) | `PartnerEventProducer` always attempts the signed service-outbox path |

`PartnerRolloutGate.kt` and `PartnerRolloutGateTest.kt` were deleted, along with the
`AccountabilityModule` provider (and its 900 s fetch-interval constant), the gate parameters
on `PartnerSyncWorker`, `PartnerLiveSync`, `PartnerSyncTelemetry`, `PartnerEventProducer`,
`PartnerEventDrain` and the JIT arguments in `MainActivity`/`MyExodusApp`. `firebase-config`
left `gradle/libs.versions.toml`, `app/build.gradle.kts` and `app/gradle.lockfile`
(`firebase-config:23.1.0` gone; `firebase-config-interop` survives only as a transitive of
other Firebase artifacts). The RC SDK's back-dated-clock fetch-throttle wedge guard
(`isFetchStampWedged`) died with it.

**Behavior notes.** The staged producer was already the production transport on every device
by 2026-11-12, so their wire behavior does not change; the removal makes it unconditional on
any build from this one onward. `PartnerSyncTelemetry` keeps emitting
`partner_delivery_phase` once per enrolled pass, now a constant `rule_producer=staged`, so
`partner_delivery_phase` dashboards need no filter change. The legacy `LEGACY` transport enum
branch survives only as the fallback when a caller wires no producer (no shipped DI path
does); an unsealable row still DEFERS rather than being dropped.

**Capability lost, recorded honestly.** There is no longer any way to pause push
registration, event sending, or the producer from a console without shipping an APK. The
operational controls are the server-side `config/features` switches (ADR #207), which pause
ingest/dispatch while clients keep recording and retrying, and the signed GitHub Releases
`HALT` policy for a defective client build. Runbook A was rewritten accordingly.

## Phase 8 umbrella sweep — 2026-11-13 (task #169)

Reconciled the acceptance matrix above against the code, the plan and the task list at the
close of the last two-device window. No verdict changed: AC17 remains **WAIVED, not run —
not a pass**; AC01/AC06 remain **BLOCKED** on an owner-attended locked-device window; the
AC09 sustained-absence hardware re-proof remains **owner-WAIVED** (emulator suite + AC15 are
its recorded evidence); AC02/AC05/AC11/AC18 partials remain blocked on hardware the project
does not have. A repo-wide search found no stale "AC17 PASSED" or "Phase 8 complete" claim.
No new overnights were scheduled. The GitHub Releases publish gate remains **NOT TRIGGERED**
until the owner-gated legs run; the liveness flag is OFF and the producer flags are
unconditional per the 2026-11-13 Remote Config removal above. The software-verifiable work
of Phase 8 (unit/emulator tiers, E2EE, latency, runbooks, evidence) is complete; only the
owner/device-gated legs are open, and they are recorded as such rather than claimed.
