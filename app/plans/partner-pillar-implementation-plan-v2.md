# Partner Pillar — Implementation Specification (v2)

**Status:** Approved for implementation
**Supersedes:** v1 (corrected following plan-specific audit — see Section 0)
**Target Audience:** Implementation engineer / coding agent
**Stack:** Kotlin, Jetpack Compose, Hilt, Room 3 + SQLCipher (Android Keystore-backed), Firebase (Firestore, Cloud Functions v2 — Node.js 22, `firebase-admin` pinned, Firestore-triggered), FCM, Huawei Push Kit (HMS), Cloud Scheduler
**minSdk:** 26 (Android 8.0)

---

## 0. What changed from v1

v1 fixed the original research's defects (mailbox authorization, per-sender identity, approval correlation, outbox durability, provider abstraction) but introduced its own implementation-level defects once the design got concrete. This version fixes those. The changes that matter most:

- App Check is no longer assumed universal — it is GMS-only, with a distinct control on the HMS/no-GMS path (Section 5, AD-03/AD-03b).
- The relay no longer claims exactly-once dispatch. It is at-least-once dispatch with per-installation state and client-side idempotent processing (AD-10).
- Mailbox documents are no longer deleted the instant one device processes them — retention + per-installation acknowledgment replaces that (AD-18b).
- The encrypted/signed event envelope is now created atomically at event-creation time, not lazily inside the sync worker (AD-06b).
- Same-sender concurrent writes are now serialized through a transactional `ChainState` row (AD-06c).
- Push notifications are now strictly a generic wake signal; all sensitive rendering happens after a verified local fetch (AD-11, resolves the NFR2/Step-9 contradiction in favor of privacy-first).
- Unpair is now specified accurately: local shred is guaranteed, remote shred is acknowledgment-based and best-effort (AD-13b).
- Server-side abuse rate limiting is added (AD-23).
- "Hard cost ceiling" is replaced with named per-service quotas plus a budget alert (AD-20b).
- Function region is explicitly colocated with the Firestore database region (AD-17b).

---

## 1. Feature Overview

The Partner Pillar is the delivery and UI layer that completes the dual-role peer accountability system. One user ("restricted device") is paired with a partner ("partner device") via an existing out-of-band SAS/animated-QR enrollment flow. Once paired, the restricted device emits signed, encrypted domain events (behavioral telemetry, SOS alerts, disable-approval requests) that must reach the partner device, be authenticated and decrypted locally, and be surfaced through a dashboard and push notifications.

This feature builds:
- A server-mediated push relay that never exposes push credentials on-device, with at-least-once dispatch semantics and idempotent client processing.
- A push-provider abstraction supporting GMS (FCM), HMS (Huawei), and no-provider (foreground/periodic sync) Android devices.
- A per-sender cryptographic event chain, with atomic sequence allocation and a staged repair protocol.
- A partner dashboard with explicit cache/staleness semantics.
- A disable-approval request/response flow with correct, epoch-bound event correlation.
- A distributed unpair protocol with an accurate, non-overstated security guarantee.
- Server-side abuse controls independent of client-enforced rate limits.

Cryptographic pairing (SAS/QR), Ed25519 primitives, and SQLCipher-backed local storage are existing infrastructure, extended per Section 5.

---

## 2. Requirements

### Functional
- FR1: An SOS alert produces a locally-rendered notification on the partner device. Delivery is measured in four independent stages (T1–T4, defined in Section 11), not as a single number. Target: T1 (Firestore write → provider acceptance) p50 < 2s, p95 < 6s; T3 (provider acceptance → notification posted on a reachable, non-force-stopped device) p50 < 3s, p95 < 10s.
- FR2: A disable-approval request/response is cryptographically verified and epoch-bound; a response cannot be replayed against a re-paired or already-consumed request.
- FR3: The partner dashboard shows last-known state with an explicit "last synced" timestamp, refreshing on a 15-minute best-effort interval or immediately on a verified push-triggered sync.
- FR4: Either partner can initiate unpair. Local cryptographic shredding is guaranteed on the initiating device. Remote shredding is acknowledgment-based: it completes when the remote device confirms, or the initiating device proceeds to a `LOCAL_ONLY_TERMINATED` state after a 7-day timeout if no confirmation arrives. The system never claims to have remotely erased a device it cannot reach.
- FR5: Push delivery works on GMS Android (FCM) and Huawei devices without Google Play services (HMS Push Kit). Devices with neither provider fall back to foreground-launch and best-effort periodic background synchronization with no real-time delivery guarantee.

### Non-Functional
- NFR1: No push provider credentials exist in the client APK under any circumstance.
- NFR2: Push wire payloads (the data sent through FCM/HMS) never contain plaintext event content, aliases, or behavioral state. The OS-rendered notification tray shows generic text ("New accountability alert") by default; alias/event-type detail is never shown in the system notification tray. Detail is visible only inside the app after the user opens it.
- NFR3: All local persistence uses SQLCipher with an Android Keystore-backed passphrase, consistent with the existing Room 3 schema.
- NFR4: Cost is controlled through named per-service quotas (Cloud Functions `maxInstances`, Firestore document-write rate limits, per-pair event rate limits) plus a budget alert at a defined threshold. This bounds *predictable* cost growth; it is not described as an absolute financial ceiling, because no combination of quotas and alerts can guarantee zero overage in a pay-as-you-go billing model.
- NFR5: Mailbox writes require an active, server-verified pairing record. GMS clients additionally attach a Play Integrity App Check token. HMS/no-GMS clients are authorized through the pairing record plus server-side rate limiting (Section 5, AD-03b) in place of App Check, since Play Integrity requires Google Play services and cannot be required universally.
- NFR6: The HMS SDK is loaded via runtime reflection/availability checks (Section 8, Step 7) and must never cause a `ClassNotFoundException`/`NoClassDefFoundError` on a device without HMS Core installed.
- NFR7: Detecting a local-data wipe (SQLCipher DB absent or undecryptable while other install signals suggest a prior pairing) forces re-enrollment via SAS/QR; it never attempts to silently recover a lost session.

### Constraints
- The Firebase project must be on the Blaze plan; Cloud Functions cannot be deployed on Spark.
- FCM requires Google Play services on the receiving device — this is why the provider abstraction (Section 5) exists.
- Play Integrity App Check requires Google Play services — this is why it is GMS-only (NFR5), not a universal gate.
- Existing pairing (SAS/QR), Ed25519 signing, and local encrypted storage are out of scope for redesign except where Section 5 states an explicit modification.

---

## 3. Existing System Context

**Untouched:** SAS/QR enrollment's cryptographic transcript and public-key exchange; Ed25519 sign/verify primitives; Room 3 + SQLCipher base configuration (Keystore-backed passphrase, `PRAGMA cipher_memory_security`, `PRAGMA secure_delete`); the `Partnership` entity's alias-binding role.

**Modified:**
- SAS/QR enrollment completion now additionally submits a signed pairing proof to a Cloud Function (Section 8, Step 3), not just two independent UID claims.
- The event/hash-chain model moves to one chain per sender, with a transactional `ChainState` row serializing local writes (AD-06b/c).
- `DisableApprovalResponse` carries `request_event_id` plus `pairingEpoch`, and its own hash is computed only after the request reference is fixed.
- Event envelopes (ciphertext, signature, hash) are finalized atomically at creation time, not lazily during sync.

**New components:**
- `Outbox` table keyed by a client-generated stable `messageId` (used as the Firestore document ID — not an auto-ID).
- Cloud Functions v2 push relay with per-installation dispatch state and a Cloud Scheduler-driven retry sweep.
- `PushTransport` abstraction (`FcmPushTransport`, `HmsPushTransport`).
- Server-side `pairings`, `push_installations`, `relay-dispatch`, and `rate-limits` Firestore collections.
- Partner Dashboard Compose screens, approval UI, unpair UI.

---

## 4. Architecture

```
                    ┌─────────────────────────┐
                    │   Partner UI (Compose)  │
                    └────────────┬────────────┘
                                 ▼
                    ┌─────────────────────────┐
                    │ ViewModels / Use Cases   │
                    └──────┬──────────┬───────┘
                           ▼          ▼
              ┌──────────────────┐  ┌──────────────────┐
              │ Room + SQLCipher │  │ Outbox            │
              │ ChainState per   │  │ (messageId = doc  │
              │ sender, events   │  │  id, PENDING/     │
              │                  │  │  SENT/FAILED_*)   │
              └────────┬─────────┘  └────────┬─────────┘
                       └───────────┬──────────┘
                                   ▼
                       ┌─────────────────────┐
                       │ SyncWorker           │
                       │ (transmits the exact │
                       │  bytes created at    │
                       │  event-creation time)│
                       └──────────┬──────────┘
                                  ▼
                       ┌─────────────────────┐
                       │ Firestore Mailbox    │
                       │ doc ID = messageId   │
                       └──────────┬──────────┘
                                  │ onDocumentCreated
                                  ▼
                       ┌─────────────────────┐
                       │ Cloud Function Relay │
                       │ - checks /pairings   │
                       │ - checks rate-limits │
                       │ - per-installation   │
                       │   dispatch records   │
                       └────┬───────────┬─────┘
                            ▼           ▼
                  FcmTransport      HmsTransport
                       (generic wake payload only)
                            │           │
                            └─────┬─────┘
                                  ▼
                  Partner Device Push Handler
                  → records wake signal
                  → fetches mailbox by messageId
                  → verifies signature + chain link
                  → decrypts, commits to Room
                  → renders GENERIC notification
                  → (alias shown only in-app)

  ┌─────────────────────────┐
  │ Cloud Scheduler (2 min)  │──► sweeps /relay-dispatch for
  │                          │    PENDING/RETRYABLE records,
  │                          │    re-attempts per-installation
  └─────────────────────────┘    dispatch, bounded attempts
```

Firestore is transport and synchronization state, never the canonical event store. Room is canonical local state. The Cloud Function relay dispatches at least once per installation and records per-installation outcome; it never assumes a single dispatch attempt is sufficient, and it never deletes a mailbox document on the strength of one installation's success.

---

## 5. Architecture Decisions

**AD-01. Push delivery is server-mediated via Cloud Functions, never client-to-FCM/HMS.** Client-held provider credentials are trivially extracted from an APK.

**AD-02. Firestore mailbox writes require an active `/pairings/{pairId}` record**, where `pairId = min(uidA, uidB) + "_" + max(uidA, uidB)`, computed client-side and validated server-side by checking `request.auth.uid` is a member of the referenced pairing document. This closes the original open-write vulnerability without a centralized identity directory.

**AD-03. GMS clients attach a Firebase App Check (Play Integrity provider) token, enforced via `request.app` in Firestore Security Rules.** App Check tokens are never validated inside the Cloud Function's background trigger context — Firestore-triggered background functions do not receive client App Check tokens; enforcement happens at the Firestore API boundary only, before the write (and therefore the trigger) ever occurs.

**AD-03b. HMS and no-GMS clients are not required to provide Play Integrity attestation, since the provider requires Google Play services.** Their mailbox writes are instead gated by AD-02's pairing check plus the server-side rate limits in AD-23. This is a deliberately asymmetric security posture across device classes, documented as such rather than papered over. **(Amended 2026-09-19 — task #21, owner ruling "enforce both levers"; see ADR #57.)** App Check enforcement is a per-service, project-wide binary switch — an exemption for one device class is not expressible in enforce mode, so this AD is vacuous in production. No-GMS devices now fall to AD-05's local/foreground behavior with the mailbox rail unavailable; the emulator/test path instead runs a registered App Check debug token (a documented test-only path, not a device-class exemption).

**AD-04. Push delivery uses a `PushTransport` abstraction** (`FcmPushTransport`, `HmsPushTransport`), selected server-side per registered installation's `provider` field.

**AD-05. Devices with neither FCM nor HMS available register no push installation** and rely on foreground-launch sync plus a best-effort periodic `WorkManager` job requesting a minimum 15-minute interval. This interval is not a scheduling guarantee — Android's periodic work API is explicitly inexact, and force-stop, hibernation, or vendor restrictions can prevent execution entirely. The UI must reflect this (Section 8, Step 11).

**AD-06a. The cryptographic event chain is one independent chain per sender** (`senderPublicKeyHash` scopes the chain), eliminating the two-writer fork ambiguity of a single shared chain.

**AD-06b. The full event envelope — ciphertext, nonce, key epoch, hash, and signature — is created atomically inside the same Room transaction that inserts the `Outbox` row.** No field of the envelope is computed later during sync. Retries transmit the exact same serialized bytes; the `SyncWorker` never regenerates ciphertext or a signature for an event that already has an `Outbox` row.

**AD-06c. Local same-sender chain-head updates are serialized through a per-sender `ChainState(senderKeyHash) { lastSequence, lastHash, keyEpoch }` row, updated inside the same transaction as AD-06b.** This prevents two concurrent local writers from allocating the same sequence number.

**AD-07. `DisableApprovalResponse` carries `request_event_id` (referencing the already-persisted request event) and `pairingEpoch` (incremented on every re-pair).** A response is rejected if `pairingEpoch` does not match the current partnership epoch, closing the replay-after-re-pair gap.

**AD-08. Chain repair uses a three-stage terminal path: `REPAIR_PENDING → RECOVERY_EXHAUSTED → CHAIN_INCOMPLETE`.** A gap is not declared permanently unrecoverable (`CHAIN_INCOMPLETE`) until the sender has failed to supply the missing range across a 30-day recovery window, distinguishing "partner temporarily offline" from "history genuinely gone." `TAMPER_SUSPECTED` is reserved exclusively for a supplied replay batch that fails signature or hash verification — it is never used for a benign missing-history case, a key-epoch mismatch, or a protocol-version mismatch (those get their own states: `UNKNOWN_KEY_EPOCH`, `PROTOCOL_VERSION_MISMATCH`, `INVALID_FORMAT`).

**AD-08b. Repair requests carry both the last known sequence number and its hash (`expectedPreviousSequence`, `expectedPreviousHash`)**, not sequence alone, so the responding device can prove the returned segment actually connects to the requester's chain head rather than merely matching a sequence number.

**AD-09. Outbound events use the local Room `Outbox` table, keyed by a client-generated stable `messageId`, as the durability boundary.** `messageId` is used directly as the Firestore document ID (not an auto-generated ID), making retries of the same `Outbox` row idempotent at the Firestore layer by construction: a retried write to the same document ID either succeeds once or is a harmless no-op overwrite of identical content.

**AD-10. The Cloud Function relay dispatches at least once per registered installation, tracked via per-installation records in `/relay-dispatch/{messageId}/installations/{installationId}` (`status: PENDING|DISPATCHED|FAILED_RETRYABLE|FAILED_TERMINAL`, `attempts`, `lastAttemptAt`).** A single boolean "dispatched" flag is never used, because it causes either duplicate dispatch (check-then-act race across concurrent trigger invocations) or permanent partial delivery (one installation's success suppressing retry for another's failure). Firestore triggers are at-least-once by design; the system's actual guarantee is **at-least-once provider dispatch, exactly-once logical client processing** — client-side deduplication (AD-10b) is what makes the second half true, not the relay itself.

**AD-10b. The partner device deduplicates using a local `ProcessedEvents(messageId)` table**, checked before any domain-state mutation or notification is created. A `messageId` already present in this table is a no-op.

**AD-10c. A Cloud Scheduler job runs every 2 minutes and sweeps `/relay-dispatch/*/installations/*` for records in `PENDING` or `FAILED_RETRYABLE` with `attempts < 8`, re-invoking dispatch for those specific installations only.** This replaces relying on Firestore's own trigger retry (which retries the whole event, not per-installation) as the mechanism for partial-failure recovery.

**AD-11. Push messages are always a generic, non-sensitive wake signal** (`{ messageId, type: "domain-event" }` in the `data` payload, no `notification` block set by the server). The receiving device fetches the actual envelope from Firestore, verifies and decrypts it, and only then constructs a locally-rendered notification. This separation exists because FCM's `onMessageReceived()` has a short, documented execution window, and a fetch-verify-decrypt pipeline run synchronously inside it risks being paused or terminated before the notification is posted. The locally-rendered notification's tray-visible text is generic by default (NFR2); revealing the alias requires opening the app.

**AD-11b. On receiving `onDeletedMessages()` (FCM) or the HMS equivalent, the client marks `sync_required = true` and performs a full mailbox reconciliation** rather than waiting for the next individual push.

**AD-12. Session encryption uses the existing static-session-key primitive, with `K_epoch = HKDF(pairingRootSecret, epochInfo)` rotated every 500 events or 30 days, whichever comes first.** This is documented accurately as **reducing the exposure window of an individual derived session key**, not as forward secrecy or post-compromise security — compromise of the underlying `pairingRootSecret` itself would allow deterministic re-derivation of past and future epoch keys. A full Double Ratchet migration remains out of scope for this feature. Every event envelope carries an explicit `keyEpoch` field so the receiver selects the correct key deterministically rather than trial-decrypting.

**AD-13a. Unpair is modeled as a distributed protocol**: `UNPAIR_REQUESTED → UNPAIR_ACKNOWLEDGED → LOCAL_SHRED_COMPLETE → REMOTE_SHRED_CONFIRMED → TERMINAL`.

**AD-13b. Local cryptographic shredding on the initiating device is unconditional and immediate once `UNPAIR_REQUESTED` is acted on locally.** Remote shredding is acknowledgment-based: the initiating device's UI and internal state accurately describe it as complete only when `REMOTE_SHRED_CONFIRMED` is received. If no confirmation arrives within 7 days, the initiating device's local state transitions to `LOCAL_ONLY_TERMINATED` — not `TERMINAL` — and the UI states plainly that the partner device's data could not be confirmed erased. No distributed protocol can force an unreachable device to execute code; the specification does not claim otherwise.

**AD-14. Cryptographic shredding's authoritative security claim is Android Keystore key deletion.** `PRAGMA secure_delete`/`VACUUM` are defense-in-depth only, never described as guaranteeing physical flash erasure (wear-leveling on eMMC/UFS defeats that guarantee at the hardware layer).

**AD-15. TLS uses the platform/OkHttp negotiated default, with an enforced floor of TLS 1.2.** TLS 1.3 is not hard-pinned because it is unavailable below API 29, below the stated minSdk 26.

**AD-16. Time-sensitive local state (SOS cooldown, approval expiry) is persisted as UTC wall-clock timestamps with a backward-rollback guard** (reject if current wall time < last-recorded event time). Forward clock manipulation remains a residual, accepted risk (Section 15, R-07) for purely local cooldown enforcement; server-visible expiry decisions (e.g., approval-request expiry as enforced by the responding partner) use the Firestore server timestamp captured at write time as the authoritative reference where the check matters for security, not just UX.

**AD-17a. Firestore's database location and the Cloud Function's deployment region are the same region.** Cross-region calls between a Firestore trigger and its handling function introduce material added latency; colocating them removes that variable entirely, independent of which specific region is chosen.

**AD-17b. The initial deployment region is `us-central1`, chosen as a concrete, reproducible default for both Firestore and Functions.** This is revisited (Section 16, OQ-4) once production telemetry shows the actual geographic distribution of paired users; the load-bearing decision is AD-17a (colocation), not the specific region name.

**AD-18a. Push installations are modeled as `/users/{uid}/push_installations/{installationId}`**, one document per device/provider pair, supporting multiple devices per user.

**AD-18b. A mailbox document is retained for a minimum of 7 days or until every currently-registered installation for the recipient has an acknowledgment record, whichever is later, up to a maximum retention of 30 days.** It is never deleted merely because one installation processed it — doing so under a multi-device model causes a second device to silently lose the event. Acknowledgment is recorded per-installation (`/mailboxes/{uid}/messages/{messageId}/acks/{installationId}`); a scheduled cleanup function deletes fully-acknowledged-or-expired documents.

**AD-18c. `push_installations` has an explicit lifecycle**: immutable `installationId`, a maximum of 5 active installations per user (oldest-unused evicted beyond that), and automatic staleness expiry after 90 days without a token refresh.

**AD-19. The Firestore mailbox payload's byte-size limit (4096 bytes) is validated in the Cloud Function** by measuring the actual decoded length of the base64 `payload` field, after first validating it is well-formed base64 — not via `request.resource.data.size()` in Security Rules, which counts map field count, not bytes, and cannot enforce this. Rules retain `hasOnly()` field-set and type validation as defense-in-depth.

**AD-20a. Cloud Functions cost exposure is bounded by a `maxInstances` cap**, sized from an explicit load estimate (expected peak events/second × execution time), not chosen arbitrarily — the cap is deliberately also a backpressure boundary, and Section 11 documents the calculation that sets its value once real traffic data exists (Section 16, OQ-1).

**AD-20b. "Cost control" is implemented as named per-service quotas (Cloud Functions `maxInstances`, Firestore per-pair write-rate limit via AD-23, push-installation cap via AD-18c) plus a GCP budget alert at 80% of a defined monthly figure (Section 16, OQ-5) — not described as a hard financial ceiling**, since no combination of application-level quotas can guarantee zero billing overage under Google's pay-as-you-go model.

**AD-21. The HMS Push Kit SDK is isolated via runtime reflection-based availability checks** (Section 8, Step 7), so its absence never causes a classload failure on a GMS-only device.

**AD-22. A local-data-wipe state (SQLCipher DB missing or fails to decrypt with the expected Keystore-backed key, on a device that otherwise shows signs of prior pairing) is detected at app start and forces re-enrollment via SAS/QR.** No attempt is made to reconstruct or recover the prior session; the corresponding server-side `/pairings/{pairId}` record is left to expire via AD-18c-style staleness handling rather than being actively torn down by the client (a device that just lost its local state is not in a position to reliably authenticate a teardown request).

**AD-23. Server-side rate limits are enforced in the Cloud Function before any push dispatch is attempted**: max 10 events per pair per minute, max 5 SOS events per pair per hour, max 200 mailbox messages per sender per day. Limits are tracked via fixed-window counters in `/rate-limits/{pairId}` and are independent of the client-side SOS cooldown (Section 8, Step 12b), which remains as a UX-layer control, not a security boundary.

---

## 6. Data Flow

**SOS send:**
1. User taps SOS FAB.
2. In one Room transaction: `SendSosUseCase` (a) reads and atomically updates `ChainState` for the local sender (AD-06c), (b) constructs the complete signed/encrypted envelope including `keyEpoch` (AD-06b), (c) generates a stable `messageId` (UUID), and (d) inserts the `Outbox` row (`status = PENDING`, `messageId`, serialized envelope bytes).
3. `SyncWorker` (expedited work request) reads the `PENDING` `Outbox` row and writes the **exact stored bytes** to `/mailboxes/{partnerUid}/messages/{messageId}` using `messageId` as the document ID (not an auto-ID).
4. Firestore `onDocumentCreated` fires the relay.
5. Relay: (a) checks `/pairings/{pairId}` is active, (b) checks `/rate-limits/{pairId}` (AD-23), (c) for GMS senders, defensively re-checks App Check status is consistent with rules enforcement, (d) reads `/users/{partnerUid}/push_installations/*`, (e) creates a `/relay-dispatch/{messageId}/installations/{installationId}` record per installation in `PENDING`, (f) dispatches a generic data-only wake message to each via the matching `PushTransport`, (g) updates each installation's record to `DISPATCHED` or `FAILED_RETRYABLE`/`FAILED_TERMINAL` based on the provider's response.
6. Cloud Scheduler sweep (every 2 minutes) retries any `PENDING`/`FAILED_RETRYABLE` installation records up to 8 attempts.
7. Partner device receives the wake message, records it, fetches `/mailboxes/{partnerUid}/messages/{messageId}`, checks `ProcessedEvents` for `messageId` (AD-10b) — if already processed, no-op; otherwise verifies signature, verifies chain link (using `expectedPreviousSequence`/`expectedPreviousHash` if repairing), decrypts using the envelope's `keyEpoch`, commits to Room, inserts into `ProcessedEvents`, writes an acknowledgment record (AD-18b), and renders a generic notification.
8. Sending device's `Outbox` row is marked `SENT` once the Firestore write succeeds (independent of push delivery — the write itself, not the push, is what "sent" means at this layer).

**Disable approval:** identical relay path; request/response payloads carry `request_event_id` and `pairingEpoch` (AD-07); the responding partner's UI shows the hash-chain verification badge before allowing approve/reject.

**Unpair:** per AD-13a/b; UI language is explicit about the local-guaranteed / remote-best-effort distinction at every stage, not just internally.

---

## 7. Implementation Sequence

1. Firestore data model & security rules (`pairings`, `push_installations`, `mailboxes`, `relay-dispatch`, `rate-limits`).
2. Firebase Anonymous Auth + App Check (Play Integrity, GMS-only enforcement path).
3. Pairing-time server registration with signed pairing proof (Step 3).
4. Local schema: `Outbox`, `ChainState`, `ProcessedEvents`, corrected `DisableApprovalResponse` fields.
5. Cloud Function relay with per-installation dispatch state.
6. Cloud Scheduler retry sweep.
7. `FcmPushTransport`.
8. `HmsPushTransport` with reflection-based isolation.
9. `SyncWorker` (transmits pre-built envelope bytes only).
10. Push receive handlers (fetch/dedup/verify/decrypt/commit/notify), including `onDeletedMessages()` reconciliation.
11. Chain repair protocol (staged terminal states, hash+sequence repair requests).
12. Partner Dashboard UI.
13. Approval UI + epoch-bound state machine.
14. Unpair protocol + UI (accurate local/remote language).
15. Session key rotation (`keyEpoch`).
16. Notification permission flow + notification channels.
17. Data-wipe detection.
18. Server-side rate limiting.
19. Monitoring/alerting wiring.
20. Full test suite (Section 12) before rollout.

---

## 8. Detailed Implementation Steps

**Step 1 — Firestore data model & rules**
```
/pairings/{pairId}                         // pairId = min(uidA,uidB)+"_"+max(uidA,uidB)
  { uidA, uidB, members: [uidA, uidB], status: "active"|"terminated", pairingEpoch, createdAt }

/mailboxes/{recipientUid}/messages/{messageId}   // messageId is client-generated, used as doc ID
  { senderId, senderUid, pairId, payload, keyEpoch, timestamp }
  /acks/{installationId} { ackedAt }

/users/{uid}/push_installations/{installationId}
  { provider: "fcm"|"hms", token, platform, appVersion, updatedAt, createdAt }

/relay-dispatch/{messageId}/installations/{installationId}
  { status: "PENDING"|"DISPATCHED"|"FAILED_RETRYABLE"|"FAILED_TERMINAL", attempts, lastAttemptAt }

/rate-limits/{pairId}
  { windowStart, eventCount, sosCount, senderDailyCounts: { [uid]: count } }
```
Rules (core; implement full type/format validation for every field):
```
match /pairings/{pairId} {
  allow read: if request.auth.uid in resource.data.members;
  allow write: if false; // written only by the trusted enrollment Cloud Function
}
match /mailboxes/{recipientUid}/messages/{messageId} {
  allow read: if request.auth.uid == recipientUid;
  allow delete: if false; // deletion only via the scheduled cleanup function
  allow create: if request.auth != null
    && request.resource.data.senderUid == request.auth.uid
    && request.resource.data.pairId is string
    && exists(/databases/$(database)/documents/pairings/$(request.resource.data.pairId))
    && get(/databases/$(database)/documents/pairings/$(request.resource.data.pairId)).data.status == "active"
    && request.auth.uid in get(/databases/$(database)/documents/pairings/$(request.resource.data.pairId)).data.members
    && request.resource.data.keys().hasOnly(['senderId','senderUid','pairId','payload','keyEpoch','timestamp'])
    && request.resource.data.senderId is string
    && request.resource.data.payload is string
    && request.resource.data.keyEpoch is int
    && request.resource.data.timestamp == request.time;
  // GMS senders additionally require App Check at the project/API level (Console setting),
  // enforced independent of this rule text, per AD-03.
}
match /users/{uid}/push_installations/{installationId} {
  allow read, write: if request.auth.uid == uid;
}
match /relay-dispatch/{document=**} { allow read, write: if false; } // function-only
match /rate-limits/{document=**} { allow read, write: if false; }   // function-only
```

**Step 3 — Pairing-time server registration**
Both devices, on local SAS/QR transcript agreement, each call a `registerPairing` Cloud Function with:
```
{ ownUid, partnerUid, publicKeyHash, transcriptHash, protocolVersion, signature }
```
`signature` is over `(ownUid, partnerUid, publicKeyHash, transcriptHash)` using the device's existing signing key. The function writes `/pairings/{pairId}` only when both sides' calls arrive within a 5-minute window **and** their `transcriptHash` values match. This binds the cloud authorization record to the actual cryptographic transcript rather than trusting two independent UID claims.

**Step 4 — Local schema**
```
Outbox: (messageId PK, eventId, destinationUid, envelopeBytes, status, createdAt, lastAttemptAt, attemptCount)
ChainState: (senderKeyHash PK, lastSequence, lastHash, keyEpoch)
ProcessedEvents: (messageId PK, processedAt)
DisableApprovalResponse fields: request_event_id (String), pairingEpoch (Int) — replaces the prior hash-based reference.
```
Event creation (`ProcessEventUseCase`) runs as a single Room transaction:
1. Read `ChainState` for the local `senderKeyHash` (or initialize if first event).
2. Compute `nextSequence = lastSequence + 1`.
3. Build the envelope: encrypt payload under the current `keyEpoch`'s key, compute `hash = SHA256(lastHash || ciphertext || timestamp || nextSequence)`, sign `hash` with the sender's Ed25519 key.
4. Update `ChainState` to `(nextSequence, hash, keyEpoch)`.
5. Insert `DomainEvent` row and `Outbox` row (`messageId` generated once here, never regenerated).

**Step 5 — Cloud Function relay**
```javascript
onDocumentCreated('/mailboxes/{recipientUid}/messages/{messageId}', async (event) => {
  const { senderUid, pairId, payload, keyEpoch } = event.data;

  // Validate base64, then byte length
  if (!isValidBase64(payload)) return markInvalid(event.params.messageId, 'INVALID_FORMAT');
  if (Buffer.byteLength(payload, 'base64') > 4096) return markInvalid(event.params.messageId, 'OVERSIZED');

  // Defense-in-depth pairing re-check
  if (!(await pairingActive(pairId, senderUid, event.params.recipientUid))) {
    return markRejected(event.params.messageId, 'PAIRING_INVALID');
  }

  // Rate limiting (AD-23) — reject before any dispatch work
  if (!(await checkAndIncrementRateLimit(pairId, senderUid))) {
    return markRejected(event.params.messageId, 'RATE_LIMITED');
  }

  const installations = await getInstallations(event.params.recipientUid);
  await Promise.all(installations.map(async (inst) => {
    await createDispatchRecord(event.params.messageId, inst.id, 'PENDING');
    try {
      await dispatchViaProvider(inst, event.params.messageId); // generic wake payload only
      await updateDispatchRecord(event.params.messageId, inst.id, 'DISPATCHED');
    } catch (err) {
      if (isTokenInvalid(err)) {
        await markInstallationStale(inst.id);
        await updateDispatchRecord(event.params.messageId, inst.id, 'FAILED_TERMINAL');
      } else {
        await updateDispatchRecord(event.params.messageId, inst.id, 'FAILED_RETRYABLE');
      }
    }
  }));
});
```
Retry policy: 2nd-gen event-driven retry (`retry: true`) is **disabled** at the trigger level — retries of the whole event are not the mechanism used, because that would re-run rate limiting and pairing checks redundantly and does not address per-installation partial failure. Partial-failure retry is handled exclusively by the Cloud Scheduler sweep (Step 6), which is the single source of retry truth for dispatch.

**Step 6 — Cloud Scheduler retry sweep**
A scheduled function (every 2 minutes) queries `/relay-dispatch/*/installations/*` (via a collection-group query) for `status in [PENDING, FAILED_RETRYABLE]` and `attempts < 8`, and re-invokes `dispatchViaProvider` for exactly those installation records, incrementing `attempts` each time. After 8 attempts, a record moves to `FAILED_TERMINAL` and is not retried further; this is surfaced in monitoring (Section 14) but does not block the recipient's other installations or the sender's own `Outbox` state, which already reflects a successful Firestore write regardless of downstream push outcome.

**Step 7 — HMS isolation**
```kotlin
fun isHmsAvailable(context: Context): Boolean = try {
    val clazz = Class.forName("com.huawei.hms.api.HuaweiApiAvailability")
    val instance = clazz.getMethod("getInstance").invoke(null)
    (clazz.getMethod("isHuaweiMobileServicesAvailable", Context::class.java)
        .invoke(instance, context) as Int) == 0
} catch (e: Exception) { false }
```
`HmsPushTransport` and its SDK dependency are only referenced behind this check; no HMS class is loaded eagerly at application start on a GMS-only device.

**Step 9 — Push receive handling**
```
onMessageReceived / onMessageReceivedFromHms(message):
  if not ProcessedEvents.contains(message.messageId):
    envelope = fetchMailboxDocument(message.messageId)
    if envelope missing: return  // already cleaned up or invalid; no-op
    if not verifySignature(envelope): logSecurityEvent(SIGNATURE_INVALID); return
    chainResult = verifyChainLink(envelope)  // OK | GAP | UNKNOWN_KEY_EPOCH | PROTOCOL_MISMATCH
    when (chainResult):
      OK -> decrypt(envelope, envelope.keyEpoch) -> commitToRoom() -> ProcessedEvents.insert(messageId)
            -> writeAck(messageId) -> renderGenericNotification()
      GAP -> bufferOrphan(envelope) -> initiateRepair()  // Step 10
      UNKNOWN_KEY_EPOCH -> logSecurityEvent(UNKNOWN_KEY_EPOCH); do not apply
      PROTOCOL_MISMATCH -> logSecurityEvent(PROTOCOL_VERSION_MISMATCH); do not apply

onDeletedMessages():
  sync_required = true
  triggerFullMailboxReconciliation()
```
Mailbox documents are never deleted by the client in this path — deletion is exclusively the scheduled cleanup function's responsibility, per AD-18b.

**Step 10 — Chain repair (staged)**
1. `REPAIR_PENDING`: buffer orphan, write `SyncRequest(expectedPreviousSequence, expectedPreviousHash)` to sender's mailbox.
2. Sender replies with the connecting segment (verified against the supplied hash, not sequence alone).
3. If no reply arrives and the requester continues to receive newer events referencing the same broken point, escalate to `RECOVERY_EXHAUSTED` after repeated unanswered `SyncRequest` attempts over 30 days.
4. Only after `RECOVERY_EXHAUSTED` with no successful recovery does the chain move to terminal `CHAIN_INCOMPLETE` — a neutral state, never conflated with `TAMPER_SUSPECTED`.
5. `TAMPER_SUSPECTED` triggers exclusively when a supplied replay segment fails signature or hash verification; it blocks further automatic trust of that sender pending manual re-pair.

**Step 12 — Approval state machine**
States: `REQUESTED → APPROVED | REJECTED | EXPIRED (72h, server-timestamp-based) | CONSUMED`. Transition out of `REQUESTED` occurs exactly once via a `WHERE status = 'REQUESTED'` transactional guard. A response is additionally rejected if its `pairingEpoch` does not match the current partnership epoch (AD-07), closing replay after a re-pair.

**Step 12b — Client-side SOS cooldown**
Retained as a UX control (persisted wall-clock timestamp with rollback guard, per AD-16) — explicitly documented as *not* the security boundary against abuse; that role belongs to AD-23's server-side rate limits.

**Step 13 — Unpair protocol UI**
UI copy is explicit at every state: "Your data on this device has been erased" (shown as soon as local shred completes, regardless of remote state) versus "Waiting to confirm your partner's device has also erased its data" (shown until `REMOTE_SHRED_CONFIRMED` or the 7-day timeout, at which point it changes to "Could not confirm your partner's device erased its data").

**Step 14 — Session key rotation**
`SessionKeyManager.rotateIfDue()`: rotate when `eventsSinceRotation >= 500 OR daysSinceRotation >= 30`. New `keyEpoch` is written to `ChainState` and applied to all subsequently created envelopes. Prior epoch's key is retained for 7 days (to support in-flight repair replays referencing it), then zeroed.

**Step 16 — Notification permission + channels**
Flow: check `areNotificationsEnabled()` → if false, show rationale → request `POST_NOTIFICATIONS` (Android 13+) → if denied, show persistent dashboard banner with a deep link to system notification settings → re-check on every foreground.
Channels: `partner_alerts` (SOS, high importance), `approval_requests` (high importance), `sync_status` (low importance, used only for internal/debug visibility, not shown to the user in production builds).

**Step 17 — Data-wipe detection**
On app start: if a `Partnership` record's expected SQLCipher-backed database fails to open/decrypt with the Keystore key that should exist for a previously-paired device (detected via a Keystore-alias presence check independent of the DB itself), treat as data wipe: clear any partial local state, do not attempt recovery, route to the SAS/QR re-enrollment flow.

**Step 18 — Server-side rate limiting**
Implemented as shown in Step 5; thresholds per AD-23. Rejected writes surface to the sending client as a `RATE_LIMITED` mailbox-write failure, distinct from a network failure, so the UI can message it accurately rather than showing a generic "couldn't send" error.

---

## 9. Error Handling

| Condition | Handling |
|---|---|
| Firestore write timeout/unavailable | `Outbox` row already persisted with full envelope before the network attempt; `SyncWorker` retries with WorkManager backoff. |
| Duplicate relay trigger invocation | No incremental harm: per-installation dispatch records are keyed by `(messageId, installationId)`, so a second invocation observing existing `DISPATCHED` records for all installations does no further work; any installation still `PENDING` is legitimately retried, which is the intended at-least-once behavior, not a bug. |
| One installation fails, others succeed | Per-installation dispatch records (AD-10) mean the failing installation is retried by the Scheduler sweep independent of the succeeding ones. |
| FCM/HMS reports invalid/stale token | Installation marked stale (AD-18c); dispatch record for that installation set `FAILED_TERMINAL`; client re-registers on next `onNewToken()`/HMS equivalent or cold launch. |
| `onDeletedMessages()` received | Full mailbox reconciliation triggered (AD-11b), not a wait for the next push. |
| Signature verification fails | Event not applied; logged as `SIGNATURE_INVALID` without payload content. |
| Chain sequence gap | Staged repair (Step 10); never silently dropped, never immediately `TAMPER_SUSPECTED`. |
| Repair cannot recover history within 30 days | `CHAIN_INCOMPLETE`; UI shows a neutral "some history could not be synced" state. |
| Replay batch fails verification | `TAMPER_SUSPECTED`; further automatic trust of that sender blocked pending re-pair. |
| Approval response for `CONSUMED`/`EXPIRED`/wrong-epoch request | Rejected by the transactional guard and epoch check (Step 12); logged, not applied. |
| Partner unreachable during unpair | AD-13b path: local shred proceeds unconditionally; remote state accurately reported as unconfirmed, then `LOCAL_ONLY_TERMINATED` after 7 days. |
| Server-side rate limit hit | Mailbox write rejected with `RATE_LIMITED`; distinct client-visible state from a network failure. |
| Notification permission denied | Persistent dashboard banner (Step 16); delivery is not silently claimed. |
| App force-stopped on receiving device | No client-side mitigation exists; documented platform ceiling (Section 15, R-01), not coded around. |
| Device has neither FCM nor HMS | No push installation registered; foreground/best-effort periodic sync only (AD-05); expected, not a failure state. |
| Local data wipe detected | Forced re-enrollment via SAS/QR (Step 17); no silent recovery attempted. |
| HMS SDK absent on device | `isHmsAvailable()` returns false; `HmsPushTransport` never referenced; no crash (Step 7). |

---

## 10. Security Requirements

- No push-provider credentials of any kind exist in the client APK, in any build variant.
- Play Integrity App Check is enforced via `request.app` in Firestore Security Rules for GMS clients; it is never checked inside a Firestore-triggered Cloud Function's execution context, and it is never required from HMS/no-GMS clients.
- Mailbox write authorization requires an active `/pairings/{pairId}` record created only by the trusted `registerPairing` function, which itself requires matching signed transcript hashes from both devices (Step 3) — not merely two independent UID claims.
- Every Firestore write path uses `hasOnly()` for field-set validation, plus explicit type checks.
- Push wire payloads and OS-rendered notification text never contain plaintext event content, aliases, or behavioral state (AD-11, NFR2).
- Local persistence uses SQLCipher with an Android Keystore-backed passphrase (StrongBox where available, TEE fallback — never required).
- `android:allowBackup="false"` is set.
- Cryptographic shredding's authoritative claim is Keystore key deletion (AD-14); `secure_delete`/`VACUUM` are never documented as a physical-erasure guarantee.
- Session keys rotate per AD-12, explicitly documented as bounding exposure of an individual derived key, not as forward secrecy against root-secret compromise.
- Every applied event is independently verified (signature + chain link + key-epoch match) regardless of how its write was authorized; write authorization (AD-02/AD-03/AD-03b) and content trust (cryptographic verification) are separate, independently-enforced layers.
- Replay protection: `ProcessedEvents` dedup (AD-10b), the approval `REQUESTED`-only transactional guard plus `pairingEpoch` check (AD-07, Step 12), and per-sender monotonic sequence verification (AD-06a/c) together prevent reapplication of a previously consumed or superseded event.
- Server-side rate limits (AD-23) are the authoritative abuse control; client-side cooldowns are UX only and must never be relied upon as the security boundary in code review or design discussion.

---

## 11. Performance Requirements

Delivery is measured and targeted in four distinct stages, not as one number:

| Stage | Definition | Target (p50 / p95) |
|---|---|---|
| T1 | `Outbox` write attempt → Firestore write confirmed | < 1s / < 3s |
| T2 | Firestore write confirmed → provider (FCM/HMS) reports acceptance | < 1s / < 3s |
| T3 | Provider acceptance → notification posted on a reachable, non-force-stopped device | < 3s / < 10s |
| T4 | Event sent → partner's app-level acknowledgment record written (Section 14) | best-effort, no hard SLO in V1 |

FR1's stated overall targets (Section 2) are T1+T2 combined for "provider acceptance" and T1+T2+T3 combined for "notification posted," matching how they're actually measurable — a single "notification tray" number is never reported as one metric without stating which stages it includes.

- Cloud Function `maxInstances` is sized from an explicit load calculation: `expected_peak_events_per_second × average_execution_duration_seconds`, recomputed once real traffic data exists (Section 16, OQ-1); it is not chosen as an arbitrary round number.
- Cold start is not separately optimized with `minInstances > 0` in V1; accepted as a bounded latency cost against the T2 target, revisited only if measured cold-start latency threatens that target in production (Section 16, OQ-2).
- Dashboard initial render uses cached local Room state with zero network round-trip; the 15-minute refresh (best-effort, not exact — Android's periodic work API is inherently inexact) never blocks initial paint.
- Server-side rate limiting (AD-23) is evaluated before any expensive fan-out work in the relay, so abusive traffic is rejected cheaply rather than after paying for dispatch attempts.

---

## 12. Testing Requirements

**Unit / JVM:**
- Ed25519 sign/verify known-answer tests.
- Per-sender chain construction and link verification across multiple senders.
- Atomic envelope creation: verify that retrying a `SyncWorker` send transmits byte-identical envelope content, never regenerated ciphertext/signature.
- Concurrent `ChainState` update: fire concurrent coroutines attempting to create an event for the same sender; verify exactly one succeeds per sequence number and no duplicate sequence is produced.
- HKDF key-epoch rotation triggers at both the 500-event and 30-day thresholds; verify old-key retention/discard timing.
- `request_event_id` + `pairingEpoch` correlation: reject a response to a non-existent request, an already-consumed request, and a request from a prior pairing epoch.

**Instrumentation:**
- SQLCipher migration tests (`MigrationTestHelper`, full encrypted `SupportFactory` stack): clean install, incremental migration, interrupted migration, downgrade rejection.
- `Outbox` crash-consistency: kill the process between transaction commit and `SyncWorker` scheduling; verify reconciliation on next launch.
- Force-stop test: force-stop the app, send an event from the partner, verify no delivery until manual relaunch (documents expected behavior, does not assert delivery).
- HMS-absent device: verify `isHmsAvailable()` returns false and no crash occurs anywhere in the push-registration or dispatch path.
- Data-wipe detection: simulate a missing/undecryptable local DB on a device with prior-pairing signals; verify forced re-enrollment, no crash, no silent data loss beyond the wipe itself.
- Notification-permission-denied path: verify banner appears; verify no crash when a notification is attempted while denied.
- Chain repair: simulate a temporary gap (recovers within window) versus a permanent gap (30-day exhaustion) and verify `CHAIN_INCOMPLETE` versus successful catch-up are both reached correctly, and that neither path produces `TAMPER_SUSPECTED`.
- Tamper detection: simulate a replay batch with an invalid signature; verify `TAMPER_SUSPECTED` and blocked automatic trust.
- Unpair: simulate partner-reachable (full confirmation path) and partner-unreachable through the full 7-day timeout (time-accelerated), verifying `LOCAL_ONLY_TERMINATED` versus `TERMINAL` are reported distinctly and accurately in the UI.
- `onDeletedMessages()`: verify full reconciliation is triggered and recovers events that would otherwise have been missed.

**Security rules (Firebase emulator):**
- Positive: paired GMS user with valid App Check token can write to partner's mailbox.
- Positive: paired HMS/no-GMS user without an App Check token can still write (per AD-03b).
- Negative: unpaired authenticated user cannot write to an arbitrary mailbox.
- Negative: user cannot read another user's `push_installations`.
- Negative: client cannot write directly to `/pairings/{pairId}`, `/relay-dispatch/*`, or `/rate-limits/*`.
- Negative: a write with extra fields beyond schema, or a wrong-typed field, is rejected.

**Cloud Function (emulator + unit tests):**
- Duplicate trigger invocation for one `messageId` results in per-installation dispatch records that reach `DISPATCHED` exactly once each, with no duplicate push actually sent to an installation already `DISPATCHED`.
- One installation failing and another succeeding results in independent per-installation states, with the Scheduler sweep correctly retrying only the failed one.
- Oversized or malformed-base64 payload is rejected before any dispatch attempt.
- Rate limit rejection occurs before any installation lookup or dispatch attempt (verified via call-count assertions on the dispatch mock).
- Scheduler sweep respects the 8-attempt cap and correctly transitions to `FAILED_TERMINAL`.

**Device/provider matrix:**
- GMS Android device: FCM path across foreground/background/Doze.
- Huawei/HMS device: HMS Push Kit path.
- No-GMS, no-HMS device/emulator: graceful fallback, correct UI messaging, no crash.
- Android 13+ device with notification permission denied.
- Multi-device: two installations for one user, verify both receive and process the same event exactly once each, and the mailbox document is not deleted until both have acknowledged.

---

## 13. Deployment Requirements

- Blaze plan enabled before any Cloud Function deployment.
- Firestore database and all Cloud Functions deployed to the same region (`us-central1` initially, per AD-17a/b).
- Node.js 22 runtime; `firebase-admin` and `firebase-functions` versions pinned in `package.json` with a lockfile committed.
- Firebase Android BoM version pinned explicitly; HMS Push Kit SDK version pinned explicitly.
- Firestore Security Rules deployed and validated against the full emulator test suite (Section 12) as a CI gate before every rules deployment.
- App Check enforcement deployed in monitor mode first, verified against real GMS traffic for false positives, then switched to enforce mode — HMS/no-GMS traffic is never subject to this gate at any stage (AD-03b).
- Cloud Function deployment is versioned (tagged source revisions); rollback is redeploying a prior tagged revision, never a delete-then-redeploy cycle.
- Cloud Scheduler job for the dispatch-retry sweep is deployed alongside the relay function and is treated as a required component, not optional infrastructure — the relay's at-least-once guarantee depends on it.
- Mailbox cleanup function (deletes fully-acknowledged-or-expired documents per AD-18b) is deployed as a separate scheduled function.
- Staged client rollout: `push_installations` registration is gated behind a feature flag, independent of app-store release cadence.

---

## 14. Monitoring Requirements

- Cloud Function invocation count, error rate, duration — alert if error rate exceeds 5% over 15 minutes.
- Per-installation dispatch outcome distribution (`DISPATCHED` / `FAILED_RETRYABLE` / `FAILED_TERMINAL`), broken down by provider.
- Scheduler sweep: count of records retried per run, count reaching `FAILED_TERMINAL` after exhausting attempts.
- Rate-limit rejection count per pair — a sustained high rate for one pair is a signal worth investigating, not just a cost control.
- `CHAIN_INCOMPLETE` and `TAMPER_SUSPECTED` occurrence counts, tracked separately — rising `TAMPER_SUSPECTED` is a security signal; rising `CHAIN_INCOMPLETE` is a product/retention signal.
- T1/T2/T3 latency distributions (Section 11), reported separately, not blended into one delivery number.
- `Outbox` age distribution on the client (time in `PENDING`/`FAILED_RETRYABLE`), reported via existing telemetry, independent of server-side metrics.
- Optional app-level acknowledgment records (T4) aggregated to distinguish "server accepted" from "partner app actually processed," closing the observability gap that provider-acceptance metrics alone cannot.
- GCP budget alert at 80% of the monthly figure set in Section 16 (OQ-5); `maxInstances` and the per-service quotas in AD-20b monitored as a set, not just the budget alert in isolation.
- Notification-permission-denied rate among active pairs.
- Data-wipe-detection trigger count (Step 17), to gauge how often re-enrollment is being forced and whether it correlates with a specific device class or OS version.

---

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| R-01: Force-stop/hibernation on receiving device prevents delivery | High (platform-driven) | High | No software fix exists; dashboard "last synced" and optional app-level ack (T4) let the partner infer staleness | `Outbox`/relay success with no corresponding T4 acknowledgment pattern over time for a given pair |
| R-02: HMS/no-GMS write path lacks Play Integrity attestation | Medium | Medium | Compensated by AD-02 pairing gate + AD-23 server-side rate limits; explicitly accepted as an asymmetric posture, not hidden | Rate-limit rejection metrics on the HMS/no-GMS path |
| R-03: Relay Scheduler sweep itself fails or is delayed | Low | Medium | Sweep failure alerting (Section 14); dispatch records remain durable in Firestore regardless, so a delayed sweep is a latency problem, not a data-loss problem | Scheduler execution success/failure metric |
| R-04: Session/root-secret compromise | Low | High | AD-12 rotation bounds exposure of an individual epoch key; root-secret compromise remains a residual risk outside this feature's scope (full Double Ratchet migration is future work) | No automated detection for compromise itself |
| R-05: Chain repair genuinely unrecoverable (uninstall, retention expiry) | Medium | Low-Medium | `CHAIN_INCOMPLETE` after the 30-day recovery window avoids false tamper alarms | `CHAIN_INCOMPLETE` rate metric |
| R-06: Huawei Push Kit API/account changes break the HMS path independently | Low | Medium (HMS-installation users only) | Provider abstraction isolates blast radius to `HmsPushTransport` | HMS-specific dispatch failure rate |
| R-07: Forward clock manipulation bypasses local SOS cooldown | Low | Low | Cooldown is UX-layer only (Step 12b); AD-23 server-side rate limits are the actual security boundary and are immune to local clock manipulation | Not separately monitored; accepted residual risk given AD-23's coverage |
| R-08: Unpair partner unreachable beyond 7 days | Low | Medium | Explicit `LOCAL_ONLY_TERMINATED` state and accurate UI language (AD-13b) rather than a false completion claim | Unpair-state-machine stuck-in-`UNPAIR_REQUESTED`/timed-out-to-`LOCAL_ONLY_TERMINATED` metric |
| R-09: Multi-device mailbox retention (30-day max) storage growth | Low | Low | Bounded by the 30-day cap and per-installation ack-driven early cleanup (AD-18b); monitored via Firestore storage metrics | Firestore stored-data metric against Section 11's cost model |
| R-10: `us-central1` region unsuitable for actual user geography | Medium (unknown until measured) | Medium | Colocation (AD-17a) already removes the cross-region penalty; region-choice-specific latency is a tunable, not a structural risk | T1/T2 latency distributions by user region, once available |

---

## 16. Open Questions

These do not block starting Steps 1–4.

- **OQ-1:** What is the actual expected daily active pair count and per-pair event volume? Needed to compute AD-20a's `maxInstances` sizing and to validate the AD-23 rate-limit thresholds are set correctly (not so tight they block legitimate use, not so loose they fail to bound abuse).
- **OQ-2:** Is `minInstances = 1` (small ongoing cost) justified once production T2 latency is measured against the Section 11 target? Decide after two weeks of production telemetry.
- **OQ-3:** Should the product surface a partner-visible heartbeat/last-active signal to help distinguish "no news" from "force-stopped/uninstalled" (R-01)? Product/UX decision, not an engineering blocker.
- **OQ-4:** Should `us-central1` be revisited for user-geography reasons once real distribution data exists (R-10)? Any change must keep Firestore and Functions colocated (AD-17a is the invariant; the specific region is not).
- **OQ-5:** What is the acceptable monthly budget-alert threshold (AD-20b)? **ANSWERED 2026-09-19 (owner): $2/month** (owner revised from $5 the same day). The budget + 80%/100% threshold alerts are configured on the project (task #34; `app/docs/MyExodus Monitoring.md` §3-4).
- **OQ-6:** Should the optional app-level acknowledgment (T4, Section 14) be built in V1 or deferred to a fast-follow, given it materially improves observability but is not required for the core delivery path to function?

---

## 17. Acceptance Criteria

*(Status audit 2026-09-19 — every item re-checked against the implementation, task list, and recorded runs; evidence noted per line. Checked = verified, open = named blocker, N/A = user ruling/plan mapping.)*

- [x] An unpaired, authenticated user cannot write to another user's mailbox (rules emulator test). — `rules-test` suite, negative case.
- [x] A user cannot read another user's `push_installations`, `relay-dispatch`, or `rate-limits` documents. — `rules-test` suite, negative cases.
- [x] A GMS client without a valid App Check token is rejected; an HMS/no-GMS client without one is accepted per AD-03b, both verified by emulator tests. — **closed 2026-09-19 (task #21, ADR #57):** enforcement activated on the owner's ruling (`firestore.googleapis.com` ENFORCED 05:21:42Z + `registerPairing` `enforceAppCheck: true` deployed). Rejection half verified on emulator-5554: no-token run → `PERMISSION_DENIED`, with an airtight A/B — the same run 40 s later with the registered debug token wrote the identical document on the same device/rules/code. The AD-03b acceptance half is **void** (enforcement is binary project-wide; AD-03b amended above) and its role is taken by the registered debug-token path. X6532 attested client accepted post-propagation (05:45:45Z, `push installation registered`).
- [x] Duplicate Cloud Function trigger invocation for one `messageId` never results in more than one push actually delivered per installation (per-installation dispatch records reach `DISPATCHED` at most once each per successful attempt). — `functions/test/dispatch-logic.test.js` (Step 20).
- [x] An installation that fails to receive a push is retried independently of other installations that succeeded for the same event. — `dispatch-logic.test.js` per-installation independence + sweep cases.
- [x] A mailbox document is not deleted until every registered installation has acknowledged it or the 30-day maximum retention is reached. — `functions/test/mailbox-retention.test.js`.
- [x] An SOS event survives an app process kill between local commit and network send, with the retried send transmitting byte-identical envelope content. — outbox durability test + §12 byte-identical retransmission anchor (ChainStateStoreTest, Step 20).
- [x] Two devices creating events for the same sender concurrently never produce duplicate sequence numbers (verified under concurrent-coroutine test). — `ChainStateStoreTest` concurrent-append single-winner + two-sender independence (Step 20).
- [x] A `DisableApprovalResponse` is rejected if it references a non-existent, already-consumed, or wrong-pairing-epoch request. — `ApprovalFlowTest` (Step 13).
- [x] A genuinely unrecoverable chain gap results in `CHAIN_INCOMPLETE` only after the 30-day recovery window, never immediately and never as `TAMPER_SUSPECTED`. — `ChainStateStore`/repair tests (Step 11).
- [x] A replay of a tampered or previously-verified batch results in `TAMPER_SUSPECTED` and blocks further automatic trust of that sender. — tamper-path tests (Step 11).
- [ ] FCM delivery works on a GMS Android device across foreground, background, and Doze states. — **open:** device-matrix leg; the X6532 round trip (ADR #46) exercised the wire path, the deliberate foreground/background/Doze matrix does not exist. (The task #32 approval-flow device leg landed 2026-09-19 — ADR #58 — but it is a *flow* leg, not this matrix: its last hop is a foreground in-process pull, so it does not close this line.)
- [ ] ~~HMS Push Kit delivery works on a Huawei device without Google Play services.~~ **N/A — HMS rail dropped by user ruling 2026-09-18 (ADR #43).**
- [x] A device with neither push provider falls back to foreground/periodic sync without crashing, with UI messaging that does not overstate delivery reliability. — coordinator fallback + dashboard copy tests; no-GMS *physical* leg named separately.
- [ ] ~~`isHmsAvailable()` returns false and no crash occurs on a device without HMS Core, anywhere in the push path.~~ **N/A — no HMS code exists (grep-verified 2026-09-19; ADR #43).**
- [x] Android 13+ notification-permission-denied state is detected and surfaced via the dashboard banner. — Step 16, X6532-verified (photographed denial path).
- [x] No push-provider credentials of any kind exist in the compiled APK (verified via static scan in CI). — Gate 2.5 static scans (Trivy/opengrep/gitleaks), all clean 2026-09-13.
- [x] No plaintext event content or alias appears in any FCM/HMS wire payload or OS-rendered notification tray text. — data-only payload contract (AD-11) + notification tests.
- [x] SQLCipher migration v8→v9 succeeds under `MigrationTestHelper` with the full encrypted driver stack, including an interrupted-migration scenario. — Step 20: chain-DB v7→v8 under the SQLCipher driver stack + interrupted-migration rollback test (ADR #50 records the plan-vs-DB mapping: the SQLCipher rules store's latest migration is 7→8; v8→v9 is the accountability chain DB).
- [x] Session key rotation triggers at both the 500-event and 30-day thresholds; the prior key is discarded after its 7-day grace window. — `SessionKeyManagerTest` both thresholds + acceptance window (Step 15, ADR #50; "discarded" = receive-side refusal past grace, recorded honestly).
- [x] Unpair completes correctly both when the partner is reachable (`TERMINAL` with `REMOTE_SHRED_CONFIRMED`) and when it is not (`LOCAL_ONLY_TERMINATED` after 7 days), with UI language matching each case accurately. — `BilateralDeletionTest` + the mandated UI strings; supporting rules now deployed (2026-09-19).
- [x] A local data wipe is detected and forces re-enrollment without attempting silent recovery. — Step 17, X6532-verified (photographed wipe card).
- [x] Server-side rate limits (AD-23) reject excess writes before any dispatch work is attempted, verified via call-count assertions. — `relay-logic` handler tests asserting `listInstallations`/`dispatch` call counts (Step 18).
- [ ] GCP budget alert and Cloud Function `maxInstances` are both configured and verified to fire/enforce in staging. — **configured 2026-09-19 (task #34); the firing-test half stays open, named not dropped:** `maxInstances` configured + deployed (10/10/1/1, ADR #54); budget + 80%/100% thresholds, 3 alert policies, 3 log-based metrics, the §14 dashboard, and the email channel all created via API and list-verified (`app/docs/MyExodus Monitoring.md` §4). What is NOT done: a synthetic firing test of the policies — the only honest trigger is real signal, and "in staging" has no staging environment to fire in (production-or-defer, ADR #54).

---

## 18. Definition of Done

The Partner Pillar feature is complete when:

- All items in Section 17 pass in a staging environment matching production configuration.
- The full test suite in Section 12 passes, including the device/provider matrix and the security-rules emulator suite (positive and negative cases for both GMS and HMS/no-GMS paths).
- Monitoring dashboards for every metric in Section 14 are live and have collected at least one full day of real staging data.
- The Cloud Function relay, the Cloud Scheduler retry sweep, and the mailbox cleanup function are all deployed to the same region on the Blaze plan, with `maxInstances`, rate limits, and the budget alert all active.
- A rollback (redeploy of a prior tagged revision) has been exercised at least once in staging.
- Firestore Security Rules have been reviewed against every negative test case in Section 12 with zero known bypasses.
- No open Critical or High finding from either audit round remains unaddressed in the shipped implementation; any accepted residual risk is recorded in Section 15 with a stated detection mechanism.
- Product has been given the Section 16 open questions and has either answered them or explicitly deferred them with a stated review date.
- No sentence in this document overstates a guarantee the architecture cannot actually provide (in particular: unpair, push delivery, and cost control are described exactly as accurately as Sections 5 and 15 state them, not more optimistically, anywhere else in product-facing copy derived from this spec).
