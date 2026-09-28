# Accountability Partner Device Pairing — Implementation Specification (v2)

**Project:** ũtethyo (MyExodus)
**Scope:** V1, Android only (Kotlin)
**Status:** ⛔ **SUPERSEDED — NOT ADOPTED. Do not implement this document.** Audit ruling 2026-09-22 (task #10); full reasoning in `Obsidian/MyExodus/Open Product Decisions.md` item 24. The shipped, device-verified pairing (ADR #55) stands unchanged.

Why, in four points:
1. **Written without knowledge of this codebase.** §3 assumes a relational DB, REST endpoints and an existing auth service; the stack is Firestore + Cloud Functions + anonymous Firebase auth. It shares *zero* vocabulary with the shipped code — `pairId`, CEK, StrictMode, hash-chain/chain head, Envelope, CBOR, mailbox, Firestore, Cloud Function all appear **zero times**. Step 12's "adversarial and device-matrix testing" (§12) describes tests against endpoints that do not exist here.
2. **Its central demand reverses an explicit owner ruling.** AD-01/AD-04/NFR-2 ("no hardware attestation", "no device-integrity gating", "pairing completes without excluding rooted/custom-ROM/non-GMS devices") contradicts ADR #57: App Check is **ENFORCED** project-wide on Firestore, a per-service binary switch with **no per-device exemption mechanism**. The no-GMS rejection is a recorded accepted trade-off, and AD-02's pairing gate is R-02's stated compensating control. This document's requirement was not an oversight in the shipped design — it was a deliberate decision.
3. **Relaxing the client-side gate would achieve nothing.** With the `SOFTWARE` rejection removed, the device still cannot write to Firestore — App Check rejects it server-side with `PERMISSION_DENIED`. The change would convert an honest, diagnosed rejection into an opaque backend error, and would not satisfy this document's own NFR-2.
4. **The case it appears to unblock is already handled.** ADR #66 documents the emulator's `attestation: key security level is SOFTWARE` rejection *and* its workaround — the ADR #46 virtual-partner harness plus ADR #57's registered debug-token path. The two-device rig is usable for every leg that does not require a pair.

If non-GMS pairing is ever genuinely wanted, it is a **backend** decision (relax App Check enforcement, or build a second acceptance rail) requiring its own ruling and a re-planned R-02 mitigation — not a client change.

5. **Requirement-level re-audit, 2026-09-27 (task #12 — the owner challenged the over-broad supersession; this closed it):** every FR-1…7 and NFR-1…4 was checked against shipped code. FR-4–7 and NFR-1/3 ship under the pillar's own design (rejection/timeout invalidation; AES-GCM relay wired in production; biometric unpair + bilateral shred; the wipe-detected repair path; ciphertext-only relay docs). FR-3's "4-word SAS" ships as a **5-digit numeric SAS** (`EnrollmentTranscript.sas`, same transcript binding) — differently by design, per the pillar's own protocol plan §6.1. NFR-2 is the backend ruling above; NFR-4's client half (data minimization) is shipped, retention/consent remains the legal track. **The one genuinely missing client deliverable is FR-1/2/3's UI surface — the production pairing-ceremony screen** (QR render/scan + SAS display and confirm): `AnimatedQrRenderer` and `QrCameraDecoder` sit in `main` with zero production referrers, and `PairingFlow`/`EnrollmentFlow` are consumed only by DI, androidTest and the debug `PairingTestActivity`. Recorded in `Obsidian/MyExodus/Open Product Decisions.md` item 25; tracked by task #16.

---

## 1. Feature Overview

Two users — an app user and their one human accountability partner — establish a trusted, authenticated pairing between their two Android devices by scanning each other's QR codes inside the app. Pairing produces:

1. A verified, MITM-resistant shared identity between the two devices, confirmed by both users visually comparing a 4-word code (Short Authentication String, "SAS") before the pairing is accepted.
2. A pair of persistent, direction-specific symmetric keys used to end-to-end encrypt all subsequent accountability messages sent between the two devices through the existing backend relay, so the backend never sees plaintext message content.

**What this feature is not:** it is not a hardware-attestation or device-integrity gate. No device is rejected, blocked, or flagged for being rooted, running a custom ROM, or lacking Google Play Services. It also does not provide forward secrecy or post-compromise recovery for messages (that is a V2 concern, see AD-08) — it provides confidentiality and authenticity against network interception and a compromised/malicious backend, and integrity against message forgery, replay, and cross-context reuse.

**V1 supports exactly one active accountability partnership per account.** Multiple simultaneous partners are out of scope for this version.

---

## 2. Requirements

### Functional
- FR-1: A user with no active partnership can generate a pairing QR code.
- FR-2: A user can scan a partner's QR code to initiate a join.
- FR-3: Both devices display a 4-word SAS after key exchange; pairing only completes after both users explicitly confirm the SAS matches.
- FR-4: On mismatch, explicit rejection, or timeout, the session is invalidated and no partnership is created.
- FR-5: Once paired, both devices send and receive encrypted accountability messages via the existing message relay.
- FR-6: Either user can unpair. Unpairing requires the app to first obtain a device unlock/biometric confirmation from the OS, then revokes the partnership server-side immediately and deletes local key material on the initiating device immediately; the peer device deletes its local key material the next time it syncs (see §8, Step 9 and AD-06).
- FR-7: If a partner's device reports (on sync) that it no longer holds the expected identity key, or stops reporting entirely, the partnership transitions to `REPAIR_REQUIRED` and the surviving user is notified that their partner needs to re-pair (see §8, Step 10).

### Non-Functional
- NFR-1: No plaintext accountability message content is ever stored or readable by the backend, and never appears in push-notification payloads.
- NFR-2: Pairing completes without excluding rooted, custom-ROM, or non-GMS devices.
- NFR-3: Buildable and maintainable by a single developer within the project's existing V1 timeline and budget; no new paid infrastructure, no third-party paid SDKs.
- NFR-4: Designed to support compliance with Kenya's Data Protection Act No. 24 of 2019 (data minimization, explicit consent, defined retention). Full compliance sign-off is a separate legal/DPIA task (see §16, OQ-5).

### Constraints
- Android only, Kotlin. Code paths are specified for both API < 30 and API ≥ 30 wherever a version-gated Keystore API is used, so this spec does not require knowing the exact `minSdkVersion` to be implementable (see §16, OQ-1 for why the exact value still matters operationally).
- Uses the existing backend API and existing push-notification path (assumed FCM); does not introduce a new backend service.

---

## 3. Existing System Context

**Assumed present and unchanged:**
- Authenticated user account system (this spec's endpoints all require an already-logged-in account; this spec does not implement authentication).
- Existing backend API and persistence layer (assumed relational DB).
- Existing push-notification path (assumed FCM), used here only to deliver generic "you have a new event" pings — never message content.

**New components:**
- Backend: `pairing_sessions` table + state machine, `partnerships` table, `messages` table (or extension of an existing message table to carry opaque ciphertext blobs), idempotency-key enforcement on all mutating endpoints.
- Android: Keystore identity-key management, QR generation/scanning UI, local cryptography (ECDH, HKDF, AES-GCM), session polling, backup-exclusion rules, Keystore-backed local storage encryption (not `androidx.security.crypto`, which is deprecated — see AD-11).

**Explicitly out of scope / not touched:** existing authentication, any distraction-blocking functionality, any existing plaintext messaging unrelated to accountability check-ins.

---

## 4. Architecture

```
┌───────────────────────────┐                              ┌───────────────────────────┐
│        Device A (role=A)  │                              │        Device B (role=B)  │
│                           │                              │                           │
│  Identity Key (Keystore,  │                              │  Identity Key (Keystore,  │
│  hardware-backed if       │                              │  hardware-backed if       │
│  available, EC P-256,     │                              │  available, EC P-256,     │
│  requires device unlock   │                              │  requires device unlock   │
│  to sign, survives        │                              │  to sign, survives        │
│  biometric re-enrollment) │                              │  biometric re-enrollment) │
│                           │                              │                           │
│  Ephemeral ECDH key pair  │                              │  Ephemeral ECDH key pair  │
│  (software, P-256,        │                              │  (software, P-256,        │
│  session-only, discarded  │                              │  session-only, discarded  │
│  after use)               │                              │  after use)               │
│                           │                              │                           │
│  send_key_A_to_B (derived)│                              │  send_key_A_to_B (derived)│
│  send_key_B_to_A (derived)│                              │  send_key_B_to_A (derived)│
│  own send_seq (persisted) │                              │  own send_seq (persisted) │
│  peer seen-seq set        │                              │  peer seen-seq set        │
└─────────────┬─────────────┘                              └─────────────┬─────────────┘
              │                                                          │
              │            HTTPS (existing backend API)                 │
              ▼                                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              Backend (existing service)                              │
│                                                                                       │
│  Pairing session state machine + idempotency enforcement (rendezvous only —          │
│  never computes or sees the SAS or any message/directional key)                      │
│  Partnership record: pinned identity public keys (TOFU), key_epoch, status           │
│  Message relay: authorization by partnership membership, opaque ciphertext blobs      │
│  Continuity check: compares each synced device's reported identity key to the pin     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Trust boundary:** the backend is trusted for rendezvous, session/partnership bookkeeping, authorization (which accounts may act on which partnership), rate limiting, and encrypted-message relay. It is **not** trusted with message confidentiality or with determining pairing validity: it cannot forge a valid transcript signature under either identity key, and it never possesses the ECDH shared secret or any key derived from it. A compromised backend can deny service, delay, reorder, or drop messages/session state, and can see communication metadata (who is paired with whom, how often they message, message sizes) — it cannot read message content, cannot forge a message that decrypts successfully under a direction it doesn't hold the key for, and cannot force two devices to accept a pairing with an attacker.

**Cryptographic roles (kept strictly separate):**
1. **Identity key (Keystore, long-term, per role):** signs the pairing transcript once at pairing time (and again only during an explicit key-change re-confirmation, §8 Step 10). TOFU-pinned server-side per partnership.
2. **Ephemeral ECDH key (software, per-session):** produces the shared secret. Discarded after use.
3. **SAS:** derived from the ECDH shared secret, computed independently by each device, confirmed by humans.
4. **Directional message keys (`send_key_A_to_B`, `send_key_B_to_A`):** both HKDF-derived from the same shared secret with distinct labels; each key is used by exactly one sender, eliminating any nonce-collision risk between senders.

---

## 5. Architecture Decisions

- **AD-01 — No hardware attestation in V1.** Deferred past V1; re-evaluate only against real abuse data.
- **AD-02 — SAS is computed independently by each client from the ECDH shared secret, never by or through the server.**
- **AD-03 — Ephemeral ECDH keys are software keys, generated fresh per session, discarded after use.** Avoids the API 31 floor for Keystore `PURPOSE_AGREE_KEY`.
- **AD-04 — No device-integrity gating.** Root/custom-ROM/non-GMS devices pair normally. V1 does not claim resistance to a compromised endpoint (the user's own device being compromised is out of scope; see §10).
- **AD-05 — Identity keys use Trust-On-First-Use (TOFU), pinned per partnership at first successful pairing.** A later change in either party's reported identity key never silently overwrites the pin — it triggers an explicit re-confirmation flow (§8, Step 10), never automatic trust.
- **AD-06 — The identity key requires device unlock to sign (`setUserAuthenticationRequired(true)`) but survives biometric re-enrollment (`setInvalidatedByBiometricEnrollment(false)`).** One key, not two: there is no separate "unpair key." The unpair action itself is gated by an explicit `BiometricPrompt`/device-credential authorization step in app logic before the app calls Keystore key deletion — this needs no dedicated cryptographic key and works identically across all supported API levels.
- **AD-07 — StrongBox is attempted opportunistically per key-generation call. Only `StrongBoxUnavailableException` triggers fallback to a non-StrongBox spec; other `ProviderException` subtypes (e.g. `BackendBusyException`) are surfaced as retryable transient errors, not treated as StrongBox-unavailable.** A single fallback event is logged, not treated as a permanent per-device blacklist.
- **AD-08 — Post-pairing messaging uses two HKDF-derived directional keys (one per sender), not one shared key, and not a full Double Ratchet.** Directional keys eliminate the nonce-collision risk of two independent senders sharing one key; a full ratchet's forward-secrecy/complexity trade-off is not justified for low-frequency accountability check-ins in V1. **This is an explicit, documented limitation:** V1 provides confidentiality and integrity against network/backend interception, but not forward secrecy or post-compromise recovery. Re-evaluate in V2 (§16, OQ-4).
- **AD-09 — Nonces for AES-GCM are deterministic, not random: each message's 96-bit nonce is the sender's own strictly-increasing `send_seq`, zero-padded.** Because each directional key has exactly one sender and that sender's `send_seq` is persisted and never reused (even across app restarts — see §8, Step 8), nonce uniqueness is guaranteed by construction, not probability.
- **AD-10 — Replay/reordering is handled via a bounded seen-sequence set per direction (last 500 sequence numbers), not a strict "must be greater than last accepted" rule.** Any sequence number not already in the set is accepted (handles reordering); anything already seen is rejected (prevents replay). This is intentionally simpler than a full sliding-window bitmap, which is not justified at this app's message volume.
- **AD-11 — Local persistence of key-derived material uses a Keystore-backed AES-GCM encryption key applied directly via the JCA `Cipher` API, stored in the app's existing database/file storage — not `androidx.security.crypto.EncryptedSharedPreferences`/`MasterKey`, which are deprecated.**
- **AD-12 — Unpairing is epoch-based, not instantaneously bidirectional.** Server-side revocation (no new messages accepted for the partnership) is immediate. The initiating device deletes its local key material immediately. The peer device deletes its local key material asynchronously, the next time it syncs and observes the revoked status — this is stated explicitly as the accurate behavior, not "immediate on both sides."
- **AD-13 — Each QR code independently carries its own sender's full contribution** (`protocol_version`, `session_id`, `role`, `identity_pubkey`, `ephemeral_pubkey`, `nonce`) rather than one side relying on server-delivered key material for the other party. The backend still relays the same values for session bookkeeping and polling redundancy, but each device's transcript is built from what it itself scanned, and cross-verification of the peer's signature over that same transcript is what catches any discrepancy — not server authority over "the real" key material.
- **AD-14 — All EC public keys are serialized as X9.62 uncompressed format (65 bytes: `0x04` || 32-byte big-endian X || 32-byte big-endian Y, left-zero-padded), on both platforms of the transcript, with no exceptions.**
- **AD-15 — All mutating endpoints (session create/join/attest/confirm, message send, unpair) require a client-generated 128-bit idempotency key.** The server enforces uniqueness per `(account_id, idempotency_key)` and returns the original result on retry rather than creating new state.
- **AD-16 — One active partnership per account; one active (non-terminal) pairing session per account.** Creating a new session while one is already active (`CREATED`/`JOINED`/etc.) auto-cancels the prior one.
- **AD-17 — The identity key alias is scoped per account** (`pairing_identity_key_<account_id>`), not global to the installation, to avoid collision if the app ever supports multiple logged-in accounts on one device.
- **AD-18 — Locally persisted pairing/messaging state (encrypted-blob columns, sequence counters, partnership metadata) is explicitly excluded from Android Auto Backup**, to prevent a restored-but-stale sequence counter or shared-secret-derived value from being reused inconsistently after a backup/restore cycle. Keystore keys themselves are never included in Auto Backup by the OS and require no explicit exclusion.

---

## 6. Data Flow

```
1. Device A: POST /pairing/sessions  { idempotency_key }
     → Server creates session (state=CREATED), returns session_id (128-bit),
       nonce_A (256-bit random). Any other active session for account A is
       auto-cancelled (AD-16).

2. Device A generates its ephemeral P-256 key pair. Device A displays QR_A:
     { protocol_version=1, session_id, role="A", nonce_A,
       identity_pubkey_A (X9.62, 65 bytes), ephemeral_pubkey_A (X9.62, 65 bytes) }
   Device A also POSTs the same identity_pubkey_A / ephemeral_pubkey_A to the
   session record, for bookkeeping and polling redundancy.

3. Device B scans QR_A (validates protocol_version, field presence, field sizes).
   Device B generates its own ephemeral key pair, then:
   POST /pairing/sessions/{session_id}/join
     { idempotency_key, nonce_A (proves the QR was actually scanned — this is a
       one-time redemption token, not proof of the peer's identity; identity
       is established later by signature verification),
       identity_pubkey_B, ephemeral_pubkey_B }
     → Server validates nonce_A matches, transitions CREATED → JOINED
       (atomic conditional update only), generates nonce_B, returns it.

4. Device B displays QR_B:
     { protocol_version=1, session_id, role="B", nonce_B,
       identity_pubkey_B, ephemeral_pubkey_B }

5. Device A scans QR_B (same validation as step 3), then:
     POST /pairing/sessions/{session_id}/confirm-nonce  { idempotency_key, nonce_B }
     → Server validates, transitions JOINED → KEY_EXCHANGED (atomic).

6. Both devices independently build the transcript from what THEY scanned/generated
   (not from server-relayed values):

     transcript = SHA-256(
       uint8(protocol_version=1) ||
       bytes16(session_id) ||
       bytes32(nonce_A) || bytes32(nonce_B) ||
       identity_pubkey_A (65B) || identity_pubkey_B (65B) ||
       ephemeral_pubkey_A (65B) || ephemeral_pubkey_B (65B)
     )
     // Field order is always A-role-fields-then-B-role-fields, regardless of
     // which device is computing it — this is fixed by protocol, not by role.

   Each device signs `transcript` once with its own identity private key
   (caching the signature locally — see §9) and:
     POST /pairing/sessions/{session_id}/attest  { idempotency_key, signature }
     → Server relays each side's signature to the other via polling (clients
       poll GET /pairing/sessions/{session_id} every 2s during KEY_EXCHANGED/
       SAS_PENDING), transitions KEY_EXCHANGED → SAS_PENDING once both
       signatures are recorded.

   Each device verifies the OTHER device's signature against the identity_pubkey
   it itself scanned/received for that role. A mismatch here (whether from an
   attacker or an inconsistent server relay) means the two devices did not agree
   on the same transcript — abort, do not proceed to SAS.

7. Both devices independently derive:
     shared_secret = ECDH(own_ephemeral_priv, peer_ephemeral_pub)
     PRK              = HMAC-SHA256(salt="AccountabilityPairing-v1", IKM=shared_secret)
     SAS              = HKDF-Expand(PRK, info="SAS-Derivation-v1" || transcript, length=4 bytes)
                        → rendered as 4 words from a fixed, versioned word list
     send_key_A_to_B  = HKDF-Expand(PRK, info="MsgKey-v1-A2B" || transcript, length=32 bytes)
     send_key_B_to_A  = HKDF-Expand(PRK, info="MsgKey-v1-B2A" || transcript, length=32 bytes)

   Device A's own outgoing key is send_key_A_to_B; its incoming key is send_key_B_to_A.
   Device B's own outgoing key is send_key_B_to_A; its incoming key is send_key_A_to_B.

   Ephemeral private key material is overwritten in memory immediately after
   `shared_secret` is derived (best-effort; see §9).

8. Both users visually compare the 4-word SAS.
     POST /pairing/sessions/{session_id}/confirm  { idempotency_key, agree: true|false }
     → If both confirm agree=true: server transitions SAS_PENDING → COMPLETED
       inside one transaction that also writes the `partnerships` row
       (account_id_a, account_id_b — canonically ordered, identity_pubkey_a,
       identity_pubkey_b, key_epoch=1, status='active', created_at). The
       session row is deleted (or scheduled for deletion within 24h).
     → If either confirms agree=false, or the 10-minute session TTL expires
       before COMPLETED: state=CANCELLED/EXPIRED. No partnership record is
       created. Session data purged after 24h.

9. Ongoing messaging (§8, Step 8 and §9 below covers full mechanics):
   sender encrypts with its own directional key and a nonce equal to its own
   next `send_seq`; receiver decrypts with the matching directional key and
   accepts/rejects based on the seen-sequence set for that direction.
```

---

## 7. Implementation Sequence

1. **Backend:** `pairing_sessions` table, state machine, idempotency-key enforcement (no client dependency; unit-testable standalone).
2. **Backend:** `partnerships` table (with canonical account-pair ordering and TOFU pin columns), endpoints, one-active-partnership constraint (depends on 1).
3. **Backend:** rate limiting, session-join authorization, one-active-session enforcement (depends on 1).
4. **Android:** Keystore identity key generation (version-gated auth parameters) + StrongBox fallback logic (no backend dependency; unit-testable in isolation).
5. **Android:** X9.62 EC point encoding utility, ephemeral ECDH generation, transcript/SAS/directional-key derivation (depends on 4 for the signing step; crypto math independently testable).
6. **Android:** QR generation/scanning UI with schema/version validation, wired to session endpoints (depends on 1–3, 4–5).
7. **Android:** SAS confirmation UI and pairing-completion flow (depends on 6).
8. **Backend + Android:** message send/receive path using directional keys, deterministic nonces, seen-sequence-set replay protection, message idempotency (depends on 2, 5).
9. **Android:** unpair flow (BiometricPrompt gate + key deletion, no dedicated key) (depends on 4, 7).
10. **Backend + Android:** continuity/key-change/repair-required sync check (depends on 2, 8).
11. **Android:** backup-exclusion rules and Keystore-backed local storage encryption (depends on 4, 8).
12. **End-to-end adversarial and device-matrix testing** (depends on all prior steps).

---

## 8. Detailed Implementation Steps

### Step 4: Android identity key (version-gated)
```kotlin
fun buildIdentityKeySpec(alias: String): KeyGenParameterSpec {
    val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setUserAuthenticationRequired(true)
        .setInvalidatedByBiometricEnrollment(false) // relevant precisely because auth IS required
        .setIsStrongBoxBacked(true) // attempted first; removed entirely on fallback, see below

    if (Build.VERSION.SDK_INT >= 30) {
        builder.setUserAuthenticationParameters(
            0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        )
    } else {
        @Suppress("DEPRECATION")
        builder.setUserAuthenticationValidityDurationSeconds(0)
    }
    return builder.build()
}

fun generateIdentityKey(alias: String) {
    try {
        generateKeyPair(buildIdentityKeySpec(alias))
    } catch (e: StrongBoxUnavailableException) {
        // Only this specific exception triggers fallback (AD-07) — build a
        // fresh spec without setIsStrongBoxBacked(true), do not reuse a
        // "rebuild" helper that doesn't exist in the Android API.
        val fallbackSpec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setUserAuthenticationParameters(
                        0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(0)
                }
            }.build()
        logStrongBoxFallback() // monitoring event, AD-07 — telemetry only, not a security signal
        generateKeyPair(fallbackSpec)
    }
    // Any other ProviderException (e.g. BackendBusyException) propagates as a
    // retryable transient error — it must NOT be caught here as a StrongBox
    // fallback trigger.
}
```
Alias per AD-17: `"pairing_identity_key_${accountId}"`.

Unpairing (FR-6 / AD-06): before calling `KeyStore.deleteEntry(alias)`, the app must first obtain a successful `BiometricPrompt` (or device-credential fallback) authorization in app logic. No dedicated Keystore key is needed for this — the identity key's own `setUserAuthenticationRequired(true)` is unrelated to the deletion operation itself (deleting an alias does not require a signing operation), so the `BiometricPrompt` gate is the sole authorization mechanism for the destructive action.

### Step 5: EC encoding, ECDH, derivation
```kotlin
// X9.62 uncompressed encoding (AD-14) — mandatory, not "typically"
fun ECPublicKey.toX962Uncompressed(): ByteArray {
    fun BigInteger.toFixed32(): ByteArray {
        val raw = this.toByteArray()
        val trimmed = if (raw.size == 33 && raw[0] == 0.toByte()) raw.copyOfRange(1, 33) else raw
        return ByteArray(32).also {
            System.arraycopy(trimmed, 0, it, 32 - trimmed.size, trimmed.size)
        }
    }
    return byteArrayOf(0x04) + w.affineX.toFixed32() + w.affineY.toFixed32()
}

fun deriveSharedSecret(ephemeralPrivate: PrivateKey, peerEphemeralPublic: PublicKey): ByteArray {
    val ka = KeyAgreement.getInstance("ECDH")
    ka.init(ephemeralPrivate)
    ka.doPhase(peerEphemeralPublic, true)
    return ka.generateSecret() // zero this array immediately after use downstream
}
```
Ephemeral key pairs are generated with the default (non-AndroidKeyStore) `KeyPairGenerator.getInstance("EC")` provider, per AD-03, and held only in memory for the session's duration.

Transcript, SAS, and directional-key derivation follow the exact byte layout given in §6, Step 6–7. Both `SAS` and both directional keys derive from the same `PRK` with distinct `info` strings — implement this as one shared `deriveOutput(prk, info, length)` HKDF-Expand call site, not three separately-written derivations, to avoid accidental drift between them.

### Step 6: QR schema validation
Every scanned QR payload must be validated before any further processing:
- `protocol_version` must equal the single currently-supported value; unknown versions are rejected with a "please update the app" message, not silently ignored.
- All fields must be present; no additional unexpected fields are accepted.
- `identity_pubkey` / `ephemeral_pubkey` must decode to exactly 65 bytes with a leading `0x04` byte and lie on the P-256 curve (reject if `KeyFactory` throws on reconstruction).
- `session_id` must decode to exactly 16 bytes; `nonce` fields to exactly 32 bytes.
- The QR parser must never interpret payload content as a URL or deep link.

### Step 8: Messaging
**Directional keys and nonces (AD-08, AD-09):** each device knows exactly one key it is ever allowed to encrypt with (its own outgoing directional key) and exactly one key it is ever allowed to decrypt with (the peer's directional key). The nonce for message `n` sent by a given device is the 96-bit big-endian encoding of that device's own `send_seq` value at the time of sending, where `send_seq` starts at 0 for a fresh partnership and is persisted durably (Step 11) and incremented atomically before each send — never reused, even across app restarts, even after a crash between increment and network send (a skipped sequence number is harmless; a reused one is not, so the increment must be durably committed before the encrypt/send attempt, not after).

**AAD (message-context binding, addresses cross-context reuse):**
```
AAD = uint8(protocol_version) || bytes16(partnership_id) || uint32(key_epoch) ||
      uint8(sender_role) || bytes12(send_seq, same value as the nonce)
```

**Replay/reorder handling (AD-10):** the receiver maintains, per incoming direction, a bounded set of the last 500 accepted sequence numbers. On receipt: if the sequence number is already in the set, drop silently (replay/duplicate) and log a replay-detection event (do not crash, do not notify the user). Otherwise, decrypt, display, and add the sequence number to the set, evicting the oldest entry if the set exceeds 500.

**Message send:**
```kotlin
data class OutgoingMessage(val messageId: String /* 128-bit random, client-generated */,
                           val partnershipId: String, val keyEpoch: Int,
                           val seq: Long, val ciphertext: ByteArray)
```
`message_id` is the idempotency key for this endpoint (AD-15): the client generates it once, persists it in a durable local outbox row alongside the plaintext-derived ciphertext *before* attempting the network call (Step 11's durable-outbox requirement), and retries with the identical `(message_id, ciphertext)` pair on failure — never re-encrypting or generating a new ID for the same logical send.

Server-side (`POST /messages`): requires the authenticated account to be a current member of `partnership_id` with `status='active'` (reject otherwise — this is independent of and in addition to cryptographic correctness, since an unauthorized-but-valid-looking request should never even reach storage); enforces a unique `(partnership_id, message_id)` constraint so retries do not duplicate; enforces a maximum ciphertext size (4 KB) and a per-partnership rate limit (100 messages/hour). Push notifications triggered by a new message contain only a generic event type and `partnership_id` — never ciphertext, never any derived plaintext hint.

### Step 9: Unpair (epoch-based, AD-12)
1. Initiating device: obtain `BiometricPrompt`/device-credential authorization.
2. `POST /partnerships/{id}/unpair { idempotency_key }` → server sets `status='revoked'`, `revoked_at=now()` in one atomic update; from this instant, `/messages` rejects any new message for this partnership regardless of which side sends it.
3. Initiating device deletes its local identity key, directional keys, and sequence state immediately upon a successful response.
4. The peer device learns of the revocation on its next sync (Step 10's continuity check, which every active partnership goes through on app foreground and on a periodic background interval) and deletes its local key material and sequence state at that point. The UI must state this accurately: "Your partner has ended this partnership" appears once the peer's own device processes the revocation — this is not instantaneous on the peer's device, and the spec does not claim otherwise.
5. Whether ciphertext already cached locally on either device remains locally readable after unpair (since local decryption only needs locally-held keys) is a product decision, not a cryptographic one: by default, already-received message plaintext remains visible in the app's message history after unpairing (it is not retroactively hidden), matching ordinary messaging-app behavior, unless product requirements say otherwise.

### Step 10: Continuity / key-change / repair-required sync
On app foreground and on a periodic background sync (e.g., every 6 hours while the app has network), for each `active` partnership the account is part of:
```
POST /partnerships/{id}/sync { idempotency_key, local_identity_pubkey: <value or null> }
```
- `local_identity_pubkey` matches the server's pinned value for that role → no action.
- `local_identity_pubkey` is present but differs from the pinned value → server sets `status='key_change_pending'`; **do not** auto-trust the new key. Both users are notified in-app: "Your partner's device identity changed — confirm this is really them," and the partnership only returns to `active` if the OTHER party (not the one whose key changed) explicitly approves via a lightweight re-confirmation step (does not require a full new SAS ceremony, but does require an explicit human "yes, this is my partner" tap after being shown the situation).
- `local_identity_pubkey` is `null` (no local key found — uninstall, factory reset, cleared data) → server sets `status='repair_required'`; the surviving partner is notified their partner needs to re-pair. Re-establishing the partnership requires going through the full pairing flow again (§6) as a fresh session; the old partnership row is marked superseded, not silently reactivated, and gets a new `key_epoch`.
- The endpoint receiving no sync at all for an extended period (e.g., 30 days) from one side is a monitoring signal (§14), not itself a state transition — a device can be legitimately offline for a while.

### Step 11: Local storage (AD-11, AD-18)
- A Keystore-backed AES-256-GCM key (`local_storage_wrap_key`, `PURPOSE_ENCRYPT|PURPOSE_DECRYPT`, no user-authentication requirement — this key protects data at rest against a stolen/lost device's filesystem being read without the screen-lock code, not against a logged-in user; it does not need to gate the app's own normal background operation) wraps: the partnership's directional keys (or the `shared_secret` they're derived from, if re-deriving on demand is preferred over storing all three derived outputs — either is acceptable, but whichever is chosen must be the only thing persisted, not both), the per-direction sequence state, and the outbox.
- Implementation: `Cipher.getInstance("AES/GCM/NoPadding")` with the Keystore key, storing `(iv, ciphertext)` as columns in the app's existing database — not `androidx.security.crypto.EncryptedSharedPreferences`/`MasterKey`, which are deprecated.
- The database table(s) or file(s) holding this encrypted material, the sequence-state table, and the outbox table are explicitly excluded from Android Auto Backup via `<data-extraction-rules>` (API 31+) and `<full-backup-content>` (pre-31) exclusion rules, so a backup/restore cycle can never reintroduce stale sequence numbers or orphaned encrypted material inconsistent with a fresh install's Keystore state.
- Outbox durability (addresses process-death between encryption and send): a message is written to the durable outbox table (encrypted, with its `message_id` and `seq` already assigned) in the same local transaction that increments `send_seq`, *before* the network call is attempted. A background worker drains the outbox and retries indefinitely (with backoff) until each entry is acknowledged by the server, using the persisted `message_id` as the idempotency key on every retry.

---

## 9. Error Handling

| Condition | Behavior |
|---|---|
| `StrongBoxUnavailableException` during key generation | Fall back to a fresh, explicitly-constructed non-StrongBox spec (Step 4); log the fallback event. |
| Any other `ProviderException` (e.g. `BackendBusyException`) during key generation | Surface as a retryable transient error to the caller; do **not** treat as StrongBox-unavailable and do **not** silently downgrade. |
| `KeyPermanentlyInvalidatedException` / `UnrecoverableKeyException` on identity-key use | Treat as local key loss: on next sync (Step 10), the local report will show no usable key, driving the partnership to `repair_required` through the normal continuity path — do not attempt a separate ad hoc "wipe-detected" notification path. |
| Session join with mismatched or missing `nonce_A` | Reject with 403; do not transition session state; do not reveal whether a session with that ID exists. |
| Transcript signature verification failure (either side) | Abort before SAS is ever shown; treat identically whether caused by an attacker or by any inconsistency in what was scanned/relayed — the protocol does not need to distinguish the cause to be safe. |
| SAS `agree: false` from either party | Immediately transition session to `CANCELLED`; no partnership record created; both devices show "pairing cancelled" with no further detail. |
| Session TTL (10 minutes) exceeded before `COMPLETED` | Transition to `EXPIRED` by a scheduled job; client restarts the flow from a fresh session (old one is not reusable). |
| Network failure at any mutating step | Client retries the identical request with the same idempotency key (AD-15); this never creates duplicate state. |
| Duplicate/concurrent join on the same session | Only the first atomic `CREATED → JOINED` transition succeeds; later attempts get 409 and must poll status, not retry the join. |
| Duplicate ECDSA signature submission for the same transcript | Client caches and resends its own first-produced signature rather than re-signing (avoids inconsistent duplicate valid signatures for the same role). |
| Message sequence number already in the receiver's seen-set | Drop silently, log a replay-detection event; never crash, never re-request. |
| QR payload fails schema/version/size/curve validation | Reject with a user-facing "invalid or outdated code" message; never attempt partial parsing. |
| Partnership `status != 'active'` at message-send time | Reject the send with a clear "this partnership has ended" client-side state; do not silently queue. |
| Continuity sync reports a changed identity key | `key_change_pending`; freeze messaging for that partnership until explicit human re-confirmation (Step 10). |
| Continuity sync reports no local key | `repair_required`; notify the surviving partner; require a full fresh pairing flow to re-establish. |

---

## 10. Security Requirements

- SEC-1: The SAS and both directional message keys are never computed by, transmitted through, or visible to the backend in any form.
- SEC-2: All EC public keys are serialized as X9.62 uncompressed (65 bytes) with no exceptions (AD-14); the transcript hash uses only fixed-length fields in a fixed field order (AD-13/§6 Step 6).
- SEC-3: Session IDs (128-bit) and nonces (256-bit) are generated with a CSPRNG (`SecureRandom` client-side, the platform's CSPRNG server-side).
- SEC-4: Session join requires proof of possession of `nonce_A` (a one-time QR-redemption token, not a claim of peer identity — identity is established only by the subsequent signature check).
- SEC-5: Rate limits (session creation, join, message send) are enforced server-side only.
- SEC-6: SAS confirmation is a single attempt per session; mismatch or "No" invalidates the session entirely, not retryable within that session.
- SEC-7: Identity private keys never leave their device; only public keys and signatures are transmitted.
- SEC-8: Locally persisted key-derived material is encrypted at rest via a Keystore-backed key (AD-11), excluded from Auto Backup (AD-18), and ephemeral private key byte arrays are best-effort zeroed immediately after use (no guarantee of full JVM memory zeroization is claimed).
- SEC-9: No device identifiers (IMEI, serial number, advertising ID) are collected or transmitted.
- SEC-10: Users see an explicit consent notice before initiating pairing, describing that their partner will see accountability messages they send (exact copy: §16, OQ-6).
- SEC-11: Message ciphertext is cryptographically bound to its partnership, key epoch, sender role, and sequence number via the AAD (§8, Step 8) — ciphertext from one context cannot be replayed as valid in another.
- SEC-12: `/messages` and `/partnerships/{id}/*` endpoints authorize the authenticated account as a current member of the target partnership before any other processing.
- SEC-13: All server-accepted cryptographic inputs (keys, signatures, session/message payloads) have explicit maximum size limits, validated before persistence or relay, to bound resource exhaustion from malformed or oversized input.

---

## 11. Performance Requirements

- Pairing cryptographic steps (key generation, ECDH, HKDF, signing/verification) target under 500ms on a low-end device; benchmark on real target-market devices before finalizing this as a hard requirement, since Keystore/StrongBox IPC latency (not the math itself) is the likely dominant factor.
- Signature-relay polling interval: 2000ms during `KEY_EXCHANGED`/`SAS_PENDING`, for a maximum session lifetime of 10 minutes.
- Message encrypt/decrypt (AES-256-GCM on short text payloads) is not expected to be a bottleneck at any realistic accountability-message volume and requires no specific optimization.

---

## 12. Testing Requirements

### Unit tests
- X9.62 encoding correctness against known P-256 test vectors, including left-zero-padding edge cases.
- ECDH shared-secret derivation correctness against known test vectors.
- HKDF/SAS/directional-key derivation determinism given fixed inputs.
- Transcript construction determinism regardless of which "device" (A or B) computes it.
- Transcript signature: valid accepted, tampered transcript rejected, wrong identity key rejected.
- AES-GCM encrypt/decrypt round-trip per direction; deterministic nonce derived correctly from `send_seq`.
- Seen-sequence-set replay logic: duplicate rejected, out-of-order accepted, set eviction at 500 entries works correctly.

### Integration tests
- Full session state machine, including every rejection branch (mismatched nonce, expired session, duplicate join, SAS "No", unknown protocol version).
- Idempotency: retrying any mutating request with the same idempotency key never creates duplicate state (session, partnership, message).
- One-active-session and one-active-partnership constraints are enforced.
- Continuity sync: matching key (no-op), changed key (`key_change_pending` + explicit re-confirmation required), missing key (`repair_required` + fresh pairing required).
- Unpair: server-side immediate rejection of new messages; peer-side deletion only occurs after its next sync, and the test explicitly asserts the peer's local key still exists in the interval between server-side revocation and the peer's next sync.

### Adversarial tests
- Active MITM relaying/substituting ephemeral public keys between two real clients causes the two clients' independently-computed SAS values to differ (this test must fail loudly if it ever passes — it is the test that proves AD-02/AD-03 work).
- Malicious-backend simulation: a test harness that returns an attacker-chosen SAS, swaps A/B roles, replaces an identity or ephemeral key, replays an old signature, or delivers stale/modified session state must have no effect beyond causing a legitimate signature-verification failure or a client-detected inconsistency — never a successful forged pairing.
- Ciphertext transplant attempts across partnerships, key epochs, and directions are rejected via AAD mismatch.
- Session-ID guessing without the correct `nonce_A` is rejected.
- Message replay (resending an already-accepted `(ciphertext, nonce/seq)`) is rejected by the receiver.
- Duplicate message-send retries with the same `message_id` do not create duplicate stored messages.

### Device matrix
- At minimum: one StrongBox device, one TEE-only device, one rooted/custom-ROM device (confirms AD-04 — pairing succeeds with no gating on any of the three).
- Confirm fingerprint (re-)enrollment on a paired device does not invalidate the identity key.
- Confirm on at least one API < 30 and one API ≥ 30 device that the version-gated identity-key spec builds and signs correctly on both.

---

## 13. Deployment Requirements

- Ship behind a feature flag (`enable_accountability_pairing`), toggleable server-side without an app release.
- Additive-only DB migrations (`pairing_sessions`, `partnerships`, message-table extension) — no changes to existing tables.
- No new external service dependency beyond the app's existing backend and push provider.
- Rollback: disabling the flag stops new pairings; existing partnerships and message history remain fully functional, since keys live device-side and are not affected by the flag.
- `data-extraction-rules.xml` (API 31+) and `full-backup-content.xml` (pre-31) exclusion entries for the pairing/messaging local-storage tables are part of this release's changes, not a follow-up.

---

## 14. Monitoring Requirements

- StrongBox-fallback rate per app version/device model (spike → OEM regression signal).
- Session `EXPIRED` rate (spike → QR-flow UX problem).
- Rate-limit rejection counts on session creation, join, and message send (spike → abuse attempt).
- Replay-detection event counts (duplicate sequence numbers rejected).
- `key_change_pending` and `repair_required` transition counts (baseline for what's "normal" reinstall/device-change churn vs. anomalous).
- Partnerships with no continuity sync from one side for 30+ days (operational visibility, not itself an alert requiring action).
- Outbox backlog size/age per device (a persistently growing backlog indicates a delivery problem worth surfacing to the user, not just to monitoring).

---

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| Sophisticated attacker with root/Frida access on their own device forges the pairing flow | Low | Medium | Accepted residual risk for V1 (AD-01); revisit if abuse data shows this occurring | User reports; anomalous re-pairing patterns |
| Peer device offline at unpair time, giving a false impression of "immediate" symmetric revocation | Medium | Low | AD-12 defines and documents the true asynchronous behavior; UI reflects it accurately | Continuity-sync telemetry showing the gap between server revocation and peer key deletion |
| Reinstall/factory-reset without the surviving partner being notified | Medium | Medium | Step 10 continuity sync surfaces `repair_required` on the next sync from either side | Sync telemetry; user reports |
| StrongBox HAL bug on a specific OEM causes repeated fallback | Low–Medium | Low (pairing still succeeds via TEE/software) | AD-07 narrow-exception fallback | StrongBox-fallback-rate monitoring |
| Backend compromise | Low | Medium (can disrupt delivery/metadata; cannot forge message content or a pairing) | Trust model in §4; directional keys and signatures never touch the server | Standard backend intrusion monitoring (existing infra) |
| Solo-developer bandwidth underestimated even at this reduced, corrected scope | Medium | Medium (schedule slip) | Scope stays deliberately narrower than the original attestation-heavy design; sequence in §7 is dependency-ordered so partial progress is still shippable in stages | Track implementation-sequence completion against the 3–6 month V1 window |

---

## 16. Open Questions

- **OQ-1:** Exact `minSdkVersion`. Both API<30 and API≥30 code paths are specified (§8, Step 4), so this does not block implementation, but confirming it lets the version-gating logic be simplified (e.g., dropping the pre-30 branch entirely if `minSdkVersion` is already ≥30).
- **OQ-2:** Backend language/framework and datastore. This spec is framework-agnostic (state machine + atomic transitions + tables); concrete migration syntax and endpoint implementation depend on this, which isn't available anywhere in this conversation's context.
- **OQ-3:** Whether Play Integrity API or Keystore hardware attestation should be added in a later version, and what real-world abuse signal would trigger that decision — no abuse data exists yet.
- **OQ-4:** Whether post-pairing messaging should move to per-message key ratcheting (or full Double Ratchet) in V2 — depends on whether accountability messages grow more sensitive/frequent than currently anticipated.
- **OQ-5:** Exact retention period for `pairing_sessions`, `partnerships`, and message records after termination states, for DPA/ODPC sign-off — needs confirmation from whoever handles the app's DPA registration, and likely a DPIA given the app's subject matter (compulsive-behavior/addiction-adjacent accountability data), not just a retention number.
- **OQ-6:** Exact consent-notice copy shown before pairing — a product/legal writing task.
- **OQ-7:** Whether the app's existing backend already relies on Android Auto Backup for any other feature; if so, the exclusion rules in AD-18 must be added to the existing rules file rather than assumed to be the app's first use of it.

---

## 17. Acceptance Criteria

- Two devices complete a full pairing by scanning each other's QR codes and confirming a matching 4-word SAS, with each device's transcript built from what it itself scanned (not from server-relayed values alone).
- A simulated active MITM causes the two devices' computed SAS values to differ, and pairing fails to complete.
- The backend, by code inspection and by the adversarial test suite, never receives or transmits the SAS or either directional message key.
- A rooted or custom-ROM test device completes pairing with no rejection or degraded behavior tied to root/ROM status.
- Fingerprint (re-)enrollment on a paired device does not invalidate the identity key.
- A StrongBox-unavailable device (simulated via `StrongBoxUnavailableException` only) completes pairing via the explicit fallback spec with no user-visible error; a `BackendBusyException` does not trigger fallback and instead surfaces as a retryable error.
- Messages sent between paired devices are unreadable as stored server-side (ciphertext only) and decrypt correctly using the correct directional key on the receiving device.
- A replayed message is rejected by the receiver; an out-of-order (but not previously seen) message is accepted and displayed.
- Retrying a message send (same `message_id`) never creates a duplicate stored message; retrying any pairing-flow request (same idempotency key) never creates duplicate session/partnership state.
- Unpairing requires a `BiometricPrompt`/device-credential step, immediately revokes server-side acceptance of new messages for that partnership, immediately deletes the initiator's local key material, and results in the peer's local key material being deleted on the peer's next continuity sync (not instantaneously).
- A device reporting no local identity key on continuity sync drives its partnership to `repair_required` and notifies the surviving partner.
- A device reporting a changed identity key drives its partnership to `key_change_pending` and requires explicit human re-confirmation before messaging resumes — it is never auto-trusted.

---

## 18. Definition of Done

- All items in the Implementation Sequence (§7) implemented and code-reviewed.
- All tests in §12 (unit, integration, adversarial, device matrix) pass.
- Feature flag `enable_accountability_pairing` deployed and verified toggle-able without an app release.
- Monitoring dashboards from §14 are live and verified to fire on a deliberately-triggered test condition (e.g., forcing a StrongBox fallback and confirming it appears; forcing a `repair_required` transition and confirming the notification fires).
- Backup-exclusion rules (AD-18) verified by an actual backup/restore test cycle on a test device, confirming the excluded tables are absent from the restored backup and the app correctly treats a fresh-install state as expected in that case.
- OQ-1, OQ-2, OQ-5, and OQ-7 are answered and incorporated before production rollout — they affect concrete implementation and legal details, not just documentation.
- Consent notice (OQ-6) is in place and shown before the first pairing action a user can take.
- A pre-launch data audit confirms no plaintext accountability message content, device identifiers, SAS values, or directional message keys appear anywhere in backend logs or database inspection.
