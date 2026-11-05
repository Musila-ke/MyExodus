# accountability-partner-protocol-implementation-plan.md

**Status:** ✅ **BUILT — this is the spec of record for ADR #18 (phases 1–6, implemented 2026-08; see `app/docs/MyExodus Build & Dependencies.md`). Do not build from this document.** It covers the **crypto foundation only** — identity/agreement keys, ECDH + CEK derivation, `EventCipher`, the hash chain, and the pairing ceremony. The **transport/sync/UI layer built on top of it** (Firestore mailbox relay, Cloud Functions, push, approval and unpair protocols, partner dashboard) is specified separately in `partner-pillar-implementation-plan-v2.md`, which is also BUILT. §17.1 carries the device-verification record (X6532 pairings 2026-09-16; ADR #66 emulator boundary 2026-09-20). §16's two open questions are live product questions, not build gaps — attestation policy (StrongBox vs TEE fallback, ADR #66 §4) and legal deletion semantics.

## 1. Feature Overview
The Accountability-Partner Protocol enables two paired Android users to exchange cryptographically authenticated, hash-chained, and end-to-end encrypted (E2EE) activity log entries. Entries are signed by hardware-backed keys and delivered via an untrusted cloud relay (Firestore) or an offline animated QR channel. The protocol guarantees authenticity, continuity (fork/rollback detection), and confidentiality against the relay and forensic storage extraction.

## 2. Requirements
### Functional
*   **FR1:** Generate and manage two hardware-backed Android Keystore P-256 keys per device: an Identity Signing Key (ECDSA) and a Key Agreement Key (ECDH).
*   **FR2:** Enroll with a partner using a transcript-bound Short Authentication String (SAS) to prevent MITM attacks.
*   **FR3:** Create, sign, encrypt, and chain events atomically in local SQLite.
*   **FR4:** Verify partner events for signature validity, chain continuity, and sequence monotonicity.
*   **FR5:** Support offline sync via animated QR codes (camera-to-camera).
*   **FR6:** Execute Bilateral Crypto-Shredding upon relationship deletion, destroying the hardware-backed ECDH key to render historical ciphertext permanently unrecoverable.

### Non-Functional
*   **NFR1:** All private keys must be non-extractable and hardware-backed (StrongBox preferred, TEE fallback).
*   **NFR2:** The cloud relay is untrusted for confidentiality, integrity, and availability.
*   **NFR3:** Cryptographic operations must execute on background threads; UI thread blocking is forbidden.
*   **NFR4:** Biometric authentication must utilize a 900-second timeout, with app-level polling to enforce invalidation on biometric enrollment changes.

## 3. Existing System Context
*   Replaces the dormant `StrictModeManager` stub entirely.
*   No migration of legacy data is required.
*   Isolated subsystem: new SQLite DB, new Firestore collections, new Android Keystore aliases.

## 4. Architecture
```text
┌─────────────────────────────────────────────────────────┐
│                  Android Client App                     │
│  ┌────────────────┐  ┌──────────────┐  ┌─────────────┐  │
│  │ Android Keystore│  │ State Manager│  │ Sync Engine │  │
│  │ (Sign + Agree) │◄─┤ (SQLite Chain├──┤ (Firestore +│  │
│  │ (AES Derivation│  │  + Outbox)   │  │  CameraX QR)│  │
│  └────────────────┘  └──────────────┘  └──────┬──────┘  │
└───────────────────────────────────────────────┼─────────┘
                                                │
                           ┌────────────────────▼────────────────────┐
                           │         Untrusted Transport Layer       │
                           │  Cloud Firestore (Mailbox) / QR Optics  │
                           └────────────────────┬────────────────────┘
                                                │
┌───────────────────────────────────────────────┼─────────┐
│                     Partner Client            │         │
│  (Mirrors Crypto Engine, State Manager, Sync Engine)    │
└─────────────────────────────────────────────────────────┘
```

## 5. Architecture Decisions
*   **Dual-Key Model:** Separates ECDSA and ECDH to prevent cross-protocol attacks and satisfy strict Android Keystore purpose tags.
*   **900s Biometric Timeout + App-Level Polling:** Balances UX fatigue with security, bypassing the Android Keystore limitation where `setInvalidatedByBiometricEnrollment` is ignored for positive timeouts.
*   **Animated QR (600-byte frames):** Guarantees physical scannability by avoiding moiré interference on mobile OLED screens.
*   **Hardware-Backed ECDH Key Retention:** The CEK is derived in memory via HKDF from the hardware-backed ECDH shared secret and never persisted to disk; destroying the hardware-backed ECDH private key guarantees permanent crypto-shredding without relying on insecure SQLite BLOB deletion.
*   **Firestore Cross-Document Rules:** Satisfies the "rules are not filters" constraint by validating event writes against a canonical `pairs/{pairId}` state document.
*   **Deterministic Nonces:** Eliminates catastrophic AES-GCM nonce reuse risks caused by mobile RNG state loss or process death.
*   **Play Integrity App Check:** Enforces app authenticity at the Firebase service level to mitigate automated relay abuse.

## 6. Data Flow
### 6.1 Enrollment (Transcript-Bound SAS)
1.  Device A and B generate `SigningKey` and `AgreementKey` pairs in Android Keystore.
2.  Both devices construct a sorted transcript: `T = "StrictMode-V1" || pairId || min(PubKeyA, PubKeyB) || max(PubKeyA, PubKeyB)`.
3.  `SAS = Truncate(SHA256(T), 40 bits)` (displayed as 5 decimal digits).
4.  Users compare SAS out-of-band. If matched, both devices sign `T` with their `SigningKey` and exchange signatures.
5.  Upon mutual verification, the pairwise CEK is derived via `HKDF-SHA256(ECDH(AgreeKeyA, AgreeKeyB.PublicKey), "StrictMode-CEK")`.

### 6.2 Event Creation
1.  Payload is NFC-normalized and encoded via RFC 8949 Deterministic CBOR.
2.  `sequence = lastSequence + 1`.
3.  `nonce = HKDF-SHA256(CEK, "nonce" || sequence)[0..12]`.
4.  `ciphertext = AES-GCM-Encrypt(CEK, nonce, payload)`.
5.  Envelope is constructed.
6.  `eventHash = SHA256("StrictMode-V1" || canonicalCBOR(unsignedEnvelope))`.
7.  `BiometricPrompt` authenticates the user; `SigningKey` signs `eventHash`.
8.  Event is appended to SQLite `events` table and `chain_heads` atomically.

### 6.3 Offline QR Transport
1.  Envelope bytes are split into 592-byte chunks.
2.  Each chunk is prepended with an 8-byte header: `[sessionId: 4 bytes][chunkCount: 2 bytes][chunkIndex: 2 bytes]`.
3.  Chunks are rendered as QR codes and cycled in an `ImageView` at 10 FPS.
4.  Receiver uses CameraX `ImageAnalysis` to decode frames, reassembling the payload in a memory buffer until all unique indices are received.

## 7. Implementation Sequence
1.  **Crypto Engine:** Implement dual-key generation, StrongBox/TEE fallback, and Android Key Attestation verification.
2.  **Serialization:** Implement RFC 8949 CBOR with strict NFC normalization and deterministic map-key sorting.
3.  **State Manager:** Implement SQLite schema, hash-chain validation, and transactional outbox.
4.  **Enrollment:** Implement transcript-bound SAS and mutual signature verification.
5.  **Sync Engines:** Implement Firestore listeners (with cross-document rules) and CameraX animated QR chunking/reassembly.
6.  **Lifecycle:** Implement key rotation, revocation, and bilateral crypto-shredding.

## 8. Detailed Implementation Steps

### 8.1 Key Generation & Storage (Android Keystore)
Generate two distinct keys using `KeyGenParameterSpec.Builder`.

**Identity Signing Key:**
```kotlin
val signSpec = KeyGenParameterSpec.Builder(
    "accountability_signing_key",
    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
)
    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
    .setDigests(KeyProperties.DIGEST_SHA256)
    .setIsStrongBoxBacked(true) // Catch StrongBoxUnavailableException and fallback to false
    .setUserAuthenticationRequired(true)
    .setUserAuthenticationParameters(900, KeyProperties.AUTH_BIOMETRIC_STRONG)
    .setInvalidatedByBiometricEnrollment(false) // Ignored by OS when timeout > 0, handled via app polling
    .setAttestationChallenge(challengeBytes)
    .build()
```

**Key Agreement Key:**
```kotlin
val agreeSpec = KeyGenParameterSpec.Builder(
    "accountability_agreement_key",
    KeyProperties.PURPOSE_AGREE_KEY
)
    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
    .setIsStrongBoxBacked(true)
    .setUserAuthenticationRequired(false) // ECDH derivation must not prompt user
    .build()
```

### 8.2 Biometric Invalidation Polling
Because Android ignores `setInvalidatedByBiometricEnrollment` when a positive timeout is set, the app must check for biometric enrollment changes at startup and on `ON_RESUME`. **[Implemented 2026-09-28, task #68 — ADR #113.** `BiometricEnrollmentMonitor` polls at startup and ON_RESUME; `MainActivity` runs `IdentityMigration` on change and shows the identity-reset dialog. Recorded deviations: the cache is plain `SharedPreferences`, not `EncryptedSharedPreferences` (the cached value is public system state — `canAuthenticate` status + `isDeviceSecure` — and Decision 12 dropped `security-crypto`); the cached value doubles as a baseline (first run is never a change, so an upgrade cannot rotate an existing identity) and detection never writes — the commit happens only after a successful migration, so a failure retries. `BiometricManager.from()` is also adapted to `getSystemService(BIOMETRIC_SERVICE)` (the static factory is trimmed from the android-37 stubs — ADR #17).]**
```kotlin
val biometricManager = BiometricManager.from(context)
val currentEnrollmentState = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
// Compare against cached state in EncryptedSharedPreferences. 
// If changed, trigger Identity Migration (Key Rotation) flow immediately.
```

### 8.3 SQLite Schema (Room or SQLiteOpenHelper)
Enable Write-Ahead Logging (WAL) for concurrency.
```sql
CREATE TABLE events (
  id INTEGER PRIMARY KEY, pair_id TEXT, sender_key_id TEXT, epoch INTEGER,
  sequence INTEGER, previous_event_hash BLOB, event_hash BLOB, envelope BLOB,
  direction TEXT, created_at INTEGER, sync_state TEXT DEFAULT 'PENDING'
);
CREATE TABLE chain_heads (
  pair_id TEXT, sender_key_id TEXT, last_sequence INTEGER, last_event_hash BLOB,
  PRIMARY KEY (pair_id, sender_key_id)
);
```
*Append and head-update must occur in a single `db.beginTransaction()` / `db.setTransactionSuccessful()` block.*

### 8.4 Firestore Schema & Rules
Collections: `pairs/{pairId}` (State) and `accountability_events/{eventId}` (Mailbox).
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /pairs/{pairId} {
      allow read, write: if request.auth.uid in resource.data.members;
    }
    match /accountability_events/{eventId} {
      allow create: if request.auth.uid == request.resource.data.authorUid
        && exists(/databases/$(database)/documents/pairs/$(request.resource.data.pairId))
        && request.resource.data.envelope.size() < 1048576; 
      allow read: if request.auth.uid == resource.data.partnerUid
        || request.auth.uid == resource.data.authorUid;
      allow update, delete: if false;
    }
  }
}
```

### 8.5 Firebase App Check
Initialize App Check using the Play Integrity API provider before any Firestore operations.
```kotlin
FirebaseAppCheck.installAppCheckProviderFactory(
    PlayIntegrityAppCheckProviderFactory.getInstance()
)
```

## 9. Error Handling
| Condition | Handling |
| :--- | :--- |
| `StrongBoxUnavailableException` | Fallback to TEE (`setIsStrongBoxBacked(false)`). Log telemetry. |
| `KeyPermanentlyInvalidatedException` | Trigger Identity Migration flow immediately. **▶ Shipped 2026-09-28, task #68 — ADR #113: wired at sign time (`initSign` pre-prompt, post-auth, and the screen-lock path) in `MainActivity`; the dead key routes to migration + the identity-reset dialog instead of reading as a cancel. Tier-1 note: this exception is real for the signing key (auth-required keys are irreversibly invalidated when the secure lock screen is removed/reset — android-36 `KeyGenParameterSpec` javadoc), unlike enrolment-change invalidation, which the OS cannot raise for time-bound keys.** |
| `UserNotAuthenticatedException` | Trigger `BiometricPrompt` with `CryptoObject` wrapping the `Signature` instance. |
| Sequence Gap | Hold event in `PENDING_GAP` state. Request backfill via reverse Firestore channel. Terminal state: `PERMANENTLY_INCOMPLETE` after 7 days. |
| Fork Detected | Reject event, flag `senderKeyId` as `FORKED`. Block further chain advancement. |
| Firestore `PERMISSION_DENIED` | Fail immediately. Do not retry authorization errors. |

## 10. Security Requirements
*   **Domain Separation:** All signatures MUST include the domain string `"StrictMode-Accountability-V1"` in the hashed input.
*   **Low-S Enforcement:** Verifiers MUST parse ECDSA signatures and reject if `S > N/2`.
*   **Android Key Attestation:** The partner MUST verify the attestation certificate chain. The `KeyDescription` extension must confirm `origin` is `GENERATED`, `purpose` includes `SIGN`, and `securityLevel` is `StrongBox` or `TrustedEnvironment`.
*   **Threat Model Boundary:** The protocol guarantees authenticity and continuity against a malicious relay. It **does not** guarantee confidentiality against a fully compromised endpoint OS (which can observe plaintext in memory).
*   **Bilateral Deletion:** Destroying the local ECDH key is insufficient. A `DELETION_REQUEST` event must be signed and transmitted. Upon receipt, the partner's app MUST securely delete its hardware-backed Key Agreement key to achieve true crypto-shredding.

## 11. Performance Requirements
*   **Background Execution:** All Keystore operations and CBOR encoding must run on a dedicated `Dispatchers.IO` or `Executors.newSingleThreadExecutor()`.
*   **Latency Budgets:** StrongBox/TEE signing ≤ 150ms p95.
*   **QR Scanning:** CameraX `ImageAnalysis` must be configured with `STRATEGY_KEEP_ONLY_LATEST` and a resolution of 640x480 to maximize frame rate. Animated QR reassembly must tolerate 30% frame loss without requiring a full restart.

## 12. Testing Requirements
*   **Cross-Platform Vectors:** CI must execute RFC 8949 CBOR encoding and ECDSA signing on Android and a reference server implementation, asserting byte-for-byte equality of the signed payloads.
*   **Fuzzing:** Coverage-guided fuzzing of the CBOR parser and QR chunk reassembler to prevent resource exhaustion attacks.
*   **State Machine Fault Injection:** Simulator must inject database restores, key rotations, and network partitions to verify fork detection and gap handling terminal states.
*   **Hardware Matrix:** Test Key Attestation and StrongBox fallback on a minimum of 5 distinct OEM devices (Samsung, Pixel, Xiaomi, OnePlus, Motorola).

## 13. Deployment Requirements
*   **Phase 1 (Telemetry):** Ship Crypto Engine behind a remote config flag. Measure hardware attestation success rates and biometric prompt frequencies.
*   **Phase 2 (Pilot):** Enable for closed beta. Validate animated QR scan rates in low-light conditions.
*   **Phase 3 (GA):** Remove `StrictModeManager`. Enforce App Check at the service level.

## 14. Monitoring Requirements
*   `crypto_key_generation_result` (tagged by `signing` vs `agreement`, `strongbox` vs `tee`).
*   `qr_reassembly_completion_time_ms` and `qr_frame_loss_rate`.
*   `hash_chain_validation_failure` (tagged by `fork`, `gap`, `signature_invalid`).
*   `biometric_enrollment_change_detected` (count). **[Deliberately not shipped — ADR #113 (task #68): no consumer in the shipped telemetry surface; recorded rather than silently dropped.]**
*   `bilateral_deletion_executed` (count only).

## 15. Risk Register
| Risk | Likelihood | Impact | Mitigation |
| :--- | :--- | :--- | :--- |
| **OEM Keystore Bugs** | Medium | High | Catch all `KeyStoreException` variants; fallback to TEE or fail gracefully with UI prompts. |
| **QR Scan Failure (Moiré)** | Medium | High | Enforce 600-byte frame limit; use high-contrast QR rendering; optimize CameraX exposure. |
| **Unilateral Deletion Misunderstanding** | High | High | Explicit UI warnings that local deletion does not erase partner's history unless bilateral protocol completes. |
| **Firestore Query Failure** | Medium | High | Strict adherence to "rules are not filters" via `pairs/{pairId}` cross-document validation. |

## 16. Open Questions
1.  **Legal Deletion Semantics:** Does the target market's privacy regulation require the partner to be *forced* to delete their copy, or is unilateral local deletion sufficient for compliance?
2.  **Attestation Enforcement:** Should the partner reject enrollment if the Android device fails hardware attestation (e.g., falls back to TEE instead of StrongBox)?

## 17. Acceptance Criteria
*   Two Android devices successfully enroll using a matching 5-digit transcript-bound SAS.
*   Events are exchanged online and offline (animated QR), with payloads unreadable to the Firestore relay.
*   Attempting to replay an old event or fork the chain triggers a `FORK_DETECTED` UI state.
*   Executing a relationship deletion securely destroys the hardware-backed Key Agreement keys on **both** devices, rendering historical ciphertext permanently undecryptable.
*   Firestore security rules pass the emulator suite, specifically validating cross-document pair membership.
*   Biometric prompts occur no more than once per 900-second window during continuous use, and key invalidation triggers correctly when a new fingerprint is added to the device.

### 17.1 Device verification status (2026-09-16)

**AC#1 (enrollment, matching SAS) — PASS at protocol level on the X6532**, with one hardware caveat. Three complete pairings (real Firestore relay, two anonymous uids, roles driven by `PairingTestActivity` — the manual harness was removed 2026-11-05): SAS **85108** / **67329** / **79827**, each matching on both roles with both `Paired` outcomes. Caveat: the emulator cannot be the second device — `AttestationVerifier` PKIX-pins the Google Hardware Attestation roots and rejects `SecurityLevel.SOFTWARE` (`AttestationVerifier.kt:37`), so emulator software keys can never pass verification (attempts ended `TokenExpired` / `rejected or timed out`). The run therefore used both roles on one device, which means both roles share the device-level signing/agreement keys: mutual verification, transcript signatures in both directions, redemption, and SAS derivation are fully exercised, but **cross-device key distinctness is not** (requires a second hardware-attesting device).

**AC#1 cross-device closure (2026-10-01, first production ceremony between two physical devices).** The caveat is closed. Two physical Infinix handsets (X6532 = initiator, X6532C = partner) ran the shipped ceremony end-to-end with no harnesses — typed Crockford code `9HBJ-TJYV-DDNM-T`, SAS **29244** observed identical on both screens, each device approving with its own key (X6532C via the prompt's "Use screen lock" handoff — first production use of the ADR #112 amendment). The registered pair doc (`/pairings/mXyGJujNZrdkx4rKxs2VzuEd1Af1_xutCN4Br7sY6Ryd19snrqOoQqeX2`, `status=active`, `createdAt=2026-10-01T07:23:27Z`) carries both proofs over the **same transcript hash** (`02e5f236…b1db`) with **different hardware-backed public-key hashes** — `bb5de7…ca23` initiator / `a010b1…c9d3` partner, client-stamped 25.7 s apart. Both proofs passed the server's PKIX-pinned hardware-attestation check (the pair is live). Cross-device key distinctness is now exercised directly, not inferred. Run details in the pairing plan §12.3 status block.

**AC#1 emulator confirmation (2026-09-20, ADR #66).** The caveat above is now proven directly rather than inferred. Two emulators (`emulator-5554` as INITIATOR, `emulator-5556` as PARTNER; distinct anonymous uids, session `dbcdbd726c4f4146`) ran the full ceremony to the verification step — token generated, QR exchanged, the partner's biometric satisfied (`accept=1`), transcript signed — and the responder rejected the initiator's payload with `attestation: key security level is SOFTWARE`, the initiator reporting `partner material rejected or timed out — pair aborted`. Note the failure is exactly at the two policy checks: `PairingFlow.ownPayload` **succeeded** and logged `QR_TEXT`, so the emulator's software keymaster does emit a well-formed `KeyDescription` with an embedded challenge; it is the security-level value and the PKIX root that reject. No debug waiver was added — see ADR #66 §4 for why that was considered and refused (attestation policy is Open Question §16 item 2). **This is a design boundary, not setup drift: do not re-open it as a rig defect.**

**AC#4 (revocation + bilateral key destruction) — PASS for the initiating device, server side confirmed.** Harness outcome `Deleted` (pair `52684df48c97e727`): deletion event chained → outbox drained → `destroyAgreementKey()` → pair data wiped → tombstone → pair-doc delete. Server-side: `pairs/52684df48c97e727` REST-reads **404**, and the tombstone is proven present via a create-probe (re-creating the revoked pairId → **403**, identical payload on a bogus pairId → **200** control): the revocation-reopen gap is closed. The receiver half (`handleIncoming`) is unit-tested (`BilateralDeletionTest`); destruction is device-level (one agreement key served both roles in this run), so the "both devices" clause is covered by the unit tests + the design, not by a second physical device.

Residual debris (harmless, member-gated rules prevent cleanup without the original anonymous sessions): pairs `550496d7655cddc6` and `e1b6226a3487b6c4` remain live server-side; expired token `1d652a14d0d04d16` created no pair.

## 18. Definition of Done
*   All dual-key crypto engine implementations are complete and hardware-attested.
*   Animated QR reassembly is functional and tested with 30% simulated frame loss.
*   Firestore rules are deployed and verified against the Local Emulator Suite.
*   Bilateral crypto-shredding is verified via forensic memory/storage inspection (keys are unrecoverable).
*   App Check is enforced via Play Integrity in the Firebase Console.
*   All Open Questions are resolved with Product/Legal sign-off.