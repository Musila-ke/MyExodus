# Partner Accountability Event Delivery and Device Availability: Implementation Plan

**Status:** Implementation specification  
**Product:** MyExodus Android  
**Platform baseline:** Android API 26+; Play-distributed release targets API 36 or the then-current Google Play requirement  
**Scope:** Integrate reliable, privacy-preserving partner event delivery and best-effort device availability reporting into the existing application. This is an additive feature, not a greenfield rewrite.

**Revision:** Updated after the plan-specific SRE/platform audit. The DMS now uses a durable delayed-check mechanism when already supported by the backend, otherwise a bounded indexed deadline worker; it separates status thresholds from partner push timing, accounts for ingestion outages, and adds partner-side alert health, monitoring-state snapshots, and sequence-gap detection. No new queue vendor, foreground service, or crypto protocol is assumed.

## 1. Feature Overview

Implement an additive partner-accountability delivery path for MyExodus. When an existing local monitoring or enforcement component emits a supported event, the feature will minimize the event data, create a durable encrypted outbox entry, send a separately authorized encrypted envelope to each eligible partner through the existing partner relay, and allow the partner application to synchronize, decrypt, deduplicate, and display the event.

The feature also reports whether a paired monitored device has recently checked in. The server-side Dead-Man's Switch (DMS) is an **availability detector**, not proof of uninstall or intentional tampering. It may notify currently authorized partners that the device has stopped checking in. It must never describe the reason as confirmed unless independent evidence establishes that reason.

The feature must work across major Android manufacturer distributions by using the application's existing Android monitoring components and documented platform APIs. It must not depend on XOS-only behavior or promise that monitoring survives every OS restriction, force-stop, device shutdown, permission revocation, or firmware variation.

### User-visible behavior

- An eligible event produces a minimal, category-based partner update without sending raw URLs, package names, notification contents, keystrokes, screenshots, or location.
- Each event is sent only to the partner relationship and event scope authorized when the event is created. The sender must not reroute an event to a different partner merely because its intended recipient is offline.
- If network delivery is unavailable, the event remains in the encrypted local outbox and is retried safely.
- Partners see the latest confirmed synchronization time and a clear status when the monitored device has not checked in recently.
- A missed heartbeat is displayed as “not checking in” or “unreachable,” never as confirmed uninstallation.
- Consent can be revoked or a relationship can be ended without blocking the user from disabling the feature. Revocation stops future delivery to that partner and removes not-yet-delivered events for that recipient where the system controls them. Information already decrypted by a partner cannot be recalled.

## 2. Requirements

### Functional requirements

1. Consume supported events from the existing MyExodus monitoring and enforcement engines. Do not implement a second independent detector for the same event.
2. Support, at minimum, these normalized categories where an existing detector can reliably produce them:
   - `BLOCKED_APP_ATTEMPT`
   - `BLOCKED_DOMAIN_ATTEMPT`
   - `MONITORING_PERMISSION_CHANGED`
   - `MONITORING_COMPONENT_UNAVAILABLE`
   - `DEVICE_STATUS_CHANGED`
3. Each event must have a random stable `event_id`, a schema version, a category, a coarse event time, an opaque local rule identifier where applicable, and the authorized recipient relationship/scope. Do not put raw user activity strings into the event schema.
4. Persist event creation and outbox insertion atomically. The event must not be considered queued unless its encrypted representation is durably stored.
5. Encrypt a separate event envelope for each authorized partner relationship using the existing reviewed partner-encryption implementation. Do not use one shared ciphertext whose group membership exposes events to unrelated partners.
6. Require the existing partner authentication and relationship authorization checks for enrollment, event upload, event fetch, acknowledgements, heartbeat registration, and revocation.
7. Store ciphertext durably server-side until acknowledged, expired, or invalidated by relationship revocation. Server delivery operations must be idempotent.
8. Synchronize missed events when the partner returns online. Push notification delivery is an optimization and must not be the source of truth for event history.
9. Implement the DMS as an explicit backend state machine with persistent state and outage compensation. Do not rely solely on Redis TTL expiration notifications.
10. On clean partner revocation, remove the revoked partner from future routing immediately and disarm DMS notifications to that partner. Do not use availability metadata to secretly preserve access after revocation.
11. Use generic push notification text. Push payloads must not include event categories that reveal intimate behavior, raw event data, or decryptable event content.
12. Retain the project's existing privacy and consent UI patterns. Explain that monitoring is best-effort and that device silence does not prove user intent.

### Non-functional requirements

- **Confidentiality:** The relay and push provider must not receive plaintext accountability event content.
- **Recipient isolation:** A partner must not receive events outside the application-level authorization scope. An event must never fall back to a different partner because of connectivity.
- **Integrity:** Event envelopes and device check-ins must be authenticated by the existing reviewed cryptographic/authentication mechanism.
- **Durability:** Local outbox and backend queue operations must survive ordinary process death and network retries.
- **Idempotency:** Duplicate uploads, pushes, and acknowledgements must not create duplicate partner-visible events.
- **Compatibility:** Core storage, synchronization, and event processing must use supported Android APIs and work on the project's supported Android/OEM matrix, subject to the documented permissions and delivery limitations of each device.
- **Battery:** Do not add a dedicated 5-second polling loop, exact-alarm heartbeat, second keep-alive process, or always-open custom socket. Measure incremental battery and wakeup behavior against the existing application baseline.
- **Privacy:** Do not log raw sensitive activity or add collection of location, messages, call logs, keystrokes, screenshots, or unrelated notifications.
- **Maintainability:** Reuse existing partner pairing, relay, Room/SQLCipher, dependency-injection, and WorkManager conventions. Do not introduce MLS, Sender Keys, a new cryptographic protocol, a new database, or a new backend runtime for this feature.

### Out of scope

- Replacing or redesigning MyExodus's current monitoring, VPN, DNS filtering, overlay, pairing, partner activity, or Partner SOS implementations.
- Making the app impossible to force-stop, uninstall, clear data from, or bypass.
- Accessibility UI automation that interferes with system settings, permission revocation, service deactivation, or uninstall flows.
- Adding `NotificationListenerService`, browser URL scraping, a new `AccessibilityService`, a second Android process, full-screen intents, or exact alarms solely for partner accountability.
- Guaranteeing five-second delivery, uninterrupted execution on every OEM, or detection of the specific cause of a missing heartbeat.
- Adding iOS implementation work in this change.

## 3. Existing System Context

MyExodus is an existing Kotlin Android application using Jetpack Compose, Room with SQLCipher, Hilt, WorkManager, DataStore/Tink, and Firebase-related integrations. The project already has partner pairing, partner activity, and Partner SOS/relay design decisions. The implementation must extend these existing systems instead of creating parallel infrastructure.

Relevant established project context to inspect and preserve:

- Partner pairing: ADR #55.
- Partner SOS / zero-knowledge relay: ADR #42.
- Partner activity: ADR #46 and ADR #111, including any later amendments or superseding decisions.
- Existing monitoring components using `UsageStatsManager`, the current VPN/DNS filtering path, and the current blocking/overlay mechanism.
- Existing Room/SQLCipher schema and migration conventions.
- Existing WorkManager scheduling and retry conventions.
- Existing authentication/session handling, relay API, push token registration, backend persistence, and operational monitoring.

The repository is the source of truth for exact class names, packages, data structures, and backend technology. Do not assume a greenfield Node.js/Go service, Redis deployment, PostgreSQL database, or a new Firebase database. Extend the currently authoritative relay/persistence service and its existing deployment model. Do not move personal data to a new region or provider as part of this feature.

Before editing, locate and read the ADRs above, the current partner event flow, encryption/decryption entry points, consent/revocation code, current notification behavior, Room entities/DAOs/migrations, backend relay handlers, and production deployment configuration. Record the exact existing files in the implementation pull request. Do not rename unrelated classes or perform broad refactors.

### Required baseline verification

Confirm in code that the current partner cryptographic boundary provides authenticated encryption for the intended recipient and that the server cannot decrypt the event body. Reuse it. Do not implement a new cipher, custom ratchet, custom HKDF key schedule, MLS layer, or Sender Key protocol.

If the existing implementation cannot provide authenticated recipient-specific encryption, stop before shipping the event-delivery path and report the exact missing guarantee. Do not silently substitute unauthenticated encryption or invent a parallel protocol. This is a security gate, not permission to improvise.

## 4. Architecture

### Components and responsibilities

1. **Existing monitoring engines**
   - Continue to detect application usage, supported blocked-domain events, permission status, and monitoring component health through the existing implementations.
   - Emit normalized internal events to a narrow partner-accountability interface.
   - Do not gain new system permissions as part of this feature.

2. **Partner Event Policy**
   - Resolve active partner relationships and event scopes using the existing consent state.
   - Produce an immutable authorization snapshot for each event.
   - Never route an event to a partner who is not authorized for the event's scope.
   - Do not use recipient connectivity as authorization to reroute.

3. **Event Minimizer and Schema Validator**
   - Convert detector output into an allowlisted schema.
   - Remove raw URLs, hostnames, package names, app labels, notification text, and other identifying strings.
   - Reject unknown event fields rather than serializing arbitrary detector payloads.

4. **Partner Event Outbox**
   - Use the existing Room/SQLCipher storage approach.
   - Store stable event identifiers, recipient-specific encrypted envelopes, delivery state, attempt metadata, and creation time.
   - Persist before network dispatch and keep retry operations idempotent.

5. **Existing Pairwise Partner Cryptography**
   - Use the current, reviewed per-partner encryption/session abstraction from the partner pairing/relay implementation.
   - Produce one separately authenticated encrypted envelope per authorized recipient.
   - No global shared group key. No MLS. No Group Sender Keys. No custom cryptographic protocol.
   - Do not claim forward secrecy or post-compromise security beyond the guarantees actually provided by the existing implementation.

6. **Partner Relay API and Durable Store**
   - Extend the existing relay API to accept, persist, fetch, acknowledge, and expire opaque ciphertext envelopes.
   - Authenticate and authorize every operation.
   - Use a unique idempotency key for each recipient-specific envelope.
   - The server may observe network source information transiently as part of transport. Do not persist source IP addresses in heartbeat records or include them in analytics; apply the existing security-log retention/minimization policy where transport logs require them. Routing identifiers, envelope size, timestamps, and device availability metadata remain privacy-sensitive even without event plaintext.

7. **Availability Heartbeat Client**
   - Count every fresh, authenticated request from a registered monitored installation as liveness, including heartbeat, successful event upload, sync, and app-open synchronization. Requests must pass existing authentication and replay/idempotency checks before updating liveness; a replayed stale request is not liveness. Liveness means the app communicated with the server, not that monitoring is active.
   - Use the existing Monitoring Foreground Service as an opportunistic heartbeat execution path only while it is already running for its existing, user-visible and policy-justified monitoring purpose. Send on service start, material monitoring-state changes, and at most one jittered periodic interval while that service is active. Do not start or keep the service alive solely to send heartbeats.
   - Keep WorkManager periodic work as best-effort fallback and recovery. Its minimum periodic interval is 15 minutes, not a delivery guarantee. Do not claim the FGS bypasses Doze network restrictions or OEM firmware behavior.
   - Persist a monotonically increasing counter per installation identity. A cleared-data/reinstalled device must re-enroll with a new installation identity and counter namespace; it must not be permanently locked out because an old counter was lost.
   - On API 28+, inspect `UsageStatsManager.getAppStandbyBucket()` only at startup, check-in, or relevant diagnostics. Report `RARE`/`RESTRICTED` as a best-effort scheduling warning; do not try to manipulate the bucket, repeatedly nag, or request battery-optimization exemptions as a workaround.

8. **Backend DMS State and Deadline Processor**
   - Persist device-installation liveness, the last accepted counter, the current deadline generation, the next deadline, notification idempotency state, and backend-ingestion outage intervals in the existing authoritative store.
   - Prefer an already available durable delayed-job/queue facility if it supports delayed execution, retry, and idempotent consumers. A new broker must not be introduced solely for this feature.
   - If the repository has no suitable delayed queue, use an indexed, bounded deadline worker against the existing database. Query only due rows ordered by `next_deadline_at` with a supporting index and a fixed batch/claim limit. Never run an unbounded full-table scan every five minutes.
   - Every heartbeat increments a `deadline_generation` and updates the next deadline. A delayed message or worker claim must include the generation/expected liveness version; stale checks become no-ops. Consumers must be idempotent and use compare-and-set state changes.
   - Persist a server-side notification outbox in the same transaction as a DMS notification transition. Recheck the active relationship and its `DEVICE_AVAILABILITY_STATUS` consent when dispatching. Do not copy relationship membership into an independently authoritative DMS table.

9. **Push Notification Adapter**
   - Use the existing supported provider adapter. Push is a generic wake-up or generic user-visible status notification; the authenticated relay/status API remains authoritative.
   - A partner push payload must not include event category, domain, package name, user/device identity, relationship ID, precise heartbeat time, or event content. Use a small generic payload such as `{"v":1,"type":"SYNC_AVAILABLE"}`; partner clients fetch current state and pending ciphertext after receiving it.
   - On entry to `LATE`, send at most one generic, user-visible check-in reminder to the monitored device if the existing provider is available, notification permission/settings permit it, and the message can result in a visible notification. Treat it as a best-effort nudge, not a wake-up guarantee.
   - Do not use high-priority FCM for silent/background-only work. Use high priority only for eligible time-sensitive messages that result in a user-visible notification. Measure delivered/processed outcomes where observable; FCM acceptance is not a delivery receipt.
   - On partner devices, detect notification permission/channel disablement where APIs permit and show an in-app health banner. If GMS/HMS push is unavailable, retain inbox sync and state the delivery limitation clearly.

10. **Partner Inbox, Monitoring Status, and Alert Health**
    - Synchronize, authenticate, decrypt, deduplicate, sequence-check, and persist incoming events.
    - Distinguish server-observed `CHECKING_IN` from the monitored component's reported state. Never label a device `HEALTHY` solely because a heartbeat arrived.
    - Include a recipient-specific encrypted status snapshot when monitoring state changes and during appropriate subsequent sync/heartbeat cycles. The snapshot includes only `monitoring_state` (`ACTIVE`, `DEGRADED`, `UNAVAILABLE`, or `UNKNOWN`), `outbox_age_bucket`, the installation generation, and the latest committed per-relationship sequence. The server cannot read this snapshot.
    - Mark monitoring state `UNKNOWN` when the last authenticated status snapshot is stale. Report `ACTIVE` only after a live check confirms the required permissions and monitoring components are operating; do not infer activity from a persisted desired-state flag.
    - Display coarse availability buckets (for example, “within the last hour,” “1–6 hours ago,” and “over 6 hours ago”) rather than minute-by-minute check-in timestamps. Separate connection status from monitoring status and event-delivery completeness.
    - During partner onboarding, check `POST_NOTIFICATIONS` on API 33+, whether the relevant notification channel is enabled and its importance, and whether a supported push provider is registered. Offer an explicitly user-triggered test notification and show a persistent in-app banner if alerts cannot be displayed or push is unavailable.
    - If a partner device has not synchronized after a status/event push, allow at most one generic reminder after six hours while authorization remains active. Do not treat it as proof that the user saw the original notification.

### DMS states and user-visible notification timing

Use server receipt time and any documented ingestion-outage compensation, never client wall-clock time, as the basis for availability calculations. The state shown in the app is derived at read time from the effective last-received time; background jobs exist to schedule notifications and persist transition/idempotency state, not to make the displayed status depend on a timely sweep.

- `CHECKING_IN`: effective last authenticated activity is less than 45 minutes old.
- `LATE`: no authenticated activity for at least 45 minutes but less than 90 minutes. Update the in-app status when next read/synchronized and send at most one generic check-in reminder to the monitored device where supported.
- `NOT_CHECKING_IN`: no authenticated activity for at least 90 minutes. Display the status in the partner UI on the next successful read, but do not yet send an external partner push.
- Partner availability push threshold: six hours since the effective last authenticated activity as the initial production setting. Send at most one generic status push per outage generation, only to relationships that explicitly enabled `DEVICE_AVAILABILITY_STATUS` sharing. Tune this value only after a documented OEM soak study and false-alert review; do not shorten it solely to meet an arbitrary latency target.
- `RECOVERED`: any fresh, valid authenticated request restores current liveness. If a partner was sent a six-hour notice, send at most one generic recovery update. A valid request is not, by itself, proof that the monitoring components are active.

Status labels and thresholds are distinct from push timing. FCM, OEM scheduling, device shutdown, force-stop, connectivity loss, and permission changes can delay or prevent check-ins or notifications. Heartbeat silence means “not checking in,” not confirmed uninstall or intentional tampering.

### DMS outage and consent semantics

- **Scheduler/worker outage:** Heartbeats may still be recorded. Preserve their real server receipt times; do not rebase device deadlines merely because the deadline processor was unavailable. On recovery, process due records in bounded batches and rate-limit notification dispatch. If a fleet-wide spike exceeds the configured incident threshold, hold partner pushes and alert the operator until the incident is reviewed.
- **Heartbeat-ingestion outage:** Record the outage interval and affected service/region. For affected installations whose deadline overlapped that outage, calculate the effective baseline as `max(last_received_at, relevant_ingestion_outage_end)`. Grant the normal status/notification windows from that baseline. Do not apply this compensation for a sweep-only outage.
- **Correlated client-network outage:** The server cannot know the cause from individual heartbeat silence. Detect a sudden fleet-wide rise in `LATE` transitions, open an operational incident, and suppress partner push fan-out while the incident is being assessed. Do not silently mark devices recovered or rewrite their histories.
- **Consent:** Availability sharing is a separate, explicit relationship scope (`DEVICE_AVAILABILITY_STATUS`). Revocation takes effect immediately on the client. The backend stops future notifications when the authenticated revocation reaches it. If revocation occurs while offline, the server cannot know immediately; onboarding must disclose this limitation, and the client must show that server-side revocation is pending until acknowledged. Do not claim instant remote revocation without connectivity.
- At dispatch time, join to the current relationship/consent source of truth. Never send DMS notices to a revoked relationship, even if an old queue message remains.
- Information already decrypted, copied, screenshotted, exported, or backed up by a recipient cannot be recalled.

## 5. Architecture Decisions

- **Extend the existing MyExodus partner architecture:** avoids a parallel identity, event, or backend system.
- **Use one recipient-specific encrypted envelope per authorized partner:** prevents other partners from receiving a shared group message outside their scope.
- **Reuse the existing reviewed partner encryption implementation:** avoids custom cryptography and avoids adding MLS, Sender Keys, or an unsupported new ratchet to this feature.
- **Treat event scope as an application authorization rule:** only selected categories are routed to each partner; do not claim that a compromised sender client is cryptographically prevented from deliberately misreporting its own event category.
- **Keep DMS separate from E2EE event content:** the backend may process minimal heartbeat metadata but cannot create or decrypt accountability event bodies.
- **Use server receipt time and generation-checked DMS deadlines:** prevents stale checks and device-clock changes from deciding server status.
- **Use the existing durable delayed-job mechanism if available; otherwise use indexed, bounded deadline scans:** avoids both lossy TTL-only triggers and unbounded table sweeps without adding a new broker by default.
- **Use the existing monitoring FGS opportunistically plus WorkManager fallback:** heartbeats may piggyback only while the existing service is legitimately running; neither mechanism guarantees a fixed cadence across Doze/OEM conditions.
- **Separate availability states from notification timing:** show coarse `LATE`/`NOT_CHECKING_IN` status earlier, but begin partner push escalation at six hours as the initial measured/tunable policy.
- **Treat check-in and monitoring health as separate signals:** a live heartbeat is not proof that VPN/accessibility/usage monitoring is active.
- **Use push as an alert/wake-up channel and the relay as the source of truth:** prevents push loss from becoming event-history loss.
- **Keep the existing monitoring APIs unchanged:** this feature consumes normalized events rather than adding new system-level sensors.
- **Do not add anti-uninstall or permission-blocking UI automation:** preserves voluntary consent and reduces Play-policy and coercion risks.
- **Do not add a generic foreground service or a second process for this feature:** use the current monitoring service only where its existing, declared use case permits it.
- **Do not use full-screen intents or `SYSTEM_ALERT_WINDOW` as a new partner-alert mechanism:** partner updates are delivered through notifications and the app UI.
- **Treat all manufacturer-specific behavior as a measured compatibility concern:** use documented APIs and a repeatable OEM test matrix rather than a special-case XOS implementation.
- **Do not promise forward secrecy or post-compromise security unless the current cryptographic implementation demonstrably provides them:** document the actual protocol guarantees.
- **Do not introduce raw event data into logs, push payloads, analytics, or crash reports:** keep sensitive content out of observability systems.

## 6. Data Flow

### Outbound accountability event

1. An existing MyExodus detector emits a typed event. The event is mapped to a user-consented coarse category; raw activity strings never enter the partner event model.
2. The policy layer snapshots currently authorized relationship scopes. If no relationship has consented to the category, no partner envelope is created.
3. In a local SQLCipher transaction, allocate the next per-relationship `relationship_seq` and persist a minimized event row. The sequence is monotonic within the sender-install/relationship stream and is included inside the encrypted event body.
4. Prepare the recipient-specific envelope through the existing reviewed crypto API. Follow its state semantics: never manually manipulate keys; never re-encrypt a stateful-ratchet message after its outbound state has been committed. Persist the envelope and idempotency key before sending it over the network.
5. Upload using an idempotency key scoped to `(sender_install_id, relationship_id, event_id)`. The relay authenticates the sender, rechecks current authorization, and durably stores only opaque ciphertext.
6. After durable relay acceptance, delete the local ciphertext and minimal event body according to the existing retention policy, retaining a small delivery-state row. The sender does not retain ciphertext indefinitely while waiting for the partner to open the app.
7. The relay writes a generic push-trigger record to its transactional notification outbox. Push payloads do not identify the relationship or event category.
8. The partner fetches pending ciphertext through an authenticated pull path, decrypts through the existing crypto API, verifies schema and `relationship_seq`, persists once, and acknowledges after durable local commit.
9. If the receiver observes a sequence gap, it first attempts relay resynchronization. If missing sequence numbers cannot be recovered because an event expired or was evicted, display “possible missing updates” and store a `GAP_MARKER` covering the missing range when the sender can safely emit one. Never display the gap as proof of misconduct.

### Liveness, DMS, and monitoring state

1. The existing monitoring FGS sends opportunistic liveness updates only while already active for its legitimate, declared monitoring purpose. It also reports on service start and real monitoring-state changes. It is not started solely to heartbeat.
2. WorkManager sends best-effort periodic liveness updates with a network constraint, and runs after other eligible app work. App open/resume and any other valid authenticated backend call also refresh liveness. The schedule is not a guarantee.
3. The backend authenticates the installation and checks a monotonic counter scoped to that installation identity. It records server receive time, increments `liveness_generation`, and updates the next DMS deadline.
4. The backend schedules a generation-bound delayed check through the existing durable queue when available; otherwise, an indexed bounded worker claims due deadlines. Stale messages or claims become no-ops after a newer heartbeat.
5. The server derives the current availability bucket from the effective last receipt time. It does not see the encrypted monitoring-state snapshot.
6. The client sends a recipient-specific encrypted monitoring-state snapshot on relevant state changes and later eligible check-ins. The partner UI displays availability and monitoring health separately.
7. At six hours without valid liveness, the DMS transactionally records a generic notification intent for active, consenting relationships. The dispatcher rechecks current relationship authorization before sending.
8. A new valid request resets liveness. If the prior outage generated a partner notice, the server emits at most one generic recovery notice. This signals device contact, not proof that monitoring was active throughout the gap.

## 7. Implementation Sequence

### Step 1: Repository and ADR inventory

**Objective:** Identify the authoritative existing implementation before changing anything.  
**Components:** Partner pairing, Partner SOS/relay, partner activity, Room/SQLCipher, WorkManager, existing detector event models, backend relay, push integration.  
**Required work:** Read ADR #42, #55, #46, and #111 plus superseding ADRs. Trace one current partner event end-to-end. Record the exact files, tables, APIs, and key interfaces that already implement pairing, consent, encryption, relay, push, and deletion.  
**Validation:** Produce a short in-code change map. Confirm no parallel relay, crypto engine, queue, or backend datastore is needed.

### Step 2: Security and consent integration gate

**Objective:** Prove that the current pairwise crypto path and consent state are suitable for event delivery.  
**Components:** Existing cryptographic wrapper, partner enrollment/session, relationship access control.  
**Required work:** Verify recipient authentication, ciphertext confidentiality, replay/duplicate handling, key invalidation behavior, and whether a relationship can be revoked before pending events are sent. Add only missing wrappers around the existing reviewed protocol.  
**Validation:** A server-side test must demonstrate that stored event envelopes cannot be decrypted by the relay. Cross-partner tests must demonstrate that a partner cannot fetch another relationship's events. If these conditions cannot be met without custom cryptography, stop implementation and report the blocker.

### Step 3: Define the event contract and minimizer

**Objective:** Introduce a versioned allowlisted event envelope.  
**Components:** Existing detector interface, event schema, policy evaluator.  
**Required work:** Define event types, required fields, maximum serialized size, coarse time representation, schema version, stable event ID, recipient relationship, and delivery state. Reject raw URLs, domain strings, package names, app labels, notification text, arbitrary JSON fields, and location.  
**Validation:** Unit tests for every allowed event type and rejection tests for every prohibited data field.

### Step 4: Durable outbox and sequence contract

**Objective:** Persist minimal events reliably and detect missing partner-visible updates.  
**Components:** Existing Room/SQLCipher database, entities, DAOs, migrations, pairing/crypto adapter.  
**Required work:** Add only the minimum additive schema needed for sender installation identity, event ID, relationship ID, per-relationship sequence, schema version, minimized body or prepared encrypted envelope, delivery state, idempotency key, attempts, next attempt, expiry, and last error code. Allocate sequence numbers transactionally. Add uniqueness constraints for `(sender_install_id, relationship_id, event_id)` and `(sender_install_id, relationship_id, relationship_seq)`, plus an index for pending work. Exclude live counters and crypto state from backup unless the current secure transfer design explicitly supports restoration.

The crypto integration gate in Step 2 determines when the envelope is produced. For stateless recipient encryption, create and persist the envelope before networking. For a stateful ratchet, follow the existing library's durable outbound-state contract and persist the committed envelope so a retry reuses the exact same ciphertext and idempotency key. If safe crash recovery cannot be proven, stop and report a blocker.

**Validation:** Migration/rollback, concurrent sequence allocation, transaction rollback, process death before and after encryption, same-envelope retry, outbox eviction, and clear-data/re-enrollment tests.

### Step 5: Extend the existing relay API

**Objective:** Add durable ciphertext upload/fetch/acknowledgement using the established backend.  
**Components:** Existing relay request handlers, authorization middleware, persistence, cleanup, and rate limiting.  
**Required work:** Accept opaque envelopes; authenticate the sender installation; verify active relationship authorization; enforce size/rate limits; persist idempotently; return a stable acceptance receipt; fetch only for the intended recipient; and acknowledge after recipient persistence. After relay acceptance, the sender may delete the local ciphertext while keeping a minimal delivery-state row. The server retains its own ciphertext until recipient acknowledgement, expiry, or revocation. Do not log payloads and do not add Redis or a new backend vendor solely for deadlines.

**Validation:** Unauthorized access, duplicate upload, retry after lost response, payload-size, retention, recipient acknowledgement, revocation, server restart, and queue backlog tests.

### Step 6: Partner synchronization, sequence checks, and notification health

**Objective:** Ensure push failure does not lose history and alert-receiving failures are visible.  
**Components:** Existing partner inbox/repository, decryption wrapper, local persistence, push handler, onboarding/settings UI.  
**Required work:** Fetch pending ciphertext after push, on app start/resume, and through existing scheduled sync. Decrypt, validate, sequence-check, deduplicate, persist, then acknowledge. Onboarding must check notification permission on API 33+, channel enablement/importance, provider registration, and run an explicitly user-triggered test notification. Show an in-app banner when notifications are disabled or a push provider is unsupported. For a DMS availability notice only, if the partner has not synchronized within six hours, allow at most one generic reminder per `(install_id, relationship_id, outage_generation)`. Do not create a separate reminder for every ordinary event push and do not interpret provider acceptance as proof of display.

**Validation:** Duplicate/out-of-order envelopes, sequence gaps, offline partner, invalid auth, disabled notification permission/channel, provider unregistered, non-GMS device, decryption failure, and process death between persist and acknowledgement.

### Step 7: Heartbeat client and monitoring-state snapshot

**Objective:** Provide authenticated best-effort availability and truthful monitoring-health status.  
**Components:** Existing Monitoring FGS if present and verified, WorkManager worker, device/install identity, API client, existing monitoring permission/component state providers.  
**Required work:** During Step 1, verify whether an existing Monitoring FGS exists, its declared type/purpose, and current Play-policy status. If it exists and is already legitimately active, piggyback a jittered heartbeat no more often than every 15 minutes while active, plus a check-in at start and on material state changes. Do not start/restart/keep alive the service solely for heartbeat traffic. If no suitable existing FGS exists, do not add one for this feature; use WorkManager and other already-authorized requests as best effort. A running FGS improves execution opportunity but does not bypass all Doze/network restrictions or OEM policy.

Schedule a 15-minute periodic WorkManager fallback with a connected-network constraint. Count all fresh authenticated requests as liveness after replay/idempotency checks. Use a counter scoped to `sender_install_id`. A cleared-data/reinstalled device must complete authorized re-enrollment with a new install identity and fresh counter namespace; never reject it forever because its old counter was lost.

At API 28+, inspect `UsageStatsManager.getAppStandbyBucket()` at startup/check-in or after an observed delay, not in a tight polling loop. If Rare/Restricted, record a low-sensitivity diagnostic and show no more than one non-intrusive explanation per transition/session. Offer optional settings guidance, but do not claim setting the app to Unrestricted is required or guarantees delivery. Never request new battery-exemption permissions for this feature.

Produce an E2EE status snapshot per authorized relationship when status changes and during eligible check-ins. Report `ACTIVE` only after confirming current permissions and required components; otherwise use `DEGRADED`, `UNAVAILABLE`, or `UNKNOWN`. Include only the current install generation, an outbox-age bucket, and latest committed `relationship_seq`; do not place these fields in plaintext heartbeat metadata.

**Validation:** Counters/replay, reinstall/new identity, FGS active/inactive paths, WorkManager delays, force-stop, reboot-before-unlock, no network, App Standby Rare/Restricted, missing notifications, and stale monitoring-state snapshot tests.

### Step 8: Backend DMS deadline processor and durable notifications

**Objective:** Process device-availability deadlines without table-wide scans, lost expiry events, or duplicate/misleading alerts.  
**Components:** Existing backend database, existing durable delayed-job facility if available, otherwise an indexed deadline table and bounded worker, server notification outbox, operator health monitoring.  
**Required work:** Inspect existing infrastructure and use a durable delayed-job mechanism only if it already supports scheduled execution, retry, idempotency, and stale-job validation. Otherwise, create/update a deadline row in the current persistence store and run a bounded worker querying only rows due before `now`, ordered by `next_deadline_at` with a supporting index. No unbounded full-table scan and no Redis Pub/Sub/key-expiry event as the sole trigger. Do not introduce a new queue service without a separate cost/operations approval.

Every accepted liveness update increments `liveness_generation` and records `last_received_at`, `last_counter`, and `next_deadline_at`. Delayed jobs contain `install_id`, expected generation, and expected deadline; stale jobs become no-ops. Worker state changes use compare-and-set. The notification outbox is committed in the same transaction as the notification state transition. At send time, rejoin the authoritative relationship table and recheck `DEVICE_AVAILABILITY_STATUS` consent.

Display `CHECKING_IN`/`LATE`/`NOT_CHECKING_IN` at the defined 45/90-minute thresholds, but send partner availability push only at the initial six-hour threshold. A scheduler outage does not rebase heartbeat timestamps. A heartbeat-ingestion outage is recorded separately; deadlines for affected installations whose interval overlapped it are rebased to `max(last_received_at, relevant_ingestion_outage_end) + threshold`. Add a fleet circuit breaker: when late transitions exceed the deployment-configured baseline threshold in a short window, hold partner push dispatch, page the operator, and investigate a service or correlated network incident before releasing notifications at a controlled rate.

**Validation:** 100k-install load/soak test against the chosen queue/deadline implementation; stale delayed messages; concurrent workers; CAS duplicate transitions; transaction-crash between transition/outbox/dispatch; queue failure; scheduler outage; ingestion outage; a correlated-client-outage simulation; and controlled catch-up after recovery.

### Step 9: Generic push notifications

**Objective:** Notify partners without leaking event content or pretending dispatch equals delivery.  
**Components:** Existing push sender, token registry, server notification outbox, client notification handler.  
**Required work:** Use the generic payload schema in Section 8.10. Use high-priority FCM only for user-visible, time-sensitive notifications and include no content that should require a subsequent network request to interpret the initial notice. Use normal priority for ordinary sync. Apply TTL/collapse identifiers to suppress stale duplicate prompts. On `UNREGISTERED` or equivalent provider response, remove the invalid push token; do not interpret it as conclusive proof of app uninstall. Provide in-app sync and status when notifications cannot be delivered.

**Validation:** Payload inspection, delayed/expired/collapsed push, notifications disabled, channel blocked, provider token invalidation, non-GMS device, Doze tests, and separation of send acceptance from client sync/display metrics.

### Step 10: Consent revocation and relationship exit

**Objective:** Ensure all paths enforce current authorization.  
**Components:** Consent repository, pairing UI, local outbox, relay access control, DMS relationship state.  
**Required work:** On revocation, atomically update local authorization, remove unsent recipient envelopes, send the authorized relationship-revocation request if online, and reject future fetch/upload for that relationship. Do not reroute queued events. Ensure DMS does not keep notifying the revoked partner. If offline, the local revocation takes effect immediately for local routing and is retried to the server; the UI must accurately show that server-side revocation is pending until acknowledged.  
**Validation:** Revoke during event creation, after encryption, during upload, after server storage, during partner fetch, and while offline. Verify no new event reaches the revoked partner through any alternate path.

### Step 11: Cross-OEM compatibility and release checks

**Objective:** Validate common behavior across supported Android skins.  
**Components:** Current monitoring components, WorkManager jobs, overlay/VPN behavior already in the app, push integration, partner UI.  
**Required work:** Run the test matrix in Section 12 on physical devices. Record manufacturer, model, OS/build, GMS/HMS presence, permission configuration, network type, and reproduction steps. Do not add manufacturer-specific code until a reproducible incompatibility is demonstrated.  
**Validation:** Review the compatibility report and all permission/policy checks before enabling the feature for production users.

### Step 12: Staged rollout and operational verification

**Objective:** Release without exposing all existing users to an unvalidated change.  
**Components:** Existing feature-flag mechanism, backend dashboards, app release pipeline.  
**Required work:** Gate event transport and DMS notification behavior behind the project's existing feature-flag mechanism if one exists. Roll out to internal devices, a small opted-in cohort, then expand only after duplicate/lost-event rates, DMS false-positive behavior, notification delivery, and battery measurements meet the measured release criteria. Do not add a new remote-config dependency solely for this feature.  
**Validation:** Demonstrate rollback and confirm disabling the feature stops new partner sends without deleting unrelated user data.

## 8. Detailed Implementation Steps

### 8.1 Event data contract

Use a versioned typed model. Do not serialize arbitrary detector objects. The model must contain only:

- `schema_version`
- `event_id` (random UUID or existing project-standard opaque identifier)
- `relationship_seq` (monotonic within the sender-install/relationship stream)
- `event_kind` (`ACCOUNTABILITY_EVENT`, `MONITORING_STATUS`, or `GAP_MARKER`)
- `event_category` (allowlisted and explicitly consented coarse category, such as `ADULT_CONTENT`, `SOCIAL_MEDIA`, `GAMBLING`, `SHOPPING`, `OTHER`, or `MONITORING_STATUS` only where enabled by the user)
- `occurred_at_minute` (time rounded to the minute; untrusted client metadata, not used for DMS deadlines)
- `monitoring_state` (only for status snapshots: `ACTIVE`, `DEGRADED`, `UNAVAILABLE`, or `UNKNOWN`)
- `outbox_age_bucket` and `latest_relationship_seq` only for monitoring-status snapshots
- `missing_seq_start` and `missing_seq_end` only for a `GAP_MARKER`

Do not include an opaque `rule_ref` in the partner-visible body unless the existing product demonstrably maps it to a user-consented, understandable category. Partners see only consented coarse category labels, not raw rule names or arbitrary rule identifiers.

Recipient identifiers and delivery state belong in the local/relay envelope metadata, not in the user-visible event body unless needed for recipient validation. Do not include URLs, hostnames, package names, visible application names, notification contents, typed text, screenshots, geolocation, or raw rule names. Use the existing serialization library and conventions. Add explicit maximum lengths and reject unknown fields.

### 8.2 Event authorization

At event creation, snapshot active consent for each recipient and event category. For each event, generate envelopes only for the authorized recipients. Do not add a primary-partner fallback, secondary-partner fallback, or “send to whoever is online” rule. Connectivity is not an authorization property.

Before envelope creation and before each upload attempt, recheck whether the recipient relationship and category scope remain authorized. The backend must independently recheck server-side relationship status. The client-side check and backend check are both required because a local state update does not instantly revoke already stored server-side access while the device is offline. If a prepared envelope must be cancelled after a stateful ratchet has advanced, follow the existing protocol's supported cancellation/recovery semantics; if safe continuation cannot be proven, stop delivery for that relationship and require its documented re-enrollment/reset flow. Never manually skip or repair ratchet state.

### 8.3 Existing encryption boundary

All event bodies must pass through the existing reviewed partner encryption API. The caller must not see or manipulate low-level keys, derive new keys manually, choose nonces, or write cryptographic primitives. The serialized content must be encrypted independently for each recipient. Bind the event identifier and schema version to the authenticated context if the existing crypto API supports associated context.

The server stores and forwards only opaque ciphertext. Server-side metadata is limited to opaque routing identifiers, envelope ID, payload length, server receive time, expiry, and acknowledgement state. Record that these metadata fields can still reveal social relationships and timing.

### 8.4 Outbox state model

Use the following event states, or map them to equivalent existing project states:

- `PENDING`: persisted and waiting for an upload attempt.
- `IN_FLIGHT`: a request is in progress; a crash must not make the event unrecoverable.
- `ACCEPTED_BY_RELAY`: the relay has durably stored the ciphertext; delete the sender-local ciphertext bytes and retain only minimal delivery-state metadata.
- `ACKNOWLEDGED_BY_RECIPIENT`: the relay has recorded that the partner decrypted and persisted the event, if the current protocol supports that acknowledgement. This server-side state does not require the sender to retain its ciphertext.
- `CANCELLED_BY_REVOCATION`: delivery was cancelled because authorization ended before delivery.
- `EXPIRED`: retention policy ended delivery attempts.
- `FAILED_NEEDS_REPAIR`: a permanent crypto/schema/identity issue requires user-visible recovery rather than endless retry.

A timeout after upload must retry with the same idempotency key. Do not generate a new event ID because the HTTP response was lost. Acknowledgement semantics must be explicit: relay acceptance is not partner receipt, and push dispatch is not proof that the partner saw an event.

### 8.5 Retry behavior

Reuse the existing retry policy where possible. Use exponential backoff with jitter and a bounded maximum delay. Persist retry metadata. Retry transient network, rate-limit, and server errors. Do not retry permanent authorization failures, invalid ciphertext, revoked relationships, or unsupported schema versions indefinitely. A later app launch must resume pending work. Keep the local queue bounded by retention and maximum queue size; surface a monitoring gap when queue eviction becomes necessary.

Permission/tamper state changes and DMS recovery events must not be silently merged into ordinary blocked-attempt digests. Repeated ordinary blocked attempts may be summarized into a count and coarse time window, but the summary must flush on both threshold and a maximum-age trigger. The design must not require a tenth event to flush a nine-event digest.

### 8.6 Partner inbox

For each fetched envelope:

1. Verify the recipient relationship and envelope metadata.
2. Verify authenticity and decrypt through the existing crypto interface.
3. Validate the decrypted schema and allowed event category.
4. Insert using a unique event ID and a database transaction.
5. Commit the event locally before sending a recipient acknowledgement.
6. Ignore duplicates safely.
7. If an envelope cannot be decrypted, preserve diagnostic status without logging plaintext or cryptographic material. Request synchronization or re-pairing according to the existing identity-recovery path.

### 8.7 Data retention and deletion

Use the existing product retention policy if one is already enforced. Otherwise, apply these defaults for this feature:

- Pending relay ciphertext: expire after 30 days unless the current product's explicit retention policy is shorter.
- Partner-local event detail: retain for 30 days, then delete through the existing cleanup mechanism.
- Local outbox envelopes: delete after recipient acknowledgement, revocation, or expiry according to the durable delivery contract.
- DMS: retain current state and the minimum transition/outage metadata necessary for reliability and abuse investigation; do not expose precise minute-by-minute history to partners or build a detailed heartbeat history by default.
- On relationship revocation: immediately block access, delete pending envelopes for that relationship where possible, and schedule cleanup of remaining relationship metadata under the established retention policy.

Deletion is best-effort after a recipient has already received an event. Do not promise deletion of screenshots, exports, external backups, or information already learned by another person.

### 8.8 Key loss and re-enrollment

Do not restore private partner cryptographic state from server ciphertext. On permanent key invalidation, application-data clear, or device replacement, require the existing pairing/re-enrollment flow and re-verify the partner identity. A new installation receives a new device/install identity and must not impersonate the previous cryptographic state. Historical events that cannot be decrypted remain unavailable; do not silently mark them as successfully restored.

Exclude keys and cryptographic state from Android Auto Backup and device transfer unless the existing implementation has an explicitly designed, tested secure migration procedure. Verify the actual manifest and `dataExtractionRules`; do not assume defaults.

### 8.9 Required logical client schema

Map these logical fields to existing Room entities if equivalent fields already exist; do not create duplicate tables unnecessarily.

| Logical record | Required fields/invariants |
|---|---|
| `partner_event_outbox` | `event_id`; `sender_install_id`; `relationship_id`; `relationship_seq`; `schema_version`; minimized event body stored only inside SQLCipher; nullable `envelope_ciphertext`; `delivery_state`; scoped `idempotency_key`; `attempt_count`; `next_attempt_at`; `created_at`; `expires_at`; `last_error_code`. Unique `(sender_install_id, relationship_id, event_id)` and `(sender_install_id, relationship_id, relationship_seq)`. Index pending state plus `next_attempt_at`. |
| `relationship_sequence` | `sender_install_id`; `relationship_id`; `last_committed_seq`; `pending_gap_start/end` where required. Sequence allocation and event-row creation occur in one Room transaction. Reset only for a newly enrolled install identity, never silently for the same identity. |
| `partner_event_inbox` | `envelope_id`; `sender_install_id`; `relationship_id`; decrypted event ID; `relationship_seq`; category; received time; acknowledgement state. Enforce uniqueness per relationship/event ID and relationship sequence. Do not retain decrypted detail beyond the existing retention policy. |
| `partner_monitoring_snapshot` | The recipient-local decrypted `monitoring_state`, `snapshot_install_generation`, `outbox_age_bucket`, `latest_relationship_seq`, and snapshot age. Mark `UNKNOWN` when stale; never treat persisted desired state as proof of active monitoring. |

### 8.10 Required logical backend contracts

Use the existing route naming and authentication conventions; these are required operations, not instructions to create a parallel API namespace.

| Operation | Required request/behavior | Required result/security contract |
|---|---|---|
| Upload recipient envelope | Authenticated `sender_install_id`, `relationship_id`, scoped idempotency key, opaque envelope bytes, bounded size. | Recheck active consent server-side; durable idempotent acceptance receipt. Same key and same payload returns the original receipt; key reuse with different payload is rejected. |
| Fetch partner inbox | Authenticated recipient relationship and opaque cursor/receipt. | Return only authorized envelopes for that recipient, paginated in bounded batches; no cross-relationship fetch. |
| Acknowledge recipient envelope | Authenticated recipient plus `envelope_id`, after successful decrypt and durable local persistence. | Idempotent acknowledgement. Relay may delete its ciphertext after acknowledgement or expiry; sender local ciphertext is deleted after relay durable acceptance, not after the partner opens the app. |
| Submit heartbeat/liveness | Authenticated install identity, monotonic counter, install generation/proof through existing auth. No permission bitmask, category, or plaintext monitoring status. | Reject replay/invalid identity; persist server receipt time; refresh `liveness_generation` and deadline. Other valid authenticated calls also refresh liveness. |
| Revoke relationship | Authenticated current party and relationship ID using existing relationship authorization. | Atomically stop new send/fetch rights and disarm pending DMS notifications for the revoked relationship once the server receives the request. Return an acknowledgement used by the client UI. |
| Read partner status | Authenticated active partner; relationship ID. | Derive availability bucket at read time with outage compensation. Return coarse availability status and bucket, not precise heartbeat timestamps, IP addresses, or plaintext monitoring snapshot. |

### 8.11 Required logical backend records and indexes

| Logical record | Required fields/invariants |
|---|---|
| `dms_install_state` | `install_id`; installation generation; last accepted counter; `last_received_at`; `liveness_generation`; `next_deadline_at`; last processed notification generation; last accepted server receipt. Keep only current state/minimal transition data, not a minute-by-minute location-like history. |
| DMS deadline work item/table | `install_id`; expected `liveness_generation`; expected deadline; attempt/lease metadata. Stale generation is a no-op. For the DB-worker path, index `(next_deadline_at, processing_state)` and claim a bounded number of due rows per batch. |
| `dms_notification_outbox` | Unique notification key for `(install_id, relationship_id, outage_generation, notice_type)`; created time; dispatch attempts; dispatch status. Insert in the same transaction as the relevant transition. Revalidate current relationship consent before dispatch. |
| `ingestion_outage` | Service/region or partition; outage start/end; recovery marker. Only ingestion outages rebase affected DMS deadlines. Scheduler-only outages do not change `last_received_at`. |
| `relay_envelope` | `envelope_id`; sender install; relationship; scoped idempotency key; opaque ciphertext; accepted time; expiry; acknowledged time. Unique `(sender_install_id, relationship_id, idempotency_key)`. Do not store plaintext event categories. |

### 8.12 Push schema and partner notification health

The default push payload is only a generic synchronization trigger, for example:

```json
{"v":1,"type":"SYNC_AVAILABLE"}
```

Do not include `group_id`, relationship ID, device ID, last-check-in timestamp, event category, or activity-derived text. After receiving the push, the client authenticates to the backend and fetches current inbox and status. A separate generic server-generated availability notification may use the operating system notification text “A paired device has not checked in recently,” but it is sent only at the six-hour threshold and only when the relationship has explicitly enabled availability sharing.

During partner onboarding, check runtime notification permission (where applicable), notification-channel enablement and importance, and push provider registration. Offer a user-triggered test notification. If notifications are unavailable, show an in-app banner and retain in-app sync. For a DMS notice only, if successful sync has not occurred within six hours, send at most one reminder per outage generation; do not re-notify per ordinary event. The server must not infer that a notification was read.

### 8.13 App Standby and monitoring-state reporting

On API 28+, inspect the app standby bucket at startup, during an eligible check-in, and after material scheduling delays. Do not poll frequently. A `RARE` or `RESTRICTED` result is a diagnostic signal, not a security violation. Show a rate-limited explanation that Android may defer background work, with optional settings guidance chosen by the user. Do not claim that unrestricted battery settings guarantee operation. On API 26–27, omit the bucket check and rely on measured work execution behavior.

The partner-facing display must distinguish:

- **Connection:** `CHECKING_IN`, `LATE`, or `NOT_CHECKING_IN`, derived at read time from server-observed liveness.
- **Monitoring:** `ACTIVE`, `DEGRADED`, `UNAVAILABLE`, or `UNKNOWN`, decrypted from the most recent recipient-specific monitoring snapshot.
- **Delivery completeness:** `UP_TO_DATE`, `SYNC_PENDING`, or `POSSIBLE_MISSING_UPDATES`, derived from the inbox cursor and per-relationship sequence checks.

Do not combine these into a single `HEALTHY` badge. If an independent WorkManager check-in runs while the monitoring service is absent or its state cannot be verified, it must report `UNAVAILABLE` or `UNKNOWN`, not `ACTIVE`.

## 9. Error Handling

| Failure | Required behavior |
|---|---|
| No network/client ISP outage | Keep the minimized event/outbox state in SQLCipher; retry with bounded exponential backoff and jitter. Never change recipient authorization. The server cannot know the cause of client silence. |
| Relay accepted upload but response was lost | Retry the same persisted envelope with the same scoped idempotency key. The server returns the prior receipt without creating a duplicate. |
| Partner is offline | Keep ciphertext on the relay within retention. Push may be delayed or lost; partner syncs on reconnect/app open. Sender removes the local envelope after relay durable acceptance and retains only delivery-state metadata. |
| Partner notification permission/channel disabled | Show partner-side in-app notification-health banner. Keep inbox sync available; do not report event as displayed. |
| FCM/HMS unavailable or token returns `UNREGISTERED` | Remove invalid provider token and mark push unavailable. Do not infer app uninstall from the provider response. Use in-app and best-effort sync. |
| Existing monitoring FGS stops | Do not auto-restart it solely for heartbeat. WorkManager and other eligible authenticated calls may refresh liveness, with a status snapshot of `UNAVAILABLE`/`UNKNOWN` if the monitor is not confirmed active. DMS thresholds apply normally. |
| WorkManager delayed by Doze/Standby/OEM | Do not escalate at a tight assumed cadence. Status derives from actual server receipt time; partner push begins only at six hours unless product policy is changed through measured release review. |
| App Standby bucket Rare/Restricted | Record a coarse diagnostic; show optional rate-limited user guidance. Do not attempt to manipulate the bucket or request new exemption permissions. |
| Partner revokes consent | Immediately stop local routing and purge not-yet-uploaded envelopes for that relationship. On server acknowledgement, backend rejects new uploads/fetches and cancels pending notifications. Do not reroute. If offline, clearly show server-side revocation as pending; disclose this delay before enrollment. |
| Device owner force-stops, uninstalls, enters Safe Mode, shuts down, clears data, or uses another profile/device | Do not defeat OS controls. DMS can report only missing liveness, not a confirmed cause. Cleared data/reinstall requires new authorized install identity and re-enrollment. |
| Device reboot-before-unlock | Treat credential-encrypted state as possibly unavailable until first unlock. Do not issue immediate partner alerts; evaluate liveness under the six-hour notification threshold and resume when permitted. |
| Counter resets or stale heartbeat replay | Reject replay for the current installation identity. A legitimately re-enrolled installation receives a new authenticated install generation/counter namespace. Never permanently lock out a new install because old local state was cleared. |
| Event sequence gap | Attempt relay resynchronization first. If missing events have expired or were evicted, show `POSSIBLE_MISSING_UPDATES`; persist/send a `GAP_MARKER` when the sender can safely do so. Do not interpret gaps as proof of misconduct. |
| Local crypto state lost or key invalidated | Stop encryption/delivery requiring the lost state; require the established pairing/re-enrollment flow. Do not recreate keys and claim historic data was restored. |
| Relay/database migration failure | Preserve existing data; abort the migration/rollout; log only a redacted error. Never silently reset crypto or event state. |
| DMS delayed queue unavailable | Keep the authoritative `next_deadline_at` in durable storage, alert operators, and have the indexed bounded recovery worker claim overdue deadlines until queue processing recovers. Never lose due work or depend on Redis TTL/Pub/Sub as the only trigger. |
| DMS scheduler/worker outage while ingestion remains healthy | Do not rebase liveness deadlines. Process overdue work in bounded batches after recovery and rate-limit alert dispatch. Activate a fleet circuit breaker if late transitions spike. |
| Heartbeat-ingestion service outage | Record outage start/end. For affected installs whose deadline overlapped the outage, use `max(last_received_at, relevant_ingestion_outage_end)` as the effective baseline before applying 45-minute, 90-minute, and six-hour thresholds. |
| Correlated fleet-wide `LATE` spike | Hold partner push fan-out, page the operator, inspect ingestion/region/network health, and release notifications at a controlled rate after review. Do not label a correlated outage as user-specific tampering. |
| DMS transition/notification worker crashes | Use compare-and-set on the expected generation and transactional server notification outbox. Retry dispatch idempotently; recheck relationship authorization at send time. |
| Outbox full/retention eviction | Never silently delete without recording delivery-state/gap metadata. Bound storage; surface a monitoring-gap status and send a gap marker when connectivity and crypto state allow. |
| Device clock changes | Server receipt time governs DMS. Treat `occurred_at_minute` as untrusted client metadata and do not use it to schedule server deadlines. |

## 10. Security Requirements

1. **No custom cryptography.** Do not implement MLS, Sender Keys, X3DH, Double Ratchet, custom HKDF derivations, nonce generation, or an AES wrapper in application code. Use the existing reviewed partner crypto boundary. If the current boundary does not meet the guarantees required by this feature, stop and report the gap before implementation proceeds.
2. **Recipient isolation.** Encrypt separately to each authorized partner. No shared group ciphertext and no automatic recipient fallback.
3. **Server blindness to event content.** The backend must never receive plaintext event bodies or private decryption keys. Any server-visible DMS metadata must be documented as a separate trust channel.
4. **Authentication and integrity.** Authenticate uploads, fetches, heartbeats, acknowledgements, pairing actions, and relationship changes. Enforce replay protection and rate limits. Use the existing registered device identity/key mechanism; do not trust a client-supplied device ID alone.
5. **Minimization.** Use an allowlist schema. Raw URLs, hostnames, package names, notification contents, user input, screenshots, contact details, and location must not enter event payloads, FCM messages, logs, crash reports, or analytics.
6. **Generic push.** Push provider payloads contain generic text and only the opaque routing/wake-up data strictly required. Push is never the source of event content or authorization.
7. **Authorization.** Recheck consent locally before upload and server-side before storing/fetching. Revocation must disable future access and prevent queued events from being rerouted. Availability status is a separate explicit `DEVICE_AVAILABILITY_STATUS` consent scope.
8. **Replay/idempotency/sequence.** Every envelope has a stable event ID and an idempotency key scoped to sender installation + relationship + event ID. Each recipient stream includes a monotonic `relationship_seq` inside the encrypted body. Heartbeats use authenticated counters scoped to install identity and server receipt timestamps. Re-enrollment after clear-data uses a new authenticated installation generation.
8a. **Loss detection.** Partner clients compare decrypted relationship sequences, fetch missing envelopes before declaring a gap, and visibly mark `POSSIBLE_MISSING_UPDATES` if retained history cannot fill the gap. A gap is not evidence of misconduct.
8b. **Monitoring status privacy.** Monitoring-health snapshots are encrypted separately to authorized partners. The server sees liveness metadata only, not `monitoring_state`, outbox age bucket, or sequence content.
9. **Key storage.** Preserve the existing Android Keystore-protected key handling and SQLCipher setup. Do not store raw private keys in preferences, logs, unencrypted databases, or backup archives.
10. **Backup/restore.** Exclude private keys and live cryptographic state from backup unless the existing secure transfer design explicitly supports restore and is tested. New installation/device migration requires re-enrollment when state cannot be securely restored.
11. **No coercive enforcement.** No automated navigation, clicks, or back actions to prevent disabling, revocation, or uninstall. No covert location tracking, keylogging, screenshot capture, SMS/call monitoring, or unrelated notification collection.
12. **DMS semantics.** The DMS may say “not checking in” or “unreachable.” It must not claim “uninstalled,” “tampered,” or “violated” solely from heartbeat silence.
13. **Rate limits and abuse resistance.** Rate-limit pairing, event upload, heartbeat, pull, and acknowledgement endpoints. Prevent event flooding and recipient enumeration. Never trust client-only debouncing as a server abuse control.
14. **Sensitive logs.** Log event IDs only where necessary, never event contents or keys. Redact auth headers, push tokens, ciphertext payloads, device identifiers, and pairing secrets from diagnostics.
15. **Consent data.** Persist only the minimum relationship status and scopes needed for routing. Clean revocation removes the revoked partner from DMS recipients immediately.
16. **Dependency security.** Reuse current dependency management. Pin versions through the existing lock/verification strategy, review licenses and native ABIs, and run dependency vulnerability checks. Do not add an unmaintained or unsupported cryptographic dependency.
17. **Threat model.** Assume the device owner can force-stop, clear data, uninstall, reboot, enter Safe Mode, disable permissions, use another profile/device, or modify a rooted device. The feature is tamper-evident only in limited observable cases, not tamper-proof.

## 11. Performance Requirements

- Do not create a dedicated background process, exact-alarm heartbeat, five-second polling loop, permanent socket, or new FGS solely for this feature.
- Reuse the existing Monitoring FGS only while it is already running for a legitimate, user-visible and policy-justified monitoring task. It may provide opportunistic heartbeat execution, but it is not a Doze/network bypass and must not be kept alive solely for liveness traffic.
- Use 15-minute WorkManager periodic work as a best-effort fallback, not an SLA. On API 28+, record the App Standby bucket diagnostically at coarse intervals; `RARE` and `RESTRICTED` can materially defer jobs/network. Do not poll the bucket repeatedly or assume settings guidance removes all restrictions.
- Use six hours without authenticated liveness as the initial partner-push threshold. The 45- and 90-minute statuses may update the UI earlier, but must not emit partner push alerts on every missed WorkManager interval. Tune only with soak-test data and review of false-alert rate.
- For the backend, use the existing durable delayed queue if already available and suitable. Otherwise, use an indexed bounded deadline worker with `next_deadline_at` and compare-and-set generation checks. Never perform an unbounded full-table scan at a fixed interval. Do not add a new broker solely for this feature.
- Load-test 100,000 synthetic installation states and representative heartbeat cadence. Measure query/queue cost, memory, connection pool usage, consumer lag, database I/O, notification fan-out, and recovery backlog. The test must show bounded batches and no table-wide scan; do not claim O(1) total system load because per-device state and due-work processing remain.
- Persist DMS state transitions and notification intents transactionally. Use unique notification keys and idempotent dispatch to prevent duplication after crashes.
- Keep event serialization, minimization, encryption, and database writes off the main thread. Allocate per-relationship sequence numbers transactionally.
- Keep push payloads small and generic. Push never carries event history or acts as source of truth.
- Keep the outbox bounded by existing retention and queue limits. Delete local ciphertext after relay durable acceptance, retain only the minimal delivery state needed for retries/diagnostics, and keep relay ciphertext until recipient ack, expiry, or revocation.
- Measure battery, wakeups, background network use, event latency, heartbeat gap distribution, partner notification success, false-alert rate, and queue catch-up after outages. Do not assert that FGS piggybacking prevents all WorkManager wakeups or guarantees 15-minute checks.
- Report latency in separate stages: detector-to-outbox, outbox-to-relay acceptance, relay-to-partner synchronization, decrypt/persist, and notification display. FCM accepted/sent is not proof of delivery or user viewing.

## 12. Testing Requirements

### Unit tests

- Event schema allowlist and minimization of prohibited fields.
- Recipient/scope authorization, no fallback routing, and revocation races.
- Stable event IDs, idempotency keys, retry policy, retention, and queue bounds.
- DMS status transitions at 44, 45, 89, and 90 minutes, and partner alert thresholds at 5h59m, 6h, and 6h01m.
- Per-relationship sequence allocation under concurrent event producers, gap detection, resynchronization, retention expiry, and `GAP_MARKER` behavior.
- Current monitoring state is not inferred from a heartbeat alone; stale status snapshots become `UNKNOWN`.
- Server-clock handling, heartbeat counter monotonicity, replay rejection, duplicate heartbeat behavior, and invalid signatures/authentication.
- Digest threshold and maximum-age flush; permission/tamper status events cannot be silently suppressed.
- Schema-version compatibility and invalid ciphertext/error mapping.

### Database and migration tests

- Upgrade from each supported previous Room schema version.
- Interrupted transaction, process death during outbox insertion, and failed migration.
- SQLCipher open failure and Keystore invalidation.
- Backup/restore and device-transfer exclusions for keys and live cryptographic state.
- Event acknowledgement and deletion behavior under crash/restart.

### Backend tests

- Duplicate upload after lost HTTP response.
- Unauthorized partner fetch and cross-relationship isolation.
- Revocation while envelopes are queued, stored, fetched, and acknowledged.
- Durable DMS state after process restart and generation-bound stale delayed jobs.
- Verify the existing durable delayed queue when available; otherwise verify bounded indexed `next_deadline_at` claims using realistic database cardinality. No full-table sweep.
- Verify DMS transition and notification-outbox writes are atomic, CAS-protected, and idempotent across concurrent workers and crashes.
- Distinguish scheduler outage from heartbeat-ingestion outage. Scheduler-only outage must not rebase timestamps; ingestion outage must apply the effective baseline formula.
- Simulate correlated client-network degradation and confirm the fleet circuit breaker holds push fan-out and alerts operators.
- Run 100k synthetic install load/soak tests and measure deadline lag, queue depth, query plans, database I/O, and dispatch recovery.
- Rate limiting, payload limits, spam, stale counters, replay, invalid session, and token rotation.
- Server outage, push outage, delayed/expired/collapsed push, and partner-side synchronization recovery.
- Partner notification permission denied, channel disabled, GMS/HMS absence, invalid provider token, app not synced after alert, and the one-reminder limit.
- API 28+ App Standby Rare/Restricted, plus API 26–27 where `getAppStandbyBucket()` is unavailable.
- Clear-data/reinstall creates a newly enrolled install generation; stale counter values from a prior install do not lock out the new install.

### End-to-end tests

- Two paired devices exchange an authorized event; relay stores ciphertext only; partner decrypts exactly once.
- A second partner not authorized for that event receives no envelope and cannot fetch it.
- The monitored device goes offline, queues events, and later uploads them without duplication.
- The partner is offline during several events and synchronizes all retained events on reconnect.
- A partner revokes consent during event creation, after encryption, during upload, and after server storage. No unauthorized queued event is delivered afterward.
- Clear app data, reinstall, replace a device, lose keys, and re-pair. Verify old state is not falsely reported as restored.
- Push notification payload inspection confirms it contains no sensitive content.
- Force-stop/uninstall and later relaunch test the expected best-effort DMS behavior without a claim that the app can self-restart.

### Cross-OEM physical-device matrix

Test representative current physical devices and OS builds, not emulators alone. Because exhaustive combinations of all manufacturers, OS versions, and models are not feasible, use a tiered matrix without narrowing the product's intended Android-skin compatibility. Prioritize physical devices based on the actual user market and available devices: Transsion (TECNO/Infinix/itel), Samsung, Xiaomi/Redmi/POCO, OPPO/realme, then Vivo/iQOO, Motorola, OnePlus, and Pixel. Use remote device labs where appropriate, and label each manufacturer/version capability as `TESTED`, `PARTIALLY TESTED`, or `UNVERIFIED`. A passing test on one model never proves all skins work.

The initial matrix must include:

- Google Pixel / stock Android.
- Samsung Galaxy / One UI.
- Xiaomi, Redmi, and POCO / HyperOS or supported MIUI versions.
- OPPO and realme / ColorOS or equivalent supported builds.
- OnePlus / OxygenOS.
- Vivo/iQOO / Funtouch OS or equivalent supported builds.
- Motorola / near-stock Android.
- TECNO/Infinix/itel / HiOS or XOS.
- Huawei/HMS or another GMS-free environment if claimed as supported.

For each tier-1 manufacturer, cover the minimum supported Android version where practical and a current supported Android 16/API 36 device. Use Android 14/15 devices when they remain in the product's support matrix or are needed to reproduce a regression. Add older/current paired models where resources permit and expand based on failures and usage data. Remote labs supplement, but do not replace, physical testing for OEM background restrictions. Publish `tested` versus `unverified` per vendor/OS family; do not claim all firmware variants are validated.

Test cases include Doze, App Standby, battery saver, network loss, device reboot, reboot-before-first-unlock where supported, app update, permission revocation, process death, explicit force-stop, user profile changes, VPN conflict on devices using the existing VPN feature, notifications disabled, no GMS, and long-duration soak tests. ADB-forced Doze is useful but does not reproduce every OEM power-management policy.

### Security and policy tests

- Verify that there is no code path which auto-clicks, navigates, or blocks system settings to prevent revocation/uninstall.
- Review the manifest and Play Console declarations for every sensitive API already used by MyExodus.
- Verify no new Accessibility, Notification Listener, exact-alarm, full-screen-intent, or foreground-service permission is added for this feature.
- Run static analysis, dependency scanning, backend authorization tests, and a focused security review of the event/consent flow.

## 13. Deployment Requirements

- Preserve the existing Gradle, package/module structure, DI setup, database conventions, backend runtime, deployment region, and secrets management.
- Use `minSdk 26` unless the actual repository has a newer project-wide minimum. For Play distribution, target API 36 or the current Play-required target at submission time; compile against the project's supported compile SDK. As of 31 August 2026, Google Play requires new apps and updates to target Android 16/API 36 or higher: [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878).
- Do not add `FOREGROUND_SERVICE_SPECIAL_USE`, `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `SYSTEM_ALERT_WINDOW`, `NotificationListenerService`, or a new `AccessibilityService` solely for this feature. If the existing app already declares a permission/service type for another feature, do not repurpose it for heartbeat cadence without a documented use-case and current policy review. A foreground service must reflect the actual user-visible feature it supports; do not start one merely to avoid idle restrictions.
- Review Play and APK distribution flavors separately. The Play flavor must meet current target API and sensitive-permission policies. The APK flavor must use the same consent, minimization, crypto, revocation, and safety boundaries; do not use sideloading as a reason to add anti-uninstall behavior. Ensure the existing release/kill-switch mechanism can disable partner transmission if a policy or security issue is found.
- Do not create a new Redis, PostgreSQL, Node.js, Go, or Firebase database service. Extend the currently authoritative partner relay and persistence implementation.
- Add Room schema migrations and backend data migrations with backward-compatible readers where old and new clients may coexist. Include rollback behavior and a feature-disable path.
- Keep push payload changes backward-compatible. A client that does not understand a new envelope schema must reject it safely and request a supported sync/re-pair path rather than crash or display unverified data.
- Deploy backend changes before enabling client event upload. Roll out the client behind the existing feature-flag/release mechanism if one exists. Do not add a new remote-config dependency solely for this.
- Add and verify backup exclusions for private keys and cryptographic session state unless an existing secure migration mechanism explicitly supports their transfer.
- Keep personal data within the existing approved hosting/data-residency arrangement. Do not move DMS data to a new cloud data store as an incidental implementation choice.
- Before Play submission, review current Accessibility, foreground-service, notification, VPN, and background execution policies that apply to the existing app. Google Play prohibits using Accessibility to prevent ordinary users from disabling/uninstalling apps outside specified parental-control/enterprise exceptions: [Sensitive permissions and APIs policy](https://support.google.com/googleplay/android-developer/answer/16558241). Do not add such behavior.
- Platform references to verify during implementation: [WorkManager periodic interval](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby), [App Standby buckets](https://developer.android.com/topic/performance/appstandby), [foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), and [FCM Android message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority). A foreground service improves execution opportunity while legitimately active but must not be documented as a universal Doze/network exemption.
- Keep operational rollback able to stop new event dispatch and DMS notification sends without erasing unrelated user or pairing data. Verify the rollback in staging.

## 14. Monitoring Requirements

### Client-side metrics

Collect only low-sensitivity operational measurements:

- Outbox count/age buckets and counts of queued, relay-accepted, cancelled, expired, gapped, and failed events.
- Event latency by stage as distributions; no per-event sensitive activity history.
- Heartbeat/check-in source (`existing_fgs`, `workmanager`, or `other_authenticated_request`), execution time bucket, success/failure category, and time since last server acknowledgement.
- App Standby bucket (API 28+) as a coarse diagnostic at relevant lifecycle points, plus app version, API level, manufacturer/model family, and GMS/HMS availability when justified.
- Monitoring-state snapshot age and broad state transition counts; never report raw detector output.
- Partner-side notification permission/channel/provider health and last successful synchronization age as coarse buckets.
- Previous process exit reason via `ApplicationExitInfo` on API 30+ only on next app start where useful; treat as diagnostic context, not proof of intent. API 26–29 must omit this signal.

Never emit domains, package names, raw activity, notification content, partner names, cryptographic keys, ciphertext bodies, full IP addresses, or authentication tokens to analytics/crash reporting.

### Backend metrics

- Heartbeat receipt rate and age distribution; distinguish any valid authenticated request from dedicated heartbeat calls.
- `LATE`/`NOT_CHECKING_IN` state derivations, six-hour alert triggers, recoveries, and false-alert investigation outcomes.
- Delayed queue depth/lag and stale-job discard count, or indexed deadline-worker batch size/query duration/query errors depending on the selected existing mechanism.
- DMS state-transition CAS conflicts, notification-outbox depth, dispatch attempts, retries, duplicate suppression, and current-relationship authorization rejections.
- Ingestion outage periods, scheduler/worker outage periods, effective-deadline rebases, fleet circuit-breaker activations, and controlled catch-up rate.
- Relay acceptance/fetch/acknowledgement counts, duplicate idempotency hits, queue age, expiry, sequence-gap/recovery counts, and invalid envelope counters.
- Push-provider dispatch result and invalid-token counts; keep them separate from partner sync, notification display, and user-view evidence.

### Alerting and operations

- Alert operators on scheduler/queue lag, indexed deadline-worker lag, relay/database unavailability, event backlog age, push credential/token failure spikes, repeated counter rejection, mass `LATE` transitions, and unknown backend health.
- If the fleet circuit breaker activates, hold partner availability pushes, page the operator, review ingestion/region/provider health, and release notices at a controlled rate when safe. Do not silently convert a service incident into individual tamper claims.
- After heartbeat-ingestion outage, apply outage compensation only to affected installation deadlines. After a scheduler-only outage, retain authentic server receipt times and process overdue work in bounded batches.
- Dashboards distinguish no event, monitoring unavailable, sync incomplete, push unavailable, and device not checking in.
- Maintain runbooks for relay outage, queue/deadline processor outage, event backlog, invalid crypto state, mass DMS transitions, push outage, partner notification disablement, migration failure, and rollback.

## 15. Risk Register

| Risk | Likelihood | Impact | Mitigation | Detection |
|---|---|---|---|---|
| Existing partner crypto does not provide authenticated recipient-specific envelopes | Unknown until inspected | Critical | Step 2 security gate; do not ship or build custom fallback | Security review and negative cross-recipient tests |
| Consent revocation races with queued delivery or DMS notification | Medium | Critical | Local atomic revocation, server recheck at upload/fetch/dispatch, purge pending envelopes, no fallback | Concurrency and offline-revocation tests |
| Doze/Standby/OEM scheduling creates false liveness gaps | High | High | FGS opportunistic path only if already legitimate, WorkManager fallback, 45/90 status thresholds and six-hour partner push | Physical-device soak, bucket tests, heartbeat gap distribution |
| FGS is absent, killed, or not policy-eligible for heartbeat use | Medium until repository/policy review | High | Do not add a new FGS solely for this feature; use best-effort WorkManager and honest status | Manifest review, service lifecycle tests, Play policy review |
| DMS deadline worker scans the whole table or falls behind | Medium | High | Existing durable delayed queue if suitable; otherwise indexed bounded due-row worker | Query plans, queue lag, 100k-load test |
| Stale/duplicate DMS job sends repeated notice | Medium | High | Generation-bound jobs, compare-and-set state, transactional notification outbox, unique notice key | Crash/concurrent-worker tests |
| Heartbeat-ingestion outage produces fleet-wide false alerts | Medium | High | Track ingestion outages; rebase affected deadlines from outage end; circuit breaker and controlled release | Failure injection and fleet transition dashboard |
| Scheduler-only outage causes bursty overdue notifications | Medium | Medium/High | Keep actual receipt times, process bounded batches, rate-limit dispatch | Scheduler outage simulation |
| Monitoring component stops while independent check-in continues | Medium | Critical for product trust | Encrypted monitoring snapshot; separate connection and monitoring UI; stale snapshot becomes `UNKNOWN` | Kill monitoring component while WorkManager executes |
| Partner app cannot display or sync alert | Medium | High | Onboarding notification/channel/provider health, in-app banner, authenticated pull, at most one reminder | Partner-side device matrix and sync timestamps |
| Sequence gaps hide lost events | Medium | High | Monotonic per-relationship sequence, inbox gap detection, resync, explicit `POSSIBLE_MISSING_UPDATES` | Queue eviction, retention, retry and ordering tests |
| Clear-data/device replacement loses crypto state | Medium | High | Backup exclusion, new authenticated install generation, re-enrollment and identity verification | Restore/key-invalidation/re-pair tests |
| Raw sensitive activity leaks into logs/push | Medium | Critical | Allowlist schema, generic push, payload/diagnostic inspection | Static scans and staging inspection |
| Unsupported permission/FGS/target-SDK use causes Play rejection | Medium | High | Manifest diff and current policy review before submission | Release policy checklist and Play review |
| Outbox or relay backlog grows during extended offline periods | Medium | Medium | Bounded retention, age/size limits, backoff, explicit gap status | Queue depth/age and expiry metrics |
| GMS-free partner cannot receive push promptly | High where such devices are supported | Medium/High | Relay source of truth, partner in-app banner, best-effort pull, honest latency copy | GMS/HMS tests and partner-side sync metrics |
| Device-status metadata becomes a coercive surveillance channel | Medium | High | Separate explicit consent scope, coarse time buckets, no IP/history exposure, revocation disclosure | Abuse/privacy review and UI copy review |

## 16. Open Questions

These are repository facts or product/operational inputs that must be resolved at the named implementation gate. They do not authorize the coding agent to invent a new architecture.

1. **Existing crypto semantics (Step 2):** Is the partner-encryption interface stateless or ratchet/stateful, and what transaction/durable-state guarantees does it provide? This determines whether the envelope is prepared before queueing or during queue processing; in both cases the exact envelope must be persisted before network send and reused after an ambiguous timeout.
2. **Existing heartbeat execution path (Step 1):** Is the current Monitoring FGS already declared, policy-justified, and legitimately active for a user-visible monitoring purpose? If yes, piggyback opportunistically while it runs. If no, do not add an FGS for this feature and use best-effort WorkManager/authenticated-request liveness.
3. **Backend deadline infrastructure (Step 1):** Does the current backend already have a durable delayed job/queue supporting scheduled messages, retries, stale-generation checks, and idempotent consumers? If yes, reuse it. If not, implement the indexed bounded `next_deadline_at` worker in the existing authoritative database. Do not add a new broker solely for DMS.
4. **Existing route and table names:** Record exact paths/symbols for ADR #42 relay/Partner SOS, ADR #55 pairing, ADR #46/#111 partner activity, event detectors, relay tables, auth middleware, and scheduled jobs. Map the logical contracts in Section 8 onto these existing structures.
5. **Current relationship consent fields:** Determine how `DEVICE_AVAILABILITY_STATUS` should be represented in the current consent schema and pairing UI. Implement it as an explicit scope and do not enable DMS partner pushes where that consent is absent.
6. **Notification provider support:** Confirm existing GMS/HMS/provider integration and how the app distinguishes provider unavailable, invalid token, disabled OS notifications, and unobserved display. Do not add a new provider SDK without an explicit product/support decision.
7. **Operational baseline:** Before tuning the six-hour alert threshold or fleet circuit breaker, collect soak data for heartbeat gaps, scheduler/ingestion uptime, partner notification sync, and false-alert rate on the tier-1 OEM matrix. Keep six hours as the initial production setting until that review authorizes a change.
8. **Distribution-specific compliance:** Confirm the current Play and APK flavors, target SDK/manifest differences, and human sign-off owner for Play policy review. Policy review is a release gate and cannot be marked complete by code tests alone.

If a repository fact reveals that a mandatory security invariant cannot be met, stop at that step and write a blocker report with source evidence and the smallest decision needed. Do not substitute custom cryptography, a new FGS, a new broker, or a new sensitive permission to bypass the blocker.

## 17. Acceptance Criteria

### Functional

- A supported existing detector event produces a minimized, allowlisted category event with a unique event ID and per-relationship sequence.
- The event reaches only currently authorized recipients. Offline recipients do not cause rerouting.
- Partner inbox sync restores retained events after the partner has been offline and detects unrecoverable sequence gaps without interpreting them as misconduct.
- Relay ciphertext is durable and idempotent. Sender-local ciphertext is deleted after durable relay acceptance, with only the minimal delivery-state row retained.
- Partner consent revocation prevents new uploads/fetches and disarms queued DMS notices when the server receives the authenticated revocation. Offline revocation status is shown accurately until acknowledged.
- Push payloads contain only the documented generic trigger. Push dispatch is not treated as receipt/display.
- Partner UI separates connection status, monitoring status, and event completeness. It displays coarse availability buckets, not minute-level heartbeat timestamps.
- Partner onboarding detects denied notification permission/channel/provider problems where supported, offers a user-triggered test, and shows an in-app banner if notifications are unavailable.

### Security

- Relay has no event plaintext or private partner decryption keys.
- A partner cannot fetch or decrypt an envelope addressed to another relationship.
- Invalid authentication, replayed heartbeat counters, malformed payloads, unknown schema, and failed decryption are rejected safely.
- No raw URLs, domains, package names, notification content, keystrokes, screenshots, or location enter event bodies, push payloads, logs, analytics, or crash reports.
- No code path interferes with uninstall, permission revocation, service deactivation, or user exit.
- No custom crypto, new ratchet, MLS, Sender Keys, or unreviewed cryptographic wrapper is introduced.
- Monitoring snapshots are encrypted for authorized recipients and cannot be read by the server.

### DMS and backend reliability

- Status is derived using server receipt time and the correct effective baseline; the UI can distinguish `CHECKING_IN`, `LATE`, `NOT_CHECKING_IN`, and `RECOVERED`.
- The initial partner push threshold is six hours of effective missing liveness, with at most one notice per outage generation and explicit `DEVICE_AVAILABILITY_STATUS` consent.
- Any valid authenticated API request refreshes liveness after replay/authentication checks.
- The existing durable delayed queue or indexed bounded deadline worker processes deadlines without a full-table scan. A 100k-install load test demonstrates bounded work and acceptable queue/query lag.
- DMS state updates and notification intents are transactional, CAS-protected, and idempotent; concurrent workers do not send duplicate notices.
- Scheduler-only outage does not rebase heartbeat times. Ingestion outages rebase only affected deadlines. A correlated fleet spike activates a circuit breaker and operator alert rather than mass partner push.
- DMS never claims confirmed uninstall/tampering from heartbeat silence or invalid push token alone.

### Android compatibility and performance

- The tiered physical-device matrix includes Pixel, Samsung, Xiaomi/Redmi/POCO, OPPO/realme, OnePlus, Vivo/iQOO, Motorola, and TECNO/Infinix/itel. Huawei/HMS is tested if included in the supported scope. Each vendor/API family is labelled `TESTED`, `PARTIALLY TESTED`, or `UNVERIFIED`.
- WorkManager and existing FGS paths are tested independently through Doze, App Standby Rare/Restricted, force-stop, reboot-before-unlock where supported, no network, monitoring-component kill, and partner-notification failure.
- On API 28+, App Standby bucket diagnostics do not poll tightly or try to manipulate the assigned bucket. User guidance is rate-limited and optional.
- Battery/network use, heartbeat gaps, status false positives, push/partner-sync performance, backend queue/query performance, and outage recovery are measured against stated test conditions. No five-second or all-device delivery guarantee is claimed.
- Target SDK, sensitive APIs, Play permissions, APK flavor behavior, backup exclusions, migration, rollout, and rollback pass human review.

## 18. Definition of Done

The feature is done only when all of the following are true:

- [ ] Existing MyExodus pairing, crypto, event detectors, relay, persistence, and monitoring components have been inspected and reused without unrelated redesign.
- [ ] The Step 2 crypto gate documents whether the existing interface is stateless or stateful and proves recipient confidentiality/authenticity plus crash-safe retry semantics. Any missing mandatory guarantee was raised as a blocker rather than filled with custom cryptography.
- [ ] Event schemas are allowlisted; `relationship_seq`, gap detection, resynchronization, and `POSSIBLE_MISSING_UPDATES` behavior are implemented and tested.
- [ ] Local writes are transactional, SQLCipher-protected, bounded, and idempotent. A committed cryptographic envelope is reused after ambiguous network outcomes.
- [ ] Sender-local ciphertext is removed after durable relay acceptance; server ciphertext is removed according to recipient acknowledgement, expiry, or revocation; retention is documented.
- [ ] Backend upload/fetch/ack/revoke contracts and logical schema are mapped to existing routes/tables and tested.
- [ ] DMS uses an existing durable delayed queue or indexed bounded deadline worker; no unbounded five-minute full-table scan and no Redis key-expiry-only trigger remain.
- [ ] DMS generation/CAS, transactional notification outbox, outage compensation, fleet circuit breaker, notification suppression, and six-hour partner push threshold are tested.
- [ ] Connection status is distinct from encrypted monitoring-health snapshots and inbox completeness. Stale snapshots become `UNKNOWN`; a heartbeat alone never means monitoring is healthy.
- [ ] Partner-side notification permission/channel/provider checks, user-triggered test notification, in-app warning, generic payload, and one-reminder cap are implemented.
- [ ] WorkManager remains best effort; any existing FGS heartbeat hook runs only while that service is legitimately active and policy-justified. No new FGS, exact alarm, keep-alive process, anti-uninstall automation, or new sensitive permission is added for this feature.
- [ ] App Standby diagnostics on API 28+ are rate-limited, API 26–27 degrade cleanly, and settings guidance does not promise unrestricted operation.
- [ ] Backup/key-loss/clear-data/device replacement/re-enrollment and new install-counter namespace are tested.
- [ ] The tiered cross-OEM report is attached and distinguishes tested behavior from unverified configurations.
- [ ] The 100k-install DMS load test, scheduler/ingestion outage tests, correlated client outage test, queue recovery test, partner-side notification tests, and performance/battery soak tests pass.
- [ ] Play and APK flavors have been reviewed separately for target SDK, manifest permissions, foreground-service purpose, disclosures, and rollback/kill switch.
- [ ] Monitoring, operator alerting, recovery runbooks, staged rollout, migration rollback, and server-side push-notification outbox recovery are verified.
- [ ] All acceptance criteria pass; no Critical or High security/reliability blocker remains open.
- [ ] User-facing copy states that device silence is not proof of uninstall, tamper, or violation; explains delayed notifications and offline-revocation limits; and does not promise recall of information already decrypted by a recipient.

---

## Appendix S1 — Repository change map (Step 1, verified against code 2026-10-21)

**Baseline verdict:** every required rail exists. No parallel relay, crypto engine, database,
queue, or backend runtime is needed. The plan's assumed server API shape does not match the
shipped transport and is mapped below rather than built twice.

### Transport (existing, authoritative)
| Concern | Exact location | What it does |
|---|---|---|
| Sender mailbox write | `app/.../accountability/sync/MailboxOutboxDrainer.kt` | Writes the **stored envelope bytes** to `mailboxes/{partnerUid}/messages/{messageId}`; doc id = message id → idempotent re-drain. "Sent" = mailbox write. |
| Mailbox contract | `app/.../accountability/sync/FirestoreSchema.kt` (`mailboxMessageData`) | Fields `senderId, senderUid, pairId, payload(base64), keyEpoch, timestamp`; client-supplied timestamp is rejected by rules (`timestamp == request.time`). |
| Relay (untrusted) | `functions/relay.js`, `functions/relay-logic.js` | `onDocumentCreated` trigger: format/size validation, pairing re-check, AD-23 rate windows (10/pair/min, 200/sender/day), then per-installation push dispatch records. Content-blind (ADR #42). |
| Push dispatch + retry | `functions/dispatch-record.js`, `functions/dispatch.js`, `functions/sweep.js` | Durable `relay-dispatch/{messageId}/installations/{installationId}` records; Cloud Scheduler every 2 min retries PENDING/FAILED_RETRYABLE < 8 attempts. |
| Push payload | `functions/fcm-transport.js`, `app/.../sync/PartnerMessagingService.kt` | Data-only generic wake: `{messageId, type:"domain-event"}`; client enqueues the one-time sync work; no content in push. |
| Retention | `functions/cleanup.js`, `functions/mailbox-retention.js` | Daily scheduled; deletes when every registered non-stale installation acked and age ≥ 7-day floor, or unconditional 30-day cap. Acks live at `.../messages/{id}/acks/{installationId}`. |
| Access control | `firestore.rules` lines 42–88 | Create requires sender == auth, active pair containing the sender; read only recipient + message author (author sees relayStatus only); update/delete always denied (cleanup is Admin SDK). |
| Recipient fetch | `app/.../sync/FirestoreMailboxReceiver.kt` | Direct Firestore watermark query (`timestamp` asc, limit 500), not a server API. |
| Receive pipeline | `app/.../sync/MailboxMessageHandler.kt`, `MailboxEventProcessor.kt`, `state/ChainStateStore.receiveRemote` | Verify signature → decrypt → append chain + `processed_events` marker in one Room transaction → ack after durable commit. Dedup before validation. |
| Sync cadence | `app/.../sync/PartnerSyncWorker.kt` (15-min periodic), `PartnerMessagingService` wake path, `runPartnerSyncPass` (manual "Check now") | Best-effort delivery; mailbox is the source of truth. |
| Push registration | `app/.../sync/PushInstallationRegistrar.kt` | Rewrites this install's `users/{uid}/push_installations/{id}` every pass (staleness source). |

### Crypto and identity (existing, authoritative)
| Concern | Exact location | Fact |
|---|---|---|
| Pair key | `accountability/enrollment/CekDerivation.kt`, `Ecdh.kt` | CEK = ECDH(my agreement key, partner agreement pub) → HKDF; **never persisted**; one key per pair. |
| Payload AEAD | `crypto/EventCipher.kt` | Chain events: AES-256-GCM, deterministic nonce `HKDF(CEK,"nonce"||seq)`. Non-chain messages: `encryptWithAad` with a fresh random nonce + caller AAD (already used by SOS nudge) — the primitive for a standalone event envelope. |
| Integrity/identity | `serialization/Envelope.kt`, `crypto/AccountabilitySigner.kt`, `EcdsaLowS.kt` | `UnsignedEnvelope` canonical CBOR + SHA-256 domain string, signed with the hardware-attested identity key; low-S enforced; key ids are SHA-256 of signing pubkeys. |
| Signing gate | `crypto/AccountabilitySigner.beginSign()` | Signing key is user-auth-gated (900 s warm window). **Cold window → `UserNotAuthenticatedException`; the pass stops and rows wait for a warm window.** There is no background signing path and this feature must not add one. |
| Sequence / anti-fork | `state/ChainStateStore.appendLocal/appendOutcome`, `ChainHead`, `ChainEvent` | Per-(pair, sender) monotonic sequence allocated inside the append transaction; head hash chain; duplicate detection; fork flag is permanent and blocks further appends. This **is** `relationship_seq`; no second counter is needed. |
| Session key rotation | `state/SessionKeyManager`, `IdentityEpochStore` | Epoch rotation inside the creation critical section; `keyEpoch` travels on the envelope. |
| Pairing / revocation | `enrollment/EnrollmentDao/Flow`, `state/UnpairState.kt`, `accountability/BilateralDeletion.kt` | Pair data shred + key destruction on unpair (`destroyAllKeys`), server pairing doc → `terminated` by the member (Firestore rule allows only that one field/status change). An offline revocation shreds local keys immediately; queued rows die with the pair. |

### Event sources (existing detectors to consume, not duplicate)
| Plan category | Existing emitter | Notes |
|---|---|---|
| `BLOCKED_APP_ATTEMPT` | `blocking/AttemptRecorder.recordBlocked(ruleId, packageName)` from `EnforcementForegroundService` line ~697 | Single insert choke point; rule row tells target type. **Today's check-in payload sends the raw package name (`eventText` in `PartnerSyncCoordinator`) — the new contract must be coarse and must not reuse that text.** |
| `BLOCKED_DOMAIN_ATTEMPT` | same recorder, called from `BlockerAccessibilityService` line ~1001 with `url.hostUnicode` | Same coarse-only rule applies; raw host never enters the new event. |
| `MONITORING_PERMISSION_CHANGED` / `MONITORING_COMPONENT_UNAVAILABLE` | `blocking/ProtectionStatusMonitor.refresh()` + `publish()` (change-gated; called on process start, FGS 30 s cycle, a11y connect/disconnect, 15-min health worker) | Transitions are only logged (logcat + Crashlytics non-fatal); **not persisted**. A coarse transition journal must be added at this choke point for delivery. `causeFor()` already yields coarse codes (`enhanced_mode_off`, `usage_access`, `overlay`, `accessibility_unavailable`) with no user content. |
| `DEVICE_STATUS_CHANGED` | New: monitoring-state snapshot built from the same authority check | Must be computed from current permissions/components at send time; never inferred from a heartbeat. |
| Detection journal pattern | `accountability/activity/PartnerActivityJournal.kt` (ADR #115), consumed by `PartnerSyncCoordinator.reconcileEvents` | Proven shape: journal-first inside the domain transaction; envelope + signature built at reconcile while the auth window is warm; per-pair consent filter in SQL; linkage table prevents per-pair duplicates. The new outbox should follow this, not invent a second pipeline. |

### Existing capture of the plan's deltas
1. **No server upload/fetch/ack API exists.** Sender and recipient talk to Firestore directly under rules; the Cloud Function is a validation + push trigger. "Extend the relay API" = extend the mailbox document/rules/relay validation and add the acknowledgment-derived server state; there is no REST surface to add endpoints to.
2. **Server ciphertext retention is ack-based with a 7-day floor / 30-day cap**, not "until acknowledged or expired" with no floor. Reused as-is; the new feature inherits it.
3. **Envelope creation is user-auth-gated.** The plan's "persist the encrypted envelope, then send" is satisfied in shape by journaling the minimized event atomically at detection and producing the signed envelope at reconcile (same as ADR #115). A detection made while the window is cold is durable but undeliverable until the next warm window; this is recorded as an honest delivery limit, not worked around with a second key.
4. **DMS infrastructure already exists:** Firestore + Cloud Scheduler functions (`retryDispatchSweep` every 2 min, `cleanupMailboxes` daily). A per-minute indexed deadline worker fits the existing deployment; no Redis, no new vendor. `relay-dispatch`/`rate-limits` are Admin-only collections (rules deny all client access).
5. **Push is already generic** (`{messageId, type}` data-only). The DMS notice needs one new generic type; it must not carry categories.
6. **Monitoring transitions are not persisted** (logging only) and must gain a durable coarse journal at `ProtectionStatusMonitor.publish`.
7. **`AccountabilityDatabase` holds chain/outbox/processed state; `AppRulesStore` holds the detection journals.** New sender-side event rows belong with the existing activity journal in `AppRulesStore` (detector transaction domain), not a third database.
8. **No Play-distribution constraint applies** (ADR #15, no Play distribution) — plan §2/§13 Play-flavor lines are informational, not a gate for this project.

## Appendix S2 — Step 2 security + consent integration gate (executed 2026-10-21)

**Verdict: PASS after one storage-boundary fix. No custom cryptography was added.**

### Crypto interface classification (answers §16 OQ-1)

The existing boundary is **stateless per key epoch**, not a ratchet. The pair CEK is derived deterministically with ECDH + HKDF from the Android Keystore agreement keys (`CekDerivation`); the only evolving state is an integer `keyEpoch` anchored on the per-sender chain head (`SessionKeyManager`, 500 events or 30 days), and each envelope names its epoch. Envelope creation therefore follows the plan's stateless branch: `EventCreator` builds nonce + AES-256-GCM ciphertext + canonical-CBOR unsigned envelope + hardware-attested signature **once**, and `ChainStateStore` persists the exact `SignedEnvelope` bytes in the outbox **before** any network call. A retry re-drains those stored bytes unchanged (`MailboxOutboxDrainer`), and the mailbox document id is the message id, so duplication is impossible by construction (`ALREADY_EXISTS` → mark SENT). Crash-safe exact-once-envelope retry is proven; no stateful ratchet claim is made.

### Guarantee evidence

| Guarantee | Proof | Result |
|---|---|---|
| Recipient confidentiality | `EventCipherTest` (wrong key / wrong sequence / tampered ciphertext fail AEAD), `CekDerivationTest` (different partner or pair derives a different CEK), `MailboxEventProcessorTest.wrongCek_isCorrupt_notInvalid` | 166/166 targeted JVM tests green |
| Recipient authenticity + integrity | `EnvelopeTest` (canonical bytes, stable hash, truncation/garbage rejected), `MailboxMessageHandlerTest.invalidSignature_isLogged_notAcked_notApplied`, `MailboxEventProcessorTest.signatureFromAnotherKey/highS/tamperedCiphertext` | green |
| Relay cannot decrypt | Payload is opaque base64; delivery modules contain no decryption or key derivation; no key-material field is writable to a mailbox; no observable relay effect carries payload content | `functions/test/relay-blindness.test.js` 5/5 |
| Cross-partner fetch isolation | Rules emulator: another partner cannot read or list a mailbox; unauthenticated denied; author reads only its own sent marker | `functions/emulator-test/firestore-rules.emulator.js` 17/17 |
| Replay / duplicate handling | Create-only documents (replaying a message id denied); `processed_events` short-circuits before validation; chain duplicates idempotent, conflicting sequences flag a fork | rules 17/17; `ChainStateStoreTest` 34/34 |
| Key invalidation / re-enrollment | Epoch window refuses out-of-policy envelopes (`acceptsIncoming`, `refusedEpoch_isUnknownKeyEpoch`); unpair destroys the pair key only (`unpairOfOnePair_destroysOnlyItsKey_leavesSiblingPairIntact`) | green |
| Revocation before pending sends | Local `deletePairData` deletes pending outbox rows inside the pair transaction before/with the key shred (`deletePairData_clearsTheOutboxWithTheChain`, `initiate_drainsEveryPendingBatch_beforeShredding`); server denies new creates once the pairing is `terminated` | green; rules test "a revoked relationship cannot queue new events" |

Commands: `./gradlew :app:testDebugUnitTest --tests "com.example.myexodus.accountability.*"` (12 classes, 166 tests); `npm test` in `functions/` (99 + 5 = 104 tests); `npm run test:rules` in `functions/` (17 tests, Firestore emulator).

### Finding fixed at this gate (ADR #194)

The mailbox **create** rule required the sender to be a member of an `active` pairing but never required the **recipient** to be that pairing's other member. Empirically (rules test run against the unmodified file): a user with any active pair could write documents into any other user's mailbox under their own `pairId`. The relay rejects such a message (`PAIRING_INVALID`) but only after storage and **before** rate limiting, so the junk rows were never throttled — an unauthenticated-content storage-abuse path, though not a confidentiality break (the recipient has no CEK for that pair and the client skips foreign-pair documents). Fixed in `firestore.rules` with one `recipientUid in members` clause on the same `get()`; the same test now passes (17/17).

### Honest limits carried into later steps

- The rules change is **not deployed**; deployment is an owner action. Until then production keeps the old rule.
- The relay still accepts a `terminated` pairing for the members-bound unpair deletion-request race (ADR #107 design); this is intentional and tested.
- Ciphertext already stored before a revocation remains until the recipient acks or the 30-day cap removes it; the recipient's key is destroyed by the shred, so it is unreadable. Step 10 owns the full revocation/exit surface.
- No consent matrix is consulted for the new event kinds yet — Step 3 defines the event contract and Step 4/6 wire the per-kind gate.

## Appendix S3 — Step 3 event contract + minimizer (executed 2026-10-21)

**Delivered:** `accountability/serialization/AccountabilityEventWire.kt` — the closed, versioned body for the new event-delivery feature, plus the Step 4 delivery-state enum. 13 JVM tests, 0 failures.

### Contract

- `schema_version` = 1 (exact; any other version is declined).
- `event_id` — canonical lowercase UUID, random per event, stable/idempotent across retries.
- `relationship_seq` — positive, monotonic per sender-install/relationship stream.
- `event_kind` — `ACCOUNTABILITY_EVENT`, `MONITORING_STATUS`, `GAP_MARKER`.
- `event_category` — closed allowlist: `BLOCKED_APP_ATTEMPT`, `BLOCKED_DOMAIN_ATTEMPT`, `MONITORING_PERMISSION_CHANGED`, `MONITORING_COMPONENT_UNAVAILABLE`, `DEVICE_STATUS_CHANGED`, `GAP_MARKER`. Kind and category must agree.
- `occurred_at_minute` — epoch minute, floored (never rounded up). Untrusted client metadata, never a DMS basis.
- Monitoring snapshots additionally carry `monitoring_state` (`ACTIVE`/`DEGRADED`/`UNAVAILABLE`/`UNKNOWN`), `outbox_age_bucket` (`UNDER_1H`, `ONE_TO_SIX_HOURS`, `SIX_TO_TWENTY_FOUR_HOURS`, `OVER_TWENTY_FOUR_HOURS`, `UNKNOWN`), and `latest_relationship_seq` (≥ the event's own sequence).
- Gap markers carry `missing_seq_start` / `missing_seq_end`.
- No other field is legal; the decoder declines unknown keys, wrong types, unknown enum values, missing/forbidden fields, out-of-range numbers, malformed CBOR, and bodies over **512 bytes**.
- Recipient relationship and delivery state are deliberately **not in the body** (§8.1); Step 4 persists them as outbox metadata, and the seven §8.4 states are defined in `EventDeliveryState` for that mapping.

### Reconciliation recorded (plan §2 FR2 vs §8.1)

§2 FR2 requires the five normalized categories above; §8.1's `event_category` examples name content categories (`ADULT_CONTENT`, `SOCIAL_MEDIA`, …). Carrying both would mean either a second, unlisted taxonomy field or dropping a required FR2 value. Decision: **`event_category` is FR2's five normalized categories + `GAP_MARKER`**; the §8.1 examples are read as illustrative ("such as"), and content categories are deferred to a future schema version, to be added only when the consent matrix actually gains a content scope. This keeps consent per event category (blocks vs monitoring vs device status), which is the scope this feature ships.

### Minimizer evidence

The model has exactly one `String` field (`event_id`, UUID-format-checked) — asserted by reflection in the test. Raw URLs, hostnames, package names, app labels, notification text, arbitrary JSON, and location have no field to occupy; unknown-key rejection is tested for each of those names. Builders accept no free-text parameters other than the event id.

### Tests (`AccountabilityEventContractTest`, 13)

Every category round-trips; floored minutes; deterministic, ≤512-byte encoding; 12 prohibited field names rejected; non-UUID event ids rejected in both the builder and decoder; unknown version/kind/category/enum declined; wrong types declined; kind/category mismatch declined; required/forbidden field sets per kind; out-of-range sequences and gap ranges declined; malformed bytes never throw; delivery states match §8.4 exactly.

## Appendix S4 — Step 4 durable outbox + sequence contract (executed 2026-10-21)

**Delivered:** `AccountabilityDatabase` v13 (`events.event_id`, outbox retry/retention columns, `MIGRATION_12_13` + rollback `MIGRATION_13_12`), `AppRulesStore` v18 (`partner_event_journal` + rollback `MIGRATION_18_17`), `OutboxBackoff`, drain-side due/expiry sweep, and the wire-event-id idempotency seam in `ChainStateStore`/`EventCreator`. Full JVM suite 1,884 tests / 0 failures; device migration proven on the X6532 the same day (below).

### §8.9 field mapping (and where the logical schema is split)

| §8.9 logical field | Implemented as | Notes |
|---|---|---|
| `partner_event_outbox` table | **split**: `events` (chain row) + `outbox` (delivery row), joined `outbox.event_id → events.id` | Appendix S1.7: no third database, no duplicate row for the same event. |
| `event_id` | `events.event_id` (TEXT, unique per pair+sender; NULL exempt) | Step 3 wire UUID. `outbox.event_id` is the internal row reference; the wire id stays on `events` so chain and delivery cannot disagree. |
| `sender_install_id` | `events.sender_key_id` / `chain_heads.sender_key_id` | SHA-256 of the install's signing public key (`EnrollmentTranscript.senderKeyId`), regenerated on re-enrollment/clear-data. |
| `relationship_id` | `pair_id` | One row per authorized pair. |
| `relationship_seq` | `events.sequence` | Allocated inside the same `withWriteTransaction` that writes the head and the outbox row. |
| `schema_version` | inside the signed envelope bytes (Step 3 body, `v=1`) | Not a queryable column; no Step 4/6 consumer needs to route by version before decryption. |
| minimized body / envelope | `partner_event_journal` (plaintext, SQLCipher) and `outbox.envelope_bytes` (prepared ciphertext) | Detector transaction writes the journal; reconcile turns it into exactly one envelope. |
| `delivery_state` | `outbox.status` | TEXT, admits all seven §8.4 states; Step 4 reaches `PENDING`/`SENT`/`EXPIRED`; the other four get writers in Steps 5/6/10. |
| `idempotency_key` | `outbox.message_id` (UUID minted once at creation, = mailbox doc id) **plus** the unique event index | Retries reuse the stored bytes and id; create-only mailbox documents turn a replay into `ALREADY_EXISTS` → marked SENT. |
| `attempt_count` / `next_attempt_at` / `last_error_code` | outbox columns | `OutboxBackoff` = exponential 30–60 s first retry, ×2 per attempt, capped at 6 h, deterministic per-message jitter. Error codes are closed (`FirebaseFirestoreException.Code` names, `TRANSPORT`); never exception text. |
| `created_at` / `expires_at` | outbox columns | `expires_at = createdAt + 30 days`, stamped in the same transaction as the append (§8.7); migration backfills pre-existing rows. |
| unique `(sender_install_id, relationship_id, event_id)` | `index_events_pair_id_sender_key_id_event_id` (unique) | |
| unique `(sender_install_id, relationship_id, relationship_seq)` | `index_events_pair_id_sender_key_id_sequence` (unique, pre-existing) | |
| pending-work index | `index_outbox_status_next_attempt_at` | Drain filters `status = PENDING`, `next_attempt_at <= now`, unexpired, ordered by chain sequence. |
| `relationship_sequence` table | `chain_heads.last_sequence` | Already transactional; no second counter to drift. |

### Retention and eviction (§8.7)

`OutboxDao.expireDue` runs before every drain: a `PENDING` row past its window becomes `EXPIRED` (state recorded, row kept) and ciphertext is cleared from both `PENDING` and `SENT` rows past the window (the relay's own copy dies on the same 30-day cap, so the local bytes can no longer deliver or repair anything). Rows with `expires_at = 0` predate Step 4 and are left to the migration backfill.

### Backup exclusion

`android:allowBackup="false"` plus `backup_rules.xml`/`data_extraction_rules.xml` excluding `database`, `sharedpref`, `rules_snapshot.json`, and `datastore` — no counter, chain, outbox, or crypto state can enter a cloud backup, which satisfies "exclude unless the secure transfer design explicitly supports restoration" without imposing a restore path.

### Validation evidence (Step 4 list)

| Required validation | Evidence | Result |
|---|---|---|
| Migration / rollback | `AccountabilityDatabaseMigrationTest.migration12to13_addsEventIdAndOutboxRetryColumns_preservingQueuedWork` (seeded v12 rows keep bytes/status/attempts; new columns present) and `migration13to12_restoresTheV12Shape_preservingEveryRow` (table rebuild copies every row, drops only the new columns) | green |
| Concurrent sequence allocation | `ChainStateStoreTest.concurrentAppends_forTheSameSequence_exactlyOneWins_noDuplicateSequence` (pre-existing, re-run) | green |
| Transaction rollback | `ChainStateStoreTest.localAppend_whenTheOutboxInsertFails_rollsBackTheChainRow` (the append reports failure and no chain row/head survives) | green |
| Process death before/after encryption | `localAppend_whenTheOutboxInsertFails_rollsBackTheChainRow` (death before commit → nothing durable) + `outboxRow_survivesAReopen_pendingForTheNextDrain_theCrashConsistencyStandIn` (death after commit → the stored bytes are what retries transmit). Real SIGKILL fault injection is deferred to the Step 11 device matrix; this is an honest stand-in, not a claim of a kill test. | green (stand-in) |
| Same-envelope retry | `MailboxOutboxDrainerTest.drain_writesTheExactStoredBytes_inChainOrder_underMessageId` (byte-identical payload under the same doc id) | green |
| Outbox eviction | `ChainStateStoreTest.localAppend_stampsTheRetentionWindow_fromEventCreationTime` (EXPIRED at the boundary, ciphertext cleared, no longer drained) + `MailboxOutboxDrainerTest.drain_sweepsExpiredRowsBeforeReading_theQueue` | green |
| Clear-data / re-enrollment | New install identity = new `sender_key_id`, so sequence allocation starts a fresh namespace while old rows stay scoped to the old key (`twoSendersOnOnePair_buildIndependentChains_aForkOnOneBlocksOnlyOne`); pair deletion clears its outbox (`deletePairData_clearsTheOutboxWithTheChain`) | green |

Also covered: wire-event idempotency on both paths (`appendLocal_storesTheWireEventId_andADifferentEnvelopeClaimingItIsRejected`, `receiveRemote_storesTheWireEventId_andDeduplicatesByIt`), and the journal contract (`PartnerEventJournalDaoTest`, 4 tests — round-trip both shapes, pairing cut-off + expiry + order + batch limit, boundary-strict sweep, entity contract guards).

### Device migration proof (X6532, 2026-10-21)

`install -r` over the pre-Step-4 build (no uninstall), force-stop, relaunch. Pre → post:

- `myexodus_accountability.db` `PRAGMA user_version` **12 → 13**;
- row counts identical: events 0, outbox 0, chain_heads 0, enrollments 1, processed_events 17, approval_requests 0, unpair_state 1, identity_epoch 1;
- new outbox columns `next_attempt_at`/`expires_at`/`last_error_code` present with defaults; both `events` unique indexes present; no `.corrupt` files;
- `myexodus_rules.db` (4.1 MB, v17 → v18) opened with its WAL intact; Home still shows "1 day / Best streak 5 / Blocks 6 / Times blocked 107" and Rules still lists Netflix 4h + the four website rules.

Honest limit: the device outbox held 0 rows, so the migration's live-row backfill (`UPDATE outbox SET expires_at = created_at + retention WHERE expires_at = 0`) is proven by the seeded JVM migration test, not by a row on that device.

### Interpretation notes (recorded, not silently applied)

1. **Split schema** — the plan's §8.9 `partner_event_outbox` is realized across `events` + `outbox` per S1.7, not as a new parallel table (ADR #197).
2. **`schema_version`** travels inside the prepared envelope rather than as a column; adding a duplicate column would create two sources of truth for one value.
3. **`message_id` as idempotency key** — §8.9 names a "scoped idempotency_key"; the scope is enforced by the `(pair, sender, event_id)` unique index plus create-only mailbox storage, while the key itself is the mailbox document id every retry reuses.

## Appendix S5 — Step 5 relay API (executed 2026-10-21)

**Verdict: PASS, no code change.** The existing mailbox pipeline was built to this contract (its files cite plan §8 Step 5 / AD-10) and already implements upload/fetch/ack for opaque envelopes. This gate mapped every required behavior to code and ran both server suites fresh (104/104 unit, 17/17 rules-emulator). No server change was needed for the new event contract because the payload crosses the relay as opaque bytes.

### Requirement mapping

| Step 5 required work | Implementation | Evidence |
|---|---|---|
| Accept opaque envelopes | `payload` is base64 ciphertext; the rules field allowlist has no plaintext field; the relay only measures decoded length, never decodes content (`relay-logic.js` `validatePayload`) | rules 17/17; `relay-blindness.test.js` 5/5 |
| Authenticate the sender installation | Firebase auth + `senderUid == request.auth.uid` + membership re-check in the trigger; the installation identity (sender key id) travels inside the E2EE envelope and is verified cryptographically by the recipient — the relay cannot see it without breaking §8.1 (ADR #198) | rules "senderUid cannot be spoofed"; `pairingUsableForRelay` tests |
| Verify active relationship authorization | Rules create gate (pairing `active`, sender **and** recipient both members) plus a defense-in-depth re-check in the trigger; `terminated` accepted only for the members-bound deletion-request race (ADR #107) | rules "a revoked relationship cannot queue new events", "another relationship's pairId…", "a pairId the sender does not belong to is denied"; relay tests |
| Enforce size/rate limits | 8192-char storage bound (defense-in-depth) + 4096 decoded-byte relay cap; 10 messages/pair/minute and 200 messages/sender/day advanced in one Firestore transaction | relay "oversized payload is rejected before the pairing check"; `incrementWindows` tests |
| Persist idempotently / stable receipt | Document id = the client-minted message id, create-only; a retry after a lost response is `ALREADY_EXISTS`, which the drainer records as accepted; the same id can never carry different bytes (create-only replay denied) | rules "a mailbox document is create-only"; `MailboxOutboxDrainerTest.drain_alreadyExists_marksSent_withoutRecordingAnAttempt` |
| Fetch only for the intended recipient | Read = mailbox owner, or the author for its own sent document (needed for the Step 18 rejection marker) | rules read tests ×4 |
| Acknowledge after recipient persistence | Per-installation `acks/{installationId}` written only by the recipient after processing; cleanup deletes at the 7-day floor once every registered non-stale installation has acked, or unconditionally at the 30-day cap | rules "only the recipient can acknowledge"; `mailbox-retention.test.js` |
| Server retention until ack, expiry, or revocation | AD-18b retention stands. Revocation does not delete stored ciphertext early; the recipient key shred makes it unreadable and the cap removes it — Step 10 owns the revocation surface (recorded limit, not silent) | retention tests; Step 2 Appendix limit |
| No payload logging, no new vendor | Relay log lines carry `messageId`/`pairId`/`relayStatus` only; Firestore + the existing scheduler; no Redis, no new service | code review of `relay.js`/`dispatch-record.js` |

### Interpretations recorded (ADR #198)

1. **"Authenticate the sender installation"** is satisfied by uid authentication + pair membership at the relay plus cryptographic sender authentication at the recipient. Installation identity is inside the sealed envelope; exposing it to the relay would violate §8.1.
2. **"Stable acceptance receipt"** is the durable, create-only mailbox document under the client-minted id: creation success and `ALREADY_EXISTS` both mean accepted; rejections are explicit markers (`relayStatus` = `RATE_LIMITED` / `PAIRING_INVALID` / `OVERSIZED` / `INVALID_FORMAT`), never inferred.
3. **Sender-side ciphertext after acceptance** — the plan says the sender *may* delete it; this client keeps the stored bytes for chain repair/replay until the 30-day §8.7 cap, which is stricter and JVM-proven (the Step 4 device outbox was empty, so live-row retention on-device is not claimed).

### Validation matrix

- **Unauthorized access** — cross-partner read/list denial, unauthenticated denial, sender spoof, non-member pairId, unknown pairing: rules tests.
- **Duplicate upload** — create-only replay denied at the storage boundary; client retry maps `ALREADY_EXISTS` to SENT without re-recording an attempt.
- **Retry after lost response** — same document id + same stored bytes (`MailboxOutboxDrainerCrashWindowTest`, drainer exact-bytes test).
- **Payload size** — rules refuse at 8192 chars; relay distinguishes `OVERSIZED` from `INVALID_FORMAT` on decoded bytes.
- **Retention** — floor/cap/all-acked/stale-exemption decisions unit-tested; deletion is function-only.
- **Recipient acknowledgement** — ack rule test + `unackedInstallationIds` logic tests.
- **Revocation** — create denial after termination; the members-bound `terminated` relay race is regression-tested.
- **Server restart** — no in-memory server state: every decision reads/writes Firestore, and retry is owned by the scheduler sweep (AD-10c), so a restart loses nothing but an in-flight attempt.
- **Queue backlog** — per-document triggers with `maxInstances: 10` and bounded dispatch batches. The mandated 100k-install load/soak is Step 8's test; it is **not** claimed here.

Commands (run 2026-10-21): `npm test` → 104/104 pass; `npm run test:rules` → 17/17 pass (Firestore emulator).

### Honest limits

- Rejected messages are stored before the trigger rejects them (rules cannot decode the payload). The authorization hole that allowed foreign-mailbox fills was closed at the storage boundary in Step 2 (ADR #194); the remaining cost is a bounded junk row that cleanup removes. The rules fix is **still undeployed** (owner action). *Revision 2026-10-21 (Appendix S6): deployed — production ruleset `42262fca-6962-4cf0-ac54-24fbf4a43a9e`, source verified.*
- A crash between mailbox acceptance and the first dispatch-record write means no push for that message; the recipient still fetches it on app start/resume — that is Step 6's delivery path, stated rather than papered over.
- Nothing in this gate changes or deploys server code, so production behavior is exactly today's until Steps 6–9 land.

## Appendix S6 — Step 6 partner synchronization + notification health (executed 2026-10-21)

> **Revision 2026-11-05 (project closeout):** the user-triggered test alert was removed from the shipped app; every mention below is the historical record of that run. `PartnerAlertHealth` and its banners remain.

**Verdict: PASS** — the receive pipeline already satisfied the synchronization half and was hardened at one crash window; the notification half was built (health model, onboarding surfaces, user-triggered test alert) and proven on both devices. One required item is **explicitly deferred**: the DMS availability reminder (see Interpretation 2), because the `outage_generation` identity and the DMS availability notice it refers to are created by Steps 7/8.

### Requirement mapping

| Step 6 required work | Implementation | Evidence |
|---|---|---|
| Fetch pending ciphertext after push | `PartnerMessagingService.onMessageReceived` → unique one-time `PartnerSyncWorker` carrying the wake message id; `onNewToken` and `onDeletedMessages` ring the same pass, so a dropped push or rotated token reconciles from the mailbox, not the push channel | `PartnerSyncCoordinatorTest`; scenario harness runs the production coordinator; push service code review |
| Fetch on app start/resume | `MainActivity.refreshPartnerPushRegistration` (foreground, permission-result, settings-return) plus the live mailbox snapshot listener under `repeatOnLifecycle(STARTED)`; `MyExodusApp` activity-tick collector kicks a pass after a shared transition | code review; scenario harness |
| Fetch through existing scheduled sync | periodic 15-minute `PartnerSyncWorker` (`PeriodicWorkRequestBuilder`, unique name `partner-sync`), one-time wakes use a distinct name (`partner-sync-wake`) so KEEP cannot suppress them | code review |
| Decrypt, validate, sequence-check, deduplicate, persist, then acknowledge | `MailboxMessageHandler`: processed-events dedupe before any fetch (AD-10b) → pair gate → own-sender skip → verify-then-decrypt → `ChainStateStore.append` (`Appended`/`Duplicate`/`Gap`/`Fork`/`Rejected`) → ack only on the consumed outcomes (`APPENDED`, `DUPLICATE`, `GAP`) → notify only on `APPENDED` | `MailboxMessageHandlerTest` 22 tests |
| Acknowledge only after persistence (crash window) | On `ALREADY_PROCESSED`, the handler retries the idempotent create-only ack before returning, so a death between chain commit and ack is healed by re-delivery without re-applying the event; a failed retry is swallowed and left to the next delivery | `alreadyProcessed_shortCircuitsBeforeLoad_butCompletesTheAck`, `alreadyProcessed_ackRetryFailure_isSwallowed`; scenario Stage 4 |
| Onboarding: permission (API 33+), channel enablement/importance, provider registration, user-triggered test notification | `PartnerAlertHealth` reads `NotificationAccess.canPost` (permission/app-level), the `partner_alerts` channel's importance (absent = not muted), and Google Play services availability; surfaced in `PairingCeremonyScreen.PairedTerminal` and the Partner tab. (The user-triggered test notification was removed 2026-11-05 in the project closeout; the health reads remain.) Provider **registration** is checked/re-run by every sync pass (`PushInstallationRegistrar`), not shown as an onboarding banner — registration failure is transient and the mailbox pull still delivers (Interpretation 1) | `PartnerAlertHealthTest` 4; `PushInstallationRegistrarTest`; device legs |
| In-app banner when notifications disabled or provider unsupported | `PartnerScreen.AlertHealthBanner` for `NOTIFICATIONS_OFF` / `CHANNEL_OFF` / `PROVIDER_UNSUPPORTED`, refreshed on permission result and `ON_RESUME`; fixes route to the permission dialog (API 33+, not yet asked), app notification settings, or `ACTION_CHANNEL_NOTIFICATION_SETTINGS` with package + channel extras. The provider case has no action — no switch fixes it | device legs (below) |
| One generic DMS availability reminder per `(install_id, relationship_id, outage_generation)` after six hours; no per-event reminder | **Deferred to Step 8** (Interpretation 2). Step 6 creates no reminder of any kind: notifications fan out per appended event only, coalesced by the fixed per-pair id + `onlyAlertOnce` | code review |
| Provider acceptance is not display | No code path treats an FCM receipt as delivered; the mailbox pull is the delivery path; display is proven by an actually delivered alert plus the device's own notification state (the test alert used at the time was removed 2026-11-05); `onDeletedMessages` reconciles what push dropped | code review; device alert leg |
| Startup channel agreement | `MyExodusApp` calls `ensurePartnerAlertsChannel`, create-only: an existing channel (and its user-chosen importance) is never rewritten | code review; X6532 channel-restore leg |

### Validation evidence (Step 6 list)

| Required validation | Evidence | Result |
|---|---|---|
| Duplicate envelopes | Scenario Stage 1: same signed bytes under two message ids → head advances once, both copies acked; `MailboxMessageHandlerTest.duplicateAndGap_areAcked_butNeverNotify` | green |
| Out-of-order envelopes + sequence gaps | Scenario Stage 2: seq3 arrives first → `REPAIR_PENDING`, `gapSequences = [3]`, acked; seq2 arrives → head 3, gap cleared, `REPAIR_NONE` in one pass; `PartnerRoundTripInstrumentedTest` gap-repair run | green |
| Offline partner | Design: mailbox is the source of truth; next pass (push/foreground/periodic) delivers whatever a network drop missed; outbox retry/backoff proven in Step 4. Not re-run as a dedicated airplane-mode device leg in this step | design + Step 4 tests |
| Invalid auth | Rules suite 17/17 (unauthenticated denial incl.); live unauthenticated Firestore probe against production returned HTTP 403 `PERMISSION_DENIED` | green |
| Disabled notification permission | X6532: `pm revoke POST_NOTIFICATIONS` → `NOTIFICATIONS_OFF` banner; grant → banner clears. `PartnerAlertHealthTest.permissionDenied_outranksEveryOtherCause` | green |
| Disabled channel | X6532: channel importance set to NONE → `CHANNEL_OFF` banner; deep link opens the channel; re-enable → banner clears and importance is 4 again (the app did not re-create/demote it) | green |
| Provider unregistered | `PushInstallationRegistrarTest.register_withoutFid_skipsTheWrite`, `register_withoutToken_skipsTheWrite`, `register_carriesAPushIneligibleSnapshotWithoutGatingTheWrite`; registration re-runs on every wake/foreground through the sync pass | green |
| Non-GMS device | API-37 AVD with Google Play services disabled: app loads fully, `PROVIDER_UNSUPPORTED` banner shows, provider registration is refused ("push installation not (re)registered this run"), test alert still posts | green |
| Decryption failure | Scenario Stage 3: valid signature over a well-formed envelope with invalid ciphertext → verification passes, decryption fails, not committed, not acked; the same sequence number then accepts the real event | green |
| Process death between persist and acknowledgement | Scenario Stage 4: `ProcessedEvent` seeded for an unacked message → re-delivery writes the missing ack and the head does not move. Honest limit: seeded state, not a real mid-pass kill (Step 11 matrix) | green (seeded) |

### Device evidence (2026-10-21)

> **Revision 2026-11-05:** the observations below are the historical record of the then-shipped test alert; that control was removed in the project closeout. The `PartnerAlertHealth` banners remain.

- **X6532 (physical):** OK state → test alert observed in the tray and in `dumpsys notification` as id 2006, tag `partner_alerts_test`, channel `partner_alerts`, importance 4, with the correct title/body copy. Permission-off and channel-off banners each shown and cleared by the real fix path; the channel came back at importance 4 without the app rewriting it.
- **API-37 AVD:** unpaired Partner tab shows the test-alert row; test alert posts; with Google Play services disabled the `PROVIDER_UNSUPPORTED` banner renders and the push registrar refuses registration while the rest of the app works.
- **Scenario harness (X6532, production relay + rules):** `PartnerSyncScenariosInstrumentedTest` `OK (1 test)` in 62 s with `RESULT duplicate=PASS`, `outOfOrder=PASS`, `decryptFailure=PASS`, `ackCompletion=PASS`, `scenarios=PASS`. `PartnerRoundTripInstrumentedTest` `OK` (63 s). Virtual partner paired through the production ceremony; every assertion reads chain head, gap state, or server ack documents rather than handler return values.
- **Deploy:** the ADR #194 rules fix is **now deployed** (owner authorization). Release `cloud.firestore` resolves to ruleset `42262fca-6962-4cf0-ac54-24fbf4a43a9e`, and its fetched source contains the recipient-membership check in the mailbox create gate — the owner-action limit recorded in Appendix S5 is closed.
- **Full JVM suite:** `./gradlew testDebugUnitTest` → **1,890 tests, 0 failures** (includes the new `PartnerAlertHealthTest` 4 and the ack-completion pins).

### Interpretations recorded (ADR #199)

1. **Provider registration is a sync-pass check, not a banner state.** A missing token or FID is transient (offline, rotation, first-run timing) and the mailbox pull delivers every event regardless; only a device that cannot run the provider at all is a persistent, user-actionable condition. The onboarding check still *happens* — `PushInstallationRegistrar` runs on every pass and records a truthful `pushEligible` snapshot — it just does not pretend a transient failure is a device defect.
2. **DMS availability reminder deferred to Step 8.** The six-hour rule needs `outage_generation` and the DMS availability notice itself, both created by Steps 7/8. Implementing it now would invent the generation identity and the notice it refers to; Step 6 ships no reminder and no per-event reminder, which the rule forbids anyway.

### Honest limits

- `PROVIDER_UNSUPPORTED` is proven by disabling Google Play services on the emulator, not on a true non-GMS device.
- The ceremony's `PairedTerminal` health branches compile and mirror the Partner-tab branches but were not device-exercised — that would require unpairing the live X6532 pair. The same three states are device-proven on the unpaired Partner tab.
- The process-death validation seeds the exact post-commit/pre-ack state rather than killing the process mid-pass; a true SIGKILL fault injection belongs to the Step 11 device matrix and is not claimed here.
- "Provider acceptance is not display" is enforced by construction (no receipt is read as delivery), but an OEM that drops a notification after a successful local post cannot be detected in-app — Android exposes no per-notification read receipt.

## Appendix S7 — Step 7 heartbeat client + monitoring-state snapshot (executed 2026-10-21)

**Verdict: PASS** — the heartbeat rides the enforcement FGS that was already legitimately running (never a heartbeat-only service), with a connected-network 15-minute WorkManager fallback; counters are persisted before send, refused beats never reuse a number, and a stale namespace resyncs instead of locking out; the App Standby diagnostic is read at startup and check-ins only, with at most one explanation per transition; and the E2EE monitoring snapshot is journaled on change/hourly, consent-gated at reconcile, linked into the pair's chain, and delivered through the production relay. Two validation items could not be run on this hardware and are recorded as honest limits, not claimed: reboot-before-unlock and on-device Rare/Restricted bucket mapping.

### Requirement mapping

| Step 7 required work | Implementation | Evidence |
|---|---|---|
| Verify the existing Monitoring FGS, its declared type/purpose, and piggyback a jittered heartbeat ≤ every 15 min while active + check-in at start and on material state changes | The existing `EnforcementForegroundService` (`specialUse`, subtype `app_blocking_and_daily_usage_limit_enforcement`) already runs while the app is enforcing. It now calls `heartbeat.checkIn()` on its 10-minute `HEARTBEAT_TICKS` cadence (client window: 15 min + 0–2 min jitter), forced at every service start whose authority stamp moved and at every observed authority change (`noteLiveness`). No service is started, restarted, or kept alive for heartbeat traffic | `EnforcementForegroundService` diff; device main leg (FGS alive, forced start accepted, live ticks continued after restoration); `PartnerHeartbeatClientTest` 13 |
| Never add/keep an FGS for heartbeat only; WorkManager fallback when absent | `enqueuePartnerSync` is the production 15-minute periodic `PartnerSyncWorker` with a `NetworkType.CONNECTED` constraint (policy changed KEEP→UPDATE so existing devices adopt the constrained shape). `syncOnce` calls `checkIn()` only after the unpaired early return, i.e. paired passes only | device WM leg with no FGS before/during/after; `PartnerSyncWorker` diff; coordinator test |
| Count all fresh authenticated requests as liveness after replay/idempotency checks; counter scoped to `sender_install_id` | Liveness = an accepted `submitHeartbeat` beat: server transaction reads the `(uid, installId)` state row, refuses `counter <= last_counter` as STALE (never liveness, returns the server counter) and beats inside 30 s as RATE_LIMITED (prior receipt stands), and only then advances generation + deadline. The client persists the next counter BEFORE the call, so a failed/refused send burns it. Scope is the Firestore document id `${uid}_${installId}` | `decideHeartbeat` unit 11; emulator suite 12; device floor-refusal leg (`burned=17`, next accepted 18); `PartnerHeartbeatClientTest` burn/resync/uid-rotation |
| Cleared-data/reinstalled device re-enrolls with a new install identity and fresh counter namespace, never rejected forever | A rotated anonymous uid is a new Firestore document (counter restarts at 1); a reinstall gets a new Firebase installation id. A client that lost its counter while keeping its namespace resyncs to the server's number (`counter = maxOf(serverCounter, counter)`) and never reuses a burned number | `uidRotation_startsAFreshCounterNamespace`; `staleServerCounter_resyncsOntoTheServer`; emulator stale-counter cases; device RESYNCED floor refusal |
| API 28+ `getAppStandbyBucket()` at startup/check-in or after an observed delay, never a tight poll; Rare/Restricted → low-sensitivity diagnostic; at most one non-intrusive explanation per transition/session; optional guidance only; no Unrestricted-required claim; never request battery exemption | `PartnerHeartbeatClient.checkStandby`: read once per 6 h AND once per process at the first check-in, bucket name persisted only. `RARE`/`RESTRICTED` emits once per transition on `PartnerSyncSignals.standbyNotice`; a return to an unrestricted bucket re-arms it. `AppShell` shows one snackbar naming where to look; no permission is requested anywhere in the path | `standbyTransition_isExplainedOnce_andRearmsAfterRecovery`; `startup_inspectsStandby_oncePerProcess_evenInsideTheInterval`; device persisted bucket = platform (`EXEMPTED`) |
| E2EE status snapshot per authorized relationship on change and during eligible check-ins; ACTIVE only after confirming current permissions/components; otherwise DEGRADED/UNAVAILABLE/UNKNOWN; include only current install generation, outbox-age bucket, latest committed `relationship_seq`; never in plaintext heartbeat metadata | `MonitoringSnapshotRecorder` journals a `MONITORING_STATUS` row on change or hourly (`force` at service start/material change), then `PartnerSyncCoordinator.reconcileEvents` builds one sealed envelope per consenting pair (`shareAvailability` + `availabilityEnabledSinceMs` watermark) with `senderKeyId` as install generation, `OutboxAgeBucket` from the oldest pending outbox row, and the sequence allocated by `EventCreator.createBody` inside the create mutex (the event's own seq and `latest_relationship_seq`). ACTIVE requires `MonitoringLiveness.Confirmed(FULL)`: FGS alive in-process AND its authority stamp refreshed within 90 s; absent → UNAVAILABLE; alive-but-stale → UNKNOWN. The heartbeat payload remains `{installId, counter}` | `MonitoringSnapshotRecorderTest` 9; `MonitoringSnapshotReceiverTest` 3; `LivenessSnapshotTest` 4; `PartnerSyncCoordinatorTest` monitoring queue; device sender-half leg; server row contains no monitoring fields |

### Validation evidence (Step 7 list)

| Required validation | Evidence | Result |
|---|---|---|
| Counters/replay | `PartnerHeartbeatClientTest` (13): persisted-before-send, window boundary, jitter only stretches, forced bypass, failure burns, stale resync, refusal preserves acceptance, uid rotation, unresolved identity, startup standby, transition notice, malformed replies. Device: forced beat inside the server's 30 s floor returned RESYNCED with `burned=17` and the prior acceptance time intact; the next honest beat (18) was accepted | green |
| Reinstall/new identity | JVM `uidRotation_startsAFreshCounterNamespace` (new uid → counter 1) and `staleServerCounter_resyncsOntoTheServer` (lost counter resumes from the server, never locked out); emulator suite covers first-beat, stale, floor, and new-document cases. Device reinstall/clear-data not run (owner rule: `install -r` only, never uninstall) — JVM + server emulator are the proof | green (JVM/server); device not run, see limits |
| FGS active/inactive paths | Device main leg ran with the FGS alive: forced start beat accepted, 10-minute ticks continued, standby read taken; device WM leg ran with no FGS in the process (`getRunningServices` checked before, during, and after) and still produced an accepted beat + startup standby read | green |
| WorkManager delays | Device WM leg waited out the client's real 15-minute floor + jitter on the wall clock (`waitMs=919969`), then force-due'd the production worker; acceptance was asserted on `lastAcceptedCounter == counter` (the reply landed), not on the persisted-before-send bump | green |
| Force-stop | Host force-stopped the app before the WM leg; no beats occurred while stopped; instrumentation then started the process, `MyExodusApp` correctly skipped its FGS re-arm under instrumentation, and only the due worker produced the beat | green |
| Reboot-before-unlock | Not run: the X6532 has no USB transport in this environment and wireless adb (`adb_wifi_enabled=0`, tcpip mode) does not survive a reboot. The FGS process-start re-arm it would exercise is already device-proven under ADR #152; Step 7 adds no boot receiver and no heartbeat-only service | **blocked**, see limits |
| No network | Device leg: airplane mode armed by the host; the offline beat returned FAILED and burned its counter (14) without unproving the prior acceptance (13); after radio recovery the next online beat (15) was accepted and advanced the acceptance time | green |
| App Standby Rare/Restricted | Mapping, jitter floor, and once-per-transition behavior are JVM-proven (`PartnerHeartbeatClientTest`); on-device the X6532 is deviceidle-whitelisted (bucket pinned EXEMPTED) and instrumentation foregrounds the app, so Rare/Restricted cannot be observed there. The device asserts the persisted bucket equals the platform's current report (`EXEMPTED` on both legs) | JVM green; device restricted bucket not observable, see limits |
| Missing notifications | The main leg captured the set of active notification ids before and after the entire heartbeat sequence and asserted it unchanged; nothing on the heartbeat path posts | green |
| Stale monitoring-state snapshot | `PartnerDashboardSourceTest` stale (>2 h since receipt) reads `MonitoringState.UNKNOWN`; `MonitoringSnapshotRecorderTest` debounce/change/consent cases; `MonitoringSnapshotReceiverTest` + `PartnerMonitoringSnapshotDaoTest` replay guard (older install generation or sequence never replaces a newer row; a newer install generation replaces regardless of sequence) | green |

### Device evidence (X6532, 2026-10-21, production callable + rules)

- **Deployment:** `submitHeartbeat` is ACTIVE in `us-central1` (gen 2, `maxInstances 10`, `timeoutSeconds 15`, update 2026-10-21T16:42Z). The production `cloud.firestore` release (`rulesets/e7ce7dbe-969e-405b-881e-4644b53ce369`) contains the explicit `dms_install_state` deny-all rule and otherwise matches the working tree.
- **Main leg** (`deployedCallableAcceptsTheDeviceBeat_andTheServerFloorHolds`, OK): production install registration passed under App Check enforcement; the first beat was accepted; a forced beat inside the 30 s floor returned RESYNCED and burned the number (`RESULT serverFloor=PASS burned=17`) while keeping the prior acceptance time; the post-floor beat (counter 18) was accepted. Standby persisted `RESULT standby=EXEMPTED`; no notification was posted (`heartbeat=PASS`); with availability sharing off for every pair the recorder correctly stayed silent (`RESULT snapshot=NO_CONSENT`). Main leg ended at server row counter 18 / generation 12.
- **WM fallback leg** (`workManagerFallback_beatsWithoutTheForegroundService`, OK): no FGS in the process; production work cancelled and re-enqueued due-now after the real floor was waited out; `RESULT wm=PASS counter=13 standby=EXEMPTED standbyCheckedAt=1792604307053` — the WM process's first check-in was a startup standby read, the server accepted the beat (row generation 9), and no FGS appeared before, during, or after.
- **No-network leg** (`noNetwork_beatFailsBurnsItsCounter_andTheNextOnlineBeatRecovers`, OK): `RESULT offline=ARMED counter=13 accepted=13`; airplane mode; `RESULT offline=FAILED burned=14 accepted=13` (FAILED, counter burned, prior acceptance intact); radio restored; `RESULT offline=PASS counter=15 at=1792604394992` (accepted, burned number never reused).
- **Snapshot sender half** (`MonitoringSnapshotInstrumentedTest`, OK 14.875 s): consent enabled for the one resolvable pair; `RESULT monitor=JOURNALED event=1a8dce8e-… state=UNAVAILABLE outbox=UNDER_1H` (no FGS in the instrumentation process — the truthful state); the production pass built the envelope and `drain: 1 event(s) delivered` reached the relay; `RESULT monitor=LINKED pair=WvdzKxfVkcOwNH6PudjtkmvdoNl2_oxs4tQd0kRX1VXBhChVtKLIP9hH2 linked=1`; `RESULT monitor=PASS`; consent restored (`RESTORED availability=false`).
- **Live FGS continuation after restoration:** the rebind returned the service to `authority=FULL degradation=null`, and the production row kept advancing from the main leg's counter 18/generation 12 to **counter 23 / generation 16** by 17:54:53Z, with `next_deadline_at` exactly `last_received_at + 6 h` and `last_notified_generation` still 0 (the deadline processor is Step 8's).
- **Server suites:** `npm test` 115/115 (11 heartbeat-logic cases); `npm run test:rules` 18/18; `npm run test:heartbeat` (Firestore emulator) 12/12.

### Interpretations recorded (ADR #200)

1. **"Count all fresh authenticated requests as liveness"** is satisfied by accepted heartbeat beats only. The installation identity exists solely in this call by design: the relay is identity-blind (ADR #198) and §8.10 forbids monitoring fields in plaintext metadata, so attributing mailbox fetch/ack traffic to an installation would leak a stable device identifier to the relay. Every beat that passes the replay/idempotency checks (strictly newer counter, outside the 30 s abuse floor) counts as liveness; replayed, stale, and floor-refused requests never do.
2. **"Material state change"** is an observed `ProtectionAuthority` move (FULL / DEGRADED_POLLING_ONLY / DEGRADED_NO_DETECTION). The first stamp of a service instance counts as material because a fresh process has proven nothing yet; repeated starts inside the client window stay unforced.
3. **The snapshot's install generation** is `sender_key_id` (SHA-256 of the install's signing key), the same identity mapping as ADR #197; the "latest committed `relationship_seq`" is the sequence allocated inside `EventCreator`'s create mutex via the new `createBody` seam, so the value is the event's own allocation rather than a stale read.
4. **"Eligible check-ins"** means hourly steady state plus forced snapshots at service start and material change. The change-debounce row advances even when no pair consents, so enabling sharing later cannot replay a stale transition.
5. **Consent watermark.** A consenting pair only receives rows created at or after `availabilityEnabledSinceMs`; enabling availability never backfills pre-consent monitoring history, and disabling it stops all future sends.
6. **ACTIVE is a claim, not an assumption.** It requires a live FGS in this process and an authority computed within 90 s (two status cycles plus margin). Alive-but-stale reports UNKNOWN; no service reports UNAVAILABLE. The dashboard additionally degrades a snapshot not received for 2 h to UNKNOWN rather than showing a stale ACTIVE.
7. **The heartbeat carries nothing else.** Its only payload is `{installId, counter}`; the server row stores liveness counters, generations, receipt times, and the next deadline, never monitoring state, permissions, categories, or sequence numbers.

### Honest limits

- **Reboot-before-unlock was not run.** The X6532 is reachable only over wireless adb, which does not survive a reboot (no USB transport here). Step 7 adds no boot path of its own; the FGS re-arm behavior was device-proven under ADR #152, and the WM fallback's independence from the FGS is device-proven here.
- **Rare/Restricted buckets are not device-observable on this hardware.** The X6532 is deviceidle-whitelisted (bucket pinned EXEMPTED) and instrumentation always foregrounds the app. The bucket mapping, the 6-hour interval, the startup read, and the once-per-transition notice are JVM-proven; on-device the persisted value equals the platform's current report.
- **Reinstall/clear-data was not run on the X6532** (owner rule: `install -r` only, never uninstall). New-identity namespaces and lost-counter resync are JVM- and emulator-proven; the server emulator additionally proves a reinstall's fresh document starts at counter 1.
- **The snapshot receive half is JVM-proven, not peer-device-proven.** The sender leg delivered a real envelope through the production relay and linked it into the chain; rendering ACTIVE/Limited on a second device requires a second consenting device, which this environment does not have.
- **WorkManager cadence is platform-owned.** The leg proves one due pass end-to-end after waiting the real client floor; it does not observe repeated 15-minute periods over hours, and OEM batching may delay a pass further (the heartbeat is best-effort by contract).
- **The six-hour partner alert is Step 8's.** Step 7 only writes `next_deadline_at`; `last_notified_generation` remains 0 until the deadline processor exists.
- **Harness defect found and fixed (not a product defect):** the first WM run asserted on the persisted-before-send counter bump and cancelled the worker mid-call, so the assertion raced the in-flight reply. Firestore showed the server never saw that counter. The test now waits for `lastAcceptedCounter == counter`; the production persist-before-send design behaved exactly as specified (burn, never reuse).

## Appendix S8 — Step 8 backend DMS deadline processor + durable notifications (executed 2026-10-21)

**Verdict: PASS** — the deadline store is the existing Firestore database, not a new queue service: `dms_install_state.next_deadline_at` is the delayed job, read by a bounded indexed due query on a five-minute Cloud Scheduler tick. Transitions and their notification outbox rows commit in one transaction; dispatch rejoins the authoritative pairing document and rechecks consent; stale jobs are CAS no-ops; ingestion outages rebase and scheduler outages do not; a fleet late burst opens a breaker that holds dispatch, pages the operator through a Cloud Monitoring log alert, and releases at a bounded rate. The client half converges the local sharing toggle to the server on every sync pass, shows the read-time connection card, and creates the `availability_alerts_v1` channel. All four functions, the composite index, the rules denials, and the alert policy are deployed to `myexodus-53804`; the 100k soak and the device leg are green. One rollout finding is recorded honestly: the first scheduled run (18:35 UTC) hit the outbox composite index while it was still building; the next tick after it became READY succeeded.

### Requirement mapping

| Step 8 required work | Implementation | Evidence |
|---|---|---|
| Inspect existing infrastructure; use a durable delayed-job mechanism only if it supports scheduled execution, retry, idempotency, and stale-job validation; otherwise deadline row + bounded worker; no new queue service | Existing Firestore database + the existing Cloud Scheduler facility. The deadline row IS the job: `dms_install_state` stores `install_id`, `liveness_generation`, `last_received_at_ms`, `last_counter`, `next_deadline_at`, notice state. No Redis, Pub/Sub, or key-expiry trigger exists anywhere in the path | `functions/dms-processor.js`; deployed `dmsDeadlineWorker` (gen 2, `*/5 * * * *` UTC, `maxInstances 1`, `timeoutSeconds 300`) |
| Bounded due query only for rows due before `now`, ordered by `next_deadline_at`, with a supporting index; no full-table scan | `.where('next_deadline_at','<=',now).orderBy('next_deadline_at').limit(config.dueBatch)` (default 100, clamped 1–500). Single-field index is automatic. Dispatch uses `.where('status','==',PENDING).orderBy('next_attempt_at_ms').limit(dispatchBatch)` over `dms_notification_outbox`, backed by the composite index now READY in production | 100k soak: 100,000 installs seeded, 1,000 due, due query = 100 rows in 3,297 ms, 10 bounded passes, p95 11.2 s (bound 60 s), 1,000/1,000 notified with exactly 1,000 outbox rows; index state READY verified via the Firestore Admin API |
| Every accepted liveness update increments `liveness_generation` and records `last_received_at`, `last_counter`, `next_deadline_at` | Step 7's acceptance transaction already does this; Step 8 consumes exactly those fields. A beat inside the 30 s floor or a stale counter never advances them | `heartbeat.emulator.js` 12/12; server row observed advancing generation 12→16 with `next_deadline_at = last_received_at + 6 h` (Appendix S7) |
| Delayed jobs contain `install_id`, expected generation, expected deadline; stale jobs become no-ops | The due row carries all three; `_processDueRow` re-reads the row and CAS-checks generation, receipt time, and already-notified state before writing | `worker: stale delayed jobs are CAS no-ops (generation, receipt, already notified)` |
| Worker state changes use compare-and-set | Every transition is a Firestore transaction: read → validate → update; the notice/outbox creation is in the same transaction | `worker: two concurrent passes produce exactly one notification and one send` |
| Notification outbox committed in the same transaction as the notification state transition | `processDueRow` writes the state transition (`notice_state = NOTIFIED`, `outage_generation += 1`, `last_notified_generation`, `next_deadline_at` deleted) and the deterministic outbox document in one `runTransaction` | `worker: the six-hour deadline commits the transition and its outbox row, then sends`; a transition that loses the CAS writes neither |
| At send time, rejoin the authoritative relationship table and recheck `DEVICE_AVAILABILITY_STATUS` consent | `dispatchOutbox` reads `pairings/{relationshipId}`, requires `status == active`, both members present, and the monitored member's `availabilityConsent.{uid}.enabled === true`; refusal marks the row SUPPRESSED (not retried) | `dispatch: consent revoked between transition and dispatch suppresses the notice`; `dispatch: a relationship closed after the transition suppresses the notice` |
| Display `CHECKING_IN`/`LATE`/`NOT_CHECKING_IN` at 45/90 min; partner push only at the initial six-hour threshold | `deriveAvailability` is pure read-time derivation (45 and 90 min, coarse buckets); the worker's only external transition is `lastReceived + 6 h`. `readPartnerStatus` returns the derived state without any precise timestamp | `readPartnerStatus: the 45/90-minute display states and coarse buckets derive at read time`; `worker: a row short of six hours is untouched` |
| A scheduler outage does not rebase heartbeat timestamps | Nothing in the worker writes `last_received_at_ms`; catch-up processes the same receipt times, and notices that are no longer due are silently skipped | `worker: a scheduler outage leaves receipt times alone and catches up in bounds` |
| A heartbeat-ingestion outage is recorded separately; affected deadlines rebase to `max(last_received_at, relevant_outage_end) + threshold` | `recordIngestionOutage` (operator-gated, one open record at a time) writes `dms_ingestion_outages`; while one is open the worker holds transitions AND dispatch; after close, `_outageEndFor` rebases rows whose interval overlapped it, and the read-time status applies the same compensation | `recordIngestionOutage: operator-gated, one open record, idempotent close`; `readPartnerStatus: an overlapping ingestion outage rebases; a bare gap does not`; `worker: an open ingestion outage holds transitions and dispatch`; `worker: an ended outage that consumed the deadline rebases to outage end + 6h` |
| Fleet circuit breaker: hold dispatch when late transitions exceed the deployment-configured baseline in a short window, page the operator, investigate before releasing at a controlled rate | `notified > config.late_burst_threshold` (default 50, clamped 1–100,000) writes `config/dms.dispatch_hold=true` + incident fields in the same pass, logs at ERROR, and holds dispatch; release is operator config (`dispatch_hold=false`), and `dispatch_batch` bounds each pass. Operator paging is a Cloud Monitoring log-based alert policy on the ERROR line, routed to the existing owner email channel | `worker: a fleet late burst opens the breaker; release is bounded and catch-up is controlled`; alert policy `projects/myexodus-53804/alertPolicies/12571310519408075705` enabled, channel `18347258884556077313` |
| Client: explicit consent intent, no invented state, display card, channel | `PartnerSyncCoordinator.syncPair` converges `PairProfileSharing.shareAvailability` to `setAvailabilityConsent` on every pass, only when a local row exists (the user actually expressed a choice); failures retry next pass. `PartnerDmsClient` caches acks in memory and the read for 60 s, never invents a status on failure. `PartnerScreen.PartnerConnectionCard` renders the three display states plus not-shared/no-data, as a third card distinct from monitoring and sync ( §8.13). `availability_alerts_v1` is created at startup, create-only, HIGH | `PartnerDmsClientTest` 12; `PartnerSyncCoordinatorTest` +5 consent tests (38 total); device leg below |

### Validation evidence (Step 8 list)

| Required validation | Evidence | Result |
|---|---|---|
| 100k-install load/soak against the chosen implementation | `npm run test:dms-soak`: 100,000 installs seeded in 20 s, 1% due (1,000), due query 100 rows/3,297 ms, 10 passes to drain, median 9.9 s/pass, p95 11.2 s (< 60 s bound), 0 failures, exactly 1,000 notifications and 1,000 outbox rows, future rows untouched | green |
| Stale delayed messages | Emulator: generation mismatch, a heartbeat arrived after the deadline was read, and an already-notified row are all no-ops (no notice, no send) | green |
| Concurrent workers | Emulator: two full worker passes race one due row → `notified = 1`, one outbox row, one send. Production additionally runs `maxInstances 1` | green |
| CAS duplicate transitions | Same race at the state-row level; the losing transaction writes nothing | green |
| Transaction-crash between transition/outbox/dispatch | The transition and outbox commit atomically (no half-state is representable); a committed PENDING row with no dispatch attempt is exactly the state the dispatch tests start from (consent-revoked / relationship-closed passes), so the next pass claims it; a crash mid-dispatch is covered by the abandoned-claim test | green (structural + lease requeue) |
| Queue failure | Emulator: a DISPATCHING claim whose lease expired is requeued and retried; a failing provider backs off and terminates inside the attempt bound | green |
| Scheduler outage | Emulator: receipts survive catch-up unchanged; due rows are processed in bounded batches | green |
| Ingestion outage | Emulator: open outage holds transitions and dispatch; ended outage rebases only overlapping rows to `max(last, end) + 6 h`; status read applies the same compensation | green |
| Correlated-client-outage simulation | Emulator: a fleet late burst opens the breaker, dispatch is held, and release respects `dispatch_batch` | green |
| Controlled catch-up after recovery | Emulator: batch bounds cap a pass and the remainder catch up on later passes; release is bounded | green |
| Client contract (consent idempotence, status TTL, fail-closed) | `PartnerDmsClientTest` 12: one callable per confirmed value, changed value re-sent, unacked failure retried, no identity sends nothing; 60 s TTL boundary, force refresh, failed read invents nothing, forced refresh keeps only a still-fresh reading, expired cache + failure → null; parser accepts the callable shape and rejects malformed/unknown states | green |
| Consent convergence through the production pass | `PartnerSyncCoordinatorTest` +5: enabled converges once, explicit off converges false, no local row sends nothing, unresolved pair sends nothing, a failed call does not stop the pass | green |

### Device evidence (X6532, 2026-10-21, production callables + rules)

- **`PartnerDmsInstrumentedTest` → `OK (1 test)`.** A real pairing with a fresh virtual partner (`A=ALOAaMXMCmWfuGc2JXWw7tEfop92`, `B=6nymwAZweOVooxbn9POqpJ0vsbD2`), both sides `Paired` with SAS `37554`. Channel: `RESULT channel=HIGH id=availability_alerts_v1`. No local sharing row → the sync pass wrote nothing and the partner read `consent=false` (`noLocalRow=PASS`). Setting the exact row the sharing toggle writes and running the production pass → partner read `consent=true` (`consentOn=PASS`). The client's status read about an unconsented partner parsed `NO_DATA` (`statusAboutUnconsentedPartner=PASS`); after the partner set its own scope through the deployed callable, the client parsed `consent=true` (`statusAboutConsentedPartner=PASS`). Revocation through the pass → partner read `consent=false` (`consentOff=PASS`). A foreign pair id was refused without throwing: `syncConsent=false`, `status=null` (`refusedWrites=PASS`). No notification was posted by any of it. `RESULT dms=PASS`.
- **Server-state proof.** The production pairing document contains BOTH members' consent entries — `6nym…: {enabled: true}` and `ALOA…: {enabled: false}` — proving the dotted-path update (not a nested merge) preserved the other member while the device's revocation landed (`updatedAtMs 1792607928516`).
- **Deployment.** `setAvailabilityConsent`, `readPartnerStatus`, `recordIngestionOutage`, and `dmsDeadlineWorker` are ACTIVE in `us-central1` (update 2026-10-21T18:30Z; App Check enforced on all three callables; worker `maxInstances 1`, 300 s). Cloud Scheduler job `firebase-schedule-dmsDeadlineWorker-us-central1` is ENABLED at `*/5 * * * *` UTC.
- **Production worker run.** The 18:40:01Z tick returned HTTP 200 in 0.64 s and logged `dms worker pass` with `due=0 notified=0 deferred=0 stale=0 outboxCreated=0 requeued=0 dispatchHeld=false sent=0`. **Rollout finding:** the 18:35:00Z first tick (job created 18:30) failed with `9 FAILED_PRECONDITION: The query requires an index. That index is currently building` — the outbox composite index was deployed in the same release and became READY minutes later; the next scheduled tick succeeded with no intervention. Deploying indexes before functions avoids the one-tick failure; the worker's next tick is the retry.
- **Rules.** The deployed `cloud.firestore` release is now ruleset `75b62e73-5feb-463c-8514-4c0407f0c7ca`, whose source contains explicit deny-all matches for `dms_install_state`, `dms_notification_outbox`, and `dms_ingestion_outages` (the previous deployed release carried only the first; the other two were deployed in this step).
- **Operator page.** Alert policy `MyExodus: DMS fleet late-burst breaker opened (Step 8)` is enabled, matches the worker's breaker ERROR log, routes to the existing owner email channel, and auto-closes after 24 h without a re-fire (the dispatch hold itself remains until an operator releases it).
- **Suites.** Functions unit `npm test`: **131/131**. DMS emulator `npm run test:dms`: **28/28**. Full JVM `:app:testDebugUnitTest`: **1,952 tests, 0 failures** (includes the 12 new client tests and 5 new coordinator tests). Soak: PASS (above).

### Interpretations recorded (ADR #201)

1. **The delayed-job facility is the deadline row, not a queue.** Cloud Scheduler is only the clock. The row named by the plan (`install_id`, expected generation, expected deadline) carries retry (next tick), idempotency (CAS + deterministic outbox id), and stale-job validation (generation/receipt re-read), which is what the plan's conditional required; no new queue service was introduced.
2. **Display states are read-time derivation; only the six-hour push is persisted.** Nothing writes CHECKING_IN/LATE/NOT_CHECKING_IN; storing them would create a second source of truth that can drift from the receipt times. The transition the worker persists is the notice itself.
3. **"Page the operator" is a monitoring alert, not an in-code side effect.** The breaker writes the hold and logs at ERROR; a log-based policy on that line notifies the existing owner email channel. Dispatch remains held after the page closes — releasing is a deliberate operator action.
4. **A local sharing row is required before consent converges.** No row means this install never expressed a choice; a second install of the same identity must not overwrite a value it did not set. Failure to acknowledge is quiet and retried; the server value stands until a pass succeeds.
5. **Consent is per member on the authoritative pairing document.** The write is a dotted-path update (`availabilityConsent.{uid}`), so one member's entry never erases the other's — the Firestore nested-merge trap already recorded for the pairing record. Dispatch rechecks only the monitored member's entry.
6. **The connection card is a third card, not part of monitoring or sync.** §8.13's display contract is about device availability ("the app talked to the server"), a different fact from monitoring permissions and from sync health; mixing them would imply one causes another. The client reads at most once per 60 s (force-refreshed on screen reload).
7. **The client's ack cache is memory-only.** Offline revocation is "pending until acknowledged" by contract; a process death simply re-sends the idempotent callable on the next pass instead of persisting a completion flag that could lie.
8. **Scheduler outages are not recorded as ingestion outages.** Only an operator can assert that heartbeats were not arriving; a late worker changes when a transition is observed, never what the device's receipt time was.

### Honest limits

- The 100k soak runs against the Firestore emulator (no index enforcement, single-process scheduling); it proves bounded query shape, batching, and pass cost, not production multitenancy behavior. Production load at that scale is Step 12's staged rollout.
- A transaction crash between transition and outbox is not representable — atomicity IS the proof; a crash immediately after commit is exercised structurally by the dispatch tests that start from a committed PENDING row, not by a real SIGKILL.
- The device leg proves A's consent write/revocation and the parse of both consented and unconsented shapes, but the `CHECKING_IN`/`LATE` reading is derived from a real heartbeat receipt only when the monitored identity has an install-state row; the virtual partner never beats, so the device observes the truthful `NO_DATA` reading for it. The 45/90/6 h derivations are covered by the emulator suite.
- The breaker alert fires on the worker's log line; the page was created and verified enabled, not fired in production (a real fleet burst was not induced). Firing/notification delivery is infrastructure behavior covered by the same policy machinery as the existing R-03 alert.
- The first production tick failed once on the still-building composite index. This is a deploy-ordering constraint, recorded rather than fixed in code: a worker cannot wait for index builds inside its 5-minute tick, and the next tick recovered. Future deploys must include `firestore:indexes` before or with the first worker release.

## Appendix S9 — Step 9 generic push notifications (executed 2026-10-21/22)

**Verdict: PASS** — every push is now a versioned generic wake (`{v:"1", type:"SYNC_AVAILABLE", messageId?}`) or fixed OS copy on the server; ordinary sync is normal priority, user-visible time-sensitive notices are high priority, and every class carries a TTL and a collapse key. Provider-`UNREGISTERED` tokens are removed with a tombstone that is explicitly not an uninstall claim, and the client's in-app sync/status path does not depend on push at all. The three immutable channels exist on the device with the retired id deleted. Live FCM legs on the X6532 in forced deep idle proved deferral, expiry, collapse, and the high-priority exception.

### Requirement mapping

| Step 9 required work | Implementation | Evidence |
|---|---|---|
| Use the generic payload schema in Section 8.10 | Relay and sweep send exactly `{v:'1', type:'SYNC_AVAILABLE', messageId?}` (opaque relay id; absent = whole-mailbox reconcile). The availability/recovery notice sends `{v:'1', type:'SYNC_AVAILABLE'}` beside fixed OS copy; the LATE reminder sends `{v:'1', type:'CHECKIN_REMINDER'}`. The client parser accepts the legacy `domain-event` type and ignores unknown versions | `push-payload.test.js` 5; emulator `payload:` 2; `PartnerPushParserTest` 9 |
| High-priority FCM only for user-visible, time-sensitive notifications | Availability/recovery notices: `priority: high` (they render OS copy). Wakes and the LATE reminder: `priority: normal`. No invisible path uses high | `push-payload.test.js`; emulator payload tests; live Doze leg (high received while idle, normal held) |
| No content that requires a subsequent network call to interpret the initial notice | The notice body is complete on its own ("A paired device has not checked in recently." / "…has checked in again."); the reminder says "Your device has not checked in recently. Open the app to stay connected." None names a person, relation, device, category, or time | `push-payload.test.js` + emulator payload tests assert exact copy and identity absence |
| Normal priority for ordinary sync | Relay/sweep wakes: `priority: normal`, data-only, no `notification` block | `push-payload.test.js`; live Doze leg |
| Apply TTL/collapse identifiers to suppress stale duplicate prompts | Wake: 30-minute TTL, constant `sync` collapse key (one wake runs the full pass). Notice: 60-minute TTL, per-relationship hashed `avail-<16 hex>` collapse key (recovery supersedes silence; pairs never collide). Reminder: 45-minute TTL, `checkin` collapse key | `push-payload.test.js`; emulator payload tests; live expiry and collapse legs below |
| On `UNREGISTERED` or equivalent, remove the invalid token; do not interpret it as conclusive uninstall | `dispatch-record.js` and `dms-processor.js` delete `token` and set `stale: true` with `staleAt`; the next successful registration rewrites the token and clears the tombstone. No code path deletes the installation row or asserts uninstall | Emulator: `dispatch: invalid provider tokens remove the token and tombstone the install, not the monitor`; `late reminder: an UNREGISTERED token is removed and the nudge is never retried` |
| Provide in-app sync and status when notifications cannot be delivered | A wake with no `messageId` runs a full mailbox pass; foreground/resume and the 15-minute periodic worker are independent of push; `PartnerAlertHealth` reports `NOTIFICATIONS_OFF` / `CHANNEL_OFF` / `PROVIDER_UNSUPPORTED` with fix paths, and the user-triggered test alert proves the display path | `PartnerPushParserTest.genericWakeWithoutMessageId_runsAFullSyncPass`; `PartnerAlertHealthTest`; `PartnerNotificationsTest`; Step 6 device banner leg (ADR #199) |

### Validation evidence (Step 9 list)

| Required validation | Evidence | Result |
|---|---|---|
| Payload inspection | `functions/test/push-payload.test.js` 5 tests pin every plaintext byte: the exact relay wake object, its android block, the availability notice copy/priority/TTL/collapse/package/channel, recovery-supersedes-pair-isolation, and the reminder's copy/priority/TTL/collapse/channel — each also scanned against a forbidden-identity list and the real uid/install/pair strings. The emulator's two `payload:` tests re-pin the wire messages inside the processor path. `PartnerPushParserTest` 9 pins the client contract | green |
| Delayed/expired/collapsed push | Live FCM v1 sends to the production registration token with the device in forced deep idle: (1) normal wake accepted 20:52:27Z, held, delivered on idle exit at 20:55:34Z; (2) high wake accepted 20:54:47Z, received 23:54:46.421 local while still `IDLE`; (3) 2-second-TTL message accepted 20:56:15Z, never delivered after exit; (4) two same-collapse-key wakes accepted 20:57:04Z/20:57:09Z, exactly one receipt (`dozetest-collapse-2`) | green |
| Notifications disabled | `PartnerNotificationsTest` posting gate (denied → no channel created, no active notification, no crash; assertions updated to the new channel id); `PartnerAlertHealthTest.permissionDenied_outranksEveryOtherCause`; the banner + fix path were device-proven in Step 6 (ADR #199) | green (logic) / device-proven in Step 6 |
| Channel blocked | `PartnerAlertHealthTest.mutedChannel_outranksProviderSupport` reads the new id; `ensureAccountabilityEventsChannel_isHigh_andRetiresTheLegacyId` pins creation/retirement; device `dumpsys notification` below shows the live channel state | green |
| Provider token invalidation | Emulator `dispatch:` and `late reminder:` UNREGISTERED tests assert `stale: true`, `token` undefined, no retry, and the monitored user's own rows untouched | green |
| Non-GMS device | `PartnerAlertHealthTest.missingProvider_isUnsupportedOnlyWhenNotificationsCanOtherwisePost`; the API-37 AVD leg in Step 6 proved the `PROVIDER_UNSUPPORTED` banner with Google Play services disabled | green (no non-GMS hardware exists) |
| Doze tests | The four live legs above, with `dumpsys deviceidle get deep` = `IDLE` at acceptance and `ACTIVE` on exit; the device was returned to `ACTIVE` after every leg | green |
| Separation of send acceptance from client sync/display metrics | `sendDmsPush` returns `providerAccepted` (renamed from `delivered`); the outbox stores `provider_accepted`; the soak harness and dispatch-logic pins use the new name; `MyExodus Monitoring.md` §2 keeps T2 at "provider acceptance" and T3 explicitly not client-measurable. The device's sync pass is observed separately (`MyExodusPushRegistrar: push installation registered`) from send acceptance | green |

### Device evidence (X6532, 2026-10-21/22)

- **Channels on device (`dumpsys notification --noredact`):** `partner_accountability_events_v1` importance 4 (HIGH), `checkin_reminders_v1` importance 3 (DEFAULT), `availability_alerts_v1` importance 4 (HIGH), and the retired `partner_alerts` channel present only as `mDeleted=true` (`mDeletedTimeMs=1792610322554`) — the startup migration ran on a real device.
- **FCM Doze leg** (production-shaped payloads sent to the real registration token; device forced into deep idle): normal wake accepted 20:52:27Z with `deep = IDLE`, no receipt; after `unforce`/`force-active`, `MyExodusPush: wake for dozetest-normal` at 23:55:34 local (20:55:34Z) followed by `MyExodusPushRegistrar: push installation registered` at 23:55:38 — the wake runs the sync pass. High-priority wake accepted 20:54:47Z, `wake for dozetest-high` at 23:54:46.421 local (~1 s) while still `IDLE`. A 2-second-TTL message accepted 20:56:15Z produced no receipt after exit (checked through 20:56:49Z). Two wakes sharing one collapse key produced exactly one receipt, `wake for dozetest-collapse-2` at 23:57:12.876 local. Device restored to `ACTIVE`; no other device state changed.
- **Deployment:** `relayMailboxMessage` update 2026-10-21T19:15:13Z; `dmsDeadlineWorker` 20:13:03Z and `retryDispatchSweep` 20:13:02Z, all `ACTIVE` in `us-central1`. Production worker passes at 20:40:01Z, 20:45:01Z, and 20:50:02Z log the Step 9 fields (`lateDue=0 lateReminded=0 lateDeferred=0 lateStale=0`) with `failed=0`; no post-deploy errors.
- **Suites:** functions unit `npm test` **145/145**; DMS emulator `npm run test:dms` **33/33**; full JVM `:app:testDebugUnitTest` **220 classes / 1,969 tests / 0 failures / 0 errors / 0 skipped**.

### Interpretations recorded (ADR #203)

1. **Everything that wakes the app uses one versioned schema; visible notices additionally own their copy.** §8.10's generic schema governs the data half; the notice copy is fixed server-side because it must be interpretable without a network call, and it carries no identity by construction.
2. **Normal priority is the default because acceptance is invisible.** A wake proves nothing to the user; spending Doze's high-priority privilege on it would degrade the notices that do. The live leg is the factual demonstration of the difference.
3. **`messageId` stays optional and must be shape-checked.** It is an opaque relay id, not event content, and letting an arbitrary string ride into work input data would be a new untrusted-input path; a malformed id is dropped rather than repaired.
4. **A token that cannot be sent to is a token fact, not an install fact.** The tombstone keeps the row so registration can heal it; deleting the row would erase the app's own registration state on a provider hiccup.
5. **Acceptance is a provider word, so the code uses the provider's word.** Nothing renames `providerAccepted` to delivery anywhere; display truth stays with the user-triggered test alert and the OS notification state.
6. **The channel id is the migration mechanism.** An app-lowered channel cannot be raised in place (ADR #49), so the renamed `_v1` id is the only way existing installs get HIGH; the legacy id is deleted so settings never shows two partner-activity channels.
7. **The LATE reminder bypasses the outbox deliberately.** It has one recipient, no relationship fan-out, and an at-most-once contract; the CAS claim is committed before the send, so a crash loses one reminder rather than risking a duplicate. Retrying a best-effort nudge would violate its own contract.

### Honest limits

- FCM deferral, expiry, and collapse are provider-owned behaviors. The live legs prove today's behavior on this device/provider with production-shaped payloads; they are not a platform guarantee.
- The live test messages were sent directly through the FCM v1 API, not through the relay's production send call (that path is covered by the payload suite and the emulator dispatch tests). The acceptance metric distinction is proven in code and tests, not by comparing a production send against a device display.
- Notification-disabled and channel-blocked **display** states were not re-induced on this device in Step 9; Step 6's device leg proved the banner/fix paths on the old channel id, and the new id's creation, precedence, and test-alert routing are Robolectric-pinned. `dumpsys notification` proves the channels' live importance and the legacy deletion.
- No non-GMS hardware exists here; `PROVIDER_UNSUPPORTED` is unit-proven and was AVD-proven in Step 6. A real two-device partner notification remains Step 11's matrix (OPD #37).

## Appendix S10 — Step 10 consent revocation + relationship exit (executed 2026-10-22)

### Required work → implementation → evidence

| Step 10 requirement | Implementation | Evidence |
|---|---|---|
| Atomically update local authorization on revocation | `initiate` journals the request first, creates it while keys live, then `shredPair`: cancel PENDING rows → destroy the pair key (device key only with the last enrollment) → `wipePair` (enrollment first, then chain/head/outbox/approval rows in one transaction) | `ChainStateStoreTest.wipePair_deletesTheEnrollmentFirst…`; `BilateralDeletionTest.revocation_cancelsTheQueuedEnvelopes…`, `deviceLevelKeyDiesOnlyWithTheLastEnrollment` |
| Remove unsent recipient envelopes | Every PENDING outbox row of the pair → `CANCELLED_BY_REVOCATION`; the pair's rows then fall with the wipe. Rows of other pairs untouched | `cancelUnsentEnvelopes_cancelsEveryPendingRowOfThePair_andOnlyThatPair`; `revocation_cancelsTheQueuedEnvelopes_theRequestIsTheOnlyMailboxWrite` |
| Send the authorized relationship-revocation request if online | The request is created before the shred and delivered from the journal after it; offline attempts stay undelivered and are retried from the same bytes each pass | `undeliveredRequest_journalBytesSurviveForLaterRedelivery`; `offlineRevocation_isRetriedEveryPass…` |
| Reject future fetch/upload for that relationship | Upload gate `stillAuthorized` = live enrollment AND row PENDING, rechecked per row inside the drainer; `FirestoreMailboxReceiver` returns `isNotEnrolled` when the enrollment is gone; `EventCreator.create` and `ChainStateStore.appendLocal` refuse without an enrollment (the append check runs in-transaction) | `drain_revocationBetweenBatchReadAndWrite_skipsTheCancelledRow_sendsTheRest`; `drain_authorizationRecheckFailure_neverSends`; `appendLocal_withoutAnEnrollment_isNotEnrolled_andWritesNothing`, `appendLocal_afterWipePair_isNotEnrolled_theRevocationWinsTheRace`; `futureEvents_afterRevocation_areRefusedAtCreationAndAtTheStore` |
| Do not reroute queued events | Cancellation is terminal for the row; nothing is re-encrypted for, or reassigned to, another recipient (no fallback path exists in the code) | `revocation_cancelsTheQueuedEnvelopes…` (exactly one mailbox write, the request); `stillAuthorized_falseOnceTheRowIsCancelledOrTheEnrollmentIsGone` |
| Ensure DMS does not keep notifying the revoked partner | The server exit flips `/pairings/{pairId}.status = terminated`; mailbox create rules require `status == 'active'`; `dms-logic` treats terminated as no active partner; the local availability consent converges to off through the pending marker | Rules source (lines 26–33, 64); `relay-logic.js`; `dms-logic.js`; `PartnerSyncCoordinatorTest.availabilityConsent_*`; `HabitRepositoryTest.setPairProfileSharing_availabilityTransition…` |
| Offline: local effect immediately, server exit retried, UI shows pending until acknowledged | Local teardown is synchronous regardless of network. `revoked_at` (server exit) is separate from `confirmed_at` (partner ack); inside the 7-day window an unacknowledged exit is `AWAITING_REVOCATION` and non-dismissible even if the partner acked; terminate retried every pass, throw-safe, `revoked_at` written once | `offlineRevocation_…`, `terminateThrows_…`, `partnerReachable_confirmsOnAck_terminalPhase`, `partnerUnreachable_sevenDayTimeout…`, `lateAckAfterTimeout_upgradesToTerminal`; migrations 13→14 / 14→13 |

### Validation evidence (Step 10 list)

| Required validation | Evidence | Result |
|---|---|---|
| Revoke during event creation | Creation refuses (`CreateResult.Failed` because the enrollment is gone) and the store-level append refuses in-transaction (`AppendResult.NotEnrolled`) even for a race that slipped past creation; no chain/outbox row is written | green |
| Revoke after encryption / before upload | Queued rows are cancelled, not sent; a drain that read the batch before the revocation rechecks per row and skips cancelled rows without recording an attempt; exact journaled bytes remain the only retry source | green |
| Revoke during upload | The upload gate reads enrollment + row status inside the write path; a cancelled row or an absent enrollment fails the gate before the Firestore write | green |
| Revoke after server storage / during partner fetch | Stored ciphertext is not deleted early (AD-18b); the deletion request tells the partner device to shred, and the partner's receiver refuses to decrypt once its enrollment is gone; the server relationship flips `terminated`, after which rules deny every further mailbox create for the pair | green (unit + source-pinned); live two-device leg is Step 11 |
| Revoke while offline | Local teardown completes; the request stays journaled and is retried; the UI reads `AWAITING_REVOCATION` (pending, non-dismissible) until the server exit is acknowledged; the terminate exception path does not escape the pass; the 7-day timeout reports `LOCAL_ONLY_TERMINATED`, never TERMINAL | green |
| No new event reaches the revoked partner through any alternate path | Only the journaled deletion request is delivered after revocation; replay and append refuse without enrollment; the drainer's per-row authorization check closes the batch-read race; no fallback/reroute code path exists | green |

### Device evidence (X6532, 2026-10-22)

- `install -r` only (never uninstall). The accountability DB was at `user_version` 13 before the install; after launch the on-device file reads **14**, and `unpair_state` has the `revoked_at` column — the real v13→v14 migration ran on hardware.
- The pre-existing journal row survived (`delivered = 1`, `revoked_at` NULL) — an old exit is not retroactively claimed by the new column.
- `MainActivity` launched, the process stayed alive, the VPN/sync engine logged normal domain-usage lines, and logcat showed no fatal exception. The rules store (v19→v20) opened without error; a failed migration would have aborted startup.
- **Blocked, stated honestly:** the device sat on its lock screen for this session, so the Partner unpair card was not driven or photographed on-device in Step 10; and no live two-identity exit was possible on one phone. Both legs belong to Step 11 / OPD #37.

### Interpretations recorded (ADR #205)

1. **The partner's ack and the server's exit are different facts.** `TERMINAL` requires both; an unacknowledged server exit outranks a partner ack inside the window, because the pairing document may still allow writes.
2. **Cancellation is a state, not a delete-then-forget.** Marking the row keeps the "authorization ended before delivery" evidence and makes every late batch read fail closed.
3. **The append check belongs inside the transaction.** A creation-time check alone leaves a race; the enrollment row is the single authority and the write path re-reads it.
4. **Retry the terminate every pass.** The 7-day window is short and the server flip is one idempotent write; a backoff schedule would burn passes without reducing load.
5. **The pending marker exists because a switch is not convergence.** The local switch is immediate; the server value is a separate fact, cleared only by the CAS on the acknowledged value.
6. **The migration registries are the only lists.** Hand-written per-test migration lists are the drift class that already caused one failure in this step; pointing every builder at `ALL_MIGRATIONS` makes the next schema bump fail loudly in the coverage test instead of silently.

### Honest limits

- The server-side half (rules, relay, DMS) is source-verified and was previously deployed; Step 10 added no backend change and performed no live terminate against production from this session.
- Stored ciphertext for a revoked relationship is not deleted at revocation; the recipient key shred makes it unreadable and AD-18b's 30-day cap removes it. This is the recorded limit (ADR #205 and §8.4).
- The UI card's `AWAITING_REVOCATION` state is JVM-pinned (phase logic + string mapping) and its earlier states were device-proven in ADR #127; the Step 10 card itself was not re-photographed on-device because the phone was locked.
- Full JVM: **220 classes / 1,985 tests / 0 failures / 0 errors / 0 skipped**.

## Appendix S11 — Step 11 cross-OEM compatibility + release checks (executed 2026-10-22)

Governing report: `app/docs/partner-accountability-compatibility-report-2026-10-22.md` (device facts, per-leg evidence, reproduction commands, vendor labels, security/policy checks). ADR #206 records the decisions. No manufacturer-specific code was added — no reproducible incompatibility was demonstrated, so the plan's precondition was not met.

### Required work → implementation → evidence

| Step 11 requirement | Implementation | Evidence |
|---|---|---|
| Run the §12 matrix on physical devices | Full single-device-verifiable matrix on the Infinix X6532 (XOS/Android 14/API 34, build `X6532-V632AABCDEFGHIJKLMNO-UGo-OP-260710V3278`, patch 2026-08-01, GMS 26.39.33, Wi-Fi + LTE, full-tunnel `tun0`, owner uid 10414) plus the sanctioned API-37 AVD for the visual/no-GMS legs; every leg restored its device state | Report leg table, 18 executed rows + 4 blocked |
| Record manufacturer, model, OS/build, GMS/HMS, permissions, network, reproduction steps | Device fact tables + inline commands (`cmd jobscheduler run -f -n androidx.work.systemjobscheduler …`, `dumpsys deviceidle force-idle|unforce`, `cmd power set-mode 1|0` + `dumpsys battery unplug|reset`, `pm revoke|grant … POST_NOTIFICATIONS`) | Report “Devices” and “Reproduction commands” |
| Label each vendor/OS family honestly | Transsion/XOS `PARTIALLY TESTED` (one model, one OS version); GMS-free `PARTIALLY TESTED (emulator only)`; Pixel, Samsung, Xiaomi/Redmi/POCO, OPPO/realme, OnePlus, Vivo/iQOO, Motorola `UNVERIFIED`; API 26 and a physical API 36 device `UNVERIFIED` | Report vendor matrix table |
| Do not add manufacturer-specific code without a reproducible incompatibility | No OEM branch, keyed table or workaround added; the two observed deviations (App Standby clamp, Doze-without-exemption freeze) are documented platform semantics, not defects | ADR #206 decisions 2–5; report legs |
| Doze | With exemption: deep IDLE, process/FGS alive, server beats 36 (22:10:20Z) and 37 (22:15:49Z) accepted, `next_deadline_at` +6 h. Without exemption: killed process not restarted inside idle (30 s window), recovery after `unforce` + allowlist → pid 9262, FGS foreground, full sync pass. No self-restart is claimed; DMS deadlines are the backstop | Report Doze rows |
| App Standby (API 28+) | Baseline `EXEMPTED`; with allowlist removed, `am set-standby-bucket rare|restricted` read back 10 (ACTIVE) while running and force-stopped, no `STANDBY_BUCKET_CHANGED`; AOSP semantics (FGS ⇒ ACTIVE; debug override clamped). Behavioral RARE/RESTRICTED rendering is JVM-pinned, stated as a limit | Report App Standby row; JVM `PartnerHeartbeatClient` standby tests |
| Battery saver | `dumpsys battery unplug` + `cmd power set-mode 1` → `mSettingBatterySaverEnabled=true`; FGS stayed foreground; restored to mode 0 + `dumpsys battery reset` | Report Battery saver row |
| Network loss | Not re-run on the X6532 in Step 11 (Wi-Fi is the only adb channel): Step 7's device-proven offline leg (ADR #200: counter preserved, next online beat accepted) stands; the Doze-without-exemption leg exercises the frozen-process/no-network path | Report Network loss row; ADR #200 |
| Device reboot / reboot-before-first-unlock | BLOCKED — no USB on the machine; wireless adb does not survive reboot and the device owner was unavailable to re-enable it. Not claimed | Report blocked rows |
| App update | In-place `install -r` over a running process twice (pid 32261 → 3770 → 17836); `MY_PACKAGE_REPLACED` both times; FGS `isForeground=true`; DB `user_version` 14 before/after; no FATAL | Report App update row |
| Permission revocation | `pm revoke android.permission.POST_NOTIFICATIONS` → `granted=false`, AppOps `ignore`; process restarted cleanly (5083 → 5810), no FATAL; `pm grant` restored. AVD additionally captured the user-facing “Notifications are off” banner + Allow action | Report Permission revocation / Notifications-disabled rows |
| Process death / explicit force-stop | SIGKILL of the app process → pid 3770 → 4276 within 12 s (START_STICKY + ensure-FGS on process start, ADR #152), FGS foreground, DB v14. `am force-stop` → intended stopped state (no pid, 0 services); relaunch → pid 5013, FGS foreground. No self-restart claim after force-stop | Report rows |
| User profile changes | BLOCKED — one profile on the device; creating/switching requires owner interaction | Report blocked row |
| VPN conflict | `tun0` active (owner uid 10414) while beats 36/37 were accepted and a sync pass ran; no tunnel-attributable failures | Report VPN row |
| No GMS | AVD: `pm disable-user com.google.android.gms` → app launched (pid 9734, no FATAL), Partner tab renders “Push alerts aren’t supported here” + the still-arrives copy; GMS re-enabled afterwards | Report No-GMS row |
| Notifications disabled | AVD: Partner tab renders “Notifications are off” / “MyExodus can’t tell you when new partner activity arrives…” + “Allow notifications” | Report row |
| Soak | Server counter 34 → 35 (refused, resynced) → 36 → 37 → 39, `last_received_at` 22:33:51Z, `next_deadline_at` 04:33:51Z (exactly +6 h), `late_deadline_at` 23:18:51Z, `liveness_generation` 30, `notice_state` NONE; no FATAL across all legs | Report Soak row; Firestore `dms_install_state/ALOAaMXMCmWfuGc2JXWw7tEfop92_cdhfw9AARkGUzw9EvoZRxA` |

### Security and policy checks

| Check | Result | Evidence |
|---|---|---|
| No code path auto-clicks/navigates/blocks system settings to prevent revocation/uninstall | PASS — the accessibility service acts only on user-configured blocked packages, excludes its own package, and uses `GLOBAL_ACTION_HOME` only to close a blocked app; no Settings automation; Settings deep links are user-initiated fix routing | Source review; report table |
| Review manifest and sensitive-API declarations | PASS — `AndroidManifest.xml` unchanged across PA Steps 1–10 (`git log e670155..HEAD -- app/src/main/AndroidManifest.xml` empty) | Git + manifest read |
| No new Accessibility / Notification Listener / exact-alarm / full-screen-intent / FGS permission | PASS — no `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `NotificationListenerService` or `setExact*`; `FOREGROUND_SERVICE_SPECIAL_USE` predates the feature (enforcement FGS, 2026-08-27) and the heartbeat only piggybacks it; `PartnerMessagingService` is `exported=false` | Manifest read; ADR #200 |
| Static analysis | PASS — opengrep 7 custom rules / 361 tracked files / 0 findings after rewording one diagnostic label that named “dek”/“key” while logging only elapsed ms (message-text false positive; rule kept as signal) | `/tmp/opengrep_full.txt` re-run |
| Secret scanning | PASS — gitleaks history scan 78 commits, no leaks; working-tree hits are 3 gitignored cache artifacts (`.cache/web-search-pro/*.json`, `scripts/.cache/du-test-server.pem`) | gitleaks output; `git check-ignore -v` |
| Dependency scan | PASS for the shipped APK — Trivy HIGH/CRITICAL findings are `androidLintTool`/UTP tooling classpaths (bcprov 1.79, netty UTP); the app ships bcprov 1.85.2, above every listed fix version (CVE-2025-14813 fixes 1.80.2/1.81.1/1.84), no runtime netty | Trivy output; dependency tree |
| Backend authorization tests | PASS — functions `npm test` 145/145; Firestore rules emulator suite 18/18, exit 0 | Test outputs |
| Focused security review of the event/consent flow | PASS — mailbox create requires the pair’s other member and `status == 'active'`; closed CBOR allowlist (§8.10); E2EE payloads; relay stores ciphertext only; per-row authorization recheck in the drainer (ADR #205); DMS dispatch re-joins `pairings/{pairId}` and requires both consents; `dms_install_state` deny-all; App Check enforced on all partner callables; the deletion request is the only post-revocation mailbox write | ADR #204/#205 review; rules source |

### Release-readiness assessment

Ready for the direct-APK release channel behind the existing rollout gate. No OEM-specific incompatibility was demonstrated, so no OEM-specific code was added. Remaining unknowns are hardware coverage (all non-Transsion families), reboot legs, profile changes and two-phone end-to-end — all blocked on device availability, not on observed defects. Step 12 was executed the same day (Appendix S12, ADR #207): the gate and live rollback are done; cohort/expand rollout stages remain owner-gated and are blocked on the release channel.

### Honest limits

- One physical device only; the §12 tiering refuses to convert that into a general claim. All other tier-1 families and the minimum supported API 26 are `UNVERIFIED`.
- Reboot, reboot-before-first-unlock and user-profile legs were not run (hardware/owner constraints), and are recorded BLOCKED, not passed.
- App Standby Rare/Restricted rendering is JVM-pinned, not device-proven, because the platform clamped the debug override.
- The network-loss leg was not re-run in Step 11 (cutting Wi-Fi cuts the only adb channel); Step 7's device-proven offline evidence stands.
- Full JVM on the final source: **220 classes / 1,985 tests / 0 failures / 0 errors / 0 skipped**. Final build installed in place on the X6532 (pid 17836, `MY_PACKAGE_REPLACED`, FGS foreground, DB `user_version` 14, `EXEMPTED`, zero FATAL).

## Appendix S12 — Step 12 staged rollout + operational verification (executed 2026-10-22)

ADR #207 records the decisions; the operator recipe lives in `app/docs/MyExodus Monitoring.md` §7. The gate reuses the project's existing server kill switch (`config/features`, the same document the SOS switch lives on) — no new remote-config dependency, no new collection. Remote Config is being retired (ADR #171) and was not used.

### Required work → implementation → evidence

| Step 12 requirement | Implementation | Evidence |
|---|---|---|
| Gate event transport behind the existing feature-flag mechanism | `config/features.partnerAccountabilityEnabled` (absent doc/field = enabled; only explicit `false` off). Client transport: the mailbox create rule denies new envelope writes, so the `onDocumentCreated` relay never triggers; the author-visible marker for a relay rejection stays unchanged. In-flight transport: `retryDispatchSweep` returns before listing retryable records, so messages created before the flip are not pushed while the feature is off | Rules test `operator kill switch: an explicit false stops new sends…` (rules suite 20/20); sweep-gate tests (functions 147/147); live sweep proof below |
| Gate DMS notification behavior | `dmsDeadlineWorker` returns `{ disabled: 'PARTNER_ACCOUNTABILITY' }` before any deadline transition or dispatch; no state mutation, no outbox row, no push | DMS suite 34/34 (`worker: the partner-accountability switch stops transitions and dispatch without touching state`); live worker proof below |
| Do not add a new remote-config dependency solely for this feature | No RC parameter added (RC untouched and retiring, ADR #171); no new dependency, secret or service; one boolean field on the existing operator document | `firestore.rules`, `functions/dms-processor.js`, `functions/sweep.js`, `functions/dispatch-logic.js` diffs |
| Roll out to internal devices | Internal stage = the X6532 running the current build (Steps 1–11 legs, compatibility report). The switch is server-side, so it governs every installed build, including any that predates it | Compatibility report; ADR #207 decision 6 |
| Roll out to a small opted-in cohort, then expand | **BLOCKED** — distribution is a direct APK with no git remote, and the GitHub Releases repository required by ADR #171 does not exist; there is no channel to enroll a cohort on, and the owner is the only installer. Not simulated, not claimed | ADR #207 decision 6; ADR #171 |
| Expand only after measured release criteria are met | Criteria defined with measurement sources and internal-device values (table below). Cohort values do not exist, so expansion is not evaluated | Table; this appendix |
| Demonstrate rollback | Two live windows on the deployed backend: (1) flag `false` after the 22:50:02Z DMS pass → the **22:55:00.946Z** pass logged `dms worker disabled: partner accountability switched off`; flag restored `true` → the **23:00:02.075Z** pass logged `dms worker pass`. (2) flag `false` at **23:05:22Z** → the **23:06:05.013Z** sweep logged `dispatch sweep disabled: partner accountability switched off`; restored `true` immediately after (read-back `true`) | Cloud Logging entries for `dmsdeadlineworker` / `retrydispatchsweep`; `config/features` read-backs |
| Confirm disabling stops new partner sends without deleting unrelated user data | No enforcement point deletes or rewrites anything. Live checks before, during and after the off window: pairing `SvBQFOk…_zmHLexVW…` status `active`; recipient message `5118f2de-bda6-48d9-a7df-d5ec244af869` present with payload length 308 | Firestore REST reads; ADR #207 |
| Verify rollback in staging (§13) | There is no separate staging project; the demonstration ran against the deployed backend itself — a stronger environment than staging for this check. No user-visible client is affected because the app on the device was asleep and no UI send was attempted | ADR #207 verified section |

### Measured release criteria (expansion gate)

| Criterion | Measurement source | Internal-device value (2026-10-22) | Cohort gate |
|---|---|---|---|
| Duplicate partner-visible events | Partner-side dedupe outcomes (`ChainStateStore` `Duplicate` is suppressed) + create-only message id + emulator suites | 0 observed; one message relayed and none duplicated (JVM/emulator pin the replay denial) | 0 user-visible duplicates |
| Lost events | Sequence-gap detection (`GAP_MARKER`, `POSSIBLE_MISSING_UPDATES`) and ack coverage | 0 `dispatch failed` in the 24 h ending 23:00Z; 3 `mailbox rejected` entries, all `RATE_LIMITED` on the non-live test pair `ALOAaMX…_EShLSCjo…` at 19:58Z — the designed author-visible classifier, not loss | 0 unexplained gaps; every gap resolves via resync |
| DMS false positives | `dms_notification_outbox` rows + `notice_state` vs actual heartbeat receipt | 0 outbox rows, 0 notices, 0 ingestion outages; the live pair carries no DMS state (availability consent revoked in Step 10/11 teardown) | 0 notices for installs that checked in inside the 6 h window |
| Notification delivery | Outbox statuses + `dispatch failed` / `dispatch sweep` logs | 1 relayed message awaits its ack because the paired second device (X6532C) is not on hand; 0 dead-lettered, 0 dispatch failures | 0 dead-lettered, 0 unaccounted notices |
| Battery / network | `dumpsys batterystats` per app UID under stated conditions; heartbeat cadence intact | uid 10414: 320 mAh app-computed over the current cycle (9 h 23 m 55 s screen-on, 85.6% — mixed use with the full-tunnel VPN and enforcement FGS), FGS component 18.0 mAh, app wakelocks 78 s total. Conditions stated because the number is whole-app, not PA-only; PA adds no new wake source (the heartbeat piggybacks the existing FGS at 15 min + jitter; deadline work is server-side) | No regression beyond the same-conditions baseline; heartbeat cadence intact |
| Baseline operational counts (24 h ending 23:00Z) | Cloud Logging | 52 `dms worker pass`, 0 `dispatch failed`, 0 circuit-breaker trips | — |

### Honest limits

- The on-device send-refusal leg was not executed: the X6532 was asleep behind a secure keyguard (`isKeyguardShowing=true`, `secure=true`; swipe/`wm dismiss-keyguard` no-op) without the owner credential. The transport boundary is proven by the rules emulator against the exact deployed source and by the deployed ruleset content re-read from the Rules API (`3adf7753-d6d5-43a4-bfe6-e030c15c03ab`, gate at line 77); the client's denial path leaves outbox rows pending with ≤6 h capped backoff (code-verified, `MailboxOutboxDrainer`/`OutboxBackoff`). Run the device leg when the device is unlocked.
- Cohort/expand stages are blocked on the release channel, not on a defect; no cohort measurement exists.
- Already-dispatched provider pushes cannot be recalled; the sweep gate prevents new pushes, not accepted ones.
- After a long off window, queued client rows drain within their ≤6 h backoff cap of re-enable, and overdue DMS deadlines are processed on the next tick — truthful, not immediate.
- The switch does not affect the SOS path or any other feature (separate `partnerSosEnabled` field).
- The gate does not replace monitoring: the fleet late-burst breaker and its alert (ADR #201) still page the operator while the feature is on.

## Appendix S13 — Definition of Done reconciliation (plan §18, against S1–S12 evidence, 2026-10-22)

Step 12 was the last numbered step in §7, so this appendix closes the plan by walking §18 line by line. Verdicts are **PASS**, **PASS (as scoped)** where a literal reading does not apply to this project, or **OPEN** where only external conditions can close it — never inferred from a green suite alone. Each row names the evidence already recorded above.

### Gap found at this gate, and closed

§18 requires: *"User-facing copy states that device silence is not proof of uninstall, tamper, or violation; explains delayed notifications and offline-revocation limits; and does not promise recall of information already decrypted by a recipient."*

Delayed notifications and offline revocation were already explained (`partner_connection_body_late`, `partner_sync_delayed`, `partner_unpair_revocation_pending`), and no string promises recall (grepped). **The positive statement that silence is not proof did not exist anywhere** — the copy was neutral but silent on the interpretation a partner is most likely to form. Fixed on both surfaces:

- **In-app:** new `partner_connection_silence_note` — "Not checking in isn't proof the app was removed, blocking was turned off, or anything was tampered with." Rendered in `PartnerConnectionCard` whenever consent is on and the state is anything but `CHECKING_IN` (`PartnerScreen.kt`).
- **Notice:** `PUSH_BODY_SILENT` now reads "A paired device has not checked in recently. This is not proof the app was removed or blocking was turned off." The notice is still identity-free and whole-content — nothing requires a fetch to interpret. `dmsDeadlineWorker` redeployed this session.
- **Evidence:** functions `npm test` **147/147** and DMS emulator **34/34** (both now pin the new notice text); full JVM suite **220 classes / 1,985 tests / 0 failures / 0 errors / 0 skipped**. Honest limit: the project has no Compose/Robolectric UI-test harness, and the X6532 keyguard stayed secure, so the card's visual render was not photographed; the condition is a static guard and the string resource is compile-verified.

### §18 checklist verdicts

| # | Requirement (abridged) | Verdict | Evidence |
|---|---|---|---|
| 1 | Existing pairing, crypto, detectors, relay, persistence, monitoring reused without unrelated redesign | PASS | S1 change map; S5 gate ("existing pipeline already meets the contract"); ADR #198 |
| 2 | Step 2 crypto gate states stateless/stateful, proves confidentiality/authenticity + crash-safe retry; gaps as blockers; no custom crypto | PASS | S2 classification + guarantee evidence; S3/S4 reuse; no crypto dependency added |
| 3 | Allowlisted schemas; `relationship_seq`, gaps, resync, `POSSIBLE_MISSING_UPDATES` | PASS (label differs) | S3 allowlist; S6 gap → `REPAIR_PENDING` → resync → `GAP_MARKER`; the user-visible state ships as `PartnerChainStatus.INCOMPLETE` ("Some history missing"), which is the plan's `POSSIBLE_MISSING_UPDATES` under clearer copy; `ChainStateStoreTest`, `ChainRepairCoordinatorTest`, `PartnerSyncScenariosInstrumentedTest` |
| 4 | Local writes transactional, SQLCipher, bounded, idempotent; committed envelope reused on ambiguous outcome | PASS | S4 (v13 schema, unique `event_id` per pair+sender, outbox rollout, idempotency tests) |
| 5 | Local ciphertext removed after acceptance; server ciphertext removed by ack/expiry/revocation; retention documented | PASS | S4 drain; S5 records the deliberate ≤30-day local repair copy (declared, not accidental); `functions/cleanup.js` `cleanupMailboxes`; S10 revocation |
| 6 | Upload/fetch/ack/revoke contracts mapped to existing routes and tested | PASS | S5 requirement→code map; rules suite 20/20 (cross-partner isolation, replay, oversize, rate limit, revocation) |
| 7 | DMS durable delayed queue/indexed bounded worker; no unbounded scan, no Redis-expiry trigger | PASS | S8 DB-as-queue design; bounded due query; 100k soak |
| 8 | DMS generation/CAS, transactional outbox, outage compensation, breaker, suppression, six-hour threshold tested | PASS | S8/S9 emulator topology + late-burst breaker; reminder cap per `(install, relationship, outage_generation)` |
| 9 | Connection ≠ monitoring snapshot ≠ inbox completeness; stale → `UNKNOWN`; heartbeat alone never "healthy" | PASS | S7 (ACTIVE needs FGS + <90 s authority stamp; stale → UNKNOWN); separate connection and chain cards |
| 10 | Notification permission/channel/provider checks, user-triggered test, in-app warning, generic payload, one-reminder cap | PASS (revised 2026-11-05) | S6 `PartnerAlertHealth` + test alert; S9 channels/payload; S8 cap. The user-triggered test was removed in the closeout; the permission/channel/provider checks and in-app warning remain |
| 11 | WorkManager best effort; FGS hook only while legitimately active; no new FGS/exact alarm/keep-alive/anti-uninstall/sensitive permission | PASS | S7 piggyback design; S11 policy table: manifest unchanged across PA commits |
| 12 | App Standby diagnostics API 28+, API 26–27 degrade cleanly, guidance does not promise unrestricted operation | PASS | `PartnerHeartbeatClient` gates on `SDK_INT >= P` (API 28); no exemption request; S11 App Standby finding recorded |
| 13 | Backup/key-loss/clear-data/device replacement/re-enrollment and install-counter namespace tested | PARTIAL | Namespace and migration JVM/emulator-proven (S7); re-enrollment/identity rotation JVM-proven S4/S10 + `IdentityMigrationTest`; **physical reinstall/clear-data blocked** by the owner's never-uninstall rule |
| 14 | Tiered cross-OEM report attached, tested vs unverified distinguished | PASS | `partner-accountability-compatibility-report-2026-10-22.md`; S11 labels (Transsion PARTIALLY TESTED; all other tier-1 families UNVERIFIED) |
| 15 | 100k-install load, scheduler/ingestion outage, correlated outage, queue recovery, notification tests, performance/battery soak | PASS | S8 100k soak (p95 11.2 s), outage + breaker tests, lease requeue; S11 soak; S12 battery baseline with stated conditions |
| 16 | Play and APK flavors reviewed for target SDK, permissions, FGS purpose, disclosures, kill switch | PASS (as scoped) | No Play flavor and no product flavors exist — ADR #15 makes Play distribution out of scope; APK half verified in S11 + S12 |
| 17 | Monitoring, operator alerting, recovery runbooks, staged rollout, migration rollback, push-outbox recovery | PASS | S8 alert policy + release procedure; S12 live staged rollback; S4 `13↔12` / `18↔17` down migrations tested; S8 lease requeue |
| 18 | All acceptance criteria pass; no Critical/High blocker open | **OPEN (one AC)** | Every software-verifiable AC in §17 passes and no Critical/High blocker is open; the tiered physical-device-matrix AC cannot pass on one available phone and is reported with honest labels rather than claimed |
| 19 | User-facing copy: silence is not proof; delayed notifications; offline revocation; no recall promise | PASS (after this gate's fix) | Copy added + notice redeployed; delayed/offline copy already present; recall never promised |

### What remains open, and what closes each

- **§17 tiered physical-device matrix AC / §18 row 18.** Closes only with hardware: Pixel, Samsung, Xiaomi/Redmi/POCO, OPPO/realme, OnePlus, Vivo/iQOO, Motorola, TECNO/Infinix/itel, plus a physical API 26 and API 36 device. Labels are already in the S11 report; no code change is pending.
- **Two-device legs** (partner receives/resolves on a second phone, live unpair, snapshot receive half, OPD #37 naming cases). Closes with a second physical device or after the authentication work.
- **Reboot / reboot-before-first-unlock.** Blocked by wireless-only adb (dies at reboot); closes with USB debugging attached.
- **Cohort → expand rollout stages (§13, Step 12).** Owner-gated and blocked on the release channel (GitHub Releases, ADR #171).
- **On-device legs blocked by the secure keyguard this session:** client send-refusal (Step 12) and the new card copy's visual render. Closes on the next unlocked device window; the logic is pinned by suites, not claimed as device-proven.

**Conclusion:** all buildable work in the plan is complete. The remaining open lines are external conditions, each named above with its closing condition — none is a code TODO.
