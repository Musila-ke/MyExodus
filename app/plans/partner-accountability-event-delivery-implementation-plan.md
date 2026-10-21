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
