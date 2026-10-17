# Partner SOS ("Reach Out" Nudge) — Final Implementation Specification

**Version:** 2.0  
**Platform:** Android (Kotlin) + Firebase  
**Scope:** V1, Android only  
**Audience:** AI coding agent / implementation engineer  
**Status:** Build specification — **shipped deviations (read before FR-1/FR-22):** (1) 2026-10-15, owner mandate / ADR #158 — the client Remote Config flag `partner_sos_enabled` was removed end-to-end; the Reach out control's visibility is now structural (Home tab + a live pair), and the server kill switch is the only operational control. (2) 2026-10-17 — Remote Config is scheduled for removal entirely and GitHub Releases becomes the APK channel (ADR #171). Both FR rows are historical intent; `app/docs/partner-sos-mapping.md` + ADR #158/#171 record what shipped.

---

## 1. Feature Overview

Build a single-tap **Reach out** control on the Home screen that lets a paired user send a small, end-to-end encrypted accountability nudge to the active accountability partner.

The sender experiences an immediate local action with no confirmation dialog, PIN, delay timer, or Strict Mode friction. The server accepts the nudge only when the pair is active, the sender is authenticated and App Check-valid, the sender is within the server-enforced cooldown and daily cap, and the feature kill switch is enabled.

The nudge is stored as a durable Firestore record. Push delivery is a separate asynchronous stage. Firebase Cloud Messaging (FCM) is a best-effort notification transport, not a delivery guarantee. Firestore remains the source of truth for the partner's in-app nudge card.

The partner receives one replaceable Android notification using a dedicated `partner_nudges` channel and fixed notification tag. While the partner application is open in the foreground, the existing `FirebaseMessagingService` must post the local notification immediately. When the app is backgrounded or the process is not running, the notification payload is handled by the FCM/Android notification path and the nudge is recovered from Firestore when the app opens.

The feature is **not an emergency service** and must never claim guaranteed, immediate, or life-critical delivery.

The user-facing send states are only:

- `Sending`
- `Sent`
- `Rate limited`
- `Not sent: offline`
- `Couldn't confirm`
- `Unavailable`

There is no sender-facing `Delivered`, `Displayed`, `Seen`, or `Acknowledged` state.

### Official platform references

- [Firebase Cloud Messaging: Android message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority)
- [Firebase Cloud Messaging: receive messages in Android apps](https://firebase.google.com/docs/cloud-messaging/android/receive-messages)
- [Firebase Cloud Functions: callable functions](https://firebase.google.com/docs/functions/callable)
- [Firebase Cloud Firestore triggers](https://firebase.google.com/docs/functions/firestore-events)

---

## 2. Requirements

### 2.1 Functional requirements

| ID | Requirement |
|---|---|
| FR-1 | Show the `Reach out` control only when the client Remote Config parameter `partner_sos_enabled` is true and the user has one active accountability pair. |
| FR-2 | ~~The control sends immediately with no confirmation dialog, PIN, timer, delay, or Strict Mode challenge.~~ **Superseded 2026-10-04 (owner mandate):** the control is a FAB that raises one confirmation dialog (Send/Cancel) before dispatching — no PIN, timer, delay, or Strict Mode challenge remain banned as before. |
| FR-3 | The client performs a validated-network preflight. When no validated network exists at tap time, show `No connection. Not sent.` and make no backend call. The client must not queue an offline callable request. |
| FR-4 | The server enforces a **sliding 10-minute cooldown** between accepted nudges for `(pairId, senderUid)`, using server time. A tap at exactly 10 minutes or later is eligible. |
| FR-5 | The server enforces a **daily cap of 12 accepted nudges** per `(pairId, senderUid)` using the `Africa/Nairobi` calendar day. Blocked/suppressed nudges consume the cooldown and daily cap exactly like delivered nudges. |
| FR-6 | Every send attempt has a client-generated UUIDv4 `nudgeId`. Repeating the same logical request with the same `nudgeId` is idempotent. Reusing a `nudgeId` with a different envelope is rejected. |
| FR-7 | The sender encrypts the constant plaintext `{"v":1,"kind":"nudge"}` using the existing versioned pair envelope format and AAD `partner_nudge|v1|{pairId}|{nudgeId}|{senderUid}`. **Rev 2026-10-04 (owner mandate):** v1 stays the no-message fallback; an optional user-written message travels as a **v2 plaintext inside the same seal** — deterministic CBOR `{v:2, kind:"nudge", text:…}`, NFC-normalized, ≤280 chars (EventPayload bound) and byte-bounded so the wire stays ≤1024 base64 chars. AAD unchanged (versioning lives inside the ciphertext); a v1 receiver opens a v2 envelope exactly as before (it null-checks the plaintext), and a v1 envelope has no message. The server still never parses the plaintext. |
| FR-8 | The ciphertext is stored in Firestore and never sent through FCM. |
| FR-9 | The server returns `accepted` after the nudge has been durably recorded, not after FCM delivery completes. |
| FR-10 | A recipient with `blocked=true` receives no push and no in-app card for the affected nudge. The sender receives the same `accepted` result as an unblocked recipient. |
| FR-11 | A recipient can block future nudges through an in-app setting. The block takes effect immediately for all pending, not-yet-sent nudges. Nudges already accepted by FCM cannot be retracted. |
| FR-12 | A recipient can mute the Android `partner_nudges` notification channel. Channel mute state is local Android state and is never returned to the sender. |
| FR-13 | A nudge that cannot be pushed remains available as an in-app card unless it was suppressed by recipient block or pair revocation before push. |
| FR-14 | The partner Home shows the most recent unacknowledged, non-suppressed nudge and its relative send time. |
| FR-15 | `I'm here` and `X` acknowledge every currently fetched, unacknowledged, non-suppressed nudge and set `ackedAt` exactly once for each document. |
| FR-16 | After acknowledgment, the client cancels notifications on the `partner_nudges` channel. |
| FR-17 | Each partner device has an installation registration. The server attempts push to all installation records that are eligible at the time the nudge's first dispatch claim snapshots its targets. |
| FR-18 | Android devices without Google Play services are supported for app-open Firestore synchronization only and do not receive FCM push. |
| FR-19 | The system notification uses channel `partner_nudges`, default importance, private lock-screen visibility, generic English text, and fixed Android tag `partner_nudge`. **Rev 2026-10-04 (owner mandate):** the channel id is `partner_nudges_v3` at **IMPORTANCE_HIGH** — DEFAULT was device-verified as tray-only (no heads-up banner), Android cannot raise an existing channel's importance in place, and the app can even silently LOWER its own channel (the pre-fix post path dented `_v2` 4→3 on every foreground receipt, device-confirmed on both rig devices) — so a versioned id takes HIGH and every prior id (`partner_nudges`, `partner_nudges_v2`) is deleted at startup; the post path creates a channel only when missing (recorded as D14 in the mapping doc). Channel-id mentions in FR-12/FR-16 and Step 7/10/11 text read `partner_nudges_v3` accordingly; fixed tag `partner_nudge`, private visibility, generic text unchanged. |
| FR-20 | Foreground FCM receipt must result in a locally posted notification. The existing `FirebaseMessagingService` must not simply return for `partner_nudge`. |
| FR-21 | Background notification+data delivery uses the normal Android system notification path. The data payload is `type=partner_nudge` and `nudge_id=<UUID>`. |
| FR-22 | The feature is disabled by either the client Remote Config flag or the authoritative server kill switch. The server kill switch is checked on every `sendNudge` request and is not cached. |
| FR-23 | The existing mailbox relay is not used by Partner SOS. Partner SOS uses the dedicated callable ingress and nudge collections defined here. |
| FR-24 | The existing accountability envelope format is reused. No second encryption implementation is created. |
| FR-25 | Help/onboarding text states that Reach out is not an emergency service and directs users who need urgent help to their local emergency number. The tap path itself never displays that warning. |

### 2.2 Non-functional requirements

- **Sender result latency:** `sendNudge` p95 ≤ 2 seconds on the production-region backend under normal load; client tap-to-result p95 ≤ 5 seconds on a connected 4G device.
- **Push stage:** asynchronous; push latency is measured separately from sender response latency.
- **Server function region:** use the same region as the project's Firestore database unless the existing project is already standardized on a documented adjacent region.
- **Cost:** no new external paid infrastructure. Use Cloud Functions 2nd gen, Firestore, FCM, App Check, Remote Config, and one Cloud Scheduler repair job.
- **Function instances:** `sendNudge` `minInstances=0`, `maxInstances=20`; push trigger `minInstances=0`, `maxInstances=10`; repair scheduler `maxInstances=1`, with application-level lease enforcement.
- **Retention:** nudge documents and queue documents expire after 7 days through Firestore TTL. TTL is cleanup only and never correctness-critical.
- **FCM TTL:** 3600 seconds.
- **Privacy:** server never receives plaintext nudge content; FCM never carries ciphertext.
- **Distribution:** Android Play and APK distribution use the project's existing Firebase configuration and App Check configuration documented in Step 0. Play Integrity settings must support the actual distribution modes in use.
- **Language:** English only for V1.
- **No unrelated refactor:** do not redesign the existing mailbox, pairing, blocker, milestone notification, or relapse notification systems beyond the exact integration points listed here.

### 2.3 Explicit invariants

1. One active pair exists per user.
2. One accepted logical nudge corresponds to exactly one `nudges/{nudgeId}` document.
3. The same `nudgeId` never consumes the cooldown or daily cap twice.
4. Only one dispatcher may own a nudge's push lease at a time.
5. External FCM sends are treated as at-least-once side effects; a crash after FCM acceptance may cause a replacement send after lease recovery. The fixed Android notification tag prevents tray stacking for the feature's single active notification.
6. A pending nudge cannot disappear solely because a dispatcher crashed.
7. A queue document is deleted only after the authoritative nudge state has been set to a terminal state or the nudge has been suppressed.
8. A pair revocation or recipient block prevents any not-yet-sent nudge from being pushed.
9. Push failure never destroys the underlying nudge record.
10. Firestore Security Rules are the client-side data-access boundary; Cloud Functions/Admin SDK are the server-side privileged boundary.

---

## 3. Existing System Context

### 3.1 Existing application

The Android app is Kotlin-based and already contains:

- Firebase Authentication.
- Cloud Firestore.
- Firebase Cloud Messaging.
- Firebase App Check.
- Remote Config.
- Existing FCM service infrastructure.
- An accountability pairing system.
- A versioned end-to-end encrypted pair envelope using the existing shared pair secret.
- A local blocker based on `VpnService` and Strict Mode.
- A Compose Home screen and ViewModel.
- Existing notification infrastructure for milestone and relapse notifications.
- An existing device-registration collection under `/users/{uid}/devices/{installId}`.

### 3.2 Existing components that remain unchanged

Do not create replacements for:

- the existing pair record;
- the existing envelope encryption/decryption helper;
- the existing device registry;
- the existing `FirebaseMessagingService`;
- the existing Home screen architecture;
- the existing blocker engine;
- the existing mailbox relay;
- existing milestone/relapse notification flows;
- existing Gradle Firebase dependency management.

Integrate into these components rather than creating parallel implementations.

### 3.3 Required backend runtime baseline

The functions package must run on **Node.js 22 or later** and use a current Firebase Admin SDK release compatible with Node 22 and FID-targeted messaging. At the time of this specification, Firebase Admin Node SDK 14.x requires Node 22+ and recommends FID-based targeting while legacy token-based properties are deprecated.

Keep the existing Firebase project and function framework. Update only the backend runtime/Admin SDK baseline necessary for this feature.

Official references:

- [Firebase Admin Node.js release notes](https://firebase.google.com/support/release-notes/admin/node)
- [Firebase Admin SDK FCM send documentation](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk)

### 3.4 Step-0 mappings

Before modifying code, create `docs/partner-sos-mapping.md` containing the exact project-specific values for:

- Android `applicationId`.
- Firebase Functions region.
- Firestore region.
- Existing active pair path.
- Existing pair-members field.
- Existing pair-active field/logic.
- Existing envelope encrypt helper.
- Existing envelope decrypt helper.
- Existing FCM service class.
- Existing device-registry path and ownership rules.
- Existing Home composable/ViewModel.
- Existing pairing-success screen.
- Existing VPN builder location.
- Existing Firebase Android BoM version.
- Existing Functions Node version.
- Existing Firebase Admin SDK version.

Every implementation step below refers to these mappings.

### 3.5 VPN boundary

The blocker owns the app's `VpnService`. The final implementation uses `VpnService.Builder.addDisallowedApplication()` for:

- the application's own package;
- `com.google.android.gms` when installed.

This is an intentional application-wide VPN exclusion, not an SOS-only socket exclusion. No custom OkHttp `SocketFactory` is used for Firestore, and no attempt is made to inject a custom transport into the Firestore SDK.

The blocker must otherwise remain unchanged.

Official reference:

- [Android `VpnService.Builder.addDisallowedApplication`](https://developer.android.com/reference/android/net/VpnService.Builder)
- [Android `VpnService.protect`](https://developer.android.com/reference/android/net/VpnService)

---

## 4. Architecture

### 4.1 High-level architecture

```text
Sender Android
    |
    | Firebase Callable: sendNudge
    | Auth + App Check
    v
sendNudge (2nd gen)
    |
    | one Firestore transaction
    | validate pair + block + cooldown + daily cap + idempotency
    | create nudge + queue atomically
    v
/pairs/{pairId}/nudges/{nudgeId}
/nudgePushQueue/{nudgeId}
    |
    +------------------------------+
    |                              |
    | onDocumentCreated            | scheduled repair every 5 min
    v                              v
pushNudge() <----------------- resumePendingNudges()
    |
    | atomic dispatch lease
    | target snapshot
    | FCM FID multicast/batches
    v
Recipient Android devices
    |
    +--> system notification
    |
    +--> app opens / foreground receive
                |
                v
        Firestore nudge listener
                |
                v
          NudgeCard / ack
```

### 4.2 Components

| ID | Component | Responsibility | State owned |
|---|---|---|---|
| A1 | `SosViewModel` | Home button state machine, nudgeId/envelope lifetime, user-visible result mapping | UI state |
| A2 | `NudgeRepository` | Build plaintext, encrypt with existing envelope helper, call `sendNudge` | in-memory pending request |
| A3 | `VpnService` integration | Exclude app package and GMS package from the local VPN | VPN configuration |
| A4 | `DeviceRegistrar` | Keep installation/FID record and `pushEligible` fresh | device document + local registration cache |
| B1 | `sendNudge` callable | Authentication, App Check, feature flag, pair authorization, block check, rate limiting, idempotency, atomic persistence | `nudgeState` + nudge/queue documents |
| B2 | `onNudgeQueueCreated` trigger | Immediate asynchronous dispatch of newly queued nudge | dispatch lease + nudge status |
| B3 | `resumePendingNudges` scheduled function | Repair stalled or retryable push jobs | queue recovery |
| B4 | `pushNudge` shared service | Acquire lease, snapshot targets, send FCM, record per-device result, schedule retry or terminate | queue + nudge status |
| B5 | Firestore Security Rules | Client read/ack/settings/device enforcement | access policy |
| C1 | `NudgeInboxRepository` | Listen for current user's non-suppressed unacknowledged nudges | none |
| C2 | `NudgeCard` | Render most recent nudge and acknowledge | none |
| C3 | Existing `FirebaseMessagingService` | Foreground notification rendering and existing message handling | none |
| C4 | Notification setup | Channel creation and permission/settings handling | Android system channel |
| C5 | Settings | Partner block, channel settings link, notification-disabled banner | block setting + OS setting |

### 4.3 Firestore data model

#### `/pairs/{pairId}/nudgeState/{senderUid}`

Admin SDK only.

```text
lastAcceptedAtMs: number
lastNudgeId: string
 dayKey: string                    // YYYY-MM-DD in Africa/Nairobi
 dayCount: number
```

This document is the sole rate-limit state for the `(pairId, senderUid)` stream.

#### `/pairs/{pairId}/nudges/{nudgeId}`

Created and updated only by Admin SDK; read/acknowledged by the recipient.

```text
senderUid: string
recipientUid: string
envelope: string
envelopeVersion: number            // 1
createdAt: Timestamp
createdAtMs: number
expiresAt: Timestamp                // createdAt + 7 days
status: string                      // pending|sent|partial|suppressed|no_eligible_device|failed
suppressedAt: Timestamp | null
ackedAt: Timestamp | null
pushAttempts: number
lastPushAtMs: number | null
targetDeviceCount: number
sentDeviceCount: number
lastPushErrorCode: string | null
terminalAt: Timestamp | null
```

`status` meanings:

- `pending`: accepted and queued, or retryable push work remains.
- `sent`: all targeted devices accepted by FCM.
- `partial`: at least one targeted device accepted by FCM and at least one other target became terminally unsuccessful.
- `suppressed`: blocked or pair-revoked before push; recipient must not see a card.
- `no_eligible_device`: no eligible push target existed at first target snapshot; the nudge remains available in-app.
- `failed`: all targeted devices failed terminally without any successful target; the nudge remains available in-app.

#### `/nudgePushQueue/{nudgeId}`

Admin SDK only. One queue document exists only while push work is active or retryable.

```text
pairId: string
createdAtMs: number
expiresAt: Timestamp
nextAttemptAtMs: number
attemptCount: number
leaseId: string | null
leaseUntilMs: number | null
targetInstallIds: string[]
sentInstallIds: string[]
```

`targetInstallIds` is the immutable target snapshot established by the first successful dispatch claim. New device registrations do not get added to an already-created nudge.

`sentInstallIds` records targets for which FCM accepted the current logical nudge so later retries do not resend to successful targets.

#### `/pairs/{pairId}/nudgeSettings/{uid}`

User-owned document.

```text
blocked: boolean
updatedAt: Timestamp
```

#### `/users/{uid}/devices/{installId}`

User-owned. The document ID is the Firebase Installation ID (FID).

Existing fields remain intact. Partner SOS requires:

```text
fcmToken: string                  // retain if existing features use it
pushEligible: boolean
appVersion: string
updatedAt: Timestamp
```

The push service targets **FIDs**, not the deprecated token field, when the installed Admin SDK supports FID targeting. The existing `fcmToken` field remains for compatibility with other application features.

> **Rev 2026-10-04 (verified on the rig):** FID sends fail with `installation-id-not-registered`
> for every installation registered through the app's legacy `getToken()` path (FID targeting
> only reaches installations that re-registered via `register()`/`onRegistered()`, FCM SDK
> 25.1+) — while the same installations' stored registration tokens deliver. The dispatcher
> therefore sends to the registry's stored `token` field (`messages.sendEachForMulticast`),
> keeping the FID as the doc id / ledger / ack identity. Token targeting is supported during
> FCM's deprecation window; revisit when the client migrates to the new registration API.
> Recorded as D13 in `app/docs/partner-sos-mapping.md`; code comment on `nudgeFcmMessage`.

#### `/config/features`

Admin SDK only.

```text
partnerSosEnabled: boolean
```

### 4.4 Push queue lifecycle

```text
queue created
    |
    v
available
    |
    v
LEASED (leaseId + leaseUntilMs)
    |
    +---- recipient blocked/unpaired ----> nudge SUPPRESSED + queue deleted
    |
    +---- no targets --------------------> nudge NO_ELIGIBLE_DEVICE + queue deleted
    |
    +---- all targets accepted ----------> nudge SENT + queue deleted
    |
    +---- some targets terminal fail ----> nudge PARTIAL/FAILED + queue deleted
    |
    +---- transient failure -------------> queue retained with nextAttemptAtMs
    |
    +---- lease expires -----------------> repair worker may reclaim
```

### 4.5 Dispatch lease rules

- Lease acquisition is an atomic Firestore transaction.
- A dispatcher with an unexpired lease may send.
- A dispatcher without the current lease must not send.
- Lease duration is 90 seconds.
- A new lease may be acquired only when `leaseId == null` or `leaseUntilMs <= now`.
- Every result write must verify the same `leaseId` before modifying the queue or nudge.
- Queue deletion is performed in the same finalization transaction that sets the terminal nudge status.
- The system does not claim exactly-once FCM delivery. It minimizes duplicate sends and tolerates the irreducible crash window between external FCM acceptance and durable finalization.

### 4.6 FCM message contract

Use the Firebase Admin Node.js SDK FID-based multicast API.

The logical message is:

```text
notification:
  title = "Accountability Check-in"
  body  = "Your partner reached out — check in when you can."

data:
  type    = "partner_nudge"
  nudge_id = <UUIDv4>

android:
  priority = "high"
  ttl = 3600000                    // Admin SDK object: milliseconds
  restrictedPackageName = <applicationId>
  notification:
    channelId = "partner_nudges"
    tag = "partner_nudge"
    visibility = "private"
```

Do not include:

- ciphertext;
- pair ID;
- sender UID;
- recipient UID;
- event plaintext;
- APNs configuration;
- `collapse_key`.

FCM notification messages are collapsible by FCM transport semantics, while Android `tag` controls replacement of the displayed notification. These are separate mechanisms and must not be conflated.

Official references:

- [Firebase Admin SDK: send FCM messages](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk)
- [FCM Android message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority)
- [FCM collapsible/non-collapsible messages](https://firebase.google.com/docs/cloud-messaging/customize-messages/collapsible-message-types)
- [FCM HTTP v1 message resource](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages)

---

## 5. Architecture Decisions

1. **Use one authenticated callable ingress `sendNudge`;** it provides a small, explicit server API with automatic Firebase Auth/App Check integration.  
2. **Persist the nudge and push queue in one Firestore transaction;** durable acceptance cannot exist without durable recovery work.  
3. **Use a sliding server-side cooldown plus Nairobi daily cap;** the server, not the client clock, owns abuse controls.  
4. **Use one nudge document for every accepted logical nudge;** all terminal and non-terminal outcomes share one authoritative state record.  
5. **Use a root-level push queue;** it separates durable business data from dispatch work and avoids collection-group polling as the primary repair mechanism.  
6. **Dispatch asynchronously through `onDocumentCreated`;** sender latency is independent of FCM latency and failures.  
7. **Use a scheduled repair job only for recovery;** the normal path remains event-driven without adding Cloud Tasks.  
8. **Use an atomic lease;** Firestore transaction idempotency does not make external FCM sends exactly once.  
9. **Use FID-targeted FCM sends;** current Firebase Admin SDK documentation recommends FIDs while token-targeting APIs are deprecated.  
10. **Snapshot target installations on first dispatch;** a nudge must not unexpectedly fan out to devices registered after the nudge was accepted.  
11. **Retry at the queue level rather than doing multiple FCM retries inside one function invocation;** this keeps leases simple and makes crash recovery deterministic.  
12. **Use high-priority hybrid notification+data FCM;** the notification payload enables system-level tray display while data provides a deterministic nudge identifier.  
13. **Post a local notification when the app is foregrounded;** Firebase delivers notification+data to `onMessageReceived()` in the foreground.  
14. **Keep Firestore as the in-app source of truth;** FCM only signals the partner that a nudge exists.  
15. **Use `partner_nudge` as a fixed Android notification tag;** the active-pair-per-user constraint makes one replaceable tray entry sufficient.  
16. **Treat recipient block as server-side suppression;** sender responses never reveal whether suppression occurred.  
17. **Treat channel mute as Android-local state;** the backend does not need to know or store a user-controlled channel mute.  
18. **Use `addDisallowedApplication` for the app and GMS;** the current Firebase/Firestore transport cannot be given an SOS-specific custom socket factory, so the existing app-owned VPN must exclude the required network principals globally.  
19. **Use App Check enforcement on the callable only;** extending Firestore App Check enforcement globally would change unrelated existing backend behavior.  
20. **Do not cache the server kill switch;** the kill switch must take effect on the next request without a cache window.  
21. **Use Firestore TTL only for cleanup;** correctness never depends on TTL execution timing.  
22. **Use no new backend database or queue product;** the expected V1 volume does not justify Cloud Tasks or Redis.  
23. **Keep the feature online-only;** this eliminates offline burst flushing and gives the sender an honest `Not sent` result.  
24. **Use generic notification text and no plaintext payload data beyond `type` and `nudge_id`;** the recipient must not see sensitive habit information on the lock screen.  
25. **Use Node 22+ and current Admin SDK FID APIs;** this matches the current supported Firebase Admin runtime and messaging API direction.  

---

## 6. Data Flow

### 6.1 Sender tap

1. Home renders `Reach out` only when Remote Config is enabled and the pair is active.
2. On tap, `SosViewModel` creates exactly one UUIDv4 `nudgeId` for that attempt and enters `Sending(nudgeId)`.
3. Additional taps while `Sending` are ignored.
4. The repository checks `ConnectivityManager` for a validated network.
5. If no validated network exists, transition to `NotSentOffline`; do not call Firebase.
6. Build plaintext exactly:

```json
{"v":1,"kind":"nudge"}
```

7. Encrypt with the existing envelope helper and AAD:

```text
partner_nudge|v1|{pairId}|{nudgeId}|{senderUid}
```

8. Retain the exact `(nudgeId, envelope)` pair in memory for the lifetime of this user attempt. A retry after `Couldn't confirm` must reuse both values rather than re-encrypting under the same `nudgeId`.
9. Call `sendNudge`.

### 6.2 `sendNudge` callable

The callable performs:

1. Automatic callable transport validation of Auth/App Check.
2. Explicit validation that `req.auth` exists.
3. Read and verify `/config/features.partnerSosEnabled` inside the transaction. If false, return `failed-precondition: feature_disabled` with no writes.
4. Read the active pair inside the same transaction.
5. Verify the authenticated UID is exactly one of the pair members.
6. Derive `recipientUid` as the other member of the pair.
7. Read the recipient's `/nudgeSettings/{recipientUid}` inside the transaction.
8. Read `nudgeState/{senderUid}` inside the transaction.
9. Read `nudges/{nudgeId}` and the corresponding `/nudgePushQueue/{nudgeId}` inside the transaction for idempotency/integrity checking.
10. Use one fixed server `nowMs` value for the request.
11. Verify the pair is active.
12. If the nudge document already exists:
    - If `senderUid` differs from the authenticated UID, reject with `permission-denied`.
    - If the envelope differs from the stored envelope, reject with `invalid-argument: nudge_id_reuse`.
    - Otherwise return `accepted` and do not alter cooldown, cap, queue or nudge state.
13. If the nudge document does not exist, enforce cooldown and daily cap from `nudgeState`.
14. Derive the Nairobi `dayKey` using explicit `Africa/Nairobi` calendar formatting. Do not use the process timezone.
15. If cooldown is active, return `rate_limited` and write nothing.
16. If daily cap is exhausted, return `rate_limited` and write nothing.
17. Update `nudgeState` atomically.
18. Create the nudge document.
19. If the recipient is currently blocked, create the nudge with `status=suppressed`, `suppressedAt=serverTimestamp`, no queue document, and return `accepted`.
20. Otherwise create the nudge with `status=pending` and create the corresponding queue document with `nextAttemptAtMs=nowMs`, `attemptCount=0`, no lease, and empty target/sent arrays.
21. Commit the transaction.
22. Return `{status:"accepted", nudgeId}` immediately.

The callable never invokes FCM directly.

### 6.3 Immediate push dispatch

`onNudgeQueueCreated` triggers on creation of `/nudgePushQueue/{nudgeId}`.

The trigger calls `pushNudge(pairId, nudgeId)` and does not mutate unrelated records.

### 6.4 `pushNudge`

1. Acquire the queue lease transactionally.
2. If the queue is terminal, missing, expired, or has an active unexpired lease owned by another worker, exit without sending.
3. Set `leaseId` to a random opaque ID, `leaseUntilMs=now+90s`, increment `attemptCount`, and set the nudge status to `pending`.
4. Read the current pair and recipient block state **after lease acquisition**.
5. If the pair is inactive or the recipient is blocked:
   - transactionally confirm the same `leaseId`;
   - set nudge `status=suppressed`, `suppressedAt=serverTimestamp`, `terminalAt=serverTimestamp`;
   - delete the queue document in the same transaction;
   - stop.
6. If `targetInstallIds` is empty, query the recipient's device registry for `pushEligible == true` and snapshot the resulting document IDs into `targetInstallIds` in a lease-checked transaction.
7. If no eligible target exists:
   - set nudge `status=no_eligible_device`;
   - set terminal fields;
   - delete queue in the same transaction;
   - stop.
8. Compute `pendingInstallIds = targetInstallIds - sentInstallIds`.
9. Read current device documents for those installation IDs.
10. Drop targets that no longer exist or are no longer eligible. They are terminally skipped for this nudge rather than re-added later.
11. Resolve each remaining installation ID to its current FID, which is the document ID.
12. Send the same notification payload to FIDs in batches of at most 500 using the Admin SDK FID multicast API.
13. Do not include ciphertext in the message.
14. Process individual FCM responses by target order.
15. Add successful installation IDs to `sentInstallIds`.
16. Delete a device registration when FCM returns a confirmed invalid/unregistered registration result.
17. For transient server/quota/rate-limit failures, leave the installation ID pending and compute `nextAttemptAtMs` using exponential backoff with jitter.
18. For a malformed message error, do not retain the device as healthy. Mark the nudge terminally failed and emit an alert; malformed message errors are engineering defects, not device-specific retry conditions.
19. Finalize only if the queue lease is still owned by this worker.
20. If every target is in `sentInstallIds`, set nudge `status=sent`, update counts, delete queue.
21. If some targets were successfully sent and the remaining targets are permanently unsuccessful after the retry budget is exhausted, set `status=partial`, update counts, delete queue.
22. If no target was successfully sent and all remaining work is terminally unsuccessful after the retry budget, set `status=failed`, delete queue.
23. If transient failures remain and `attemptCount < MAX_PUSH_ATTEMPTS`, clear the lease, set `nextAttemptAtMs`, retain queue.
24. If `attemptCount >= MAX_PUSH_ATTEMPTS`, set terminal `failed` or `partial`, delete queue, and emit `nudge_stuck`.

### 6.5 Retry policy

`MAX_PUSH_ATTEMPTS = 4` means four **dispatch claims**, not four FCM RPC retries inside one invocation.

Base retry schedule:

- attempt 1 failure: next attempt no earlier than 60 seconds;
- attempt 2 failure: next attempt no earlier than 120 seconds;
- attempt 3 failure: next attempt no earlier than 240 seconds;
- attempt 4 failure: terminal.

Add random jitter of ±25% to each delay.

For quota/rate-exceeded responses, use at least the service-provided retry delay when the underlying SDK exposes one; otherwise use the same queue-level exponential backoff with a minimum 60-second delay.

Official references:

- [FCM error codes](https://firebase.google.com/docs/cloud-messaging/error-codes)
- [FCM scaling and retry guidance](https://firebase.google.com/docs/cloud-messaging/scale-fcm)

### 6.6 Repair scheduler

`resumePendingNudges` runs every 5 minutes.

Query only `/nudgePushQueue` using:

```text
where nextAttemptAtMs <= now
orderBy nextAttemptAtMs ascending
limit 50
```

For every returned queue document:

1. Derive `pairId` and `nudgeId` from the queue document.
2. Call `pushNudge`.
3. `pushNudge` decides whether the job is claimable.
4. Do not assume scheduler invocation serialization; the lease is the correctness mechanism.
5. Do not delete queue documents directly from the scheduler.

Queue records older than 7 days are expired by TTL. Any remaining queue older than the nudge's `expiresAt` is finalized as terminal `failed` or `suppressed` according to current state before cleanup.

### 6.7 Recipient foreground receive

When the app is foregrounded and `FirebaseMessagingService.onMessageReceived()` receives `type=partner_nudge`:

1. Validate that `nudge_id` is present and has UUIDv4 syntax.
2. Build/post the generic local notification immediately using channel `partner_nudges` and tag `partner_nudge`.
3. Do not decrypt the payload in the FCM callback.
4. Do not perform a network request from `onMessageReceived()`.
5. Do not mutate Firestore from the callback.
6. Return.

The Home Firestore listener independently renders the card.

Firebase explicitly documents that foreground notification+data messages reach `onMessageReceived()` while background notification+data messages place the notification in the system tray and make the data available through the launcher intent.

Official reference:

- [Receive FCM messages in Android apps](https://firebase.google.com/docs/cloud-messaging/android/receive-messages)

### 6.8 Recipient background/killed receive

When the app is backgrounded:

1. FCM displays the notification using the notification payload.
2. The user taps the notification.
3. Android launches the app launcher intent.
4. `MainActivity` checks for `type=partner_nudge` and `nudge_id`.
5. Navigate to Home.
6. Home attaches the Firestore listener.
7. The listener loads the current nudge document.
8. The app decrypts the Firestore envelope with the existing envelope helper and identical AAD construction.
9. The notification is not used as the source of truth for content.
10. Remove the handled intent extras after navigation.

### 6.9 Recipient card and acknowledgment

Home query:

```text
/pairs/{pairId}/nudges
  where recipientUid == currentUid
  where suppressedAt == null
  where ackedAt == null
  orderBy createdAt descending
  limit 10
```

The client ignores any record whose `expiresAt` is in the past even if TTL has not deleted it yet.

Decrypt only the newest document needed for the card.

If decryption succeeds, render the card.

If decryption fails:

- record a Crashlytics non-fatal with safe metadata only;
- do not log envelope/ciphertext/AAD;
- do not display plaintext derived from the malformed document;
- acknowledge that malformed document only if it is provably unusable and the existing product policy allows silent terminal cleanup.

When the user taps `I'm here` or `X`:

1. Update every fetched unacknowledged non-suppressed nudge with `ackedAt=serverTimestamp()`.
2. Do the update as a single batched write.
3. Security Rules allow only the recipient to perform this exact field update.
4. After the batch succeeds, cancel all active notifications whose channel is `partner_nudges` and whose tag is `partner_nudge`.
5. A later Home query must not return the acknowledged documents.

---

## 7. Implementation Sequence

### Step 0 — Project mapping and baseline verification

Create `docs/partner-sos-mapping.md` and resolve every project-specific mapping listed in Section 3.4.

Verify backend Node 22+ and current Admin SDK FID support. Do not create a second device registry or encryption helper.

**Validation:** mapping file complete; build still passes with no feature changes.

### Step 1 — Firestore model, rules, indexes, TTL, feature config

Add:

- `/pairs/{pairId}/nudgeState/{senderUid}`;
- `/pairs/{pairId}/nudges/{nudgeId}`;
- `/nudgePushQueue/{nudgeId}`;
- `/pairs/{pairId}/nudgeSettings/{uid}` additions;
- device registry fields;
- `/config/features.partnerSosEnabled`.

Add only required indexes and TTL policies.

**Dependency:** Step 0.

### Step 2 — `sendNudge` callable

Implement atomic authorization, cooldown, daily cap, idempotency, nudge creation, queue creation.

Do not call FCM from the callable.

**Dependency:** Step 1.

### Step 3 — Push trigger and dispatch lease

Implement `onNudgeQueueCreated`, `pushNudge`, atomic lease acquisition/finalization, target snapshot, FCM FID multicast, per-target result handling, retry scheduling.

**Dependency:** Step 2.

### Step 4 — Repair scheduler

Implement `resumePendingNudges` using the root queue's `nextAttemptAtMs` query.

**Dependency:** Step 3.

### Step 5 — Device registrar

Add FID-aware registration freshness and `pushEligible` updates without replacing existing registry behaviour.

**Dependency:** Step 1.

### Step 6 — VPN exemption

Modify the existing VPN builder to exclude the app package and GMS package.

**Dependency:** Step 0.

### Step 7 — Notification channel and permission

Create `partner_nudges`, add Android notification permission handling, settings links and pairing-success request flow.

**Dependency:** Step 0.

### Step 8 — Sender UI

Implement `SosViewModel`, `NudgeRepository`, `Reach out` button and all sender states.

**Dependencies:** Steps 2 and 7.

### Step 9 — Recipient inbox/card

Implement Firestore listener, decryption, card, acknowledgement and notification cancellation.

**Dependencies:** Steps 1, 7.

### Step 10 — Existing FCM service integration and notification intents

Add foreground notification handling and launcher intent handling without altering existing message types.

**Dependencies:** Step 7.

### Step 11 — Settings and block state

Implement recipient block toggle and notification settings deep link.

**Dependency:** Step 1.

### Step 12 — App Check and flags

Enable callable App Check enforcement, configure Play Integrity, deploy Remote Config parameter and server kill switch.

**Dependency:** Step 2.

### Step 13 — Backend and Android automated tests

Complete all test suites in Section 12 before device testing.

**Dependencies:** Steps 2–12.

### Step 14 — Real-device matrix and VPN spike

Run the required Android/OEM/VPN matrix. The VPN test must prove the intended app/GMS exemption and verify ordinary blocker behavior still works for other applications.

**Dependencies:** Steps 6, 10.

### Step 15 — Production rollout

Run the deployment sequence in Section 13.

**Dependency:** all prior steps.

---

## 8. Detailed Implementation Steps

### 8.1 Step 0 — Mapping and project inspection

Inspect the codebase before editing.

Record exact paths/classes in `docs/partner-sos-mapping.md`.

Confirm:

- the existing pair document and active-state logic;
- current envelope protocol and whether the helper already supports AAD;
- current FCM service and notification helper;
- current device registry;
- current VPN builder;
- current Home screen and ViewModel;
- current Firebase BoM;
- current Functions runtime and Admin SDK.

Do not add dependencies until the inspection proves the existing stack lacks the capability.

If the existing envelope helper does not expose an AAD argument, add only the minimal API surface required while preserving the existing envelope version/format.

### 8.2 Step 1 — Firestore Security Rules

Rules must enforce:

```text
/pairs/{pairId}/nudges/{nudgeId}
```

Recipient-only read:

- authenticated user required;
- `resource.data.recipientUid == request.auth.uid`;
- pair must still be active for the user's access path;
- `resource.data.expiresAt >= request.time` for direct reads after deployment of the final schema.

Recipient-only update:

- authenticated user required;
- recipient UID must equal auth UID;
- document is not already acknowledged;
- only `ackedAt` may change;
- new `ackedAt` must equal `request.time`;
- no other field may change.

No client create/delete on nudge documents.

`/nudgePushQueue` is fully denied to clients.

`nudgeState` is fully denied to clients.

`config/features` is fully denied to clients.

`nudgeSettings/{uid}`:

- only the owner can read/write;
- owner must be an active pair member;
- only `blocked` and `updatedAt` may change;
- `blocked` must be boolean;
- `updatedAt == request.time`.

Device registry:

- owner-only writes;
- preserve all existing allowed fields;
- new fields must satisfy the exact types;
- `updatedAt == request.time`;
- `appVersion` max length 32.

Do not rewrite existing rules. Merge the smallest additional blocks required.

Official reference:

- [Firestore Security Rules: field validation](https://firebase.google.com/docs/firestore/security/rules-fields)

### 8.3 Step 1 — Indexes and TTL

Required nudge index:

```text
recipientUid ASC
suppressedAt ASC
ackedAt ASC
createdAt DESC
```

Required root queue index:

```text
nextAttemptAtMs ASC
```

Use Firestore's normal single-field indexing for the queue field unless the deployed query requires a composite index.

Enable TTL on `expiresAt` for:

- `nudges` collection group;
- `nudgePushQueue` collection group only if the TTL configuration command targets the root collection group accepted by the project configuration.

Do not rely on TTL for cooldown, queue eligibility, or access-control correctness.

Official reference:

- [Firestore TTL policies](https://firebase.google.com/docs/firestore/ttl)
- [Firestore query documentation](https://firebase.google.com/docs/firestore/query-data/queries)

### 8.4 Step 2 — `sendNudge` implementation

File:

```text
functions/src/partnerSos/sendNudge.ts
```

Use Cloud Functions 2nd gen `onCall` with:

```text
region: FIRESTORE_REGION
memory: 256MiB
timeoutSeconds: 15
minInstances: 0
maxInstances: 20
enforceAppCheck: true
```

Input:

```text
pairId: string
nudgeId: UUIDv4 string
envelope: base64 string, max 1024 characters
```

Reject unknown fields.

Perform all acceptance-state reads and writes in a single transaction.

Use a single request `nowMs` captured before transaction execution.

Daily key generation must explicitly use `Africa/Nairobi`; never depend on `process.env.TZ` or host timezone.

Use `Intl.DateTimeFormat` with `timeZone: 'Africa/Nairobi'` to derive the `YYYY-MM-DD` key.

The next-midnight retry value must also be calculated in `Africa/Nairobi` calendar terms.

On accepted non-blocked request, create:

```text
nudge.status = pending
queue.nextAttemptAtMs = nowMs
queue.attemptCount = 0
queue.leaseId = null
queue.leaseUntilMs = null
queue.targetInstallIds = []
queue.sentInstallIds = []
```

The transaction writes the nudge and queue together so an accepted nudge cannot exist without a recovery record.

### 8.5 Step 3 — `onNudgeQueueCreated`

File:

```text
functions/src/partnerSos/onNudgeQueueCreated.ts
```

Use Firestore `onDocumentCreated` for `/nudgePushQueue/{nudgeId}`.

The handler must call the shared `pushNudge(pairId, nudgeId)` and return after that invocation completes.

It must tolerate duplicate trigger delivery and missing queue documents.

Official reference:

- [Firestore-triggered Cloud Functions](https://firebase.google.com/docs/functions/firestore-events)

### 8.6 Step 3 — Dispatch lease transaction

File:

```text
functions/src/partnerSos/pushNudge.ts
```

Lease acquisition transaction:

1. Read queue.
2. Read nudge.
3. If queue missing, return `missing`.
4. If nudge terminal, delete orphan queue if present and return.
5. If `leaseUntilMs > now`, return `not_owner`.
6. Otherwise generate a new `leaseId` and write:

```text
leaseId = generatedLeaseId
leaseUntilMs = now + 90000
attemptCount = attemptCount + 1
```

7. Commit.
8. All later writes must validate that the queue's `leaseId` is still the same.

Do not hold a Firestore transaction open while calling FCM.

Official reference:

- [Firestore transactions](https://firebase.google.com/docs/firestore/manage-data/transactions)

### 8.7 Step 3 — Target snapshot

If `targetInstallIds` is empty:

1. Query `/users/{recipientUid}/devices` where `pushEligible == true`.
2. Snapshot the returned installation document IDs.
3. Write the snapshot only if the same dispatch lease is still owned.
4. Do not add new installations on later retries.

This snapshot is the target set for the logical nudge.

If zero eligible installations are found, finalize `no_eligible_device` and delete queue atomically with the same lease check.

### 8.8 Step 3 — FCM sending

Use FID-targeted multicast in batches of at most 500.

For each batch:

- message notification title/body as specified in Section 4.6;
- Android priority `high`;
- Android TTL `3600000` milliseconds in the Admin SDK object;
- `restrictedPackageName` equal to the mapped Android application ID;
- notification channel `partner_nudges`;
- notification tag `partner_nudge`;
- private lock-screen visibility;
- data fields only `type` and `nudge_id`.

Use the current Admin SDK API that accepts `fids`.

Official reference:

- [Firebase Admin SDK FCM sending](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk)
- [Firebase Admin Node.js release notes](https://firebase.google.com/support/release-notes/admin/node)

### 8.9 Step 3 — FCM result handling

Classify results:

**Success**

- add installation ID to `sentInstallIds`.

**Invalid/unregistered target**

- delete that device registration;
- do not retry that target;
- do not count it as successfully sent.

**Quota/rate limited**

- keep target pending;
- schedule queue retry with at least 60-second delay and exponential backoff/jitter.

**Server unavailable/internal/unknown transient**

- keep target pending;
- queue retry with exponential backoff/jitter.

**Invalid message/payload**

- terminal engineering failure for the nudge;
- emit `nudge_push_payload_invalid`;
- do not continue blind retries.

### 8.10 Step 3 — Finalization transaction

After FCM response processing, run one Firestore transaction that reads the queue and verifies `leaseId`.

If the lease no longer matches, do not write any result from the stale worker.

If it matches:

- update `sentInstallIds`;
- update nudge counts and `lastPushAtMs`;
- set terminal status and delete queue when appropriate;
- otherwise clear lease and schedule `nextAttemptAtMs`.

The delete and terminal status write occur in the same transaction.

### 8.11 Step 4 — Repair scheduler

File:

```text
functions/src/partnerSos/resumePendingNudges.ts
```

Schedule every 5 minutes.

Use:

```text
maxInstances = 1
```

and explicitly set a low concurrency configuration where the Functions runtime supports it. The application lease remains mandatory even if scheduler concurrency is reduced.

Query:

```text
/nudgePushQueue
where nextAttemptAtMs <= now
orderBy nextAttemptAtMs ascending
limit 50
```

Do not use an `IN` filter on push status. Queue presence itself indicates active/retryable work.

Do not scan the `nudges` collection group for normal recovery.

### 8.12 Step 5 — DeviceRegistrar

Use the existing installation registration path.

The document ID is the FID from `FirebaseInstallations.getInstance().id`.

Refresh registration:

- application startup after Firebase Auth readiness;
- `FirebaseMessagingService.onNewToken()` for existing token compatibility;
- `ProcessLifecycleOwner` `ON_START`;
- after notification permission result;
- after returning from Android notification settings.

Compute:

```text
pushEligible =
    app notifications enabled
    AND partner_nudges channel exists
    AND channel importance != IMPORTANCE_NONE
```

Store registration freshness through `updatedAt`.

Do not treat `pushEligible` as authoritative truth; it is the latest known device capability snapshot used to reduce unnecessary sends.

Official references:

- [FCM registration token management](https://firebase.google.com/docs/cloud-messaging/manage-tokens)
- [Android notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

### 8.13 Step 6 — VPN exemption

At the existing `VpnService.Builder` creation site, before `establish()`:

```kotlin
listOf(packageName, "com.google.android.gms").forEach { pkg ->
    try {
        builder.addDisallowedApplication(pkg)
    } catch (_: PackageManager.NameNotFoundException) {
        // Package is not installed; nothing to exclude.
    }
}
```

Do not:

- create a second VPN;
- create a custom OkHttp client for Firestore;
- use an OkHttp `SocketFactory` for the SOS path;
- use `VpnService.protect()` on Firestore SDK sockets;
- modify unrelated blocker filtering.

After the change, ordinary traffic from other applications must remain subject to the existing blocker policy.

### 8.14 Step 7 — Notification channel and permission

Create the notification channel at application startup for API 26+.

```text
id: partner_nudges_v3
name: Accountability Check-ins
importance: IMPORTANCE_HIGH
lockscreenVisibility: VISIBILITY_PRIVATE
vibration: enabled on first creation
```

> **Rev 2026-10-04 (owner mandate):** id + importance changed from the block above's original
> `partner_nudges`/`IMPORTANCE_DEFAULT` — a reach-out must surface as an on-screen heads-up
> banner (device-verified: DEFAULT = tray-only), and Android neither raises an existing
> channel's importance in place nor restores one the app itself has silently lowered — so the
> id is versioned and every prior id (`partner_nudges`, `partner_nudges_v2`) is deleted at
> startup (D14 in `app/docs/partner-sos-mapping.md`; FCM payload + client post read the
> current const; `pushEligible` reads it).

Add `POST_NOTIFICATIONS` permission declaration.

On API 33+ request permission when the pair becomes active on the pairing-success screen.

If permission is denied, do not block pairing. Show the Home banner:

`Notifications are off, so you'll miss check-ins.`

The banner links to Android app notification settings.

Add a settings row linking directly to the `partner_nudges` channel settings.

Never attempt to change user-controlled channel importance programmatically after channel creation.

Official references:

- [Android notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Android notification channels](https://developer.android.com/develop/ui/views/notifications/channels)

### 8.15 Step 8 — Sender UI

> **Rev 2026-10-04 (owner mandate):** the sender control is a shell-level **FAB** ("Reach
> out", waving-hand icon) with an explicit **confirmation dialog** (Send/Cancel), replacing
> the Home card — accidental sends were ruled worse than one extra tap. The state machine,
> its 5-second auto-returns, retry semantics, and result copy are unchanged: terminal
> results now surface as snackbars and the FAB is dimmed/inert while a result shows. Gating
> is unchanged (FR-1: flag + active pair); the roster read is hoisted to AppShell so the
> FAB and Home's pinned card share one read. FR-25 still holds — the dialog shows the body
> copy only, never the emergency warning.
>
> **Rev 2026-10-04 second pass (owner mandate — the message):** the dialog names the partner
> the send will actually reach (the §19.5 target rule, same resolution as tap time; generic
> title as fallback) and carries a **message field prefilled with a default** ("I could use
> a check-in."), fully editable, char-capped at the envelope bound; a cleared field sends
> the v1 no-text nudge. A retry (`CouldNotConfirm`) replays the sealed envelope verbatim —
> a message edited between attempts is ignored, never re-encrypted. Realtime path, rate
> limits and notifications are unchanged; the tray copy stays content-free by privacy
> design (FR-13) — the message is visible only inside the app, from the decrypted Firestore
> document.

States:

```text
Idle
Sending(nudgeId, envelope)
Sent
RateLimited(reason, retryAfterSeconds)
NotSentOffline
CouldNotConfirm(nudgeId, envelope)
Unavailable
```

Rules:

- ignore taps while `Sending`;
- `Sent` returns to `Idle` after 5 seconds;
- rate-limited state returns to `Idle` after 5 seconds;
- `NotSentOffline` returns to `Idle` after 5 seconds;
- `Unavailable` returns to `Idle` after 5 seconds;
- `CouldNotConfirm` keeps the same `(nudgeId, envelope)` and lets the next tap retry the callable;
- no automatic background retry from the client;
- no local cooldown enforcement is required for correctness, though button-level UI may disable temporarily while showing the result.

Callable response mapping:

```text
accepted -> Sent
rate_limited/cooldown -> RateLimited
rate_limited/daily_cap -> RateLimited
failed-precondition/feature_disabled -> Unavailable
unauthenticated -> Unavailable
permission-denied -> Unavailable
invalid-argument -> Unavailable
unavailable/deadline-exceeded/internal -> CouldNotConfirm
```

The client may record a Crashlytics non-fatal for unexpected errors, but must not log envelope, pair ID, UID, FCM token, or plaintext.

### 8.16 Step 9 — Recipient inbox

Attach the listener during Home lifecycle.

Query only the active pair's nudge collection.

Use the recipient's UID and `suppressedAt == null`, `ackedAt == null` filters.

Order by `createdAt DESC`, limit 10.

The card displays:

- title: `Your partner reached out`;
- relative send time;
- button: `I'm here`;
- dismiss `X`.

The card does not show the plaintext envelope itself. Rev 2026-10-04 (owner mandate): when the envelope is v2 and carries a message, the card renders that message as its body line; a v1 (or blank-message) nudge has none and the card stands as before. The repository's `cardOf` opens the newest document exactly once and extracts the message from the opened plaintext — receiver-side sanitized (control characters stripped, trimmed, re-bounded), never trusted as-is.

### 8.17 Step 10 — Existing FCM service

In the existing `FirebaseMessagingService`:

```text
if data.type == partner_nudge:
    postPartnerNudgeNotification(nudgeId)
    return
```

The notification must be posted immediately.

Do not perform Firestore reads, decryption or network synchronization in the FCM callback.

Existing message types continue through their current paths unchanged.

### 8.18 Step 10 — MainActivity intent handling

In both `onCreate` and `onNewIntent`:

1. Check for `type=partner_nudge`.
2. Validate `nudge_id`.
3. Navigate to Home.
4. Remove handled extras before subsequent navigation/state restoration.

Do not treat FCM extras as authoritative message content.

### 8.19 Step 11 — Partner settings

Block switch:

- read current `/pairs/{pairId}/nudgeSettings/{uid}`;
- write only `{blocked, updatedAt: serverTimestamp()}`;
- on enabling block, no retroactive deletion is performed on already-delivered notifications;
- pending nudges are suppressed by `pushNudge` before their next FCM send;
- sender is not notified.

Notification row:

- open `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` for `partner_nudges`.

Home banner:

- show only when app-level notifications are disabled;
- do not show the banner merely because the channel is silent/muted.

### 8.20 Step 12 — App Check and Remote Config

Initialize App Check using the project's release provider configuration.

For Play Integrity:

- configure the provider for the actual Google Play/APK distribution modes;
- do not blindly require a Play-only recognition verdict for an APK-only path;
- document the exact Play Integrity/App Check configuration in `docs/partner-sos-mapping.md`.

Enable App Check enforcement on `sendNudge`.

Remote Config:

```text
partner_sos_enabled = false (default)
```

The client may fetch/activate Remote Config on its existing app-start schedule.

Server kill switch:

```text
/config/features.partnerSosEnabled = false
```

is authoritative and checked on every callable request.

Official reference:

- [Firebase App Check with Play Integrity](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
- [Enable App Check enforcement for Cloud Functions](https://firebase.google.com/docs/app-check/cloud-functions)

---

## 9. Error Handling

### 9.1 Client errors

| Condition | Required behavior |
|---|---|
| No validated network | Show `Not sent: offline`; no callable request |
| Callable timeout | Show `Couldn't confirm`; retain same nudgeId+envelope for manual retry |
| Callable unavailable/internal | Show `Couldn't confirm`; retain same nudgeId+envelope |
| Feature disabled | Show `Unavailable` |
| Invalid input | Show `Unavailable`; record safe diagnostic |
| Auth/App Check rejection | Show `Unavailable`; do not expose backend reason |
| Rate limit | Show exact cooldown or daily-cap copy from server response |
| Token registration failure | Retry on next lifecycle/registration opportunity |
| Decryption failure | Safe Crashlytics non-fatal; do not expose ciphertext; do not render unsafe content |

### 9.2 Server errors

#### Transaction contention

Firestore retries the transaction automatically. If it ultimately fails, return `internal`; the client can retry with the same `nudgeId`.

#### Function crash before transaction commit

No nudge exists and no cooldown state changes.

#### Function crash after transaction commit, before queue trigger

The queue document remains durable and `onDocumentCreated`/repair path dispatches it.

#### Function crash during dispatch

The lease eventually expires and the repair scheduler can reclaim the queue.

#### Crash after FCM accepts a message but before finalization

The lease expires and the message may be resent. The system treats this as an unavoidable at-least-once side-effect window. The fixed notification tag prevents multiple active tray entries for the feature.

#### Recipient blocks while push is pending

The next dispatch attempt reads the current block state and transitions the nudge to `suppressed` without sending FCM.

#### Pair revoked while push is pending

The next dispatch attempt reads the current pair state and transitions the nudge to `suppressed` without sending FCM.

#### FCM invalid/unregistered target

Delete that device registration and do not retry that target.

#### FCM quota/rate limit

Retain target, schedule queue retry using minimum 60-second delay plus exponential backoff/jitter.

#### FCM server unavailable/internal error

Retain target, schedule queue retry with exponential backoff/jitter.

#### Malformed FCM message

Terminal engineering failure. Do not repeatedly retry an invalid payload.

#### No eligible device

Set `no_eligible_device`, delete queue, retain nudge for in-app sync.

#### Queue missing but nudge pending

Treat as invariant violation. Emit `nudge_queue_missing` and create a repair queue document from a repair-only administrative process. Do not silently ignore.

### 9.3 Retention failures

TTL delays do not affect correctness. Client queries apply the `expiresAt` check; Security Rules require unexpired access; TTL performs eventual deletion.

### 9.4 Logging failures

Never log:

- envelope;
- plaintext;
- Firebase Auth UID in clear;
- pair ID in clear;
- FCM token/FID in clear;
- AAD;
- App Check token.

Use short deterministic hash prefixes only for operational correlation when necessary.

---

## 10. Security Requirements

### 10.1 Authentication and authorization

- Every `sendNudge` request requires Firebase Authentication.
- Every `sendNudge` request requires valid App Check.
- The server derives sender identity only from `req.auth.uid`.
- Pair membership and active status are verified server-side.
- Recipient identity is derived from the pair record, never accepted from client input.
- Client cannot write nudge records, queue records, rate-limit state, config, or another user's settings.

Official references:

- [Firebase callable functions](https://firebase.google.com/docs/functions/callable)
- [Firebase App Check enforcement](https://firebase.google.com/docs/app-check/cloud-functions)

### 10.2 Idempotency and replay resistance

- `nudgeId` is UUIDv4.
- The server checks the nudge document before consuming cooldown/cap.
- The same `nudgeId` with the same envelope is a replay and does not create new state.
- The same `nudgeId` with a different envelope is rejected.
- A different sender attempting an existing `nudgeId` receives `permission-denied`.
- Queue dispatch uses a separate lease because request idempotency does not make the FCM side effect exactly once.

### 10.3 Rate-limit security

- Cooldown uses only server time/state.
- Daily cap uses only server time/state.
- No client timestamps influence authorization.
- Blocked/suppressed attempts consume rate-limit state.
- Rate-limit state is updated in the same transaction as nudge acceptance.

### 10.4 Encryption

- Use the existing pair envelope protocol.
- Use a fresh nonce per encryption operation according to the existing envelope protocol.
- Bind the logical nudge to AAD containing pair ID, nudge ID and sender UID.
- The server stores ciphertext as an opaque string.
- FCM contains no ciphertext.
- Do not introduce a new crypto primitive, new key store, or new pair secret.
- Rev 2026-10-04 (owner mandate): the optional message lives **inside** the sealed plaintext
  as a v2 CBOR map (see FR-7 rev) — the AAD string stays `partner_nudge|v1|…`, the wire stays
  `nonce || ciphertext` base64, and the server's ≤1024-char opaque-string contract is
  enforced client-side by a byte bound on the text. No server, rules, or key changes.

The symmetric pair key demonstrates possession of the pair secret; Firebase Auth establishes the sender's server identity. Do not describe the symmetric envelope as non-repudiating sender identity.

### 10.5 Firestore data exposure

Recipient access is limited to the currently active pair and unexpired nudge documents belonging to that recipient.

The sender cannot directly read recipient-side nudge records.

The queue and administrative state are server-only.

### 10.6 Metadata privacy

The backend may know that the callable is a Partner SOS request and may store the operational metadata required for dispatch:

- pair ID;
- sender UID;
- recipient UID;
- timestamps;
- block state;
- push eligibility state;
- dispatch status;
- installation IDs/FIDs internally for delivery.

The backend must not receive plaintext nudge content.

### 10.7 FCM hardening

- Use `restrictedPackageName` equal to the mapped Android package name.
- Use FID-targeting APIs supported by the installed Admin SDK.
- Never place access tokens or secrets in FCM data.
- Never place ciphertext in FCM notification or data fields.

### 10.8 VPN security

The app package and GMS are excluded from the app-owned VPN when the VPN is active. That exemption applies to all traffic from those packages.

No other package is exempted by this feature.

The real-device VPN test must confirm that unrelated blocked applications remain blocked.

### 10.9 Abuse controls

- 10-minute cooldown.
- Daily cap of 12.
- App Check.
- Firebase Auth.
- Pair-membership enforcement.
- Firestore Rules denial of direct nudge creation.
- `maxInstances` limits on functions.
- Queue attempt cap.
- Invalid FCM registrations are removed.

Do not treat App Check as a cryptographic guarantee against a modified client; it is an abuse-resistance layer.

### 10.10 Privacy/compliance

Update the Kenya Data Protection Act DPIA and privacy notice to include:

- nudge metadata;
- encrypted nudge ciphertext;
- `pushEligible`;
- block state;
- 7-day nudge retention;
- operational log retention of 30 days;
- FCM delivery processing.

---

## 11. Performance Requirements

### 11.1 Sender path

The callable must finish after durable Firestore acceptance and must never wait for FCM.

Target:

- p95 ≤ 2s server-side;
- p95 ≤ 5s tap-to-result on connected 4G devices.

The callable's critical path is:

```text
auth/App Check
→ transaction reads
→ rate decision
→ nudge + queue writes
→ return
```

### 11.2 Push path

Normal dispatch is event-driven from queue creation.

Repair dispatch is bounded to 50 queue documents per five-minute scheduler invocation.

FCM fanout uses batches of at most 500 FIDs per API call.

### 11.3 Query efficiency

Do not use a collection-group scan of all historical nudges for normal recovery.

The root queue is the work index.

Do not describe the queue query as mathematically O(1); describe it as an indexed root-collection query with bounded result count.

Use Query Explain/Cloud monitoring to verify real performance if production data grows.

Official references:

- [Firestore query documentation](https://firebase.google.com/docs/firestore/query-data/queries)
- [Firestore pricing/query Explain](https://firebase.google.com/docs/firestore/pricing)

### 11.4 Notification receive path

`onMessageReceived()` must do only the minimum work needed to post the notification immediately.

No network I/O, decryption, or Firestore query is allowed there.

### 11.5 Cold starts

`minInstances=0` remains the default for cost control.

Do not add `minInstances=1` unless production latency measurements prove it is required.

### 11.6 Firestore indexing

Exclude the `envelope` field from indexing if the project's schema tooling supports a field exemption, because the ciphertext is never queried.

### 11.7 Billing

Track:

- callable invocations;
- Firestore document reads/writes;
- FCM sends;
- scheduler invocation count;
- function execution time.

Do not assume exact read/write counts in documentation without measuring the deployed query/transaction behavior.

---

## 12. Testing Requirements

### 12.1 Backend automated tests

#### Acceptance/rate/idempotency

**T-F1** First nudge: `accepted`; one nudge document; one queue document.  
**T-F2** Second nudge at +9 min: `rate_limited/cooldown`; no new nudge/queue/state mutation.  
**T-F3** Second nudge at +10 min: `accepted`.  
**T-F4** Same `nudgeId` + same envelope after `sent`: `accepted`; no second cooldown.  
**T-F5** Same `nudgeId` + same envelope while `pending`: `accepted`; no second cooldown.  
**T-F6** Same `nudgeId` + different envelope: `invalid-argument`.  
**T-F7** Same `nudgeId` from different sender: `permission-denied`.  
**T-F8** Thirteenth accepted nudge in Nairobi day: `rate_limited/daily_cap`.  
**T-F9** A↔B directionality: independent cooldown state.  
**T-F10** Recipient blocked at acceptance: `accepted`; nudge `suppressed`; no queue.  
**T-F11** Block enabled after acceptance but before dispatch: push worker suppresses without FCM send.  
**T-F12** Pair revoked before dispatch: push worker suppresses without FCM send.  
**T-F13** Ten concurrent distinct nudge IDs: exactly one acceptance because of cooldown transaction.  
**T-F14** Concurrent same nudge ID: one logical nudge and one queue document.

#### Dispatch/lease

**T-F15** Duplicate queue trigger events: only one active lease; no concurrent send authorization.  
**T-F16** Scheduler overlaps trigger: only lease owner may send.  
**T-F17** Lease expires: next worker reclaims successfully.  
**T-F18** Stale worker finalization after lease loss: ignored.  
**T-F19** Crash before FCM: queue remains and repair dispatches.  
**T-F20** Crash after FCM acceptance before finalization: recovery may resend, but tray remains one tagged notification.  
**T-F21** Successful FCM to all targets: nudge `sent`; queue deleted atomically.  
**T-F22** Partial success + permanent failures: nudge `partial`; queue deleted.  
**T-F23** No eligible device: nudge `no_eligible_device`; queue deleted.  
**T-F24** All targets terminally fail: nudge `failed`; queue deleted.  
**T-F25** Transient FCM failure: queue retained with future `nextAttemptAtMs`.  
**T-F26** Fourth failed attempt: terminal state and queue deletion; `nudge_stuck` emitted.

#### Security/rules

**R-F1** Sender cannot create/delete/update nudge.  
**R-F2** Recipient can read only active-pair, unexpired, own-recipient nudge.  
**R-F3** Recipient can set only `ackedAt`.  
**R-F4** Second acknowledgment mutation is rejected.  
**R-F5** Client cannot access queue/rate state/config.  
**R-F6** Block setting schema enforced.  
**R-F7** Device registration owner/schema enforced.  
**R-F8** Pair membership requirement enforced.

### 12.2 Android unit tests

`SosViewModel`:

- every state transition;
- repeated tap during `Sending` ignored;
- exact same nudgeId+envelope reused after timeout;
- rate-limit formatting;
- offline result.

`NudgeRepository`:

- encryption helper called with exact AAD;
- no second encryption for retry of the same logical request;
- callable error mapping.

`DeviceRegistrar`:

- notification enabled + channel enabled → true;
- notification disabled → false;
- channel disabled → false;
- channel silent but enabled → true;
- unchanged registration not rewritten unnecessarily.

`NudgeInboxRepository`:

- newest-first;
- ignores suppressed/acknowledged/expired records;
- decrypts with existing AAD;
- malformed envelope path is safe.

### 12.3 Instrumented Android tests

- Channel exists before notification post.
- Foreground FCM results in one local notification.
- Background FCM results in one system notification.
- Fixed tag replaces existing partner notification.
- Notification tap navigates to Home and consumes intent extras.
- Card appears from Firestore without relying on FCM payload content.
- `I'm here` acknowledges all fetched pending nudges.
- `X` acknowledges all fetched pending nudges.
- Notification cancellation occurs after successful acknowledgment.
- Notification-disabled banner appears.
- Channel-mute state does not show the app-level notification-off banner.

### 12.4 VPN tests

Run on real devices.

Sender side:

1. Activate Strict Mode.
2. Activate blocker.
3. Verify ordinary blocked traffic remains blocked.
4. Tap Reach out.
5. Verify callable succeeds.
6. Verify DNS resolution and network path succeed for the app's actual backend operations.

Partner side:

- GMS device with blocker active;
- notification permission enabled;
- receive FCM.

The VPN test passes only if the SOS path works **and** unrelated application blocking still functions.

> **Rev 2026-10-04 (device-run, two-device rig):** sender side executed on the X6532 end-to-end — Strict + blocker on, ordinary blocked traffic stayed blocked, the callable succeeded under the VPN (step 5), and the recipient-inbox leg ran green (`NudgeInboxDeviceInstrumentedTest` OK(1), 11.48 s). Partner side executed on the X6532C (GMS, notifications enabled, blocker active, FCM received). Honest gap: the FAB-*tap* was not drivable inside the window (client RC refetch stuck at the stale `false`); the callable leg covers step 5, and the FAB's dialog/send path is separately device-pinned (mapping §5.1). Record: mapping doc §5.3. **Parked same night (owner time-box):** the FAB-tap leg + the repair-sweep leg (`resumePendingNudges` on a stalled `nudgePushQueue` doc) were deferred to the next session — the kill-switch window was opened via API and **both switches restored false + verified** (RC v7, Firestore doc) before parking; the live pair (epoch 9) is intentionally kept for these legs.

### 12.5 Device matrix

Minimum devices:

- Pixel/AOSP;
- Samsung;
- Xiaomi;
- Transsion (Tecno/Infinix/itel);
- one non-GMS Android device.

Partner states:

1. Foreground.
2. Background.
3. Doze.
4. App removed from recents.
5. Restricted standby bucket.
6. OEM battery restriction.
7. App notifications off.
8. `partner_nudges` channel silent/muted.
9. Recipient offline for 2 minutes, then online.
10. Force-stop test, recorded without asserting that it reproduces every OEM freezer behavior.
11. Recipient's own blocker/Strict Mode active.

Record:

- FCM accepted;
- notification shown/not shown;
- notification latency where observable;
- app-open card presence;
- card timestamp correctness;
- device-specific anomalies.

> **Rev 2026-10-04 (device-run):** the partner-state matrix ran on the X6532C via host FCM v1 pushes — states 1–5, 7, 9–11 executed with results (doze delivered, recents delivered, restricted-only-after-unwhitelist delivered, notifications-off suppressed-but-woke, offline queued→delivered on reconnect, force-stop correctly undelivered, blocker-active delivered). Honest gaps: state 6 (OEM battery restriction) is not drivable via adb on this XOS build; state 8 (channel mute) is not drivable via adb either — its distinct claim (mute ≠ app-level banner) is already device-pinned by §12.3 bullet 11; the offline window was cut to ~20 s twice by manual wi-fi reconnects (mechanism duration-independent — FCM queues). Card presence/timestamp rows are §12.3's legs (host pushes carry no envelope). Min-device matrix: **Transsion only** in the rig — Pixel/Samsung/Xiaomi/non-GMS not available, recorded as a limit, not claimed. Full table + anomalies: mapping doc §5.3.

### 12.6 App Check tests

Test:

- valid release app;
- debug provider in debug environment;
- invalid/missing App Check;
- Play distribution;
- APK distribution according to the project's configured Play Integrity policy.

### 12.7 Abuse tests

Using a modified client:

- 20 distinct `nudgeId`s in 1 minute → exactly one accepted;
- 20 repeated calls using the same accepted `nudgeId` → exactly one logical nudge and no additional cooldown consumption;
- direct Firestore nudge creation → denied;
- direct queue creation → denied;
- recipient block cannot be bypassed by direct client write.

---

## 13. Deployment Requirements

### 13.1 Pre-deployment

1. Complete `docs/partner-sos-mapping.md`.
2. Ratify the ADR amendment permitting server-visible Partner SOS metadata.
3. Update the DPIA/privacy notice.
4. Confirm backend runtime Node 22+ and Admin SDK FID support.
5. Run Firebase Emulator Suite rules tests.
6. Run backend unit/integration tests.
7. Run Android unit/instrumented tests.
8. Run the VPN real-device spike.
9. Validate the FCM payload in a non-production project.

### 13.2 Production deployment order

1. Deploy Firestore rules.
2. Deploy indexes.
3. Enable TTL policies.
4. Deploy `/config/features.partnerSosEnabled=false`.
5. Deploy `sendNudge`, `onNudgeQueueCreated`, and `resumePendingNudges` with the feature disabled.
6. Deploy Android release with Remote Config `partner_sos_enabled=false`.
7. Enable monitoring and budget alerts.
8. Run internal tests with controlled flag enablement.
9. Enable the feature for internal accounts.
10. Run the production-like device matrix.
11. Enable a 5% Remote Config rollout.
12. Hold for 7 days.
13. Review all alert conditions and device results.
14. Expand to 50% if all rollout gates pass.
15. Expand to 100%.

### 13.3 Rollback

Primary rollback:

```text
/config/features.partnerSosEnabled = false
partner_sos_enabled = false
```

The server kill switch stops new acceptance immediately.

Already-accepted nudges remain in Firestore for recovery/retention semantics and are not deleted by rollback.

If backend rollback is necessary, revert to the last tagged functions version after disabling the feature.

Do not delete the new collections merely to roll back application code.

### 13.4 Backward compatibility

Older app versions do not need to understand the new in-app nudge card.

They may still display the generic system notification when their Android/FCM environment supports it.

The backend must not assume an older client can decrypt or display Partner SOS content.

### 13.5 Release tagging

Tag the implementation:

```text
partner-sos-v2.0
```

---

## 14. Monitoring Requirements

### 14.1 Structured log events

Emit:

- `nudge_accepted`
- `nudge_replay`
- `nudge_rate_limited`
- `nudge_suppressed`
- `nudge_dispatch_claimed`
- `nudge_push_result`
- `nudge_status_final`
- `nudge_resume`
- `nudge_stuck`
- `nudge_queue_missing`
- `nudge_push_payload_invalid`

Log safe fields only:

- hashed nudge ID;
- hashed pair ID if needed;
- hashed installation ID if needed;
- status;
- reason;
- attempt count;
- target count;
- successful count;
- error code;
- elapsed milliseconds.

### 14.2 Metrics

Track:

- accepted nudges/day;
- rate-limited nudges/day by reason;
- suppressed nudges/day;
- `no_eligible_device` percentage;
- push success percentage;
- partial/failed percentage;
- average and p95 dispatch duration;
- queue age p50/p95/p99;
- number of reclaimed expired leases;
- FCM invalid-token rate;
- FCM quota/rate-limit errors;
- function error rate;
- callable p95 latency;
- callable unknown-outcome rate;
- device registration freshness.

### 14.3 Alerts

Create:

- `sendNudge` error rate >2% over 10 minutes with at least 20 requests;
- callable p95 >5s over 15 minutes;
- any `nudge_queue_missing`;
- any `nudge_push_payload_invalid`;
- any `nudge_stuck`;
- FCM quota errors occurring repeatedly for 10 minutes;
- invalid-registration rate >30% of attempted targets over 24 hours;
- queue age p95 >15 minutes;
- billing budget threshold at 50% and 90%.

### 14.4 FCM delivery analytics

Use Firebase's available FCM reporting tools to review:

- message deprioritization;
- proxied notification behavior where applicable;
- aggregate delivery trends.

Do not treat aggregate delivery analytics as an authoritative per-device delivery acknowledgement.

Official reference:

- [FCM Android message priority and deprioritization](https://firebase.google.com/docs/cloud-messaging/android-message-priority)

### 14.5 Operational runbook

Create:

```text
docs/partner-sos-runbook.md
```

Document:

- kill switch;
- rollback;
- locating stuck queue work;
- inspecting a hashed nudge ID;
- manually invoking the push repair function;
- interpreting FCM errors;
- removing stale registrations;
- reading queue age metrics;
- VPN diagnostic procedure.

---

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| OEM power management delays or suppresses notification | High | High | High-priority FCM + app-open Firestore source of truth + device matrix | Device matrix and FCM analytics |
| VPN exemption weakens blocker boundary | Medium | High | Exclude only app/GMS; no other package changes; verify other apps remain blocked | Real-device VPN regression test |
| Crash after FCM send causes duplicate external send | Low | Medium | Lease + sent-install tracking + fixed notification tag | Dispatch metrics and duplicate test |
| Two workers attempt same nudge | Medium | High | Atomic lease and lease-checked finalization | Concurrent dispatch tests |
| Block changes during dispatch | Medium | High | Re-read block state after lease acquisition | T-F11 |
| Pair revoked during dispatch | Low | High | Re-read pair state before FCM | T-F12 |
| Stale `pushEligible` state | Medium | Medium | Refresh on lifecycle/settings changes; treat as hint only | no-eligible-device trend |
| Notification permission disabled | Medium | High | Banner + device eligibility snapshot + in-app sync | Device registration and FCM metrics |
| FCM invalid target registrations | Medium | Medium | Delete confirmed-invalid device registrations | Token churn metric |
| FCM quota throttling | Low | High | Queue backoff, jitter, bounded retries | quota alert |
| Queue backlog grows beyond scheduler capacity | Low | Medium | Limit 50/run, monitor queue age, increase worker budget only after measurement | queue age p95 |
| Cloud Function cold start | Medium | Medium | Async dispatch, measured latency; keep minInstances 0 initially | p95 function latency |
| Firestore transaction contention | Low | Medium | One nudgeState doc per sender/pair and bounded transaction | transaction errors |
| User interprets feature as emergency channel | Medium | High | Copy and onboarding language explicitly deny emergency use | Support/user feedback |
| Metadata/privacy exposure | Medium | High | Minimal metadata, encrypted content, no ciphertext in FCM/logs | privacy review |
| App Check rejects legitimate build | Medium | High | Configure Play Integrity for actual distribution modes and test Play/APK builds | release test matrix |
| TTL deletion delayed | High | Low | Correctness never depends on TTL; client/rules enforce expiry | retention audit |
| Old app version lacks in-app card | Medium | Low | Notification remains generic; staged rollout | release adoption |
| Queue document orphaned | Low | High | Atomic nudge+queue creation, invariant alert, repair path | `nudge_queue_missing` |

---

## 16. Open Questions

None remain for implementation.

All platform-dependent behavior required by V1 is handled by explicit Android/Firebase contracts, runtime tests, or bounded best-effort semantics.

The only values that are project-specific are resolved in Step 0 from the existing codebase and Firebase configuration; they are mapping values, not architectural decisions.

Official documentation used to close platform details:

- [Android VpnService](https://developer.android.com/reference/android/net/VpnService)
- [Android VpnService.Builder](https://developer.android.com/reference/android/net/VpnService.Builder)
- [Android notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Firebase FCM Android priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority)
- [Firebase FCM Android receive behavior](https://firebase.google.com/docs/cloud-messaging/android/receive-messages)
- [Firebase FCM token/FID management](https://firebase.google.com/docs/cloud-messaging/manage-tokens)
- [Firebase Admin FCM sending](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk)
- [Firebase Admin Node.js release notes](https://firebase.google.com/support/release-notes/admin/node)
- [Firebase callable functions](https://firebase.google.com/docs/functions/callable)
- [Firebase App Check with Play Integrity](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
- [Firebase App Check Cloud Functions enforcement](https://firebase.google.com/docs/app-check/cloud-functions)
- [Firestore transactions](https://firebase.google.com/docs/firestore/manage-data/transactions)
- [Firestore-triggered functions](https://firebase.google.com/docs/functions/firestore-events)
- [Firestore TTL](https://firebase.google.com/docs/firestore/ttl)
- [Firestore query documentation](https://firebase.google.com/docs/firestore/query-data/queries)
- [FCM error codes](https://firebase.google.com/docs/cloud-messaging/error-codes)
- [FCM scaling/backoff guidance](https://firebase.google.com/docs/cloud-messaging/scale-fcm)

---

## 17. Acceptance Criteria

1. The Reach out control appears only for an active pair when the client flag is enabled.
2. ~~Tapping Reach out does not display a dialog, PIN, timer or confirmation.~~ **Superseded 2026-10-04:** tapping the FAB displays exactly one confirmation dialog (Send/Cancel; no PIN, timer, delay, or challenge), and dispatch happens only on Send.
3. With no validated network, the app displays `No connection. Not sent.` and makes no backend call.
4. The first valid request returns `accepted` and creates exactly one nudge and one queue document.
5. A second request within 10 minutes is rate-limited by the server without creating another nudge.
6. A request at or after 10 minutes is accepted when the daily cap permits it.
7. The thirteenth accepted nudge in the same Nairobi day is rejected by the daily cap.
8. Repeating a request with the same nudgeId and envelope never consumes another cooldown or creates another nudge.
9. Reusing an existing nudgeId with a different envelope is rejected.
10. Concurrent dispatch workers cannot both hold the same nudge lease.
11. A crash before FCM does not permanently lose the nudge.
12. A crash after FCM acceptance cannot create multiple active tray entries because the Android notification uses the fixed `partner_nudge` tag.
13. Recipient block at acceptance suppresses push and card while returning `accepted` to the sender.
14. Recipient block enabled after acceptance but before push suppresses the pending nudge.
15. Pair revocation before push suppresses the pending nudge.
16. Pair revocation after FCM acceptance does not claim to retract a notification already handed to FCM.
17. All eligible devices at first dispatch are targeted using FIDs, with batch size no greater than 500 per FCM call.
18. An invalid/unregistered FCM target is deleted from the device registry and not retried.
19. Transient FCM failures leave retryable queue work with an appropriate future `nextAttemptAtMs`.
20. After four failed dispatch claims, the queue is terminally finalized and deleted.
21. No-eligible-device results retain the nudge for in-app synchronization.
22. Push failure does not delete the underlying nudge.
23. Foreground `partner_nudge` FCM reception posts a local notification immediately.
24. Background `partner_nudge` FCM reception shows the generic system notification.
25. The notification uses `partner_nudges` channel, private visibility, default importance and fixed tag `partner_nudge`.
26. A repeated nudge replaces the existing partner notification instead of creating a second feature notification entry.
27. Opening the app shows the most recent unacknowledged non-suppressed nudge from Firestore.
28. The in-app card remains correct even when the FCM notification is absent.
29. `I'm here` and `X` acknowledge every fetched unacknowledged non-suppressed nudge.
30. Acknowledgment cancels the active Partner SOS notification.
31. Notification-off banner appears when app notifications are disabled.
32. Muting the channel does not cause the app-level notification-off banner to appear.
33. Non-GMS devices do not crash and can display the nudge card when the app is opened.
34. Direct client writes to nudge, queue, rate-state and config paths are denied.
35. Sender cannot read recipient nudge documents.
36. Logs and FCM payloads contain no ciphertext, plaintext, access token, FCM token, or clear UID/pair ID.
37. Strict Mode + local blocker does not prevent the sender from invoking the feature.
38. Ordinary blocker restrictions on unrelated applications remain intact after the VPN exemption.
39. App Check blocks invalid callable access.
40. Server kill switch prevents acceptance regardless of client Remote Config state.
41. Remote Config disables the client UI when its flag is false.
42. All automated tests pass.
43. The complete device matrix is recorded.
44. Monitoring dashboards, alerts and runbook are deployed.
45. The staged rollout completes without unresolved critical reliability/security regressions.

---

## 18. Definition of Done

- [ ] `docs/partner-sos-mapping.md` is complete.
- [ ] Node.js 22+ is configured for Functions.
- [ ] Firebase Admin SDK supports FID-targeted FCM sending.
- [ ] The ADR amendment for Partner SOS metadata is approved and merged.
- [ ] Firestore schema, rules and indexes are deployed.
- [ ] TTL policies are enabled for nudge/queue retention cleanup.
- [ ] `/config/features.partnerSosEnabled` exists and defaults to false in production.
- [ ] `sendNudge` callable is implemented with Auth, App Check, pair validation, block validation, sliding cooldown, Nairobi daily cap and nudgeId idempotency.
- [ ] Nudge and queue are created atomically in one Firestore transaction.
- [ ] `onNudgeQueueCreated` is deployed and idempotent through the dispatch lease.
- [ ] `pushNudge` implements lease acquisition, target snapshot, FID multicast, per-device result handling and bounded retries.
- [ ] `resumePendingNudges` repairs stalled queue records without relying on global nudge scans.
- [ ] Device registry uses Firebase Installation IDs as document IDs and maintains `pushEligible` freshness.
- [ ] Android notification channel `partner_nudges` exists before notification posting.
- [ ] `POST_NOTIFICATIONS` flow is implemented for API 33+.
- [ ] Foreground FCM `partner_nudge` messages generate local notifications immediately.
- [ ] Background FCM `partner_nudge` messages use the generic system notification flow.
- [ ] MainActivity handles Partner SOS notification intents safely.
- [ ] Home Firestore listener renders the current non-suppressed unacknowledged nudge.
- [ ] Acknowledgment updates only `ackedAt` and cancels the corresponding notification.
- [ ] Partner block setting works and suppresses pending pushes.
- [ ] App/GMS VPN exemptions are implemented at the existing VPN builder site.
- [ ] Real-device VPN testing proves SOS connectivity while unrelated blocker enforcement remains intact.
- [ ] App Check is enforced on `sendNudge`.
- [ ] Play Integrity/App Check configuration is validated for the project's Play/APK distribution modes.
- [ ] Backend tests, rules tests, Android unit tests and instrumented tests pass.
- [ ] Abuse tests pass.
- [ ] Device/OEM matrix is recorded.
- [ ] Monitoring dashboard and alert rules are live.
- [ ] `docs/partner-sos-runbook.md` exists and has been exercised.
- [ ] DPIA and privacy notice are updated.
- [ ] Internal rollout succeeds.
- [ ] 5% rollout completes the required observation period without unresolved blocking regressions.
- [ ] 50% and 100% rollout gates are completed.
- [ ] Existing application test suite passes without regressions.
- [ ] Repository is tagged `partner-sos-v2.0`.
