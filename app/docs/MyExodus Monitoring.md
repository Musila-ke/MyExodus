# MyExodus Monitoring — Partner Pillar §14 Realization

*Plan section 14 (partner-pillar-implementation-plan-v2.md lines 568-580) lists eleven monitoring
requirements; §17 line 639 adds the budget alert + `maxInstances` acceptance item; §18 line 649
requires the dashboards to be live with one day of staging data. This document records, per
requirement, exactly what exists in code, what exists as server log rails, and what is console
configuration that still needs doing — with its blocker named.*

Status as of 2026-09-19: client + server instrumentation COMPLETE and test-verified
(664/0/0 JVM, 30/30 functions). **Console configuration DONE (task #34, 2026-09-19)** — created
via the Google APIs using the firebase-tools OAuth token (no console clicks needed once the
OQ-5 figure — $5/month — was supplied by the owner): 1 email notification channel, 3 alert
policies, 3 log-based metrics, 1 dashboard, 1 budget alert; every artifact listed and verified
in §4 (all enabled, all wired to the channel).

**2026-09-28 (task #37, ADR #101): the pairing-ceremony plan's own §14 (six bullets) is
realized too — see §5.** Added to the live project: 2 log-based metrics
(`pairing_transcript_mismatch_count`, `pairing_idempotency_replay_count`), 1 alert policy
(429-throttling, wired to the same channel), 4 dashboard tiles + a text-map update, and the
client `pairing_ceremony_outcome` rail. The dashboard tile text also had a stale figure
corrected ($5 → $2 budget).

---

## 1. Client instrumentation (code, done)

All client events ride the existing `telemetry/CrashReporting` rail (one `NonFatalEvent`
class, so every §14 event groups under one crash-console issue), metadata-only per
Decision 12, opt-out honored, best-effort (`runCatching`). Reporting happens **once per
periodic sync pass** (`PartnerSyncWorker`, 15-min cadence) — the dashboard's manual
"Check now" does *not* report, so a user's taps cannot inflate counts.

| §14 metric | Event | Fired when | Where |
|---|---|---|---|
| `CHAIN_INCOMPLETE` count | `chain_incomplete` | a chain reaches the neutral terminal (gap range unrecoverable) | `ChainStateStore.markChainIncomplete` via `onSecuritySignal` seam |
| `TAMPER_SUSPECTED` count | `chain_tamper_suspected` | repair-active verification failure (either escalation path: `flagFork` hash mismatch, `flagTamperIfRepairing` signature failure); emitted once per event, never for ordinary noise | same seam |
| Outbox age distribution | `partner_outbox_pending age=<bucket>` | end of a periodic pass **only if** a row is still PENDING; buckets `under_15m` / `under_1h` / `under_24h` / `over_24h` | `PartnerSyncTelemetry.afterPass` + `OutboxDao.oldestPendingCreatedAt()` |
| Notification-permission-denied rate among active pairs | `partner_notifications_denied` | enrolled pass where `NotificationAccess.canPost` is false | same class |
| Current phase flag (reliable-delivery §14) | `partner_delivery_phase rule_producer=staged` | every enrolled periodic pass; a constant since #177 retired the Remote Config gate — a `legacy` sample identifies a build older than the removal | `PartnerSyncTelemetry.afterPass`, wired in `AccountabilityModule` |
| Oldest `WAITING_FOR_RECIPIENT_KEY` (reliable-delivery §14) | `partner_waiting_recipient_key age=<bucket>` | enrolled pass **only if** a row is waiting for a recipient key; same bucket edges as the outbox age | same class + `ServiceEventOutboxDao.oldestWaitingCreatedAt()` |
| Recipient-key stale re-encryption count (reliable-delivery §14) | `partner_recipient_key_regenerated` | one event per successful `Regenerated` outcome, both the stale-response branch and the waiting re-probe | `PartnerEventDrain.onRecipientKeyRegenerated` seam, wired in `PartnerDeliveryModule` |
| T1 latency (§11) | `partner_t1_ms bucket=<bucket>` | per successfully written outbox message: attempt → Firestore confirmed; buckets `under_1s` / `under_3s` / `over_3s` (the §11 targets as bucket edges) | `MailboxOutboxDrainer.onSendLatency` seam, wired in `AccountabilityModule` |
| Data-wipe-detection trigger count (Step 17) | `local_wipe_detected` | pre-existing (Step 17) | `MyExodusApp.onCreate` |
| Firestore rules denials (account plan §13.1 item 6; ADR #232) | `firestore_denied cause=<op>` | a direct client write is rejected with PERMISSION_DENIED. Ops: `nudge_settings`, `nudge_ack`, `push_installation`, `push_installation_delete`, `mailbox_ack`, `unpair_ack`. Transport failures are not reported (not denials). | `accountability/sync/FirestoreTasks.awaitReportingDenial` |

Denominator note for the denied rate: only enrolled devices report, so the console-side
approximation is `partner_notifications_denied` event count over the active `pairings`
collection count.

### 1a. Feature-outcome rail (task #7, owner direction 2026-11-06)

A remote tester's "X doesn't work" report needs the flow outcome, not a crash. Three
non-crash flow terminals emit through `CrashReporting.featureOutcome(event, outcome,
reason)` — same one-issue-class rail, same Decision-12 metadata-only contract, same
Settings opt-out. Message shape: `event cause=<outcome> [reason=<code>] oem=<OEM>
api=<API>`. The OEM/API pair is the same device-info contract `protection_state` already
carries; the Crashlytics report itself adds model, OS version and app version.

| Flow | Event | Terminals (outcome / reason) | Where |
|---|---|---|---|
| Streak create | `streak_create` | `ok` · `rejected` (reason = `INVALID_DURATION` / `START_IN_FUTURE` / `IMPLAUSIBLE_CLOCK` / `STORE_UNAVAILABLE`) | `HabitRepository.onboard` |
| Relapse log | `relapse_log` | `ok` · `rejected` / `before_streak_start` · `ignored` / `duplicate` · `ignored` / `no_active_streak` · `failed` / `store_unavailable` | `HabitRepository.logRelapse` (covers both the app and the widget) |
| Rule create | `rule_create` | `ok` · `failed` with reason `limit` / `self_block` / the exception's simple class name | `RulesRepository.insertRule` (covers the editor and the website-preset batch) |

Honest scope limits: rule batches created through `insertBatch` are **not** instrumented
(owner named rule create; batch creation is a distinct editor flow); never sent are habit
labels, rule targets, cap messages, durations or limits — the reason codes above are the
entire payload. Both seams are defaulted constructor params, so unwired construction
(tests, harnesses) emits nothing and telemetry can never throw into a write.

## 2. Server-side rails (code, done — queryable, console charts pending)

| §14 metric | Rail | Query shape |
|---|---|---|
| Invocation count / error rate / duration | Cloud Functions built-in metrics (no code needed) | Console / Logging metrics; alert policy per §14 (see §4) |
| Dispatch outcome per installation, by provider | `dispatch-record.js` writes `DISPATCHED` / `FAILED_RETRYABLE` / `FAILED_TERMINAL` on `/relay-dispatch/{pairId}/installations/*` | Firestore collection-group count over `status`, grouped by `provider` |
| Sweep: retried per run, terminal after exhausting attempts | `sweep.js` logs `dispatch sweep {scanned, redispatched, terminal}` | Log-based counter metric on the `terminal`/`redispatched` fields |
| Rate-limit rejection count per pair | `relay.js` logs `mailbox rejected {messageId, pairId, relayStatus}` with `relayStatus=RATE_LIMITED` (Step 18; the author-read side surfaces it to the sender in-app) | Log-based counter metric keyed on `pairId` |
| T2 latency (§11: Firestore write confirmed → provider acceptance) | derivable from relay-dispatch records: mailbox `createdAt` → installation record `lastAttemptAt` at `DISPATCHED` | Firestore query; not a log metric |
| T3 latency (§11: provider acceptance → notification posted) | **not client-measurable** — would require the receiver's clock; cross-device clock skew makes it dishonest | staging/harness only (ADR #46 virtual partner) |
| T4 acknowledgment records | out of scope per plan (§14 "Optional"); ack records exist (Step 14) but are not aggregated | — |

**Send acceptance is not delivery or display (Step 9, ADR #203).** The DMS outbox field
`provider_accepted` records the provider's acceptance of a send and nothing more; no metric,
log line, or UI may read it as delivery or display. Client sync health is observed in-app
(`PartnerAlertHealth` states and sync-pass logs), and display truth is the user-triggered test
alert plus the OS notification state — the same separation T3 states above. A missing display
on the receiver never moves a server metric, and a successful send never claims one.

## 3. §17 line 639 — maxInstances (configured + deployed) and budget alert (DONE)

- `maxInstances` is set and deployed (functions deploy 2026-09-18): `registerPairing` 10
  (index.js:247), `relayMailboxMessage` 10 (relay.js:72), `retryDispatchSweep` 1 (sweep.js:46),
  `cleanupMailboxes` 1 (cleanup.js:24). AD-20a's load estimate (OQ-1) remains the bound's
  justification; the values are placeholders per AD-20a until OQ-1 is answered. (Line refs
  refreshed 2026-10-17; the earlier `dispatchSweep` name was wrong — the exported/deployed
  name has always been `retryDispatchSweep`.)
- **Budget alert: DONE 2026-09-19 (task #34).** OQ-5 answered by the owner: **$2/month**
  (owner revised from $5 the same day; budget PATCHed via the Billing Budgets API).
  Budget `MyExodus monthly budget (OQ-5: $2)` ($2.00, project-scoped to myexodus-53804)
  with thresholds at 80% ($1.60) and 100%, notifying the monitoring email channel *and* the
  billing admins (default IAM recipients kept). Pre-existing separate budget "Firebase Project
  myexodus-53804" ($1, Firebase-auto-created) left untouched.

## 4. GCP configuration (task #34 — DONE 2026-09-19, created via APIs)

Created with the firebase-tools OAuth token against the existing project (`myexodus-53804`;
Firebase IS the GCP project — no separate product). Verified by list-and-read after creation:

1. **Notification channel:** `MyExodus owner email` — `projects/myexodus-53804/notificationChannels/18347258884556077313`, email → the project owner's Google account (confirmed via `getIamPolicy` `roles/owner`), enabled. If Google sends an email-channel verification note, clicking it is the only remaining step.
2. **Alert policy — server errors:** `MyExodus: Cloud Functions server errors (HTTP 5xx, 15m)` — policy `7634493270550799513` (same policy, conditions replaced 2026-10-28, ADR #224). One condition: `resource.type="cloud_run_revision" AND metric.type="run.googleapis.com/request_count" AND metric.labels.response_code_class="5xx"`, ALIGN_SUM over 900 s, REDUCE_SUM, `COMPARISON_GT 2` (fires at ≥3 5xx in 15 m), duration 0 s, one email channel (`18347258884556077313`), severity ERROR, `autoClose 1800s`.
   - **Why the old error policy was retired (2026-10-28).** The previous policy (`… error rate > 5% (15m)`, later AND-ed with a ≥2-errors floor) counted `cloudfunctions.googleapis.com/function/execution_count` with `status="error"` — but every thrown `HttpsError` is an "error" execution, including expected client behavior: App Check 401 rejections, `RELATIONSHIP_UNAVAILABLE` 403s from a phone whose pair was removed, and 400 invalid-argument refusals. It produced **56 closed incidents** and zero real defects. Evidence from the 7-day window read on 2026-10-28: `submitHeartbeat` 119 error vs 489 ok executions, dominated by 401/403; the `readPartnerStatus` 403 entries were plain Cloud Run 4xx request logs from carrier IPs; the only `5xx` series held 9 requests with 0 s latency and no matching stderr entry (edge transport, never handler code). A 4xx-first alert policy cannot page honestly on this surface.
   - **Standing semantics.** 5xx (`response_code_class`) is the only signal that says "the function failed." 4xx stays visible in the dashboard's request-by-response-code tiles and CF logs, and the App Check / relationship rejection paths keep their own client-facing behavior; they are not incidents. A genuine deploy-wide break shows 5xx across routes; a client-transport blip shows 0 s-latency 500s with no stderr entry.
   - **2026-10-21 incident class (index builds, historical):** 19 incidents across Oct 15–21; the Oct 16–18 ones were `retryDispatchSweep` and the Oct 21 ones `dmsDeadlineWorker` failing with gRPC code 9 `FAILED_PRECONDITION … index is currently building`. Fixed at the source: scheduled handlers defer through index-build windows via `functions/index-build-guard.js` (ADR #202). Under the 5xx policy those ticks would no longer page regardless — the guard is still the correct behavior, the alert is now just honest about it.
3. **Alert policy — sweep failure (R-03):** `MyExodus: dispatch sweep not running (R-03)` — conditionAbsent on `retryDispatchSweep` execution_count for 30 min, severity WARNING (scheduler failure/delay signal).
   - **2026-10-18 refinement (success series):** the condition filter now also carries `metric.labels.status = "ok"` (ADR #180). Without it, the 2026-10-17 billing incident was anchored to the `status="error"` series; when executions resumed as `status="ok"` that series never returned, and Google's absent-data semantics close such an incident only after `autoClose + 24 h` (1 h + 24 h), so the recovery surfaced as a fresh-looking warning ~25 h after the fact. Pinning `status="ok"` also means a sweep that only errors (no success points) trips the alarm. `autoClose` remains 3600 s.
     - **Closure rules, verified against official docs (read 2026-10-18):** a time series is the metric type + one combination of label values, so `status="ok"` and `status="error"` are distinct series; an alert opens per series whose condition is met, one open alert per series. A metric-absence alert closes when **an observation occurs** for that series, or otherwise when **no observations arrive for 24 h after the policy's auto-close duration expires**. `alertStrategy.autoClose` defaults to **7 days** (minimum 30 min); a disabled policy's open alerts stay until it expires; snoozing the policy closes them; acknowledging an alert does not close it. Sources: "Behavior of metric-based alerting policies" and "Incidents and events" at `docs.cloud.google.com/monitoring/alerts/concepts-indepth` and `/alerts/incidents-events`.
4. **Alert policy — terminal delivery:** `MyExodus: relay gave up on a delivery (terminal)` — user log metric `sweep_terminal_count` sum over 1 h > 0, severity ERROR (a push permanently undelivered after the 8-attempt cap).
5. **Log-based counter metrics:** `sweep_terminal_count` (`jsonPayload.terminal > 0` on `dispatch sweep`), `relay_rate_limit_rejections` (label-extracted `pair_id`, `relayStatus="RATE_LIMITED"`), `dispatch_failures` (labels `terminal`, `provider`).
   - **2026-10-03 correction (ADR #131):** three of the five user log metrics — `dispatch_failures`, `relay_rate_limit_rejections`, `sweep_terminal_count` — were created filtering `resource.type="cloud_function"`, which **no gen2 log entry ever carries**. Platform metrics dual-emit under `cloud_function`, but log *entries* from gen2 functions are all `cloud_run_revision` — so those three could never match an entry, and the terminal-delivery alert they feed (item 4) was permanently blind. All five filters now read `resource.type="cloud_run_revision"` (the two pairing metrics already did) and were each verified by API re-read. Live filters as of 2026-10-03:
     - `dispatch_failures`: `resource.type="cloud_run_revision" AND jsonPayload.message="dispatch failed"`
     - `relay_rate_limit_rejections`: `resource.type="cloud_run_revision" AND jsonPayload.message="mailbox rejected" AND jsonPayload.relayStatus="RATE_LIMITED"`
     - `sweep_terminal_count`: `resource.type="cloud_run_revision" AND jsonPayload.message="dispatch sweep" AND jsonPayload.terminal > 0`
     - `pairing_idempotency_replay_count` / `pairing_transcript_mismatch_count`: unchanged (already `cloud_run_revision` + `service_name="registerpairing"`).
   - **Silent-when-empty is by design, not drift:** `retryDispatchSweep`'s `dispatch sweep` log line sits *after* the empty-queue fast path (`functions/dispatch-logic.js` returns before the `logger.info`), so a sweep with nothing stale emits no entry at all. Zero `dispatch sweep` entries across the project's history with an empty queue is correct; the R-03 absent-condition policy (item 3, platform metric) is what catches a genuinely dead sweep.
6. **Dashboard:** `MyExodus — §14 Monitoring` (`projects/913808045052/dashboards/1cf1a456-177d-4132-a078-5b98bed816e0`) — CF executions by status, terminal sweeps/hour, rate-limit rejections per pair, dispatch failures, plus a text tile mapping every §14 metric to its rail (Crashlytics client events, Firestore queries, this dashboard, billing).
7. **Budget alert:** see §3.
8. **Alert policy — callable rejection storm (account Release 0 §13.1 item 6, ADR #232):** log-based counter metric `logging.googleapis.com/user/callable_rejections` — filter `resource.type="cloud_run_revision" AND jsonPayload.message=~"rejected$"`, label `reason` from `jsonPayload.reason`. Counts *application-level* handler rejections (`registerPairing` / `appendUserEvent` / `submitHeartbeat` / pair-lifecycle / `sendNudge` / mailbox `rejected` lines). App Check 401s are **not** counted — they never reach a handler, which is exactly what keeps this metric off the noise floor that produced the retired error-rate policy (§4 item 2). Alert policy `projects/myexodus-53804/alertPolicies/9334481852107082372`: ALIGN_SUM over 900 s, REDUCE_SUM, `COMPARISON_GT 19` (fires at ≥20 rejections in 15 min), severity WARNING, `autoClose 1800s`, owner email channel. Threshold rationale: single-digit rejections a day are normal (the benign `terminatePair` `NOT_A_PAIR_MEMBER` after an identity rotation, one `INSTALL_NOT_REGISTERED` after a fresh install — both observed); 20+ in 15 min is a client loop, a callable regression rejecting valid input, or abuse. Created via APIs 2026-10-29 and read back enabled; the filter was verified by replaying real log entries (8 recent matches, including that day's `INSTALL_NOT_REGISTERED` and the 2026-10-23 `RATE_LIMITED` burst of seven in one second). A metric-based policy **rejects** `alertStrategy.notificationRateLimit` — only log-based alert policies may set one — so repeat emails are bounded by `autoClose` and the single-incident-per-series rule. **Rules-level denials have no server rail**: Firestore logs nothing for a denied write, so the client `firestore_denied` event (§1) is the only observer; Crashlytics exposes no API-level velocity alert for non-fatals (console velocity alerts remain an optional owner-side step).
9. **Alert policy — DMS fleet late-burst breaker (Step 8, ADR #201):** `MyExodus: DMS fleet late-burst breaker opened (Step 8)` — log-based condition on `resource.type="cloud_run_revision" AND jsonPayload.message="dms circuit breaker opened: fleet-wide late burst"`, severity ERROR, routed to the owner email channel above. Policy `projects/myexodus-53804/alertPolicies/12571310519408075705`, enabled and read back 2026-10-21. `alertStrategy` is `autoClose: 86400s` + `notificationRateLimit.period: 300s` — **a log-based policy is rejected at creation without an explicit rate limit** (learned live), and the 24 h auto-close means a persistent condition fires about once a day rather than continuously. The alert is the page; the worker also writes `config/dms.dispatch_hold=true`, and that hold persists until an operator releases it — acknowledging the alert is not releasing the fleet. The condition was verified enabled, not fired (no real fleet burst induced).

§18 line 649 (dashboards live with one day of real staging data) has no staging environment in
this project — the honest reading is: dashboards can go live on the production project
(single-user scale, real data), and the "staging day" criterion is a DoD item to be met or
explicitly deferred with the rest of §18.

## 5. Pairing-ceremony plan §14 (task #37, DONE 2026-09-28 — ADR #101)

*The pairing plan's own §14 (partner-pairing-implementation-plan.md lines 441–449, six bullets)
is a separate requirement set from the partner-pillar-v2 §14 above — it instruments the ceremony
(`registerPairing` + `PairingCeremonyScreen`), not the sync pillar. Wired per §7 step 16: after
the functional flow passed the emulator suite (ADR #100).*

| §14 bullet | Rail | Where |
|---|---|---|
| 1. Session outcomes by terminal state (+ CANCELLED reason) | Client event `pairing_ceremony_outcome outcome=<x>` per ceremony terminal, via `CrashReporting.nonFatal` (one issue class, metadata only). Terminals: `paired`, `sas_mismatch`, `expired`, `token_used`, `cancelled`, `failed`. Exactly-once per attempt: every path routes through one `reportOnce()` seam (`AtomicBoolean` CAS, reset on attempt start) so a double-tap on Cancel cannot double-count. Wire strings pinned by `PairingCeremonyTelemetryTest`. | `PairingCeremonyTelemetry` + `PairingCeremonyScreen` |
| 2. Cross-session per-reporting-UID mismatch counter (manual review, never a lock) | Log-based metric `pairing_transcript_mismatch_count`, labels `uid` (the reporter whose proof disagreed) + `pair_id`; emitted by the `registerPairing rejected reason=TRANSCRIPT_MISMATCH` warn line. 24h rolling view = the metric in the explorer with a 24h alignment; "distinct partners" = distinct `pair_id` values per `uid`. | metric: `logging.googleapis.com/user/pairing_transcript_mismatch_count` |
| 3. CF invocation counts / error rates / throttling | Native `run.googleapis.com/request_count` per `registerpairing` (labels `response_code`, `response_code_class`, `route`); dashboard tile "requests by response code" (ALIGN_SUM 300 s). 429 = `maxInstances` throttling → its own WARNING alert policy (fires on first 429 in 5 min, auto-close 30 min). Server-error coverage is the fleet-wide 5xx policy (§4 item 2 — `run.googleapis.com/request_count`, `response_code_class="5xx"`; the old `cloud_function` error-count policy was retired 2026-10-28 because 4xx client rejections counted as errors, ADR #224). | dashboard + policy `projects/myexodus-53804/alertPolicies/1164734849334613422` |
| 4. Idempotency cache-hit rate | Numerator: log-based metric `pairing_idempotency_replay_count` (the deployed `registerPairing idempotent replay` line). Denominator: bullet 3's request count. Dashboard tile shows the numerator; a rising ratio = client retry bug, not network. | metric: `logging.googleapis.com/user/pairing_idempotency_replay_count` |
| 5. Cold-start latency distribution | Platform metric `run.googleapis.com/container/startup_latencies` (DELTA distribution; verified carrying live data before the tile was built). Dashboard tile: p50 + p95. **No code was added** — a hand-rolled `bootMs` log line would measure process init, not container startup, and would be the dishonest version. | dashboard tile "cold start latency (p50/p95)" |
| 6. No SAS/tokens/signatures/key material in any label or log line | Contract enforced in code: `PairingCeremonyTelemetry` reports only `outcome=<enum>` (KDoc records Decision 12; the exact-string test would fail on any added field); server log lines carry `reason`/`pairId`/`uid` only, never protocol material; `failed` deliberately folds specific rejection causes into one token so they stay in logcat/CF logs and don't leak into metric labels. | reviewed in #37; pinned by test |

Vocabulary reconciliation (recorded, not silent): the plan's cancel reasons
`user_cancelled` / `superseded` / `process_restart_required` predate the shipped token-doc design —
`user_cancelled` is the only shipped reason; the other two have no counterpart (no single-active-
session lock to supersede; pending keys are in-memory so a restart drops the attempt and nothing
survives to require). A `CANCELLED`-reason breakdown metric is therefore unnecessary — there is
exactly one reason.

### 5.1 Honest limits
- Crashlytics gives **counts and issue grouping, not rates with denominators** — the outcome
  tile is console-side event counting; "paired rate" = `paired` count over total outcome count,
  computed by reading the console, not stored.
- The 429 policy is a **"the cap engaged" signal, not a saturation curve** (threshold >0 on a
  5-min sum): one throttled request pages, and no distinction is drawn between a single shed
  request and sustained shedding.
- Cold-start latency is **service-wide**: deploy-triggered instance starts are included alongside
  user-triggered ones. With containerConcurrency 80 a cold start serves many requests, so the
  distribution is the honest platform view of what a cold request might pay, not a per-request
  average.
- The mismatch counter's consumer is a **human reading a dashboard** (per §5.19: manual review,
  never an automatic lock) — the rail deliberately stops at "indexed and queryable."

## 6. Honest limits (recorded, not hidden) — partner-pillar-v2 §14 rails

- Crashlytics gives counts and issue grouping, **not percentiles** — T1/T3 "distributions"
  land as bucketed counts; p50/p95 targets are approximated by bucket shares.
- T3 is provider- and OS-side (FCM delivery → OS posting); no client or server clock pair can
  measure it honestly outside a controlled harness.
- XOS (the physical test device) dispatches no WorkManager jobs — the periodic telemetry rail
  only samples on OEMs that run WorkManager; noted for metric interpretation.

## 7. Partner-accountability operator kill switch (Step 12, ADR #207)

One boolean on the existing operator document — the same document the SOS switch lives on, no new dependency:

```
config/features.partnerAccountabilityEnabled   // absent doc or absent field = ENABLED; only explicit false = OFF
```

Flip with the Firestore REST recipe in the GCP API notes:

```
PATCH https://firestore.googleapis.com/v1/projects/myexodus-53804/databases/(default)/documents/config/features?updateMask.fieldPaths=partnerAccountabilityEnabled
{"fields":{"partnerAccountabilityEnabled":{"booleanValue":false}}}   // restore: true
```

What an explicit `false` stops, at three boundaries — all server-side, so it covers every installed build without a client release:

1. **New client sends** — the mailbox create rule denies every new envelope write (`firestore.rules`, "Step 12 operator gate"). The relay is `onDocumentCreated`, so it never triggers for a denied write.
2. **DMS notifications** — `dmsDeadlineWorker` returns `{ disabled: 'PARTNER_ACCOUNTABILITY' }` before any deadline transition or dispatch; nothing is written, nothing is pushed.
3. **In-flight pushes** — `retryDispatchSweep` returns before listing retryable records, so messages created before the flip are not retried while the feature is off.

What it does **not** do: it does not delete or rewrite anything (pairings, mailbox messages, acks, client outbox rows, DMS state and outbox rows all stay), it does not affect the SOS path (`partnerSosEnabled` is separate), and it cannot recall a push the provider already accepted.

Verification that a flip took effect (live-verified 2026-10-22):

- flag `false` → next 5-minute worker pass logs `dms worker disabled: partner accountability switched off`; next even-minute sweep pass logs `dispatch sweep disabled: partner accountability switched off` (the pass that ran before the flip logged `dms worker pass`).
- flag `true` → the next worker pass logs `dms worker pass` again.
- Client side: a denied send leaves its outbox row pending with jittered exponential backoff capped at 6 h; it drains after re-enable. A denied send does **not** raise the OPD #30 re-pair card (that card only observes pairing-document read denials, which this switch does not touch).

Rollback evidence and the measured rollout criteria are in plan Appendix S12; the decision record is ADR #207.

## 8. Release 3 operational rail — deletion / backup / transition metrics, alerts, runbooks and rollback (plan step 17, ADR #245)

*Built 2026-10-30 against the deployed `myexodus-53804` project through the Logging/Monitoring APIs (no console clicks). Every metric and policy below was created, then read back; the log filters were replayed against real entries where such entries exist (46 real `cloud_run_revision` entries from 2026-10-23 → 2026-10-30 matched the deletion/backup/transition/registration filter; 14 × `account deletion completed`, 2 × `account deletion accepted`, 1 × `guest data deletion accepted`, 9 × `prepareGuestTransition`, 9 × `finalizeGuestTransition`, 4 × `initializeBackupKeyBundle`, 4 × `updateBackupKeyBundle`, 3 × `deletion reconciliation`).*

The code rail added with this step (same ADR): the 15-minute reconciler now also detects the two §14 **Auth/profile mismatches** — a COMPLETED tombstone whose Auth user exists again (replaced atomically by a fresh QUEUED job, never deleted-then-created) and a `PENDING_DELETION` profile with no job (converged by creating the missing job). Both alert at ERROR and are counted by `auth_profile_mismatches`. `commitBackupManifest` now logs `commitBackupManifest object rejected` when `verifyObject` refuses an object, giving the hash-failure metric a real anchor. Session-pane blockers now report `session_cloud_blocked` / `session_pending_writes_blocked` non-fatals (the pre-Hilt purge cannot report itself — it runs before the telemetry opt-out is known).

### 8.0 Metric inventory (log-based; `resource.type="cloud_run_revision"`; DELTA/INT64 except where noted)

| Metric (`logging.googleapis.com/user/…`) | Anchor log line(s) | Consumer |
|---|---|---|
| `deletion_accepted` | `account deletion accepted`, `guest data deletion accepted` | dashboard lifecycle |
| `deletion_completed` | `account deletion completed` | dashboard lifecycle |
| `deletion_purge_incomplete` | `deletion purge incomplete; task will retry` | policy 8.1 |
| `deletion_job_stuck` | `deletion job stuck; manual intervention required` | policy 8.1 |
| `deletion_enqueue_failures` | `deletion requeue failed`, `auth deletion backstop enqueue failed` | policy 8.6 |
| `deletion_rejections` | `requestAccountDeletion rejected`, `requestGuestDataDeletion rejected` | dashboard (storm coverage is the existing callable policy) |
| `auth_profile_mismatches` | `auth profile mismatch: completed job with live auth`, `…pending profile without job`, `auth profile mismatch check failed` | policy 8.3 |
| `stale_guest_transitions` | `stale unfinalized guest transitions` | dashboard |
| `backup_reservations` | `beginBackup` | dashboard |
| `backup_committed` | `commitBackupManifest` | dashboard |
| `backup_commit_conflicts` | `commitBackupManifest conflict` | dashboard |
| `backup_manifest_hash_failures` | `commitBackupManifest object rejected` | policy 8.2 |
| `backup_rejections` | `beginBackup rejected` | dashboard |
| `backup_retention_failures` | `backup retention failed`, `backup retention object delete failed` | policy 8.4 |
| `backup_orphan_failures` | `backup orphan object delete failed` | policy 8.4 |
| `backup_orphan_scans_nonzero` | `backup orphan cleanup` where `jsonPayload.scanned>0` | policy 8.4 |
| `backup_orphan_scan_scanned` (DISTRIBUTION, `EXTRACT(jsonPayload.scanned)`) | `backup orphan cleanup` | explorer (bucket sizes) |
| `guest_transitions_prepared` / `…_finalized` / `…_cancelled` / `…_expired` | `prepareGuestTransition` / `finalizeGuestTransition` / `cancelGuestTransition` / `prepareGuestTransition expired` | dashboard |
| `pair_registrations` / `pair_registration_rejections` | `registerPairing` / `registerPairing rejected` | dashboard (storm alert = existing policy `9334481852107082372`) |

All 22 counters + the distribution metric were created via `logging.googleapis.com/v2/projects/myexodus-53804/metrics` PUT and read back present. A log-based metric updates within ~2 minutes of a matching entry; the aggregate has no data (not zero) until the first match — that is normal, not drift.

### 8.1 Policy `3380314823292222848` — deletion job stuck or repeatedly retrying (ERROR)

- **Conditions (OR):** `deletion_job_stuck` sum over 1800 s > 0 (the 12-requeue cap fires exactly once per job); `deletion_purge_incomplete` sum over 3600 s > 3 (a phase retried more than three times in an hour).
- **Operational SLO (defined here, referenced by §14):** acceptance → COMPLETED within **30 minutes**. The worker retries every few minutes up to 12 attempts (1 h cap); the reconciler re-enqueues a job stale for 10 minutes. Past 30 minutes without COMPLETED, a human reads the job.
- **Diagnose:** Firestore → `deletionJobs/{uid}`: `phase`, `requeueCount`, `updatedAt`. Storage-phase stalls are usually a bucket-permission or generation-mismatch error; PAIRS stalls are Firestore pagination/transaction errors. Logging filter `jsonPayload.message:"deletion"` around the job's window gives the phase error text (uid hashed).
- **Remediate:** fix the underlying cause (permissions, quota, code), then let the reconciler re-enqueue — **never** hand-delete the `deletionJobs/{uid}` document while the Auth user exists or holds data; that would abandon a half purge. If the Auth user is already gone and the job is wedged on an unreachable phase, the reconciler's next pass still re-enqueues; a job at the cap logs `deletion job stuck` and requires the manual fix above.
- **Verify:** the job reaches COMPLETED, `deletion_completed` increments, and the Auth user is gone (`getUser` → not-found).

### 8.2 Policy `15389505154354957982` — backup manifest hash verification failures (ERROR)

- **Condition:** `backup_manifest_hash_failures` sum over 3600 s > 0.
- **Diagnose:** the warn line carries the uid hash and the HttpsError message (`BACKUP_OBJECT_MISSING`, `BACKUP_OBJECT_MISMATCH`, `BACKUP_TRANSFER_INVALID`). Compare the reservation's `objectBytes`/path with the GCS object metadata (`gsutil ls -L`-equivalent via the Storage API) — a mismatch means the client uploaded different bytes than it declared, or the object was replaced between upload and commit.
- **Remediate:** the manifest was **not** committed, so the pointer still names the previous committed revision; nothing is lost. Have the client retry the upload (a fresh reservation) or fix the client-side hash/size computation. Never hand-write a manifest.
- **Verify:** the next `commitBackupManifest` logs at INFO with a revision, and `backup_committed` increments.

### 8.3 Policy `15389505154354958125` — Auth/profile mismatch detected (ERROR)

- **Condition:** `auth_profile_mismatches` sum over 3600 s > 0.
- **What it means:** the reconciler either (a) found a COMPLETED tombstone whose Auth user exists again — the uid was recreated out of band after "deletion" — and replaced the tombstone with a fresh QUEUED job; (b) found a `PENDING_DELETION` profile with no job (damaged/operator state that would deny cloud operations forever) and created the missing job; or (c) could not read Auth for a uid (logged separately, retried next pass).
- **Diagnose:** `deletionJobs/{uid}` now exists at QUEUED for (a)/(b); confirm the follow-up job completes. For (a), check who recreated the Auth user (Admin console audit / `firebase auth:export`) — recreation of a deleted uid is never expected from this app.
- **Remediate:** let the converged job run; if recreation keeps happening, stop the recreating actor first (the reconciler will keep replacing tombstones for as long as the uid lives).
- **Verify:** job COMPLETED, Auth uid absent, the next reconciliation pass logs nothing for that uid.

### 8.4 Policy `2631685325467471086` — backup retention/orphan cleanup failing or orphans accumulating (WARNING)

- **Conditions (OR):** `backup_retention_failures` sum over 6 h > 0; `backup_orphan_failures` sum over 6 h > 0; `backup_orphan_scans_nonzero` sum over 24 h > 20.
- **Diagnose:** both failure lines carry a uid hash. Delete failures are usually GCS permission/generation races (the code deletes `ifGenerationMatch` and treats 404 as success). `backup orphan cleanup` logs `{scanned, cleaned}` on every pass — repeated passes with `scanned > 0` and `cleaned: 0` mean deletions are silently failing (the failure line says which step).
- **Remediate:** restore the service account's `storage.objects.delete` capability on the bucket; if an object is stuck on a generation guard, delete that exact generation by hand (this is the safe manual path — object identity is exact). Retention never deletes a manifest that is current or pinned by a live restore; deleting objects by hand must follow the same rule.
- **Verify:** the next `backup orphan cleanup` pass reports `cleaned == scanned`, and the metric stops growing.

### 8.5 Policy `16442835332106904175` — unexpected backup storage growth (WARNING)

- **Condition:** `storage.googleapis.com/storage/total_bytes` (GAUGE, `gcs_bucket` = `myexodus-53804.firebasestorage.app`) ALIGN_MAX 86400 s > 512 MiB. This is an **absolute cap, not a delta** — the Monitoring API rejects `ALIGN_DELTA` on a GAUGE/DOUBLE metric (learned live while creating the policy), and a percent-change condition is undefined from zero. 512 MiB is many multiples of the data envelope: 21 MiB max object × 3 retained objects per account.
- **Diagnose:** list the bucket prefix by uid (`backups/{uid}/`) and compare object counts to active accounts (Firestore `users` count). Orphans (no manifest/reservation) should already have been swept by the 6-hour cleanup; hundreds of uncommitted uploads point at a client retry loop, which also shows as `backup_orphan_scans_nonzero` and `backup_commit_conflicts`.
- **Remediate:** fix the upload loop at the client, let the orphan sweep catch up, and (only after confirming no manifest references them) delete stray generations.
- **Verify:** total_bytes plateaus; `backup_orphan_scans_nonzero` returns to zero.

### 8.6 Policy `14722285015033791067` — deletion task or enqueue failures (WARNING)

- **Conditions (OR):** Cloud Tasks `queue/task_attempt_count` with `response_code != "ok"` for `processAccountDeletion`, sum over 3600 s > 3; `deletion_enqueue_failures` sum over 3600 s > 0.
- **Diagnose:** Cloud Tasks console/API for the queue shows the task's last response (5xx = worker crash/timeout, 429 = dispatch concurrency/queue throttling, 403 = service-account permission). Enqueue failures are logged with the reason string (`deletion requeue failed` / `auth deletion backstop enqueue failed`) and are recoverable — the job document is durable and the reconciler retries.
- **Remediate:** for dispatch failures, check the worker's logs for the same window (phase errors surface as `deletion purge incomplete`); for permission errors, restore the Cloud Tasks enqueuer role. Do **not** delete the queue: the reconciler would recreate tasks but the queue's retry config is part of the RFC contract.
- **Verify:** non-ok attempts stop; `deletion_completed` increments for the affected job(s).

### 8.7 Client-only rails (no server trace — honest limit)

Two §14 alert items observe things the server cannot see. They are Crashlytics non-fatals, not Cloud Monitoring metrics:

| §14 item | Event | Why no server rail |
|---|---|---|
| Spike in rules permission denials after Release 0 | `firestore_denied` (via `awaitReportingDenial`, already wired) | Firestore evaluates rules on the client SDK path and denies without reaching any Admin handler; Logging has no per-denial entry. |
| Repeated account-switch cache-clear failures | `session_cloud_blocked`, `session_pending_writes_blocked` (added with this step) | The legacy-cache purge runs in `Application.onCreate` **before Hilt**, so it cannot use the opt-out-aware `CrashReporting`; the manager reports the blocked state once startup has read the opt-out. |

Crashlytics velocity alerts (e.g. "more than N events of issue X in an hour") are configured **in the Firebase console only** — the Crashlytics API exposes no alert-policy resource. Owner action (one minute): console → Crashlytics → each issue above → Set alert. This is recorded as an owner-side step, not silently claimed as an API-created alert. The existing `callable_rejections` storm policy (item 8 in §4) still covers every callable-layer rejection, so a rules-only regression is the only class with no server-side proxy.

### 8.8 Rollback procedures (§13.5)

**Server gates (the only rollback control that covers every installed build).** Two Firestore documents, flipped through the REST recipe already used for the partner kill switch (§7):

```
PATCH https://firestore.googleapis.com/v1/projects/myexodus-53804/databases/(default)/documents/serverFeatureGates/backupUpload?updateMask.fieldPaths=enabled
{"fields":{"enabled":{"booleanValue":false}}}      // restore: true
PATCH …/documents/serverFeatureGates/guestTransition?updateMask.fieldPaths=enabled
```

- `backupUpload` off → new `beginBackup`, reservation extension and `commitBackupManifest` are denied `FEATURE_DISABLED` (`requireGate` inside each transaction). **Restore (`beginBackupRestore`/`endBackupRestore`), retention and orphan cleanup are not gated** — a rollback stops new backups while leaving read/restore and cleanup working.
- `guestTransition` off → `prepareGuestTransition` denied `GUEST_TRANSITION_DISABLED`; **finalize and cancel stay available**, so an in-flight transition can complete or cancel rather than being stranded.
- **Deletion is never gated.** `account-deletion.js` contains no `requireGate` call; the gate-independence test in `emulator-test/account-deletion.emulator.js` (`server feature gates off never disable deletion or reconciliation`) drives acceptance → worker → reconciler with both gates `false` and proves revocation, COMPLETED and requeue still happen. Disabling deletion, revocation, task enqueue or cleanup is **out of bounds for any rollback**.

**What a rollback must not do.**

- Do not roll `firestore.rules` back to the pre-R0 insecure profile/pair rules. Roll back clients/functions while retaining safe authorization; a rollback that needs loosened rules is not a permitted rollback.
- Do not delete the `deletionJobs` collection, `processAccountDeletion` queue, or scheduled reconciler to "stop" anything — those are the cleanup guarantees.
- Do not revoke the service account's Storage/Tasks roles to disable a feature; that breaks cleanup (8.4/8.6).

**Client / APK.** Record per deployed APK: version code, minimum supported version and the exact feature set. Keep the previous signed APK available in the direct-distribution channel (GitHub Releases, ADR #171; Play is not used — ADR #15). Client-side flags exist for UX but are **not** rollback controls by themselves; the server gates above are. No self-updater exists or is added here (§13.5).

**Minimum client version record.** When a client rollout must be reverted, bump `clientCompatibility/current` (`{ minimumVersionCode, updateMessage }`, published in R0) so older clients fail closed on server mutations rather than writing against a rolled-back contract. The read path, caching and fail-open/closed behavior are documented in `account-release0-inventory.md`.

**Verification after any rollback:** gates read back with `enabled:false`; a backup call returns `FEATURE_DISABLED`; a deletion request still accepts, revokes and completes (the emulator test above is the permanent regression); no policy in this section is disabled — the monitoring rail itself is never rolled back.

## 9. Diagnosing a tester report (operator runbook, added 2026-11-13)

When a tester reports "X doesn't work", collect first: app version (Settings, About), phone
model and Android version, what they did, when, and whether Settings' "Send anonymous
diagnostics" is on. The app never uploads local-only data (vault contents, App Lock state,
logcat), so the server and Crashlytics rails below are the observable surface.

**1. Server logs (functions + Cloud Run).** No gcloud is installed on the owner workstation;
mint a per-command access token from the firebase-tools stored refresh token and call the
Logging API directly (never print or persist the token):

```bash
TOKEN=$(curl -s -X POST https://www.googleapis.com/oauth2/v4/token \
  --data-urlencode "client_id=563584335869-fgrhgmd47bqnekij5i8b5pr03ho849e6.apps.googleusercontent.com" \
  --data-urlencode "client_secret=j9iVZfS8kkCEFUPaAeJV0sAi" \
  --data-urlencode "refresh_token=$(jq -r .tokens.refresh_token ~/.config/configstore/firebase-tools.json)" \
  --data-urlencode "grant_type=refresh_token" | jq -r .access_token)

curl -s -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: myexodus-53804" \
  -H "Content-Type: application/json" https://logging.googleapis.com/v2/entries:list -X POST -d '{
    "resourceNames":["projects/myexodus-53804"],
    "pageSize":50,
    "orderBy":"timestamp desc",
    "filter":"resource.type=\"cloud_run_revision\" AND severity>=WARNING"
  }' | jq -r '.entries[]? | [.timestamp, .jsonPayload.message // .textPayload // ""] | @tsv'
```

Adjust the filter to the report: `jsonPayload.message=~"rejected"` for callable rejections,
`resource.labels.service_name="sendNudge"` to scope one function, or
`timestamp>="2026-11-13T17:00:00Z"` for the report's time window. `firebase functions:log`
currently fails against this project ("Failed to retrieve log entries from Google Cloud"); the
REST call above is the working path (verified 2026-11-13).

**2. Client non-fatals.** Firebase console, project `myexodus-53804`, Crashlytics. There is no
CLI/API for reading Crashlytics issues; use the console. Every rail in §1 and §1a groups under
one issue class, `com.example.myexodus.telemetry.NonFatalEvent`. Search the issue's events for
the event string: `chain_incomplete`, `chain_tamper_suspected`, `partner_outbox_pending`,
`partner_waiting_recipient_key`, `partner_t1_ms`, `firestore_denied`, `trace:` handling, and the
feature outcomes `streak_create cause=...`, `relapse_log cause=...`, `rule_create cause=...`.
The message carries `oem=` and `api=`; the crash metadata adds app version and OS.

**3. Firestore state.** Console or REST
(`https://firestore.googleapis.com/v1/projects/myexodus-53804/databases/(default)/documents/<path>`)
with the same token. Useful paths: `pairings/{id}`, `users/{uid}/push_installations`,
`mailboxes/...`, `config/features` (the kill switches, §7).

**4. Alert policies already watching.** §4 item 8 (callable rejection storm, >=20 in 15 min),
§5 (pairing 429s), §8 (deletion/backup rails), plus the App Check verdict metric (owner ruling
on its threshold still pending, ADR #232 revision). A tester report that maps to one of these
usually has a matching incident in Cloud Monitoring.

   **Reading incident history.** There is no public incidents API: Monitoring v3 exposes only
policy config, and the console's `monitoring.clients6.google.com` host answers `Method not found`.
Cloud Logging carries the events instead — query both logs with the recipe above
(`orderBy timestamp asc`) and pair them per policy: `monitoring.googleapis.com%2FViolationOpenEventv1`
opens an incident, `monitoring.googleapis.com%2FViolationAutoResolveEventv1` closes it, and each
entry has `labels.policy_display_name`, `labels.policy_id`, `labels.violation_id` and a
`terse_message` with value vs threshold. A policy whose latest event is an Open with no later
resolve is the currently open incident. Verified 2026-11-13.

   **2026-11-13 read (retention window):** no new alert class. Two classes fired, both known.
(a) App Check attestation volume >70%: opened 11-12T12:44Z, auto-resolved 11-13T18:53Z once the
rolling verdict count dropped. (b) Callable rejection storm: cycled five times from 14:08Z; all
but two of that day's 1,000+ rejection entries came from ONE installation
(`ae3926b5-e7a1-454d-ab32-4355f6288dfa`, `ACTIVE_HARDWARE` — the physical phone, account created
2026-11-08), whose ~13 outbox rows re-sent the same `RECIPIENT_KEY_STALE` envelopes on a clean
900 s clock because that build predates the stale-key parking fix (`bc2f836`). 1.1.29 (published
after the fix, commit `d30f2b5`) parks those rows; the loop ends when the phone installs it.
Zero rejections came from the emulator.

**5. What the rails cannot see.** A device that opted out of diagnostics; anything local-only
(vault, App Lock, rules UI state); OEM-specific behavior with no server call. For those, ask the
tester for a screenshot or a screen recording where FLAG_SECURE allows it.

**6. GitHub Releases (the update channel).** Tester says "no update shows": check the live
envelope `https://github.com/Musila-ke/MyExodus-Releases/releases/latest/download/update-manifest.json`
(the app reads `releases/latest`, so a wrong tag or an artifact release promoted to latest
breaks discovery), then its `policySequence` against the installed `versionCode`, and the signed
release assets. The client also re-verifies a fresh policy immediately before installing. Full
runbook and pitfalls: `app-update-channel.md` §11.
