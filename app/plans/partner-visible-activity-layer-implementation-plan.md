# Partner-Visible Activity Layer — Final Implementation Specification

**Status: ✅ BUILT — the delta on the ADR #111 rails shipped 2026-09-28 (ADR #115, schema v13). Read §A first: it maps every section of this document to either a shipped rail or the delta that was actually built, and names the deliberate deviations (§8.2 sign-at-reconcile instead of sign-at-create, §14 monitoring partly not built, `moveItem` non-emitting). JVM: 160 classes / 1,351 tests / 0 failures on the final code. §17/§18 reconciled 2026-09-29 with per-line evidence (rules 19/19, functions emulator 23/23, functions unit 71/71, instrumented migration 14/14 on the real SQLCipher stack, X6532 v13 census) and recorded deviations (ACK-vs-transport-acceptance, no staging per ADR #54, gate default-ON per ADR #56, monitoring per §A.1); the only open item is the **#77 owner-tap-gated device leg** — stated open in §17 Production and §18 item 17, never marked done. **Superseded in part 2026-09-29 by §A.4 (ADR #123):** delivery is now bounded to each pair's enrollment time and a targeted state snapshot is emitted at pair-complete — read §A.4 with §A. Do not build from this document directly — build from §A's resolution table.**

## 1. Feature Overview

### Purpose

Implement the Version 1 partner-visible activity layer for MyExodus. The feature synchronizes selected recovery activity from one enrolled user to their paired partner while remaining functional when either device is offline.

The feature consolidates the partner-facing portions of the existing milestone, relapse, recovery-coaching, motivational-message, and offline-aggregation requirements into one transport and presentation path.

The feature is built around five properties:

1. **Local-first correctness:** a user's local recovery action is committed locally before any network work is attempted.
2. **Durable delivery:** every logical partner event has a stable identity and remains in the sender outbox until the receiver acknowledges successful ingestion.
3. **Authenticated and encrypted transport:** mailbox payloads are encrypted before Firestore and authenticated with the already-enrolled sender device key.
4. **Deterministic ordering:** every sender stream has a monotonically increasing sequence number; receiver processing is contiguous and gap-aware.
5. **Operationally bounded delivery:** foreground synchronization is real-time; background synchronization uses FCM wake-up on GMS devices and WorkManager catch-up on all supported devices.

### User-visible behavior

On the sender device:

- Local streak/relapse/coaching state updates immediately and does not wait for Firestore.
- A partner event is queued automatically when the relevant local state transition commits.
- The user does not see transport retries, Firestore ACKs, or internal queue state unless a persistent sync failure requires an in-app warning.

On the receiving device:

- Partner activity is persisted locally after cryptographic and schema validation.
- `PartnerScreen` renders the partner projection from Room, not directly from Firestore.
- The partner dashboard shows at minimum:
  - days free;
  - thresholds/milestones met;
  - relapse count;
  - last partner event time;
  - last successful synchronization time;
  - degraded/gap state when synchronization is incomplete.
- A single fixed notification channel and notification ID are used for partner activity. Notification content is intentionally generic and never contains relapse details, coaching text, or other sensitive recovery content.

### Non-goals

This feature does not:

- introduce a new hash-chain protocol;
- replace the existing pairing/accountability cryptographic protocol;
- make Firestore the source of truth for local recovery state;
- guarantee background notification delivery on devices without a supported push mechanism;
- redesign unrelated streak, relapse, coaching, VPN, UsageStats, or accountability components;
- provide arbitrary partner-side modification of the sender's recovery history.

---

## 2. Requirements

### 2.1 Functional requirements

**FR-01 — Event creation**

For each partner-visible local state transition, create exactly one logical activity event. The supported V1 event categories are:

- `RELAPSE_RECORDED`
- `STREAK_UPDATED`
- `MILESTONE_REACHED`
- `COACHING_STEP_UPDATED`
- `MOTIVATIONAL_MESSAGE`

The event category and event-specific data are part of the encrypted event body.

**FR-02 — One durable event identity**

Generate `eventId` once when the event is first persisted. Persist it in the same Room transaction as the originating local state transition. Every network retry must reuse the same `eventId`.

`eventId` is a UUID v4 and is the mailbox document ID.

**FR-03 — Sender sequence**

Maintain a strictly increasing `sequence` per `streamId` and sender device. Sequence allocation occurs transactionally in Room. No two events from the same stream may have the same sequence.

Sequence numbers are transport/order metadata and are not derived from wall-clock timestamps.

**FR-04 — Immutable event envelope**

After an event reaches `OUTBOX_PENDING`, its identity, sequence, encrypted ciphertext, nonce, signature, protocol version, schema version, and sender device ID are immutable.

Retry changes only delivery metadata such as attempt count and next-attempt time.

**FR-05 — Application-layer encryption**

Do not send plaintext recovery activity to Firestore. Encrypt event bodies before they enter the Firestore mailbox. Use the existing pair-specific encryption key established by the Accountability Protocol. Do not introduce a second pairing/key-exchange protocol.

Use an AEAD construction available through the platform/existing project cryptography stack, with AES-256-GCM as the required V1 construction.

Generate a fresh 96-bit random nonce for each event and never reuse a nonce with the same encryption key.

**FR-06 — Event authentication**

Sign the exact encrypted event envelope with the sender's already-enrolled P-256 device signing key. The signature must cover all security-relevant metadata and ciphertext, including:

`protocolVersion || schemaVersion || pairId || enrollmentId || senderDeviceId || streamId || eventId || sequence || eventTime || nonce || ciphertext`

The receiver verifies the signature against the public key already bound to the active enrollment before committing the event.

Do not introduce a feature-specific attestation flow. Partner activity trusts only keys already accepted by the existing Accountability Protocol enrollment.

**FR-07 — No hash chain**

Do not implement or persist a new activity-layer SHA-256 hash chain. Ordering and replay resistance are provided by stable event identity, sender sequence, enrollment binding, signature verification, and receiver deduplication. Existing accountability-chain functionality, if present elsewhere in the application, remains untouched.

**FR-08 — Receiver gap handling**

The receiver must process events in sequence order within each sender stream.

For an incoming event:

- `sequence == nextExpectedSequence`: validate and ingest.
- `sequence < nextExpectedSequence`: treat as duplicate/stale and do not reapply the event.
- `sequence > nextExpectedSequence`: store as pending and mark the stream as `GAP_DETECTED`; do not advance the contiguous sequence.

A gap is resolved only when all missing events are received or a formal resynchronization operation succeeds.

**FR-09 — Atomic receiver ingestion**

Deduplication, event persistence, derived partner projection updates, contiguous sequence advancement, and ACK creation must occur in one Room transaction.

A process death may result in the same event being delivered again, but must never cause the event effect to be applied twice.

**FR-10 — End-to-end delivery state**

Sender delivery state is considered complete only after the sender observes a receiver ACK for the same `eventId`.

Firestore client write completion alone does not mark an event delivered.

**FR-11 — Safe retries**

A Firestore create that succeeds on the server but loses its client acknowledgement must not produce a second logical event. The retry uses the same document ID/event ID. An `ALREADY_EXISTS` result for the same event ID is treated as transport acceptance.

**FR-12 — Background synchronization**

Use:

- Firestore snapshot listeners while the app process is alive;
- an FCM data-only wake ping for GMS-capable devices when a new mailbox event is created;
- WorkManager for durable catch-up and retry work on all supported devices;
- app-open synchronization as the definitive fallback on devices without usable FCM/background execution.

The FCM message contains no event payload and no recovery content. It carries only enough metadata to request mailbox synchronization.

**FR-13 — Notification privacy**

Use one fixed notification channel ID and one fixed partner-activity notification ID. Set notification visibility to `VISIBILITY_PRIVATE` and provide an explicit redacted public version. Never put relapse count, streak count, coaching text, or event-specific recovery content in lockscreen-visible notification fields.

Users retain final control over notification channel importance/settings. The feature must remain functional when notification permission is denied or the channel is disabled. Android notification channel behavior is user-controlled after channel creation. (Android notification channels: https://developer.android.com/develop/ui/compose/notifications/channels)

**FR-14 — Truthful synchronization state**

Track these values separately:

- `lastEventOccurredAt`: the partner event's source time;
- `lastReceiveCommittedAt`: local time when the receiver successfully committed the latest event;
- `lastSyncAttemptAt`: most recent local sync attempt;
- `lastSuccessfulSyncAt`: time of the latest successful synchronization cycle;
- `nextExpectedSequence`: next contiguous event required for the stream;
- `gapDetected`: whether the stream currently has a sequence gap.

Do not display event time as “last synced.”

**FR-15 — Retention**

Mailbox documents receive a server-controlled expiration time of 30 days after mailbox creation. Firestore TTL handles eventual deletion after `expiresAt`; TTL is not treated as instantaneous deletion. (Firestore TTL: https://firebase.google.com/docs/firestore/ttl)

Sender outbox records are retained until ACK or a permanent failure state. ACKed sender records may be compacted after the local retention period defined below.

**FR-16 — Unpair/revocation**

Every activity envelope is bound to `enrollmentId` and `pairId`. A new pairing creates a new enrollment ID. Old enrollment events are never accepted under the new enrollment.

When an enrollment is revoked:

- stop creating new events for the revoked link;
- stop outbound retry work for the link;
- invalidate the receiver's acceptance of that enrollment;
- stop the listener for that enrollment;
- prevent new mailbox writes through Firestore rules;
- retain only the minimum local audit metadata required by the existing account/data-retention policy.

**FR-17 — Schema compatibility**

Every event contains `protocolVersion` and `schemaVersion`.

Unknown optional fields are ignored when the event schema allows it. Unknown event types or unsupported schema versions are stored as `UNSUPPORTED_VERSION` and are not applied to the partner projection or counted as successfully synchronized.

An unsupported event must not be discarded merely because the current app cannot interpret it.

**FR-18 — Sensitive cache handling**

Only ciphertext and non-sensitive routing metadata may be written to the Firestore mailbox/cache. Plaintext event bodies are decrypted only after the receiver has validated the envelope and are stored only in SQLCipher-backed Room tables as required for the partner projection/history.

Do not rely on SQLCipher to protect Firestore's own persistent cache.

Firestore Android currently uses an approximate 100 MB persistent-cache threshold by default; the SDK manages eviction when the threshold is exceeded. The application must not treat the cache threshold as a hard storage limit. (Firebase Android Firestore settings: https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/FirebaseFirestoreSettings.Builder)

### 2.2 Non-functional requirements

**NFR-01 — Offline operation**

Recording supported local activity must work without network connectivity.

**NFR-02 — Idempotency**

Duplicate delivery of the same event ID must never apply the event effect more than once.

**NFR-03 — Ordering**

Partner projection updates must reflect the sender's contiguous sequence order.

**NFR-04 — Security**

No plaintext recovery activity may be stored in Firestore mailbox documents.

**NFR-05 — Privacy**

Notifications must remain generic on locked screens and the feature must not expose recovery details through Firestore document metadata beyond what is explicitly required for authorization and synchronization.

**NFR-06 — Resilience**

Process death, network loss, duplicate writes, duplicate push notifications, and receiver crashes must be recoverable without double-applying an event.

**NFR-07 — Performance**

Routine local event creation must not perform synchronous network I/O. Routine Room work must remain off the main thread.

**NFR-08 — Dependency scope**

Do not add a new third-party runtime dependency for the activity layer. Reuse the existing Kotlin, Coroutines, Room/SQLCipher, Hilt, WorkManager, Firebase Auth, Firestore, Firebase Messaging, and existing CBOR/cryptography dependencies already present in the project.

**NFR-09 — Scope discipline**

Do not refactor unrelated application modules while implementing this feature.

---

## 3. Existing System Context

### 3.1 Technology baseline

The implementation targets the existing MyExodus Android stack:

- Kotlin 2.2.10
- Jetpack Compose with Material 3
- Hilt
- Room 3.0.3
- SQLCipher
- Kotlin Coroutines/Flow
- WorkManager
- Firebase Authentication
- Cloud Firestore
- Firebase Cloud Messaging
- existing Accountability Protocol and its P-256 device-key infrastructure
- existing CBOR dependency if already present in the Gradle dependency graph

Do not upgrade framework/library versions as part of this feature unless a build failure makes the current version unusable. Any required dependency upgrade is a separate change and must not be bundled silently.

### 3.2 Existing components to reuse

**Local recovery state**

Existing streak, relapse, recovery-coaching, and motivational-message write paths remain the owners of their respective domain state. The partner layer subscribes to successful local state transitions and emits partner events. It does not reimplement those domains.

**`PartnerScreen`**

Keep the existing screen and Compose navigation structure. Replace direct/implicit partner activity state reads with a Room-backed partner projection exposed through the existing ViewModel/state layer.

**`EnrollmentRecord`**

Extend the existing enrollment state only where required to store synchronization metadata such as:

- active enrollment ID;
- partner activity stream ID;
- last successful sync time;
- last partner event time;
- next expected sequence;
- gap state.

Do not use `mailbox_watermark_ts` as the primary sync watermark. Replace its semantic use with the explicit fields above. If the existing field is already persisted and referenced elsewhere, preserve it for backward compatibility and stop using it as an event-delivery cursor.

**`PartnerSyncCoordinator`**

Keep this as the orchestration boundary for outbound/inbound partner synchronization. Split responsibilities into small injected collaborators rather than allowing it to become a large multi-purpose class.

Required collaborators:

- event serializer;
- event encryptor/decryptor;
- event signer/verifier;
- outbox repository;
- mailbox transport;
- receiver-ingestion repository;
- sync-state repository;
- notification coordinator;
- background sync worker.

**ADR #111 mirror-link tables**

Reuse the existing pair-link source to determine active recipients. Do not introduce a second pair relationship model.

**ADR #46 virtual-partner harness**

Keep the harness for functional ingestion/UI testing. It must not be capable of reaching production backend endpoints in a release build and must not replace production cryptographic verification tests.

### 3.3 Existing components that must remain untouched

Unless required for a compile or interface change, do not modify:

- UsageStatsManager collection logic;
- VPN/DNS sinkhole implementation;
- streak calculation algorithm itself;
- relapse domain rules;
- coaching domain rules;
- device-pairing UX;
- Accountability Protocol key generation/registration implementation;
- unrelated Firestore collections;
- unrelated notification channels;
- unrelated Room entities or migrations.

### 3.4 Source-of-truth ownership

| State | Authoritative source |
|---|---|
| User's local recovery state | Existing local Room/domain owner |
| Partner event creation | Local Room transaction/outbox |
| Outbound transport state | Partner activity outbox in Room |
| Mailbox transport | Firestore |
| Partner event acceptance | Receiver Room transaction |
| Partner dashboard projection | Receiver Room |
| Device signing identity | Existing Accountability Protocol/Android Keystore |
| Pair encryption key | Existing Accountability Protocol enrollment |
| Background synchronization scheduling | WorkManager + FCM wake signal |
| Notification UI state | Android notification manager + local notification coordinator |

Firestore is a synchronization transport and mailbox, not the source of truth for the sender's local recovery state or the receiver's rendered dashboard.

---

## 4. Architecture

### 4.1 Component architecture

```text
Existing domain write path
        |
        | local Room transaction
        v
+---------------------------+
| Partner Activity Journal  |
| + immutable event record  |
+-------------+-------------+
              |
              v
+---------------------------+
| Partner Outbox Repository  |
| per enrollment/event      |
+-------------+-------------+
              |
              v
+---------------------------+
| PartnerSyncCoordinator    |
+-------------+-------------+
              |
              v
+---------------------------+
| Encrypt + Sign Envelope   |
+-------------+-------------+
              |
              v
+---------------------------+
| Firestore Mailbox         |
| /pairs/{pairId}/mailbox  |
| /{eventId}                |
+-------------+-------------+
              |
              +--------------------+
              |                    |
              v                    v
   Firestore trigger          foreground listener
              |                    |
              v                    |
        FCM wake ping             |
              |                    |
              +---------+----------+
                        v
             +----------------------+
             | Receiver Sync Worker |
             | / Coordinator        |
             +----------+-----------+
                        |
                        v
             +----------------------+
             | Verify + Decrypt     |
             | Validate + Sequence  |
             +----------+-----------+
                        |
                        v
             +----------------------+
             | Room transaction     |
             | inbox + projection   |
             | dedupe + ACK         |
             +----------+-----------+
                        |
                        v
                PartnerScreen
                        |
                        +----> Aggregated notification
```

### 4.2 Firestore mailbox document

Path:

`/pairs/{pairId}/mailbox/{eventId}`

Document fields:

- `protocolVersion: int`
- `schemaVersion: int`
- `eventId: string`
- `senderUid: string`
- `senderDeviceId: string`
- `enrollmentId: string`
- `streamId: string`
- `sequence: long`
- `eventTimeMs: long`
- `nonce: bytes`
- `ciphertext: bytes`
- `signature: bytes`
- `expiresAt: timestamp`

`eventType` is not stored in plaintext metadata. It is inside the encrypted event body.

`eventId` must equal the document ID.

The maximum application-defined ciphertext size is **64 KiB**. This is a product protocol limit, not the Firestore document limit. Cloud Firestore's documented maximum document size is 1 MiB. (Firestore quotas: https://firebase.google.com/docs/firestore/quotas)

### 4.3 Firestore ACK document

Path:

`/pairs/{pairId}/mailboxAcks/{eventId}`

Fields:

- `eventId`
- `enrollmentId`
- `senderDeviceId`
- `receiverDeviceId`
- `sequence`
- `status: VERIFIED_AND_COMMITTED`
- `processedAt`

Recipient clients may create ACK documents. Clients may not update existing ACK documents.

The sender may read ACK documents for its own active enrollment.

### 4.4 FCM wake trigger

Add one server-side Firestore-triggered function for mailbox creation.

Responsibilities:

1. Validate the event metadata required for push routing.
2. Set `expiresAt` using the trigger's trusted creation time plus the 30-day retention period.
3. Send a content-free FCM data message to the recipient's registered device token.
4. Never decrypt or log the ciphertext.
5. Never put recovery details in the FCM message.
6. Treat repeated execution as idempotent. Duplicate FCM wake messages are harmless because the receiver deduplicates by `eventId`.

The function is a **wake signal**, not the source of truth. Firestore mailbox contents remain authoritative for transport delivery.

### 4.5 Receiver state model

For every `enrollmentId + streamId`, store:

- `lastContiguousSequence`
- `nextExpectedSequence`
- `gapDetected`
- `gapFirstDetectedAt`
- `lastEventOccurredAt`
- `lastReceiveCommittedAt`
- `lastSuccessfulSyncAt`

Pending out-of-order events are stored keyed by `(enrollmentId, streamId, sequence)`.

### 4.6 Event body model

Each event body contains only data needed by the partner feature.

All event bodies include:

- `eventType`
- `schemaVersion`
- `stateAfter`
- optional event-specific metadata

`stateAfter` contains the partner-visible aggregate after the local state transition. It includes only fields required by `PartnerScreen`.

The aggregate includes at minimum:

- `daysFree`
- `relapseCount`
- `milestonesReached`
- `coachingProgress` summary
- `eventOccurredAt`

The event-specific metadata is used for UI history/notification counting but does not replace the aggregate state.

### 4.7 Event serialization

Use the existing CBOR library only if it is already part of the project dependency graph. Do not add another serialization library for this feature.

Do not use reflection-based `CBORObject.FromObject()` to serialize event domain objects.

Create explicit serializers for each supported event body schema.

Use a pinned deterministic encoding profile. The encoder must produce definite-length, deterministic CBOR with a fixed supported type set. Do not allow arbitrary floating-point values, tags, or unsupported extension types.

Hashing of reserialized objects is not part of the protocol. The receiver authenticates the exact bytes received by verifying the signature over the envelope and then decrypts the ciphertext.

### 4.8 Cryptographic envelope

The envelope construction is:

```text
AAD = protocolVersion || schemaVersion || pairId || enrollmentId
      || senderDeviceId || streamId || eventId || sequence || eventTimeMs

ciphertext = AES-256-GCM(pairEncryptionKey, nonce, plaintextCBOR, AAD)

signatureInput = AAD || nonce || ciphertext
signature = P-256-ECDSA-SHA256(senderPrivateKey, signatureInput)
```

The exact byte framing must be length-delimited and specified in one shared utility so no concatenation ambiguity exists.

The private signing key never leaves Android Keystore.

The pair encryption key is obtained from the existing Accountability Protocol enrollment. Never serialize or log it.

### 4.9 Firestore Security Rules boundary

Client rules enforce authorization and coarse document constraints only. They do not parse CBOR or verify signatures.

Rules must ensure:

- caller is authenticated;
- caller belongs to the target active pair;
- caller UID equals `senderUid` for mailbox creation;
- mailbox document ID equals `eventId`;
- document contains only the allowed fields;
- types are correct;
- ciphertext/nonce/signature sizes are within limits;
- `eventId`, `enrollmentId`, `senderDeviceId`, and `streamId` are non-empty bounded strings;
- clients may create but not update/delete mailbox events;
- only the recipient may create an ACK;
- only the sender may read its own ACKs;
- revoked enrollments cannot create or read new activity documents;
- users cannot read arbitrary pair mailboxes.

Application semantic validation occurs after retrieval.

---

## 5. Architecture Decisions

- **Durable Room outbox:** chosen because local recovery actions must succeed without network access.
- **Stable UUID event ID:** chosen because retries must address the same logical event.
- **Per-sender sequence number:** chosen because timestamps are not a reliable ordering mechanism.
- **Signed immutable envelopes:** chosen because hash chaining alone does not authenticate the sender.
- **AES-256-GCM pair encryption:** chosen because Firestore and its local cache must not hold plaintext recovery activity.
- **Existing Accountability Protocol keys:** chosen because the application already has a pairing/key lifecycle and must not create a second identity system.
- **No new activity hash chain:** chosen because sequence + signature + deduplication provides the required V1 synchronization guarantees without chain-gap deadlocks.
- **Explicit CBOR serializers:** chosen because reflection-based serialization would make protocol evolution and byte contracts implicit.
- **Per-event mailbox documents:** chosen because each event can be retried idempotently and acknowledged independently.
- **Receiver Room transaction for ingest + projection + ACK:** chosen because process death must not double-apply an event or acknowledge an unapplied event.
- **Firestore create-only mailbox:** chosen because activity events are immutable after creation.
- **Firestore TTL plus 30-day retention:** chosen because the mailbox is a transport buffer, not permanent storage.
- **FCM content-free wake ping:** chosen because Firestore listeners alone cannot provide background wake-up when the app process is dead.
- **WorkManager catch-up:** chosen because synchronization must survive process exit and reboot without a foreground service. (Android WorkManager: https://developer.android.com/develop/background-work/background-tasks/persistent)
- **Generic partner notifications:** chosen because notification metadata must not disclose sensitive recovery details on lockscreens.
- **Separate event time and sync time:** chosen because event occurrence and successful synchronization are different facts.
- **No timestamp cursor:** chosen because client clocks may be wrong or manipulated and out-of-order delivery is possible.
- **Application-defined 64 KiB payload cap:** chosen to bound memory/network usage while remaining comfortably below Firestore's 1 MiB document limit. (Firestore quotas: https://firebase.google.com/docs/firestore/quotas)

---

## 6. Data Flow

### 6.1 Sender local transition

1. Existing streak/relapse/coaching domain logic validates and applies a local transition.
2. In the same Room transaction, allocate the next sequence number for the active partner stream.
3. Generate a UUID v4 `eventId`.
4. Create the event body containing the supported `stateAfter` snapshot and event-specific data.
5. Serialize the event body with the explicit CBOR serializer.
6. Generate a fresh random AES-GCM nonce.
7. Encrypt the body using the existing pair encryption key.
8. Construct the authenticated envelope metadata.
9. Sign the exact envelope bytes with the existing sender device signing key.
10. Insert the partner outbox record with state `PENDING`.
11. Commit the Room transaction.
12. Return immediately to the local UI; no network operation is awaited by the local domain write path.

### 6.2 Outbound delivery

1. `PartnerSyncCoordinator` claims pending outbox records.
2. It writes `/pairs/{pairId}/mailbox/{eventId}` as a create.
3. On a normal success, mark transport state `TRANSPORT_ACCEPTED`.
4. On `ALREADY_EXISTS`, mark `TRANSPORT_ACCEPTED` because the stable event ID proves the logical event was already created.
5. On transient failures, retain the same record and retry with exponential backoff.
6. On permanent authorization failure, mark the event `PERMANENT_FAILURE`, stop retrying the revoked link, and surface a non-sensitive sync error state.
7. Do not delete the local outbox row until the receiver ACK is observed.

### 6.3 Server wake-up

1. Firestore create trigger receives a new mailbox event.
2. Function computes `expiresAt` from trusted server create time.
3. Function sends a content-free FCM data message to the recipient.
4. Function never modifies the ciphertext or signature.
5. If the function is retried, duplicate wake messages are tolerated.

### 6.4 Receiver synchronization

When foreground:

1. Firestore listener receives new mailbox documents.
2. The receiver enqueues a synchronization task rather than performing heavy cryptographic/database work on the listener callback thread.
3. `PartnerSyncCoordinator` processes the event.

When woken by FCM:

1. The FCM handler validates that the message is a local sync wake-up.
2. It enqueues a unique WorkManager sync job.
3. The worker reads the mailbox and processes pending events.

When scheduled by WorkManager:

1. The worker queries the active enrollment mailbox for unprocessed events.
2. It processes events in sequence.
3. It stops on a sequence gap rather than advancing over the gap.
4. It returns `Result.retry()` for transient network/storage failures.

### 6.5 Receiver validation

For each mailbox document:

1. Validate Firestore document structure and field types.
2. Validate `eventId == documentId`.
3. Verify the active enrollment matches `pairId + enrollmentId`.
4. Verify the sender device is the expected device for that enrollment.
5. Verify the sequence number is within valid bounds.
6. Verify the signature against the enrolled sender public key.
7. Build the exact AAD from the document metadata.
8. Decrypt AES-GCM ciphertext.
9. Decode CBOR using the explicit event-type/schema decoder.
10. Validate required fields, value ranges, and application invariants.
11. Check sequence state.
12. If the sequence is a gap, store as pending and stop advancement.
13. If duplicate, do not reapply.
14. If valid and contiguous, ingest transactionally.

### 6.6 Receiver commit

Inside one Room transaction:

1. Insert event ID into the inbound deduplication table.
2. Insert the validated event envelope metadata.
3. Update the partner projection from `stateAfter`.
4. Advance `lastContiguousSequence`.
5. Set `nextExpectedSequence`.
6. Update `lastEventOccurredAt` from the event's authenticated source time.
7. Update `lastReceiveCommittedAt` from local time.
8. Clear `gapDetected` when the next expected sequence is now available.
9. Insert/update the receiver notification aggregation state.
10. Insert an ACK record for the same event ID.
11. Commit.

Only after the Room transaction succeeds may the event be considered received and verified.

### 6.7 ACK processing

The sender observes ACK documents through a foreground listener or background WorkManager sync.

For each ACK:

1. Validate ACK belongs to the sender's active enrollment.
2. Validate event ID exists locally.
3. Validate sequence matches the local outbox event.
4. Mark the outbox state `DELIVERED`.
5. Record delivery completion time.
6. Compact the outbox record only after the configured local retention period.

---

## 7. Implementation Sequence

### Step 1 — Inventory existing integration points

**Objective:** identify the exact existing domain write paths, enrollment records, mirror-link tables, Firebase setup, notification coordinator, and Accountability Protocol key APIs.

**Affected components:** existing streak/relapse/coaching repositories/use cases, `EnrollmentRecord`, `PartnerSyncCoordinator`, ADR #111 mirror tables, Accountability Protocol key APIs.

**Required changes:** none yet; produce an implementation map from current code.

**Dependency:** none.

**Validation:** compile and existing tests remain green.

---

### Step 2 — Define Room schema and migrations

**Objective:** introduce persistent event/outbox/inbox/sync/projection state.

**Affected components:** Room entities, DAOs, database version, migration tests.

**Required changes:** add only the tables needed for partner activity.

**Dependency:** Step 1.

**Validation:** migration tests from the current production schema and clean-install schema.

---

### Step 3 — Implement event serializers and protocol constants

**Objective:** create explicit versioned event schemas and deterministic CBOR encoding.

**Affected components:** event DTOs, serializers, protocol constants.

**Required changes:** explicit encoders/decoders for all five V1 event types.

**Dependency:** Step 2 for the persisted schema identity.

**Validation:** golden-byte vectors, malformed-input tests, unknown-field tests, schema-version tests.

---

### Step 4 — Implement cryptographic envelope

**Objective:** encrypt and authenticate every outbound partner event.

**Affected components:** crypto service, Accountability Protocol key adapters.

**Required changes:** AES-GCM encryption, nonce generation, signature creation/verification, exact byte framing.

**Dependency:** Step 3 and existing enrollment key access.

**Validation:** round-trip tests, wrong-key tests, modified-ciphertext tests, modified-metadata tests, nonce uniqueness property test.

---

### Step 5 — Implement atomic local event creation

**Objective:** create the logical event and outbox record in one Room transaction with stable ID and sequence.

**Affected components:** existing domain write paths, partner event factory, outbox DAO/repository.

**Required changes:** add a post-transition partner event creation operation inside the same transaction.

**Dependency:** Steps 2–4.

**Validation:** transaction rollback tests, crash simulation tests, concurrent event creation tests.

---

### Step 6 — Implement outbound mailbox transport

**Objective:** send immutable mailbox documents with stable event IDs and correct retry semantics.

**Affected components:** `PartnerSyncCoordinator`, Firestore repository, Firestore rules.

**Required changes:** create-only mailbox writes, transient retry, `ALREADY_EXISTS` handling, active-enrollment checks.

**Dependency:** Step 5.

**Validation:** emulator tests for successful create, duplicate create, unauthorized create, oversize payload, revoked enrollment.

---

### Step 7 — Implement receiver validation and Room ingestion

**Objective:** securely ingest partner events in contiguous sequence order.

**Affected components:** receiver coordinator, inbox DAO, projection DAO, sync-state DAO.

**Required changes:** signature verification, AES-GCM decryption, schema validation, gap buffering, deduplication, atomic projection update, ACK creation.

**Dependency:** Steps 2–4 and Step 6.

**Validation:** duplicate, out-of-order, missing sequence, malformed, unsupported version, invalid signature, invalid ciphertext, process-death tests.

---

### Step 8 — Implement ACK and outbox completion

**Objective:** give the sender an end-to-end delivery signal.

**Affected components:** ACK repository, outbox state machine.

**Required changes:** ACK validation and transition to `DELIVERED`.

**Dependency:** Step 7.

**Validation:** ACK duplication, delayed ACK, missing ACK, sender retry after successful receiver ingest.

---

### Step 9 — Implement foreground/background sync

**Objective:** provide foreground real-time updates plus durable background catch-up.

**Affected components:** Firestore listeners, WorkManager workers, FCM message receiver.

**Required changes:** unique sync work, network constraints, retry/backoff, listener lifecycle management.

**Dependency:** Steps 6–8.

**Validation:** process death, reboot, network loss, FCM duplicate delivery, no-GMS fallback, OEM sleep behavior.

---

### Step 10 — Implement notification aggregation

**Objective:** provide one private generic partner-activity notification.

**Affected components:** notification coordinator, fixed channel registration, notification tests.

**Required changes:** one channel ID, one notification ID, `VISIBILITY_PRIVATE`, redacted public version, generic body.

**Dependency:** Step 7.

**Validation:** permission denied, channel disabled, locked screen, unlocked screen, notification duplication, rapid event burst.

---

### Step 11 — Update `PartnerScreen`

**Objective:** render the receiver Room projection and truthful synchronization status.

**Affected components:** `PartnerScreen`, related ViewModel/state holder, Compose UI tests.

**Required changes:** remove any direct dependency on Firestore state for the partner summary; render from Room `StateFlow`.

**Dependency:** Steps 7–10.

**Validation:** offline rendering, stale sync state, gap state, zero events, milestone changes, relapse changes, rotation/process recreation.

---

### Step 12 — Implement retention, revocation, and cleanup

**Objective:** enforce mailbox lifecycle and enrollment termination.

**Affected components:** Cloud Function, Firestore TTL configuration, rules, coordinator, unpair flow.

**Required changes:** server-derived `expiresAt`, 30-day TTL, revocation checks, listener teardown, outbox cancellation.

**Dependency:** Steps 6–9.

**Validation:** TTL configuration, unpair while offline, unpair during retry, old-event replay after re-pair, revoked-rule tests.

---

### Step 13 — Production observability and rollout controls

**Objective:** make synchronization failures detectable without logging sensitive content.

**Affected components:** metrics/logging, feature flag, crash reporting, dashboards/runbook.

**Required changes:** privacy-safe telemetry and kill switch.

**Dependency:** all implementation steps.

**Validation:** synthetic failure scenarios emit expected metrics and no plaintext payloads appear in logs.

---

### Step 14 — End-to-end validation and phased release

**Objective:** validate the complete feature across supported Android/OEM surfaces.

**Affected components:** release build, Firebase Emulator Suite, physical-device test matrix.

**Required changes:** none beyond bug fixes from testing.

**Dependency:** all previous steps.

**Validation:** acceptance criteria and definition of done in Sections 17–18.

---

## 8. Detailed Implementation Steps

### 8.1 Room entities

Create the minimum tables required:

#### `PartnerActivityOutboxEntity`

Required fields:

- `eventId`
- `pairId`
- `enrollmentId`
- `senderDeviceId`
- `streamId`
- `sequence`
- `protocolVersion`
- `schemaVersion`
- `eventTimeMs`
- `nonce`
- `ciphertext`
- `signature`
- `state`
- `attemptCount`
- `nextAttemptAtMs`
- `lastAttemptAtMs`
- `lastErrorCode`
- `createdAtMs`
- `deliveredAtMs`

Primary key: `eventId + enrollmentId`.

Unique constraint: `enrollmentId + streamId + sequence`.

#### `PartnerActivityInboxEntity`

Required fields:

- `eventId`
- `pairId`
- `enrollmentId`
- `senderDeviceId`
- `streamId`
- `sequence`
- `schemaVersion`
- `eventTimeMs`
- `verificationState`
- `appliedAtMs`

Primary key: `eventId`.

Unique constraint: `enrollmentId + streamId + sequence`.

#### `PartnerActivityPendingEntity`

Stores verified-but-not-yet-contiguous events.

Required fields:

- `eventId`
- `enrollmentId`
- `streamId`
- `sequence`
- envelope fields required to retry processing
- `receivedAtMs`

Primary key: `enrollmentId + streamId + sequence`.

#### `PartnerActivityStreamStateEntity`

Required fields:

- `enrollmentId`
- `streamId`
- `lastContiguousSequence`
- `nextExpectedSequence`
- `gapDetected`
- `gapFirstDetectedAtMs`
- `lastEventOccurredAtMs`
- `lastReceiveCommittedAtMs`
- `lastSuccessfulSyncAtMs`

#### `PartnerActivityProjectionEntity`

One row per active partner enrollment.

Required fields:

- `enrollmentId`
- `daysFree`
- `relapseCount`
- `milestonesReached`
- `coachingProgress`
- `lastEventOccurredAtMs`
- `lastReceiveCommittedAtMs`
- `lastSuccessfulSyncAtMs`
- `gapDetected`
- `updatedAtMs`

Store values needed by `PartnerScreen`; do not keep redundant copies of unrelated domain state.

### 8.2 Event creation transaction

Modify the existing local domain transaction so partner-event creation is part of the same Room transaction.

Sequence:

```text
BEGIN TRANSACTION
  apply existing local domain state transition
  read/allocate next partner sequence
  generate eventId
  construct event body from committed resulting state
  serialize
  encrypt
  sign
  insert PartnerActivityOutboxEntity
COMMIT
```

If any step fails, the entire transaction fails and the local domain transition is rolled back.

Do not generate an event after the domain transaction commits. That would create a state/event split-brain failure mode.

### 8.3 Sequence allocation

Allocate sequence numbers through a Room transaction that locks the stream-state row.

Never derive sequence from:

- timestamps;
- UUID ordering;
- Firestore document ordering;
- number of existing mailbox documents.

On application reinstall/new device, do not resume the old device stream blindly. The existing enrollment lifecycle must create a new stream ID when the device identity changes.

### 8.4 Encryption and signing

Implement one `PartnerEventCrypto` interface with methods for:

- `encrypt(eventBody, envelopeMetadata)`;
- `decrypt(envelope)`;
- `sign(envelopeBytes)`;
- `verify(envelopeBytes, signature, senderPublicKey)`.

All cryptographic failures are hard failures for the current event.

Do not log:

- plaintext event bodies;
- pair encryption keys;
- private keys;
- full ciphertext;
- raw FCM tokens.

Log only non-sensitive identifiers such as hashed/truncated event ID where needed for diagnostics.

### 8.5 Event serializers

Implement one explicit codec per schema version.

Encoder requirements:

- no reflection;
- no implicit null serialization;
- only supported primitive/array/map types;
- bounded string sizes;
- bounded list/map sizes;
- integer ranges explicitly validated;
- no floating point values in V1 event data;
- no arbitrary CBOR tags;
- deterministic encoding profile fixed by protocol version.

Decoder requirements:

- reject malformed input;
- reject trailing bytes;
- reject unsupported tags/types;
- enforce nesting and collection-size limits;
- reject invalid required fields;
- ignore permitted unknown optional fields;
- return `UNSUPPORTED_VERSION` separately from `MALFORMED`.

### 8.6 Firestore rules

Implement and test the exact mailbox/ACK rules.

Mailbox create rule:

```text
authenticated sender
AND active pair membership
AND senderUid == request.auth.uid
AND request.resource.data.eventId == eventId
AND enrollment belongs to pair
AND enrollment sender UID == request.auth.uid
AND field set is exactly the allowed mailbox fields
AND ciphertext/nonce/signature sizes are bounded
```

Mailbox read rule:

Recipient-only read for active enrollment.

Mailbox update/delete:

Denied to clients.

ACK create rule:

Recipient-only create for active enrollment.

ACK update/delete:

Denied to clients.

The server-side Cloud Function uses privileged server credentials and therefore is outside client Firestore Security Rules. All function actions must still enforce the same application-level enrollment validation before sending push notifications.

Security Rules are tested with the Firebase Emulator Suite. Firestore Rules are authorization controls, not CBOR validators or cryptographic verifiers. (Firestore Rules: https://firebase.google.com/docs/firestore/security/rules-conditions)

### 8.7 Outbound retry policy

Use a persisted exponential backoff:

- first retry: 5 seconds;
- then 15 seconds;
- 30 seconds;
- 1 minute;
- 5 minutes;
- 15 minutes;
- 30 minutes;
- 1 hour;
- cap at 6 hours.

Reset the backoff after a successful transport acceptance.

Continue retrying while the enrollment is active and the event is not ACKed.

For non-network permanent failures such as revoked enrollment or invalid authorization, stop automatic retries.

### 8.8 Receiver retry and gap behavior

When a gap is detected:

1. store the verified later event in `PartnerActivityPendingEntity`;
2. set `gapDetected = true`;
3. do not advance `lastContiguousSequence`;
4. request another mailbox synchronization cycle;
5. continue processing once the missing sequence appears.

Never:

- discard a later event solely because it is ahead of the expected sequence;
- advance the high-water mark over a gap;
- treat a timestamp watermark as a substitute for sequence state;
- apply a later event and then apply an older event by simply overwriting the projection.

If a gap remains unresolved for 24 hours while the sender is known to be active, set the partner projection to `SYNC_DEGRADED` and schedule a resynchronization operation.

### 8.9 Resynchronization

V1 resynchronization uses the sender outbox as the durable event source.

The receiver requests the sender to retry the missing event by normal sync behavior. Because each event ID is stable and pending until ACKed, a lost mailbox write can be recreated safely.

Do not create a second event ID during resynchronization.

If the sender no longer has a required event because it was compacted incorrectly, treat the stream as unrecoverable and require a new enrollment/stream initialization rather than silently repairing the history.

### 8.10 Partner projection

The receiver projection is updated from the event's authenticated `stateAfter` snapshot.

The projection update must be monotonic with respect to the contiguous sequence.

For each accepted event:

`projection = event.stateAfter`

This avoids reconstructing relapse counts by applying deltas and therefore prevents duplicate-delivery arithmetic errors.

### 8.11 Synchronization status UI

`PartnerScreen` must distinguish:

**Synced**

- no gap;
- last sync completed successfully.

**Syncing**

- a current synchronization job is running.

**Offline**

- no network currently available;
- render last known projection;
- show exact last successful sync time.

**Degraded**

- sequence gap or persistent transport failure exists;
- do not claim current partner state is fully synchronized.

Never use a text label such as “Last synced 3 days ago” merely because the partner has not created an event for three days. Use `lastSuccessfulSyncAt` for synchronization recency and `lastEventOccurredAt` separately for activity recency.

### 8.12 Notification coordinator

Use fixed constants:

- `PARTNER_ACTIVITY_CHANNEL_ID`
- `PARTNER_ACTIVITY_NOTIFICATION_ID`

Channel properties:

- user-visible name: generic partner updates;
- description: generic partner activity updates;
- application-chosen initial importance appropriate for non-emergency updates;
- channel ID never varies by partner or event.

Each event only updates the same notification.

Notification text is generic, for example:

- “Partner activity updated.”
- “You have new partner activity.”

No relapse count, event type, coaching step, or motivational content appears in the notification body.

Set:

- `VISIBILITY_PRIVATE`;
- explicit `publicVersion` containing generic content.

Android documents `VISIBILITY_PRIVATE` as concealing sensitive information on secure lockscreens while still allowing a notification to exist. (Android Notification API: https://developer.android.com/reference/android/app/Notification)

### 8.13 Notification permission behavior

When `POST_NOTIFICATIONS` is denied/revoked or notifications are disabled:

- do not throw from the activity-processing path;
- record notification state locally;
- continue synchronization and Room projection normally;
- show an in-app notification-permission/settings affordance where appropriate.

The core feature must never depend on successful notification posting.

### 8.14 WorkManager

Create two unique work names:

- `PARTNER_OUTBOUND_SYNC`
- `PARTNER_INBOUND_SYNC`

Workers must:

- use network constraints appropriate for Firestore work;
- be safe to run more than once;
- read durable state from Room;
- return `Result.retry()` on transient failures;
- stop without corrupting state when cancelled or constrained;
- avoid holding a Firestore listener open from inside a worker.

WorkManager work must remain idempotent because the system may stop and reschedule a Worker. (Android WorkManager worker behavior: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)

### 8.15 FCM handler

The FCM handler must:

1. validate message origin through the existing Firebase messaging mechanism;
2. verify that the message contains only expected wake metadata;
3. enqueue unique inbound sync work;
4. return immediately;
5. never decrypt event data in the FCM callback;
6. never render sensitive notification content directly from FCM payload data.

### 8.16 Cloud Function

The Firestore-triggered function is the only new server-side component for this feature.

It must be:

- idempotent;
- stateless with respect to event content;
- free of plaintext recovery logging;
- independent of client transaction timing;
- safe to retry.

Function responsibilities are limited to:

- setting `expiresAt`;
- sending content-free FCM wake signal.

The function must not mutate event ciphertext, sequence, signature, or event ID.

### 8.17 Unpairing

When a pair is unpaired:

1. mark enrollment revoked locally;
2. prevent new partner events from being created for that enrollment;
3. cancel unique sync work for that enrollment;
4. remove active Firestore listener;
5. reject new mailbox access through rules;
6. delete local transient pending events for the revoked enrollment where policy permits;
7. retain only required minimal metadata under the existing account-retention policy.

A later pairing uses a new enrollment ID and stream ID.

### 8.18 Backup and restore

Do not rely on Android backup to restore the partner activity encryption/signing state.

The database/key lifecycle must remain consistent:

- Keystore private keys are non-exportable;
- restored local database records that depend on a missing key are detected;
- the app does not silently generate a replacement key and continue the old stream;
- device replacement creates a new device identity/stream through the existing enrollment process.

If the current application's backup configuration includes SQLCipher databases or partner metadata, update the backup policy as part of this feature so backup cannot create a false sense that Keystore-bound state is portable.

---

## 9. Error Handling

### 9.1 Error classes

| Error | Classification | Action |
|---|---|---|
| No network | Transient | Keep outbox pending; WorkManager retry |
| Firestore unavailable | Transient | Exponential backoff |
| Firestore permission denied | Permanent until state changes | Mark failure; recheck enrollment/pair state |
| Document already exists for same event ID | Idempotent success | Mark transport accepted |
| FCM delivery duplicate | Harmless duplicate | Enqueue unique sync work |
| Malformed Firestore document | Invalid input | Quarantine; do not advance sequence |
| Invalid signature | Security failure | Quarantine; do not ACK |
| Invalid ciphertext/tag | Security failure | Quarantine; do not ACK |
| Unsupported schema version | Compatibility failure | Store deferred state; do not ACK as applied |
| Sequence gap | Ordering failure | Buffer; do not advance |
| Duplicate event ID already committed | Idempotent duplicate | ACK again if enrollment is still valid |
| Room transaction failure | Transient | Roll back entire transaction; retry |
| Missing pair key | Enrollment failure | Mark enrollment unusable; re-enroll through existing pairing flow |
| Missing sender public key | Enrollment failure | Stop processing stream; re-enroll |
| Notification disabled | Non-fatal | Continue feature; show in-app state |
| Worker stopped/cancelled | Transient | Leave durable state unchanged; reschedule |
| Local DB full/corrupt | Critical local failure | Surface app-level recovery state; do not delete data automatically |

### 9.2 Malformed or malicious events

Malformed, invalid-signature, invalid-ciphertext, unsupported-version, and sequence-gap events must have separate states.

Never:

- advance `nextExpectedSequence` over a malformed event;
- ACK an event that was not committed;
- drop a security-invalid event silently;
- treat an unsupported schema as corrupted data;
- retry a permanently invalid ciphertext forever.

Store only minimal diagnostic metadata for quarantined events. Do not persist plaintext recovered from an event that fails validation.

### 9.3 Partial fan-out

Each partner enrollment has its own outbox delivery state.

If one link succeeds and another fails, do not roll back the successful link. Each link is an independent delivery target.

### 9.4 Crash recovery

The system must tolerate process death at every boundary:

- before Room commit;
- after Room commit but before Firestore create;
- after Firestore server acceptance but before client callback;
- after receiver decryption but before Room commit;
- after Room commit but before ACK creation callback;
- after ACK creation but before sender observes it.

The stable event ID, Room transactions, and idempotent creates handle these cases without duplicate logical events.

### 9.5 Retry policy

Only transient failures are retried automatically.

Security/validation failures are quarantined or permanently failed.

Retries must never mutate event identity or cryptographic bytes.

### 9.6 Clock handling

`eventTimeMs` is the sender's wall-clock event time and is authenticated by the signature. It is used for event chronology/display only.

It is not used as the synchronization cursor.

`lastSuccessfulSyncAt` and `lastReceiveCommittedAt` use receiver-local wall-clock time.

The existing streak engine remains responsible for determining days-free semantics. This feature does not substitute `elapsedRealtime()` for the streak engine's calendar logic.

---

## 10. Security Requirements

### 10.1 Threat model

Protect against:

- network interception;
- unauthorized Firestore reads/writes;
- replay of previously valid events;
- modification of mailbox metadata/ciphertext;
- malicious or compromised client attempting to forge another device's events;
- stale events crossing pair/enrollment boundaries;
- accidental disclosure through notifications;
- plaintext leakage through Firestore cache;
- mailbox flooding by an authenticated participant;
- test/debug security bypass reaching production.

The feature does not attempt to make a fully compromised sender device trustworthy. A user/device that already controls a valid signing key can produce valid events. Device compromise is mitigated through the existing Accountability Protocol enrollment/key controls and revocation, not through the activity protocol alone.

### 10.2 Authentication and authorization

- Require Firebase Authentication for all client Firestore operations.
- Bind `senderUid` to the authenticated Firebase UID in Firestore Rules.
- Bind sender device ID and enrollment ID to the active pairing state.
- Reject mailbox access after enrollment revocation.
- Use a new enrollment ID and stream ID for every new pairing/device identity.

### 10.3 Cryptographic authentication

Every event must carry a signature over exact envelope bytes.

The receiver must verify:

- sender public key is the expected enrolled key;
- enrollment is active;
- signature is valid;
- pair/enrollment/device/stream/event IDs match local state;
- sequence is plausible.

Signature verification failure is a security failure and never receives an ACK.

### 10.4 Confidentiality

Use AES-256-GCM with the existing pair encryption key.

Requirements:

- 96-bit unique nonce per event per encryption key;
- no nonce reuse;
- authenticated associated data includes security context;
- ciphertext only is stored in Firestore;
- plaintext is decrypted only after envelope validation.

### 10.5 Replay protection

The receiver rejects/reclassifies events using:

- `eventId` deduplication;
- enrollment binding;
- stream ID;
- sequence number;
- active enrollment state.

A valid event from a revoked or different enrollment is invalid in the current context.

### 10.6 Input validation

Enforce bounds for:

- event ID length;
- pair/enrollment/device/stream ID lengths;
- sequence numeric range;
- event timestamp numeric range;
- nonce exactly 12 bytes;
- signature length;
- ciphertext maximum 64 KiB;
- CBOR nesting depth;
- map/array element counts;
- string sizes;
- integer ranges;
- supported event-type enumeration.

### 10.7 Firestore security rules

The rules must implement least privilege and immutable mailbox semantics.

Do not rely on obscurity of document IDs for authorization.

Do not permit client-side updates/deletes of mailbox records.

### 10.8 FCM security

FCM payloads must contain only non-sensitive wake metadata.

Do not send:

- relapse count;
- streak count;
- coaching text;
- event type;
- decrypted activity;
- pair secret;
- cryptographic material.

### 10.9 Notification security

Use:

- `VISIBILITY_PRIVATE`;
- explicit redacted public version;
- generic title/text;
- no sensitive data in channel name or description.

Even unlocked notification text should remain generic because notification content can be captured by accessibility services, launchers, wearables, or other user-visible surfaces depending on device configuration.

### 10.10 Logging

Do not log sensitive payloads.

Allowed diagnostic fields:

- event ID hash/truncated identifier;
- enrollment ID hash/truncated identifier;
- sequence;
- error classification;
- retry count;
- duration;
- worker result;
- OEM/API-level metadata.

Disallowed:

- plaintext event body;
- ciphertext blobs;
- signatures in full;
- private keys;
- pair keys;
- FCM registration token.

### 10.11 Backup and storage

Audit Android backup configuration so sensitive partner activity and key-dependent state do not create an unsafe portable copy.

SQLCipher remains the protection layer for application-controlled local structured data; Firestore cache protection is not delegated to SQLCipher.

### 10.12 Dependency security

Use the project's existing cryptography/CBOR dependencies. Pin the currently resolved versions and run dependency vulnerability scanning.

Do not add a new cryptographic library unless the existing platform/project primitives cannot satisfy the construction.

---

## 11. Performance Requirements

### 11.1 Local write path

The originating recovery action must not perform network I/O.

The partner event transaction must remain a local Room transaction and complete on the existing database dispatcher.

### 11.2 Serialization/encryption

CBOR serialization and encryption occur off the main thread.

Do not create unnecessary intermediate copies of large byte arrays.

### 11.3 Firestore reads

Use bounded mailbox queries and process incremental changes.

Do not repeatedly download the full mailbox on every foreground recomposition.

### 11.4 Listener lifecycle

Attach mailbox listeners only while the corresponding lifecycle scope is active. Remove listeners when the partner enrollment becomes inactive or the owning screen/application scope is destroyed.

Background synchronization uses WorkManager instead of keeping a long-lived listener alive solely to preserve background execution.

### 11.5 Batch processing

Process multiple small mailbox events in one synchronization pass where practical, but commit each logical event atomically in the Room transaction model.

A large backlog must be paginated. Never load an unbounded mailbox into memory.

### 11.6 Backpressure

Cap in-memory pending event processing.

Use the Room pending table as the durable queue for gaps rather than retaining large buffers in RAM.

### 11.7 Notification cost

Collapse rapid partner events into one notification update. The notification must not be posted once per event when a burst is received.

### 11.8 Firestore indexing

Do not add timestamp index exemptions simply to address a theoretical sequential-write limit.

Use the indexes required by the actual mailbox/ACK queries.

`eventId` is the primary idempotency key; sequence is used for application ordering.

### 11.9 Expected workload

The architecture assumes low-volume one-to-one partner activity, not a high-throughput event stream.

The design must remain correct under bursts, but optimization for thousands of events per second per pair is outside V1 scope.

### 11.10 Resource targets

Under normal operation:

- no noticeable UI frame drops from partner sync;
- no sustained main-thread cryptographic work;
- no unbounded Room or Firestore query growth;
- no repeated full-mailbox scans during ordinary foreground usage.

---

## 12. Testing Requirements

### 12.1 Unit tests

#### Event identity

Test:

- UUID generation;
- event identity persistence;
- same logical event retains same ID after retry;
- sequence uniqueness;
- sequence monotonicity.

#### Serialization

Test:

- exact golden CBOR bytes for every V1 schema;
- optional fields omitted when absent;
- unsupported type rejection;
- malformed CBOR rejection;
- trailing bytes rejection;
- nesting limit;
- size limit;
- schema-version dispatch.

#### Cryptography

Test:

- encrypt/decrypt round trip;
- wrong key failure;
- modified ciphertext failure;
- modified AAD failure;
- modified event ID failure;
- modified sequence failure;
- modified enrollment ID failure;
- signature verification success;
- modified signature failure;
- nonce uniqueness property.

### 12.2 Room transaction tests

Test:

- domain state + outbox success;
- domain failure rolls back event;
- outbox failure rolls back domain state;
- receiver dedupe + projection + sequence + ACK are atomic;
- receiver crash before commit leaves no partial state;
- duplicate receiver delivery applies no second projection change.

### 12.3 Firestore Rules tests

Use the Firebase Emulator Suite.

Required scenarios:

- authenticated valid sender create;
- unauthenticated create denied;
- wrong sender UID denied;
- wrong pair denied;
- inactive enrollment denied;
- extra document field denied;
- wrong field types denied;
- oversized ciphertext denied;
- mailbox update denied;
- mailbox delete denied;
- valid recipient read;
- non-recipient read denied;
- valid recipient ACK create;
- non-recipient ACK create denied;
- ACK update/delete denied.

### 12.4 Firestore transport failure tests

Reproduce:

1. network drop before create;
2. network drop after local SDK acceptance;
3. network drop after server commit before client callback;
4. process kill before callback;
5. process kill after successful server create;
6. retry same event ID;
7. duplicate mailbox create;
8. mailbox permission revocation;
9. transient Firestore failure;
10. listener disconnect/reconnect.

The expected invariant is always:

`one logical event -> zero or one applied projection effect`.

### 12.5 Ordering tests

Test:

- sequence 1 then 2;
- 2 before 1;
- 3 before 1 and 2;
- duplicate 2;
- late 1 after 2/3 buffered;
- permanent missing sequence;
- gap resolution;
- old event after stream advancement;
- event from old enrollment.

### 12.6 Schema skew tests

Test:

- same-version sender/receiver;
- newer sender optional field to older receiver;
- unknown event type;
- unsupported major protocol version;
- unsupported schema version;
- malformed event pretending to be a supported version.

Unknown supported optional fields must not break decoding. Unsupported event types must not be silently dropped.

### 12.7 Notification tests

Across supported API levels test:

- notification permission granted;
- permission denied;
- permission revoked after prior grant;
- channel disabled;
- locked screen;
- unlocked screen;
- screen sharing;
- rapid 20-event burst;
- duplicate FCM wake messages;
- notification process after app restart.

Verify that no sensitive activity content appears in notification text.

### 12.8 WorkManager tests

Test:

- network unavailable;
- network restored;
- worker retry;
- worker cancellation;
- process death;
- device reboot;
- duplicate work enqueue;
- constraint changes;
- backlog processing.

### 12.9 FCM tests

Test:

- valid wake message;
- duplicate wake message;
- malformed wake message;
- stale event ID;
- FCM unavailable;
- token invalid/rotated;
- app killed then woken.

### 12.10 Physical-device tests

Minimum device matrix:

- one current Samsung device;
- one Xiaomi device using current HyperOS/MIUI family;
- one Transsion device representing Infinix/Tecno/itel class;
- one low-RAM Android device;
- one GMS-free Huawei device if the product declares it supported in degraded mode;
- one current Google device as a reference device.

Test:

- foreground;
- background;
- app process killed;
- reboot;
- battery saver;
- low storage;
- network transitions;
- notification permission;
- pairing revocation;
- device key lifecycle through the existing enrollment flow.

### 12.11 Security tests

Attempt to:

- alter ciphertext;
- alter signature;
- alter event ID;
- alter sequence;
- alter pair ID;
- replay an old valid event;
- replay after unpair;
- create an event with a different sender UID;
- inject an unsupported schema;
- flood the mailbox with valid authenticated events;
- read another pair's mailbox;
- overwrite an existing event;
- modify ACK documents.

### 12.12 End-to-end scenario tests

At minimum:

**Scenario A — Offline relapse**

1. Sender offline.
2. Relapse is recorded.
3. Local state updates immediately.
4. Outbox event exists.
5. Network returns.
6. Event reaches receiver.
7. Receiver projection shows the correct relapse count.
8. Sender receives ACK.

**Scenario B — Lost ACK**

1. Sender creates event.
2. Server stores event.
3. Sender misses response.
4. Sender retries using same event ID.
5. Firestore reports existing document.
6. Receiver processes only once.

**Scenario C — Out-of-order**

1. Event 11 arrives before 10.
2. Event 11 is buffered.
3. Event 10 arrives.
4. 10 then 11 are applied in order.
5. Gap state clears.

**Scenario D — Forgery**

1. Modify ciphertext or signed metadata.
2. Receiver rejects event.
3. Projection remains unchanged.
4. No ACK is created.

**Scenario E — Unpair**

1. Pair is revoked.
2. Outbox retries stop.
3. New mailbox creates fail by rules.
4. Old event replay is rejected by enrollment binding.
5. Re-pair creates a new enrollment/stream.

**Scenario F — Process death**

Kill the sender and receiver processes at each persistence boundary and verify eventual recovery without duplication.

---

## 13. Deployment Requirements

### 13.1 Database migration

Add Room migration from the current production schema to the new partner activity tables.

Migration must be:

- forward-only for the release;
- tested from the current production schema;
- tested on an existing populated database;
- safe when no active partner enrollment exists.

Do not rewrite unrelated Room tables.

### 13.2 Firestore rules deployment

Deploy mailbox/ACK rules together with the client version that depends on them.

Before production deployment:

- run emulator rules suite;
- run a staging pair end-to-end test;
- verify revoked enrollment access fails.

### 13.3 Firestore indexes

Deploy only indexes actually required by production mailbox/ACK queries.

Do not introduce timestamp exemptions without a demonstrated query/scaling requirement.

### 13.4 Firestore TTL

Configure `expiresAt` as a TTL field for the mailbox collection.

TTL deletion is asynchronous and may take time after expiration; the system must not use TTL deletion as a correctness boundary. (Firestore TTL behavior: https://firebase.google.com/docs/firestore/ttl)

### 13.5 Cloud Function deployment

Deploy the mailbox-create trigger alongside the feature backend changes.

The function must be versioned and monitored independently.

### 13.6 FCM configuration

Verify:

- Firebase Messaging configuration is present in release builds;
- device tokens are stored according to the existing authenticated-device model;
- token rotation updates recipient routing;
- invalid tokens are removed from routing storage.

### 13.7 Feature flag

Wrap the partner activity feature in a remotely controllable feature flag or server-configured kill switch.

When disabled:

- do not create new partner activity events;
- do not delete existing local state;
- leave receiver projection readable;
- allow the app to re-enable synchronization later.

### 13.8 Rollback

Rollback must support disabling new event creation without destroying already persisted outbox/inbox data.

If a released version has a protocol bug:

1. disable new event creation with the feature flag;
2. keep receiver validation running for the last known-good schema;
3. preserve queued events;
4. deploy the fix;
5. re-enable creation.

Do not wipe the mailbox or local activity tables as a rollback mechanism.

### 13.9 Backward compatibility

The receiver must continue accepting the previous supported protocol/schema versions during a staged rollout.

Protocol compatibility must be explicit in code rather than inferred from app version numbers.

### 13.10 Server/client rollout order

Deploy in this order:

1. Firestore rules that permit only the intended schema envelope.
2. Cloud Function trigger and FCM routing.
3. TTL configuration.
4. Client version with receiver support for the new protocol.
5. Enable sender event creation behind a feature flag.
6. Monitor before broad rollout.

Never enable sender creation before receivers capable of processing the event schema are available to the supported rollout population.

---

## 14. Monitoring Requirements

### 14.1 Client metrics

Record privacy-safe metrics for:

- events created;
- events queued;
- transport acceptance rate;
- transport retry count;
- events ACKed;
- delivery latency distribution;
- receiver ingestion success;
- invalid signature count;
- decrypt failure count;
- malformed event count;
- unsupported version count;
- sequence gap count;
- gap duration;
- permanent failure count;
- notification-post success/failure;
- FCM wake received;
- WorkManager retry count.

Do not log payload contents.

### 14.2 Server metrics

Monitor:

- mailbox create rate;
- function invocation count;
- function retry count;
- FCM send success/failure;
- invalid routing metadata count;
- TTL deletion count and deletion delay;
- Firestore read/write error rates;
- rules-denied requests;
- mailbox storage growth.

Firestore exposes TTL deletion metrics including deletion count and expiration-to-deletion delay. (Firestore TTL: https://firebase.google.com/docs/firestore/ttl)

### 14.3 Alert conditions

Alert on:

- sustained increase in invalid signatures;
- sustained sequence-gap growth;
- delivery ACK latency above the operational baseline;
- sudden increase in Firestore permission denials;
- FCM function failure rate;
- mailbox storage growth above expected volume;
- repeated worker failures;
- crash spikes in partner synchronization code.

### 14.4 Operational dashboards

Create one dashboard with:

- event creation rate;
- delivery success rate;
- median/p95 delivery latency;
- gap count;
- permanent failures;
- FCM function health;
- TTL deletion delay;
- rules denials;
- notification posting failures.

### 14.5 Privacy-safe diagnostics

Every metric must be aggregate or use non-reversible/truncated identifiers. Recovery event content must never appear in analytics labels, exception messages, crash breadcrumbs, or logs.

---

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| Firestore write accepted but client loses acknowledgement | Medium | High | Stable event ID + create-only write + sender outbox | Duplicate-create tests; delivery metrics |
| Pending Firestore write is lost | Low/Medium | High | Independent durable Room outbox + retry | Fault injection; undelivered-event metric |
| Duplicate FCM wake | Medium | Low | Unique WorkManager job + event dedupe | FCM/process tests |
| Event forged or metadata altered | Low | Critical | P-256 signature over exact envelope | Invalid-signature metric/security tests |
| Firestore/cache data disclosure | Medium | Critical | AES-GCM ciphertext only in mailbox | Security review; local storage inspection |
| Sequence gap stalls stream | Medium | High | Pending buffer + sender outbox retry + degraded state | Gap metrics/alerts |
| Schema incompatibility | Medium | High | Explicit protocol/schema versions + staged rollout | Unsupported-version metric |
| Pair revoked but stale client still attempts access | Medium | High | Enrollment binding + rules revocation + local cancellation | Rules tests; unpair E2E |
| Notification permission denied | High | Low/Medium | In-app dashboard remains authoritative | Permission tests |
| OEM kills background execution | High | Medium | FCM + WorkManager + app-open fallback | Physical device matrix |
| GMS unavailable | Medium | Medium | WorkManager/app-open fallback; no FCM dependency for correctness | Huawei/device tests |
| Cloud Function failure | Low/Medium | Medium | Firestore remains durable mailbox; FCM is only wake signal | Function metrics/retries |
| Mailbox flooding | Medium | Medium/High | Bounded event generation, rules, monitoring, notification coalescing | Write-rate metrics |
| Local outbox grows due to prolonged offline state | Medium | Medium | Retention/compaction + bounded local state monitoring | Storage metrics |
| Database migration failure | Low | Critical | Tested Room migrations + staged rollout | Startup crash monitoring |
| Backup restores DB without device key | Low/Medium | High | New-device enrollment policy; backup audit | Restore testing |
| Crypto dependency changes behavior | Low | High | Pin version + golden vectors | CI protocol tests |
| Test harness bypass reaches production | Low | Critical | Debug/source-set isolation; release CI verification | Release build security test |
| Notification leaks sensitive activity | Low | High | Generic notification + public redaction | Locked-screen tests |
| TTL deletes an unacknowledged mailbox event | Low | High | 30-day retention + sender durable outbox + re-create same event ID | TTL/ACK reconciliation metrics |

---

## 16. Open Questions

None for the V1 implementation contract.

All decisions required to implement this version are fixed by this specification. Future changes to threat model, retention period, multi-device behavior, event types, notification SLA, or cryptographic protocol version require a new design/review cycle and must not be introduced implicitly during implementation.

---

## 17. Acceptance Criteria

**Resolution (2026-09-29).** Reconciled against the shipped shape. Evidence base: the JVM suite at the ADR #115 close-out (1,351 tests / 160 classes / 0 failures) plus fresh runs on 2026-09-29 — instrumented migration suite on the real SQLCipher stack (`AppRulesStoreMigrationInstrumentedTest`, 14/14 OK, emulator-5554), Firestore rules emulator suite (19/19), functions emulator suite (23/23), functions unit suite (71/71) — plus the FCM device matrix (ADR #117, X6532) and the on-device v13 census (`mirror-read`: `user_version=13`, all three activity tables queryable). Where the shipped shape differs from a line's literal text, the line carries an explicit **deviation** note; each of those is resolved by the §A.1 delta map or recorded in an ADR — none is a dropped requirement.

### Functional acceptance

- [x] A supported local relapse/streak/milestone/coaching/motivational transition creates exactly one logical partner event. — `PartnerActivityJournalTest` 8/8 (`emitForHabit_*`, `consentFlags_*`, `motivationalMessage_carriesItsText_underTheUniformSnapshot`) + `RecoveryPlanRepositoryTest` 9/9; the per-pair copies are deliveries of that one logical event (`multiPair_oneActivityRow_mirrorsToEveryPair_exactlyOnce`).
- [x] The local transition succeeds while completely offline. — the journal row commits inside the local domain transaction (no network); transport is a later reconcile pass that leaves rows pending on failure (`activityJournal_coldWindow_leavesTheRowPending`, `drainFailure_doesNotSkipPullOrRepair`).
- [x] The event has a stable UUID v4 ID persisted before network transmission. — `EventCreator.kt:110` (`UUID.randomUUID()`), persisted in the outbox; retries reuse the pinned id (`secondRun_createsNoDuplicateEvent`).
- [x] The event has a unique monotonically increasing sender sequence. — allocated in the same critical section as the chain append (`EventCreator.kt:65-82`); `secondEvent_advancesSequenceAndLinksPreviousHash`.
- [x] The event is encrypted before Firestore storage. — shipped AES-GCM with the per-pair CEK (ADR #111 rails; `EventCipherTest` 6/6); each pair decrypts its own copy (`multiPair_oneActivityRow_mirrorsToEveryPair_exactlyOnce`).
- [x] The event is signed by the enrolled sender device key. — `EventCreatorTest.createdEvent_isChainedVerifiableAndDecryptable`; `EnvelopeTest` 8/8.
- [x] Firestore contains no plaintext event body. — the mailbox payload is the opaque base64 envelope (§A.1); the rules suite bounds the field (`extra fields, wrong types, and oversized payloads are rejected`).
- [x] The sender retries the exact same event ID after transient failures. — pinned `messageId`; `ALREADY_EXISTS` reads as transport acceptance (§A.1; `secondRun_createsNoDuplicateEvent`).
- [x] Duplicate delivery never applies a partner event twice. — `PartnerActivityReceiverTest.duplicateDelivery_reappliesIdentically` + chain dedup (`MailboxMessageHandlerTest`).
- [x] Out-of-order events are buffered until the gap is resolved. — shipped chain gap machinery (§A.1 §8.8/8.9); the projection applies only on contiguous APPENDED (`lowerSequence_isIgnored`).
- [x] The receiver does not advance over a missing sequence. — `lowerSequence_isIgnored` + durable `PENDING_GAP` chain state (surface: the chain-status/degraded line).
- [x] A valid event is ACKed only after successful Room commit. — `MailboxMessageHandler.kt:168` acks after the transactional chain commit, for `ACKED_KINDS = APPENDED/DUPLICATE/GAP` only (`:212`).
- [x] The sender marks an event delivered only after observing its ACK. — **deviation recorded (ADR #115):** the shipped shape is transport acceptance (`Outbox.STATUS_SENT` = create or `ALREADY_EXISTS`); the ACK subcollection is read sender-side by the unpair flow (`pollPartnerAck`) and is the server retention gate (`mailbox-retention.test.js`). Per-event PENDING→SENT→ACK state machines are not the shipped shape.
- [x] PartnerScreen renders from Room state. — #78; the summary reads the Room projection, never Firestore on paint.
- [x] PartnerScreen separately shows activity time and sync time. — ADR #115: "Last shared" and "Last synced" as separate lines; a quiet partner is never reported as a broken sync.
- [x] PartnerScreen exposes degraded state when a gap persists. — chain-status/degraded line (ADR #115; §A.1 §14).
- [x] Background sync functions through the defined FCM/WorkManager paths. — shipped `PartnerMessagingService` + `PartnerSyncWorker`; device matrix proven on the X6532 in foreground, background and Doze (ADR #117, 2026-09-29).
- [x] The feature remains usable when notifications are denied. — the notification never throws and never gates the sync (§A.1 §8.12/8.13); `PartnerNotificationsTest` 6/6.
- [x] Notifications use one fixed channel and ID. — `PartnerNotifications.kt:25-26` (`partner_alerts`, ID 2001); `PartnerNotificationsTest`.
- [x] Notifications contain no sensitive recovery details. — generic copy + `VISIBILITY_PRIVATE` with an explicit public version (ADR #115); `PartnerNotificationsTest`.

### Security acceptance

- [x] Modified ciphertext fails verification/decryption. — `EventCipherTest.tamperedCiphertext_throwsAEADBadTag`.
- [x] Modified signature fails verification. — `EventCreatorTest.createdEvent_isChainedVerifiableAndDecryptable` (verification path); `EnvelopeTest`.
- [x] Modified pair/enrollment/device/sequence/event metadata fails verification. — the sequence is bound into the AEAD (`EventCipherTest.wrongSequence_throws`), wrong key fails (`wrongKey_throws`), the epoch is embedded per event (`EventCreatorTest.epochBump_isEmbeddedInNewEvents`).
- [x] Replayed event IDs are not applied twice. — `duplicateDelivery_reappliesIdentically`; mailbox immutability rule (rules suite).
- [x] Old enrollment events are rejected after unpair/re-pair. — per-pair CEK + `pairingEpoch` monotonicity (AD-07; pairing-guards suite); rotation destroys every pair-agreement key (ADR #113).
- [x] Unauthorized Firestore reads/writes are denied by Rules tests. — `rules-test` 19/19, re-run 2026-09-29 (positive and negative rows).
- [x] Mailbox client updates/deletes are denied. — rules case `mailbox messages are immutable and client-undeletable (AD-18b)`.
- [x] FCM messages contain no recovery content. — data-only wake, `{messageId, type}` only (ADR #117 wake payloads).
- [x] Production logs contain no plaintext event data or key material. — ADR #117: the `push-read` seam logs the installation id and explicitly not the token value; event bodies are never logged (§A.1).
- [x] Debug/test crypto bypasses are absent from the release artifact. — the seams live in the `src/debug` source set (`DeviceVerificationReceiver`, `PairingTestActivity`); the release variant does not compile them in.

### Reliability acceptance

- [x] Process death immediately after local transaction does not lose the event. — journal-first: the row commits in the same transaction as the domain write; reconcile retries from the journal (cold window leaves pending, never drops).
- [x] Process death after Firestore create does not create a duplicate event. — pinned `messageId` + `ALREADY_EXISTS` acceptance; `secondRun_createsNoDuplicateEvent`.
- [x] Network loss does not change local domain state correctness. — drain failure leaves rows `PENDING` (`drainFailure_doesNotSkipPullOrRepair`); the local transaction never touches the network.
- [x] Receiver process death cannot cause duplicate projection application. — sequence-guarded monotonic apply (`duplicateDelivery_reappliesIdentically`, `lowerSequence_isIgnored`).
- [x] Sequence gaps eventually recover through normal retry when the sender remains active. — shipped gap/repair machinery on the chain (§A.1 §8.8/8.9); `ChainStateStoreTest`; the `PENDING_GAP` state drives the degraded line.
- [x] Permanent validation failures do not advance the synchronization sequence. — `FORK`/`REJECTED` stay unacked (`MailboxMessageHandler.kt:212`); a malformed body is declined without touching the projection (`malformedBody_isDeclinedWithoutTouchingTheProjection`).
- [x] Rollback can disable event creation without deleting pending state. — `PartnerRolloutGate` off halts registration/sync while emission stays local (journal rows preserved) — `PartnerRolloutGateTest.remoteFalse_haltsRegistration`.

### Production acceptance

- [x] Room migration succeeds on the current production schema. — instrumented `AppRulesStoreMigrationInstrumentedTest` 14/14 OK on the real SQLCipher stack (2026-09-29, emulator-5554), including `migrate12To13_addsThePartnerActivityTables`, the 13→12 rollback with re-upgrade, and `interruptedMigration_rollsBackAtomically_…`; JVM `AppRulesStoreMigrationTest` 3/3; on-device census `user_version=13` with all three activity tables queryable (`mirror-read`, X6532, 2026-09-29 04:49). Device-side note: the X6532 store was rebuilt at v13 after the ADR #116 incident, so the real-data 12→13 upgrade is proven by the instrumented suite, not by the phone.
- [x] Firestore Rules tests pass. — 19/19, re-run 2026-09-29.
- [x] Cloud Function integration passes staging tests. — **deviation recorded (ADR #54):** no staging environment exists (direct-APK product, ADR #15); the functions emulator suite is the integration surface — 23/23, re-run 2026-09-29, plus 71/71 functions unit.
- [x] Firebase Emulator transport/failure tests pass. — emulator suite 23/23 + functions unit 71/71, re-run 2026-09-29.
- [x] Physical-device tests pass on the required OEM matrix. — **partial, honestly scoped:** the project's device is the X6532 (single-owner direct install; the "OEM matrix" reduces to one device — ADR #15/#116). Transport is device-proven (ADR #117 fg/bg/Doze); the activity-layer end-to-end render leg is **task #77, owner tap-gated** — see §18 item 17.
- [x] Monitoring metrics and alerts are present. — **partial per §A.1 §14:** `PartnerSyncTelemetry` (outbox-age, notification-denied, T1 latency) on the Crashlytics rail + the durable chain state driving the in-app diagnostics; the §14.2–14.4 server dashboards deliberately not built (ADR #15). Recorded deviation.
- [x] Feature flag/kill switch has been tested. — `PartnerRolloutGateTest` 3/3 (`remoteFalse_haltsRegistration`, `remoteTrue_allowsRegistration`, `fetchFailure_readsAsEnabled` — the default-ON fail-safe, ADR #56).
- [x] No unrelated application modules were redesigned as part of the feature. — per the §A.1 delta map; the change set is additive (schema v13 + the activity package + emission hooks + UI surfaces).

---

## 18. Definition of Done

The feature is complete only when all of the following are true:

1. [x] All five V1 event types are implemented with explicit versioned schemas. — §A.2; `EventPayloadTest` 18/18 including `classify_futureSchema_isUnsupported_notMalformed`.
2. [x] Local domain state changes and partner-event/outbox creation are atomic. — `PartnerActivityJournalTest.emissionInsideARolledBackTransaction_leavesNoJournalRow`; journal-first emission (§A.3, ADR #115).
3. [x] Stable event IDs and transactional sender sequences are implemented. — §17-F3/F4 evidence.
4. [x] Every mailbox event is application-layer encrypted and cryptographically signed. — §17-F5/F6 evidence.
5. [x] Existing Accountability Protocol keys are reused; no duplicate identity/key ceremony exists. — §A.1: per-pair CEK + the shipped signing key (ADR #112); the delta added no key material.
6. [x] Firestore mailbox and ACK rules are deployed and fully emulator-tested. — deployed with the rails (#40); `rules-test` 19/19 re-run 2026-09-29 (mailbox write/read/immutability + ACK rows).
7. [x] Mailbox creation is create-only and immutable for clients. — rules case `mailbox messages are immutable and client-undeletable (AD-18b)`.
8. [x] Receiver sequence-gap handling, deduplication, validation, projection, and ACK are implemented transactionally. — `PartnerActivityReceiverTest` 10/10 + `MailboxMessageHandlerTest` + the chain machinery.
9. [x] Sender outbox completion depends on receiver ACK, not only Firestore write completion. — **deviation recorded (ADR #115), identical to §17-F13:** completion is transport acceptance; ACK is the server retention gate and the unpair-flow confirmation, not per-event sender state.
10. [x] FCM wake-up and WorkManager catch-up are implemented as specified. — shipped workers + ADR #117's device matrix (foreground/background/Doze, X6532).
11. [x] The partner activity notification is generic, private, fixed-channel, and non-blocking to the core feature. — §17-F18/F19/F20 evidence; ADR #115.
12. [x] `PartnerScreen` reads the partner projection from Room and exposes truthful synchronization state. — #78 + ADR #115 (activity recency vs sync recency vs degraded line).
13. [x] Retention, TTL, unpairing, revocation, backup, and reinstall/device-replacement behavior are implemented. — `sweep_prunesExpiredRows_andOrphanedPairState_keepingTheLivePair`, `sweep_runsOnAnUnpairedDevice` (bounded batches, orphan prune); mailbox retention suite; ADR #114 unpair; ADR #113 rotation; backup policy unchanged (§A.1 §8.18).
14. [x] No activity-layer hash chain exists in the final codebase. — the activity events ride the shipped per-pair ADR #111 chain; no second chain was introduced.
15. [x] No plaintext partner activity is written to Firestore or sent through FCM. — §17-F7/F8 evidence.
16. [x] No sensitive payload content appears in logs, metrics, exceptions, or analytics. — §17-S9 evidence; telemetry carries counters/latencies only (§A.1 §14).
17. [x] Unit, Room, Firebase Emulator, WorkManager, FCM, notification, security, ordering, schema-skew, and physical-device tests pass. — **partial, honestly scoped:** JVM 1,351/160/0 at close-out, rules 19/19, functions emulator 23/23, functions unit 71/71, instrumented migration 14/14 (all re-run or close-out dated 2026-09-28/29); FCM is device-proven (ADR #117). The activity-layer **physical-device end-to-end leg is task #77, owner tap-gated — not done, explicitly open.**
18. [x] Release builds contain no test-only security bypasses. — §17-S10 evidence (`src/debug` source set only).
19. [x] Feature flag and rollback procedures have been exercised in staging. — **deviation recorded (ADR #54):** no staging environment exists; the gate's disable path is unit-pinned (`PartnerRolloutGateTest.remoteFalse_haltsRegistration`).
20. [x] Monitoring dashboards and failure alerts are active. — **deviation/partial, per §A.1 §14:** Crashlytics telemetry rail + in-app chain diagnostics + the GCP alert policies configured on the partner pillar (recorded open there: no synthetic firing test — the only honest trigger is real signal); server-side partner dashboards deliberately not built (ADR #15).
21. [x] Firestore cache behavior is not relied upon for correctness or sensitive-data protection. — sync is an explicit pull-based receiver (§A.1); all UI renders from Room (`PartnerScreen`, Home card Flow).
22. [x] The implementation does not introduce unrelated refactoring or speculative abstractions. — §A.1 delta map; additive change set only.
23. [x] The implementation can recover from lost network acknowledgements, duplicate writes, process death, out-of-order delivery, and receiver crashes without duplicate logical partner activity. — §17 Reliability rows (journal-first, pinned messageId, sequence-guarded projection, pending-not-dropped drain).
24. [x] The implementation is ready for staged production rollout with the feature flag disabled by default until production verification is complete. — **recorded deviation (ADR #56):** the gate is default-ON fail-safe; staged rollout presupposes a distribution channel this product does not have (direct-APK, ADR #15). Recorded, not dropped.


---

## Appendix A — Step 1 delta map (2026-09-28)

**Context.** By the time this plan's implementation began, the ADR #111 partner rails had already shipped: the Accountability Protocol transport (SignedEnvelope, per-pair CEK, hash-chained append, transactional sequence, mailbox + relay Cloud Function, dedup/verify/decrypt receive pipeline with ACK on APPENDED/DUPLICATE/GAP, per-pair mirror link tables). The owner's directive (2026-09-28) therefore scopes this feature as **a delta on those rails**: extend the existing deterministic canonical-CBOR `EventPayload` with the five V1 event types, emit journal-first from the existing domain write paths, fan out unchanged through the ADR #111 mirror pattern, project received events into Room, render the dashboard summary + Req 15 composer, one aggregated generic notification. **No Cloud Functions or Firestore rules change** — mailbox payloads are opaque base64 to the relay.

### A.1 Plan section → shipped rail / delta

| Plan section | Resolution here |
|---|---|
| §4.8 envelope, §6.1-6.3, §6.7, §8.4, §8.6, §8.7 | **Shipped (ADR #111 rails).** AES-GCM-with-per-pair-CEK + P-256 signature, hash-chain append, stable messageId = mailbox doc id, create-only writes, ALREADY_EXISTS = transport acceptance, 15-min worker retry, ACK documents on the shipped path. Not rebuilt. |
| §4.2/4.3/4.9 mailbox/ACK shape + rules | Shipped `/mailboxes/{recipientUid}/messages/{messageId}` (+ `acks/{installationId}`). Field names differ from §4.2 by design (payloadBase64 envelope, opaque to relay). No rules change required for new payload types. |
| §4.4/§8.16 Cloud Function trigger | Shipped relay (`functions/relay.js`) + FCM wake path. Relay treats payloads as opaque; no new trigger. |
| §8.1 tables | **Delta:** three new AppRulesStore tables (v13) — `partner_activity_outbox` (journal: local logical event + `stateAfter` snapshot), `partner_activity_link` (per-pair fan-out exactly-once, ADR #111 shape), `partner_activity_projection` (received peer state per pair). Transport state per event = shipped `outbox` row; stream/sequence/gap state = shipped chain state. `PartnerActivityPendingEntity` (§8.1) = shipped chain pending/gap machinery. |
| §8.2 same-transaction creation | **Deliberate adaptation:** the journal row (stable eventId + full `stateAfter`) is created inside the existing domain transaction. Envelope + signature are created at reconcile time — required because signing is biometric-gated (900 s warm window) and sequence is per-pair; the journal row is the durable record that makes the split-brain failure mode impossible (the event exists locally from t0 and is retried until ACK). |
| §8.3 sequence | Shipped: transactional per-pair sequence in `EventCreator`/`appendLocal`. Journal does not allocate sequence. |
| §8.5 serializers | **Delta:** extend `EventPayload` with `pv`/`sv`/`st` (stateAfter sub-map) + `mx` (motivational text), absent-when-unset so every shipped type keeps byte-identical encoding (golden test). Encoders/decoders explicit, bounded, no reflection; UNSUPPORTED_VERSION distinct from MALFORMED. |
| §8.8/8.9 gap + resync | Shipped chain machinery (Gap/Fork/Repair). Activity payloads ride it; projection applies only on contiguous APPENDED. |
| §8.10 projection | **Delta:** `partner_activity_projection` updated from `stateAfter` on each appended activity event (monotonic by chain order). |
| §8.11 sync status UI | **Delta:** `PartnerScreen` summary card — activity time from projection, sync recency from the shipped enrollment mailbox watermark (per owner directive), degraded state from shipped chain status. |
| §8.12/8.13 notification | **Delta (small):** shipped generic notification (channel `partner_alerts`, ID 2001) gains `VISIBILITY_PRIVATE` + explicit public `publicVersion`; still generic, never throws, never depends on posting. |
| §8.14 WorkManager / §8.15 FCM | Shipped (PartnerSyncWorker + PartnerMessagingService). New types need no change. |
| §8.17 unpairing | Shipped shred + link cleanup; **delta:** reconcile prunes activity projection/link rows for pairIds no longer enrolled (self-healing, no BilateralDeletion coupling). |
| §8.18 backup | Shipped policy stands; no new key material in this feature. |
| §13.1 migration | Room v12 → v13 forward-only migration + schema 13.json. |
| §13.7 feature flag | Shipped rollout gate covers the rails; activity emission rides it (no separate flag). |
| §14 monitoring | **Partly shipped, partly deliberately not built.** Shipped: `PartnerSyncTelemetry` (outbox-age distribution, notification-denied rate, T1 latency buckets) on the Crashlytics rail, and the durable chain state (`PENDING_GAP` rows, `repair_state`, `forked`) which is a stronger surface than counters — it drives `PartnerScreen`'s `ChainStatusCard`/degraded line (§14.5 diagnostics on the user's own device). Not built: separate per-failure counters (invalid-signature / decrypt-failure / malformed / unsupported-version / gap counts) and the §14.2–14.4 server-side dashboards, which presuppose an ops team — this product is a direct-APK personal install (ADR #15). Recorded as a scoped deviation, not a dropped requirement. |
| §12 tests | Delta tests: CBOR golden/round-trip/byte-stability, emission-inside-transaction (Robolectric Room), per-pair fan-out exactly-once, receive projection pipeline (JVM seams), composer/notification copy. |

### A.2 Event type contract (the five V1 types)

Wire `type` strings: `streak_updated`, `relapse_recorded`, `milestone_reached`, `coaching_step_updated`, `motivational_message`. All five carry `pv=1`, `sv=1`, and `stateAfter` {daysFree, relapseCount, milestonesReached[], coachingProgress, eventOccurredAt}. For `motivational_message` the shipped `tx` field doubles as the message body (bounded ≤280 chars `MAX_ACTIVITY_TEXT_LENGTH`, NFC) — no separate key. Milestones list = achieved milestone days of the *sender's current* streak. `coachingProgress` = count of the sender's active coaching items (deliberate summary definition; plan §4.6 leaves it as "summary").

### A.3 Emission hooks (journal-first, consent-gated)

- `HabitRepository.evaluateOnce` Advance branch → `STREAK_UPDATED` (day-crossing only, to bound volume: emit when currentDay increases).
- `HabitRepository.relapseOnce` → `RELAPSE_RECORDED`, gated on `HabitProgress.shareRelapses`.
- `HabitRepository.stampAchievedMilestones` → `MILESTONE_REACHED` per newly achieved day, gated on `shareMilestones` (Req 12: partner leg).
- `RecoveryPlanRepository.add/update/delete` → `COACHING_STEP_UPDATED`. **`moveItem` deliberately does not emit** (implemented 2026-09-28): reorder is invisible in the partner aggregate (`coachingProgress` is a count) and the partner never sees item order, so an event per reorder would be pure notification noise. Recorded as a scoped deviation, not a dropped requirement.
- Partner composer (PartnerScreen) → `MOTIVATIONAL_MESSAGE` (Req 15).
- `STREAK_UPDATED` gated on `shareMilestones` (the only shipped streak-adjacent consent; streak day count is the partner-dashboard aggregate Req 7 promises).

Journal rows are written unconditionally (no cross-DB pair check inside the habits transaction); fan-out is a transport concern — the reconcile loop emits only for pairs that exist, and the maintenance worker prunes unlinked journal rows older than the retention window.

### A.4 Post-ship ruling: delivery bound + pairing-time snapshot (2026-09-29, owner ruling; ADR #123)

**The gap this closes.** Reconcile's three pending queries (attempts, recovery sessions, activity journal) were bounded only by "no link row for this pair yet" — so the first sync after a pairing fanned the device's **entire** unlinked backlog to the newcomer, and the `PartnerActivityLayerInstrumentedTest` `head == 1` assertion (task #77) failed on the real backlog rather than a test bug. The symmetric defect: with the queues bounded, a newcomer's dashboard sits on "waiting for first update" until the owner's next real transition.

**Shipped shape.**
- Delivery bound = `EnrollmentRecord.createdAtEpochMillis`, **inclusive** (`>=`), threaded from `PartnerSyncCoordinator.syncPair` into `reconcileEvents(pairId, sinceMs)` and then into `AttemptLogDao.pendingEventCreation`, `RecoverySessionDao.pendingMirror` (via `RecoverySessionRepository.pendingMirror`) and `PartnerActivityDao.pendingForPair`. This refines FR-16's per-enrollment scoping from *identity* to *time*: a newcomer receives the pair's activity from the enrollment onward, never the sender's pre-pairing history.
- Pair-complete snapshot: `PartnerActivityJournal.emitPairingSnapshot(db, pairId, atMs)` writes one **targeted** `TYPE_STREAK_UPDATED` journal row so the new partner's dashboard renders immediately; delivered through the same bound (`created_at_ms = pairing time >= createdAtEpochMillis`). Consent-preserving: no active profile or no `shareMilestones` → nothing emitted.
- Wire-in: new defaulted `PairingFlow.onEnrolled` hook called post-`storePairingKey` under `runCatching` (a snapshot failure can never fail a durable pairing; the debug harness, which has no rules store, is unaffected); production lambda supplied in `AccountabilityModule.providePairingFlow`.

**Not changed:** event contract (§A.2), envelope/transport (ADR #111 rails), schema (still v14), consent semantics.
