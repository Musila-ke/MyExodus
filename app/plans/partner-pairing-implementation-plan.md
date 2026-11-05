# Partner Pairing — Implementation Specification (v2)

**Feature:** Out-of-band partner enrollment & device pairing (ũtethyo / MyExodus)
**Scope of this document:** V1 — typed-code pairing only. Optical (QR/CameraX) pairing is explicitly out of scope for this build and is deferred to V1.1 (see §4 and §16).
**Audience:** an engineer implementing this feature with no other context than this document.
**Revision note:** this is v2 of the specification. All contradictions and race conditions identified in the plan-level audit of v1 are resolved below. Nothing in this document should be read as "the previous version said X, this version says Y" — treat every statement here as the current, final requirement.

**⚠ IMPLEMENTATION STATUS (2026-09-28, final): the §19.4 delta is COMPLETE.** (1) typed Crockford code — ADR #97; (2) server-side attestation — ADR #96; (3) pairing-path rate limits + idempotency with the corrected §9 ordering — ADR #98; (4) production ceremony UI + §19.5 multi-user switcher — ADR #97/#99; (5) tests + deploy to the live project — tasks #40/#42; §14 telemetry wired (#37; its `superseded`/`process_restart_required` terminals have no shipped counterpart — recorded, not dropped); plus per-pair mirror fan-out (#52/ADR #111, schema v12) and the unpair trap (#66/#70, ADR #114 — device-verified by six residue unpairs through the production UI). §5.20's biometric-only rule was superseded by the owner's screen-lock fallback ruling (ADR #112; both isolating legs proven on the X6532) and enrollment-change invalidation is wired (ADR #113). A 2026-09-29 revision (ADR #121) makes the selected partner visible and switchable on every partner view and binds a composed message to its recipient (schema v14) — see the §19.5 revision note. **Device legs CLOSED 2026-09-29 (X6532, wireless adb, warm signing window — all owner tap-gated items ran):** #39 §12.3 (labeled pairing → `labels: A sees 'Mama', B sees 'Musila'` + `tray: 'Approval request from Mama'`, `approval=PASS`; unpair-one-of-N → `unpairIsolation=PASS remaining=11 deviceKeyAlive=true`), the ADR #111 emission leg → `roundtrip=PASS base=0` + `dashboard=PASS targets=[com.android.settings, com.android.chrome]`, and #67 Leg A (proven earlier, ADR #112). **No plan item of §12.3 remains open.** Live finding: the owner's real pair is orphaned by anonymous-identity rotation (OPD #30) — re-pair is the only fix. Original note: §7 step 1 is done — the required "Existing System Contract" appendix is at the end of this file (§19), with the concept→code reconciliation table. That appendix is **binding on the rest of this plan**: where a §7 step names a structure the appendix shows already shipped (`PairRegistry` → `/pairings`, `pairingGeneration` → `pairingEpoch`, the SAS/transcript/attestation machinery), the step is satisfied by reusing the shipped structure, and the deliverable narrows to the genuine delta. Read §19 before implementing any §7 step. Open Question 1 is **resolved**. Open Question 2 is resolved (BouncyCastle `bcprov-jdk18on` 1.85.2 already on the classpath; `Hkdf.kt` already implements HKDF). Open Question 3 is **overridden by the owner (2026-09-28): Android Key Attestation IS in scope, verified server-side** — deliberate deviation from §5.7/§10.7, recorded as an ADR.

---

## 1. Feature Overview

Users of the app pair with a human accountability partner so the partner can receive visibility into the user's recovery progress. Pairing is a mutual, cryptographically verified handshake between two Android devices, mediated by a Firestore relay and Cloud Functions, and finalized only after both devices independently confirm a Short Authenticated String (SAS) and each device's owner authorizes the pairing with a biometric-gated, locally-verified signing operation whose output the server independently verifies.

V1 ships one pairing entry point: a short, typed invitation code (no camera, no QR). This is the same cryptographic protocol V1.1's QR flow will use later, differing only in how the two devices discover each other's session (typed code now, QR scan later).

This feature unblocks: partner display identity, multi-user-per-partner support, Strict Mode's partner-approval gate, and the destructive unpair workflow.

---

## 2. Requirements

### Functional
- An **initiator** generates a single-use invitation and shares it out-of-band with the intended **acceptor**.
- The acceptor enters the invitation code in their own app to join the same pairing session.
- Both devices perform an authenticated ephemeral key exchange and display a 6-digit SAS; both users must actively confirm the SAS matches (an active selection, not a passive "Confirm" tap) before proceeding.
- Both devices must each separately authorize the pairing with a biometric-gated signing operation before the pairing is finalized.
- Either paired party can unilaterally unpair at any time, without requiring the other party's consent, approval, or even the other party's device being online.
- A pairing session not completed within 15 minutes becomes unusable, regardless of whether background cleanup has run yet.
- A user may only have one active (non-terminal) pairing session at a time; starting a new one supersedes any existing one, atomically.

### Non-functional
- No new paid external services. Existing Firebase (Blaze-tier Cloud Functions, Firestore, FCM, App Check) only.
- Solo-maintainable: minimize the number of distinct subsystems in V1.
- Firebase cost must stay bounded even under abuse attempts, via explicit per-function scaling limits, not rate limiting alone (see §10, §13).
- Must not create a feature that can be used to non-consensually monitor or trap a user in a relationship (see §10, unpair invariant).
- Data processed by this feature must have a defined retention period and deletion trigger for every field (see §10.12). This document states V1's retention design; it is not a legal compliance certification for Kenya's Data Protection Act No. 24 of 2019, and a full legal review remains an Open Question (§16).

### Explicitly out of scope for this build
- QR code generation/scanning, CameraX, ML Kit Barcode Scanning.
- Multi-partner-per-user or multi-user-per-partner UI (data model allows for it later; UI does not need to handle it now).
- Device-to-device key migration on reinstall/replacement (re-pairing is the supported path — see §9).
- Coercion-detection heuristics beyond the "no partner veto on unpair" invariant.
- Remote cryptographic proof that a signing key is hardware-backed (Android Key Attestation). V1's security claim is deliberately weaker and stated precisely in §10.7.

---

## 3. Existing System Context

The following are already shipped and must not be redesigned by this work:
- `EnrollmentRecord`, `PairRegistry` — existing data structures this feature writes into on successful pairing and reads from on unpair. **This document cannot specify their exact current field names, collection paths, or existing invariants because that schema was not supplied during design.** Before implementation begins, the implementer must produce the "Existing System Contract" appendix described in §16 (Open Question 1) by reading the actual code. Nothing in §8 below should be built against an assumed shape for these two structures — the new fields this feature needs (`partnerUserId`, `partnerDeviceId`, `partnerIdentityKeyVersion`, `pairingGeneration`, `pairedAt`, `unpairedAt`) must be reconciled against whatever the real schema already contains.
- Firestore strict-mode relay channel infrastructure and Firebase App Check attestation — already configured; this feature adds new collections and functions on top of it, it does not reconfigure App Check itself.
- FCM wake-notification plumbing — already exists; this feature adds new message payloads.
- The Hilt DI graph, Jetpack Compose/Material 3 "Desert Dawn" UI system — new screens plug into these, no changes to the DI graph structure itself.
- minSdk 26.

This feature adds: a new Firestore collection set (`pairingSessions`, `idempotency`, `deviceIdentityKeys`, `activePairingLocks`), a new set of Cloud Functions, a Keystore-backed device identity signing key (new), and new Compose screens for invitation creation, code entry, SAS confirmation, and biometric approval.

---

## 4. Architecture

```
┌─────────────────┐                                   ┌─────────────────┐
│ Initiator Device │                                   │ Acceptor Device  │
│                  │                                   │                  │
│  Compose UI      │                                   │  Compose UI      │
│  ViewModel (UDF) │                                   │  ViewModel (UDF) │
│  Keystore        │                                   │  Keystore        │
│  (PURPOSE_SIGN,  │                                   │  (PURPOSE_SIGN,  │
│   EC P-256, via  │                                   │   EC P-256, via  │
│   androidx.      │                                   │   androidx.      │
│   biometric)     │                                   │   biometric)     │
└────────┬─────────┘                                   └────────┬─────────┘
         │  HTTPS Callable Functions (App Check + Auth required)  │
         ▼                                                        ▼
┌───────────────────────────────────────────────────────────────────────┐
│                     Cloud Functions (server-authoritative)             │
│  createPairingSession / joinPairingSession / submitCommit /            │
│  revealKeys / confirmSas / requestApprovalChallenge /                  │
│  submitApproval / cancelPairingSession / unpair                        │
│  — every function: Auth+AppCheck check → idempotency lookup (by       │
│    requestId+requestHash) → expiry check (new requests only) →         │
│    runTransaction (read current state + active-session lock,           │
│    validate transition, write) → optional FCM wake send                │
│  — maxInstances and per-function timeout configured (§13)              │
└───────────────────────────────────┬───────────────────────────────────┘
                                     ▼
                    ┌────────────────────────────────┐
                    │ Firestore                        │
                    │ /pairingSessions/{sessionId}      │  ← clients: read-only,
                    │ /idempotency/{requestId}          │    scoped to participants
                    │ /deviceIdentityKeys/{uid}/{did}   │  ← server-only
                    │ /activePairingLocks/{uid}         │  ← server-only
                    │ PairRegistry / EnrollmentRecord   │  (existing, extended)
                    └────────────────────────────────┘
```

Clients never write to Firestore. All mutation goes through callable Cloud Functions, which are the actual authorization boundary (the Admin SDK bypasses Firestore Security Rules, so the rules below are a second, independent layer — not the primary one). Clients attach a read-only Firestore listener to their own `pairingSessions/{sessionId}` document for live state updates; FCM data messages are a wake hint only, never trusted as authoritative.

QR/CameraX (V1.1) will add a second `joinPairingSession` entry path (QR payload encodes the same invitation-token format used for typed entry) — no protocol change required later.

---

## 5. Architecture Decisions

1. **All Firestore writes go through callable Cloud Functions; clients get read-only listener access.** Only this model gives an atomic, server-enforced state machine; Firestore Security Rules exist as a second, independent denial layer, not as the primary authorization mechanism (privileged Admin SDK calls bypass rules entirely).
2. **Firebase Authentication + Cloud-Function-side UID checks are required in addition to App Check.** App Check attests the app binary, not the user.
3. **The pairing protocol uses commit-then-reveal.** Prevents an adaptive MITM who could otherwise see one honest public key before choosing a substitute for the other side.
4. **The invitation token is generated entirely client-side and never leaves the client in plaintext, including in server responses.** `createPairingSession` receives only a salted hash of the token (see §8.2); the server's response never echoes the token back, which also removes any risk of the token being persisted in an idempotency cache — there is nothing server-side to cache except the hash the client already possesses.
5. **Idempotency lookup happens before the expiry check, and only a genuinely new request is subject to expiry.** A replay of an already-committed request must be able to retrieve its committed result even after the session's execution window has closed; expiry only blocks new state transitions, never the retrieval of a prior committed outcome.
6. **One active (non-terminal) pairing session per UID is enforced via an atomic lock document (`/activePairingLocks/{uid}`), written in the same transaction as session creation.** A plain existence query is not atomic and admits a race between two concurrent creation attempts.
7. **Biometric approval produces a signature the server verifies against a registered public key. The server does not attempt to remotely prove the key is hardware-backed.** Android Key Attestation would let the server verify hardware provenance, but implementing and validating attestation chains is disproportionate complexity for a solo-maintained V1 with this budget. The accurate, stated security property is: *"the server verifies possession of the device's registered signing key; on an uncompromised Android device, using that key requires strong biometric authentication, because the key is configured with `setUserAuthenticationRequired(true)`."* This is a decision, not an open question — V1 ships with this weaker but honest claim; hardware attestation is reconsidered only if a future threat-model review requires it. **[ADR #112, owner ruling 2026-09-28 — the italicized claim's second half is amended: keys generated from #112 are configured with a positive 900 s validity and accept `AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL`, so 'requires strong biometric authentication' becomes 'requires strong biometric authentication or the device credential (screen lock)'. Pre-#112 keys remain biometric-only (immutable). Server-side possession verification stands; the attestation-chain verification added later (task #43) rides on top.]**
8. **Both parties must submit a signed approval before a session finalizes to `PAIRED`.** Either side could be the person being monitored; the protocol treats both as needing to consent.
9. **Unpairing never requires the other party's approval and is never blocked by the other device being offline.** Hard invariant.
10. **Approval and unpair signing challenges are single-use, short-lived, and bound to an explicit relationship generation identifier.** Prevents a stale signed authorization from a prior pairing session or a prior incarnation of the relationship from being replayed against a later one.
11. **Expiry is enforced as a logical check on every new (non-replayed) function call, independent of when the background cleanup job runs.**
12. **All Cloud Functions are idempotent via a client-supplied `requestId`, and a replayed `requestId` with a different payload is rejected rather than silently re-served the old cached result.**
13. **QR/CameraX pairing is deferred to V1.1.** The typed-code path alone provides full pairing capability.
14. **Device replacement/reinstall requires re-pairing in V1; there is no automatic key migration.**
15. **SAS confirmation requires an active choice (select-the-matching-code), not a passive "Confirm" button.**
16. **No optional "key confirmation MAC" is derived or transmitted in V1.** The commit/reveal step already gives cryptographic transcript integrity, and the SAS already gives human-verified integrity; a third, unused confirmation channel with no defined transport (as flagged in review) adds complexity without adding a security property V1 needs. Only `SasMaterial` is derived from HKDF in V1 — not an unused `SessionKey`/`MacKey`, which would encrypt relay payloads V1 doesn't send.
17. **The invitation token carries 60 bits of entropy (12 Crockford Base32 characters), not 40.** Combined with single-use consumption and a 15-minute TTL, this gives an adequate offline-brute-force margin against a hypothetical Firestore data exposure, at a small, acceptable cost to manual-entry length.
18. **The invitation token's checksum character stays within the Crockford Base32 alphanumeric alphabet.** The `*~$=U` extended check-symbol scheme is dropped in favor of a check character computed as `sum(weighted digit values) mod 32`, mapped back into the same 32-character alphabet — fully alphanumeric, consistent with the "alphanumeric" requirement, at the cost of slightly weaker (but still adequate for this use) transposition-error detection than Crockford's mod-37 scheme.
19. **SAS mismatch handling never locks both participants symmetrically.** Repeated mismatch reports reject that session, but account-level lockout (if any) applies only to the UID that is disproportionately generating rejected sessions across many different partners, tracked separately — not to whichever UID happens to be paired with an abusive counterpart. This prevents a malicious participant from using false mismatch reports as a denial-of-service tool against an innocent partner.
20. **AndroidX `androidx.biometric:biometric` is used for all `BiometricPrompt` interaction, not the framework `android.hardware.biometrics` package.** The framework API requires API 28+; this app's minSdk is 26, so the framework API is not usable directly.
21. **Process death before the reveal phase completes is not resumed — the session is abandoned and the user restarts pairing.** Ephemeral private keys exist only in process memory and are never persisted, so there is nothing to resume from after process death; persisting them would require a new secure-storage subsystem this feature does not otherwise need. After reveal completes (both public values exchanged and verified), the derived material still lives only in memory for the remainder of the flow — process death after reveal also requires a restart, for the same reason. Only `sessionId` (non-sensitive) is persisted locally, purely so the UI can show "your previous pairing attempt expired/was abandoned" rather than silently forgetting it happened.

---

## 6. Data Flow

### 6.1 Session creation and join
1. Initiator's device generates a 12-character Crockford Base32 invitation token + check character locally (CSPRNG, 60 bits of data). It computes `tokenHash = HMAC-SHA256(serverPepper, token)` — see §8.2 for why HMAC with a server-held pepper is used instead of plain SHA-256. Wait: the pepper must be known to compute the HMAC, and the client cannot hold the server's pepper. **Correction applied inline:** the client does not compute `tokenHash` itself. Instead, the client sends the raw token to `createPairingSession` over the authenticated, TLS-protected callable-function channel (this is a single point-to-point authenticated call, not a Firestore write); the **server** computes `tokenHash = HMAC-SHA256(serverPepper, token)` and stores only the hash. The server's response to `createPairingSession` contains only `{sessionId, expiresAt}` — never the token or its hash — since the initiator's own device already has the raw token it generated and does not need it echoed back.
2. Function creates `/pairingSessions/{sessionId}`, stores `tokenHash`, sets `expiresAt = now + 15m`, and also writes/refreshes `/activePairingLocks/{initiatorUid}` pointing at this session inside the same transaction, superseding any prior active session for that UID.
3. Initiator shares the raw token with the partner out-of-band.
4. Acceptor enters the token; client normalizes it locally (uppercase, alias mapping, hyphen stripping) and validates the check character locally before calling `joinPairingSession(token)` — the raw token is sent over the same authenticated callable-function channel, never through Firestore.
5. Function computes `HMAC-SHA256(serverPepper, token)` and looks up a session by `tokenHash`, validates it's unexpired and unused, validates acceptor UID ≠ initiator UID, marks the token consumed (single-use, regardless of downstream success), records the acceptor's commit, writes/refreshes `/activePairingLocks/{acceptorUid}`, transitions state, notifies initiator via FCM.

### 6.2 Reveal and key agreement
6. Both devices call `revealKeys` with their real ephemeral public key + nonce.
7. Function verifies each reveal hashes to the stored commit for that role; on success, stores both, transitions to `KEY_EXCHANGE`, notifies both via FCM.
8. Both devices read the peer's revealed values via their listener, compute `X25519(sk_own, pk_peer)`, reject an all-zero result, and derive `SasMaterial` via HKDF over the canonical transcript (§8.3). No other key material is derived in V1.

### 6.3 SAS verification
9. Both devices display a 6-digit SAS and require the user to actively select which of two candidate codes matches what their partner reports (not a passive confirm).
10. Each device calls `confirmSas(sessionId, matched: true/false)`. On any `false`, function transitions to `REJECTED`; this rejects only the session, and separately increments a per-reporting-UID abuse counter used for cross-session pattern detection (§5.19), never a symmetric per-session lock on both participants.

### 6.4 Biometric approval and finalization
11. Once both `confirmSas(true)` calls land, function transitions to `APPROVAL_PENDING`. Each device calls `requestApprovalChallenge`; if a live, unexpired challenge already exists for that participant, the same challenge is returned rather than a new one being generated (prevents the two-challenges-race described in review).
12. Each device signs `domain || sessionId || pairingGeneration || challenge || "approve"` (see §8.4 for exact encoding) inside a `BiometricPrompt.CryptoObject` flow using its registered `PURPOSE_SIGN` key, then calls `submitApproval(sessionId, signature)`.
13. Function verifies the signature against the device's currently registered public signing key, consumes the challenge (single-use). Once both approvals are verified, a single transaction writes `PairRegistry`/`EnrollmentRecord` entries for both users (including a new `pairingGeneration` value for this relationship) and sets session status `PAIRED`.

### 6.5 Unpair
14. Either device calls `unpair(challenge, signature)`. The challenge is obtained via a dedicated `requestUnpairChallenge` call scoped to the existing relationship's current `pairingGeneration` — not the pairing session's challenge, which no longer exists once the session reaches `PAIRED`. The signed payload includes `pairingGeneration`, so a stale signed unpair request from a previous relationship incarnation cannot affect a newly re-established one. Function verifies the signature, consumes the challenge, immediately sets the relationship `UNPAIRED` in `PairRegistry` regardless of the partner device's connectivity, and sends an informational (non-blocking) FCM notice to the partner.

---

## 7. Implementation Sequence

1. **Obtain the actual `PairRegistry`/`EnrollmentRecord` schema and current write paths from the codebase** (§16, Open Question 1) — blocking prerequisite for step 7 below; do not guess this.
2. **Firestore schema + security rules** for `pairingSessions`, `idempotency`, `deviceIdentityKeys`, `activePairingLocks`.
3. **Device identity key registration flow**: `androidx.biometric`-backed Keystore `PURPOSE_SIGN` key generation, public key upload to `deviceIdentityKeys/{uid}/{deviceId}` on first login.
4. **Cloud Functions shared utilities**: Auth+App Check enforcement wrapper, idempotency wrapper (requestId + requestHash, checked before expiry), transaction helper, `maxInstances`/timeout config.
5. **`createPairingSession` + `joinPairingSession`** (server-side token hashing with pepper, active-session lock, rate limiting).
6. **`revealKeys`** (commit verification, state transition to `KEY_EXCHANGE`).
7. **`confirmSas`** (mismatch handling, per-reporting-UID abuse counters, no symmetric lockout).
8. **`requestApprovalChallenge` + `submitApproval`** (single active challenge per participant, signature verification, dual-approval finalization, `PairRegistry`/`EnrollmentRecord` write with new `pairingGeneration`).
9. **`cancelPairingSession`** (explicit user cancel at any pre-`PAIRED` state, with a reason code — user-initiated vs. superseded).
10. **`requestUnpairChallenge` + `unpair`** (separate flow, scoped to `pairingGeneration`, operates on existing `PairRegistry`).
11. **Scheduled `cleanupExpiredSessions`** (storage hygiene only) and **`cleanupExpiredIdempotencyRecords`**.
12. **Client crypto module**: X25519 keypair generation (library selected per §16 Open Question 2), commit/reveal, transcript construction, HKDF derivation of `SasMaterial` only, degenerate-key rejection, SAS rendering, in-memory-only key lifetime enforcement (no persistence — see §5.21).
13. **Client Keystore module**: `PURPOSE_SIGN` key management via `androidx.biometric`, exact signature encoding per §8.4, `KeyPermanentlyInvalidatedException` handling.
14. **Compose screens**: invite-creation, code-entry, SAS-confirmation (active-choice UI), biometric-approval, success/error/restart states — wired to a `PairingViewModel` that delegates crypto and network orchestration to separate injected components rather than holding all logic itself.
15. **FCM wake payload handling** in `PartnerSyncCoordinator` (existing component — add new message types).
16. **Telemetry** (§14), wired last, once the functional flow is verified end-to-end in the emulator suite. **[DONE 2026-09-28 — ADR #101 (task #37): client `PairingCeremonyTelemetry` (6 shipped terminals, exactly-once per attempt via an `AtomicBoolean` guard, wire strings pinned by test) → Crashlytics; server rails deployed (replay log line, uid on the mismatch log); GCP live: 2 log metrics, 1 429-throttling alert policy, 4 dashboard tiles + text-map update. Cold start = platform `container/startup_latencies` (no code). §14's `superseded`/`process_restart_required` have no shipped counterpart — recorded, not silently dropped.]**

---

## 8. Detailed Implementation Steps

### 8.1 Firestore schema

```
/pairingSessions/{sessionId}
  protocolVersion: number             // = 1
  status: string                      // enum, see §9
  initiatorUid, acceptorUid: string   // acceptorUid null until join
  initiatorDeviceId, acceptorDeviceId: string
  tokenHash: string                   // HMAC-SHA256(serverPepper, token), server-computed only
  invitationConsumed: bool
  commitInitiator, commitAcceptor: string (base64)
  pkInitiator, nonceInitiator, pkAcceptor, nonceAcceptor: string (base64)  // nullable until reveal
  sasConfirmedInitiator, sasConfirmedAcceptor: bool
  approvalChallengeInitiator, approvalChallengeAcceptor: string (base64, nullable)
  approvalChallengeInitiatorExpiresAt, approvalChallengeAcceptorExpiresAt: Timestamp (nullable)
  mismatchReporterUid: string (nullable)   // which participant reported the mismatch, for §5.19 abuse tracking
  createdAt, updatedAt, expiresAt: Timestamp (serverTimestamp only)

/idempotency/{requestId}
  functionName: string
  requestHash: string        // hash of the request payload; a replay with a different payload is rejected
  result: map                // cached response — never contains a raw invitation token (nothing to cache, see §6.1 step 1)
  createdAt, expiresAt: Timestamp

/deviceIdentityKeys/{uid}/{deviceId}
  publicKey: string (base64, SPKI-encoded EC P-256 public key)
  keyVersion: number            // increments on every rotation
  status: string                // "active" | "revoked"
  registeredAt: Timestamp
  revokedAt: Timestamp (nullable)
  replacesKeyVersion: number (nullable)

/activePairingLocks/{uid}
  activeSessionId: string
  updatedAt: Timestamp
```

**Security rules** (second, independent layer — not the primary authorization boundary; see §4):
```
match /pairingSessions/{sessionId} {
  allow read: if request.auth != null &&
    (request.auth.uid == resource.data.initiatorUid ||
     request.auth.uid == resource.data.acceptorUid);
  allow write: if false; // Admin SDK only, via Cloud Functions
}
match /idempotency/{requestId} { allow read, write: if false; }
match /deviceIdentityKeys/{uid}/{deviceId} { allow read, write: if false; }
match /activePairingLocks/{uid} { allow read, write: if false; }
```

**Server-side function authorization** (the actual boundary — every callable must enforce this explicitly, and must be covered by the tests in §12.2):
```
function must verify:
  context.auth != null
  context.app != null                      // App Check
  for session-scoped calls:
    caller UID == session.initiatorUid OR session.acceptorUid
    (never trust a UID or deviceId supplied in the request payload —
     always derive identity from context.auth.uid, and derive the
     expected deviceId server-side from the caller's registered device
     record, never from a client-supplied field)
```

### 8.2 Invitation token

- 12 Crockford Base32 data characters (60 bits) + 1 check character, displayed as `XXXX-XXXX-XXXX-C`.
- Check character: computed as `(Σ weight_i × value_i) mod 32`, mapped to the standard 32-character Crockford alphabet — fully alphanumeric, no extended symbols.
- The server never stores the raw token. It receives the raw token once, over the authenticated callable-function TLS channel, at `createPairingSession` time (from the initiator, to establish `tokenHash`) and at `joinPairingSession` time (from the acceptor, to look up the session by recomputing the same HMAC and matching against the stored `tokenHash`). It is never written to Firestore, never included in any function's response payload, and never appears in the idempotency cache.
- `tokenHash = HMAC-SHA256(serverPepper, token)`, where `serverPepper` is a server-held secret (e.g., Cloud Functions environment config / Secret Manager), not derivable by an attacker who only has read access to a Firestore export — this is strictly stronger than plain `SHA-256(token)` against an offline brute-force attempt following a hypothetical data exposure.
- Token is single-use: `joinPairingSession` marks `invitationConsumed = true` inside the same transaction that validates it, regardless of whether the join ultimately succeeds.
- Client-side entry normalizes input per Crockford rules before submission: uppercase, map `I`/`L`→`1`, `O`→`0`, strip hyphens, validate the check character locally before calling the function.

### 8.3 Cryptographic protocol

All values below are exact byte lengths; do not vary them.

- `sessionId`: server-generated, 128-bit random value. Firestore document ID and all wire references use its 32-character lowercase-hex encoding. Wherever the protocol below says "the 16-byte raw session identifier," this means hex-decoding that 32-character string back to 16 raw bytes before hashing — never hash the hex string itself.
- Ephemeral keypair: X25519, 32-byte public key. Library selection is an Open Question (§16, Open Question 2) — whichever library is chosen must expose raw 32-byte public/private values in this exact form, and must be validated to run correctly across API 26–32 before this step is considered complete.
- Nonce: 32 bytes, CSPRNG.
- Domain string: the exact ASCII bytes `MYEXODUS-PAIRING-V1` (19 bytes, no trailing null, no quotes).
- Role bytes: the single ASCII byte `0x49` (`'I'`, initiator) or `0x41` (`'A'`, acceptor).
- Version byte: a single byte, value `0x01` for protocol version 1.
- **Commit:** `commit = SHA-256(domain || pk(32B) || nonce(32B) || sessionId(16B raw) || role(1B))`.
- **Reveal verification:** on `revealKeys`, the server recomputes the commit from the submitted `pk`/`nonce` and rejects with `FAILED` if it doesn't match the stored commit for that role.
- **Canonical transcript:**
  `T = SHA-256(domain(19B) || versionByte(1B) || sessionId(16B raw) || 0x49 || pkInitiator(32B) || nonceInitiator(32B) || 0x41 || pkAcceptor(32B) || nonceAcceptor(32B))`
- **Key agreement:** `K = X25519(sk_own, pk_peer)`. If `K` is all-zero (32 zero bytes), abort locally with a client-side `KEY_EXCHANGE_FAILED` state, call `cancelPairingSession(reason: "invalid_key")`, and do not proceed. Do not display a SAS in this case.
- **Derivation:** `HKDF-Extract(salt = nonceInitiator || nonceAcceptor, IKM = K)` → `HKDF-Expand(..., info = T, L = 32 bytes)` → `SasMaterial`. No other output is derived in V1 (see §5.16 — `SessionKey`/`MacKey` are not built, since nothing in V1 encrypts a relay payload or performs key confirmation).
- **SAS:** interpret `SasMaterial[0:4]` as a big-endian unsigned 32-bit integer, `SAS = value mod 1_000_000`, zero-padded to 6 digits.
- Implementation must be validated against a fixed, checked-in test vector (fixed `sessionId`, nonces, keypairs → expected `commit`, `T`, `K`, `SasMaterial`, `SAS`) reproduced independently by both the Kotlin client test suite and, where feasible, a reference script used only for generating the vector — not by trusting the Kotlin implementation to check itself.

### 8.4 Device identity signing key

- Algorithm: EC P-256, `KeyProperties.PURPOSE_SIGN`, JCA signature algorithm `SHA256withECDSA`, signature wire encoding ASN.1 DER (standard JCA `Signature` output — do not re-encode to IEEE P1363).
- `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)`. **[Reconciled 2026-09-28, task #68 — ADR #113:** the `(true)` argument is a verified **no-op** for this key — the flag "applies only to keys which require user authentication … and if no positive validity duration has been set" (android-36 `KeyGenParameterSpec` javadoc, checked against local sources), and the signing key carries a positive 900 s validity duration. Enrolment-change guarding is therefore the app-level poll (protocol plan §8.2) plus sign-time `KeyPermanentlyInvalidatedException` detection — both now wired.]**
- Hardware backing policy: request `StrongBox` if `PackageManager.hasSystemFeature(FEATURE_STRONGBOX_KEYSTORE)` is true; otherwise request a standard (TEE-backed on virtually all API 26+ devices) hardware Keystore key. Never fall back to a software-only key — if the device reports it cannot provide a hardware-backed key at all, treat this as a device that cannot use this feature and show an explicit unsupported-device message rather than silently weakening the key.
- `BiometricPrompt` interaction uses `androidx.biometric:biometric` (not the framework API — see §5.20), configured to require `BIOMETRIC_STRONG`. If the device has no `BIOMETRIC_STRONG`-class authenticator enrolled, approval and unpair cannot proceed; show an explicit message rather than silently degrading to a weaker authenticator class or device credential. **[Owner-ruled 2026-09-28, ADR #112 — supersedes the second half of this line: approval/unpair falls back to the screen lock (PIN/pattern/password) through the platform's confirm-credential flow when biometrics cannot run, and the explicit message remains only when neither can sign. Keys generated from #112 accept `AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL` (positive 900 s timeout unchanged); pre-ruling keys are immutable, stay biometric-only, and refuse the fallback with their own explicit message.]**
- Registered once per device at first authenticated app launch: generate key, upload public key (SPKI DER, base64) to `deviceIdentityKeys/{uid}/{deviceId}` via a dedicated callable function, authenticated by the existing app login flow. `deviceId` is a 128-bit random value generated on-device and persisted in app-private storage; it is regenerated (a new identity) on reinstall — it is never derived from a hardware identifier.
- **Signed payload for approval:** `domain(19B) || sessionId(16B raw) || pairingGeneration(16B raw) || challenge(32B) || operation(1B)`, where `operation = 0x01` for "approve" and `0x02` for "unpair." This exact byte layout must be pinned with a test vector, mirrored identically on server and client.
- On `KeyPermanentlyInvalidatedException` (thrown from `Signature.initSign()`): delete the invalidated key, generate a replacement key (new `keyVersion`, marking the prior version `revoked` in `deviceIdentityKeys`), prompt the user to re-authenticate (existing app login), re-upload the new public key. `PairRegistry` entries are never deleted by this event — only the device's signing capability is refreshed. If this happens mid-approval, the in-progress pairing session transitions to `AUTH_REQUIRED`; the user must complete key re-registration and then resume approval if the session TTL still permits, otherwise restart. **[Reconciled 2026-09-28, task #68 — ADR #113.** The shipped response to `KeyPermanentlyInvalidatedException` is the protocol plan §9 Identity Migration — rotate both device keys, bump the identity epoch, clear every enrollment, tell the user partner connections were reset (`IdentityResetDialog`) — not the in-place signing-key refresh described here. Recorded delta (heavier path chosen deliberately): a device whose secure lock screen was removed or reset must re-pair, and rotation also shreds each pair's agreement key so nothing of the old identity lingers in the keystore.]**

### 8.5 CameraX / QR (V1.1 — not built now)

Not implemented in this milestone. When built, the QR payload will encode the same `invitationToken` format from §8.2, consumed through the existing `joinPairingSession` function unchanged.

*Revision 2026-10-17 (docs audit): this section and §4's "QR/CameraX (V1.1)" paragraph describe the plan-time assumption and are superseded by §19.2/§19.3. The shipped app already had QR pairing (`PairingCodec`, CBOR) before this delta; the delta built the **typed Crockford code** alongside it (ADR #97), not QR. The shipped ceremony uses neither `joinPairingSession` nor the §8 `pairingSessions` collection — see the §19.3 reconciliation table.*

---

## 9. Error Handling

### State machine (authoritative list — implement exactly these states)

```
AWAITING_ACCEPTOR → COMMIT_EXCHANGE → KEY_EXCHANGE → SAS_VERIFICATION
  → APPROVAL_PENDING → FINALIZING → PAIRED

Terminal/error states reachable from any non-terminal state above:
  EXPIRED           — expiresAt passed at the time of a new (non-replayed) function call
  CANCELLED         — reason: "user_cancelled" | "superseded" | "process_restart_required"
  REJECTED          — SAS mismatch reported by either party
  FAILED            — commit/reveal verification failure, or all-zero key agreement
  AUTH_REQUIRED     — KeyPermanentlyInvalidatedException during approval

Post-PAIRED:
  UNPAIR_REQUESTED  — transient, resolves immediately to UNPAIRED
  UNPAIRED          — terminal
```

`SUPERSEDED` is not a separate state — it is a `reason` value on `CANCELLED`, so a superseded session and a user-cancelled session are distinguishable in logs and telemetry without needing a distinct state in the transition table.

### Transition table (every function enforces this; reject with a typed error otherwise)

| Current state | Function called | Precondition checked | Next state |
|---|---|---|---|
| (none) | `createPairingSession` | atomically write/replace `/activePairingLocks/{initiatorUid}`; any prior active session for that UID transitions to `CANCELLED (reason: superseded)` in the same transaction | `AWAITING_ACCEPTOR` |
| `AWAITING_ACCEPTOR` | `joinPairingSession` | `tokenHash` matches, unconsumed, unexpired; acceptorUid ≠ initiatorUid; atomically write/replace `/activePairingLocks/{acceptorUid}` | `COMMIT_EXCHANGE` |
| `COMMIT_EXCHANGE` | `revealKeys` (both sides) | both commits present, unexpired | `KEY_EXCHANGE` (after both reveal) |
| `KEY_EXCHANGE` | (client-local key derivation) | — | `SAS_VERIFICATION` |
| `SAS_VERIFICATION` | `confirmSas(true)` (both sides) | unexpired | `APPROVAL_PENDING` |
| `SAS_VERIFICATION` | `confirmSas(false)` (either side) | — | `REJECTED` (this session only; see §5.19 for abuse tracking) |
| `APPROVAL_PENDING` | `requestApprovalChallenge` | if an unexpired challenge already exists for this participant, return it unchanged; otherwise generate one | (no state change) |
| `APPROVAL_PENDING` | `submitApproval` (both sides, verified signature, challenge consumed) | unexpired, challenge matches, not already consumed | `FINALIZING` → `PAIRED` (single transaction once both verified; writes `pairingGeneration`) |
| any pre-`PAIRED` | `cancelPairingSession` | caller is a participant | `CANCELLED (reason: user_cancelled)` |
| any pre-`PAIRED` | any function call | `now >= expiresAt`, **and this is not a replay of an already-committed `requestId`** | `EXPIRED` |
| `PAIRED` | `requestUnpairChallenge` | caller is a participant in the current `pairingGeneration` | (no state change; issues a scoped, single-use challenge) |
| `PAIRED` | `unpair` | caller is a participant, valid signature over the current `pairingGeneration`, challenge unconsumed | `UNPAIRED` (immediate, no partner approval required) |

### Idempotency (corrected ordering)

Every callable accepts `requestId: string` (client-generated UUID v4). Function logic, in this exact order:
```
1. authenticate (Auth + App Check)
2. compute requestHash = hash(function name + normalized payload)
3. look up /idempotency/{requestId}
     if found and requestHash matches: return cached result immediately (no expiry check, no re-processing)
     if found and requestHash does NOT match: reject — "requestId reused with different payload"
     if not found: continue
4. check now >= expiresAt on the target session (only reached for genuinely new requests) → if true, EXPIRED
5. load session + activePairingLocks as needed, verify caller is an authorized participant
6. execute transaction (validate current state, perform transition, write)
7. on success, write /idempotency/{requestId} = { functionName, requestHash, result, createdAt, expiresAt: now + 24h }
8. send FCM wake message only on this fresh-execution path, never on a cache-hit replay
```
This ordering guarantees that a request which actually committed server-side, but whose response never reached the client (e.g., a dropped connection right after commit), can always be safely retried and will return the true committed result — even if the session itself has since expired — rather than being told `EXPIRED` for an operation that already succeeded.

### Client-side failure handling

- **Network drop mid-exchange:** client retries the last function call with the same `requestId` (idempotent by design, and now guaranteed correct per the ordering above).
- **App killed before the reveal phase completes:** on resume, the client finds it no longer holds the ephemeral private key for the persisted `sessionId`; it cannot proceed. The UI shows "your previous pairing attempt was interrupted — start again," calls `cancelPairingSession(reason: process_restart_required)` if the session is still live, and returns to the invite/join entry screen. This is a hard requirement, not a fallback — there is no code path that attempts to resume mid-handshake after process death.
- **App killed after reveal but before SAS/approval completes:** same as above — restart, for the same reason (derived key material also lives only in process memory).
- **FCM message arrives for an already-resolved session:** client always re-reads authoritative Firestore state before acting; a stale FCM payload never directly drives a UI transition.

---

## 10. Security Requirements

1. Every Cloud Function requires: valid Firebase Auth context, valid App Check token (`enforceAppCheck: true`), and — for session-scoped functions — the calling UID must equal `initiatorUid` or `acceptorUid` on the target document. Identity and `deviceId` are always derived server-side from `context.auth.uid` and the caller's registered device record — never trusted from a client-supplied field in the request payload. This is the actual authorization boundary; Firestore Security Rules are a secondary denial layer only, since the Admin SDK bypasses them.
2. Firestore security rules deny all client writes to `pairingSessions`, `idempotency`, `deviceIdentityKeys`, and `activePairingLocks` unconditionally.
3. All ephemeral key material (`sk`) is generated on-device, lives only in process memory for the duration of the handshake, and is never transmitted or persisted; only public keys and nonces leave the device.
4. Reject any revealed public key that produces an all-zero X25519 output; abort the session and log a security event without displaying a SAS.
5. Rate limits, enforced per authenticated UID inside the relevant Cloud Function using a per-UID rolling-window counter document:
   - Max 5 `createPairingSession` calls per UID per hour.
   - Max 10 `joinPairingSession` attempts per UID per hour.
   - Per-session cap: max 3 `confirmSas(false)` reports before the session is permanently `REJECTED`.
   - Cross-session abuse signal (not a lockout): a per-reporting-UID counter of mismatches across distinct sessions with distinct partners over a rolling 24h window, surfaced in telemetry (§14) for manual review — never an automatic symmetric lock on both participants of a single session (this would let a malicious participant weaponize the mismatch report as a denial-of-service tool against an innocent partner).
6. Invitation tokens: 60 bits of entropy, single-use, 15-minute TTL, hashed server-side with `HMAC-SHA256(serverPepper, token)` (never plain `SHA-256`, and the raw token itself is never stored — see §8.2).
7. Approval and unpair signatures use a `PURPOSE_SIGN` Keystore key (`androidx.biometric`; strong-biometric prompt first, screen-lock fallback per ADR #112), `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)` **[verified no-op for this time-bound key — task #68, ADR #113; enrolment-change guarding is the app-level poll]**, hardware-backed (StrongBox where available, otherwise TEE — never software-only). **Stated security property (deliberately precise):** the server verifies possession of the device's currently registered signing key; it does not independently verify, via remote attestation, that the key is hardware-backed or that its policy configuration is as claimed. On an uncompromised device this distinction is immaterial; on a compromised or rooted device, an attacker with sufficient control could in principle satisfy the signature check without a genuine biometric event. This is an accepted, documented risk for V1 (see §15).
8. **Unpair invariant (hard requirement):** `unpair` never requires the partner's signature, approval, or online presence. It completes as soon as the requesting device's own signature — scoped to the current `pairingGeneration` and a freshly issued, single-use challenge — verifies. No code path may add a partner-side veto, delay, or notification-blocking step to this flow, now or in any future change to this feature.
9. Expiry (`now >= expiresAt`) is checked only for requests that are not a replay of an already-committed `requestId` (see §9, corrected idempotency ordering) — never rely on the scheduled cleanup job for security enforcement.
10. Telemetry, logs, and crash reports must never contain: raw invitation tokens, ephemeral private keys, SAS values, HKDF outputs, approval/unpair signatures, or the `serverPepper`. Session IDs and hashed tokens are acceptable to log.
11. Cloud Functions are configured with an explicit `maxInstances` ceiling, a per-function timeout, and a bounded maximum request payload size, so that a retry storm or abuse attempt has a hard ceiling on cost regardless of rate-limiting logic (see §13 for concrete values).
12. **Data retention (per field, V1 design):**
    - `pairingSessions` documents: deleted by the scheduled cleanup job 24 hours after reaching any terminal state, or after `expiresAt + 1h` if never resolved.
    - `idempotency` records: deleted 24 hours after creation (see `expiresAt` field in §8.1).
    - `deviceIdentityKeys`: retained for the lifetime of the device registration; a revoked key's record is retained (marked `revoked`, not deleted) for audit purposes.
    - `activePairingLocks`: overwritten on every new session creation; not separately retained.
    - `PairRegistry`/`EnrollmentRecord` additions from this feature (`partnerUserId`, `pairingGeneration`, etc.): retained per the existing retention policy of those structures — **this must be reconciled against the actual existing policy, which was not supplied (§16, Open Question 1).**
    A full Kenya DPA legal review (purpose limitation, cross-border transfer given Firebase's processing region, and required privacy notice updates) is tracked as §16, Open Question 5, and is not substituted by this section.

---

## 11. Performance Requirements

These are benchmarking targets to validate post-launch, not hard-coded assumptions to build against blindly, and none of them should drive an algorithmic design change on their own:
- Cloud Function cold start should not push the visible "Waiting…" UI state past a few seconds on a first invocation; use `minInstances: 0` (cost-appropriate for this budget) and design the UI to tolerate a cold-start delay with a clear loading state rather than paying for `minInstances: 1`.
- Firestore listener updates should reflect a server-side state change within roughly 2 seconds under normal connectivity; FCM is a wake hint only and is not counted toward this target.
- Client-side crypto operations (X25519 keygen, HKDF, commit/reveal hashing) are expected to be fast relative to network latency on any supported device; if a benchmark shows otherwise on a low-end device, investigate the specific operation rather than assuming a fixed millisecond budget in advance.

---

## 12. Testing Requirements

### 12.1 Unit tests
- Commit/reveal hash construction and verification, including a deliberately mismatched reveal (must produce `FAILED`).
- Canonical transcript construction against the pinned test vector from §8.3 — exact byte layout, including a test that changing any single input byte changes the resulting SAS.
- All-zero X25519 output detection and rejection.
- Crockford Base32 encode/decode/normalize, including alias mapping and the mod-32 alphanumeric check character on both valid and corrupted inputs.
- HKDF derivation against the fixed test vector.
- Approval/unpair signed-payload byte layout (§8.4) against a pinned test vector, reproduced identically by both client code and server-side verification code.
- **[DONE 2026-09-28 — ADR #102 (task #48, user ask): adversarial matrix over the shipped ceremony — `PairingAdversarialTest`, 10 rows the existing suites did not pin (expiry boundary, stale-await refusal, self-pairing, replay idempotency, multi-pair + re-pair isolation, straggler give-up, cancel poll, clock-skew denial mapping, seeded stream); shared `PairingTestHarness` extracted from `PairingFlowTest`; found + fixed one boundary defect (`PairingToken.isExpired` aligned with the deployed rules' deny-at-deadline) and recorded the write-only vault hazard (ADR #102 ruling 3). Full JVM 1,258/154/0.]**

### 12.2 Cloud Function / integration tests (Firebase Local Emulator Suite)
- Full happy-path flow: create → join → commit → reveal → SAS confirm (both true) → approval (both) → `PAIRED`.
- Concurrent conflicting writes: initiator cancels while acceptor confirms SAS in the same instant — exactly one of these must win, and the resulting state must be a valid terminal or intermediate state, never both.
- **Concurrent session creation race:** two near-simultaneous `createPairingSession` calls from the same UID — verify `/activePairingLocks/{uid}` enforces exactly one becomes active and the other is immediately `CANCELLED (reason: superseded)`.
- **Idempotency, corrected semantics:** replay the exact same `requestId` for `createPairingSession`, `confirmSas`, and `submitApproval` after the underlying session has since expired — the replay must still return the originally cached, successful result, not `EXPIRED`.
- **Idempotency payload mismatch:** replay a `requestId` with a different payload — must be rejected, not silently re-served.
- Expiry: attempt a *new* (non-replayed) function call after `expiresAt` has passed but before the cleanup job has run — must be rejected with `EXPIRED`.
- Invitation token reuse: attempt a second `joinPairingSession` with an already-consumed token — must be rejected.
- Rate limits: exceed each limit in §10.5 and verify the resulting behavior, including that a mismatch-triggered rejection never locks the innocent counterpart's ability to start a new session.
- **Duplicate approval challenge request:** call `requestApprovalChallenge` twice for the same participant before submitting — verify the second call returns the same challenge, not a new one.
- **Stale unpair replay:** sign and submit a valid unpair request, then re-pair (new `pairingGeneration`), then replay the old signed unpair request — must be rejected.
- Security rules: attempt a direct client SDK write to any of the four new collections — must be denied. Attempt a read by a UID that is neither participant — must be denied.
- App Check: attempt a callable invocation with a missing/invalid App Check token — must be rejected before any business logic runs.
- **Function-level authorization (independent of Security Rules, since Admin SDK bypasses them):** authenticated UID A attempts to mutate or read a session it is not a participant of; UID A attempts to submit UID B's `deviceId` or approval challenge in a request payload — all must be rejected, and identity must be shown to always derive server-side from `context.auth.uid`, never from the payload.
- **Malformed/hostile input:** empty or oversized signatures, invalid base64, wrong public-key length, wrong nonce length, unexpected extra fields — every callable must reject these cleanly rather than throwing an unhandled exception.
- **Protocol version mismatch:** a request declaring an unsupported `protocolVersion` must be explicitly rejected with a clear error, not silently processed.

### 12.3 Device/manual tests
- Biometric enrollment change mid-flow: trigger `KeyPermanentlyInvalidatedException` during `submitApproval` and verify the app reaches `AUTH_REQUIRED`, re-registers a new key (new `keyVersion`) after re-authentication, and can resume (if session still unexpired) or must restart (if expired) — verify `PairRegistry` is untouched in this scenario.
- Kill the app process before reveal completes, and again after reveal but before approval — verify both cases correctly restart rather than attempt to resume, per §9.
- Airplane mode toggled mid-flow and restored — verify retry-with-same-`requestId` recovers cleanly, including after the session has expired in the interim (per the corrected idempotency ordering).
- Unpair with the partner device fully offline (airplane mode) — verify the requesting device still completes `UNPAIRED` immediately.
- A device with no `BIOMETRIC_STRONG`-class authenticator enrolled — verify approval/unpair is blocked with a clear message rather than silently falling back to a weaker method. **[Superseded by owner ruling 2026-09-28, ADR #112: a device with no enrolled `BIOMETRIC_STRONG` authenticator but with a secure lock screen must sign via the screen-lock confirm flow (harness leg at the time: `PairingTestActivity` → "Sign via screen lock"; the manual harness was removed 2026-11-05); the blocked-with-message behavior now applies only when neither biometrics nor a secure lock screen can authorize the key, and `SCREEN_LOCK_REJECTED` covers pre-#112 keys that refuse the fallback.]**
- A device without StrongBox — verify a TEE-backed key is still used successfully; a device that reports no hardware Keystore support at all — verify the explicit "unsupported device" message.

**[Status 2026-09-29 — these ran on the real handset.** X6532, wireless adb, warm 900 s signing window, `am instrument` by class list (ADR #122's rule). `PartnerApprovalInstrumentedTest` OK (labeled pairing: `labels: A sees 'Mama', B sees 'Musila'`; tray `'Approval request from Mama'` public text generic; `approval=PASS`); `PartnerUnpairIsolationInstrumentedTest` OK (`unpairIsolation=PASS remaining=11 deviceKeyAlive=true` — the #66 regression: unpairing one of N leaves the device agreement key alive and the remaining pairs working); `PartnerRoundTripInstrumentedTest` OK (`roundtrip=PASS base=0`); `PartnerDashboardInstrumentedTest` OK (`dashboard=PASS targets=[com.android.settings, com.android.chrome]`); `PartnerActivityLayerInstrumentedTest` OK (1 test, 80.8 s — the activity-layer e2e, `bIngestedA=1`). The offline-partner and mid-flow-kill scenarios are covered by the JVM/emulator halves (§12.1/§12.2); the biometric-re-enrollment path is ADR #112/#113's subject.]**

**[Status 2026-10-01 — first production ceremony between two physical devices.** X6532 (initiator) ↔ X6532C (partner), no harnesses, both devices `install -r`-fresh with the 2026-10-01 build. Typed code `9HBJ-TJYV-DDNM-T`; SAS **29244** matched visually on both screens; partner committed first (`finishJoin` 10:23:27.803) then the initiator registered (+25.7 s, after its own fingerprint approval); both devices landed on the paired terminal (initiator "Paired with Bravo", partner "Paired with Alpha" — label exchange cross-confirmed). Pair doc `mXyGJ…_xutCN…` `active`; same transcript hash, distinct hardware-attested key hashes (this closes the accountability plan §17.1 AC#1 caveat — cross-device key distinctness). The partner's approval used the prompt's new "Use screen lock" secondary button (ADR #112 revision, 2026-10-01) — verified live: `uiautomator` text dump showed the rendered button, the tap landed on the system credential screen ("Approve pairing"/"Confirm with your screen lock"), and the ceremony completed through it. Honest finding (ADR #125's card): the aborted first attempt (`install -r` killed the app mid-join) left the X6532C showing the "Pairing data was lost" re-pair card for a half-join that never registered — the diagnosis is honest and safe, but note the card can fire without a completed pair ever having existed.]**

**[Status 2026-10-01 (2) — first cross-device delivery on the real pair, both directions through the production pass.** A→B: the pairing snapshot + a composer message ("Thinking of you today.") drained 10:29/10:34; B pulled on its FCM wake (10:36), projection pinned, B dashboard shows "Their message to you — Thinking of you today.", per-pair tray id `-1137483685` / channel `partner_alerts` (ADR #119). B→A: the reverse message ("Proud of you for keeping going.", composed on B) first landed on the **cold 900 s signing window** — the production `Emit.Stop` path: `drain: 0` logged silently, row left unlinked, no prompt raised from background work (exactly as designed; the 10:53 periodic and 10:57 Check-now passes both returned cold). After one device unlock on the X6532C (11:01:31, PIN — the time-bound key re-arms only on real device authentication), the same Check-now pass signed, linked, and drained it: `partner_activity_link` n=1, `journal unlinked` 1→0; mailbox doc `3844e34c…` senderId `a010b109…` at 08:03:56.594Z; relay-dispatch `DISPATCHED` fcm 08:04:00.926Z (attempts=1); A's wake work `3dc40f95…` enqueued 11:04:00, SUCCEEDED `run_attempt=1`; notification posted 11:04:11 — **~15 s from tap to A's tray**. A's dashboard (tab re-entry) shows "Paired with Bravo" + "Their message to you — Proud of you for keeping going."; tray id matches B's (`-1137483685`); A census `projections=2 pinned=2`. Honest findings: (1) **WorkManager DOES dispatch on XOS — both X6532 and X6532C** (periodic partner-sync on the exact 15-min cadence, A `period_count=127`; FCM wake one-time work SUCCEEDED on both devices) — ADR #27's absolute "XOS never dispatches WM jobs" is corrected to "WM dispatch on XOS cannot be relied on for latency", which is what keeps Check now's in-process pass justified. (2) **Fresh-enrollment watermark contamination**: A's first pull for the minutes-old pair read its whole mailbox backlog (27 foreign docs, all verification-failed) and the not-pair-scoped waterfall query advanced `mailbox_watermark_ts` over them — the fresh card rendered "Last synced 1d ago" + the stale-sync copy until the first real message arrived (self-heals on any inbound). No tamper-counter pollution (that path is pair-guarded); candidate defect: pair-scope the pull query or floor the watermark at enrollment time. (3) Notification copy names the partner but never the text ("New activity from Bravo" / "Open MyExodus to see what changed.") — owner-flagged UX, deferred (task #11). (4) The ADR #112 "Use screen lock" fallback and the 900 s window semantics were exercised live.]**

---

## 13. Deployment Requirements

- Ship behind a feature flag; this flag also serves as the operational kill switch — disabling it immediately stops new pairing attempts with no data-corruption risk, since all in-flight state lives in the isolated `pairingSessions` collection and naturally expires within 15 minutes.
- Do not flip Strict Mode's partner-approval gate (OPD #16) until this feature has been live and stable for a measurable period defined by: at least N successful real pairings (N to be set once emulator testing is complete and a rough usage estimate exists), zero unresolved P0/P1 incidents, and no security-event anomalies in the abuse-tracking telemetry from §10.5 — not simply "one release cycle" as a calendar-based gate.
- Cloud Functions deployed as a new, independently deployable group, with explicit `maxInstances` set per function (start conservatively, e.g. 10, and raise only with observed legitimate traffic data), a per-function timeout of 30 seconds, and a bounded maximum request payload size (a few KB is more than sufficient for any payload this protocol defines — reject anything larger before it reaches business logic).
- Firestore security rules changes for the new collections are additive — do not modify existing rules for `PairRegistry`/`EnrollmentRecord` beyond what's needed to accept writes from the new finalization transaction.
- Rollback plan: disabling the feature flag stops new pairing attempts; in-flight sessions expire naturally with no data corruption risk.
- No schema migration is required for existing users — this feature only adds new collections and new fields to `PairRegistry`/`EnrollmentRecord` (additive), pending the reconciliation in §16, Open Question 1.
- Firebase project region for Firestore and Cloud Functions must be confirmed and recorded before launch (§16, Open Question 4) — this affects both latency expectations in §11 and the data-location aspect of the retention/DPA note in §10.12.

---

## 14. Monitoring Requirements

**[REALIZED 2026-09-28 — ADR #101 (task #37). Per-bullet rail map: (1) client `pairing_ceremony_outcome` events, 6 shipped terminals — Crashlytics; (2) `pairing_transcript_mismatch_count` log metric (labels uid, pair_id) — manual review, never an auto-lock; (3) dashboard "requests by response code" tile (`run.googleapis.com/request_count`) + 429 WARNING policy — native, no code; (4) `pairing_idempotency_replay_count` log metric = cache-hit numerator, denominator = bullet 3's request count; (5) dashboard cold-start tile, platform `run.googleapis.com/container/startup_latencies` p50/p95 (a hand-rolled boot log would be less honest, so it wasn't written); (6) metadata-only enforced by the telemetry class contract (pinned test) and by keeping diagnostics out of metric labels. Full mapping + honest limits in `app/docs/MyExodus Monitoring.md` §6.]**

Instrument the following as counters/histograms once the functional flow is verified:
- Session outcome counts by terminal state and `CANCELLED` reason (`user_cancelled` vs `superseded` vs `process_restart_required`) — a rising `REJECTED` (SAS mismatch) rate is a security signal, not just a UX metric.
- Cross-session, per-reporting-UID mismatch pattern counter from §10.5 — surfaced for manual review, not an automated lock.
- Cloud Function invocation counts and error rates per function name, plus `maxInstances`-throttling events — both a bug signal and a cost-control signal given the tight budget.
- Idempotency cache-hit rate per function — an unexpectedly high rate may indicate a client-side retry bug rather than genuine network instability.
- Cold-start latency distribution for the pairing functions, to validate the §11 target with real data.
- No SAS values, tokens, signatures, or key material in any metric label or log line (see §10.10).

---

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| Firestore/Function cost overrun from retry storms or abuse, given the ~9,000 KES total budget | Low-Medium | High | Rate limits, single-use tokens, idempotency, explicit `maxInstances`/timeout/payload-size ceilings (§13) | Firebase budget alerts; §14 invocation-rate and throttling monitoring |
| Signature verification cannot remotely prove hardware-backing (§5.7, §10.7) | Low | Medium | Documented, accepted limitation for V1; StrongBox/TEE required locally even without remote proof | Revisit if a future security review of the actual production threat model requires remote attestation |
| Existing `PairRegistry`/`EnrollmentRecord` schema conflicts with the fields this feature needs to add | Medium (unknown until checked) | Medium-High | §16 Open Question 1 must be resolved before step 7 of §7 begins | Code review against actual schema before implementation, not assumed from this document |
| Chosen X25519 library has compatibility or behavioral gaps across API 26–32 | Medium | High | §16 Open Question 2 must be resolved and validated with device testing before §7 step 12 begins | Device-matrix testing across the minSdk range |
| Solo developer runs out of implementation time before shipping | Medium | High | QR/CameraX explicitly deferred to V1.1; implementation sequence (§7) ordered so a partial build still yields a working typed-code flow before UI polish | Track §7 step completion against the timeline directly |
| Partner relationship used for coercive control (forced approval, blocked unpair) | Low-Medium | Critical | Hard unpair invariant (§10.8), no partner veto, no online-partner requirement, unpair scoped to `pairingGeneration` to prevent stale replay | Manual QA scenario in §12.3 (offline unpair test) must pass before ship |
| Device biometric re-enrollment destroys a partner relationship unexpectedly | Low | Medium | `AUTH_REQUIRED` path preserves `PairRegistry`; only local signing key resets, tracked via `keyVersion` **[Superseded 2026-10-01 — ADR #113 deliberately ships the stronger response: enrollment change → full Identity Migration, rotation = revocation, every pair must re-pair (the v2 in-place-refresh mitigation above was not built). The poll's transition pair (remove → migration #1 → re-add → migration #2) is device-exercised on the X6532 2026-10-01; the paired device was unpaired, so `reEnrollmentRequired=0` and the dialog half awaits a paired re-run.]** | Manual QA scenario in §12.3 |
| Play Integrity/App Check false-positive rejects legitimate users on non-GMS or custom-ROM devices common in the target market | Medium | Medium | Not mitigated in this build — accepted risk for V1 | Track App Check rejection rate once telemetry (§14) is live |
| A malicious participant uses false SAS mismatch reports as a denial-of-service tool | Low | Medium | Per-session rejection only, no symmetric account lockout (§5.19) | Cross-session abuse counter in §14 |

---

## 16. Open Questions

Everything else in this document is settled. These require external input before or shortly after implementation begins.

1. **Actual `PairRegistry`/`EnrollmentRecord` schema, collection paths, and existing invariants.** Not supplied during design. Must be obtained by reading the real codebase before §7 step 7/10, and the new fields this feature needs (`partnerUserId`, `partnerDeviceId`, `partnerIdentityKeyVersion`, `pairingGeneration`, `pairedAt`, `unpairedAt`) must be reconciled against whatever already exists there.
2. **X25519 library choice for API 26–32.** Must be a vetted third-party library (never hand-rolled), must expose raw 32-byte public/private values in the exact form §8.3 requires, and must be validated across the full minSdk range before implementation of §7 step 12 is considered complete.
3. **Whether V1's stated security property for biometric approval (§5.7/§10.7 — signature verification without remote hardware attestation) is acceptable to the product owner as a final decision, or whether a future revision should add Android Key Attestation.** This document decides against attestation for V1 on proportionality grounds; flagging it here only so the decision is visible for reconsideration, not because the decision itself is open.
4. **Firebase project region for Firestore and Cloud Functions.** Needed to validate the §11 latency targets and to complete the data-location aspect of the §10.12 retention/DPA note.
5. **Full Kenya DPA legal review** (purpose limitation, cross-border data transfer given Firebase's actual processing region, required privacy notice updates). §10.12 states this feature's retention *design*; it is not a substitute for that review.
6. **Target N (successful real pairings) for the §13 gate before flipping OPD #16 (Strict Mode's partner-approval gate).** To be set once emulator testing is complete and a rough usage estimate exists.

---

## 17. Acceptance Criteria

- A user can generate an invitation, share it, and a second device can join, complete SAS verification, both approve biometrically, and reach `PAIRED`, with `PairRegistry`/`EnrollmentRecord` correctly updated for both users, including a new `pairingGeneration`.
- Any of the following independently and correctly abort the flow without leaving the system in an inconsistent state: expired session, consumed token reuse, SAS mismatch (either side), commit/reveal verification failure, all-zero key agreement, biometric key invalidation, process death before reveal completes.
- A concurrent cancel-vs-approve race resolves to exactly one consistent outcome on both devices, not a split state.
- A concurrent double-`createPairingSession` race from the same UID resolves to exactly one active session, with the other correctly marked `CANCELLED (reason: superseded)`.
- Unpair succeeds immediately from either device, including when the partner device is offline, with no partner-side approval step anywhere in the code path, and a replayed unpair signature from a prior `pairingGeneration` is rejected after re-pairing.
- No client SDK write to any of the four new Firestore collections succeeds under the deployed security rules.
- No log, crash report, or telemetry event emitted by this feature contains a raw invitation token, private key, SAS value, approval/unpair signature, or the server pepper.
- All rate limits in §10.5 trigger correctly under the emulator test suite, and a series of SAS mismatch reports against one session never locks the non-reporting participant out of starting a fresh session with someone else.
- An idempotent replay of a committed request returns the original result correctly even after the underlying session has expired.

## 18. Definition of Done

- Open Questions 1 and 2 (§16) are resolved before implementation of the corresponding steps in §7 begins.
- All unit and integration tests in §12.1–12.2 pass, including the emulator-based concurrency, idempotency-ordering, active-session-lock, and function-level-authorization tests.
- The manual device test scenarios in §12.3 have been performed on at least one physical low/mid-range Android device and one higher-end device, including a device without StrongBox.
- Cloud Functions for this feature are deployed as an independently rollback-able group, with `maxInstances`, timeout, and payload-size limits configured per §13.
- Firebase budget alerts are configured before this feature is enabled for any real users.
- Monitoring in §14 is live and producing data.
- The risk register in §15 has been reviewed by the developer and no unmitigated Critical/High risk remains unaccepted.
- Open Questions 3–6 in §16 have each been either resolved or explicitly accepted as a deferred/tracked item before public release.

---

## 19. Existing System Contract appendix (§7 step 1 / Open Question 1 — RESOLVED 2026-09-28)

Produced by reading the actual code on `dev` @ 2026-09-28. **These are the real structures — nothing below is assumed.**

### 19.1 Server (Firestore, region us-central1 + Cloud Functions Node 22)

| Path | Shape | Writer |
|---|---|---|
| `/pairings/{pairId}` | `uidA`, `uidB`, `members[]`, `status` (`pending`/`active`/`terminated`), `pairingEpoch:int` (incremented on re-pair after terminate), `proofs{uid: {publicKeyHash, transcriptHash, protocolVersion, at}}`, `pendingSince`, `createdAt`, `activatedAt` | `registerPairing` (functions/index.js) — **only** writer; `enforceAppCheck:true`, `maxInstances:10`; activates only when both proofs land inside a 5-min window with equal `transcriptHash`; rejects `TRANSCRIPT_MISMATCH`, `SAME_KEY`, `SELF_PAIRING`, `PAIRING_ACTIVE`. Idempotent by proof equality. |
| `/pairing_tokens/{sha256hex(token)}` | `creatorUid`, `expiresAt`, `payload` (initiator CBOR `EnrollmentPayload`), then `partnerUid`, `partnerPayload`, `partnerSignature` | Client (rules-enforced): single-use update, TTL, pinned fields. Possession of the 256-bit token is the read capability. |
| `/mailboxes/{uid}/messages/{messageId}` (+ `/acks/{installationId}`) | ciphertext envelopes (`EventCipher`), `senderUid`, `pairId`, `keyEpoch`, `timestamp` | Client create (rules), relay function marks `relayStatus`; `retryDispatchSweep`; `cleanupMailboxes` is the only deleter. |
| `/users/{uid}/push_installations/{installationId}` | `provider` (`fcm`/`hms`), `token`, `platform`, `appVersion`, timestamps | Client. |
| `/rate-limits/**`, `/relay-dispatch/**` | server-only | Functions. |

Rules: client writes to `/pairings` denied except the one-way `active → terminated` status flip; `/pairing_tokens` as above; rules are the **secondary** layer (Admin SDK bypasses them) — same model §4 requires.

### 19.2 Client (Kotlin)

- **`EnrollmentRecord`** — Room entity `enrollments` (AccountabilityDatabase v5): `pair_id` PK, `my_sender_key_id`, `partner_signing_key_id`, `partner_signing_pubkey` (DER SPKI), `partner_agreement_pubkey` (DER SPKI), `created_at`, `mailbox_watermark_ts`, `pairing_epoch`. This is what §8.1 calls "the PairRegistry/EnrollmentRecord additions"; it already carries the identity material.
- **`AccountabilityKeyStore`** — EC P-256 signing + agreement keys, StrongBox→TEE narrow fallback (budget-safe, never software).
- **`AttestationVerifier`** (crypto/) — **Android Key Attestation chain verification already exists client-side**: PKIX path validation against two pinned Google attestation roots, KeyDescription parse (origin `GENERATED`, purpose includes `SIGN`, securityLevel ≥ TEE, challenge equality). 295 lines of device-caught parser detail (Keymaster's EXPLICIT SET encoding, caught on the X6532 2026-08-30).
- **`PairingFlow` / `EnrollmentFlow`** — the shipped ceremony protocol: token issuance → token-document exchange → mutual material verification (attestation + transcript signature) → SAS → biometric-signed `PairingProof` → `registerPairing` → `finalizePairing` (CEK derivation, `EnrollmentRecord` upsert, pairing key to `SecretsRepository`).
- **`EnrollmentTranscript`** — `T = "StrictMode-V1" || sessionId || min(pkA,pkB) || max(pkA,pkB)` (byte-order canonical, order-independent); **SAS = 5 decimal digits from the first 40 bits of SHA-256(T)**; `durablePairIdOf` = AD-02 pairId.
- **`PairingToken`** — 32 random bytes, 10-minute TTL, single-use; rendered as **QR only** (`PairingCodec`, CBOR).
- **`PairingProof` / `functions/proof.js`** — canonical signed proof message `MyExodus-Pairing-Proof-v1 \n protocolVersion \n ownUid \n partnerUid \n publicKeyHash \n transcriptHash`, ECDSA P-256/SHA-256 DER, verified server-side against the SPKI carried in the proof (bound by `publicKeyHash`).
- **Unpair** — shipped: biometric-gated, journal-first `BilateralDeletion` shred + `destroyAgreementKey()`; pair doc flips `terminated` via the one-way client rule. Unilateral; partner need not be online (hard invariant §10.8 already holds).
- **`PartnerScreen`** — post-pairing dashboard + unpair. No enrollment entry point exists in production.

### 19.3 Concept → code reconciliation (binding)

| This plan says | Real code | Ruling |
|---|---|---|
| `PairRegistry` | `/pairings/{pairId}` + `registerPairing` | **Reuse as-is.** No new registry. |
| `EnrollmentRecord` | Room `enrollments` entity | **Reuse as-is.** |
| `pairingGeneration` (16 B) | `pairingEpoch` (int, server-assigned) | **Map**: the relationship generation *is* `pairingEpoch`; encode big-endian into 16 B where a signature binds it. No parallel field. |
| `pairedAt` / `unpairedAt` | `activatedAt` / termination flips `status` | **Reuse.** |
| `deviceIdentityKeys/{uid}/{deviceId}` | `AccountabilityKeyStore` keys + proofs carrying `signingPublicKey` | **Superseded by shipped design** — the server receives the key inside each proof, hash-bound. No key-registry collection. (Owner's attestation directive is implemented by verifying that key's chain server-side, §19.4.) |
| §8.3 commit/reveal + X25519 + HKDF `SasMaterial`, 6-digit SAS | long-term EC agreement keys + transcript-bound SAS, 5 digits | **Superseded by shipped design.** Rewriting the transcript re-keys the pairwise CEK that `EventCipher` seals every mailbox event with — there is no seam for partial adoption, and the shipped flow is device-verified (ADR #55, X6532 + emulator runs). The *human* property §8.3 buys (both sides see the same number only if the transcript is identical) already holds; 5 vs 6 digits is not a security delta. |
| `sessionId` (16 B, server-generated, hex) | `PairingToken.sessionId` (8 random bytes hex, client-generated, per-attempt) | **Reuse** — same role (per-attempt transcript binding, distinct from the durable pairId). |
| Invitation **typed code** (§8.2 Crockford) | QR only | **BUILD** — the genuine V1 delta. Same `PairingToken`, new human-readable encoding, both renderings accepted by the same exchange. |
| `createPairingSession`/`joinPairingSession`/`revealKeys`/`confirmSas`/`submitApproval`/`unpair` callables | `registerPairing` + rules-mediated token doc | **Superseded by shipped design** (the state machine lives in the token doc + `registerPairing`). Their *security requirements* (§9 idempotency ordering, §10.5 rate limits) are real and are implemented against the shipped surface. |
| Server-side attestation verification | client-side `AttestationVerifier` (device-to-device) | **BUILD** (owner directive 2026-09-28 — overrides §5.7/§10.7/§16 Q3). |
| Ceremony UI (§7 step 14) | **missing** (OPD #25) | **BUILD** — the headline deliverable. |
| §10.5 rate limits | `/rate-limits` exists (relay only) | **BUILD** for the pairing path. |
| §10.12 retention / §7 step 11 cleanup | `cleanupMailboxes` exists | **BUILD** only for collections this work actually adds (none required by the delta); shipped cleanup covers shipped collections. |

### 19.4 What the delta builds (complete list)

1. **Typed invitation code** — Crockford Base32 encoding of the existing token (§8.2 rules: 12 data chars + mod-32 check char, `XXXX-XXXX-XXXX-C`, alias normalization), accepted alongside the existing QR on both roles. Unit-tested against §12.1. **[DONE 2026-09-28 — ADR #97: `PairingCode` + `PairingFlow.completeWithCode` + `PairingExchange.readTokenByValue`; token generation narrowed to the 60-bit typed domain (§17 decision 17); local check before any network call; `PairingCodeTest` 13/13 + `PairingFlowTest` 15/15; debug harness accepts code-or-QR. UI wiring joins item 4; device leg joins item 5.]**
2. **Server-side Key Attestation verification** (owner directive) — the proof carries the device's attestation chain; `registerPairing` validates PKIX to the pinned Google roots + KeyDescription (origin GENERATED, purpose SIGN, securityLevel ≥ TEE, challenge bound to the session) before accepting a proof. Port of the client logic; pinned test vectors. **[DONE 2026-09-28 — ADR #96: `functions/attestation.js` + 19 node tests + client wire fields; "challenge bound to the session" realized as challenge equality + signature-over-transcript by the attested key (long-term keystore key — recorded deviation). Deploy joins item 5.]**
3. **Pairing-path rate limits** (§10.5) + **idempotency** (§9 ordering) on the pairing callables. **[DONE 2026-09-28 — ADR #98: `pairing-guards.js` (requestId doc-id-safe validation, canonical requestHash excluding requestId, per-UID 1h window) + shared `rate-window.js`; `registerPairing` order = auth → §13 size ceiling (16 KB) → requestId → idempotency replay (hash match ⇒ committed result; mismatch ⇒ `REQUEST_ID_REUSED`) → rate-limit tx (`/rate-limits/pairing_{uid}`, 10/h) → proof → attestation → registration tx → best-effort cache write; `cleanupIdempotencyRecords` daily; rules deny `/idempotency/**`; client sends `requestId` (UUID v4, unsigned envelope field) and retries once with the identical payload on UNAVAILABLE/DEADLINE_EXCEEDED. Rulings: 5+10 ⇒ one 10/h cap (shipped surface can't tell roles); confirmSas(false) cap N/A (no such channel); mismatch counter = the `TRANSCRIPT_MISMATCH` log line (§14 wiring DONE — #37/ADR #101); fixed windows (AD-23 math); §13 timeout 30s. Node 70/70, JVM 1,219/0. Emulator-ordering leg DONE 2026-09-28 — ADR #100 (`emulator-test/`, 23/23; caught the inert-window defect — the counter was written but never read back — fixed in `advancePairingWindow` and redeployed). Deploy = #40.]**
4. **Production ceremony UI** — invite/join/SAS/biometric/terminal screens over the shipped `PairingFlow`, wired from `PartnerScreen`, built from `/Users/musila/Documents/ReferenceInspirationUI` via the component-reverse-engineer skill (owner directive: no online UI research). **[DONE 2026-09-28 — ADR #99: `PairingCeremonyScreen` (invite/join/typed-code boxes/SAS compare/biometric/terminal) entered from `PartnerScreen`; two-phase join (`prepareJoin`/`prepareJoinWithCode` → human SAS → `finishJoin`) so the check gates the proof on BOTH roles; §19.5 multi-user roster + switcher + per-pair scoping + label exchange (`PartnerLabel`, CBOR `"l"`, schema v11 `partner_label`); JVM 1,244/0 (+25). Device: roster/switch/persistence verified on the X6532 (8 real enrollments); fresh labeled pairing + unpair-one-of-N await one warm-window biometric tap (user's finger). Device-found cancel defect fixed in the same pass (`runCatching` swallowed the effect cancellation → cancelling an invitation rendered the expired terminal; now `runCatchingCancellable`, pinned by `RunCatchingCancellableTest`, re-verified on the X6532: code created → Cancel invitation → back on the invite entry). Known open defect recorded: #52 (single-valued `event_sequence` mirrors own activity to only one of N pairs) — **FIXED 2026-09-28, ADR #111: schema v12 per-pair mirror-link tables (`attempt_mirror_link`/`recovery_mirror_link`); `pendingEventCreation`/`pendingMirror` take a pairId and exclude rows already linked *for that pair*; `linkEvent` is `INSERT OR IGNORE` per pair (per-pair exactly-once); own activity fans out to every enrollment, each copy into that pair's own chain with its own CEK. JVM 1,298/0/0/0 incl. `multiPair_oneActivityRow_mirrorsToEveryPair_exactlyOnce`; real v11→v12 migration device-verified on the X6532; the emission half rides the same warm-window tap ask as §12.3).]**
5. **Tests** (§12.1 unit on the new encoders + attestation; instrumented full-ceremony on both roles; device runs) and **deployment** of the hardened function group + rules to the live project (owner-authorized). **Emulator/integration half DONE 2026-09-28 — ADR #100: `emulator-test/` suite (23 tests: 19 in-process handler runs against real Firestore + 4 HTTP-wire runs through the real callable transport with App Check/auth/attestation); it caught and root-fixed two live defects: the §10.5 pairing rate limit was inert (`advancePairingWindow` never read the persisted window back — counter written, never read) and the `merge:false` activation write destroyed `createdAt`; both redeployed to the live project with a post-deploy smoke probe (`UNAUTHENTICATED` without tokens). Functions unit 71/71. Deployment half DONE (#40). §12.3 device runs: roster/switch/persistence verified on the X6532; fresh labeled 2-user pairing + unpair-one-of-N await one warm-window biometric tap (user's finger, command in the progress note).**

### 19.5 Multi-user partner (owner ruling 2026-09-27 — binding on §19.4 item 4)

One partner account supports **multiple users at once**. The data layer already allows it — one `/pairings/{pairId}` per pairing (`pairId = min(uidA,uidB)_max(uidA,uidB)`), local `enrollments` keyed by `pair_id`, `EnrollmentDao.all()` already returns them all. The single-partner assumption lives only in presentation:

- `PartnerDashboardSource.load()` selects `.maxByOrNull { it.createdAtEpochMillis }` — every other supported user is silently invisible.
- `PartnerScreen` renders that one selection as "your partner".

**Requirements (build with the ceremony UI, task #49):**

1. The partner surface lists every active enrollment and lets the owner **switch** which user's dashboard is shown. Selected `pairId` is persisted UI state (survives process death), defaulting to the most recently activated.
2. Every stream — chain head, envelopes, approvals, mailbox watermark, relay rejections, unpair journal — is scoped to the selected `pairId` (all backends already take `pairId`; the selection is threaded through explicitly, never implicit "the" enrollment).
3. A label per supported user so the switcher is meaningful. **Decision (flagged for veto):** each side's self-chosen `user_profile.name`, sanitized and ≤32 chars, is exchanged during pairing (rides the existing token payload) and stored as `partnerLabel` on `EnrollmentRecord`; fallback label when absent. Recorded in Open Product Decisions.
4. Unpairing one of N pairings leaves the other N−1 intact — journal, keys, and mailbox state per pairId (test).
5. Tests (§12.1/§12.3): with ≥2 enrollments, switching changes every stream; watermarks isolated per pair; unpair one → the rest intact; device run covers a 2-user partner. **[§12.1 half DONE 2026-09-28 — ADR #99 (`PartnerDashboardSourceTest` scoped-stream tests; `PartnerSyncCoordinatorTest` per-pair remote steps; `PartnerLabelTest`; `EnrollmentPayloadTest`; migration +1); mirror fan-out per pair DONE — ADR #111 (`multiPair_oneActivityRow_mirrorsToEveryPair_exactlyOnce`: own activity reaches every enrollment exactly once, the second pair decrypting via its own CEK; legacy pre-v12 rows excluded for every pair). §12.3 half DONE 2026-09-29 on the X6532 (warm window, wireless adb): roster (11+ enrollments), switch re-scopes streams, selection survives process death — screenshots captured; real v11→v12 migration device-verified (`mirror-read`); **fresh labeled 2-user pairing** (`PartnerApprovalInstrumentedTest` OK — `labels: A sees 'Mama', B sees 'Musila'`, `tray: 'Approval request from Mama' (public: generic)`, `approval=PASS`); **unpair-one-of-N** (`PartnerUnpairIsolationInstrumentedTest` OK — `unpairIsolation=PASS remaining=11 deviceKeyAlive=true`); **the fan-out emission leg** (`PartnerRoundTripInstrumentedTest` OK — `roundtrip=PASS base=0`; `PartnerDashboardInstrumentedTest` OK — `dashboard=PASS targets=[com.android.settings, com.android.chrome]`).]**

**§19.5 UI revision note (2026-09-29, ADR #121, schema v14) — device-evidenced.** The multi-partner selection is now *visible* on every partner view (not just the hub): `PartnerContextBar` on each subpage ("Viewing / RoundTrip Partner" + **Switch**), the roster rows carry the rename pencil with selection re-seating the hero card, and the composer is addressed (**"To: RoundTrip Partner"**) with the pair captured at send time. Verified by live X6532 screenshots (no Compose test harness exists for these private surfaces) plus the dashboard/round-trip instrumented legs above.

**Revision (2026-09-29, ADR #121, owner request — binding on §19.5 items 1–3):** owner verbatim: *"also in the partner page we have so many operations and subpages but as we do them and move on even to the smallest thing like write a message, the partner needs to be able to see or change the partner, and it should syc all over in the partners pages and subpages"*. The multi-user selection was already persisted and per-pair scoping already threaded (§19.5 items 1–2, ADR #99) — the gap was that it was not **visible or switchable from every partner view**, selection was silently cleared on subpage navigation, and a composed message had no recipient binding. Delta shipped:
- `PartnerSelection` is the single persisted source of the selected `pairId` (survives process death, null = newest enrollment); switching reloads and **no longer clears the open subpage**, so the context survives navigation in both directions.
- `PartnerContextBar` ("Viewing <label>" + Switch when N>1) renders on every partner view; the switcher dialog reuses `PartnerUserRow` (same composable as the hub roster).
- `ComposeMessageDialog` names its recipient ("To: <label>") and `onSendMessage(pairId, text)` captures the pair **at send time**, so a switch mid-compose cannot misroute.
- Recipient binding at the data layer: `partner_activity_outbox.target_pair_id` (**schema v14**; `MIGRATION_13_14` additive `ALTER TABLE`, `MIGRATION_14_13` table recreate — SQLite <3.35 lacks DROP COLUMN — both copying all rows and recreating `index_partner_activity_outbox_created_at_ms`). `pendingForPair` predicate widens to `(target_pair_id IS NULL OR target_pair_id = :pairId)`. State snapshots stay **untargeted** (fan out to every enrollment per §19.5/ADR #111); motivational messages are **targeted** (Req 15 — a message is for its recipient only). Honest limit: an in-flight addressed row downgraded to v13 and re-upgraded fans out.
- Cognitive-load pass on the partner views (#97, same sweep): the pairing page's duplicate identity card folded into `SyncCard(showIdentity = true)` (4 cards → 3), and the three list sections gained count-bearing titles.
- Tests: `PartnerActivityJournalTest.targetedMessage_pendsOnlyForItsPair_whileSnapshotsStillFanOut` (addressing + cross-pair privacy on the real Room store); `AppRulesStoreMigrationTest` chain 2→14 end-to-end plus `migration13to14AndBack_dropsTheTargetColumn_andKeepsTheJournalRows` (both directions, index restored); the same two legs staged device-side in `AppRulesStoreMigrationInstrumentedTest` for the next harness run.
