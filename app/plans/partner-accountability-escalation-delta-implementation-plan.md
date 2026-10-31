# Partner-Accountability Escalation Delta Implementation Plan

## 1. Feature Overview

Implement the Partner-Accountability Escalation Delta for MyExodus. The feature assigns accountability responsibilities per rule, sends redacted end-to-end encrypted events to the correct partner devices, detects loss or degradation of local enforcement, retries delivery from durable storage, and notifies partners when the app has been unreachable for an extended period.

The feature is a server-assisted, payload-end-to-end-encrypted system. Firebase can observe authenticated sender identity, pair/routing relationships, message volume, request times, message sizes, coarse routing classes, delivery state and heartbeat freshness. Firebase must not receive decrypted rule names, target-package details, violation details, or compliance metrics.

The implementation covers:

- Mutual partner pairing with authenticated device-key verification.
- One assigned accountability partner per rule, an optional backup delivery partner, and an optional streak partner.
- Rule-scoped routing, per-partner event consent, redaction and encrypted event delivery.
- An encrypted local event log and persistent outbox.
- Five-minute fixed-window coalescing for repeated low-priority violation attempts while preserving every occurrence durably until it is summarized or delivered.
- Local detection of enforcement-state changes and reconciliation at app startup.
- Best-effort client heartbeats and a backend dead-man's-switch (DMS) that reports stale/unknown app health without claiming to know the cause.
- Firestore mailbox persistence, idempotent dispatch, high-priority FCM data-only wake-up messages, partner synchronization acknowledgements, and delayed backup delivery.
- Target-app removal as an informational event, not automatically a compliance violation.
- Daily, redacted streak summaries for a specifically consented streak partner.

The multi-period compliance analytics dashboard, 7-day/30-day charts, and server-generated analytics rollups are out of scope for this implementation. Do not implement `ANALYTICS_ROLLUP` or a scheduled analytics pipeline in this change.

## 2. Requirements

### 2.1 Functional requirements

1. A rule must have exactly one `assigned_partner_uid`. It may also have one `backup_partner_uid` and one `streak_partner_uid`. These are independent roles; there is no account-wide primary partner.
2. A partner assignment is valid only after the relevant pair is mutually accepted and the recipient's public keys have been verified through the pairing ceremony defined in Section 10.
3. A rule creation, reassignment, weakening, disable, or deletion must create a durable partner-visible event in the same local database transaction as the rule mutation. No weakening action may be silently omitted from the outbox.
4. A violation attempt must be persisted immediately. The first event in a coalescing window is dispatched immediately. Further low-priority violation attempts within that fixed five-minute window remain durably stored and are represented by a summary when the window closes.
5. Never coalesce consent changes, pairing/key changes, rule weakening, enforcement-state changes, app-health alerts, revocation notices, or delivery-escalation control events.
6. The sender must redact per recipient before encrypting. Raw event details must not be included in the outbox envelope for a recipient whose consent specifies a less detailed view.
7. Every partner-bound envelope must be encrypted for its intended recipient with HPKE and signed by the registered sender-device signing key.
8. Every inbound envelope must be authenticated, authorized, schema-validated, size-checked, and deduplicated by the backend before mailbox persistence.
9. A successful sender response means the backend committed the ciphertext to durable mailbox storage. It does not mean FCM delivered a notification, the partner app synced it, or a human read it.
10. FCM is a wake-up mechanism only. Firestore mailbox state is the source of truth; the partner app must fetch pending mailbox items on launch and through scheduled background sync.
11. The app must reconcile enforcement state when the process starts and when a monitored permission/service/network state changes. An observer callback alone is not the source of truth.
12. Heartbeats are best-effort. A missing heartbeat means that app health cannot be verified, not proof of uninstall, force-stop, or enforcement failure.
13. A recipient must acknowledge a message after it has been durably written to its local database. This is a **partner-app sync acknowledgement**, not a human acknowledgement.
14. If a rule has a backup partner and the primary partner has not sync-acknowledged an event within 24 hours of backend acceptance, the backend releases the separately encrypted backup envelope and sends a generic FCM notification.
15. The backup-delivery timer must be cancelled when the primary partner sync-acknowledges the event before the deadline. Do not describe a sync acknowledgement as proof that the human partner read the event.
16. App-wide health alerts go to all currently active paired partners who have explicitly consented to `APP_HEALTH` events. Rule events go only to the appropriate rule route and eligible recipients.
17. A target app being removed is reported as `TARGET_APP_REMOVED` informational data. It must not be counted as a violation unless a later, separate product requirement explicitly changes this behavior.
18. Streak partners receive only daily redacted `STREAK_SUMMARY` events and only when they have explicitly consented to that category. They do not automatically receive raw violations.
19. Pair revocation takes effect locally immediately. Remote authorization is revoked when the backend has accepted the signed revocation request. The UI must distinguish `REVOCATION_PENDING_SYNC` from `REVOKED`; the app must not claim remote revocation has completed while offline.
20. Revocation and consent-off apply only to events created after the local change sequence/time. Events created before the change remain eligible for delivery. The sender must flush older pending events in order before the final notice; if offline, the final notice records the pending count and is delivered after those events when connectivity returns. Server revocation cancels only items not yet accepted into the mailbox. Already-accepted mailbox items remain readable until sync acknowledgement plus a seven-day grace period, or until the 90-day unsynced TTL expires.
21. The subject owns per-partner category visibility consent. A partner separately accepts the accountability role during pairing. Consent is represented server-side by a signed `RouteAuthorizationUpdate` containing route ID, category bitmask, monotonically increasing version and device signature. The backend checks this version on every event acceptance.
22. Map each event to one consent category, routing class and recipient set using the table in Section 8.4. Do not infer recipient eligibility from category names.
23. Either party can revoke a pairing. Partner-initiated revocation immediately removes the partner's local access and submits a signed route-revocation request. The subject receives a visible notice at next sync. Rules assigned to a departed partner become `UNSUPERVISED`; they remain enforced and visible, and weakening changes are logged locally and sent to any remaining eligible partner.
24. Revocation and consent-off must always be writable, even when the event outbox is full. Store these operations in a separate small, durable control table that is not subject to the normal outbox capacity limit. Never refuse a revocation.
25. Only one active enforcement device is permitted per account in this release. Registering a replacement device requires re-verification and emits a partner-visible device-change event. A heartbeat from an unregistered or superseded device is rejected.
26. Deliver the feature in milestones: M1 pairing/crypto/mailbox/outbox/rules/consent/revoke; M2 enforcement reconciliation/heartbeat/DMS; M3 backup partner/streak/target-removal enhancements. Ship the Play flavor first. Do not add a sideload production flavor unless the existing product distribution requirement is confirmed in repository inventory and separately tested.

### 2.2 Non-functional requirements

- Preserve the existing MyExodus app architecture and unrelated functionality. Implement the smallest necessary delta.
- Preserve the existing minimum SDK; the currently documented project baseline is `minSdk 26`, `targetSdk 36`, `compileSdk 37`, Kotlin 2.2.10, Jetpack Compose Material 3, Room/SQLCipher, Hilt, WorkManager, and Firebase. Verify actual repository configuration before editing; do not raise `minSdk` as part of this feature.
- No plaintext private-key storage in `SharedPreferences`, ordinary files, logs, or analytics.
- No destructive Room migration.
- No exact-alarm dependency for heartbeat or coalescing schedules.
- No claim of guaranteed real-time background execution on Android or OEM builds.
- No plaintext event payload or raw partner activity in FCM notification content, Crashlytics, server logs, or analytics telemetry.
- Support Play-distributed and sideloaded release flavors with explicit trust tiers; do not ship a debug attestation provider in either production flavor.

## 3. Existing System Context

The known MyExodus baseline is an Android Kotlin application using Compose, Room protected by SQLCipher, Android `UsageStatsManager`, local VPN/DNS enforcement, WorkManager, Firebase Authentication, Firestore, Cloud Functions gen2, FCM, and Crashlytics. The codebase also has a planned/variant-specific AccessibilityService path. Do not assume that AccessibilityService exists or is enabled in every release flavor.

Use these existing platform boundaries:

- **Local database:** Room with SQLCipher stores current rules, consent state, partner-device records, event history, event coalescing rows, key metadata, and the durable outbox.
- **Android enforcement:** Monitor only the enforcement mechanisms actually enabled in the installed build. The baseline engine checks Usage Stats access and VPN state. Include AccessibilityService state only in a build flavor that actually includes that service.
- **Background scheduling:** WorkManager performs best-effort dispatch, partner mailbox sync, coalescing-window closure, daily streak summaries, and periodic heartbeat work. It does not provide exact wall-clock scheduling.
- **Firebase Authentication:** Authenticated account identity is derived server-side from the verified Firebase ID token, never from a caller-supplied `sender_uid` field.
- **Cloud Functions gen2:** TypeScript HTTPS/callable endpoints validate submitted events and authorize all routing. Backend code must not rely on Firestore Security Rules for authorization because server SDKs bypass those rules.
- **Firestore:** Stores pair/routing authorization state, ciphertext mailboxes, idempotency records, dispatch-outbox documents, heartbeat state, partner sync acknowledgements, and retention metadata.
- **FCM:** Sends generic, non-sensitive, user-visible notifications when notification permission is available. A push does not carry decrypted content and does not replace mailbox sync.

Repository-specific class names, module paths, current Room version, current migration number, and existing Firebase function names are to be discovered during implementation step 1. Do not create duplicate services or repositories before inspecting the source tree. Reuse existing equivalents where they meet this specification.

## 4. Architecture

### 4.1 Android components

1. **Pairing and Partner Repository**
   - Maintains accepted partner/device records, public-key fingerprints, pair-specific route identifiers, recipient roles, and consent versions.
   - Exposes explicit pairing, re-pairing, key-change confirmation, and revocation states to the UI.
   - Never accepts a new partner public key merely because it appears in a server response.

2. **Crypto Manager**
   - Uses Google Tink for HPKE and Ed25519 signatures.
   - Stores Tink keysets encrypted at rest by a non-exportable AES-GCM key in Android Keystore. The encrypted keyset files live in no-backup app-private storage.
   - Uses a separate Android Keystore-wrapped random database key for SQLCipher.
   - Provides only typed operations: load/rotate signing keys, load/rotate recipient encryption keys, seal envelope, verify envelope, encrypt/decrypt payload, and obtain key fingerprints. Do not expose raw private-key bytes to feature code.

3. **Event Repository**
   - Receives rule mutations, violation attempts, overrides, enforcement-state transitions, target-app removal events, consent changes, pairing changes, and scheduled streak-summary events.
   - Applies recipient consent and redaction before storing recipient-bound outbox data.
   - Creates local event/outbox state transactionally with the rule or consent mutation that caused it.
   - Does not make network calls inside a Room transaction.

4. **Enforcement Monitor**
   - Observes changes to Usage Stats access, active VPN state, and AccessibilityService state only where applicable.
   - Uses callbacks as a fast path, then re-queries authoritative current state.
   - Reconciles the complete enforcement snapshot on app process start and on the next opportunity after device boot.
   - Emits `SAFEGUARD_REDUCED`, `ENFORCEMENT_STATE`, or recovery events according to the state model below.

5. **Dispatch Worker**
   - Reads durable outbox rows, closes eligible five-minute violation windows, creates and persists the sealed envelope before transmission, and submits it to the backend endpoint.
   - Retries transient failures with exponential backoff and jitter.
   - Does not send an event until its sealed bytes are durably stored locally.

6. **Heartbeat Worker**
   - Uses a two-hour `PeriodicWorkRequest`, with immediate best-effort check-ins on process startup and enforcement-state transitions subject to a ten-minute minimum between routine heartbeats.
   - Sends a signed heartbeat-liveness request containing a monotonically increasing heartbeat sequence and unique request ID. The heartbeat endpoint receives no rule, package, violation, or enforcement-state details.
   - Enforcement snapshots are sent separately as encrypted `ENFORCEMENT_STATE` partner events on state transitions and at least once per local day while monitoring is enabled.
   - Never uses `setExactAndAllowWhileIdle()` for normal heartbeats.

7. **Partner Sync Worker and Inbox UI**
   - Fetches mailbox items, validates the backend record, verifies sender signature, decrypts to the local SQLCipher database, rejects duplicates, detects sequence gaps, and commits the sync receipt only after local persistence succeeds.
   - The UI shows states separately: accepted by backend, synced by partner app, and decryptable. Do not implement a human-read/human-acknowledgement feature in this change, and do not imply that a sync receipt means a person saw the event.

8. **Local State UI**
   - Displays current enforcement as `FULL`, `DEGRADED`, `DOWN`, or `UNKNOWN`.
   - Displays pending outbox count, last successful sync, key-repair state, notification-permission state, and revocation sync state without exposing another partner's private details.

### 4.2 Backend components

1. **Authenticated Envelope Ingress**
   - Accepts HTTPS requests from an authenticated Firebase user.
   - Validates request size/schema, Firebase Auth identity, device registration, sender signature, route authorization, consent version, rate limits, supported protocol version, and idempotency.
   - Uses App Check with Play Integrity for the Play flavor on registration and high-risk account/key actions. Do not require a new Play Integrity token for every heartbeat or violation. For sideload releases, use authenticated account identity, registered device keys, nonce-bound signed challenges for high-risk actions, and rate limits; label this as a lower-attestation trust tier, not equivalent binary integrity.

2. **Mailbox Commit Service**
   - Within one Firestore transaction, writes the recipient's immutable ciphertext mailbox item, idempotency result, and a push-dispatch-outbox document.
   - Returns success only after the transaction commits.
   - Does not claim a Firestore write and an FCM send are atomic.

3. **Push Dispatcher**
   - Processes push-dispatch-outbox documents and sends high-priority FCM data-only messages with a generic `sync_required: true` payload. The client fetches the mailbox and may post a local private notification only after decrypting an item that warrants attention.
   - Is idempotent in state transitions. Duplicate FCM sends are acceptable because clients deduplicate mailbox envelopes.
   - Maintains FCM token registration, token refresh, invalid-token removal, and per-device notification capability state.

4. **Heartbeat and DMS Service**
   - Records server ingestion time, monotonically increasing heartbeat sequence, device registration, liveness state and current evaluation generation. It does not receive local enforcement snapshots in plaintext.
   - Each accepted heartbeat schedules a delayed Cloud Task for eight hours later and a second evaluation task for 24 hours later. Each task rereads the current device heartbeat sequence and exits without changing state if a newer heartbeat exists. Task creation is idempotent using device ID plus heartbeat sequence plus evaluation stage.
   - `STALE` at eight hours triggers a user-visible local nudge when the subject device next executes; it does not by itself alert partners.
   - At approximately 20 hours, send a high-priority FCM data-only wake request to the subject device. A successful fresh heartbeat cancels escalation. FCM delivery is best-effort and not treated as proof of device reachability.
   - At 24 hours, mark `UNREACHABLE` and emit one generic signed notice to all active paired partners with `APP_HEALTH` consent, unless the backend outage guard is active. Repeat notices are suppressed until a new heartbeat resumes the cycle.
   - The notice says MyExodus has not checked in and enforcement state cannot currently be verified. It does not claim uninstall or force-stop has been proven. The partner client also shows `ENFORCEMENT_STATE_STALE` if no such state report has arrived for more than 36 hours, independently of heartbeat status.
   - Run heartbeat attempts from the active enforcement foreground-service/VPN process where the current platform design permits, with WorkManager as backup. Do not claim either mechanism guarantees execution under force-stop or OEM restrictions.
   - The outage guard suppresses mass escalation if heartbeat-ingress errors exceed 5% over 10 minutes, the evaluator has failed two consecutive runs, or more than 20% of active devices become newly stale in one evaluation batch. The guard pages operations, records a suppression metric and re-evaluates after service health recovers. Evaluation queries are paginated and capped at 5,000 devices per invocation.

5. **Server Control-Event Signer**
   - Signs backend-generated DMS/control events with a Cloud KMS ECDSA P-256/SHA-256 asymmetric signing key. The client pins the corresponding public key and verifies the `key_id` and signature before presenting a server-originated status as authentic.
   - Control messages contain no rule name, target-package data, violation details, or usage history.

6. **Retention and Revocation Service**
   - Deletes synced mailbox ciphertext after sync acknowledgement plus a seven-day grace period. Unsynced ciphertext expires after 90 days. Delivery tombstones retain only minimal routing/idempotency metadata under a separately documented retention period. Revocation cancels only not-yet-accepted items.
   - Rejects route use after server-side revocation has committed.
   - Maintains audit metadata needed for idempotency and abuse prevention without logging plaintext payloads.

### 4.3 Enforcement-state model

- `FULL`: all enforcement mechanisms required by the current build/configuration report operational.
- `DEGRADED`: one or more required safeguards are unavailable while at least one applicable enforcement mechanism remains operational.
- `DOWN`: all enforcement mechanisms required for the active protection profile report non-operational.
- `UNKNOWN`: the process cannot establish the state, the last report is stale, or the app has not reconciled since startup.

Any loss of a single required safeguard emits `SAFEGUARD_REDUCED`. A transition to `DOWN` emits a higher-priority `ENFORCEMENT_STATE`. A heartbeat carries the last observed snapshot, but the backend and partner must treat this as a signed client report, not independent proof against a rooted or modified device.

### 4.4 Cryptographic protocol

- Use a verified, pinned Google Tink release and its documented HPKE X25519/HKDF-SHA256/AES-256-GCM template plus Ed25519 signature primitive. During Step 0, confirm the exact artifact version and template identifier against the dependency actually resolved by Gradle; do not assume a template name or version from this document if it does not compile. Use RAW Ed25519 key templates for cross-platform interoperability. Export only the documented raw 32-byte public key representation and use raw 64-byte signatures for Node verification; never hand-roll key parsing or cryptography. Add Android-sign/Node-verify known-answer vectors before proceeding.
- Serialize protocol headers and inner payloads with the shared versioned Protocol Buffers schema. The exact raw serialized `header_bytes` used by the sender are included in the submitted envelope and parsed by the server. Signature verification is performed against those exact bytes; the verifier must not reconstruct a different header serialization before signature verification.
- Build the signature preimage as `ASCII("MYEXODUS-EVENT-V1\0") || uint32_be(header_length) || header_bytes || uint32_be(hpke_output_length) || hpke_output`. Sign this complete preimage with the registered sender-device Ed25519 key.
- Pass the domain-separated `header_bytes` as HPKE `context_info`, so the ciphertext cannot be decrypted under a different route/header context without failing the HPKE operation. The signature separately authenticates the exact header and HPKE output.
- Treat Tink's HPKE output as an opaque byte string. Do not hand-parse the encapsulated key unless the library API explicitly exposes a separate encapsulation field.
- The visible outer header contains only: `protocol_version`, random `envelope_id`, random per-logical-event `delivery_group_id`, opaque `route_id`, `recipient_key_id`, coarse `routing_class`, and `sender_device_key_id`. Application-level `recipient_key_id` and `sender_device_key_id` are the first 16 bytes of SHA-256 over the corresponding raw public key, hex-encoded; they are not Tink’s internal four-byte key IDs. `delivery_group_id` lets the backend coordinate the delayed backup copy and therefore remains visible routing metadata. Derive sender account identity from Firebase Authentication. Do not expose `rule_id`, target package, exact event type, rule name, violation detail, client timestamp, or compliance metric in the outer header.
- The backend can still see authenticated account identity, the pair/route mapping, routing class, message size/volume, ingestion time, and delivery outcomes. Describe the system as **payload-E2EE with pseudonymous routing**, never metadata-blind.
- Inner payloads include `payload_version`, full event type, rule identity, redacted event details, client wall-clock time, device boot-session identifier, monotonic offset where applicable, per-route/rule sequence number, and aggregate count/time range where applicable. V1 does not implement a hash chain. Client time is untrusted display context, not authoritative ordering.

## 5. Architecture Decisions

- Use Firebase Auth plus registered device keys for identity because account authentication and device-level message authenticity are separate controls.
- Use remote-capable pairing with a short-lived deep link and out-of-band safety-phrase verification; retain QR pairing for in-person users. The server is not the trust anchor for the partner public key.
- Use Tink HPKE with X25519/HKDF-SHA256/AES-256-GCM because it provides a maintained implementation of the required standardized envelope encryption.
- Use Tink Ed25519 signatures over the exact header bytes and HPKE output because encryption does not authenticate the sender.
- Store Tink keysets encrypted with an AES-GCM wrapping key held by Android Keystore because X25519/Ed25519 key material must not sit in plaintext app storage.
- Store the SQLCipher database key as random key material wrapped by a separate Android Keystore AES-GCM key because a Keystore key itself is not exportable database key material.
- Use SQLCipher for local event data and exclude new feature keysets and feature-specific key-bound state from Android backup; preserve existing backup behavior for unrelated data unless repository inspection confirms a database-wide exclusion is already the established product decision.
- Use a durable Room outbox with immutable sealed envelopes because process death must not discard an event or change its ciphertext on retry.
- Use fixed five-minute coalescing windows for repeat violation attempts because the window is simple to persist and test; never coalesce security/control events.
- Use best-effort heartbeat attempts from the enforcement foreground process where permitted, with WorkManager backup, an 8-hour subject nudge, an approximately 20-hour data-only wake attempt and a 24-hour partner alert because Android background execution is not exact and the DMS must not assert an unknown cause.
- Use delayed Cloud Tasks for heartbeat evaluation, with each task checking the current heartbeat sequence before changing state. This avoids a global high-cardinality Firestore threshold query on every sweep.
- Use Firestore mailbox transactions plus a separate push-dispatch outbox because Firestore commits and FCM sends cannot be one atomic operation.
- Use high-priority FCM data-only messages with a generic sync-required payload to wake the app where Android permits; Firestore remains the durable source of truth and launch/WorkManager sync are fallbacks.
- Use an optional backup partner with a 24-hour app-sync timeout because it provides a delivery fallback without pretending that app synchronization proves a human read an alert.
- Send rule events to rule-authorized recipients and app-health status to all currently paired, health-consented partners because an app-wide heartbeat failure is not owned by one rule.
- Make target-app removal informational because uninstalling a restricted app is not inherently a rule violation.
- Require partner-visible notices for rule weakening and consent changes without partner approval or a mandatory delay because the user must retain immediate control while the application must not silently conceal weakening actions.
- Implement the core event, delivery, consent, key and DMS paths first; keep the multi-period analytics dashboard out of this feature because it introduces additional collection and disclosure scope not needed for core escalation.
- Support Play and sideload release variants with separate integrity trust levels because Play Integrity's Play-recognition verdict is not a universal guarantee for APK-distributed builds.

## 6. Data Flow

### 6.1 Local event creation

1. A rule mutation, override, violation, enforcement callback, consent change, pairing change, or scheduled streak summary enters the Event Repository.
2. The repository snapshots the relevant rule/consent state and calculates the recipient set.
3. For each recipient, the repository checks the locally cached consent version and applies that recipient's redaction policy. If consent is disabled, do not create an ordinary event envelope for that category.
4. Persist the event and recipient-specific outbox entries in one Room transaction. When a rule mutation or consent change causes the event, commit the state change and the event record atomically.
5. For repeated low-priority violation attempts, persist each occurrence immediately and associate it with the currently open five-minute fixed window. The first occurrence is eligible for immediate dispatch; later occurrences are summarized when the window closes.
6. Enqueue unique WorkManager dispatch work after the transaction commits. Do not perform network or cryptographic I/O inside the database transaction.

### 6.2 Sealing and client dispatch

1. Load a pending recipient-specific outbox row and verify the pairing route, key version, event class and consent version.
2. Build the protobuf inner payload and outer header. Generate a random immutable `envelope_id` for the recipient-specific envelope.
3. Encrypt the payload using the recipient's active HPKE public key and header-bound context information.
4. Sign the exact header bytes and HPKE output with the sender device's Ed25519 key.
5. Persist the complete sealed envelope and change its state to `READY_TO_SEND` before any network request.
6. Submit the sealed bytes to the authenticated Cloud Function endpoint with the idempotency key. Retry using the same bytes and identifiers after a timeout or uncertain response.
7. On durable server acceptance, mark the local outbox row `BACKEND_ACCEPTED` and remove bulky local outbox payload bytes only after the accepted state is committed. Preserve a bounded delivery-status record for reconciliation.

### 6.3 Backend acceptance and mailbox delivery

1. The backend derives the authenticated sender UID from Firebase Auth and verifies the registered sender device key.
2. It verifies the signature, protocol/schema version, envelope size, route, recipient key ID, routing class, current consent version, rule/role authorization represented by the route record, revocation status, rate limits and idempotency.
3. In one Firestore transaction, write the immutable ciphertext mailbox item, the idempotency result and a push-dispatch-outbox document.
4. Return `ACCEPTED` only after the transaction commits. Return the previously committed result for a duplicate idempotency key; reject the same key with different sealed bytes.
5. The push dispatcher sends a high-priority data-only FCM message with no behavioral payload. If FCM is throttled, denied or delayed by an OEM, the mailbox item remains available through app-launch and WorkManager sync.
6. The partner app syncs on launch and scheduled work, verifies the sender signature, decrypts the payload, checks sequence/hash continuity, and stores the event locally before acknowledging sync.
7. Store backup ciphertext in a `held_items` collection that is not readable by client queries or recipient security rules until release. A paginated, indexed due-item sweep runs every five minutes and moves eligible items transactionally into the backup recipient mailbox. If a backup envelope exists, hold its FCM dispatch until the primary app-sync deadline. If a valid primary sync acknowledgement arrives first, suppress and delete the unsent backup copy. The primary-sync receipt and backup-release transition must both read and update the same `delivery_groups/{delivery_group_id}` state document in their Firestore transactions so only one wins the deadline race. If the deadline expires first, release it to the backup partner.

### 6.4 Consent and revocation

1. The user changes partner consent or revokes a pairing.
2. The local transaction immediately updates local generation behavior, increments the local consent version, and writes a signed control request to the separate always-writable control table. Events created before the change remain queued and must be flushed in order before the final control notice. Events created after the change are not generated for the disabled category. If offline, the final notice records the count of older pending events and remains queued behind them.
3. While offline, show `REVOCATION_PENDING_SYNC` and state that remote revocation is pending. Do not tell the user that backend revocation is complete.
4. The sender flushes all pre-change events first, then submits the signed final notice. If it cannot flush while offline, it sends the final notice after reconnect with the pending-count metadata. The backend validates the registered device, accepts the final notice, then atomically marks the route revoked. It cancels only items not yet accepted into the mailbox; accepted mailbox items remain readable until sync acknowledgement plus seven days or the 90-day unsynced TTL.
5. After server revocation commits, reject every ordinary envelope for that route, including retries created before revocation. Previously delivered or decrypted data cannot be remotely recalled; state that limit in the UI.

### 6.5 Heartbeat and DMS

1. WorkManager sends a best-effort heartbeat approximately every two hours when Android grants execution. App startup and enforcement transitions may request an immediate heartbeat subject to the ten-minute routine throttle.
2. The signed heartbeat request contains only the registered device ID, monotonically increasing heartbeat sequence, unique request ID and protocol version. The server records its own ingestion time. Local enforcement snapshots travel only in separate partner-encrypted `ENFORCEMENT_STATE` envelopes. Each heartbeat request is signed over a domain-separated, length-prefixed encoding of the device ID, heartbeat sequence, request ID and protocol version. The server rejects old sequences and treats a repeated request ID with identical bytes idempotently; it does not accept replayed old requests as fresh heartbeats.
3. The 15-minute scheduled evaluator marks a heartbeat record `STALE` after eight hours without a valid check-in, visible on subsequent partner sync but without a push.
4. At 24 hours without a valid heartbeat, the evaluator marks the installation `UNREACHABLE` and sends one generic signed app-health notice to eligible active paired partners. This message says state is unverified, not that uninstall or enforcement loss is proven.
5. A resumed heartbeat resets the state to `HEALTHY` and creates one recovery notice.

## 7. Implementation Sequence

### Step 0: Policy, legal and repository go/no-go
1. Review current Play policy for AccessibilityService, accountability/monitoring disclosures, package visibility, foreground-service types and account deletion. Review Kenya data-protection/ODPC duties and cross-border data processing. Record the selected Firebase region. Do not enable public rollout before these reviews are recorded.
2. Inventory the repository, current auth provider, Room/SQLCipher migrations, Android backup rules, build variants, enforcement services, Firebase region, IAM, rules/indexes, existing protocol and CI matrix. Preserve unrelated existing database backup behavior; exclude only the new feature's key material and feature-specific data unless current backup behavior is already intentionally database-wide excluded.
3. Verify current Tink artifacts/templates and Node raw-key/raw-signature interoperability. Commit known-answer test vectors before implementing the envelope.

### Milestone M1: Pairing, cryptography, mailbox, outbox and consent
4. Implement remote/in-person pairing, safety phrase verification, registered single-device identity, key storage and key rotation.
5. Implement shared protocol schema, raw Ed25519 signatures, HPKE sealing, server-side signature verification, authorization matrix and `RouteAuthorizationUpdate`.
6. Implement Room event log, separate always-writable control table, durable outbox, idempotent backend mailbox acceptance, data-only FCM sync, recipient inbox and sync acknowledgements.
7. Implement consent changes, flush-before-revoke, partner-initiated unpair, `UNSUPERVISED` rules and rollback-safe control notices.
8. Release M1 behind a feature flag to internal Play testers. Do not start M2 until crypto vectors, consent, revoke, migration and mailbox tests pass.

### Milestone M2: Enforcement reconciliation and DMS
9. Implement enforcement state reconciliation, package-visibility-safe target status, state reporting and best-effort heartbeat sources.
10. Implement delayed Cloud Tasks for 8-hour and 24-hour evaluations, 20-hour subject wake attempt, subject nudge, outage guard, paginated/capped evaluation and partner stale-state indicator.
11. Run a two-week canary measuring heartbeat lag by OEM, Android version and standby bucket before enabling partner `UNREACHABLE` alerts broadly.

### Milestone M3: Backup, target-removal and streak enhancements
12. Implement held backup items, due-index release sweep, backup-specific `SPARSE` sequence handling and recipient-specific encryption.
13. Implement informational target-removal events and daily streak summaries with the specified consent and time-zone rules.
14. Add the sideload flavor only if Step 0 confirms it is required by the existing distribution plan; test it as a distinct trust tier. Hash-chain integrity is not part of this release.
15. Roll out each milestone independently with server-side kill switches that stop new feature activation/events but continue revocation, consent/weakening notices, and heartbeats for already paired users.

## 8. Detailed Implementation Steps

### 8.1 Repository inventory

- Produce a file map of current relevant classes, module owners, database schema and migration chain before making changes.
- Reuse the project's existing dependency injection, repository, worker and Firebase initialization patterns.
- Confirm whether the current build variant uses Usage Stats, VPN, AccessibilityService, or a combination. Enforcement-state requirements must be derived from the enabled profile, not hard-coded to a service absent from the variant.
- Capture existing tests and baseline behavior for rule creation, blocking, pairing, local event history, backend auth, FCM, Room migration and app startup.

### 8.2 Key creation, storage and pairing

- Generate a per-device Ed25519 signing keyset and HPKE recipient keyset using Tink. Use `DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM` for HPKE. Do not use `KeyPairGenerator` with `KEY_ALGORITHM_EC` as a substitute for Ed25519 or X25519.
- Encrypt each Tink keyset at rest with an AES-GCM wrapping key generated in Android Keystore. Keep encrypted keyset files in `noBackupFilesDir`, never in plaintext `SharedPreferences`.
- Generate a separate random database key for SQLCipher and wrap it with a separate Android Keystore AES-GCM key alias. Do not attempt to derive or export an Android Keystore key as the SQLCipher key.
- Request StrongBox for the wrapping key only when supported and suitable. If StrongBox does not support the requested wrapping algorithm, fall back to the normal Android Keystore provider. The wrapping key must still remain non-exportable by the provider.
- Support both in-person QR pairing and remote pairing. Remote pairing uses a short-lived HTTPS deep link carrying a one-time pairing-session ID and initiator public-key fingerprint; it must not carry private keys or bearer credentials that alone activate the route. The recipient opens the link, reviews the identity, and both users compare the same six-word safety phrase over a voice/video call or in person.
- Derive the phrase from the lexicographically sorted concatenation of both raw public-key fingerprints, both device nonces sorted by device ID, and the pairing-session ID. Display `Verified in person` or `Verified remotely`; do not present an unverified link click as verification.
- Each device displays/scans a short-lived QR when in person, or opens the one-time link remotely. Both devices sign acceptance statements that bind the session ID, both key fingerprints, both device IDs and verification method. The backend activates the route only after both confirmations. Sessions expire after five minutes, are single-use, and cannot be replayed.
- Pin the verified public keys locally and persist the same key fingerprints server-side. Any mismatch blocks sending and displays a key-verification warning; do not silently accept a server-supplied replacement.
- The backend stores only public keys and key metadata. It never stores private key material or a decryption escrow key.
- Do not support transparent private-key recovery or multi-device private-key synchronization in this feature. A lost device key requires re-pairing. Previously received ciphertext that the lost key alone could decrypt is not recoverable from the backend.
- When rotating a key, verify the replacement key through the pairing ceremony, version it, and keep the previous recipient decryption key on the recipient device until ciphertext encrypted to it has expired or has been explicitly deleted. On confirmed key compromise, revoke the old signing key immediately and cancel/reseal still-local unsent messages where possible.

### 8.3 Protocol schema and cryptographic envelope

- Add a single shared versioned `.proto` schema used by Android and backend TypeScript generation.
- `OuterHeader` fields: `protocol_version`, `envelope_id`, `delivery_group_id`, `route_id`, `recipient_key_id`, `routing_class`, `sender_device_key_id`. Application-level `recipient_key_id` and `sender_device_key_id` are the first 16 bytes of SHA-256 over the corresponding raw public key, hex-encoded; they are not Tink’s internal four-byte key IDs.
- Allowed `routing_class` values: `RULE_CONTROL`, `ACTIVITY`, `HEALTH`, `CONSENT_CONTROL`, `STREAK_SUMMARY`.
- Inner client-event types: `RULE_ASSIGNED`, `RULE_MUTATED`, `VIOLATION_ATTEMPT`, `OVERRIDE`, `SAFEGUARD_REDUCED`, `ENFORCEMENT_STATE`, `TARGET_APP_REMOVED`, `ALERT_STORM_SUMMARY`, `CONSENT_CHANGED`, `PAIR_REVOKED`, and `STREAK_SUMMARY`. Heartbeat liveness is a separate signed client-to-backend request, not a partner event. Server-generated control types are `APP_HEALTH_UNREACHABLE`, `APP_HEALTH_RECOVERED`, and `BACKUP_DELIVERY_RELEASED`; they use the Cloud KMS server signature and are not represented as sender-signed inner payloads.
- Every payload includes `payload_version`, unique logical `event_id`, event type, rule identity when relevant, redacted details, client wall-clock occurrence time, boot-session ID, monotonic offset where available, per-rule/per-recipient sequence, and redaction mode. V1 does not include a hash-chain predecessor.
- Preserve raw outer `header_bytes`; sign those exact bytes and the opaque HPKE output using the preimage definition in Section 4.4. Validate that the protobuf can be parsed and that required fields are present before authorizing it.
- The inner `event_id` identifies the logical event; each recipient-specific sealed copy has its own `envelope_id`, while copies for the same logical event share `delivery_group_id`. Idempotency is keyed by sender device + recipient route + envelope ID. The backend rejects duplicate IDs with different bytes and uses `delivery_group_id` only to coordinate backup-release state.
- Assign sequence numbers at seal time, transactionally per `(sender device, route, rule)`. A cancelled allocated sequence is represented by a `CANCELLED` tombstone and is never reused. The primary route uses contiguous sequence validation. Backup delivery is explicitly `SPARSE` because it receives only delayed copies; do not report missing backup-route sequence numbers as tampering. V1 does not implement a hash chain.
- Enforce a maximum submitted envelope size of 64 KiB. Reject oversize requests before writing Firestore.

### 8.4 Consent and redaction

- Store consent per pairing and event category: `RULE_CONTROL`, `ACTIVITY`, `OVERRIDE`, `APP_HEALTH`, `STREAK_SUMMARY`, and `DELIVERY_ESCALATION`. The subject controls category visibility; the partner separately accepts the role at pairing. The backend stores the latest signed `RouteAuthorizationUpdate` with route ID, category bitmask and monotonically increasing consent version.
- Consent defaults to off until an explicit user choice is committed. A partner assignment is not valid for a category the partner has not consented to receive.
- Redaction levels are `NONE`, `AGGREGATE_ONLY`, `CATEGORY_ONLY`, and `BINARY_ALERT`. Apply redaction separately for each recipient before payload serialization and encryption.
- `NONE` may contain exact package/category and event timestamp. `AGGREGATE_ONLY` uses a generic restricted-app label and bucketed counts. `CATEGORY_ONLY` includes a broad category and coarse time bucket. `BINARY_ALERT` includes only the fact that a configured restriction was triggered. Never include raw screen text, content, browser URLs, credentials, message contents, or unrelated app data.
- Aggregate counts must not round a nonzero count to zero. Use explicit bucket ranges such as `1–4`, `5–9`, and `10+` or a rounding rule with a minimum displayed nonzero value.
- The UI must distinguish detail redaction from event notification consent. Revoking ordinary activity detail must not silently suppress the required `CONSENT_CHANGED` notice.
- No partner approval or mandatory cooling-off period is required for rule weakening or consent revocation. The local state change takes effect immediately, and a durable notice is created atomically. The partner receives the notice when network delivery resumes and the route remains server-authorized for that final notice.

**Event authorization matrix (authoritative):**

| Event type | Subject consent category | Routing class | Eligible recipient set |
|---|---|---|---|
| `RULE_ASSIGNED`, `RULE_MUTATED` | `RULE_CONTROL` | `RULE_CONTROL` | Current assigned partner for that rule; backup partner receives only on backup release |
| `VIOLATION_ATTEMPT`, `ALERT_STORM_SUMMARY` | `ACTIVITY` | `ACTIVITY` | Assigned partner for the rule; backup only on backup release |
| `OVERRIDE` | `OVERRIDE` | `ACTIVITY` | Assigned partner for the rule; backup only on backup release |
| `SAFEGUARD_REDUCED`, `ENFORCEMENT_STATE` | `APP_HEALTH` | `HEALTH` | All active paired partners with `APP_HEALTH` consent, except rule-scoped detail stays on rule route |
| `TARGET_APP_REMOVED` | `ACTIVITY` | `ACTIVITY` | Assigned partner for the affected rule, if one exists and consent is active |
| `CONSENT_CHANGED`, `PAIR_REVOKED`, `ROUTE_AUTHORIZATION_UPDATED` | `DELIVERY_ESCALATION` | `CONSENT_CONTROL` | Affected partner/route; final control notice is mandatory and cannot be suppressed by turning off ordinary category consent |
| `STREAK_SUMMARY` | `STREAK_SUMMARY` | `STREAK_SUMMARY` | Explicitly designated streak partner only |
| Server DMS control event | Partner's `APP_HEALTH` consent | `HEALTH` | Active health-consented partner set for that subject device |

`RULE_CONTROL` consent category and routing class names are distinct namespaces; implement this matrix exactly. The subject sets category visibility per partner; the partner separately accepts the role at pairing. Send a signed `RouteAuthorizationUpdate` on every consent change. The backend stores the latest accepted version and rejects stale versions.

### 8.5 Room schema and outbox

- Add entities or equivalent columns for partner devices, verified key IDs/fingerprints, pair route IDs, consent/version state, enforcement snapshots, event log, coalescing buckets, outbox state, recipient sequence/hash state and backend acceptance state.
- Every generated event is recorded in Room immediately in the same transaction as the change that caused it. Store the redacted recipient copy in the outbox; raw local details remain only in the existing protected event/history store if the product already records them.
- Outbox states: `PENDING_SEAL`, `READY_TO_SEND`, `IN_FLIGHT`, `BACKEND_ACCEPTED`, `DEAD_LETTER`, `CANCELLED`. Store retry count, next-attempt time, last error category, sealed bytes, envelope ID and route ID.
- Acquire work with a Room transaction and a lease/owner field so two workers cannot seal or send the same row concurrently. Expired leases return to the retryable state.
- Persist sealed bytes and the immutable envelope ID before network submission. A retry reuses the exact same bytes and idempotency key.
- Use bounded storage: target a soft limit of 10,000 pending records or 50 MiB. Coalesce repeated low-priority violation occurrences when the limit is approached, and reserve 512 KiB for critical control events. Never evict unsent critical events silently. If ordinary capacity is exhausted, preserve the control-table capacity for consent-off and revocation. Do not refuse revocation. If the ordinary critical-event reserve is exhausted, block additional rule weakening with a clear error while keeping existing enforcement active.
- Transient failures retry indefinitely with exponential backoff and jitter: start at 30 seconds, double per attempt, cap at six hours. Permanent schema/auth/key errors move to `DEAD_LETTER` and surface a repair/action-required state; they must not loop forever or be discarded without notice.
- For already accepted rows, retain a small delivery tombstone for reconciliation and remove the sealed payload from the local outbox after the accepted transition commits.
- Use wall-clock epoch for display, boot-session ID plus `elapsedRealtime()` for same-boot interval calculations, and server ingestion timestamps for backend freshness. Never store `elapsedRealtime()` alone as a persistent creation timestamp.

### 8.6 Violation coalescing

- The coalescing window is a fixed five-minute interval beginning with the first occurrence. It is not a token bucket and must not use the expression from the research dossier.
- Persist every violation occurrence or its occurrence record in Room immediately. Send the first event immediately. Place later noncritical repeat attempts in the same open bucket and increment its persisted count/timestamps transactionally.
- When the deadline expires, create one `ALERT_STORM_SUMMARY`, seal it, and enqueue it. The summary includes the first immediately dispatched occurrence in its aggregate count and sets the same `delivery_group_id` as the immediate event. The partner UI treats the summary as an aggregate update, not as additional violations; deduplicate/supersede by `delivery_group_id`. Schedule closure through WorkManager, but treat the persisted deadline and bucket state as authoritative if WorkManager runs late.
- Use `elapsedRealtime()` for interval calculations while the same boot-session ID remains active. On detecting a different boot session, close the old bucket immediately on the next app start and mark the timing context as approximate; do not treat elapsed offsets from different boots as comparable.
- Exempt all rule-weakening, consent, key, pairing, enforcement, DMS, revocation and delivery-escalation events from coalescing.

### 8.7 Enforcement monitoring and target-app removal

- Register public AppOps monitoring using the documented listener APIs and query the current state with the documented `checkOpNoThrow` API for the target SDK. Do not introduce hidden APIs. Keep the observer path best-effort and reconcile state by querying the underlying service/permission after a callback.
- Observe `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` only in flavors containing the AccessibilityService. Parse component entries with `ComponentName.unflattenFromString`; compare normalized component names, not substring matches.
- Observe VPN state using the app's `VpnService` callbacks and `ConnectivityManager` network callbacks. Treat `VpnService.onRevoke()` as a fast signal, then confirm whether the VPN enforcement path is still active.
- On process start, reconcile Usage Stats access, VPN state and any variant-specific AccessibilityService state before marking the system healthy or emitting a recovery event. Do not depend on `onDestroy()` or shutdown broadcasts as the sole termination signal.
- Declare a `<queries>` launcher-intent entry for app-picker visibility and explicit package declarations only for the predefined common-app list. Register `ACTION_PACKAGE_FULLY_REMOVED` dynamically with a package data scheme where supported, and reconcile at startup/resume. Do not rely on runtime mutation of `<queries>` or assume removal broadcasts bypass package visibility.
- `QUERY_ALL_PACKAGES` is not a prerequisite. Request it for the Play flavor only if the Step 0 policy review confirms eligibility and the Play declaration is approved. For custom targets outside visible packages, `UsageStatsManager` history is supporting evidence only: recent foreground use suggests presence, while no recent use cannot prove uninstall. On `NameNotFoundException` or absent usage history, retain the rule and display `STATUS_UNKNOWN` with a manual verification action. Never auto-delete a rule based on package invisibility.
- Store the canonical package name separately from its display label. Split APK/App Bundle installs still resolve to the canonical package name; never infer package identity from the visible label. Report confirmed removals only as informational `TARGET_APP_REMOVED` events.
- Device clock, permission or callback transitions must not be allowed to reset server heartbeat age or suppress a required state transition.

### 8.8 Backend authorization and storage

- Use authenticated Cloud Functions HTTPS/callable endpoints for all sender envelope submissions. Derive sender identity from Firebase Auth, not caller-supplied fields.
- Before storage, verify device registration status, Ed25519 signature, route ownership, current pair state, recipient identity/key ID, routing-class consent, consent version, revocation state, schema version, payload size, request rate and idempotency. Use server-side checks for every route; Firestore Security Rules are not a substitute for these checks in trusted server code.
- Use Firestore transactions to create the immutable mailbox item, idempotency record and push-outbox entry as one commit.
- Restrict direct client mailbox writes. Permit recipient reads only for that recipient's own mailbox and provide no client query path that exposes other recipients' records.
- Configure Firestore indexes for DMS health evaluation, pending dispatch, and expiring records. Use a 90-day retention field for mailbox ciphertext and delivery metadata. Explicitly delete unsent route items when revocation is accepted; TTL deletion is eventual cleanup, not the immediate revocation mechanism.
- Store FCM tokens per installation, update tokens on refresh, remove invalid tokens on terminal FCM responses, and do not treat FCM send success as partner sync.
- The server's push dispatch and DMS state transitions must be idempotent and safe under duplicate function invocation and out-of-order triggers.

### 8.9 Backup delivery and DMS

- If a rule has a backup partner and the primary partner has not sync-acknowledged the message within 24 hours of backend acceptance, release the separately encrypted backup envelope. Create the backup envelope at the sender, because the backend cannot re-encrypt a payload it cannot decrypt.
- Keep the backup envelope in a server-only `held_items` collection that client rules and queries cannot read. A five-minute indexed, paginated due-item sweep moves eligible items transactionally into the backup recipient mailbox. Suppress/delete it if a valid primary sync receipt is committed first.
- If there is no backup partner, mark the primary delivery as unacknowledged and expose that state to the subject on their next app launch. Do not imply a human has ignored the message.
- Each accepted heartbeat creates idempotent delayed Cloud Tasks for the 8-hour and 24-hour evaluation stages. Each task compares its recorded heartbeat sequence with the latest server-side sequence and exits if a newer heartbeat exists. Do not run a global time-threshold Firestore sweep for normal DMS evaluation.
- At eight hours mark `STALE` and enqueue a subject nudge; at approximately 20 hours send a high-priority data-only FCM wake attempt to the subject device; at 24 hours mark `UNREACHABLE` and send one generic app-health notice to active health-consented partners if the outage guard is clear. The client can also report stale enforcement state if no `ENFORCEMENT_STATE` has arrived for 36 hours.
- The outage guard suppresses mass escalation if heartbeat-ingress errors exceed 5% over 10 minutes, the evaluator fails two consecutive runs, or more than 20% of active devices become newly stale in one evaluation batch. Alert operations, record suppression and re-evaluate after recovery. Evaluation and backup-release sweeps are paginated and capped at 5,000 records per invocation.
- No clean-shutdown token is used. The system does not rely on `ACTION_SHUTDOWN` or `onDestroy()` to prove the process ended.

### 8.10 Streak summary

- Calculate the daily streak summary locally from protected event/rule state. Do not upload raw event history for aggregation.
- Generate one per-recipient `STREAK_SUMMARY` per local calendar day, subject to that partner's consent and the relevant rule assignment.
- Derive the day boundary from the rule's persisted IANA time-zone ID. Time-zone changes do not silently rewrite an existing rule's time zone; a user changing the rule time zone creates a partner-visible rule mutation. For daylight-saving gaps, resolve a scheduled boundary to the first valid local time after the gap; for overlaps, use the earlier offset consistently.
- An `OVERRIDE` is an event-only record that a user explicitly invoked an existing bypass/allow action; it does not itself change a rule unless the existing rule API separately records that mutation. A streak day counts as compliant only when the rule was enabled for the full applicable schedule and no recorded violation or override occurred during its active window; disabled schedules are excluded, not counted as compliant. The separate 7-day/30-day analytics dashboard and its `ANALYTICS_ROLLUP` protocol are not part of this implementation.

## 9. Error Handling

- **No network / timeout / 5xx / rate limit:** retain the exact sealed envelope and retry with exponential backoff/jitter. Respect `Retry-After` when present. Do not generate a new event ID or ciphertext for the same outbox entry.
- **HTTP timeout with unknown server result:** retry the same idempotency key and bytes; the backend returns the previously committed result if accepted.
- **401/expired Firebase token:** refresh authentication once, then retry. If reauthentication is required, retain the outbox and show a pending-auth state.
- **403 route/consent revoked:** stop creating new ordinary entries for that category, preserve pre-change events and control notices according to flush-before-revoke ordering, and show the actual server state. Do not retry a permanently unauthorized route indefinitely.
- **400 malformed/schema mismatch/oversize:** move the entry to `DEAD_LETTER`, record a redacted reason code, and show a repair-required state. Never log payload bytes.
- **Invalid signature/key ID/key mismatch:** reject before mailbox write. Record a security event without payload content and require re-pair/key repair where appropriate.
- **HPKE decryption failure on recipient:** preserve the encrypted inbox item, mark `DECRYPTION_FAILED`, verify key ID/version, and show a key-repair action. Do not discard the message or attempt plaintext fallback.
- **Keystore unavailable or keyset integrity failure:** fail closed for partner event sending/decryption. Do not silently generate a new identity key or fall back to plaintext. Preserve the encrypted database state and initiate key-repair/re-pair guidance.
- **SQLCipher open/migration failure:** do not destructively rebuild the database. Preserve the original database file, stop writes requiring the failed schema, report the migration error, and provide a recoverable support/repair path.
- **Play Integrity quota/unavailability:** do not reuse an expired or request-bound token for a different request. Defer or lower the trust tier only according to the fixed flavor policy; never silently accept an operation that requires high-risk verification as if verified. Safety-critical revocation remains locally effective and queues its signed request for backend processing.
- **FCM send failure / invalid token / notification denied:** retain mailbox data. Retry transient FCM errors, remove terminal-invalid tokens, and surface notification unavailability in partner health state. Partner sync on app launch remains available.
- **Missed heartbeat:** set `STALE` at eight hours and `UNREACHABLE` at 24 hours. Report only that status cannot be verified. Never infer a specific cause from silence.
- **Backend outage recovery:** use the global ingestion-health guard to prevent a mass false escalation storm. Reconcile current heartbeat records before releasing stale-state notices.
- **Outbox cap:** coalesce eligible low-priority repeats and preserve reserved capacity for critical events. Never silently drop a critical event.

## 10. Security Requirements

1. **Pairing trust:** Require both devices to scan each other's short-lived public-key QR codes, compare the same six-word verification phrase, and confirm. Backend pair activation requires both signed acceptance statements. Expired pairing sessions cannot be reused.
2. **Key storage:** Never store plaintext private keys in SharedPreferences, ordinary files, databases, crash logs or analytics. Tink private keysets must be encrypted with an Android Keystore AES-GCM wrapping key in no-backup storage. Database key wrapping uses a separate Keystore key alias.
3. **Encryption/signature:** Use the HPKE and Ed25519 algorithms defined in Section 4.4. Bind header bytes into HPKE context information and sign the header plus ciphertext output. Verify signature before accepting/decrypting an envelope. Never implement cryptographic primitives manually.
4. **Server authorization:** Check authenticated sender, enrolled device key, route ownership, current assignment, event-class consent, consent version, active route state, recipient key, schema and idempotency on every request. Do not trust caller-supplied sender/recipient UIDs or a valid signature as sufficient authorization.
5. **Replay/ordering:** Deduplicate by sender device + route + envelope ID. Reject conflicting reuse of an idempotency key. Validate contiguous sequence on the primary route; mark backup routes `SPARSE` and never label their expected gaps as tampering. V1 does not implement a hash chain.
6. **Consent/revocation:** Subject-controlled category visibility and partner role acceptance are separate. Signed server-visible `RouteAuthorizationUpdate` requests carry a monotonically increasing version. Pre-change events flush before the final notice; post-change events are not generated for the disabled category. Revocation is never refused due to outbox capacity. Partner-initiated unpair is supported. Rules with no eligible assigned partner become visibly `UNSUPERVISED`, remain enforced, and weakening is logged locally or sent to remaining eligible partners.
7. **Data minimization:** Keep package/rule/event details in the encrypted inner payload only. Keep notifications generic. Apply redaction separately per recipient before encryption. Never log plaintext payloads, target package names, rule names, or violation details in backend logs or Crashlytics.
8. **Firebase access:** Clients submit via authenticated endpoints; they do not write arbitrary mailbox/outbox documents. Mailbox reads are recipient-scoped. Cloud Function service accounts use least privilege. Rate-limit account, device, route and IP requests without using IP as identity proof.
9. **Play Integrity:** Use Play Integrity/App Check on Play-distributed builds for registration and high-risk key/pairing changes. Bind each token to its specific request hash/nonce and verify server-side. Do not reuse a request-bound token for unrelated events. Sideload builds use registered-key challenge-response and a distinct lower-attestation trust label; do not claim equivalent protection against binary tampering.
10. **Local database and backup:** Keep new feature keysets and feature-specific key-bound state out of Android Auto Backup/data extraction. Inspect the existing backup policy first and do not change backup behavior for unrelated rules/history or the entire SQLCipher database unless that database-wide exclusion is already the established product decision. Key-bound feature data must not restore without its wrapping key. No plaintext fallback is allowed after backup/restore or key loss.
11. **Root/owner threat:** Treat root, active debugging, code instrumentation, recompiled apps and direct physical control as residual risks. Signatures and Integrity checks increase friction and provide signals; they do not prove that a voluntarily installed app's enforcement state is truthful on a device controlled by its owner.
12. **Server DMS events:** Sign server-generated app-health control events with a Cloud KMS asymmetric key. Pin/rotate the verification public key in the app with explicit key IDs. The client must reject a control event with an unknown key ID or invalid signature. Bundle at least the current and next public verification keys during a rotation window, identify the signer with a server key ID, and support a signed key-set update fetched from a trusted HTTPS endpoint with local caching. Never require an app update for every routine KMS key rotation.
13. **Retention/deletion:** Delete mailbox ciphertext after sync acknowledgement plus seven days; unsynced items expire after 90 days. Revocation cancels only not-yet-accepted items. Firestore TTL cleanup is eventual, so access-control state must prevent expired/deleted items from being served before physical TTL deletion completes. Do not promise remote deletion of content already downloaded/decrypted by a partner.
14. **Notification privacy:** FCM message content must not include package identifiers, rule names, violation details, streak values, or other behavioral data. Use generic alert wording and fetch the encrypted mailbox item after sync.
15. **Distribution/privacy disclosure:** The onboarding and partner-consent UI must explain which alert categories a partner can receive, what the backend metadata reveals, how stale-heartbeat alerts work, and the limits of offline revocation. The UI must not promise stealth-proof or tamper-proof enforcement.

## 11. Performance Requirements

- Schedule the recurring heartbeat with WorkManager at a two-hour interval. Use `PeriodicWorkRequest` semantics correctly; actual execution is best-effort, not exact. The minimum periodic interval is 15 minutes and the system may delay execution for constraints and battery optimization. See [Android WorkManager scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
- Do not use exact alarms or constant polling for the heartbeat, coalescing, or outbox retry loop.
- Throttle routine immediate heartbeats to one per ten minutes; enforcement-state transitions may request immediate work but must still use unique-work deduplication.
- Use cursor/page-based mailbox sync. Process bounded pages and continue with another WorkManager request when more messages remain; do not load an unbounded mailbox into memory.
- Limit each envelope to 64 KiB. Split an oversized summary into multiple summary envelopes rather than exceeding the limit.
- Use unique WorkManager jobs for the outbox dispatcher, mailbox sync, coalescing closure, heartbeat and daily streak summary. Ensure each job is idempotent and safe to run more than once.
- Batch Firestore operations only where semantics allow. Preserve transactional acceptance, idempotency, and route authorization; do not batch messages across recipients into a shared plaintext document.
- Avoid calling Keystore operations concurrently from many worker threads. Serialize cryptographic keyset access through the Crypto Manager and retry transient keystore/provider failures only after bounded local serialization, not in a busy loop.
- Use aggregate counters and summary records to bound local storage under violation bursts. Keep a 10,000-record/50-MiB soft pending limit and a separate 512-KiB critical-event reserve as specified in Section 8.5.
- Measure CPU, memory, database size, wakeups, event generation latency, outbox oldest age, sync latency and battery impact on representative devices. Do not claim a daily battery percentage until it has been measured under a repeatable protocol.

## 12. Testing Requirements

### 12.1 Unit tests

- Redaction tests for every consent level and event category, including no leakage of raw package names when redacted.
- Protocol tests for protobuf serialization, exact signature preimage, key IDs, wrong-recipient decryption, wrong-context decryption, invalid signatures, altered headers, altered ciphertext, unsupported versions, oversize payloads and malformed fields.
- Tink HPKE round-trip tests and fixed test vectors shared with the TypeScript backend.
- Pairing tests for expired/duplicate QR sessions, mismatched verification phrases, missing second-party confirmation, changed key fingerprints, and forged acceptance statements.
- Consent tests verifying that event creation checks the right recipient/category, weakening actions always create durable notice events, and revoked routes reject later submissions.
- Outbox state-machine tests for process death at every transition, expired leases, idempotent retry, conflicting idempotency key, network timeout after server commit, permanent 4xx errors, and outbox capacity exhaustion.
- Coalescing tests for first event immediate delivery, repeat events within and after five minutes, persisted bucket after process death, reboot during the window, critical-event exemption, and nonzero aggregate display.
- Enforcement-state tests for `FULL`, `DEGRADED`, `DOWN`, `UNKNOWN`, startup reconciliation and recovery transitions.
- Time tests for monotonic clock resets at reboot, wall-clock changes, cross-midnight windows, timezone changes, and DST edge behavior.
- SQLCipher/Room migration tests using pre-populated encrypted databases and every supported old schema version. Verify no destructive fallback.

### 12.2 Backend integration tests

- Reject a caller that supplies another user's UID but lacks that authenticated account/device registration.
- Reject a valid signature submitted to an unauthorized route or after consent revocation.
- Verify no mailbox document is committed on any authorization/validation failure.
- Verify Firestore transaction writes mailbox item, idempotency record and push-outbox record together.
- Invoke the same function event repeatedly and out of order; verify one logical mailbox item and correct dispatch state.
- Test concurrent submissions for the same envelope ID and for distinct envelope IDs with the same event payload.
- Test recipient-specific mailbox access, route separation, and least-privilege service permissions.
- Test FCM token rotation, invalid token deletion, notification failure, partner sync acknowledgement, delayed backup release, backup suppression on timely primary sync, and expired mailbox records.
- Simulate a global heartbeat-ingestion outage and recovery; confirm the DMS suppresses mass false alerts while reconciling.
- Verify server-signed DMS controls are rejected if signature/key ID is invalid.

### 12.3 Android integration and system tests

- Run on API 26+ compatibility coverage and specifically Android 14+ restrictions; do not only test on emulators.
- Test Usage Stats revocation, VPN revoke/disconnect, AccessibilityService disable where applicable, app process restart, boot reconciliation, permission auto-reset/hibernation behavior, notification permission denial, Do Not Disturb, and partner app force-stop.
- Test `adb shell am force-stop` as a separate scenario. Verify that local jobs do not falsely claim to run while force-stopped; verify the server eventually reports `UNREACHABLE` under the defined 24-hour rule and buffered events sync after manual relaunch.
- Test Doze using ADB, then restore device state. Preserve pre-existing enabled Accessibility services; never overwrite the entire setting without saving and restoring it.
- Test target package removal via system broadcast on a real device and test startup reconciliation. Do not use only unit tests as proof of Android broadcast delivery.
- Test app reinstall, data clear, backup restore attempt, device replacement, lost key, re-pairing and 90-day mailbox expiry behavior.
- Test at least one current Xiaomi/HyperOS, Samsung/One UI, Oppo/ColorOS, and locally representative Tecno/Infinix/itel device. Record OS build, battery state, network type, app standby state and test timestamps.
- Test multi-day offline periods, no notification permission, no partner sync for 24 hours, no backup partner, partner key rotation while events are pending, and server recovery after outage.

### 12.4 Test pass criteria

- No invalid, unauthorized, revoked, or signature-invalid event is accepted by the mailbox.
- No transient network failure causes loss of an event that remains in the durable outbox.
- No replay produces a second logical event or second local inbox record.
- A partner receives decrypted content only if the registered recipient private key matches the envelope recipient key ID.
- The DMS never labels a missing heartbeat as proven uninstall or proven enforcement failure.
- A critical event is never coalesced or silently deleted.
- No test logs include plaintext behavioral payloads or private-key material.
- Every migration preserves all existing data and opens the encrypted database successfully.

## 13. Deployment Requirements

- Add the backend schema and functions in an additive, backward-compatible deployment before enabling the client feature.
- Add Firestore indexes, rules for recipient-only mailbox reads, IAM permissions for Cloud Functions/Cloud KMS, TTL fields, scheduler configuration, and secret/key configuration through infrastructure-as-code or the repository's existing deployment process.
- Store backend server signing key material in Cloud KMS; do not place signing private keys in source control or ordinary environment files.
- Keep the feature disabled by default behind a remote/config feature flag. Backend ingress must reject unsupported protocol versions cleanly and must not interpret unknown enum values as authorized categories.
- Add only forward Room migrations. Destructive database migration is prohibited. Test migrations with encrypted production-like database fixtures before rollout.
- Deploy backend support for protocol version 1, mailbox acceptance, idempotency and DMS before releasing the Android client that emits version 1 envelopes.
- Release the Android client with event production disabled until key pairing and backend acceptance tests pass. Then enable for internal test accounts, a small canary cohort, a larger cohort, and finally all eligible users. Advance only when authorization failures, decryption failures, outbox growth, false DMS alerts and crash rates remain within the release gates defined by existing project telemetry; if no baseline exists, hold at internal/canary until one is established.
- Keep old protocol readers only when an existing released client actually uses the older version. Do not invent a legacy envelope mode if none exists in the repository.
- Rollback must disable feature emission through the feature flag and leave the backend able to read already accepted envelopes. Do not delete mailbox collections or key metadata needed by clients during rollback.
- Revoke or rotate signing keys through a documented `key_id` transition with overlap for verification. Emergency revocation must stop accepting new signatures from the compromised key.
- Ensure Android backup/data-extraction rules exclude SQLCipher database files, database-key wrappers, private keysets, and pending sealed outbox records.
- Before enabling the Play build, complete current Play policy review for AccessibilityService use (if present), package visibility, notification behavior, and foreground-service types. Do not declare `systemExempted` unless the app meets Android's documented eligibility. Do not rely on an unapproved `specialUse` declaration. See [Android foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

## 14. Monitoring Requirements

### 14.1 Client metrics

Record only non-sensitive technical metrics:

- Pending outbox count, oldest pending age, bytes used, retry count and error class.
- Seal/sign/verify/decrypt success and failure counts by protocol version and key ID class, never raw keys or payloads.
- Time from event persistence to backend acceptance.
- Partner mailbox sync lag, accepted-to-sync time, decryption failures, sequence/hash gaps and dead-letter counts.
- Current enforcement state and transitions by mechanism, using aggregate app/build/OS labels only.
- Heartbeat execution lag, last successful heartbeat age, and WorkManager result class.
- Keystore/SQLCipher opening failures, migration failures, notification permission state and FCM token update failures.

Do not log rule IDs, package identifiers, decrypted event bodies, QR contents, verification phrases, Firebase ID tokens, FCM registration tokens, private keys, ciphertext blobs, or full envelope headers to Crashlytics or standard logs.

### 14.2 Backend metrics and alerts

- Valid/invalid envelope counts, signature failure rate, authorization rejection rate, replay/idempotency hit rate, mailbox commit latency, push dispatch failures and invalid FCM tokens.
- Counts and age distribution for `STALE`/`UNREACHABLE` installations, DMS notices and recovery notices.
- Heartbeat ingestion availability and scheduler execution lag. The DMS must have an independent alert when the evaluator or ingestion pipeline itself is unhealthy.
- Mailbox queue size/age, 90-day TTL backlog, per-account quota violations and revocation processing latency.
- Track delivery as distinct states: accepted by backend, FCM send attempted/succeeded, synced by partner app, decrypted, and human acknowledged only when an explicit human action exists.
- Alert on sustained rise in signature failures, authorization failures, decryption failures, DMS false-positive reports, outbox backlog, migration failures, or mailboxes approaching quota.
- Every operational alert must include a runbook reference and must avoid plaintext user behavior details.

### 14.3 Initial service objectives

- A successful backend `ACCEPTED` response must correspond to a committed Firestore mailbox item and idempotency record.
- Under healthy network/backend conditions, measure p50/p95 event-persistence-to-backend-acceptance and backend-acceptance-to-FCM-attempt latency. Establish numeric latency objectives only after the canary measurements are collected; do not advertise an unmeasured delivery guarantee.
- The scheduled DMS evaluator must be monitored independently from the heartbeat path. If it is unhealthy, the UI/operations state must indicate that app health is not being evaluated reliably.

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection strategy |
|---|---|---|---|---|
| Subject-controlled device tampers with app or fabricates enforcement reports | High | High | Treat signatures/Play Integrity as signals; show reported versus verified state; document root/debug residual risk | Integrity verdict trend, impossible state transitions, device/key anomalies |
| Partner key substitution during pairing or rotation | Low after controls | Critical | Remote deep-link or in-person QR pairing, out-of-band safety phrase, pinned fingerprints and signed confirmation | Key mismatch, verification failure and unexpected key-change events |
| Backend accepts an event after consent or route revocation | Medium without controls | Critical | Server-side authorization and transactional revocation/route checks | Negative authorization tests and production rejection metrics |
| Silent event loss during process death or network failure | Medium | High | Durable Room outbox, immutable envelope bytes, idempotency and persistent retry state | Oldest outbox age, retry failures, test process death between each state transition |
| Doze/OEM scheduling delay creates misleading DMS alerts | High | High | Two-hour best-effort heartbeat, 8-hour stale state, 24-hour unverified alert, truthful wording | False-alert reports segmented by OEM/OS/network; heartbeat lag distribution |
| Partner's FCM/notifications are disabled or app is force-stopped | Medium | High | Firestore mailbox source of truth, partner sync on launch, notification-capability state, backup route | accepted-to-sync age, invalid FCM tokens, notification permission reports |
| Duplicate/out-of-order function invocation | Medium | High | Firestore transactions, idempotency records, immutable envelopes and client dedupe | duplicate invocation tests, idempotency hits, duplicate inbox assertions |
| Consent revocation while sender is offline | Medium | High | Stop local generation immediately, show `REVOCATION_PENDING_SYNC`, prioritize signed revocation, disclose remote delay | pending-revocation age and tests across offline intervals |
| Key loss/device replacement makes prior ciphertext undecryptable | Medium | High | No silent key regeneration; explicit re-pair flow; 90-day retention; clear UI | keyset open failures, re-pair cases, device-migration tests |
| Room/SQLCipher migration damages local events | Low after tests | Critical | Additive migrations only, encrypted fixture tests, backup exclusion and recoverable failure path | migration CI suite, startup migration telemetry |
| Coalescing suppresses a critical event or summary is delayed | Medium | High | Critical-event exemption, durable occurrence rows and persisted fixed-window deadline | coalescing property tests, summary age/count checks |
| Outbox/storage limit is reached | Low to medium | High | Soft/hard limits, reserved critical-event capacity, coalesce eligible repeats, reject weakening actions rather than dropping events | storage usage, reserve usage, overflow state count |
| Firebase/Functions outage triggers mass DMS alerts on recovery | Medium | High | Global ingestion/evaluator health guard and reconciliation before alerting | service health status, DMS burst rate, outage drill |
| Public app-store policy change blocks service behavior/distribution | Medium | High | Review current policies before Play release; documented FGS types; build-flavor controls | Play Console review results, crash/permission metrics on target API |
| Metadata exposure is mistaken for full anonymity | Medium | High | Use opaque routes and coarse routing classes; document visible metadata and data retention | privacy review of envelope fields, logs and Firestore schema |
| Mailbox TTL/cost grows due offline recipients | Medium | Medium | Seven-day post-sync ciphertext deletion, 90-day unsynced TTL, quotas and queue-age monitoring | TTL backlog, bytes per mailbox, cost dashboard |
| Consent/revoke ordering loses pre-change evidence | Medium | Critical | Separate control table; pre-change events flush before final notice; accepted mailbox items are not cancelled | offline revoke fault tests and pending-count assertions |
| Partner leaves and leaves a rule unsupervised | Medium | High | Partner-initiated unpair; visible `UNSUPERVISED` rule state; weakening remains logged/notified | partner revoke and orphan-rule tests |
| Sparse backup sequence creates false tamper warnings | Medium | Medium | Backup route marked `SPARSE`; contiguous sequence checks only on primary route | backup delay/gap tests |
| Tink/Node signature format mismatch | Medium | Critical | RAW Ed25519 templates, raw key IDs, cross-platform vectors | Android-sign/Node-verify CI vectors |
| FCM data-only message throttled by OEM | High | Medium | App-launch and WorkManager fallback; subject UI explains OEM limits | two-week canary by OEM and standby bucket |
| Play policy or data-protection review blocks launch | Medium | Critical | Step 0 go/no-go before public rollout; account deletion and region decision | signed policy/legal checklist |
| Existing backup/restore behavior regresses | Low | High | Limit backup exclusion to feature key material and feature-specific state | restore tests with existing database fixtures |

## 16. Open Questions

The following are the only unresolved items. They are release gates, not permission for the build model to invent behavior. Record evidence and the decision owner for each before the relevant milestone can ship.

1. **Play policy and legal go/no-go:** Verify current Google Play policies for AccessibilityService use, monitoring/accountability features, `QUERY_ALL_PACKAGES`, foreground-service declarations, and account deletion. Obtain review of Kenya Data Protection Act/ODPC obligations and cross-border processing for the selected Firebase region. Do not launch publicly until this review is recorded.
2. **`QUERY_ALL_PACKAGES` eligibility:** Determine whether the Play-distributed accountability use case qualifies under current Play policy. The architecture must continue to work without approval; approval may improve package visibility but is not a prerequisite for core operation.
3. **Foreground-service declaration:** During repository inventory, verify the exact permitted service type for each existing VPN/enforcement service against current Android documentation and the app’s actual role. Do not invent or declare `systemExempted` or `specialUse` without a documented eligibility basis.
4. **Firebase deployment region:** Select and document the region based on latency, availability, cost, and Kenya data-protection review before production deployment.
5. **Current repository facts:** Record actual source paths, Room version/migrations, auth provider, Firebase project/region, existing pairing protocol, build flavors, backup rules, CI device matrix and monitoring setup during Step 0. These are inventory tasks, not architecture alternatives.
6. **Canary thresholds:** Set final DMS thresholds only after the two-week canary measures heartbeat lag by Android version, OEM and standby bucket. Initial implementation thresholds remain 8 hours for `STALE`, a subject nudge at `STALE`, a server wake attempt at approximately 20 hours, and 24 hours for partner `UNREACHABLE`, behind a server-side configurable policy.

No other architectural or product choice is open. Where current policy or platform evidence blocks a required release, keep that milestone disabled and report the specific blocker; do not silently substitute a weaker implementation.

## 17. Acceptance Criteria

- [ ] Partner pairing supports remote deep-link and in-person QR paths; both require matching safety phrase, both signed confirmations, and a visible verification-method badge before route activation.
- [ ] Public-key mismatch or unconfirmed key rotation blocks encrypted sending and is visible to the user.
- [ ] No private key is stored in plaintext SharedPreferences, app files, database fields, logs or analytics. Tink keysets are encrypted by Android Keystore wrapping keys.
- [ ] SQLCipher uses a random database key wrapped by a separate Android Keystore key; backup/data-extraction rules exclude the database and key-bound data.
- [ ] Every partner-bound envelope uses the specified Tink HPKE suite, includes header context binding, and is signed over exact header bytes plus the opaque HPKE output.
- [ ] The backend verifies sender authentication, registered device signature, route authorization, consent, key ID, schema, size, idempotency and rate limits before writing a mailbox item.
- [ ] A committed `ACCEPTED` result corresponds to an atomic Firestore transaction containing mailbox item, idempotency record and push-dispatch-outbox record.
- [ ] Replaying the same envelope does not create a duplicate logical event. Reusing an idempotency key with different bytes is rejected.
- [ ] A network/process failure at every outbox transition preserves the event and lets the app retry with the same envelope bytes and idempotency key.
- [ ] Repeated low-priority violation attempts use the fixed five-minute durable coalescing mechanism. Critical/control events are never coalesced.
- [ ] Rule creation, reassignment, weakening, disable, delete, consent change and revocation create durable partner-visible event records in the same local transaction as the triggering state change.
- [ ] Revocation immediately stops local generation, visibly reports pending remote sync when offline, and causes backend rejection of later route events after server commit. Unsent route messages are cancelled except the final revocation notice.
- [ ] The partner inbox persists messages before sync acknowledgement, deduplicates them, verifies signatures, decrypts with the correct key version, validates primary-route sequence continuity, and does not flag sparse backup sequences as tampering.
- [ ] Backup delivery releases at 24 hours if primary sync acknowledgement is absent, and is suppressed if the primary sync acknowledgement arrives before that deadline.
- [ ] Target app removal is informational and is not counted as a violation.
- [ ] Enforcement state is reconciled at app process start and after relevant permission/service changes. No `onDestroy()` or shutdown callback is a primary termination guarantee.
- [ ] Heartbeat uses two-hour best-effort WorkManager scheduling, server status becomes `STALE` after eight hours and `UNREACHABLE` after 24 hours, and notification wording does not claim a proven cause.
- [ ] Each heartbeat schedules idempotent delayed Cloud Tasks at 8 and 24 hours; tasks compare the current sequence before evaluating. The DMS outage guard suppresses escalation at the specified error/batch thresholds and records the suppression. A subject nudge at 8 hours and data-only wake attempt at approximately 20 hours are verified.
- [ ] Play-distributed builds use request-bound Play Integrity/App Check for registration and high-risk key/pairing actions without token reuse. Sideload builds use the defined registered-device trust tier.
- [ ] Synced mailbox ciphertext is deleted after sync acknowledgement plus seven days; unsynced items expire after 90 days. Route revocation cancels only not-yet-accepted items, and access control blocks serving expired/deleted records before eventual TTL cleanup.
- [ ] No destructive Room migration is present. All migrations pass against pre-populated SQLCipher fixtures.
- [ ] Tests pass for API 26+ compatibility, Android 14+ foreground/background restrictions, Doze, force-stop, permission revoke, OEM variations, notification denial, partner offline periods, key rotation and backend outage recovery.
- [ ] Crashlytics and backend logs contain no plaintext behavior payloads, private keys, tokens, QR content or verification phrases.
- [ ] The multi-period analytics dashboard and `ANALYTICS_ROLLUP` pipeline are not implemented by this feature.

## 18. Definition of Done

The feature is done only when all of the following are true:

1. The required repository inventory is documented and changes map to actual existing components without duplicate services or unrelated refactors.
2. Protocol schema and crypto test vectors are shared and pass on Android and backend implementations.
3. Pairing/key trust, encrypted local key storage, consent/revocation, durable outbox, envelope signing/encryption, backend authorization, mailbox delivery, partner sync, backup delivery, enforcement reconciliation and DMS are implemented according to this document.
4. Room/SQLCipher migrations are additive, tested with encrypted fixtures, and preserve existing data.
5. Unit, integration, fault-injection, security and migration suites pass in CI; the Android system tests are completed on the required API and OEM matrix.
6. Backend deployment, scheduled DMS evaluation, TTL cleanup, KMS signing, FCM token management and rollback controls are documented and verified in a non-production environment.
7. Operational metrics and alerts distinguish app health from backend health and distinguish server acceptance from FCM delivery, partner app sync, decryption and any explicit human acknowledgement.
8. Consent and onboarding screens accurately explain partner visibility, metadata exposure, offline revocation delay, notification limitations, backup escalation semantics and root/device-owner residual risk.
9. The feature remains disabled until protocol, authorization, pairing and failure-recovery acceptance tests pass; rollout proceeds through internal testing and canary gates.
10. No unresolved Critical or High security/reliability findings remain in the code review for this implementation. Any exception requires a documented, explicitly accepted risk and cannot be silently waived by the build model.
11. The implementation contains no plaintext fallback, no private key logging, no destructive database migration, no exact-alarm dependency, and no unsupported claim that a heartbeat proves enforcement is active.
12. The final code review includes Android, backend, security and QA reviewers, and the release notes describe known monitoring limits without implying guaranteed instant delivery or tamper-proof enforcement.

---

## Appendix D — Delta audit against shipped code (2026-10-29)

**Method.** Every requirement in §2 and the milestone steps in §7 was checked against the
shipped source, not against other documents. Owner ruling for this build: **delta only** —
implement genuine gaps, never rebuild shipped pairing, crypto, mailbox, outbox, consent or
DMS. Verdict vocabulary: **SHIPPED** (an equivalent exists, with code anchor), **PARTIAL**
(some of the requirement exists), **GAP** (nothing implements it), **REJECTED** (conflicts
with an owner-approved shipped decision), **SUPERSEDED** (a newer owner decision replaced
the requirement's numbers/mechanism).

### D.1 M1 — pairing, crypto, mailbox, outbox, consent, revoke

| Req | Verdict | Evidence / remaining work |
|---|---|---|
| 1 per-rule `assigned/backup/streak_partner_uid` | **SHIPPED** (delta 2026-10-29, ADR #226) | `restriction_rules.assigned_pair_id` / `backup_pair_id` / `streak_pair_id` (schema v21, additive; `MIGRATION_20_21`); editor picker over live enrollments, row label, `UNSUPERVISED` read model. Roles are local **pair ids**, not uids (the enrollment carries the verified key id; a pair id is the local trust unit). Only `assigned` is selectable — backup/streak are stored and unused until req 14/15/18. |
| 2 assignment valid only after mutual pairing + key verification | **SHIPPED** (pair level) | `PairingFlow`, `PairingProof`, `AttestationVerifier`; the picker only offers live enrollments and the row model derives `UNSUPERVISED` when the stored pair leaves the roster (ADR #226). |
| 3 rule create/reassign/weaken/disable/delete durable partner event in the same transaction | **SHIPPED** (delta 2026-10-29, ADR #226) | `RulesRepository.journalRows` feeds `insertAndBump`/`updateAndBump`/`deleteAndBump` (Room `@Transaction`): CREATED, ASSIGNED/UNASSIGNED (reassignment writes both), UPDATED (any other mutation — a superset of "weakening"), DISABLED, DELETED. Unassigned rules journal nothing (no eligible recipient); an unrepresentable id/clock skips the row, never the write. |
| 4 violation persisted immediately; first dispatched; 5-min fixed window summary | **PARTIAL** | Attempts persist immediately (`AttemptLog`) and each pending attempt is dispatched individually (`PartnerSyncCoordinator.kt:332-338`); no window, bucket or `ALERT_STORM_SUMMARY`. |
| 5 never coalesce control events | **N/A today** | Control types (deletion, approval, revoke, sync, rule change) are separate payload types and are never queued through an attempt path. Applies once req 4 ships. |
| 6 per-recipient redaction before encryption | **SHIPPED equivalent** | Per-pair sharing flags gate every activity/streak event before journaling (ADR #115/#135); the accountability allowlist carries category only, never a target (`AccountabilityEventWire.kt:37-49`, ADR #196); rule rows carry a rule UUID + closed verb, never a package name (ADR #226). |
| 7 HPKE X25519 + Ed25519 | **REJECTED** | Shipped protocol is ECDH P-256 + HKDF + AES-GCM + ECDSA low-S with hardware attestation (ADRs #96–#102, #112). Rebuilding the crypto would violate the delta-only ruling and break the shipped chain. |
| 8 inbound auth/authorization/schema/dedupe | **SHIPPED** | Relay + rules + mailbox pipeline (ADRs #194, #198, #199). |
| 9 accepted ≠ delivered | **SHIPPED** | ADR #203; ack pipeline #199. |
| 10 FCM wake only; mailbox is truth | **SHIPPED** | ADRs #199/#203; `MailboxOutboxDrainer`, `FirestoreMailboxReceiver`. |
| 13 sync ack after durable local write | **SHIPPED** | ADR #199. |
| 16 app-health to all consenting pairs; rule events per route | **SHIPPED** (delta 2026-10-29, ADR #226/#227) | Monitoring snapshots ship to all paired, consenting partners (`MonitoringSnapshotRecorder`); rule-lifecycle rows carry `route_pair_id` and `dueRuleChangesForPair` routes each envelope to the assigned pair only and deletes the row once chained (ADR #227), while the legacy `dueForDelivery` loop selects `route_pair_id IS NULL` only — neither queue can see the other's rows, so no fan-out path can pick up a rule row. |
| 19 revoke local-immediate, pending-sync UI | **SHIPPED** | ADR #205; `UnpairState`. |
| 20 flush-before-revoke; retention windows | **SHIPPED equivalent, stricter** | ADR #205 ordering; `mailbox-retention.js` deletes after a 7-day floor AND all-install ack, or unconditionally at 30 days (AD-18b) — stricter than the plan's 90-day unsynced cap. |
| 21 signed `RouteAuthorizationUpdate` with category bitmask + version | **SUPERSEDED** | Shipped consent is per-pair sharing flags (`PairProfileSharing`, `PairStreakSharing`) synced to the partner; the relay authorizes by pair membership (ADR #194). No signed consent version document exists; the delta-only ruling keeps the shipped model. For `RULE_CONTROL`, the per-rule assignment is the subject's own visibility choice (ADR #226). |
| 22 §8.4 authorization matrix | **SHIPPED for rule rows / SUPERSEDED elsewhere** (ADR #226) | `RULE_CHANGED` → `RULE_CONTROL` route → current assigned partner only, independent of the activity consent matrix; backup release does not exist yet (req 14). Other rows keep the shipped mapping: attempts/sessions → legacy activity events; monitoring → `MONITORING_STATUS`; deletion/approval/SOS → dedicated types. |
| 23 partner-initiated revoke; `UNSUPERVISED` rules | **SHIPPED** (delta 2026-10-29, ADR #226) | Partner-initiated unpair ships (ADR #205). An assigned rule whose pair leaves the roster reads `UNSUPERVISED` at read time — still enforced and visible, never auto-cleared or auto-transferred; re-pairing mints a new pair id and the user reselects. |
| 24 control events always writable, never refused | **SHIPPED equivalent** | Control types are separate chain events; there is no outbox-capacity refusal path in the shipped model (chain cap applies uniformly, no silent eviction). |
| 25 one active device per account; superseded heartbeat rejected | **DIFFERENT, accepted** | Heartbeat namespace is `(uid, installId)` (`heartbeat-logic.js`); multiple installs legitimately hold separate documents and there is no supersession. No multi-device blocking ships. |

### D.2 M2 — enforcement reconciliation and DMS

| Req | Verdict | Evidence |
|---|---|---|
| 11 reconcile at process start and on state changes | **SHIPPED** | `MonitoringSnapshotRecorder` (service-start + hourly + change debounce), ADR #200. |
| 12 best-effort heartbeat; silence ≠ proof | **SHIPPED** | `PartnerHeartbeatClient`, DMS server (`heartbeat.js`, `dms-processor.js`), ADRs #200/#201. |
| 8h STALE / 20h wake / 24h UNREACHABLE | **SUPERSEDED** | Owner policy: 6-hour partner silence notice (kept 2026-10-28), 45-minute silent liveness probe (ADR #223), 24-hour unverified notice. |
| outage guard, pagination, caps | **SHIPPED** | ADR #201 (`dms-logic.js` breaker + paging). |
| Step 11 two-week canary by OEM | **PARTIAL / BLOCKED** | Compatibility matrix shipped with honest `UNVERIFIED` rows (ADR #206); a live fleet canary is blocked on distribution/fleet size. |

### D.3 M3 — backup partner, target removal, streak summaries

| Req | Verdict | Evidence / remaining work |
|---|---|---|
| 14 backup partner + 24 h held-item release | **GAP** | No `held_items` collection, no due-sweep function, no backup role or separately encrypted backup copy in the client. |
| 15 cancel backup on primary sync-ack | **GAP** | Follows req 14. |
| 17 `TARGET_APP_REMOVED` informational | **GAP** | No `ACTION_PACKAGE_FULLY_REMOVED` handling anywhere in `app/src/main`. |
| 18 daily redacted `STREAK_SUMMARY` to a streak partner | **PARTIAL** | Event-shaped streak disclosure ships (`streak_updated`, `relapse_recorded`, `milestone_reached` with per-pair consent, ADR #134); there is no daily summary job or streak-partner role. |
| §8.10 time-zone/DST rules for a daily summary | **N/A** | No daily summary exists yet. |
| 26 Play flavor first; sideload gated on inventory | **REJECTED / SUPERSEDED** | Owner ruling: no Play Store distribution; direct APK is the channel (ADR #15, ADR #171). The sideload/attestation-tier requirement is the shipped design. |

### D.4 Remaining build scope

The rule-identity cluster that D.1 listed as deferred was **built on 2026-10-29** (ADR #226):
per-rule assignment (req 1), transactional rule lifecycle events (req 3), rule-scoped routing
(req 16/22) and `UNSUPERVISED` on unpair (req 23). What remains from this plan:

- **Violation-storm coalescing (req 4/5):** attempts still dispatch individually; no five-minute
  window or `ALERT_STORM_SUMMARY`. Depends on the now-shipped rule-route rows but changes the
  attempt dispatcher, not the rule write path.
- **Backup delivery (req 14/15):** needs a server-side held-item collection, a due sweep, and a
  release timer that the primary's sync-ack cancels. The `backup_pair_id` column ships unused.
- **Target-app removal (req 17):** no `ACTION_PACKAGE_FULLY_REMOVED` handling anywhere.
- **Daily redacted streak summary (req 18 + §8.10):** event-shaped streak disclosure ships
  (ADR #134); a daily summary job and the `streak_pair_id` role remain unbuilt (column stored,
  unused).

Each of these still needs its §12 test matrix and §12.3 device legs before it can be called
done. They stay recorded here as open, **not** as done.

**Shipped in the 2026-10-28/29 night session (evidence in ADR #223/#224):** the silent
45-minute liveness probe (client + server, device-proven), the HTTP-5xx-only Cloud Functions
alert policy (live, verified), and a Room `@Upsert` log-noise fix in `PartnerLivenessDao`
(INSERT OR REPLACE).

**Shipped in the 2026-10-29 rule-assignment session (evidence in ADR #226):** Room v21
(`assigned_pair_id` + `backup_pair_id` + `streak_pair_id`, journal `route_pair_id`/`rule_id`/
`rule_change`, receiver-local `partner_rule_change` table, additive up + rebuild down),
`RulesRepository` transactional journaling, rule-scoped reconcile delivery, the editor
partner picker and rule-row label, the partner rule-change feed row, and the pre-1970
clock/journal fix found by the stress suite.

**Delivery-mechanics fix found by the owner-requested stress matrix (ADR #227):** delivered
rule rows are deleted once their envelope is on the chain, so `dueRuleChangesForPair`'s LIMIT
window advances past row 100; `dueForDelivery` selects `route_pair_id IS NULL` so a rule
backlog can never occupy the monitoring queue (and vice versa). Seven new coordinator tests
prove the batch ceiling (205 rows → 100/100/5/0), route isolation, dedupe, cold-window retry,
pairing-window bound and queue starvation. The physical X6532 leg ran the v21 APK over the live
database (four rules intact, six enrollments in the picker, assignment persisted across cold
start); a sealed rule envelope on device still awaits a sync pass with resolved Firebase
identity, and the partner-receipt half needs a second attesting device.

**Shipped in the 2026-10-31 batch/identity/delivery session (evidence in ADRs #249/#250):**
schema v23 (`batch_id` + `batch_name` denormalized on `restriction_rules`; additive `MIGRATION_22_23`
and rollback rebuild), `RulesRepository.insertBatch`/`setBatchEnabled`/`removeBatchPartner`/
`deleteBatch` with one batch-level journal row per assigned pair per verb and per-pair member
counts, whole-vs-partial expiry journaling, wire v2 `RuleIdentity` (sanitized ≤64-char label,
`APP`/`WEBSITE`/`BATCH`, `itemCount` for batches, `batchLabel` on members) with v1 rows still
accepted, the recipient feed/notification/Rules page naming the target and batch, batch-named
blocked-attempt text, and the delivery-guarantee surface (journal-count "waiting to be sent"
banner on the Rules page, `forcePrompt` signing-heal retry, DEBUG-only receive breadcrumb at
`files/rule-delivery.log`). Proof: `BatchRuleLifecycleTest` 7/7, `RuleDeliveryTraceTest` 3/3 and
the wire/receiver/notification suites in the full JVM gate; the two-physical-device receive leg
(X6532 ↔ X6532C) is scheduled under #74/#76 and its evidence is added to the progress note when
run - it closes the "second attesting device" gap D.4 previously noted only on that evidence.
Req 4/5 (storm coalescing), req 14/15 (backup delivery), req 17
(target removal) and req 18 (daily streak summary) remain the open scope.

**Delivery guarantee (owner report 2026-10-31, ADR #250).** The Rules page now reads the journal
itself: `observePendingRuleChanges(livePairIds)` counts rule rows still awaiting chain creation,
so a cold signing window is visible and "Send now" runs the authenticated heal with
`forcePrompt = true`; the banner clears only when the delivered row is actually deleted
(ADR #227). The DEBUG receive trace (`received`/`stored`/`notify` stages) exists solely to prove
the partner half on a device and is a no-op in release.

**On-phone outcome 2026-10-31 (honest status, not a pass).** The first batch flow run on the
X6532 (15:15–15:30) crashed the app whenever the batch picker opened:
`SnapshotApplyConflictException` from the background app-label publish (three fatal exceptions
15:18–15:24, then Android killed the process for repeated crashes). Fixed under ADR #251 —
`publishLabel` retries a losing snapshot apply per Compose's documented contract — and proven by
`AppLabelsTest` 7/7 including a new churn regression. The two-physical-device receive legs under
#74/#76 did **not** run: the second device was returned to its owner, then the first left with
the owner. They remain blocked-on-hardware, the fixed build has not yet been installed, and the
fixed picker flow must be re-run on the X6532 before any device leg here is called passed.

**AVD follow-up 2026-10-31 (fixed build, MyExodus_API37).** With both handsets away, the fixed
APK ran on the sanctioned API-37 AVD: the batch app-picker opened 4× cold-process + 1× warm with
zero crashes and zero FATAL entries (the phone crash does not reproduce post-fix); the full batch
lifecycle ran end-to-end — named "QA temp batch", 2 apps (Chrome, Calendar), create → Rules page
lists the batch with its 2 member rows and "Blocked now", delete → dialog names the batch and its
item count, confirm → empty baseline restored (no residue). The local test alert on the real
`partner_accountability_events_v1` channel produced an on-screen heads-up and deep-linked to the
Partner tab. A full-page text-truncation sweep (Home, Rules, Recovery and subpages, Streaks and
subpages, Partner and both ceremony entries, Settings and subpages, batch editor, picker) found no
clipped or ellipsized app text. What the AVD still cannot exercise: any partner-visible leg
(attestation wall — an emulator can never pair), so #74/#76's receive halves stay blocked on the
handsets.
